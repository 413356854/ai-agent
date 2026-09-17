package ai.agent.service.impl.tool;

import ai.agent.config.ReviewProperties;
import ai.agent.service.review.ReviewSeverity;
import ai.agent.entity.review.ReviewTicket;
import ai.agent.service.review.TicketStore;
import ai.agent.service.tool.AgentTool;
import ai.agent.service.tool.AgentToolResult;
import ai.agent.entity.tool.ToolCallContext;
import ai.agent.entity.model.tool.AgentToolDefinition;
import ai.agent.entity.model.tool.ToolHints;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 工具 ④：<b>提交一条人工审查工单</b> —— 本项目里第一把"写"工具。
 *
 * <h2>它为什么必须存在（不是"顺手多加一把工具"）</h2>
 * HITL 那一条卡在哪，当时写得很具体：
 * 「⚠️ 现在<b>三把工具全是只读的，这条没有真实场景可验证</b>：
 * 要做出可信的样子，必须先有至少一个『写』工具。**这一条与第 4 件是绑定的，别单独做。**」
 *
 * <p>所以这把工具的第一个作用是<b>让审批有东西可审批</b>。
 * 一个只读工具走审批流程，在测试里断言得再漂亮，也回答不了
 * 真正需要回答的那个问题：<b>"人拒绝之后，那个副作用真的没发生吗？"</b> ——
 * 而这需要有一个"副作用"能被观察。
 *
 * <h2>它的风险声明为什么是"非幂等、可能破坏性"</h2>
 * 调用它会<b>创建一条不可逆的记录</b>（工单一旦建出来，别人就会按它去干活）。
 * 而且它<b>不幂等</b>：模型重提一次、人又批一次，就可能多出一条内容几乎相同的工单 ——
 * 这恰好是路线文档面试追问里那一条「工具调用重试会不会产生重复副作用」的现实形态。
 *
 * <p>于是它触发 HITL 审批（见 {@link ToolHints#requiresApproval()}），
 * 而存储层还额外加了一道幂等窗口（见 {@link TicketStore#create}）——
 * 两道防线拦的是同一个风险，这不是冗余：<b>闸门可能被绕过（例如有人把 approval 关掉），
 * 而幂等性不会。</b>
 *
 * <h2>为什么"何时不要使用"这一段写得这么长</h2>
 * 这是"描述即 prompt"最需要发挥作用的地方。写工具是模型最容易滥用的东西：
 * 它会把"我觉得这个回答不太确定"也变成一条工单。所以描述里必须明确划出界线 ——
 * <b>建工单是一个有成本的动作（会占用别人的时间）</b>，而不是一种"留个记号"。
 */
@Component
public class ProposeReviewTool implements AgentTool<ProposeReviewTool.Params> {

	private static final Logger log = LoggerFactory.getLogger(ProposeReviewTool.class);

	private final TicketStore ticketStore;

	private final ReviewProperties properties;

	/**
	 * 本次调用的审批 id（由执行器通过 {@link #onContext} 交进来）。
	 *
	 * <p>字段是实例级的，但这<b>不是并发隐患</b>：工具是单例，而写操作在执行器里
	 * 被闸门保护，两次写调用不会同时在同一个实例上走到 {@link #invoke}。
	 * 更关键的是：即使它被串掉，串掉的也只是一个<b>审计字段</b>，
	 * 不会让"该审批的没审批"发生 —— 那个判断在闸门那一侧，
	 * 依据的是传进去的通行证，不是这个字段。
	 * 这个取舍写在注释里，而不是假装它不存在。
	 */
	private volatile String approvalId;

	public ProposeReviewTool(TicketStore ticketStore, ReviewProperties properties) {
		this.ticketStore = ticketStore;
		this.properties = properties;
	}

	@Override
	public String name() {
		return "propose_review";
	}

	@Override
	public AgentToolDefinition description() {
		return new AgentToolDefinition(name(),
				"向人工审查队列提交一条待处理工单（写操作：会在系统里创建一条记录，需要人工确认）。",
				"当你在查文档的过程中发现**具体的、需要人跟进的**问题时使用。典型场景："
						+ "文档里存在明显自相矛盾的结论、某个实验数据与结论对不上、"
						+ "用户明确要求「把这个记下来 / 提个工单」。"
						+ "标题要具体到能被独立看懂（例如「阶段二 · RAG：分块建议与实测结论相反」），"
						+ "不要写「文档有问题」这种无法据此行动的标题。",
				"⚠️ **不要**用它记录你自己的不确定、也不要用它当「留个记号」："
						+ "创建工单会占用别人的时间，是一个有成本的动作。"
						+ "以下情况**不要**建工单：只是你没查到内容（那是检索问题，换关键词再查）、"
						+ "用户只是随口问了个问题、你已经能在回答里直接说清楚的事情。"
						+ "一次任务里最多建 1~2 条 —— 如果你觉得发现了 5 个问题，"
						+ "说明你该在回答里把它们讲清楚，而不是建 5 条工单。",
				"返回新建工单的 id、标题、级别，以及它是否命中了去重（同标题同级别在窗口内已存在时，"
						+ "返回的是**已有那条**而不是新建）。拿到 id 就说明记录确实写进去了。",
				"参数不合法（标题为空、级别不在允许值内）会被拒绝并说明原因，你可以改参数重试。"
						+ "如果这次写操作**没有被人工确认通过**，你会收到一条明确的拒绝说明 ——"
						+ "那种情况下**不要重新提交**，请改用文字把情况告诉用户。",
				ToolHints.writeRequiringApproval());
	}

	@Override
	public Class<Params> parameterType() {
		return Params.class;
	}

	/**
	 * 记下这次调用的上下文 —— 只为了拿到<b>放行它的审批 id</b>。
	 *
	 * <p>为什么要这么做而不是"不记也行"：工单是业务记录，而业务记录一旦写下去，
	 * 后面所有关于它的追责都从这里开始。把审批 id 一起写进工单，
	 * "一条不该存在的工单"就能直接倒查到"哪一次审批放行了它"——
	 * 不需要比对时间戳、也不需要假设两边的顺序一致。
	 *
	 * <p>注意它<b>不会</b>影响"要不要审批"这个判断：那个判断在执行器的闸门那一侧，
	 * 依据是传进去的通行证。所以即使这里的字段被并发串掉，
	 * 最坏的后果是"审计字段记错了"，而不是"该拦的没拦住"。
	 */
	@Override
	public void onContext(ToolCallContext context) {
		this.approvalId = context == null ? null : context.approvalId();
	}

	@Override
	public AgentToolResult invoke(Params parameters) {
		// 枚举在反序列化阶段就已经拒绝了 "CRITICAL" 这类值，所以这里不需要再校验合法性。
		// 唯一要处理的是大小写（"high" → HIGH）：那由 ReviewSeverity.parse 宽容掉，
		// 理由写在那个方法里（大小写不是语义问题，为它多花一轮模型调用不值得）
		ReviewSeverity severity = ReviewSeverity.parse(parameters.severity().name());
		if (severity == null) {
			// 理论上到不了这里（枚举已经限定了取值）。真到了说明"宽容解析"与枚举定义漂移了 ——
			// 报出来，而不是默默用一个默认值，那会让一条写操作以错误的级别落库
			return AgentToolResult.Failure.notFound(
					"级别「%s」不是允许的取值。允许的值：%s".formatted(parameters.severity(), ReviewSeverity.allowed()));
		}

		ReviewTicket ticket = ticketStore.create(parameters.title().strip(), fit(parameters.detail()), severity,
				"agent-tool", approvalId);

		log.info("propose_review 建单 id={} severity={} dedup={} approval={}", ticket.id(), ticket.severity(),
				ticket.deduplicated(), ticket.approvalId());

		if (ticket.deduplicated()) {
			return AgentToolResult.ok("""
					工单**没有重复创建**：队列里已经有一条同标题同级别的工单。
					- 已有工单 id：%s
					- 标题：%s
					- 级别：%s
					这说明之前的创建已经生效（或有人刚建过同样的）。不需要再提交，把它写进你的回答里即可。"""
					.formatted(ticket.id(), ticket.title(), ticket.severity()));
		}

		return AgentToolResult.ok("""
				工单已提交成功（记录已写入）。
				- 工单 id：%s
				- 标题：%s
				- 级别：%s
				- 创建时间：%s
				请在最终回答里告诉用户这个 id —— 那是他后续跟进这件事的凭据。"""
				.formatted(ticket.id(), ticket.title(), ticket.severity(), ticket.createdAt()));
	}

	/** 详情按配置截断，并<b>显式说明</b>被截断（不静默截掉）。 */
	private String fit(String detail) {
		String text = detail == null ? "" : detail.strip();
		int max = properties.maxDetailChars();
		if (text.length() <= max) {
			return text;
		}
		return text.substring(0, max) + "\n…（详情超过 " + max + " 字符，已截断）";
	}

	/**
	 * 参数：标题 / 详情 / 级别。
	 *
	 * <p>约束写在注解上而不是描述里，理由与另外三把工具一致：
	 * JSON Schema 由注解生成，于是"模型看到的边界"与"校验器执行的边界"
	 * <b>是同一份</b>，不会漂移（见 {@code JsonSchemaGenerator}）。
	 *
	 * <h2>{@code severity} 为什么从 {@code String + @Pattern} 改成了枚举</h2>
	 * 第一版是 {@code @Pattern(regexp = "LOW|MEDIUM|HIGH") String severity}，
	 * 而它有两个问题，第一个是<b>当场被测试抓出来的</b>：
	 * <ol>
	 *   <li><b>缺字段时校验压根不执行</b>：JSR-380 里 {@code @Pattern} 对 {@code null} 是通过的
	 *       （规范如此，要禁止得再加 {@code @NotNull}）。于是模型不传 {@code severity} 时
	 *       参数是 {@code null}、校验放行 —— 而代码里那句"为 null 就用 MEDIUM"的兜底
	 *       <b>看起来在处理缺失，实际处理的却是空字符串</b>；</li>
	 *   <li><b>它表达不了"只有这三个值"</b>：字符串在类型上允许任何值，只能靠校验器守；
	 *       枚举在反序列化阶段就拒了别的值，校验器那一层少一个"必须记得写"的东西。
	 *       顺带 schema 也从 {@code pattern} 变成了 {@code enum: ["LOW","MEDIUM","HIGH"]} ——
	 *       对模型来说后者更直白。</li>
	 * </ol>
	 * 改成枚举后 {@code severity} 变成<b>必填</b>，那才是它该有的语义：
	 * 一条不知道紧急程度的工单，接到的人无从排优先级。
	 */
	public record Params(
			@NotBlank(message = "标题不能为空，且必须具体到能被独立看懂")
			@Size(max = 120, message = "标题不能超过 120 字（标题不是正文）")
			String title,

			@Size(max = 4000, message = "详情不能超过 4000 字")
			String detail,

			@NotNull(message = "必须给出级别，供人排优先级")
			ReviewSeverity severity) {
	}

}
