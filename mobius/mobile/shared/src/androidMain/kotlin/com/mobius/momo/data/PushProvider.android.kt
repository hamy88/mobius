package com.mobius.momo.data

import cn.jpush.android.api.JPushInterface
import com.mobius.momo.shared.BuildConfig
import kotlinx.coroutines.delay

/**
 * Android 端 PushProvider 实现：极光推送 JPush。
 *
 * - [init] 读取 BuildConfig.MOMO_JPUSH_APPKEY；为空（未配置）时整个推送链路安全降级为空操作，
 *   App 仍可正常构建运行。配置后调用 JPushInterface.init 完成 SDK 注册（AppKey 经
 *   AndroidManifest 的 meta-data JPUSH_APPKEY 注入）。
 * - [getRegistrationId] 轮询 JPushInterface.getRegistrationID：JPush 注册是异步的，刚 init 完
 *   通常拿不到，需等秒级。轮询拿到后缓存到 [registrationId]。
 * - [setEnabled] 映射到 stopPush / resumePush，配合「消息推送」开关。
 */
internal object AndroidJPushProvider : PushProvider {
    @Volatile private var initialized: Boolean = false
    @Volatile private var registrationId: String? = null

    /** 是否已配置有效 AppKey（来自 gradle.properties: MOMO_JPUSH_APPKEY）。 */
    private val configured: Boolean get() = BuildConfig.MOMO_JPUSH_APPKEY.isNotBlank()

    override fun init() {
        if (initialized) return
        initialized = true
        if (!configured) return
        val context = AndroidContext.application
        // debug 包打开 JPush 详细日志，release 关闭，避免泄露与噪音。
        runCatching {
            JPushInterface.setDebugMode(false)
            JPushInterface.init(context)
        }
        // 显式开启华为 HMS Push auto-init: JPush 4.0.5 的华为插件与 HMS 6.x 协作时,
        // auto-init 不一定被自动触发, 会导致 HMS 不下发令牌 → JPush 拿不到华为令牌。
        // 开启后 HMS 会异步经 onNewToken 下发令牌(由 JPush 华为桥接服务接收并上报)。
        runCatching { enableHmsAutoInit(context) }
    }

    /** 反射 HmsMessaging.setAutoInitEnabled(true), 触发 HMS 令牌下发。 */
    private fun enableHmsAutoInit(context: android.content.Context) {
        val cls = Class.forName("com.huawei.hms.push.HmsMessaging")
        val inst = cls.getMethod("getInstance", android.content.Context::class.java).invoke(null, context)
        cls.getMethod("setAutoInitEnabled", java.lang.Boolean.TYPE).invoke(inst, true)
    }

    override suspend fun getRegistrationId(): String? {
        if (!configured) return null
        val context = AndroidContext.application
        // JPush 注册是异步的：最多等约 15s（50 次 × 300ms）。多数设备 1~3s 内拿到。
        repeat(50) {
            registrationId?.let { return it }
            val rid = runCatching { JPushInterface.getRegistrationID(context) }.getOrNull()
            if (!rid.isNullOrBlank()) {
                registrationId = rid
                return rid
            }
            delay(300)
        }
        return registrationId
    }

    override suspend fun getHmsToken(): String? {
        // 华为令牌由 MomoHmsPushService.onNewToken 异步写入 hmsToken。最多等约 10s（20×500ms）。
        repeat(20) {
            hmsToken?.let { return it }
            delay(500)
        }
        return hmsToken
    }

    /** 供 JPush 接收器在 onRegister 回调到达时直接缓存，避免轮询。 */
    fun onRegistered(rid: String?) {
        if (!rid.isNullOrBlank()) registrationId = rid
    }

    /** 华为 HMS 令牌(MomoHmsPushService.onNewToken 回调到达时缓存)，供后续上报/直接下发。 */
    @Volatile var hmsToken: String? = null
        private set

    fun onHmsToken(token: String?) {
        if (!token.isNullOrBlank()) hmsToken = token
    }

    override fun setEnabled(enabled: Boolean) {
        if (!configured) return
        val context = AndroidContext.application
        runCatching {
            if (enabled) JPushInterface.resumePush(context) else JPushInterface.stopPush(context)
        }
    }
}

actual fun createPushProvider(): PushProvider = AndroidJPushProvider
