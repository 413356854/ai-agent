package ai.agent.rag.eval;

import ai.agent.rag.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 评测集加载器：把 TSV 读成用例列表。
 *
 * <h2>为什么加载要做"大声的校验"</h2>
 * 评测集是<b>标注数据</b>，它出错的方式和代码不一样：代码错了会编译失败或抛异常，
 * 而标注错了什么都不发生 —— 只是召回率悄悄变低。所以这里的每一步都主动报错：
 * <ul>
 *   <li>列数不对 → 直接抛，并告诉你第几行有几个字段（而不是把 undefined 塞进对象）；</li>
 *   <li>必填列空着 → 直接抛；</li>
 *   <li>同一份评测集里 id 重复 → 直接抛（重复的 id 会让"哪条用例失败了"无法定位）。</li>
 * </ul>
 * 这个习惯的直接来源是台账踩坑库 #32：校准脚本里一个被 try/catch 吞掉的 400
 * 让失败样本混进了数据表，看起来像"文本变长了 token 却没变"这种重大发现。
 * <b>一个静默失败的样本比少一个样本危险得多</b>。
 */
@Component
public class EvalSetLoader {

	private static final Logger log = LoggerFactory.getLogger(EvalSetLoader.class);

	/** TSV 列数。列定义见 eval-set.tsv 头部注释。 */
	private static final int EXPECTED_COLUMNS = 5;

	private final ResourceLoader resourceLoader;

	private final RagProperties properties;

	public EvalSetLoader(ResourceLoader resourceLoader, RagProperties properties) {
		this.resourceLoader = resourceLoader;
		this.properties = properties;
	}

	/** 加载配置里指定的评测集。 */
	public List<EvalCase> load() {
		return load(properties.getEvaluation().getEvalSetLocation());
	}

	/**
	 * 从任意位置加载评测集。
	 *
	 * <p><b>为什么要单独处理"看起来像 Windows 路径"的输入</b>：
	 * {@code DefaultResourceLoader} 把 {@code C:\...} 里的 {@code C:}
	 * 当成了 <b>URL 协议头</b>（就像 {@code http:} 那样），于是返回一个
	 * "不是文件、也不存在"的资源，报出「评测集不存在：C:\...\x.tsv」——
	 * 而那个文件明明就在那里。更糟的是错误信息指向"文件不存在"，
	 * 让人去查路径拼写，而真正的问题是<b>资源解析规则</b>。
	 *
	 * <p>所以先探测"这个字符串是不是一个真实存在的本地文件"，是就直接用
	 * （与 Spring Boot 命令行参数、配置文件里写 {@code file:} 或裸路径的习惯一致）。
	 *
	 * @param location Spring 资源位置（{@code classpath:...} / {@code file:...}），
	 *                 或一个<b>裸的本地文件路径</b>
	 */
	public List<EvalCase> load(String location) {
		Resource resource = resolve(location);
		if (!resource.exists()) {
			throw new IllegalStateException("评测集不存在：" + location);
		}

		List<String> lines;
		try (var in = resource.getInputStream()) {
			lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
		}
		catch (IOException e) {
			throw new UncheckedIOException("读取评测集失败：" + location, e);
		}

		List<EvalCase> cases = new ArrayList<>();
		List<String> seenIds = new ArrayList<>();
		int lineNumber = 0;

		for (String raw : lines) {
			lineNumber++;
			String line = raw.strip();
			// 允许 # 注释与空行：评测集是要反复手改的，注释能写清"这条为什么这么问"
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}

			String[] columns = raw.split("\t", -1);
			if (columns.length != EXPECTED_COLUMNS) {
				throw new IllegalStateException(("评测集第 %d 行有 %d 个字段，期望 %d 个"
						+ "（TSV 每行必须是 source/id/question/matchHeadings/tag，用制表符分隔；"
						+ "最常见的原因是提问里混进了制表符，或者 matchHeadings 多写了一列）：%s")
						.formatted(lineNumber, columns.length, EXPECTED_COLUMNS, line));
			}

			String source = columns[0].strip();
			String id = columns[1].strip();
			String question = columns[2].strip();
			String headings = columns[3].strip();
			String tag = columns[4].strip();

			if (source.isEmpty() || id.isEmpty() || question.isEmpty() || headings.isEmpty()) {
				throw new IllegalStateException("评测集第 %d 行的必填字段为空：%s".formatted(lineNumber, line));
			}
			if (seenIds.contains(id)) {
				throw new IllegalStateException(
						"评测集第 %d 行的 id 重复（%s）—— id 重复会让「哪条用例失败了」无法定位"
								.formatted(lineNumber, id));
			}
			seenIds.add(id);

			cases.add(new EvalCase(source, id, question,
					Arrays.stream(headings.split("\\|")).map(String::strip).filter(s -> !s.isEmpty()).toList(),
					tag.isEmpty() ? "ungrouped" : tag));
		}

		if (cases.isEmpty()) {
			throw new IllegalStateException("评测集里一条用例都没有：" + location);
		}
		log.info("评测集加载完成 location={} cases={}", location, cases.size());
		return List.copyOf(cases);
	}

	/**
	 * 解析资源位置。
	 *
	 * <p>三种情况依次判断，<b>顺序不能反</b>：
	 * <ol>
	 *   <li>带 {@code classpath:} / {@code file:} 前缀或含 {@code ://}
	 *       → 交给 Spring 的资源加载器；</li>
	 *   <li>裸路径且<b>文件真的存在</b> → 直接当本地文件
	 *       （这是关键的那一步，理由见 {@link #load(String)}）；</li>
	 *   <li>其余 → 仍交给资源加载器，保持它原有的报错语义。</li>
	 * </ol>
	 */
	private Resource resolve(String location) {
		if (location.startsWith("classpath:") || location.startsWith("file:") || location.contains("://")) {
			return resourceLoader.getResource(location);
		}
		Path path = Path.of(location);
		if (Files.isRegularFile(path)) {
			return new FileSystemResource(path);
		}
		return resourceLoader.getResource(location);
	}

}
