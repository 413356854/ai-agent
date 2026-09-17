package ai.agent.service.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 版本对比（逐行差异）的判据测试（阶段四第 3 件）。
 *
 * <h2>为什么"差异"这件事值得单独测</h2>
 * 它是这一页上<b>最容易被做错、而错了完全看不出来</b>的一块：
 * 一个按下标逐行比较的实现，会把"改了中间一行"显示成"它之后所有行都改了"——
 * 一份看起来像"我几乎重写了整份提示词"的差异表，而实际上只动了一行。
 * 那种视图比没有视图更坏：它会让人不敢做小改动，或者干脆不看差异直接激活。
 *
 * <p>这与阶段二那次"差值表把 token 成本夸大了 100 倍"是同一种错，
 * 而它当时也是<b>不报错</b>的（表里每个数字单看都合理）。
 */
class PromptDiffTest {

	private static PromptVersion version(int number, String content) {
		return new PromptVersion(PromptKind.AGENT_LOOP, number, content, PromptVersion.hashOf(content),
				content.length(), content.length() / 3, "第 " + number + " 版", "t", null, null);
	}

	private static List<PromptDiff.Line> changedLines(PromptDiff diff) {
		return diff.lines().stream()
				.filter(l -> l.op() == PromptDiff.Op.ADD || l.op() == PromptDiff.Op.REMOVE)
				.toList();
	}

	@Test
	@DisplayName("★ 改了中间一行：只有那一行显示成改动（后面所有行都不许变成『已修改』）")
	void middleLineChangeDoesNotCascade() {
		String before = """
				第一行
				第二行
				第三行
				第四行
				第五行
				第六行
				第七行
				第八行""";
		String after = before.replace("第四行", "第四行（改过了）");

		PromptDiff diff = DefaultPromptService.compare(version(1, before), version(2, after));

		assertThat(diff.added()).as("只加了一行").isEqualTo(1);
		assertThat(diff.removed()).as("只删了一行").isEqualTo(1);
		assertThat(diff.identical()).isFalse();
		// 这一条就是"按下标比较"那个实现的死穴：它会把第 4~8 行全报成改动
		assertThat(changedLines(diff)).as("改动行只有加的那一行与删的那一行（共 2 条）").hasSize(2);
		assertThat(changedLines(diff)).anySatisfy(l -> assertThat(l.text()).contains("改过了"));
		assertThat(changedLines(diff)).anySatisfy(l -> assertThat(l.text()).isEqualTo("第四行"));
	}

	@Test
	@DisplayName("★ 两份完全相同 → identical 为真且不给差异行（不是渲染一整份满是 SAME 的表）")
	void identicalVersionsAreReportedAsSuch() {
		String content = "第一行\n第二行\n第三行";
		PromptDiff diff = DefaultPromptService.compare(version(1, content), version(2, content));

		// 【这一条钉的是一个真 bug】第一版把 identical 写成了 raw.isEmpty()，
		// 而 raw 里装着**全部**行（含未变更的），于是它只有在两份都是空正文时才为真 ——
		// 结果是"没有差异"这个提示永远不显示，页面会渲染一整份全是 SAME 行的表
		assertThat(diff.identical()).as("内容相同必须能被认出来").isTrue();
		assertThat(diff.added()).isZero();
		assertThat(diff.removed()).isZero();
		assertThat(diff.lines()).as("既然完全相同，差异行就该是空的").isEmpty();
	}

	@Test
	@DisplayName("末尾多一个换行也算改动（否则差异表与字符数会对不上，而没人知道该信哪个）")
	void trailingNewlineIsADifference() {
		PromptDiff diff = DefaultPromptService.compare(version(1, "第一行\n第二行"), version(2, "第一行\n第二行\n"));

		assertThat(diff.identical()).as("末尾换行改变了正文，不能算相同").isFalse();
		assertThat(diff.added()).as("多出来的那个空行要被算进去").isEqualTo(1);
	}

	@Test
	@DisplayName("未变更的长段落被折叠，但改动附近的上下文要保留")
	void longUnchangedRunsAreCollapsedWithContext() {
		// 【为什么是"两处改动"而不是"一处"】只有一处改动时，整份文档就是一个
		// "开头到结尾"的 SAME 段 —— 而那种段刻意不折叠（见 collapse 的注释）。
		// 要测折叠，就必须造出一个**夹在两处改动之间**的长未变更段
		StringBuilder sb = new StringBuilder();
		for (int i = 1; i <= 40; i++) {
			sb.append("第 ").append(i).append(" 行\n");
		}
		String before = sb.toString();
		String after = before.replace("第 5 行", "第 5 行（改了）").replace("第 35 行", "第 35 行（改了）");

		PromptDiff diff = DefaultPromptService.compare(version(1, before), version(2, after));

		boolean collapsed = diff.lines().stream().anyMatch(l -> l.op() == PromptDiff.Op.GAP);
		assertThat(collapsed).as("夹在两处改动之间的 20 多行未变更内容不该逐行铺开").isTrue();
		assertThat(diff.added()).as("两处改动各加一行").isEqualTo(2);
		assertThat(diff.removed()).isEqualTo(2);
		// 改动那一行前后各留 2 行 —— "它在哪一段里"是差异的一部分
		assertThat(diff.lines().stream().filter(l -> l.op() == PromptDiff.Op.SAME).map(PromptDiff.Line::text))
				.as("保留的上下文必须是改动附近的那几行，而不是随便几行")
				.contains("第 3 行", "第 7 行", "第 33 行", "第 37 行");
	}

	@Test
	@DisplayName("短文档不折叠（免得塞进『省略了 1 行』这种没信息量的话）")
	void shortDocumentsAreNotCollapsed() {
		String before = "第一行\n第二行\n第三行\n第四行\n第五行";
		PromptDiff diff = DefaultPromptService.compare(version(1, before),
				version(2, before.replace("第三行", "第三行（改了）")));

		assertThat(diff.lines()).as("总共才 5 行，不该出现折叠标记")
				.noneMatch(l -> l.op() == PromptDiff.Op.GAP);
	}

	@Test
	@DisplayName("token 变化带符号：变长时是正数（页面要能说清『这版更贵』）")
	void tokenDeltaIsSigned() {
		PromptDiff grow = DefaultPromptService.compare(version(1, "短"), version(2, "长".repeat(50)));
		assertThat(grow.tokenDelta()).as("内容变长 → token 变化为正").isPositive();

		PromptDiff shrink = DefaultPromptService.compare(version(1, "长".repeat(50)), version(2, "短"));
		assertThat(shrink.tokenDelta()).isNegative();
	}

	@Test
	@DisplayName("逐行行号对得上：新增行只有目标行号，删除行只有起始行号")
	void lineNumbersArePopulated() {
		PromptDiff diff = DefaultPromptService.compare(version(1, "A\nB\nC"), version(2, "A\nC"));

		assertThat(diff.lines()).anySatisfy(l -> {
			if (l.op() == PromptDiff.Op.REMOVE) {
				assertThat(l.from()).as("删除行必须有起始版行号").isNotNull();
				assertThat(l.to()).as("删除行在目标版里不存在，行号应为空").isNull();
			}
		});
	}

}
