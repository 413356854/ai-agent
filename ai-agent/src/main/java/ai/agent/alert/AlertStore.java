package ai.agent.alert;

import java.util.List;
import java.util.Optional;

/**
 * 告警事件的存储契约（阶段四第 5 件）。
 *
 * <h2>★ 这个接口<b>故意没有 delete</b></h2>
 * 没有 {@code delete(fingerprint)}，也没有"清空告警列表"。这不是还没写，而是
 * 这个功能能成立的前提：一张能被人一键清空的告警表，在事故复盘时是<b>不可信</b>的 ——
 * 因为"它没响过"与"响过但被清了"看起来一样。
 *
 * <p>台账里已经立过同一条纪律两次（Prompt 版本管理"没有 update 与 delete"、
 * 成本看板"调价要新增一条、不要改旧的"），这里第三次：
 * <b>会改写历史的操作，事后无法解释。</b>
 *
 * <p>想让它从列表里消失只有一条正路：<b>让它不再成立</b>（修好它）——
 * 那时评估器会把它标成 {@link AlertEvent.State#RESOLVED}，
 * 而"它曾经响过"这件事被留在了 {@link #resolved} 里。
 *
 * <h2>为什么"确认"是一条单独的写方法，而不是 update 状态</h2>
 * 因为它是<b>人做的唯一一个动作</b>，而人的动作必须留痕（谁、什么时候）。
 * 一个通用的 {@code update(state)} 会让"系统标成已恢复"与
 * "张三说他看过了"写进同一列 —— 而这两件事的含义完全不同。
 */
public interface AlertStore {

	/**
	 * 记一次"这条告警现在成立"。
	 *
	 * <p>已存在就更新 {@code last_seen_at} / {@code occurrences} / 观测值，
	 * 并把状态从 {@code RESOLVED} 拉回 {@code OPEN}（**重新触发必须重新出现在未处理里**，
	 * 否则"修好过又坏了"会被静默地当成"还没修好"）。
	 *
	 * @return 落库后的那一行（{@code firstSeenAt} 是<b>原来那个</b>，不是现在）
	 */
	AlertEvent recordFiring(AlertRule rule, String subject, AlertRule.Verdict verdict, String windowLabel,
			java.time.Instant now);

	/**
	 * 记一次"这条告警现在不成立了"。
	 *
	 * <p>找不到对应行时返回空并<b>什么都不做</b> —— 不写一行"已恢复"占位：
	 * 那会让列表里出现一堆从来没见过的问题，而列表的可信度一旦下降就回不来了。
	 */
	Optional<AlertEvent> recordResolved(String fingerprint, java.time.Instant now);

	/** 还在响的告警（未处理 + 已确认），按严重度、再按第一次发现的时间排。 */
	List<AlertEvent> firing();

	/** 全部告警（含已恢复），按最后发现时间倒序。 */
	List<AlertEvent> recent(int limit);

	/** 按指纹查一条。 */
	Optional<AlertEvent> find(String fingerprint);

	/**
	 * 人确认一条（"我看过了"）。
	 *
	 * @param by 谁确认的；空白按"匿名"处理，但**不写成 NULL** ——
	 *           "有人确认了但没留名"与"系统标的状态"是两件事，前者要能看出来
	 * @return 确认后的那一行；指纹不存在时为空（端点据此回 404）
	 */
	Optional<AlertEvent> acknowledge(String fingerprint, String by, java.time.Instant now);

	/** 未处理的条数（界面顶部那个红点用它 —— 它必须是一次 COUNT，而不是把列表读回来数一遍）。 */
	long openCount();

}
