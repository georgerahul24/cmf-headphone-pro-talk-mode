import Foundation
import UserNotifications

/// Battery forecast from the headphone history. Same as Android:
/// 1. Drain per mode: every battery drop while connected is matched to the noise mode in use, giving
///    each mode its own %-per-hour rate; CMF's figures fill in until there's enough history
///    (about 2 %/h with noise control on, 1 %/h off).
/// 2. Your real use: average hours per day in each mode over the last 14 days, counting days you
///    didn't use them at all.
/// 3. Daily drain = hours in each mode x that mode's rate. Days until 10% gives the next charge day.
enum BatteryPredict {
    struct Forecast { let hoursLeft: Double; let daysToCharge: Double?; let hoursPerDay: Double; let learned: Bool }
    private static let spec: [ModeGroup: Double] = [.anc: 2, .transparency: 2, .off: 1]

    static func forecast(level: Int, anc: UInt8?, sources: [[History.Event]], now: Date = Date()) -> Forecast {
        let hv = HistoryView(sources, now: now)
        func modeAt(_ t: Date) -> ModeGroup? { hv.spans.first { t >= $0.start && t < $0.end }?.mode }

        // The headphones report battery in 5% steps, so time is measured from one step down to the
        // next within the same connection (the first reading after connecting can sit anywhere
        // inside a step).
        var drop: [ModeGroup: Double] = [:], time: [ModeGroup: Double] = [:]
        for events in sources {
            var level: Int?
            var step: History.Event?   // the last step down
            for e in events {
                switch e.kind {
                case "C": level = nil; step = nil
                case "B":
                    if let l = level, e.v < l {
                        if let s = step, e.t > now.addingTimeInterval(-30 * 86400) {
                            let dt = e.t.timeIntervalSince(s.t)
                            if dt > 0, dt <= 8 * 3600, let m = modeAt(s.t.addingTimeInterval(dt / 2)) {
                                drop[m, default: 0] += Double(s.v - e.v); time[m, default: 0] += dt / 3600
                            }
                        }
                        step = e
                    } else if let l = level, e.v > l { step = nil } // charging
                    level = e.v
                default: break
                }
            }
        }
        // Blend with CMF's figures (worth 6 hours of history), so a few readings can't swing it.
        let prior = 6.0
        var learned = false
        var rate: [ModeGroup: Double] = [:]
        for m in ModeGroup.allCases {
            let d = drop[m] ?? 0, h = time[m] ?? 0, base = spec[m] ?? 2
            rate[m] = (d + base * prior) / (h + prior)
            if h >= 2 { learned = true }
        }

        let first = sources.compactMap { $0.first?.t }.min() ?? now
        let from = max(first, now.addingTimeInterval(-14 * 86400))
        let days = max(1, now.timeIntervalSince(from) / 86400)
        var hours: [ModeGroup: Double] = [:]
        for sp in hv.spans {
            let a = max(sp.start, now.addingTimeInterval(-14 * 86400)), b = min(sp.end, now)
            if b > a { hours[sp.mode, default: 0] += b.timeIntervalSince(a) / 3600 }
        }
        let perDay = ModeGroup.allCases.reduce(0) { $0 + (hours[$1] ?? 0) / days }
        let dailyDrain = ModeGroup.allCases.reduce(0) { $0 + (hours[$1] ?? 0) / days * (rate[$1] ?? 2) }
        let current = anc.map { ModeGroup(Int($0)) } ?? .anc
        let left = Double(level) / (rate[current] ?? 2)
        let toCharge: Double? = dailyDrain < 0.2 ? nil : Double(max(0, level - 10)) / dailyDrain
        return Forecast(hoursLeft: left, daysToCharge: toCharge, hoursPerDay: perDay, learned: learned)
    }

    static func hoursText(_ h: Double) -> String {
        if h < 1 { return "Less than an hour of listening left" }
        if h < 10 { return String(format: "About %.1f h of listening left", h).replacingOccurrences(of: ".0 h", with: " h") }
        return "About \(Int(h)) h of listening left"
    }

    /// "Charge today", "Next charge tomorrow", "Next charge around Thu 14 Nov (in ~6 days)".
    static func chargeText(_ days: Double?, now: Date = Date()) -> String? {
        guard let days else { return nil }
        if days < 0.5 { return "Charge today" }
        let d = Int(days.rounded())
        if d <= 1 { return "Next charge tomorrow" }
        let date = now.addingTimeInterval(Double(d) * 86400).formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated))
        return "Next charge around \(date) (in ~\(d) days)"
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
