package com.aliothmoon.maafw.settings

import com.aliothmoon.maafw.domain.AlasMirror
import com.aliothmoon.maafw.domain.OverlayControlMode
import com.aliothmoon.maafw.theme.ThemeStyle
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel 侧看得见的那部分 app 设置；实现是 [AppSettingsManager]
 * 与 [com.aliothmoon.maafw.privileged.PermissionGateway] 同一路子：让 VM 测试能塞 fake，
 * 而不必把 DataStore 一起拖进来
 */
interface AppSettingsGateway {
    val overlayControlMode: StateFlow<OverlayControlMode>

    val autoCleanLogs: StateFlow<Boolean>
    suspend fun setAutoCleanLogs(enabled: Boolean)

    val themeStyle: StateFlow<ThemeStyle>
    suspend fun setThemeStyle(style: ThemeStyle)

    val alasMirror: StateFlow<AlasMirror>
    suspend fun setAlasMirror(mirror: AlasMirror)

    /** 上次热更新成功时生效的档位；与 [alasMirror] 不一致 = 下次启动全量重同步（只读，写归 ProotHost） */
    val alasMirrorSynced: StateFlow<AlasMirror>

}
