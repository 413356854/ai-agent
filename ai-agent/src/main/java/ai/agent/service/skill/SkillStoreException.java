package ai.agent.service.skill;

import org.springframework.dao.DataAccessException;

/**
 * 技能读写时数据库出的错（表不存在、SQL 与库对不上、连接断了）。
 *
 * <p>它存在的唯一理由是<b>把"在做什么"带上去</b>：直接把 {@link DataAccessException}
 * 抛出去，端点上只会看到一句 {@code BadSqlGrammarException}，
 * 而它不告诉人这次是在读技能列表还是在读某个账号的目录。
 */
public class SkillStoreException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public SkillStoreException(String message) {
		super(message);
	}

	public SkillStoreException(String message, Throwable cause) {
		super(message, cause);
	}

}
