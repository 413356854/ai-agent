package ai.agent.service.prompt;

import ai.agent.entity.prompt.PromptVersion;
import java.util.List;
import java.util.Optional;

/**
 * 提示词版本的存储（阶段四第 3 件）。
 *
 * <h2>为什么用户会看到两份"提示词"表</h2>
 * 库里有两张表，但它们回答的是两个不同的问题（见 {@code db/prompt-schema.sql}）：
 * {@code prompt_version} 是"这一版是谁写的"，{@code prompt_active} 是"是谁让它生效的"。
 * 在灰度和回滚里这两件事经常不是同一个人，所以刻意没有合并。
 *
 * <h2>这个接口刻意只有"追加"与"换代"，没有"更新"和"删除"</h2>
 * 没有 {@code update(kind, version, content)}，也没有 {@code delete} —— 这不是还没写，
 * 而是这个功能能成立的前提：
 * <ul>
 *   <li><b>改一版 = 新增一版</b>。历史不被改写，"上一版是什么"永远可查；</li>
 *   <li><b>回滚也是新增一版</b>（正文复制自旧版、{@code forked_from} 记着来源），
 *       而不是"把指针挪回去"。因为一次回滚本身是一次<b>运维动作</b>，
 *       它必须留在时间线上 —— 否则事后看历史只会看到"第 5 版之后又出现了第 3 版的内容"，
 *       而"这是谁在什么时候回滚的"查不到（这与成本看板那条"调价新增一条、不改旧的"
 *       是同一条纪律：<b>会改写历史的操作，事后无法解释</b>）。</li>
 * </ul>
 *
 * <h2>激活不是"更新一行"，而是"一次带前置条件的切换"</h2>
 * {@link #activate} 带一个 {@code expectedVersion}。这不是多余的参数，而是
 * <b>并发下唯一能防住"静默覆盖"的手段</b>：两个人都打开了页面、都看到当前是第 3 版，
 * 甲切到第 4 版，乙随后切到第 5 版 —— 若不带前置条件，乙会成功，
 * 而<b>甲那次切换就被无声地覆盖了</b>（页面上看不到任何异常）。
 * 带上它，乙会拿到空结果，端点回 409："你看到的版本已经变了，刷新后再决定"。
 */
public interface PromptStore {

	/** 某一份提示词的全部版本，<b>版本号降序</b>（最新在前，界面直接照着渲染）。 */
	List<PromptVersion> versionsOf(PromptKind kind);

	/** 查某一版（用于详情、对比、回滚取源）。 */
	Optional<PromptVersion> find(PromptKind kind, int version);

	/** 当前生效的是哪一版。库是空的时候返回空。 */
	Optional<PromptVersion> active(PromptKind kind);

	/**
	 * 追加一版。
	 *
	 * <p>版本号由实现自己算（当前最大 +1），<b>调用方不许指定</b> ——
	 * 让调用方给号会让"两个人同时保存"写出两个同号版本，
	 * 而其中一条会被主键挡掉、表现为"保存失败了但不知道为什么"。
	 *
	 * @param tokenEstimate 由调用方（{@code PromptService}）用估算器算好传进来：
	 *        存储层不该依赖估算器，否则"换一个估算器"就变成"要动 SQL 层"
	 * @return 落库后的那一版（带上了真实的版本号与时间）
	 */
	PromptVersion save(PromptKind kind, String content, String note, String author, Integer forkedFrom,
			int tokenEstimate);

	/**
	 * 把某一份提示词切换到指定版本（切换本身<b>不新增</b>版本）。
	 *
	 * @param expectedVersion 调用方<b>以为</b>当前生效的版本号；为 {@code null} 表示不做检查
	 *                        （只用于首次初始化这种已知没有并发的场景）
	 * @return 切换后的生效版本；若目标版本不存在、或当前生效版本与 {@code expectedVersion} 不符，
	 *         返回 {@link Optional#empty()} —— 表示"你的前提已经过期，这次切换没有执行"
	 */
	Optional<PromptVersion> activate(PromptKind kind, int version, Integer expectedVersion);

}
