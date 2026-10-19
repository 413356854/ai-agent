package ai.agent.entity.rag.kb;

import java.util.List;

/**
 * 一份文档的索引状态（{@code kb_document.status}）。
 *
 * <p>四个取值与 admin 的四态一一对应：上传完成、处理中、处理成功、处理失败。
 * 存的是枚举名（见 {@code db/knowledge-schema.sql} 的列注释）。
 *
 * <p>{@link #PROCESSED} 且 {@code chunk_count = 0} 是**正常终态**，不是失败 ——
 * 那是"解析成功但一段都没切出来"（扫描件、空文件）。它与 {@link #FAILED} 的区别是
 * "文档本身没内容"和"我们处理不了它"，处置方式不同（前者换文档，后者点重新索引）。
 */
public enum KbDocumentStatus {

	/** 已入库，还没开始索引。 */
	UPLOADED("已上传"),

	/** 索引线程正在处理。 */
	PROCESSING("索引中"),

	/** 索引成功（块数可能是 0，见类注释）。 */
	PROCESSED("已索引"),

	/** 索引失败，原因在 {@code kb_document.error}。 */
	FAILED("索引失败");

	private final String label;

	KbDocumentStatus(String label) {
		this.label = label;
	}

	/** 界面上显示的字。 */
	public String label() {
		return label;
	}

	/** 终态：不会再自己变化，只能靠人点"重新索引"。 */
	public boolean terminal() {
		return this == PROCESSED || this == FAILED;
	}

	/**
	 * 允许的状态迁移。索引线程只走 {@code UPLOADED → PROCESSING → PROCESSED|FAILED} 这一条链，
	 * 其余组合出现时说明有两个写入者在同一条记录上（重复入队、重启前后重叠），
	 * 处置是记一条 WARN 并放弃这次写入，而不是覆盖掉另一个写入者的结果。
	 */
	public boolean canMoveTo(KbDocumentStatus next) {
		return switch (this) {
			case UPLOADED -> next == PROCESSING;
			case PROCESSING -> next == PROCESSED || next == FAILED;
			case PROCESSED, FAILED -> next == UPLOADED || next == PROCESSING;
		};
	}

	/** 还没到终态的那些（启动清扫按它们捞"上次没跑完的"）。 */
	public static List<KbDocumentStatus> unfinished() {
		return List.of(UPLOADED, PROCESSING);
	}

	/** 从库里读出来的取值。认不出时落 {@link #UPLOADED}：老数据 + 新枚举取值的方向是"当作还没处理"。 */
	public static KbDocumentStatus parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return UPLOADED;
		}
		for (KbDocumentStatus status : values()) {
			if (status.name().equalsIgnoreCase(raw.strip())) {
				return status;
			}
		}
		return UPLOADED;
	}

}
