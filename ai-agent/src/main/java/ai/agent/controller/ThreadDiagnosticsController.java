package ai.agent.controller;

import ai.agent.config.ChatCallProperties;
import ai.agent.diag.ThreadDiagnostics;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 线程诊断端点（学习点 7 的验收工具）。
 *
 * <p>这些端点不是给用户用的，是给<b>排查和举证</b>用的：
 * 每个端点返回的都是"只有改造真的生效才成立"的运行时事实。
 *
 * <ul>
 *   <li>{@code GET /api/diag/threads} —— 处理这个请求的线程是不是虚拟线程。
 *       <b>这条比读配置可靠</b>：配置写错了 Boot 不会报错（未知属性静默忽略），
 *       但这里会如实返回 false。</li>
 *   <li>{@code GET /api/diag/threads/stats} —— JVM 平台线程数 / 虚拟线程数 / 并发峰值。
 *       压测期间反复采样，就能画出"并发 vs 平台线程数"的关系曲线。</li>
 *   <li>{@code GET /api/diag/threads/carriers?tasks=N} —— 让 N 个虚拟线程同时阻塞，
 *       把每个虚拟线程<b>被调度到哪个载体线程</b>（ForkJoinPool worker）报出来。
 *       这是理解"虚拟线程 = 用户态调度"最直观的一张图。</li>
 *   <li>{@code GET /api/diag/threads/pin?holders=N} —— <b>同步块钉住载体线程</b>实验。
 *       见方法注释：它证明"虚拟线程不是免费的"，是本节最有价值的一条。</li>
 *   <li>{@code GET /api/diag/threads/config} —— 当前生效的关键配置
 *       （排查"改了没生效"时先看这个）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/diag/threads")
public class ThreadDiagnosticsController {

	private final ThreadDiagnostics diagnostics;

	private final ChatCallProperties properties;

	private final ObjectProvider<ExecutorService> executorProvider;

	public ThreadDiagnosticsController(ThreadDiagnostics diagnostics, ChatCallProperties properties,
			ObjectProvider<ExecutorService> executorProvider) {
		this.diagnostics = diagnostics;
		this.properties = properties;
		this.executorProvider = executorProvider;
	}

	/** 处理这个请求的线程是谁：虚拟还是平台、叫什么名字、配的是什么模式。 */
	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> currentThread() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("currentThread", diagnostics.currentThread());
		body.put("note", "virtual=true 的含义：本次请求跑在虚拟线程上，并发上限不再等于容器线程池大小");
		return body;
	}

	/** JVM 线程账目与并发峰值。 */
	@GetMapping(value = "/stats", produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> stats() {
		return diagnostics.snapshot();
	}

	/** 重置并发峰值（连续做几组对照实验时用，避免上一组的数据污染下一组）。 */
	@PostMapping(value = "/stats/reset", produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> resetStats() {
		diagnostics.reset();
		return diagnostics.snapshot();
	}

	/** 当前生效的关键配置（排查"改了没生效"时先看这个）。 */
	@GetMapping(value = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> config() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("containerVirtualThreads", properties.getVirtualThreads().isEnabled());
		body.put("asyncExecutor", properties.getVirtualThreads().getAsyncExecutor());
		body.put("platformPoolSize", properties.getVirtualThreads().getPlatformPoolSize());
		body.put("samplingLatency", Duration.ofMillis(properties.getSampling().getLatency().toMillis()).toString());
		body.put("standInActive", properties.getSampling().isStandInActive());
		body.put("budgetEnabled", properties.getBudget().isEnabled());
		body.put("contextWindow", properties.getBudget().getContextWindow());
		body.put("maxOutputTokens", properties.getBudget().getMaxOutputTokens());
		body.put("safetyMargin", properties.getBudget().getSafetyMargin());
		body.put("usableBudget", properties.getBudget().usableBudget());
		body.put("inputAllowance", properties.getBudget().inputAllowance());
		return body;
	}

	/**
	 * 观察"虚拟线程被调度到哪个载体线程上"。
	 *
	 * <p><b>这个端点存在的意义</b>：虚拟线程最反直觉的一点是它<b>不是 1:1 映射到操作系统线程</b>的。
	 * JVM 维护一个 {@code ForkJoinPool}（默认并行度 = CPU 核数），虚拟线程在<b>阻塞时</b>
	 * 从这里"下车"，在<b>就绪时</b>再"上车"。所以你会看到 N 个虚拟线程轮流用少数几个
	 * {@code ForkJoinPool-1-worker-*} 载体线程 —— 这就是"并发数 ≫ 线程数"的实现方式，
	 * 也是它和线程池最本质的区别（线程池是"任务等线程"，虚拟线程是"线程等 CPU"）。
	 *
	 * <p>返回里给出载体线程名，并用 {@code distinctCarriers} 统计一共用到了几个 ——
	 * 这个数字明显小于任务数时，就亲眼看到了复用。
	 */
	@GetMapping(value = "/carriers", produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> carriers(@RequestParam(defaultValue = "16") int tasks,
			@RequestParam(defaultValue = "300") long ms) {

		ExecutorService executor = requireExecutor();
		int count = Math.max(1, Math.min(tasks, 512));
		long sleepMs = Math.max(50, Math.min(ms, 5000));

		List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			futures.add(CompletableFuture.supplyAsync(() -> {
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("virtualThread", Thread.currentThread().getName());
				sleep(sleepMs);
				// 睡醒后取载体线程名。
				//
				// 【实测踩坑】这里不能直接存 Thread.currentThread().toString()。
				// 它的格式是 VirtualThread[#63]/runnable@ForkJoinPool-1-worker-6 ——
				// 整串里带着**虚拟线程自己的编号**，所以每个样本都不一样，
				// 去重后得到"16 个不同载体"，而机器只有 8 个并行度，明显是假的。
				// 第一版就是这么错的：把虚拟线程的身份混进了载体的统计里。
				// 必须只取 @ 之后那一段，才是真正的载体线程名。
				row.put("carrier", carrierName());
				return row;
			}, executor));
		}

		List<Map<String, Object>> rows = new ArrayList<>();
		for (CompletableFuture<Map<String, Object>> future : futures) {
			rows.add(future.join());
		}
		long distinctCarriers = rows.stream().map(row -> String.valueOf(row.get("carrier"))).distinct().count();

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("virtualThreads", count);
		body.put("sleepMs", sleepMs);
		body.put("distinctCarriers", distinctCarriers);
		body.put("carrierParallelism", Runtime.getRuntime().availableProcessors());
		body.put("samples", rows);
		body.put("note", "virtualThreads 远大于 distinctCarriers 即为正常：虚拟线程复用了少量载体线程。"
				+ "distinctCarriers 略大于 CPU 核数也属正常（调度器在虚拟线程阻塞时允许临时扩容）");
		return body;
	}

	/**
	 * 取当前线程的<b>载体线程名</b>。
	 *
	 * <p>虚拟线程运行时，它的 {@code toString()} 形如
	 * {@code VirtualThread[#63]/runnable@ForkJoinPool-1-worker-6}：
	 * {@code @} 之前是虚拟线程自己的身份，之后才是载体。
	 * 只想要载体就必须切掉前半段 —— 否则每个样本都是"不同载体"，统计彻底失真。
	 */
	private static String carrierName() {
		Thread thread = Thread.currentThread();
		String text = thread.toString();
		int at = text.indexOf('@');
		return at >= 0 ? text.substring(at + 1) : text;
	}

	/**
	 * <b>同步块钉住载体线程</b>（pinning）实验 —— 本节最该记住的一条。
	 *
	 * <p>直觉上"虚拟线程遇到阻塞就让出载体线程，所以能有几十万个"，但有一个例外：
	 * 在 {@code synchronized} 块里阻塞时，虚拟线程<b>无法卸载</b>，
	 * 它会把载体线程一起钉住（JDK 21 的行为；JDK 24 的 JEP 491 才取消这个限制）。
	 * 载体线程只有"CPU 核数"那么多，所以后果很反直觉：
	 * <b>十几个 synchronized 就能把虚拟线程的并发能力打回平台线程时代</b>。
	 *
	 * <h2>这个实验第一版是失败的，原因值得记</h2>
	 * 第一版的做法是"占位任务全部启动 → 睡一小会儿 → 提交一个空任务量延迟"，
	 * 结果 synchronized 与 ReentrantLock 两组的延迟都是 <b>0ms</b>，看不出任何差异。
	 * 原因是：{@code CompletableFuture.runAsync} 在<b>提交时</b>就选好了载体，
	 * 只有在提交那一刻载体恰好全被占住，才会被记到延迟 ——
	 * 而"占位任务真的把载体占满"是一个时间窗口很窄的状态，靠"睡一会儿再看"根本抓不住。
	 * <b>教训：测量并发状态时，"先启动再观察"这种做法很容易滑过去，
	 * 必须想办法把被测状态<i>持续</i>住，或者直接在结果上做对照。</b>
	 *
	 * <h2>现在怎么测</h2>
	 * 换成<b>分批 + 轮询重叠</b>：每批再放 {@code batchSize} 个占位任务，
	 * 立即连着 {@code probesPerBatch} 次量"提交空任务 → 开始执行"的延迟，取最大值。
	 * 这样每一批都必然有"载体被这批判的占位任务占住"的时刻，
	 * 于是 <b>延迟本身</b>就成了被测量的对象，而不是靠运气撞上。
	 *
	 * <p>判读方式：占位任务在两组里<b>数量、时长、持锁行为完全相同</b>，
	 * 唯一差别是阻塞方式，所以延迟差异只能归因于 pinning：
	 * <ul>
	 *   <li>{@code synchronized}：载体被钉住 → 新任务要等载体空出来 → 延迟冲高；</li>
	 *   <li>{@code ReentrantLock}：park 型阻塞可卸载，载体立刻可用 → 延迟只剩调度开销。</li>
	 * </ul>
	 * 两个数字在<b>同一次运行</b>里测出来并排返回，不依赖跨次实验的环境一致性 ——
	 * 做对照实验最要紧的就是这一点。
	 *
	 * <p>工程结论是一句可执行的话：<b>虚拟线程化之前，先排查存量代码里包着 IO 的
	 * {@code synchronized}</b>，把它们换成 {@code ReentrantLock}。
	 * 这类问题不报错、不打日志，只会在高并发时表现为"虚拟线程好像没什么用"。
	 *
	 * <p><b>holders 默认取 12（而不是 16），是权衡出来的</b>：这个实验的耗时随 holders
	 * 线性增长（synchronized 那组要一批批把载体耗完）。实测：8 → 13.3s、12 → 19.2s、16 → 25.9s，
	 * 而同步块的探针延迟分别是 801ms / 3202ms / 4050ms。
	 * 8 个载体就把延迟从 0ms 抬到 800ms 以上（对比足够醒目），12 又把它拉到 3 秒级。
	 * 所以 12 是"证据够硬 + 界面不至于让人以为卡死"的折中 —— 默认值要照顾的是**交互体验**，
	 * 想看更极端的数字可以手动传 holders=16 或更大。
	 */
	@GetMapping(value = "/pin", produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> pinning(@RequestParam(defaultValue = "12") int holders,
			@RequestParam(defaultValue = "800") long holdMs) {

		ExecutorService executor = requireExecutor();
		int count = Math.max(1, Math.min(holders, 256));
		long hold = Math.max(100, Math.min(holdMs, 5000));

		long monitorLatency = measureCarrierExhaustion(executor, count, hold, true);
		long lockLatency = measureCarrierExhaustion(executor, count, hold, false);

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("holders", count);
		body.put("holdMsEach", hold);
		body.put("carrierParallelism", Runtime.getRuntime().availableProcessors());
		body.put("probeLatencyWithSynchronized", monitorLatency + "ms");
		body.put("probeLatencyWithReentrantLock", lockLatency + "ms");
		body.put("verdict", monitorLatency > Math.max(20, lockLatency * 5)
				? "确认钉住：synchronized 占住载体线程后，新任务必须排队等载体空出来"
				: "未观察到明显钉住（占位任务数可能少于载体数，或该 JDK 已放宽限制）");
		body.put("note", "probeLatency = 『提交一个空任务 → 它真正开始执行』的最坏延迟，"
				+ "等价于用户感知到的排队时间。两组实验除阻塞方式外完全相同");
		body.put("fix", "把包着阻塞 IO 的 synchronized 换成 ReentrantLock，或缩小临界区、把 IO 移到锁外");
		return body;
	}

	/**
	 * 分批投放占位任务，并在每批投放后立即探测"新任务要等多久才能拿到载体线程"。
	 *
	 * @param useMonitor true 用 {@code synchronized}（JDK 21 下会钉住载体），
	 *                   false 用 {@code ReentrantLock}（可卸载）
	 * @return 探测到的最坏排队延迟（毫秒）
	 */
	private static long measureCarrierExhaustion(ExecutorService executor, int holders, long holdMs,
			boolean useMonitor) {

		Object monitor = new Object();
		java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
		List<CompletableFuture<Void>> occupied = new ArrayList<>();

		int batchSize = 8;   // 一批就足以把 8 个载体全占住
		int probesPerBatch = 24;
		long worstLatencyMs = 0;

		for (int start = 0; start < holders; start += batchSize) {
			int end = Math.min(start + batchSize, holders);
			for (int i = start; i < end; i++) {
				occupied.add(CompletableFuture.runAsync(() -> {
					if (useMonitor) {
						synchronized (monitor) {
							sleep(holdMs);
						}
					}
					else {
						lock.lock();
						try {
							sleep(holdMs);
						}
						finally {
							lock.unlock();
						}
					}
				}, executor));
			}

			// 立刻连着探测：此刻占位任务正在抢载体，正是要抓的状态
			for (int i = 0; i < probesPerBatch; i++) {
				long submittedAt = System.nanoTime();
				CompletableFuture.runAsync(() -> {
				}, executor).join();
				worstLatencyMs = Math.max(worstLatencyMs, (System.nanoTime() - submittedAt) / 1_000_000);
			}
		}

		// 让这一组彻底跑完，别把载体占用带进下一组实验
		CompletableFuture.allOf(occupied.toArray(CompletableFuture[]::new)).join();
		return worstLatencyMs;
	}

	private ExecutorService requireExecutor() {
		ExecutorService executor = executorProvider.getIfAvailable();
		if (executor == null) {
			throw new IllegalStateException("虚拟线程执行器未装配（检查 ai.chat.virtual-threads.async-executor 配置）");
		}
		return executor;
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException e) {
			// 恢复中断标记：吞掉中断会让上游的取消/关闭逻辑失效（并发代码的经典错误）
			Thread.currentThread().interrupt();
			throw new IllegalStateException("诊断实验被中断", e);
		}
	}

}
