package com.mobius.momo.data

actual object NotificationGateway {
    actual suspend fun requestPermission(): Boolean = true
    actual fun requestIgnoreBatteryOptimizations(): Boolean = false
    actual fun show(title: String, body: String, deepLink: String?) = Unit
    actual fun startForeground() = Unit
    actual fun stopForeground() = Unit
}

actual fun isAppInForeground(): Boolean = false
