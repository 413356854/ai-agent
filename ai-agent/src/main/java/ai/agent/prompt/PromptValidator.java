package ai.agent.prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * 提示词正文的写前校验（阶段四第 3 件里<b>最值钱的一个类</b>）。
 *
 * <h2>为什么提示词必须在"写的时候"校验，而不能等到跑的时候</h2>
 * 这是这一整件功能存在的主要理由。代码改坏了会编译失败、测试变红、启动报错 ——
 * 三种都会立刻拦住你。而<b>提示词改坏了什么都不发生</b>：
 * <ul>
 *   <li>接口照样 200，模型照样回答，字数甚至差不多；</li>
 *   <li>唯一的症状是<b>答案质量下降</b>，而"质量"没有断言能钉住（现阶段没有）；</li>
 *   <li>于是它会被归因成"模型这两天不太行"，而真相是我们自己刚把提示词截断了。</li>
 * </ul>
 * 所以校验的位置只有一个正确选择：<b>在内容进入版本库之前</b>。
 * 进了库之后就晚了 —— 那时它已经能被激活、已经被用过了。
 *
 * <h2>判据为什么是"结构性"的，而不是"内容好不好"</h2>
 * 一个类不可能判断"这段提示词写得好不好"。但它能判断一批<b>真实的、不报错的</b>事故形态，
 * 而这些恰好是版本管理要防的事故：
 * <table border="1">
 *   <caption>校验项与它防的事故</caption>
 *   <tr><th>判据</th><th>它防的事故</th></tr>
 *   <tr><td>非空白 / 长度上限</td><td>误把空内容激活 —— 模型失去角色设定，但回答看起来"只是变笨了"</td></tr>
 *   <tr><td><b>代码围栏配平</b></td><td>粘贴时被截断（最容易发生、最难发现的一种残缺）</td></tr>
 *   <tr><td>关键段落还在</td><td>只把"## 回答要求"那一段粘贴过来，把其余全删了 —— 内容非空、围栏配平，但工作方式没了</td></tr>
 *   <tr><td>改动说明必填</td><td>没有说明的历史等于没有历史：回滚时不知道该回哪一版</td></tr>
 * </table>
 *
 * <h2>为什么有的判据只警告、不拒绝</h2>
 * 拒绝的门槛必须落在"<b>这一定是坏的</b>"上，而不是"这看起来可疑"。因为误拒的代价是
 * 逼人绕过校验（比如删掉一段就是为了压短 token），而<b>一旦养成"绕过它"的习惯，
 * 这个类就彻底没用了</b>。所以：
 * <ul>
 *   <li>文本明显变短 / 变长、正文过短 → <b>警告</b>，页面必须显示，但放行；</li>
 *   <li>确实需要更短的正文（例如压预算）→ 调用方显式传 {@code force}，
 *       于是"这次是有意为之"留在记录里，而不是靠把校验关掉。</li>
 * </ul>
 * 这个类<b>无状态、不碰 IO、不依赖 Spring</b>：它是纯函数，可以逐条断言
 * （与成本看板把全部算术收进 {@code CostPricing} 是同一种做法）。
 */
public final class PromptValidator {

	/** 正文长度上限（字符）。超过它多半是粘贴事故，而不是"写得更详细"。 */
	public static final int MAX_CHARS = 20000;

	/** 正文长度下限（字符）。低于它几乎不可能是完整的系统提示词 —— 但可以 force 放行。 */
	private static final int MIN_CHARS = 120;

	/**
	 * 一次校验的输入。
	 *
	 * @param kind       哪一份提示词（不同种类的"关键段落"不同）
	 * @param content    待写入的正文
	 * @param note       改动说明（必填）
	 * @param author     操作人（选填，界面默认给 runtime）
	 * @param force      已知自己在做非常规改动时显式打开；只对"警告级"判据生效，
	 *                   <b>不能</b>让"没写说明"或"内容为空"通过
	 */
	public record Input(PromptKind kind, String content, String note, String author, boolean force) {
	}

	/**
	 * 一条判据的结论。
	 *
	 * @param rule   判据的名字（页面上直接显示，所以写成短句而不是代码标识符）
	 * @param level  级别
	 * @param detail 具体数字或原因 —— <b>必须带数字</b>：
	 *               "内容过短"没法据以行动，"只有 43 字符（下限 120）"可以
	 */
	public record Check(String rule, Level level, String detail) {
	}

	/** 级别。拒绝与警告必须是两个级别：它们的处理方式完全不同。 */
	public enum Level {
		/** 不通过 —— 这一版写不进库。 */
		REJECT,
		/** 可疑但放行 —— 页面必须显示出来，让人自己判断。 */
		WARN,
		/** 通过。 */
		OK
	}

	/**
	 * 校验结果。
	 *
	 * @param checks   每一条判据的结论（<b>全部判据都要有结论</b>，包括通过的）
	 * @param rejected 是否被拒绝（等于 checks 里存在 REJECT）
	 */
	public record Result(List<Check> checks, boolean rejected) {

		/** 被拒的那几条（端点把它们拼成一句能照着改的话）。 */
		public List<Check> rejections() {
			return checks.stream().filter(c -> c.level() == Level.REJECT).toList();
		}

		/** 警告（页面单独列出来）。 */
		public List<Check> warnings() {
			return checks.stream().filter(c -> c.level() == Level.WARN).toList();
		}
	}

	private PromptValidator() {
	}

	/**
	 * 校验一版准备写入的正文。
	 *
	 * @param input      见 {@link Input}
	 * @param previous   当前生效的那一版（用于比较长短变化）；没有时传 {@code null}
	 */
	public static Result validate(Input input, PromptVersion previous) {
		List<Check> checks = new ArrayList<>();
		String content = input.content() == null ? "" : input.content();

		// ---- ① 内容本身 ----
		if (content.isBlank()) {
			checks.add(new Check("正文不能为空", Level.REJECT,
					"空提示词写进库之后模型会失去角色设定，而回答看起来只是『变笨了』—— 这种坏法没有报错"));
		}
		else if (content.length() > MAX_CHARS) {
			checks.add(new Check("正文长度上限", Level.REJECT,
					"当前 %d 字符，上限 %d —— 这么长通常是粘贴事故（粘了两遍、或粘进了无关内容）"
							.formatted(content.length(), MAX_CHARS)));
		}
		else {
			checks.add(new Check("正文长度上限", Level.OK,
					"%d 字符（上限 %d）".formatted(content.length(), MAX_CHARS)));
		}

		// ---- ② 代码围栏配平（最常见也最难发现的残缺形态）----
		int fences = countFences(content);
		if (!content.isBlank() && fences % 2 != 0) {
			checks.add(new Check("代码围栏配平", Level.REJECT,
					"检测到 %d 个 ``` 围栏（奇数）—— 多半是粘贴时被截断了；"
							+ "残缺的提示词不会报错，只会让模型看到半段说明".formatted(fences)));
		}
		else {
			checks.add(new Check("代码围栏配平", Level.OK, "%d 个围栏（配平）".formatted(fences)));
		}

		// ---- ③ 关键段落还在 ----
		if (!content.isBlank()) {
			checks.add(checkRequiredSections(input.kind(), content));
		}

		// ---- ④ 改动说明必填 ----
		if (input.note() == null || input.note().isBlank()) {
			checks.add(new Check("改动说明必填", Level.REJECT,
					"没有说明的历史等于没有历史：回滚时不知道该回哪一版，也说不清当时为什么这么改"));
		}
		else if (input.note().strip().length() < 4) {
			int noteChars = input.note().strip().length();
			checks.add(new Check("改动说明必填", Level.REJECT,
					"说明只有 %d 个字，读的人看不出这次改了什么".formatted(noteChars)));
		}
		else {
			checks.add(new Check("改动说明必填", Level.OK, "已给出（%d 字）".formatted(input.note().strip().length())));
		}

		// ---- ⑤ 篇幅异常（两条判据，都是警告级：它们描述的可以是刻意的改动）----
		//
		// 【这两条必须是两个独立的 check，不能写成 if/else】
		// 它们防的是不同的东西：
		//   ① **短于绝对下限** —— "这段正文根本不像一份完整提示词"；
		//   ② **相对上一版骤变** —— 绝对长度完全正常（3000 → 1500 字），但它掉了一半。
		// 第一版把它们写成了 if/else，于是"骤减到很短"这个最危险的形态
		// **只报出第 ① 条**（因为它先命中），而"相对变化"这条完全没被说出来 ——
		// 读者看到的是"只有 130 字符"，却看不到"上一版有 900 字符"。
		// 而"它掉了多少"恰恰是判断"这是删减还是粘贴残缺"的唯一依据。
		// （这一条是被 PromptValidatorTest.warnsButAllowsOnBigShrink 抓出来的）
		if (!content.isBlank()) {
			if (content.length() < MIN_CHARS && !input.force()) {
				checks.add(new Check("篇幅没有低于下限", Level.WARN,
						"只有 %d 字符，远小于一份完整系统提示词的常见篇幅（下限参考 %d）。确实要这么短就带上『确认放行』再存一次"
								.formatted(content.length(), MIN_CHARS)));
			}
			else {
				checks.add(new Check("篇幅没有低于下限", Level.OK,
						"%d 字符（下限参考 %d）".formatted(content.length(), MIN_CHARS)));
			}

			if (previous != null && previous.charCount() > 0) {
				double ratio = (double) content.length() / previous.charCount();
				if (ratio < 0.5) {
					checks.add(new Check("篇幅相对上一版没有骤变", Level.WARN,
							"只有上一版的 %.0f%%（%d → %d 字符）—— 确认这是有意的删减，而不是粘贴残缺"
									.formatted(ratio * 100, previous.charCount(), content.length())));
				}
				else if (ratio > 2.0) {
					checks.add(new Check("篇幅相对上一版没有骤变", Level.WARN,
							"是上一版的 %.1f 倍（%d → %d 字符）—— 这会让 prompt token 明显上涨，"
									+ "而它的副作用是『历史比以前更早被裁掉』"
											.formatted(ratio, previous.charCount(), content.length())));
				}
				else {
					checks.add(new Check("篇幅相对上一版没有骤变", Level.OK,
							"上一版的 %.0f%%（%d → %d 字符）"
									.formatted(ratio * 100, previous.charCount(), content.length())));
				}
			}
			else {
				checks.add(new Check("篇幅相对上一版没有骤变", Level.OK, "首次写入，无可比较的上一版"));
			}
		}

		boolean rejected = checks.stream().anyMatch(c -> c.level() == Level.REJECT);
		return new Result(List.copyOf(checks), rejected);
	}

	/**
	 * 关键段落检查。
	 *
	 * <p>只钉<b>结构性锚点</b>（段落标题、必须提到的动作），不钉具体措辞 ——
	 * 钉措辞会让"改一句话"变成"过不了校验"，而那样人就会去删校验。
	 *
	 * <p>这一条拦的是真实发生过的一类事故：只把 {@code ## 回答要求} 那一段复制过来改，
	 * 其余整段丢了。那种正文非空、围栏配平、篇幅也不算离谱（所以前面几条全绿），
	 * 但 Agent 的工作方式没了 —— 它不再知道要先查再答。
	 */
	private static Check checkRequiredSections(PromptKind kind, String content) {
		List<String> missing = new ArrayList<>();
		if (kind == PromptKind.AGENT_LOOP) {
			// 这三样分别对应"要查"、"怎么查"、"别编" —— 是 Agent 行为的三根柱子
			if (!content.contains("工作方式")) {
				missing.add("「工作方式」这一段（它定义了 think → act → observe）");
			}
			if (!content.contains("工具")) {
				missing.add("对「工具」的说明（没有它模型不知道可以调用工具）");
			}
			if (!content.contains("编造") && !content.contains("查不到")) {
				missing.add("「不编造」那一条（少了它，查不到时模型会用看起来合理的数字填空）");
			}
		}
		else if (!content.contains("助手") && !content.contains("你是")) {
			// 对话提示词的核心是角色设定；没有它，模型的人格由上游默认值决定
			missing.add("角色设定（「你是…」这类开头）");
		}
		if (missing.isEmpty()) {
			return new Check("关键段落还在", Level.OK, "结构锚点齐全");
		}
		return new Check("关键段落还在", Level.REJECT,
				"缺少 " + String.join("、", missing)
						+ " —— 这类残缺的正文是有效的、也能通过前面几条，所以只能在这里拦");
	}

	/** 数 ``` 的出现次数（不解析语言标注，只看围栏标记本身）。 */
	private static int countFences(String content) {
		int count = 0;
		int idx = content.indexOf("```");
		while (idx >= 0) {
			count++;
			idx = content.indexOf("```", idx + 3);
		}
		return count;
	}

}
