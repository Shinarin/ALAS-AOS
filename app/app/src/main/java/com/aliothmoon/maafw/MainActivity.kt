package com.aliothmoon.maafw

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aliothmoon.maafw.proot.AlasRunController
import com.aliothmoon.maafw.settings.AppSettingsManager
import com.aliothmoon.maafw.ui.AppRoot
import com.aliothmoon.maafw.ui.alas.AlasWebViewHolder
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : AppCompatActivity() {

    private val appSettings: AppSettingsManager by inject()
    private val alasController: AlasRunController by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { !appSettings.loaded.value }
        super.onCreate(savedInstanceState)

        // 挂机/工具运行期间保持屏幕唤醒（App 退到后台或用户手动息屏时仍允许锁屏）
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                alasController.state.collect { s ->
                    if (s.runnerAlive || s.toolAlive) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }

        setContent {
            AppRoot(onDarkThemeChanged = ::applyEdgeToEdge)
        }
    }

    override fun onStart() {
        super.onStart()
        // 回前台：恢复 ALAS WebUI 的 WebView（退后台时被 pauseAll 停过）
        AlasWebViewHolder.resumeAll()
    }

    override fun onStop() {
        // App 整体退后台：停 WebView 渲染与 JS 定时器，省 CPU/电量
        AlasWebViewHolder.pauseAll()
        super.onStop()
    }

    private fun applyEdgeToEdge(darkMode: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }
}
