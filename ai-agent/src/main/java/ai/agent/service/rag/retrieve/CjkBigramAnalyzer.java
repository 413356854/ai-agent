package ai.agent.service.rag.retrieve;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cjk.CJKBigramFilter;
import org.apache.lucene.analysis.cjk.CJKWidthFilter;
import org.apache.lucene.analysis.core.StopFilter;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 中英混排分析器：<b>中文按二元组切分，英文按词切分</b>，无需任何中文分词依赖。
 *
 * <h2>为什么不用 IK / jieba 这类中文分词器</h2>
 * 三个理由，按重要性排序：
 * <ol>
 *   <li><b>少一个会出错的环节</b>。评测器的职责是量出"分块策略"的差异。
 *       如果它自己依赖一个带词典的分词器，那么"要加载词典、词典版本会变、
 *       未登录词切错"这些问题会混进结果里，而它们的表现和"分块变差了"一模一样——
 *       根本分不清。用 Lucene 自带的 CJK 二元组，行为完全确定，没有外部状态。</li>
 *   <li><b>二元组在检索上并不差</b>。检索要的是"能匹配上"，
 *       不是"切出语言学上正确的词"。把"虚拟线程"切成「虚拟/拟线/线程」，
 *       用户查"虚拟线程"同样会被切成这三个二元组，照样命中；
 *       甚至查"线程的坑"也能因为「线程」这个二元组命中。
 *       代价是召回变宽（可能引入噪音），但<b>这正是 RRF + Rerank 要解决的问题</b>，
 *       不该由分析器来扛。</li>
 *   <li><b>它是可替换的</b>。等真的需要精确分词时，换掉这一个类即可。</li>
 * </ol>
 *
 * <h2>过滤器链的顺序不是随便排的</h2>
 * <pre>
 *   StandardTokenizer   切出词（中文会被切成单字，英文按空格/标点切）
 *     → CJKWidthFilter  全角转半角（"ＡＢＣ"→"abc"、"１"→"1"）
 *     → LowerCaseFilter 统一小写
 *     → StopFilter      去掉停用词（<b>必须在 CJKBigram 之前</b>）
 *     → CJKBigramFilter 把连续的中文单字两两组合成二元组
 * </pre>
 * <b>顺序为什么重要</b>：{@code StopFilter} 如果在 {@code CJKBigramFilter} 之后，
 * 二元组「的坑」不会等于停用词「的」，于是停用词根本去不掉 ——
 * 链条顺序错了不会报错，只会让过滤静默失效。
 *
 * <p>另外 {@code CJKBigramFilter} 默认只处理中/日/韩，英文词原样透传，
 * 所以中英混排文档不用担心英文被拆坏。
 */
public final class CjkBigramAnalyzer extends Analyzer {

	private final Analyzer reusable;

	public CjkBigramAnalyzer() {
		this.reusable = new EnglishAnalyzer();
	}

	/**
	 * 每线程一套组件。
	 *
	 * <p>Lucene 的分析器组件<b>有状态</b>（TokenStream 复用同一批缓冲区），
	 * 因此必须用 {@code perThread} 而不是在多个线程间共享实例 ——
	 * 共享的表现是多线程下偶发地切出乱七八糟的 token，
	 * 而且只在并发时出现，单测永远发现不了。
	 */
	@Override
	protected TokenStreamComponents createComponents(String fieldName) {
		StandardTokenizer tokenizer = new StandardTokenizer();
		TokenStream stream = tokenizer;
		stream = new CJKWidthFilter(stream);
		stream = new LowerCaseFilter(stream);
		// 英文停用词表足够用：中文停用词（的/了/是）在这里不关键，
		// 因为二元组本来就很少单独命中它们（"的坑"这种组合反而有信息量）
		stream = new StopFilter(stream, EnglishAnalyzer.ENGLISH_STOP_WORDS_SET);
		stream = new CJKBigramFilter(stream);
		// 折叠重音符号：café / cafe 视为同一个词，避免用户少打一个音符就检索不到
		stream = new ASCIIFoldingFilter(stream);
		return new TokenStreamComponents(tokenizer, stream);
	}

	/**
	 * 便于诊断：把一段文本按本分析器切开，返回 token 列表。
	 *
	 * <p>这是"分词看不到"这个问题的解药 —— 检索不出结果时，
	 * 第一件该确认的事就是"提问和文档到底被切成了什么"。
	 * 十有八九问题就出在这里，而看不到 token 就只能瞎猜。
	 */
	public List<String> analyze(String text) {
		List<String> tokens = new ArrayList<>();
		try (TokenStream stream = tokenStream("text", text)) {
			stream.reset();
			CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
			while (stream.incrementToken()) {
				tokens.add(term.toString());
			}
			stream.end();
		}
		catch (IOException e) {
			throw new UncheckedIOException("分析文本失败", e);
		}
		return tokens;
	}

}
