package ai.agent.service.agent;

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
	 * 这一层实际在用的模型名（阶段四成本看板）。
	 *
	 * <h2>为什么它在接口上，而不是让成本侧去读配置</h2>
	 * 因为"配置里写的是哪个模型"与"这一轮真的用了哪个模型"是两件事：
	 * 测试会注入脚本化替身、将来的灰度会按请求换模型、包装层（并发闸门）会让
	 * 真实类型不再是那个适配器。**成本表绝对不该按"我以为在用的模型"计价** ——
	 * 那会算出一个看起来完全正常的错钱数。
	 *
	 * <p>放在接口上的代价是要给默认实现，而默认值刻意选了一个**不能被误认成模型名**
	 * 的字符串：看板上会出现「未上报模型名」这一行，且它<b>不参与计价</b>
	 * （没有价格规则就是没有价格，不能拿别的模型的价去顶）。这与
	 * "认不出来就诚实地说认不出来"是同一条规矩（见 {@code ModelFailureClassifier} 的兜底）。
	 */
	default String modelName() {
		return UNKNOWN_MODEL;
	}

	/** 取不到模型名时的占位值。<b>不是一个模型名</b>，看板会据此拒绝计价。 */
	String UNKNOWN_MODEL = "未知模型";

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
	 * @param cachedPromptTokens 输入里**命中上游提示词缓存**的那部分 token（阶段四成本看板）。
	 *            <b>它是 promptTokens 的子集，不是额外的一档</b> ——
	 *            prompt 总量恒等于「命中缓存 + 未命中缓存」。
	 *            <p>为什么要把它单独带出来：缓存命中与未命中的单价差一个数量级，
	 *            而 promptTokens 是两者之和。只留合计的话，成本只能按最贵的单价算，
	 *            误差永远是"高估"，且高估多少取决于上游的缓存命中率 ——
	 *            一个我们既看不见也调不了的量。
	 *            <p>缺省值 0 的语义是明确的：**"没有命中的"**，也就是按最贵那一档算（偏保守）。
	 *            上游没这个能力时它恒为 0，看板上会显示命中率 0% 而不是一个编出来的数
	 */
	record ModelReply(String text, List<ai.agent.service.tool.ToolInvocation> toolCalls, int promptTokens,
			int completionTokens, boolean truncatedByLength, int cachedPromptTokens) {

		public ModelReply {
			toolCalls = List.copyOf(toolCalls);
			if (cachedPromptTokens < 0) {
				throw new IllegalArgumentException("命中缓存的 token 数不能为负：" + cachedPromptTokens);
			}
			if (cachedPromptTokens > promptTokens) {
				// 命中量是输入量的子集，超过它说明上游报的数自相矛盾。
				// **在这里抛而不是截断**：截断会把一个数据问题变成一个静默的错数字，
				// 而成本表恰恰是最不该出现"看起来正常的错数字"的地方
				throw new IllegalArgumentException(
						"命中缓存的 token（%d）不可能超过输入 token（%d）—— 上游的 usage 自相矛盾"
								.formatted(cachedPromptTokens, promptTokens));
			}
		}

		/** 不带缓存信息的构造器（脚本化模型与旧调用点用）。 */
		public ModelReply(String text, List<ai.agent.service.tool.ToolInvocation> toolCalls, int promptTokens,
				int completionTokens, boolean truncatedByLength) {
			this(text, toolCalls, promptTokens, completionTokens, truncatedByLength, 0);
		}

		public static ModelReply text(String text, int promptTokens, int completionTokens) {
			return new ModelReply(text, List.of(), promptTokens, completionTokens, false, 0);
		}

		/** 这一轮的 token 合计。 */
		public int tokens() {
			return promptTokens + completionTokens;
		}

		/**
		 * 这一轮里**按未命中缓存单价计费**的输入 token。
		 *
		 * <p>单独一个派生量而不是让计费侧自己减：这个减法只有一个正确写法，
		 * 而写成 {@code promptTokens + cached} 或者忘了减都会得到一个偏高的成本 ——
		 * 后者不报错、只是钱数不对。
		 */
		public int uncachedPromptTokens() {
			return promptTokens - cachedPromptTokens;
		}

		/** 模型有没有要求调工具。 */
		public boolean wantsTools() {
			return !toolCalls.isEmpty();
		}

	}

}
