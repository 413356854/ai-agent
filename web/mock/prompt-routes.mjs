/**
 * Prompt 版本管理端点的**假数据**（阶段四 · 工程化第 3 件）。
 *
 * ---------------------------------------------------------------------------
 * 与 cost-routes / agent-routes 的规矩相同：**数字是假的，形状是真的** ——
 * 但这一组有一处**本质不同，必须先说清**
 * ---------------------------------------------------------------------------
 * 前面几组端点都是**只读**的：假数据写死，自检跑多少遍结果都一样。
 * 而这一组有四个写端点（存 / 生效 / 回滚 / 清空），界面自检要验的恰恰是
 * **"点一下之后状态真的变了"**。所以这里是**有状态的**，而且：
 *
 *   1. 状态放在模块级变量里（进程内），不落盘 —— 假后端的职责是让界面能跑，
 *      不是模拟持久化。**重启 node mock/sse-server.mjs 就回到初始状态**；
 *   2. 既然是状态机，就必须有 **`POST /api/prompts/__reset`**：
 *      自检脚本跑第二遍时不能继承第一遍改出来的版本号，否则
 *      "存一版之后数量 +1" 这种断言第二遍必红 —— 而红的原因与功能无关。
 *      这与 `/api/agent/__reset-approval` 是同一条经验（HITL 那两条相反分支
 *      就是靠它做到确定性的）；
 *   3. 校验逻辑**照抄真后端的判据**（围栏配平 / 结构锚点 / 说明必填 / 篇幅骤变）。
 *      ⚠️ 这一条最容易偷懒，但代价很具体：**假后端不校验的话，
 *      "提交一份坏提示词 → 界面逐条列出判据"这个分支在自检里永远走不到**，
 *      等于没验证。这与"假数据里必须留一个没价格的模型"是同一条纪律。
 *
 * ---------------------------------------------------------------------------
 * 初始数据刻意不是"万事如意"的那一份
 * ---------------------------------------------------------------------------
 *   1. **有两条历史版本**，这样版本表、差异视图、回滚都有东西可看；
 *   2. **第 2 版带 `forkedFrom`**，于是「复制自第 N 版（回滚产生）」这一行会渲染出来
 *      —— 而它是"回滚也是一次新增"这条纪律在界面上的唯一证据；
 *   3. **有一版没有署名**（author 为 null）→ 界面必须显示「（未署名）」而不是空白，
 *      否则"没署名"与"署名的值是空串"在屏幕上长得一样。
 */

/** 初始的 Agent 循环提示词（与真后端 classpath 里那一份同形，但短一些 —— 自检不需要全文）。 */
const AGENT_V1 = `你是「AI Agent 项目」的技术助手。你的知识来自项目自己的文档库，可以调用工具去查。

## 工作方式（think → act → observe）

1. 先判断要不要查。用户问的是项目里的做法、结论、数字 —— 这些必须查，不要凭印象回答。
2. 需要查时，从最省的一步开始：不确定知识库里有什么，先 list_documents。
3. 看到工具结果后要真的读它。结果为空或无关时换关键词，不要用同样的参数再调一次。
4. 拿到足够信息就回答，不要为了"更全面"继续无意义地检索。

## 关于工具的错误反馈

工具失败时你会收到一段说明，它会告诉你哪些字段不合法、以及值不值得重试。

## 回答要求

- 有依据：用工具查到的内容要说明它来自哪份文档。
- 不编造：查不到就说查不到，绝不要用看起来合理的数字填空。
- 简洁：先给结论，再给依据。
`

const CHAT_V1 = `你是「AI Agent 项目」的对话助手。

## 回答要求

- 简洁：先给结论，再给依据。
- 有依据：不确定的内容要说明不确定，不要编造。
- 用中文回答，除非用户用其他语言提问。
`

/** 估算 token：与真后端一样是"字数 / 3"这类粗略口径，够界面显示即可。 */
function estimateTokens(text) {
  return Math.max(1, Math.round(text.length / 2.2))
}

function hashOf(text) {
  // 假指纹：只要"内容相同 → 指纹相同、内容不同 → 指纹不同"就够了。
  // 不实现真 SHA-256 是因为自检**不比对指纹的值**，只用它来确认
  // "页面显示的这一版"与"列表里那一版"是同一个东西
  let h1 = 0x811c9dc5
  let h2 = 0x1000193
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i)
    h1 = Math.imul(h1 ^ c, 0x01000193) >>> 0
    h2 = Math.imul(h2 + c, 0x85ebca6b) >>> 0
  }
  return (h1.toString(16).padStart(8, '0') + h2.toString(16).padStart(8, '0')).slice(0, 12)
}

function version(kind, number, content, note, author, forkedFrom, createdAt) {
  return {
    kind,
    version: number,
    content,
    hash: hashOf(content),
    chars: content.length,
    tokens: estimateTokens(content),
    note,
    author,
    forkedFrom,
    createdAt,
  }
}

/** 每次 reset 都重建的初始状态。 */
function initialState() {
  return {
    'system-agent-tools': [
      // 降序（最新在前）—— 与真后端 versionsOf 的排序一致
      version('system-agent-tools', 2,
        AGENT_V1.replace('## 回答要求', '## 回答要求（第 2 版补充）') + '\n- 引用时给出章节名，而不是文档名。\n',
        '补一条引用要求：给章节名比给文档名更有用', null, 1, '2026-09-16T02:10:00Z'),
      version('system-agent-tools', 1, AGENT_V1,
        '出厂版本（来自 prompts/system-agent-tools.st）—— 这一版由启动时的自动初始化写入，是回滚的最终去处',
        'system', null, '2026-09-16T01:00:00Z'),
    ],
    'system-agent': [
      version('system-agent', 1, CHAT_V1,
        '出厂版本（来自 prompts/system-agent.st）—— 这一版由启动时的自动初始化写入',
        'system', null, '2026-09-16T01:00:00Z'),
    ],
  }
}

let versions = initialState()
let active = { 'system-agent-tools': 2, 'system-agent': 1 }
/** 自增计数：新版本号取"当前最大 + 1"，与真后端一致。 */
let authorCounter = 0

const KIND_META = {
  'system-agent-tools': {
    summary: 'Agent 循环（ReAct）的系统提示词：工作方式、错误反馈的读法、回答要求',
    classpathLocation: 'prompts/system-agent-tools.st',
    readBy: 'Agent 工具调用面板（每次运行开始时读一次）',
  },
  'system-agent': {
    summary: '对话与结构化输出的系统提示词：角色设定与回答风格',
    classpathLocation: 'prompts/system-agent.st',
    readBy: 'SSE 流式对话与结构化输出（每轮请求读一次）',
  },
}

function infoOf(kind) {
  const rows = versions[kind] ?? []
  const activeRow = rows.find(r => r.version === active[kind]) ?? null
  const meta = KIND_META[kind]
  return {
    kind,
    summary: meta.summary,
    classpathLocation: meta.classpathLocation,
    versionCount: rows.length,
    activeVersion: activeRow ? activeRow.version : null,
    activeHash: activeRow ? activeRow.hash : null,
    activeChars: activeRow ? activeRow.chars : 0,
    activeTokens: activeRow ? activeRow.tokens : 0,
    source: activeRow ? 'library' : 'classpath',
    readBy: meta.readBy,
  }
}

function summaryOf(row) {
  return {
    version: row.version,
    hash: row.hash,
    chars: row.chars,
    tokens: row.tokens,
    note: row.note,
    author: row.author,
    createdAt: row.createdAt,
    forkedFrom: row.forkedFrom,
    active: active[row.kind] === row.version,
  }
}

/* ------------------------------------------------------------------ */
/*  校验：判据照抄真后端 PromptValidator                                */
/* ------------------------------------------------------------------ */

const MAX_CHARS = 20000
const MIN_CHARS = 120

function countFences(text) {
  let count = 0
  let idx = text.indexOf('```')
  while (idx >= 0) {
    count++
    idx = text.indexOf('```', idx + 3)
  }
  return count
}

function check(rule, level, detail) {
  return { rule, level, detail }
}

function validate(kind, content, note, force) {
  const text = content ?? ''
  const rows = versions[kind] ?? []
  const previous = rows.find(r => r.version === active[kind]) ?? null
  const checks = []

  if (text.trim() === '') {
    checks.push(check('正文不能为空', 'REJECT',
      '空提示词写进库之后模型会失去角色设定，而回答看起来只是「变笨了」—— 这种坏法没有报错'))
  }
  else if (text.length > MAX_CHARS) {
    checks.push(check('正文长度上限', 'REJECT',
      `当前 ${text.length} 字符，上限 ${MAX_CHARS} —— 这么长通常是粘贴事故（粘了两遍、或粘进了无关内容）`))
  }
  else {
    checks.push(check('正文长度上限', 'OK', `${text.length} 字符（上限 ${MAX_CHARS}）`))
  }

  const fences = countFences(text)
  if (text.trim() !== '' && fences % 2 !== 0) {
    checks.push(check('代码围栏配平', 'REJECT',
      `检测到 ${fences} 个 \`\`\` 围栏（奇数）—— 多半是粘贴时被截断了；残缺的提示词不会报错，只会让模型看到半段说明`))
  }
  else {
    checks.push(check('代码围栏配平', 'OK', `${fences} 个围栏（配平）`))
  }

  if (text.trim() !== '') {
    const missing = []
    if (kind === 'system-agent-tools') {
      if (!text.includes('工作方式')) missing.push('「工作方式」这一段（它定义了 think → act → observe）')
      if (!text.includes('工具')) missing.push('对「工具」的说明（没有它模型不知道可以调用工具）')
      if (!text.includes('编造') && !text.includes('查不到')) {
        missing.push('「不编造」那一条（少了它，查不到时模型会用看起来合理的数字填空）')
      }
    }
    else if (!text.includes('你是')) {
      missing.push('角色设定（「你是…」这类开头）')
    }
    checks.push(missing.length === 0
      ? check('关键段落还在', 'OK', '结构锚点齐全')
      : check('关键段落还在', 'REJECT',
        `缺少 ${missing.join('、')} —— 这类残缺的正文是有效的、也能通过前面几条，所以只能在这里拦`))
  }

  const stripped = (note ?? '').trim()
  if (stripped.length === 0) {
    checks.push(check('改动说明必填', 'REJECT',
      '没有说明的历史等于没有历史：回滚时不知道该回哪一版，也说不清当时为什么这么改'))
  }
  else if (stripped.length < 4) {
    checks.push(check('改动说明必填', 'REJECT', `说明只有 ${stripped.length} 个字，读的人看不出这次改了什么`))
  }
  else {
    checks.push(check('改动说明必填', 'OK', `已给出（${stripped.length} 字）`))
  }

  if (text.trim() !== '') {
    if (text.length < MIN_CHARS && !force) {
      checks.push(check('篇幅没有低于下限', 'WARN',
        `只有 ${text.length} 字符，远小于一份完整系统提示词的常见篇幅（下限参考 ${MIN_CHARS}）。确实要这么短就带上『确认放行』再存一次`))
    }
    else {
      checks.push(check('篇幅没有低于下限', 'OK', `${text.length} 字符（下限参考 ${MIN_CHARS}）`))
    }

    if (previous && previous.chars > 0) {
      const ratio = text.length / previous.chars
      if (ratio < 0.5) {
        checks.push(check('篇幅相对上一版没有骤变', 'WARN',
          `只有上一版的 ${Math.round(ratio * 100)}%（${previous.chars} → ${text.length} 字符）—— 确认这是有意的删减，而不是粘贴残缺`))
      }
      else if (ratio > 2) {
        checks.push(check('篇幅相对上一版没有骤变', 'WARN',
          `是上一版的 ${ratio.toFixed(1)} 倍（${previous.chars} → ${text.length} 字符）—— 这会让 prompt token 明显上涨，而它的副作用是『历史比以前更早被裁掉』`))
      }
      else {
        checks.push(check('篇幅相对上一版没有骤变', 'OK',
          `上一版的 ${Math.round(ratio * 100)}%（${previous.chars} → ${text.length} 字符）`))
      }
    }
    else {
      checks.push(check('篇幅相对上一版没有骤变', 'OK', '首次写入，无可比较的上一版'))
    }
  }

  return { checks, rejected: checks.some(c => c.level === 'REJECT') }
}

/* ------------------------------------------------------------------ */
/*  差异：与真后端同一套（LCS + 就近折叠）                              */
/* ------------------------------------------------------------------ */

function diffOf(fromRow, toRow) {
  const a = fromRow.content.split('\n')
  const b = toRow.content.split('\n')
  const identical = fromRow.content === toRow.content
  if (identical) {
    return {
      from: fromRow.version, to: toRow.version, identical: true, added: 0, removed: 0,
      tokenDelta: toRow.tokens - fromRow.tokens,
      fromTokenEstimate: fromRow.tokens, toTokenEstimate: toRow.tokens,
      lines: [],
      note: '差异由后端按最长公共子序列计算：改了中间一行不会让它之后的所有行都显示成『已修改』',
    }
  }

  // LCS 表
  const table = Array.from({ length: a.length + 1 }, () => new Array(b.length + 1).fill(0))
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      table[i][j] = a[i] === b[j] ? table[i + 1][j + 1] + 1 : Math.max(table[i + 1][j], table[i][j + 1])
    }
  }

  const raw = []
  let added = 0
  let removed = 0
  let i = 0
  let j = 0
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      raw.push({ op: 'SAME', fromLine: i + 1, toLine: j + 1, text: b[j] })
      i++
      j++
    }
    else if (table[i + 1][j] >= table[i][j + 1]) {
      raw.push({ op: 'REMOVE', fromLine: i + 1, toLine: null, text: a[i] })
      removed++
      i++
    }
    else {
      raw.push({ op: 'ADD', fromLine: null, toLine: j + 1, text: b[j] })
      added++
      j++
    }
  }
  while (i < a.length) {
    raw.push({ op: 'REMOVE', fromLine: i + 1, toLine: null, text: a[i] })
    removed++
    i++
  }
  while (j < b.length) {
    raw.push({ op: 'ADD', fromLine: null, toLine: j + 1, text: b[j] })
    added++
    j++
  }

  // 折叠长的未变更段（开头/结尾与短段不折叠 —— 与真后端同一套规则）
  const CONTEXT = 2
  const lines = []
  let index = 0
  while (index < raw.length) {
    if (raw[index].op !== 'SAME') {
      lines.push(raw[index])
      index++
      continue
    }
    const start = index
    while (index < raw.length && raw[index].op === 'SAME') index++
    const runLength = index - start
    if (start === 0 || index === raw.length || runLength <= CONTEXT * 2 + 1) {
      lines.push(...raw.slice(start, index))
      continue
    }
    lines.push(...raw.slice(start, start + CONTEXT))
    lines.push({ op: 'GAP', fromLine: null, toLine: null, text: `……省略 ${runLength - CONTEXT * 2} 行未变更的内容……` })
    lines.push(...raw.slice(index - CONTEXT, index))
  }

  return {
    from: fromRow.version, to: toRow.version, identical: false, added, removed,
    tokenDelta: toRow.tokens - fromRow.tokens,
    fromTokenEstimate: fromRow.tokens, toTokenEstimate: toRow.tokens,
    lines,
    note: '差异由后端按最长公共子序列计算：改了中间一行不会让它之后的所有行都显示成『已修改』',
  }
}

/* ------------------------------------------------------------------ */
/*  路由                                                                */
/* ------------------------------------------------------------------ */

function readBody(req) {
  return new Promise(resolve => {
    let body = ''
    req.on('data', chunk => {
      body += chunk
    })
    req.on('end', () => {
      try {
        resolve(JSON.parse(body || '{}'))
      }
      catch {
        resolve({})
      }
    })
  })
}

export function handlePromptRoutes(req, res, url, json) {
  const pathname = url.pathname

  // ---- 自检用的复位端点 ----
  // 状态机必须有它：不然自检脚本跑第二遍时会继承第一遍改出来的版本号，
  // 于是"存一版之后数量 +1"这类断言第二遍必红，而红的原因与功能无关
  if (req.method === 'POST' && pathname === '/api/prompts/__reset') {
    versions = initialState()
    active = { 'system-agent-tools': 2, 'system-agent': 1 }
    authorCounter = 0
    json(res, { ok: true, reset: true, prompts: Object.keys(versions) })
    return true
  }

  // ---- 只读 ----
  if (req.method === 'GET' && pathname === '/api/prompts') {
    setTimeout(() => json(res, {
      prompts: Object.keys(versions).map(infoOf),
      hotReload: {
        takesEffect: [
          '下一次 Agent 运行（每次运行开始时读一次系统提示词）',
          '下一轮对话与结构化输出（每轮请求重新注入系统提示词）',
        ],
        doesNotAffect: [
          '已经在跑的那次运行 —— 它的系统消息在运行开始时就已经进了消息列表。这是刻意的：中途换提示词会让同一份对话里前后两轮遵循不同指令，于是『它为什么这么选』变得无法解释',
          '已经算出去的那次 token 预算 —— 预算随正文长度变，所以它是每次重新问的，不是启动时算的',
        ],
      },
      notes: [
        '历史只追加：改一版等于新增一版，回滚也等于新增一版 —— 所以任何一次改动都能回答「是谁、什么时候、为什么」',
        '存与生效是两次决定：保存后的新版本默认不生效（一次粘贴失误不会直接作用到线上）',
        '提示词改坏了不会报错，只会让答案质量下降 —— 所以校验放在写入之前，而不是等跑起来',
        '这一页的改动对已经跑过的历史没有影响：token 数字与金额都留在各自的表里，不会被追改',
      ],
    }), 150)
    return true
  }

  const versionsMatch = pathname.match(/^\/api\/prompts\/([^/]+)\/versions$/)
  if (req.method === 'GET' && versionsMatch) {
    const kind = versionsMatch[1]
    if (!versions[kind]) {
      json(res, { error: 'not-found', message: `没有名为「${kind}」的提示词；可用的是 ['system-agent-tools', 'system-agent']` }, 404)
      return true
    }
    setTimeout(() => json(res, {
      prompt: infoOf(kind),
      versions: versions[kind].map(summaryOf),
      count: versions[kind].length,
    }), 120)
    return true
  }

  const detailMatch = pathname.match(/^\/api\/prompts\/([^/]+)\/versions\/(\d+)$/)
  if (req.method === 'GET' && detailMatch) {
    const kind = detailMatch[1]
    const number = Number(detailMatch[2])
    const row = (versions[kind] ?? []).find(r => r.version === number)
    if (!row) {
      json(res, { error: 'not-found', message: `「${kind}」没有第 ${number} 版` }, 404)
      return true
    }
    json(res, {
      prompt: infoOf(kind),
      version: { ...summaryOf(row), content: row.content, lines: row.content.split('\n').length },
    })
    return true
  }

  const diffMatch = pathname.match(/^\/api\/prompts\/([^/]+)\/diff$/)
  if (req.method === 'GET' && diffMatch) {
    const kind = diffMatch[1]
    const rows = versions[kind] ?? []
    if (rows.length === 0) {
      json(res, { error: 'not-found', message: `「${kind}」还没有任何版本` }, 404)
      return true
    }
    const to = Number(url.searchParams.get('to') ?? rows[0].version)
    const fromParam = url.searchParams.get('from')
    let from
    if (fromParam != null) {
      from = Number(fromParam)
    }
    else {
      // 与真后端同一条规则：省略 from 时取"比 to 小的第一个版本"，没有就更早那个
      const smaller = rows.filter(r => r.version < to)
      from = smaller.length > 0 ? smaller[0].version : rows[rows.length - 1].version
    }
    const fromRow = rows.find(r => r.version === from)
    const toRow = rows.find(r => r.version === to)
    if (!fromRow || !toRow) {
      json(res, { error: 'not-found', message: `版本不存在 from=${from} to=${to}` }, 404)
      return true
    }
    json(res, diffOf(fromRow, toRow))
    return true
  }

  // ---- 写（真状态机）----

  const createMatch = pathname.match(/^\/api\/prompts\/([^/]+)\/versions$/)
  if (req.method === 'POST' && createMatch) {
    const kind = createMatch[1]
    if (!versions[kind]) {
      json(res, { error: 'not-found', message: `没有名为「${kind}」的提示词` }, 404)
      return true
    }
    void readBody(req).then(body => {
      const content = body.content ?? ''
      const note = body.note ?? ''
      const force = body.force === true
      const result = validate(kind, content, note, force)
      if (result.rejected) {
        const reason = result.checks.filter(c => c.level === 'REJECT')
          .map(c => `${c.rule}：${c.detail}`).join('；')
        // 400 + checks：界面靠 checks 逐条列出"哪一条没过、具体数字是多少"
        json(res, { error: 'rejected', message: `提示词没有写入：${reason}`, checks: result.checks }, 400)
        return
      }
      const next = Math.max(0, ...versions[kind].map(r => r.version)) + 1
      authorCounter++
      const row = version(kind, next, content, note, body.author ?? `web-ui-${authorCounter}`,
        null, new Date().toISOString())
      versions[kind] = [row, ...versions[kind]]
      json(res, {
        prompt: infoOf(kind),
        version: { ...summaryOf(row), content: row.content, lines: row.content.split('\n').length },
        validation: { checks: result.checks, warnings: result.checks.filter(c => c.level === 'WARN').length },
        note: `已存为第 ${next} 版，但尚未生效 —— 存与生效是两次决定：先看一眼篇幅变化，再决定要不要让它生效`,
      }, 201)
    })
    return true
  }

  const activateMatch = pathname.match(/^\/api\/prompts\/([^/]+)\/activate$/)
  if (req.method === 'POST' && activateMatch) {
    const kind = activateMatch[1]
    if (!versions[kind]) {
      json(res, { error: 'not-found', message: `没有名为「${kind}」的提示词` }, 404)
      return true
    }
    void readBody(req).then(body => {
      const target = (versions[kind] ?? []).find(r => r.version === body.version)
      if (!target) {
        json(res, { error: 'not-found', message: `没有第 ${body.version} 版` }, 404)
        return
      }
      const expected = body.expectedVersion
      // 与真后端同一套并发语义：前提过期就 409，**不静默覆盖**
      if (expected != null && active[kind] != null && active[kind] !== expected) {
        json(res, {
          error: 'conflict',
          message: `当前生效的已经是第 ${active[kind]} 版（你以为的是第 ${expected} 版），这次切换没有执行 —— 刷新后重新决定`,
          expectedVersion: expected,
          actualVersion: active[kind],
        }, 409)
        return
      }
      active[kind] = body.version
      json(res, {
        prompt: infoOf(kind),
        activeVersion: target.version,
        activeHash: target.hash,
        activeTokens: target.tokens,
        author: body.author ?? null,
        action: 'activate',
        message: '切换生效',
      })
    })
    return true
  }

  const rollbackMatch = pathname.match(/^\/api\/prompts\/([^/]+)\/rollback$/)
  if (req.method === 'POST' && rollbackMatch) {
    const kind = rollbackMatch[1]
    if (!versions[kind]) {
      json(res, { error: 'not-found', message: `没有名为「${kind}」的提示词` }, 404)
      return true
    }
    void readBody(req).then(body => {
      const target = (versions[kind] ?? []).find(r => r.version === body.version)
      if (!target) {
        json(res, { error: 'not-found', message: `没有第 ${body.version} 版` }, 404)
        return
      }
      const currentRow = (versions[kind] ?? []).find(r => r.version === active[kind])
      const noOp = currentRow != null && currentRow.content === target.content
      if (noOp) {
        // 内容已相同 → 不新增版本，但**要说清没产生副作用**
        json(res, {
          prompt: infoOf(kind),
          activeVersion: currentRow.version,
          activeHash: currentRow.hash,
          activeTokens: currentRow.tokens,
          author: body.author ?? null,
          action: 'rollback',
          message: `回滚（当前内容已与第 ${body.version} 版一致，未新增版本）`,
          noOp: true,
          createdVersion: null,
          targetVersion: body.version,
          note: `当前生效版本的内容与第 ${body.version} 版完全相同，所以这次没有新增版本 —— 无意义的复制会让历史里多出一条解释不了的记录`,
        })
        return
      }
      const next = Math.max(0, ...versions[kind].map(r => r.version)) + 1
      const row = version(kind, next, target.content,
        body.note ?? `回滚到第 ${body.version} 版（由系统补写的说明）`, body.author ?? 'web-ui',
        target.version, new Date().toISOString())
      versions[kind] = [row, ...versions[kind]]
      active[kind] = next
      json(res, {
        prompt: infoOf(kind),
        activeVersion: row.version,
        activeHash: row.hash,
        activeTokens: row.tokens,
        author: body.author ?? null,
        action: 'rollback',
        message: `回滚：第 ${body.version} 版的内容被复制为新的一版（v${row.version}）并生效`,
        noOp: false,
        createdVersion: row.version,
        targetVersion: body.version,
        note: '回滚也是一次新增（不是把指针挪回去）：这样历史里留着「是谁、什么时候、把哪一版拉回来的」，而指针式回滚会让这段历史消失',
      })
    })
    return true
  }

  return false
}
