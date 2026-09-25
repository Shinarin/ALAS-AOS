---
name: releasing-new-version
description: Use when 用户明确下达发版指令（"发版"、"打包新版 release"、"push 并发版"等），需要为 ALAS-AOS 产出新版本：写 CHANGELOG、打 tag、构建 release APK、创建 GitHub Release、收官账册（devlog/handoff）。模糊表述不算授权，须先确认。
---

# Releasing New Version（ALAS-AOS）

## Overview

版本号由 git tag 推导（定义在 `app/build-logic/.../gradle/GitVersion.kt`，`AndroidApplicationConventionPlugin.kt` 调用），**不许手改任何版本文件**。顺序承力：**tag 必须打在发版 commit 上、构建必须在 tag 之后**——tag 不在 HEAD 时 versionName 会带 `-alpha.N` 后缀（GitVersion.kt 的 distance 分支）。

授权口径：一句明确的「push 并发版 vX.Y.Z」覆盖整条流水线（含第 9 步收官 push）；中途范围变更（如临时加内容）须重新确认。

## 第 0 步 · Pre-flight（不满足就停下问用户，禁止硬闯）

```bash
git status --short                    # 有哪些待提交内容
git fetch origin && git log origin/main..HEAD --oneline   # 本地领先什么
git tag -l vX.Y.Z                     # 必须为空（tag 未被占用）
gh auth status                        # 登录态 + repo scope
date +%F                              # CHANGELOG 日期
```

- devlog 自上版以来**没有条目**且工作区无实质改动 = 没东西可发 → 问用户本版内容是什么，禁止发空版。
- `git add -A` 前先过一遍 status：发版 commit 只纳入本版内容 + 发版准备文件（CHANGELOG/devlog/handoff）；混入无关 untracked 文件先问用户。
- fetch/gh 联网失败 = 直连超时场景：先按 `pushing-to-github` 探测代理，`git -c http.proxy=$P -c https.proxy=$P fetch origin` 同样命令级注入。

## 流水线（按序执行）

1. **CHANGELOG**：`CHANGELOG.md` 顶部加 `## vX.Y.Z（日期）`；emoji 小节、面向用户通俗语言；素材 = devlog 自上版以来的全部条目**重写**（格式参考 v0.1.4 段）。
2. **README 核对**：grep 本版涉及的功能词；描述与现状不符才改，无相关内容直接过。
3. **发版 commit + tag**（commit 风格约定详见 `pushing-to-github`）：
   ```bash
   git add CHANGELOG.md devlog.md <本版改动路径>   # status 确认无杂项时才可 git add -A
   git commit -m "feat(scope): 摘要 + vX.Y.Z 发版准备" && git tag vX.Y.Z
   ```
   纯文档/无代码版本用 `docs:` 前缀（v0.1.2 先例 `4c4f50e`）。
4. **push main + tag**：**REQUIRED SUB-SKILL: `pushing-to-github`**（代理探测与命令级注入打法在里面，本 skill 不重复）。
5. **tag 上重建**（发版 commit 后工作区即 tag 内容，直接构建；`cmd //c` 是 Windows Git Bash 专用写法）：
   ```bash
   cd app && JAVA_HOME='D:\VSCodeCache\shizku-m\build-env\jdk-17.0.2' GRADLE_USER_HOME='D:\VSCodeCache\maa-alas\.tmp\gradle-home' cmd //c 'gradlew.bat assembleRelease --console=plain'
   ```
   成功标准：`BUILD SUCCESSFUL` + `R8 keeps verified`。
6. **badging 核验**（每条 Bash 调用是新 shell，路径用仓库根相对）：
   ```bash
   "/c/Users/da270/AppData/Local/Android/Sdk/build-tools/37.0.0/aapt.exe" dump badging app/app/build/outputs/apk/release/app-release.apk | head -3
   # 期望：package: name='io.github.shinarin.alasaos' versionName='X.Y.Z'（无 -alpha 后缀）、versionCode 较上版递增
   ```
7. **资产改名**：
   ```bash
   cp app/app/build/outputs/apk/release/app-release.apk .tmp/release/ALAS-AOS-vX.Y.Z-android-arm64.apk
   stat -c %s .tmp/release/ALAS-AOS-vX.Y.Z-android-arm64.apk
   ```
8. **GitHub Release 两段式**（327MB 走代理一把梭会在 PATCH 阶段 EOF，2026-09-21 实证；两段式让断连只废小请求）。`$P` = pushing-to-github 探测出的代理（勿写死端口）：
   ```bash
   HTTPS_PROXY=$P gh release create vX.Y.Z --repo Shinarin/ALAS-AOS --title "vX.Y.Z" --notes-file .tmp/release/notes-vX.Y.Z.md --verify-tag
   HTTPS_PROXY=$P gh release upload vX.Y.Z .tmp/release/ALAS-AOS-vX.Y.Z-android-arm64.apk --repo Shinarin/ALAS-AOS   # 重试遇残留资产加 --clobber
   HTTPS_PROXY=$P gh release view vX.Y.Z --repo Shinarin/ALAS-AOS --json assets --jq '.assets[0].size'   # 须 == 第 7 步 stat 字节数
   ```
   notes 结构：安装说明置顶（覆盖安装 or 迁移路径）→ 用户向小节（同 CHANGELOG）→ CHANGELOG 链接。
   **tag 已推但构建/badging 失败的回滚**：`git push origin :refs/tags/vX.Y.Z`（带代理参数）删远端 tag + `git tag -d vX.Y.Z` 删本地 → 修复 → 重打重推。
9. **收官账册**：devlog 顶部加发布条目（提交/badging/发布链接/网络坑）；新建 `handoff/YYYY-MM-DD-release-vXXX.md`（发版事实/本版内容/坑点/遗留）；`docs: vX.Y.Z 发布收官——...` commit + push（代理打法同第 4 步）。**没有收官 commit + push 不算发版完成。**

## Common Mistakes

| 错误 | 正解 |
|---|---|
| 跳过 pre-flight 直接开工 | 无内容可发 / tag 撞车 / auth 失效，全是先问了再做的坑 |
| 第 0 步说好只加本版内容，第 3 步裸 `git add -A` | 默认选择性 add；status 确认无杂项才许 `-A` |
| `gh release create` 连资产一把梭 | 两段式：先建空 release，再 `gh release upload` |
| 忘核验远端资产尺寸 | 第 8 步 `gh release view --json assets` 对比本地 stat |
| 手改 build.gradle 版本号 | 版本号来自 git tag，手改反而错 |
| tag 打完忘推 / 推错 commit | tag 独立 push；tag 必须在发版 commit 上，否则 versionName 带 -alpha.N |
| 收官文档只写不 push | 收官 commit 与 push 都做才算完 |
| CHANGELOG 照抄 devlog 内部细节 | 面向用户重写：通俗、去实现细节、可用 emoji |
