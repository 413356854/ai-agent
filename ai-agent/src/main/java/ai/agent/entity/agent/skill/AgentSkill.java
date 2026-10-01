package ai.agent.entity.agent.skill;

import java.time.Instant;

/**
 * <b>一条技能</b>：放在哪个文件夹、叫什么、干什么用、正文是什么，以及它是谁的。
 *
 * <h2>两个"人"的列存的是用户 id</h2>
 * {@code ownerId} 与 {@code lastModifiedBy} 都是 {@code agent_user.id}，不是姓名 ——
 * 与 {@code AgentTask} 的三个用户列同一条理由：姓名可以改、也可以重名，
 * 而"这条技能是谁的"必须一直指向同一个人。显示用的姓名由读侧 LEFT JOIN 出来，
 * 所以 {@code ownerName} / {@code lastModifiedByName} 可能为 {@code null}
 * （账号那一行被手工清掉时），界面按 {@link #displayOwner()} 那一条判据显示。
 *
 * <h2>★ ownerId 只在服务端赋值</h2>
 * 构造器要求它非空，而它<b>不来自请求体</b>：请求体里只有文件夹 / 名字 / 说明 / 正文。
 * 让客户端传 ownerId 的写法，任何人打一次接口就能把记录写成别人的，
 * 而那种错误在界面上看起来完全正常（记录确实建出来了，只是归属不对）。
 *
 * <h2>长度上限为什么写成常量</h2>
 * 它们在 DDL 里就是 {@code VARCHAR(n)}，而超长在这里当场报出来 ——
 * 交给数据库的后果是插入报一个 1406 / 22001，那条消息里没有人看得懂"是哪个字段太长了"。
 * 三个上限值与 {@code permissions-schema.sql} 头注里那条 192 字符的索引长度依据一致。
 *
 * @param id 技能 id（新建时为 {@code null}，落库之后才有）
 * @param folder 文件夹目录（拥有者角色的目录前缀原文，来自界面上的选项集）
 * @param name 技能名（同一个人的同一个文件夹下唯一）
 * @param description 这个技能是干什么的（可空）
 * @param content 技能正文（可空）
 * @param ownerId 拥有者：创建这条技能的人（agent_user.id）
 * @param ownerName 拥有者的姓名（读侧 JOIN 出来的，可能为 {@code null}）
 * @param lastModifiedBy 最后修改人（agent_user.id）
 * @param lastModifiedByName 最后修改人的姓名（读侧 JOIN 出来的，可能为 {@code null}）
 * @param createdAt 创建时间
 * @param updatedAt 最后更新时间
 */
public record AgentSkill(Long id, String folder, String name, String description, String content, Long ownerId,
		String ownerName, Long lastModifiedBy, String lastModifiedByName, Instant createdAt, Instant updatedAt) {

	/** 与 DDL 的 {@code folder VARCHAR(192)} 对齐；长度依据见 permissions-schema.sql 的头注。 */
	public static final int MAX_FOLDER_CHARS = 192;

	/** 与 DDL 的 {@code name VARCHAR(128)} 对齐。 */
	public static final int MAX_NAME_CHARS = 128;

	/** 与 DDL 的 {@code description VARCHAR(512)} 对齐。 */
	public static final int MAX_DESCRIPTION_CHARS = 512;

	// ==================================================================
	//  大模型自补充规则：那一段的边界标记
	// ==================================================================
	//
	// 【为什么正文里要有标记，而不是"往里追加一段"】
	// 技能正文是**用户的内容**，而"验收通过之后由模型汇出几条规则"是系统写的。
	// 两者混在一段文本里，如果不划边界，下一次补充就只能"往末尾接"——
	// 接几次之后没人分得清哪一段是用户写的、哪一段是系统补的，
	// 而"把用户的内容改掉了"这件事在界面上看不出来（那段文字看起来只是变长了）。
	// 所以系统只在自己那一段里写，边界由下面两个标记回答。

	/**
	 * 自补充段的开始标记。
	 *
	 * <p>写法是 Markdown 的三级标题：技能正文本身大多是 Markdown，
	 * 用标题能让人在编辑器里一眼看到这一段的边界（用 HTML 注释也能做到，但那样折叠时看不见）。
	 */
	public static final String SUPPLEMENT_START = "### 大模型自补充规则 ###";

	/** 自补充段的结束标记（{@link #SUPPLEMENT_START} 的那一半）。 */
	public static final String SUPPLEMENT_END = "###end###";

	/**
	 * 建一条技能时放在两个标记之间的占位行。
	 *
	 * <p>它的作用是让"标记之间"从一建出来就是一段可识别的内容：
	 * 第一次补充时<b>替换</b>它、之后的补充在它原来那个位置<b>插入</b> ——
	 * 少一个占位行的话，"标记之间现在是空的"与"标记被删掉了"在字符串上长得一样。
	 */
	public static final String SUPPLEMENT_PLACEHOLDER = "（暂无：每完成一条 agent 任务，系统会在这里追加一条经验规则）";

	/**
	 * 每一条补充内容的开头那一行里的任务标识 —— 幂等判据。
	 *
	 * <p>"这条任务的经验已经补过了"必须能从正文里读出来：不看它的后果是
	 * 同一条任务被补两次（界面上重复的两条规则看起来像模型啰嗦，而不像重复写入）。
	 */
	public static final String RULE_BLOCK_TASK_PREFIX = "<!-- 任务 #";

	/** {@link #RULE_BLOCK_TASK_PREFIX} 的收尾（与 {@code "#id"} 拼成一条完整注释）。 */
	public static final String RULE_BLOCK_TASK_SUFFIX = " -->";

	/** 一条新建的自补充段落：只有两个标记与占位行，没有任何规则。 */
	public static String initialSupplementContent() {
		return SUPPLEMENT_START + "\n" + SUPPLEMENT_PLACEHOLDER + "\n" + SUPPLEMENT_END;
	}

	/**
	 * 正文里自补充那一段的原文（<b>含</b>两个标记）；找不到成对的标记时返回 {@code null}。
	 *
	 * <p>它是"系统只动自己那一段"的唯一判据 —— 调用方拿它去比对/拼接，
	 * 而<b>不要</b>各自写一遍 {@code indexOf}：两处判据不一致时的形态是
	 * "这次改动落到了标记之外"，而那时用户的正文已经被改掉了（看不出、也回不来）。
	 *
	 * <p>成对要求是刻意的：只有开始标记（或只有结束标记）时返回 {@code null}，
	 * 调用方据此走"重新贴一个完整段落"那条路，而不是把正文从半截标记处切开。
	 */
	public String supplementSegment() {
		if (content == null) {
			return null;
		}
		int start = content.indexOf(SUPPLEMENT_START);
		if (start < 0) {
			return null;
		}
		int end = content.indexOf(SUPPLEMENT_END, start + SUPPLEMENT_START.length());
		if (end < 0) {
			return null;
		}
		return content.substring(start, end + SUPPLEMENT_END.length());
	}

	/** 这条技能的自补充段里有没有 {@code 任务 #id} 那条记录（幂等判据，见 {@link #RULE_BLOCK_TASK_PREFIX}）。 */
	public boolean hasSupplementFor(Long taskId) {
		if (taskId == null) {
			return false;
		}
		String segment = supplementSegment();
		return segment != null && segment.contains(RULE_BLOCK_TASK_PREFIX + taskId + RULE_BLOCK_TASK_SUFFIX);
	}

	public AgentSkill {
		if (ownerId == null) {
			throw new IllegalArgumentException("拥有者不能为空 —— 一条没有主人的技能，它的列表与改删判权都没有主语");
		}
		folder = required(folder, MAX_FOLDER_CHARS, "文件夹目录");
		name = required(name, MAX_NAME_CHARS, "技能名");
		description = optional(description, MAX_DESCRIPTION_CHARS, "说明");
	}

	/**
	 * 界面上那一格显示谁：有姓名用姓名，没有（账号行不在）退回 id。
	 *
	 * <p>只有这一处判据 —— 列表上的「拥有者」与「最后修改人」两列都从这里取，
	 * 各写一遍 {@code name == null ? id : name} 的话，两处总有一处会漏掉那一支。
	 */
	public String displayOwner() {
		return ownerName == null || ownerName.isBlank() ? "未知用户 #" + ownerId : ownerName;
	}

	/** 最后修改人那一格的显示名（判据同上）。{@code lastModifiedBy} 为空时才落到"未知用户"。 */
	public String displayLastModifiedBy() {
		if (lastModifiedByName != null && !lastModifiedByName.isBlank()) {
			return lastModifiedByName;
		}
		return lastModifiedBy == null ? "未知用户" : "未知用户 #" + lastModifiedBy;
	}

	private static String required(String value, int max, String what) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(what + "不能为空");
		}
		return fit(value.strip(), max, what);
	}

	private static String optional(String value, int max, String what) {
		return value == null || value.isBlank() ? null : fit(value.strip(), max, what);
	}

	private static String fit(String value, int max, String what) {
		if (value.length() > max) {
			throw new IllegalArgumentException("%s过长（%d 字符 > 上限 %d）".formatted(what, value.length(), max));
		}
		return value;
	}

}
