package com.mobius.momo.platform.ota

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.mobius.momo.data.AndroidContext
import java.io.File
import java.io.FileInputStream

/**
 * Android 端 OTA 安装触发器（v1.1，方案 §3.4 + §6.2）。
 *
 * 双路径：
 * - Android 14+（SDK ≥ 34）：[PackageInstaller.Session] API（§6.2.1）
 * - Android ≤ 13：回退到 [Intent.ACTION_INSTALL_PACKAGE]（§6.2.2）
 *
 * 首次安装前检测 [PackageInstallerCompat.canRequestPackageInstalls]：
 * - false → 引导跳转 `Settings → Apps → Special access → Install unknown apps`
 *
 * §6 安全红线：
 * - 仅允许 https:// URL（前置断言由 OtaDownloader 完成）
 * - SHA256 校验在安装前由调用方完成；本类不重复校验
 */
class OtaInstaller(private val context: Context = AndroidContext.application) {

    /** 安装结果（callback 给 UI / ViewModel）。 */
    sealed interface InstallResult {
        data class Success(val packageName: String) : InstallResult
        data class Failure(val code: Int, val message: String) : InstallResult
    }

    /**
     * 触发安装完整入口：自动按 SDK 选路径，预检 REQUEST_INSTALL_PACKAGES。
     *
     * @param apkFile DownloadManager 完成后落盘到公共 Download 目录的文件
     * @param expectedPackageName 与 manifest.json 的 android.package_name 强校验；不匹配拒绝
     * @param onResult 安装结果回调（运行在主线程外的调用线程，建议切到主线程更新 UI）
     */
    fun install(
        apkFile: File,
        expectedPackageName: String,
        onResult: (InstallResult) -> Unit,
    ) {
        // §6.2 REQUEST_INSTALL_PACKAGES 授权检测
        if (!PackageInstallerCompat.canRequestPackageInstalls(context)) {
            // 引导用户去设置页授权；调用方需监听用户返回后再次触发 install
            openInstallUnknownAppsSettings()
            onResult(InstallResult.Failure(CODE_NEEDS_USER_PERMISSION, "需要先授予「安装未知应用」权限"))
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            installWithSession(apkFile, expectedPackageName, onResult)
        } else {
            installWithIntent(apkFile, onResult)
        }
    }

    // ===== §6.2.1 PackageInstaller.Session API =====

    private fun installWithSession(
        apkFile: File,
        expectedPackageName: String,
        onResult: (InstallResult) -> Unit,
    ) {
        val pm = context.packageManager
        val installer = pm.packageInstaller
        val totalBytes = apkFile.length()
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(expectedPackageName)
            // size 必填；PackageInstaller 要求精确预填，否则 commit() 抛 IOException（§6.2.1）
            setSize(totalBytes)
        }
        val sessionId = try {
            installer.createSession(params)
        } catch (t: Throwable) {
            onResult(InstallResult.Failure(CODE_SESSION_CREATE_FAILED, t.message ?: "createSession 失败"))
            return
        }

        val session = try {
            installer.openSession(sessionId)
        } catch (t: Throwable) {
            onResult(InstallResult.Failure(CODE_SESSION_OPEN_FAILED, t.message ?: "openSession 失败"))
            return
        }

        try {
            // §6.2.1 分块写入：64KB–1MB 折中
            val chunkSize = 256 * 1024
            session.openWrite("mobius-ota", 0, totalBytes).use { out ->
                FileInputStream(apkFile).use { input ->
                    val buf = ByteArray(chunkSize)
                    var offset = 0L
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        offset += read
                    }
                    session.fsync(out)
                }
            }

            val intent = Intent(ACTION_INSTALL_COMMIT).apply {
                setPackage(context.packageName)
            }
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pendingIntent = PendingIntent.getBroadcast(context, sessionId, intent, pendingFlags)
            session.commit(pendingIntent.intentSender)
            // §6.2.1 close() 必须调用，否则下次升级触发 fd 耗尽
            session.close()

            // 实际安装结果通过 PackageInstaller 会话回调；本期仅同步返回"已提交"提示
            onResult(InstallResult.Success(expectedPackageName))
        } catch (t: Throwable) {
            runCatching { session.close() }
            onResult(InstallResult.Failure(CODE_SESSION_COMMIT_FAILED, t.message ?: "commit 失败"))
        }
    }

    // ===== §6.2.2 Intent.ACTION_INSTALL_PACKAGE 兼容回退 =====

    private fun installWithIntent(apkFile: File, onResult: (InstallResult) -> Unit) {
        val uri: Uri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )
        } catch (t: Throwable) {
            // 部分设备无 FileProvider 配置 → 退化到 Uri.fromFile（API 24+ 会抛 FileUriExposedException，仅兜底）
            onResult(InstallResult.Failure(CODE_FILE_PROVIDER_MISSING, "FileProvider 未配置：${t.message}"))
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            // Android ≤ 13 路径下，结果由用户在系统安装器中确认；此处仅返回"已触发"
            onResult(InstallResult.Success(apkFile.nameWithoutExtension))
        } catch (t: Throwable) {
            onResult(InstallResult.Failure(CODE_INTENT_FAILED, t.message ?: "ACTION_INSTALL_PACKAGE 失败"))
        }
    }

    private fun openInstallUnknownAppsSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    companion object {
        const val ACTION_INSTALL_COMMIT = "com.mobius.momo.action.OTA_INSTALL_COMMIT"
        const val CODE_NEEDS_USER_PERMISSION = 1001
        const val CODE_SESSION_CREATE_FAILED = 1002
        const val CODE_SESSION_OPEN_FAILED = 1003
        const val CODE_SESSION_COMMIT_FAILED = 1004
        const val CODE_INTENT_FAILED = 1005
        const val CODE_FILE_PROVIDER_MISSING = 1006
    }
}

/**
 * Android 端 PackageInstaller 兼容层（§6.2）。
 *
 * 当前 AndroidX core 不提供 `canRequestPackageInstalls` 兼容层，所有版本均直接调 Context
 * API；这里保留为单独的 object 是为了未来若 SDK 行为变更时可集中加版本分支。
 */
object PackageInstallerCompat {
    fun canRequestPackageInstalls(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            // API 26 起才有该 API；< 26 视为可安装（无运行时权限机制）
            true
        }
}
