# Shizuku 手动引导收敛设计

## 目标与边界

仅在设置选择 Shizuku 启动模式时进行 Shizuku 就绪检测与提示。服务未运行时保留提示，由用户点击后打开 Shizuku-m App；未安装时由用户点击“去下载”，直接通过系统 `ACTION_VIEW` 交给默认浏览器打开 shizuku-m Releases 页面。保留“我已安装，重新检测”、授权、官方版冲突、切换 Root 与跳过检查等既有行为。

不自动打开 Shizuku、不自动启动 Shizuku 服务、不新增 RemoteService 自动重绑，不修改外部 shizuku-m 仓库，也不试图绕过重启后的必要人工激活。

## 方案

- 采用：复用现有 `PermissionManager.readiness` 和 `ShizukuReadinessDialog`，删除未安装档的二级选源弹窗，确认按钮直接调用现有 `openUrl`。将启动模式门控抽成纯函数，补 JVM 回归测试。改动最小，不改变特权连接生命周期。
- 不采用：监听 RemoteService 断线并自动拉起或重绑。用户明确要求不要过度绑定，且打开 Shizuku 主页并不等于服务已启动。
- 不采用：保留 shizuku-m / 官方版双源选择。它增加一层交互，且官方版与当前配套引导冲突；本次只保留 shizuku-m 下载页。

## 实施点

1. 在 readiness 判定中抽出可测试的纯函数，验证 Root 模式不探测、不提示，Shizuku 模式未授权时才进入后续探测；既有跳过、Sui、已授权分支不变。
2. `ShizukuReadinessDialog` 的 NotInstalled 确认按钮直接打开 `SHIZUKU_M_RELEASES_URL`，删除下载源二级弹窗、官方下载常量及不再使用的双语字符串，更新提示文案与注释。
3. 不调整 `RemoteServiceManager`、`HostState`、Shizuku-m 或自动绑定逻辑。

## 验证

新增判定函数单元测试；运行 `testDebugUnitTest` 与 `assembleDebug`，检查双语资源及死引用。当前真机未连接，无法执行实际点击与浏览器落地验收时必须明确说明，不以编译代替真机结果。
