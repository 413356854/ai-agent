<#
  把项目自己的 Markdown 文档收进 RAG 评测语料库。

  【为什么要"复制一份"而不是直接指向仓库根目录的原文】
  评测集必须**可复现**：40 条评测用例把"标准答案"标注到了具体的章节路径上
  （例如 "四、踩坑知识库" / "学习点 7 · 虚拟线程异步改造"）。
  如果语料直接读根目录那份文档，那么每次往台账里追加内容、
  或者改一个小标题，标准答案就可能静默失效 —— 而失效的标注不会报错，
  只会让召回率悄悄掉下来，看起来像"我的分块策略变差了"。

  所以语料是**快照**：日期固定在文件名里的这份副本，改了原文也不动它。
  想更新语料，就重跑这个脚本、重新核对标注 —— 这是一次显式动作，不是副作用。

  【2026-09-18：源文件只剩一份了】
  那 4 份学习文档已逐条核对、合并进 `docs/项目功能点与代码索引.md`（附录 A）后**删除**，
  所以下面 $map 里只剩 `学习进度台账.md` 还有源文件。另外 3 份**没有源文件**，
  corpus 目录里的那份就是**唯一的工作副本**（完整原文另在 git 历史里：`git show HEAD:"<文件名>"`）。
  重跑本脚本时它们会被**跳过并给出 WARNING** —— 这是预期的，不是出错；
  脚本**不会**删除已存在的语料文件（快照本来就该冻着，`rag/eval-set.tsv` 的 relevantSection
  标注钉在它们的章节路径上）。

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

# 源文件 → 语料库文件名。用 ASCII 文件名是刻意的：
# 中文文件名在 Windows / 压缩包 / CI 之间来回搬运时容易被重新编码，
# 而 resource 路径是要写进评测集 JSON 的，必须稳定。
#
# ⚠️ 只列**还有源文件**的。被删掉的那 3 份学习的源文件仍然可用上面的 git 命令取回；
#    想重新纳管（例如把某一份又放回根目录），把它加回本表即可 —— 语料文件名保持不变，
#    所以 eval-set.tsv 的标注不受影响。
$map = [ordered]@{
    '学习进度台账.md'                  = 'ledger-progress.md'
}

# 已冻结、**没有源文件**的语料（列在这里是为了让"为什么只有一行"一眼可见）
$frozen = @{
    'roadmap-java-to-agent.md'   = 'Java开发者-AI-Agent转型路线.md'
    'materials-checklist.md'     = 'Java转型-AI-Agent学习资料清单.md'
    'knowledge-points-ranked.md' = 'AI-Agent应用岗知识要点-重要度排序.md'
}

if (-not (Test-Path $outDir)) {
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
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
    Write-Host ("  {0,-34} <- {1,-38} {2,7} bytes / {3,6} chars" -f $map[$src], $src, $bytes, $chars)
    $copied++
}

Write-Host ""
Write-Host "语料库已生成：$outDir（$copied 个文件）" -ForegroundColor Green
Write-Host "冻结语料（无源文件，本次未重建）："
foreach ($name in $frozen.Keys) {
    $p = Join-Path $outDir $name
    $state = if (Test-Path $p) { '{0} bytes' -f (Get-Item $p).Length } else { '** 缺失 **' }
    Write-Host ("  {0,-30} <- {1,-38} {2}" -f $name, $frozen[$name], $state)
}
Write-Host "注意：语料是快照。改动了源文档后若要更新语料，请重跑本脚本，"
Write-Host "      并重新核对 rag/eval-set.tsv 里的 relevantSection 标注是否仍然有效。"
