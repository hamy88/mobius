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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 聚合推送设备令牌注册/注销的 wire 契约守护：客户端经 Mobius /api/ext 转发到扩展 handler，
 * 钉死实际发出的方法、路径、JSON 体（extension_name + ext_main_payload.action/token/platform）与
 * Authorization: Bearer 头，防止后续重构悄悄改了格式而无人察觉。
 */
class MobiusApiDeviceTokenTest {

    @Test
    fun registerDeviceTokenPostsExtCallWithBearerAuth() = runBlocking {
        CaptureServer().use { server ->
            val api = MobiusApi(
                baseUrl = server.baseUrl,
                storage = TestTokenStorage("bearer-abc"),
                onUnauthorized = {},
            )
            api.registerDeviceToken("rid-123456", PUSH_PLATFORM_JPUSH)
            val request = server.awaitRequest()
            api.close()

            assertEquals("POST", request.method)
            assertEquals("/api/ext", request.path)
            assertEquals("Bearer bearer-abc", request.headers["authorization"])
            assertEquals("application/json", request.headers["content-type"])
            val body = request.body.toString(StandardCharsets.UTF_8)
            assertTrue(body.contains("\"extension_name\":\"momo-mobile\""), body)
            assertTrue(body.contains("\"action\":\"register_device\""), body)
            assertTrue(body.contains("\"token\":\"rid-123456\""), body)
            assertTrue(body.contains("\"platform\":\"jpush\""), body)
        }
    }

    @Test
    fun unregisterDeviceTokenPostsExtCall() = runBlocking {
        CaptureServer().use { server ->
            val api = MobiusApi(
                baseUrl = server.baseUrl,
                storage = TestTokenStorage("bearer-abc"),
                onUnauthorized = {},
            )
            api.unregisterDeviceToken("rid-xyz")
            val request = server.awaitRequest()
            api.close()

            // 注销也走 POST /api/ext（action 区分），不再是 DELETE /api/devices/:token。
            assertEquals("POST", request.method)
            assertEquals("/api/ext", request.path)
            assertEquals("Bearer bearer-abc", request.headers["authorization"])
            val body = request.body.toString(StandardCharsets.UTF_8)
            assertTrue(body.contains("\"action\":\"unregister_device\""), body)
            assertTrue(body.contains("\"token\":\"rid-xyz\""), body)
        }
    }

    @Test
    fun blankTokenIsNoOp() = runBlocking {
        CaptureServer().use { server ->
            val api = MobiusApi(
                baseUrl = server.baseUrl,
                storage = TestTokenStorage("bearer-abc"),
                onUnauthorized = {},
            )
            api.registerDeviceToken("   ")
            api.unregisterDeviceToken("")
            api.close()
            // 不应有任何请求发出。
            assertNull(server.pollNoRequest(), "blank token must not trigger a network request")
        }
    }

    private class TestTokenStorage(private val token: String?) : SecureStorage {
        override fun saveToken(token: String) = Unit
        override fun getToken(): String? = token
        override fun savePreference(key: String, value: String) = Unit
        override fun getPreference(key: String): String? = null
        override fun clear() = Unit
    }

    private data class CapturedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private class CaptureServer : Closeable {
        private val server = ServerSocket(0)
        private val requests = ArrayBlockingQueue<CapturedRequest>(1)
        private val worker = thread(start = true, name = "device-token-capture") {
            server.accept().use { socket ->
                val input = socket.getInputStream()
                val requestLine = readAsciiLineDt(input) ?: return@use
                val headers = buildMap {
                    while (true) {
                        val line = readAsciiLineDt(input) ?: break
                        if (line.isEmpty()) break
                        val sep = line.indexOf(':')
                        if (sep > 0) put(line.substring(0, sep).lowercase(), line.substring(sep + 1).trim())
                    }
                }
                val body = headers["content-length"]?.toIntOrNull()?.let { readExactlyDt(input, it) } ?: ByteArray(0)
                val parts = requestLine.split(" ")
                requests.put(CapturedRequest(parts[0], parts[1], headers, body))
                val resp = "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(resp.toByteArray(StandardCharsets.ISO_8859_1))
            }
        }

        val baseUrl: String = "http://127.0.0.1:${server.localPort}"

        fun awaitRequest(): CapturedRequest =
            requests.poll(10, TimeUnit.SECONDS) ?: fail("device-token request was not captured")

        fun pollNoRequest(): CapturedRequest? = requests.poll(300, TimeUnit.MILLISECONDS)

        override fun close() {
            runCatching { server.close() }
            runCatching { worker.join(1_000L) }
        }

        private fun readAsciiLineDt(input: java.io.InputStream): String? {
            val out = ByteArrayOutputStream()
            var sawCr = false
            while (true) {
                val b = input.read()
                if (b == -1) {
                    if (out.size() == 0 && !sawCr) return null
                    if (sawCr) out.write('\r'.code)
                    return out.toString(StandardCharsets.ISO_8859_1)
                }
                if (sawCr) {
                    if (b == '\n'.code) break
                    out.write('\r'.code)
                    sawCr = false
                }
                if (b == '\r'.code) sawCr = true else out.write(b)
            }
            return out.toString(StandardCharsets.ISO_8859_1)
        }

        private fun readExactlyDt(input: java.io.InputStream, length: Int): ByteArray {
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(bytes, offset, length - offset)
                if (read == -1) error("unexpected end of stream")
                offset += read
            }
            return bytes
        }
    }
}
