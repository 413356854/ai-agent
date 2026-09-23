package ai.agent.service.skill;

import ai.agent.entity.agent.skill.AgentSkill;
import ai.agent.mapper.AgentSkillMapper;
import ai.agent.mapper.MapperRows;
import ai.agent.mapper.UserDirectoryMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.List;

/**
 * {@link SkillStore} 的 JDBC 实现 —— <b>每个方法就是一次 mapper 调用</b>。
 *
 * <p>与 {@code JdbcPermissionStore} 同一条取舍：这一层薄，但它把
 * "哪个 mapper 回答哪个问题"写下来了一次，而且读失败会被包成带"在做什么"的
 * {@link SkillStoreException} —— 否则端点只能看到一句 {@code BadSqlGrammarException}，
 * 而它不告诉人是在读哪一张表。
 *
 * <h2>"先插再取键"这两步为什么在这里、不在服务层</h2>
 * 取键必须紧接着插入、在同一条连接上（见 {@link AgentSkillMapper#lastInsertId()}），
 * 所以这两步之间不能有别的东西插进来。放在一个方法里，调用方就没有把它们拆开的机会。
 */
@Component
public class JdbcSkillStore implements SkillStore {

	private final AgentSkillMapper skills;

	private final UserDirectoryMapper userDirectories;

	public JdbcSkillStore(AgentSkillMapper skills, UserDirectoryMapper userDirectories) {
		this.skills = skills;
		this.userDirectories = userDirectories;
	}

	@Override
	public List<AgentSkill> skills() {
		return read(skills::list, "读技能列表失败").stream()
				.map(MapperRows.AgentSkillRow::toDomain)
				.toList();
	}

	@Override
	public List<AgentSkill> skillsOfOwner(Long ownerId) {
		return read(() -> skills.byOwner(ownerId), "读技能列表失败 ownerId=" + ownerId).stream()
				.map(MapperRows.AgentSkillRow::toDomain)
				.toList();
	}

	@Override
	public AgentSkill findById(Long id) {
		if (id == null) {
			return null;
		}
		MapperRows.AgentSkillRow row = read(() -> skills.findById(id), "读技能失败 id=" + id);
		return row == null ? null : row.toDomain();
	}

	@Override
	public AgentSkill create(AgentSkill skill) {
		Long id;
		try {
			skills.insert(skill.folder(), skill.name(), skill.description(), skill.content(), skill.ownerId(),
					skill.lastModifiedBy());
			// 取键必须是紧接着的下一句（LAST_INSERT_ID() 是连接级的，中间隔一次别的写就会取错）
			id = skills.lastInsertId();
		}
		catch (DataAccessException e) {
			// ⚠️ 撞唯一键必须在这里就翻成 SkillConflictException，不能包成 SkillStoreException：
			// 把它包掉之后，服务层那一层再也分不出"撞名字"（409）与"库出了别的问题"（500），
			// 而这个区分正是界面上的下一步动作（换个名字 / 去查库）
			throw writeFailure("创建技能失败 name=" + skill.name(),
					"「%s」在文件夹「%s」下已经有一条同名的技能 —— 同一个人的同一个文件夹下技能名是唯一键；"
							+ "要改的是那一条，或者换一个名字"
							.formatted(skill.name(), skill.folder()), e);
		}
		if (id == null) {
			throw new IllegalStateException("插入技能之后没有拿到自增主键 —— "
					+ "SELECT LAST_INSERT_ID() 没有写进载体（写法与两条实测的弯路见 AgentSkillMapper 的注释）。"
					+ "这一行已经写进库里了，但调用方拿不到它的 id");
		}
		// 重新读回来：响应里的两个时间与两个姓名只有库里那一份说了算（见 SkillStore#create）
		AgentSkill saved = findById(id);
		if (saved == null) {
			throw new SkillStoreException("创建技能之后读不回 id=" + id + "（插入成功但这一行取不到）");
		}
		return saved;
	}

	@Override
	public AgentSkill update(Long id, String folder, String name, String description, String content,
			Long lastModifiedBy) {
		try {
			skills.update(id, folder, name, description, content, lastModifiedBy);
		}
		catch (DataAccessException e) {
			// 同 create：撞唯一键在这里翻成 409（改成一个已经被占用的名字是最常见的一种）
			throw writeFailure("更新技能失败 id=" + id,
					"「%s」在文件夹「%s」下已经有一条同名的技能".formatted(name, folder), e);
		}
		AgentSkill saved = findById(id);
		if (saved == null) {
			throw new SkillStoreException("更新技能之后读不回 id=" + id + "（更新成功但这一行取不到）");
		}
		return saved;
	}

	@Override
	public boolean delete(Long id) {
		int affected;
		try {
			affected = skills.delete(id);
		}
		catch (DataAccessException e) {
			throw new SkillStoreException("删除技能失败 id=" + id, e);
		}
		return affected > 0;
	}

	@Override
	public List<String> folderPrefixesOfUser(Long userId) {
		if (userId == null) {
			return List.of();
		}
		return read(() -> userDirectories.folderPrefixesOfUser(userId), "读账号目录失败 userId=" + userId);
	}

	/**
	 * 读操作的异常包装。
	 *
	 * <p>与 {@code JdbcPermissionStore#read} 逐条相同：{@link DataAccessException} 换成带
	 * "在做什么"的 {@link SkillStoreException}。
	 */
	private <T> T read(java.util.function.Supplier<T> call, String whatFailed) {
		try {
			return call.get();
		}
		catch (DataAccessException e) {
			throw new SkillStoreException(whatFailed, e);
		}
	}

	/**
	 * 写失败的翻译：<b>撞唯一键与"别的原因"分成两种异常</b>。
	 *
	 * <p>判据与 {@code DefaultPermissionService#conflictOr} 逐条相同，同样三档并存：
	 * Spring 的异常翻译（{@link DataIntegrityViolationException}）接住标准 SQLState，
	 * 底层 {@link SQLException} 接住 H2 的 23505 / 23000 与 MySQL 的 1062
	 * （这两个码不在 Spring 的"重复键"表里）。
	 *
	 * <p>⚠️ 这个判断必须发生在<b>包装最内层</b>（就在 mapper 调用外面）：
	 * 先包成 {@link SkillStoreException} 再往上抛的话，服务层拿到的是一层已经看不出原因的异常，
	 * 于是"撞名字"会以 **500** 的形式出现 —— 而界面上该说的是"换个名字"。
	 * 实测踩过：同名的两次创建都回了 500。
	 */
	private static RuntimeException writeFailure(String whatFailed, String conflictMessage, DataAccessException e) {
		if (e instanceof DataIntegrityViolationException || hasDuplicateSqlState(e)) {
			return new SkillConflictException(conflictMessage);
		}
		return new SkillStoreException(whatFailed, e);
	}

	private static boolean hasDuplicateSqlState(Throwable root) {
		for (Throwable t = root; t != null; t = t.getCause()) {
			if (t instanceof SQLException sql) {
				String state = sql.getSQLState();
				if ("23505".equals(state) || "23000".equals(state) || sql.getErrorCode() == 23505
						|| sql.getErrorCode() == 1062) {
					return true;
				}
			}
			if (t.getCause() == t) {
				break;
			}
		}
		return false;
	}

}
