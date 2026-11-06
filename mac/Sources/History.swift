import Foundation

/// A log of what the headphones did, kept on this Mac for good (never deleted):
/// connected / disconnected, noise mode changes and battery level changes.
/// One line per event: "time,kind,value" (time in ms; kind C = connected 1/0, A = noise mode, B = battery).
///
/// history.log holds this Mac's own events. Events synced from the paired phone go to
/// history-<phone id>.log, line for line, so each side knows exactly how much it has.
final class History {
    struct Event { let t: Date; let kind: Character; let v: Int }

    static let shared = History()
    let url: URL
    private var last: [Character: Int] = [:]
    private let io = DispatchQueue(label: "history")
    private(set) var version = 0
    /// Lines in this Mac's own log (counted when logged, before the write finishes).
    private(set) var ownCount = 0
    /// Called after a new own event, for sync.
    var onLog: (() -> Void)?

    private init() {
        // CMF_TALK_DATA_DIR: a separate folder for tests, so they never touch real history.
        let dir = ProcessInfo.processInfo.environment["CMF_TALK_DATA_DIR"].map { URL(fileURLWithPath: $0, isDirectory: true) }
            ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("CMF Talk Mode", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        url = dir.appendingPathComponent("history.log")
        // Close a connection left open by a crash, at the last thing it recorded.
        let events = load()
        if let c = events.last(where: { $0.kind == "C" }), c.v == 1, let end = events.last?.t,
           let h = try? FileHandle(forWritingTo: url) {
            h.seekToEndOfFile(); h.write(Self.line(Event(t: end, kind: "C", v: 0)).data(using: .utf8)!); try? h.close()
        }
        ownCount = Self.lines(url).count
    }

    private static func lines(_ u: URL) -> [Substring] {
        guard let text = try? String(contentsOf: u, encoding: .utf8) else { return [] }
        return text.split(separator: "\n", omittingEmptySubsequences: true)
    }

    private func peerURL(_ id: String) -> URL {
        url.deletingLastPathComponent().appendingPathComponent("history-\(id.filter { $0.isLetter || $0.isNumber || $0 == "-" }).log")
    }

    /// This Mac's own lines from `index` on, for sending to the phone.
    func ownLines(from index: Int, max n: Int = 2000) -> [String] {
        io.sync {
            let all = Self.lines(url)
            guard index < all.count else { return [] }
            return all[index..<min(all.count, index + n)].map(String.init)
        }
    }

    /// How many of the phone's lines this Mac has.
    func peerCount(_ id: String) -> Int { io.sync { Self.lines(peerURL(id)).count } }

    /// Adds the phone's lines to its file. Main thread.
    func appendPeer(_ id: String, _ lines: [String]) {
        guard !lines.isEmpty else { return }
        let u = peerURL(id)
        let text = lines.map { $0.replacingOccurrences(of: "\n", with: "") + "\n" }.joined()
        io.sync {
            if let h = try? FileHandle(forWritingTo: u) {
                h.seekToEndOfFile(); h.write(text.data(using: .utf8)!); try? h.close()
            } else {
                try? text.write(to: u, atomically: true, encoding: .utf8)
            }
        }
        version += 1
    }

    /// Every history file: this Mac's first, then the phone's.
    func peerFiles() -> [URL] {
        let dir = url.deletingLastPathComponent()
        let names = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
        return names.filter { $0.hasPrefix("history-") && $0.hasSuffix(".log") }.sorted().map { dir.appendingPathComponent($0) }
    }

    func loadAll() -> [[Event]] { [load()] + peerFiles().map { load($0) } }

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
        ownCount += 1
        onLog?()
    }

    func load(_ from: URL? = nil) -> [Event] {
        guard let text = try? String(contentsOf: from ?? url, encoding: .utf8) else { return [] }
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

    /// `sources`: this Mac's events first, then the phone's. When both were connected at the same
    /// time, that time counts once, and this Mac's noise mode record wins.
    init(_ sources: [[History.Event]], now: Date = Date()) {
        let cal = Calendar.current
        let today = cal.startOfDay(for: now)
        days = (0..<7).reversed().map { cal.date(byAdding: .day, value: -$0, to: today)! }

        var spans: [Span] = [], conn: [(Date, Date)] = [], bat: [(Date, Int)] = []
        for (i, events) in sources.enumerated() {
            // A synced log that ends while connected: count it only up to its last event.
            let end = i == 0 ? now : (events.last?.t ?? now)
            let (sp, cn, bt) = Self.parse(events, end: end)
            for s in sp { spans += Self.subtract(s, spans) }
            conn += cn
            bat += bt
        }
        self.spans = spans.sorted { $0.start < $1.start }
        battery = bat.sorted { $0.0 < $1.0 }
        conn = Self.union(conn)
        let d = days
        usage = d.indices.map { i in
            let a = d[i], b = cal.date(byAdding: .day, value: 1, to: a)!
            return conn.reduce(0) { $0 + max(0, min($1.1, b).timeIntervalSince(max($1.0, a))) }
        }
    }

    private static func parse(_ events: [History.Event], end: Date) -> ([Span], [(Date, Date)], [(Date, Int)]) {
        var spans: [Span] = [], conn: [(Date, Date)] = [], bat: [(Date, Int)] = []
        var connStart: Date?, mode: Int?, modeStart = end
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
        closeMode(end)
        if let s = connStart, end > s { conn.append((s, end)) }
        return (spans, conn, bat)
    }

    /// The parts of `s` not already covered by `taken`.
    private static func subtract(_ s: Span, _ taken: [Span]) -> [Span] {
        var parts = [(s.start, s.end)]
        for t in taken where t.end > s.start && t.start < s.end {
            parts = parts.flatMap { p -> [(Date, Date)] in
                if t.end <= p.0 || t.start >= p.1 { return [p] }
                var out: [(Date, Date)] = []
                if t.start > p.0 { out.append((p.0, t.start)) }
                if t.end < p.1 { out.append((t.end, p.1)) }
                return out
            }
        }
        return parts.map { Span(start: $0.0, end: $0.1, mode: s.mode) }
    }

    /// Overlapping time ranges joined, so time on two devices counts once.
    private static func union(_ r: [(Date, Date)]) -> [(Date, Date)] {
        var out: [(Date, Date)] = []
        for x in r.sorted(by: { $0.0 < $1.0 }) {
            if let last = out.last, x.0 <= last.1 { out[out.count - 1].1 = max(last.1, x.1) } else { out.append(x) }
        }
        return out
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
