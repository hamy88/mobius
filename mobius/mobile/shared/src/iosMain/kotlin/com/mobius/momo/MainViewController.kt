package com.mobius.momo

import androidx.compose.ui.window.ComposeUIViewController
import com.mobius.momo.ui.MomoApp
import com.mobius.momo.viewmodel.ThemeMode
import com.mobius.momo.viewmodel.MomoAppViewModel
import platform.UIKit.UIScreen
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIViewController

// 持有 viewModel, 供 iOS 通知点击回调(Swift UNUserNotificationCenter delegate)调用 handleDeepLink。
// 参见 docs/IOS-PUSH-DEEPLINK.md。
private var appViewModel: MomoAppViewModel? = null

fun MainViewController(): UIViewController {
    val vm = MomoAppViewModel()
    appViewModel = vm
    val vc = ComposeUIViewController { MomoApp(vm) }
    // 根据已保存的主题模式设界面外观 → 状态栏文字自动适配(深色主题→白字, 浅色→黑字)。
    // 配合 SwiftUI .ignoresSafeArea() 让 Compose Surface(bgPrimary) 填满安全区, 整屏背景跟随主题。
    val isDark = when (vm.authState.value.themeMode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System ->
            UIScreen.mainScreen.traitCollection.userInterfaceStyle ==
            UIUserInterfaceStyle.UIUserInterfaceStyleDark
    }
    vc.overrideUserInterfaceStyle =
        if (isDark) UIUserInterfaceStyle.UIUserInterfaceStyleDark
        else UIUserInterfaceStyle.UIUserInterfaceStyleLight
    return vc
}

/**
 * iOS 通知点击入口：Swift 端在收到 APNs/JPush 点击(didReceive response)时,
 * 从 payload extras 取出 deepLink(momo://group/<id> 或 momo://chat/<sid>)调本函数,
 * 由 ViewModel 进入对应聊天(与 Android MainActivity.handleNotificationDeepLink 对齐)。
 */
fun handleIosNotificationDeepLink(deepLink: String) {
    appViewModel?.handleDeepLink(deepLink)
}
