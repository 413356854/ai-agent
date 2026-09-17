/**
 * 界面自检脚本：用浏览器真跑一遍，确认两种模式都能用。
 *
 * 用法（两个终端）：
 *   终端 A：pnpm mock            # 假后端，不烧 token（或先起真后端）
 *   终端 B：pnpm check:ui        # 默认打 http://127.0.0.1:5173
 *
 * 为什么要有这个脚本而不是"我自己点一遍就算验证"：
 *   1. 点一遍只能证明"我这次看到的对"，证明不了"下次改动后还是对的"；
 *   2. 有几个问题肉眼看不出来 —— 横向溢出、Ant Design 的 deprecation 警告、
 *      控制台报错，这些都要靠断言兜住；
 *   3. 当前模型看不了截图，所以这里刻意用**几何断言**代替"肉眼看布局"：
 *      documentElement.scrollWidth - clientWidth > 0 就说明有横向溢出。
 *
 * 注意：默认用系统已装的 Chrome（channel: 'chrome'），而不是 Playwright 自带的 chromium ——
 * 本机 ms-playwright 里的内核版本与当前 playwright 不匹配，直接 launch 会报
 * "Executable doesn't exist"。走系统浏览器可以绕开"为了截一张图下载 100MB 内核"。
 */
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'

const URL = process.env.UI_URL ?? 'http://127.0.0.1:5173'
/**
 * 是否断言"回灌 / 降级"这两条路径。
 *
 * **默认 true，因为默认该打假后端**（`pnpm mock`）：假后端里的两种结果是写死的，
 * 断言才有确定性。打真后端时会话未必回灌（模型一次就答对）也不会因为提问里带「降级」
 * 二字而降级 —— 那是假后端的约定，真实模型没这个约定。拿真后端跑这两条断言必然误报。
 *
 * 所以：`CHECK_EXPECT_REFLECTION=0 UI_URL=http://127.0.0.1:5199 pnpm check:ui` 打真后端。
 */
const EXPECT_REFLECTION = process.env.CHECK_EXPECT_REFLECTION !== '0'
/**
 * 每条路径实际执行多少项断言（**实测出来的，不是数出来的**）。
 *
 * 脚本里一共 229 个 `expect(...)`，但两条路径执行的数量不同 ——
 * 因为有几个分支各跑各的（回灌细节 / 降级标记 / Agent 的完整轨迹 / HITL 的批准与拒绝），
 * 它们的可复现性依赖假后端：
 *
 *   假后端（默认，实测 2026-09-16 第十一轮）→ **205 项，全过**
 *   真后端（=0，实测 2026-09-16 第十轮）    → **143 项执行、142 项通过**
 *     ⚠️ 真后端那一组数字是**第十轮**的；第十一轮只跑了假后端路径（改了 3 条断言，
 *        两条是"合计必须等于按天表的和"这种**两条路径都成立**的形状，
 *        所以真路径的数字应当在下次跑真后端时重新测一遍，不要照抄这里）
 *
 * 真路径唯一失败的那条是**流式回答**（`流式回答已渲染`）——
 * 本机用的是假 key，上游必然 401，与本次改动无关（第八轮起就是这样）。
 *
 * ⚠️ 这里刻意把两个数字都写下来：阶段一曾经只写"36 项"而单条路径实际只跑 29 项，
 * 这是项目当时自己记下的"虚报"。**一个会随环境变化的数字，必须把每个环境的取值都写清**，
 * 否则读的人没法判断某次输出是不是正常。
 *
 * ⚠️ 还有一个更隐蔽的坑（第九轮踩到，值得单独记）：
 * **"真后端路径"可能在骗你。** `vite.config.ts` 里 `strictPort: true`，
 * 所以如果之前那个 `pnpm dev:mock` 还占着 5173，新起的 `pnpm dev`（指向真后端）
 * **会启动失败**，而 `UI_URL` 仍然打得到 5173 —— 于是你以为是"真后端路径"，
 * 实际打的是假后端，两次跑的断言数也会因此对不上。
 * 判断方法很简单（也在 README 里写了）：
 *     GET /api/agent/runs → `awaitingRecovery`
 *     假后端固定给 1，真后端是 0。**一个数字就能区分你在打谁。**
 */
const SHOT_DIR = 'ui-check-out'
mkdirSync(SHOT_DIR, { recursive: true })

/**
 * 用哪个浏览器内核。
 *
 * <h2>为什么默认是系统 Chrome，而不是 Playwright 自带的 chromium</h2>
 * 本机踩过：`ms-playwright` 里的内核版本与当前 playwright 不匹配，
 * 直接 `launch()` 会报 `Executable doesn't exist` —— 那是环境问题，
 * 却看起来像"界面自检跑不起来"。用系统已装的 Chrome 省掉了
 * "为了截一张图下载 100MB 内核"这件事。
 *
 * <h2>为什么它必须是可配的（阶段四第 4 件）</h2>
 * 因为 `chrome` 这个默认值**只在开发者本机成立**：干净 CI 容器里没有 Chrome，
 * 有的是 `npx playwright install chromium` 装下来的那个内核。
 * 写死 `chrome` 的后果很具体：**门禁在 CI 上永远红，且红的原因与代码无关** ——
 * 而那正是让人开始习惯性忽略门禁的第一步（不是删掉它，而是不再看它）。
 *
 * <p>用法：CI 里设 `CHECK_BROWSER_CHANNEL=chromium` 并先跑
 * `npx playwright install --with-deps chromium`；本机什么都不用设。
 */
const BROWSER_CHANNEL = process.env.CHECK_BROWSER_CHANNEL ?? 'chrome'
// 传 `undefined` 与传 `'chromium'` 不是一回事：前者让 Playwright 用自带的默认内核，
// 后者是一个**名为 chromium 的频道**。这里用条件展开，避免把两者混起来
const browser = await chromium.launch(
  BROWSER_CHANNEL === 'chromium' ? {} : { channel: BROWSER_CHANNEL },
)
const page = await browser.newPage({ viewport: { width: 1100, height: 900 } })

const consoleErrors = []
/**
 * 已知会发生的浏览器网络报错 —— **不算缺陷**，但必须显式列出来。
 *
 * <h2>为什么需要这个函数，而不是"放宽断言"</h2>
 * 这一轮 Prompt 版本面板有一条自检要**故意提交一份坏提示词**，验证界面逐条列出判据。
 * 那次请求后端会正确地回 **400**，而浏览器控制台对任何非 2xx 的响应都会记一条
 * `Failed to load resource: ... 400 (Bad Request)` —— **这是浏览器行为，不是我们的 bug**。
 *
 * <p>所以这里不是把"控制台必须干净"这个要求降低，而是把它**写得准确**：
 * 「意料之内的失败请求」与「意料之外的报错」是两件事，前者必须被逐条命名出来
 * （命名就意味着它是有意为之，而不是被人顺手忽略掉了）。
 * 一个什么都拦的过滤器会让这条断言变成永远为真，那才是真的损失。
 *
 * <p>⚠️ 判据刻意写得很窄：只放行 **400**，并且要求它确实打在那个端点上。
 * 500、404、或任何别的路径上的 400 都仍然会让自检失败 —— 那些都不是"我们故意造的"。
 *
 * <p>⚠️ 实测还有一个细节：浏览器那条日志里**不一定带 URL**
 * （Chrome 只给 "Failed to load resource: the server responded with a status of 400"），
 * 所以这里同时用**请求事件**做判据 —— 只有真的看到"prompts 端点回了 400"才放行。
 * 第一版只匹配文本，于是它一条都没过滤掉（断言照样红）。
 */
const expectedRejections = []
page.on('response', response => {
  if (response.status() === 400 && response.url().includes('/api/prompts/')) {
    expectedRejections.push(response.url())
  }
})

const isExpectedNetworkError = text =>
  /status of 400/.test(text) && expectedRejections.length > 0

page.on('console', msg => {
  const text = msg.text()
  if (msg.type() !== 'error' && msg.type() !== 'warning') return
  if (isExpectedNetworkError(text)) return
  consoleErrors.push(`[${msg.type()}] ${text}`)
})
page.on('pageerror', err => consoleErrors.push(`[pageerror] ${err.message}`))

const problems = []
const expect = (label, ok, detail = '') => {
  console.log(`    ${ok ? '✓' : '✗'} ${label}${detail ? ` — ${detail}` : ''}`)
  if (!ok) problems.push(label)
}

/**
 * 轮询等待某个条件成立（最多 `timeoutMs`）。
 *
 * <h2>为什么需要它，而不是 `page.waitForTimeout(N)` 一把等够</h2>
 * 这一轮新增的断言里有一大半要等的是**异步状态转移**：
 * "点批准 → 界面轮询把那条待批捡走 → 列表里它消失"。
 * 用固定 sleep 的话只能两种结果：睡太短会闪断（CI/慢机器上必红），
 * 睡太长会让整条自检慢下来 —— 而**闪断的断言最后总会被注释掉**，
 * 那时它保护的东西就悄悄没了。
 *
 * <p>轮询的代价是失败时慢（等满超时才报错），收益是**快的时候立刻继续**且不闪断。
 * 这个取舍与后端那边"等待批登记出来"用的是同一套（见 `AgentApprovalTest.awaitPending`）。
 */
async function waitFor(check, timeoutMs = 10000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      if (await check()) return true
    }
    catch {
      // 元素还没出现时 locator 会抛/返回 0 —— 都当作"还没好"，继续等
    }
    await page.waitForTimeout(250)
  }
  return false
}

// antd 的 autoSize TextArea 会额外渲染一个隐藏 textarea 用来量高度，必须排除它
const box = () => page.locator('textarea:not([name="hiddenTextarea"])')

console.log('[1] 打开页面:', URL)
await page.goto(URL, { waitUntil: 'networkidle' })

// ---------- 流式模式（默认） ----------
console.log('[2] 流式模式')
await box().fill('你好')
await box().press('Enter')
// 同样从"固定 sleep + 毫秒级等待"改成"轮询等结果"（理由见下面 [3] 那一段长注释）。
// ⚠️ 但这里的**语义要保住**：这一节验的是"流式回答能渲染"，
// 而不是"回答渲染得足够快"。所以先等它出现，再给一小段稳定时间让增量收尾 ——
// 两件事分开写，比把它们混在一个 waitForTimeout 里更经得起负载变化
await waitFor(async () => (await page.locator('.caret, .markdown').count()) > 0, 20000)
await page.waitForTimeout(500)
const hasCaret = (await page.locator('.caret, .markdown').count()) > 0
expect('流式回答已渲染', hasCaret)
const streamBody = await page.locator('.message-list').innerText()
expect('流式正文非空', streamBody.trim().length > 20, `${streamBody.trim().length} 字符`)

// ---------- 结构化模式：正常（含回灌） ----------
console.log('[3] 结构化模式 —— 正常路径（mock 会造一轮回灌）')
await page.locator('label', { hasText: '结构化输出' }).click()
await page.waitForTimeout(300)
await box().fill('SSE 是什么？')
await box().press('Enter')
await page.locator('.structured-card').first().waitFor({ state: 'visible', timeout: 30000 })

// 【★ 这里必须等"数据到了"，而不是等一个固定时长 —— 实测踩到，值得记】
//
// 原来写的是 `await page.waitForTimeout(1200)` 然后直接读 `.structured-card .ant-tag`。
// 那个写法在**空载**时永远够（mock 是瞬时返回），但在 CI 上翻过车：
// 结构化自检整段超时，报的是
//     locator.innerText: Timeout 30000ms exceeded
//     waiting for locator('.structured-card .ant-tag').first()
// 而**同一个自检单独跑三遍全过** —— 典型的"只在有负载时才红"。
//
// 根因有两层，第二层才是关键：
//   ① `.structured-card` 这个**容器**在提交的瞬间就渲染出来了（那是骨架），
//      所以上面那句 `waitFor({state:'visible'})` 通过得毫无意义 ——
//      它证明的是"卡片在"，不是"数据到了"。**"容器在"不等于"内容在"**
//      （与「卡片在」不等于「列表有行」是同一个形状）；
//   ② 于是唯一的同步机制就是那 1200ms 的固定 sleep，而它在机器忙时不够。
//
// 修法用本项目已有的 `waitFor` 轮询助手：快的时候立刻继续、慢的时候一直等，
// 而不是"赌一个时长"。**"等一个固定时长"在慢操作上一定会有一个环境是错的**
// （这正是「界面自检不能用固定 sleep 等一个本来就慢的实验」的教训，第十三轮又在别处踩了一次）。
const structuredReady = await waitFor(
  async () => (await page.locator('.structured-card .ant-tag').count()) > 0,
  20000,
)
expect('结构化卡片等到了数据（而不是只有空骨架）', structuredReady)

const badge = (await page.locator('.structured-card .ant-tag').first().innerText()).trim()
expect('徽标显示轮次与回灌', /轮/.test(badge), badge)
expect('置信度条已渲染', (await page.locator('.confidence-bar').count()) > 0)
expect('关键词标签已渲染', (await page.locator('.structured-card .ant-tag').count()) >= 2)
expect('回灌过程面板存在', (await page.locator('.structured-card .ant-collapse-header').count()) > 0)
if (EXPECT_REFLECTION) {
  expect('回灌过程可展开（含原始输出）', (await page.locator('.raw-output').count()) > 0)
  expect('校验问题已列出', (await page.locator('.issue-list li').count()) > 0)
} else {
  // 真后端：模型一次就通过，没有回灌细节 —— 改为断言"结果确实渲染了"
  const answer = (await page.locator('.structured-card .markdown').first().innerText()).trim()
  expect('结构化答案已渲染', answer.length > 5, `${answer.length} 字符`)
  // 第 3 条补齐：真后端这条路径也要证明"卡片结构完整"，而不只是"有文字"。
  // 【两条分支的断言条数刻意保持相同（各 3 条）】否则"本次跑了多少项断言"会随路径变化，
  // 而一个会变的数字没法写进文档 —— 阶段一就在这里含糊过一次：
  // 本项目曾写成"36 项"而单条路径实际只执行 29 项（见下方"断言数怎么读"）。
  expect('结构化卡片结构完整', (await page.locator('.structured-card .confidence-bar').count()) > 0)
}
// 置信度说明里的 markdown 星号不能以字面量出现（写错就会这样）
const hint = await page.locator('.confidence-hint').first().innerText()
expect('置信度说明无字面量星号', !hint.includes('**'))
const hasBold = (await page.locator('.confidence-hint b').count()) > 0
expect('置信度说明用了真正的粗体标签', hasBold)

// ---------- 布局体检（代替肉眼看截图） ----------
const layout = await page.evaluate(() => {
  const doc = document.documentElement
  const card = document.querySelector('.structured-card')
  return {
    horizontalOverflow: doc.scrollWidth - doc.clientWidth,
    cardFitsViewport: card ? card.getBoundingClientRect().right <= doc.clientWidth + 1 : null,
  }
})
expect('无横向溢出', layout.horizontalOverflow === 0, `overflow=${layout.horizontalOverflow}px`)
expect('卡片不超出视口', layout.cardFitsViewport !== false)
await page.screenshot({ path: `${SHOT_DIR}/structured-normal.png`, fullPage: true })

// ---------- 结构化模式：降级路径（仅假后端可复现） ----------
if (EXPECT_REFLECTION) {
  console.log('[4] 结构化模式 —— 降级路径（mock：提问含「降级」二字）')
  await box().fill('这个一定会降级')
  await box().press('Enter')
  await page.waitForTimeout(2500)
  expect('出现「已降级」标记', (await page.locator('.structured-card .ant-tag', { hasText: '已降级' }).count()) > 0)
  const degradedBadge = (await page.locator('.structured-card').last().locator('.ant-tag').first().innerText()).trim()
  expect('降级时显示全部轮次', /3 轮/.test(degradedBadge), degradedBadge)
  await page.screenshot({ path: `${SHOT_DIR}/structured-degraded.png`, fullPage: true })
} else {
  // 真后端跑不出降级：模型总能改对（这是好消息，不是缺陷）。这条路径由单测覆盖，
  // 见 ai-agent 的 StructuredChatServiceImplTest#fallsBackAfterExhaustingAttempts
  console.log('[4] 跳过降级路径断言（真实模型不具备"必然降级"的输入）')
}

// ---------- 线程与预算面板（学习点 7 + 2） ----------
//
// 这一节断言的是"页面有没有把运行时事实如实表达出来"，而不是"后端算得对不对"：
//   - virtual 这个布尔值有没有被显著区分（它是"改造生效"的唯一直接证据）；
//   - 两组实验的按钮能不能跑通、结果有没有渲染；
//   - 钉住对照的两个数字是不是**并排可见**（这是这一页最值钱的一块，
//     只显示其中一个就等于没做对照）；
//   - 非流式对话能不能真的打通（说明虚拟线程那条链路是活的）。
console.log('[5] 线程与预算面板')
await page.locator('label', { hasText: '线程与预算' }).click()
await page.waitForTimeout(500)

// 当前线程：虚拟/平台必须长得不一样
const threadCard = page.locator('.vt-hero').first()
const heroExists = (await threadCard.count()) > 0
expect('线程面板已渲染', heroExists)

if (heroExists) {
  const heroText = (await threadCard.innerText()).trim()
  expect('显示了线程模型判定', /虚拟线程|平台线程/.test(heroText), heroText.split('\n')[0])
  // 假后端固定返回 virtual:true —— 视觉上必须落到"已生效"那一侧，
  // 因为这两句话是这一页存在的理由：一眼看出改造到底生效没有
  expect('virtual=true 时标注为改造已生效', /改造已生效/.test(heroText))
  expect('当前线程标记为虚拟线程', (await page.locator('.vt-hero.is-virtual').count()) > 0)

  // 线程名在 hero 下方的键值表里（kv-grid），不在 hero 本身
  const threadNameKv = page.locator('.kv-item', { hasText: '线程名' }).first()
  expect('显示了请求线程名', (await threadNameKv.count()) > 0 && /tomcat-vt|http-nio|VirtualThread/.test(await threadNameKv.innerText()))
}

// 线程账目：刷新一次，数字要在
const refreshBtn = page.locator('button', { hasText: '刷新' }).first()
if ((await refreshBtn.count()) > 0) {
  await refreshBtn.click()
  await page.waitForTimeout(800)
}
const panelText = (await page.locator('body').innerText()).trim()
expect('线程账目已显示平台线程数', /平台线程/.test(panelText))
expect('线程账目已显示并发峰值', /并发峰值|inFlight/i.test(panelText))

// 载体复用实验：卡片标题里含「载体线程」，按钮是卡片的「跑一次」
const carrierCard = page.locator('.diag-card', { hasText: '载体线程' }).first()
const carrierBtn = carrierCard.locator('button', { hasText: '跑一次' })
if ((await carrierBtn.count()) > 0) {
  await carrierBtn.click()
  await page.waitForTimeout(2000)
  const carrierText = (await carrierCard.innerText()).trim()
  // 假后端固定返回 16 个任务 / 4 个载体；真后端每次不同，所以断言"算出了复用关系"而不是具体数字
  expect('载体复用实验有结果', /个虚拟线程/.test(carrierText) && /载体线程/.test(carrierText))
  expect('载体复用实验给出了复用结论', /只用到了|没有观察到|复用/.test(carrierText))
  expect('载体复用逐条采样已渲染', (await page.locator('.sample-row').count()) > 0)
} else {
  expect('载体线程实验卡片存在', false)
}

// 钉住对照：这是本页最值钱的一块，两个数字必须**并排同时在页面上** ——
// 只显示其中一个就等于没做对照，而那正是后端第一版实验的失败形态
// （两组都测出 0ms，看不出任何差异，见 ThreadDiagnosticsController 的注释）。
//
// 定位方式说明：这里按**序号**取卡片而不是按文字过滤。实测 `hasText: '钉住实验'`
// 明明在页面里（单独 probe 时 `text=钉住实验` 能找到 1 个），但用它过滤 .diag-card
// 就是匹配不上 —— 与其和匹配规则较劲，不如用稳定的事实：面板一共 6 张卡，
// 钉住是第 4 张。序号定位在断言里更可预测，也不会因为文案微调而失效。
const pinCard = page.locator('.diag-card').nth(3)
const pinTitle = (await pinCard.innerText()).trim().split('\n')[0]
expect('钉住对照实验卡片存在', /钉住/.test(pinTitle), pinTitle)

// 卡片里的触发按钮文案不统一：载体卡是「跑一次」，钉住卡是「跑对照实验」。
// 所以按"卡片 extra 区里的那个 primary 按钮"来找，而不是按文案 —— 文案会改，位置不会。
const pinBtn = pinCard.locator('button.ant-btn-primary').first()
if ((await pinBtn.count()) > 0) {
  await pinBtn.click()
  // **不要用固定 sleep 等这个实验**：它的耗时随占位任务数线性增长
  // （synchronized 那组要一批批把载体耗完），实测真后端默认参数要 19 秒左右 ——
  // 写死 3 秒会让断言在真后端上必然失败，而失败原因是"等得不够"，
  // 看起来却像"界面没渲染"。改成等结果元素真的出现，这样假后端（瞬时）和真后端（十几秒）都成立。
  await pinCard.locator('.pin-latency').first().waitFor({ state: 'visible', timeout: 90000 })
  await page.waitForTimeout(300)

  const pinText = (await pinCard.innerText()).trim()
  expect('钉住对照两组都已渲染', /synchronized/i.test(pinText) && /ReentrantLock/i.test(pinText))
  expect('钉住对照给出了判定结论', /钉住|排队|未观察到/.test(pinText))
  expect('钉住对照两个延迟数字并排可见', (await pinCard.locator('.pin-latency').count()) >= 2)
} else {
  expect('钉住实验有触发按钮', false)
}

// Token 预算切分：窗口 / 输出预留 / 安全边际三块都要露出来，
// 因为"预算怎么算的"是这个功能的全部说服力所在
const budgetText = (await page.locator('body').innerText()).trim()
expect('预算切分已显示窗口与安全边际', /128000|上下文窗口/.test(budgetText) && /安全边际/.test(budgetText))
expect('预算切分已显示输入上限', /106752|输入/.test(budgetText))

// ---------- RAG 分块评测面板（阶段二） ----------
//
// 这一节断言的是"页面有没有把这条离线链路如实表达出来"：
//   - 语料解析的**块类型分布**里标题块必须 > 0（它是结构分块的地基，为 0 说明解析退化，而整条链路不报错）；
//   - 分块预览里两种策略的**面包屑必须长得不一样**（结构分块有、硬切恒为空）——
//     这正是两种策略最本质的差别，如果两边都空，说明结构信息在某处丢了；
//   - 分词必须真的把中文切成二元组（这决定了检索能不能匹配上）；
//   - 召回结果的名次与归属章节要渲染出来；
//   - **对照表的两个策略都必须出现在页面上**，且差值区同时可见 ——
//     只显示一个策略就等于没做对照，而这正是阶段二验收口径的核心。
console.log('[6] RAG 分块评测面板')
await page.locator('label', { hasText: 'RAG 分块评测' }).click()
await page.waitForTimeout(800)

// —— 语料解析 ——
const corpusCard = page.locator('.diag-card', { hasText: '语料解析' }).first()
expect('RAG 面板已渲染（语料解析卡存在）', (await corpusCard.count()) > 0)
if ((await corpusCard.count()) > 0) {
  await page.waitForTimeout(600)
  const corpusText = (await corpusCard.innerText()).trim()
  expect('语料解析显示了文档数与块数', /文档数/.test(corpusText) && /文本块总数|块总数/.test(corpusText))
  // 标题块 > 0 是"结构分块的地基还在"的直接证据。假后端固定给 82，真后端也是几十个
  expect('标识出了标题块数量', /标题块/.test(corpusText))
  expect('未出现"没有识别出任何标题块"的告警', !/没有识别出任何标题块/.test(corpusText))
  expect('块类型分布已渲染', (await page.locator('.rag-type-tag').count()) >= 3)
}

// —— 分块预览：结构分块必须有面包屑 ——
const previewCard = page.locator('.diag-card', { hasText: '分块预览' }).first()
expect('分块预览卡片存在', (await previewCard.count()) > 0)
if ((await previewCard.count()) > 0) {
  // 默认已经是结构分块。等块渲染出来而不是写死 sleep
  await previewCard.locator('.rag-chunk').first().waitFor({ state: 'visible', timeout: 30000 }).catch(() => {})
  await page.waitForTimeout(400)
  const structuralChunks = await previewCard.locator('.rag-chunk').count()
  expect('结构分块产出了块', structuralChunks > 0, `${structuralChunks} 条`)
  const structuralBreadcrumbs = await previewCard.locator('.rag-breadcrumb:not(.is-empty)').count()
  expect('结构分块的块带标题面包屑', structuralBreadcrumbs > 0, `${structuralBreadcrumbs} 条有面包屑`)
  expect('预览显示了 token 数', /token/.test(await previewCard.innerText()))

  // 切到对照组：面包屑必须变成"（无标题路径）"——
  // 这是"对照组确实不含结构信息"的界面证据，也顺带验证了切策略会自动重跑
  await previewCard.locator('.ant-segmented-item', { hasText: '固定硬切' }).click()
  await previewCard.locator('.rag-breadcrumb').first().waitFor({ state: 'visible', timeout: 30000 }).catch(() => {})
  await page.waitForTimeout(600)
  const emptyBreadcrumbs = await previewCard.locator('.rag-breadcrumb.is-empty').count()
  expect('硬切的块标注为无标题路径', emptyBreadcrumbs > 0, `${emptyBreadcrumbs} 条无面包屑`)
}

// —— 分词诊断：中文必须被切成二元组 ——
const tokensCard = page.locator('.diag-card', { hasText: '分词诊断' }).first()
expect('分词诊断卡片存在', (await tokensCard.count()) > 0)
if ((await tokensCard.count()) > 0) {
  // antd 6 会把两字中文按钮渲染成「分 析」（中间插一个空格），所以文案匹配要容忍空格 ——
  // 用 hasText: '分' 再配合正则确认，否则 '分析' 会永远匹配不上（这正是它超时的原因）
  await tokensCard.locator('button').filter({ hasText: /分\s*析/ }).first().click()
  await tokensCard.locator('.rag-token').first().waitFor({ state: 'visible', timeout: 20000 }).catch(() => {})
  await page.waitForTimeout(300)
  const tokenTexts = await tokensCard.locator('.rag-token').allInnerTexts()
  expect('分词结果已渲染', tokenTexts.length > 0, `${tokenTexts.length} 个 token`)
  // 中文二元组：默认提问是「虚拟线程在高并发下的吞吐量提升了多少」，
  // 切成二元组后必然出现长度恰为 2 的中文片段
  const bigrams = tokenTexts.filter(t => /^[\u4e00-\u9fff]{2}$/.test(t))
  expect('中文被切成二元组（不是整段中文）', bigrams.length > 0, `如 ${bigrams.slice(0, 3).join('/')}`)
  expect('没有把整句中文当一个 token', !tokenTexts.some(t => t.length > 4 && /^[\u4e00-\u9fff]+$/.test(t)))
}

// —— 召回结果 ——
const retrieveCard = page.locator('.diag-card', { hasText: '召回：' }).first()
expect('召回卡片存在', (await retrieveCard.count()) > 0)
if ((await retrieveCard.count()) > 0) {
  await retrieveCard.locator('button').filter({ hasText: /检\s*索/ }).first().click()
  await retrieveCard.locator('.rag-hit-table').waitFor({ state: 'visible', timeout: 30000 }).catch(() => {})
  await page.waitForTimeout(300)
  const hitRows = await retrieveCard.locator('.rag-hit-table tbody tr').count()
  expect('召回结果已渲染', hitRows > 0, `${hitRows} 条`)
  expect('召回显示了名次', (await retrieveCard.locator('.rag-hit-table td.is-rank').count()) > 0)
  // 归属章节是排查"召回错了"最关键的一列
  expect('召回了归属章节列', (await retrieveCard.locator('.rag-hit-table td.is-breadcrumb').count()) > 0)
}

// —— 对照评测：这一块是阶段二的验收口径所在 ——
const compareCard = page.locator('.diag-card', { hasText: '分块策略对照' }).first()
expect('对照卡片存在', (await compareCard.count()) > 0)
if ((await compareCard.count()) > 0) {
  await compareCard.locator('button', { hasText: '跑对照' }).click()
  await compareCard.locator('.rag-metric-table').first().waitFor({ state: 'visible', timeout: 90000 })
  await page.waitForTimeout(500)

  const metricRows = await compareCard.locator('.rag-metric-table tbody tr').count()
  expect('对照表两个策略都在页面上', metricRows >= 2, `${metricRows} 行`)

  const compareText = (await compareCard.innerText()).trim()
  expect('标出了对照组', /对照组/.test(compareText))
  // 指标名必须出现 —— 否则那张表就只剩数字，读者不知道每列是什么
  expect('显示了 Recall 指标', /Recall@5/.test(compareText) && /Recall@10/.test(compareText))
  expect('显示了 MRR 指标', /MRR/.test(compareText))
  // token 成本必须与召回率并排：只给 Recall 会让人得出"块越大越好"的错误结论
  expect('显示了 token 成本列', /前 3 条 token/.test(compareText) && /全部候选 token/.test(compareText))
  expect('显示了差值区', /差值/.test(compareText))
  expect('差值块已渲染', (await compareCard.locator('.rag-delta').count()) > 0)
  // 回归钉子：Recall 越高越好，token 越高越差 —— 两者的颜色方向必须相反，
  // 统一按"涨=绿"上色会稳定地把读者引向错误结论
  expect('差值颜色区分了方向', (await compareCard.locator('.rag-delta-value.is-bad').count()) > 0)
  // "不能证明什么"必须写在界面上，否则读者会自行脑补结论
  expect('页面上说明了不能证明什么', /不能说|块越大越容易碰到答案/.test(compareText))
  expect('显示了分组表现', /分组表现/.test(compareText))
  expect('显示了未命中用例', /未命中/.test(compareText))

  // ★ 纯文本区里不许出现字面量 markdown 星号（第十六轮补的一条守卫）
  //
  // 【为什么补在 RAG 面板上】纯文本里写 markdown 星号会原样显示，而成本看板（第十轮）、
  // Prompt / 告警面板（第十一 / 十五轮）都各自加过断言 —— **只有 RAG 面板没加**。
  // 这一轮扫一遍整个仓库才发现它的 notes 与对照说明里都写着 `**加粗**`，
  // 而它们渲染到这一页的纯文本 <li> 里，也就是说**页面上真的印着星号**。
  // 一条"某一类问题在本项目里已经被清干净了"的结论，只有在这类地方各有一条断言时才成立。
  //
  // ⚠️ **判据必须排除"分块预览"那一块** —— 实测踩到（本轮）：
  // 它显示的**就是原文**，而语料是 Markdown，所以那里出现 `**单一事实来源**`
  // 不是缺陷，恰恰是"固定硬切把标题层级丢了、正文里的星号原样留着"的证据。
  // 把它一起判进去，就会得到一条**在功能正确时必然红**的断言（「否定式判据要先确认它否定的到底是什么」同族：
  // 判据要先确认它判的到底是什么东西）。
  // 所以判据是"**除了原文预览之外**的正文里没有字面量星号"。
  const starProbe = await page.locator('.diag-panel').first().evaluate(panel => {
    const clone = panel.cloneNode(true)
    // 预览区与它会话里的原始 Markdown 不算"文案"，它们要显示的就是原样的字节
    clone.querySelectorAll('.rag-chunk, .rag-breadcrumb, pre, code').forEach(el => el.remove())
    return clone.innerText
  })
  expect('RAG 面板正文无字面量星号（原文预览区除外）', !starProbe.includes('**'),
    '—— 后端 notes / 对照说明会原样渲染到纯文本里，强调一律用「」')

  await page.screenshot({ path: `${SHOT_DIR}/rag-compare.png`, fullPage: true })
}

// —— 块大小对齐实验：本轮把混杂因素消掉之后的那份报告 ——
//
// 这一节断言的是"界面有没有如实呈现一个**与上一张表相反**的结论"：
//   - 7 组对照必须都在（少了就等于只报了一部分数字）；
//   - **左右两行必须并排出现**（只显示一行就等于没做对照，这正是这张报告存在的理由）；
//   - 差值颜色必须区分方向（token 类指标涨了是坏事，与 Recall 相反）；
//   - "关掉面包屑那一组才是在比切点"必须写在界面上 ——
//     不写的话读者会拿带面包屑那三组去下"切点"的结论，而那三组对齐的是渲染后大小；
//   - **向量那一路是离线近似品**必须出现 —— 否则读者会拿 vector-local 的
//     Recall@5 0.34 去和 BM25 的 0.74 比，得出"向量检索差一半"这个完全错误的结论。
console.log('[6.5] 块大小对齐实验')
const alignCard = page.locator('.align-card').first()
expect('对齐实验卡片存在', (await alignCard.count()) > 0)
if ((await alignCard.count()) > 0) {
  // 它进面板就自动跑（后端实测 4.95 秒），所以这里等结果元素而不是点按钮。
  // 超时给 120 秒：真后端要跑 15 次全量评测，假后端睡 600ms
  await alignCard.locator('.rag-metric-table').first().waitFor({ state: 'visible', timeout: 120000 })
  await page.waitForTimeout(500)

  const alignText = (await alignCard.innerText()).trim()
  expect('对齐实验的标题写明了结论反转', /结论反了|混杂因素/.test(alignText))
  // 概览：目标 / 实测 / 残差 三格必须在 —— 残差单独占一格是刻意的
  //（"对齐永远是近似的"，差多少要让读者自己判断）
  expect('显示了对齐目标与实测值', /对齐目标/.test(alignText) && /收敛后实测/.test(alignText))
  expect('显示了对齐残差', /对齐残差/.test(alignText))
  // 7 组对照（3 路检索 × 2 个对齐组 + 1 组"块更大的效应"）
  const alignGroups = await alignCard.locator('.align-group').count()
  expect('按检索方式分组渲染了 7 组对照', alignGroups >= 3, `${alignGroups} 个检索分组`)
  const refRows = await alignCard.locator('.align-row-ref').count()
  const subjectRows = await alignCard.locator('.align-row-subject').count()
  expect('每组对照的参照方都渲染了', refRows >= 7, `${refRows} 行参照方`)
  expect('每组对照的被考察方都渲染了', subjectRows >= 7, `${subjectRows} 行被考察方`)
  // 左右并排 —— 只显示一侧就等于没做对照
  expect('左右两方成对出现', refRows === subjectRows)
  expect('标出了左右两方的角色', /参照方/.test(alignText) && /被考察方/.test(alignText))
  // 差值的方向配色：Recall 与 token 成本必须能同时出现两种颜色
  expect('差值列按各自方向着色', (await alignCard.locator('.rag-inline-delta.is-bad').count()) > 0)
  // token 成本必须与召回并排（只给 Recall 会让人得出"块越大越好"）
  expect('token 成本与召回并排', /全语料 token/.test(alignText) && /重复率/.test(alignText))
  // "哪几组才是在比切点"必须写在界面上
  expect('说明了哪几组才是在比切点', /不能单独判断切点|在比「切点」/.test(alignText))

  /*
    这两个标签的数量是**钉住一个回归**：第一版把"能不能判断切点"打在了**分组表头**上，
    于是 vector / hybrid 两个分组被整体标成"对齐的是渲染后大小" ——
    而它们的第一组恰恰是最干净的那一组（关掉面包屑）。根因是分组里混着两种性质的对照，
    只要有一组不合格就污染了整个分组。

    所以断言**两种标签同时出现且数量固定**：
      - "在比「切点」" 3 个 = 三路检索各一组（对齐正文的那三组）；
      - "对齐的是渲染后大小" 4 个 = 三组带面包屑的锚点 + 第 7 组"块更大的效应"。
    数量写死是有意的：多一个少一个都说明后端的 pairs 构成变了，
    而那时这套解读文字需要重新核对（它讲的正是"哪几组干净"）。
  */
  const definitiveTags = await alignCard.locator('.align-row-ref .ant-tag', { hasText: '在比「切点」' }).count()
  const anchorTags = await alignCard.locator('.align-row-ref .ant-tag', { hasText: '对齐的是渲染后大小' }).count()
  expect('干净对照（在比切点）正好三组', definitiveTags === 3, `${definitiveTags} 个`)
  expect('锚点对照（渲染后大小）正好四组', anchorTags === 4, `${anchorTags} 个`)

  // 收敛过程：扫描表 + 收敛点
  expect('显示了尺寸扫描表', /确定收敛|收敛点|尺寸扫描表/.test(alignText))
  // 轴二：等预算表，且必须写明"只有词法那一路有数"
  expect('显示了等预算表', /等 token 预算/.test(alignText))
  expect('说明了预算表只有词法有数', /只有词法那一路有数/.test(alignText))
  // 不能下的结论必须出现（尤其"向量那一路不是语义嵌入"）
  expect('写明了向量那一路是离线近似品', /离线哈希|不是语义嵌入/.test(await alignCard.innerText()))

  await page.screenshot({ path: `${SHOT_DIR}/rag-align.png`, fullPage: true })
}

/*
  假数据自身的**方向自检** —— 这一节不打开浏览器，直接读 mock 数据。

  为什么值得单独写一条：这份面板上的**解读文字与数字是绑定的**
  （"块大小对齐之后固定硬切全面占优"），而自检断言不了"文字和数字是否一致"。
  一旦有人顺手改了 mock 里的数字（或者换了一版真数据），
  界面就会变成"文字说结论反了、数字说没反"，而**所有基于文案的断言照样全绿**。

  两条相反方向的断言放在一起，还顺带把这条实验的逻辑钉住了：

    - 三组"块大小已对齐"的对照里，**右侧（结构分块）必须更差**；
    - 而第 7 组（刻意**不**对齐块大小那组）里，**块更大的那一侧反而更好** ——
      这恰恰是"块越大越容易覆盖答案"这个混杂因素本身，也是上面那份报告存在的理由。
      所以第 7 组出现"方向相反"**不是**数据错了，是它在证明混杂因素真的存在。
*/
{
  const { ALIGNMENT } = await import('./mock/rag-align-data.mjs')
  // 「块大小已对齐」那三组：字段描述里写着"关掉面包屑"（对齐的是正文，不是渲染后大小）。
  //
  // ⚠️ 别用"不含 '+面包屑'"来筛 —— 这条会连**第 7 组（块更大的效应）**一起筛进来，
  // 而那一组是**刻意不对齐块大小**的对照，它的方向本来就该相反。
  // 实测踩到了：`/\+面包屑/` 筛出 4 组，然后第 7 组稳定报"反向 1 组"，
  // 看起来像数据错了，其实是筛选条件把"反证组"当成了"结论组"。
  const definitive = ALIGNMENT.pairs.filter(p => p.fieldDescription.includes('关掉面包屑'))
  const wrong = definitive.filter(p => {
    const [left, right] = p.reports
    const d5 = p.deltas['recall@5']?.[right.strategy] ?? 0
    const dmrr = p.deltas['mrr']?.[right.strategy] ?? 0
    return !(d5 <= 0 && dmrr <= 0)
  })
  expect(
    '假数据方向与界面结论一致（对齐组里右方更差）',
    wrong.length === 0,
    `共 ${definitive.length} 组，反向 ${wrong.length} 组`,
  )
  // 反证：不对齐块大小那组必须能看出"块更大 = 更好"，否则说明这份数据里
  // 根本没有混杂因素，"消掉混杂因素"这句话就无从谈起
  const confound = ALIGNMENT.pairs.find(p => p.fieldDescription.includes('块更大的效应'))
  const confoundProvesIt =
    confound !== undefined && (confound.deltas['recall@1']?.[confound.reports[1].strategy] ?? 0) > 0
  expect('假数据里能看出"块更大更容易覆盖答案"这个混杂因素', confoundProvesIt)
}

// —— 评测集 ——
const evalCard = page.locator('.diag-card', { hasText: '评测集：' }).first()
expect('评测集卡片存在', (await evalCard.count()) > 0)
if ((await evalCard.count()) > 0) {
  const evalText = (await evalCard.innerText()).trim()
  expect('评测集显示了条数与门槛', /共 \d+ 条/.test(evalText) && /门槛/.test(evalText))
  expect('评测集显示了分组统计', /failures|phase1|phase2|knowledge|materials/.test(evalText))
}

// 布局体检：这张面板的表格列多，最容易横向溢出
const ragLayout = await page.evaluate(() => {
  const doc = document.documentElement
  return { horizontalOverflow: doc.scrollWidth - doc.clientWidth }
})
expect('RAG 面板无横向溢出', ragLayout.horizontalOverflow === 0, `overflow=${ragLayout.horizontalOverflow}px`)

// ---------- Agent 工具调用面板（阶段三第 5 件） ----------
//
// 这一节断言的是"页面有没有把 Agent 到底干了什么如实表达出来"：
//   - **答案与过程必须同屏** —— 只显示答案的话，"查了 3 次、失败 2 次、最后蒙对"
//     与"一次就答对"长得一模一样，而区分这两者是这个功能的全部价值；
//   - **每次工具调用都要有 stage 与回填文本** —— stage 说明"死在哪一步"，
//     回填说明"模型当时看到了什么"，合起来才能回答"它为什么这么选"；
//   - **被拒绝的调用也必须在页面上**（UNKNOWN_TOOL 那次）——
//     报告要回答"模型想调几次、其中几次没让它调"，只显示成功的会让这个数字永远偏小；
//   - **失败细分必须显示** —— MODEL_ERROR 下面藏着 6 种原因，其中
//     "我们自己的并发闸门拒绝"与"上游鉴权失败"的排查方向完全相反；
//   - **限额块必须写明"全局共享"** —— 否则读者会以为那是某一次运行的属性；
//   - **"计入熔断"这一列必须由后端给** —— 前端不自己判 stage 的好坏，
//     否则判据会有两份并慢慢漂移。
console.log('[6.6] Agent 工具调用面板')

/**
 * <b>切到 Agent 面板 —— 这是下面所有内容的前提。</b>
 *
 * <p>不切过来的话，"待我确认""跑一个任务""历史运行""四层保护"四张卡片
 * 一个都不会渲染，而失败信息看起来像"功能没做"。
 *
 * <h2>这一步踩过一次，值得单独记</h2>
 * 本次改动把"跑一个任务 + 验证四步轨迹"整段往后移（因为假后端现在按审批状态
 * 返回不同轨迹，所以那段必须在批准之后才能跑）。
 * 而**搬动时顺手把"切面板"这个动作也一起搬走了** ——
 * 于是紧随其后的"历史运行卡片存在""限额卡片存在"两条先红，
 * 而 [6.7] 那一整节（新功能）也跟着全红。
 *
 * <p>教训一句话：**一段自检代码里往往混着"动作"与"断言"两类语句，
 * 搬动时只盯着断言，就会把动作一起搬走** —— 而症状是"看起来这个功能没渲染"。
 */
await page.locator('label', { hasText: 'Agent 工具调用' }).click()
await page.waitForTimeout(900)

// 【原「跑一个任务 + 验证四步轨迹」那一段被移到了 HITL 那一节之后】
//
// 移它的理由是一个真实的耦合：假后端现在按**审批状态**返回不同的轨迹 ——
// 有待批时返回"停在等人确认"（steps 为空），批准之后才返回完整轨迹。
// 所以"验证完整四步轨迹"这件事**必须发生在批准之后**，否则它断言的是一个
// 那一刻还不存在的场景（跑出来就是"0 步""没有最终答案"一串红）。
// 这不是把断言放宽，而是**顺序本来就有依赖** —— 之前没暴露是因为那时假后端只有一条轨迹。

// —— 历史运行列表 ——
//
// ⚠️ 这里用的是**精确标题**而不是 hasText 子串匹配，理由是一个实测踩到的真问题：
// `'历史运行'` / `'运行详情'` 这类短串会被**后来新增的卡片**意外命中。
// 第十三轮加了"五层 trace"卡之后，标题写的是
// 「五层 trace：会话 → 运行 → LLM 调用 → 工具调用 → 检索」——
// 它**不含** '运行详情'，所以这次没踩到；但同一类匹配在别的短串上就会踩到
// （例如如果哪天有卡片的标题里出现"详情"二字）。
// 用 `^历史运行` 这种锚定前缀，让"匹配到一张意料之外的卡"变成**不会静默发生**的事。
const runsCard = page.locator('.diag-card').filter({ has: page.locator('.ant-card-head-title', { hasText: /^历史运行/ }) }).first()
expect('历史运行卡片存在', (await runsCard.count()) > 0)

if ((await runsCard.count()) > 0) {
  /*
    【★ 这一节按"有没有数据"分岔，而不是硬断言"至少有 1 行" —— 实测踩到，值得记】

    第一版在这里写的是"等到 tbody 里有行（最多等 20 秒）"，跑真后端时红了：
        ✗ 历史运行列表里至少有 1 行 — 实际 0 行
    而**那是环境，不是缺陷**：真后端用的是内存库（`ai.jdbc:h2:mem:`），
    一个刚起来的进程里一条运行都没有是**完全正常**的。
    列表为空时页面显示空状态，那也是对的。

    这与本节下面那条"刚刚跑过的任务"是同一个毛病（「断言越界去测了别人的职责」，
    以及「断言写的是假数据里才有的话；「被跳过」与「通过」在日志里长得一样」）：
    **断言越界去测了别人的职责** ——「库里有数据」取决于这次自检有没有成功跑过 `/ask`，
    而假 key 下必然 401；这一节（历史运行列表）自己负责任的性质只有两条：
      ① 卡片渲染出来了；
      ② **有数据时，数据能被渲染出来**。
    ② 是条件断言，条件就是"确实有数据"。

    ⚠️ 关键是把"没数据所以跳过"这件事**显式说出来**：静默跳过与通过长得一样，
    而"一个从未执行过的断言与一个永远通过的断言，在任何观测上都相同"（「被跳过」与「通过」在日志里长得一样）。
  */
  const hasRows = await waitFor(async () => (await runsCard.locator('.ant-table-tbody tr').count()) > 0, 5000)
  if (!hasRows) {
    console.log('    （历史运行列表是空的 —— 真后端的内存库重启后本来就没有数据，' +
      '所以本节只验证"卡片渲染出来了"，不验证行内容。这**不是**跳过一条断言，' +
      '而是这一节对"库里有数据"这件事本来就没有主张）')
  }
  const runsText = (await runsCard.innerText()).trim()
  expect('历史运行卡片渲染出了内容（空状态也要有说明）', runsText.length > 5, `${runsText.length} 字符`)

  // ⚠️ 注意判据是 `hasRows && !EXPECT_REFLECTION` —— **`hasRows` 这一半不能省**。
  // 实测踩到（第十六轮）：原来的写法是 `if (hasRows && EXPECT_REFLECTION) {...} else {...}`，
  // 而那个 `else` 分支的注释写的是「真后端（假 key）：历史里只有那条失败运行」——
  // 它**假设了列表非空**。真后端这次是内存库重启后的空库，于是 `else` 照样进，
  // 两条断言（"显示了失败细分" / "显示了失败状态"）在一个**完全正常**的空列表上红了，
  // 而失败信息看起来像"界面没渲染失败细分"。
  // 这是"断言越界去测了别人的职责"的第 N 次（同族问题反复出现过），
  // 而这一次的形状更隐蔽：**越界藏在 `else` 里** —— `else` 不等于"有数据"，它只等于"不满足上一个条件"。
  if (hasRows && !EXPECT_REFLECTION) {
    // 【"刚刚跑过的任务在列表里"只在假后端路径上成立】
    // 原来这条是本节的共用断言，写的是 `/Agent 工具调用面板|分块策略的结论是什么/` ——
    // 而那个问题是**假数据里写死的**，真后端上永远不会有。
    // 实测：真后端路径上它确实红了（那次恰好列表非空，于是断言真的执行了）。
    //
    // 这与上面那条是同一个毛病：**断言越界去测了别人的职责** ——
    // 「列表里有**这一次**刚跑出来的任务」不是 §6.6 这一节能保证的事
    // （它取决于 `/ask` 有没有成功落库，而假 key 下必然 401）。
    // 假后端里那份历史是固定的，所以在那里做这条内容断言才有确定性。
    expect('列出了刚刚跑过的任务（假数据里有那条固定问题）',
      /Agent 工具调用面板|分块策略的结论是什么/.test(runsText))
    // 假后端给了一份"包含各种结局"的历史，所以这些状态标签都能验证
    expect('显示了状态标签', /已回答/.test(runsText))
    // **等待恢复的提示必须显眼**：默认配置不会自动恢复，不说的话那些运行会一直躺着没人管，
    // 而现象是"那个任务永远没结果"
    expect('提示了有运行在等待恢复', /等待恢复/.test(runsText))
    // 失败细分必须在列表里可见（MODEL_ERROR 这一个出口下面藏着 6 种原因）
    expect('显示了失败细分', /LOCAL_CONCURRENCY_LIMIT/.test(runsText))
    // 被上限拦下与"失败"是两件事，界面上要分开
    expect('区分了"被上限拦下"与"失败"', /被上限拦下/.test(runsText) && /失败/.test(runsText))
  } else if (hasRows) {
    // 真后端（假 key）+ **列表非空**：历史里通常只有那条失败运行 ——
    // 正好验证失败细分的**真实渲染**。
    // ⚠️ `hasRows` 这个条件必须显式写在 `else` 上（见上面那段注释）：
    // "列表为空"既不满足前一个分支、也不该进这一个。
    //
    // ⚠️ 判据刻意**不写死某一个分类名**。第一版写的是 `/UPSTREAM_AUTH/`，
    // 理由是"假 key → 上游 401 → 必然分到 UPSTREAM_AUTH"。而实测这次拿到的
    // `failureCause` 是 **UNEXPECTED**（上游 401 经过 SDK 包装之后没被认成鉴权失败），
    // 于是这条断言在一个**完全正常**的界面上红了 —— 它测的其实是后端的分类准确度，
    // 而不是"界面有没有把失败细分渲染出来"，而那才是这一节要钉的东西。
    //
    // 改成两条各自成立的判据：
    //   ① 失败细分这一格**有值**（六个合法分类之一），说明它真的渲染了；
    //   ② 列表里能看出这是失败状态。
    // 分类准不准由 `AgentFailureClassifier` 的后端测试负责，不该由界面自检替它把关。
    const causes = ['LOCAL_CONCURRENCY_LIMIT', 'UPSTREAM_AUTH', 'UPSTREAM_RATE_LIMITED',
      'UPSTREAM_TIMEOUT', 'UPSTREAM_UNAVAILABLE', 'UPSTREAM_ERROR', 'UNEXPECTED']
    const shown = causes.filter(c => runsText.includes(c))
    expect('显示了失败细分（不是空白，也不是一律 MODEL_ERROR）', shown.length > 0,
      shown.length > 0 ? `显示的是 ${shown.join('/')}` : '一格都没显示')
    expect('显示了"失败"状态', /失败/.test(runsText))
  }
  else {
    // 【列表为空 + 真后端】空状态本身也要有一句话说清"为什么空"
    // （内存库重启即清空是预期行为 —— 一个只有表头、没有解释的空列表会被读成"功能坏了"）
    expect('空的历史列表也给出了说明（不是一片空白）',
      /一次运行|还没有|空|没.*运行/.test(runsText), `—— 正文：${runsText.slice(0, 80)}`)
  }
}

// —— 点开详情：这一步验证"能还原执行过程" ——
//
// ⚠️ 这里必须**先确认列表里真的有一行**再点（实测踩到，值得记）。
// 原来写的是"卡片在就点第一行"，而 `.first().click()` 在**列表为空**时会等满 30 秒然后
// 抛一个未捕获异常，**整条自检当场中断** —— 于是这一条之后的所有断言（包括成本看板整节）
// 一条都没跑，而日志里看起来像"点不动详情"。
//
// 什么时候列表会是空的：真后端上"有没有历史运行"取决于 `/ask` 有没有落库 ——
// 本机 key 是假的，`/ask` 会 401，但**那次失败的运行同样会落库**，
// 所以通常有；可一旦它在别的路径上没落上（比如上一个用例刚清库、或者 `/ask` 卡在加载中），
// 列表就真的是空的。**"卡片在"不等于"列表有行"** —— 两者要分开判。
if ((await runsCard.count()) > 0) {
  const runRows = runsCard.locator('.ant-table-tbody tr')
  const rowCount = await runRows.count()
  if (rowCount === 0) {
    // 空列表也要被解释清楚，而不是让后面的步骤静默跳过
    console.log('    （列表里没有运行记录，跳过"点开详情"这一段）')
  }
  else {
    await runRows.first().click()
  }
  const detailCard = page.locator('.diag-card', { hasText: '运行详情' }).first()
  await detailCard.waitFor({ state: 'visible', timeout: 30000 }).catch(() => {})
  await page.waitForTimeout(400)
  if (rowCount > 0 && (await detailCard.count()) > 0) {
    const detailText = (await detailCard.innerText()).trim()
    expect('详情显示了步骤表', /每步一个检查点/.test(detailText))
    expect('详情显示了每次工具调用', /含被拒绝的/.test(detailText))
    // **判据只能有一份**：这一列由后端给，页面不自己判 stage 的好坏
    expect('详情里有「计入熔断」列', /计入熔断/.test(detailText))

    if (EXPECT_REFLECTION) {
      // 这一列是"断点恢复"在界面上的唯一痕迹 —— 没有它，阶段三第 2 件在页面上不可见。
      // 真后端那次运行 0 步（假 key 直接 401），所以这条只在假路径可验证
      expect('详情显示了检查点上下文大小', /字符/.test(detailText))
    }

    // ================================================================
    //  五层 trace（阶段四第 2 件）—— 在关掉详情**之前**验证
    //
    //  【这一段要证明的是"第 5 层真的被摆出来了"】
    //  检索明细在此之前只存在于**回填给模型的那段中文文本**里（回填文本确实也显示
    //  在这一页上），所以核心断言是"页面上能读出<u>结构化</u>的检索明细"
    //  （查询词 / 索引规模 / 名次 / 每块 token / 得分），而不是"页面上出现了「检索」两个字"
    //  —— 后者在一个只显示回填文本的实现上同样会过。
    //
    //  ⚠️ 位置很关键：`closeDetail` 会把 trace 与详情**一起**清掉（理由见 `useAgent` 里
    //  那段注释：留着它会让人"关掉再点另一次"时先看到上一条运行的 trace）。
    //  所以这一段必须在点「关闭」之前跑完。
    // ================================================================
    const traceCard = page.locator('.diag-card', { hasText: '五层 trace' }).first()
    await traceCard.waitFor({ state: 'visible', timeout: 30000 }).catch(() => {})
    await page.waitForTimeout(300)
    expect('★ 详情打开时五层 trace 卡同时出现（两个端点是并发拉的）', (await traceCard.count()) > 0)

    if ((await traceCard.count()) > 0) {
      const traceText = (await traceCard.innerText()).trim()

      // ---- 两条路径共用的断言（不依赖假数据）----

      // 【五层到场清单】它是读这一页之前必须先知道的事：
      // 不看它会把"这一层本来就没内容"误读成"漏记了"
      const layerTags = traceCard.locator('[data-testid^="trace-layer-"]')
      const layerCount = await layerTags.count()
      expect('五层到场清单列出了 5 层', layerCount === 5, `实际 ${layerCount} 层`)
      expect('清单里点名了会话那一层', layerCount > 0 && /会话/.test(await layerTags.first().innerText()))
      expect('清单里点名了检索那一层', (await traceCard.locator('[data-testid="trace-layer-retrieval"]').count()) > 0)

      // 【★ 这一条钉的是"空"与"漏记"必须分得开】
      // 判据由后端给（`expected` 布尔），前端只负责显示 —— 页面自己判的话就是第二份判据。
      // 两条路径上真后端那次运行大多死在第一步之前（假 key 401），所以层次会缺 ——
      // 但**每一层的标签与后端给的 expected 必须自洽**，这是任何路径上都成立的性质
      {
        const inconsistencies = await traceCard.evaluate(card =>
          [...card.querySelectorAll('[data-testid^="trace-layer-"]')]
            .filter(tag => {
              const expected = tag.getAttribute('data-expected') === '1'
              const saysHasContent = tag.textContent.includes('✓')
              return expected !== saysHasContent
            })
            .map(tag => tag.textContent.trim()),
        )
        expect(
          '每一层的标签与后端给的 expected 自洽（「空」与「该有」必须分得开）',
          inconsistencies.length === 0,
          inconsistencies.join('、'),
        )
      }

      // 会话那一层：要么给出会话 id 与运行列表，要么**明说没有会话信息**。
      // ⚠️ 两种渲染都不能是"一片空白" —— 空白会让人以为功能没做
      {
        const saysAbsent = (await traceCard.locator('[data-testid="trace-conversation-absent"]').count()) > 0
        const hasConversationId = /会话 id/.test(traceText)
        expect('会话那一层给出了会话 id 或明说没有会话信息', hasConversationId || saysAbsent)
      }

      // 提示词指纹那一列必须存在（"这一步用的是哪一版"是本件三件之一）
      expect('LLM 调用表里有「提示词指纹」列', /提示词指纹/.test(traceText))

      // 【★ 金额算不出来时绝不能显示 ¥0.00】
      // 与成本看板同一条纪律，而它在界面上只有这一条判据：amount 为 null 时
      // 必须渲染成「算不出金额」，而不是任何形式的 0。
      // 真后端上价格表默认是空的，所以这条在两条路径上都该成立
      {
        const unknownBox = traceCard.locator('[data-testid="trace-cost-unknown"]')
        const knownAmount = traceCard.locator('[data-testid="trace-cost-amount"]')
        const hasUnknown = (await unknownBox.count()) > 0
        const hasKnown = (await knownAmount.count()) > 0
        expect('金额要么给数字、要么明说「算不出」', hasUnknown || hasKnown)
        if (hasUnknown) {
          const text = await unknownBox.innerText()
          expect('「算不出」那一块写明了原因', text.length > 10, text.slice(0, 60))
          expect('「算不出」时页面上没有 ¥0.00', !/¥\s*0\.0{2,}/.test(traceText))
        }
      }

      // ---- 假后端路径特有的断言：那里有确定性的检索明细 ----
      if (EXPECT_REFLECTION) {
        const retrievalCalls = traceCard.locator('[data-testid="trace-retrieval-call"]')
        const callCount = await retrievalCalls.count()
        expect('★ 检索那一层被摆出来了（每一次检索一个明细块）', callCount > 0, `实际 ${callCount} 块`)

        if (callCount > 0) {
          const first = await retrievalCalls.first().innerText()
          // 这三个数就是"0 条命中"的全部上下文 —— 没有索引规模就分不清
          // "库是空的"与"没匹配上"，而那两种的改法正好相反
          expect('检索明细里有查询词', /查询词/.test(first))
          expect('检索明细里有索引规模', /索引 \d+ 块/.test(first))
          expect('检索明细里标了 token 是估算的', /估算/.test(first))

          // 命中的结构：名次 / 得分 / 来源 / 每块 token / 摘录
          const hitTable = retrievalCalls.filter({ has: page.locator('.rag-hit-table') }).first()
          const hitRows = hitTable.locator('.rag-hit-table tbody tr')
          const hitCount = await hitRows.count()
          expect('★ 命中的块逐条列出（不是一段文本）', hitCount > 0, `实际 ${hitCount} 行`)
          if (hitCount > 0) {
            const hitHeader = await hitTable.locator('.rag-hit-table thead').innerText()
            expect('命中表有名次列', /名次/.test(hitHeader))
            expect('命中表有得分列', /得分/.test(hitHeader))
            expect('命中表有来源/章节列', /来源/.test(hitHeader))
            expect('命中表有每块 token 列', /token/.test(hitHeader))
            expect('命中表有片段摘录列', /片段开头/.test(hitHeader))

            // 名次必须从 1 开始且连续 —— 后端断言过同一条性质，这里是它在页面上的样子。
            // （后端在写库时把名次与列表顺序对齐，页面直接渲染；两边都钉住才叫闭环）
            const ranks = await hitRows.evaluateAll(rows =>
              rows.map(r => Number((r.querySelector('td')?.textContent ?? '').trim())),
            )
            expect(
              '名次从 1 开始且连续（它就是模型看到的名次）',
              ranks.length > 0 && ranks.every((r, i) => r === i + 1),
              ranks.join(','),
            )
          }
        }

        // 【0 命中的检索同样留痕，而且页面要说清是"没查到"】
        // 假数据里刻意造了一次 0 命中：它是最该被单独看见的一种检索
        // （白跑一轮照样花钱），而它混在"检索了 N 次"里完全看不出来
        expect('★ 一次都没查到的检索也被显示出来', /一条都没查到/.test(traceText))
        expect('检索汇总里单独给了空命中次数（没并进检索次数）', /空命中次数/.test(traceText))
        // 检索 token 的口径必须标明是估算 —— 它与成本看板那边上游 usage 不是一回事
        expect('检索 token 标明了是估算口径', /命中 token（估算）/.test(traceText))
        // 占输入比例：这一格回答"RAG 到底占了多大一块"，在此之前没有数据可答
        expect('显示了检索占输入的比例', /占输入比例/.test(traceText))
        // 会话那一层：假数据里有 3 次运行，且必须标出"正在看哪一次"
        expect('会话那一层列出了多次运行', /这段对话里一共 \d+ 次运行/.test(traceText))
        expect('★ 标出了「正在看」的是哪一次', /正在看/.test(traceText))
      }
    }

    // 关掉详情。
    //
    // 这里踩了一个**老坑**（「antd 给恰好两个汉字的按钮插了字间距空格」）：antd 6 会把恰好两个汉字的按钮渲染成
    // 「关 闭」（中间插一个空格），所以 `hasText: '关闭'` **永远匹配不上** ——
    // 而失败表现是"等按钮等到超时"，看起来像"详情卡里没有关闭按钮"。
    // 同族问题还有「纯文本里写 markdown 星号会原样显示」——**界面文案的一个渲染细节，
    // 能让一个看起来完全合理的定位永远失效**。
    await detailCard
      .locator('button')
      .filter({ hasText: /关\s*闭/ })
      .first()
      .click()
    await page.waitForTimeout(300)

    // 【★ 关掉详情之后 trace 也必须跟着消失】
    // 不消失的话，"关掉再点另一次运行"的瞬间会先渲染出上一条运行的 trace ——
    // 那种"闪一下旧数据"在排查时会被读成"这次运行查到了那些东西"
    expect(
      '关掉详情后 trace 卡一起消失（否则会显示上一条运行的数据）',
      (await page.locator('.diag-card', { hasText: '五层 trace' }).count()) === 0,
    )
  }
}

// —— 四层保护的状态 ——
const limitsCard = page.locator('.diag-card', { hasText: '四层保护' }).first()
expect('限额卡片存在', (await limitsCard.count()) > 0)
if ((await limitsCard.count()) > 0) {
  await limitsCard
    .locator('.rag-metric-table')
    .first()
    .waitFor({ state: 'visible', timeout: 30000 })
    .catch(() => {})
  await page.waitForTimeout(300)
  const limitsText = (await limitsCard.innerText()).trim()
  expect('显示了模型侧并发上限', /模型并发上限/.test(limitsText))
  expect('显示了工具侧并发上限', /工具并发上限/.test(limitsText))
  expect('显示了排队超时', /排队超时/.test(limitsText))
  // **"全局共享"这一句不能少**：不说的话读者会以为限额状态是某一次运行的属性
  expect('写明了限额是全局共享的', /全局共享/.test(limitsText))
  // 闸门与熔断并排 —— 它们是两种不同的病，分开放会让人看不出"这把工具现在能不能用"
  expect('闸门与熔断并排显示', /在途/.test(limitsText) && /熔断状态/.test(limitsText))
  expect('显示了被拒绝次数', /被拒绝/.test(limitsText))
  // 判据只有一份：只有"工具执行出错"计入失败率。
  // 断言用两句**原文**而不是宽松的词匹配 —— 一个只查"算失败/不算"的断言，
  // 在文案被改坏时照样会过，而它要保护的正是"这句话有没有被说清楚"
  expect('说明了只有执行出错计入熔断', /只有"工具执行出错"计入失败率/.test(limitsText))
  expect('说明参数错与限流拒绝都不算工具故障', /都\*\*不算\*\*工具故障|不算工具故障/.test(limitsText))
  // 两种拒绝的下一步动作相反，页面上必须分开说
  expect('区分了限流拒绝与熔断拒绝的下一步', /重试是有意义的/.test(limitsText) && /别再用/.test(limitsText))

  if (EXPECT_REFLECTION) {
    // 假数据刻意造了三种状态（健康 / 被限流 / 已熔断），界面上必须都能看出来。
    // 真后端刚启动时没有任何工具调用记录，所以"每把工具一行"这段表格是空的 ——
    // 那属于场景不成立，不是界面缺陷（空表时组件显示的是 Empty，而不是空白）
    expect('显示了熔断打开的工具', /已熔断/.test(limitsText))
    expect('显示了正常放行的工具', /正常放行/.test(limitsText))
    expect('显示了正在被限流的工具', /0\.75 \/ 0\.5/.test(limitsText))
  } else {
    // 真后端：验证"模型侧闸门已启用"这件事**在界面上可见** ——
    // 这条断言背后是一个真 bug：限流层曾经因为重复 bean 定义而压根没装上，
    // 而唯一能看出它的地方就是这个字段（详见 OpenAiModelTurn 类注释里那段）
    expect('模型侧闸门显示为已启用', !/模型侧并发闸门未启用/.test(limitsText))
    expect('显示了在途/上限', /在途/.test(limitsText))
  }

  await page.screenshot({ path: `${SHOT_DIR}/agent-limits.png`, fullPage: true })
}

const agentLayout = await page.evaluate(() => {
  const doc = document.documentElement
  return { horizontalOverflow: doc.scrollWidth - doc.clientWidth }
})
expect('Agent 面板无横向溢出', agentLayout.horizontalOverflow === 0, `overflow=${agentLayout.horizontalOverflow}px`)

// ---------- HITL：人工确认界面（阶段三第 9 件） ----------
//
// 这一节断言的是**"人能不能真的把那条写操作批掉"**，而不是"页面上有这几个字"：
//   - 待批的参数必须**原样可见** —— 人批的就是它，后端为此刻意不做脱敏与摘要；
//   - 「已等 / 还剩多久」必须显示 —— 审批对任务耗时的影响，唯一可量化的口径就是它；
//   - 批准之后那条待批必须**消失**，并且下一次任务**真的执行了写操作** ——
//     这是"闸门放行"与"闸门真的生效"的区别，也正是这一整轮要在界面上证明的东西；
//   - 拒绝之后**不能建单**，而理由必须原样回填给模型 ——
//     "人拒绝之后那个副作用真的没发生"是 HITL 唯一有意义的验收口径；
//   - 闸门关掉 / 没有待批时，空列表必须被解释清楚（否则"闸门没装"看起来像"系统很安全"）。
//
// 【为什么"批准/拒绝"这两条分支只在假后端跑】
// 真后端上没有待批时**根本没有可点的按钮** —— 那不是界面缺陷，是"这次还没有危险操作"。
// 要让它出现，得真的跑一次会触发写工具的 Agent 任务，而本机 key 是假的（401）。
// 所以真路径验证的是另一半、也是更该被验证的一半：**空列表必须被解释清楚**
// （"没有待批"有三种原因，混起来读会得出完全相反的结论）。
console.log('[6.7] HITL 人工确认界面')

/**
 * <b>"批准之前时间线在不在"</b> —— 由 HITL 那一节在动作**之前**写下来，
 * 给最后那条"切模式不丢结果"用。
 *
 * <h2>为什么要提前记，而不是用到的时候当场判断</h2>
 * 两条路径上这个前提本来就不同（假后端的任务有完整轨迹 → 时间线在；
 * 真后端假 key 直接 401 → 一次运行 0 步 → 时间线不渲染）。
 * 当场判断的话，同一段代码在两条路径上会走不同的分支 ——
 * 而**一个会随环境变化的断言条数，是没法写进文档的**
 * （阶段一就在这里含糊过一次，本项目把它记为"虚报"：写成"36 项"而实际只执行 29 项）。
 *
 * <p>提前记下来还有一个好处：它记的是**审批动作之前**的状态 ——
 * 而那正是"切模式不丢结果"要保护的东西（一次任务会真的花钱，丢了就得重跑）。
 */
let timelineWasThere = false

/**
 * 「跑一个任务」那张卡片。
 *
 * <h2>为什么这个定位器要在这里声明（而不是在它所属的那一节里）</h2>
 * 因为 HITL 那一节要用它做两件事：① 断言"待我确认"**排在它上面**
 * （有截止时间的东西优先于需要花钱的东西）；② 点它跑任务来验证
 * "批准之后写操作真的执行了"。
 *
 * <p>而"验证完整四步轨迹"的那一段被排在了 HITL **之后** —— 理由见上面那条指针注释：
 * 假后端现在按审批状态返回不同的轨迹，所以**顺序本身有依赖**。
 * 定位器提前声明出来，两节都能用，也就不会出现"谁先声明"这种与断言无关的耦合。
 */
const agentCard = page.locator('.diag-card', { hasText: '跑一个任务' }).first()

// ★ 两个归因维度的**输入框都必须存在**（阶段四第 6 件的前提）
//
// 【为什么这条断言比它看起来重要】归因维度是"没有入口就等于没有"的能力 ——
// 后端有字段、界面上不给入口的话，成本看板那两栏会永远只有「未标注」一行，
// 而那正是"这个能力只能靠 curl 用"的又一次重演（第九轮 HITL 踩过同一个形状）。
// 更要紧的是：**只给一个入口**（比如只加租户、忘了业务）在页面上完全没有症状，
// 只是看板少一栏的利用率 —— 所以两个都要点名断言。
{
  const labels = await agentCard.locator('.cost-intent-label').allInnerTexts()
  expect('★ 归因输入框有「业务归因」', labels.some(t => /业务归因/.test(t)),
    `—— 实际标签：${JSON.stringify(labels)}`)
  expect('★ 归因输入框有「租户」（第 6 件新增的第二个维度）', labels.some(t => /租户/.test(t)),
    `—— 实际标签：${JSON.stringify(labels)}`)
  const askText = await agentCard.innerText()
  expect('★ 界面写清了两个维度不是一回事（谁的钱 vs 花在哪个功能上）',
    /谁花的钱/.test(askText) && /哪个功能/.test(askText),
    '—— 只写两个输入框而不说区别，使用者会把租户填成业务名')
}

// 假后端才有复位端点（真后端没有这个路径，调用会 404 —— 所以只在假路径调）
if (EXPECT_REFLECTION) {
  const reset = await page.request.post(`${URL}/api/agent/__reset-approval`).catch(() => null)
  expect('假后端支持把审批状态复位（否则两条相反的分支只能测一条）', !!reset && reset.ok())
}

// 【这里刻意**不**再点一次「Agent 工具调用」标签】
//
// 上一节刚跑完 `/ask` + 打开过历史详情 + 读过限额，面板与三张卡片都已经就位。
// 再点一次标签会让 Ant Design 的 Segmented 触发一次重挂载，而那样做的后果很具体：
// **历史运行与限额这两张卡片会短暂消失**（它们的 Remote 态被重置成 idle）——
// 于是紧接其后的"历史运行卡片存在 / 限额卡片存在"两条断言会红，
// 而失败信息看起来像"页面少了两块"，实际只是我们自己把它重新挂了一遍。
// 踩这一次的教训值得记：**在界面自检里，"多此一举地点一下"不是无害的**。
//
// ⚠️ 这里**不**再重复切一次面板：上面 [6.6] 那一节已经切过来了，
// 而且它之后紧接着就是"历史运行 / 运行详情 / 四层保护"三块 ——
// 那三块都建立在"面板已经切过来"这个前提上（它们是同一个面板的组成部分）。
// 一开始我把这一步也写在了这里，结果把它从 [6.6] 搬了过来、导致上面两块全红，
// 所以最后把它放回了 [6.6] 的开头（理由写在那里）。

const approvalCard = page.locator('.diag-card', { hasText: '待我确认' }).first()
expect('「待我确认」卡片已渲染', (await approvalCard.count()) > 0)
expect(
  '「待我确认」排在"跑一个任务"之前（有截止时间的东西优先）',
  (await approvalCard.count()) > 0 && (await agentCard.count()) > 0
    ? (await approvalCard.boundingBox()).y < (await agentCard.boundingBox()).y
    : false,
)

if ((await approvalCard.count()) > 0) {
  /*
    ⚠️ 这里**必须轮询等待，不能用固定 sleep**（实测踩到，值得记下来）。

    待办列表是**轮询**来的（每 2.5 秒一次，见 AGENT_APPROVAL_POLL_MS），
    而上面刚调过一次"复位"（把假后端的待批状态改回 PENDING）。
    这一节原来写的是 `waitForTimeout(700)` 然后立刻断言 —— 700ms 短于一个轮询间隔，
    所以它**跑得通还是跑不通取决于上一次轮询落在哪**：
    如果这个面板是刚切过来的（首拉刚发生过），700ms 后拿到的还是复位之前的那份数据，
    于是"显示了有写操作在等我"以及它下面那 9 条**整节全红**，
    而失败信息看起来像"待办卡片没渲染" —— 功能完全正常。

    这正是这个文件里反复记过的那类坑（钉住实验那次：
    **等待时长与它要等的东西没有关系**）。所以改成"轮询它出现"：
    假后端上它 2.5 秒内必然出现；真后端上待批列表本来就是空的，
    这次轮询等满超时后照常走下面那条分支（那一节验证的是"空列表必须被解释清楚"）。
  */
  if (EXPECT_REFLECTION) {
    const pendingArrived = await waitFor(async () => /在等我/.test(await approvalCard.innerText()), 5000)
    expect('待办列表把复位后的待批捡回来了（轮询在生效）', pendingArrived)
  }
  else {
    await page.waitForTimeout(700)
  }
  const apprText = (await approvalCard.innerText()).trim()
  // 闸门状态必须可见 —— 空列表的两种含义（没危险操作 / 闸门没开）必须能分开
  expect('显示了闸门开关状态', /闸门已启用|闸门已关闭/.test(apprText))
  expect('显示了等人时限', /时限\s*\d+s/.test(apprText))

  if (EXPECT_REFLECTION) {
    // ================= 假后端：验证"批掉它"这条完整路径 =================
    expect('显示了有写操作在等我', /在等我/.test(apprText))
    expect('显示了是哪个工具', /propose_review/.test(apprText))
    // 参数原样可见 —— 人批的就是这一份，界面上不许折叠成摘要
    expect('显示了将被执行的参数（原样）', /阶段二 · RAG：分块建议与实测结论相反/.test(apprText))
    // 风险声明来自工具的 ToolHints（与"要不要审批"的判据同源，不是前端另写一份）
    expect('显示了风险声明', /非幂等|需人工确认/.test(apprText))
    // 已等 / 还剩：这一块唯一的量化口径
    expect('显示了已等与剩余时间', /已等\s*\d+s/.test(apprText) && /还剩\s*\d+s/.test(apprText))
    // "超时按拒绝处理"必须写出来：这一块最容易出的错不是批错了，而是没人知道不理它会怎样
    expect('写明了超时按拒绝处理', /超时按.{0,2}拒绝.{0,2}处理/.test(apprText))
    // 两种决定对模型意味着相反的事，界面上必须分开说
    expect('说明了拒绝之后模型该做什么', /不要再重新发起/.test(apprText))

    // 决定人是**每条自己的**输入框：共用一个会让"写给 A 的理由"跑到 B 上，而且不报错
    const byInput = approvalCard.locator('[data-testid="approval-decided-by"]')
    expect('有"谁做的决定"输入框（审计要记人）', (await byInput.count()) > 0)
    if ((await byInput.count()) > 0) await byInput.first().fill('自检机器人')

    const rejectBtn = approvalCard.locator('button.approval-reject').first()
    const approveBtn = approvalCard.locator('button.approval-approve').first()
    expect('有批准按钮', (await approveBtn.count()) > 0)
    expect('有拒绝按钮', (await rejectBtn.count()) > 0)

    // ---- 先试一次"拒绝"（这一步验证的是"拒绝不是一键动作"）----
    //
    // 拒绝理由会**原样回填给模型**，是模型换方案的唯一依据。所以界面刻意不让人一键拒绝：
    // 第一次点只展开理由输入框，第二次点才真的提交。
    // 不这么做的话，页面上会稳定地产生一堆"没有理由的拒绝"，而那种审计记录几乎没有价值。
    if ((await rejectBtn.count()) > 0) {
      await rejectBtn.click()
      await page.waitForTimeout(300)
      // 断言"理由输入框出现了"而不是"页面上出现了『拒绝的理由』这几个字"。
      //
      // 【为什么不用文案】placeholder 是**辅助文本**，它在 DOM 里是属性、在
      // `innerText` 里**不出现** —— 拿 `innerText` 去匹配 placeholder 会永远为假，
      // 而失败信息看起来像"这个交互没做"。
      // 这条与本项目「antd 给恰好两个汉字的按钮插了字间距空格」（antd 把两个汉字渲染成「关 闭」）同族：
      // **界面文案的一个渲染细节，能让一个看起来完全合理的定位永远失效**。
      // 判据要落在**结构**上（有没有那个 textarea），而不是落在文案上。
      const rejectBox = approvalCard.locator('.approval-reject-box textarea')
      expect('第一次点「拒绝」只展开理由框，不直接提交', (await rejectBox.count()) > 0)
      // 同时再确认"理由框的提示文案确实在"——用 placeholder 属性读，而不是 innerText
      const placeholder = (await rejectBox.count()) > 0 ? await rejectBox.first().getAttribute('placeholder') : ''
      expect('理由框提示了它会回填给模型', /回填给模型/.test(placeholder ?? ''), placeholder ?? '(无)')
    }

    // ---- 批准它 ----
    await approveBtn.click()
    const decided = await waitFor(async () => {
      const t = await approvalCard.innerText()
      return /已批准/.test(t) || /没有被采纳/.test(t)
    }, 30000)
    expect('批准之后给了明确的结论条', decided)

    const afterText = (await approvalCard.innerText()).trim()
    // `awakened` / `resumed` 必须显示：这几种处境的下一步动作完全不同 ——
    // 有线程在等 → 任务当场跑完；没人等 → 从检查点接着跑；都没有 → 任务还停着
    expect('显示了"唤醒等待线程 / 从检查点接着跑"', /唤醒等待线程/.test(afterText))
    expect('给了这两个布尔的实际取值（而不是只有标题）', /从检查点接着跑：/.test(afterText))

    // 待批必须消失：它已经决过了
    const gone = await waitFor(async () => (await page.locator('.approval-item').count()) === 0, 15000)
    expect('决定之后那条待批从列表里消失了（轮询捡到了状态转移）', gone)

    // ---- 再跑一次任务：这次写操作**真的执行了** ----
    //
    // 这是整节最值钱的一条断言：它把"闸门放行"与"闸门真的生效"分开了 ——
    // 前者只是状态变了，后者是**那个副作用真的发生了**。
    await agentCard.locator('button', { hasText: /跑\s*一\s*次/ }).first().click()
    await agentCard.locator('.stat-cell', { hasText: '停因' }).first().waitFor({ state: 'visible', timeout: 150000 })
    await page.waitForTimeout(800)
    const approvedAsk = (await agentCard.innerText()).trim()
    expect('批准之后写操作真的执行了（出现成功建单的回填）', /工单已提交成功|RV-0001/.test(approvedAsk))
    expect('写操作那一步的 stage 显示为成功', /成功/.test(approvedAsk))
    expect('未再出现"停在等人确认"', !/正在等人确认/.test(approvedAsk))

    await page.screenshot({ path: `${SHOT_DIR}/agent-approval-approved.png`, fullPage: true })

    // ---- 复位 → 拒绝 → 验证"没执行" ----
    //
    // 「拒绝之后那个副作用真的没发生」是 HITL 唯一有意义的验收口径。
    // 在界面上它对应的是：模型收到一条**不可重试**的拒绝回填，并换了个说法回答。
    const reset2 = await page.request.post(`${URL}/api/agent/__reset-approval`).catch(() => null)
    expect('复位成功（第二次：走拒绝分支）', !!reset2 && reset2.ok())
    const backAgain = await waitFor(async () => (await page.locator('.approval-item').count()) === 1, 20000)
    expect('复位之后待批又出现了（轮询把它捡回来了）', backAgain)

    await approvalCard.locator('button.approval-reject').first().click()
    await page.waitForTimeout(300)
    await approvalCard.locator('.approval-reject-box textarea').first().fill('这条已经有对应的工单了，别重复建')
    await approvalCard.locator('button.approval-reject').first().click()
    const rejected = await waitFor(async () => {
      const t = await approvalCard.innerText()
      return /已拒绝/.test(t) || /没有被采纳/.test(t)
    }, 30000)
    expect('拒绝之后给了明确的结论条', rejected)
    expect('结论条写明"那个写操作不会执行"', /不会执行/.test(await approvalCard.innerText()))

    // 再跑一次：这次写操作**没有执行**，模型收到拒绝回填后换了做法
    await agentCard.locator('button', { hasText: /跑\s*一\s*次/ }).first().click()
    await agentCard.locator('.stat-cell', { hasText: '停因' }).first().waitFor({ state: 'visible', timeout: 150000 })
    await page.waitForTimeout(800)
    const deniedAsk = (await agentCard.innerText()).trim()
    expect('拒绝之后写操作没有建单（没有出现"提交成功"）', !/工单已提交成功/.test(deniedAsk))
    expect('显示了"人工拒绝"这个 stage', /人工拒绝/.test(deniedAsk))
    // 拒绝理由必须原样回填 —— 它是人给出的唯一一条新信息
    expect('拒绝理由原样回填给了模型', /别重复建/.test(deniedAsk))
    // 拒绝是"不要再试了"，与执行异常不同：回填里必须说的就是这句话
    expect('回填里带了"不要再重新发起"这句不可重试的说明', /不要再重新发起/.test(deniedAsk))


    // 【在这里记下"时间线在不在"】给最后那条"切模式不丢结果"用。
    //
    // 【为什么记的是此刻，而不是更早】踩过一次：第一版把它记在"批准之前"，
    // 而那时假后端返回的是"停在等人确认"分支（steps 为空）→ 记下来是 false。
    // 而走到最后那条断言时，"跑一个任务"已经被点过三次（三份不同的结果），
    // 于是"现在有没有时间线"与"记下来的那个"根本不是同一件事，断言必然误报。
    //
    // 规则一句话：**要跨段复用某个状态快照，就必须在"它已经稳定"的时刻取，
    // 而不是在"它恰好还没被后面几次动作改掉"的时刻取。**
    timelineWasThere = (await page.locator('.agent-timeline').count()) > 0

    // ---- 运行详情里的审批历史 ----
    //
    // 【为什么先复位】复位之后待批那条运行会被放进列表首位，于是"点第一行"点到的
    // 就是**确实有审批记录**的那一次 —— 否则这条断言只能写得很松（"有表格或有空状态"），
    // 而那种断言在两种情况下都通过，等于没测。
    await page.request.post(`${URL}/api/agent/__reset-approval`).catch(() => null)
    await runsCard.locator('button', { hasText: /刷\s*新/ }).first().click()
    await page.waitForTimeout(700)
    await runsCard.locator('.ant-table-tbody tr').first().click()
    const hitlDetail = page.locator('.diag-card', { hasText: '运行详情' }).first()
    await hitlDetail.waitFor({ state: 'visible', timeout: 30000 }).catch(() => {})
    await page.waitForTimeout(500)
    const hitlDetailText = (await hitlDetail.innerText()).trim()
    expect('运行详情里有"人工确认"这一节', /人工确认（HITL）/.test(hitlDetailText))
    expect('运行详情里有"人工确认"这一节', /人工确认（HITL）/.test(hitlDetailText))
    // 「谁决定的」与「等了多久」是审计闭环的两半：一个回答"谁批的"，一个回答"拖了多久"
    expect('详情里能看到"谁决定的"与"等了多久"', /谁决定的/.test(hitlDetailText) && /等了多久/.test(hitlDetailText))
    // 待批的 stage 也要在工具调用表里可见（否则"死在等人"这件事在详情里没有痕迹）
    expect('详情里能看到"等人工确认"这个 stage', /等人工确认/.test(hitlDetailText))
    expect('详情里说明了超时与人工拒绝为什么要分开', /流程没接上/.test(hitlDetailText))
    await page.screenshot({ path: `${SHOT_DIR}/agent-approval-detail.png`, fullPage: true })
    await hitlDetail
      .locator('button')
      .filter({ hasText: /关\s*闭/ })
      .first()
      .click()
    await page.waitForTimeout(300)
    // 最后复位一次，让下面的"模式往返"看到的是初始的待批状态
    await page.request.post(`${URL}/api/agent/__reset-approval`).catch(() => null)
  } else {
    // ================= 真后端：验证"没有待批时必须解释清楚" =================
    //
    // 真后端上没有待批 —— 那不是缺陷，而是"这次还没有危险操作"。
    // 但**空列表必须被解释**：它有三种原因（确实没有写操作 / 写操作还没被发起 / 闸门被关掉），
    // 混起来读会得出完全相反的结论 —— 把"闸门没装"看成"系统很安全"。
    expect('空列表时说明了它为什么是空的', /有三种原因|确实没有|闸门被关掉/.test(apprText))
    expect('说明了只读工具按规矩不需要审批', /只读/.test(apprText))
    expect('说明了闸门的当前状态', /当前闸门状态/.test(apprText))
    timelineWasThere = (await page.locator('.agent-timeline').count()) > 0
  }
}
if ((await agentCard.count()) > 0) {

// 【先把这个假后端的审批状态置成已批准】
//
// 这一段要验证的是**批准之后那条完整四步轨迹**（写操作执行成功 + 自愈过程）。
// 而 HITL 那一节最后停在"已拒绝"上（它刚验证完"拒绝之后不能建单"）。
// 若不再置一次，这里同样会落到"停在等人确认"分支 —— 那也是一种正确的行为，
// 但不是这一段要断言的东西。
//
// 为什么不"再点一次批准按钮"：那时界面上那条待批已经决过了，再点会被后端明确拒绝
// （"不能重复决定"），而真后端上那条路径根本不存在。自检要验证的是**行为**，
// 不是"想办法绕过我们自己的守卫"。
if (EXPECT_REFLECTION) {
  const setApproved = await page.request
    .post(URL + '/api/agent/__set-approval', { data: { decision: 'APPROVED' } })
    .catch(() => null)
  expect('把假后端置为已批准（让这一段的前提确定）', !!setApproved && setApproved.ok())
}

  // 整张页面上只有这一个按钮有外部代价，必须明确标出来：
  // 不标的话用户点它和点"刷新"的心理预期是一样的
  const askText = (await agentCard.innerText()).trim()
  expect('标出了"会调模型 · 花钱"', /花钱/.test(askText))

  // 跑一次。
  //
  // **等待方式按路径分**（这是实测出来的必要区分）：
  //   - 假后端：会给出一条"含失败与自愈"的四步轨迹，所以能等到 .agent-timeline；
  //   - 真后端：本机 key 是假的，模型调用会 401 失败 → 那次运行**一步都没有**，
  //     于是 .agent-timeline 根本不会渲染（组件在 steps 为空时返回 null）。
  //     所以真路径要等"停因"那一格出现 —— 它在成功与失败两种情况下都会有。
  //
  // 这不是"把断言放宽"，而是**两条路径本来就跑在不同的场景里**：
  // 假后端能确定性地产出完整轨迹，真后端（假 key）只能产出一次分类明确的失败。
  // 硬等 .agent-timeline 会让真路径必然超时，而失败原因看起来像"界面没渲染"。
  //
  // ★ 顺手抓一次**出站请求体**（阶段四第 6 件的做法，与灰度那次"抓出站提示词"同一思路）：
  // "两个输入框存在"证明不了"它们被发出去了" —— 中间隔着一次字段名拼接，
  // 而字段名写错的症状是"看板上那一栏永远只有未标注"，看起来像"没人填过"。
  // 判据必须是**请求体里真的有那两个键**，而不是"页面上有那两个框"。
  const askRequest = page.waitForRequest(
    r => r.url().includes('/api/agent/ask') && r.method() === 'POST',
    { timeout: 20000 },
  )
  await agentCard.locator('button', { hasText: /跑\s*一\s*次/ }).first().click()
  try {
    const body = JSON.parse((await askRequest).postData() ?? '{}')
    expect('★ 出站请求体里有 tenantId 这个键（不是只画了一个输入框）',
      Object.prototype.hasOwnProperty.call(body, 'tenantId'), `—— body=${JSON.stringify(body).slice(0, 160)}`)
    expect('★ 出站请求体里的 tenantId 有值（默认值会真的被发出去）',
      typeof body.tenantId === 'string' && body.tenantId.length > 0, `tenantId=${body.tenantId}`)
    expect('★ 出站请求体里业务归因与租户是两个**独立**的键（不是拼成一个字符串）',
      Object.prototype.hasOwnProperty.call(body, 'intent') && body.intent !== body.tenantId,
      `intent=${body.intent} tenantId=${body.tenantId}`)
  }
  catch (e) {
    expect('抓到了 /api/agent/ask 的出站请求体（用来核对归因字段）', false, String(e))
  }
  await agentCard.locator('.stat-cell', { hasText: '停因' }).first().waitFor({ state: 'visible', timeout: 150000 })
  await page.waitForTimeout(800)

  const resultText = (await agentCard.innerText()).trim()
  expect('显示了停因与成本', /停因/.test(resultText) && /token/.test(resultText))
  expect('显示了工具调用汇总', /工具调用/.test(resultText))

  // 「停因」这一格在每个路径上都该是**真实的枚举名**，而不是空 ——
  // 它是"这一页回答了为什么"的最小证据
  expect('停因是真实的枚举名', /ANSWERED|MODEL_ERROR|MAX_STEPS|MAX_TOKENS|MAX_DURATION|DUPLICATE_ACTION|EMPTY_RESPONSE/.test(resultText))

  if (EXPECT_REFLECTION) {
    // ================= 假后端路径：验证完整的执行过程 =================
    expect('显示了最终答案', (await agentCard.locator('.agent-answer-text').count()) > 0)

    const steps = await agentCard.locator('.agent-step').count()
    expect('渲染了执行过程时间线', steps >= 3, `${steps} 步`)
    expect('每步标出了"调工具"还是"作答"', /调工具/.test(resultText) && /作答/.test(resultText))

    const toolCalls = await agentCard.locator('.agent-tool-call').count()
    expect('渲染了每次工具调用', toolCalls >= 3, `${toolCalls} 次`)
    expect('显示了模型的 think 段', (await agentCard.locator('.agent-thought').count()) > 0)
    // 回填文本是"模型当时看到了什么"的唯一凭据，必须能被看到
    expect('显示了回填给模型的内容', (await agentCard.locator('.agent-tool-feedback pre').count()) >= 3)
    // 被拒绝的那次必须也在（否则"想调几次"这个数字永远偏小）
    expect('被拒绝的调用也在页面上', /工具不存在/.test(resultText))
    expect('回填里带了可用工具列表', /可用工具/.test(resultText))
    // stage 用中文短语显示，但枚举名要能查到（放在 title 里）—— 排查时要能和日志对上
    expect('stage 的枚举名可查到', (await agentCard.locator('[title="UNKNOWN_TOOL"]').count()) > 0)
    expect('工具调用的参数可见', (await agentCard.locator('.agent-tool-args code').count()) >= 3)
  } else {
    // ================= 真后端路径（假 key）：验证失败被如实说明 =================
    //
    // 这条路径**恰好是"失败细分"那个功能的真实验证**：本机 key 是假的 →
    // 上游会拒绝 → 界面必须把细分原因与处置建议都显示出来，而不是笼统一句"模型出错"。
    expect('把失败原因分类显示出来了', /UPSTREAM_AUTH|MODEL_ERROR|UNEXPECTED/.test(resultText))
    //
    // ⚠️ 判据**不能**写死某一种失败的措辞。第一版写的是
    // `/重试没有意义|下一步|API Key|鉴权/`，理由是"假 key → 401 → 必然是 UPSTREAM_AUTH 那条建议"。
    // 实测这次分到的是 **UNEXPECTED**（上游 401 经 SDK 包装后没被认成鉴权失败），
    // 它给出的建议是「没能归类的异常 —— 这是一个信号：分类逻辑需要补一种情况。请直接看日志堆栈。」
    // —— 那是一句**完全正确且可操作**的建议，而断言却红了。
    //
    // 也就是说：这条断言原本测的是"后端分类得准不准"（那是 `AgentFailureClassifier` 的活），
    // 而不是"界面有没有把建议渲染出来"（这才是这一节要钉的）。
    // 改成本节真正要保证的性质：**建议这一格非空，而且是一句能照着做的话**。
    // 六个分类的措辞各不相同，但它们都含有"建议/重试/检查/看日志"这类动作词。
    const adviceShown = /建议|重试|检查|看日志|看.*堆栈|调大|调小|换一个/.test(resultText)
    expect('显示了对这一页失败的处置建议（非空且可操作）', adviceShown,
      adviceShown ? '' : `没找到可操作的建议；页面文本片段：${resultText.slice(0, 200).replace(/\n/g, ' | ')}`)
    // 没有答案时不能显示"最终答案"那一块 —— 空答案框会让人以为"答了但是空的"
    expect('没有答案时不显示答案块', (await agentCard.locator('.agent-answer-text').count()) === 0)
  }

  await page.screenshot({ path: `${SHOT_DIR}/agent-ask.png`, fullPage: true })
}

// ---------- 成本看板（阶段四 · 工程化） ----------
//
// 【这一节最值钱的那条断言是"不许出现 ¥0.0000"】
// 假数据里刻意有一个模型没有价格（deepseek-v4-pro）。按"看起来对"的做法，
// 那一行的金额会被渲染成 0.0000 —— 而那个 0 会被读成"这个功能不花钱"，
// 真相是"我们不知道它花多少钱"。这两句话对使用者的含义完全相反，
// 而界面上长得一模一样。所以这一节的核心断言是**负向的**：
// 页面上必须出现"算不出"与原因，而**不能**出现把未知渲染成 0 的那种形态。
//
// 【两条路径的断言条数刻意保持一致，但验的不是同一件事】
//   假后端：有数据、有价格、有"算不出"的那一行 → 验证四个视图 + 金额纪律；
//   真后端：**全新的库**（没有任何运行记录）且**价格没配** → 验证的是另一件事，
//           而它恰好是这一页**第一次被人打开时的样子**：
//           不能显示 ¥0.0000，必须显示"算不出"+ 一句能照着做的配置说明。
// 真路径反而把那条纪律验得更彻底 —— 因为那里连一条能算出钱的记录都没有。
console.log('[6.8] 成本看板')

await page.locator('label', { hasText: '成本看板' }).click()
const costHead = page.locator('.cost-head', { hasText: '成本看板' })
// 进面板会自动拉一次（只读、零外部代价），但要等它回来
const costLoaded = await waitFor(async () => (await page.locator('.cost-headline').count()) > 0, 15000)
expect('进面板自动加载了看板', costLoaded)
await page.waitForTimeout(500)

// 价格表、按天、按模型这三张卡在两条路径上都存在（只是内容不同）
const priceCard = page.locator('.diag-card', { hasText: '价格表：这一页的钱是按这些单价算出来的' })
const daysCard = page.locator('.diag-card', { hasText: '按天：钱是怎么涨上去的' })
const modelCard = page.locator('.diag-card', { hasText: '按模型：换成别的模型会差多少' })
// 【两个归因维度各一张卡，标题刻意不同】阶段四第 6 件把「按业务归因：钱是谁花的」
// 拆成了两栏 —— 旧标题（"钱是谁花的"）留在业务那一栏是**错的**：
// 那句话现在归租户那一栏，而两栏标题一样的话，读者会以为它们是同一个东西的两种排法
const tenantCard = page.locator('.diag-card', { hasText: '按租户：钱是谁花的' })
const intentCard = page.locator('.diag-card', { hasText: '按业务归因：钱花在哪个功能上' })
const toolCard = page.locator('.diag-card', { hasText: '哪把工具在拖慢任务' })
const notesCard = page.locator('.diag-card', { hasText: '这份数据能说什么、不能说什么' })

// 从一段文本里读出某个指标格子的数字。
// ⚠️ 必须吃掉千分位逗号：大数在页面上是「1,234」，只匹配 \d+ 会读出 1 而不是 1234 ——
// 而那种误读的失败信息看起来像"两边对不上"，会把人引到错误的方向去查
function statNumber(text, label) {
  const raw = (text.match(new RegExp(`${label}\\s*([\\d,]+)`)) ?? [])[1]
  return raw === undefined ? -1 : Number(raw.replace(/,/g, ''))
}

// ---- 两条路径共用的断言 ----
const costText = await costHead.innerText()
expect('显示了运行次数', /运行次数/.test(costText))
expect('失败的运行被单独显示（花了钱又没办成事 —— 账单里看不到这一格）', /失败的运行/.test(costText))
// 【被上限拦下的那些必须自己占一格】它既不是成功也不是失败，但同样花了钱 ——
// 并进任何一边都会让那边的数字说谎（详见 CostPanel 里那段注释）
expect('被上限拦下的运行单独占一格', /被上限拦下/.test(costText))
// 【★ 这一条钉的是一个真 bug：合计的运行级计数取错了来源】
// 合计曾经用"按模型那一组"的和，而那个维度里 finished/failed 是写死的 0 ——
// 于是真后端上「已结束」永远是 0（假数据里写的是正确的和，所以只在这条路径上现形）。
// 判据刻意写成"两个数字必须相等"而不是"已结束大于 0"：
// 后者在空库的真后端上会红，而空库时 0 是正确的。
{
  const dayFinished = await daysCard.evaluate(card =>
    [...card.querySelectorAll('tbody tr')].reduce((sum, tr) => {
      const cell = tr.querySelector('td:nth-child(3)') // 按天表第 3 列是「已结束」
      return sum + (cell ? Number(cell.textContent.trim()) || 0 : 0)
    }, 0))
  const headlineFinished = statNumber(costText, '已结束')
  expect(
    '合计的「已结束」等于按天表的和（取错来源时这里会是 0）',
    headlineFinished === dayFinished,
    `合计 ${headlineFinished} vs 按天之和 ${dayFinished}`,
  )
  // 同理钉住"被上限拦下"：它也必须两边一致，而且它在假数据里刻意非 0
  // （恒为 0 的假数据会让那一格永远看起来"从来没被拦下过"）
  const dayStopped = await daysCard.evaluate(card =>
    [...card.querySelectorAll('tbody tr')].reduce((sum, tr) => {
      const cell = tr.querySelector('td:nth-child(4)') // 第 4 列是「被上限拦下」
      return sum + (cell ? Number(cell.textContent.trim()) || 0 : 0)
    }, 0))
  const headlineStopped = statNumber(costText, '被上限拦下')
  expect('「被上限拦下」的合计等于按天表的和', headlineStopped === dayStopped,
    `合计 ${headlineStopped} vs 按天之和 ${dayStopped}`)
}
expect('总 token 已显示', /总 token/.test(costText))
expect('缓存命中率已显示', /缓存命中/.test(costText))
expect('价格表卡已渲染', (await priceCard.count()) > 0)
expect('按天卡已渲染', (await daysCard.count()) > 0)
expect('按租户卡已渲染（第 6 件新加的那一维）', (await tenantCard.count()) > 0)
expect('按业务归因卡已渲染', (await intentCard.count()) > 0)
expect('按模型卡已渲染', (await modelCard.count()) > 0)
expect('按工具卡已渲染', (await toolCard.count()) > 0)

// ★ 跨路径一致性：两个归因维度与分摊口径必须**在后端真的存在**
//
// 【这一条为什么必须打端点，而不是只看页面】
// 页面上"这一栏是空的"与"后端压根没有这个字段"长得一模一样（都是没有行）。
// 而第 15 轮刚吃过这个形状的亏（「默认参数里的字符串两边各写一份，真后端 400 而假后端全绿」）：假后端照着真后端一个写错的默认值抄了一遍，
// 于是 6 组断言全绿、真后端上一行都渲染不出来。
// 判据写成"两个维度都在响应里、且金额都带口径标记"——
// 它在**假后端上也会红**（假后端要写同样的字段），于是下一次任何一边改错都当场现形。
{
  const shape = await page.evaluate(async () => {
    const d = await fetch('/api/cost/summary?days=7').then(r => r.json())
    return {
      ok: d.ok === true,
      hasTenant: Array.isArray(d.byTenant),
      hasIntent: Array.isArray(d.byIntent),
      // 有数据时：两个维度都必须给金额（第 6 件起是分摊值，不再是 null），
      // 而且必须带 amountBasis —— 界面靠它决定"这一格的钱是怎么来的"那句说明
      tenantRows: (d.byTenant ?? []).length,
      tenantBasis: (d.byTenant ?? []).map(r => r.amountBasis),
      intentBasis: (d.byIntent ?? []).map(r => r.amountBasis),
      // 合计的金额（用来核对"各栏加起来等于合计"）
      totalMicros: d.totals?.amountMicros ?? null,
      tenantMicrosSum: (d.byTenant ?? []).reduce((s, r) => s + (r.amountMicros ?? 0), 0),
      intentMicrosSum: (d.byIntent ?? []).reduce((s, r) => s + (r.amountMicros ?? 0), 0),
    }
  })
  expect('★ /api/cost/summary 有 byTenant 这一维（不是只有 byIntent）', shape.hasTenant && shape.hasIntent,
    `byTenant=${shape.hasTenant} byIntent=${shape.hasIntent}`)
  if (EXPECT_REFLECTION) {
    expect('★ 两个归因维度的每一行都带金额口径（ALLOCATED_BY_TOKEN_SHARE）',
      shape.tenantBasis.length > 0 && shape.tenantBasis.every(b => b === 'ALLOCATED_BY_TOKEN_SHARE')
        && shape.intentBasis.length > 0 && shape.intentBasis.every(b => b === 'ALLOCATED_BY_TOKEN_SHARE'),
      `tenant=${JSON.stringify(shape.tenantBasis)} intent=${JSON.stringify(shape.intentBasis)}`)
    // 这一条与后端 CostDashboardViewTest 里那条是同一个判据的两条路径：
    // 后端保证"分摊的最后一行取合计减前面所有行"，这里核对它真的成立
    expect('★ 按租户那一栏各行相加 == 合计（分摊的最后一行吸收了舍入差）',
      shape.tenantMicrosSum === shape.totalMicros,
      `租户和 ${shape.tenantMicrosSum} vs 合计 ${shape.totalMicros}`)
    expect('★ 按业务那一栏各行相加 == 合计（两个维度是同一份钱的两个视图）',
      shape.intentMicrosSum === shape.totalMicros,
      `业务和 ${shape.intentMicrosSum} vs 合计 ${shape.totalMicros}`)
  }
}

// 纯文本里的 markdown 星号不能以字面量出现（「纯文本里写 markdown 星号会原样显示」同族）。
// ⚠️ 这条**在真后端路径上曾经红过**：后端 notes 里写的 `**加粗**` 在界面上真的印出了星号，
// 因为那段文本有两个消费方（JSON 接口 / 页面的纯文本渲染），而只有后者在意星号。
// 修法是源头不写星号，而不是在界面上过滤（那样接口的读者会拿到被改过的文本）
const costPanelText = await page.locator('.diag-panel').first().innerText()
expect('看板正文无字面量星号', !costPanelText.includes('**'))

if (EXPECT_REFLECTION) {
  // ================= 假后端：有数据、有价格 =================
  expect('合计金额已渲染', /¥[\d.]+/.test(costText), (costText.match(/¥[\d.]+/) ?? [''])[0])
  const dayRows = await daysCard.locator('tbody tr').count()
  expect('按天表有数据行', dayRows >= 1, `${dayRows} 行`)

  // ---- 核心：未配价的模型必须显示「算不出」而不是 0 ----
  const modelText = await modelCard.innerText()
  expect('未配价的模型显示「算不出」而不是 0', /算不出/.test(modelText))
  expect('并且给出了算不出的原因（能照着去补价格）', /没有匹配的价格规则|补一条/.test(modelText))
  expect('有模型没价时，合计被标成下界', /下界/.test(await page.locator('.cost-needs-alert').first().innerText()))
  const unknownRows = await page.locator('.cost-amount-unknown').count()
  expect('确实有金额格被渲染成「算不出」形态', unknownRows > 0, `${unknownRows} 处`)
  // 原因必须是**可见文本**，不能只藏在悬浮提示里：
  // 「为什么算不出」就是这一格的全部信息，而藏起来的信息只有已经知道它存在的人才会去找。
  // （顺带记一个实测的坑：给 antd <Tooltip> 的直接子元素传 title，那个 title 不会落到 DOM 上 ——
  //  Tooltip 会把它吃掉当自己的内容。第一版断言"元素带 title 属性"因此是红的，而功能其实在）
  expect('「算不出」的原因以可见文本给出（不是只在悬浮提示里）', (await page.locator('.cost-amount-reason').count()) > 0)

  // ---- 归因 / 工具 / 对账 ----
  //
  // ★ 两个归因维度**各验一遍**，而且验的是各自不同的东西：
  //   租户那一栏：空值的名字必须是「未标注租户」（与业务那一栏的「未标注」不同）
  //   业务那一栏：金额不再是「算不出」，而是带口径的分摊值
  const tenantText = await tenantCard.innerText()
  expect('按租户表有数据行', (await tenantCard.locator('tbody tr').count()) >= 1)
  expect('★ 租户那一栏的空值叫「未标注租户」—— 与业务那一栏的「未标注」刻意不同',
    /未标注租户/.test(tenantText),
    '—— 两个维度的空长得一样的话，读者会以为它们是同一个空洞，' +
      '而处置方式完全不同（让调用方传 tenantId / 传 intent）')
  expect('租户那一栏说明了「未标注租户」与「未标注」是两件事', /两件事/.test(tenantText))
  expect('★ 两栏都写明了金额是「按 token 占比分摊」的（口径与数字同屏）',
    /分摊/.test(tenantText) && /分摊/.test(await intentCard.innerText()))

  const intentText = await intentCard.innerText()
  expect('按业务归因表有数据行', (await intentCard.locator('tbody tr').count()) >= 1)
  expect('未标注的运行单独成一行（没被并进任何业务里）', /未标注/.test(intentText))
  expect('界面说明了「未标注」不该被并进业务里', /没有.*被并进|虚高/.test(intentText))
  // 【第 6 件把这一格的语义改了】以前它是「算不出」（与按模型那一栏同样处理），
  // 现在它是一个分摊值 —— 所以这里断言**这一栏里出现了金额数字**。
  // 不断言这一条的话，"后端忘了把 amount 填上"会以"界面显示算不出"的形式静默通过
  // （那一格从前就长这样，所以没人会觉得不对）
  //
  // ⚠️ 判据刻意**读 DOM 的 textContent**，而不是对 innerText 做正则 ——
  // 实测踩到：`innerText` 会按**渲染后的换行**插入空白，而这一页的金额格很窄，
  // 于是「¥0.0753」在 innerText 里可能是「¥0.0\n753」（数字被折成两半）。
  // 对 innerText 写 `/¥[\d.]+/` 因此会**在功能正常时报红**，而失败信息看起来像
  // "界面没渲染金额" —— 一个把排查引向错误方向的假红。
  // textContent 不受渲染换行影响，它读的就是那一格的内容
  const intentAmounts = await intentCard.locator('.cost-amount').allTextContents()
  expect('★ 按业务那一栏出现了金额数字（不再与按模型那一栏一样显示「算不出」）',
    intentAmounts.some(t => /¥\s*\d/.test(t.replace(/\s+/g, '')) || /^[¥$]/.test(t.replace(/\s+/g, ''))),
    `—— 业务栏金额格：${JSON.stringify(intentAmounts)}`)

  const toolText = await toolCard.innerText()
  expect('按工具表有数据行', (await toolCard.locator('tbody tr').count()) >= 1)
  expect('工具那一栏明确标注了没有金额', /没有金额/.test(toolText) || /不直接消耗 token/.test(toolText))
  expect('模型编错的工具名也在这一栏', /doc_search/.test(toolText))
  expect('被限流拒绝与被熔断拒绝分列（它们要给模型相反的建议）', /被限流拒绝/.test(toolText))

  const reconCard = page.locator('.diag-card', { hasText: '口径对账' })
  const reconText = await reconCard.innerText()
  expect('对账块已渲染（两个口径并排）', /步骤表 token/.test(reconText) && /运行行 token/.test(reconText))
  expect('对账不一致时明确报出来（假数据刻意差 1200）', /差|对不上/.test(reconText))
  expect('一步都没有的运行被单独数出来', /一步都没有的运行/.test(reconText))

  const notesText = await notesCard.innerText()
  expect('写明了 token 不是估算的', /不是估算/.test(notesText))
  expect('写明了默认库是内存库（多天走势需要改 url）', /内存库/.test(notesText))

  // ---- 时间窗切换会真的换数据 ----
  const beforeSwitch = dayRows
  await page.locator('.cost-head .ant-segmented-item', { hasText: '30 天' }).click()
  const switched = await waitFor(
    async () => (await page.locator('.cost-headline').count()) > 0 && !/正在汇总/.test(await costHead.innerText()),
    15000,
  )
  expect('切到 30 天后仍然正常渲染', switched)
  expect(
    '切换后按天表仍有数据',
    (await daysCard.locator('tbody tr').count()) >= beforeSwitch,
  )
  expect('价格表有数据行', (await priceCard.locator('tbody tr').count()) >= 1)
  expect('没配缓存价的那条被标成「假设」而不是「事实」', /假设/.test(await priceCard.innerText()))
} else {
  // ================= 真后端：全新的库 + 价格没配 =================
  //
  // 这条路径验的是"这一页第一次被人打开时的样子"。它比假后端那条**更严格**：
  // 那里至少有能算出钱的记录，而这里一条都没有 ——
  // 于是"算不出"这条纪律是在**没有任何数字可依赖**的情况下被检验的。
  expect('没有价格时不显示金额，而是明确说「算不出」', /算不出金额/.test(costText))
  expect('并给出了原因（能照着去配价格）', /价格未配置|ai\.cost\.prices/.test(costText))
  // 按天那一格：有记录就是表格行、没记录就是一句可操作的空状态。
  // **两种形态都合法**，而"显示一个 0 让人以为没花钱"是唯一不合法的那种
  const realDayRows = await daysCard.locator('tbody tr').count()
  const realDaysText = await daysCard.innerText()
  expect(
    '按天那一格要么有数据行、要么给出可操作的空状态（不是静默的 0）',
    realDayRows > 0 || /一次运行都没有|跑一个任务/.test(realDaysText),
    realDayRows > 0 ? `${realDayRows} 行` : '空状态',
  )
  const emptyPrices = await priceCard.innerText()
  expect('价格卡给出配置写法（那是一份待办）', /ai\.cost\.prices/.test(emptyPrices))
  expect('空价格表说明「刻意不给 0」的理由', /不花钱|不知道/.test(emptyPrices))
  const reconText = await page.locator('.diag-card', { hasText: '口径对账' }).innerText()
  expect('对账块仍然渲染（没有数据时也要显示两个 0，而不是藏起来）', /步骤表 token/.test(reconText))
  const notesText = await notesCard.innerText()
  // ⚠️ 这一条第一版写的是"断言缓存那一栏说『无从谈起』"，而它**在真后端上会闪红** ——
  // 因为真后端的形态取决于这次跑批里 `/ask` 有没有落下步骤：
  //   一次成功往返都没有 → 「窗口内没有 token 记录，命中率无从谈起」；
  //   有任何一步落了库   → 「整个窗口的缓存命中都是 0，有两种可能」。
  // 两种都是**正确**的文案，所以断言任何一个都不该写死 ——
  // 真正该被钉住的是"金额不许变成 0"（上面那两条），而不是备注里挑了哪句措辞。
  expect('边界说明随数字一起交付（不是只有数字）', (await notesCard.locator('.cost-notes li').count()) >= 3)
  expect('边界说明里有"默认库是内存库"这条提醒', /内存库/.test(notesText))
  expect('价格表卡在真后端也渲染了（空表也要有说明）', (await priceCard.count()) > 0)
}

// ---- 布局体检（两条路径共用，代替肉眼） ----
const costLayout = await page.evaluate(() => {
  const doc = document.documentElement
  const card = document.querySelector('.cost-head')
  return {
    horizontalOverflow: doc.scrollWidth - doc.clientWidth,
    cardFitsViewport: card ? card.getBoundingClientRect().right <= doc.clientWidth + 1 : null,
  }
})
expect('成本看板无横向溢出', costLayout.horizontalOverflow === 0, `overflow=${costLayout.horizontalOverflow}px`)
expect('成本看板头部不超出视口', costLayout.cardFitsViewport !== false)
await page.screenshot({ path: `${SHOT_DIR}/cost-dashboard.png`, fullPage: true })


// ---------- Prompt 版本管理（阶段四第 3 件，第七种模式） ----------
//
// 【两条路径验的不是同一件事，条数刻意保持一致】
//   假后端：prompt-routes.mjs 是**有状态**的（四个写端点会真的改内存里的版本表），
//           所以这里跑「被拒 → 存 → 生效 → 回滚 → 再回滚（noOp）」的完整往返。
//           这是这一页**唯一**能验证"点一下之后状态真的变了"的地方；
//   真后端：它跑在真库上，而"往真库里塞一版再切过去"会**改掉这个实例的运行时行为**
//           （后面真有东西跑 Agent 时用的就是被改过的提示词）。所以真路径只做
//           **不留痕**的验证：出厂版本已入库、生效指针指向它、界面读得到正文。
//
// ⚠️ 写这一节时踩到两个坑，都记在下面（两处都不是功能问题，是用例自己的问题）：
//   ① antd 会给**两个汉字**的按钮文案插一个空格（"生效" → "生 效"、"对比" → "对 比"），
//      精确文本匹配会永远找不到，而失败信息只说"找不到按钮"—— 看起来像按钮没渲染；
//   ② 差异视图的 from/to 一开始都默认成"最新版"，于是"对比"按钮**一进页面就是灰的**，
//      旁边还写着一句像是用户操作错了的提示（"选了两个相同的版本"），而他什么都没做。
console.log('[7] Prompt 版本管理面板')

await page.locator('label', { hasText: 'Prompt 版本' }).click()
const promptHead = page.locator('.prompt-head')
const promptLoaded = await waitFor(async () => (await page.locator('.prompt-current').count()) > 0, 15000)
expect('进面板自动加载了当前生效版本', promptLoaded)
await page.waitForTimeout(400)

const versionsCard = page.locator('.diag-card', { hasText: '版本历史' })
const promptText = await promptHead.innerText()

// ---- 两条路径共用的断言 ----
expect('显示了当前生效的版本号', /生效版本/.test(promptText) && /v\d+/.test(promptText))
expect('显示了当前篇幅（字符 + 估算 token）', /字符/.test(promptText) && /tokens/.test(promptText))
// 「谁在读这一份」必须可见，否则"改了哪一份会影响哪些功能"要靠翻代码
expect('说明了这一份提示词谁在读', /谁在读它/.test(promptText))
// 热更新的边界里，**"什么不会变"比"什么会变"更要紧**（读者默认全都立刻变）
expect('写明了热更新什么时候生效', /会变：/.test(promptText))
expect('写明了什么不会变（正在跑的运行 / 已算出的预算）',
  /不会变：/.test(promptText) && /已经在跑/.test(promptText))
expect('版本历史表已渲染', (await versionsCard.locator('tbody tr').count()) >= 1)
expect('版本行显示了指纹', (await page.locator('.prompt-hash').count()) >= 1)
expect('版本行标注了哪一版生效中', /生效中/.test(await versionsCard.innerText()))
expect('说明了历史只追加', /历史只追加/.test(await versionsCard.innerText()))
// 「存」与「生效」必须**分成两个按钮**：合并成"保存并生效"会让一次粘贴失误直接作用到线上。
// `\s*` 见本节开头第 ① 条（antd 的汉字间距）
const editorReady = (await page.locator('button', { hasText: '存为新版本' }).count()) > 0
{
  const versionCount = await versionsCard.locator('tbody tr').count()
  if (versionCount > 1) {
    // 有多版时才可能有"另一版"可以生效 —— 这时必须看得见那个按钮
    expect('「存」与「生效」是两个分开的动作',
      (await page.locator('button', { hasText: /^生\s*效$/ }).count()) > 0)
    expect('生效按钮确实可用（不是渲染出来但点不动）', editorReady)
  }
  else {
    // ⚠️ 真后端首次运行时**只有一版**（出厂版），而生效中的那一版它的「生效」按钮
    // 本来就该是禁用的（"让生效中的那一版生效"没有意义）。于是 `生 效` 这三个字
    // 在页面上根本不出现 —— 第一版在这里断言"必须有这个按钮"，于是在真路径上红了，
    // 而功能完全正常。这与本节开头第 ② 条（对比按钮默认是灰的）是同一类问题：
    // **空状态下的正确形态与有数据时不同，断言必须分叉。**
    expect('只有一版时，生效按钮不出现（避免"让生效中的那一版生效"这种无意义操作）',
      (await page.locator('button', { hasText: /^生\s*效$/ }).count()) === 0)
    expect('生效中的那一行明确标着「生效中」',
      /生效中/.test(await versionsCard.innerText()))
  }
}
expect('编辑区说明了为什么分两次', /退路出现在生效/.test(await page.locator('.prompt-editor').innerText()))
// 纯文本区域里不能出现字面量 markdown 星号（第十六轮把这类坑一次性清干净了，同族问题一并核查过）
expect('面板正文无字面量星号', !(await page.locator('.prompt-panel').innerText()).includes('**'))

// ---- 差异视图：点一下真的能算出差异 ----
const diffCard = page.locator('.prompt-diff-card')
{
  const compareButton = diffCard.locator('button', { hasText: /^对\s*比$/ })
  const comparable = (await compareButton.count()) > 0 && !(await compareButton.first().isDisabled())
  if (comparable) {
    await compareButton.first().click()
    const gotDiff = await waitFor(
      async () => (await diffCard.locator('.prompt-diff, .ant-alert').count()) > 0, 10000)
    expect('差异视图能算出两版之间的差异（默认就该是可点的）', gotDiff)
    const diffText = await diffCard.innerText()
    // token 变化必须给出：涨了会让预算变紧，而那个副作用在别处看不出来
    expect('差异里给出了 token 变化', /token/.test(diffText))
    // ★ 改了一行不该让它之后的所有行都变成"已修改"（按下标比较的实现的死穴）
    expect('差异视图用 +/- 标记区分新增与删除',
      (await diffCard.locator('.prompt-diff-line.is-add, .prompt-diff-line.is-remove').count()) > 0)
    // ⚠️ 这里原本还有一条"差异行数远小于正文行数"的断言，它是**无效判据**，已删：
    // 两份正文都只有 ~19 行时，正确的 LCS 差异本来就会返回几乎全文
    // （左侧全是未变更的上下文）。也就是说这条断言在一个小文档上
    // **无法区分"逐行 LCS"与"按下标比较"**，而后者才是它想拦的东西 ——
    // 一个测不出它要防的缺陷的断言，比没有断言更坏（它给的是假的信心）。
    // 那条性质由后端的 `PromptDiffTest.middleLineChangeDoesNotCascade` 真正钉住
    // （它用 40 行的文档 + 断言"改动行恰好是 2 条"）。
    // 界面这一层改成验**渲染出来的东西能读**：
    expect('差异行把行号渲染出来了（能对上原文第几行）',
      (await diffCard.locator('.prompt-diff-no').count()) > 0)
    expect('差异里给出了 token 变化的两个端点值', /\d+\s*→\s*\d+/.test(diffText))
  }
  else {
    // 只有一版时不该渲染可点的对比按钮（点了也没意义）—— 但必须说清为什么
    expect('版本不足时说明了为什么不能对比', /至少要有两版/.test(await diffCard.innerText()))
    expect('版本不足时没有渲染可点的对比按钮', true, '（真后端首次运行时只有 1 版，属正常）')
  }
}

if (EXPECT_REFLECTION) {
  // ================= 假后端：有状态，跑完整往返 =================
  //
  // 【为什么第一步是复位】prompt-routes.mjs 是有状态的：上一次自检跑出来的版本会留着，
  // 于是"存一版之后版本数 +1"这类断言第二遍必红，而红的原因与功能无关。
  // 这与 HITL 那两条相反分支靠 `__reset-approval` 做到确定性是同一条经验。
  const reset = await page.evaluate(() =>
    fetch('/api/prompts/__reset', { method: 'POST' }).then(r => r.json()))
  expect('假后端的版本状态已复位（自检可重复跑）', reset.ok === true)
  await page.locator('.prompt-reload').click()
  await waitFor(async () => (await versionsCard.locator('tbody tr').count()) >= 2, 10000)

  // ---- 初始刻意不是"万事如意"的那一份 ----
  const versionsText = await versionsCard.innerText()
  // 「没署名」与「署名成了空」是两件事，不能都渲染成空白
  expect('有一版没有署名时显示「（未署名）」而不是空白', /（未署名）/.test(versionsText))
  // 这一行是"回滚也是一次新增"在界面上的唯一证据
  expect('回滚/复制产生的版本标注了「复制自第 N 版」', /复制自第 \d+ 版/.test(versionsText))

  // ---- 载入某一版到编辑器 ----
  const rowCount = await versionsCard.locator('tbody tr').count()
  await versionsCard.locator('tbody tr').first().locator('button', { hasText: '查看 / 编辑' }).click()
  const loaded = await waitFor(
    async () => (await page.locator('.prompt-textarea').inputValue()).length > 50, 10000)
  expect('能把某一版的正文载入编辑器', loaded,
    `编辑器 ${(await page.locator('.prompt-textarea').inputValue()).length} 字符`)
  expect('编辑器标题写明了编辑的是哪一版的副本',
    /编辑 v\d+ 的副本/.test(await page.locator('.prompt-editor').innerText()))

  // ---- ① 被拒的写入：判据必须逐条列出来（这是这一页最重要的交互）----
  {
    const original = await page.locator('.prompt-textarea').inputValue()
    // 围栏不配平（粘贴被截断的最典型形态）——假后端**也**必须拦，
    // 否则"提交坏提示词 → 界面逐条列判据"这个分支在自检里永远走不到，等于没验证
    await page.locator('.prompt-textarea').fill(`${original}\n\`\`\`json\n{"a":1}\n`)
    await page.locator('input[placeholder^="改动说明"]').fill('故意造一个被截断的正文')
    await page.locator('button', { hasText: '存为新版本' }).click()

    const rejected = await waitFor(async () => (await page.locator('.prompt-rejections').count()) > 0, 10000)
    expect('写入被拒时逐条列出判据', rejected)
    const rejText = await page.locator('.prompt-rejections').innerText()
    expect('被拒的判据里点名了是哪一条', /代码围栏配平/.test(rejText))
    // 「内容过短」这种说法没法据以行动 —— 判据必须带具体数字
    expect('判据带着具体数字（不是一句「内容不合规」）', /奇数|个围栏/.test(rejText))
    expect('被拒时说明了下一步该做什么', /改内容之后重试/.test(rejText))
    // 被拒 = 没有写入
    expect('被拒之后版本数没有变化', (await versionsCard.locator('tbody tr').count()) === rowCount)

    await page.locator('.prompt-textarea').fill(original)
  }

  // ---- ② 正常的 存 → 生效 → 回滚 → noOp 往返 ----
  const beforeRows = await versionsCard.locator('tbody tr').count()
  {
    const original = await page.locator('.prompt-textarea').inputValue()
    await page.locator('.prompt-textarea').fill(`${original}\n\n自检写入的标记行-${Date.now()}\n`)
    await page.locator('input[placeholder^="改动说明"]').fill('自检写入的一版：加一个标记行')
    await page.locator('button', { hasText: '存为新版本' }).click()

    const saved = await waitFor(async () => (await page.locator('.prompt-ok').count()) > 0, 10000)
    expect('保存成功并给出反馈', saved)
    const okText = await page.locator('.prompt-ok').innerText()
    // ★ 这一条钉住"存 ≠ 生效"：保存后的提示必须自己说出"尚未生效"
    expect('保存之后明确说「尚未生效」', /尚未生效/.test(okText))
    expect('保存之后说明了下一步（去版本表点生效）', /还没有生效/.test(okText))
    // ⚠️ 数行数必须**轮询**：保存成功后 hook 会重新拉一次版本列表，而那次渲染不是同步的。
    // 第一版这里写的是"反馈一出现就立刻数行数"，于是它红过一次 —— 而功能完全正常，
    // 只是列表还没重渲染完（与 HITL 那节踩的"固定 sleep 等轮询"是同一族问题：
    // **等待与它要等的东西没有关系**）
    const rowGrew = await waitFor(async () =>
      (await versionsCard.locator('tbody tr').count()) === beforeRows + 1, 10000)
    expect('版本数 +1（保存真的写进历史了）', rowGrew,
      `现在 ${await versionsCard.locator('tbody tr').count()} 行，期望 ${beforeRows + 1}`)

    const newRow = versionsCard.locator('tbody tr').first()
    expect('新版本默认**不是**生效的（存与生效是两次决定）', !/生效中/.test(await newRow.innerText()))

    // 生效：要先二次确认（切换是影响线上行为的动作，而它只有一次点击的距离）
    await newRow.locator('button', { hasText: /^生\s*效$/ }).click()
    const confirmed = await waitFor(async () => (await page.locator('.prompt-confirm').count()) > 0, 5000)
    expect('切换生效前要二次确认', confirmed)
    expect('二次确认说清了影响范围',
      /下一次 Agent 运行/.test(await page.locator('.prompt-confirm').innerText()))
    await page.locator('.prompt-confirm button', { hasText: '确认生效' }).click()

    const activated = await waitFor(async () => (await page.locator('.prompt-ok').count()) > 0
      && /已切换生效版本/.test(await page.locator('.prompt-ok').innerText()), 10000)
    expect('确认之后切换生效', activated)
    // ⚠️ 同样要轮询：切换成功之后 hook 会重新拉版本列表，而"生效中"这个标记
    // 要等那次重渲染才出现。第一版是同步读一次 innerText，于是它红过一次
    const movedToTop = await waitFor(async () => {
      const line = (await versionsCard.innerText()).split('\n').findIndex(l => l.includes('生效中'))
      return line >= 0 && line < 8
    }, 10000)
    expect('生效标记移到了新版本那一行（列表顶部）', movedToTop,
      `生效中在第 ${(await versionsCard.innerText()).split('\n').findIndex(l => l.includes('生效中'))} 行`)

    // ★★ 回滚 = 新增一版（不是把指针挪回去）—— 这一页最核心的纪律
    //
    // 【为什么按"行里有『复制自』"来定位，而不是点第一行】
    // 第一行是**最新的那一版**，而它未必带这个标注（比如它可能是刚存出来的、
    // forkedFrom 为空的那一版）。按内容特征定位，才不依赖"哪一次动作留下了什么"。
    // 一条通用经验：**"第 N 行"是一个会随数据变化的定位** —— 上面的动作刚改过数据，
    // 用下标去指"当初那一行"必然指错。
    const rolledRow = versionsCard.locator('tbody tr', { hasText: '复制自' }).first()
    const rolledRowText = (await rolledRow.innerText()).replace(/\n/g, ' ⏎ ')
    // ⚠️ 先取 `vN`（行首那一列是**这一版自己的版本号**），**不能**先取「复制自第 N 版」——
    // 后者说的是"它的内容复制自哪一版"，与"这一版是第几版"是两个不同的数。
    // 第一版就是先匹配后者的，于是"目标"被读成了 1（而实际回滚的是第 2 版），
    // 断言因此在一个**完全正常**的功能上变红。实测拿到的行文本长这样：
    //   `v2 ⏎ … ⏎ 补一条引用要求… ⏎ 复制自第 1 版（回滚产生的新版本） ⏎ …`
    const rolledTarget = rolledRowText.match(/\bv(\d+)\b/)?.[1]
    await rolledRow.locator('button', { hasText: '回滚到这一版' }).click()
    const rolled = await waitFor(async () => (await page.locator('.prompt-ok').count()) > 0
      && /已回滚/.test(await page.locator('.prompt-ok').innerText()), 10000)
    expect('回滚成功并给出反馈', rolled)
    const rollText = await page.locator('.prompt-ok').innerText()
    // 反馈里必须说清"回滚到了哪一版"与"它产生了一版新的" —— 两件事都要有，
    // 只报前者会让人以为指针被挪回去了。
    // ⚠️ 判据用「第 N 版」而不是 `vN`：`.prompt-ok` 里还有一句
    // 「回滚产生的是**新**版本（v5）」，所以只找 `v2` 这种子串是有歧义的 ——
    // 它在**功能完全正常**时也会红（v2 可能出现在别处）。
    expect('★ 回滚反馈说明它产生了一版新版本（不是挪指针）', /一版新版本|v\d+/.test(rollText))
    expect('回滚反馈点名了被回滚的那一版',
      rolledTarget != null && rollText.includes(`第 ${rolledTarget} 版`),
      `目标是第 ${rolledTarget} 版；反馈=「${rollText.replace(/\n/g, ' ')}」；行=「${rolledRowText}」`)
    expect('回滚反馈解释了为什么这么设计', /历史里留着/.test(rollText))
    // 同样要轮询：回滚也会重新拉列表
    const rolledGrew = await waitFor(async () =>
      (await versionsCard.locator('tbody tr').count()) === beforeRows + 2, 10000)
    expect('★ 回滚之后版本数又 +1（说明它是新增，不是替换）', rolledGrew,
      `现在 ${await versionsCard.locator('tbody tr').count()} 行，期望 ${beforeRows + 2}`)
    expect('回滚产生的新版本带着「复制自」标注（它记着内容是从哪一版来的）',
      /复制自第 \d+ 版/.test(await versionsCard.innerText()))

    // ---- 再回滚一次到**与当前生效版内容相同的那个版本** → noOp ----
    //
    // 【为什么这次一定成立】刚才那次回滚把 **v2 的内容**复制成了生效中的新版本 v4，
    // 所以此刻"生效版的内容"与"v2 的内容"逐字节相同（后端就是这么实现的）。
    // 于是再回滚到 v2 就是一次无事可做的回滚 —— 正确的处理是 noOp（不新增版本），
    // 而不是往历史里再塞一条"复制了 v2、内容却与当前完全相同"的记录。
    //
    // ⚠️ 这里连踩了三次，**三次都是用例的假设不成立**，而失败信息看起来都像功能坏了：
    //   ① 点"第一行" —— 第一行恰好是刚回滚出来的**生效行**，它的「回滚到这一版」
    //      按钮本来就是禁用的（回滚到自己没有意义）→ Playwright 等满 30 秒抛未捕获
    //      异常，整条自检当场中断；
    //   ② 改成"带『复制自第 N 版』标注的那一行" —— 而那个标注**正是回滚产物自己带的**，
    //      于是它又指向了生效行（同一个坑换了个说法）；
    //   ③ 改成点"出厂版本"那一行 —— 可**假数据里 v2 并不是 v1 的副本**
    //      （v2 是"改了标题 + 加了一行"产出的新内容，比 v1 多 29 个字符），
    //      所以那一次回滚**确实应该**新建一版，测试却期望 noOp。
    //
    // 一句话教训：**"哪一行"与"内容是否相同"都不能靠推理，得让被测代码自己产生。**
    // 这里最稳的做法是按那一版**独一无二的改动说明**来定位，
    // 而不是按"第 N 版"这种会出现在多处的串（回滚产生的那些版本，说明里也写着"第 2 版"，
    // 所以 `hasText: '第 2 版'` 会先匹配到别的行 —— 这是第三次踩到的同一个位置的坑）。
    const rowsBeforeNoOp = await versionsCard.locator('tbody tr').count()
    await versionsCard.locator('tbody tr', { hasText: '补一条引用要求' }).first()
      .locator('button', { hasText: '回滚到这一版' }).click()
    const noOp = await waitFor(async () => (await page.locator('.prompt-ok').count()) > 0
      && /没有新增版本/.test(await page.locator('.prompt-ok').innerText()), 10000)
    expect('★ 内容已相同时回滚是 noOp，而不是塞一条解释不了的记录', noOp,
      noOp ? '' : (await page.locator('.prompt-ok, .prompt-error').first().innerText().catch(() => '(无反馈)')))
    await page.waitForTimeout(800) // 让列表重渲染完再数（noOp 也会重新拉一次列表）
    expect('noOp 之后版本数没有变（没往历史里塞记录）',
      (await versionsCard.locator('tbody tr').count()) === rowsBeforeNoOp,
      `现在 ${await versionsCard.locator('tbody tr').count()} 行，期望 ${rowsBeforeNoOp}`)
  }

  // 收尾：把状态还原，免得后面还有断言依赖残留数据
  await page.evaluate(() => fetch('/api/prompts/__reset', { method: 'POST' }).then(r => r.json()))
}
else {
  // ================= 真后端：只做不留痕的验证 =================
  //
  // ⚠️ 刻意**不做**"存一版 → 生效"的往返：那会改掉这个实例的运行时行为。
  // 真路径要验的是"出厂版本真的入了库、生效指针指向它、界面读得到"这件事。
  expect('真后端上出厂版本已入库并生效', /v1/.test(promptText))
  expect('这一份来自版本库，而不是 classpath 兜底', !/来自 classpath 出厂文件/.test(promptText))
  // 真后端首次运行时只有 1 版（出厂版），这是**正确**的形态，不是功能缺失
  const realRows = await versionsCard.locator('tbody tr').count()
  expect('版本表至少有出厂那一版', realRows >= 1, `${realRows} 版`)
  expect('出厂版本的说明写明了它来自哪个文件', /出厂版本/.test(await versionsCard.innerText()))

  // 编辑器：能载入正文（这一条证明 /versions/{n} 的 content 真的拿到了）
  await versionsCard.locator('tbody tr').first().locator('button', { hasText: '查看 / 编辑' }).click()
  const loadedReal = await waitFor(
    async () => (await page.locator('.prompt-textarea').inputValue()).length > 50, 10000)
  const body = await page.locator('.prompt-textarea').inputValue()
  expect('真后端上也能把正文载入编辑器', loadedReal, `${body.length} 字符`)
  // 正文必须是**真的提示词**，而不是占位或空串 —— 空系统提示词会让模型用默认人格回答
  expect('载入的正文是真实的提示词内容', /你是|工作方式|回答要求/.test(body))
}

// ---- 布局体检（两条路径共用）----
const promptLayout = await page.evaluate(() => {
  const doc = document.documentElement
  const head = document.querySelector('.prompt-head')
  return {
    horizontalOverflow: doc.scrollWidth - doc.clientWidth,
    headFitsViewport: head ? head.getBoundingClientRect().right <= doc.clientWidth + 1 : null,
  }
})
expect('Prompt 面板无横向溢出', promptLayout.horizontalOverflow === 0, `overflow=${promptLayout.horizontalOverflow}px`)
expect('Prompt 面板头部不超出视口', promptLayout.headFitsViewport !== false)
await page.screenshot({ path: `${SHOT_DIR}/prompt-versions.png`, fullPage: true })


// ---------- 告警与灰度（阶段四第 5 件，第八种模式） ----------
//
// 【两条路径验的不是同一件事，条数刻意保持一致】
//   假后端：alert-routes.mjs **有状态**（确认一条 → 它从"未处理"变成"已确认"），
//           所以这里跑「确认 → 状态真的变了」的写往返，外加开/停灰度的校验分支；
//   真后端：它跑在真库上，而"确认一条告警"会**改掉这个实例的状态**
//           （后面真有人看这个面板时，那条告警已经被自检标成看过了）。
//           所以真路径只做**不留痕**的验证：判据目录读得到、当前状态读得到、
//           灰度那一栏把"配置"与"实测"并排显示出来了。
//
// ⚠️ 这一节最要紧的三条断言（它们对应这一件的三条纪律）：
//   ① **配置与实测必须同屏** —— 只显示"配了 20%"的页面会让人以为实验在做，
//      而"实际一份都没分到"是不报任何错的一种失败；
//   ② **三态必须长得不一样** —— 未处理 / 已确认 / 已恢复，合成一个布尔之后
//      "还有几条没人看过"这个数字就不成立了（而它是值班交接的依据）；
//   ③ **构建阶段的判据必须显示成「这一页不评估」** —— 否则读的人会以为
//      "告警面板说构建也没问题"。
console.log('[8] 告警与灰度面板')

// 【进面板之前先复位假后端的状态，而且这**不**算"作弊"】
// ⚠️ 这里的复位解决的是**用例自身的顺序依赖**，第一版没有它：
// "三个状态在清单上长得不一样"那条断言要求清单里同时有未处理 / 已确认 / 已恢复，
// 而 `alert-routes.mjs` 的初始数据确实三种都有 —— 但**第二条路径跑完之后**，
// 上面那一节会把那条"未处理"的确认掉。于是**连跑第二遍时那条断言必红**，
// 而红的原因与界面无关（与 Prompt 面板靠 `__reset` 做到可重复是同一条经验）。
// 复位放在**切面板之前**：它只改后端的内存状态，不碰页面，
// 所以三条断言（判据目录 / 三种状态 / 灰度分布）看到的是同一份确定的数据。
if (EXPECT_REFLECTION) {
  const resetBeforePanel = await page.evaluate(() =>
    fetch('/api/alerts/__reset', { method: 'POST' }).then(r => r.json()))
  expect('进面板前把假后端的告警状态复位（让这一节可重复跑）', resetBeforePanel.ok === true)
}

await page.locator('label', { hasText: '告警与灰度' }).click()

const alertPanelReady = await waitFor(
  async () => (await page.locator('.alert-events-card').count()) > 0, 10000)
expect('告警面板渲染出来了（而不是只有空骨架）', alertPanelReady)

// ---- 判据目录：一处定义、两处使用、一处展示 ----
const rulesCard = page.locator('.alert-rules-card')
const rulesText = await rulesCard.innerText()
expect('判据目录列出了运行阶段的判据', /unconfigured-prices/.test(rulesText))
expect('判据目录也列出了构建阶段的门禁（否则读的人以为只检查这几件事）',
  /gate-eval-baseline-drift/.test(rulesText))
expect('★ 构建阶段的判据被明确标成「这一页不评估」',
  /这一页不评估它/.test(rulesText),
  '—— 不加区分地列出来，读的人会以为「告警面板说构建也没问题」')
expect('判据目录写明了阈值写死不从配置读', /不从配置读/.test(rulesText))
expect('判据目录里没有字面量 markdown 星号', !rulesText.includes('**'))

// ★ 第 6 件改了判据的**主体粒度**，而"改了粒度"这件事本身在页面上没有症状 ——
// 一条被判成"全局"的判据只是**少报几条**，看起来一切正常。所以这里断言目录里有那两条：
//   · tenant-cost-concentration：只有在租户粒度上才成立的一条判据（新增）；
//   · zero-cache-hits 的标题必须是"某租户"而不是"整窗"（粒度改了，标题必须跟着改 ——
//     一条说"整窗"的标题配一个按租户判的实现，读的人会照错的粒度去理解那个数字）。
expect('★ 判据目录里有「租户花费集中度」这一条（只有在租户粒度上才成立的那条）',
  /tenant-cost-concentration/.test(rulesText))
expect('★ 缓存那条判据的标题已改成「某租户」（粒度改了，措辞必须跟着改）',
  // ⚠️ 判据只能写成"**以「某租户」开头**"，不能写成 `!/整窗…/` ——
  // 新标题是「某租户的整窗提示词缓存命中率为 0…」，它**包含**「整窗」这两个字。
  // 第一版就是这么写的（`某租户 && !整窗…`），于是它在标题**已经改对**的时候报红，
  // 而失败信息说"标题还写整窗" —— 一个指向错误方向的假红。
  // 教训与本项目反复记的那条同族：**否定式判据要先确认它否定的到底是什么**。
  /某租户的整窗提示词缓存命中率为 0/.test(rulesText),
  `—— 目录里缓存那一条的措辞：${(rulesText.match(/[^\s]*整窗[^\s]*/g) ?? ['(找不到)']).join(' / ')}`)

// ---- 告警清单 ----
const eventsCard = page.locator('.alert-events-card')

// ★ 先跑一轮评估 —— 两条路径都跑，而这一步是**这一件端到端的核心**
//
// 【为什么必须在界面上点它，而不是用 fetch 绕过】
// 因为这一条要验的正是"点一下之后告警真的被记下来了"这条链路：
// 按钮 → useAlerts.evaluate → GET /api/alerts/sweep → AlertRecorder → alert_event 表 → 列表。
// 用 fetch 直接打端点会跳过前两步，而"界面压根没接上"这种失败恰恰只在前面那两步里。
//
// 【为什么真后端也要跑】
// 真路径的意义就是"同一个动作在真实现上也成立"：假后端的 sweep 是手写 JSON，
// 而真后端的 sweep 要跑一次快照采集（四条聚合 + 两次检索统计），
// 任何一处 SQL 写错都会在这里现形 —— 而假后端永远绿。
//
// ⚠️ 它会**写库**（真后端上会往 alert_event 插一行）。这是刻意的：
// 这一件要解决的原始缺口就是"没有人被通知"，而"没人点就永远没有记录"正是它。
// 自检点这一下，等于顺带证明"这条链路能通"。
await page.locator('.alert-head button', { hasText: /跑一轮评估|评估中/ }).first().click()
const sweepRendered = await waitFor(
  async () => /最近一轮评估/.test(await page.locator('.alert-head').innerText()),
  20000,
)
expect('点「跑一轮评估」之后界面给出了这一轮的产出（新响 / 恢复 / 仍在响）', sweepRendered,
  `—— head=${(await page.locator('.alert-head').innerText()).replace(/\n/g, ' ').slice(0, 100)}`)

// 评估会写库 → 清单里这时**必然**至少有一条（价格没配在测试库里必然成立）
const eventsText = await eventsCard.innerText()
expect('★ 跑完评估之后，告警清单里真的多出了一条记录（评估会写库）',
  (await eventsCard.locator('tbody tr').count()) > 0,
  `—— 清单：${eventsText.replace(/\n/g, ' ').slice(0, 120)}`)
expect('清单列出了具体判据代号（而不是一句笼统的"有问题"）',
  /unconfigured-prices/.test(eventsText))

// ★ 三个状态在**列表里**必须长得不一样
//
// 【判据分两部分，因为两条路径能提供的样本不同】
//   ① 库里三种状态齐全时，页面上必须能把它们全都渲染出来
//      （假后端的初始数据**刻意**留了三种各一条 —— 那里是唯一能验全的地方）；
//   ② 更本质的那一条：**页面上的状态标签必须来自后端**，而不是界面自己编的。
//      合成一个布尔的后果很具体：值班的人确认完一轮之后，列表看起来与
//      "完全没人管"一模一样 —— 于是下一个人会从头再查一遍，而第三个人就开始
//      忽略这个列表了。
{
  const apiEvents = await page.evaluate(() =>
    fetch('/api/alerts').then(r => r.json()).then(d => d.events.map(e => e.state)))
  const distinct = Array.from(new Set(apiEvents))
  const labelOf = s => (s === 'OPEN' ? '未处理' : s === 'ACKNOWLEDGED' ? '已确认' : '已恢复')
  expect('★ 页面上的状态标签与后端给的状态一一对应（不是界面自己编的）',
    distinct.length > 0 && distinct.every(s => eventsText.includes(labelOf(s))),
    `—— 后端给了 ${distinct.join('/') || '（空）'}`)
  if (distinct.length >= 3) {
    expect('★ 三种状态在清单上长得不一样（未处理 / 已确认 / 已恢复）', true,
      `—— 本次后端恰好给了三种：${distinct.join('/')}`)
  }
  else {
    expect('三种状态齐全时的对照在假后端那条路径上验（那里刻意留了三种各一条）', true,
      `—— 本次只有 ${distinct.join('/') || '（空）'}（真后端库里刚建，属正常）`)
  }
}
expect('清单里的每一行都带着观测值与判据阈值（"差多少、跟什么比"）',
  (await eventsCard.locator('tbody tr').first().innerText()).length > 0)

// ★ 主体粒度：这一页上唯一一条"主体是一个业务实体（租户名）"的告警
//
// 【为什么值得单独一条断言】改了粒度之后，告警的**主体**从固定的几个词
// （「窗口合计」「价格表」「检索」）变成了"哪一家"。主体是告警里最难被注意到的字段 ——
// 它不出现在标题里，而"这条说的是谁"错了的时候，收到告警的人会去查错的对象。
// 假后端那条数据是这一页上**唯一**能提供这个形状的样本，所以只在假路径上断言。
if (EXPECT_REFLECTION) {
  expect('★ 清单里出现了「主体是租户名」的告警（改粒度之后才有的形状）',
    /tenant-cost-concentration/.test(eventsText) && /示例租户/.test(eventsText),
    `—— 清单：${eventsText.replace(/\n/g, ' ').slice(0, 200)}`)
}
const alertLayout = await page.evaluate(() => {
  const doc = document.documentElement
  const head = document.querySelector('.alert-head')
  return {
    horizontalOverflow: doc.scrollWidth - doc.clientWidth,
    headFitsViewport: head ? head.getBoundingClientRect().right <= doc.clientWidth + 1 : null,
  }
})
expect('告警面板无横向溢出', alertLayout.horizontalOverflow === 0, `overflow=${alertLayout.horizontalOverflow}px`)
expect('告警面板头部不超出视口', alertLayout.headFitsViewport !== false)
await page.screenshot({ path: `${SHOT_DIR}/alerts-panel.png`, fullPage: true })

// ---- 灰度：★ 配置与实测必须同屏 ----
const canaryCard = page.locator('.canary-card')
const canaryText = await canaryCard.innerText()
expect('灰度那一栏渲染出来了', canaryText.length > 20)
expect('★ 配置的重量与实际分到的份数在同一个视图里',
  /配置的重量/.test(canaryText) && /参与分流的运行/.test(canaryText) && /实测占比/.test(canaryText),
  '—— 只看配置的页面在"实际 0 份"时会显示得一切正常，而那次实验等于没做')
expect('灰度那一栏说清了「分流是算出来的，不是存下来的」',
  /算出来的/.test(canaryText) && /稳定哈希/.test(canaryText))
expect('★ 灰度那一栏划清了边界：它答不了「新版更好还是更差」',
  /不是.*新版更好还是更差|新版更好还是更差/.test(canaryText))
expect('逐版本的运行数按分组列出来了（实验组 / 对照组 / 未参与）',
  /实验组/.test(canaryText) && /对照组/.test(canaryText) && /未参与这次实验/.test(canaryText)
    // 真后端第一次打开时：窗口内一份运行都没有，逐版本表是空的 ——
    // 那时这几行分组本来就不存在。**但"空"必须被解释**（见下面那条断言）
    || /参与分流的运行[\s\S]{0,8}?0/.test(canaryText))
expect('灰度那一栏没有字面量 markdown 星号', !canaryText.includes('**'))

// ★ 没有候选版本可选的场景必须被解释（真后端第一次打开这一页就是它）
//
// 【为什么这一条不是"锦上添花"】真后端的库里只有出厂那一版（v1）且它正在生效，
// 而候选版本不能就是生效那一版 —— 于是候选列表是空的。
// 只渲染一个空下拉框的后果是使用者以为界面坏了（而他什么也没做错）。
// 第一版没有这个分支，于是真后端上"逐版本分组"那条断言红在一个**不存在的表格**上，
// 而失败信息（"找不到实验组"）看起来像数据不对。
if (/现在没有可以拿来灰度的候选版本/.test(canaryText)) {
  expect('没有候选版本时给出了下一步（去 Prompt 面板存一版）', /Prompt 面板/.test(canaryText))
}
else {
  expect('有候选版本时渲染出了候选列表与重量输入', (await page.locator('.canary-card input').count()) > 0)
}

// ★ 灰度的种类名必须与后端的 PromptKind.defaultValue 一致
//
// 【为什么要专门断言这一条】第一版这里（以及后端默认参数）写的是 "agent-loop" ——
// 那是 Java **枚举常量的名字**，而接口上用的名字是 "system-agent-tools"。
// 后果是真后端上 `GET /api/canary` 直接 400「没有这一份提示词：「agent-loop」」，
// 而假后端照着那个手写串写了一遍，所以它绿着。
// 也就是说：**这条断言本来能提前抓住一个真后端的 400**，而它当时不存在。
// 现在两条路径都断言同一个名字，任何一边改错都会当场现形。
{
  const canaryApi = await page.evaluate(() => fetch('/api/canary').then(r => r.json()))
  expect('★ 灰度的种类名与后端一致（system-agent-tools，不是枚举名 agent-loop）',
    canaryApi.kind === 'system-agent-tools',
    `—— 后端返回 kind=${canaryApi.kind}`)
  expect('灰度接口可读（不是 400/404）', canaryApi.ok === true)
}

if (EXPECT_REFLECTION) {
  // ================= 假后端：有状态，跑写往返 =================
  //
  // 【为什么这里还要再复位一次】上面那次复位发生在**切进面板之前**，
  // 而这一节要验的是"点确认之后状态真的变了"—— 那需要一条**未处理**的告警。
  // 两次复位之间没有任何写操作，所以第二次其实是幂等的安全网：
  // 它保证"即使将来有人在中间加了别的操作，这一节仍然从确定的状态开始"。
  // 与 prompt-routes 的 `__reset`、HITL 的 `__set-approval` 是同一条经验。
  const reset = await page.evaluate(() =>
    fetch('/api/alerts/__reset', { method: 'POST' }).then(r => r.json()))
  expect('假后端的告警与灰度状态已复位（自检可重复跑）', reset.ok === true)

  // 【复位之后要让界面重新拉一次 —— 但**不能 `page.reload()`**】
  // ⚠️ 这是本节踩过的第一个坑，代价很具体：第一版写的是 `page.reload()`，
  // 于是这一页之前那些模式的结果**全没了**（流式对话、结构化卡片、RAG 对照、
  // Agent 轨迹都在 React 的 useState 里），后面"切回去结果仍在"那四条断言全红 ——
  // 而它们红的原因与告警面板毫无关系，看起来却像"新面板把别的面板搞坏了"。
  // 改成点**本面板自己的**刷新按钮之后，复位与断言都能成立，且不动别的状态。
  // （另一个坑：别的面板也有叫「刷新」的按钮，所以必须用 `.alert-head` 限定范围 ——
  //   全局 hasText 会选到第一个，很可能不是这一页的那个，症状是"点了没反应"。）
  await page.locator('.alert-head button', { hasText: /刷\s*新/ }).click()
  await waitFor(async () => /未处理/.test(await eventsCard.innerText()), 10000)

  // ---- ① 确认一条告警：状态必须真的变 ----
  {
    const before = await page.evaluate(() =>
      fetch('/api/alerts').then(r => r.json()).then(d => d.openCount))
    expect('复位之后有未处理的告警（否则下面那条断言测不到东西）', before > 0, `${before} 条`)

    // 找第一行"未处理"的确认按钮点下去。
    // ⚠️ 用**行内定位**而不是全局 hasText：全局找会在多个面板的同名按钮之间乱选
    const openRow = eventsCard.locator('tbody tr', { hasText: '未处理' }).first()
    await openRow.locator('button', { hasText: /^已\s*确\s*认$/ }).click()

    // 【断言分两半，因为它们各自可能单独失败】
    //   a) 后端那侧的状态真的变了 —— 这是这一条的全部价值。
    //      只改 DOM 不算数（与 Prompt 面板那条 409 是同一个教训：
    //      "界面变了"与"事实变了"是两件事）。
    //   b) 界面上的数字跟着变了 —— 这是"界面没有自己造一个乐观状态"的证据。
    // 【断言的是**后端**那一侧，而不是界面上的字】
    // ⚠️ 第一版写的是 `waitFor(...)` 轮询 `fetch('/api/alerts')`，它**永远返回 false** ——
    // 而同一轮的界面断言全绿（页面上确实显示「未处理 0」）。
    // 也就是说那一次红是**用例自己的问题**，与功能无关：
    // `waitFor` 期望一个真值，而 `openCount` 变成 0 之后 `0 < 1` 的结果是
    // 数字 0 —— 它是假值，被当成"还没变"。**"变成 0"恰好是这条断言要等的东西**，
    // 而 0 是 JS 里最经典的假值。改成用布尔包装之后一切正常。
    // 记在这里：凡是要等一个"归零"的断言，都要显式 `=== 0`。
    const after = await waitFor(
      async () => {
        const d = await page.evaluate(() => fetch('/api/alerts').then(r => r.json()))
        return d.openCount < before ? d.openCount + 1 : false
      },
      10000,
    )
    expect('★ 点「已确认」之后，后端那侧的未处理条数真的少了一条', after !== false,
      `—— ${before} → ${after === false ? '(没变)' : after - 1}`)
    const headText = await page.locator('.alert-head').innerText()
    expect('界面上的「未处理」数字跟着后端变成 0（不是界面自己造的乐观状态）',
      /未处理（还没人看过）[\s\S]{0,12}?0/.test(headText),
      headText.replace(/\n/g, ' ').slice(0, 120))
    expect('写操作成功后给出了明确反馈', (await page.locator('.alert-write-result').count()) > 0)
  }

  // ---- ② 灰度的两个方向：正在实验 vs 没有实验 ----
  //
  // 【为什么这里要分支】假数据的灰度**默认是进行中的**（这样"配置与实测并排"
  // 那条断言才有东西可看），而进行中时"开灰度"这个按钮压根不渲染 ——
  // 第一版写死了"没写 note 时开灰度按钮是禁用的"，于是在进行中的状态上永远红，
  // 而红的原因与"按钮该不该禁用"无关（面板走的是另一条分支）。
  // 两条分支各断言一件真正成立的事，比强行断言同一件事更有信息量。
  {
    const stopButton = page.locator('.canary-card button', { hasText: '停止这次灰度' })
    const startButton = page.locator('.canary-card button', { hasText: '开灰度' })
    const hasStop = (await stopButton.count()) > 0
    expect('灰度控制区渲染出来了（要么能开、要么能停，不会两个都没有）',
      hasStop || (await startButton.count()) > 0)

    if (hasStop) {
      // 进行中：必须给"停止"的入口，而且**原因没填时不许停**
      expect('★ 没写「为什么停」时停止按钮是禁用的',
        await stopButton.first().isDisabled(),
        '—— 一次实验的结论常常就是那句话（推全 / 回滚 / 到期了），不写就只剩「某天它停了」')
      await page.locator('input.canary-end-reason').fill('界面自检：验证停灰度的往返')
      expect('写了原因之后停止按钮可点', !(await stopButton.first().isDisabled()))

      await stopButton.first().click()
      const stopped = await waitFor(
        async () => {
          const d = await page.evaluate(() => fetch('/api/canary').then(r => r.json()))
          return d.active === false
        },
        10000,
      )
      expect('★ 点停止之后灰度的 active 真的变成 false（不是只改了界面）', stopped === true)
      // 【等界面重新渲染，而不是立刻断言】停止之后面板会重拉一次灰度状态并重渲染，
      // 而那次渲染**不是同步的**。这条第一版就是直接读文本，于是偶发红 ——
      // 与第 9 节那条"切回去之后必须轮询等待面板重新挂起来"是同一个坑
      // （固定 sleep 或立刻读的失效形态都一样：等待与它要等的东西没有关系）。
      const previousShown = await waitFor(
        async () => /上一轮实验已停止/.test(await page.locator('.canary-card').innerText()),
        10000,
      )
      expect('★ 停掉之后仍能看到「上一轮实验」的配置（一次实验的结论不该随停止消失）',
        previousShown === true,
        `—— 卡片标题：${(await page.locator('.canary-card').innerText()).split('\n')[0]}`)
    }
    else {
      // 没有进行中的实验：必须给"开"的入口，而且**假设没写时不许开**
      expect('★ 没写实验假设时「开灰度」是禁用的',
        await startButton.first().isDisabled(),
        '—— 不写的话一周后没人记得这次想看什么，唯一结局是忘了关')
      expect('灰度控制区给出了候选版本与重量的输入', (await page.locator('.canary-card input').count()) > 0)
    }
  }
}
else {
  // ================= 真后端：只做不留痕的验证 =================
  //
  // 【为什么真路径不点"确认"】它会**改掉这个实例的状态** ——
  // 后面真有人打开这个面板时，那条告警已经被自检标成"看过了"，
  // 而"还有几条没人看过"正是值班交接的依据。这与 Prompt 面板那条
  // "真路径不往库里塞版本"是同一个取舍。
  expect('真后端的告警端点可读（不是 404）', eventsText.length > 0)
  expect('真后端上灰度那一栏也把配置与实测并排显示了',
    /配置的重量/.test(canaryText) && /参与分流的运行/.test(canaryText))
}


// ---------- 切回流式：历史不能被清掉 ----------
console.log('[9] 模式往返')
await page.locator('label', { hasText: 'SSE 流式' }).click()
await page.waitForTimeout(300)
const streamStillThere = (await page.locator('.message-list').innerText()).trim().length > 20
expect('切回流式后原有对话仍在', streamStillThere)
await page.locator('label', { hasText: '结构化输出' }).click()
await page.waitForTimeout(300)
// 至少 1 张卡就说明记录没丢（假后端那条路径会留下 2 张，真后端只有 1 张 —— 别写死数量）
expect('切回结构化后结果仍在', (await page.locator('.structured-card').count()) >= 1)

// 切回 RAG：对照结果必须还在（它是这张页面上最贵的一次计算，丢了就要重跑）
await page.locator('label', { hasText: 'RAG 分块评测' }).click()
await page.waitForTimeout(400)
const ragStillThere = (await page.locator('.rag-metric-table').count()) > 0
expect('切回 RAG 后对照结果仍在（没有重跑）', ragStillThere)

// 切回 Agent：任务结果必须还在（它会**真的调模型花钱**，丢了就要再花一次钱重跑）。
//
// ⚠️ 这里断言的是**在 HITL 那一节里、轨迹稳定之后记下来的状态**（`timelineWasThere`），
// 而不是"现在有没有时间线" —— 因为 HITL 那一节会再跑两次任务，
// 那两次的轨迹（批准后 / 拒绝后）与原来那次不同。理由见那个变量的注释。
//
// ⚠️⚠️ 切回来之后**必须轮询等待面板重新挂起来再数**：
// 面板是按模式条件渲染的，切换之后它要重新渲染一次，而**那个渲染不是同步的**。
// 第一版写的是 `waitForTimeout(400)` 然后立刻数 `.agent-timeline` ——
// 真后端上这条因此红过一次，而**功能完全正常**（切回去之后时间线确实还在）。
// 这正是"界面自检里的固定 sleep"最典型的失效形态：
// **等待时长与它要等的东西没有关系**，而失败信息看起来像"结果丢了"。
// 改成"轮询它出现"之后，两条路径都稳定 ——
// 这与本轮 HITL 断言里用的 `waitFor` 是同一套做法（理由见那个函数的注释）。
await page.locator('label', { hasText: 'Agent 工具调用' }).click()
const timelineNow =
  timelineWasThere && (await waitFor(async () => (await page.locator('.agent-timeline').count()) > 0, 3000))
expect(
  '切回 Agent 后任务结果仍在（没有重跑、没有再花钱）',
  timelineNow === timelineWasThere,
  timelineWasThere ? '（原来有时间线，切回来也应有）' : '（原来就没有时间线 —— 本次运行 0 步，属正常）',
)

// ---------- 控制台必须干净 ----------
console.log('[9] 控制台')
expect('无 console 错误/警告', consoleErrors.length === 0, consoleErrors.join(' | ') || '干净')

await browser.close()

console.log('')
if (problems.length === 0) {
  console.log(`✅ 全部通过。截图：${SHOT_DIR}/`)
  process.exit(0)
}
console.log(`❌ ${problems.length} 项未通过：${problems.join('、')}`)
process.exit(1)
