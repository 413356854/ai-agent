/**
 * 一次性探针：看一眼深色模式到底渲染成什么样（自检断言之前在它这里过一遍）。
 *
 * 与自检脚本的分工：这里只打印取到的颜色与几何，不做判断 ——
 * "哪一块还是白底"这类问题要靠人看数值，而不是靠再写一层断言。
 *
 * 用法：node tools/probe-theme.mjs
 */
import { chromium } from 'playwright'

const URL = process.env.UI_URL ?? 'http://127.0.0.1:5173'
const browser = await chromium.launch({ channel: 'chrome' })
const page = await browser.newPage({ viewport: { width: 1100, height: 900 } })

const consoleMsgs = []
page.on('console', m => consoleMsgs.push(`[${m.type()}] ${m.text()}`))
page.on('pageerror', e => consoleMsgs.push(`[pageerror] ${e.message}`))

const probe = async label => {
  const data = await page.evaluate(() => {
    const bg = sel => {
      const el = document.querySelector(sel)
      return el ? getComputedStyle(el).backgroundColor : '(没有这个元素)'
    }
    const fg = sel => {
      const el = document.querySelector(sel)
      return el ? getComputedStyle(el).color : '(没有这个元素)'
    }
    const link = id => {
      const el = document.getElementById(id)
      return el ? `${el.sheet ? '生效' : '未生效'}/disabled=${el.disabled}` : '(没有这张表)'
    }
    return {
      theme: document.documentElement.dataset.theme,
      colorScheme: document.documentElement.style.colorScheme || '(未设)',
      bodyBg: bg('body'),
      sidebarBg: bg('.app-sidebar'),
      topbarBg: bg('.app-topbar'),
      titleColor: fg('.app-title'),
      titleTagBg: fg('.app-section-tag'),
      navItemColor: fg('.app-nav-item'),
      conversationIdColor: fg('.conversation-id'),
      hljsLight: link('hljs-light'),
      hljsDark: link('hljs-dark'),
      toggleText: document.querySelector('.theme-toggle')?.getAttribute('aria-label'),
      togglePressed: document.querySelector('.theme-toggle')?.getAttribute('aria-pressed'),
      overflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
    }
  })
  console.log(`\n===== ${label} =====`)
  for (const [k, v] of Object.entries(data)) console.log(`  ${k.padEnd(20)} ${v}`)
  return data
}

await page.goto(URL, { waitUntil: 'domcontentloaded' })
await page.waitForSelector('.app-topbar')
await page.waitForTimeout(400)
await probe('首帧（系统偏好 / 存过的偏好）')
await page.screenshot({ path: 'ui-check-out/theme-probe-before.png' })

await page.locator('.theme-toggle').click()
await page.waitForTimeout(400)
await probe('点一下开关之后')
await page.screenshot({ path: 'ui-check-out/theme-probe-after.png' })

// 刷新一次：存过的偏好要能撑过刷新（否则每次进来都要再点一下）
await page.reload({ waitUntil: 'domcontentloaded' })
await page.waitForSelector('.app-topbar')
await page.waitForTimeout(300)
const afterReload = await probe('刷新之后（应当仍是上一步那个主题）')

// 各视图走一遍：找"深色下还是白底"的那些块
if (afterReload.theme === 'dark') {
  const labels = ['结构化输出', '线程与预算', 'RAG 分块评测', 'Agent 工具调用', '成本看板',
    'Prompt 版本', '告警与灰度', '用户管理', '权限管理', 'agent任务']
  console.log('\n===== 逐个视图找白底块 =====')
  for (const label of labels) {
    const item = page.locator('.app-nav-item', { hasText: label }).first()
    if ((await item.count()) === 0) {
      console.log(`  ${label.padEnd(12)} (目录里没有这一项)`)
      continue
    }
    await item.click()
    await page.waitForTimeout(700)
    // 把整页里"底色是浅色"的可见元素都列出来 —— 深色下它们就是刺眼的那几块
    const light = await page.evaluate(() => {
      const isLight = c => {
        const m = /rgba?\((\d+), (\d+), (\d+)/.exec(c)
        if (!m) return false
        const [r, g, b] = [Number(m[1]), Number(m[2]), Number(m[3])]
        return (r + g + b) / 3 > 200
      }
      const out = []
      for (const el of document.querySelectorAll('body *')) {
        const rect = el.getBoundingClientRect()
        if (rect.width < 8 || rect.height < 8) continue
        if (rect.bottom < 0 || rect.top > window.innerHeight) continue
        const style = getComputedStyle(el)
        if (style.visibility === 'hidden' || style.opacity === '0') continue
        if (!isLight(style.backgroundColor)) continue
        out.push(`${el.tagName.toLowerCase()}.${(el.className || '').toString().split(' ').slice(0, 3).join('.')} ${style.backgroundColor}`)
      }
      return [...new Set(out)].slice(0, 12)
    })
    console.log(`  ${label}：${light.length === 0 ? '没有浅底元素' : ''}`)
    for (const l of light) console.log(`      ${l}`)
    await page.screenshot({ path: `ui-check-out/theme-dark-${label}.png`, fullPage: false })
  }
}

console.log('\n===== 控制台 =====')
console.log(consoleMsgs.length === 0 ? '  干净' : consoleMsgs.map(m => `  ${m}`).join('\n'))
await browser.close()
