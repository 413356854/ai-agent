package ai.agent.rag.parse;

/**
 * 文档解析契约：把任意格式的字节流变成"带结构的块序列"。
 *
 * <p><b>为什么定义接口而不直接调 Tika</b>：解析器是整个 RAG 链路里
 * <b>最容易被替换的一环</b>，而且替换压力是真实存在的：
 * <ul>
 *   <li>Tika 走的是"快速本地解析"，PDF 只按文本流取字，<b>不理解版式</b> ——
 *       双栏 PDF、复杂表格会读成错乱的文字流；</li>
 *   <li>路线文档里明确写了备选方案：PDF 用 Layout-Aware 解析器
 *       （Docling / Unstructured），或者干脆<b>用多模态模型把页图转成 Markdown</b>；</li>
 *   <li>这两条路将来一定会试（因为"PDF 解析质量"直接决定 RAG 上限），
 *       而它们都返回同一种东西：文本 + 结构。</li>
 * </ul>
 * 有了这个接口，换解析器时切分、检索、评测三层的代码一行都不用动。
 *
 * <p>注意接口<b>只收字节、不收文件路径</b>：路径是"来源标识"，
 * 由调用方作为 {@code documentId} 传进来。这样上传的临时文件、
 * 从对象存储拉的流、类路径下的样例文档都能走同一条链路。
 */
public interface DocumentParser {

	/**
	 * 解析文档。
	 *
	 * @param documentId 文档标识（相对路径或逻辑名），会原样带进结果里
	 * @param content    原始字节
	 * @param fileName   原始文件名，用于判断格式（扩展名 / 内容类型）
	 * @return 解析结果，永不为 {@code null}
	 * @throws DocumentParseException 二进制损坏、格式不支持、加密等无法解析的情况
	 */
	ParsedDocument parse(String documentId, byte[] content, String fileName);

	/** 这个解析器是否声称能处理该文件名。 */
	boolean supports(String fileName);

}
