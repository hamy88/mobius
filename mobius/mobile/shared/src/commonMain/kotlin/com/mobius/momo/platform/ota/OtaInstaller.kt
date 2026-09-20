package com.mobius.momo.platform.ota

/**
 * OTA 安装结果（commonMain，平台无关）。
 *
 * Success = 安装流程已触发；Android 14+ 走 PackageInstaller.Session 由系统接管实际安装确认，
 * Android ≤ 13 走系统安装器弹窗由用户点确认。
 */
sealed interface OtaInstallResult {
    data class Success(val packageName: String) : OtaInstallResult
    data class Failure(val code: Int, val message: String) : OtaInstallResult
}

/**
 * OTA 安装触发器公共接口（commonMain expect class）。
 *
 * 平台层 [androidMain] 实装（Android 14+ 走 PackageInstaller.Session, ≤ 13 走 ACTION_INSTALL_PACKAGE）；
 * [iosMain] / [desktopMain] 仅占位（throw UnsupportedOperationException）。
 *
 * @param apkPath APK 在设备上的绝对路径（DownloadManager 完成后落到公共 Download 目录）。
 *                不直接传 [java.io.File] 以避免 commonMain 跨平台类型依赖。
 * @param expectedPackageName 与 manifest.android.package_name 强校验；不匹配拒绝安装。
 * @param onResult 安装结果回调（运行在调用线程；调用方需自行切到主线程更新 UI）。
 */
expect class OtaInstaller() {
    fun install(
        apkPath: String,
        expectedPackageName: String,
        onResult: (OtaInstallResult) -> Unit,
    )
}
