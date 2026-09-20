package com.mobius.momo.platform.ota

/**
 * iOS 端 OTA 安装触发器占位（v0.4.4 expect class 框架补全）。
 *
 * iOS 不走 APK 直装（App Store / TestFlight 渠道），本类仅满足 expect 契约；
 * 真实调用会立即返回 [OtaInstallResult.Failure]，UI 层在 iOS 上不会触达 downloadAndInstallOta 路径。
 */
actual class OtaInstaller actual constructor() {
    actual fun install(
        apkPath: String,
        expectedPackageName: String,
        onResult: (OtaInstallResult) -> Unit,
    ) {
        onResult(OtaInstallResult.Failure(CODE_UNSUPPORTED, "iOS 暂不支持 APK 直装（请走 TestFlight）"))
    }

    companion object {
        const val CODE_UNSUPPORTED = 2001
    }
}
