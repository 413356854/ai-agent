import { useState } from 'react'
import { Alert, Button, Card, Col, Empty, Flex, Input, Row, Segmented, Spin, Tag, Typography } from 'antd'
import {
  CARRIER_SLEEP_MS,
  CARRIER_TASKS,
  PIN_HOLDERS,
  PIN_HOLD_MS,
} from '../hooks/useThreadDiag'
import type { Remote, UseThreadDiagResult } from '../hooks/useThreadDiag'
import type {
  AsyncMode,
  AsyncTurn,
  CarrierExperiment,
  DiagConfig,
  JvmStats,
  PinExperiment,
  ThreadResponse,
} from '../api/diag'

/**
 * 「线程与预算」面板：把后端两件事的运行时证据摆出来。
 *
 * 学习点 7（虚拟线程 / 线程模型）：面板的第一块就是<b>当前处理请求的线程是不是虚拟线程</b>。
 * 这一块的排版刻意做得跟别处不一样，因为它是全页唯一的"直接证据" ——
 * 配置里写 virtual 不等于真的跑在虚拟线程上（Boot 对未知属性静默忽略，
 * 也拦不住 bean 没装配），所以"配置意图"和"运行时事实"必须并排显示，让两者对不上时一眼可见。
 *
 * 学习点 2（Token 预算账目）：窗口 - 安全边际 = 可用预算，再切出一块给输出，剩下的才是输入上限。
 * 这一串数字单看每个都不难，难的是它们之间的关系；所以这里用一条按真实 token 数按比例铺开的
 * 色带把它们画在同一个坐标系里，"为输出预留 2048" 占窗口多大一块是看出来的，不是算出来的。
 *
 * 钉住实验（ping）是这张页面上最值钱的单个部件：它把"虚拟线程不是免费的"变成两个并排的数字。
 * 同一个进程、同一份配置、同样数量的占位任务、同样的持有时长，唯一差别是阻塞方式
 * （synchronized 会钉住载体线程，ReentrantLock 不会），排队延迟差出两个数量级。
 * 这个对比不靠讲解，靠数字自己说话。
 */
export function ThreadBudgetPanel({ diag }: { diag: UseThreadDiagResult }) {
  const config = diag.config.status === 'ok' ? diag.config.data : null

  return (
    <div className="diag-panel">
      {/*
        后端不可用时最要紧的是"说清是什么坏了"，而不是显示一个空框 —— 空框会被当成
        "数据是 0"（而"0 个虚拟线程"在虚拟线程这个话题里恰好是一个有意义的观测值）。
        所以这一条用 error 而不是 warning，并且只在两个首选端点都失败时出现。
      */}
      {diag.threadFacts.status === 'error' && (
        <Alert
          type="error"
          showIcon
          title="拿不到后端数据，这个面板的先决条件是后端在跑"
          description={
            <Flex vertical gap={6}>
              <span>{diag.threadFacts.error}</span>
              <Typography.Text type="secondary">
                Vite dev server 会把 /api 代理到 http://localhost:8080，所以这里不需要配 CORS；
                面板上所有数字都来自后端的运行时快照，没有本地估算或假数据可退。
              </Typography.Text>
            </Flex>
          }
        />
      )}

      <CurrentThreadCard remote={diag.threadFacts} onRefresh={diag.refreshThreads} busy={diag.busy} />

      <JvmAccountingCard
        remote={diag.stats}
        onRefresh={diag.refreshThreads}
        onReset={diag.resetPeaks}
        busy={diag.busy}
      />

      {/*
        两个实验并排：它们回答的是同一个问题的两面（载体线程会被复用 / 什么情况下复用不了），
        并排摆着才好对照。窄屏下 flex-wrap 会自动让它们各占一行，不需要写媒体查询。
      */}
      <div className="diag-pair">
        <CarrierExperimentCard remote={diag.carriers} onRun={diag.runCarriers} busy={diag.busy} />

        <PinningExperimentCard remote={diag.pinning} onRun={diag.runPinning} busy={diag.busy} />
      </div>

      <BudgetCard config={config} onReload={diag.loadConfig} />

      <AsyncCallCard turns={diag.asyncTurns} onSend={diag.callAsync} configSummary={budgetSummary(config)} />
    </div>
  )
}

// ---------------------------------------------------------------------------
//  1. 当前线程（有没有真的换上虚拟线程）
// ---------------------------------------------------------------------------

function CurrentThreadCard({
  remote,
  onRefresh,
  busy,
}: {
  remote: Remote<ThreadResponse>
  onRefresh: () => void
  busy: boolean
}) {
  return (
    <Card
      size="small"
      className="diag-card diag-card-wide"
      title="当前线程：处理这个请求的是谁"
      extra={
        <Button
          size="small"
          onClick={onRefresh}
          loading={remote.status === 'loading'}
          // 实验跑的时候刷新会被 hook 里的锁挡掉（两组请求同时占载体线程会让数字互相污染），
          // 所以这里直接禁用，而不是让用户点一个点了没反应的按钮
          disabled={busy}
        >
          重新探测
        </Button>
      }
    >
      {remote.status === 'loading' && <LoadingLine text="正在问后端：这次请求跑在哪个线程上……" />}

      {remote.status === 'error' && <InlineError title="探测失败" message={remote.error} />}

      {remote.status === 'ok' && <ThreadFactsBody facts={remote.data.currentThread} note={remote.data.note} />}
    </Card>
  )
}

function ThreadFactsBody({ facts, note }: { facts: ThreadResponse['currentThread']; note: string }) {
  const isVirtual = facts.virtual

  // 两个布尔值放在一起对比是有意设计的：
  //   containerVirtualThreadsEnabled = 我们打算让容器用虚拟线程处理请求（读配置的意图）；
  //   virtual = 这次请求真的跑在虚拟线程上（问运行时的事实）。
  //
  // 只在"开了容器虚拟线程、但当前线程仍是平台线程"时告警，因为只有这个方向能确定是异常
  // （配置生效的话处理这个诊断请求的线程本身就应该是虚拟的）。
  // 反过来的方向（当前是虚拟线程、配置却显示关着）多半是正常情况：ai.chat.virtual-threads
  // 只管容器虚拟线程这一个开关，而 async-executor 的控制粒度比它更细，两者不一致不构成故障 ——
  // 把正常配置报成异常，比不报更糟（用户会开始忽略告警）。
  const suspicious = facts.containerVirtualThreadsEnabled && !isVirtual

  return (
    <Flex vertical gap={12}>
      <div className={`vt-hero ${isVirtual ? 'is-virtual' : 'is-platform'}`}>
        <Flex align="center" gap={12} wrap>
          <span className="vt-hero-flag">{isVirtual ? '虚拟线程' : '平台线程'}</span>
          <Typography.Text className="vt-hero-raw">
            virtual: {String(isVirtual)}
          </Typography.Text>
          <Tag color={isVirtual ? 'green' : 'volcano'}>
            {isVirtual ? '改造已生效' : '仍在操作系统线程上'}
          </Tag>
        </Flex>
        <Typography.Text className="vt-hero-hint">
          {isVirtual
            ? '这个布尔值是全页唯一的直接证据：服务请求的线程不再是操作系统线程，并发上限因此不再等于容器线程池大小。'
            : '配置里可能写了 virtual，但这次请求仍跑在平台线程上 —— 配置意图不等于运行时事实。'}
        </Typography.Text>
      </div>

      <dl className="kv-grid">
        <Kv label="线程名" value={facts.name} mono />
        <Kv label="线程 ID" value={facts.threadId} mono />
        <Kv label="配置模式 asyncExecutor" value={facts.configuredMode} mono />
        <Kv label="容器虚拟线程已开启" value={String(facts.containerVirtualThreadsEnabled)} mono />
        <Kv label="守卫线程 daemon" value={String(facts.daemon)} mono />
        <Kv label="优先级" value={facts.priority} mono />
      </dl>

      {suspicious && (
        <Alert
          type="warning"
          showIcon
          title="配置说容器已经在用虚拟线程，但这次请求仍跑在平台线程上"
          description="两者冲突时以 virtual 为准 —— 它来自 Thread.isVirtual()，是运行时事实；配置读取只能反映意图，而属性名写错时 Spring Boot 不会报错，只会静默忽略。先检查 ai.chat.virtual-threads.enabled 对应的那个自定义 Tomcat 协议执行器有没有真的装配上（bean 没被扫描到也能正常启动，只是白改）。"
        />
      )}

      <Typography.Text type="secondary" className="diag-note">
        {note}
      </Typography.Text>
    </Flex>
  )
}

// ---------------------------------------------------------------------------
//  2. JVM 线程账目
// ---------------------------------------------------------------------------

function JvmAccountingCard({
  remote,
  onRefresh,
  onReset,
  busy,
}: {
  remote: Remote<JvmStats>
  onRefresh: () => void
  onReset: () => void
  busy: boolean
}) {
  const loading = remote.status === 'loading'

  return (
    <Card
      size="small"
      className="diag-card diag-card-wide"
      title="JVM 线程账目与并发峰值"
      extra={
        <Flex gap={8} wrap>
          <Button size="small" onClick={onRefresh} loading={loading} disabled={busy}>
            刷新
          </Button>
          {/*
            顺序不能反：先重置再压测，两组的数字才可比。
            按钮在 busy 时禁用 —— 与实验同时打进来会让 stats 被后到的响应覆盖，
            出现"重置了但峰值还是旧的"这种自相矛盾的画面
          */}
          <Button size="small" onClick={onReset} disabled={loading || busy}>
            重置峰值
          </Button>
        </Flex>
      }
    >
      {loading && <LoadingLine text="正在读 JVM 线程快照（虚拟线程计数要遍历所有线程，稍慢）……" />}

      {remote.status === 'error' && <InlineError title="读取线程账目失败" message={remote.error} />}

      {remote.status === 'ok' && <JvmStatsBody stats={remote.data} />}
    </Card>
  )
}

function JvmStatsBody({ stats }: { stats: JvmStats }) {
  return (
    <Flex vertical gap={10}>
      <div className="stat-grid">
        <Stat label="平台线程数" value={stats.livePlatformThreads} />
        <Stat label="平台线程峰值" value={stats.peakPlatformThreads} />
        <Stat label="守护线程数" value={stats.daemonThreads} />
        <Stat label="虚拟线程数" value={stats.liveVirtualThreads} accent />
        <Stat label="当前并发 inFlight" value={stats.inFlight} />
        <Stat label="并发峰值" value={stats.peakInFlight} />
        <Stat label="累计请求数" value={stats.totalRequests} />
        <Stat label="可用核数" value={stats.availableProcessors} />
      </div>

      <Typography.Text type="secondary" className="diag-note">
        三组数字的口径必须分清，否则会得出相反结论：<b>平台线程数</b>来自 ThreadMXBean，只统计操作系统线程 ——
        <b>虚拟线程不计入其中</b>，这正是要证明的事；<b>虚拟线程数</b>是遍历所有线程数出来的，开销不小，
        所以只在诊断端点手动取；<b>并发峰值</b>是本服务自己数的业务口径，与线程数无关，用来确认"压测真的压上去了"。
        配置的模式是 {stats.configuredMode}，platform 模式下线程池大小为 {stats.platformPoolSize}。
      </Typography.Text>

      <Typography.Text type="secondary" className="diag-note">
        重置峰值只清掉"并发峰值 / 累计请求数"这类单调递增的计数 —— 连续做两组对照实验时，
        上一组的峰值会把这一组顶住，看着像"这一组也压到了同样的并发"，其实是上一组留下的。
      </Typography.Text>
    </Flex>
  )
}

// ---------------------------------------------------------------------------
//  3. 载体线程实验（并发数 ≫ 线程数）
// ---------------------------------------------------------------------------

function CarrierExperimentCard({
  remote,
  onRun,
  busy,
}: {
  remote: Remote<CarrierExperiment>
  onRun: () => void
  busy: boolean
}) {
  return (
    <Card
      size="small"
      className="diag-card"
      title="载体线程实验：N 个虚拟线程复用几个载体线程"
      extra={
        <Button
          size="small"
          type="primary"
          onClick={onRun}
          loading={remote.status === 'loading'}
          disabled={busy}
        >
          跑一次
        </Button>
      }
    >
      <Typography.Text type="secondary" className="diag-note">
        并发 {CARRIER_TASKS} 个任务，每个阻塞 {CARRIER_SLEEP_MS} ms。虚拟线程不是 1:1 映射到操作系统线程的：
        它在阻塞时从 ForkJoinPool 上"下车"，就绪时再"上车"，所以 N 个虚拟线程会轮流用少数几个载体线程。
      </Typography.Text>

      {remote.status === 'idle' && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没跑过，点右上角「跑一次」" />}

      {remote.status === 'loading' && (
        <LoadingLine text={`正在让 ${CARRIER_TASKS} 个任务同时阻塞并采样载体线程……`} />
      )}

      {remote.status === 'error' && <InlineError title="载体线程实验失败" message={remote.error} />}

      {remote.status === 'ok' && <CarrierBody data={remote.data} />}
    </Card>
  )
}

function CarrierBody({ data }: { data: CarrierExperiment }) {
  const reuse = data.virtualThreads > data.distinctCarriers
  // 按载体线程名归组：这一栏存在的意义就是"看出几个虚拟线程挤在同一个载体上"，
  // 平铺的样本表看不出这件事
  const byCarrier = new Map<string, number>()
  data.samples.forEach(sample => {
    byCarrier.set(sample.carrier, (byCarrier.get(sample.carrier) ?? 0) + 1)
  })

  /*
   * 实测后端返回的 virtualThread 是**空字符串**：ForkJoinPool 里那些虚拟线程是
   * Executors.newVirtualThreadPerTaskExecutor() 造的，而这个执行器不给线程起名
   * （Thread#getName() 返回空串，JDK 内部用的名字形如 "" + 一个空字符 + 序号，
   * 传到 JSON 里也被吃掉）。所以这里不能把线程名当标识用，有两条硬约束：
   *   1. 拿它当 React key 的话，16 个样本全是同一个 key（空串）→ 控制台会报重复 key
   *      警告，而这个项目的自检脚本是把任何 console 警告都算失败的；
   *   2. 直接渲染成标签的话，那一行会是 16 个空框，看着像界面坏了。
   * 结论：**样本自己不能当条目**，只能计数。复用证据仍然完整 ——
   * 16 个任务落在 4 个载体线程上这件事，用"每个载体背了几个任务"表达得更清楚。
   */
  const named = data.samples.every(sample => sample.virtualThread.trim() !== '')

  return (
    <Flex vertical gap={14}>
      <div className="vt-mux">
        <div className="vt-mux-group">
          <div className="vt-mux-label">虚拟线程（任务）</div>
          <div className="vt-chips">
            <span className="vt-chip is-virtual">共 {data.virtualThreads} 个</span>
            <span className="vt-chip is-plain">
              {named ? '创建时带了名字' : 'JDK 默认执行器不命名，getName() 返回空串'}
            </span>
          </div>
        </div>

        <div className="vt-mux-arrow">
          {data.virtualThreads} 个虚拟线程被调度到 {data.distinctCarriers} 个载体线程上 ↓
        </div>

        <div className="vt-mux-group">
          <div className="vt-mux-label">载体线程（ForkJoinPool worker）与它背的任务数</div>
          <div className="vt-chips">
            {[...byCarrier.entries()].map(([carrier, count]) => (
              // 兜底：载体名理论上不会是空串（它总是 ForkJoinPool-1-worker-N），
              // 但真为空时把多个 key 撞在一起会让 React 打重复 key 警告 —— 一行 `||` 买不起不写它的风险
              <span key={carrier || '(载体名未知)'} className="vt-chip is-carrier">
                {carrier || '(载体名未知)'} · {count} 个任务
              </span>
            ))}
          </div>
        </div>
      </div>

      <div className="stat-grid">
        <Stat label="虚拟线程（任务数）" value={data.virtualThreads} accent />
        <Stat label="用到的载体线程数" value={data.distinctCarriers} accent />
        <Stat label="载体并行度上限" value={data.carrierParallelism} />
        <Stat label="每个任务阻塞" value={`${data.sleepMs} ms`} />
      </div>

      <Alert
        type={reuse ? 'success' : 'warning'}
        showIcon
        title={
          reuse
            ? `${data.virtualThreads} 个虚拟线程只用到了 ${data.distinctCarriers} 个载体线程`
            : `这次用到了 ${data.distinctCarriers} 个载体线程，没有观察到明显复用`
        }
        description={
          reuse
            ? '并发数远大于线程数 —— 这就是虚拟线程把"线程等 CPU"换成"任务等 CPU"的方式，也是它和线程池最本质的区别：线程池是任务等线程，虚拟线程是线程等 CPU。'
            : '载体线程数是 CPU 核数级别，任务要明显多于它才看得出复用；把任务数调大再跑一次。'
        }
      />

      <div>
        <Typography.Text type="secondary" className="diag-note">
          逐条采样（虚拟线程名 → 它当时跑在哪个载体线程上）：
        </Typography.Text>
        <div className="sample-list">
          {data.samples.map((sample, index) => (
            // key 只能用下标：样本里唯一的"标识"（线程名）实测是空串，用它当 key 等于全表同一个 key
            <div key={index} className="sample-row">
              <span className="sample-cell is-virtual">
                样本 {index + 1}
                {sample.virtualThread.trim() ? `（${sample.virtualThread}）` : '（虚拟线程未命名）'}
              </span>
              <span className="sample-arrow">→</span>
              <span className="sample-cell is-carrier">{sample.carrier}</span>
            </div>
          ))}
        </div>
      </div>

      <Typography.Text type="secondary" className="diag-note">
        {data.note}
      </Typography.Text>
    </Flex>
  )
}

// ---------------------------------------------------------------------------
//  4. 钉住实验（本页最值钱的一块）
// ---------------------------------------------------------------------------

function PinningExperimentCard({
  remote,
  onRun,
  busy,
}: {
  remote: Remote<PinExperiment>
  onRun: () => void
  busy: boolean
}) {
  return (
    <Card
      size="small"
      className="diag-card"
      title="钉住实验：synchronized 会毁掉虚拟线程的并发能力"
      extra={
        <Button
          size="small"
          type="primary"
          onClick={onRun}
          loading={remote.status === 'loading'}
          disabled={busy}
        >
          跑对照实验
        </Button>
      }
    >
      <Typography.Text type="secondary" className="diag-note">
        两组实验的占位任务<b>数量、时长、持锁行为完全相同</b>，唯一差别是阻塞方式。测的都是同一个指标——
        探针延迟：「提交一个空任务 → 它真正开始执行」的最坏延迟，也就是用户感知到的排队时间。
      </Typography.Text>

      {remote.status === 'idle' && (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没跑过，点右上角「跑对照实验」" />
      )}

      {remote.status === 'loading' && (
        <LoadingLine
          text={`正在跑两组对照实验（每组 ${PIN_HOLDERS} 个占位任务，每个持有 ${PIN_HOLD_MS} ms，分批投放并连续探针取最大值；synchronized 那组要把载体线程一个个耗完，所以这组是整页最慢的一次点击）……`}
        />
      )}

      {remote.status === 'error' && <InlineError title="钉住实验失败" message={remote.error} />}

      {remote.status === 'ok' && <PinBody data={remote.data} />}
    </Card>
  )
}

function PinBody({ data }: { data: PinExperiment }) {
  const monitorMs = parseLatency(data.probeLatencyWithSynchronized)
  const lockMs = parseLatency(data.probeLatencyWithReentrantLock)
  // 柱子长度按两者中的大值等比铺开，最小取 1 防止除以 0（对照组实测常常就是 0ms）
  const scale = Math.max(monitorMs, lockMs, 1)
  // 与后端 verdict 的判定阈值保持一致（> max(20, 锁路径 × 5)），否则会出现"文案说没钉住、
  // 颜色却是红的"这种自相矛盾的组合
  const pinned = monitorMs > Math.max(20, lockMs * 5)
  // 对照组是 0ms 时"慢多少倍"没有定义（除以 0），这时只给绝对差值，不编一个倍数出来
  const ratio =
    pinned && lockMs >= 5 ? `约 ${Math.round(monitorMs / lockMs)} 倍` : pinned ? `多等 ${monitorMs} ms` : null

  return (
    <Flex vertical gap={14}>
      <Row gutter={[12, 12]}>
        <Col xs={24} md={12}>
          <div className="pin-lane is-bad">
            <div className="pin-lane-head">
              <span className="pin-lane-title">synchronized</span>
              <Tag color="volcano">会钉住载体线程</Tag>
            </div>
            <div className="pin-latency">{data.probeLatencyWithSynchronized}</div>
            <div className="pin-bar-track">
              <div className="pin-bar is-bad" style={{ width: `${Math.max((monitorMs / scale) * 100, 2)}%` }} />
            </div>
            <Typography.Text type="secondary" className="pin-lane-note">
              在 synchronized 块里阻塞时，虚拟线程无法从载体线程上卸载 —— 载体线程被一起钉住。
            </Typography.Text>
          </div>
        </Col>

        <Col xs={24} md={12}>
          <div className="pin-lane is-good">
            <div className="pin-lane-head">
              <span className="pin-lane-title">ReentrantLock</span>
              <Tag color="green">可卸载</Tag>
            </div>
            <div className="pin-latency">{data.probeLatencyWithReentrantLock}</div>
            <div className="pin-bar-track">
              <div className="pin-bar is-good" style={{ width: `${Math.max((lockMs / scale) * 100, 2)}%` }} />
            </div>
            <Typography.Text type="secondary" className="pin-lane-note">
              这是 park 型阻塞：虚拟线程让出载体线程，载体立刻可以接下一个任务。
            </Typography.Text>
          </div>
        </Col>
      </Row>

      <Alert
        type={pinned ? 'error' : 'warning'}
        showIcon
        title={
          <Flex align="center" gap={8} wrap>
            <span>{data.verdict}</span>
            {ratio && <Tag color="volcano">{ratio}</Tag>}
          </Flex>
        }
        description={
          <Flex vertical gap={6}>
            <span>
              两组实验的其他条件完全一样：各 {data.holders} 个占位任务，每个持有 {data.holdMsEach} ms，
              载体的并行度上限是 {data.carrierParallelism}（= CPU 核数）。所以延迟差异只能归因于阻塞方式本身。
            </span>
            <span>
              数字的含义是"提交一个空任务 → 它真正开始执行"的最坏延迟，
              也就是用户在这条链路上多等的时间。对照组量到 0ms 是正常结果：
              它说明载体线程根本没被占住，探针立刻就跑上了。
            </span>
            <span>
              <b>要修的地方：</b>
              {data.fix}
            </span>
          </Flex>
        }
      />

      <Typography.Text type="secondary" className="diag-note">
        {data.note}
      </Typography.Text>
    </Flex>
  )
}

/** 后端把延迟写成 "800ms" 这样的字符串（前端不重复实现它的单位约定，只把数字取出来画长度） */
function parseLatency(text: string): number {
  const n = Number.parseFloat(text)
  return Number.isFinite(n) ? n : 0
}

// ---------------------------------------------------------------------------
//  5. Token 预算账目
// ---------------------------------------------------------------------------

function BudgetCard({ config, onReload }: { config: DiagConfig | null; onReload: () => void }) {
  return (
    <Card
      size="small"
      className="diag-card diag-card-wide"
      title="Token 预算账目：窗口是怎么被切开的"
      extra={
        <Button size="small" onClick={onReload}>
          重新读取配置
        </Button>
      }
    >
      {config === null ? (
        <Flex vertical gap={12}>
          <InlineError
            title="还没拿到配置"
            message="预算数字全部来自后端的当前生效配置，这里不会用前端的估算值代替真实配置 —— 两边对不上就成了新的排查负担。"
          />
          <Flex>
            <Button size="small" onClick={onReload}>
              重试读取
            </Button>
          </Flex>
        </Flex>
      ) : (
        <BudgetBody config={config} />
      )}
    </Card>
  )
}

function BudgetBody({ config }: { config: DiagConfig }) {
  const window = Math.max(config.contextWindow, 1)
  const reservedForOutput = Math.max(0, config.usableBudget - config.inputAllowance)
  const marginTokens = Math.max(0, window - config.usableBudget)
  const pct = (n: number) => `${(n / window) * 100}%`

  return (
    <Flex vertical gap={14}>
      <div className="budget-bar" role="img" aria-label="上下文窗口的切分">
        {/* 三段按真实 token 数按比例铺开：安全边际 / 为输出预留 / 留给输入 */}
        <div className="budget-seg is-margin" style={{ width: pct(marginTokens) }} />
        <div className="budget-seg is-output" style={{ width: pct(reservedForOutput) }} />
        <div className="budget-seg is-input" style={{ width: pct(config.inputAllowance) }} />
      </div>

      <Flex gap={16} wrap className="budget-legend">
        <LegendItem className="is-input" label="留给输入" value={config.inputAllowance} />
        <LegendItem className="is-output" label="为输出预留" value={reservedForOutput} />
        <LegendItem className="is-margin" label="安全边际" value={marginTokens} />
      </Flex>

      <div className="stat-grid">
        <Stat label="上下文窗口" value={config.contextWindow} />
        <Stat label="安全边际比例" value={formatMargin(config.safetyMargin)} />
        <Stat label="可用预算" value={config.usableBudget} accent />
        <Stat label="输入上限" value={config.inputAllowance} accent />
        <Stat label="输出预留上限" value={config.maxOutputTokens} />
        <Stat label="预算校验开关" value={String(config.budgetEnabled)} />
        <Stat label="替身延迟" value={config.standInActive ? config.samplingLatency : '未启用'} />
        <Stat label="执行模式" value={config.asyncExecutor} />
      </div>

      <Typography.Text type="secondary" className="diag-note">
        这条算式是学习点 2 的全部内容：<b>contextWindow × (1 - safetyMargin) ≥ 估算输入 + 保留输出</b>。
        左边=可用预算 {config.usableBudget}，右边=这次要花的额度。之所以要在发请求<b>之前</b>算：
        自己算是一次本地计算（微秒级、零成本），等模型报错则要先花一次往返的钱和几秒，
        更糟的是有些供应商不报错而是<b>静默截断</b>输入 —— 那样你会在"答案为什么变差了"上排查半天。
      </Typography.Text>

      <Typography.Text type="secondary" className="diag-note">
        安全边际不能是 0：token 估算永远是估算（不同模型分词器不同），脚手架（system 提示词、
        消息角色包装、工具定义）也要占窗口，供应商还可能悄悄改默认值。留小了会偶发超窗，
        留大了会早早拒绝本来能过的请求。当前窗口 {config.contextWindow}，
        容器虚拟线程开关 {String(config.containerVirtualThreads)}。
      </Typography.Text>

      {/*
        替身是个容易被误读的状态：开着它时"模型"其实在睡固定时长、回一句固定文本。
        不说清楚的话，下面非流式对话那一块的耗时和内容都会被当成真实模型的表现
      */}
      {config.standInActive && (
        <Alert
          type="info"
          showIcon
          title={`压测替身已开启，延迟 ${config.samplingLatency}，回答是一段固定文本`}
          description="替身不调用真实模型：压测做对照实验时，上游抖动会污染两组的数字，而这类对比最需要的恰恰是「两次实验除线程模型之外完全一样」。所以下面非流式对话里看到的耗时和内容反映的是替身，不是模型。"
        />
      )}
    </Flex>
  )
}

function LegendItem({ className, label, value }: { className: string; label: string; value: number }) {
  return (
    <Flex align="center" gap={6}>
      <span className={`budget-dot ${className}`} />
      <Typography.Text type="secondary">
        {label} <Typography.Text code>{value}</Typography.Text> token
      </Typography.Text>
    </Flex>
  )
}

function formatMargin(margin: number): string {
  return `${(margin * 100).toFixed(1)}%`
}

/**
 * 给非流式对话卡片用的一行预算摘要。
 *
 * 为什么是两个卡片之间复制的一行字，而不是让对话卡片自己去读配置：
 * 这次请求能用的输入上限完全由配置决定（且是后端算的），把这一行摆在输入框上方，
 * 用户"发这条消息"和"它最多能用多少 token"就在同一个视野里；
 * 而配置请求本身归预算卡片管，一个 hook 只发一次，不重复打后端。
 */
function budgetSummary(config: DiagConfig | null): string | null {
  if (!config) return null
  return `可用预算 ${config.usableBudget} · 留给输入 ${config.inputAllowance} · 为输出预留 ${config.maxOutputTokens} · 安全边际 ${formatMargin(config.safetyMargin)}`
}

// ---------------------------------------------------------------------------
//  6. 非流式对话（async 路径的活体验证）
// ---------------------------------------------------------------------------

function AsyncCallCard({
  turns,
  onSend,
  configSummary,
}: {
  turns: AsyncTurn[]
  onSend: (message: string, mode: AsyncMode) => void
  configSummary: string | null
}) {
  const [text, setText] = useState('')
  const [mode, setMode] = useState<AsyncMode>('async')
  const busy = turns.some(t => t.loading)

  const submit = () => {
    const content = text.trim()
    if (!content || busy) return
    onSend(content, mode)
    setText('')
  }

  return (
    <Card
      size="small"
      className="diag-card diag-card-wide"
      title="非流式对话：预算账目在这条链路上真的跑过"
      extra={
        <Flex gap={8} align="center" wrap>
          <Segmented
            size="small"
            value={mode}
            onChange={v => setMode(v as AsyncMode)}
            options={[
              { label: '/api/chat/async', value: 'async' },
              { label: '对照组 /sync', value: 'sync' },
            ]}
          />
          <Button size="small" type="primary" onClick={submit} disabled={!text.trim() || busy} loading={busy}>
            发送
          </Button>
        </Flex>
      }
    >
      <Flex vertical gap={12}>
        <Input.TextArea
          value={text}
          onChange={e => setText(e.target.value)}
          placeholder="发一条消息…（Enter 发送，Shift+Enter 换行）"
          autoSize={{ minRows: 2, maxRows: 6 }}
          disabled={busy}
          onPressEnter={e => {
            // 中文输入法里 Enter 还承担「上屏候选词」，不判断 isComposing 会在选词时误发送半截话
            if (e.shiftKey || e.nativeEvent.isComposing) return
            e.preventDefault()
            submit()
          }}
        />

        <Typography.Text type="secondary" className="diag-note">
          这里调的是前两个模式之外的另一条链路 —— 非流式端点。它和流式端点的线程模型完全不同：
          <b>流式</b>返回 Flux，等 token 的时候没有线程被占用；<b>/api/chat/async</b> 是同步阻塞的调用，
          一个请求全程占一个线程干等，所以才需要挪到虚拟线程上（并发上限从此不再等于线程数）。
          <b>对照组 /sync</b> 和它同进程、同配置、同一份业务代码，唯一差别是阻塞发生在哪个线程上。
          后端在进入执行器之前会先把这次请求的 token 预算算一遍并记进日志，
          所以这条链路是"预算账目真的在跑"的活体验证。
        </Typography.Text>

        {/* 把"这次请求最多能用多少 token"摆在输入框下面：发消息和它的预算上限在同一个视野里 */}
        {configSummary && <div className="budget-strip">{configSummary}</div>}

        {/*
          会话归属不是一句废话：面板借用的就是当前对话模式那个 conversationId，
          所以这里的消息会接进同一条对话记忆（换会话之后再发就是新的一条）。
          把 ID 露出来是因为它是"借来的"证据 —— 但每发一条消息都会重新取一次当前会话，
          所以这一行不是"面板的固定会话"，而是"这次会写进哪条记忆"。
        */}
        {turns.length > 0 && (
          <Typography.Text type="secondary" className="diag-note">
            这批记录写进的是
            {turns[turns.length - 1].conversationId ? (
              <Typography.Text code>{turns[turns.length - 1].conversationId.slice(0, 8)}…</Typography.Text>
            ) : (
              '（还没有会话，后端会为这次请求起一条新的）'
            )}
            这条对话记忆 —— 面板借用的是当前对话模式的 conversationId，所以两边看到的是同一段上下文。
          </Typography.Text>
        )}

        {turns.length === 0 && (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description="还没有调用记录。发一条试试（会真的打后端；若替身已开启则不会消耗模型额度）"
          />
        )}

        {turns.map(turn => (
          <AsyncTurnCard key={turn.id} turn={turn} />
        ))}
      </Flex>
    </Card>
  )
}

function AsyncTurnCard({ turn }: { turn: AsyncTurn }) {
  return (
    <div className="async-turn">
      <Flex align="center" gap={8} wrap>
        <Tag color={turn.mode === 'async' ? 'blue' : 'default'}>
          {turn.mode === 'async' ? '虚拟线程路径' : '对照组（容器线程）'}
        </Tag>
        <Typography.Text type="secondary" className="async-question">
          问：{turn.message}
        </Typography.Text>
        {turn.elapsedMs !== undefined && (
          <Typography.Text code className="async-elapsed">
            耗时 {turn.elapsedMs} ms
          </Typography.Text>
        )}
      </Flex>

      {turn.loading && <LoadingLine text="正在等模型返回（这条路径同步等，等多久就占多久）……" />}

      {turn.error && <InlineError title="调用失败" message={turn.error} />}

      {turn.text !== undefined && <div className="plain-text async-answer">{turn.text}</div>}

      {turn.elapsedMs !== undefined && (
        <Typography.Text type="secondary" className="diag-note">
          耗时是从前端发起请求量到响应体读完的墙钟时间。
          要对比两种线程模型的吞吐量别用这个数字 —— 单次请求的延迟由上游决定，
          阻塞式设计的问题只在<b>高并发</b>时表现为"任务排队"，那要靠压测脚本并发打这两个端点来看。
        </Typography.Text>
      )}
    </div>
  )
}

// ---------------------------------------------------------------------------
//  小部件
// ---------------------------------------------------------------------------

/** 一行键值对，等宽字体：这些值（线程名、线程 ID）是要拿去和日志对照的，得能逐字符读 */
function Kv({ label, value, mono }: { label: string; value: string | number; mono?: boolean }) {
  return (
    <div className="kv-item">
      <dt className="kv-label">{label}</dt>
      <dd className={`kv-value ${mono ? 'is-mono' : ''}`}>{value}</dd>
    </div>
  )
}

function Stat({ label, value, accent }: { label: string; value: string | number; accent?: boolean }) {
  return (
    <div className={`stat-cell ${accent ? 'is-accent' : ''}`}>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
    </div>
  )
}

function LoadingLine({ text }: { text: string }) {
  return (
    <Flex align="center" gap={10} className="diag-loading">
      <Spin size="small" />
      <Typography.Text type="secondary">{text}</Typography.Text>
    </Flex>
  )
}

/** 内联错误：每个部件自己承担"失败了"的表达，不让失败静默变成空白 */
function InlineError({ title, message }: { title: string; message: string }) {
  return <Alert type="error" showIcon title={title} description={message} />
}
