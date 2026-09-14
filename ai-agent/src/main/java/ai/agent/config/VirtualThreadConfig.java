package ai.agent.config;

import org.apache.tomcat.util.threads.VirtualThreadExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.tomcat.TomcatProtocolHandlerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 虚拟线程改造（学习点 7）——把"阻塞等待"从"占用平台线程"变成"只占用一个虚拟线程"。
 *
 * <h2>先说清楚：四件事不是一个开关</h2>
 * 把 {@code spring.threads.virtual.enabled=true} 打开<b>就以为改造完了</b>，是这一节最容易踩的坑。
 * 实际上这里有四个层次，每一层解决的问题都不同：
 * <ol>
 *   <li><b>容器层</b>（本类的 {@link #virtualThreadProtocolHandler}）——
 *       让 Tomcat 每来一个请求就开一个虚拟线程。不换的话，请求线程数上限就是
 *       {@code server.tomcat.threads.max}（默认 200）：<b>第 201 个请求开始排队</b>。
 *       这是"最大并发"的硬天花板。</li>
 *   <li><b>业务层</b>（{@link #virtualThreadExecutor}）——
 *       当阻塞点<b>不在请求线程上</b>时（异步、定时任务、消息消费、批量扇出），
 *       容器层完全帮不上忙，必须自己指定执行器。</li>
 *   <li><b>超时层</b> —— 虚拟线程带来的新风险：<b>阻塞的代价变得极低，
 *       于是"忘了设超时"的代价变得极高</b>。以前线程池满了会逼着你处理，
 *       现在 10 万个虚拟线程安静地挂着，你会以为系统很闲。
 *       本项目的第一道超时（首字 / 空闲）是在这个改造<b>之前</b>就做好了的，
 *       顺序不能反 —— 先有超时，再放宽并发。</li>
 *   <li><b>依赖库层</b>（synchronized 钉住载体线程、ThreadLocal 膨胀）——
 *       这层不是配置能解决的，只能靠压测暴露。见台账里对应条目。</li>
 * </ol>
 *
 * <h2>为什么流式端点（Reactor）不需要这个改造，非流式端点必须要</h2>
 * 这是学习点 7 里那个"顺带要回答的问题"，答案不是"都要改"，而是<b>两者的线程模型根本不同</b>：
 * <ul>
 *   <li>{@code /api/chat/stream} 返回 {@code Flux}，由 Spring MVC 的响应式适配器接管。
 *       模型侧是 WebClient（Netty 事件循环），<b>等 token 的时候没有任何线程被占用</b> ——
 *       它是事件驱动的，来一个 chunk 才有一个回调。所以它在 200 个线程的容器里
 *       也能挂几千条连接。</li>
 *   <li>{@code /api/chat}（{@code .call()}）和 {@code /api/chat/structured} 是
 *       <b>同步阻塞</b>：一个请求从进来到模型吐完，全程占着一个线程干等。
 *       更糟的是 {@code /api/chat/structured} 是"最多 3 轮回灌"的循环，
 *       <b>一个 HTTP 请求最坏要占住线程 3 个模型调用的时长</b>（实测第 2 轮 4.3 秒）。
 *       于是同样 200 个线程，它能扛的并发比流式端点低一个数量级 ——
 *       这才是"为什么必须改造非流式端点"的完整答案。</li>
 * </ul>
 *
 * <h2>为什么"换成虚拟线程"就够了、不需要改成响应式</h2>
 * 这是这个改造<b>最值得讲的一句</b>：把阻塞式代码直接丢进虚拟线程，
 * 就得到了响应式方案的并发能力，而<b>代码一行都不用重写</b> ——
 * 不用学 Reactor、不用把 try/catch 改成 onErrorResume、不用处理
 * "响应式链路里没法用 ThreadLocal"这类问题。这是 Java 21 给存量系统的最大红利：
 * <b>升级的是运行时，不是编程模型</b>。
 *
 * <h2>为什么还要留一个 platform 模式</h2>
 * 没有对照就没有结论。{@code ai.chat.virtual-threads.enabled=false} 与
 * {@code async-executor=platform} 这两个开关让"改造前 / 改造后"能在
 * <b>同一个二进制、同一台机器、同一份压测脚本</b>下对比 ——
 * 否则对比数字会被"不同版本、不同机器"污染，那不是证据、只是故事。
 */
@Configuration
public class VirtualThreadConfig {

	private static final Logger log = LoggerFactory.getLogger(VirtualThreadConfig.class);

	/**
	 * 给 Tomcat 连接器换上虚拟线程执行器（容器层）。
	 *
	 * <p><b>为什么用自定义执行器而不是让 Tomcat 自己开虚拟线程</b>：
	 * Tomcat 11 自带 {@link VirtualThreadExecutor}，装配点是 {@code ProtocolHandler} 的
	 * executor 属性；用 Boot 的 {@link TomcatProtocolHandlerCustomizer} 挂上去，
	 * 好处是<b>显式且可关</b>—— 一个配置项就能退回平台线程，做对照实验。
	 *
	 * <p>线程名前缀（{@code tomcat-vt-}）是显式传进去的，不是从 bean 名推出来的 ——
	 * 有了它，诊断端点里能一眼看出"这个请求到底跑在哪种线程上"，
	 * 不用靠猜、也不用读源码。可观测性要从"能验证"这个标准来设计：
	 * 一个改不动的线程模型，和没改是一样的。
	 */
	@Bean
	TomcatProtocolHandlerCustomizer<?> virtualThreadProtocolHandler(ChatCallProperties properties) {
		boolean enabled = properties.getVirtualThreads().isEnabled();
		return handler -> {
			if (!enabled) {
				log.info("虚拟线程（Tomcat 容器层）未启用，将使用平台线程池：max={}（对照模式）",
						handler.getClass().getSimpleName());
				return;
			}
			handler.setExecutor(new VirtualThreadExecutor("tomcat-vt-"));
			log.info("虚拟线程（Tomcat 容器层）已启用：每个请求一个虚拟线程，"
					+ "并发上限不再等于 server.tomcat.threads.max");
		};
	}

	/**
	 * 业务侧虚拟线程执行器（业务层）。
	 *
	 * <p><b>为什么不是 {@code Executors.newCachedThreadPool()}</b>：
	 * 缓存线程池会为每个任务新建平台线程并缓存 60 秒，<b>线程数上限约等于并发数</b> ——
	 * 500 并发就是 500 个操作系统线程，光栈空间（每个约 1MB）就是 500MB，
	 * 还没算上下文切换的开销。这正是阻塞模型的成本，我们要绕开的就是它。
	 *
	 * <p><b>为什么用 {@code newVirtualThreadPerTaskExecutor()} 而不复用</b>：
	 * 虚拟线程<b>就是设计成一次性、用完即弃的</b>，创建成本只有几百字节、
	 * 不预分配栈。想"池化虚拟线程"是因为把平台线程的直觉搬了过来 ——
	 * 池化在这里只会带来坏处（任务被长期钉在同一个虚拟线程上，
	 * 而虚拟线程的价值恰恰是"需要阻塞时再挂起"，池化等于人为制造排队）。
	 */
	@Bean(destroyMethod = "close")
	ExecutorService virtualThreadExecutor() {
		return Executors.newVirtualThreadPerTaskExecutor();
	}

}
