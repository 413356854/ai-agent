/**
 * 一次性探针：把左边栏"展开 / 收起"两态的几何与页头那一行打出来，并各存一张截图。
 *
 * <h2>为什么需要它</h2>
 * "收起"这件事现在是**几何**（宽度归 0），而断言只报得出"宽度不是 0"，
 * 说不出是"没收起"还是"收起了但内容区没接上"、"页头那一行被挤换行了"。
 * 这个探针把目录宽度、内容区左边界、页头高度、当前视图标签一起打出来 ——
 * 布局上的毛病基本都落在其中一项上，而截图只是顺手给人看一眼。
 *
 * 用法：`pnpm mock` + `pnpm dev:mock` 起着，然后 `node tools/probe-sidebar.mjs`
 * （打真后端也可以，这个探针不发写请求，面数据它不关心）。
 * 截图落在 `ui-check-out/`（已 gitignore）。它只点那一个开关，结尾恢复原态。
 */
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'

const URL = process.env.UI_URL ?? 'http://127.0.0.1:5173'
const SHOT_DIR = 'ui-check-out'
/** 宽度是 0.2s 过渡出来的：不等它走完读到的就是动画中间的宽度 */
const SETTLE_MS = 600
/** 与 `useSidebarCollapse` 里那个断点同值：窗口比它窄时首帧默认收起 */
const EXPANDED_WIDTH = 150

mkdirSync(SHOT_DIR, { recursive: true })

const browser = await chromium.launch({ channel: 'chrome' })
const page = await browser.newPage({ viewport: { width: 1100, height: 900 } })
await page.goto(URL, { waitUntil: 'networkidle' })

const measure = () => page.evaluate(() => {
  const round = n => Math.round(n)
  const rect = sel => {
    const el = document.querySelector(sel)
    return el ? el.getBoundingClientRect() : null
  }
  const sidebar = rect('.app-sidebar')
  const main = rect('.app-main')
  const topbar = rect('.app-topbar')
  const toggle = rect('.app-nav-toggle')
  const current = document.querySelector('.app-topbar [data-current-mode]')
  return {
    sidebarWidth: sidebar ? round(sidebar.width) : null,
    sidebarVisible: sidebar ? getComputedStyle(document.querySelector('.app-sidebar')).visibility : null,
    inert: document.querySelector('.app-sidebar')?.inert === true,
    // 内容区左边界应等于目录右边界：差一位就说明目录还占着位置（或被内容盖住）
    mainLeft: main ? round(main.left) : null,
    sidebarRight: sidebar ? round(sidebar.right) : null,
    topbarHeight: topbar ? round(topbar.height) : null,
    toggle: toggle
      ? { width: round(toggle.width), x: round(toggle.left), inTopbar: Boolean(document.querySelector('.app-topbar .app-nav-toggle')) }
      : null,
    toggleLabel: document.querySelector('.app-nav-toggle')?.getAttribute('aria-label') ?? null,
    toggleText: document.querySelector('.app-nav-toggle')?.textContent?.trim() ?? null,
    currentMode: current ? current.textContent.trim() : '（页头没有这枚标签）',
    horizontalOverflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
  }
})

const report = label => async () => {
  console.log(`--- ${label} ---\n${JSON.stringify(await measure(), null, 2)}`)
}

const shot = name => page.screenshot({ path: `${SHOT_DIR}/${name}.png`, fullPage: false })

// 首帧的收起态取决于 localStorage 里存过的偏好（新开一个浏览器上下文时按窗口宽度算），
// 所以先归到展开态，两态都从同一个起点量
if ((await page.locator('.app-sidebar').getAttribute('data-collapsed')) === '1') {
  await page.locator('.app-nav-toggle').click()
  await page.waitForTimeout(SETTLE_MS)
}
await (report('展开态（232px，目录项与页头同屏）'))()
await shot('sidebar-expanded')

await page.locator('.app-nav-toggle').click()
await page.waitForTimeout(SETTLE_MS)
await (report('收起态（宽度归 0，页头多一枚当前视图标签）'))()
await shot('sidebar-collapsed')

await page.locator('.app-nav-toggle').click()
await page.waitForTimeout(SETTLE_MS)
const back = await measure()
console.log(`--- 再点一下 ---\n回到 ${back.sidebarWidth}px（> ${EXPANDED_WIDTH} 才算真的回来了）`)

await browser.close()
