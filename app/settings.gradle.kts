pluginManagement {
    // 构建约定插件（maafw.*）在这个独立构建里，模块脚本只按 id 应用
    includeBuild("build-logic")
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
            }
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
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
