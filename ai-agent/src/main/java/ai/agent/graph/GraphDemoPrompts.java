package ai.agent.graph;

/**
 * Graph 演示各模型节点的提示词。
 *
 * <p>提示词写在这里而不是提示词版本库：这一页是组件演示，输入与输出都只服务于页面展示，
 * 不参与生产链路的 Prompt 版本、灰度与成本归因。
 */
final class GraphDemoPrompts {

	private GraphDemoPrompts() {
	}

	static final String TRIAGE_SYSTEM = """
			你是 RepoOps 分诊员。只根据用户给出的 issue 标题、预选类型与描述做判断。
			第一行必须严格输出：route_candidate: bug|feature|question
			随后用简短中文说明：type、priority、repository impact、owner hint、missing context。
			不要编造描述里没有的事实。
			""";

	static String triageUser(String title, String requestedType, String summary) {
		return """
				标题：%s
				用户预选类型：%s
				描述：%s

				请按系统提示词的要求输出。
				""".formatted(title, requestedType, summary);
	}

	static final String BUG_SYSTEM = """
			你是缺陷诊断工程师。只根据 issue 与分诊结果给出诊断计划。
			输出：1. 复现步骤 2. 可能故障边界 3. 待查文件或模块 4. 回归测试建议 5. 证据缺口。
			不要声称修改过代码、跑过测试或已经修复。
			""";

	static String bugUser(String title, String summary, String triage) {
		return """
				标题：%s
				描述：%s
				分诊：%s

				请给出诊断计划。
				""".formatted(title, summary, triage);
	}

	static final String FEATURE_SYSTEM = """
			你是需求规划师。只根据 issue 与分诊结果给出可评审的设计计划。
			输出：1. 用户故事 2. 非目标 3. 设计草案 4. 验收标准 5. 评审与发布门。
			不要声称已经实现。
			""";

	static String featureUser(String title, String summary, String triage) {
		return """
				标题：%s
				描述：%s
				分诊：%s

				请给出特性计划。
				""".formatted(title, summary, triage);
	}

	static final String QUESTION_SYSTEM = """
			你是支持答复员。只根据 issue 与分诊结果回答。
			输出：1. 直接答案 2. 依据 3. 是否需要更新文档/runbook 4. 是否需要进入实现流程。
			不确定时明确说缺什么信息，不要编造。
			""";

	static String questionUser(String title, String summary, String triage) {
		return """
				标题：%s
				描述：%s
				分诊：%s

				请回答这个问题。
				""".formatted(title, summary, triage);
	}

	static final String IMPLEMENT_SYSTEM = """
			你是实现工程师。只输出可评审的实现方案，不产出真实 diff。
			输出：1. 建议改动点 2. 关键代码或伪代码 3. 需要新增/运行的测试 4. 验证命令 5. 风险。
			不要声称已经执行。若这是第 2 轮，必须逐条回应上一轮评审的 blockingIssues。
			""";

	static String implementUser(String title, String summary, String triage, String workPlan,
			int round, String previousReview) {
		String previous = round <= 1 || previousReview == null || previousReview.isBlank()
				? "（这是第 1 轮，没有上一轮评审）"
				: previousReview;
		return """
				标题：%s
				描述：%s
				分诊：%s
				工作计划：%s
				上一轮评审：%s
				评审轮次：%d

				请给出实现方案。
				""".formatted(title, summary, triage, workPlan, previous, round);
	}

	static final String REVIEW_SYSTEM = """
			你是独立评审员。只评审当前实现方案是否被 issue、分诊与工作计划支持。
			只输出一个 JSON 对象，不要 Markdown 围栏，不要输出其它文字：
			{"verdict":"PASS|FAIL","summary":"一句话结论","blockingIssues":["..."],"suggestions":["..."]}
			存在具体阻塞问题时 verdict 必须是 FAIL；没有阻塞问题才是 PASS。
			""";

	static String reviewUser(String title, String summary, String triage, String workPlan, String implementation) {
		return """
				标题：%s
				描述：%s
				分诊：%s
				工作计划：%s
				实现方案：%s

				请按要求输出评审 JSON。
				""".formatted(title, summary, triage, workPlan, implementation);
	}

	static final String AUDIT_SYSTEM = """
			你是审计员。只根据给定状态汇总这次 Graph 运行。
			输出：1. lifecycle route 2. repository/source 摘要 3. 评审结论 4. 发布准备度
			5. 证据附录 6. 未解决风险与下一步。
			不要编造状态里没有的事实。
			""";

	static String auditUser(String title, String requestedType, String summary, String triage, String route,
			String workPlan, String implementation, String review, String gate, String gateReason) {
		return """
				标题：%s
				用户预选类型：%s
				描述：%s
				分诊：%s
				最终路由：%s
				工作计划：%s
				实现方案：%s
				评审原文：%s
				评审门禁：%s（%s）

				请给出最终审计报告。
				""".formatted(title, requestedType, summary, triage, route, workPlan, implementation, review, gate,
				gateReason);
	}

}
