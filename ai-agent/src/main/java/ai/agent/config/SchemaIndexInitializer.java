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
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 建索引：<b>已存在的不动，缺的补上</b>。它依赖建表脚本的执行器，所以表一定先于索引存在。
 *
 * <h2>索引为什么不写在建表脚本里</h2>
 * 为了让那几份脚本同时被 MySQL 与 H2 接受、而且每次启动都能重跑
 * （{@code spring.sql.init.mode=always}），索引不能写成脚本里的一条 {@code CREATE INDEX}：
 * <b>MySQL 8.0 不认 {@code CREATE INDEX IF NOT EXISTS}</b>（实测 8.0.17 报 1064；H2 认），
 * 而不带 {@code IF NOT EXISTS} 时第二次启动会因索引已存在而失败 ——
 * 两种写法的失效方向不同，结果一样：**"启动一次 = 对齐一次表结构"这条纪律不成立**。
 * 所以索引改由代码建，先问数据库"这个索引在不在"。
 *
 * <h2>顺序：靠构造器参数，不靠注解</h2>
 * 参数里的 {@link ApplicationScriptDatabaseInitializer} 就是执行脚本的那个 bean
 * （{@code DataSourceInitializationAutoConfiguration} 提供的，本项目不接管它）——
 * 于是"表已经建好了"在 Spring 创建本 bean 之前就是事实。
 * 而本 bean 又实现了 {@link InitializingBean}：Boot 的
 * {@code DatabaseInitializationDependencyConfigurer} 会认这个类型，
 * 把用库的其它 bean（MyBatis 的 mapper 工厂等）排到本 bean 之后，
 * 于是它们看到的是<b>表和索引都齐了</b>的库。
 *
 * <p>试过的两种注解写法都不成立：{@code @DependsOnDatabaseInitialization} 会与本 bean
 * 依赖脚本初始化器这件事合成一个环（实测报 "Requested bean is currently in creation"），
 * 而把建索引挂到 {@code ApplicationReadyEvent} 上则太晚 —— 那时请求已经可以进来了，
 * 建索引失败会以"运行期偶发慢查询"的样子出现，而不是启动失败。
 *
 * <h2>为什么不"先建、撞了就当它已存在"</h2>
 * 那会把「索引名对、列写错了」这类错误一起吞掉 —— 而那种错的表现是<b>索引没生效、
 * 也不报错</b>，查询悄悄退化成全表扫。所以先问元数据，只有真的建不了才失败。
 *
 * <h2>为什么定义是 Java 常量而不是另一份 SQL 脚本</h2>
 * 为 7 行 DDL 再引一套"读脚本、挑语句"的解析不划算，而那份解析一旦出错是静默少建几个索引。
 * 写成 {@link Definition} 清单是啰嗦的，但它在编译期就被检查，名字 / 表 / 列都在一处看得见。
 * ⚠️ 代价说清：<b>加表时要在 {@link #DEFINITIONS} 里加索引</b>，
 * 写在 {@code db/*.sql} 里的索引语句不会被任何东西执行。
 */
@Component
public class SchemaIndexInitializer implements InitializingBean {

	private static final Logger log = LoggerFactory.getLogger(SchemaIndexInitializer.class);

	/**
	 * 全部索引。分组与 {@code db/agent-schema.sql} 里的表顺序一致。
	 *
	 * <p>每一段是 {@code <表> (列...)}，执行的是 {@code CREATE INDEX <名字> ON <表> (列...)}。
	 */
	private static final List<Definition> DEFINITIONS = List.of(
			// agent_run：恢复时找"本实例还有哪些没跑完的"；
			// 按会话把多次运行串起来读（Agent trace 的第一层）；
			// 按租户看钱（看板最长的分组查询，它的基数随业务增长）；
			// 看板每次查询都带的时间窗
			new Definition("idx_agent_run_status", "agent_run (status, updated_at)"),
			new Definition("idx_agent_run_conversation", "agent_run (conversation_id, created_at)"),
			new Definition("idx_agent_run_tenant", "agent_run (tenant_id, created_at)"),
			new Definition("idx_agent_run_created", "agent_run (created_at)"),
			// agent_tool_approval：待办列表（还没处理的、先来的在前）；运行详情按 run 取全部记录
			new Definition("idx_agent_tool_approval_status", "agent_tool_approval (status, requested_at)"),
			new Definition("idx_agent_tool_approval_run", "agent_tool_approval (run_id, requested_at)"),
			// retrieval_hit：按文档回溯"这个块被哪几次检索召回过"
			new Definition("idx_retrieval_hit_document", "retrieval_hit (document_id)"));

	private final DataSource dataSource;

	/**
	 * {@code scripts} <b>只用于声明顺序</b>：本类不调它的任何方法，
	 * 它出现在参数里就保证"表已经建好了"（见类注释）。
	 */
	public SchemaIndexInitializer(DataSource dataSource, ApplicationScriptDatabaseInitializer scripts) {
		this.dataSource = dataSource;
	}

	@Override
	public void afterPropertiesSet() {
		int created = 0;
		int existing = 0;
		try (Connection connection = dataSource.getConnection()) {
			for (Definition definition : DEFINITIONS) {
				if (exists(connection, definition)) {
					existing++;
					continue;
				}
				try (Statement statement = connection.createStatement()) {
					statement.execute(definition.createSql());
				}
				created++;
				log.info("状态库索引已建：{}", definition.createSql());
			}
		}
		catch (SQLException e) {
			throw new IllegalStateException("建索引失败 —— 库连上了但索引没建起来。这个状态不能放过："
					+ "查询会退化成全表扫，而现象只是「页面越来越慢」，看不出是缺索引", e);
		}
		log.info("状态库索引核对完成：新建 {} 个，已存在 {} 个", created, existing);
	}

	/**
	 * 这个索引在不在。
	 *
	 * <p>必须逐表问：MySQL 驱动在表名为 null 时直接抛 "Table not specified"
	 * （实测 mysql-connector-j 9.7.0），H2 允许 null 表示全库 —— 取交集，于是表名从定义里解析。
	 * 顺带成了"索引要建的表真的在不在"的校验：表名写错会在这里炸，
	 * 而不是等到某天发现索引一直没建上。
	 */
	private static boolean exists(Connection connection, Definition definition) throws SQLException {
		DatabaseMetaData metaData = connection.getMetaData();
		Set<String> names = new HashSet<>();
		try (ResultSet rs = metaData.getIndexInfo(connection.getCatalog(), null, definition.table(), false, false)) {
			while (rs.next()) {
				String name = rs.getString("INDEX_NAME");
				if (name != null) {
					names.add(name.toLowerCase(Locale.ROOT));
				}
			}
		}
		return names.contains(definition.name().toLowerCase(Locale.ROOT));
	}

	/**
	 * 一条索引：名字 + {@code <表> (列...)}。
	 *
	 * <p>表名单独解析出来，因为元数据查询必须逐表问（见 {@link #exists}）。
	 */
	private record Definition(String name, String tableAndColumns) {

		/** 表与列的分界是第一个空格：{@code agent_run (status, updated_at)} → {@code agent_run}。 */
		String table() {
			int space = tableAndColumns.indexOf(' ');
			return space < 0 ? tableAndColumns : tableAndColumns.substring(0, space);
		}

		String createSql() {
			return "CREATE INDEX " + name + " ON " + tableAndColumns;
		}

	}

}
