package ai.agent.service.prompt;

/**
 * 受版本管理的提示词种类（阶段四第 3 件：Prompt 版本管理 + 配置热更新）。
 *
 * <h2>为什么用枚举，而不是让调用方传一个字符串路径</h2>
 * 版本管理要回答的第一个问题是「<b>改的是哪一份提示词</b>」。
 * 如果"是哪一份"由调用方随便传字符串决定，那么：
 * <ul>
 *   <li>回滚端点会变成"回滚某个路径的第 3 版"—— 而路径是部署细节，不是业务概念；</li>
 *   <li>界面上的那一栏会随调用方拼错字符串而出现一行谁都对不上的记录
 *       （而它不报错，只是永远不被激活）。</li>
 * </ul>
 * 枚举把"能被版本化的那些提示词"变成一份<b>闭集</b>：新增一种就得改这里，
 * 于是"哪些提示词受管理"这件事在一个地方看得全。
 *
 * <h2>为什么每一项都带一个 classpath 位置</h2>
 * 那份 classpath 文件有两个<b>互不相同</b>的作用，不要混为一谈：
 * <ol>
 *   <li><b>出厂默认值</b>：库是空的时候拿它建第 1 版（{@code PromptDefaultsSeeder}）。
 *       所以"出厂那一版永远在库里"，回滚永远有一个能回的地方；</li>
 *   <li><b>兜底</b>：库被人清空 / 读库失败时，仍然要给模型一份可用的提示词 ——
 *       没有提示词的 Agent 会用模型的默认人格乱答，而那看起来像"模型不听话"，
 *       实际是部署问题（与 {@code AgentLoop.loadSystemPrompt} 里那条注释同一个理由）。</li>
 * </ol>
 *
 * <p><b>刻意不把文件内容读进这个枚举</b>：枚举是常量，读文件有 IO 也可能失败，
 * 而且读一次就会过期 —— 那正是这一件要消灭的东西。
 */
public enum PromptKind {

	/**
	 * Agent 循环的系统提示词（阶段三）。
	 *
	 * <p>{@code AgentLoop} 每次运行都会读它，所以它天然支持热更新 ——
	 * 这一份是"热更新真的生效"最容易验证的地方。
	 */
	AGENT_LOOP("system-agent-tools", "prompts/system-agent-tools.st",
			"Agent 循环（ReAct）的系统提示词：工作方式、错误反馈的读法、回答要求"),

	/**
	 * 对话（含结构化输出）的系统提示词（阶段一）。
	 *
	 * <p>⚠️ 这一份的读法与上面那一份<b>不一样</b>，而且这个差别是实测出来的：
	 * {@code ChatClientBuilder.defaultSystem(Resource)} 在<b>构建 ChatClient 的那一刻</b>
	 * 就调用了 {@code Resource.getContentAsString()}（用 {@code javap} 核过字节码），
	 * 也就是说 —— <b>换一个"每次读都返回新内容"的 Resource 并不能让它热更新</b>，
	 * 内容在启动时就被取走了。所以这一份的注入点必须挪到<b>每次请求的调用点</b>
	 * （{@code ChatServiceImpl} / {@code StructuredChatServiceImpl} 的 {@code .system(...)}）。
	 */
	CHAT_SYSTEM("system-agent", "prompts/system-agent.st",
			"对话与结构化输出的系统提示词：角色设定与回答风格"),

	/**
	 * agent 任务判定（需求歧义判定 / 任务点拆分）的系统提示词。
	 *
	 * <p>判定与执行同源（走同一个 {@code AgentLoop}），所以这一份的读法与
	 * {@link #AGENT_LOOP} 完全一样：<b>每次判定开始时读一次</b>，正文与工具目录、技能目录
	 * 一起拼成那次运行的系统消息。热更新的粒度因此是「下一次判定」。
	 *
	 * <p>它不能复用 {@link #AGENT_LOOP}：那一份写的角色是"回答用户问题的技术助手"，
	 * 而且明确要求"不输出 JSON、不暴露字段名" —— 与判定"只输出一个 JSON 对象"的契约正好相反。
	 */
	TASK_REVIEW("task-review", "prompts/task-review.st",
			"agent 任务判定的系统提示词：需求歧义判定与任务点拆分的工作方式、工具与技能的使用边界、JSON 输出口径"),

	/**
	 * 对话历史压缩（滚动摘要）的系统提示词。
	 *
	 * <p>它回答的是"把被挤出去的那一段对话压成什么"：哪些内容必须逐字保留、哪些可以概括、
	 * 以及<b>压缩后的摘要自己要怎么写才不会被下一轮误解</b>。
	 *
	 * <p>读法与 {@link #AGENT_LOOP} 一样（每次压缩前读一次），所以换一版对<b>下一次压缩</b>生效。
	 * 它与另外三份都不能互换：{@link #CHAT_SYSTEM} 是"回答用户"的人格，
	 * {@link #TASK_REVIEW} 输出 JSON，{@link #AGENT_LOOP} 是带工具的技术助手 ——
	 * 而这一份的产物是一段给人看、也给模型当背景的资料。
	 */
	CHAT_COMPACTION("chat-compaction", "prompts/chat-compaction.st",
			"对话历史压缩的系统提示词：把被挤出的旧对话压成一份保留事实的摘要"),

	/**
	 * 定时任务<b>把描述模板解析成"当下这条任务的描述"</b>的系统提示词。
	 *
	 * <p>它回答的是"一句带周期的描述（'昨天''上个月''本周'）到点该变成什么"：
	 * 哪些词要落到具体日期、哪些必须逐字保留、以及<b>输出只能是那句描述本身</b>
	 * （不要解释、不要围栏、不要顺手把要求改掉或加码）。
	 *
	 * <p>读法与 {@link #AGENT_LOOP} 一样（每次解析前读一次），所以换一版对<b>下一次触发</b>生效。
	 * 它与另外几份都不能互换：{@link #CHAT_COMPACTION} 的产物是给人看的资料、
	 * {@link #TASK_REVIEW} 输出 JSON、{@link #AGENT_LOOP} 是带工具的技术助手 ——
	 * 而这一份的产物是<b>要直接写进 agent 任务那一列</b>的一句任务描述。
	 */
	TASK_SCHEDULE("task-schedule", "prompts/task-schedule.st",
			"定时任务的描述模板解析：把「昨天 / 上个月 / 本周」这类周期说法落到当下那一天的具体日期，只输出那句描述本身");

	private final String defaultValue;

	private final String classpathLocation;

	private final String summary;

	PromptKind(String defaultValue, String classpathLocation, String summary) {
		this.defaultValue = defaultValue;
		this.classpathLocation = classpathLocation;
		this.summary = summary;
	}

	/** 库里存的名字（也是界面上与接口里用的名字）。 */
	public String defaultValue() {
		return defaultValue;
	}

	/** classpath 位置：出厂默认值与兜底的来源，<b>不是</b>运行时的读取来源。 */
	public String classpathLocation() {
		return classpathLocation;
	}

	/** 一句话说明这一份管什么（给界面显示，避免读者去代码里找）。 */
	public String summary() {
		return summary;
	}

	/**
	 * 按名字解析。
	 *
	 * <p>不认识的返回 {@code null} 而不是抛异常：调用方（端点）需要能区分
	 * "名字不认识"（400，是调用方的错）与"这一份读不出来"（500，是部署的错），
	 * 而这两句话该由端点说出来。
	 */
	public static PromptKind byName(String name) {
		if (name == null) {
			return null;
		}
		String trimmed = name.trim();
		for (PromptKind kind : values()) {
			if (kind.defaultValue.equals(trimmed)) {
				return kind;
			}
		}
		return null;
	}

}
