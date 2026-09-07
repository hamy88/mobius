import SwiftUI
import UserNotifications
import MomoShared

@main
struct MomoMobileApp: App {
    // SwiftUI App 生命周期下, 用 UIApplicationDelegateAdaptor 接 APNs 注册与通知点击回调。
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            ComposeRoot()
                .ignoresSafeArea()
        }
    }
}

class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            guard granted else { return }
            DispatchQueue.main.async { application.registerForRemoteNotifications() }
        }
        // Kotlin 侧本地通知桥: NotificationGateway.show() 发 "momo.showNotification" 广播,
        // 这里转成 UNNotification(权限/前台横幅/点击 deepLink 已由本类其余回调覆盖)。
        NotificationCenter.default.addObserver(
            forName: NSNotification.Name("momo.showNotification"),
            object: nil,
            queue: .main
        ) { note in
            guard let info = note.userInfo as? [String: String] else { return }
            let content = UNMutableNotificationContent()
            content.title = info["title"] ?? "Mobius"
            content.body = info["body"] ?? ""
            content.sound = .default
            if let link = info["deepLink"], !link.isEmpty {
                content.userInfo = ["deepLink": link]
            }
            let request = UNNotificationRequest(
                identifier: "momo-\(Date().timeIntervalSince1970)",
                content: content,
                trigger: nil
            )
            UNUserNotificationCenter.current().add(request)
        }
        // 冷启动由通知点开: launchOptions 里直接取 deepLink。
        if let payload = launchOptions?[.remoteNotification] as? [String: Any],
           let link = payload["deepLink"] as? String, !link.isEmpty {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                MainViewControllerKt.handleIosNotificationDeepLink(deepLink: link)
            }
        }
        return true
    }

    // 收到 APNs 设备令牌 → 上报后端(后端需支持 platform=apns 的下发, 见 docs/IOS-PUSH-DEEPLINK.md)。
    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        // TODO: 登录后上报后端 register_device(platform="apns", token=token)。
        _ = deviceToken.map { String(format: "%02x", $0) }.joined()
    }

    // 点击通知(前台/后台/冷启动)→ 进对应聊天。
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let userInfo = response.notification.request.content.userInfo
        if let link = userInfo["deepLink"] as? String, !link.isEmpty {
            MainViewControllerKt.handleIosNotificationDeepLink(deepLink: link)
        }
        completionHandler()
    }

    // 前台收到通知: 显示横幅, 让用户能点。
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound, .badge])
    }
}

struct ComposeRoot: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
