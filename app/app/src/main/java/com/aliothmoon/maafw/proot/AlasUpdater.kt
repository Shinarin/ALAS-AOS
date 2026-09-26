package com.aliothmoon.maafw.proot

import timber.log.Timber

/**
 * ALAS 热更新（roadmap 阶段三第 3 条）：proot 内跑 `seeds/alasaos_update.sh`，
 * 只拉 ALAS 源码不动依赖；断网/超时/镜像不可达一律降级为跳过，不阻塞启动
 *
 * 与脚本的协议：最后一行 `UPDATED <sha> | UNCHANGED <sha> | FAILED <reason>`。
 * UPDATED 之后上游跟踪文件已被 reset 打回原版，调用方负责重放 overlay + assets_fix
 */
class AlasUpdater(
    private val exec: suspend (guestCmd: List<String>, timeoutMs: Long) -> ProotHost.ExecResult,
) {

    data class Result(
        /** 是否真的 checkout 了新 commit（需要重放补丁） */
        val updated: Boolean,
        /** 给人看的摘要：UPDATED <sha> / UNCHANGED <sha> / SKIPPED <reason> */
        val summary: String,
    ) {
        /** UPDATED/UNCHANGED 才算通道打通；SKIPPED 是降级（镜像脏标记按它决定回写与否） */
        val succeeded: Boolean get() = !summary.startsWith("SKIPPED")
    }

    /**
     * 跑一次更新；永不抛异常——任何失败都折叠成 SKIPPED
     * 默认超时给足脚本内部 `timeout 240` 之外的余量；force-full（镜像档切换）
     * 内部是 3×(240s fetch + 5s 间隔)，调用方需传 FORCE_FULL_TIMEOUT_MS
     */
    suspend fun update(timeoutMs: Long = TIMEOUT_MS): Result {
        val result = runCatching {
            exec(listOf("/bin/bash", "seeds/alasaos_update.sh"), timeoutMs)
        }.getOrElse {
            Timber.w(it, "hot update exec failed")
            return Result(false, "SKIPPED exec: ${it.message}")
        }
        if (result.timedOut) {
            Timber.w("hot update timed out after %dms", timeoutMs)
            return Result(false, "SKIPPED timeout")
        }
        result.output.lineSequence().forEach { Timber.d("alasaos_update| %s", it) }
        val verdict = result.output.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith("UPDATED") || it.startsWith("UNCHANGED") || it.startsWith("FAILED") }
        return when {
            verdict == null -> {
                Timber.w("hot update: no verdict line (exit=%s)", result.exit)
                Result(false, "SKIPPED no-verdict(exit=${result.exit})")
            }

            verdict.startsWith("UPDATED") -> {
                Timber.i("hot update: %s", verdict)
                Result(true, verdict)
            }

            verdict.startsWith("UNCHANGED") -> {
                Timber.i("hot update: %s", verdict)
                Result(false, verdict)
            }

            else -> {
                Timber.w("hot update degraded: %s", verdict)
                Result(false, "SKIPPED $verdict")
            }
        }
    }

    companion object {
        /** 脚本内部 timeout 默认 240s；这里留 60s 余量做兜底杀 */
        const val TIMEOUT_MS = 300_000L

        /** force-full：脚本内 3×(240s fetch + 5s 间隔) ≈ 735s，再留余量 */
        const val FORCE_FULL_TIMEOUT_MS = 780_000L
    }
}
