package ai.agent.service.agent;

import ai.agent.entity.agent.AgentRun;
import ai.agent.service.agent.approval.AgentApprovalService;
import ai.agent.service.agent.approval.ApprovalStatus;
import ai.agent.entity.agent.approval.ToolApprovalRecord;
import ai.agent.entity.agent.store.AgentRunRecord;
import ai.agent.service.agent.store.AgentRunStore;
import ai.agent.service.agent.store.RunStatus;import ai.agent.service.support.agent.store.TranscriptCodec;
import ai.agent.config.AgentStoreProperties;
import ai.agent.entity.tool.ApprovalGrant;
import ai.agent.entity.tool.ToolInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 带状态持久化与断点恢复的 Agent 服务（阶段三：路线文档验收里的「进程被杀能从检查点恢复」）。
 *
 * <h2>它比 {@link AgentLoop} 多做了什么</h2>
 * 循环负责"把任务跑完"，本类负责"跑的过程不丢"：
 * <ol>
 *   <li>跑之前先落一条 {@code RUNNING} —— 否则"进程第一步就挂了"在库里不留任何痕迹；</li>
 *   <li>每一步结束写检查点（**含完整对话**）—— 恢复靠它，不靠重放；</li>
 *   <li>启动时认领上一次留下的未完成运行（{@code RUNNING} → {@code INTERRUPTED}）；</li>
 *   <li>从最新检查点**接着跑**，并把"那个工具的结果未知"这件事显式告诉模型。</li>
 * </ol>
 *
 * <h2>恢复为什么不是"从头重跑一遍"</h2>
 * 这是本类最重要的一个取舍。重跑看起来简单（反正有幂等性就行？），但：
 * <ul>
 *   <li><b>已经花掉的 token 会再花一遍</b> —— 一个跑了 4 步的任务，重跑就是 4 步的钱；</li>
 *   <li><b>工具会被重复执行</b>（只读的是浪费，写操作是事故）——
 *       这正是路线文档在面试追问里点名的「工具调用重试会不会产生重复副作用」；</li>
 *   <li><b>上下文会变</b>：工具返回的内容可能已经不一样了，重跑出来的 trace
 *       与你原来看到的那份不是同一个东西，于是"还原执行过程"也就无从谈起。</li>
 * </ul>
 * 所以这里"接着跑"：把检查点里的对话原样恢复，步数接着数。
 *
 * <h2>关于 stepIndex 的续接</h2>
 * 恢复后的步号从"已跑过的步数 + 1"开始。这不只是好看 ——
 * {@link AgentLoop} 的步数上限是按轮号判定的，如果恢复时从 1 重新数，
 * 一个被杀过 N 次的任务就能跑 N×maxSteps 步，上限形同虚设。
 */
@Service
public class ResumableAgentService {

	private static final Logger log = LoggerFactory.getLogger(ResumableAgentService.class);

	private final AgentLoop loop;

	private final AgentRunStore store;

	private final TranscriptCodec codec;

	private final AgentStoreProperties properties;

	/**
	 * 人工审批（HITL）。
	 *
	 * <p>依赖方向是 <b>service → approval</b>：服务层负责"把审批结果翻译成循环能吃的输入"
	 * （查记录、核对、构造成 {@link ApprovalGrant}），而审批服务自己不知道循环的存在。
	 * 反过来的话，审批服务就得依赖 {@code AgentLoop} —— 那会让"审批"这件与业务无关的事
	 * 绑死在 Agent 上，将来 MCP 那条链路要复用它时就得先把 Agent 一起拖进来。
	 */
	private final AgentApprovalService approvalService;

	/**
	 * 取"这一步是哪个模型跑的"（阶段四成本看板）。
	 *
	 * <p>用的是 {@code ObjectProvider} 而不是直接注入 {@link ModelTurn}：这个服务在
	 * 很多测试里被直接 new 出来（没有 Spring 容器），而那些测试不关心成本归因。
	 * 真正需要它的是**生产路径**，那里容器里一定有。
	 *
	 * <p>取不到时记 {@link ModelTurn#UNKNOWN_MODEL} —— **不是**记一个默认模型名：
	 * 编一个模型名会让成本按错的单价算，而那个错钱数在页面上完全看不出来。
	 * 这与"认不出来就说认不出来"是同一条规矩。
	 */
	private final org.springframework.beans.factory.ObjectProvider<ModelTurn> modelTurnProvider;

	public ResumableAgentService(AgentLoop loop, AgentRunStore store, TranscriptCodec codec,
			AgentStoreProperties properties, AgentApprovalService approvalService,
			org.springframework.beans.factory.ObjectProvider<ModelTurn> modelTurnProvider) {
		this.loop = loop;
		this.store = store;
		this.codec = codec;
		this.properties = properties;
		this.approvalService = approvalService;
		this.modelTurnProvider = modelTurnProvider;
	}

	/** 新起一次任务（带持久化）。 */
	public AgentRun start(String question) {
		String runId = UUID.randomUUID().toString();
		return runWithId(runId, question);
	}

	/** 用指定 id 跑一次（测试要能控制 id，否则没法在断言里引用它）。 */
	public AgentRun runWithId(String runId, String question) {
		// 显式转型：`runWithId(id, q, null)` 在这里是**歧义的**
		// ——第三个参数既可能是 ModelTurn（覆盖模型），也可能是 String（归因键），
		// 而 null 两种都匹配。编译器报"对 runWithId 的引用不明确"，
		// 那一行代码看起来毫无问题（这正是重载 + null 的经典陷阱）。
		// 顺带说明为什么没有把两个重载合成一个"接受 Object"的方法：那样更糟 ——
		// 它会把一个**编译期**能发现的错误推成运行期的 ClassCastException
		return runWithId(runId, question, (ModelTurn) null);
	}

	/**
	 * 用指定 id 与<b>业务归因键</b>跑一次（阶段四成本看板）。
	 *
	 * @param intent 业务归因键；{@code null} 表示未标注
	 */
	public AgentRun runWithId(String runId, String question, String intent) {
		// 【第六件在这里留下了一处必须记住的教训】
		// 这一行原来写的是 `runWithId(runId, question, null, intent, null)` ——
		// 加了"五参 + 第六个 String 租户"那个重载之后，它变成了**歧义**：
		// 五个实参两种都匹配（`(String,String,String,String,String)` 与
		// `(String,String,ModelTurn,String,String)`），javac 报「对 runWithId 的引用不明确」。
		// 也就是说：**新增一个 String 参数，会让"第三位是 null"的每一处调用都变成编译错误。**
		// 好在它是编译错误而不是静默走错分支 —— 那种"编译器帮你把影响面找齐"的失败，
		// 与 tenantId 加进 Start 只改一处构造点是同一件事的两面。
		// 修法就是第 112 行那句注释说的办法：把歧义的那一位显式转型。
		return runWithId(runId, question, (ModelTurn) null, intent, null, null);
	}

	/**
	 * 用指定 id 与<b>归因键 + 会话 id</b>跑一次（阶段四第 2 件：Agent trace 的第一层）。
	 *
	 * @param intent 业务归因键；{@code null} 表示未标注
	 * @param conversationId 会话 id；{@code null} / 空白 = 没有会话信息
	 */
	public AgentRun runWithId(String runId, String question, String intent, String conversationId) {
		return runWithId(runId, question, (ModelTurn) null, intent, conversationId, null);
	}

	/**
	 * 用指定 id 与<b>归因键 + 会话 id + 租户</b>跑一次（阶段四第 6 件）。
	 *
	 * @param tenantId 租户：这次运行花的是**谁**的钱；{@code null} / 空白 = 未标注租户
	 */
	public AgentRun runWithId(String runId, String question, String intent, String conversationId, String tenantId) {
		return runWithId(runId, question, null, intent, conversationId, tenantId);
	}

	/**
	 * 用指定 id 与<b>指定模型</b>跑一次。
	 *
	 * <h2>为什么要暴露"换一个模型"这个能力（而不是只给测试用）</h2>
	 * 它不只是测试便利，它是<b>这条链路上唯一能造出"中途死掉"的手段</b>：
	 * 断点恢复要验证的是"进程死在两步之间"，而那要求"跑到某一步就停住"——
	 * 用一个会在指定轮次抛异常的模型就能确定性地造出来，
	 * 而不必真的去 kill 进程（那种测试既慢又不稳定）。
	 *
	 * <p>顺带它也让"同一个 loop 换不同模型跑"成为可能 —— 那是做模型对照实验的入口
	 * （属于阶段四"评测接入 CI"的范畴）。
	 *
	 * @param modelTurn 覆盖默认模型；传 {@code null} 表示用容器里那个
	 */
	public AgentRun runWithId(String runId, String question, ModelTurn modelTurn) {
		return runWithId(runId, question, modelTurn, null, null);
	}

	/** 完整入口：id + 问题 + 覆盖模型 + 业务归因键（不带会话）。 */
	public AgentRun runWithId(String runId, String question, ModelTurn modelTurn, String intent) {
		return runWithId(runId, question, modelTurn, intent, null);
	}

	/**
	 * 完整入口：id + 问题 + 覆盖模型 + 业务归因键 + 会话 id。
	 *
	 * <p>等价于"不带租户"的那一次调用 —— 保留五参形态是因为**有一批调用方
	 * 确实不知道租户**（测试、外部只读链路），而 {@link #runWithId(String, String, ModelTurn, String, String, String)}
	 * 的第六个参数会与第五个混淆（两个都是 String）：
	 * 把租户写成会话 id 是**编译得过**的错，而它的现象是
	 * "成本看板上多出一个叫 web-xxxx 的租户"。所以宁可多一个具名入口。
	 */
	public AgentRun runWithId(String runId, String question, ModelTurn modelTurn, String intent,
			String conversationId) {
		return runWithId(runId, question, modelTurn, intent, conversationId, null);
	}

	/**
	 * 完整入口：id + 问题 + 覆盖模型 + 业务归因键 + 会话 id + <b>租户</b>
	 * （阶段四第 6 件：成本归因）。
	 *
	 * @param tenantId 租户：这次运行花的是**谁**的钱；{@code null} / 空白 = 未标注。
	 *            <p>它与 {@code intent} 是两个维度、两列 —— 拼接会互相污染，见
	 *            {@code agent-schema.sql} 里 tenant_id 那一段
	 */
	public AgentRun runWithId(String runId, String question, ModelTurn modelTurn, String intent,
			String conversationId, String tenantId) {
		store.start(new AgentRunStore.Start(runId, question, properties.node(), intent, conversationId, tenantId));
		// 把"这次实际用哪个模型"传给监听器 —— 它是成本归因的一部分（见 PersistingListener.override）
		PersistingListener listener = new PersistingListener(runId, modelTurn);
		try {
			// 传了模型就临时换一个 loop：AgentLoop 是无状态的（所有可变状态都在方法局部变量里），
			// 所以这里 new 一个是安全的 —— 不是"共享可变状态"那种危险做法
			AgentLoop effectiveLoop = modelTurn == null ? loop : loop.withModelTurn(modelTurn);
			AgentRun run = effectiveLoop.run(runId, question, listener);
			store.persistCompletedRun(runId, run);
			return run;
		}
		catch (RuntimeException e) {
			// 循环正常路径不抛异常（失败会变成 MODEL_ERROR 的报告），所以走到这里
			// 说明是**存储或监听器**炸了。必须把状态落成 FAILED：
			// 留在 RUNNING 的话，下次启动会被当成"被中断的运行"去恢复一个已经确定失败的任务
			log.error("Agent 运行失败（非模型原因）runId={}", runId, e);
			safeFinishFailed(runId, e);
			throw e;
		}
	}

	/**
	 * 启动时恢复上一次留下的未完成运行。
	 *
	 * @return 恢复结果列表（每次恢复一条）；没有可恢复的就返回空
	 */
	public List<ResumeResult> resumeInterruptedRuns() {
		// 【先作废"孤儿待批"】进程重启后，内存里那些等人确认的线程都没了，
		// 但库里还留着 PENDING 的记录。不作废的话，界面会一直显示一条
		// **永远点不动**的待批（点了也没人接），而对应运行早已是中断状态。
		// 注意顺序：先作废再认领，否则新认领出来的记录会和旧的混在一起
		expireOrphanApprovals();
		List<AgentRunRecord> interrupted = store.markInterruptedRuns(properties.node());
		if (interrupted.isEmpty()) {
			return List.of();
		}
		log.warn("发现 {} 次上次未跑完的运行，开始恢复", interrupted.size());
		List<ResumeResult> results = new ArrayList<>();
		for (AgentRunRecord record : interrupted) {
			results.add(resume(record));
		}
		return List.copyOf(results);
	}

	/**
	 * 把"没有人在等"的待批作废。
	 *
	 * <h2>为什么"作废"而不是"继续等"</h2>
	 * 一条 PENDING 的审批有三种含义，而它们对界面是完全不同的指令：
	 * <ol>
	 *   <li><b>有线程正在等它</b> → 人点一下，任务立刻继续（正常路径）；</li>
	 *   <li><b>没人等了，但运行还活着</b> → 人点一下，决定落库，任务要靠恢复才继续；</li>
	 *   <li><b>没人等了，运行也已经中断</b> → 这条待批<b>已经没有意义</b>：
	 *       它指向的那次执行永远不会发生。留在界面上只会让人困惑"这到底要不要批"。</li>
	 * </ol>
	 * 本方法处理第 3 种。判据刻意用"运行不是 WAITING_APPROVAL"而不是"有没有线程在等"——
	 * 后者是内存态，重启之后必然是"没有"，而那会把第 2 种也一起误伤掉。
	 */
	private void expireOrphanApprovals() {
		try {
			int expired = 0;
			for (ToolApprovalRecord pending : approvalService.pending()) {
				if (pending.runId() == null) {
					// 没有 runId 的待批来自外部 MCP 客户端那条链路：它没有"运行"这个对象，
					// 所以"运行还在不在"这个判据对它不成立。保持原样，让人自己处理
					continue;
				}
				Optional<AgentRunRecord> run = store.find(pending.runId());
				if (run.isPresent() && run.get().status() == RunStatus.WAITING_APPROVAL) {
					continue;
				}
				if (approvalService.expire(pending.id(), "进程重启后作废：对应的运行已不在等待人工确认")) {
					expired++;
				}
			}
			if (expired > 0) {
				log.warn("作废了 {} 条已经没有人等的待批记录（进程重启遗留）", expired);
			}
		}
		catch (RuntimeException e) {
			// 不让"清理遗留"这件事挡住正常启动：它失败了顶多是界面上多几条点不动的记录，
			// 而抛出去会让整个应用起不来 —— 代价完全不成比例
			log.error("清理遗留待批记录失败（不影响启动）", e);
		}
	}

	/** 恢复一次运行（也可以手工调用某一次）。 */
	public ResumeResult resume(AgentRunRecord record) {
		Optional<AgentRunRecord.Checkpoint> loaded = store.loadCheckpoint(record.id());
		if (loaded.isEmpty()) {
			return ResumeResult.notFound(record.id());
		}
		AgentRunRecord.Checkpoint checkpoint = loaded.get();
		if (!checkpoint.hasTranscript()) {
			// 一次都没跑完一步：没有可用的上下文。**不在这里自己从头跑** ——
			// 那是"重跑"而不是"恢复"，语义不同，该由调用方明确选择
			log.warn("运行 {} 没有可用检查点（可能在第 1 步就被杀了），不做自动恢复", record.id());
			store.finish(record.id(), RunStatus.FAILED, AgentStopReason.MODEL_ERROR, null, record.totalTokens(),
					record.toolCallCount(), record.elapsedMs(), "被中断且没有可用检查点（第 1 步未完成）");
			return ResumeResult.noCheckpoint(record.id());
		}

		// 被杀那一刻"意图已提出、结果未知"的那次调用
		ToolInvocation pending = findPendingInvocation(checkpoint.latestStep().transcriptJson());
		if (pending != null) {
			log.warn("运行 {} 死在一个未完成的工具调用上：{}（会明确告知模型这个结果未知）", record.id(),
					pending.describe());
		}

		return continueRun(record, checkpoint, pending, null);
	}

	/**
	 * <b>人工批准之后接着跑</b>（HITL 的恢复入口）。
	 *
	 * <h2>它为什么与 {@link #resume} 分开，而不是加一个布尔参数</h2>
	 * 两者的语义差别大到不该共用一个入口：
	 * <ul>
	 *   <li>{@link #resume} 的前提是"上一次是<b>意外</b>中断的"，所以模型要被告知
	 *       "那个调用结果未知，别假定成功"；</li>
	 *   <li>本方法的前提是"上一次是<b>按计划</b>停在人工闸门上的"，所以模型要被告知
	 *       "那次调用已经获批，请原样重新提出"。</li>
	 * </ul>
	 * 这两句话是相反的。用一个布尔去切换，很容易在某条路径上传错 ——
	 * 而传错的后果很具体：模型会以为"没成功"从而改写参数，
	 * 于是**人刚点的批准作废、界面又弹一条待批**。
	 *
	 * <h2>三件必须一起做的事</h2>
	 * <ol>
	 *   <li>确认那条审批记录<b>真的是 APPROVED</b>（不是"调用方说它批了"）——
	 *       这是本方法唯一信任的来源；</li>
	 *   <li>把审批记录转成 {@link ApprovalGrant} 交给循环。按"工具名 + 参数"匹配
	 *       （见那个类的注释：恢复后模型会重新提出调用，tool_call_id 变了）；</li>
	 *   <li>确认那条待批的调用就是检查点里悬空的那一个。不确认的话，
	 *       批准 A 却可能放行了 B。</li>
	 * </ol>
	 *
	 * @param runId 运行 id
	 * @param approvalId 刚刚被批准的审批 id
	 */
	public ResumeResult resumeAfterApproval(String runId, String approvalId) {
		ToolApprovalRecord approval = approvalService.find(approvalId)
				.orElseThrow(() -> new IllegalArgumentException("没有这条人工审批记录：" + approvalId));
		if (approval.status() != ApprovalStatus.APPROVED) {
			throw new IllegalStateException(
					"审批 #%s 的状态是 %s，不是 APPROVED —— 只有被批准的调用才能继续执行"
							.formatted(approvalId, approval.status()));
		}
		if (approval.runId() != null && !approval.runId().equals(runId)) {
			throw new IllegalArgumentException(
					"审批 #%s 属于运行 %s，与传入的 %s 不是同一次".formatted(approvalId, approval.runId(), runId));
		}

		AgentRunRecord record = store.find(runId)
				.orElseThrow(() -> new IllegalArgumentException("没有这次运行：" + runId));
		Optional<AgentRunRecord.Checkpoint> loaded = store.loadCheckpoint(runId);
		if (loaded.isEmpty() || !loaded.get().hasTranscript()) {
			// 没有检查点意味着"第 1 步都没跑完"。那说明这次运行并没有真的走到审批那一步 ——
			// 报出来让人看，而不是悄悄从头重跑（重跑会把暂停前的工作全丢一遍）
			throw new IllegalStateException(
					"运行 %s 没有可用检查点，无法接着跑（它可能在第 1 步就被中断了）".formatted(runId));
		}
		AgentRunRecord.Checkpoint checkpoint = loaded.get();

		// 悬空的那次调用 = 检查点里"提出了但没拿到结果"的 tool_call。
		// 审批是绑在那一次调用上的，所以这里必须能对上；对不上就说明库里的状态不干净
		ToolInvocation pending = findPendingInvocation(checkpoint.latestStep().transcriptJson());
		if (pending == null) {
			throw new IllegalStateException(
					"运行 %s 的检查点里找不到悬空的工具调用，与审批 #%s 对不上（拒绝在不清楚状态的情况下继续）"
							.formatted(runId, approvalId));
		}

		ApprovalGrant grant = ApprovalGrant.of(approval.id(), approval.toolName(), approval.arguments());
		if (!grant.matches(pending)) {
			// 【这一条是最重要的防线】审批针对的是"工具名 + 参数"。
			// 如果检查点里悬空的那次调用与审批记录对不上（有人手工改过库、
			// 或者审批记录被错误地挂到了另一次运行上），**必须失败**。
			// 放行它的后果是"批的是 A，执行的是 B"—— 审批制度最严重的失效形态
			throw new IllegalStateException(
					"审批 #%s 针对的是「%s %s」，而运行 %s 悬空的调用是「%s」，两者对不上 —— 拒绝继续"
							.formatted(approvalId, approval.toolName(), approval.arguments(), runId, pending.describe()));
		}

		log.info("运行 {} 带着人工批准 #{} 继续执行：{}", runId, approvalId, pending.describe());
		return continueRun(record, checkpoint, pending, grant);
	}

	/** 从检查点接着跑（共用实现：恢复 与 批准后继续 都走这里）。 */
	private ResumeResult continueRun(AgentRunRecord record, AgentRunRecord.Checkpoint checkpoint,
			ToolInvocation pending) {
		return continueRun(record, checkpoint, pending, null);
	}

	private ResumeResult continueRun(AgentRunRecord record, AgentRunRecord.Checkpoint checkpoint,
			ToolInvocation pending, ApprovalGrant grant) {
		List<Message> messages = codec.decode(checkpoint.latestStep().transcriptJson());
		PersistingListener listener = new PersistingListener(record.id());
		// 【续跑时的提示词指纹：沿用检查点里那一步记的，不重新去读】
		//
		// 理由写在 AgentLoop 那个重载的注释里，这里只说结论：续跑的步骤用的确实是**原来那一版**
		// （系统消息就写在检查点里，本次不会重新注入）。让它重新读一次当前版本会造成两个错：
		//  ① 假话 —— 那几步并不是用新版跑的；
		//  ② 更坏的是它会把一条运行切成两版（前 2 步记 v3、续跑的 3~5 步记 v4），
		//     于是"这次运行到底用的哪一版"这个问题得到两个都"有据可查"的答案。
		// 而正是为了回答那个问题才有这一列 —— 一个会自相矛盾的观测字段比没有更糟。
		AgentRun run = loop.run(record.id(), record.question(), messages, checkpoint.resumeFromStep(), listener,
				pending, grant, checkpoint.latestStep().promptFingerprint());
		store.persistCompletedRun(record.id(), run);
		log.info("运行 {} 已从第 {} 步{}结束：{}（提示词指纹 {}）", record.id(), checkpoint.resumeFromStep(),
				grant == null ? "恢复并" : "（人工批准后）接着跑，", run.stopReason(),
				checkpoint.latestStep().promptFingerprint().isBlank() ? "(未记录)"
						: checkpoint.latestStep().promptFingerprint());
		return ResumeResult.resumed(record.id(), checkpoint.resumeFromStep(), run, pending);
	}

	/**
	 * 从检查点里找出"提出了但没拿到结果"的那次工具调用。
	 *
	 * <h2>为什么要专门找它，而不是忽略</h2>
	 * 它是整条链路里**唯一一个状态不明**的东西：那次调用可能压根没开始，
	 * 也可能已经在外部系统里生效了、只是结果没记下来。
	 * 对只读工具这只是浪费；对写操作，这正是"重复副作用"的来源 ——
	 * 所以恢复时必须把它显式交给模型（见 {@code AgentLoop#interruptionNotice}），
	 * 而不是让模型看到一个悬空的 tool_call 然后自己猜。
	 *
	 * <p>判据：assistant 消息里的每个 {@code tool_calls[i].id}，
	 * 在它之后的 tool 消息里找不到对应返回，即为待定。
	 */
	private ToolInvocation findPendingInvocation(String transcriptJson) {
		List<Message> messages = codec.decode(transcriptJson);
		List<AssistantMessage.ToolCall> calls = new ArrayList<>();
		List<String> answeredIds = new ArrayList<>();
		for (Message message : messages) {
			if (message instanceof AssistantMessage assistant) {
				calls.addAll(assistant.getToolCalls());
			}
			if (message instanceof org.springframework.ai.chat.messages.ToolResponseMessage responses) {
				for (var response : responses.getResponses()) {
					answeredIds.add(response.id());
				}
			}
		}
		for (AssistantMessage.ToolCall call : calls) {
			if (!answeredIds.contains(call.id())) {
				return ToolInvocation.from(call.id(), call.name(), call.arguments());
			}
		}
		return null;
	}

	private void safeFinishFailed(String runId, RuntimeException e) {
		try {
			store.finish(runId, RunStatus.FAILED, AgentStopReason.MODEL_ERROR, null, 0, 0, 0,
					e.getClass().getSimpleName() + ": " + e.getMessage());
		}
		catch (RuntimeException finishFailure) {
			// 连收尾都失败：说明库本身不可用。把两件事一起记下来 ——
			// 只报后者会掩盖前一个原因
			log.error("收尾失败（原始异常：{}）", e.toString(), finishFailure);
		}
	}

	/** 最近若干次运行（排查与界面用）。 */
	public List<AgentRunRecord> recentRuns(int limit) {
		return store.recentRuns(limit);
	}

	/** 同一个会话里的全部运行（按时间正序）—— Agent trace 的第一层。 */
	public List<AgentRunRecord> runsOfConversation(String conversationId) {
		return store.runsOfConversation(conversationId);
	}

	/** 一次运行的完整步骤（还原执行过程用）。 */
	public List<AgentRunRecord.StepCheckpoint> stepsOf(String runId) {
		return store.stepsOf(runId);
	}

	/** 一次运行的全部检索明细（trace 的「检索」那一层）。 */
	public List<AgentRunRecord.RetrievalRecord> retrievalsOf(String runId) {
		return store.retrievalsOf(runId);
	}

	/** 一次运行的全部工具调用（trace 的「工具调用」那一层）。 */
	public List<AgentRunRecord.ToolCallRecord> toolCallsOf(String runId) {
		return store.toolCallsOf(runId);
	}

	/**
	 * 把每一步的检查点写进库的监听器。
	 *
	 * <h2>写失败时为什么会一路抛出去（而不是记个日志继续跑）</h2>
	 * 因为"检查点写不进去"意味着**这次运行没有恢复点**。如果这里吞掉异常继续跑，
	 * 用户会以为一切正常 —— 直到真的被杀那一刻才发现没得恢复，而那时整次运行都丢了。
	 * 宁可现在就打断，让调用方去决定（重试 / 降级成不持久化 / 报错给用户）。
	 */
	private class PersistingListener implements AgentProgressListener {

		private final String runId;

		/**
		 * 这一次运行**实际**用的模型（拿它的名字做成本归因）。
		 *
		 * <h2>为什么它不能是"构造监听器时从容器里取一个"</h2>
		 * 因为 {@link #runWithId(String, String, ModelTurn, String)} 允许**换一个模型跑**，
		 * 而那时容器里那个 ModelTurn <b>根本不是这次用的那一个</b>。
		 * 第一版就是从容器里取的，于是"换模型跑"的每一次运行都被记成配置里那个模型的名字 ——
		 * 抓出它的是一条端到端测试（{@code CostPipelineIntegrationTest}）：
		 * 它给了一个 modelName 为「测试模型-带缓存」的替身，而库里落的是 `deepseek-flash`。
		 *
		 * <p><b>这个错为什么危险</b>：成本看板会按 `deepseek-flash` 的单价给那些运行算钱，
		 * 而真实单价可能是另一个模型的 —— 于是钱数是错的，却**看起来完全正常**
		 * （有一个具体的模型名、有一笔具体的金额）。
		 * 这正是"成本归因"这一层最需要防的一件事：<b>按"我以为在用的模型"计价。</b>
		 */
		private final ModelTurn override;

		/**
		 * 取模型名。
		 *
		 * <p>有覆盖就用覆盖（那是这次真的在用的），否则每轮问一次容器 ——
		 * 而**不能**在构造期问一次就缓存：模型可能在运行期间被换掉，
		 * 缓存它会让成本归因悄悄落在错的模型上。
		 */
		private String resolveModelName() {
			if (override != null) {
				return override.modelName();
			}
			ModelTurn turn = modelTurnProvider.getIfAvailable();
			return turn == null ? ModelTurn.UNKNOWN_MODEL : turn.modelName();
		}

		PersistingListener(String runId) {
			this(runId, null);
		}

		PersistingListener(String runId, ModelTurn override) {
			this.runId = runId;
			this.override = override;
		}

		@Override
		public void afterStep(String runId, int stepIndex, AgentRun.Step step, List<Message> messages,
				int totalTokensSoFar, int toolCallsSoFar, long elapsedMsSoFar, String promptFingerprint,
				String canaryVariant) {
			// 先写工具调用明细，再写检查点。
			// 顺序有讲究：检查点是"这一步完成了"的凭据，如果先写它、
			// 后写明细时失败，恢复时就会看到一个"完成"的步骤却缺了工具记录 ——
			// 而那是无法从检查点本身发现的（检查点里只有对话，没有 stage 分类）
			//
			// 【callIndex 的记法：只对"有结论的"那一次自增】
			// 这一点很讲究，改之前先看完。`step.outcomes()` 可能**短于**
			// `step.invocations()`：循环在"重复动作"与"停在人工闸门"两处会提前 break，
			// 那时后面的意图压根没有结论。
			// 所以 outcomes 与 invocations 是**同一个前缀**，下标 i 对得上。
			// 判据用 `i < invocations.size()` 而不是"总是取 invocations.get(i)"，
			// 是为了让"万一将来多出一个没有对应意图的结论"退化成丢一个 callId，
			// 而不是抛 IndexOutOfBounds 把整次运行打断
			int callIndex = 0;
			List<ai.agent.entity.tool.ToolInvocation> invocations = step.invocations();
			for (var outcome : step.outcomes()) {
				String callId = callIndex < invocations.size() ? invocations.get(callIndex).id() : null;
				store.recordToolCall(new AgentRunRecord.ToolCallRecord(runId, stepIndex, callIndex, callId,
						outcome.invocation().name(), outcome.invocation().argumentsForFeedback(2000),
						outcome.stage().name(), outcome.feedback(), outcome.elapsedMs()));

				// 【阶段四 trace 的「检索」那一层】
				//
				// 判据是"这次调用有没有带回检索明细"，而不是"工具名是不是 search_docs"：
				//   - 按工具名判 → 将来第二把检索工具出现时必须记得来这里加一个名字，
				//     而忘了的表现是"新工具的检索明细一条都不落库"，且**不报错**；
				//   - 按明细判 → 谁产出明细就落谁的，工具名只是记录里的一个字段。
				// 这正是把"结构化观察"做成类型（AgentToolResult.Observation）而不是
				// 约定（"检索类工具记得调一下 XXX"）的收益。
				//
				// 非检索调用**什么都不写** —— 写一个空明细会让"这次任务检索了几次"
				// 这个数字变大，而它恰恰是这一层最常被问的数
				//
				// 取一个方法局部的副本再进 lambda：`callIndex` 是本循环的可变变量，
				// 而 lambda 只能捕获有效终结（effectively final）的变量。
				// 写成 `final int index = callIndex` 同时表达了一件事：
				// **这一次落库用的就是当下这个下标**，它不随循环继续而变
				final int index = callIndex;
				outcome.retrievalTrace().ifPresent(trace -> store.recordRetrieval(
						new AgentRunRecord.RetrievalRecord(runId, stepIndex, index,
								outcome.invocation().name(), trace)));
				callIndex++;
			}
			// 缓存命中、模型名、提示词指纹与灰度分组一起落库：它们是 trace 与成本看板的四个输入口径。
			// 少了缓存命中，成本只能按最贵那一档算（偏高，且高估倍数随上游命中率浮动）；
			// 少了模型名，"换了模型之后钱花在哪"没有数据可答；
			// 少了提示词指纹，"答案变差了是不是因为换了提示词"没有数据可答；
			// 少了灰度分组，"新版到底比旧版好还是差"没有数据可答 ——
			// 而它是**唯一**能事后区分两组的东西（用指纹反推在推全之后就失效了）
			store.checkpoint(new AgentRunRecord.StepCheckpoint(runId, stepIndex, codec.encode(messages),
					step.thoughtText(), step.promptTokens(), step.completionTokens(), step.elapsedMs(),
					step.cachedPromptTokens(), resolveModelName(), promptFingerprint, canaryVariant));
			store.updateProgress(runId, totalTokensSoFar, toolCallsSoFar, elapsedMsSoFar);
		}
	}

	/**
	 * 一次恢复的结果。
	 *
	 * <p>三种情况分开表达，而不是用一个可空的 run + 一个布尔：
	 * 「没找到」「找到了但没检查点」「恢复了」对使用者是三件不同的事 ——
	 * 前两种需要人去处理（看为什么没检查点），第三种是正常路径。
	 *
	 * @param runId 运行 id
	 * @param outcome 结果类型
	 * @param resumedFromStep 从第几步之后接着跑（0 = 没有检查点）
	 * @param run 恢复后的运行结果（只有 {@code RESUMED} 时非空）
	 * @param pendingInvocation 被杀时状态不明的那次工具调用（可能为 null）
	 */
	public record ResumeResult(String runId, Outcome outcome, int resumedFromStep, AgentRun run,
			ToolInvocation pendingInvocation) {

		public enum Outcome {
			/** 从检查点接着跑完。 */
			RESUMED,
			/** 库里没有这次运行。 */
			NOT_FOUND,
			/** 有运行但没有可用检查点（第 1 步都没跑完）。 */
			NO_CHECKPOINT
		}

		static ResumeResult resumed(String runId, int fromStep, AgentRun run, ToolInvocation pending) {
			return new ResumeResult(runId, Outcome.RESUMED, fromStep, run, pending);
		}

		static ResumeResult notFound(String runId) {
			return new ResumeResult(runId, Outcome.NOT_FOUND, 0, null, null);
		}

		static ResumeResult noCheckpoint(String runId) {
			return new ResumeResult(runId, Outcome.NO_CHECKPOINT, 0, null, null);
		}

		public boolean resumed() {
			return outcome == Outcome.RESUMED;
		}
	}

}
