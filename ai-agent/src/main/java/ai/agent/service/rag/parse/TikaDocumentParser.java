package ai.agent.service.rag.parse;

import ai.agent.entity.rag.parse.ParsedBlock;
import ai.agent.entity.rag.parse.ParsedDocument;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.html.DefaultHtmlMapper;
import org.apache.tika.parser.html.HtmlMapper;
import org.apache.tika.sax.ToXMLContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 基于 Apache Tika 的解析器：Word / PDF / PPT / HTML / CSV 等 → XHTML → 带结构的块序列。
 *
 * <h2>关键选择一：走 XHTML，不走纯文本</h2>
 * Tika 最常见的用法是 {@code BodyContentHandler}（吐纯文本），一行代码就能拿到全文 ——
 * 但<b>标题层级、列表、表格结构全部被拍平</b>，于是后面只能按字数硬切。
 * 而"按标题层级切分"正是阶段二的第一个要求，所以这里用 {@code ToXMLContentHandler}
 * 取结构的 XHTML，再交给 {@link XhtmlBlockExtractor} 还原成块。
 *
 * <h2>Markdown <b>不</b>走这个解析器</h2>
 * 实测：Tika 会把 {@code .md} 交给 {@code TextAndCSVParser} 当<b>纯文本</b>处理 ——
 * {@code #} 号原样留在正文里，整篇产出 <b>1 个 {@code <p>}、0 个 {@code <h1>}</b>，
 * 结构全丢。所以 Markdown 必须先转 HTML，见 {@link MarkdownDocumentParser}。
 * 这个分流不是优化，是正确性要求：不分开的话，"按标题层级分块"在 Markdown 上
 * 根本不成立，而且不会报任何错。
 */
@Component
public class TikaDocumentParser implements DocumentParser {

	private static final Logger log = LoggerFactory.getLogger(TikaDocumentParser.class);

	/**
	 * 支持的扩展名。
	 *
	 * <p>{@link AutoDetectParser} 其实能靠内容嗅探认出更多格式，这个集合只用于
	 * "上传前先挡一道明显不支持的类型"（例如 {@code .exe}）。
	 * 真判断仍交给 Tika —— 扩展名会撒谎（把 PDF 改名成 {@code .txt} 是常见操作，
	 * 而按扩展名分发解析器的实现会在这种文件上报一句莫名其妙的解析错误）。
	 *
	 * <p>注意<b>不含 {@code md}</b>：Markdown 由 {@link MarkdownDocumentParser} 负责。
	 */
	private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
			"txt", "text", "html", "htm", "xhtml",
			"pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "csv", "rtf", "odt", "epub");

	/**
	 * 解析器实例在多个线程间共享。
	 *
	 * <p>{@link AutoDetectParser} 被设计为可复用（内部持有格式探测器与解析器集合，
	 * 都是无状态的）。<b>不要</b>每次解析都 {@code new} 一个：Tika 的解析器注册表
	 * 初始化不便宜，而且"每次新建"会让解析器缓存的 {@code MimeType} 探测结果全部作废。
	 */
	private final Parser parser = new AutoDetectParser();

	@Override
	public ParsedDocument parse(String documentId, byte[] content, String fileName) {
		if (content == null || content.length == 0) {
			throw new DocumentParseException("文档内容为空：" + documentId);
		}

		String xhtml = toXhtml(documentId, content, fileName);
		List<ParsedBlock> blocks = XhtmlBlockExtractor.extract(documentId, xhtml);

		if (blocks.isEmpty()) {
			// 【必须区分"解析失败"与"解析成功但没内容"】
			// 扫描版 PDF（整页是图片）走的就是这条路：解析器完全成功、一个字符都没有。
			// 报成"文件损坏"会把排查方向引向错误的方向 —— 真正该做的是 OCR / 多模态解析。
			log.warn("文档解析成功但没有提取到任何文本块 documentId={} fileName={} bytes={}",
					documentId, fileName, content.length);
			throw new DocumentParseException(
					"文档中没有可用文本（如果是扫描版 PDF，需要 OCR 或多模态解析）：" + documentId);
		}

		log.debug("解析完成 documentId={} fileName={} bytes={} blocks={}",
				documentId, fileName, content.length, blocks.size());
		return new ParsedDocument(documentId, blocks);
	}

	@Override
	public boolean supports(String fileName) {
		return SUPPORTED_EXTENSIONS.contains(extensionOf(fileName));
	}

	private String toXhtml(String documentId, byte[] content, String fileName) {
		Metadata metadata = new Metadata();
		if (fileName != null && !fileName.isBlank()) {
			// 注意：Tika 3.x 里这个常量在 TikaCoreProperties，不在 Metadata 上。
			// 按老教程写 Metadata.RESOURCE_NAME_KEY 会直接编译不过 ——
			// 这属于"文档/教程与依赖版本不同步"的典型症状，遇到先查版本再改代码
			metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
		}

		ParseContext context = new ParseContext();
		// 显式固定 HTML 标签映射器。不设置时 Tika 会自己装一个默认实现，
		// 结果一样 —— 之所以写出来，是因为"哪些标签会被丢掉"直接决定解析出的块结构，
		// 属于本模块的正确性前提，不该依赖一个隐式的自动装配行为。
		context.set(HtmlMapper.class, DefaultHtmlMapper.INSTANCE);

		// 【为什么走字节流再解码，而不是直接给一个 StringWriter】
		// ToXMLContentHandler **没有** Writer 构造器（它的父类 ToTextContentHandler 有），
		// 只有 (OutputStream, encoding) 与 (String encoding) 两个。
		// 这里用前者并显式声明 UTF-8，然后**用同一个 UTF-8 解回来** ——
		// 关键是"声明什么编码就用什么解码"。
		// 反过来做（比如用 new String(bytes) 走平台默认编码）在中文 Windows 上是
		// GBK，整篇中文会变成乱码 —— 而且不报错，只是检索全废。
		ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.max(1024, content.length * 2));
		try {
			// ToXMLContentHandler 基于 ToTextContentHandler，**不做内容长度截断**
			// （WriteOutContentHandler 那个 100KB 默认上限在这里不生效），
			// 这正是我们要的：真实文档通常远超 100KB
			ToXMLContentHandler handler = new ToXMLContentHandler(buffer, StandardCharsets.UTF_8.name());
			parser.parse(new ByteArrayInputStream(content), handler, metadata, context);
		}
		catch (SAXException | TikaException | IOException | RuntimeException e) {
			// 加密 PDF 走这里：Tika 会抛 "Unable to extract text due to encryption" 一类的错误
			throw new DocumentParseException("解析文档失败（格式不支持或文件损坏/加密）："
					+ documentId + " —— " + e.getMessage(), e);
		}
		return buffer.toString(StandardCharsets.UTF_8);
	}

	private static String extensionOf(String fileName) {
		if (fileName == null) {
			return "";
		}
		int dot = fileName.lastIndexOf('.');
		return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

}
