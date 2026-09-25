pluginManagement {
    // 构建约定插件（maafw.*）在这个独立构建里，模块脚本只按 id 应用
    includeBuild("build-logic")
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.google.devtools.ksp") {
                // KSP marker 工件（com.google.devtools.ksp.gradle.plugin）在 fresh 环境
                // （CI/空仓）全源判 not found（机制未明，2026-09-25 GHA+本地空仓同现），
                // 绕过 marker 直拉 impl module——普通库工件，Central/AliyunCentral 稳定有货
                useModule("com.google.devtools.ksp:symbol-processing-gradle-plugin:${requested.version}")
            }
        }
    }
    repositories {
        mavenLocal()
        // 大陆网络环境 dl.google.com 偶发握手中断，Aliyun 镜像优先、官方源兜底
        maven {
            name = "AliyunGoogle"
            url = uri("https://maven.aliyun.com/repository/google")
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
                // Google Maven 的 KSP 停在 1.5.30-1.0.0（2.x 只发 Maven Central/插件门户）：
                // com.google.devtools.* 走 Google 系必 404，且实测会毒化整条解析链
                // （空仓/CI 全灭），排除后落 AliyunCentral/Central 解析
                excludeGroupByRegex("com\\.google\\.devtools.*")
            }
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
                excludeGroupByRegex("com\\.google\\.devtools.*")
            }
        }
        maven {
            name = "AliyunCentral"
            url = uri("https://maven.aliyun.com/repository/central")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            name = "AliyunGoogle"
            url = uri("https://maven.aliyun.com/repository/google")
        }
        google()
        maven {
            name = "AliyunCentral"
            url = uri("https://maven.aliyun.com/repository/central")
        }
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "MaaFwApp"
include(":app")
include(":hidden-api")
// Preferences DataStore 的 schema 代码生成（@PrefSchema / @PrefKey）
include(":annotation-api")
include(":ksp-processor")
