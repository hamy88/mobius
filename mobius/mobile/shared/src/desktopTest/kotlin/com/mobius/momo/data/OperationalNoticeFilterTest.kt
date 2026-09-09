package com.mobius.momo.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 钉住"服务端运维通知不进聊天流"的过滤契约:
 * role=system 且内容为巡检清理 / 自动提醒 / 技术字段堆叠 → 解析为 null(被丢弃);
 * role=system 但对用户有价值的错误提示(如发送失败回执)不受影响。
 */
class OperationalNoticeFilterTest {

    @Test
    fun tmuxCleanupNoticeIsFiltered() {
        val content = listOf(
            "后台巡检已自动清理超过 10 分钟无活动的 agent tmux window。",
            "backend=tmux-claude-code",
            "window=sess-1",
            "pid=12345",
            "cleanup_reason=inactive",
            "last_activity=2026-08-16T03:00:00.000Z",
            "last_activity_source=tmux",
            "inactive_age=10m",
        ).joinToString("\n")
        assertTrue(isOperationalNoticeForTest(content), "tmux 清理通知应被识别为运维消息")
    }

    @Test
    fun autoReminderNoticeIsFiltered() {
        val content = "[自动提醒] 检测到 running.flag 仍存在但 agent 已停工, 已自动向本会话发送提醒消息 (来源: project-config):\n\n[A message from the system]: ..."
        assertTrue(isOperationalNoticeForTest(content), "自动提醒应被识别为运维消息")
    }

    @Test
    fun agentParaphrasedCounterNoticeIsFiltered() {
        // 真实案例: agent 把 forgotten-flag 提醒当普通回复转述(author=assistant) — 也要过滤。
        val content = "又来了 94c3c519 第53次（24m）。"
        assertTrue(isOperationalNoticeForTest(content), "agent 转述的计数提醒应被过滤")
    }

    @Test
    fun userFacingSystemMessageIsKept() {
        val content = "消息发送失败，请稍后重试。"
        assertFalse(isOperationalNoticeForTest(content), "普通系统提示不应被过滤")
    }
}

/** 测试用空 storage。 */
private class NoopStorage : SecureStorage {
    override fun saveToken(token: String) = Unit
    override fun getToken(): String? = null
    override fun savePreference(key: String, value: String) = Unit
    override fun getPreference(key: String): String? = null
    override fun clear() = Unit
}

private val testApi = MobiusApi(baseUrl = "http://localhost:1", storage = NoopStorage(), onUnauthorized = {})

/** 同模块直接调 internal isOperationalNotice。 */
private fun isOperationalNoticeForTest(content: String): Boolean = testApi.isOperationalNotice(content)
