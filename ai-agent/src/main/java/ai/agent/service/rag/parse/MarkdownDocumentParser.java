package ai.agent.service.rag.parse;

import ai.agent.entity.rag.parse.ParsedBlock;
import ai.agent.entity.rag.parse.ParsedDocument;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.util.misc.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Markdown 解析器：Markdown → HTML → 带结构的块序列。
 *
 * <h2>为什么 Markdown 需要一条专门的路径（这可能是本模块最值钱的一条踩坑）</h2>
 * 第一版实现把 {@code .md} 也交给 Tika 处理，结果：
 * <pre>
 *   输入：一篇 13KB 的 Markdown，含 19 个 ## / ### 标题、2 张表格、多个列表
 *   输出：<b>1 个 &lt;p&gt; 元素、0 个 &lt;h1&gt;~&lt;h6&gt;、0 个 &lt;table&gt;</b>
 *        整篇文档变成一个块，正文里还留着字面的 "#" 号
 * </pre>
 * 原因：Tika 把 {@code .md} 识别成 {@code text/markdown}，而
 * {@code tika-parsers-standard-package} 里<b>没有 Markdown 解析器</b> ——
 * 它退回 {@code TextAndCSVParser}，把整个文件当成<b>纯文本</b>。
 * 于是 Tika 眼里"这就是一大段没有结构的文字"，跟一个 {@code .txt} 没区别。
 *
 * <p><b>这个失败为什么特别危险</b>：整条链路没有任何报错。
 * 文档读进来了、块也切出来了、检索也能返回结果 —— 只是"按标题层级分块"
 * 这件事<b>根本没有发生</b>，退化成"按段落堆叠"。而分数下降会被归因成
 * "分块策略不好"或"检索不行"，排查方向从一开始就是错的。
 * 它最终是被集成测试里那条"标题块数必须 &gt; 20"的断言抓住的 ——
 * <b>这就是为什么"结构性断言"（数量级断言）比"功能是否成功"更有价值</b>：
 * 后者在这种退化面前一路绿灯。
 *
 * <h2>为什么用 flexmark 而不是自己写正则</h2>
 * Markdown 的表格、嵌套列表、围栏代码块、行内标记都有边界情况。
 * 自己写的正则会稳定地在"表格分隔行"和"代码块里的 # 号"上出错 ——
 * 而这两处恰好是本项目语料里密度很高的结构（台账里全是表格）。
 * flexmark 是 Java 生态里维护活跃、且支持 GFM 扩展的实现。
 *
 * <h2>表格扩展是必须开的</h2>
 * 标准 Markdown 规范里<b>没有表格</b>，它是 GFM 扩展。
 * 不开 {@link TablesExtension} 的话，表格会被渲染成一堆普通段落，
 * 于是"整表成块、不可切"这条策略失效 —— 又是同一个模式的静默退化。
 */
@Component
public class MarkdownDocumentParser implements DocumentParser {

	private static final Logger log = LoggerFactory.getLogger(MarkdownDocumentParser.class);

	private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("md", "markdown", "mdown", "mkd");

	private final Parser flexmark;

	private final HtmlRenderer renderer;

	public MarkdownDocumentParser() {
		// 【扩展必须显式开启】标准 Markdown 没有表格和删除线，
		// 它们是 GFM 扩展。不开表格扩展，语料里的表格会退化成普通段落 ——
		// 而本项目的语料（台账、路线文档）里表格密度极高，
		// 退化之后"整表成块"的策略就形同虚设
		MutableDataSet options = new MutableDataSet()
				.set(TablesExtension.COLUMN_SPANS, false)
				.set(TablesExtension.APPEND_MISSING_COLUMNS, true)
				.set(TablesExtension.DISCARD_EXTRA_COLUMNS, false)
				.set(TablesExtension.HEADER_SEPARATOR_COLUMN_MATCH, true);

		// 【扩展必须同时注册到解析器与渲染器 —— 这是本模块踩的第二个坑】
		// flexmark 的扩展是**两段式**的：ParserExtension 负责把文本识别成
		// TableBlock 节点，HtmlRendererExtension 负责把该节点渲染成 <table>。
		// 第一版只在 Parser 上注册了扩展，结果非常反直觉：
		//     AST 里明明有 TableBlock / TableHead / TableRow / TableCell（解析成功）
		//     但 renderer.render() 的输出里**一个 <table> 都没有**（渲染丢失）
		// 于是表格内容从 HTML 里凭空消失 —— 不报错、不告警，只是没了。
		//
		// 教训：**"解析成功"不等于"产物里有它"**。当中间表示里明明有东西、
		// 最终产物里却没有时，第一件该查的不是数据，而是"负责输出它的那一环
		// 到底注册了没有"。这也解释了为什么排查时直接看 AST 比看结果快得多。
		List<Extension> extensions = List.of(
				TablesExtension.create(),
				StrikethroughExtension.create());

		this.flexmark = Parser.builder(options).extensions(extensions).build();
		this.renderer = HtmlRenderer.builder(options).extensions(extensions).build();
	}

	@Override
	public ParsedDocument parse(String documentId, byte[] content, String fileName) {
		if (content == null || content.length == 0) {
			throw new DocumentParseException("文档内容为空：" + documentId);
		}

		// 显式按 UTF-8 解码。
		// 【为什么要显式】多数 Markdown 是 UTF-8，但中文文档里混 GBK 也很常见。
		// 用平台默认编码（中文 Windows 上是 GBK）解码 UTF-8 文件，
		// 结果是满篇乱码 —— 而乱码不会报错，只会让检索彻底失效。
		// 这里固定 UTF-8，并在解码失败时留下痕迹（见下面的 replacement 检查）
		String markdown = new String(content, StandardCharsets.UTF_8);
		if (markdown.indexOf('\uFFFD') >= 0) {
			// U+FFFD 是"解码失败"的替换字符。它的出现说明原文不是 UTF-8，
			// 这时必须**明确报出来**，而不是让乱码流进索引 ——
			// 乱码文档在检索里的表现是"这份文档永远召回不到"，
			// 而没人会想到是编码问题
			log.warn("Markdown 解码出现替换字符，文件可能不是 UTF-8：documentId={} fileName={}",
					documentId, fileName);
		}

		String html = renderer.render(flexmark.parse(markdown));
		List<ParsedBlock> blocks = XhtmlBlockExtractor.extract(documentId, wrapAsXhtml(html));

		if (blocks.isEmpty()) {
			throw new DocumentParseException("Markdown 中没有可用文本：" + documentId);
		}

		log.debug("Markdown 解析完成 documentId={} chars={} blocks={}", documentId, markdown.length(), blocks.size());
		return new ParsedDocument(documentId, blocks);
	}

	@Override
	public boolean supports(String fileName) {
		if (fileName == null) {
			return false;
		}
		int dot = fileName.lastIndexOf('.');
		return dot >= 0 && SUPPORTED_EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
	}

	/**
	 * 包一层最小的 XHTML 外壳。
	 *
	 * <p>{@link XhtmlBlockExtractor} 是从 {@code <body>} 开始遍历的
	 * （为了跳过 {@code <head>} 里的 title/meta）。flexmark 渲染出的只是一段
	 * HTML 片段、没有 {@code <body>}，所以这里补上 ——
	 * 不补的话提取器会回退到 documentElement（{@code <html>}），
	 * 结果碰巧也对，但那是"靠兜底逻辑碰巧正确"，一旦兜底逻辑调整就会静默失效。
	 * <b>不要依赖碰巧正确。</b>
	 */
	private static String wrapAsXhtml(String htmlFragment) {
		return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<html><body>" + htmlFragment + "</body></html>";
	}

}
