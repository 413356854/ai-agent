package ai.agent.entity.rag.eval;

import ai.agent.service.rag.eval.SectionLocator;
import java.util.List;

/**
 * 一条评测用例（纯数据）。
 *
 * <h2>相关性判定放在哪里</h2>
 * <b>不在这里</b>，在 {@link SectionLocator}。原因是第一版的判定写成
 * "块的标题路径里是否包含标准答案章节"，结果对不带标题路径的对照组
 * （固定字数硬切）恒判为不相关，把它的 Recall 压成 0.00 ——
 * 看起来像"对照组检索完全不行"，实际是"判定函数对它不适用"。
 * 详见 {@link SectionLocator} 的类注释。
 *
 * <p>把判定与数据分开之后，{@code EvalCase} 退化成一个朴素记录，
 * 而"怎么判相关"只有一处实现 —— 也就只有一处可能出错。
 *
 * @param source        用例来自哪份语料（文件名）
 * @param id            用例编号，例如 {@code L02}
 * @param question      用户提问
 * @param matchHeadings 标准答案所在章节的标题，多条表示答案跨章节
 * @param tag           分组标签（failures / phase1 / phase2 / knowledge / materials）
 */
public record EvalCase(String source, String id, String question, List<String> matchHeadings, String tag) {

	public EvalCase {
		matchHeadings = List.copyOf(matchHeadings);
	}

}
