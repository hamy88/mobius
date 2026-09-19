package com.mobius.momo.data

/**
 * 签名 scheme 校验结果（§D7 + §5.4）。
 *
 * 触发场景：
 * - [Ok]              : 设备 scheme 完全覆盖 release 要求，bspatch 可正常拼装
 * - [UpgradeRequired] : 设备 scheme 缺关键 scheme（典型 v2 → v3 跳级），必须走全量安装
 * - [Downgrade]       : 设备 scheme 包含 release 未要求的 scheme（一般降级安装场景），拒绝 OTA
 * - [Unknown]         : 任一输入缺失（不阻断，仅记日志）
 */
enum class SignatureValidationResult {
    Ok,
    UpgradeRequired,
    Downgrade,
    Unknown,
    ;

    /** 简洁文案标识（用于 reason 前缀与日志）。 */
    val wire: String
        get() = when (this) {
            Ok -> "ok"
            UpgradeRequired -> "upgrade_required"
            Downgrade -> "downgrade"
            Unknown -> "unknown"
        }
}

/**
 * APK Signature Scheme 跳级检测器（§5.4）。
 *
 * 校验逻辑：
 * ```
 * device = {"v2", "v3"}            // 当前设备已安装包支持的 scheme
 * required = ["v2", "v3"]          // release manifest 要求的 scheme
 *
 * if device.isEmpty() || required.isEmpty() → Unknown
 * if device ⊇ required → Ok
 * if device ⊂ required → UpgradeRequired（典型 v2 → v3 跳级）
 * if device ⊋ required → Downgrade（一般降级安装场景）
 * else → Downgrade（一般不会出现，留兜底）
 * ```
 *
 * 输入 scheme 字符串支持 `,` / `;` / 空格分隔；大小写不敏感（统一 lowercase）。
 *
 * 调用方（ThresholdEvaluator）只在 hard_block 档串联校验：结果为 UpgradeRequired/Downgrade 时
 * 不阻断弹窗，但在 reason 文案前加 "签名 scheme 不满足" 前缀，便于管理员 / 用户识别。
 */
object SignatureSchemeValidator {

    private val SEPARATORS = Regex("[,;\\s]+")

    /**
     * @param deviceScheme 当前设备已安装包支持的 scheme，例如 `v2,v3`；null / 空白视为 Unknown
     * @param requiredScheme release manifest 要求 scheme 列表，例 `["v2", "v3"]`
     */
    fun validate(
        deviceScheme: String?,
        requiredScheme: List<String>,
    ): SignatureValidationResult {
        val deviceRaw = deviceScheme?.takeIf { it.isNotBlank() } ?: return SignatureValidationResult.Unknown
        val device = deviceRaw.split(SEPARATORS)
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        val required = requiredScheme
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

        if (device.isEmpty() || required.isEmpty()) return SignatureValidationResult.Unknown

        return when {
            device.containsAll(required) -> SignatureValidationResult.Ok
            required.containsAll(device) -> SignatureValidationResult.UpgradeRequired
            else -> SignatureValidationResult.Downgrade
        }
    }
}