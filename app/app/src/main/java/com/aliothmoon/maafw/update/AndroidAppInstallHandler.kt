package com.aliothmoon.maafw.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

class AndroidAppInstallHandler(
    private val context: Context,
) : AppInstallHandler {

    override fun canRequestInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun requestInstallPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    override fun validateAndInstall(file: File, currentVersionCode: Int) {
        val packageManager = context.packageManager
        val archive = packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES,
        ) ?: throw AppUpdateException(AppUpdateError.INVALID_PACKAGE, "Cannot read APK")
        if (archive.packageName != context.packageName) {
            throw AppUpdateException(AppUpdateError.INVALID_PACKAGE, "Package name mismatch")
        }
        if (archive.longVersionCode <= currentVersionCode) {
            throw AppUpdateException(AppUpdateError.INVALID_PACKAGE, "Version code is not newer")
        }
        val installed = packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val installedSignatures = installed.signatureBytes()
        val archiveSignatures = archive.signatureBytes()
        val sameSignature = installedSignatures.any { expected ->
            archiveSignatures.any { actual -> MessageDigest.isEqual(expected, actual) }
        }
        if (!sameSignature) {
            throw AppUpdateException(AppUpdateError.INVALID_PACKAGE, "Signing certificate mismatch")
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_APK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun PackageInfo.signatureBytes(): List<ByteArray> =
        signingInfo?.signingCertificateHistory?.map { it.toByteArray() }.orEmpty()

    private companion object {
        const val MIME_APK = "application/vnd.android.package-archive"
    }
}
