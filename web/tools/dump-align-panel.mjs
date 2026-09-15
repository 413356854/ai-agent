/**
 * 一次性工具：把对齐面板渲染后的**结构**打出来，人工核对一遍。
 *
 * 为什么需要它：自检脚本断言的是"元素在不在"，而"渲染出来的表到底长什么样"
 * （预算表 30 行有没有对上、扫描表哪一行高亮、7 组的分组标签是什么）
 * 只能靠把 DOM 读出来看。当前模型看不了截图，所以用这套结构化 dump 代替肉眼看图。
 *
 * 用法：UI_URL=http://127.0.0.1:5173 node scripts/dump-align-panel.mjs
 */
import { chromium } from 'playwright'

const URL = process.env.UI_URL ?? 'http://127.0.0.1:5173'
const browser = await chromium.launch({ channel: 'chrome' })
const page = await browser.newPage({ viewport: { width: 1400, height: 1000 } })
await page.goto(URL, { waitUntil: 'networkidle' })

await page.locator('label', { hasText: 'RAG 分块评测' }).click()
const card = page.locator('.align-card').first()
await card.locator('.rag-metric-table').first().waitFor({ state: 'visible', timeout: 120000 })
await page.waitForTimeout(800)

const dump = await card.evaluate(el => {
  const tables = Array.from(el.querySelectorAll('table'))
  return {
    statCells: Array.from(el.querySelectorAll('.stat-cell')).map(c => c.innerText.replace(/\n/g, '=')),
    groupHeads: Array.from(el.querySelectorAll('.align-group-head')).map(h => h.innerText.replace(/\n/g, ' | ')),
    tables: tables.map((t, i) => ({
      index: i,
      head: Array.from(t.querySelectorAll('thead th')).map(th => th.innerText.trim()),
      rows: Array.from(t.querySelectorAll('tbody tr')).map(tr =>
        Array.from(tr.querySelectorAll('td')).map(td => td.innerText.trim().replace(/\n/g, ' ')),
      ),
    })),
    notesCollapseLabel: el.querySelector('.ant-collapse-header')?.innerText.trim(),
    notesCount: el.querySelectorAll('.issue-list li').length,
    detailsSummaries: Array.from(el.querySelectorAll('details > summary')).map(s => s.innerText.trim()),
  }
})

console.log('=== 概览格 ===')
dump.statCells.forEach(s => console.log('  ', s))
console.log('\n=== 分组标签 ===')
dump.groupHeads.forEach(g => console.log('  ', g))
console.log('\n=== 备注 ===')
console.log('  折叠标题:', dump.notesCollapseLabel)
console.log('  备注条数:', dump.notesCount)
console.log('  各组注意事项:', dump.detailsSummaries.length, '个折叠块')
dump.detailsSummaries.forEach(s => console.log('    -', s))

for (const t of dump.tables) {
  console.log(`\n=== 表 ${t.index} === 列: ${t.head.join(' | ')}`)
  console.log(`  行数: ${t.rows.length}`)
  t.rows.forEach(r => console.log('   ', r.join(' | ')))
}

// 布局体检：表宽超过视口容器就会出现横向溢出（自检脚本用几何断言盯着这个）
const overflow = await page.evaluate(() => {
  const doc = document.documentElement
  return doc.scrollWidth - doc.clientWidth
})
console.log('\n=== 布局 ===')
console.log('  页面横向溢出:', overflow, 'px')

await browser.close()
