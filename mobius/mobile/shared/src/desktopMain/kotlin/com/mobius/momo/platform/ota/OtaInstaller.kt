package com.mobius.momo.platform.ota

/**
 * Desktop 端 OTA 安装触发器占位（v0.4.4 expect class 框架补全）。
 *
 * Desktop 走 GitHub Releases 直下 / 系统包管理器，不走 APK 直装。本类仅满足 expect 契约；
 * 真实调用会立即返回 [OtaInstallResult.Failure]。
 */
actual class OtaInstaller actual constructor() {
    actual fun install(
        apkPath: String,
        expectedPackageName: String,
        onResult: (OtaInstallResult) -> Unit,
    ) {
        onResult(OtaInstallResult.Failure(CODE_UNSUPPORTED, "Desktop 暂不支持 APK 直装（请走系统包管理器）"))
    }

    companion object {
        const val CODE_UNSUPPORTED = 2002
    }
}
