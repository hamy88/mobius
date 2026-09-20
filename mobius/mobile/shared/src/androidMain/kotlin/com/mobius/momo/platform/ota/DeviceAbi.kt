package com.mobius.momo.platform.ota

import android.os.Build

/**
 * Android 端设备 ABI：取 [Build.SUPPORTED_ABIS][0]，OEM 厂商通常把首选 ABI 放在首位。
 *
 * manifest.builds[] 中按 arm64-v8a / armeabi-v7a / universal 匹配；
 * 找不到匹配时由调用方决定是否拒绝下载（[OtaDownloader.enqueue] 前应先做 ABI 选择）。
 */
actual fun currentDeviceAbi(): String = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
