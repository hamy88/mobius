package com.mobius.momo.data

import com.mobius.momo.domain.MessageAuthor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 用真实 SSE jsonl_history 抓包数据钉住解析契约:
 * type=assistant + message.content 为 blocks 数组(含 text/tool_use 混合)的条目,
 * 必须能解析出 author=Momo 且提取出 text 块文本 — 否则 App 聊天页"看不到 agent 回复"。
 */
class SseJsonlParseTest {

    // 真实条目(2026-09-01 抓自 /api/sessions/:id/events, 已验证服务端数据完整):
    // 结构 type=assistant, message{role, content:[{type:text,text}, {type:tool_use,...}]}
    private val realAssistantEntry = """
        {"type":"assistant","message":{"id":"msg_01","type":"message","role":"assistant","model":"glm-5.2","content":[{"type":"text","text":"情况完全清楚了。前两个功能已实现但从未打包部署。"},{"type":"tool_use","id":"toolu_01","name":"Bash","input":{"command":"ls"}}],"stop_reason":"tool_use"},"timestamp":"2026-09-01T03:41:01.123Z","uuid":"u-1"}
    """.trimIndent()

    @Test
    fun realAssistantEntryParsesToMomoTextChunk() {
        val entry = Json.parseToJsonElement(realAssistantEntry) as JsonObject
        val chunk = testApi2.parseJsonlChunkForTest(entry)
        assertNotNull(chunk, "真实结构的 assistant 条目必须能解析出 chunk")
        assertEquals(MessageAuthor.Momo, chunk.author)
        assertTrue(chunk.text.contains("前两个功能已实现"), "应提取出 text 块内容, 实际: ${chunk.text}")
    }

    @Test
    fun pureToolUseAssistantEntryParsesAsProcessOrNull() {
        val entry = """
            {"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"t","name":"Bash","input":{}}],"stop_reason":"tool_use"},"timestamp":"2026-09-01T03:46:21.681Z"}
        """.trimIndent()
        val obj = Json.parseToJsonElement(entry) as JsonObject
        val chunk = testApi2.parseJsonlChunkForTest(obj)
        // 纯 tool_use 无文本: parseJsonlChunk 返回 null, 由 parseAnyJsonlChunk 的 process 分支接管
        assertTrue(chunk == null || chunk.text.isBlank(), "纯 tool_use 条目不应产出文本气泡")
    }

    @Test
    fun jsonArrayContentInMessageIsExtracted() {
        // message.content 是数组时, string("content") 返回 null, 必须走 contentBlocksText
        val entry = """
            {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"纯文本回复"}]},"timestamp":"2026-09-01T04:00:00.000Z"}
        """.trimIndent()
        val obj = Json.parseToJsonElement(entry) as JsonObject
        val chunk = testApi2.parseJsonlChunkForTest(obj)
        assertNotNull(chunk)
        assertEquals("纯文本回复", chunk.text)
    }
}

/** 测试用空 storage。 */
private class NoopStorage2 : SecureStorage {
    override fun saveToken(token: String) = Unit
    override fun getToken(): String? = null
    override fun savePreference(key: String, value: String) = Unit
    override fun getPreference(key: String): String? = null
    override fun clear() = Unit
}

private val testApi2 = MobiusApi(baseUrl = "http://localhost:1", storage = NoopStorage2(), onUnauthorized = {})

/** 同模块调 internal(临时) parseJsonlChunk。 */
private fun MobiusApi.parseJsonlChunkForTest(entry: JsonObject): com.mobius.momo.domain.StreamTextChunk? = parseJsonlChunk(entry)
