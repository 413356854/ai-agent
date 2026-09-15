package ai.agent.tool;

import ai.agent.tool.support.ToolFeedbackFormatter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * <b>本学习点的核心：模型与 Java 之间的那道边界。</b>
 *
 * <h2>它存在的唯一理由</h2>
 * 路线文档的原话是「明确<b>模型只输出调用意图，Java 端负责校验 + 执行 + 回填</b>的边界」。
 * 这句话读起来像常识，但它有一个非常具体的敌人：<b>把模型的输出当成已经合法的调用</b>。
 * 一旦那样做，下面每一件都会发生，而且都不报错：
 * <ul>
 *   <li>模型编一个不存在的工具名 → 反射找不到方法 → 抛 <i>NoSuchMethodException</i>，
 *       而它在日志里看起来像"系统故障"；</li>
 *   <li>模型少给一个必填参数 → 参数是 null → 工具内部 NPE，
 *       报错指向工具的第 40 行，而真正的原因在模型的输出里；</li>
 *   <li>模型给一个参数多塞一个没用的字段 → Jackson 默认静默忽略 →
 *       <b>调用"成功"了，但行为不是模型以为的那个</b>（踩坑库 #20 同族）；</li>
 *   <li>模型把数字写成字符串 {@code "3"} → Jackson 顺手强转 → 看起来没问题，
 *       但如果哪天模型给的是 {@code "三"}，报错位置又会跑到很深的地方。</li>
 * </ul>
 * 所以这个类的每一段代码都在回答同一个问题：<b>"模型可以随便乱来，然后会怎样？"</b>
 *
 * <h2>四道门禁，顺序不能换</h2>
 * <ol>
 *   <li><b>白名单</b>（{@link ToolCallStage#UNKNOWN_TOOL}）—— 必须先做。
 *       放到参数校验之后的话，一个不存在的工具会先报"参数不合法"，
 *       而真正的问题是"压根没这个工具"，回填内容会把模型带向错误的修法；</li>
 *   <li><b>JSON 解析</b>（{@link ToolCallStage#PARSE_ERROR}）—— 参数不是合法 JSON，
 *       或结构对不上（给对象类型传了数组）；</li>
 *   <li><b>JSR-380 校验</b>（{@link ToolCallStage#VALIDATION_ERROR}）—— JSON 合法但内容不合格。
 *       <b>这一步不能省</b>：模型看到 schema ≠ 模型会遵守 schema；</li>
 *   <li><b>执行</b>（{@link ToolCallStage#EXECUTION_ERROR}）—— 参数可信之后才干活，
 *       异常在这里被兜住，转成一条 observation 回到模型（而不是炸掉整个会话）。</li>
 * </ol>
 *
 * <h2>两个刻意的严格选择</h2>
 * <ul>
 *   <li><b>多余的字段一律拒绝</b>（不是忽略）。忽略会让"模型多给了一个字段"
 *       变成一次静默的行为偏差；拒绝则变成一条能被回填、能被模型自己删掉的错误。
 *       代价是多一轮，但这个代价换的是"不会悄悄算错"。</li>
 *   <li><b>数字/布尔写成了字符串会拒绝</b>（关掉 {@code MapperFeature.ALLOW_COERCION_OF_SCALARS}）。
 *       模型的典型形态是把 {@code 3} 写成 {@code "3"}；放行它看起来更"健壮"，
 *       但那是在<b>替模型猜意图</b> —— 同一套宽松规则遇到 {@code "三"} 就只能抛一个
 *       位置很深的解析异常。宁可现在给一条明确的回填。
 *       <p><b>这里有一个实测出来的坑值得记下来</b>：直觉上会去关
 *       {@code DeserializationFeature.ACCEPT_STRING_AS_NUMBER} —— 但那个常量
 *       <b>在 Jackson 2.21（本项目实际解析到的版本）里已经被删掉了</b>，
 *       它变成了一个"编译不过"的选项；而如果写的是某个"看起来对"的常量名，
 *       最坏的情况是它存在但控制的是另一件事。真正有效的开关只有一个：
 *       {@code MapperFeature.ALLOW_COERCION_OF_SCALARS}（做了 javap 核实）。
 *       教训：**"我以为我关掉了强转"与"强转真的被关掉了"是两件事，只能靠测试区分。**</li>
 * </ul>
 * 这两条都有一个共同的代价：白名单里那把工具本来能"侥幸跑通"。换成拒绝之后，
 * 模型必须再花一轮改参数 —— 而这一轮的值就是这个学习点要展示的东西：<b>错误回填能不能让模型自愈</b>。
 */
@Component
public class ToolCallExecutor {

	private static final Logger log = LoggerFactory.getLogger(ToolCallExecutor.class);

	/**
	 * 参数解析用的 mapper。
	 *
	 * <p>刻意<b>不复用</b> Spring 容器里那个（{@code ObjectMapper} bean）：
	 * 那个是为 Web 层配置的（可能开了各种宽松特性、可能带自定义模块），
	 * 而这里是<b>不可信输入的第一道解析</b>，strict 必须由本类自己保证 ——
	 * 依赖容器配置的话，"哪天有人为了兼容某个接口把容器 mapper 配宽了"就会
	 * 悄悄把这道门禁拆掉，而且不会有任何测试失败。
	 */
	private static final ObjectMapper STRICT = new ObjectMapper()
			// 多余的字段：拒绝（见类注释里的取舍）
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			// null 传给基本类型：拒绝。默认会被当成 0 —— 而 0 是一个看起来完全合法的值（踩坑库 #20）
			.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
			// 类型强转：整体关闭（模型把 5 写成 "5"、把 true 写成 "true" 都会被拒）
			.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);

	private final ToolRegistry registry;

	private final Validator validator;

	private final ToolFeedbackFormatter formatter;

	public ToolCallExecutor(ToolRegistry registry, Validator validator, ToolFeedbackFormatter formatter) {
		this.registry = registry;
		this.validator = validator;
		this.formatter = formatter;
	}

	/**
	 * 执行一次工具调用意图。<b>本方法保证不抛异常</b>（除了 JVM 级错误）。
	 *
	 * <p>"不抛"不是"吞掉"：每一种失败都变成一条带 stage 的回填文本，
	 * 回到模型那里成为它能用来修正自己的 observation。
	 * 这是 {@code AgentLoop} 能持续跑下去的前提 —— 工具失败不该中断整个任务。
	 *
	 * @param invocation 模型提出的调用意图（不可信）
	 * @return 走到哪一步、结果、以及要回填给模型的文本
	 */
	public ToolCallOutcome execute(ToolInvocation invocation) {
		long startedAt = System.nanoTime();

		// ---- 门禁 1：白名单 ----
		// 回填里必须带**可用工具列表**：只说"工具不存在"，模型的下一个反应往往是再编一个。
		// 这是"错误信息里带可用值"这条要求最直接的落点
		var found = registry.find(invocation.name());
		if (found.isEmpty()) {
			String feedback = formatter.unknownTool(invocation, registry.names());
			log.warn("模型提出了不存在的工具 name={}（可用：{}）", invocation.name(), registry.names());
			return new ToolCallOutcome(invocation, ToolCallStage.UNKNOWN_TOOL, null, feedback,
					elapsedMs(startedAt), true);
		}
		AgentTool<?> tool = found.get();

		// ---- 门禁 2：解析 ----
		Object parameters;
		try {
			parameters = parse(tool, invocation);
		}
		catch (ToolArgumentsException e) {
			log.warn("工具「{}」的参数解析失败：{}", tool.name(), e.getMessage());
			return new ToolCallOutcome(invocation, ToolCallStage.PARSE_ERROR, null,
					formatter.parseError(invocation, tool, e.getMessage()), elapsedMs(startedAt), true);
		}

		// ---- 门禁 3：校验 ----
		// 这一步是"模型看到 schema ≠ 模型遵守 schema"的直接体现。
		// 回填里带字段名 + 具体原因 + 可用值，模型才有可能一次改对
		List<String> issues = validate(parameters);
		if (!issues.isEmpty()) {
			log.warn("工具「{}」的参数未通过校验：{}", tool.name(), issues);
			return new ToolCallOutcome(invocation, ToolCallStage.VALIDATION_ERROR, null,
					formatter.validationError(invocation, tool, issues), elapsedMs(startedAt), true);
		}

		// ---- 门禁 4：执行 ----
		return invoke(tool, parameters, invocation, startedAt);
	}

	/**
	 * 解析成强类型参数。
	 *
	 * <p>用"先 {@code readTree} 再 {@code treeToValue}"而不是直接 {@code readValue(String, Class)}：
	 * 前者的失败信息能区分"整串不是 JSON"与"是 JSON 但形状不对"，
	 * 而这两种给模型的建议不同（一个说语法，一个说结构）。
	 */
	@SuppressWarnings("unchecked")
	private <P> P parse(AgentTool<?> tool, ToolInvocation invocation) {
		String raw = invocation.arguments();
		if (raw == null || raw.isBlank()) {
			raw = "{}";
		}

		JsonNode tree;
		try {
			tree = STRICT.readTree(raw);
		}
		catch (Exception e) {
			throw new ToolArgumentsException("参数不是合法 JSON：" + rootMessage(e));
		}
		if (tree == null || tree.isNull()) {
			// 无参工具允许模型传 "null" 或 ""；其它工具视为缺参数
			tree = STRICT.createObjectNode();
		}
		if (!tree.isObject()) {
			throw new ToolArgumentsException(
					"参数必须是一个 JSON 对象，实际收到的是 " + tree.getNodeType().name().toLowerCase());
		}

		Class<?> parameterType = tool.parameterType();
		try {
			return (P) STRICT.treeToValue(tree, parameterType);
		}
		catch (UnrecognizedPropertyException e) {
			// 单独接住：这是最常见的"模型多给字段"，而默认异常信息（带类名与包路径）
			// 对模型毫无帮助。这里把 可用字段 与 它多给的那个 都拿出来
			SortedSet<String> known = fieldNamesOf(parameterType);
			throw new ToolArgumentsException(
					"参数里出现了工具不认识的字段「%s」。这个工具只接受：%s".formatted(e.getPropertyName(), known));
		}
		catch (Exception e) {
			throw new ToolArgumentsException("参数与工具的声明对不上：" + rootMessage(e));
		}
	}

	/** 跑 JSR-380 校验，返回可读的问题清单。字段名必须出现在每条里（否则模型无从下手）。 */
	private List<String> validate(Object parameters) {
		Set<ConstraintViolation<Object>> violations = validator.validate(parameters);
		if (violations.isEmpty()) {
			return List.of();
		}
		List<String> issues = new ArrayList<>();
		for (ConstraintViolation<Object> v : violations) {
			// 与 StructuredChatServiceImpl 同一条规则：只说"校验失败"模型改不动，
			// 必须带 propertyPath（哪个字段）+ message（错在哪）
			issues.add("字段 %s：%s".formatted(v.getPropertyPath(), v.getMessage()));
		}
		return issues;
	}

	/** 真正调用工具。异常在这里被兜住 —— 目标是"工具炸了不等于会话炸了"。 */
	private ToolCallOutcome invoke(AgentTool<?> tool, Object parameters, ToolInvocation invocation, long startedAt) {
		try {
			AgentToolResult result = invokeUnchecked(tool, parameters);
			long elapsed = elapsedMs(startedAt);

			// 注意：工具自己返回 Failure 时 stage 仍是 OK ——
			// 工具**正常完成了它的工作**，只是工作上给出了一个否定结论。
			// 把它记成 EXECUTION_ERROR 会让"没查到"看起来像"服务故障"，那是错误分类（踩坑库 #26/#37 同族）
			boolean retriable = result instanceof AgentToolResult.Failure f && f.retriable();
			log.info("工具「{}」执行完成 elapsed={}ms result={}", tool.name(), elapsed,
					result instanceof AgentToolResult.Success ? "success" : "failure");
			return new ToolCallOutcome(invocation, ToolCallStage.OK, result,
					formatter.resultFeedback(invocation, tool, result), elapsed, retriable);
		}
		catch (RuntimeException e) {
			long elapsed = elapsedMs(startedAt);
			// 打成 ERROR：走到这里说明工具实现或它的依赖真出问题了，这和"没查到"不是一回事
			log.error("工具「{}」执行时抛出异常 elapsed={}ms", tool.name(), elapsed, e);
			return new ToolCallOutcome(invocation, ToolCallStage.EXECUTION_ERROR, null,
					formatter.executionError(invocation, tool, rootMessage(e)), elapsed, false);
		}
	}

	@SuppressWarnings("unchecked")
	private <P> AgentToolResult invokeUnchecked(AgentTool<?> tool, Object parameters) {
		return ((AgentTool<P>) tool).invoke((P) parameters);
	}

	/** 参数类型里声明的字段名（排序后，便于可复现地回填给模型）。 */
	private static SortedSet<String> fieldNamesOf(Class<?> parameterType) {
		SortedSet<String> names = new TreeSet<>();
		if (parameterType.isRecord()) {
			for (var component : parameterType.getRecordComponents()) {
				names.add(component.getName());
			}
		}
		return names;
	}

	private static long elapsedMs(long startedAt) {
		return (System.nanoTime() - startedAt) / 1_000_000;
	}

	/** 剥到最内层消息，避免把整条包装链写进给模型看的回填里。 */
	private static String rootMessage(Throwable e) {
		Throwable cause = e;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		String message = cause.getMessage();
		if (message == null || message.isBlank()) {
			return cause.getClass().getSimpleName();
		}
		return message.length() > 300 ? message.substring(0, 300) + "…" : message;
	}

	/** 参数解析阶段的失败。私有类型：它只在 {@link #parse} 与它的调用者之间传递。 */
	private static class ToolArgumentsException extends RuntimeException {

		ToolArgumentsException(String message) {
			super(message);
		}
	}

	/** 供测试读取：执行器实际使用的严格解析配置（避免测试自己 new 一个 mapper 而与实现漂移）。 */
	public static ObjectMapper strictMapper() {
		return STRICT;
	}

	/** 已注册工具名（报告与自检用）。 */
	public List<String> knownTools() {
		return registry.names();
	}

	/** 遍历参数 JSON 的顶层字段名（调试用；空对象返回空列表）。 */
	static List<String> topLevelFields(JsonNode node) {
		if (node == null || !node.isObject()) {
			return List.of();
		}
		List<String> fields = new ArrayList<>();
		for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
			fields.add(it.next());
		}
		return fields;
	}

}
