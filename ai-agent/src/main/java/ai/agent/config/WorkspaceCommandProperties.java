package ai.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.Charset;
import java.time.Duration;

/**
 * <b>命令执行工具（{@code run_command}）的边界</b>（前缀 {@code ai.agent.workspace.command}）。
 *
 * <h2>为什么与 {@link WorkspaceFileProperties} 合不到一个类里</h2>
 * 那个类管的是"读进来多少、写出去多大"（回填给模型的<b>上下文预算</b>），
 * 本类管的是"一条命令最多跑多久、它的输出按哪个编码读"。
 * 两件事的读者不同：上限那一组由写工具的人调，本类由<b>部署环境</b>决定 ——
 * 同一份代码在 Windows 与 Linux 上该用不同的编码。
 *
 * <h2>为什么 {@code charset} 必须是一个配置项</h2>
 * 子进程输出按哪个编码编码，由<b>被执行的程序</b>决定，而我们看不到它。
 * 本机实测（{@code java -XshowSettings:properties}）：{@code native.encoding=GBK}、
 * {@code file.encoding=UTF-8}，而 {@code cmd /d /s /c echo 中文测试} 输出的是 GBK 字节。
 * 所以这里<b>不猜第二套编码</b>去兜底：解码出错时按 {@code U+FFFD} 替换
 * （实测 {@code new String(new byte[]{(byte)0xD6}, UTF_8)} → {@code U+FFFD}），
 * 并把"可能是编码不对 + 当前取的是哪个编码"写进回填文本。
 * 两套编码互相兜底会让"我这个配置写错了"变成一件看不出来的事 ——
 * 现象只是"输出里偶尔有问号"。
 *
 * @param timeout 模型不传 {@code timeoutSeconds} 时的默认执行时限。
 *            <p>30 秒的依据：本项目最耗时的工具调用是检索（毫秒级）与调模型（1~4 秒），
 *            30 秒能容下"跑一次构建 / 一次测试"这类命令；而 {@code ai.agent.approval.timeout}
 *            是 120 秒，默认时限必须明显小于它，否则"人批完之后命令又超时"会让人以为审批没生效。
 * @param maxTimeout 模型可以申请的上限（{@code timeoutSeconds} 超过它就拒绝并回填这个值）。
 *            <p>300 秒的依据：它是"人愿意在审批页面上等"的量级上限；命令执行期间占着
 *            并发闸门的一个名额（{@code ai.agent.concurrency.max-concurrent-tool-requests}），
 *            而 {@code ai.agent.loop.max-duration-ms} 是 120000 —— 一次命令跑 5 分钟时，
 *            循环那一层的总时限会先把这次运行停住。
 * @param charset 读子进程输出用的编码。默认取 JVM 的 {@code native.encoding}
 *            （见 {@code application.properties} 里那一行），取不到时退到 JVM 默认编码。
 */
@ConfigurationProperties(prefix = "ai.agent.workspace.command")
public record WorkspaceCommandProperties(Duration timeout, Duration maxTimeout, Charset charset) {

	/**
	 * 命令字符串的字符上限。
	 *
	 * <p>它是<b>编译期常量</b>而不是配置项（与 {@code JdbcSqlExecutor} 那五个上限同一条做法）：
	 * 这个数同时进 JSON Schema（模型看得到的 {@code maxLength}）与 JSR-380 校验，
	 * 一处定义、两处生效，不可能漂移。要改就得改代码重新编译。
	 *
	 * <p>2000 的依据：它容得下"几行命令行拼起来的一条命令"（含引号与路径），
	 * 而又明显短于一次工具调用该占的上下文预算 —— 一条 2000 字符的命令本身就往回填里
	 * 塞了同量级的内容。
	 */
	public static final int MAX_COMMAND_CHARS = 2000;

	public WorkspaceCommandProperties {
		requirePositive(timeout, "timeout");
		requirePositive(maxTimeout, "max-timeout");
		if (maxTimeout.compareTo(timeout) < 0) {
			// 反过来的话，"默认值"本身就越过了上限：模型不传参数会被拒，
			// 而现象是"这把工具压根调不通"，看起来像工具坏了
			throw new IllegalArgumentException(
					"ai.agent.workspace.command.max-timeout（%s）不能小于 timeout（%s）".formatted(maxTimeout, timeout));
		}
		if (charset == null || charset.name().isBlank()) {
			throw new IllegalArgumentException("ai.agent.workspace.command.charset 不能为空");
		}
	}

	private static void requirePositive(Duration value, String name) {
		if (value == null || value.isZero() || value.isNegative()) {
			// 与 AgentApprovalProperties.timeout 同一条规矩：0/负数表示"永远等下去"，
			// 而这把工具在超时那一刻要杀进程树 —— 一个写错的配置会在启动时就暴露，
			// 而不是在第一次调用时表现为"命令跑起来就不回来了"
			throw new IllegalArgumentException(
					"ai.agent.workspace.command.%s 必须是正数（收到 %s）".formatted(name, value));
		}
	}

}
