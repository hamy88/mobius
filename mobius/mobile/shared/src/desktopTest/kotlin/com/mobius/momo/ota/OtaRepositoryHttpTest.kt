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
 * 覆盖（v0.4.2 起 — /releases/latest → /releases 数组切换 + 本服务器 channel）:
 * 1. 200 OK + releases 数组 + 首条为最新 → OtaRelease 正确反序列化, changelogItems 自动解析
 * 2. draft=true 的 release 应被过滤（不会被选作 latest）
 * 3. prerelease=true 的 release 不被过滤（allow prerelease）
 * 4. 200 OK + ota-manifest.json 路径 → OtaManifest 解析
 * 5. 404 → OtaError.NotFound
 * 6. 500 → OtaError.Other
 * 7. 200 OK + 非法 JSON → OtaError.ParseError
 * 8. empty body → OtaError.ParseError
 * 9. fetchLocalManifest: 200 + 合法 OtaManifest JSON → 返回 OtaManifest
 * 10. fetchLocalManifest: 任意 HTTP 错误 → 返回 null (不抛错, 留给上层 fallback)
 */
class OtaRepositoryHttpTest {

    @Test
    fun `releases array with valid drafts false returns parsed release with changelogItems`() = runBlocking<Unit> {
        val releasesArray = """
            [
              { "tag_name": "mobile-v0.3.1", "draft": false, "published_at": "2026-09-01T00:00:00Z" },
              {
                "tag_name": "mobile-v0.4.0",
                "draft": false,
                "prerelease": true,
                "published_at": "2026-09-19T12:00:00+08:00",
                "version_code": 24,
                "version_name": "0.4.0",
                "body": "**Feature:** New OTA prompt\n**Fix:** Cold start crash",
                "manifest": null
              }
            ]
        """.trimIndent()
        val client = createMockOtaClient { request ->
            assertTrue(
                request.url.toString().contains("/releases?per_page=10"),
                "应请求 /releases?per_page=10，actual=${request.url}",
            )
            respond(
                content = releasesArray,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = createOtaRepositoryWithClient(client)
        val release = repo.fetchLatestRelease("hamy88/mobius")
        assertEquals("mobile-v0.4.0", release.tagName, "应取最新 (published_at 最大的) release")
        assertEquals(24, release.versionCode)
        assertEquals("0.4.0", release.versionName)
        assertEquals(2, release.changelogItems?.size, "CHANGELOG body 应自动解析为 2 项")
        assertEquals("New OTA prompt", release.changelogItems!![0].text)
    }

    @Test
    fun `draft true release is filtered out`() = runBlocking<Unit> {
        // 数组里只有 draft=true 的 (永远不应被选中); parseLatestNonDraftRelease 应抛 NotFound.
        val releasesArray = """
            [
              { "tag_name": "draft-1", "draft": true, "published_at": "2026-09-19T12:00:00Z" }
            ]
        """.trimIndent()
        val client = createMockOtaClient { _ ->
            respond(releasesArray, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val repo = createOtaRepositoryWithClient(client)
        assertFailsWith<OtaError.NotFound> {
            repo.fetchLatestRelease("hamy88/mobius")
        }
    }

    @Test
    fun `prerelease true release is NOT filtered`() = runBlocking<Unit> {
        // fork 仓库只发 prerelease; /releases/latest 在 GitHub 上仍返回 404, 但 /releases 数组
        // 能正确拿到; 且 draft=false 应被允许通过过滤.
        val releasesArray = """
            [
              { "tag_name": "mobile-v0.4.2-ci", "draft": false, "prerelease": true,
                "published_at": "2026-09-20T10:00:00Z",
                "version_code": 26, "version_name": "0.4.2", "body": "" }
            ]
        """.trimIndent()
        val client = createMockOtaClient { _ ->
            respond(releasesArray, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val repo = createOtaRepositoryWithClient(client)
        val release = repo.fetchLatestRelease("hamy88/mobius")
        assertEquals("mobile-v0.4.2-ci", release.tagName)
        assertEquals(26, release.versionCode)
    }

    @Test
    fun `200 OK on ota_manifest_json returns parsed OtaManifest`() = runBlocking<Unit> {
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
    fun `404 on releases yields OtaError_NotFound`() = runBlocking<Unit> {
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
    fun `500 on manifest yields OtaError_Other`() = runBlocking<Unit> {
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
    fun `malformed JSON in releases yields ParseError`() {
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
    fun `empty body in releases yields ParseError`() {
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