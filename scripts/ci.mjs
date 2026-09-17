#!/usr/bin/env node
/**
 * <b>回归门禁的唯一入口</b>（阶段四第 4 件：评测接入 CI）。
 *
 * <h2>为什么需要"一个入口"，而不是四份 CI 配置</h2>
 * 这个项目已经有四道各自独立的验证：后端测试、离线评测回归基线、
 * 界面自检（假后端 / 真后端各一条路径）。在它之前，跑全套要人工记住四条命令、
 * 四个工作目录、两个必须先起起来的服务、以及一组环境变量。
 *
 * <p>那种状态下的真实后果不是"麻烦"，而是**门禁会被跳过**：
 * 一个人改了分块参数，他会记得跑后端测试（因为 IDE 里顺手就跑了），
 * 而"评测基线有没有动"需要一条他记不住的命令 —— 于是那道门禁事实上不存在。
 * 所以这一步的产出是一件很小但很关键的东西：<b>把四道门禁变成一条命令</b>。
 *
 * <h2>为什么用 Node 而不是 PowerShell</h2>
 * 这个仓库里已有 `scripts/verify-mcp.ps1`，而它只能在 Windows 上跑。
 * CI 机器几乎都是 Linux —— 一份在 CI 上跑不起来的门禁脚本等于零。
 * Node 在本仓库已经是既有依赖（`web/` 与 `scripts/` 都在用），
 * 所以选它意味着**不引入任何新工具链**，同时三个平台都能跑。
 *
 * <h2>三条设计纪律（都是这一轮踩出来的）</h2>
 * <ol>
 *   <li><b>每道门禁的输出都落成文件</b>（`ci-logs/`）。理由很具体：
 *       这个项目的报告与失败原因**全是中文**，而 Maven / Playwright 的输出编码
 *       跟随平台 —— 在 Windows 上是 GBK，写进 CI 日志就是乱码。
 *       落文件之后 CI 与本地看到的是同一串字节（见 `RagRegressionBaseline.REPORT_FILE` 的注释）；</li>
 *   <li><b>失败要 fail-fast，但要先把已完成的结果打出来</b>。
 *       继续跑后面的门禁只会掩盖第一个失败（那是根因所在），
 *       而"什么都不说直接退出"会让人不知道跑到哪一步了；</li>
 *   <li><b>起服务的那道门禁必须自己管生命周期</b>，并且在**任何**退出路径上清理
 *       （包括正常结束、失败、Ctrl-C）。漏了清理的后果不是"留个进程"，
 *       而是下一次运行因为端口被占而**静默地测到了上一次的服务** ——
 *       `strictPort: true` 会让 Vite 起不来，而 `UI_URL` 仍然打得到旧的那个，
 *       于是你以为在验新代码，其实验的是旧进程（第九轮踩过，见 web/README.md）。</li>
 * </ol>
 *
 * <h2>用法</h2>
 * <pre>
 *   node scripts/ci.mjs                            # 默认：后端测试（含离线评测回归基线）
 *   node scripts/ci.mjs --with-ui                  # 再加界面自检（假后端，跑两遍验确定性）
 *   node scripts/ci.mjs --with-ui --with-real-ui   # 再加真后端路径（需要后端已在跑）
 * </pre>
 *
 * <p>Maven 一律用 `-o`（离线）：依赖都在本地仓库，而联网解析会引入一个与代码无关的失败源。
 * 真需要拉新依赖时手工跑一次 `mvn`，那是一次性的环境准备，不该混进"验证代码"这条路。
 *
 * <h2>为什么界面那一道**默认不跑**</h2>
 * 它需要 Playwright 与一个 Chrome（`channel: 'chrome'`），在干净 CI 容器里通常都没有。
 * 一道"默认会红、而且红的原因与代码无关"的门禁，几次之后就会被加进忽略列表 ——
 * 那时它保护的东西就悄悄没了。所以它是显式的 `--with-ui`，
 * 而"没跑"这件事会**被打印出来**（见末尾的"未执行的门禁"一节）。
 */
import { spawn, spawnSync } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const LOG_DIR = path.join(ROOT, 'ci-logs')

// ---------------------------------------------------------------------------
//  参数
// ---------------------------------------------------------------------------

const argv = new Set(process.argv.slice(2))
const WITH_UI = argv.has('--with-ui')
const WITH_REAL_UI = argv.has('--with-real-ui')

/** 假后端与 vite 的端口。与 `web/README.md` 里那两条命令保持一致。 */
const MOCK_PORT = Number(process.env.CI_MOCK_PORT ?? 8081)
const UI_PORT = Number(process.env.CI_UI_PORT ?? 5173)

// ---------------------------------------------------------------------------
//  小工具
// ---------------------------------------------------------------------------

const results = []

/**
 * {@code run} 子进程的收尾登记：任何退出路径上都要把它们清掉（见文件头纪律 ③）。
 */
const children = new Set()

/**
 * <b>前置条件不满足</b>（指的不是代码问题）。
 *
 * <h2>为什么这个类型必须与"编排出错"分开</h2>
 * 实测踩到：`--with-real-ui` 而 8080 上没有后端时，第一版把它当成普通异常抛出去，
 * 于是输出是
 * <pre>
 *   [ci] ❌ 编排本身出错： Error: 等待 真后端 超时（15000ms）：http://127.0.0.1:8080/…
 *       at waitForHttp (…/ci.mjs:182:9)
 * </pre>
 * 那堆栈会把人指向**脚本自己**，而真正要做的事是"把后端起起来"。
 * 更要紧的是"编排本身出错"这句话的语义：它暗示**门禁的结论不可信** ——
 * 而这里门禁压根没跑，结论是"没验"。把这两种混成一句话，
 * 下次真出现编排 bug 时人已经学会忽略这条信息了。
 */
class PreconditionError extends Error {}

/**
 * 收尾：把本脚本起过的服务全部杀掉。
 *
 * <h2>★ 为什么"按 pid taskkill /T"不够（实测踩到）</h2>
 * 第一版写的是 `taskkill /pid <child.pid> /T /F`，看起来是"连子孙一起杀"。
 * 实测跑完之后**三个 node 进程还活着**（8081 / 5173 / 5174 仍被占用），
 * 原因是进程树的结构比想象的多一层：
 * <pre>
 *   spawn('pnpm mock', {shell:true})  →  cmd.exe（我们拿到的 pid）
 *                                          └→ node pnpm.cjs
 *                                               └→ node mock/sse-server.mjs   ← 真正监听端口的是它
 * </pre>
 * `child.pid` 是**中间那个 shell**。它起完子进程之后可能很快就退出了，
 * 于是"杀一个已经不存在/已经换代的 pid"对最深那层毫无影响。
 *
 * <p>而后果不是"留几个进程"，是**下一次运行会静默地测到上一次的服务**：
 * `vite.config.ts` 里 `strictPort: true`，端口被占时新起的 Vite 直接起不来，
 * 而 `UI_URL` 仍然打得到旧的那个 —— 你以为在验新代码，其实验的是旧进程。
 * 这个坑第九轮踩过一次（见 web/README.md），本脚本本来就是为了消灭它。
 *
 * <h2>所以三重收尾，缺一不可</h2>
 * <ol>
 *   <li><b>按 pid 杀子孙</b>（递归找到所有后代再杀）—— 覆盖"shell 还活着"的常见情形；</li>
 *   <li><b>按端口杀</b> —— 覆盖"shell 已经退了、pid 用不上"的情形。
 *       端口是本脚本**独占命名**的资源（8081 / 5173 / 5174，可用
 *       `CI_MOCK_PORT` / `CI_UI_PORT` 改），所以在这里按端口收尾是**有意的强杀**，
 *       而不是"看到进程就杀"；</li>
 *   <li><b>先杀子孙再杀父</b> —— 反过来的话父进程先没了，子孙会变成孤儿，
 *       而按 pid 那一招就再也够不到它们。</li>
 * </ol>
 */
function killAll() {
  const pids = [...children].map(c => c.pid).filter(Boolean)
  children.clear()

  if (process.platform === 'win32') {
    // ① 递归找后代（包含自己），深度优先顺序正好是"先子孙后父"
    for (const pid of pids) {
      for (const target of descendantsOnWindows(pid)) {
        spawnSync('taskkill', ['/pid', String(target), '/T', '/F'], { stdio: 'ignore' })
      }
    }
    // ② 按端口兜底（本脚本独占这几个端口，见上面的说明）
    for (const port of [MOCK_PORT, UI_PORT, UI_PORT + 1]) {
      const pid = listenerPidOnWindows(port)
      if (pid) {
        spawnSync('taskkill', ['/pid', String(pid), '/T', '/F'], { stdio: 'ignore' })
      }
    }
  }
  else {
    // POSIX：detached: true 让每个子进程成为**进程组**组长，
    // 于是负号 pid 可以一次干掉整组（这是 POSIX 上比递归找后代更可靠的做法）
    for (const pid of pids) {
      try {
        process.kill(-pid, 'SIGKILL')
      } catch {
        // 已经退出了 —— 这正是期望的状态
      }
    }
  }
}

/**
 * 在 Windows 上找某个 pid 的**全部后代**（含自己），**子孙在前、父在后**。
 *
 * <p>用 `wmic` 而不是 `Get-CimInstance`：前者在 Windows 上几乎总是存在、
 * 启动更快，而且输出格式简单（`ParentProcessId,ProcessId`）。
 * 拿不到时返回只有自己那一个 —— 上层还有"按端口"那一重兜底，不会因此漏掉服务。
 */
function descendantsOnWindows(rootPid) {
  const out = []
  const seen = new Set()
  const childMap = new Map()

  const res = spawnSync('wmic', ['process', 'get', 'ParentProcessId,ProcessId', '/format:csv'],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
  if (res.stdout) {
    for (const line of res.stdout.split(/\r?\n/)) {
      // 每行形如 `NODE,1234,5678`（列序由 /format:csv 决定：Node,ParentProcessId,ProcessId）
      const parts = line.trim().split(',')
      if (parts.length < 3) continue
      const parent = Number(parts[1])
      const child = Number(parts[2])
      if (!Number.isFinite(parent) || !Number.isFinite(child)) continue
      if (!childMap.has(parent)) childMap.set(parent, [])
      childMap.get(parent).push(child)
    }
  }

  const walk = pid => {
    if (seen.has(pid)) return
    seen.add(pid)
    for (const child of childMap.get(pid) ?? []) {
      walk(child)
    }
    out.push(pid) // 后序：子孙先入列
  }
  walk(rootPid)
  return out
}

/** 在 Windows 上找监听某个端口的进程 pid（找不到返回 null）。 */
function listenerPidOnWindows(port) {
  const res = spawnSync('netstat', ['-ano', '-p', 'TCP'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
  if (!res.stdout) return null
  for (const line of res.stdout.split(/\r?\n/)) {
    // `  TCP    127.0.0.1:8081    0.0.0.0:0    LISTENING    10268`
    const parts = line.trim().split(/\s+/)
    if (parts.length >= 5 && parts[0] === 'TCP' && parts[3] === 'LISTENING' && parts[1].endsWith(`:${port}`)) {
      const pid = Number(parts[4])
      if (Number.isFinite(pid) && pid > 0) return pid
    }
  }
  return null
}

process.on('exit', killAll)
process.on('SIGINT', () => {
  console.log('\n[ci] 收到中断，正在清理子进程…')
  killAll()
  process.exit(130)
})

/**
 * 跑一条命令，输出同时进终端与 `ci-logs/<name>.log`。
 *
 * @param {string} command 完整命令行。**必须是一个整串**，不能是 (命令, 参数数组) ——
 *   实测 Node 会为此报 `DeprecationWarning: Passing args to a child process with shell
 *   option true …`（DEP0190）。警告本身无害，但这个项目的纪律是**控制台必须干净**：
 *   一条常驻的警告会训练人忽略整个警告通道，而那正是"新警告出现时没人看见"的成因。
 * @returns {Promise<number>} 退出码
 */
function run(name, command, options = {}) {
  return new Promise(resolve => {
    const logFile = path.join(LOG_DIR, `${name}.log`)
    const header = `$ ${command}\n(cwd: ${options.cwd ?? ROOT})\n\n`
    writeFileSync(logFile, header, 'utf8')

    console.log(`\n${'='.repeat(78)}`)
    console.log(`[ci] ${name}`)
    console.log(`[ci] $ ${command}`)
    console.log(`${'='.repeat(78)}`)

    const child = spawn(command, {
      cwd: options.cwd ?? ROOT,
      env: { ...process.env, ...(options.env ?? {}) },
      // shell: 让 `mvn` / `pnpm` 这两个"在 Windows 上其实是 .cmd / .ps1"的命令能直接跑。
      // 不用 shell 的话 Windows 会报 ENOENT，而错误信息看起来像"命令没装"
      shell: true,
      stdio: ['ignore', 'pipe', 'pipe'],
      detached: process.platform !== 'win32',
    })
    children.add(child)

    // 【带 name 的收集器，而不是让两股流各写各的】
    // stderr 与 stdout 分文件会让"失败原因在 stderr、上下文在 stdout"时读起来要来回跳，
    // 而这正是排查时最费时间的形态
    child.stdout.on('data', chunk => appendLog(logFile, chunk))
    child.stderr.on('data', chunk => appendLog(logFile, chunk))
    child.on('close', code => {
      children.delete(child)
      results.push({ gate: name, code: code ?? 1, log: path.relative(ROOT, logFile) })
      console.log(`[ci] ${name} → 退出码 ${code ?? 1}${code === 0 ? ' ✅' : ' ❌'}`)
      resolve(code ?? 1)
    })
    child.on('error', err => {
      children.delete(child)
      appendLog(logFile, Buffer.from(`\n[ci] 启动失败：${err.message}\n`, 'utf8'))
      results.push({ gate: name, code: 1, log: path.relative(ROOT, logFile) })
      resolve(1)
    })
  })
}

/**
 * 追写日志。
 *
 * <p>⚠️ 这里刻意**不**做编码转换：子进程交出来的就是字节，而各家的编码不同
 * （Maven 在本机是 GBK、Node 是 UTF-8）。原样落盘至少是"与终端一致"的；
 * 强行猜一个编码去转，只会在某一半上把好好的字节转坏。
 * 真正治根的动作在 pom 里（钉 `stdout.encoding=UTF-8`），见那里的注释。
 */
function appendLog(file, chunk) {
  try {
    writeFileSync(file, chunk, { flag: 'a' })
  } catch {
    // 日志写不进去不该让构建失败：它是观测，不是被测对象
  }
}

/**
 * 轮询等一个 HTTP 端点可用。
 *
 * <p>超时抛 {@link PreconditionError} 而不是普通 Error —— 因为这一层失败的含义
 * 永远是"该在跑的东西没在跑"，不是"代码有问题"（见那个类的注释）。
 */
async function waitForHttp(url, { timeoutMs = 90_000, label = url } = {}) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(3000) })
      if (res.ok) return true
    } catch {
      // 还没起来 —— 继续等
    }
    await sleep(750)
  }
  throw new PreconditionError(`等待「${label}」超时（${timeoutMs}ms）：${url} 上没有响应`);
}

/**
 * 起一个"长驻"服务（不 await 它的退出，只登记进 children 以便收尾）。
 *
 * @param {string} command 完整命令行（理由同 {@link run}）
 */
function startService(name, command, options = {}) {
  const logFile = path.join(LOG_DIR, `${name}.log`)
  writeFileSync(logFile, `$ ${command}\n(cwd: ${options.cwd ?? ROOT})\n\n`, 'utf8')
  console.log(`[ci] 启动 ${name}：${command}`)
  const child = spawn(command, {
    cwd: options.cwd ?? ROOT,
    env: { ...process.env, ...(options.env ?? {}) },
    shell: true,
    stdio: ['ignore', 'pipe', 'pipe'],
    detached: process.platform !== 'win32',
  })
  children.add(child)
  child.stdout.on('data', chunk => appendLog(logFile, chunk))
  child.stderr.on('data', chunk => appendLog(logFile, chunk))
  child.on('close', code => {
    children.delete(child)
    // 长驻服务自己退出 = 异常（它该一直活着直到我们杀它）。留一行痕迹，
    // 否则后面的失败会表现成"UI_URL 打不到"，而真因是服务早就死了
    if (code !== 0 && code !== null) {
      appendLog(logFile, Buffer.from(`\n[ci] ${name} 意外退出，code=${code}\n`, 'utf8'))
    }
  })
  return child
}

// ---------------------------------------------------------------------------
//  门禁
// ---------------------------------------------------------------------------

/**
 * 门禁 1：后端测试（内含离线评测回归基线 `RagRegressionTest`）。
 *
 * <h2>为什么一律加 `-o`（离线）</h2>
 * 这个项目的依赖早已全部在本地仓库里，而联网解析会在 CI 上引入一个
 * **与代码无关的失败源**（镜像抽风、网络抖动）。门禁的失败必须只指向代码 ——
 * 否则第一次"红了但不是我改的东西"之后，人就开始习惯性重跑，
 * 而那正是门禁失效的第一步（不是被删掉，而是被无视）。
 *
 * <p>真需要联网解析新依赖时，用 `mvn` 手工跑一次把它拉进本地仓库即可 ——
 * 那是一次性的环境准备，不该混在"验证代码"这条路径里。
 */
async function gateBackend() {
  return run('01-backend-tests', 'mvn -o test', { cwd: path.join(ROOT, 'ai-agent') })
}

/** 门禁 2：界面自检（假后端），**跑两遍**。 */
async function gateUiMock() {
  const webDir = path.join(ROOT, 'web')

  startService('00-mock-backend', 'pnpm mock', { cwd: webDir })
  await waitForHttp(`http://127.0.0.1:${MOCK_PORT}/api/agent/runs`, { label: '假后端' })

  startService('00-vite-mock', `pnpm dev:mock --port ${UI_PORT} --strictPort`, { cwd: webDir })
  await waitForHttp(`http://127.0.0.1:${UI_PORT}`, { label: 'vite（mock 模式）' })

  const env = { UI_URL: `http://127.0.0.1:${UI_PORT}` }
  const first = await run('02-ui-check-mock-run1', 'pnpm check:ui', { cwd: webDir, env })
  if (first !== 0) return first

  // 【为什么要跑第二遍】假后端里有一批状态机（HITL 的批准/拒绝、Prompt 面板的版本号），
  // 靠 `__reset` / `__set-approval` 复位端点做到顺序无关。这两条端点本身也可能写错 ——
  // 而它写错的表现是"**单独跑没问题、连着跑第二遍才红**"，只在 CI 里能稳定暴露。
  // 第十二轮就是这么发现"两条路径都全过"是有前提的。
  return run('02-ui-check-mock-run2', 'pnpm check:ui', { cwd: webDir, env })
}

/**
 * 门禁 3：界面自检（真后端）。
 *
 * <p>要求后端**已经在跑** —— 本脚本刻意不替它起：起一个 Spring 后端意味着
 * 要管 JDK、要等几十秒、还要处理"它起来了但上游 key 是假的"这种状态，
 * 那是另一件事（而且真后端路径的价值在于验证**真实契约**，
 * 由脚本自己起一个"刚好能跑"的后端反而会削弱这一点）。
 */
async function gateUiReal() {
  const baseUrl = process.env.CI_REAL_BACKEND ?? 'http://127.0.0.1:8080'
  await waitForHttp(`${baseUrl}/api/agent/runs`, { timeoutMs: 15_000, label: '真后端' })

  const webDir = path.join(ROOT, 'web')
  const realUiPort = UI_PORT + 1
  startService('00-vite-real', `pnpm dev --port ${realUiPort} --strictPort`, {
    cwd: webDir,
    env: { VITE_API_TARGET: baseUrl },
  })
  await waitForHttp(`http://127.0.0.1:${realUiPort}`, { label: 'vite（真后端模式）' })

  // 【"我验的到底是哪一个"必须先确认】（第九轮踩过，web/README.md 里写了）
  // 假后端的 awaitingRecovery 恒为 1、真后端是 0 —— 这个数字是"我打到了谁"的指纹。
  // 不确认的话，端口被旧进程占着时你会以为在验真后端，其实验的是假后端
  const probe = await (await fetch(`http://127.0.0.1:${realUiPort}/api/agent/runs`)).json()
  const looksMock = probe.awaitingRecovery === 1
  console.log(`[ci] 路径指纹：awaitingRecovery=${probe.awaitingRecovery} → ${looksMock ? '假后端' : '真后端'}`)
  if (looksMock) {
    // 这是**前置条件失败**，不是断言失败 —— 门禁压根没跑。所以既报错也返回非 0，
    // 但不说"界面自检失败"（那会让人去查界面代码，而问题在端口上）
    throw new PreconditionError(
      '以为在验真后端，实际打到的是假后端（awaitingRecovery=1）—— 多半是端口被上一次遗留的进程占着。'
				+ ' 拒绝继续：跑一次"假后端"却记为"真后端通过"，比不跑更糟。见 web/README.md')
  }

  return run('03-ui-check-real', 'pnpm check:ui', {
    cwd: webDir,
    env: {
      UI_URL: `http://127.0.0.1:${realUiPort}`,
      // 真后端上"断点恢复的检查点大小"这类断言要跳过（那次运行 0 步，假 key 直接 401）
      CHECK_EXPECT_REFLECTION: '0',
    },
  })
}

// ---------------------------------------------------------------------------
//  自检：这个脚本本身进得了版本控制吗
// ---------------------------------------------------------------------------
//
// ⚠️ 这一段检查已于 2026-09-18 **删除**，因为它检查的那条 `.gitignore` 规则没了 ——
// 一条"永远会说 ✓"的检查与没有这条检查在任何观测上一样，而它会让人以为这里还有人守着。
//
// 它当年守的是什么（值得留档，因为它是"本地永远发现不了"那一类里最典型的一个）：
//   `.gitignore` 里有一条 `scripts/*`（那里原本只放一次性排查脚本），于是
//   `.github/workflows/regression.yml` 里那句 `run: node scripts/ci.mjs` **在 CI 上必然报
//   Cannot find module** —— 而本地文件就在那儿、`node scripts/ci.mjs` 跑得好好的、
//   测试全绿。缺的那一环是"git 会不会把它带上"，而那不是任何一条测试会去问的问题。
//
// 它顺带留下的一条判据（**仍然值得记**）：`git check-ignore -v` 的**退出码不能当判据** ——
// 加了 `-v` 之后它会显示**匹配到的最后一条规则**，**包括反向规则**（`!` 开头）。
// 也就是说"匹配了规则"与"被忽略了"不是同一件事：匹配到 `!` 开头的规则恰恰意味着它被救回来了。
// 判据必须看**规则内容**，不能看退出码。
//
// 现在这条规则被整体取消了（白名单比黑名单可靠：一次性的删掉，留着的都有长期用途），
// 所以这个缺口在结构上不再存在 —— 而不是"被一条自检挡住了"。

// ---------------------------------------------------------------------------
//  主流程
// ---------------------------------------------------------------------------

async function main() {
  mkdirSync(LOG_DIR, { recursive: true })
  console.log('[ci] 回归门禁开始')
  console.log(`[ci] 仓库根目录：${ROOT}`)
  console.log(`[ci] 门禁：后端测试（含离线评测回归基线）${WITH_UI ? ' + 界面自检（假）' : ''}${WITH_REAL_UI ? ' + 界面自检（真）' : ''}`)

  let failed = null
  let blockedBy = null
  try {
    if ((await gateBackend()) !== 0) failed = '后端测试'
    else if (WITH_UI && (await gateUiMock()) !== 0) failed = '界面自检（假后端）'
    else if (WITH_REAL_UI && (await gateUiReal()) !== 0) failed = '界面自检（真后端）'
  }
  catch (err) {
    // 前置条件不满足 ≠ 断言失败。分开处理的理由见 PreconditionError 的注释：
    // 门禁压根没跑，所以结论是"没验"，而不是"验了、没过"。
    // 把这两种说成同一句话，下次真出现编排出错时人已经学会忽略这条信息了
    if (err instanceof PreconditionError) blockedBy = err.message
    else throw err
  }

  killAll()

  // ---- 汇总 ----
  console.log(`\n${'='.repeat(78)}`)
  console.log('[ci] 汇总')
  console.log(`${'='.repeat(78)}`)
  for (const r of results) {
    console.log(`  ${r.code === 0 ? '✅' : '❌'} ${r.gate}（退出码 ${r.code}）→ ${r.log}`)
  }

  // 【未执行的门禁必须显式说出来】
  // 不说的话，"这次全绿"会被读成"四道门禁都过了"，
  // 而真相是其中两道压根没跑 —— 一个被跳过的门禁比没有门禁更危险
  const skipped = []
  if (!WITH_UI) skipped.push('界面自检（假后端）—— 加 --with-ui 打开（需要 Playwright + 一个浏览器内核）')
  if (!WITH_REAL_UI) skipped.push('界面自检（真后端）—— 加 --with-real-ui 打开（需要 8080 上已跑着后端）')
  // 已经跑了、但被前置条件挡住的那一道，也要算进"没验"
  if (blockedBy) skipped.push(`界面自检（真后端）—— 前置条件不满足：${blockedBy}`)
  if (skipped.length > 0) {
    console.log('\n[ci] ⚠️ 本次**未执行**的门禁（"全绿"不代表它们过了）：')
    for (const s of skipped) console.log(`  - ${s}`)
  }

  console.log('\n[ci] 产物：')
  console.log('  - ai-agent/target/rag-regression-report.txt  离线评测回归门禁的人读报告（UTF-8）')
  console.log('  - ai-agent/target/rag-actual.json            本次实测的全部指标')
  console.log('  - ci-logs/                                   各道门禁的完整输出')

  if (failed) {
    console.log(`\n[ci] ❌ 门禁失败：${failed}。日志见 ci-logs/。`)
    process.exit(1)
  }
  if (blockedBy) {
    // 【退出码同样是 1，但话不一样】
    // 门禁没跑成不能算通过 —— 那会让"后端起没起"变成"门禁过不过"的隐藏变量。
    // 但也不说"失败在界面自检"，因为那会把人指向界面代码，而问题在端口上
    console.log(`\n[ci] ⛔ 有门禁**没能执行**（不是失败，是没跑成）：${blockedBy}`)
    console.log('[ci]    退出码按失败处理（CI 上必须红）—— 见 docs/CI回归门禁.md「首次接入要盯什么」。')
    process.exit(1)
  }
  console.log('\n[ci] ✅ 已执行的门禁全部通过。')
}

main().catch(err => {
  killAll()
  console.error('[ci] ❌ 编排本身出错：', err?.stack ?? err)
  process.exit(1)
})
