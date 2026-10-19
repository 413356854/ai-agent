package ai.agent.service.rag.kb;

import ai.agent.entity.rag.kb.KbChunk;

import java.util.List;

/**
 * 切片的读改删（列表分页、改正文、启停、删除）。
 *
 * <h2>三个写操作的成本不一样，界面上要说清楚</h2>
 * <ul>
 *   <li><b>改正文</b>：要重新算一次向量（删旧行 + 插新行，Milvus 侧没有 upsert 可用），
 *       也就是**花一次钱、动两个库**，所以它是同步的 —— 保存完就能搜到新内容；</li>
 *   <li><b>启停</b>：只改 {@code kb_chunk.enabled} 一列，Milvus 一动不动
 *       （检索侧在召回之后按这一列过滤）；</li>
 *   <li><b>删除</b>：删向量 + 删行，并把所属文档的块数减一。</li>
 * </ul>
 */
public interface KbChunkService {

	/** 一份文档的切片，按序号分页（切片可能有几千块，一次给全在界面上翻不动）。 */
	Paged list(long documentId, int offset, int limit);

	/** 改正文：重新算向量并替换 Milvus 里那一行，同时更新字数与 token 数。 */
	KbChunk updateText(String uid, String text);

	/** 启停一片。 */
	KbChunk setEnabled(String uid, boolean enabled);

	/** 删一片：Milvus 里那一行 + 清单行，并把文档的块数减一。 */
	void delete(String uid);

	/** 分页结果。{@code total} 是这份文档的切片总数（"共 N 条"），不是本页条数。 */
	record Paged(List<KbChunk> chunks, long total, int offset, int limit) {
	}

}
