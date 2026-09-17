package ai.agent.service.tool;

import ai.agent.entity.model.tool.AgentToolDefinition;
import ai.agent.entity.tool.ToolCallContext;
import ai.agent.service.support.tool.JsonSchemaGenerator;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 一把工具：<b>自我描述 + 一个纯函数式的实现</b>。
 *
 * <h2>为什么直接实现 Spring AI 的 {@link ToolCallback}，而不是自定义接口</h2>
 * 与本项目 {@code EmbeddingModel} 那件事同一个理由，而且更值：
 * {@code ToolCallback} 就是 <b>Spring AI 工具调用的真实 SPI</b>
 * （2.0.1 实测契约：{@code getToolDefinition()} / {@code call(String)} / {@code call(String, ToolContext)}），
 * 直接实现它意味着 {@code ChatClient.toolCallbacks(...)} 可以原样吃下这些工具 ——
 * 「换回真模型零适配」不是一句承诺，是可以直接跑的事实。
 *
 * <h2>但 {@code call(String)} 这个方法<b>不</b>是本类的入口</h2>
 * 这是整个学习点的关键设计。{@code call(String)} 接收裸 JSON 字符串、返回裸字符串，
 * 它<b>没有地方表达"我拒绝了，原因是什么、能不能重试"</b> ——
 * 用它当入口，校验逻辑就只能靠抛异常，而异常回到模型那里时只剩一句 message，
 * 分类信息（未知工具 / 参数不合法 / 执行失败）全部丢失，回填给模型的反馈也就没法"可操作"。
 *
 * <p>所以本接口把入口定成强类型的
 * {@link #invoke(Object)}（参数已解析、已校验），而 {@code call(String)} 由
 * {@link ToolCallExecutor} 负责适配。分层是：
 * <pre>
 *   模型意图(JSON)  →  ToolCallExecutor（白名单 → 解析 → JSR-380 校验 → 兜异常 → 计时 → 格式化）
 *                        ↓ 只有走到这里，参数才是"已经可信的强类型对象"
 *                     AgentTool.invoke(参数)  →  AgentToolResult（成功 / 失败，都带结构）
 * </pre>
 * 一句话：<b>{@code invoke} 只关心业务，不关心"输入是不是模型给的乱码"。</b>
 * 后者是执行器的职责，而它必须被单独测试（见 {@code ToolCallExecutorTest}）。
 *
 * @param <P> 参数类型。用 record 承载，schema 由 {@link JsonSchemaGenerator} 从它生成
 */
public interface AgentTool<P> extends ToolCallback {

	/** 工具名（同时是白名单键，必须与 {@link #description()} 里的名字一致）。 */
	String name();

	/** 自我描述：什么时候用 / 什么时候别用 / 返回什么 / 失败什么样（描述即 prompt）。 */
	AgentToolDefinition description();

	/** 参数类型。执行器用它做反序列化与校验。 */
	Class<P> parameterType();

	/**
	 * 真正干活的地方。
	 *
	 * <p><b>契约</b>：入参保证已经过白名单、JSON 解析、JSR-380 校验，可以当可信数据用。
	 * 实现里不需要再判 null / 再查范围 —— 那是重复劳动，而且会掩盖"校验没生效"这件事
	 * （如果校验真没生效，这里再判一次会让它继续看起来正常，问题被推迟到更难查的地方）。
	 *
	 * <p>返回 {@link AgentToolResult} 而不是抛异常：见那个接口的注释 ——
	 * "没查到"和"炸了"要让模型做不一样的事。
	 */
	AgentToolResult invoke(P parameters);

	/**
	 * <b>把这次调用的上下文交给工具（可选覆写）</b>。
	 *
	 * <h2>为什么需要它：有些副作用必须把"谁批的"一起落下来</h2>
	 * 写工具创建的业务记录里应当带上<b>放行它的审批 id</b>。
	 * 理由是审计闭环：写操作出事之后的第一个问题是"这是谁批的"，
	 * 而如果审批 id 只存在于"审批表"那一侧，就只能靠比对时间戳去猜关联 ——
	 * 那在两边的时钟或顺序稍有出入时就会给出错误答案。
	 * 把它写进业务记录本身，"一条不该存在的记录"就能直接倒查到"哪一次审批放行了它"。
	 *
	 * <h2>为什么用"执行器回调"而不是让工具自己去读参数或全局状态</h2>
	 * <ul>
	 *   <li><b>不能放进参数</b>：参数是模型给的<b>不可信输入</b>。审批 id 出现在 schema 里，
	 *       模型就能自己编一个（"我批过了，id 是 xxx"）—— 那是审批制度最不能容忍的漏洞；</li>
	 *   <li><b>不能用全局/线程本地状态</b>：本项目跑在虚拟线程上，而阶段一那条教训正是
	 *       "不要用隐式的当前上下文"（共享可变状态在并发的虚拟线程下会串味）。</li>
	 * </ul>
	 * 调用是<b>显式的、由执行器在调用之前的最后一刻</b>发起的，所以不存在"读到别人的上下文"。
	 *
	 * <h2>默认实现什么都不做</h2>
	 * 只读工具（三把检索工具）对审批完全无感 —— 它们不需要覆写这个方法。
	 * 只有真正需要把审批信息落进业务记录的工具才覆写它。
	 * 这比"让所有工具都实现一个带两参数的 invoke"要轻得多，
	 * 也比"给所有工具都塞两个用不上的参数"更诚实。
	 *
	 * @param context 本次调用的上下文（哪次运行、第几步、来源、人工批准信息）
	 */
	default void onContext(ToolCallContext context) {
		// 默认忽略 —— 大多数工具不关心"谁在调用、谁批的"
	}

	/**
	 * 拼出给模型（和框架）看的 tool definition。
	 *
	 * <p>{@code description} 用 {@link AgentToolDefinition#render()} 的四段拼装结果；
	 * {@code inputSchema} 由参数类型的注解生成 —— 两者都不手写，避免"说明与校验两处维护"。
	 */
	@Override
	default ToolDefinition getToolDefinition() {
		return ToolDefinition.builder()
				.name(name())
				.description(description().render())
				.inputSchema(JsonSchemaGenerator.generate(parameterType(), name()))
				.build();
	}

	/**
	 * Spring AI 的裸字符串入口。
	 *
	 * <p><b>刻意实现成"不可用"而不是"顺手也能跑"</b>：如果这里也把参数解析 + 执行走通，
	 * 就会存在<b>两条都能执行工具的路径</b> —— 一条经过 {@link ToolCallExecutor}（有白名单、校验、
	 * 分类、计时），一条不经过。两条并存的结果一定是：某天有人在某处调了这条，
	 * 而那条路径上<b>没有任何校验</b>，且失败时会抛异常而不是回填。
	 *
	 * <p>本项目的执行入口只有一个：{@link ToolCallExecutor#execute}。
	 * 框架如果直接调到这个方法，说明有人绕过了执行器 —— 那就该当场炸，
	 * 而不是"悄悄用一条没有校验的路径把活干了"。
	 */
	@Override
	default String call(String arguments) {
		throw new UnsupportedOperationException(
				"工具「%s」必须经过 ToolCallExecutor 执行（白名单 + 校验 + 分类 + 计时）。".formatted(name())
						+ "直接调 ToolCallback.call(...) 会绕过全部校验 —— 这条路径故意不可用。");
	}

}
