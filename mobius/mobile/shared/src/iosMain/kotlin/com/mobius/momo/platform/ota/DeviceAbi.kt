package com.mobius.momo.platform.ota

/**
 * iOS 端"ABI"占位：iOS 应用包走 IPA / App Store，无 ABI 概念。
 *
 * 返回空串；调用方在使用前应判断非空 + 命中 manifest.builds[].abi，否则走"无匹配 CPU 安装包"提示。
 * 当前 iOS OTA 路径不下载/安装 APK，本函数主要为 expect/actual 框架对齐。
 */
actual fun currentDeviceAbi(): String = ""
