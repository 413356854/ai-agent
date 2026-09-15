package ai.agent.rag.chunk;

import ai.agent.config.ChatCallProperties;
import ai.agent.rag.chunk.impl.StructuralChunker;
import ai.agent.rag.config.RagProperties;
import ai.agent.rag.parse.MarkdownDocumentParser;
import ai.agent.rag.parse.ParsedBlock;
import ai.agent.rag.parse.ParsedDocument;
import ai.agent.token.impl.CharacterClassTokenEstimator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上限不变式：<b>结构分块产出的块不得超过 maxTokens</b>。
 *
 * <h2>为什么这条断言值得单独一个测试类</h2>
 * "块不超过上限"是结构分块最硬的约束 —— 它直接对应"会不会超窗"。
 * 而它被破坏过两次，两次都不报错：
 * <ol>
 *   <li>合并小块的判断用的是"片段 token 之和"，漏掉了面包屑，
 *       于是"看起来没超"的块实际超了（实测出现 802 token 的块，上限 400）；</li>
 *   <li>重叠尾巴被带进新块时没有计入预算。</li>
 * </ol>
 * 两次都是<b>在真实语料上才暴露</b>的：合成语料段落太规整，撞不到这些路径。
 * 所以这里对真实语料做全量检查，而不是只测一两个手工构造的例子。
 *
 * <p>同时它也顺带钉住另一个不变式：<b>正文不能丢</b>。
 * 为了压住上限而截断内容，是把"超窗"换成了"静默丢数据"——那是更糟的交易。
 */
class ChunkSizeInvariantTest {

	private final CharacterClassTokenEstimator estimator =
			new CharacterClassTokenEstimator(new ChatCallProperties());

	private final RagProperties properties = new RagProperties();

	private final StructuralChunker chunker = new StructuralChunker(properties, estimator);

	private List<ParsedDocument> corpus() throws Exception {
		MarkdownDocumentParser parser = new MarkdownDocumentParser();
		List<ParsedDocument> documents = new ArrayList<>();
		for (String name : new String[] { "ledger-progress.md", "roadmap-java-to-agent.md",
				"knowledge-points-ranked.md", "materials-checklist.md" }) {
			Path file = Path.of("src/main/resources/rag/corpus/" + name);
			documents.add(parser.parse(name, Files.readAllBytes(file), name));
		}
		return documents;
	}

	@Test
	@DisplayName("真实语料上没有任何块超过 maxTokens（含面包屑）")
	void noChunkExceedsMaxTokens() throws Exception {
		int max = properties.getChunking().getStructural().getMaxTokens();
		List<String> violations = new ArrayList<>();

		for (ParsedDocument document : corpus()) {
			for (Chunk chunk : chunker.chunk(document)) {
				if (chunk.tokenCount() > max) {
					violations.add("%s#%d tokens=%d spans=[%d,%d] text=%s".formatted(
							document.documentId(), chunk.index(), chunk.tokenCount(),
							chunk.startBlock(), chunk.endBlock(),
							chunk.text().replace("\n", "\\n").substring(0, Math.min(80, chunk.text().length()))));
				}
			}
		}

		assertThat(violations)
				.as("以下块超过了 maxTokens=%d；超上限意味着检索阶段会撑爆上下文窗口", max)
				.isEmpty();
	}

	@Test
	@DisplayName("重叠受 maxDuplicationRatio 约束（相邻块重复计入的内容不能失控）")
	void duplicationRatioWithinLimit() throws Exception {
		double maxRatio = properties.getChunking().getStructural().getMaxDuplicationRatio();

		for (ParsedDocument document : corpus()) {
			List<Chunk> chunks = chunker.chunk(document);

			// 量的是"实际被重复计入的内容"：把块的覆盖区间按块展开，
			// 统计每个解析块被多少个块覆盖过，超出 1 次的部分就是重叠带来的重复。
			// 这个量只跟重叠参数有关 —— 详见 StructuralChunker#overlapRatio 里
			// 关于"指标混进无关因素"的两次返工记录
			java.util.Map<Integer, Integer> covered = new java.util.HashMap<>();
			for (Chunk chunk : chunks) {
				if (chunk.startBlock() < 0) {
					continue;
				}
				for (int b = chunk.startBlock(); b <= chunk.endBlock(); b++) {
					covered.merge(b, 1, Integer::sum);
				}
			}

			// 用"被重复覆盖的解析块数 / 总解析块数"当重叠率：
			// 这个比值只受重叠影响（面包屑重复、估算开销都不参与）
			long duplicatedBlocks = covered.values().stream().filter(count -> count > 1).count();
			double ratio = duplicatedBlocks / (double) Math.max(1, document.blockCount());

			assertThat(ratio)
					.as("%s 的重叠率 %.4f 超过上限 %.2f", document.documentId(), ratio, maxRatio)
					.isLessThanOrEqualTo(maxRatio);
		}
	}

	@Test
	@DisplayName("正文不丢：每个解析块的文本都能在某个块里找到")
	void noContentIsLost() throws Exception {
		for (ParsedDocument document : corpus()) {
			List<Chunk> chunks = chunker.chunk(document);
			String all = String.join("\n", chunks.stream().map(Chunk::text).toList());

			List<String> missing = new ArrayList<>();
			for (ParsedBlock block : document.blocks()) {
				// 用块的前 30 个字符做存在性检查：整段比对会因为空白归一化而误报
				String probe = block.text().replace("\n", " ").strip();
				probe = probe.length() <= 30 ? probe : probe.substring(0, 30);
				if (!probe.isEmpty() && !all.replace("\n", " ").contains(probe)) {
					missing.add("%s [%s] %s".formatted(document.documentId(), block.type(), probe));
				}
			}

			assertThat(missing)
					.as("以下内容在切分后找不到了 —— 这是静默的数据损失，比超窗更糟")
					.isEmpty();
		}
	}

}
