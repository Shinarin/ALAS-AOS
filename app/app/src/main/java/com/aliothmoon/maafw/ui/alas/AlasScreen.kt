package com.aliothmoon.maafw.ui.alas

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.constant.DefaultDisplayConfig
import com.aliothmoon.maafw.proot.AlasRunController
import com.aliothmoon.maafw.proot.AlasRunState
import com.aliothmoon.maafw.proot.ProotHost
import com.aliothmoon.maafw.proot.ProotPhase
import com.aliothmoon.maafw.proot.ProotSnapshot
import com.aliothmoon.maafw.provision.ProvisionState
import com.aliothmoon.maafw.provision.RootfsProvisioner
import com.aliothmoon.maafw.service.HostSnapshot
import com.aliothmoon.maafw.service.HostState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.ui.components.MaaCard
import org.koin.compose.koinInject

/** ALAS WebUI：App 内置环境监听的本机回环地址 */
private const val ALAS_WEBUI_URL = "http://127.0.0.1:22267"

/**
 * 本机（HONOR PPG-AN00, WebView 151）实测 `vh` 单位恒为 0：页面的 layout viewport
 * 错位成 0 高，所有 `100vh`（pywebio 脚手架 `#pywebio-scope-ROOT` 的高度）塌成 0，
 * 整页只画顶部一条标题带。`innerHeight` 正常，所以注入同值像素覆盖即可恢复。
 * 规则挂在 head 上对 JS 后续创建的 scope 元素同样生效；vh 正常的设备上本规则
 * 与 `100vh` 等值，无副作用。
 */
private const val SCOPE_HEIGHT_FIX_JS =
    """
    (() => {
      const h = Math.max(1, window.innerHeight);
      let s = document.getElementById('alas-scope-height-fix');
      if (!s) {
        s = document.createElement('style');
        s.id = 'alas-scope-height-fix';
        document.head.appendChild(s);
      }
      s.textContent = '#pywebio-scope-ROOT{height:' + h + 'px !important;min-height:' + h + 'px !important;}';
    })();
    """

/**
 * WebUI 闲置状态环修复。ALAS 把闲置设计成静态完整圆环（fill 态）：
 * `put_loading(color="secondary").style("--loading-border-fill--")` 打内联标记，
 * alas.css 用 `*[style*="--loading-border-fill--"]` 命中后定制尺寸+四边同色 border。
 * 但 pywebio 的 `.style()` 把标记写在 put_html 的**外包装 div** 上（spinner 的父级），
 * fill 规则实际给 wrapper 画了个无圆角静态方框，真正的 `.spinner-border` 完全没被
 * 定制——保持 Bootstrap 默认：0.75s 旋转 + border-right 透明缺口。于是闲置态
 * 呈现「方框 + 转圈」，被误读为卡住/加载中（CDP 实测：marker 在 wrapper、
 * spinner animName=spinner-border、borderRight=transparent）。
 * 此处按类名定点修真正的 spinner：停转 + 补缺口成完整圆（仅 secondary 命中：
 * 闲置/UpToDate/RemoteNotRunning 三个 fill 态；Running/Warning 颜色不同照常旋转），
 * 同时剥掉 fill wrapper 的方框 artifact。改 ALAS 文件会被热更新冲掉且用户明令
 * 禁止，故注入在 WebView 层。
 */
private const val IDLE_SPINNER_FIX_JS =
    """
    (() => {
      if (document.getElementById('alasaos-idle-spinner-fix')) return;
      const s = document.createElement('style');
      s.id = 'alasaos-idle-spinner-fix';
      s.textContent = '.spinner-border.text-secondary{animation:none !important;border-right-color:currentColor !important;}'
        + 'div[style*="--loading-border-fill--"]{border:none !important;width:auto !important;height:auto !important;}';
      document.head.appendChild(s);
    })();
    """

/**
 * ALAS tab：全屏 WebView 容器，承载 App 内置环境里的 ALAS WebUI
 *
 * [active] 标记当前是否为 pager 可见页：ALAS 页不在前台时（pager 仍预组合着它）
 * 不该抢返回键。WebUI 历史能后退就 goBack，否则把返回键让回原有导航逻辑
 *
 * 本页可见且特权连接就绪时自动补一次「开始」链路建虚拟屏（HostState 内幂等，
 * 断线重连后随 privilegedConnected 翻转会再触发）
 *
 * 开屏遮罩是启动阶段清单卡（[AlasBootCard]）：各行勾按真实信号落，
 * 控制台页面就绪（pageReady）后整卡随遮罩淡出
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AlasScreen(
    active: Boolean,
    modifier: Modifier = Modifier,
    hostState: HostState = koinInject(),
    prootHost: ProotHost = koinInject(),
    provisioner: RootfsProvisioner = koinInject(),
    alasController: AlasRunController = koinInject(),
) {
    var loadFailed by remember { mutableStateOf(false) }
    var pageReady by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val hostSnapshot by hostState.snapshot.collectAsStateWithLifecycle()
    val prootState by prootHost.state.collectAsStateWithLifecycle()
    val provisionState by provisioner.state.collectAsStateWithLifecycle()
    val alasState by alasController.state.collectAsStateWithLifecycle()

    // FAILED 只留当前态不留来处：记住它之前处于哪个阶段，清单据此把 ✗ 挂到对应行
    var lastActivePhase by remember { mutableStateOf(ProotPhase.IDLE) }
    LaunchedEffect(prootState.phase) {
        if (prootState.phase != ProotPhase.FAILED) lastActivePhase = prootState.phase
    }

    LaunchedEffect(active, hostSnapshot.privilegedConnected) {
        if (active && hostSnapshot.privilegedConnected) {
            hostState.ensureEnvironmentStarted()
        }
    }

    // 内置环境转 RUNNING（首启/热更新/崩溃重拉完成）时自动重载，不用用户点重试
    LaunchedEffect(prootState.phase) {
        if (prootState.phase == ProotPhase.RUNNING) {
            loadFailed = false
            webView?.loadUrl(ALAS_WEBUI_URL)
        }
    }

    BackHandler(enabled = active && canGoBack) {
        webView?.let {
            it.goBack()
            canGoBack = it.canGoBack()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    // debug 包开 WebView 调试口：本地排查页面渲染用
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && BuildConfig.DEBUG) {
                        WebView.setWebContentsDebuggingEnabled(true)
                    }
                    // pywebio 是 SPA，JS 与 localStorage 都要开
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean = when (request.url?.scheme) {
                            // http/https 一律留在 WebView 内打开，不放给外部浏览器
                            "http", "https" -> false
                            // 其余协议（intent:/tel:/mailto:...）不交给外部处理
                            else -> true
                        }

                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            loadFailed = false
                            pageReady = false
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            canGoBack = view.canGoBack()
                            // 主文档失败不算就绪（onReceivedError 已置位），开屏就不淡出
                            if (!loadFailed) pageReady = true
                            // 见 SCOPE_HEIGHT_FIX_JS：本机 vh=0，补像素高度
                            view.evaluateJavascript(SCOPE_HEIGHT_FIX_JS, null)
                            // 见 IDLE_SPINNER_FIX_JS：闲置环停转，fill 态专用
                            view.evaluateJavascript(IDLE_SPINNER_FIX_JS, null)
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            // 只对主文档报错；子资源（图片/脚本）失败不算整页失败
                            if (request.isForMainFrame) {
                                canGoBack = view.canGoBack()
                                loadFailed = true
                            }
                        }
                    }
                    webView = this
                    AlasWebViewHolder.register(this)
                    loadUrl(ALAS_WEBUI_URL)
                }
            },
            onRelease = {
                AlasWebViewHolder.unregister(it)
                it.destroy()
            },
            modifier = Modifier.fillMaxSize(),
        )

        AnimatedVisibility(
            visible = !pageReady,
            modifier = Modifier.fillMaxSize(),
            enter = EnterTransition.None,
            exit = fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .pointerInput(Unit) {
                        // 挡住穿透到 WebView 上的漏点，启动期间只留行内重试可点
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    }
                    .padding(MaaDesignTokens.Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                AlasBootCard(
                    provisionState = provisionState,
                    hostSnapshot = hostSnapshot,
                    prootState = prootState,
                    alasState = alasState,
                    lastActivePhase = lastActivePhase,
                    loadFailed = loadFailed,
                    pageReady = pageReady,
                    onRetryProvision = { provisioner.retry() },
                    onRetryStartup = {
                        loadFailed = false
                        if (prootState.phase == ProotPhase.FAILED || prootState.phase == ProotPhase.IDLE) {
                            prootHost.ensureStarted()
                        }
                        webView?.loadUrl(ALAS_WEBUI_URL)
                    },
                )
            }
        }
    }
}

private enum class BootRowState { Pending, Running, Done, Failed }

/** 行内小字的色调：进行中明细 / 警告 / 错误摘要 */
private enum class BootRowTone { Normal, Warning, Error }

private class BootRow(
    @param:StringRes val labelRes: Int,
    val state: BootRowState,
    val detail: String? = null,
    val tone: BootRowTone = BootRowTone.Normal,
    val onRetry: (() -> Unit)? = null,
)

/**
 * 启动阶段清单卡：8 行固定顺序，勾按真实信号落（允许跳跃式打勾），
 * 底部一条总进度条。行 1 只在部署状态机非 Ready（首启/升版）时出现，
 * 此时分母为 8，否则为 7
 */
@Composable
private fun AlasBootCard(
    provisionState: ProvisionState,
    hostSnapshot: HostSnapshot,
    prootState: ProotSnapshot,
    alasState: AlasRunState,
    lastActivePhase: ProotPhase,
    loadFailed: Boolean,
    pageReady: Boolean,
    onRetryProvision: () -> Unit,
    onRetryStartup: () -> Unit,
) {
    val phase = prootState.phase
    val showProvisionRow = provisionState !is ProvisionState.Ready

    // 「越过哪站」判定与挂机页状态小字同口径（AlasControlPanel 的 when 分支）
    val prepDone = phase == ProotPhase.UPDATING || phase == ProotPhase.STARTING ||
            phase == ProotPhase.RUNNING
    // FAILED 落在准备链上：从没进过热更新/启动（含 sanityCheck 直接败在 IDLE）
    val prepFailed = phase == ProotPhase.FAILED &&
            (lastActivePhase == ProotPhase.IDLE || lastActivePhase == ProotPhase.PREPARING)
    val startFailed = phase == ProotPhase.FAILED && !prepFailed

    val updateResult = prootState.updateResult
    val updateDone = phase == ProotPhase.STARTING || phase == ProotPhase.RUNNING || updateResult != null
    // 热更新失败不阻塞启动：勾照打，只留警告小字（摘要取自 updater 的 SKIPPED 行）
    val updateDegraded = updateDone && updateResult?.startsWith("SKIPPED") == true

    val serviceDone = alasState.reachable && alasState.guiAlive
    // 服务活着但主文档进不来才算行 8 失败；启动链自己挂的 ✗ 在行 5/7
    val pageFailed = loadFailed && (phase == ProotPhase.IDLE || phase == ProotPhase.RUNNING)

    val rows = buildList {
        if (showProvisionRow) {
            add(
                when (val s = provisionState) {
                    is ProvisionState.Extracting -> BootRow(
                        R.string.alas_boot_step_provision,
                        BootRowState.Running,
                        detail = stringResource(
                            R.string.provision_extracting,
                            if (s.totalBytes > 0) (s.doneBytes * 100 / s.totalBytes).toInt() else 0,
                            s.doneBytes / 1_000_000,
                            s.totalBytes / 1_000_000,
                        ),
                    )

                    is ProvisionState.Failed -> BootRow(
                        R.string.alas_boot_step_provision,
                        BootRowState.Failed,
                        detail = stringResource(R.string.provision_failed, s.reason),
                        tone = BootRowTone.Error,
                        onRetry = onRetryProvision,
                    )

                    is ProvisionState.LowDisk -> BootRow(
                        R.string.alas_boot_step_provision,
                        BootRowState.Failed,
                        detail = stringResource(R.string.provision_low_disk, s.freeBytes / 1_000_000),
                        tone = BootRowTone.Error,
                        onRetry = onRetryProvision,
                    )

                    ProvisionState.NotBundled -> BootRow(
                        R.string.alas_boot_step_provision,
                        BootRowState.Failed,
                        detail = stringResource(R.string.provision_not_bundled),
                        tone = BootRowTone.Error,
                        onRetry = onRetryProvision,
                    )

                    // Checking（Ready 时本行不显示）
                    else -> BootRow(R.string.alas_boot_step_provision, BootRowState.Running)
                }
            )
        }
        add(
            BootRow(
                R.string.alas_boot_step_privileged,
                when {
                    hostSnapshot.privilegedConnected -> BootRowState.Done
                    showProvisionRow -> BootRowState.Pending
                    else -> BootRowState.Running
                },
            )
        )
        add(
            BootRow(
                R.string.alas_boot_step_display,
                when {
                    hostSnapshot.vdDisplayId != DefaultDisplayConfig.DISPLAY_NONE -> BootRowState.Done
                    hostSnapshot.privilegedConnected -> BootRowState.Running
                    else -> BootRowState.Pending
                },
            )
        )
        add(
            BootRow(
                R.string.alas_boot_step_bridge,
                when {
                    hostSnapshot.bridgeReachable -> BootRowState.Done
                    hostSnapshot.vdDisplayId != DefaultDisplayConfig.DISPLAY_NONE -> BootRowState.Running
                    else -> BootRowState.Pending
                },
            )
        )
        add(
            BootRow(
                R.string.alas_boot_step_prepare,
                when {
                    prepDone -> BootRowState.Done
                    prepFailed -> BootRowState.Failed
                    phase == ProotPhase.PREPARING -> BootRowState.Running
                    else -> BootRowState.Pending
                },
                detail = when {
                    prepFailed -> prootState.detail
                    phase == ProotPhase.PREPARING -> prootState.detail.ifEmpty { null }
                    else -> null
                },
                tone = if (prepFailed) BootRowTone.Error else BootRowTone.Normal,
                onRetry = if (prepFailed) onRetryStartup else null,
            )
        )
        add(
            BootRow(
                R.string.alas_boot_step_update,
                when {
                    updateDone -> BootRowState.Done
                    phase == ProotPhase.UPDATING -> BootRowState.Running
                    else -> BootRowState.Pending
                },
                detail = when {
                    updateDegraded -> stringResource(R.string.alas_boot_update_degraded, updateResult.orEmpty())
                    // 热更新进行中：实时进度小字（git --progress / CDN 分块旁路，见 ProotHost 轮询）
                    phase == ProotPhase.UPDATING -> prootState.detail.ifEmpty { null }
                    else -> null
                },
                tone = if (updateDegraded) BootRowTone.Warning else BootRowTone.Normal,
            )
        )
        add(
            BootRow(
                R.string.alas_boot_step_service,
                when {
                    serviceDone -> BootRowState.Done
                    startFailed -> BootRowState.Failed
                    phase == ProotPhase.STARTING || phase == ProotPhase.RUNNING || updateDone ->
                        BootRowState.Running

                    else -> BootRowState.Pending
                },
                detail = if (startFailed) prootState.detail else null,
                tone = if (startFailed) BootRowTone.Error else BootRowTone.Normal,
                onRetry = if (startFailed) onRetryStartup else null,
            )
        )
        add(
            BootRow(
                R.string.alas_boot_step_page,
                when {
                    pageReady -> BootRowState.Done
                    pageFailed -> BootRowState.Failed
                    serviceDone -> BootRowState.Running
                    else -> BootRowState.Pending
                },
                detail = if (pageFailed) stringResource(R.string.alas_boot_page_failed) else null,
                tone = if (pageFailed) BootRowTone.Error else BootRowTone.Normal,
                onRetry = if (pageFailed) onRetryStartup else null,
            )
        )
    }

    MaaCard(title = stringResource(R.string.alas_boot_title)) {
        rows.forEach { BootCheckRow(it) }
        LinearProgressIndicator(
            progress = { rows.count { it.state == BootRowState.Done } / rows.size.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BootCheckRow(row: BootRow) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
    ) {
        Box(
            modifier = Modifier.size(MaaDesignTokens.IconSize.md),
            contentAlignment = Alignment.Center,
        ) {
            when (row.state) {
                BootRowState.Done -> Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
                )

                BootRowState.Running -> CircularProgressIndicator(
                    modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
                    strokeWidth = 2.dp,
                )

                BootRowState.Failed -> Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
                )

                BootRowState.Pending -> Box(
                    modifier = Modifier
                        .size(MaaDesignTokens.IconSize.xs)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(row.labelRes),
                style = MaterialTheme.typography.bodyMedium,
                color = when (row.state) {
                    BootRowState.Pending -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            row.detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (row.tone) {
                        BootRowTone.Normal -> MaterialTheme.colorScheme.onSurfaceVariant
                        BootRowTone.Warning -> MaaTheme.palette.warning.content
                        BootRowTone.Error -> MaterialTheme.colorScheme.error
                    },
                )
            }
        }
        row.onRetry?.let {
            TextButton(onClick = it) {
                Text(stringResource(R.string.alas_boot_retry))
            }
        }
    }
}
