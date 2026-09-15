package ai.agent.tool.support;

import ai.agent.tool.AgentTool;
import ai.agent.tool.AgentToolResult;
import ai.agent.tool.ToolApprovalGate;
import ai.agent.tool.ToolCallStage;
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
	 * <b>正在等人批</b>（HITL）。
	 *
	 * <h2>这段文本有一个反直觉的要求：它不该诱导模型"重试"</h2>
	 * 其他所有回填都在回答"你下一步该做什么"。这里也一样，但答案很特殊：
	 * <b>什么都别做，等</b>。而这个答案对模型是有害的 ——
	 * 如果它把这句读成"稍后再试"，它会在下一轮立刻提出同样的调用，
	 * 于是界面上堆出一串待批（人批一个、又来一个），
	 * 而真正该发生的事是"整个任务停在这里，等人"。
	 *
	 * <p>所以文本里明确写了"不要重新发起"。而且正常情况下模型看不到这段文本 ——
	 * 循环在拿到 {@code AWAITING_APPROVAL} 时会<b>直接暂停</b>（见 {@code AgentLoop}）。
	 * 它出现在这里，是为了覆盖那些异常路径：
	 * 例如外部 MCP 客户端直接调到一个需要审批的写工具时，
	 * 那段文本就是返回给客户端的说明。
	 */
	public String awaitingApproval(ToolInvocation invocation, AgentTool<?> tool,
			ToolApprovalGate.ApprovalRequest request) {
		return """
				工具「%s」是一次**写操作**，按规则需要人工确认后才能执行。这次调用已被挂起：
				- 审批编号：%s
				- 它将会执行的参数：%s
				- 风险声明：%s
				它**还没有执行**（也没有失败）。在有人确认之前，请不要重新发起这次调用 ——
				重复发起只会产生多条待批记录，而不会让它更快被批准。
				如果你现在无法继续，就把当前进展和"在等什么确认"直接告诉用户。"""
				.formatted(tool.name(), request.approvalId(), invocation.argumentsForFeedback(500),
						request.hints().describe());
	}

	/**
	 * <b>人工确认未通过</b>（被拒绝，或等到超时）。
	 *
	 * <h2>为什么它必须与"执行异常"分开写</h2>
	 * 两者对模型都是"这次没干成"，但下一步动作完全不同：
	 * <ul>
	 *   <li>执行异常 → "换个参数或换个工具再试"；</li>
	 *   <li>人工拒绝 → <b>"不要再试了"</b>。原样重提会被人再拒一次，
	 *       而"再拒一次"不该由模型的重试来驱动 —— 那是骚扰审批人。</li>
	 * </ul>
	 * 所以这里的措辞是"停下、把情况交给用户"，而不是"改进后重试"。
	 *
	 * <p>拒绝理由必须原样带上：它是人给出的<b>唯一一条新信息</b>，
	 * 也是模型换方案时唯一的依据。吞掉它（只说"被拒绝了"）会让这次审批白做。
	 */
	public String approvalRejected(ToolInvocation invocation, AgentTool<?> tool, ToolCallStage stage, String reason,
			String approvalId) {
		String headline = stage == ToolCallStage.APPROVAL_TIMEOUT
				? "人工确认**超时**（在时限内没有人处理）"
				: "人工确认**被拒绝**";
		return """
				工具「%s」的%s，所以这次**没有执行**（审批编号：%s）。
				原因：%s
				⚠️ 不要再重新发起同样的调用：它会被**再次拦住**，需要人再确认一次，
				而这不会比现在更快得到批准。
				请改用只读工具收集更多信息、换一个不涉及写操作的方案，
				或者直接把情况告诉用户（说明你本来打算做什么、为什么被拦下）。"""
				.formatted(tool.name(), headline, approvalId == null ? "未记录" : approvalId, reason);
	}

	/**
	 * 被限流或熔断拒绝（阶段三第 3 件：并发控制）。
	 *
	 * <h2>这段回填的措辞是本轮最重要的产出之一</h2>
	 * 两种拒绝要让模型做<b>相反</b>的事，所以文本必须分开写：
	 * <ul>
	 *   <li>{@link ToolCallStage#REQUEST_REJECTED}（太挤了）：<b>等一会儿用同样的参数重试是对的</b>。
	 *       这时候如果让模型"换个工具试试"，它会在一个只要等一秒的问题上浪费好几轮预算，
	 *       而那些尝试同样会被拒；</li>
	 *   <li>{@link ToolCallStage#CIRCUIT_OPEN}（已经确定坏了）：<b>别再用这把工具</b>。
	 *       这时候如果让它"稍后重试"，它会一直撞到冷却期结束，同样浪费轮次。</li>
	 * </ul>
	 * 所以这里不是共用一段"调用被拒绝"的模板，而是按 stage 给出各自的下一步动作 ——
	 * 这与本类开头那条规则一致：<b>错误回填的价值在于告诉模型下一步做什么</b>。
	 */
	public String rejected(ToolInvocation invocation, AgentTool<?> tool, ToolCallStage stage, String reason) {
		String nextStep = switch (stage) {
			case REQUEST_REJECTED -> """
					这是**暂时性的拥塞**，不是工具坏了：**稍后用完全相同的参数重试是有意义的**。
					如果你现在还需要这个结果，可以再做一次同样的调用；也可以先用其它工具推进任务，
					过一会儿再回来补这一次。**不要**因为被拒就换一个语义不同的参数 —— 那会拿到不相关的数据。""";
			case CIRCUIT_OPEN -> """
					这把工具当前处于**熔断**状态（它的下游最近失败率过高），所以这一次调用**压根没有发出去**。
					**不要再用同样的参数重试**：在冷却期内它会被直接拒绝。
					请改用其它工具来获取信息；如果确实没有替代工具，就基于已有信息回答，
					并明确告诉用户"某类查询暂时不可用"。""";
			default -> "请稍后重试，或换用其它工具。";
		};
		return """
				工具「%s」的调用被拒绝（%s）：
				%s
				%s""".formatted(tool.name(), stage.name(), reason, nextStep);
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
