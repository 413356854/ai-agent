package ai.agent.alert;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * <b>「什么算异常」的唯一一份判据</b>（阶段四第 5 件：灰度发布 + 异常告警）。
 *
 * <h2>为什么判据必须是枚举 + 一个方法，而不是散在各处的 if</h2>
 * 这一件要回答的问题在<b>两个时间点</b>上各问一次，而两次问的是同一句话：
 * <ul>
 *   <li><b>上线前</b>（构建阶段）：如果这样放上去，会不会已经坏了？—— 门禁，会红，挡住建；</li>
 *   <li><b>上线后</b>（运行阶段）：现在这样跑着，有没有坏掉？—— 告警，不挡任何东西，只报出来。</li>
 * </ul>
 * 台账里反复记过一条纪律：「<b>判据只能有一份</b>」。如果门禁那边写一套阈值、
 * 告警这边再写一套，两者必然漂移 —— 而漂移的表现是最坏的一种：
 * <b>门禁说没事、告警在响</b>（或者反过来），于是人开始两边都不信。
 * 所以阈值、严重度、处置说明三样东西全部写在这个枚举里
 * （{@link #judge} 是唯一的判定入口，{@link #action} 是唯一的处置说明）。
 *
 * <h2>★ 为什么"门禁类"与"观察类"是两个阶段，而不是两个严重度</h2>
 * 这是本件最容易做错的一处取舍。直觉会说"严重的挡门禁、不严重的只告警"，
 * 但那是错的，理由很具体：
 * <ul>
 *   <li><b>构建阶段只值得对"可以只靠构建环境判定"的事下结论</b>。
 *       离线评测基线漂移是一条门禁 —— 因为它需要的输入（固定语料、固定评测集、
 *       本地 BM25）在构建机上全都有，结果完全确定；</li>
 *   <li>而"价格没配"这类判断<b>在构建机上永远是「未配置」</b>（那是个干净的 CI 环境）。
 *       把它做成门禁，得到的是一条<b>默认就会红、且红的原因与代码无关</b>的门禁 ——
 *       台账里已经写过：那种门禁比没有门禁更糟，因为它教会人忽略红色。</li>
 * </ul>
 * 所以这里的分类标准不是"有多严重"，而是「<b>这个判断在构建阶段能不能得出结论</b>」。
 * 于是 {@link AlertPhase#GATE} 只出现在构建阶段，{@link AlertPhase#OBSERVE}
 * 只出现在运行阶段 —— 两个阶段各自只读自己那一半，谁也不会替对方下结论。
 *
 * <h2>为什么"违反了"返回的是 {@link Verdict} 而不是一个布尔</h2>
 * 一条告警说"出问题了"是不够用的：看到它的人第一个问题是
 * "<b>差多少、跟什么比</b>"。所以判定结果里必须同时带着
 * <b>观测值、判据阈值、以及为什么是这个阈值</b> —— 少了任何一样，
 * 收到告警的人就得回到代码里重新推一遍，而那时已经来不及了。
 * 这与成本看板那条"算不出来要显示成「算不出」而不是 ¥0.00"是同一条纪律：
 * <b>一个数字必须带着它的口径一起交付</b>。
 *
 * <h2>⚠️ 阈值写死在判据里，而不是做成配置项（这是刻意的）</h2>
 * 一个<b>能被调低</b>的阈值，会在"告警响了但没人管"的时候被顺手调低 ——
 * 而那一刻它就不再是判据了，它变成了"让页面变绿"的开关。
 * 改判据要走代码改动 + 测试，那才是判据该有的成本。
 *
 * <p>它们同时以公开常量暴露（{@link #CONSECUTIVE_EMPTY_RETRIEVALS} 等），
 * 供界面、文档与测试引用。⚠️ <b>那三个常量是"抄"在判据里的数字</b>，
 * 不是判据读的那一份 —— 原因见 {@link #CONSECUTIVE_EMPTY_RETRIEVALS} 上的注释。
 * 有一条测试专门盯着"抄的那一份与公开的那一份一致"：
 * 一个抄错一位的阈值不会有任何症状，只会让某条判据在该响的时候不响。
 */
public enum AlertRule {

	// ==================================================================
	//  一、成本口径（来源：成本看板的 notes —— 这一族在第十轮就已经是"人读的告警"了）
	//
	//  ★ 阶段四第 6 件（成本归因）对这一族做了一次**主体粒度**的重新决定：
	//    在租户维度出现之前，"这一族判据说的是谁"这个问题只有一个答案（窗口合计）。
	//    有了租户之后，同一句话可以有两种问法，而它们**不是"哪个更细"的问题**：
	//      · 「取数/配置本身对不对」是**全局**的 —— 价格表配没配、两个口径对不对得上，
	//        这些与有几个租户无关。按租户拆开问，只会得到 N 条一模一样的话
	//        （而"同一件事说 N 遍"正是让告警列表变成噪声的最快方式）；
	//      · 「花钱的形态有没有异常」是**每租户**的 —— 缓存一次都没命中、
	//        某一家占掉了绝大部分花费，这些只有在租户粒度上才有可处置的动作。
	//    所以粒度不是一个全局开关，而是**逐条判据**的属性 ——
	//    也就是 AlertEvaluator.judge 里那处显式的 switch。
	// ==================================================================

	/**
	 * 价格没配。
	 *
	 * <p>它<b>不是</b>故障，而是"这一页有一个待办"。严重度是 {@link Severity#INFO}
	 * 而不是 WARNING，理由很具体：把它标成警告，等于让一个"还没填价格"的新部署
	 * 一直顶着一个红点 —— 而红点一旦长期为红，它就不再提供信息了。
	 * <b>它要的是有人去填，不是有人被叫醒。</b>
	 *
	 * <p>★ <b>主体粒度：全局</b>（第 6 件重新确认过）。价格表是一个部署一份的配置，
	 * 与有几个租户无关 —— 按租户拆开问会得到 N 条一模一样的话，
	 * 而"同一件事说 N 遍"会让人开始不看告警。
	 */
	UNCONFIGURED_PRICES("unconfigured-prices", "价格未配置（成本只能算到 token，算不出金额）",
			Severity.INFO, AlertPhase.OBSERVE,
			"在 application.properties 里按模型补 ai.cost.prices（写法见 GET /api/cost/prices）。"
					+ "⚠️ 不要为了「有个数字」随便填一个价：成本表是最容易被直接引用的一张表，"
					+ "一个按错价算出来的金额比一个明确的「算不出」危险得多",
			snapshot -> {
				if (snapshot.pricingConfigured()) {
					return Verdict.silent();
				}
				return Verdict.of("ai.cost.prices 是空的", "至少要有一条价格规则（ai.cost.prices[0].model=...）",
						"金额会显示成「算不出金额」；token 与运行数不受影响");
			}),

	/**
	 * 有模型没有价格。
	 *
	 * <p>与上一条的区别值得单列：上一条是"整张价格表是空的"，
	 * 这一条是"表里有规则，但窗口内这个模型没匹配上"。后者的表现更隐蔽 ——
	 * 看板上多数模型都有钱数，只有一格写着「算不出」，
	 * 而合计被悄悄标成了下界。两者的修法也不同（配整张表 vs 补一条规则）。
	 *
	 * <p>★ <b>主体粒度：全局，但主体的取值是"模型"</b>（第 6 件重新确认过）。
	 * 它看起来像"按租户"，其实不是：单价是按<b>模型</b>配的，与谁在用无关。
	 * 按租户拆开问会得到"甲租户的模型 X 没价"这种**无法处置**的告警 ——
	 * 因为处置动作（补一条 ai.cost.prices）是全局的、补一次就够。
	 */
	UNPRICED_MODELS("unpriced-models", "窗口内出现了没有价格规则的模型（合计只是下界）",
			Severity.WARNING, AlertPhase.OBSERVE,
			"按告警明细里的模型名各补一条 ai.cost.prices 规则。"
					+ "⚠️ 上游没上报模型名的那种（列表里显示成「未上报模型名」）"
					+ "不能靠补价格解决 —— 它是采集侧的问题，补价会得到一个按别的模型定价的假数字",
			snapshot -> {
				if (snapshot.unpricedModels().isEmpty()) {
					return Verdict.silent();
				}
				return Verdict.of(String.join("、", snapshot.unpricedModels()),
						"每一个出现在窗口内的模型都要有匹配的价格规则",
						"这些模型的 token 计入了总量但不在金额里，所以合计是下界");
			}),

	/**
	 * 两个口径对不上（步骤表 vs 运行行）。
	 *
	 * <p>这是成本看板的"仪表自检"。它红了不代表钱一定错了，
	 * 但代表<b>有一处漏记</b> —— 而漏记在看板上完全看不出来（只是钱少一点或多一点），
	 * 所以它必须由一条会响的判据来兜。
	 */
	RECONCILE_GAP("reconcile-gap", "两个 token 口径对不上（按步骤汇总 ≠ 运行行累计）",
			Severity.WARNING, AlertPhase.OBSERVE,
			"看钱的算法用的是步骤表，而运行列表与 token 上限用的是运行行。"
					+ "先看 runsWithoutSteps —— 「有运行行、一个步骤都没有」是最可疑的一类。"
					+ "⚠️ 对不上不代表钱错了，但代表有一处需要解释",
			snapshot -> {
				if (snapshot.reconcileGap() == 0) {
					return Verdict.silent();
				}
				return Verdict.of(String.valueOf(snapshot.reconcileGap()),
						"0（两个口径必须逐 token 相等）",
						"差值 = 步骤口径 " + snapshot.stepLevelTokens() + " − 运行口径 "
								+ snapshot.runLevelTokens() + "；其中有 " + snapshot.runsWithoutSteps()
								+ " 次运行一个步骤都没有");
			}),

	/**
	 * 某个租户的整窗缓存命中率为 0（<b>第 6 件从"整窗"改成了"每租户"</b>）。
	 *
	 * <p><b>这条判据的措辞比它的阈值重要。</b>命中率 0 有两种成因，
	 * 而数据本身分不出来：（a）上游确实没命中；（b）上游压根不返回缓存字段。
	 * （b）的后果是成本被按最贵那一档算了（缓存价与未命中价差一个数量级），
	 * 而页面上看起来只是"缓存没起作用"。
	 * 所以这里刻意<b>不下"上游不支持"这个结论</b>，只把两种可能一起交出去，
	 * 并给出唯一能分辨的动作（拿真 key 看一次原始 usage）。
	 *
	 * <h2>★ 为什么主体粒度从"整窗"改成了"每个租户"（第 6 件的一处真实修正）</h2>
	 * 在租户维度出现之前，这句话只能问整窗。出现之后，**整窗口径会掩盖真正的信号**：
	 * 窗口里只要有一个租户命中过缓存，合计就不再是 0 ——
	 * 于是"某个租户一次都没命中"这个真正有动作可做的事实（它的提示词前缀可能每次都在变、
	 * 或者它走的是另一条链路）永远看不见。
	 *
	 * <p>⚠️ 而<b>未标注租户的运行不进这个口径</b>：它们不是一个主体，
	 * 而是一堆互不相干的运行的合计。把那个合计当成"一个租户"去报，
	 * 会让告警指向一个不存在的东西（"未标注租户 的缓存命中率为 0"—— 收到的人没法处置）。
	 * 它们仍由 {@link #EMPTY_RETRIEVAL} 之外的窗口口径覆盖：窗口合计为 0 时，
	 * 说明整个部署都没有缓存命中（那才是"上游不返回这个字段"的形状）。
	 */
	ZERO_CACHE_HITS("zero-cache-hits", "某租户的整窗提示词缓存命中率为 0（有两种成因，数据分不出来）",
			Severity.WARNING, AlertPhase.OBSERVE,
			"成因（a）上游确实没命中：去看这个租户的提示词前缀是否稳定。"
					+ "成因（b）上游不支持/不返回缓存字段：那么它的成本一直被按最贵的那一档算。"
					+ "★ 分辨方法只有一个 —— 拿真 key 跑一次，直接看响应里的原始 usage 有没有缓存字段。"
					+ "⚠️ 未标注租户的运行不在这一条的覆盖范围里（它们不是一个主体）",
			snapshot -> {
				// 【窗口合计为 0 时仍然要看】那时 tenantCaches 可能整个是空的
				// （一个租户都没有），所以这条判据要能同时回答两种情况。
				//
				// 判据的顺序刻意是"合计有没有 token"在前：窗口内连 token 都没有时，
				// 命中率无从谈起 —— 那时报"0 命中"是一条不可处置的噪声
				if (snapshot.promptTokens() <= 0) {
					return Verdict.silent();
				}
				// 主体由 AlertEvaluator 展开成"每租户一条"，这里判的是**某一个租户**：
				// 传入的快照是逐租户切出来的（见 AlertEvaluator.judge 的 tenantScope）。
				// 这样"判据只有一份"仍然成立 —— 逐租户的循环不在判据里，
				// 所以它不会被抄成第二份阈值。
				if (snapshot.cachedPromptTokens() != 0) {
					return Verdict.silent();
				}
				return Verdict.of(snapshot.cachedPromptTokens() + " / " + snapshot.promptTokens(),
						"有输入 token 时，命中量不该恰好为 0",
						"命中缓存的价格便宜一个数量级；为 0 意味着成本一直按最贵的那一档在算。"
								// ⚠️ 这段文本会**原样渲染到页面的纯文本区**（告警明细的「为什么」那一格），
								// 所以不许出现 markdown 星号 —— 强调一律用「」。
								// 同一轮里成本看板那条 notes 就是因为写了 `**分摊**` 而被真后端路径抓出来的
								+ "⚠️ 这一条是「按租户」判的：整窗合计不为 0 时也可能有租户一次都没命中 —— "
								+ "那种租户的成本同样被按最贵的一档算了");
			}),

	/**
	 * <b>单个租户占掉了绝大部分花费</b>（第 6 件新增：一条只有在租户粒度上才成立的成本判据）。
	 *
	 * <h2>它回答的是什么问题</h2>
	 * 它不是"某个租户花得太多"（那要预算才好判，而预算得由业务方给 —— 这个项目没有），
	 * 而是「<b>这一批花费几乎全来自一家</b>」。这件事有两种完全不同的含义，
	 * 而两种都值得有人看一眼：
	 * <ul>
	 *   <li>好的一面：<b>它印证了归因本身是有效的</b> —— 如果"按租户"这一维压根没接上，
	 *       所有花费会均匀地落在「未标注租户」那一行，而不是集中在某一家；</li>
	 *   <li>需要查的一面：可能是某一家跑了批处理、进入了重试风暴、或者它的入参特别长。
	 *       而这类事情在**整窗合计**上完全看不出来（合计只告诉你花了多少）。</li>
	 * </ul>
	 *
	 * <h2>★ 为什么"比阈值更重要的是它的边界"</h2>
	 * 这条判据<b>不</b>说明谁该省钱、也<b>不</b>说明别人花得少就是正常的。
	 * 一个正当的大客户会让它一直响 —— 所以它的处置说明里第一句就是"先确认这是不是已知的"，
	 * 而不是"去优化它"。这与 CANARY_SPLIT_BIASED 那条"先确认样本量"同一个形状：
	 * <b>阈值能被算对，用途只能被写清。</b>
	 *
	 * @see #TENANT_CONCENTRATION_THRESHOLD
	 */
	TENANT_COST_CONCENTRATION("tenant-cost-concentration",
			"单个租户占掉了绝大部分 token 花费（先把归因本身当作一个信号读）",
			Severity.INFO, AlertPhase.OBSERVE,
			"先确认这是不是已知的：一个大客户、一次批处理、一段压测都会让它响。"
					+ "★ 它同时是一条「归因自检」—— 花费集中在某一家，说明「按租户」这一维真的接上了；"
					+ "反过来，如果这一条从来不响而归因也该有集中度，值得去看是不是所有运行都落在"
					+ "「未标注租户」那一行（那说明调用方没传 tenantId，而不是没有集中度）。"
					+ "⚠️ 它不作为收紧某个租户预算的依据：预算该由业务方给，从观测数据反推预算是本末倒置。",
			snapshot -> {
				TenantShare share = snapshot.tenantShare();
				if (share == null || share.totalTokens() <= 0) {
					// 【没有数据 ≠ 集中度低】没有租户信息时不响，也不给一个 0。
					// 这一条刻意不把"全都未标注"报出来：那不是成本问题，
					// 而是"归因维度没接上"，它由调用方与看板那一栏的「未标注租户」回答
					return Verdict.silent();
				}
				// 0.90 是 TENANT_CONCENTRATION_THRESHOLD 的字面量副本
				// （原因同 EMPTY_RETRIEVAL 那条：枚举常量的实参不能引用后面的静态字段）
				if (share.share() < 0.90) {
					return Verdict.silent();
				}
				return Verdict.of(round3(share.share()) + "（" + share.tenant() + "）",
						"单个租户不超过全部 token 的 90%",
						"该租户 " + share.tenantTokens() + " / 全部 " + share.totalTokens()
								+ " tokens。一条正当的大客户流量会让它一直响 —— "
								+ "所以它的用途是「这条判据把归因接上了吗」，而不是「该限制谁」。");
			}),

	// ==================================================================
	//  二、检索（来源：第十三轮 trace 的 retrieval_call.hit_count）
	// ==================================================================
	/**
	 * 连续 N 次检索一条都没命中。
	 *
	 * <p>台账点名要监测这个信号，理由写得很清楚：一次 0 命中的检索会让模型
	 * <b>白跑一轮</b>（那一轮的 token 照样花钱），而它混在"检索了 N 次"里完全看不出来。
	 *
	 * <h2>★ 为什么判据是"连续"而不是"累计"</h2>
	 * 这是设计里唯一一处需要解释的阈值形态。两次空命中分散在一百次检索里，
	 * 与两次空命中连续挨着，含义完全不同：
	 * <ul>
	 *   <li>分散 → 那是几个恰好没有答案的问题，正常；</li>
	 *   <li>连续 → 从某一刻开始<b>全都查不到</b>，最可能是索引没建起来、
	 *       或者提问方式变了、或者语料被换掉了。</li>
	 * </ul>
	 * 累计计数会把第一种情况也报出来，而误报会消耗掉这条告警的信用
	 * （台账里那条"一道默认会红的门禁比没有门禁更糟"说的是同一件事）。
	 *
	 * <p>⚠️ 阈值 3 在下面那个 lambda 里是<b>字面量</b>，而公开常量
	 * {@link #CONSECUTIVE_EMPTY_RETRIEVALS} 也写着 3 —— 为什么不能只写一份，
	 * 见那个常量上的注释。
	 */
	EMPTY_RETRIEVAL("empty-retrieval", "连续多次检索一条都没命中（模型白跑一轮，那一轮照样花钱）",
			Severity.WARNING, AlertPhase.OBSERVE,
			"看检索明细里的 query 与 indexSize：indexSize=0 是索引没建起来（部署问题），"
					+ "query 明显跑题是提问侧的问题。"
					+ "⚠️ 单次 0 命中是正常结果，不是故障 —— 这条判据盯的是连续",
			snapshot -> {
				int streak = snapshot.consecutiveEmptyRetrievals();
				// 【这里的 3 与 CONSECUTIVE_EMPTY_RETRIEVALS 是同一个数，但必须写字面量】
				// 枚举常量的构造实参不能引用它后面声明的静态字段（非法前向引用），
				// 而枚举常量又必须声明在最前面 —— 两条夹在一起，"只写一份"做不到。
				// 所以 AlertRuleCatalogTest 用反射把两份对齐：抄错一位会被当场抓住
				if (streak < 3) {
					return Verdict.silent();
				}
				return Verdict.of(streak + " 次连续空命中", "至少 3 次连续（不是累计）",
						"最后一次检索时索引里有 " + snapshot.lastIndexSize() + " 个块");
			}),

	// ==================================================================
	//  三、灰度发布（本件新增：把"分流到底有没有按比例发生"变成一条会响的判据）
	// ==================================================================

	/**
	 * 灰度没分到流量。
	 *
	 * <p>这是灰度发布<b>特有的</b>一类失败，而且它是最坏的一种：
	 * 配置写着"10% 走新版本"，实际一份都没分到 —— 于是
	 * 「新版表现正常」这句话跟没测一样，而观察者会以为测过了。
	 * 它不报错、不影响任何功能，只让一次实验悄悄变成空转。
	 */
	CANARY_NO_TRAFFIC("canary-no-traffic", "灰度开着，但一份流量都没分到（这次实验等于没做）",
			Severity.WARNING, AlertPhase.OBSERVE,
			"两个可能：① 观测窗口里还没有运行（换个更长的窗口或先跑几个任务）；"
					+ "② 分流函数与运行 id 的形态不匹配（例如运行 id 恒为同一个值）。"
					+ "★ 判据用的是「实际分到几份」，不是「配置写了几成」",
			snapshot -> {
				if (!snapshot.canary().active()) {
					return Verdict.silent();
				}
				if (snapshot.canary().participatingRuns() > 0) {
					return Verdict.silent();
				}
				return Verdict.of("0 / 0 份参与运行", "灰度开着时，窗口内至少要有 1 份运行参与分流",
						"配置的重量是 " + snapshot.canary().weightPercent() + "%（v"
								+ snapshot.canary().candidateVersion() + "）；"
								+ "没有参与运行就无法判断它到底有没有分到");
			}),

	/**
	 * 灰度的实际分流与配置比例差太多。
	 *
	 * <p>这条判据回答的是"<b>分流函数真的按比例在分吗</b>"。
	 * 配置 20% 而实测 5%（样本足够时）说明分流函数有问题（例如哈希偏斜、
	 * 或者压根没接上而只是碰巧有几份走了新版）。这与上一条互补：
	 * 上一条管"一份都没有"，这一条管"有，但比例不对"。
	 *
	 * <p>容忍度与最小样本量：<b>样本不足时不下结论</b>（返回 silent），
	 * 因为 3 份里分到 1 份并不说明 30% 的比例 —— 那是小样本噪声，
	 * 而"用噪声报警"是告警系统最常见的死法。
	 */
	CANARY_SPLIT_BIASED("canary-split-biased", "灰度的实际分流与配置比例偏离过大（分流函数可疑）",
			Severity.WARNING, AlertPhase.OBSERVE,
			"先确认样本量（明细里有参与运行数）—— 样本少时这条判据本就不会响。"
					+ "样本足够还偏，去看分流函数的输入：它按运行 id 取稳定哈希，"
					+ "输入不变则分桶不变；如果每次的运行 id 都是同一个值，分流就退化成「全走一边」",
			snapshot -> {
				CanarySplit split = snapshot.canary();
				// 10 与 0.30 是 CANARY_MIN_SAMPLE / CANARY_SPLIT_TOLERANCE 的字面量副本
				// （原因同 EMPTY_RETRIEVAL 那条：枚举常量的实参不能引用后面的静态字段）
				if (!split.active() || split.participatingRuns() < 10) {
					return Verdict.silent();
				}
				double expected = split.weightPercent() / 100.0;
				double observed = (double) split.candidateRuns() / split.participatingRuns();
				double deviation = expected == 0 ? observed : Math.abs(observed - expected) / expected;
				if (deviation <= 0.30) {
					return Verdict.silent();
				}
				return Verdict.of(round3(observed) + "（" + split.candidateRuns() + "/"
						+ split.participatingRuns() + "）",
						"配置 " + split.weightPercent() + "%（允许相对偏离不超过 30%）",
						"样本 " + split.participatingRuns() + " 份（少于 10 份时本判据刻意不响）");
			}),

	// ==================================================================
	//  四、构建阶段（GATE：只有能靠构建环境判定的事才在这里下结论）
	// ==================================================================

	/**
	 * 离线评测基线漂移。
	 *
	 * <p>⚠️ <b>它的实际执行者是 {@code RagRegressionTest}，不是这个类。</b>
	 * 这条规则在这里出现的唯一理由是：让"这个项目里被判断为异常的东西"<b>只有一份清单</b>，
	 * 于是告警面板上能列出"构建阶段有四条门禁"，而不必让人去翻文档。
	 *
	 * <p>为什么它的判定故意返回 {@link Verdict#silent()} 而不是自己算一遍：
	 * 真正那条比较已经由 {@code RagRegressionTest} 做过了，而且做得更严格
	 * （逐位比较 + 采集条件核对 + 打印方向）。在这里再算一遍意味着
	 * <b>同一件事有两份实现</b>，而两份实现迟早会对不上 ——
	 * 那正好是这一整个枚举要消灭的东西。
	 * 所以它标注了自己的执行者（{@link #executedBy}），并明确"不在这里重复判断"。
	 */
	GATE_EVAL_BASELINE_DRIFT("gate-eval-baseline-drift", "离线评测相对基线退化（构建阶段门禁）",
			Severity.BLOCKER, AlertPhase.GATE,
			"先分清属于哪一种，两种处置正好相反："
					+ "① 真退化（你这轮没动语料/分块/检索）→ 去查代码，不要改基线；"
					+ "② 有意的变更（调了参数/改了语料/加了用例）→ 重新采基线，并在 note 里写清为什么动。"
					+ "★ 它不回答「新提示词更好」—— 这条链路 0 次模型调用（见 docs/CI回归门禁.md）",
			snapshot -> Verdict.silent(), "RagRegressionTest#evaluationMatchesBaseline（逐位比较，容差 1e-9）"),

	/**
	 * 评测集本身的采集条件变了。
	 *
	 * <p>换了语料或改了用例数时，指标变化是<b>当然的</b> ——
	 * 而"当然的变化"若被报成退化，会消耗掉对那条断言的信任，最后被人注释掉。
	 * 所以"采集条件必须与基线一致"被单独列成一条门禁：它先响，于是后面的漂移
	 * 不必再被解释。
	 */
	GATE_EVAL_CONTEXT_MISMATCH("gate-eval-context-mismatch", "评测的采集条件与基线不一致（指标变化无从归因）",
			Severity.BLOCKER, AlertPhase.GATE,
			"核对语料、用例数、策略集合三项。"
					+ "⚠️ 条件变了就该重采基线 —— 而不是把指标差异解释成退化",
			snapshot -> Verdict.silent(), "RagRegressionTest#evaluationMatchesBaseline（sameContext 前置断言）"),

	/**
	 * 判据登记不完整（方向没登记 / 登记表过时）。
	 *
	 * <p>这条门禁保护的<b>不是结果，而是判据本身</b>：一个"加了指标却忘了登记方向"
	 * 的改动会让那条指标静默地按默认方向判断 —— 而默认方向错了，
	 * 报出来的就是"改进"而不是"退化"。
	 */
	GATE_METRIC_DIRECTION_UNREGISTERED("gate-metric-direction-unregistered",
			"有指标没有登记方向（会被按默认方向静默判断）", Severity.BLOCKER, AlertPhase.GATE,
			"把新增的指标补进方向登记表。"
					+ "★ 这一类「给判据写测试」的断言比「给结果写测试」更值：结果会变，判据不该变",
			snapshot -> Verdict.silent(), "RagRegressionTest#unregisteredMetricDirectionThrows"),

	/** 门禁入口本身进得了版本控制吗（一个只在 CI 上现形的缺口）。 */
	GATE_ENTRY_NOT_COMMITTABLE("gate-entry-not-committable",
			"门禁脚本自己被 git 忽略（CI 上必然 Cannot find module）", Severity.BLOCKER, AlertPhase.GATE,
			"在 .gitignore 里让 scripts/ci.mjs 被重新包含。"
					+ "⚠️ 忽略目录时反向规则无效：要写 scripts/* + !scripts/ci.mjs，写 scripts/ 是无效的",
			snapshot -> Verdict.silent(), "scripts/ci.mjs#verifyGateIsCommittable");

	// ==================================================================
	//  公开的阈值（给界面、文档与测试读）
	// ==================================================================

	/**
	 * 连续空命中的次数上限：达到它就报。
	 *
	 * <h2>⚠️ 这个常量是"抄"的，不是判据读的那一份 —— 这是刻意的，原因必须说清</h2>
	 * 判据 lambda 里写的是字面量 {@code 3}，而这个常量也写着 3。
	 * 为什么不能只写一份：
	 * <ul>
	 *   <li>枚举常量必须声明在枚举体的<b>最前面</b>（任何字段之前）；</li>
	 *   <li>而枚举常量的构造实参<b>不能</b>引用它后面声明的静态字段（javac：非法前向引用）。</li>
	 * </ul>
	 * 两条夹在一起，"只写一份"在枚举里做不到。第一版试过写成普通的静态字段，
	 * javac 报的是 {@code if (streak < CONSECUTIVE_EMPTY_RETRIEVALS)} 那一行的
	 * {@code <} 非法 —— 错误位置指向比较运算符，完全不会让人想到是声明顺序的问题。
	 *
	 * <p>所以这里换成"抄一份 + 一条测试盯着两份一致"
	 * （{@code AlertRuleCatalogTest} 用反射读判据里的字面量与这些常量比对）。
	 * 代价说清：<b>抄错一位不会有任何症状</b>，只会让某条判据在该响的时候不响。
	 *
	 * <p>取 3 的理由是"两次可能是巧合、三次是状态"。取 1 会把每一次
	 * "这个问题恰好没有答案"都报出来（那是正常结果，不是故障）；
	 * 取 5 会让"索引挂了"这件事在第五次检索之后才被发现 —— 而那五次
	 * 模型白跑的轮次照样花了钱。
	 */
	public static final int CONSECUTIVE_EMPTY_RETRIEVALS = 3;

	/**
	 * 灰度分流的允许相对偏离（判据里的字面量副本，见
	 * {@link #CONSECUTIVE_EMPTY_RETRIEVALS} 上那段说明）。
	 *
	 * <p>取 0.30：分流是按稳定哈希分的，样本足够时不该偏过三成。
	 */
	public static final double CANARY_SPLIT_TOLERANCE = 0.30;

	/**
	 * 灰度分流的<b>最小样本量</b>：少于它一律不下结论（判据里的字面量副本）。
	 *
	 * <p>这一条与阈值同等重要。10% 的重量在 5 份样本里"一份都没分到"的概率高达 59%，
	 * 若允许小样本判定，这条告警会在每次刚开灰度时<b>必然误报一次</b> ——
	 * 而误报三次之后，人就再也不看它了。
	 */
	public static final int CANARY_MIN_SAMPLE = 10;

	/**
	 * 单个租户占全部 token 的占比上限：达到它就报（判据里的字面量副本，
	 * 见 {@link #CONSECUTIVE_EMPTY_RETRIEVALS} 上那段说明）。
	 *
	 * <p>取 <b>0.90</b> 而不是 0.5 那种"看起来更敏感"的数：这条判据的用途
	 * （见 {@link #TENANT_COST_CONCENTRATION}）是"归因有没有接上 + 有没有一家在异常地烧钱"，
	 * 而不是"分布是否均衡"。两个租户各占一半是**完全正常**的；
	 * 阈值取 0.5 会让任何两家客户的部署一上线就顶着一条告警 ——
	 * 而"默认就会响的告警"与"默认就会红的门禁"一样，会教会人忽略红色。
	 *
	 * <p>取 0.90 的代价同样写清：真正需要早发现的那类情况
	 * （一个租户从 30% 涨到 60%）这条判据**不会**响。那是"趋势"问题，
	 * 需要一个历史基线才能判 —— 而这个项目没有基线，所以刻意不做。
	 */
	public static final double TENANT_CONCENTRATION_THRESHOLD = 0.90;

	private final String code;

	private final String title;

	private final Severity severity;

	private final AlertPhase phase;

	private final String action;

	private final java.util.function.Function<RuntimeSnapshot, Verdict> judgement;

	/** 这一条判据真正的执行者（构建阶段的门禁不在本类里跑，这里如实写清是谁在跑）。 */
	private final String executedBy;

	AlertRule(String code, String title, Severity severity, AlertPhase phase, String action,
			java.util.function.Function<RuntimeSnapshot, Verdict> judgement) {
		this(code, title, severity, phase, action, judgement, "AlertEvaluator（运行阶段，每次评估跑一遍）");
	}

	AlertRule(String code, String title, Severity severity, AlertPhase phase, String action,
			java.util.function.Function<RuntimeSnapshot, Verdict> judgement, String executedBy) {
		this.code = code;
		this.title = title;
		this.severity = severity;
		this.phase = phase;
		this.action = action;
		this.judgement = judgement;
		this.executedBy = executedBy;
	}

	/** 稳定的判据代号（落库、去重、确认都用它；**改标题不许改它**）。 */
	public String code() {
		return code;
	}

	public String title() {
		return title;
	}

	public Severity severity() {
		return severity;
	}

	public AlertPhase phase() {
		return phase;
	}

	/** 看到这条告警该做什么 —— 一句话，能照着做。 */
	public String action() {
		return action;
	}

	/** 谁是这条判据真正的执行者（构建阶段那几条不在本类里跑）。 */
	public String executedBy() {
		return executedBy;
	}

	/** 是不是构建阶段的门禁（只有构建阶段能得出这种结论）。 */
	public boolean isGate() {
		return phase == AlertPhase.GATE;
	}

	/**
	 * <b>唯一的判定入口</b>。
	 *
	 * @return 违反了就返回带数字的结论；没违反返回 {@link Verdict#silent()}
	 */
	public Verdict judge(RuntimeSnapshot snapshot) {
		return judgement.apply(snapshot);
	}

	/** 按代号找判据（不认识的返回空 —— 端点要能区分"代号写错"与"判据没了"）。 */
	public static Optional<AlertRule> byCode(String code) {
		if (code == null) {
			return Optional.empty();
		}
		String trimmed = code.trim();
		for (AlertRule rule : values()) {
			if (rule.code.equals(trimmed)) {
				return Optional.of(rule);
			}
		}
		return Optional.empty();
	}

	/** 运行阶段的判据（会真的参与评估的那些）。 */
	public static List<AlertRule> observed() {
		return byPhase(AlertPhase.OBSERVE);
	}

	/** 构建阶段的判据（门禁；在本类里只作登记，实际执行者是别的类）。 */
	public static List<AlertRule> gates() {
		return byPhase(AlertPhase.GATE);
	}

	private static List<AlertRule> byPhase(AlertPhase phase) {
		List<AlertRule> list = new ArrayList<>();
		for (AlertRule rule : values()) {
			if (rule.phase == phase) {
				list.add(rule);
			}
		}
		return List.copyOf(list);
	}

	private static double round3(double value) {
		return Math.round(value * 1000.0) / 1000.0;
	}

	// ==================================================================
	//  配套类型
	// ==================================================================

	/** 判据在哪个时间点被回答。 */
	public enum AlertPhase {

		/** 构建阶段：门禁。会红、挡住建。只有"构建环境足以判定"的事才该在这里。 */
		GATE("构建阶段（门禁）", "上线前挡：它红了就说明这次改动不该合进去"),

		/** 运行阶段：告警。只报出来，不挡任何东西。 */
		OBSERVE("运行阶段（告警）", "上线后报：它响了说明已经在跑的这一版有问题");

		private final String label;

		private final String meaning;

		AlertPhase(String label, String meaning) {
			this.label = label;
			this.meaning = meaning;
		}

		public String label() {
			return label;
		}

		public String meaning() {
			return meaning;
		}

	}

	/**
	 * 严重度。
	 *
	 * <h2>为什么 INFO 也算一条会响的告警</h2>
	 * 因为"价格没配"这件事<b>必须被看见，但不该把人叫醒</b>。
	 * 如果只允许 WARNING 以上存在，它就会被写成一句注释 —— 而注释不会被任何人读第二遍。
	 */
	public enum Severity {

		/** 建议：有件事该做，但现在这样跑也没错。 */
		INFO("建议", "排进待办就行，不影响服务"),

		/** 警告：功能在跑，但有一个口径/数据不可信了。 */
		WARNING("警告", "需要有人看一眼，通常不紧急"),

		/** 阻断：这条会在构建阶段让 CI 红。 */
		BLOCKER("阻断", "门禁类，红在构建阶段");

		private final String label;

		private final String meaning;

		Severity(String label, String meaning) {
			this.label = label;
			this.meaning = meaning;
		}

		public String label() {
			return label;
		}

		public String meaning() {
			return meaning;
		}

	}

	/**
	 * 一次判定的结论。
	 *
	 * @param breached 是否违反了判据
	 * @param observed 观测到的值（人能读，例如 {@code "0.0500（3/60）"}）
	 * @param threshold 判据阈值与它的形态（例如 {@code "≥ 3 次连续"}）
	 * @param why 为什么这个阈值是这个数（**这一格是给三个月后的人看的**）
	 */
	public record Verdict(boolean breached, String observed, String threshold, String why) {

		private static final Verdict SILENT = new Verdict(false, "", "", "");

		/** 没违反。 */
		public static Verdict silent() {
			return SILENT;
		}

		/**
		 * 违反了，带上"差多少、跟什么比"。
		 *
		 * <p>这里刻意<b>不校验参数非空</b>：一条措辞不全的告警仍然应该被报出来
		 * （少一句话比少一条告警轻得多）。但那三格是这一件质量的全部所在，
		 * 所以 {@code AlertRuleCatalogTest} 用合成快照把每一条运行阶段的判据
		 * 都逼响一次，断言三格都不为空 —— <b>把校验放在测试里，而不是放在运行路径上</b>。
		 */
		public static Verdict of(String observed, String threshold, String why) {
			return new Verdict(true, observed, threshold, why);
		}

	}

	/**
	 * 一次灰度分流的观测结果（判据的输入之一）。
	 *
	 * @param active 当前有没有开着的灰度
	 * @param candidateVersion 候选版本号（没开时为 0）
	 * @param weightPercent 配置的重量（%）
	 * @param participatingRuns 窗口内**参与了分流**的运行数（即灰度期间新起的运行）
	 * @param candidateRuns 其中真的用了候选版本的那些
	 */
	public record CanarySplit(boolean active, int candidateVersion, int weightPercent, int participatingRuns,
			int candidateRuns) {

		/** 没有灰度时的空值（用它而不是 null：判据里少一处判空就少一个坑）。 */
		public static CanarySplit none() {
			return new CanarySplit(false, 0, 0, 0, 0);
		}

	}

	/**
	 * 单个租户在窗口内的花费占比（{@link #TENANT_COST_CONCENTRATION} 的输入）。
	 *
	 * <h2>为什么"未标注租户"不进这个口径</h2>
	 * 判据要报的是「<b>某一家</b>占掉了绝大部分」，而「未标注」不是一家 ——
	 * 它是一堆互不相干的运行的合计。把它的占比报出来，会得到一条
	 * "未标注租户占了 95%"的告警：那个数字是对的，但它**没有处置动作**
	 * （要去补的是调用方的 tenantId，而不是"限制未标注租户"）。
	 * 那种告警会消耗掉这条判据的信用。
	 *
	 * @param tenant 占比最高的那个租户（一定非空、非"未标注"）
	 * @param tenantTokens 它的 token 数
	 * @param totalTokens 全部**已标注租户**的 token 数（分母刻意不含未标注的那些）
	 */
	public record TenantShare(String tenant, long tenantTokens, long totalTokens) {

		/** 占比（0~1）。分母为 0 时返回 0 —— 调用方要先判"有没有数据"。 */
		public double share() {
			return totalTokens <= 0 ? 0.0 : (double) tenantTokens / totalTokens;
		}

	}

}
