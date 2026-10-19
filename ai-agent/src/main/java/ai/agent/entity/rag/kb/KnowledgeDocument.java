package ai.agent.entity.rag.kb;

import java.time.Instant;

/**
 * 一份上传进来的文档（{@code kb_document} 表的一行）。
 *
 * <h2>三种"没有块"必须分得开</h2>
 * <ul>
 *   <li>{@code indexedAt == null} —— 从没索引成功过（还没跑，或者跑了但失败了）；</li>
 *   <li>{@code status == PROCESSED && chunkCount == 0} —— 索引成功，但一段都没切出来
 *       （扫描件、空文件）。这是**正常终态**，界面上要写成「已索引，但没有切出任何块」；</li>
 *   <li>{@code status == FAILED} —— 处理不了它，原因在 {@link #error()}。</li>
 * </ul>
 * 只看 {@code chunkCount} 一个字段的话，前两种长得一模一样（都是 0），
 * 而它们的处置方式相反：一个该换文档，一个该点重新索引。
 *
 * @param id 主键
 * @param kbId 所属知识库
 * @param name 上传时的原始文件名（展示用）
 * @param format 扩展名（小写）
 * @param sizeBytes 字节数
 * @param path 落盘位置（相对 {@code ai.rag.kb.storage-path}，形如 {@code {kbId}/{docId}.{ext}}）
 * @param status 索引状态
 * @param enabled 是否参与检索
 * @param chunkCount 上次索引切出的块数（0 是正常值，见类注释）
 * @param tokenCount 上次索引切出的估算 token 总数
 * @param error 失败原因原文；成功时为 null
 * @param indexedAt 上次索引成功的时间；从没成功过时为 null
 * @param createdAt 创建时间
 * @param updatedAt 最后更新时间
 */
public record KnowledgeDocument(Long id, long kbId, String name, String format, long sizeBytes, String path,
		KbDocumentStatus status, boolean enabled, int chunkCount, int tokenCount, String error,
		Instant indexedAt, Instant createdAt, Instant updatedAt) {

	/** 与 {@code kb_document.name} 的列宽一致。 */
	public static final int MAX_NAME_CHARS = 255;

	/** 与 {@code kb_document.error} 的列宽一致：失败原因超长时截断，截断的痕迹保留（末尾 `…`）。 */
	public static final int MAX_ERROR_CHARS = 1024;

	/** 从没索引成功过（与"索引了 0 块"不同，见类注释）。 */
	public boolean neverIndexed() {
		return indexedAt == null;
	}

	/** 索引成功但一块都没切出来。 */
	public boolean indexedWithNoChunk() {
		return status == KbDocumentStatus.PROCESSED && chunkCount == 0 && indexedAt != null;
	}

	/** 已经开始处理过（用于列表上区分"还没轮到"）。 */
	public boolean started() {
		return status != KbDocumentStatus.UPLOADED || indexedAt != null;
	}

	/** 失败原因的原文，超长时截断并留痕。 */
	public static String clampError(String raw) {
		if (raw == null) {
			return null;
		}
		String stripped = raw.strip();
		if (stripped.length() <= MAX_ERROR_CHARS) {
			return stripped;
		}
		return stripped.substring(0, MAX_ERROR_CHARS - 1) + "…";
	}

}
