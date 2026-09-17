/**
 * 成本看板端点的**假数据**（阶段四 · 工程化）。
 *
 * ---------------------------------------------------------------------------
 * 与 rag-routes / agent-routes 同一条规矩：**数字是假的，形状是真的**
 * ---------------------------------------------------------------------------
 * 界面自检要确定性断言，真后端的数字每次都不同。所以这里的每个字段都照着
 * `ai.agent.service.cost.CostDashboardView` 返回的形状写，而且刻意**不是一份"万事如意"的数据**——
 * 它必须能让那些"出了事才看得见"的分支也走到：
 *
 *   1. **有一个模型没有价格**（`deepseek-v4-pro`）→ 界面上那一行的金额必须是
 *      「算不出」+ 原因，而**合计必须标成下界**。这是这一页最重要的一条纪律
 *      （null 不许渲染成 0），而如果假数据里所有模型都有价，那条分支在自检里
 *      永远走不到 —— 等于没验证；
 *   2. **有一条没有业务归因的运行**（key 为 null）→ 它必须独立占一行「未标注」，
 *      不能被并进任何一个业务里；
 *   3. **缓存命中率整窗为 0**（`suspicious: true`）→ 界面必须说明"有两种可能、
 *      数据本身分不出来"，而不是下结论说"缓存没起作用"；
 *   4. **对账不一致**（gap 非 0）→ 界面必须报出来。这一条尤其重要：
 *      它是看板的仪表自检，而"对得上"是常态、不一致才是要看的东西 ——
 *      假数据永远给"对得上"的话，那个红色告警就从来没被人看过一眼；
 *   5. **工具那一栏没有金额**（这是刻意的，不是漏了）。
 *
 * ---------------------------------------------------------------------------
 * 为什么时间窗是"相对今天"算出来的
 * ---------------------------------------------------------------------------
 * 写死日期的话，第二天打开假后端就会看到"最近 7 天"里一条数据都没有 ——
 * 而那恰好是"没运行过"与"假数据过期了"两种情况，看起来一样。
 * 相对今天算，任何时候跑自检看到的都是同一份数据。
 */

/** 相对今天偏移 n 天，返回 YYYY-MM-DD */
function dayOffset(n) {
  const d = new Date()
  d.setDate(d.getDate() + n)
  return d.toISOString().slice(0, 10)
}

function at(n) {
  return `${dayOffset(n)}T10:00:00Z`
}

/**
 * 价格表。
 *
 * 【为什么刻意留一个模型没价】
 * 见文件头注释第 1 条 —— 那是这一页最需要被验证的分支。
 * 【为什么有一条 `*` 兜底规则】它顺带验证"兜底价"这个标记的显示
 * （兜底规则命中的金额必须带一个「兜底价」标签，否则读者以为那是专属价）。
 */
export const MOCK_PRICES = [
  {
    model: 'deepseek-flash',
    inputPerMillion: '0.5000',
    cacheHitPerMillion: '0.0500',
    cacheHitIsAssumed: false,
    outputPerMillion: '2.0000',
    currency: 'CNY',
    effectiveFrom: '2026-09-10',
    source: '供应商定价页（2026-09-10 调价后）',
  },
  {
    /*
      这一条的作用是**演示"缓存价是假设的"**（cacheHitIsAssumed: true）：
      它表示"没配缓存价，所以按未命中价算"。界面必须把它标成「假设」而不是「事实」——
      两者对"成本偏高"这件事的解释完全不同（一个是上游真的不便宜，一个是我们没配）。
      而它**不影响** needsPriceFor：那个列表说的是"窗口里出现过、但没匹配到价格的模型"，
      这条规则对应的模型这一窗根本没跑过，所以在列表里没有它 ——
      价格表里有"还没用到的模型"是正常的（这正是先把价配好的样子）。
    */
    model: 'deepseek-v4-pro',
    inputPerMillion: '1.0000',
    cacheHitPerMillion: '1.0000',
    cacheHitIsAssumed: true,
    outputPerMillion: '4.0000',
    currency: 'CNY',
    effectiveFrom: '2026-09-01',
    source: '供应商定价页',
  },
]

/**
 * 没有价格的模型（界面据此显示"待配置"待办）。
 *
 * ⚠️ 这里的 `deepseek-v4-pro` **在 byModel() 里刻意不给 amount**
 * （也就是"窗口里跑过它、但当时没有价格规则"），所以界面上那一行必须是「算不出」+ 原因。
 * 而上面 MOCK_PRICES 里那条同名的规则是"后来才补上的价" —— 它不影响
 * `needsPriceFor`（那个列表说的是"窗口里出现过、但没匹配到价格的模型"）。
 *
 * <p>为什么必须同时有这两样：只有"没价"就没有价格表可显示；
 * 只有"有价"那条最需要被验证的分支（null 不许渲染成 0）就永远走不到。
 */
const NEEDS_PRICE = ['deepseek-v4-pro']

const CURRENCY = 'CNY'

/**
 * 按天的数据。
 *
 * 【为什么天数不是 7 天全有】留一天空档（第 3 天）是刻意的：
 * 那张表必须能表达"某天没有运行"，而如果每天都排满，读者会以为
 * "每天都有数据"是这个表的固有形态 —— 真实使用里空档才是常态。
 */
function buildDays() {
  /*
    【为什么第 -2 天单独标注了 v4-pro】
    这一天的金额是**两个模型分别算完再相加**的：flash 那部分按 ¥0.5/百万 输入，
    v4-pro 那部分没有价格 —— 所以那一天的金额是 flash 的、而 totalTokens 是两者的。
    假数据如果只写"一个数字"，就看不出"有一天的合计其实是下界"这件事，
    而界面上那一格正是要表达它。
  */
  const rows = [
    { day: dayOffset(-6), runs: 3, failed: 0, stopped: 0, prompt: 21400, cached: 0, completion: 3100, unplacedPrompt: 0, unplacedCompletion: 0, amount: '0.0169' },
    { day: dayOffset(-4), runs: 5, failed: 1, stopped: 1, prompt: 38200, cached: 0, completion: 5400, unplacedPrompt: 0, unplacedCompletion: 0, amount: '0.0299' },
    // proUnplaced：这些 token 属于"没有价格的模型"，所以它们进了总量、不进金额
    { day: dayOffset(-2), runs: 8, failed: 2, stopped: 3, prompt: 61400, cached: 0, completion: 8800, unplacedPrompt: 2000, unplacedCompletion: 600, amount: '0.0483' },
    { day: dayOffset(-1), runs: 6, failed: 0, stopped: 1, prompt: 44900, cached: 0, completion: 6100, unplacedPrompt: 0, unplacedCompletion: 0, amount: '0.0347' },
    { day: dayOffset(0), runs: 4, failed: 0, stopped: 0, prompt: 29600, cached: 0, completion: 4200, unplacedPrompt: 0, unplacedCompletion: 0, amount: '0.0232' },
  ]
  return rows.map(r => ({
    day: r.day,
    runs: r.runs,
    finishedRuns: r.runs,
    /*
      【为什么这里刻意留了"被上限拦下"大于 0 的天】
      它是 finishedRuns 的**子集**：既不是成功也不是失败。假数据里如果这一列恒为 0，
      界面上那一格就永远是「被上限拦下 0」—— 看起来像"从来没有被上限拦下过"，
      而真相可能只是"这个分支从来没被渲染过"。与 NEEDS_PRICE 那条是同一个道理：
      **假数据必须让"出了事才看得见"的分支也走到**。
    */
    stoppedRuns: r.stopped,
    failedRuns: r.failed,
    promptTokens: r.prompt + r.unplacedPrompt,
    cachedPromptTokens: r.cached,
    completionTokens: r.completion + r.unplacedCompletion,
    totalTokens: r.prompt + r.unplacedPrompt + r.completion + r.unplacedCompletion,
    cacheHitRate: 0,
    elapsedMs: r.runs * 8200,
    toolCalls: r.runs * 3,
    toolFailures: r.failed,
    toolElapsedMs: r.runs * 140,
    firstAt: `${r.day}T09:12:00Z`,
    amount: r.amount,
    amountMicros: Math.round(Number(r.amount) * 1_000_000),
    currency: CURRENCY,
    amountReason: null,
    amountBasis: 'PER_MODEL',
    priceRule: 'deepseek-flash',
    priceSource: '供应商定价页（2026-09-10 调价后）',
  }))
}

/** 按业务归因。**注意最后一条的 key 是 null**（见文件头注释第 2 条） */
function byIntent() {
  return [
    {
      key: '客服问答',
      keyLabel: '客服问答',
      keyMissing: false,
      runs: 12,
      finishedRuns: 12,
      stoppedRuns: 3,
      failedRuns: 1,
      promptTokens: 96000,
      cachedPromptTokens: 0,
      completionTokens: 13800,
      totalTokens: 109800,
      cacheHitRate: 0,
      elapsedMs: 98000,
      toolCalls: 36,
      toolFailures: 1,
      toolElapsedMs: 1620,
      firstAt: at(-6),
      // 按业务那一栏**刻意不给单一金额**（一格里可能混着多个模型）——
      // 界面上必须显示"去按模型那一栏看"，而不是一个按某个模型算出来的数字
      amount: null,
      amountMicros: null,
      amountReason: '这一组混着多个模型，单一单价不适用 —— 金额按模型分别计算后相加，见「按模型」那一栏',
    },
    {
      key: '报表助手',
      keyLabel: '报表助手',
      keyMissing: false,
      runs: 9,
      finishedRuns: 9,
      failedRuns: 2,
      promptTokens: 74000,
      cachedPromptTokens: 0,
      completionTokens: 9600,
      totalTokens: 83600,
      cacheHitRate: 0,
      elapsedMs: 76000,
      toolCalls: 27,
      toolFailures: 2,
      toolElapsedMs: 1180,
      firstAt: at(-5),
      amount: null,
      amountMicros: null,
      amountReason: '这一组混着多个模型，单一单价不适用 —— 金额按模型分别计算后相加，见「按模型」那一栏',
    },
    {
      key: null,
      keyLabel: '未标注',
      keyMissing: true,
      runs: 5,
      finishedRuns: 5,
      failedRuns: 0,
      promptTokens: 25300,
      cachedPromptTokens: 0,
      completionTokens: 4200,
      totalTokens: 29500,
      cacheHitRate: 0,
      elapsedMs: 41000,
      toolCalls: 15,
      toolFailures: 0,
      toolElapsedMs: 700,
      firstAt: at(-3),
      amount: null,
      amountMicros: null,
      amountReason: '这一组混着多个模型，单一单价不适用 —— 金额按模型分别计算后相加，见「按模型」那一栏',
    },
  ]
}

/**
 * 按模型。
 *
 * 【这里的金额是"独立算出来的"，不是随便编的】用 MOCK_PRICES 的单价乘一遍，
 * 让假数据自己内部一致 —— 因为自检里有一条断言是"按模型那一栏加起来等于合计"。
 * 假数据自己算不平的话，那条断言就永远红，而人会去改断言而不是改数据。
 * 而 `deepseek-v4-pro` 刻意没有价格规则 → amount 必须是 null。
 */
function byModel() {
  /*
    ⚠️ 这几个数字必须与 buildDays() 里各天之和**严格相等**：
      flash 输入 = 21400+38200+61400+44900+29600 = 195500
      flash 输出 = 3100+5400+8800+6100+4200 = 27600
      pro   输入/输出 = 2000 / 600（那一天里没有价格的那部分）
    为什么值得写这么细：自检里有一条"按模型那一栏加起来等于合计"的断言，
    而假数据自己算不平的话，那条断言会一直红 —— 然后人会去改断言，而不是改数据。
    **一份自己都算不平的假数据，比没有假数据更坏。**
  */
  const flash = {
    prompt: 195500,
    cached: 0,
    completion: 27600,
  }
  // deepseek-v4-pro **有 token 但没有价格** —— 这一条是整份假数据里最重要的构造：
  // 它的 token 计入总量、不进金额，于是界面上必须出现"算不出"+ 原因，
  // 而合计必须被标成下界。没有它，这条分支在自检里永远走不到
  const pro = { prompt: 2000, cached: 0, completion: 600 }
  // deepseek-flash：0.5 元/百万 输入 + 2 元/百万 输出（缓存 0.05，但这一窗没命中）
  const flashMicros = Math.round((flash.prompt * 0.5 + flash.completion * 2.0))
  return [
    {
      key: 'deepseek-flash',
      keyLabel: 'deepseek-flash',
      keyMissing: false,
      runs: 24,
      finishedRuns: 24,
      /*
        【这一行刻意是 0，因为它模拟的是 byModel 那一维】
        真后端里"按模型"分组的 SQL 把 finished / stopped / failed 三个计数写死为 0
        （那一维按模型分组，没有运行级状态可言）。假数据必须**照着这个形状**写 ——
        否则"合计取错了来源"这个 bug 在假路径上永远看不出来（它当初就是这样躲过一轮的）。
      */
      stoppedRuns: 0,
      failedRuns: 3,
      promptTokens: flash.prompt,
      cachedPromptTokens: flash.cached,
      completionTokens: flash.completion,
      totalTokens: flash.prompt + flash.completion,
      cacheHitRate: 0,
      elapsedMs: 214000,
      toolCalls: 78,
      toolFailures: 3,
      toolElapsedMs: 3500,
      firstAt: at(-6),
      amount: (flashMicros / 1_000_000).toFixed(4),
      amountMicros: flashMicros,
      currency: CURRENCY,
      amountReason: null,
      // 口径：这一格的金额是**在这一格上直接算出来的**（模型名唯一 → 单价唯一）。
      // 与两个归因维度那两栏的 ALLOCATED_BY_TOKEN_SHARE 相对 —— 界面据此显示不同的说明
      amountBasis: 'PER_MODEL',
      priceRule: 'deepseek-flash',
      priceSource: '供应商定价页（2026-09-10 调价后）',
    },
    {
      key: 'deepseek-v4-pro',
      keyLabel: 'deepseek-v4-pro',
      keyMissing: false,
      runs: 2,
      finishedRuns: 2,
      failedRuns: 0,
      // 它**有 token**（所以"没价"这件事才有后果：token 计入总量、不进金额）
      promptTokens: pro.prompt,
      cachedPromptTokens: pro.cached,
      completionTokens: pro.completion,
      totalTokens: pro.prompt + pro.completion,
      cacheHitRate: 0,
      elapsedMs: 22000,
      toolCalls: 0,
      toolFailures: 0,
      toolElapsedMs: 0,
      firstAt: at(-2),
      // ⚠️ 这一行的 key 有值，但**没有匹配的价格规则** —— 与"没上报模型名"是两种不同的 null 原因，
      // 界面必须把两种都显示成「算不出」+ 各自的原因
      amount: null,
      amountMicros: null,
      amountReason:
        '没有匹配的价格规则（model=deepseek-v4-pro）—— 在 ai.cost.prices 里补一条，或访问 /api/cost/prices 看写法',
    },
  ]
}

/**
 * 按**租户**归因（阶段四第 6 件：成本归因）。
 *
 * ---------------------------------------------------------------------------
 * 【它与 byIntent 是两个维度，假数据也必须体现这一点】
 * ---------------------------------------------------------------------------
 * 「按租户」回答"谁在花钱"，「按业务」回答"花在哪个功能上"。
 * 所以这里的三个租户与 byIntent 里那三个业务**刻意不是一一对应**：
 *   · 示例租户 用了两个功能（客服问答 + 报表助手）；
 *   · 内部试用 只用了一个功能（客服问答）；
 *   · 而 byIntent 里的「未标注」那一行，在租户维度上分属不同租户。
 * 如果两边一一对应，那个结构差异就永远走不到 —— 而它正是这一件要证明的东西。
 *
 * ---------------------------------------------------------------------------
 * 【金额：这里写的是 null，由 costSummary 按 token 占比分摊后填上】
 * ---------------------------------------------------------------------------
 * 真后端在「按租户」这一栏给的是**分摊值**（费用对 token 线性，所以它与逐模型算完再相加等价），
 * 而且刻意让"各行相加恰好等于合计"成立。假数据也照同一条规则算 ——
 * 见 costSummary 里 allocateByTokenShare 那段注释：**一份自己都算不平的假数据，
 * 比没有假数据更坏**（自检里那条"各栏加起来等于合计"的断言会永远红，
 * 然后人会去改断言而不是改数据）。
 */
function byTenant() {
  return [
    {
      key: '示例租户',
      keyLabel: '示例租户',
      keyMissing: false,
      runs: 12,
      finishedRuns: 12,
      stoppedRuns: 3,
      failedRuns: 1,
      promptTokens: 96_000,
      cachedPromptTokens: 0,
      completionTokens: 13_800,
      totalTokens: 109_800,
      cacheHitRate: 0,
      elapsedMs: 98_000,
      toolCalls: 36,
      toolFailures: 1,
      toolElapsedMs: 1_620,
      firstAt: at(-6),
      amount: null,
      amountMicros: null,
      amountReason: null,
    },
    {
      key: '内部试用',
      keyLabel: '内部试用',
      keyMissing: false,
      runs: 9,
      finishedRuns: 9,
      stoppedRuns: 0,
      failedRuns: 2,
      promptTokens: 74_000,
      cachedPromptTokens: 0,
      completionTokens: 9_600,
      totalTokens: 83_600,
      cacheHitRate: 0,
      elapsedMs: 76_000,
      toolCalls: 27,
      toolFailures: 2,
      toolElapsedMs: 1_180,
      firstAt: at(-5),
      amount: null,
      amountMicros: null,
      amountReason: null,
    },
    {
      /*
        【这一行的 key 是 null —— 与 byIntent 里那条"未标注"同一个形状，但含义不同】
        它说的是"这些运行没有传 tenantId"（不知道算谁的钱），
        而不是"不知道属于哪个功能"。界面上两行的名字**必须不同**
        （「未标注租户」vs「未标注」），否则读者会以为它们是同一个空洞。
      */
      key: null,
      keyLabel: '未标注租户',
      keyMissing: true,
      runs: 5,
      finishedRuns: 5,
      stoppedRuns: 1,
      failedRuns: 0,
      promptTokens: 25_300,
      cachedPromptTokens: 0,
      completionTokens: 4_200,
      totalTokens: 29_500,
      cacheHitRate: 0,
      elapsedMs: 41_000,
      toolCalls: 15,
      toolFailures: 0,
      toolElapsedMs: 700,
      firstAt: at(-3),
      amount: null,
      amountMicros: null,
      currency: CURRENCY,
      amountReason: null,
    },
  ]
}

/**
 * 按工具。**没有金额**，这是刻意的（见文件头注释第 5 条）
 */
function byTool() {
  const reason =
    '工具不直接消耗 token —— 它消耗的是延迟与下游容量。「这把工具花了多少钱」只能靠' +
    '「它让模型多跑了几轮」间接归因，那是推测而不是数据'
  return [
    {
      toolName: 'search_docs',
      calls: 42,
      failures: 2,
      successRate: 0.9524,
      unknownTool: 1,
      validationErrors: 4,
      parseErrors: 1,
      rejected: 2,
      circuitOpen: 0,
      elapsedMs: 1840,
      runs: 21,
      amount: null,
      amountReason: reason,
    },
    {
      toolName: 'read_section',
      calls: 24,
      failures: 1,
      successRate: 0.9583,
      unknownTool: 0,
      validationErrors: 0,
      parseErrors: 0,
      rejected: 0,
      circuitOpen: 0,
      elapsedMs: 620,
      runs: 18,
      amount: null,
      amountReason: reason,
    },
    {
      toolName: 'list_documents',
      calls: 15,
      failures: 0,
      successRate: 1,
      unknownTool: 0,
      validationErrors: 0,
      parseErrors: 0,
      rejected: 0,
      circuitOpen: 0,
      elapsedMs: 340,
      runs: 15,
      amount: null,
      amountReason: reason,
    },
    {
      // 模型编出来的工具名也要出现在这一栏 —— 它换来的是"模型不知道有哪些工具"这份数据，
      // 而不是"工具坏了"。自检会断言这一行存在
      toolName: 'doc_search',
      calls: 1,
      failures: 0,
      successRate: 1,
      unknownTool: 1,
      validationErrors: 0,
      parseErrors: 0,
      rejected: 0,
      circuitOpen: 0,
      elapsedMs: 2,
      runs: 1,
      amount: null,
      amountReason: reason,
    },
  ]
}

/** `GET /api/cost/summary` 的完整响应 */
export function costSummary(windowDays) {
  const dayRows = buildDays()
  const totals = {
    runs: dayRows.reduce((s, r) => s + r.runs, 0),
    finishedRuns: dayRows.reduce((s, r) => s + r.finishedRuns, 0),
    // 【合计的运行级计数必须是"按天的和"】真后端也走这条口径（见 CostDashboardView.totals）——
    // 曾经它取的是"按模型那一组"的和，而那个维度里这几个计数是写死的 0。
    // 假数据这里如果跟着写 0，就等于替真代码圆了场（两条路径的差异会被抹掉）
    stoppedRuns: dayRows.reduce((s, r) => s + r.stoppedRuns, 0),
    failedRuns: dayRows.reduce((s, r) => s + r.failedRuns, 0),
    promptTokens: dayRows.reduce((s, r) => s + r.promptTokens, 0),
    cachedPromptTokens: 0,
    completionTokens: dayRows.reduce((s, r) => s + r.completionTokens, 0),
    toolCalls: dayRows.reduce((s, r) => s + r.toolCalls, 0),
    toolFailures: dayRows.reduce((s, r) => s + r.toolFailures, 0),
    toolElapsedMs: dayRows.reduce((s, r) => s + r.toolElapsedMs, 0),
    elapsedMs: dayRows.reduce((s, r) => s + r.elapsedMs, 0),
    firstAt: dayRows[0].firstAt,
  }
  totals.totalTokens = totals.promptTokens + totals.completionTokens
  totals.cacheHitRate = 0

  // 合计金额 = 各模型金额之和（真后端就是这么算的，假数据也照同一条规则算，
  // 否则"合计等于按模型之和"这条断言在假后端上就永远对不上）
  const models = byModel()
  const known = models.filter(m => m.amountMicros !== null)
  const totalMicros = known.reduce((s, m) => s + m.amountMicros, 0)

  // ---- 两个归因维度的金额：从合计按 token 占比分摊（与真后端同一套算法）----
  //
  // 【为什么这里也要"算"而不是写死】自检里有一条断言是"各栏加起来等于合计"。
  // 写死的话，任何一次改动（加一行、改一个 token 数）都会让那条断言变红 ——
  // 而那时人的第一反应是去改断言，不是去改数据。
  // 【为什么最后一行取"合计 − 前面所有行"】逐行各自四舍五入会差几个 micro，
  // 于是"加起来等于合计"这条核对永远差一点点。真后端也是这么做的（见
  // CostDashboardView.allocateByTokenShare）—— 假数据与真代码用同一条算法，
  // 才不会出现"两条路径长得一样但结论不同"。
  const allocation = rows => {
    const total = rows.reduce((s, r) => s + r.totalTokens, 0)
    let assigned = 0
    return rows.map((row, i) => {
      const micros = total <= 0
        ? 0
        : i === rows.length - 1
          ? totalMicros - assigned
          : Math.round((totalMicros * row.totalTokens) / total)
      assigned += micros
      return {
        ...row,
        amount: (micros / 1_000_000).toFixed(4),
        amountMicros: micros,
        // 币种要给：界面靠它决定显示 ¥ 还是 $（缺了它那一格就只剩一个裸数字）——
        // 真后端在每一行上都会给 currency（见 CostDashboardView.groups）
        currency: CURRENCY,
        amountReason: null,
        amountBasis: 'ALLOCATED_BY_TOKEN_SHARE',
      }
    })
  }

  return {
    ok: true,
    window: { from: dayOffset(-(windowDays - 1)), to: dayOffset(0), days: windowDays, maxDays: 90 },
    currency: CURRENCY,
    pricingConfigured: true,
    needsPriceFor: NEEDS_PRICE,
    priceTable: MOCK_PRICES,
    configNotes: [],
    totals: {
      ...totals,
      // 【这里的 amount 是"下界"而不是真值】：有一个模型没价（见 NEEDS_PRICE）。
      // 界面上必须同时显示"下界"这个限定词 —— 一个标着"合计"的下界是最容易误导的数字
      amount: (totalMicros / 1_000_000).toFixed(4),
      amountMicros: totalMicros,
      currency: CURRENCY,
      amountReason: null,
      allModelsPriced: false,
      daysWithData: dayRows.length,
    },
    days: dayRows,
    byTenant: allocation(byTenant()),
    byIntent: allocation(byIntent()),
    byModel: models,
    byTool: byTool(),
    reconciliation: {
      // ⚠️ **刻意不一致**（见文件头注释第 4 条）：看板的金额按步骤表汇总，
      // 而运行列表显示的是运行行上的累计值。差 1200 说明有一处漏记 ——
      // 假数据永远给"对得上"的话，那个红色告警就从来没被人看过
      runLevelTokens: 226100,
      stepLevelTokens: 224900,
      gap: -1200,
      runs: totals.runs,
      runsWithoutSteps: 2,
      matched: false,
      note:
        '⚠️ 两个口径对不上（差 -1200）—— 看板的钱按步骤表算，而运行列表显示的是运行行上的累计值。' +
        '差值说明有一处漏记，最可能是某次运行没写检查点（见 runsWithoutSteps）',
    },
    cache: {
      promptTokens: totals.promptTokens,
      cachedPromptTokens: 0,
      hitRate: 0,
      runsWithCacheHit: 0,
      suspicious: true,
      note:
        '⚠️ 整个窗口的缓存命中都是 0。这有两种可能，数据本身分不出来：' +
        '（a）上游确实一次都没命中；（b）上游不支持/不返回缓存字段 —— ' +
        '如果是 (b)，成本被按最贵那一档算了。区分方法只有一个：' +
        '拿真 key 跑一次、直接看响应里的 usage 有没有缓存字段。',
    },
    notes: [
      'token 数不是估算的：它来自上游响应里的 usage，应用侧只做聚合 —— ' +
        '所以这一页的 token 口径与运行列表、与上游账单是同一份事实。',
      // 【第 6 件：两个归因维度的口径必须与数字同屏】
      // 少了它，读者会以为「按租户」那一栏的钱是错的（"它凭什么有金额"）
      '「按租户」与「按业务」两栏回答的是两个问题：前者是「谁在花钱」，' +
        '后者是「花在哪个功能上」—— 它们是两个独立的分组，不是同一个字段的两种叫法。' +
        '两栏的金额都是按 token 占比从合计分摊的（费用对 token 是线性的，' +
        '所以这与逐模型算完再相加等价），因此两栏各自加起来都等于「按模型」那一栏的和。',
      '⚠️ 有一批运行没有租户信息（调用方没传 tenantId）—— 它们单独占「未标注租户」' +
        '那一行，没有被并进任何一个租户里。并进去会让那个租户的成本虚高，' +
        '而虚高的部分无从发现。它与「未标注」（业务归因键缺失）是两件事。',
      '⚠️ 有模型还没有价格：deepseek-v4-pro —— 它们的 token 计入了总量，但不在金额里，' +
        '所以上面的合计是下界。',
      '⚠️ 窗口内有 2 次运行一个步骤都没有（大多死在第一步之前，例如上游鉴权失败）。' +
        '它们记的是 0 token —— 那不是「没花钱」，而是「没有可记录的花费」。',
      '金额精度到 4 位小数：单次运行的成本常常远小于一分钱（2000 token 在 ¥1/百万 的价位上是 ¥0.002），' +
        '四舍五入到分会让总额变成 0。',
      '默认库是内存库（ai.agent.store.url 默认 jdbc:h2:mem:...）：进程退出即清空，' +
        '所以「这一页只有本次启动之后的运行」是预期行为 —— 要看多天走势必须把 url 指到文件库或真 MySQL。',
      '工具那一维没有金额，这是刻意的：token 花在模型往返上，工具消耗的是延迟与下游容量。',
    ],
  }
}

/** `GET /api/cost/prices` 的响应 */
export function costPrices() {
  return {
    ok: true,
    currency: CURRENCY,
    configured: true,
    prices: MOCK_PRICES,
    notes: [],
    howToConfigure:
      '价格来自上游的商业条款，代码里刻意不写死默认值 —— 一个 ¥0.00 的成本表 ' +
      '会让人得出『这个功能不花钱』的结论，而真相是『我们不知道它花多少钱』。\n' +
      '在 application.properties 里加（可以多条，按模型区分）：\n' +
      '  ai.cost.prices[0].model=你的模型名\n' +
      '  ai.cost.prices[0].input-per-million=未命中缓存的输入单价\n' +
      '  ai.cost.prices[0].cache-hit-per-million=命中缓存的输入单价（少了它按未命中价算，偏保守）\n' +
      '  ai.cost.prices[0].output-per-million=输出单价\n' +
      '  ai.cost.prices[0].currency=CNY\n' +
      '  ai.cost.prices[0].effective-from=2026-01-01\n' +
      '  ai.cost.prices[0].source=价格出处（调价之后要能回答『这个价哪来的』）\n' +
      '模型名用 * 表示兜底规则。调价要新增一条带新 effective-from 的规则，' +
      '不要改旧的那条 —— 否则历史成本会被新价改写，而那种改写不留任何痕迹。',
  }
}

/**
 * 路由入口。
 *
 * 与 `handleAgentRoutes` 同一套签名（`json` 是 sse-server 传进来的响应助手）。
 * 返回值表示"这条请求我处理了没有" —— 与那两族路由一致。
 */
export function handleCostRoutes(req, res, url, json) {
  const pathname = url.pathname
  if (req.method !== 'GET') return false

  if (pathname === '/api/cost/summary') {
    const raw = Number(url.searchParams.get('days') ?? '7')
    // 与后端一样**夹住**而不是报错（后端上限 90）—— 两份上限保持一致，
    // 否则自检里"切到 30 天"这条断言在两边的表现会不一样
    const windowDays = Number.isFinite(raw) && raw > 0 ? Math.min(raw, 90) : 7
    setTimeout(() => json(res, costSummary(windowDays)), 260)
    return true
  }

  if (pathname === '/api/cost/prices') {
    json(res, costPrices())
    return true
  }

  return false
}
