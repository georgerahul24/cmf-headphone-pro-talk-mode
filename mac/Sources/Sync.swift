import Foundation
import Network
import CryptoKit

/// Syncs everything with the paired phone over the local network: history, stats and settings.
///
/// The Mac listens (Bonjour "_cmftalk._tcp"); the phone finds it and keeps a link open while both
/// are on the same network, so changes go across within seconds.
///
/// Pairing (once): the Mac shows a 6-digit code. Both sides make an X25519 key pair and swap the
/// public keys; the shared secret is HKDF(X25519 result, salt = code). Each side proves it has the
/// same secret with an HMAC before it is saved. After that, every message is AES-GCM encrypted.
///
/// What each side has:
/// - History: each device appends to its own log; the other side keeps an exact copy, line for
///   line. "have" = how many of the other side's lines I hold, so only missing lines are sent.
/// - Settings: each has a change time; the newer one wins.
/// - Stats: each device sends its own counters; screens show the total. A reset resets both.
///
/// Wire format: 4-byte big-endian length, then the message. Plain JSON until "hi"; after that
/// AES-GCM (12-byte nonce + ciphertext + 16-byte tag) of the JSON.
final class Sync: ObservableObject {
    static let shared = Sync()
    static let serviceType = "_cmftalk._tcp"

    /// The settings that sync, by their shared names.
    static let keys = ["talk.enabled", "talk.volume", "talk.pause", "saver.on", "saver.minutes",
                       "eq.mode", "eq.music", "update.auto", "alert.battery", "alert.hearing"]

    private let d = UserDefaults.standard
    let deviceId: String
    let deviceName = Host.current().localizedName ?? "Mac"

    @Published private(set) var peerId: String?
    @Published private(set) var peerName: String?
    @Published private(set) var connected = false
    @Published private(set) var lastSync: Date?
    @Published private(set) var pairCode: String?
    @Published private(set) var note: String?
    /// How many of this Mac's own history lines the phone has.
    @Published private(set) var peerHas: Int
    /// How many of the phone's history lines this Mac has.
    @Published private(set) var haveFromPeer = 0
    @Published private(set) var dirtySettings: Set<String>
    @Published private(set) var statsDirty: Bool
    /// The phone's own counters, from its last sync.
    @Published private(set) var peerStats: UsageStats?
    /// The phone's hearing dose this week, from its last sync.
    @Published private(set) var peerDose: Double

    /// Hooks into the app, set by TalkMode.
    var settingValue: (String) -> Any? = { _ in nil }
    var applySetting: (String, Any) -> Void = { _, _ in }
    var ownStats: () -> UsageStats = { UsageStats() }
    var resetOwnStats: (Date) -> Void = { _ in }

    private var secret: SymmetricKey?
    private var listener: NWListener?
    private var session: Session?
    private var pending: Session?
    /// Every open connection, kept alive until it closes.
    private var open: [ObjectIdentifier: Session] = [:]
    private var pendingPeer: (id: String, name: String, secret: SymmetricKey)?
    private var pairTimer: Timer?
    private var failures = 0
    private var sentUpTo = 0
    private var lastHeard = Date()
    private var watchdog: Timer?

    private init() {
        if let id = d.string(forKey: "sync.deviceId") { deviceId = id } else {
            deviceId = UUID().uuidString.lowercased()
            d.set(deviceId, forKey: "sync.deviceId")
        }
        peerId = d.string(forKey: "sync.peerId")
        peerName = d.string(forKey: "sync.peerName")
        if let s = d.data(forKey: "sync.secret") { secret = SymmetricKey(data: s) }
        peerHas = d.integer(forKey: "sync.peerHas")
        peerDose = d.double(forKey: "sync.peerDose")
        dirtySettings = Set(d.stringArray(forKey: "sync.dirty") ?? [])
        statsDirty = d.bool(forKey: "sync.statsDirty")
        if let ms = d.object(forKey: "sync.lastSync") as? Double { lastSync = Date(timeIntervalSince1970: ms / 1000) }
        if let data = d.data(forKey: "sync.peerStats") { peerStats = try? JSONDecoder().decode(UsageStats.self, from: data) }
        if let p = peerId { haveFromPeer = History.shared.peerCount(p) }
    }

    func start() {
        History.shared.onLog = { [weak self] in self?.historyChanged() }
        if peerId != nil { listen() }
        // The phone pings every minute. Silence for 2.5 minutes: it left the network.
        watchdog = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            guard let self, let s = self.session, Date().timeIntervalSince(self.lastHeard) > 150 else { return }
            s.close()
        }
    }

    // MARK: what is waiting

    var historyWaiting: Int { max(0, History.shared.ownCount - peerHas) }

    var everythingSynced: Bool { historyWaiting == 0 && dirtySettings.isEmpty && !statsDirty }

    /// "34 history events, 2 settings and stats waiting to sync", or nil when nothing is waiting.
    var waitingText: String? {
        var parts: [String] = []
        let h = historyWaiting
        if h > 0 { parts.append("\(h) history event\(h == 1 ? "" : "s")") }
        if !dirtySettings.isEmpty { parts.append("\(dirtySettings.count) setting\(dirtySettings.count == 1 ? "" : "s")") }
        if statsDirty { parts.append("stats") }
        guard !parts.isEmpty else { return nil }
        let list = parts.count == 1 ? parts[0] : parts.dropLast().joined(separator: ", ") + " and " + parts.last!
        return "\(list) waiting to sync"
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
        dirtySettings.insert(key)
        saveDirty()
        if connected { sendSettings() }
    }

    /// A remembered EQ pick changed on this Mac.
    func learnedChanged() {
        dirtySettings.insert("eq.learned")
        saveDirty()
        if connected {
            session?.send(["t": "learned", "learned": Learned.shared.all()])
            dirtySettings.removeAll(); saveDirty()
        }
    }

    /// This Mac's counters changed.
    func statsChanged() {
        statsDirty = true
        d.set(true, forKey: "sync.statsDirty")
        if connected { schedule { self.sendStats() } }
    }

    /// Reset pressed on this Mac: resets the phone's counters too.
    func statsReset() {
        d.set(Date().timeIntervalSince1970 * 1000, forKey: "sync.statsReset")
        peerStats = nil
        d.removeObject(forKey: "sync.peerStats")
        statsChanged()
    }

    private func historyChanged() {
        if connected { schedule { self.sendEvents() } }
    }

    private func schedule(_ f: @escaping () -> Void) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 1, execute: f)
    }

    private func saveDirty() { d.set(Array(dirtySettings), forKey: "sync.dirty") }

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
        if peerId == nil { listener?.cancel(); listener = nil }
    }

    func unpair() {
        session?.close(); session = nil
        connected = false
        peerId = nil; peerName = nil; secret = nil; peerStats = nil
        for k in ["sync.peerId", "sync.peerName", "sync.secret", "sync.peerHas", "sync.peerStats", "sync.lastSync"] { d.removeObject(forKey: k) }
        peerHas = 0; haveFromPeer = 0; lastSync = nil
        listener?.cancel(); listener = nil
        note = "Unpaired. History already synced from the phone stays on this Mac."
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
            if self.session === s { self.session = nil; self.connected = false }
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
                guard let id = m["id"] as? String, id == peerId, let secret else {
                    s.send(["t": "err", "msg": "This Mac isn't paired with this phone. Pair again."]); s.close(); return
                }
                session?.close()
                s.key = Self.sessionKey(secret)
                session = s
                lastHeard = Date()
                connected = true
                sentUpTo = peerHas
                note = nil
                sendState()
            default: s.close()
            }
            return
        }
        guard s === session else { return }
        lastHeard = Date()
        lastSync = Date()
        d.set(Date().timeIntervalSince1970 * 1000, forKey: "sync.lastSync")
        if t == "ping" { s.send(["t": "pong"]); return }
        switch t {
        case "state":
            if let n = m["name"] as? String, n != peerName { peerName = n; d.set(n, forKey: "sync.peerName") }
            if let set = m["settings"] as? [String: Any] { applySettings(set) }
            if let l = m["learned"] as? [String: Any] { applyLearned(l) }
            applyStats(m)
            if let have = m["have"] as? Int { gotAck(have) }
        case "events":
            guard let from = m["from"] as? Int, let lines = m["lines"] as? [String], let p = peerId else { return }
            let have = History.shared.peerCount(p)
            if from == have { History.shared.appendPeer(p, lines) }
            haveFromPeer = History.shared.peerCount(p)
            s.send(["t": "ack", "have": haveFromPeer])
        case "ack":
            if let have = m["have"] as? Int { gotAck(have) }
        case "settings":
            if let set = m["settings"] as? [String: Any] { applySettings(set) }
        case "stats":
            applyStats(m)
        case "learned":
            if let l = m["learned"] as? [String: Any] { applyLearned(l) }
        default:
            break
        }
    }

    private func pair(_ m: [String: Any], _ s: Session) {
        guard let code = pairCode else {
            s.send(["t": "err", "msg": "Pairing isn't open. On the Mac, click Pair a phone first."]); s.close(); return
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
            note = "Wrong code entered on the phone."
            if failures >= 3 { stopPairing(); note = "Too many wrong codes. Click Pair a phone to get a new code." }
            return
        }
        let reply = Data(HMAC<SHA256>.authenticationCode(for: Data("mac".utf8), using: p.secret))
        s.send(["t": "pairDone", "mac": reply.base64EncodedString()])
        // New phone: start its history copy from zero.
        if peerId != p.id {
            peerHas = 0; d.set(0, forKey: "sync.peerHas")
            peerStats = nil; d.removeObject(forKey: "sync.peerStats")
        }
        peerId = p.id; peerName = p.name; secret = p.secret
        d.set(p.id, forKey: "sync.peerId"); d.set(p.name, forKey: "sync.peerName")
        d.set(p.secret.withUnsafeBytes { Data($0) }, forKey: "sync.secret")
        haveFromPeer = History.shared.peerCount(p.id)
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

    private func statsMap() -> [String: Any] {
        let s = ownStats()
        return ["talkCount": s.talkCount, "talkMs": Int64(s.talkSeconds * 1000), "idleOffCount": s.idleOffCount,
                "idleOffMs": Int64(s.idleOffSeconds * 1000), "restoreCount": s.restoreCount,
                "since": Int64(s.since.timeIntervalSince1970 * 1000), "dose7": Hearing.shared.week()]
    }

    private var resetAt: Double { d.double(forKey: "sync.statsReset") }

    private func sendState() {
        session?.send(["t": "state", "id": deviceId, "name": deviceName, "have": haveFromPeer,
                       "settings": settingsMap(), "stats": statsMap(), "reset": resetAt,
                       "learned": Learned.shared.all()])
        dirtySettings.removeAll(); saveDirty()
        statsDirty = false; d.set(false, forKey: "sync.statsDirty")
    }

    private func sendSettings() {
        session?.send(["t": "settings", "settings": settingsMap()])
        dirtySettings.removeAll(); saveDirty()
    }

    private func sendStats() {
        guard let s = session else { return }
        s.send(["t": "stats", "stats": statsMap(), "reset": resetAt])
        statsDirty = false; d.set(false, forKey: "sync.statsDirty")
    }

    private func sendEvents() {
        guard let s = session else { return }
        let start = max(peerHas, sentUpTo)
        guard start < History.shared.ownCount else { return }
        let lines = History.shared.ownLines(from: start)
        guard !lines.isEmpty else { return }
        s.send(["t": "events", "from": start, "lines": lines])
        sentUpTo = start + lines.count
    }

    private func gotAck(_ have: Int) {
        peerHas = have
        d.set(have, forKey: "sync.peerHas")
        if have < sentUpTo { sentUpTo = have }
        sendEvents()
    }

    // MARK: receiving

    private func applySettings(_ set: [String: Any]) {
        var ts = settingTs
        for (k, raw) in set {
            guard Self.keys.contains(k), let e = raw as? [String: Any], let v = e["v"], let t = (e["ts"] as? NSNumber)?.doubleValue else { continue }
            if t > (ts[k] ?? 0) {
                ts[k] = t
                applySetting(k, v)
                dirtySettings.remove(k)
            }
        }
        settingTs = ts
        saveDirty()
    }

    /// Remembered picks from the phone; the Auto EQ is re-checked through the settings hook.
    var learnedApplied: () -> Void = {}

    private func applyLearned(_ l: [String: Any]) {
        if Learned.shared.merge(l) { learnedApplied() }
    }

    private func applyStats(_ m: [String: Any]) {
        if let r = (m["reset"] as? NSNumber)?.doubleValue, r > resetAt {
            d.set(r, forKey: "sync.statsReset")
            resetOwnStats(Date(timeIntervalSince1970: r / 1000))
            schedule { self.sendStats() }
        }
        guard let st = m["stats"] as? [String: Any] else { return }
        func n(_ k: String) -> Double { (st[k] as? NSNumber)?.doubleValue ?? 0 }
        var u = UsageStats()
        u.talkCount = Int(n("talkCount")); u.talkSeconds = n("talkMs") / 1000
        u.idleOffCount = Int(n("idleOffCount")); u.idleOffSeconds = n("idleOffMs") / 1000
        u.restoreCount = Int(n("restoreCount"))
        u.since = Date(timeIntervalSince1970: n("since") / 1000)
        peerStats = u
        peerDose = n("dose7")
        d.set(peerDose, forKey: "sync.peerDose")
        if let data = try? JSONEncoder().encode(u) { d.set(data, forKey: "sync.peerStats") }
    }
}

/// One TCP connection with length-prefixed frames, encrypted once `key` is set.
final class Session {
    let conn: NWConnection
    var key: SymmetricKey?
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
