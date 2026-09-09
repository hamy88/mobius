package com.mobius.momo.data

/** Android: 通知点击走 JPush receiver → handleDeepLink, 无本地暂存通道。 */
actual fun consumePendingNotificationDeepLink(): String? = null
actual fun platformIsIOS(): Boolean = false
