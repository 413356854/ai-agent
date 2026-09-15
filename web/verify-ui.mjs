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
 * 脚本里一共 72 个 `expect(...)`，但两条路径执行的数量不同，因为有两个分支
 * 各跑各的断言（回灌细节 / 降级标记只在假后端可复现）：
 *
 *   假后端（默认）      → **68 项全过**
 *   真后端（=0）        → **66 项**，其中流式那条会失败（假 key 导致 401）
 *
 * 这里刻意把两个数字都写下来：阶段一曾经只写"36 项"而单条路径实际只跑 29 项，
 * 被台账自己记为"虚报"。**一个会随环境变化的数字，必须把每个环境的取值都写清**，
 * 否则读的人没法判断某次输出是不是正常。
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
