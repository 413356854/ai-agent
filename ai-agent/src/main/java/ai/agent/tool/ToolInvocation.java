package ai.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 一次工具调用的<b>意图</b> —— 模型能产出的全部东西就到这一层为止。
 *
 * <h2>这个类存在的理由：把「边界」变成一个可以断言的东西</h2>
 * 学习点 5 的原话是「明确<b>模型只输出调用意图，Java 端负责校验 + 执行 + 回填</b>的边界」。
 * 把"意图"落成一个只有三个字段的记录，边界就有了物理形态：
 * <ul>
 *   <li>{@code id} —— 由模型/协议给的调用 id。回填时必须带上它，
 *       否则多轮并发调用时模型无法把"结果"与"我提的哪一次"对上；</li>
 *   <li>{@code name} —— 工具名。<b>模型可以随便编</b>，所以它只是一条待校验的字符串，
 *       不是"要调用的方法"；</li>
 *   <li>{@code arguments} —— <b>原始 JSON 字符串</b>，不是反序列化后的对象。
 *       刻意不在这里反序列化：一旦这个类型持有强类型参数，
 *       "校验"就会被悄悄提前到解析器里、且失败方式变成抛异常，
 *       而我们需要的是"能拒绝、能回填、能让模型自己改"。</li>
 * </ul>
 *
 * <p>换句话说：<b>这个类是不可信输入的类型。</b>它来自模型，等价于来自网络。
 * 谁把它当成"已经合法的调用"，谁就把边界做没了。
 *
 * @param id 调用 id（回填时的对账凭据）
 * @param name 工具名（不可信，必须过白名单）
 * @param arguments 原始 JSON 参数（不可信，必须过 schema + 类型 + 约束三层）
 */
public record ToolInvocation(String id, String name, String arguments) {

	/**
	 * 从 Spring AI 的 {@code AssistantMessage.ToolCall} 转换。
	 *
	 * <p>用一个静态工厂而不是让上游直接 new：Spring AI 那个类型是<b>协议层</b>的类型，
	 * 本项目的业务层不该依赖它的类名。这样将来换 SDK（或升 Spring AI 大版本、
	 * 它把 ToolCall 挪了包）时，改动只落在这一个方法里。
	 */
	public static ToolInvocation from(String id, String name, String arguments) {
		return new ToolInvocation(id, name, arguments == null ? "{}" : arguments);
	}

	/**
	 * 参数的原始文本，用于回填给模型看。
	 *
	 * <p><b>为什么要截断</b>：模型可能吐出一个几 KB 的参数（把整段文档塞进查询词）。
	 * 回填时原样带上会把上下文直接顶满，而错误信息只需要"你调了什么"这个轮廓。
	 * 截断长度与 {@code ToolResultFormatter} 的结果上限同一个量级。
	 */
	public String argumentsForFeedback(int maxChars) {
		if (arguments == null) {
			return "{}";
		}
		return arguments.length() <= maxChars ? arguments : arguments.substring(0, maxChars) + "…(已截断)";
	}

	/** 参数是不是一个空的 JSON 对象（无参工具的合法形态）。 */
	public boolean hasEmptyArguments() {
		return arguments == null || arguments.isBlank() || "{}".equals(arguments.strip()) || "null".equals(arguments.strip());
	}

	/** 校验时用的共享 ObjectMapper —— 只读、线程安全，不必每次 new。 */
	static final ObjectMapper MAPPER = new ObjectMapper();

	/** 供子类/工具实现复用的 mapper。 */
	public static ObjectMapper mapper() {
		return MAPPER;
	}

	/** 给日志/报告用的可读形式（不含完整参数，避免日志被大参数冲爆）。 */
	public String describe() {
		return "%s(id=%s, args=%s)".formatted(name, id, argumentsForFeedback(120));
	}

	/** 工具名是否在白名单里 —— 只是便捷方法，真正的判定在 {@code ToolRegistry}。 */
	public boolean nameIn(List<String> allowed) {
		return name != null && allowed.contains(name);
	}

}
