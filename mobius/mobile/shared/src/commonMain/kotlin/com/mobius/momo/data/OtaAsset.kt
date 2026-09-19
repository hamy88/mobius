package com.mobius.momo.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 单个 OTA 资产（APK 全量 / bsdiff 增量），客户端下载单元。
 *
 * 与 [OtaBuildEntry] / [OtaPatchEntry] 字段一一对应，但额外带上 contentType 与 abi 便于下载层
 * 直接调度（不需再回查 OtaManifest）。
 */
@Serializable
data class OtaAsset(
    /** 资产名（用作下载文件名 + DownloadManager title）。 */
    @SerialName("name") val name: String,
    /** GitHub Releases asset 浏览器下载 URL（已是匿名直链）。 */
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    /** 字节数；PackageInstaller.Session API 必填。 */
    @SerialName("size") val size: Long,
    /** release 元数据公布的 SHA256，下载完成后强校验。 */
    @SerialName("digest_sha256") val digestSha256: String,
    /** MIME type；APK 固定 application/vnd.android.package-archive。 */
    @SerialName("content_type") val contentType: String = "application/vnd.android.package-archive",
    /** 目标 ABI；arm64-v8a / armeabi-v7a / universal。 */
    @SerialName("abi") val abi: String,
) {
    companion object {
        /** 从 manifest.json builds[] 单条构造 OtaAsset（patch / full 各自走对应字段）。 */
        fun fromBuild(build: OtaBuildEntry): OtaAsset = OtaAsset(
            name = "mobius-mobile-${build.version}-android-${build.abi}.apk",
            browserDownloadUrl = build.url,
            size = build.size,
            digestSha256 = build.sha256,
            abi = build.abi,
        )

        fun fromPatch(build: OtaBuildEntry, prevVersion: String, patch: OtaPatchEntry): OtaAsset = OtaAsset(
            name = "mobius-mobile-${prevVersion}-to-${build.version}-android-${build.abi}.patch",
            browserDownloadUrl = patch.url,
            size = patch.size,
            digestSha256 = patch.sha256,
            contentType = "application/octet-stream",
            abi = build.abi,
        )
    }
}
