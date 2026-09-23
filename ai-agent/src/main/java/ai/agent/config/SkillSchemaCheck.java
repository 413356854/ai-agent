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
 * <b>启动时核对 {@code agent_skill} 的形状</b> —— 表在不在、本代码要用的列在不在。
 *
 * <p>做法与 {@link PermissionSchemaCheck} / {@link TaskSchemaCheck} 逐条相同
 * （那边写清了它接住的是什么：{@code CREATE TABLE IF NOT EXISTS} 只保证表存在、
 * 不保证表是新的，于是"库里有一张旧表"的报错会以运行期 {@code Unknown column}
 * 的形式出现在离原因很远的地方），这里只在"拒绝启动时给出的处理方式"上按本表说话。
 *
 * <h2>为什么它不合并进另外两个检查</h2>
 * 与 {@link TaskSchemaCheck} 的理由一致：三个检查各自对着"重建代价"说话。
 * 技能表里现在是人在界面上建的记录，重建的代价是重新建一遍；
 * 而权限那几张表重建的代价是重新配一遍角色。合并成一个类会让那句"处理方式"只能写一份，
 * 而它对三种表的含义不同。
 */
@Component
public class SkillSchemaCheck implements InitializingBean {

	private static final Logger log = LoggerFactory.getLogger(SkillSchemaCheck.class);

	/**
	 * 这张表必须存在的列。
	 *
	 * <p>列的是<b>本代码会读写的那些</b>：多出来的列无害，而少一列就会在某个查询上炸。
	 */
	private static final Map<String, List<String>> REQUIRED = new LinkedHashMap<>();

	static {
		REQUIRED.put("agent_skill", List.of("id", "folder", "name", "description", "content", "owner_id",
				"last_modified_by", "created_at", "updated_at"));
	}

	private final DataSource dataSource;

	/** 只用于声明顺序（本类不调它的任何方法），见 {@link PermissionSchemaCheck} 的类注释。 */
	public SkillSchemaCheck(DataSource dataSource, ApplicationScriptDatabaseInitializer scripts) {
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
			throw new IllegalStateException("核对技能表结构失败 —— 库连上了但元数据读不出来", e);
		}

		if (!problems.isEmpty()) {
			throw new IllegalStateException(("技能表结构与代码对不上（脚本 db/skill-schema.sql 只建缺的表，"
					+ "**不会改已经存在的表**）：%s。"
					+ "处理方式二选一：① 删掉 agent_skill 再启动一次，脚本会按最新定义重建；"
					+ "② 手工 ALTER TABLE 补上缺的列。"
					+ "⚠️ 走第一条之前先确认表里没有要留的技能 —— 删了就没有了")
					.formatted(problems));
		}
		log.info("技能表结构核对完成：{} 张表、{} 个承重列都在", REQUIRED.size(),
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
