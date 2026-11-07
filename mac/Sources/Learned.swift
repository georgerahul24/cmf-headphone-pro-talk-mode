import Foundation

/// EQ picks you made for what's playing, remembered by name: the show, film or song ("mr robot"),
/// and the artist or channel. Next time something with the same name plays, Auto uses your pick.
/// Episode numbers are ignored, so a pick on one episode covers the whole series. Nothing is looked
/// up online. Syncs with the phone: each entry has a change time and the newer one wins; forgetting
/// a pick is kept as an empty entry so it syncs too. Same rules as the Android app (Learned.kt).
final class Learned: ObservableObject {
    static let shared = Learned()
    private let d = UserDefaults.standard
    /// Called after a change made on this Mac, for sync.
    var onChange: (() -> Void)?

    /// key ("t:mr robot", "a:anirudh ravichander") -> ["p": preset ("" = forgotten), "ts": ms]
    private var map: [String: [String: Any]] {
        get { d.dictionary(forKey: "learned") as? [String: [String: Any]] ?? [:] }
        set { d.set(newValue, forKey: "learned"); objectWillChange.send() }
    }

    private static func re(_ s: String, _ pattern: String, _ with: String) -> String {
        s.replacingOccurrences(of: pattern, with: with, options: .regularExpression)
    }

    /// "Mr. Robot S1E3 | eps1.2_d3bug" -> "mr robot". Nil if nothing is left.
    static func titleKey(_ title: String?) -> String? {
        guard var t = title?.lowercased() else { return nil }
        t = re(t, "[(\\[{][^)\\]}]*[)\\]}]", " ")
        if let r = t.range(of: "\\s+[|\u{2014}\u{2013}-]\\s+|:\\s+", options: .regularExpression) { t = String(t[..<r.lowerBound]) }
        // Cut at the first episode marker ("3x07 One Minute" is the episode's own name), then drop any left.
        let marker = "\\b(s\\d+\\s*e\\d+|\\d+x\\d+|season\\s*\\d+|episode\\s*\\d+|ep\\.?\\s*\\d+|part\\s*\\d+|e\\d+)\\b"
        if let r = t.range(of: marker, options: .regularExpression), r.lowerBound > t.startIndex { t = String(t[..<r.lowerBound]) }
        t = re(t, marker, " ")
        t = re(t, "[^\\p{L}\\p{N}]+", " ").trimmingCharacters(in: .whitespaces)
        t = re(t, "\\s+", " ")
        t = re(t, "(\\s\\d+)+$", "")
        return t.isEmpty ? nil : t
    }

    /// "Anirudh Ravichander, Super Subu and Heisenberg" -> "anirudh ravichander".
    static func artistKey(_ artist: String?) -> String? {
        guard let a = artist else { return nil }
        let first = a.components(separatedBy: CharacterSet(charactersIn: ",&")).first?.components(separatedBy: " and ").first ?? a
        let k = re(re(first.lowercased(), "[^\\p{L}\\p{N}]+", " ").trimmingCharacters(in: .whitespaces), "\\s+", " ")
        return k.isEmpty ? nil : k
    }

    private func preset(_ key: String) -> String? {
        (map[key]?["p"] as? String).flatMap { $0.isEmpty ? nil : $0 }
    }

    /// Your pick for this title or, failing that, this artist: the preset and the name it matched.
    func lookup(title: String?, artist: String?) -> (preset: String, name: String)? {
        if let k = Self.titleKey(title), let p = preset("t:" + k) { return (p, k) }
        if let k = Self.artistKey(artist), let p = preset("a:" + k) { return (p, k) }
        return nil
    }

    /// The pick stored for exactly this title, if any (for highlighting the buttons).
    func forTitle(_ title: String?) -> String? { Self.titleKey(title).flatMap { preset("t:" + $0) } }

    /// Remembers `p` for this title and artist; nil forgets them.
    func set(title: String?, artist: String?, preset p: String?) {
        var m = map
        let e: [String: Any] = ["p": p ?? "", "ts": Date().timeIntervalSince1970 * 1000]
        if let k = Self.titleKey(title) { m["t:" + k] = e }
        if let k = Self.artistKey(artist) { m["a:" + k] = e }
        map = m
        onChange?()
    }

    /// Everything, for sending to the phone: {key: {p, ts}}.
    func all() -> [String: Any] { map }

    /// Entries from the phone; newer ones win. True if anything changed.
    func merge(_ j: [String: Any]) -> Bool {
        var m = map
        var changed = false
        for (k, raw) in j {
            guard k.hasPrefix("t:") || k.hasPrefix("a:"), k.count <= 300, let o = raw as? [String: Any] else { continue }
            let p = o["p"] as? String ?? ""
            if !p.isEmpty && Eq(rawValue: p) == nil { continue }
            let ts = (o["ts"] as? NSNumber)?.doubleValue ?? 0
            let mine = (m[k]?["ts"] as? NSNumber)?.doubleValue ?? (m[k]?["ts"] as? Double) ?? -1
            if ts > mine { m[k] = ["p": p, "ts": ts]; changed = true }
        }
        if changed { map = m }
        return changed
    }
}
