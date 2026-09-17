package ai.agent.service.impl.rag.chunk;

import ai.agent.service.rag.chunk.Chunk;
import ai.agent.service.rag.chunk.DocumentChunker;
import ai.agent.config.rag.RagProperties;
import ai.agent.service.rag.parse.BlockType;
import ai.agent.service.rag.parse.ParsedBlock;
import ai.agent.service.rag.parse.ParsedDocument;
import ai.agent.service.token.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构感知分块：按<b>标题层级 + 段落边界</b>切，带 overlap 与标题面包屑。
 *
 * <h2>切点从哪来</h2>
 * 全部是"原文里本来就存在的边界"，不需要任何模型参与：
 * <ol>
 *   <li><b>标题</b> —— 最强的切点。标题变化 = 主题变化，作者已经替你划好了；
 *       而且标题永远属于它后面那段内容，所以标题必须<b>开始</b>一个新块，
 *       绝不能留在上一块末尾（这正是硬切最典型的伤害）。</li>
 *   <li><b>段落</b> —— 基本切分单位。贪心地往当前块里装段落，装到接近上限为止，
 *       所以块边界永远落在段落之间，不会切进句子中间。</li>
 *   <li><b>不可切块</b>（表格 / 代码）—— 尽量整块保留。
 *       一张表被切成两半时两个块都拿不到完整表头，等于两个块都废了。</li>
 * </ol>
 *
 * <h2>三个机制，缺一个就退化</h2>
 * <ul>
 *   <li><b>上限</b> {@code maxTokens} —— 防超窗，同时决定"进上下文的粒度"。</li>
 *   <li><b>下限</b> {@code minTokens} —— 防碎片。<b>这条最容易被忽略</b>：
 *       一个小节只有一句话时，单独成块的关键词密度太低，词法检索与向量检索都吃不饱，
 *       结果就是这个块永远召回不到 —— 而它的内容可能是文档里唯一的答案。
 *       所以要把同节内过小的相邻块合并（{@link #mergeUndersized}）。</li>
 *   <li><b>重叠</b> {@code overlapTokens} —— 防边界切断。
 *       <b>只在同一个章节内重叠</b>：跨章节重叠等于把上一节的内容注射进下一节的块里，
 *       那是引入噪音而不是保留上下文。</li>
 * </ul>
 *
 * <h2>标题面包屑：一个"有代价的收益"</h2>
 * 每个块前面拼上它所属的标题路径（例如 {@code [四、踩坑知识库 > 第 12 条]}）。
 * 收益：块正文里因此出现了标题中的词，而<b>用户提问常常直接用标题里的词</b>
 * （问"虚拟线程的坑"时，正文里可能一次都没写"虚拟线程"这四个字）。
 * 代价：每块多占几十 token。
 *
 * <p>所以它做成开关，并且做到<b>不占 {@code maxTokens} 额度</b> ——
 * 否则开开关会让实际正文变小，"开面包屑"和"关面包屑"两组就混进了
 * "块大小也变了"这个额外变量，数字就解释不清了。这类"变量没控制住"
 * 是实验设计里最常见的错误，台账里已经踩过一次（踩坑库 #35）。
 */
@Component
public class StructuralChunker implements DocumentChunker {

	/** 评测报告里的列名。 */
	public static final String NAME = "structural";

	private static final Logger log = LoggerFactory.getLogger(StructuralChunker.class);

	private final RagProperties properties;

	private final TokenEstimator estimator;

	public StructuralChunker(RagProperties properties, TokenEstimator estimator) {
		this.properties = properties;
		this.estimator = estimator;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public List<Chunk> chunk(ParsedDocument document) {
		return chunk(document, properties.getChunking().getStructural());
	}

	/**
	 * 用<b>另一份结构分块配置</b>切分，绕过全局配置。
	 *
	 * <h2>为什么需要它</h2>
	 * 块大小对齐实验里有两种必须能改的配置，而它们都<b>不该改全局配置</b>
	 * （改了要重启、还会同时污染其它实验结果的可复现性）：
	 * <ul>
	 *   <li><b>max-tokens</b>：找出与对照组对齐的那一档；</li>
	 *   <li><b>面包屑开关</b>：结构分块的 200.9 token 里，
	 *       <b>正文其实只有约 163</b>（差值就是每块都拼一遍的标题路径）。
	 *       所以"块大小对齐"要把面包屑也一起按住 —— 否则对齐的是"渲染后的大小"，
	 *       正文仍然比对照组小 20%，混杂因素只是换了个地方待着。</li>
	 * </ul>
	 *
	 * <p>配置对象由调用方构造并传入，所以这个方法是<b>无副作用</b>的：
	 * 不会回写任何共享状态（早先的探针脚本用过"改配置 → 跑 → 改回来"的写法，
	 * 那种写法在并发下会互相串味，而且异常路径上恢复不了）。
	 */
	public List<Chunk> chunk(ParsedDocument document, RagProperties.Chunking.Structural cfg) {
		if (document.blocks().isEmpty()) {
			return List.of();
		}

		List<Piece> pieces = splitOversized(toPieces(document, cfg), cfg);

		// 【重叠预算：装填 → 量重复率 → 超了就减半重来】
		// 为什么不在装填逻辑里"提前算准"该给多少重叠：重复量取决于文档结构
		// （段落长短、标题密度、单段是否超限被拆），事先估不准。
		// 与其塞一堆估算，不如让装填保持简单，装完量一次**真实**重复率。
		// 实测第一版重复率达到 52.63%（对照组只有 13.70%），
		// 根因正是"块只剩一点空位、却仍把上一组整个尾部搬过来"。
		int overlapTarget = Math.max(0, cfg.getOverlapTokens());
		double maxRatio = Math.max(0, cfg.getMaxDuplicationRatio());
		int rawChars = document.plainText().length();
		List<Chunk> chunks = List.of();

		// 循环必然终止：每轮 overlapTarget 减半，最多 5 轮就归零
		for (int round = 0; round < 5; round++) {
			List<Group> groups = mergeUndersized(document, pack(pieces, cfg, overlapTarget), cfg);
			chunks = enforceMaxTokens(toChunks(document, groups, cfg), cfg);

			double ratio = overlapRatio(groups, rawChars);
			if (ratio <= maxRatio || overlapTarget == 0) {
				if (round > 0) {
					log.debug("结构分块重叠率收敛 documentId={} 轮次={} 最终重叠={} 重叠率={}",
							document.documentId(), round, overlapTarget, round4(ratio));
				}
				break;
			}
			log.debug("重叠率 {} 超过上限 {}，把重叠目标从 {} 降为 {} 重试 documentId={}",
					round4(ratio), maxRatio, overlapTarget, overlapTarget / 2, document.documentId());
			overlapTarget /= 2;
		}

		return chunks;
	}

	/**
	 * <b>重叠率</b>：相邻块之间重复计入的字符数 / 原文字符数。
	 *
	 * <h2>这个指标返工了两次，两次都是"指标混进了别的东西"</h2>
	 * <ol>
	 *   <li><b>第一次用 token 总量算</b>：分母分子里混进了 token 估算器给
	 *       每条消息的固定开销（块越多这项越大），于是不管怎么降重叠，
	 *       数字都卡在 50% 下不来，阈值根本调不准。</li>
	 *   <li><b>第二次改用字符总量算</b>：估算开销的问题没了，但引入了另一个
	 *       无关因素 —— <b>面包屑</b>。每个块都要拼一遍标题路径，
	 *       而标题路径里那几十个字<b>每块都重复一次</b>。
	 *       实测重叠降到 0 之后，重复率仍有 43.85%，全部来自面包屑。</li>
	 * </ol>
	 * 两次犯的是同一个错：<b>拿一个"总量相对原文"的比值去做单因素控制</b>。
	 * 这个比值同时受重叠、块数、面包屑长度影响，而我只能调其中一个。
	 *
	 * <p>所以现在直接量"重叠"本身：把被携带进多个组的片段字符数除以原文字符数。
	 * 它只跟重叠参数有关，减半就必然下降、归零就必然为 0 —— 可控、可解释。
	 *
	 * <p>（面包屑的重复不是浪费：它是"让块单独被召回时知道自己属于哪一节"的代价，
	 * 属于有意的设计成本。它由报告里的 avgChunkTokens 体现，不该算作重叠。）
	 */
	private static double overlapRatio(List<Group> groups, int rawChars) {
		if (rawChars <= 0) {
			return 0;
		}
		// 统计每个片段出现在多少个组里；出现 n 次就有 (n-1) 份是重复计入的
		java.util.Map<Piece, Integer> occurrences = new java.util.HashMap<>();
		for (Group group : groups) {
			for (Piece piece : group.pieces()) {
				occurrences.merge(piece, 1, Integer::sum);
			}
		}
		long duplicated = 0;
		for (java.util.Map.Entry<Piece, Integer> entry : occurrences.entrySet()) {
			duplicated += (long) entry.getKey().text().length() * (entry.getValue() - 1);
		}
		return duplicated / (double) rawChars;
	}

	/**
	 * 最后一道闸门：<b>保证没有任何块超过 maxTokens</b>。
	 *
	 * <h2>为什么预算已经在每一层都算过了，还需要这一道</h2>
	 * 因为"块不超过上限"是一条<b>硬约束</b>（直接对应会不会撑爆上下文窗口），
	 * 而它被破坏的路径出奇地多 —— 本项目实测踩到三次：
	 * <ol>
	 *   <li>合并小块的判断漏算面包屑 → 出现 802 token 的块（上限 400）；</li>
	 *   <li>拆分超长片段时重新估算、又把面包屑丢了 → 残余 410 / 411 的块；</li>
	 *   <li>用 {@code block.order()}（丢空块后的序号）去查标题路径，
	 *       与实际渲染用的下标不是同一个坐标系 → 预算与渲染对不上。</li>
	 * </ol>
	 * 三次都不报错，全都只在真实语料上才暴露。这说明<b>指望"每一层都算对"
	 * 来保证硬约束，是靠不住的</b>：只要有一处路径没考虑到，约束就失效，
	 * 而且要等到检索阶段才以"上下文被撑爆"的形式暴露。
	 *
	 * <p>所以这里加一道与前面所有逻辑<b>无关</b>的兜底：按最终正文重新量一次，
	 * 超了就按句子边界拆开。它的价值不在于"更聪明"，而在于
	 * <b>无论上游怎么改，这条不变式都成立</b>。
	 *
	 * <p>这也符合工程上的一般做法：关键约束要有一处<b>独立于所有优化路径</b>的
	 * 校验点。上游的优化可以随便改，兜底始终在那里 ——
	 * 而且这里的拆分会保留面包屑、不丢正文（丢内容比超窗更糟）。
	 */
	private List<Chunk> enforceMaxTokens(List<Chunk> chunks, RagProperties.Chunking.Structural cfg) {
		int max = Math.max(1, cfg.getMaxTokens());
		List<Chunk> result = new ArrayList<>();
		int index = 0;

		for (Chunk chunk : chunks) {
			if (chunk.tokenCount() <= max) {
				result.add(reindex(chunk, index++));
				continue;
			}
			// 超限：按句拆开。
			//
			// 【这里踩过一次，而且是个很典型的"兜底逻辑把主逻辑的活干了"的坑】
			// 第一版用 max/2 当拆分的字符预算，本意是"留点余量更安全"。
			// 结果：一个 450 token 的块被硬拆成两个 ~150 token 的块 ——
			// 它只需要吐出 50 token 就够了，却被砍掉三分之一。
			// 实测证据：max-tokens=400 时块数 235，调到 800 时反而只有 146
			// （上限翻倍、块数应当更少，但差这么多说明兜底被频繁误触发）。
			// 块又小又碎直接伤害召回质量 —— 一个"为了保证安全"的兜底
			// 反过来成了质量下降的主因。
			//
			// 正确做法：预算应当**贴着上限**，只切掉超出的那部分
			// （留 5% 余量给估算误差）。兜底要做的是"刚好压回线内"，
			// 不是"顺手把块切小一半"。
			int target = Math.max(1, (int) (max * 0.95));
			List<String> parts = packToLimit(
					splitBySentence(chunk.text(), charBudgetFor(target)),
					charBudgetFor(target));

			// 兜底拆出来的每一段都保留面包屑：块被拆开之后，
			// 后半段如果丢了面包屑，就变成一个"不知道自己属于哪一节"的孤儿块
			for (String part : parts) {
				String text = part.strip();
				if (text.isEmpty()) {
					continue;
				}
				result.add(new Chunk(chunk.documentId() + "#" + index, chunk.documentId(), index, text,
						chunk.headingPath(), chunk.startBlock(), chunk.endBlock(),
						estimator.estimateMessage(text), text.length()));
				index++;
			}
		}
		return result;
	}

	/** 重新编号（兜底拆分会让块数变多，序号必须重新生成连续值）。 */
	private static Chunk reindex(Chunk chunk, int index) {
		if (chunk.index() == index) {
			return chunk;
		}
		return new Chunk(chunk.documentId() + "#" + index, chunk.documentId(), index, chunk.text(),
				chunk.headingPath(), chunk.startBlock(), chunk.endBlock(),
				chunk.tokenCount(), chunk.charCount());
	}

	private static double round4(double value) {
		return Math.round(value * 10000.0) / 10000.0;
	}

	// ==================================================================
	//  内部模型
	// ==================================================================

	/**
	 * 待拼装的最小片段。
	 *
	 * @param text          正文
	 * @param tokens        估算 token 数
	 * @param startBlock    在解析文档中的起始块下标（拆分出来的片段与原文块共享）
	 * @param endBlock      结束块下标
	 * @param startsSection 是否由标题开头 —— <b>标题必须开启新块</b>，这是结构分块的核心不变式
	 */
	private record Piece(String text, int tokens, int startBlock, int endBlock, boolean startsSection) {
	}

	/**
	 * 已拼装好的一个组（= 未来的一个 Chunk）。
	 *
	 * <p><b>为什么要有这个类型，而不是直接用 {@code List<Piece>}</b>：
	 * 组的"总 token 数"和"覆盖的块区间"是两个<b>派生量</b>，
	 * 一旦散落在各处手工维护（add 时加 token、改区间时取 min/max），
	 * 迟早会出现某条路径忘了更新 —— 而这类 bug 不报错，只让 token 账目和
	 * 溯源区间悄悄失真，最后表现为"评测数字对不上但找不出原因"。
	 * 收进一个不可变记录、由构造器一次算清，是让这类错误无法表达。
	 */
	private record Group(List<Piece> pieces, int tokens, int startBlock, int endBlock) {

		/**
		 * 由片段列表算出组的 token 与覆盖区间。
		 *
		 * <p><b>这里踩过一个代价很大的坑，必须写下来</b>：第一版把
		 * {@code start} 初始化成 {@code 0}（想着"越小越好，用 min 收敛"），
		 * 但块下标本来就是从 0 开始的 —— 于是 {@code Math.min(0, 任何值)} 恒等于 0，
		 * <b>每个组的 startBlock 都变成 0</b>。
		 *
		 * <p>后果不是"区间显示得难看"，而是<b>整条链路静默失效</b>：
		 * 标题路径是按 {@code headingPathOf(startBlock)} 算的，
		 * startBlock 全是 0 → 每个块的面包屑都退化成文档的 H1
		 * （实测 86 个块的 path 全是一个元素）→
		 * ① 「按标题层级分块」这个核心能力实际上是空的，实验组和对照组没差别；
		 * ② 评测里的相关性判定走的是标题路径，于是 Recall 恒为 0 ——
		 *    看起来像"检索完全不行"，而真正坏掉的是分块器的元数据。
		 *
		 * <p>教训与台账踩坑库 #34 是同一族：<b>哨兵值/初值一旦与真实取值空间重叠，
		 * 聚合函数就会静默给出常数</b>（那里是"—1 既表示没有又表示两个不同章节"，
		 * 这里是"0 既表示起点又表示未知"）。
		 * 正确写法是用"第一个元素"当基准，而不是依赖一个恰好不在取值空间里的魔数。
		 */
		static Group of(List<Piece> pieces) {
			if (pieces.isEmpty()) {
				return new Group(List.of(), 0, 0, -1);
			}
			int tokens = 0;
			int start = pieces.get(0).startBlock();
			int end = pieces.get(0).endBlock();
			for (Piece piece : pieces) {
				tokens += piece.tokens();
				start = Math.min(start, piece.startBlock());
				end = Math.max(end, piece.endBlock());
			}
			return new Group(List.copyOf(pieces), tokens, start, end);
		}

		Group concat(Group other) {
			List<Piece> combined = new ArrayList<>(this.pieces);
			combined.addAll(other.pieces);
			return Group.of(combined);
		}

		String text() {
			StringBuilder sb = new StringBuilder();
			for (Piece piece : pieces) {
				if (sb.length() > 0) {
					sb.append('\n');
				}
				sb.append(piece.text());
			}
			return sb.toString();
		}
	}

	// ==================================================================
	//  第一步：解析块 → 片段
	// ==================================================================

	/**
	 * 解析块 → 片段。
	 *
	 * <p><b>这里的 {@code tokens} 是"渲染后"的 token 数，含该块会带上的标题面包屑。</b>
	 * 这一点很关键：{@code maxTokens} 约束的是<b>最终正文</b>（面包屑 + 片段文本），
	 * 因为那才是真正占窗口的东西。曾经这里存的是纯文本 token，
	 * 于是 {@code pack} 认为"刚好 400、没超"，拼上面包屑之后实际是 445 ——
	 * 实测出现过 410 / 460 / 639 的块，全都源于这一个量纲混用。
	 *
	 * <p>让 {@code maxTokens} 从一开始约束"渲染后的大小"，上限就不可能被绕过：
	 * 所有判断（断点、合并）看到的都是同一个量。
	 */
	private List<Piece> toPieces(ParsedDocument document, RagProperties.Chunking.Structural cfg) {
		List<Piece> pieces = new ArrayList<>();
		// 【必须用 list 下标，不能用 block.order()】
		// 原因：解析阶段会丢掉空块，于是 block.order() 是"丢掉空块之后的序号"，
		// 而 document.headingPathOf(i) 是按**完整块列表**的下标取标题路径的。
		// 两者在有空块时会错开，错开后算出来的面包屑与实际渲染的面包屑不是同一条 ——
		// 表现就是"预算算的是 380、实际渲染出来 410"，上限被悄悄绕过。
		// 这类"两个下标看着一样、其实不是一个坐标系"的问题不报错，只是数字对不上。
		List<ParsedBlock> blocks = document.blocks();
		for (int i = 0; i < blocks.size(); i++) {
			ParsedBlock block = blocks.get(i);
			if (block.isBlank()) {
				continue;
			}
			// 面包屑按块自身位置算：标题路径只取决于它前面出现过哪些标题，
			// 与"它会被分到哪个块"无关，所以这里可以精确算出它的开销
			int tokens = renderedTokensOf(document, i, block.text(), cfg);
			pieces.add(new Piece(block.text(), tokens, i, i, block.isHeading()));
		}
		return pieces;
	}

	/** 一段正文在加上它所属章节的面包屑之后，实际会占多少 token。 */
	private int renderedTokensOf(ParsedDocument document, int blockIndex, String body,
			RagProperties.Chunking.Structural cfg) {
		if (!cfg.isIncludeHeadingBreadcrumb()) {
			return estimator.estimateMessage(body);
		}
		List<String> path = breadcrumbOf(document, blockIndex, cfg);
		String text = path.isEmpty() ? body : "[" + String.join(" > ", path) + "]\n" + body;
		return estimator.estimateMessage(text);
	}

	/** 块下标 → 面包屑路径（按配置截断到最近若干层）。 */
	private static List<String> breadcrumbOf(ParsedDocument document, int blockIndex,
			RagProperties.Chunking.Structural cfg) {
		int depth = Math.max(1, cfg.getHeadingDepth());
		List<String> path = document.headingPathOf(blockIndex).stream()
				.filter(s -> s != null && !s.isBlank())
				.toList();
		return path.size() > depth ? path.subList(path.size() - depth, path.size()) : path;
	}

	// ==================================================================
	//  第二步：超长片段先拆开（否则一个块必然超窗）
	// ==================================================================

	/**
	 * 把超过阈值的单个片段拆成若干个能装下的片段。
	 *
	 * <p><b>为什么这一步必须有</b>：上限检查只有在"片段本身小于上限"时才有意义。
	 * 一份文档里出现一个 3000 token 的大表格是完全正常的（本项目的台账里就有），
	 * 不先拆它，"装不下就换新块"的逻辑要么陷入"新块也装不下"的死循环，
	 * 要么更糟 —— 被静默放过去，产出一个远超上限的块，
	 * 直到检索阶段才发现它塞不进上下文。
	 *
	 * <p><b>阈值这里踩过一次，而且是个很典型的"两个上限谁说了算"的坑</b>：
	 * 第一版用的是 {@code threshold = max(min, hardSplitThreshold)} 且拆完就收工，
	 * 于是 {@code hardSplitThreshold=800} 时，一个 <b>715 token 的表格块
	 * 根本不会被拆</b>（715 &lt; 800），而 {@code maxTokens} 是 400 ——
	 * 结果实测出现 715 token 的块，配置里的 400 形同虚设。
	 * <br>根因：<b>把"什么时候值得拆"和"必须拆到多小"混成了同一个数</b>。
	 * 前者可以大于 max（表示"不到这个量级就别费劲按句拆"），
	 * 后者必须 ≤ max（不然就违反了硬上限）。
	 * 现在拆的依据是 {@code min(hardSplitThreshold, max)} ——
	 * 保证"拆完的片段一定 ≤ max"，上限不再可能被绕过。
	 */
	private List<Piece> splitOversized(List<Piece> pieces, RagProperties.Chunking.Structural cfg) {
		int max = Math.max(1, cfg.getMaxTokens());
		int threshold = Math.max(1, Math.min(cfg.getHardSplitThresholdTokens(), max));

		List<Piece> result = new ArrayList<>();
		for (Piece piece : pieces) {
			if (piece.tokens() <= threshold) {
				result.add(piece);
				continue;
			}
			// 【拆出来的片段必须继续遵守"tokens = 渲染后大小"这个约定】
			// 原片段是"正文 + 面包屑"的估算，这里要先把面包屑的开销减掉，
			// 得到真正的正文字符预算，再按预算拆。
			// 不减的话，拆出来的片段只算了正文、没算面包屑，
			// 拼装阶段就会再次"以为没超" —— 实测残留的 410/411 token 的块就是这么来的。
			// 教训：**改了一个量的定义，就要把所有产生它的地方一起改**；
			// 漏掉一处不会有任何报错，只会让上限在特定路径上悄悄失效。
			int breadcrumbTokens = Math.max(0, piece.tokens() - estimator.estimateMessage(piece.text()));
			int bodyBudgetTokens = Math.max(1, max - breadcrumbTokens);
			int charBudget = charBudgetFor(bodyBudgetTokens);

			for (String part : splitText(piece.text(), charBudget)) {
				String text = part.strip();
				if (!text.isEmpty()) {
					// 拆出来的部分不再是章节开头（它是同一节的延续），也不再是标题
					int tokens = estimator.estimateMessage(text) + breadcrumbTokens;
					result.add(new Piece(text, tokens, piece.startBlock(), piece.endBlock(), false));
				}
			}
		}
		return result;
	}

	/** 按行 → 按句 → 按字符 三级降级拆分，最后把小碎行重新打包到接近上限。 */
	private List<String> splitText(String text, int maxTokens) {
		// 【为什么这里按"字符预算"而不是 token 预算来装填】
		// 因为装填发生的时候，正文还没有拼上面包屑，而 token 估算要等最后一步
		// 才知道准确值。如果这里按 token 装填到刚好 400，回头再拼上约 45 token 的
		// 面包屑，最终正文就是 445 —— 实测出现过 410 / 460 的块，正是这个原因。
		// 用一个更保守的字符预算，保证"装填 + 面包屑"仍然落在 maxTokens 以内。
		int charBudget = charBudgetFor(maxTokens);
		List<String> parts = new ArrayList<>();
		for (String line : text.split("\n")) {
			if (line.length() <= charBudget) {
				parts.add(line);
			}
			else {
				parts.addAll(splitBySentence(line, charBudget));
			}
		}
		return packToLimit(parts, charBudget);
	}

	/**
	 * 把 token 上限折算成<b>保守的</b>字符预算。
	 *
	 * <p>除以 2 而不是按实测密度（中文 0.75 token/字 → 约 1.33 字/token）折算：
	 * 那样算出来是 533 字符，仍会在"装填 + 面包屑"之后超限。
	 * 这里刻意取一个偏小的值，理由与阶段一 Token 估算的取舍完全一致 ——
	 * <b>低估的代价是"块小了一点"，高估的代价是"超窗"</b>，两者不对称。
	 *
	 * <p>面面俱到的精确解是"把面包屑也算进装填"，但那要求把面包屑提前算出来
	 * 传进拆分层，会让拆分层同时关心"结构元数据"和"长度预算"两件事。
	 * 用一个保守预算把问题夹住，代码简单得多，且不会错。
	 */
	private static int charBudgetFor(int maxTokens) {
		return Math.max(1, maxTokens / 2);
	}

	private List<String> splitBySentence(String line, int charBudget) {
		// 中英文句末标点都覆盖。用 lookbehind 切，保留标点 ——
		// 丢掉标点会让"这句话结束了"这个信息消失
		String[] sentences = line.split("(?<=[。！？；.!?;])");
		List<String> result = new ArrayList<>();
		for (String sentence : sentences) {
			if (sentence.isBlank()) {
				continue;
			}
			if (sentence.length() <= charBudget) {
				result.add(sentence);
			}
			else {
				// 最后一招：按字符切。一个没有句末标点的超长行
				// （表格里一整行几千字符、或者一段没有标点的粘贴内容）只能这样处理
				for (int i = 0; i < sentence.length(); i += charBudget) {
					result.add(sentence.substring(i, Math.min(sentence.length(), i + charBudget)));
				}
			}
		}
		return result;
	}

	/** 把相邻小段拼到不超过字符预算，避免产出大量"一行一个块"的碎片。 */
	private static List<String> packToLimit(List<String> parts, int charBudget) {
		List<String> packed = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String part : parts) {
			if (current.length() > 0 && current.length() + 1 + part.length() > charBudget) {
				packed.add(current.toString());
				current.setLength(0);
			}
			if (current.length() > 0) {
				current.append('\n');
			}
			current.append(part);
		}
		if (current.length() > 0) {
			packed.add(current.toString());
		}
		return packed;
	}

	// ==================================================================
	//  第三步：贪心拼装
	// ==================================================================

	/**
	 * 贪心拼装：段落是装填单位，标题强制开新组。
	 *
	 * <p>重叠不做成"从字符中间截一段复制过来"，而是<b>把上一组末尾的完整片段带进新组</b> ——
	 * 这样重叠的边界落在有意义的位置。代价是重叠量只有"片段"的粒度、凑不准 token 数；
	 * 这是刻意的取舍：重叠 20 token 还是 50 token 无关紧要，
	 * "边界落在段落之间"才是真正影响召回的东西。
	 */
	private List<Group> pack(List<Piece> pieces, RagProperties.Chunking.Structural cfg, int overlapTarget) {
		int max = Math.max(1, cfg.getMaxTokens());
		// 重叠最多占单块额度的一半：给它留上限是为了避免"块一开头就被上一组填满"，
		// 那种块既装不下新内容、又整段重复，是重复率失控的直接来源
		overlapTarget = Math.min(overlapTarget, max / 2);
		int[] sections = sectionSignatures(pieces);

		List<Group> groups = new ArrayList<>();
		List<Piece> current = new ArrayList<>();
		int currentTokens = 0;
		int currentSection = -1;

		for (int i = 0; i < pieces.size(); i++) {
			Piece piece = pieces.get(i);
			int section = sections[i];
			boolean sectionChanged = currentSection != -1 && section != currentSection;

			// 三个断点条件：① 标题开始 ② 章节变化 ③ 装不下了
			boolean mustBreak = !current.isEmpty()
					&& (piece.startsSection() || sectionChanged || currentTokens + piece.tokens() > max);

			if (mustBreak) {
				groups.add(Group.of(current));

				// 重叠只在同一章节内做：跨章节重叠 = 往新块里注射上一节的内容
				List<Piece> tail = (!sectionChanged && !piece.startsSection())
						? tailForOverlap(current, overlapTarget, max)
						: List.of();
				current = new ArrayList<>(tail);
				currentTokens = 0;
				for (Piece carried : tail) {
					currentTokens += carried.tokens();
				}
			}

			current.add(piece);
			currentTokens += piece.tokens();
			currentSection = section;
		}
		if (!current.isEmpty()) {
			groups.add(Group.of(current));
		}
		return groups;
	}

	/**
	 * 从组尾往前取完整片段，凑到接近重叠目标为止。
	 *
	 * <p>只有在新块"装得下重叠、还能再装点新内容"时才带 —— 如果重叠刚好把
	 * 新块填满（{@code max - overlapTarget} 已经容不下任何片段），
	 * 那就退化成"两个块内容几乎相同"，纯属浪费。
	 */
	private static List<Piece> tailForOverlap(List<Piece> group, int overlapTarget, int max) {
		if (overlapTarget <= 0 || group.isEmpty() || overlapTarget >= max) {
			return List.of();
		}
		List<Piece> tail = new ArrayList<>();
		int tokens = 0;
		for (int i = group.size() - 1; i >= 0 && tokens < overlapTarget; i--) {
			Piece piece = group.get(i);
			// 标题不参与重叠：把上一节的标题复制到新块开头，
			// 会让这个块看起来"属于上一节"，是伪造结构信息
			if (piece.startsSection()) {
				break;
			}
			// 带进来之后不能把新块顶满，否则新块留不下新内容
			if (tokens + piece.tokens() > max - overlapTarget) {
				break;
			}
			tail.add(0, piece);
			tokens += piece.tokens();
		}
		return tail;
	}

	/**
	 * 每个片段所属的章节签名：它前面<b>最近的标题块下标</b>。
	 *
	 * <p>用标题块下标当章节身份，而不是比对标题文字 —— 文档里同名标题很常见
	 * （每个模块都有一节"注意事项"），按文字比对会把两个不同章节误判成同一节，
	 * 于是重叠与合并都会跨章节发生。
	 *
	 * <p><b>这里踩过一次，值得记</b>：第一版写成
	 * {@code return piece.startsSection() ? piece.startBlock() : -1}，
	 * 即"只有标题片段才有章节身份，正文片段一律 -1"。
	 * 同一节里的两个正文片段签名都是 -1（看起来"章节没变"，是对的），
	 * 但<b>跨到下一节之后签名仍然是 -1</b>，于是 {@code sectionChanged}
	 * 永远为 false，跨章节重叠与跨章节合并全部漏检。
	 * <br>教训：<b>用哨兵值表示"未知"时，要先确认这个哨兵不会同时表示两个不同的东西</b> ——
	 * 这里 -1 既表示"没有章节"，又被用来表示"两个不同的章节"，
	 * 语义一冲突，判断必然失效。正确做法是让每个片段都有一个真实身份。
	 */
	private static int[] sectionSignatures(List<Piece> pieces) {
		int[] signatures = new int[pieces.size()];
		int nearestHeading = -1;
		for (int i = 0; i < pieces.size(); i++) {
			if (pieces.get(i).startsSection()) {
				nearestHeading = pieces.get(i).startBlock();
			}
			signatures[i] = nearestHeading;
		}
		return signatures;
	}

	// ==================================================================
	//  第三步：合并同节内的过小组
	// ==================================================================

	/**
	 * 把同章节内偏小的相邻组合并起来。
	 *
	 * <p>三个条件必须同时满足，否则宁可留一个小块：
	 * <ol>
	 *   <li><b>同属一个章节</b>（按标题路径的前两层判断）——
	 *       跨章节合并会把两个主题搅在一起，直接伤害精确率；</li>
	 *   <li>合并后<b>不超上限</b>；</li>
	 *   <li>当前组确实<b>偏小</b>。</li>
	 * </ol>
	 *
	 * <p><b>第 2 条这里踩过一次</b>：第一版用的是"片段 token 之和 ≤ maxTokens"，
	 * 结果实测出现 <b>802 token 的块</b>（配置上限是 400）。
	 * 原因是块最终正文 = 面包屑 + 片段文本，而片段 token 之和不含面包屑；
	 * 更关键的是"偏小的组"往往本身就是<b>上一轮带过来的重叠尾巴</b>凑成的，
	 * 合并上去等于在接近满额的块上再加内容。
	 * <br>教训：<b>比较大小之前，先确认比较的是同一个东西</b> ——
	 * "片段之和"和"最终会占窗口的 token 数"是两个量，混用不会报错，
	 * 只会让上限形同虚设，而超窗要到检索阶段才暴露。
	 */
	private List<Group> mergeUndersized(ParsedDocument document, List<Group> groups,
			RagProperties.Chunking.Structural cfg) {
		int min = Math.max(0, cfg.getMinTokens());
		int max = Math.max(1, cfg.getMaxTokens());
		if (min <= 0) {
			return groups;
		}

		List<Group> merged = new ArrayList<>();
		for (Group group : groups) {
			Group previous = merged.isEmpty() ? null : merged.get(merged.size() - 1);
			boolean sameSection = previous != null
					&& sectionKeyOf(document, previous).equals(sectionKeyOf(document, group));
			// 用**渲染后**的 token 数来判断，而不是片段之和 —— 后者漏掉面包屑，
			// 会让"看起来没超"的块实际超窗（见上面的踩坑记录）
			if (previous != null && group.tokens() < min && sameSection
					&& renderedTokens(document, previous.concat(group), cfg) <= max) {
				merged.set(merged.size() - 1, previous.concat(group));
			}
			else {
				merged.add(group);
			}
		}
		return merged;
	}

	/** 一个组拼成最终正文（含面包屑）后的 token 数 —— 这才是真正会占窗口的量。 */
	private int renderedTokens(ParsedDocument document, Group group, RagProperties.Chunking.Structural cfg) {
		return renderedTokensOf(document, group.startBlock(), group.text(), cfg);
	}

	// ==================================================================
	//  第四步：组装成 Chunk（加面包屑、算最终 token）
	// ==================================================================

	private List<Chunk> toChunks(ParsedDocument document, List<Group> groups,
			RagProperties.Chunking.Structural cfg) {
		int depth = Math.max(1, cfg.getHeadingDepth());
		List<Chunk> chunks = new ArrayList<>();
		int index = 0;

		for (Group group : groups) {
			String body = group.text();
			if (body.isBlank()) {
				continue;
			}

			// 面包屑取"块起点所属的章节"：块横跨两节时，它主要是从起点那一节长出来的
			List<String> path = breadcrumbOf(document, group.startBlock(), cfg);

			String text = (cfg.isIncludeHeadingBreadcrumb() && !path.isEmpty())
					? "[" + String.join(" > ", path) + "]\n" + body
					: body;

			chunks.add(new Chunk(document.documentId() + "#" + index, document.documentId(), index, text,
					path, group.startBlock(), group.endBlock(),
					// token 按**最终正文**（含面包屑）算 —— 那才是真正会占窗口的量
					estimator.estimateMessage(text), text.length()));
			index++;
		}
		return chunks;
	}

	// ==================================================================
	//  工具
	// ==================================================================

	/**
	 * 组的"章节键"：标题路径的前两层。
	 *
	 * <p>比"最近标题"粗一档 —— 合并允许发生在同一个二级标题下的兄弟小节之间，
	 * 但不允许跨二级标题。粒度选择本身就是个权衡：太细（精确到最近标题）
	 * 会让合并几乎不发生、碎块依旧；太粗（精确到一级标题）会把"总则"和"处罚"
	 * 合进一个块。
	 */
	private static String sectionKeyOf(ParsedDocument document, Group group) {
		List<String> path = document.headingPathOf(group.startBlock());
		int depth = Math.min(2, path.size());
		return String.join(" > ", path.subList(0, depth));
	}

}
