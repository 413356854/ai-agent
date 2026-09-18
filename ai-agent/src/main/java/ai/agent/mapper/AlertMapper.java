package ai.agent.mapper;

import ai.agent.entity.alert.AlertEvent;
import ai.agent.service.alert.AlertRule;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * {@code alert_event} 表的 SQL（原先手写在 {@code JdbcAlertStore} 里）。
 *
 * <h2>写侧只有一条语句，而且它承担了三件事</h2>
 * {@link #recordFiring} 是一条 upsert，它的 {@code ON DUPLICATE KEY UPDATE} 里同时表达了：
 * ① 持续成立时"计数 +1、最后发现时间往后走"；② 重新触发时把状态拉回 OPEN 并清掉 resolved_at；
 * ③ <b>已确认的保持已确认</b>（{@code CASE WHEN state = 'ACKNOWLEDGED'}）。
 * 第 ③ 条最容易在"清理 SQL"时被丢掉，而丢掉的表现是「这个按钮时灵时不灵」——
 * 系统每评估一遍就把人的确认抹掉一次。所以它在 XML 里带着注释，不要删。
 */
@Mapper
public interface AlertMapper {

	/** upsert 一条正在响的告警（并发保护靠主键仲裁，不是"先查再决定"）。 */
	int recordFiring(@Param("fingerprint") String fingerprint, @Param("ruleCode") String ruleCode,
			@Param("subject") String subject, @Param("severity") AlertRule.Severity severity,
			@Param("phase") AlertRule.AlertPhase phase, @Param("at") Instant at,
			@Param("observed") String observed, @Param("threshold") String threshold, @Param("why") String why,
			@Param("action") String action, @Param("windowLabel") String windowLabel);

	/** 标记恢复。返回受影响行数（0 = 这条不存在了，调用方要区分"没找到"与"已经恢复了"）。 */
	int markResolved(@Param("fingerprint") String fingerprint, @Param("at") Instant at);

	/** 人工确认。带前置条件 {@code state <> 'RESOLVED'}：已恢复的不该被"确认"成已处理。 */
	int acknowledge(@Param("fingerprint") String fingerprint, @Param("at") Instant at,
			@Param("by") String by);

	List<MapperRows.AlertEventRow> firing();

	/** 最近的 N 条。{@code LIMIT} 用参数绑定（调用方已经把它夹在 1..500）。 */
	List<MapperRows.AlertEventRow> recent(@Param("limit") int limit);

	MapperRows.AlertEventRow findByFingerprint(@Param("fingerprint") String fingerprint);

	long openCount();

}

