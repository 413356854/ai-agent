package ai.agent.service.alert;

import ai.agent.entity.alert.AlertEvent;
import ai.agent.mapper.AlertMapper;
import ai.agent.mapper.MapperRows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@link AlertStore} 的存储实现 —— <b>SQL 在 {@link AlertMapper} 里，本类只管那几条"必须说的话"</b>。
 *
 * <h2>三个要点，每个都是"不这么写就会有一个安静的错"（SQL 侧见 AlertMapper.xml）</h2>
 * <ol>
 *   <li><b>写用一条 upsert，而不是"先查再写"</b>：
 *       {@link #recordFiring} 会被定时器调用，而"先 SELECT 再决定 INSERT 还是 UPDATE"
 *       在两次评估重叠时（上一次还没跑完、下一次已经到点）会写出两行。
 *       这里让数据库按主键仲裁 —— 与 Prompt 版本激活那条"带前置条件的 UPDATE"是同一个做法；</li>
 *   <li><b>重新触发要把状态拉回 OPEN，并清掉 resolved_at</b>（在 upsert 的分支里）。
 *       不清的话，"修好过又坏了"会停留在 RESOLVED 里 —— 而它恰恰是最该被看见的一种；
 *       同时 {@code first_seen_at} <b>不动</b>：它记的是"这个问题最早是什么时候出现的"；</li>
 *   <li><b>确认时必须带前置条件</b>（{@code WHERE state <> 'RESOLVED'}）。
 *       已恢复的告警不该被"确认"成已处理 —— 那会把一条已经结束的历史
 *       标成"人处理过"，而复盘时这两句话的含义完全不同。</li>
 * </ol>
 *
 * <h2>建表与连接池：本类不再管了</h2>
 * 表在 {@code db/alert-schema.sql}（与灰度配置同一份脚本）里，由 {@code spring.sql.init} 统一建。
 * 原先那个"构造器里塞一个用不到的 {@code JdbcAgentRunStore} 来保证建表先发生"的参数已删除 ——
 * 建表现在是启动期一个明确阶段的事，不再靠构造参数的顺序表达。
 */
@Component
public class JdbcAlertStore implements AlertStore {

	private static final Logger log = LoggerFactory.getLogger(JdbcAlertStore.class);

	private final AlertMapper mapper;

	public JdbcAlertStore(AlertMapper mapper) {
		this.mapper = mapper;
		log.info("告警库就绪（表 alert_event / prompt_canary 由 spring.sql.init 建好）");
	}

	@Override
	public AlertEvent recordFiring(AlertRule rule, String subject, AlertRule.Verdict verdict, String windowLabel,
			Instant at) {
		String fingerprint = AlertEvent.fingerprintOf(rule, subject);
		// subject 的空值在**这一处**归一成空串：库里那一列 NOT NULL，
		// 而"没有主体"与"主体是空串"在这条链路里是同一件事（与 schema 注释一致）
		String safeSubject = subject == null ? "" : subject;
		try {
			mapper.recordFiring(fingerprint, rule.code(), safeSubject, rule.severity(), rule.phase(), at,
					verdict.observed(), verdict.threshold(), verdict.why(), rule.action(), windowLabel);
		}
		catch (DataAccessException e) {
			throw new AlertStoreException("写告警失败 fingerprint=" + fingerprint, e);
		}
		return find(fingerprint).orElseThrow(
				() -> new AlertStoreException("刚写入的告警读不回来 fingerprint=" + fingerprint));
	}

	@Override
	public Optional<AlertEvent> recordResolved(String fingerprint, Instant now) {
		Optional<AlertEvent> existing = find(fingerprint);
		// 找不到 / 已经恢复 → 什么都不做。写一行"已恢复"占位会让列表里出现
		// 一堆从来没见过的问题，而列表的可信度一旦下降就回不来了
		if (existing.isEmpty() || existing.get().state() == AlertEvent.State.RESOLVED) {
			return Optional.empty();
		}
		Instant at = now == null ? Instant.now() : now;
		int affected = read(() -> mapper.markResolved(fingerprint, at), "标记告警恢复失败 fingerprint=" + fingerprint);
		if (affected == 0) {
			return Optional.empty();
		}
		log.info("告警已恢复：{}", fingerprint);
		return find(fingerprint);
	}

	@Override
	public Optional<AlertEvent> acknowledge(String fingerprint, String by, Instant now) {
		Instant at = now == null ? Instant.now() : now;
		// 空白按"匿名"处理但**不写成 NULL**："有人确认了但没留名"与"系统标的状态"是两件事
		String who = by == null || by.isBlank() ? "匿名" : by.strip();
		int affected = read(() -> mapper.acknowledge(fingerprint, at, who),
				"确认告警失败 fingerprint=" + fingerprint);
		if (affected == 0) {
			// 两种情况都返回空，由调用方（端点）去查一次原因：
			// "这条不存在"（404）与"它已经恢复了"（409）该说两句不同的话
			return Optional.empty();
		}
		log.info("告警已确认：{} by={}", fingerprint, who);
		return find(fingerprint);
	}

	// ------------------------------------------------------------------
	//  读
	// ------------------------------------------------------------------

	@Override
	public List<AlertEvent> firing() {
		return read(() -> mapper.firing().stream().map(MapperRows.AlertEventRow::toDomain).toList(), "读告警失败");
	}

	@Override
	public List<AlertEvent> recent(int limit) {
		// 上下界在 Java 侧定：它是**调用契约**的一部分，不该指望数据库对 0 / 负数报错
		// （H2 与 MySQL 对 LIMIT 0 的处理并不一致）
		int effective = Math.max(1, Math.min(limit, 500));
		return read(() -> mapper.recent(effective).stream().map(MapperRows.AlertEventRow::toDomain).toList(), "读告警失败");
	}

	@Override
	public Optional<AlertEvent> find(String fingerprint) {
		// 先判 null 再转 domain：mapper 查不到时返回 null，直接 .toDomain() 会 NPE
		MapperRows.AlertEventRow row = read(() -> mapper.findByFingerprint(fingerprint),
				"读告警失败 fingerprint=" + fingerprint);
		return row == null ? Optional.empty() : Optional.of(row.toDomain());
	}

	@Override
	public long openCount() {
		return read(mapper::openCount, "统计未处理告警数失败");
	}

	/** 异常包装（六个方法共用）：读侧的失败消息不必各自不同，写侧的要带 id（见上面）。 */
	private <T> T read(Supplier<T> call, String whatFailed) {
		try {
			return call.get();
		}
		catch (DataAccessException e) {
			throw new AlertStoreException(whatFailed, e);
		}
	}

	/** 告警存储自己的异常类型（与 {@code PromptStoreException} 同一种做法：便于端点分别处理）。 */
	public static class AlertStoreException extends RuntimeException {

		public AlertStoreException(String message, Throwable cause) {
			super(message, cause);
		}

		public AlertStoreException(String message) {
			super(message);
		}

	}

}

