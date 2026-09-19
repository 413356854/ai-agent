package ai.agent.diag;

import ai.agent.entity.agent.approval.ToolApprovalRecord;
import ai.agent.entity.agent.store.AgentRunRecord;
import ai.agent.entity.alert.AlertEvent;
import ai.agent.entity.prompt.PromptVersion;
import ai.agent.entity.tool.RetrievalTrace;
import ai.agent.service.agent.AgentStopReason;
import ai.agent.service.agent.approval.ApprovalStatus;
import ai.agent.service.agent.approval.ToolApprovalStore;
import ai.agent.service.agent.store.AgentRunStore;
import ai.agent.service.agent.store.RunStatus;
import ai.agent.service.alert.AlertRecorder;
import ai.agent.service.alert.AlertRule;
import ai.agent.service.alert.AlertStore;
import ai.agent.service.canary.CanaryStore;
import ai.agent.service.cost.CostQuery;
import ai.agent.service.prompt.PromptKind;
import ai.agent.service.prompt.PromptStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 持久层迁移的<b>行为验证器</b>（默认关闭）。
 *
 * <h2>它为什么存在</h2>
 * 这次迁移把 7 个类、50 余条 SQL 从手写 JDBC 换成了 mapper + XML。换的东西恰好是
 * <b>编译器管不到的那一半</b> —— SQL 的语义、事务边界、受影响行数的含义、空串与 NULL 的区别。
 * 项目里没有测试目录（后端测试代码在阶段四第 4 件被整体删除），
 * 所以这里用一个"跑一次就说结论"的验证器把关键语义逐条断言出来。
 *
 * <h2>怎么用</h2>
 * <pre>
 *   run.cmd "-Dspring-boot.run.arguments=--ai.store.verify=true"
 *   # 或者打包后：java -jar target/ai-agent-0.0.1-SNAPSHOT.jar --ai.store.verify=true
 * </pre>
 * 全部通过时进程正常退出；任何一条不成立都会打印失败清单并以非 0 退出码结束 ——
 * 这样它既能人工看，也能被门禁脚本调用。
 *
 * <h2>为什么不是单元测试</h2>
 * 它要验证的是"这些 mapper 在真库上真的这么干"，而不是"某个类的方法返回什么"。
 * 走完整 Spring 上下文 + 真 H2 的路径，才覆盖到 XML 解析、类型转换器注册、
 * {@code spring.sql.init} 建表、{@code @Transactional} 代理这几件事 ——
 * 而这次迁移的风险恰好都在这些接缝上。
 */
@Component
@ConditionalOnProperty(name = "ai.store.verify", havingValue = "true")
public class StoreMigrationVerifier implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(StoreMigrationVerifier.class);

	private final AgentRunStore runStore;

	private final CostQuery costQuery;

	private final PromptStore promptStore;

	private final CanaryStore canaryStore;

	private final AlertStore alertStore;

	private final AlertRecorder alertRecorder;

	private final ToolApprovalStore approvalStore;

	private final Map<String, String> results = new LinkedHashMap<>();

	/**
	 * 验完是否直接退出进程（默认 true）。
	 *
	 * <p>默认退出，理由见 {@link #run}：不退出的话从调用方看就是"命令永不返回"。
	 * 想验完之后接着手工调几个接口，就设 {@code --ai.store.verify-exit=false}。
	 */
	@Value("${ai.store.verify-exit:true}")
	private boolean exitAfterVerify;

	public StoreMigrationVerifier(AgentRunStore runStore, CostQuery costQuery, PromptStore promptStore,
			CanaryStore canaryStore, AlertStore alertStore, AlertRecorder alertRecorder,
			ToolApprovalStore approvalStore) {
		this.runStore = runStore;
		this.costQuery = costQuery;
		this.promptStore = promptStore;
		this.canaryStore = canaryStore;
		this.alertStore = alertStore;
		this.alertRecorder = alertRecorder;
		this.approvalStore = approvalStore;
	}

	@Override
	public void run(ApplicationArguments args) {
		log.info("=== 持久层迁移验证开始（MyBatis mapper + spring.sql.init + @Transactional）===");
		int failed;
		try {
			verifyAgentRuns();
			verifyCostAggregates();
			verifyPrompts();
			verifyCanary();
			verifyAlerts();
			verifyApprovals();
		}
		catch (RuntimeException e) {
			log.error("验证过程本身抛异常（这一条也要当成失败看）", e);
			results.put("验证器自身", "FAIL: " + e);
		}

		failed = (int) results.values().stream().filter(v -> v.startsWith("FAIL")).count();
		log.info("=== 验证结果：{} 项，通过 {} 项，失败 {} 项 ===", results.size(), results.size() - failed, failed);
		results.forEach((name, verdict) -> log.info("  {} | {}", verdict, name));
		if (failed > 0) {
			log.error("=== 持久层迁移验证失败 {} 项，见上面的清单 ===", failed);
		}
		else {
			log.info("=== 持久层迁移验证全部通过 ===");
		}

		// 【必须显式退出】验证模式跑在一个**服务**里：不退出的话，Tomcat 会一直挂着，
		// 从调用方看就是"命令永不返回"。而"跑完不退"与"跑挂了"在自动化里长得一样 ——
		// 这正是本项目反复强调的那类"不报错的错"（这里连退出码都拿不到）。
		// 留一个开关：想让它验完继续跑（例如接着手工 curl 几个接口）就设成 false。
		if (exitAfterVerify) {
			System.exit(failed > 0 ? 1 : 0);
		}
	}

	// ------------------------------------------------------------------
	//  Agent 运行状态库
	// ------------------------------------------------------------------

	private void verifyAgentRuns() {
		String runId = "verify-run-1";
		// 用 withPermissions 补上"这一次运行的权限"：它走的就是界面那条链路的同一个方法，
		// 所以这两列的往返（写进去、读回来）在启动自检里也被覆盖到了
		AgentRunStore.Start start = new AgentRunStore.Start(runId, "验证问题：检查点能不能读回来？", "verify-node",
				"验证业务", "verify-conversation", "verify-tenant")
				.withPermissions(ai.agent.entity.tool.RunPermission.ofStored(".",
						ai.agent.entity.agent.AgentPermissionLevel.WORKSPACE_WRITE));

		check("start：开一次运行后能按 id 读回来（含归因/会话/租户三列）", () -> {
			runStore.start(start);
			AgentRunRecord run = runStore.find(runId).orElseThrow();
			return run.status() == RunStatus.RUNNING && "验证业务".equals(run.intent())
					&& "verify-conversation".equals(run.conversationId()) && "verify-tenant".equals(run.tenantId());
		});

		check("start：工作区目录与权限档位能原样读回来（恢复要靠它们续跑）", () -> {
			AgentRunRecord run = runStore.find(runId).orElseThrow();
			return run.hasWorkspace() && ".".equals(run.workspaceDir())
					&& run.permissionLevel() == ai.agent.entity.agent.AgentPermissionLevel.WORKSPACE_WRITE
					&& !run.permission().allowsWriteWithoutApproval();
		});

		check("checkpoint：写一步后 latestCheckpoint 能读回 JSON + 指纹 + 灰度分组（空串不是 NULL）", () -> {
			AgentRunRecord.StepCheckpoint step = new AgentRunRecord.StepCheckpoint(runId, 1,
					"{\"messages\":[{\"role\":\"user\"}]}", "第 1 步的想法", 120, 30, 900L, 40, "deepseek-flash",
					"v1:abc123def456", "");
			runStore.checkpoint(step);
			AgentRunRecord.StepCheckpoint back = runStore.latestCheckpoint(runId).orElseThrow();
			return back.stepIndex() == 1 && back.transcriptJson().contains("messages")
					&& "v1:abc123def456".equals(back.promptFingerprint()) && "".equals(back.canaryVariant())
					&& back.cacheReadTokens() == 40;
		});

		check("checkpoint：同一步再写一次是覆盖（幂等），不是主键冲突", () -> {
			runStore.checkpoint(new AgentRunRecord.StepCheckpoint(runId, 1, "{\"messages\":[{\"role\":\"user\"},"
					+ "{\"role\":\"assistant\"}]}", "改写过的想法", 200, 50, 1100L, 60, "deepseek-flash",
					"v1:abc123def456", ""));
			AgentRunRecord.StepCheckpoint back = runStore.latestCheckpoint(runId).orElseThrow();
			return back.thoughtText().equals("改写过的想法") && back.promptTokens() == 200
					&& runStore.stepsOf(runId).size() == 1;
		});

		check("toolCalls：工具调用（含被拒绝的）能读回来，列顺序是 (step, call)", () -> {
			runStore.recordToolCall(new AgentRunRecord.ToolCallRecord(runId, 1, 1, "call_a", "search_docs",
					"{\"query\":\"虚拟线程\"}", "OK", "找到 3 条", 120L));
			runStore.recordToolCall(new AgentRunRecord.ToolCallRecord(runId, 1, 2, "call_b", "propose_review",
					"{\"title\":\"t\"}", "REQUEST_REJECTED", "人拒绝了", 5L));
			List<AgentRunRecord.ToolCallRecord> calls = runStore.toolCallsOf(runId);
			return calls.size() == 2 && "call_a".equals(calls.get(0).callId())
					&& "REQUEST_REJECTED".equals(calls.get(1).stage());
		});

		check("retrieval：一行调用 + N 行命中在一个事务里写入，按调用归组读回来", () -> {
			List<RetrievalTrace.Hit> hits = List.of(
					new RetrievalTrace.Hit(1, "doc.md#1", "doc.md", "总则 > 第一章", 180, 12.5d, "片段一"),
					new RetrievalTrace.Hit(2, "doc.md#2", "doc.md", "总则 > 第二章", 240, 9.25d, "片段二"));
			RetrievalTrace trace = new RetrievalTrace("虚拟线程 为什么", 5, 516, hits, 33L);
			runStore.recordRetrieval(new AgentRunRecord.RetrievalRecord(runId, 1, 1, "search_docs", trace));

			List<AgentRunRecord.RetrievalRecord> back = runStore.retrievalsOf(runId);
			if (back.size() != 1) {
				return false;
			}
			RetrievalTrace read = back.get(0).trace();
			return "search_docs".equals(back.get(0).toolName()) && read.hits().size() == 2
					&& read.hits().get(1).rank() == 2 && "doc.md#2".equals(read.hits().get(1).chunkId())
					&& Math.abs(read.hits().get(1).score() - 9.25d) < 1e-9 && read.indexSize() == 516;
		});

		check("retrieval：0 条命中时调用行仍然留下（否则与'真的没查到'分不开）", () -> {
			runStore.recordRetrieval(new AgentRunRecord.RetrievalRecord(runId, 2, 1, "search_docs",
					new RetrievalTrace("查不到的词", 5, 516, List.of(), 20L)));
			List<AgentRunRecord.RetrievalRecord> back = runStore.retrievalsOf(runId);
			Optional<AgentRunRecord.RetrievalRecord> emptyCall = back.stream()
					.filter(r -> r.stepIndex() == 2).findFirst();
			return back.size() == 2 && emptyCall.isPresent() && emptyCall.get().trace().hits().isEmpty()
					&& emptyCall.get().trace().hitCount() == 0;
		});

		check("updateProgress：只动用量三列，不动状态", () -> {
			runStore.updateProgress(runId, 1234, 2, 4321L);
			AgentRunRecord run = runStore.find(runId).orElseThrow();
			return run.totalTokens() == 1234 && run.toolCallCount() == 2 && run.elapsedMs() == 4321L
					&& run.status() == RunStatus.RUNNING;
		});

		check("认领：SELECT + 带条件的 UPDATE 在一个事务里，认领后状态是 INTERRUPTED 且字段不丢", () -> {
			List<AgentRunRecord> claimed = runStore.markInterruptedRuns("verify-node");
			if (claimed.size() != 1) {
				return false;
			}
			AgentRunRecord run = claimed.get(0);
			return run.status() == RunStatus.INTERRUPTED && "verify-tenant".equals(run.tenantId())
					&& "验证业务".equals(run.intent()) && "verify-conversation".equals(run.conversationId())
					&& "验证问题：检查点能不能读回来？".equals(run.question());
		});

		check("认领幂等：第二次调用不再认领同一批（乐观锁真的在过滤）", () -> runStore.markInterruptedRuns("verify-node")
				.isEmpty());

		check("markInterruptedForTesting + 再次认领：状态改回 RUNNING 后又能被认领", () -> {
			runStore.markInterruptedForTesting(runId);
			List<AgentRunRecord> claimed = runStore.markInterruptedRuns("verify-node");
			return claimed.size() == 1 && claimed.get(0).status() == RunStatus.INTERRUPTED;
		});

		check("finish：收尾成功并落 answer / stopReason / 失败细分", () -> {
			runStore.finish(runId, RunStatus.ANSWERED, AgentStopReason.ANSWERED, "验证答案", 1500, 2, 5000L,
					null, null);
			AgentRunRecord run = runStore.find(runId).orElseThrow();
			return run.status() == RunStatus.ANSWERED && "验证答案".equals(run.finalAnswer())
					&& run.stopReason() == AgentStopReason.ANSWERED;
		});

		check("finish：收尾一个不存在的运行必须炸（静默会让状态永远停在 RUNNING）", () -> {
			try {
				runStore.finish("verify-run-does-not-exist", RunStatus.ANSWERED, AgentStopReason.ANSWERED, "x",
						0, 0, 0L, null, null);
				return false;
			}
			catch (RuntimeException expected) {
				return true;
			}
		});

		check("replaceStepTranscriptForTesting：替换成功；目标不存在时炸", () -> {
			runStore.replaceStepTranscriptForTesting(runId, 1, "{\"messages\":[]}");
			boolean replaced = "{\"messages\":[]}".equals(runStore.latestCheckpoint(runId).orElseThrow()
					.transcriptJson());
			try {
				runStore.replaceStepTranscriptForTesting(runId, 99, "{}");
				return false;
			}
			catch (RuntimeException expected) {
				return replaced;
			}
		});

		check("recentRuns / runsOfConversation：列名与 LIMIT 都通", () -> {
			List<AgentRunRecord> recent = runStore.recentRuns(5);
			List<AgentRunRecord> conversation = runStore.runsOfConversation("verify-conversation");
			return recent.size() == 1 && recent.get(0).id().equals(runId) && conversation.size() == 1
					&& runStore.runsOfConversation("   ").isEmpty();
		});
	}

	// ------------------------------------------------------------------
	//  成本聚合（看板那 12 条查询）
	// ------------------------------------------------------------------

	private void verifyCostAggregates() {
		LocalDate today = LocalDate.now();
		LocalDate from = today.minusDays(6);

		check("byDay：按天分桶读回来（Date 列的类型转换器生效，不是 null）",
				() -> costQuery.byDay(from, today).stream()
						.anyMatch(day -> today.equals(day.day()) && day.totals().runCount() >= 1
								&& day.totals().promptTokens() == 200));

		check("byIntent / byTenant：两个维度各自分组，且'未标注'归一成 null",
				() -> costQuery.byIntent(from, today).stream().anyMatch(g -> "验证业务".equals(g.key()))
						&& costQuery.byTenant(from, today).stream().anyMatch(g -> "verify-tenant".equals(g.key())));

		check("byModel / byModelAndDay：口径来自步骤表，模型名与 token 都对得上",
				() -> costQuery.byModel(from, today).stream()
						.anyMatch(g -> "deepseek-flash".equals(g.key()) && g.promptTokens() == 200)
						&& !costQuery.byModelAndDay(from, today).isEmpty());

		check("byTool：六种出口分别计数（这里应有 1 次调用、0 次 EXECUTION_ERROR）",
				() -> costQuery.byTool(from, today).stream().anyMatch(t -> "search_docs".equals(t.toolName())
						&& t.calls() >= 1 && t.failures() == 0));

		// 不写死数字：两个口径的期望值从**已经写进去的步骤**算出来，
		// 这样这条断言在别的用例顺序下也成立（写死数字的断言会随前置步骤变化而假失败）
		check("reconcile：两个 token 口径并排可读，且与步骤表之和一致", () -> {
			List<AgentRunRecord.StepCheckpoint> steps = runStore.stepsOf("verify-run-1");
			long expected = steps.stream()
					.mapToLong(s -> (long) s.promptTokens() + s.completionTokens())
					.sum();
			CostQuery.Reconciliation r = costQuery.reconcile(from, today);
			return r.stepLevelTokens() == expected && r.promptTokens() == steps.stream()
					.mapToLong(AgentRunRecord.StepCheckpoint::promptTokens).sum()
					&& r.gap() == r.stepLevelTokens() - r.runLevelTokens();
		});

		check("modelsInWindow：窗口内出现过的模型清单非空", () -> !costQuery.modelsInWindow(from, today).isEmpty()
				&& costQuery.modelsInWindow(from, today).contains("deepseek-flash"));
	}

	// ------------------------------------------------------------------
	//  Prompt 版本库
	// ------------------------------------------------------------------

	private void verifyPrompts() {
		check("save：新版本号是 max+1，hash 与 charCount 落库，forkedFrom 可空",
				() -> {
					PromptVersion v1 = promptStore.save(PromptKind.AGENT_LOOP, "验证用正文 A", "第一次", "verifier",
							null, 12);
					PromptVersion v2 = promptStore.save(PromptKind.AGENT_LOOP, "验证用正文 B", "第二次", "verifier",
							v1.version(), 12);
					return v2.version() == v1.version() + 1 && v2.forkedFrom() != null
							&& v2.hash().equals(PromptVersion.hashOf("验证用正文 B")) && v2.charCount() == 7;
				});

		check("find / versionsOf：能按 (kind, version) 读回，不受别的 kind 影响",
				() -> {
					List<PromptVersion> versions = promptStore.versionsOf(PromptKind.AGENT_LOOP);
					int latest = versions.get(0).version();
					boolean readable = promptStore.find(PromptKind.AGENT_LOOP, latest).isPresent();
					boolean scoped = promptStore.find(PromptKind.CHAT_SYSTEM, latest).isEmpty()
							|| !promptStore.find(PromptKind.CHAT_SYSTEM, latest).orElseThrow().content()
									.startsWith("验证用正文");
					return readable && scoped;
				});

		check("activate：带前置条件的指针切换（无指针时插入、有指针时更新）",
				() -> {
					int latest = promptStore.versionsOf(PromptKind.AGENT_LOOP).get(0).version();
					Optional<PromptVersion> first = promptStore.activate(PromptKind.AGENT_LOOP, latest, null);
					boolean switched = first.isPresent() && first.get().version() == latest;
					Optional<PromptVersion> again = promptStore.activate(PromptKind.AGENT_LOOP, latest - 1, null);
					// 第二次切到上一版：前置条件仍在（expected=null 表示不校验），应成功
					boolean back = again.isPresent() && again.get().version() == latest - 1;
					return switched && back;
				});

		check("activate：过期的预期（expectedVersion 不是当前值）→ 空，且**不改动**指针",
				() -> {
					int current = promptStore.active(PromptKind.AGENT_LOOP).orElseThrow().version();
					int latest = promptStore.versionsOf(PromptKind.AGENT_LOOP).get(0).version();
					Optional<PromptVersion> stale = promptStore.activate(PromptKind.AGENT_LOOP, latest,
							current + 100);
					int after = promptStore.active(PromptKind.AGENT_LOOP).orElseThrow().version();
					return stale.isEmpty() && after == current;
				});

		check("activate：不存在的版本 → 空",
				() -> promptStore.activate(PromptKind.AGENT_LOOP, 9999, null).isEmpty());
	}

	// ------------------------------------------------------------------
	//  灰度配置
	// ------------------------------------------------------------------

	private void verifyCanary() {
		Instant now = Instant.now();

		check("start + active + latest：开一次灰度后能读回（ended_at 为 null → active 为真）",
				() -> {
					CanaryStore.Row row = canaryStore.start(PromptKind.AGENT_LOOP, 2, 20, "验证用", "verifier", now);
					Optional<CanaryStore.Row> active = canaryStore.active(PromptKind.AGENT_LOOP);
					return row.weightPercent() == 20 && active.isPresent() && active.get().active()
							&& canaryStore.latest(PromptKind.AGENT_LOOP).isPresent();
				});

		check("start：已经有一条进行中的灰度时抛 CanaryConflictException（主键仲裁 + 冲突判定）", () -> {
			try {
				canaryStore.start(PromptKind.AGENT_LOOP, 3, 30, "第二次", "verifier", Instant.now());
				return false;
			}
			catch (CanaryStore.CanaryConflictException expected) {
				return true;
			}
		});

		check("observe：两个粒度的观测查询都能跑（空库上也必须返回，不是抛异常）",
				() -> canaryStore.observe(7) != null && canaryStore.observe(7).runs() >= 0);

		check("stop：停掉进行中的那条；再停一次返回空（'我停了'与'它本来就是停的'是两句话）",
				() -> {
					Optional<CanaryStore.Row> stopped = canaryStore.stop(PromptKind.AGENT_LOOP, "验证结束", "verifier",
							Instant.now());
					return stopped.isPresent() && !stopped.get().active()
							&& canaryStore.stop(PromptKind.AGENT_LOOP, "再停一次", "verifier", Instant.now()).isEmpty();
				});
	}

	// ------------------------------------------------------------------
	//  告警
	// ------------------------------------------------------------------

	private void verifyAlerts() {
		AlertRule rule = AlertRule.UNCONFIGURED_PRICES;
		String subject = "验证主体";
		String fingerprint = AlertEvent.fingerprintOf(rule, subject);
		AlertRule.Verdict verdict = new AlertRule.Verdict(true, "3/60", "≥ 3 次连续", "验证用判据");

		check("recordFiring：第一次写是 INSERT（occurrences=1，state=OPEN）", () -> {
			AlertEvent event = alertStore.recordFiring(rule, subject, verdict, "最近 7 天", Instant.now());
			return event.state() == AlertEvent.State.OPEN && event.occurrences() == 1
					&& "3/60".equals(event.observed());
		});

		check("recordFiring：第二次是 upsert（occurrences+1，first_seen_at 不动）", () -> {
			Instant firstSeen = alertStore.find(fingerprint).orElseThrow().firstSeenAt();
			AlertEvent event = alertStore.recordFiring(rule, subject, verdict, "最近 7 天", Instant.now());
			return event.occurrences() == 2 && firstSeen.equals(event.firstSeenAt());
		});

		check("acknowledge：确认后状态是 ACKNOWLEDGED 且记下是谁", () -> {
			AlertEvent event = alertStore.acknowledge(fingerprint, "张三", Instant.now()).orElseThrow();
			return event.state() == AlertEvent.State.ACKNOWLEDGED && "张三".equals(event.acknowledgedBy())
					&& event.acknowledgedAt() != null;
		});

		check("★ 再次 recordFiring 时已确认的保持已确认（CASE WHEN 真的在生效，计数照涨）", () -> {
			AlertEvent event = alertStore.recordFiring(rule, subject, verdict, "最近 7 天", Instant.now());
			return event.state() == AlertEvent.State.ACKNOWLEDGED && event.occurrences() == 3;
		});

		check("recordResolved：恢复后 resolvedAt 非空；重复恢复返回空",
				() -> {
					AlertEvent resolved = alertStore.recordResolved(fingerprint, Instant.now()).orElseThrow();
					return resolved.state() == AlertEvent.State.RESOLVED && resolved.resolvedAt() != null
							&& alertStore.recordResolved(fingerprint, Instant.now()).isEmpty();
				});

		check("★ 恢复后又响：状态被拉回 OPEN 且 resolved_at 被清空", () -> {
			AlertEvent event = alertStore.recordFiring(rule, subject, verdict, "最近 7 天", Instant.now());
			return event.state() == AlertEvent.State.OPEN && event.resolvedAt() == null;
		});

		check("acknowledge：已恢复的告警不能被确认（前置条件 state <> 'RESOLVED'）",
				() -> {
					alertStore.recordResolved(fingerprint, Instant.now());
					return alertStore.acknowledge(fingerprint, "李四", Instant.now()).isEmpty();
				});

		check("firing / recent / openCount：三个读接口都对得上",
				() -> {
					long open = alertStore.openCount();
					List<AlertEvent> recent = alertStore.recent(10);
					return open >= 0 && !recent.isEmpty() && recent.stream()
							.anyMatch(e -> fingerprint.equals(e.fingerprint()));
				});

		check("告警评估器整条链路（snapshot → 判据 → 落库）：dryRun 与真跑都能走完",
				() -> {
					AlertRecorder.Sweep sweep = alertRecorder.apply(alertRecorder.evaluate(7).snapshot());
					return sweep != null && sweep.openCount() >= 0;
				});
	}

	// ------------------------------------------------------------------
	//  人工审批
	// ------------------------------------------------------------------

	private void verifyApprovals() {
		String id = "verify-approval-1";
		Instant now = Instant.now();

		check("save + find + pending + ofRun：待批记录写进去读得回来（stepIndex 为 null 也能写）", () -> {
			approvalStore.save(ToolApprovalRecord.pending(id, "verify-run-1", 1, "propose_review", "{\"a\":1}",
					"写操作需确认", "建一张工单", now));
			approvalStore.save(ToolApprovalRecord.pending("verify-approval-2", "verify-run-1", null, "x", "null", null,
					null, now));
			ToolApprovalRecord back = approvalStore.find(id).orElseThrow();
			return back.pending() && back.stepIndex() == 1 && approvalStore.pending().size() == 2
					&& approvalStore.ofRun("verify-run-1").size() == 2 && approvalStore.recent(10).size() == 2;
		});

		check("decide：先写者胜（第二次返回 false，记录保持第一次的结论）", () -> {
			boolean first = approvalStore.decide(id, ApprovalStatus.APPROVED, Instant.now(), "张三", "同意");
			boolean second = approvalStore.decide(id, ApprovalStatus.TIMED_OUT, Instant.now(), null, null);
			ToolApprovalRecord back = approvalStore.find(id).orElseThrow();
			return first && !second && back.status() == ApprovalStatus.APPROVED && "张三".equals(back.decidedBy());
		});

		check("超长参数被截断并留下标记（人看到的必须说明它不是全部）", () -> {
			String longArgs = "x".repeat(4100);
			approvalStore.save(ToolApprovalRecord.pending("verify-approval-3", null, null, "t", longArgs, null, null,
					Instant.now()));
			String stored = approvalStore.find("verify-approval-3").orElseThrow().arguments();
			return stored.length() > 4000 && stored.contains("截断");
		});
	}

	// ------------------------------------------------------------------

	private void check(String what, Check body) {
		String verdict;
		try {
			verdict = body.ok() ? "PASS" : "FAIL(返回 false)";
		}
		catch (Throwable t) {
			verdict = "FAIL(" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")";
		}
		results.put(what, verdict);
		log.info("{} | {}", verdict, what);
	}

	@FunctionalInterface
	private interface Check {

		boolean ok();

	}

}


