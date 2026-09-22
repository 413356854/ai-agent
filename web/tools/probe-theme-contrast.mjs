/**
 * 一次性探针：量"深色下到底哪几个字看不清"。
 *
 * 上一版按"往上找第一个不透明的祖先"当背景，结果量到的是 **antd 卡片**的底色 ——
 * 而 `.app-nav-item` 明明在目录里。所以这一版不再猜：直接读**元素自己的** `color` 与
 * `backgroundColor`，再配一张"这个色号是什么语义"的表（值来自 styles.css 的变量）。
 * 这样量出来的数是"这两个色号差多少"，不会因为 DOM 结构而指错对象。
 *
 * 用法：node tools/probe-theme-contrast.mjs
 */
import { chromium } from 'playwright'

const URL = process.env.UI_URL ?? 'http://127.0.0.1:5173'
const browser = await chromium.launch({ channel: 'chrome' })
const page = await browser.newPage({ viewport: { width: 1100, height: 900 } })

await page.goto(URL, { waitUntil: 'domcontentloaded' })
await page.waitForSelector('.app-topbar')

/** 量一遍当前主题：把每个选择器自己的前景 / 背景 / 对比度打出来 */
const measure = label => page.evaluate(({ label, LAYERS }) => {
  const parse = c => {
    const m = /rgba?\((\d+), (\d+), (\d+)(?:, ([\d.]+))?\)/.exec(c)
    return m ? { r: +m[1], g: +m[2], b: +m[3], a: m[4] === undefined ? 1 : +m[4] } : null
  }
  const over = (fg, bg) => ({
    r: fg.r * fg.a + bg.r * (1 - fg.a),
    g: fg.g * fg.a + bg.g * (1 - fg.a),
    b: fg.b * fg.a + bg.b * (1 - fg.a),
    a: 1,
  })
  const lum = c => {
    const f = v => {
      const s = v / 255
      return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
    }
    return 0.2126 * f(c.r) + 0.7152 * f(c.g) + 0.0722 * f(c.b)
  }
  const ratio = (a, b) => {
    const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x)
    return Math.round(((hi + 0.05) / (lo + 0.05)) * 100) / 100
  }
  /** 元素**自己**的背景；透明的就当"压在下面那层"上，由调用方指定 */
  const own = sel => {
    const el = document.querySelector(sel)
    if (!el) return null
    const s = getComputedStyle(el)
    return { sel, color: s.color, bg: s.backgroundColor }
  }
  const rows = []
  const push = (sel, bgHint) => {
    const r = own(sel)
    if (!r) return
    const fg = parse(r.color)
    const ownBg = parse(r.bg)
    const bg = ownBg && ownBg.a === 1 ? ownBg : parse(bgHint)
    rows.push({
      sel, theme: label, color: r.color, selfBg: r.bg,
      vs: bgHint, ratio: ratio(over(fg, bg), bg),
    })
  }
  // 第二个参数是"它实际压在哪一层"：这些块自己多半是透明的，必须显式给
  const L = LAYERS[label]
  push('.app-title', L.container)
  push('.app-section-tag', L.brandBg)
  push('.app-nav-item', L.container)
  push('.app-nav-item.is-active', L.brandBg)
  push('.app-nav-group-title', L.container)
  push('.conversation-id', L.container)
  push('.app-brand-name', L.container)
  push('.theme-toggle', L.container)
  return rows
}, { label, LAYERS })

/** 每个色号是什么语义 —— 从 styles.css 的变量表里抄一遍，量出来的数才读得懂 */
const LAYERS = {
  dark: { container: 'rgb(20, 20, 20)', brandBg: 'rgb(17, 29, 44)' },
  light: { container: 'rgb(255, 255, 255)', brandBg: 'rgb(240, 247, 255)' },
}

const setTheme = async theme => {
  await page.evaluate(t => localStorage.setItem('ai-agent.theme', t), theme)
  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.app-topbar')
  await page.waitForTimeout(350)
}

const fmt = r => `  ${String(r.ratio).padStart(6)}  ${r.sel.padEnd(26)} 前景 ${r.color.padEnd(28)} 压 ${r.vs}`

for (const theme of ['dark', 'light']) {
  await setTheme(theme)
  const rows = await measure(theme)
  console.log(`\n===== ${theme === 'dark' ? '深色' : '浅色'}（WCAG AA：正文 4.5，大字 3.0）=====`)
  for (const r of rows) console.log(fmt(r))

  // 先挪开鼠标再截：停在按钮上会命中 `:hover`，截到的就不是默认态
  await page.mouse.move(400, 400)
  await page.waitForTimeout(200)
  await page.locator('.app-topbar').screenshot({ path: `ui-check-out/theme-topbar-${theme}.png` })
  await page.locator('.theme-toggle').screenshot({ path: `ui-check-out/theme-toggle-${theme}.png` })
  await page.locator('.theme-toggle').hover()
  await page.waitForTimeout(200)
  await page.locator('.theme-toggle').screenshot({ path: `ui-check-out/theme-toggle-${theme}-hover.png` })
}

await browser.close()
