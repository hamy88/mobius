package com.mobius.momo.ui

import androidx.compose.runtime.Composable

// 跨平台返回键处理. Android 接管物理返回键, desktop/iOS 无物理返回键为 no-op.
@Composable
expect fun AppBackHandler(enabled: Boolean, onBack: () -> Unit)
