import AppKit
import CoreAudio

/// One EQ filter. Type: 0 low shelf, 1 peak, 2 high shelf. Gain in dB.
struct Band { let type: UInt8; let gain: Float; let freq: Float; let q: Float }

/// EQ presets for the CMF Headphone Pro, picked for what each one is for rather than copied from the factory presets.
///
/// Same values as the Android app.
enum Eq: String, CaseIterable {
    case movie, pop, rock, flat

    /// The presets with a character (everything but Flat).
    static let presets: [Eq] = [.movie, .pop, .rock]

    var label: String { rawValue.capitalized }

    var about: String {
        switch self {
        case .movie: return "Made for the Energy Slider at full bass, which brings the rumble and boom. Mid and treble are lifted so dialogue, faint sounds and small details stay clear."
        case .pop: return "Big bass, vocals pushed forward and a brighter top. Good for English, Hindi and Malayalam songs."
        case .flat: return "No EQ: bass, mid and treble all at 0. Every preset is measured from here."
        case .rock: return "Punchy bass and bright, crisp top with the mids pulled back a little, for drums and guitars."
        }
    }

    /// No preamp: the factory presets and the Energy Slider boost bass 6-10 dB with no loss of level, so
    /// the DSP has headroom. A negative preamp only made ours sound quieter and thinner than stock.
    var preamp: Float { 0 }

    /// The CMF Headphone Pro's sound chip is built for Nothing's 3-band custom EQ (bass, mid,
    /// treble). It will store more bands, but a listening test and every review point to only the 3
    /// being used properly, so each preset is a 3-band EQ sent exactly like Nothing X sends it.
    var gains: (bass: Float, mid: Float, treble: Float) {
        switch self {
        case .movie: return (2, 2, 4)   // the Energy Slider brings the bass; clear dialogue and detail
        case .pop: return (5, 2, 3)     // big bass, vocals forward, brighter top
        case .rock: return (4, -2, 4)   // punchy and bright, mids pulled back
        case .flat: return (0, 0, 0)    // the neutral starting point
        }
    }

    /// Nothing's order and shapes: mid peak 980 Hz, treble high shelf 3.5 kHz, bass low shelf 140 Hz.
    var bands: [Band] {
        let g = gains
        return [Band(type: 1, gain: g.mid, freq: 980, q: 0.7), Band(type: 2, gain: g.treble, freq: 3500, q: 1),
                Band(type: 0, gain: g.bass, freq: 140, q: 0.8)]
    }

    /// Payload for `Proto.eqCustomSet`: count, preamp, then type/gain/freq/Q per band (float32 LE), 3 zero bytes per band.
    var payload: [UInt8] {
        func f(_ v: Float) -> [UInt8] { withUnsafeBytes(of: v.bitPattern.littleEndian) { Array($0) } }
        var out: [UInt8] = [UInt8(bands.count)] + f(preamp)
        for b in bands { out += [b.type] + f(b.gain) + f(b.freq) + f(b.q) }
        return out + [UInt8](repeating: 0, count: 3 * bands.count)
    }
}

/// EQ choices in the menu: Auto or one of the `Eq` presets.
enum EqMode {
    static let auto = "auto"
    static let all = [auto, Eq.movie.rawValue, Eq.pop.rawValue, Eq.rock.rawValue, Eq.flat.rawValue]
    /// Older versions had "stock" (the factory Pop preset); it is Flat now.
    static func normalize(_ id: String) -> String { id == "stock" ? Eq.flat.rawValue : id }
    static func label(_ id: String) -> String {
        switch id {
        case auto: return "Auto"
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
        "com.apple.iTunes", "io.cider", "com.audirvana.Audirvana-Studio", "com.roon.Roon", "app.zen-browser.zen",
    ]
    /// Browsers count as video: on a Mac, sound from a browser is usually a show or a video.
    /// Zen is the exception: it's used mostly for music, so it counts as music.
    private static let browsers = [
        "com.apple.WebKit", "com.apple.Safari", "com.google.Chrome", "org.mozilla.firefox", "company.thebrowser",
        "com.microsoft.edgemac", "com.brave.Browser", "com.operasoftware", "com.vivaldi",
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

// MARK: - curve for drawing

extension Eq {
    /// Frequencies to draw the curve at: 20 Hz to 20 kHz, evenly on a log scale.
    static let plotFreqs: [Double] = (0..<160).map { 20 * pow(1000, Double($0) / 159) }

    /// The preset's EQ curve in dB at `f` Hz, from standard biquad filters (RBJ cookbook) at 48 kHz.
    /// Close to what the headphones' DSP does; for drawing only.
    func gain(at f: Double) -> Double {
        Double(preamp) + bands.reduce(0) { $0 + Self.bandGain($1, f) }
    }

    static func bandGain(_ b: Band, _ f: Double) -> Double {
        let fs = 48_000.0
        let a = pow(10, Double(b.gain) / 40)
        let w0 = 2 * Double.pi * Double(b.freq) / fs
        let cw = cos(w0), alpha = sin(w0) / (2 * Double(b.q)), sa = 2 * sqrt(a) * alpha
        var b0, b1, b2, a0, a1, a2: Double
        switch b.type {
        case 0: // low shelf
            b0 = a * ((a + 1) - (a - 1) * cw + sa); b1 = 2 * a * ((a - 1) - (a + 1) * cw); b2 = a * ((a + 1) - (a - 1) * cw - sa)
            a0 = (a + 1) + (a - 1) * cw + sa; a1 = -2 * ((a - 1) + (a + 1) * cw); a2 = (a + 1) + (a - 1) * cw - sa
        case 2: // high shelf
            b0 = a * ((a + 1) + (a - 1) * cw + sa); b1 = -2 * a * ((a - 1) + (a + 1) * cw); b2 = a * ((a + 1) + (a - 1) * cw - sa)
            a0 = (a + 1) - (a - 1) * cw + sa; a1 = 2 * ((a - 1) - (a + 1) * cw); a2 = (a + 1) - (a - 1) * cw - sa
        default: // peak
            b0 = 1 + alpha * a; b1 = -2 * cw; b2 = 1 - alpha * a
            a0 = 1 + alpha / a; a1 = -2 * cw; a2 = 1 - alpha / a
        }
        let w = 2 * Double.pi * f / fs
        func mag2(_ c0: Double, _ c1: Double, _ c2: Double) -> Double {
            c0 * c0 + c1 * c1 + c2 * c2 + 2 * (c0 * c1 + c1 * c2) * cos(w) + 2 * c0 * c2 * cos(2 * w)
        }
        return 10 * log10(mag2(b0, b1, b2) / mag2(a0, a1, a2))
    }
}

/// "40 Hz", "1.8 kHz".
func freqText(_ f: Float) -> String {
    f < 1000 ? "\(Int(f)) Hz" : (f.truncatingRemainder(dividingBy: 1000) == 0 ? "\(Int(f / 1000)) kHz" : String(format: "%.1f kHz", f / 1000))
}

/// "+2.5 dB", "-3 dB".
func gainText(_ g: Float) -> String {
    let s = g.truncatingRemainder(dividingBy: 1) == 0 ? String(Int(g)) : String(format: "%.1f", g)
    return (g > 0 ? "+" : "") + s + " dB"
}
