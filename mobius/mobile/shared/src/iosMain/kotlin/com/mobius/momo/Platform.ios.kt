@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.mobius.momo.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
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
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.usePinned
import kotlinx.serialization.json.Json
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioPlayerDelegateProtocol
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionModeMeasurement
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.setActive
import platform.Foundation.*
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIWindow
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

actual fun createSecureStorage(): SecureStorage = IosSecureStorage()

actual fun platformBuildBaseUrl(): String =
    (NSBundle.mainBundle.objectForInfoDictionaryKey("MOMO_BASE_URL") as? String)
        ?.takeIf { it.isNotBlank() }
        ?: "https://cloud-17.agent-matrix.com"

// iOS: .ignoresSafeArea() 让 Compose 延伸到安全区, navigationBarsPadding 不可靠,
// 用硬编码兜底 home indicator 高度. 仅用此值, 不叠加 navigationBarsPadding.
actual fun platformBottomTabPaddingDp(): Float = 34f

// iOS 版本: 读 Info.plist 的 CFBundleShortVersionString。
actual fun platformAppVersion(): String =
    (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String)
        ?.takeIf { it.isNotBlank() }
        ?: "0.1.4"

// iOS 文件选择: UIDocumentPickerViewController(Files app / iCloud / 相册文件)。
// 通过 KMPInterop 从 Compose 层调起 — 这里用 UIApplication 顶层 viewController present。
// 读文件到内存(PickedFile 携带 bytes), 与 Android FilePicker 契约一致。
actual fun createFilePicker(): FilePicker = object : FilePicker {
    override fun pickFiles(
        maxFiles: Int,
        onResult: (List<PickedFile>) -> Unit,
        onError: (String) -> Unit,
    ) {
        val pickerDelegate = DocumentPickerDelegate(
            maxFiles = maxFiles.coerceAtLeast(1),
            onResult = onResult,
            onError = onError,
        )
        val picker = UIDocumentPickerViewController(
            forOpeningContentTypes = listOf(UTTypeItem),
            asCopy = true, // 拷贝到临时目录, 避免 iCloud 占位文件读不到字节
        )
        picker.delegate = pickerDelegate
        picker.allowsMultipleSelection = maxFiles > 1
        // 找到当前顶层 VC present(模态)
        val rootVc = UIApplication.sharedApplication.keyWindow?.rootViewController
            ?: UIApplication.sharedApplication.windows
                .firstNotNullOfOrNull { (it as? UIWindow)?.rootViewController }
        var top = rootVc
        while (top?.presentedViewController != null) top = top.presentedViewController
        if (top == null) {
            onError("无法打开文件选择器")
            return
        }
        top.presentViewController(picker, animated = true, completion = null)
        // delegate 强引用保活: picker dismiss 后释放(用 objc 关联或静态列表)。
        DocumentPickerRetain.retain(pickerDelegate)
    }
}

// 保活已 present 的 delegate, 避免 Kotlin 对象被 GC 后回调丢失。
private object DocumentPickerRetain {
    private val retained = mutableSetOf<Any>()
    fun retain(obj: Any) { retained.add(obj) }
    fun release(obj: Any) { retained.remove(obj) }
}

private class DocumentPickerDelegate(
    private val maxFiles: Int,
    private val onResult: (List<PickedFile>) -> Unit,
    private val onError: (String) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        DocumentPickerRetain.release(this)
        @Suppress("UNCHECKED_CAST")
        val rawUrls = didPickDocumentsAtURLs as? List<NSURL> ?: emptyList()
        val urls = rawUrls.take(maxFiles)
        if (urls.isEmpty()) {
            onResult(emptyList())
            return
        }
        val picked = mutableListOf<PickedFile>()
        for (url in urls) {
            val name = url.lastPathComponent ?: "file"
            val gotAccess = url.startAccessingSecurityScopedResource()
            try {
                val nsData = runCatching { NSData.dataWithContentsOfURL(url) }.getOrNull()
                if (nsData == null) {
                    onError("读取文件失败: $name")
                    return
                }
                val ext = name.substringAfterLast('.', "").lowercase()
                val mime = when (ext) {
                    "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"
                    "webp" -> "image/webp"; "heic" -> "image/heic"
                    "pdf" -> "application/pdf"; "txt" -> "text/plain"
                    "json" -> "application/json"; "csv" -> "text/csv"
                    "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "wav" -> "audio/wav"
                    "mp4" -> "video/mp4"; "mov" -> "video/quicktime"
                    else -> "application/octet-stream"
                }
                picked.add(PickedFile(name = name, mimeType = mime, bytes = nsData.toByteArray()))
            } finally {
                if (gotAccess) url.stopAccessingSecurityScopedResource()
            }
        }
        onResult(picked)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        DocumentPickerRetain.release(this)
        onResult(emptyList())
    }
}

private class IosSecureStorage : SecureStorage {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun saveToken(token: String) {
        defaults.setObject(token, forKey = TOKEN_KEY)
        defaults.synchronize()
    }

    override fun getToken(): String? =
        defaults.stringForKey(TOKEN_KEY)

    override fun saveTokenMetadata(metadata: StoredTokenMetadata) {
        defaults.setObject(metadata.baseUrl, forKey = TOKEN_BASE_URL_KEY)
        defaults.setObject(metadata.savedAtEpochMillis.toString(), forKey = TOKEN_SAVED_AT_KEY)
        defaults.setObject(metadata.storageVersion.toString(), forKey = TOKEN_STORAGE_VERSION_KEY)
        defaults.synchronize()
    }

    override fun getTokenMetadata(): StoredTokenMetadata? =
        storedTokenMetadataFromStrings(
            baseUrl = defaults.stringForKey(TOKEN_BASE_URL_KEY),
            savedAtEpochMillis = defaults.stringForKey(TOKEN_SAVED_AT_KEY),
            storageVersion = defaults.stringForKey(TOKEN_STORAGE_VERSION_KEY),
        )

    override fun savePreference(key: String, value: String) {
        defaults.setObject(value, forKey = "momo.pref.$key")
        defaults.synchronize()
    }

    override fun getPreference(key: String): String? =
        defaults.stringForKey("momo.pref.$key")

    override fun clear() {
        defaults.removeObjectForKey(TOKEN_KEY)
        defaults.removeObjectForKey(TOKEN_BASE_URL_KEY)
        defaults.removeObjectForKey(TOKEN_SAVED_AT_KEY)
        defaults.removeObjectForKey(TOKEN_STORAGE_VERSION_KEY)
        defaults.synchronize()
    }

    private companion object {
        const val TOKEN_KEY = "momo.secure.token"
        const val TOKEN_BASE_URL_KEY = "momo.secure.token.baseUrl"
        const val TOKEN_SAVED_AT_KEY = "momo.secure.token.savedAtEpochMillis"
        const val TOKEN_STORAGE_VERSION_KEY = "momo.secure.token.storageVersion"
    }
}

actual fun createMobiusHttpClient(
    onUnauthorized: suspend () -> Unit,
): HttpClient = HttpClient(Darwin) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true })
    }
    install(Logging) {
        level = LogLevel.INFO
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

@OptIn(ExperimentalForeignApi::class)
actual fun nowShortTime(): String {
    val formatter = NSDateFormatter()
    formatter.dateFormat = "HH:mm"
    return formatter.stringFromDate(NSDate())
}

actual fun nowEpochMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()

@OptIn(ExperimentalForeignApi::class)
actual fun nowIsoTime(): String {
    val formatter = NSDateFormatter()
    formatter.locale = NSLocale.localeWithLocaleIdentifier("en_US_POSIX")
    formatter.timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)
    formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
    return formatter.stringFromDate(NSDate())
}

actual fun parseBackendTimeMillis(value: String?): Long? {
    var raw = value?.takeIf { it.isNotBlank() } ?: return null
    // 宽容规范化: 微秒(6位)截成毫秒; +00:00/+0000 偏移换 Z; 空格分隔符换 T。
    raw = Regex("T\\d\\d:\\d\\d:\\d\\d\\.(\\d{6,})").replace(raw) { m ->
        "T" + raw.substringAfter("T").substringBefore(".") + "." + m.groupValues[1].take(3)
    }
    if (raw.endsWith("+00:00")) raw = raw.removeSuffix("+00:00") + "Z"
    if (raw.endsWith("+0000")) raw = raw.removeSuffix("+0000") + "Z"
    val date = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
    ).firstNotNullOfOrNull { pattern ->
        val parser = NSDateFormatter()
        parser.locale = NSLocale.localeWithLocaleIdentifier("en_US_POSIX")
        parser.timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)
        parser.dateFormat = pattern
        parser.dateFromString(raw)
    } ?: return null
    return (date.timeIntervalSince1970 * 1000.0).toLong()
}

actual fun formatBackendTime(value: String?): String {
    val raw = value?.takeIf { it.isNotBlank() } ?: return ""
    val date = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
    ).firstNotNullOfOrNull { pattern ->
        val parser = NSDateFormatter()
        parser.locale = NSLocale.localeWithLocaleIdentifier("en_US_POSIX")
        parser.timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)
        parser.dateFormat = pattern
        parser.dateFromString(raw)
    } ?: return ""
    // 全局统一北京时间（CST, UTC+8）+ 24 小时制 (Issue 8f64748a)
    val cal = NSCalendar.currentCalendar()
    cal.timeZone = NSTimeZone.timeZoneForSecondsFromGMT(8 * 3600) // Asia/Shanghai (UTC+8)
    val unitFlags = NSCalendarUnitHour or NSCalendarUnitMinute or NSCalendarUnitMonth or NSCalendarUnitDay
    val comps = cal.components(unitFlags, date)
    val hour = comps.hour.toInt()
    val time = "${hour.toString().padStart(2, '0')}:${comps.minute.toString().padStart(2, '0')}"
    return when {
        cal.isDateInToday(date) -> time
        cal.isDateInYesterday(date) -> "昨天 $time"
        else -> "${comps.month}月${comps.day}日 $time"
    }
}

actual fun createSpeechPermissionController(): SpeechPermissionController = IosSpeechPermissionController()

private class IosSpeechPermissionController : SpeechPermissionController {
    override fun hasPermission(): Boolean =
        AVAudioSession.sharedInstance().recordPermission() ==
            platform.AVFAudio.AVAudioSessionRecordPermissionGranted

    override suspend fun requestPermission(): SpeechPermissionStatus = suspendCoroutine { continuation ->
        AVAudioSession.sharedInstance().requestRecordPermission { granted ->
            continuation.resume(if (granted) SpeechPermissionStatus.Granted else SpeechPermissionStatus.Denied)
        }
    }
}

actual fun createSpeechRecognizer(): SpeechRecognizer = IosAudioRecorder()

private class IosAudioRecorder : SpeechRecognizer {
    private var recorder: AVAudioRecorder? = null
    private var outputUrl: NSURL? = null
    private var onEvent: ((SpeechRecognitionEvent) -> Unit)? = null
    @Volatile private var active: Boolean = false

    override fun start(languageTag: String, onEvent: (SpeechRecognitionEvent) -> Unit) {
        cancel()
        this.onEvent = onEvent
        val session = AVAudioSession.sharedInstance()
        runCatching {
            session.setCategory(AVAudioSessionCategoryPlayAndRecord, error = null)
            session.setMode(AVAudioSessionModeDefault, error = null)
            session.setActive(true, error = null)
        }.onFailure {
            onEvent(SpeechRecognitionEvent.Error("麦克风启动失败"))
            return
        }

        val tempDir = NSTemporaryDirectory()
        val path = "$tempDir/momo-voice-${NSUUID().UUIDString}.m4a"
        val url = NSURL.fileURLWithPath(path)
        outputUrl = url
        val settings: Map<Any?, *> = mapOf(
            platform.AVFAudio.AVFormatIDKey to platform.CoreAudioTypes.kAudioFormatMPEG4AAC,
            platform.AVFAudio.AVSampleRateKey to 16000.0,
            platform.AVFAudio.AVNumberOfChannelsKey to 1,
            platform.AVFAudio.AVEncoderAudioQualityKey to platform.AVFAudio.AVAudioQualityMedium,
        )
        val newRecorder = runCatching { AVAudioRecorder(uRL = url, settings = settings, error = null) }.getOrNull()
        if (newRecorder == null || !newRecorder.prepareToRecord()) {
            onEvent(SpeechRecognitionEvent.Error("录音初始化失败"))
            return
        }
        if (!newRecorder.record()) {
            onEvent(SpeechRecognitionEvent.Error("录音启动失败"))
            return
        }
        recorder = newRecorder
        active = true
        onEvent(SpeechRecognitionEvent.Ready)
    }

    override fun stop() {
        val recorderRef = recorder
        val urlRef = outputUrl
        val eventRef = onEvent
        if (!active || recorderRef == null || urlRef == null) {
            cleanup()
            return
        }
        active = false
        recorderRef.stop()
        eventRef?.invoke(SpeechRecognitionEvent.Volume(0))
        val urlPath = urlRef.path ?: ""
        val fileManager = NSFileManager.defaultManager
        if (urlPath.isEmpty() || !fileManager.fileExistsAtPath(urlPath)) {
            eventRef?.invoke(SpeechRecognitionEvent.Error("录音内容为空,请重新录制"))
            cleanup()
            return
        }
        val bytes = runCatching {
            NSData.dataWithContentsOfURL(urlRef)?.toByteArray()
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            eventRef?.invoke(SpeechRecognitionEvent.Error("录音保存失败,请重新录制"))
            cleanup()
            return
        }
        eventRef?.invoke(SpeechRecognitionEvent.Audio(bytes, "audio/mp4", "momo-voice.m4a"))
        eventRef?.invoke(SpeechRecognitionEvent.End)
        cleanup()
    }

    override fun cancel() {
        active = false
        runCatching { recorder?.stop() }
        cleanup()
    }

    override fun dispose() {
        active = false
        runCatching { recorder?.stop() }
        cleanup()
        onEvent = null
    }

    private fun cleanup() {
        recorder = null
        outputUrl?.let { url -> runCatching { NSFileManager.defaultManager.removeItemAtURL(url, error = null) } }
        outputUrl = null
    }
}

actual fun createTextToSpeech(): TextToSpeech = IosSystemTextToSpeech()

actual fun createSystemTtsEngine(): TtsEngine = SystemTtsEngine(createTextToSpeech())

internal actual fun createPlatformAudioPlayer(): PlatformAudioPlayer = IosPlatformAudioPlayer()

private class IosSpeechSynthesizerDelegate(
    private val onComplete: () -> Unit,
    private val onError: (String) -> Unit,
) : NSObject(), AVSpeechSynthesizerDelegateProtocol {
    @ObjCSignatureOverride
    override fun speechSynthesizer(
        synthesizer: AVSpeechSynthesizer,
        didFinishSpeechUtterance: AVSpeechUtterance,
    ) = onComplete()

    @ObjCSignatureOverride
    override fun speechSynthesizer(
        synthesizer: AVSpeechSynthesizer,
        didCancelSpeechUtterance: AVSpeechUtterance,
    ) = onComplete()

    @ObjCSignatureOverride
    override fun speechSynthesizer(
        synthesizer: AVSpeechSynthesizer,
        didPauseSpeechUtterance: AVSpeechUtterance,
    ) = Unit

    @ObjCSignatureOverride
    override fun speechSynthesizer(
        synthesizer: AVSpeechSynthesizer,
        didContinueSpeechUtterance: AVSpeechUtterance,
    ) = Unit

    @ObjCSignatureOverride
    override fun speechSynthesizer(
        synthesizer: AVSpeechSynthesizer,
        didStartSpeechUtterance: AVSpeechUtterance,
    ) = Unit
}

private class IosSystemTextToSpeech : TextToSpeech {
    private val synthesizer = AVSpeechSynthesizer()
    @Volatile private var activeDelegate: IosSpeechSynthesizerDelegate? = null

    override fun speak(
        text: String,
        languageTag: String,
        onError: (String) -> Unit,
        onComplete: () -> Unit,
    ) {
        if (text.isBlank()) {
            onComplete()
            return
        }
        stopInternal()
        var delegateRef: IosSpeechSynthesizerDelegate? = null
        val delegate = IosSpeechSynthesizerDelegate(
            onComplete = {
                if (activeDelegate === delegateRef) {
                    activeDelegate = null
                    synthesizer.delegate = null
                }
                onComplete()
            },
            onError = { message ->
                if (activeDelegate === delegateRef) {
                    activeDelegate = null
                    synthesizer.delegate = null
                }
                onError(message)
            },
        )
        delegateRef = delegate
        activeDelegate = delegate
        synthesizer.delegate = delegate
        val session = AVAudioSession.sharedInstance()
        runCatching {
            // 关键: 带 defaultToSpeaker 选项 — 否则录音后的会话仍路由到听筒(贴耳小声),
            // 用户以为"没有播报"。播报走扬声器, 与语音输入的 PlayAndRecord 区分开。
            session.setCategory(
                AVAudioSessionCategoryPlayback,
                platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker,
                null,
            )
            session.setMode(AVAudioSessionModeDefault, error = null)
            session.setActive(true, error = null)
        }.onFailure {
            // 带选项设置失败时退回无选项版(仍可出声)
            runCatching {
                session.setCategory(AVAudioSessionCategoryPlayback, error = null)
                session.setActive(true, error = null)
            }
        }
        val utterance = AVSpeechUtterance.speechUtteranceWithString(text)
        // 中文 voice 在部分设备(未装中文 TTS 的模拟器/精简系统)返回 null → 默认英文引擎
        // 会静默吞掉中文(听感即"没有播报")。逐级降级: 指定语言 → zh-TW → 任意中文 → nil。
        val zhVoice: AVSpeechSynthesisVoice? = AVSpeechSynthesisVoice.voiceWithLanguage(languageTag)
            ?: AVSpeechSynthesisVoice.voiceWithLanguage("zh-TW")
        utterance.voice = zhVoice
        utterance.rate = 0.48f
        synthesizer.speakUtterance(utterance)
    }

    override fun stop() = stopInternal()

    private fun stopInternal() {
        if (synthesizer.speaking) synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        activeDelegate = null
        synthesizer.delegate = null
    }

    override fun dispose() = stopInternal()
}

private class IosAudioPlayerDelegate(
    private val isAlive: () -> Boolean,
    private val onComplete: () -> Unit,
    private val onError: (String) -> Unit,
) : NSObject(), AVAudioPlayerDelegateProtocol {
    override fun audioPlayerDidFinishPlaying(player: AVAudioPlayer, successfully: Boolean) {
        if (!isAlive()) return
        if (successfully) onComplete() else onError("音频播放失败")
    }

    override fun audioPlayerDecodeErrorDidOccur(player: AVAudioPlayer, error: NSError?) {
        if (!isAlive()) return
        onError(error?.localizedDescription ?: "音频解码失败")
    }
}

private class IosPlatformAudioPlayer : PlatformAudioPlayer {
    private var player: AVAudioPlayer? = null
    private var delegate: IosAudioPlayerDelegate? = null
    @Volatile private var active: Boolean = false

    @Suppress("SENSELESS_COMPARISON")
    override fun play(bytes: ByteArray, mimeType: String, onComplete: () -> Unit, onError: (String) -> Unit) {
        cleanupPlayer()
        val session = AVAudioSession.sharedInstance()
        runCatching {
            // 同 TTS: 扬声器路由, 避免听筒小声被当成"没播报"。
            session.setCategory(
                AVAudioSessionCategoryPlayback,
                platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker,
                null,
            )
            session.setActive(true, error = null)
        }.onFailure {
            runCatching {
                session.setCategory(AVAudioSessionCategoryPlayback, error = null)
                session.setActive(true, error = null)
            }
        }
        val nsData = bytes.usePinned { pin ->
            NSData.create(bytes = pin.addressOf(0), length = bytes.size.toULong())
        }
        if (nsData == null) {
            onError("音频缓冲失败")
            return
        }
        val audioPlayer = runCatching {
            @Suppress("UNCHECKED_CAST")
            AVAudioPlayer(data = nsData, error = null)
        }.getOrNull()
        if (audioPlayer == null) {
            onError("音频初始化失败")
            return
        }
        val playerDelegate = IosAudioPlayerDelegate(
            isAlive = { active },
            onComplete = {
                active = false
                cleanupPlayer()
                onComplete()
            },
            onError = { message ->
                active = false
                cleanupPlayer()
                onError(message)
            },
        )
        active = true
        delegate = playerDelegate
        audioPlayer.delegate = playerDelegate
        audioPlayer.prepareToPlay()
        audioPlayer.play()
        player = audioPlayer
    }

    override fun stop() = cleanupPlayer()

    override fun dispose() = cleanupPlayer()

    private fun cleanupPlayer() {
        active = false
        val current = player
        delegate = null
        current?.let {
            runCatching { it.pause() }
            runCatching { it.stop() }
            it.delegate = null
        }
        player = null
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size <= 0) return ByteArray(0)
    val bytes = ByteArray(size)
    val src = bytes // for clarity
    bytes.usePinned { pinned ->
        platform.posix.memcpy(pinned.addressOf(0), this@toByteArray.bytes, this@toByteArray.length)
    }
    return src
}
