package ai.agent.config;

import ai.agent.entity.agent.AgentPermissionLevel;
import ai.agent.entity.agent.task.AgentTask;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * <b>agent 任务执行</b>的配置（前缀 {@code ai.agent.task}）。
 *
 * <h2>为什么这几个值必须能改</h2>
 * 它们各自决定一件"跑起来才知道合不合适"的事：多久轮一次、一轮最多处理几条、
 * 拆到几个任务点就该让人确认计划、以及执行时拿哪一档权限。
 * 写死在代码里的话，调它们要重新打包 —— 而这几件事恰恰是要按真实任务反复试的。
 *
 * <h2>{@code permissionLevel} 的默认值是完全权限，理由不是"方便"</h2>
 * 任务执行是<b>无人值守</b>的：跑它的是调度线程，没有人在页面前。
 * 而除完全权限之外的两档下，每一次写操作都会停下来等人点头 ——
 * 调度线程会一直等到 {@code ai.agent.approval.timeout}（默认 120 秒）然后<b>按拒绝处理</b>。
 * 也就是说：默认档位不是"更安全"，而是"每一条要改文件的任务都必然失败"，
 * 且失败发生在暂停两分钟之后。
 *
 * <p>这个默认值成立的前提是它<b>不绕过任何人的决定</b>：任务在执行之前已经过了一格
 * "待授权"，那一格的答复就是人对这次执行的授权（见 {@code AgentTaskStatus} 的状态链）。
 * 要更严就把它改成 {@code WORKSPACE_WRITE} / {@code READ_ONLY}，并接受"写操作会停下来等人"。
 *
 * <p>取值认不出来<b>拒绝启动</b>（不是降成默认档）：一个拼错的档位
 * （{@code FULL_ACESS}）如果被宽容成"仅可查看"，现象是"任务全都卡在等审批"，
 * 而那个现象会被归因成审批功能坏了，没人会想到是自己拼错了。
 * 这与 {@code AgentPermissionLevel.parse} 对<b>库里历史值</b>的宽容是两个场景：
 * 那边没有调用方可以被告知，这里有一个配置文件可以当场报错。
 *
 * @param enabled 总开关。关掉时定时触发与"记录更新即调度"都不再执行任何任务
 *            （任务照常留在原状态，界面上的卡片不动）
 * @param agentId <b>本应用在 Agent 应用注册表里的 id</b>（字符串）：任务开始执行（平台第一次接手）时，
 *            它被写进 {@code agent_task.agent_id}。发布人不填那一列 —— 它要回答的是
 *            "这条任务交给哪个 Agent 应用"，而那个 id 是部署方才知道的东西。
 *            ⚠️ 那张注册表在另一个业务域里，本仓不校验这个 id 存不存在（判据与
 *            {@code agent_run.intent} 同一条：归因键的取值由业务侧定义）；
 *            只校验"填了没有、超没超列宽"—— 空值会让卡片上那一格什么都不是
 * @param fixedDelay 定时触发的间隔（默认 {@code 10m}）。它是<b>兜底</b>：
 *            正常情况下任务由"记录一更新就调度"推动，这个周期只负责捞起
 *            "上一次判定失败、或调度时进程正在重启"而漏掉的那些
 * @param maxTasksPerRound 一轮调度最多处理几条任务。
 *            它是<b>防打转</b>的：一轮里每条任务最多处理一次，但"处理完之后又变成待处理"的
 *            形态（例如两条任务互相触发）会让这一轮没有尽头 —— 撞到这个上限会留一行 WARN
 * @param planConfirmThreshold 拆出来的任务点达到几个就<b>要人确认计划</b>（默认 5）。
 *            依据是"人一次能看清的清单长度"：少于这个数，逐个点一遍比读一份计划再点头更省事
 * @param permissionLevel 执行时用的权限档位（枚举名，也认中文标签）。取值见上面的说明
 */
@ConfigurationProperties(prefix = "ai.agent.task")
public record AgentTaskProperties(boolean enabled, String agentId, Duration fixedDelay, int maxTasksPerRound,
		int planConfirmThreshold, String permissionLevel) {

	public AgentTaskProperties {
		agentId = agentId == null ? null : agentId.strip();
		if (agentId == null || agentId.isEmpty()) {
			// 空值不是"没配"，而是"配了一个什么都没有的应用 id"：它会被写进库里，
			// 卡片上显示成 agent#，而按它反查 Agent 应用永远查不到 —— 一个不报错的错
			throw new IllegalArgumentException("ai.agent.task.agent-id 必填（本应用在 Agent 应用注册表里的 id），"
					+ "它会在任务开始执行时写进 agent_task.agent_id");
		}
		if (agentId.length() > AgentTask.MAX_AGENT_ID_CHARS) {
			throw new IllegalArgumentException("ai.agent.task.agent-id 过长（%d 字符 > 上限 %d）—— "
					+ "agent_task.agent_id 是 VARCHAR(%d)"
					.formatted(agentId.length(), AgentTask.MAX_AGENT_ID_CHARS, AgentTask.MAX_AGENT_ID_CHARS));
		}
		if (fixedDelay == null || fixedDelay.isZero() || fixedDelay.isNegative()) {
			// 与 AgentApprovalProperties.timeout 同一条规矩：0/负数在这里的含义是"没有周期"，
			// 而那会让"记录更新即调度"失败之后没有任何兜底 —— 一条任务就此永远停住
			throw new IllegalArgumentException("ai.agent.task.fixed-delay 必须是正的时长（例如 10m）。"
					+ "它是判定的兜底周期：设成 0/负数等于「调度漏掉的那些再也不重试」");
		}
		if (maxTasksPerRound < 1) {
			throw new IllegalArgumentException("ai.agent.task.max-tasks-per-round 至少是 1，实际是 "
					+ maxTasksPerRound + "（它是一轮的上限，不是开关；要关掉整件事请用 ai.agent.task.enabled）");
		}
		if (planConfirmThreshold < 1) {
			throw new IllegalArgumentException("ai.agent.task.plan-confirm-threshold 至少是 1，实际是 "
					+ planConfirmThreshold + "（要表达「任何任务都要人确认计划」，请把阈值设成 1）");
		}
		requireKnownLevel(permissionLevel);
	}

	/** 这一档解析出来是什么（构造期已经校验过取值，这里不会拿到降级结果）。 */
	public AgentPermissionLevel level() {
		return AgentPermissionLevel.parse(permissionLevel);
	}

	/** 装配用的缺省值（测试与手工构造用；配置里这几个键都要写）。 */
	public static AgentTaskProperties defaults() {
		return new AgentTaskProperties(true, "1", Duration.ofMinutes(10), 20, 5, "FULL_ACCESS");
	}

	private static void requireKnownLevel(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new IllegalArgumentException("ai.agent.task.permission-level 必填，取值："
					+ availableLevels());
		}
		for (AgentPermissionLevel level : AgentPermissionLevel.values()) {
			if (level.name().equalsIgnoreCase(raw.strip()) || level.label().equals(raw.strip())) {
				return;
			}
		}
		throw new IllegalArgumentException("ai.agent.task.permission-level 的取值不认识：「%s」。可用取值（写枚举名或中文标签）：%s"
				.formatted(raw.strip(), availableLevels()));
	}

	private static String availableLevels() {
		return Arrays.stream(AgentPermissionLevel.values())
				.map(level -> level.name() + "=" + level.label())
				.collect(Collectors.joining("、"));
	}

}
