package ai.agent.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 持久层的装配：<b>一个库、一个 DataSource、一处建表</b>。
 *
 * <h2>为什么连接参数仍然读 {@code ai.agent.store.*}，而不是 {@code spring.datasource.*}</h2>
 * 见 {@link AgentStoreProperties} 的类注释：那个命名空间是"谁负责一望而知"的一部分。
 * 这里显式把它转成一个 {@code DataSource} bean ——
 * 于是"Boot 的自动配置有没有在管它"这个问题有了确定答案：
 * <b>自动配置不会介入建第二个库</b>（{@code spring.datasource.*} 没配，
 * {@code DataSourceAutoConfiguration} 不会被触发），而这个 bean 就是全应用唯一的库。
 *
 * <h2>池：{@link HikariDataSource}</h2>
 * 它是 Boot 的默认池，由 {@code spring-boot-starter-jdbc}（经 mybatis starter）带进来。
 * 用它的理由与"库换成 MySQL"是同一件事：H2 自带的 {@code JdbcConnectionPool}
 * 只有 url/user/password 三个参数，**没有建连超时** ——
 * 而这恰好是跨网络访问数据库时最常见的故障形态，现象是"启动卡在那里不报错"。
 * 本库的两种形态（MySQL / H2 内存）走同一个池，少一条按 url 分支的装配路径。
 *
 * <p>{@code maximumPoolSize} 取 10：本应用压到库上的是**短查询**
 * （单表 CRUD 与聚合，毫秒级），并发上限由 {@code ai.agent.concurrency.*} 管着，
 * 真正同时用库的连接数远小于模型并发的 8。池开大没有收益 ——
 * MySQL 的 {@code max_connections} 默认 151，多实例部署时每个实例各占一份。
 * 这个值写在代码里而不是做成配置项：与 {@code ai.chat.estimation.*} 那几个估算系数同一条规矩，
 * 它的依据是"本应用怎么用库"，不是部署方的环境差异。
 *
 * <h2>表与索引分别由谁建</h2>
 * 表：Boot 的脚本初始化器执行 {@code spring.sql.init.schema-locations} 里那几份脚本 ——
 * 它的 bean 由 {@code DataSourceInitializationAutoConfiguration} 提供，本项目不接管，
 * 因为"用库的 bean 都排在脚本之后"这条顺序保证正是它给的。
 * 索引：{@link SchemaIndexInitializer}，它依赖那个脚本初始化器，所以表一定先于索引。
 * 两者都是幂等的，所以"启动一次 = 对齐一次表结构"不需要版本表。
 *
 * <p>⚠️ 第一次连库失败（库没起、账号密码不对、库名不存在）会让应用**起不来**。
 * 这是刻意的：这几件事都不是"跑起来再说的"问题，而一个连不上库却启动成功的实例
 * 会在收到第一个请求时才报错 —— 排查方向那时会跑到接口上去。
 */
@Configuration
public class AgentStoreConfig {

	private static final Logger log = LoggerFactory.getLogger(AgentStoreConfig.class);

	/** 建连超时（毫秒）。连不上就该响亮地失败，见下面的注释。 */
	private static final long CONNECTION_TIMEOUT_MS = 10_000;

	/** 池上限。依据见类注释 —— 它是"本应用怎么用库"，不是部署参数。 */
	private static final int MAXIMUM_POOL_SIZE = 10;

	@Bean(destroyMethod = "close")
	public DataSource dataSource(AgentStoreProperties properties) {
		HikariConfig config = new HikariConfig();
		config.setPoolName("ai-agent-store");
		config.setJdbcUrl(properties.url());
		config.setUsername(properties.username());
		config.setPassword(properties.password());
		config.setMaximumPoolSize(MAXIMUM_POOL_SIZE);
		// 10 秒而不是默认的 30 秒：连不上时启动日志会白等 20 秒，
		// 而这段时间里什么都没有 —— 看起来像卡死（与"无限等"是同一类现象，只是有上限）
		config.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
		HikariDataSource pool = new HikariDataSource(config);
		log.info("状态库就绪：url={} node={}（DataSource 由 Spring 持有，表由 spring.sql.init 建、索引由 SchemaIndexInitializer 建）",
				properties.url(), properties.node());
		return pool;
	}

}
