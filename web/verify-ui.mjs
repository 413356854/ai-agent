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
  // 真后端：模型一次就通过，没有回灌细节 —— 改为断言"答案确实渲染了"
  const answer = (await page.locator('.structured-card .markdown').first().innerText()).trim()
  expect('结构化答案已渲染', answer.length > 5, `${answer.length} 字符`)
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

// ---------- 切回流式：历史不能被清掉 ----------
console.log('[5] 模式往返')
await page.locator('label', { hasText: 'SSE 流式' }).click()
await page.waitForTimeout(300)
const streamStillThere = (await page.locator('.message-list').innerText()).trim().length > 20
expect('切回流式后原有对话仍在', streamStillThere)
await page.locator('label', { hasText: '结构化输出' }).click()
await page.waitForTimeout(300)
// 至少 1 张卡就说明记录没丢（假后端那条路径会留下 2 张，真后端只有 1 张 —— 别写死数量）
expect('切回结构化后结果仍在', (await page.locator('.structured-card').count()) >= 1)

// ---------- 控制台必须干净 ----------
console.log('[6] 控制台')
expect('无 console 错误/警告', consoleErrors.length === 0, consoleErrors.join(' | ') || '干净')

await browser.close()

console.log('')
if (problems.length === 0) {
  console.log(`✅ 全部通过。截图：${SHOT_DIR}/`)
  process.exit(0)
}
console.log(`❌ ${problems.length} 项未通过：${problems.join('、')}`)
process.exit(1)
