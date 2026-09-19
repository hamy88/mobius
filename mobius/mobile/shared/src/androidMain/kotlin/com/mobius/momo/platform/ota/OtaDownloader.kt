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
 * Android 端 OTA 下载封装（v1.1，方案 §3.4 + §6.2）。
 *
 * 设计要点：
 * - 走 Android 系统 DownloadManager（§3.4），前台 / 后台统一；Application 被杀仍可继续
 * - 不在系统下载 UI 中暴露（`setVisibleInDownloadsUi(false)`，§3.4）
 * - POST_NOTIFICATIONS 权限运行时申请（§6.2 Android 13+），由调用方在首次 enqueue 前调 [requestPostNotificationsIfNeeded]
 * - 进度通过 [queryProgress] 主动查询 + [completionEvents] Flow 被动接收完成 / 失败
 * - split-abi 选择：[selectAssetForDevice] 按 Build.SUPPORTED_ABIS[0] 优先 arm64-v8a，回退 armeabi-v7a
 *
 * §6 安全红线：
 * - 仅 HTTPS（assertHttpsUrl）：拒绝 http://
 * - SHA256 校验在下载完成后由调用方自行处理（见 OtaInstaller.install）
 */
class OtaDownloader(private val context: Context = AndroidContext.application) {

    /** 下载进度快照（暴露给 ViewModel / UI 渲染应用内进度条）。 */
    data class DownloadProgress(
        val downloadId: Long,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val status: Status,
        val reason: String? = null,
    ) {
        /** 0..1；尚未拿到 total 时返回 0。 */
        val fraction: Float
            get() = if (totalBytes <= 0L) 0f else (bytesDownloaded.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)

        enum class Status { Pending, Running, Paused, Successful, Failed, Cancelled }
    }

    /** 下载完成 / 失败 事件流（cold subscriber；非持久化）。 */
    sealed interface CompletionEvent {
        val downloadId: Long
        data class Success(override val downloadId: Long, val localUri: String) : CompletionEvent
        data class Failure(override val downloadId: Long, val reason: String) : CompletionEvent
    }

    private val downloadManager: DownloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: error("DownloadManager unavailable")

    private val _completionEvents = MutableSharedFlow<CompletionEvent>(extraBufferCapacity = 16)
    val completionEvents: SharedFlow<CompletionEvent> = _completionEvents.asSharedFlow()

    private val _lastProgress = MutableStateFlow<Map<Long, DownloadProgress>>(emptyMap())
    val lastProgress: StateFlow<Map<Long, DownloadProgress>> = _lastProgress.asStateFlow()

    private val receiverRegistered = java.util.concurrent.atomic.AtomicBoolean(false)
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
                        _completionEvents.tryEmit(CompletionEvent.Failure(id, "cursor 查询失败"))
                        return
                    }
                    cursor.use { c ->
                        if (!c.moveToFirst()) {
                            _completionEvents.tryEmit(CompletionEvent.Failure(id, "cursor 为空"))
                            return
                        }
                        val statusIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val uriIdx = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                        val reasonIdx = c.getColumnIndex(DownloadManager.COLUMN_REASON)
                        val status = c.getInt(statusIdx)
                        val uri = if (uriIdx >= 0) c.getString(uriIdx) else null
                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                _completionEvents.tryEmit(CompletionEvent.Success(id, uri.orEmpty()))
                            }
                            else -> {
                                val reasonText = if (reasonIdx >= 0) c.getString(reasonIdx) else null
                                _completionEvents.tryEmit(CompletionEvent.Failure(id, "status=$status reason=$reasonText"))
                            }
                        }
                    }
                }
                DownloadManager.ACTION_NOTIFICATION_CLICKED -> Unit
            }
        }
    }

    /** Android 13+ 首次下载前调用：运行时申请 POST_NOTIFICATIONS。 */
    fun requestPostNotificationsIfNeeded() {
        AndroidContext.requestInitialNotificationPermissionIfNeeded()
    }

    /**
     * 入队下载。
     * @return DownloadManager 分配的 downloadId；调用方需保留以便 [queryProgress] / [cancel]
     */
    fun enqueue(asset: OtaAsset): Long {
        assertHttpsUrl(asset.browserDownloadUrl)
        registerReceiverIfNeeded()
        val request = DownloadManager.Request(Uri.parse(asset.browserDownloadUrl))
            .setTitle("Mobius ${asset.name}")
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
        return downloadManager.enqueue(request)
    }

    /** 主动查询进度（应用内进度条用；不阻塞主线程）。 */
    fun queryProgress(downloadId: Long): DownloadProgress? {
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
                DownloadManager.STATUS_PENDING -> DownloadProgress.Status.Pending
                DownloadManager.STATUS_RUNNING -> DownloadProgress.Status.Running
                DownloadManager.STATUS_PAUSED -> DownloadProgress.Status.Paused
                DownloadManager.STATUS_SUCCESSFUL -> DownloadProgress.Status.Successful
                DownloadManager.STATUS_FAILED -> DownloadProgress.Status.Failed
                else -> DownloadProgress.Status.Running
            }
            DownloadProgress(downloadId, bytes, total, mapped, reason).also { p ->
                _lastProgress.value = _lastProgress.value.toMutableMap().apply { put(downloadId, p) }
            }
        }
    }

    /** 取消下载。 */
    fun cancel(downloadId: Long) {
        runCatching { downloadManager.remove(downloadId) }
    }

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
         * split-abi 选择（§3.3）：按 Build.SUPPORTED_ABIS[0] 优先 arm64-v8a，回退 armeabi-v7a。
         * @return 与设备匹配的 ABI；列表中没有匹配则回退到 [preferred]，由调用方决定是否拒绝下载。
         */
        fun selectAbiForDevice(supportedAbis: Array<String>, preferred: String = "arm64-v8a"): String {
            val order = listOf("arm64-v8a", "armeabi-v7a")
            for (candidate in order) {
                if (supportedAbis.any { it.equals(candidate, ignoreCase = true) }) return candidate
            }
            return preferred
        }
    }
}
