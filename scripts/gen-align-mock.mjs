/**
 * 生成工具：把真后端 `/api/diag/rag/align` 的实测响应**瘦身**成界面假后端用的数据。
 * ⚠️ 它不是一次性脚本 —— `web/mock/rag-align-data.mjs` 会一直由它生成，换数据就重跑。
 *
 * 为什么要瘦身而不是直接把 185KB 的响应塞进仓库：
 *   `metrics.misses[].retrievedTitles` 是每条用例召回的所有块 id、`perTag` 是 5 个分组
 *   —— 这两块在对齐面板里一条都不显示（它们属于 `/compare` 那张表）。
 *   留着它们只会让假数据文件从 400 行涨到几千行，改起来没人看得清。
 *
 * 但**瘦身必须只删不造**：所有保留下来的字段名、嵌套层级、键的类型
 * （JSON 的键是字符串，例如 `recallAt: {"1": …}`）都原样来自真后端 ——
 * 假数据的价值就在于"数字可以是假的，形状必须是真的"（见 rag-routes.mjs 开头那段）。
 *
 * 要更新假数据，按下面三步重跑一遍：
 *
 *   # 1) 起真后端（假 key 即可 —— RAG 链路 0 次调模型），然后抓一次实测响应
 *   $env:OPENAI_API_KEY='sk-local-probe-not-a-real-key'   # 只是过启动自检，不发请求
 *   Invoke-WebRequest http://127.0.0.1:8080/api/diag/rag/align -OutFile align-response.json
 *
 *   # 2) 瘦身成假数据
 *   node scripts/gen-align-mock.mjs align-response.json web/mock/rag-align-data.mjs
 *
 *   # 3) 删掉中间产物（它是本机实测快照，不该进仓库）
 *   Remove-Item align-response.json
 *
 * 注意本机实测不在仓库里，所以**回不到"当初那份原始响应"** ——
 * 这也正是要留下生成脚本、而不是手改数字的原因：任何一次改动都必须是"重跑一遍"，
 * 而不是"在文件里改几个数"。
 *
 * 用法：node scripts/gen-align-mock.mjs <align-response.json> <输出文件>
 */
import { readFileSync, writeFileSync } from 'node:fs'

const [, , input, output] = process.argv
if (!input || !output) {
  console.error('用法：node scripts/gen-align-mock.mjs <align-response.json> <输出 .mjs>')
  process.exit(1)
}

const d = JSON.parse(readFileSync(input, 'utf8'))

/** 指标对象瘦身：去掉对齐面板不显示的 misses / perTag / budgetAlignment */
function slimMetrics(m) {
  return {
    strategy: m.strategy,
    caseCount: m.caseCount,
    chunkCount: m.chunkCount,
    avgChunkTokens: m.avgChunkTokens,
    recallAt: m.recallAt,
    mrr: m.mrr,
    contextPrecisionAt: m.contextPrecisionAt,
    firstThreeHitsTokens: m.firstThreeHitsTokens,
    topKTokens: m.topKTokens,
    budgetAlignment: m.budgetAlignment,
    // ⚠️ 这三个字段刻意保留成空壳：真后端**一定有**它们，界面将来若用到，
    // 空数组会让"没渲染"和"后端没返回"长得一样。留空即是在提醒这一点
    misses: [],
    perTag: {},
  }
}

const slim = {
  ...d,
  pairs: d.pairs.map(p => ({
    fieldDescription: p.fieldDescription,
    dimension: p.dimension,
    reports: p.reports.map(r => ({
      strategy: r.strategy,
      chunkCount: r.chunkCount,
      avgChunkTokens: r.avgChunkTokens,
      maxChunkTokens: r.maxChunkTokens,
      metrics: slimMetrics(r.metrics),
      tokenCost: r.tokenCost,
    })),
    deltas: p.deltas,
    notes: p.notes,
  })),
}

const header = `/**
 * 块大小对齐实验（\`/api/diag/rag/align\`）的**假数据**。
 *
 * ⚠️ 这个文件由 \`scripts/gen-align-mock.mjs\` 从真后端的一次实测响应生成，
 * **不要手改数字** —— 手改就会与"界面上的解读文字"脱钩，而自检断言不了
 * "文字和数字是否一致"（rag-routes.mjs 开头那段说的就是这个坑）。
 * 要更新就重新跑一次真后端 + 生成脚本。
 *
 * 数值来源：真启动的 Spring Boot 后端，GET /api/diag/rag/align
 *   43 条用例 / 4 份文档 / 三路检索 / 六档预算 / HTTP 200 / 184585 字节 / 2.8s（2026-09-18 重测）
 *
 * 它承载的结论（阶段二「块大小对齐实验」，完整说明见 \`docs/项目功能点与代码索引.md\` 的 3.5 节）：
 *   块大小对齐之后（202.8 vs 202.4 token，差 0.41），**固定字数硬切全面占优** ——
 *   Recall@5 / MRR / token 总量 / 重复率都更优，且**三路检索下方向一致**。
 *
 * 所以这份假数据里，\`pairs\` 的**左（对齐硬切）必须一直优于右（结构无面包屑）**：
 * 如果哪天它反过来了，那界面就在自相矛盾 —— 自检脚本里有一条断言专门盯着这件事。
 */
`

writeFileSync(
  output,
  `${header}\nexport const ALIGNMENT = ${JSON.stringify(slim, null, 2)}\n`,
  'utf8',
)

console.log(`已生成 ${output}`)
console.log(`  pairs=${slim.pairs.length} sweep=${slim.sweep.length} notes=${slim.notes.length} budgets=${slim.budgetSteps.length}`)
console.log(`  字节数=${readFileSync(output, 'utf8').length}`)
