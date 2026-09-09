package com.mobius.momo.data

import android.content.Context
import android.content.Intent
import cn.jpush.android.api.CustomMessage
import cn.jpush.android.api.NotificationMessage
import cn.jpush.android.service.JPushMessageReceiver

/**
 * 极光推送消息接收器（继承 JPush SDK 的 JPushMessageReceiver）。
 *
 * - [onRegister]: RegistrationID 到达时缓存到 [AndroidJPushProvider]，省去轮询。
 * - [onMessage]: 后端下发的「自定义消息（透传）」。JPush 不会自动弹通知，这里走我们的本地通知渠道
 *   （AndroidContext.showLocalNotification，复用 "Mobius 消息" 渠道），并支持 extra(JSON) 里的 deepLink。
 * - [onNotifyMessageOpened]: 用户点击 JPush 自动弹出的通知 → 拉起入口 Activity，并尽量带 deepLink。
 *
 * 在 AndroidManifest 中以 RECEIVE_MESSAGE intent-filter 注册（见 androidApp/AndroidManifest.xml）。
 */
class MomoJPushReceiver : JPushMessageReceiver() {

    override fun onRegister(context: Context, registrationId: String) {
        AndroidJPushProvider.onRegistered(registrationId)
    }

    override fun onMessage(context: Context, message: CustomMessage) {
        val title = message.title?.takeIf { it.isNotBlank() } ?: "Mobius"
        // JPush 4.x: CustomMessage 的正文是 `message` 字段，扩展是 `extra`(JSON 字符串)。
        val content = message.message.orEmpty()
        if (content.isBlank()) return
        val deepLink = message.extra?.takeIf { it.isNotBlank() }?.let { PushPayloadParser.parseDeepLinkFromJson(it) }
        AndroidContext.showLocalNotification(title, content, deepLink)
    }

    override fun onNotifyMessageOpened(context: Context, message: NotificationMessage) {
        val extras = message.notificationExtras
        val deepLink = extras?.takeIf { it.isNotBlank() }?.let { PushPayloadParser.parseDeepLinkFromJson(it) }
        launchMainActivity(context, deepLink)
    }

    private fun launchMainActivity(context: Context, deepLink: String?) {
        runCatching {
            // 不能直接引用 androidApp 的 MainActivity（shared 库反向依赖）；用 launcher intent
            // 拉起入口 Activity（与 AndroidContext.showLocalNotification 同一手法）。
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!deepLink.isNullOrBlank()) intent.data = android.net.Uri.parse(deepLink)
            context.startActivity(intent)
        }
    }
}

/**
 * 解析推送 extras(JSON 字符串) 中的 deepLink。JPush 的 extra / notificationExtras 都是 JSON 字符串，
 * 形如 `{"deepLink":"momo://chat/abc"}`。非法 JSON 容错返回 null。
 */
internal object PushPayloadParser {
    private const val DEEP_LINK_KEY = "deepLink"

    fun parseDeepLinkFromJson(json: String): String? = runCatching {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(json) as? kotlinx.serialization.json.JsonObject
        (obj?.get(DEEP_LINK_KEY) as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
