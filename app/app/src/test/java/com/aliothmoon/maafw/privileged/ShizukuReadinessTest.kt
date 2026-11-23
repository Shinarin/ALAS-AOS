package com.aliothmoon.maafw.privileged

import com.aliothmoon.maafw.domain.RemoteBackend
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuReadinessTest {

    @Test
    fun `Root 启动模式跳过 Shizuku 探测`() = runTest {
        var probeCalls = 0

        val stage = resolveShizukuReadinessStage(
            remoteState = RemoteAccessState(configuredBackend = RemoteBackend.ROOT),
            skipCheck = false,
            isSui = false,
            probeNotGranted = {
                probeCalls++
                ShizukuReadinessStage.NotRunning
            },
        )

        assertEquals(ShizukuReadinessStage.Ready, stage)
        assertEquals(0, probeCalls)
    }

    @Test
    fun `Shizuku 启动模式未授权时进入后续探测`() = runTest {
        var probeCalls = 0

        val stage = resolveShizukuReadinessStage(
            remoteState = RemoteAccessState(configuredBackend = RemoteBackend.SHIZUKU),
            skipCheck = false,
            isSui = false,
            probeNotGranted = {
                probeCalls++
                ShizukuReadinessStage.NotRunning
            },
        )

        assertEquals(ShizukuReadinessStage.NotRunning, stage)
        assertEquals(1, probeCalls)
    }

    @Test
    fun `Shizuku 已授权时直接就绪`() = runTest {
        var probeCalls = 0

        val stage = resolveShizukuReadinessStage(
            remoteState = RemoteAccessState(
                shizukuAvailable = true,
                shizukuGranted = true,
                configuredBackend = RemoteBackend.SHIZUKU,
            ),
            skipCheck = false,
            isSui = false,
            probeNotGranted = {
                probeCalls++
                ShizukuReadinessStage.NotRunning
            },
        )

        assertEquals(ShizukuReadinessStage.Ready, stage)
        assertEquals(0, probeCalls)
    }

    @Test
    fun `跳过检查时不再探测 Shizuku`() = runTest {
        var probeCalls = 0

        val stage = resolveShizukuReadinessStage(
            remoteState = RemoteAccessState(configuredBackend = RemoteBackend.SHIZUKU),
            skipCheck = true,
            isSui = false,
            probeNotGranted = {
                probeCalls++
                ShizukuReadinessStage.NotRunning
            },
        )

        assertEquals(ShizukuReadinessStage.Ready, stage)
        assertEquals(0, probeCalls)
    }

    @Test
    fun `Sui 可用时保留兼容性提示`() = runTest {
        var probeCalls = 0

        val stage = resolveShizukuReadinessStage(
            remoteState = RemoteAccessState(configuredBackend = RemoteBackend.SHIZUKU),
            skipCheck = false,
            isSui = true,
            probeNotGranted = {
                probeCalls++
                ShizukuReadinessStage.NotRunning
            },
        )

        assertEquals(ShizukuReadinessStage.SuiAvailable, stage)
        assertEquals(0, probeCalls)
    }
}
