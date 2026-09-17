package ai.agent.service.rag.retrieve;

import ai.agent.entity.rag.chunk.Chunk;
import ai.agent.entity.rag.retrieve.RetrievalHit;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 词法检索实现：Lucene BM25，<b>内存索引</b>。
 *
 * <h2>为什么用 Bert 而不是自己写 BM25</h2>
 * 教训是：<b>压测客户端必须比被测系统更不容易成为瓶颈</b>，
 * 否则量出来的是客户端的上限。评测器同理 ——
 * 自己写的 BM25 一旦在语料统计、长度归一化、IDF 上有一处算错，
 * 量出来的差异就分不清是"分块策略的差异"还是"我 BM25 写错了"。
 * 用成熟实现，把变量的个数降到最低。
 *
 * <h2>为什么把标题路径单独建一个字段</h2>
 * 块正文里已经含了面包屑（见 {@code StructuralChunker}），再单独索引标题字段
 * 看似重复，但作用不同：<b>单独字段可以做权重</b>。
 * 标题命中"虚拟线程"通常比正文里出现一次更有判别力，
 * 所以给标题 2 倍权重 —— 这也是"结构化分块"的优势能否兑现的关键之一：
 * 硬切没有标题字段可给（它根本不知道标题在哪）。
 *
 * <h2>为什么用内存索引而不是落盘</h2>
 * 评测要跑很多轮（换参数、换策略各跑一遍），落盘索引会带来
 * "上一轮的索引没删干净"这类问题，而且会污染一个真实目录。
 * 语料只有几百 KB，{@link ByteBuffersDirectory} 完全够，且天然隔离。
 */
public class LexicalRetriever implements Retriever {

	private static final Logger log = LoggerFactory.getLogger(LexicalRetriever.class);

	/** 评测报告里的列名。 */
	public static final String NAME = "lexical-bm25";

	/** 正文与标题两个字段。 */
	private static final String FIELD_TEXT = "text";

	private static final String FIELD_HEADING = "heading";

	/** 标题的权重。取 2 是常见起点：既体现"标题命中更有判别力"，又不至于压倒正文。 */
	private static final float HEADING_BOOST = 2.0f;

	private final Analyzer analyzer = new CjkBigramAnalyzer();

	private final Directory directory = new ByteBuffersDirectory();

	/**
	 * 块元数据。
	 *
	 * <p>存一份内存映射而不是把整个 {@link Chunk} 序列化进索引：
	 * 评测只在单进程内跑，直接从内存取比"从索引里反序列化"简单得多，
	 * 也避免了"索引里的块与内存里的块不一致"这种只会在改代码后才暴露的问题。
	 */
	private final Map<String, Chunk> chunksById = new HashMap<>();

	private IndexSearcher searcher;

	private int indexedCount;

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public void index(List<Chunk> chunks) {
		// 重建语义：先清空。评测的两组实验必须互不干扰，
		// 否则上一组的块会留在索引里参与召回，两次实验就不是同一个起点了
		chunksById.clear();
		try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
			writer.deleteAll();
			for (Chunk chunk : chunks) {
				Document doc = new Document();
				doc.add(new TextField(FIELD_TEXT, chunk.text(), Field.Store.NO));
				// 标题路径为空时给空串：Lucene 的字段不能为 null
				doc.add(new TextField(FIELD_HEADING, chunk.breadcrumb(), Field.Store.NO));
				// 只存 id，真正的块对象放内存映射
				doc.add(new StoredField("id", chunk.id()));
				writer.addDocument(doc);
				chunksById.put(chunk.id(), chunk);
			}
			writer.commit();
		}
		catch (IOException e) {
			throw new UncheckedIOException("建立内存索引失败", e);
		}

		try {
			// 每轮重建后换一个 reader：BM25 的 IDF 与平均文档长度是**索引级统计量**，
			// 换了一批块必须重新统计，否则打分用的还是上一批语料的统计量
			if (searcher != null && searcher.getIndexReader() != null) {
				searcher.getIndexReader().close();
			}
			searcher = new IndexSearcher(DirectoryReader.open(directory));
		}
		catch (IOException e) {
			throw new UncheckedIOException("打开索引失败", e);
		}

		indexedCount = chunksById.size();
		log.info("词法索引重建完成 chunks={}", indexedCount);
	}

	@Override
	public List<RetrievalHit> retrieve(String query, int topK) {
		if (searcher == null || indexedCount == 0 || query == null || query.isBlank()) {
			return List.of();
		}
		if (topK <= 0) {
			return List.of();
		}

		try {
			Query parsed = buildQuery(query);
			if (parsed == null) {
				return List.of();
			}
			TopDocs topDocs = searcher.search(parsed, topK);

			List<RetrievalHit> hits = new ArrayList<>();
			int rank = 1;
			for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
				Document doc = searcher.storedFields().document(scoreDoc.doc);
				String id = doc.get("id");
				Chunk chunk = chunksById.get(id);
				if (chunk == null) {
					// 索引与内存映射不一致属于实现 bug，必须响，而不是静默跳过 ——
					// 静默跳过会让召回率凭空变低，看起来像"分块策略变差了"
					throw new IllegalStateException("索引里的块在内存映射中不存在：" + id);
				}
				hits.add(new RetrievalHit(chunk, scoreDoc.score, rank));
				rank++;
			}
			return hits;
		}
		catch (IOException e) {
			throw new UncheckedIOException("检索失败：query=" + query, e);
		}
	}

	/**
	 * 构造查询：把提问里的词分别到正文与标题字段上匹配，用 OR 语义。
	 *
	 * <p><b>不用 AND</b>：用户提问里的词不一定每个都在同一个块里出现
	 * （"怎么防止块被切断"里，"切断"可能不在目标块）。用 AND 会导致
	 * 一条都召不回来 —— 表现是"召回率 0"，而原因和分块质量无关。
	 * OR + 打分排序 才是检索该做的事：让最像的排在前面。
	 *
	 * <p>{@link QueryParser} 的转义要注意：用户提问里可能有
	 * {@code :} {@code (} {@code "} 这类查询语法字符，
	 * 不转义会直接抛 {@code ParseException}（而且这是个用户可控的输入路径）。
	 * 用 {@link QueryParser#escape} 处理。
	 */
	private Query buildQuery(String raw) {
		String escaped = QueryParser.escape(raw.strip());
		if (escaped.isBlank()) {
			return null;
		}
		try {
			MultiFieldQueryParser parser = new MultiFieldQueryParser(
					new String[] { FIELD_TEXT, FIELD_HEADING }, analyzer);
			// 默认 OR 语义
			parser.setDefaultOperator(QueryParser.Operator.OR);
			Query parsed = parser.parse(escaped);

			// 标题加权：把标题字段上的子查询整体提到 2 倍。
			// 用一个额外的 BooleanQuery 包一层，比依赖 MultiFieldQueryParser 的
			// boost 映射更好读 —— 后者要求传一个 Map<String, Float>，
			// 而这里只有两个字段，写出来反而更绕
			Query headingOnly = new QueryParser(FIELD_HEADING, analyzer).parse(escaped);
			BooleanQuery.Builder builder = new BooleanQuery.Builder();
			builder.add(parsed, BooleanClause.Occur.SHOULD);
			builder.add(new BoostQuery(headingOnly, HEADING_BOOST), BooleanClause.Occur.SHOULD);
			return builder.build();
		}
		catch (Exception e) {
			// 解析失败不该让整轮评测挂掉，但必须留日志 ——
			// 静默返回空结果会让这条用例算成"没召回"，
			// 而那其实是"查询没构造出来"，两者的责任完全不同
			log.warn("查询构造失败，本条用例将无召回：query={} err={}", raw, e.toString());
			return null;
		}
	}

	/** 已索引的块数（诊断端点用）。 */
	public int indexedCount() {
		return indexedCount;
	}

	/** 复用分析器做分词诊断（诊断端点用）。 */
	public List<String> analyze(String text) {
		return ((CjkBigramAnalyzer) analyzer).analyze(text);
	}

}
