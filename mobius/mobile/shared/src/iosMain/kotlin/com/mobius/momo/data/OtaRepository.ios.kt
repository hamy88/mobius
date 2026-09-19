package com.mobius.momo.data

/**
 * iOS 端 OTA 仓库实现。
 *
 * iOS 本期不在 v1.1 范围内（方案 v1.1 收窄为 Android only）；保留契约入口与 NoOp，
 * 避免 commonMain 调用方出现 platform-specific 异常。
 */
actual fun createOtaRepository(): OtaRepository = NoOpOtaRepository()

private class NoOpOtaRepository : OtaRepository {
    override suspend fun fetchLatestRelease(repo: String): OtaRelease =
        error("iOS OTA 未启用（v1.1 Android only）")

    override suspend fun fetchManifestJson(repo: String, version: String): OtaManifest =
        error("iOS OTA 未启用（v1.1 Android only）")
}
