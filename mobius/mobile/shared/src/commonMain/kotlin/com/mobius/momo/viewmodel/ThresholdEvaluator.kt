package com.mobius.momo.viewmodel

import com.mobius.momo.data.OtaManifest
import com.mobius.momo.data.SemVer
import com.mobius.momo.data.SignatureSchemeValidator
import com.mobius.momo.data.SignatureValidationResult

/**
 * §10.5.2 客户端阈值四档分级判定（v1.1.1）。
 *
 * 触发条件回顾（与方案保持一致）：
 * - normal：远端 > 本地，且 min_supported_version 缺失 或 本地 ≥ 该阈值
 * - advisory：min_supported_version 已填，且 level = advisory
 * - strong_advisory：level = strong_advisory
 * - hard_block：level = hard_block
 *
 * 防御性默认值：
 * - 未识别的 level 字符串一律降级为 advisory（§10.5.1 契约补充）
 * - 未识别的 reason 不显示"为什么"说明框（仅影响 UX，不影响档位）
 * - 远端 ≤ 本地 直接判定 NoUpdate，不进入阈值链路（§3.3 降级防御）
 */
object ThresholdEvaluator {

    /** 评估结果档位（与 §10.5.2 表一一对应）。 */
    enum class Level {
        NoUpdate,
        Normal,
        Advisory,
        StrongAdvisory,
        HardBlock,
        /** 元数据缺失 / 解析失败——客户端不弹窗，只在设置页给"无法验证更新源"提示。 */
        Invalid,
    }

    /** 评估输入。 */
    data class Input(
        val localVersion: String,
        val manifest: OtaManifest,
        /**
         * 当前设备已安装包支持的签名 scheme（如 `"v2,v3"`），用于 §5.4 跳级检测。
         * null / 空白 → 视为未启用签名 scheme 校验（不阻断）。
         */
        val deviceSignatureScheme: String? = null,
    )

    /** 评估输出（除档位外，还携带 UX 层渲染所需的 reason / advisoryId / bypassable）。 */
    data class Result(
        val level: Level,
        val minSupportedVersion: String? = null,
        val reason: String? = null,
        val reasonDisplay: String? = null,
        val advisoryId: String? = null,
        val hardBlockBypassable: Boolean = true,
        /** 升级方向；NoUpdate 时为 0。 */
        val compareDirection: Int = 0,
        /**
         * §5.4 签名 scheme 校验结果（不阻断弹窗，仅用于 reason 文案前缀）。
         * 默认为 [SignatureValidationResult.Unknown]（设备未上报 scheme 时）。
         */
        val signatureValidation: SignatureValidationResult = SignatureValidationResult.Unknown,
    )

    /** 五种 reason 枚举（§10.5.1 表）。 */
    enum class Reason(val wire: String) {
        SecurityCve("security_cve"),
        BreakingApi("breaking_api"),
        ProtocolChange("protocol_change"),
        PolicyCompliance("policy_compliance"),
        Other("other"),
        ;

        companion object {
            fun fromWire(value: String?): Reason? = value
                ?.takeIf { it.isNotBlank() }
                ?.let { wire -> entries.firstOrNull { it.wire == wire } }
        }
    }

    /**
     * 主入口。失败兜底：解析抛错 / manifest 缺字段 → 返回 Invalid 档（UX 层展示"无法验证"）。
     */
    fun evaluate(input: Input): Result {
        val remote = SemVer.parse(input.manifest.version) ?: return invalid(input)
        val local = SemVer.parse(input.localVersion) ?: return invalid(input)
        val cmp = remote.compareTo(local)

        // §3.3 降级防御：远端 ≤ 本地 → NoUpdate
        if (cmp <= 0) {
            return Result(level = Level.NoUpdate, compareDirection = cmp)
        }

        // 无阈值字段 → normal（§10.5.2 第一行）
        val min = input.manifest.minSupportedVersion?.takeIf { it.isNotBlank() }
        if (min == null) {
            return Result(level = Level.Normal, compareDirection = cmp)
        }

        val minSemver = SemVer.parse(min) ?: return invalid(input)
        // 本地 ≥ 阈值 → 仍按 normal 走（用户已在支持的最低版本之上）
        if (local >= minSemver) {
            return Result(level = Level.Normal, compareDirection = cmp)
        }

        // 本地 < 阈值 → 按 level 分档
        val rawLevel = input.manifest.minSupportedVersionLevel
        val reasonWire = input.manifest.minSupportedVersionReason
        val reason = Reason.fromWire(reasonWire)
        val reasonDisplay = reasonDisplay(reason, reasonWire)
        val advisoryId = input.manifest.minSupportedVersionAdvisoryId?.takeIf { it.isNotBlank() }
        val bypassable = input.manifest.hardBlockBypassable

        val level = when (rawLevel?.lowercase()) {
            "strong_advisory" -> Level.StrongAdvisory
            "hard_block" -> Level.HardBlock
            // advisory / null / 未知字符串 → 全部降级 advisory（§10.5.1 防御性默认值）
            else -> Level.Advisory
        }

        // §5.4 签名 scheme 跳级检测：hard_block 档串联校验；其他档不阻断（patch 失败走全量兜底即可）
        val signatureValidation = if (level == Level.HardBlock) {
            SignatureSchemeValidator.validate(
                deviceScheme = input.deviceSignatureScheme,
                requiredScheme = input.manifest.android.signatureScheme,
            )
        } else {
            SignatureValidationResult.Unknown
        }
        val effectiveReasonDisplay = if (level == Level.HardBlock &&
            (signatureValidation == SignatureValidationResult.UpgradeRequired ||
                signatureValidation == SignatureValidationResult.Downgrade)
        ) {
            val prefix = "签名 scheme 不满足（${signatureValidation.wire}）；"
            prefix + (reasonDisplay ?: "需要升级")
        } else {
            reasonDisplay
        }

        return Result(
            level = level,
            minSupportedVersion = min,
            reason = reasonWire,
            reasonDisplay = effectiveReasonDisplay,
            advisoryId = advisoryId,
            hardBlockBypassable = bypassable,
            compareDirection = cmp,
            signatureValidation = signatureValidation,
        )
    }

    /** reason 中文/英文映射（§10.5.3.2 + §10.5.3.3 模板用）。 */
    private fun reasonDisplay(reason: Reason?, wire: String?): String? {
        if (reason == null) return null
        return when (reason) {
            Reason.SecurityCve -> "安全漏洞修复"
            Reason.BreakingApi -> "接口重大变更"
            Reason.ProtocolChange -> "通信协议变更"
            Reason.PolicyCompliance -> "合规要求"
            Reason.Other -> wire ?: "其他原因"
        }
    }

    private fun invalid(input: Input): Result {
        // 解析失败但远端可能仍 > 本地，compareDirection 置 0 便于上层兜底文案。
        return Result(
            level = Level.Invalid,
            minSupportedVersion = input.manifest.minSupportedVersion,
            reason = input.manifest.minSupportedVersionReason,
            reasonDisplay = null,
            advisoryId = input.manifest.minSupportedVersionAdvisoryId,
            hardBlockBypassable = input.manifest.hardBlockBypassable,
        )
    }

    // ===== 各档静默期常量（小时，§10.5.4） =====
    const val SILENT_HOURS_NORMAL: Long = 6L
    const val SILENT_HOURS_ADVISORY: Long = 24L
    const val SILENT_HOURS_STRONG_ADVISORY: Long = 12L
    /** hard_block 无静默期（每次冷启动必弹）。 */
    const val SILENT_HOURS_HARD_BLOCK: Long = 0L

    fun silentPeriodMillis(level: Level): Long = when (level) {
        Level.Normal -> SILENT_HOURS_NORMAL * 60L * 60L * 1000L
        Level.Advisory -> SILENT_HOURS_ADVISORY * 60L * 60L * 1000L
        Level.StrongAdvisory -> SILENT_HOURS_STRONG_ADVISORY * 60L * 60L * 1000L
        // hard_block 无静默期：每次冷启动必弹（§10.5.4）
        Level.HardBlock -> 0L
        Level.NoUpdate, Level.Invalid -> Long.MAX_VALUE / 2
    }
}
