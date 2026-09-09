@file:Suppress("EXPECT_ACTUAL_CLASSES_IN_BETA_WARNING")

package com.mobius.momo.data

expect object NotificationGateway {
    suspend fun requestPermission(): Boolean
    fun requestIgnoreBatteryOptimizations(): Boolean
    fun show(title: String, body: String, deepLink: String? = null)
    fun startForeground()
    fun stopForeground()
}

expect fun isAppInForeground(): Boolean
