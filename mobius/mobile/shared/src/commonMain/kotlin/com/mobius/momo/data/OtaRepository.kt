package com.mobius.momo.data

/**
 * OTA 仓库抽象（commonMain expect/actual）。
 *
 * 客户端调用：
 * - [fetchLatestRelease] 拉 GitHub Releases `/releases/latest`（或 mobile-builds/manifest.json 直读）
 * - [fetchManifestJson]   拉具体版本的 manifest.json；与 fetchLatestRelease 解耦便于重试
 *
 * 平台实现：
 * - androidMain: OkHttp（与现有 [createMobiusHttpClient] 同源）
 * - iosMain:     NSURLSession（[OtaRepository.ios.kt]）
 * - desktopMain: java.net.HttpURLConnection（无三方依赖，便于 desktopTest 跑 fake 替换）
 *
 * 注意：开发期可用 httpbin / 本地 mock 测连通性；本期不实装真实网络，生产实装在 D6/D7 阶段。
 */
interface OtaRepository {
    /** 拉取远端最新 release 元数据。 */
    suspend fun fetchLatestRelease(repo: String): OtaRelease

    /** 拉取具体版本的 manifest.json。 */
    suspend fun fetchManifestJson(repo: String, version: String): OtaManifest
}

expect fun createOtaRepository(): OtaRepository

/** 默认仓库源（fork = hamy88/mobius；上游 = mobius-system/mobius；可被环境变量覆盖）。 */
const val DEFAULT_OTA_REPO = "hamy88/mobius"
