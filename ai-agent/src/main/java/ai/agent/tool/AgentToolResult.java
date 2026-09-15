package ai.agent.tool;

import java.util.List;

/**
 * 工具自己的执行结果。
 *
 * <h2>为什么工具<b>不</b>用抛异常来表达"业务上没成功"</h2>
 * 这是这一层最容易做错的地方。两个理由：
 * <ol>
 *   <li><b>"没查到"不是故障。</b>用户问一个语料里没有的问题，检索工具返回 0 条 ——
 *       这是<b>正常结果</b>。用异常表达它，就会把"正常的空结果"和"代码炸了"
 *       塞进同一条路径，于是日志里全是 ERROR、监控上全是失败率，
 *       而真正的问题被淹掉。台账踩坑库 #26/#37 记的就是这种"错误分类错了"的代价。</li>
 *   <li><b>回填给模型的措辞完全不同。</b>"没有匹配的文档，建议换用更宽的关键词"
 *       和"工具内部错误，请稍后重试"要引导模型做<b>相反</b>的事：
 *       前者该换个问法再试，后者重试同样的参数只会再失败一次。
 *       合并成一种，模型就学不会自愈。</li>
 * </ol>
 *
 * <p>所以：<b>工具自己的"没有 / 不合法 / 依赖不可用"走 {@link Failure}；
 * 只有"实现写错了"（NPE、IO 炸了）才是异常，由执行器兜住。</b>
 * 后者的意义不在于"不会崩"，而在于<b>崩了也不会炸掉整个会话</b> ——
 * 它会变成一条 observation 回到模型那里。
 *
 * @param payload 回填给模型的文本（成功时的结果正文）。<b>这个字符串就是模型看到的全部</b>，
 *                所以它要"像给人看的一样"写清楚，而不是塞一个 JSON 让模型自己猜字段
 * @param truncated 是否因为太长被截断（截断必须显式报出来，见 {@code ToolResultFormatter}）
 */
public sealed interface AgentToolResult {

	/** 工具正常完成。注意"结果为空"也走这里 —— 空结果是一种正常结果。 */
	record Success(String payload, boolean truncated) implements AgentToolResult {
	}

	/**
	 * 工具明确地没能完成这件事，但<b>没有崩</b>。
	 *
	 * @param code 机器可读的短码（报告与自检断言用它，不用中文）
	 * @param message 给模型看的一句话。<b>必须带上下一步能做什么</b> ——
	 *                只说"失败了"模型只能重复同样的调用
	 * @param retriable 同样的参数再试一次有没有可能成功。
	 *                  这个布尔直接决定回填给模型的是"换个参数再试"还是"别重试了，换个工具"
	 */
	record Failure(String code, String message, boolean retriable) implements AgentToolResult {

		/** 参数合法但值越界/不存在（例如查了一个语料里没有的章节）。 */
		public static Failure notFound(String message) {
			return new Failure("not_found", message, false);
		}

		/** 工具依赖的下游暂时不可用（依赖服务挂掉、超时）。 */
		public static Failure upstream(String message) {
			return new Failure("upstream_unavailable", message, true);
		}
	}

	/** 便捷构造：成功且未截断。 */
	static Success ok(String payload) {
		return new Success(payload, false);
	}

	/** 便捷构造：成功但被截断。 */
	static Success truncatedAt(String payload) {
		return new Success(payload, true);
	}

	/** 成功结果的正文，失败时给出失败的说明（用于日志与报告的统一读取）。 */
	static String textOf(AgentToolResult result) {
		return switch (result) {
			case Success s -> s.payload();
			case Failure f -> f.message();
		};
	}

	/** 参数名清单，用于生成"可用值"提示（自愈率的来源之一）。 */
	static String hint(List<String> usableNames) {
		return usableNames.isEmpty() ? "" : "可用值：" + String.join("、", usableNames);
	}

}
