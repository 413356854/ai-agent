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
 * <b>启动时核对 agent 任务那两张表的形状</b> —— 表在不在、本代码要用的列在不在。
 *
 * <p>做法与 {@link PermissionSchemaCheck} 逐条相同（那边的类注释写清了它接住的是什么：
 * {@code CREATE TABLE IF NOT EXISTS} 只保证表存在、不保证表是新的，
 * 于是"库里有一张旧表"的报错会以运行期 {@code Unknown column} 的形式出现在离原因很远的地方），
 * 这里只在两处按本表的情况不同：表名清单，以及拒绝启动时给出的处理方式。
 *
 * <h2>为什么它不合并进 {@link PermissionSchemaCheck}</h2>
 * 两个检查各自对着"重建代价"说话：权限那几张表重建的代价是重新配一遍角色；
 * 任务这两张表在脚手架阶段只有测试数据，重建是零代价。合并成一个类会让那句
 * "处理方式二选一"只能写一份，而它对两种表的含义不同 ——
 * 与其写一句模糊的公共说明，不如让两处各自把话说准。
 */
@Component
public class TaskSchemaCheck implements InitializingBean {

	private static final Logger log = LoggerFactory.getLogger(TaskSchemaCheck.class);

	/**
	 * 每张表必须存在的列。
	 *
	 * <p>列的是<b>本代码会读写的那些</b>：多出来的列无害，而少一列就会在某个查询上炸。
	 */
	private static final Map<String, List<String>> REQUIRED = new LinkedHashMap<>();

	static {
		REQUIRED.put("agent_task", List.of("id", "publisher_id", "assignee_id", "description", "status",
				"current_executor_id", "current_step", "agent_id", "result", "cancel_reason", "incomplete_reason"));
		REQUIRED.put("agent_task_confirm", List.of("id", "task_id", "step_index", "confirm_type", "description",
				"option_set", "answer"));
	}

	private final DataSource dataSource;

	/** 只用于声明顺序（本类不调它的任何方法），见 {@link PermissionSchemaCheck} 的类注释。 */
	public TaskSchemaCheck(DataSource dataSource, ApplicationScriptDatabaseInitializer scripts) {
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
			throw new IllegalStateException("核对 agent 任务表结构失败 —— 库连上了但元数据读不出来", e);
		}

		if (!problems.isEmpty()) {
			throw new IllegalStateException(("agent 任务表结构与代码对不上（脚本 db/task-schema.sql 只建缺的表，"
					+ "**不会改已经存在的表**）：%s。"
					+ "处理方式二选一：① 删掉这两张表（agent_task_confirm / agent_task，有外键、按这个顺序删）"
					+ "再启动一次，脚本会按最新定义重建；② 手工 ALTER TABLE 补上缺的列。"
					+ "⚠️ 走第一条之前先确认表里没有要留的任务 —— 删了就没有了（账号那种「只停用不删除」的规矩"
					+ "在这两张表上不适用：它们是过程数据）")
					.formatted(problems));
		}
		log.info("agent 任务表结构核对完成：{} 张表、{} 个承重列都在", REQUIRED.size(),
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
