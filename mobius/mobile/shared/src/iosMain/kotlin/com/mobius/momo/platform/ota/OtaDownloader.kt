package com.mobius.momo.platform.ota

import com.mobius.momo.data.OtaAsset
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS 端 OTA 下载器占位（v0.4.4 expect class 框架补全）。
 *
 * 当前 iOS OTA 路径仅做 Repository stub + manifest 解析（参 [com.mobius.momo.data.OtaRepository.ios.kt]），
 * 不接真机下载 / 安装（依赖 TestFlight 渠道分发）。本类提供最小可用实现以让 commonMain 编译通过：
 * - [lastProgress] 恒空；[completionEvents] 无任何 emit
 * - [enqueue] / [cancel] / [queryProgress] / [openDownloadId] / [requestPostNotificationsIfNeeded] 全部 no-op
 */
actual class OtaDownloader actual constructor() {
    private val _lastProgress = MutableStateFlow<Map<Long, OtaDownloadProgress>>(emptyMap())
    actual val lastProgress: StateFlow<Map<Long, OtaDownloadProgress>> = _lastProgress.asStateFlow()

    private val _completionEvents = MutableSharedFlow<OtaCompletionEvent>(extraBufferCapacity = 16)
    actual val completionEvents: SharedFlow<OtaCompletionEvent> = _completionEvents.asSharedFlow()

    actual fun enqueue(asset: OtaAsset): Long = -1L
    actual fun cancel(downloadId: Long) = Unit
    actual fun queryProgress(downloadId: Long): OtaDownloadProgress? = null
    actual fun localUri(downloadId: Long): String? = null
    actual fun openDownloadId(): Long? = null
    actual fun requestPostNotificationsIfNeeded() = Unit
}
