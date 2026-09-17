package ai.agent.controller.cost;

import ai.agent.service.cost.CostDashboardView;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 成本看板的端点（阶段四）。
 *
 * <h2>为什么它是一个独立的 controller，而不是加到 {@code AgentController}</h2>
 * 因为 {@code AgentController} 回答的是"**某一次运行**干了什么"，
 * 而这里回答的是"**一批运行**一共花了多少"。两者的取数粒度差一个数量级 ——
 * 前者按 id 取一行，后者按时间窗扫表并分组。放在一起之后，
 * 那个类的注释里那句"端点几乎不含业务逻辑：只做读 + 组装"就不成立了。
 *
 * <h2>两个端点，一条只读路径</h2>
 * <table border="1">
 *   <caption>端点与它回答的问题</caption>
 *   <tr><th>端点</th><th>回答什么</th></tr>
 *   <tr><td>{@code GET /api/cost/summary?days=7}</td><td>"这段时间花了多少、谁花的、花得值不值"</td></tr>
 *   <tr><td>{@code GET /api/cost/prices}</td><td>"现在是按什么价在算的、哪些模型还没价"</td></tr>
 * </table>
 *
 * <p>两个都是 GET 而不是 POST：它们只读、不改任何状态、也不花钱
 * （与 {@code /api/agent/ask} 正好相反 —— 那个必须 POST，因为它会真的调模型）。
 *
 * <h2>为什么价格要单独一个端点，而不是塞进 summary</h2>
 * 因为两者的问题不同：summary 回答"花了多少"，prices 回答"这个数字是怎么来的、
 * 还差什么"。一个没有价格配置的部署会**反复**需要后者（那是一个待办清单），
 * 而 summary 是日常刷新的那一个。分开之后，界面能把"待配置"做成一个独立的可操作入口。
 */
@RestController
@RequestMapping(path = "/api/cost", produces = MediaType.APPLICATION_JSON_VALUE)
public class CostController {

	private final CostDashboardView view;

	public CostController(CostDashboardView view) {
		this.view = view;
	}

	/**
	 * 成本看板快照。
	 *
	 * @param days 时间窗（天）。默认 7；上限在 {@code CostDashboardView.MAX_DAYS}（会被夹住而不是报错）——
	 *             夹住而不是拒绝，是因为"在地址栏里多打一个 0"不该得到一个错误页，
	 *             但也不该让一次页面刷新变成一次全表扫描
	 */
	@GetMapping("/summary")
	public Map<String, Object> summary(
			@RequestParam(defaultValue = "7") int days) {
		return view.snapshot(days);
	}

	/** 价格表与"还缺哪些模型的价格"。 */
	@GetMapping("/prices")
	public Map<String, Object> prices() {
		return view.priceSnapshot();
	}

}
