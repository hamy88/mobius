package com.mobius.momo.data

// iOS 端聚合推送暂未接入（后续走 APNs），先 NoOp：令牌恒为 null，保持三端调用一致。
private object NoOpPushProvider : PushProvider {
    override fun init() = Unit
    override suspend fun getRegistrationId(): String? = null
    override suspend fun getHmsToken(): String? = null
    override fun setEnabled(enabled: Boolean) = Unit
}

actual fun createPushProvider(): PushProvider = NoOpPushProvider
