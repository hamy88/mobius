package com.mobius.momo.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Desktop 端 OTA 仓库实现（D6 网络接入）。
 *
 * 真实端点：
 * - [fetchLatestRelease] → `GET https://api.github.com/repos/{repo}/releases?per_page=10`
 *                          (数组形式: 过滤 draft=false, 按 published_at 倒序取第一条)
 * - [fetchManifestJson]  → `GET https://raw.githubusercontent.com/{repo}/{version}/ota-manifest.json`
 * - [fetchLocalManifest] → `GET {baseUrl}/api/mobile/ota/manifest.json`
 *                          (本服务器优先; 失败 → null, 调用方 fallback 到 GitHub)
 *
 * 设计意图：
 * - 复用现有 [createMobiusHttpClient] OkHttp 引擎，便于测试 fake 替换（见 desktopTest）。
 * - 用户代理 `momo-desktop/<versionCode>` 便于服务端日志统计。
 * - 错误映射（§10.5.5 + §6）：
 *   - HTTP 404 → [OtaError.NotFound]
 *   - 超时（>10s）→ [OtaError.Timeout]
 *   - 解析失败 → [OtaError.ParseError]
 *   - 其他 → [OtaError.Other]
 *
 * iOS NoOp / Desktop 实装分支由 expect/actual 自动选择（[createOtaRepository]）。
 */
actual fun createOtaRepository(): OtaRepository = DesktopOtaRepository()

/** 测试 / Mock 入口：允许注入 MockEngine（desktopTest 用）。 */
internal fun createOtaRepositoryWithClient(client: HttpClient): OtaRepository =
    DesktopOtaRepository(client = client)

private const val OTA_REQUEST_TIMEOUT_MILLIS: Long = 10_000L

private class DesktopOtaRepository(
    private val client: HttpClient = createMobiusHttpClient(onUnauthorized = {}),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true },
    private val versionCodeProvider: () -> Int = { 0 },
) : OtaRepository {

    override suspend fun fetchLatestRelease(repo: String): OtaRelease {
        // 用 /releases?per_page=10 替代 /releases/latest: 纯 prerelease 仓库 /releases/latest 返回 404。
        val url = "https://api.github.com/repos/$repo/releases?per_page=10"
        val raw = safeRequest(url)
        return try {
            parseLatestNonDraftRelease(raw)
        } catch (e: OtaError) {
            throw e
        } catch (e: SerializationException) {
            throw OtaError.ParseError("releases/latest body is not valid OtaRelease JSON: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw OtaError.ParseError("releases/latest body missing required fields: ${e.message}", e)
        }
    }

    override suspend fun fetchManifestJson(repo: String, version: String): OtaManifest {
        val url = "https://raw.githubusercontent.com/$repo/$version/ota-manifest.json"
        val raw = safeRequest(url)
        return try {
            json.decodeFromString(OtaManifest.serializer(), raw)
        } catch (e: SerializationException) {
            throw OtaError.ParseError("manifest.json is not valid OtaManifest JSON: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw OtaError.ParseError("manifest.json missing required fields: ${e.message}", e)
        }
    }

    override suspend fun fetchLocalManifest(baseUrl: String): OtaManifest? {
        val normalized = baseUrl.trim().trimEnd('/')
        if (normalized.isBlank()) return null
        val url = "$normalized$LOCAL_OTA_MANIFEST_PATH"
        val raw = try {
            safeRequest(url)
        } catch (e: Throwable) {
            // 本服务器 channel 任意失败 → 静默回退到 GitHub, 不抛错。
            return null
        }
        return try {
            json.decodeFromString(OtaManifest.serializer(), raw)
        } catch (e: Throwable) {
            null
        }
    }

    /** 共享的 GET + 错误映射逻辑。 */
    private suspend fun safeRequest(url: String): String {
        val ua = "momo-desktop/${versionCodeProvider()}"
        return try {
            val response = client.get(url) {
                header(HttpHeaders.UserAgent, ua)
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            }
            val status = response.status
            when {
                status == HttpStatusCode.NotFound -> throw OtaError.NotFound()
                status.value in 200..299 -> response.bodyAsText()
                else -> throw OtaError.Other("HTTP $status on $url", null)
            }
        } catch (e: OtaError) {
            throw e
        } catch (e: HttpRequestTimeoutException) {
            throw OtaError.Timeout(e)
        } catch (e: java.net.SocketTimeoutException) {
            throw OtaError.Timeout(e)
        } catch (e: java.io.IOException) {
            throw OtaError.Other("network IO failed: ${e.message}", e)
        } catch (e: Exception) {
            throw OtaError.Other("unexpected error: ${e.message}", e)
        }
    }
}