package ai.agent.service.agent;

import ai.agent.service.tool.ToolInvocation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按脚本返回预设回复的假模型 —— <b>只用于测试</b>。
 *
 * <h2>它解决的是"真模型不配合"这个根本问题</h2>
 * 手写 ReAct 循环要验证的能力里，有一大半<b>真模型不会帮你触发</b>：
 * <ul>
 *   <li>"模型编了一个不存在的工具名" —— 真模型大多数时候不会编；</li>
 *   <li>"模型重复调用同一个动作 3 次" —— 除非故意把工具描述写坏，否则很少发生；</li>
 *   <li>"模型先失败、看到回填后改对" —— 这是<b>自愈</b>，而自愈的前提是先犯错；</li>
 *   <li>"模型这一轮就答完" vs "它转了 6 轮才答" —— 无法指定。</li>
 * </ul>
 * 这些都是循环<b>最重要的分支</b>（正是它们决定了三重上限和自愈率有没有意义），
 * 而用真模型验证它们的办法只有"祈祷它配合"。
 *
 * <p>脚本化之后，每一条分支都能被确定性地跑一遍，而且断言可以精确到
 * "第 2 轮的请求里必须带上第 1 轮的错误回填" —— 这是"边界真的生效了"的直接证据。
 *
 * <h2>它同时是"请求也被记录下来"的地方</h2>
 * {@link #requests()} 保留每一轮收到的完整消息列表，于是测试可以反问：
 * <b>回填内容到底有没有真的发给模型？</b>
 * 只看 {@code ToolCallOutcome.feedback()} 证明不了这件事 —— 它可能被算出来了但从没进过请求
 * （这类"算了但没接线"的 bug 在本项目里出现过不止一次）。
 */
public class ScriptedModelTurn implements ModelTurn {

	private final List<ModelReply> script;

	/** 每一轮收到的消息快照（深拷贝成只读列表，供断言）。 */
	private final List<List<Message>> requests = new ArrayList<>();

	/** 脚本用完之后的兜底回复。 */
	private final ModelReply fallback;

	private int cursor;

	public ScriptedModelTurn(List<ModelReply> script) {
		this(script, ModelReply.text("脚本已用尽，模型不再产出新内容。", 0, 0));
	}

	public ScriptedModelTurn(List<ModelReply> script, ModelReply fallback) {
		this.script = List.copyOf(script);
		this.fallback = fallback;
	}

	@Override
	public ModelReply chat(List<Message> messages) {
		requests.add(List.copyOf(messages));
		if (cursor < script.size()) {
			return script.get(cursor++);
		}
		return fallback;
	}

	/** 全部收到的请求（按轮次）。 */
	public List<List<Message>> requests() {
		return List.copyOf(requests);
	}

	/** 第 N 轮（从 0 开始）收到的请求；越界返回空列表。 */
	public List<Message> requestAt(int index) {
		return index >= 0 && index < requests.size() ? requests.get(index) : List.of();
	}

	/** 实际发起了几轮请求。 */
	public int callCount() {
		return requests.size();
	}

	// ------------------------------------------------------------------
	//  脚本构造的便捷方法 —— 让测试读起来像"模型的行为剧本"
	// ------------------------------------------------------------------

	/** 模型直接给出文字回答。 */
	public static ModelReply answer(String text) {
		return ModelReply.text(text, 100, 20);
	}

	/** 模型要求调用一个工具。 */
	public static ModelReply callTool(String name, String argumentsJson) {
		return callTool("call_1", name, argumentsJson);
	}

	/** 模型要求调用一个工具（指定 call id）。 */
	public static ModelReply callTool(String id, String name, String argumentsJson) {
		return new ModelReply("", List.of(ToolInvocation.from(id, name, argumentsJson)), 120, 30, false);
	}

	/** 模型同时要求调用多个工具（并行工具调用）。 */
	public static ModelReply callTools(ToolInvocation... invocations) {
		return new ModelReply("", List.of(invocations), 150, 40, false);
	}

	/** 模型因为输出额度过小被截断（参数可能是半截 JSON）。 */
	public static ModelReply truncated(String partialText) {
		return new ModelReply(partialText, List.of(), 120, 200, true);
	}

	/** 模型给了文字、同时也要调工具（ReAct 里很常见的形态：先说要干什么）。 */
	public static ModelReply thinkThenCall(String thought, String name, String argumentsJson) {
		return new ModelReply(thought, List.of(ToolInvocation.from("call_1", name, argumentsJson)), 120, 35, false);
	}

	// ------------------------------------------------------------------
	//  断言辅助
	// ------------------------------------------------------------------

	/**
	 * 把某一轮请求里的消息渲染成可搜索的文本。
	 *
	 * <p>给断言用："第 2 轮的请求里必须包含『工具不存在』这几个字" ——
	 * 也就是"回填真的进了请求"。用文本而不是结构化比对，
	 * 是因为回填本来就是一段给人/给模型读的自然语言，
	 * 而结构化断言会要求测试知道具体的消息类型与顺序，一改就碎。
	 */
	public String requestTextAt(int index) {
		StringBuilder sb = new StringBuilder();
		for (Message message : requestAt(index)) {
			sb.append('[').append(role(message)).append("] ");
			sb.append(textOf(message)).append('\n');
		}
		return sb.toString();
	}

	private static String role(Message message) {
		return switch (message) {
			case SystemMessage ignored -> "system";
			case UserMessage ignored -> "user";
			case AssistantMessage ignored -> "assistant";
			default -> message.getMessageType().name().toLowerCase();
		};
	}

	private static String textOf(Message message) {
		if (message instanceof AssistantMessage assistant) {
			StringBuilder sb = new StringBuilder(assistant.getText() == null ? "" : assistant.getText());
			for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
				sb.append(" <tool_call:").append(call.name()).append(' ').append(call.arguments()).append('>');
			}
			return sb.toString();
		}
		if (message instanceof org.springframework.ai.chat.messages.ToolResponseMessage toolResponse) {
			StringBuilder sb = new StringBuilder();
			for (var response : toolResponse.getResponses()) {
				sb.append("<tool_response:").append(response.name()).append(' ').append(response.responseData())
						.append('>');
			}
			return sb.toString();
		}
		return message.getText() == null ? "" : message.getText();
	}

	/** 消息类型计数（断言"上下文里有没有多余的消息"时用）。 */
	public Map<String, Integer> requestShapeAt(int index) {
		Map<String, Integer> shape = new LinkedHashMap<>();
		for (Message message : requestAt(index)) {
			shape.merge(role(message), 1, Integer::sum);
		}
		return shape;
	}

}
