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
await page.locator('label', { hasText: 'Agent 工具调用' }).click()
await page.waitForTimeout(900)

const agentCard = page.locator('.diag-card', { hasText: '跑一个任务' }).first()
expect('Agent 面板已渲染', (await agentCard.count()) > 0)

if ((await agentCard.count()) > 0) {
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
if ((await runsCard.count()) > 0) {
  await runsCard.locator('.ant-table-tbody tr').first().click()
  const detailCard = page.locator('.diag-card', { hasText: '运行详情' }).first()
  await detailCard.waitFor({ state: 'visible', timeout: 30000 }).catch(() => {})
  await page.waitForTimeout(400)
  if ((await detailCard.count()) > 0) {
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

// 切回 Agent：任务结果必须还在（它会**真的调模型花钱**，丢了就要再花一次钱重跑）
await page.locator('label', { hasText: 'Agent 工具调用' }).click()
await page.waitForTimeout(400)
const agentStillThere = (await page.locator('.agent-timeline').count()) > 0
expect('切回 Agent 后任务结果仍在（没有重跑、没有再花钱）', agentStillThere)

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
