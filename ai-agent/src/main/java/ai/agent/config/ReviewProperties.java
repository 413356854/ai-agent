package ai.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 审查工单（本项目选定的"外部系统"）的配置。
 *
 * @param ticketFile 工单落盘的 JSONL 文件路径。默认写到 {@code ./data/review-tickets.jsonl} ——
 *                   它<b>与 Agent 运行库分开</b>是刻意的：工单是业务数据，
 *                   而运行记录是过程数据，两者的保留策略与清理时机完全不同
 *                   （清掉过程记录不该顺手清掉业务数据）
 * @param deduplicationWindow 幂等窗口：同一个"标题 + 级别"在这个时长内只建一条工单。
 *                  它是"重复副作用"的第二道防线（第一道是 HITL 闸门）。
 *                  <p>为什么默认 10 分钟这么长：写操作重复的典型成因是
 *                  "模型重提 + 人又批了一次"，而这两件事之间的间隔是<b>人的反应时间</b>，
 *                  不是毫秒级。窗口设成几秒等于没设
 * @param maxDetailChars 详情入库前的截断上限。超限时<b>显式说明被截断</b>，
 *                  而不是静默截掉（与本项目其他所有截断同一条规则）
 */
@ConfigurationProperties(prefix = "ai.review")
public record ReviewProperties(String ticketFile, Duration deduplicationWindow, int maxDetailChars) {

	public ReviewProperties {
		if (ticketFile == null || ticketFile.isBlank()) {
			throw new IllegalArgumentException("ai.review.ticket-file 不能为空");
		}
		if (deduplicationWindow == null || deduplicationWindow.isNegative()) {
			deduplicationWindow = Duration.ZERO;
		}
		if (maxDetailChars <= 0) {
			maxDetailChars = 1000;
		}
	}

	public static ReviewProperties defaults() {
		return new ReviewProperties("./data/review-tickets.jsonl", Duration.ofMinutes(10), 1000);
	}

}
