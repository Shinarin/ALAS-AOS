# Development

## 当前状态

**ALAS-AOS**（ALAS 的 AndroidOS 版，2026-09-20 由 MaaAL 更名）：仓库 [github.com/Shinarin/ALAS-AOS](https://github.com/Shinarin/ALAS-AOS)，包名 `io.github.shinarin.alasaos`，最新发布 **v0.1.5**；本地 main 已领先 13 个 commit（2026-09-25 大扫除，**未 push 未发版**，等用户授权）。

已交付功能：三 tab（挂机=游戏画面+操作面板 / ALAS WebUI / 设置）、悬浮窗控制面板（调度器状态 + 开始/停止挂机 + 半透明日志板，唯一控制面）、半自动点击与活动剧情（工具类，不入挂机队列）、开屏热更新（CDN pack 通道，git:// 兜底）、i18n（跟随系统/简体中文/English，切换会重建项目）、主题（跟随系统/浅色/深色 + 默认/Semi Design）、日志区（启动器/ALAS 日志查看、两类导出、冷启自动清理）、启动模式（Shizuku / Root）。

路线图（`docs/roadmap-v3.md`，开发宪法）：阶段一~四已交付；阶段五剩余 = 多 ROM 矩阵扩列（`docs/rom-matrix.md`）、2~3h 长稳挂机实测、shizuku-m 产品化说明。2026-09-25 大扫除（三模型交叉审计：-76,679 行死代码 + 功耗四件套 + App 侧首个 CI + R1–R8 收口）的共识与后续路线图见 `docs/cleanup-consensus-2026-09-25.md`。

- 任何不清楚之处：先读本文件与 roadmap，再读 `handoff/` 最新文件（按文件名日期排序），坑点查 `debug.md`。

## 仓库结构（现状）

- `app/` — Android App 本体（Kotlin + Compose，AGPL-3.0）。`app/src/main/java/com/aliothmoon/maafw/` 包职责：
  - `bridge/` TCP 22300 桥 Kotlin 端（五端点：ping/screencap/click/swipe/shell）
  - `config/` 用户配置 DataStore 存储（**历史教训：此包曾因 .gitignore 裸模式 `config/` 整包未入库，见 debug.md 2026-09-25**）
  - `di/` / `domain/` / `i18n/` / `theme/` / `settings/` 常规分层
  - `log/` FileLogTree（每批 drain 后即 flush）+ CrashHandler
  - `overlay/` AlasOverlay：APK assets 幂等铺到 /opt/alas（升级路径保险）
  - `privileged/` Shizuku 特权链；`remote/` AIDL RemoteService；`root/` Root 启动模式壳
  - `proot/` ProotHost 会话宿主、AlasRunController（wrapper 4s 轮询，空闲+后台退避 30s，失败日志状态沿节流）、AlasUpdater 热更新
  - `provision/` 首启 rootfs 流式解压（纯 Java tar/xz；busybox tar 解 ubuntu 硬链接必炸，不用）
  - `service/` 保活 FGS + AppForegroundTracker（ProcessLifecycleOwner）
  - `third/` 隐藏 API 反射壳（整包 R8 keep）；`ui/` 三 tab + 悬浮窗 + `alas/AlasWebViewHolder`（退后台暂停，paused 标志位防重建继承全局暂停）
  - `MaaFwApp.kt` / `MainActivity.kt` / `MaaDispatchers.kt`
  - `src/main/prootLibs/arm64-v8a/` proot 九件套（Spike A 钉版入库）
  - `src/main/assets/alas/` overlay 双源之一（wrapper/runner/seeds/patches/azur_lane OCR 模型），与 `rootfs/` 侧同内容，CI diff 门禁守着
  - `src/main/assets/rootfs/`：`BUILD_MANIFEST` 入库；`rootfs.tar.xz` gitignore，构建前手动放（来源=GHA rootfs.yml artifact）
  - `proguard-rules.pro`：每条 keep 注明调用方；`verifyReleaseR8Keeps` 任务随 assembleRelease 跑
- `rootfs/` — rootfs 侧资产：`build/build-rootfs.sh`（GHA ARM64）、`patches/`（含 `module/device/method/alasaos.py` 桥客户端）、`overlays/`（wrapper.py / runner.py / rpc.py / models）、`seeds/`（deploy.yaml 七锁 / seed_config.py / alasaos_update.sh）
- `.github/workflows/` — `app.yml`（App CI：testDebugUnitTest → assembleDebug → overlay/patches/seeds 双源 diff → i18n 校验；`fetch-depth: 0` 因 GitVersion 靠 git describe）、`rootfs.yml`（rootfs 构建，手动触发，`ALAS_REF` 默认 master 浮动，manifest 记录解析后 commit）
- `docs/` — `roadmap-v3.md`、`cleanup-consensus-2026-09-25.md`、`stage2-maafwapp-inventory.md`、`spike-d-wrapper-surface.md`、`logging-dev.md`、`rom-matrix.md`
- `spike/` — 阶段〇存档（`a-proot-exec/` Spike A/C；`e-adb-virtual-display/` Spike E/B′）
- `m0-archive/`（gitignore，本地只读）— m0 全部成果归档；MaaFwApp fork 基线在 `m0-archive/vendor/MaaFwApp` @ b2b0f54
- `keystore/`（gitignore）— 发版签名；`.tmp/`（gitignore）— gradle-home 构建缓存、实验物、回归截图
- 账册（根目录）：`devlog.md`（倒序流水，按版本号分段）、`debug.md`（坑与解法）、`development.md`（本文件）、`handoff/`（跨对话接力，取最新）、`CHANGELOG.md`（发版用户向）、`README.md`

## 技术栈（建成事实）

- **App**：Kotlin + Jetpack Compose（Material3 / Semi Design 双主题）、DataStore + kotlinx.serialization（schemaVersion 信封，损坏走 ReplaceFileCorruptionHandler）、Timber、R8 full-mode。
- **特权链**：shizuku-m（官方 Shizuku fork，端口重挂载修改，支持离线自连，用户自装不内置）→ RemoteService（AIDL，app_process 特权进程）→ BridgeServer（TCP 22300）→ VirtualDisplay 1280x720（禁 FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS，见 debug.md 手势劫持事件）。
- **客环境**：proot + Ubuntu 24.04 ARM64 + Python 3.12 + ALAS 官方 master（BUILD_MANIFEST 钉 commit）+ m0 补丁集；overlay 机制每次启动幂等铺 /opt/alas，免重烘 rootfs。
- **OCR**：通用 PP-OCR（in-proc onnxruntime）+ azur_lane 专用 numpy 引擎（`al_numpy.py` 手写 mxnet cuDNN 变体前向 + `weights.npz`/`label_cn.txt`，双源=rootfs 镜像 + APK overlay）。
- **控制面**：wrapper.py 薄 HTTP `127.0.0.1:22400`（GET `/status`、`/logs?tail=N`；POST `/start?config=N`、`/stop`、`/tool/start?name=daemon|event_story&config=N`、`/tool/stop`）；`/status` 的日志字段是 `log_size`（O(1)，F1 优化）。
- **版本号**：build-logic `GitVersion.kt`——`git describe` 出 versionName、提交计数出 versionCode，**无 git checkout 构建直接失败**（CI 必须 fetch-depth: 0）。

## 运行与构建

### 主 App（`app/`）

```bash
export JAVA_HOME='D:\VSCodeCache\shizku-m\build-env\jdk-17.0.2'
export GRADLE_USER_HOME='D:\VSCodeCache\maa-alas\.tmp\gradle-home'
cd app
cmd //c 'gradlew.bat assembleRelease --console=plain'   # 含 verifyReleaseR8Keeps
cmd //c 'gradlew.bat testDebugUnitTest --console=plain' # 20 tests（src/test 6 文件）
# APK → app/app/build/outputs/apk/release/app-release.apk
```

- 构建前确认 `app/app/src/main/assets/rootfs/rootfs.tar.xz` 已就位（GHA rootfs.yml artifact 手动放入，gitignore 不入库）。
- SDK 由 `app/local.properties`（gitignored）指向 `C:\Users\da270\AppData\Local\Android\Sdk`（cmake 3.22.1 + ndk 28.2）。
- dl.google.com 间歇握手断 → settings 已加 Aliyun 镜像（官方源兜底）；详见 debug.md 2026-09-16 条目。
- **本地跑 Python 一律用 `python`**：Git Bash 的 `python3` 是 WindowsApps 占位 stub，静默 exit 49（debug.md 2026-09-25）。

### rootfs（`rootfs/`）

- GHA `rootfs.yml` 手动触发；产物 `rootfs.tar.xz` + BUILD_MANIFEST。下次运行会首验 G2 新增断言（azur_lane numpy 引擎 `proxy._al is not None`），失败第一嫌疑是 chroot 内 cv2/numpy 环境。

### 真机调试闭环

- 设备 `AVAY025422002864`；adb shell 命令前必须 `export MSYS_NO_PATHCONV=1`；Windows 侧 adb/python 只吃 Windows 路径。
- 装机：`adb -s AVAY025422002864 install -r app/app/build/outputs/apk/release/app-release.apk`（覆盖安装会杀特权进程，Shizuku-m 需已运行或事后点「离线自连」，桥 22300 refused 时先查它，见 debug.md 2026-09-25）。
- wrapper 取证：`adb forward tcp:22400 tcp:22400` + `curl 127.0.0.1:22400/status`、`/logs?tail=N`。
- 纪律红线：**禁止私自锁屏/息屏测验**；虚拟屏实验前后必查手势窗口归属（详见 AGENTS.md 工程约定）。

### Spike 工程（阶段〇，存档）

两条可复现流程都在 `spike/a-proot-exec/`：

```bash
export JAVA_HOME=/d/VSCodeCache/shizku-m/build-env/jdk-21.0.2          # 便携工具链（只读）
export GRADLE_USER_HOME="D:/VSCodeCache/maa-alas/.tmp/spike-a/gradle-home"
cd spike/a-proot-exec
./gradlew --no-daemon -Pspike.targetSdk=35 :app:assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk dist/spikea-phantom-target35-debug.apk

bash run-device-ladder.sh AVAY025422002864     # Spike A：exec 阶梯（自动装/跑/收日志）
bash run-phantom-ab.sh A 600                   # Spike C：幻影查杀 A/B 轮（A|B|A2|B1|B2|L|final）
```
