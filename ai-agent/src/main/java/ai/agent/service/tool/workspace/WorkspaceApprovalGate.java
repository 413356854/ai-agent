package ai.agent.service.tool.workspace;

import ai.agent.config.AgentApprovalProperties;
import ai.agent.entity.model.tool.ToolHints;
import ai.agent.entity.tool.RunPermission;
import ai.agent.entity.tool.ToolCallContext;
import ai.agent.entity.tool.ToolInvocation;
import ai.agent.service.tool.AgentTool;
import ai.agent.service.tool.ToolApprovalGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * <b>把「这一次运行的权限档位」套在人工确认闸门外面</b>。
 *
 * <h2>它为什么是一个包装而不是改 {@code AgentApprovalService}</h2>
 * 那个服务回答的是"这把工具<b>本身</b>要不要人点头"（依据是工具的风险声明），
 * 而这个包装回答的是"<b>这一次运行</b>的档位允许它不点头吗"。两个问题的输入不同，
 * 合进一个类里就会出现一个方法既读工具声明又读运行档位，
 * 而它的每一条分支都要同时考虑两种来源 —— 那种代码的失效方式是
 * "某一档下的写操作被静默放行"，没有报错。
 *
 * <p>分成两层之后，三层各自的判断都能被单独读出来：
 * <pre>
 *   WorkspaceApprovalGate（本次运行的档位）
 *        └── AgentApprovalService（工具自己的声明 + 等人在哪等、超时怎么算）
 * </pre>
 *
 * <h2>顺序：<b>先判"档位覆盖"，再判工具声明</b></h2>
 * 被档位覆盖时直接放行，不再问工具声明 —— 这正是
 * 「完全权限则直接执行不需要审核请求」的落地。
 * 反过来（先问工具声明）会让完全权限档位仍然停在写工具上等人，
 * 而那与需求原话相反。
 *
 * <h2>它<b>不做</b>的两件事</h2>
 * <ol>
 *   <li><b>不新增"拒绝"</b>：权限不足的后果是去问人（需求原话），
 *       而不是让调用失败。所以本类只会多产生 {@code Pending}，从不产生 {@code Denied}；</li>
 *   <li><b>不放宽工具自己的风险声明</b>：完全权限只决定"要不要产生审核请求"，
 *       它不改变"这把工具是不是只读"。</li>
 * </ol>
 *
 * <h2>没有档位时的默认是最严的一档</h2>
 * 外部 MCP 客户端那条链路没有"运行"这个对象，所以它的上下文里没有档位，
 * 而缺失一律按 {@link RunPermission#none()}（仅可查看）算 ——
 * 于是"会不会放宽"这件事有唯一的答案：<b>不会</b>。
 * 若反过来把"没有档位"当成完全权限，那么一条没人审计的链路就会成为绕过审批的入口。
 *
 * <h2>⚠️ 跑在 {@code /ask} 链路上时，"等人"发生在内层而不是这里</h2>
 * 内层的 {@code AgentApprovalService} 是<b>就地阻塞等人</b>的
 * （它注册待批之后用 {@code CompletableFuture.get} 把当前线程停住），
 * 所以 {@code /ask} 这条链路上不产生 {@code AWAITING_APPROVAL} 这个 stage，
 * 而是"请求挂着、界面上出现一条待批、人点一下它继续跑完"。
 * 本类不改变这件事：档位没覆盖时原样交给内层，覆盖时根本不进内层。
 */
@Component
@Primary
public class WorkspaceApprovalGate implements ToolApprovalGate {

	private static final Logger log = LoggerFactory.getLogger(WorkspaceApprovalGate.class);

	private final ToolApprovalGate delegate;

	private final ObjectProvider<AgentApprovalProperties> approvalProperties;

	public WorkspaceApprovalGate(ToolApprovalGate delegate, ObjectProvider<AgentApprovalProperties> approvalProperties) {
		this.delegate = delegate;
		this.approvalProperties = approvalProperties;
	}

	@Override
	public Authorization authorize(ToolInvocation invocation, AgentTool<?> tool, ToolCallContext context) {
		RunPermission permission = context == null ? null : context.runPermission();
		AgentPermissionPolicy.Operation operation = AgentPermissionPolicy.operationOf(tool);

		if (!AgentPermissionPolicy.coversWithoutApproval(permission, operation)) {
			// 档位没覆盖它：交给内层闸门（工具自己的声明 + 等人/超时的整套机制）
			return delegate.authorize(invocation, tool, context);
		}

		// ---- 覆盖了：直接执行，并把"为什么没弹确认"记成一句话 ----
		//
		// 【只读操作与写操作的理由要分开】前者"换一档也不会弹"，后者
		// "换成仅可查看就会弹" —— 合成一句之后，事后查"这次为什么没弹"读不出结论
		ToolHints hints = tool.description().hints();
		if (operation.isWrite()) {
			// 只在"确实放宽了"时留 WARN —— 这句日志是这一层唯一的运行时证据：
			// 一条完全权限的运行不会在界面上留下任何"被放行"的痕迹（它本来就不产生待批）
			log.warn("工具「{}」的写调用在 {} 下直接执行（跳过人工确认）。工具自己的声明是：{}", tool.name(),
					AgentPermissionPolicy.describe(permission), hints == null ? "(未声明)" : hints.describe());
			return Authorization.Allowed.byRunPermission(
					"本次运行的权限档位覆盖了它：%s（写操作在该档位下直接执行，不产生审核请求）"
							.formatted(AgentPermissionPolicy.describe(permission)));
		}
		log.debug("工具「{}」是只读调用，直接执行（档位 {}）", tool.name(),
				AgentPermissionPolicy.describe(permission));
		return Authorization.Allowed.byRunPermission("只读调用不受权限档位限制（三档里没有一档禁止查看）");
	}

	/**
	 * 档位没覆盖这次调用时，交给内层闸门 —— <b>内层可能就是"全部放行"</b>。
	 *
	 * <p>那时这一档的约束就只剩注释里的一句话（同 {@code ToolApprovalGate.Allow} 那条：
	 * "我配了保护"与"保护真的装着"是两件事）。所以这里返回一个把开关状态说清楚的字符串。
	 */
	@Override
	public String describe() {
		AgentApprovalProperties properties = approvalProperties.getIfAvailable();
		String inner = delegate.describe();
		String enabled = properties == null ? "(开关状态读不到)"
				: properties.enabled() ? "已启用（enabled=true）"
						: "已关闭（ai.agent.approval.enabled=false）—— 写操作不会停在闸门上等人，本次运行的档位约束因此不生效";
		return "运行权限档位生效中：仅可查看 / 工作区内修改两档下写操作要人点头，完全权限直接执行；"
				+ "内层人工确认 " + enabled + "（" + inner + "）";
	}

}
