import Foundation

/// The EQ values, all from eq.json at the repo root; the app has none of its own. build.sh ships a
/// copy of it in the app (Resources/eq.json), and the latest is downloaded a minute after launch,
/// every 6 hours and with "Check now". Whichever copy is newest ("updatedAt") is used. A file that
/// fails any check is ignored.
enum EqRemote {
    enum Outcome { case applied, upToDate, failed }

    private static let endpoint = URL(string: "https://raw.githubusercontent.com/georgerahul24/cmf-headphone-pro-talk-mode/main/eq.json")!
    private static let dataKey = "eq.remote.data"
    private static var configs: [String: Config] = [:]
    private static var checking = false
    private(set) static var version = "none"
    /// "updatedAt" of the tuning in use, to tell which copy is newer. ISO dates sort as text.
    private static var updatedAt = ""

    /// One preset from eq.json. `factory`: Nothing's own preset number on the headphones, if set.
    struct Config { let about: String?; let factory: UInt8?; let preamp: Float; let bands: [Band] }
    struct Parsed { let version: String; let updatedAt: String; let configs: [String: Config] }

    static func about(for eq: Eq) -> String? { configs[eq.rawValue]?.about }
    static func factory(for eq: Eq) -> UInt8? { configs[eq.rawValue]?.factory }
    static func preamp(for eq: Eq) -> Float? { configs[eq.rawValue]?.preamp }
    static func bands(for eq: Eq) -> [Band]? { configs[eq.rawValue]?.bands }

    /// Loads the copy shipped in the app and the last download, and uses the newer one.
    static func load() {
        var bundled: Parsed?
        if let url = Bundle.main.url(forResource: "eq", withExtension: "json"), let data = try? Data(contentsOf: url) {
            do { bundled = try parse(data) } catch { log("the eq.json shipped with the app is broken: \(error)") }
        }
        var saved: Parsed?
        if let data = UserDefaults.standard.data(forKey: dataKey) {
            do { saved = try parse(data) } catch { log("saved EQ rejected: \(error)") }
        }
        if let best = [bundled, saved].compactMap({ $0 }).max(by: { $0.updatedAt < $1.updatedAt }) { use(best) }
    }

    private static func use(_ t: Parsed) {
        configs = t.configs
        version = t.version
        updatedAt = t.updatedAt
    }

    /// Downloads eq.json and applies it if it changed. `done` runs on the main thread.
    static func check(done: @escaping (Outcome) -> Void) {
        guard !checking else { return }
        checking = true
        // The query skips GitHub's few-minute cache, so a new tuning shows up right away.
        var parts = URLComponents(url: endpoint, resolvingAgainstBaseURL: false)!
        parts.queryItems = [URLQueryItem(name: "t", value: String(Int(Date().timeIntervalSince1970 / 60)))]
        var request = URLRequest(url: parts.url!, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)
        request.setValue("cmf-talk-mode-eq", forHTTPHeaderField: "User-Agent")
        URLSession.shared.dataTask(with: request) { data, response, error in
            do {
                if let error { throw error }
                guard let response = response as? HTTPURLResponse, response.statusCode == 200,
                      let data, data.count <= 64 * 1024 else { throw URLError(.badServerResponse) }
                let parsed = try parse(data) // throws if anything is out of range
                DispatchQueue.main.async {
                    checking = false
                    if data == UserDefaults.standard.data(forKey: dataKey) && !configs.isEmpty { done(.upToDate); return }
                    UserDefaults.standard.set(data, forKey: dataKey)
                    // The copy in the app can be newer than GitHub's; keep it then.
                    if parsed.updatedAt < updatedAt { done(.upToDate); return }
                    use(parsed)
                    done(.applied)
                }
            } catch {
                log("live EQ unavailable; keeping the current tuning: \(error)")
                DispatchQueue.main.async { checking = false; done(.failed) }
            }
        }.resume()
    }

    private static func parse(_ data: Data) throws -> Parsed {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              (root["schema"] as? NSNumber)?.intValue == 1,
              let revision = root["version"] as? String, !revision.isEmpty,
              let presets = root["presets"] as? [String: Any] else {
            throw URLError(.cannotParseResponse)
        }
        var out: [String: Config] = [:]
        for id in ["movie", "pop", "rock", "classical"] {
            guard let preset = presets[id] as? [String: Any],
                  let preamp = preset["preamp"] as? NSNumber,
                  (-6.0...3.0).contains(preamp.doubleValue),
                  let rawBands = preset["bands"] as? [[String: Any]], rawBands.count == 8 else {
                throw URLError(.cannotParseResponse)
            }
            // Optional: use Nothing's own preset on the headphones (1 to 5) instead of the bands.
            var factory: UInt8?
            if let f = preset["factory"] {
                guard let n = f as? NSNumber, (1...5).contains(n.intValue) else { throw URLError(.cannotParseResponse) }
                factory = UInt8(n.intValue)
            }
            let bands = try rawBands.map { b -> Band in
                guard let type = b["type"] as? NSNumber, (0...2).contains(type.intValue),
                      let gain = b["gain"] as? NSNumber, (-12.0...12.0).contains(gain.doubleValue),
                      let freq = b["freq"] as? NSNumber, (20.0...20000.0).contains(freq.doubleValue),
                      let q = b["q"] as? NSNumber, (0.1...10.0).contains(q.doubleValue) else {
                    throw URLError(.cannotParseResponse)
                }
                return Band(type: UInt8(type.intValue), gain: gain.floatValue, freq: freq.floatValue, q: q.floatValue)
            }
            let about = (preset["about"] as? String).flatMap { $0.isEmpty ? nil : String($0.prefix(500)) }
            out[id] = Config(about: about, factory: factory, preamp: preamp.floatValue, bands: bands)
        }
        let updatedAt = (root["updatedAt"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        return Parsed(version: [revision, updatedAt].compactMap { $0 }.joined(separator: " · "), updatedAt: updatedAt ?? "", configs: out)
    }
}
