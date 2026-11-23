package com.aliothmoon.maafw.update

import android.content.Context
import android.net.ConnectivityManager
import com.aliothmoon.maafw.MaaDispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

const val GITHUB_LATEST_RELEASE_URL = "https://api.github.com/repos/Shinarin/ALAS-AOS/releases/latest"

class GithubAppUpdateSource(
    private val context: Context,
    private val endpoint: String = GITHUB_LATEST_RELEASE_URL,
) : AppUpdateSource {

    override suspend fun fetchLatestRelease(): AppRelease = withContext(MaaDispatchers.IO) {
        val connection = openGet(endpoint)
        try {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseGithubRelease(body)
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun download(
        release: AppRelease,
        onProgress: (Long, Long) -> Unit,
    ): File = withContext(MaaDispatchers.IO) {
        val safeTag = release.tagName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, "ALAS-AOS-$safeTag.apk")
        val partial = File(directory, "$safeTag.part")
        if (partial.exists() && !partial.delete()) {
            throw AppUpdateException(AppUpdateError.INSTALL_FAILED, "Cannot clear partial download")
        }
        var connection: HttpURLConnection? = null
        try {
            connection = openGet(release.downloadUrl)
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.size
            var downloaded = 0L
            onProgress(0, total)
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, total)
                    }
                }
            }
            if (total > 0 && downloaded != total) {
                throw AppUpdateException(AppUpdateError.NETWORK, "Incomplete download")
            }
            if (target.exists() && !target.delete()) {
                throw AppUpdateException(AppUpdateError.INSTALL_FAILED, "Cannot replace download")
            }
            if (!partial.renameTo(target)) {
                throw AppUpdateException(AppUpdateError.INSTALL_FAILED, "Cannot finalize download")
            }
            target
        } catch (error: Exception) {
            partial.delete()
            throw error
        } finally {
            connection?.disconnect()
        }
    }

    private fun openGet(url: String): HttpURLConnection {
        val target = URL(url)
        val connection = target.openConnection(systemProxy(target)) as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", "ALAS-AOS-Android")
        if (connection.responseCode !in 200..299) {
            val code = connection.responseCode
            connection.disconnect()
            throw AppUpdateException(AppUpdateError.NETWORK, "HTTP $code")
        }
        return connection
    }

    private fun systemProxy(url: URL): Proxy {
        if (!url.host.endsWith("github.com", ignoreCase = true)) return Proxy.NO_PROXY
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return Proxy.NO_PROXY
        val info = runCatching { connectivity.defaultProxy }.getOrNull() ?: return Proxy.NO_PROXY
        val host = info.host?.takeIf { it.isNotBlank() } ?: return Proxy.NO_PROXY
        if (info.port <= 0) return Proxy.NO_PROXY
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, info.port))
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
    }
}

internal fun parseGithubRelease(json: String): AppRelease {
    val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrElse {
        throw AppUpdateException(AppUpdateError.NO_ASSET, "Invalid release JSON")
    } ?: throw AppUpdateException(AppUpdateError.NO_ASSET, "Invalid release object")
    val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: throw AppUpdateException(AppUpdateError.NO_ASSET, "Missing tag_name")
    val expectedName = "ALAS-AOS-$tag-android-arm64.apk"
    val assets = root["assets"] as? JsonArray
        ?: throw AppUpdateException(AppUpdateError.NO_ASSET, "Missing assets")
    for (element in assets) {
        val asset = element as? JsonObject ?: continue
        if (asset["name"]?.jsonPrimitive?.contentOrNull != expectedName) continue
        val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw AppUpdateException(AppUpdateError.NO_ASSET, "Missing download URL")
        val size = asset["size"]?.jsonPrimitive?.longOrNull ?: 0
        if (size <= 0) throw AppUpdateException(AppUpdateError.NO_ASSET, "Invalid asset size")
        return AppRelease(tagName = tag, downloadUrl = url, size = size)
    }
    throw AppUpdateException(AppUpdateError.NO_ASSET, "No ARM64 APK for $tag")
}
