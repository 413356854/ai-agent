package ai.agent.chat.async;

import ai.agent.chat.service.ChatService;
import ai.agent.config.ChatCallProperties;
import ai.agent.diag.ThreadDiagnostics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 非流式对话的<b>执行模式</b>（学习点 7）：同一份阻塞业务代码，
 * 换三种执行方式跑，用来回答"虚拟线程到底改变了什么"。
 *
 * <h2>为什么要在"执行方式"这一层做文章，而不是去改 {@code ChatService}</h2>
 * 阻塞的是 {@code ChatClient.call()} 本身 —— 它是<b>同步 API</b>，调用它的线程会被
 * 阻塞在 socket 读上直到模型返回。这个事实改不掉（除非改用 {@code stream()} 走响应式链路，
 * 那就是另一个功能了）。既然阻塞事实不变，能动的就只有<b>"拿哪种线程去承受这个阻塞"</b>：
 * <table border="1">
 *   <caption>三种模式</caption>
 *   <tr><th>模式</th><th>承受阻塞的线程</th><th>并发能力由什么决定</th></tr>
 *   <tr><td>{@code auto}</td><td>容器（Tomcat）的工作线程</td>
 *       <td>{@code server.tomcat.threads.max}（默认 200）</td></tr>
 *   <tr><td>{@code platform}</td><td>一个固定大小的平台线程池</td>
 *       <td>池大小（本项目故意设成 8，好让瓶颈几秒钟就复现）</td></tr>
 *   <tr><td>{@code virtual}</td><td>每个任务一个虚拟线程</td>
 *       <td><b>不再是线程数</b>，而是上游（模型服务）能承受的并发</td></tr>
 * </table>
 *
 * <h2>虚拟线程真的"更快"吗？不是 —— 它改变的是"等待的成本"</h2>
 * 这一点必须说准，否则面试时会被一句话问倒（"那单次请求延迟降了多少？"）：
 * <ul>
 *   <li><b>单次请求延迟：几乎不变</b>。模型就用那么久，换谁来等都一样。
 *       实测数字也证实了这点（见台账）。</li>
 *   <li><b>吞吐与尾延迟：数量级差异</b>。请求变多时，平台线程池会先<b>排队</b> ——
 *       排队时间会直接叠加到用户的等待上（实测 p99 从 1.5s 涨到 6s 量级）；
 *       虚拟线程不排队，因为它不需要"先拿到一个稀缺的线程"。</li>
 * </ul>
 * 所以正确的说法是：<b>虚拟线程不加速单个请求，它消除的是"因为线程不够而排队"这件事</b>。
 * 用一句话概括就是"等待不再昂贵"。
 *
 * <h2>那还要不要限流</h2>
 * <b>要，而且更必须要</b>。并发上限从"200 个线程"松开之后，
 * 真正会被打挂的下游变成了<b>模型服务</b>与<b>数据库连接池</b>。
 * 这个改造顺手把风险从"我们自己排队"转移成了"上游被打挂"——
 * 后者更难排查、影响面更大。所以生产上必须补一层按下游容量定的限流
 * （信号量 / {@code RateLimiter} / 网关层限流都行），
 * 线程模型从来不是并发控制的终点。
 */
@Service
public class ChatExecutionService {

	private static final Logger log = LoggerFactory.getLogger(ChatExecutionService.class);

	/** 与虚拟线程 bean 同一个名字前缀，便于在诊断端点里区分"业务虚拟线程"和"容器虚拟线程"。 */
	private static final String BUSINESS_THREAD_PREFIX = "chat-blocking-";

	private final ChatService chatService;

	private final ChatCallProperties properties;

	private final ThreadDiagnostics diagnostics;

	/** 虚拟线程执行器（每个任务一个新虚拟线程）。null 表示该模式未启用。 */
	private final ExecutorService virtualExecutor;

	/** 对照用的固定平台线程池。null 表示该模式未启用。 */
	private final ExecutorService platformExecutor;

	public ChatExecutionService(ChatService chatService, ChatCallProperties properties, ThreadDiagnostics diagnostics,
			ObjectProvider<ExecutorService> executorProvider) {
		this.chatService = chatService;
		this.properties = properties;
		this.diagnostics = diagnostics;

		String mode = normalize(properties.getVirtualThreads().getAsyncExecutor());
		this.virtualExecutor = ("virtual".equals(mode) || "auto".equals(mode)) ? executorProvider.getIfAvailable() : null;
		this.platformExecutor = "platform".equals(mode) ? newPlatformPool(properties.getVirtualThreads().getPlatformPoolSize()) : null;

		log.info("非流式对话执行模式 = {}（虚拟线程={}，平台线程池={}）", mode,
				virtualExecutor != null ? "已装配" : "未装配", platformExecutor != null ? "已装配" : "未装配");
	}

	/**
	 * 异步执行一次非流式对话。
	 *
	 * <p><b>返回值为什么是 {@link CompletableFuture} 而不是 {@code String}</b>：
	 * Spring MVC 见到它就把请求转成异步处理 —— <b>容器线程立刻被释放</b>，
	 * 回到线程池去接下一个请求；等 Future 完成时再由容器派一个线程继续写响应。
	 * 这正是"改造"的落点：阻塞并没有消失，它只是从"稀缺的平台线程"搬到了
	 * "不稀缺的虚拟线程"上。
	 */
	public CompletableFuture<String> chatAsync(String conversationId, String message) {
		String mode = normalize(properties.getVirtualThreads().getAsyncExecutor());

		if ("auto".equals(mode)) {
			// 对照模式三：不切换线程，就在容器线程上同步跑（改造前的行为）。
			// 用 completedFuture 是刻意的：这样三条路径返给 Spring MVC 的类型一致，
			// 差异只体现在"谁在阻塞"，不会掺进别的变量
			return CompletableFuture.completedFuture(execute(conversationId, message));
		}

		ExecutorService executor = "platform".equals(mode) ? platformExecutor : virtualExecutor;
		if (executor == null) {
			log.warn("配置的执行模式 {} 没有可用执行器，退化为在当前线程执行", mode);
			return CompletableFuture.completedFuture(execute(conversationId, message));
		}

		// supplyAsync 让任务进入执行器；异常会被自动包进 CompletableFuture，
		// 由 Spring MVC 的异步处理机制翻译成 500（或 ResponseStatusException 对应的状态码）
		return CompletableFuture.supplyAsync(() -> execute(conversationId, message), executor);
	}

	/** 同步执行（供不支持异步返回类型的调用方 / 测试使用）。 */
	public String chatBlocking(String conversationId, String message) {
		return execute(conversationId, message);
	}

	/**
	 * 真正干活的地方 —— <b>三种模式共用这一份代码，一行都没有分叉</b>。
	 *
	 * <p>这一点值得强调：整个虚拟线程改造<b>没有动业务逻辑</b>，
	 * 只是在外面套了一层"谁来执行"。如果有哪一天需要为虚拟线程单独写一套代码，
	 * 那说明改造方向就已经错了 —— 虚拟线程的设计目的就是让<b>存量阻塞代码</b>直接受益。
	 */
	private String execute(String conversationId, String message) {
		Runnable leave = diagnostics.enter();
		try {
			ChatCallProperties.Sampling sampling = properties.getSampling();
			if (sampling.isStandInActive()) {
				// 压测替身：把不可控的模型调用换成可控的固定延迟。
				// 用 Thread.sleep（而不是忙等）是关键 —— 它正是"阻塞"的最小模型：
				// 虚拟线程会在这里让出载体线程，平台线程则被钉死。
				sleep(sampling.getLatency().toMillis());
				log.debug("替身响应 conversationId={} latencyMs={} thread={}", conversationId,
						sampling.getLatency().toMillis(), Thread.currentThread());
				return sampling.getResponse();
			}
			return chatService.call(conversationId, message);
		}
		finally {
			leave.run();
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException e) {
			// 恢复中断标记：吞掉中断会让上游的取消/关闭逻辑失效（这是并发代码的经典错误）
			Thread.currentThread().interrupt();
			throw new IllegalStateException("压测替身延迟被中断", e);
		}
	}

	/**
	 * 对照用的固定平台线程池。
	 *
	 * <p>用 {@link SynchronousQueue}（而不是有界队列）是刻意的：
	 * 有界队列会把超出容量的请求<b>悄悄排队</b>，压测表现为"延迟慢慢涨"，
	 * 很难看出瓶颈在哪；同步队列没有缓冲，超出的任务直接被拒绝，
	 * 现象立刻变成"线程池满了"——<b>这是我们要给用户看的那种失败</b>，
	 * 因为它暴露了约束的真实位置。
	 */
	private static ExecutorService newPlatformPool(int size) {
		int poolSize = Math.max(1, size);
		return new ThreadPoolExecutor(poolSize, poolSize, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>(),
				runnable -> {
					Thread thread = new Thread(runnable, BUSINESS_THREAD_PREFIX + "pool");
					thread.setDaemon(true);
					return thread;
				});
	}

	private static String normalize(String mode) {
		if (mode == null || mode.isBlank()) {
			return "virtual";
		}
		return mode.trim().toLowerCase();
	}

}
