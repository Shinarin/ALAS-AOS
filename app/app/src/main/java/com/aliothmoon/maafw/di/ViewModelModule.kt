package com.aliothmoon.maafw.di

import com.aliothmoon.maafw.log.AlasErrorDetailViewModel
import com.aliothmoon.maafw.log.AlasLogViewModel
import com.aliothmoon.maafw.log.AppLogViewModel
import com.aliothmoon.maafw.log.LogTailViewModel
import com.aliothmoon.maafw.settings.SettingsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val viewModelModule = module {
    viewModelOf(::AppLogViewModel)
    viewModelOf(::LogTailViewModel)
    viewModelOf(::AlasLogViewModel)
    viewModelOf(::AlasErrorDetailViewModel)

    viewModel {
        SettingsViewModel(get(), get(), get(), get())
    }
}
