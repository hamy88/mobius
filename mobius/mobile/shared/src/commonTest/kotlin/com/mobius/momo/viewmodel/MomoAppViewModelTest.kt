package com.mobius.momo.viewmodel

import com.mobius.momo.data.StoredTokenMetadata
import com.mobius.momo.data.TOKEN_MAX_AGE_MILLIS
import com.mobius.momo.data.TOKEN_STORAGE_VERSION
import com.mobius.momo.data.TtsPlaybackMode
import com.mobius.momo.data.parseBackendTimeMillis
import com.mobius.momo.domain.ChatMessage
import com.mobius.momo.domain.ConversationMessage
import com.mobius.momo.domain.MessageAuthor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MomoAppViewModelTest {
    private val now = 1_800_000_000_000L
    private val validMetadata = StoredTokenMetadata(
        baseUrl = "https://cloud-17.agent-matrix.com",
        savedAtEpochMillis = now - 60_000L,
        storageVersion = TOKEN_STORAGE_VERSION,
    )

    @Test
    fun blankTokenCannotRestoreSession() {
        assertFalse(canAttemptTokenRestore("https://cloud-17.agent-matrix.com", null, validMetadata, now))
        assertFalse(canAttemptTokenRestore("https://cloud-17.agent-matrix.com", "", validMetadata, now))
        assertFalse(canAttemptTokenRestore("https://cloud-17.agent-matrix.com", "   ", validMetadata, now))
    }

    @Test
    fun blankBaseUrlCannotRestoreSession() {
        assertFalse(canAttemptTokenRestore("", "jwt-token", validMetadata, now))
        assertFalse(canAttemptTokenRestore("   ", "jwt-token", validMetadata, now))
    }

    @Test
    fun missingMetadataCannotRestoreSession() {
        assertFalse(canAttemptTokenRestore("https://cloud-17.agent-matrix.com", "jwt-token", null, now))
    }

    @Test
    fun changedBaseUrlCannotRestoreSession() {
        assertFalse(canAttemptTokenRestore("https://other.agent-matrix.com", "jwt-token", validMetadata, now))
    }

    @Test
    fun staleTokenCannotRestoreSession() {
        val metadata = validMetadata.copy(savedAtEpochMillis = now - TOKEN_MAX_AGE_MILLIS - 1L)
        assertFalse(canAttemptTokenRestore("https://cloud-17.agent-matrix.com", "jwt-token", metadata, now))
    }

    @Test
    fun futureTokenTimestampCannotRestoreSession() {
        val metadata = validMetadata.copy(savedAtEpochMillis = now + 1L)
        assertFalse(canAttemptTokenRestore("https://cloud-17.agent-matrix.com", "jwt-token", metadata, now))
    }

    @Test
    fun incompatibleStorageVersionCannotRestoreSession() {
        val metadata = validMetadata.copy(storageVersion = TOKEN_STORAGE_VERSION - 1)
        assertFalse(canAttemptTokenRestore("https://cloud-17.agent-matrix.com", "jwt-token", metadata, now))
    }

    @Test
    fun validMetadataCanRestoreSession() {
        assertTrue(canAttemptTokenRestore("https://cloud-17.agent-matrix.com", "jwt-token", validMetadata, now))
    }

    @Test
    fun trailingSlashDoesNotInvalidateSameBaseUrl() {
        val metadata = validMetadata.copy(baseUrl = "https://cloud-17.agent-matrix.com/")
        assertTrue(canAttemptTokenRestore("https://cloud-17.agent-matrix.com", "jwt-token", metadata, now))
    }

    @Test
    fun selectedSpeechUsesVoiceTextWhenPresent() {
        val message = ChatMessage(
            id = "assistant-1",
            author = MessageAuthor.Momo,
            text = "完整回复正文。",
            time = "10:23",
            voiceText = "只播关键结论。",
        )

        assertEquals("只播关键结论。", assistantSpeechText(message, TtsPlaybackMode.Selected))
    }

    @Test
    fun selectedSpeechFallsBackToVisibleFirstSentenceWithoutVoiceText() {
        val message = ChatMessage(
            id = "assistant-2",
            author = MessageAuthor.Momo,
            text = "这是关键结论。后面是展开解释。\n\n第二段继续补充。",
            time = "10:23",
        )

        assertEquals("这是关键结论。", assistantSpeechText(message, TtsPlaybackMode.Selected))
    }

    @Test
    fun fullSpeechStripsVoiceMarkersFromVisibleText() {
        val message = ChatMessage(
            id = "assistant-3",
            author = MessageAuthor.Momo,
            text = "正文 PushVoiceToUser(\"关键结论\") 继续说明。",
            time = "10:23",
            voiceText = "关键结论",
        )

        assertEquals("正文 继续说明。", assistantSpeechText(message, TtsPlaybackMode.All))
    }

    @Test
    fun localUserPendingMessageIsRemovedWhenServerHistoryContainsSameSubmission() {
        val pending = listOf(
            ChatMessage(
                id = "user-1800000000000-1-abcd",
                author = MessageAuthor.User,
                text = "你吃饭了吗",
                time = "22:02",
                createdAtMillis = 1_800_000_000_000L,
            ),
        )
        val historical = listOf(
            ChatMessage(
                id = "server-user-1",
                author = MessageAuthor.User,
                text = "你吃饭了吗",
                time = "刚刚",
                createdAtMillis = 1_800_000_008_000L,
            ),
            ChatMessage(
                id = "server-assistant-1",
                author = MessageAuthor.Momo,
                text = "还没呢。",
                time = "刚刚",
                createdAtMillis = 1_800_000_020_000L,
            ),
        )

        assertEquals(emptyList(), removePendingMessagesCoveredByHistory(pending, historical))
    }

    @Test
    fun pendingUserDedupeConsumesOneHistoricalMessagePerPendingMessage() {
        val pending = listOf(
            ChatMessage(
                id = "user-1",
                author = MessageAuthor.User,
                text = "你好啊",
                time = "22:00",
                createdAtMillis = 1_800_000_000_000L,
            ),
            ChatMessage(
                id = "user-2",
                author = MessageAuthor.User,
                text = "你好啊",
                time = "22:01",
                createdAtMillis = 1_800_000_060_000L,
            ),
        )
        val historical = listOf(
            ChatMessage(
                id = "server-user-1",
                author = MessageAuthor.User,
                text = "你好啊",
                time = "22:00",
                createdAtMillis = 1_800_000_006_000L,
            ),
        )

        assertEquals(listOf(pending[1]), removePendingMessagesCoveredByHistory(pending, historical))
    }

    @Test
    fun oldHistoricalUserMessageDoesNotRemoveNewSameTextPendingMessage() {
        val pending = listOf(
            ChatMessage(
                id = "user-new",
                author = MessageAuthor.User,
                text = "你好啊",
                time = "22:05",
                createdAtMillis = 1_800_000_300_000L,
            ),
        )
        val historical = listOf(
            ChatMessage(
                id = "server-user-old",
                author = MessageAuthor.User,
                text = "你好啊",
                time = "22:00",
                createdAtMillis = 1_800_000_000_000L,
            ),
        )

        assertEquals(pending, removePendingMessagesCoveredByHistory(pending, historical))
    }

    @Test
    fun queuedUnsentUserMessageDoesNotBlockTurnSettledDetection() {
        // 场景: 用户快发 A、B 两条。A 已发出且回复完成; B 仍在出站队列排队。
        // B(未发出)不应被当成"最后一个提问者" 否则 A 的轮次被误判"没有回复" →
        // typing 永久保持 → 队列互锁(B 等 typing 收, typing 等 B 的回复) → 消息滞留/丢失。
        val messages = listOf(
            ChatMessage(
                id = "user-A",
                author = MessageAuthor.User,
                text = "第一条",
                time = "10:00",
                createdAtMillis = 1_800_000_000_000L,
            ),
            ChatMessage(
                id = "assistant-A",
                author = MessageAuthor.Momo,
                text = "第一条的回复",
                time = "10:01",
                createdAtMillis = 1_800_000_060_000L,
            ),
            ChatMessage(
                id = "user-B",
                author = MessageAuthor.User,
                text = "第二条(还在队列里没发出去)",
                time = "10:02",
                createdAtMillis = 1_800_000_090_000L,
            ),
        )
        val unsentIds = setOf("user-B")

        // 忽略未发出的 B 后, 最后一条"真正发出的"user 是 A, 其后有回复 → 轮次已结束。
        assertTrue(messages.hasAssistantAfterLatestUser(unsentIds))
        // 不忽略时(旧行为), B 被当成提问者且无回复 → 永远等 → 互锁。这就是被修复的 bug。
        assertFalse(messages.hasAssistantAfterLatestUser())
    }

    @Test
    fun sentUserMessageStillRequiresReplyWhenNothingQueued() {
        // 队列为空(正常单条发送): 最后一条 user 后无回复 → 保持等待(loading 不提前收)。
        val messages = listOf(
            ChatMessage(
                id = "user-A",
                author = MessageAuthor.User,
                text = "第一条",
                time = "10:00",
                createdAtMillis = 1_800_000_000_000L,
            ),
            ChatMessage(
                id = "assistant-A",
                author = MessageAuthor.Momo,
                text = "第一条的回复",
                time = "10:01",
                createdAtMillis = 1_800_000_060_000L,
            ),
            ChatMessage(
                id = "user-C",
                author = MessageAuthor.User,
                text = "已发出的新问题",
                time = "10:02",
                createdAtMillis = 1_800_000_090_000L,
            ),
        )

        assertFalse(messages.hasAssistantAfterLatestUser())
        assertFalse(messages.hasAssistantAfterLatestUser(emptySet()))
    }

    @Test
    fun voiceInputBelowMinDurationCountsAsTooShort() {
        // 1..499ms 视为误触/抖动；0 与负数（未记录开始时间）不算太短，500ms 起为正常录音。
        assertFalse(isVoiceInputTooShort(0L))
        assertTrue(isVoiceInputTooShort(1L))
        assertTrue(isVoiceInputTooShort(499L))
        assertFalse(isVoiceInputTooShort(MIN_VOICE_INPUT_MS))
        assertFalse(isVoiceInputTooShort(1_000L))
        assertFalse(isVoiceInputTooShort(-5L))
    }

    @Test
    fun filterDeletedChatMessagesRemovesDeletedIds() {
        val messages = listOf(
            ChatMessage("m1", MessageAuthor.User, "a", "10:00"),
            ChatMessage("m2", MessageAuthor.Momo, "b", "10:01"),
            ChatMessage("m3", MessageAuthor.User, "c", "10:02"),
        )
        // 空已删集合: 原样返回
        assertEquals(messages, filterDeletedChatMessages(messages, emptySet()))
        // 删除 m2: 只剩 m1, m3
        assertEquals(
            listOf("m1", "m3"),
            filterDeletedChatMessages(messages, setOf("m2")).map { it.id },
        )
    }

    @Test
    fun chatCutoffHidesOlderMessagesButKeepsNewerAndCompactAlwaysHidden() {
        // 清空会话消息(clearSessionMessages/clearActiveConversation)后, 旧消息隐藏、新消息保留;
        // /compact 指令始终隐藏; 无时间戳的消息在 cutoff>0 时保守显示 — 时间解析存在平台差异
        // (iOS 对非标准格式), 按旧语义隐藏会造成"消息不显示"。
        val cutoff = 1_000L
        val oldMsg = ChatMessage("m1", MessageAuthor.Momo, "旧", "10:00", createdAtMillis = 500L)
        val newMsg = ChatMessage("m2", MessageAuthor.User, "新", "10:01", createdAtMillis = 2_000L)
        val compactMsg = ChatMessage("m3", MessageAuthor.User, "/compact", "10:02", createdAtMillis = 3_000L)
        val noTsMsg = ChatMessage("m4", MessageAuthor.Momo, "无时间戳", "10:03", createdAtMillis = null)
        // cutoff=0(未清空): 普通消息全显示, 仅 /compact 隐藏
        assertTrue(oldMsg.shouldShowAfterClearCutoff(0L))
        assertTrue(newMsg.shouldShowAfterClearCutoff(0L))
        assertFalse(compactMsg.shouldShowAfterClearCutoff(0L))
        assertTrue(noTsMsg.shouldShowAfterClearCutoff(0L))
        // cutoff>0(已清空): 早于 cutoff 隐藏, 晚于显示, /compact 仍隐藏, 无时间戳保守显示
        assertFalse(oldMsg.shouldShowAfterClearCutoff(cutoff))
        assertTrue(newMsg.shouldShowAfterClearCutoff(cutoff))
        assertFalse(compactMsg.shouldShowAfterClearCutoff(cutoff))
        assertTrue(noTsMsg.shouldShowAfterClearCutoff(cutoff))
    }

    @Test
    fun groupMessageHiddenWhenDeletedIdMatches() {
        val msg = ConversationMessage(id = 42, conversationId = "c1", content = "hi", createdAt = "2026-07-11T10:00:00Z")
        // 命中已删 id -> 隐藏
        assertFalse(shouldShowGroupMessageLocally(cutoff = 0L, deletedIds = setOf(42L), message = msg))
        // 未命中 -> 显示
        assertTrue(shouldShowGroupMessageLocally(cutoff = 0L, deletedIds = emptySet(), message = msg))
        // id=0(后端未给 id)不按 id 删除, 仍显示
        val noId = msg.copy(id = 0L)
        assertTrue(shouldShowGroupMessageLocally(cutoff = 0L, deletedIds = setOf(0L), message = noId))
    }

    @Test
    fun groupMessageCutoffHidesOlderParseableMessagesButKeepsOptimistic() {
        // cutoff 取 09:30, 早于它的(09:00)隐藏, 晚于的(11:00)显示; 用 parseBackendTimeMillis 自身算, 避免硬编码.
        val cutoff = assertNotNull(parseBackendTimeMillis("2026-07-11T09:30:00Z"))
        val oldIso = ConversationMessage(id = 1, content = "旧", createdAt = "2026-07-11T09:00:00Z")
        val newIso = ConversationMessage(id = 2, content = "新", createdAt = "2026-07-11T11:00:00Z")
        val optimistic = ConversationMessage(id = 3, content = "乐观", createdAt = "10:30") // 本地乐观消息, 非 ISO
        // 早于 cutoff 且可解析 -> 隐藏
        assertFalse(shouldShowGroupMessageLocally(cutoff = cutoff, deletedIds = emptySet(), message = oldIso))
        // 晚于 cutoff -> 显示
        assertTrue(shouldShowGroupMessageLocally(cutoff = cutoff, deletedIds = emptySet(), message = newIso))
        // 不可解析时间(乐观消息) -> 即使 cutoff>0 也保留, 避免新消息被误隐藏
        assertTrue(shouldShowGroupMessageLocally(cutoff = cutoff, deletedIds = emptySet(), message = optimistic))
        // 无 cutoff(=0): 全部显示
        assertTrue(shouldShowGroupMessageLocally(cutoff = 0L, deletedIds = emptySet(), message = oldIso))
    }
}
