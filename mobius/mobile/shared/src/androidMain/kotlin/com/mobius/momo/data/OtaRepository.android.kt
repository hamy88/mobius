package com.mobius.momo.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
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
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Android 端 OTA 仓库实现（D6 网络接入）。
 *
 * 真实端点：
 * - [fetchLatestRelease] → `GET https://api.github.com/repos/{repo}/releases?per_page=10`
 *                          (数组形式: 过滤 draft=false, 取 published_at 最新一条)
 * - [fetchManifestJson]  → `GET https://raw.githubusercontent.com/{repo}/{version}/ota-manifest.json`
 * - [fetchLocalManifest] → `GET {baseUrl}/api/mobile/ota/manifest.json`
 *                          (本服务器优先; 失败 → null, 调用方 fallback 到 GitHub)
 *
 * 传输层：Ktor HttpClient + OkHttp engine（与现有 [createMobiusHttpClient] 共用 OkHttp 4.x）。
 * 这样在不引入新三方依赖的前提下满足 §6 "OkHttp 4.x 传输层" 要求，且便于 desktopTest 用 MockEngine
 * fake 替换共享逻辑。
 *
 * 错误映射（§6 + §10.5.5）：
 * - HTTP 404                → [OtaError.NotFound]
 * - 超时（>10s）/ SocketTimeoutException → [OtaError.Timeout]
 * - IOException / 其他      → [OtaError.Other]
 * - JSON 反序列化失败        → [OtaError.ParseError]
 *
 * User-Agent: `momo-mobile/<versionCode>`，便于服务端统计客户端版本分布。
 */
actual fun createOtaRepository(): OtaRepository = AndroidOtaRepository()

/** 测试 / Mock 入口：允许注入自定义 HttpClient（未来 androidTest 可复用）。 */
internal fun createOtaRepositoryWithClient(client: HttpClient): OtaRepository =
    AndroidOtaRepository(client = client)

private const val OTA_REQUEST_TIMEOUT_MILLIS: Long = 10_000L

private class AndroidOtaRepository(
    private val client: HttpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = OTA_REQUEST_TIMEOUT_MILLIS
            requestTimeoutMillis = OTA_REQUEST_TIMEOUT_MILLIS
            socketTimeoutMillis = OTA_REQUEST_TIMEOUT_MILLIS
        }
    },
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true },
    private val versionCodeProvider: () -> Int = { 0 },
) : OtaRepository {

    override suspend fun fetchLatestRelease(repo: String): OtaRelease {
        // 用 /releases?per_page=10 替代 /releases/latest: 仓库若只发布 prerelease (如 fork ci/* 构建),
        // GitHub /releases/latest 会返回 404; 数组端点可同时拿到 draft=false 的 stable + prerelease,
        // 我们自己按 published_at 取最新一条 (含 prerelease)。
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

    /** 共享的 GET + 错误映射逻辑（Android 端无 MockEngine 复用，仅在此处独立）。 */
    private suspend fun safeRequest(url: String): String {
        val ua = "momo-mobile/${versionCodeProvider()}"
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
        } catch (e: SocketTimeoutException) {
            throw OtaError.Timeout(e)
        } catch (e: IOException) {
            throw OtaError.Other("network IO failed: ${e.message}", e)
        } catch (e: Exception) {
            throw OtaError.Other("unexpected error: ${e.message}", e)
        }
    }
}