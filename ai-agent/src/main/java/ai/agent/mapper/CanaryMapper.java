package ai.agent.mapper;

import ai.agent.service.canary.CanaryRunBucket;
import ai.agent.service.canary.CanaryStore;
import ai.agent.service.prompt.PromptKind;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * {@code prompt_canary} 表 + {@code agent_step} 的分流观测
 * （原先手写在 {@code JdbcCanaryStore} 里）。
 */
@Mapper
public interface CanaryMapper {

	MapperRows.CanaryRow activeOfKind(@Param("kind") PromptKind kind);

	MapperRows.CanaryRow latestOfKind(@Param("kind") PromptKind kind);

	/**
	 * 开一次灰度。
	 *
	 * <p><b>刻意是裸 INSERT，不是 upsert</b>：撞主键就是"已经有一次进行中的实验"。
	 * 写成 {@code ON DUPLICATE KEY UPDATE} 会让"开第二次"静默覆盖第一次 ——
	 * 而覆盖掉的是一次正在进行的实验，覆盖之后它从历史上就消失了。
	 *
	 * @throws org.springframework.dao.DuplicateKeyException 已经有进行中的那一条
	 */
	int insert(@Param("kind") PromptKind kind, @Param("candidateVersion") int candidateVersion,
			@Param("weightPercent") int weightPercent, @Param("by") String by, @Param("now") Instant now,
			@Param("note") String note);

	/** 停止：只有还在进行中的那条能被停，返回受影响行数（0 = 本来就没有进行中的）。 */
	int stop(@Param("kind") PromptKind kind, @Param("endReason") String endReason, @Param("by") String by,
			@Param("now") Instant now);

	/**
	 * 按（提示词指纹, 灰度变体）统计窗口内的运行数。
	 *
	 * <p>两个观测查询分开而不是合成一条"大而全"的：见 {@code JdbcCanaryStore} 类注释
	 * （合成一条会引入"同一次运行不会出现在两个变体里"这条隐含前提）。
	 */
	List<MapperRows.CanaryRunBucketRow> runsByFingerprintAndVariant(@Param("since") Instant since);

	/**
	 * 按灰度变体统计窗口内的运行数。
	 *
	 * <p>返回类型里带 {@code fingerprint} 是为了与上面那条共用同一个 record ——
	 * 这一档粒度上没有指纹，所以它恒为空串（服务层只把 runs 相加）。
	 */
	List<MapperRows.CanaryRunBucketRow> runsByVariant(@Param("since") Instant since);

}

