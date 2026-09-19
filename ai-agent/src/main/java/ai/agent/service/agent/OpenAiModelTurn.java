package ai.agent.service.agent;

import ai.agent.entity.tool.ToolInvocation;
import ai.agent.service.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ModelTurn} 的生产实现：接真实的 OpenAI 兼容模型。
 *
 * <h2>本类里最重要的一个决定：用 {@code ChatModel} 而不是 {@code ChatClient}</h2>
 * 这不是风格偏好，而是<b>正确性</b>问题。2.0.1 实测：{@code ChatClient} 在
 * 请求里带上工具之后会<b>自动执行工具</b>（{@code DefaultToolCallingManager.executeToolCalls}），
 * 执行完再把结果塞回去自动重问 —— 也就是说，"循环"会由框架跑起来。
 *
 * <p>那对本项目是致命的，原因很具体：框架执行工具走的是
 * {@code ToolCallback.call(String)}，而本项目的 {@code AgentTool} 把那个方法实现成
 * <b>故意抛异常</b>（所有执行必须经过 {@code ToolCallExecutor} 才有白名单、校验、
 * 分类与计时）。于是框架一旦接管，结果是<b>每一次工具调用都抛异常</b> ——
 * 而且症状会表现为"模型调什么工具都失败"，排查方向很容易跑到模型或工具实现上去。
 *
 * <p>{@code ChatModel.call(Prompt)} <b>不做这件事</b>：它把模型返回的
 * {@code tool_calls} 原样交出来，执不执行由调用方决定。所以本类只做"翻译"
 * （框架类型 ↔ 本项目的类型），执行权留在 {@link AgentLoop} 手里。
 *
 * <h2>翻译里两个必须显式处理的点</h2>
 * <ol>
 *   <li><b>{@code finish_reason = length}</b>：模型因为输出额度被截断时，
 *       工具参数可能是半截 JSON，解析必然失败。不把这个信号带出来，
 *       报告里会写成"模型输出了非法 JSON"—— 而真正的原因是我们给的 max_tokens 太小。
 *       这是"同一现象两种归因、修法完全相反"的典型。</li>
 *   <li><b>token 用量可能缺失</b>：不是所有兼容端点都返回 usage。
 *       缺的时候记 0 而不是抛异常 —— 但要在日志里说一次，
 *       因为"用量恒为 0"会让阶段四的成本看板一直显示零成本，而那是<b>看起来很正常的错数据</b>。</li>
 * </ol>
 *
 * <h2>⚠️ 这个类<b>刻意没有</b> {@code @Component}（这是一个踩过的坑，改它之前先读完）</h2>
 * 它由 {@code AgentConfig.openAiModelTurn(...)} 显式装配 —— 那里还要在它<b>外面</b>
 * 包一层 {@code LimitedModelTurn}（并发闸门）。
 *
 * <p>第一版这里标着 {@code @Component}，于是<b>同一个类有两个 bean 定义</b>：
 * 组件扫描一个、{@code @Bean} 方法一个。而 {@code @Bean} 上的
 * {@code @ConditionalOnMissingBean(ModelTurn.class)} 看到组件扫描那个已经存在，
 * 就<b>跳过了整个方法</b>——包括那层包装。最终容器里是<b>裸的适配器</b>。
 *
 * <p><b>为什么这个坑极难发现</b>：它不报错、不抛异常、不产生歧义异常
 * （因为条件装配"正确地"跳过了一个定义）。它唯一的表现是
 * <b>并发闸门在真实路径上没生效</b> —— 而这件事只有在你主动去问
 * "现在装的到底是哪个实现"时才会暴露。
 * 发现它的方式是：真后端上打开 {@code /api/agent/limits}，
 * 看到 {@code modelGate.available=false}，再顺着查到 bean 类型是 {@code OpenAiModelTurn}。
 *
 * <p><b>结论</b>：一个类只能由一个地方负责注册。
 * 要么全部走组件扫描（那就不需要 {@code @Bean}，但也包不上闸门），
 * 要么全部走 {@code @Bean}（那就<b>不能</b>再标 {@code @Component}）。
 * 两者并存时，条件装配会安静地选择其中一个 —— 而"安静"就是这里最贵的地方。
 */
public class OpenAiModelTurn implements ModelTurn {

	private static final Logger log = LoggerFactory.getLogger(OpenAiModelTurn.class);

	private final ChatModel chatModel;

	private final ToolRegistry registry;

	/** usage 缺失只提醒一次，避免每次调用都刷日志。 */
	private volatile boolean usageMissingReported;

	/** 上游"命中量 > 输入量"这种自相矛盾只提醒一次（理由见 translate 里的注释）。 */
	private volatile boolean cacheAnomalyReported;

	/**
	 * 当前实际在用的模型名。
	 *
	 * <p>取自 Spring AI 自己的配置（{@code spring.ai.openai.chat.options.model}），
	 * 而<b>不是</b>我们另抄一份到自己的配置里 —— 抄一份的话，改了
	 * {@code spring.ai.openai.*} 而忘了改我们那份，成本就会按一个**根本没用过的模型**计价，
	 * 而页面上一切正常。这类"同一个事实有两份来源"的漂移，本项目的做法一律是
	 * 找出真正的那一份，另一份删掉。
	 *
	 * <p>用 {@code ObjectProvider} 而不是直接注入属性类：那个 bean 由 Spring AI 的自动配置提供，
	 * 而**测试里不一定存在**（替换掉 ChatModel 时它可能不在）。
	 * 取不到时退回 {@link ModelTurn#UNKNOWN_MODEL}，看板显示"未上报"并**拒绝计价** ——
	 * 而不是编一个模型名出来。
	 */
	private final org.springframework.beans.factory.ObjectProvider<org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties> chatProperties;

	public OpenAiModelTurn(ChatModel chatModel, ToolRegistry registry,
			org.springframework.beans.factory.ObjectProvider<org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties> chatProperties) {
		this.chatModel = chatModel;
		this.registry = registry;
		this.chatProperties = chatProperties;
	}

	/**
	 * 配置里那一份模型名（唯一来源：{@code spring.ai.openai.chat.options.model}）；
	 * 取不到时返回 {@code null} —— {@link #modelName()} 把它换成 {@link #UNKNOWN_MODEL}（看板据此拒绝计价），
	 * {@link #chat} 把它当成"配置缺失"直接报错。
	 */
	private String configuredModel() {
		var properties = chatProperties.getIfAvailable();
		String model = properties == null ? null : properties.getModel();
		return model == null || model.isBlank() ? null : model;
	}

	@Override
	public String modelName() {
		String model = configuredModel();
		return model == null ? UNKNOWN_MODEL : model;
	}

	@Override
	public ModelReply chat(List<Message> messages) {
		String model = configuredModel();
		if (model == null) {
			// 不"让它空着"：空着不会让请求不带模型，那个位置会被 SDK 默认值顶上（见下面 options 的注释），
			// 而上游只会报"你传了一个我不支持的模型名"，看不出是配置少了一行
			throw new IllegalStateException(
					"spring.ai.openai.chat.options.model 没配（或为空）—— 没有模型名就不发请求");
		}

		// 工具定义随请求发出：模型据此知道能调什么、参数长什么样。
		// 注意 toolCallbacks 里放的是 AgentTool 本身（它实现 ToolCallback）——
		// 但**不会**被框架执行，因为走的是 ChatModel.call 而不是 ChatClient（见类注释）
		//
		// 【model 必须显式写】2.0.1 的 OpenAiChatOptions 表达不了"模型名未设置"：构造器在 model 为 null 时
		// 填 SDK 默认的 "gpt-5-mini"；而 OpenAiChatModel 对带了 options 的 Prompt 是原样使用
		// （buildRequestPrompt 字节码：不与环境里那份默认 options 合并）。少这一行，请求发出的就是 gpt-5-mini。
		// 实测（假上游抓请求体）：不写 → gpt-5-mini，写 → deepseek-flash。
		//
		// 走 ChatClient 的两处（ChatServiceImpl / StructuredChatServiceImpl）不受这条约束：
		// 它们传的是 Builder，合并在 builder 层（getOptions().mutate().combineWith(请求 builder)），
		// 未设置的字段仍是 null。
		//
		// 【必须用 OpenAiChatOptions，不能用通用的 ToolCallingChatOptions】
		// ToolCallingChatOptions.builder() 造出来的是 DefaultToolCallingChatOptions，
		// 而 OpenAiChatModel 内部会把它**强转成 OpenAiChatOptions**，于是运行期直接：
		//   ClassCastException: DefaultToolCallingChatOptions cannot be cast to OpenAiChatOptions
		//
		// 它的症状：这个异常被 AgentLoop 兜住、变成 MODEL_ERROR 的 run 报告（模型失败不该炸掉会话，
		// 已花的成本要带回来）。于是表面上"只是模型调用失败"，真正的原因是 options 的类型传错了。
		// 这条路径必须**真的经过这个适配器**才会暴露：在 ModelTurn 接口上打桩的替身根本不经过这里。
		OpenAiChatOptions options = OpenAiChatOptions.builder()
				.model(model)
				.toolCallbacks(new ArrayList<>(registry.all()))
				.build();

		Prompt prompt = new Prompt(messages, options);
		ChatResponse response = chatModel.call(prompt);

		return translate(response);
	}

	private ModelReply translate(ChatResponse response) {
		if (response == null || response.getResult() == null) {
			// 空响应：交给 AgentLoop 判成 EMPTY_RESPONSE，而不是在这里抛 ——
			// "上游给了空响应"是一种可观测的失败，应该出现在报告里
			log.warn("模型返回了空响应（response 或 result 为 null）");
			return new ModelReply("", List.of(), 0, 0, false);
		}

		Generation generation = response.getResult();
		AssistantMessage output = generation.getOutput();
		String text = output == null || output.getText() == null ? "" : output.getText();

		List<ToolInvocation> invocations = new ArrayList<>();
		if (output != null) {
			for (AssistantMessage.ToolCall call : output.getToolCalls()) {
				invocations.add(ToolInvocation.from(call.id(), call.name(), call.arguments()));
			}
		}

		boolean truncated = isLengthTruncated(generation, output);
		if (truncated) {
			log.warn("模型输出因 max_tokens 被截断（finish_reason=length）—— 工具参数可能是半截 JSON，"
					+ "这会被报成「参数解析失败」，但真正原因是输出额度太小");
		}

		int promptTokens = 0;
		int completionTokens = 0;
		int cachedPromptTokens = 0;
		var metadata = response.getMetadata();
		if (metadata != null) {
			Usage usage = metadata.getUsage();
			if (usage != null) {
				promptTokens = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
				completionTokens = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
				// 【阶段四成本看板】命中提示词缓存的那部分输入 token。
				//
				// 它不是 Spring AI 自造的概念：`Usage.getCacheReadInputTokens()` 读的就是
				// OpenAI 协议里的 `prompt_tokens_details.cached_tokens`（用 javap 核过
				// `OpenAiChatModel` 的字节码：它取的正是
				// `CompletionUsage$PromptTokensDetails.cachedTokens()`）。
				// 兼容端点的做法是把 `prompt_cache_hit_tokens` 映射到同一个位置。
				//
				// ⚠️ 这里**必须夹紧到 [0, promptTokens]**，理由有两条，都是"不这么写就出错"：
				//   ① 上游没报这个字段时它是 null → 0，语义正好是"全部未命中"（按最贵那档算，偏保守）；
				//   ② 有些兼容端点的总数与明细**对不上**（总数不含明细、或明细比总数大）。
				//      不夹紧的话，费用计算会算出"命中比总量还多"，也就是**负的未命中量** ——
				//      一个能让成本变成负数（或小于真实值）的错数字，而且在页面上完全看不出来。
				//      夹紧的代价只是"上游少报的那部分按未命中算"，方向是安全的那一侧。
				Long cached = usage.getCacheReadInputTokens();
				if (cached != null && cached > 0) {
					cachedPromptTokens = (int) Math.min(cached, promptTokens);
				}
				// 命中量比总量大是上游数据的问题，必须留痕：静默夹紧会让这个上游缺陷
				// 永远不被人发现，而它影响的是**所有**成本数字
				if (cached != null && cached > promptTokens && !cacheAnomalyReported) {
					cacheAnomalyReported = true;
					log.warn("上游报的命中缓存 token（{}）大于输入 token（{}）—— 已按输入总量夹紧。"
							+ "这会让成本按最贵那一档计算（偏保守），但数据源本身有问题，建议核对上游的 usage 字段",
							cached, promptTokens);
				}
			}
		}
		if (promptTokens == 0 && completionTokens == 0 && !usageMissingReported) {
			usageMissingReported = true;
			log.warn("本次模型响应没有返回 token 用量，Agent 的 token 上限与成本统计会一直记 0。"
					+ "如果这不是预期，请检查上游端点是否支持 usage 字段（成本看板会因它显示零成本而看起来正常）");
		}

		return new ModelReply(text, invocations, promptTokens, completionTokens, truncated, cachedPromptTokens);
	}

	/**
	 * 判断是不是"因为输出额度被截断"。
	 *
	 * <p>两个来源都看：Spring AI 的 {@code finishReason} 字段，
	 * 以及 OpenAI 协议里的 {@code finish_reason=length}。
	 * 只看一个的话，某些兼容端点的响应会漏判 —— 而漏判的后果是
	 * 一条"模型输出了非法 JSON"的错误结论。
	 */
	private static boolean isLengthTruncated(Generation generation, AssistantMessage output) {
		if (generation.getMetadata() != null && generation.getMetadata().getFinishReason() != null) {
			String reason = generation.getMetadata().getFinishReason();
			if ("length".equalsIgnoreCase(reason) || "max_tokens".equalsIgnoreCase(reason)) {
				return true;
			}
		}
		// 有些实现把 finish_reason 放在消息上；工具调用被截断时最典型的形态是
		// "有 toolCall 但 arguments 是半截/空的 JSON"
		if (output != null && output.hasToolCalls()) {
			for (AssistantMessage.ToolCall call : output.getToolCalls()) {
				if (call.arguments() != null && !call.arguments().isBlank() && !isLikelyCompleteJson(call.arguments())) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * 粗判一段字符串是不是"看起来完整"的 JSON 对象/数组。
	 *
	 * <p>刻意<b>不</b>用真正的 JSON 解析：这里的用途只是"猜测上游是不是截断了"，
	 * 而解析失败本身会在 {@code ToolCallExecutor} 里被精确处理。
	 * 在这里做一次严格解析反而会制造第二个错误来源 ——
	 * 而且是"同一个问题在两处各报一次、措辞不同"的那种。
	 */
	private static boolean isLikelyCompleteJson(String raw) {
		String trimmed = raw.strip();
		char last = trimmed.charAt(trimmed.length() - 1);
		return last == '}' || last == ']' || last == '"';
	}

}
