package ai.agent.agent;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * "问模型一轮"这件事的<b>唯一</b>抽象 —— 手写 ReAct 循环的可测性就建立在这一个接口上。
 *
 * <h2>为什么必须有这个接口（而不是直接注入 ChatModel）</h2>
 * 路线文档要求「手写 ReAct 循环（think → act → observe），<b>不用框架先写一遍</b>」。
 * 一旦循环里直接调 {@code ChatModel}，循环的<b>全部逻辑</b>
 * （轮数上限、token 上限、时长上限、重复检测、回填顺序、停止条件）
 * 就都只能靠"起一个真后端 + 真 key + 让模型配合地犯错"来验证 ——
 * 而模型不会配合：它可能一次就答对，于是"工具失败后能不能自愈"这条路径<b>永远没被跑到</b>。
 *
 * <p>{@link ScriptedModelTurn}（测试用）就是为此存在的：它按脚本返回预设的响应，
 * 于是可以确定性地构造出"模型第一次编了个工具名、第二次才改对"这种序列。
 * 这与本项目在结构化输出上用替身跑"必然降级"是同一种做法。
 *
 * <h2>为什么它<b>不</b>返回 Spring AI 的 ChatResponse</h2>
 * 返回框架类型会让"用脚本替换模型"这件事需要构造框架的完整对象图
 * （ChatResponse → Generation → AssistantMessage → metadata），
 * 测试会被框架的构造细节绑住 —— 升一个小版本就可能编译不过，
 * 而那时失败原因看起来像"业务代码坏了"。
 *
 * <p>所以这里只保留循环真正要用的三样东西：<b>文字输出、工具调用意图、token 用量</b>。
 * 适配框架的活儿全部留在 {@code OpenAiModelTurn} 里（唯一一处 import 框架的地方）。
 */
public interface ModelTurn {

	/**
	 * 发一轮请求。
	 *
	 * <p>{@code messages} 是<b>完整</b>的对话（系统提示词 + 用户问题 + 之前的 assistant/tool 消息）。
	 * 由循环维护而不是由框架 advisor 维护 —— 这是"手写一遍"的意义所在：
	 * 上下文里到底有什么，是循环自己的责任，看得见、测得到。
	 */
	ModelReply chat(List<Message> messages);

	/** 是否把工具定义一起发给模型（诊断用：能看出这轮有没有真的开工具）。 */
	default boolean toolCallingEnabled() {
		return true;
	}

	/**
	 * 一轮模型回复。
	 *
	 * @param text 文字输出（ReAct 的 think；可能为空）
	 * @param toolCalls 模型提出的工具调用意图（空 = 它选择直接作答）
	 * @param promptTokens 输入 token；上游没报时为 0
	 * @param completionTokens 输出 token；上游没报时为 0
	 * @param truncatedByLength 是不是因为 max_tokens 被截断
	 *            （{@code finish_reason=length}）。<b>必须显式带出来</b>：
	 *            被截断的 JSON 参数会解析失败，而那时错误看起来像"模型不会写 JSON"，
	 *            真正的原因是我们给的输出额度太小
	 */
	record ModelReply(String text, List<ai.agent.tool.ToolInvocation> toolCalls, int promptTokens,
			int completionTokens, boolean truncatedByLength) {

		public ModelReply {
			toolCalls = List.copyOf(toolCalls);
		}

		public static ModelReply text(String text, int promptTokens, int completionTokens) {
			return new ModelReply(text, List.of(), promptTokens, completionTokens, false);
		}

		/** 这一轮的 token 合计。 */
		public int tokens() {
			return promptTokens + completionTokens;
		}

		/** 模型有没有要求调工具。 */
		public boolean wantsTools() {
			return !toolCalls.isEmpty();
		}

	}

}
