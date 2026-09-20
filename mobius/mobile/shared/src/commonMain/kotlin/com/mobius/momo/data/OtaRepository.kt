package com.mobius.momo.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * OTA 仓库抽象（commonMain expect/actual）。
 *
 * 客户端调用：
 * - [fetchLatestRelease] 拉 GitHub Releases `/releases?per_page=10`（数组，过滤 draft=false，取最新 prerelease/stable）
 * - [fetchManifestJson]   拉具体版本的 manifest.json；与 fetchLatestRelease 解耦便于重试
 * - [fetchLocalManifest]  拉本服务器 `/api/mobile/ota/manifest.json`；与 fetchLatestRelease 解耦便于本服务器优先策略
 *
 * 平台实现：
 * - androidMain: OkHttp（与现有 [createMobiusHttpClient] 同源）
 * - iosMain:     NSURLSession（[OtaRepository.ios.kt]）
 * - desktopMain: java.net.HttpURLConnection（无三方依赖，便于 desktopTest 跑 fake 替换）
 *
 * 注意：开发期可用 httpbin / 本地 mock 测连通性；本期不实装真实网络，生产实装在 D6/D7 阶段。
 */
interface OtaRepository {
    /** 拉取远端最新 release 元数据（GitHub Releases 列表首条 non-draft）。 */
    suspend fun fetchLatestRelease(repo: String): OtaRelease

    /** 拉取具体版本的 manifest.json。 */
    suspend fun fetchManifestJson(repo: String, version: String): OtaManifest

    /**
     * 拉取本服务器 OTA manifest（[baseUrl] 例如 `https://example.com`）。
     *
     * 端点：`GET {baseUrl.trimEnd('/')}/api/mobile/ota/manifest.json`
     *
     * 返回：成功 → [OtaManifest]；网络/HTTP 错误或解析失败 → null（**不抛错**，由调用方决定是否 fallback）。
     * 渠道设计：本服务器 channel 任意失败都视为"无新版本"回退到 GitHub，
     * 不应让本服务器临时 502 / 404 阻塞 OTA 检查流程。
     */
    suspend fun fetchLocalManifest(baseUrl: String): OtaManifest?
}

expect fun createOtaRepository(): OtaRepository

/** 默认仓库源（fork = hamy88/mobius；上游 = mobius-system/mobius；可被环境变量覆盖）。 */
const val DEFAULT_OTA_REPO = "hamy88/mobius"

/** 本服务器 OTA manifest 端点路径（相对 baseUrl，无尾斜杠）。 */
const val LOCAL_OTA_MANIFEST_PATH = "/api/mobile/ota/manifest.json"

/**
 * GitHub Releases API 数组响应 → 取首条 non-draft（按 published_at 倒序的最新一条）。
 *
 * 暴露为 commonMain 顶层函数：androidMain / desktopMain 拿到原始 JSON 字符串后
 * 走这里统一解析，避免每个平台重复实现"过滤 draft + 选最大 published_at"逻辑。
 * iOS NoOp 不走这里。
 *
 * 输入是 GitHub API `/repos/{owner}/{repo}/releases?per_page=10` 的 JSON 数组：
 * `[{"tag_name": "...", "draft": false, "prerelease": true, "published_at": "...", ...}, ...]`
 *
 * 抛出 [OtaError.ParseError] 当：
 * - 顶层不是数组
 * - 数组内元素不是对象 / draft 字段非 boolean
 * - 数组全部为 draft
 *
 * 抛出 [OtaError.NotFound] 当数组为空（仓库无 release）。
 */
fun parseLatestNonDraftRelease(rawJson: String): OtaRelease {
    // 用 lenient + ignoreUnknownKeys 配置: GitHub Releases API 返回大量字段 (draft / prerelease / author /
    // assets / ...), 而 OtaRelease 仅消费其中 5 个; 多余字段必须忽略.
    val parser = Json { ignoreUnknownKeys = true; isLenient = true }
    val list: List<JsonElement> = try {
        parser.decodeFromString(rawJson)
    } catch (e: kotlinx.serialization.SerializationException) {
        throw OtaError.ParseError("releases array body is not valid JSON: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw OtaError.ParseError("releases array body decode error: ${e.message}", e)
    }
    val candidates = list.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val draft = obj["draft"] as? JsonPrimitive ?: return@mapNotNull null
        // draft=true 的 release 直接过滤掉（GitHub UI 上看不到）。
        if (draft.booleanOrNull != true) obj else null
    }
    if (candidates.isEmpty()) {
        throw OtaError.NotFound()
    }
    // 按 published_at 字典序倒序；GitHub ISO-8601 字符串字典序 = 时间倒序，简化版本不再做 Date 解析。
    val latestObj = candidates.maxBy { obj ->
        (obj["published_at"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    }
    val release: OtaRelease = try {
        // JsonObject → JsonElement → String (走 Json 的 stringify, 不是 Kotlin 的 toString, 否则 quoted keys 会被破坏).
        parser.decodeFromString(OtaRelease.serializer(), latestObj.toString())
    } catch (e: kotlinx.serialization.SerializationException) {
        throw OtaError.ParseError("latest release object decode error: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw OtaError.ParseError("latest release object decode error: ${e.message}", e)
    }
    return release.copy(
        changelogItems = ChangelogParser.parse(release.body),
    )
}