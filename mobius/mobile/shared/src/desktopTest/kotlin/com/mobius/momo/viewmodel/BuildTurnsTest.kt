package com.mobius.momo.viewmodel

import com.mobius.momo.domain.ChatMessage
import com.mobius.momo.domain.MessageAuthor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 钉住项目 session 折叠的"用户消息 ↔ agent 回复"配对契约:
 * - 连续 user(没等回复连发)不拆轮, 回复归属同一 turn
 * - 回复后的新 user 开新轮
 * - 无 user 前缀的 assistant(会话开头)自成一轮(userMessage=null)
 */
class BuildTurnsTest {

    private fun user(id: String, text: String) =
        ChatMessage(id = id, author = MessageAuthor.User, text = text, time = "10:00", createdAtMillis = 1L)

    private fun momo(id: String, text: String) =
        ChatMessage(id = id, author = MessageAuthor.Momo, text = text, time = "10:01", createdAtMillis = 2L)

    @Test
    fun consecutiveUserMessagesStayInOneTurn() {
        val turns = buildTurns(
            listOf(
                user("u1", "第一个问题"),
                user("u2", "补充一下"),
                momo("a1", "回复一"),
                momo("a2", "回复二"),
            ),
            isStreaming = false,
        )
        assertEquals(1, turns.size, "连发 user + 回复应是一个 turn")
        assertEquals("u1", turns[0].userMessage?.id, "header 取最早的提问")
        // 补充提问前置在 body 里, 回复在后, 顺序保持
        assertEquals(listOf("u2", "a1", "a2"), turns[0].assistantItems.map { it.id })
    }

    @Test
    fun newTurnStartsAfterReply() {
        val turns = buildTurns(
            listOf(
                user("u1", "问题一"),
                momo("a1", "回复一"),
                user("u2", "问题二"),
                momo("a2", "回复二"),
            ),
            isStreaming = false,
        )
        assertEquals(2, turns.size)
        assertEquals("u1", turns[0].userMessage?.id)
        assertEquals(listOf("a1"), turns[0].assistantItems.map { it.id })
        assertEquals("u2", turns[1].userMessage?.id)
        assertEquals(listOf("a2"), turns[1].assistantItems.map { it.id })
    }

    @Test
    fun leadingAssistantWithoutUserFormsOwnTurn() {
        val turns = buildTurns(
            listOf(momo("a0", "开场白")),
            isStreaming = false,
        )
        assertEquals(1, turns.size)
        assertEquals(null, turns[0].userMessage)
        assertEquals(listOf("a0"), turns[0].assistantItems.map { it.id })
    }

    @Test
    fun processEntriesBelongToTheirTurn() {
        val process = ChatMessage(
            id = "p1", author = MessageAuthor.Momo, text = "thinking...",
            time = "10:00", createdAtMillis = 2L, processType = "thinking", processLabel = "正在思考",
        )
        val turns = buildTurns(
            listOf(user("u1", "问题"), process, momo("a1", "回复")),
            isStreaming = false,
        )
        assertEquals(1, turns.size)
        assertTrue(turns[0].assistantItems.any { it.id == "p1" }, "过程条目归属其 turn")
    }
}
