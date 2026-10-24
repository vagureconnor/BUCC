import ComposeApp
import SwiftUI
import UIKit
import UserNotifications

final class BuccNotificationAppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        if let destination = response.notification.request.content.userInfo["buccDestination"] as? String {
            NotificationRouteInbox.shared.request(destination: destination)
        }
        completionHandler()
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound])
    }
}

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(BuccNotificationAppDelegate.self) private var notificationDelegate

    init() {
        HomeworkBackgroundRefreshScheduler.register()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
