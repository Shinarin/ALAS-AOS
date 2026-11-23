# 设置页日志与 App 自更新设计

## 目标

1. 设置“日志”卡默认收起；两个导出入口交换顺序为“导出启动器日志”在前、“导出 ALAS 日志”在后。查看日志入口和自动清理开关不变。
2. 设置底部“关于”卡保留版本/构建号，增加手动“检查更新”和“下载并安装”能力。

## 更新方案与取舍

采用应用内完整更新：用户点击检查后读取 `https://api.github.com/repos/Shinarin/ALAS-AOS/releases/latest`，以 tag 的版本名判断是否有新版；有新版时才出现下载入口。下载显示进度，完成包名校验、`versionCode` 递增校验和签名一致性校验后，通过 FileProvider 调系统安装器。

- 不采用仅跳浏览器 Release 页：只能算获取更新信息，不能覆盖用户要求的下载和更新动作。
- 不采用系统 DownloadManager：它适合长后台下载，但当前需要细粒度进度、系统代理、fixture 测试与签名校验，复用 `HttpURLConnection` 更直接且不新增依赖。
- 不在开屏自动检查或自动安装：只响应用户在设置页的明确点击，避免大流量与打断。

## 实现边界

- `MaaCard` 新增默认值为 true 的 `initiallyExpanded` 参数，仅日志卡传 false；`rememberSaveable` 保留用户当次展开/收起状态，不写 DataStore。
- 新增独立 `update/` 包：Release 数据与状态、GitHub/HTTP 源、AppUpdateManager。SettingsViewModel 只暴露更新状态并转发检查/安装动作，不承载 HTTP。
- 使用 GitHub `/releases/latest`（稳定版通道），版本比较支持 `x.y.z` 与 `x.y.z-alpha.N`；精确选择 `ALAS-AOS-<tag>-android-arm64.apk`，不接受源码包或其他架构。
- 下载写 `cacheDir/updates/*.part`，流式复制并显示已下载/总字节，完成后原子改名；非 200、长度不符、下载异常均不进入安装。
- 更新包必须满足：包名等于当前 applicationId、versionCode 大于当前、签名与当前安装包一致。校验失败不打开安装器。
- Android 8+ 声明 `REQUEST_INSTALL_PACKAGES`；未允许当前应用安装未知来源包时，跳 `ACTION_MANAGE_UNKNOWN_APP_SOURCES`，用户返回后再次点击继续。
- 系统代理只在主机为 GitHub 时使用；本地 fixture 不走代理。错误可重试，下载中的重复点击忽略。
- 不修改 ALAS 上游、外部 shizuku-m、Git 提交/push/release。

## 验证

- JVM：版本比较（稳定/alpha/相等）、GitHub JSON 资产选择、检查更新 Available/UpToDate/Error 状态。
- 完整 `testDebugUnitTest`、`assembleDebug`、`assembleRelease`。
- 真机：日志默认收起、展开后导出顺序、关于卡检查更新；生产 GitHub 当前版本检查，以及本地 HTTP fixture 下的“发现新版→下载→校验→系统安装”闭环。GitHub 真机测试前提醒代理要求，网络失败与功能失败分开记录。
