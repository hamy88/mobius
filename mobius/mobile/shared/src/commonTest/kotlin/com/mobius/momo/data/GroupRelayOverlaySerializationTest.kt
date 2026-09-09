package com.mobius.momo.data

import com.mobius.momo.domain.ConversationMessage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupRelayOverlaySerializationTest {

    // 复刻 MomoAppViewModel 里覆盖层落盘/读取用的序列化器，验证其能正确往返。
    private val json = Json { ignoreUnknownKeys = true }
    private val mapSer = MapSerializer(String.serializer(), ListSerializer(ConversationMessage.serializer()))

    @Test
    fun overlay_round_trips_through_json() {
        val original = mapOf(
            "conv-1" to listOf(
                ConversationMessage(
                    id = -900_000_000L - 123,
                    conversationId = "conv-1",
                    senderId = "agent-session-1",
                    senderType = "agent",
                    senderName = "分身 Mobius #2",
                    content = "你好，这是群聊里 @小莫 的回复。",
                    createdAt = "10:30",
                ),
                ConversationMessage(
                    id = 42,
                    conversationId = "conv-1",
                    senderId = "agent-session-1",
                    senderType = "agent",
                    senderName = "Mobius",
                    content = "第二条回复",
                    createdAt = "10:31",
                ),
            ),
            "conv-2" to emptyList(),
        )

        val encoded = json.encodeToString(mapSer, original)
        val decoded = json.decodeFromString(mapSer, encoded)

        assertEquals(original.keys, decoded.keys)
        val list = decoded["conv-1"].orEmpty()
        assertEquals(2, list.size)
        assertEquals("你好，这是群聊里 @小莫 的回复。", list[0].content)
        assertEquals("agent", list[0].senderType)
        assertTrue(list[0].isAgent)
        assertEquals(-900_000_000L - 123, list[0].id)
        assertEquals("conv-1", list[0].conversationId)
    }

    @Test
    fun empty_overlay_round_trips() {
        val original = emptyMap<String, List<ConversationMessage>>()
        val encoded = json.encodeToString(mapSer, original)
        val decoded = json.decodeFromString(mapSer, encoded)
        assertTrue(decoded.isEmpty())
    }
}
