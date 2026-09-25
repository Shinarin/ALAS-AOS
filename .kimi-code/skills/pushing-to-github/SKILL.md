---
name: pushing-to-github
description: Use when 需要把本仓库的 commit 或 tag 推送到 GitHub（git push、push tag），或执行任何访问 github.com 的 git/gh 联网操作；尤其是直连超时（Connection timed out、curl 返回 000、gh 请求 EOF/卡死）需要切换本机代理时。仅在用户明确授权 push 后使用。
---

# Pushing to GitHub（ALAS-AOS）

## Overview

本机直连 github.com 经常超时；可用代理在本机 Clash 系端口（2026-09-21 实测 `127.0.0.1:7897` 可用）。核心原则：**代理只用命令级注入，绝不写进 git 配置**（全局/仓库级都不行——会污染用户环境，属红线级副作用）。

## 红线

- push 必须用户**逐次明确授权**（AGENTS.md 第六节）；「差不多了」「可以了」等模糊表述不算，须先确认。
- 发版流水线（`releasing-new-version`）内的 push 按该 skill 的授权口径执行，不重复确认。

## Commit 约定（git log 实证）

- `feat(scope): 中文摘要` / `fix(scope): ...` / `docs: ...`
- 发版 commit 末尾带「+ vX.Y.Z 发版准备」；发版后另有 `docs: vX.Y.Z 发布收官——...` 入账 commit。

## 网络探测（直连失败时）

```bash
# 直连探活：000 = 不通
curl -sI --max-time 15 https://github.com -o /dev/null -w "%{http_code}\n"
# 扫常用本机代理端口，返回 200 的即可用
for p in 7897 7890 10809 10808 1080 2080 8888; do
  code=$(curl -sI --max-time 6 -x http://127.0.0.1:$p https://github.com -o /dev/null -w "%{http_code}" 2>/dev/null)
  [ "$code" != "000" ] && echo "proxy port: $p -> $code"
done
```

## 联网命令（代理命令级注入）

push、fetch、pull 等一切联网 git 命令同一打法：

```bash
P=http://127.0.0.1:7897   # 换成探测到的实际端口
git -c http.proxy=$P -c https.proxy=$P fetch origin
git -c http.proxy=$P -c https.proxy=$P push origin main
git -c http.proxy=$P -c https.proxy=$P push origin vX.Y.Z   # tag 要单独 push
```

gh CLI 不吃 `git -c`，走环境变量：

```bash
HTTPS_PROXY=$P gh <args...>
```

## 验收

- 输出须同时看到 `main -> main`（或对应分支）与 `* [new tag] vX.Y.Z -> vX.Y.Z` 两行。
- `git fetch origin`（带代理）后 `git log origin/main..HEAD` 为空才算推完；刻意不提交的 untracked 文件（如暂存的本地资料）不影响本判定。

## Common Mistakes

| 错误 | 正解 |
|---|---|
| `git config http.proxy ...`（含 --global） | 只许 `git -c http.proxy=... -c https.proxy=...` 命令级注入 |
| 直连超时后无脑原样重试 | 先跑端口扫描，换代理再重试 |
| 只给 push 配代理，fetch 卡死 | fetch/pull 等联网命令同打法（见上） |
| 忘推 tag（只推了 main） | tag 独立 push，release 流程里两步缺一不可 |
| 代理端口记死 7897 | 每次先探测，端口随代理软件变 |
