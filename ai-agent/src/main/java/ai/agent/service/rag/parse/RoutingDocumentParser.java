package ai.agent.service.rag.parse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 解析器路由：按文件名把文档分派给合适的解析器。
 *
 * <h2>为什么必须有一个路由，而不是"一个解析器包打天下"</h2>
 * 因为实测证明<b>不同格式需要完全不同的处理链</b>：
 * Markdown 必须"先转 HTML 再取结构"，而 Word/PDF 必须"交给 Tika 嗅探格式"。
 * 让其中任意一个兼任另一个，就会出现"其中一个正确、另一个静默退化"——
 * 这正是本模块踩过的坑（见 {@link MarkdownDocumentParser} 的类注释：
 * Tika 把 .md 当纯文本，13KB 带 19 个标题的文档被解析成 1 个段落）。
 *
 * <h2>为什么标 {@code @Primary}</h2>
 * 容器里现在有两个 {@link DocumentParser} bean（Tika 与 Markdown），
 * 任何按类型注入 {@code DocumentParser} 的地方都会因为"有两个候选"而启动失败。
 * 路由本身就是那个"该被注入的实现"，所以由它 {@code @Primary}。
 *
 * <p>不用 {@code @Qualifier} 是因为那样每个注入点都得知道"有哪些实现"，
 * 加一个新解析器就要改所有注入点；用 {@code @Primary} 则只在这一处决定。
 * （顺带避开台账踩坑库 #27 那一族问题：Spring 面对多个候选 bean
 * 只会报"期望一个但找到两个"，而这只能靠 {@code @Primary}/{@code @Qualifier}
 * 解决，改构造器数量是没用的。）
 */
@Component
@Primary
public class RoutingDocumentParser implements DocumentParser {

	private static final Logger log = LoggerFactory.getLogger(RoutingDocumentParser.class);

	private final List<DocumentParser> delegates;

	public RoutingDocumentParser(MarkdownDocumentParser markdownParser, TikaDocumentParser tikaParser) {
		// Markdown 排在前面：它的 supports() 只认 md 系列扩展名，不会误吞其它格式。
		// 【顺序在这里真的有意义】如果反过来把 Tika 放前面，也不会错（Tika 的
		// SUPPORTED_EXTENSIONS 里刻意不含 md），但那是靠两个类的白名单"恰好不重叠"。
		// 显式把专用解析器放前面，让"专用优先于通用"这个意图写在代码里，
		// 而不是依赖别人的白名单不重叠 —— 后者一改就出问题且不会报错
		this.delegates = List.of(markdownParser, tikaParser);
	}

	@Override
	public ParsedDocument parse(String documentId, byte[] content, String fileName) {
		for (DocumentParser delegate : delegates) {
			if (delegate.supports(fileName)) {
				log.debug("解析器路由 documentId={} fileName={} → {}",
						documentId, fileName, delegate.getClass().getSimpleName());
				return delegate.parse(documentId, content, fileName);
			}
		}
		throw new DocumentParseException("没有可用的解析器处理这个文件：" + fileName
				+ "（已知支持的格式见各解析器的 SUPPORTED_EXTENSIONS）");
	}

	@Override
	public boolean supports(String fileName) {
		return delegates.stream().anyMatch(d -> d.supports(fileName));
	}

	/** 便于诊断：当前生效的路由顺序。 */
	public List<String> parserChain() {
		return delegates.stream().map(d -> d.getClass().getSimpleName()).toList();
	}

}
