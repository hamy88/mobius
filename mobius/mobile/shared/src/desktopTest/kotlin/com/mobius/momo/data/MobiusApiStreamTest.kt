package com.mobius.momo.data

import com.mobius.momo.domain.MessageAuthor
import com.mobius.momo.domain.StreamTextChunk
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MobiusApiStreamTest {
    /**
     * 后端把 jsonl 文件里每一条完整记录原样透传为独立 jsonl_entry（非 token delta）。
     * 一个轮次里若有多条 assistant 文本消息，必须各自产出独立 chunk，而不能被合并成一条
     * ——否则移动端就会出现"部分输出的消息不显示"。这里用 fake SSE server 守护该契约。
     */
    @Test
    fun multipleAssistantEntriesInOneTurnYieldDistinctChunks() = runBlocking {
        val events = buildString {
            appendSse("jsonl_entry", """{"event":"jsonl_entry","session_id":"sess-1","entry":{"type":"assistant","id":"a1","message":{"role":"assistant","content":[{"type":"text","text":"第一条完整回复"}]}}}""")
            appendSse("jsonl_entry", """{"event":"jsonl_entry","session_id":"sess-1","entry":{"type":"assistant","id":"a2","message":{"role":"assistant","content":[{"type":"text","text":"第二条完整回复"}]}}}""")
            appendSse("typing", """{"active":false}""")
        }
        FakeSseServer(events).use { server ->
            val api = MobiusApi(
                baseUrl = server.baseUrl,
                storage = TestStreamStorage("test-token"),
                onUnauthorized = {},
            )
            val chunks = mutableListOf<StreamTextChunk>()
            api.streamSession(
                sessionId = "sess-1",
                onConnected = {},
                onTyping = {},
                onHistory = {},
                onJsonlHistory = { _, _, _ -> },
                onChunk = { chunks += it },
                onError = { error("stream error: $it") },
            )
            api.close()

            assertEquals(2, chunks.size, "expected one chunk per assistant entry but got $chunks")
            assertEquals(listOf("a1", "a2"), chunks.map { it.id })
            assertTrue(chunks.all { it.author == MessageAuthor.Momo })
            assertEquals("第一条完整回复", chunks[0].text)
            assertEquals("第二条完整回复", chunks[1].text)
        }
    }

    /**
     * 同一条消息（按 id）被后端重发时，必须仍按同 id 产出 chunk，交给上层按 id 去重，
     * 而不是把重发的内容当作新 delta 拼接进上一条。
     */
    @Test
    fun reEmittedAssistantEntryKeepsSameId() = runBlocking {
        val events = buildString {
            appendSse("jsonl_entry", """{"event":"jsonl_entry","session_id":"sess-1","entry":{"type":"assistant","id":"a1","message":{"role":"assistant","content":[{"type":"text","text":"完整回复"}]}}}""")
            appendSse("jsonl_entry", """{"event":"jsonl_entry","session_id":"sess-1","entry":{"type":"assistant","id":"a1","message":{"role":"assistant","content":[{"type":"text","text":"完整回复"}]}}}""")
        }
        FakeSseServer(events).use { server ->
            val api = MobiusApi(baseUrl = server.baseUrl, storage = TestStreamStorage("test-token"), onUnauthorized = {})
            val chunks = mutableListOf<StreamTextChunk>()
            api.streamSession(
                sessionId = "sess-1",
                onConnected = {},
                onTyping = {},
                onHistory = {},
                onJsonlHistory = { _, _, _ -> },
                onChunk = { chunks += it },
                onError = { error("stream error: $it") },
            )
            api.close()

            assertTrue(chunks.isNotEmpty())
            assertTrue(chunks.all { it.id == "a1" }, "re-emitted entries must keep same id for dedup: $chunks")
            assertTrue(chunks.all { it.text == "完整回复" })
        }
    }

    /**
     * 后端已把增量协议从 jsonl_entry 换成 agent-history-store 的 entries 事件
     * (group_created/entries/pending_opener)。移动端必须同样把 entries 里的每条
     * 原始 jsonl entry 产出独立 chunk，否则聊天页"看不到助手回复"。此处守护该契约。
     */
    @Test
    fun entriesEventYieldsChunksLikeLegacyJsonlEntry() = runBlocking {
        val events = buildString {
            appendSse("entries", """{"event":"entries","session_id":"sess-1","group_id":"1","group_id_version":2,"entries":[{"type":"assistant","id":"a1","message":{"role":"assistant","content":[{"type":"text","text":"第一条回复"}]}},{"type":"assistant","id":"a2","message":{"role":"assistant","content":[{"type":"text","text":"第二条回复"}]}}]}""")
            appendSse("typing", """{"active":false}""")
        }
        FakeSseServer(events).use { server ->
            val api = MobiusApi(baseUrl = server.baseUrl, storage = TestStreamStorage("test-token"), onUnauthorized = {})
            val chunks = mutableListOf<StreamTextChunk>()
            api.streamSession(
                sessionId = "sess-1",
                onConnected = {},
                onTyping = {},
                onHistory = {},
                onJsonlHistory = { _, _, _ -> },
                onChunk = { chunks += it },
                onError = { error("stream error: $it") },
            )
            api.close()

            assertEquals(2, chunks.size, "entries 事件里的每条 assistant entry 都应产出独立 chunk: $chunks")
            assertEquals(listOf("a1", "a2"), chunks.map { it.id })
            assertEquals("第一条回复", chunks[0].text)
            assertEquals("第二条回复", chunks[1].text)
            assertTrue(chunks.all { it.author == MessageAuthor.Momo })
        }
    }

    private fun StringBuilder.appendSse(event: String, data: String) {
        append("event: ").append(event).append("\n")
        data.split("\n").forEach { append("data: ").append(it).append("\n") }
        append("\n")
    }
}

private class TestStreamStorage(token: String?) : SecureStorage {
    private var currentToken = token
    override fun saveToken(token: String) { currentToken = token }
    override fun getToken(): String? = currentToken
    override fun savePreference(key: String, value: String) = Unit
    override fun getPreference(key: String): String? = null
    override fun clear() { currentToken = null }
}

private class FakeSseServer(payload: String) : Closeable {
    private val bytes = payload.toByteArray(StandardCharsets.UTF_8)
    private val server = ServerSocket(0)
    private val done = ArrayBlockingQueue<Unit>(1)
    private val worker = thread(start = true, name = "sse-fake-server") {
        server.accept().use { socket ->
            // 读取并丢弃请求行 + 头，直到空行。
            val input = socket.getInputStream()
            while (true) {
                val line = readCrLfLine(input) ?: break
                if (line.isEmpty()) break
            }
            val out = socket.getOutputStream()
            val header = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: text/event-stream\r\n")
                append("Cache-Control: no-cache\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }.toByteArray(StandardCharsets.ISO_8859_1)
            out.write(header)
            out.write(bytes)
            out.flush()
        }
        done.put(Unit)
    }

    val baseUrl: String = "http://127.0.0.1:${server.localPort}"

    override fun close() {
        runCatching { server.close() }
        done.poll(5, TimeUnit.SECONDS)
        runCatching { worker.join(1_000L) }
    }

    private fun readCrLfLine(input: java.io.InputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        var sawCr = false
        while (true) {
            val b = input.read()
            if (b == -1) return if (out.size() == 0 && !sawCr) null else out.toString(StandardCharsets.ISO_8859_1)
            if (sawCr) {
                if (b == '\n'.code) break
                out.write('\r'.code)
                sawCr = false
            }
            if (b == '\r'.code) sawCr = true else out.write(b)
        }
        return out.toString(StandardCharsets.ISO_8859_1)
    }
}
