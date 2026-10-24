import BackgroundTasks
import ComposeApp
import Foundation
import UserNotifications

final class NativeHomeworkNotificationBridge: NSObject, HomeworkNotificationPlatform {
    func showHomeworkNotification(title: String, body: String, identifier: String) {
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            guard granted else { return }
            let content = UNMutableNotificationContent()
            content.title = title
            content.body = body
            content.sound = .default
            center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil))
        }
    }
}

enum HomeworkNotificationBridgeInstaller {
    static func install() {
        HomeworkNotificationBridge.shared.install(platform: NativeHomeworkNotificationBridge())
    }
}

private final class HomeworkRefreshCompletion: NSObject, HomeworkBackgroundTaskCompletion {
    private let task: BGTask
    private let lock = NSLock()
    private var finished = false

    init(task: BGTask) { self.task = task }

    func finish(status: String) {
        lock.lock()
        guard !finished else { lock.unlock(); return }
        finished = true
        lock.unlock()
        task.setTaskCompleted(success: status == "success")
        HomeworkBackgroundRefreshScheduler.scheduleNext()
    }
}

enum HomeworkBackgroundRefreshScheduler {
    private static let identifier = "app.bucc.homework.refresh"

    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
            let completion = HomeworkRefreshCompletion(task: task)
            guard let task = task as? BGAppRefreshTask else {
                completion.finish(status: "failed")
                return
            }
            task.expirationHandler = { completion.finish(status: "expired") }
            HomeworkBackgroundTaskBridge.shared.run(completion: completion)
        }
        scheduleNext()
    }

    fileprivate static func scheduleNext() {
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 60 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }
}
