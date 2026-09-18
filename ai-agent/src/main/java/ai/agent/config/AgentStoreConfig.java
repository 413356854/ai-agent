package ai.agent.config;

import org.h2.jdbcx.JdbcConnectionPool;
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
 * <b>自动配置不会介入</b>（{@code spring.datasource.*} 没配，`DataSourceAutoConfiguration`
 * 在类路径上没有可用的连接池属性时不会被触发来抢着建第二个库），
 * 而这个 bean 就是全应用唯一的库。
 *
 * <h2>为什么用 H2 自带的 {@link JdbcConnectionPool} 当 DataSource</h2>
 * 它本来就实现了 {@code javax.sql.DataSource}，而这台机器上它已经在用（阶段三的选择）。
 * 换成 HikariCP 能拿到更细的池参数，但那是**另一个决定**（连接数、泄漏检测、超时），
 * 与"把 SQL 收进 mapper"这件事无关 —— 这次迁移刻意不捆绑它。
 * 唯一的形状变化：池现在由 Spring 管理生命周期（{@code destroyMethod = "dispose"}），
 * 不再是 {@code JdbcAgentRunStore} 里那个 {@code @PreDestroy}。
 *
 * <h2>{@code DB_CLOSE_DELAY=-1} 是这整套配置的隐含前提</h2>
 * 默认 url 是内存库，而内存库在**最后一个连接关闭时**会被删掉。
 * 没有这个参数，一次性用完就归还的池会让库在两次操作之间消失 ——
 * 现象是"表不存在"，看起来像建表没跑。这个参数写在
 * {@link AgentStoreProperties#defaults()} 的默认连接串里。
 *
 * <h2>建表交给 {@code spring.sql.init}</h2>
 * 原先三处手写的 DDL 执行（`ddl.split(";")` + 逐行去 `--` 注释）已经删除。
 * 换过去不只是少了几十行：那个 split 的做法**只在"没有注释里带分号"时才正确**
 * （原代码里就留着这条警告），而 Boot 的脚本执行器是真正解析 SQL 的。
 * 脚本清单在 {@code application.properties} 的 {@code spring.sql.init.schema-locations}。
 */
@Configuration
public class AgentStoreConfig {

	private static final Logger log = LoggerFactory.getLogger(AgentStoreConfig.class);

	/**
	 * 全应用唯一的库。
	 *
	 * <p>{@code destroyMethod = "dispose"} 是必须显式写的：
	 * Spring 猜不出这个非标准名字的关闭方法，不写就会在停机时泄漏池里的连接 ——
	 * 对文件库而言，那意味着<b>最后几个检查点可能没刷盘</b>，
	 * 而这恰好会在"进程被杀要恢复"的场景里咬人。
	 */
	@Bean(destroyMethod = "dispose")
	public DataSource dataSource(AgentStoreProperties properties) {
		JdbcConnectionPool pool = JdbcConnectionPool.create(properties.url(), properties.username(),
				properties.password());
		log.info("状态库就绪：url={} node={}（DataSource 由 Spring 持有，建表由 spring.sql.init 负责）",
				properties.url(), properties.node());
		return pool;
	}

}
