package ai.agent.service.impl.rag.chunk;

import ai.agent.service.rag.chunk.Chunk;
import ai.agent.service.rag.chunk.DocumentChunker;
import ai.agent.config.rag.RagProperties;
import ai.agent.service.rag.parse.ParsedBlock;
import ai.agent.service.rag.parse.ParsedDocument;
import ai.agent.service.token.TokenEstimator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定字数硬切 —— <b>对照组</b>，即路线文档明确警告的"别按固定字数硬切"的那种做法。
 *
 * <h2>为什么要在生产代码里留一个"错误做法"</h2>
 * 因为阶段二的验收口径是 <b>"能用数字说明换了策略提升多少"</b>，
 * 而"提升"必须有一个基准。没有这个类，"语义分块更好"就只是一句信仰；
 * 有了它，结论才能写成"Recall@5 从 0.62 提到 0.88"这种可被追问、可被复现的话。
 *
 * <p>更实际的一点：<b>大部分人写的 RAG 就是这个类</b>。
 * 拿它当基准量出来的差距，才是"我的做法 vs 常见做法"的真实差距；
 * 如果故意把基准写得更差（比如去掉 overlap）来赢得好看，结论一文不值。
 * 所以这里<b>保留了 overlap</b>——重叠不是语义分块的专利，
 * 它是"防止边界切断语义"的通用手段，两组都有，差异才能干净地归因到"切点选在哪"。
 *
 * <h2>它到底哪里错了</h2>
 * 错在<b>切点与语义无关</b>。它只知道"到第 500 个字了"，不知道
 * 第 500 个字可能落在一张表的第 3 行、一段代码的中间、或一个标题和它的正文之间。
 * 典型伤害有三种，都能在评测里看到：
 * <ol>
 *   <li><b>标题与正文分离</b>：标题留在上一块末尾，正文进了下一块 ——
 *       下一块从此不知道自己在讲什么；</li>
 *   <li><b>表格被拦腰截断</b>：两个块各拿半张表，谁也答不出"某项的值是多少"；</li>
 *   <li><b>块内主题混杂</b>：一个块里塞着上一节的结尾和下一节的开头，
 *       向量被平均成一个"什么都不是"的方向，谁都召回不准。</li>
 * </ol>
 */
@Component
public class FixedSizeChunker implements DocumentChunker {

	/** 评测报告里的列名。 */
	public static final String NAME = "fixed-size";

	private final RagProperties properties;

	private final TokenEstimator estimator;

	public FixedSizeChunker(RagProperties properties, TokenEstimator estimator) {
		this.properties = properties;
		this.estimator = estimator;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public List<Chunk> chunk(ParsedDocument document) {
		RagProperties.Chunking.Fixed cfg = properties.getChunking().getFixed();
		return chunk(document, cfg.getSize(), cfg.getOverlap());
	}

	/**
	 * 用<b>显式尺寸</b>切分，绕过配置。
	 *
	 * <h2>为什么对照组必须能改大小（这不是为了方便，是实验的前提）</h2>
	 * 原始对照（500 字硬切 vs 结构分块）量出的差异里混着一个没被排除的因素：
	 * <b>两组的块不一样大</b>（实测 234.8 vs 200.9 token）。而本项目的相关性判定是
	 * "检索到的块有没有覆盖答案所在章节" —— <b>块越大越容易碰到答案</b>。
	 * 于是"结构分块 Recall 更低"这句话无法归因：是它切得不好，
	 * 还是它只是块更小？（台账把这条列为阶段二最高优先级的待办）
	 *
	 * <p>排除它的唯一办法就是<b>把对照组的块调到与实验组一样大</b>。
	 * 不能改配置来做这件事：配置是全局的，改一次就得重启、而且会同时影响
	 * 其它实验结果的可复现性。所以尺寸必须是参数。
	 *
	 * @param size    每块的字符数
	 * @param overlap 相邻块重叠的字符数
	 */
	public List<Chunk> chunk(ParsedDocument document, int size, int overlap) {
		size = Math.max(1, size);
		// 重叠必须小于块大小，否则步进为 0 或负数 → 死循环
		overlap = Math.min(Math.max(0, overlap), size - 1);
		int step = size - overlap;

		List<ParsedBlock> blocks = document.blocks();
		String text = normalize(document, blocks);
		if (text.isEmpty()) {
			return List.of();
		}
		// 每个解析块在拼好的文本里的字符起点，用于把"字符区间"反查成"解析块区间"
		int[] blockStarts = blockCharOffsets(blocks);

		// 【刻意把结构信息丢掉】硬切的定义就是"只看字符位置"。
		// 这里只把标题当成普通的短行 —— 这正是它"标题与正文分离"的根因
		List<Chunk> chunks = new ArrayList<>();
		int index = 0;
		for (int start = 0; start < text.length(); start += step) {
			int end = Math.min(text.length(), start + size);
			String piece = text.substring(start, end).strip();
			if (piece.isEmpty()) {
				// 整段是空白（常见于跨表格/空行边界）：不产出空块。
				// 空块会进索引、占位置、永远召回不到，只会污染召回率的分母
				if (end >= text.length()) {
					break;
				}
				continue;
			}

			// 【诚实地标出它不知道自己在哪】硬切没有标题路径可给，一律空列表。
			// 不要试图事后补一个"最近的标题" —— 那样就变成结构分块了，
			// 对照组必须保持"不含结构信息"这个唯一变量
			//
			// 但**块覆盖了哪些解析块**必须记下来：那不是"结构信息"，
			// 而是"这段文字在原文里的位置"，两者性质完全不同。
			// 评测正是靠它来公平判定的（见 ai.agent.service.rag.eval.SectionLocator）——
			// 不记的话，硬切会对所有用例恒判为"不相关"，Recall 恒为 0，
			// 看起来像"检索完全不行"，其实是判定无从下手
			chunks.add(new Chunk(document.documentId() + "#" + index, document.documentId(), index, piece,
					List.of(), blockAt(blockStarts, start), blockAt(blockStarts, Math.max(start, end - 1)),
					estimator.estimateMessage(piece), piece.length()));
			index++;
			if (end >= text.length()) {
				break;
			}
		}
		return chunks;
	}

	/**
	 * 把块拼成一整条文本。
	 *
	 * <p>用两个换行连接：保留"段落"这个最粗的边界信息（哪怕是硬切，
	 * 段落之间断开也总比连成一坨强），让对照组不至于离谱到不具参考性。
	 * 这也是它唯一用到的结构信息。
	 */
	private static String normalize(ParsedDocument document, List<ParsedBlock> blocks) {
		return String.join("\n\n", blocks.stream().map(b -> b.text().strip()).filter(s -> !s.isEmpty()).toList());
	}

	/**
	 * 每个解析块在拼好文本里的起始字符位置。
	 *
	 * <p>必须与 {@link #normalize} 的拼接方式严格一致（同样的过滤、同样的
	 * {@code "\n\n"} 分隔），否则反查出来的块区间会整体偏移 ——
	 * 而这种偏移不会报错，只会让评测的命中判定偶尔判错，
	 * 表现为"召回率莫名其妙低了一点"。
	 */
	private static int[] blockCharOffsets(List<ParsedBlock> blocks) {
		int[] starts = new int[blocks.size()];
		int cursor = 0;
		boolean first = true;
		for (int i = 0; i < blocks.size(); i++) {
			String piece = blocks.get(i).text().strip();
			starts[i] = cursor;
			if (piece.isEmpty()) {
				continue;
			}
			if (!first) {
				cursor += 2; // "\n\n"
			}
			cursor += piece.length();
			first = false;
		}
		return starts;
	}

	/** 字符位置 → 它落在第几个解析块（取最后一个起点不超过 pos 的块）。 */
	private static int blockAt(int[] blockStarts, int pos) {
		int found = 0;
		for (int i = 0; i < blockStarts.length; i++) {
			if (blockStarts[i] <= pos) {
				found = i;
			}
			else {
				break;
			}
		}
		return found;
	}

}
