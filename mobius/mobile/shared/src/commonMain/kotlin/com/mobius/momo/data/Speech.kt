package com.mobius.momo.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class SpeechPermissionStatus {
    Granted,
    Denied,
    NotDetermined,
}

interface SpeechPermissionController {
    fun hasPermission(): Boolean
    suspend fun requestPermission(): SpeechPermissionStatus
}

sealed interface SpeechRecognitionEvent {
    data object Ready : SpeechRecognitionEvent
    data class Partial(val text: String) : SpeechRecognitionEvent
    data class Final(val text: String) : SpeechRecognitionEvent
    data class Audio(val bytes: ByteArray, val mimeType: String, val fileName: String) : SpeechRecognitionEvent
    data class Volume(val level: Int) : SpeechRecognitionEvent
    data class Error(val message: String) : SpeechRecognitionEvent
    data object End : SpeechRecognitionEvent
}

interface SpeechRecognizer {
    fun start(
        languageTag: String = "zh-CN",
        onEvent: (SpeechRecognitionEvent) -> Unit,
    )

    fun stop()
    fun cancel()
    fun dispose()
}

interface TextToSpeech {
    fun speak(
        text: String,
        languageTag: String = "zh-CN",
        onError: (String) -> Unit = {},
        onComplete: () -> Unit = {},
    )

    fun stop()
    fun dispose()
}

interface TtsEngine {
    val isAvailable: Boolean
    suspend fun speak(text: String, voice: String? = null, onStart: () -> Unit = {}): Result<Unit>

    // 预取并缓存(不播放), 用于消除切换音色后首次播放的网络往返滞后. 默认空操作(系统语音本地合成, 无需预取).
    suspend fun prefetch(text: String, voice: String? = null) {}

    fun stop()
    fun dispose()
}

data class Voice(
    val id: String,
    val label: String,
    val description: String? = null,
    val isDefault: Boolean = false,
)

const val TTS_SYSTEM_VOICE_ID = "system"
const val TTS_DEFAULT_DOUBAO_VOICE = "zh_female_vv_uranus_bigtts"
const val DOUBAO_TTS_CACHE_CAP = 16
// 单次豆包合成取音频的总超时(含连接+合成+下载). 配合 fetchDoubaoTtsAudio 里更短的 connect 超时,
// 连不上时尽快失败 → 回退系统语音, 而不是干等到全局 15s connectTimeout.
const val DOUBAO_TTS_FETCH_TIMEOUT_MILLIS = 20_000L

expect fun createSpeechPermissionController(): SpeechPermissionController

expect fun createSpeechRecognizer(): SpeechRecognizer

expect fun createTextToSpeech(): TextToSpeech

expect fun createSystemTtsEngine(): TtsEngine

internal interface PlatformAudioPlayer {
    fun play(bytes: ByteArray, mimeType: String, onComplete: () -> Unit, onError: (String) -> Unit)
    fun stop()
    fun dispose()
}

internal expect fun createPlatformAudioPlayer(): PlatformAudioPlayer

// runCatching 会把协程取消(CancellationException)也当成普通异常吞掉并返回 failure。
// 但取消并不是"引擎失败":用户在语音播报中点击其他消息/空白区域时,stopSpeaking() 取消 speak 协程,
// 若被误判为失败就会触发 fallback——弹出"系统语音不可用,已切换到豆包"toast,并改用豆包继续播报,
// 与"停止播放"的意图相悖。这里恢复取消语义,让取消正常向上传播,不进入 fallback 分支。
internal fun <T> Result<T>.propagateCancellation(): Result<T> {
    exceptionOrNull()?.let { if (it is CancellationException) throw it }
    return this
}

class SystemTtsEngine(private val tts: TextToSpeech) : TtsEngine {
    override val isAvailable: Boolean = true

    override suspend fun speak(text: String, voice: String?, onStart: () -> Unit): Result<Unit> = runCatching {
        if (text.isBlank()) return@runCatching
        suspendCancellableCoroutine { cont ->
            tts.speak(
                text = text,
                languageTag = "zh-CN",
                onError = { message ->
                    if (cont.isActive) cont.resumeWith(Result.failure(RuntimeException(message)))
                },
                onComplete = {
                    if (cont.isActive) cont.resumeWith(Result.success(Unit))
                },
            )
            onStart() // 系统语音本地合成, 调用即视为开始播放
            cont.invokeOnCancellation { tts.stop() }
        }
    }.propagateCancellation()

    override fun stop() = tts.stop()
    override fun dispose() = tts.dispose()
}

class DoubaoTtsEngine(
    private val fetchAudio: suspend (String, String?) -> ByteArray,
) : TtsEngine {
    override val isAvailable: Boolean = true
    private val player = createPlatformAudioPlayer()
    private val lock = ReentrantLock()
    // 按 (voice, text) 缓存合成音频: 豆包是云端合成, 每次播放都要一次网络往返(切换音色/重播时的滞后主因).
    // 命中缓存即可直接播放. 访问时先 remove 再 put, 使其按最近使用淘汰(LRU), 上限 DOUBAO_TTS_CACHE_CAP 条.
    private val audioCache = LinkedHashMap<String, ByteArray>()
    // 同 (voice,text) 在途请求去重: prefetch 与 speak 同时命中未缓存项时共享同一次网络合成, 避免重复请求
    // (每次都可能卡在连接超时). winner 负责实际取, 其余 await 同一结果(成功则都拿到、失败则都回退).
    private val inFlight = LinkedHashMap<String, CompletableDeferred<ByteArray>>()

    private fun cacheKey(text: String, voice: String?): String = "${voice ?: "default"} $text"

    private fun cachedBytes(key: String): ByteArray? = lock.withLock { audioCache[key] }

    private fun storeCache(key: String, bytes: ByteArray) {
        lock.withLock {
            audioCache.remove(key)
            audioCache[key] = bytes
            while (audioCache.size > DOUBAO_TTS_CACHE_CAP) {
                val it = audioCache.entries.iterator()
                if (!it.hasNext()) break
                it.next()
                it.remove()
            }
        }
    }

    // 取音频: 命中缓存直接返回; 否则发起或复用在途请求(同 key 并发只发一次), 结果写入缓存.
    private suspend fun fetchOnce(text: String, voice: String?): ByteArray {
        val key = cacheKey(text, voice)
        cachedBytes(key)?.let { return it }
        val mine = CompletableDeferred<ByteArray>()
        val winner = lock.withLock { inFlight.getOrPut(key) { mine } }
        if (winner !== mine) return winner.await() // 已有在途请求, 等它的结果
        return try {
            val bytes = withTimeoutOrNull(DOUBAO_TTS_FETCH_TIMEOUT_MILLIS) { fetchAudio(text, voice) }
                ?: throw RuntimeException("豆包语音请求超时")
            if (bytes.isEmpty()) throw RuntimeException("豆包语音返回空音频")
            storeCache(key, bytes)
            mine.complete(bytes)
            bytes
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            lock.withLock { inFlight.remove(key) }
        }
    }

    // 预取并缓存(不播放): 切换音色/消息落定后立即把该音色下的音频备好, 随后点击播放/自动播报
    // 可直接命中缓存. 失败静默(到时 speak 会再取一次), 不影响主流程.
    override suspend fun prefetch(text: String, voice: String?) {
        if (text.isBlank()) return
        runCatching { fetchOnce(text, voice) }
    }

    override suspend fun speak(text: String, voice: String?, onStart: () -> Unit): Result<Unit> = runCatching {
        if (text.isBlank()) return@runCatching
        val bytes = fetchOnce(text, voice)
        onStart() // 音频已就绪, 即将开始播放(标志取音频/连接等待结束 → 关闭 loading)
        suspendCancellableCoroutine<Unit> { cont ->
            lock.withLock {
                player.play(
                    bytes = bytes,
                    mimeType = "audio/mpeg",
                    onComplete = {
                        if (cont.isActive) cont.resumeWith(Result.success(Unit))
                    },
                    onError = { message ->
                        if (cont.isActive) cont.resumeWith(Result.failure(RuntimeException(message)))
                    },
                )
            }
            cont.invokeOnCancellation { lock.withLock { player.stop() } }
        }
    }.propagateCancellation()

    override fun stop() = lock.withLock { player.stop() }
    override fun dispose() = lock.withLock { player.dispose() }
}

sealed interface TtsEvent {
    data class Started(val voice: String) : TtsEvent
    data class Fallback(val message: String) : TtsEvent
    data class Error(val message: String) : TtsEvent
}

class TtsController(
    private val systemEngine: TtsEngine,
    private val doubaoEngine: TtsEngine,
    private val onEvent: (TtsEvent) -> Unit = {},
) {
    var selectedVoice: String = TTS_SYSTEM_VOICE_ID
        private set

    fun setVoice(voice: String) {
        selectedVoice = voice
    }

    suspend fun speak(text: String, onStart: () -> Unit = {}): Result<Unit> {
        if (text.isBlank()) return Result.success(Unit)
        // 引擎可能 fallback(豆包→系统), 两个引擎各自会在播放开始时回调 onStart; 用 safeStart
        // 保证「真正开始播放」只通知一次, 避免豆包预触发后再回退系统时二次回调。
        var started = false
        val safeStart: () -> Unit = { if (!started) { started = true; onStart() } }
        return if (selectedVoice == TTS_SYSTEM_VOICE_ID) {
            speakWithSystemFallbackChain(text, safeStart)
        } else {
            speakWithDoubaoFallbackChain(text, selectedVoice, safeStart)
        }
    }

    // 切换音色后预取: 把文本在当前所选(豆包)音色下合成好并缓存, 使随后的播放/自动播报直接命中缓存.
    // 系统音色为本地合成, 无需预取.
    suspend fun prefetch(text: String) {
        if (text.isBlank() || selectedVoice == TTS_SYSTEM_VOICE_ID) return
        doubaoEngine.prefetch(text, selectedVoice)
    }

    private suspend fun speakWithSystemFallbackChain(text: String, onStart: () -> Unit): Result<Unit> {
        if (systemEngine.isAvailable) {
            val result = systemEngine.speak(text, onStart = onStart)
            if (result.isSuccess) {
                onEvent(TtsEvent.Started(TTS_SYSTEM_VOICE_ID))
                return result
            }
            onEvent(TtsEvent.Fallback("系统语音不可用,已切换到豆包"))
        }
        val doubaoResult = doubaoEngine.speak(text, voice = TTS_DEFAULT_DOUBAO_VOICE, onStart = onStart)
        if (doubaoResult.isSuccess) {
            onEvent(TtsEvent.Started(TTS_DEFAULT_DOUBAO_VOICE))
        } else {
            onEvent(TtsEvent.Error(doubaoResult.exceptionOrNull()?.message ?: "豆包语音失败"))
        }
        return doubaoResult
    }

    private suspend fun speakWithDoubaoFallbackChain(text: String, voice: String, onStart: () -> Unit): Result<Unit> {
        val doubaoResult = doubaoEngine.speak(text, voice = voice, onStart = onStart)
        if (doubaoResult.isSuccess) {
            onEvent(TtsEvent.Started(voice))
            return doubaoResult
        }
        if (systemEngine.isAvailable) {
            onEvent(TtsEvent.Fallback("豆包请求失败,已切换到系统语音"))
            val systemResult = systemEngine.speak(text, onStart = onStart)
            if (systemResult.isSuccess) {
                onEvent(TtsEvent.Started(TTS_SYSTEM_VOICE_ID))
            } else {
                onEvent(TtsEvent.Error(systemResult.exceptionOrNull()?.message ?: "系统语音失败"))
            }
            return systemResult
        }
        onEvent(TtsEvent.Error(doubaoResult.exceptionOrNull()?.message ?: "豆包语音失败"))
        return doubaoResult
    }

    fun stop() {
        systemEngine.stop()
        doubaoEngine.stop()
    }

    fun dispose() {
        systemEngine.dispose()
        doubaoEngine.dispose()
    }
}

private val ttsErrorJson = Json { ignoreUnknownKeys = true }

internal suspend fun fetchDoubaoTtsAudio(
    client: HttpClient,
    baseUrl: String,
    token: String?,
    text: String,
    voice: String?,
): ByteArray {
    val response = client.post("$baseUrl/api/assistant/speak") {
        if (!token.isNullOrBlank()) bearerAuth(token)
        contentType(ContentType.Application.Json)
        // TTS 用独立的(更短)连接超时: 全局 HttpClient 的 connectTimeoutMillis=15s, 豆包连不上时会干等满 15s
        // 才回退系统语音(用户感知"15s 空白后才开始/不播"). 这里把连接阶段压到 8s, 连不上尽快失败回退。
        timeout {
            connectTimeoutMillis = 8_000L
            requestTimeoutMillis = DOUBAO_TTS_FETCH_TIMEOUT_MILLIS
            socketTimeoutMillis = DOUBAO_TTS_FETCH_TIMEOUT_MILLIS
        }
        setBody(
            buildMap {
                put("text", text)
                if (!voice.isNullOrBlank()) put("voice", voice)
            },
        )
    }
    if (!response.status.isSuccess()) {
        // 后端 /speak 失败时返回 JSON {ok:false, code, error/message}, 带具体原因(如"TTS 凭据未配置")。
        // 解析出来, 否则用户只看到 HTTP 状态码, 无法排查为何失败。
        val raw = runCatching { response.bodyAsText() }.getOrDefault("")
        val backendMsg = runCatching {
            val obj = ttsErrorJson.parseToJsonElement(raw) as? JsonObject
            (obj?.get("error") as? JsonPrimitive)?.contentOrNull
                ?: (obj?.get("message") as? JsonPrimitive)?.contentOrNull
        }.getOrNull()
        throw RuntimeException(
            backendMsg?.takeIf { it.isNotBlank() } ?: "豆包 TTS 失败: HTTP ${response.status.value}",
        )
    }
    val bytes = response.readBytes()
    if (bytes.isEmpty()) throw RuntimeException("豆包 TTS 返回空音频")
    return bytes
}

data class TtsVoicesResult(val voices: List<Voice>, val configured: Boolean)

internal fun parseTtsVoices(body: JsonObject): TtsVoicesResult {
    // configured 缺省视为 true(老后端不返回该字段时向后兼容), 仅明确 "false" 才算未配置。
    val configured = (body["configured"] as? JsonPrimitive)?.contentOrNull?.lowercase() != "false"
    val array = body["voices"] as? JsonArray
    val defaultId = (body["default_voice"] as? JsonPrimitive)?.contentOrNull
    val voices = array?.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val id = obj.stringField("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        // label 为空(该项后端未配置/数据损坏) → 直接跳过, 不渲染成"空白文字 + 单选框"的多余项。
        val label = obj.stringField("label")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val description = obj.stringField("description")
        Voice(id = id, label = label, description = description, isDefault = id == defaultId)
    }
        ?.distinctBy { it.id }
        .orEmpty()
    return TtsVoicesResult(voices, configured)
}

private fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
