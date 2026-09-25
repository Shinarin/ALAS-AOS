package com.aliothmoon.maafw.domain

import kotlinx.serialization.Serializable

enum class ThemeMode { System, Light, Dark }

/** 持久化聚合根：只存用户选择；schemaVersion 在序列化层 */
@Serializable
data class UserConfiguration(
    val themeMode: ThemeMode = ThemeMode.System,
)
