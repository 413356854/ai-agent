package ai.agent.entity.model.tool;

import ai.agent.entity.agent.AgentRun;
import ai.agent.service.support.tool.JsonSchemaGenerator;

import java.util.List;

/**
 * 一个工具的<b>自我描述</b> —— 也就是「描述即 prompt」这句话的物理载体。
 *
 * <h2>为什么单独一个类型，而不是复用 Spring AI 的 ToolDefinition</h2>
 * Spring AI 的 {@code ToolDefinition(name, description, inputSchema)} 只有三段，
 * 而它把"怎么用"这件事压进了一个 {@code description} 字符串里。
 * 结果是大多数人只写一句"查询文档"，然后困惑模型为什么老选错工具或传错参数。
 *
 * <p>本项目的立场是：<b>工具描述是提示词工程的一部分，必须按提示词的标准写。</b>
 * 所以这里把描述拆成四段，每段回答一个模型真的会问的问题：
 * <table border="1">
 *   <caption>四段各自回答什么</caption>
 *   <tr><th>字段</th><th>模型拿它回答的问题</th></tr>
 *   <tr><td>{@code whenToUse}</td><td>"用户这句话该不该动这个工具？"</td></tr>
 *   <tr><td>{@code whenNotToUse}</td><td>"有没有看起来像、其实不该用它的场合？"</td></tr>
 *   <tr><td>{@code returns}</td><td>"它给我的是什么形状的东西，我要怎么读？"</td></tr>
 *   <tr><td>{@code onFailure}</td><td>"失败了返回什么，我该怎么改？"</td></tr>
 * </table>
 *
 * <p><b>只有 {@code whenToUse} 是必填</b>，另外三段可以为空 —— 但这不是"可选装饰"：
 * 空着就少一个约束，而实测中"选错工具"绝大多数来自"没说什么时候<b>不</b>该用它"。
 * 所以四段里的内容会一起拼进模型看到的 description（见 {@link #render()}）。
 *
 * <p><b>参数说明不在这里</b>：参数的类型、必填、取值范围由 Java 类型 + JSR-380 注解
 * 自动生成 JSON Schema（{@link JsonSchemaGenerator}）。理由很直接 ——
 * 手写参数说明就会与真正的校验规则<b>两处维护</b>，而两处维护必然漂移：
 * 漂移之后模型看到的是一个宽松的 schema、实际遇到的是一套更严的校验。
 * 那种失败模式就是"工具看起来没问题，模型却怎么调都过不去"。
 *
 * @param name 工具名（同时也是白名单键）。必须与 {@code AgentTool.name()} 一致
 * @param summary 一句话说它做什么（拼在最前，模型快速扫读用）
 * @param whenToUse 何时该用它 —— <b>必填</b>，最好带一个具体例子
 * @param whenNotToUse 何时<b>不</b>该用它。留空的代价是模型会拿它去干别的工具的活
 * @param returns 返回什么形状的数据（让模型知道怎么读 observation）
 * @param onFailure 常见失败与含义（让模型知道失败后该改什么）
 * @param hints <b>风险与副作用声明</b>（{@link ToolHints}）。
 *              它不是"补充信息" —— 它同时决定三件事：要不要人工审批（HITL）、
 *              通过 MCP 暴露时填什么 {@code annotations}、启动期自检拦下哪些自相矛盾。
 *              <p><b>为什么放在最后一位</b>：与 {@code AgentRun.failureCause} 同一个理由 ——
 *              这样已有的六参构造调用不需要改动就能编译，新信息通过重载构造器补上。
 *              但<b>新增写工具时必须显式传它</b>：默认值是只读，
 *              一个忘了声明的写操作不会报错，只会静默地免掉人工审批。
 */
public record AgentToolDefinition(String name, String summary, String whenToUse, String whenNotToUse, String returns,
		String onFailure, ToolHints hints) {

	public AgentToolDefinition {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("工具名不能为空");
		}
		if (whenToUse == null || whenToUse.isBlank()) {
			// 硬失败而不是"给个默认值"：一个没写"何时用"的工具，
			// 模型只能靠名字猜 —— 而它猜错时不会有任何报错，只会调错工具。
			// 这类"静默的坏描述"必须在注册期就拦下（见 ToolRegistry 的启动自检）
			throw new IllegalArgumentException("工具「" + name + "」必须写明 whenToUse：描述即 prompt，空描述=让模型猜");
		}
		if (hints == null) {
			// 显式写 null 的人多半是想说"没有风险"，那就该写 ToolHints.readOnly()。
			// 给个默认值会把"我忘了写"和"我确认它只读"合并成同一件事
			throw new IllegalArgumentException("工具「" + name + "」必须声明 ToolHints（只读就写 ToolHints.readOnly()）");
		}
		if (hints.selfContradictory()) {
			// 在构造期就抛，而不是留给 ToolRegistry 自检：
			// 越早失败，"是谁声明错了"这个问题的答案越明确
			throw new IllegalArgumentException(
					"工具「%s」的风险声明自相矛盾：%s。只读的工具不可能同时是破坏性的或不幂等的".formatted(name,
							hints.describe()));
		}
	}

	/**
	 * 六段构造，风险声明默认为<b>只读</b>。
	 *
	 * <p>保留它是为了"描述变了但风险没变"这类改动只改一处；
	 * 但要注意它的默认值意味着<b>写工具不能用它</b> —— 见类注释里关于默认值的那一段。
	 */
	public AgentToolDefinition(String name, String summary, String whenToUse, String whenNotToUse, String returns,
			String onFailure) {
		this(name, summary, whenToUse, whenNotToUse, returns, onFailure, ToolHints.readOnly());
	}

	/** 三段可空描述的简洁构造（只有 summary + whenToUse 时用；风险声明为只读）。 */
	public static AgentToolDefinition of(String name, String summary, String whenToUse) {
		return new AgentToolDefinition(name, summary, whenToUse, "", "", "", ToolHints.readOnly());
	}

	/** 这次调用要不要先经过人工确认（转发给 {@link ToolHints#requiresApproval()}）。 */
	public boolean requiresApproval() {
		return hints.requiresApproval();
	}

	/**
	 * 渲染成模型实际看到的 description。
	 *
	 * <p>顺序是刻意的：先"是什么"、再"什么时候用/不用"、最后"会返回什么/失败什么样"。
	 * 空段直接跳过（不写"（无）"，那只会白占 token）。
	 */
	public String render() {
		StringBuilder sb = new StringBuilder();
		if (summary != null && !summary.isBlank()) {
			sb.append(summary.strip()).append('\n');
		}
		sb.append("何时使用：").append(whenToUse.strip());
		appendIfPresent(sb, "何时不要使用", whenNotToUse);
		appendIfPresent(sb, "返回", returns);
		appendIfPresent(sb, "失败时", onFailure);
		return sb.toString();
	}

	private static void appendIfPresent(StringBuilder sb, String label, String value) {
		if (value != null && !value.isBlank()) {
			sb.append('\n').append(label).append('：').append(value.strip());
		}
	}

	/** 四段里哪几段是空的 —— 给启动自检用，把"描述写得薄"这件事变得可见。 */
	public List<String> emptySections() {
		List<String> empty = new java.util.ArrayList<>();
		if (isBlank(summary)) {
			empty.add("summary");
		}
		if (isBlank(whenNotToUse)) {
			empty.add("whenNotToUse");
		}
		if (isBlank(returns)) {
			empty.add("returns");
		}
		if (isBlank(onFailure)) {
			empty.add("onFailure");
		}
		return List.copyOf(empty);
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}

}
