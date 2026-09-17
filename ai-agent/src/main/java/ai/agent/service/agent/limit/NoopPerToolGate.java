package ai.agent.service.agent.limit;

import ai.agent.config.AgentConcurrencyProperties;
import ai.agent.service.tool.ToolCallStage;

/**
 * 不做任何限制的闸门实现。给不需要保护的场景（工具自己的单测、诊断）用。
 *
 * <h2>为什么不是"传 null 然后每处判空"</h2>
 * 判空会让"没有闸门"这条路径在代码里到处出现，而<b>任何一条没有被测到的分支都是下一个 bug 的家</b>。
 * 空对象让执行器里只有一条路径（永远调 {@code tryAcquire}），
 * 于是"限制生效"与"不生效"走的是同一段代码，只是结果不同。
 */
public final class NoopPerToolGate extends PerToolGate {

	private static final AgentConcurrencyProperties UNLIMITED = new AgentConcurrencyProperties(1_000_000, 1_000_000,
			1, new AgentConcurrencyProperties.Circuit(false, 1.0, 1, 1, 1, 1));

	private static final NoopPerToolGate INSTANCE = new NoopPerToolGate();

	private NoopPerToolGate() {
		// 【注意这里的取舍】上限给了一个极大的值而不是"跳过检查"：
		// 这样闸门本身的逻辑（含 inFlight 计数与 finally 里的 release）仍然被执行，
		// 于是"空实现掩盖了 release 泄漏"这种情况不会发生 ——
		// 如果这里真的跳过，那么泄漏只会在生产配置下才暴露
		super(UNLIMITED);
	}

	public static NoopPerToolGate instance() {
		return INSTANCE;
	}

	@Override
	public Rejection tryAcquire(String toolName) {
		// 上限极大 + 超时 1ms：正常情况下必然立刻拿到。
		// 仍然走父类逻辑，于是计数与释放路径一致
		return super.tryAcquire(toolName);
	}

	@Override
	public void release(String toolName, ToolCallStage stage) {
		super.release(toolName, stage);
	}

}
