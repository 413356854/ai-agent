package ai.agent.tool;

import ai.agent.tool.model.AgentToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具白名单 —— 模型能调到的工具<b>只有</b>这里注册的那些。
 *
 * <h2>为什么"白名单"这件事必须有个显式的容器</h2>
 * Spring AI 允许把工具对象直接传给 {@code ChatClient.tools(...)}，
 * 那样确实能跑，但<b>没有任何地方能回答"这个应用一共有哪些工具"</b>。
 * 没有这个答案，下面这些事就都做不到：
 * <ul>
 *   <li><b>拒绝时要给出可用列表</b> —— 模型编了个工具名，回填"工具不存在"它只能再编一个；
 *       回填"不存在的工具，可用的是 a、b、c"它才能改对。
 *       而这正是路线文档要的「错误信息里带可用值」；</li>
 *   <li><b>未知工具要先于参数校验被拦下</b> —— 顺序反了会先报"参数不合法"，
 *       而真正的问题是"压根没有这个工具"，回填内容会把人带偏；</li>
 *   <li><b>启动期自检</b> —— 见 {@link #selfCheck()}。</li>
 * </ul>
 *
 * <h2>启动期自检：把"以后才会炸"的事提前到启动</h2>
 * 注册时做四件事，每一件都对应一种"不报错的错"：
 * <ol>
 *   <li><b>工具名重复</b> → 直接抛。重名时后注册的会静默覆盖前一个，
 *       于是"我明明实现了 A，模型却调到了 B"；</li>
 *   <li><b>{@code name()} 与描述里的名字不一致</b> → 直接抛。白名单键取自 {@code name()}，
 *       而模型看到的名字来自 description —— 两边不一致时，模型照着描述调用会得到"工具不存在"，
 *       而人看报告时发现工具明明在注册表里；</li>
 *   <li><b>强制生成一次 JSON Schema</b> → 生成失败（参数类型不支持）当场抛。
 *       否则要等到模型第一次调用这把工具时才炸，而那时错误会被归因成"模型传参有问题"；</li>
 *   <li><b>描述里空了几段</b> → <b>只 WARN 不抛</b>。描述写得薄不是错误，
 *       但它会直接降低模型选对工具的概率，所以要让它在启动日志里可见 ——
 *       这比事后从"模型怎么老选错"倒查快得多。</li>
 * </ol>
 */
@Component
public class ToolRegistry {

	private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

	private final Map<String, AgentTool<?>> byName;

	public ToolRegistry(List<AgentTool<?>> tools) {
		Map<String, AgentTool<?>> registered = new LinkedHashMap<>();
		for (AgentTool<?> tool : tools) {
			String name = tool.name();
			if (name == null || name.isBlank()) {
				throw new IllegalStateException("工具 " + tool.getClass().getName() + " 的 name() 为空");
			}
			AgentTool<?> previous = registered.putIfAbsent(name, tool);
			if (previous != null) {
				throw new IllegalStateException(("工具名「%s」重复：%s 与 %s。"
						+ "重名时后注册的会静默覆盖前一个 —— 表现是「我实现了 A，模型却调到了 B」。")
						.formatted(name, previous.getClass().getSimpleName(), tool.getClass().getSimpleName()));
			}
		}
		this.byName = Map.copyOf(registered);
		selfCheck();
	}

	/** 按名字找工具。返回 Optional 而不是抛异常：找不到是<b>可预期的正常情况</b>（模型会编名字）。 */
	public Optional<AgentTool<?>> find(String name) {
		return name == null ? Optional.empty() : Optional.ofNullable(byName.get(name));
	}

	/** 是否存在这个名字的工具。 */
	public boolean contains(String name) {
		return name != null && byName.containsKey(name);
	}

	/** 全部已注册工具（顺序稳定 = 注册顺序，报告可复现）。 */
	public List<AgentTool<?>> all() {
		return List.copyOf(byName.values());
	}

	/** 全部工具名 —— 拒绝未知工具时要把这个列表回填给模型。 */
	public List<String> names() {
		return List.copyOf(byName.keySet());
	}

	/**
	 * 把工具自己的描述渲染进系统提示词。
	 *
	 * <p><b>为什么要有一个"渲染全部工具描述"的方法</b>：模型对工具的全部认知来自两处 ——
	 * 协议里的 {@code tools[]} 字段（名字 + description + schema），以及系统提示词。
	 * 有些模型/网关对 {@code tools[]} 里的长描述支持不好（实测里描述被截断的情况不算少见），
	 * 所以把同一份描述再放进系统提示词是一种便宜的加固。
	 *
	 * <p>注意它和 {@code tools[]} <b>用的是同一个对象</b>（都来自
	 * {@link AgentToolDefinition#render()}），所以不存在"两处描述不一致"的问题。
	 */
	public String renderCatalog() {
		StringBuilder sb = new StringBuilder();
		for (AgentTool<?> tool : all()) {
			sb.append("### ").append(tool.name()).append('\n');
			sb.append(tool.description().render()).append("\n\n");
		}
		return sb.toString().strip();
	}

	/**
	 * 注册期自检。四个检查，逐个都在类注释里说明了它防的是什么。
	 *
	 * <p>做成公开方法（而不是藏在构造器里）是为了让测试能直接调用它 ——
	 * 而且它<b>幂等</b>：多调几次不会改变状态。
	 */
	public final void selfCheck() {
		for (AgentTool<?> tool : all()) {
			AgentToolDefinition definition = tool.description();

			// ② 两个名字必须一致：白名单键 vs 模型看到的名字
			if (!tool.name().equals(definition.name())) {
				throw new IllegalStateException(("工具「%s」的 name() 与描述里的名字「%s」不一致。"
						+ "白名单用的是 name()，而模型看到的是描述里的名字 —— "
						+ "不一致时模型照着描述调用会得到「工具不存在」，而人查注册表却能看到它。")
						.formatted(tool.name(), definition.name()));
			}

			// ③ 当场生成一次 schema：参数类型不支持就要在启动时炸，而不是等模型第一次调用
			try {
				tool.getToolDefinition();
			}
			catch (RuntimeException e) {
				throw new IllegalStateException(
						"工具「%s」的 JSON Schema 生成失败，拒绝启动：%s".formatted(tool.name(), e.getMessage()), e);
			}

			// ④ 描述写得薄 → 只警告
			List<String> empty = new ArrayList<>(definition.emptySections());
			if (!empty.isEmpty()) {
				log.warn("工具「{}」的自我描述缺了 {} —— 描述即 prompt，缺一段就少一个约束，模型更容易选错或用错",
						tool.name(), String.join("、", empty));
			}
		}
		log.info("工具注册表就绪：{} 把工具 [{}]", all().size(), String.join("、", names()));
	}

}
