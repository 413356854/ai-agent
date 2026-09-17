package ai.agent.service.rag.parse;

/**
 * 解析出来的一个文本块。
 *
 * @param type  块类型，决定切分层怎么对待它
 * @param text  块正文（已去掉标记符号，例如标题不含 {@code #}）
 * @param level 标题层级，仅 {@link BlockType#HEADING} 有意义：1 = 一级标题；其余为 0
 * @param order 在原文档中的顺序，从 0 开始
 */
public record ParsedBlock(BlockType type, String text, int level, int order) {

	/** 这个块是不是标题。 */
	public boolean isHeading() {
		return type == BlockType.HEADING;
	}

	/** 正文是否为空（解析器会顺手滤掉空块，这里是兜底判断）。 */
	public boolean isBlank() {
		return text == null || text.isBlank();
	}

}
