package com.mobius.momo.data

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MobiusApiUploadTest {
    @Test
    fun uploadAttachmentSendsMulterCompatibleMultipart() = runBlocking {
        RawHttpCaptureServer().use { server ->
            val api = MobiusApi(
                baseUrl = server.baseUrl,
                storage = TestSecureStorage("test-token"),
                onUnauthorized = {},
            )
            val result = api.uploadAttachment(
                PickedFile(
                    name = "\u70b9\u7fe0 file.txt",
                    mimeType = "text/plain",
                    bytes = "hello".encodeToByteArray(),
                ),
            )

            val request = server.awaitRequest()
            val contentType = request.headers["content-type"]
            val bodyText = request.body.toString(StandardCharsets.ISO_8859_1)

            api.close()

            assertEquals("/api/upload", request.path)
            assertEquals("/tmp/uploaded", result.path)
            assertNotNull(contentType, "missing content-type; headers=${request.headers}")
            assertTrue(
                contentType.startsWith("multipart/form-data; boundary=momo-mobile-upload-"),
                "unexpected content-type=$contentType; headers=${request.headers}",
            )
            assertNotNull(request.headers["content-length"], "missing content-length; headers=${request.headers}")
            assertNull(request.headers["transfer-encoding"], "multipart upload must not be chunked; headers=${request.headers}")
            assertTrue(bodyText.contains("name=\"file\""), bodyText)
            assertTrue(bodyText.contains("filename=\"file.txt\""), bodyText)
            assertFalse(bodyText.contains("filename*="), bodyText)
            assertTrue(bodyText.contains("\r\n\r\nhello\r\n"), bodyText)
        }
    }

    /**
     * 语音转写回归守护（Desktop/Android 格式）：两端录音 API 不同（AudioRecord / TargetDataLine）
     * 但都产出 audio/wav，并经同一个共享链路 transcribeAssistantAudio → multipartFileContent 上传。
     * 验证命中 /api/assistant/transcribe 的 audio 字段，且为带 content-length（非 chunked）的标准
     * multipart/form-data——这正是历次「上传 N 字节后端却 ASR_AUDIO_EMPTY」的根因所在。
     */
    @Test
    fun transcribeWavVoiceSendsMulterCompatibleMultipart() = runBlocking {
        RawHttpCaptureServer(responseBody = """{"text":"你好"}""").use { server ->
            val api = MobiusApi(
                baseUrl = server.baseUrl,
                storage = TestSecureStorage("test-token"),
                onUnauthorized = {},
            )
            // 模仿 AudioRecord/TargetDataLine 产出的标准 WAV：RIFF....WAVE 头 + PCM。
            val payload = ByteArray(12_000) { (it and 0xFF).toByte() }
            payload[0] = 'R'.code.toByte()
            payload[1] = 'I'.code.toByte()
            payload[2] = 'F'.code.toByte()
            payload[3] = 'F'.code.toByte()

            val text = api.transcribeAssistantAudio(payload, "audio/wav", "momo-voice.wav")
            val request = server.awaitRequest()
            api.close()

            assertEquals("你好", text)
            assertMulterCompatibleAudioPart(request, "audio/wav", "momo-voice.wav", payload)
        }
    }

    /**
     * 语音转写回归守护（iOS 格式）：iOS 用 AVAudioRecorder 产出 AAC m4a（audio/mp4），engine 是 Darwin，
     * 与 Android/Desktop 的 OkHttp 不同——但同样走共享 multipartFileContent 上传。验证 m4a 格式也产出
     * multer 兼容、带 content-length 的标准 multipart，确保 iOS 端不会复现「ASR_AUDIO_EMPTY」。
     */
    @Test
    fun transcribeM4aVoiceSendsMulterCompatibleMultipart() = runBlocking {
        RawHttpCaptureServer(responseBody = """{"text":"你好小莫"}""").use { server ->
            val api = MobiusApi(
                baseUrl = server.baseUrl,
                storage = TestSecureStorage("test-token"),
                onUnauthorized = {},
            )
            // 模仿 AVAudioRecorder 产出的 m4a：....ftyp box 头。
            val payload = ByteArray(9_000) { (it and 0xFF).toByte() }
            payload[4] = 'f'.code.toByte()
            payload[5] = 't'.code.toByte()
            payload[6] = 'y'.code.toByte()
            payload[7] = 'p'.code.toByte()

            val text = api.transcribeAssistantAudio(payload, "audio/mp4", "momo-voice.m4a")
            val request = server.awaitRequest()
            api.close()

            assertEquals("你好小莫", text)
            assertMulterCompatibleAudioPart(request, "audio/mp4", "momo-voice.m4a", payload)
        }
    }

    private fun assertMulterCompatibleAudioPart(
        request: CapturedRequest,
        expectedMime: String,
        expectedFileName: String,
        payload: ByteArray,
    ) {
        val contentType = request.headers["content-type"]
        val bodyText = request.body.toString(StandardCharsets.ISO_8859_1)
        assertEquals("/api/assistant/transcribe", request.path)
        assertNotNull(contentType, "missing content-type; headers=${request.headers}")
        assertTrue(
            contentType.startsWith("multipart/form-data; boundary=momo-mobile-audio-"),
            "unexpected content-type=$contentType; headers=${request.headers}",
        )
        // content-length 必须存在且非 chunked——这是「multer 找不到 audio 字段 → ASR_AUDIO_EMPTY」根因的回归守护。
        assertNotNull(request.headers["content-length"], "missing content-length; headers=${request.headers}")
        assertNull(request.headers["transfer-encoding"], "voice upload must not be chunked; headers=${request.headers}")
        assertTrue(bodyText.contains("name=\"audio\""), "body missing name=\"audio\"\n${bodyText.take(400)}")
        assertTrue(bodyText.contains("filename=\"$expectedFileName\""), "body missing filename\n${bodyText.take(400)}")
        assertTrue(bodyText.contains(expectedMime), "body missing $expectedMime\n${bodyText.take(400)}")
        assertTrue(request.body.size >= payload.size + 100, "body ${request.body.size} did not include payload ${payload.size}")
    }

    /**
     * Regression guard: production HttpClient must use the OkHttp engine on Desktop so
     * multipart uploads are sent with a content-length (not chunked). CIO 2.3.12 emits
     * chunked transfer-encoding which multer rejects with "No file".
     */
    @Test
    fun desktopHttpClientUsesOkHttpEngine() {
        val client = createMobiusHttpClient(onUnauthorized = {})
        try {
            val engineClassName = client.engine::class.qualifiedName ?: ""
            assertTrue(
                engineClassName.contains("OkHttp", ignoreCase = true),
                "expected OkHttp engine but got $engineClassName",
            )
        } finally {
            client.close()
        }
    }
}

private class TestSecureStorage(token: String?) : SecureStorage {
    private var currentToken = token

    override fun saveToken(token: String) {
        currentToken = token
    }

    override fun getToken(): String? = currentToken

    override fun savePreference(key: String, value: String) = Unit

    override fun getPreference(key: String): String? = null

    override fun clear() {
        currentToken = null
    }
}

private data class CapturedRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)

private class RawHttpCaptureServer(
    private val responseBody: String = """{"path":"/tmp/uploaded","name":"ok.txt","size":5}""",
) : Closeable {
    private val server = ServerSocket(0)
    private val requests = ArrayBlockingQueue<CapturedRequest>(1)
    private val worker = thread(start = true, name = "upload-capture-server") {
        server.accept().use { socket ->
            val input = socket.getInputStream()
            val requestLine = readAsciiLine(input) ?: error("missing request line")
            val headers = buildMap {
                while (true) {
                    val line = readAsciiLine(input) ?: break
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        put(
                            line.substring(0, separator).lowercase(),
                            line.substring(separator + 1).trim(),
                        )
                    }
                }
            }
            val body = when {
                headers["content-length"] != null -> readExactly(input, headers.getValue("content-length").toInt())
                headers["transfer-encoding"]?.equals("chunked", ignoreCase = true) == true -> readChunked(input)
                else -> ByteArray(0)
            }
            val parts = requestLine.split(" ")
            requests.put(CapturedRequest(parts[0], parts[1], headers, body))
            // body 用 UTF-8 字节、Content-Length 按同一编码计算，避免中文响应（如 {"text":"你好"}）
            // 因 Content-Length 与实际字节不符而触发 OkHttp ProtocolException。
            val bodyBytes = responseBody.toByteArray(StandardCharsets.UTF_8)
            val response = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Content-Length: ${bodyBytes.size}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            socket.getOutputStream().write(response.toByteArray(StandardCharsets.ISO_8859_1) + bodyBytes)
        }
    }

    val baseUrl: String = "http://127.0.0.1:${server.localPort}"

    fun awaitRequest(): CapturedRequest =
        requests.poll(10, TimeUnit.SECONDS) ?: error("upload request was not captured")

    override fun close() {
        runCatching { server.close() }
        runCatching { worker.join(1_000L) }
    }
}

private fun readAsciiLine(input: java.io.InputStream): String? {
    val output = ByteArrayOutputStream()
    var sawCarriageReturn = false
    while (true) {
        val byte = input.read()
        if (byte == -1) {
            if (output.size() == 0 && !sawCarriageReturn) return null
            if (sawCarriageReturn) output.write('\r'.code)
            break
        }
        if (sawCarriageReturn) {
            if (byte == '\n'.code) break
            output.write('\r'.code)
            sawCarriageReturn = false
        }
        if (byte == '\r'.code) {
            sawCarriageReturn = true
        } else {
            output.write(byte)
        }
    }
    return output.toString(StandardCharsets.ISO_8859_1)
}

private fun readExactly(input: java.io.InputStream, length: Int): ByteArray {
    val bytes = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = input.read(bytes, offset, length - offset)
        if (read == -1) error("unexpected end of stream")
        offset += read
    }
    return bytes
}

private fun readChunked(input: java.io.InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    while (true) {
        val sizeLine = readAsciiLine(input)?.substringBefore(';')?.trim().orEmpty()
        val size = sizeLine.toInt(16)
        if (size == 0) {
            while (true) {
                val trailer = readAsciiLine(input) ?: break
                if (trailer.isEmpty()) break
            }
            break
        }
        output.write(readExactly(input, size))
        readExactly(input, 2)
    }
    return output.toByteArray()
}
