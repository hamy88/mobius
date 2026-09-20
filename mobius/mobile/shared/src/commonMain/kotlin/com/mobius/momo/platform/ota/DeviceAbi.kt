package com.mobius.momo.platform.ota

/**
 * 当前设备首选 ABI（commonMain expect fun）。
 *
 * Android: [android.os.Build.SUPPORTED_ABIS][0]
 * iOS / desktop: 空串("")表示无 ABI 概念；当前 iOS / desktop OTA 路径不下载/安装 APK。
 *
 * 调用方在使用前应自行判断非空 + 命中 manifest.builds[].abi，否则走"找不到匹配 CPU 的安装包"提示。
 */
expect fun currentDeviceAbi(): String
