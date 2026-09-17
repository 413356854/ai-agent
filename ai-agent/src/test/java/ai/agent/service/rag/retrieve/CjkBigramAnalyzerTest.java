package ai.agent.service.rag.retrieve;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 中英混排分析器的测试。
 *
 * <h2>为什么要单独测分词</h2>
 * 因为它是整条检索链路里<b>最不透明</b>的一环：块切得对不对肉眼能看出来，
 * 分数高低能看出来，但"提问和文档被切成了什么 token"看不见。
 * 而检索不到结果的原因，十有八九就在这一步 —— 提问切成「虚拟/拟线/线程」，
 * 文档切成「虛擬」（繁体）、或英文被切碎，都能让匹配彻底失败，
 * 且<b>不报任何错</b>。
 */
class CjkBigramAnalyzerTest {

	private final CjkBigramAnalyzer analyzer = new CjkBigramAnalyzer();

	@Test
	@DisplayName("中文按二元组切分：虚拟线程 → 虚拟/拟线/线程")
	void producesBigramsForCjk() {
		List<String> tokens = analyzer.analyze("虚拟线程");
		assertThat(tokens).containsExactly("虚拟", "拟线", "线程");
	}

	@Test
	@DisplayName("英文按词切分并小写，不被二元组逻辑拆坏")
	void keepsEnglishWordsIntact() {
		List<String> tokens = analyzer.analyze("Virtual Threads and BM25");
		assertThat(tokens).contains("virtual", "threads", "bm25");
		// 英文绝不能被切成 "vi","ir","rt" 这种碎片
		assertThat(tokens).doesNotContain("vi", "ir");
	}

	@Test
	@DisplayName("中文提问与中文文档能对上：这是二元组方案可行的根本原因")
	void queryMatchesDocument() {
		List<String> doc = analyzer.analyze("虚拟线程在阻塞时会让出载体线程");
		List<String> query = analyzer.analyze("怎么让线程在阻塞时让出载体");

		// 取提问里的二元组，看在文档里命中几个
		long overlap = query.stream().filter(doc::contains).count();
		assertThat(overlap).as("提问与文档应当有相当数量的二元组重合").isGreaterThanOrEqualTo(3);
	}

	@Test
	@DisplayName("全角字符被归一化：Ａ Ｂ Ｃ 与 ABC 等价")
	void normalizesFullWidth() {
		assertThat(analyzer.analyze("ＡＢＣ")).containsExactlyElementsOf(analyzer.analyze("ABC"));
	}

	@Test
	@DisplayName("空输入返回空列表，不抛异常")
	void handlesEmpty() {
		assertThat(analyzer.analyze("")).isEmpty();
		assertThat(analyzer.analyze("   ")).isEmpty();
	}

}
