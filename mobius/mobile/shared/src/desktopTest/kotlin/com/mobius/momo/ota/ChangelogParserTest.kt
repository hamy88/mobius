package com.mobius.momo.ota

import com.mobius.momo.data.ChangelogItem
import com.mobius.momo.data.ChangelogParser
import com.mobius.momo.data.ChangelogType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 覆盖 §D7 + §5.1 GitHub Release body markdown 解析的所有边界。
 *
 * 测试目标：
 * - null / 空白 body → 空列表
 * - 英文 / 中文 / 中英混排标签都能识别
 * - `**Breaking:**` / `**Fix:**` / `**Feature:**` / `**修复:**` / `**破坏性变更:**` 分类正确
 * - 列表项 `- ` / `* ` 前缀被剥离
 * - 相邻同 type 项被合并
 * - 无标签段（纯文本 Feature 默认）
 */
class ChangelogParserTest {

    @Test
    fun `null body returns empty list`() {
        assertEquals(emptyList(), ChangelogParser.parse(null))
    }

    @Test
    fun `blank body returns empty list`() {
        assertEquals(emptyList(), ChangelogParser.parse("   \n\n  "))
    }

    @Test
    fun `english labels classify correctly`() {
        val body = """
            **Feature:** Login biometric support
            **Fix:** Crash on cold start
            **Breaking:** Removed legacy /v1/auth
        """.trimIndent()
        val items = ChangelogParser.parse(body)
        assertEquals(3, items.size)
        assertEquals(ChangelogType.Feature, items[0].type)
        assertEquals("Login biometric support", items[0].text)
        assertEquals(ChangelogType.Fix, items[1].type)
        assertEquals("Crash on cold start", items[1].text)
        assertEquals(ChangelogType.Breaking, items[2].type)
        assertEquals("Removed legacy /v1/auth", items[2].text)
    }

    @Test
    fun `chinese labels classify correctly`() {
        val body = """
            **特性:** 登录新增生物识别
            **修复:** 冷启动崩溃
            **破坏性变更:** 移除旧版 /v1/auth 接口
        """.trimIndent()
        val items = ChangelogParser.parse(body)
        assertEquals(3, items.size)
        assertEquals(ChangelogType.Feature, items[0].type)
        assertEquals(ChangelogType.Fix, items[1].type)
        assertEquals(ChangelogType.Breaking, items[2].type)
    }

    @Test
    fun `mixed bullet markers and indentation`() {
        val body = """
            **Feature:**
            - Added server picker
            * Added multi-account
            • Added OTA prompt
        """.trimIndent()
        val items = ChangelogParser.parse(body)
        assertEquals(1, items.size, "相邻 Feature 应合并为单条")
        assertEquals(ChangelogType.Feature, items[0].type)
        val lines = items[0].text.lines()
        assertEquals(3, lines.size)
        assertEquals("Added server picker", lines[0])
        assertEquals("Added multi-account", lines[1])
        assertEquals("Added OTA prompt", lines[2])
    }

    @Test
    fun `section headers do not become items`() {
        val body = """
            # Mobius Mobile v0.4.0

            **Build / versionCode**: 24
            **Channel**: stable

            ## Highlights
            - Faster startup
            - Smaller APK

            ## Fixes
            - Login freeze on cold start
        """.trimIndent()
        val items = ChangelogParser.parse(body)
        // # 一级标题不入 changelog；## 二级标题作为分类边界（Highlights=Feature，Fixes=Fix）
        // 同 type 相邻项合并 → Highlights 两条 Feature 合并成 1，Fixes 1 条 Fix 独立
        assertEquals(2, items.size, "Highlights 内 Feature 合并 + Fixes 独立")
        assertEquals(ChangelogType.Feature, items[0].type)
        assertTrue(items[0].text.contains("Faster startup"))
        assertTrue(items[0].text.contains("Smaller APK"))
        assertEquals(ChangelogType.Fix, items[1].type)
        assertTrue(items[1].text.contains("Login freeze"))
    }

    @Test
    fun `unlabeled text falls back to Feature`() {
        val body = """
            Initial release notes, no labels
            - Some improvement
            - Another improvement
        """.trimIndent()
        val items = ChangelogParser.parse(body)
        assertEquals(1, items.size)
        assertEquals(ChangelogType.Feature, items[0].type)
    }

    @Test
    fun `bug fix alias recognized`() {
        val body = """
            **Bug Fix:** NPE on resume
            **Bugfix:** Another npe
            **修复:** 中文修复
        """.trimIndent()
        val items = ChangelogParser.parse(body)
        // 相邻同 type 项合并为单条（多行 Bug Fix 共享上下文）
        assertEquals(1, items.size)
        assertEquals(ChangelogType.Fix, items[0].type)
        val lines = items[0].text.lines()
        assertEquals(3, lines.size)
        assertEquals("NPE on resume", lines[0])
        assertEquals("Another npe", lines[1])
        assertEquals("中文修复", lines[2])
    }

    @Test
    fun `breaking case insensitive and Chinese variants`() {
        val body = """
            **BREAKING CHANGE:** Removed API
            **breaking changes:** Removed Another
            **破坏性变化:** 中文破坏
        """.trimIndent()
        val items = ChangelogParser.parse(body)
        // 相邻 Breaking 项合并
        assertEquals(1, items.size)
        assertEquals(ChangelogType.Breaking, items[0].type)
        val lines = items[0].text.lines()
        assertEquals(3, lines.size)
    }

    @Test
    fun `mixed types keep separate entries`() {
        val body = """
            **Feature:** New dashboard
            **Fix:** Bug A
            **Feature:** New sidebar
        """.trimIndent()
        val items = ChangelogParser.parse(body)
        assertEquals(3, items.size)
        assertEquals(ChangelogType.Feature, items[0].type)
        assertEquals(ChangelogType.Fix, items[1].type)
        assertEquals(ChangelogType.Feature, items[2].type)
    }

    @Test
    fun `empty lines and section breaks do not produce empty items`() {
        val body = """
            **Feature:**
            - Item one

            - Item two after blank line
        """.trimIndent()
        val items = ChangelogParser.parse(body)
        // 跨空行时 currentType 仍为 Feature，故两条仍合并
        assertEquals(1, items.size)
        assertEquals(ChangelogType.Feature, items[0].type)
        val lines = items[0].text.lines()
        assertEquals(2, lines.size)
        assertEquals("Item one", lines[0])
        assertEquals("Item two after blank line", lines[1])
    }
}