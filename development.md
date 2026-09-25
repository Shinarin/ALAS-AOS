# Development

## 当前阶段

**阶段四应用内侧全落地**：M4-a ✅（悬浮窗直连 wrapper 薄 HTTP：调度器状态行 + 开始/停止挂机 + 半透明日志板；双头管理定案=悬浮窗唯一控制面）→ M4-b ✅（官方版 Shizuku 冲突引导：flavor 检测 + OfficialConflict 档 + 去卸载闭环；冲突分支真机未演留阶段五）→ M4-c ✅（开屏 `pageReady` 载入层淡出不再闪错误脸 + 面板分工 caption）→ M4-d ✅（M2-b 遗留②③④⑤核销：死 pref×7、分辨率安慰剂连根拔、runner/ 包删、okhttp/tracing/baselineprofile/toml 死账清）。**阶段四 DoD 只剩「开始挂机」端到端演示（真拉起 ALAS 操作游戏，必须用户在场）**，演示前置体检全绿（游戏在装/guest 配置一致/VD+桥+wrapper 通），过后进阶段五（多 ROM/长稳/Release/README）。**阶段五已开锣**：-3 断网容灾 ✅（审计 + PC 七场景 + 真机三态实证）、-4 桥截图定档 ✅（screencap p50=30ms，对照 >1s 不可用线富余 33 倍）；剩 -1 多 ROM 矩阵、-2 长稳挂机（依赖演示）、-5 shizuku-m 产品化、-6 Release/三仓库公开 + README。**阶段三已收官**（M3-a/b/c 全 ✅，重启恢复机制+文案落地、真机重启验证待用户授权）。阶段一 M1 已交付：rootfs 构建链 GHA 四连迭代至绿，v4 artifact 为交付基准；M1-d 真机复验 WebUI/MANIFEST 已过，**油数验收改走生产链（待用户把游戏点到出击菜单页）**。

- 开发宪法：`docs/roadmap-v3.md`（13 项决策、阶段〇–五、风险登记）。
- 阶段二工作底稿：`docs/stage2-maafwapp-inventory.md`（减法三栏清单 / 新桥设计 / VD flag 核查）。
- 任何不清楚之处：先读 roadmap，再读 `handoff/` 目录下最新文件（按文件名日期排序取最新）。

## 仓库结构（现状）

- `app/` — 阶段二/三主战场：MaaFwApp fork 复活副本（b2b0f54 + m0 WebView 6 处改动固化 + 构建修复）。阶段三新增：
  - `app/src/main/java/.../provision/`（首启 rootfs 解压，M3-a）与 `.../proot/`（ProotHost 会话宿主 / AlasOverlay 资产覆盖 / AlasUpdater 热更新，M3-b）。
  - `app/src/main/prootLibs/arm64-v8a/`（proot 九件套，Spike A 钉版入库）+ `app/src/main/assets/alas/`（wrapper/runner/seed/alasaos_update.sh/rpc.py + patches 全量，运行时幂等铺 /opt/alas）。
  - `app/src/main/assets/rootfs/`（rootfs.tar.xz 随包，gitignore 不入库；BUILD_MANIFEST 入库）。
- `rootfs/` — 阶段一资产：`build/build-rootfs.sh`（GHA ARM64 构建脚本）、`patches/`（ALAS 补丁集，含 `module/device/method/alasaos.py` 桥客户端）、`overlays/`（wrapper.py 监管 WebUI 版 / runner.py / rpc.py）、`seeds/`（deploy.yaml 七锁 / seed_config.py / alasaos_update.sh 热更新脚本）。
- `.github/workflows/` — rootfs 构建 workflow（手动触发；`ALAS_REF` 默认 master 浮动，manifest 记录解析后 commit）。
- `docs/` — `roadmap-v3.md`、`stage2-maafwapp-inventory.md`、`spike-d-wrapper-surface.md`。
- `spike/` — 阶段〇交付：`a-proot-exec/`（Spike A/C 工程+报告）、`e-adb-virtual-display/`（Spike E/B′）。
- `m0-archive/`（gitignore，本地只读）— m0 全部成果归档：MaaFwApp fork、termux 补丁/种子、桥代理、OCR 模型、m0 devlog。
- 账册（根目录）：`devlog.md`（倒序流水）、`debug.md`（坑与解法）、`development.md`（本文件）、`handoff/`（跨对话接力，取最新）。
- `.tmp/`（gitignore）— 构建缓存（`gradle-home`）、实验物、rootfs artifact、ALAS 部分克隆。

## 技术栈（规划，源自 v3）

- **Android App**：MaaFwApp fork 减法整理（Kotlin，Gradle，AGPL-3.0）——保留特权进程（虚拟屏+截屏注入）、TCP 22300 桥（五端点，Kotlin 重写）、WebView 容器、Shizuku 辅助。
- **rootfs**：Ubuntu 24.04 ARM64 + Python 3.12 + opencv-headless + onnxruntime + ALAS 官方 master（GitHub，BUILD_MANIFEST 钉 commit）+ m0 补丁集 + PP-OCR 模型——GitHub Actions ARM64 runner 构建。
- **提权**：shizuku-m（官方 v13.6.0 fork，用户自装，不内置）。
- **控制面/OCR**：桥代理五端点（ping/screencap/click/swipe/shell）；in-proc PP-OCR + rpc.py shim。

## 运行与构建

### 主 App（`app/`，阶段二起）

```bash
export JAVA_HOME='D:\VSCodeCache\shizku-m\build-env\jdk-17.0.2'
export GRADLE_USER_HOME='D:\VSCodeCache\maa-alas\.tmp\gradle-home'
cd app && cmd //c 'gradlew.bat assembleDebug --console=plain'
# APK → app/app/build/outputs/apk/debug/app-debug.apk
```

- SDK 由 `app/local.properties`（gitignored）指向 `C:\Users\da270\AppData\Local\Android\Sdk`（cmake 3.22.1 + ndk 28.2 齐）。
- 坑：dl.google.com 间歇握手断 → settings 已加 Aliyun 镜像（官方源兜底）；floatingx 的 compose 包必须显式声明 `floatingx-compose`（两坑详见 debug.md 2026-09-16 条目）。

### rootfs（阶段一）

- GHA workflow 手动触发（主仓需公开）；产物 `rootfs.tar.xz` + BUILD_MANIFEST。
- **交付基准 = v4 artifact**（run 34997038262，sha256 `b506a62e…745a`；含 cached-property 修复）。
- 真机部署链（M1-d 实证）：PC `repack-linkfree.py` 去硬链接重打包 → push → 设备 busybox tar 解 + `chmod -R a+x` → proot harness（nld loader + 显式 guest PATH + `-b /dev,/proc,/sys`）。

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

前置：`adb` 可达真机、`export MSYS_NO_PATHCONV=1`（细节与坑点见 `debug.md`）。
