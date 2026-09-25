package com.aliothmoon.maafw

import android.app.Application
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.di.AppCoroutineScope
import com.aliothmoon.maafw.di.coreModule
import com.aliothmoon.maafw.di.hostModule
import com.aliothmoon.maafw.di.logModule
import com.aliothmoon.maafw.di.overlayModule
import com.aliothmoon.maafw.di.privilegedModule
import com.aliothmoon.maafw.di.prootModule
import com.aliothmoon.maafw.di.provisionModule
import com.aliothmoon.maafw.di.viewModelModule
import com.aliothmoon.maafw.log.AppLogWriter
import com.aliothmoon.maafw.log.CrashHandler
import com.aliothmoon.maafw.log.LogCleaner
import com.aliothmoon.maafw.log.LogTreeHolder
import com.aliothmoon.maafw.overlay.OverlayController
import com.aliothmoon.maafw.privileged.PermissionManager
import com.aliothmoon.maafw.privileged.RemoteServiceManager
import com.aliothmoon.maafw.proot.AlasRunController
import com.aliothmoon.maafw.service.HostState
import com.aliothmoon.maafw.settings.AppSettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.core.qualifier.named
import timber.log.Timber

class MaaFwApp : Application() {

    private val writer by inject<AppLogWriter>()
    private val settings by inject<AppSettingsManager>()

    override fun onCreate() {
        super.onCreate()
        AppPaths.init(this)
        CrashHandler().install()
        val app = this
        val koin = startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.DEBUG else Level.NONE)
            androidContext(app)
            modules(
                coreModule,
                privilegedModule,
                hostModule,
                logModule,
                overlayModule,
                provisionModule,
                prootModule,
                viewModelModule,
            )
        }.koin
        writer.setup()
        LogTreeHolder(writer).setup()
        koin.get<CoroutineScope>(named<AppCoroutineScope>()).launch {
            settings.loaded.first { it }
            // 自动清理门控：静默执行，失败不挡启动；汇总行由 LogCleaner 自己 Timber.w
            launch(MaaDispatchers.IO) {
                runCatching {
                    if (settings.autoCleanLogs.value) koin.get<LogCleaner>().cleanOutdated()
                }.onFailure { Timber.w(it, "LogCleaner 执行失败") }
            }
            withContext(Dispatchers.Main) { postCreate(koin) }
        }
    }

    fun postCreate(koin: Koin) {
        koin.get<PermissionManager>()
        val provider = koin.get<AppSettingsManager>().startupBackend::value
        RemoteServiceManager.initialize(this, provider)
        koin.get<HostState>().start()
        koin.get<AlasRunController>().start()
        koin.get<OverlayController>().setup()
    }
}
