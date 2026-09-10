@file:Suppress("EXPECT_ACTUAL_CLASSES_IN_BETA_WARNING")

package com.mobius.momo.data

import com.mobius.momo.shared.BuildConfig

import android.app.Activity
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech as AndroidTextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

object AndroidContext {
    private const val SPEECH_PERMISSION_REQUEST = 9021
    private const val FILE_PICK_REQUEST = 9022
    private const val NOTIFICATION_PERMISSION_REQUEST = 9023
    private const val MOMO_APP_PACKAGE_NAME = "com.mobius.momo"
    const val NOTIFICATION_CHANNEL_ID = "momo_assistant_channel"

    lateinit var application: Context
    var activity: Activity? = null
    private var permissionContinuation: Continuation<SpeechPermissionStatus>? = null
    private var notificationContinuation: Continuation<Boolean>? = null
    private var fileResult: ((List<PickedFile>) -> Unit)? = null
    private var fileError: ((String) -> Unit)? = null
    private var maxFiles: Int = 1

    fun requestSpeechPermission(continuation: Continuation<SpeechPermissionStatus>) {
        val currentActivity = activity
        if (application.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            continuation.resumeWith(Result.success(SpeechPermissionStatus.Granted))
            return
        }
        if (currentActivity == null) {
            continuation.resumeWith(Result.success(SpeechPermissionStatus.Denied))
            return
        }
        permissionContinuation = continuation
        currentActivity.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), SPEECH_PERMISSION_REQUEST)
    }

    fun requestNotificationPermission(continuation: Continuation<Boolean>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            continuation.resumeWith(Result.success(true))
            return
        }
        if (application.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            continuation.resumeWith(Result.success(true))
            return
        }
        val currentActivity = activity
        if (currentActivity == null) {
            continuation.resumeWith(Result.success(false))
            return
        }
        notificationContinuation?.resumeWith(Result.success(false))
        notificationContinuation = continuation
        currentActivity.requestPermissions(
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATION_PERMISSION_REQUEST,
        )
    }

    fun requestInitialNotificationPermissionIfNeeded() {
        val currentActivity = activity ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (application.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        currentActivity.requestPermissions(
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATION_PERMISSION_REQUEST,
        )
    }

    fun handlePermissionResult(requestCode: Int, grantResults: IntArray) {
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            SPEECH_PERMISSION_REQUEST -> {
                permissionContinuation?.resumeWith(Result.success(if (granted) SpeechPermissionStatus.Granted else SpeechPermissionStatus.Denied))
                permissionContinuation = null
            }
            NOTIFICATION_PERMISSION_REQUEST -> {
                notificationContinuation?.resumeWith(Result.success(granted))
                notificationContinuation = null
            }
        }
    }

    fun pickFiles(maxFiles: Int, onResult: (List<PickedFile>) -> Unit, onError: (String) -> Unit) {
        val currentActivity = activity
        if (currentActivity == null) {
            onError("文件选择器暂不可用")
            return
        }
        this.maxFiles = maxFiles.coerceAtLeast(1)
        fileResult = onResult
        fileError = onError
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, AndroidContext.maxFiles > 1)
        }
        currentActivity.startActivityForResult(intent, FILE_PICK_REQUEST)
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != FILE_PICK_REQUEST) return
        val onResult = fileResult
        val onError = fileError
        fileResult = null
        fileError = null
        if (resultCode != Activity.RESULT_OK || data == null) {
            onResult?.invoke(emptyList())
            return
        }
        val uris = buildList {
            data.clipData?.let { clip ->
                repeat(minOf(clip.itemCount, maxFiles)) { index -> add(clip.getItemAt(index).uri) }
            }
            if (isEmpty()) data.data?.let(::add)
        }
        runCatching {
            uris.take(maxFiles).map { uri ->
                val resolver = application.contentResolver
                var name = "attachment"
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && index >= 0) name = cursor.getString(index) ?: name
                }
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("无法读取文件 $name")
                PickedFile(
                    name = name,
                    mimeType = resolver.getType(uri)?.takeIf { it.isNotBlank() } ?: inferMimeTypeFromExtension(name),
                    bytes = bytes,
                )
            }
        }.onSuccess { onResult?.invoke(it) }
            .onFailure { onError?.invoke(it.message ?: "读取附件失败") }
    }

    fun requestIgnoreBatteryOptimizations(): Boolean {
        val powerManager = application.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        if (powerManager.isIgnoringBatteryOptimizations(application.packageName)) return true
        val currentActivity = activity ?: return false
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${application.packageName}")
        }
        return runCatching {
            currentActivity.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun showLocalNotification(title: String, body: String, deepLink: String?) {
        if (!hasNotificationPermission()) return
        val notificationManager = application.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val launchIntent = application.packageManager.getLaunchIntentForPackage(application.packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(application.packageName)
        val intent = launchIntent.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!deepLink.isNullOrBlank()) data = Uri.parse(deepLink)
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            application,
            deepLink?.hashCode() ?: nowEpochMillis().toInt(),
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(application, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(notificationIconResourceId())
            .setContentTitle(title.ifBlank { "Mobius" })
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .build()
        notificationManager.notify((nowEpochMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    /**
     * 已取消常驻状态栏的保活前台服务（用户不想要那条"后台运行中"常驻通知）。
     * 后台消息送达改由 JPush 远程推送（含华为厂商通道）负责，不再依赖常驻进程保活 SSE。
     * 保留空实现供 [NotificationGateway] 调用，不产生任何前台通知。
     */
    fun startKeepaliveForeground() = Unit

    fun stopKeepaliveForeground() = Unit

    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            application.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notificationIconResourceId(): Int =
        application.resources.getIdentifier("ic_notification", "drawable", application.packageName)
            .takeIf { it != 0 }
            ?: application.resources.getIdentifier("ic_launcher", "mipmap", application.packageName)
}

actual fun createSecureStorage(): SecureStorage {
    val context = AndroidContext.application
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    val prefs = EncryptedSharedPreferences.create(
        context,
        "momo_secure_storage",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    return object : SecureStorage {
        override fun saveToken(token: String) {
            prefs.edit().putString("token", token).apply()
        }

        override fun getToken(): String? = prefs.getString("token", null)

        override fun saveTokenMetadata(metadata: StoredTokenMetadata) {
            prefs.edit()
                .putString("token.baseUrl", metadata.baseUrl)
                .putString("token.savedAtEpochMillis", metadata.savedAtEpochMillis.toString())
                .putString("token.storageVersion", metadata.storageVersion.toString())
                .apply()
        }

        override fun getTokenMetadata(): StoredTokenMetadata? =
            storedTokenMetadataFromStrings(
                baseUrl = prefs.getString("token.baseUrl", null),
                savedAtEpochMillis = prefs.getString("token.savedAtEpochMillis", null),
                storageVersion = prefs.getString("token.storageVersion", null),
            )

        override fun savePreference(key: String, value: String) {
            prefs.edit().putString("pref.$key", value).apply()
        }

        override fun getPreference(key: String): String? = prefs.getString("pref.$key", null)

        override fun clear() {
            prefs.edit()
                .remove("token")
                .remove("token.baseUrl")
                .remove("token.savedAtEpochMillis")
                .remove("token.storageVersion")
                .apply()
        }
    }
}

actual fun platformBuildBaseUrl(): String = BuildConfig.MOMO_BASE_URL

// 读实际安装包的 versionName(与 androidApp/build.gradle.kts 的 versionName 一致)。
// Android: navigationBarsPadding() 自动处理系统导航栏, 无需额外 padding.
actual fun platformBottomTabPaddingDp(): Float = 0f

actual fun platformAppVersion(): String = runCatching {
    val ctx = AndroidContext.application
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
}.getOrDefault("")

actual fun createFilePicker(): FilePicker = object : FilePicker {
    override fun pickFiles(
        maxFiles: Int,
        onResult: (List<PickedFile>) -> Unit,
        onError: (String) -> Unit,
    ) {
        AndroidContext.pickFiles(maxFiles, onResult, onError)
    }
}

private fun inferMimeTypeFromExtension(name: String): String =
    when (name.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        else -> "application/octet-stream"
    }

actual fun createMobiusHttpClient(
    onUnauthorized: suspend () -> Unit,
): HttpClient = HttpClient(OkHttp) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true })
    }
    install(Logging) {
        level = LogLevel.NONE
    }
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000L
        requestTimeoutMillis = 30_000L
        socketTimeoutMillis = 30_000L
    }
    defaultRequest {
        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
    }
    HttpResponseValidator {
        validateResponse { response ->
            if (response.status.value == 401) onUnauthorized()
        }
    }
}

actual fun nowShortTime(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

actual fun nowEpochMillis(): Long = System.currentTimeMillis()

actual fun nowIsoTime(): String = java.time.Instant.now().toString()

actual fun parseBackendTimeMillis(value: String?): Long? {
    val raw = value?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
}

actual fun formatBackendTime(value: String?): String {
    val raw = value?.takeIf { it.isNotBlank() } ?: return ""
    val instant = runCatching { java.time.Instant.parse(raw) }.getOrNull() ?: return ""
    // 全局统一北京时间（CST, UTC+8）+ 24 小时制 (Issue 8f64748a)
    val zone = java.time.ZoneId.of("Asia/Shanghai")
    val zdt = instant.atZone(zone)
    val now = java.time.ZonedDateTime.now(zone)
    val time = "${zdt.hour.toString().padStart(2, '0')}:${zdt.minute.toString().padStart(2, '0')}"
    val today = now.toLocalDate()
    val date = zdt.toLocalDate()
    return when {
        date.isEqual(today) -> time
        date.isEqual(today.minusDays(1)) -> "昨天 $time"
        else -> "${zdt.monthValue}月${zdt.dayOfMonth}日 $time"
    }
}

actual object NotificationGateway {
    actual suspend fun requestPermission(): Boolean = suspendCoroutine { continuation ->
        AndroidContext.requestNotificationPermission(continuation)
    }

    actual fun requestIgnoreBatteryOptimizations(): Boolean =
        AndroidContext.requestIgnoreBatteryOptimizations()

    actual fun show(title: String, body: String, deepLink: String?) {
        AndroidContext.showLocalNotification(title, body, deepLink)
    }

    actual fun startForeground() {
        AndroidContext.startKeepaliveForeground()
    }

    actual fun stopForeground() {
        AndroidContext.stopKeepaliveForeground()
    }
}

actual fun isAppInForeground(): Boolean {
    val manager = AndroidContext.application.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
    val processInfo = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(processInfo)
    if (processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) return true
    return manager.runningAppProcesses.orEmpty().any { process ->
        process.pid == Process.myPid() &&
            process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}

actual fun createSpeechPermissionController(): SpeechPermissionController = AndroidSpeechPermissionController()

private class AndroidSpeechPermissionController : SpeechPermissionController {
    override fun hasPermission(): Boolean =
        AndroidContext.application.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) &&
            AndroidContext.application.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override suspend fun requestPermission(): SpeechPermissionStatus = suspendCoroutine { continuation ->
        if (!AndroidContext.application.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
            continuation.resumeWith(Result.success(SpeechPermissionStatus.Denied))
            return@suspendCoroutine
        }
        AndroidContext.requestSpeechPermission(continuation)
    }
}

actual fun createSpeechRecognizer(): SpeechRecognizer = AndroidAudioRecorder()

private const val MIN_VOICE_RECORD_MS = 500L
// AudioRecord 16kHz/mono/16bit 每秒 32000 字节；低于此阈值判空（约 0.12s，与后端 MIN_PCM_BYTES 同量级）。
private const val MIN_VOICE_PCM_BYTES = 4_000L

private class AndroidAudioRecorder : SpeechRecognizer {
    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var recordThread: Thread? = null
    private var audioBuffer: ByteArrayOutputStream? = null
    private var onEvent: ((SpeechRecognitionEvent) -> Unit)? = null
    @Volatile private var active = false
    private var recordStartElapsedMs: Long = 0L

    override fun start(languageTag: String, onEvent: (SpeechRecognitionEvent) -> Unit) {
        cancel()
        this.onEvent = onEvent
        try {
            // AudioRecord 直接采集 PCM，比 MediaRecorder 更底层、设备兼容性更好——后者在部分
            // ROM 上 start 成功却录不到数据，产出 0 字节文件，从而误报"录音内容为空"。
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBufferSize <= 0) error("麦克风初始化失败")
            val readBufferSize = maxOf(minBufferSize, SAMPLE_RATE / 10 * 2)
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                readBufferSize,
            )
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { audioRecord.release() }
                error("麦克风初始化失败")
            }
            audioRecord.startRecording()
            recorder = audioRecord
            audioBuffer = ByteArrayOutputStream(readBufferSize * 8)
            active = true
            recordStartElapsedMs = SystemClock.elapsedRealtime()
            onEvent(SpeechRecognitionEvent.Ready)
            recordThread = thread(name = "momo-android-asr", isDaemon = true) {
                val chunk = ByteArray(readBufferSize)
                while (active) {
                    val read = runCatching { recorder?.read(chunk, 0, chunk.size) ?: 0 }.getOrDefault(0)
                    if (read <= 0) continue
                    audioBuffer?.write(chunk, 0, read)
                    val level = computePcmVolumeLevel(chunk, read)
                    mainHandler.post { this.onEvent?.invoke(SpeechRecognitionEvent.Volume(level)) }
                }
            }
        } catch (e: Throwable) {
            cleanup(deleteBuffer = true)
            emit { onEvent(SpeechRecognitionEvent.Error(e.message ?: "麦克风启动失败")) }
        }
    }

    override fun stop() {
        if (!active) return
        active = false
        val elapsedMs = SystemClock.elapsedRealtime() - recordStartElapsedMs
        runCatching { recorder?.stop() }
        joinRecordThread(700L)
        val captured = audioBuffer?.toByteArray()
        cleanup(deleteBuffer = true)
        // 极短录音兜底（ViewModel 已对误触取消，这里再兜底）：给明确提示而非"录音内容为空"。
        if (elapsedMs in 1 until MIN_VOICE_RECORD_MS) {
            emit { onEvent?.invoke(SpeechRecognitionEvent.Error("录音时间太短，请长按说话再松手")) }
            return
        }
        if (captured == null || captured.size < MIN_VOICE_PCM_BYTES) {
            emit { onEvent?.invoke(SpeechRecognitionEvent.Error("录音内容为空，请重新录制")) }
            return
        }
        // 标准 WAV：后端 ffmpeg 解码为 16kHz/mono/16bit PCM 后送豆包 ASR
        // （multer 不限 mimetype、ffmpeg 原生支持 wav，故 audio/wav 可正常上传与识别）。
        val wav = AndroidWavContainer.encode(captured, SAMPLE_RATE, CHANNELS, BITS_PER_SAMPLE)
        emit {
            onEvent?.invoke(SpeechRecognitionEvent.Audio(wav, "audio/wav", "momo-voice.wav"))
            onEvent?.invoke(SpeechRecognitionEvent.End)
        }
    }

    override fun cancel() {
        active = false
        runCatching { recorder?.stop() }
        joinRecordThread(300L)
        cleanup(deleteBuffer = true)
    }

    override fun dispose() {
        cancel()
        onEvent = null
    }

    private fun cleanup(deleteBuffer: Boolean) {
        active = false
        runCatching { recorder?.release() }
        recorder = null
        recordThread = null
        if (deleteBuffer) audioBuffer = null
    }

    private fun joinRecordThread(timeoutMillis: Long) {
        try {
            recordThread?.join(timeoutMillis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun emit(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun computePcmVolumeLevel(chunk: ByteArray, len: Int): Int {
        if (len < 2) return 1
        var sum = 0L
        var count = 0
        var i = 0
        while (i + 1 < len) {
            val lo = chunk[i].toInt() and 0xFF
            val hi = chunk[i + 1].toInt()
            val sample = (hi shl 8) or lo
            sum += (sample.toLong() * sample.toLong()) shr 16
            count++
            i += 2
        }
        if (count == 0) return 1
        val rms = (sum / count).toInt()
        return when {
            rms < 4 -> 1
            rms < 16 -> 2
            rms < 64 -> 3
            rms < 256 -> 4
            else -> 5
        }.coerceIn(1, 5)
    }
}

private object AndroidWavContainer {
    fun encode(pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        // 标准 44 字节 WAV（RIFF/WAVE/fmt 16/data）。后端 ffmpeg 解码为 PCM 后送豆包 ASR。
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataLen = pcm.size
        val chunkSize = 36 + dataLen
        val out = ByteArrayOutputStream(dataLen + 44)
        out.write(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()))
        out.write(intToLE(chunkSize))
        out.write(byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()))
        out.write(byteArrayOf('f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte()))
        out.write(intToLE(16))
        out.write(shortToLE(1)) // PCM
        out.write(shortToLE(channels))
        out.write(intToLE(sampleRate))
        out.write(intToLE(byteRate))
        out.write(shortToLE(blockAlign))
        out.write(shortToLE(bitsPerSample))
        out.write(byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte()))
        out.write(intToLE(dataLen))
        out.write(pcm)
        return out.toByteArray()
    }

    private fun intToLE(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
    )

    private fun shortToLE(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
    )
}

actual fun createTextToSpeech(): TextToSpeech = AndroidSystemTextToSpeech()

actual fun createSystemTtsEngine(): TtsEngine = SystemTtsEngine(createTextToSpeech())

internal actual fun createPlatformAudioPlayer(): PlatformAudioPlayer = AndroidPlatformAudioPlayer()

private class AndroidSystemTextToSpeech : TextToSpeech {
    private var ready = false
    private var tts: AndroidTextToSpeech? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingCallbacks = mutableMapOf<String, Pair<(String) -> Unit, () -> Unit>>()

    init {
        tts = AndroidTextToSpeech(AndroidContext.application) { status ->
            ready = status == AndroidTextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.SIMPLIFIED_CHINESE
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                val id = utteranceId ?: return
                val callbacks = synchronized(pendingCallbacks) { pendingCallbacks.remove(id) }
                if (callbacks != null) {
                    mainHandler.post { callbacks.second.invoke() }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                val id = utteranceId ?: return
                val callbacks = synchronized(pendingCallbacks) { pendingCallbacks.remove(id) }
                if (callbacks != null) {
                    mainHandler.post { callbacks.first.invoke("语音播报失败") }
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
        })
    }

    override fun speak(text: String, languageTag: String, onError: (String) -> Unit, onComplete: () -> Unit) {
        val engine = tts
        if (engine == null || !ready) {
            onError("语音播报尚未就绪")
            return
        }
        engine.language = Locale.forLanguageTag(languageTag).takeUnless { it.language.isBlank() } ?: Locale.SIMPLIFIED_CHINESE
        val utteranceId = "momo-${text.hashCode()}-${System.nanoTime()}"
        synchronized(pendingCallbacks) {
            pendingCallbacks[utteranceId] = onError to onComplete
        }
        val result = engine.speak(text, AndroidTextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == AndroidTextToSpeech.ERROR) {
            synchronized(pendingCallbacks) { pendingCallbacks.remove(utteranceId) }
            onError("语音播报失败")
        }
    }

    override fun stop() {
        synchronized(pendingCallbacks) { pendingCallbacks.clear() }
        tts?.stop()
    }

    override fun dispose() {
        synchronized(pendingCallbacks) { pendingCallbacks.clear() }
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}

private class AndroidPlatformAudioPlayer : PlatformAudioPlayer {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var player: MediaPlayer? = null
    private var active = false

    override fun play(bytes: ByteArray, mimeType: String, onComplete: () -> Unit, onError: (String) -> Unit) {
        stopInternal()
        val tempFile = runCatching {
            File.createTempFile("momo-tts-", ".mp3", AndroidContext.application.cacheDir).apply {
                writeBytes(bytes)
            }
        }.getOrElse {
            mainHandler.post { onError("音频缓冲失败") }
            return
        }
        val mediaPlayer = MediaPlayer()
        try {
            mediaPlayer.setDataSource(tempFile.absolutePath)
            mediaPlayer.setOnCompletionListener {
                val shouldComplete = synchronized(lock) {
                    if (!active) return@synchronized false
                    active = false
                    runCatching { tempFile.delete() }
                    runCatching { it.release() }
                    if (player === it) player = null
                    true
                }
                if (shouldComplete) mainHandler.post { onComplete() }
            }
            mediaPlayer.setOnErrorListener { mp, _, _ ->
                val shouldError = synchronized(lock) {
                    active = false
                    runCatching { tempFile.delete() }
                    runCatching { mp.release() }
                    if (player === mp) player = null
                    true
                }
                if (shouldError) mainHandler.post { onError("音频播放失败") }
                true
            }
            mediaPlayer.prepare()
            synchronized(lock) {
                active = true
                player = mediaPlayer
            }
            mediaPlayer.start()
        } catch (e: Throwable) {
            synchronized(lock) {
                active = false
                runCatching { tempFile.delete() }
                runCatching { mediaPlayer.release() }
                player = null
            }
            mainHandler.post { onError(e.message ?: "音频播放失败") }
        }
    }

    override fun stop() = stopInternal()

    private fun stopInternal() {
        synchronized(lock) {
            active = false
            player?.let { current ->
                runCatching { current.stop() }
                runCatching { current.release() }
            }
            player = null
        }
    }

    override fun dispose() = stopInternal()
}
