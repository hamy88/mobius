package com.mobius.momo.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GitHub Releases 元数据（§3 + §5.1）。
 *
 * 客户端拿到 release 后通过 [manifest] 字段读 OTA 字段契约；[body] 保留供调试 / 后续
 * CHANGELOG 渲染（D7 通过 [ChangelogParser] 在客户端解析 [changelogItems]）。
 *
 * 序列化字段命名遵循 GitHub Releases API 原生 snake_case（[tagName] / [versionCode] ...），
 * 客户端二次封装字段（[manifest] / [changelogItems]）用 snake_case 与 API 风格保持一致。
 */
@Serializable
data class OtaRelease(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("version_code") val versionCode: Int = 0,
    @SerialName("version_name") val versionName: String = "",
    @SerialName("published_at") val publishedAt: String = "",
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("body") val body: String = "",
    /** 客户端解析后的 OTA 字段契约；由仓库在 fetchLatestRelease 后填入。 */
    @SerialName("manifest") val manifest: OtaManifest? = null,
    /**
     * 客户端二次解析后的 CHANGELOG 项列表（§D7）。由 [OtaRepository] 实现层在
     * [body] 拿到后调用 [ChangelogParser.parse] 填入，UI 层直接消费。
     */
    @SerialName("changelog_items") val changelogItems: List<ChangelogItem>? = null,
) {
    /** 提取 manifest.json URL（mobile-builds/<ver>/manifest.json）。 */
    fun manifestAssetUrl(): String? {
        // 客户端解析由 OtaRepository 完成；这里保留 null 占位以保证 Serializable 不被反射破坏。
        return null
    }
}