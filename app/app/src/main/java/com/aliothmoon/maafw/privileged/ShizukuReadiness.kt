package com.aliothmoon.maafw.privileged

import com.aliothmoon.maafw.domain.RemoteBackend

enum class ShizukuReadinessStage {
    /** 未安装 Shizuku，也没检测到 Sui */
    NotInstalled,

    /** 已安装但服务未启动 */
    NotRunning,

    /** 装的是官方版 Shizuku：与 shizuku-m 同包名不同签名，必须先卸载再装 shizuku-m */
    OfficialConflict,

    /** 检测到 Sui（Magisk 模块）提供服务 */
    SuiAvailable,

    /** 服务运行中但未授权 */
    NeedAuth,

    /** 就绪：已授权、当前后端不是 Shizuku，或用户已选跳过 */
    Ready,
}

internal suspend fun resolveShizukuReadinessStage(
    remoteState: RemoteAccessState,
    skipCheck: Boolean,
    isSui: Boolean,
    probeNotGranted: suspend () -> ShizukuReadinessStage,
): ShizukuReadinessStage = when {
    // 已跳过就不再付探测那次 IPC 的代价
    skipCheck -> ShizukuReadinessStage.Ready
    remoteState.configuredBackend != RemoteBackend.SHIZUKU -> ShizukuReadinessStage.Ready
    // Sui 在启动时已 init，先报兼容性，别被下面的 shizukuAvailable 抢成 NeedAuth
    isSui -> ShizukuReadinessStage.SuiAvailable
    remoteState.shizukuGranted -> ShizukuReadinessStage.Ready
    else -> probeNotGranted()
}

/** moe.shizuku.privileged.api 这个包名下面装的到底是谁（shizuku-m 与官方版同包名） */
enum class ShizukuFlavor {
    NONE,
    OFFICIAL,
    MOD,
}

/** shizuku-m 下载页：未安装时由默认浏览器打开 */
const val SHIZUKU_M_RELEASES_URL = "https://github.com/Shinarin/shizuku-m/releases"

data class ShizukuReadiness(
    val stage: ShizukuReadinessStage = ShizukuReadinessStage.Ready,
    val canSwitchToRoot: Boolean = false,
) {
    val needsGuidance: Boolean
        get() = stage != ShizukuReadinessStage.Ready
}
