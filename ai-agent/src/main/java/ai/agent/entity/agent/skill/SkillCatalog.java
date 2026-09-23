package ai.agent.entity.agent.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>这一次运行能用的技能</b> —— 按执行者的角色目录筛出来的一份只读快照。
 *
 * <h2>它存在的理由：技能到现在为止对模型行为的影响是 0</h2>
 * {@code agent_skill} 是一张有主人的配置表，但运行期没有任何调用方读它 ——
 * 也就是说"某个目录下的技能"写进去了，模型既不知道它存在，也拿不到它的正文。
 * 本对象是那条接线的载体：<b>进系统提示词的那一份</b>与<b>工具按名字取正文的那一份</b>
 * 是同一个对象，所以"提示词里列了这个技能、工具却查不到它"结构上不可能发生。
 *
 * <h2>★ 筛选判据只有一条：文件夹落在执行者的角色目录前缀内</h2>
 * 由 {@code SkillDirectory} 用它同一个 {@code RoleDirectory.covers} 算出来（分段比路径，
 * 不是字符串前缀比较）—— 于是"哪些技能进这次运行"与"这次能在哪个目录下干活"
 * 是同一份授权，判据只有一处。
 *
 * <h2>为什么它自带一个容量上限</h2>
 * 这份目录会<b>拼进每一次运行的系统提示词</b>（每一轮模型往返都付一遍 token）。
 * 没有上限时，一个目录下堆了 500 条技能会让每次运行的提示词多出几万 token ——
 * 那是"钱花了、模型还被淹掉"。超上限时截断并<b>把截断这件事写进提示词</b>：
 * 静默截断会让模型以为"目录里就这些"。
 *
 * @param executorId 这次运行代表谁（{@code agent_user.id}）；{@code null} = 没有指定执行者
 * @param executorName 那个人的显示名（提示词里写清"你是按谁的身份在干活"）；没有执行者时为空串
 * @param roles 那个人的角色名（提示词里与执行者一起写出来 —— 技能正是"他的角色给的目录"筛出来的，
 *            不写角色的话，模型看到一份技能却不知道为什么是这几条）
 * @param folders 本次生效的文件夹目录（执行者角色目录前缀的原文，去重）；没有执行者时为空
 * @param skills 本次可用的技能（按文件夹、再按名字排序）
 * @param truncated 是否因为超过条数上限被截断。它与 {@code skills} 是否为空是两件事
 * @param totalMatched 实际匹配到多少条（截断前）—— 报告里要能说清"被拦掉了多少条"
 */
public record SkillCatalog(Long executorId, String executorName, List<String> roles, List<String> folders,
		List<AgentSkill> skills, boolean truncated, int totalMatched) {

	public SkillCatalog {
		roles = List.copyOf(roles);
		folders = List.copyOf(folders);
		skills = List.copyOf(skills);
		executorName = executorName == null ? "" : executorName;
	}

	/** 没有执行者：一条技能都不注入，也没有文件夹。 */
	public static SkillCatalog none() {
		return new SkillCatalog(null, "", List.of(), List.of(), List.of(), false, 0);
	}

	/**
	 * 从已经筛好的技能装配。
	 *
	 * <p>排序与截断都在这里做，<b>不在调用点</b>：顺序决定提示词里那几行的先后，
	 * 而两次读之间顺序不同会让"同一份配置、两次运行"的提示词不一样 ——
	 * 那会让提示词指纹与灰度对照都变得不可解释。
	 *
	 * @param maxSkills 最多注入几条；超出的部分被丢掉并把 {@code truncated} 置真
	 */
	public static SkillCatalog of(Long executorId, String executorName, List<String> roles, List<String> folders,
			List<AgentSkill> matched, int maxSkills) {
		List<AgentSkill> sorted = new ArrayList<>(matched);
		sorted.sort((a, b) -> {
			int byFolder = a.folder().compareToIgnoreCase(b.folder());
			return byFolder != 0 ? byFolder : a.name().compareTo(b.name());
		});
		boolean truncated = maxSkills > 0 && sorted.size() > maxSkills;
		List<AgentSkill> kept = truncated ? List.copyOf(sorted.subList(0, maxSkills)) : List.copyOf(sorted);
		return new SkillCatalog(executorId, executorName, roles, folders, kept, truncated, sorted.size());
	}

	/** 这次运行有没有注入技能目录。 */
	public boolean isEmpty() {
		return skills.isEmpty();
	}

	/**
	 * 按技能名（大小写不敏感）找这次运行能用的那些。
	 *
	 * <p>同名技能是允许存在的（唯一的约束是"同一个人的同一个文件夹下不重名"），
	 * 所以返回的是<b>一份列表</b>而不是一条：调用方要么全给模型、要么让模型用 folder 收窄。
	 */
	public List<AgentSkill> byName(String name) {
		if (name == null || name.isBlank()) {
			return List.of();
		}
		String wanted = name.strip();
		return skills.stream()
				.filter(skill -> skill.name().equalsIgnoreCase(wanted))
				.toList();
	}

	/** 按技能名 + 文件夹找（folder 为空时等价于 {@link #byName(String)}）。 */
	public List<AgentSkill> byNameAndFolder(String name, String folder) {
		List<AgentSkill> hits = byName(name);
		if (folder == null || folder.isBlank()) {
			return hits;
		}
		String wanted = folder.strip();
		return hits.stream().filter(skill -> skill.folder().equalsIgnoreCase(wanted)).toList();
	}

	/** 这次运行能用的技能名（去重、保持顺序）—— 工具拒绝时要把它回填给模型。 */
	public List<String> names() {
		Map<String, String> unique = new LinkedHashMap<>();
		for (AgentSkill skill : skills) {
			unique.putIfAbsent(skill.name().toLowerCase(Locale.ROOT), skill.name());
		}
		return List.copyOf(unique.values());
	}

	/** 某个文件夹下有几条（提示词里按文件夹分组时用）。 */
	public List<AgentSkill> inFolder(String folder) {
		if (folder == null) {
			return List.of();
		}
		return skills.stream().filter(skill -> skill.folder().equals(folder)).toList();
	}

	/** 文件夹去重后的顺序（按原文排序）。 */
	public List<String> sortedFolders() {
		return skills.stream().map(AgentSkill::folder).distinct().sorted().toList();
	}

	/**
	 * 拼进系统提示词的那一段。
	 *
	 * <h2>只给 name 与 description，不给正文</h2>
	 * 正文可能有几万字，全塞进提示词等于每一轮模型往返都付一遍 ——
	 * 而绝大多数运行根本用不上其中任何一条。所以这里是<b>目录</b>：
	 * 想用哪一条，由模型按名字调 {@code get_skill_content} 取回来。
	 *
	 * <p>没有技能时返回空串（调用方据此不加这一段）—— 一个"本次没有可用技能"的小节
	 * 只会白占 token，而且会把"没人配技能"与"技能没生效"混成同一个现象。
	 */
	public String render() {
		if (skills.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		sb.append("## 你当前可以使用的技能\n\n");
		// 执行者与角色写出来：技能是按"他的角色给的目录"筛的，不写这一句的话，
		// 模型看到一份技能却不知道为什么是这几条 —— 而"我为什么能看这个"会影响它怎么用
		sb.append("这一次你代表 ").append(executorLabel());
		if (!roles.isEmpty()) {
			sb.append("，角色是 ").append(String.join("、", roles));
		}
		sb.append("。下面这些技能就来自这些角色的目录前缀，只有名字与说明；\n");
		sb.append("要照着哪一条做，先用 ").append(SKILL_TOOL_NAME).append(" 把它的正文取回来。\n\n");
		for (String folder : sortedFolders()) {
			sb.append("### 文件夹：").append(folder).append('\n');
			for (AgentSkill skill : inFolder(folder)) {
				sb.append("- ").append(skill.name()).append("：").append(describe(skill)).append('\n');
			}
		}
		if (truncated) {
			// 截断必须出现在模型读得到的地方：不说的话，"目录里就这些"会被当成事实，
			// 于是它会用错误的理由放弃某个技能（"没有这个技能"），而不是去问人
			sb.append("\n（本次只列出前 %d 条，共匹配到 %d 条 —— 还有 %d 条没有列出）。\n"
					.formatted(skills.size(), totalMatched, totalMatched - skills.size()));
		}
		return sb.toString().strip();
	}

	/** 报告与日志用的一行说明（不含正文）。 */
	public String describe() {
		if (skills.isEmpty()) {
			return executorId == null ? "未指定执行者 → 本次不注入技能"
					: "%s 的角色目录下没有技能（目录 %d 个）".formatted(executorLabel(), folders.size());
		}
		return "%s：%d 个文件夹 / %d 条技能%s".formatted(executorLabel(), sortedFolders().size(), skills.size(),
				truncated ? "（已截断，共匹配 " + totalMatched + " 条）" : "");
	}

	/** 执行者在提示词与日志里的写法；没有执行者时给一句说明而不是空串。 */
	public String executorLabel() {
		if (executorId == null) {
			return "(没有指定执行者)";
		}
		return executorName.isBlank() ? "用户#" + executorId : executorName + "(#" + executorId + ")";
	}

	/** 工具名。提示词里那句话与工具自己的 {@code name()} 必须一致，所以写成一个常量。 */
	public static final String SKILL_TOOL_NAME = "get_skill_content";

	private static String describe(AgentSkill skill) {
		String description = skill.description();
		return description == null || description.isBlank() ? "(这条技能没有写说明)" : description;
	}

	/** 单条技能在报告里的写法（id 便于界面直接跳去 skill 管理那一页核对）。 */
	public static Map<String, Object> row(AgentSkill skill) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", skill.id());
		row.put("folder", skill.folder());
		row.put("name", skill.name());
		row.put("description", skill.description() == null ? "" : skill.description());
		row.put("ownerId", skill.ownerId());
		row.put("ownerName", skill.displayOwner());
		return row;
	}

}
