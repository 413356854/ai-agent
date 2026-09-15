package ai.agent.rag.parse;

/**
 * 文档无法解析时抛出。
 *
 * <p><b>为什么要有专门的异常类型，而不是让它变成一个笼统的 RuntimeException</b>：
 * 解析失败和"解析成功但内容为空"是两件必须分开处理的事 ——
 * 前者要告诉用户"这个文件读不了"（可能是加密 PDF、损坏的 DOCX），
 * 后者要告诉用户"读到了，但里面没有可用的文字"（扫描版 PDF 就是这个表现）。
 * 两者混在一起的后果是：一份<b>纯扫描件</b>会被报告成"文件损坏"，
 * 而真正该做的是对它启用 OCR 或多模态解析 —— 排查方向完全跑偏。
 *
 * <p>继承 {@link RuntimeException} 而不是受检异常：解析发生在
 * "上传一批文档"的循环里，每一份失败都应当被单独记录并继续处理下一份，
 * 而不是让整个批次因为一份坏文件全部失败。
 */
public class DocumentParseException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public DocumentParseException(String message, Throwable cause) {
		super(message, cause);
	}

	public DocumentParseException(String message) {
		super(message);
	}

}
