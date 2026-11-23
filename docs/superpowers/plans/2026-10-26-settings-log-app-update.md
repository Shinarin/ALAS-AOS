# 设置页日志与 App 自更新 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 日志卡默认收起且导出顺序对调，并在关于卡实现检查、下载、校验和安装 App 更新。

**Architecture:** 日志只调整 UI 参数与顺序。更新分为 `AppUpdateSource`（Release 获取/下载）、`AppUpdateManager`（状态机）和 `AppInstallHandler`（权限、APK 校验、系统安装），通过 Koin 注入 SettingsViewModel；Android 细节不进入状态机测试。

**Tech Stack:** Kotlin、Jetpack Compose、kotlinx.coroutines、HttpURLConnection、org.json、Android PackageManager/FileProvider、JUnit4/MockK。

**Spec:** `docs/superpowers/specs/2026-10-26-settings-log-app-update-design.md`

## Global Constraints

- 仅手动检查/下载，不开屏自动检查、不自动安装。
- 不新增依赖，不改 ALAS 上游或外部 shizuku-m，不 commit/push/release。
- 只允许精确 ARM64 APK 资产；包名一致、versionCode 递增、签名一致才可安装。
- 临时产物与 fixture 仅放 `.tmp/`，GitHub 真机测试前提醒代理要求。

## Review Focus

- `/releases/latest` 无合适资产或 JSON 结构缺失时不得误选源码包，由 Task 2 parser 测试锁定。
- 相同版本、alpha 与稳定版比较不得误报更新，由 Task 2 版本测试锁定。
- 无未知来源权限时不得先下载，由 Task 3 权限状态测试锁定。
- 非 200、长度不符、包名/版本/签名不符不得打开安装器；Task 3 状态测试覆盖失败分支，真机 fixture 覆盖实际校验。
- 下载中重复点击不得并发下载，由 Task 3 状态测试锁定。

---

### Task 1: 日志卡顺序与默认收起

**Files:**
- Modify: `app/app/src/main/java/com/aliothmoon/maafw/ui/components/MaaComponents.kt:72-84`
- Modify: `app/app/src/main/java/com/aliothmoon/maafw/ui/settings/SettingsScreen.kt:168-203`

**Interfaces:**
- Produces: `MaaCard(..., collapsible: Boolean = false, initiallyExpanded: Boolean = true, ...)`；旧调用点不变。
- Consumes: 现有 `MaaNavigationRow` 与导出回调。

- [x] **Step 1: 增加初始状态参数**
`var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }`，不增加持久化。

- [x] **Step 2: 调整日志卡**
LogCard 传 `initiallyExpanded = false`；交换两个导出 `MaaNavigationRow` 的位置为 Launcher、ALAS，查看入口和清理开关不动。

- [x] **Step 3: 编译检查**
Run: `cmd //c 'gradlew.bat :app:compileDebugKotlin --console=plain'`（`app/` 下）
Expected: BUILD SUCCESSFUL，无新参数或顺序错误。

### Task 2: 更新数据、HTTP 源与检查状态

**Files:**
- Create: `app/app/src/main/java/com/aliothmoon/maafw/update/AppUpdateModels.kt`
- Create: `app/app/src/main/java/com/aliothmoon/maafw/update/AppUpdateSource.kt`
- Create: `app/app/src/main/java/com/aliothmoon/maafw/update/GithubAppUpdateSource.kt`
- Create: `app/app/src/main/java/com/aliothmoon/maafw/update/AppUpdateManager.kt`
- Create: `app/app/src/main/java/com/aliothmoon/maafw/update/AppInstallHandler.kt`
- Test: `app/app/src/test/java/com/aliothmoon/maafw/update/AppUpdateManagerTest.kt`
- Test: `app/app/src/test/java/com/aliothmoon/maafw/update/GithubAppUpdateSourceTest.kt`

**Interfaces:**
- Produces: `AppRelease(tagName, downloadUrl, size)`；`AppUpdateSource.fetchLatestRelease()/download(...)`；`AppUpdateManager.checkForUpdates()`；`AppUpdateState` 的 Idle/Checking/Available/UpToDate/Downloading/ReadyToInstall/PermissionRequired/Error。
- Produces: `AppInstallHandler.canRequestInstall()/requestInstallPermission()/validateAndInstall(file,currentVersionCode)`，Task 3 提供 Android 实现。
- Produces: `isNewerVersion(candidate,current)` 与 `parseGithubRelease(json)`。

- [x] **Step 1: 写失败测试**
覆盖：稳定版高于/等于/低于 alpha、alpha 数字比较；精确 ARM64 资产、无资产/缺字段；fake source 下 Available、UpToDate、Error。

- [x] **Step 2: 验证 RED**
Run: `cmd //c 'gradlew.bat :app:testDebugUnitTest --console=plain'`（`app/` 下）
Expected: 新类型和函数不存在而编译失败。

- [x] **Step 3: 实现模型、解析与检查状态机**
`parseGithubRelease` 只接受 `ALAS-AOS-<tag>-android-arm64.apk`；HTTP 非 200 抛错，GitHub 主机使用系统代理，localhost 直连。`checkForUpdates` 防重入并映射 Available/UpToDate/Error。

- [x] **Step 4: 验证 GREEN**
Run: 同 Step 2。
Expected: 全部 JVM 测试通过。

### Task 3: 下载、权限、安装与设置页集成

**Files:**
- Create: `app/app/src/main/java/com/aliothmoon/maafw/update/AndroidAppInstallHandler.kt`
- Modify: `app/app/src/main/java/com/aliothmoon/maafw/update/AppUpdateManager.kt`
- Modify: `app/app/src/main/java/com/aliothmoon/maafw/di/CoreModule.kt`
- Modify: `app/app/src/main/java/com/aliothmoon/maafw/di/ViewModelModule.kt`
- Modify: `app/app/src/main/java/com/aliothmoon/maafw/settings/SettingsViewModel.kt`
- Modify: `app/app/src/main/java/com/aliothmoon/maafw/settings/SettingsContracts.kt`
- Modify: `app/app/src/main/java/com/aliothmoon/maafw/ui/settings/SettingsScreen.kt`
- Modify: `app/app/src/main/java/com/aliothmoon/maafw/ui/AppRoot.kt`
- Modify: `app/app/src/main/AndroidManifest.xml`
- Modify: `app/app/src/main/res/values/strings.xml`
- Modify: `app/app/src/main/res/values-en/strings.xml`
- Modify: `README.md`
- Modify: `development.md`
- Modify: `devlog.md`
- Test: `app/app/src/test/java/com/aliothmoon/maafw/update/AppUpdateManagerTest.kt`
- Create: `handoff/2026-10-26-settings-log-app-update.md`

**Interfaces:**
- Consumes: Task 2 的 Source、Manager、InstallHandler 与状态。
- Produces: `SettingsIntent.CheckAppUpdate` / `DownloadAndInstall`；`SettingsViewModel.updateState`；`SettingsScreen(updateState=...)`；关于卡检查/下载/安装入口。

- [x] **Step 1: 先写下载与权限失败测试**
覆盖：无权限时 PermissionRequired 且不下载；下载成功进入 ReadyToInstall；重复点击不并发；校验/安装异常进入 Error；ReadyToInstall 再点击只重开安装器。

- [x] **Step 2: 验证 RED**
Run: `cmd //c 'gradlew.bat :app:testDebugUnitTest --console=plain'`（`app/` 下）
Expected: 下载安装路径尚未实现而失败。

- [x] **Step 3: 实现下载状态机与 Android 安装**
下载 `.part`、流式进度、长度校验、原子改名；实现包名/versionCode/签名校验、未知来源授权跳转、FileProvider 安装 Intent，并声明 `REQUEST_INSTALL_PACKAGES`。

- [x] **Step 4: 接入设置 UI 与文档**
SettingsViewModel 暴露状态，AboutCard 显示检查状态、可用版本、下载进度和安装/权限出口，补双语文案、README 与 development.md。

- [x] **Step 5: 完整本地验证**
Run: `cmd //c 'gradlew.bat testDebugUnitTest assembleDebug assembleRelease --console=plain'`（`app/` 下）
Expected: BUILD SUCCESSFUL、所有测试 0 failures、R8 keeps 5/5。

- [x] **Step 6: 真机验收并记账**
验证日志初始收起与导出顺序、生产 GitHub 检查，以及本地 HTTP fixture 的发现/下载/校验/安装闭环；清掉 fixture 和临时代码，记录未覆盖项到 devlog/handoff。
