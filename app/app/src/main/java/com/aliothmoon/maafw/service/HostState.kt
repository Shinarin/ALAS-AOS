package com.aliothmoon.maafw.service

import android.content.Context
import android.os.SystemClock
import android.view.Surface
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.constant.DefaultDisplayConfig
import com.aliothmoon.maafw.privileged.PrivilegedServicePort
import com.aliothmoon.maafw.privileged.PrivilegedServiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * 外壳真实状态的唯一来源：特权进程连接态 + 桥可达性 + 虚拟屏 displayId
 *
 * 桥活在特权进程里（BridgeServer 随 RemoteServiceImpl 自起），app 侧够不到它的
 * isRunning()，可达性只能自己周期 ping——协议与 m0 桥同款（行分隔 JSON），
 * 与 ALAS 客户端打的是同一个端口
 *
 * displayId 只是「最近一次 startVirtualDisplay 的返回值」：特权进程一断，
 * 屏与桥随之作废，快照整份清零，等下次连接/探测重建
 */
class HostState(
    private val context: Context,
    private val servicePort: PrivilegedServicePort,
    private val scope: CoroutineScope,
) {

    private val _snapshot = MutableStateFlow(HostSnapshot())
    val snapshot: StateFlow<HostSnapshot> = _snapshot.asStateFlow()

    /** 周期与按需探测共用一把锁，防并发探测挤在同一端口上 */
    private val probeMutex = Mutex()
    private val envMutex = Mutex()
    private val pingSeq = AtomicInteger(0)

    /** 桥探测连续失败计数（只被 probeMutex 保护的块读写） */
    private var probeFailStreak = 0

    /** 上次记失败日志的时刻（elapsedRealtime），持续失败的心跳节流用 */
    private var lastProbeFailLogAtMs = 0L

    fun start() {
        scope.launch {
            servicePort.serviceState.collect { state ->
                _snapshot.update {
                    if (state == PrivilegedServiceState.Connected) {
                        it.copy(privilegedConnected = true)
                    } else {
                        HostSnapshot()
                    }
                }
            }
        }
        scope.launch(MaaDispatchers.IO) {
            while (true) {
                probeBridgeNow()
                // 环境没起且 App 在后台：纯空转，退避到 30s；其余场景维持 4s
                val idle = _snapshot.value.vdDisplayId == DefaultDisplayConfig.DISPLAY_NONE
                val interval = if (idle && !AppForegroundTracker.foreground.value) {
                    BRIDGE_PROBE_IDLE_INTERVAL_MS
                } else {
                    BRIDGE_PROBE_INTERVAL_MS
                }
                withTimeoutOrNull(interval) { AppForegroundTracker.poke.first() }
            }
        }
    }

    suspend fun probeBridgeNow(): Boolean = probeMutex.withLock {
        var failReason: String? = null
        val reachable = runCatching { pingBridge() }
            .onFailure { failReason = it.message }
            .getOrDefault(false)
        if (reachable) {
            if (probeFailStreak > 0) {
                Timber.d("bridge probe recovered after %d failures", probeFailStreak)
                probeFailStreak = 0
            }
            _snapshot.update { it.copy(bridgeReachable = true) }
        } else {
            // 挂机满负荷（ALAS 每帧 2.7MB 打 screencap）时单次 ping 超时是常态，
            // 连续 BRIDGE_FAIL_THRESHOLD 次失败才判不可达，与 FGS「桥抖动不撤保活」对齐
            probeFailStreak++
            logProbeFailureThrottled(failReason)
            if (probeFailStreak >= BRIDGE_FAIL_THRESHOLD) {
                _snapshot.update { it.copy(bridgeReachable = false) }
            }
        }
        reachable
    }

    /** 失败日志状态沿：首败记一条，持续失败每 5 分钟最多一条心跳，恢复成功记 recovered */
    private fun logProbeFailureThrottled(reason: String?) {
        val now = SystemClock.elapsedRealtime()
        when {
            probeFailStreak == 1 -> {
                Timber.d("bridge probe failed: %s", reason ?: "no pong")
                lastProbeFailLogAtMs = now
            }
            now - lastProbeFailLogAtMs >= PROBE_FAIL_LOG_HEARTBEAT_MS -> {
                Timber.d("bridge probe still failing (streak=%d): %s", probeFailStreak, reason ?: "no pong")
                lastProbeFailLogAtMs = now
            }
        }
    }

    /**
     * 「开始」链路：确保特权连接就绪 → setup() → startVirtualDisplay()
     * 桥随特权进程自起，无需显式操作
     *
     * 幂等：屏已存在直接成功；断线重连后（快照已清零）可再次触发
     */
    suspend fun ensureEnvironmentStarted(): Boolean {
        envMutex.withLock {
            if (_snapshot.value.vdDisplayId != DefaultDisplayConfig.DISPLAY_NONE) return@withLock
            val service = servicePort.serviceOrNull() ?: run {
                // 已断就拉一次：自动路径进入前已判 Connected，这里基本是空转；
                // 未授权时 bind 会落成 Error，等完照旧拿不到服务面
                servicePort.bind()
                runCatching {
                    withTimeout(CONNECT_WAIT_MS) {
                        servicePort.serviceState.first { it != PrivilegedServiceState.Connecting }
                    }
                }
                servicePort.serviceOrNull() ?: run {
                    Timber.w("ensureEnvironmentStarted: privileged not connected")
                    return@withLock
                }
            }
            runCatching { service.setup(null, null, BuildConfig.DEBUG) }
                .onFailure { Timber.w(it, "setup failed") }
            val displayId = runCatching { service.startVirtualDisplay() }
                .getOrElse {
                    Timber.e(it, "startVirtualDisplay failed")
                    return@withLock
                }
            if (displayId == DefaultDisplayConfig.DISPLAY_NONE) {
                Timber.w("startVirtualDisplay returned DISPLAY_NONE")
                return@withLock
            }
            Timber.i("Environment started, displayId=%s", displayId)
            _snapshot.update { it.copy(vdDisplayId = displayId) }
            RunForegroundService.start(context)
        }
        // 建完屏（或本来就有屏）顺手刷一次桥态，UI 不必干等下个探测周期
        probeBridgeNow()
        // 环境态已翻转，唤醒轮询循环立刻按新节奏跑
        AppForegroundTracker.poke()
        return _snapshot.value.vdDisplayId != DefaultDisplayConfig.DISPLAY_NONE
    }

    /** 「停止」链路：只停虚拟屏；特权进程与桥留着，下次开始不用重连 */
    suspend fun stopEnvironment() {
        envMutex.withLock {
            servicePort.serviceOrNull()?.let { service ->
                runCatching { service.stopVirtualDisplay() }
                    .onFailure { Timber.w(it, "stopVirtualDisplay failed") }
            }
            _snapshot.update { it.copy(vdDisplayId = DefaultDisplayConfig.DISPLAY_NONE) }
        }
        // 屏已撤，轮询进入空闲档；poke 让循环立刻重算间隔
        AppForegroundTracker.poke()
    }

    /**
     * 预览面挂载/摘除：挂机页 SurfaceView 的 Surface 交给特权进程渲染虚拟屏画面
     * （native bridge_preview 通道，零拷贝）。特权断线时静默失败——
     * 页面侧显示占位，连接恢复后随页面 active 翻转会重挂
     */
    fun attachPreviewSurface(surface: Surface) {
        runCatching { servicePort.serviceOrNull()?.setMonitorSurface(surface) }
            .onFailure { Timber.w(it, "attachPreviewSurface failed") }
    }

    fun detachPreviewSurface() {
        runCatching { servicePort.serviceOrNull()?.setMonitorSurface(null) }
            .onFailure { Timber.w(it, "detachPreviewSurface failed") }
    }

    /**
     * 全屏预览上的手动操作：坐标由 UI 换算到虚拟屏坐标系后传入，
     * 直通 AIDL 同名方法（oneway，内部带虚拟屏 displayId 注入，见 RemoteServiceImpl）。
     * 高频（一次滑动几十条），失败静默——特权断线时快照清零，注入也随之失去目标
     */
    fun touchDown(x: Int, y: Int) {
        runCatching { servicePort.serviceOrNull()?.touchDown(x, y) }
            .onFailure { Timber.w(it, "touchDown failed") }
    }

    fun touchMove(x: Int, y: Int) {
        runCatching { servicePort.serviceOrNull()?.touchMove(x, y) }
            .onFailure { Timber.w(it, "touchMove failed") }
    }

    fun touchUp(x: Int, y: Int) {
        runCatching { servicePort.serviceOrNull()?.touchUp(x, y) }
            .onFailure { Timber.w(it, "touchUp failed") }
    }

    /** m0 桥协议最小客户端：一行请求一行响应，判 "pong":true */
    private fun pingBridge(): Boolean {
        Socket().use { socket ->
            socket.connect(
                InetSocketAddress(InetAddress.getByName(BRIDGE_HOST), BRIDGE_PORT),
                BRIDGE_CONNECT_TIMEOUT_MS,
            )
            socket.soTimeout = BRIDGE_READ_TIMEOUT_MS
            val request = """{"id":${pingSeq.incrementAndGet()},"method":"ping"}"""
            socket.getOutputStream().write((request + "\n").toByteArray(Charsets.UTF_8))
            val line = readLine(BufferedInputStream(socket.getInputStream()))
            return JSONObject(line).optBoolean("pong", false)
        }
    }

    private fun readLine(input: BufferedInputStream): String {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            check(b >= 0) { "bridge closed connection" }
            if (b == '\n'.code) break
            line.write(b)
            check(line.size() <= MAX_REPLY_LINE) { "reply line too long" }
        }
        return line.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val BRIDGE_HOST = "127.0.0.1"
        const val BRIDGE_PORT = 22300
        const val BRIDGE_PROBE_INTERVAL_MS = 4_000L
        const val BRIDGE_PROBE_IDLE_INTERVAL_MS = 30_000L
        const val BRIDGE_FAIL_THRESHOLD = 2
        const val PROBE_FAIL_LOG_HEARTBEAT_MS = 300_000L
        const val BRIDGE_CONNECT_TIMEOUT_MS = 1_500
        const val BRIDGE_READ_TIMEOUT_MS = 2_000
        const val CONNECT_WAIT_MS = 12_000L
        const val MAX_REPLY_LINE = 4 * 1024
    }
}

data class HostSnapshot(
    val privilegedConnected: Boolean = false,
    val bridgeReachable: Boolean = false,
    val vdDisplayId: Int = DefaultDisplayConfig.DISPLAY_NONE,
) {
    /** 环境整体活着：屏在且桥通；悬浮球与 FGS 的「活着」判据 */
    val environmentUp: Boolean
        get() = bridgeReachable && vdDisplayId != DefaultDisplayConfig.DISPLAY_NONE
}
