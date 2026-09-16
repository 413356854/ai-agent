/**
 * RAG 诊断端点的**假数据**（阶段二）。
 *
 * ---------------------------------------------------------------------------
 * 为什么这些假数据要"照着真后端的形状写"，而不是随便编几个数字
 * ---------------------------------------------------------------------------
 * 界面自检（verify-ui.mjs）要用确定性数据断言界面逻辑。真后端跑出来的数字
 * 每次都不一样（语料重新分块、检索分数变化），断言只能写得很松，松到失去意义。
 *
 * 所以这里的原则是：**数字是假的，形状是真的**。
 *   - 指标的确切数值无所谓，但 `recallAt` 必须是「字符串键 → 数字」——
 *     因为 JSON 的键只能是字符串，真后端返回的就是 `{"1":0.46,"5":0.81}`，
 *     界面要是按数字键去取（`recallAt[5]`）就会永远拿到 undefined，
 *     而这种错在假后端用数字键时反而测不出来；
 *   - `deltas` 必须是「指标名 → 策略名 → 数字」两层嵌套；
 *   - `perTag` / `misses` 必须真的存在且非空，否则界面里那两块会渲染成空，
 *     而"空"和"没实现"在自检里长得一样。
 *
 * ---------------------------------------------------------------------------
 * 数值直接取自真后端的一次实测（43 条用例 / 4 份文档）
 * ---------------------------------------------------------------------------
 * 用真实数字而不是编的，是因为**界面上的解读文字与这些数字绑定**：
 * 那句"换分块策略并没有提升召回率"如果配上一组"结构分块更高"的假数字，
 * 界面就会自相矛盾 —— 而自检断言不了"文字和数字是否一致"，
 * 只有让假数据与真实结论同向，人工看的时候才不会被误导。
 */
import { ALIGNMENT } from './rag-align-data.mjs'

const STRATEGIES = [
  {
    strategy: 'fixed-size',
    chunkCount: 157,
    avgChunkTokens: 234.8,
    maxChunkTokens: 328,
    metrics: {
      strategy: 'fixed-size',
      caseCount: 43,
      chunkCount: 157,
      avgChunkTokens: 234.8,
      recallAt: { '1': 0.4651, '3': 0.7674, '5': 0.814, '10': 0.8605 },
      mrr: 0.6173,
      contextPrecisionAt: { '1': 0.4651, '3': 0.3256, '5': 0.2698, '10': 0.1558 },
      firstThreeHitsTokens: 743,
      topKTokens: 2457,
      misses: [
        { caseId: 'L11', tag: 'phase1', question: '怎么判断当前跑的线程到底是轻量级线程还是操作系统线程？', hitRank: 0, matchedTitles: ['三、基础设施'], retrievedTitles: [], retrievedTokens: 0 },
        { caseId: 'R02', tag: 'phase2', question: '文档分块的时候怎么防止把一段话从中间切断？', hitRank: 0, matchedTitles: ['阶段二（2–4 周）RAG'], retrievedTitles: [], retrievedTokens: 0 },
        { caseId: 'K06', tag: 'knowledge', question: '高危的写操作在智能体里应该怎么控制？', hitRank: 0, matchedTitles: ['P1-1 可靠性与 HITL'], retrievedTitles: [], retrievedTokens: 0 },
      ],
      perTag: {
        failures: { caseCount: 11, recallAt5: 1.0, recallAt10: 1.0, mrr: 0.8485 },
        phase1: { caseCount: 9, recallAt5: 0.7778, recallAt10: 0.8889, mrr: 0.6852 },
        phase2: { caseCount: 11, recallAt5: 0.7273, recallAt10: 0.7273, mrr: 0.4303 },
        knowledge: { caseCount: 8, recallAt5: 0.625, recallAt10: 0.75, mrr: 0.497 },
        materials: { caseCount: 4, recallAt5: 1.0, recallAt10: 1.0, mrr: 0.5833 },
      },
    },
    tokenCost: { totalTokens: 36864, duplicationRatio: 0.137, minTokens: 96, undersizedChunks: 12 },
  },
  {
    strategy: 'structural',
    chunkCount: 235,
    avgChunkTokens: 200.9,
    maxChunkTokens: 390,
    metrics: {
      strategy: 'structural',
      caseCount: 43,
      chunkCount: 235,
      avgChunkTokens: 200.9,
      recallAt: { '1': 0.4186, '3': 0.6047, '5': 0.7674, '10': 0.8372 },
      mrr: 0.5452,
      contextPrecisionAt: { '1': 0.4186, '3': 0.2713, '5': 0.2512, '10': 0.1605 },
      firstThreeHitsTokens: 590,
      topKTokens: 2013,
      misses: [
        { caseId: 'L01', tag: 'failures', question: '开发过程中实际踩到的、教程里通常不会写的坑都有哪些？', hitRank: 0, matchedTitles: ['四、踩坑知识库'], retrievedTitles: [], retrievedTokens: 0 },
        { caseId: 'L11', tag: 'phase1', question: '怎么判断当前跑的线程到底是轻量级线程还是操作系统线程？', hitRank: 0, matchedTitles: ['三、基础设施'], retrievedTitles: [], retrievedTokens: 0 },
        { caseId: 'R09', tag: 'phase2', question: '向量库想换一个厂商，代码上怎么做才不用大改？', hitRank: 0, matchedTitles: ['阶段二（2–4 周）RAG'], retrievedTitles: [], retrievedTokens: 0 },
      ],
      perTag: {
        failures: { caseCount: 11, recallAt5: 0.9091, recallAt10: 0.9091, mrr: 0.6167 },
        phase1: { caseCount: 9, recallAt5: 0.7778, recallAt10: 0.8889, mrr: 0.3593 },
        phase2: { caseCount: 11, recallAt5: 0.7273, recallAt10: 0.8182, mrr: 0.4783 },
        knowledge: { caseCount: 8, recallAt5: 0.625, recallAt10: 0.75, mrr: 0.6458 },
        materials: { caseCount: 4, recallAt5: 0.75, recallAt10: 0.75, mrr: 0.75 },
      },
    },
    tokenCost: { totalTokens: 47214, duplicationRatio: 0.456, minTokens: 42, undersizedChunks: 61 },
  },
]

/** 指标名 → 策略名 → 相对对照组的百分点差值（结构与真后端一致） */
const DELTAS = {
  'recall@1': { 'fixed-size': 0, structural: -4.65 },
  'recall@3': { 'fixed-size': 0, structural: -16.27 },
  'recall@5': { 'fixed-size': 0, structural: -4.66 },
  'recall@10': { 'fixed-size': 0, structural: -2.33 },
  mrr: { 'fixed-size': 0, structural: -7.21 },
  chunkCount: { 'fixed-size': 0, structural: 7800 },
  avgChunkTokens: { 'fixed-size': 0, structural: -3392.18 },
  firstThreeHitsTokens: { 'fixed-size': 0, structural: -15244.18 },
  duplicationRatio: { 'fixed-size': 0, structural: 31.9 },
}

const NOTES = [
  '差值单位为「百分点」（percentage point），且一律相对对照组「fixed-size」计算',
  '相关性判定按「块覆盖了原文哪些块」与「标准答案章节覆盖哪些块」是否有交集来做，因此两种策略在同一个判据下比较',
  '块数不同会天然影响 Recall（块越多越容易捞到答案），所以必须同时看 MRR 与 token 成本，不能只拿 Recall 一个数下结论',
  // ⚠️ 这段（以及本文件所有 notes）不许出现 markdown 星号：它们原样渲染到面板的纯文本 <li> 里。
  // 真后端那一侧同样改过一遍 —— **假数据必须跟着改**，否则这一类缺陷在假路径上永远看不出来
  '本阶段检索为「词法（BM25）」：它只认词面，对"块里有没有正确的词"极其敏感',
  '⚠ 有 1 处标注在语料里找不到对应章节（假数据示例），这些用例必然算作未命中',
]

const CORPUS = {
  location: 'classpath:rag/corpus',
  documentCount: 4,
  blockCount: 516,
  blockTypes: { heading: 82, paragraph: 301, list_item: 53, table: 46, code: 9, quote: 4, other: 2 },
  documents: [
    { documentId: 'ledger-progress.md', blocks: 162, chars: 38317 },
    { documentId: 'roadmap-java-to-agent.md', blocks: 156, chars: 13598 },
    { documentId: 'knowledge-points-ranked.md', blocks: 118, chars: 16257 },
    { documentId: 'materials-checklist.md', blocks: 80, chars: 16339 },
  ],
  failed: [],
  availableStrategies: ['fixed-size', 'structural'],
  note: 'blockTypes 里 heading 为 0 说明标题层级没被识别 —— 那『按标题分块』就没有真正生效，后面的对比结果都不可信',
}

function previewFor(strategy) {
  const structural = strategy === 'structural'
  const base = structural
    ? [
        { index: 0, tokens: 347, chars: 812, breadcrumb: '学习进度台账 > 二、阶段一 · 大模型对接层（8 个学习点，完成 7 个）', text: '[学习进度台账 > 二、阶段一 · 大模型对接层（8 个学习点，完成 7 个）]\n当前产出：一个可运行的 Spring Boot 4.1.1 + Spring AI 2.0.1 项目骨架……\n（假后端返回的示例块，结构与真后端一致）' },
        { index: 1, tokens: 268, chars: 640, breadcrumb: '学习进度台账 > 四、踩坑知识库', text: '[学习进度台账 > 四、踩坑知识库]\n| # | 问题 | 根因 | 结论 |\n| 1 | options(...) 编译失败 | 它接收 ChatOptions.Builder | 传 .builder().temperature(t) |' },
        { index: 2, tokens: 190, chars: 455, breadcrumb: '学习进度台账 > 四、踩坑知识库', text: '[学习进度台账 > 四、踩坑知识库]\n| 12 | switchOnFirst(signal, flux) 的 flux 参数已经包含首元素 | 官方签名看不出这点 | 不要再 concat 首元素 |' },
      ]
    : [
        { index: 0, tokens: 318, chars: 500, breadcrumb: '', text: '# 学习进度台账\n\n> **单一事实来源**：进度只在这里详细记录，其余文档只保留状态列并指向本文件\n（假后端返回的示例块：固定硬切不知道标题在哪，所以面包屑恒为空）' },
        { index: 1, tokens: 305, chars: 500, breadcrumb: '', text: '避免多处重复维护导致不一致。\n最后更新：2026-09-14\n\n## 一、总览\n\n| 阶段 | 主题 | 状态 | 完成度 |\n|---|---|---|---|' },
        { index: 2, tokens: 292, chars: 500, breadcrumb: '', text: '| 阶段一 | 大模型对接层 | 收尾完成 | 8 个学习点完成 7 个 |\n| 阶段二 | RAG | 未开始 | 0% |\n（示例：切点与语义无关，标题和正文被切开）' },
      ]

  return {
    strategy,
    chunkCount: structural ? 235 : 157,
    avgTokens: structural ? 200.9 : 234.8,
    maxTokens: structural ? 390 : 328,
    minTokens: structural ? 42 : 96,
    previewShown: base.length,
    previews: base.map(c => ({
      id: `${structural ? 'structural' : 'fixed'}#${c.index}`,
      startBlock: c.index * 2,
      endBlock: c.index * 2 + 1,
      coveredBlocks: 2,
      ...c,
    })),
  }
}

const EVAL_SET = {
  location: 'classpath:rag/eval-set.tsv',
  caseCount: 43,
  byTag: { failures: 11, phase1: 9, phase2: 11, knowledge: 8, materials: 4 },
  minUsableCases: 30,
  sampleSufficient: true,
  cases: [
    { source: 'ledger-progress.md', id: 'L01', question: '开发过程中实际踩到的、教程里通常不会写的坑都有哪些？', matchHeadings: ['四、踩坑知识库'], tag: 'failures' },
    { source: 'ledger-progress.md', id: 'L09', question: 'token 估算的系数是怎么标定出来的，第一版为什么会错那么多？', matchHeadings: ['学习点 2 · Token 预算校验'], tag: 'phase1' },
    { source: 'roadmap-java-to-agent.md', id: 'R02', question: '文档分块的时候怎么防止把一段话从中间切断？', matchHeadings: ['阶段二（2–4 周）RAG'], tag: 'phase2' },
    { source: 'knowledge-points-ranked.md', id: 'K01', question: '工具的描述该怎么写，模型才不容易选错？', matchHeadings: ['3.1 工具设计'], tag: 'knowledge' },
    { source: 'materials-checklist.md', id: 'M01', question: 'Spring AI 官方推荐的稳定版本是哪一个？', matchHeadings: ['版本基线'], tag: 'materials' },
    { source: 'ledger-progress.md', id: 'L10', question: '用 JDK 的轻量级线程替换容器线程池之后，系统吞吐能提升多少？', matchHeadings: ['学习点 7 · 虚拟线程异步改造'], tag: 'phase1' },
  ],
}

const RETRIEVE_HITS = [
  { rank: 1, score: 7.227, chunkId: 'structural#12', breadcrumb: '学习进度台账 > 二、阶段一 · 大模型对接层 > ✅ 已完成', tokens: 347, excerpt: '| 学习点 | 一句话结论 | 关键数字 | …' },
  { rank: 2, score: 6.306, chunkId: 'structural#31', breadcrumb: '学习进度台账 > 四、踩坑知识库', tokens: 268, excerpt: '| 12 | switchOnFirst(signal, flux) 的 flux 参数已经包含首元素 | …' },
  { rank: 3, score: 4.889, chunkId: 'structural#48', breadcrumb: 'Java 开发者 → AI Agent 岗位 · 专属路线 > 阶段二（2–4 周）RAG', tokens: 190, excerpt: '文档解析 → 语义分块 + Overlap（别按固定字数硬切）→ Embedding → 向量库' },
  { rank: 4, score: 4.686, chunkId: 'structural#7', breadcrumb: '学习进度台账 > 一、总览', tokens: 212, excerpt: '当前产出：一个可运行的 Spring Boot 4.1.1 + Spring AI 2.0.1 项目骨架' },
  { rank: 5, score: 3.828, chunkId: 'structural#55', breadcrumb: '学习进度台账 > 五、下一步计划', tokens: 233, excerpt: '建 30–50 条评测集 —— 阶段二的硬性产出' },
]

/**
 * 安装 RAG 假端点。返回 true 表示这个请求已被接管。
 *
 * 单独放一个函数（而不是继续堆在主文件的 if 链里）是因为这一组端点的假数据
 * 有真实的嵌套结构，堆在一个文件里会让主文件的 if 链彻底淹没在数据里。
 */
export function handleRagRoutes(req, res, url, json) {
  const pathname = url.pathname

  if (req.method === 'GET' && pathname === '/api/diag/rag/corpus') {
    json(res, CORPUS)
    return true
  }

  if (req.method === 'GET' && pathname === '/api/diag/rag/preview') {
    json(res, previewFor(url.searchParams.get('strategy') ?? 'structural'))
    return true
  }

  if (req.method === 'GET' && pathname === '/api/diag/rag/tokens') {
    const text = url.searchParams.get('text') ?? ''
    json(res, {
      text,
      tokenCount: tokenize(text).length,
      tokens: tokenize(text),
      note: '中文被切成二元组（虚拟线程 → 虚拟/拟线/线程），英文按词切。检索不到结果时先看这里',
    })
    return true
  }

  if (req.method === 'GET' && pathname === '/api/diag/rag/retrieve') {
    const query = url.searchParams.get('query') ?? ''
    json(res, {
      query,
      strategy: url.searchParams.get('strategy') ?? 'structural',
      indexedChunks: 235,
      hitCount: RETRIEVE_HITS.length,
      hits: RETRIEVE_HITS,
      queryTokens: tokenize(query),
    })
    return true
  }

  if (req.method === 'GET' && pathname === '/api/diag/rag/eval-set') {
    json(res, EVAL_SET)
    return true
  }

  if (req.method === 'GET' && pathname === '/api/diag/rag/compare') {
    // 真后端这里要跑几百毫秒到几秒；假后端刻意也睡一下，
    // 好让"正在跑对照评测"那个 loading 状态真的被渲染到一次 ——
    // 否则自检里"点完立刻断言结果"会跳过 loading 分支，那条路径就永远没被验证过
    setTimeout(() => {
      json(res, {
        evalSetLocation: EVAL_SET.location,
        caseCount: 43,
        sampleSufficient: true,
        documents: 4,
        failedDocuments: [],
        strategies: STRATEGIES,
        deltas: DELTAS,
        notes: NOTES,
      })
    }, 400)
    return true
  }

  if (req.method === 'GET' && pathname === '/api/diag/rag/align') {
    // 睡 600ms 而不是瞬时返回：与 `/compare` 同一个理由，让"正在跑对齐实验"那个
    // loading 分支真的被渲染到一次。**但别睡太久** —— 真后端实测 4.95 秒，
    // 这里要是也睡 5 秒，自检脚本每次都要多等 5 秒，而它验证不了"真后端够快"
    // （超时是 180 秒，5 秒和 0.6 秒都在余量之内）。
    setTimeout(() => json(res, ALIGNMENT), 600)
    return true
  }

  return false
}

/**
 * 中文二元组 + 英文按词 —— 与后端 CjkBigramAnalyzer 的行为**对齐到足以验证界面**。
 *
 * 刻意复刻这个规则（而不是简单按空格切）：界面要展示"中文被切成二元组"这件事，
 * 假后端要是按空格切，界面上就会出现一长串完整中文，与后端的真实行为不符 ——
 * 那自检断言"token 被切开了"就失去意义了。
 */
function tokenize(text) {
  const tokens = []
  // 先按"非中日韩字符"切开，得到若干段
  const segments = text.split(/([^\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]+)/)
  for (const seg of segments) {
    if (!seg) continue
    if (/^[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]+$/.test(seg)) {
      if (seg.length === 1) tokens.push(seg)
      else for (let i = 0; i < seg.length - 1; i++) tokens.push(seg.slice(i, i + 2))
    } else {
      for (const word of seg.toLowerCase().split(/[^a-z0-9]+/)) {
        if (word) tokens.push(word)
      }
    }
  }
  return tokens
}
