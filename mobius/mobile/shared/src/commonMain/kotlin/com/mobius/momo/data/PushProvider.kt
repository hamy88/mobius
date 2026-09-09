package com.mobius.momo.data

/**
 * 远程聚合推送通道（Android 端实现为「极光推送 JPush」）。
 *
 * 现有 [NotificationGateway] 负责的是「本地通知 + 前台 keepalive 服务」——App 在后台靠
 * 常驻进程维持 SSE，收到消息时弹本地通知。一旦系统把进程彻底杀掉，那条 SSE 就断了，
 * 消息便无法送达。聚合推送补上这一环：App 启动时由 JPush SDK 注册拿到设备唯一
 * RegistrationID，登录后上报给后端；之后即便 App 被杀，后端也能通过 JPush（及其聚合的
 * 华为 / 小米 / OPPO / vivo / 魅族 等厂商通道）把消息推到设备状态栏。
 *
 * desktop / iOS 暂为 NoOp（iOS 后续接 APNs）。
 */
interface PushProvider {
    /**
     * 初始化推送 SDK（如 JPushInterface.init）。幂等：多次调用与一次等价。
     * 未配置 AppKey 时为安全空操作（不抛异常），仅写日志。
     */
    fun init()

    /**
     * 获取当前设备的推送令牌（JPush RegistrationID）。SDK 注册是异步的，刚 init 完可能还拿不到，
     * 因此本方法内部会做有限时长的轮询；超时仍未拿到则返回 null。拿到后应上报给后端。
     */
    suspend fun getRegistrationId(): String?

    /**
     * 华为 HMS 推送令牌(App 被杀时华为直推用)。由 MomoHmsPushService.onNewToken 异步捕获,
     * 本方法内部做有限轮询; 拿到后上报后端(platform="huawei")。非华为设备/desktop/iOS 返回 null。
     */
    suspend fun getHmsToken(): String?

    /**
     * 暂停 / 恢复推送。用户在「设置」里关掉「消息推送」时调用 [setEnabled]`(false)`，
     * 对应 JPushInterface.stopPush；重新开启时 resumePush。
     */
    fun setEnabled(enabled: Boolean)
}

/** 平台工厂：Android 返回 JPush 实现，其余平台返回 NoOp。 */
expect fun createPushProvider(): PushProvider
