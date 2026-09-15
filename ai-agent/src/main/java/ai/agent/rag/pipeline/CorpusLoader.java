package ai.agent.rag.pipeline;

import ai.agent.rag.config.RagProperties;
import ai.agent.rag.parse.DocumentParseException;
import ai.agent.rag.parse.DocumentParser;
import ai.agent.rag.parse.ParsedDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 语料库加载：目录 → 一批解析好的文档。
 *
 * <h2>为什么按目录扫，而不是在配置里逐个列文件</h2>
 * 加一份文档就要改一次配置、重启一次应用，实验节奏会被拖垮。
 * 而 RAG 的调参本来就该是"丢几份文档进去，跑一遍评测看数字"。
 *
 * <h2>为什么单个文件失败不中断整批</h2>
 * 真实语料里混着加密 PDF、损坏的 DOCX 是常态。一份坏文件让整批加载失败，
 * 后果是"加了一份文档，评测反而跑不起来了" —— 而正确行为显然是
 * <b>跳过它、把失败明确报出来、继续处理其余的</b>。
 * 注意"跳过"必须带日志与计数（见 {@link CorpusLoadResult#failed()}）：
 * 静默跳过是最糟的选择 —— 语料少了几份，评测分数会变，而没人知道为什么。
 */
@Component
public class CorpusLoader {

	private static final Logger log = LoggerFactory.getLogger(CorpusLoader.class);

	private final RagProperties properties;

	private final DocumentParser parser;

	public CorpusLoader(RagProperties properties, DocumentParser parser) {
		this.properties = properties;
		this.parser = parser;
	}

	/** 加载结果：成功的文档 + 失败清单。 */
	public record CorpusLoadResult(List<ParsedDocument> documents, List<FailedDocument> failed) {

		public CorpusLoadResult {
			documents = List.copyOf(documents);
			failed = List.copyOf(failed);
		}

		public int blockCount() {
			return documents.stream().mapToInt(ParsedDocument::blockCount).sum();
		}
	}

	/** 一份读不进来的文档：必须让它可见，不能静默丢。 */
	public record FailedDocument(String documentId, String reason) {
	}

	/** 按配置加载语料库。 */
	public CorpusLoadResult load() {
		return load(properties.getCorpus().getLocation());
	}

	/**
	 * 从指定位置加载语料库。
	 *
	 * <p>用 {@code file:} 前缀可以直接指向本机目录 —— 换成真实企业文档时
	 * 不需要重新打包。这是"语料位置可配"这件事的实际价值。
	 */
	public CorpusLoadResult load(String location) {
		RagProperties.Corpus cfg = properties.getCorpus();

		// 用 PathMatchingResourcePatternResolver 而不是注入 ResourceLoader：
		// 这里需要的是 **通配符扫描**（classpath:dir/**/*），
		// 而 ResourceLoader 只能取单个资源，拿不到"一批"。
		PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
		String pattern = location.endsWith("/") ? location + "**/*" : location + "/**/*";

		List<Resource> resources;
		try {
			resources = List.of(resolver.getResources(pattern)).stream()
					.filter(Resource::isReadable)
					.filter(Resource::exists)
					.sorted(Comparator.comparing(r -> String.valueOf(r.getFilename())))
					.toList();
		}
		catch (IOException e) {
			throw new UncheckedIOException("扫描语料目录失败：" + location, e);
		}

		List<ParsedDocument> documents = new ArrayList<>();
		List<FailedDocument> failed = new ArrayList<>();

		for (Resource resource : resources) {
			String fileName = resource.getFilename();
			if (fileName == null || !hasWantedExtension(fileName, cfg)) {
				continue;
			}
			if (!parser.supports(fileName)) {
				if (!cfg.getExtensions().isEmpty()) {
					log.debug("跳过解析器不支持的格式：{}", fileName);
				}
				continue;
			}

			try {
				long size = resource.contentLength();
				if (cfg.getMaxFileSizeBytes() > 0 && size > cfg.getMaxFileSizeBytes()) {
					failed.add(new FailedDocument(fileName,
							"文件过大（%d 字节 > 上限 %d）".formatted(size, cfg.getMaxFileSizeBytes())));
					continue;
				}
				byte[] bytes;
				try (var in = resource.getInputStream()) {
					bytes = in.readAllBytes();
				}
				documents.add(parser.parse(fileName, bytes, fileName));
			}
			catch (DocumentParseException e) {
				// 跳过但记账：语料少一份会让分数变化，必须让人看得见
				log.warn("语料解析失败，已跳过：{} —— {}", fileName, e.getMessage());
				failed.add(new FailedDocument(fileName, e.getMessage()));
			}
			catch (IOException e) {
				log.warn("语料读取失败，已跳过：{} —— {}", fileName, e.toString());
				failed.add(new FailedDocument(fileName, e.toString()));
			}
		}

		log.info("语料加载完成 location={} documents={} blocks={} failed={}",
				location, documents.size(), documents.stream().mapToInt(ParsedDocument::blockCount).sum(),
				failed.size());
		return new CorpusLoadResult(documents, failed);
	}

	private static boolean hasWantedExtension(String fileName, RagProperties.Corpus cfg) {
		if (cfg.getExtensions() == null || cfg.getExtensions().isEmpty()) {
			return true;
		}
		int dot = fileName.lastIndexOf('.');
		if (dot < 0) {
			return false;
		}
		String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
		return cfg.getExtensions().stream().map(e -> e.toLowerCase(Locale.ROOT)).anyMatch(ext::equals);
	}

}
