import CryptoKit
import Foundation
import UserNotifications

struct CourseReminderEvent {
    let id: String
    let title: String
    let startMillis: Int64
}

enum CourseReminderScheduler {
    private static let maximumPendingCount = 64

    static func schedule(events: [CourseReminderEvent], namespace: String,
                         leadMinutes: Int32, completion: @escaping (String) -> Void) {
        let center = UNUserNotificationCenter.current()
        guard leadMinutes >= 0 else {
            center.getPendingNotificationRequests { requests in
                let identifiers = requests.map(\.identifier).filter { $0.hasPrefix(namespace) }
                center.removePendingNotificationRequests(withIdentifiers: identifiers)
                completion("课程系统通知已关闭")
            }
            return
        }

        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            guard granted else {
                completion("未获得通知权限；课程已导入日历，但系统通知未开启。可在系统设置中允许 BUCC 通知")
                return
            }

            center.getPendingNotificationRequests { requests in
                let previous = requests.map(\.identifier).filter { $0.hasPrefix(namespace) }
                let otherPendingCount = requests.count - previous.count
                center.removePendingNotificationRequests(withIdentifiers: previous)

                let now = Date()
                let candidates = events.compactMap { event -> (String, String, Date)? in
                    let start = Date(timeIntervalSince1970: Double(event.startMillis) / 1000)
                    let fireDate = start.addingTimeInterval(-Double(leadMinutes) * 60)
                    guard fireDate > now else { return nil }
                    return (identifier(namespace: namespace, eventID: event.id), event.title, fireDate)
                }.sorted { $0.2 < $1.2 }

                let availableCount = max(0, maximumPendingCount - otherPendingCount)
                let selected = Array(candidates.prefix(availableCount))
                let omittedCount = candidates.count - selected.count
                guard !selected.isEmpty else {
                    completion(omittedCount > 0
                        ? "iOS 通知待处理数量已达上限，未能安排新的课程提醒"
                        : "没有尚未开始的课程可安排系统通知")
                    return
                }

                let group = DispatchGroup()
                let countLock = NSLock()
                var addedCount = 0
                for (requestID, courseTitle, fireDate) in selected {
                    let content = UNMutableNotificationContent()
                    content.title = "课程提醒"
                    content.body = "\(courseTitle)开始打卡啦"
                    content.sound = .default
                    let trigger = UNTimeIntervalNotificationTrigger(
                        timeInterval: max(1, fireDate.timeIntervalSinceNow), repeats: false)
                    group.enter()
                    center.add(UNNotificationRequest(identifier: requestID, content: content, trigger: trigger)) { error in
                        if error == nil {
                            countLock.lock()
                            addedCount += 1
                            countLock.unlock()
                        }
                        group.leave()
                    }
                }
                group.notify(queue: .main) {
                    var message = "已安排 \(addedCount) 条课程系统通知"
                    if omittedCount > 0 {
                        message += "；iOS 同时最多保留 \(maximumPendingCount) 条，优先安排了近期课程，请之后重新导入以补充其余提醒"
                    }
                    completion(message)
                }
            }
        }
    }

    private static func identifier(namespace: String, eventID: String) -> String {
        let digest = SHA256.hash(data: Data(eventID.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
        return "\(namespace)\(digest)"
    }
}
