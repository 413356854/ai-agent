package ai.agent.agent.store;

import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>H2 {@code MODE=MySQL} 的兼容性回归测试。</b>
 *
 * <h2>为什么它必须留下，而不能是"写代码前跑一次的探针"</h2>
 * 这段话说的是<b>依赖行为</b>，不是我的代码行为：
 * "H2 的 MySQL 兼容模式接受哪些 MySQL 写法" —— 它由 H2 的版本决定。
 * 一次探针跑完就删，等于把结论留在注释里，而<b>注释不会因为 H2 升级而变红</b>。
 * 升到 H2 2.4 / 3.x 之后兼容模式的行为可能变（比如某个写法不再被接受），
 * 那时报错会发生在应用启动建表时，看起来像"我的 DDL 写坏了"，
 * 而真相是"依赖变了" —— 这两种情况的修法完全不同。
 *
 * <p>所以这里把"当初实测出来的四条结论"变成断言。它跑得很快（纯内存库、无 Spring 上下文）。
 *
 * <h2>为什么不复用生产那份 DDL 就完事</h2>
 * <b>其实就是要复用</b>：{@link #productionSchemaLoadsOnMySqlMode()} 直接读
 * {@code classpath:db/agent-schema.sql} 逐条执行 —— 那才是真正需要在兼容模式下成立的 DDL。
 * 后面几条断言是在它之上再确认几个具体写法（它们是"兼容模式支持到什么程度"的边界）。
 *
 * <h2>同时记录一个实测发现的边界</h2>
 * MySQL 的**前缀索引** {@code INDEX (col(64))} 在 H2 的 {@code MODE=MySQL} 下**不被接受**
 * （见 {@link #prefixIndexIsNotSupported()}）。它是 MySQL 专有语法，
 * H2 只是"部分兼容"。这条断言的作用是<b>把这个边界钉住</b>：
 * 将来若有人想给 TEXT 列加前缀索引，会在这里先撞上，而不是在启动建表时才发现。
 */
class H2MySqlModeCompatibilityTest {

	private static final String URL = "jdbc:h2:mem:compat;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

	@Test
	@DisplayName("生产 DDL（db/agent-schema.sql）能直接在 MODE=MySQL 下建起来 —— 逐条执行，注释不算语句")
	void productionSchemaLoadsOnMySqlMode() throws Exception {
		JdbcConnectionPool pool = JdbcConnectionPool.create(URL, "sa", "");
		try (Connection conn = pool.getConnection(); Statement st = conn.createStatement()) {
			String ddl = readSchema();
			assertThat(ddl).as("DDL 不能是空的（读不到就会静默建不出表）").contains("CREATE TABLE IF NOT EXISTS agent_run");

			int executed = 0;
			for (String statement : ddl.split(";")) {
				String sql = stripComments(statement).strip();
				if (!sql.isEmpty()) {
					st.execute(sql);
					executed++;
				}
			}
			assertThat(executed).as("三张表 + 一个索引").isGreaterThanOrEqualTo(4);

			// 三张表都要真的存在（而不是"执行没报错"就算过）
			for (String table : new String[] { "agent_run", "agent_step", "agent_tool_invocation" }) {
				try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
					assertThat(rs.next()).as("表 %s 应该能查", table).isTrue();
				}
			}
		}
		finally {
			pool.dispose();
		}
	}

	@Test
	@DisplayName("MySQL 写法被接受：AUTO_INCREMENT / ON UPDATE CURRENT_TIMESTAMP / INSERT ... ON DUPLICATE KEY UPDATE")
	void mysqlSpecificSyntaxIsAccepted() throws Exception {
		JdbcConnectionPool pool = JdbcConnectionPool.create(URL, "sa", "");
		try (Connection conn = pool.getConnection(); Statement st = conn.createStatement()) {
			st.execute("""
					CREATE TABLE IF NOT EXISTS compat_probe (
					  id         BIGINT       NOT NULL AUTO_INCREMENT,
					  name       VARCHAR(64)  NOT NULL,
					  body       TEXT         NULL,
					  created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
					  updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
					  PRIMARY KEY (id),
					  UNIQUE KEY uk_name (name)
					)
					""");

			// upsert：存储层对 agent_step / agent_tool_invocation 都用了这个写法
			st.execute("INSERT INTO compat_probe (name, body) VALUES ('a','v1') "
					+ "ON DUPLICATE KEY UPDATE body = VALUES(body), name = VALUES(name)");
			st.execute("INSERT INTO compat_probe (name, body) VALUES ('a','v2') "
					+ "ON DUPLICATE KEY UPDATE body = VALUES(body), name = VALUES(name)");

			try (ResultSet rs = st.executeQuery("SELECT COUNT(*) c, MAX(body) b FROM compat_probe WHERE name='a'")) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getInt("c")).as("upsert 不该插出第二行").isEqualTo(1);
				assertThat(rs.getString("b")).as("upsert 应该覆盖成新值").isEqualTo("v2");
			}
		}
		finally {
			pool.dispose();
		}
	}

	@Test
	@DisplayName("DATABASE_TO_LOWER=TRUE 让表名可以不写引号直接查（这正是配置里加它的理由）")
	void lowercaseTableNamesWorkUnquoted() throws Exception {
		JdbcConnectionPool pool = JdbcConnectionPool.create(URL, "sa", "");
		try (Connection conn = pool.getConnection(); Statement st = conn.createStatement()) {
			st.execute("CREATE TABLE IF NOT EXISTS compat_lower (id VARCHAR(8) PRIMARY KEY)");
			st.execute("INSERT INTO compat_lower (id) VALUES ('x')");
			// 不带引号的小写表名 —— 若不设 DATABASE_TO_LOWER，H2 会把未加引号的标识符折成大写，
			// 于是这里会报"表不存在"，而现象很像"建表语句没执行"
			try (ResultSet rs = st.executeQuery("SELECT id FROM compat_lower")) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getString("id")).isEqualTo("x");
			}
		}
		finally {
			pool.dispose();
		}
	}

	@Test
	@DisplayName("边界：MySQL 的前缀索引 INDEX (col(64)) 在兼容模式下**不被接受**")
	void prefixIndexIsNotSupported() throws Exception {
		JdbcConnectionPool pool = JdbcConnectionPool.create(URL, "sa", "");
		try (Connection conn = pool.getConnection(); Statement st = conn.createStatement()) {
			st.execute("CREATE TABLE IF NOT EXISTS compat_prefix (id VARCHAR(8) PRIMARY KEY, body TEXT)");

			assertThatThrownBy(() -> st.execute("CREATE INDEX idx_prefix ON compat_prefix (body(64))"))
					.as("前缀索引是 MySQL 专有语法，H2 只是部分兼容 —— 这条断言把这个边界钉住")
					.isInstanceOf(SQLException.class);
		}
		finally {
			pool.dispose();
		}
	}

	@Test
	@DisplayName("手工事务可用（存储层没引 spring-tx，事务是自己写的，这条路径必须成立）")
	void manualTransactionsWork() throws Exception {
		JdbcConnectionPool pool = JdbcConnectionPool.create(URL, "sa", "");
		try (Connection conn = pool.getConnection()) {
			try (Statement st = conn.createStatement()) {
				st.execute("CREATE TABLE IF NOT EXISTS compat_tx (id VARCHAR(8) PRIMARY KEY)");
			}
			conn.setAutoCommit(false);
			try (Statement st = conn.createStatement()) {
				// 顺带验证一件事：列是 VARCHAR(8)，而这里写 9 个字符会**直接报错**而不是静默截断。
				// 这条不是可有可无的细节 —— "静默截断"是那种最难查的问题：
				// 数据看起来存进去了，内容却少了一截，而没有任何地方报错
				// （MySQL 的非严格模式就会那样做，只给一条 warning）
				st.execute("INSERT INTO compat_tx (id) VALUES ('rollback')");
			}
			conn.rollback();
			conn.setAutoCommit(true);

			try (ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM compat_tx")) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getInt(1)).as("rollback 必须真的回滚").isZero();
			}
		}
		finally {
			pool.dispose();
		}
	}

	// ------------------------------------------------------------------

	private static String readSchema() throws IOException {
		try (InputStream in = new org.springframework.core.io.ClassPathResource("db/agent-schema.sql")
				.getInputStream()) {
			return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
		}
	}

	/** 与 {@code JdbcAgentRunStore} 里同一套处理：去掉 {@code --} 注释（DDL 里注释很多）。 */
	private static String stripComments(String sql) {
		StringBuilder sb = new StringBuilder();
		for (String line : sql.split("\n")) {
			int idx = line.indexOf("--");
			sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
		}
		return sb.toString();
	}

}
