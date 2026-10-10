package ai.agent.service.task;

import ai.agent.entity.agent.permission.Account;
import ai.agent.entity.agent.permission.RoleDirectory;
import ai.agent.entity.agent.permission.UserPermissions;
import ai.agent.service.permission.PermissionResolver;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * <b>写文件工具（write_file / edit_file）的授权规则</b>：这次要动的目录必须在<b>任务人角色的目录前缀</b>里。
 *
 * <h2>判据（两步）</h2>
 * <ol>
 *   <li><b>从任务记录取任务人</b>（{@code agent_task.assignee_id}，即 {@code agent_user.id}），
 *       按账号表解析出他的全部角色 ——
 *       目录范围是这些角色的并集（{@link UserPermissions#coversDirectory}）；</li>
 *   <li><b>这次执行的文件工具工作目录</b>（任务计划里的 {@code workspaceDir}）必须落在那份并集里：
 *       等于某一条前缀、或者是它的子目录。比较按路径分段做、不做字符串前缀比较，
 *       判据只有 {@link RoleDirectory#covers} 一份（{@code D:\work\demo} 不该命中
 *       {@code D:\work\demo-other}）。</li>
 * </ol>
 *
 * <h2>为什么工具清单不参与通过 / 不通过</h2>
 * 角色的工具白名单在运行期<b>不过滤任何工具</b>（{@code UserPermissions.allowsTool} 的调用点
 * 只有核对端点 {@code DefaultPermissionService#verify}），所以"角色的清单里有没有这两把工具"
 * 与"这次能不能写"是两件事。角色授予的写文件工具仍然会被读出来、写进结论 ——
 * 它是用户决定"该去改什么"的线索，不是判定条件。
 *
 * <h2>没有工作区目录的任务判通过</h2>
 * {@code workspaceDir} 为空表示这条任务不动文件（只回答一个问题，或跑一段不依赖文件的命令）——
 * 那时没有目录可判。文件工具在调用时会自己回一句"本次运行没有工作区目录"
 * （见 {@code FileToolSupport#invocation}），那比在这里把所有不动文件的任务都判成权限不足准确得多。
 *
 * <h2>没有角色 = 没有权限</h2>
 * 账号不存在、账号停用、一个角色都没有 —— 三种都判不通过（目录并集为空时任何目录都不在范围内）。
 * 让"还没配权限"读起来像"可以执行"，等于把最危险的那种状态设成默认值
 * （同一条取舍见 {@link UserPermissions} 与 {@code PermissionAccess}）。
 */
@Component
public class TaskFileAuthorization {

	/**
	 * 这条规则管辖的工具 —— 会动工作区文件的那些。
	 *
	 * <p>取值是 {@code AgentTool.name()}：{@code write_file}（整份写入）与 {@code edit_file}（改一处），
	 * 两把都在 {@code ai.agent.service.impl.tool.file} 下。它<b>只用于结论里的提示</b>，不参与判定
	 * （理由见类注释）；新增会动工作区文件的工具时要把名字加进来，否则提示那一行会漏报。
	 *
	 * <p><b>{@code run_command} 刻意不在这个清单里</b>：它会不会动文件、动哪个文件，
	 * 由命令内容决定（一条 {@code type} 什么都不改，一条 {@code del} 改一片），
	 * 从参数里判不出来。<b>判不出来就不写进这份结论</b> ——
	 * 写了会让"任务人角色授予的写文件工具：无"这句话变成一句错话。
	 */
	private static final List<String> FILE_WRITE_TOOLS = List.of("write_file", "edit_file");

	private final PermissionResolver resolver;

	public TaskFileAuthorization(PermissionResolver resolver) {
		this.resolver = resolver;
	}

	/**
	 * 判断结论。
	 *
	 * @param allowed 通过 / 不通过
	 * @param reason 一句话结论（写成中文，直接落进「权限不足」那一格或授权确认项的正文）：
	 *        不通过时要说清"缺哪一条前缀、已授权的是哪些、怎么放行"
	 */
	public record Verdict(boolean allowed, String reason) {
	}

	/**
	 * 判一次。
	 *
	 * @param assigneeId 任务人（{@code agent_task.assignee_id}，也就是 {@code agent_user.id}）
	 * @param workspaceDir 这次执行文件工具的工作目录；空 = 这条任务不动文件
	 */
	public Verdict judge(Long assigneeId, String workspaceDir) {
		String dir = workspaceDir == null ? "" : workspaceDir.strip();
		if (dir.isEmpty()) {
			return new Verdict(true, "这条任务没有工作区目录（不动文件），目录授权不参与判断。");
		}
		if (assigneeId == null) {
			return new Verdict(false, ("这条任务没有任务人，判断不了目录「%s」在不在授权范围内 —— "
					+ "发布任务时要从用户列表里选一个真实用户当任务人。").formatted(dir));
		}
		Optional<Account> found = resolver.accountById(assigneeId);
		if (found.isEmpty()) {
			return new Verdict(false, ("账号表里没有 id=%d 这个人（任务人），判断不了目录「%s」的授权 —— "
					+ "他可能已被删掉，请重新指派任务人。").formatted(assigneeId, dir));
		}
		Account account = found.get();
		if (!account.usable()) {
			return new Verdict(false, ("任务人「%s」已停用（status=%s）—— 停用的账号一律没有权限，"
					+ "目录「%s」因此不在授权范围内。启用这个账号，或把任务人换成别人。")
					.formatted(account.name(), account.status().name(), dir));
		}

		UserPermissions permissions = resolver.permissionsOf(account);
		if (permissions.coversDirectory(dir)) {
			return new Verdict(true, ("目录「%s」在任务人「%s」的角色授权范围内（按前缀「%s」放行）。"
					+ "任务人角色授予的写文件工具：%s。")
					.formatted(dir, account.name(),
							permissions.matchedDirectory(dir).map(RoleDirectory::prefix).orElse(""),
							grantedWriteTools(permissions)));
		}

		String authorized = permissions.directories().isEmpty() ? "无"
				: permissions.directories().stream().map(RoleDirectory::prefix).distinct().toList().toString();
		String reason = permissions.hasNoRole()
				? "任务人「%s」没有任何角色 —— 没有角色就是没有任何目录授权。".formatted(account.name())
				: "目录「%s」不在任务人「%s」的任何角色目录前缀里。".formatted(dir, account.name());
		return new Verdict(false, ("%s已授权的目录前缀：%s。任务人角色授予的写文件工具：%s。"
				+ "要放行这条任务：在「权限管理」里给这个人的角色加一条覆盖「%s」的目录前缀，"
				+ "或者把这条任务要动的目录改到已授权的范围内。")
				.formatted(reason, authorized, grantedWriteTools(permissions), dir));
	}

	/**
	 * 任务人角色授予的写文件工具（只作提示，不参与判定）。
	 *
	 * <p>{@code ALL} 的角色不逐个登记工具（见 {@code UserPermissions#of}），所以那种情况说"全部工具"，
	 * 不能把空清单显示成"无" —— 那是两件相反的事。
	 */
	private static String grantedWriteTools(UserPermissions permissions) {
		if (permissions.allTools()) {
			return "全部工具";
		}
		List<String> granted = permissions.sortedTools().stream().filter(FILE_WRITE_TOOLS::contains).toList();
		return granted.isEmpty() ? "无" : String.join("、", granted);
	}

}
