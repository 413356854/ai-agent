package ai.agent.service.agent.limit;

import ai.agent.service.agent.ModelTurn;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 给模型调用套上并发闸门（阶段三：并发控制）。
 *
 * <h2>为什么这一层必须存在（阶段一留下的那条风险）</h2>
 * 阶段一做了虚拟线程改造：容器线程池换掉、业务层换掉，2000 并发吞吐从 472 涨到 1192.6 req/s。
 * 那次改造的台账结论里有一句风险提醒：「**并发上限从"200 个线程"松开之后，
 * 真正会被打挂的下游变成了模型服务**」。
 *
 * <p>本类就是那条提醒的兑现。没有它，虚拟线程会安静地开出成千上万个在途的模型请求 ——
 * 而"安静"正是最危险的地方：平台线程时代线程池满了会排队/拒绝，<b>逼着你处理</b>；
 * 现在它会一直开线程，你会以为系统很闲，直到下游的配额被打满、
 * 或者开始按 429 回你。
 *
 * <h2>为什么用"包装 ModelTurn"而不是在 AgentLoop 里加锁</h2>
 * 三个理由：
 * <ol>
 *   <li><b>闸门与循环是两件事</b>：循环管"跑到第几步"，闸门管"同时几个在途"。
 *       混在一起之后，循环的单测都要面对一个信号量；</li>
 *   <li><b>这样它适用于所有模型调用点</b>：将来若有别的入口（诊断端点直接试调模型、
 *       结构化输出的回灌轮），只要它用 {@code ModelTurn}，就自动被保护 ——
 *       而在循环里加锁只保护循环那条路径；</li>
 *   <li><b>可替换</b>：想换成"按租户隔离"或"按模型分别限流"时，改的是包装器，
 *       循环一行不动。</li>
 * </ol>
 *
 * <h2>被拒绝时抛什么</h2>
 * 抛 {@link ModelBusyException}。<b>刻意不抛 InterruptedException 之类</b>：
 * 那是"被中断"，而这里是"太挤了" —— 两者对调用方的含义不同
 * （前者该终止任务，后者该稍后重试）。
 *
 * <p>这个异常会被 {@code AgentLoop} 兜住、变成 {@code MODEL_ERROR} 的报告。
 * ⚠️ 这里有一个已知的粗糙之处，写下来而不是假装它不存在：
 * <b>"模型太挤"与"模型真的挂了"在报告里目前是同一个 stop reason</b>。
 * 区分它们需要在 {@code AgentStopReason} 上加一个出口（比如 {@code MODEL_BUSY}），
 * 而那会牵动报告与界面。本轮先把它做成"异常信息说得清清楚楚"，
 * 因为至少日志与异常链条能看出来；等 Agent 界面那件做的时候一起改更划算。
 */
public class LimitedModelTurn implements ModelTurn {

	private final ModelTurn delegate;

	private final ConcurrencyGate gate;

	public LimitedModelTurn(ModelTurn delegate, ConcurrencyGate gate) {
		this.delegate = delegate;
		this.gate = gate;
	}

	@Override
	public ModelReply chat(List<Message> messages) {
		String rejection = gate.tryAcquire();
		if (rejection != null) {
			throw new ModelBusyException(rejection);
		}
		try {
			return delegate.chat(messages);
		}
		finally {
			// 必须在 finally：异常路径上漏掉一次，闸门就永久少一个名额，
			// 而表现是"越跑越慢最后卡住"，重启又好了 —— 最难查的一类泄漏
			gate.release();
		}
	}

	@Override
	public boolean toolCallingEnabled() {
		return delegate.toolCallingEnabled();
	}

	/**
	 * 委派给被包装的那个适配器（阶段四成本看板）。
	 *
	 * <p><b>这一行如果漏了会怎样</b>：默认实现返回 {@code UNKNOWN_MODEL}，
	 * 于是**每一次 Agent 运行的每一步都记成"未知模型"**，成本看板上所有钱都归到
	 * 「未上报模型名」那一行，而"按模型归因"这个维度整个失效 ——
	 * 而且它不报错、接口照常 200，只有打开看板才会发现全是"未知"。
	 * 这正是装饰器模式最经典的漏法：包装类忘了转发一个新增的接口方法。
	 */
	@Override
	public String modelName() {
		return delegate.modelName();
	}

	/** 闸门快照（诊断端点与报告用）。 */
	public ConcurrencyGate.Snapshot gateSnapshot() {
		return gate.snapshot();
	}

	/** 模型侧排队失败时抛的异常。 */
	public static class ModelBusyException extends RuntimeException {

		public ModelBusyException(String message) {
			super(message);
		}

	}

}
