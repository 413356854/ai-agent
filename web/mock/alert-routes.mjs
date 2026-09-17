/**
 * 告警 + 灰度端点的**假数据**（阶段四 · 工程化第 5 件）。
 *
 * ---------------------------------------------------------------------------
 * 与 cost-routes / prompt-routes 同一条规矩：**形状是真的，状态机是真的，数字是假的**
 * ---------------------------------------------------------------------------
 * 这一组里有两类端点，性质完全不同，所以在这个文件里也分成两半：
 *
 *   【一半是纯读】`GET /api/alerts`、`GET /api/alerts/rules`、`GET /api/alerts/sweep`
 *   它们是快照，每次返回同一份形状。
 *
 *   【另一半是状态机】`POST /api/alerts/{fp}/ack` 与 `/api/canary/start|stop`
 *   界面自检要验的正是"点一下之后状态真的变了"，所以状态必须**进程内可变**。
 *   而可变状态必须配一条复位入口（`POST /api/alerts/__reset`），
 *   否则自检脚本跑第二遍时会继承第一遍改出来的状态 ——
 *   这与 prompt-routes 的 `__reset`、HITL 的 `__set-approval` 是同一条经验，
 *   而且第十二轮已经实测踩过：**"两条路径都全过"是有前提的**。
 *
 * ---------------------------------------------------------------------------
 * 假数据刻意**不是一份"万事如意"的数据**
 * ---------------------------------------------------------------------------
 * 事故分支必须在假后端上也能走到，否则自检里那些断言等于没验证：
 *   1. **一条刚刚响起的**（未处理，没有人看过）→ 界面必须显示"未处理"并能点确认；
 *   2. **一条已经确认过的**（ACKNOWLEDGED）→ 两个状态在界面上必须长得不一样，
 *      否则"还有几条没人看过"这个数字就是假的；
 *   3. **一条已经恢复的**（RESOLVED）→ 它必须留在列表里（带恢复时间），
 *      而不是消失 —— "它到底响过没有"是复盘的第一句话；
 *   4. **判据目录里构建阶段那一段现在是空的**（GATE 下一个成员都没有）→
 *      界面必须**明说"当前没有判据"**，而不是渲染一个空表头，
 *      并且仍然要留着"这一页不评估构建阶段"这层意思 ——
 *      一个空表头读起来像"构建阶段没问题"，而真相是"这里没有构建阶段的结论可显示"；
 *   5. **灰度：已参与 14 份、其中 3 份走候选版**（配置 20%）→
 *      刻意让"实际分到几份"与"配置写了几成"**不相等且能对上**，
 *      这样界面上那两栏并排显示才有东西可看；
 *   6. **版本列表里有一个 forkedFrom 非空的版本**（回滚出来的）→
 *      灰度选候选版本时那一行要能看出它是复制品。
 */

/** 相对现在偏移 n 分钟，返回 ISO 串 */
function minutesAgo(n) {
  return new Date(Date.now() - n * 60_000).toISOString()
}

// ---------------------------------------------------------------------------
//  告警状态（进程内可变 —— 见文件头注释）
// ---------------------------------------------------------------------------

/**
 * 初始告警清单。
 *
 * ⚠️ `fingerprint` 必须与真后端**同一种拼法**：`ruleCode + '|' + subject`。
 * 写错的话，"确认"这条路径在假后端上会指到一条不存在的行 ——
 * 而那种失败看起来像"确认按钮坏了"。
 */
function initialEvents() {
  return [
    {
      fingerprint: 'unconfigured-prices|价格表',
      ruleCode: 'unconfigured-prices',
      subject: '价格表',
      title: '价格未配置（成本只能算到 token，算不出金额）',
      severity: 'INFO',
      severityLabel: '建议',
      severityMeaning: '排进待办就行，不影响服务',
      phase: 'OBSERVE',
      state: 'OPEN',
      stateLabel: '未处理',
      firstSeenAt: minutesAgo(360),
      lastSeenAt: minutesAgo(3),
      resolvedAt: '',
      acknowledgedAt: '',
      acknowledgedBy: null,
      occurrences: 121,
      firingSeconds: 360 * 60,
      observed: 'ai.cost.prices 是空的',
      threshold: '至少要有一条价格规则（ai.cost.prices[0].model=...）',
      why: '金额会显示成「算不出金额」；token 与运行数不受影响',
      action:
        '在 application.properties 里按模型补 ai.cost.prices（写法见 GET /api/cost/prices）。' +
        '⚠️ 不要为了「有个数字」随便填一个价：成本表是最容易被直接引用的一张表，' +
        '一个按错价算出来的金额比一个明确的「算不出」危险得多',
      window: '2026-09-12 ~ 2026-09-18',
      needsAttention: true,
    },
    {
      fingerprint: 'reconcile-gap|窗口合计',
      ruleCode: 'reconcile-gap',
      subject: '窗口合计',
      title: '两个 token 口径对不上（按步骤汇总 ≠ 运行行累计）',
      severity: 'WARNING',
      severityLabel: '警告',
      severityMeaning: '需要有人看一眼，通常不紧急',
      phase: 'OBSERVE',
      state: 'ACKNOWLEDGED',
      stateLabel: '已确认',
      firstSeenAt: minutesAgo(200),
      lastSeenAt: minutesAgo(3),
      resolvedAt: '',
      acknowledgedAt: minutesAgo(90),
      acknowledgedBy: '值班-张三',
      occurrences: 67,
      firingSeconds: 200 * 60,
      observed: '-418',
      threshold: '0（两个口径必须逐 token 相等）',
      why: '差值 = 步骤口径 128400 − 运行口径 128818；其中有 2 次运行一个步骤都没有',
      action:
        '看钱的算法用的是步骤表，而运行列表与 token 上限用的是运行行。' +
        '先看 runsWithoutSteps —— 「有运行行、一个步骤都没有」是最可疑的一类。' +
        '⚠️ 对不上不代表钱错了，但代表有一处需要解释',
      window: '2026-09-12 ~ 2026-09-18',
      needsAttention: false,
    },
    {
      /*
        【第 6 件新增的一条：主体是**租户名**，不是「窗口合计」】

        它是这一页上唯一一条"主体是一个业务实体"的告警，而**主体那一格正是这一件的产物** ——
        改为逐租户判之后，告警的指纹与主体都变成"哪一家"。
        假数据里必须有这么一条，否则"主体是租户名"这个形状在自检里走不到
        （其它几条的主体都是"窗口合计/价格表/检索"这类固定词）。

        ⚠️ 它的处置说明刻意以「先确认这是不是已知的」开头 —— 一个正当的大客户会让它一直响，
        而一条写着"去优化它"的说明会把人引到错误的方向上。
      */
      fingerprint: 'tenant-cost-concentration|示例租户',
      ruleCode: 'tenant-cost-concentration',
      subject: '示例租户',
      title: '单个租户占掉了绝大部分 token 花费（先把归因本身当作一个信号读）',
      severity: 'INFO',
      severityLabel: '建议',
      severityMeaning: '排进待办就行，不影响服务',
      phase: 'OBSERVE',
      firstSeenAt: minutesAgo(150),
      lastSeenAt: minutesAgo(3),
      resolvedAt: '',
      /*
        ⚠️ 状态刻意是「已确认」而不是「未处理」—— 这是一个**测试契约**，不是随手选的：

        下面那条"点确认之后未处理归零"的断言（`[8.1]` 那一节）隐含假设
        「初始 OPEN 的告警只有一条」。第一版把这条新告警写成 OPEN，
        于是确认掉一条之后还剩一条未处理，那条断言红了 —— 而失败信息看起来像
        "确认没有生效"（界面自己造乐观状态），真因却是**假数据多了一条未处理**。
        这也顺带是一次形态正确的数据：一条已经响了一百多次的告警，
        本来就该是"有人看过了、暂时不管"的样子。
      */
      state: 'ACKNOWLEDGED',
      stateLabel: '已确认',
      acknowledgedAt: minutesAgo(60),
      acknowledgedBy: '值班-李四',
      occurrences: 48,
      firingSeconds: 150 * 60,
      observed: '0.937（示例租户）',
      threshold: '单个租户不超过全部 token 的 90%',
      why: '该租户 968000 / 全部 1033000 tokens。一条正当的大客户流量会让它一直响 —— ' +
        '所以它的用途是「这条判据把归因接上了吗」，而不是「该限制谁」。',
      action:
        '先确认这是不是已知的：一个大客户、一次批处理、一段压测都会让它响。' +
        '★ 它同时是一条归因自检 —— 花费集中在某一家，说明「按租户」这一维真的接上了。' +
        '⚠️ 它不作为收紧某个租户预算的依据：预算该由业务方给，从观测数据反推预算是本末倒置。',
      window: '2026-09-12 ~ 2026-09-18',
      // 已确认 = 有人看过了，所以它不再需要被"关注"（与 reconcile-gap 那条同一个形状）
      needsAttention: false,
    },
    {
      fingerprint: 'empty-retrieval|检索',
      ruleCode: 'empty-retrieval',
      subject: '检索',
      title: '连续多次检索一条都没命中（模型白跑一轮，那一轮照样花钱）',
      severity: 'WARNING',
      severityLabel: '警告',
      severityMeaning: '需要有人看一眼，通常不紧急',
      phase: 'OBSERVE',
      state: 'RESOLVED',
      stateLabel: '已恢复',
      firstSeenAt: minutesAgo(1400),
      lastSeenAt: minutesAgo(300),
      resolvedAt: minutesAgo(280),
      acknowledgedAt: '',
      acknowledgedBy: null,
      occurrences: 9,
      firingSeconds: 1400 * 60 - 280 * 60,
      observed: '4 次连续空命中',
      threshold: '至少 3 次连续（不是累计）',
      why: '最后一次检索时索引里有 0 个块',
      action:
        '看检索明细里的 query 与 indexSize：indexSize=0 是索引没建起来（部署问题），' +
        'query 明显跑题是提问侧的问题。' +
        '⚠️ 单次 0 命中是正常结果，不是故障 —— 这条判据盯的是连续',
      window: '2026-09-12 ~ 2026-09-18',
      needsAttention: false,
    },
  ]
}

/**
 * 判据目录。
 *
 * ⚠️ 它必须**与后端的 AlertRule 一致**：剩下的这 8 条**全部是运行阶段（OBSERVE）判据** ——
 * 构建阶段（GATE）下**一个成员都没有**，因为原来那三条离线评测门禁
 * （基线漂移 / 采集条件不一致 / 指标方向未登记）的判定依赖后端测试与 `rag-baseline.json`，
 * 已随后端测试代码一起删除（2026-09-18）。所以 `phases` 里构建阶段那一条的 `count`
 * 由下面那个 filter 算出来，会自然变成 0 —— **结构不动，只少内容**。
 *
 * ⚠️ 这里曾经抄过后端的 `gate-entry-not-committable`，后来又删过三条门禁：
 * **假后端多一条判据不会有任何症状**（页面上只是多一行），而这正是"假数据与真代码漂移"
 * 最常见的形态 —— 所以删真后端那三条时，这里也必须跟着删。
 * 反过来，**漏掉一条运行阶段的判据也不会有任何症状**（页面上只是少一行），
 * 所以自检里有一条专门盯着运行阶段条数（8 条）的断言 —— 少一条在那里现形。
 * 而"构建阶段 0 条"是一件**有症状**的事：界面上那一段必须写明"当前没有判据"，
 * 自检里也有一条断言盯着它（否则一个空表头读起来像"构建阶段没问题"）。
 */
export const MOCK_RULES = [
  rule('unconfigured-prices', '价格未配置（成本只能算到 token，算不出金额）', 'INFO', 'OBSERVE', true),
  rule('unpriced-models', '窗口内出现了没有价格规则的模型（合计只是下界）', 'WARNING', 'OBSERVE', true),
  rule('reconcile-gap', '两个 token 口径对不上（按步骤汇总 ≠ 运行行累计）', 'WARNING', 'OBSERVE', true),
  // 【第 6 件】标题从"整窗"改成了"某租户"—— 主体粒度改了，标题必须跟着改：
  // 一条说"整窗"的标题配一个按租户判的实现，读的人会照错的粒度去理解那个数字
  rule('zero-cache-hits', '某租户的整窗提示词缓存命中率为 0（有两种成因，数据分不出来）', 'WARNING', 'OBSERVE', true),
  rule(
    'tenant-cost-concentration',
    '单个租户占掉了绝大部分 token 花费（先把归因本身当作一个信号读）',
    'INFO',
    'OBSERVE',
    true,
  ),
  rule('empty-retrieval', '连续多次检索一条都没命中（模型白跑一轮，那一轮照样花钱）', 'WARNING', 'OBSERVE', true),
  rule('canary-no-traffic', '灰度开着，但一份流量都没分到（这次实验等于没做）', 'WARNING', 'OBSERVE', true),
  rule('canary-split-biased', '灰度的实际分流与配置比例偏离过大（分流函数可疑）', 'WARNING', 'OBSERVE', true),
]

/**
 * 造一条判据。
 *
 * ⚠️ 最后一个参数 `executedBy` 现在**没有任何调用方传它**：目录里已经没有构建阶段判据，
 * 剩下这几条全都由 AlertEvaluator 在运行阶段跑（也就是下面那个默认值）。
 * 参数仍然留着，是因为真后端的 `AlertRule.executedBy` 依旧是**逐条**给的字段 ——
 * 将来再加门禁（由别的类跑）时，这里补一句"谁在执行"就能与真后端对上。
 */
function rule(code, title, severity, phase, evaluatedHere, executedBy) {
  return {
    code,
    title,
    severity,
    severityLabel: severity === 'INFO' ? '建议' : severity === 'WARNING' ? '警告' : '阻断',
    severityMeaning:
      severity === 'INFO'
        ? '排进待办就行，不影响服务'
        : severity === 'WARNING'
          ? '需要有人看一眼，通常不紧急'
          : '门禁类，红在构建阶段',
    phase,
    phaseLabel: phase === 'GATE' ? '构建阶段（门禁）' : '运行阶段（告警）',
    action: '按判据说明处置（假数据里不重复一整段 —— 真后端会给全文）',
    executedBy: executedBy ?? 'AlertEvaluator（运行阶段，每次评估跑一遍）',
    evaluatedHere,
  }
}

/** `GET /api/alerts/rules` 的响应 */
export function alertRules() {
  return {
    ok: true,
    writeProtected: false,
    thresholds: { consecutiveEmptyRetrievals: 3, canarySplitTolerance: 0.3, canaryMinSample: 10 },
    phases: [
      {
        phase: 'GATE',
        label: '构建阶段（门禁）',
        meaning: '上线前挡：它红了就说明这次改动不该合进去',
        evaluatedHere: false,
        count: MOCK_RULES.filter(r => r.phase === 'GATE').length,
      },
      {
        phase: 'OBSERVE',
        label: '运行阶段（告警）',
        meaning: '上线后报：它响了说明已经在跑的这一版有问题',
        evaluatedHere: true,
        count: MOCK_RULES.filter(r => r.phase === 'OBSERVE').length,
      },
    ],
    rules: MOCK_RULES,
    notes: [
      '判据目录是「什么算异常」的唯一一份定义：门禁（构建阶段）与告警（运行阶段）读的是同一个枚举。',
      '门禁只在构建阶段评估（只对「构建环境足以判定」的事下结论），告警只在运行阶段评估 —— ' +
        '所以本页不显示构建阶段的门禁结论：那要去 CI 上看。',
      '阈值写死在判据旁边（枚举里），不从配置读：一个可以被调低的阈值会在' +
        '「告警响了但没人管」时被顺手调低，而那时它就不再是判据了。',
    ],
  }
}

// ---------------------------------------------------------------------------
//  灰度状态（进程内可变）
// ---------------------------------------------------------------------------

function initialCanary() {
  return {
    kind: 'system-agent-tools',
    candidateVersion: 4,
    weightPercent: 20,
    note: '想验证新版提示词对工具选择的影响（假数据：实验进行中）',
    startedBy: 'zhangsan',
    startedAt: minutesAgo(180),
    active: true,
    endedAt: '',
    endedBy: null,
    endReason: null,
  }
}

/** 版本列表（含一个"回滚出来的"版本 —— 见文件头注释第 6 条）。 */
const MOCK_VERSIONS = [
  {
    version: 5,
    hash: 'c81f0a4b7e29',
    fingerprint: 'v5:c81f0a4b7e29',
    charCount: 1180,
    tokenEstimate: 302,
    note: '回滚到第 2 版（上一版线上答案变差）',
    author: 'oncall',
    forkedFrom: 2,
    createdAt: minutesAgo(60 * 30),
  },
  {
    version: 4,
    hash: '9ab12cd34ef5',
    fingerprint: 'v4:9ab12cd34ef5',
    charCount: 1042,
    tokenEstimate: 271,
    note: '灰度候选：在错误反馈那一段里加了「不要编造工具名」',
    author: 'zhangsan',
    forkedFrom: null,
    createdAt: minutesAgo(60 * 5),
  },
  {
    version: 3,
    hash: '77ee12ab90cd',
    fingerprint: 'v3:77ee12ab90cd',
    charCount: 1010,
    tokenEstimate: 264,
    note: '把「工作方式」那一段的步骤写细',
    author: 'lisi',
    forkedFrom: null,
    createdAt: minutesAgo(60 * 40),
  },
  {
    version: 1,
    hash: '3fa9c0d1e2b4',
    fingerprint: 'v1:3fa9c0d1e2b4',
    charCount: 932,
    tokenEstimate: 236,
    note: '出厂版本（由 system 从 classpath 写入）',
    author: 'system',
    forkedFrom: null,
    createdAt: minutesAgo(60 * 72),
  },
]

/**
 * 观测到的分流分布。
 *
 * 【为什么刻意让"配置 20%"与"实测 3/14 = 21.4%"不相等】
 * 它们本来就不该相等（抽样总有波动）。假数据如果写成"恰好 20%"，界面上那句
 * "实测与配置差多少"就永远显示成完美的 0 —— 而那既不是常态，也让
 * "样本够不够"那半句提示走不到。14 份样本刚好过最小样本量 10，
 * 于是"可以判断了"与"偏得不多"两条分支都能走到。
 */
function split() {
  return {
    candidateVersion: 4,
    weightPercent: 20,
    runs: 31,
    participatingRuns: 14,
    candidateRuns: 3,
    stableRuns: 11,
    observedRatio: 0.2143,
    minSampleForJudgement: 10,
    sampleEnough: true,
    runsStillNeeded: 0,
    noTraffic: false,
    versions: [
      { version: 4, variant: 'canary', variantLabel: '实验组（候选版）', runs: 3 },
      { version: 3, variant: 'stable', variantLabel: '对照组（稳定版）', runs: 11 },
      { version: 1, variant: '', variantLabel: '未参与这次实验', runs: 17 },
    ],
    note:
      '窗口 7 天内 14 份运行参与分流，其中 3 份走了 v4（实测 21.43%，配置 20%）。' +
      '⚠️ 这个数字回答的是「分流有没有按比例发生」，不是「新版更好还是更差」—— ' +
      '后者要一批问题 + 一套评判口径，本项目还没有。',
  }
}

// ---------------------------------------------------------------------------
//  进程内状态
// ---------------------------------------------------------------------------

const state = {
  events: initialEvents(),
  canary: initialCanary(),
}

/** 复位（`POST /api/alerts/__reset`）—— 自检跑第二遍的前提，见文件头注释。 */
function reset() {
  state.events = initialEvents()
  state.canary = initialCanary()
}

// ---------------------------------------------------------------------------
//  响应组装
// ---------------------------------------------------------------------------

function alertsResponse() {
  const firing = state.events.filter(e => e.state !== 'RESOLVED')
  return {
    ok: true,
    openCount: state.events.filter(e => e.state === 'OPEN').length,
    firingCount: firing.length,
    acknowledgedCount: firing.filter(e => e.state === 'ACKNOWLEDGED').length,
    worstSeverity: firing.length > 0 ? firing[0].severity : null,
    events: state.events,
    writeProtected: false,
  }
}

function sweepResponse() {
  const newly = state.events.filter(e => e.state === 'OPEN')
  return {
    ok: true,
    dryRun: false,
    window: '2026-09-12 ~ 2026-09-18',
    openCount: state.events.filter(e => e.state === 'OPEN').length,
    fired: newly,
    resolved: state.events.filter(e => e.state === 'RESOLVED'),
    ongoingCount: 0,
    ongoing: [],
  }
}

function canaryResponse() {
  return {
    ok: true,
    kind: 'system-agent-tools',
    kindSummary: 'Agent 循环（ReAct）的系统提示词：工作方式、错误反馈的读法、回答要求',
    kinds: ['system-agent-tools', 'system-agent'],
    windowDays: 7,
    active: state.canary.active,
    canary: state.canary.active ? state.canary : null,
    previous: state.canary.active ? null : state.canary,
    versions: MOCK_VERSIONS,
    activeVersion: 3,
    activeFingerprint: 'v3:77ee12ab90cd',
    split: split(),
  }
}

// ---------------------------------------------------------------------------
//  路由
// ---------------------------------------------------------------------------

/**
 * 路由入口（与 `handleCostRoutes` 同一套签名）。
 *
 * ⚠️ 这个函数会处理 **POST** —— 与 cost/prompt 那几组不一样，
 * 所以 `handleAlertsRoutes` 必须放在 sse-server 的 POST 分支里能被调到的地方，
 * 而不能像只读那几组一样被 `if (req.method !== 'GET') return false` 挡住。
 */
export function handleAlertsRoutes(req, res, url, json) {
  const pathname = url.pathname

  // ---- 复位（自检跑第二遍的前提） ----
  if (req.method === 'POST' && pathname === '/api/alerts/__reset') {
    reset()
    json(res, { ok: true, message: '假后端的告警与灰度状态已复位' })
    return true
  }

  // ---- 判据目录 ----
  if (req.method === 'GET' && pathname === '/api/alerts/rules') {
    json(res, alertRules())
    return true
  }

  // ---- 当前状态 ----
  if (req.method === 'GET' && pathname === '/api/alerts') {
    json(res, alertsResponse())
    return true
  }

  // ---- 跑一轮评估 ----
  if (req.method === 'GET' && pathname === '/api/alerts/sweep') {
    if (url.searchParams.get('dryRun') === 'true') {
      json(res, {
        ok: true,
        dryRun: true,
        window: '2026-09-12 ~ 2026-09-18',
        wouldFire: [{ ruleCode: 'unconfigured-prices', title: '价格未配置', subject: '价格表', severity: 'INFO' }],
        quiet: ['empty-retrieval', 'zero-cache-hits'],
        skipped: MOCK_RULES.filter(r => !r.evaluatedHere).map(r => ({
          ruleCode: r.code,
          title: r.title,
          note: '这条判据属于构建阶段，本轮不评估',
        })),
        note: 'dryRun：这一轮没有写库，所以「新响 / 恢复」分不出来。',
      })
      return true
    }
    setTimeout(() => json(res, sweepResponse()), 200)
    return true
  }

  // ---- 确认一条 ----
  const ack = pathname.match(/^\/api\/alerts\/(.+)\/ack$/)
  if (req.method === 'POST' && ack) {
    const fingerprint = decodeURIComponent(ack[1])
    const target = state.events.find(e => e.fingerprint === fingerprint)
    if (!target) {
      res.writeHead(404, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ ok: false, message: `没有这条告警：${fingerprint}` }))
      return true
    }
    if (target.state === 'RESOLVED') {
      // 409 而不是 400：这两句话的处置不同（刷新 vs 改内容），
      // 与真后端 Prompt 面板那条 409 是同一条纪律
      res.writeHead(409, { 'Content-Type': 'application/json' })
      res.end(
        JSON.stringify({
          ok: false,
          message: '这条告警已经恢复了，不需要确认 —— 已恢复的不该被标成「人处理过」',
        }),
      )
      return true
    }
    target.state = 'ACKNOWLEDGED'
    target.stateLabel = '已确认'
    target.needsAttention = false
    target.acknowledgedAt = new Date().toISOString()
    target.acknowledgedBy = '界面自检'
    json(res, { ok: true, event: target })
    return true
  }

  // ---- 灰度 ----
  if (req.method === 'GET' && pathname === '/api/canary') {
    json(res, canaryResponse())
    return true
  }

  if (req.method === 'POST' && pathname === '/api/canary/start') {
    return withBody(req, body => {
      const version = Number(body.candidateVersion)
      const weight = Number(body.weightPercent)
      if (state.canary.active) {
        res.writeHead(409, { 'Content-Type': 'application/json' })
        res.end(
          JSON.stringify({
            ok: false,
            message:
              '这一份提示词已经有一次进行中的灰度了 —— 先停掉它（停止时要写清为什么停），再开新的。' +
              '⚠️ 不允许直接覆盖：被覆盖掉的那次是一次正在进行的实验。',
          }),
        )
        return
      }
      if (!Number.isFinite(version) || !Number.isFinite(weight)) {
        res.writeHead(400, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: false, message: '缺少字段（weightPercent 或 candidateVersion）' }))
        return
      }
      if (weight < 1 || weight > 99) {
        res.writeHead(400, { 'Content-Type': 'application/json' })
        res.end(
          JSON.stringify({
            ok: false,
            message: `重量必须在 1..99 之间（现在 ${weight}）：0 等于没开却看起来开着，100 等于全量发布。`,
          }),
        )
        return
      }
      if (version === 3) {
        res.writeHead(400, { 'Content-Type': 'application/json' })
        res.end(
          JSON.stringify({
            ok: false,
            message: '第 3 版就是当前生效那一版 —— 那样两组跑的是同一份提示词，实验什么都证明不了。',
          }),
        )
        return
      }
      if (!body.note || String(body.note).trim() === '') {
        res.writeHead(400, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: false, message: '必须写清这次实验想验证什么（note）' }))
        return
      }
      state.canary = {
        kind: 'system-agent-tools',
        candidateVersion: version,
        weightPercent: weight,
        note: String(body.note),
        startedBy: body.by ? String(body.by) : null,
        startedAt: new Date().toISOString(),
        active: true,
        endedAt: '',
        endedBy: null,
        endReason: null,
      }
      json(res, { ok: true, canary: state.canary, next: '新起的运行会按运行 id 的稳定哈希分流' })
    })
  }

  if (req.method === 'POST' && pathname === '/api/canary/stop') {
    return withBody(req, body => {
      if (!state.canary.active) {
        res.writeHead(409, { 'Content-Type': 'application/json' })
        res.end(
          JSON.stringify({
            ok: false,
            message: '这一份提示词现在没有进行中的灰度 —— 停止没有执行。',
          }),
        )
        return
      }
      if (!body.endReason || String(body.endReason).trim() === '') {
        res.writeHead(400, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: false, message: '必须写清为什么停（endReason）' }))
        return
      }
      state.canary = {
        ...state.canary,
        active: false,
        endedAt: new Date().toISOString(),
        endedBy: body.by ? String(body.by) : null,
        endReason: String(body.endReason),
      }
      json(res, { ok: true, canary: state.canary, next: '新的运行全部走稳定版。已经跑过的不受影响。' })
    })
  }

  return false
}

/** 读完请求体再回调（假后端要收 JSON 体，而 sse-server 的 POST 分支不代读）。 */
function withBody(req, handler) {
  let raw = ''
  req.on('data', chunk => {
    raw += chunk
  })
  req.on('end', () => {
    let body = {}
    try {
      body = raw ? JSON.parse(raw) : {}
    }
    catch {
      body = {}
    }
    handler(body)
  })
  return true
}
