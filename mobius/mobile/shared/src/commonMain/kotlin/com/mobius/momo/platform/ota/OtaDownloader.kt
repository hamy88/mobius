package com.mobius.momo.platform.ota

import com.mobius.momo.data.OtaAsset
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * OTA 下载进度快照（commonMain，平台无关）。
 *
 * 由 [OtaDownloader.lastProgress] 暴露给 ViewModel / UI 渲染应用内进度条；
 * 具体实现走系统 DownloadManager(Android) / 占位(iOS/desktop)。
 */
data class OtaDownloadProgress(
    val downloadId: Long,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val status: OtaDownloadStatus,
    val reason: String? = null,
) {
    /** 0..1；尚未拿到 total 时返回 0。 */
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (bytesDownloaded.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
}

/** OTA 下载状态（与系统 DownloadManager.STATUS_* 对齐；iOS/desktop 维持单一 Running）。 */
enum class OtaDownloadStatus { Pending, Running, Paused, Successful, Failed, Cancelled }

/** OTA 下载完成事件（成功 / 失败）。取消走 dismissOtaDownload 路径不产生 Failure 事件。 */
sealed interface OtaCompletionEvent {
    val downloadId: Long
    data class Success(override val downloadId: Long, val localUri: String) : OtaCompletionEvent
    data class Failure(override val downloadId: Long, val reason: String) : OtaCompletionEvent
}

/**
 * OTA 下载器公共接口（commonMain expect class）。
 *
 * 平台层 [androidMain] 用 Android 系统 DownloadManager 实现；[iosMain] / [desktopMain]
 * 仅暴露占位（throwing UnsupportedOperationException），因为目前 OTA 仅在 Android 真机走下载/安装链路。
 *
 * 调用约定：
 * - [enqueue] 立即返回 downloadId；后续 [lastProgress] 会持续 emit 进度
 * - [cancel] 主动取消（UI 按"取消下载"时调用）；不会 emit [OtaCompletionEvent.Failure]
 * - [completionEvents] 在下载完成 / 失败时 emit 单次事件；订阅者负责更新 UI state
 */
expect class OtaDownloader() {
    /** 当前所有活跃下载的进度快照；key = downloadId。当前客户端只跑一个 OTA 下载, 取 values.first() 即可。 */
    val lastProgress: StateFlow<Map<Long, OtaDownloadProgress>>

    /** 完成 / 失败事件流（cold subscriber；非持久化）。 */
    val completionEvents: SharedFlow<OtaCompletionEvent>

    /**
     * 入队下载。
     * @return downloadId；调用方需保留以便 [queryProgress] / [cancel] / [openDownloadId]
     */
    fun enqueue(asset: OtaAsset): Long

    /** 主动取消下载（用户在弹窗点"取消下载"时调用）。 */
    fun cancel(downloadId: Long)

    /** 主动查询进度（应用内进度条用；非阻塞主线程）。 */
    fun queryProgress(downloadId: Long): OtaDownloadProgress?

    /**
     * 拿下载完成的本地路径（`file:///storage/emulated/0/Download/mobius-ota-xxx.apk`）。
     *
     * 给 [OtaInstaller.install] 使用；下载未完成 / 已删除 / 平台不支持时返回 null。
     */
    fun localUri(downloadId: Long): String?

    /**
     * 返回当前 downloadId（用于系统通知点击回跳到 MomoApp 内的 OTA 弹窗）。
     * 当前客户端只跑一个 OTA 下载；存在多个时取最新一个。
     */
    fun openDownloadId(): Long?

    /** Android 13+ 首次下载前运行时申请 POST_NOTIFICATIONS。其它平台 no-op。 */
    fun requestPostNotificationsIfNeeded()
}

/**
 * split-ABI 选择：按 priority 列表匹配设备支持的 ABI，回退到 [preferred]。
 * 仅为平台层 helper；commonMain 调用方可通过 [currentDeviceAbi] 获取设备首选 ABI 后再做匹配。
 */
object OtaAbiSelector {
    /**
     * 按 [preferredOrder] 优先级匹配 [supportedAbis]，找不到时回退 [preferred]。
     * 仅暴露匹配逻辑（与 Build 等平台 API 解耦），方便 commonMain / desktopTest 单测。
     */
    fun pick(supportedAbis: List<String>, preferredOrder: List<String> = listOf("arm64-v8a", "armeabi-v7a"), preferred: String = "arm64-v8a"): String {
        for (candidate in preferredOrder) {
            if (supportedAbis.any { it.equals(candidate, ignoreCase = true) }) return candidate
        }
        return preferred
    }
}
