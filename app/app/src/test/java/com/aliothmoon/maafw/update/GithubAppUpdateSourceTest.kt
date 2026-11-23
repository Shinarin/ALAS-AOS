package com.aliothmoon.maafw.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubAppUpdateSourceTest {

    @Test
    fun `稳定版高于 alpha 时提示更新`() {
        assertTrue(isNewerVersion("v0.1.8", "0.1.8-alpha.1"))
    }

    @Test
    fun `当前 alpha 高于旧稳定版时不提示更新`() {
        assertFalse(isNewerVersion("v0.1.8", "0.1.9-alpha.1"))
    }

    @Test
    fun `相同版本不提示更新`() {
        assertFalse(isNewerVersion("v0.1.8", "0.1.8"))
        assertFalse(isNewerVersion("v0.1.8-alpha.1", "0.1.8-alpha.1"))
    }

    @Test
    fun `稳定版和 alpha 按各自序号比较`() {
        assertTrue(isNewerVersion("v0.1.9", "0.1.8"))
        assertFalse(isNewerVersion("v0.1.8", "0.1.9"))
        assertTrue(isNewerVersion("v0.1.8-alpha.2", "0.1.8-alpha.1"))
        assertFalse(isNewerVersion("v0.1.8-alpha.1", "0.1.8-alpha.2"))
    }

    @Test
    fun `只选择匹配的 ARM64 安装包`() {
        val release = parseGithubRelease(
            """
            {
              "tag_name": "v0.1.8",
              "assets": [
                {
                  "name": "ALAS-AOS-v0.1.8-android-arm64.apk",
                  "browser_download_url": "https://github.com/Shinarin/ALAS-AOS/releases/download/v0.1.8/app.apk",
                  "size": 338255227
                },
                {
                  "name": "Source code.zip",
                  "browser_download_url": "https://github.com/Shinarin/ALAS-AOS/archive/source.zip",
                  "size": 1000
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("v0.1.8", release.tagName)
        assertEquals("https://github.com/Shinarin/ALAS-AOS/releases/download/v0.1.8/app.apk", release.downloadUrl)
        assertEquals(338255227L, release.size)
    }

    @Test
    fun `没有匹配安装包时返回资产错误`() {
        val error = runCatching {
            parseGithubRelease("""{"tag_name":"v0.1.8","assets":[]}""")
        }.exceptionOrNull()

        assertTrue(error is AppUpdateException)
        assertEquals(AppUpdateError.NO_ASSET, (error as AppUpdateException).kind)
    }

    @Test
    fun `缺少资产字段时返回资产错误`() {
        val error = runCatching {
            parseGithubRelease(
                """{"tag_name":"v0.1.8","assets":[{"name":"ALAS-AOS-v0.1.8-android-arm64.apk"}]}""",
            )
        }.exceptionOrNull()

        assertTrue(error is AppUpdateException)
        assertEquals(AppUpdateError.NO_ASSET, (error as AppUpdateException).kind)
    }
}
