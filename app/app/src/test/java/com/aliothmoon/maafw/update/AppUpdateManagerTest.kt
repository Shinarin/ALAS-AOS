package com.aliothmoon.maafw.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

private class FakeUpdateSource(
    private val release: AppRelease? = null,
    private val failure: Throwable? = null,
    var downloadFile: File? = null,
    var downloadGate: CompletableDeferred<Unit>? = null,
) : AppUpdateSource {
    var fetchCalls = 0
    var downloadCalls = 0
    var downloadFailure: Throwable? = null

    override suspend fun fetchLatestRelease(): AppRelease {
        fetchCalls++
        failure?.let { throw it }
        return checkNotNull(release)
    }

    override suspend fun download(
        release: AppRelease,
        onProgress: (Long, Long) -> Unit,
    ): File {
        downloadCalls++
        downloadFailure?.let { throw it }
        downloadGate?.await()
        onProgress(50, 100)
        return checkNotNull(downloadFile)
    }
}

private class FakeInstallHandler : AppInstallHandler {
    var allowInstall = true
    var installCalls = 0
    var permissionRequests = 0
    var failure: AppUpdateException? = null

    override fun canRequestInstall(): Boolean = allowInstall

    override fun requestInstallPermission() {
        permissionRequests++
    }

    override fun validateAndInstall(file: File, currentVersionCode: Int) {
        failure?.let { throw it }
        installCalls++
    }
}

class AppUpdateManagerTest {

    @Test
    fun `发现更高版本时进入 Available`() = runTest {
        val release = AppRelease("v0.1.8", "https://example.com/app.apk", 100)
        val source = FakeUpdateSource(release = release)
        val manager = AppUpdateManager(source, FakeInstallHandler(), "0.1.7", 90)

        manager.checkForUpdates()

        assertEquals(AppUpdateState.Available(release), manager.state.value)
        assertEquals(1, source.fetchCalls)
    }

    @Test
    fun `当前版本更高时进入 UpToDate`() = runTest {
        val source = FakeUpdateSource(
            release = AppRelease("v0.1.8", "https://example.com/app.apk", 100),
        )
        val manager = AppUpdateManager(source, FakeInstallHandler(), "0.1.9-alpha.1", 92)

        manager.checkForUpdates()

        assertEquals(AppUpdateState.UpToDate("v0.1.8"), manager.state.value)
    }

    @Test
    fun `检查失败时进入 Error`() = runTest {
        val source = FakeUpdateSource(
            failure = AppUpdateException(AppUpdateError.NETWORK, "http 500"),
        )
        val manager = AppUpdateManager(source, FakeInstallHandler(), "0.1.7", 90)

        manager.checkForUpdates()

        assertEquals(AppUpdateState.Error(AppUpdateError.NETWORK, "http 500"), manager.state.value)
    }

    @Test
    fun `无安装权限时先请求权限且不下载`() = runTest {
        val release = AppRelease("v0.1.8", "https://example.com/app.apk", 100)
        val source = FakeUpdateSource(release = release, downloadFile = File("fixture.apk"))
        val installer = FakeInstallHandler().apply { allowInstall = false }
        val manager = AppUpdateManager(source, installer, "0.1.7", 90)
        manager.checkForUpdates()

        manager.downloadAndInstall()

        assertEquals(AppUpdateState.PermissionRequired(release), manager.state.value)
        assertEquals(0, source.downloadCalls)
        assertEquals(1, installer.permissionRequests)
    }

    @Test
    fun `下载成功后进入 ReadyToInstall`() = runTest {
        val release = AppRelease("v0.1.8", "https://example.com/app.apk", 100)
        val file = File("fixture.apk")
        val source = FakeUpdateSource(release = release, downloadFile = file)
        val installer = FakeInstallHandler()
        val manager = AppUpdateManager(source, installer, "0.1.7", 90)
        manager.checkForUpdates()

        manager.downloadAndInstall()

        assertEquals(AppUpdateState.ReadyToInstall(release, file), manager.state.value)
        assertEquals(1, source.downloadCalls)
        assertEquals(1, installer.installCalls)
    }

    @Test
    fun `下载中的重复调用不会并发下载`() = runTest {
        val release = AppRelease("v0.1.8", "https://example.com/app.apk", 100)
        val source = FakeUpdateSource(
            release = release,
            downloadFile = File("fixture.apk"),
            downloadGate = CompletableDeferred(),
        )
        val installer = FakeInstallHandler()
        val manager = AppUpdateManager(source, installer, "0.1.7", 90)
        manager.checkForUpdates()

        val first = async { manager.downloadAndInstall() }
        val second = async { manager.downloadAndInstall() }
        yield()
        assertEquals(1, source.downloadCalls)
        source.downloadGate?.complete(Unit)
        first.await()
        second.await()

        assertEquals(1, source.downloadCalls)
        assertEquals(1, installer.installCalls)
    }

    @Test
    fun `安装包校验失败时进入 Error`() = runTest {
        val release = AppRelease("v0.1.8", "https://example.com/app.apk", 100)
        val source = FakeUpdateSource(release = release, downloadFile = File("fixture.apk"))
        val installer = FakeInstallHandler().apply {
            failure = AppUpdateException(AppUpdateError.INVALID_PACKAGE, "bad apk")
        }
        val manager = AppUpdateManager(source, installer, "0.1.7", 90)
        manager.checkForUpdates()

        manager.downloadAndInstall()

        assertEquals(AppUpdateState.Error(AppUpdateError.INVALID_PACKAGE, "bad apk"), manager.state.value)
    }

    @Test
    fun `已下载后再次点击只重开安装器`() = runTest {
        val release = AppRelease("v0.1.8", "https://example.com/app.apk", 100)
        val file = File("fixture.apk")
        val source = FakeUpdateSource(release = release, downloadFile = file)
        val installer = FakeInstallHandler()
        val manager = AppUpdateManager(source, installer, "0.1.7", 90)
        manager.checkForUpdates()
        manager.downloadAndInstall()

        manager.downloadAndInstall()

        assertEquals(AppUpdateState.ReadyToInstall(release, file), manager.state.value)
        assertEquals(1, source.downloadCalls)
        assertEquals(2, installer.installCalls)
    }
}
