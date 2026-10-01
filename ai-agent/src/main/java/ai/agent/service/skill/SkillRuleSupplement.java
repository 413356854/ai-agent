package ai.agent.service.skill;

import ai.agent.config.SkillPromptProperties;
import ai.agent.entity.agent.skill.AgentSkill;
import ai.agent.entity.agent.task.AgentTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>把一段"经验规则"补进技能正文里那道只属于系统的段落</b>。
 *
 * <h2>它要守住的那条边界</h2>
 * 技能正文是<b>用户的内容</b>，而"这条任务问过什么、答过什么、下次别再问"是系统写进去的。
 * 两者混在一段文本里，所以系统只在自己那两个标记之间写：
 * {@link AgentSkill#SUPPLEMENT_START} 与 {@link AgentSkill#SUPPLEMENT_END} ——
 * 标记之外（含用户在标记前面、后面、以及标记被删掉之后补的任何文字）<b>一个字节都不动</b>。
 * 这不是"尽量不要动"，而是这个类唯一的形态：新正文永远是
 * "标记之前那一份 + 新的标记段 + 标记之后那一份"，没有任何一条分支会去改另外两段。
 *
 * <h2>没有成对标记时怎么办 —— 在末尾重贴一段，而不是修</h2>
 * 用户可能把标记删了、也可能只删了一半。那时 {@link AgentSkill#supplementSegment()} 返回空，
 * 这一条走的是"把完整的标记段<b>接在正文末尾</b>"，已有的正文原样保留 ——
 * 刻意<b>不</b>去"补一个结束标记"：那等于从一个半截标记处把正文切开，
 * 切点之后的内容会被吞进系统段落里，而那正是这个类要防的事。
 *
 * <h2>为什么写库走条件更新（{@link SkillStore#replaceContent}）</h2>
 * 这一条的形态是"读出来 → 拼接 → 写回去"，两次同时发生（两个人同时验收、
 * 或有人正在界面上改这条技能）会静默丢掉其中一次。条件更新让拿着过期正文的那一次改不到行，
 * 这里据此报一句"刚被别处改过"，由调用方决定是重试还是放弃。
 *
 * <h2>这个类抛异常，但不自己吞</h2>
 * 它回答的是"写没写成"，而"失败了算什么"是调用方那件业务的事
 * （见 {@code TaskRuleDigest}：那里吞掉并记一条 WARN，验收已经完成，不受影响）。
 */
@Component
public class SkillRuleSupplement {

	private static final Logger log = LoggerFactory.getLogger(SkillRuleSupplement.class);

	/** 每条补充内容的标题里的时间格式：本地时间、到分钟。 */
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	/**
	 * 自补充段里"更早的规则被丢掉了多少条"那句话的开头。
	 *
	 * <p>它必须留在段里：静默丢弃会让模型与人都以为"规则就这几条"，
	 * 而丢掉的往往是**最早**总结出来的那几条。
	 */
	private static final String DROPPED_NOTE_PREFIX = "（已按上限丢弃 ";

	private final SkillStore skills;

	private final SkillDirectory skillDirectory;

	private final SkillPromptProperties properties;

	public SkillRuleSupplement(SkillStore skills, SkillDirectory skillDirectory, SkillPromptProperties properties) {
		this.skills = skills;
		this.skillDirectory = skillDirectory;
		this.properties = properties;
	}

	/**
	 * 把这一条任务的规则补进任务人的技能。
	 *
	 * @param task 这条任务（用它的 id、任务人、描述）
	 * @param rules 已经汇成"一句话一条"的规则（空的那一次不该走到这里，由调用方在前面拦住）
	 */
	public void append(AgentTask task, List<String> rules) {
		List<String> cleaned = clean(rules);
		if (cleaned.isEmpty()) {
			log.info("任务 #{} 的自补充规则是空的，不写技能", task.id());
			return;
		}
		if (task.assigneeId() == null) {
			log.warn("任务 #{} 没有任务人，写不了技能（技能要落在某个人角色的目录下）", task.id());
			return;
		}
		AgentSkill skill = target(task);
		if (skill == null) {
			return;
		}
		if (skill.hasSupplementFor(task.id())) {
			// 幂等：同一条任务只补一次。重复补充在界面上表现为"两条一样的规则"，
			// 那看起来像模型啰嗦，而不像一次重复写入 —— 所以判据落在正文里（见 AgentSkill 里的说明）
			log.info("任务 #{} 的经验规则已经在技能 #{} 的自补充段里了，跳过", task.id(), skill.id());
			return;
		}
		String block = block(task, cleaned);
		String merged = merge(skill, block, properties.supplementMaxChars());
		if (!skills.replaceContent(skill.id(), merged, skill.content(), task.assigneeId())) {
			throw new SkillConflictException(("技能 #%d（%s）的正文刚刚被别处改过，这次的经验规则没有写进去 —— "
					+ "短时间内重试一次即可（这条任务的验收已经完成，不受影响）")
					.formatted(skill.id(), skill.name()));
		}
		log.info("任务 #{} 的经验规则已补进技能 #{}（{} / {}）：本次 {} 条，正文 {} 字符", task.id(), skill.id(),
				skill.folder(), skill.name(), cleaned.size(), merged.length());
	}

	/**
	 * 这一条该写进哪条技能；没有去处时返回 {@code null}（并已记下原因）。
	 *
	 * <h2>挑技能的优先顺序</h2>
	 * <ol>
	 *   <li>任务人拥有的技能里，名字等于 {@code ai.agent.skill.supplement-skill-name} 的那一条
	 *       —— 命中多条（同一个人在不同文件夹下可以重名）时取<b>第一条</b>：两条同名技能是
	 *       两份互不相干的内容，规则该住在"配置里写的那一条"上；</li>
	 *   <li>否则任务人只有一条技能时，就是它（"这个人只写了这一条，那它显然是这件事的家"）；</li>
	 *   <li>否则（他有好几条、名字都不对）<b>一条都不写</b>，日志里列出候选名字与那个配置项。</li>
	 * </ol>
	 * 第三条刻意选"不写"：随便挑一条写进去之后，它会在下一次任务里被模型读到，
	 * 而它可能是一条与这件事毫无关系的技能。那种错在界面上看不出来，
	 * 只表现为模型偶尔说一句不搭界的话 —— 比"这一次没补充"难查得多。
	 *
	 * <p>一条都没有时才建：文件夹取任务人角色目录前缀里排序后的第一条。没有角色 / 没有目录前缀时
	 * 也<b>不写</b> —— 技能按"文件夹落在角色目录前缀内"筛，落在谁都取不到的目录里等于没写，
	 * 而它会在列表上安安静静地躺着。
	 */
	private AgentSkill target(AgentTask task) {
		List<AgentSkill> owned = skills.skillsOfOwner(task.assigneeId());
		String wanted = properties.supplementSkillName().toLowerCase(Locale.ROOT);
		for (AgentSkill skill : owned) {
			if (skill.name().toLowerCase(Locale.ROOT).equals(wanted)) {
				return skill;
			}
		}
		if (owned.size() == 1) {
			return owned.get(0);
		}
		if (!owned.isEmpty()) {
			log.warn("任务 #{} 的经验规则没有去处：任务人 #{} 有 {} 条技能，没有一条叫「{}」—— "
					+ "随便挑一条写进去会让规则落到一条无关的技能里（界面上看不出来）。"
					+ "候选：{}。要么把规则该住的那条技能改名成配置里那个名字"
					+ "（ai.agent.skill.supplement-skill-name），要么把他名下的技能收敛到一条",
					task.id(), task.assigneeId(), owned.size(), properties.supplementSkillName(),
					owned.stream().map(AgentSkill::name).toList());
			return null;
		}
		List<String> folders = skillDirectory.foldersForExecutor(task.assigneeId());
		if (folders.isEmpty()) {
			log.warn("任务 #{} 的经验规则没有去处：任务人 #{} 没有任何角色目录前缀 —— "
					+ "技能按「文件夹落在角色目录里」筛，落在谁都取不到的目录下等于没写。"
					+ "给这个人的角色配一条目录前缀，或把他名下的技能建在已授权的目录里",
					task.id(), task.assigneeId());
			return null;
		}
		AgentSkill created = skills.create(new AgentSkill(null, folders.get(0), properties.supplementSkillName(),
				"由系统在 agent 任务验收通过后自动追加的经验规则（只动「" + AgentSkill.SUPPLEMENT_START
						+ "」与「" + AgentSkill.SUPPLEMENT_END + "」之间，其余正文属于这条技能的拥有者）",
				AgentSkill.initialSupplementContent(), task.assigneeId(), null, task.assigneeId(), null, null, null));
		log.info("任务 #{} 的经验规则：新建了技能 #{}（{} / {}，拥有者 #{}）", task.id(), created.id(),
				created.folder(), created.name(), created.ownerId());
		return created;
	}

	// ------------------------------------------------------------------
	//  正文的拼装：标记之外一个字节都不动
	// ------------------------------------------------------------------

	/**
	 * 新正文 = 标记之前那一份 + 新的标记段 + 标记之后那一份。
	 *
	 * <p>三条分支只有一条会走，而<b>每一条都保证标记之外原样保留</b>：
	 * <ul>
	 *   <li>有标记段 → 之前 + 新标记段 + 之后（"之后"通常是空的，但用户完全可能在标记后面接着写，
	 *       那一段必须跟在新标记段后面，而不是被吞掉）；</li>
	 *   <li>没有标记段、正文也空 → 只有一段新的标记段；</li>
	 *   <li>没有标记段、但有正文 → 正文原样 + 空行 + 新的标记段（接在末尾，绝不插进正文中间）。</li>
	 * </ul>
	 */
	private static String merge(AgentSkill skill, String block, int maxChars) {
		String segment = skill.supplementSegment();
		String segmentBody = segment(segment, block, maxChars);
		String original = skill.content();
		if (segment == null) {
			String kept = original == null ? "" : original.strip();
			return kept.isEmpty() ? segmentBody : kept + "\n\n" + segmentBody;
		}
		int start = original.indexOf(segment);
		String before = original.substring(0, start).stripTrailing();
		String after = original.substring(start + segment.length()).strip();
		String merged = (before.isEmpty() ? "" : before + "\n\n") + segmentBody;
		return after.isEmpty() ? merged : merged + "\n\n" + after;
	}

	/** 两个标记之间那一段（含两个标记）：占位行被本次的块替换；已有内容则把新块接在末尾。 */
	private static String segment(String oldSegment, String block, int maxChars) {
		String inner = innerOf(oldSegment);
		String merged = inner.isEmpty() ? block : inner + "\n\n" + block;
		return AgentSkill.SUPPLEMENT_START + "\n" + trim(merged, maxChars) + "\n" + AgentSkill.SUPPLEMENT_END;
	}

	/** 旧标记段里两个标记之间的内容（不是标记段 / 只有占位行时给空串）。 */
	private static String innerOf(String oldSegment) {
		if (oldSegment == null) {
			return "";
		}
		int start = oldSegment.indexOf(AgentSkill.SUPPLEMENT_START);
		int end = oldSegment.lastIndexOf(AgentSkill.SUPPLEMENT_END);
		if (start < 0 || end < 0) {
			return "";
		}
		String inner = oldSegment.substring(start + AgentSkill.SUPPLEMENT_START.length(), end).strip();
		return AgentSkill.SUPPLEMENT_PLACEHOLDER.equals(inner) ? "" : inner;
	}

	/**
	 * 超上限时<b>从最旧的一块开始整块丢</b>。
	 *
	 * <p>丢这件事必须在段里说出来（段首那句"已按上限丢弃 N 条"）：不说的话，
	 * "规则里就这几条"会被当成事实，而丢掉的正是最早总结出来的那些。
	 *
	 * <p>按"块"（{@link AgentSkill#RULE_BLOCK_TASK_PREFIX} 那一行起、到下一块之前）丢，
	 * 不按行丢：一条规则被从中间切开之后，它读起来仍像一条完整的规则。
	 * 只剩最后一块时不再丢 —— 一条很长的规则好过零条规则，代价是这一段可能超过上限，
	 * 那由 WARN 说出来。
	 */
	private static String trim(String inner, int maxChars) {
		if (inner.length() <= maxChars) {
			return inner;
		}
		log.warn("自补充段 {} 字符超过上限 {}，从最旧的一块开始丢", inner.length(), maxChars);
		List<String> blocks = blocksOf(inner);
		int dropped = 0;
		while (blocks.size() > 1 && joined(blocks, dropped).length() > maxChars) {
			blocks = new ArrayList<>(blocks.subList(1, blocks.size()));
			dropped++;
		}
		return joined(blocks, dropped);
	}

	/** 段里剩下的块（含那句"已丢弃 N 条"）。 */
	private static String joined(List<String> blocks, int dropped) {
		String body = String.join("\n\n", blocks).strip();
		return dropped <= 0 ? body : DROPPED_NOTE_PREFIX + dropped + " 条更早的规则）\n" + body;
	}

	/**
	 * 把整段切成"一块一条"。
	 *
	 * <p>切点是每一块开头那行 {@link AgentSkill#RULE_BLOCK_TASK_PREFIX}（那条 HTML 注释就是
	 * "块从哪儿开始"的唯一标识，它同时是幂等判据，见 {@link AgentSkill#hasSupplementFor}）。
	 * 一段里一块都没有（手工写坏的那种）时返回整段本身：那时没有"更旧的"可丢。
	 */
	private static List<String> blocksOf(String inner) {
		List<String> blocks = new ArrayList<>();
		StringBuilder current = null;
		for (String line : inner.split("\n")) {
			if (line.startsWith(AgentSkill.RULE_BLOCK_TASK_PREFIX)) {
				if (current != null) {
					blocks.add(current.toString().strip());
				}
				current = new StringBuilder();
			}
			if (current == null) {
				// 第一块之前的内容（上一次留下的"已丢弃 N 条"那句）：它不参与预算、也不该被当成一块
				continue;
			}
			current.append(line).append('\n');
		}
		if (current != null) {
			blocks.add(current.toString().strip());
		}
		return blocks.isEmpty() ? List.of(inner.strip()) : blocks;
	}

	// ------------------------------------------------------------------
	//  本次那一块
	// ------------------------------------------------------------------

	/** 本次要写进标记之间的那一块：幂等标识 + 时间与任务标题 + 逐条规则。 */
	private static String block(AgentTask task, List<String> rules) {
		StringBuilder sb = new StringBuilder();
		sb.append(AgentSkill.RULE_BLOCK_TASK_PREFIX).append(task.id())
				.append(AgentSkill.RULE_BLOCK_TASK_SUFFIX).append('\n');
		sb.append("#### ").append(LocalDateTime.now().format(STAMP)).append(" · 任务 #").append(task.id())
				.append("（").append(oneLine(task.description())).append("）").append('\n');
		for (String rule : rules) {
			sb.append("- ").append(rule).append('\n');
		}
		return sb.toString().strip();
	}

	/**
	 * 规则文本的清洗：去空、去重、保持顺序；带着段落标记的整条丢掉。
	 *
	 * <p>整条丢掉而不是把那几个字删掉：删字之后剩下的句子会变成一句读不通的话，
	 * 而它照样会被下一次任务当成一条规则用。
	 */
	private static List<String> clean(List<String> rules) {
		if (rules == null) {
			return List.of();
		}
		List<String> cleaned = new ArrayList<>();
		for (String rule : rules) {
			if (rule == null || rule.isBlank()) {
				continue;
			}
			String text = rule.strip().replace("\r\n", "\n");
			if (text.contains(AgentSkill.SUPPLEMENT_END) || text.contains(AgentSkill.SUPPLEMENT_START)) {
				log.warn("自补充的一条规则里带着段落标记，整条丢弃：{}",
						text.length() > 80 ? text.substring(0, 80) + "…" : text);
				continue;
			}
			if (!cleaned.contains(text)) {
				cleaned.add(text);
			}
		}
		return List.copyOf(cleaned);
	}

	/** 一段文字压成一行（进标题用）：换行会让 Markdown 的标题断在中间。 */
	private static String oneLine(String text) {
		String flat = text == null ? "" : text.strip().replaceAll("\\s+", " ");
		return flat.length() <= 40 ? flat : flat.substring(0, 40) + "…";
	}

}
