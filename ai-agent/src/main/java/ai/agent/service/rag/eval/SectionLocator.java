package ai.agent.service.rag.eval;

import ai.agent.service.rag.parse.ParsedBlock;
import ai.agent.service.rag.parse.ParsedDocument;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 章节定位器：把"某文档的某章节"解析成<b>它覆盖的解析块区间</b>。
 *
 * <h2>它解决的第一个问题：对比不公平（这个坑很隐蔽）</h2>
 * 第一版的相关性判定是"块的标题路径里是否包含标准答案章节"。跑出来：
 * <pre>
 *   固定字数硬切   Recall@5 = 0.00   ← 看起来"完全不行"
 *   结构感知分块   Recall@5 = 0.74
 * </pre>
 * 如果就此写进报告，结论会是"结构分块把召回率从 0 提到了 0.74" ——
 * <b>而这是假的</b>。因为硬切根本没有标题路径（见 {@code FixedSizeChunker}：
 * 它不知道标题在哪，这是它作为对照组的本质特征），于是它对<b>任何</b>用例
 * 都判为"不相关"。那个 0.00 不是"检索不到"，是"判定函数对它不适用"。
 *
 * <p>这类错误的危险之处在于它<b>朝有利于自己的方向偏</b>：
 * 实验组虚高、对照组虚低，结论看起来很漂亮，而且不会报错。
 * 一个"提升很大"的结果很少会被人质疑 —— 这正是它容易被采纳的原因。
 *
 * <h2>正确做法：用内容判定，而不是用元数据判定</h2>
 * "第三节讲的是什么"是文档的客观事实，与怎么切块无关。所以这里先把
 * 标准答案章节解析成它覆盖哪些解析块，再看召回的块覆盖了哪些解析块，
 * <b>有交集即命中</b>。两种策略由此在同一个判据下比较：比的是
 * "谁把答案捞回来了"，而不是"谁的元数据更全"。
 *
 * <h2>它顺带解决的第二个问题：标注校验</h2>
 * 若某个标注章节名在语料里根本找不到，它会被记进 {@link #unresolved()}
 * 并在报告里显式暴露 —— 而不是让那条用例悄悄算作"未命中"
 * （那会让分数下降，且没人知道原因是标注过期了）。
 */
public final class SectionLocator {

	/**
	 * 预计算的章节区间表：{@code 文档id → (章节标题 → 块区间列表)}。
	 *
	 * <p><b>为什么必须预计算</b>：判定是"每条用例 × 每条召回结果 × 每条标注"
	 * 的三重循环，43 条用例 × 10 条召回 × 2 个策略就是 860 次判定。
	 * 每次都去线性扫一遍文档块的话，评测会从"秒级"变成"要等一会儿"——
	 * 而调参阶段评测要跑几十遍，这个差别直接决定"愿不愿意多试一组参数"。
	 */
	private final Map<String, Map<String, List<int[]>>> spansByDocument;

	private final Map<String, ParsedDocument> documentsById;

	private final List<String> unresolved;

	private SectionLocator(Map<String, Map<String, List<int[]>>> spansByDocument,
			Map<String, ParsedDocument> documentsById, List<String> unresolved) {
		this.spansByDocument = spansByDocument;
		this.documentsById = documentsById;
		this.unresolved = List.copyOf(unresolved);
	}

	/**
	 * 从语料构建定位器，并预先把所有标注解析成区间。
	 *
	 * @param cases     评测用例（用它们的 matchHeadings 建立索引并做存在性校验）
	 * @param documents 解析后的语料
	 */
	public static SectionLocator build(List<EvalCase> cases, List<ParsedDocument> documents) {
		Map<String, ParsedDocument> byId = new LinkedHashMap<>();
		for (ParsedDocument document : documents) {
			byId.put(document.documentId(), document);
		}

		// 只解析"用例引用到的那些章节"，而不是文档里所有标题 ——
		// 43 条用例引用的章节通常只有 20 来个，没必要把几百个标题全解析一遍
		Map<String, Map<String, List<int[]>>> spans = new LinkedHashMap<>();
		List<String> unresolved = new ArrayList<>();

		for (EvalCase evalCase : cases) {
			ParsedDocument document = resolveDocument(byId, evalCase.source());
			if (document == null) {
				unresolved.add("%s: 语料里没有这份文档「%s」".formatted(evalCase.id(), evalCase.source()));
				continue;
			}
			Map<String, List<int[]>> perDoc = spans.computeIfAbsent(document.documentId(),
					k -> new LinkedHashMap<>());
			for (String heading : evalCase.matchHeadings()) {
				if (perDoc.containsKey(heading)) {
					continue; // 已在别处解析过，不重复劳动
				}
				List<int[]> found = locate(document, heading);
				perDoc.put(heading, found);
				if (found.isEmpty()) {
					unresolved.add("%s: 「%s」在 %s 里找不到对应章节"
							.formatted(evalCase.id(), heading, document.documentId()));
				}
			}
		}
		return new SectionLocator(spans, byId, unresolved);
	}

	/**
	 * 定位一个章节覆盖的块区间，返回<b>所有</b>同名匹配（同名标题可能出现在多处）。
	 *
	 * <p>区间为【闭区间】{@code [起, 止]}：起 = 标题块本身，
	 * 止 = 下一个<b>同级或更高级</b>标题之前。这样"一节"的范围与人的直觉一致：
	 * 一级标题下可以有很多二级标题，它们都属于这一节。
	 */
	private static List<int[]> locate(ParsedDocument document, String heading) {
		List<ParsedBlock> blocks = document.blocks();
		List<int[]> spans = new ArrayList<>();
		for (int i = 0; i < blocks.size(); i++) {
			ParsedBlock block = blocks.get(i);
			// 子串匹配而不是等号：标题里可能带 emoji 前缀或尾部补充说明
			// （"学习点 7 · 虚拟线程异步改造（2026-09-14 完成）"），
			// 等号匹配会让标注一改标题就静默失效
			if (!block.isHeading() || !block.text().contains(heading)) {
				continue;
			}
			int level = Math.max(1, block.level());
			int end = blocks.size() - 1;
			for (int j = i + 1; j < blocks.size(); j++) {
				ParsedBlock next = blocks.get(j);
				if (next.isHeading() && Math.max(1, next.level()) <= level) {
					end = j - 1;
					break;
				}
			}
			spans.add(new int[] { i, end });
		}
		return spans;
	}

	private static ParsedDocument resolveDocument(Map<String, ParsedDocument> byId, String source) {
		ParsedDocument exact = byId.get(source);
		if (exact != null) {
			return exact;
		}
		// 兼容 documentId 带目录前缀的情况
		for (Map.Entry<String, ParsedDocument> entry : byId.entrySet()) {
			if (entry.getKey().endsWith(source)) {
				return entry.getValue();
			}
		}
		return null;
	}

	/**
	 * 判定一个块是否命中某条用例的标准答案。
	 *
	 * <p><b>要求块与标注属于同一份文档</b>：不同文档出现同名章节（每个模块都有一节
	 * "注意事项"）时不能算命中，否则召回率会虚高。
	 *
	 * @param evalCase   用例
	 * @param documentId 块所属文档
	 * @param startBlock 块覆盖的起始解析块下标（-1 表示该策略没记录区间）
	 * @param endBlock   块覆盖的结束解析块下标
	 */
	public boolean matches(EvalCase evalCase, String documentId, int startBlock, int endBlock) {
		if (startBlock < 0 || endBlock < startBlock) {
			return false;
		}
		ParsedDocument document = resolveDocument(documentsById, documentId);
		if (document == null) {
			return false;
		}
		Map<String, List<int[]>> perDoc = spansByDocument.get(document.documentId());
		if (perDoc == null) {
			return false;
		}
		for (String heading : evalCase.matchHeadings()) {
			for (int[] span : perDoc.getOrDefault(heading, List.of())) {
				// 区间有交集即算命中：块只要"碰到了"答案所在的那一节就算
				if (startBlock <= span[1] && endBlock >= span[0]) {
					return true;
				}
			}
		}
		return false;
	}

	/** 标注解析失败的清单（章节名在语料里找不到）—— 必须出现在报告里。 */
	public List<String> unresolved() {
		return unresolved;
	}

	/** 全部语料文档。 */
	public List<ParsedDocument> documents() {
		return List.copyOf(documentsById.values());
	}

}
