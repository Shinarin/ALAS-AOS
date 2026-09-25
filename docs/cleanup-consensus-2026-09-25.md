# 2026-09-25 · 大扫除三方共审共识与改进方案

> 交付物：用户指令「三个子代理的三方共审，讨论汇总结果需达成共识，并形成改进方案」。
> 状态：共识已达成（kimi-code / gemini-3.8-flash-high 无保留通过，deepseek-flash 有保留共识），
> 7 条保留项 + 收口时新发现的 R8 全部闭环（见 §4）。全部改动为本地 commit，push/发版待用户授权。

## 1. 方法与交叉查验机制

1. **独立审计**：三家各自只读审计全仓（App Kotlin / Python wrapper / 构建配置），产出死代码与优化候选清单。审计范围与豁免区由 grilling 三轮与用户确认：预防性大扫除；死代码豁免=未来模块可能用到的接口（adb 控制面切换、设置页日志区、ACCESSIBILITY 悬浮模式）；方案 C（侵入式重构）不做。
2. **两两互评**：每家对另外两家的报告做交叉评审，标注「认同 / 反对 / 需取证」。
3. **主代理取证裁决**：对互评中的分歧项，主代理在仓库内实地取证（grep / git log / 构建配置核对），逐案裁决。裁决即执行依据。
4. **分批执行 + 门禁**：coder 子代理按风险分 7 批次（A–G）执行，每批过 `assembleRelease`（含 `verifyReleaseR8Keeps`）+ `testDebugUnitTest`；纯删除批与行为变更批分开 commit，便于回滚定位。
5. **真机回归**：0.1.6-alpha.11 装机，三件套（挂机 / 半自动点击 / 活动剧情）+ 切页 18 次 + HOME 35s + 15min 挂机观察。
6. **共识表决**：执行完成后三家对「改动集 + 账册」做最终表决；保留项逐条处置闭环，才算共识达成。

## 2. 关键裁决表（互评分歧项，下轮审计不要再翻案）

| 候选 | 裁决 | 依据 |
|---|---|---|
| `weights.npz` 3.4MB「APK 冗余去重」 | **撤销，保留** | 裁决时 build-rootfs.sh 不装 `overlays/models/`，APK overlay 是唯一交付路径，删=numpy OCR 静默回落 PP-OCR；G2 后镜像也装了，保留理由升级为「双源门禁 + 升级路径保险」（存量旧 rootfs 无模型） |
| `app_name` 字符串「零引用」 | **活，保留** | build-logic 用 manifestPlaceholders 注入；字符串扫描必须覆盖 build-logic |
| `third/IO.java` | **活，保留** | `Command.java` 在调用 |
| `third/StatusBarManager.java` | 死，已删 | 无任何调用方 |
| `MaaChoiceChip.kt` | 部分死 | 只删 `MaaMultiChoiceFlow`；`SingleChoiceFlow` 在设置页 4 处活 |
| icons-extended 模块 | **保留**（误报为 core 重复） | 实为 7 种图标 10 处使用，core 无等价物；`:semi-icons`（592 图标 2.7MB）才是真死的那个，已删 |
| `AppWatchdog` 整链 | 死，已删（用户批准） | v3 重写丢 `startWatching()`，从未启动，`watchdogState()` 恒 IDLE，PermissionManager 还为其 2s 空转轮询——bug 级死代码 |

## 3. 执行批次与数据

| 批次 | commit | 内容 | 量级 |
|---|---|---|---|
| A | `e453629` | test 源集大扫除（54→6 测试文件，**20 tests** 复活转绿；ButtonPrimitiveBoundaryTest 一行修复） | |
| B | `78568f6` | 死 Kotlin 11 整文件 + 死成员 + 死依赖（androidx.window、ui-tooling-preview）+ baselineProfiles 49k 行 + 死 drawable/colors | |
| C | `aec4612` | 不可达链：前台模式/主屏分辨率/亮屏解锁/AIDL runner 残桩/屏保 537 行/AppWatchdog 链；RemoteService.aidl **136→64 行删 21 方法**（只删不重排，DriverClass 签名未动） | |
| D | `8bbbd6f` | `:semi-icons` 模块 + MaaIcons | 592 图标 2.7MB |
| E | `4151c00` | 死字符串 428 组×双语 + 5 plurals（561 组中 428 零引用，排除 app_name） | |
| A–E 合计 | | 735 文件 | +155 / **-76,679** |
| F | `3e8d7ee` `f6eb30f` | 功耗四件套：wrapper `/status` log_lines→log_size（O(1)）；WebView 退后台 onPause+pauseTimers（切 tab 不暂停）；空闲退避 4s→30s（AppForegroundTracker + poke）；wrapper 失败日志状态沿节流（原关停态 20~40 万行/天） | |
| G | `d6f03c7` `208d6b4` `1c76b0b` | App 侧首个 CI（单测→assembleDebug→双源 diff→i18n）；OCR 门禁盲区修复（azur_lane 模型进镜像 + gate 断言 `_al is not None`）；文档/注释腐烂清扫 + .tmp 回收 7.2GB | |
| 账册 | `a87725d` | devlog / handoff / debug.md 同步 | |

真机回归（0.1.6-alpha.11/79 @ AVAY025422002864）：三件套全过；切页 18 次 0 闪「启动环境」；HOME 35s 无损；15min 挂机 runner 全程 alive、respawns=0、金币在涨、停止干净。

## 4. 共识表决与保留项闭环

表决结果：kimi-code ✅ 无保留；gemini-3.8-flash-high ✅ 无保留；deepseek-flash ✅ 有保留共识（7 条非阻断保留项，不推翻任何已执行改动）。

| # | 保留项 | 处置 |
|---|---|---|
| R1 | 账册错数：测试实为 20 个（原写 30） | 已勘误 devlog.md / handoff |
| R2 | 账册错数：AIDL 删 21 方法、RemoteService.aidl 136→64 行（原写 19 / →69） | 已勘误 devlog.md |
| R3 | `proguard-rules.pro` 残留 `-keep IMaaRunnerCallback**`（类已在批次 C 删） | 已删该行 |
| R4 | `UserConfigurationStore.kt` 注释指向不存在的 `docs/persistence-diagnostics.md §9` | 已删死指针，保留行为说明 |
| R5 | **WebView `pauseTimers()` 是进程全局语义**，`resumeAll` 只遍历已注册实例 → Activity 重建（切语言）后新 WebView 继承全局暂停态，真机已复现（切 English 后 ALAS 页永久卡 Loading） | 已修：`AlasWebViewHolder` 加 `paused` 标志位，`register()` 按标志对齐（该停则停，否则 `resumeTimers()` 清残留）。**真机回归通过**：English→简体中文重建后控制台正常渲染（修复前卡死）；HOME 35s 回切无损 |
| R6 | CI 双源门禁漏守 seeds 对 | app.yml 增加 `diff -r --exclude=deploy.yaml …/overlay/seeds rootfs/seeds`（deepseek 实测当前可过） |
| R7 | `AlasRunController.get()` 非 200 分支不设 `lastGetFailure` 就 return → 节流日志可能挂上一次的旧堆栈 | 已修：先记 `IOException("http $code")` 再 return |
| R8 | **（收口时新发现）** `.gitignore` 裸模式 `config/` 把 `com.aliothmoon.maafw.config` 整包挡在仓库外——`UserConfigurationStore.kt` 从未入库，本地能编、fresh clone 必炸 | 锚定 `/config/` + 全仓 `--ignored` 排查（仅此一包受害）+ 文件补入库。新建的 app.yml CI 尚未首跑即提前立功 |

kimi 附注（已收）：① 本地 Git Bash 的 `python3` 是 WindowsApps 占位 stub（静默 exit 49），本地跑脚本须用 `python`——已写入 debug.md；② npz「零安装」措辞在 G2 后陈旧——账册已按 §2 修正。

## 5. 缓办清单（共识：本轮不做，理由在案）

| 项 | 缓办理由 |
|---|---|
| DataStore 三店合并 | 批次 B 删死代码后已降为**低成本**（下轮可做）；注意常量拼写 `DataStoreFile.USER_CONFIGRATION` 是既成事实，改动需兼容存量数据 |
| FileLogTree「定时 flush」 | 前提不成立：现状已是每批 drain 后即 flush，无缓冲丢失窗口 |
| src/test 扩充 | 只剩 6 文件骨架；扩充时**优先覆盖 F2/F3 新代码**（HostState 退避 / AlasRunController 节流 / AppForegroundTracker，均可用 runTest 驱动） |
| third/wrappers 成员级清理 | 整文件级已清完；成员级（WindowManager.lockNow 等）收益小、隐藏 API 反射面风险高，留待下轮 |
| 发版流程脚本化 | 候选池事项，用户已知 |

## 6. 后续改进路线图（建议顺序）

1. **下轮低成本项**：DataStore 合并（含 USER_CONFIGRATION 拼写兼容方案）+ third/wrappers 成员级清理。
2. **测试补强**：F2/F3 新代码单测（退避/节流/前台跟踪）；CI 已有骨架，直接加。
3. **长稳实测补两个场景**：冷态后台 30s 退避支路（R 轮未覆盖）；**切语言后 ALAS 页**（R5 修复的固化回归场景）。
4. **rootfs.yml 下次运行**验证 G2 新断言；失败第一嫌疑是 chroot 内 cv2/numpy 环境。
5. **发版与否由用户定**（若发版走 releasing-new-version 9 步，建议 v0.1.6）。

## 7. 预存未查项（与大扫除无关，已记账）

- 热更新 CDN 拉包成功但 `git reset --hard` FAILED（unable to read tree）。
- env_fix `template.py WARN restore-failed`。
