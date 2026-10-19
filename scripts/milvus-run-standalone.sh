#!/usr/bin/env bash
# 前台启动 Milvus standalone。
# 由 scripts/milvus-start.ps1 用独立的 wsl.exe 进程拉起（WSL1 没有 systemd，
# 而且 wsl.exe 一退出，挂在那个会话下的进程会被一起收掉）。
#
# 这个文件由 scripts/milvus-install-wsl.sh 安装到 ~/milvus/start-milvus.sh。
BASE="$HOME/milvus"

# 必须先 cd：wsl.exe 会把 Windows 侧的工作目录带进来（就是本项目的仓库根），
# 而 Milvus 的嵌入式 etcd 在没有绝对 data-dir 时会按 cwd 建 default.etcd ——
# 那会让 60MB+ 的 etcd 数据出现在仓库里。绝对 data-dir 写在 conf/embedEtcd.yaml，
# 这里的 cd 是第二道保险。
cd "$BASE" || exit 1

export MILVUSCONF="$BASE/conf"
export LD_LIBRARY_PATH="$BASE/rootfs/usr/lib/milvus"
export DEPLOY_MODE=STANDALONE
exec "$BASE/rootfs/usr/bin/milvus" run standalone >> "$BASE/milvus.log" 2>&1
