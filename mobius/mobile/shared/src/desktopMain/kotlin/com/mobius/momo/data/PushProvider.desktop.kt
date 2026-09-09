package com.mobius.momo.data

// Desktop 端无聚合推送通道，NoOp 实现：注册令牌恒为 null（后端不会收到 desktop 设备），
// 保持 commonMain 调用方代码在三端一致。
private object NoOpPushProvider : PushProvider {
    override fun init() = Unit
    override suspend fun getRegistrationId(): String? = null
    override suspend fun getHmsToken(): String? = null
    override fun setEnabled(enabled: Boolean) = Unit
}

actual fun createPushProvider(): PushProvider = NoOpPushProvider
