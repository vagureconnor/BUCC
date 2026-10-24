import ComposeApp
import CryptoKit
import Foundation
import UserNotifications

final class NativeBuccNotificationPlatform: NSObject, BuccNotificationPlatform {
    private let center = UNUserNotificationCenter.current()
    private let ownPrefix = "bucc.notifications."
    private let generationLock = NSLock()
    private var generation: UInt64 = 0

    private func beginGeneration() -> UInt64 {
        generationLock.lock()
        generation &+= 1
        let value = generation
        generationLock.unlock()
        return value
    }

    private func isCurrent(_ value: UInt64) -> Bool {
        generationLock.lock()
        let current = generation == value
        generationLock.unlock()
        return current
    }

    func schedule(
        coursePlan: [CourseReminderPlanItem],
        dailyTodoEnabled: Bool,
        completion: @escaping (String) -> Void
    ) {
        let scheduledGeneration = beginGeneration()
        center.getPendingNotificationRequests { [weak self] pending in
            guard let self else { completion("unavailable"); return }
            guard self.isCurrent(scheduledGeneration) else { completion("cancelled"); return }
            let oldOwnIds = pending.map(\.identifier).filter {
                $0.hasPrefix(self.ownPrefix) || $0.hasPrefix("bucc.course.")
            }
            self.center.removePendingNotificationRequests(withIdentifiers: oldOwnIds)
            self.center.requestAuthorization(options: [.alert, .sound]) { granted, error in
                guard self.isCurrent(scheduledGeneration) else { completion("cancelled"); return }
                guard granted, error == nil else {
                    completion("permission_required")
                    return
                }
                self.center.getPendingNotificationRequests { remaining in
                    guard self.isCurrent(scheduledGeneration) else { completion("cancelled"); return }
                    let externalPendingCount = remaining.filter { !$0.identifier.hasPrefix(self.ownPrefix) }.count
                    let available = max(0, 64 - externalPendingCount)
                    let dailySlots = dailyTodoEnabled && available > 0 ? 1 : 0
                    var requests: [UNNotificationRequest] = []
                    if dailySlots == 1 {
                        let content = UNMutableNotificationContent()
                        content.title = "待办提醒"
                        content.body = "查看今天的待办事项"
                        content.sound = .default
                        content.userInfo["buccDestination"] = "todo"
                        var components = DateComponents()
                        components.timeZone = TimeZone(identifier: "Asia/Shanghai")
                        components.hour = 10
                        components.minute = 0
                        requests.append(UNNotificationRequest(
                            identifier: self.ownPrefix + "daily-todo.\(scheduledGeneration)",
                            content: content,
                            trigger: UNCalendarNotificationTrigger(dateMatching: components, repeats: true)
                        ))
                    }
                    let courseCapacity = max(0, available - dailySlots)
                    let capacityInsufficient = (dailyTodoEnabled && dailySlots == 0) || coursePlan.count > courseCapacity
                    for item in coursePlan.prefix(courseCapacity) {
                        let delay = Double(item.triggerAtMillis) / 1000 - Date().timeIntervalSince1970
                        guard delay > 1 else { continue }
                        let content = UNMutableNotificationContent()
                        content.title = "上课提醒"
                        content.body = "还有 10 分钟上课，记得打卡"
                        content.sound = .default
                        content.userInfo["buccDestination"] = "schedule"
                        let hashedId = SHA256.hash(data: Data(item.eventId.utf8))
                            .map { String(format: "%02x", $0) }
                            .joined()
                        requests.append(UNNotificationRequest(
                            identifier: self.ownPrefix + "course.\(scheduledGeneration)." + hashedId,
                            content: content,
                            trigger: UNTimeIntervalNotificationTrigger(timeInterval: delay, repeats: false)
                        ))
                    }
                    guard self.isCurrent(scheduledGeneration) else { completion("cancelled"); return }
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
                            completion("cancelled")
                            return
                        }
                        failedLock.lock()
                        let didFail = failed
                        failedLock.unlock()
                        completion(didFail ? "partial_failure" : (capacityInsufficient ? "partial_capacity" : "scheduled"))
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
            let ids = pending.map(\.identifier).filter {
                $0.hasPrefix(self.ownPrefix) || $0.hasPrefix("bucc.course.")
            }
            self.center.removePendingNotificationRequests(withIdentifiers: ids)
            completion()
        }
    }
}

enum BuccNotificationPlatformInstaller {
    static func install() {
        NotificationSchedulerBridge.shared.install(platform: NativeBuccNotificationPlatform())
    }
}
