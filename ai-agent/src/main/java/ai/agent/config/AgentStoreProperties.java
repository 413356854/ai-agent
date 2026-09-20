package ai.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 状态持久化的配置（阶段三：断点恢复）。
 *
 * <h2>为什么 JDBC 连接参数不放在 {@code spring.datasource.*}</h2>
 * 见 pom 里 mybatis starter 那一段：它自带 {@code spring-boot-starter-jdbc}，
 * 而连接池与脚本初始化都由本项目的 {@code AgentStoreConfig} 显式装配。
 * 写成 {@code spring.datasource.*} 会让人以为 Boot 在管这个 DataSource ——
 * 实际上 {@code DataSourceAutoConfiguration} 只在那一族属性存在时才介入，
 * 而这里没有它们，于是"改了不生效"的排查方向会跑到"Boot 的自动配置怎么没生效"上去。
 *
 * <p>用自己命名空间（{@code ai.agent.store.*}）的好处是：**谁负责一望而知**。
 *
 * <h2>连接串里的 {@code MODE=MySQL} 是刻意的</h2>
 * 它出现在<b>可选的内存库那一条</b>上，让 H2 也接受 MySQL 写法的 DDL 与 SQL ——
 * 于是 {@code db/*.sql} 只有一份、两种库共用，从 MySQL 退回内存库不用改 DDL。
 * 实测确认 H2 2.3.232 在这个模式下接受 {@code AUTO_INCREMENT} / {@code TEXT} /
 * {@code ON UPDATE CURRENT_TIMESTAMP} / {@code INSERT ... ON DUPLICATE KEY UPDATE}。
 *
 * @param url JDBC 连接串。当前指向 MySQL（库进程退出不清空）—— 理由见下面 {@link #defaults()} 的注释
 * @param username 用户名
 * @param password 密码
 * @param node 本实例标识。多实例部署时靠它区分"这条 RUNNING 是谁留下的"，
 *             避免 A 实例去认领 B 实例正在跑的运行（那会让同一次任务的工具被执行两遍）
 * @param autoResumeOnStartup 启动时是否自动恢复上次留下的未完成运行
 */
@ConfigurationProperties(prefix = "ai.agent.store")
public record AgentStoreProperties(String url, String username, String password, String node,
		boolean autoResumeOnStartup) {

	/**
	 * 默认值，以及它们各自为什么是这个值。
	 *
	 * <p><b>为什么默认是 MySQL 而不是内存库</b>：内存库在进程退出时清空 ——
	 * 也就是说内存库下"进程被杀"之后什么都没了，<b>断点恢复这条能力无从谈起</b>
	 * （要复现"库里留着 RUNNING 行"那种状态，也得先有一个不会随进程消失的库）。
	 * 既然这个应用的验收口径里有「进程被杀能从检查点恢复」，默认值就不该是内存库。
	 *
	 * <p><b>内存库仍然是一条随时可用的退路</b>，代价必须说清：它跑得快、不留残留，
	 * 适合 CI 与"只想跑一遍看看"；但配置里那一行改成它之后，
	 * "落库"会看起来生效、实际重启照样清空 —— 这是本项目明确列过的一类风险，
	 * 所以退路写在 {@code application.properties} 的注释里，而不是做成默认值。
	 */
	public static AgentStoreProperties defaults() {
		return new AgentStoreProperties(
				"jdbc:mysql://localhost:3306/db_ai_agent?useUnicode=true&characterEncoding=utf8"
						+ "&connectionTimeZone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
				"admin", "admin", "local", false);
	}

	public AgentStoreProperties {
		if (url == null || url.isBlank()) {
			throw new IllegalArgumentException("ai.agent.store.url 不能为空");
		}
		if (node == null || node.isBlank()) {
			// 不默认成 "local" 之外的随机值：随机值会让"同一个实例重启后认不出自己的遗留运行"，
			// 而那样恢复永远不生效、且没有任何报错
			throw new IllegalArgumentException("ai.agent.store.node 不能为空（多实例下用它区分是谁的遗留运行）");
		}
	}

}
