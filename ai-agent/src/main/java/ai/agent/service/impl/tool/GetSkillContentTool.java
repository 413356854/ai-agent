package ai.agent.service.impl.tool;

import ai.agent.config.SkillPromptProperties;
import ai.agent.entity.agent.skill.AgentSkill;
import ai.agent.entity.agent.skill.SkillCatalog;
import ai.agent.entity.model.tool.AgentToolDefinition;
import ai.agent.entity.model.tool.ToolHints;
import ai.agent.entity.tool.ToolCallContext;
import ai.agent.service.tool.AgentTool;
import ai.agent.service.tool.AgentToolResult;
import ai.agent.service.tool.workspace.RunContextBoundTool;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具 ⑩：<b>按名字取一条技能的正文</b> —— 让"技能"这件事对模型真的起作用的那一半。
 *
 * <h2>为什么技能要分成"目录"与"正文"两步</h2>
 * 技能目录（名字 + 说明）拼在系统提示词里，那时模型知道"有这么几条技能"；
 * 但正文可能有几万字，全部预先塞进提示词等于每一轮模型往返都付一遍 token，
 * 而绝大多数运行根本用不上其中任何一条。所以正文<b>按需取</b>：
 * 模型看到目录里某一条正对自己的任务，才花一次工具调用把它读回来。
 *
 * <p>代价是多一次往返。这个取舍与 {@code search_docs} 那类检索工具完全相同 ——
 * 区别只是这里的"库"是**按角色目录筛过的那一份**，而不是全库。
 *
 * <h2>★ 它只能取到"这次运行看得见"的那些</h2>
 * 候选来自 {@link SkillCatalog} —— 由 {@code SkillDirectory} 按执行者的角色目录前缀算出来。
 * 取一条不在其中的技能不会返回正文，而是回一句"这次的技能目录里没有它"并带上<b>可用的名字</b>：
 * 不带可用值的"没有"会让模型原样重试或换一个同样不存在的名字。
 *
 * <h2>目录怎么进到这把工具里：走 {@link #onContext(ToolCallContext)}，不走全局状态</h2>
 * 本类是 Spring 单例，同一时刻可能有多次运行在跑（虚拟线程），所以它<b>不能</b>有一个
 * "当前目录"的字段。目录由循环作为这次调用的上下文交进来
 * （{@link ToolCallContext#skillCatalog()}），本类按 runId 存下，取正文时再按同一次运行取回。
 *
 * <p><b>为什么目录不能靠 ThreadLocal 一路带着</b>：本项目阶段一的载体线程实验已经量过这类做法的代价 ——
 * 目录是<b>运行级事实</b>，它在多次调用之间都要成立，而 ThreadLocal 在异步/线程切换的链路上会丢。
 * 这里只用它记一个"本次调用属于哪次运行"的短命键，候选目录仍然按 runId 分开存。
 *
 * <h2>没有 runId 的链路（外部 MCP 客户端）拿不到技能</h2>
 * 那条链路没有"运行"这个对象，也就没有执行者可筛 —— 本类如实回一句
 * "这次调用没有技能目录"，而不是给它一份比授权更宽的清单。
 */
@Component
public class GetSkillContentTool implements AgentTool<GetSkillContentTool.Params>, RunContextBoundTool {

	private static final Logger log = LoggerFactory.getLogger(GetSkillContentTool.class);

	/** 没有运行 id 时用的键（外部 MCP 链路、执行器被单独调用）。这类调用拿不到技能目录。 */
	private static final String NO_RUN = "";

	/**
	 * 存"这次运行能看见哪些技能"的地方。
	 *
	 * <p>它是缓存而不是事实来源：事实在 {@link ToolCallContext} 里，每次调用都会重新放进来。
	 * 上限的存在是为了让"某个 runId 永远不来取"不会变成一处缓慢增长的内存 ——
	 * 一次运行最多几步，而一台机器上并发的运行数是有限的。
	 */
	private final Map<String, SkillCatalog> byRun = new ConcurrentHashMap<>();

	/**
	 * <b>当前线程上正在执行的那一次运行</b>。
	 *
	 * <h2>它解决的是什么问题</h2>
	 * 目录由 {@link #onContext(ToolCallContext)} 交进来，而取正文的 {@link #invoke(Params)}
	 * 拿不到那个上下文 —— 参数类型是给模型看的 schema，里面放 {@code runId}
	 * 会让模型多填一个它不该知道的字段（还会占 token、还会填错）。
	 * 所以这里只记一个**指向**：本线程现在跑的是哪一次运行，候选目录仍然按 runId 存在
	 * {@link #byRun} 里。
	 *
	 * <h2>为什么这次的 ThreadLocal 是安全的（而项目别处刻意不用它）</h2>
	 * 因为它的存活范围正好是"执行器调用工具"这一跳：{@code ToolCallExecutor.execute}
	 * 先调 {@code onContext}、紧接着在**同一个线程**上调 {@code invoke}
	 * （见那个类的门禁 5 与门禁 6）。它不跨线程、不跨请求、也不跨两次工具调用 ——
	 * 每次调用都会被重新写一遍，所以两个并发运行之间不会串味。
	 *
	 * <p>项目里被否掉的那类用法正好相反：把"当前上下文"放进去、指望它在异步链路与
	 * 虚拟线程切换之后还在（阶段一的载体线程实验量过那种做法的代价）。
	 * 这里的判据是<b>它会不会活过这一次调用</b> —— 会，就不该用 ThreadLocal。
	 */
	private final ThreadLocal<String> currentRun = new ThreadLocal<>();

	private final SkillPromptProperties properties;

	public GetSkillContentTool(SkillPromptProperties properties) {
		this.properties = properties;
	}

	@Override
	public String name() {
		return SkillCatalog.SKILL_TOOL_NAME;
	}

	@Override
	public AgentToolDefinition description() {
		return new AgentToolDefinition(name(),
				"按名字取回一条技能（skill）的完整正文，照着它做事。",
				"当系统提示词的「你当前可以使用的技能」那一节里有一条名字与说明正好对得上你手上的任务时使用 —— "
						+ "例如那一节里写着「日报汇总：把一天的进展汇成日报」，而用户要的就是这件事，"
						+ "那就先把它的正文取回来，按正文里的步骤做，而不是自己发挥。"
						+ "不确定该不该用时也可以取回来看一眼（它很便宜，只是读一次配置）。",
				"不要用它回答「有没有某个技能」之外的问题 —— 它不检索文档（那是 search_docs）、"
						+ "也不读工作区里的文件（那是 read_file）。"
						+ "目录里没有的技能不要猜名字：回填里会带着这次能用的全部名字。",
				"返回那条技能的正文（含它所在的文件夹与拥有者）；"
						+ "正文超过 " + properties.maxContentChars() + " 字符时截断，并在文本里说明还有多少没显示。",
				"名字不在这次的技能目录里 → 返回「没有这条」并列出可用名字，重试同样的名字不会有不同结果；"
						+ "这条技能存在但正文是空的 → 返回「它没有写正文」，那说明这条技能还没写完，"
						+ "请告诉用户而不是自己编一套步骤。",
				ToolHints.readOnly());
	}

	@Override
	public Class<Params> parameterType() {
		return Params.class;
	}

	/**
	 * 记下"这次运行能看见哪些技能"。
	 *
	 * <p>它由执行器在<b>授权判定之前</b>调用（见 {@code ToolCallExecutor} 的门禁 5），
	 * 所以无论这次调用最后走到哪个出口，记录都已经放下了。
	 */
	@Override
	public void onContext(ToolCallContext context) {
		if (context == null) {
			return;
		}
		String runId = key(context.runId());
		currentRun.set(runId);
		if (context.skillCatalog() == null) {
			// 上下文**在**、目录不在：那是"这一次运行没有执行者"（没传 executorId）。
			// 这时要把同一个 runId 上可能留下的旧记录清掉 —— 一次运行的前几步带了目录、
			// 后几步不带（不该发生，但真发生了）时，留着旧的会让工具返回
			// **这次运行已经不该看见的**技能，而那种越权在回填文本里看不出来
			byRun.remove(runId);
			return;
		}
		byRun.put(runId, context.skillCatalog());
		trim();
	}

	/**
	 * 取正文。
	 *
	 * <p><b>候选从哪来：{@code onContext} 刚刚在同一个线程上放下的那一份</b>
	 * （见 {@link #currentRun}）。三种情况各有明确行为，没有一种是"猜一个"：
	 * <ol>
	 *   <li><b>有目录</b>（正常运行、且指定了执行者）→ 在目录里按名字找；</li>
	 *   <li><b>目录是空的</b>（正常运行、但没传 {@code executorId}）→ 回一句"这次没有可用技能"。
	 *       它不是一个错误，而是"确实没有候选"；</li>
	 *   <li><b>本线程上没有那一次运行的记录</b>（有人绕过循环直接调执行器）→ 回"没有技能目录"。
	 *       <b>刻意不兜底成全库技能</b>：那会给一条没有执行者的链路一份比授权更宽的清单。</li>
	 * </ol>
	 */
	@Override
	public AgentToolResult invoke(Params parameters) {
		SkillCatalog catalog = byRun.get(key(currentRun.get()));
		if (catalog == null) {
			return AgentToolResult.Failure.notFound(
					"这次调用没有技能目录（它不经过 Agent 循环，或循环没有指定执行者）—— "
							+ "所以按名字取不到任何技能。请直接按你自己的判断完成任务。");
		}
		if (catalog.isEmpty()) {
			return AgentToolResult.Failure.notFound(
					"这次运行没有可用技能（没有指定执行者）—— 所以按名字取不到任何技能。"
							+ "请直接按你自己的判断完成任务。");
		}

		List<AgentSkill> hits = catalog.byNameAndFolder(parameters.name(), parameters.folder());
		if (hits.isEmpty()) {
			if (parameters.folder() != null && !parameters.folder().isBlank() && !catalog.byName(parameters.name()).isEmpty()) {
				// 名字有、文件夹不对：这是最容易自己改对的一种失败，所以把两个可用值都给出来
				List<String> folders = catalog.byName(parameters.name()).stream().map(AgentSkill::folder).distinct()
						.toList();
				return AgentToolResult.Failure.notFound(
						("技能「%s」在这次的技能目录里有，但不在文件夹「%s」下 —— 它在这几个文件夹里：%s。")
								.formatted(parameters.name(), parameters.folder(), folders));
			}
			return AgentToolResult.Failure.notFound(
					("这次的技能目录里没有叫「%s」的技能。%s")
							.formatted(parameters.name(), available(catalog)));
		}

		// 同名技能可以在不同文件夹下各有一条（唯一约束是"同一个人的同一个文件夹下不重名"），
		// 所以不指定 folder 时会命中多条。全部给出来而不是随便挑一条：
		// 挑错的那一条会让模型照着另一套步骤做事，而那种错在回填文本里看不出来
		StringBuilder sb = new StringBuilder();
		for (AgentSkill skill : hits) {
			sb.append(render(skill));
			sb.append("\n\n");
		}
		String payload = sb.toString().strip();
		log.info("get_skill_content name=\"{}\" folder={} 命中={} 运行={} 字符={}", parameters.name(),
				parameters.folder() == null ? "" : parameters.folder(), hits.size(),
				key(currentRun.get()).isEmpty() ? "(无运行)" : currentRun.get(), payload.length());
		return AgentToolResult.ok(payload);
	}

	/**
	 * 一条技能的正文。
	 *
	 * <p>截断必须在文本里说出来：模型会把"我看到的就是全部"当成事实，
	 * 于是一份被截掉一半的操作手册会被它当成完整流程照做 —— 而缺的那一半
	 * 往往正是"做之前要先确认什么"。
	 */
	private String render(AgentSkill skill) {
		String content = skill.content() == null ? "" : skill.content().strip();
		StringBuilder sb = new StringBuilder();
		sb.append("【技能】").append(skill.name()).append('\n');
		sb.append("文件夹：").append(skill.folder()).append(" · 拥有者：").append(skill.displayOwner()).append('\n');
		if (skill.description() != null && !skill.description().isBlank()) {
			sb.append("说明：").append(skill.description()).append('\n');
		}
		sb.append('\n');
		if (content.isEmpty()) {
			// 空正文不是错误，但它必须被说出来：编一套步骤比什么都不做更坏
			sb.append("（这条技能还没有写正文 —— 它目前只有名字与说明。"
					+ "请不要自己编一套步骤，把这件事实告诉用户。）");
			return sb.toString();
		}
		if (content.length() > properties.maxContentChars()) {
			sb.append(content, 0, properties.maxContentChars());
			sb.append("\n\n（正文过长，这里只显示了前 %d 个字符，共 %d 个字符 —— 剩下的 %d 个字符没有显示。）"
					.formatted(properties.maxContentChars(), content.length(),
							content.length() - properties.maxContentChars()));
			return sb.toString();
		}
		sb.append(content);
		return sb.toString();
	}

	/** 可用名字清单 —— 拒绝时必须带上它，否则模型只能再猜一个名字。 */
	private static String available(SkillCatalog catalog) {
		List<String> names = catalog.names();
		if (names.isEmpty()) {
			return "这次运行的技能目录是空的，请直接按你自己的判断完成任务。";
		}
		return "这次能用的技能是：" + String.join("、", names) + "。";
	}

	private static String key(String runId) {
		return runId == null || runId.isBlank() ? NO_RUN : runId;
	}

	/**
	 * 把太久没人取的记录丢掉。
	 *
	 * <p>上限取 200 的依据：并发运行的量级由 {@code ai.agent.concurrency} 那一族限制住，
	 * 而一次运行只会在开始时放一条 —— 200 远高于同时活跃的运行数，
	 * 于是它只会清掉"早就结束、再也没人取"的那些。
	 */
	private void trim() {
		if (byRun.size() <= 200) {
			return;
		}
		// ConcurrentHashMap 的迭代顺序不确定，所以这里丢掉的是"任意一些"而不是"最旧的"。
		// 这是刻意的：要按时间淘汰就得再存一份访问时间，而在这一点上它不影响正确性 ——
		// 下一次调用会把这次运行的目录重新放回来（onContext 在每次执行之前都会走一遍）
		int excess = byRun.size() - 200;
		for (String key : byRun.keySet()) {
			if (excess <= 0) {
				break;
			}
			if (!NO_RUN.equals(key) && byRun.remove(key) != null) {
				excess--;
			}
		}
	}

	/**
	 * 参数。
	 *
	 * <p>{@code folder} 可空：绝大多数情况下同名技能只有一条，让模型每次多填一个文件夹
	 * 只会多一次犯错的机会。只在"这次的技能目录里同一个名字出现了多次"时它才必要 ——
	 * 而那时回填文本会把几个文件夹都列出来。
	 */
	public record Params(
			@NotBlank(message = "技能名不能为空")
			@Size(max = 128, message = "技能名最长 128 字（与库里的列宽一致）")
			String name,

			@Size(max = 192, message = "文件夹最长 192 字（与库里的列宽一致）")
			String folder) {
	}

}
