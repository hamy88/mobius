package com.mobius.momo.ota

import com.mobius.momo.data.OtaAndroidSpec
import com.mobius.momo.data.OtaManifest
import com.mobius.momo.data.SignatureValidationResult
import com.mobius.momo.viewmodel.ThresholdEvaluator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §D7 + §5.4 签名 scheme 跳级检测在 ThresholdEvaluator HardBlock 档的串联测试。
 *
 * 覆盖：
 * - HardBlock + Ok           → 不加签名 scheme 前缀
 * - HardBlock + UpgradeRequired → reasonDisplay 前缀 "签名 scheme 不满足（upgrade_required）；..."
 * - HardBlock + Downgrade    → reasonDisplay 前缀 "签名 scheme 不满足（downgrade）；..."
 * - HardBlock + Unknown      → reasonDisplay 不加前缀（保持原值）
 * - Normal / Advisory / StrongAdvisory → 不加前缀（patch 失败兜底走全量即可，不阻断弹窗）
 */
class ThresholdEvaluatorSignatureTest {

    private fun manifest(
        version: String = "0.4.1",
        min: String = "0.3.0",
        level: String = "hard_block",
        reason: String = "security_cve",
        signatureScheme: List<String> = listOf("v2", "v3"),
    ): OtaManifest = OtaManifest(
        version = version,
        versionCode = 25,
        minSupportedVersion = min,
        minSupportedVersionLevel = level,
        minSupportedVersionReason = reason,
        minSupportedVersionAdvisoryId = "CVE-2026-12345",
        android = OtaAndroidSpec(signatureScheme = signatureScheme),
    )

    @Test
    fun `HardBlock + device scheme contains required yields no prefix`() {
        val r = ThresholdEvaluator.evaluate(
            ThresholdEvaluator.Input(
                localVersion = "0.2.0",
                manifest = manifest(),
                deviceSignatureScheme = "v2,v3",
            ),
        )
        assertEquals(ThresholdEvaluator.Level.HardBlock, r.level)
        assertEquals(SignatureValidationResult.Ok, r.signatureValidation)
        // Ok 时不加前缀，reasonDisplay 应为纯 "安全漏洞修复"
        assertEquals("安全漏洞修复", r.reasonDisplay)
    }

    @Test
    fun `HardBlock + device missing required scheme yields UpgradeRequired prefix`() {
        val r = ThresholdEvaluator.evaluate(
            ThresholdEvaluator.Input(
                localVersion = "0.2.0",
                manifest = manifest(),
                deviceSignatureScheme = "v2", // 缺 v3
            ),
        )
        assertEquals(ThresholdEvaluator.Level.HardBlock, r.level)
        assertEquals(SignatureValidationResult.UpgradeRequired, r.signatureValidation)
        assertTrue(
            r.reasonDisplay!!.startsWith("签名 scheme 不满足（upgrade_required）"),
            "HardBlock + UpgradeRequired 必须加前缀，actual=${r.reasonDisplay}",
        )
        assertTrue(r.reasonDisplay!!.contains("安全漏洞修复"))
    }

    @Test
    fun `HardBlock + device partial overlap yields Downgrade prefix`() {
        val r = ThresholdEvaluator.evaluate(
            ThresholdEvaluator.Input(
                localVersion = "0.2.0",
                manifest = manifest(),
                deviceSignatureScheme = "v2,v4", // 多了 v4（partial overlap → Downgrade）
            ),
        )
        assertEquals(ThresholdEvaluator.Level.HardBlock, r.level)
        assertEquals(SignatureValidationResult.Downgrade, r.signatureValidation)
        assertTrue(
            r.reasonDisplay!!.startsWith("签名 scheme 不满足（downgrade）"),
            "HardBlock + Downgrade 必须加前缀，actual=${r.reasonDisplay}",
        )
    }

    @Test
    fun `HardBlock + device scheme unknown (null) yields no prefix`() {
        val r = ThresholdEvaluator.evaluate(
            ThresholdEvaluator.Input(
                localVersion = "0.2.0",
                manifest = manifest(),
                deviceSignatureScheme = null,
            ),
        )
        assertEquals(ThresholdEvaluator.Level.HardBlock, r.level)
        assertEquals(SignatureValidationResult.Unknown, r.signatureValidation)
        // Unknown 不加前缀，保持原 reasonDisplay
        assertEquals("安全漏洞修复", r.reasonDisplay)
    }

    @Test
    fun `HardBlock + empty required scheme in manifest yields Unknown`() {
        val r = ThresholdEvaluator.evaluate(
            ThresholdEvaluator.Input(
                localVersion = "0.2.0",
                manifest = manifest(signatureScheme = emptyList()),
                deviceSignatureScheme = "v2",
            ),
        )
        assertEquals(ThresholdEvaluator.Level.HardBlock, r.level)
        assertEquals(SignatureValidationResult.Unknown, r.signatureValidation)
    }

    @Test
    fun `Advisory level does NOT add signature prefix even if scheme invalid`() {
        val r = ThresholdEvaluator.evaluate(
            ThresholdEvaluator.Input(
                localVersion = "0.2.0",
                manifest = manifest(level = "advisory", reason = "protocol_change"),
                deviceSignatureScheme = "v2", // UpgradeRequired
            ),
        )
        assertEquals(ThresholdEvaluator.Level.Advisory, r.level)
        // Advisory 档 signatureValidation 走 Unknown 分支（不串联）
        assertEquals(SignatureValidationResult.Unknown, r.signatureValidation)
        // reasonDisplay 保持原值
        assertEquals("通信协议变更", r.reasonDisplay)
    }

    @Test
    fun `StrongAdvisory level does NOT add signature prefix`() {
        val r = ThresholdEvaluator.evaluate(
            ThresholdEvaluator.Input(
                localVersion = "0.2.0",
                manifest = manifest(level = "strong_advisory", reason = "breaking_api"),
                deviceSignatureScheme = "v2",
            ),
        )
        assertEquals(ThresholdEvaluator.Level.StrongAdvisory, r.level)
        assertEquals(SignatureValidationResult.Unknown, r.signatureValidation)
        assertEquals("接口重大变更", r.reasonDisplay)
    }

    @Test
    fun `Normal level does NOT trigger signature check at all`() {
        val r = ThresholdEvaluator.evaluate(
            ThresholdEvaluator.Input(
                localVersion = "0.2.0",
                manifest = OtaManifest(version = "0.4.0", versionCode = 24),
                deviceSignatureScheme = "v2",
            ),
        )
        assertEquals(ThresholdEvaluator.Level.Normal, r.level)
        assertEquals(SignatureValidationResult.Unknown, r.signatureValidation)
        assertNull(r.reasonDisplay)
    }
}