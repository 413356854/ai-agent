package ai.agent.service.chat.structured;

import java.util.List;

/**
 * 结构化输出的一次完整调用结果：最终答案 + <b>拿到它的过程</b>。
 *
 * <p><b>为什么要把"过程"也返回</b>：结构化输出的价值不在于"最后拿到了对象"，
 * 而在于"模型不听话时系统怎么办"。如果只返回最终对象，那么
 * "第一次就成功"和"重试两次才成功"看起来一模一样，演示时讲不出东西，
 * 出问题时（比如某天失败率突然升高）也没有任何线索。
 *
 * <p>把 attempts 暴露出来之后可以直接回答这些问题：
 * <ul>
 *   <li>模型第一次就守规矩的比例是多少？（{@code attempts.size() == 1} 的占比）</li>
 *   <li>它最常犯哪种错？（看 {@code issues} 的分布 —— 实测 confidence 给成百分制最常见）</li>
 *   <li>回灌一次够不够？（{@code attempts.size()} 的分布）</li>
 * </ul>
 * 这也是阶段四"调用日志 + Agent trace"最原始的雏形。
 *
 * @param answer   最终答案；若 {@code degraded=true} 则是兜底内容，不是模型给的
 * @param attempts 每一次尝试的记录（含失败的那些），按发生顺序
 * @param degraded 是否降级（模型在限定轮数内始终没能产出合格结果）
 */
public record StructuredResult(
		StructuredAnswer answer,
		List<Attempt> attempts,
		boolean degraded
) {

	/** 最后一次尝试的序号（从 1 开始），也就是"总共尝试了几次" */
	public int totalAttempts() {
		return attempts.size();
	}

	/** 是否发生过回灌重试（即第一次没成功） */
	public boolean neededReflection() {
		return attempts.size() > 1;
	}

	/**
	 * 一次尝试的完整记录。
	 *
	 * <p>{@code rawOutput} 特意<b>保留原始文本</b>而不是只留解析后的对象：
	 * 排查"模型到底吐了什么鬼东西"时，只有原始文本能给出答案 ——
	 * 一旦解析成对象，那些"多余的 markdown 代码块围栏""字段名写成中文""JSON 被截断"
	 * 的痕迹就全丢了。
	 *
	 * @param index     第几次尝试，从 1 开始
	 * @param rawOutput 模型这一次的原始输出（可能为 null，表示调用直接失败）
	 * @param parsed    解析并绑定成功的结果；解析或校验失败时为 null
	 * @param issues    这一次失败的原因清单（可读中文），成功时为空
	 * @param elapsedMs 这一次尝试耗时
	 */
	public record Attempt(
			int index,
			String rawOutput,
			StructuredAnswer parsed,
			List<String> issues,
			long elapsedMs
	) {

		public boolean success() {
			return parsed != null && issues.isEmpty();
		}
	}
}
