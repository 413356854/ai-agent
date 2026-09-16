package ai.agent.prompt;

import ai.agent.token.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 用 classpath 里的出厂文件把版本库<b>初始化到第 1 版</b>（阶段四第 3 件）。
 *
 * <h2>为什么必须有这一步，而不是"库空就直接读文件"</h2>
 * 因为要回答「<b>回滚到哪里</b>」。没有出厂版本在库里的话，
 * 一个人改了三版之后想退回最初那一版，得去翻 jar 包里的文件 ——
 * 而"出厂那一版长什么样"这件事，恰恰是<B>最需要被固定下来的历史</b>。
 * 有它在库里，回滚目标永远存在，且与发布产物解耦。
 *
 * <h2>为什么只写一次（幂等），而不是每次启动都同步</h2>
 * 如果每次启动都拿 classpath 覆盖第 1 版，就会发生：<b>有人回滚到第 1 版、
 * 然后重启，第 1 版的内容被 jar 里那份悄悄替换掉</b> ——
 * 而"回滚"这个动作的结果变了，且没有任何痕迹。
 * 所以这张表只追加：出厂版本只在"库里一条都没有"时写入。
 * （代价是：以后升级 jar 里那份出厂文件不会自动进库。这是刻意的 ——
 * 要更新成品提示词就新增一版，那正是这个功能存在的意义。）
 *
 * <h2>为什么挂在 ApplicationReadyEvent 而不是构造器里</h2>
 * 构造器里做，会把"读库 + 写库"塞进 bean 创建阶段，任何一次失败都会变成
 * <b>启动失败</b>，而失败原因看起来像"某个 bean 建不出来"。
 * 挂在 Ready 事件上之后：应用先可用（读不到版本时走 classpath 兜底），
 * 初始化失败只影响"版本管理"这一个功能，而且日志里是一句能读懂的话。
 */
@Component
public class PromptDefaultsSeeder {

	private static final Logger log = LoggerFactory.getLogger(PromptDefaultsSeeder.class);

	private final PromptStore store;

	private final PromptService service;

	private final TokenEstimator estimator;

	public PromptDefaultsSeeder(PromptStore store, PromptService service, TokenEstimator estimator) {
		this.store = store;
		this.service = service;
		this.estimator = estimator;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void seed() {
		for (PromptKind kind : PromptKind.values()) {
			try {
				seedOne(kind);
			}
			catch (RuntimeException e) {
				// 一个种类失败不该拖垮另一个：两份提示词互相独立，
				// 而"其中一份没有版本记录"是可以在页面上看见的状态
				log.warn("提示词出厂版本初始化失败 kind={}：{}", kind.defaultValue(), e.getMessage());
			}
		}
	}

	private void seedOne(PromptKind kind) {
		if (store.active(kind).isPresent()) {
			log.debug("提示词已有版本记录，跳过初始化 kind={}", kind.defaultValue());
			return;
		}
		if (!store.versionsOf(kind).isEmpty()) {
			// 有版本但没有生效指针（指针被手工删过，或者上一次切换失败留下的状态）。
			// 这时候**不要**新建一版 —— 那会造出一个与第 1 版内容相同的第 2 版，
			// 而历史里会多出一条谁都解释不了的记录。把最新那一版激活即可。
			PromptVersion latest = store.versionsOf(kind).get(0);
			store.activate(kind, latest.version(), null);
			log.info("提示词有版本但无生效指针，已把第 {} 版置为生效 kind={}", latest.version(), kind.defaultValue());
			return;
		}
		String content = service.currentContent(kind);
		PromptVersion seeded = store.save(kind, content,
				"出厂版本（来自 " + kind.classpathLocation() + "）—— 这一版由启动时的自动初始化写入，是回滚的最终去处",
				"system", null, estimator.estimateMessage(content));
		store.activate(kind, seeded.version(), null);
		log.info("提示词出厂版本已入库并生效 kind={} version={} chars={} tokens={}", kind.defaultValue(),
				seeded.version(), seeded.charCount(), seeded.tokenEstimate());
	}

}
