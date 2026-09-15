package ai.agent.agent.store;

import ai.agent.agent.ResumableAgentService;
import ai.agent.config.AgentStoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动时恢复上一次留下的未完成运行。
 *
 * <h2>为什么挂在 {@code ApplicationReadyEvent} 而不是 {@code @PostConstruct}</h2>
 * 因为恢复要真的**跑 Agent**（调模型、执行工具），而那需要整个上下文就绪。
 * 在 {@code @PostConstruct} 阶段做，会在容器还没完全启动时发起外部调用 ——
 * 一旦那时抛异常，整个应用起不来，而原因是"上次有个任务没跑完"，
 * 这个因果关系没有人能一眼看出来。
 *
 * <h2>为什么默认关闭（{@code ai.agent.store.auto-resume-on-startup=false}）</h2>
 * 两个理由：
 * <ol>
 *   <li><b>它会在启动时花钱</b>：恢复意味着继续调模型。一个"应用一启动就自动打模型"的默认行为，
 *       在本地开发和 CI 里都是意外；</li>
 *   <li><b>"要不要恢复"是运维决定</b>：事故之后重启时，人可能希望先看看遗留的是什么任务、
 *       再决定恢复哪些（尤其是那些可能已经产生副作用的写操作任务）。
 *       自动恢复把它们一股脑跑完，反而剥夺了这个判断机会。</li>
 * </ol>
 * 所以能力默认具备、开关默认关闭，并在启动日志里把"有多少次没跑完的运行在等着"说清楚 ——
 * 让人知道有这件事，而不是悄悄跳过。
 *
 * <h2>恢复失败会不会影响启动</h2>
 * 不会。整个恢复过程被 try/catch 包住：<b>一次恢复失败不该让应用起不来</b>。
 * 但失败会被打成 ERROR 并带上 runId，因为"恢复失败了但没人知道"是最糟的组合：
 * 用户以为任务还在跑，而它其实已经停在库里了。
 */
@Component
public class AgentRunRecoveryOnStartup {

	private static final Logger log = LoggerFactory.getLogger(AgentRunRecoveryOnStartup.class);

	private final ResumableAgentService service;

	private final AgentStoreProperties properties;

	public AgentRunRecoveryOnStartup(ResumableAgentService service, AgentStoreProperties properties) {
		this.service = service;
		this.properties = properties;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void resumeInterruptedRuns() {
		boolean auto = properties.autoResumeOnStartup();
		try {
			if (auto) {
				var results = service.resumeInterruptedRuns();
				results.forEach(result -> log.info("恢复结果 runId={} outcome={} fromStep={}", result.runId(),
						result.outcome(), result.resumedFromStep()));
				log.info("启动恢复完成：共处理 {} 次未完成运行", results.size());
				return;
			}
			// 关闭自动恢复时**不静默跳过**：把"有几次在等着"打出来。
			// 不打的话，这些运行会一直躺在库里没人管，而现象是"那个任务永远没结果"
			long waiting = service.recentRuns(50).stream()
					.filter(run -> run.status() == RunStatus.INTERRUPTED)
					.count();
			if (waiting > 0) {
				log.warn("有 {} 次被中断的 Agent 运行等待恢复，但 ai.agent.store.auto-resume-on-startup=false ——"
						+ "它们不会自动继续。要恢复请打开该开关或调用 ResumableAgentService.resumeInterruptedRuns()", waiting);
			}
		}
		catch (RuntimeException e) {
			log.error("启动恢复失败（不影响应用启动）—— 未完成的运行仍留在库里，可稍后手工恢复", e);
		}
	}

	/** 供诊断端点/界面使用：当前有多少次等待恢复。 */
	public long waitingForRecovery() {
		return service.recentRuns(100).stream().filter(run -> run.status() == RunStatus.INTERRUPTED).count();
	}

}
