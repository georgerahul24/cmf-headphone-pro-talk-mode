import Foundation
import Combine
import ServiceManagement

/// The talk-mode logic.
///
/// Trigger: one headphone button is set to "Spatial Audio" (the app can do this
/// itself, see `setTrigger`). A press
/// makes the headphones report Spatial Audio as on (seq 0xFF = from the
/// headphones). We switch Spatial Audio back off and toggle talk mode. Reports of
/// Spatial Audio being off are ignored, so our own switch-back never triggers.
///
/// Talk on:  remember current noise mode + volume, set Transparency, volume 10%.
/// Talk off: restore the exact noise mode (Off / Transparency / any ANC level)
///           and the volume from before.
final class TalkMode: ObservableObject {
    @Published var enabled: Bool { didSet { UserDefaults.standard.set(enabled, forKey: "enabled") } }
    @Published var talkVolumePercent: Int { didSet { UserDefaults.standard.set(talkVolumePercent, forKey: "talkVolume") } }
    @Published private(set) var active = false
    @Published private(set) var status = "Starting..."
    @Published private(set) var ancMode: UInt8?
    @Published private(set) var battery: Int?
    /// Current action for each button slot, keyed by ButtonSlot.id.
    @Published private(set) var buttons: [UInt32: UInt8] = [:]
    /// Which slot the app set up as the talk button ("" = none).
    @Published private(set) var triggerKey: String = UserDefaults.standard.string(forKey: "triggerSlot") ?? ""

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

    init() {
        let d = UserDefaults.standard
        enabled = d.object(forKey: "enabled") as? Bool ?? true
        talkVolumePercent = d.object(forKey: "talkVolume") as? Int ?? 10
        headset.onState = { [weak self] s in
            DispatchQueue.main.async {
                log("state: \(s)")
                self?.status = s
                if s != "Connected" { self?.ancMode = nil; self?.battery = nil; self?.buttons = [:] }
            }
        }
        headset.onFrame = { [weak self] f in DispatchQueue.main.async { self?.handle(f) } }
        headset.start()
    }

    private func handle(_ f: Frame) {
        log(String(format: "rx type=0x%04X seq=%d ", f.type, f.seq) + f.payload.map { String(format: "%02X", $0) }.joined(separator: " "))
        switch f.type {
        case Proto.ancNotify, Proto.ancReply:
            if f.payload.count >= 2 { ancMode = f.payload[1] }
        case Proto.batteryNotify, Proto.batteryReply:
            // payload: count, then (id, level) pairs; level bit 7 = charging
            if f.payload.count >= 3 { battery = Int(f.payload[2] & 0x7F) }
        case Proto.gesturesReply:
            // payload: count, then (device, button, gesture, action) groups
            guard let n = f.payload.first else { return }
            var map: [UInt32: UInt8] = [:]
            for i in 0..<Int(n) {
                let o = 1 + i * 4
                guard o + 3 < f.payload.count else { break }
                let p = f.payload
                map[UInt32(p[o]) << 16 | UInt32(p[o + 1]) << 8 | UInt32(p[o + 2])] = p[o + 3]
            }
            buttons = map
        case Proto.gesturesSetAck:
            headset.send(Proto.gesturesGet)
        case Proto.spatialNotify:
            guard let v = f.payload.first, v != 0 else { return }
            guard enabled else { return }
            headset.send(Proto.spatialSet, [0x00]) // undo the Spatial Audio change
            if f.seq == Proto.unsolicitedSeq { pressed() }
        default:
            break
        }
    }

    private func pressed() {
        // Ignore double reports from one press.
        guard Date().timeIntervalSince(lastPress) > 0.7 else { return }
        lastPress = Date()
        toggle()
    }

    // ---- talk button setup ----

    func action(of slot: ButtonSlot) -> UInt8? { buttons[slot.id] }

    private func originalKey(_ slot: ButtonSlot) -> String { "original.\(slot.key)" }

    /// Makes `slot` the talk button: remembers what it did, sets it to Spatial
    /// Audio. Puts the previous talk button back first.
    func setTrigger(_ slot: ButtonSlot) {
        if let old = ButtonSlot.byKey(triggerKey), old != slot { restoreButton(old) }
        let d = UserDefaults.standard
        if let cur = action(of: slot), cur != ButtonAction.spatialAudio, d.object(forKey: originalKey(slot)) == nil {
            d.set(Int(cur), forKey: originalKey(slot))
        }
        headset.send(Proto.gesturesSet, [0x01, slot.device, slot.button, slot.gesture, ButtonAction.spatialAudio])
        triggerKey = slot.key
        d.set(slot.key, forKey: "triggerSlot")
    }

    /// Puts the talk button back to what it did before.
    func restoreTrigger() {
        if let slot = ButtonSlot.byKey(triggerKey) { restoreButton(slot) }
        triggerKey = ""
        UserDefaults.standard.removeObject(forKey: "triggerSlot")
    }

    private func restoreButton(_ slot: ButtonSlot) {
        let d = UserDefaults.standard
        guard let orig = d.object(forKey: originalKey(slot)) as? Int else { return }
        headset.send(Proto.gesturesSet, [0x01, slot.device, slot.button, slot.gesture, UInt8(orig)])
        d.removeObject(forKey: originalKey(slot))
    }

    func originalName(_ slot: ButtonSlot) -> String? {
        (UserDefaults.standard.object(forKey: originalKey(slot)) as? Int).map { ButtonAction.name(UInt8($0)) }
    }

    // ---- talk mode ----

    func toggle() {
        if active { stop() } else { start() }
    }

    private func start() {
        log("talk ON (saved mode \(ancName), volume \(SystemVolume.get() ?? -1))")
        savedAnc = ancMode
        savedVolume = SystemVolume.get()
        headset.send(Proto.ancSet, [0x01, Proto.ancTransparency, 0x00])
        SystemVolume.set(Float(talkVolumePercent) / 100)
        active = true
    }

    private func stop() {
        log("talk OFF (restoring mode \(savedAnc.map { String(format: "0x%02X", $0) } ?? "-"), volume \(savedVolume ?? -1))")
        if let m = savedAnc { headset.send(Proto.ancSet, [0x01, m, 0x00]) }
        if let v = savedVolume { SystemVolume.set(v) }
        active = false
    }

    var ancName: String {
        switch ancMode {
        case nil: return "-"
        case 0x05: return "Off"
        case 0x07: return "Transparency"
        case 0x01: return "Noise cancelling (high)"
        case 0x02: return "Noise cancelling (mid)"
        case 0x03: return "Noise cancelling (low)"
        case 0x04: return "Noise cancelling (adaptive)"
        case let m?: return String(format: "Mode 0x%02X", m)
        }
    }
}

func log(_ s: String) {
    let line = "\(Date().formatted(date: .omitted, time: .standard)) \(s)\n"
    FileHandle.standardError.write(line.data(using: .utf8)!)
}
