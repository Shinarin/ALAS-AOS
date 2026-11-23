package com.aliothmoon.maafw.update

import com.aliothmoon.maafw.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppUpdateManager(
    private val source: AppUpdateSource,
    private val installer: AppInstallHandler,
    private val currentVersionName: String = BuildConfig.VERSION_NAME,
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE,
) {
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    suspend fun checkForUpdates() {
        if (_state.value is AppUpdateState.Checking || _state.value is AppUpdateState.Downloading) return
        _state.value = AppUpdateState.Checking
        _state.value = try {
            val release = source.fetchLatestRelease()
            if (isNewerVersion(release.tagName, currentVersionName)) {
                AppUpdateState.Available(release)
            } else {
                AppUpdateState.UpToDate(release.tagName)
            }
        } catch (error: AppUpdateException) {
            AppUpdateState.Error(error.kind, error.message)
        } catch (error: Exception) {
            AppUpdateState.Error(AppUpdateError.NETWORK, error.message)
        }
    }

    suspend fun downloadAndInstall() {
        when (val current = _state.value) {
            is AppUpdateState.ReadyToInstall -> {
                try {
                    installer.validateAndInstall(current.file, currentVersionCode)
                } catch (error: AppUpdateException) {
                    _state.value = AppUpdateState.Error(error.kind, error.message)
                } catch (error: Exception) {
                    _state.value = AppUpdateState.Error(AppUpdateError.INSTALL_FAILED, error.message)
                }
                return
            }
            is AppUpdateState.Available -> current.release
            is AppUpdateState.PermissionRequired -> current.release
            else -> return
        }.let { release ->
            if (!installer.canRequestInstall()) {
                _state.value = AppUpdateState.PermissionRequired(release)
                installer.requestInstallPermission()
                return
            }
            _state.value = AppUpdateState.Downloading(release, 0, release.size)
            _state.value = try {
                val file = source.download(release) { downloaded, total ->
                    _state.value = AppUpdateState.Downloading(release, downloaded, total)
                }
                _state.value = AppUpdateState.ReadyToInstall(release, file)
                installer.validateAndInstall(file, currentVersionCode)
                AppUpdateState.ReadyToInstall(release, file)
            } catch (error: AppUpdateException) {
                AppUpdateState.Error(error.kind, error.message)
            } catch (error: Exception) {
                AppUpdateState.Error(AppUpdateError.INSTALL_FAILED, error.message)
            }
        }
    }
}
