package ai.agent.prompt;

import java.util.List;
import java.util.Optional;

/**
 * 提示词版本管理的服务（阶段四第 3 件）。
 *
 * <h2>为什么"读当前内容"与"写新版本"放在同一个服务里</h2>
 * 因为它们必须共享同一个事实：<b>当前生效的是哪一版</b>。
 * 拆成两个类就会出现"读的那个类自己缓存了一份"——
 * 而缓存的那一份正是热更新失效的来源（本项目的成本看板已经踩过一次：
 * 一个"只在启动时算一次"的字段在真并发下会串味）。
 * 所以这一层刻意<b>不缓存任何正文</b>，每次调用都去库里读当前生效的那一版。
 *
 * <h2>热更新的边界写在这里，不要靠符号推理</h2>
 * 换一个提示词版本之后，<b>下一次</b>读取就会拿到新内容，但有三种情况<b>不会</b>立刻变：
 * <ol>
 *   <li><b>已经在跑的那次 Agent 运行</b>：它的系统消息在运行开始时就已经进了消息列表
 *       （{@code AgentLoop} 只在 {@code messages.isEmpty()} 时注入系统消息）。
 *       这是刻意的 —— 一次运行中途换提示词，会让同一份对话里前后两轮遵循不同的指令，
 *       于是报告里"它为什么这么选"变得无法解释。
 *       所以热更新的粒度是「<b>下一次运行</b>」，不是「下一步」；</li>
 *   <li><b>流式对话的历史消息</b>：系统消息每轮都会重新注入，所以下一轮就生效；</li>
 *   <li><b>已经算出去的 token 预算</b>：预算随正文长度变，
 *       所以本服务把 token 估算一起返回，让预算那一侧每次重新问 ——
 *       如果哪一天这里又变成"启动时算一次"，页面上「预算」那一格会与实际不符，
 *       而它不报错（见 {@code PromptBudgetReconcileTest} 钉的就是这个）。</li>
 * </ol>
 */
public interface PromptService {

	/**
	 * 当前生效的提示词正文。
	 *
	 * <p><b>这是全项目读提示词的唯一入口</b>（Agent 循环、对话、结构化输出都走它）。
	 * 兜底顺序：库里生效的那一版 → classpath 出厂文件 → 抛异常。
	 * 最后那一档刻意不"返回一个空字符串"：空系统提示词会让模型用默认人格回答，
	 * 而那看起来像"模型不听话"，实际是部署问题。
	 */
	String currentContent(PromptKind kind);

	/**
	 * 当前生效版本的 fingerprint（{@code "v3:" + hash}），读不到时抛异常。
	 *
	 * <p>它同时被用于两件事：断言"出站请求里的提示词就是库里那一版"，
	 * 以及判断"预算算的那一版"与"发出去的那一版"是不是同一版。
	 * 报错信息里带上它，是为了让"热更新没生效"这种事故一眼能看出卡在哪一段。
	 */
	String currentFingerprint(PromptKind kind);

	/** 当前生效的那一版；库里还没有时返回空（此时走 classpath 兜底）。 */
	Optional<PromptVersion> activeVersion(PromptKind kind);

	/** 某一份提示词的全部版本（版本号降序）。 */
	List<PromptVersion> versions(PromptKind kind);

	/** 当前生效的 token 估算（预算那一侧每次重新问它）。 */
	default int tokenCount(PromptKind kind) {
		return activeVersion(kind).map(PromptVersion::tokenEstimate)
				.orElseGet(() -> estimateFallback(kind));
	}

	/** 库里还没有这一份时的兜底估算（读 classpath）。 */
	int estimateFallback(PromptKind kind);

	/**
	 * 保存一个新版本（<b>不</b>自动激活）。
	 *
	 * <p>为什么不自动激活：保存与生效是两次决定。先存、看一眼篇幅变化、再决定生效 ——
	 * 这个顺序下"存错但不生效"是可恢复的；自动激活则让一次粘贴失误直接作用到线上。
	 * 而"保存后忘了激活"是可见的（列表里那一版不显示"生效中"），
	 * 两种失误的代价不对称。
	 *
	 * @throws PromptRejectedException 校验不通过（含为什么、以及每条判据的具体数字）
	 */
	PromptChange save(PromptKind kind, PromptValidator.Input input);

	/**
	 * 把某一版切成生效版本。
	 *
	 * @param expectedVersion 调用方以为的当前生效版本号，不传则不做并发检查
	 * @throws PromptConflictException 当前生效版本已不是 {@code expectedVersion}
	 *                                 （有人在你看页面的时候改过了）
	 */
	PromptVersion activate(PromptKind kind, int version, Integer expectedVersion, String author);

	/**
	 * 回滚到指定版本 —— <b>回滚也是一次新增</b>。
	 *
	 * <p>它等价于"把旧版正文复制成新的一版并激活"，而不是把指针挪回去。
	 * 理由见 {@link PromptStore} 的注释：一次回滚本身是一次运维动作，
	 * 事后必须能回答"是谁、什么时候、把哪一版拉回来的"。
	 * 落成数据就是新版本的 {@code forkedFrom = 被回滚到的版本}。
	 */
	PromptVersion rollback(PromptKind kind, int targetVersion, String author, String note);

	/**
	 * 版本对比（含逐行差异 + token 变化）。
	 *
	 * <p>放在后端而不是前端：差异的判断是"业务规则"（什么算改了一行），
	 * 放在界面上会变成两处实现，而两处迟早会不一致。
	 */
	PromptDiff diff(PromptKind kind, int fromVersion, int toVersion);

}
