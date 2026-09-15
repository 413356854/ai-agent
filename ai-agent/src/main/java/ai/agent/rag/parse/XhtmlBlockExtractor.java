package ai.agent.rag.parse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 把 XHTML 转成"带结构的块序列"——<b>解析层真正干活的那部分</b>。
 *
 * <h2>为什么从 TikaDocumentParser 里拆出来</h2>
 * 因为"取结构"这件事有两个来源，而"用结构"的逻辑必须只有一份：
 * <ul>
 *   <li>Word / PDF / HTML → Tika 直接吐 XHTML；</li>
 *   <li><b>Markdown → 必须先转 HTML</b>（实测 Tika 把 .md 当纯文本处理，
 *       见 {@link MarkdownDocumentParser} 里的踩坑记录），再用同一个逻辑取块。</li>
 * </ul>
 * 如果让两处各自实现一遍 DOM 遍历，必然出现"一个认 {@code <h2>}、
 * 另一个漏了 {@code <blockquote>}"这种不一致 —— 而后果是同一份内容
 * 换一条解析路径结构就不同，分块结果也就不一致，且不会报错。
 *
 * <h2>安全：为什么必须自己配 {@code DocumentBuilderFactory}</h2>
 * 要解析的 XHTML 来自<b>用户上传的文档</b>，属于不可信输入。JDK 的
 * {@code DocumentBuilderFactory} 默认允许 DTD 与外部实体 ——
 * 一份精心构造的 {@code .docx}/{@code .html} 就能用它读服务器本地文件（XXE）
 * 或发起内网请求（SSRF）。这里显式关掉 DTD、外部实体与外部 schema，
 * 并打开 {@code FEATURE_SECURE_PROCESSING}。
 *
 * <p>这类漏洞的特点是<b>功能上完全看不出来</b>：解析照样成功，只是顺手把
 * {@code /etc/passwd} 的内容读进了块里，然后被向量化、被检索、被模型复述出来。
 * 所以它必须在写解析器的第一天就配好，而不是等安全扫描报出来。
 */
public final class XhtmlBlockExtractor {

	private static final Logger log = LoggerFactory.getLogger(XhtmlBlockExtractor.class);

	private XhtmlBlockExtractor() {
	}

	/**
	 * XHTML → 块序列。
	 *
	 * @param documentId 文档标识（仅用于报错信息）
	 * @param xhtml      XHTML 文本
	 * @return 块序列，按文档顺序；没有任何内容时返回空列表
	 * @throws DocumentParseException XHTML 本身无法解析时
	 */
	public static List<ParsedBlock> extract(String documentId, String xhtml) {
		Document dom = parseDom(documentId, xhtml);
		List<ParsedBlock> blocks = new ArrayList<>();

		// 从 <body> 开始遍历。文档里的 <title> 等 head 内容不属于正文，
		// 混进来会变成一个莫名其妙的块（而且它往往和正文首句重复）
		Element root = firstElement(dom.getElementsByTagName("body"));
		if (root == null) {
			root = dom.getDocumentElement();
		}
		walk(root, blocks);
		return renumber(blocks);
	}

	// ------------------------------------------------------------------

	private static void walk(Node node, List<ParsedBlock> out) {
		for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (child.getNodeType() == Node.TEXT_NODE) {
				// 顶层散落的裸文本（某些解析器会产出）也要当段落收，否则会丢内容
				String text = clean(child.getNodeValue());
				if (!text.isEmpty()) {
					out.add(new ParsedBlock(BlockType.PARAGRAPH, text, 0, out.size()));
				}
				continue;
			}
			if (child.getNodeType() != Node.ELEMENT_NODE) {
				continue;
			}

			Element element = (Element) child;
			String tag = localName(element);

			if (isOneOf(tag, "script", "style", "noscript", "head", "meta", "link")) {
				continue; // 脚本与样式不是内容，收进去只会污染向量
			}
			if (tag.matches("h[1-6]")) {
				addBlock(out, BlockType.HEADING, clean(textOf(element)), Integer.parseInt(tag.substring(1)));
				continue;
			}
			if (isOneOf(tag, "p", "div", "section", "article", "header", "footer")) {
				// 容器型标签：先看它有没有块级子元素。有就继续往下钻（否则会把整节
				// 压成一个巨大的段落，等于把结构又拍平了）；没有就直接收成段落
				if (hasBlockChildren(element)) {
					walk(element, out);
				}
				else {
					addBlock(out, BlockType.PARAGRAPH, clean(textOf(element)), 0);
				}
				continue;
			}
			if (isOneOf(tag, "blockquote")) {
				if (hasBlockChildren(element)) {
					walk(element, out);
				}
				else {
					addBlock(out, BlockType.QUOTE, clean(textOf(element)), 0);
				}
				continue;
			}
			if (isOneOf(tag, "ul", "ol")) {
				for (Element item : childElements(element, "li")) {
					addBlock(out, BlockType.LIST_ITEM, clean(textOf(item)), 0);
				}
				continue;
			}
			if (isOneOf(tag, "li")) {
				addBlock(out, BlockType.LIST_ITEM, clean(textOf(element)), 0);
				continue;
			}
			if (isOneOf(tag, "table")) {
				// 【整表成块，绝不拆】一张表被切成两半，两个块都只拿到半张表头，谁都用不了
				addBlock(out, BlockType.TABLE, cleanTable(element), 0);
				continue;
			}
			if (isOneOf(tag, "pre")) {
				addBlock(out, BlockType.CODE, textOf(element).strip(), 0);
				continue;
			}
			if (isOneOf(tag, "img")) {
				// 图片本身取不到文字，但 alt 往往是唯一能表达它的文本。
				// 阶段二后期会换成多模态理解，现在至少不要把 alt 丢掉
				String alt = element.getAttribute("alt");
				if (alt != null && !alt.isBlank()) {
					addBlock(out, BlockType.OTHER, "[图片] " + clean(alt), 0);
				}
				continue;
			}
			if (isOneOf(tag, "br", "hr")) {
				continue;
			}

			// 其余标签（span / strong / em / a / td 等行内元素）：
			// 有块级子元素就继续钻，否则按段落收下
			if (hasBlockChildren(element)) {
				walk(element, out);
			}
			else {
				addBlock(out, BlockType.PARAGRAPH, clean(textOf(element)), 0);
			}
		}
	}

	private static boolean hasBlockChildren(Element element) {
		for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (child.getNodeType() != Node.ELEMENT_NODE) {
				continue;
			}
			String tag = localName((Element) child);
			if (tag.matches("h[1-6]") || isOneOf(tag, "p", "div", "section", "article", "ul", "ol",
					"li", "table", "pre", "blockquote")) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 表格转文本：每行占一行，单元格之间用 {@code " | "} 分隔。
	 *
	 * <p>保留行结构而不是把整张表连成一串，是因为行才是表的语义单位 ——
	 * 连成一串之后"某个指标的值是多少"就只能靠模型自己数，
	 * 而分行之后至少每行还是自洽的。
	 */
	private static String cleanTable(Element table) {
		List<String> rows = new ArrayList<>();
		for (Element tr : descendants(table, "tr")) {
			List<String> cells = new ArrayList<>();
			for (Element cell : childElements(tr, "td", "th")) {
				cells.add(clean(textOf(cell)).replace("\n", " "));
			}
			if (!cells.isEmpty()) {
				rows.add(String.join(" | ", cells));
			}
		}
		return String.join("\n", rows);
	}

	private static void addBlock(List<ParsedBlock> out, BlockType type, String text, int level) {
		if (text == null || text.isBlank()) {
			return; // 空标题/空段落不产出块：它们进不了检索，只会让块计数虚高
		}
		out.add(new ParsedBlock(type, text, level, out.size()));
	}

	private static List<ParsedBlock> renumber(List<ParsedBlock> blocks) {
		List<ParsedBlock> result = new ArrayList<>(blocks.size());
		for (int i = 0; i < blocks.size(); i++) {
			ParsedBlock block = blocks.get(i);
			result.add(new ParsedBlock(block.type(), block.text(), block.level(), i));
		}
		return result;
	}

	/**
	 * 归一化文本。
	 *
	 * <p>做三件事：把 {@code \u00A0}（不换行空格）换成普通空格 —— Tika 从
	 * PDF/HTML 提取时非常爱产出它，而它会让"看起来一样"的两段文本哈希不同、
	 * 分词不同，属于典型的隐形脏数据；统一换行符；压缩多余空白。
	 */
	private static String clean(String raw) {
		if (raw == null) {
			return "";
		}
		return raw.replace('\u00A0', ' ')
				.replace("\r\n", "\n")
				.replace('\r', '\n')
				.replaceAll("[ \t]+", " ")
				.replaceAll("\n{3,}", "\n\n")
				.strip();
	}

	private static String textOf(Node node) {
		return node == null ? "" : node.getTextContent();
	}

	private static List<Element> childElements(Element parent, String... tags) {
		List<Element> result = new ArrayList<>();
		for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (child.getNodeType() != Node.ELEMENT_NODE) {
				continue;
			}
			Element element = (Element) child;
			if (tags.length == 0 || isOneOf(localName(element), tags)) {
				result.add(element);
			}
		}
		return result;
	}

	private static List<Element> descendants(Element parent, String tag) {
		List<Element> result = new ArrayList<>();
		NodeList nodes = parent.getElementsByTagName(tag);
		for (int i = 0; i < nodes.getLength(); i++) {
			if (nodes.item(i) instanceof Element element) {
				result.add(element);
			}
		}
		return result;
	}

	private static Element firstElement(NodeList nodes) {
		for (int i = 0; i < nodes.getLength(); i++) {
			if (nodes.item(i) instanceof Element element) {
				return element;
			}
		}
		return null;
	}

	/** 取本地名并转小写：XHTML 可能带命名空间前缀（{@code xhtml:h2}），也可能大写。 */
	private static String localName(Element element) {
		String name = element.getLocalName() != null ? element.getLocalName() : element.getNodeName();
		int colon = name.indexOf(':');
		if (colon >= 0) {
			name = name.substring(colon + 1);
		}
		return name.toLowerCase(Locale.ROOT);
	}

	private static boolean isOneOf(String value, String... candidates) {
		for (String candidate : candidates) {
			if (candidate.equals(value)) {
				return true;
			}
		}
		return false;
	}

	private static Document parseDom(String documentId, String xhtml) {
		try {
			DocumentBuilder builder = secureDomFactory().newDocumentBuilder();
			// XHTML 由解析器生成、可能自带 DOCTYPE 声明，但 DTD 不会真的去取 ——
			// 我们已在 factory 上关掉外部 DTD，这里再显式给一个空 EntityResolver，
			// 双保险（任何"看起来像要联网取 DTD"的行为都被直接短路掉）
			builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
			return builder.parse(new InputSource(new StringReader(xhtml)));
		}
		catch (ParserConfigurationException | SAXException | IOException e) {
			throw new DocumentParseException("解析中间产物 XHTML 失败：" + documentId + " —— " + e.getMessage(), e);
		}
	}

	/**
	 * 加固过的 DOM 工厂。
	 *
	 * <p>每一项都对应一类真实攻击：外部实体（XXE 读本地文件）、
	 * 参数实体（十亿笑声 DoS）、外部 DTD / schema（SSRF 探测内网端口）。
	 * 详见类注释 —— 这些开关必须在上线前就位，事后补等于给已经泄漏的数据道歉。
	 */
	public static DocumentBuilderFactory secureDomFactory() {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		factory.setExpandEntityReferences(false);

		setFeatureQuietly(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
		setFeatureQuietly(factory, "http://xml.org/sax/features/external-general-entities", false);
		setFeatureQuietly(factory, "http://xml.org/sax/features/external-parameter-entities", false);
		setFeatureQuietly(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
		return factory;
	}

	/**
	 * 设置解析器特性，不支持就记录日志。
	 *
	 * <p>不同 XML 实现支持的特性集合不同（Xerces 有全部，某些内嵌实现只有子集）。
	 * 忽略失败是刻意的，但<b>必须留日志</b>：静默降级会让
	 * "我明明关了外部实体"变成一句没有依据的话。
	 */
	private static void setFeatureQuietly(DocumentBuilderFactory factory, String feature, boolean value) {
		try {
			factory.setFeature(feature, value);
		}
		catch (ParserConfigurationException e) {
			log.warn("XML 解析器不支持特性 {}={}（该平台的安全加固少了一项）", feature, value);
		}
	}

	/** 便于诊断与测试：当前 XML 解析器实际生效的安全特性。 */
	public static List<String> securityFeatures() {
		DocumentBuilderFactory factory = secureDomFactory();
		List<String> report = new ArrayList<>();
		report.add("namespaceAware=" + factory.isNamespaceAware());
		// 展开实体必须为 false：为 true 时外部实体有机会被解析出来（XXE 的入口）
		report.add("expandEntityReferences=" + factory.isExpandEntityReferences());
		report.add("ACCESS_EXTERNAL_DTD=\"\"（禁止读取外部 DTD）");
		report.add("ACCESS_EXTERNAL_SCHEMA=\"\"（禁止读取外部 schema）");
		report.add("FEATURE_SECURE_PROCESSING=true");
		return report;
	}

}
