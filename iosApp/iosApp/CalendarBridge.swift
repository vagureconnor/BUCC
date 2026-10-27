import ComposeApp
import CryptoKit
import EventKit
import Foundation
import UIKit

final class CourseCalendarBridge: NSObject, CalendarNativePlatform {
    private let store = EKEventStore()
    private let queue = DispatchQueue(label: "app.bucc.calendar-import")

    private struct CourseEvent: Decodable {
        let id: String
        let title: String
        let location: String
        let startMillis: Int64
        let endMillis: Int64
    }

    func importEvents(accountId: String, termName: String, eventsJson: String,
                      reminderMinutes: Int32, callback: CalendarImportCallback) {
        let completion: (Bool, Error?) -> Void = { [self] granted, error in
            guard granted else {
                callback.onFailure(message: "未获得日历权限，请在系统设置中允许 BUCC 访问日历")
                return
            }
            queue.async {
                do {
                    let events = try JSONDecoder().decode([CourseEvent].self, from: Data(eventsJson.utf8))
                    let count = try self.save(events, accountId: accountId, termName: termName,
                                              reminderMinutes: reminderMinutes)
                    let namespace = "bucc.course.\(self.digest(accountId + "/" + termName))/"
                    CourseReminderScheduler.schedule(
                        events: events.map { CourseReminderEvent(id: $0.id, title: $0.title,
                                                                 startMillis: $0.startMillis) },
                        namespace: namespace,
                        leadMinutes: reminderMinutes
                    ) { status in
                        callback.onSuccess(count: Int32(count), notificationStatus: status)
                    }
                } catch {
                    self.store.reset()
                    callback.onFailure(message: "日历导入失败：\(error.localizedDescription)")
                }
            }
        }
        if #available(iOS 17.0, *) {
            store.requestFullAccessToEvents(completion: completion)
        } else {
            store.requestAccess(to: .event, completion: completion)
        }
    }

    private func digest(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    private func save(_ courses: [CourseEvent], accountId: String, termName: String,
                      reminderMinutes: Int32) throws -> Int {
        let key = "bucc.calendar.\(digest(accountId + "/" + termName))"
        if courses.isEmpty && UserDefaults.standard.string(forKey: key) == nil { return 0 }
        let calendar: EKCalendar
        if let id = UserDefaults.standard.string(forKey: key),
           let existing = store.calendar(withIdentifier: id), existing.allowsContentModifications {
            calendar = existing
        } else {
            calendar = EKCalendar(for: .event, eventStore: store)
            calendar.title = "BUCC · \(termName)"
            calendar.cgColor = UIColor.systemBlue.cgColor
            guard let source = store.defaultCalendarForNewEvents?.source
                    ?? store.sources.first(where: { $0.sourceType == .local })
                    ?? store.sources.first(where: { $0.sourceType == .calDAV }) else {
                throw NSError(domain: "BUCC.Calendar", code: 1,
                              userInfo: [NSLocalizedDescriptionKey: "没有可写入的日历账户，请先在系统日历中配置账户"])
            }
            calendar.source = source
            try store.saveCalendar(calendar, commit: true)
            UserDefaults.standard.set(calendar.calendarIdentifier, forKey: key)
        }
        // Include the previous import range so moved/deleted classes are reconciled too.
        let boundsKey = key + ".range"
        let previous = UserDefaults.standard.array(forKey: boundsKey) as? [Double] ?? []
        let starts = courses.map { Double($0.startMillis) / 1000 }
        let ends = courses.map { Double($0.endMillis) / 1000 }
        let anchor = starts.min() ?? previous.first ?? Date().timeIntervalSince1970
        let year = 366.0 * 24 * 60 * 60
        let lower = min(starts.min() ?? anchor, previous.first ?? anchor - year)
        let upper = max(ends.max() ?? anchor, previous.last ?? anchor + year)
        var managed: [EKEvent] = []
        // EventKit predicates cover at most four years. Query bounded yearly windows.
        var cursor = lower
        var seen = Set<String>()
        while cursor < upper {
            let next = min(cursor + year, upper)
            let predicate = store.predicateForEvents(withStart: Date(timeIntervalSince1970: cursor),
                                                     end: Date(timeIntervalSince1970: next), calendars: [calendar])
            for event in store.events(matching: predicate) {
                if let url = event.url?.absoluteString, url.hasPrefix("bucc://course/"),
                   seen.insert(event.eventIdentifier).inserted { managed.append(event) }
            }
            cursor = next
        }
        let wanted = Set(courses.map { "bucc://course/\(digest($0.id))" })
        var existing: [String: EKEvent] = [:]
        for event in managed {
            let url = event.url!.absoluteString
            if wanted.contains(url) && existing[url] == nil { existing[url] = event }
            else { try store.remove(event, span: .thisEvent, commit: false) }
        }
        for course in courses {
            let url = "bucc://course/\(digest(course.id))"
            let event = existing[url] ?? EKEvent(eventStore: store)
            event.calendar = calendar
            event.title = course.title
            event.location = course.location
            event.startDate = Date(timeIntervalSince1970: Double(course.startMillis) / 1000)
            event.endDate = Date(timeIntervalSince1970: Double(course.endMillis) / 1000)
            event.timeZone = TimeZone(identifier: "Asia/Shanghai")
            event.url = URL(string: url)
            event.notes = "由 BUCC 课表导入"
            for alarm in event.alarms ?? [] { event.removeAlarm(alarm) }
            if reminderMinutes >= 0 {
                event.addAlarm(EKAlarm(relativeOffset: -Double(reminderMinutes) * 60))
            }
            try store.save(event, span: .thisEvent, commit: false)
            existing[url] = event
        }
        try store.commit()
        UserDefaults.standard.set([lower, upper], forKey: boundsKey)
        return courses.count
    }
}
