package ai.agent.entity.rag.parse;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析后的文档：一串带类型的块 + 一份"每个块的标题路径"。
 *
 * <h2>为什么把标题路径在这里算好，而不是留给切分层</h2>
 * "这个块属于哪一节"是<b>纯结构信息</b>，只跟块的顺序和标题层级有关，
 * 跟"怎么切"完全无关。把它放在解析层算一次，切分层（无论哪种策略）
 * 都能直接用，不必各自重写一遍"往上找最近的上级标题"的逻辑 ——
 * 那种逻辑写两遍就一定会有一遍写错（漏掉某个层级、或遇到跳级标题时行为不一致）。
 *
 * @param documentId 文档标识（相对路径或逻辑名），召回结果里要带回它才能定位来源
 * @param blocks     按原顺序排列的块
 */
public record ParsedDocument(String documentId, List<ParsedBlock> blocks) {

	public ParsedDocument {
		blocks = List.copyOf(blocks);
	}

	/** 块的数量。 */
	public int blockCount() {
		return blocks.size();
	}

	/** 整个文档的纯文本（块之间用换行连接），用于"不分块"的整体对照。 */
	public String plainText() {
		StringBuilder sb = new StringBuilder();
		for (ParsedBlock block : blocks) {
			if (sb.length() > 0) {
				sb.append('\n');
			}
			sb.append(block.text());
		}
		return sb.toString();
	}

	/**
	 * 计算第 {@code index} 个块的标题路径，例如
	 * {@code ["二、阶段一 · 大模型对接层", "学习点 7 · 虚拟线程异步改造"]}。
	 *
	 * <p>规则：沿块序列向前回溯，维护一个"当前各级标题"的栈 ——
	 * 遇到层级为 L 的标题时，把栈里层级 ≥ L 的都弹掉再压入，
	 * 这样得到的路径天然正确，跳级标题（一级直接跳到三级）也不会串味。
	 *
	 * <p>块本身就是标题时，返回的路径<b>包含它自己</b>：
	 * 一个标题块被召回时，它最相关的上下文就是它自己所属的这一节。
	 */
	public List<String> headingPathOf(int index) {
		List<String> path = new ArrayList<>();
		for (int i = 0; i <= index && i < blocks.size(); i++) {
			ParsedBlock block = blocks.get(i);
			if (!block.isHeading()) {
				continue;
			}
			int level = Math.max(1, block.level());
			// 弹掉同级与更低层级的标题，保证路径是一条真正的祖先链
			while (path.size() >= level) {
				path.remove(path.size() - 1);
			}
			while (path.size() < level - 1) {
				// 跳级标题：中间层缺失时补空位，否则后续层级的插入位置会错位
				path.add("");
			}
			path.add(block.text());
		}
		return path;
	}

	/**
	 * 标题路径的字符串形式，层级之间用 {@code " > "} 连接，空层自动跳过。
	 *
	 * <p>这就是检索结果的"面包屑"。它有两个用途：
	 * 一是让人一眼看出块属于哪一节（排查召回错误时最有用的一条信息）；
	 * 二是<b>可选地拼进块正文</b> —— 块单独被召回时，标题里的词
	 * （例如"虚拟线程""Token 预算"）往往正是用户提问用的词，
	 * 但块正文里可能一次都没出现。这是结构化分块相对硬切的一个隐性优势。
	 */
	public String headingBreadcrumb(int index) {
		return String.join(" > ", headingPathOf(index).stream().filter(s -> !s.isBlank()).toList());
	}

}
