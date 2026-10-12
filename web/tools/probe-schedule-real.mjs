/**
 * 一次性探针：定时任务那一页打**真后端**时的样子（截图 + 关键读数）。
 *
 * <h2>为什么单独一个探针</h2>
 * `verify-ui.mjs` 的"真后端"那条路径（`--with-real-ui`）在它前面就会红着停下 ——
 * 告警 / 成本那几节要库里真有运行记录（假 key 下上游 401，一次运行都落不下来），
 * 而 `[8.10]` 排在那几节后面。所以这一页在真后端上"长什么样、读得到什么"
 * 需要单独看一眼，否则它就成了"只在假后端上验过"的一节。
 *
 * 用法（真后端起着，vite 指向它）：
 *   UI_URL=http://127.0.0.1:5299 node tools/probe-schedule-real.mjs
 *
 * ⚠️ 它**只读**：不点「配一条规则」、不点「现在跑一次」、不点任何启停/删除 ——
 * 那些都会真的改库或真的派任务（派出去的任务会去调模型花钱）。
 */
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'

const URL = process.env.UI_URL ?? 'http://127.0.0.1:5299'
const SHOT_DIR = 'ui-check-out'
mkdirSync(SHOT_DIR, { recursive: true })

const browser = await chromium.launch({ channel: 'chrome' })
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } })
await page.goto(URL, { waitUntil: 'networkidle' })

// 切到定时任务（目录项与 verify-ui.mjs 的 navMode 同一套动作）
const sidebar = page.locator('.app-sidebar')
if ((await sidebar.getAttribute('data-collapsed')) === '1') {
  await page.locator('.app-nav-toggle').click()
  await page.waitForTimeout(600)
}
const folded = sidebar.locator('.app-nav-group-title[aria-expanded="false"]')
for (let i = 0; i < 2 && (await folded.count()) > 0; i++) await folded.first().click()
await sidebar.getByRole('button', { name: '定时任务', exact: true }).click()
await page.waitForTimeout(1500)

const api = await (await page.request.get(`${URL}/api/agent-task-schedules`)).json()
const panel = await page.evaluate(() => ({
  panelExists: document.querySelectorAll('.schedule-panel').length,
  title: document.querySelector('.schedule-panel .ant-card-head-title')?.innerText?.replace(/\n/g, ' '),
  poolText: document.querySelector('.schedule-pool')?.innerText?.replace(/\n/g, ' '),
  poolScheduled: document.querySelector('.schedule-pool')?.getAttribute('data-pool-scheduled'),
  cards: document.querySelectorAll('.schedule-card').length,
  firstCard: document.querySelector('.schedule-card')?.innerText?.replace(/\n/g, ' | ').slice(0, 260),
  overflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
  newRuleDisabled: document.querySelector('.schedule-panel .ant-btn-primary')?.disabled,
  scope: document.querySelector('.schedule-panel .task-board-scope')?.innerText?.replace(/\n/g, ' '),
}))

console.log(JSON.stringify({ api: { total: api.total, pool: api.pool, rules: api.rules.map(r => ({
  id: r.id, cron: r.cron, desc: r.cronDescription, enabled: r.enabled, scheduled: r.scheduled,
  runs: r.runs, next: r.nextRuns[0] ?? null, assignee: r.assignee, assigneeName: r.assigneeName,
})) }, panel }, null, 2))

await page.screenshot({ path: `${SHOT_DIR}/schedule-panel-real.png` })

// 随便点一张卡片的「改」，看详情弹窗在真后端上读得对不对（**不提交**）
if (await page.locator('.schedule-card button').filter({ hasText: /^改$/ }).count() > 0) {
  await page.locator('.schedule-card button').filter({ hasText: /^改$/ }).first().click()
  await page.waitForTimeout(1500)
  const dialog = page.locator('.ant-modal:visible')
  console.log('--- 改规则弹窗 ---')
  console.log(JSON.stringify({
    title: await dialog.locator('.ant-modal-title').innerText(),
    checkedMode: await dialog.locator('.ant-radio-button-wrapper-checked').innerText().catch(() => '（无）'),
    previewCron: await dialog.locator('.schedule-preview').getAttribute('data-cron-preview'),
    previewDesc: await dialog.locator('[data-preview-desc]').innerText().catch(() => '（无）'),
    previewRuns: await dialog.locator('[data-preview-runs]').innerText().catch(() => '（无）'),
    assigneeFilled: await dialog.locator('.user-picker input').first().inputValue(),
    okDisabled: await dialog.locator('.ant-btn-primary').isDisabled(),
  }, null, 2))
  await page.screenshot({ path: `${SHOT_DIR}/schedule-modal-real.png` })
  await dialog.locator('.ant-btn', { hasText: /^取\s*消$/ }).first().click()
}

await browser.close()
