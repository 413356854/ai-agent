<#
  把项目自己的 Markdown 文档收进 RAG 评测语料库。

  【为什么要"复制一份"而不是直接指向仓库根目录的原文】
  评测集必须**可复现**：43 条评测用例把"标准答案"标注到了具体的章节路径上
  （例如 "四、踩坑知识库" / "学习点 7 · 虚拟线程异步改造"）。
  如果语料直接读根目录那份文档，那么每次往原文里追加内容、
  或者改一个小标题，标准答案就可能静默失效 —— 而失效的标注不会报错，
  只会让召回率悄悄掉下来，看起来像"我的分块策略变差了"。

  所以语料是**快照**：日期固定在文件名里的这份副本，改了原文也不动它。

  【2026-09-18：源文件全部删除了 —— 本脚本现在不再复制任何东西】
  仓库根目录那批源文档已清理掉，语料即唯一下来的副本（4 份都列在下面的 `$frozen` 里），
  功能点与结论见 `docs/项目功能点与代码索引.md`。
  于是 `ai-agent/src/main/resources/rag/corpus/` 里的 4 份就是**唯一的副本**，
  而且**必须继续冻着**：`rag/eval-set.tsv` 的 relevantSection 标注钉在它们的章节路径上
  （其中 12/43 条钉在 `ledger-progress.md` 的「四、踩坑知识库」上），
  一改标注就可能静默失效、评测数字随之变化 —— 而**现在既没有逐位基线，也没有门禁会红**
  （`rag-baseline.json` 与那批后端测试已在 2026-09-18 一起删除）。
  要确认改动的后果，只能起服务打 `GET /api/diag/rag/compare` 重新量一遍，
  再与文档里记的数字对照（见 `ai-agent/docs/RAG-阶段二说明.md` 的「快速上手」）。

  ⚠️ 所以本脚本现在的职责只剩一件：**只读地报告这 4 份语料的状态**（存在、大小、字数）。
  它**不会**复制、不会覆盖、更不会删除任何语料文件。
  完整原文都在 git 历史里：`git show HEAD:"<文件名>"`。

  用法（在仓库根目录执行）：
      pwsh -File scripts\build-rag-corpus.ps1
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$Target   = 'ai-agent\src\main\resources\rag\corpus'
)

$ErrorActionPreference = 'Stop'
$outDir = Join-Path $RepoRoot $Target

# 源文件 → 语料库文件名。
# ⚠️ 现在是**空的**：源文件已全部删除，语料即唯一副本（见文件头）。
#    如果将来某一份源文档又被放回仓库根目录，把它加回本表即可 ——
#    语料文件名保持不变，所以 eval-set.tsv 的标注不受影响。
$map = [ordered]@{}

# 已冻结、**没有源文件**的语料。列在这里是为了让"为什么什么都不复制"一眼可见。
$frozen = @(
    'ledger-progress.md'
    'roadmap-java-to-agent.md'
    'materials-checklist.md'
    'knowledge-points-ranked.md'
)

if (-not (Test-Path $outDir)) {
    Write-Warning "语料目录不存在：$outDir"
}

$copied = 0
foreach ($src in $map.Keys) {
    $from = Join-Path $RepoRoot $src
    if (-not (Test-Path $from)) {
        Write-Warning "跳过（源文件不存在）：$src"
        continue
    }
    $to = Join-Path $outDir $map[$src]

    # 统一成 UTF-8 **无 BOM**：JDK 读 resource 时 BOM 会变成一个 U+FEFF 字符
    # 混进正文首行，进而影响首个分块的内容与 token 估算 —— 这类"看不见的字符"
    # 造成的偏差最难查（它只让第一个块的估算偏大一点点，不会报错）。
    $text = [System.IO.File]::ReadAllText($from, [System.Text.Encoding]::UTF8)
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($to, $text, $utf8NoBom)

    $bytes = (Get-Item $to).Length
    $chars = $text.Length
    Write-Host ("  {0,-30} <- {1,-38} {2,7} bytes / {3,6} chars" -f $map[$src], $src, $bytes, $chars)
    $copied++
}

Write-Host ""
if ($copied -eq 0) {
    Write-Host "本次没有复制任何文件（源文件已全部删除）。" -ForegroundColor Yellow
} else {
    Write-Host "语料库已生成：$outDir（$copied 个文件）" -ForegroundColor Green
}
Write-Host ""
Write-Host "冻结语料状态（唯一副本，请勿改动）："
$missing = 0
foreach ($name in $frozen) {
    $p = Join-Path $outDir $name
    if (Test-Path $p) {
        $item = Get-Item $p
        Write-Host ("  {0,-28} {1,7} bytes   {2:yyyy-MM-dd HH:mm}" -f $name, $item.Length, $item.LastWriteTime)
    } else {
        Write-Host ("  {0,-28} ** 缺失 **" -f $name) -ForegroundColor Red
        $missing++
    }
}
if ($missing -gt 0) {
    Write-Host ""
    Write-Warning "有 $missing 份语料缺失 —— rag/eval-set.tsv 里钉在它们章节路径上的用例会失效，评测数字会变，而且**没有任何东西会自动报出来**（逐位基线与那批后端测试已在 2026-09-18 删除）。"
    Write-Host "取回方式：从 git 历史里取回对应的语料文件。"
}

Write-Host ""
Write-Host "注意：语料是快照，且现在**没有源文件**。要改语料，必须同时重新核对"
Write-Host "      rag/eval-set.tsv 里的 relevantSection 标注，并用 GET /api/diag/rag/compare 重新量一遍评测数字"
Write-Host "      （已无逐位基线可比）—— 这是一次显式动作。"
