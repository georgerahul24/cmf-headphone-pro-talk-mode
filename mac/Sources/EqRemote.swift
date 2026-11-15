import Foundation

/// Live EQ tuning: eq.json from the repository, checked a minute after launch, every 6 hours and
/// with "Check now". A file that fails any check is ignored, and the last good one (or the values
/// built into the app) stays in use.
enum EqRemote {
    enum Outcome { case applied, upToDate, failed }

    private static let endpoint = URL(string: "https://raw.githubusercontent.com/georgerahul24/cmf-headphone-pro-talk-mode/main/eq.json")!
    private static let dataKey = "eq.remote.data"
    private static var configs: [String: (Float, [Band])] = [:]
    private static var checking = false
    private(set) static var version = "Bundled"

    static func preamp(for eq: Eq) -> Float? { configs[eq.rawValue]?.0 }
    static func bands(for eq: Eq) -> [Band]? { configs[eq.rawValue]?.1 }

    static func loadCached() {
        guard let data = UserDefaults.standard.data(forKey: dataKey) else { return }
        do {
            let parsed = try parse(data)
            configs = parsed.configs
            version = parsed.version
        } catch {
            log("cached EQ rejected: \(error)")
        }
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
                    configs = parsed.configs
                    version = parsed.version
                    UserDefaults.standard.set(data, forKey: dataKey)
                    done(.applied)
                }
            } catch {
                log("live EQ unavailable; keeping the current tuning: \(error)")
                DispatchQueue.main.async { checking = false; done(.failed) }
            }
        }.resume()
    }

    private static func parse(_ data: Data) throws -> (version: String, configs: [String: (Float, [Band])]) {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              (root["schema"] as? NSNumber)?.intValue == 1,
              let revision = root["version"] as? String, !revision.isEmpty,
              let presets = root["presets"] as? [String: Any] else {
            throw URLError(.cannotParseResponse)
        }
        var out: [String: (Float, [Band])] = [:]
        for id in ["movie", "pop", "rock", "classical"] {
            guard let preset = presets[id] as? [String: Any],
                  let preamp = preset["preamp"] as? NSNumber,
                  (-6.0...3.0).contains(preamp.doubleValue),
                  let rawBands = preset["bands"] as? [[String: Any]], rawBands.count == 8 else {
                throw URLError(.cannotParseResponse)
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
            out[id] = (preamp.floatValue, bands)
        }
        let updatedAt = (root["updatedAt"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        return ([revision, updatedAt].compactMap { $0 }.joined(separator: " · "), out)
    }
}
