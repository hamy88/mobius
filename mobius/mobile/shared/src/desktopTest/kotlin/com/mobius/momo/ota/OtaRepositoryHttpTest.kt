package com.mobius.momo.ota

import com.mobius.momo.data.OtaError
import com.mobius.momo.data.createOtaRepositoryWithClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 本地 MockEngine 工厂（生产代码不依赖，仅 desktopTest 验证错误映射）。 */
private fun createMockOtaClient(handler: MockRequestHandler): HttpClient =
    HttpClient(MockEngine(MockEngineConfig().apply { addHandler(handler) })) {
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000L
            requestTimeoutMillis = 10_000L
            socketTimeoutMillis = 10_000L
        }
    }

/**
 * §D6 网络接入测试：用 MockEngine fake Ktor HttpClient，验证 desktop 端 OtaRepository 的
 * 错误映射（404 / timeout / 200 OK 解析 / 解析失败）。
 *
 * 覆盖：
 * 1. 200 OK + 合法 JSON → OtaRelease / OtaManifest 正确反序列化
 * 2. 200 OK + CHANGELOG body → changelogItems 自动解析
 * 3. 404 → OtaError.NotFound
 * 4. 500 → OtaError.Other
 * 5. 200 OK + 非法 JSON → OtaError.ParseError
 * 6. empty body → OtaError.ParseError
 */
class OtaRepositoryHttpTest {

    @Test
    fun `200 OK on releases_latest returns parsed release with changelogItems`() = runBlocking {
        val releaseJson = """
            {
              "tag_name": "mobile-v0.4.0",
              "version_code": 24,
              "version_name": "0.4.0",
              "published_at": "2026-09-19T12:00:00+08:00",
              "prerelease": false,
              "body": "**Feature:** New OTA prompt\n**Fix:** Cold start crash",
              "manifest": null
            }
        """.trimIndent()
        val client = createMockOtaClient { request ->
            assertTrue(
                request.url.toString().endsWith("/releases/latest"),
                "应请求 /releases/latest，actual=${request.url}",
            )
            respond(
                content = releaseJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = createOtaRepositoryWithClient(client)
        val release = repo.fetchLatestRelease("hamy88/mobius")
        assertEquals("mobile-v0.4.0", release.tagName)
        assertEquals(24, release.versionCode)
        assertEquals("0.4.0", release.versionName)
        assertEquals(2, release.changelogItems?.size, "CHANGELOG body 应自动解析为 2 项")
        assertEquals("New OTA prompt", release.changelogItems!![0].text)
    }

    @Test
    fun `200 OK on ota_manifest_json returns parsed OtaManifest`() = runBlocking {
        val manifestJson = """
            {
              "version": "0.4.0",
              "version_code": 24,
              "channel": "stable",
              "android": { "signature_scheme": ["v2","v3"] }
            }
        """.trimIndent()
        val client = createMockOtaClient { request ->
            assertTrue(request.url.toString().endsWith("/0.4.0/ota-manifest.json"))
            respond(
                content = manifestJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = createOtaRepositoryWithClient(client)
        val manifest = repo.fetchManifestJson("hamy88/mobius", "0.4.0")
        assertEquals("0.4.0", manifest.version)
        assertEquals(listOf("v2", "v3"), manifest.android.signatureScheme)
    }

    @Test
    fun `404 on releases_latest yields OtaError_NotFound`() = runBlocking {
        val client = createMockOtaClient { _ ->
            respondError(HttpStatusCode.NotFound)
        }
        val repo = createOtaRepositoryWithClient(client)
        val ex = assertFailsWith<OtaError.NotFound> {
            repo.fetchLatestRelease("hamy88/mobius")
        }
        assertEquals("OtaError.NotFound", ex.toString())
    }

    @Test
    fun `500 on manifest yields OtaError_Other`() = runBlocking {
        val client = createMockOtaClient { _ ->
            respondError(HttpStatusCode.InternalServerError)
        }
        val repo = createOtaRepositoryWithClient(client)
        val ex = assertFailsWith<OtaError.Other> {
            repo.fetchManifestJson("hamy88/mobius", "0.4.0")
        }
        assertTrue(ex.message!!.contains("500"))
    }

    @Test
    fun `malformed JSON in releases_latest yields ParseError`() {
        runBlocking {
            val client = createMockOtaClient { _ ->
                respond(
                    content = "this is not valid json",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val repo = createOtaRepositoryWithClient(client)
            assertFailsWith<OtaError.ParseError> {
                repo.fetchLatestRelease("hamy88/mobius")
            }
        }
    }

    @Test
    fun `empty body in releases_latest yields ParseError`() {
        runBlocking {
            val client = createMockOtaClient { _ ->
                respond(
                    content = " ",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val repo = createOtaRepositoryWithClient(client)
            assertFailsWith<OtaError.ParseError> {
                repo.fetchLatestRelease("hamy88/mobius")
            }
        }
    }
}