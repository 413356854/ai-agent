package ai.agent.service.impl.tool.command;

import ai.agent.config.WorkspaceCommandProperties;
import ai.agent.entity.model.tool.AgentToolDefinition;
import ai.agent.entity.model.tool.ToolHints;
import ai.agent.entity.tool.RunPermission;
import ai.agent.entity.tool.ToolCallContext;
import ai.agent.service.support.tool.ToolFeedbackFormatter;
import ai.agent.service.tool.AgentTool;
import ai.agent.service.tool.AgentToolResult;
import ai.agent.service.tool.workspace.WorkspaceBoundTool;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具 ⑬：<b>在工作区目录下执行一条 shell 命令</b>。
 *
 * <h2>它为什么存在（以及为什么前十二把工具都刻意没有它）</h2>
 * 文件工具能读、能写、能找，但"这条命令跑起来会怎样"只有真的跑过才知道 ——
 * 编译、测试、脚本、{@code git status} 都属于这一类。没有它，模型对"我改完能不能过"
 * 只能给一个猜测，而猜测在回填文本里看起来与结论一模一样。
 *
 * <h2>三道闸门</h2>
 * <ol>
 *   <li><b>必须有工作区</b>（没有就拒绝，并告诉模型"让用户重新发起任务并填工作区"）——
 *       命令的工作目录由它决定，而"挑一个默认目录"会让"这次到底在哪儿执行的"没有答案；</li>
 *   <li><b>档位允许写</b>：仅可查看档位直接拒绝（理由见 {@code AgentPermissionPolicy}：
 *       那一档的字面意思是不许改，转成人工确认等于给人一个"点通过就绕过档位"的后门）；</li>
 *   <li><b>人工确认</b>：工作区内修改档位下要人点头，「完全权限」档直接执行
 *       （那是这一档的定义）。</li>
 * </ol>
 *
 * <h2>为什么本类<b>不</b>实现 {@code WorkspaceCallContext}</h2>
 * 人工确认闸门用那个接口判"这次要动的东西是不是只在工作区内"，判据是<b>参数里的路径</b>。
 * 而一条命令的副作用写在哪，从命令字符串里<b>看不出来</b>
 * （{@code type ..\..\x} 就是一个反例）—— 与 {@code execute_sql} 完全同族。
 * 本类只实现 {@link WorkspaceBoundTool}，于是闸门把这次调用按"没有路径参数"处理：
 * 工作区内修改档位不放行、要人去点批准。给出一个"它在工作区内"的结论去换免审批，
 * 是把两件不同的事说成一件。
 *
 * <p>实现 {@link WorkspaceBoundTool} 的另一个作用是<b>它不出现在 MCP 暴露清单里</b>：
 * 外部 MCP 客户端那条链路没有"一次运行"（也就没有工作区），更没有人在界面后面点批准。
 *
 * <h2>工作区是运行级事实，而 {@code invoke} 拿不到上下文</h2>
 * 所以用 {@code GetSkillContentTool} 那套已验证的做法：{@link #currentRun}（ThreadLocal）
 * 只记"本线程现在跑的是哪一次运行"这个短命键，本次运行的权限按 runId 存在 {@link #byRun} 里。
 * <b>只存一个字段会串味</b>：本类是 Spring 单例，同一时刻可能有多次运行在虚拟线程上并发，
 * 而 {@code run_command} 一次要跑几十秒 —— 那正是"两次调用的边界被写坏"最容易发生的窗口。
 */
@Component
public class RunCommandTool implements AgentTool<RunCommandTool.Params>, WorkspaceBoundTool {

	private static final Logger log = LoggerFactory.getLogger(RunCommandTool.class);

	/** 没有运行 id 时用的键（外部链路、执行器被单独调用）。这类调用拿不到工作区。 */
	private static final String NO_RUN = "";

	/**
	 * 存"这一次运行的权限"的地方，上限 {@value #MAX_TRACKED_RUNS} 条。
	 *
	 * <p>它是缓存而不是事实来源：事实在 {@code ToolCallContext} 里，每次调用都会重新放进来
	 * （{@code onContext} 在每次执行之前都会走一遍）。上限的存在是为了让
	 * "某个 runId 永远不来取"不会变成一处缓慢增长的内存。
	 */
	private static final int MAX_TRACKED_RUNS = 200;

	/**
	 * 回填文本里"元信息 + 各种说明"的预留字符数。
	 *
	 * <p>{@link ToolFeedbackFormatter#maxChars()} 是回填文本的唯一总量上限，
	 * 而输出正文与说明文字共享这一份预算。预留 400 的依据：元信息最长的一行是
	 * "工作区路径（上限 512 字符）+ shell + 退出码 + 耗时"，而说明文字（超时 / 截断 / 解码失败）
	 * 每条不超过 200 字符、最多三条 —— 400 是这两者的上界。
	 *
	 * <p><b>为什么必须留</b>：不留的话，一段刚好顶满预算的输出会把后面那些说明挤掉，
	 * 而"被截断了 / 解码丢字了 / 超时了"正是模型判断这份输出可不可信的唯一依据。
	 */
	private static final int FOOTER_RESERVE_CHARS = 400;

	/**
	 * 当前线程上正在执行的那一次运行。
	 *
	 * <p>存活范围正好是"执行器调用工具"这一跳：{@code ToolCallExecutor.execute}
	 * 先调 {@code onContext}、紧接着在<b>同一个线程</b>上调 {@code invoke}。
	 * 它不跨线程、不跨请求、也不跨两次工具调用 —— 每次调用都会被重新写一遍，
	 * 所以并发的两次运行之间不会串味。
	 */
	private final ThreadLocal<String> currentRun = new ThreadLocal<>();

	/** runId → 本次运行的权限（工作区目录 + 档位）。 */
	private final Map<String, RunPermission> byRun = new ConcurrentHashMap<>();

	private final WorkspaceCommandProperties properties;

	private final ShellCommandExecutor executor;

	private final ToolFeedbackFormatter formatter;

	public RunCommandTool(WorkspaceCommandProperties properties, ShellCommandExecutor executor,
			ToolFeedbackFormatter formatter) {
		this.properties = properties;
		this.executor = executor;
		this.formatter = formatter;
	}

	@Override
	public String name() {
		return "run_command";
	}

	@Override
	public AgentToolDefinition description() {
		return new AgentToolDefinition(name(),
				"在工作区目录下执行一条 shell 命令，返回它的输出与退出码。"
						+ "⚠️ 这是写操作：命令能改动的东西不受工作区限制（工作区只是它的工作目录），"
						+ "所以**要经过人工确认才会执行**（「完全权限」档位除外）。",
				"当某件事只有真的执行才能知道结果时使用：跑构建、跑测试、跑项目里的脚本、"
						+ "用 git 看仓库状态、需要管道或重定向串起来的操作。"
						+ "改完文件之后想确认「这样写对不对」，最直接的办法就是用本工具把它跑一遍。",
				"⚠️ **看文件不要用它**：读文件用 read_file、按文件名找用 glob、按内容找用 grep、"
						+ "查文档用 search_docs —— 那些工具不需要人确认，而且输出带行号、能被引用。"
						+ "本工具一次只跑一条命令：要按顺序做几件事时，用 && 或 ; 串在一条里，"
						+ "而不是把几条命令并排写在一起让人批不清。",
				"返回：命令的退出码、耗时、以及**合并后的输出**（stdout 与 stderr 都在一起）。"
						+ "退出码非 0 是正常返回（很多命令用它表达「有差异」「有问题」），不是本工具故障。"
						+ "输出超过回填上限时会截断，并在文本里说明被截掉了多少；"
						+ "命令超过时限会被强杀（连同它起的子进程），文本里会写明这一点。",
				"命令本身写错（命令不存在、参数形式不对）时，返回的是 shell 自己的错误信息 —— "
						+ "改对再提，但注意改参数意味着**要重新经过人工确认**。"
						+ "这次调用**没有通过人工确认**时，不要重新提交同样的调用（会被再次拦住），"
						+ "请把情况用文字告诉用户。",
				ToolHints.writeRequiringApproval());
	}

	@Override
	public Class<Params> parameterType() {
		return Params.class;
	}

	/**
	 * 记下"这一次运行的权限"。
	 *
	 * <p>它由执行器在<b>授权判定之前</b>调用（见 {@code ToolCallExecutor} 的门禁 ⑤），
	 * 所以无论这次调用最后走到哪个出口（含"停在人工确认"），记录都已经放下了。
	 */
	@Override
	public void onContext(ToolCallContext context) {
		if (context == null) {
			return;
		}
		String runId = key(context.runId());
		currentRun.set(runId);
		RunPermission permission = context.runPermission();
		if (permission == null || !permission.hasWorkspace()) {
			// 上下文**在**、工作区不在：那是"这一次运行没有工作区目录"。
			// 这时要把同一个 runId 上可能留下的旧记录清掉 —— 留着它会让下一次调用
			// 按一个**这次运行已经不该用**的目录去执行命令
			byRun.remove(runId);
			return;
		}
		byRun.put(runId, permission);
		trim();
	}

	@Override
	public AgentToolResult invoke(Params parameters) {
		try {
			return run(parameters);
		}
		catch (CommandAccessException e) {
			return new AgentToolResult.Failure(e.code(), e.getMessage(), false);
		}
	}

	private AgentToolResult run(Params parameters) {
		RunPermission permission = currentPermission();
		requireWritable(permission);
		Path workingDirectory = Path.of(permission.workspaceDir());
		requireExistingDirectory(workingDirectory);

		Duration timeout = timeoutOf(parameters);
		if (timeout == null) {
			long limit = properties.maxTimeout().toSeconds();
			return new AgentToolResult.Failure("timeout_too_large",
					("timeoutSeconds=%d 超过本工具的上限 %d 秒 —— 这次**没有执行**任何命令。"
							+ "请把它改到 1~%d 之间再提；如果这条命令确实要跑更久，"
							+ "就把这件事告诉用户，由人来决定怎么办。").formatted(parameters.timeoutSeconds(), limit,
									limit),
					true);
		}

		// 回填文本的总量上限由 ToolFeedbackFormatter 持有（见 FOOTER_RESERVE_CHARS 的说明）：
		// 本工具只用它的预算，**不新增第二个上限** —— 两个上限会让"到底是谁截断的"没有答案。
		// 上限调得很小时（< 预留值）按 80% 折算，于是正文仍然留得下一点，
		// 而"截断了"这件事由回填层那一句兜住（它的 limit() 会留下标记）
		int budget = formatter.maxChars() > FOOTER_RESERVE_CHARS ? formatter.maxChars()
				: (int) Math.max(1, Math.round(formatter.maxChars() * 0.8));
		int maxOutputChars = Math.max(1, budget - FOOTER_RESERVE_CHARS);

		ShellCommandExecutor.CommandOutput result;
		try {
			result = executor.run(parameters.command(), workingDirectory, timeout, maxOutputChars);
		}
		catch (IOException e) {
			// 进程起不来与"命令跑失败了"是两件事：前者重试同样的参数不会有不同结果，
			// 后者要改的是命令本身。所以这里给的是不可重试的 Failure，并把根因原样带出来
			return new AgentToolResult.Failure("execution_not_available",
					("没能启动命令：%s。这条命令**没有执行**。"
							+ "常见原因是 shell 或工作区目录不可用 —— 请把这一点告诉用户，"
							+ "用同样的参数重试不会有不同结果。").formatted(rootMessage(e)),
					false);
		}

		log.info("run_command purpose=\"{}\" 工作区={} exit={} timedOut={} 耗时={}ms 输出={} 字符（截断={}）",
				parameters.purpose(), permission.workspaceDir(), result.exitCode(), result.timedOut(),
				result.elapsedMs(), result.outputChars(), result.outputTruncated());

		return new AgentToolResult.Success(render(permission, result, parameters), false);
	}

	/**
	 * 把结论拼成回填文本。
	 *
	 * <p>顺序是刻意的：先元信息（在哪执行、退出码、耗时），再输出正文，最后才是说明 ——
	 * 说明放在末尾是因为它正好出现在模型准备总结的位置（与 {@code ToolFeedbackFormatter}
	 * 把截断标记放末尾是同一条理由）。
	 */
	private String render(RunPermission permission, ShellCommandExecutor.CommandOutput result, Params parameters) {
		StringBuilder sb = new StringBuilder();
		sb.append("工作区：").append(permission.workspaceDir()).append(" · shell：")
				.append(ShellCommandExecutor.shellPath()).append('\n');
		sb.append("退出码：").append(result.exitCode()).append(" · 耗时：").append(result.elapsedMs()).append("ms");
		if (result.timedOut()) {
			sb.append("（**超时被杀**）");
		}
		sb.append('\n');
		sb.append("用途：").append(parameters.purpose()).append('\n');
		sb.append('\n');

		String output = result.output();
		if (output.isBlank()) {
			sb.append("（这条命令没有任何输出）");
		}
		else {
			sb.append(output.strip());
		}

		// 【说明必须留下】它们与正文共享同一份回填预算，而"被截断了 / 解码丢字了 / 超时了"
		// 正是模型判断这份输出可不可信的唯一依据 —— 所以正文的额度里已经预留了这一段
		if (result.timedOut()) {
			sb.append("\n\n【注意】这条命令没有在时限内结束，已经被强制停止（它起的子进程一起被停掉了）。"
					+ "上面的输出是**它被杀之前**的内容，不完整；"
					+ "要接着做这件事，请把命令改成能在时限内跑完的形式（显式给 timeoutSeconds、"
					+ "缩小范围、或分成几步执行）。");
		}
		if (result.outputTruncated()) {
			sb.append(("\n\n【注意】输出被截断了：这条命令一共产生了 %d 个字符，上面只显示了前 %d 个。"
					+ "★ 不要用这份内容下「命令只输出了这些」的结论 —— "
					+ "要看剩下的部分，请把命令改成只输出你真正需要的那一段（加过滤、只看尾部）。")
					.formatted(result.outputChars(), output.length()));
		}
		if (result.decodeLossy()) {
			sb.append("\n\n【注意】输出里有无法按当前编码解释的字节（已用「�」标记）。"
					+ "这通常是命令输出用的编码与配置不一致，也可能是输出里本来就有二进制内容；"
					+ "★ 不要把带「�」的片段当成原文引用。");
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------
	//  边界与档位
	// ------------------------------------------------------------------

	/**
	 * 本次运行的权限。
	 *
	 * <p>三种"拿不到"各有明确行为，没有一种是"猜一个目录"：
	 * <ul>
	 *   <li><b>本线程上没有记录</b>（有人绕过循环直接调执行器）→ 没有工作区；</li>
	 *   <li><b>有记录但本次运行没有工作区</b> → 同上；</li>
	 *   <li><b>有权限但工作区目录现在不在了</b> → 由 {@link #requireExistingDirectory} 单独说清。</li>
	 * </ul>
	 */
	private RunPermission currentPermission() {
		String runId = currentRun.get();
		if (runId == null) {
			// 【这一条不是防重复】本线程上没有记录说明 {@code onContext} 没被调过
			// （有人绕过循环直接调 invoke）。那时按最严的一侧算：
			// 没有工作区、命令一个字节都不执行。**刻意不回落到 NO_RUN 那一格** ——
			// 那一格是留给"外部链路的一次运行"的，里面的权限属于**另一次运行**
			throw new CommandAccessException(CommandAccessException.NO_WORKSPACE,
					"这次调用没有经过执行器（没有工作区上下文），所以命令**没有执行**。"
							+ "请告诉用户这是实现上的问题，不要重试。");
		}
		RunPermission permission = byRun.get(runId);
		if (permission == null || !permission.hasWorkspace()) {
			throw new CommandAccessException(CommandAccessException.NO_WORKSPACE,
					"本次运行没有工作区目录（发起任务时的 workspaceDir 为空），命令没有可执行的工作目录，"
							+ "所以这次调用**没有执行**任何命令。请告诉用户：要执行命令需要重新发起任务"
							+ "并填入工作区目录；或者直接用文字回答 —— 不要改写成绝对路径重试，那条路同样走不通。");
		}
		return permission;
	}

	/**
	 * 仅可查看档位下不许执行 —— 唯一落点。
	 *
	 * <p>与 {@code FileToolSupport#requireWritable} 同一位置、同一理由：其余两档放行，
	 * 它们的差别不在"能不能执行"，而在"要不要人点头"，那个判断在闸门里。
	 */
	private void requireWritable(RunPermission permission) {
		if (!permission.level().workspaceWrite()) {
			throw new CommandAccessException(CommandAccessException.NOT_PERMITTED,
					("权限档位是「%s」，这一档不允许执行命令（它是「仅可查看」）。"
							+ "请把这一点告诉用户：要执行命令需要重新发起任务并选「工作区内修改」或「完全权限」——"
							+ "原样重试不会有不同结果。").formatted(permission.level().label()));
		}
	}

	/**
	 * 工作区目录必须现在确实是一个目录。
	 *
	 * <p>不查的话，{@code ProcessBuilder} 会抛一个 {@code CreateProcess error=267}（目录名无效），
	 * 而那条消息看起来像"命令写错了"。这里先查一次，把结论说成"工作区目录现在不是目录"。
	 */
	private void requireExistingDirectory(Path workingDirectory) {
		if (Files.isDirectory(workingDirectory)) {
			return;
		}
		throw new CommandAccessException(CommandAccessException.NO_WORKSPACE,
				("工作区目录「%s」现在不是一个存在的目录（它可能已被删除或改名），命令没有可执行的工作目录，"
						+ "所以这次调用**没有执行**任何命令。请告诉用户重新发起任务并选一个存在的目录 —— "
						+ "原样重试不会有不同结果。").formatted(workingDirectory));
	}

	/**
	 * 本次调用的时限。
	 *
	 * @return 可用的时限；请求值超过上限时返回 {@code null}（由调用方给出明确回填）
	 */
	private Duration timeoutOf(Params parameters) {
		Integer requested = parameters.timeoutSeconds();
		if (requested == null) {
			return properties.timeout();
		}
		Duration limit = properties.maxTimeout();
		if (Duration.ofSeconds(requested).compareTo(limit) > 0) {
			return null;
		}
		return Duration.ofSeconds(requested);
	}

	/** 把异常剥到最内层消息（同 {@code ToolCallExecutor} 的做法：不留整条包装链）。 */
	private static String rootMessage(Throwable e) {
		Throwable cause = e;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		String message = cause.getMessage();
		if (message == null || message.isBlank()) {
			return cause.getClass().getSimpleName();
		}
		return message.length() > 300 ? message.substring(0, 300) + "…" : message;
	}

	private static String key(String runId) {
		return runId == null || runId.isBlank() ? NO_RUN : runId;
	}

	/**
	 * 把太久没人取的记录丢掉。
	 *
	 * <p>同 {@code GetSkillContentTool#trim}：{@code ConcurrentHashMap} 的迭代顺序不确定，
	 * 所以丢掉的是"任意一些"而不是"最旧的" —— 下一次调用会把这次运行的权限重新放回来
	 * （{@code onContext} 在每次执行之前都会走一遍）。
	 */
	private void trim() {
		if (byRun.size() <= MAX_TRACKED_RUNS) {
			return;
		}
		int excess = byRun.size() - MAX_TRACKED_RUNS;
		for (String key : byRun.keySet()) {
			if (excess <= 0) {
				break;
			}
			if (!NO_RUN.equals(key) && byRun.remove(key) != null) {
				excess--;
			}
		}
	}

	/**
	 * 参数。
	 *
	 * <h2>{@code timeoutSeconds} 为什么是可空的包装类型</h2>
	 * 不传时它是 {@code null}（走配置里的默认时限），而基本类型 {@code int} 不传会反序列化成
	 * {@code 0} —— 一个看起来完全合法的值，与本工具的语义（时限必须为正）正好相反。
	 * 上限<b>不写在这里</b>：它是配置项（{@code ai.agent.workspace.command.max-timeout}），
	 * 而注解的参数必须是编译期常量。所以 schema 只声明 {@code minimum: 1}，
	 * 上限由工具在运行期判，并把具体的数字回填给模型。
	 *
	 * <h2>{@code purpose} 是给人看的字段</h2>
	 * 审批页面上原样显示的是这份参数的 JSON，而一条命令行在 JSON 里是一段转义文本 ——
	 * 人能读它，但很难一眼看出"这次到底要干什么"。这与 {@code execute_sql} 的
	 * {@code purpose} 是同一条规矩（那边有完整的判据）。
	 */
	public record Params(
			@NotBlank(message = "command 不能为空：它是要交给 shell 执行的那一条命令")
			@Size(max = WorkspaceCommandProperties.MAX_COMMAND_CHARS,
					message = "command 过长（上限 " + WorkspaceCommandProperties.MAX_COMMAND_CHARS
							+ " 字符）；请拆成多次调用，或把命令写得更短")
			String command,

			@Min(value = 1, message = "timeoutSeconds 是执行时限（秒），至少是 1")
			Integer timeoutSeconds,

			@NotBlank(message = "purpose 不能为空：用一句话说明这条命令要达成什么（人要靠它决定批不批）")
			@Size(max = 200, message = "purpose 是一句话，不要超过 200 字符")
			String purpose) {
	}

}
