package ai.agent.service.task;

import ai.agent.config.AgentTaskScheduleProperties;
import ai.agent.entity.agent.task.AgentTaskSchedule;
import ai.agent.mapper.AgentTaskScheduleMapper;
import ai.agent.mapper.MapperRows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.autoconfigure.init.ApplicationScriptDatabaseInitializer;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <b>启动时把库里那些启用的定时规则装进调度线程池</b> —— 应用重启之后"这些规则还跑不跑"。
 *
 * <h2>★ 它解决的是一件很具体的事：进程重启会清空线程池</h2>
 * 规则存在库里（进程退出不会没），而线程只在<b>本进程的内存里</b>。
 * 没有这一步，重启一次之后：界面上每条规则都显示"启用"、库里那一行原封不动、
 * 而<b>它们一条都不会再触发</b> —— 页面上看不出任何区别，
 * 唯一的痕迹是"昨天该跑的活今天没跑"。
 *
 * <h2>为什么它单独一个类，而不是挂在 {@code AgentTaskScheduleScheduler} 上</h2>
 * 依赖方向：调度器需要 {@code AgentTaskService}（触发时要建任务），
 * 而 {@code AgentTaskService} 需要调度器（记录更新即调度）。
 * 让调度器自己去查"启动时装哪些"，这条链上就多出一个环 ——
 * 而 Spring 从 2.6 起<b>禁止</b>循环依赖（默认 {@code allow-circular-references=false}），
 * 那种失败是启动期的一句 bean 依赖报错，排查方向会跑到"是不是注入写错了"上去。
 * 装规则的职责交给本类之后，环的两边各是单向的：
 * 本类 → 服务 → 调度器 → 任务服务，没有任何一条反向边。
 *
 * <h2>为什么依赖 {@link ApplicationScriptDatabaseInitializer}</h2>
 * 与 {@code SchemaIndexInitializer} / {@code TaskSchemaCheck} 同一条：那个 bean
 * 出现在构造器参数里就保证"建表脚本已经跑完"，于是这一步读到的表一定存在。
 * 它也实现了 {@link InitializingBean} —— Boot 的 {@code DatabaseInitializationDependencyConfigurer}
 * 会认这个类型，把用库的其它 bean 排到它之后。
 *
 * <h2>一条规则装不上，不许拖住其余规则</h2>
 * 每一条单独 try：正则算不出来（手工改过库）、发布人已经被清掉…… 这些都会让那一条
 * 装不上，而它们的正确处置是"那一条没装、日志里说清是哪一条为什么"，
 * 不是"整个应用起不来"。反过来做（任何一条失败就抛）的后果很严重：
 * 一条配错的规则能让<b>整个应用</b>起不来，而那时运维只能去改库。
 */
@Component
public class AgentTaskScheduleInstaller implements InitializingBean {

	private static final Logger log = LoggerFactory.getLogger(AgentTaskScheduleInstaller.class);

	private final AgentTaskScheduleService schedules;

	private final AgentTaskScheduleMapper rows;

	private final AgentTaskScheduleProperties properties;

	/** 第四个参数刻意不进字段：本类不调它的任何方法，它在这里只保证建表脚本已经跑完（见类注释）。 */
	public AgentTaskScheduleInstaller(AgentTaskScheduleService schedules, AgentTaskScheduleMapper rows,
			AgentTaskScheduleProperties properties, ApplicationScriptDatabaseInitializer scripts) {
		this.schedules = schedules;
		this.rows = rows;
		this.properties = properties;
	}

	@Override
	public void afterPropertiesSet() {
		List<MapperRows.AgentTaskScheduleRow> enabled = rows.listEnabled();
		if (!properties.enabled()) {
			// 关掉时**一条都不装**（已经在池子里的也没有 —— 启动时装是第一批）。
			// 这一行日志是"为什么它们都不动"的唯一答案
			log.info("定时任务总开关是关的（ai.agent.schedule.enabled=false）：库里 {} 条启用的规则"
					+ "一条都没装进调度线程池，它们不会被自动触发（规则本身照常留在库里，界面上也能手工触发）",
					enabled.size());
			return;
		}
		int installed = 0;
		for (MapperRows.AgentTaskScheduleRow row : enabled) {
			AgentTaskSchedule rule = row.toDomain();
			try {
				schedules.register(rule);
				installed++;
			}
			catch (RuntimeException e) {
				// 单独一条失败不拖住其余（理由见类注释）。这条规则在界面上会显示成
				// "启用但不在池子里"——那是它真实的处境
				log.warn("定时规则 #{}（{}）装不进调度线程池，它不会被自动触发：{}",
						rule.id(), rule.cron(), e.toString());
			}
		}
		AgentTaskScheduleService.PoolStatus pool = schedules.poolStatus();
		log.info("定时任务启动安装完成：库里 {} 条启用规则，装进池子 {} 条；"
				+ "池子线程数上限 {}（同一瞬间最多几条规则能触发）",
				enabled.size(), installed, pool.poolSize());
		if (installed < enabled.size()) {
			log.warn("有 {} 条启用的规则没装进池子 —— 它们在界面上显示「启用」但不会自动触发，"
					+ "逐条原因见上面的 WARN", enabled.size() - installed);
		}
	}

}
