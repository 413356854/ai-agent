package ai.agent.service.task;

import ai.agent.entity.agent.task.AgentTaskStatus;

/**
 * <b>任务记录变更之后的调度入口</b> —— 由 {@code AgentTaskScheduler} 实现，由
 * {@code DefaultAgentTaskService} 在每次写入之后调用。
 *
 * <h2>为什么是一个接口，而不是让服务层直接依赖那个定时任务类</h2>
 * 依赖方向必须只有一条：<b>服务层 → 调度</b>，而调度<b>不</b>依赖服务层。
 * 直接用具体类也能编译，但那样"任务执行"这一整套（执行器、判定、Agent 运行）
 * 就挂在了人用的写入口上 —— 而它们唯一的联系是"记录变了，去看一眼有没有活要干"。
 *
 * <h2>它是"通知"，不是"执行"</h2>
 * 实现必须是<b>非阻塞</b>的：调用方（HTTP 请求线程）把消息放下就走。
 * 这个方法一旦阻塞，一次任务发布就会连带把执行时间算进响应时间里 ——
 * 而执行会调模型、会跑工具，那是几十秒量级的事。
 */
public interface AgentTaskDispatch {

	/**
	 * 一条任务刚被改成了 {@code status}，去看一眼有没有活要干。
	 *
	 * <p>状态由调用方给（而不是让实现自己去查一遍）：<b>已完成 / 已取消不调度</b>这条判据
	 * 就落在这里，而它是这条链上唯一"什么情况下不必调度"的规则 ——
	 * 让每个写动作各自判断的话，漏一个地方的表现只是"少调度了一次"（不报错，最多晚 10 分钟）。
	 *
	 * @param status 这次写入之后那条任务的状态
	 */
	void onTaskUpdated(AgentTaskStatus status);

}
