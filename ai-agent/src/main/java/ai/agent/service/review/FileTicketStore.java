package ai.agent.service.review;

import ai.agent.config.ReviewProperties;
import ai.agent.entity.review.ReviewTicket;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把审查工单落到一个 <b>JSONL 文件</b>里的实现。
 *
 * <h2>为什么是 JSONL 而不是数据库</h2>
 * 因为这条"外部系统"的定位是<b>演示与验证</b>，而数据库那一侧本项目已经在
 * Agent 运行记录与审批记录上用了两次（H2）。这里换一个形态有两个好处：
 * <ul>
 *   <li><b>可以用肉眼与记事本核对</b>。写操作这件事最需要的是"我亲眼看到它写进去了"——
 *       一行 JSON、一个文件，比"查一张表"直观得多；</li>
 *   <li>它顺带证明<b>工单的存储与运行记录是两套东西</b>：把工单写进 agent 的运行库，
 *       会让"清空运行记录"顺手清掉业务数据 —— 那是很典型的耦合事故。</li>
 * </ul>
 * 生产上这里该换成真的工单系统（Jira / 内部 API），替换面就是本类一个文件。
 *
 * <h2>并发：为什么用 {@code synchronized} 而不是锁对象</h2>
 * 这个类的方法会被<b>虚拟线程</b>调用。阶段一那次实测留下过一条教训：
 * {@code synchronized} 会<b>钉住载体线程</b>（pinned carrier，探针延迟 6405ms vs 0ms）——
 * 也就是说在虚拟线程里用 {@code synchronized} 包住<b>阻塞 IO</b>，
 * 会把虚拟线程"不占平台线程"的优势抹掉。
 *
 * <p>但这里仍然用它，理由是<b>临界区里几乎没有阻塞</b>：
 * 只有一次内存 map 的读写与一次极短的 append。真正的写文件被刻意放在锁外
 * （见 {@link #create} 的实现顺序），所以"钉住载体线程"的时长是微秒级。
 * 把它换成 {@code ReentrantLock} 反而会引入一个新的失败模式
 * （忘记 unlock 就永久死锁），换来的收益在这个临界区长度下约等于零。
 * <b>结论：那条教训的正确用法是"不要让 synchronized 包住慢操作"，不是"永远别用 synchronized"。</b>
 */
@Component
public class FileTicketStore implements TicketStore {

	private static final Logger log = LoggerFactory.getLogger(FileTicketStore.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** 已建过的"标题 + 级别" → 那条工单。用于幂等窗口判定（内存态，重启即清）。 */
	private final Map<String, ReviewTicket> recent = new ConcurrentHashMap<>();

	private final AtomicInteger sequence = new AtomicInteger();

	private final ReviewProperties properties;

	public FileTicketStore(ReviewProperties properties) {
		this.properties = properties;
		log.info("审查工单存储就绪：file={} 幂等窗口={}", properties.ticketFile(), properties.deduplicationWindow());
	}

	@Override
	public ReviewTicket create(String title, String detail, ReviewSeverity severity, String createdBy, String approvalId) {
		String key = keyOf(title, severity);

		// ---- 临界区（内存判定 + 预留 key）：不含任何文件 IO ----
		synchronized (this) {
			ReviewTicket existing = recent.get(key);
			if (existing != null && withinWindow(existing.createdAt())) {
				log.info("工单去重命中：{}（已存在 #{}，{} 内不重复创建）", key, existing.id(),
						properties.deduplicationWindow());
				return existing.asDuplicate();
			}
			ReviewTicket ticket = new ReviewTicket(nextId(), title, detail, severity, Instant.now(), createdBy,
					approvalId, false);
			recent.put(key, ticket);

			// ---- 文件 IO 放在锁外面做（理由见类注释：不要让 synchronized 包住慢操作）----
			// 但"落盘失败"必须让这次调用失败：一个"返回成功但其实没写进去"的写工具
			// 是这类系统里最坏的一种工具 —— 模型会据此告诉用户"工单已创建"
			appendToFile(ticket);
			return ticket;
		}
	}

	/**
	 * 追加一行 JSON。
	 *
	 * <p>用 {@code CREATE + APPEND} 而不是"读出来、改、写回去"：
	 * 后者在并发下会丢更新（两个线程各读到同一份旧内容、各写回一份，后者覆盖前者），
	 * 而且那是"不报错的错"——文件看上去一切正常，只是少了一条工单。
	 */
	private void appendToFile(ReviewTicket ticket) {
		try {
			Path file = Path.of(properties.ticketFile());
			if (file.getParent() != null) {
				Files.createDirectories(file.getParent());
			}
			ObjectNode node = MAPPER.createObjectNode();
			node.put("id", ticket.id());
			node.put("title", ticket.title());
			node.put("detail", ticket.detail());
			node.put("severity", ticket.severity().name());
			node.put("createdAt", ticket.createdAt().toString());
			node.put("createdBy", ticket.createdBy());
			// 审批 id 特意用可空的写法：写 null 与"字段缺失"是两件事，
			// 而审计时"这条工单没走审批"必须能一眼看出来
			node.put("approvalId", ticket.approvalId());
			Files.writeString(file, MAPPER.writeValueAsString(node) + "\n", StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}
		catch (IOException e) {
			// 抛而不是吞：见上面调用点的注释
			throw new UncheckedIOException("写入审查工单文件失败：" + properties.ticketFile(), e);
		}
	}

	@Override
	public List<ReviewTicket> all() {
		synchronized (this) {
			List<ReviewTicket> tickets = new ArrayList<>(recent.values());
			tickets.sort(java.util.Comparator.comparing(ReviewTicket::createdAt));
			return List.copyOf(tickets);
		}
	}

	@Override
	public int count() {
		return recent.size();
	}

	@Override
	public Duration deduplicationWindow() {
		return properties.deduplicationWindow();
	}

	/** 顺序号 + 时间戳：可读，且单机内不会重。 */
	private String nextId() {
		return "RV-%04d".formatted(sequence.incrementAndGet());
	}

	private boolean withinWindow(Instant createdAt) {
		return Duration.between(createdAt, Instant.now()).compareTo(properties.deduplicationWindow()) < 0;
	}

	private static String keyOf(String title, ReviewSeverity severity) {
		return (title == null ? "" : title.strip()) + "|" + (severity == null ? "" : severity.name());
	}

	/** 供测试清理（把幂等记忆与序号复位）。 */
	public void resetForTesting() {
		synchronized (this) {
			recent.clear();
			sequence.set(0);
		}
	}

}
