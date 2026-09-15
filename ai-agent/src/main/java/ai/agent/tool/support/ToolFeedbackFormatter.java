package ai.agent.tool.support;

import ai.agent.tool.AgentTool;
import ai.agent.tool.AgentToolResult;
import ai.agent.tool.ToolInvocation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把工具调用的结果 / 拒绝原因渲染成<b>回填给模型的文本</b>。
 *
 * <h2>为什么单独一个类，而不是在 Executor 里拼字符串</h2>
 * 这段文本是<b>整条链路里唯一被模型看到的东西</b> —— 它决定了模型能不能自愈。
 * 把它当成"日志拼接"随手写在执行器里，结果是每次出错都临时补一句，
 * 最后谁也不知道失败一回填到底长什么样、更没法比对"哪种回填更能让模型改对"。
 * 独立出来之后，它成了可以单独测试、单独演进的一层。
 *
 * <h2>四条硬规则（每一条都对应一种"模型学不会"的失败）</h2>
 * <ol>
 *   <li><b>错误里必须带可用值</b>。这是路线文档的原话（「错误信息里带可用值，模型才能自愈」）。
 *       "工具不存在"之后模型最常见的动作是<b>再编一个</b>；写成"不存在，可用的是 a、b、c"，
 *       它才能改对。同理：字段不认识 → 列出可用字段；枚举值越界 → 列出合法取值。</li>
 *   <li><b>必须说清"能不能重试"</b>。同一个失败，一种是"改个参数再来"，
 *       一种是"别试了"。不说清，模型会原样重试 —— 而在 {@code UNKNOWN_TOOL}
 *       和 {@code EXECUTION_ERROR} 上原样重试是纯浪费一轮。</li>
 *   <li><b>截断必须显式说明</b>。结果被截断却不说，模型会以为"这就是全部"，
 *       于是拿一段被砍掉尾巴的检索结果去回答 —— 而它给出的是一个<b>看起来完整</b>的答案。
 *       这是本项目里最接近"静默错误"的一种形态，必须在文本里喊出来。</li>
 *   <li><b>绝不把内部实现细节泄漏给模型</b>。Java 类名、包路径、堆栈对模型毫无用处，
 *       只会挤占上下文并诱导它去"解释"这些符号。剥到最内层消息即可
 *       （{@code ToolCallExecutor} 已经剥过一层，这里再兜一次长度）。</li>
 * </ol>
 *
 * <h2>结果长度上限为什么是配置项</h2>
 * 它直接对应"每次工具调用烧多少 token"：检索类工具最容易踩 ——
 * 一次返回 10 个块、每块 200 token，就是 2000 token 进上下文，而其中往往只有一两条有用。
 * 做成 {@code ai.tool.feedback.max-chars} 是为了能实测它在不同取值下的代价
 * （见台账里"检索预算纳入 Token 账目"那一条：这个上限就是那个口径在工具层的入口）。
 */
@Component
public class ToolFeedbackFormatter {

	/** 回填文本的字符上限。0 或负数表示不限制（不推荐：模型可能吐出一个几 KB 的查询词）。 */
	private final int maxChars;

	public ToolFeedbackFormatter(@Value("${ai.tool.feedback.max-chars:2000}") int maxChars) {
		this.maxChars = maxChars;
	}

	public int maxChars() {
		return maxChars;
	}

	/**
	 * 工具不存在。
	 *
	 * <p>句式刻意是"拒绝 + 纠正"而不是"道歉"：模型对"抱歉"的典型反应是重新措辞同一件事，
	 * 而对"可用的是 X、Y、Z"的反应才是换工具。
	 */
	public String unknownTool(ToolInvocation invocation, List<String> availableTools) {
		return """
				工具调用被拒绝：没有名为「%s」的工具。
				你提出的参数是：%s
				可用工具：%s
				请从上面这些工具里重新选择。如果你认为缺少一个必要的工具，就直接用文字回答，不要编造工具名。"""
				.formatted(nullSafe(invocation.name()), invocation.argumentsForFeedback(200),
						availableTools.isEmpty() ? "（当前没有任何工具可用）" : String.join("、", availableTools));
	}

	/** 参数不是合法 JSON / 形状不对。建议指向"JSON 语法与结构"，而不是"值对不对"。 */
	public String parseError(ToolInvocation invocation, AgentTool<?> tool, String reason) {
		return """
				工具「%s」的参数无法解析：%s
				你提出的参数是：%s
				该工具的参数必须是这样一个 JSON 对象（字段名与类型必须完全一致）：
				%s
				请重新给出参数。注意：不要用 markdown 代码块包裹，不要加解释文字，不要增加 schema 里没有的字段。"""
				.formatted(tool.name(), reason, invocation.argumentsForFeedback(300),
						tool.getToolDefinition().inputSchema());
	}

	/**
	 * 参数合法 JSON 但内容不合格。
	 *
	 * <p>这是<b>最有可能靠一轮回填救回来</b>的一类，所以这里的文本最详细：
	 * 逐条列出"哪个字段错在哪"（{@code issues} 里已经带了字段名）。
	 */
	public String validationError(ToolInvocation invocation, AgentTool<?> tool, List<String> issues) {
		StringBuilder list = new StringBuilder();
		for (String issue : issues) {
			list.append("- ").append(issue).append('\n');
		}
		return """
				工具「%s」的参数不合法，共 %d 处问题：
				%s
				你提出的参数是：%s
				参数需要满足的结构要求：
				%s
				请只修正上面列出的问题，其它字段保持原样，重新给出**完整**的参数 JSON。"""
				.formatted(tool.name(), issues.size(), list, invocation.argumentsForFeedback(300),
						tool.getToolDefinition().inputSchema());
	}

	/** 参数没问题但工具自己炸了。要明确告诉模型"重试同样的调用没用"。 */
	public String executionError(ToolInvocation invocation, AgentTool<?> tool, String reason) {
		return """
				工具「%s」执行时出错：%s
				参数本身是合法的，所以**原样重试不会有不同结果**。
				可以选择：换一个参数（例如换更宽泛的查询条件）、换一个工具，或者直接用你已有的信息回答。
				如果这个问题看起来是系统性的（多次都失败），请在回答里说明该工具当前不可用。"""
				.formatted(tool.name(), reason);
	}

	/**
	 * 正常结果的回填。
	 *
	 * <p>成功也用醒目的小标题包起来（而不是把裸文本丢回去）：模型需要能明确区分
	 * "这是我调工具拿回来的"与"这是用户说的"—— 混在一起时，它会把工具返回的内容
	 * 当成用户的输入继续对话，这在检索类工具上表现为"开始和文档对话"。
	 */
	public String resultFeedback(ToolInvocation invocation, AgentTool<?> tool, AgentToolResult result) {
		String callId = invocation.id() == null || invocation.id().isBlank() ? "(无 id)" : invocation.id();
		return switch (result) {
			case AgentToolResult.Success success -> {
				String body = limit(success.payload());
				String truncNote = success.truncated()
						? "\n【注意】上面的结果因为过长被截断了，你看到的内容**不完整**。"
								+ "如果需要被截掉的部分，请用更精确的查询重新调用这个工具。"
						: "";
				yield "【工具「%s」的返回结果｜call_id=%s】\n%s%s".formatted(tool.name(), callId, body, truncNote);
			}
			case AgentToolResult.Failure failure -> {
				String advice = failure.retriable()
						? "这个失败是暂时的：可以稍后用同样的参数再试一次，或者换一个参数/工具。"
						: "这个失败不是暂时的：**用同样的参数重试不会有不同结果**，请改变查询方式或换用其它工具。";
				yield "【工具「%s」未能完成｜code=%s｜call_id=%s】\n%s\n%s".formatted(tool.name(), failure.code(), callId,
						limit(failure.message()), advice);
			}
		};
	}

	/**
	 * 超长截断。返回的文本里**必须**留下截断标记 —— 见类注释规则 3。
	 *
	 * <p>标记写在末尾而不是开头：模型是顺序读的，末尾的标记正好在它准备开始总结的时候出现。
	 */
	private String limit(String text) {
		String body = text == null ? "" : text;
		if (maxChars <= 0 || body.length() <= maxChars) {
			return body;
		}
		return body.substring(0, maxChars) + "\n…（内容超过 " + maxChars + " 字符，此处截断）";
	}

	private static String nullSafe(String value) {
		return value == null || value.isBlank() ? "(空)" : value;
	}

}
