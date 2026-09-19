package ai.agent.mapper;

import ai.agent.entity.agent.approval.ToolApprovalRecord;
import ai.agent.service.agent.approval.ApprovalStatus;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * {@code agent_tool_approval} 表的 SQL（原先手写在 {@code JdbcToolApprovalStore} 里）。
 *
 * <h2>为什么把这个接口单独放一个包（{@code ai.agent.mapper}）</h2>
 * 它是<b>SQL 与 Java 的边界</b>：接口只描述"查什么/写什么"，具体语句在同名 XML 里。
 * 混在 service 包里的话，"这条链路上到底有哪些 SQL"要翻很多个类才看得全 ——
 * 而这次迁移要回答的第一个问题恰恰是那个。
 *
 * <p>接口方法上的 {@code @Param} 全部显式写出来：参数名是 SQL 里 {@code #{name}} 的凭据，
 * 靠编译器的 {@code -parameters} 保留名字能work，但**一旦哪天编译参数变了，
 * 失败发生在启动期、错误信息却是"找不到参数"** —— 显式写下来就没有这个可能。
 */
@Mapper
public interface ToolApprovalMapper {

	/** 写一条待批/已决记录。参数列在写之前已由调用方截断并留标记（见 store 里的 fit()）。 */
	int insert(@Param("id") String id, @Param("runId") String runId, @Param("stepIndex") Integer stepIndex,
			@Param("toolName") String toolName, @Param("arguments") String arguments, @Param("hints") String hints,
			@Param("summary") String summary, @Param("permissionNote") String permissionNote,
			@Param("status") ApprovalStatus status, @Param("requestedAt") Instant requestedAt);

	/**
	 * 落一个人工/超时的决定。返回受影响行数 ——
	 * <b>调用方必须看返回值</b>：0 表示"这条已经不是 PENDING 了"（另一个人或超时先写了）。
	 */
	int decide(@Param("id") String id, @Param("status") ApprovalStatus status, @Param("decidedAt") Instant decidedAt,
			@Param("decidedBy") String decidedBy, @Param("note") String note);

	ToolApprovalRecord findById(@Param("id") String id);

	List<ToolApprovalRecord> pending();

	List<ToolApprovalRecord> ofRun(@Param("runId") String runId);

	/**
	 * 最近的 N 条。
	 *
	 * <p>⚠️ {@code LIMIT} 用参数绑定（{@code #{limit}}）而不是字符串拼接 ——
	 * 拼接是 SQL 注入面，而这里拼进去的还是一个**来自 HTTP 查询参数**的整数。
	 */
	List<ToolApprovalRecord> recent(@Param("limit") int limit);

}
