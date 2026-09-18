package ai.agent.mapper;

import ai.agent.entity.prompt.PromptVersion;
import ai.agent.service.prompt.PromptKind;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * {@code prompt_version} / {@code prompt_active} 两张表的 SQL
 * （原先手写在 {@code JdbcPromptStore} 里）。
 *
 * <h2>两个方法返回受影响行数，这是刻意的</h2>
 * {@link #updatePointer} 与 {@link #activateFirst} 的返回值是<b>并发语义的载体</b>：
 * "切换生效版本"这条链路的保证全在
 * {@code UPDATE ... WHERE kind = ? AND version = ?}（前置条件）与
 * {@code INSERT}（本来就没有指针）上。把它们包装成 void 会让
 * "前提过期"与"切换成功"变得无法区分 —— 而静默成功会覆盖另一个人的决定。
 */
@Mapper
public interface PromptMapper {

	List<MapperRows.PromptVersionRow> listByKind(@Param("kind") PromptKind kind);

	/** 某一份提示词的当前最大版本号；一版都没有时返回 0（SQL 里用 COALESCE 保证不是 NULL）。 */
	int maxVersion(@Param("kind") PromptKind kind);

	MapperRows.PromptVersionRow findByKindAndVersion(@Param("kind") PromptKind kind, @Param("version") int version);

	/** 当前生效的那一版（{@code prompt_active} 指针 JOIN {@code prompt_version}）。 */
	MapperRows.PromptVersionRow activeOfKind(@Param("kind") PromptKind kind);

	int insertVersion(@Param("kind") PromptKind kind, @Param("version") int version, @Param("content") String content,
			@Param("hash") String hash, @Param("charCount") int charCount,
			@Param("tokenEstimate") int tokenEstimate, @Param("note") String note, @Param("author") String author,
			@Param("forkedFrom") Integer forkedFrom);

	/** 库里还没有生效指针时用它插一条。 */
	int activateFirst(@Param("kind") PromptKind kind, @Param("version") int version);

	/** 带前置条件的切换：{@code expectedVersion} 不是当前值时受影响行数为 0。 */
	int updatePointer(@Param("kind") PromptKind kind, @Param("version") int version,
			@Param("expectedVersion") int expectedVersion);

}

