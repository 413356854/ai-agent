package ai.agent.controller;

import ai.agent.service.graph.GraphDemoService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Graph 演示端点：元信息、同步运行、从检查点重放。
 *
 * <p>控制器只做转发；校验、并发闸门与响应组装在 {@link GraphDemoService}。
 * 功能开关关闭时本类不装配，整个 {@code /api/graph-demo/**} 返回 404。
 */
@RestController
@RequestMapping(path = "/api/graph-demo", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "ai.graph-demo.enabled", havingValue = "true", matchIfMissing = true)
public class GraphDemoController {

	private final GraphDemoService service;

	public GraphDemoController(GraphDemoService service) {
		this.service = service;
	}

	/**
	 * 页面元信息：图的节点与边、状态键及策略、当前模型名、预计模型调用次数、检查点说明。
	 *
	 * <p><b>只读，不调用模型</b>。页面进入时拉一次，用来画拓扑与决定运行按钮的提示；
	 * 返回的 {@code nodes} / {@code edges} 是前端拓扑的清单来源。
	 */
	@GetMapping("/meta")
	public Map<String, Object> meta() {
		return service.meta();
	}

	/**
	 * 同步运行一次图：triage → route_issue → bug/feature/question 分支 → 实现 → 评审 → 审计。
	 *
	 * <p>返回值包括节点轨迹（每个节点的输入/输出 keys、耗时、摘要与 outcome）、最终状态、
	 * 检查点历史与运行提示。请求里的 {@code type} 是<b>回退路由</b>：模型给出的
	 * {@code route_candidate} 有效时以模型为准。
	 *
	 * <p>一次运行串行调用 3–7 次真实模型（question 3 次；bug/feature 5 次；
	 * {@code forceFirstReviewFail=true} 时 7 次）。同一时刻只允许一条运行，第二次请求返回 409；
	 * 参数为空或 type 非法返回 400；模型/图执行失败返回 502。
	 * {@code threadId} 可省略，省略时服务端生成 {@code graph-<uuid>}；页面不传它。
	 */
	@PostMapping(path = "/run", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> run(@RequestBody(required = false) GraphDemoService.RunRequest request) {
		return service.run(request);
	}

	/**
	 * 从指定检查点之后重放：按 {@code threadId} + {@code checkpointId} 找到检查点，
	 * 重新执行它之后的节点，并把新检查点追加到同一个 thread。
	 *
	 * <p>返回重放后的完整轨迹、最终状态、增长后的检查点历史，以及
	 * {@code replayedFromTraceIndex} / {@code replayedNodeIds} 供页面标出这次重放的节点。
	 * 检查点不存在、或它之后已经没有节点（{@code next=__END__}）返回 400；
	 * 同一时刻只允许一条运行，第二次请求返回 409；模型/图执行失败返回 502。
	 *
	 * <p><b>重放不是时间旅行</b>：它重新执行检查点之后的节点，会再次调用真实模型并产生费用；
	 * 模型输出也可能与第一次不同。检查点由进程内的 {@code MemorySaver} 保存，后端重启后清空。
	 */
	@PostMapping(path = "/replay", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> replay(@RequestBody(required = false) GraphDemoService.ReplayRequest request) {
		return service.replay(request);
	}

}
