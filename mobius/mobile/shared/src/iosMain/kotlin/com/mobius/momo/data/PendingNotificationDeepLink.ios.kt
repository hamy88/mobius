package com.mobius.momo.data

/** iOS: 通知点击 deepLink 由 Swift AppDelegate → MainViewControllerKt.handleIosNotificationDeepLink
 *  直达 ViewModel, 无需 Kotlin 侧暂存通道。 */
actual fun consumePendingNotificationDeepLink(): String? = null
actual fun platformIsIOS(): Boolean = true
