import Foundation
import UserNotifications

/// Battery time left, learned from the headphone history: how fast the battery dropped while
/// connected over the last 7 days (charging is left out). With too little history it uses CMF's
/// figures: about 50 h with noise control on, about 100 h with it off. Same as Android.
enum BatteryPredict {
    static func drainPerHour(_ sources: [[History.Event]], now: Date = Date()) -> Double? {
        var drop = 0.0, time = 0.0
        let weekAgo = now.addingTimeInterval(-7 * 86400)
        for events in sources {
            var connected = false
            var last: History.Event?
            for e in events {
                switch e.kind {
                case "C": connected = e.v == 1; last = nil
                case "B":
                    if connected, let l = last, e.t > weekAgo {
                        let d = Double(l.v - e.v), dt = e.t.timeIntervalSince(l.t)
                        if d > 0, dt > 0, dt <= 6 * 3600 { drop += d; time += dt }
                    }
                    if connected { last = e }
                default: break
                }
            }
        }
        guard drop >= 5, time >= 1800 else { return nil }
        return drop / (time / 3600)
    }

    static func hoursLeft(level: Int, anc: UInt8?, sources: [[History.Event]]) -> (hours: Double, learned: Bool) {
        if let r = drainPerHour(sources) { return (Double(level) / r, true) }
        return (Double(level) / 100 * (anc == Proto.ancOff ? 100 : 50), false)
    }

    static func text(_ h: Double) -> String {
        if h < 1 { return "Less than an hour left" }
        if h < 10 { return String(format: "About %.1f h left", h).replacingOccurrences(of: ".0 h", with: " h") }
        return "About \(Int(h)) h left"
    }
}

/// Hearing safety, following the WHO guideline: 80 dB for 40 hours a week is safe, and every 3 dB
/// louder halves the safe time. The headphones can't measure loudness, so it's estimated from the
/// volume: about 100 dB at full volume, 50 dB less at the bottom. A guide, not a measurement.
/// Kept per day for the last 7 days. Same as Android.
final class Hearing {
    static let shared = Hearing()
    private let d = UserDefaults.standard
    private let fmt: DateFormatter = { let f = DateFormatter(); f.dateFormat = "yyyyMMdd"; f.locale = Locale(identifier: "en_US_POSIX"); return f }()
    private var loudSince: Date?
    private var lastLoudAlert = Date.distantPast

    private var days: [String: [String: Double]] {
        get { d.dictionary(forKey: "hearing") as? [String: [String: Double]] ?? [:] }
        set { d.set(newValue, forKey: "hearing") }
    }

    static func db(_ volume: Double) -> Double { volume <= 0 ? 0 : 100 - 50 * (1 - min(1, max(0, volume))) }

    private func lastDays(_ now: Date) -> [String] { (0..<7).map { fmt.string(from: now.addingTimeInterval(Double(-$0) * 86400)) } }

    /// Some listening happened. Returns an alert to show, if any.
    func add(volume: Double, seconds: Double, now: Date = Date()) -> String? {
        guard seconds > 0, volume > 0 else { loudSince = nil; return nil }
        let level = Self.db(volume)
        let key = fmt.string(from: now)
        var all = days
        var today = all[key] ?? [:]
        today["dose", default: 0] += seconds * pow(2, (level - 80) / 3) / (40 * 3600)
        today["secs", default: 0] += seconds
        today["dbs", default: 0] += level * seconds
        all[key] = today
        let keep = Set(lastDays(now))
        days = all.filter { keep.contains($0.key) }
        if level >= 85 { if loudSince == nil { loudSince = now } } else { loudSince = nil }
        if let s = loudSince, now.timeIntervalSince(s) > 1800, now.timeIntervalSince(lastLoudAlert) > 3600 {
            lastLoudAlert = now
            return "You've been listening loud for 30 minutes. Turning it down a little protects your hearing."
        }
        if week(now) >= 1, d.string(forKey: "hearingWeekAlert") != key {
            d.set(key, forKey: "hearingWeekAlert")
            return "You've used this week's safe listening amount. Lower volume or a break helps your ears recover."
        }
        return nil
    }

    /// This Mac's share of the weekly safe amount (1.0 = 100%).
    func week(_ now: Date = Date()) -> Double {
        let all = days
        return lastDays(now).reduce(0) { $0 + (all[$1]?["dose"] ?? 0) }
    }

    func today(_ now: Date = Date()) -> (secs: Double, db: Double) {
        let t = days[fmt.string(from: now)] ?? [:]
        let s = t["secs"] ?? 0
        return (s, s > 0 ? (t["dbs"] ?? 0) / s : 0)
    }
}

/// Mac notifications for the reminders.
enum Notify {
    /// Notifications need a real app bundle (not a bare tool, as in tests).
    private static var available: Bool { Bundle.main.bundleIdentifier != nil && Bundle.main.bundleURL.pathExtension == "app" }

    static func ask() {
        guard available else { return }
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    static func show(_ id: String, _ title: String, _ body: String) {
        guard available else { return }
        let c = UNMutableNotificationContent()
        c.title = title; c.body = body; c.sound = .default
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: c, trigger: nil))
    }
}
