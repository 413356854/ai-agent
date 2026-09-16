/**
 * 一次性排查脚本：找出 RAG 面板里**哪一张卡**还带着字面量 `**`。
 *
 * 用法（先起 `pnpm mock` + `pnpm dev:mock`）：
 *   node tools/probe-asterisk.mjs
 *
 * ---------------------------------------------------------------------------
 * 为什么值得单独写一个探针
 * ---------------------------------------------------------------------------
 * 自检里那条守卫只能告诉你"这一页里有星号"，而**哪一张卡**带着它，
 * 靠读代码猜一遍要花十几分钟（本轮实测如此）。打印出每张卡里含 `**` 的文本片段，
 * 一眼就能定位。
 *
 * ⚠️ **它的结论有两个已知盲区，第二次用的时候要记得**（都是本轮实测出来的）：
 *   1. 探针默认看到的是**当前渲染出来的那一屏**：分块预览默认是「结构分块」，
 *      而带星号的那条假数据属于「固定硬切」那一支 —— 所以**不切策略就看不到它**。
 *      要复现本轮那条，得先点一下 `.ant-segmented-item`（固定硬切）再看。
 *      （自检脚本会一路切过去，所以它在自检里现形、在探针里不现形 —— 两边并不矛盾。）
 *   2. 原文预览区里的星号**未必是缺陷**：`.rag-chunk` 显示的就是 Markdown 原文的字节，
 *      "硬切把标题层级丢了、正文里的星号原样留着"恰恰是证据。
 *      自检的判据已经把 `.rag-chunk` / `pre` / `code` 排除在外 —— 这个分工要记住。
 */
import { chromium } from 'playwright'

const URL = process.env.UI_URL ?? 'http://127.0.0.1:5173'
const browser = await chromium.launch({ channel: 'chrome' })
const page = await browser.newPage()
await page.goto(URL, { waitUntil: 'domcontentloaded' })
await page.locator('label', { hasText: 'RAG 分块评测' }).click()
await page.waitForTimeout(2500)
// 让对照表与对齐实验都真的加载出来（它们要么自动跑、要么要点一下）
await page.locator('.diag-card', { hasText: '分块策略对照' }).first()
  .locator('button', { hasText: '跑对照' }).click().catch(() => {})
await page.waitForTimeout(3000)
// 分块预览：**两种策略都要看**（默认只渲染「结构分块」，而假数据里带星号的那条属于「固定硬切」）
for (const label of ['固定硬切', '结构分块']) {
  await page.locator('.ant-segmented-item', { hasText: label }).first().click({ timeout: 2000 }).catch(() => {})
  await page.waitForTimeout(2500)
  const previewHits = await page.evaluate(() => {
    const out = []
    for (const el of document.querySelectorAll('.rag-chunk, .diag-card, .ant-alert, .diag-notes li')) {
      const text = el.innerText ?? ''
      if (!text.includes('**')) continue
      const idx = text.indexOf('**')
      out.push({ where: el.className || el.tagName, snippet: text.slice(Math.max(0, idx - 60), idx + 80).replace(/\n/g, ' ⏎ ') })
    }
    return out
  })
  if (previewHits.length > 0) {
    console.log(`策略「${label}」下含 ** 的元素：`)
    console.log(JSON.stringify(previewHits, null, 2))
  }
}
// 对齐实验（它可能有个「跑一次」/「跑实验」的按钮）
const alignCard = page.locator('.diag-card', { hasText: '块大小对齐' }).first()
for (const label of ['跑一次', '跑对齐', '跑实验', '重新跑']) {
  await alignCard.locator('button', { hasText: label }).first().click({ timeout: 1500 }).catch(() => {})
}
await page.waitForTimeout(6000)

const all = await page.evaluate(() => {
  const panel = document.querySelector('.diag-panel')
  return panel ? panel.innerText.includes('**') : null
})
console.log('panel has ** :', all)

const hits = await page.evaluate(() => {
  const out = []
  // ⚠️ 扫描范围是**整页**（不只 `.diag-card`）：本轮那条真正的星号在 `.rag-chunk` 里，
  // 而只扫卡片会得到"没有"这个错误结论（见文件头那段说明）
  const targets = document.querySelectorAll('.diag-card, .rag-chunk, .ant-alert, .diag-note, .diag-notes li')
  for (const el of targets) {
    const card = el.closest('.diag-card')
    const title = card?.querySelector('.ant-card-head-title')?.innerText?.trim() ?? '(卡片外)'
    const text = el.innerText ?? ''
    if (text.includes('**')) {
      const idx = text.indexOf('**')
      out.push({
        where: el.className || el.tagName,
        title,
        snippet: text.slice(Math.max(0, idx - 60), idx + 80).replace(/\n/g, ' ⏎ '),
      })
    }
  }
  return out
})
console.log(JSON.stringify(hits, null, 2))
await browser.close()
