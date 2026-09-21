package ai.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * {@code agent_task_confirm} 表的 SQL。
 *
 * <h2>为什么没有"改确认项内容"的方法</h2>
 * 确认事项与选项集在生成时定下，之后只有答复会变（{@link #answer}）。
 * 一条能被事后改写的问题，"他当时确认的是什么"就没有答案了 —— 所以改内容的 SQL 刻意不存在。
 */
@Mapper
public interface AgentTaskConfirmMapper {

	/** 生成一条确认项。它建出来就是"还没答"（{@code answer} 为 NULL），没有可传的答复参数。 */
	int insert(@Param("taskId") Long taskId, @Param("stepIndex") int stepIndex,
			@Param("confirmType") String confirmType, @Param("description") String description,
			@Param("optionSet") String optionSet);

	/** 刚插入那一行的自增主键（与 {@code AgentTaskMapper#lastInsertId} 同一条规矩）。 */
	Long lastInsertId();

	MapperRows.AgentTaskConfirmRow findById(@Param("id") Long id);

	/** 一个任务的全部确认项，按步骤排（详情页从上往下就是流转顺序）。 */
	List<MapperRows.AgentTaskConfirmRow> listByTask(@Param("taskId") Long taskId);

	/**
	 * 答复。
	 *
	 * <p><b>{@code answer IS NULL} 这个条件是并发保护，不是优化</b>：
	 * 两条请求（两次点击、或人与另一个脚本）同时答复同一条确认项时，
	 * "先查再写"的写法两条都能过检查，后写的会静默覆盖先写的 ——
	 * 而覆盖之后界面上只剩一个答案，没有任何痕迹说明它曾经是另一个。
	 * 条件更新把这件事交给数据库：只有一条能改到行，返回 0 的那一条由服务层报 409。
	 */
	int answer(@Param("id") Long id, @Param("answer") String answer);

}
