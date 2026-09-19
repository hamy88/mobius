package com.mobius.momo.ota

import com.mobius.momo.data.SignatureSchemeValidator
import com.mobius.momo.data.SignatureValidationResult
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 覆盖 §D7 + §5.4 签名 scheme 跳级检测的 5+ 边界用例：
 *
 * 1) Ok              : 设备 ⊇ 要求（v2,v3 ⊇ [v2,v3]）
 * 2) Ok              : 设备 = 要求（完全相同）
 * 3) Ok              : 设备 ⊇ 要求 + 额外（v2,v3,v4 ⊇ [v2,v3]）
 * 4) UpgradeRequired : 设备 ⊂ 要求（v2 ⊂ [v2,v3]）
 * 5) UpgradeRequired : 设备缺关键（[] 或 null vs [v2]）
 * 6) Downgrade       : 设备 ⊋ 要求（v2,v3,v4 ⊋ [v2,v3] 实际上是 Ok，不是 Downgrade；测试 partial overlap 走 Downgrade）
 * 7) Unknown         : 任一为 null / 空
 * 8) Unknown         : 输入均为空白字符串
 * 9) 大小写 + 分隔符容错
 */
class SignatureSchemeValidatorTest {

    @Test
    fun `device contains required yields Ok`() {
        val r = SignatureSchemeValidator.validate("v2,v3", listOf("v2", "v3"))
        assertEquals(SignatureValidationResult.Ok, r)
    }

    @Test
    fun `device equals required yields Ok`() {
        val r = SignatureSchemeValidator.validate("v2,v3", listOf("v2", "v3"))
        assertEquals(SignatureValidationResult.Ok, r)
    }

    @Test
    fun `device has more than required still Ok (subset covers)`() {
        // v2,v3,v4 ⊇ v2,v3 → Ok（设备支持更多一定能装要求的 release）
        val r = SignatureSchemeValidator.validate("v2,v3,v4", listOf("v2", "v3"))
        assertEquals(SignatureValidationResult.Ok, r)
    }

    @Test
    fun `device missing required yields UpgradeRequired`() {
        val r = SignatureSchemeValidator.validate("v2", listOf("v2", "v3"))
        assertEquals(SignatureValidationResult.UpgradeRequired, r)
    }

    @Test
    fun `device has empty required scheme yields Unknown`() {
        val r = SignatureSchemeValidator.validate("v2,v3", emptyList())
        assertEquals(SignatureValidationResult.Unknown, r)
    }

    @Test
    fun `device null yields Unknown`() {
        val r = SignatureSchemeValidator.validate(null, listOf("v2"))
        assertEquals(SignatureValidationResult.Unknown, r)
    }

    @Test
    fun `device blank string yields Unknown`() {
        val r = SignatureSchemeValidator.validate("  ", listOf("v2"))
        assertEquals(SignatureValidationResult.Unknown, r)
    }

    @Test
    fun `device contains unrelated schemes yields Downgrade`() {
        // 设备只有 v1，要求 v2 → device ∩ required = ∅ → 走 else 分支（partial overlap）
        val r = SignatureSchemeValidator.validate("v1", listOf("v2"))
        assertEquals(SignatureValidationResult.Downgrade, r, "完全不交集应降级（partial overlap）")
    }

    @Test
    fun `partial overlap with device superset yields Downgrade`() {
        // 设备 v2,v3，要求 v2,v4 → device ⊋ required (v3 设备有但要求没)；实际是 partial overlap
        val r = SignatureSchemeValidator.validate("v2,v3", listOf("v2", "v4"))
        assertEquals(SignatureValidationResult.Downgrade, r)
    }

    @Test
    fun `case insensitive and separator tolerant`() {
        val r = SignatureSchemeValidator.validate("V2; v3  V4", listOf("v2", "v3"))
        assertEquals(SignatureValidationResult.Ok, r)
    }

    @Test
    fun `unknown scheme versions are passed through`() {
        // 未识别的 scheme 名（如 "v5"）：device {v2,v5} vs required {v2,v3} → partial overlap → Downgrade
        val r = SignatureSchemeValidator.validate("v2,v5", listOf("v2", "v3"))
        assertEquals(SignatureValidationResult.Downgrade, r)
    }

    @Test
    fun `device missing required v3 with v2 only yields UpgradeRequired`() {
        // device {v2} ⊂ required {v2,v3} → UpgradeRequired（典型 v2→v3 跳级）
        val r = SignatureSchemeValidator.validate("v2", listOf("v2", "v3"))
        assertEquals(SignatureValidationResult.UpgradeRequired, r)
    }
}