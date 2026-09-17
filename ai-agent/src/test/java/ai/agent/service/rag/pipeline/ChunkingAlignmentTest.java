package ai.agent.service.rag.pipeline;

import ai.agent.entity.rag.chunk.Chunk;
import ai.agent.service.impl.rag.chunk.StructuralChunker;
import ai.agent.config.rag.RagProperties;
import ai.agent.entity.rag.eval.AlignmentExperiment;
import ai.agent.service.rag.parse.BlockType;
import ai.agent.entity.rag.parse.ParsedBlock;
import ai.agent.entity.rag.parse.ParsedDocument;
import ai.agent.service.token.TokenEstimator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 块大小对齐实验的<b>不变式</b>测试。
 *
 * <h2>为什么必须有这一组断言（它抓的是一个真实发生过的 bug）</h2>
 * 第一版 {@code StrategyEvaluator.chunkCorpus} 调的是
 * {@code chunker.chunk(document)} —— 接口上那个不带参数的方法。
 * 而变体参数（尺寸 / 重叠）只存在于 {@link StrategyEvaluator.ChunkingVariant} 里，
 * 接口方法只认配置值。于是：
 * <ul>
 *   <li><b>尺寸扫描表的 9 行数字完全相同</b>（都是"157 块 / 234.8 token"）；</li>
 *   <li>号称"fixed 121 字"的对齐组，实际跑的是 500 字硬切；</li>
 *   <li>二分收敛永远收敛不了（每个点测出来一样），却<b>不报任何错</b>；</li>
 *   <li>报告照常生成、表格照常漂亮 —— 两个实验同时变成废纸。</li>
 * </ul>
 * 这正是本项目反复出现的那一类退化：<b>"功能成功了吗"的断言一路绿灯</b>。
 * 所以这里的断言全部是<b>结构性</b>的：不问"跑通了吗"，只问
 * "参数真的改变了结果吗"、"两组的块大小真的对齐了吗"。
 *
 * <p>把结构断言写在测试里而不是只写在注释里，是因为注释不会失败。
 */
@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class ChunkingAlignmentTest {

	@Autowired
	private StrategyEvaluator evaluator;

	@Autowired
	private RagAlignmentService alignmentService;

	@Autowired
	private CorpusLoader corpusLoader;

	@Autowired
	private RagProperties properties;

	@Autowired
	private TokenEstimator estimator;

	@Test
	@DisplayName("变体参数必须真的生效：不同尺寸必须切出不同的块数与平均大小")
	void variantParametersMustTakeEffect() {
		List<ParsedDocument> documents = corpusLoader.load().documents();
		int rawTokens = evaluator.rawCorpusTokens(documents);

		StrategyEvaluator.SizeMeasurement small = evaluator.measure(documents,
				fixedVariant(200, 20), rawTokens);
		StrategyEvaluator.SizeMeasurement large = evaluator.measure(documents,
				fixedVariant(800, 80), rawTokens);

		// 块变大 ⇒ 块数必须变少（这条等式一旦不成立，说明尺寸参数被丢掉了）
		assertThat(small.chunkCount())
				.as("200 字硬切切出的块必须明显多于 800 字硬切（%d vs %d）", small.chunkCount(), large.chunkCount())
				.isGreaterThan(large.chunkCount());
		assertThat(small.avgChunkTokens())
				.as("200 字硬切的平均块必须明显小于 800 字硬切")
				.isLessThan(large.avgChunkTokens());

		// 顺带钉住"参数确实按传入值走"：200 字的平均块不该接近配置值 500 字那一档
		StrategyEvaluator.SizeMeasurement configured = evaluator.measure(documents,
				StrategyEvaluator.ChunkingVariant.configuredFixed(properties), rawTokens);
		assertThat(small.avgChunkTokens())
				.as("显式 200 字不能被静默替换成配置值 %d 字", properties.getChunking().getFixed().getSize())
				.isLessThan(configured.avgChunkTokens() * 0.7);
	}

	@Test
	@DisplayName("对齐实验：扫描表不能出现'所有尺寸量出同一个数'")
	void sweepMustNotBeConstant() {
		AlignmentExperiment experiment = alignmentService.run(List.of(1000, 4000), 7);

		assertThat(experiment.sweep()).as("扫描表要有多个点").hasSizeGreaterThanOrEqualTo(5);
		assertThat(experiment.sweep().stream().map(AlignmentExperiment.SweepRow::avgChunkTokens).distinct().count())
				.as("扫描表里不同尺寸的平均块大小必须不同 —— 全部相同就意味着尺寸参数被丢掉了（这是个真实发生过的 bug）")
				.isGreaterThan(1);
		assertThat(experiment.sweep().stream().map(AlignmentExperiment.SweepRow::chunkCount).distinct().count())
				.as("块数也必须随尺寸变化")
				.isGreaterThan(1);
	}

	@Test
	@DisplayName("对齐必须真的对上：对照组与实验组的平均块大小差距在容差内")
	void alignmentMustActuallyAlign() {
		AlignmentExperiment experiment = alignmentService.run(List.of(1000, 4000), 5);

		// 【第一版在这里写错过一次断言】当时断言 alignedGapTokens ≤ 容差。
		// 而那个字段报的是**块大小对齐那一组的收敛差**，容差 ALIGN_TOLERANCE_TOKENS
		// 是**二分搜索**的中止条件 —— 两者说的不是同一件事：
		// 早停可能把"粗扫描里较近的一个点"当成最优解返回（实测差到 55.5 token）。
		// 那次失败是有价值的：它逼着把收敛判据从"本次探针落在容差内"改成
		// "已找到的最优点落在容差内"，改完实测收敛到 0.5 token 以内。
		// 所以这条断言现在是**成立的**，保留它就是在钉住那个修复。
		assertThat(Math.abs(experiment.alignedGapTokens()))
				.as("收敛差必须落在容差内（实测 %.2f token，容差 %.1f）—— "
						+ "差太大说明二分没真正收敛，而『已对齐』就成了无法检验的声明",
						experiment.alignedGapTokens(), RagAlignmentService.ALIGN_TOLERANCE_TOKENS)
				.isLessThanOrEqualTo(RagAlignmentService.ALIGN_TOLERANCE_TOKENS);
		assertThat(experiment.refinementProbes()).as("必须真的做过细化探针（否则说明二分没跑）").isPositive();

		// 每组"块大小对齐"的对照，两边平均块大小必须接近
		List<AlignmentExperiment.PairComparison> sizeAlignedPairs = experiment.pairs().stream()
				.filter(p -> RagAlignmentService.DIM_AVG_CHUNK_TOKENS.equals(p.dimension()))
				.toList();
		assertThat(sizeAlignedPairs).as("必须存在『平均块大小』这一轴的对照").isNotEmpty();

		for (AlignmentExperiment.PairComparison pair : sizeAlignedPairs) {
			assertThat(pair.reports()).as("一组对照必须是两个策略").hasSize(2);
			double left = pair.reports().get(0).avgChunkTokens();
			double right = pair.reports().get(1).avgChunkTokens();
			assertThat(Math.abs(left - right))
					.as("对照「%s」里两边的平均块大小必须接近（左 %.1f vs 右 %.1f）—— "
							+ "不接近就说明这个混杂因素根本没被消掉", pair.fieldDescription(), left, right)
					.isLessThan(Math.max(left, right) * 0.05);
		}

		AlignmentExperiment.PairComparison first = sizeAlignedPairs.get(0);
		assertThat(first.reports().get(0).strategy()).as("右边是参照方：对齐后的硬切").contains("fixed-aligned");
		assertThat(first.reports().get(1).strategy()).as("被考察方是关掉面包屑的结构分块").contains("nobc");
	}

	@Test
	@DisplayName("等预算表必须有内容：每个档位、每个组都要有指标，且装进来的条数 > 0")
	void budgetTableMustBePopulated() {
		AlignmentExperiment experiment = alignmentService.run(List.of(1000, 4000), 5);

		assertThat(experiment.budgetRows()).as("预算档位表不能为空").isNotEmpty();
		assertThat(experiment.budgetSteps()).containsExactly(1000, 4000);
		experiment.budgetRows().forEach((budget, row) -> {
			assertThat(row).as("预算 %d 下必须有各组的指标", budget).isNotEmpty();
			row.forEach((label, alignment) -> {
				// 失败信息里带上"哪一组、哪个预算、实测值"，否则只看到 "0.0 > 0.0" 无从下手
				assertThat(alignment.avgHitCount())
						.as("预算 %d 下 %s 至少该装进来一条（第一条无条件放行）：实际 %s", budget, label, alignment)
						.isGreaterThan(0.0);
				assertThat(alignment.avgTokensUsed())
						.as("预算 %d 下 %s 用掉的 token 不该显著超过预算（允许第一条略超）：实际 %s", budget, label,
								alignment)
						.isLessThan(budget * 1.5);
				assertThat(label).as("预算表里的组名必须与 budgetLabel 对得上").isIn(experiment.budgetLabel());
			});
		});
	}

	@Test
	@DisplayName("关掉面包屑必须真的去掉面包屑（用可控文档断言，不用语料平均值的假设）")
	void breadcrumbToggleMustTakeEffect() {
		// 【这里返工过一次，原因值得记】第一版拿全语料的**平均块 token** 来断言
		// "关掉面包屑之后平均块必须更小"，实测 203.1 > 200.9 —— 断言失败。
		// 但代码没错：面包屑虽然不占 maxTokens 额度，它却计入**渲染后**的 token 数，
		// 而 mergeUndersized 判断"合并后会不会超上限"用的正是渲染后的值。
		// 少了每块约 38 token 的面包屑，更多相邻小节能被合并进来，
		// 于是块数 235 → 193、平均块反而变大（合并出来的块更大）。
		//
		// 教训：**平均块大小是一个受多个机制共同影响的聚合量**，
		// 拿它去断言"某个开关生效了"必然不稳（这跟本项目那条
		// "用来做控制的指标，含义必须单一"是同一个道理）。
		// 所以要断言的是这个开关**直接**控制的东西：块正文里有没有面包屑。
		// 用一个结构可控的小文档，结论就是确定性的。
		ParsedDocument document = syntheticDocument();
		int maxTokens = properties.getChunking().getStructural().getMaxTokens();

		StructuralChunker withBreadcrumb = new StructuralChunker(propertiesWithBreadcrumb(true), estimator);
		StructuralChunker withoutBreadcrumb = new StructuralChunker(propertiesWithBreadcrumb(false), estimator);

		List<String> withTexts = withBreadcrumb.chunk(document).stream().map(Chunk::text).toList();
		List<String> withoutTexts = withoutBreadcrumb.chunk(document).stream().map(Chunk::text).toList();

		assertThat(withTexts).as("两组都必须切出块来（否则下面的断言会因为空集合而失去意义）").isNotEmpty();
		assertThat(withoutTexts).isNotEmpty();
		assertThat(withTexts.size()).as("切分决策不依赖面包屑的存在，块数应当相同").isEqualTo(withoutTexts.size());

		// 开了面包屑：块正文里必须出现标题文字
		assertThat(withTexts).as("开了面包屑之后，块正文里必须能看到标题（例如%s）",
				document.blocks().get(0).text())
				.anyMatch(text -> text.contains(document.blocks().get(0).text()));

		// 关掉面包屑：块正文里**不该**出现"标题 + > 连接符"这种面包屑形态
		assertThat(withoutTexts).as("关掉面包屑之后块正文里不该再出现面包屑分隔符")
				.noneMatch(text -> text.startsWith("[") && text.contains(" > "));

		// 每个块都必须比开了面包屑时短（少的就是那串标题路径）
		for (int i = 0; i < withTexts.size(); i++) {
			assertThat(withoutTexts.get(i).length())
					.as("第 %d 个块在关掉面包屑之后必须更短", i)
					.isLessThan(withTexts.get(i).length());
		}

		// 顺带钉住那条"想当然"的假设是错的：面包屑确实会改变全语料的块数与平均大小
		List<ParsedDocument> documents = corpusLoader.load().documents();
		int rawTokens = evaluator.rawCorpusTokens(documents);
		StrategyEvaluator.SizeMeasurement realWith = evaluator.measure(documents,
				StrategyEvaluator.ChunkingVariant.configuredStructural(properties), rawTokens);
		StrategyEvaluator.SizeMeasurement realWithout = evaluator.measure(documents,
				new StrategyEvaluator.ChunkingVariant("structural-nobc", "探针：无面包屑", true, 0, 0, maxTokens, false),
				rawTokens);
		assertThat(realWithout.chunkCount())
				.as("面包屑影响『合并后会不会超上限』的判断，所以真实语料上的块数会变（实测 %d vs %d）",
						realWithout.chunkCount(), realWith.chunkCount())
				.isNotEqualTo(realWith.chunkCount());
	}

	/** 结构可控的小文档：一个 H1 + 两个 H2，每节一段正文。 */
	private static ParsedDocument syntheticDocument() {
		List<ParsedBlock> blocks = List.of(
				new ParsedBlock(BlockType.HEADING, "第一章 总则", 1, 0),
				new ParsedBlock(BlockType.PARAGRAPH, "这是第一章的正文，用于验证面包屑是否存在。", 0, 1),
				new ParsedBlock(BlockType.HEADING, "1.1 适用范围", 2, 2),
				new ParsedBlock(BlockType.PARAGRAPH, "这是第一小节的正文，讲适用范围。", 0, 3),
				new ParsedBlock(BlockType.HEADING, "1.2 术语定义", 2, 4),
				new ParsedBlock(BlockType.PARAGRAPH, "这是第二小节的正文，讲术语定义。", 0, 5));
		return new ParsedDocument("synthetic", blocks);
	}

	/** 复制一份配置，只改面包屑开关。 */
	private RagProperties propertiesWithBreadcrumb(boolean breadcrumb) {
		RagProperties copy = new RagProperties();
		RagProperties.Chunking.Structural source = properties.getChunking().getStructural();
		RagProperties.Chunking.Structural target = copy.getChunking().getStructural();
		target.setMaxTokens(source.getMaxTokens());
		target.setMinTokens(source.getMinTokens());
		target.setOverlapTokens(source.getOverlapTokens());
		target.setHardSplitThresholdTokens(source.getHardSplitThresholdTokens());
		target.setMaxDuplicationRatio(source.getMaxDuplicationRatio());
		target.setHeadingDepth(source.getHeadingDepth());
		target.setIncludeHeadingBreadcrumb(breadcrumb);
		return copy;
	}

	@Test
	@DisplayName("报告里的说明文字不能残留未替换的格式占位符")
	void notesMustNotContainRawPlaceholders() {
		AlignmentExperiment experiment = alignmentService.run(List.of(1000), 5);

		// 【这条断言是踩出来的】报告里的说明是用 "…%.2f…".formatted(x) 拼的，
		// 而 Java 的字符串拼接优先级会让这种写法**静默出错**：
		//     notes.add("甲" + "乙 %.2f"。formatted(x))   ← 只有"乙"被替换
		// 实测真的漏了三条，界面上显示成 "与目标差 %.2f token"，
		// 而**不报错、不抛异常**，只是读者看到一串问号。
		// 这类"模板串没被替换"的问题，只有把产物本身检一遍才能发现。
		assertThat(experiment.notes()).as("报告必须带说明文字").isNotEmpty();
		for (String note : experiment.notes()) {
			assertThat(note.replace("%%", ""))
					.as("说明文字里不该残留未替换的占位符：%s", note)
					.doesNotContain("%.");
			assertThat(note).as("说明文字里不该为空").isNotBlank();
		}
		for (AlignmentExperiment.PairComparison pair : experiment.pairs()) {
			assertThat(pair.fieldDescription()).as("对照的字段说明不该残留占位符（%s）", pair.fieldDescription())
					.doesNotContain("%.");
			for (String note : pair.notes()) {
				assertThat(note).as("对照备注不该残留占位符：%s", note).doesNotContain("%.");
			}
		}
	}

	private static StrategyEvaluator.ChunkingVariant fixedVariant(int size, int overlap) {
		return new StrategyEvaluator.ChunkingVariant("probe-" + size, "fixed " + size + "字", false, size, overlap, 0,
				false);
	}

}
