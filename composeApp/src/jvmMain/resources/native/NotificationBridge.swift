import Foundation
import CryptoKit
import UserNotifications

public typealias BuccNotificationsCompletion = @convention(c) (UnsafeMutableRawPointer?, Int32) -> Void
public typealias BuccNotificationsOpenCallback = @convention(c) (UnsafePointer<CChar>?, UnsafeMutableRawPointer?) -> Void

private final class NativeBuccNotificationManager: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NativeBuccNotificationManager()
    private let center = UNUserNotificationCenter.current()
    private let prefix = "bucc.notifications."
    private let callbackLock = NSLock()
    private var generation: UInt64 = 0
    private var openCallback: BuccNotificationsOpenCallback?
    private var openContext: UnsafeMutableRawPointer?
    private var pendingDestination: String?

    private override init() {
        super.init()
        center.delegate = self
    }

    func installOpenCallback(_ callback: BuccNotificationsOpenCallback?, context: UnsafeMutableRawPointer?) {
        callbackLock.lock()
        openCallback = callback
        openContext = context
        let pending = pendingDestination
        pendingDestination = nil
        callbackLock.unlock()
        if let pending { routeToApp(pending) }
    }

    private func beginGeneration() -> UInt64 {
        callbackLock.lock()
        generation &+= 1
        let value = generation
        callbackLock.unlock()
        return value
    }

    private func isCurrent(_ value: UInt64) -> Bool {
        callbackLock.lock()
        let current = generation == value
        callbackLock.unlock()
        return current
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        if let destination = response.notification.request.content.userInfo["buccDestination"] as? String {
            routeToApp(destination)
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

    private func routeToApp(_ destination: String) {
        guard destination == "todo" || destination == "schedule" else { return }
        callbackLock.lock()
        guard let callback = openCallback else {
            pendingDestination = destination
            callbackLock.unlock()
            return
        }
        let context = openContext
        callbackLock.unlock()
        destination.withCString { callback($0, context) }
    }

    func schedule(coursePlanJSON: String, dailyEnabled: Bool, completion: @escaping (Int32) -> Void) {
        let scheduledGeneration = beginGeneration()
        center.getPendingNotificationRequests { [weak self] pending in
            guard let self else { completion(3); return }
            guard self.isCurrent(scheduledGeneration) else { completion(4); return }
            let ownIds = pending.map(\.identifier).filter { $0.hasPrefix(self.prefix) }
            self.center.removePendingNotificationRequests(withIdentifiers: ownIds)
            self.center.requestAuthorization(options: [.alert, .sound]) { granted, error in
                guard self.isCurrent(scheduledGeneration) else { completion(4); return }
                guard granted, error == nil else { completion(2); return }
                self.center.getPendingNotificationRequests { remaining in
                    guard self.isCurrent(scheduledGeneration) else { completion(4); return }
                    let externalPendingCount = remaining.filter { !$0.identifier.hasPrefix(self.prefix) }.count
                    let available = max(0, 64 - externalPendingCount)
                    let reserveDaily = dailyEnabled && available > 0 ? 1 : 0
                    var requests: [UNNotificationRequest] = []
                    if reserveDaily == 1 {
                        let content = UNMutableNotificationContent()
                        content.title = "待办提醒"
                        content.body = "查看今天的待办事项"
                        content.sound = .default
                        content.userInfo["buccDestination"] = "todo"
                        var date = DateComponents()
                        date.timeZone = TimeZone(identifier: "Asia/Shanghai")
                        date.hour = 10
                        date.minute = 0
                        requests.append(UNNotificationRequest(
                            identifier: self.prefix + "daily-todo.\(scheduledGeneration)",
                            content: content,
                            trigger: UNCalendarNotificationTrigger(dateMatching: date, repeats: true)
                        ))
                    }
                    guard let data = coursePlanJSON.data(using: .utf8),
                          let plans = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
                        completion(3)
                        return
                    }
                    let capacity = max(0, available - reserveDaily)
                    let dropped = dailyEnabled && reserveDaily == 0 || plans.count > capacity
                    for item in plans.prefix(capacity) {
                        guard let eventId = item["eventId"] as? String,
                              let triggerAt = item["triggerAtMillis"] as? NSNumber else { continue }
                        let delay = triggerAt.doubleValue / 1000 - Date().timeIntervalSince1970
                        guard delay > 1 else { continue }
                        let content = UNMutableNotificationContent()
                        content.title = "上课提醒"
                        content.body = "还有 10 分钟上课，记得打卡"
                        content.sound = .default
                        content.userInfo["buccDestination"] = "schedule"
                        let hashedId = SHA256.hash(data: Data(eventId.utf8))
                            .map { String(format: "%02x", $0) }
                            .joined()
                        requests.append(UNNotificationRequest(
                            identifier: self.prefix + "course.\(scheduledGeneration)." + hashedId,
                            content: content,
                            trigger: UNTimeIntervalNotificationTrigger(timeInterval: delay, repeats: false)
                        ))
                    }
                    guard self.isCurrent(scheduledGeneration) else { completion(4); return }
                    let group = DispatchGroup()
                    let failedLock = NSLock()
                    var failed = false
                    requests.forEach { request in
                        group.enter()
                        self.center.add(request) { error in
                            guard self.isCurrent(scheduledGeneration) else {
                                group.leave()
                                return
                            }
                            if error != nil {
                                failedLock.lock()
                                failed = true
                                failedLock.unlock()
                            }
                            group.leave()
                        }
                    }
                    group.notify(queue: .main) {
                        guard self.isCurrent(scheduledGeneration) else {
                            self.center.removePendingNotificationRequests(withIdentifiers: requests.map(\.identifier))
                            completion(4)
                            return
                        }
                        failedLock.lock()
                        let didFail = failed
                        failedLock.unlock()
                        if didFail { completion(3) }
                        else if dropped { completion(1) }
                        else { completion(0) }
                    }
                }
            }
        }
    }

    func cancel(completion: @escaping () -> Void) {
        let cancelledGeneration = beginGeneration()
        center.getPendingNotificationRequests { [weak self] pending in
            guard let self else { completion(); return }
            guard self.isCurrent(cancelledGeneration) else { completion(); return }
            let ownIds = pending.map(\.identifier).filter { $0.hasPrefix(self.prefix) }
            self.center.removePendingNotificationRequests(withIdentifiers: ownIds)
            completion()
        }
    }

    func permissionStatus(completion: @escaping (Int32) -> Void) {
        center.getNotificationSettings { settings in
            switch settings.authorizationStatus {
            case .authorized, .provisional, .ephemeral:
                completion(0)
            case .notDetermined:
                completion(1)
            case .denied:
                completion(2)
            @unknown default:
                completion(3)
            }
        }
    }

    func requestAuthorization(completion: @escaping (Int32) -> Void) {
        center.requestAuthorization(options: [.alert, .sound]) { granted, error in
            completion(granted && error == nil ? 0 : 2)
        }
    }
}

@_cdecl("bucc_notifications_schedule")
public func bucc_notifications_schedule(
    _ coursePlanJSON: UnsafePointer<CChar>?,
    _ dailyTodoEnabled: Int32,
    _ completion: BuccNotificationsCompletion?,
    _ context: UnsafeMutableRawPointer?
) {
    guard let completion else { return }
    guard let coursePlanJSON else { completion(context, 3); return }
    let json = String(cString: coursePlanJSON)
    NativeBuccNotificationManager.shared.schedule(coursePlanJSON: json, dailyEnabled: dailyTodoEnabled != 0) { status in
        completion(context, status)
    }
}

@_cdecl("bucc_notifications_cancel")
public func bucc_notifications_cancel(
    _ completion: BuccNotificationsCompletion?,
    _ context: UnsafeMutableRawPointer?
) {
    guard let completion else { return }
    NativeBuccNotificationManager.shared.cancel { completion(context, 0) }
}

@_cdecl("bucc_notifications_permission_status")
public func bucc_notifications_permission_status(
    _ completion: BuccNotificationsCompletion?,
    _ context: UnsafeMutableRawPointer?
) {
    guard let completion else { return }
    NativeBuccNotificationManager.shared.permissionStatus { status in completion(context, status) }
}

@_cdecl("bucc_notifications_request_authorization")
public func bucc_notifications_request_authorization(
    _ completion: BuccNotificationsCompletion?,
    _ context: UnsafeMutableRawPointer?
) {
    guard let completion else { return }
    NativeBuccNotificationManager.shared.requestAuthorization { status in completion(context, status) }
}

@_cdecl("bucc_notifications_install_open_callback")
public func bucc_notifications_install_open_callback(
    _ callback: BuccNotificationsOpenCallback?,
    _ context: UnsafeMutableRawPointer?
) {
    NativeBuccNotificationManager.shared.installOpenCallback(callback, context: context)
}
