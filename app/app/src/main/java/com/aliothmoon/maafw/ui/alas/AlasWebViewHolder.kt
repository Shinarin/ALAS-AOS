package com.aliothmoon.maafw.ui.alas

import android.webkit.WebView

/**
 * ALAS WebUI 的 WebView 注册处：App 整体退后台时统一暂停，回前台恢复
 *
 * 只挂在 MainActivity 的 onStart/onStop 上：切 tab 不暂停（pager 三页常驻
 * 是 v0.1.5 修黑屏的决策，切页时 pywebio 不该断线）
 *
 * 全部方法只在主线程调：register/unregister 在 AndroidView 的 factory/onRelease，
 * pause/resume 在 Activity 生命周期回调，天然同线程，不必加锁
 */
object AlasWebViewHolder {

    private val webViews = mutableSetOf<WebView>()

    /** pauseTimers 是进程全局语义；用标志位记住当前该不该处于暂停态 */
    private var paused = false

    fun register(webView: WebView) {
        webViews += webView
        // Activity 重建窗口里 resumeAll 可能跑在空集合上（全局暂停残留），
        // 新实例注册时按标志位对齐：该停则停，不该停就清掉残留的全局暂停
        if (paused) {
            webView.onPause()
            webView.pauseTimers()
        } else {
            webView.resumeTimers()
        }
    }

    fun unregister(webView: WebView) {
        webViews -= webView
    }

    /** pauseTimers 是 WebView 全局的；onPause 才是逐实例的，两个都调才算真停 */
    fun pauseAll() {
        paused = true
        webViews.forEach {
            it.onPause()
            it.pauseTimers()
        }
    }

    fun resumeAll() {
        paused = false
        webViews.forEach {
            it.onResume()
            it.resumeTimers()
        }
    }
}
