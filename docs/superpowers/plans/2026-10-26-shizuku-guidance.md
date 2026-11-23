# Shizuku 手动引导收敛 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 仅在 Shizuku 启动模式下显示手动恢复提示，未安装时一键用默认浏览器打开 shizuku-m 下载页，不增加自动拉起或自动重绑。

**Architecture:** 复用 `PermissionManager.readiness` 与现有弹窗，仅把状态判定抽为可测试的 suspend 函数。未安装档删除二级选源 UI，直接复用 `PermissionManager.openUrl`；特权连接层保持不变。

**Tech Stack:** Kotlin、Jetpack Compose、kotlinx.coroutines.test、JUnit4、Android Gradle Plugin。

**Spec:** `docs/superpowers/specs/2026-10-26-shizuku-guidance-design.md`

## Global Constraints

- 不自动打开 Shizuku、不自动启动服务、不新增 RemoteService 自动重绑。
- 不修改 `D:\VSCodeCache\shizku-m` 外部仓库，不修改 ALAS 上游文件。
- 不新增依赖，不执行 git commit、push 或 release。
- 临时产物仅使用项目 `.tmp/` 与既有 Gradle 输出目录。

## Review Focus

- Root 模式必须完全不进入 Shizuku 后续探测并返回 `Ready`，由 Task 1 的 Root 测试锁定。
- 已授权、跳过检查和 Sui 分支不得因重构回归，由 Task 1 分别锁定。
- 未安装时不得再出现二级选源，由 Task 2 的调用点检查与编译锁定。
- 下载只去 `https://github.com/Shinarin/shizuku-m/releases`，由 Task 2 的常量与死引用检查锁定。
- 浏览器或 Shizuku App 不存在时不得崩溃，继续复用现有 `runCatching` 出口；Task 2 核查该路径不改动。

---

### Task 1: 启动模式门控回归测试

**Files:**
- Create: `app/app/src/test/java/com/aliothmoon/maafw/privileged/ShizukuReadinessTest.kt`
- Modify: `app/app/src/main/java/com/aliothmoon/maafw/privileged/ShizukuReadiness.kt`
- Modify: `app/app/src/main/java/com/aliothmoon/maafw/privileged/PermissionManager.kt:244-261`

**Interfaces:**
- Produces: `internal suspend fun resolveShizukuReadinessStage(remoteState: RemoteAccessState, skipCheck: Boolean, isSui: Boolean, probeNotGranted: suspend () -> ShizukuReadinessStage): ShizukuReadinessStage`
- Consumes: 现有 `RemoteAccessState`、`ShizukuReadinessStage` 与 `PermissionManager.probeShizukuStage`。

- [x] **Step 1: 写失败测试**

用 `runTest` 覆盖：Root 返回 `Ready` 且 probe 未调用；Shizuku 未授权调用一次 probe 并返回 `NotRunning`；已授权、跳过检查、Sui 分别返回原状态且 probe 未调用。

- [x] **Step 2: 验证测试先失败**

Run: `cmd //c 'gradlew.bat :app:testDebugUnitTest --tests "com.aliothmoon.maafw.privileged.ShizukuReadinessTest" --console=plain'`（在 `app/` 下，使用既有 JDK 与 `.tmp/gradle-home`）
Expected: 因 `resolveShizukuReadinessStage` 尚不存在而编译失败。

- [x] **Step 3: 实现最小判定函数**

在 `ShizukuReadiness.kt` 按现有 `PermissionManager.resolveReadiness` 的分支顺序实现函数；`PermissionManager` 改为传入 `ShizukuManager.isSui` 和在 IO dispatcher 执行原 probe 的 suspend lambda，行为不变。

- [x] **Step 4: 验证测试转绿**

Run: 同 Step 2。
Expected: `BUILD SUCCESSFUL`，新测试 0 failures。

### Task 2: 简化提示与下载跳转

**Files:**
- Modify: `app/app/src/main/java/com/aliothmoon/maafw/ui/components/ShizukuReadinessDialog.kt`
- Modify: `app/app/src/main/java/com/aliothmoon/maafw/ui/AppRoot.kt:189-201`
- Modify: `app/app/src/main/java/com/aliothmoon/maafw/privileged/ShizukuReadiness.kt:30-34`
- Modify: `app/app/src/main/res/values/strings.xml:95-108`
- Modify: `app/app/src/main/res/values-en/strings.xml:95-108`
- Modify: `devlog.md`
- Create: `handoff/2026-10-26-shizuku-guidance-simplify.md`

**Interfaces:**
- Consumes: `SHIZUKU_M_RELEASES_URL`、`PermissionManager.openUrl(context, url)` 与 Task 1 的 readiness 判定。
- Produces: NotInstalled 确认回调 `onOpenUrl(SHIZUKU_M_RELEASES_URL)`；不再产生 `ShizukuDownloadDialog` 或 `SHIZUKU_OFFICIAL_RELEASES_URL`。

- [x] **Step 1: 删除二级选源并直连下载页**

NotInstalled 的 `onConfirm` 直接调用 `onOpenUrl(SHIZUKU_M_RELEASES_URL)`；删除私有下载弹窗及仅为它服务的 Compose imports、状态、官方 URL 常量和双语下载源字符串。保留重新检测次按钮与 NotRunning 的 `onOpenApp` 路径。

- [x] **Step 2: 更新文案、注释与账册**

中英文未安装正文改为“去下载会用默认浏览器打开 shizuku-m 下载页”；`AppRoot` 注释改为不再选源。devlog 记录用户要求、改动和验证；handoff 记录真机未连接的验收边界。

- [x] **Step 3: 检查死引用与改动范围**

用 Grep 确认 `ShizukuDownloadDialog`、`dialog_shizuku_download_`、`SHIZUKU_OFFICIAL_RELEASES_URL` 无残留；`git diff` 仅包含本设计、计划、上述实现与账册文件。

- [x] **Step 4: 完整本地验证**

Run: `cmd //c 'gradlew.bat testDebugUnitTest assembleDebug --console=plain'`（在 `app/` 下，环境同上）
Expected: `BUILD SUCCESSFUL`，全部单元测试 0 failures，双语资源编译通过。真机未连接时明确标记实际点击、默认浏览器落地与 Root 模式 UI 未做设备验收。
