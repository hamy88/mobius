package com.mobius.momo.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OTA 元数据契约（v1.1）。
 *
 * 对应方案 §5.3 + §5.4 + §10.5.1 字段集合。GitHub Releases 同步至本地的 manifest.json
 * 在顶层展开以下字段；未填的字段走防御默认值（详见各字段注释 + ThresholdEvaluator）。
 *
 * 顶层字段一览：
 * - [version] / [versionCode] / [channel] / [releasedAt]：必填强校验（§5.5）
 * - [minSupportedVersion] + 4 个配套字段（level / reason / advisoryId / hardBlockBypassable）：
 *   §10.5.1 阈值分档契约；缺 level 时默认 advisory，未识别 level 也降级 advisory
 * - [android]：§5.4 Android 14+ 安装限制字段块（minSdk / targetSdk / signatureScheme / packageName ...）
 * - [deltaEnabled]：§4.2 灰度开关；v1.1 二态
 * - [releaseNotesUrl]：人类可读的发布说明链接
 * - [builds]：每个 ABI 一条构建产物
 */
@Serializable
data class OtaManifest(
    @SerialName("version") val version: String = "",
    @SerialName("version_code") val versionCode: Int = 0,
    @SerialName("channel") val channel: String = "stable",
    @SerialName("released_at") val releasedAt: String = "",
    @SerialName("min_supported_version") val minSupportedVersion: String? = null,
    @SerialName("min_supported_version_level") val minSupportedVersionLevel: String? = null,
    @SerialName("min_supported_version_reason") val minSupportedVersionReason: String? = null,
    @SerialName("min_supported_version_advisory_id") val minSupportedVersionAdvisoryId: String? = null,
    @SerialName("hard_block_bypassable") val hardBlockBypassable: Boolean = true,
    @SerialName("android") val android: OtaAndroidSpec = OtaAndroidSpec(),
    @SerialName("delta_enabled") val deltaEnabled: Boolean = false,
    @SerialName("release_notes_url") val releaseNotesUrl: String? = null,
    @SerialName("builds") val builds: List<OtaBuildEntry> = emptyList(),
)

/** §5.4 Android 14+ 安装限制字段块。 */
@Serializable
data class OtaAndroidSpec(
    @SerialName("min_sdk") val minSdk: Int = 0,
    @SerialName("target_sdk") val targetSdk: Int = 0,
    @SerialName("signature_scheme") val signatureScheme: List<String> = emptyList(),
    @SerialName("package_name") val packageName: String = "",
    @SerialName("request_install_packages_declared") val requestInstallPackagesDeclared: Boolean = false,
    @SerialName("post_notifications_required") val postNotificationsRequired: Boolean = false,
)

/** 单 ABI 的构建产物（§5.3 builds[]）。 */
@Serializable
data class OtaBuildEntry(
    @SerialName("platform") val platform: String = "",
    @SerialName("abi") val abi: String = "",
    @SerialName("version") val version: String = "",
    @SerialName("version_code") val versionCode: Int = 0,
    @SerialName("url") val url: String = "",
    @SerialName("size") val size: Long = 0L,
    @SerialName("sha256") val sha256: String = "",
    /** 可选增量包：旧版本 → 当前版本的 bsdiff 资产。 */
    @SerialName("patch_from") val patchFrom: Map<String, OtaPatchEntry> = emptyMap(),
)

/** 增量包资产（§4.2 服务端流水线产出）。 */
@Serializable
data class OtaPatchEntry(
    @SerialName("url") val url: String = "",
    @SerialName("size") val size: Long = 0L,
    @SerialName("sha256") val sha256: String = "",
)
