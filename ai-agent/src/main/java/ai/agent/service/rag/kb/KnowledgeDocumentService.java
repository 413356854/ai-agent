package ai.agent.service.rag.kb;

import ai.agent.entity.rag.kb.KbChunk;
import ai.agent.entity.rag.kb.KnowledgeDocument;

import java.util.List;

/**
 * 知识库文档：上传、列表、启停、删除、重新索引、切分预览。
 *
 * <h2>切分预览为什么必须有</h2>
 * 分块参数的效果只能"看到那块文本"才知道好坏 —— 数字（多少块、多少 token）
 * 看不出"它把一段话从中间切断了"这类问题。所以预览把**真正会入库的那些块**原样给出来，
 * 而它不写库、不算向量（不花钱、不占索引线程）。
 */
public interface KnowledgeDocumentService {

	/**
	 * 上传一份文档：落盘 + 落一行"已上传" + 排进索引队列。
	 *
	 * @param kbId 目标知识库
	 * @param originalName 原始文件名（展示用；落盘名由 id 生成，见 {@link KbFileStorage}）
	 * @param content 文件内容
	 */
	KnowledgeDocument upload(long kbId, String originalName, byte[] content);

	/** 某个知识库的文档列表（最近上传在前）。 */
	List<KnowledgeDocument> list(long kbId);

	/** 按 id 取；不存在时抛 400。 */
	KnowledgeDocument get(long documentId);

	/** 每个状态各几份（界面上的分档计数）。 */
	List<StatusCount> countsByStatus(long kbId);

	/** 这份文档在清单里有几行切片（一致性核对用：与 {@code kb_document.chunk_count} 比对）。 */
	long chunkCountOf(long documentId);

	/** 启停（停用的文档不参与检索；切片行的 enabled 不动，检索侧按 doc_id 过滤）。 */
	KnowledgeDocument setEnabled(long documentId, boolean enabled);

	/** 改展示名（不动落盘路径、不影响已索引的切片）。 */
	KnowledgeDocument rename(long documentId, String name);

	/** 删一份文档：向量 + 切片 + 文档行 + 原件。 */
	void delete(long documentId);

	/**
	 * 重新索引：清掉这份文档的切片与向量，状态推回"已上传"，再排一次队。
	 *
	 * <p>正在处理中（{@code PROCESSING}）时拒绝：那意味着已经有一个任务在跑，
	 * 放第二个进去会让两边互相删对方的产物（"先删后插"的两份删除叠在一起）。
	 */
	KnowledgeDocument reIndex(long documentId);

	/**
	 * 切分预览：解析原件、按当前策略切一遍，返回**将要入库的那些块**。
	 *
	 * <p>不写库、不算向量。返回的 uid 是空的（这些块还不存在）。
	 */
	List<KbChunk> previewChunks(long documentId);

	/** 某状态有几份。 */
	record StatusCount(String status, String label, long total) {
	}

}
