import AppKit
import CoreAudio

/// One EQ filter. Type: 0 low shelf, 1 peak, 2 high shelf. Gain in dB.
struct Band { let type: UInt8; let gain: Float; let freq: Float; let q: Float }

/// EQ presets, tuned for the CMF Headphone Pro from measurements, not copied from the factory presets.
///
/// Out of the box these headphones have (vs. the Harman target): about +2.5 dB sub-bass, +2 dB
/// mid-bass and 200-250 Hz, a 3-6 dB dip at 2-4.5 kHz (voices sound a bit far away) and a +4 dB
/// peak at 8 kHz (sharp "s" sounds). Sources: SoundGuys B&K 5128 data, RTINGS, Kuulokenurkka,
/// Pragmatic Audio. The headphones take up to 8 filters with any frequency and Q (checked on a
/// real pair), so each preset uses all 8 to fix these one by one, instead of plain bass/mid/treble.
/// Same values as the Android app.
enum Eq: String, CaseIterable {
    case movie, pop, rock

    var label: String { rawValue.capitalized }

    var about: String {
        switch self {
        case .movie: return "Deep rumble you can feel, without the boom. Dialogue pushed forward so voices stay clear in loud scenes."
        case .pop: return "Full, clean bass, vocals up front and smooth, with a bit of shine on top."
        case .rock: return "Punchy kick drum, less muddy guitars, more crunch and bite, cymbals that sparkle."
        }
    }

    var preamp: Float { self == .movie ? -5 : -4 }

    var bands: [Band] {
        switch self {
        case .movie: return [
            Band(type: 0, gain: 4.5, freq: 40, q: 0.7),     // rumble: lowest bass only, felt, not boomy
            Band(type: 1, gain: -1.5, freq: 110, q: 1),     // less mid-bass boom, keeps punch
            Band(type: 1, gain: -2.5, freq: 250, q: 1),     // clears the mud that covers voices
            Band(type: 1, gain: 1.5, freq: 1800, q: 1.4),   // body of the voice
            Band(type: 1, gain: 3, freq: 3000, q: 1.4),     // dialogue clarity
            Band(type: 1, gain: 3, freq: 4200, q: 2.5),     // fills the 4 kHz dip
            Band(type: 1, gain: -3, freq: 8000, q: 3),      // tames the 8 kHz peak
            Band(type: 2, gain: 1.5, freq: 13000, q: 0.7),  // air and space
        ]
        case .pop: return [
            Band(type: 0, gain: -1, freq: 105, q: 0.7),
            Band(type: 1, gain: 1, freq: 55, q: 1.2),
            Band(type: 1, gain: -1.5, freq: 250, q: 1),
            Band(type: 1, gain: 1, freq: 1800, q: 1.4),
            Band(type: 1, gain: 2.5, freq: 3000, q: 1.4),
            Band(type: 1, gain: 3, freq: 4200, q: 2.5),
            Band(type: 1, gain: -3.5, freq: 8000, q: 3),
            Band(type: 2, gain: 2, freq: 12000, q: 0.7),
        ]
        case .rock: return [
            Band(type: 0, gain: -1.5, freq: 35, q: 0.7),
            Band(type: 1, gain: 1, freq: 90, q: 1.2),
            Band(type: 1, gain: -2, freq: 250, q: 1),
            Band(type: 1, gain: 1, freq: 1500, q: 1.2),
            Band(type: 1, gain: 2, freq: 3000, q: 1.4),
            Band(type: 1, gain: 3, freq: 4200, q: 2.5),
            Band(type: 1, gain: -2, freq: 8000, q: 3),
            Band(type: 2, gain: 2.5, freq: 11000, q: 0.7),
        ]
        }
    }

    /// Payload for `Proto.eqCustomSet`: count, preamp, then type/gain/freq/Q per band (float32 LE), 3 zero bytes per band.
    var payload: [UInt8] {
        func f(_ v: Float) -> [UInt8] { withUnsafeBytes(of: v.bitPattern.littleEndian) { Array($0) } }
        var out: [UInt8] = [UInt8(bands.count)] + f(preamp)
        for b in bands { out += [b.type] + f(b.gain) + f(b.freq) + f(b.q) }
        return out + [UInt8](repeating: 0, count: 3 * bands.count)
    }
}

/// EQ choices in the menu. "stock" = the headphones' own factory sound.
enum EqMode {
    static let auto = "auto", stock = "stock"
    static let all = [auto, Eq.movie.rawValue, Eq.pop.rawValue, Eq.rock.rawValue, stock]
    static func label(_ id: String) -> String {
        switch id {
        case auto: return "Auto"
        case stock: return "Stock"
        default: return Eq(rawValue: id)?.label ?? id
        }
    }
}

/// Which apps are sending sound out right now, for Auto EQ. Uses the Core Audio process list
/// (macOS 14.2 and newer), so no extra permission is needed.
enum PlayingApps {
    private static let video: Set<String> = [
        "com.apple.TV", "com.colliderli.iina", "org.videolan.vlc", "com.apple.QuickTimePlayerX", "io.mpv",
        "com.firecore.infuse", "tv.plex.desktop", "com.amazon.aiv.AIVApp", "com.plexapp.plexmediaplayer",
        "org.jellyfin.JellyfinMediaPlayer", "com.movist.MovistPro", "com.apple.FinalCut",
    ]
    private static let music: Set<String> = [
        "com.apple.Music", "com.spotify.client", "com.tidal.desktop", "com.apple.podcasts", "com.deezer.deezer-desktop",
        "com.amazon.music", "com.github.th-ch.youtube-music", "com.coppertino.Vox", "com.qobuz.QobuzDesktop",
        "com.apple.iTunes", "io.cider", "com.audirvana.Audirvana-Studio", "com.roon.Roon",
    ]
    /// Browsers count as video: on a Mac, sound from a browser is usually a show or a video.
    private static let browsers = [
        "com.apple.WebKit", "com.apple.Safari", "com.google.Chrome", "org.mozilla.firefox", "company.thebrowser",
        "com.microsoft.edgemac", "com.brave.Browser", "app.zen-browser", "com.operasoftware", "com.vivaldi",
    ]

    /// true = video, false = music, with the app's name. Nil = nothing we can tell.
    static func kind() -> (Bool, String)? {
        let ids = playing()
        if let v = ids.first(where: { id in video.contains(id) || browsers.contains { id.hasPrefix($0) } }) { return (true, name(v)) }
        if let m = ids.first(where: { music.contains($0) }) { return (false, name(m)) }
        // Any other app that isn't a macOS system sound: treat as music.
        if let o = ids.first(where: { !$0.hasPrefix("com.apple.") }) { return (false, name(o)) }
        return nil
    }

    private static func name(_ id: String) -> String {
        if id.hasPrefix("com.apple.WebKit") { return "Safari" }
        var base = id
        for s in [".helper", ".Helper", ".plugin"] { if let r = base.range(of: s) { base = String(base[..<r.lowerBound]) } }
        if let app = NSRunningApplication.runningApplications(withBundleIdentifier: base).first, let n = app.localizedName { return n }
        return base.split(separator: ".").last.map(String.init) ?? id
    }

    /// Bundle IDs of processes playing audio right now.
    static func playing() -> [String] {
        guard #available(macOS 14.2, *) else { return [] }
        let sys = AudioObjectID(kAudioObjectSystemObject)
        var addr = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyProcessObjectList,
                                              mScope: kAudioObjectPropertyScopeGlobal,
                                              mElement: kAudioObjectPropertyElementMain)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(sys, &addr, 0, nil, &size) == noErr, size > 0 else { return [] }
        var ids = [AudioObjectID](repeating: 0, count: Int(size) / MemoryLayout<AudioObjectID>.size)
        guard AudioObjectGetPropertyData(sys, &addr, 0, nil, &size, &ids) == noErr else { return [] }
        return ids.compactMap { id -> String? in
            var a = AudioObjectPropertyAddress(mSelector: kAudioProcessPropertyIsRunningOutput,
                                               mScope: kAudioObjectPropertyScopeGlobal,
                                               mElement: kAudioObjectPropertyElementMain)
            var running: UInt32 = 0
            var s = UInt32(MemoryLayout<UInt32>.size)
            guard AudioObjectGetPropertyData(id, &a, 0, nil, &s, &running) == noErr, running != 0 else { return nil }
            a.mSelector = kAudioProcessPropertyBundleID
            var cf: Unmanaged<CFString>?
            s = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
            guard AudioObjectGetPropertyData(id, &a, 0, nil, &s, &cf) == noErr, let str = cf?.takeRetainedValue() else { return nil }
            let b = str as String
            return b.isEmpty ? nil : b
        }
    }
}
