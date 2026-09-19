package ai.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工作区目录与运行权限的配置（前缀 {@code ai.agent.workspace}）。
 *
 * <h2>为什么"必填"要做成配置，而不是写死在控制器里</h2>
 * 界面上发起任务时它<b>确实是必填的</b>（那是这一件的需求原话）。
 * 但 {@code POST /api/agent/ask} 同时也是 curl、脚本与外部系统的入口，
 * 而把它钉死成"任何调用方不传工作区就 400"会把那些调用方一起挡在门外 ——
 * 它们本来就不该被迫声明一个目录（那些运行没有权限上下文，写操作按最严的一档处理）。
 *
 * <p>所以判据是：<b>默认必填</b>（保护界面这条主路径，且改配置就能放开），
 * 而不是"默认可选、界面自己记得校验"—— 后者一旦界面那侧漏了，
 * 后端不会有任何反应，而"工作区没传"这件事在库里看起来和"老数据"一模一样。
 *
 * @param requiredOnAsk 发起任务时是否必须给出工作区目录。默认 {@code true}
 * @param maxPathChars 工作区路径的长度上限。
 *            <p>取值依据：Windows 的 {@code MAX_PATH} 是 260 字符（更长的路径要加
 *            {@code \\?\} 前缀才可用），Linux 的 {@code PATH_MAX} 是 4096。
 *            512 覆盖两边的日常使用，同时让 {@code agent_run.workspace_dir} 保持成一个短字段
 *            （同长度的字符串会出现在每一行的历史列表里）。
 *            <p>超限<b>不静默截断</b>：截断一个路径得到的目录与它指向的可能是两个完全不同的地方。
 */
@ConfigurationProperties(prefix = "ai.agent.workspace")
public record WorkspaceProperties(boolean requiredOnAsk, int maxPathChars) {

	public WorkspaceProperties {
		if (maxPathChars <= 0) {
			// 允许 0/负数会让每一次请求都被判成"路径过长"，而那个现象看起来像"界面填什么都没用"。
			// 与 AgentApprovalProperties.timeout 同一条规矩：写错的配置要在启动时就暴露
			throw new IllegalArgumentException(
					"ai.agent.workspace.max-path-chars 必须是正数（收到 " + maxPathChars + "）");
		}
	}

	public static WorkspaceProperties defaults() {
		return new WorkspaceProperties(true, 512);
	}

}
