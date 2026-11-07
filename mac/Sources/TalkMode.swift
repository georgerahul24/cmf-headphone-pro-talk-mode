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
/// Battery saver: after a set time (10 minutes by default) with nothing playing, noise control goes Off.
/// When audio plays again, the old mode comes back.
final class TalkMode: ObservableObject {

    @Published var enabled: Bool { didSet { UserDefaults.standard.set(enabled, forKey: "enabled"); synced("talk.enabled") } }
    @Published var talkVolumePercent: Int { didSet { UserDefaults.standard.set(talkVolumePercent, forKey: "talkVolume"); synced("talk.volume") } }
    /// While talking: true = pause the music, false = lower the volume.
    @Published var talkPause: Bool { didSet { UserDefaults.standard.set(talkPause, forKey: "talkPause"); synced("talk.pause") } }
    /// EQ: "auto", "movie", "pop", "rock" or "flat".
    @Published var eqMode: String { didSet { UserDefaults.standard.set(eqMode, forKey: "eqMode"); eqSettingChanged() } }
    /// Preset Auto uses for music: "pop" or "rock".
    @Published var musicEq: String { didSet { UserDefaults.standard.set(musicEq, forKey: "musicEq"); eqSettingChanged() } }
    /// What's playing, and the remembered name Auto matched, if any.
    @Published private(set) var song: NowPlayingInfo.Song?
    @Published private(set) var songIsVideo = false
    @Published private(set) var learnedFor: String?
    private var lastSongCheck = Date.distantPast
    private var lastSongApp = ""
    private var lastAutoCheck = Date.distantPast
    /// Notify when the headphone battery drops below 10%.
    @Published var alertBattery: Bool { didSet { UserDefaults.standard.set(alertBattery, forKey: "alertBattery"); synced("alert.battery") } }
    /// Notify about loud listening and the weekly safe amount.
    @Published var alertHearing: Bool { didSet { UserDefaults.standard.set(alertHearing, forKey: "alertHearing"); synced("alert.hearing") } }
    private var lowWarned = false
    private var lastHearTick: Date?
    /// Check GitHub for new versions and install them.
    @Published var autoUpdate: Bool { didSet { UserDefaults.standard.set(autoUpdate, forKey: "autoUpdate"); synced("update.auto") } }
    /// EQ preset on the headphones now (an `Eq` raw value), and the app Auto saw playing.
    @Published private(set) var eqNow: String?
    @Published private(set) var eqApp: String?
    @Published var idleSaver: Bool {
        didSet {
            UserDefaults.standard.set(idleSaver, forKey: "idleSaver")
            if idleSaver { timerStart = Date() } else { restoreFromIdle() }
            synced("saver.on")
        }
    }

    /// Minutes with nothing playing before the battery saver acts.
    @Published var idleMinutes: Int {
        didSet { UserDefaults.standard.set(idleMinutes, forKey: "idleMinutes"); timerStart = Date(); synced("saver.minutes") }
    }
    var idleSeconds: TimeInterval { TimeInterval(idleMinutes * 60) }

    @Published private(set) var active = false
    @Published private(set) var talkSince: Date?
    @Published private(set) var status = "Starting..."
    @Published private(set) var connected = false
    @Published private(set) var ancMode: UInt8? { didSet { if let m = ancMode, connected { History.shared.log("A", Int(m)) } } }
    @Published private(set) var spatial: UInt8?
    @Published private(set) var battery: Int? { didSet { if let b = battery { History.shared.log("B", b) } } }
    @Published private(set) var charging = false

    @Published private(set) var playing = false { didSet { if playing != oldValue { DockIcon.shared.wake() } } }
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
    /// Posted by a second copy of the app (opened from Applications) to show the window.
    static let showWindow = Notification.Name("dev.georgerahul.cmftalkmode.show")
    private var savedAnc: UInt8?
    private var savedVolume: Float?
    /// True if talk mode paused the music, so ending it should play again.
    private var pausedMusic = false
    private var lastPress = Date.distantPast
    private var spatialChosen: UInt8?
    private var idleSavedAnc: UInt8?
    /// When the 10 minute countdown started (last audio, or noise control turned on).
    private var timerStart = Date()
    private var tick: Timer?
    private var updateTimer: Timer?
    private var eqApplied: String?
    private var autoPick: String?

    init() {
        let d = UserDefaults.standard
        enabled = d.object(forKey: "enabled") as? Bool ?? true
        talkVolumePercent = d.object(forKey: "talkVolume") as? Int ?? 10
        talkPause = d.object(forKey: "talkPause") as? Bool ?? false
        idleSaver = d.object(forKey: "idleSaver") as? Bool ?? true
        idleMinutes = d.object(forKey: "idleMinutes") as? Int ?? 10
        eqMode = EqMode.normalize(d.string(forKey: "eqMode") ?? EqMode.auto)
        musicEq = d.string(forKey: "musicEq") ?? Eq.pop.rawValue
        autoUpdate = d.object(forKey: "autoUpdate") as? Bool ?? true
        alertBattery = d.object(forKey: "alertBattery") as? Bool ?? true
        alertHearing = d.object(forKey: "alertHearing") as? Bool ?? true
        stats = (d.data(forKey: "stats")).flatMap { try? JSONDecoder().decode(UsageStats.self, from: $0) } ?? UsageStats()
        headset.onState = { [weak self] s in DispatchQueue.main.async { self?.stateChanged(s) } }
        headset.onFrame = { [weak self] f in DispatchQueue.main.async { self?.handle(f) } }
        headset.start()
        MainWindowController.shared.talk = self
        hookSync()
        Notify.ask()
        DistributedNotificationCenter.default().addObserver(forName: Self.showWindow, object: nil, queue: .main) { _ in
            MainWindowController.shared.show()
        }
        // Checking "is audio playing" every 2 s costs next to nothing.
        // Every 3 s, with slack so macOS can group it with other wakeups.
        tick = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] _ in self?.checkIdle() }
        tick?.tolerance = 1.5
        // Updates: first check a minute after start, then every 6 hours.
        DispatchQueue.main.asyncAfter(deadline: .now() + 60) { [weak self] in self?.updateCheck() }
        updateTimer = Timer.scheduledTimer(withTimeInterval: 6 * 3600, repeats: true) { [weak self] _ in self?.updateCheck() }
    }

    private func updateCheck() {
        if autoUpdate && !active { Updater.shared.check(install: true) }
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

    func connect() { headset.connectNow() }

    func toggle() {
        if active { stop() } else { start() }
    }

    func resetStats() {
        stats = UsageStats()
        saveStats()
        Sync.shared.statsReset()
    }

    // MARK: sync

    /// True while a setting from the phone is being applied, so it isn't sent back.
    private var applyingRemote = false

    private func synced(_ key: String) {
        if !applyingRemote { Sync.shared.settingChanged(key) }
    }

    /// This Mac's and the phone's counters added up.
    var allStats: UsageStats {
        guard let p = Sync.shared.peerStats else { return stats }
        var s = stats
        s.talkCount += p.talkCount; s.talkSeconds += p.talkSeconds
        s.idleOffCount += p.idleOffCount; s.idleOffSeconds += p.idleOffSeconds
        s.restoreCount += p.restoreCount
        s.since = min(s.since, p.since)
        return s
    }

    private func hookSync() {
        let sync = Sync.shared
        sync.settingValue = { [unowned self] k in
            switch k {
            case "talk.enabled": return enabled
            case "talk.volume": return talkVolumePercent
            case "talk.pause": return talkPause
            case "saver.on": return idleSaver
            case "saver.minutes": return idleMinutes
            case "eq.mode": return eqMode
            case "eq.music": return musicEq
            case "alert.battery": return alertBattery
            case "alert.hearing": return alertHearing
            case "update.auto": return autoUpdate
            default: return nil
            }
        }
        sync.applySetting = { [unowned self] k, v in
            let b = (v as? NSNumber)?.boolValue, i = (v as? NSNumber)?.intValue, str = v as? String
            applyingRemote = true
            defer { applyingRemote = false }
            switch k {
            case "talk.enabled": if let b { enabled = b }
            case "talk.volume": if let i { talkVolumePercent = max(0, min(100, i)) }
            case "talk.pause": if let b { talkPause = b }
            case "saver.on": if let b { idleSaver = b }
            case "saver.minutes": if let i, i > 0 { idleMinutes = i }
            case "eq.mode": if let str, EqMode.all.contains(EqMode.normalize(str)) { eqMode = EqMode.normalize(str) }
            case "eq.music": if let str, Eq(rawValue: str) != nil { musicEq = str }
            case "alert.battery": if let b { alertBattery = b }
            case "alert.hearing": if let b { alertHearing = b }
            case "update.auto": if let b { autoUpdate = b }
            default: break
            }
        }
        sync.ownStats = { [unowned self] in stats }
        sync.resetOwnStats = { [unowned self] at in
            stats = UsageStats()
            stats.since = at
            saveStats()
        }
        Learned.shared.onChange = { sync.learnedChanged() }
        sync.learnedApplied = { [unowned self] in lastSongCheck = .distantPast; autoEq() }
        sync.start()
    }

    // MARK: headphones

    private func stateChanged(_ s: String) {
        log("state: \(s)")
        status = s
        let now = s == "Connected"
        if now && !connected { spatialChosen = nil; timerStart = Date(); History.shared.log("C", 1) }
        if !now && connected { History.shared.log("C", 0); Hearing.shared.flush() }
        let fresh = now && !connected
        if !now {
            clearIdle(restored: false)
            ancMode = nil; battery = nil; spatial = nil; charging = false; quietSince = nil; eqNow = nil
        }
        connected = now
        if fresh { eqApplied = nil; applyEq() }
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
                checkLowBattery()
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
        headset.send(Proto.ancSet, [0x01, Proto.ancTransparency, 0x00])
        if talkPause {
            savedVolume = nil
            pausedMusic = SystemVolume.isPlaying()
            if pausedMusic { NowPlaying.pause() }
            log("talk ON (saved mode \(savedAnc.map { String(format: "0x%02X", $0) } ?? "-"), paused music: \(pausedMusic))")
        } else {
            pausedMusic = false
            savedVolume = SystemVolume.get()
            log("talk ON (saved mode \(savedAnc.map { String(format: "0x%02X", $0) } ?? "-"), volume \(savedVolume ?? -1))")
            SystemVolume.set(Float(talkVolumePercent) / 100)
        }
        active = true
        talkSince = Date()
        stats.talkCount += 1
        saveStats()
    }

    private func stop() {
        log("talk OFF (restoring mode \(savedAnc.map { String(format: "0x%02X", $0) } ?? "-"), volume \(savedVolume ?? -1))")
        if let m = savedAnc { headset.send(Proto.ancSet, [0x01, m, 0x00]); ancMode = m }
        if let v = savedVolume { SystemVolume.set(v) }
        if pausedMusic { NowPlaying.play() }
        pausedMusic = false
        active = false
        if let t = talkSince { stats.talkSeconds += Date().timeIntervalSince(t) }
        talkSince = nil
        timerStart = Date()
        saveStats()
    }

    // MARK: EQ

    private func eqSettingChanged() {
        if let a = autoPick, a != Eq.movie.rawValue { autoPick = musicEq }
        autoEq()
        applyEq()
    }

    private var wantedEq: String { eqMode == EqMode.auto ? (autoPick ?? musicEq) : eqMode }

    /// Auto: your remembered pick for this show, film, song or artist if there is one; otherwise
    /// Movie while a video app plays and the music preset for music (browsers like Zen count as
    /// music). Nothing playing: no change. Also keeps the "Now playing" card up to date.
    private func autoEq() {
        guard playing, let k = PlayingApps.kind() else { return }
        if eqApp != k.name { eqApp = k.name }
        if songIsVideo != k.video { songIsVideo = k.video }
        // Reading the song starts a small helper, so: when the playing app changes, else every 20 s.
        if k.id != lastSongApp || Date().timeIntervalSince(lastSongCheck) > 20 {
            lastSongCheck = Date(); lastSongApp = k.id
            // The system's Now Playing works for every app. It's read in the background and used
            // only when it belongs to the app that is playing (its helper processes count too).
            NowPlayingInfo.refreshSystem { [weak self] in self?.lastSongCheck = .distantPast; self?.autoEq() }
            var s: NowPlayingInfo.Song?
            if let sys = NowPlayingInfo.system, !sys.app.isEmpty,
               k.id.hasPrefix(sys.app) || sys.app.hasPrefix(k.id) || (k.id.hasPrefix("com.apple.WebKit") && sys.app == "com.apple.Safari") {
                s = sys.song
            } else if NowPlayingInfo.canRead(k.id) {
                s = NowPlayingInfo.song(in: k.id)
            }
            if s != song { song = s }
        }
        guard eqMode == EqMode.auto else { return }
        let learned = Learned.shared.lookup(title: song?.title, artist: song?.artist)
        if learnedFor != learned?.name { learnedFor = learned?.name }
        autoPick = learned?.preset ?? (k.video ? Eq.movie.rawValue : musicEq)
        applyEq()
    }

    /// Re-checks Auto right away (after a remembered pick changed).
    func refreshAuto() { lastSongCheck = .distantPast; autoEq() }

    private func applyEq() {
        guard connected else { return }
        let w = wantedEq
        guard w != eqApplied else { return }
        eqApplied = w
        eqNow = w
        log("EQ: \(w)")
        // Every preset, Flat included, is a custom EQ, so they are all measured from flat.
        let eq = Eq(rawValue: w) ?? .flat
        headset.send(Proto.eqCustomSet, eq.payload)
        headset.send(Proto.eqPresetSet, [Proto.eqCustom, 0x00])
    }

    /// "Auto (Movie)" in Auto mode, else the preset name.
    var eqTitle: String {
        eqMode == EqMode.auto ? "Auto (\(EqMode.label(eqNow ?? musicEq)))" : EqMode.label(eqMode)
    }

    // MARK: reminders

    /// One reminder when the battery drops below 10%; again only after it charged back up.
    private func checkLowBattery() {
        guard let b = battery else { return }
        if charging || b >= 20 { lowWarned = false; return }
        if b < 10, !lowWarned, alertBattery {
            lowWarned = true
            Notify.show("battery", "Headphone battery at \(b)%", "Time to charge your CMF Headphone Pro.")
        }
    }

    /// Adds listening time to the hearing dose while this Mac plays through the headphones.
    private func hearingTick() {
        let now = Date()
        defer { lastHearTick = playing && connected ? now : nil }
        guard playing, connected, let last = lastHearTick else { return }
        let dt = min(6, now.timeIntervalSince(last))
        if let msg = Hearing.shared.add(volume: Double(SystemVolume.get() ?? 0), seconds: dt, now: now), alertHearing {
            Notify.show("hearing", "Hearing safety", msg)
        }
    }

    /// This week's hearing dose from both devices (1.0 = the whole safe amount).
    var allDose: Double { Hearing.shared.week() + Sync.shared.peerDose }

    /// The battery forecast, re-worked out every 10 minutes or when the level or mode changes.
    func batteryForecast(now: Date) -> BatteryPredict.Forecast? {
        guard let b = battery else { return nil }
        let minute = Int(now.timeIntervalSince1970 / 600)
        if let c = predictCache, c.minute == minute, c.level == b, c.anc == ancMode { return c.value }
        let v = BatteryPredict.forecast(level: b, anc: ancMode, sources: History.shared.loadAll(), now: now)
        predictCache = (minute, b, ancMode, v)
        return v
    }
    private var predictCache: (minute: Int, level: Int, anc: UInt8?, value: BatteryPredict.Forecast)?

    /// Talk mode ran longer than the battery saver time: you most likely took the headphones off to
    /// talk. Ends talk mode with the music paused (never left playing) and noise control Off; your
    /// old noise mode comes back when audio plays again, like the battery saver.
    private func talkTimedOut() {
        guard active, connected else { return }
        log("talk mode timed out: music paused, noise control off")
        if !pausedMusic && SystemVolume.isPlaying() { NowPlaying.pause() }
        if let v = savedVolume { SystemVolume.set(v) }
        savedVolume = nil
        pausedMusic = false // stays paused
        idleSavedAnc = savedAnc ?? ancMode
        headset.send(Proto.ancSet, [0x01, Proto.ancOff, 0x00])
        ancMode = Proto.ancOff
        idleOffSince = Date()
        stats.idleOffCount += 1
        active = false
        if let t = talkSince { stats.talkSeconds += Date().timeIntervalSince(t) }
        talkSince = nil
        saveStats()
    }

    // MARK: battery saver

    /// When noise control will be turned off, if the countdown is running.
    var idleDeadline: Date? {
        guard idleSaver, connected, !playing, !active, idleOffSince == nil,
              let m = ancMode, m != Proto.ancOff else { return nil }
        return max(timerStart, quietSince ?? timerStart).addingTimeInterval(idleSeconds)
    }

    private func checkIdle() {
        let p = SystemVolume.isPlaying()
        let started = p && !playing
        if !p && playing { Hearing.shared.flush() } // audio stopped: save the listening time
        if p != playing { playing = p }
        hearingTick()
        if p {
            if quietSince != nil { quietSince = nil }
            restoreFromIdle()
            // Which app is playing: right away when audio starts, then every 6 s.
            if started || Date().timeIntervalSince(lastAutoCheck) >= 6 { lastAutoCheck = Date(); autoEq() }
            return
        }
        // Talking longer than the battery saver time: you probably took the headphones off.
        if active, idleSaver, let t = talkSince, Date().timeIntervalSince(t) >= idleSeconds { talkTimedOut() }
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
        // No forced redraw here: the screens tick their own clock while they're open.
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
        Sync.shared.statsChanged()
    }

    // MARK: history

    /// Day picked in the history charts (6 = today).
    @Published var historyDay = 6
    /// Battery point under the mouse in the battery chart.
    @Published var batteryHover: Date?
    private var historyCache: (version: Int, minute: Int, view: HistoryView)?

    /// The history, re-read when something new is logged and once a minute.
    func history(now: Date) -> HistoryView {
        let minute = Int(now.timeIntervalSince1970 / 60)
        if let c = historyCache, c.version == History.shared.version, c.minute == minute { return c.view }
        let v = HistoryView(History.shared.loadAll(), now: now)
        historyCache = (History.shared.version, minute, v)
        return v
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
