package com.mobius.momo.platform.ota

/**
 * Desktop 端"ABI"占位：Desktop 应用走系统包管理器，无 ABI 概念。
 *
 * 返回空串；调用方在使用前应判断非空 + 命中 manifest.builds[].abi，否则走"无匹配 CPU 安装包"提示。
 * 当前 Desktop OTA 路径不下载/安装 APK，本函数主要为 expect/actual 框架对齐。
 */
actual fun currentDeviceAbi(): String = ""
