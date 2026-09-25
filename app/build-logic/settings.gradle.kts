// build-logic 是独立构建（settings.gradle.kts 里 includeBuild），
// 主构建的版本目录不会自动带过来，这里显式指同一份 libs.versions.toml
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        // 与主 settings 同理：dl.google.com 大陆偶发握手中断（2026-09-25 空仓复现期实测 15s 超时），
        // Aliyun 镜像优先、官方源兜底；KSP 类 com.google.devtools.* 不走 Google 系（停更于 1.5.30）
        maven {
            name = "AliyunGoogle"
            url = uri("https://maven.aliyun.com/repository/google")
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
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
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
include(":convention")
