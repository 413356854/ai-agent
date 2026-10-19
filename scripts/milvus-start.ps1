# 启动本机 WSL1 里的 Milvus standalone，并等到真正就绪。
#
# 为什么不做成 systemd 服务：WSL1 没有 systemd（PID 1 不是 init），
# Milvus 的 DEB 包只带了 milvus.service，在这里起不来，
# 所以用「前台进程 + 独立 wsl.exe 宿主」的方式常驻。
#
# 就绪判据用 http://127.0.0.1:9091/healthz 返回 200，而不是「19530 能连上」：
# 实测刚启动时 19530 已经接受连接、v2 API 也能用，但 healthz 还是 500
# （proxy 已起、组件健康状态未全部上报），此时发 DDL 不是它承诺的状态。
#
# 用法：pwsh -File scripts/milvus-start.ps1
#       pwsh -File scripts/milvus-start.ps1 -Distro Ubuntu-20.04 -TimeoutSeconds 300

param(
	[string]$Distro = 'Ubuntu-20.04',
	[int]$TimeoutSeconds = 300,
	[string]$WslHome = '/home/ljf/milvus'
)

$ErrorActionPreference = 'Stop'

function Test-MilvusReady {
	param([int]$TimeoutSec = 5)
	try {
		$r = Invoke-WebRequest -Uri 'http://127.0.0.1:9091/healthz' -TimeoutSec $TimeoutSec -UseBasicParsing
		return $r.StatusCode -eq 200
	} catch {
		return $false
	}
}

if (Test-MilvusReady) {
	Write-Host 'Milvus 已在运行且就绪（healthz 200），无需重复启动。'
	exit 0
}

$script = "$WslHome/start-milvus.sh"
$exists = (wsl -d $Distro -- bash -c "test -x '$script' && echo yes || echo no") 2>&1
if ("$exists".Trim() -ne 'yes') {
	Write-Error "在 WSL($Distro) 里找不到可执行的 $script —— Milvus 可能还没装（见 ai-agent/docs/向量库环境记录.md 第二节）。"
	exit 1
}

Write-Host "启动 Milvus（WSL: $Distro）..."
$start = Get-Date
# 独立进程：不挂在当前 shell 下，本终端退出后 Milvus 继续跑
Start-Process -FilePath 'wsl.exe' `
	-ArgumentList '-d', $Distro, '--', 'bash', $script `
	-WindowStyle Hidden

$deadline = $start.AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline) {
	Start-Sleep -Seconds 3
	if (Test-MilvusReady) {
		$secs = [int]((Get-Date) - $start).TotalSeconds
		Write-Host "Milvus 已就绪：127.0.0.1:19530（healthz 200，用时约 $secs 秒）"
		Write-Host 'WebUI: http://127.0.0.1:9091/webui/'
		exit 0
	}
}

Write-Error "等待 $TimeoutSeconds 秒后 healthz 仍非 200。看日志：wsl -d $Distro -- tail -50 $WslHome/milvus.log"
exit 1
