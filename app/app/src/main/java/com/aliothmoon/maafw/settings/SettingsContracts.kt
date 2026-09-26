package com.aliothmoon.maafw.settings

import com.aliothmoon.maafw.domain.AlasMirror
import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.domain.ThemeMode
import com.aliothmoon.maafw.privileged.RemoteAccessState
import com.aliothmoon.maafw.theme.ThemeStyle

/**
 * 设置页聚合态
 *
 * 后端的写走 PermissionGateway.setBackend（带 unbind 副作用），不直接落 AppSettings——
 * 跳过 unbind 会连着错的特权进程
 */
data class SettingsUiState(
    val remoteAccess: RemoteAccessState = RemoteAccessState(),
    val themeMode: ThemeMode = ThemeMode.System,
    val themeStyle: ThemeStyle = ThemeStyle.DEFAULT,
    val autoCleanLogs: Boolean = true,
    val alasMirror: AlasMirror = AlasMirror.CN,
    val alasMirrorSynced: AlasMirror = AlasMirror.CN,
) {
    /** 镜像档已切但尚未同步成功：下次启动强制全量重同步 */
    val alasMirrorDirty: Boolean get() = alasMirror != alasMirrorSynced
}

sealed interface SettingsIntent {
    /** 切换 Shizuku / Root 后端；落到 AppSettings.startupBackend 并断开当前特权进程 */
    data class SetBackend(val backend: RemoteBackend) : SettingsIntent

    data class SetThemeMode(val mode: ThemeMode) : SettingsIntent

    data class SetThemeStyle(val style: ThemeStyle) : SettingsIntent

    /** null 恢复跟随系统；切换后 Activity 重建 */
    data class SetLanguage(val tag: String?) : SettingsIntent

    data class SetAutoCleanLogs(val enabled: Boolean) : SettingsIntent

    /** 切换 ALAS 镜像档；落盘后下次启动全量重同步（确认弹窗在 Screen 侧） */
    data class SetAlasMirror(val mirror: AlasMirror) : SettingsIntent
}
