param(
  [string]$BaseUrl = "http://127.0.0.1:5211"
)
$ErrorActionPreference = 'Continue'
Add-Type -AssemblyName System.Net.Http

$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.UseProxy = $false
$client = [System.Net.Http.HttpClient]::new($handler)
# 关键：超时给长一点。Streamable HTTP 的响应可能是 SSE 流，
# 而"read 到流结束"要等服务器把流关掉；超时设短会得到一个看起来像"请求失败"的假象
$client.Timeout = [TimeSpan]::FromSeconds(30)

function Invoke-Mcp {
  param([string]$Body, [string]$SessionId = $null)
  $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, "$BaseUrl/mcp")
  $req.Content = [System.Net.Http.StringContent]::new($Body, [System.Text.Encoding]::UTF8, "application/json")
  $req.Headers.Add("Accept", "application/json, text/event-stream")
  if ($SessionId) { $req.Headers.Add("Mcp-Session-Id", $SessionId) }
  try {
    $resp = $client.SendAsync($req, [System.Net.Http.HttpCompletionOption]::ResponseContentRead).GetAwaiter().GetResult()
    $text = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $sid = $null
    $vals = $null
    if ($resp.Headers.TryGetValues("Mcp-Session-Id", [ref]$vals)) { $sid = @($vals)[0] }
    return [pscustomobject]@{ Status = [int]$resp.StatusCode; Session = $sid; Raw = $text; Error = $null }
  }
  catch {
    return [pscustomobject]@{ Status = -1; Session = $null; Raw = $null; Error = $_.Exception.Message }
  }
}

function Get-Payload {
  param([string]$Raw)
  if (-not $Raw) { return $null }
  $dataLines = @($Raw -split "`n" | Where-Object { $_ -match '^\s*data:\s*\S' } |
      ForEach-Object { ($_ -replace '^\s*data:\s*', '').Trim() })
  if ($dataLines.Count -gt 0) { return ($dataLines -join '') }
  return $Raw.Trim()
}

$pass = 0; $fail = 0
function Check {
  param([string]$Name, [bool]$Ok, [string]$Detail)
  if ($Ok) { $script:pass++; Write-Host "[PASS] $Name$(if ($Detail) { " — $Detail" })" }
  else { $script:fail++; Write-Host "[FAIL] $Name$(if ($Detail) { " — $Detail" })" }
}

Write-Host "=== 1) initialize ==="
$r1 = Invoke-Mcp -Body '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"verify-probe","version":"1.0"}}}'
Write-Host "     原始响应：$((($r1.Raw -replace '\s+',' ')))"
Check 'initialize 返回 200' ($r1.Status -eq 200) "status=$($r1.Status)$(if($r1.Error){" err=$($r1.Error)"})"
Check '响应里带 Mcp-Session-Id（后续请求必须带上）' ([bool]$r1.Session) "session=$($r1.Session)"
$p1 = Get-Payload -Raw $r1.Raw
$serverInfo = $null
if ($p1) { try { $serverInfo = ($p1 | ConvertFrom-Json).result.serverInfo } catch { } }
Check 'initialize 报出服务器名与版本' ([bool]$serverInfo) "$($serverInfo | ConvertTo-Json -Compress)"

Write-Host "`n=== 2) notifications/initialized ==="
$r2 = Invoke-Mcp -Body '{"jsonrpc":"2.0","method":"notifications/initialized"}' -SessionId $r1.Session
Check 'initialized 通知被接受' ($r2.Status -in 200, 202) "status=$($r2.Status)"

Write-Host "`n=== 3) tools/list ==="
$r3 = Invoke-Mcp -Body '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' -SessionId $r1.Session
$p3 = Get-Payload -Raw $r3.Raw
$toolNames = @(); $searchTool = $null
if ($p3) {
  try {
    $parsed = $p3 | ConvertFrom-Json
    $toolNames = @($parsed.result.tools | ForEach-Object { $_.name })
    $searchTool = $parsed.result.tools | Where-Object { $_.name -eq 'search_docs' }
  } catch { }
}
Check 'tools/list 返回 200' ($r3.Status -eq 200) "status=$($r3.Status)"
Check '对外恰好暴露 3 把只读工具' ($toolNames.Count -eq 3) "实际=$($toolNames -join ',')"
Check '★ 写工具 propose_review 不在列表里' ($toolNames -notcontains 'propose_review') ""
Check '三把只读工具齐全' ((@($toolNames | Sort-Object) -join ',') -eq 'list_documents,read_section,search_docs') ""
Check 'search_docs 带 readOnlyHint=true 的 annotations' ($searchTool -and $searchTool.annotations.readOnlyHint -eq $true) "$($searchTool.annotations | ConvertTo-Json -Compress)"

Write-Host "`n=== 4) tools/call：只读工具真的能调通（那个坑正是在这里炸）==="
$r4 = Invoke-Mcp -Body '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_documents","arguments":{}}}' -SessionId $r1.Session
$p4 = Get-Payload -Raw $r4.Raw
$callText = $null; $isError = $null
if ($p4) { try { $x = $p4 | ConvertFrom-Json; $callText = (($x.result.content | ForEach-Object { $_.text }) -join ''); $isError = $x.result.isError } catch { } }
Check 'tools/call 返回 200' ($r4.Status -eq 200) "status=$($r4.Status)"
Check '★ 调用真的成功（不是抛 UnsupportedOperationException）' ($callText -and $isError -ne $true) "isError=$isError"
Check '返回执行器那一套回填文本（含 call_id）' ([bool]($callText -match 'call_id')) ""
Check '内容里带知识库文档数（真的读到了语料）' ([bool]($callText -match '知识库共')) ""
if ($callText) { Write-Host "     返回内容前 200 字：$(($callText -replace '\s+',' ').Substring(0, [Math]::Min(200, ($callText -replace '\s+',' ').Length)))" }

Write-Host "`n=== 5) tools/call：错参数走同一道门禁 ==="
$r5 = Invoke-Mcp -Body '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"search_docs","arguments":{}}}' -SessionId $r1.Session
$p5 = Get-Payload -Raw $r5.Raw
$badText = $null
if ($p5) { try { $badText = ((($p5 | ConvertFrom-Json).result.content | ForEach-Object { $_.text }) -join '') } catch { } }
Check '缺必填字段时返回可读的校验失败说明（带字段名 query）' ([bool]($badText -match 'query')) ""

Write-Host "`n=== 6) 外部客户端调写工具：必须调不到 ==="
$r6 = Invoke-Mcp -Body '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"propose_review","arguments":{"title":"x","severity":"LOW"}}}' -SessionId $r1.Session
$p6 = Get-Payload -Raw $r6.Raw
$blocked = ($r6.Status -ne 200) -or ($p6 -match 'not found|Unknown|不存在|error|Error')
Check '★ 写工具调不到（要么非 200，要么明确报错）' $blocked "status=$($r6.Status) body=$((($p6 -replace '\s+',' ')))"

Write-Host "`n=== 7) HITL 端点 ==="
try {
  $appr = $client.GetStringAsync("$BaseUrl/api/agent/approvals").GetAwaiter().GetResult() | ConvertFrom-Json
  Check '/api/agent/approvals 可读' ($null -ne $appr.count) "count=$($appr.count)"
  Check '带 enabled（空列表的两种含义必须能分开）' ($appr.enabled -eq $true) "enabled=$($appr.enabled)"
  Check '带 note（说明闸门当前状态）' ([bool]$appr.note) "$($appr.note)"
} catch { Check '/api/agent/approvals 可读' $false $_.Exception.Message }

try {
  $st = $client.GetStringAsync("$BaseUrl/api/agent/approvals/status").GetAwaiter().GetResult() | ConvertFrom-Json
  Check '/api/agent/approvals/status 可读' ($null -ne $st.timeoutSeconds) "timeoutSeconds=$($st.timeoutSeconds)"
} catch { Check '/api/agent/approvals/status 可读' $false $_.Exception.Message }

$client.Dispose()
Write-Host "`n=== 合计 $($pass + $fail) 项：通过 $pass，失败 $fail ==="
if ($fail -gt 0) { exit 1 } else { exit 0 }
