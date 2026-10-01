package ai.agent.config;

import ai.agent.entity.agent.skill.AgentSkill;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <b>技能进提示词与进工具的边界，以及"大模型自补充规则"那一件事的参数</b>（前缀 {@code ai.agent.skill}）。
 *
 * <h2>两个上限为什么必须存在</h2>
 * 技能目录<b>拼进每一次运行的系统提示词</b>，而系统提示词在每一轮模型往返里都要付一遍 token。
 * 一个目录下堆了几百条技能时，没有上限的后果不是"崩"，而是
 * <b>每次运行都先花掉几万 token 读一份用不上的目录</b>，同时把真正有用的上下文挤出去。
 * 所以与 {@link WorkspaceFileProperties} 同一条理由：上限防的是"答错"，不是"崩"。
 *
 * <h2>它与 {@code ai.tool.feedback.max-chars} 的分工</h2>
 * 那个是<b>回填层</b>的截断（工具返回值统一过一遍）；
 * 这里是<b>工具自己</b>的截断 —— 自己截才能带上"还有多少没显示、剩下的怎么看"，
 * 而回填层截断只会给一句"内容过长已截断"。
 *
 * @param maxCatalogSkills 系统提示词里最多列出几条技能的 name + desc。
 *            50 条的依据：每条约 60~120 字符（名字 + 一句说明），50 条约 3~6 千字符，
 *            与一次检索回填（约 2000 字符）同量级而略大 —— 再大就会把提示词本身
 *            变成主要成本。超限时截断并<b>把截断写进提示词</b>。
 * @param maxContentChars {@code get_skill_content} 单次返回的正文上限。
 *            6000 字符的依据：它明显低于回填上限（{@code ai.tool.feedback.max-chars}，默认 2000）
 *            之外的那一档预算，同时容得下一份完整的操作手册；正文比它长说明
 *            这条技能本身该拆成几条，而工具会把这个判断写进回填里。
 * @param supplementSkillName <b>大模型自补充规则优先写进哪条技能</b>：任务人拥有的技能里
 *            名字等于它的那一条（不区分大小写）。它不是一个"必须存在"的技能 ——
 *            一条都没有时由平台按这个名字建一条（见 {@code SkillRuleSupplement}），
 *            所以它是"那一段经验规则住哪儿"的地址，而不是一个开关。
 *            ⚠️ 它与 DDL 的 {@code name VARCHAR(128)} 对齐，超长拒绝启动。
 * @param supplementMaxChars 自补充那一段（两个标记之间）的字符上限。
 *            8000 的依据：技能正文通过 {@code get_skill_content} 进模型时会被
 *            {@link #maxContentChars} 截断，而"规则段把用户正文挤到截断线之外"是最坏的一种结果 ——
 *            所以这一段自己有一个上限，超了从最旧的一条开始丢，并把"丢了 N 条"写进那一段。
 *            它量的是<b>标记之间</b>的字符数，不是整篇正文。
 */
@ConfigurationProperties(prefix = "ai.agent.skill")
public record SkillPromptProperties(int maxCatalogSkills, int maxContentChars, String supplementSkillName,
		int supplementMaxChars) {

	public SkillPromptProperties {
		// 允许 0/负数会让"目录永远为空、正文永远取不到"，而那个现象看起来像"技能没生效" ——
		// 与 WorkspaceFileProperties 同一条规矩：写错的配置要在启动时就暴露
		if (maxCatalogSkills <= 0) {
			throw new IllegalArgumentException(
					"ai.agent.skill.max-catalog-skills 必须是正数（收到 %d）".formatted(maxCatalogSkills));
		}
		if (maxContentChars <= 0) {
			throw new IllegalArgumentException(
					"ai.agent.skill.max-content-chars 必须是正数（收到 %d）".formatted(maxContentChars));
		}
		// 空名字不是"不补充"，而是"补进一条名字是空的技能"：那条技能在列表上是一格空白，
		// 而按它的名字永远找不到（判据见 SkillRuleSupplement）—— 与 agent-id 那条同一种错
		if (supplementSkillName == null || supplementSkillName.isBlank()) {
			throw new IllegalArgumentException("ai.agent.skill.supplement-skill-name 必填 —— "
					+ "它是「大模型自补充规则」优先写进的那条技能的名字（没有时由平台按这个名字建一条）");
		}
		supplementSkillName = supplementSkillName.strip();
		if (supplementSkillName.length() > AgentSkill.MAX_NAME_CHARS) {
			throw new IllegalArgumentException("ai.agent.skill.supplement-skill-name 过长（%d 字符 > 上限 %d）—— "
					+ "agent_skill.name 是 VARCHAR(%d)"
					.formatted(supplementSkillName.length(), AgentSkill.MAX_NAME_CHARS, AgentSkill.MAX_NAME_CHARS));
		}
		if (supplementMaxChars <= 0) {
			throw new IllegalArgumentException("ai.agent.skill.supplement-max-chars 必须是正数（收到 %d）—— "
					+ "它是自补充那一段的上限，设成 0/负数等于「一条规则都不留」".formatted(supplementMaxChars));
		}
	}

	/** 代码默认值（与 {@code application.properties} 里那几行逐字一致）。 */
	public static SkillPromptProperties defaults() {
		return new SkillPromptProperties(50, 6000, "【自补充】任务经验规则", 8000);
	}

}
