package ai.agent.service.diag;

import ai.agent.config.ChatCallProperties;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 线程诊断（学习点 7 的<b>证据来源</b>）。
 *
 * <h2>为什么必须有这个东西</h2>
 * "我把虚拟线程打开了"是一句无法验证的话。可能生效了，也可能：
 * <ul>
 *   <li>配置项名字写错，Boot 静默忽略（Boot 对未知属性不报错）；</li>
 *   <li>bean 没被扫描到，自定义执行器根本没挂上去；</li>
 *   <li>改的层不对 —— 请求线程换成虚拟线程了，但业务代码自己起了平台线程池，
 *       瓶颈原地不动。</li>
 * </ul>
 * 这三种情况的共同点是<b>日志里看不出任何异常</b>。所以这里不去"看配置"，
 * 而是直接问运行时三个只有真改了才成立的问题：
 * <ol>
 *   <li><b>当前处理这个请求的线程是不是虚拟线程</b>（{@code Thread.isVirtual()}）——
 *       这是容器层是否生效的<b>唯一直接证据</b>；</li>
 *   <li><b>并发上到 N 的时候，操作系统线程数涨了没有</b> ——
 *       虚拟线程的核心承诺是"并发数 ≫ 平台线程数"，
 *       如果压测时平台线程数跟着并发一起涨，说明改造没落到实处；</li>
 *   <li><b>JVM 的 peak 平台线程数</b>（{@link ThreadMXBean#getPeakThreadCount()}）——
 *       它是单调递增的历史峰值，能抓到"压测期间某个瞬间线程数冲高"</li>
 * </ol>
 * 把这三个数字打进压测报告，"虚拟线程到底有没有用"就不再是一个观点，而是一组数据。
 */
@Component
public class ThreadDiagnostics {

	private final ChatCallProperties properties;

	private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

	/** 当前正在执行中的（进入本服务并被统计的）请求数。 */
	private final AtomicLong inFlight = new AtomicLong();

	/** 历史最大并发（单调递增，用来回答"最挤的时候有多少个请求同时在等"）。 */
	private final AtomicLong peakInFlight = new AtomicLong();

	private final AtomicLong totalRequests = new AtomicLong();

	public ThreadDiagnostics(ChatCallProperties properties) {
		this.properties = properties;
	}

	/** 记录一个请求开始，并返回"离开时调用"的收尾动作。 */
	public Runnable enter() {
		totalRequests.incrementAndGet();
		long current = inFlight.incrementAndGet();
		peakInFlight.accumulateAndGet(current, Math::max);
		return () -> inFlight.decrementAndGet();
	}

	/**
	 * 当前线程的快照。
	 *
	 * <p>字段里那个 {@code virtual} 布尔值就是"改造是否生效"的直接证据，
	 * 所以它必须来自 {@code Thread.currentThread().isVirtual()}（运行时事实），
	 * 而不是来自配置读取（配置意图）。两者不一致本身就是最有价值的信号。
	 */
	public Map<String, Object> currentThread() {
		Thread thread = Thread.currentThread();
		Map<String, Object> info = new LinkedHashMap<>();
		info.put("virtual", thread.isVirtual());
		info.put("name", thread.getName());
		info.put("threadId", thread.threadId());
		info.put("daemon", thread.isDaemon());
		info.put("priority", thread.getPriority());
		info.put("configuredMode", properties.getVirtualThreads().getAsyncExecutor());
		info.put("containerVirtualThreadsEnabled", properties.getVirtualThreads().isEnabled());
		return info;
	}

	/**
	 * JVM 线程账目。
	 *
	 * <p>三组数字的含义必须分清，否则会得出错误结论：
	 * <ul>
	 *   <li>{@code livePlatformThreads} / {@code peakPlatformThreads} ——
	 *       来自 {@link ThreadMXBean}，只统计<b>平台线程</b>。
	 *       虚拟线程不算在内：它们不是操作系统线程，这正是我们要证明的事。</li>
	 *   <li>{@code liveVirtualThreads} —— 通过遍历 {@code Thread.getAllStackTraces()}
	 *       数出来的。这个调用在虚拟线程很多时<b>不便宜</b>（要抓所有栈），
	 *       所以只放在诊断端点里手动调，绝不埋进请求链路。</li>
	 *   <li>{@code peakInFlight} —— 业务口径的并发峰值（本服务自己数的），
	 *       与线程数无关，用来验证"压测真的压上去了"。</li>
	 * </ul>
	 */
	public Map<String, Object> snapshot() {
		Map<String, Object> info = new LinkedHashMap<>();
		info.put("livePlatformThreads", threadBean.getThreadCount());
		info.put("peakPlatformThreads", threadBean.getPeakThreadCount());
		info.put("daemonThreads", threadBean.getDaemonThreadCount());
		info.put("liveVirtualThreads", countVirtualThreads());
		info.put("inFlight", inFlight.get());
		info.put("peakInFlight", peakInFlight.get());
		info.put("totalRequests", totalRequests.get());
		info.put("availableProcessors", Runtime.getRuntime().availableProcessors());
		info.put("platformPoolSize", properties.getVirtualThreads().getPlatformPoolSize());
		info.put("containerVirtualThreadsEnabled", properties.getVirtualThreads().isEnabled());
		info.put("configuredMode", properties.getVirtualThreads().getAsyncExecutor());
		return info;
	}

	/** 重置"并发峰值"这类单调计数，便于连续做几组对照实验时互不污染。 */
	public void reset() {
		peakInFlight.set(inFlight.get());
		totalRequests.set(0);
	}

	private long countVirtualThreads() {
		// 注意：这个调用会抓取所有线程的栈，开销不小，只适合诊断端点
		return Thread.getAllStackTraces().keySet().stream().filter(Thread::isVirtual).count();
	}

}
