/**
 * 线程诊断与 Token 预算端点的调用层（学习点 7 + 学习点 2 的取证入口）。
 *
 * 与 chat.ts / structured.ts 的取舍对比（同样三个端点，三种契约，所以三套写法）：
 *   - streamChat 用回调：增量要多次到达；
 *   - askStructured 用 Promise + 抛异常：只有一个结果；
 *   - 这里沿用 Promise + 抛异常 —— 这些端点全是「一问一答」，没有增量。
 *
 * 与 structured.ts 唯一的差别是**错误要分类**：诊断面板的端点全都要手动触发，
 * 而"后端没启动"和"这个实验超时了"对用户来说是完全不同的两件事，
 * 前者要去起服务、后者要么重按一次要么调小参数。所以这里把两者拆成不同的异常类型，
 * 而不是给一句 "Failed to fetch" 让用户自己猜（浏览器给出那句原文时，
 * 看不出到底是没启动、端口不对，还是被代理挡住了）。
 */

/** 诊断端点的请求超时（毫秒）。见下方 VIRTUAL_WORK_* 的说明。 */
export const DIAG_TIMEOUT_MS = 20_000

/**
 * 钉住对照实验的超时。
 *
 * **这个常量是实测"炸"出来的**：原先把所有诊断端点都套 DIAG_TIMEOUT_MS = 20 秒，
 * 而钉住实验在后端要跑 **19~26 秒**（synchronized 那组要把 12~16 个载体线程一批批耗完，
 * 耗时随占位任务数线性增长）。于是真后端上必然报
 * 「钉住载体的对照实验超过 20 秒仍未返回，已中断」——
 * **实验本身完全正常，是前端的等待时间比后端的固有耗时还短**。
 *
 * 为什么假后端掩盖了这个问题：假后端是瞬时返回固定 JSON（几百毫秒），
 * 20 秒绰绰有余，自检全绿。**这正说明"假后端全过"证明不了"真后端能用"** ——
 * 契约一致不代表耗时一致，而超时是个耗时问题。
 *
 * 取 120 秒：足够覆盖 holders 调到最大的情况，同时保留一个明确的失败边界
 *（不能设成无限，否则后端真的卡住时用户只会看到转圈）。
 */
export const PIN_TIMEOUT_MS = 120_000

/**
 * 跑「模型」的端点超时。
 *
 * 刻意与 DIAG_TIMEOUT_MS 分开：/api/chat/async 的耗时由上游模型决定
 * （实测单轮 1~4.6 秒，慢的时候十几秒），和诊断端点那种纯本地计算不是一回事。
 * 取 180s 与流式那边的前端整体超时对齐 —— 同一套约定，
 * 「前端超时 > 后端可能的最长耗时」，否则会出现"后端还在成功返回的路上，前端先报失败"的假故障。
 *
 * 另外这两个端点走的是**同步阻塞**路径（/api/chat/async 虽在虚拟线程上等，但对浏览器仍是一次普通请求），
 * 所以要给足时间，别用几十秒这种会把正常回答掐断的值。
 */
export const VIRTUAL_WORK_TIMEOUT_MS = 180_000

/** 诊断端点的 HTTP / 链路层错误；message 已经是给人看的中文 */
export class DiagError extends Error {
  constructor(
    message: string,
    /** 有 HTTP 状态码时带上，纯网络层失败时为 undefined */
    readonly status?: number,
    /**
     * Token 预算超限时的账目数字（只有被预算拒绝时才有）。
     *
     * 挂在错误对象上而不是塞进 message 字符串里，是因为界面要**用这些数字做排版**
     *（显示"超出 1752 tokens"、画一条超限的比例条），而不是让用户去正则解析一句话。
     * 机器要用的数据和给人看的话分成两份 —— 和结构化输出那边"结果 + attempts 过程"
     * 的拆分是同一个思路。
     */
    readonly budget?: BudgetOver | null,
  ) {
    super(message)
    this.name = 'DiagError'
  }
}

// ---------------------------------------------------------------------------
//  与后端契约对应的类型。
//
//  唯一事实来源是后端代码，这里改也要跟着改那边：
//    - ai.agent.diag.ThreadDiagnostics#currentThread / #snapshot
//    - ai.agent.controller.ThreadDiagnosticsController#config / #carriers / #pinning
//    - ai.agent.config.ChatCallProperties 的 VirtualThreads / Budget / Sampling 三节
// ---------------------------------------------------------------------------

/** GET /api/diag/threads 的 currentThread 字段 */
export interface ThreadFacts {
  /**
   * 处理这次请求的线程是不是虚拟线程。
   *
   * 这是整个面板唯一的"直接证据"字段：它来自运行时的 Thread.isVirtual()，
   * 而不是读配置。配置写错时 Boot 会静默忽略（未知属性不报错），
   * 所以「配置里写了 virtual」和「真的跑在虚拟线程上」是两件事 ——
   * 界面上必须把两者分开显示，别混成一个"已开启"的绿灯。
   */
  virtual: boolean
  name: string
  threadId: number
  daemon: boolean
  priority: number
  /** 配置里的执行模式：virtual / platform / auto */
  configuredMode: string
  /** 容器（Tomcat 连接器）是否换上了虚拟线程执行器 */
  containerVirtualThreadsEnabled: boolean
}

/** GET /api/diag/threads 的响应 */
export interface ThreadResponse {
  currentThread: ThreadFacts
  note: string
}

/**
 * GET /api/diag/threads/stats 的响应（POST /stats/reset 返回同一个形状）。
 *
 * 字段口径必须分清，否则会得出错误结论：
 *   - platform 系列来自 ThreadMXBean，<b>虚拟线程不算在内</b>（它们不是操作系统线程）；
 *   - liveVirtualThreads 是遍历所有线程数出来的，开销不小，所以只在手动刷新时取；
 *   - peakInFlight / totalRequests 是本服务自己数的业务口径并发，与线程数无关。
 */
export interface JvmStats {
  livePlatformThreads: number
  peakPlatformThreads: number
  daemonThreads: number
  liveVirtualThreads: number
  inFlight: number
  peakInFlight: number
  totalRequests: number
  availableProcessors: number
  /** platform 模式下线程池的大小（对照实验的分母） */
  platformPoolSize: number
  containerVirtualThreadsEnabled: boolean
  configuredMode: string
}

/** GET /api/diag/threads/config 的响应 */
export interface DiagConfig {
  containerVirtualThreads: boolean
  asyncExecutor: string
  platformPoolSize: number
  /** 采样替身延迟的 Duration 文本（PT0S / PT0.3S），替代压测时用 */
  samplingLatency: string
  standInActive: boolean
  budgetEnabled: boolean
  /** 上下文窗口（输入 + 输出共享这一个窗口） */
  contextWindow: number
  /** 为输出预留的最大 token 数 */
  maxOutputTokens: number
  /** 安全边际比例，0~1 */
  safetyMargin: number
  /** 扣掉安全边际后真正可用的预算 */
  usableBudget: number
  /** 留给输入的上限 = usableBudget - 为输出预留的部分 */
  inputAllowance: number
}

/** carriers 实验里的一行：某个任务跑在哪个载体线程上 */
export interface CarrierSample {
  /**
   * 虚拟线程名。
   *
   * 【实测】这里通常是**空字符串**：async-executor 用的是
   * Executors.newVirtualThreadPerTaskExecutor()，而它不给线程起名。
   * 所以这个字段不能当标识用（拿来当 React key 会让 16 条样本撞成同一个 key），
   * 也不一定有内容可显示 —— 界面上要按"可能为空"来渲染。
   */
  virtualThread: string
  /**
   * 载体线程名（形如 ForkJoinPool-1-worker-6）。
   *
   * 注意后端刻意只取 Thread#toString() 里 @ 之后那一段：完整串形如
   * VirtualThread[#63]/runnable@ForkJoinPool-1-worker-6，里面带着虚拟线程自己的编号，
   * 整串去重会得到"16 个不同载体"这种假结论。
   */
  carrier: string
}

/** GET /api/diag/threads/carriers 的响应 */
export interface CarrierExperiment {
  virtualThreads: number
  sleepMs: number
  /** 一共用到了几个不同的载体线程 —— 明显小于 virtualThreads 就亲眼看到了复用 */
  distinctCarriers: number
  /** ForkJoinPool 的并行度上限（= CPU 核数）；阻塞时调度器允许临时扩容，所以实测可能略大 */
  carrierParallelism: number
  samples: CarrierSample[]
  note: string
}

/**
 * GET /api/diag/threads/pin 的响应。
 *
 * 字段名是 probeLatency* 而不是 queueLatency*：这个实验经过一次返工，
 * 第一版"先启动占位任务、睡一会儿再量延迟"量到的两组都是 0ms
 * （runAsync 在提交时就把载体选好了，而"载体刚好好被占满"是个很窄的时间窗口，抓不住）。
 * 现在改成"分批投放 + 每批立刻连测多次探针，取最大值"，所以指标名也换成了 probeLatency。
 */
export interface PinExperiment {
  holders: number
  holdMsEach: number
  carrierParallelism: number
  /** 用 synchronized 占住载体线程时，探针任务从提交到真正开始执行的最坏延迟 */
  probeLatencyWithSynchronized: string
  /** 同一批次、同样的占位任务，改用 ReentrantLock（park 型阻塞，可卸载）的探针延迟 */
  probeLatencyWithReentrantLock: string
  verdict: string
  note: string
  fix: string
}

/** /api/chat/async 的执行方式：async = 虚拟线程里等，sync = 容器线程上等（对照组） */
export type AsyncMode = 'async' | 'sync'

/** 一次非流式对话的界面记录（回答 + 耗时，用来证明这条链路真的通） */
export interface AsyncTurn {
  id: string
  mode: AsyncMode
  conversationId: string
  message: string
  /** 请求进行中 */
  loading: boolean
  /** 后端返回的纯文本回答 */
  text?: string
  /** 从发起到读完响应体的耗时；与后端自己在日志里记的账不完全是一回事，见面板内的说明 */
  elapsedMs?: number
  error?: string
}

// ---------------------------------------------------------------------------
//  调用函数
// ---------------------------------------------------------------------------

/** GET /api/diag/threads —— 当前处理请求的线程是谁 */
export function getCurrentThread(): Promise<ThreadResponse> {
  return getJson<ThreadResponse>('/api/diag/threads', '当前线程')
}

/** GET /api/diag/threads/stats —— JVM 线程账目与并发峰值 */
export function getStats(): Promise<JvmStats> {
  return getJson<JvmStats>('/api/diag/threads/stats', '线程统计')
}

/**
 * POST /api/diag/threads/stats/reset —— 把 peakInFlight / totalRequests 归零。
 *
 * 为什么界面要暴露这个按钮：peak 是单调递增的历史值，连着做两组对照实验时，
 * 上一组的峰值会把这一组的数据顶住，看着像"这一组也压到了 12 并发"，其实是上一组留下的。
 * 每次实验前先重置，两组数字才可比。
 */
export function resetStats(): Promise<JvmStats> {
  return getJson<JvmStats>('/api/diag/threads/stats/reset', '重置峰值', 'POST')
}

/** GET /api/diag/threads/config —— 当前生效的关键配置 */
export function getConfig(): Promise<DiagConfig> {
  return getJson<DiagConfig>('/api/diag/threads/config', '生效配置')
}

/** GET /api/diag/threads/carriers —— 让 N 个虚拟线程同时阻塞，看它们被调度到哪些载体线程 */
export function runCarrierExperiment(tasks: number, ms: number): Promise<CarrierExperiment> {
  return getJson<CarrierExperiment>(`/api/diag/threads/carriers?tasks=${tasks}&ms=${ms}`, '载体线程实验')
}

/**
 * GET /api/diag/threads/pin —— 对比 synchronized 与 ReentrantLock 阻塞时的排队延迟
 *
 * 用**单独的超时**（PIN_TIMEOUT_MS）而不是诊断端点通用的 20 秒，原因见那个常量的注释：
 * 这个实验在后端天然要跑十几到二十几秒，套通用超时会让一个正常的实验必然报超时。
 * 一句话：**超时值必须大于被调用的固有耗时，而"固有耗时"只能靠实测得知。**
 */
export function runPinExperiment(holders: number, holdMs: number): Promise<PinExperiment> {
  return getJson<PinExperiment>(
    `/api/diag/threads/pin?holders=${holders}&holdMs=${holdMs}`,
    '钉住载体的对照实验',
    'GET',
    PIN_TIMEOUT_MS,
  )
}

/**
 * 非流式对话（学习点 7 的落点）：async 走虚拟线程，sync 是对照组。
 *
 * 返回 { text, elapsedMs } 而不是只返回文本：耗时是这两个端点唯一能被前端观察到的差异
 * （同样的模型、同样的配置，只有"阻塞发生在哪个线程上"不同），
 * 只显示回答的话这个功能等于没演示。
 */
export async function askNonStreaming(
  conversationId: string,
  message: string,
  mode: AsyncMode,
): Promise<{ text: string; elapsedMs: number }> {
  const path = mode === 'async' ? '/api/chat/async' : '/api/chat/async/sync'
  const query = `?conversationId=${encodeURIComponent(conversationId)}&message=${encodeURIComponent(message)}`
  const startedAt = performance.now()
  const text = await requestText(path + query, '非流式对话')
  return { text, elapsedMs: Math.round(performance.now() - startedAt) }
}

// ---------------------------------------------------------------------------
//  内部工具
// ---------------------------------------------------------------------------

/**
 * 发一个诊断请求并解析 JSON。
 *
 * 用同一个 helper 收口所有端点，是为了让"后端没启动"这句话只写一遍 ——
 * 六个端点各写一套 try/catch 的话，总有一两个会漏掉，面板上就会出现空框。
 */
async function getJson<T>(
  path: string,
  what: string,
  method: 'GET' | 'POST' = 'GET',
  timeoutMs: number = DIAG_TIMEOUT_MS,
): Promise<T> {
  const response = await request(path, what, method, timeoutMs)

  const body = await response.text().catch(() => '')
  try {
    return JSON.parse(body) as T
  } catch {
    // 返回了 200 但内容不是 JSON（例如被代理/网关换成了首页 HTML）——
    // 这时"解析失败"才是真相，别把 HTML 当数据往下传
    throw new DiagError(`拿「${what}」时后端返回的不是 JSON：${body.slice(0, 200) || '(空响应体)'}`)
  }
}

/** 发请求并读回纯文本（/api/chat/async 的 produces 是 text/plain） */
async function requestText(path: string, what: string): Promise<string> {
  const response = await request(path, what, 'GET', VIRTUAL_WORK_TIMEOUT_MS)
  return response.text()
}

/**
 * 真正发请求的地方：只负责把「链路层失败」和「HTTP 拒绝」翻译成能照着做的中文。
 *
 * timeoutMs 必须由调用方给：诊断端点（纯本地计算）和对话端点（等上游模型）
 * 的合理上限差一个数量级，用同一个值必然有一边是错的。
 *
 * 导出给 `rag.ts` 复用：RAG 面板的六个端点与这里同属"诊断"性质，
 * 错误文案与超时约定必须一致。各写一份的结果是两边对"后端没启动"
 * 的解释慢慢漂移，而用户看到的提示就不一样了。
 */
export async function request(
  path: string,
  what: string,
  method: 'GET' | 'POST',
  // 给默认值：调用方可以省略，省略时用诊断端点的通用超时。
  // rag.ts 那边把它当作可选参数往下传，没有默认值就编译不过 ——
  // 而这正是好事：类型系统逼着"用哪个超时"这件事必须显式决定，
  // 而不是漏传时静默地拿到 undefined。
  timeoutMs: number = DIAG_TIMEOUT_MS,
): Promise<Response> {
  const controller = new AbortController()
  let timedOut = false

  const timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, timeoutMs)

  try {
    const response = await fetch(path, {
      method,
      headers: { Accept: 'application/json, text/plain' },
      signal: controller.signal,
    })

    if (!response.ok) {
      const body = await response.text().catch(() => '')
      throw new DiagError(`HTTP ${response.status}：${extractHttpError(body, what)}`, response.status, budgetOf(body))
    }
    return response
  } catch (e) {
    if (e instanceof DiagError) throw e
    // 超时与断连都要显式认出来：否则用户看到的是浏览器那句
    // "The operation was aborted" / "Failed to fetch"，两种完全不同的处置方式被压成同一句话
    if (timedOut) {
      throw new DiagError(
        `「${what}」超过 ${Math.round(timeoutMs / 1000)} 秒仍未返回，已中断。` +
          '若这是一个本来就慢的实验（例如钉住对照），重按一次或把参数调小；' +
          '若持续超时，检查后端是否卡在模型调用上。',
      )
    }
    throw new DiagError(
      `连接不上后端：拿「${what}」时请求没发出去（${e instanceof Error ? e.message : String(e)}）。` +
        '最常见的原因是后端没启动 —— Vite 会把 /api 代理到 http://localhost:8080。',
    )
  } finally {
    clearTimeout(timer)
  }
}

/** Spring 的错误响应可能是 ProblemDetail JSON（detail 字段），也可能是空体 */
function extractHttpError(body: string, what: string): string {
  if (!body) return `后端没有给出原因（${what}）`
  try {
    const parsed = JSON.parse(body) as { detail?: string; message?: string; error?: string }
    return parsed.detail ?? parsed.message ?? parsed.error ?? body.slice(0, 300)
  } catch {
    return body.slice(0, 300)
  }
}

/**
 * 从错误响应里取出 Token 预算的账目数字。
 *
 * **为什么值得单独做这件事**：后端在预算超限时除了那句人话，还回了
 * `neededTokens / allowedTokens / overBy` —— 而"超出多少"恰恰是用户唯一能据以行动的数字
 *（把问题缩短到多少才合适，全靠它）。前端如果只渲染 message，
 * 用户就得自己从那句话里做心算，等于把最有用的字段扔了。
 *
 * 这些字段只在预算拒绝时存在，所以解析失败一律返回 null，绝不因此把错误本身吞掉 ——
 * **附加信息拿不到，不该影响主错误的呈现**。
 */
export interface BudgetOver {
  neededTokens: number
  allowedTokens: number
  overBy: number
}

function budgetOf(body: string): BudgetOver | null {
  if (!body) return null
  try {
    const p = JSON.parse(body) as Partial<BudgetOver>
    if (typeof p.overBy === 'number' && typeof p.neededTokens === 'number' && typeof p.allowedTokens === 'number') {
      return { neededTokens: p.neededTokens, allowedTokens: p.allowedTokens, overBy: p.overBy }
    }
    return null
  } catch {
    return null
  }
}
