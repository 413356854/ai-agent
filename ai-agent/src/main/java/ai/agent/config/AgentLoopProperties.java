package ai.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 循环的三重上限（最大步数 / 最大 Token / 最大时长）。
 *
 * <h2>为什么"三个上限"必须同时存在，一个都不能省</h2>
 * 它们是三种<b>完全不同的死法</b>，各自能拦住另外两个拦不住的情况：
 * <table border="1">
 *   <caption>各自防的是什么</caption>
 *   <tr><th>上限</th><th>防的场景</th><th>只有一个上限时会发生什么</th></tr>
 *   <tr><td>{@code maxSteps}</td><td>模型在原地打转（反复调同一个工具）</td>
 *       <td>只限 token 的话，模型用极短的调用也能转上几百轮</td></tr>
 *   <tr><td>{@code maxTokens}</td><td>单轮上下文极长（一次检索塞进 8000 token）</td>
 *       <td>只限步数的话，6 步 × 8000 token 就够烧掉一大笔钱</td></tr>
 *   <tr><td>{@code maxDurationMs}</td><td>下游变慢（模型排队、工具卡住）</td>
 *       <td>只限步数/token 的话，一轮卡 5 分钟也照样"没超限"</td></tr>
 * </table>
 *
 * <p><b>为什么做成配置而不是常量</b>：这三个数字与"任务复杂度"强相关，
 * 而任务复杂度是随业务变的。写死之后，要么为了复杂任务把上限调得极高（等于没有上限），
 * 要么为了省钱把上限压得极低（简单任务也失败）。做成配置之后，
 * 可以按调用方分别设置（这也是阶段四"成本归因"的前置条件）。
 *
 * @param maxSteps 最多几轮"模型 → 工具"往返
 * @param maxTokens 整个任务累计允许消耗的 token（含所有轮次）
 * @param maxDurationMs 整个任务的时间上限
 * @param maxIdenticalActions 同一个工具 + 同一份参数连续重复几次就判定为"卡住了"
 */
@ConfigurationProperties(prefix = "ai.agent.loop")
public record AgentLoopProperties(int maxSteps, int maxTokens, long maxDurationMs, int maxIdenticalActions) {

	/**
	 * 默认值的选择依据（都是按"这个项目里真实的工具耗时"定的，不是拍脑袋）：
	 *
	 * <ul>
	 *   <li>{@code maxSteps = 6}：典型任务只需要 2~3 轮（列文档 → 检索 → 读章节）。
	 *       6 轮给了"试错两次"的余量；再多基本就是在打转了 ——
	 *       实测里模型卡住时的形态是反复调同一把工具，而不是"需要一个很长的规划"。</li>
	 *   <li>{@code maxTokens = 12000}：一轮工具往返约 1500~2500 token
	 *       （系统提示词 + 工具描述 + observe 片段），6 轮约 1 万，留一点余量。
	 *       这个数字与 {@code ai.tool.feedback.max-chars=2000} 是<b>联动</b>的：
	 *       调大回填上限就必须一起调大这里。</li>
	 *   <li>{@code maxDurationMs = 60000}：单轮实测 1~4 秒（本地检索毫秒级，模型是主要耗时），
	 *       6 轮正常在 20 秒内。60 秒能容下"模型偶尔慢"但不至于让用户等到以为页面挂了。</li>
	 *   <li>{@code maxIdenticalActions = 2}：允许重复一次（有时重复一次是合理的 ——
	 *       例如模型想确认结果稳定），第二次相同就说明它没有新信息还在原地试。</li>
	 * </ul>
	 */
	public static AgentLoopProperties defaults() {
		return new AgentLoopProperties(6, 12_000, 60_000L, 2);
	}

	public AgentLoopProperties {
		if (maxSteps <= 0) {
			throw new IllegalArgumentException("ai.agent.loop.max-steps 必须为正数");
		}
		if (maxTokens <= 0) {
			throw new IllegalArgumentException("ai.agent.loop.max-tokens 必须为正数");
		}
		if (maxDurationMs <= 0) {
			throw new IllegalArgumentException("ai.agent.loop.max-duration-ms 必须为正数");
		}
		// 允许 0：表示"连一次重复都不容忍"（更严格），这是合法配置
		if (maxIdenticalActions < 0) {
			throw new IllegalArgumentException("ai.agent.loop.max-identical-actions 不能为负");
		}
	}

}
