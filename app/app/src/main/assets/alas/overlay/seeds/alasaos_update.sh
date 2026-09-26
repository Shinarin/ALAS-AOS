#!/bin/bash
# =============================================================================
# AlasAos · ALAS 热更新（rootfs 内由 App 侧 AlasUpdater 经 proot 拉起）
#
# 只拉 ALAS 源码，不动依赖（InstallDependencies:false 已锁死 pip）。
# deploy.yaml 里 ALAS 内置更新器已被 AutoUpdate:false 锁死，
# 本脚本是设备上唯一的 ALAS 更新通道。
#
# 双通道（2026-09-18 起，按优先级）：
#   1. CDN pack（seeds/cdn_update.py，复刻上游 git_over_cdn 协议）：
#      latest.json(3s) → 有更新才下 {latest}/{current}.zip 增量 pack（仅 ~400KB，
#      git 浅树的零头），落 .git/objects/pack + refs 后统一 reset --hard。
#      404/403（无此增量包）或 CDN 不可达 → 回落通道 2。
#   2. git://git.lyoko.io（9418 裸 TCP，运营商限速下 fetch 曾连续烧满 240s 超时，
#      只作兜底）：ls-remote(60s) 比对 → 需要才 fetch --depth(240s)。
#
# 失败退避：两通道都失败 → 记当天日期到 $FAIL_FILE，当天后续启动直接跳过
# （弱网/镜像抽风时开机不再每天烧 N 次 4 分钟）；次日自动恢复检查。
#
# 补丁重放不在本脚本职责内：git reset 会把上游跟踪文件打回原版，
# App 侧在收到 UPDATED 后重放 assets/alas（patches/module、patches/assets、
# overlays/rpc.py）并重跑 assets_fix.py。
#
# 与 App 的协议：最后一行打印三态之一，UPDATED/UNCHANGED 退出码 0，FAILED 退出码 1。
# FAILED（断网/超时/镜像不可达）由 App 降级为"跳过更新"，不阻塞启动。
#
# 环境变量（均可 export 覆盖，冒号后为默认值）：
#   ALASAOS_ALAS_ROOT      /opt/alas
#   ALASAOS_UPDATE_REPO    git://git.lyoko.io/AzurLaneAutoScript
#   ALASAOS_UPDATE_BRANCH  master
#   ALASAOS_UPDATE_DEPTH   50
#   ALASAOS_UPDATE_TIMEOUT 240（秒；首次 fetch 需整棵浅树，弱网可调大）
#   ALASAOS_UPDATE_NO_CDN  置非空则跳过 CDN 通道（排障用；GitHub 镜像档恒置）
#   ALASAOS_UPDATE_FORCE_FULL 置非空则跳过 CDN 与增量逻辑，1:1 走上游 git_repository_init
#                             序列全量重同步（镜像档切换后的首跑，由 App 侧注入）
#   ALASAOS_UPDATE_PROXY   非空则 export 为 http_proxy/https_proxy 全程生效（GitHub 档
#                             下 App 侧注入系统 HTTP 代理；VPN fake-ip 劫持下直连 TCP 被秒拒）
# =============================================================================
set -uo pipefail

ALAS_DIR="${ALASAOS_ALAS_ROOT:-/opt/alas}"
REPO="${ALASAOS_UPDATE_REPO:-git://git.lyoko.io/AzurLaneAutoScript}"
BRANCH="${ALASAOS_UPDATE_BRANCH:-master}"
DEPTH="${ALASAOS_UPDATE_DEPTH:-50}"
TIMEOUT="${ALASAOS_UPDATE_TIMEOUT:-240}"
STATE_FILE="$ALAS_DIR/.alasaos_alas_commit"
FAIL_FILE="$ALAS_DIR/.alasaos_update_fail_date"

# 系统 HTTP 代理（GitHub 档下 App 侧注入；VPN fake-ip 劫持下直连 TCP 被秒拒，
# git/curl 均认 http_proxy/https_proxy，全程生效——不止 force-full）
if [[ -n "${ALASAOS_UPDATE_PROXY:-}" ]]; then
  export http_proxy="$ALASAOS_UPDATE_PROXY" https_proxy="$ALASAOS_UPDATE_PROXY"
  echo "using proxy $ALASAOS_UPDATE_PROXY"
fi

# 进度旁路：git --progress 的 \r 流与 cdn_update.py 的分块下载都写这个文件，
# App 侧每秒轮询最后一个段落喂给启动清单卡片的热更新行（detail 小字）
PROGRESS_FILE="$ALAS_DIR/.alasaos_update_progress"
export ALASAOS_UPDATE_PROGRESS="$PROGRESS_FILE"
: > "$PROGRESS_FILE"

# fetch 失败时把进度文件尾巴（含 fatal 行）倒回 stdout 留证
dump_progress_tail() { tr '\r' '\n' < "$PROGRESS_FILE" 2>/dev/null | grep -v '^$' | tail -3; }

# 终态失败才记退避：通道内回落不算失败
fail() { date +%F > "$FAIL_FILE" 2>/dev/null; echo "FAILED $1"; exit 1; }

# 1:1 复刻上游 deploy/git.py 的 git_repository_init（ssl 段略：sslVerify 默认 true 无需动；
# fetch 改为 --depth 浅拉：全量历史数百 MB，手机/代理路径吃不消，且后续通道全是浅增量）。
# 供 FORCE_FULL 全量重同步用，步间任一失败即 return 1
git_repository_init() {
  git init -q . || {
    rm -f .git/config .git/index .git/HEAD
    git init -q . || return 1
  }
  git remote set-url origin "$REPO" || git remote add origin "$REPO" || return 1
  # 必须带 timeout：单次慢 fetch 不吃超时会把 App 侧总预算烧穿，3 次重试形同虚设
  # --progress：非 tty 也输出 \r 分隔的进度流，落 PROGRESS_FILE 供 App 轮询
  timeout "$TIMEOUT" git fetch --progress --depth "$DEPTH" origin "$BRANCH" 2>"$PROGRESS_FILE" || { dump_progress_tail; return 1; }
  rm -f .git/index.lock .git/HEAD.lock .git/refs/heads/master.lock
  git reset --hard "origin/$BRANCH" || return 1
  git pull --ff-only origin "$BRANCH" || return 1
  git --no-pager log --no-merges -1 || return 1
}

cd "$ALAS_DIR" || fail "cd $ALAS_DIR"

# 当前 commit：优先 state 文件（上次更新写入），否则 BUILD_MANIFEST 的烘焙钉版
current=""
if [[ -f "$STATE_FILE" ]]; then
  current="$(cat "$STATE_FILE")"
elif [[ -f BUILD_MANIFEST ]]; then
  current="$(grep -o '"alas_commit": *"[0-9a-f]\{40\}"' BUILD_MANIFEST | grep -o '[0-9a-f]\{40\}' | head -1)"
fi

# ---------- 强制全量重同步（镜像档切换后由 App 侧注入 FORCE_FULL） ----------
# 必须排在失败退避之前：退避的 UNCHANGED verdict 会让 App 误把脏档标成已同步。
# 两档分流（2026-09-26 真机实证修订）：
#   GitHub 档（NO_CDN 恒置）：旧浅树 graft 会让 fetch 后的 reset 报 "unable to read
#     tree"，必须先 ls-remote 探活（失败 fast-fail 保 .git）再推倒 .git 重来，
#     走 git_repository_init 浅拉序列，3×5s 重试。
#   CN 档：lyoko 与 GitHub 是同一棵对象树，CDN 增量包可直接吃 GitHub 档拉来的对象，
#     推 .git 反而自毁快照（空树 CDN 无法 bootstrap，只能走随时可能被运营商限速的
#     git://）。故 CN force-full 不推 .git，跳过退避后落入正常双通道流程。
if [[ -n "${ALASAOS_UPDATE_FORCE_FULL:-}" && -n "${ALASAOS_UPDATE_NO_CDN:-}" ]]; then
  echo "force-full: mirror switched, full re-sync from $REPO"
  # 探活：代理缺席/网络不可达时保 .git 不推（CN 对象还要留着给 CDN 增量当底）
  remote_head="$(timeout 60 git ls-remote "$REPO" "$BRANCH" 2>/dev/null | head -1 | cut -f1)"
  [[ -z "$remote_head" ]] && fail "force-full probe ls-remote"
  echo "force-full: probe ok, remote $BRANCH = $remote_head"
  rm -rf .git
  # 取证（github 档排障留下）：解析结果 / 真实连通 IP / DNS 与 connect 分段耗时
  repo_host="$(echo "$REPO" | sed -E 's#^[a-z]+://([^/]+).*#\1#')"
  echo "diag| resolv=$(tr '\n' ';' </etc/resolv.conf 2>&1)"
  echo "diag| getent4=$(getent ahostsv4 "$repo_host" 2>&1 | head -2 | tr '\n' ';')"
  echo "diag| getent6=$(getent ahostsv6 "$repo_host" 2>&1 | head -1 | tr '\n' ';')"
  echo "diag| curl4=$(curl -4 -s -o /dev/null -m 8 -w 'http=%{http_code} ip=%{remote_ip} dns=%{time_namelookup}s conn=%{time_connect}s' "https://$repo_host" 2>&1)"
  for attempt in 1 2 3; do
    # 上次被杀的 fetch/reset 可能留锁（每轮重试前再扫一遍）
    find .git -name '*.lock' -delete 2>/dev/null
    if git_repository_init; then
      new="$(git rev-parse HEAD)"
      echo "$new" > "$STATE_FILE"
      rm -f "$FAIL_FILE"
      echo "UPDATED $new (force-full)"
      exit 0
    fi
    if [[ $attempt -lt 3 ]]; then
      echo "force-full: attempt $attempt failed, retry in 5s"
      sleep 5
    fi
  done
  fail "force-full"
fi

# 失败退避：今天已败过一次就不再烧超时（次日自动恢复）；force-full（CN 档）不吃退避
today="$(date +%F)"
if [[ -z "${ALASAOS_UPDATE_FORCE_FULL:-}" && -f "$FAIL_FILE" && "$(cat "$FAIL_FILE" 2>/dev/null)" == "$today" ]]; then
  echo "UNCHANGED backoff-until-tomorrow current=${current:-unknown}"
  exit 0
fi

if [[ ! -d .git ]]; then
  git init -q . || fail "git init"
  git remote add origin "$REPO" || fail "git remote add"
fi

# 上次被杀的 fetch/reset 可能留锁（App 侧启动清理也会扫一遍，这里双保险）
find .git -name '*.lock' -delete 2>/dev/null

# ---------- 通道 1：CDN pack ----------
if [[ -z "${ALASAOS_UPDATE_NO_CDN:-}" ]]; then
  if [[ -n "$current" ]] && ! git cat-file -e "$current^{commit}" 2>/dev/null; then
    # .git 被 force-full 推倒重建过/对象不全：CDN 增量包无基可打，直走 git:// 重建
    echo "  cdn| 本地缺 current 对象（${current:-none} 不在库），跳过 CDN"
  else
  cdn_out="$(python3 seeds/cdn_update.py "$ALAS_DIR" "$current" 2>&1)"; cdn_rc=$?
  echo "$cdn_out" | sed 's/^/  /'
  cdn_last="$(echo "$cdn_out" | tail -1)"
  if [[ $cdn_rc -eq 0 && "$cdn_last" == "UPTODATE" ]]; then
    echo "$current" > "$STATE_FILE"
    echo "UNCHANGED $current (cdn)"
    exit 0
  elif [[ $cdn_rc -eq 0 && "$cdn_last" == PACK_READY\ * ]]; then
    new="${cdn_last#PACK_READY }"
    # reset 失败（对象不全）不终局，落回 git:// 通道重建
    if git reset --hard origin/"$BRANCH"; then
      echo "$new" > "$STATE_FILE"
      rm -f "$FAIL_FILE"
      echo "UPDATED $new (cdn)"
      exit 0
    fi
    echo "  cdn| reset --hard $new 失败（对象不全），回落 git://"
  else
    echo "  cdn| 通道不可用（$cdn_last），回落 git://"
  fi
  fi
fi

# ---------- 通道 2：git:// 兜底 ----------
# 快进路径：ls-remote 直取远端 HEAD（无需本地仓库对象，秒级），
# 与当前一致就连 fetch 都免了——已是最新的常态下热更新必须零下载
remote_head="$(timeout 60 git ls-remote origin "$BRANCH" | head -1 | cut -f1)"
[[ -z "$remote_head" ]] && fail "ls-remote"
if [[ "$remote_head" == "$current" ]]; then
  echo "$remote_head" > "$STATE_FILE"
  echo "UNCHANGED $remote_head"
  exit 0
fi

# 首次更新本地没有对象：只取单提交树先把更新跑完（后续 fetch 会按需加深历史）
if [[ ! -f .git/FETCH_HEAD && ! -f .git/shallow ]]; then
  DEPTH=1
fi

timeout "$TIMEOUT" git fetch --progress --depth "$DEPTH" origin "$BRANCH" 2>"$PROGRESS_FILE" || { dump_progress_tail; fail "fetch"; }
new="$(git rev-parse FETCH_HEAD)" || fail "rev-parse FETCH_HEAD"

if [[ "$new" == "$current" ]]; then
  echo "$new" > "$STATE_FILE"
  echo "UNCHANGED $new"
  exit 0
fi

git reset --hard FETCH_HEAD || fail "reset --hard $new"
echo "$new" > "$STATE_FILE"
rm -f "$FAIL_FILE"
echo "UPDATED $new"
exit 0
