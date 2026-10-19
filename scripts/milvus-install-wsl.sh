#!/usr/bin/env bash
# 在 WSL1 里安装 Milvus standalone（不需要 root、不需要 Docker、不需要 systemd）。
#
# 为什么走 DEB 包而不是官方首选的 Docker：官方 Windows 路径要求 Docker Desktop + WSL2，
# 而本机 WSL2 起不来（缺「虚拟机平台」的 hypervisor，需管理员 + 重启），详见
# ai-agent/docs/向量库环境记录.md。Milvus 官方另外发布了 RPM/DEB 包，里面是
# /usr/bin/milvus 这个原生二进制，不依赖 Docker。
#
# 版本选 2.6.0 而不是最新的 3.x：pom 里 Spring AI 2.0.1 带的是 milvus-sdk-java 2.6.21，
# 同一小版本线。另外 2.6.x 里只有 2.6.0 这一版在 GitHub Release 上挂了 deb/rpm 资产。
#
# 用法（在 WSL 里跑）：bash /mnt/c/.../scripts/milvus-install-wsl.sh
set -euo pipefail

MILVUS_VER="2.6.0"
BASE="$HOME/milvus"
DEB="milvus_${MILVUS_VER}-1_amd64.deb"
URL="https://github.com/milvus-io/milvus/releases/download/v${MILVUS_VER}/${DEB}"

echo "==> 安装目录：$BASE"
mkdir -p "$BASE"
cd "$BASE"

echo "==> [1/5] 下载官方 DEB（约 191MB）"
if [ ! -s "$DEB" ]; then
	curl -L --fail --retry 3 -o "$DEB" "$URL"
fi
ls -lh "$DEB"

echo "==> [2/5] 解包到 rootfs/（不用 dpkg -i，避免动系统目录）"
rm -rf rootfs
dpkg-deb -x "$DEB" rootfs

LIBDIR="$BASE/rootfs/usr/lib/milvus"
echo "==> [3/5] 补 libquadmath.so.0"
# DEB 里的 libquadmath.so 是指向 libquadmath.so.0 的符号链接，但自己没带这个文件，
# 它把 libquadmath0 声明成外部依赖。不补的话 ldd 报 "libquadmath.so.0 => not found"。
if [ ! -e "$LIBDIR/libquadmath.so.0" ]; then
	TMPQ="$(mktemp -d)"
	QPKG=""
	for pkgdir in gcc-10 gcc-9; do
		f=$(curl -s "http://archive.ubuntu.com/ubuntu/pool/main/g/$pkgdir/" \
			| grep -oE 'libquadmath0_[^"<>]*_amd64\.deb' | sort -u | tail -1)
		if [ -n "$f" ]; then
			curl -sL --fail -o "$TMPQ/q.deb" "http://archive.ubuntu.com/ubuntu/pool/main/g/$pkgdir/$f"
			QPKG="$f"
			break
		fi
	done
	[ -n "$QPKG" ] || { echo "找不到 libquadmath0 包"; exit 1; }
	echo "    来自 $QPKG"
	dpkg-deb -x "$TMPQ/q.deb" "$TMPQ/x"
	find "$TMPQ/x" -name 'libquadmath.so.0*' -exec cp -av {} "$LIBDIR/" \;
	rm -rf "$TMPQ"
fi

echo "==> [4/5] 准备可写配置（改写只读绝对路径）"
# milvus.yaml 默认把数据写到 /var/lib/milvus、读 /etc/milvus/configs，
# 这两个都要 root；这里整份复制到 $BASE 下并把路径指过来。
rm -rf conf data
cp -r rootfs/etc/milvus/configs conf
mkdir -p data
for f in conf/milvus.yaml conf/embedEtcd.yaml conf/advanced/etcd.yaml; do
	[ -f "$f" ] && sed -i "s#/var/lib/milvus#$BASE/data#g; s#/etc/milvus/configs#$BASE/conf#g" "$f"
done

# 注意：嵌入式 etcd 的数据目录**不能**靠配置指定。实测 Milvus 2.6.0 起 etcd 时用的是
# 命令行 --data-dir（相对路径 default.etcd），会覆盖 embedEtcd.yaml 里的 data-dir 键；
# milvus.yaml 的 etcd.data.data.dir 同样不生效（两者都设过，etcd 日志里仍是
# "data-dir":"default.etcd"）。所以它只由**启动时的 cwd** 决定 ——
# 而 wsl.exe 会把 Windows 侧的项目目录当 cwd 带进来，那样 60MB+ 的 etcd 数据
# 就会落进仓库根目录。约束写在启动脚本的 cd 里（见 milvus-run-standalone.sh）。

echo "==> [5/5] 安装启动脚本"
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/milvus-run-standalone.sh"
[ -f "$SRC" ] || { echo "找不到 $SRC"; exit 1; }
cp "$SRC" "$BASE/start-milvus.sh"
chmod +x "$BASE/start-milvus.sh"

echo
echo "==> 自检"
LD_LIBRARY_PATH="$LIBDIR" ldd rootfs/usr/bin/milvus | grep 'not found' && {
	echo "仍有缺失的库，安装不完整"; exit 1
} || echo "    动态库依赖：全部满足"
echo "    配置：$BASE/conf/milvus.yaml"
echo "    启动脚本：$BASE/start-milvus.sh"
echo
echo "完成。用 pwsh -File scripts/milvus-start.ps1 启动。"
