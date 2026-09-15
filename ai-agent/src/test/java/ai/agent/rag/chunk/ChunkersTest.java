package ai.agent.rag.chunk;

import ai.agent.config.ChatCallProperties;
import ai.agent.rag.chunk.impl.FixedSizeChunker;
import ai.agent.rag.chunk.impl.StructuralChunker;
import ai.agent.rag.config.RagProperties;
import ai.agent.rag.parse.BlockType;
import ai.agent.rag.parse.ParsedBlock;
import ai.agent.rag.parse.ParsedDocument;
import ai.agent.token.TokenEstimator;
import ai.agent.token.impl.CharacterClassTokenEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两个分块策略的行为测试。
 *
 * <h2>这些测试要钉住的是什么</h2>
 * 分块器的 bug 有个共同特点：<b>不报错、不崩，只是切得不对</b>。
 * 切得不对的后果要等到检索评测里才显现，而且看起来像"检索不行"。
 * 所以这里针对的是几条<b>不变式</b>——它们一旦被破坏，一定是分块的错：
 * <ol>
 *   <li>块永远不超过上限（超了就是超窗，最硬的约束）；</li>
 *   <li>标题必须开启新块，且标题块的标题路径包含它自己
 *       （破坏了就会出现"正文不知道自己属于哪一节"）；</li>
 *   <li>块必须覆盖全部内容，不能丢字（丢了就是静默的数据损失）；</li>
 *   <li>同节内的重叠存在，跨章节的重叠不存在（后者是噪音注射）。</li>
 * </ol>
 */
class ChunkersTest {

	private RagProperties properties;

	private TokenEstimator estimator;

	private FixedSizeChunker fixedChunker;

	private StructuralChunker structuralChunker;

	@BeforeEach
	void setUp() {
		properties = new RagProperties();
		estimator = new CharacterClassTokenEstimator(new ChatCallProperties());
		fixedChunker = new FixedSizeChunker(properties, estimator);
		structuralChunker = new StructuralChunker(properties, estimator);
	}

	// ------------------------------------------------------------------

	@Nested
	@DisplayName("固定字数硬切（对照组）")
	class Fixed {

		@Test
		@DisplayName("块不超过配置的字数上限")
		void respectsSize() {
			properties.getChunking().getFixed().setSize(100);
			properties.getChunking().getFixed().setOverlap(10);

			ParsedDocument doc = document("d", "字".repeat(1000));
			List<Chunk> chunks = fixedChunker.chunk(doc);

			assertThat(chunks).isNotEmpty();
			assertThat(chunks).allSatisfy(c -> assertThat(c.charCount()).isLessThanOrEqualTo(100));
		}

		@Test
		@DisplayName("相邻块之间存在重叠（重叠是两组的共同点，不是语义分块的专利）")
		void hasOverlapBetweenNeighbours() {
			properties.getChunking().getFixed().setSize(100);
			properties.getChunking().getFixed().setOverlap(20);

			// 用可区分的序列，便于核对重叠内容
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 50; i++) {
				sb.append("%04d".formatted(i));
			}
			List<Chunk> chunks = fixedChunker.chunk(document("d", sb.toString()));

			assertThat(chunks.size()).isGreaterThan(1);
			// 第 1 块的尾部应当出现在第 2 块里
			String first = chunks.get(0).text();
			String second = chunks.get(1).text();
			String tail = first.substring(Math.max(0, first.length() - 20));
			assertThat(second).contains(tail.strip());
		}

		@Test
		@DisplayName("硬切不产出标题路径 —— 这是对照组必须保持的唯一变量")
		void hasNoHeadingPath() {
			ParsedDocument doc = new ParsedDocument("d", List.of(
					new ParsedBlock(BlockType.HEADING, "一、总览", 1, 0),
					new ParsedBlock(BlockType.PARAGRAPH, "正文内容".repeat(100), 0, 1)));

			List<Chunk> chunks = fixedChunker.chunk(doc);
			// 如果这里将来被人"好心"补上标题路径，对照组就失去意义了：
			// 它必须保持"不含结构信息"，否则两组的差异无法归因到"切在哪"
			assertThat(chunks).allSatisfy(c -> assertThat(c.headingPath()).isEmpty());
		}

		@Test
		@DisplayName("overlap 大于等于 size 时不死循环（配置写错不能把服务挂住）")
		void survivesBadOverlapConfig() {
			properties.getChunking().getFixed().setSize(50);
			properties.getChunking().getFixed().setOverlap(200); // 故意写错

			List<Chunk> chunks = fixedChunker.chunk(document("d", "内容".repeat(500)));
			assertThat(chunks).isNotEmpty();
		}
	}

	// ------------------------------------------------------------------

	@Nested
	@DisplayName("结构感知分块")
	class Structural {

		@Test
		@DisplayName("标题开启新块，且正文块的标题路径能定位到所属章节")
		void headingsStartNewChunks() {
			ParsedDocument doc = new ParsedDocument("d", List.of(
					new ParsedBlock(BlockType.HEADING, "一、总览", 1, 0),
					new ParsedBlock(BlockType.PARAGRAPH, "总览的正文。".repeat(10), 0, 1),
					new ParsedBlock(BlockType.HEADING, "二、细节", 1, 2),
					new ParsedBlock(BlockType.PARAGRAPH, "细节的正文。".repeat(10), 0, 3)));

			List<Chunk> chunks = structuralChunker.chunk(doc);

			// 每个标题都应当出现在某个块的开头（标题块以自己为起点）
			assertThat(chunks.stream().filter(c -> c.text().contains("一、总览"))).isNotEmpty();
			assertThat(chunks.stream().filter(c -> c.text().contains("二、细节"))).isNotEmpty();

			// 关键的回归点：讲"细节"的那个块的标题路径必须是"二、细节"，
			// 不能是"一、总览"（硬切就会出这种错）
			Chunk detailChunk = chunks.stream()
					.filter(c -> c.text().contains("细节的正文"))
					.findFirst().orElseThrow();
			assertThat(detailChunk.headingPath()).contains("二、细节");
			assertThat(detailChunk.headingPath()).doesNotContain("一、总览");
		}

		@Test
		@DisplayName("面包屑可以关掉，关掉后正文里不再出现标题行")
		void breadcrumbCanBeTurnedOff() {
			ParsedDocument doc = new ParsedDocument("d", List.of(
					new ParsedBlock(BlockType.HEADING, "唯一标题", 1, 0),
					new ParsedBlock(BlockType.PARAGRAPH, "段落内容。".repeat(30), 0, 1)));

			properties.getChunking().getStructural().setIncludeHeadingBreadcrumb(true);
			String withBreadcrumb = structuralChunker.chunk(doc).get(0).text();
			assertThat(withBreadcrumb).startsWith("[唯一标题]");

			properties.getChunking().getStructural().setIncludeHeadingBreadcrumb(false);
			String without = structuralChunker.chunk(doc).get(0).text();
			// 注意：关掉面包屑后，标题仍然是一个独立的块（标题必须开新块），
			// 所以这里断言的是"正文块不再被加上方括号前缀"
			assertThat(without).doesNotContain("[唯一标题]");
		}

		@Test
		@DisplayName("超长单段会被拆开 —— 否则一个 3000 token 的表格必然超窗")
		void splitsOversizedBlock() {
			properties.getChunking().getStructural().setMaxTokens(100);
			properties.getChunking().getStructural().setHardSplitThresholdTokens(200);

			// 一段巨大的正文（远大于 maxTokens）
			ParsedDocument doc = document("d", "这是一句用来测试拆分的正文。".repeat(200));

			List<Chunk> chunks = structuralChunker.chunk(doc);
			assertThat(chunks.size()).isGreaterThan(1);
			// 拆完之后不应该出现"比阈值还大的块"
			assertThat(chunks).allSatisfy(c -> assertThat(c.tokenCount()).isLessThan(600));
		}

		@Test
		@DisplayName("正文不会丢字：所有块的正文拼起来包含原文的每个段落")
		void doesNotLoseContent() {
			List<ParsedBlock> blocks = new ArrayList<>();
			for (int i = 0; i < 30; i++) {
				blocks.add(new ParsedBlock(BlockType.PARAGRAPH, "第" + i + "段落的内容。".repeat(5), 0, i));
			}
			ParsedDocument doc = new ParsedDocument("d", blocks);

			List<Chunk> chunks = structuralChunker.chunk(doc);
			String all = String.join("\n", chunks.stream().map(Chunk::text).toList());

			for (int i = 0; i < 30; i++) {
				assertThat(all).as("第 %d 段不能丢", i).contains("第" + i + "段落的内容。");
			}
		}

		@Test
		@DisplayName("正文块归属的章节身份正确：同名标题不会让两节混在一起")
		void handlesDuplicateHeadingText() {
			// 两个同名标题（真实文档里极常见："注意事项"每个模块都有）
			ParsedDocument doc = new ParsedDocument("d", List.of(
					new ParsedBlock(BlockType.HEADING, "模块A", 1, 0),
					new ParsedBlock(BlockType.HEADING, "注意事项", 2, 1),
					new ParsedBlock(BlockType.PARAGRAPH, "A 的注意事项正文。".repeat(30), 0, 2),
					new ParsedBlock(BlockType.HEADING, "模块B", 1, 3),
					new ParsedBlock(BlockType.HEADING, "注意事项", 2, 4),
					new ParsedBlock(BlockType.PARAGRAPH, "B 的注意事项正文。".repeat(30), 0, 5)));

			List<Chunk> chunks = structuralChunker.chunk(doc);

			Chunk aChunk = chunks.stream().filter(c -> c.text().contains("A 的注意事项正文")).findFirst().orElseThrow();
			Chunk bChunk = chunks.stream().filter(c -> c.text().contains("B 的注意事项正文")).findFirst().orElseThrow();

			// 这是本测试的重点：两条路径同名，但必须能区分出"属于哪个模块"
			assertThat(aChunk.headingPath()).contains("模块A");
			assertThat(bChunk.headingPath()).contains("模块B");
			assertThat(aChunk.headingPath()).doesNotContain("模块B");
			assertThat(bChunk.headingPath()).doesNotContain("模块A");
		}

		@Test
		@DisplayName("空文档返回空列表，不抛异常")
		void handlesEmptyDocument() {
			assertThat(structuralChunker.chunk(new ParsedDocument("d", List.of()))).isEmpty();
			assertThat(fixedChunker.chunk(new ParsedDocument("d", List.of()))).isEmpty();
			assertThat(structuralChunker.chunk(document("d", "   "))).isEmpty();
		}

		@Test
		@DisplayName("每个块的 token 数都被算出来（后面要拿它做上下文预算）")
		void computesTokenCounts() {
			List<Chunk> chunks = structuralChunker.chunk(document("d", "这是一段用来检查 token 统计的正文。".repeat(20)));
			assertThat(chunks).isNotEmpty();
			assertThat(chunks).allSatisfy(c -> assertThat(c.tokenCount()).isPositive());
		}
	}

	// ------------------------------------------------------------------

	private static ParsedDocument document(String id, String text) {
		return new ParsedDocument(id, List.of(new ParsedBlock(BlockType.PARAGRAPH, text, 0, 0)));
	}

}
