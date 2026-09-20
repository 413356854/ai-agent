package ai.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 权限管理的配置（{@code ai.agent.permission.*}）。
 *
 * <h2>为什么有一个"关掉它"的开关，而且默认是开的</h2>
 * 这一层现在<b>没有登录</b>：{@code /api/permissions/**} 的每一个端点都是匿名的，
 * 也就是说能访问这个端口的人可以给自己开"全部工具"。当前它只绑 {@code localhost}
 * 供本地开发用，所以默认开；一旦要放到别人能访问的地方，这个开关就是第一道闸门
 * （第二道是接上登录链路，那时这个开关的作用变成"只读运维模式"）。
 *
 * <p>这一点写在配置里，而不是只写在文档里 —— 因为它是一条<b>部署时</b>必须做对的事，
 * 而配置是部署时会看的那一份。
 *
 * @param managementEnabled 管理端点是否可用（false 时 {@code /api/permissions/**} 一律 404）
 * @param bootstrapEnabled 启动时是否补一个初始管理员（库里一个账号都没有时才动作）
 * @param bootstrapAdminName 初始管理员的登录名
 * @param bootstrapAdminPassword 初始管理员的口令（留空表示不建，只建角色）
 * @param bootstrapDirPrefix 初始管理员的目录前缀（留空 = 不给目录授权，判据见 PermissionBootstrap）
 */
@ConfigurationProperties(prefix = "ai.agent.permission")
public record PermissionProperties(boolean managementEnabled, boolean bootstrapEnabled, String bootstrapAdminName,
		String bootstrapAdminPassword, String bootstrapDirPrefix) {

	public PermissionProperties {
		bootstrapAdminName = bootstrapAdminName == null || bootstrapAdminName.isBlank() ? "admin"
				: bootstrapAdminName.strip();
		// 空口令不是"没有口令"，它会在建号时被 Account.of 拒掉（那一层是最终的判据）。
		// 这里把它归一成空串，让"要不要建号"这个判断只有一处
		bootstrapAdminPassword = bootstrapAdminPassword == null ? "" : bootstrapAdminPassword;
		bootstrapDirPrefix = bootstrapDirPrefix == null ? "" : bootstrapDirPrefix.strip();
	}

}
