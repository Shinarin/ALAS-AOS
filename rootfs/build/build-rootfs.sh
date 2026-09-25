#!/usr/bin/env bash
# =============================================================================
# AlasAos 阶段一 M1 · build-rootfs.sh
# 烘焙 Ubuntu ARM64 rootfs：ubuntu-base 24.04 + ALAS（钉版）+ PP-OCR（in-proc onnxruntime）+ wrapper
#
# 运行环境：GitHub Actions `ubuntu-24.04-arm` runner（原生 aarch64，chroot 无需 qemu）。
# 本机（Windows + Git Bash）不可执行：核心动作是 chroot / mount --bind / GNU tar，
# Windows 无这些语义；本机只做 `bash -n` 语法检查与 rootfs/ 资产 curated。
#
# 环境变量（均可 export 覆盖，冒号后为默认值）：
#   ALAS_REF        master                          ALAS 分支/tag；传 40 位 commit sha 则按 commit 浅 fetch
#   ALAS_REPO       https://github.com/LmeSzinc/AzurLaneAutoScript.git
#   ROOTFS_VERSION  0.1.0                           写入 BUILD_MANIFEST.rootfs_version
#   UBUNTU_BASE     https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz
#   WORK_DIR        $GITHUB_WORKSPACE/work          构建工作区（runner 工作区内）
#
# 产物：
#   $GITHUB_WORKSPACE/dist/rootfs.tar.xz            rootfs 包（含 /opt/alas）
#   $GITHUB_WORKSPACE/dist/BUILD_MANIFEST           构建清单（同时装入镜像 /opt/alas/BUILD_MANIFEST，App 可读）
# =============================================================================
set -euo pipefail

# ---------- 0. 变量、提权、前置检查 ----------
ALAS_REF="${ALAS_REF:-master}"
ALAS_REPO="${ALAS_REPO:-https://github.com/LmeSzinc/AzurLaneAutoScript.git}"
# 注：GHA runner 在海外，GitHub 原生最快；gitee 同名镜像对匿名克隆要凭证（401→挂凭证提示），勿用。
# 国内本地复现构建时可 export ALAS_REPO=<可达镜像>；runtime 更新镜像由 deploy.yaml 的 fullcn 配置管，与此无关。
ROOTFS_VERSION="${ROOTFS_VERSION:-0.1.0}"
UBUNTU_BASE="${UBUNTU_BASE:-https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GITHUB_WORKSPACE="${GITHUB_WORKSPACE:-$REPO_ROOT}"
WORK_DIR="${WORK_DIR:-$GITHUB_WORKSPACE/work}"
ROOTFS_DIR="$WORK_DIR/rootfs"
ASSETS="$REPO_ROOT/rootfs"          # 本仓 curated 资产（任务 A 产物）
DIST_DIR="$GITHUB_WORKSPACE/dist"
# 构建期 pip 源：默认 PyPI 官方（GHA runner 在海外，直连最快最稳）；
# 与设备运行时无关（InstallDependencies:false 已锁，rootfs 永不在设备上装包）。
# 国内本地复现构建时可 export PYPI_MIRROR=https://mirrors.aliyun.com/pypi/simple
PYPI_MIRROR="${PYPI_MIRROR:-https://pypi.org/simple}"

log() { echo "[build-rootfs] $*"; }

# chroot / mount 需要 root；GHA runner 有免密 sudo，自提权（-E 保留上面的环境变量），用绝对路径防 CWD 漂移
if [[ "$(id -u)" -ne 0 ]]; then
  exec sudo -E bash "$REPO_ROOT/rootfs/build/build-rootfs.sh" "$@"
fi

if [[ "$(uname -m)" != "aarch64" ]]; then
  log "WARNING: 宿主架构 $(uname -m) 非 aarch64；本脚本设计运行于 GHA ubuntu-24.04-arm，chroot 预计将失败"
fi

# fail-fast：overlays（in-proc OCR rpc.py / wrapper / runner）由并行任务提供，
# 缺任何一个都不许开构建——在下载与 apt 之前先验，省一次白跑
require_file() {
  if [[ ! -f "$1" ]]; then
    echo "::error::必需资产缺失: $1（由并行任务提供，请先落地该文件再触发构建）"
    exit 1
  fi
}
require_file "$ASSETS/overlays/module/ocr/rpc.py"
require_file "$ASSETS/overlays/module/ocr/al_numpy.py"
require_file "$ASSETS/overlays/models/ocr/azur_lane/weights.npz"
require_file "$ASSETS/overlays/models/ocr/azur_lane/label_cn.txt"
require_file "$ASSETS/overlays/wrapper.py"
require_file "$ASSETS/overlays/runner.py"
require_file "$ASSETS/build/spike-f-ocr-gate.py"
require_file "$ASSETS/patches/assets_fix.py"
require_file "$ASSETS/seeds/deploy.yaml"
require_file "$ASSETS/seeds/alasaos_update.sh"
require_file "$ASSETS/seeds/regen_args.py"
require_file "$ASSETS/shims/jellyfish.py"
require_file "$ASSETS/models/ocr/det.onnx"
require_file "$ASSETS/models/ocr/rec.onnx"
require_file "$ASSETS/models/ocr/keys.txt"

# chroot 内统一环境：干净 env + 非交互 + C.UTF-8（免 perl locale 警告）
chroot_run() {
  chroot "$ROOTFS_DIR" /usr/bin/env -i \
    HOME=/root LANG=C.UTF-8 LC_ALL=C.UTF-8 DEBIAN_FRONTEND=noninteractive \
    GIT_TERMINAL_PROMPT=0 \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    "$@"
}

# ---------- 1. 下载并解包 ubuntu-base ----------
mkdir -p "$WORK_DIR" "$DIST_DIR"
BASE_TAR="$WORK_DIR/ubuntu-base.tar.gz"
if [[ ! -f "$BASE_TAR" ]]; then
  log "下载 ubuntu-base: $UBUNTU_BASE"
  curl -fL --retry 3 -o "$BASE_TAR" "$UBUNTU_BASE"
fi
rm -rf -- "${ROOTFS_DIR:?}/"
mkdir -p "$ROOTFS_DIR"
tar -xzf "$BASE_TAR" -C "$ROOTFS_DIR"

# ---------- 2. 挂载准备（trap 兜底卸载；打包前还会显式卸载并校验） ----------
# chroot 老规矩：/dev /proc /sys bind 进去；/dev/pts 单独 bind（bind 不携带子挂载点，
# 而 apt 部分 postinst 需要 pts）。挂载失败 → set -e 中止 → EXIT trap 卸掉已挂部分。
MOUNTED=()
mount_bind() {
  mount --bind "$1" "$2"
  MOUNTED+=("$2")
}
cleanup_mounts() {
  local i
  for (( i=${#MOUNTED[@]}-1; i>=0; i-- )); do
    if ! umount -lf "${MOUNTED[i]}" 2>/dev/null; then
      echo "::warning::umount 失败: ${MOUNTED[i]}（runner 为一次性环境，影响有限，但需记录）"
    fi
  done
  MOUNTED=()
}
trap cleanup_mounts EXIT

# DNS：不能 bind 宿主 /etc/resolv.conf——GHA runner 是 systemd-resolved stub
# （127.0.0.53），chroot 内没有 resolved 监听，解析必挂。写静态 resolv.conf
# （AliDNS + Cloudflare）bind 进去；ubuntu-base 自带的是指向 /run/systemd 的悬空软链，先删
rm -f "$ROOTFS_DIR/etc/resolv.conf"
touch "$ROOTFS_DIR/etc/resolv.conf"
printf 'nameserver 223.5.5.5\nnameserver 1.1.1.1\n' > "$WORK_DIR/resolv.conf"
mount_bind "$WORK_DIR/resolv.conf" "$ROOTFS_DIR/etc/resolv.conf"
# ubuntu-base 的 /dev 下可能没有 pts/ 子目录（mount --bind 要求挂载点已存在），先补齐
mkdir -p "$ROOTFS_DIR/dev/pts" "$ROOTFS_DIR/proc" "$ROOTFS_DIR/sys"
mount_bind /dev "$ROOTFS_DIR/dev"
mount_bind /dev/pts "$ROOTFS_DIR/dev/pts"
mount_bind /proc "$ROOTFS_DIR/proc"
mount_bind /sys "$ROOTFS_DIR/sys"

# ---------- 3. chroot 内 apt：最小系统依赖 ----------
# opencv-headless 运行只需 glib/gomp 级系统库；不装 Qt/X11
chroot_run apt-get update
chroot_run apt-get install -y --no-install-recommends \
  python3 python3-pip python3-venv git ca-certificates \
  libglib2.0-0t64 libgomp1 curl xz-utils
chroot_run /bin/bash -c 'rm -rf /var/lib/apt/lists/*'

# deploy.yaml 里 PythonExecutable: python；ubuntu-base 只有 python3，补软链对齐
chroot_run ln -sf /usr/bin/python3 /usr/local/bin/python

# ---------- 4. chroot 内建 /opt/alas：浅克隆并钉版 ----------
if [[ "$ALAS_REF" =~ ^[0-9a-fA-F]{40}$ ]]; then
  # 钉 commit：浅 fetch 指定 sha。注意 gitee 若未开 allow-any-sha1-in-want 会拒绝——
  # 那时请改用 branch/tag；此处失败即构建失败，钉版语义不允许静默回退
  chroot_run git init /opt/alas
  chroot_run git -C /opt/alas remote add origin "$ALAS_REPO"
  chroot_run git -C /opt/alas fetch --depth 1 origin "$ALAS_REF"
  chroot_run git -C /opt/alas checkout --detach FETCH_HEAD
else
  chroot_run git clone --depth 1 --branch "$ALAS_REF" "$ALAS_REPO" /opt/alas
fi
PINNED_COMMIT="$(chroot_run git -C /opt/alas rev-parse HEAD)"
log "ALAS 钉版: $PINNED_COMMIT (ref: $ALAS_REF)"

# ---------- 5. chroot 内 pip（系统级安装，Ubuntu 24.04 PEP 668 需 --break-system-packages） ----------
pip_install() {
  chroot_run python3 -m pip install --break-system-packages --no-cache-dir -i "$PYPI_MIRROR" "$@"
}

# 依赖层（单条 install）：现代化宽松版本，来源 = m0 termux/setup_env.sh 真机实证集
# （Termux/py3.14 跑通 ALAS：numpy 2.4.4 / scipy 1.18.1 / cv2 4.14.0 / pydantic 1.10.26）。
# 不以 ALAS deploy/headless/requirements.txt 钉版为底——其钉版在 aarch64 + py3.12 大面积
# 无 wheel 死链（numpy==1.17.4 / scipy==1.4.1 / pillow==9.5.0 / av==10.0.0 / lz4==4.3.2 等）。
# 不装清单：jellyfish（Rust/maturin 构建，由 shims/jellyfish.py 顶替）、cnocr/mxnet（被
# in-proc onnxruntime OCR 取代）、zerorpc/pyzmq（TCP 桥方案废弃）、av（编译死链且用不上）。
# uvicorn 装裸版不带 [standard]：standard extra 拉 uvloop/httptools 老钉版死链（m0 同款处理）。
# deploy.yaml 的 RequirementsFile 键永不执行（InstallDependencies:false 已锁），无需迁就其清单。
# native 包宽松不钉死（numpy 写 >=2 表意图）；pydantic 钉 <2 对齐 ALAS v1 API
# cached-property：ALAS config_updater.py / alas.py 顶层 import；老 uiautomator2 2.x 的
# 传递依赖，现代 3.x 不再传递，必须显式装（M1-d 真机 WebUI 实锤，静态全扫唯一缺口）
# imageio 钉 2.27.0 对齐上游 requirements.txt:38（纯 Python 无死链）：2.35+ 把 P 模式 GIF
# 统一解码成 RGB 3 通道，campaign 选关模板匹配时 cv2 通道断言直接崩（T2 真机崩溃根因）；
# 已实证 2.27.0 与 numpy 2.5 共存且 GIF 解码回 2D 调色板索引（.tmp/verify_t2_envfix.py）
pip_install \
  'numpy>=2' scipy pillow lxml opencv-python-headless onnxruntime \
  pywebio uvicorn fastapi aiofiles inflection pyyaml requests tqdm rich 'imageio==2.27.0' \
  'pydantic<2' adbutils uiautomator2 uiautomator2cache websockets pypresence onepush \
  cached-property

# ---------- 6. 应用本仓资产（宿主侧拷入 $ROOTFS_DIR/opt/alas） ----------
# m0 补丁集：module/ 与 assets/ 子树整层覆盖上游同名文件
cp -rf "$ASSETS/patches/module/." "$ROOTFS_DIR/opt/alas/module/"
cp -rf "$ASSETS/patches/assets/." "$ROOTFS_DIR/opt/alas/assets/"

# assets_fix.py 改的是 **ALAS 树内** 文件（argv[1]=ALAS 根目录）：按 Button 名就地重写
# module/*/assets.py 里的 cn area/color/button，非整文件覆盖（上游资产更新后可重放，见脚本 docstring）
python3 "$ASSETS/patches/assets_fix.py" "$ROOTFS_DIR/opt/alas"

# OCR rpc.py：in-proc onnxruntime 版（overlays，并行任务产物），替换掉 ALAS 上游同名文件
cp "$ASSETS/overlays/module/ocr/rpc.py" "$ROOTFS_DIR/opt/alas/module/ocr/rpc.py"

# jellyfish shim：现代 jellyfish（1.x）是 Rust/maturin 构建，目标环境装不了，未入依赖清单；
# 把纯 Python shim 放到 site-packages 顶替模块名（ALAS 只调 levenshtein_distance）。
# 模块路径在 chroot 内用 sysconfig 查实，不猜前缀
PY_PURELIB="$(chroot_run python3 -c 'import sysconfig; print(sysconfig.get_path("purelib"))')"
install -D -m 0644 "$ASSETS/shims/jellyfish.py" "$ROOTFS_DIR$PY_PURELIB/jellyfish.py"

# deploy.yaml：更新器七键全锁（AutoUpdate:false 是保住钉版 commit 的唯一闸门，详见文件头注释）
install -D -m 0644 "$ASSETS/seeds/deploy.yaml" "$ROOTFS_DIR/opt/alas/config/deploy.yaml"

# 实例配置生成器：运行时实例播种由阶段三调用（ALAS CWD=仓库根；脚本内 ALAS 根取
# ALASAOS_ALAS_ROOT 环境变量，调用方需 export ALASAOS_ALAS_ROOT=/opt/alas）
install -D -m 0644 "$ASSETS/seeds/seed_config.py" "$ROOTFS_DIR/opt/alas/seeds/seed_config.py"

# ALAS 热更新脚本：设备端唯一更新通道（内置更新器已被 AutoUpdate:false 锁死），
# 阶段三 App 侧 AlasUpdater 经 proot 拉起；协议见脚本头注释
install -D -m 0755 "$ASSETS/seeds/alasaos_update.sh" "$ROOTFS_DIR/opt/alas/seeds/alasaos_update.sh"

# args 现场再生器：args.json/argument.yaml 不补丁化，每次启动重跑 ALAS 生成链
# 并补回 alasaos 桥选项（活动列表永不冻结）；App 侧 ProotHost 经 proot 拉起
install -D -m 0755 "$ASSETS/seeds/regen_args.py" "$ROOTFS_DIR/opt/alas/seeds/regen_args.py"

# 环境自检修复：每次启动幂等跑（App 侧 ProotHost 经 proot 拉起）——把已部署 rootfs 的
# imageio 钉回上游 2.27.0，并 git 还原被旧构建补丁盖过的上游跟踪文件；协议见脚本头注释
install -D -m 0755 "$ASSETS/seeds/env_fix.sh" "$ROOTFS_DIR/opt/alas/seeds/env_fix.sh"

# PP-OCR 模型三件套 → /opt/alas/models/ocr/
# 注意：这是 v3 自定义路径（非 ALAS 上游约定）——in-proc 版 module/ocr/rpc.py 默认按
# ./models/ocr/（相对 ALAS 根）加载，ALASAOS_OCR_MODEL_DIR 可覆盖；两边约定必须保持一致
install -D -m 0644 "$ASSETS/models/ocr/det.onnx"  "$ROOTFS_DIR/opt/alas/models/ocr/det.onnx"
install -D -m 0644 "$ASSETS/models/ocr/rec.onnx"  "$ROOTFS_DIR/opt/alas/models/ocr/rec.onnx"
install -D -m 0644 "$ASSETS/models/ocr/keys.txt"  "$ROOTFS_DIR/opt/alas/models/ocr/keys.txt"

# azur_lane numpy 字体模型（上游 cnocr 权重的纯 numpy 移植）：rpc.py `_get_al_engine`
# 按 $ALASAOS_OCR_MODEL_DIR/azur_lane/（默认 ./models/ocr/azur_lane/，相对 ALAS 根）加载。
# 真机上这两份由 APK assets（alas/overlay→""）交付；rootfs 不铺则 CI 的 Spike F 门禁
# 恒走 PP-OCR 回落，numpy 引擎零验证（权重 +3.4MB 可接受）
install -D -m 0644 "$ASSETS/overlays/models/ocr/azur_lane/weights.npz" \
    "$ROOTFS_DIR/opt/alas/models/ocr/azur_lane/weights.npz"
install -D -m 0644 "$ASSETS/overlays/models/ocr/azur_lane/label_cn.txt" \
    "$ROOTFS_DIR/opt/alas/models/ocr/azur_lane/label_cn.txt"

# al_numpy.py 同理：overlay 应用面只 cp rpc.py（下文第 7 节），不铺它则 rpc.py 顶层
# `from module.ocr.al_numpy import AlNumpyOcr` 失败 → AlNumpyOcr=None → 同样恒回落
install -D -m 0644 "$ASSETS/overlays/module/ocr/al_numpy.py" \
    "$ROOTFS_DIR/opt/alas/module/ocr/al_numpy.py"

# ---------- 7. wrapper / runner / Spike F 门禁（并行任务产物，fail-fast 已在开头验过） ----------
# 门禁脚本也装进 /opt/alas：workflow 的 Spike F gate step 直接在 chroot 里跑它，
# 设备端日后也可用同一入口复跑 OCR 自检
cp "$ASSETS/overlays/wrapper.py" "$ASSETS/overlays/runner.py" \
   "$ASSETS/build/spike-f-ocr-gate.py" "$ROOTFS_DIR/opt/alas/"

# ---------- 8. import 硬门禁 + BUILD_MANIFEST（决策 #10：App 要可读） ----------
PY_VER="$(chroot_run python3 -c 'import platform; print(platform.python_version())')"
ORT_VER="$(chroot_run python3 -c 'import onnxruntime; print(onnxruntime.__version__)')"
CV_VER="$(chroot_run python3 -c 'import cv2; print(cv2.__version__)')"

# import 硬门禁（fail-fast）：m0 setup_env.sh 第 4 节同款校验并扩展 onnxruntime。
# 必须在 jellyfish shim 安装（第 6 步）之后跑——此时 jellyfish 已是 shim 文件；
# 任一 ImportError → ::error:: 并以退出码 1 中止构建（set -e 捕获）
chroot_run python3 - <<'PY'
try:
    import cv2, numpy, scipy, PIL, lxml.etree, yaml
    import pywebio, uvicorn, fastapi, pydantic, imageio, rich, requests, jellyfish
    import adbutils, uiautomator2, onnxruntime, cached_property
except ImportError as e:
    print(f'::error::import 硬门禁失败: {e}')
    raise SystemExit(1)
print('cv2', cv2.__version__, '| numpy', numpy.__version__, '| scipy', scipy.__version__, '| PIL', PIL.__version__)
print('pydantic', pydantic.VERSION, '| pywebio', pywebio.__version__, '| onnxruntime', onnxruntime.__version__)
print('jellyfish shim check:', jellyfish.levenshtein_distance('abc', 'abd') == 1)
print('ALL_IMPORTS_OK')
PY

# 优先用 GITHUB_SHA（checkout 的那个 commit）；本地兜底走 git——脚本已 sudo 提权为 root，
# 直接 git 会撞 "dubious ownership"（仓属 runner 用户），故带 -c safe.directory
REPO_COMMIT="${GITHUB_SHA:-$(git -c safe.directory='*' -C "$REPO_ROOT" rev-parse HEAD)}"
BUILD_TIME_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
DET_SHA="$(sha256sum "$ASSETS/models/ocr/det.onnx" | awk '{print $1}')"
REC_SHA="$(sha256sum "$ASSETS/models/ocr/rec.onnx" | awk '{print $1}')"
KEYS_SHA="$(sha256sum "$ASSETS/models/ocr/keys.txt" | awk '{print $1}')"

ROOTFS_VERSION="$ROOTFS_VERSION" BUILD_TIME_UTC="$BUILD_TIME_UTC" \
ALAS_REPO="$ALAS_REPO" PINNED_COMMIT="$PINNED_COMMIT" REPO_COMMIT="$REPO_COMMIT" \
DET_SHA="$DET_SHA" REC_SHA="$REC_SHA" KEYS_SHA="$KEYS_SHA" \
PY_VER="$PY_VER" ORT_VER="$ORT_VER" CV_VER="$CV_VER" \
python3 - <<'PY' > "$ROOTFS_DIR/opt/alas/BUILD_MANIFEST"
import json, os
e = os.environ
manifest = {
    "rootfs_version": e["ROOTFS_VERSION"],
    "build_time_utc": e["BUILD_TIME_UTC"],
    "alas_repo": e["ALAS_REPO"],
    "alas_commit": e["PINNED_COMMIT"],
    "patches_source": f"m0-archive/termux/patches @ repo commit {e['REPO_COMMIT']}",
    "ocr_models": {
        "det.onnx": e["DET_SHA"],
        "rec.onnx": e["REC_SHA"],
        "keys.txt": e["KEYS_SHA"],
    },
    "python_version": e["PY_VER"],
    "onnxruntime_version": e["ORT_VER"],
    "opencv_version": e["CV_VER"],
}
print(json.dumps(manifest, indent=2, ensure_ascii=False))
PY
cp "$ROOTFS_DIR/opt/alas/BUILD_MANIFEST" "$DIST_DIR/BUILD_MANIFEST"

# ---------- 9. 瘦身 + 打包 ----------
# 先显式卸载全部 bind mount（trap 只是兜底）：否则下面 find/rm 会爬进宿主 /proc /sys /dev，
# 打包也会把宿主文件系统打进 tar。卸载后再做一切 rootfs 内部清理
cleanup_mounts
for mp in dev dev/pts proc sys etc/resolv.conf; do
  if mountpoint -q "$ROOTFS_DIR/$mp"; then
    echo "::error::$ROOTFS_DIR/$mp 仍处于挂载状态，拒绝清理与打包（trap 已兜底，此处为显式防线）"
    exit 1
  fi
done

rm -rf "$ROOTFS_DIR/opt/alas/.git"
find "$ROOTFS_DIR" -type d -name __pycache__ -prune -exec rm -rf {} +
rm -rf "$ROOTFS_DIR/root/.cache" "$ROOTFS_DIR/var/lib/apt/lists"/*

OUT="$DIST_DIR/rootfs.tar.xz"
# --one-file-system 双保险：即使有残留挂载也不会把宿主文件系统打进包；
# XZ_OPT=-T0 多线程压缩（单线程 xz 压 ~600MB 要几分钟）
XZ_OPT=-T0 tar --one-file-system -C "$ROOTFS_DIR" -cJf "$OUT" .
SIZE="$(stat -c %s "$OUT")"
SHA="$(sha256sum "$OUT" | awk '{print $1}')"
log "rootfs.tar.xz: $SIZE bytes"
log "rootfs.tar.xz sha256: $SHA"
# 目标 ~250MB；超 400MB 报警（不 fail，留人审）
if (( SIZE > 400*1024*1024 )); then
  echo "::warning::rootfs.tar.xz 超 400MB（$SIZE bytes，目标 ~250MB），需要瘦身"
fi
log "完成：$OUT"
