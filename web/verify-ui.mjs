/**
 * 界面自检脚本：用浏览器真跑一遍，确认两种模式都能用。
 *
 * 用法（两个终端）：
 *   终端 A：pnpm mock            # 假后端，不烧 token（或先起真后端）
 *   终端 B：pnpm check:ui        # 默认打 http://127.0.0.1:5173
 *
 * 为什么要有这个脚本而不是"我自己点一遍就算验证"：
 *   1. 点一遍只能证明"我这次看到的对"，证明不了"下次改动后还是对的"；
 *   2. 有几个问题肉眼看不出来 —— 横向溢出、Ant Design 的 deprecation 警告、
 *      控制台报错，这些都要靠断言兜住；
 *   3. 当前模型看不了截图，所以这里刻意用**几何断言**代替"肉眼看布局"：
 *      documentElement.scrollWidth - clientWidth > 0 就说明有横向溢出。
 *
 * 注意：默认用系统已装的 Chrome（channel: 'chrome'），而不是 Playwright 自带的 chromium ——
 * 本机 ms-playwright 里的内核版本与当前 playwright 不匹配，直接 launch 会报
 * "Executable doesn't exist"。走系统浏览器可以绕开"为了截一张图下载 100MB 内核"。
 */
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'

const URL = process.env.UI_URL ?? 'http://127.0.0.1:5173'
/**
 * 是否断言"回灌 / 降级"这两条路径。
 *
 * **默认 true，因为默认该打假后端**（`pnpm mock`）：假后端里的两种结果是写死的，
 * 断言才有确定性。打真后端时会话未必回灌（模型一次就答对）也不会因为提问里带「降级」
 * 二字而降级 —— 那是假后端的约定，真实模型没这个约定。拿真后端跑这两条断言必然误报。
 *
 * 所以：`CHECK_EXPECT_REFLECTION=0 UI_URL=http://127.0.0.1:5199 pnpm check:ui` 打真后端。
 */
const EXPECT_REFLECTION = process.env.CHECK_EXPECT_REFLECTION !== '0'
/**
 * 每条路径实际执行多少项断言（**实测出来的，不是数出来的**）。
 *
 * 脚本里一共 518 个 `expect(...)`，但两条路径执行的数量不同 ——
 * 因为有几个分支各跑各的（回灌细节 / 降级标记 / Agent 的完整轨迹 / HITL 的批准与拒绝），
 * 它们的可复现性依赖假后端：
 *
 *   假后端（默认路径，实测 2026-09-22）→ **472 项，全过**（同一份脚本连跑两遍，两遍都是 472）
 *   真后端（`CHECK_EXPECT_REFLECTION=0`）→ 见下面 README 那一行；本轮跑过，数字写在 web/README.md
 *
 * 真后端路径上失败的是**流式回答**（`流式回答已渲染`）：
 * 本机用的是假 key，上游必然 401，与改动无关。
 *
 * ⚠️ **一个会随环境变化的数字，必须把每个环境的取值都写清**，并在每次重测后更新 ——
 * 否则读的人没法判断某次输出是不是正常。
 *
 * ⚠️ 还有一个更隐蔽的坑（第九轮踩到，值得单独记）：
 * **"真后端路径"可能在骗你。** `vite.config.ts` 里 `strictPort: true`，
 * 所以如果之前那个 `pnpm dev:mock` 还占着 5173，新起的 `pnpm dev`（指向真后端）
 * **会启动失败**，而 `UI_URL` 仍然打得到 5173 —— 于是你以为是"真后端路径"，
 * 实际打的是假后端，两次跑的断言数也会因此对不上。
 * 判断方法很简单（也在 README 里写了）：
 *     GET /api/agent/runs → `awaitingRecovery`
 *     假后端固定给 1，真后端是 0。**一个数字就能区分你在打谁。**
 *
 * ⚠️ 同一族的第三种形态（2026-09-20 踩到）：**假后端端口上残留的旧进程**。
 * 假后端本身不报错，它只是"上一轮的代码 + 上一轮的状态"，于是断言会红在很怪的地方 ——
 * 实测症状是"流式回答只渲染出 8 个字符""结构化没有回灌""钉住实验 90 秒等不到结果"。
 * 所以怀疑自检本身有问题时，**先确认你打的那个端口上是谁**：
 *     假后端 → `GET /api/agent/runs` 的 `awaitingRecovery` 恒为 1（旧进程与真后端都是 0）
 *     更彻底：先确认端口没人监听（`Get-NetTCPConnection -State Listen -LocalPort 8081`）。
 */
const SHOT_DIR = 'ui-check-out'
mkdirSync(SHOT_DIR, { recursive: true })

/**
 * 用哪个浏览器内核。
 *
 * <h2>为什么默认是系统 Chrome，而不是 Playwright 自带的 chromium</h2>
 * 本机踩过：`ms-playwright` 里的内核版本与当前 playwright 不匹配，
 * 直接 `launch()` 会报 `Executable doesn't exist` —— 那是环境问题，
 * 却看起来像"界面自检跑不起来"。用系统已装的 Chrome 省掉了
 * "为了截一张图下载 100MB 内核"这件事。
 *
 * <h2>为什么它必须是可配的（阶段四第 4 件）</h2>
 * 因为 `chrome` 这个默认值**只在开发者本机成立**：干净 CI 容器里没有 Chrome，
 * 有的是 `npx playwright install chromium` 装下来的那个内核。
 * 写死 `chrome` 的后果很具体：**门禁在 CI 上永远红，且红的原因与代码无关** ——
 * 而那正是让人开始习惯性忽略门禁的第一步（不是删掉它，而是不再看它）。
 *
 * <p>用法：CI 里设 `CHECK_BROWSER_CHANNEL=chromium` 并先跑
 * `npx playwright install --with-deps chromium`；本机什么都不用设。
 */
const BROWSER_CHANNEL = process.env.CHECK_BROWSER_CHANNEL ?? 'chrome'
// 传 `undefined` 与传 `'chromium'` 不是一回事：前者让 Playwright 用自带的默认内核，
// 后者是一个**名为 chromium 的频道**。这里用条件展开，避免把两者混起来
const browser = await chromium.launch(
  BROWSER_CHANNEL === 'chromium' ? {} : { channel: BROWSER_CHANNEL },
)
const page = await browser.newPage({ viewport: { width: 1100, height: 900 } })

const consoleErrors = []
/**
 * 已知会发生的浏览器网络报错 —— **不算缺陷**，但必须显式列出来。
 *
 * <h2>为什么需要这个函数，而不是"放宽断言"</h2>
 * 这一轮 Prompt 版本面板有一条自检要**故意提交一份坏提示词**，验证界面逐条列出判据。
 * 那次请求后端会正确地回 **400**，而浏览器控制台对任何非 2xx 的响应都会记一条
 * `Failed to load resource: ... 400 (Bad Request)` —— **这是浏览器行为，不是我们的 bug**。
 * 权限面板同理：自检会故意制造一次**同名账号（409）**，验证界面把它说成"名字已被占用"。
 * agent 任务那一页也会造一次：先由脚本直接答掉一条确认项（模拟"另一个人"），
 * 再让界面对着**已经变了**的那条任务点一次答复 —— 那次后端正确地回 **409**，
 * 要验的正是"它被界面说成状态变了，而不是你填错了"。
 *
 * <p>所以这里不是把"控制台必须干净"这个要求降低，而是把它**写得准确**：
 * 「意料之内的失败请求」与「意料之外的报错」是两件事，前者必须被逐条命名出来
 * （命名就意味着它是有意为之，而不是被人顺手忽略掉了）。
 * 一个什么都拦的过滤器会让这条断言变成永远为真，那才是真的损失。
 *
 * <p>⚠️ 判据刻意写得很窄：只放行 **400 与 409**，并且要求它确实打在那两个端点上。
 * 500、404、或任何别的路径上的 4xx 都仍然会让自检失败 —— 那些都不是"我们故意造的"。
 * 409 那一档**还要窄一层**：只有被记进 `expectedConflicts`（自检自己刚做过的那次重名操作）
 * 的 URL 才算数，免得"任何 409 都被放行"。
 *
 * <p>⚠️ 实测还有一个细节：浏览器那条日志里**不一定带 URL**
 * （Chrome 只给 "Failed to load resource: the server responded with a status of 400"），
 * 所以这里同时用**请求事件**做判据 —— 只有真的看到"那个端点回了 4xx"才放行。
 * 第一版只匹配文本，于是它一条都没过滤掉（断言照样红）。
 */
const expectedRejections = []
const expectedConflicts = []
page.on('response', response => {
  const url = response.url()
  const fromOurEndpoints = url.includes('/api/prompts/') || url.includes('/api/permissions/')
    || url.includes('/api/agent-tasks/')
  if (!fromOurEndpoints) return
  if (response.status() === 400) expectedRejections.push(url)
  if (response.status() === 409) expectedConflicts.push(url)
})

const isExpectedNetworkError = text => {
  if (/status of 400/.test(text) && expectedRejections.length > 0) return true
  return /status of 409/.test(text) && expectedConflicts.length > 0
}

page.on('console', msg => {
  const text = msg.text()
  if (msg.type() !== 'error' && msg.type() !== 'warning') return
  if (isExpectedNetworkError(text)) return
  consoleErrors.push(`[${msg.type()}] ${text}`)
})
page.on('pageerror', err => consoleErrors.push(`[pageerror] ${err.message}`))

const problems = []
/**
 * 已执行的断言条数。**由脚本自己报数**，而不是让人去数 `expect(` ——
 * 文档里那个"442 项"就是数出来的，而数它要同时判断每个分支走没走到
 * （README 里为此写过一条"不要凭印象写"）—— 那种手工账迟早对不上。
 */
let executed = 0
const expect = (label, ok, detail = '') => {
  executed++
  console.log(`    ${ok ? '✓' : '✗'} ${label}${detail ? ` — ${detail}` : ''}`)
  if (!ok) problems.push(label)
}

/**
 * 轮询等待某个条件成立（最多 `timeoutMs`），**返回条件成立时的那个值**。
 *
 * <h2>为什么需要它，而不是 `page.waitForTimeout(N)` 一把等够</h2>
 * 这一轮新增的断言里有一大半要等的是**异步状态转移**：
 * "点批准 → 界面轮询把那条待批捡走 → 列表里它消失"。
 * 用固定 sleep 的话只能两种结果：睡太短会闪断（CI/慢机器上必红），
 * 睡太长会让整条自检慢下来 —— 而**闪断的断言最后总会被注释掉**，
 * 那时它保护的东西就悄悄没了。
 *
 * <p>轮询的代价是失败时慢（等满超时才报错），收益是**快的时候立刻继续**且不闪断。
 * 这个取舍与后端那边"等待批登记出来"用的是同一套（见 `AgentApprovalTest.awaitPending`）。
 *
 * <h2>★ 返回的是**条件成立时的那个值**，不是 `true`</h2>
 * 谓词写成 `async () => { const r = await …; return r.total === n ? r : false }` 时，
 * 调用方拿到的是 `r` —— 于是"等它变成 3 条"之后紧接着就能读那 3 条，不必再查一次。
 * 谓词返回布尔（`count() === 1` 这类）时拿到的就是 `true`，与以前完全一样。
 * ⚠️ 超时返回 `false`：调用方判它就行；**把它当对象读会在下一行抛 TypeError**，
 * 而那时的报错位置离真正的失败很远。
 */
async function waitFor(check, timeoutMs = 10000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      const value = await check()
      if (value) return value
    }
    catch {
      // 元素还没出现时 locator 会抛/返回 0 —— 都当作"还没好"，继续等
    }
    await page.waitForTimeout(250)
  }
  return false
}

// antd 的 autoSize TextArea 会额外渲染一个隐藏 textarea 用来量高度，必须排除它
const box = () => page.locator('textarea:not([name="hiddenTextarea"])')

/**
 * 切模式 —— 一律走**左侧目录**里那一项。
 *
 * <h2>为什么切模式这件事要单独一个函数</h2>
 * 目录项与页头里那些按钮的标签会撞名（「新会话」「刷新」这类），
 * 而下面每一节开头都要切一次模式（共 16 处）。散着写，改一次布局就要改 16 个选择器，
 * 而漏改的那一处**不会报错** —— 它会点中另一个元素（或超时等 30 秒），
 * 症状看起来像"那个面板坏了"。收在一个函数里，布局再变也只需要改这里。
 *
 * <h2>为什么先读一次收起态，而不是直接点</h2>
 * 收起态（或窄窗口）下目录里没有文字，`hasText` 会一个都匹配不到，
 * 而失败信息是"等不到那个按钮"——看起来像目录里没有这一项。
 *
 * <h2>为什么还要先把分组展平</h2>
 * 分组折叠（点一级目录名收起那一段）也是存在 localStorage 里的用户偏好，
 * 会跨节保留：前面某一节若把这一段收起来过，它下面的项**根本不在 DOM 里**，
 * 症状与上面那条一模一样。所以切模式前一律先展开所有被折叠的分组。
 */
async function navMode(label) {
  const sidebar = page.locator('.app-sidebar')
  if ((await sidebar.getAttribute('data-collapsed')) === '1') {
    await page.locator('.app-collapse').click()
  }
  // 上限 2 次（分组只有两段；写死上限是为了"点了没反应"时不会变成死循环，
  // 那种情况该以"点不到那一项"的形式报出来，而不是把自检挂在这里）
  const folded = sidebar.locator('.app-nav-group-title[aria-expanded="false"]')
  for (let i = 0; i < 2 && (await folded.count()) > 0; i++) {
    await folded.first().click()
  }
  await sidebar.getByRole('button', { name: label, exact: true }).click()
}

/**
 * 读一次响应的"理由那一句话"：<b>先读原文，再试着解析</b>。
 *
 * <p>后端两种错误体形状：`{message}`（`ErrorResponseAdvice` 的 400/500）与
 * `{error}`（控制器自己给的 409）。两种都读 —— 但更关键的是**读不出来时不要变成空串**：
 * 只写 `await response.json()` 的话，响应体一旦不是 JSON，拿到的是一个空对象，
 * 而断言的失败信息里就只剩一个 `HTTP 400 · `。那时看起来像"后端没给理由"，
 * 实际上只是这一次读取没读到 —— 实测为此白查过一轮。
 */
async function readReason(response) {
  const raw = await response.text()
  try {
    const parsed = JSON.parse(raw)
    return String(parsed.message ?? parsed.error ?? raw)
  }
  catch {
    return raw
  }
}

console.log('[1] 打开页面:', URL)
await page.goto(URL, { waitUntil: 'networkidle' })

// ---------- 布局：左侧目录 / 页头 / 内容 ----------
//
// 这一节验的是"类后台那个壳"本身，与任何面板的功能无关：
// 目录两段都在、当前项被标出来、页头标题跟着区走、目录能收能展、页面不横向溢出。
// 【为什么先做这一节】下面每一节切模式都靠目录项，所以"目录能不能点"是它们的前提；
// 它坏了的话，后面 300 多项会以"某某面板没渲染"的形式红，而根因在这一层。
console.log('[1.5] 布局 —— 左侧目录与页头')
{
  const sidebar = page.locator('.app-sidebar')
  const groups = await sidebar.locator('.app-nav-group-title').allInnerTexts()
  expect('目录有两段：对话台 + 应用管理',
    groups.length === 2 && /对话台/.test(groups[0]) && /应用管理/.test(groups[1]),
    groups.join(' / '))
  expect('对话台那段列出八个模式',
    (await sidebar.locator('.app-nav-group').first().locator('.app-nav-item').count()) === 8)
  // 应用管理那一段的**顺序**也是要求的一部分：账号在前、角色在后、任务最后
  const appsItems = (await sidebar.locator('.app-nav-group').nth(1).locator('.app-nav-item').allInnerTexts())
    .map(t => t.trim())
  expect('应用管理那段列出四项，顺序是 用户管理 / 权限管理 / agent任务 / skill管理',
    JSON.stringify(appsItems) === JSON.stringify(['用户管理', '权限管理', 'agent任务', 'skill管理']),
    appsItems.join(' / '))
  expect('当前项被标出来（aria-current）', (await sidebar.locator('[aria-current="page"]').count()) === 1)
  // 默认落在流式模式：这条同时证明"选中的那一项就是当前视图"，而不只是"有一个项被标了"
  expect('默认进的是 SSE 流式',
    (await sidebar.locator('.app-nav-item.is-active').innerText()).trim() === 'SSE 流式')
  expect('页头标题是所属区的名字',
    /AI Agent 对话台/.test(await page.locator('.app-topbar .app-title').innerText()))
  expect('页头有分区标签', (await page.locator('.app-topbar .app-section-tag').count()) === 1)

  const startCollapsed = await sidebar.getAttribute('data-collapsed')
  // ⚠️ 这里**不能**硬断言"默认是展开的"：收起态是存在 localStorage 里的用户偏好，
  // 上一轮自检可能把它收起来过。所以下面按"当前是什么态"各自断言，并保证结尾恢复原态
  expect('目录的收起态有一个可读的开关值',
    startCollapsed === '0' || startCollapsed === '1', `data-collapsed=${startCollapsed}`)
  const collapseBtn = page.locator('.app-collapse')
  expect('收起开关可见（它是目录从窄条变回来的唯一入口）', await collapseBtn.isVisible())

  await collapseBtn.click()
  const nextState = startCollapsed === '0' ? '1' : '0'
  // ⚠️ 点完**要等状态渲进 DOM 再读**：`click()` 返回时 React 的这次更新未必已提交，
  // 而"读早了"的症状是"点了没反应"——看起来像功能坏了，其实是断言抢跑。
  const toggled = await waitFor(
    async () => (await sidebar.getAttribute('data-collapsed')) === nextState, 3000)
  expect('★ 点一下收起开关，收起态真的翻转了', toggled,
    `${startCollapsed} → ${await sidebar.getAttribute('data-collapsed')}`)
  // 收起之后当前项仍然能被读出来：靠的是那条左侧色标 + aria-current，
  // 而不是文字（文字这时是藏起来的）—— 所以这一条与上面那条不是重复的
  expect('收起之后仍然看得出当前在哪一项',
    (await sidebar.locator('[aria-current="page"]').count()) === 1)
  await collapseBtn.click()
  // 宽度是**过渡**出来的（CSS transition），所以这里等的不是状态而是几何：
  // 状态已经回到 0 而宽度还停在 64px 的那一瞬间是存在的，读早了就会得到
  // "目录没占宽度"这个与事实相反的结论
  const expandedWidth = await waitFor(async () => await page.evaluate(() => {
    const sb = document.querySelector('.app-sidebar')
    return sb ? Math.round(sb.getBoundingClientRect().width) > 150 : false
  }), 3000)
  expect('再点一下回到展开态（宽度也回来了）',
    (await sidebar.getAttribute('data-collapsed')) === startCollapsed && expandedWidth)

  // ---- 分组折叠：点一级目录名，收起/展开它下面那一段 ----
  //
  // 它与上面那条"整条目录收起"是两件事：那条是横向省宽度，这条是纵向省高度。
  // 这里验三样：标题确实是个开关、收起真的把项拿走了、收起后**仍然读得出当前在哪**
  // （最后一条与"收起之后仍然看得出当前在哪一项"是同一个性质的要求）。
  const group1 = sidebar.locator('.app-nav-group').first()
  const groupTitle = group1.locator('.app-nav-group-title')
  // ⚠️ 折叠态同样是存在 localStorage 里的用户偏好（与整条目录的收起态一个道理），
  // 所以不能硬断言"默认是展开的"：先归到展开态再验
  if ((await groupTitle.getAttribute('aria-expanded')) === 'false') {
    await groupTitle.click()
  }
  expect('分组名是一个可折叠的开关（aria-expanded 可读）',
    (await groupTitle.getAttribute('aria-expanded')) === 'true',
    `aria-expanded=${await groupTitle.getAttribute('aria-expanded')}`)

  const itemsBefore = await group1.locator('.app-nav-item').count()
  await groupTitle.click()
  const foldedGroup = await waitFor(
    async () => (await groupTitle.getAttribute('aria-expanded')) === 'false', 3000)
  expect('★ 点一下分组名，这一段收起来了', foldedGroup
    && (await group1.locator('.app-nav-item').count()) < itemsBefore,
    `${itemsBefore} → ${await group1.locator('.app-nav-item').count()} 项`)
  // 收起的是**当前视图所在的那一段**（此刻是默认的 SSE 流式，属于对话台）：
  // 项都不在 DOM 里了，位置的唯一线索就是标题上那枚标记 —— 所以这一条必须成立
  expect('收起后标题仍然标出「当前视图在这一段里」',
    (await groupTitle.getAttribute('data-active-inside')) === '1',
    `data-active-inside=${await groupTitle.getAttribute('data-active-inside')}`)
  // 只收起被点的那一段：应用管理那四项不该跟着消失
  expect('另一段不受影响',
    (await sidebar.locator('.app-nav-group').nth(1).locator('.app-nav-item').count()) === 4)

  await groupTitle.click()
  const unfoldedGroup = await waitFor(
    async () => (await groupTitle.getAttribute('aria-expanded')) === 'true', 3000)
  expect('再点一下展开回来（项也回来了）', unfoldedGroup
    && (await group1.locator('.app-nav-item').count()) === itemsBefore,
    `aria-expanded=${await groupTitle.getAttribute('aria-expanded')}`)

  const layout1 = await page.evaluate(() => {
    const doc = document.documentElement
    const sb = document.querySelector('.app-sidebar')
    return {
      horizontalOverflow: doc.scrollWidth - doc.clientWidth,
      sidebarWidth: sb ? Math.round(sb.getBoundingClientRect().width) : null,
    }
  })
  expect('无横向溢出（外壳也一样）', layout1.horizontalOverflow === 0,
    `overflow=${layout1.horizontalOverflow}px`)
  // 展开态目录必须"占住一块宽度"，而不是被内容挤成 0 宽 ——
  // 挤成 0 宽时目录项仍然存在，上面那些 count 断言会全部照过
  expect('目录在展开态占住了一块宽度', layout1.sidebarWidth > 150, `${layout1.sidebarWidth}px`)
  expect('内容区在目录右侧（不是被目录盖住）', await page.evaluate(() => {
    const sb = document.querySelector('.app-sidebar')
    const main = document.querySelector('.app-main')
    if (!sb || !main) return false
    return main.getBoundingClientRect().left >= sb.getBoundingClientRect().right - 1
  }))
}

// ---------- 流式模式（默认） ----------
console.log('[2] 流式模式')
await box().fill('你好')
await box().press('Enter')
// 同样从"固定 sleep + 毫秒级等待"改成"轮询等结果"（理由见下面 [3] 那一段长注释）。
// ⚠️ 但这里的**语义要保住**：这一节验的是"流式回答能渲染"，
// 而不是"回答渲染得足够快"。所以先等它出现，再给一小段稳定时间让增量收尾 ——
// 两件事分开写，比把它们混在一个 waitForTimeout 里更经得起负载变化
await waitFor(async () => (await page.locator('.caret, .markdown').count()) > 0, 20000)
await page.waitForTimeout(500)
const hasCaret = (await page.locator('.caret, .markdown').count()) > 0
expect('流式回答已渲染', hasCaret)
const streamBody = await page.locator('.message-list').innerText()
expect('流式正文非空', streamBody.trim().length > 20, `${streamBody.trim().length} 字符`)

// ---------- 结构化模式：正常（含回灌） ----------
console.log('[3] 结构化模式 —— 正常路径（mock 会造一轮回灌）')
await navMode('结构化输出')
await page.waitForTimeout(300)
// 页头标题是**区的名字**，不是视图的名字：同一区里换视图，它不该变（变了说明分组判据散了）
expect('同区换视图，页头标题不变',
  /AI Agent 对话台/.test(await page.locator('.app-topbar .app-title').innerText()))
await box().fill('SSE 是什么？')
await box().press('Enter')
await page.locator('.structured-card').first().waitFor({ state: 'visible', timeout: 30000 })

// 【★ 这里必须等"数据到了"，而不是等一个固定时长 —— 实测踩到，值得记】
//
// 原来写的是 `await page.waitForTimeout(1200)` 然后直接读 `.structured-card .ant-tag`。
// 那个写法在**空载**时永远够（mock 是瞬时返回），但在 CI 上翻过车：
// 结构化自检整段超时，报的是
//     locator.innerText: Timeout 30000ms exceeded
//     waiting for locator('.structured-card .ant-tag').first()
// 而**同一个自检单独跑三遍全过** —— 典型的"只在有负载时才红"。
//
// 根因有两层，第二层才是关键：
//   ① `.structured-card` 这个**容器**在提交的瞬间就渲染出来了（那是骨架），
//      所以上面那句 `waitFor({state:'visible'})` 通过得毫无意义 ——
//      它证明的是"卡片在"，不是"数据到了"。**"容器在"不等于"内容在"**
//      （与「卡片在」不等于「列表有行」是同一个形状）；
//   ② 于是唯一的同步机制就是那 1200ms 的固定 sleep，而它在机器忙时不够。
//
// 修法用本项目已有的 `waitFor` 轮询助手：快的时候立刻继续、慢的时候一直等，
// 而不是"赌一个时长"。**"等一个固定时长"在慢操作上一定会有一个环境是错的**
// （这正是「界面自检不能用固定 sleep 等一个本来就慢的实验」的教训，第十三轮又在别处踩了一次）。
const structuredReady = await waitFor(
  async () => (await page.locator('.structured-card .ant-tag').count()) > 0,
  20000,
)
expect('结构化卡片等到了数据（而不是只有空骨架）', structuredReady)

const badge = (await page.locator('.structured-card .ant-tag').first().innerText()).trim()
expect('徽标显示轮次与回灌', /轮/.test(badge), badge)
expect('置信度条已渲染', (await page.locator('.confidence-bar').count()) > 0)
expect('关键词标签已渲染', (await page.locator('.structured-card .ant-tag').count()) >= 2)
expect('回灌过程面板存在', (await page.locator('.structured-card .ant-collapse-header').count()) > 0)
if (EXPECT_REFLECTION) {
  expect('回灌过程可展开（含原始输出）', (await page.locator('.raw-output').count()) > 0)
  expect('校验问题已列出', (await page.locator('.issue-list li').count()) > 0)
} else {
  // 真后端：模型一次就通过，没有回灌细节 —— 改为断言"结果确实渲染了"
  const answer = (await page.locator('.structured-card .markdown').first().innerText()).trim()
  expect('结构化答案已渲染', answer.length > 5, `${answer.length} 字符`)
  // 第 3 条补齐：真后端这条路径也要证明"卡片结构完整"，而不只是"有文字"。
  // 【两条分支的断言条数刻意保持相同（各 3 条）】否则"本次跑了多少项断言"会随路径变化，
  // 而一个会变的数字没法写进文档 —— 阶段一就在这里含糊过一次：
  // 本项目曾写成"36 项"而单条路径实际只执行 29 项（见下方"断言数怎么读"）。
  expect('结构化卡片结构完整', (await page.locator('.structured-card .confidence-bar').count()) > 0)
}
// 置信度说明里的 markdown 星号不能以字面量出现（写错就会这样）
const hint = await page.locator('.confidence-hint').first().innerText()
expect('置信度说明无字面量星号', !hint.includes('**'))
const hasBold = (await page.locator('.confidence-hint b').count()) > 0
expect('置信度说明用了真正的粗体标签', hasBold)

// ---------- 布局体检（代替肉眼看截图） ----------
const layout = await page.evaluate(() => {
  const doc = document.documentElement
  const card = document.querySelector('.structured-card')
  return {
    horizontalOverflow: doc.scrollWidth - doc.clientWidth,
    cardFitsViewport: card ? card.getBoundingClientRect().right <= doc.clientWidth + 1 : null,
  }
})
expect('无横向溢出', layout.horizontalOverflow === 0, `overflow=${layout.horizontalOverflow}px`)
expect('卡片不超出视口', layout.cardFitsViewport !== false)
await page.screenshot({ path: `${SHOT_DIR}/structured-normal.png`, fullPage: true })

// ---------- 结构化模式：降级路径（仅假后端可复现） ----------
if (EXPECT_REFLECTION) {
  console.log('[4] 结构化模式 —— 降级路径（mock：提问含「降级」二字）')
  await box().fill('这个一定会降级')
  await box().press('Enter')
  await page.waitForTimeout(2500)
  expect('出现「已降级」标记', (await page.locator('.structured-card .ant-tag', { hasText: '已降级' }).count()) > 0)
  const degradedBadge = (await page.locator('.structured-card').last().locator('.ant-tag').first().innerText()).trim()
  expect('降级时显示全部轮次', /3 轮/.test(degradedBadge), degradedBadge)
  await page.screenshot({ path: `${SHOT_DIR}/structured-degraded.png`, fullPage: true })
} else {
  // 真后端跑不出降级：模型总能改对（这是好消息，不是缺陷）。这条路径由单测覆盖，
  // 见 ai-agent 的 StructuredChatServiceImplTest#fallsBackAfterExhaustingAttempts
  console.log('[4] 跳过降级路径断言（真实模型不具备"必然降级"的输入）')
}

// ---------- 线程与预算面板（学习点 7 + 2） ----------
//
// 这一节断言的是"页面有没有把运行时事实如实表达出来"，而不是"后端算得对不对"：
//   - virtual 这个布尔值有没有被显著区分（它是"改造生效"的唯一直接证据）；
//   - 两组实验的按钮能不能跑通、结果有没有渲染；
//   - 钉住对照的两个数字是不是**并排可见**（这是这一页最值钱的一块，
//     只显示其中一个就等于没做对照）；
//   - 非流式对话能不能真的打通（说明虚拟线程那条链路是活的）。
console.log('[5] 线程与预算面板')
await navMode('线程与预算')
await page.waitForTimeout(500)

// 当前线程：虚拟/平台必须长得不一样
const threadCard = page.locator('.vt-hero').first()
const heroExists = (await threadCard.count()) > 0
expect('线程面板已渲染', heroExists)

if (heroExists) {
  const heroText = (await threadCard.innerText()).trim()
  expect('显示了线程模型判定', /虚拟线程|平台线程/.test(heroText), heroText.split('\n')[0])
  // 假后端固定返回 virtual:true —— 视觉上必须落到"已生效"那一侧，
  // 因为这两句话是这一页存在的理由：一眼看出改造到底生效没有
  expect('virtual=true 时标注为改造已生效', /改造已生效/.test(heroText))
  expect('当前线程标记为虚拟线程', (await page.locator('.vt-hero.is-virtual').count()) > 0)

  // 线程名在 hero 下方的键值表里（kv-grid），不在 hero 本身
  const threadNameKv = page.locator('.kv-item', { hasText: '线程名' }).first()
  expect('显示了请求线程名', (await threadNameKv.count()) > 0 && /tomcat-vt|http-nio|VirtualThread/.test(await threadNameKv.innerText()))
}

// 线程账目：刷新一次，数字要在
const refreshBtn = page.locator('button', { hasText: '刷新' }).first()
if ((await refreshBtn.count()) > 0) {
  await refreshBtn.click()
  await page.waitForTimeout(800)
}
const panelText = (await page.locator('body').innerText()).trim()
expect('线程账目已显示平台线程数', /平台线程/.test(panelText))
expect('线程账目已显示并发峰值', /并发峰值|inFlight/i.test(panelText))

// 载体复用实验：卡片标题里含「载体线程」，按钮是卡片的「跑一次」
const carrierCard = page.locator('.diag-card', { hasText: '载体线程' }).first()
const carrierBtn = carrierCard.locator('button', { hasText: '跑一次' })
if ((await carrierBtn.count()) > 0) {
  await carrierBtn.click()
  await page.waitForTimeout(2000)
  const carrierText = (await carrierCard.innerText()).trim()
  // 假后端固定返回 16 个任务 / 4 个载体；真后端每次不同，所以断言"算出了复用关系"而不是具体数字
  expect('载体复用实验有结果', /个虚拟线程/.test(carrierText) && /载体线程/.test(carrierText))
  expect('载体复用实验给出了复用结论', /只用到了|没有观察到|复用/.test(carrierText))
  expect('载体复用逐条采样已渲染', (await page.locator('.sample-row').count()) > 0)
} else {
  expect('载体线程实验卡片存在', false)
}

// 钉住对照：这是本页最值钱的一块，两个数字必须**并排同时在页面上** ——
// 只显示其中一个就等于没做对照，而那正是后端第一版实验的失败形态
// （两组都测出 0ms，看不出任何差异，见 ThreadDiagnosticsController 的注释）。
//
// 定位方式说明：这里按**序号**取卡片而不是按文字过滤。实测 `hasText: '钉住实验'`
// 明明在页面里（单独 probe 时 `text=钉住实验` 能找到 1 个），但用它过滤 .diag-card
// 就是匹配不上 —— 与其和匹配规则较劲，不如用稳定的事实：面板一共 6 张卡，
// 钉住是第 4 张。序号定位在断言里更可预测，也不会因为文案微调而失效。
const pinCard = page.locator('.diag-card').nth(3)
const pinTitle = (await pinCard.innerText()).trim().split('\n')[0]
expect('钉住对照实验卡片存在', /钉住/.test(pinTitle), pinTitle)

// 卡片里的触发按钮文案不统一：载体卡是「跑一次」，钉住卡是「跑对照实验」。
// 所以按"卡片 extra 区里的那个 primary 按钮"来找，而不是按文案 —— 文案会改，位置不会。
const pinBtn = pinCard.locator('button.ant-btn-primary').first()
if ((await pinBtn.count()) > 0) {
  await pinBtn.click()
  // **不要用固定 sleep 等这个实验**：它的耗时随占位任务数线性增长
  // （synchronized 那组要一批批把载体耗完），实测真后端默认参数要 19 秒左右 ——
  // 写死 3 秒会让断言在真后端上必然失败，而失败原因是"等得不够"，
  // 看起来却像"界面没渲染"。改成等结果元素真的出现，这样假后端（瞬时）和真后端（十几秒）都成立。
  await pinCard.locator('.pin-latency').first().waitFor({ state: 'visible', timeout: 90000 })
  await page.waitForTimeout(300)

  const pinText = (await pinCard.innerText()).trim()
  expect('钉住对照两组都已渲染', /synchronized/i.test(pinText) && /ReentrantLock/i.test(pinText))
  expect('钉住对照给出了判定结论', /钉住|排队|未观察到/.test(pinText))
  expect('钉住对照两个延迟数字并排可见', (await pinCard.locator('.pin-latency').count()) >= 2)
} else {
  expect('钉住实验有触发按钮', false)
}

// Token 预算切分：窗口 / 输出预留 / 安全边际三块都要露出来，
// 因为"预算怎么算的"是这个功能的全部说服力所在
const budgetText = (await page.locator('body').innerText()).trim()
expect('预算切分已显示窗口与安全边际', /128000|上下文窗口/.test(budgetText) && /安全边际/.test(budgetText))
expect('预算切分已显示输入上限', /106752|输入/.test(budgetText))

// ---------- RAG 分块评测面板（阶段二） ----------
//
// 这一节断言的是"页面有没有把这条离线链路如实表达出来"：
//   - 语料解析的**块类型分布**里标题块必须 > 0（它是结构分块的地基，为 0 说明解析退化，而整条链路不报错）；
//   - 分块预览里两种策略的**面包屑必须长得不一样**（结构分块有、硬切恒为空）——
//     这正是两种策略最本质的差别，如果两边都空，说明结构信息在某处丢了；
//   - 分词必须真的把中文切成二元组（这决定了检索能不能匹配上）；
//   - 召回结果的名次与归属章节要渲染出来；
//   - **对照表的两个策略都必须出现在页面上**，且差值区同时可见 ——
//     只显示一个策略就等于没做对照，而这正是阶段二验收口径的核心。
console.log('[6] RAG 分块评测面板')
await navMode('RAG 分块评测')
await page.waitForTimeout(800)

// —— 语料解析 ——
const corpusCard = page.locator('.diag-card', { hasText: '语料解析' }).first()
expect('RAG 面板已渲染（语料解析卡存在）', (await corpusCard.count()) > 0)
if ((await corpusCard.count()) > 0) {
  await page.waitForTimeout(600)
  const corpusText = (await corpusCard.innerText()).trim()
  expect('语料解析显示了文档数与块数', /文档数/.test(corpusText) && /文本块总数|块总数/.test(corpusText))
  // 标题块 > 0 是"结构分块的地基还在"的直接证据。假后端固定给 82，真后端也是几十个
  expect('标识出了标题块数量', /标题块/.test(corpusText))
  expect('未出现"没有识别出任何标题块"的告警', !/没有识别出任何标题块/.test(corpusText))
  expect('块类型分布已渲染', (await page.locator('.rag-type-tag').count()) >= 3)
}

// —— 分块预览：结构分块必须有面包屑 ——
const previewCard = page.locator('.diag-card', { hasText: '分块预览' }).first()
expect('分块预览卡片存在', (await previewCard.count()) > 0)
if ((await previewCard.count()) > 0) {
  // 默认已经是结构分块。等块渲染出来而不是写死 sleep
  await previewCard.locator('.rag-chunk').first().waitFor({ state: 'visible', timeout: 30000 }).catch(() => {})
  await page.waitForTimeout(400)
  const structuralChunks = await previewCard.locator('.rag-chunk').count()
  expect('结构分块产出了块', structuralChunks > 0, `${structuralChunks} 条`)
  const structuralBreadcrumbs = await previewCard.locator('.rag-breadcrumb:not(.is-empty)').count()
  expect('结构分块的块带标题面包屑', structuralBreadcrumbs > 0, `${structuralBreadcrumbs} 条有面包屑`)
  expect('预览显示了 token 数', /token/.test(await previewCard.innerText()))

  // 切到对照组：面包屑必须变成"（无标题路径）"——
  // 这是"对照组确实不含结构信息"的界面证据，也顺带验证了切策略会自动重跑
  await previewCard.locator('.ant-segmented-item', { hasText: '固定硬切' }).click()
  await previewCard.locator('.rag-breadcrumb').first().waitFor({ state: 'visible', timeout: 30000 }).catch(() => {})
  await page.waitForTimeout(600)
  const emptyBreadcrumbs = await previewCard.locator('.rag-breadcrumb.is-empty').count()
  expect('硬切的块标注为无标题路径', emptyBreadcrumbs > 0, `${emptyBreadcrumbs} 条无面包屑`)
}

// —— 分词诊断：中文必须被切成二元组 ——
const tokensCard = page.locator('.diag-card', { hasText: '分词诊断' }).first()
expect('分词诊断卡片存在', (await tokensCard.count()) > 0)
if ((await tokensCard.count()) > 0) {
  // antd 6 会把两字中文按钮渲染成「分 析」（中间插一个空格），所以文案匹配要容忍空格 ——
  // 用 hasText: '分' 再配合正则确认，否则 '分析' 会永远匹配不上（这正是它超时的原因）
  await tokensCard.locator('button').filter({ hasText: /分\s*析/ }).first().click()
  await tokensCard.locator('.rag-token').first().waitFor({ state: 'visible', timeout: 20000 }).catch(() => {})
  await page.waitForTimeout(300)
  const tokenTexts = await tokensCard.locator('.rag-token').allInnerTexts()
  expect('分词结果已渲染', tokenTexts.length > 0, `${tokenTexts.length} 个 token`)
  // 中文二元组：默认提问是「虚拟线程在高并发下的吞吐量提升了多少」，
  // 切成二元组后必然出现长度恰为 2 的中文片段
  const bigrams = tokenTexts.filter(t => /^[\u4e00-\u9fff]{2}$/.test(t))
  expect('中文被切成二元组（不是整段中文）', bigrams.length > 0, `如 ${bigrams.slice(0, 3).join('/')}`)
  expect('没有把整句中文当一个 token', !tokenTexts.some(t => t.length > 4 && /^[\u4e00-\u9fff]+$/.test(t)))
}

// —— 召回结果 ——
const retrieveCard = page.locator('.diag-card', { hasText: '召回：' }).first()
expect('召回卡片存在', (await retrieveCard.count()) > 0)
if ((await retrieveCard.count()) > 0) {
  await retrieveCard.locator('button').filter({ hasText: /检\s*索/ }).first().click()
  await retrieveCard.locator('.rag-hit-table').waitFor({ state: 'visible', timeout: 30000 }).catch(() => {})
  await page.waitForTimeout(300)
  const hitRows = await retrieveCard.locator('.rag-hit-table tbody tr').count()
  expect('召回结果已渲染', hitRows > 0, `${hitRows} 条`)
  expect('召回显示了名次', (await retrieveCard.locator('.rag-hit-table td.is-rank').count()) > 0)
  // 归属章节是排查"召回错了"最关键的一列
  expect('召回了归属章节列', (await retrieveCard.locator('.rag-hit-table td.is-breadcrumb').count()) > 0)
}

// —— 对照评测：这一块是阶段二的验收口径所在 ——
const compareCard = page.locator('.diag-card', { hasText: '分块策略对照' }).first()
expect('对照卡片存在', (await compareCard.count()) > 0)
if ((await compareCard.count()) > 0) {
  await compareCard.locator('button', { hasText: '跑对照' }).click()
  await compareCard.locator('.rag-metric-table').first().waitFor({ state: 'visible', timeout: 90000 })
  await page.waitForTimeout(500)

  const metricRows = await compareCard.locator('.rag-metric-table tbody tr').count()
  expect('对照表两个策略都在页面上', metricRows >= 2, `${metricRows} 行`)

  const compareText = (await compareCard.innerText()).trim()
  expect('标出了对照组', /对照组/.test(compareText))
  // 指标名必须出现 —— 否则那张表就只剩数字，读者不知道每列是什么
  expect('显示了 Recall 指标', /Recall@5/.test(compareText) && /Recall@10/.test(compareText))
  expect('显示了 MRR 指标', /MRR/.test(compareText))
  // token 成本必须与召回率并排：只给 Recall 会让人得出"块越大越好"的错误结论
  expect('显示了 token 成本列', /前 3 条 token/.test(compareText) && /全部候选 token/.test(compareText))
  expect('显示了差值区', /差值/.test(compareText))
  expect('差值块已渲染', (await compareCard.locator('.rag-delta').count()) > 0)
  // 回归钉子：Recall 越高越好，token 越高越差 —— 两者的颜色方向必须相反，
  // 统一按"涨=绿"上色会稳定地把读者引向错误结论
  expect('差值颜色区分了方向', (await compareCard.locator('.rag-delta-value.is-bad').count()) > 0)
  // "不能证明什么"必须写在界面上，否则读者会自行脑补结论
  expect('页面上说明了不能证明什么', /不能说|块越大越容易碰到答案/.test(compareText))
  expect('显示了分组表现', /分组表现/.test(compareText))
  expect('显示了未命中用例', /未命中/.test(compareText))

  // ★ 纯文本区里不许出现字面量 markdown 星号（第十六轮补的一条守卫）
  //
  // 【为什么补在 RAG 面板上】纯文本里写 markdown 星号会原样显示，而成本看板（第十轮）、
  // Prompt / 告警面板（第十一 / 十五轮）都各自加过断言 —— **只有 RAG 面板没加**。
  // 这一轮扫一遍整个仓库才发现它的 notes 与对照说明里都写着 `**加粗**`，
  // 而它们渲染到这一页的纯文本 <li> 里，也就是说**页面上真的印着星号**。
  // 一条"某一类问题在本项目里已经被清干净了"的结论，只有在这类地方各有一条断言时才成立。
  //
  // ⚠️ **判据必须排除"分块预览"那一块** —— 实测踩到（本轮）：
  // 它显示的**就是原文**，而语料是 Markdown，所以那里出现 `**单一事实来源**`
  // 不是缺陷，恰恰是"固定硬切把标题层级丢了、正文里的星号原样留着"的证据。
  // 把它一起判进去，就会得到一条**在功能正确时必然红**的断言（「否定式判据要先确认它否定的到底是什么」同族：
  // 判据要先确认它判的到底是什么东西）。
  // 所以判据是"**除了原文预览之外**的正文里没有字面量星号"。
  const starProbe = await page.locator('.diag-panel').first().evaluate(panel => {
    const clone = panel.cloneNode(true)
    // 预览区与它会话里的原始 Markdown 不算"文案"，它们要显示的就是原样的字节
    clone.querySelectorAll('.rag-chunk, .rag-breadcrumb, pre, code').forEach(el => el.remove())
    return clone.innerText
  })
  expect('RAG 面板正文无字面量星号（原文预览区除外）', !starProbe.includes('**'),
    '—— 后端 notes / 对照说明会原样渲染到纯文本里，强调一律用「」')

  await page.screenshot({ path: `${SHOT_DIR}/rag-compare.png`, fullPage: true })
}

// —— 块大小对齐实验：本轮把混杂因素消掉之后的那份报告 ——
//
// 这一节断言的是"界面有没有如实呈现一个**与上一张表相反**的结论"：
//   - 7 组对照必须都在（少了就等于只报了一部分数字）；
//   - **左右两行必须并排出现**（只显示一行就等于没做对照，这正是这张报告存在的理由）；
//   - 差值颜色必须区分方向（token 类指标涨了是坏事，与 Recall 相反）；
//   - "关掉面包屑那一组才是在比切点"必须写在界面上 ——
//     不写的话读者会拿带面包屑那三组去下"切点"的结论，而那三组对齐的是渲染后大小；
//   - **向量那一路是离线近似品**必须出现 —— 否则读者会拿 vector-local 的
//     Recall@5 0.34 去和 BM25 的 0.74 比，得出"向量检索差一半"这个完全错误的结论。
console.log('[6.5] 块大小对齐实验')
const alignCard = page.locator('.align-card').first()
expect('对齐实验卡片存在', (await alignCard.count()) > 0)
if ((await alignCard.count()) > 0) {
  // 它进面板就自动跑（后端实测 4.95 秒），所以这里等结果元素而不是点按钮。
  // 超时给 120 秒：真后端要跑 15 次全量评测，假后端睡 600ms
  await alignCard.locator('.rag-metric-table').first().waitFor({ state: 'visible', timeout: 120000 })
  await page.waitForTimeout(500)

  const alignText = (await alignCard.innerText()).trim()
  expect('对齐实验的标题写明了结论反转', /结论反了|混杂因素/.test(alignText))
  // 概览：目标 / 实测 / 残差 三格必须在 —— 残差单独占一格是刻意的
  //（"对齐永远是近似的"，差多少要让读者自己判断）
  expect('显示了对齐目标与实测值', /对齐目标/.test(alignText) && /收敛后实测/.test(alignText))
  expect('显示了对齐残差', /对齐残差/.test(alignText))
  // 7 组对照（3 路检索 × 2 个对齐组 + 1 组"块更大的效应"）
  const alignGroups = await alignCard.locator('.align-group').count()
  expect('按检索方式分组渲染了 7 组对照', alignGroups >= 3, `${alignGroups} 个检索分组`)
  const refRows = await alignCard.locator('.align-row-ref').count()
  const subjectRows = await alignCard.locator('.align-row-subject').count()
  expect('每组对照的参照方都渲染了', refRows >= 7, `${refRows} 行参照方`)
  expect('每组对照的被考察方都渲染了', subjectRows >= 7, `${subjectRows} 行被考察方`)
  // 左右并排 —— 只显示一侧就等于没做对照
  expect('左右两方成对出现', refRows === subjectRows)
  expect('标出了左右两方的角色', /参照方/.test(alignText) && /被考察方/.test(alignText))
  // 差值的方向配色：Recall 与 token 成本必须能同时出现两种颜色
  expect('差值列按各自方向着色', (await alignCard.locator('.rag-inline-delta.is-bad').count()) > 0)
  // token 成本必须与召回并排（只给 Recall 会让人得出"块越大越好"）
  expect('token 成本与召回并排', /全语料 token/.test(alignText) && /重复率/.test(alignText))
  // "哪几组才是在比切点"必须写在界面上
  expect('说明了哪几组才是在比切点', /不能单独判断切点|在比「切点」/.test(alignText))

  /*
    这两个标签的数量是**钉住一个回归**：第一版把"能不能判断切点"打在了**分组表头**上，
    于是 vector / hybrid 两个分组被整体标成"对齐的是渲染后大小" ——
    而它们的第一组恰恰是最干净的那一组（关掉面包屑）。根因是分组里混着两种性质的对照，
    只要有一组不合格就污染了整个分组。

    所以断言**两种标签同时出现且数量固定**：
      - "在比「切点」" 3 个 = 三路检索各一组（对齐正文的那三组）；
      - "对齐的是渲染后大小" 4 个 = 三组带面包屑的锚点 + 第 7 组"块更大的效应"。
    数量写死是有意的：多一个少一个都说明后端的 pairs 构成变了，
    而那时这套解读文字需要重新核对（它讲的正是"哪几组干净"）。
  */
  const definitiveTags = await alignCard.locator('.align-row-ref .ant-tag', { hasText: '在比「切点」' }).count()
  const anchorTags = await alignCard.locator('.align-row-ref .ant-tag', { hasText: '对齐的是渲染后大小' }).count()
  expect('干净对照（在比切点）正好三组', definitiveTags === 3, `${definitiveTags} 个`)
  expect('锚点对照（渲染后大小）正好四组', anchorTags === 4, `${anchorTags} 个`)

  // 收敛过程：扫描表 + 收敛点
  expect('显示了尺寸扫描表', /确定收敛|收敛点|尺寸扫描表/.test(alignText))
  // 轴二：等预算表，且必须写明"只有词法那一路有数"
  expect('显示了等预算表', /等 token 预算/.test(alignText))
  expect('说明了预算表只有词法有数', /只有词法那一路有数/.test(alignText))
  // 不能下的结论必须出现（尤其"向量那一路不是语义嵌入"）
  expect('写明了向量那一路是离线近似品', /离线哈希|不是语义嵌入/.test(await alignCard.innerText()))

  await page.screenshot({ path: `${SHOT_DIR}/rag-align.png`, fullPage: true })
}

/*
  假数据自身的**方向自检** —— 这一节不打开浏览器，直接读 mock 数据。

  为什么值得单独写一条：这份面板上的**解读文字与数字是绑定的**
  （"块大小对齐之后固定硬切全面占优"），而自检断言不了"文字和数字是否一致"。
  一旦有人顺手改了 mock 里的数字（或者换了一版真数据），
  界面就会变成"文字说结论反了、数字说没反"，而**所有基于文案的断言照样全绿**。

  两条相反方向的断言放在一起，还顺带把这条实验的逻辑钉住了：

    - 三组"块大小已对齐"的对照里，**右侧（结构分块）必须更差**；
    - 而第 7 组（刻意**不**对齐块大小那组）里，**块更大的那一侧反而更好** ——
      这恰恰是"块越大越容易覆盖答案"这个混杂因素本身，也是上面那份报告存在的理由。
      所以第 7 组出现"方向相反"**不是**数据错了，是它在证明混杂因素真的存在。
*/
{
  const { ALIGNMENT } = await import('./mock/rag-align-data.mjs')
  // 「块大小已对齐」那三组：字段描述里写着"关掉面包屑"（对齐的是正文，不是渲染后大小）。
  //
  // ⚠️ 别用"不含 '+面包屑'"来筛 —— 这条会连**第 7 组（块更大的效应）**一起筛进来，
  // 而那一组是**刻意不对齐块大小**的对照，它的方向本来就该相反。
  // 实测踩到了：`/\+面包屑/` 筛出 4 组，然后第 7 组稳定报"反向 1 组"，
  // 看起来像数据错了，其实是筛选条件把"反证组"当成了"结论组"。
  const definitive = ALIGNMENT.pairs.filter(p => p.fieldDescription.includes('关掉面包屑'))
  const wrong = definitive.filter(p => {
    const [left, right] = p.reports
    const d5 = p.deltas['recall@5']?.[right.strategy] ?? 0
    const dmrr = p.deltas['mrr']?.[right.strategy] ?? 0
    return !(d5 <= 0 && dmrr <= 0)
  })
  expect(
    '假数据方向与界面结论一致（对齐组里右方更差）',
    wrong.length === 0,
    `共 ${definitive.length} 组，反向 ${wrong.length} 组`,
  )
  // 反证：不对齐块大小那组必须能看出"块更大 = 更好"，否则说明这份数据里
  // 根本没有混杂因素，"消掉混杂因素"这句话就无从谈起
  const confound = ALIGNMENT.pairs.find(p => p.fieldDescription.includes('块更大的效应'))
  const confoundProvesIt =
    confound !== undefined && (confound.deltas['recall@1']?.[confound.reports[1].strategy] ?? 0) > 0
  expect('假数据里能看出"块更大更容易覆盖答案"这个混杂因素', confoundProvesIt)
}

// —— 评测集 ——
const evalCard = page.locator('.diag-card', { hasText: '评测集：' }).first()
expect('评测集卡片存在', (await evalCard.count()) > 0)
if ((await evalCard.count()) > 0) {
  const evalText = (await evalCard.innerText()).trim()
  expect('评测集显示了条数与门槛', /共 \d+ 条/.test(evalText) && /门槛/.test(evalText))
  expect('评测集显示了分组统计', /failures|phase1|phase2|knowledge|materials/.test(evalText))
}

// 布局体检：这张面板的表格列多，最容易横向溢出
const ragLayout = await page.evaluate(() => {
  const doc = document.documentElement
  return { horizontalOverflow: doc.scrollWidth - doc.clientWidth }
})
expect('RAG 面板无横向溢出', ragLayout.horizontalOverflow === 0, `overflow=${ragLayout.horizontalOverflow}px`)

// ---------- Agent 工具调用面板（阶段三第 5 件） ----------
//
// 这一节断言的是"页面有没有把 Agent 到底干了什么如实表达出来"：
//   - **答案与过程必须同屏** —— 只显示答案的话，"查了 3 次、失败 2 次、最后蒙对"
//     与"一次就答对"长得一模一样，而区分这两者是这个功能的全部价值；
//   - **每次工具调用都要有 stage 与回填文本** —— stage 说明"死在哪一步"，
//     回填说明"模型当时看到了什么"，合起来才能回答"它为什么这么选"；
//   - **被拒绝的调用也必须在页面上**（UNKNOWN_TOOL 那次）——
//     报告要回答"模型想调几次、其中几次没让它调"，只显示成功的会让这个数字永远偏小；
//   - **失败细分必须显示** —— MODEL_ERROR 下面藏着 6 种原因，其中
//     "我们自己的并发闸门拒绝"与"上游鉴权失败"的排查方向完全相反；
//   - **限额块必须写明"全局共享"** —— 否则读者会以为那是某一次运行的属性；
//   - **"计入熔断"这一列必须由后端给** —— 前端不自己判 stage 的好坏，
//     否则判据会有两份并慢慢漂移。
console.log('[6.6] Agent 工具调用面板')

/**
 * <b>切到 Agent 面板 —— 这是下面所有内容的前提。</b>
 *
 * <p>不切过来的话，"待我确认""跑一个任务""历史运行""四层保护"四张卡片
 * 一个都不会渲染，而失败信息看起来像"功能没做"。
 *
 * <h2>这一步踩过一次，值得单独记</h2>
 * 本次改动把"跑一个任务 + 验证四步轨迹"整段往后移（因为假后端现在按审批状态
 * 返回不同轨迹，所以那段必须在批准之后才能跑）。
 * 而**搬动时顺手把"切面板"这个动作也一起搬走了** ——
 * 于是紧随其后的"历史运行卡片存在""限额卡片存在"两条先红，
 * 而 [6.7] 那一整节（新功能）也跟着全红。
 *
 * <p>教训一句话：**一段自检代码里往往混着"动作"与"断言"两类语句，
 * 搬动时只盯着断言，就会把动作一起搬走** —— 而症状是"看起来这个功能没渲染"。
 */
await navMode('Agent 工具调用')
await page.waitForTimeout(900)

// 【原「跑一个任务 + 验证四步轨迹」那一段被移到了 HITL 那一节之后】
//
// 移它的理由是一个真实的耦合：假后端现在按**审批状态**返回不同的轨迹 ——
// 有待批时返回"停在等人确认"（steps 为空），批准之后才返回完整轨迹。
// 所以"验证完整四步轨迹"这件事**必须发生在批准之后**，否则它断言的是一个
// 那一刻还不存在的场景（跑出来就是"0 步""没有最终答案"一串红）。
// 这不是把断言放宽，而是**顺序本来就有依赖** —— 之前没暴露是因为那时假后端只有一条轨迹。

// —— 历史运行列表 ——
//
// ⚠️ 这里用的是**精确标题**而不是 hasText 子串匹配，理由是一个实测踩到的真问题：
// `'历史运行'` / `'运行详情'` 这类短串会被**后来新增的卡片**意外命中。
// 第十三轮加了"五层 trace"卡之后，标题写的是
// 「五层 trace：会话 → 运行 → LLM 调用 → 工具调用 → 检索」——
// 它**不含** '运行详情'，所以这次没踩到；但同一类匹配在别的短串上就会踩到
// （例如如果哪天有卡片的标题里出现"详情"二字）。
// 用 `^历史运行` 这种锚定前缀，让"匹配到一张意料之外的卡"变成**不会静默发生**的事。
const runsCard = page.locator('.diag-card').filter({ has: page.locator('.ant-card-head-title', { hasText: /^历史运行/ }) }).first()
expect('历史运行卡片存在', (await runsCard.count()) > 0)

if ((await runsCard.count()) > 0) {
  /*
    【★ 这一节按"有没有数据"分岔，而不是硬断言"至少有 1 行" —— 实测踩到，值得记】

    第一版在这里写的是"等到 tbody 里有行（最多等 20 秒）"，跑真后端时红了：
        ✗ 历史运行列表里至少有 1 行 — 实际 0 行
    而**那是环境，不是缺陷**：真后端这次自检里 `/ask` 必然 401（假 key），
    于是没有任何运行被落库，列表为空是**完全正常**的。
    列表为空时页面显示空状态，那也是对的。

    这与本节下面那条"刚刚跑过的任务"是同一个毛病（「断言越界去测了别人的职责」，
    以及「断言写的是假数据里才有的话；「被跳过」与「通过」在日志里长得一样」）：
    **断言越界去测了别人的职责** ——「库里有数据」取决于这次自检有没有成功跑过 `/ask`，
    而假 key 下必然 401；这一节（历史运行列表）自己负责任的性质只有两条：
      ① 卡片渲染出来了；
      ② **有数据时，数据能被渲染出来**。
    ② 是条件断言，条件就是"确实有数据"。

    ⚠️ 关键是把"没数据所以跳过"这件事**显式说出来**：静默跳过与通过长得一样，
    而"一个从未执行过的断言与一个永远通过的断言，在任何观测上都相同"（「被跳过」与「通过」在日志里长得一样）。
  */
  const hasRows = await waitFor(async () => (await runsCard.locator('.ant-table-tbody tr').count()) > 0, 5000)
  if (!hasRows) {
    console.log('    （历史运行列表是空的 —— 真后端的假 key 让 `/ask` 必然 401，' +
      '于是没有任何运行被落库；所以本节只验证"卡片渲染出来了"，不验证行内容。这**不是**跳过一条断言，' +
      '而是这一节对"库里有数据"这件事本来就没有主张）')
  }
  const runsText = (await runsCard.innerText()).trim()
  expect('历史运行卡片渲染出了内容（空状态也要有说明）', runsText.length > 5, `${runsText.length} 字符`)

  // ⚠️ 注意判据是 `hasRows && !EXPECT_REFLECTION` —— **`hasRows` 这一半不能省**。
  // 实测踩到（第十六轮）：原来的写法是 `if (hasRows && EXPECT_REFLECTION) {...} else {...}`，
  // 而那个 `else` 分支的注释写的是「真后端（假 key）：历史里只有那条失败运行」——
  // 它**假设了列表非空**。真后端这次列表是空的（假 key ⇒ `/ask` 401 ⇒ 没有运行落库），于是 `else` 照样进，
  // 两条断言（"显示了失败细分" / "显示了失败状态"）在一个**完全正常**的空列表上红了，
  // 而失败信息看起来像"界面没渲染失败细分"。
  // 这是"断言越界去测了别人的职责"的第 N 次（同族问题反复出现过），
  // 而这一次的形状更隐蔽：**越界藏在 `else` 里** —— `else` 不等于"有数据"，它只等于"不满足上一个条件"。
  if (hasRows && !EXPECT_REFLECTION) {
    // 【"刚刚跑过的任务在列表里"只在假后端路径上成立】
    // 原来这条是本节的共用断言，写的是 `/Agent 工具调用面板|分块策略的结论是什么/` ——
    // 而那个问题是**假数据里写死的**，真后端上永远不会有。
    // 实测：真后端路径上它确实红了（那次恰好列表非空，于是断言真的执行了）。
    //
    // 这与上面那条是同一个毛病：**断言越界去测了别人的职责** ——
    // 「列表里有**这一次**刚跑出来的任务」不是 §6.6 这一节能保证的事
    // （它取决于 `/ask` 有没有成功落库，而假 key 下必然 401）。
    // 假后端里那份历史是固定的，所以在那里做这条内容断言才有确定性。
    expect('列出了刚刚跑过的任务（假数据里有那条固定问题）',
      /Agent 工具调用面板|分块策略的结论是什么/.test(runsText))
    // 假后端给了一份"包含各种结局"的历史，所以这些状态标签都能验证
    expect('显示了状态标签', /已回答/.test(runsText))
    // **等待恢复的提示必须显眼**：默认配置不会自动恢复，不说的话那些运行会一直躺着没人管，
    // 而现象是"那个任务永远没结果"
    expect('提示了有运行在等待恢复', /等待恢复/.test(runsText))
    // 失败细分必须在列表里可见（MODEL_ERROR 这一个出口下面藏着 6 种原因）
    expect('显示了失败细分', /LOCAL_CONCURRENCY_LIMIT/.test(runsText))
    // 被上限拦下与"失败"是两件事，界面上要分开
    expect('区分了"被上限拦下"与"失败"', /被上限拦下/.test(runsText) && /失败/.test(runsText))
  } else if (hasRows) {
    // 真后端（假 key）+ **列表非空**：历史里通常只有那条失败运行 ——
    // 正好验证失败细分的**真实渲染**。
    // ⚠️ `hasRows` 这个条件必须显式写在 `else` 上（见上面那段注释）：
    // "列表为空"既不满足前一个分支、也不该进这一个。
    //
    // ⚠️ 判据刻意**不写死某一个分类名**。第一版写的是 `/UPSTREAM_AUTH/`，
    // 理由是"假 key → 上游 401 → 必然分到 UPSTREAM_AUTH"。而实测这次拿到的
    // `failureCause` 是 **UNEXPECTED**（上游 401 经过 SDK 包装之后没被认成鉴权失败），
    // 于是这条断言在一个**完全正常**的界面上红了 —— 它测的其实是后端的分类准确度，
    // 而不是"界面有没有把失败细分渲染出来"，而那才是这一节要钉的东西。
    //
    // 改成两条各自成立的判据：
    //   ① 失败细分这一格**有值**（六个合法分类之一），说明它真的渲染了；
    //   ② 列表里能看出这是失败状态。
    // 分类准不准由 `AgentFailureClassifier` 的后端测试负责，不该由界面自检替它把关。
    const causes = ['LOCAL_CONCURRENCY_LIMIT', 'UPSTREAM_AUTH', 'UPSTREAM_RATE_LIMITED',
      'UPSTREAM_TIMEOUT', 'UPSTREAM_UNAVAILABLE', 'UPSTREAM_ERROR', 'UNEXPECTED']
    const shown = causes.filter(c => runsText.includes(c))
    expect('显示了失败细分（不是空白，也不是一律 MODEL_ERROR）', shown.length > 0,
      shown.length > 0 ? `显示的是 ${shown.join('/')}` : '一格都没显示')
    expect('显示了"失败"状态', /失败/.test(runsText))
  }
  else {
    // 【列表为空 + 真后端】空状态本身也要有一句话说清"为什么空"
    // （一个只有表头、没有解释的空列表会被读成"功能坏了"）
    expect('空的历史列表也给出了说明（不是一片空白）',
      /一次运行|还没有|空|没.*运行/.test(runsText), `—— 正文：${runsText.slice(0, 80)}`)
  }
}

// —— 点开详情：这一步验证"能还原执行过程" ——
//
// ⚠️ 这里必须**先确认列表里真的有一行**再点（实测踩到，值得记）。
// 原来写的是"卡片在就点第一行"，而 `.first().click()` 在**列表为空**时会等满 30 秒然后
// 抛一个未捕获异常，**整条自检当场中断** —— 于是这一条之后的所有断言（包括成本看板整节）
// 一条都没跑，而日志里看起来像"点不动详情"。
//
// 什么时候列表会是空的：真后端上"有没有历史运行"取决于 `/ask` 有没有落库 ——
// 本机 key 是假的，`/ask` 会 401，但**那次失败的运行同样会落库**，
// 所以通常有；可一旦它在别的路径上没落上（比如上一个用例刚清库、或者 `/ask` 卡在加载中），
// 列表就真的是空的。**"卡片在"不等于"列表有行"** —— 两者要分开判。
if ((await runsCard.count()) > 0) {
  const runRows = runsCard.locator('.ant-table-tbody tr')
  const rowCount = await runRows.count()
  if (rowCount === 0) {
    // 空列表也要被解释清楚，而不是让后面的步骤静默跳过
    console.log('    （列表里没有运行记录，跳过"点开详情"这一段）')
  }
  else {
    await runRows.first().click()
  }
  const detailCard = page.locator('.diag-card', { hasText: '运行详情' }).first()
  await detailCard.waitFor({ state: 'visible', timeout: 30000 }).catch(() => {})
  await page.waitForTimeout(400)
  if (rowCount > 0 && (await detailCard.count()) > 0) {
    const detailText = (await detailCard.innerText()).trim()
    expect('详情显示了步骤表', /每步一个检查点/.test(detailText))
    expect('详情显示了每次工具调用', /含被拒绝的/.test(detailText))
    // **判据只能有一份**：这一列由后端给，页面不自己判 stage 的好坏
    expect('详情里有「计入熔断」列', /计入熔断/.test(detailText))

    if (EXPECT_REFLECTION) {
      // 这一列是"断点恢复"在界面上的唯一痕迹 —— 没有它，阶段三第 2 件在页面上不可见。
      // 真后端那次运行 0 步（假 key 直接 401），所以这条只在假路径可验证
      expect('详情显示了检查点上下文大小', /字符/.test(detailText))
    }

    // ================================================================
    //  五层 trace（阶段四第 2 件）—— 在关掉详情**之前**验证
    //
    //  【这一段要证明的是"第 5 层真的被摆出来了"】
    //  检索明细在此之前只存在于**回填给模型的那段中文文本**里（回填文本确实也显示
    //  在这一页上），所以核心断言是"页面上能读出<u>结构化</u>的检索明细"
    //  （查询词 / 索引规模 / 名次 / 每块 token / 得分），而不是"页面上出现了「检索」两个字"
    //  —— 后者在一个只显示回填文本的实现上同样会过。
    //
    //  ⚠️ 位置很关键：`closeDetail` 会把 trace 与详情**一起**清掉（理由见 `useAgent` 里
    //  那段注释：留着它会让人"关掉再点另一次"时先看到上一条运行的 trace）。
    //  所以这一段必须在点「关闭」之前跑完。
    // ================================================================
    const traceCard = page.locator('.diag-card', { hasText: '五层 trace' }).first()
    await traceCard.waitFor({ state: 'visible', timeout: 30000 }).catch(() => {})
    await page.waitForTimeout(300)
    expect('★ 详情打开时五层 trace 卡同时出现（两个端点是并发拉的）', (await traceCard.count()) > 0)

    if ((await traceCard.count()) > 0) {
      const traceText = (await traceCard.innerText()).trim()

      // ---- 两条路径共用的断言（不依赖假数据）----

      // 【五层到场清单】它是读这一页之前必须先知道的事：
      // 不看它会把"这一层本来就没内容"误读成"漏记了"
      const layerTags = traceCard.locator('[data-testid^="trace-layer-"]')
      const layerCount = await layerTags.count()
      expect('五层到场清单列出了 5 层', layerCount === 5, `实际 ${layerCount} 层`)
      expect('清单里点名了会话那一层', layerCount > 0 && /会话/.test(await layerTags.first().innerText()))
      expect('清单里点名了检索那一层', (await traceCard.locator('[data-testid="trace-layer-retrieval"]').count()) > 0)

      // 【★ 这一条钉的是"空"与"漏记"必须分得开】
      // 判据由后端给（`expected` 布尔），前端只负责显示 —— 页面自己判的话就是第二份判据。
      // 两条路径上真后端那次运行大多死在第一步之前（假 key 401），所以层次会缺 ——
      // 但**每一层的标签与后端给的 expected 必须自洽**，这是任何路径上都成立的性质
      {
        const inconsistencies = await traceCard.evaluate(card =>
          [...card.querySelectorAll('[data-testid^="trace-layer-"]')]
            .filter(tag => {
              const expected = tag.getAttribute('data-expected') === '1'
              const saysHasContent = tag.textContent.includes('✓')
              return expected !== saysHasContent
            })
            .map(tag => tag.textContent.trim()),
        )
        expect(
          '每一层的标签与后端给的 expected 自洽（「空」与「该有」必须分得开）',
          inconsistencies.length === 0,
          inconsistencies.join('、'),
        )
      }

      // 会话那一层：要么给出会话 id 与运行列表，要么**明说没有会话信息**。
      // ⚠️ 两种渲染都不能是"一片空白" —— 空白会让人以为功能没做
      {
        const saysAbsent = (await traceCard.locator('[data-testid="trace-conversation-absent"]').count()) > 0
        const hasConversationId = /会话 id/.test(traceText)
        expect('会话那一层给出了会话 id 或明说没有会话信息', hasConversationId || saysAbsent)
      }

      // 提示词指纹那一列必须存在（"这一步用的是哪一版"是本件三件之一）
      expect('LLM 调用表里有「提示词指纹」列', /提示词指纹/.test(traceText))

      // 【★ 金额算不出来时绝不能显示 ¥0.00】
      // 与成本看板同一条纪律，而它在界面上只有这一条判据：amount 为 null 时
      // 必须渲染成「算不出金额」，而不是任何形式的 0。
      // 真后端上价格表默认是空的，所以这条在两条路径上都该成立
      {
        const unknownBox = traceCard.locator('[data-testid="trace-cost-unknown"]')
        const knownAmount = traceCard.locator('[data-testid="trace-cost-amount"]')
        const hasUnknown = (await unknownBox.count()) > 0
        const hasKnown = (await knownAmount.count()) > 0
        expect('金额要么给数字、要么明说「算不出」', hasUnknown || hasKnown)
        if (hasUnknown) {
          const text = await unknownBox.innerText()
          expect('「算不出」那一块写明了原因', text.length > 10, text.slice(0, 60))
          expect('「算不出」时页面上没有 ¥0.00', !/¥\s*0\.0{2,}/.test(traceText))
        }
      }

      // ---- 假后端路径特有的断言：那里有确定性的检索明细 ----
      if (EXPECT_REFLECTION) {
        const retrievalCalls = traceCard.locator('[data-testid="trace-retrieval-call"]')
        const callCount = await retrievalCalls.count()
        expect('★ 检索那一层被摆出来了（每一次检索一个明细块）', callCount > 0, `实际 ${callCount} 块`)

        if (callCount > 0) {
          const first = await retrievalCalls.first().innerText()
          // 这三个数就是"0 条命中"的全部上下文 —— 没有索引规模就分不清
          // "库是空的"与"没匹配上"，而那两种的改法正好相反
          expect('检索明细里有查询词', /查询词/.test(first))
          expect('检索明细里有索引规模', /索引 \d+ 块/.test(first))
          expect('检索明细里标了 token 是估算的', /估算/.test(first))

          // 命中的结构：名次 / 得分 / 来源 / 每块 token / 摘录
          const hitTable = retrievalCalls.filter({ has: page.locator('.rag-hit-table') }).first()
          const hitRows = hitTable.locator('.rag-hit-table tbody tr')
          const hitCount = await hitRows.count()
          expect('★ 命中的块逐条列出（不是一段文本）', hitCount > 0, `实际 ${hitCount} 行`)
          if (hitCount > 0) {
            const hitHeader = await hitTable.locator('.rag-hit-table thead').innerText()
            expect('命中表有名次列', /名次/.test(hitHeader))
            expect('命中表有得分列', /得分/.test(hitHeader))
            expect('命中表有来源/章节列', /来源/.test(hitHeader))
            expect('命中表有每块 token 列', /token/.test(hitHeader))
            expect('命中表有片段摘录列', /片段开头/.test(hitHeader))

            // 名次必须从 1 开始且连续 —— 后端断言过同一条性质，这里是它在页面上的样子。
            // （后端在写库时把名次与列表顺序对齐，页面直接渲染；两边都钉住才叫闭环）
            const ranks = await hitRows.evaluateAll(rows =>
              rows.map(r => Number((r.querySelector('td')?.textContent ?? '').trim())),
            )
            expect(
              '名次从 1 开始且连续（它就是模型看到的名次）',
              ranks.length > 0 && ranks.every((r, i) => r === i + 1),
              ranks.join(','),
            )
          }
        }

        // 【0 命中的检索同样留痕，而且页面要说清是"没查到"】
        // 假数据里刻意造了一次 0 命中：它是最该被单独看见的一种检索
        // （白跑一轮照样花钱），而它混在"检索了 N 次"里完全看不出来
        expect('★ 一次都没查到的检索也被显示出来', /一条都没查到/.test(traceText))
        expect('检索汇总里单独给了空命中次数（没并进检索次数）', /空命中次数/.test(traceText))
        // 检索 token 的口径必须标明是估算 —— 它与成本看板那边上游 usage 不是一回事
        expect('检索 token 标明了是估算口径', /命中 token（估算）/.test(traceText))
        // 占输入比例：这一格回答"RAG 到底占了多大一块"，在此之前没有数据可答
        expect('显示了检索占输入的比例', /占输入比例/.test(traceText))
        // 会话那一层：假数据里有 3 次运行，且必须标出"正在看哪一次"
        expect('会话那一层列出了多次运行', /这段对话里一共 \d+ 次运行/.test(traceText))
        expect('★ 标出了「正在看」的是哪一次', /正在看/.test(traceText))
      }
    }

    // 关掉详情。
    //
    // 这里踩了一个**老坑**（「antd 给恰好两个汉字的按钮插了字间距空格」）：antd 6 会把恰好两个汉字的按钮渲染成
    // 「关 闭」（中间插一个空格），所以 `hasText: '关闭'` **永远匹配不上** ——
    // 而失败表现是"等按钮等到超时"，看起来像"详情卡里没有关闭按钮"。
    // 同族问题还有「纯文本里写 markdown 星号会原样显示」——**界面文案的一个渲染细节，
    // 能让一个看起来完全合理的定位永远失效**。
    await detailCard
      .locator('button')
      .filter({ hasText: /关\s*闭/ })
      .first()
      .click()
    await page.waitForTimeout(300)

    // 【★ 关掉详情之后 trace 也必须跟着消失】
    // 不消失的话，"关掉再点另一次运行"的瞬间会先渲染出上一条运行的 trace ——
    // 那种"闪一下旧数据"在排查时会被读成"这次运行查到了那些东西"
    expect(
      '关掉详情后 trace 卡一起消失（否则会显示上一条运行的数据）',
      (await page.locator('.diag-card', { hasText: '五层 trace' }).count()) === 0,
    )
  }
}

// —— 四层保护的状态 ——
const limitsCard = page.locator('.diag-card', { hasText: '四层保护' }).first()
expect('限额卡片存在', (await limitsCard.count()) > 0)
if ((await limitsCard.count()) > 0) {
  await limitsCard
    .locator('.rag-metric-table')
    .first()
    .waitFor({ state: 'visible', timeout: 30000 })
    .catch(() => {})
  await page.waitForTimeout(300)
  const limitsText = (await limitsCard.innerText()).trim()
  expect('显示了模型侧并发上限', /模型并发上限/.test(limitsText))
  expect('显示了工具侧并发上限', /工具并发上限/.test(limitsText))
  expect('显示了排队超时', /排队超时/.test(limitsText))
  // **"全局共享"这一句不能少**：不说的话读者会以为限额状态是某一次运行的属性
  expect('写明了限额是全局共享的', /全局共享/.test(limitsText))
  // 闸门与熔断并排 —— 它们是两种不同的病，分开放会让人看不出"这把工具现在能不能用"
  expect('闸门与熔断并排显示', /在途/.test(limitsText) && /熔断状态/.test(limitsText))
  expect('显示了被拒绝次数', /被拒绝/.test(limitsText))
  // 判据只有一份：只有"工具执行出错"计入失败率。
  // 断言用两句**原文**而不是宽松的词匹配 —— 一个只查"算失败/不算"的断言，
  // 在文案被改坏时照样会过，而它要保护的正是"这句话有没有被说清楚"
  expect('说明了只有执行出错计入熔断', /只有"工具执行出错"计入失败率/.test(limitsText))
  expect('说明参数错与限流拒绝都不算工具故障', /都\*\*不算\*\*工具故障|不算工具故障/.test(limitsText))
  // 两种拒绝的下一步动作相反，页面上必须分开说
  expect('区分了限流拒绝与熔断拒绝的下一步', /重试是有意义的/.test(limitsText) && /别再用/.test(limitsText))

  if (EXPECT_REFLECTION) {
    // 假数据刻意造了三种状态（健康 / 被限流 / 已熔断），界面上必须都能看出来。
    // 真后端刚启动时没有任何工具调用记录，所以"每把工具一行"这段表格是空的 ——
    // 那属于场景不成立，不是界面缺陷（空表时组件显示的是 Empty，而不是空白）
    expect('显示了熔断打开的工具', /已熔断/.test(limitsText))
    expect('显示了正常放行的工具', /正常放行/.test(limitsText))
    expect('显示了正在被限流的工具', /0\.75 \/ 0\.5/.test(limitsText))
  } else {
    // 真后端：验证"模型侧闸门已启用"这件事**在界面上可见** ——
    // 这条断言背后是一个真 bug：限流层曾经因为重复 bean 定义而压根没装上，
    // 而唯一能看出它的地方就是这个字段（详见 OpenAiModelTurn 类注释里那段）
    expect('模型侧闸门显示为已启用', !/模型侧并发闸门未启用/.test(limitsText))
    expect('显示了在途/上限', /在途/.test(limitsText))
  }

  await page.screenshot({ path: `${SHOT_DIR}/agent-limits.png`, fullPage: true })
}

const agentLayout = await page.evaluate(() => {
  const doc = document.documentElement
  return { horizontalOverflow: doc.scrollWidth - doc.clientWidth }
})
expect('Agent 面板无横向溢出', agentLayout.horizontalOverflow === 0, `overflow=${agentLayout.horizontalOverflow}px`)

// ---------- HITL：人工确认界面（阶段三第 9 件） ----------
//
// 这一节断言的是**"人能不能真的把那条写操作批掉"**，而不是"页面上有这几个字"：
//   - 待批的参数必须**原样可见** —— 人批的就是它，后端为此刻意不做脱敏与摘要；
//   - 「已等 / 还剩多久」必须显示 —— 审批对任务耗时的影响，唯一可量化的口径就是它；
//   - 批准之后那条待批必须**消失**，并且下一次任务**真的执行了写操作** ——
//     这是"闸门放行"与"闸门真的生效"的区别，也正是这一整轮要在界面上证明的东西；
//   - 拒绝之后**不能建单**，而理由必须原样回填给模型 ——
//     "人拒绝之后那个副作用真的没发生"是 HITL 唯一有意义的验收口径；
//   - 闸门关掉 / 没有待批时，空列表必须被解释清楚（否则"闸门没装"看起来像"系统很安全"）。
//
// 【为什么"批准/拒绝"这两条分支只在假后端跑】
// 真后端上没有待批时**根本没有可点的按钮** —— 那不是界面缺陷，是"这次还没有危险操作"。
// 要让它出现，得真的跑一次会触发写工具的 Agent 任务，而本机 key 是假的（401）。
// 所以真路径验证的是另一半、也是更该被验证的一半：**空列表必须被解释清楚**
// （"没有待批"有三种原因，混起来读会得出完全相反的结论）。
console.log('[6.7] HITL 人工确认界面')

/**
 * <b>"批准之前时间线在不在"</b> —— 由 HITL 那一节在动作**之前**写下来，
 * 给最后那条"切模式不丢结果"用。
 *
 * <h2>为什么要提前记，而不是用到的时候当场判断</h2>
 * 两条路径上这个前提本来就不同（假后端的任务有完整轨迹 → 时间线在；
 * 真后端假 key 直接 401 → 一次运行 0 步 → 时间线不渲染）。
 * 当场判断的话，同一段代码在两条路径上会走不同的分支 ——
 * 而**一个会随环境变化的断言条数，是没法写进文档的**
 * （阶段一就在这里含糊过一次，本项目把它记为"虚报"：写成"36 项"而实际只执行 29 项）。
 *
 * <p>提前记下来还有一个好处：它记的是**审批动作之前**的状态 ——
 * 而那正是"切模式不丢结果"要保护的东西（一次任务会真的花钱，丢了就得重跑）。
 */
let timelineWasThere = false

/**
 * 「跑一个任务」那张卡片。
 *
 * <h2>为什么这个定位器要在这里声明（而不是在它所属的那一节里）</h2>
 * 因为 HITL 那一节要用它做两件事：① 断言"待我确认"**排在它上面**
 * （有截止时间的东西优先于需要花钱的东西）；② 点它跑任务来验证
 * "批准之后写操作真的执行了"。
 *
 * <p>而"验证完整四步轨迹"的那一段被排在了 HITL **之后** —— 理由见上面那条指针注释：
 * 假后端现在按审批状态返回不同的轨迹，所以**顺序本身有依赖**。
 * 定位器提前声明出来，两节都能用，也就不会出现"谁先声明"这种与断言无关的耦合。
 */
const agentCard = page.locator('.diag-card', { hasText: '跑一个任务' }).first()

// ★ 两个归因维度的**输入框都必须存在**（阶段四第 6 件的前提）
//
// 【为什么这条断言比它看起来重要】归因维度是"没有入口就等于没有"的能力 ——
// 后端有字段、界面上不给入口的话，成本看板那两栏会永远只有「未标注」一行，
// 而那正是"这个能力只能靠 curl 用"的又一次重演（第九轮 HITL 踩过同一个形状）。
// 更要紧的是：**只给一个入口**（比如只加租户、忘了业务）在页面上完全没有症状，
// 只是看板少一栏的利用率 —— 所以两个都要点名断言。
{
  const labels = await agentCard.locator('.cost-intent-label').allInnerTexts()
  expect('★ 归因输入框有「业务归因」', labels.some(t => /业务归因/.test(t)),
    `—— 实际标签：${JSON.stringify(labels)}`)
  expect('★ 归因输入框有「租户」（第 6 件新增的第二个维度）', labels.some(t => /租户/.test(t)),
    `—— 实际标签：${JSON.stringify(labels)}`)
  const askText = await agentCard.innerText()
  expect('★ 界面写清了两个维度不是一回事（谁的钱 vs 花在哪个功能上）',
    /谁花的钱/.test(askText) && /哪个功能/.test(askText),
    '—— 只写两个输入框而不说区别，使用者会把租户填成业务名')
}

// ★ 本次新增的两个参数：工作区目录（必填）与权限档位
//
// 【为什么这两条断言比"页面上有两个控件"重要】它们与上面那两条归因断言同一个形状：
// 目录与档位是"没有入口就等于没有"的能力 —— 后端接了参数、界面上不给入口的话，
// 每一次运行都只会落默认值（无工作区 + 仅可查看），而**页面上看起来一切正常**。
// 更硬的一条是：档位决定了"写操作会不会停下来等人"，选错了没有任何报错，
// 只是那条人工闸门在你以为它开着的时候没开。
{
  // 两个控件存在
  expect('★ 写了「工作区目录」输入框（本次新增的必填参数）',
    (await agentCard.locator('[data-testid="agent-workspace-dir"]').count()) > 0)
  expect('★ 工作区是**必填**的，而且页面上写出来了',
    (await agentCard.locator('.agent-required').count()) > 0,
    '—— 后端会拒绝空值，而"没说必填"是那条错误唯一能预防的地方')

  // 三档权限都在，且每一档都带了"会发生什么"
  const permissionGroup = agentCard.locator('[data-testid="agent-permission-level"]')
  expect('★ 权限档位是一个三选一的单选组', (await permissionGroup.count()) > 0)
  const levelHints = await agentCard.locator('.agent-permission-hint').allInnerTexts()
  expect('★ 三档权限都在（仅可查看 / 工作区内修改 / 完全权限）',
    levelHints.length === 3
    && /检索/.test(levelHints[0] ?? '')
    && /工作区内/.test(levelHints[1] ?? '')
    && /直接执行/.test(levelHints[2] ?? ''),
    `—— 实际：${JSON.stringify(levelHints)}`)

  /*
    ★ 三档的差异必须**如实写出来**，而不是只给三个名字。

    【为什么这一条值得单独断言】「工作区内修改」这一档在当前功能下与「仅可查看」
    行为完全相同（本项目的工具没有路径参数，判不出"写在工作区内还是外"）。
    只给三个名字的话，使用者会以为它已经放宽了什么 —— 于是他会选一个自己以为更宽的档位，
    然后对"怎么还在弹审批"感到困惑，而那个困惑最后会被归因成权限功能坏了。
    所以断言的是"每一档都说清了后果"，而不是"有三个选项"。
  */
  const runScopeHint = levelHints[1] ?? ''
  expect('★ 「工作区内修改」这一档写明了它与「仅可查看」当前行为相同（不夸大）',
    /仍然要人点头|判不出|行为相同/.test(runScopeHint), `—— 实际：${runScopeHint}`)
  expect('★ 「完全权限」那一档写明了「写操作直接执行、不产生审核请求」（这一档的真实后果）',
    /直接执行/.test(levelHints[2] ?? '') && /审核请求/.test(levelHints[2] ?? ''),
    `—— 实际：${levelHints[2]}`)

  // 默认必须落在**最严**的一档：一个"默认就能跳过人工确认"的界面会让那条闸门多数时候不生效
  const defaultChecked = await permissionGroup.locator('input[type="radio"]:checked').count()
  const readOnlyChecked = await permissionGroup.locator('input[type="radio"][value="READ_ONLY"]:checked').count()
  expect('★ 默认档位是最严的「仅可查看」（不是完全权限）',
    defaultChecked === 1 && readOnlyChecked === 1,
    `—— 选中的单选框数=${defaultChecked}，其中 READ_ONLY=${readOnlyChecked}`)

  await page.screenshot({ path: `${SHOT_DIR}/agent-permission-controls.png`, fullPage: true })
}

// 假后端才有复位端点（真后端没有这个路径，调用会 404 —— 所以只在假路径调）
if (EXPECT_REFLECTION) {
  const reset = await page.request.post(`${URL}/api/agent/__reset-approval`).catch(() => null)
  expect('假后端支持把审批状态复位（否则两条相反的分支只能测一条）', !!reset && reset.ok())
}

// 【这里刻意**不**再点一次「Agent 工具调用」标签】
//
// 上一节刚跑完 `/ask` + 打开过历史详情 + 读过限额，面板与三张卡片都已经就位。
// 再点一次标签会让 Ant Design 的 Segmented 触发一次重挂载，而那样做的后果很具体：
// **历史运行与限额这两张卡片会短暂消失**（它们的 Remote 态被重置成 idle）——
// 于是紧接其后的"历史运行卡片存在 / 限额卡片存在"两条断言会红，
// 而失败信息看起来像"页面少了两块"，实际只是我们自己把它重新挂了一遍。
// 踩这一次的教训值得记：**在界面自检里，"多此一举地点一下"不是无害的**。
//
// ⚠️ 这里**不**再重复切一次面板：上面 [6.6] 那一节已经切过来了，
// 而且它之后紧接着就是"历史运行 / 运行详情 / 四层保护"三块 ——
// 那三块都建立在"面板已经切过来"这个前提上（它们是同一个面板的组成部分）。
// 一开始我把这一步也写在了这里，结果把它从 [6.6] 搬了过来、导致上面两块全红，
// 所以最后把它放回了 [6.6] 的开头（理由写在那里）。

const approvalCard = page.locator('.diag-card', { hasText: '待我确认' }).first()
expect('「待我确认」卡片已渲染', (await approvalCard.count()) > 0)
expect(
  '「待我确认」排在"跑一个任务"之前（有截止时间的东西优先）',
  (await approvalCard.count()) > 0 && (await agentCard.count()) > 0
    ? (await approvalCard.boundingBox()).y < (await agentCard.boundingBox()).y
    : false,
)

if ((await approvalCard.count()) > 0) {
  /*
    ⚠️ 这里**必须轮询等待，不能用固定 sleep**（实测踩到，值得记下来）。

    待办列表是**轮询**来的（每 2.5 秒一次，见 AGENT_APPROVAL_POLL_MS），
    而上面刚调过一次"复位"（把假后端的待批状态改回 PENDING）。
    这一节原来写的是 `waitForTimeout(700)` 然后立刻断言 —— 700ms 短于一个轮询间隔，
    所以它**跑得通还是跑不通取决于上一次轮询落在哪**：
    如果这个面板是刚切过来的（首拉刚发生过），700ms 后拿到的还是复位之前的那份数据，
    于是"显示了有写操作在等我"以及它下面那 9 条**整节全红**，
    而失败信息看起来像"待办卡片没渲染" —— 功能完全正常。

    这正是这个文件里反复记过的那类坑（钉住实验那次：
    **等待时长与它要等的东西没有关系**）。所以改成"轮询它出现"：
    假后端上它 2.5 秒内必然出现；真后端上待批列表本来就是空的，
    这次轮询等满超时后照常走下面那条分支（那一节验证的是"空列表必须被解释清楚"）。
  */
  if (EXPECT_REFLECTION) {
    const pendingArrived = await waitFor(async () => /在等我/.test(await approvalCard.innerText()), 5000)
    expect('待办列表把复位后的待批捡回来了（轮询在生效）', pendingArrived)
  }
  else {
    await page.waitForTimeout(700)
  }
  const apprText = (await approvalCard.innerText()).trim()
  // 闸门状态必须可见 —— 空列表的两种含义（没危险操作 / 闸门没开）必须能分开
  expect('显示了闸门开关状态', /闸门已启用|闸门已关闭/.test(apprText))
  expect('显示了等人时限', /时限\s*\d+s/.test(apprText))

  if (EXPECT_REFLECTION) {
    // ================= 假后端：验证"批掉它"这条完整路径 =================
    expect('显示了有写操作在等我', /在等我/.test(apprText))
    expect('显示了是哪个工具', /propose_review/.test(apprText))
    // 参数原样可见 —— 人批的就是这一份，界面上不许折叠成摘要
    expect('显示了将被执行的参数（原样）', /阶段二 · RAG：分块建议与实测结论相反/.test(apprText))
    // 风险声明来自工具的 ToolHints（与"要不要审批"的判据同源，不是前端另写一份）
    expect('显示了风险声明', /非幂等|需人工确认/.test(apprText))
    // 已等 / 还剩：这一块唯一的量化口径
    expect('显示了已等与剩余时间', /已等\s*\d+s/.test(apprText) && /还剩\s*\d+s/.test(apprText))
    // "超时按拒绝处理"必须写出来：这一块最容易出的错不是批错了，而是没人知道不理它会怎样
    expect('写明了超时按拒绝处理', /超时按.{0,2}拒绝.{0,2}处理/.test(apprText))
    // 两种决定对模型意味着相反的事，界面上必须分开说
    expect('说明了拒绝之后模型该做什么', /不要再重新发起/.test(apprText))

    // 决定人是**每条自己的**输入框：共用一个会让"写给 A 的理由"跑到 B 上，而且不报错
    const byInput = approvalCard.locator('[data-testid="approval-decided-by"]')
    expect('有"谁做的决定"输入框（审计要记人）', (await byInput.count()) > 0)
    if ((await byInput.count()) > 0) await byInput.first().fill('自检机器人')

    const rejectBtn = approvalCard.locator('button.approval-reject').first()
    const approveBtn = approvalCard.locator('button.approval-approve').first()
    expect('有批准按钮', (await approveBtn.count()) > 0)
    expect('有拒绝按钮', (await rejectBtn.count()) > 0)

    // ---- 先试一次"拒绝"（这一步验证的是"拒绝不是一键动作"）----
    //
    // 拒绝理由会**原样回填给模型**，是模型换方案的唯一依据。所以界面刻意不让人一键拒绝：
    // 第一次点只展开理由输入框，第二次点才真的提交。
    // 不这么做的话，页面上会稳定地产生一堆"没有理由的拒绝"，而那种审计记录几乎没有价值。
    if ((await rejectBtn.count()) > 0) {
      await rejectBtn.click()
      await page.waitForTimeout(300)
      // 断言"理由输入框出现了"而不是"页面上出现了『拒绝的理由』这几个字"。
      //
      // 【为什么不用文案】placeholder 是**辅助文本**，它在 DOM 里是属性、在
      // `innerText` 里**不出现** —— 拿 `innerText` 去匹配 placeholder 会永远为假，
      // 而失败信息看起来像"这个交互没做"。
      // 这条与本项目「antd 给恰好两个汉字的按钮插了字间距空格」（antd 把两个汉字渲染成「关 闭」）同族：
      // **界面文案的一个渲染细节，能让一个看起来完全合理的定位永远失效**。
      // 判据要落在**结构**上（有没有那个 textarea），而不是落在文案上。
      const rejectBox = approvalCard.locator('.approval-reject-box textarea')
      expect('第一次点「拒绝」只展开理由框，不直接提交', (await rejectBox.count()) > 0)
      // 同时再确认"理由框的提示文案确实在"——用 placeholder 属性读，而不是 innerText
      const placeholder = (await rejectBox.count()) > 0 ? await rejectBox.first().getAttribute('placeholder') : ''
      expect('理由框提示了它会回填给模型', /回填给模型/.test(placeholder ?? ''), placeholder ?? '(无)')
    }

    // ---- 批准它 ----
    await approveBtn.click()
    const decided = await waitFor(async () => {
      const t = await approvalCard.innerText()
      return /已批准/.test(t) || /没有被采纳/.test(t)
    }, 30000)
    expect('批准之后给了明确的结论条', decided)

    const afterText = (await approvalCard.innerText()).trim()
    // `awakened` / `resumed` 必须显示：这几种处境的下一步动作完全不同 ——
    // 有线程在等 → 任务当场跑完；没人等 → 从检查点接着跑；都没有 → 任务还停着
    expect('显示了"唤醒等待线程 / 从检查点接着跑"', /唤醒等待线程/.test(afterText))
    expect('给了这两个布尔的实际取值（而不是只有标题）', /从检查点接着跑：/.test(afterText))

    // 待批必须消失：它已经决过了
    const gone = await waitFor(async () => (await page.locator('.approval-item').count()) === 0, 15000)
    expect('决定之后那条待批从列表里消失了（轮询捡到了状态转移）', gone)

    // ---- 再跑一次任务：这次写操作**真的执行了** ----
    //
    // 这是整节最值钱的一条断言：它把"闸门放行"与"闸门真的生效"分开了 ——
    // 前者只是状态变了，后者是**那个副作用真的发生了**。
    await agentCard.locator('button', { hasText: /跑\s*一\s*次/ }).first().click()
    await agentCard.locator('.stat-cell', { hasText: '停因' }).first().waitFor({ state: 'visible', timeout: 150000 })
    await page.waitForTimeout(800)
    const approvedAsk = (await agentCard.innerText()).trim()
    expect('批准之后写操作真的执行了（出现成功建单的回填）', /工单已提交成功|RV-0001/.test(approvedAsk))
    expect('写操作那一步的 stage 显示为成功', /成功/.test(approvedAsk))
    expect('未再出现"停在等人确认"', !/正在等人确认/.test(approvedAsk))

    await page.screenshot({ path: `${SHOT_DIR}/agent-approval-approved.png`, fullPage: true })

    // ---- 复位 → 拒绝 → 验证"没执行" ----
    //
    // 「拒绝之后那个副作用真的没发生」是 HITL 唯一有意义的验收口径。
    // 在界面上它对应的是：模型收到一条**不可重试**的拒绝回填，并换了个说法回答。
    const reset2 = await page.request.post(`${URL}/api/agent/__reset-approval`).catch(() => null)
    expect('复位成功（第二次：走拒绝分支）', !!reset2 && reset2.ok())
    const backAgain = await waitFor(async () => (await page.locator('.approval-item').count()) === 1, 20000)
    expect('复位之后待批又出现了（轮询把它捡回来了）', backAgain)

    await approvalCard.locator('button.approval-reject').first().click()
    await page.waitForTimeout(300)
    await approvalCard.locator('.approval-reject-box textarea').first().fill('这条已经有对应的工单了，别重复建')
    await approvalCard.locator('button.approval-reject').first().click()
    const rejected = await waitFor(async () => {
      const t = await approvalCard.innerText()
      return /已拒绝/.test(t) || /没有被采纳/.test(t)
    }, 30000)
    expect('拒绝之后给了明确的结论条', rejected)
    expect('结论条写明"那个写操作不会执行"', /不会执行/.test(await approvalCard.innerText()))

    // 再跑一次：这次写操作**没有执行**，模型收到拒绝回填后换了做法
    await agentCard.locator('button', { hasText: /跑\s*一\s*次/ }).first().click()
    await agentCard.locator('.stat-cell', { hasText: '停因' }).first().waitFor({ state: 'visible', timeout: 150000 })
    await page.waitForTimeout(800)
    const deniedAsk = (await agentCard.innerText()).trim()
    expect('拒绝之后写操作没有建单（没有出现"提交成功"）', !/工单已提交成功/.test(deniedAsk))
    expect('显示了"人工拒绝"这个 stage', /人工拒绝/.test(deniedAsk))
    // 拒绝理由必须原样回填 —— 它是人给出的唯一一条新信息
    expect('拒绝理由原样回填给了模型', /别重复建/.test(deniedAsk))
    // 拒绝是"不要再试了"，与执行异常不同：回填里必须说的就是这句话
    expect('回填里带了"不要再重新发起"这句不可重试的说明', /不要再重新发起/.test(deniedAsk))


    // 【在这里记下"时间线在不在"】给最后那条"切模式不丢结果"用。
    //
    // 【为什么记的是此刻，而不是更早】踩过一次：第一版把它记在"批准之前"，
    // 而那时假后端返回的是"停在等人确认"分支（steps 为空）→ 记下来是 false。
    // 而走到最后那条断言时，"跑一个任务"已经被点过三次（三份不同的结果），
    // 于是"现在有没有时间线"与"记下来的那个"根本不是同一件事，断言必然误报。
    //
    // 规则一句话：**要跨段复用某个状态快照，就必须在"它已经稳定"的时刻取，
    // 而不是在"它恰好还没被后面几次动作改掉"的时刻取。**
    timelineWasThere = (await page.locator('.agent-timeline').count()) > 0

    // ---- 运行详情里的审批历史 ----
    //
    // 【为什么先复位】复位之后待批那条运行会被放进列表首位，于是"点第一行"点到的
    // 就是**确实有审批记录**的那一次 —— 否则这条断言只能写得很松（"有表格或有空状态"），
    // 而那种断言在两种情况下都通过，等于没测。
    await page.request.post(`${URL}/api/agent/__reset-approval`).catch(() => null)
    await runsCard.locator('button', { hasText: /刷\s*新/ }).first().click()
    await page.waitForTimeout(700)
    await runsCard.locator('.ant-table-tbody tr').first().click()
    const hitlDetail = page.locator('.diag-card', { hasText: '运行详情' }).first()
    await hitlDetail.waitFor({ state: 'visible', timeout: 30000 }).catch(() => {})
    await page.waitForTimeout(500)
    const hitlDetailText = (await hitlDetail.innerText()).trim()
    expect('运行详情里有"人工确认"这一节', /人工确认（HITL）/.test(hitlDetailText))
    expect('运行详情里有"人工确认"这一节', /人工确认（HITL）/.test(hitlDetailText))
    // 「谁决定的」与「等了多久」是审计闭环的两半：一个回答"谁批的"，一个回答"拖了多久"
    expect('详情里能看到"谁决定的"与"等了多久"', /谁决定的/.test(hitlDetailText) && /等了多久/.test(hitlDetailText))
    // 待批的 stage 也要在工具调用表里可见（否则"死在等人"这件事在详情里没有痕迹）
    expect('详情里能看到"等人工确认"这个 stage', /等人工确认/.test(hitlDetailText))
    expect('详情里说明了超时与人工拒绝为什么要分开', /流程没接上/.test(hitlDetailText))
    await page.screenshot({ path: `${SHOT_DIR}/agent-approval-detail.png`, fullPage: true })
    await hitlDetail
      .locator('button')
      .filter({ hasText: /关\s*闭/ })
      .first()
      .click()
    await page.waitForTimeout(300)
    // 最后复位一次，让下面的"模式往返"看到的是初始的待批状态
    await page.request.post(`${URL}/api/agent/__reset-approval`).catch(() => null)
  } else {
    // ================= 真后端：验证"没有待批时必须解释清楚" =================
    //
    // 真后端上没有待批 —— 那不是缺陷，而是"这次还没有危险操作"。
    // 但**空列表必须被解释**：它有三种原因（确实没有写操作 / 写操作还没被发起 / 闸门被关掉），
    // 混起来读会得出完全相反的结论 —— 把"闸门没装"看成"系统很安全"。
    expect('空列表时说明了它为什么是空的', /有三种原因|确实没有|闸门被关掉/.test(apprText))
    expect('说明了只读工具按规矩不需要审批', /只读/.test(apprText))
    expect('说明了闸门的当前状态', /当前闸门状态/.test(apprText))
    timelineWasThere = (await page.locator('.agent-timeline').count()) > 0
  }
}
if ((await agentCard.count()) > 0) {

// 【先把这个假后端的审批状态置成已批准】
//
// 这一段要验证的是**批准之后那条完整四步轨迹**（写操作执行成功 + 自愈过程）。
// 而 HITL 那一节最后停在"已拒绝"上（它刚验证完"拒绝之后不能建单"）。
// 若不再置一次，这里同样会落到"停在等人确认"分支 —— 那也是一种正确的行为，
// 但不是这一段要断言的东西。
//
// 为什么不"再点一次批准按钮"：那时界面上那条待批已经决过了，再点会被后端明确拒绝
// （"不能重复决定"），而真后端上那条路径根本不存在。自检要验证的是**行为**，
// 不是"想办法绕过我们自己的守卫"。
if (EXPECT_REFLECTION) {
  const setApproved = await page.request
    .post(URL + '/api/agent/__set-approval', { data: { decision: 'APPROVED' } })
    .catch(() => null)
  expect('把假后端置为已批准（让这一段的前提确定）', !!setApproved && setApproved.ok())
}

  // 整张页面上只有这一个按钮有外部代价，必须明确标出来：
  // 不标的话用户点它和点"刷新"的心理预期是一样的
  const askText = (await agentCard.innerText()).trim()
  expect('标出了"会调模型 · 花钱"', /花钱/.test(askText))

  // 跑一次。
  //
  // **等待方式按路径分**（这是实测出来的必要区分）：
  //   - 假后端：会给出一条"含失败与自愈"的四步轨迹，所以能等到 .agent-timeline；
  //   - 真后端：本机 key 是假的，模型调用会 401 失败 → 那次运行**一步都没有**，
  //     于是 .agent-timeline 根本不会渲染（组件在 steps 为空时返回 null）。
  //     所以真路径要等"停因"那一格出现 —— 它在成功与失败两种情况下都会有。
  //
  // 这不是"把断言放宽"，而是**两条路径本来就跑在不同的场景里**：
  // 假后端能确定性地产出完整轨迹，真后端（假 key）只能产出一次分类明确的失败。
  // 硬等 .agent-timeline 会让真路径必然超时，而失败原因看起来像"界面没渲染"。
  //
  // ★ 顺手抓一次**出站请求体**（阶段四第 6 件的做法，与灰度那次"抓出站提示词"同一思路）：
  // "两个输入框存在"证明不了"它们被发出去了" —— 中间隔着一次字段名拼接，
  // 而字段名写错的症状是"看板上那一栏永远只有未标注"，看起来像"没人填过"。
  // 判据必须是**请求体里真的有那两个键**，而不是"页面上有那两个框"。
  const askRequest = page.waitForRequest(
    r => r.url().includes('/api/agent/ask') && r.method() === 'POST',
    { timeout: 20000 },
  )
  await agentCard.locator('button', { hasText: /跑\s*一\s*次/ }).first().click()
  try {
    const body = JSON.parse((await askRequest).postData() ?? '{}')
    expect('★ 出站请求体里有 tenantId 这个键（不是只画了一个输入框）',
      Object.prototype.hasOwnProperty.call(body, 'tenantId'), `—— body=${JSON.stringify(body).slice(0, 160)}`)
    expect('★ 出站请求体里的 tenantId 有值（默认值会真的被发出去）',
      typeof body.tenantId === 'string' && body.tenantId.length > 0, `tenantId=${body.tenantId}`)
    expect('★ 出站请求体里业务归因与租户是两个**独立**的键（不是拼成一个字符串）',
      Object.prototype.hasOwnProperty.call(body, 'intent') && body.intent !== body.tenantId,
      `intent=${body.intent} tenantId=${body.tenantId}`)

    /*
      ★ 本次新增的两个参数也必须在出站请求体里。

      【为什么判据是"请求体里有这个键"，而不是"页面上有这个框"】
      中间隔着一次字段名拼接（`workspaceDir` / `permissionLevel`），而字段名写错的症状是：
      后端认为"没传"→ 走默认值（无工作区 + 仅可查看）→ 界面上一切正常。
      特别是档位：拼错时后端会**拒绝**（认不出的取值一律拒），而那只在点了按钮之后才现形。
      所以这里钉的是"界面填的东西真的发出去了"这一跳。
    */
    expect('★ 出站请求体里有 workspaceDir 这个键（本次新增的必填参数）',
      Object.prototype.hasOwnProperty.call(body, 'workspaceDir'),
      `—— body=${JSON.stringify(body).slice(0, 200)}`)
    expect('★ 出站请求体里的 workspaceDir 有值（默认值会真的被发出去）',
      typeof body.workspaceDir === 'string' && body.workspaceDir.trim().length > 0,
      `workspaceDir=${JSON.stringify(body.workspaceDir)}`)
    expect('★ 出站请求体里有 permissionLevel 这个键，且是后端认识的三档之一',
      ['READ_ONLY', 'WORKSPACE_WRITE', 'FULL_ACCESS'].includes(body.permissionLevel),
      `permissionLevel=${JSON.stringify(body.permissionLevel)}`)
    expect('★ 权限档位的默认值是 READ_ONLY（与界面上选中的那一档一致）',
      body.permissionLevel === 'READ_ONLY', `permissionLevel=${body.permissionLevel}`)

    /*
      ★ 执行者（本次新增）也必须在出站请求体里。

      【为什么判据是"请求体里有这个键"而不是"页面上有那一行"】
      与上面两条一字不差：中间隔着一次字段名拼接（`executorId`），而字段名写错的症状是
      **后端认为"没指定执行者" → 一条技能都不注入**，而页面上一切正常
      （"没有技能"与"技能没生效"长得一模一样）。
      这里钉的是"界面显示的那个身份真的发出去了"这一跳。

      <p>⚠️ 值在本节刻意断言成 null：这一节跑在 [11] 之前，而**登录态存的是 localStorage、
      App 挂载时只读一次** —— 也就是说"往 localStorage 里写一条记录"不会让这一页变成登录态。
      所以这里的正确期望值是"没登录 → 不带执行者"，而不是"应该等于某个人的 id"。
      登录态下的那一条断言在 [11]（那里会真的点开弹窗选一个人）。
    */
    expect('★ 出站请求体里有 executorId 这个键（不是只在界面上画了一行字）',
      Object.prototype.hasOwnProperty.call(body, 'executorId'),
      `—— body=${JSON.stringify(body).slice(0, 240)}`)
    expect('★ 未登录时出站请求体里的 executorId 是 null（不偷偷用一个默认的人）',
      body.executorId === null, `executorId=${JSON.stringify(body.executorId)}`)
  }
  catch (e) {
    expect('抓到了 /api/agent/ask 的出站请求体（用来核对归因与权限字段）', false, String(e))
  }
  await agentCard.locator('.stat-cell', { hasText: '停因' }).first().waitFor({ state: 'visible', timeout: 150000 })
  await page.waitForTimeout(800)

  const resultText = (await agentCard.innerText()).trim()
  expect('显示了停因与成本', /停因/.test(resultText) && /token/.test(resultText))
  expect('显示了工具调用汇总', /工具调用/.test(resultText))

  /*
    ★ 本次运行的执行前提必须回显出来（结果卡上那一行）。

    【它为什么必须有】在「完全权限」档位下写操作**不产生审核请求** ——
    而"待我确认"那张卡片为空时，人无法区分"这次没有写操作"与"这一次被放宽了"。
    这一行就是那个区分。所以断言"结果卡里有这一行"，而不是"界面上有这个控件"：
    前者证明的是**服务端实际用的那份前提**被显示了。
  */
  expect('★ 结果卡回显了本次运行的执行前提（工作区 + 档位）',
    /执行前提/.test(resultText) && /仅可查看|工作区内修改|完全权限|未记录/.test(resultText),
    `—— 页面文本片段：${resultText.slice(0, 240).replace(/\n/g, ' | ')}`)

  /*
    ★ 本次注入的技能必须显示出来（本次新增）。

    【为什么这一块必须回显，而不是只写进后端日志】技能目录是按执行者**动态**拼进系统提示词的，
    而提示词指纹覆盖不到它 —— 于是"模型没按技能做"与"技能压根没注入"
    在别的任何地方都长得一模一样。这一块就是那个区分。

    <p>判据是"它说的是 0 条 + 原因"，不是"这一块存在"：未登录时后端一条都不注入，
    而界面上必须把这句**原因**写出来（页头去登录）—— 只显示一个空的技能列表的话，
    "没指定执行者"与"他的目录下没有技能"会被读成同一件事，而两者的下一步动作不同。
  */
  const skillsBlock = agentCard.locator('[data-testid="agent-skills"]')
  expect('★ 结果卡上有「本次注入的技能」这一块（技能对模型的影响有唯一的凭据）',
    (await skillsBlock.count()) > 0)
  if ((await skillsBlock.count()) > 0) {
    const skillsText = (await skillsBlock.innerText()).trim()
    const shownCount = (await skillsBlock.locator('[data-testid="agent-skill-count"]').innerText()).trim()
    if (EXPECT_REFLECTION) {
      expect('★ 未登录时注入 0 条技能，并写出了原因（去页头模拟登录）',
        /^0\s*条$/.test(shownCount) && /模拟登录/.test(skillsText),
        `计数=${shownCount} 文本=${skillsText.replace(/\n/g, ' | ').slice(0, 160)}`)
    }
    else {
      // 真后端：这一次跑在未登录（没带 executorId）下，所以同样一条都不该注入；
      // 但库里可能已经有技能 —— 那也不能注入，因为**没有执行者就没有候选范围**
      expect('★ 真后端上"没有执行者"同样一条都不注入（不是把所有技能都塞进去）',
        /^0\s*条$/.test(shownCount) && /模拟登录/.test(skillsText),
        `计数=${shownCount} 文本=${skillsText.replace(/\n/g, ' | ').slice(0, 160)}`)
    }
  }

  // 「停因」这一格在每个路径上都该是**真实的枚举名**，而不是空 ——
  // 它是"这一页回答了为什么"的最小证据
  expect('停因是真实的枚举名', /ANSWERED|MODEL_ERROR|MAX_STEPS|MAX_TOKENS|MAX_DURATION|DUPLICATE_ACTION|EMPTY_RESPONSE/.test(resultText))

  if (EXPECT_REFLECTION) {
    // ================= 假后端路径：验证完整的执行过程 =================
    expect('显示了最终答案', (await agentCard.locator('.agent-answer-text').count()) > 0)

    const steps = await agentCard.locator('.agent-step').count()
    expect('渲染了执行过程时间线', steps >= 3, `${steps} 步`)
    expect('每步标出了"调工具"还是"作答"', /调工具/.test(resultText) && /作答/.test(resultText))

    const toolCalls = await agentCard.locator('.agent-tool-call').count()
    expect('渲染了每次工具调用', toolCalls >= 3, `${toolCalls} 次`)
    expect('显示了模型的 think 段', (await agentCard.locator('.agent-thought').count()) > 0)
    // 回填文本是"模型当时看到了什么"的唯一凭据，必须能被看到
    expect('显示了回填给模型的内容', (await agentCard.locator('.agent-tool-feedback pre').count()) >= 3)
    // 被拒绝的那次必须也在（否则"想调几次"这个数字永远偏小）
    expect('被拒绝的调用也在页面上', /工具不存在/.test(resultText))
    expect('回填里带了可用工具列表', /可用工具/.test(resultText))
    // stage 用中文短语显示，但枚举名要能查到（放在 title 里）—— 排查时要能和日志对上
    expect('stage 的枚举名可查到', (await agentCard.locator('[title="UNKNOWN_TOOL"]').count()) > 0)
    expect('工具调用的参数可见', (await agentCard.locator('.agent-tool-args code').count()) >= 3)
  } else {
    // ================= 真后端路径（假 key）：验证失败被如实说明 =================
    //
    // 这条路径**恰好是"失败细分"那个功能的真实验证**：本机 key 是假的 →
    // 上游会拒绝 → 界面必须把细分原因与处置建议都显示出来，而不是笼统一句"模型出错"。
    expect('把失败原因分类显示出来了', /UPSTREAM_AUTH|MODEL_ERROR|UNEXPECTED/.test(resultText))
    //
    // ⚠️ 判据**不能**写死某一种失败的措辞。第一版写的是
    // `/重试没有意义|下一步|API Key|鉴权/`，理由是"假 key → 401 → 必然是 UPSTREAM_AUTH 那条建议"。
    // 实测这次分到的是 **UNEXPECTED**（上游 401 经 SDK 包装后没被认成鉴权失败），
    // 它给出的建议是「没能归类的异常 —— 这是一个信号：分类逻辑需要补一种情况。请直接看日志堆栈。」
    // —— 那是一句**完全正确且可操作**的建议，而断言却红了。
    //
    // 也就是说：这条断言原本测的是"后端分类得准不准"（那是 `AgentFailureClassifier` 的活），
    // 而不是"界面有没有把建议渲染出来"（这才是这一节要钉的）。
    // 改成本节真正要保证的性质：**建议这一格非空，而且是一句能照着做的话**。
    // 六个分类的措辞各不相同，但它们都含有"建议/重试/检查/看日志"这类动作词。
    const adviceShown = /建议|重试|检查|看日志|看.*堆栈|调大|调小|换一个/.test(resultText)
    expect('显示了对这一页失败的处置建议（非空且可操作）', adviceShown,
      adviceShown ? '' : `没找到可操作的建议；页面文本片段：${resultText.slice(0, 200).replace(/\n/g, ' | ')}`)
    // 没有答案时不能显示"最终答案"那一块 —— 空答案框会让人以为"答了但是空的"
    expect('没有答案时不显示答案块', (await agentCard.locator('.agent-answer-text').count()) === 0)
  }

  await page.screenshot({ path: `${SHOT_DIR}/agent-ask.png`, fullPage: true })
}

// ---------- 成本看板（阶段四 · 工程化） ----------
//
// 【这一节最值钱的那条断言是"不许出现 ¥0.0000"】
// 假数据里刻意有一个模型没有价格（deepseek-v4-pro）。按"看起来对"的做法，
// 那一行的金额会被渲染成 0.0000 —— 而那个 0 会被读成"这个功能不花钱"，
// 真相是"我们不知道它花多少钱"。这两句话对使用者的含义完全相反，
// 而界面上长得一模一样。所以这一节的核心断言是**负向的**：
// 页面上必须出现"算不出"与原因，而**不能**出现把未知渲染成 0 的那种形态。
//
// 【两条路径的断言条数刻意保持一致，但验的不是同一件事】
//   假后端：有数据、有价格、有"算不出"的那一行 → 验证四个视图 + 金额纪律；
//   真后端：**全新的库**（没有任何运行记录）且**价格没配** → 验证的是另一件事，
//           而它恰好是这一页**第一次被人打开时的样子**：
//           不能显示 ¥0.0000，必须显示"算不出"+ 一句能照着做的配置说明。
// 真路径反而把那条纪律验得更彻底 —— 因为那里连一条能算出钱的记录都没有。
console.log('[6.8] 成本看板')

await navMode('成本看板')
const costHead = page.locator('.cost-head', { hasText: '成本看板' })
// 进面板会自动拉一次（只读、零外部代价），但要等它回来
const costLoaded = await waitFor(async () => (await page.locator('.cost-headline').count()) > 0, 15000)
expect('进面板自动加载了看板', costLoaded)
await page.waitForTimeout(500)

// 价格表、按天、按模型这三张卡在两条路径上都存在（只是内容不同）
const priceCard = page.locator('.diag-card', { hasText: '价格表：这一页的钱是按这些单价算出来的' })
const daysCard = page.locator('.diag-card', { hasText: '按天：钱是怎么涨上去的' })
const modelCard = page.locator('.diag-card', { hasText: '按模型：换成别的模型会差多少' })
// 【两个归因维度各一张卡，标题刻意不同】阶段四第 6 件把「按业务归因：钱是谁花的」
// 拆成了两栏 —— 旧标题（"钱是谁花的"）留在业务那一栏是**错的**：
// 那句话现在归租户那一栏，而两栏标题一样的话，读者会以为它们是同一个东西的两种排法
const tenantCard = page.locator('.diag-card', { hasText: '按租户：钱是谁花的' })
const intentCard = page.locator('.diag-card', { hasText: '按业务归因：钱花在哪个功能上' })
const toolCard = page.locator('.diag-card', { hasText: '哪把工具在拖慢任务' })
const notesCard = page.locator('.diag-card', { hasText: '这份数据能说什么、不能说什么' })

// 从一段文本里读出某个指标格子的数字。
// ⚠️ 必须吃掉千分位逗号：大数在页面上是「1,234」，只匹配 \d+ 会读出 1 而不是 1234 ——
// 而那种误读的失败信息看起来像"两边对不上"，会把人引到错误的方向去查
function statNumber(text, label) {
  const raw = (text.match(new RegExp(`${label}\\s*([\\d,]+)`)) ?? [])[1]
  return raw === undefined ? -1 : Number(raw.replace(/,/g, ''))
}

// ---- 两条路径共用的断言 ----
const costText = await costHead.innerText()
expect('显示了运行次数', /运行次数/.test(costText))
expect('失败的运行被单独显示（花了钱又没办成事 —— 账单里看不到这一格）', /失败的运行/.test(costText))
// 【被上限拦下的那些必须自己占一格】它既不是成功也不是失败，但同样花了钱 ——
// 并进任何一边都会让那边的数字说谎（详见 CostPanel 里那段注释）
expect('被上限拦下的运行单独占一格', /被上限拦下/.test(costText))
// 【★ 这一条钉的是一个真 bug：合计的运行级计数取错了来源】
// 合计曾经用"按模型那一组"的和，而那个维度里 finished/failed 是写死的 0 ——
// 于是真后端上「已结束」永远是 0（假数据里写的是正确的和，所以只在这条路径上现形）。
// 判据刻意写成"两个数字必须相等"而不是"已结束大于 0"：
// 后者在空库的真后端上会红，而空库时 0 是正确的。
{
  const dayFinished = await daysCard.evaluate(card =>
    [...card.querySelectorAll('tbody tr')].reduce((sum, tr) => {
      const cell = tr.querySelector('td:nth-child(3)') // 按天表第 3 列是「已结束」
      return sum + (cell ? Number(cell.textContent.trim()) || 0 : 0)
    }, 0))
  const headlineFinished = statNumber(costText, '已结束')
  expect(
    '合计的「已结束」等于按天表的和（取错来源时这里会是 0）',
    headlineFinished === dayFinished,
    `合计 ${headlineFinished} vs 按天之和 ${dayFinished}`,
  )
  // 同理钉住"被上限拦下"：它也必须两边一致，而且它在假数据里刻意非 0
  // （恒为 0 的假数据会让那一格永远看起来"从来没被拦下过"）
  const dayStopped = await daysCard.evaluate(card =>
    [...card.querySelectorAll('tbody tr')].reduce((sum, tr) => {
      const cell = tr.querySelector('td:nth-child(4)') // 第 4 列是「被上限拦下」
      return sum + (cell ? Number(cell.textContent.trim()) || 0 : 0)
    }, 0))
  const headlineStopped = statNumber(costText, '被上限拦下')
  expect('「被上限拦下」的合计等于按天表的和', headlineStopped === dayStopped,
    `合计 ${headlineStopped} vs 按天之和 ${dayStopped}`)
}
expect('总 token 已显示', /总 token/.test(costText))
expect('缓存命中率已显示', /缓存命中/.test(costText))
expect('价格表卡已渲染', (await priceCard.count()) > 0)
expect('按天卡已渲染', (await daysCard.count()) > 0)
expect('按租户卡已渲染（第 6 件新加的那一维）', (await tenantCard.count()) > 0)
expect('按业务归因卡已渲染', (await intentCard.count()) > 0)
expect('按模型卡已渲染', (await modelCard.count()) > 0)
expect('按工具卡已渲染', (await toolCard.count()) > 0)

// ★ 跨路径一致性：两个归因维度与分摊口径必须**在后端真的存在**
//
// 【这一条为什么必须打端点，而不是只看页面】
// 页面上"这一栏是空的"与"后端压根没有这个字段"长得一模一样（都是没有行）。
// 而第 15 轮刚吃过这个形状的亏（「默认参数里的字符串两边各写一份，真后端 400 而假后端全绿」）：假后端照着真后端一个写错的默认值抄了一遍，
// 于是 6 组断言全绿、真后端上一行都渲染不出来。
// 判据写成"两个维度都在响应里、且金额都带口径标记"——
// 它在**假后端上也会红**（假后端要写同样的字段），于是下一次任何一边改错都当场现形。
{
  const shape = await page.evaluate(async () => {
    const d = await fetch('/api/cost/summary?days=7').then(r => r.json())
    return {
      ok: d.ok === true,
      hasTenant: Array.isArray(d.byTenant),
      hasIntent: Array.isArray(d.byIntent),
      // 有数据时：两个维度都必须给金额（第 6 件起是分摊值，不再是 null），
      // 而且必须带 amountBasis —— 界面靠它决定"这一格的钱是怎么来的"那句说明
      tenantRows: (d.byTenant ?? []).length,
      tenantBasis: (d.byTenant ?? []).map(r => r.amountBasis),
      intentBasis: (d.byIntent ?? []).map(r => r.amountBasis),
      // 合计的金额（用来核对"各栏加起来等于合计"）
      totalMicros: d.totals?.amountMicros ?? null,
      tenantMicrosSum: (d.byTenant ?? []).reduce((s, r) => s + (r.amountMicros ?? 0), 0),
      intentMicrosSum: (d.byIntent ?? []).reduce((s, r) => s + (r.amountMicros ?? 0), 0),
    }
  })
  expect('★ /api/cost/summary 有 byTenant 这一维（不是只有 byIntent）', shape.hasTenant && shape.hasIntent,
    `byTenant=${shape.hasTenant} byIntent=${shape.hasIntent}`)
  if (EXPECT_REFLECTION) {
    expect('★ 两个归因维度的每一行都带金额口径（ALLOCATED_BY_TOKEN_SHARE）',
      shape.tenantBasis.length > 0 && shape.tenantBasis.every(b => b === 'ALLOCATED_BY_TOKEN_SHARE')
        && shape.intentBasis.length > 0 && shape.intentBasis.every(b => b === 'ALLOCATED_BY_TOKEN_SHARE'),
      `tenant=${JSON.stringify(shape.tenantBasis)} intent=${JSON.stringify(shape.intentBasis)}`)
    // 这一条与后端 CostDashboardViewTest 里那条是同一个判据的两条路径：
    // 后端保证"分摊的最后一行取合计减前面所有行"，这里核对它真的成立
    expect('★ 按租户那一栏各行相加 == 合计（分摊的最后一行吸收了舍入差）',
      shape.tenantMicrosSum === shape.totalMicros,
      `租户和 ${shape.tenantMicrosSum} vs 合计 ${shape.totalMicros}`)
    expect('★ 按业务那一栏各行相加 == 合计（两个维度是同一份钱的两个视图）',
      shape.intentMicrosSum === shape.totalMicros,
      `业务和 ${shape.intentMicrosSum} vs 合计 ${shape.totalMicros}`)
  }
}

// 纯文本里的 markdown 星号不能以字面量出现（「纯文本里写 markdown 星号会原样显示」同族）。
// ⚠️ 这条**在真后端路径上曾经红过**：后端 notes 里写的 `**加粗**` 在界面上真的印出了星号，
// 因为那段文本有两个消费方（JSON 接口 / 页面的纯文本渲染），而只有后者在意星号。
// 修法是源头不写星号，而不是在界面上过滤（那样接口的读者会拿到被改过的文本）
const costPanelText = await page.locator('.diag-panel').first().innerText()
expect('看板正文无字面量星号', !costPanelText.includes('**'))

if (EXPECT_REFLECTION) {
  // ================= 假后端：有数据、有价格 =================
  expect('合计金额已渲染', /¥[\d.]+/.test(costText), (costText.match(/¥[\d.]+/) ?? [''])[0])
  const dayRows = await daysCard.locator('tbody tr').count()
  expect('按天表有数据行', dayRows >= 1, `${dayRows} 行`)

  // ---- 核心：未配价的模型必须显示「算不出」而不是 0 ----
  const modelText = await modelCard.innerText()
  expect('未配价的模型显示「算不出」而不是 0', /算不出/.test(modelText))
  expect('并且给出了算不出的原因（能照着去补价格）', /没有匹配的价格规则|补一条/.test(modelText))
  expect('有模型没价时，合计被标成下界', /下界/.test(await page.locator('.cost-needs-alert').first().innerText()))
  const unknownRows = await page.locator('.cost-amount-unknown').count()
  expect('确实有金额格被渲染成「算不出」形态', unknownRows > 0, `${unknownRows} 处`)
  // 原因必须是**可见文本**，不能只藏在悬浮提示里：
  // 「为什么算不出」就是这一格的全部信息，而藏起来的信息只有已经知道它存在的人才会去找。
  // （顺带记一个实测的坑：给 antd <Tooltip> 的直接子元素传 title，那个 title 不会落到 DOM 上 ——
  //  Tooltip 会把它吃掉当自己的内容。第一版断言"元素带 title 属性"因此是红的，而功能其实在）
  expect('「算不出」的原因以可见文本给出（不是只在悬浮提示里）', (await page.locator('.cost-amount-reason').count()) > 0)

  // ---- 归因 / 工具 / 对账 ----
  //
  // ★ 两个归因维度**各验一遍**，而且验的是各自不同的东西：
  //   租户那一栏：空值的名字必须是「未标注租户」（与业务那一栏的「未标注」不同）
  //   业务那一栏：金额不再是「算不出」，而是带口径的分摊值
  const tenantText = await tenantCard.innerText()
  expect('按租户表有数据行', (await tenantCard.locator('tbody tr').count()) >= 1)
  expect('★ 租户那一栏的空值叫「未标注租户」—— 与业务那一栏的「未标注」刻意不同',
    /未标注租户/.test(tenantText),
    '—— 两个维度的空长得一样的话，读者会以为它们是同一个空洞，' +
      '而处置方式完全不同（让调用方传 tenantId / 传 intent）')
  expect('租户那一栏说明了「未标注租户」与「未标注」是两件事', /两件事/.test(tenantText))
  expect('★ 两栏都写明了金额是「按 token 占比分摊」的（口径与数字同屏）',
    /分摊/.test(tenantText) && /分摊/.test(await intentCard.innerText()))

  const intentText = await intentCard.innerText()
  expect('按业务归因表有数据行', (await intentCard.locator('tbody tr').count()) >= 1)
  expect('未标注的运行单独成一行（没被并进任何业务里）', /未标注/.test(intentText))
  expect('界面说明了「未标注」不该被并进业务里', /没有.*被并进|虚高/.test(intentText))
  // 【第 6 件把这一格的语义改了】以前它是「算不出」（与按模型那一栏同样处理），
  // 现在它是一个分摊值 —— 所以这里断言**这一栏里出现了金额数字**。
  // 不断言这一条的话，"后端忘了把 amount 填上"会以"界面显示算不出"的形式静默通过
  // （那一格从前就长这样，所以没人会觉得不对）
  //
  // ⚠️ 判据刻意**读 DOM 的 textContent**，而不是对 innerText 做正则 ——
  // 实测踩到：`innerText` 会按**渲染后的换行**插入空白，而这一页的金额格很窄，
  // 于是「¥0.0753」在 innerText 里可能是「¥0.0\n753」（数字被折成两半）。
  // 对 innerText 写 `/¥[\d.]+/` 因此会**在功能正常时报红**，而失败信息看起来像
  // "界面没渲染金额" —— 一个把排查引向错误方向的假红。
  // textContent 不受渲染换行影响，它读的就是那一格的内容
  const intentAmounts = await intentCard.locator('.cost-amount').allTextContents()
  expect('★ 按业务那一栏出现了金额数字（不再与按模型那一栏一样显示「算不出」）',
    intentAmounts.some(t => /¥\s*\d/.test(t.replace(/\s+/g, '')) || /^[¥$]/.test(t.replace(/\s+/g, ''))),
    `—— 业务栏金额格：${JSON.stringify(intentAmounts)}`)

  const toolText = await toolCard.innerText()
  expect('按工具表有数据行', (await toolCard.locator('tbody tr').count()) >= 1)
  expect('工具那一栏明确标注了没有金额', /没有金额/.test(toolText) || /不直接消耗 token/.test(toolText))
  expect('模型编错的工具名也在这一栏', /doc_search/.test(toolText))
  expect('被限流拒绝与被熔断拒绝分列（它们要给模型相反的建议）', /被限流拒绝/.test(toolText))

  const reconCard = page.locator('.diag-card', { hasText: '口径对账' })
  const reconText = await reconCard.innerText()
  expect('对账块已渲染（两个口径并排）', /步骤表 token/.test(reconText) && /运行行 token/.test(reconText))
  expect('对账不一致时明确报出来（假数据刻意差 1200）', /差|对不上/.test(reconText))
  expect('一步都没有的运行被单独数出来', /一步都没有的运行/.test(reconText))

  const notesText = await notesCard.innerText()
  expect('写明了 token 不是估算的', /不是估算/.test(notesText))
  expect('写明了库是 MySQL、记录跨重启保留', /MySQL/.test(notesText))

  // ---- 时间窗切换会真的换数据 ----
  const beforeSwitch = dayRows
  await page.locator('.cost-head .ant-segmented-item', { hasText: '30 天' }).click()
  const switched = await waitFor(
    async () => (await page.locator('.cost-headline').count()) > 0 && !/正在汇总/.test(await costHead.innerText()),
    15000,
  )
  expect('切到 30 天后仍然正常渲染', switched)
  expect(
    '切换后按天表仍有数据',
    (await daysCard.locator('tbody tr').count()) >= beforeSwitch,
  )
  expect('价格表有数据行', (await priceCard.locator('tbody tr').count()) >= 1)
  expect('没配缓存价的那条被标成「假设」而不是「事实」', /假设/.test(await priceCard.innerText()))
} else {
  // ================= 真后端：全新的库 + 价格没配 =================
  //
  // 这条路径验的是"这一页第一次被人打开时的样子"。它比假后端那条**更严格**：
  // 那里至少有能算出钱的记录，而这里一条都没有 ——
  // 于是"算不出"这条纪律是在**没有任何数字可依赖**的情况下被检验的。
  expect('没有价格时不显示金额，而是明确说「算不出」', /算不出金额/.test(costText))
  expect('并给出了原因（能照着去配价格）', /价格未配置|ai\.cost\.prices/.test(costText))
  // 按天那一格：有记录就是表格行、没记录就是一句可操作的空状态。
  // **两种形态都合法**，而"显示一个 0 让人以为没花钱"是唯一不合法的那种
  const realDayRows = await daysCard.locator('tbody tr').count()
  const realDaysText = await daysCard.innerText()
  expect(
    '按天那一格要么有数据行、要么给出可操作的空状态（不是静默的 0）',
    realDayRows > 0 || /一次运行都没有|跑一个任务/.test(realDaysText),
    realDayRows > 0 ? `${realDayRows} 行` : '空状态',
  )
  const emptyPrices = await priceCard.innerText()
  expect('价格卡给出配置写法（那是一份待办）', /ai\.cost\.prices/.test(emptyPrices))
  expect('空价格表说明「刻意不给 0」的理由', /不花钱|不知道/.test(emptyPrices))
  const reconText = await page.locator('.diag-card', { hasText: '口径对账' }).innerText()
  expect('对账块仍然渲染（没有数据时也要显示两个 0，而不是藏起来）', /步骤表 token/.test(reconText))
  const notesText = await notesCard.innerText()
  // ⚠️ 这一条第一版写的是"断言缓存那一栏说『无从谈起』"，而它**在真后端上会闪红** ——
  // 因为真后端的形态取决于这次跑批里 `/ask` 有没有落下步骤：
  //   一次成功往返都没有 → 「窗口内没有 token 记录，命中率无从谈起」；
  //   有任何一步落了库   → 「整个窗口的缓存命中都是 0，有两种可能」。
  // 两种都是**正确**的文案，所以断言任何一个都不该写死 ——
  // 真正该被钉住的是"金额不许变成 0"（上面那两条），而不是备注里挑了哪句措辞。
  expect('边界说明随数字一起交付（不是只有数字）', (await notesCard.locator('.cost-notes li').count()) >= 3)
  expect('边界说明里有"库是 MySQL"这条提醒', /MySQL/.test(notesText))
  expect('价格表卡在真后端也渲染了（空表也要有说明）', (await priceCard.count()) > 0)
}

// ---- 布局体检（两条路径共用，代替肉眼） ----
const costLayout = await page.evaluate(() => {
  const doc = document.documentElement
  const card = document.querySelector('.cost-head')
  return {
    horizontalOverflow: doc.scrollWidth - doc.clientWidth,
    cardFitsViewport: card ? card.getBoundingClientRect().right <= doc.clientWidth + 1 : null,
  }
})
expect('成本看板无横向溢出', costLayout.horizontalOverflow === 0, `overflow=${costLayout.horizontalOverflow}px`)
expect('成本看板头部不超出视口', costLayout.cardFitsViewport !== false)
await page.screenshot({ path: `${SHOT_DIR}/cost-dashboard.png`, fullPage: true })


// ---------- Prompt 版本管理（阶段四第 3 件，第七种模式） ----------
//
// 【两条路径验的不是同一件事，条数刻意保持一致】
//   假后端：prompt-routes.mjs 是**有状态**的（四个写端点会真的改内存里的版本表），
//           所以这里跑「被拒 → 存 → 生效 → 回滚 → 再回滚（noOp）」的完整往返。
//           这是这一页**唯一**能验证"点一下之后状态真的变了"的地方；
//   真后端：它跑在真库上，而"往真库里塞一版再切过去"会**改掉这个实例的运行时行为**
//           （后面真有东西跑 Agent 时用的就是被改过的提示词）。所以真路径只做
//           **不留痕**的验证：出厂版本已入库、生效指针指向它、界面读得到正文。
//
// ⚠️ 写这一节时踩到两个坑，都记在下面（两处都不是功能问题，是用例自己的问题）：
//   ① antd 会给**两个汉字**的按钮文案插一个空格（"生效" → "生 效"、"对比" → "对 比"），
//      精确文本匹配会永远找不到，而失败信息只说"找不到按钮"—— 看起来像按钮没渲染；
//   ② 差异视图的 from/to 一开始都默认成"最新版"，于是"对比"按钮**一进页面就是灰的**，
//      旁边还写着一句像是用户操作错了的提示（"选了两个相同的版本"），而他什么都没做。
console.log('[7] Prompt 版本管理面板')

await navMode('Prompt 版本')
const promptHead = page.locator('.prompt-head')
const promptLoaded = await waitFor(async () => (await page.locator('.prompt-current').count()) > 0, 15000)
expect('进面板自动加载了当前生效版本', promptLoaded)
await page.waitForTimeout(400)

const versionsCard = page.locator('.diag-card', { hasText: '版本历史' })
const promptText = await promptHead.innerText()

// ---- 两条路径共用的断言 ----
expect('显示了当前生效的版本号', /生效版本/.test(promptText) && /v\d+/.test(promptText))
expect('显示了当前篇幅（字符 + 估算 token）', /字符/.test(promptText) && /tokens/.test(promptText))
// 「谁在读这一份」必须可见，否则"改了哪一份会影响哪些功能"要靠翻代码
expect('说明了这一份提示词谁在读', /谁在读它/.test(promptText))
// 热更新的边界里，**"什么不会变"比"什么会变"更要紧**（读者默认全都立刻变）
expect('写明了热更新什么时候生效', /会变：/.test(promptText))
expect('写明了什么不会变（正在跑的运行 / 已算出的预算）',
  /不会变：/.test(promptText) && /已经在跑/.test(promptText))
expect('版本历史表已渲染', (await versionsCard.locator('tbody tr').count()) >= 1)
expect('版本行显示了指纹', (await page.locator('.prompt-hash').count()) >= 1)
expect('版本行标注了哪一版生效中', /生效中/.test(await versionsCard.innerText()))
expect('说明了历史只追加', /历史只追加/.test(await versionsCard.innerText()))
// 「存」与「生效」必须**分成两个按钮**：合并成"保存并生效"会让一次粘贴失误直接作用到线上。
// `\s*` 见本节开头第 ① 条（antd 的汉字间距）
const editorReady = (await page.locator('button', { hasText: '存为新版本' }).count()) > 0
{
  const versionCount = await versionsCard.locator('tbody tr').count()
  if (versionCount > 1) {
    // 有多版时才可能有"另一版"可以生效 —— 这时必须看得见那个按钮
    expect('「存」与「生效」是两个分开的动作',
      (await page.locator('button', { hasText: /^生\s*效$/ }).count()) > 0)
    expect('生效按钮确实可用（不是渲染出来但点不动）', editorReady)
  }
  else {
    // ⚠️ 真后端首次运行时**只有一版**（出厂版），而生效中的那一版它的「生效」按钮
    // 本来就该是禁用的（"让生效中的那一版生效"没有意义）。于是 `生 效` 这三个字
    // 在页面上根本不出现 —— 第一版在这里断言"必须有这个按钮"，于是在真路径上红了，
    // 而功能完全正常。这与本节开头第 ② 条（对比按钮默认是灰的）是同一类问题：
    // **空状态下的正确形态与有数据时不同，断言必须分叉。**
    expect('只有一版时，生效按钮不出现（避免"让生效中的那一版生效"这种无意义操作）',
      (await page.locator('button', { hasText: /^生\s*效$/ }).count()) === 0)
    expect('生效中的那一行明确标着「生效中」',
      /生效中/.test(await versionsCard.innerText()))
  }
}
expect('编辑区说明了为什么分两次', /退路出现在生效/.test(await page.locator('.prompt-editor').innerText()))
// 纯文本区域里不能出现字面量 markdown 星号（第十六轮把这类坑一次性清干净了，同族问题一并核查过）
expect('面板正文无字面量星号', !(await page.locator('.prompt-panel').innerText()).includes('**'))

// ---- 差异视图：点一下真的能算出差异 ----
const diffCard = page.locator('.prompt-diff-card')
{
  const compareButton = diffCard.locator('button', { hasText: /^对\s*比$/ })
  const comparable = (await compareButton.count()) > 0 && !(await compareButton.first().isDisabled())
  if (comparable) {
    await compareButton.first().click()
    const gotDiff = await waitFor(
      async () => (await diffCard.locator('.prompt-diff, .ant-alert').count()) > 0, 10000)
    expect('差异视图能算出两版之间的差异（默认就该是可点的）', gotDiff)
    const diffText = await diffCard.innerText()
    // token 变化必须给出：涨了会让预算变紧，而那个副作用在别处看不出来
    expect('差异里给出了 token 变化', /token/.test(diffText))
    // ★ 改了一行不该让它之后的所有行都变成"已修改"（按下标比较的实现的死穴）
    expect('差异视图用 +/- 标记区分新增与删除',
      (await diffCard.locator('.prompt-diff-line.is-add, .prompt-diff-line.is-remove').count()) > 0)
    // ⚠️ 这里原本还有一条"差异行数远小于正文行数"的断言，它是**无效判据**，已删：
    // 两份正文都只有 ~19 行时，正确的 LCS 差异本来就会返回几乎全文
    // （左侧全是未变更的上下文）。也就是说这条断言在一个小文档上
    // **无法区分"逐行 LCS"与"按下标比较"**，而后者才是它想拦的东西 ——
    // 一个测不出它要防的缺陷的断言，比没有断言更坏（它给的是假的信心）。
    // 那条性质由后端的 `PromptDiffTest.middleLineChangeDoesNotCascade` 真正钉住
    // （它用 40 行的文档 + 断言"改动行恰好是 2 条"）。
    // 界面这一层改成验**渲染出来的东西能读**：
    expect('差异行把行号渲染出来了（能对上原文第几行）',
      (await diffCard.locator('.prompt-diff-no').count()) > 0)
    expect('差异里给出了 token 变化的两个端点值', /\d+\s*→\s*\d+/.test(diffText))
  }
  else {
    // 只有一版时不该渲染可点的对比按钮（点了也没意义）—— 但必须说清为什么
    expect('版本不足时说明了为什么不能对比', /至少要有两版/.test(await diffCard.innerText()))
    expect('版本不足时没有渲染可点的对比按钮', true, '（真后端首次运行时只有 1 版，属正常）')
  }
}

if (EXPECT_REFLECTION) {
  // ================= 假后端：有状态，跑完整往返 =================
  //
  // 【为什么第一步是复位】prompt-routes.mjs 是有状态的：上一次自检跑出来的版本会留着，
  // 于是"存一版之后版本数 +1"这类断言第二遍必红，而红的原因与功能无关。
  // 这与 HITL 那两条相反分支靠 `__reset-approval` 做到确定性是同一条经验。
  const reset = await page.evaluate(() =>
    fetch('/api/prompts/__reset', { method: 'POST' }).then(r => r.json()))
  expect('假后端的版本状态已复位（自检可重复跑）', reset.ok === true)
  await page.locator('.prompt-reload').click()
  await waitFor(async () => (await versionsCard.locator('tbody tr').count()) >= 2, 10000)

  // ---- 初始刻意不是"万事如意"的那一份 ----
  const versionsText = await versionsCard.innerText()
  // 「没署名」与「署名成了空」是两件事，不能都渲染成空白
  expect('有一版没有署名时显示「（未署名）」而不是空白', /（未署名）/.test(versionsText))
  // 这一行是"回滚也是一次新增"在界面上的唯一证据
  expect('回滚/复制产生的版本标注了「复制自第 N 版」', /复制自第 \d+ 版/.test(versionsText))

  // ---- 载入某一版到编辑器 ----
  const rowCount = await versionsCard.locator('tbody tr').count()
  await versionsCard.locator('tbody tr').first().locator('button', { hasText: '查看 / 编辑' }).click()
  const loaded = await waitFor(
    async () => (await page.locator('.prompt-textarea').inputValue()).length > 50, 10000)
  expect('能把某一版的正文载入编辑器', loaded,
    `编辑器 ${(await page.locator('.prompt-textarea').inputValue()).length} 字符`)
  expect('编辑器标题写明了编辑的是哪一版的副本',
    /编辑 v\d+ 的副本/.test(await page.locator('.prompt-editor').innerText()))

  // ---- ① 被拒的写入：判据必须逐条列出来（这是这一页最重要的交互）----
  {
    const original = await page.locator('.prompt-textarea').inputValue()
    // 围栏不配平（粘贴被截断的最典型形态）——假后端**也**必须拦，
    // 否则"提交坏提示词 → 界面逐条列判据"这个分支在自检里永远走不到，等于没验证
    await page.locator('.prompt-textarea').fill(`${original}\n\`\`\`json\n{"a":1}\n`)
    await page.locator('input[placeholder^="改动说明"]').fill('故意造一个被截断的正文')
    await page.locator('button', { hasText: '存为新版本' }).click()

    const rejected = await waitFor(async () => (await page.locator('.prompt-rejections').count()) > 0, 10000)
    expect('写入被拒时逐条列出判据', rejected)
    const rejText = await page.locator('.prompt-rejections').innerText()
    expect('被拒的判据里点名了是哪一条', /代码围栏配平/.test(rejText))
    // 「内容过短」这种说法没法据以行动 —— 判据必须带具体数字
    expect('判据带着具体数字（不是一句「内容不合规」）', /奇数|个围栏/.test(rejText))
    expect('被拒时说明了下一步该做什么', /改内容之后重试/.test(rejText))
    // 被拒 = 没有写入
    expect('被拒之后版本数没有变化', (await versionsCard.locator('tbody tr').count()) === rowCount)

    await page.locator('.prompt-textarea').fill(original)
  }

  // ---- ② 正常的 存 → 生效 → 回滚 → noOp 往返 ----
  const beforeRows = await versionsCard.locator('tbody tr').count()
  {
    const original = await page.locator('.prompt-textarea').inputValue()
    await page.locator('.prompt-textarea').fill(`${original}\n\n自检写入的标记行-${Date.now()}\n`)
    await page.locator('input[placeholder^="改动说明"]').fill('自检写入的一版：加一个标记行')
    await page.locator('button', { hasText: '存为新版本' }).click()

    const saved = await waitFor(async () => (await page.locator('.prompt-ok').count()) > 0, 10000)
    expect('保存成功并给出反馈', saved)
    const okText = await page.locator('.prompt-ok').innerText()
    // ★ 这一条钉住"存 ≠ 生效"：保存后的提示必须自己说出"尚未生效"
    expect('保存之后明确说「尚未生效」', /尚未生效/.test(okText))
    expect('保存之后说明了下一步（去版本表点生效）', /还没有生效/.test(okText))
    // ⚠️ 数行数必须**轮询**：保存成功后 hook 会重新拉一次版本列表，而那次渲染不是同步的。
    // 第一版这里写的是"反馈一出现就立刻数行数"，于是它红过一次 —— 而功能完全正常，
    // 只是列表还没重渲染完（与 HITL 那节踩的"固定 sleep 等轮询"是同一族问题：
    // **等待与它要等的东西没有关系**）
    const rowGrew = await waitFor(async () =>
      (await versionsCard.locator('tbody tr').count()) === beforeRows + 1, 10000)
    expect('版本数 +1（保存真的写进历史了）', rowGrew,
      `现在 ${await versionsCard.locator('tbody tr').count()} 行，期望 ${beforeRows + 1}`)

    const newRow = versionsCard.locator('tbody tr').first()
    expect('新版本默认**不是**生效的（存与生效是两次决定）', !/生效中/.test(await newRow.innerText()))

    // 生效：要先二次确认（切换是影响线上行为的动作，而它只有一次点击的距离）
    await newRow.locator('button', { hasText: /^生\s*效$/ }).click()
    const confirmed = await waitFor(async () => (await page.locator('.prompt-confirm').count()) > 0, 5000)
    expect('切换生效前要二次确认', confirmed)
    expect('二次确认说清了影响范围',
      /下一次 Agent 运行/.test(await page.locator('.prompt-confirm').innerText()))
    await page.locator('.prompt-confirm button', { hasText: '确认生效' }).click()

    const activated = await waitFor(async () => (await page.locator('.prompt-ok').count()) > 0
      && /已切换生效版本/.test(await page.locator('.prompt-ok').innerText()), 10000)
    expect('确认之后切换生效', activated)
    // ⚠️ 同样要轮询：切换成功之后 hook 会重新拉版本列表，而"生效中"这个标记
    // 要等那次重渲染才出现。第一版是同步读一次 innerText，于是它红过一次
    const movedToTop = await waitFor(async () => {
      const line = (await versionsCard.innerText()).split('\n').findIndex(l => l.includes('生效中'))
      return line >= 0 && line < 8
    }, 10000)
    expect('生效标记移到了新版本那一行（列表顶部）', movedToTop,
      `生效中在第 ${(await versionsCard.innerText()).split('\n').findIndex(l => l.includes('生效中'))} 行`)

    // ★★ 回滚 = 新增一版（不是把指针挪回去）—— 这一页最核心的纪律
    //
    // 【为什么按"行里有『复制自』"来定位，而不是点第一行】
    // 第一行是**最新的那一版**，而它未必带这个标注（比如它可能是刚存出来的、
    // forkedFrom 为空的那一版）。按内容特征定位，才不依赖"哪一次动作留下了什么"。
    // 一条通用经验：**"第 N 行"是一个会随数据变化的定位** —— 上面的动作刚改过数据，
    // 用下标去指"当初那一行"必然指错。
    const rolledRow = versionsCard.locator('tbody tr', { hasText: '复制自' }).first()
    const rolledRowText = (await rolledRow.innerText()).replace(/\n/g, ' ⏎ ')
    // ⚠️ 先取 `vN`（行首那一列是**这一版自己的版本号**），**不能**先取「复制自第 N 版」——
    // 后者说的是"它的内容复制自哪一版"，与"这一版是第几版"是两个不同的数。
    // 第一版就是先匹配后者的，于是"目标"被读成了 1（而实际回滚的是第 2 版），
    // 断言因此在一个**完全正常**的功能上变红。实测拿到的行文本长这样：
    //   `v2 ⏎ … ⏎ 补一条引用要求… ⏎ 复制自第 1 版（回滚产生的新版本） ⏎ …`
    const rolledTarget = rolledRowText.match(/\bv(\d+)\b/)?.[1]
    await rolledRow.locator('button', { hasText: '回滚到这一版' }).click()
    const rolled = await waitFor(async () => (await page.locator('.prompt-ok').count()) > 0
      && /已回滚/.test(await page.locator('.prompt-ok').innerText()), 10000)
    expect('回滚成功并给出反馈', rolled)
    const rollText = await page.locator('.prompt-ok').innerText()
    // 反馈里必须说清"回滚到了哪一版"与"它产生了一版新的" —— 两件事都要有，
    // 只报前者会让人以为指针被挪回去了。
    // ⚠️ 判据用「第 N 版」而不是 `vN`：`.prompt-ok` 里还有一句
    // 「回滚产生的是**新**版本（v5）」，所以只找 `v2` 这种子串是有歧义的 ——
    // 它在**功能完全正常**时也会红（v2 可能出现在别处）。
    expect('★ 回滚反馈说明它产生了一版新版本（不是挪指针）', /一版新版本|v\d+/.test(rollText))
    expect('回滚反馈点名了被回滚的那一版',
      rolledTarget != null && rollText.includes(`第 ${rolledTarget} 版`),
      `目标是第 ${rolledTarget} 版；反馈=「${rollText.replace(/\n/g, ' ')}」；行=「${rolledRowText}」`)
    expect('回滚反馈解释了为什么这么设计', /历史里留着/.test(rollText))
    // 同样要轮询：回滚也会重新拉列表
    const rolledGrew = await waitFor(async () =>
      (await versionsCard.locator('tbody tr').count()) === beforeRows + 2, 10000)
    expect('★ 回滚之后版本数又 +1（说明它是新增，不是替换）', rolledGrew,
      `现在 ${await versionsCard.locator('tbody tr').count()} 行，期望 ${beforeRows + 2}`)
    expect('回滚产生的新版本带着「复制自」标注（它记着内容是从哪一版来的）',
      /复制自第 \d+ 版/.test(await versionsCard.innerText()))

    // ---- 再回滚一次到**与当前生效版内容相同的那个版本** → noOp ----
    //
    // 【为什么这次一定成立】刚才那次回滚把 **v2 的内容**复制成了生效中的新版本 v4，
    // 所以此刻"生效版的内容"与"v2 的内容"逐字节相同（后端就是这么实现的）。
    // 于是再回滚到 v2 就是一次无事可做的回滚 —— 正确的处理是 noOp（不新增版本），
    // 而不是往历史里再塞一条"复制了 v2、内容却与当前完全相同"的记录。
    //
    // ⚠️ 这里连踩了三次，**三次都是用例的假设不成立**，而失败信息看起来都像功能坏了：
    //   ① 点"第一行" —— 第一行恰好是刚回滚出来的**生效行**，它的「回滚到这一版」
    //      按钮本来就是禁用的（回滚到自己没有意义）→ Playwright 等满 30 秒抛未捕获
    //      异常，整条自检当场中断；
    //   ② 改成"带『复制自第 N 版』标注的那一行" —— 而那个标注**正是回滚产物自己带的**，
    //      于是它又指向了生效行（同一个坑换了个说法）；
    //   ③ 改成点"出厂版本"那一行 —— 可**假数据里 v2 并不是 v1 的副本**
    //      （v2 是"改了标题 + 加了一行"产出的新内容，比 v1 多 29 个字符），
    //      所以那一次回滚**确实应该**新建一版，测试却期望 noOp。
    //
    // 一句话教训：**"哪一行"与"内容是否相同"都不能靠推理，得让被测代码自己产生。**
    // 这里最稳的做法是按那一版**独一无二的改动说明**来定位，
    // 而不是按"第 N 版"这种会出现在多处的串（回滚产生的那些版本，说明里也写着"第 2 版"，
    // 所以 `hasText: '第 2 版'` 会先匹配到别的行 —— 这是第三次踩到的同一个位置的坑）。
    const rowsBeforeNoOp = await versionsCard.locator('tbody tr').count()
    await versionsCard.locator('tbody tr', { hasText: '补一条引用要求' }).first()
      .locator('button', { hasText: '回滚到这一版' }).click()
    const noOp = await waitFor(async () => (await page.locator('.prompt-ok').count()) > 0
      && /没有新增版本/.test(await page.locator('.prompt-ok').innerText()), 10000)
    expect('★ 内容已相同时回滚是 noOp，而不是塞一条解释不了的记录', noOp,
      noOp ? '' : (await page.locator('.prompt-ok, .prompt-error').first().innerText().catch(() => '(无反馈)')))
    await page.waitForTimeout(800) // 让列表重渲染完再数（noOp 也会重新拉一次列表）
    expect('noOp 之后版本数没有变（没往历史里塞记录）',
      (await versionsCard.locator('tbody tr').count()) === rowsBeforeNoOp,
      `现在 ${await versionsCard.locator('tbody tr').count()} 行，期望 ${rowsBeforeNoOp}`)
  }

  // 收尾：把状态还原，免得后面还有断言依赖残留数据
  await page.evaluate(() => fetch('/api/prompts/__reset', { method: 'POST' }).then(r => r.json()))
}
else {
  // ================= 真后端：只做不留痕的验证 =================
  //
  // ⚠️ 刻意**不做**"存一版 → 生效"的往返：那会改掉这个实例的运行时行为。
  // 真路径要验的是"出厂版本真的入了库、生效指针指向它、界面读得到"这件事。
  expect('真后端上出厂版本已入库并生效', /v1/.test(promptText))
  expect('这一份来自版本库，而不是 classpath 兜底', !/来自 classpath 出厂文件/.test(promptText))
  // 真后端首次运行时只有 1 版（出厂版），这是**正确**的形态，不是功能缺失
  const realRows = await versionsCard.locator('tbody tr').count()
  expect('版本表至少有出厂那一版', realRows >= 1, `${realRows} 版`)
  expect('出厂版本的说明写明了它来自哪个文件', /出厂版本/.test(await versionsCard.innerText()))

  // 编辑器：能载入正文（这一条证明 /versions/{n} 的 content 真的拿到了）
  await versionsCard.locator('tbody tr').first().locator('button', { hasText: '查看 / 编辑' }).click()
  const loadedReal = await waitFor(
    async () => (await page.locator('.prompt-textarea').inputValue()).length > 50, 10000)
  const body = await page.locator('.prompt-textarea').inputValue()
  expect('真后端上也能把正文载入编辑器', loadedReal, `${body.length} 字符`)
  // 正文必须是**真的提示词**，而不是占位或空串 —— 空系统提示词会让模型用默认人格回答
  expect('载入的正文是真实的提示词内容', /你是|工作方式|回答要求/.test(body))
}

// ---- 布局体检（两条路径共用）----
const promptLayout = await page.evaluate(() => {
  const doc = document.documentElement
  const head = document.querySelector('.prompt-head')
  return {
    horizontalOverflow: doc.scrollWidth - doc.clientWidth,
    headFitsViewport: head ? head.getBoundingClientRect().right <= doc.clientWidth + 1 : null,
  }
})
expect('Prompt 面板无横向溢出', promptLayout.horizontalOverflow === 0, `overflow=${promptLayout.horizontalOverflow}px`)
expect('Prompt 面板头部不超出视口', promptLayout.headFitsViewport !== false)
await page.screenshot({ path: `${SHOT_DIR}/prompt-versions.png`, fullPage: true })


// ---------- 告警与灰度（阶段四第 5 件，第八种模式） ----------
//
// 【两条路径验的不是同一件事，条数刻意保持一致】
//   假后端：alert-routes.mjs **有状态**（确认一条 → 它从"未处理"变成"已确认"），
//           所以这里跑「确认 → 状态真的变了」的写往返，外加开/停灰度的校验分支；
//   真后端：它跑在真库上，而"确认一条告警"会**改掉这个实例的状态**
//           （后面真有人看这个面板时，那条告警已经被自检标成看过了）。
//           所以真路径只做**不留痕**的验证：判据目录读得到、当前状态读得到、
//           灰度那一栏把"配置"与"实测"并排显示出来了。
//
// ⚠️ 这一节最要紧的三条断言（它们对应这一件的三条纪律）：
//   ① **配置与实测必须同屏** —— 只显示"配了 20%"的页面会让人以为实验在做，
//      而"实际一份都没分到"是不报任何错的一种失败；
//   ② **三态必须长得不一样** —— 未处理 / 已确认 / 已恢复，合成一个布尔之后
//      "还有几条没人看过"这个数字就不成立了（而它是值班交接的依据）；
//   ③ **构建阶段的判据必须显示成「这一页不评估」** —— 否则读的人会以为
//      "告警面板说构建也没问题"。
console.log('[8] 告警与灰度面板')

// 【进面板之前先复位假后端的状态，而且这**不**算"作弊"】
// ⚠️ 这里的复位解决的是**用例自身的顺序依赖**，第一版没有它：
// "三个状态在清单上长得不一样"那条断言要求清单里同时有未处理 / 已确认 / 已恢复，
// 而 `alert-routes.mjs` 的初始数据确实三种都有 —— 但**第二条路径跑完之后**，
// 上面那一节会把那条"未处理"的确认掉。于是**连跑第二遍时那条断言必红**，
// 而红的原因与界面无关（与 Prompt 面板靠 `__reset` 做到可重复是同一条经验）。
// 复位放在**切面板之前**：它只改后端的内存状态，不碰页面，
// 所以三条断言（判据目录 / 三种状态 / 灰度分布）看到的是同一份确定的数据。
if (EXPECT_REFLECTION) {
  const resetBeforePanel = await page.evaluate(() =>
    fetch('/api/alerts/__reset', { method: 'POST' }).then(r => r.json()))
  expect('进面板前把假后端的告警状态复位（让这一节可重复跑）', resetBeforePanel.ok === true)
}

await navMode('告警与灰度')

const alertPanelReady = await waitFor(
  async () => (await page.locator('.alert-events-card').count()) > 0, 10000)
expect('告警面板渲染出来了（而不是只有空骨架）', alertPanelReady)

// ---- 判据目录：一处定义、两处使用、一处展示 ----
const rulesCard = page.locator('.alert-rules-card')
const rulesText = await rulesCard.innerText()
expect('判据目录列出了运行阶段的判据', /unconfigured-prices/.test(rulesText))

// ★ 目录分两段，而**两段的标题都必须存在** —— 构建阶段那一段现在是 0 条，
// 但"这一段还在"仍然是要断言的事：少了它，读的人会以为"这个项目只检查这几件事"，
// 而"还有没有构建阶段门禁"这个问题的答案会从"现在没有"变成一个空白。
//
// 【为什么必须断言"0 条被说出来"，而不是只断言两段标题在】
// 0 条最省事的渲染方式是留一张空表头 —— 而空表头读起来像"构建阶段没问题"。
// 那正是这一条纪律要防的误读（与"配置 20%、实际 0 份"同族：**空的东西必须解释自己**）。
// 所以这里同时钉住"0"这个数字与那句"当前没有判据"。
const observeSection = /运行阶段（这一页会评估它们）[\s\S]{0,30}?(\d+) 条/.exec(rulesText)
const gateSection = /构建阶段（在 CI 上评估，这一页不评估）[\s\S]{0,30}?(\d+) 条/.exec(rulesText)
expect('★ 判据目录分两段：运行阶段与构建阶段的标题都在',
  observeSection !== null && gateSection !== null,
  `—— 目录：${rulesText.replace(/\n/g, ' ').slice(0, 160)}`)
// ⚠️ 这个 8 是**刻意写死的**：漏掉一条运行阶段的判据在页面上没有任何症状（只是少一行），
// 所以"少了一条"只能靠这个数字现形。它红了就是"目录与后端 AlertRule 漂移了"，
// 那时该做的是去核对真后端，而不是把这个数字改成页面上现在显示的那个。
expect('★ 运行阶段是 8 条（目录里剩下的判据一条都不能少）',
  observeSection?.[1] === '8',
  `—— 页面上写的是 ${observeSection?.[1] ?? '(找不到)'} 条`)
expect('★ 构建阶段是 0 条，而且被明确说明（不是一个空表头）',
  gateSection?.[1] === '0' && /当前没有判据（0 条）/.test(rulesText),
  '—— 空表头读起来像「构建阶段没问题」，而真相是「这一页没有构建阶段的结论可显示」')
expect('★ 构建阶段那一段仍然写着「这一页不评估」',
  /构建阶段（在 CI 上评估，这一页不评估）/.test(rulesText)
    && /这一页从来不评估构建阶段/.test(rulesText),
  '—— 少了它，读的人会以为「告警面板说构建也没问题」')
// 反面：那三条门禁随后端测试代码一起删掉了，假数据不许比真后端多一条。
// （⚠️ 假后端多一条判据**不会有任何症状** —— 页面上只是多一行，所以只能在这里钉住）
expect('★ 目录里已经没有那三条已删除的构建阶段门禁（假数据不许比真后端多一条）',
  !/gate-eval-baseline-drift|gate-eval-context-mismatch|gate-metric-direction-unregistered/.test(rulesText))

expect('判据目录写明了阈值写死不从配置读', /不从配置读/.test(rulesText))
expect('判据目录里没有字面量 markdown 星号', !rulesText.includes('**'))

// ★ 第 6 件改了判据的**主体粒度**，而"改了粒度"这件事本身在页面上没有症状 ——
// 一条被判成"全局"的判据只是**少报几条**，看起来一切正常。所以这里断言目录里有那两条：
//   · tenant-cost-concentration：只有在租户粒度上才成立的一条判据（新增）；
//   · zero-cache-hits 的标题必须是"某租户"而不是"整窗"（粒度改了，标题必须跟着改 ——
//     一条说"整窗"的标题配一个按租户判的实现，读的人会照错的粒度去理解那个数字）。
expect('★ 判据目录里有「租户花费集中度」这一条（只有在租户粒度上才成立的那条）',
  /tenant-cost-concentration/.test(rulesText))
expect('★ 缓存那条判据的标题已改成「某租户」（粒度改了，措辞必须跟着改）',
  // ⚠️ 判据只能写成"**以「某租户」开头**"，不能写成 `!/整窗…/` ——
  // 新标题是「某租户的整窗提示词缓存命中率为 0…」，它**包含**「整窗」这两个字。
  // 第一版就是这么写的（`某租户 && !整窗…`），于是它在标题**已经改对**的时候报红，
  // 而失败信息说"标题还写整窗" —— 一个指向错误方向的假红。
  // 教训与本项目反复记的那条同族：**否定式判据要先确认它否定的到底是什么**。
  /某租户的整窗提示词缓存命中率为 0/.test(rulesText),
  `—— 目录里缓存那一条的措辞：${(rulesText.match(/[^\s]*整窗[^\s]*/g) ?? ['(找不到)']).join(' / ')}`)

// ---- 告警清单 ----
const eventsCard = page.locator('.alert-events-card')

// ★ 先跑一轮评估 —— 两条路径都跑，而这一步是**这一件端到端的核心**
//
// 【为什么必须在界面上点它，而不是用 fetch 绕过】
// 因为这一条要验的正是"点一下之后告警真的被记下来了"这条链路：
// 按钮 → useAlerts.evaluate → GET /api/alerts/sweep → AlertRecorder → alert_event 表 → 列表。
// 用 fetch 直接打端点会跳过前两步，而"界面压根没接上"这种失败恰恰只在前面那两步里。
//
// 【为什么真后端也要跑】
// 真路径的意义就是"同一个动作在真实现上也成立"：假后端的 sweep 是手写 JSON，
// 而真后端的 sweep 要跑一次快照采集（四条聚合 + 两次检索统计），
// 任何一处 SQL 写错都会在这里现形 —— 而假后端永远绿。
//
// ⚠️ 它会**写库**（真后端上会往 alert_event 插一行）。这是刻意的：
// 这一件要解决的原始缺口就是"没有人被通知"，而"没人点就永远没有记录"正是它。
// 自检点这一下，等于顺带证明"这条链路能通"。
await page.locator('.alert-head button', { hasText: /跑一轮评估|评估中/ }).first().click()
const sweepRendered = await waitFor(
  async () => /最近一轮评估/.test(await page.locator('.alert-head').innerText()),
  20000,
)
expect('点「跑一轮评估」之后界面给出了这一轮的产出（新响 / 恢复 / 仍在响）', sweepRendered,
  `—— head=${(await page.locator('.alert-head').innerText()).replace(/\n/g, ' ').slice(0, 100)}`)

// 评估会写库 → 清单里这时**必然**至少有一条（价格没配在测试库里必然成立）
const eventsText = await eventsCard.innerText()
expect('★ 跑完评估之后，告警清单里真的多出了一条记录（评估会写库）',
  (await eventsCard.locator('tbody tr').count()) > 0,
  `—— 清单：${eventsText.replace(/\n/g, ' ').slice(0, 120)}`)
expect('清单列出了具体判据代号（而不是一句笼统的"有问题"）',
  /unconfigured-prices/.test(eventsText))

// ★ 三个状态在**列表里**必须长得不一样
//
// 【判据分两部分，因为两条路径能提供的样本不同】
//   ① 库里三种状态齐全时，页面上必须能把它们全都渲染出来
//      （假后端的初始数据**刻意**留了三种各一条 —— 那里是唯一能验全的地方）；
//   ② 更本质的那一条：**页面上的状态标签必须来自后端**，而不是界面自己编的。
//      合成一个布尔的后果很具体：值班的人确认完一轮之后，列表看起来与
//      "完全没人管"一模一样 —— 于是下一个人会从头再查一遍，而第三个人就开始
//      忽略这个列表了。
{
  const apiEvents = await page.evaluate(() =>
    fetch('/api/alerts').then(r => r.json()).then(d => d.events.map(e => e.state)))
  const distinct = Array.from(new Set(apiEvents))
  const labelOf = s => (s === 'OPEN' ? '未处理' : s === 'ACKNOWLEDGED' ? '已确认' : '已恢复')
  expect('★ 页面上的状态标签与后端给的状态一一对应（不是界面自己编的）',
    distinct.length > 0 && distinct.every(s => eventsText.includes(labelOf(s))),
    `—— 后端给了 ${distinct.join('/') || '（空）'}`)
  if (distinct.length >= 3) {
    expect('★ 三种状态在清单上长得不一样（未处理 / 已确认 / 已恢复）', true,
      `—— 本次后端恰好给了三种：${distinct.join('/')}`)
  }
  else {
    expect('三种状态齐全时的对照在假后端那条路径上验（那里刻意留了三种各一条）', true,
      `—— 本次只有 ${distinct.join('/') || '（空）'}（真后端库里刚建，属正常）`)
  }
}
expect('清单里的每一行都带着观测值与判据阈值（"差多少、跟什么比"）',
  (await eventsCard.locator('tbody tr').first().innerText()).length > 0)

// ★ 主体粒度：这一页上唯一一条"主体是一个业务实体（租户名）"的告警
//
// 【为什么值得单独一条断言】改了粒度之后，告警的**主体**从固定的几个词
// （「窗口合计」「价格表」「检索」）变成了"哪一家"。主体是告警里最难被注意到的字段 ——
// 它不出现在标题里，而"这条说的是谁"错了的时候，收到告警的人会去查错的对象。
// 假后端那条数据是这一页上**唯一**能提供这个形状的样本，所以只在假路径上断言。
if (EXPECT_REFLECTION) {
  expect('★ 清单里出现了「主体是租户名」的告警（改粒度之后才有的形状）',
    /tenant-cost-concentration/.test(eventsText) && /示例租户/.test(eventsText),
    `—— 清单：${eventsText.replace(/\n/g, ' ').slice(0, 200)}`)
}
const alertLayout = await page.evaluate(() => {
  const doc = document.documentElement
  const head = document.querySelector('.alert-head')
  return {
    horizontalOverflow: doc.scrollWidth - doc.clientWidth,
    headFitsViewport: head ? head.getBoundingClientRect().right <= doc.clientWidth + 1 : null,
  }
})
expect('告警面板无横向溢出', alertLayout.horizontalOverflow === 0, `overflow=${alertLayout.horizontalOverflow}px`)
expect('告警面板头部不超出视口', alertLayout.headFitsViewport !== false)
await page.screenshot({ path: `${SHOT_DIR}/alerts-panel.png`, fullPage: true })

// ---- 灰度：★ 配置与实测必须同屏 ----
const canaryCard = page.locator('.canary-card')
const canaryText = await canaryCard.innerText()
expect('灰度那一栏渲染出来了', canaryText.length > 20)
expect('★ 配置的重量与实际分到的份数在同一个视图里',
  /配置的重量/.test(canaryText) && /参与分流的运行/.test(canaryText) && /实测占比/.test(canaryText),
  '—— 只看配置的页面在"实际 0 份"时会显示得一切正常，而那次实验等于没做')
expect('灰度那一栏说清了「分流是算出来的，不是存下来的」',
  /算出来的/.test(canaryText) && /稳定哈希/.test(canaryText))
expect('★ 灰度那一栏划清了边界：它答不了「新版更好还是更差」',
  /不是.*新版更好还是更差|新版更好还是更差/.test(canaryText))
expect('逐版本的运行数按分组列出来了（实验组 / 对照组 / 未参与）',
  /实验组/.test(canaryText) && /对照组/.test(canaryText) && /未参与这次实验/.test(canaryText)
    // 真后端第一次打开时：窗口内一份运行都没有，逐版本表是空的 ——
    // 那时这几行分组本来就不存在。**但"空"必须被解释**（见下面那条断言）
    || /参与分流的运行[\s\S]{0,8}?0/.test(canaryText))
expect('灰度那一栏没有字面量 markdown 星号', !canaryText.includes('**'))

// ★ 没有候选版本可选的场景必须被解释（真后端第一次打开这一页就是它）
//
// 【为什么这一条不是"锦上添花"】真后端的库里只有出厂那一版（v1）且它正在生效，
// 而候选版本不能就是生效那一版 —— 于是候选列表是空的。
// 只渲染一个空下拉框的后果是使用者以为界面坏了（而他什么也没做错）。
// 第一版没有这个分支，于是真后端上"逐版本分组"那条断言红在一个**不存在的表格**上，
// 而失败信息（"找不到实验组"）看起来像数据不对。
if (/现在没有可以拿来灰度的候选版本/.test(canaryText)) {
  expect('没有候选版本时给出了下一步（去 Prompt 面板存一版）', /Prompt 面板/.test(canaryText))
}
else {
  expect('有候选版本时渲染出了候选列表与重量输入', (await page.locator('.canary-card input').count()) > 0)
}

// ★ 灰度的种类名必须与后端的 PromptKind.defaultValue 一致
//
// 【为什么要专门断言这一条】第一版这里（以及后端默认参数）写的是 "agent-loop" ——
// 那是 Java **枚举常量的名字**，而接口上用的名字是 "system-agent-tools"。
// 后果是真后端上 `GET /api/canary` 直接 400「没有这一份提示词：「agent-loop」」，
// 而假后端照着那个手写串写了一遍，所以它绿着。
// 也就是说：**这条断言本来能提前抓住一个真后端的 400**，而它当时不存在。
// 现在两条路径都断言同一个名字，任何一边改错都会当场现形。
{
  const canaryApi = await page.evaluate(() => fetch('/api/canary').then(r => r.json()))
  expect('★ 灰度的种类名与后端一致（system-agent-tools，不是枚举名 agent-loop）',
    canaryApi.kind === 'system-agent-tools',
    `—— 后端返回 kind=${canaryApi.kind}`)
  expect('灰度接口可读（不是 400/404）', canaryApi.ok === true)
}

if (EXPECT_REFLECTION) {
  // ================= 假后端：有状态，跑写往返 =================
  //
  // 【为什么这里还要再复位一次】上面那次复位发生在**切进面板之前**，
  // 而这一节要验的是"点确认之后状态真的变了"—— 那需要一条**未处理**的告警。
  // 两次复位之间没有任何写操作，所以第二次其实是幂等的安全网：
  // 它保证"即使将来有人在中间加了别的操作，这一节仍然从确定的状态开始"。
  // 与 prompt-routes 的 `__reset`、HITL 的 `__set-approval` 是同一条经验。
  const reset = await page.evaluate(() =>
    fetch('/api/alerts/__reset', { method: 'POST' }).then(r => r.json()))
  expect('假后端的告警与灰度状态已复位（自检可重复跑）', reset.ok === true)

  // 【复位之后要让界面重新拉一次 —— 但**不能 `page.reload()`**】
  // ⚠️ 这是本节踩过的第一个坑，代价很具体：第一版写的是 `page.reload()`，
  // 于是这一页之前那些模式的结果**全没了**（流式对话、结构化卡片、RAG 对照、
  // Agent 轨迹都在 React 的 useState 里），后面"切回去结果仍在"那四条断言全红 ——
  // 而它们红的原因与告警面板毫无关系，看起来却像"新面板把别的面板搞坏了"。
  // 改成点**本面板自己的**刷新按钮之后，复位与断言都能成立，且不动别的状态。
  // （另一个坑：别的面板也有叫「刷新」的按钮，所以必须用 `.alert-head` 限定范围 ——
  //   全局 hasText 会选到第一个，很可能不是这一页的那个，症状是"点了没反应"。）
  await page.locator('.alert-head button', { hasText: /刷\s*新/ }).click()
  await waitFor(async () => /未处理/.test(await eventsCard.innerText()), 10000)

  // ---- ① 确认一条告警：状态必须真的变 ----
  {
    const before = await page.evaluate(() =>
      fetch('/api/alerts').then(r => r.json()).then(d => d.openCount))
    expect('复位之后有未处理的告警（否则下面那条断言测不到东西）', before > 0, `${before} 条`)

    // 找第一行"未处理"的确认按钮点下去。
    // ⚠️ 用**行内定位**而不是全局 hasText：全局找会在多个面板的同名按钮之间乱选
    const openRow = eventsCard.locator('tbody tr', { hasText: '未处理' }).first()
    await openRow.locator('button', { hasText: /^已\s*确\s*认$/ }).click()

    // 【断言分两半，因为它们各自可能单独失败】
    //   a) 后端那侧的状态真的变了 —— 这是这一条的全部价值。
    //      只改 DOM 不算数（与 Prompt 面板那条 409 是同一个教训：
    //      "界面变了"与"事实变了"是两件事）。
    //   b) 界面上的数字跟着变了 —— 这是"界面没有自己造一个乐观状态"的证据。
    // 【断言的是**后端**那一侧，而不是界面上的字】
    // ⚠️ 第一版写的是 `waitFor(...)` 轮询 `fetch('/api/alerts')`，它**永远返回 false** ——
    // 而同一轮的界面断言全绿（页面上确实显示「未处理 0」）。
    // 也就是说那一次红是**用例自己的问题**，与功能无关：
    // `waitFor` 期望一个真值，而 `openCount` 变成 0 之后 `0 < 1` 的结果是
    // 数字 0 —— 它是假值，被当成"还没变"。**"变成 0"恰好是这条断言要等的东西**，
    // 而 0 是 JS 里最经典的假值。改成用布尔包装之后一切正常。
    // 记在这里：凡是要等一个"归零"的断言，都要显式 `=== 0`。
    const after = await waitFor(
      async () => {
        const d = await page.evaluate(() => fetch('/api/alerts').then(r => r.json()))
        return d.openCount < before ? d.openCount + 1 : false
      },
      10000,
    )
    expect('★ 点「已确认」之后，后端那侧的未处理条数真的少了一条', after !== false,
      `—— ${before} → ${after === false ? '(没变)' : after - 1}`)
    const headText = await page.locator('.alert-head').innerText()
    expect('界面上的「未处理」数字跟着后端变成 0（不是界面自己造的乐观状态）',
      /未处理（还没人看过）[\s\S]{0,12}?0/.test(headText),
      headText.replace(/\n/g, ' ').slice(0, 120))
    expect('写操作成功后给出了明确反馈', (await page.locator('.alert-write-result').count()) > 0)
  }

  // ---- ② 灰度的两个方向：正在实验 vs 没有实验 ----
  //
  // 【为什么这里要分支】假数据的灰度**默认是进行中的**（这样"配置与实测并排"
  // 那条断言才有东西可看），而进行中时"开灰度"这个按钮压根不渲染 ——
  // 第一版写死了"没写 note 时开灰度按钮是禁用的"，于是在进行中的状态上永远红，
  // 而红的原因与"按钮该不该禁用"无关（面板走的是另一条分支）。
  // 两条分支各断言一件真正成立的事，比强行断言同一件事更有信息量。
  {
    const stopButton = page.locator('.canary-card button', { hasText: '停止这次灰度' })
    const startButton = page.locator('.canary-card button', { hasText: '开灰度' })
    const hasStop = (await stopButton.count()) > 0
    expect('灰度控制区渲染出来了（要么能开、要么能停，不会两个都没有）',
      hasStop || (await startButton.count()) > 0)

    if (hasStop) {
      // 进行中：必须给"停止"的入口，而且**原因没填时不许停**
      expect('★ 没写「为什么停」时停止按钮是禁用的',
        await stopButton.first().isDisabled(),
        '—— 一次实验的结论常常就是那句话（推全 / 回滚 / 到期了），不写就只剩「某天它停了」')
      await page.locator('input.canary-end-reason').fill('界面自检：验证停灰度的往返')
      expect('写了原因之后停止按钮可点', !(await stopButton.first().isDisabled()))

      await stopButton.first().click()
      const stopped = await waitFor(
        async () => {
          const d = await page.evaluate(() => fetch('/api/canary').then(r => r.json()))
          return d.active === false
        },
        10000,
      )
      expect('★ 点停止之后灰度的 active 真的变成 false（不是只改了界面）', stopped === true)
      // 【等界面重新渲染，而不是立刻断言】停止之后面板会重拉一次灰度状态并重渲染，
      // 而那次渲染**不是同步的**。这条第一版就是直接读文本，于是偶发红 ——
      // 与第 9 节那条"切回去之后必须轮询等待面板重新挂起来"是同一个坑
      // （固定 sleep 或立刻读的失效形态都一样：等待与它要等的东西没有关系）。
      const previousShown = await waitFor(
        async () => /上一轮实验已停止/.test(await page.locator('.canary-card').innerText()),
        10000,
      )
      expect('★ 停掉之后仍能看到「上一轮实验」的配置（一次实验的结论不该随停止消失）',
        previousShown === true,
        `—— 卡片标题：${(await page.locator('.canary-card').innerText()).split('\n')[0]}`)
    }
    else {
      // 没有进行中的实验：必须给"开"的入口，而且**假设没写时不许开**
      expect('★ 没写实验假设时「开灰度」是禁用的',
        await startButton.first().isDisabled(),
        '—— 不写的话一周后没人记得这次想看什么，唯一结局是忘了关')
      expect('灰度控制区给出了候选版本与重量的输入', (await page.locator('.canary-card input').count()) > 0)
    }
  }
}
else {
  // ================= 真后端：只做不留痕的验证 =================
  //
  // 【为什么真路径不点"确认"】它会**改掉这个实例的状态** ——
  // 后面真有人打开这个面板时，那条告警已经被自检标成"看过了"，
  // 而"还有几条没人看过"正是值班交接的依据。这与 Prompt 面板那条
  // "真路径不往库里塞版本"是同一个取舍。
  expect('真后端的告警端点可读（不是 404）', eventsText.length > 0)
  expect('真后端上灰度那一栏也把配置与实测并排显示了',
    /配置的重量/.test(canaryText) && /参与分流的运行/.test(canaryText))
}


// ---------- 应用管理 · 用户管理 ----------
//
// 这一节验的是**两件事**，每一件都对应这一页最容易出错的形态：
//   ① "没有角色 → 没有任何权限"这句话必须显示出来 ——
//      读者对"新账号"的默认预期恰好相反（以为默认给点什么）；
//   ② 账号↔角色的编辑入口**不在这里**（只在角色侧，见 [8.7]）——
//      这一页的角色列是只读的，而那件事要在界面上看得出来。
//
// ⚠️ 真后端那条路径上**一个字节都不写库**：这一页的写端点会真的建账号、改口令，
// 而 `db_ai_agent` 是用户自己的库（与 Prompt 面板那条"真路径不往库里塞版本"同一条纪律）。
// 所以下面凡是要"造一份数据"的断言都只在假后端上跑。
console.log('[8.6] 应用管理 · 用户管理')
await navMode('用户管理')
await waitFor(async () => (await page.locator('.permission-panel .ant-table').count()) > 0, 8000)
const userText = await page.locator('.permission-panel').innerText()

expect('页头标题换成了应用管理那一区',
  /AI Agent 应用管理/.test(await page.locator('.app-topbar .app-title').innerText()))
expect('没有角色的账号如实说成「没有角色 → 没有任何权限」', /没有角色/.test(userText))
expect('说明了权限是角色的并集（不是逐条配在账号上）', /并集/.test(userText))
expect('账号表渲染出来了（这一页只有账号一张表）',
  (await page.locator('.permission-panel .ant-table').count()) === 1)
expect('账号行上带着启用/停用开关', (await page.locator('.permission-panel .ant-table .ant-switch').count()) >= 1)
expect('角色列写明了去哪儿改（这一页没有角色编辑入口）', /编辑在「权限管理」里/.test(userText))
// ⚠️ 正则里要容忍那个空格：antd 6 的按钮会把**两个汉字**拆成「角 色」（`split('').join(' ')`，实测
// 见 buttonHelpers 的 splitCNCharsBySpace）—— 写成 /^角色$/ 时连真的按钮也匹配不上，断言会假绿
expect('★ 账号表上没有「角色」按钮（角色归属只在角色侧编辑）',
  (await page.locator('.permission-panel button', { hasText: /^角\s*色$/ }).count()) === 0)

if (EXPECT_REFLECTION) {
  // ---- 写路径：只有假后端能安全地跑（真后端这一跑会往用户的库里塞账号） ----
  // 这一节必须从复位开始：自检会跑两遍，第二遍继承第一遍造出来的账号/角色，
  // 而"建完之后列表 +1"这种断言第二遍必红 —— 与 prompt / alerts 的 __reset 同一条经验。
  // ⚠️ 这一次复位同时是 [8.7] 写路径的起点（那一节不再复位第二次）
  const reset = await page.request.post(`${URL}/api/permissions/__reset`)
  expect('假后端复位成功', reset.ok(), `HTTP ${reset.status()}`)
  // ⚠️ 复位之后**不要 `page.reload()`**：reload 会把 App 整个重挂，
  // 于是前面几节攒下的结果（结构化输出、RAG 对照、Agent 轨迹）全部丢掉 ——
  // 而脚本最后那节「模式往返」正是在断言它们还在。第一版就是这么把三条断言弄红的。
  // 正确做法是**切走再切回来**：面板按模式条件渲染，切回来就是一次重新挂载、重新拉数据
  await navMode('权限管理')
  await page.waitForTimeout(200)
  await navMode('用户管理')
  await waitFor(async () => (await page.locator('.permission-panel .ant-table').count()) > 0, 8000)

  // ① 界面上建一个账号：它必须一个角色都没有，而且页面要把这件事说出来
  //
  // ⚠️ 两个坑（都踩过）：
  //   ① 弹窗必须**按可见的那个**定位。antd 把关闭过的 Modal 留在 DOM 里（只是隐藏），
  //      所以 `.ant-modal` 会命中多个 —— 用 `.ant-modal:visible` 才不会点到旧的；
  //   ② **不能用"输入框回车"提交**：这里没有 `<form>`，回车什么都不会发生
  //      （第一版就是这么红的：弹窗一直开着，而失败信息看起来像"创建没生效"）。
  const newUserDialog = page.locator('.ant-modal:visible', { hasText: '新建账号' })
  await page.locator('.permission-panel').getByRole('button', { name: '新建账号' }).click()
  await newUserDialog.locator('input[placeholder^="登录名"]').fill('selfcheck-user')
  await newUserDialog.locator('input[placeholder^="初始口令"]').fill('p@ss-1234')
  // ⚠️ 用**类名**点主按钮，不用 `getByRole('button', {name:'创建'})`：
  // antd 6 的按钮里有没有可算作无障碍名的文字，取决于它内部的 span 结构，
  // 而那一层结构不该成为这条断言的前提（实测 getByRole 找不到它，超时信息只是"没有这个按钮"）。
  // 主按钮在弹窗里只有一个（.ant-btn-primary），而"弹窗里的主按钮 = 提交"是这个组件的约定
  await newUserDialog.locator('.ant-btn-primary').click()
  const userCreated = await waitFor(async () =>
    (await page.locator('.permission-panel').innerText()).includes('selfcheck-user'), 8000)
  expect('界面上新建账号之后列表里出现它（前端 ↔ 后端这条线通了）', userCreated)
  expect('新建账号的反馈说清了"一个角色都没有，因此没有任何权限"',
    /已建账号「selfcheck-user」/.test(await page.locator('.permission-panel').innerText())
    && /没有任何权限/.test(await page.locator('.permission-panel').innerText()))

  // ② 同名再建一次 → 必须显示成 409 那句，而不是静默成功、也不是 500
  await page.locator('.permission-panel').getByRole('button', { name: '新建账号' }).click()
  await newUserDialog.locator('input[placeholder^="登录名"]').fill('selfcheck-user')
  await newUserDialog.locator('input[placeholder^="初始口令"]').fill('p@ss-1234')
  await newUserDialog.locator('.ant-btn-primary').click()
  const conflictShown = await waitFor(async () =>
    /名字已被占用/.test(await page.locator('.permission-panel').innerText()), 8000)
  expect('同名账号被拒时界面说成「名字已被占用」（409 与 400 分开处理）', conflictShown)
  // 取消 = 弹窗里那个非主按钮
  await newUserDialog.locator('.ant-btn:not(.ant-btn-primary)').last().click()

  // ③ 编辑弹窗：改显示名 + 停用（这个页面上"修改"与"删除的等价物"都在这一个弹窗 + 行上那枚开关）
  const selfRow = page.locator('.permission-panel .ant-table-row', { hasText: 'selfcheck-user' })
  // ⚠️ 两字按钮在 antd 6 里渲染成「编 辑」（汉字之间插了一个空格，见上面那条断言的注释）
  await selfRow.locator('button', { hasText: /^编\s*辑$/ }).click()
  const editDialog = page.locator('.ant-modal:visible', { hasText: '编辑账号' })
  await editDialog.locator('input[placeholder^="显示名"]').fill('自检账号')
  await editDialog.locator('.ant-switch').click()
  await editDialog.locator('.ant-btn-primary').click()
  const userEdited = await waitFor(async () =>
    /账号「selfcheck-user」已更新（停用）/.test(await page.locator('.permission-panel').innerText()), 8000)
  expect('★ 编辑弹窗改显示名 + 停用：反馈用的是后端那句话', userEdited)
  expect('改完之后那一行显示新的显示名', /自检账号/.test(await selfRow.innerText()))
  expect('停用之后那一行带上了停用标记', /停用/.test(await selfRow.innerText()))

  // ④ 行上那枚开关就是"删除账号"的等价物：点回去 = 重新启用 ——
  // 账号表刻意没有真删除（见 AccountMapper 的注释），所以这里验的是"关得掉、也开得回来"
  await selfRow.locator('.ant-switch').click()
  const userReEnabled = await waitFor(async () =>
    /账号「selfcheck-user」已更新（启用）/.test(await page.locator('.permission-panel').innerText()), 8000)
  expect('★ 开关点回去 = 重新启用（这个界面上没有真删除）', userReEnabled)

  // ⑤ 改口令：只有「重设」，没有「查看」（响应里根本没有口令这一列）
  await selfRow.locator('button', { hasText: /^改口令$/ }).click()
  const passwordDialog = page.locator('.ant-modal:visible', { hasText: '改口令：selfcheck-user' })
  await passwordDialog.locator('input[placeholder="新口令"]').fill('n3w-p@ss')
  await passwordDialog.locator('input[placeholder="再输一遍"]').fill('n3w-p@ss')
  await passwordDialog.locator('.ant-btn-primary').click()
  const passwordChanged = await waitFor(async () =>
    /口令已按新散列保存（旧口令立即失效）/.test(await page.locator('.permission-panel').innerText()), 8000)
  expect('改口令成功，反馈说清了旧口令立即失效', passwordChanged)
}

// ---------- 应用管理 · 权限管理 ----------
//
// 这一节验的是**角色那一半**：角色行上五个入口（编辑 / 工具 / 目录 / 成员 / 删除）里，
// 前四个各打一个写端点，以及核对卡要能对同一份数据给出**两种结论**（允许 / 不允许）——
// 否则"判定到底有没有在工作"在界面上看不出来（一个恒返回 allowed 的接口
// 画出来与真判定一模一样）。
// （账号那一半在 [8.6]；写路径同样只在假后端上跑，理由见那一节。）
console.log('[8.7] 应用管理 · 权限管理')
await navMode('权限管理')
await waitFor(async () => (await page.locator('.permission-panel .ant-table').count()) > 0, 8000)
const permText = await page.locator('.permission-panel').innerText()

expect('页头标题换成了应用管理那一区',
  /AI Agent 应用管理/.test(await page.locator('.app-topbar .app-title').innerText()))
expect('清单为空的角色如实说成「一把工具都调不到」', /清单为空|一把工具都调不到/.test(permText))
expect('角色表渲染出来了（这一页只有角色一张表）',
  (await page.locator('.permission-panel .ant-table').count()) === 1)
{
  // 不挑具体角色名（真后端的数据与假后端不同）：任何一行都必须有这五个入口
  const labels = await page.locator('.permission-panel .ant-table-row').first().locator('button').allInnerTexts()
  // ⚠️ 去空白再比：两字按钮渲染出来是「编 辑 / 工 具 / 目 录 / 成 员」（antd 6 在汉字之间插空格）
  expect('★ 角色行上有五个入口：编辑 / 工具 / 目录 / 成员 / 删除',
    JSON.stringify(labels.map(t => t.replace(/\s+/g, ''))) === JSON.stringify(['编辑', '工具', '目录', '成员', '删除']),
    labels.join(' / '))
}

{
  // 成员不进角色表：谁有它要点「成员」进弹窗才查（弹窗里勾着的就是全部成员）。
  // ⚠️ 只取 thead 里的 `th`：antd 的量尺行用的是 `td`，不会混进来
  const headers = await page.locator('.permission-panel .ant-table-thead th').allInnerTexts()
  expect('★ 角色表里没有「成员」列（谁有它要在「成员」弹窗里查）',
    !headers.some(text => /成员/.test(text)), headers.join(' / '))
}

// ---- 核对卡：同一个人，两种结论 ----
const verifyRow = page.locator('.permission-panel .diag-card', { hasText: '核对：这个人能不能' })
expect('核对卡在场', (await verifyRow.count()) === 1)
await verifyRow.locator('.permission-pick').first().click()
await page.locator('.ant-select-item-option', { hasText: 'zhou' }).first().click()
// 工具也选一把：这个账号一个角色都没有，所以**两半都该拒**。
// 只填目录的话看到的会是"目录不在前缀里"（那是 admin 也有的现象），
// 而这一条要证的恰恰是"没有角色 = 一把工具都调不到"
await verifyRow.locator('.permission-pick').nth(1).click()
await page.locator('.ant-select-item-option', { hasText: 'search_docs' }).first().click()
// ⚠️ 用 `input.permission-dir` 而不是 `.permission-dir input`：那几个类是**控件自己**的，
// 不是包裹它的容器 —— 写成后代选择器时它永远匹配不到，而失败信息只是"超时"
await verifyRow.locator('input.permission-dir').fill('D:\\work\\demo')
// ⚠️ 核对按钮同样**不能用 getByRole('button', {name:'核对'})**：那张卡的标题里也有"核对"两个字，
// 而这个 locator 链是从整张卡往下找的 → 严格模式命中两个 → 超时（第一版就是这么红的）。
// 一个专属类名比"再想一个不会撞的 accessible name"可靠得多
await verifyRow.locator('.permission-verify-go').click()
const deniedShown = await waitFor(async () =>
  /不允许/.test(await verifyRow.innerText()), 6000)
const deniedText = await verifyRow.innerText()
expect('没有角色的账号在核对里被判「不允许」', deniedShown, deniedText.slice(0, 90).replace(/\n/g, ' '))
expect('并且说清了为什么（不在工具白名单里、且列出能调的是哪些）',
  /不在任何角色的工具白名单里/.test(deniedText) && /能调的是/.test(deniedText))

await verifyRow.locator('.permission-pick').first().click()
await page.locator('.ant-select-item-option', { hasText: 'admin' }).first().click()
await verifyRow.locator('input.permission-dir').fill('D:\\work\\demo-other')
await verifyRow.locator('.permission-verify-go').click()
const pathTrap = await waitFor(async () =>
  /不允许/.test(await verifyRow.innerText()), 6000)
const pathTrapText = await verifyRow.innerText()
expect('目录按路径分段比：D:\\work\\demo 不命中 D:\\work\\demo-other',
  pathTrap && /不在任何角色的目录前缀里/.test(pathTrapText),
  pathTrapText.slice(0, 120).replace(/\n/g, ' '))
await page.screenshot({ path: `${SHOT_DIR}/permissions.png`, fullPage: true })

if (EXPECT_REFLECTION) {
  // ---- 写路径：接 [8.6] 的同一份复位状态（那一节已经 __reset 过，这里不再复位） ----
  // ① 角色行的「编辑」：改的是基本信息（名字 / 说明 / 档位），不碰工具、目录与成员 ——
  // 它们是各自整体替换的三份清单，所以各有各的入口
  const editRow = page.locator('.permission-panel .ant-table-row', { hasText: '待配置' })
  await editRow.locator('button', { hasText: /^编\s*辑$/ }).click()
  const roleEditDialog = page.locator('.ant-modal:visible', { hasText: '编辑角色' })
  await roleEditDialog.locator('input[placeholder^="角色名"]').fill('自检-待配置')
  await roleEditDialog.locator('input[placeholder^="这个角色"]').fill('自检改过说明')
  await roleEditDialog.locator('.ant-btn-primary').click()
  const roleEdited = await waitFor(async () =>
    /角色「自检-待配置」已更新/.test(await page.locator('.permission-panel').innerText()), 8000)
  expect('★ 角色行的「编辑」改了名字与说明（写的是 updateRole）', roleEdited)

  const roleRow = page.locator('.permission-panel .ant-table-row', { hasText: '自检-待配置' })

  // ② 工具弹窗：勾一把保存 —— 提交的是整份清单（后端整体替换，不是增删）
  await roleRow.locator('button', { hasText: /^工\s*具$/ }).click()
  const toolsDialog = page.locator('.ant-modal:visible', { hasText: '能调的工具' })
  await toolsDialog.locator('label', { hasText: 'search_docs' }).click()
  await toolsDialog.locator('.ant-btn-primary').click()
  const toolsSaved = await waitFor(async () =>
    /工具清单已替换为 1 把：search_docs/.test(await page.locator('.permission-panel').innerText()), 8000)
  expect('★ 工具弹窗勾选保存之后，反馈说出了新的清单', toolsSaved)

  // ③ 目录弹窗：加一条前缀（「添加」只是加进草稿，保存才写库）
  await roleRow.locator('button', { hasText: /^目\s*录$/ }).click()
  const dirsDialog = page.locator('.ant-modal:visible', { hasText: '能在哪些目录下干活' })
  await dirsDialog.locator('input[placeholder^="目录前缀"]').fill('D:\\work\\demo')
  await dirsDialog.locator('button', { hasText: /^添\s*加$/ }).click()
  await dirsDialog.locator('.ant-btn-primary').click()
  const dirsSaved = await waitFor(async () =>
    /目录前缀已替换为 1 条/.test(await page.locator('.permission-panel').innerText()), 8000)
  expect('★ 目录弹窗添加一条前缀并保存（写的是 setRoleDirectories）', dirsSaved)

  // ④ 成员弹窗：勾一个人保存，再打开清空保存 —— 打的是新端点 PUT /roles/{id}/users，
  // 界面编辑账号↔角色关系只走这一侧
  await roleRow.locator('button', { hasText: /^成\s*员$/ }).click()
  const membersDialog = page.locator('.ant-modal:visible', { hasText: '的成员' })
  await membersDialog.locator('label', { hasText: 'zhou' }).click()
  await membersDialog.locator('.ant-btn-primary').click()
  const memberAdded = await waitFor(async () =>
    /这个角色的成员已替换为 1 个：zhou/.test(await page.locator('.permission-panel').innerText()), 8000)
  expect('★ 成员弹窗勾选保存：反馈说出了新的成员', memberAdded)

  await roleRow.locator('button', { hasText: /^成\s*员$/ }).click()
  await membersDialog.locator('label', { hasText: 'zhou' }).click()
  await membersDialog.locator('.ant-btn-primary').click()
  const memberCleared = await waitFor(async () =>
    /这个角色现在一个成员都没有/.test(await page.locator('.permission-panel').innerText()), 8000)
  expect('清空成员会明说「一个成员都没有」（不是静默成功）', memberCleared)

  // ⑤ 剩下的写路径直接打接口 —— 它们要验的是**后端判据**，不是界面控件，
  // 而界面上的复选框给不出"写错一个字母的工具名"这种输入
  const created = await page.request.post(`${URL}/api/permissions/roles`, {
    data: { name: '自检-只读', description: '自检用', access: 'LISTED' },
  })
  const roleId = (await created.json()).role?.id
  expect('建角色成功', created.ok() && !!roleId, `HTTP ${created.status()} id=${roleId}`)

  const typo = await page.request.put(`${URL}/api/permissions/roles/${roleId}/tools`, {
    data: { tools: [{ toolName: 'search_doc' }] },
  })
  const typoBody = await typo.text()
  expect('写错一个字母的工具名 → 400，且报错里带可用清单',
    typo.status() === 400 && /search_docs/.test(typoBody) && /可用的是/.test(typoBody),
    `HTTP ${typo.status()}`)

  const good = await page.request.put(`${URL}/api/permissions/roles/${roleId}/tools`, {
    data: { tools: [{ toolName: 'search_docs' }, { toolName: 'read_section' }] },
  })
  expect('正确配工具成功', good.ok(), `HTTP ${good.status()}`)

  // 目录前缀也要给：**权限是"工具 ∩ 目录"两半都要过**，
  // 只挂工具时核对仍会拒（理由是"不在任何角色的目录前缀里"）——
  // 这一点正是刚建出来的角色与管理员角色的区别
  const dirs = await page.request.put(`${URL}/api/permissions/roles/${roleId}/directories`, {
    data: { directories: [{ prefix: 'D:\\work\\demo' }] },
  })
  expect('正确配目录前缀成功', dirs.ok(), `HTTP ${dirs.status()}`)

  const userSnapshot = await (await page.request.get(`${URL}/api/permissions`)).json()
  const selfUser = userSnapshot.users.find(u => u.name === 'selfcheck-user')
  expect('自检账号还在（[8.6] 在界面上建的那一个）', !!selfUser)

  // ⑥ 角色侧设成员（新端点）—— 界面上的「成员」弹窗打的就是这一条
  const grantedViaRole = await page.request.put(`${URL}/api/permissions/roles/${roleId}/users`, {
    data: { userIds: [selfUser.id] },
  })
  const grantedViaRoleBody = await grantedViaRole.json()
  expect('★ 角色侧设成员成功，回执里是登录名',
    grantedViaRole.ok() && grantedViaRoleBody.accounts.includes('selfcheck-user'),
    `HTTP ${grantedViaRole.status()}`)

  // ⑦ 未知 userId → 400（与"工具名写错"同一类判据：认不出就当场拒，且带可用清单）
  const badUser = await page.request.put(`${URL}/api/permissions/roles/${roleId}/users`, {
    data: { userIds: [999999] },
  })
  const badUserBody = await badUser.text()
  expect('挂一个不存在的 userId → 400，报错里带可用账号清单',
    badUser.status() === 400 && /这些账号不存在/.test(badUserBody) && /可用的是/.test(badUserBody),
    `HTTP ${badUser.status()}`)

  // ⑧ 核对：这一次的角色是**角色侧**挂上的，所以"允许"证明的是那条链路真的写进去了
  const allowed = await (await page.request.get(
    `${URL}/api/permissions/verify?account=selfcheck-user&tool=search_docs&dir=${encodeURIComponent('D:\\work\\demo')}`,
  )).json()
  expect('角色侧挂上角色之后核对变成「允许」（同一份数据，两种结论）',
    allowed.allowed === true, allowed.reason)

  // ⑨ 账号侧那个旧端点仍然可用：两侧写的是同一张 agent_user_role
  const grantedViaUser = await page.request.put(`${URL}/api/permissions/users/${selfUser.id}/roles`, {
    data: { roleIds: [roleId] },
  })
  const grantedViaUserBody = await grantedViaUser.json()
  expect('账号侧的 PUT /users/{id}/roles 仍然可用（两侧同一张关系表）',
    grantedViaUser.ok() && grantedViaUserBody.roles.includes('自检-只读'),
    `HTTP ${grantedViaUser.status()}`)

  const del = await page.request.delete(`${URL}/api/permissions/roles/${roleId}`)
  const delBody = await del.json()
  expect('删角色时报告影响面（在删除之前数出来的那个数字）',
    del.ok() && delBody.affectedAccounts === 1, `affected=${delBody.affectedAccounts}`)

  // ⑩ 恢复初始状态：自检跑完之后这一区应该还是它一开始的样子
  const resetAgain = await page.request.post(`${URL}/api/permissions/__reset`)
  expect('自检收尾把假后端复位（不留状态给下一次）', resetAgain.ok())
}

// ---------- 应用管理 · agent任务 ----------
//
// 这一节验的是**看板与状态链的联动**，三处最容易出错的：
//   ① 卡片停在哪一列 —— 状态→列的归属由后端算（AgentTaskService.Column），
//      前端自己算一遍的话，卡片会出现在错误的列上，而"现在等谁"这个问题的答案就错了；
//   ② 点一个动作之后卡片**真的换列了**，而不是只弹了一句反馈；
//   ③ 409 那条路：别人先把一条确认项答掉之后，界面上那个按钮看起来还能点 ——
//      这时要出现"这条任务的状态已经变了"，而不是"你填错了"。
//
// ⚠️ 真后端那条路径上**一个字节都不写库**（与用户管理 / 权限管理同一条纪律）：
// 造数据、推进、答复、取消都只在假后端上跑。真后端只断言这一页渲染得出来。
//
// ⚠️ 这一节**在未登录下跑**（看板 = 全量）：下面那一大段要断言"点一下卡片真的换列了、
// 列里的条数 ±1"，而按人筛过之后同一列可能只剩一张卡片，那些计数断言就测不到它要说的事。
// 「登录之后看板只剩我的待办」这一小段放在 [11]（那里本来就处于登录态）——
// 它要一次 reload 才拿得到登录后的那一份，而 reload 会把前面几节攒下的结果清掉，只能排在最后。
console.log('[8.8] 应用管理 · agent任务')
await navMode('agent任务')
await waitFor(async () => (await page.locator('.task-panel .ant-card').count()) > 0, 8000)

/**
 * 各列的卡片数，按列的 key 读（`.task-column-count` 上的 data-count 就是界面显示的那个数）。
 * 读界面上的数而不是重新调一次接口：断言的是"用户看到的是不是对的"。
 */
const taskColumnCounts = () => page.$$eval('.task-column', els => Object.fromEntries(
  els.map(el => [
    el.dataset.column,
    Number(el.querySelector('.task-column-count')?.dataset.count ?? -1),
  ])))

/**
 * 关掉详情弹窗，并**断言它真的没了**。
 *
 * <p>少断言那一下的后果很具体（实测踩到一次）：弹窗没关掉时，后面 `runAgent` 里那次
 * 「刷新」会被 `.ant-modal-wrap` 拦住，Playwright 一直重试直到 30 秒超时 ——
 * 报出来的是 `ant-modal-wrap … intercepts pointer events`，看起来像"刷新按钮坏了"，
 * 而真正的原因（上一步那个弹窗没关）在这 30 秒里已经看不出是哪一处了。
 * 这四处点击都要走这一步：同一段流程里连点几次关，少一处静默失败就够毁掉整节的定位。
 */
const closeTaskModal = async () => {
  await page.locator('.ant-modal:visible .ant-modal-footer .ant-btn').last().click()
  const closed = await waitFor(async () => (await page.locator('.ant-modal:visible').count()) === 0, 5000)
  expect('关掉详情弹窗（它开着会拦住后面那些点击）', closed,
    `还开着 ${await page.locator('.ant-modal:visible').count()} 个`)
}

if (EXPECT_REFLECTION) {
  // 这一节的基准：全量（未登录）下每一列各有几张卡片
  const initialCounts = await taskColumnCounts()
  expect('看板五列都在，顺序与后端的列定义一致',
    JSON.stringify(Object.keys(initialCounts))
      === JSON.stringify(['new', 'pending', 'executing', 'acceptance', 'completed']),
    Object.keys(initialCounts).join(' / '))
  expect('★ 假数据的每一列都有卡片（分组本身才验得出来）',
    Object.values(initialCounts).every(count => count >= 1), JSON.stringify(initialCounts))

  const boardText = await page.locator('.task-board').innerText()
  expect('卡片写出「发布人 → 任务人」与当前执行人',
    /admin → zhou/.test(boardText) && /当前执行人/.test(boardText))
  expect('待验收那条带着执行结果', /已补上说明段落/.test(boardText))

  // ---- 三个"人"各给两份：库里那份是用户 id，显示那份是关联查询出来的姓名 ----
  //
  // 库里存的是 `agent_user.id`（姓名可以改，而"当时是谁派的、派给谁"要一直指向同一个人），
  // 所以响应体必须同时给 id 与姓名。只断姓名的话，一个"把 id 那两格漏掉"的实现照样全绿；
  // 只断 id 的话，一个"把 id 直接显示给用户"的实现也全绿 —— 两条一起断才钉得住这个契约。
  const allBoard = await (await page.request.get(`${URL}/api/agent-tasks`)).json()
  const cardById = id => allBoard.columns.flatMap(c => c.tasks).find(t => t.id === id)
  const idCard = cardById(3)
  expect('★ 卡片上三个"人"都带 id 与姓名两份（库里存 id，界面显示姓名）',
    idCard.publisherId === 1 && idCard.publisher === 'admin'
    && idCard.assigneeId === 2 && idCard.assignee === 'zhou'
    && idCard.currentExecutorId === 2 && idCard.currentExecutor === 'zhou',
    JSON.stringify({ p: `${idCard.publisherId}/${idCard.publisher}`,
      a: `${idCard.assigneeId}/${idCard.assignee}`,
      e: `${idCard.currentExecutorId}/${idCard.currentExecutor}` }))
  // 球在 agent 手上的那一格：执行人 id 是空的，而显示的那份由后端按 agentId 拼出来
  const agentCard = cardById(5)
  expect('★ 球在 agent 手上时执行人 id 为空、显示的是 agent#…（后端按 agentId 拼的字）',
    agentCard.currentExecutorId === null && agentCard.currentExecutor === `agent#${agentCard.agentId}`,
    JSON.stringify({ id: agentCard.currentExecutorId, label: agentCard.currentExecutor }))

  // ---- 长文字默认夹住：夹掉了才出现「展开全文」，点开能看到剩下的字 ----
  //
  // 这一条要断的是几何：断言"文字还在 DOM 里"毫无意义（`overflow: hidden` 之下它一直在，
  // 自检第一版就是这么写的，`toContainText` 直接假绿）。所以量的是 `scrollHeight`
  // 与 `clientHeight` 的关系 —— 前者是完整内容的高度，后者是被 `max-height` 截过之后的高度。
  //
  // ⚠️ "夹掉了没有"是 `Clamped` 挂载之后量出来的（首帧一律按没夹掉渲染），所以下面**先等
  // 它量完再断言几何**：直接读会读到那一帧展开的样子（实测：内容 162px / 露出 162px，
  // 而失败信息看起来像"max-height 没生效"）。开关在不在、文字在不在都不是判据 ——
  // 开关是量完才画的，而文字一直就在 DOM 里。
  //
  // ⚠️ 状态一律用 `waitForState` 取回**原始读数**再断言，不要 `waitFor` 的布尔值：
  // 那一头返回的是 true/false（见 `waitFor`），拿它当读数用只会打出 `undefinedpx` ——
  // 而失败信息里没有真实数字时，"差 1px"与"根本没量到"看起来一模一样。
  //
  // 「已完成」列里那张刻意写长描述的卡片（mock/task-routes.mjs 的 id 10）是唯一会夹住的：
  // 描述只要一行时「展开全文」永远不出现，这条断言也就断不到任何东西。
  const longCard = page.locator('.task-card[data-task-id="10"]')
  const longTitle = longCard.locator('.task-card-title')
  const longToggle = longCard.locator('.task-card-toggle')
  const titleState = () => longTitle.evaluate(el => ({
    // scrollHeight 是内容高度，与展开/收起那道 max-height 过渡无关
    content: el.scrollHeight,
    visible: el.clientHeight,
    width: el.clientWidth,
    clamped: el.dataset.clamped,
    classes: el.className,
  }))
  /** 轮询直到这一段达到某个状态，返回**最后一次读数**（不是布尔值 —— 见上面那条注释）。 */
  const waitForState = async (ok, timeoutMs = 5000) => {
    const deadline = Date.now() + timeoutMs
    let last = await titleState()
    while (!ok(last) && Date.now() < deadline) {
      await page.waitForTimeout(200)
      last = await titleState()
    }
    return last
  }

  const collapsed = await waitForState(s => s.clamped === 'yes' && s.content > s.visible + 1)
  const describe = s => `内容 ${s.content}px / 露出 ${s.visible}px（列宽 ${s.width}px，${s.classes}）`
  expect('★ 长描述那张卡片给出「展开全文」入口（夹掉了才画这个开关）',
    collapsed.content > collapsed.visible + 1 && (await longToggle.count()) === 1,
    describe(collapsed))
  expect('★ 夹住时只露出前几行，剩下的字被挡在外面（内容比露出来的地方高）',
    collapsed.content > collapsed.visible + 1, describe(collapsed))

  await longToggle.click()
  const expanded = await waitForState(s => s.clamped === 'no' && s.visible === s.content)
  expect('★ 展开后整段文字都露出来（露出的高度 = 内容高度）',
    expanded.clamped === 'no' && expanded.visible === expanded.content, describe(expanded))
  expect('★ 展开之后同一个开关变成「收起」', /收\s*起/.test(await longToggle.innerText()))
  // ★ 点开关**不能**顺手把详情打开：卡片整张是"点开详情"的热区，而开关浮在它上面。
  // 少了这一条，一个"开关与卡片都被点到"的实现会照样全绿（症状是每次展开都弹出详情）
  expect('★ 点展开开关不会连带打开详情弹窗',
    (await page.locator('.ant-modal:visible').count()) === 0)

  await longToggle.click()
  const recollapsed = await waitForState(s => s.clamped === 'yes' && s.visible === collapsed.visible)
  expect('★ 再点「收起」回到夹住时的高度（同一个开关两个方向都管用）',
    recollapsed.clamped === 'yes' && recollapsed.visible === collapsed.visible,
    describe(recollapsed) + `（收起时是 ${collapsed.visible}px）`)

  // ---- 「执行中」那一列收**三个**状态：已确认需求 / 已确认计划 / 执行中 ----
  //
  // 它们对看板是同一件事：球在 agent 手上，人现在点不动。分成三列会让一眼看去
  // 像是"有三件事在等我"，而其实一件都不在等。三个状态的差别靠各自的状态标签表达。
  const executingColumn = page.locator('.task-column[data-column="executing"]')
  const executingText = await executingColumn.innerText()
  expect('★ 「执行中」列收三个状态，列头把它们都写出来',
    ['已确认需求', '已确认计划', '执行中'].every(label => executingText.includes(label)),
    initialCounts.executing + ' 条')
  expect('★ 那一列里的三张卡片各带自己的状态标签（同列靠标签区分）',
    (await executingColumn.locator('.task-card').count()) === 3, initialCounts.executing + ' 条')
  // 三条里有一条是**跑失败停在执行中**的：这一格要让人看出"它没跑成、在等我处置"，
  // 而它唯一的信号就是执行结果那一段（卡片上写着停因）—— 少了这一条，
  // 一个"把没跑成的执行中显示成正常在跑"的实现会照样全绿
  expect('★ 停住的「执行中」把"没有正常结束"写在卡片上（否则它看起来像还在跑）',
    /本次执行没有正常结束/.test(executingText) && /停因：MODEL_ERROR/.test(executingText))

  // ---- 「待处理」那一列收**三个**状态：两个"待确认" + 「权限不足」 ----
  //
  // 它们等的东西不同（答复确认项 / 处置一次权限不足），但对看板是同一个答案：球都在人手上。
  // 单开一列的话那一列多数时候是空的，而"现在有几件事在等我"反而要拼两个列名才答得出。
  // 「权限不足」那张卡片还要能一眼看出它记的是**结论**而不是执行结果 ——
  // 两者共用 `result` 字段，标签按状态换（写成"执行结果"会读成"这次跑过了"）。
  const pendingColumn = page.locator('.task-column[data-column="pending"]')
  const pendingText = await pendingColumn.innerText()
  expect('★ 「待处理」列收三个状态，列头把「权限不足」也写出来',
    ['待确认需求', '待确认计划', '权限不足'].every(label => pendingText.includes(label)),
    initialCounts.pending + ' 条')
  expect('★ 权限不足那张卡片写的是「权限不足：…」而不是「执行结果：…」，并带着结论原文',
    /权限不足：/.test(pendingText) && !/执行结果：/.test(pendingText)
    && /没有角色就是没有任何目录授权/.test(pendingText))

  // 「已取消」与「已完成」同列：那一条必须在**已完成**那一列里，且带着取消原因 ——
  // 两者唯一的区别就是状态标签与这条原因（结论与结局同列这件事的判据）
  const completedText = await page.locator('.task-column[data-column="completed"]').innerText()
  expect('★ 已取消那条落在「已完成」列里，并带着取消原因',
    /需求方撤回了这条调研/.test(completedText) && /已取消/.test(completedText)
    // 同列另一条（验收通过的）也在：一列收两个状态，只放一条就看不出这件事
    && /已改成 CI_MOCK_PORT/.test(completedText), initialCounts.completed + ' 条')

  /**
   * 显式推一轮 agent 那几格（真后端由定时任务驱动，见 web/mock/task-routes.mjs 文件头）。
   *
   * `?id=` 只推一条、`?steps=1` 每条最多推一格 —— 自检要看的正是"推到哪一格"，
   * 一次推到底就看不出中间那几格了。
   */
  const runAgent = async query => {
    const response = await page.request.post(`${URL}/api/agent-tasks/__run-agent${query ?? ''}`)
    const body = await response.json()
    const refresh = page.locator('.task-panel button', { hasText: /刷\s*新/ }).first()
    /**
     * 点「刷新」前先等**没有开着的弹窗**：`.ant-modal-wrap` 铺满整个视口，
     * 它开着的时候 Playwright 对刷新按钮会一直重试到超时（失败信息是
     * `ant-modal-wrap … intercepts pointer events`，看起来像"刷新按钮坏了"）。
     * 上一次踩到它是 30 秒后超时，而那 30 秒里真正的原因（某个弹窗没关掉）已经看不出来了 ——
     * 所以这里等不到就直接说清楚。
     */
    const noModal = await waitFor(async () => (await page.locator('.ant-modal:visible').count()) === 0, 5000)
    if (!noModal) {
      const stuck = await page.locator('.ant-modal:visible').first().innerText()
      throw new Error(`推之前点不了「刷新」：还有弹窗开着 —— ${stuck.replace(/\n/g, ' ').slice(0, 120)}`)
    }
    // ⚠️ 两字按钮在 antd 6 里渲染成「刷 新」（汉字之间插一个空格）——
    // 写成 `getByRole('button', { name: '刷新', exact: true })` 一直等不到，
    // 而失败信息（waiting for locator … 超时）看起来像"这个按钮不存在"（同 [8.6] 的「角 色」）
    await refresh.click()
    return body
  }

  // ---- 新发布：没有推进按钮（下一步是 agent 做的）→ 推一格进「执行中」 ----
  const newColumn = page.locator('.task-column[data-column="new"]')
  await newColumn.locator('.task-card').first().click()
  const detail = page.locator('.ant-modal:visible', { hasText: '确认项' })
  await waitFor(async () => (await detail.innerText()).includes('确认项'), 5000)
  const openedId = Number(await newColumn.locator('.task-card').first().getAttribute('data-task-id'))

  expect('打开「新发布」的任务：说清它还没有确认项（它不等于「待确认需求」）',
    /还没有确认项/.test(await detail.innerText()))
  // ★ 这一条是这一版的行为变化：新发布那一格由 agent 判需求，人点不动 ——
  // 画一个"看起来能推进"的按钮比不画更坏（用户会反复点，每次都回一句状态不允许）
  expect('★ 「新发布」没有「发起需求确认」按钮，并写明这一步由 agent 任务执行做',
    (await detail.getByRole('button', { name: '发起需求确认', exact: true }).count()) === 0
    && /这一步由 agent 任务执行做/.test(await detail.innerText()))
  await closeTaskModal()

  const round1 = await runAgent(`?id=${openedId}&steps=1`)
  const afterFirstStep = await taskColumnCounts()
  expect('★ agent 推一格：卡片从「新任务」移进「执行中」（已确认需求）',
    afterFirstStep.new === initialCounts.new - 1 && afterFirstStep.executing === initialCounts.executing + 1,
    `moved=${JSON.stringify(round1.moved)} / new ${initialCounts.new}→${afterFirstStep.new}`
    + ` / executing ${initialCounts.executing}→${afterFirstStep.executing}`)
  expect('（准备）这一次推的是「新发布 → 已确认需求」',
    round1.moved.length === 1 && round1.moved[0].from === 'NEW'
    && round1.moved[0].to === 'CONFIRMED_REQUIREMENT', JSON.stringify(round1.moved))

  // ---- 再推一格：拆出 5 个任务点（≥ 阈值）→ 待确认计划，并生成计划确认项 ----
  await runAgent(`?id=${openedId}`)
  const afterPlan = await taskColumnCounts()
  expect('★ agent 再推一格：任务点 ≥ 5 就转到「待处理」等计划确认',
    afterPlan.executing === afterFirstStep.executing - 1 && afterPlan.pending === initialCounts.pending + 1,
    `executing ${afterFirstStep.executing}→${afterPlan.executing} / pending ${initialCounts.pending}→${afterPlan.pending}`)
  const planned = await (await page.request.get(`${URL}/api/agent-tasks/${openedId}`)).json()
  expect('★ 计划确认项的正文里带着拆出来的任务点（人批的就是这份清单）',
    planned.task.status === 'PENDING_PLAN'
    && planned.confirms.some(c => c.type === 'PLAN' && /拆出来的任务点/.test(c.description ?? '')
      && /5\. /.test(c.description ?? '')),
    planned.confirms.map(c => c.type).join(' / '))

  // ---- 答复确认项：答完球交给 agent（答复本身不再生成下一条确认项） ----
  await page.locator(`.task-column[data-column="pending"] .task-card[data-task-id="${openedId}"]`).click()
  const planDetail = page.locator('.ant-modal:visible', { hasText: '确认项' })
  await waitFor(async () => /待答复/.test(await planDetail.innerText()), 8000)
  expect('详情里出现第 3 步的计划确认项',
    /第 3 步/.test(await planDetail.innerText()) && /计划确认/.test(await planDetail.innerText()))
  // ⚠️ 两字按钮在 antd 6 里渲染成「同 意」（汉字之间插一个空格）——
  // 写成 /^同意$/ 时连真的按钮也匹配不上，断言会假绿（同 [8.6] 的「角 色」）
  await planDetail.locator('button', { hasText: /^同\s*意$/ }).first().click()
  const answered = await waitFor(async () =>
    /已答复「同意」/.test(await page.locator('.task-panel').innerText())
    && /已确认计划/.test(await planDetail.innerText()), 8000)
  const afterAnswer = await (await page.request.get(`${URL}/api/agent-tasks/${openedId}`)).json()
  expect('★ 答复计划之后落到「已确认计划」，且答复本身不生成下一条确认项（下一条由 agent 那一步生成）',
    answered && afterAnswer.task.status === 'CONFIRMED_PLAN'
    && afterAnswer.confirms.every(c => c.answered),
    `confirms=${afterAnswer.confirms.length} 条，已答复 ${afterAnswer.confirms.filter(c => c.answered).length} 条`)

  // ---- 再推一格：判过授权就**直接开跑**（链上没有"等人批授权"那一格） ----
  //
  // 平台判断通过之后不再生成授权确认项、也不再等人答复：这一格只产生「执行中」。
  // 授权确认项那一族断言随之一并消失 —— 留着它就是在钉一个已经删掉的分支。
  //
  // ⚠️ 推之前先**关掉详情**：`__run-agent` 之后脚本要点看板上的「刷新」，
  // 而 `.ant-modal-wrap` 铺满视口时那次点击会一直重试到超时（见 runAgent 里那段说明）
  await closeTaskModal()
  await runAgent(`?id=${openedId}`)
  const judgedRun = await (await page.request.get(`${URL}/api/agent-tasks/${openedId}`)).json()
  expect('★ agent 再推一格：授权判断通过就直接进「执行中」，不生成授权确认项',
    judgedRun.task.status === 'EXECUTING'
    && judgedRun.confirms.every(c => c.type !== 'AUTHORIZATION'),
    `${judgedRun.task.statusLabel} / 确认项 ${judgedRun.confirms.map(c => c.type).join(' / ') || '（只有前面那两条）'}`)
  const afterJudgeOpen = await taskColumnCounts()
  expect('（准备）这一格让卡片留在「执行中」那一列（球在 agent 手上）',
    afterJudgeOpen.executing === afterPlan.executing + 1 && afterJudgeOpen.pending === afterPlan.pending - 1,
    `executing ${afterPlan.executing}→${afterJudgeOpen.executing} / pending ${afterPlan.pending}→${afterJudgeOpen.pending}`)

  // ---- 409：别人先答过同一条确认项（两个人在看同一张看板的常态） ----
  //
  // 脚本直接调后端替"另一个人"把当前待答的那条答掉，然后界面上的那个按钮**看起来还能点** ——
  // 点下去必须收到 409，并被说成"状态已经变了"，而不是"你填错了"。
  // 用 id 3 那条（第 3 步在等计划确认）而不是刚推完的 openedId：后者已经进「执行中」，
  // 它那一格本来就没有确认项可答 —— 拿它验 409 等于在验一个不存在的按钮。
  const conflictId = 3
  await page.locator(`.task-column[data-column="pending"] .task-card[data-task-id="${conflictId}"]`).click()
  const conflictDetail = page.locator('.ant-modal:visible', { hasText: '确认项' })
  await waitFor(async () => /待答复/.test(await conflictDetail.innerText()), 8000)
  const current = await (await page.request.get(`${URL}/api/agent-tasks/${conflictId}`)).json()
  const pendingConfirm = current.confirms.find(c => !c.answered)
  const otherAnswer = await page.request.post(
    `${URL}/api/agent-tasks/${conflictId}/confirms/${pendingConfirm.id}/answer`,
    { data: { answer: '同意' } })
  expect('（准备）脚本替另一个人先答掉这条确认项', otherAnswer.ok(), `HTTP ${otherAnswer.status()}`)

  await conflictDetail.locator('button', { hasText: /^同\s*意$/ }).first().click()
  const conflictShown = await waitFor(async () =>
    /状态已经变了/.test(await page.locator('.task-panel').innerText()), 8000)
  expect('★ 别人已经答过之后，界面上那次点答被说成「状态已经变了」（409 与 400 分开处理）',
    conflictShown)
  await closeTaskModal()

  // ---- 发布一条新任务（界面走一遍表单） ----
  //
  // 这一版的两处行为变化都在这个弹窗里，所以三条断言都长在它上面：
  //   ① 发布人 / 任务人是**搜索选择器**（候选来自 /api/permissions/users/search，最多 5 条），
  //      不再是能随便打字的输入框 —— 名字在库里是快照，打错一个字没有约束拦得下；
  //   ② 表单里**没有 agentId 这一格**；
  //   ③ 发布出来的任务 agentId 是空的，平台第一次接手（__run-agent）时才写进去。
  await page.locator('.task-panel').getByRole('button', { name: '发布任务', exact: true }).click()
  // ⚠️ 弹窗要按**标题**过滤，不能按 placeholder 过滤：placeholder 是属性、不进 innerText，
  // 而 `hasText` 匹配的是渲染出来的文字 —— 按 placeholder 过滤会一直等不到，
  // 而失败信息（"waiting for locator …" 超时）看起来像"弹窗没打开"
  const createDialog = page.locator('.ant-modal:visible', { hasText: '发布任务' })

  expect('★ 发布表单里没有 agentId 这一格（它由平台在接手任务时写，不由发布人填）',
    (await createDialog.locator('input[placeholder*="agentId"]').count()) === 0
    && (await createDialog.getByText('agent-id').count()) >= 1,
    '（表单里只有那两格选择器 + 一段说明文字，说明文字里写着 agentId 由平台写入）')

  /**
   * 在某一格搜索选择器里搜一个词、点中候选。
   *
   * 候选由后端的 `/api/permissions/users/search` 给（`filterOption={false}`），
   * 所以这里必须先输入搜索词、再等下拉出现 —— 直接点开是一个空下拉（那是刻意的：
   * 空搜索词在后端是 400，界面因此"没输入就不发请求"）。
   */
  const pickUser = async (index, keyword, expected) => {
    const picker = createDialog.locator('.ant-select').nth(index)
    await picker.click()
    await picker.locator('input').fill(keyword)
    const option = page.locator('.ant-select-dropdown:visible .ant-select-item-option',
      { hasText: expected }).first()
    await option.waitFor({ state: 'visible', timeout: 8000 })
    await option.click()
  }

  await pickUser(0, 'adm', 'admin')
  // 任务人刻意选**停用**的 li：候选里要看得见"他现在是停用的"，
  // 而"当时派给谁"与"他现在能不能登录"是两件事 —— 所以它仍然可选
  await pickUser(1, 'li', 'li')
  expect('★ 两格都是搜索选择器：搜索词进后端，候选里带得出来账号的显示名与停用标记',
    /初始管理员（admin）/.test(await createDialog.innerText())
    && /李工（li）/.test(await createDialog.innerText())
    && /已停用/.test(await createDialog.innerText()),
    (await createDialog.innerText()).replace(/\n/g, ' ').slice(0, 120))

  await createDialog.locator('textarea[placeholder^="任务描述"]').fill('自检造的一条任务')
  await createDialog.locator('.ant-btn-primary').click()
  const createdOk = await waitFor(async () =>
    /已发布任务/.test(await page.locator('.task-panel').innerText()), 8000)
  const afterCreate = await taskColumnCounts()
  expect('★ 界面上发布任务：「新任务」列 +1，反馈说明它落在哪一列',
    createdOk && afterCreate.new === afterPlan.new + 1,
    `new ${afterPlan.new}→${afterCreate.new}`)

  const createdCard = page.locator('.task-column[data-column="new"] .task-card', { hasText: '自检造的一条任务' })
  const createdId = Number(await createdCard.getAttribute('data-task-id'))
  expect('★ 发布时没有 agent：卡片上还不出现 agent#…（那一列由平台接手时写入）',
    !/agent#/.test(await createdCard.innerText()),
    (await createdCard.innerText()).replace(/\n/g, ' '))

  // agent 第一次接手这条任务 → 平台把 agentId 写进去（假后端用 AGENT_ID = 1，
  // 与真后端的 ai.agent.task.agent-id 同一个键）
  await runAgent(`?id=${createdId}&steps=1`)
  const stampedCard = page.locator(`.task-column[data-column="executing"] .task-card[data-task-id="${createdId}"]`)
  const stamped = await waitFor(async () => /agent#1/.test(await stampedCard.innerText()), 8000)
  expect('★ 平台第一次接手这条任务时写入 agentId（卡片上出现 agent#1）',
    stamped, (await stampedCard.innerText()).replace(/\n/g, ' '))

  // ---- 取消：原因必填，取消后卡片进「已完成」列（与已完成同列）且原因显示在卡片上 ----
  // 这条任务现在停在「执行中」那一列（刚被 agent 接手过一格），从那里取消它
  await stampedCard.click()
  const cancelDetail = page.locator('.ant-modal:visible', { hasText: '确认项' })
  await waitFor(async () => (await cancelDetail.innerText()).includes('确认项'), 5000)
  await cancelDetail.getByRole('button', { name: '取消任务', exact: true }).click()
  const cancelDialog = page.locator('.ant-modal:visible', { hasText: '取消原因必填' })
  await cancelDialog.locator('textarea').fill('自检：这条不需要了')
  await cancelDialog.locator('.ant-btn-primary').click()
  const cancelledOk = await waitFor(async () =>
    /已取消：自检：这条不需要了/.test(await page.locator('.task-panel').innerText()), 8000)
  const afterCancel = await taskColumnCounts()
  expect('★ 取消之后卡片进「已完成」列（没有单独的已取消列），原因显示在卡片上',
    cancelledOk && afterCancel.completed === initialCounts.completed + 1
    && !('cancelled' in afterCancel)
    && /自检：这条不需要了/.test(await page.locator('.task-column[data-column="completed"]').innerText()),
    `completed ${initialCounts.completed}→${afterCancel.completed}`)
  // 关掉详情弹窗：后面几节要切模式，留一个开着的弹窗在那里只是在制造噪声
  await cancelDetail.locator('.ant-modal-footer .ant-btn').click()

  // ---- 「权限不足」：判断不通过那一格的两个动作（已修改任务 / 取消任务） ----
  //
  // 这一格等的**不是**确认项的答复，而是"取消还是重走一遍" —— 所以它的按钮、文案、
  // 点完之后回到哪一列都与「待确认」那三条不同。四件事都要验：
  //   ① 结论显示出来了（它是用户决定"改什么"的唯一依据）；
  //   ② 给的是 revise 与 cancel，**没有**「答复确认项」；
  //   ③ 任务描述在这一格**可改**，改过的那一份与这次选择一起落库；
  //   ④ 点「已修改任务」之后卡片真的回到「新任务」列（步骤 +1）。
  const deniedCard = page.locator('.task-column[data-column="pending"] .task-card[data-task-id="12"]')
  const deniedBefore = await taskColumnCounts()
  const deniedBeforeDetail = (await (await page.request.get(`${URL}/api/agent-tasks/12`)).json()).task
  const deniedStepBefore = deniedBeforeDetail.currentStep
  await deniedCard.click()
  const deniedDetail = page.locator('.ant-modal:visible', { hasText: '确认项' })
  await waitFor(async () => /权限不足/.test(await deniedDetail.innerText()), 8000)
  const deniedText = await deniedDetail.innerText()
  expect('★ 权限不足的详情说清"这次执行没有开始"，并把后端给的结论原样显示出来',
    /权限不足：这次执行没有开始/.test(deniedText) && /没有角色就是没有任何目录授权/.test(deniedText),
    deniedText.replace(/\n/g, ' ').slice(0, 100))
  expect('★ 权限不足给的是「已修改任务」与「取消任务」，没有「答复确认项」',
    (await deniedDetail.getByRole('button', { name: '已修改任务', exact: true }).count()) === 1
    && (await deniedDetail.getByRole('button', { name: '取消任务', exact: true }).count()) === 1
    && (await deniedDetail.getByRole('button', { name: '答复确认项', exact: true }).count()) === 0)
  // ★ 这一格在**等人**，不在等 agent：少了这一条，一个把"没有 advance/answer"当成
  // "等 agent"的实现会照样全绿，而它给用户的指引完全相反（让人干等一台不会动的机器）
  expect('★ 权限不足那一格不写"这一步由 agent 任务执行做"（它在等人处置）',
    !/这一步由 agent 任务执行做/.test(deniedText))

  // ★ 描述这一格：可改的**输入框**（预填库里那一份），而不是只读的一段文字。
  // 只断"有文字"是不够的 —— 只读的呈现同样有文字，而"改了之后能不能提交上去"才是这一版的内容。
  // 按 placeholder 找而不是"弹窗里第一个 textarea"：详情弹窗里还挂着"登记执行结果"那一个（另一些状态下）
  const descBox = deniedDetail.locator('textarea[placeholder^="这条任务要做什么"]')
  const descPrefilled = await waitFor(async () => (await descBox.count()) === 1
    && (await descBox.inputValue()) === deniedBeforeDetail.description, 5000)
  expect('★ 权限不足的详情里「任务描述」是可改的输入框，且预填库里那一份',
    descPrefilled,
    `输入框 ${await descBox.count()} 个 / 值「${(await descBox.count()) ? await descBox.inputValue() : ''}」`)

  const reviseButton = deniedDetail.getByRole('button', { name: '已修改任务', exact: true })
  await descBox.fill('')
  expect('★ 描述被清空时「已修改任务」点不动（空描述不提交：后端也会回 400）',
    await reviseButton.isDisabled(), `disabled=${await reviseButton.isDisabled()}`)

  // 改一段再提交：改过的那一份要与这次选择**一起**落库
  const revisedDesc = '自检：把周报模板里的统计口径换成新版，并附一份口径对照表'
  await descBox.fill(revisedDesc)
  await reviseButton.click()
  const revisedShown = await waitFor(async () =>
    /已答复「已修改任务」/.test(await page.locator('.task-panel').innerText()), 8000)
  const afterRevise = await taskColumnCounts()
  expect('★ 点「已修改任务」之后卡片回到「新任务」列（整条链重走一遍，不是就地重试）',
    revisedShown && afterRevise.new === deniedBefore.new + 1 && afterRevise.pending === deniedBefore.pending - 1,
    `new ${deniedBefore.new}→${afterRevise.new} / pending ${deniedBefore.pending}→${afterRevise.pending}`)
  expect('★ 反馈里说清"改过的描述与这次选择一起保存了"（用户要知道自己改的那一段去哪了）',
    /这一份任务描述与这次选择一起保存了/.test(await page.locator('.task-panel').innerText()),
    (await page.locator('.task-panel').innerText()).replace(/\n/g, ' ').slice(0, 120))
  const revised = await (await page.request.get(`${URL}/api/agent-tasks/12`)).json()
  expect('★ 重发之后：状态是「新发布」、步骤 +1，上一次的「权限不足」结论留在记录里',
    revised.task.status === 'NEW' && revised.task.currentStep === deniedStepBefore + 1
    && /上一次「权限不足」的结论（留档）/.test(revised.task.result ?? ''),
    `${revised.task.status} / 第 ${deniedStepBefore}→${revised.task.currentStep} 步`)
  // ★ 描述是与这次选择**一起**落库的：库里那一份必须就是编辑框里提交上去的文本
  // （分两次写的话，"状态回到新发布、描述还是旧的"那一小段里，重走的判据是旧文本）
  expect('★ 改过的任务描述落库了（库里那一份 = 编辑框里提交的那一份）',
    revised.task.description === revisedDesc, `「${revised.task.description}」`)
  const newDescShown = await waitFor(async () =>
    (await page.locator('.task-column[data-column="new"] .task-card[data-task-id="12"]').innerText())
      .includes('口径对照表'), 8000)
  expect('★ 新的描述显示在「新任务」列那张卡片上（不是在库里改了、界面上还是旧的）',
    newDescShown,
    (await page.locator('.task-column[data-column="new"] .task-card[data-task-id="12"]')
      .innerText()).replace(/\n/g, ' ').slice(0, 100))
  // 状态已经不是「权限不足」→ 那一格回到只读的一段文字（"可改"是这一格的属性，不是描述的属性）
  const backToReadOnly = await waitFor(async () => (await descBox.count()) === 0
    && (await deniedDetail.innerText()).includes(revisedDesc), 8000)
  expect('★ 重走之后描述不再是输入框，详情里显示的是改过的那一份', backToReadOnly,
    `输入框 ${await descBox.count()} 个`)
  // 关掉它再往下走：下面的 runAgent 会点看板上的「刷新」，而开着的弹窗会挡住那次点击
  // （失败信息是 Playwright 的点击超时 + "ant-modal-wrap intercepts pointer events"，
  // 看起来像"刷新按钮坏了"）
  await closeTaskModal()

  // ---- 判断不通过那条路：任务人没有目录授权 ⇒ 「权限不足」，且**不生成授权确认项** ----
  //
  // 这一条用接口造任务而不是走发布弹窗：这一节验的是判定与落点，而"发布弹窗那两格选人"
  // 上面已经单独验过了。任务人刻意选 zhou（账号表里那个**没有角色**的账号）：
  // 没有角色 ⇒ 没有任何目录前缀 ⇒ 这次要动的目录不在授权范围内 ⇒ 判不通过。
  // 描述里带「小任务」让假后端拆出 2 个任务点（< 阈值 5）⇒ 跳过计划确认，直接进判断那一格。
  const deniedCreate = await page.request.post(`${URL}/api/agent-tasks`, {
    data: { publisher: 'admin', assignee: 'zhou', description: '自检：小任务 —— 任务人没有目录授权的那一条' },
  })
  expect('（准备）发布一条任务人是 zhou（没有角色）的任务', deniedCreate.ok(),
    `HTTP ${deniedCreate.status()}`)
  const deniedId = (await deniedCreate.json()).task.id
  const deniedRound = await runAgent(`?id=${deniedId}`)
  const judged = await (await page.request.get(`${URL}/api/agent-tasks/${deniedId}`)).json()
  expect('★ 授权判断不通过：任务停在「权限不足」（链上没有"待授权"那一格可停），结论里点名缺的是目录前缀',
    judged.task.status === 'INSUFFICIENT_PERMISSION'
    && /zhou/.test(judged.task.result ?? '') && /目录前缀/.test(judged.task.result ?? ''),
    `${deniedRound.moved.map(m => `${m.from}→${m.to}`).join(' / ')}`)
  expect('★ 判断不通过时一条授权确认项都不生成（授权不再等人批）',
    judged.confirms.every(confirm => confirm.type !== 'AUTHORIZATION'),
    judged.confirms.map(confirm => confirm.type).join(' / ') || '（一条确认项都没有）')
  const afterJudge = await taskColumnCounts()
  expect('★ 判断不通过那条卡片落在「待处理」列里（与两条"待确认"同列）',
    afterJudge.pending === afterRevise.pending + 1 && afterJudge.executing === afterRevise.executing,
    `pending ${afterRevise.pending}→${afterJudge.pending} / executing ${afterRevise.executing}→${afterJudge.executing}`)

  // ★ 界面把空描述挡在按钮上（上一条），后端也要挡：描述与这次选择是一起落库的，
  // 库里一条空描述看起来完全正常，而下一步会拿它去判需求。这一条**不改状态**（400 不留痕），
  // 所以后面那两条计数断言读到的还是「权限不足」那一条
  const blankRevise = await page.request.post(`${URL}/api/agent-tasks/${deniedId}/revise`,
    { data: { description: '   ' } })
  expect('★ 「已修改任务」提交空描述是 400（"没改"要把当前那一份原样传回来）',
    blankRevise.status() === 400, `HTTP ${blankRevise.status()}`)
  const afterBlank = await (await page.request.get(`${URL}/api/agent-tasks/${deniedId}`)).json()
  expect('★ 空描述被拒之后这条任务一动不动（还在「权限不足」、描述还是原来那一份）',
    afterBlank.task.status === 'INSUFFICIENT_PERMISSION'
    && afterBlank.task.description === judged.task.description,
    `${afterBlank.task.status} / 「${afterBlank.task.description}」`)

  // 收尾复位：与权限那一节同一条纪律（自检跑两遍，第二遍不该继承第一遍改出来的状态）
  const taskReset = await page.request.post(`${URL}/api/agent-tasks/__reset`)
  expect('自检收尾把任务假后端也复位（不留状态给下一次）', taskReset.ok())
}
else {
  // 真后端：只断言这一页渲染得出来 —— 库里没有任务时它是空状态，而不是报错
  const panelText = await page.locator('.task-panel').innerText()
  expect('真后端的任务看板可读（空库时给的是「还没有任务」而不是报错）',
    /任务看板/.test(panelText), panelText.slice(0, 80).replace(/\n/g, ' '))
}

// ---------- 应用管理 · skill管理 ----------
//
// 这一节验的是三件事，每一件都对应这一页最容易出错、而且**错了不报错**的形态：
//   ① **未登录时的边界**：列表给全部记录、写按钮禁用、三个写端点回 400。
//      不验这一条的话，"未登录"与"登录了但没有技能"在屏幕上长得一模一样；
//   ② **文件夹目录的来历**：那个下拉里的选项来自"登录人角色的目录前缀"，
//      而不是一个能随便填的路径 —— 所以既要断下拉里只有那一个，
//      也要**绕开下拉框按接口打一次**（客户端那一层拦不住 curl）；
//   ③ **归属判据是那一行的 owner_id**：别人的行上两个按钮禁用，
//      而"改成别人的记录"按接口打过去必须是 400。
//
// ⚠️ 这一节的第一段是**未登录**态（前面几节都没登录过），而 [11] 那节会自己登录一次 ——
// 所以第二段在这里直接把 localStorage 里那条记录写上再进页面（判据与 [11] 用的是同一个键），
// 而不是提前把登录 UI 走一遍：登录控件本身在 [11] 里验，两处各验一件事。
//
// ⚠️ 真后端那条路径上**一个字节都不写库**：这一页的写端点会真的建技能，
// 而 `db_ai_agent` 是用户自己的库（与 [8.6] 用户管理同一条纪律）。
console.log('[8.9] 应用管理 · skill管理')
if (EXPECT_REFLECTION) {
  // 复位：自检跑两遍，第二遍会继承第一遍建出来的技能（与 permissions / tasks 同一条经验）
  const skillReset = await page.request.post(`${URL}/api/skills/__reset`)
  expect('skill 假后端复位成功', skillReset.ok(), `HTTP ${skillReset.status()}`)

  await navMode('skill管理')
  await waitFor(async () => (await page.locator('.skill-panel .ant-table').count()) > 0, 8000)

  expect('页头标题跟着应用管理那一区走',
    /AI Agent 应用管理/.test(await page.locator('.app-topbar .app-title').innerText()))

  // ---- ① 未登录 ----
  const guestApi = await (await page.request.get(`${URL}/api/skills`)).json()
  const guestText = await page.locator('.skill-panel').innerText()
  expect('★ 未登录时列表是**全部**记录，而说明那一行把它说出来了',
    guestApi.userId === null && guestApi.total > 0 && /未登录：这是全部技能/.test(guestText),
    `total=${guestApi.total} · ${guestText.split('\n').slice(0, 3).join(' ').slice(0, 90)}`)
  expect('未登录时没有文件夹可选（它是"登录人的角色"给的，没有他就不存在这一份）',
    guestApi.folders.length === 0 && /没有可选的文件夹/.test(guestText))
  expect('★ 未登录时「新建技能」是禁用的，而且旁边写着为什么',
    await page.locator('.skill-panel .skill-create').isDisabled() && /还没有登录/.test(guestText))
  const guestRows = await page.$$eval('.skill-panel tbody tr', els => els.length)
  expect('未登录也能看见别人的记录（读是公开的，写才要登录）', guestRows === guestApi.total,
    `屏幕上 ${guestRows} 行 / 接口 ${guestApi.total} 条`)

  // 写端点按接口打一次：界面禁用按钮只是**一条**路，接口那一层才是真判据。
  // 后端两种错误体形状都读（400/500 是 `{message}`、409 是 `{error}`，见 api/skills.ts 的头注）——
  // 只读一种的话，一条"文案其实到了、只是键名不同"的断言会红，而红的原因看起来像文案丢了
  const guestWrite = await page.request.post(`${URL}/api/skills`, {
    data: { folder: 'D:\\work\\demo', name: '未登录不该建出来' },
  })
  const guestWriteRaw = await guestWrite.text()
  let guestWriteBody = {}
  try { guestWriteBody = JSON.parse(guestWriteRaw) } catch { /* 非 JSON */ }
  const guestWriteReason = String(guestWriteBody.message ?? guestWriteBody.error ?? guestWriteRaw)
  expect('★ 未登录直接打写接口是 400，且那句话说明去哪儿登录',
    guestWrite.status() === 400 && /模拟登录/.test(guestWriteReason),
    `HTTP ${guestWrite.status()} · ${guestWriteReason.slice(0, 90)}`)

  // ---- ② 登录：走**真实登录弹窗**（页面里那个「去登录」入口），不是往 localStorage 里塞一条记录 ----
  //
  // ⚠️ 这一条是实测踩出来的：登录态是**内存里的一份状态**（`useState` 初值来自挂载时读一次
  // localStorage），所以"往 localStorage 里写一条记录"**不会**让页面变成登录态 ——
  // App 挂载时读的是写之前的那一份，没有任何东西会因此重渲染。
  // 现象是：页头还是「模拟登录」、这一页还是全量视图，而"按人筛"的断言全部为假阴性。
  const admin = (await (await page.request.get(`${URL}/api/permissions/users/search?keyword=admin`)).json())
    .users[0]

  // ---- ★ 归属：别人的行上那两个按钮是禁用的 ----
  //
  // ⚠️ 这一条**必须在登录之前**跑：登录之后列表只留自己拥有的那些，别人的行根本不在屏幕上 ——
  // 那时再去等 `tr[data-skill-id=别人的]` 会一直等到超时，而报错看起来像"那一行没渲染出来"
  // （第一版就是这么红的）。未登录时列表是**全部**记录，那一行正好在，也正好该是禁用的。
  // ⚠️ 它只能放在 `admin` 之后：`const` 在初始化之前读会抛 ReferenceError（第一版也这么红过一次）
  const otherRow = guestApi.skills.find(s => s.ownerId !== admin.id)
  const otherRowLocator = page.locator(`.skill-panel tbody tr[data-skill-id="${otherRow.id}"]`)
  expect('★ 别人的记录上「编辑 / 删除」都是禁用的（而不是点下去才报错）',
    await otherRowLocator.locator('.skill-edit').isDisabled()
    && await otherRowLocator.locator('.skill-delete').isDisabled(),
    `#${otherRow.id} 拥有者 #${otherRow.ownerId}`)

  await page.locator('.skill-panel .skill-login-entry').click()
  const loginDialog = page.locator('.ant-modal:visible', { hasText: '模拟登录' })
  await loginDialog.locator('input[role="combobox"], input.ant-select-selection-search-input').first().fill('adm')
  const adminOption = page.locator('.ant-select-dropdown:visible .ant-select-item-option',
    { hasText: admin.name }).first()
  await adminOption.waitFor({ state: 'visible', timeout: 8000 })
  await adminOption.click()
  await loginDialog.locator('.ant-btn-primary').click()
  await waitFor(async () => (await page.locator('.mock-login-chip').count()) === 1, 8000)

  // 换人要重进这一页：登录态是 App 的状态，而"筛"是**服务端**算的（useSkills 的 userId 一变就重取）。
  // 切走再切回来就是一次重新挂载、重新拉取 —— 不用 reload
  // （那会把前面几节攒下的结果丢掉，[9] 正在验它们还在）
  await navMode('用户管理')
  await page.waitForTimeout(200)
  await navMode('skill管理')
  await waitFor(async () => (await page.locator('.skill-panel .skill-scope').innerText()).includes('只看'),
    8000)

  const scopeText = await page.locator('.skill-panel .skill-scope').innerText()
  expect('★ 登录之后说明它按谁筛（少掉的那几行在屏幕上没有别的痕迹）',
    new RegExp(`用户 id\\s*${admin.id}`).test(scopeText), scopeText.replace(/\n/g, ' ').slice(0, 90))

  const mineApi = await (await page.request.get(`${URL}/api/skills?userId=${admin.id}`)).json()
  const allApi = await (await page.request.get(`${URL}/api/skills`)).json()
  const screenRows = () => page.$$eval('.skill-panel tbody tr',
    els => els.map(el => Number(el.dataset.skillId)).sort((a, b) => a - b))
  expect('★ 这一页收的是"他拥有的那些"（接口那一份与屏幕上一致，而且比全量少）',
    mineApi.ownershipJudged === true && mineApi.total > 0 && mineApi.total < allApi.total
    && JSON.stringify(await screenRows())
      === JSON.stringify(mineApi.skills.map(s => s.id).sort((a, b) => a - b)),
    `屏幕上 ${JSON.stringify(await screenRows())} / 接口 ${JSON.stringify(mineApi.skills.map(s => s.id))}`)
  expect('★ 别人的那一条一条都不在（只断"该出现的出现了"的话，一个没筛的实现照样全绿）',
    mineApi.skills.every(s => s.ownerId === admin.id)
    && allApi.skills.some(s => s.ownerId !== admin.id))
  expect('列表标题上的条数是筛过之后的数（不是全库的条数）',
    (await page.locator('.skill-panel .ant-card-head-title').innerText()).includes(`技能（${mineApi.total}）`))
  expect('★ 拥有者那一列写的是姓名（后端 JOIN 出来的那一份，不是我这一页按 id 拼的）',
    mineApi.skills.every(s => s.ownerName === (admin.displayName || admin.name))
    && (await page.locator('.skill-panel').innerText()).includes(admin.displayName || admin.name))

  // 文件夹选项集 = 他全部角色的目录前缀（admin 是「管理员」，假数据里配了 D:\work\demo）
  expect('★ 文件夹选项集就是他角色的目录前缀（不是全库的目录、也不是随便填）',
    mineApi.folders.length > 0 && JSON.stringify(mineApi.folders) === JSON.stringify(['D:\\work\\demo'])
    && (await page.locator('.skill-panel .skill-folders').innerText()).includes('D:\\work\\demo'),
    JSON.stringify(mineApi.folders))

  // 归属：别人的行已经验过（未登录那一段），这里只断**自己的**行可以点
  const myRowLocator = page.locator(`.skill-panel tbody tr[data-skill-id="${mineApi.skills[0].id}"]`)
  expect('自己的记录上那两个按钮是可点的',
    !(await myRowLocator.locator('.skill-edit').isDisabled())
    && !(await myRowLocator.locator('.skill-delete').isDisabled()))

  // ---- ③ 按接口打的三条边界：越界文件夹 / 别人的记录 / 同名 ----
  //
  // ⚠️ 错误体一律**先读原文再解析**（`readReason` 那个小工具）：只写 `await r.json()` 的话，
  // 一旦响应体不是 JSON（或为空），拿到的是一个空对象，而断言的失败信息里**什么都没有** ——
  // 看起来像"后端没给理由"，其实是这一次读取没读到。实测踩过：两条断言的红字只有一个
  // `HTTP 400 · `，查了半天才发现是读取方式的问题，而不是文案丢了
  const outside = 'D:\\work\\demo-x'
  const outsideWrite = await page.request.post(`${URL}/api/skills?userId=${admin.id}`, {
    data: { folder: outside, name: '越界文件夹' },
  })
  const outsideReason = await readReason(outsideWrite)
  expect('★ 文件夹不在他的选项集里是 400，而且报错里带着可选项（没有它下一个还会填错）',
    outsideWrite.status() === 400
    && /不在当前登录人的可选范围里/.test(outsideReason)
    && outsideReason.includes('D:\\work\\demo'),
    `HTTP ${outsideWrite.status()} · ${outsideReason.slice(0, 90)}`)
  const afterOutside = await (await page.request.get(`${URL}/api/skills?userId=${admin.id}`)).json()
  expect('越界那一次没有留下任何记录（读到的条数没变）', afterOutside.total === mineApi.total)

  const stealEdit = await page.request.put(`${URL}/api/skills/${otherRow.id}?userId=${admin.id}`, {
    data: { name: '改别人的' },
  })
  const stealReason = await readReason(stealEdit)
  expect('★ 改别人的记录是 400，理由是"拥有者不是当前登录的人"',
    stealEdit.status() === 400 && /只能修改 \/ 删除自己拥有的技能/.test(stealReason),
    `HTTP ${stealEdit.status()} · ${stealReason.slice(0, 80)}`)
  const stealDelete = await page.request.delete(`${URL}/api/skills/${otherRow.id}?userId=${admin.id}`)
  expect('★ 删别人的记录也是 400，而它一动没动',
    stealDelete.status() === 400
    && (await (await page.request.get(`${URL}/api/skills`)).json()).skills.some(s => s.id === otherRow.id))

  // ---- ④ 新建：拥有者由服务端填 ----
  //
  // ⚠️ 三条实测验出来的写法（这一节翻来覆去红过好几轮，全栽在这上面）：
  //   ① **每次操作前重新取一次 locator**，不要把一个 `page.locator(...)` 存成常量跨多次交互用 ——
  //      写完一次数据之后表格会重渲染，之前取到的那个元素**已经不在 DOM 里**了，
  //      而 Playwright 的报错是"等那个元素出现"超时，看起来像功能坏了；
  //   ② 下拉展开之后要**等选项渲染出来再读**（antd 的下拉是异步挂到 body 上的，点了就立刻读会读到空）；
  //   ③ 输入类的选择器**不要写"类名 + 后代 input"** —— antd 6 把 `className` 落在
  //      `<input>` 自己身上，那种写法一个都匹配不到（详见下面那条注释）。
  await page.locator('.skill-panel .skill-create').click()
  await page.locator('.ant-modal:visible', { hasText: '新建技能' }).locator('.ant-select').click()
  const folderChoice = page.locator('.ant-select-dropdown:visible .ant-select-item-option')
  const dropdownReady = await waitFor(async () => {
    const texts = await folderChoice.allInnerTexts()
    return texts.some(t => t.trim().length > 0)
  }, 5000)
  const folderOptions = await folderChoice.allInnerTexts()
  expect('★ 下拉里只有他角色的那几个目录前缀（选不到别处）',
    dropdownReady
    && JSON.stringify(folderOptions.map(t => t.trim())) === JSON.stringify(mineApi.folders),
    `下拉=${JSON.stringify(folderOptions.map(t => t.trim()))} / 接口=${JSON.stringify(mineApi.folders)}`)
  await folderChoice.first().click()
  // ⚠️ 选择器直接指向那个**输入元素**，不写成 `.skill-name-input input`：
  // antd 6 把 `className` 落在 `<input>` 自己身上（不是包一层 div），
  // 于是"类名 + 后代 input"的写法**一个都匹配不到** —— 而失败信息是
  // "waiting for locator(…) 超时"，看起来像弹窗没渲染出来（实测为此白查过一轮：
  // 弹窗在、`.skill-name-input` 有 1 个，而 `.skill-name-input input` 是 0 个）
  await page.locator('.ant-modal:visible', { hasText: '新建技能' }).locator('.skill-name-input').fill('自检技能')
  await page.locator('.ant-modal:visible', { hasText: '新建技能' }).locator('.skill-description-input').fill('自检建的这一条')
  await page.locator('.ant-modal:visible', { hasText: '新建技能' }).locator('.skill-content-input').fill('第一版正文')
  await page.locator('.ant-modal:visible', { hasText: '新建技能' }).locator('.ant-btn-primary').click()

  // 等界面上真的建出来那一条（**按名字判**，不只等"多了 1 条"：
  // 只等计数的话，等到的可能是另一次写入留下的记录），返回那一份
  const fresh = await waitFor(async () => {
    const now = await (await page.request.get(`${URL}/api/skills?userId=${admin.id}`)).json()
    return now.skills.find(s => s.name === '自检技能') || false
  }, 8000)
  expect('★ 点「创建」真的建出来了一条（列表里读得到它）', !!fresh, fresh ? `#${fresh.id}` : '没等到')
  expect('★ 拥有者与最后修改人都是当前登录的人（请求体里根本没有这两个字段）',
    fresh && fresh.ownerId === admin.id && fresh.lastModifiedBy === admin.id,
    `owner=${fresh && fresh.ownerId} lastModifiedBy=${fresh && fresh.lastModifiedBy}`)
  expect('最后修改时间也写上了（列表按它排）',
    !!fresh && !!fresh.updatedAt && fresh.updatedAt.length > 10)
  expect('★ 新建的反馈里写着拥有者是谁（不是一句没有主语的"成功"）',
    /已建技能/.test(await page.locator('.skill-panel .ant-alert').first().innerText()))
  // ★ 屏幕上真的多了那一行（只断接口的话，"建出来了但列表没刷新"会全绿）
  const rowAppeared = await waitFor(async () =>
    (await page.locator(`.skill-panel tbody tr[data-skill-id="${fresh.id}"]`).count()) === 1, 8000)
  expect('★ 那一行真的出现在表格里（不是只在库里）', rowAppeared, `#${fresh.id}`)

  const sameName = await page.request.post(`${URL}/api/skills?userId=${admin.id}`, {
    data: { folder: fresh.folder, name: '自检技能' },
  })
  expect('★ 同一个人的同一个文件夹下重名是 409（换个名字，或者去改那一条）',
    sameName.status() === 409, `HTTP ${sameName.status()}`)

  // ---- ⑤ 改自己的那一条 ----
  await page.locator('.skill-panel .skill-create').click()   // 先确认空表单建不出来
  expect('★ 空表单时「创建」是禁用的（名字与文件夹都为空的请求后端会 400）',
    await page.locator('.ant-modal:visible .ant-btn-primary').isDisabled())
  await page.locator('.ant-modal:visible .ant-btn-default').click()

  await page.locator(`.skill-panel tbody tr[data-skill-id="${fresh.id}"] .skill-edit`).click()
  await page.locator('.ant-modal:visible', { hasText: '编辑技能' }).locator('.skill-content-input').fill('第二版正文')
  await page.locator('.ant-modal:visible', { hasText: '编辑技能' }).locator('.ant-btn-primary').click()
  const edited = await waitFor(async () => {
    const now = await (await page.request.get(`${URL}/api/skills?userId=${admin.id}`)).json()
    const hit = now.skills.find(s => s.id === fresh.id)
    return hit && hit.content === '第二版正文' ? hit : false
  }, 8000)
  expect('★ 改自己的记录成功，正文真的换了', !!edited, edited?.content)
  expect('改完之后拥有者没有变（一次编辑不该顺手改归属）', edited?.ownerId === admin.id)
  expect('最后修改人也还是当前登录的人', edited?.lastModifiedBy === admin.id)

  // ---- ⑥ 删自己的那一条：弹窗里要说清删的是哪一条 ----
  await page.locator(`.skill-panel tbody tr[data-skill-id="${fresh.id}"] .skill-delete`).click()
  const delDialog = page.locator('.ant-modal:visible', { hasText: '删除技能' })
  const delText = await delDialog.innerText()
  expect('★ 删除确认里写着是哪一条（文件夹 + 名字），以及"没有回头路"',
    delText.includes('自检技能') && delText.includes('D:\\work\\demo') && /没有回头路/.test(delText),
    delText.replace(/\n/g, ' ').slice(0, 90))
  await delDialog.locator('.ant-btn-primary').click()
  const afterDelete = await waitFor(async () => {
    const now = await (await page.request.get(`${URL}/api/skills?userId=${admin.id}`)).json()
    return now.total === mineApi.total ? now : false
  }, 8000)
  expect('★ 删掉之后那一行真的没了（列表回到建之前那一份）',
    !!afterDelete && !afterDelete.skills.some(s => s.id === fresh.id))

  // ---- ⑦ 收尾：退出登录 + 复位，别把状态留给 [11] 与下一次 ----
  // 「退出」在页头上（与 [11] 用的是同一枚按钮）：点它同时验了"这一页会跟着回到全量"
  await page.locator('.mock-login-exit').click()
  await waitFor(async () => (await page.locator('.mock-login-entry').count()) === 1, 8000)
  expect('自检收尾把 skill 假后端复位（不留状态给下一次）',
    (await page.request.post(`${URL}/api/skills/__reset`)).ok())
  await navMode('用户管理')
  await page.waitForTimeout(200)
  await navMode('skill管理')
  const outScope = await waitFor(async () =>
    /未登录：这是全部技能/.test(await page.locator('.skill-panel .skill-scope').innerText()), 8000)
  expect('★ 退出登录之后这一页回到"全部技能"，写按钮也回到禁用（筛是服务端算的）',
    outScope && await page.locator('.skill-panel .skill-create').isDisabled())

  await page.screenshot({ path: `${SHOT_DIR}/skill-panel.png` })
}
else {
  // 真后端：只断言这一页渲染得出来 —— 库里没有技能时它是空状态，而不是报错
  // （这一族端点的写路径会真的往 `db_ai_agent` 里写记录，所以一条都不跑，理由同 [8.6]）
  await navMode('skill管理')
  await waitFor(async () => (await page.locator('.skill-panel').count()) > 0, 8000)
  const panelText = await page.locator('.skill-panel').innerText()
  expect('真后端的技能页可读（空库时给的是「还没有技能」而不是报错）',
    /技能（/.test(panelText), panelText.slice(0, 80).replace(/\n/g, ' '))
}

// ---------- 切回流式：历史不能被清掉 ----------
console.log('[9] 模式往返')
await navMode('SSE 流式')
await page.waitForTimeout(300)
const streamStillThere = (await page.locator('.message-list').innerText()).trim().length > 20
expect('切回流式后原有对话仍在', streamStillThere)
await navMode('结构化输出')
await page.waitForTimeout(300)
// 至少 1 张卡就说明记录没丢（假后端那条路径会留下 2 张，真后端只有 1 张 —— 别写死数量）
expect('切回结构化后结果仍在', (await page.locator('.structured-card').count()) >= 1)

// 切回 RAG：对照结果必须还在（它是这张页面上最贵的一次计算，丢了就要重跑）
await navMode('RAG 分块评测')
await page.waitForTimeout(400)
const ragStillThere = (await page.locator('.rag-metric-table').count()) > 0
expect('切回 RAG 后对照结果仍在（没有重跑）', ragStillThere)

// 切回 Agent：任务结果必须还在（它会**真的调模型花钱**，丢了就要再花一次钱重跑）。
//
// ⚠️ 这里断言的是**在 HITL 那一节里、轨迹稳定之后记下来的状态**（`timelineWasThere`），
// 而不是"现在有没有时间线" —— 因为 HITL 那一节会再跑两次任务，
// 那两次的轨迹（批准后 / 拒绝后）与原来那次不同。理由见那个变量的注释。
//
// ⚠️⚠️ 切回来之后**必须轮询等待面板重新挂起来再数**：
// 面板是按模式条件渲染的，切换之后它要重新渲染一次，而**那个渲染不是同步的**。
// 第一版写的是 `waitForTimeout(400)` 然后立刻数 `.agent-timeline` ——
// 真后端上这条因此红过一次，而**功能完全正常**（切回去之后时间线确实还在）。
// 这正是"界面自检里的固定 sleep"最典型的失效形态：
// **等待时长与它要等的东西没有关系**，而失败信息看起来像"结果丢了"。
// 改成"轮询它出现"之后，两条路径都稳定 ——
// 这与本轮 HITL 断言里用的 `waitFor` 是同一套做法（理由见那个函数的注释）。
await navMode('Agent 工具调用')
const timelineNow =
  timelineWasThere && (await waitFor(async () => (await page.locator('.agent-timeline').count()) > 0, 3000))
expect(
  '切回 Agent 后任务结果仍在（没有重跑、没有再花钱）',
  timelineNow === timelineWasThere,
  timelineWasThere ? '（原来有时间线，切回来也应有）' : '（原来就没有时间线 —— 本次运行 0 步，属正常）',
)

// ---------- 主题（浅色 / 深色） ----------
//
// 这一节的判据落在**两件能算出来的事**上，而不是"看上去像不像深色"：
//   1. `<html>` 上的 `data-theme` 与按钮自己的 `aria-pressed` —— 状态是否真的切了；
//   2. 几个关键容器的**实际计算底色**（几何 / 颜色断言，不用肉眼）。
//
// ⚠️ 为什么必须显式设一次偏好再刷新，而不是"打开页面就开始断言"：
// 首帧主题取自「存过的偏好 → 系统偏好 → 浅色」，而这台机器上系统多半是浅色、
// 存过的偏好又可能被上一轮自检留下 —— 不钉住起点的话，这一节在别人机器上会从深色开始，
// 后续断言全反。这与"假后端状态机必须先 __reset"是同一条纪律。
//
// ⚠️ 为什么断言里没有"切主题要发请求"：它压根不发。这一节因此可以放在最后 ——
// 它不碰任何后端状态，也就不需要像前面几节那样收尾复位。
console.log('[10] 主题（浅色 / 深色）')
{
  const htmlTheme = () => page.evaluate(() => document.documentElement.dataset.theme)
  const bg = sel => page.evaluate(
    s => {
      const el = document.querySelector(s)
      return el ? getComputedStyle(el).backgroundColor : ''
    },
    sel,
  )
  /** 'rgb(20, 20, 20)' → [20,20,20]；拿来判"这一块是深色还是浅色" */
  const rgb = value => (value.match(/\d+/g) ?? []).slice(0, 3).map(Number)
  const isDark = value => {
    const [r, g, b] = rgb(value)
    return r + g + b < 240
  }

  // 从浅色起步（见上面那条注释）
  await page.evaluate(() => window.localStorage.setItem('ai-agent.theme', 'light'))
  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.app-topbar')

  expect('页头右上角有主题开关', (await page.locator('.theme-toggle').count()) === 1)
  expect('默认（没切过时）是浅色', (await htmlTheme()) === 'light', await htmlTheme())
  const lightBody = await bg('body')
  expect('浅色的页面底色是浅的', !isDark(lightBody), lightBody)

  await page.locator('.theme-toggle').click()
  // 只等属性，不等固定时长：状态一进 DOM 就能读到（等待时长与它要等的东西无关的那种断言，
  // 正是本项目反复踩过的那一类）
  const darkOn = await waitFor(async () => (await htmlTheme()) === 'dark', 3000)
  expect('点一下切到深色（<html> 的 data-theme）', darkOn, await htmlTheme())
  expect('按钮的 aria-pressed 跟着变（不靠颜色单独表达）',
    (await page.locator('.theme-toggle').getAttribute('aria-pressed')) === 'true')

  const shellParts = {
    body: await bg('body'),
    sidebar: await bg('.app-sidebar'),
    topbar: await bg('.app-topbar'),
    footer: await bg('.app-footer'),
  }
  expect(
    '深色下外壳四块（页面 / 目录 / 页头 / 输入区）都不是浅底',
    Object.values(shellParts).every(v => v !== '' && isDark(v)),
    Object.entries(shellParts).map(([k, v]) => `${k}=${v}`).join(' '),
  )
  // 卡片单独到**有卡片的视图**上量：对话模式里没有 `.diag-card`，
  // 在这里写 `bg('.diag-card, .ant-card')` 会拿到空串 —— 而空串恰好会让"非浅底"成立，
  // 于是一条永远为真的断言。第一版就是那么写的，实测报了 `card=`（空）。
  //
  // 判据也**不只是"不浅"**：卡片与页面的亮度要拉开一档。只判"不浅"的话，
  // 卡片与容器同色（深色下曾经都是 #141414）照样绿 —— 而那时整页是糊成一片的。
  await navMode('成本看板')
  const cardBg = await bg('.diag-card')
  const pageBg = await bg('body')
  const gap = Math.abs(rgb(cardBg).reduce((a, b) => a + b, 0) - rgb(pageBg).reduce((a, b) => a + b, 0))
  expect(
    '深色下卡片比页面亮出一档（否则卡片与底色糊在一起，而功能全对）',
    cardBg !== '' && isDark(cardBg) && gap > 30,
    `卡片 ${cardBg} / 页面 ${pageBg}（亮度差 ${gap}）`,
  )
  await navMode('SSE 流式')

  // 文字不能是"深色底 + 深色字"。只判"亮不亮"是不够的：
  // `rgba(0, 0, 0, 0.88)` 的 RGB 是黑的，可它的**alpha 让它照样压在深底上看不见** ——
  // 所以按 alpha 合成一次再判（实测：漏了这一步时，浅色主题那套文字色也能过这条断言）
  const textColor = await page.evaluate(() => {
    const parse = value => {
      const m = /rgba?\((\d+), (\d+), (\d+)(?:, ([\d.]+))?\)/.exec(value)
      return { r: +m[1], g: +m[2], b: +m[3], a: m[4] === undefined ? 1 : +m[4] }
    }
    const c = parse(getComputedStyle(document.body).color)
    const onDark = { r: 0, g: 0, b: 0 } // 深色页面底就是纯黑，合成用它可以，也最保守
    return {
      raw: getComputedStyle(document.body).color,
      effective: c.r * c.a + onDark.r * (1 - c.a) + c.g * c.a + onDark.g * (1 - c.a)
        + c.b * c.a + onDark.b * (1 - c.a),
    }
  })
  expect('深色下正文颜色是浅的（不是深底深字）', textColor.effective > 300, `${textColor.raw} → ${textColor.effective}`)

  // 代码块：这是唯一一处**颜色不来自 styles.css** 的地方（highlight.js 的两张表在 index.html 里），
  // 所以它必须单独钉 —— 只换 data-theme 而忘了换那张表，症状是深色页面上挂着一块白底代码
  const hljs = await page.evaluate(() => ({
    light: document.getElementById('hljs-light')?.disabled,
    dark: document.getElementById('hljs-dark')?.disabled,
  }))
  expect('深色下代码高亮用的是深色那一套表', hljs.light === true && hljs.dark === false,
    `light.disabled=${hljs.light} dark.disabled=${hljs.dark}`)

  await page.screenshot({ path: `${SHOT_DIR}/theme-dark.png` })

  // 刷新之后要记得住（它是"这台机器上我怎么看这个页面"的偏好，见 useThemeMode）
  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.app-topbar')
  expect('刷新之后仍是深色（偏好存在 localStorage）', (await htmlTheme()) === 'dark', await htmlTheme())

  // 切回去：颜色要能回到浅色，而且这一轮自检给下一次留下的偏好是浅色
  await page.locator('.theme-toggle').click()
  const backToLight = await waitFor(async () => (await htmlTheme()) === 'light', 3000)
  const backBody = await bg('body')
  expect('再点一下切回浅色，底色也跟着回来', backToLight && !isDark(backBody), `${await htmlTheme()} ${backBody}`)
  expect('没有横向溢出（深色下也一样）',
    (await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)) === 0)
}

// ---------- 模拟登录（页头右上角） ----------
//
// 这一节验四件事，每一件都对应一个"看起来正常、其实不对"的形态：
//   ① 选人走的是**已有的用户搜索端点**（候选来自 `GET /api/permissions/users/search`），
//      而不是一格格能打字的输入框 —— 打错一个字不会有任何东西拦下来；
//   ② 页头显示的是**显示名**，而记下来的是**用户 id**（后续业务认 id）——
//      所以 id 读的是 `data-login-user-id` 属性，不去解析那行显示文字；
//   ③ 记录落在 localStorage：刷新之后仍然是登录态（刷新就掉会让人每次改动重选一遍）；
//   ④ 退出**真的把那条记录删掉**：刷新之后仍是未登录 ——
//      只清内存的实现同样"看起来退出了"，而下次打开这一页身份又回来了。
//
// ⚠️ 这一节要 `page.reload()`，所以它必须排在其他节之后（reload 会把 App 整个重挂，
// 前面几节攒下的对话、RAG、Agent 结果全丢 —— 这与 [8.6] 那条"复位之后不要 reload"同源）。
// 它**不写后端**（搜索是只读端点），所以真后端路径上也能跑。
console.log('[11] 模拟登录（页头右上角）')
{
  const entry = page.locator('.app-topbar .mock-login-entry')
  const chip = page.locator('.app-topbar .mock-login-chip')
  const exit = page.locator('.app-topbar .mock-login-exit')

  expect('页头右上角有「模拟登录」入口（未登录时是一个按钮）',
    (await entry.count()) === 1 && (await chip.count()) === 0,
    `entry=${await entry.count()} chip=${await chip.count()}`)

  // 它不跟着视图走：诊断视图上没有 conversationId / 新会话那一族，但它仍在
  await navMode('成本看板')
  expect('换到没有会话状态的视图，模拟登录仍在（它说的是"以谁的身份"，不是某一页的数据）',
    (await entry.count()) === 1 && (await page.locator('.app-topbar .conversation-id').count()) === 0)
  await navMode('SSE 流式')

  await entry.click()
  const dialog = page.locator('.ant-modal:visible', { hasText: '模拟登录' })
  await dialog.waitFor({ state: 'visible', timeout: 8000 })
  expect('★ 点入口弹出选人弹窗', await dialog.isVisible())

  const picker = dialog.locator('.ant-select')
  expect('弹窗里是一个搜索选择器（不是能随便打字的输入框）', (await picker.count()) === 1)
  // 没选人时不让登录：这个记录是后续业务的身份，"空身份"比"没有身份"更糟
  expect('没选人时「登录」是按不动的',
    await dialog.locator('.ant-btn-primary').isDisabled())

  // 输入搜索词 → 等**那个端点**的响应（这一条同时证明"候选不是前端过滤出来的"）
  const [searchResponse] = await Promise.all([
    page.waitForResponse(r => r.url().includes('/api/permissions/users/search')
      && r.status() === 200, { timeout: 10000 }),
    (async () => { await picker.click(); await picker.locator('input').fill('adm') })(),
  ])
  const searched = await searchResponse.json()
  const hit = searched.users.find(u => u.name === 'admin') ?? searched.users[0]
  // 一条候选都没有时后面的断言无从谈起（选人 → 登录 → 刷新这条链每一步都要有那个人），
  // 所以在这里就把原因说清楚，而不是让它表现成"等不到某个元素"
  if (!hit) throw new Error('搜索端点没有返回任何候选（keyword=adm）—— 这一节的前提不成立')
  expect('★ 候选来自已有的用户搜索端点（搜索词进的是后端）',
    searched.users.length > 0 && /keyword=adm/.test(searchResponse.url()),
    `${searched.count} 条 · ${searchResponse.url().split('/api')[1]}`)

  const option = page.locator('.ant-select-dropdown:visible .ant-select-item-option',
    { hasText: hit.name }).first()
  await option.waitFor({ state: 'visible', timeout: 8000 })
  const optionText = (await option.innerText()).trim()
  expect('候选上写的是「显示名（登录名）」（选的是账号表里的那个人）',
    optionText.includes(hit.name) && (hit.displayName === '' || optionText.includes(hit.displayName)),
    optionText)
  await option.click()

  expect('选中之后「登录」可以点了（选中项回显里带着将记下的 id）',
    !(await dialog.locator('.ant-btn-primary').isDisabled())
    && new RegExp(`用户 id\\s*${hit.id}`).test(await dialog.innerText()),
    (await dialog.innerText()).replace(/\n/g, ' ').slice(0, 100))

  await dialog.locator('.ant-btn-primary').click()
  const loggedIn = await waitFor(async () => (await chip.count()) === 1, 8000)
  // 关掉之后页面上不该还剩浮层：候选下拉是挂在 `<body>` 上的 portal，
  // 弹窗只隐藏（不卸载）时它会留在页面上 —— "登录成功了"与"那格下拉还浮在那儿"是两件事
  const modalClosed = await waitFor(async () =>
    (await page.locator('.ant-modal:visible').count()) === 0
    && (await page.locator('.ant-select-dropdown:visible').count()) === 0, 5000)
  expect('★ 登录之后弹窗与候选下拉都收干净了（不是只把弹窗藏起来）', modalClosed,
    `modal=${await page.locator('.ant-modal:visible').count()}`
    + ` dropdown=${await page.locator('.ant-select-dropdown:visible').count()}`)
  const label = hit.displayName || hit.name
  expect('★ 登录后页头显示的是那个人的显示名', loggedIn && (await chip.innerText()).includes(label),
    (await chip.innerText()).replace(/\n/g, ' '))
  // ⚠️ 判据落在 id 上，不落在文字上：名字可以重名、也可以改，而后续业务要的是 id
  expect('★ 记下来的是搜索结果里那个 id（后续业务认它）',
    Number(await chip.getAttribute('data-login-user-id')) === hit.id,
    `data-login-user-id=${await chip.getAttribute('data-login-user-id')} 期望=${hit.id}`)
  expect('记录里还有登录名（可选不再是裸的显示名）',
    (await chip.getAttribute('data-login-user')) === hit.name,
    `data-login-user=${await chip.getAttribute('data-login-user')}`)
  expect('入口按钮换成了「退出」',
    (await entry.count()) === 0 && (await exit.count()) === 1)

  const stored = await page.evaluate(() => window.localStorage.getItem('ai-agent.mock-login'))
  expect('★ 记录落在 localStorage（id / 登录名 / 显示名三样）',
    (() => {
      try {
        const parsed = JSON.parse(stored)
        return parsed.id === hit.id && parsed.name === hit.name && parsed.displayName === hit.displayName
      } catch { return false }
    })(),
    String(stored))

  await page.screenshot({ path: `${SHOT_DIR}/mock-login.png` })

  // 刷新之后仍然是登录态：这是"记录"与"临时状态"的差别
  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.app-topbar')
  expect('刷新之后仍然是登录态，且 id 没变（读的是存下来的那份记录）',
    (await chip.count()) === 1 && Number(await chip.getAttribute('data-login-user-id')) === hit.id,
    (await chip.innerText()).replace(/\n/g, ' '))

  // ---- ★ 登录态下"这一次运行注入哪些技能"必须按身份算（本次新增） ----
  //
  // 【为什么这一小段放在这里，而不是 Agent 面板那一节】
  // 它需要一个**真的登录过**的状态，而登录态只有这一节有（理由见上面那段 reload 的说明：
  // 往 localStorage 里写一条记录不会让页面变成登录态）。而 Agent 面板那一节跑在这一节之前，
  // 那时页头还是未登录 —— 所以那一条断言的正确期望值是"0 条 + 去登录"。
  //
  // 【它钉住的是什么】"技能的候选范围来自执行者的角色目录"这条判据的**端到端**表现：
  //   ① 请求体里真的带着这个人的 id（字段名写错 = 后端认为没指定执行者）；
  //   ② 回来的条数等于"按他的角色目录前缀筛全库技能"这个**独立算出来**的数。
  //
  // ⚠️ 期望值刻意**不**取 `?userId=<他>` 那一份：那个接口筛的是**拥有者**
  // （"这一行是不是我的"），而运行期筛的是**文件夹**（"这个目录下的技能"）——
  // 两份本来就会不一样（同一批人共用一个目录时，同事写的技能也算这个目录下的）。
  // 拿拥有者那一份当期望值，等于把两件不同的事当成同一件。
  if (EXPECT_REFLECTION) {
    await navMode('Agent 工具调用')
    await waitFor(async () => (await agentCard.count()) > 0, 8000)
    const executorTag = (await agentCard.locator('[data-testid="agent-executor-value"]').innerText()).trim()
    expect('★ 登录之后 Agent 面板显示的执行者是页头那个人（身份只有一个来源）',
      executorTag.includes(label) && executorTag.includes(`#${hit.id}`), executorTag)

    const askedAgain = page.waitForRequest(
      r => r.url().includes('/api/agent/ask') && r.method() === 'POST', { timeout: 20000 })
    await agentCard.locator('button', { hasText: /跑\s*一\s*次/ }).first().click()
    const body = JSON.parse((await askedAgain).postData() ?? '{}')
    expect('★ 登录之后出站请求体里带的是页头那个人的 id（不是空、也不是别人）',
      body.executorId === hit.id, `executorId=${JSON.stringify(body.executorId)} 期望=${hit.id}`)

    await agentCard.locator('.stat-cell', { hasText: '停因' }).first().waitFor({ state: 'visible', timeout: 150000 })
    const skillsBlock = agentCard.locator('[data-testid="agent-skills"]')
    const shownCount = Number(((await skillsBlock.locator('[data-testid="agent-skill-count"]').innerText())
      .match(/\d+/) ?? ['-1'])[0])

    // 这个人的角色目录前缀（与 skill 管理那一页的「文件夹目录」同一个来源）
    const foldersOfUser = (await (await page.request.get(`${URL}/api/skills?userId=${hit.id}`)).json()).folders
    // 全库技能（**不按拥有者筛**）里落在他那些目录前缀下的那些 —— 运行期看的正是这一批
    const allSkills = (await (await page.request.get(`${URL}/api/skills`)).json()).skills
    const under = (prefix, dir) => {
      const seg = value => value.replace(/[\\/]+$/, '').toLowerCase().split(/[\\/]+/)
      const base = seg(prefix)
      const target = seg(dir)
      return target.length >= base.length && base.every((s, i) => s === target[i])
    }
    const expected = allSkills.filter(s => foldersOfUser.some(p => under(p, s.folder)))
    expect('★ 注入的技能条数 = 按他的角色目录前缀从全库筛出来的那些（两边同一个判据）',
      shownCount === expected.length,
      `Agent 页 ${shownCount} 条 / 按目录前缀算 ${expected.length} 条（${JSON.stringify(expected.map(s => s.name))}）`
      + ` / 他自己的 ${JSON.stringify(allSkills.filter(s => s.ownerId === hit.id).map(s => s.name))}`)

    const skillsText = (await skillsBlock.innerText()).trim()
    expect('★ 注入的技能在页面上是一条条列出来的（不是只有一个数字）',
      expected.length === 0
        ? /没有技能/.test(skillsText)
        : (await skillsBlock.locator('.agent-skill-row').count()) === expected.length
          && expected.every(s => skillsText.includes(s.name)),
      `—— 文本片段：${skillsText.replace(/\n/g, ' | ').slice(0, 240)}`)
    await page.screenshot({ path: `${SHOT_DIR}/agent-skills.png`, fullPage: true })
  }

  // ---- ★ 登录态下 agent任务那一页的看板按人筛（`?userId=`） ----
  //
  // 这一小段放在这里而不是 [8.8]，理由有两条，都不是排版问题：
  //   ① 它要一次 `page.reload()` 才拿得到"登录之后"的那一份看板（登录态只是 localStorage
  //      里的一条记录，App 挂载时读一次），而这一节本来就 reload 过 —— 再插一次是免费的；
  //   ② [8.8] 里那些"点一下卡片真的换列了、列里的条数 ±1"必须在**未登录（全量）**下跑，
  //      按人筛过之后同一列可能只剩一张卡片，计数断言就测不到它要说的事。
  //
  // ★ 判据是"按状态挑列"，不是某一列上的等值比较（`AgentTaskStatus#party()`）：
  //   新发布 / 已完成 / 已取消 —— 发布人**或**任务人；待确认需求 / 待验收 —— 只有发布人；
  //   其余状态 —— 只有任务人。所以这一小段两边都得断：
  //   **该出现的出现了**（只断这个，一个"没筛、返回全量"的实现照样全绿），
  //   **不该出现的一条都没有**（只断这个，"筛成空"也全绿）。期望的 id 集合是照上面对出来的。
  await navMode('agent任务')
  await waitFor(async () => /只看与|未登录/.test(await page.locator('.task-board-scope').innerText()), 8000)
  const scopeText = await page.locator('.task-board-scope').innerText()
  expect('★ 登录之后看板说明它按谁筛（少掉的那几条在屏幕上没有别的痕迹）',
    scopeText.includes(label) && new RegExp(`用户 id\\s*${hit.id}`).test(scopeText),
    scopeText.replace(/\n/g, ' ').slice(0, 90))
  /** 屏幕上那几张卡片的 id（`data-task-id` 是卡片上的稳定标识）。 */
  const shownTaskIds = () => page.$$eval('.task-card', els => els.map(el => Number(el.dataset.taskId)).sort((a, b) => a - b))
  const filteredBoard = await (await page.request.get(`${URL}/api/agent-tasks?userId=${hit.id}`)).json()
  const allBoard = await (await page.request.get(`${URL}/api/agent-tasks`)).json()
  const cardsOf = board => board.columns.flatMap(c => c.tasks)
  const idsOf = board => cardsOf(board).map(task => task.id).sort((a, b) => a - b)
  expect('★ 这一页收的是"与他有关、且该由他看见"的那些（接口那一份与屏幕上一致）',
    filteredBoard.userId === hit.id
    && allBoard.userId === null && allBoard.total > filteredBoard.total
    && cardsOf(filteredBoard).length > 0
    && JSON.stringify(await shownTaskIds()) === JSON.stringify(idsOf(filteredBoard)),
    `屏幕上 ${JSON.stringify(await shownTaskIds())} / 接口 ${JSON.stringify(idsOf(filteredBoard))}`)
  // 页头卡片上那个数字也要跟着筛：它是"我现在有几件事"里最直接的那个
  const shownTotal = await page.locator('.task-panel .ant-card-head-title').innerText()
  expect('★ 看板标题上的条数是筛过之后的数（不是全库的条数）',
    shownTotal.includes(`任务看板（${filteredBoard.total} 条）`), shownTotal.replace(/\n/g, ' '))

  // ---- ★ 同一份数据换个人看，看到的是另一批（这条才真正钉住 OR 规则） ----
  //
  // 脚本替"另一个人"取一次接口，断的是**他自己在界面上也会看到的那些**：admin 是发布人的
  // 「待确认计划」（球在 zhou 手上）只有 zhou 看得见、admin 看**不**见；
  // 而 admin 是发布人的「待验收」反过来只有 admin 看得见（那两格只有他能做）。
  // 少了这一条，一个把筛选写成"发布人或任务人，不分状态"的实现会照样全绿。
  const othersBoard = await (await page.request.get(`${URL}/api/agent-tasks?userId=2`)).json()
  const agentHeldCard = 5 // 已确认需求：球在 agent 手上，而它按状态归**任务人**
  expect('★ 收件人判据不是"发布人或任务人"一刀切：「待确认计划」只归任务人',
    idsOf(othersBoard).includes(3) && !idsOf(filteredBoard).includes(3),
    `zhou 的 ${JSON.stringify(idsOf(othersBoard))}`)
  expect('★ 「待验收」只归发布人（那两格只有他能做）',
    idsOf(filteredBoard).includes(9) && !idsOf(othersBoard).includes(9),
    `admin 的 ${JSON.stringify(idsOf(filteredBoard))} / zhou 的 ${JSON.stringify(idsOf(othersBoard))}`)
  // ⚠️ 球在 agent 手上那三格**照样归任务人**（"其余状态 → assignee_id"）：那一列是空的，
  // 但这与"该由谁看"是两件事 —— 它在等 agent 把它拆完，而它是任务人的活。
  // 这一条把两件事分开：admin 是发布人却看不到它，zhou 是任务人所以看得到
  expect('★ 球在 agent 手上的卡片归**任务人**（那一列为空 ≠ 与谁都不相关）',
    idsOf(othersBoard).includes(agentHeldCard) && !idsOf(filteredBoard).includes(agentHeldCard),
    `#${agentHeldCard}：admin ${idsOf(filteredBoard).includes(agentHeldCard)} / `
    + `zhou ${idsOf(othersBoard).includes(agentHeldCard)}`)
  expect('★ 已结束的两条（已完成 / 已取消）发布人与任务人都看得见',
    idsOf(filteredBoard).includes(10) && idsOf(filteredBoard).includes(11)
    && idsOf(othersBoard).includes(10),
    `admin 的 ${JSON.stringify(idsOf(filteredBoard))} / zhou 的 ${JSON.stringify(idsOf(othersBoard))}`)
  // 回到对话台：下一段要按页头那个「退出」按钮，而在这一页上它会顺手把看板重拉一遍
  // （那是一件好事，但会让"退出之后刷新仍是未登录"那两条多绕一圈）
  await navMode('SSE 流式')

  await exit.click()
  const loggedOut = await waitFor(async () => (await entry.count()) === 1, 8000)
  expect('★ 点「退出」回到未登录（入口按钮回来、标签消失）',
    loggedOut && (await chip.count()) === 0)
  const cleared = await page.evaluate(() => window.localStorage.getItem('ai-agent.mock-login'))
  expect('★ 退出把那条记录删掉了（不是只清了内存）', cleared === null, `localStorage=${cleared}`)

  // 退出之后回那一页看：说明那一行换成了"这是全部任务"，看板也回到全量 ——
  // 它同时钉住"换人要重拉"这条（筛选条件是服务端的，本地那一份是按旧条件取回来的）
  await navMode('agent任务')
  const outScope = await waitFor(async () =>
    /未登录：这是全部任务/.test(await page.locator('.task-board-scope').innerText()), 8000)
  expect('★ 退出之后看板回到全量，说明那一行也换回「未登录：这是全部任务」',
    outScope && /模拟登录/.test(await page.locator('.task-board-scope').innerText()),
    (await page.locator('.task-board-scope').innerText()).replace(/\n/g, ' ').slice(0, 90))
  const afterLogoutBoard = await (await page.request.get(`${URL}/api/agent-tasks`)).json()
  const shownAfter = await page.locator('.task-panel .ant-card-head-title').innerText()
  expect('★ 那一份的条数等于全量（登录时效的那一条回来了）',
    afterLogoutBoard.userId === null
    && shownAfter.includes(`任务看板（${afterLogoutBoard.total} 条）`),
    `${shownAfter.replace(/\n/g, ' ')} · 全量 ${afterLogoutBoard.total} 条`)

  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.app-topbar')
  expect('刷新之后仍然是未登录（删的是记录，不是只改了这一次的显示）',
    (await entry.count()) === 1 && (await chip.count()) === 0)
}

// ---------- 控制台必须干净 ----------
console.log('[9] 控制台')
expect('无 console 错误/警告', consoleErrors.length === 0, consoleErrors.join(' | ') || '干净')

await browser.close()

console.log('')
if (problems.length === 0) {
  console.log(`✅ 全部通过（${executed} 项断言）。截图：${SHOT_DIR}/`)
  process.exit(0)
}
console.log(`❌ ${executed} 项断言里 ${problems.length} 项未通过：${problems.join('、')}`)
process.exit(1)
