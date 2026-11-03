import Foundation

/// A log of what the headphones did, kept on this Mac for good (never deleted):
/// connected / disconnected, noise mode changes and battery level changes.
/// One line per event: "time,kind,value" (time in ms; kind C = connected 1/0, A = noise mode, B = battery).
final class History {
    struct Event { let t: Date; let kind: Character; let v: Int }

    static let shared = History()
    let url: URL
    private var last: [Character: Int] = [:]
    private let io = DispatchQueue(label: "history")
    private(set) var version = 0

    private init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("CMF Talk Mode", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        url = dir.appendingPathComponent("history.log")
        // Close a connection left open by a crash, at the last thing it recorded.
        let events = load()
        if let c = events.last(where: { $0.kind == "C" }), c.v == 1, let end = events.last?.t,
           let h = try? FileHandle(forWritingTo: url) {
            h.seekToEndOfFile(); h.write(Self.line(Event(t: end, kind: "C", v: 0)).data(using: .utf8)!); try? h.close()
        }
    }

    private static func line(_ e: Event) -> String {
        "\(Int64(e.t.timeIntervalSince1970 * 1000)),\(e.kind),\(e.v)\n"
    }

    /// Records a value if it changed. Main thread.
    func log(_ kind: Character, _ v: Int) {
        if kind != "C" && last[kind] == v { return }
        last[kind] = v
        if kind == "C" && v == 0 { last.removeAll() }
        let s = Self.line(Event(t: Date(), kind: kind, v: v))
        let url = url
        io.async {
            if let h = try? FileHandle(forWritingTo: url) {
                h.seekToEndOfFile(); h.write(s.data(using: .utf8)!); try? h.close()
            } else {
                try? s.write(to: url, atomically: true, encoding: .utf8)
            }
        }
        version += 1
    }

    func load() -> [Event] {
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        return text.split(separator: "\n").compactMap { l in
            let p = l.split(separator: ",")
            guard p.count == 3, let t = Int64(p[0]), let k = p[1].first, let v = Int(p[2]) else { return nil }
            return Event(t: Date(timeIntervalSince1970: Double(t) / 1000), kind: k, v: v)
        }
    }
}

enum ModeGroup: String, CaseIterable, Identifiable {
    case anc = "Noise cancelling", transparency = "Transparency", off = "Off"
    var id: String { rawValue }

    init(_ m: Int) {
        switch UInt8(truncatingIfNeeded: m) {
        case Proto.ancTransparency: self = .transparency
        case Proto.ancOff: self = .off
        default: self = .anc
        }
    }
}

/// Everything the charts need for the last 7 days.
struct HistoryView {
    struct Span: Identifiable { let id = UUID(); let start: Date; let end: Date; let mode: ModeGroup }

    let days: [Date]        // midnight of each of the last 7 days, oldest first
    let usage: [TimeInterval]
    let spans: [Span]
    let battery: [(Date, Int)]

    init(_ events: [History.Event], now: Date = Date()) {
        let cal = Calendar.current
        let today = cal.startOfDay(for: now)
        days = (0..<7).reversed().map { cal.date(byAdding: .day, value: -$0, to: today)! }

        var spans: [Span] = [], conn: [(Date, Date)] = [], bat: [(Date, Int)] = []
        var connStart: Date?, mode: Int?, modeStart = now
        func closeMode(_ t: Date) {
            if let m = mode, connStart != nil, t > modeStart { spans.append(Span(start: modeStart, end: t, mode: ModeGroup(m))) }
        }
        for e in events {
            switch e.kind {
            case "C":
                if e.v == 1 { if connStart == nil { connStart = e.t } }
                else {
                    closeMode(e.t); mode = nil
                    if let s = connStart { conn.append((s, e.t)) }
                    connStart = nil
                }
            case "A": closeMode(e.t); mode = e.v; modeStart = e.t
            case "B": bat.append((e.t, e.v))
            default: break
            }
        }
        closeMode(now)
        if let s = connStart { conn.append((s, now)) }
        self.spans = spans
        battery = bat
        let d = days
        usage = d.indices.map { i in
            let a = d[i], b = cal.date(byAdding: .day, value: 1, to: a)!
            return conn.reduce(0) { $0 + max(0, min($1.1, b).timeIntervalSince(max($1.0, a))) }
        }
    }

    func dayEnd(_ i: Int) -> Date { Calendar.current.date(byAdding: .day, value: 1, to: days[i])! }

    func spansOn(_ i: Int) -> [Span] {
        let a = days[i], b = dayEnd(i)
        return spans.filter { $0.end > a && $0.start < b }
            .map { Span(start: max($0.start, a), end: min($0.end, b), mode: $0.mode) }
    }

    func totals(_ i: Int) -> [ModeGroup: TimeInterval] {
        spansOn(i).reduce(into: [:]) { $0[$1.mode, default: 0] += $1.end.timeIntervalSince($1.start) }
    }

    func batteryOn(_ i: Int) -> [(Date, Int)] {
        let a = days[i], b = dayEnd(i)
        let before = battery.last { $0.0 < a }.map { (a, $0.1) }
        return (before.map { [$0] } ?? []) + battery.filter { $0.0 >= a && $0.0 < b }
    }
}
