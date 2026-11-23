package com.aliothmoon.maafw.update

import java.io.File

interface AppUpdateSource {
    suspend fun fetchLatestRelease(): AppRelease

    suspend fun download(
        release: AppRelease,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): File
}
