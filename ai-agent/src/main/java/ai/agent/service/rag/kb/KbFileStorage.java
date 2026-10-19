package ai.agent.service.rag.kb;

import ai.agent.config.rag.KnowledgeBaseProperties;
import ai.agent.config.rag.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * 上传原件的落盘与读取（{@code ai.rag.kb.storage-path} 下）。
 *
 * <h2>落盘名用 id + 扩展名，不用用户给的文件名</h2>
 * 用户给的名字只用于展示（{@code kb_document.name}）。落盘名若用它，就要处理
 * 路径分隔符、{@code ..}、控制字符、Linux 与 Windows 的保留名 —— 而每一种处理错了
 * 都是"文件写到别处去了"，且不报错。这里用 {@code {kbId}/{docId}.{ext}}：
 * 两个 id 都是自增数字，扩展名只保留字母数字。
 *
 * <h2>扩展名要清洗</h2>
 * 它来自用户文件名，且会被拼进路径。所以只保留 {@code [A-Za-z0-9]}、截断到 16 字符；
 * 洗完之后为空说明这个文件没有可用的扩展名，调用方当场拒绝
 * （没有扩展名就没有解析器认领它，落到流水线里失败得太晚）。
 */
@Component
public class KbFileStorage {

	private static final Logger log = LoggerFactory.getLogger(KbFileStorage.class);

	/** 扩展名的硬上限（与 {@code kb_document.format} 的列宽一致）。 */
	public static final int MAX_EXTENSION_CHARS = 16;

	private final KnowledgeBaseProperties properties;

	public KbFileStorage(RagProperties rag) {
		this.properties = rag.getKb();
	}

	/** 落盘根目录（不存在时建出来）。 */
	public Path root() {
		Path root = Path.of(properties.getStoragePath()).toAbsolutePath().normalize();
		try {
			Files.createDirectories(root);
		}
		catch (IOException e) {
			throw new KnowledgeBaseException("存储目录建不出来：" + root, e);
		}
		return root;
	}

	/**
	 * 写一份原件。
	 *
	 * @return 相对根目录的路径（存进 {@code kb_document.source_path}）
	 */
	public String store(long kbId, long documentId, String rawExtension, byte[] content) {
		String extension = sanitizeExtension(rawExtension);
		if (extension.isEmpty()) {
			throw new KnowledgeBaseException("文件名里没有可用的扩展名 —— 解析器按扩展名认领文件，"
					+ "没有它这份文档走不下去（支持：" + String.join("、", SUPPORTED_HINT) + "）");
		}
		Path dir = root().resolve(String.valueOf(kbId));
		Path file = dir.resolve(documentId + "." + extension);
		try {
			Files.createDirectories(dir);
			Files.write(file, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		}
		catch (IOException e) {
			throw new KnowledgeBaseException("写文件失败：" + file + "（" + e.getMessage() + "）", e);
		}
		log.info("知识库原件已落盘：{}（{} 字节）", file, content.length);
		return pathOf(kbId, documentId, extension);
	}

	/**
	 * 读一份原件。
	 *
	 * <p>文件不在时抛出的说法里带上路径 —— 这条链路上"文件不见了"的常见成因是
	 * 有人清理了 storage-path、或者换了部署目录之后库里的相对路径还指着老地方。
	 */
	public byte[] read(String sourcePath) {
		Path file = root().resolve(sourcePath).normalize();
		if (!file.startsWith(root())) {
			// 相对路径是自己写进去的，正常不会越界；越界说明那一行被人改过
			throw new KnowledgeBaseException("文件路径越界：" + sourcePath);
		}
		if (!Files.exists(file)) {
			throw new KnowledgeBaseException("文件不存在：" + file
					+ " —— 若 storage-path 换过目录，请重新上传这份文档");
		}
		try {
			return Files.readAllBytes(file);
		}
		catch (IOException e) {
			throw new KnowledgeBaseException("读文件失败：" + file + "（" + e.getMessage() + "）", e);
		}
	}

	/** 删一份原件（删文档时调用；文件已经不在了不算失败）。 */
	public void delete(String sourcePath) {
		try {
			Files.deleteIfExists(root().resolve(sourcePath).normalize());
		}
		catch (IOException e) {
			log.warn("删原件失败（记录已删，文件留在磁盘上）：{} —— {}", sourcePath, e.getMessage());
		}
	}

	static String pathOf(long kbId, long documentId, String extension) {
		return kbId + "/" + documentId + "." + extension;
	}

	/** 只保留字母数字，并截断到列宽；其余字符（含点）一律丢掉。 */
	static String sanitizeExtension(String raw) {
		if (raw == null) {
			return "";
		}
		String cleaned = raw.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
		return cleaned.length() > MAX_EXTENSION_CHARS ? cleaned.substring(0, MAX_EXTENSION_CHARS) : cleaned;
	}

	/** 报错文案里举例用的格式清单（真正的白名单在 {@code ai.rag.corpus.extensions}，只有那一处）。 */
	private static final java.util.List<String> SUPPORTED_HINT =
			java.util.List.of("md", "txt", "html", "pdf", "docx");

}
