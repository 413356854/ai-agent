# 停止本机 WSL1 里的 Milvus standalone。
#
# 用法：pwsh -File scripts/milvus-stop.ps1
#
# 说明：Milvus 收到 SIGTERM 后会先正常清理（关闭 rocksmq、embedded etcd），
# 日志里 "All cleanup done" 之后可能跟一条 `panic: context canceled` 堆栈 ——
# 那是它退出路径上的已知现象，清理已经完成，不影响数据。

param(
	[string]$Distro = 'Ubuntu-20.04',
	[int]$TimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'

function Test-MilvusPort {
	param([int]$Port = 19530, [int]$TimeoutMs = 3000)
	$client = New-Object System.Net.Sockets.TcpClient
	try {
		$task = $client.ConnectAsync('127.0.0.1', $Port)
		return $task.Wait($TimeoutMs) -and $client.Connected
	} catch {
		return $false
	} finally {
		$client.Dispose()
	}
}

if (-not (Test-MilvusPort)) {
	Write-Host "Milvus 没有在跑（127.0.0.1:19530 不可连）。"
	exit 0
}

Write-Host "停止 Milvus（WSL: $Distro）..."
wsl -d $Distro -- bash -c "pkill -TERM -f 'milvus run standalone' || true" | Out-Null

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline) {
	Start-Sleep -Seconds 2
	if (-not (Test-MilvusPort)) {
		Write-Host "已停止。"
		exit 0
	}
}

Write-Error "等待 $TimeoutSeconds 秒后 19530 仍可连，进程可能没停下来。"
exit 1
