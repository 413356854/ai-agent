/**
 * 一次性探针：把 agent任务卡片上"被夹住的那段文字"的尺寸与状态打出来。
 *
 * <h2>为什么需要它</h2>
 * 卡片上那段文字的几何**断言只能说出数字，说不出数字是哪来的**：
 * 实测踩到过一次"内容 162px / 露出 162px"，看起来像 `max-height` 没生效，
 * 真相是 `useEffect` 抢在样式重算之前读到了展开态那一帧（见 `Clamped.tsx` 的注释）。
 * 这个探针把行内样式、计算样式、行盒与祖先链一起打出来，用来分辨这两者 ——
 * 「样式没生效」与「读早了」在断言里长得一模一样。
 *
 * 用法：`pnpm mock` + `pnpm dev:mock` 起着，然后 `node tools/probe-clamp.mjs`。
 * 它只读、只点那一个开关与一处卡片空白，不改任何数据（不必 `__reset`）。
 */
import { chromium } from 'playwright'

const URL = process.env.UI_URL ?? 'http://127.0.0.1:5173'
/** 夹住/展开有一道 max-height 过渡（0.15s）：不等它走完读到的就是动画中间的高度。 */
const SETTLE_MS = 600

const browser = await chromium.launch({ channel: 'chrome' })
const page = await browser.newPage({ viewport: { width: 1100, height: 900 } })
await page.goto(URL, { waitUntil: 'domcontentloaded' })

// 切到 agent任务 那一页（目录项就是按钮，按文字点）
await page.locator('.app-nav-item', { hasText: 'agent任务' }).first().click()
// mock/task-routes.mjs 的 id 10 是那张刻意写长描述的卡片，也是唯一会夹住的一张
const card = page.locator('.task-card[data-task-id="10"]')
await card.waitFor({ state: 'visible', timeout: 15000 })
const toggle = card.locator('.task-card-toggle')
await toggle.waitFor({ state: 'visible', timeout: 8000 })
await page.waitForTimeout(SETTLE_MS)

const measure = () => card.locator('.task-card-title').evaluate(el => {
  const cs = getComputedStyle(el)
  return {
    inlineMaxHeight: el.style.maxHeight,
    computedMaxHeight: cs.maxHeight,
    overflow: cs.overflow,
    clientHeight: el.clientHeight,
    scrollHeight: el.scrollHeight,
    classes: el.className,
    // 行盒数 × 每个行盒的高度：被夹住时"看得见几行"最直接的证据
    lineBoxes: [...el.getClientRects()].map(r => Math.round(r.height * 100) / 100),
  }
})

const report = label => async () => console.log(`--- ${label} ---\n${JSON.stringify(await measure(), null, 2)}`
  + `\n开关：${await toggle.count() ? await toggle.innerText() : '（没有开关）'}`)

await (report('首屏（应已收起）'))()
await toggle.click()
await page.waitForTimeout(SETTLE_MS)
await (report('点「展开全文」之后'))()
await toggle.click()
await page.waitForTimeout(SETTLE_MS)
await (report('再点「收起」之后（应回到首屏那个高度）'))()

// 卡片整张仍是"点开详情"的热区：透明那一层盖着，而开关浮在它上面。
// ⚠️ `force` 是给**这个探针**用的：那一层拦指针事件是刻意的（见 styles.css），
// Playwright 默认的"可点性"检查会因此判它点不到 —— 真人点的就是那一片。
await card.locator('.task-card-meta').first().click({ force: true })
await page.waitForTimeout(SETTLE_MS)
console.log('点卡片空白处之后，开着的弹窗数：', await page.locator('.ant-modal:visible').count())

await browser.close()
