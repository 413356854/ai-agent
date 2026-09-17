package ai.agent.service.prompt;

import ai.agent.service.impl.agent.store.JdbcAgentRunStore;
import org.h2.jdbcx.JdbcConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link PromptStore} 的 H2 / MySQL 实现（纯 JDBC，与 {@code JdbcAgentRunStore} 同一种做法）。
 *
 * <h2>为什么复用 {@code JdbcAgentRunStore} 的连接池，而不是自己建一个</h2>
 * 这一条是<b>踩过之后才写的</b>（成本看板那一轮）：各自建池在本地跑得通，
 * 而问题要到生产才出现 —— 每个自建池都会打开自己的那几条连接，
 * 池数随"有几个组件要读库"线性增长，最后表现为"连接数不够"，
 * 而排查方向通常先怀疑业务代码。
 * 所以这里通过 {@link JdbcAgentRunStore#pool()} 借同一个池。
 *
 * <h2>为什么建表脚本单独一个文件</h2>
 * {@code db/agent-schema.sql} 是 Agent 运行记录（阶段三）的脚本，而这两张表属于阶段四。
 * 合成一个文件的话，"改这个文件会不会影响那个功能"就变成了每次都要重新判断的问题；
 * 分开之后各自的边界是清楚的。
 */
@Component
public class JdbcPromptStore implements PromptStore {

	private static final Logger log = LoggerFactory.getLogger(JdbcPromptStore.class);

	/** 所有查询共用的列清单 —— 三处 SELECT 各写一份的话，加一列时很容易漏掉其中一处。 */
	private static final String COLUMNS =
			"kind, version, content, hash, char_count, token_estimate, note, author, forked_from, created_at";

	private final JdbcConnectionPool pool;

	public JdbcPromptStore(JdbcAgentRunStore runStore) {
		this.pool = runStore.pool();
		initSchema();
		log.info("Prompt 版本库就绪（表 prompt_version / prompt_active）");
	}

	private void initSchema() {
		String ddl = readSchema();
		try (Connection conn = pool.getConnection(); Statement st = conn.createStatement()) {
			// H2 不接受一次执行多条语句，必须按分号切开逐条执行（与 JdbcAgentRunStore 同一条经验）
			for (String statement : ddl.split(";")) {
				String sql = stripComments(statement).strip();
				if (!sql.isEmpty()) {
					st.execute(sql);
				}
			}
		}
		catch (SQLException e) {
			throw new PromptStoreException("初始化 Prompt 版本库失败", e);
		}
	}

	private static String stripComments(String sql) {
		StringBuilder sb = new StringBuilder();
		for (String line : sql.split("\n")) {
			int idx = line.indexOf("--");
			sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
		}
		return sb.toString();
	}

	private static String readSchema() {
		try {
			return StreamUtils.copyToString(new ClassPathResource("db/prompt-schema.sql").getInputStream(),
					StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException("读不到 db/prompt-schema.sql", e);
		}
	}

	// ------------------------------------------------------------------
	//  读
	// ------------------------------------------------------------------

	@Override
	public List<PromptVersion> versionsOf(PromptKind kind) {
		String sql = "SELECT " + COLUMNS + " FROM prompt_version WHERE kind = ? ORDER BY version DESC";
		try (Connection conn = pool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, kind.defaultValue());
			try (ResultSet rs = ps.executeQuery()) {
				List<PromptVersion> versions = new ArrayList<>();
				while (rs.next()) {
					versions.add(map(rs));
				}
				return List.copyOf(versions);
			}
		}
		catch (SQLException e) {
			throw new PromptStoreException("读提示词版本列表失败 kind=" + kind, e);
		}
	}

	@Override
	public Optional<PromptVersion> find(PromptKind kind, int version) {
		String sql = "SELECT " + COLUMNS + " FROM prompt_version WHERE kind = ? AND version = ?";
		try (Connection conn = pool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, kind.defaultValue());
			ps.setInt(2, version);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? Optional.of(map(rs)) : Optional.empty();
			}
		}
		catch (SQLException e) {
			throw new PromptStoreException("读提示词版本失败 kind=" + kind + " version=" + version, e);
		}
	}

	@Override
	public Optional<PromptVersion> active(PromptKind kind) {
		// ⚠️ 拼接处那个空格是必须的：`COLUMNS` 末尾没有空格，
		// 少了它 SQL 会变成 `... created_atFROM prompt_active` —— 而报错信息指向的是
		// "FROM 附近的语法错误"，看起来像 JOIN 写错了（这一条是 PromptStoreTest 抓出来的）
		String sql = "SELECT v." + COLUMNS.replace(", ", ", v.") + " "
				+ """
						FROM prompt_active a JOIN prompt_version v ON v.kind = a.kind AND v.version = a.version
						WHERE a.kind = ?""";
		try (Connection conn = pool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, kind.defaultValue());
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? Optional.of(map(rs)) : Optional.empty();
			}
		}
		catch (SQLException e) {
			throw new PromptStoreException("读当前生效的提示词失败 kind=" + kind, e);
		}
	}

	// ------------------------------------------------------------------
	//  写
	// ------------------------------------------------------------------

	/**
	 * 追加一版。
	 *
	 * <p><b>方法级 {@code synchronized} 在这里是有意的</b>：版本号是"当前最大 +1"，
	 * 而"读最大 → 写新行"这两步之间若被插进来另一次保存，
	 * 两次就会算出同一个号，其中一条被主键挡掉 ——
	 * 表现为"保存失败了，但错误看着像数据库问题"。
	 * 数据库侧本来可以用 {@code SELECT MAX(...) FOR UPDATE}，
	 * 但那个写法在 H2 与 MySQL 上的行为不完全一样（本项目的存储层刻意只依赖两者的交集）。
	 * 提示词保存是<b>低频人工操作</b>（一天可能就几次），用一把锁换掉这一类不确定性是划算的。
	 */
	@Override
	public synchronized PromptVersion save(PromptKind kind, String content, String note, String author,
			Integer forkedFrom, int tokenEstimate) {
		int next = maxVersion(kind) + 1;
		String sql = """
				INSERT INTO prompt_version
				  (kind, version, content, hash, char_count, token_estimate, note, author, forked_from)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""";
		try (Connection conn = pool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, kind.defaultValue());
			ps.setInt(2, next);
			ps.setString(3, content);
			ps.setString(4, PromptVersion.hashOf(content));
			ps.setInt(5, content == null ? 0 : content.length());
			// token 估算由调用方算好传进来（见接口注释）：历史行保留的是
			// **当时那个估算器算出来的数**，换估算器之后不追改历史
			ps.setInt(6, tokenEstimate);
			ps.setString(7, note);
			ps.setString(8, author);
			if (forkedFrom == null) {
				ps.setNull(9, java.sql.Types.INTEGER);
			}
			else {
				ps.setInt(9, forkedFrom);
			}
			ps.executeUpdate();
		}
		catch (SQLException e) {
			throw new PromptStoreException("保存提示词新版本失败 kind=" + kind + " version=" + next, e);
		}
		return find(kind, next).orElseThrow(
				() -> new PromptStoreException("刚写入的版本读不回来 kind=" + kind + " version=" + next));
	}

	private int maxVersion(PromptKind kind) {
		try (Connection conn = pool.getConnection();
				PreparedStatement ps = conn.prepareStatement(
						"SELECT COALESCE(MAX(version), 0) AS mx FROM prompt_version WHERE kind = ?")) {
			ps.setString(1, kind.defaultValue());
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt("mx") : 0;
			}
		}
		catch (SQLException e) {
			throw new PromptStoreException("读最大版本号失败 kind=" + kind, e);
		}
	}

	/**
	 * 切换生效版本 —— <b>一条带前置条件的 UPDATE</b>。
	 *
	 * <p>并发语义落在 SQL 的 {@code AND version = ?} 上，而不是"先查一次再判断"：
	 * 后者是典型的 check-then-act，两个线程可以同时通过检查。
	 * 这里让数据库来仲裁 —— 受影响行数为 0 就说明前提过期了。
	 */
	@Override
	public Optional<PromptVersion> activate(PromptKind kind, int version, Integer expectedVersion) {
		if (find(kind, version).isEmpty()) {
			return Optional.empty();
		}
		Optional<PromptVersion> current = active(kind);
		if (expectedVersion != null && current.isPresent() && current.get().version() != expectedVersion) {
			return Optional.empty();
		}
		try (Connection conn = pool.getConnection()) {
			int affected = current.isEmpty() ? insertActive(conn, kind, version)
					: updateActive(conn, kind, version, current.get().version());
			if (affected == 0) {
				// 走到这里说明在"读 current"与"写"之间有人动过指针 —— 前提已过期，
				// 这一次切换**没有执行**。它必须与"切换成功"区分开：静默成功会覆盖别人的决定
				return Optional.empty();
			}
		}
		catch (SQLException e) {
			throw new PromptStoreException("切换生效提示词失败 kind=" + kind + " version=" + version, e);
		}
		return active(kind);
	}

	private static int insertActive(Connection conn, PromptKind kind, int version) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement(
				"INSERT INTO prompt_active (kind, version) VALUES (?, ?)")) {
			ps.setString(1, kind.defaultValue());
			ps.setInt(2, version);
			return ps.executeUpdate();
		}
	}

	private static int updateActive(Connection conn, PromptKind kind, int version, int expected) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement(
				"UPDATE prompt_active SET version = ?, activated_at = CURRENT_TIMESTAMP WHERE kind = ? AND version = ?")) {
			ps.setInt(1, version);
			ps.setString(2, kind.defaultValue());
			ps.setInt(3, expected);
			return ps.executeUpdate();
		}
	}

	private static PromptVersion map(ResultSet rs) throws SQLException {
		return new PromptVersion(PromptKind.byName(rs.getString("kind")), rs.getInt("version"),
				rs.getString("content"), rs.getString("hash"), rs.getInt("char_count"),
				rs.getInt("token_estimate"), rs.getString("note"), rs.getString("author"),
				(Integer) rs.getObject("forked_from"), toInstant(rs.getTimestamp("created_at")));
	}

	private static Instant toInstant(Timestamp ts) {
		return ts == null ? null : ts.toInstant();
	}

}
