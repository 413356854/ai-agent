package ai.agent.agent;

import ai.agent.tool.ToolInvocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 重复动作检测（路线文档：「重复动作检测（同工具同参数）」）。
 *
 * <h2>为什么"最大步数"拦不住它最该拦的那种情况</h2>
 * 步数上限只能事后止损：它允许模型把同一个动作做满 N 次才停。
 * 而模型卡住时的典型形态是<b>完全相同的调用</b>：
 * 同一个工具、同一份参数字符串，反复调用，因为它没有意识到上一次的 observation
 * 已经告诉它"没有结果"。
 *
 * <p>这时每一轮都在花钱（一次模型调用 + 一次工具执行），而<b>信息量为零</b>。
 * 所以这个检测的价值不是"防止无限循环"（步数上限也能防），而是
 * <b>把那几轮的钱省下来，并且把失败原因说得准确</b>：
 * 报告里写"超出最大步数"会让人以为任务太复杂，
 * 而真相是"模型在原地打转" —— 这两个原因要采取的改进措施完全相反
 * （前者调大上限，后者改工具描述或提示词）。
 *
 * <h2>为什么按"工具名 + 原始参数文本"比对，而不是解析后的对象</h2>
 * 解析后的对象需要类型信息（每把工具的参数类型都不同），而且"参数等价"的判定会引入
 * 一堆边界情况（浮点误差、字段顺序、null vs 缺失）。而<b>模型重复时给出的是逐字节相同的
 * JSON</b> —— 它是从自己的上文里复制出来的。用原始文本比对，既简单又精确命中真实情况。
 *
 * <p>对字段顺序不同但语义相同的参数（{@code {"a":1,"b":2}} vs {@code {"b":2,"a":1}}）
 * 本检测<b>认不出来</b>。这是刻意的取舍：那需要在每把工具的参数类型上做规范化，
 * 收益很小（模型复制上文时不会重排字段），代价是复杂度。这个局限写在注释里，
 * 而不是假装它不存在。
 */
public class DuplicateActionDetector {

	/**
	 * 同一个动作<b>总共</b>允许出现几次。第 {@code allowedRepeats + 1} 次才算重复。
	 *
	 * <p>这个算式写在注释里是刻意的：<b>"允许重复 N 次"是有二义的</b> ——
	 * 它可以理解为"出现 N+1 次"（N 次算重复），也可以理解为"出现 N 次"。
	 * 两种读法都不会报错，只会让实现与读代码的人各按一种理解走。
	 * 与其争论措辞，不如把算式钉在这里。
	 */
	private final int allowedRepeats;

	/** 工具名 + 规范化后的参数 → 出现次数。用 LinkedHashMap 保证报告里顺序稳定。 */
	private final Map<String, Integer> counts = new LinkedHashMap<>();

	/** 按出现顺序记录，便于报告展示"重复的是哪几个动作"。 */
	private final List<String> repeatedActions = new ArrayList<>();

	public DuplicateActionDetector(int allowedRepeats) {
		// 至少允许 1 次：一次都不允许等于"任何工具调用都会立刻终止循环"，
		// 那不是这个检测要表达的意思（它要表达的是"同一个动作别再重复"）
		this.allowedRepeats = Math.max(1, allowedRepeats);
	}

	/**
	 * 记录一次调用，返回它是不是"已经重复到该被拦下"。
	 *
	 * <p>返回 true 表示<b>调用方应当停止循环</b>，而不是"再试一次"。
	 */
	public boolean record(ToolInvocation invocation) {
		String key = keyOf(invocation);
		int count = counts.merge(key, 1, Integer::sum);
		if (count > allowedRepeats) {
			if (!repeatedActions.contains(key)) {
				repeatedActions.add(key);
			}
			return true;
		}
		return false;
	}

	/** 目前出现过的重复动作（去重、保序）。 */
	public List<String> repeatedActions() {
		return List.copyOf(repeatedActions);
	}

	/** 同一个动作最多出现了几次（报告用：一眼看出"卡得有多死"）。 */
	public int maxRepeatCount() {
		return counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
	}

	/** 规范化：去掉参数里的空白，这样 {@code {"a": 1}} 与 {@code {"a":1}} 视为同一个动作。 */
	public static String keyOf(ToolInvocation invocation) {
		String args = invocation.arguments() == null ? "{}" : invocation.arguments().replaceAll("\\s+", "");
		return invocation.name() + "|" + args;
	}

}
