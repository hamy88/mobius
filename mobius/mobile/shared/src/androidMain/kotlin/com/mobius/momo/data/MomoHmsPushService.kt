package com.mobius.momo.data

import android.os.Bundle
import android.util.Log
import com.huawei.hms.push.HmsMessageService
import com.huawei.hms.push.RemoteMessage

/**
 * 华为 HMS Push 消息服务(接管)。
 *
 * 为何自己接管而非用 JPush 华为插件: JPush 4.0.5 的 PluginHuaweiPlatformsService 只 override 了
 * 旧的 onNewToken(String), 既没 override onTokenError, 也没 override HMS 6.x 的 Bundle 新签名 →
 * 接不住华为令牌。本服务接管 com.huawei.push.action.MESSAGING_EVENT(Manifest 里 remove 掉 JPush 的服务),
 * 拿到华为令牌后缓存到 [AndroidJPushProvider] 供上报/直推。
 */
class MomoHmsPushService : HmsMessageService() {

    override fun onNewToken(token: String) {
        AndroidJPushProvider.onHmsToken(token)
    }

    override fun onNewToken(token: String, bundle: Bundle?) {
        AndroidJPushProvider.onHmsToken(token)
    }

    override fun onTokenError(e: Exception) {
        Log.w("MomoHms", "HMS onTokenError: ${e.message}")
    }

    override fun onTokenError(e: Exception, bundle: Bundle?) {
        Log.w("MomoHms", "HMS onTokenError: ${e.message}")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val notif = runCatching { message.notification }.getOrNull()
        val title = notif?.title?.takeIf { it.isNotBlank() } ?: "Mobius"
        val body = notif?.body?.takeIf { it.isNotBlank() }
            ?: runCatching { message.dataOfMap?.get("message") ?: message.dataOfMap?.get("body") }.getOrNull()
            ?: return
        val deepLink = runCatching { message.dataOfMap?.get("deepLink") }.getOrNull()
        AndroidContext.showLocalNotification(title, body, deepLink)
    }
}
