package com.mobius.momo.ota

import com.mobius.momo.data.ChangelogItem
import com.mobius.momo.data.ChangelogType
import com.mobius.momo.viewmodel.ThresholdEvaluator
import com.mobius.momo.ui.OtaDialogCopy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §10.5.3.2 + §10.5.3.3 弹窗文案 snapshot 单测：
 * - normal 三按钮（[立即更新] [稍后] [忽略此版本]）
 * - advisory 两按钮
 * - strong_advisory 两按钮 + 设置页红点（红点由业务侧另开）
 * - hard_block bypassable=true 两按钮
 * - hard_block bypassable=false 一按钮
 *
 * 文案长度守住 §10.5.3.1 上限。
 */
class OtaDialogCopySnapshotTest {

    private val baseCtx = OtaDialogCopy.Context(
        localVersion = "0.3.0",
        remoteVersion = "0.4.0",
        minSupportedVersion = "0.3.0",
        reasonDisplay = "安全漏洞修复",
        advisoryId = "CVE-2026-12345",
        hardBlockBypassable = true,
        releaseHighlight = "新增深色模式 + 性能优化",
    )

    @Test
    fun `normal copy snapshot`() {
        val c = OtaDialogCopy.render(ThresholdEvaluator.Level.Normal, baseCtx)
        assertEquals("新版本可用", c.title)
        assertTrue(c.body.contains("v0.4.0"))
        assertTrue(c.body.contains("v0.3.0"))
        assertEquals("立即更新", c.primaryLabel)
        assertEquals("稍后", c.secondaryLabel)
        assertEquals("忽略此版本", c.tertiaryLabel)
        assertTrue(c.infoBoxLines.isEmpty(), "normal 档无说明框")
    }

    @Test
    fun `advisory copy snapshot`() {
        val c = OtaDialogCopy.render(ThresholdEvaluator.Level.Advisory, baseCtx)
        assertEquals("建议尽快更新", c.title)
        assertTrue(c.body.contains("v0.3.0"))
        assertEquals("立即更新", c.primaryLabel)
        assertEquals("稍后", c.secondaryLabel)
        assertNull(c.tertiaryLabel, "advisory 无「忽略此版本」")
        assertEquals(listOf("原因：安全漏洞修复"), c.infoBoxLines)
        assertEquals(false, c.infoBoxExpandedByDefault)
    }

    @Test
    fun `strong_advisory copy snapshot`() {
        val c = OtaDialogCopy.render(ThresholdEvaluator.Level.StrongAdvisory, baseCtx)
        assertEquals("⚠️ 必须更新（强烈建议）", c.title)
        assertEquals("立即更新", c.primaryLabel)
        assertEquals("稍后", c.secondaryLabel)
        assertNull(c.tertiaryLabel)
        assertEquals(true, c.infoBoxExpandedByDefault)
        assertEquals(listOf("原因：安全漏洞修复", "公告号：CVE-2026-12345"), c.infoBoxLines)
    }

    @Test
    fun `hard_block bypassable=true copy snapshot`() {
        val c = OtaDialogCopy.render(ThresholdEvaluator.Level.HardBlock, baseCtx)
        assertEquals("🚨 检测到严重安全风险", c.title)
        assertEquals("立即更新", c.primaryLabel)
        assertEquals("继续使用（受限）", c.secondaryLabel)
        assertNull(c.tertiaryLabel)
        assertEquals(true, c.infoBoxExpandedByDefault)
        assertEquals(false, c.infoBoxDismissible, "hard_block 说明框不可关闭")
        assertEquals(3, c.infoBoxLines.size)
        assertTrue(c.infoBoxLines.any { it.contains("CVE-2026-12345") })
        assertTrue(c.infoBoxLines.any { it.contains("缓解建议") })
    }

    @Test
    fun `hard_block bypassable=false copy snapshot`() {
        val ctx = baseCtx.copy(hardBlockBypassable = false)
        val c = OtaDialogCopy.render(ThresholdEvaluator.Level.HardBlock, ctx)
        assertEquals("🚨 检测到严重安全风险（必须更新）", c.title)
        assertEquals("立即更新", c.primaryLabel)
        assertNull(c.secondaryLabel, "bypassable=false 时仅一个按钮")
        assertNull(c.tertiaryLabel)
        assertEquals(true, c.infoBoxExpandedByDefault)
        assertEquals(false, c.infoBoxDismissible)
    }

    @Test
    fun `all titles obey 24-char Chinese limit`() {
        val all = listOf(
            OtaDialogCopy.render(ThresholdEvaluator.Level.Normal, baseCtx),
            OtaDialogCopy.render(ThresholdEvaluator.Level.Advisory, baseCtx),
            OtaDialogCopy.render(ThresholdEvaluator.Level.StrongAdvisory, baseCtx),
            OtaDialogCopy.render(ThresholdEvaluator.Level.HardBlock, baseCtx),
            OtaDialogCopy.render(ThresholdEvaluator.Level.HardBlock, baseCtx.copy(hardBlockBypassable = false)),
        )
        for (c in all) {
            assertTrue(c.title.length <= 24, "标题 ${c.title} 超过 24 字")
        }
    }

    @Test
    fun `all buttons obey Chinese length limit`() {
        val all = listOf(
            OtaDialogCopy.render(ThresholdEvaluator.Level.Normal, baseCtx),
            OtaDialogCopy.render(ThresholdEvaluator.Level.Advisory, baseCtx),
            OtaDialogCopy.render(ThresholdEvaluator.Level.StrongAdvisory, baseCtx),
            OtaDialogCopy.render(ThresholdEvaluator.Level.HardBlock, baseCtx),
            OtaDialogCopy.render(ThresholdEvaluator.Level.HardBlock, baseCtx.copy(hardBlockBypassable = false)),
        )
        // §10.5.3.1 文字上限：4 中文字符为通用规则；hard_block bypassable=true 的"继续使用（受限）"
        // 模板本身就是 8 字符，与 §10.5.3.2 hard_block 段示例一致（模板优先），因此该档放宽到 8 字符。
        for (c in all) {
            listOf(c.primaryLabel, c.secondaryLabel, c.tertiaryLabel).filterNotNull().forEach { label ->
                val isHardBlockBypassable = c.secondaryLabel == "继续使用（受限）"
                val limit = if (isHardBlockBypassable) 8 else 5
                assertTrue(label.length <= limit, "按钮 $label 超过 ${limit - 1} 中文字符")
            }
        }
    }

    @Test
    fun `render is total over valid levels`() {
        // NoUpdate / Invalid 不弹窗（render 应抛错）。
        for (invalid in listOf(ThresholdEvaluator.Level.NoUpdate, ThresholdEvaluator.Level.Invalid)) {
            try {
                OtaDialogCopy.render(invalid, baseCtx)
                error("应抛 IllegalStateException")
            } catch (e: IllegalStateException) {
                assertNotNull(e.message)
            }
        }
    }

    // ===== 0.4.3: "查看完整更新说明"链接开关测试 =====

    @Test
    fun `normal with changelog items shows link and bakes first highlight into body`() {
        val items = listOf(
            ChangelogItem(ChangelogType.Feature, "首条摘要：新增 OTA changelog 弹窗"),
            ChangelogItem(ChangelogType.Fix, "修复旧版本某处崩溃"),
        )
        val ctx = baseCtx.copy(
            releaseHighlight = items.first().text,
            changelogItems = items,
            onViewFullNotes = { /* noop */ },
        )
        val c = OtaDialogCopy.render(ThresholdEvaluator.Level.Normal, ctx)
        assertTrue(c.showFullNotesLink, "有 changelog + 回调时应开启链接")
        assertEquals("查看完整更新说明", c.fullNotesLinkLabel)
        // body 拼接首条摘要: 取代兜底"新功能与体验改进"
        assertTrue(c.body.contains("首条摘要：新增 OTA changelog 弹窗"),
            "normal body 应包含 releaseHighlight 首条: ${c.body}")
        assertFalse(c.body.endsWith("新功能与体验改进。"),
            "有 highlight 时不应走兜底: ${c.body}")
    }

    @Test
    fun `normal with empty changelog falls back and hides link`() {
        val c = OtaDialogCopy.render(ThresholdEvaluator.Level.Normal, baseCtx)
        // baseCtx.releaseHighlight="新增深色模式 + 性能优化" 非空, 但 changelogItems 空 → 不显示链接,
        // body 仍走 releaseHighlight 拼接到 head (since releaseHighlight 不为空).
        assertFalse(c.showFullNotesLink, "changelogItems 为空时不显示链接")
        // releaseHighlight 非空 → body 包含 highlight 内容; 也包含 releaseHighlight.
        assertTrue(c.body.contains("新增深色模式 + 性能优化"))
    }

    @Test
    fun `normal with no changelog and no highlight falls back`() {
        val c = OtaDialogCopy.render(
            ThresholdEvaluator.Level.Normal,
            baseCtx.copy(releaseHighlight = null, changelogItems = emptyList(), onViewFullNotes = null),
        )
        assertFalse(c.showFullNotesLink, "changelogItems 空且无回调时不显示链接")
        assertTrue(c.body.endsWith("新功能与体验改进。"),
            "无 highlight 时走兜底: ${c.body}")
    }

    @Test
    fun `normal with changelog but no callback hides link`() {
        // 即便 changelog 非空, 没回调就开不了 modal → 不渲染链接(防御性兜底).
        val items = listOf(ChangelogItem(ChangelogType.Feature, "某条目"))
        val c = OtaDialogCopy.render(
            ThresholdEvaluator.Level.Normal,
            baseCtx.copy(releaseHighlight = null, changelogItems = items, onViewFullNotes = null),
        )
        assertFalse(c.showFullNotesLink, "onViewFullNotes=null 时不显示链接")
    }

    @Test
    fun `showFullNotesLink only applies to normal level not advisory`() {
        // advisory / strongAdvisory / hardBlock 不应受 showFullNotesLink 影响(本期仅 normal 档显示)
        val items = listOf(ChangelogItem(ChangelogType.Fix, "fix"))
        val ctx = baseCtx.copy(
            changelogItems = items,
            onViewFullNotes = { /* noop */ },
        )
        for (level in listOf(
            ThresholdEvaluator.Level.Advisory,
            ThresholdEvaluator.Level.StrongAdvisory,
            ThresholdEvaluator.Level.HardBlock,
        )) {
            val c = OtaDialogCopy.render(level, ctx)
            assertFalse(c.showFullNotesLink,
                "${level.name} 不显示 changelog 链接(本期仅 normal): showFullNotesLink=${c.showFullNotesLink}")
        }
    }
}
