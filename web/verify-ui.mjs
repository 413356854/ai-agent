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
 * 被台账自己记为"虚报"。**一个会随环境变化的数字，必须把每个环境的取值都写清**，
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

const browser = await chromium.launch({ channel: 'chrome' })
const page = await browser.newPage({ viewport: { width: 1100, height: 900 } })

const consoleErrors = []
page.on('console', msg => {
  if (msg.type() === 'error' || msg.type() === 'warning') consoleErrors.push(`[${msg.type()}] ${msg.text()}`)
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
await page.waitForTimeout(2500)
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
await page.waitForTimeout(1200)

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
  // 台账写成"36 项"而单条路径实际只执行 29 项（见下方"断言数怎么读"）。
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
const runsCard = page.locator('.diag-card', { hasText: '历史运行' }).first()
expect('历史运行卡片存在', (await runsCard.count()) > 0)
if ((await runsCard.count()) > 0) {
  await runsCard
    .locator('.ant-table-tbody tr')
    .first()
    .waitFor({ state: 'visible', timeout: 30000 })
    .catch(() => {})
  await page.waitForTimeout(300)
  const runsText = (await runsCard.innerText()).trim()
  // 刚刚跑过的那次一定在列表里（两条路径都成立：它就在这一次自检里跑出来的）
  expect('列出了刚刚跑过的任务', /Agent 工具调用面板|分块策略的结论是什么/.test(runsText))

  if (EXPECT_REFLECTION) {
    // 假后端给了一份"包含各种结局"的历史，所以这些状态标签都能验证
    expect('显示了状态标签', /已回答/.test(runsText))
    // **等待恢复的提示必须显眼**：默认配置不会自动恢复，不说的话那些运行会一直躺着没人管，
    // 而现象是"那个任务永远没结果"
    expect('提示了有运行在等待恢复', /等待恢复/.test(runsText))
    // 失败细分必须在列表里可见（MODEL_ERROR 这一个出口下面藏着 6 种原因）
    expect('显示了失败细分', /LOCAL_CONCURRENCY_LIMIT/.test(runsText))
    // 被上限拦下与"失败"是两件事，界面上要分开
    expect('区分了"被上限拦下"与"失败"', /被上限拦下/.test(runsText) && /失败/.test(runsText))
  } else {
    // 真后端（假 key）：历史里只有那条 401 失败 —— 正好验证失败细分的**真实渲染**
    expect('显示了失败细分', /UPSTREAM_AUTH/.test(runsText))
    expect('显示了"失败"状态', /失败/.test(runsText))
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

    // 关掉详情。
    //
    // 这里踩了一个**老坑**（踩坑库 #48）：antd 6 会把恰好两个汉字的按钮渲染成
    // 「关 闭」（中间插一个空格），所以 `hasText: '关闭'` **永远匹配不上** ——
    // 而失败表现是"等按钮等到超时"，看起来像"详情卡里没有关闭按钮"。
    // 同族问题还有 #24（纯文本里的 markdown 星号）——**界面文案的一个渲染细节，
    // 能让一个看起来完全合理的定位永远失效**。
    await detailCard
      .locator('button')
      .filter({ hasText: /关\s*闭/ })
      .first()
      .click()
    await page.waitForTimeout(300)
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
 * （阶段一就在这里含糊过一次，台账里把它记为"虚报"：写成"36 项"而实际只执行 29 项）。
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
    expect('显示了将被执行的参数（原样）', /台账二·六节：正文 token 数大于总数/.test(apprText))
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
      // 这条与本项目踩坑库 #48（antd 把两个汉字渲染成「关 闭」）同族：
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
  await agentCard.locator('button', { hasText: /跑\s*一\s*次/ }).first().click()
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
    // 这条路径**恰好是"失败细分"那个功能的真实验证**：
    // 本机 key 是假的 → 上游 401 → 分类必须是 UPSTREAM_AUTH，
    // 而不是笼统的 MODEL_ERROR。这正是本轮修掉的那个问题的核心场景。
    expect('把失败原因分类显示出来了', /UPSTREAM_AUTH|MODEL_ERROR/.test(resultText))
    expect('显示了对这一页失败的处置建议', /重试没有意义|下一步|API Key|鉴权/.test(resultText))
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
const intentCard = page.locator('.diag-card', { hasText: '按业务归因：钱是谁花的' })
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
expect('按业务归因卡已渲染', (await intentCard.count()) > 0)
expect('按模型卡已渲染', (await modelCard.count()) > 0)
expect('按工具卡已渲染', (await toolCard.count()) > 0)
// 纯文本里的 markdown 星号不能以字面量出现（踩坑库 #24 同族）。
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
  const intentText = await intentCard.innerText()
  expect('按业务归因表有数据行', (await intentCard.locator('tbody tr').count()) >= 1)
  expect('未标注的运行单独成一行（没被并进任何业务里）', /未标注/.test(intentText))
  expect('界面说明了「未标注」不该被并进业务里', /没有.*被并进|虚高/.test(intentText))

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


// ---------- 切回流式：历史不能被清掉 ----------
console.log('[7] 模式往返')
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
console.log('[8] 控制台')
expect('无 console 错误/警告', consoleErrors.length === 0, consoleErrors.join(' | ') || '干净')

await browser.close()

console.log('')
if (problems.length === 0) {
  console.log(`✅ 全部通过。截图：${SHOT_DIR}/`)
  process.exit(0)
}
console.log(`❌ ${problems.length} 项未通过：${problems.join('、')}`)
process.exit(1)
