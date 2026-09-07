package com.mobius.momo.ui

import com.mobius.momo.data.nowEpochMillis
import com.mobius.momo.domain.Project
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 复现 "活跃 tab 不过滤" bug 的诊断测试: 用各类典型后端响应数据验证
 * ProjectListTab.Active.filter() 是否能正确剔除非活跃项目。
 */
class ProjectListTabFilterTest {

    private fun proj(id: String, lastActive: String? = null, lastSession: String? = null): Project =
        Project(
            id = id,
            name = id,
            lastActive = lastActive,
            lastSessionActivityAt = lastSession,
        )

    @Test
    fun active_filter_keeps_recent_activity() {
        val now = nowEpochMillis()
        // 5 天前的时间戳(在 7 天窗口内)
        val fiveDaysAgoIso = isoFromMillis(now - 5L * 24 * 3600 * 1000L)
        // 30 天前(已过 7 天窗口)
        val thirtyDaysAgoIso = isoFromMillis(now - 30L * 24 * 3600 * 1000L)

        val list = listOf(
            proj("p-recent", lastActive = fiveDaysAgoIso),
            proj("p-old", lastActive = thirtyDaysAgoIso),
            proj("p-no-time"),
            proj("p-recent-session", lastSession = fiveDaysAgoIso),
        )
        val active = ProjectListTab.Active.filter(list)
        val ids = active.map { it.id }.toSet()
        assertEquals(setOf("p-recent", "p-recent-session"), ids,
            "仅 7 天内有活动的项目保留; 老项目/null 时间应过滤掉。实际: $ids")
    }

    @Test
    fun active_filter_with_only_nulls_yields_empty() {
        val list = listOf(proj("a"), proj("b"), proj("c"))
        val active = ProjectListTab.Active.filter(list)
        assertTrue(active.isEmpty(),
            "所有项目都没活动时间时, 活跃列表应为空。实际: ${active.size} 条")
    }

    @Test
    fun active_filter_returns_all_if_all_recent() {
        val now = nowEpochMillis()
        val recent = isoFromMillis(now - 1L * 24 * 3600 * 1000L)
        val list = listOf(proj("a", lastActive = recent), proj("b", lastActive = recent))
        val active = ProjectListTab.Active.filter(list)
        assertEquals(2, active.size)
    }

    // 复现 2026-08-06 用户报的"活跃 tab 不过滤"bug: 后端给的 18 个扩展项目
    // last_active 都在 2026-06 月(7 周前), last_session_activity_at 全 null,
    // 按 7 天窗口, 应全部过滤掉。Bug 现象是点击"活跃"仍显示全部 18 个。
    @Test
    fun active_filter_drops_all_old_extension_projects_from_real_api() {
        val realApiProjects = listOf(
            proj("ext_llm-wiki", lastActive = "2026-06-23T11:16:14.462Z", lastSession = null),
            proj("ext_world-cup", lastActive = "2026-06-19T17:33:05.111Z", lastSession = null),
            proj("ext_security-audit-report", lastActive = "2026-06-19T17:33:05.111Z", lastSession = null),
            proj("ext_momo-mobile", lastActive = "2026-06-19T17:33:05.110Z", lastSession = null),
            proj("ext_mobius-home", lastActive = "2026-06-19T17:33:05.110Z", lastSession = null),
            proj("ext_pricing", lastActive = "2026-06-19T17:33:05.110Z", lastSession = null),
            proj("ext_flappy-bird-3d", lastActive = "2026-06-19T17:33:05.109Z", lastSession = null),
            proj("ext_traffic-light-plc", lastActive = "2026-06-15T17:06:17.592Z", lastSession = null),
            proj("ext_chatgpt", lastActive = "2026-06-15T17:00:50.830Z", lastSession = null),
            proj("ext_jsoncrack", lastActive = "2026-06-14T11:50:44.621Z", lastSession = null),
            proj("ext_arxiv", lastActive = "2026-06-14T11:50:44.620Z", lastSession = null),
            proj("ext_birthday-invite", lastActive = "2026-06-14T11:50:44.620Z", lastSession = null),
            proj("ext_ppt-maker", lastActive = "2026-06-13T14:57:25.143Z", lastSession = null),
            proj("ext_matchmate", lastActive = "2026-06-13T14:57:25.142Z", lastSession = null),
            proj("ext_dot-logo-3d", lastActive = "2026-06-08T19:16:25.444Z", lastSession = null),
            proj("ext_threebody-sim", lastActive = "2026-06-08T19:16:25.444Z", lastSession = null),
            proj("ext_pacman", lastActive = "2026-06-08T19:16:25.444Z", lastSession = null),
            proj("ext_finance-news-wall", lastActive = "2026-06-08T19:16:25.444Z", lastSession = null),
        )
        val active = ProjectListTab.Active.filter(realApiProjects)
        assertTrue(active.isEmpty(),
            "所有项目 last_active 都在 6 月(>7 天前), 活跃 tab 应为空。实际: ${active.size} 条 — ${active.map { it.id }}")
    }

    private fun isoFromMillis(millis: Long): String {
        // 转为 yyyy-MM-dd'T'HH:mm:ss.SSS'Z' (UTC)
        val instant = java.time.Instant.ofEpochMilli(millis)
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(java.time.ZoneOffset.UTC)
            .format(instant)
    }
}
