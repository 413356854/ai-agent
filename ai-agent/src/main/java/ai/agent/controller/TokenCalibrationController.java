package ai.agent.controller;

import ai.agent.chat.service.ChatService;
import ai.agent.config.ChatCallProperties;
import ai.agent.token.TokenBudgetGuard;
import ai.agent.token.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Token 估算<b>校准</b>端点：把"估算值"和"真实 usage"放在同一个响应里。
 *
 * <h2>为什么这件事必须做，而且必须在应用内做</h2>
 * 整套 Token 预算校验都建立在一个假设上：<b>估算值足够接近真实值</b>。
 * 这个假设不去验证，校验就只是"看起来在工作"：
 * <ul>
 *   <li>估<b>低</b>了 → 超窗没被拦住，请求照样发出去，钱花了、还可能被静默截断；</li>
 *   <li>估<b>高</b>了 → 本来能过的请求被提前裁掉历史或直接拒绝，用户体验变差。</li>
 * </ul>
 * 而"估算准不准"这件事<b>推理不出来，只能量</b>。
 *
 * <p><b>为什么要放在应用内，而不是写个脚本在外面比</b>：如果估算在脚本里算、
 * 真实值从接口拿，那么脚本里的公式一旦和 {@link ai.agent.token.impl.CharacterClassTokenEstimator}
 * 的真实实现漂移，校准结论就毫无意义 —— 而脚本正是最容易"改配置忘了同步"的地方。
 * 放在应用内，两侧走的是<b>同一个</b> {@link TokenBudgetGuard} 估算链路，
 * 量出来的误差就是线上真实会发生的误差。
 *
 * <h2>怎么保证测出来的是"这段文本"的成本</h2>
 * 每次用一个<b>全新的 conversationId</b>，只发一轮。这样 prompt 里除了这段文本，
 * 就只有「系统提示词 + 消息包装」两个常量 —— 而它们同样会进入估算（守卫算的就是这两项），
 * 于是两边可以直接相减比较，不需要任何凑数调整。
 */
@RestController
@RequestMapping("/api/diag/token")
public class TokenCalibrationController {

	private static final Logger log = LoggerFactory.getLogger(TokenCalibrationController.class);

	private final TokenBudgetGuard budgetGuard;

	private final TokenEstimator estimator;

	private final ChatService chatService;

	private final ChatCallProperties properties;

	public TokenCalibrationController(TokenBudgetGuard budgetGuard, TokenEstimator estimator,
			ChatService chatService, ChatCallProperties properties) {
		this.budgetGuard = budgetGuard;
		this.estimator = estimator;
		this.chatService = chatService;
		this.properties = properties;
	}

	/**
	 * 对一段文本做一次真实调用，返回估算值与真实用量。
	 *
	 * <p>示例：
	 * <pre>
	 * curl "http://localhost:8080/api/diag/token/calibrate?label=纯中文&amp;text=虚拟线程..."
	 * </pre>
	 */
	@GetMapping(value = "/calibrate", produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> calibrate(@RequestParam String text,
			@RequestParam(defaultValue = "未命名样本") String label) {
		return doCalibrate(label, text);
	}

	/**
	 * 校准的 POST 版本。
	 *
	 * <p><b>为什么必须有 POST</b>：GET 版本把文本放在 query string 里，
	 * 于是受 URL 长度限制。实测踩到：把中文重复 50 次（约 1150 字符）之后，
	 * 转义后的 URL 超过了 Tomcat 的请求头上限，直接返回 <b>400</b> ——
	 * 而脚本里那个 try/catch 把它吞了，前面一轮的结果被留在了变量里，
	 * 于是表格上出现两行"完全一样"的数据（连估算值都一模一样），
	 * <b>看起来像"文本变长了但 token 没变"这种重大发现，其实是请求根本没发出去</b>。
	 *
	 * <p>这件事值得记：做测量脚本时，<b>失败必须让它响</b>。
	 * 一个静默失败的样本比少一个样本危险得多 —— 它会被当成数据。
	 */
	@PostMapping(value = "/calibrate", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> calibrateByPost(@RequestBody CalibrationRequest request) {
		if (request == null || request.text() == null || request.text().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text 必填");
		}
		String label = request.label() == null || request.label().isBlank() ? "未命名样本" : request.label();
		return doCalibrate(label, request.text());
	}

	/** 校准请求体。文本走 body，避免 URL 长度上限。 */
	public record CalibrationRequest(String label, String text) {
	}

	private Map<String, Object> doCalibrate(String label, String text) {

		if (properties.getSampling().isStandInActive()) {
			// 替身模式下拿不到真实 usage，量出来的是自我欺骗
			throw new IllegalStateException(
					"替身已启用（ai.chat.sampling.latency），拿不到真实 token 用量；请去掉该参数后重启再校准");
		}

		// 估算：和预算校验走同一条链路（系统提示词 + 文本 + 包装开销）
		int estimated = budgetGuard.systemPromptTokens() + estimator.estimateMessage(text) + estimator.requestOverhead();

		// 真实：全新会话、只发一轮，保证 prompt 里只有这段文本 + 系统提示词
		String conversationId = "calib-" + UUID.randomUUID().toString().substring(0, 8);
		Map<String, Object> usage = callAndCollectUsage(conversationId, text);

		Integer actual = asInt(usage.get("promptTokens"));
		Double errorPercent = null;
		Double tokensPerChar = null;
		if (actual != null && actual > 0) {
			errorPercent = round((estimated - actual) * 100.0 / actual, 1);
			tokensPerChar = round(actual / (double) Math.max(1, text.length()), 2);
		}

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("label", label);
		body.put("charCount", text.length());
		body.put("estimatedPromptTokens", estimated);
		body.put("actualPromptTokens", actual);
		body.put("estimateErrorPercent", errorPercent);
		body.put("actualTokensPerChar", tokensPerChar);
		body.put("systemPromptTokens", budgetGuard.systemPromptTokens());
		body.put("breakdown", Map.of(
				"systemPrompt", budgetGuard.systemPromptTokens(),
				"message", estimator.estimateMessage(text),
				"requestOverhead", estimator.requestOverhead()));
		body.put("model", usage.get("model"));
		body.put("completionTokens", usage.get("completionTokens"));
		body.put("conversationId", conversationId);
		body.put("verdict", verdictOf(errorPercent));
		body.put("note", "估算与真实走的是同一套系数（ai.chat.estimation.*）；"
				+ "误差为正表示估算偏保守（提前裁剪），为负表示估算偏低（有超窗风险）");
		return body;
	}

	/**
	 * 不调模型的纯估算（快、不要钱），用于估算器的回归检查与前端展示。
	 *
	 * <p>它还顺带把"这段文本的账目构成"拆开摊出来 ——
	 * 排查"上下文为什么变贵了"时，需要的正是"贵在系统提示词、历史、还是这一句"。
	 */
	@GetMapping(value = "/estimate", produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> estimate(@RequestParam String text) {
		ChatCallProperties.Estimation cfg = properties.getEstimation();
		int messageTokens = estimator.estimateMessage(text);

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("charCount", text.length());
		body.put("estimatedTokens", messageTokens);
		body.put("breakdown", Map.of(
				"body", estimator.estimate(text),
				"messageOverhead", cfg.getTokensPerMessage(),
				"systemPrompt", budgetGuard.systemPromptTokens(),
				"requestOverhead", estimator.requestOverhead()));
		body.put("totalWithSystemPrompt", messageTokens + budgetGuard.systemPromptTokens() + estimator.requestOverhead());
		body.put("coefficients", Map.of(
				"asciiCharsPerToken", cfg.getAsciiCharsPerToken(),
				"cjkTokensPerChar", cfg.getCjkTokensPerChar(),
				"otherTokensPerChar", cfg.getOtherTokensPerChar()));
		body.put("budget", Map.of(
				"contextWindow", properties.getBudget().getContextWindow(),
				"usableBudget", properties.getBudget().usableBudget(),
				"reservedForOutput", properties.getBudget().reservedForOutput(),
				"inputAllowance", properties.getBudget().inputAllowance()));
		return body;
	}

	/** 系统提示词本身占多少（启动时算好的那个数），以及它的字符数。 */
	@GetMapping(value = "/system-prompt", produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> systemPrompt() {
		String text = readPrompt();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("location", TokenBudgetGuard.DEFAULT_SYSTEM_PROMPT_LOCATION);
		body.put("charCount", text.length());
		body.put("estimatedTokens", budgetGuard.systemPromptTokens());
		body.put("note", "系统提示词是 advisor 在调用阶段注入的，业务代码里看不见，但它实实在在占窗口 —— 所以预算必须算它");
		return body;
	}

	/**
	 * 发一次流式调用，等流结束后取最后一次非空的 token 用量。
	 *
	 * <p>为什么用流式而不是 {@code .call()}：用量是随流的最后一个 chunk 一起回来的，
	 * 这样能<b>在同一次请求里</b>同时拿到文本和用量，不必额外发第二次请求做统计。
	 * 这与 {@code ChatServiceImpl.stream} 的选择是同一个理由。
	 */
	private Map<String, Object> callAndCollectUsage(String conversationId, String text) {
		ChatClientResponse last = chatService.stream(conversationId, text, 0.0)
				.filter(response -> response.chatResponse() != null
						&& response.chatResponse().getMetadata() != null
						&& response.chatResponse().getMetadata().getUsage() != null
						&& response.chatResponse().getMetadata().getUsage().getPromptTokens() != null)
				.blockLast();

		if (last == null) {
			log.warn("校准调用没有拿到 token 用量 conversationId={}", conversationId);
			return Map.of();
		}
		return chatService.usageOf(last);
	}

	private static String readPrompt() {
		try (var in = new ClassPathResource("prompts/system-agent.st").getInputStream()) {
			return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			return "";
		}
	}

	private static String verdictOf(Double errorPercent) {
		if (errorPercent == null) {
			return "无法判定（没拿到真实用量）";
		}
		if (errorPercent < -15) {
			return "估算偏低超过 15%：有超窗风险，应调大系数或提高安全边际";
		}
		if (errorPercent > 40) {
			return "估算偏保守较多：会提前裁历史或误拒，可适当调小系数";
		}
		return "误差在可接受范围内（估算略偏保守对预算校验是安全方向）";
	}

	private static Integer asInt(Object value) {
		if (value instanceof Number number) {
			return number.intValue();
		}
		return null;
	}

	private static double round(double value, int digits) {
		double factor = Math.pow(10, digits);
		return Math.round(value * factor) / factor;
	}

}
