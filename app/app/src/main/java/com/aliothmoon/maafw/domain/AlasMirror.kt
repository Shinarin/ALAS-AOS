package com.aliothmoon.maafw.domain

/**
 * ALAS 镜像源档位：热更新 git 仓库 / CDN pack / pip 镜像一起切
 *
 * CN 为现状默认（123pan CDN pack + git.lyoko.io 兜底 + 阿里云 pip）；
 * GITHUB 直连上游（无 CDN，pypi.org）
 */
enum class AlasMirror(
    /** seeds/seed_config.py 的 ALASAOS_MIRROR 入参 */
    val seedValue: String,
    /** ALASAOS_UPDATE_REPO 覆盖值 */
    val updateRepo: String,
    /** true = 跳过 CDN pack 通道（置 ALASAOS_UPDATE_NO_CDN） */
    val noCdn: Boolean,
    /** ALASAOS_PYPI_MIRROR 覆盖值（env_fix.sh 的 pip -i） */
    val pypiMirror: String,
) {
    CN(
        seedValue = "cn",
        updateRepo = "git://git.lyoko.io/AzurLaneAutoScript",
        noCdn = false,
        pypiMirror = "https://mirrors.aliyun.com/pypi/simple",
    ),
    GITHUB(
        seedValue = "github",
        updateRepo = "https://github.com/LmeSzinc/AzurLaneAutoScript",
        noCdn = true,
        pypiMirror = "https://pypi.org/simple",
    ),
}
