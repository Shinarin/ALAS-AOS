package com.aliothmoon.maafw.update

import java.io.File

interface AppInstallHandler {
    fun canRequestInstall(): Boolean

    fun requestInstallPermission()

    fun validateAndInstall(file: File, currentVersionCode: Int)
}
