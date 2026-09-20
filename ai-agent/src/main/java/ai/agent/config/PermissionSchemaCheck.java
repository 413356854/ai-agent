package ai.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.autoconfigure.init.ApplicationScriptDatabaseInitializer;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>启动时核对权限那五张表的形状</b> —— 表在不在、本代码要用的列在不在。
 *
 * <h2>它接住的是 {@code CREATE TABLE IF NOT EXISTS} 的一个真实后果</h2>
 * 那五个字让脚本可以反复执行，代价是它<b>只保证表存在，不保证表是新的</b>：
 * 库里已经有一张 {@code agent_role} 时，脚本里新加的那一列不会被补上。
 * 之后的表现是运行期的 {@code Unknown column 'access_scope' in 'field list'} ——
 * 一条读起来像 SQL 写错了、实际是"库比代码旧"的错误，而它出现的位置离原因很远。
 *
 * <h2>为什么是"核对形状"而不是"自动加列"</h2>
 * 自动加列（{@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS}）听起来更省事，
 * 但它要求每一条列定义都写两份（建表脚本一份、迁移一份），而两份迟早分叉 ——
 * 分叉之后的表现是"新库与老库的列不一样"，比启动失败难查得多。
 * 所以这里只做一件事：<b>不一致就拒绝启动，并把该执行什么写清楚</b>。
 *
 * <h2>顺序：靠构造器参数，不靠注解</h2>
 * 与 {@link SchemaIndexInitializer} 同一条规矩：参数里的
 * {@link ApplicationScriptDatabaseInitializer} 保证"脚本已经跑过"，
 * 而本类实现 {@link InitializingBean} 让 Boot 把用库的其它 bean 排到后面。
 */
@Component
public class PermissionSchemaCheck implements InitializingBean {

	private static final Logger log = LoggerFactory.getLogger(PermissionSchemaCheck.class);

	/**
	 * 每张表必须存在的列。
	 *
	 * <p>列的是<b>本代码会读写的那些</b>，不是"全部列"：多出来的列无害，
	 * 而少一列就会在某个查询上炸 —— 那份清单就是"哪些列是承重的"。
	 */
	private static final Map<String, List<String>> REQUIRED = new LinkedHashMap<>();

	static {
		REQUIRED.put("agent_user", List.of("id", "name", "password_hash", "display_name", "status"));
		REQUIRED.put("agent_role", List.of("id", "name", "description", "access_scope"));
		REQUIRED.put("agent_user_role", List.of("user_id", "role_id", "granted_by"));
		REQUIRED.put("agent_role_tool", List.of("role_id", "tool_name", "note"));
		REQUIRED.put("agent_role_dir", List.of("role_id", "dir_prefix", "note"));
	}

	private final DataSource dataSource;

	/** 只用于声明顺序（本类不调它的任何方法），见类注释。 */
	public PermissionSchemaCheck(DataSource dataSource, ApplicationScriptDatabaseInitializer scripts) {
		this.dataSource = dataSource;
	}

	@Override
	public void afterPropertiesSet() {
		Map<String, String> problems = new LinkedHashMap<>();
		try (Connection connection = dataSource.getConnection()) {
			for (Map.Entry<String, List<String>> entry : REQUIRED.entrySet()) {
				List<String> columns = columnsOf(connection, entry.getKey());
				if (columns.isEmpty()) {
					problems.put(entry.getKey(), "表不存在");
					continue;
				}
				List<String> missing = entry.getValue().stream()
						.filter(required -> !columns.contains(required))
						.toList();
				if (!missing.isEmpty()) {
					problems.put(entry.getKey(), "缺少列 " + missing + "（现有：" + columns + "）");
				}
			}
		}
		catch (SQLException e) {
			throw new IllegalStateException("核对权限表结构失败 —— 库连上了但元数据读不出来", e);
		}

		if (!problems.isEmpty()) {
			throw new IllegalStateException(("权限表结构与代码对不上（脚本 db/permissions-schema.sql 只建缺的表，"
					+ "**不会改已经存在的表**）：%s。"
					+ "因为库里这几张表现在只有权限数据、重建的代价是重新配一遍角色，"
					+ "这里选择拒绝启动而不是带着缺列继续跑。处理方式二选一："
					+ "① 删掉这五张表（agent_role_tool / agent_role_dir / agent_user_role / "
					+ "agent_user / agent_role）再启动一次，脚本会按最新定义重建；"
					+ "② 手工 ALTER TABLE 补上缺的列。")
					.formatted(problems));
		}
		log.info("权限表结构核对完成：{} 张表、{} 个承重列都在", REQUIRED.size(),
				REQUIRED.values().stream().mapToInt(List::size).sum());
	}

	/** 一张表的列名（小写）。表不存在时返回空表 —— 由调用方把"表不存在"与"缺列"分开报。 */
	private static List<String> columnsOf(Connection connection, String table) throws SQLException {
		DatabaseMetaData metaData = connection.getMetaData();
		Map<String, String> columns = new HashMap<>();
		// 表名大小写：MySQL 在 Windows 上按定义原样返回（小写），H2 的元数据在
		// DATABASE_TO_LOWER=TRUE 下也是小写。两种写法都问一遍，省得依赖驱动的返回习惯
		for (String candidate : List.of(table, table.toUpperCase(Locale.ROOT))) {
			try (ResultSet rs = metaData.getColumns(connection.getCatalog(), null, candidate, "%")) {
				while (rs.next()) {
					String name = rs.getString("COLUMN_NAME");
					columns.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
				}
			}
			if (!columns.isEmpty()) {
				break;
			}
		}
		return List.copyOf(columns.keySet());
	}

}
