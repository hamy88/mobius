# iOS 推送 + 通知点击进聊天 落地指南

> Android 端「点击状态栏通知进入对应聊天」已通过 `MainActivity.handleNotificationDeepLink` + `launchMode=singleTop` 实现。
> iOS 端此前**完全没接推送**(`PushProvider.ios.kt` 为 NoOp),需要补完整 APNs 链路。本文件说明已完成与待完成部分。

## 已完成(代码侧)

- **deepLink 导航(公共,三端共用)**:`MomoAppViewModel.handleDeepLink(deepLink)`
  - `momo://group/<conversationId>` → 群聊(`openConversation`)
  - `momo://chat/<sessionId>` → 1v1 会话(`openSession`)
  - 未登录暂存,登录后 `consumePendingDeepLink` 处理。
- **iOS Kotlin 桥**:`shared/src/iosMain/.../MainViewController.kt` 持有 viewModel,暴露
  `MainViewControllerKt.handleIosNotificationDeepLink(deepLink)` 供 Swift 调用。
- **Info.plist**:已加 `UIBackgroundModes = [remote-notification]`。
- **Swift 回调**:`iosApp/iosApp/MomoMobileApp.swift` 加了 `AppDelegate`(UNUserNotificationCenter delegate):
  - 启动请求通知授权 + `registerForRemoteNotifications()`。
  - 点击/冷启动 → 从 `userInfo["deepLink"]` 取 deepLink → `MainViewControllerKt.handleIosNotificationDeepLink`。

## 待完成(Mac / Apple 后台 / 服务端)

iOS 端**通知到达**本身需要以下,缺一则点通知无内容可点:

1. **Xcode 加 capability**:Signing & Capabilities → **+ Push Notifications**(自动加 entitlement `aps-environment`)。
2. **APNs Auth Key(.p8)**:Apple Developer → Keys → 生成 Push Notifications key,下载 `.p8`,记下 Team ID / Key ID。
3. **后端 APNs provider**:`/app/mobius/backend/services/extension-push.ts` 的 `notify_user` 目前只有 JPush / 华为分支,**需新增 APNs 分支**:
   - 用 `.p8`(Key ID + Team ID)签 JWT,经 `https://api.push.apple.com/3/device/<deviceToken>` 下发。
   - payload 顶层带 `deepLink`(与 JPush extras 一致):`{"aps":{"alert":{...},"sound":"default"}, "deepLink":"momo://chat/<id>"}`。
   - 1v1/群推送钩子(`routes/sessions.ts:252`、`routes/conversations.ts:437`)已构造 deepLink,APNs provider 复用即可。
4. **设备令牌上报**:iOS 拿到 APNs token 后上报后端 `register_device(platform="apns", token=...)`(Swift `AppDelegate.didRegisterForRemoteNotificationsWithDeviceToken` 里的 TODO)。

## 编译验证

iOS 目标在本 Linux 环境无法编译(被禁用)。上述 Swift/Kotlin 改动需在 **Mac + Xcode** 构建验证。

## deepLink 约定

与 Android 一致:
- 1v1: `momo://chat/<sessionId>`
- 群: `momo://group/<conversationId>`
