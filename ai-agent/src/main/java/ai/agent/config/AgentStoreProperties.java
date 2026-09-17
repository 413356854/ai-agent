package ai.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 状态持久化的配置（阶段三：断点恢复）。
 *
 * <h2>为什么 JDBC 连接参数不放在 {@code spring.datasource.*}</h2>
 * 因为<b>这个项目没有引 Spring 的 JDBC 自动配置</b>（理由见 pom 里那段注释：
 * spring-boot-starter-jdbc 4.x 拿不到、spring-jdbc 本地只到 6.2.8 与 Spring Framework 7 不匹配）。
 * 写成 {@code spring.datasource.*} 会让人以为 Boot 在管这个 DataSource ——
 * 而实际上没人管，改了不生效，排查方向会跑到"Boot 的自动配置怎么没生效"上去。
 *
 * <p>用自己命名空间（{@code ai.agent.store.*}）的好处是：**谁负责一望而知**。
 * 将来真换成 Boot 的 DataSource 时，改名这件事本身就是一个显式的动作，
 * 而不是"以为改了其实没改"。
 *
 * <h2>连接串里的 {@code MODE=MySQL} 是刻意的</h2>
 * 让 SQL 尽量贴着 MySQL 写法，将来换真 MySQL 时 DDL 与语句改动最小。
 * 实测确认 H2 2.3.232 在这个模式下接受 {@code AUTO_INCREMENT} / {@code TEXT} /
 * {@code ON UPDATE CURRENT_TIMESTAMP} / {@code INSERT ... ON DUPLICATE KEY UPDATE}。
 *
 * @param url JDBC 连接串。默认**内存库**（进程退出即清空）—— 理由见下面 {@link #defaults()} 的注释
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
	 * <p><b>默认用内存库是一个刻意的取舍</b>：内存库在进程退出时清空 ——
	 * 也就是说<b>默认配置下"进程被杀"之后什么都没了，恢复无从谈起</b>。
	 * 那为什么不默认用文件库？
	 * <ul>
	 *   <li>测试与 CI 跑的是内存库（快、不残留、可并行）；</li>
	 *   <li>而"要不要持久化"是个<b>部署决定</b>，该由部署方明确说出来 ——
	 *       默认偷偷往磁盘写文件，是那种"本地跑着跑着多出一堆文件"的意外；</li>
	 *   <li>更重要的是：**默认值不能假装自己实现了断点恢复**。
	 *       内存库 + 默认配置下，恢复能力的验证要靠测试（真实模拟"库里留着 RUNNING 行"），
	 *       而生产要开就把 url 指到文件库或真 MySQL。这一条是本项目明确列出的风险。</li>
	 * </ul>
	 */
	public static AgentStoreProperties defaults() {
		return new AgentStoreProperties(
				"jdbc:h2:mem:agent;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "", "local", false);
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
