package ai.agent.service.impl.tool.command;

import ai.agent.config.WorkspaceCommandProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * <b>起一个子进程跑一条命令，把输出与退出码带回来</b> —— {@code run_command} 的执行者。
 *
 * <h2>为什么单独一个类，而不是写在工具里</h2>
 * 与本项目 {@code JdbcSqlExecutor} 同一个理由：上限与失败路径收在一处，
 * 而工具那一层只负责"把结论说给模型听"。它<b>刻意不实现 {@code AgentTool}</b>，
 * 于是可以被单独调用验证（本项目的后端测试代码已整体移除，验证靠一次性验证类直接调它）。
 *
 * <h2>命令怎么拼：交给系统自带的 shell，而不是自己分词</h2>
 * <pre>
 *   Windows  [ComSpec（默认 C:\WINDOWS\system32\cmd.exe）, /d, /s, /c, 命令]
 *   Unix     [/bin/sh, -c, 命令]
 * </pre>
 * <b>不自己分词</b>的理由：模型写的是一条命令行（管道、重定向、引号、{@code &} 串联），
 * 按空格切开会把 {@code echo a & echo b} 拆成四条互不相干的参数，
 * 而那时回填里给出的失败原因会是"命令不存在"，与真正的问题差了很远。
 *
 * <p>Windows 用 {@code /d} 关掉注册表里的 AutoRun（那是"每次起 cmd 都会先跑的一段命令"，
 * 它会让同一条命令在两台机器上做不同的事）。本机实测 {@code /d /s /c} 下：
 * {@code echo A & echo B} 输出两行、{@code cd} 输出进程工作目录、
 * {@code exit /b 3} 退出码为 3、不存在的命令退出码为 1 且报
 * {@code 'xxx' 不是内部或外部命令}。
 *
 * <h2>工作目录是<b>调用方给的</b>，不由命令决定</h2>
 * 本类只接受一个 {@code workingDirectory} 并把它设成进程的工作目录 ——
 * 它<b>不解析任何路径</b>，也不判断那个目录在不在工作区内。边界判定只有一处
 * （文件工具那一套 {@code FileAccess}），这里再做一遍就是第二份判据。
 *
 * <h2>三个必须做对的地方（每一个错了都不报错）</h2>
 * <ol>
 *   <li><b>输出必须并发读</b>。只 {@code waitFor} 不读，输出一旦超过管道缓冲区
 *       （Windows 约 4KB、Linux 约 64KB）子进程就写不动了，于是它会永远等着，
 *       而工具看起来只是"命令很慢"；</li>
 *   <li><b>超时必须杀整棵进程树</b>。只杀直接子进程的话，
 *       {@code cmd.exe} 死了而它起的编译进程还在跑、还在往管道里写 ——
 *       现象是"审批说已经超时停止了，机器上还在编译"；</li>
 *   <li><b>收尾读取必须有界</b>。孙进程继承了管道的写端时，管道在它们退出之前不会关闭，
 *       读取线程就会一直挂着。所以它只 join 一小段时间，剩下的一律放弃 ——
 *       一个赖着不走的孙进程不该把这次工具调用永远卡住。</li>
 * </ol>
 *
 * <h2>输出上限的单位是<b>字符</b>，读的时候按字节有界累积</h2>
 * 上限的消费者是回填文本（模型看到的字符数），而读的时候只能拿到字节。
 * 所以读取预算按 {@code maxChars * 4 + 64KB} 字节算（UTF-8 下一个字符最多 4 字节），
 * 读满就停止累积、继续丢弃。多出来的 64KB 是留给"解码后再按字符截断"的余量：
 * 若不留给它，一个全是中文的输出会在解码之后立刻撞上字符上限，
 * 而那时已经无法知道"到底还差多少"。
 */
@Component
public class ShellCommandExecutor {

	private static final Logger log = LoggerFactory.getLogger(ShellCommandExecutor.class);

	private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

	/** Windows 上 cmd.exe 的兜底路径（读不到 {@code ComSpec} 时用）。 */
	private static final String WINDOWS_FALLBACK_SHELL = "C:\\WINDOWS\\system32\\cmd.exe";

	/** 逐字节读取时每次要的块大小。 */
	private static final int READ_CHUNK = 8192;

	/** 读取预算里留给"解码后再按字符截断"的余量。 */
	private static final int READ_BUDGET_SLACK_BYTES = 64 * 1024;

	/** 等待输出流关闭的上限：孙进程继承了管道写端时，只能放弃。 */
	private static final Duration DRAIN_JOIN_TIMEOUT = Duration.ofSeconds(1);

	/** 等进程结束时的轮询间隔。 */
	private static final long POLL_MILLIS = 20;

	/** 杀进程树之后等它真正退出的上限。 */
	private static final Duration KILL_GRACE = Duration.ofSeconds(3);

	private final Charset charset;

	/**
	 * @param commandProperties 命令执行的边界。编码在这里被<b>打一行 INFO</b>：
	 *            它的默认值来自 {@code native.encoding} 这个系统属性，
	 *            而那个表达式解不出来时 Spring 会安静地给 null、退到 JVM 默认编码 ——
	 *            于是"我配了 GBK 但实际用的是 UTF-8"会变成一件看不出来的事
	 *            （现象只是回填里偶尔有 U+FFFD）。一行日志是这件事唯一的运行时证据
	 */
	public ShellCommandExecutor(WorkspaceCommandProperties commandProperties) {
		this.charset = commandProperties.charset();
		log.info("命令执行工具就绪：shell={} 编码={}（来自 ai.agent.workspace.command.charset）", shellPath(),
				charset.name());
	}

	/** 当前配置的编码（给工具与验证类读；判据在配置那一层）。 */
	public Charset charset() {
		return charset;
	}

	/**
	 * 一次命令执行的结果。
	 *
	 * @param exitCode 进程退出码。<b>非 0 不是本类的失败</b>：很多命令"有输出才说明有问题"
	 *            （{@code git diff} 有差异时退出码是 1），所以它原样带回给模型
	 * @param output 合并 stderr 之后的输出（已按字符上限截断）
	 * @param elapsedMs 从起进程到现在实际花掉的毫秒数
	 * @param timedOut 是否因为超时被强杀（被杀时 {@code exitCode} 是 {@code destroyForcibly()}
	 *            产生的值，不代表命令自己的结论）
	 * @param outputTruncated 输出是否因为超过字符上限被截断
	 * @param outputChars 截断前的字符数（"还有多少没看到"靠它算出来）
	 * @param decodeLossy 解码时出现过无法映射的字节（输出里出现了 {@code U+FFFD}）——
	 *            这几乎总是"编码配错了"，必须让模型与人都看得见
	 */
	public record CommandOutput(int exitCode, String output, long elapsedMs, boolean timedOut, boolean outputTruncated,
			long outputChars, boolean decodeLossy) {
	}

	/**
	 * 跑一条命令。
	 *
	 * @param command 模型给的整条命令行（原样交给 shell，不分词）
	 * @param workingDirectory 子进程的工作目录（<b>由调用方判定过边界</b>，本类不解析路径）
	 * @param timeout 执行时限；到点强杀进程树
	 * @param maxOutputChars 回填文本的输出字符上限（消费者是模型，所以单位是字符）
	 * @throws IOException 进程起不来（可执行文件不在、目录不存在、权限不足）
	 */
	public CommandOutput run(String command, Path workingDirectory, Duration timeout, int maxOutputChars)
			throws IOException {
		List<String> argv = commandLine(command);
		long startedAt = System.nanoTime();
		log.info("run_command 启动命令 workingDirectory={} shell={} command=\"{}\" timeout={}s", workingDirectory,
				argv.get(0), oneLine(command), timeout.toSeconds());

		ProcessBuilder builder = new ProcessBuilder(argv).directory(workingDirectory.toFile());
		// stderr 合并进 stdout：**分开读会需要第二个读取线程**，而它们之间的顺序对模型毫无价值
		builder.redirectErrorStream(true);
		// stdin 接空设备：不接的话子进程可能停在那里等输入，而调用方没有任何输入可给
		builder.redirectInput(nullDevice().toFile());

		Process process = builder.start();
		int readBudget = readBudgetOf(maxOutputChars);
		ByteAccumulator sink = new ByteAccumulator(readBudget);
		Thread reader = startReader(process.getInputStream(), sink);

		boolean finished = waitFor(process, timeout);
		if (!finished) {
			killTree(process);
		}
		closeStdin(process);

		// 【退出码只读一次，之后只用这一个值】它分两种来源：
		// 正常结束的读它自己的结论（实测：cmd 的 exit /b 3 → 3），超时的走 killedExitCode()。
		// 判据是 `finished` 这个布尔，而不是"再读一次 exitValue()" ——
		// 后者在进程已被强杀时会读到"被杀"产生的值，于是"命令超时被杀"与"命令自己返回了 1"
		// 会被读成同一件事
		Integer exitCode = finished ? process.exitValue() : killedExitCode(process);

		// 进程已经结束（或被杀），再给读取线程一小段收尾时间。超时就不再等：
		// 孙进程持有管道写端时，这个流可能永远不关闭（见类注释第 ③ 条）
		joinQuietly(reader, DRAIN_JOIN_TIMEOUT);
		if (reader.isAlive()) {
			log.warn("run_command 的输出流在进程结束后仍被占用（可能有孙进程继承了管道），本次放弃剩余输出：{}",
					oneLine(command));
		}

		long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
		Decoded decoded = decode(sink.toByteArray(), charset);
		String text = decoded.text();
		long totalChars = text.length();
		boolean truncated = totalChars > maxOutputChars;
		String output = truncated ? text.substring(0, maxOutputChars) : text;

		// 【为什么非 0 退出码只记 INFO】走到这里说明命令正常跑完了，它的结论是"失败"
		// —— 那是模型的活（它要改命令还是改方案由它判断），不是本类或工具出故障。
		// 记成 WARN 会让"某条命令按预期返回非 0"在日志里看起来像系统问题
		log.info("run_command 结束 exit={} timedOut={} elapsed={}ms 输出={} 字符（截断={} 解码丢字={}）：{}",
				exitCode, !finished, elapsedMs, totalChars, truncated, decoded.lossy(), oneLine(command));

		return new CommandOutput(exitCode, output, elapsedMs, !finished, truncated, totalChars, decoded.lossy());
	}

	/**
	 * 这条命令对应的 argv。
	 *
	 * <p>单独抽出来是<b>为了能被逐条核对</b>：平台分支只有这一处，
	 * 而它的每个位置参数都有含义（见类注释）。做成公开的静态方法，
	 * 一次性验证类可以直接打印它在两个平台上的结论。
	 */
	public static List<String> commandLine(String command) {
		List<String> argv = new ArrayList<>(5);
		if (WINDOWS) {
			argv.add(shellPath());
			argv.add("/d");
			argv.add("/s");
			argv.add("/c");
		}
		else {
			argv.add("/bin/sh");
			argv.add("-c");
		}
		argv.add(command);
		return List.copyOf(argv);
	}

	/** 当前平台用来执行命令的 shell 的可执行文件路径。 */
	public static String shellPath() {
		if (!WINDOWS) {
			return "/bin/sh";
		}
		String comSpec = System.getenv("ComSpec");
		return comSpec == null || comSpec.isBlank() ? WINDOWS_FALLBACK_SHELL : comSpec;
	}

	/** 读取预算：字符上限按 UTF-8 最坏情况折算成字节，再留一段截断余量。 */
	private static int readBudgetOf(int maxOutputChars) {
		long budget = (long) Math.max(maxOutputChars, 0) * 4 + READ_BUDGET_SLACK_BYTES;
		return (int) Math.min(budget, Integer.MAX_VALUE - 8L);
	}

	/** 在虚拟线程上读输出。<b>必须并发读</b>，理由见类注释第 ① 条。 */
	private static Thread startReader(InputStream in, ByteAccumulator sink) {
		return Thread.ofVirtual().name("run-command-reader").start(() -> {
			byte[] chunk = new byte[READ_CHUNK];
			try (in) {
				while (true) {
					int read = in.read(chunk);
					if (read < 0) {
						return;
					}
					sink.append(chunk, read);
				}
			}
			catch (IOException e) {
				// 进程被杀时管道会先断，这个异常是预期路径的一部分（超时那一条走的就是它）。
				// 不作为失败上报：真正的结论由退出码与 elapsed 表达
				log.debug("run_command 读取输出时管道关闭：{}", e.getMessage());
			}
		});
	}

	/**
	 * 等进程结束，最多等 {@code timeout}。
	 *
	 * <p>用"剩余时间"轮询而不是一次 {@code waitFor(全部时间)}：一次等待没法在中途做别的检查，
	 * 而这里需要的是"到点就杀"。轮询间隔 20ms 的代价可以忽略
	 * （一次命令的量级是秒），换来的是超时的判定不受系统调度影响。
	 *
	 * @return 是否在时限内结束
	 */
	private static boolean waitFor(Process process, Duration timeout) {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (true) {
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0) {
				return !process.isAlive();
			}
			try {
				if (process.waitFor(Math.max(1, Math.min(remaining / 1_000_000, POLL_MILLIS)), TimeUnit.MILLISECONDS)) {
					return true;
				}
			}
			catch (InterruptedException e) {
				// 被中断时先恢复中断标记，再把"没结束"这个事实返回去 —— 由调用方走超时那一条
				// （它会把进程树杀掉）。吞掉中断会让上层以为一切正常
				Thread.currentThread().interrupt();
				return !process.isAlive();
			}
		}
	}

	/**
	 * 杀整棵进程树。
	 *
	 * <p><b>先杀后代、再杀直接子进程</b>：反过来的话，{@code cmd.exe} 先死，
	 * 而它起的那些进程会被系统改挂到别处（或变成孤儿），{@code descendants()} 就找不全了。
	 * 实测（本机）：先取 {@code descendants()}，{@code ping -n 30} 那条命令下有 2 个后代，
	 * 强杀后归零，全程 29ms。
	 */
	private static void killTree(Process process) {
		List<ProcessHandle> descendants = List.copyOf(process.descendants().toList());
		for (ProcessHandle handle : descendants) {
			handle.destroyForcibly();
		}
		process.destroyForcibly();
		// 等它真正退出：不等的话进程树里的残留可能在下一次调用时才被发现，
		// 而那时的现象是"机器上多了一堆 build 进程"，与这次工具调用已经对不上了
		try {
			process.waitFor(KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		log.warn("run_command 超时，已强杀进程树：后代 {} 个（pid {}）", descendants.size(),
				descendants.stream().map(ProcessHandle::pid).toList());
	}

	/** 被强杀时读退出码：读不到就用 -1（它只在"被杀"这一路上出现，不代表命令的结论）。 */
	private static int killedExitCode(Process process) {
		try {
			return process.isAlive() ? -1 : process.exitValue();
		}
		catch (IllegalThreadStateException e) {
			return -1;
		}
	}

	/** 关掉 stdin：{@code cmd.exe} 的批处理在某些写法下会去读它。 */
	private static void closeStdin(Process process) {
		try {
			OutputStream in = process.getOutputStream();
			in.close();
		}
		catch (IOException e) {
			log.debug("run_command 关闭 stdin 失败（忽略）：{}", e.getMessage());
		}
	}

	private static void joinQuietly(Thread thread, Duration limit) {
		try {
			thread.join(limit.toMillis());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static Path nullDevice() {
		return Path.of(WINDOWS ? "NUL" : "/dev/null");
	}

	/**
	 * 解码。
	 *
	 * <p>用 {@code REPORT} + {@code REPLACE} 的显式组合，而不是平台的默认行为：
	 * {@code REPORT} 让我知道"这个编码解不了这段字节"，{@code REPLACE} 让它变成
	 * {@code U+FFFD} 而不是抛异常。两个一起用，才是"能解就解、解不了要留证据"。
	 */
	private static Decoded decode(byte[] bytes, Charset charset) {
		CharsetDecoder decoder = charset.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT);
		try {
			CharBuffer chars = decoder.decode(ByteBuffer.wrap(bytes));
			String text = chars.toString();
			return new Decoded(text, text.indexOf('\uFFFD') >= 0);
		}
		catch (CharacterCodingException e) {
			// 整体解码失败（例如 GBK 配了一段 UTF-8 字节）：退回"替换"模式读出能读的部分，
			// 并把 lossy 标出来 —— 让回填文本去说"可能是编码不对"
			CharsetDecoder replacing = charset.newDecoder()
					.onMalformedInput(CodingErrorAction.REPLACE)
					.onUnmappableCharacter(CodingErrorAction.REPLACE);
			try {
				CharBuffer chars = replacing.decode(ByteBuffer.wrap(bytes));
				return new Decoded(chars.toString(), true);
			}
			catch (CharacterCodingException unreachable) {
				// REPLACE 模式下不会再抛；真抛了说明 charset 本身有问题，退回 ISO-8859-1
				// （它对任意字节都有映射，只会让中文变成问号，但至少内容不丢结构）
				String fallback = new String(bytes, StandardCharsets.ISO_8859_1);
				log.warn("run_command 输出解码失败，退回 ISO-8859-1：{}", unreachable.getMessage());
				return new Decoded(fallback, true);
			}
		}
	}

	/**
	 * 解码结果。
	 *
	 * @param text 文本
	 * @param lossy 是否出现过无法映射的字节（{@code U+FFFD}）
	 */
	private record Decoded(String text, boolean lossy) {
	}

	/**
	 * 有界字节累积。
	 *
	 * <p>读满预算之后<b>继续丢弃</b>而不是停止读取：不读的话子进程会卡在写管道上，
	 * 于是"输出太多"会变成"命令不返回"，那是两种完全不同的失败。
	 */
	private static final class ByteAccumulator {

		private final byte[] buffer;

		private int size;

		private boolean overflowed;

		ByteAccumulator(int capacity) {
			this.buffer = new byte[Math.max(capacity, READ_CHUNK)];
		}

		synchronized void append(byte[] chunk, int length) {
			int room = buffer.length - size;
			if (room <= 0) {
				overflowed = true;
				return;
			}
			int copy = Math.min(room, length);
			System.arraycopy(chunk, 0, buffer, size, copy);
			size += copy;
			if (copy < length) {
				overflowed = true;
			}
		}

		synchronized byte[] toByteArray() {
			if (overflowed) {
				// 读数预算撞到时说出来：丢弃的部分没有任何记录，唯一能说清的只有这一行日志
				log.warn("run_command 输出超过读取预算 {} 字节，已丢弃后续内容", buffer.length);
			}
			return java.util.Arrays.copyOf(buffer, size);
		}
	}

	/** 日志里的一行摘要（超长截断，避免把整条命令写进日志）。 */
	static String oneLine(String text) {
		String flat = text == null ? "" : text.replaceAll("\\s+", " ").strip();
		return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
	}

}
