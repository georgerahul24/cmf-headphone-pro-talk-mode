import Foundation

/// What's playing on this Mac, for the "Now playing" card and the genre lookup. macOS 15.4 and
/// newer block apps from reading the system's now-playing song, so this asks Apple Music and
/// Spotify directly (macOS asks once for permission). Songs in a browser can't be read.
enum NowPlayingInfo {
    struct Song: Equatable { let title: String; let artist: String }

    private static let scripts: [String: String] = [
        "com.apple.Music": "tell application id \"com.apple.Music\" to if player state is playing then return (name of current track) & \"\\n\" & (artist of current track)",
        "com.spotify.client": "tell application id \"com.spotify.client\" to if player state is playing then return (name of current track) & \"\\n\" & (artist of current track)",
    ]

    static func canRead(_ bundleId: String) -> Bool { scripts[bundleId] != nil }

    /// The song from the system's Now Playing (what Control Center shows), e.g. YouTube Music in a
    /// browser. Newer macOS versions may refuse this to apps; then it stays nil.
    private(set) static var system: Song?

    private typealias GetInfo = @convention(c) (DispatchQueue, @escaping ([String: Any]?) -> Void) -> Void
    private static let getInfo: GetInfo? = {
        guard let h = dlopen("/System/Library/PrivateFrameworks/MediaRemote.framework/MediaRemote", RTLD_LAZY),
              let f = dlsym(h, "MRMediaRemoteGetNowPlayingInfo") else { return nil }
        return unsafeBitCast(f, to: GetInfo.self)
    }()

    /// Asks the system for its Now Playing song; `system` is updated on the main thread.
    static func refreshSystem(_ done: @escaping () -> Void) {
        guard let getInfo else { return }
        getInfo(.main) { info in
            let title = info?["kMRMediaRemoteNowPlayingInfoTitle"] as? String ?? ""
            let artist = info?["kMRMediaRemoteNowPlayingInfoArtist"] as? String ?? ""
            let s = title.isEmpty ? nil : Song(title: title, artist: artist)
            if s != system { system = s; done() }
        }
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
        c.queryItems = [.init(name: "term", value: "\(clean(s.title)) \(clean(s.artist))"), .init(name: "entity", value: "song"),
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
