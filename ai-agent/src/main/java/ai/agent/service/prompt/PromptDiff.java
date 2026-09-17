package ai.agent.service.prompt;

import java.util.List;

/**
 * 提示词版本之间的逐行差异（阶段四第 3 件）。
 *
 * <h2>为什么差异算在后端</h2>
 * "什么算改了一行"是一条规则：折叠相邻的同类型行、把"改了一行"表达成"删一行 + 加一行"、
 * 未变更的上下文留几行。放在界面上会变成两处实现，而两处迟早不一致 ——
 * 那时会出现"后端说改了 3 行、界面显示改了 5 行"，且没有任何一方是错的。
 *
 * <h2>为什么 diff 必须返回原文片段</h2>
 * 页面上要能直接看到被改的那几行是什么。如果只返回"第 12 行、第 18 行有变化"，
 * 读者还得自己去数行 —— 而"数行"这件事一旦要人做，这个视图就没人用了。
 *
 * @param kind       哪一份提示词
 * @param fromVersion 起始版本
 * @param toVersion   目标版本
 * @param lines      逐行差异（保序）
 * @param added      新增行数（<b>不是</b> lines 里 ADD 的条数：连续的改动会合并成一条）
 * @param removed    删除行数
 * @param tokenDelta 目标版比起始版多占的 token（可正可负）
 * @param identical  两份正文完全相同（此时 lines 为空，页面要明说"没有差异"）
 */
public record PromptDiff(
		PromptKind kind,
		int fromVersion,
		int toVersion,
		List<Line> lines,
		int added,
		int removed,
		int tokenDelta,
		boolean identical) {

	/** 一行差异。 */
	public enum Op {
		/** 两边都有，原样（只保留改动附近的少量上下文）。 */
		SAME,
		/** 新增的一行。 */
		ADD,
		/** 删掉的一行。 */
		REMOVE,
		/** 改动前存在、改动后不存在的行号区间被折叠起来了（不逐行列出）。 */
		GAP
	}

	/**
	 * @param op   这一行的性质
	 * @param from 起始版里的行号（1 开始）；{@code null} 表示这一行在起始版里不存在
	 * @param to   目标版里的行号（1 开始）；{@code null} 表示这一行在目标版里不存在
	 * @param text 正文（含 {@link Op#GAP} 时是一句"省略了 N 行"的说明）
	 */
	public record Line(Op op, Integer from, Integer to, String text) {

		static Line gap(int count) {
			return new Line(Op.GAP, null, null, "……省略 " + count + " 行未变更的内容……");
		}

		static Line same(int lineNo, String text) {
			return new Line(Op.SAME, lineNo, lineNo, text);
		}

	}
}
