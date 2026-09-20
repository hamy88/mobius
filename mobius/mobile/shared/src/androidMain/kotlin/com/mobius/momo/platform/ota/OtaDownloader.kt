package com.mobius.momo.platform.ota

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.mobius.momo.data.AndroidContext
import com.mobius.momo.data.OtaAsset
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android 端 OTA 下载封装（v1.2，0.4.4 暴露到 commonMain expect class 实现）。
 *
 * 设计要点：
 * - 走 Android 系统 DownloadManager，前台 / 后台统一；Application 被杀仍可继续
 * - 不在系统下载 UI 中暴露（`setVisibleInDownloadsUi(false)`）
 * - POST_NOTIFICATIONS 权限运行时申请（Android 13+），由调用方在首次 enqueue 前调 [requestPostNotificationsIfNeeded]
 * - 进度通过 [queryProgress] 主动查询 + [completionEvents] Flow 被动接收完成 / 失败
 * - 系统通知文案：标题 "Mobius v{version} 正在下载"，下载完成后系统自动切到 "下载完成" 通知
 *
 * §6 安全红线：
 * - 仅 HTTPS（assertHttpsUrl）：拒绝 http://
 * - SHA256 校验在下载完成后由调用方自行处理（见 OtaInstaller.install）
 */
actual class OtaDownloader actual constructor() {

    private val context: Context = AndroidContext.application

    private val downloadManager: DownloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: error("DownloadManager unavailable")

    private val _completionEvents = MutableSharedFlow<OtaCompletionEvent>(extraBufferCapacity = 16)
    actual val completionEvents: SharedFlow<OtaCompletionEvent> = _completionEvents.asSharedFlow()

    private val _lastProgress = MutableStateFlow<Map<Long, OtaDownloadProgress>>(emptyMap())
    actual val lastProgress: StateFlow<Map<Long, OtaDownloadProgress>> = _lastProgress.asStateFlow()

    private val receiverRegistered = java.util.concurrent.atomic.AtomicBoolean(false)

    // 当前活跃 downloadId（仅最近一个；客户端同一时刻只跑一个 OTA 下载）。
    // 给系统通知点击 deepLink 回调(openDownloadId)使用, 避免遍历 map 拿到陈旧 id。
    @Volatile
    private var currentDownloadId: Long = -1L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
            if (id < 0) return
            when (intent?.action) {
                DownloadManager.ACTION_DOWNLOAD_COMPLETE -> {
                    val query = DownloadManager.Query().setFilterById(id)
                    val cursor: Cursor = runCatching {
                        downloadManager.query(query)
                    }.getOrNull() ?: run {
                        _completionEvents.tryEmit(OtaCompletionEvent.Failure(id, "cursor 查询失败"))
                        return
                    }
                    cursor.use { c ->
                        if (!c.moveToFirst()) {
                            _completionEvents.tryEmit(OtaCompletionEvent.Failure(id, "cursor 为空"))
                            return
                        }
                        val statusIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val uriIdx = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                        val reasonIdx = c.getColumnIndex(DownloadManager.COLUMN_REASON)
                        val status = c.getInt(statusIdx)
                        val uri = if (uriIdx >= 0) c.getString(uriIdx) else null
                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                _completionEvents.tryEmit(OtaCompletionEvent.Success(id, uri.orEmpty()))
                            }
                            else -> {
                                val reasonText = if (reasonIdx >= 0) c.getString(reasonIdx) else null
                                _completionEvents.tryEmit(OtaCompletionEvent.Failure(id, "status=$status reason=$reasonText"))
                            }
                        }
                    }
                    // 下载完成/失败后清空 currentDownloadId（cancel 路径不清，openDownloadId 还能返回 cancel 前的 id 供上层提示）。
                    if (id == currentDownloadId && status != DownloadManager.STATUS_RUNNING) {
                        currentDownloadId = -1L
                    }
                }
                DownloadManager.ACTION_NOTIFICATION_CLICKED -> Unit
            }
        }
    }

    /** Android 13+ 首次下载前调用：运行时申请 POST_NOTIFICATIONS。 */
    actual fun requestPostNotificationsIfNeeded() {
        AndroidContext.requestInitialNotificationPermissionIfNeeded()
    }

    /**
     * 入队下载。
     *
     * @return DownloadManager 分配的 downloadId；调用方需保留以便 [queryProgress] / [cancel] / [openDownloadId]
     */
    actual fun enqueue(asset: OtaAsset): Long {
        assertHttpsUrl(asset.browserDownloadUrl)
        registerReceiverIfNeeded()
        // 0.4.4 通知文案：带版本号标题更直观；description 留给系统显示进度
        val title = "Mobius v${asset.abi} 正在下载"
        val request = DownloadManager.Request(Uri.parse(asset.browserDownloadUrl))
            .setTitle(title)
            .setDescription("正在下载更新…")
            .setMimeType(asset.contentType)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setVisibleInDownloadsUi(false)
            .setDestinationInExternalPublicDir(
                android.os.Environment.DIRECTORY_DOWNLOADS,
                "mobius-ota-${asset.name}",
            )
        val id = downloadManager.enqueue(request)
        currentDownloadId = id
        return id
    }

    /** 主动查询进度（应用内进度条用；不阻塞主线程）。 */
    actual fun queryProgress(downloadId: Long): OtaDownloadProgress? {
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = runCatching { downloadManager.query(query) }.getOrNull() ?: return null
        return cursor.use { c ->
            if (!c.moveToFirst()) return@use null
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reason = runCatching {
                val idx = c.getColumnIndex(DownloadManager.COLUMN_REASON)
                if (idx >= 0) c.getString(idx) else null
            }.getOrNull()
            val mapped = when (status) {
                DownloadManager.STATUS_PENDING -> OtaDownloadStatus.Pending
                DownloadManager.STATUS_RUNNING -> OtaDownloadStatus.Running
                DownloadManager.STATUS_PAUSED -> OtaDownloadStatus.Paused
                DownloadManager.STATUS_SUCCESSFUL -> OtaDownloadStatus.Successful
                DownloadManager.STATUS_FAILED -> OtaDownloadStatus.Failed
                else -> OtaDownloadStatus.Running
            }
            OtaDownloadProgress(downloadId, bytes, total, mapped, reason).also { p ->
                _lastProgress.value = _lastProgress.value.toMutableMap().apply { put(downloadId, p) }
            }
        }
    }

    /** 取消下载。 */
    actual fun cancel(downloadId: Long) {
        runCatching { downloadManager.remove(downloadId) }
        // 不清 currentDownloadId：openDownloadId 仍可返回 cancel 前的 id 供上层判断
        // "用户已取消过当前 OTA"。SUCCESSFUL/FAILED 事件回调时会清。
    }

    /** 拿下载文件的本地 URI（file://...）。未完成 / 已删除 / 查询失败时返回 null。 */
    actual fun localUri(downloadId: Long): String? {
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = runCatching { downloadManager.query(query) }.getOrNull() ?: return null
        return cursor.use { c ->
            if (!c.moveToFirst()) return@use null
            val idx = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            if (idx >= 0) c.getString(idx) else null
        }
    }

    /** 返回当前 downloadId（系统通知点击 deepLink 回调使用）。当前活跃下载被取消/完成后置 -1。 */
    actual fun openDownloadId(): Long? = currentDownloadId.takeIf { it > 0L }

    private fun registerReceiverIfNeeded() {
        if (receiverRegistered.compareAndSet(false, true)) {
            val filter = IntentFilter().apply {
                addAction(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.RECEIVER_EXPORTED
            } else {
                0
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, flags)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
        }
    }

    /** §6 红线：拒绝 HTTP URL（GitHub Releases 全域名 HTTPS）。 */
    private fun assertHttpsUrl(url: String) {
        require(url.startsWith("https://")) { "OTA 下载仅支持 HTTPS：$url" }
    }

    companion object {
        /**
         * split-abi 选择：按 Build.SUPPORTED_ABIS[0] 优先 arm64-v8a，回退 armeabi-v7a。
         * @return 与设备匹配的 ABI；列表中没有匹配则回退到 [preferred]，由调用方决定是否拒绝下载。
         *
         * 实际公共逻辑见 commonMain [OtaAbiSelector.pick]，这里只是 androidMain 直接可用的便捷入口。
         */
        fun selectAbiForDevice(supportedAbis: Array<String>, preferred: String = "arm64-v8a"): String =
            OtaAbiSelector.pick(supportedAbis.toList(), preferred = preferred)
    }
}
