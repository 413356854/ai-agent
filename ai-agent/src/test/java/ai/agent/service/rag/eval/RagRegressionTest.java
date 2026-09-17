package ai.agent.service.rag.eval;

import ai.agent.entity.rag.eval.ChunkingComparison;
import ai.agent.entity.rag.eval.RagRegressionBaseline;
import ai.agent.service.rag.pipeline.RagEvaluationService;
import ai.agent.entity.rag.eval.RagRegressionBaseline.Drift;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * <b>离线评测的回归门禁</b>（阶段四第 4 件：评测接入 CI）。
 *
 * <h2>它补的是哪一个缺口</h2>
 * 在它之前，43 条评测集的结果只被<b>打印</b>出来（{@code RagEvidenceReportTest}）。
 * 那个类的取舍本身是对的 —— 把具体数值钉成断言会让"调参"变成"改测试"。
 * 但它留下了一个真实的洞：<b>打印出来的数字没有人会逐行比对</b>。
 * 于是"召回率悄悄掉了 3 个百分点"可以存在很久，直到某天有人翻报告才发现，
 * 而那时已经不知道是哪一次改动造成的。
 *
 * <h2>为什么它敢用"逐位比较"而不是一个宽松阈值</h2>
 * 因为这条链路是<b>完全确定的</b>：固定语料（仓库里的 4 份文档）+ 固定评测集（43 条）
 * + 本地 BM25。无模型、无网络、无时间依赖。
 * 实测<b>连跑两次，4 位小数逐位相同</b>。
 *
 * <p>确定性的东西用阈值是有害的：一个"允许掉 5%"的阈值会放过 4.9% 的退化，
 * 而放过一次就会放过第二次 —— 累积到最后，没人知道退化是从哪一次开始的。
 * 所以这里的判据是「**和基线不一样就要被看见**」，容差只留浮点比较的量级（{@link #TOLERANCE}）。
 *
 * <h2>红了之后怎么办（这一段是给未来的人看的，别删）</h2>
 * 它会红在两种完全不同的情形上，而<b>两种的处置正好相反</b>：
 *
 * <table border="1">
 *   <caption>两种红，两种处置</caption>
 *   <tr><th>情形</th><th>怎么认出来</th><th>怎么做</th></tr>
 *   <tr><td><b>真退化</b></td>
 *       <td>你这轮没动语料/分块/检索，而某个指标掉了</td>
 *       <td>去查代码。这正是它存在的理由 —— 别改基线</td></tr>
 *   <tr><td><b>有意的变更</b></td>
 *       <td>你刚调了分块参数 / 改了语料 / 加了用例 / 修了一条过期标注</td>
 *       <td>重新采基线，并在 {@code note} 里写清为什么动（见下）</td></tr>
 * </table>
 *
 * <p>⚠️ <b>这两种在数字上长得一模一样</b>，所以判断只能靠人。这也是这条基线
 * <b>刻意不做自动更新</b>的原因 —— 一个会自己更新的基线等于没有基线。
 *
 * <h2>它<b>不</b>回答什么（这条比上面的代码重要）</h2>
 * <ul>
 *   <li>❌ <b>它不回答"这次 Prompt 改动是不是改好了"</b>。这一点必须说透：
 *       离线评测集量的是<b>检索</b>（解析 → 分块 → BM25 → 指标），这条链路上
 *       <b>一次模型调用都没有</b>，所以**提示词的文本对这里的任何一个数字都没有影响**。
 *       "改了 Prompt 跑一遍回归"能得到的诚实结论是「其余部分没被带坏」，
 *       而<b>不是</b>"新提示词更好"。后者需要真模型 + 一批问题 + 一套评判口径，
 *       那是另一个缺口（与"自愈率没有真实数字"同族，已单列记录）；</li>
 *   <li>❌ 它<b>不</b>验证"多语料/多分块参数的泛化"。基线只对这一份语料 + 这一套参数成立；</li>
 *   <li>❌ 它<b>不</b>量延迟与内存。</li>
 * </ul>
 *
 * <h2>为什么把判据放在 {@link RagRegressionBaseline} 而不是这个类里</h2>
 * 因为"哪个指标朝哪个方向算变好"是一份<b>判据</b>，而判据必须能被单独验证。
 * 放在测试类里的话，它只能通过"跑一次完整评测"来间接测 —— 那既慢又掩盖了判据本身的错。
 * 拆出去之后，"召回变大不该报退化""成本变大该报退化"这两条可以直接用合成数据断言。
 */
@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class RagRegressionTest {

	/**
	 * 逐位比较的容差。
	 *
	 * <p>取这么小是**有意的**：这个量级只够吸收"double 转 JSON 再转回来"的精度损失，
	 * 吸收不了任何真实的指标变化。换句话说，凡是能被 <b>0.000000001</b> 挡住的差异，
	 * 都不值得人去看；而挡不住的，都值得。
	 *
	 * <p>⚠️ 如果这条线在某台机器上开始闪红（同样的代码、不同的运行），
	 * <b>不要调大它</b> —— 那说明这条链路不再确定了，而那本身就是必须先查清的事
	 * （最可能的两个原因：语料文件被改动过、或者评测集标注被改过）。
	 */
	static final double TOLERANCE = 1e-9;

	/** 重新采基线的开关（**必须显式打开**，默认只读不写）。 */
	private static final String WRITE_FLAG = "rag.baseline.write";

	/** 基线候选文件（人确认之后再拷进测试资源）。 */
	private static final Path CANDIDATE = Path.of("target", "rag-baseline-candidate.json");

	@Autowired
	private RagEvaluationService evaluationService;

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	@DisplayName("★ 离线评测对照代码基线：任何指标漂移都必须被看见（不是靠人翻报告）")
	void evaluationMatchesBaseline() {
		ChunkingComparison comparison = evaluationService.compare(null, 0);

		// 先把"这次到底量出了什么"落成文件。它的用途不是给人看（人是看测试报告），
		// 而是① CI 收作 artifact；② 红了之后能直接 diff 两份 JSON 定位是哪一项
		RagRegressionBaseline actual = RagRegressionBaseline.from(comparison, "本次实测（自动记录，不是基线）");
		writeActual(actual);

		if (Boolean.parseBoolean(System.getProperty(WRITE_FLAG, "false"))) {
			writeBaseline(actual);
			return; // 采基线模式下不比较 —— 此刻比较的对象正是它自己
		}

		Optional<RagRegressionBaseline> loaded = RagRegressionBaseline.load(mapper);
		if (loaded.isEmpty()) {
			fail(RagRegressionBaseline.missingBaselineHint());
			return;
		}
		RagRegressionBaseline baseline = loaded.get();

		// 【先核对格式版本】勉强读旧格式的后果很隐蔽：缺的字段会是 0.0，
		// 于是"基线说 recall@5 = 0"表现成一堆看起来像"严重退化"的红，而真因是格式不认识
		assertThat(baseline.schema())
				.as("基线的格式版本必须是 %s（读到别的值说明这份基线是旧格式，"
						+ "而勉强读它会把缺字段当成 0，报出一堆假退化）", RagRegressionBaseline.SCHEMA)
				.isEqualTo(RagRegressionBaseline.SCHEMA);
		// note 必填：没有"为什么采这一版"的基线，半年后没人敢动它，也没人敢信它
		assertThat(baseline.note()).as("基线必须写明为什么采这一版").isNotBlank();

		// 【采集条件必须先对上】
		// 换了语料或改了用例数时，数字变了是**当然的**，而"当然的变化"不该被报成退化 ——
		// 那会消耗对这条断言的信任，最后被人注释掉
		assertThat(baseline.sameContext(actual))
				.as("基线与本次的采集条件必须一致（否则数字变化无从归因）%n  基线：%s%n  本次：%s",
						baseline.describeContext(), actual.describeContext())
				.isTrue();

		List<Drift> drifts = baseline.diff(actual, TOLERANCE);
		List<Drift> regressions = drifts.stream().filter(Drift::worsened).toList();
		List<Drift> improvements = drifts.stream().filter(d -> !d.worsened()).toList();

		// 变化本身先报出来（不管方向）—— CI 日志里能直接读到，不必去翻 artifact
		report(baseline, drifts, regressions, improvements);

		assertThat(regressions)
				.as("""

						★ 离线评测相对基线出现退化。这条断言量的是"这 43 条用例在真实语料上跑出来的指标"，
						而它是一条**完全确定**的链路（固定语料 + 固定评测集 + 本地 BM25，无模型/无网络），
						实测连跑两次逐位相同 —— 所以**任何超出浮点噪声的差异都是真实的**。

						先判断自己属于哪一种（两种的处置正好相反）：

						① 真退化 —— 你这轮没动语料/分块/检索，而数字掉了
						   → 去查代码。这正是这条断言的用途。**不要改基线。**

						② 有意的变更 —— 你刚调了分块参数 / 改了语料 / 加了用例 / 修正了过期标注
						   → 重新采基线（下面有命令），并在 note 里写清为什么动。

						重新采基线（只在情形 ② 做）：
						  mvn -o test -Dtest=RagRegressionTest -DfailIfNoSpecifiedTests=false -Drag.baseline.write=true
						  然后把 %s 里那条 note 改写清楚，合并进 %s

						本次的完整实测值：%s
						""".formatted(CANDIDATE, RagRegressionBaseline.RESOURCE_PATH,
								RagRegressionBaseline.ACTUAL_FILE),
						regressions.stream().map(Drift::describe).collect(Collectors.joining("\n")))
				.isEmpty();
	}

	/**
	 * 把差异打成一份人类可读的报告：<b>既打印、也落成 UTF-8 文件</b>。
	 *
	 * <h2>为什么必须落文件（实测踩到）</h2>
	 * 第一版只 `System.out.println`，然后在 Windows 上读日志时发现**整段中文是乱码** ——
	 * 因为 Maven 的 stdout 跟随平台编码（GBK）。后果不是"显示难看"，而是很具体的一类误判：
	 * 这一段恰好是**失败原因与怎么处置**所在的地方，而它成了唯一读不出来的东西；
	 * 更糟的是本机可能"碰巧正常"，于是**只在 CI 上读不到**，而 CI 是它唯一的读者。
	 *
	 * <p>两手都做了：pom 里钉住 `sourceEncoding` 与 surefire 的 `-Dstdout.encoding=UTF-8`（治根），
	 * 外加落一份文件（不依赖任何人的控制台设置 —— CI `cat` 它、人直接打开它，字节一样）。
	 *
	 * <p><b>为什么"没有差异"也要打一行</b>：一份什么都不输出的绿色测试，
	 * 读的人无法区分"比过了、一模一样"与"这段代码压根没跑"。
	 * 这与界面自检里那条"空列表必须解释自己"是同一条规矩。
	 */
	private void report(RagRegressionBaseline baseline, List<Drift> drifts, List<Drift> regressions,
			List<Drift> improvements) {
		StringBuilder sb = new StringBuilder();
		sb.append("################ 离线评测回归门禁 ################").append('\n');
		sb.append("基线：").append(baseline.summary()).append('\n');
		sb.append("容差：").append(TOLERANCE).append("（只吸收浮点噪声；这条链路两次运行逐位相同）").append('\n');
		if (drifts.isEmpty()) {
			// 指标项数从 flatten 现算，而不是写死一个数字 —— 写死的话，
			// 加指标时这一行会悄悄说谎（而它正是"读了让人放心"的那一行）
			int metricCount = baseline.metrics().values().stream()
					.findFirst().map(m -> RagRegressionBaseline.flatten("x", m).size()).orElse(0);
			sb.append("结果：全部指标与基线一致（").append(baseline.strategies().size()).append(" 个策略 × ")
					.append(metricCount).append(" 项指标）").append('\n');
		}
		else {
			sb.append("结果：").append(regressions.size()).append(" 项退化、").append(improvements.size())
					.append(" 项朝好的方向变化").append('\n');
			for (Drift drift : drifts) {
				sb.append("  ").append(drift.worsened() ? "[退化] " : "[改进] ").append(drift.describe())
						.append('\n');
			}
		}
		sb.append("说明：这条链路量的是**检索**（解析 → 分块 → BM25 → 指标），"
				+ "全程 0 次模型调用 —— 所以提示词的文本对这里任何一个数字都没有影响。"
				+ "它能回答的是「其余部分没被带坏」，回答不了「新提示词更好」。").append('\n');
		String text = sb.toString();

		System.out.println();
		System.out.print(text);
		System.out.println();
		writeText(Path.of(RagRegressionBaseline.REPORT_FILE), text);
	}

	private void writeActual(RagRegressionBaseline actual) {
		write(Path.of(RagRegressionBaseline.ACTUAL_FILE), actual);
	}

	private void writeBaseline(RagRegressionBaseline actual) {
		write(CANDIDATE, actual);
		// 同时也写进测试资源目录：采基线是**人明确打开开关**才做的事，
		// 而人要做的最后一步是"填 note 并提交" —— 让文件直接落在该在的位置，
		// 少一次手抄（手抄一份 JSON 是很容易抄错一位数字的）
		write(Path.of("src", "test", "resources", "rag-baseline.json"), actual);
		System.out.println();
		System.out.println("################ 已采基线 ################");
		System.out.println("写到：" + CANDIDATE.toAbsolutePath());
		System.out.println("以及：ai-agent/src/test/resources/rag-baseline.json");
		System.out.println("⚠️ 下一步是**手工**编辑那个文件里的 note，写清这次为什么动基线 —— 不写就提交，"
				+ "半年后没人敢再动它。");
		System.out.println();
	}

	private void write(Path path, RagRegressionBaseline baseline) {
		writeText(path, RagRegressionBaseline.toJson(mapper, baseline) + System.lineSeparator());
	}

	/**
	 * 写一份 UTF-8 文本。
	 *
	 * <p>落盘失败必须炸：一个"以为写了其实没写"的基线会让下一次运行**静默地拿旧值比较** ——
	 * 而那正好会产生一条绿色的、什么也没验证的构建。
	 */
	private void writeText(Path path, String content) {
		try {
			Path parent = path.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.writeString(path, content, StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new IllegalStateException("写文件失败：" + path.toAbsolutePath(), e);
		}
	}

	/**
	 * 方向判据本身的测试（**不需要跑评测**，所以它很快，而且不受语料影响）。
	 *
	 * <h2>为什么这几条要单独存在</h2>
	 * 上面那条端到端测试红了的时候，人需要立刻知道一件事：
	 * <b>是数字真的变了，还是方向判据错了</b>。这两者的处置完全相反 ——
	 * 前者要查代码，后者要改这个测试。而"合成两条基线来比"是回答它最便宜的方式。
	 */
	@Test
	@DisplayName("方向判据：召回变大算改进、成本变大算退化 —— 判反了会让人不再相信这条断言")
	void directionJudgementIsCorrect() {
		RagRegressionBaseline before = synthetic(0.80, 200, 40000);
		// 召回降了、成本涨了 —— 两处都该被认成退化
		RagRegressionBaseline worse = synthetic(0.70, 250, 50000);
		List<Drift> down = before.diff(worse, TOLERANCE);
		assertThat(down).as("召回下降必须被认成退化").anySatisfy(d -> {
			assertThat(d.metric()).endsWith(".recall@5");
			assertThat(d.worsened()).isTrue();
		});
		assertThat(down).as("块数变多必须被认成退化（块越大越容易覆盖答案章节，是个已知混杂因素）")
				.anySatisfy(d -> {
					assertThat(d.metric()).endsWith(".chunkCount");
					assertThat(d.worsened()).isTrue();
				});

		// 召回涨了、成本降了 —— 两处都不该被认成退化（否则会误报，而误报会让人开始忽略这条断言）
		RagRegressionBaseline better = synthetic(0.90, 150, 30000);
		assertThat(before.diff(better, TOLERANCE)).as("召回变大与成本变小都不该报退化")
				.noneMatch(Drift::worsened);

		// 一模一样 → 一处差异都没有（容差之内的抖动必须被吃掉）
		assertThat(before.diff(synthetic(0.80, 200, 40000), TOLERANCE)).isEmpty();
	}

	@Test
	@DisplayName("没有登记方向的指标必须当场炸 —— 不能静默用默认判断")
	void unregisteredMetricDirectionThrows() {
		// `flatten` 与 `directions` 必须覆盖同一批指标。这里用反射把两边对齐，
		// 好让"加了一个指标却忘了登记方向"在**这条测试**上就红，
		// 而不是等到某次真实评测里被默认判断放过
		var sample = new RagRegressionBaseline.StrategyMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
		var flattened = RagRegressionBaseline.flatten("s", sample);
		var directions = RagRegressionBaseline.directions();
		assertThat(flattened.keySet().stream().map(RagRegressionBaseline::bareName).toList())
				.as("flatten 产出的每个指标都必须在 directions 里登记了方向")
				.allSatisfy(name -> assertThat(directions).containsKey(name));
		assertThat(directions.keySet())
				.as("directions 里不该有 flatten 产不出的指标（那说明登记表过时了）")
				.containsExactlyInAnyOrderElementsOf(
						flattened.keySet().stream().map(RagRegressionBaseline::bareName).toList());
	}

	@Test
	@DisplayName("采集条件不一致时必须能认出来（换了语料/用例数时，数字变化是当然的，不该报成退化）")
	void contextMismatchIsDetectable() {
		RagRegressionBaseline base = synthetic(0.80, 200, 40000);
		assertThat(base.sameContext(synthetic(0.80, 200, 40000))).as("同条件").isTrue();

		// 用例数变了（加了评测用例）
		RagRegressionBaseline moreCases = new RagRegressionBaseline(base.schema(), base.takenAt(), base.note(),
				base.strategies(), base.evalSet(), base.caseCount() + 5, base.documents(), base.metrics());
		assertThat(base.sameContext(moreCases)).as("用例数不同 → 条件不同").isFalse();

		// 策略集合变了（换了对照组的名字）
		RagRegressionBaseline otherStrategies = new RagRegressionBaseline(base.schema(), base.takenAt(), base.note(),
				List.of("fixed-size", "另一个策略"), base.evalSet(), base.caseCount(), base.documents(),
				base.metrics());
		assertThat(base.sameContext(otherStrategies)).as("策略集合不同 → 条件不同").isFalse();
	}

	/** 造一条合成基线（只填判定用得到的那几项，其余给 0）。 */
	private static RagRegressionBaseline synthetic(double recall5, int chunkCount, int totalTokens) {
		Map<String, RagRegressionBaseline.StrategyMetrics> metrics = new java.util.LinkedHashMap<>();
		// fixed-size 与 structural 各一份，值相同即可 —— 这里测的是**判据**，不是策略差异
		for (String strategy : List.of("fixed-size", "structural")) {
			metrics.put(strategy, new RagRegressionBaseline.StrategyMetrics(recall5, recall5, recall5, recall5,
					recall5, 0.25, chunkCount, 200.0, 390, totalTokens, 0.13, 0));
		}
		return new RagRegressionBaseline(RagRegressionBaseline.SCHEMA, "2026-09-17", "合成基线（测试用）",
				List.of("fixed-size", "structural"), "classpath:rag/eval-set.jsonl", 43, 4, metrics);
	}
}
