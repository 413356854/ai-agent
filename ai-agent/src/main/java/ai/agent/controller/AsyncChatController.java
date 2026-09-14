package ai.agent.controller;

import ai.agent.service.ChatExecutionService;
import ai.agent.service.ChatService;
import ai.agent.token.TokenBudgetGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.CompletableFuture;

/**
 * 非流式对话端点（学习点 7 的落点）。
 *
 * <h2>这个类为什么独立于 {@code ChatController}</h2>
 * SSE 与"一次性返回"是两套<b>完全不同的线程模型</b>，混在一个类里最容易让人
 * 得出错误结论（"都是聊天接口，为什么一个要改造一个不用"）。分开之后，
 * 每个类的注释可以只讲自己那套模型：
 * <ul>
 *   <li>{@code /api/chat/stream} —— 返回 {@code Flux}，事件驱动，
 *       <b>等 token 的时候没有线程被占用</b>，200 个容器线程也能挂几千条连接；</li>
 *   <li>{@code /api/chat/async}（本类）—— 同步阻塞的 {@code ChatClient.call()}，
 *       一个请求全程占一个线程干等。<b>并发上限 = 线程数</b>，所以才需要改造。</li>
 * </ul>
 *
 * <h2>改造的方式：不改业务代码，只换"谁来等"</h2>
 * 返回类型从 {@code String} 换成 {@link CompletableFuture} 是本改造的<b>全部</b>接口变化。
 * Spring MVC 见到它会启动异步处理：<b>容器线程立刻还回线程池</b>去接下一个请求，
 * 真正的等待发生在虚拟线程上，完成后再由容器派线程写响应。
 * 也就是说 —— 阻塞没有消失，它只是从"稀缺资源"搬到了"不稀缺资源"上。
 *
 * <h2>为什么两个端点都要保留</h2>
 * {@code /api/chat/async/sync} 是<b>对照组</b>：它和异步端点在同一个进程、
 * 同一份配置、同一台机器上跑同一个 {@code ChatExecutionService}，
 * 唯一差别是"阻塞发生在哪个线程上"。压测脚本同时打这两个端点，
 * 得到的差异才叫证据；否则只是把两个不同时刻的数字放在一起讲故事。
 */
@RestController
@RequestMapping("/api/chat/async")
public class AsyncChatController {

	private static final Logger log = LoggerFactory.getLogger(AsyncChatController.class);

	private final ChatExecutionService executionService;

	private final TokenBudgetGuard budgetGuard;

	public AsyncChatController(ChatExecutionService executionService, TokenBudgetGuard budgetGuard) {
		this.executionService = executionService;
		this.budgetGuard = budgetGuard;
	}

	/**
	 * 异步（虚拟线程）非流式对话。
	 *
	 * <p>示例：
	 * <pre>
	 * curl "http://localhost:8080/api/chat/async?conversationId=c1&amp;message=你好"
	 * </pre>
	 */
	@GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
	public CompletableFuture<String> chat(@RequestParam String conversationId, @RequestParam String message) {
		requireText(conversationId, "conversationId");
		requireText(message, "message");

		// 预算校验刻意放在"进执行器之前"：不合法就立刻拒绝，不要白白占一个虚拟线程去等
		TokenBudgetGuard.BudgetCheck check = budgetGuard.prepare(conversationId, message);
		logBudget(conversationId, check);

		return executionService.chatAsync(conversationId, message);
	}

	/**
	 * 同步对照端点（改造前的行为）。
	 *
	 * <p>它存在的唯一目的是给压测当基线。故意不加 {@code @Deprecated}：
	 * 它不是"待删除的旧代码"，而是一个<b>长期保留的对照实现</b> ——
	 * 以后每次调线程模型参数（池大小、执行器类型），都可以用它重新量一遍"有没有变好"。
	 */
	@GetMapping(value = "/sync", produces = MediaType.TEXT_PLAIN_VALUE)
	public String chatSync(@RequestParam String conversationId, @RequestParam String message) {
		requireText(conversationId, "conversationId");
		requireText(message, "message");

		TokenBudgetGuard.BudgetCheck check = budgetGuard.prepare(conversationId, message);
		logBudget(conversationId, check);

		return executionService.chatBlocking(conversationId, message);
	}

	/**
	 * 记账：把"这次花了多少预算"打进日志。
	 *
	 * <p>为什么值得占一行日志：上下文超限的故障几乎都是<b>渐进</b>发生的 ——
	 * 第 1 轮 500 token，第 10 轮 3000，等到某轮突然报错时，
	 * 你已经不知道是"哪一轮开始不对劲"了。每轮留一条账目，
	 * "对话从第几轮开始变贵"就是可以直接看出来的事实，而不是需要复现的猜想。
	 */
	private static void logBudget(String conversationId, TokenBudgetGuard.BudgetCheck check) {
		if (!check.enabled()) {
			return;
		}
		if (check.truncated()) {
			log.warn("预算账目 conversationId={} 需要={} 可用={} 历史条数={} 已裁掉={} 系统提示词={}",
					conversationId, check.neededTokens(), check.allowedTokens(), check.historyMessages(),
					check.droppedMessages(), check.systemPromptTokens());
		}
		else {
			log.info("预算账目 conversationId={} 需要={} 可用={} 历史条数={} 系统提示词={}",
					conversationId, check.neededTokens(), check.allowedTokens(), check.historyMessages(),
					check.systemPromptTokens());
		}
	}

	private static void requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 必填");
		}
	}

}
