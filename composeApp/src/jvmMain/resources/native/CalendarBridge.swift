import AppKit
import CryptoKit
import EventKit
import Foundation

public typealias BuccCalendarCompletion = @convention(c) (UnsafeMutableRawPointer?, Int32, Int32) -> Void

private final class BuccCalendarImporter {
    static let shared = BuccCalendarImporter()
    private let store = EKEventStore()
    private let queue = DispatchQueue(label: "app.bucc.calendar-import")

    private struct CourseEvent: Decodable {
        let id: String
        let title: String
        let location: String
        let startMillis: Int64
        let endMillis: Int64
    }

    private func digest(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    func importEvents(account: String, term: String, json: String, reminder: Int32,
                      completion: @escaping BuccCalendarCompletion, context: UnsafeMutableRawPointer?) {
        guard !account.isEmpty, !term.isEmpty,
              let data = json.data(using: .utf8),
              let events = try? JSONDecoder().decode([CourseEvent].self, from: data),
              events.allSatisfy({ !$0.id.isEmpty && $0.endMillis > $0.startMillis }) else {
            completion(context, 0, 3)
            return
        }
        if events.isEmpty { completion(context, 0, 0); return }
        let authorized: (Bool, Error?) -> Void = { [self] granted, _ in
            guard granted else { completion(context, 0, 1); return }
            queue.async {
                do {
                    let count = try self.save(events, account: account, term: term, reminder: reminder)
                    completion(context, Int32(count), 0)
                } catch {
                    self.store.reset()
                    completion(context, 0, 3)
                }
            }
        }
        DispatchQueue.main.async {
            if #available(macOS 14.0, *) {
                self.store.requestFullAccessToEvents(completion: authorized)
            } else {
                self.store.requestAccess(to: .event, completion: authorized)
            }
        }
    }

    private func save(_ events: [CourseEvent], account: String, term: String, reminder: Int32) throws -> Int {
        let key = "bucc.calendar.\(digest(account + "/" + term))"
        let calendar: EKCalendar
        if let id = UserDefaults.standard.string(forKey: key),
           let existing = store.calendar(withIdentifier: id), existing.allowsContentModifications {
            calendar = existing
        } else {
            calendar = EKCalendar(for: .event, eventStore: store)
            calendar.title = "BUCC · \(term)"
            calendar.cgColor = NSColor.systemBlue.cgColor
            guard let source = store.defaultCalendarForNewEvents?.source
                    ?? store.sources.first(where: { $0.sourceType == .local })
                    ?? store.sources.first(where: { $0.sourceType == .calDAV }) else {
                throw NSError(domain: "BUCC.Calendar", code: 2)
            }
            calendar.source = source
            try store.saveCalendar(calendar, commit: true)
            UserDefaults.standard.set(calendar.calendarIdentifier, forKey: key)
        }
        let start = Date(timeIntervalSince1970: Double(events.map(\.startMillis).min()!) / 1000)
        let end = Date(timeIntervalSince1970: Double(events.map(\.endMillis).max()!) / 1000)
        let predicate = store.predicateForEvents(withStart: start, end: end, calendars: [calendar])
        var existing: [String: EKEvent] = [:]
        for event in store.events(matching: predicate) {
            if let url = event.url?.absoluteString, url.hasPrefix("bucc://course/") { existing[url] = event }
        }
        for course in events {
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
            if reminder >= 0 { event.addAlarm(EKAlarm(relativeOffset: -Double(reminder) * 60)) }
            try store.save(event, span: .thisEvent, commit: false)
            existing[url] = event
        }
        try store.commit()
        return Set(events.map(\.id)).count
    }
}

@_cdecl("bucc_calendar_import")
public func buccCalendarImport(_ account: UnsafePointer<CChar>, _ term: UnsafePointer<CChar>,
                               _ json: UnsafePointer<CChar>, _ reminder: Int32,
                               _ completion: @escaping BuccCalendarCompletion,
                               _ context: UnsafeMutableRawPointer?) {
    BuccCalendarImporter.shared.importEvents(account: String(cString: account), term: String(cString: term),
                                             json: String(cString: json), reminder: reminder,
                                             completion: completion, context: context)
}
