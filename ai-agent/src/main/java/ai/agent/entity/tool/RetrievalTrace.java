package ai.agent.entity.tool;

import ai.agent.entity.rag.chunk.Chunk;
import ai.agent.service.tool.AgentToolResult;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>一次检索的完整明细</b> —— 阶段四「Agent trace 五层」里的<b>「检索」那一层</b>。
 *
 * <h2>它存在的理由：文本对模型是对的，对人什么都不是</h2>
 * 在这之前，{@code search_docs} 的返回只有一段文本（回填给模型的那段）。
 * 文本对模型完全正确 —— 它要判断的是"有没有相关内容"，字段名帮不上忙。
 * 但对**人**，那段文本回答不了下面任何一个问题，而它们正是「日志能还原执行过程」的全部内容：
 * <table border="1">
 *   <caption>文本回答不了的问题</caption>
 *   <tr><th>问题</th><th>当时只能怎么做</th></tr>
 *   <tr><td>这次任务一共检索了几次</td><td>数回填文本里出现了几次「在 516 个文档片段里找到」</td></tr>
 *   <tr><td>命中的是哪几个块</td><td>正则解析那段中文</td></tr>
 *   <tr><td>检索塞进 prompt 的 token 有多少</td><td><b>做不到</b> —— 文本里根本没有这个数</td></tr>
 * </table>
 * 第三行是真正的缺口：阶段二量出来的 token 口径（{@code avgTokensUsed}）
 * 在 Agent 这条链路上<b>追不到</b>，于是"RAG 到底占了成本的多少"一直答不了。
 *
 * <h2>为什么它落在"每一次工具调用"上，而不是"每一步"</h2>
 * 一步里可以发起多次检索（模型一次提出多个并行工具调用），
 * 而「第 2 步的两次检索分别查了什么」必须能分开看 —— 合到一步上就退化成"这一步检索过"。
 *
 * <h2>为什么这个类型住在 {@code tool} 包，而不是 {@code agent.trace} 包</h2>
 * 因为它由**工具**产出（{@link AgentToolResult}），并挂在 {@link ToolCallOutcome} 上 ——
 * 而那两个类型都在本包。放进 {@code agent} 包会让"工具产出物"反向依赖 Agent 层，
 * 于是工具自己的单测要连带拖进整个 Agent 的类图。
 * 真正的 Agent trace 语义（五层怎么串、怎么读）在 {@code ai.agent.service.agent.trace} 那一侧，
 * 这个类只负责"把一次检索的明细端出来"。
 *
 * @param query 实际拿去检索的查询词（<b>模型给的原词</b>，不做归一化 —— 排查"它为什么查不到"
 *              时，改过的词会把线索擦掉）
 * @param topK 模型要了几条（不是引擎实际返回几条）。两者不同说明<b>召回不足</b>，
 *             而那是"语料里没有"与"检索没找着"的分界
 * @param indexSize 当时索引里有多少块。它是"0 条命中"的分母 ——
 *                  0 条命中在这两种情况下含义完全相反：索引是空的（环境问题），
 *                  还是索引有 516 块但没匹配上（语料问题）
 * @param hits 命中的块（按名次升序）
 * @param elapsedMs 这次检索自己的耗时（不含参数解析、闸门排队）
 */
public record RetrievalTrace(String query, int topK, int indexSize, List<Hit> hits, long elapsedMs) {

	/**
	 * 片段摘录的长度上限。
	 *
	 * <p>与 {@code SearchDocsTool} 喂给模型的摘录<b>不是同一个值，也是刻意的</b>：
	 * 那边的 220 字受 {@code ai.tool.feedback.max-chars} 这个上下文预算约束，
	 * 这边的 512 字受界面可读性约束。两处的约束来源不同 ——
	 * 共用一个常量会让一次改动同时影响两件事，而"改一处"的动机永远不存在。
	 *
	 * <p>⚠️ <b>这个数必须与 {@code retrieval_hit.excerpt VARCHAR(512)} 的列宽一致</b>，
	 * 改一处要一起改。这条约束是被一次实测抓出来的：第一版把省略号写在截断<b>之后</b>，
	 * 于是最长的摘录是 512 + 1 = 513 个字符，插库时 H2 报
	 * {@code Value too long for column "excerpt CHARACTER VARYING(512)"}。
	 * 单看代码那一行完全正常（"截断到 512 再加省略号"读起来天经地义），
	 * 只有真的写一次库才会发现 —— 这正是"边界值必须真的跑一遍"的又一处落点。
	 */
	public static final int EXCERPT_CHARS = 512;

	/** 截断标记。单独抽出来，好让 {@link #excerpt} 能把它算进长度上限里。 */
	private static final String ELLIPSIS = "…";

	public RetrievalTrace {
		hits = List.copyOf(hits);
	}

	/** 从检索引擎的原始命中构造（查询词、topK 与索引规模由调用方补上）。 */
	public static RetrievalTrace of(String query, int topK, int indexSize,
			List<ai.agent.entity.rag.retrieve.RetrievalHit> raw, long elapsedMs) {
		List<Hit> hits = new ArrayList<>(raw.size());
		for (var hit : raw) {
			Chunk chunk = hit.chunk();
			hits.add(new Hit(hit.rank(), chunk.id(), chunk.documentId(), chunk.breadcrumb(), chunk.tokenCount(),
					hit.score(), excerpt(chunk.text())));
		}
		return new RetrievalTrace(query, topK, indexSize, hits, elapsedMs);
	}

	/**
	 * 一条都没查到时也要有痕迹。
	 *
	 * <p>"0 条"是正常结果（不是故障），但它是**必须能被查到的**正常结果 ——
	 * 用户说"它答不上来"时，第一个要看的正是"它拿什么词去查的、是不是一条都没查到"。
	 */
	public static RetrievalTrace empty(String query, int topK, int indexSize, long elapsedMs) {
		return new RetrievalTrace(query, topK, indexSize, List.of(), elapsedMs);
	}

	/** 命中条数。 */
	public int hitCount() {
		return hits.size();
	}

	/**
	 * 这些命中<b>一共占多少 token</b>。
	 *
	 * <p>它是这一层最值钱的数字：「RAG 到底占了成本的多少」这句话在此之前没有数据可依。
	 * 口径是<b>估算</b>（块自带的 {@code tokenCount} 来自 {@code TokenEstimator}），
	 * 与成本看板那条"token 来自上游 usage、不估算"<b>不是同一个口径</b> ——
	 * 所以它在页面上必须标成估算，绝不能混进账目里。
	 */
	public int totalHitTokens() {
		return hits.stream().mapToInt(Hit::tokenCount).sum();
	}

	/** 名次是否连续（1..n）。断续说明引擎返回的名次与列表顺序对不上 —— 那会让页面显示错名次。 */
	public boolean ranksAreSequential() {
		for (int i = 0; i < hits.size(); i++) {
			if (hits.get(i).rank() != i + 1) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 片段摘录：压平空白 + 截断。压平是因为界面那一格是单行文本。
	 *
	 * <h2>截断的写法为什么是"从上限里扣掉省略号"，而不是"截到上限再加省略号"</h2>
	 * 因为返回值有<b>硬上限</b>（{@link #EXCERPT_CHARS}，与数据库列宽一致）。
	 * 后一种写法产出的最长串是 {@code 上限 + 1} 个字符 —— 于是**只有最长的那些摘录**
	 * 会在插库时炸，而短摘录永远正常。这类"只在边界上炸"的缺陷最难在本地发现：
	 * 语料里恰好有几条长块才会撞上，而报错信息指向 SQL 而不指向这段截断逻辑。
	 */
	private static String excerpt(String text) {
		if (text == null) {
			return "";
		}
		String flat = text.replace('\n', ' ').replaceAll("\\s+", " ").strip();
		if (flat.length() <= EXCERPT_CHARS) {
			return flat;
		}
		// 从上限里扣掉省略号本身的长度 —— 保证返回值**永远不超**上限
		return flat.substring(0, EXCERPT_CHARS - ELLIPSIS.length()) + ELLIPSIS;
	}

	/**
	 * 一条命中。
	 *
	 * @param rank 名次（从 1 开始，<b>就是模型看到的名次</b>）
	 * @param chunkId 块 id（{@code 文档id#序号}）—— 能直接回到分块结果
	 * @param documentId 来源文档
	 * @param breadcrumb 标题路径（"这条命中属于哪一节"）
	 * @param tokenCount 这个块估算的 token
	 * @param score 相关性得分；<b>只在同一次检索内可比</b> —— 不同引擎的量纲不同
	 *              （这正是混合检索必须按名次融合、不能直接相加的原因）
	 * @param excerpt 片段开头（截断），给人扫一眼认召回对不对
	 */
	public record Hit(int rank, String chunkId, String documentId, String breadcrumb, int tokenCount, double score,
			String excerpt) {
	}

}
