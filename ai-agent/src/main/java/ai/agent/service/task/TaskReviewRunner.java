package ai.agent.service.task;

import ai.agent.config.AgentLoopProperties;
import ai.agent.entity.agent.AgentPermissionLevel;
import ai.agent.entity.agent.AgentRun;
import ai.agent.entity.agent.skill.SkillCatalog;
import ai.agent.entity.agent.task.AgentTask;
import ai.agent.entity.agent.task.TaskReviewPrompt;
import ai.agent.entity.tool.RunPermission;
import ai.agent.service.agent.AgentStopReason;
import ai.agent.service.agent.ResumableAgentService;
import ai.agent.service.prompt.PromptKind;
import ai.agent.service.prompt.PromptService;
import ai.agent.service.skill.SkillDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * <b>把一次判定跑成一次 Agent 运行</b> —— 需求歧义判定与任务点拆分共用的那一处。
 *
 * <h2>为什么判定要走执行那条链路</h2>
 * 判定要回答的问题不只是"这句话读不读得懂"：它还取决于<b>这次能用的技能与工具</b>
 * （要交付的东西没有任何技能覆盖，本身就说明需求没说清），而"能用哪些技能"是
 * 「执行者 → 他的角色 → 角色目录 → 那些目录下的技能」算出来的（{@link SkillDirectory}）、
 * "能用哪些工具"是注册表决定的。这两件事都不是一次裸模型调用能看见的。
 * 所以判定与执行同源：同一个 {@link ResumableAgentService}、同一条手写循环、同一份技能判据。
 *
 * <h2>与执行四处刻意不同</h2>
 * <ol>
 *   <li><b>档位是仅可查看</b>（不是配置里那一档）：判定只查不改。写类工具在这里会停下来等人确认，
 *       而判定阶段没有人等在页面前 —— 所以"判定顺手真的改了什么"结构上不会发生。
 *       ⚠️ 代价：模型若真的提出写操作，这次判定会停在人工闸门上（等
 *       {@code ai.agent.approval.timeout} 之后按拒绝处理），然后按"判定没给出结论"停住任务；
 *   <li><b>没有工作区</b>：这次要动哪个目录正是判定自己要回答的问题
 *       （它写在 {@code TaskBreakdown#workspaceDir} 里），所以判定手上还没有一个可信的目录 ——
 *       先算一个出来要额外跑一次拆分，而"猜一个"会让判定在一个执行根本不会碰的目录里查资料
 *       （那次查询的审计记录看起来完全正常）。文件类工具会拒掉这次调用（
 *       {@code FileAccessDeniedException.NO_WORKSPACE}，回填文本给的是
 *       "本次运行没有工作区目录"），判定按描述与技能正文判（提示词里就是这么写的）；
 *   <li><b>失败不写任何业务状态</b>：判定抛出去，任务留在原状态、下个周期重判
 *       （见 {@link AgentTaskAnalyst} 的接口注释）。这里只负责把"没给出结论"的各种停因
 *       翻译成一句能读的异常；
 *   <li><b>不回灌重试</b>：解析不出来就是这次没有结论，不拿两三轮 token 去救一条本来就读不懂的描述。
 * </ol>
 *
 * <h2>为什么不做"从 markdown 围栏里抠 JSON"</h2>
 * 抠 JSON 需要对"哪一段才是答案"做猜测，而猜错的方式是<b>得到一个看起来正确的结论</b>：
 * 判定结果直接决定要不要去问人、执行时动什么。相比之下"解析失败"是可恢复的
 * （任务停在原地、下一个周期再判一次），所以这条路刻意留白。
 */
@Component
public class TaskReviewRunner {

	private static final Logger log = LoggerFactory.getLogger(TaskReviewRunner.class);

	/**
	 * 判定的成本归因键。<b>与执行分开</b>（执行是 {@code agent-task}）：
	 * 判定与执行是两次运行、两类开销，混在一个键里之后"这个功能花了多少钱在判定上"就查不到了。
	 */
	public static final String INTENT = "agent-task-review";

	/**
	 * 会话 id 前缀 + 任务 id —— 与执行那一条<b>同一个前缀</b>
	 * （见 {@code DefaultAgentTaskExecution#CONVERSATION_PREFIX}）：
	 * 于是同一条任务的判定与执行在 trace 里串成一段，而不是两段互不相干的记录。
	 *
	 * <p>长度受 {@code agent_run.conversation_id}（64 字符）限制：前缀 5 字符 + 19 位 Long 上限。
	 */
	private static final String CONVERSATION_PREFIX = "task-";

	private final ResumableAgentService agent;

	private final SkillDirectory skillDirectory;

	private final PromptService prompts;

	private final AgentLoopProperties loopProperties;

	public TaskReviewRunner(ResumableAgentService agent, SkillDirectory skillDirectory, PromptService prompts,
			AgentLoopProperties loopProperties) {
		this.agent = agent;
		this.skillDirectory = skillDirectory;
		this.prompts = prompts;
		this.loopProperties = loopProperties;
	}

	/**
	 * 跑一次判定。
	 *
	 * @param task 判的是哪条任务：<b>执行者是它的任务人</b>（与执行、与授权判断同一个人 ——
	 *            技能是按"角色目录"筛的，所以"技能来自任务人"这一条本身已经隐含了
	 *            "这些目录他有权限"），<b>任务 id</b> 用来拼运行 id 前缀与会话 id
	 * @param instruction 这一次判定的输入（任务描述 / 已答复的确认项 / 输出契约）
	 * @param converter 模型输出的 JSON 结构，它的 schema 会被替换进系统提示词
	 * @param what 判定的名字（异常与日志里用，例如「需求歧义」）
	 */
	public <T> T judge(AgentTask task, String instruction, BeanOutputConverter<T> converter, String what) {
		SkillCatalog skills = skillsOf(task);
		// 【判定的前提：没有工作区 + 仅可查看】两件事都是刻意的，理由各有一条：
		//
		// ① **没有工作区**：这次要动哪个目录正是判定自己要回答的问题（它写在
		//    TaskBreakdown#workspaceDir 里），所以判定手上不可能有一个可信的目录。
		//    给一个"猜的"目录比不给更坏 —— 判定会在一个执行根本不会碰的目录里查资料，
		//    而那次查询的审计记录看起来完全正常。代价是文件类工具会拒掉这次调用
		//    （FileAccessDeniedException.NO_WORKSPACE，回填给模型的是"本次运行没有工作区目录"），
		//    判定只能靠任务描述与技能正文判（提示词里就是这么写的）。
		//
		// ② **仅可查看**：判定只查不改。写类工具在这里会停下来等人确认，而判定阶段没有人
		//    等在页面前 —— 于是"判定顺手真的改了什么"结构上不会发生。
		//    读操作在任何档位下都直接执行，所以只读工具不受影响。
		RunPermission permission = RunPermission.ofStored("", AgentPermissionLevel.READ_ONLY);
		// 【Schema 只出现在系统那一段】它由 BeanOutputConverter 从 record 生成，
		// 而"提示词里写的字段"与"代码解析的字段"必须是同一个东西 —— 所以唯一的来源是这里，
		// 用户那一段（instruction）只说"按系统提示词里那份 schema 输出"。
		// 两段都写一遍的话，两份 schema 迟早分叉，而分叉时的现象是"模型照一份输出、另一份解析不到值"。
		String system = TaskReviewPrompt.renderSystem(prompts.currentContent(PromptKind.TASK_REVIEW),
				converter.getJsonSchema());

		String runId = UUID.randomUUID().toString();
		log.info("任务 #{} 的判定「{}」开始：{} 步上限 / {} tokens 上限 / {}ms 上限，{}，技能 {}",
				task.id(), what, loopProperties.maxSteps(), loopProperties.maxTokens(),
				loopProperties.maxDurationMs(), permission.describe(), skills.describe());

		// 判定这条链路不带知识库材料（最后一个 null）：它只输出 JSON 结论，
		// 而"要不要查知识库"是执行那条链路的事
		AgentRun run = agent.runWithSkills(runId, instruction, null, INTENT,
				CONVERSATION_PREFIX + task.id(), null, permission, skills, PromptKind.TASK_REVIEW, system, null);

		if (!run.answered()) {
			// 【这里绝不返回默认结论】两个方向的默认值都是错的：默认"无歧义"会把没读懂的需求交给执行，
			// 默认"有歧义"会拿一个编出来的问题去问用户（见 AgentTaskAnalyst 的接口注释）。
			throw new IllegalStateException(
					("判定「%s」没有给出结论：停因 %s（runId=%s，%d 步、%d 次工具调用、%d tokens）。"
							+ "不做任何写入 —— 任务留在原状态，下一次调度会重判"
							+ "（要一起看这次判定看到了什么，请按 runId 查这次运行的 trace）")
							.formatted(what, stopReasonText(run.stopReason()), runId, run.steps().size(),
									run.toolCallCount(), run.totalTokens()));
		}

		log.info("判定「{}」完成：runId={} · {} 步 · {} 次工具调用 · {} tokens · {}ms", what, runId,
				run.steps().size(), run.toolCallCount(), run.totalTokens(), run.elapsedMs());
		return parse(converter, run.finalAnswer(), what, runId);
	}

	/**
	 * 这次判定用的技能目录：<b>任务人的</b>（不是发布人）。
	 *
	 * <p>与执行那一侧（{@code DefaultAgentTaskExecution#skillsOf}）是同一个人、同一条口径 ——
	 * 换成发布人就会出现"发布人的技能在任务人的目录下被执行"，而那些技能可能包含任务人自己都读不到的东西。
	 *
	 * <p>解析不出来（账号不存在 / 已停用 / 没有角色）时退成"没有技能"：
	 * 一条任务不该因为"任务人的权限此刻解析不出来"而判不动，真正的结论由授权那一格给。
	 * 这个纪律与 {@link SkillDirectory} 自己的说明一致（它落 INFO，这里不重复）。
	 */
	private SkillCatalog skillsOf(AgentTask task) {
		return task.assigneeId() == null ? SkillCatalog.none() : skillDirectory.forExecutor(task.assigneeId());
	}

	/**
	 * 解析成 record。
	 *
	 * <p>失败抛出去（不是返回一个空结论）：解析不出来意味着"这次判定没有结论"，
	 * 而调用方对此的处置是明确的 —— 任务留在原状态、下一个周期重判。
	 * 异常消息里带上模型的原始输出（截断）：不然"解析失败"这四个字无法回答
	 * "它到底输出了什么"，而排查这类问题只能靠那一句原文。
	 */
	static <T> T parse(BeanOutputConverter<T> converter, String raw, String what, String runId) {
		try {
			return converter.convert(raw);
		}
		catch (RuntimeException e) {
			throw new IllegalStateException(
					"判定「%s」失败：模型输出解析不出目标结构（%s）。运行 runId=%s。模型原始输出：%s"
							.formatted(what, rootMessage(e), runId, truncate(raw)));
		}
	}

	/** 停因的中文说明（异常消息里给的是人读的那一份，不是枚举名）。 */
	private static String stopReasonText(AgentStopReason reason) {
		return switch (reason) {
			case ANSWERED -> "ANSWERED（它给了答复，但答复是空的）";
			case EMPTY_RESPONSE -> "EMPTY_RESPONSE（上游返回了空响应）";
			case MAX_STEPS -> "MAX_STEPS（步数上限）";
			case MAX_TOKENS -> "MAX_TOKENS（token 上限）";
			case MAX_DURATION -> "MAX_DURATION（时长上限）";
			case DUPLICATE_ACTION -> "DUPLICATE_ACTION（同一个工具 + 同一份参数重复太多次）";
			case WAITING_FOR_APPROVAL -> "WAITING_FOR_APPROVAL（停在人工确认上 —— 判定阶段的档位是仅可查看，"
					+ "写类工具会停下来等人）";
			case MODEL_ERROR -> "MODEL_ERROR（模型调用失败）";
		};
	}

	/** 剥到最内层的异常消息，避免把整条 Reactor 包装链写进异常里。 */
	private static String rootMessage(Throwable e) {
		Throwable cause = e;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		String message = cause.getMessage();
		return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
	}

	private static String truncate(String raw) {
		if (raw == null) {
			return "(空)";
		}
		return raw.length() <= 500 ? raw : raw.substring(0, 500) + "…(已截断)";
	}

}
