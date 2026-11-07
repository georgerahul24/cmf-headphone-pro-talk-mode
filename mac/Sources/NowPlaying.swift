import Foundation

/// What's playing on this Mac, for the "Now playing" card and the genre lookup: from the system's
/// Now Playing (any app, browsers included), or by asking Apple Music and Spotify directly as a
/// fallback (macOS asks once for permission).
enum NowPlayingInfo {
    struct Song: Equatable { let title: String; let artist: String }

    private static let scripts: [String: String] = [
        "com.apple.Music": "tell application id \"com.apple.Music\" to if player state is playing then return (name of current track) & \"\\n\" & (artist of current track)",
        "com.spotify.client": "tell application id \"com.spotify.client\" to if player state is playing then return (name of current track) & \"\\n\" & (artist of current track)",
    ]

    static func canRead(_ bundleId: String) -> Bool { scripts[bundleId] != nil }

    /// The song from the system's Now Playing (what Control Center shows): any app, including
    /// YouTube Music in a browser like Zen. Read with the bundled mediaremote-adapter, since
    /// macOS 15.4 and newer no longer share it with apps directly. See mac/vendor/mediaremote-adapter.
    private(set) static var system: (song: Song, app: String)?
    private static var reading = false

    /// Reads the system's Now Playing in the background; `system` is updated on the main thread.
    static func refreshSystem(_ done: @escaping () -> Void) {
        guard !reading,
              let script = Bundle.main.url(forResource: "mediaremote-adapter", withExtension: "pl") else { return }
        let fw = Bundle.main.bundleURL.appendingPathComponent("Contents/Frameworks/MediaRemoteAdapter.framework")
        guard FileManager.default.fileExists(atPath: fw.path) else { return }
        reading = true
        DispatchQueue.global(qos: .utility).async {
            let found = read(script: script, framework: fw)
            DispatchQueue.main.async {
                reading = false
                let changed = found?.song != system?.song || found?.app != system?.app
                system = found
                if changed { done() }
            }
        }
    }

    /// Runs Apple's /usr/bin/perl with fixed paths inside the app, no shell, an empty environment
    /// (so nothing like PERL5OPT can change what it does), the read-only "get" command, a 3 second
    /// limit and a 64 KB output limit. Only title, artist and app are used.
    private static func read(script: URL, framework: URL) -> (song: Song, app: String)? {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/perl")
        p.arguments = [script.path, framework.path, "get", "--no-artwork"]
        p.environment = [:]
        let out = Pipe()
        p.standardOutput = out
        p.standardError = FileHandle.nullDevice
        p.standardInput = FileHandle.nullDevice
        do { try p.run() } catch { return nil }
        let timer = DispatchWorkItem { if p.isRunning { p.terminate() } }
        DispatchQueue.global().asyncAfter(deadline: .now() + 3, execute: timer)
        let data = out.fileHandleForReading.readData(ofLength: 64 * 1024)
        p.waitUntilExit()
        timer.cancel()
        guard p.terminationStatus == 0,
              let j = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let title = j["title"] as? String, !title.isEmpty else { return nil }
        let artist = j["artist"] as? String ?? ""
        let app = j["bundleIdentifier"] as? String ?? ""
        return (Song(title: String(title.prefix(300)), artist: String(artist.prefix(300))), app)
    }

    /// The song playing in that app, or nil. Main thread.
    static func song(in bundleId: String) -> Song? {
        guard let src = scripts[bundleId], let script = NSAppleScript(source: src) else { return nil }
        var err: NSDictionary?
        guard let out = script.executeAndReturnError(&err).stringValue, err == nil else { return nil }
        let parts = out.components(separatedBy: "\n")
        guard let title = parts.first, !title.isEmpty else { return nil }
        return Song(title: title, artist: parts.count > 1 ? parts[1] : "")
    }
}

/// Song genre for Auto EQ: looks the song up on Apple's iTunes Search (free, no account; only the
/// title and artist are sent). Rock-type genres get Rock, classical music Classical, everything else Pop.
enum GenreLookup {
    private static var cache: [String: String] = [:]   // "" = not found
    private static var pending: Set<String> = []
    private static let rock = ["rock", "metal", "punk", "alternative", "grunge", "emo", "hardcore"]
    private static let classical = ["classical", "opera", "orchestral", "baroque", "choral", "chamber"]

    static func key(_ s: NowPlayingInfo.Song) -> String { "\(s.artist.lowercased())|\(s.title.lowercased())" }

    /// The cached genre, "" if not found, or nil if not looked up yet.
    static func cached(_ s: NowPlayingInfo.Song) -> String? { cache[key(s)] }

    static func preset(for genre: String, fallback: String) -> String {
        if genre.isEmpty { return fallback }
        let g = genre.lowercased()
        if classical.contains(where: { g.contains($0) }) { return Eq.classical.rawValue }
        return rock.contains { g.contains($0) } ? Eq.rock.rawValue : Eq.pop.rawValue
    }

    private static func clean(_ s: String) -> String {
        s.replacingOccurrences(of: "\\s*[(\\[][^)\\]]*[)\\]]", with: "", options: .regularExpression)
            .replacingOccurrences(of: "(?i)\\s+(ft\\.?|feat\\.?|featuring)\\s.*$", with: "", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
    }

    /// Looks the song up in the background, then calls `done` on the main thread.
    static func lookup(_ s: NowPlayingInfo.Song, done: @escaping () -> Void) {
        let k = key(s)
        guard cache[k] == nil, !pending.contains(k) else { return }
        pending.insert(k)
        let country = Locale.current.region?.identifier ?? "IN"
        var c = URLComponents(string: "https://itunes.apple.com/search")!
        // Only the first artist: long lists like "A, B, C and D" can make the search miss.
        let artist = s.artist.components(separatedBy: CharacterSet(charactersIn: ",&")).first?
            .components(separatedBy: " and ").first ?? s.artist
        c.queryItems = [.init(name: "term", value: "\(clean(s.title)) \(clean(artist))"), .init(name: "entity", value: "song"),
                        .init(name: "limit", value: "1"), .init(name: "country", value: country)]
        var req = URLRequest(url: c.url!, timeoutInterval: 10)
        req.setValue("cmf-talk-mode", forHTTPHeaderField: "User-Agent")
        URLSession.shared.dataTask(with: req) { data, _, error in
            let genre: String? = error != nil ? nil : {
                guard let data, let j = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return "" }
                return ((j["results"] as? [[String: Any]])?.first?["primaryGenreName"] as? String) ?? ""
            }()
            DispatchQueue.main.async {
                pending.remove(k)
                if let genre { cache[k] = genre } // network errors aren't cached, so it tries again later
                done()
            }
        }.resume()
    }
}
