package ai.agent.service.agent.approval;

import ai.agent.entity.agent.approval.ToolApprovalRecord;
import ai.agent.mapper.ToolApprovalMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@link ToolApprovalStore} 的存储实现 —— <b>SQL 在 {@link ToolApprovalMapper} 里，本类只管语义</b>。
 *
 * <h2>这里还剩什么（以及为什么留下）</h2>
 * <ul>
 *   <li>{@link #fit} —— 超长参数的**截断并留标记**。它不是 SQL 的事，
 *       而是"人批准的是他看到的那个调用"这条规矩的落点（理由见方法注释）；</li>
 *   <li>异常包装 —— 把 {@link DataAccessException} 转成 {@link ApprovalStoreException}，
 *       让端点能分清"是审批库坏了"还是别的地方坏了；</li>
 *   <li>{@link #decide} 的返回值语义 —— 它是 HITL 的不变式（先写者胜）。</li>
 * </ul>
 *
 * <h2>建表与连接池：本类不再管了</h2>
 * 表由 {@code spring.sql.init} 在启动时统一建（脚本清单在 {@code application.properties}），
 * 连接由全应用唯一的那个 {@code DataSource} 提供。
 * 原先这里做过两件现在已删除的事，各自的理由值得留一句：
 * <ul>
 *   <li><b>自己建第二个 H2 池</b> —— 已删。同一个库上开两个池的代价是"进程的连接上限
 *       乘以池数"，而现象是偶发连不上、排查方向却会跑到"是不是连接泄漏"；</li>
 *   <li><b>构造器里塞一个用不到的 {@code JdbcAgentRunStore} 参数来保证"先建表"</b> ——
 *       已删。那是给"启动顺序由扫描顺序决定"打的补丁，现在顺序由
 *       {@code spring.sql.init} 这一个阶段负责，不再需要靠构造器参数表达。</li>
 * </ul>
 */
@Component
public class JdbcToolApprovalStore implements ToolApprovalStore {

	private static final Logger log = LoggerFactory.getLogger(JdbcToolApprovalStore.class);

	/** 参数列的字符上限。超过时<b>截断并留下标记</b>，而不是静默截掉（见下面的 {@link #fit}）。 */
	static final int ARGUMENTS_MAX_CHARS = 4000;

	private final ToolApprovalMapper mapper;

	public JdbcToolApprovalStore(ToolApprovalMapper mapper) {
		this.mapper = mapper;
	}

	@Override
	public void save(ToolApprovalRecord record) {
		try {
			mapper.insert(record.id(), record.runId(), record.stepIndex(), record.toolName(),
					fit(record.arguments()), record.hints(), record.summary(),
					// 那一列在 DDL 里是 NOT NULL DEFAULT ''：null 与空串都会出现（外部链路没有档位），
					// 这里统一成空串，理由是"空串表示取不到"这条规矩只在**写入侧**定义一次
					record.permissionNote() == null ? "" : record.permissionNote(),
					record.status(), record.requestedAt());
		}
		catch (DataAccessException e) {
			// 抛而不是吞：审批记录写不进去意味着"这次写操作没有可审计的凭据"。
			// 继续跑下去，人批准的动作在库里不留痕迹 —— 而那是 HITL 最不该发生的事
			throw new ApprovalStoreException("写入人工审批记录失败 id=" + record.id(), e);
		}
	}

	@Override
	public boolean decide(String id, ApprovalStatus status, Instant at, String decidedBy, String note) {
		try {
			// 只有第一个写入才生效 —— 不变式在 XML 的 WHERE 里（status = 'PENDING'），
			// 本方法负责把"没写进去"如实回报给调用方（false），而不是当成成功
			return mapper.decide(id, status, at, decidedBy, note) == 1;
		}
		catch (DataAccessException e) {
			throw new ApprovalStoreException("更新人工审批记录失败 id=" + id, e);
		}
	}

	@Override
	public Optional<ToolApprovalRecord> find(String id) {
		return Optional.ofNullable(read(() -> mapper.findById(id), "查询人工审批记录失败 id=" + id));
	}

	@Override
	public List<ToolApprovalRecord> pending() {
		return read(mapper::pending, "查询待批记录失败");
	}

	@Override
	public List<ToolApprovalRecord> ofRun(String runId) {
		return read(() -> mapper.ofRun(runId), "查询人工审批记录失败 runId=" + runId);
	}

	@Override
	public List<ToolApprovalRecord> recent(int limit) {
		// 上下界在 Java 侧定：LIMIT 的合法性是**调用契约**的一部分，
		// 不该指望数据库对 0 / 负数报错（H2 与 MySQL 对它的处理并不一致）
		return read(() -> mapper.recent(Math.max(1, limit)), "查询最近的人工审批记录失败");
	}

	/** 读操作的异常包装（四处共用；写操作各自包，因为它们的失败消息要看 id）。 */
	private <T> T read(java.util.function.Supplier<T> call, String whatFailed) {
		try {
			return call.get();
		}
		catch (DataAccessException e) {
			throw new ApprovalStoreException(whatFailed, e);
		}
	}

	/**
	 * 超长参数的处理：<b>截断并留下标记</b>。
	 *
	 * <p>不静默截断的理由与本项目其他所有截断一致（见 {@code ToolFeedbackFormatter} 规则 3）：
	 * 一段被砍掉尾巴的参数看起来完全正常，而人据此做出的批准决定
	 * 针对的是<b>他看到的那个调用</b>、不是真正会执行的那个。
	 * 标记写出来，人才知道"我看到的不是全部"。
	 */
	private static String fit(String arguments) {
		if (arguments == null) {
			return null;
		}
		if (arguments.length() <= ARGUMENTS_MAX_CHARS) {
			return arguments;
		}
		return arguments.substring(0, ARGUMENTS_MAX_CHARS)
				+ "\n…（参数超过 " + ARGUMENTS_MAX_CHARS + " 字符，此处截断；完整参数见运行日志）";
	}

	/** 存储层异常。与 {@code AgentStoreException} 分开，便于断言"是哪一侧坏了"。 */
	public static class ApprovalStoreException extends RuntimeException {

		public ApprovalStoreException(String message, Throwable cause) {
			super(message, cause);
		}

	}

}
