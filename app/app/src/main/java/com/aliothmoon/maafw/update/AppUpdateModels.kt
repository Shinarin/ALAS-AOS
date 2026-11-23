package com.aliothmoon.maafw.update

import java.io.File
import java.io.IOException

data class AppRelease(
    val tagName: String,
    val downloadUrl: String,
    val size: Long,
)

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data class Available(val release: AppRelease) : AppUpdateState
    data class UpToDate(val latestVersion: String) : AppUpdateState
    data class Downloading(
        val release: AppRelease,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : AppUpdateState
    data class ReadyToInstall(val release: AppRelease, val file: File) : AppUpdateState
    data class PermissionRequired(val release: AppRelease) : AppUpdateState
    data class Error(val kind: AppUpdateError, val detail: String?) : AppUpdateState
}

enum class AppUpdateError {
    NETWORK,
    NO_ASSET,
    INVALID_PACKAGE,
    INSTALL_FAILED,
}

class AppUpdateException(
    val kind: AppUpdateError,
    message: String?,
) : IOException(message)

internal fun isNewerVersion(candidate: String, current: String): Boolean {
    val next = parseAppVersion(candidate) ?: return false
    val installed = parseAppVersion(current) ?: return false
    val coreComparison = compareValuesBy(
        next,
        installed,
        AppVersion::major,
        AppVersion::minor,
        AppVersion::patch,
    )
    if (coreComparison != 0) return coreComparison > 0
    return when {
        next.alpha == null && installed.alpha != null -> true
        next.alpha != null && installed.alpha == null -> false
        next.alpha != null && installed.alpha != null -> next.alpha > installed.alpha
        else -> false
    }
}

private data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val alpha: Int?,
)

private val APP_VERSION_PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-alpha\.(\d+))?$""")

private fun parseAppVersion(value: String): AppVersion? {
    val match = APP_VERSION_PATTERN.matchEntire(value) ?: return null
    val (major, minor, patch, alpha) = match.destructured
    return AppVersion(
        major = major.toInt(),
        minor = minor.toInt(),
        patch = patch.toInt(),
        alpha = alpha.toIntOrNull(),
    )
}
