package com.aliothmoon.maafw.proot

import android.app.Application
import android.net.ConnectivityManager
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.domain.AlasMirror
import com.aliothmoon.maafw.service.RunForegroundService
import com.aliothmoon.maafw.settings.AppSettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * proot 会话宿主：以 App 进程为父，拉起 rootfs 内的 wrapper.py（WebUI 由 wrapper 监管）
 *
 * 链路（roadmap 阶段三第 3 条）：
 * 自愈清锁 → 铺 overlay → 播种实例配置 → 热更新（降级不阻塞）→ 必要时重放补丁
 * → ProcessBuilder 拉起 proot 长跑会话 → 崩溃/退出带退避重拉
 *
 * 生命周期约定：
 * - **stdin 管道必须保持敞开**：wrapper 挂 stdin 监控线程，App 进程一死管道 EOF，
 *   wrapper 杀 runner/gui 进程组后自尽（防孤儿主链路；stop() 也是先关 stdin）
 * - 重拉走 supervisor 协程，退避 3s 翻倍至 60s；热更新每个 App 进程只跑一次
 * - FGS 保活：会话活跃期间 RunForegroundService 钉住 app 进程（其退出判据已并入本会话状态）
 */
class ProotHost(
    private val app: Application,
    private val scope: CoroutineScope,
    private val settings: AppSettingsManager,
) {

    private val _state = MutableStateFlow(ProotSnapshot())
    val state: StateFlow<ProotSnapshot> = _state.asStateFlow()

    private val startMutex = Mutex()
    private var session: Process? = null
    private var supervisorJob: kotlinx.coroutines.Job? = null

    @Volatile
    private var wantRunning = false

    /** 热更新每个 App 进程只跑一次（开屏那次）；崩溃重拉不再重复 */
    @Volatile
    private var updateAttempted = false

    private val rootfsDir: File get() = File(app.filesDir, "rootfs")
    private val alasDir: File get() = File(rootfsDir, "opt/alas")
    private val prootTmpDir: File get() = File(app.filesDir, "proot-tmp")
    private val sessionLog: File get() = File(AppPaths.LOG_DIR, "proot/session.log")
    private val nativeLibDir: String get() = app.applicationInfo.nativeLibraryDir

    // ------------------------------------------------------------------ 对外入口

    /** 幂等：已在跑/在起直接返回；失败后可重复调（手动重试同一入口） */
    fun ensureStarted() {
        wantRunning = true
        scope.launch(MaaDispatchers.IO) { startLocked() }
    }

    /** 停会话：关 stdin 让 wrapper 自尽，超时兜底 destroyForcibly */
    fun stop() {
        wantRunning = false
        scope.launch(MaaDispatchers.IO) {
            startMutex.withLock {
                val proc = session ?: return@withLock
                Timber.i("proot session: stopping")
                runCatching { proc.outputStream.close() }
                withTimeoutOrNull(STOP_GRACE_MS) { runInterruptible { proc.waitFor() } }
                if (proc.isAlive) {
                    Timber.w("proot session: still alive after stdin close, destroyForcibly")
                    proc.destroyForcibly()
                }
                session = null
                _state.update { it.copy(phase = ProotPhase.IDLE, detail = "") }
            }
        }
    }

    // ------------------------------------------------------------------ 启动链

    private suspend fun startLocked() = startMutex.withLock {
        if (session?.isAlive == true) return@withLock
        if (!sanityCheck()) return@withLock

        setState(ProotPhase.PREPARING, "清理残留")
        cleanupStale()
        writeResolvConf()

        setState(ProotPhase.PREPARING, "同步运行文件")
        val overlay = AlasOverlay(app).apply(alasDir)
        if (overlay.failed > 0) {
            fail("运行文件覆盖失败（${overlay.failed} 项）")
            return@withLock
        }

        // 镜像档：git 仓库 / CDN pack / pip 源一起切；与上次热更新成功档不一致 = 本次强制全量重同步
        val mirror = currentMirror()
        val mirrorDirty = mirror != settings.alasMirrorSynced.value

        setState(ProotPhase.PREPARING, "环境自检修复")
        // 幂等：imageio 钉回上游 2.27.0（T2 崩溃根因=环境未钉版）+ git 还原旧补丁遗留；
        // 断网/git 不可用一律降级为日志警告，不阻塞启动（对齐 seed_config 哲学）。
        // proot 下 pip 比原生慢一个量级（首次降级实测 >60s），给独立长超时
        runGuest(
            listOf("/bin/bash", "seeds/env_fix.sh"),
            ENV_FIX_TIMEOUT_MS,
            mapOf("ALASAOS_PYPI_MIRROR" to mirror.pypiMirror),
        )?.let { r ->
            r.output.lineSequence().filter { it.isNotBlank() }.forEach { Timber.i("env_fix| %s", it) }
            // 失败时输出必须落盘：FileLogTree 只收 W+，i 级逐行在 release 包不可见
            if (r.exit != 0) Timber.w("env_fix exit=%s out=%s", r.exit, r.output.takeLast(500))
        }

        setState(ProotPhase.PREPARING, "播种实例配置")
        // 幂等（config/alas.json 已存在即跳过）；失败不阻塞——WebUI 也能救。
        // ALASAOS_MIRROR 驱动 deploy.yaml 的 Repository/PypiMirror 两键随档改写
        runGuest(
            listOf("/usr/bin/python3", "seeds/seed_config.py"),
            SHORT_EXEC_MS,
            mapOf(
                "ALASAOS_ALAS_ROOT" to GUEST_ALAS_ROOT,
                "ALASAOS_MIRROR" to mirror.seedValue,
            ),
        )?.let { r ->
            if (r.exit != 0) Timber.w("seed_config exit=%s out=%s", r.exit, r.output.take(300))
        }

        if (!updateAttempted) {
            updateAttempted = true
            setState(ProotPhase.UPDATING, "检查 ALAS 热更新")
            val updateEnv = buildMap {
                put("ALASAOS_UPDATE_REPO", mirror.updateRepo)
                if (mirror.noCdn) put("ALASAOS_UPDATE_NO_CDN", "1")
                if (mirrorDirty) put("ALASAOS_UPDATE_FORCE_FULL", "1")
                // GitHub 档走系统 HTTP 代理：VPN fake-ip 劫持下 proot 内直连 TCP 被内核秒拒
                // （实测 direct=000/3ms，经代理 200/0.9s）；CN 档 lyoko 直连不需要
                if (mirror == AlasMirror.GITHUB) {
                    systemHttpProxyUrl()?.let { put("ALASAOS_UPDATE_PROXY", it) }
                        ?: Timber.w("github mirror but no system http proxy; direct connect will likely fail")
                }
            }
            // 进度旁路：脚本把 git --progress / CDN 分块进度写 .alasaos_update_progress，
            // 每秒取最后一个 \r/\n 段落喂给清单卡片热更新行的 detail 小字
            val progressFile = File(alasDir, ".alasaos_update_progress")
            runCatching { progressFile.delete() }
            val progressPoller = scope.launch(MaaDispatchers.IO) {
                while (true) {
                    val seg = runCatching {
                        progressFile.readText().split('\r', '\n').lastOrNull { it.isNotBlank() }?.trim()
                    }.getOrNull()
                    if (!seg.isNullOrEmpty()) {
                        _state.update { it.copy(detail = seg.take(120)) }
                    }
                    delay(1000)
                }
            }
            val update = try {
                AlasUpdater { cmd, timeout -> runGuestRaw(cmd, timeout, updateEnv) }
                    .update(if (mirrorDirty) AlasUpdater.FORCE_FULL_TIMEOUT_MS else AlasUpdater.TIMEOUT_MS)
            } finally {
                progressPoller.cancel()
            }
            _state.update { it.copy(updateResult = update.summary) }
            // 通道打通（UPDATED/UNCHANGED）才把镜像档标记为已同步；失败保持脏，下次启动继续全量重同步
            if (update.succeeded) {
                runCatching { settings.setAlasMirrorSynced(mirror) }
                    .onFailure { Timber.w(it, "alasMirrorSynced write failed") }
            }
            if (update.updated) {
                // reset --hard 打回了上游跟踪文件：重放补丁；assets_fix 失败=漂移，记警告不阻塞
                setState(ProotPhase.PREPARING, "重放本地补丁")
                AlasOverlay(app).apply(alasDir)
                runAssetsFix()
            }
        } else {
            // 每启动一次跑一回当漂移自检（幂等）；失败只记警告
            runAssetsFix()
        }

        // args.json/argument.yaml 已不再是补丁（整文件覆盖曾把活动列表冻回烘焙日）：
        // 每次启动现场再生 args（活动列表随 campaign/Readme.md 走）并补回 alasaos 桥选项。
        // 失败降级为警告——args.json 仍是上游 git 版，可启动，但 alasaos 选项可能缺失。
        setState(ProotPhase.PREPARING, "再生 args 配置")
        runGuest(listOf("/usr/bin/python3", "seeds/regen_args.py"), REGEN_ARGS_TIMEOUT_MS)?.let { r ->
            if (r.exit != 0) Timber.w("regen_args exit=%s out=%s", r.exit, r.output.takeLast(500))
        }

        setState(ProotPhase.STARTING, "拉起 proot 会话")
        val proc = runCatching { spawnSession() }.getOrElse {
            fail("exec proot: ${it.message}")
            return@withLock
        }
        session = proc
        RunForegroundService.start(app)
        supervise(proc)

        if (awaitServices(SERVICES_UP_MS)) {
            setState(ProotPhase.RUNNING)
            Timber.i("proot session up: wrapper ready on %d", WRAPPER_PORT)
        } else {
            // wrapper 还没就绪：可能首次 import 慢，也可能马上退出——交给 supervisor 兜底
            setState(ProotPhase.STARTING, "等待 wrapper 就绪")
            Timber.w("wrapper not ready within %dms", SERVICES_UP_MS)
        }
    }

    private fun sanityCheck(): Boolean {
        if (!File(rootfsDir, "usr/bin/python3").exists()) {
            fail("rootfs 未部署（python3 缺失）")
            return false
        }
        if (!File(nativeLibDir, "libproot.so").exists()) {
            fail("libproot.so 缺失（当前 ABI 不支持？）")
            return false
        }
        if (!File(nativeLibDir, "libproot-loader.so").exists()) {
            fail("libproot-loader.so 缺失")
            return false
        }
        return true
    }

    // ------------------------------------------------------------------ 会话

    /** 拉起长跑会话；调用方持有返回的 Process（stdin 保持敞开，见类头约定） */
    private fun spawnSession(): Process {
        prootTmpDir.mkdirs()
        sessionLog.parentFile?.mkdirs()
        val cmd = listOf(
            File(nativeLibDir, "libproot.so").absolutePath,
            "-w", GUEST_ALAS_ROOT,
            "-r", rootfsDir.absolutePath,
            "-b", "/dev:/dev", "-b", "/proc:/proc", "-b", "/sys:/sys",
            "/usr/bin/python3", "wrapper.py",
        )
        Timber.i("proot session spawn: %s", cmd.joinToString(" "))
        val proc = ProcessBuilder(cmd)
            .directory(alasDir)
            .apply { environment().remove("LD_PRELOAD"); environment().putAll(baseEnv()) }
            .start()
        drainTo(proc.inputStream, "proot-out")
        drainTo(proc.errorStream, "proot-err")
        return proc
    }

    /** proot 进程环境：与 Spike A 实证的同一套（nld 即 LD_LIBRARY_PATH） */
    private fun baseEnv(): Map<String, String> = mapOf(
        "LD_LIBRARY_PATH" to nativeLibDir,
        "PROOT_LOADER" to File(nativeLibDir, "libproot-loader.so").absolutePath,
        "PROOT_TMP_DIR" to prootTmpDir.absolutePath,
        "TMPDIR" to prootTmpDir.absolutePath,
        "HOME" to app.filesDir.absolutePath,
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "LANG" to "C.UTF-8",
        // rootfs 未装 tzdata：用 POSIX 形式 CST-8（UTC+8 无 DST），不依赖 zoneinfo 文件；
        // 不设则全环境 UTC，ALAS 日志/调度时间比设备慢 8 小时
        "TZ" to "CST-8",
        "ALASAOS_ALAS_ROOT" to GUEST_ALAS_ROOT,
        "ALASAOS_WEBUI" to "1",
    )

    /** stdout/stderr 汇进 session 日志（带行级时间戳太贵，纯追加即可） */
    private fun drainTo(stream: java.io.InputStream, tag: String) {
        Thread {
            runCatching {
                sessionLog.parentFile?.mkdirs()
                java.io.FileOutputStream(sessionLog, true).bufferedWriter().use { w ->
                    stream.bufferedReader().forEachLine {
                        w.append("[$tag] ").append(it)
                        w.newLine()
                        // 行量小（wrapper 生命周期事件），逐行 flush 保证现场随时可查
                        w.flush()
                    }
                }
            }.onFailure { Timber.d("drain %s closed: %s", tag, it.message) }
        }.apply { isDaemon = true; name = "proot-drain-$tag" }.start()
    }

    /** 崩溃/退出重拉：退避 3s 翻倍至 60s；wantRunning 撤了就不拉。
     *  熔断：会话连续秒退（<QUICK_DEATH_MS）MAX_RAPID_DEATHS 次即放弃——典型诱因是
     *  端口被同机旧装 App 的残留会话占用，此时 awaitServices 会被占位者喂成假 RUNNING，
     *  不退熔断就是 3s 一轮的无限崩溃循环（21:16 真机事故） */
    private fun supervise(first: Process) {
        supervisorJob?.cancel()
        supervisorJob = scope.launch(MaaDispatchers.IO) {
            var proc = first
            var backoff = RESTART_BACKOFF_INIT_MS
            var rapidDeaths = 0
            var spawnedAt = System.currentTimeMillis()
            while (true) {
                val code = runCatching { runInterruptible { proc.waitFor() } }.getOrDefault(-1)
                val livedMs = System.currentTimeMillis() - spawnedAt
                Timber.w("proot session exited code=%s lived=%dms", code, livedMs)
                session = null
                if (!wantRunning) break
                rapidDeaths = if (livedMs < QUICK_DEATH_MS) rapidDeaths + 1 else 0
                if (rapidDeaths >= MAX_RAPID_DEATHS) {
                    fail("会话连续 $MAX_RAPID_DEATHS 次秒退（端口被占用？），已停止重拉")
                    break
                }
                setState(ProotPhase.STARTING, "会话退出($code)，${backoff / 1000}s 后重拉")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(RESTART_BACKOFF_MAX_MS)
                if (!wantRunning) break
                cleanupStale()
                val next = runCatching { spawnSession() }
                    .onFailure { Timber.w(it, "proot respawn failed") }
                    .getOrNull() ?: continue
                session = next
                proc = next
                spawnedAt = System.currentTimeMillis()
                if (awaitServices(SERVICES_UP_MS)) {
                    backoff = RESTART_BACKOFF_INIT_MS
                    setState(ProotPhase.RUNNING)
                    Timber.i("proot session respawned, wrapper ready")
                }
            }
            Timber.i("proot supervisor exited")
        }
    }

    /**
     * 轮询直到 wrapper(22400) 与 WebUI(22267) 双双可达（1s 一拍）
     *
     * RUNNING 的语义必须是「WebUI 真的能服务」：gui.py 进程活着但 uvicorn 还在
     * import 的几秒里，WebView 自动重载会吃 connection refused 卡进错误页
     */
    private suspend fun awaitServices(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (httpOk("http://127.0.0.1:$WRAPPER_PORT/status") &&
                httpOk("http://127.0.0.1:$WEBUI_PORT/")
            ) {
                return true
            }
            delay(1_000)
        }
        return false
    }

    private fun httpOk(url: String): Boolean = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 800
        conn.readTimeout = 800
        conn.inputStream.use { it.readBytes() }
        conn.responseCode == 200
    }.getOrDefault(false)

    // ------------------------------------------------------------------ 一次性 proot 执行

    data class ExecResult(
        val exit: Int?,
        val output: String,
        val timedOut: Boolean,
    )

    /** 带默认环境的 [runGuestRaw]（seed/assets_fix 用） */
    private suspend fun runGuest(
        guestCmd: List<String>,
        timeoutMs: Long,
        extraEnv: Map<String, String> = emptyMap(),
    ): ExecResult? = runCatching { runGuestRaw(guestCmd, timeoutMs, extraEnv) }
        .onFailure { Timber.w(it, "guest exec failed: %s", guestCmd.joinToString(" ")) }
        .getOrNull()

    /** 一次性 proot 执行：合并 stderr，限时强杀；输出整体回收（更新脚本的 verdict 在里面） */
    private suspend fun runGuestRaw(
        guestCmd: List<String>,
        timeoutMs: Long,
        extraEnv: Map<String, String> = emptyMap(),
    ): ExecResult = withContext(MaaDispatchers.IO) {
        prootTmpDir.mkdirs()
        val cmd = listOf(
            File(nativeLibDir, "libproot.so").absolutePath,
            "-w", GUEST_ALAS_ROOT,
            "-r", rootfsDir.absolutePath,
            "-b", "/dev:/dev", "-b", "/proc:/proc", "-b", "/sys:/sys",
        ) + guestCmd
        val proc = ProcessBuilder(cmd)
            .directory(alasDir)
            .redirectErrorStream(true)
            .apply { environment().remove("LD_PRELOAD"); environment().putAll(baseEnv()); environment().putAll(extraEnv) }
            .start()
        val out = StringBuilder()
        val reader = Thread {
            runCatching { proc.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') } }
        }.apply { isDaemon = true; name = "proot-exec-reader" }
        reader.start()
        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) proc.destroyForcibly()
        reader.join(2_000)
        ExecResult(if (finished) proc.exitValue() else null, out.toString(), !finished)
    }

    /** assets_fix：幂等 + 漂移自检（Button 找不到会非零退出），失败只记警告 */
    private suspend fun runAssetsFix() {
        val r = runGuest(listOf("/usr/bin/python3", "seeds/assets_fix.py", GUEST_ALAS_ROOT), SHORT_EXEC_MS)
            ?: return
        if (r.exit == 0) {
            Timber.d("assets_fix OK")
        } else {
            // 漂移=上游改版对不上补丁：roadmap 预定的稀有事件，提示重下整包（不阻塞本次启动）
            Timber.w("assets_fix drift detected exit=%s: %s", r.exit, r.output.takeLast(500))
            _state.update {
                it.copy(updateResult = (it.updateResult ?: "") + " | assets_fix 漂移，建议重下整包")
            }
        }
    }

    // ------------------------------------------------------------------ 自愈清理与 DNS

    /** 镜像档读盘是异步的：最多等一拍，等不到按 schema 默认档（CN）走，不卡启动链 */
    private suspend fun currentMirror(): AlasMirror {
        withTimeoutOrNull(SETTINGS_LOADED_WAIT_MS) { settings.loaded.first { it } }
        return settings.alasMirror.value
    }

    /** 系统 HTTP 代理（VPN 通告的 HttpProxy，地址/端口随 VPN 启动会变，不能硬编码） */
    private fun systemHttpProxyUrl(): String? {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return null
        val info = runCatching { cm.defaultProxy }.getOrNull() ?: return null
        val host = info.host?.takeIf { it.isNotBlank() } ?: return null
        if (info.port <= 0) return null
        return "http://$host:${info.port}"
    }

    /** 自愈清锁：proot 临时目录整体重来 + git 锁 + reloadalas（会话不在跑时才可调） */
    private suspend fun cleanupStale() {
        runCatching {
            prootTmpDir.deleteRecursively()
            prootTmpDir.mkdirs()
        }.onFailure { Timber.w(it, "cleanup proot-tmp failed") }
        runCatching { File(alasDir, "config/reloadalas").delete() }
        runCatching {
            val gitDir = File(alasDir, ".git")
            if (gitDir.isDirectory) {
                gitDir.walkTopDown().filter { it.isFile && it.name.endsWith(".lock") }
                    .forEach { it.delete() }
            }
        }.onFailure { Timber.w(it, "cleanup git locks failed") }
        truncateSessionLogIfStale()
    }

    /**
     * session.log 截尾：mtime 超 7 天且体积超上限时只留最后 [SESSION_LOG_KEEP_BYTES]
     *
     * 放在这里做是因为 startLocked 每次启动必经、且早于 spawnSession——此刻没有
     * drain 线程在写，无竞争；截断会刷新 mtime，崩溃重拉循环里不会再重复截
     */
    private suspend fun truncateSessionLogIfStale() {
        // 设置读盘是异步的：最多等一拍，等不到就本次跳过（下轮启动再判），不卡启动链
        val loaded = withTimeoutOrNull(SETTINGS_LOADED_WAIT_MS) {
            settings.loaded.first { it }
            true
        } ?: false
        if (!loaded || !settings.autoCleanLogs.value) return

        val file = sessionLog
        val length = file.length()
        if (!file.isFile || length <= SESSION_LOG_KEEP_BYTES) return
        if (System.currentTimeMillis() - file.lastModified() < SESSION_LOG_STALE_MS) return

        runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                val tail = ByteArray(SESSION_LOG_KEEP_BYTES.toInt())
                raf.seek(length - tail.size)
                raf.readFully(tail)
                // 切口多半落在半行/半个 UTF-8 字符上：从第一个换行之后开始留
                val firstNewline = tail.indexOf('\n'.code.toByte())
                val body = if (firstNewline in 0 until tail.size - 1) {
                    tail.copyOfRange(firstNewline + 1, tail.size)
                } else {
                    tail
                }
                val marker = buildString {
                    append("[host] ")
                    synchronized(sessionLogLock) { append(phaseTs.format(java.util.Date())) }
                    append(" TRUNCATED 过期 session.log，仅保留尾部 ")
                    append(SESSION_LOG_KEEP_BYTES / 1024 / 1024).append("MB\n")
                }.toByteArray()
                raf.setLength(0)
                raf.write(marker)
                raf.write(body)
            }
            Timber.w("session.log 过期且超 %dMB，已截尾（原 %dKB）", SESSION_LOG_KEEP_BYTES / 1024 / 1024, length / 1024)
        }.onFailure { Timber.w(it, "session.log 截尾失败") }
    }

    /**
     * 写死 DNS：烘焙包里的 /etc/resolv.conf 是指向 /run/systemd 的悬空软链，
     * 设备上解析必挂（热更新需要网络）。写普通文件， mainland 默认 AliDNS
     */
    private fun writeResolvConf() {
        runCatching {
            val f = File(rootfsDir, "etc/resolv.conf")
            if (Files.isSymbolicLink(f.toPath()) || f.exists()) f.delete()
            f.writeText("nameserver 223.5.5.5\nnameserver 223.6.6.6\n")
        }.onFailure { Timber.w(it, "write resolv.conf failed") }
    }

    // ------------------------------------------------------------------ 状态

    private val sessionLogLock = Any()
    private val phaseTs = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)

    private fun setState(phase: ProotPhase, detail: String = "") {
        _state.update { it.copy(phase = phase, detail = detail) }
        logPhase(phase.name, detail)
    }

    /**
     * 阶段迁移落盘 session.log：release 版 Timber 只落 W+，准备链 2~5 分钟全程静默
     * 曾致「App 假死」误判——session.log 本就是生命周期时间线，[host] 行与 [proot-out] 交错即全貌
     */
    private fun logPhase(tag: String, detail: String) {
        runCatching {
            sessionLog.parentFile?.mkdirs()
            val line = buildString {
                append("[host] ")
                synchronized(sessionLogLock) { append(phaseTs.format(java.util.Date())) }
                append(' ').append(tag)
                if (detail.isNotEmpty()) append(' ').append(detail)
                append('\n')
            }
            java.io.FileOutputStream(sessionLog, true).use { it.write(line.toByteArray()) }
        }
    }

    private fun fail(reason: String) {
        Timber.e("ProotHost failed: %s", reason)
        _state.update { it.copy(phase = ProotPhase.FAILED, detail = reason) }
        logPhase("FAILED", reason)
    }

    companion object {
        /** wrapper 薄 HTTP（与 rootfs wrapper.py 的 PORT 对齐；避开桥 22300 与 WebUI 22267） */
        const val WRAPPER_PORT = 22400

        /** WebUI 端口（deploy.yaml WebuiPort；AlasScreen 与外部浏览器都打它） */
        const val WEBUI_PORT = 22267

        private const val GUEST_ALAS_ROOT = "/opt/alas"
        private const val SERVICES_UP_MS = 90_000L
        private const val SHORT_EXEC_MS = 60_000L
        private const val ENV_FIX_TIMEOUT_MS = 300_000L
        private const val REGEN_ARGS_TIMEOUT_MS = 360_000L
        private const val STOP_GRACE_MS = 8_000L
        private const val RESTART_BACKOFF_INIT_MS = 3_000L
        private const val RESTART_BACKOFF_MAX_MS = 60_000L
        private const val QUICK_DEATH_MS = 10_000L
        private const val MAX_RAPID_DEATHS = 5

        /** session.log 截尾：保留尾部 2MB；mtime 超 7 天才算过期 */
        private const val SESSION_LOG_KEEP_BYTES = 2L * 1024 * 1024
        private const val SESSION_LOG_STALE_MS = 7L * 24 * 60 * 60 * 1000
        private const val SETTINGS_LOADED_WAIT_MS = 2_000L
    }
}
