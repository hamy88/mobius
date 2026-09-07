package com.mobius.momo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Application
import com.mobius.momo.data.AndroidContext

class MomoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidContext.application = this
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val messageChannel = NotificationChannel(
            AndroidContext.NOTIFICATION_CHANNEL_ID,
            "Mobius 消息",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Mobius 的新消息与回复提醒"
            enableVibration(true)
            enableLights(true)
        }
        // 不再创建「后台运行」保活渠道——已取消常驻状态栏通知，后台推送改由 JPush 远程通道负责。
        notificationManager.createNotificationChannels(listOf(messageChannel))
    }
}
