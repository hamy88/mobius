@file:Suppress("EXPECT_ACTUAL_CLASSES_IN_BETA_WARNING")
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.mobius.momo.data

import platform.UIKit.UIApplication
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

// iOS 本地通知(桥接版): Kotlin 侧 show() 发 NSNotificationCenter 广播,
// Swift AppDelegate 监听后弹 UNNotification(权限/前台横幅/点击 deepLink 均在 Swift 侧处理)。
actual object NotificationGateway {
    actual suspend fun requestPermission(): Boolean = true // 权限由 AppDelegate 在启动时请求

    actual fun requestIgnoreBatteryOptimizations(): Boolean = false

    actual fun show(title: String, body: String, deepLink: String?) {
        runCatching {
            val payload: Map<Any?, Any?> = mapOf(
                "title" to title,
                "body" to body,
                "deepLink" to (deepLink ?: ""),
            )
            platform.Foundation.NSNotificationCenter.defaultCenter.postNotificationName(
                "momo.showNotification",
                null,
                payload,
            )
        }
    }

    actual fun startForeground() = Unit
    actual fun stopForeground() = Unit
}

actual fun isAppInForeground(): Boolean =
    UIApplication.sharedApplication.applicationState !=
        platform.UIKit.UIApplicationState.UIApplicationStateBackground
