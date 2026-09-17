package ai.agent.service.rag.eval;

import ai.agent.config.rag.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 评测集加载测试。
 *
 * <h2>为什么对"加载"这件事也要认真测</h2>
 * 评测集是<b>标注数据</b>，它出错的方式和代码完全不同：代码错了会崩，
 * 标注错了什么都不发生 —— 只是分数悄悄变了。而"分数变了"看起来
 * 和"我改的分块策略生效了"一模一样，于是你会照着错误的数据去调参。
 *
 * <p>所以加载器必须"大声地失败"：列数不对、字段空、id 重复，一律抛异常。
 * 这里的测试就是在钉死这几条"不许静默"。
 */
class EvalSetLoaderTest {

	private EvalSetLoader loader(String location) {
		RagProperties properties = new RagProperties();
		properties.getEvaluation().setEvalSetLocation(location);
		return new EvalSetLoader(new DefaultResourceLoader(), properties);
	}

	@Test
	@DisplayName("真实评测集能加载，且条数落在路线文档要求的 30–50 条区间内")
	void loadsProjectEvalSet() {
		List<EvalCase> cases = loader("classpath:rag/eval-set.tsv").load();

		// 路线文档：建立 30–50 条评测集 —— 别只问两三个问题觉得"还行"
		assertThat(cases.size()).isBetween(30, 50);
		// id 唯一（加载器强制，这里再确认一次）
		assertThat(cases.stream().map(EvalCase::id).distinct().count()).isEqualTo(cases.size());
		// 每条都有提问与标准答案
		assertThat(cases).allSatisfy(c -> {
			assertThat(c.question()).isNotBlank();
			assertThat(c.matchHeadings()).isNotEmpty();
			assertThat(c.source()).isNotBlank();
		});
		// 分组覆盖：评测集刻意混了多类问题，只有一类说明覆盖面有问题
		assertThat(cases.stream().map(EvalCase::tag).distinct().count()).isGreaterThanOrEqualTo(4);
	}

	@Test
	@DisplayName("注释与空行被跳过，不产生空用例")
	void skipsCommentsAndBlankLines() {
		List<EvalCase> cases = loader("classpath:rag/eval-set.tsv").load();
		assertThat(cases).allSatisfy(c -> assertThat(c.question()).doesNotStartWith("#"));
	}

	@Test
	@DisplayName("列数不对时直接抛异常，而不是把 undefined 塞进对象")
	void rejectsWrongColumnCount(@TempDir Path tempDir) throws IOException {
		// 这一行只有 3 列 —— 典型的"提问里混进了制表符"或"少写了一列"
		Path bad = tempDir.resolve("bad-eval.tsv");
		Files.writeString(bad, "doc.md\tX1\t一个提问\n", StandardCharsets.UTF_8);

		assertThatThrownBy(() -> loader(bad.toString()).load())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("字段");
	}

	@Test
	@DisplayName("id 重复时抛异常 —— 重复的 id 会让『哪条用例失败了』无法定位")
	void rejectsDuplicateId(@TempDir Path tempDir) throws IOException {
		Path dup = tempDir.resolve("dup-eval.tsv");
		Files.writeString(dup, """
				doc.md\tX1\t第一个提问\t某章节\tt
				doc.md\tX1\t第二个提问\t某章节\tt
				""", StandardCharsets.UTF_8);

		assertThatThrownBy(() -> loader(dup.toString()).load())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("重复");
	}

	@Test
	@DisplayName("必填字段为空时抛异常")
	void rejectsBlankRequiredField(@TempDir Path tempDir) throws IOException {
		Path blank = tempDir.resolve("blank-eval.tsv");
		Files.writeString(blank, "doc.md\tX1\t\t某章节\tt\n", StandardCharsets.UTF_8);

		assertThatThrownBy(() -> loader(blank.toString()).load())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("必填");
	}

	@Test
	@DisplayName("位置不存在时抛异常，不返回空列表（空列表会被当成'全部未命中'）")
	void rejectsMissingLocation() {
		assertThatThrownBy(() -> loader("classpath:rag/no-such-file.tsv").load())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("不存在");
	}

}
