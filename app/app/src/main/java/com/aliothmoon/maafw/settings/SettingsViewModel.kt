package com.aliothmoon.maafw.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliothmoon.maafw.config.UserConfigurationStore
import com.aliothmoon.maafw.i18n.AppLocales
import com.aliothmoon.maafw.privileged.PermissionGateway
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 设置页的 Activity 作用域会话
 *
 * 后端选择是 app 设置而非运行配置；主题/语言/日志清理都是只改观感与维护行为的外壳设置
 */
class SettingsViewModel(
    private val permissionGateway: PermissionGateway,
    private val appSettings: AppSettingsGateway,
    private val userConfigurationStore: UserConfigurationStore,
) : ViewModel() {

    private val baseState = combine(
        permissionGateway.state,
        userConfigurationStore.data,
        appSettings.themeStyle,
        appSettings.autoCleanLogs,
    ) { remoteAccess, userConfig, themeStyle, autoCleanLogs ->
        SettingsUiState(
            remoteAccess = remoteAccess,
            themeMode = userConfig.themeMode,
            themeStyle = themeStyle,
            autoCleanLogs = autoCleanLogs,
        )
    }

    // combine 类型化重载最多 5 路：镜像两档并到第二段，保持全程有类型
    val uiState: StateFlow<SettingsUiState> = combine(
        baseState,
        appSettings.alasMirror,
        appSettings.alasMirrorSynced,
    ) { base, alasMirror, alasMirrorSynced ->
        base.copy(alasMirror = alasMirror, alasMirrorSynced = alasMirrorSynced)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    fun onIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.SetBackend -> viewModelScope.launch {
                permissionGateway.setBackend(intent.backend)
            }

            is SettingsIntent.SetThemeMode -> viewModelScope.launch {
                userConfigurationStore.update { it.copy(themeMode = intent.mode) }
            }

            is SettingsIntent.SetThemeStyle -> viewModelScope.launch {
                appSettings.setThemeStyle(intent.style)
            }

            is SettingsIntent.SetLanguage -> AppLocales.apply(intent.tag)

            is SettingsIntent.SetAutoCleanLogs -> viewModelScope.launch {
                appSettings.setAutoCleanLogs(intent.enabled)
            }

            is SettingsIntent.SetAlasMirror -> viewModelScope.launch {
                appSettings.setAlasMirror(intent.mirror)
            }
        }
    }
}
