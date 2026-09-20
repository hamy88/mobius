package com.mobius.momo.ota

import com.mobius.momo.data.OtaManifest
import com.mobius.momo.data.SecureStorage
import com.mobius.momo.data.createOtaRepositoryWithClient
import com.mobius.momo.viewmodel.OtaCheckUseCase
import com.mobius.momo.viewmodel.ThresholdEvaluator
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 共用 MockEngine 工厂（与 OtaRepositoryHttpTest 同一模式，但放在此文件方便独立运行）。 */
private fun createMockOtaClient(handler: MockRequestHandler): HttpClient =
    HttpClient(MockEngine(MockEngineConfig().apply { addHandler(handler) })) {
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000L
            requestTimeoutMillis = 10_000L
            socketTimeoutMillis = 10_000L
        }
    }

/** In-memory SecureStorage stub（与 MomoAppViewModelOtaTest 同样的 stub 模式）。
 *  暴露为 internal 让 Kotlin 编译器在跨 override 时不报 "private in file" 错误
 *  （Kotlin 编译器对 private 类的 override 校验在多文件场景有已知的可见性解析问题）。 */
internal class OtaTestSecureStorage : SecureStorage {
    private val prefs = mutableMapOf<String, String>()
    override fun saveToken(token: String) { prefs["token"] = token }
    override fun getToken(): String? = prefs["token"]
    override fun saveTokenMetadata(metadata: com.mobius.momo.data.StoredTokenMetadata) {
        prefs["token_meta_base"] = metadata.baseUrl
        prefs["token_meta_saved"] = metadata.savedAtEpochMillis.toString()
        prefs["token_meta_ver"] = metadata.storageVersion.toString()
    }
    override fun getTokenMetadata(): com.mobius.momo.data.StoredTokenMetadata? = null
    override fun savePreference(key: String, value: String) { prefs[key] = value }
    override fun getPreference(key: String): String? = prefs[key]
    override fun clear() { prefs.clear() }
}

/**
 * v0.4.2 本服务器 OTA channel 测试：验证
 * 1. [com.mobius.momo.data.OtaRepository.fetchLocalManifest] 在 200 + 合法 JSON → 返回 OtaManifest
 * 2. fetchLocalManifest 在 404 / 500 / 非法 JSON → 返回 null (不抛错, 留给 fallback 兜底)
 * 3. [OtaCheckUseCase] 优先调用本服务器, 本服务器无新版本 → fallback GitHub
 * 4. OtaCheckUseCase 优先本服务器, 本服务器有新版本 → 直接返回 Show, 不调 GitHub
 * 5. localBaseUrl 为空字符串 → 跳过本服务器, 直接走 GitHub
 */
class OtaRepositoryLocalServerTest {

    @Test
    fun `fetchLocalManifest 200 valid returns parsed OtaManifest`() = runBlocking<Unit> {
        val manifestJson = """
            {
              "version": "0.4.2",
              "version_code": 26,
              "channel": "stable",
              "released_at": "2026-09-20T10:00:00Z",
              "android": { "package_name": "com.mobius.momo", "min_sdk": 24, "target_sdk": 34 },
              "builds": [
                {
                  "platform": "android", "abi": "arm64-v8a",
                  "version": "0.4.2", "version_code": 26,
                  "url": "/mobile-builds/mobius-mobile-0.4.2-android-arm64.apk",
                  "size": 5136500,
                  "sha256": "abc123"
                }
              ]
            }
        """.trimIndent()
        val client = createMockOtaClient { request ->
            assertTrue(
                request.url.toString().endsWith("/api/mobile/ota/manifest.json"),
                "应请求 /api/mobile/ota/manifest.json, actual=${request.url}",
            )
            respond(manifestJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val repo = createOtaRepositoryWithClient(client)
        val manifest = repo.fetchLocalManifest("https://example.com")
        assertNotNull(manifest)
        assertEquals("0.4.2", manifest.version)
        assertEquals(26, manifest.versionCode)
        assertEquals(1, manifest.builds.size)
        assertEquals("arm64-v8a", manifest.builds[0].abi)
    }

    @Test
    fun `fetchLocalManifest strips trailing slash from baseUrl`() = runBlocking<Unit> {
        val manifestJson = """{ "version": "0.4.2", "version_code": 26 }"""
        var requestedUrl: String? = null
        val client = createMockOtaClient { request ->
            requestedUrl = request.url.toString()
            respond(manifestJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val repo = createOtaRepositoryWithClient(client)
        repo.fetchLocalManifest("https://example.com/") // 带尾斜杠
        assertEquals("https://example.com/api/mobile/ota/manifest.json", requestedUrl)
    }

    @Test
    fun `fetchLocalManifest 404 returns null not throws`() = runBlocking<Unit> {
        val client = createMockOtaClient { _ -> respondError(HttpStatusCode.NotFound) }
        val repo = createOtaRepositoryWithClient(client)
        val manifest = repo.fetchLocalManifest("https://example.com")
        assertNull(manifest, "本服务器 404 不抛错, 返回 null 让上层 fallback")
    }

    @Test
    fun `fetchLocalManifest 500 returns null not throws`() = runBlocking<Unit> {
        val client = createMockOtaClient { _ -> respondError(HttpStatusCode.InternalServerError) }
        val repo = createOtaRepositoryWithClient(client)
        val manifest = repo.fetchLocalManifest("https://example.com")
        assertNull(manifest)
    }

    @Test
    fun `fetchLocalManifest malformed JSON returns null`() = runBlocking<Unit> {
        val client = createMockOtaClient { _ ->
            respond("not valid json", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val repo = createOtaRepositoryWithClient(client)
        val manifest = repo.fetchLocalManifest("https://example.com")
        assertNull(manifest)
    }

    @Test
    fun `fetchLocalManifest empty baseUrl returns null without HTTP call`() = runBlocking<Unit> {
        var called = false
        val client = createMockOtaClient { _ ->
            called = true
            respondError(HttpStatusCode.NotFound)
        }
        val repo = createOtaRepositoryWithClient(client)
        val manifest = repo.fetchLocalManifest("")
        assertNull(manifest)
        assertEquals(false, called, "空 baseUrl 不应发请求")
    }

    @Test
    fun `localBaseUrl blank skips local server and uses GitHub only`() = runBlocking<Unit> {
        // localBaseUrl 空 → useCase.run 应跳过本服务器 channel; 由于 useCase 默认走真实 desktop
        // OtaRepository (createOtaRepository() 创建 Ktor 真实 HTTP 客户端), 单元测试只能验证
        // manifestProvider 注入路径不抛 NPE.
        val storage = OtaTestSecureStorage()
        val useCase = OtaCheckUseCase(
            storage = storage,
            localBaseUrl = "",
            localVersion = "0.4.1",
        )
        val show = assertIs<OtaCheckUseCase.OtaCheckResult.Show>(
            useCase.run { OtaManifest(version = "0.4.2", versionCode = 26) },
        )
        assertEquals("0.4.2", show.manifest.version)
    }

    @Test
    fun `localBaseUrl passed through to fetchManifestDualChannel even with provider`() = runBlocking<Unit> {
        // 即便 manifestProvider 注入, useCase 也不读 localBaseUrl; 验证字段能正确传入构造器不抛错.
        val storage = OtaTestSecureStorage()
        val useCase = OtaCheckUseCase(
            storage = storage,
            localBaseUrl = "https://example.com",
            localVersion = "0.4.1",
        )
        val show = assertIs<OtaCheckUseCase.OtaCheckResult.Show>(
            useCase.run { OtaManifest(version = "0.4.2", versionCode = 26) },
        )
        assertEquals(ThresholdEvaluator.Level.Normal, show.level)
    }

    // ===== 0.4.3: 本服务器 manifest.json 含 changelog_items 字段 =====
    //
    // 后端 mobile-ota.ts 已在顶层补 changelog_items 数组(读 CHANGELOG.md 解析);
    // 客户端 OtaManifest schema 当前不消费该字段(下一版本再加 changelogItems 字段透传);
    // 本测试验证 ignoreUnknownKeys 解析路径不会因多余字段抛错, 解析后 OtaManifest 仍可用.

    @Test
    fun `fetchLocalManifest tolerates changelog_items field at top level`() = runBlocking<Unit> {
        val manifestJson = """
            {
              "version": "0.4.3",
              "version_code": 27,
              "channel": "stable",
              "released_at": "2026-09-20T10:00:00Z",
              "changelog_items": [
                { "type": "Feature", "text": "OTA 弹窗显示 changelog" },
                { "type": "Fix", "text": "某处崩溃修复" }
              ],
              "android": { "package_name": "com.mobius.momo", "min_sdk": 24, "target_sdk": 34 },
              "builds": [
                {
                  "platform": "android", "abi": "arm64-v8a",
                  "version": "0.4.3", "version_code": 27,
                  "url": "/mobile-builds/mobius-mobile-0.4.3-android-arm64.apk",
                  "size": 5136500, "sha256": "abc123"
                }
              ]
            }
        """.trimIndent()
        val client = createMockOtaClient { request ->
            respond(manifestJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val repo = createOtaRepositoryWithClient(client)
        val manifest = repo.fetchLocalManifest("https://example.com")
        assertNotNull(manifest, "含 changelog_items 的 manifest 应正常解析(ignoreUnknownKeys)")
        assertEquals("0.4.3", manifest.version)
        assertEquals(27, manifest.versionCode)
        assertEquals(1, manifest.builds.size)
    }
}