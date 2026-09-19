package com.mobius.momo.ota

import com.mobius.momo.data.OtaManifest
import com.mobius.momo.viewmodel.ThresholdEvaluator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 覆盖 §10.5.2 四档判定的所有边界：
 * - normal：local < remote 且 minSupportedVersion 缺失 / local ≥ 阈值
 * - advisory：minSupportedVersion 已填 + level=advisory
 * - strong_advisory：level=strong_advisory
 * - hard_block：level=hard_block（bypassable=true / false 都覆盖）
 * - 防御：未识别 level → advisory；未识别 reason → reasonDisplay=null
 * - 降级：remote ≤ local → NoUpdate（不论 manifest 字段如何）
 */
class ThresholdEvaluatorTest {

    private fun manifest(
        version: String = "0.4.0",
        min: String? = null,
        level: String? = null,
        reason: String? = null,
        advisoryId: String? = null,
        bypassable: Boolean = true,
    ): OtaManifest = OtaManifest(
        version = version,
        versionCode = 24,
        minSupportedVersion = min,
        minSupportedVersionLevel = level,
        minSupportedVersionReason = reason,
        minSupportedVersionAdvisoryId = advisoryId,
        hardBlockBypassable = bypassable,
    )

    // ===== normal =====

    @Test
    fun `no min field and remote greater than local yields normal`() {
        val r = ThresholdEvaluator.evaluate(ThresholdEvaluator.Input("0.3.0", manifest()))
        assertEquals(ThresholdEvaluator.Level.Normal, r.level)
    }

    @Test
    fun `local equals min yields normal`() {
        val r = ThresholdEvaluator.evaluate(
            ThresholdEvaluator.Input("0.3.0", manifest(min = "0.3.0", level = "advisory")),
        )
        assertEquals(ThresholdEvaluator.Level.Normal, r.level, "local == min 应走 normal 档")
    }

    @Test
    fun `local greater than min yields normal`() {
        val r = ThresholdEvaluator.evaluate(
            ThresholdEvaluator.Input("0.3.5", manifest(min = "0.3.0", level = "advisory")),
        )
        assertEquals(ThresholdEvaluator.Level.Normal, r.level)
    }

    // ===== advisory =====

    @Test
    fun `local below min with advisory level yields advisory`() {
        val r = ThresholdEvaluator.evaluate(
            ThresholdEvaluator.Input(
                "0.2.0",
                manifest(min = "0.3.0", level = "advisory", reason = "protocol_change"),
            ),
        )
        assertEquals(ThresholdEvaluator.Level.Advisory, r.level)
        assertEquals("通信协议变更", r.reasonDisplay)
    }

    // ===== strong_advisory =====

    @Test
    fun `local below min with strong_advisory level yields strong_advisory`() {
        val r = ThresholdEvaluator.evaluate(
            ThresholdEvaluator.Input(
                "0.2.0",
                manifest(min = "0.3.0", level = "strong_advisory", reason = "breaking_api", advisoryId = "MOMO-SEC-2026-07"),
            ),
        )
        assertEquals(ThresholdEvaluator.Level.StrongAdvisory, r.level)
        assertEquals("接口重大变更", r.reasonDisplay)
        assertEquals("MOMO-SEC-2026-07", r.advisoryId)
    }

    // ===== hard_block =====

    @Test
    fun `hard_block bypassable=true yields hard_block with bypassable=true`() {
        val r = ThresholdEvaluator.evaluate(
            ThresholdEvaluator.Input(
                "0.1.0",
                manifest(
                    min = "0.3.0",
                    level = "hard_block",
                    reason = "security_cve",
                    advisoryId = "CVE-2026-12345",
                    bypassable = true,
                ),
            ),
        )
        assertEquals(ThresholdEvaluator.Level.HardBlock, r.level)
        assertEquals(true, r.hardBlockBypassable)
        assertEquals("安全漏洞修复", r.reasonDisplay)
    }

    @Test
    fun `hard_block bypassable=false yields hard_block with bypassable=false`() {
        val r = ThresholdEvaluator.evaluate(
            ThresholdEvaluator.Input(
                "0.1.0",
                manifest(
                    min = "0.3.0",
                    level = "hard_block",
                    reason = "security_cve",
                    advisoryId = "CVE-2026-99999",
                    bypassable = false,
                ),
            ),
        )
        assertEquals(ThresholdEvaluator.Level.HardBlock, r.level)
        assertEquals(false, r.hardBlockBypassable)
    }

    // ===== 防御默认值 =====

    @Test
    fun `unknown level falls back to advisory`() {
        val r = ThresholdEvaluator.evaluate(
            ThresholdEvaluator.Input(
                "0.2.0",
                manifest(min = "0.3.0", level = "ultra_mega_block"),
            ),
        )
        assertEquals(ThresholdEvaluator.Level.Advisory, r.level, "未识别 level 必须降级 advisory")
    }

    @Test
    fun `unknown reason yields null reasonDisplay`() {
        val r = ThresholdEvaluator.evaluate(
            ThresholdEvaluator.Input(
                "0.2.0",
                manifest(min = "0.3.0", level = "advisory", reason = "made_up_reason"),
            ),
        )
        assertEquals(ThresholdEvaluator.Level.Advisory, r.level)
        assertNull(r.reasonDisplay, "未识别 reason 不显示说明框")
    }

    @Test
    fun `missing level field falls back to advisory`() {
        val r = ThresholdEvaluator.evaluate(
            ThresholdEvaluator.Input(
                "0.2.0",
                manifest(min = "0.3.0", level = null),
            ),
        )
        assertEquals(ThresholdEvaluator.Level.Advisory, r.level, "level 缺失 → advisory")
    }

    // ===== 降级防御 =====

    @Test
    fun `remote less than local yields NoUpdate even with min field`() {
        val r = ThresholdEvaluator.evaluate(
            ThresholdEvaluator.Input(
                "0.5.0",
                manifest(version = "0.3.0", min = "0.4.0", level = "hard_block"),
            ),
        )
        assertEquals(ThresholdEvaluator.Level.NoUpdate, r.level, "远端 < 本地直接 NoUpdate")
    }

    @Test
    fun `remote equal to local yields NoUpdate`() {
        val r = ThresholdEvaluator.evaluate(
            ThresholdEvaluator.Input("0.3.0", manifest(version = "0.3.0")),
        )
        assertEquals(ThresholdEvaluator.Level.NoUpdate, r.level)
    }

    @Test
    fun `invalid local version yields Invalid`() {
        val r = ThresholdEvaluator.evaluate(
            ThresholdEvaluator.Input("garbage", manifest(version = "0.4.0")),
        )
        assertEquals(ThresholdEvaluator.Level.Invalid, r.level)
    }

    @Test
    fun `invalid remote version yields Invalid`() {
        val r = ThresholdEvaluator.evaluate(
            ThresholdEvaluator.Input("0.3.0", manifest(version = "not.a.version.that.parses")),
        )
        assertEquals(ThresholdEvaluator.Level.Invalid, r.level)
    }

    // ===== 静默期常量 =====

    @Test
    fun `silent periods match spec 10_5_4`() {
        assertEquals(6L, ThresholdEvaluator.SILENT_HOURS_NORMAL)
        assertEquals(24L, ThresholdEvaluator.SILENT_HOURS_ADVISORY)
        assertEquals(12L, ThresholdEvaluator.SILENT_HOURS_STRONG_ADVISORY)
        assertEquals(0L, ThresholdEvaluator.SILENT_HOURS_HARD_BLOCK)
    }

    @Test
    fun `silent period millis`() {
        assertEquals(6L * 3600_000L, ThresholdEvaluator.silentPeriodMillis(ThresholdEvaluator.Level.Normal))
        assertEquals(0L, ThresholdEvaluator.silentPeriodMillis(ThresholdEvaluator.Level.HardBlock))
    }
}
