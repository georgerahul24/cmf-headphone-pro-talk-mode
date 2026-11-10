import Foundation
import Network
import CryptoKit

/// One paired device (a phone or a TV), saved between launches.
struct PeerInfo: Codable, Identifiable {
    var id: String
    var name: String
    var secret: Data
    /// How many of this Mac's own history lines the device has.
    var has = 0
    /// How many of the device's history lines this Mac has.
    var haveFrom = 0
    /// The device's own counters and hearing dose, from its last sync.
    var stats: UsageStats?
    var dose = 0.0
    var lastSync: Date?
    /// Settings ("eq.learned" for remembered picks) changed here and not yet sent to the device.
    var dirty: Set<String> = []
    var statsDirty = false

    var historyWaiting: Int { max(0, History.shared.ownCount - has) }
    var everythingSynced: Bool { historyWaiting == 0 && dirty.isEmpty && !statsDirty }

    /// "34 history events, 2 settings and stats waiting to sync", or nil when nothing is waiting.
    var waitingText: String? {
        var parts: [String] = []
        let h = historyWaiting
        if h > 0 { parts.append("\(h) history event\(h == 1 ? "" : "s")") }
        if !dirty.isEmpty { parts.append("\(dirty.count) setting\(dirty.count == 1 ? "" : "s")") }
        if statsDirty { parts.append("stats") }
        guard !parts.isEmpty else { return nil }
        let list = parts.count == 1 ? parts[0] : parts.dropLast().joined(separator: ", ") + " and " + parts.last!
        return "\(list) waiting to sync"
    }
}

/// Syncs everything with the paired phones and TVs over the local network: history, stats and settings.
///
/// The Mac listens (Bonjour "_cmftalk._tcp"); each device finds it and keeps a link open while
/// both are on the same network, so changes go across within seconds. Several devices can be
/// paired at once (say a phone and a Fire TV); the Mac is the hub between them.
///
/// Pairing (once per device): the Mac shows a 6-digit code. Both sides make an X25519 key pair and
/// swap the public keys; the shared secret is HKDF(X25519 result, salt = code). Each side proves it
/// has the same secret with an HMAC before it is saved. After that, every message is AES-GCM encrypted.
///
/// What each side has:
/// - History: each device appends to its own log; the Mac keeps an exact copy of each device's log,
///   line for line, and each device keeps a copy of the Mac's. "have" = how many of the other
///   side's lines I hold, so only missing lines are sent.
/// - Settings: each has a change time; the newer one wins. A change from one device is passed on
///   to the others.
/// - Stats: each device sends its own counters. The Mac sends each device its own counters plus
///   every other device's, so every screen shows the total. A reset resets all of them.
///
/// Wire format: 4-byte big-endian length, then the message. Plain JSON until "hi"; after that
/// AES-GCM (12-byte nonce + ciphertext + 16-byte tag) of the JSON.
final class Sync: ObservableObject {
    static let shared = Sync()
    static let serviceType = "_cmftalk._tcp"

    /// The settings that sync, by their shared names. The EQ choice, noise control and Spatial
    /// Audio don't: each device keeps its own, since you may want different sound on each.
    static let keys = ["talk.enabled", "talk.volume", "talk.pause", "saver.on", "saver.minutes",
                       "update.auto", "alert.battery", "alert.hearing"]

    private let d = UserDefaults.standard
    let deviceId: String
    let deviceName = Host.current().localizedName ?? "Mac"

    /// Paired devices, in the order they were paired.
    @Published private(set) var peers: [PeerInfo] = []
    /// Ids of the devices connected right now.
    @Published private(set) var live: Set<String> = []
    @Published private(set) var pairCode: String?
    @Published private(set) var note: String?

    /// Hooks into the app, set by TalkMode.
    var settingValue: (String) -> Any? = { _ in nil }
    var applySetting: (String, Any) -> Void = { _, _ in }
    var ownStats: () -> UsageStats = { UsageStats() }
    var resetOwnStats: (Date) -> Void = { _ in }

    private var listener: NWListener?
    /// The open, checked link to each device, by device id.
    private var sessions: [String: Session] = [:]
    private var pending: Session?
    /// Every open connection, kept alive until it closes.
    private var open: [ObjectIdentifier: Session] = [:]
    private var pendingPeer: (id: String, name: String, secret: SymmetricKey)?
    private var pairTimer: Timer?
    private var failures = 0
    /// History lines sent to each device but not yet acknowledged.
    private var sentUpTo: [String: Int] = [:]
    private var lastHeard: [String: Date] = [:]
    private var watchdog: Timer?

    private init() {
        if let id = d.string(forKey: "sync.deviceId") { deviceId = id } else {
            deviceId = UUID().uuidString.lowercased()
            d.set(deviceId, forKey: "sync.deviceId")
        }
        if let data = d.data(forKey: "sync.peers"), let list = try? JSONDecoder().decode([PeerInfo].self, from: data) {
            peers = list
        } else if let id = d.string(forKey: "sync.peerId"), let s = d.data(forKey: "sync.secret") {
            // Before 2.13.0 the Mac kept one phone, under single keys: move it into the list.
            var p = PeerInfo(id: id, name: d.string(forKey: "sync.peerName") ?? "Phone", secret: s)
            p.has = d.integer(forKey: "sync.peerHas")
            p.dose = d.double(forKey: "sync.peerDose")
            p.dirty = Set(d.stringArray(forKey: "sync.dirty") ?? [])
            p.statsDirty = d.bool(forKey: "sync.statsDirty")
            if let ms = d.object(forKey: "sync.lastSync") as? Double { p.lastSync = Date(timeIntervalSince1970: ms / 1000) }
            if let data = d.data(forKey: "sync.peerStats") { p.stats = try? JSONDecoder().decode(UsageStats.self, from: data) }
            peers = [p]
            savePeers()
            for k in ["sync.peerId", "sync.peerName", "sync.secret", "sync.peerHas", "sync.peerDose", "sync.dirty",
                      "sync.statsDirty", "sync.lastSync", "sync.peerStats"] { d.removeObject(forKey: k) }
        }
        for i in peers.indices { peers[i].haveFrom = History.shared.peerCount(peers[i].id) }
    }

    func start() {
        History.shared.onLog = { [weak self] in self?.historyChanged() }
        if !peers.isEmpty { listen() }
        // Devices ping every minute. Silence for 2.5 minutes: it left the network.
        watchdog = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            guard let self else { return }
            for (id, s) in self.sessions where Date().timeIntervalSince(self.lastHeard[id] ?? .distantPast) > 150 { s.close() }
        }
    }

    // MARK: peers

    private func savePeers() {
        if let data = try? JSONEncoder().encode(peers) { d.set(data, forKey: "sync.peers") }
    }

    private func update(_ id: String, _ f: (inout PeerInfo) -> Void) {
        guard let i = peers.firstIndex(where: { $0.id == id }) else { return }
        f(&peers[i])
        savePeers()
    }

    func peer(_ id: String) -> PeerInfo? { peers.first { $0.id == id } }

    /// Everything from this Mac reached every paired device.
    var everythingSynced: Bool { peers.allSatisfy { $0.everythingSynced } }

    /// Every device's counters added up, leaving out [except]. Nil when there are none.
    func othersStats(except: String? = nil) -> UsageStats? {
        let list = peers.filter { $0.id != except }.compactMap { $0.stats }
        guard var s = list.first else { return nil }
        for p in list.dropFirst() {
            s.talkCount += p.talkCount; s.talkSeconds += p.talkSeconds
            s.idleOffCount += p.idleOffCount; s.idleOffSeconds += p.idleOffSeconds
            s.restoreCount += p.restoreCount
            s.since = min(s.since, p.since)
        }
        return s
    }

    /// Every device's hearing dose this week, leaving out [except].
    func othersDose(except: String? = nil) -> Double {
        peers.filter { $0.id != except }.reduce(0) { $0 + $1.dose }
    }

    // MARK: settings and stats changes from this Mac

    private var settingTs: [String: Double] {
        get { d.dictionary(forKey: "sync.ts") as? [String: Double] ?? [:] }
        set { d.set(newValue, forKey: "sync.ts") }
    }

    /// A setting was changed on this Mac.
    func settingChanged(_ key: String) {
        var ts = settingTs
        ts[key] = Date().timeIntervalSince1970 * 1000
        settingTs = ts
        markSettings([key], except: nil)
    }

    /// Settings to send to every device but [except]; sent now to the ones that are connected.
    private func markSettings(_ keys: Set<String>, except: String?) {
        for p in peers where p.id != except {
            update(p.id) { $0.dirty.formUnion(keys) }
            if live.contains(p.id) { sendSettings(p.id) }
        }
    }

    /// A remembered EQ pick changed on this Mac.
    func learnedChanged() { markLearned(except: nil) }

    private func markLearned(except: String?) {
        for p in peers where p.id != except {
            update(p.id) { $0.dirty.insert("eq.learned") }
            if let s = sessions[p.id] {
                s.send(["t": "learned", "learned": Learned.shared.all()])
                update(p.id) { $0.dirty.remove("eq.learned") }
            }
        }
    }

    /// This Mac's counters changed: every device's total changed.
    func statsChanged() { markStats(except: nil) }

    private func markStats(except: String?) {
        for p in peers where p.id != except {
            update(p.id) { $0.statsDirty = true }
            if live.contains(p.id) { let id = p.id; schedule { self.sendStats(id) } }
        }
    }

    /// Reset pressed on this Mac: resets every device's counters too.
    func statsReset() {
        d.set(Date().timeIntervalSince1970 * 1000, forKey: "sync.statsReset")
        for p in peers { update(p.id) { $0.stats = nil; $0.dose = 0 } }
        statsChanged()
    }

    private func historyChanged() {
        for id in sessions.keys { schedule { self.sendEvents(id) } }
    }

    private func schedule(_ f: @escaping () -> Void) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 1, execute: f)
    }

    // MARK: pairing

    /// Opens pairing for 5 minutes and shows a code.
    func startPairing() {
        let code = String(format: "%06d", Int.random(in: 0...999_999))
        pairCode = code
        failures = 0
        note = nil
        listen()
        pairTimer?.invalidate()
        pairTimer = Timer.scheduledTimer(withTimeInterval: 300, repeats: false) { [weak self] _ in self?.stopPairing() }
    }

    func stopPairing() {
        pairCode = nil
        pairTimer?.invalidate()
        pendingPeer = nil
        if peers.isEmpty { listener?.cancel(); listener = nil }
    }

    func unpair(_ id: String) {
        let name = peer(id)?.name ?? "the device"
        sessions[id]?.close()
        sessions[id] = nil
        live.remove(id)
        peers.removeAll { $0.id == id }
        savePeers()
        if peers.isEmpty && pairCode == nil { listener?.cancel(); listener = nil }
        note = "Unpaired \(name). History already synced from it stays on this Mac."
    }

    // MARK: network

    private func listen() {
        guard listener == nil else { return }
        do {
            let l = try NWListener(using: .tcp)
            l.service = NWListener.Service(name: "CMF Talk Mode \(deviceName)", type: Self.serviceType)
            l.newConnectionHandler = { [weak self] c in self?.accept(c) }
            l.stateUpdateHandler = { [weak self] st in
                if case .failed(let e) = st {
                    log("sync listener: \(e)")
                    DispatchQueue.main.async {
                        self?.listener = nil
                        self?.note = "Can't listen on the network. Allow Local Network for CMF Talk Mode in System Settings, Privacy & Security."
                    }
                }
            }
            l.start(queue: .main)
            listener = l
        } catch {
            log("sync listen: \(error)")
        }
    }

    private func accept(_ c: NWConnection) {
        let s = Session(c)
        open[ObjectIdentifier(s)] = s
        s.onMessage = { [weak self, weak s] m in if let s { self?.handle(m, from: s) } }
        s.onClose = { [weak self, weak s] in
            guard let self, let s else { return }
            self.open[ObjectIdentifier(s)] = nil
            if let id = s.peerId, self.sessions[id] === s { self.sessions[id] = nil; self.live.remove(id) }
            if self.pending === s { self.pending = nil }
        }
        s.start()
    }

    // MARK: messages

    private func handle(_ m: [String: Any], from s: Session) {
        guard let t = m["t"] as? String else { return }
        if s.key == nil {
            switch t {
            case "pair": pair(m, s)
            case "pairCheck": pairCheck(m, s)
            case "hi":
                guard let id = m["id"] as? String, let p = peer(id) else {
                    s.send(["t": "err", "msg": "This Mac isn't paired with this device. Pair again."]); s.close(); return
                }
                sessions[id]?.close()
                s.key = Self.sessionKey(SymmetricKey(data: p.secret))
                s.peerId = id
                sessions[id] = s
                live.insert(id)
                lastHeard[id] = Date()
                sentUpTo[id] = p.has
                note = nil
                sendState(id)
            default: s.close()
            }
            return
        }
        guard let id = s.peerId, sessions[id] === s else { return }
        lastHeard[id] = Date()
        update(id) { $0.lastSync = Date() }
        if t == "ping" { s.send(["t": "pong"]); return }
        switch t {
        case "state":
            if let n = m["name"] as? String, n != peer(id)?.name { update(id) { $0.name = n } }
            if let set = m["settings"] as? [String: Any] { applySettings(set, from: id) }
            if let l = m["learned"] as? [String: Any] { applyLearned(l, from: id) }
            applyStats(m, from: id)
            if let have = m["have"] as? Int { gotAck(id, have) }
        case "events":
            guard let from = m["from"] as? Int, let lines = m["lines"] as? [String] else { return }
            if from == History.shared.peerCount(id) { History.shared.appendPeer(id, lines) }
            let have = History.shared.peerCount(id)
            update(id) { $0.haveFrom = have }
            s.send(["t": "ack", "have": have])
        case "ack":
            if let have = m["have"] as? Int { gotAck(id, have) }
        case "settings":
            if let set = m["settings"] as? [String: Any] { applySettings(set, from: id) }
        case "stats":
            applyStats(m, from: id)
        case "learned":
            if let l = m["learned"] as? [String: Any] { applyLearned(l, from: id) }
        default:
            break
        }
    }

    private func pair(_ m: [String: Any], _ s: Session) {
        guard let code = pairCode else {
            s.send(["t": "err", "msg": "Pairing isn't open. On the Mac, click Pair a device first."]); s.close(); return
        }
        guard let id = m["id"] as? String, let name = m["name"] as? String,
              let pubB64 = m["pub"] as? String, let pubData = Data(base64Encoded: pubB64),
              let theirs = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: pubData) else { s.close(); return }
        let mine = Curve25519.KeyAgreement.PrivateKey()
        guard let shared = try? mine.sharedSecretFromKeyAgreement(with: theirs) else { s.close(); return }
        let key = shared.hkdfDerivedSymmetricKey(using: SHA256.self, salt: Data(code.utf8),
                                                 sharedInfo: Data("cmftalk-pair".utf8), outputByteCount: 32)
        pendingPeer = (id, name, key)
        pending = s
        s.send(["t": "pairOk", "id": deviceId, "name": deviceName, "pub": mine.publicKey.rawRepresentation.base64EncodedString()])
    }

    private func pairCheck(_ m: [String: Any], _ s: Session) {
        guard let p = pendingPeer, s === pending, let macB64 = m["mac"] as? String, let mac = Data(base64Encoded: macB64) else { s.close(); return }
        guard HMAC<SHA256>.isValidAuthenticationCode(mac, authenticating: Data("phone".utf8), using: p.secret) else {
            failures += 1
            s.send(["t": "err", "msg": "Wrong code. Check the code on the Mac and try again."]); s.close()
            note = "Wrong code entered on the device."
            if failures >= 3 { stopPairing(); note = "Too many wrong codes. Click Pair a device to get a new code." }
            return
        }
        let reply = Data(HMAC<SHA256>.authenticationCode(for: Data("mac".utf8), using: p.secret))
        s.send(["t": "pairDone", "mac": reply.base64EncodedString()])
        let secret = p.secret.withUnsafeBytes { Data($0) }
        if peer(p.id) != nil {
            // Paired again: new key, same history copy.
            update(p.id) { $0.name = p.name; $0.secret = secret }
        } else {
            // New device: its copy of this Mac's history starts from zero, and it gets every setting.
            var info = PeerInfo(id: p.id, name: p.name, secret: secret)
            info.haveFrom = History.shared.peerCount(p.id)
            peers.append(info)
            savePeers()
        }
        note = "Paired with \(p.name)."
        pairCode = nil; pairTimer?.invalidate(); pendingPeer = nil
    }

    static func sessionKey(_ secret: SymmetricKey) -> SymmetricKey {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: secret, salt: Data("cmftalk".utf8),
                               info: Data("cmftalk-session".utf8), outputByteCount: 32)
    }

    // MARK: sending

    private func settingsMap() -> [String: Any] {
        let ts = settingTs
        var out: [String: Any] = [:]
        for k in Self.keys {
            if let v = settingValue(k) { out[k] = ["v": v, "ts": ts[k] ?? 0] }
        }
        return out
    }

    /// This Mac's counters plus every other device's, for device [id].
    private func statsMap(for id: String) -> [String: Any] {
        var s = ownStats()
        if let o = othersStats(except: id) {
            s.talkCount += o.talkCount; s.talkSeconds += o.talkSeconds
            s.idleOffCount += o.idleOffCount; s.idleOffSeconds += o.idleOffSeconds
            s.restoreCount += o.restoreCount
            s.since = min(s.since, o.since)
        }
        return ["talkCount": s.talkCount, "talkMs": Int64(s.talkSeconds * 1000), "idleOffCount": s.idleOffCount,
                "idleOffMs": Int64(s.idleOffSeconds * 1000), "restoreCount": s.restoreCount,
                "since": Int64(s.since.timeIntervalSince1970 * 1000),
                "dose7": Hearing.shared.week() + othersDose(except: id)]
    }

    private var resetAt: Double { d.double(forKey: "sync.statsReset") }

    private func sendState(_ id: String) {
        guard let s = sessions[id], let p = peer(id) else { return }
        s.send(["t": "state", "id": deviceId, "name": deviceName, "have": p.haveFrom,
                "settings": settingsMap(), "stats": statsMap(for: id), "reset": resetAt,
                "learned": Learned.shared.all()])
        update(id) { $0.dirty.removeAll(); $0.statsDirty = false }
    }

    private func sendSettings(_ id: String) {
        guard let s = sessions[id] else { return }
        s.send(["t": "settings", "settings": settingsMap()])
        update(id) { $0.dirty = $0.dirty.intersection(["eq.learned"]) }
    }

    private func sendStats(_ id: String) {
        guard let s = sessions[id] else { return }
        s.send(["t": "stats", "stats": statsMap(for: id), "reset": resetAt])
        update(id) { $0.statsDirty = false }
    }

    private func sendEvents(_ id: String) {
        guard let s = sessions[id], let p = peer(id) else { return }
        let start = max(p.has, sentUpTo[id] ?? 0)
        guard start < History.shared.ownCount else { return }
        let lines = History.shared.ownLines(from: start)
        guard !lines.isEmpty else { return }
        s.send(["t": "events", "from": start, "lines": lines])
        sentUpTo[id] = start + lines.count
    }

    private func gotAck(_ id: String, _ have: Int) {
        update(id) { $0.has = have }
        if have < (sentUpTo[id] ?? 0) { sentUpTo[id] = have }
        sendEvents(id)
    }

    // MARK: receiving

    private func applySettings(_ set: [String: Any], from id: String) {
        var ts = settingTs
        var changed = Set<String>()
        for (k, raw) in set {
            guard Self.keys.contains(k), let e = raw as? [String: Any], let v = e["v"], let t = (e["ts"] as? NSNumber)?.doubleValue else { continue }
            if t > (ts[k] ?? 0) {
                ts[k] = t
                applySetting(k, v)
                changed.insert(k)
            }
        }
        settingTs = ts
        guard !changed.isEmpty else { return }
        update(id) { $0.dirty.subtract(changed) }
        markSettings(changed, except: id) // pass it on to the other devices
    }

    /// Remembered picks from a device; the Auto EQ is re-checked through the settings hook.
    var learnedApplied: () -> Void = {}

    private func applyLearned(_ l: [String: Any], from id: String) {
        if Learned.shared.merge(l) {
            learnedApplied()
            markLearned(except: id)
        }
    }

    private func applyStats(_ m: [String: Any], from id: String) {
        if let r = (m["reset"] as? NSNumber)?.doubleValue, r > resetAt {
            // Reset on that device: reset here, and the others through the reset time sent with their stats.
            d.set(r, forKey: "sync.statsReset")
            for p in peers where p.id != id { update(p.id) { $0.stats = nil; $0.dose = 0 } }
            resetOwnStats(Date(timeIntervalSince1970: r / 1000))
            let me = id
            schedule { self.sendStats(me) }
        }
        guard let st = m["stats"] as? [String: Any] else { return }
        func n(_ k: String) -> Double { (st[k] as? NSNumber)?.doubleValue ?? 0 }
        var u = UsageStats()
        u.talkCount = Int(n("talkCount")); u.talkSeconds = n("talkMs") / 1000
        u.idleOffCount = Int(n("idleOffCount")); u.idleOffSeconds = n("idleOffMs") / 1000
        u.restoreCount = Int(n("restoreCount"))
        u.since = Date(timeIntervalSince1970: n("since") / 1000)
        update(id) { $0.stats = u; $0.dose = n("dose7") }
        markStats(except: id) // the other devices' totals changed
    }
}

final class Session {
    let conn: NWConnection
    var key: SymmetricKey?
    /// The device on the other end, once it said "hi".
    var peerId: String?
    var onMessage: (([String: Any]) -> Void)?
    var onClose: (() -> Void)?
    private var closed = false

    init(_ c: NWConnection) { conn = c }

    func start() {
        conn.stateUpdateHandler = { [weak self] st in
            switch st {
            case .failed, .cancelled: self?.finish()
            default: break
            }
        }
        conn.start(queue: .main)
        readFrame()
    }

    private func readFrame() {
        conn.receive(minimumIncompleteLength: 4, maximumLength: 4) { [weak self] data, _, done, err in
            guard let self else { return }
            guard let data, data.count == 4, err == nil else { self.close(); return }
            let n = data.reduce(0) { $0 << 8 | Int($1) }
            guard n > 0, n < 16_000_000 else { self.close(); return }
            self.conn.receive(minimumIncompleteLength: n, maximumLength: n) { body, _, _, err in
                guard let body, body.count == n, err == nil else { self.close(); return }
                if let m = self.decode(body) { self.onMessage?(m) } else { self.close(); return }
                if !self.closed { self.readFrame() }
            }
            if done && data.isEmpty { self.close() }
        }
    }

    private func decode(_ body: Data) -> [String: Any]? {
        var plain = body
        if let key {
            guard let box = try? AES.GCM.SealedBox(combined: body), let p = try? AES.GCM.open(box, using: key) else { return nil }
            plain = p
        }
        return (try? JSONSerialization.jsonObject(with: plain)) as? [String: Any]
    }

    func send(_ m: [String: Any]) {
        guard !closed, var body = try? JSONSerialization.data(withJSONObject: m) else { return }
        if let key {
            guard let sealed = try? AES.GCM.seal(body, using: key).combined else { return }
            body = sealed
        }
        let n = UInt32(body.count).bigEndian
        var frame = withUnsafeBytes(of: n) { Data($0) }
        frame.append(body)
        conn.send(content: frame, completion: .contentProcessed { [weak self] e in if e != nil { self?.close() } })
    }

    func close() {
        conn.cancel()
        finish()
    }

    private func finish() {
        guard !closed else { return }
        closed = true
        onClose?()
    }
}
