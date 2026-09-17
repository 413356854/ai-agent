package ai.agent.service.prompt;

import ai.agent.entity.prompt.PromptChange;
import ai.agent.entity.prompt.PromptDiff;
import ai.agent.entity.prompt.PromptVersion;
import ai.agent.service.token.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link PromptService} 的默认实现（阶段四第 3 件）。
 *
 * <h2>本类里唯一需要小心的东西：不缓存正文</h2>
 * 每一个读方法都去商店里问一次当前生效的版本。这不是"没做优化"，而是
 * <b>热更新能不能成立就取决于这一点</b>：
 * 任何一处"启动时读一次放进字段"都会让热更新在那个位置静默失效 ——
 * 而失效的表现是"我明明换了提示词，效果没变"，看起来像模型的问题。
 *
 * <h2>兜底链为什么是三级，以及每一级存在的理由</h2>
 * <ol>
 *   <li><b>库里生效的那一版</b> —— 正常路径；</li>
 *   <li><b>classpath 出厂文件</b> —— 库空 / 读库失败时用（保证 Agent 永远有提示词可用。
 *       一个没有系统提示词的 Agent 会用模型的默认人格乱答，而那看起来像"模型不听话"）；</li>
 *   <li><b>抛异常</b> —— 连 classpath 都读不到（打包漏了资源）。
 *       这一档<b>刻意不返回空串</b>：空串会让第 2 条那个问题以最隐蔽的方式发生。</li>
 * </ol>
 * 第 2 级存在还有第二个作用：它让"库里一条版本都没有"这个中间状态是<b>可用</b>的 ——
 * 于是初始化可以在启动后台慢慢做，而不必让应用依赖它先跑完。
 */
@Service
public class DefaultPromptService implements PromptService {

	private static final Logger log = LoggerFactory.getLogger(DefaultPromptService.class);

	private final PromptStore store;

	private final TokenEstimator estimator;

	private final ResourceLoader resourceLoader;

	public DefaultPromptService(PromptStore store, TokenEstimator estimator, ResourceLoader resourceLoader) {
		this.store = store;
		this.estimator = estimator;
		this.resourceLoader = resourceLoader;
	}

	// ------------------------------------------------------------------
	//  读
	// ------------------------------------------------------------------

	@Override
	public String currentContent(PromptKind kind) {
		return activeVersion(kind).map(PromptVersion::content).orElseGet(() -> classpathOf(kind));
	}

	@Override
	public String currentFingerprint(PromptKind kind) {
		Optional<PromptVersion> active = activeVersion(kind);
		if (active.isPresent()) {
			// 拼法来自 PromptVersion.fingerprintOf —— **不许在这里手拼**：
			// 灰度发布也要算"另一版的指纹"（它算不出当前生效那一版），
			// 于是就会有两份拼法。两份迟早分叉，而分叉的表现是
			// "灰度那一组的指纹在库里对不上任何版本"（只在读 trace 时才发现）
			return PromptVersion.fingerprintOf(active.get());
		}
		return "classpath:" + PromptVersion.hashOf(classpathOf(kind));
	}

	@Override
	public Optional<PromptVersion> activeVersion(PromptKind kind) {
		return store.active(kind);
	}

	@Override
	public List<PromptVersion> versions(PromptKind kind) {
		return store.versionsOf(kind);
	}

	@Override
	public int estimateFallback(PromptKind kind) {
		return estimator.estimateMessage(classpathOf(kind));
	}

	private String classpathOf(PromptKind kind) {
		try {
			Resource resource = resourceLoader.getResource("classpath:" + kind.classpathLocation());
			try (var in = resource.getInputStream()) {
				return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
			}
		}
		catch (IOException e) {
			// 走到这里说明打包漏了资源 —— 必须打断，不能返回空串（理由见类注释）
			throw new UncheckedIOException("读不到 " + kind.classpathLocation() + "（打包是否漏了 prompts/？）", e);
		}
	}

	// ------------------------------------------------------------------
	//  写
	// ------------------------------------------------------------------

	@Override
	public PromptChange save(PromptKind kind, PromptValidator.Input input) {
		PromptVersion previous = store.active(kind).orElse(null);
		PromptValidator.Result result = PromptValidator.validate(input, previous);
		if (result.rejected()) {
			// 报错信息里必须带上"哪一条没过 + 具体数字"，否则使用者只能猜是哪里不合规
			String reason = result.rejections().stream()
					.map(c -> c.rule() + "：" + c.detail())
					.reduce((a, b) -> a + "；" + b)
					.orElse("内容不合规");
			throw new PromptRejectedException("提示词没有写入：" + reason, result);
		}
		int tokens = estimator.estimateMessage(input.content());
		PromptVersion saved = store.save(kind, input.content(), input.note(), authorOf(input), null, tokens);
		log.info("新增提示词版本 kind={} version={} chars={} tokens={}（尚未生效）", kind.defaultValue(),
				saved.version(), saved.charCount(), saved.tokenEstimate());
		// 校验结论随写入结果一起返回：端点**不要**自己再算一遍 ——
		// 两遍之间"当前生效版本"可能已经变了，于是接口的警告与实际判据不一致（且不报错）
		return new PromptChange(saved, result);
	}

	@Override
	public PromptVersion activate(PromptKind kind, int version, Integer expectedVersion, String author) {
		PromptVersion target = store.find(kind, version).orElseThrow(
				() -> new PromptStoreException("没有第 " + version + " 版 kind=" + kind.defaultValue() + "，无法生效"));
		Optional<PromptVersion> switched = store.activate(kind, version, expectedVersion);
		if (switched.isEmpty()) {
			Integer actual = store.active(kind).map(PromptVersion::version).orElse(null);
			throw new PromptConflictException(
					"当前生效的已经是第 %s 版（你以为的是第 %s 版），这次切换没有执行 —— 刷新后重新决定"
							.formatted(actual == null ? "（无）" : actual, expectedVersion),
					expectedVersion, actual);
		}
		log.info("提示词切换生效 kind={} version={} by={} tokens={}", kind.defaultValue(), version, author,
				target.tokenEstimate());
		return switched.get();
	}

	@Override
	public PromptVersion rollback(PromptKind kind, int targetVersion, String author, String note) {
		PromptVersion source = store.find(kind, targetVersion).orElseThrow(
				() -> new PromptStoreException("没有第 " + targetVersion + " 版 kind=" + kind.defaultValue()));
		String rollbackNote = (note == null || note.isBlank())
				? "回滚到第 %d 版（由系统补写的说明：真正的理由请写在变更记录里）".formatted(targetVersion)
				: note;
		// 【回滚 = 新增一版】正文复制自旧版，forked_from 记着来源。
		// 不复制 note：那一版的说明讲的是**当时**为什么那么改，与这次回滚是两件事。
		//
		// 这里刻意**不**走 PromptValidator：被回滚到的正文是库里已经存在的一份，
		// 它在当初写入时已经过了一遍校验。再校验一次的话，会撞上"篇幅相对上一版骤减"
		// 这类**警告级**判据 —— 而让一个真实存在的历史版本"回不去"，是这个功能最严重的失效
		// （回滚的意义就是"回到一个已知能用的状态"）。
		PromptVersion copied = store.save(kind, source.content(), rollbackNote, authorOf(author), targetVersion,
				source.tokenEstimate());
		PromptVersion activated = activate(kind, copied.version(), store.active(kind)
				.map(PromptVersion::version).orElse(null), authorOf(author));
		log.info("提示词回滚 kind={} 第 {} 版的内容复制为第 {} 版并生效 by={}", kind.defaultValue(), targetVersion,
				activated.version(), author);
		return activated;
	}

	@Override
	public PromptDiff diff(PromptKind kind, int fromVersion, int toVersion) {
		PromptVersion from = store.find(kind, fromVersion).orElseThrow(
				() -> new PromptStoreException("没有第 " + fromVersion + " 版 kind=" + kind.defaultValue()));
		PromptVersion to = store.find(kind, toVersion).orElseThrow(
				() -> new PromptStoreException("没有第 " + toVersion + " 版 kind=" + kind.defaultValue()));
		return compare(from, to);
	}

	// ------------------------------------------------------------------
	//  逐行差异
	// ------------------------------------------------------------------

	/**
	 * 两份正文的逐行差异（最长公共子序列 + 就近折叠）。
	 *
	 * <p>为什么用 LCS 而不是"逐行按下标比"：改了中间一行会让<i>它之后所有的行</i>
	 * 在下标比较里都变成"已修改"。那种差异表看起来像"我几乎重写了整份提示词"，
	 * 而实际上只动了一行 —— 一个把改动放大几十倍的视图，比没有视图更坏
	 * （阶段二那次"差值表把 token 成本夸大了 100 倍"就是同一种错，
	 * 而它当时也是<b>不报错</b>的）。
	 */
	static PromptDiff compare(PromptVersion from, PromptVersion to) {
		// 【内容相同 → 直接返回空差异，不要往下走去算 LCS】
		// 走到下面的话，`identical` 会被正确置为 true，而 lines 里却是**全部行标着 SAME** ——
		// 于是页面拿到"没有差异"这个结论的同时，还拿到一整份看起来像差异表的东西。
		// 这种自相矛盾的返回体，读的人只会信那个更长的（也就是错的）。
		// 顺手说一个真实踩到的版本：第一版把 identical 写成了 raw.isEmpty()，
		// 而 raw 里装着全部行 —— 那个 bug 让"没有差异"这个提示**永远不显示**，
		// 于是"对比两个相同的版本"会渲染出一整页未变更内容。
		if (from.content() != null && from.content().equals(to.content())) {
			return new PromptDiff(from.kind(), from.version(), to.version(), List.of(), 0, 0,
					to.tokenEstimate() - from.tokenEstimate(), true);
		}

		List<String> a = lines(from.content());
		List<String> b = lines(to.content());
		int[][] lcs = lcsTable(a, b);

		List<PromptDiff.Line> raw = new ArrayList<>();
		int added = 0;
		int removed = 0;
		int i = 0;
		int j = 0;
		while (i < a.size() && j < b.size()) {
			if (a.get(i).equals(b.get(j))) {
				raw.add(PromptDiff.Line.same(j + 1, b.get(j)));
				i++;
				j++;
			}
			else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
				raw.add(new PromptDiff.Line(PromptDiff.Op.REMOVE, i + 1, null, a.get(i)));
				removed++;
				i++;
			}
			else {
				raw.add(new PromptDiff.Line(PromptDiff.Op.ADD, null, j + 1, b.get(j)));
				added++;
				j++;
			}
		}
		while (i < a.size()) {
			raw.add(new PromptDiff.Line(PromptDiff.Op.REMOVE, i + 1, null, a.get(i)));
			removed++;
			i++;
		}
		while (j < b.size()) {
			raw.add(new PromptDiff.Line(PromptDiff.Op.ADD, null, j + 1, b.get(j)));
			added++;
			j++;
		}

		// 【identical 必须由 added/removed 判断，不能由 raw.isEmpty()】
		// raw 里装着全部行（包括 SAME），所以它只有两份都是空正文时才为空 ——
		// 而"两份完全相同"恰恰是最常见的形态。第一版就是这么写的：
		// 于是"没有差异"这个提示永远不显示，页面会渲染一整份满是 SAME 行的"差异表"。
		boolean identical = added == 0 && removed == 0;
		return new PromptDiff(from.kind(), from.version(), to.version(), collapse(raw), added, removed,
				to.tokenEstimate() - from.tokenEstimate(), identical);
	}

	/**
	 * 把长的未变更段落折叠成一行说明。
	 *
	 * <p>保留改动附近的 {@value #CONTEXT_LINES} 行上下文：改动的意义常常在于"它在哪一段里"，
	 * 只给差异行本身会让人看不出上下文（"加了一句『不要编造』"与
	 * "把『不要编造』加在错误反馈那一段里"是两件事）。
	 */
	private static final int CONTEXT_LINES = 2;

	private static List<PromptDiff.Line> collapse(List<PromptDiff.Line> raw) {
		List<PromptDiff.Line> out = new ArrayList<>();
		int index = 0;
		int size = raw.size();
		while (index < size) {
			if (raw.get(index).op() != PromptDiff.Op.SAME) {
				out.add(raw.get(index));
				index++;
				continue;
			}
			int start = index;
			while (index < size && raw.get(index).op() == PromptDiff.Op.SAME) {
				index++;
			}
			int runLength = index - start;
			// 两种不折叠的情形：
			//   ① 这一段在开头或结尾 —— 那里没有"和改动相邻"的说法，折叠它只会让顶部看起来被截过；
			//   ② 这一段本来就短（≤ 两侧上下文之和 +1）—— 折叠省不下什么，
			//      反而把"省略了 1 行"这种没信息量的话塞进视图里
			if (start == 0 || index == size || runLength <= CONTEXT_LINES * 2 + 1) {
				out.addAll(raw.subList(start, index));
				continue;
			}
			out.addAll(raw.subList(start, start + CONTEXT_LINES));
			out.add(PromptDiff.Line.gap(runLength - CONTEXT_LINES * 2));
			out.addAll(raw.subList(index - CONTEXT_LINES, index));
		}
		return List.copyOf(out);
	}

	private static int[][] lcsTable(List<String> a, List<String> b) {
		int[][] table = new int[a.size() + 1][b.size() + 1];
		for (int i = a.size() - 1; i >= 0; i--) {
			for (int j = b.size() - 1; j >= 0; j--) {
				table[i][j] = a.get(i).equals(b.get(j)) ? table[i + 1][j + 1] + 1
						: Math.max(table[i + 1][j], table[i][j + 1]);
			}
		}
		return table;
	}

	/**
	 * 按行切开。
	 *
	 * <p>{@code -1} 保留末尾空行：{@code split("\n")} 会把它丢掉，
	 * 于是"只在末尾加了一个换行"这种改动在差异里<b>看不见</b>，
	 * 而页面显示的字符数却变了 —— 两个视图对不上，而没人知道该信哪个。
	 */
	private static List<String> lines(String content) {
		return List.of((content == null ? "" : content).split("\n", -1));
	}

	private static String authorOf(PromptValidator.Input input) {
		return authorOf(input.author());
	}

	private static String authorOf(String author) {
		// 空白存 NULL 而不是空串（与归因键那条同一个理由："没署名"与"署名成了空"是两件事）
		return author == null || author.isBlank() ? null : author.strip();
	}

}
