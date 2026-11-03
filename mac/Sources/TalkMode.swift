import Foundation
import Combine
import ServiceManagement

/// Usage counters, kept on this Mac only.
struct UsageStats: Codable {
    var talkCount = 0
    var talkSeconds = 0.0
    var idleOffCount = 0
    var idleOffSeconds = 0.0
    var restoreCount = 0
    var since = Date()
}

/// The app logic: headphone state, talk mode, battery saver and stats.
///
/// Talk trigger: a headphone button is set to "Spatial Audio". The app keeps a
/// "chosen" Spatial Audio mode. When the headphones report a different mode on
/// their own (seq 0xFF), that was a button press: we switch Spatial Audio back
/// to the chosen mode and toggle talk mode.
///
/// Talk on:  remember noise mode + volume, set Transparency, lower the volume.
/// Talk off: restore the exact noise mode and the volume from before.
///
/// Battery saver: after 10 minutes with nothing playing, noise control goes Off.
/// When audio plays again, the old mode comes back.
final class TalkMode: ObservableObject {
    static let idleSeconds: TimeInterval = 10 * 60

    @Published var enabled: Bool { didSet { UserDefaults.standard.set(enabled, forKey: "enabled") } }
    @Published var talkVolumePercent: Int { didSet { UserDefaults.standard.set(talkVolumePercent, forKey: "talkVolume") } }
    @Published var idleSaver: Bool {
        didSet {
            UserDefaults.standard.set(idleSaver, forKey: "idleSaver")
            if idleSaver { timerStart = Date() } else { restoreFromIdle() }
        }
    }

    @Published private(set) var active = false
    @Published private(set) var talkSince: Date?
    @Published private(set) var status = "Starting..."
    @Published private(set) var connected = false
    @Published private(set) var ancMode: UInt8?
    @Published private(set) var spatial: UInt8?
    @Published private(set) var battery: Int?
    @Published private(set) var charging = false

    @Published private(set) var playing = false
    @Published private(set) var quietSince: Date?
    @Published private(set) var idleOffSince: Date?
    @Published private(set) var stats: UsageStats

    /// Login item as a launch agent: starts at login and is restarted by macOS
    /// if it crashes. Choosing Quit is a clean exit, so it stays quit.
    static let agent = SMAppService.agent(plistName: "dev.georgerahul.cmftalkmode.agent.plist")

    @Published var launchAtLogin = TalkMode.agent.status == .enabled {
        didSet {
            guard launchAtLogin != (TalkMode.agent.status == .enabled) else { return }
            do {
                if launchAtLogin { try TalkMode.agent.register() } else { try TalkMode.agent.unregister() }
            } catch {
                log("login item: \(error)")
                DispatchQueue.main.async { self.launchAtLogin = TalkMode.agent.status == .enabled }
            }
        }
    }

    let headset = Headset()
    private var savedAnc: UInt8?
    private var savedVolume: Float?
    private var lastPress = Date.distantPast
    private var spatialChosen: UInt8?
    private var idleSavedAnc: UInt8?
    /// When the 10 minute countdown started (last audio, or noise control turned on).
    private var timerStart = Date()
    private var tick: Timer?

    init() {
        let d = UserDefaults.standard
        enabled = d.object(forKey: "enabled") as? Bool ?? true
        talkVolumePercent = d.object(forKey: "talkVolume") as? Int ?? 10
        idleSaver = d.object(forKey: "idleSaver") as? Bool ?? true
        stats = (d.data(forKey: "stats")).flatMap { try? JSONDecoder().decode(UsageStats.self, from: $0) } ?? UsageStats()
        headset.onState = { [weak self] s in DispatchQueue.main.async { self?.stateChanged(s) } }
        headset.onFrame = { [weak self] f in DispatchQueue.main.async { self?.handle(f) } }
        headset.start()
        // Checking "is audio playing" every 2 s costs next to nothing.
        tick = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in self?.checkIdle() }
        tick?.tolerance = 1
    }

    // MARK: commands from the menu

    func setAnc(_ m: UInt8) {
        headset.send(Proto.ancSet, [0x01, m, 0x00])
        ancMode = m
        if active && m != Proto.ancTransparency { savedAnc = m }
        clearIdle(restored: false)
        timerStart = Date()
    }

    func setSpatial(_ m: UInt8) {
        spatialChosen = m
        spatial = m
        headset.send(Proto.spatialSet, [m])
    }

    func toggle() {
        if active { stop() } else { start() }
    }

    func resetStats() {
        stats = UsageStats()
        saveStats()
    }

    // MARK: headphones

    private func stateChanged(_ s: String) {
        log("state: \(s)")
        status = s
        let now = s == "Connected"
        if now && !connected { spatialChosen = nil; timerStart = Date() }
        if !now {
            clearIdle(restored: false)
            ancMode = nil; battery = nil; spatial = nil; charging = false; quietSince = nil
        }
        connected = now
    }

    private func handle(_ f: Frame) {
        switch f.type {
        case Proto.ancNotify, Proto.ancReply:
            guard f.payload.count >= 2 else { return }
            let m = f.payload[1]
            if m != ancMode && m != Proto.ancOff { clearIdle(restored: false); timerStart = Date() }
            ancMode = m
        case Proto.batteryNotify, Proto.batteryReply:
            // payload: count, then (id, level) pairs; level bit 7 = charging
            if f.payload.count >= 3 {
                battery = Int(f.payload[2] & 0x7F)
                charging = f.payload[2] & 0x80 != 0
            }
        case Proto.spatialNotify:
            if let v = f.payload.first { spatialReport(v, seq: f.seq) }
        default:
            break
        }
    }

    private func spatialReport(_ v: UInt8, seq: UInt8) {
        guard let chosen = spatialChosen, seq == Proto.unsolicitedSeq else {
            // Reply to our own question, or first report: that is the chosen mode.
            spatialChosen = v; spatial = v; return
        }
        if v == chosen { spatial = v; return }
        // Talk mode off: the button just works as Spatial Audio.
        guard enabled else { spatialChosen = v; spatial = v; return }
        headset.send(Proto.spatialSet, [chosen]) // undo the button's Spatial Audio change
        guard Date().timeIntervalSince(lastPress) > 0.7 else { return }
        lastPress = Date()
        toggle()
    }

    // MARK: talk mode

    private func start() {
        savedAnc = idleSavedAnc ?? ancMode
        clearIdle(restored: false)
        savedVolume = SystemVolume.get()
        log("talk ON (saved mode \(savedAnc.map { String(format: "0x%02X", $0) } ?? "-"), volume \(savedVolume ?? -1))")
        headset.send(Proto.ancSet, [0x01, Proto.ancTransparency, 0x00])
        SystemVolume.set(Float(talkVolumePercent) / 100)
        active = true
        talkSince = Date()
        stats.talkCount += 1
        saveStats()
    }

    private func stop() {
        log("talk OFF (restoring mode \(savedAnc.map { String(format: "0x%02X", $0) } ?? "-"), volume \(savedVolume ?? -1))")
        if let m = savedAnc { headset.send(Proto.ancSet, [0x01, m, 0x00]); ancMode = m }
        if let v = savedVolume { SystemVolume.set(v) }
        active = false
        if let t = talkSince { stats.talkSeconds += Date().timeIntervalSince(t) }
        talkSince = nil
        timerStart = Date()
        saveStats()
    }

    // MARK: battery saver

    /// When noise control will be turned off, if the countdown is running.
    var idleDeadline: Date? {
        guard idleSaver, connected, !playing, !active, idleOffSince == nil,
              let m = ancMode, m != Proto.ancOff else { return nil }
        return max(timerStart, quietSince ?? timerStart).addingTimeInterval(Self.idleSeconds)
    }

    private func checkIdle() {
        let p = SystemVolume.isPlaying()
        if p != playing { playing = p }
        if p {
            quietSince = nil
            restoreFromIdle()
            return
        }
        guard connected else { return }
        if quietSince == nil { quietSince = Date() }
        if let d = idleDeadline, Date() >= d, let m = ancMode {
            log("idle: noise control off (was 0x\(String(format: "%02X", m)))")
            idleSavedAnc = m
            headset.send(Proto.ancSet, [0x01, Proto.ancOff, 0x00])
            ancMode = Proto.ancOff
            idleOffSince = Date()
            stats.idleOffCount += 1
            saveStats()
        }
        objectWillChange.send() // the countdown moves
    }

    private func restoreFromIdle() {
        guard let m = idleSavedAnc else { return }
        clearIdle(restored: true)
        if connected && ancMode == Proto.ancOff {
            log("audio back: restore 0x\(String(format: "%02X", m))")
            headset.send(Proto.ancSet, [0x01, m, 0x00])
            ancMode = m
        }
    }

    private func clearIdle(restored: Bool) {
        guard idleSavedAnc != nil else { return }
        idleSavedAnc = nil
        if let t = idleOffSince { stats.idleOffSeconds += Date().timeIntervalSince(t) }
        if restored { stats.restoreCount += 1 }
        idleOffSince = nil
        saveStats()
    }

    private func saveStats() {
        if let d = try? JSONEncoder().encode(stats) { UserDefaults.standard.set(d, forKey: "stats") }
    }

    // MARK: names

    var ancName: String { Self.ancName(ancMode) }

    static func ancName(_ m: UInt8?) -> String {
        switch m {
        case nil: return "-"
        case 0x05: return "Off"
        case 0x07: return "Transparency"
        case 0x01: return "ANC · High"
        case 0x02: return "ANC · Mid"
        case 0x03: return "ANC · Low"
        case 0x04: return "ANC · Adaptive"
        case let m?: return String(format: "Mode 0x%02X", m)
        }
    }
}

func log(_ s: String) {
    let line = "\(Date().formatted(date: .omitted, time: .standard)) \(s)\n"
    FileHandle.standardError.write(line.data(using: .utf8)!)
}
