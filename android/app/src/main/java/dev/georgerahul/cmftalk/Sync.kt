package dev.georgerahul.cmftalk

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Syncs everything with the paired Mac over the local network: history, stats and every setting.
 *
 * The Mac listens (Bonjour "_cmftalk._tcp"). This phone finds it and keeps a link open while both
 * are on the same network, so changes go across within seconds; whatever piled up while apart
 * goes across as soon as they meet again.
 *
 * Pairing (once): the Mac shows a 6-digit code. Both sides make an X25519 key pair and swap the
 * public keys; the shared secret is HKDF(X25519 result, salt = code). Each side proves it has the
 * same secret with an HMAC before saving it. After that, every message is AES-GCM encrypted.
 *
 * What each side has:
 * - History: each device appends to its own log; the other keeps an exact copy, line for line.
 *   "have" = how many of the other side's lines I hold, so only missing lines are sent.
 * - Settings: each has a change time; the newer one wins.
 * - Stats: each sends its own counters; screens show the total. A reset resets both.
 *
 * Wire format: 4-byte big-endian length, then the message. Plain JSON until "hi"; after that
 * AES-GCM (12-byte nonce + ciphertext + 16-byte tag) of the JSON. Same as the Mac (Sync.swift).
 */
object Sync {
    private const val TAG = "CMFSync"
    private const val SERVICE = "_cmftalk._tcp"

    private var p: SharedPreferences? = null
    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    /** Socket writes and history reads for sending, one at a time. */
    private val tx = Executors.newSingleThreadExecutor()

    var peerId by mutableStateOf<String?>(null); private set
    var peerName by mutableStateOf<String?>(null); private set
    var connected by mutableStateOf(false); private set
    var lastSync by mutableLongStateOf(0L); private set
    /** How many of this phone's own history lines the Mac has. */
    var peerHas by mutableIntStateOf(0); private set
    /** How many of the Mac's history lines this phone has. */
    var haveFromPeer by mutableIntStateOf(0); private set
    var dirty by mutableStateOf(setOf<String>()); private set
    var statsDirty by mutableStateOf(false); private set
    var note by mutableStateOf<String?>(null); private set
    var pairing by mutableStateOf(false); private set
    /** Bumped when settings arrive from the Mac, so screens reload them. */
    var settingsVersion by mutableIntStateOf(0); private set
    /** Called on the main thread after settings from the Mac were saved. */
    var onSettingsApplied: (() -> Unit)? = null

    var deviceId = ""; private set
    /** The name the Mac shows: the device's own name (like "Living room Fire TV"), else maker and model. */
    private val deviceName by lazy {
        runCatching { android.provider.Settings.Global.getString(app!!.contentResolver, android.provider.Settings.Global.DEVICE_NAME) }
            .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
    }

    @Volatile private var secret: ByteArray? = null
    @Volatile private var running = false
    private val wake = Object()
    @Volatile private var socket: Socket? = null
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var key: ByteArray? = null
    @Volatile private var peerHasV = 0
    /** Lines sent but not yet acknowledged. Only used on [tx]. */
    private var sentUpTo = 0
    private var started = false

    fun init(ctx: Context) {
        if (p != null) return
        app = ctx.applicationContext
        val prefs = ctx.applicationContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
        p = prefs
        deviceId = prefs.getString("deviceId", null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }
        peerId = prefs.getString("peerId", null)
        peerName = prefs.getString("peerName", null)
        secret = prefs.getString("secret", null)?.let { Base64.getDecoder().decode(it) }
        peerHas = prefs.getInt("peerHas", 0); peerHasV = peerHas
        dirty = prefs.getStringSet("dirty", emptySet())!!.toSet()
        statsDirty = prefs.getBoolean("statsDirty", false)
        lastSync = prefs.getLong("lastSync", 0)
        peerId?.let { haveFromPeer = History.peerCount(it) }
    }

    /** Starts the background link. Called by the service. */
    fun start() {
        if (started) return
        started = true
        running = true
        History.onLog = { historyChanged() }
        Learned.onChange = { learnedChanged() }
        runCatching {
            app!!.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = wakeUp()
                })
        }
        Thread(::loop, "cmf-sync").start()
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        wakeUp()
    }

    private fun wakeUp() = synchronized(wake) { wake.notifyAll() }
    private fun sleep(ms: Long) = synchronized(wake) { runCatching { wake.wait(ms) } }

    // ---- what is waiting ----

    val historyWaiting get() = (History.ownCount - peerHas).coerceAtLeast(0)
    val everythingSynced get() = historyWaiting == 0 && dirty.isEmpty() && !statsDirty

    /** "34 history events, 2 settings and stats waiting to sync", or null when nothing is waiting. */
    val waitingText: String?
        get() {
            val parts = ArrayList<String>()
            val h = historyWaiting
            if (h > 0) parts += "$h history event${if (h == 1) "" else "s"}"
            if (dirty.isNotEmpty()) parts += "${dirty.size} setting${if (dirty.size == 1) "" else "s"}"
            if (statsDirty) parts += "stats"
            if (parts.isEmpty()) return null
            val list = if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
            return "$list waiting to sync"
        }

    // ---- changes on this phone ----

    /** A setting changed on this phone. Main thread. */
    fun settingChanged(key: String) {
        dirty = dirty + key
        p?.edit()?.putStringSet("dirty", dirty)?.apply()
        if (connected) main.post { sendSettings() }
    }

    /** A remembered EQ pick changed on this phone. Main thread. */
    fun learnedChanged() {
        dirty = dirty + "eq.learned"
        p?.edit()?.putStringSet("dirty", dirty)?.apply()
        if (connected) main.post {
            send(JSONObject().put("t", "learned").put("learned", Learned.all()))
            clearDirty()
        }
    }

    /** This phone's counters changed. Main thread. */
    fun statsChanged() {
        statsDirty = true
        p?.edit()?.putBoolean("statsDirty", true)?.apply()
        if (connected) { main.removeCallbacks(statsSend); main.postDelayed(statsSend, 1_000) }
    }

    /** Reset pressed on this phone: resets the Mac's counters too. */
    fun statsReset() {
        p?.edit()?.putLong("statsReset", System.currentTimeMillis())?.apply()
        statsChanged()
    }

    private val statsSend = Runnable { sendStats() }
    private val eventsSend = Runnable { tx.execute { sendEventsTx() } }

    private fun historyChanged() {
        if (connected) { main.removeCallbacks(eventsSend); main.postDelayed(eventsSend, 1_000) }
    }

    /** "Sync now": reconnect if needed and send everything again. */
    fun syncNow() {
        if (connected) sendState() else wakeUp()
    }

    fun unpair() {
        p?.edit()?.remove("peerId")?.remove("peerName")?.remove("secret")?.remove("peerHas")
            ?.remove("lastSync")?.remove("host")?.remove("port")?.apply()
        secret = null
        peerId = null; peerName = null; peerHas = 0; peerHasV = 0; haveFromPeer = 0; lastSync = 0
        Stats.updatePeer(null)
        runCatching { socket?.close() }
        note = "Unpaired. History already synced from the Mac stays here."
    }

    // ---- pairing ----

    /** Finds the Mac and pairs with the code it shows. Main thread. */
    fun pair(code: String) {
        if (pairing) return
        pairing = true
        note = "Looking for your Mac..."
        Thread({
            val result = runCatching { doPair(code.trim()) }.getOrElse { e ->
                Log.w(TAG, "pair", e); "Couldn't pair: ${e.message ?: e.javaClass.simpleName}"
            }
            main.post { pairing = false; note = result }
        }, "cmf-pair").start()
    }

    private fun doPair(code: String): String {
        if (!code.matches(Regex("\\d{6}"))) return "Enter the 6-digit code shown on the Mac."
        val addr = discover(15_000)
            ?: return "Couldn't find the Mac. Keep both on the same Wi-Fi, and click Pair a device on the Mac first."
        Socket().use { s ->
            s.connect(addr, 5_000)
            s.soTimeout = 15_000
            val o = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            val i = DataInputStream(BufferedInputStream(s.getInputStream()))
            val priv = SyncCrypto.randomBytes(32)
            val b64 = Base64.getEncoder()
            writeFrame(o, JSONObject().put("t", "pair").put("id", deviceId).put("name", deviceName)
                .put("pub", b64.encodeToString(SyncCrypto.publicKey(priv))), null)
            val ok = readFrame(i, null) ?: return "The Mac closed the connection."
            if (ok.optString("t") != "pairOk") return ok.optString("msg", "The Mac said no.")
            val macId = ok.getString("id")
            val macName = ok.optString("name", "Mac")
            val sec = SyncCrypto.pairSecret(priv, Base64.getDecoder().decode(ok.getString("pub")), code)
            writeFrame(o, JSONObject().put("t", "pairCheck")
                .put("mac", b64.encodeToString(SyncCrypto.hmac(sec, "phone".toByteArray()))), null)
            val done = readFrame(i, null) ?: return "Wrong code. Check the code on the Mac and try again."
            if (done.optString("t") != "pairDone") return done.optString("msg", "Wrong code.")
            val proof = Base64.getDecoder().decode(done.getString("mac"))
            if (!java.security.MessageDigest.isEqual(proof, SyncCrypto.hmac(sec, "mac".toByteArray())))
                return "The Mac's answer didn't check out. Try again."
            val newPeer = peerId != macId
            p!!.edit().putString("peerId", macId).putString("peerName", macName)
                .putString("secret", b64.encodeToString(sec))
                .putString("host", addr.address.hostAddress).putInt("port", addr.port)
                .apply { if (newPeer) putInt("peerHas", 0) }
                .apply()
            secret = sec
            main.post {
                peerId = macId; peerName = macName
                if (newPeer) { peerHas = 0; peerHasV = 0; Stats.updatePeer(null) }
                haveFromPeer = History.peerCount(macId)
            }
            wakeUp()
            return "Paired with $macName."
        }
    }

    // ---- the link ----

    private fun loop() {
        while (running) {
            val s = secret
            if (s == null || peerId == null) { sleep(10 * 60_000); continue }
            val cached = p?.getString("host", null)?.let { h -> InetSocketAddress(h, p!!.getInt("port", 0)) }
            val addr = cached ?: discover(10_000)
            if (addr == null) { sleep(2 * 60_000); continue }
            try {
                session(addr, s)
            } catch (e: Exception) {
                Log.i(TAG, "link: $e")
                if (!connected) p?.edit()?.remove("host")?.remove("port")?.apply() // the Mac moved or is off
            } finally {
                runCatching { socket?.close() }
                socket = null; out = null; key = null
                main.post { connected = false; main.removeCallbacks(ping) }
            }
            if (running) sleep(if (cached != null) 3_000 else 30_000)
        }
    }

    private fun session(addr: InetSocketAddress, s: ByteArray) {
        val sock = Socket()
        sock.connect(addr, 5_000)
        sock.soTimeout = 150_000
        sock.tcpNoDelay = true
        socket = sock
        val o = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))
        val i = DataInputStream(BufferedInputStream(sock.getInputStream()))
        synchronized(this) { writeFrame(o, JSONObject().put("t", "hi").put("id", deviceId), null) }
        val k = SyncCrypto.sessionKey(s)
        key = k
        out = o
        p?.edit()?.putString("host", addr.address.hostAddress)?.putInt("port", addr.port)?.apply()
        val ready = CountDownLatch(1)
        main.post {
            connected = true
            note = null
            tx.execute { sentUpTo = peerHasV }
            sendState()
            main.postDelayed(ping, 60_000)
            ready.countDown()
        }
        ready.await(5, TimeUnit.SECONDS)
        while (running) {
            val m = readFrame(i, k) ?: break
            main.post { handle(m) }
        }
    }

    private val ping: Runnable = object : Runnable {
        override fun run() {
            send(JSONObject().put("t", "ping"))
            main.postDelayed(this, 60_000)
        }
    }

    private fun writeFrame(o: DataOutputStream, m: JSONObject, k: ByteArray?) {
        var body = m.toString().toByteArray()
        if (k != null) body = SyncCrypto.seal(k, body)
        o.writeInt(body.size)
        o.write(body)
        o.flush()
    }

    private fun readFrame(i: DataInputStream, k: ByteArray?): JSONObject? {
        val n = runCatching { i.readInt() }.getOrNull() ?: return null
        if (n <= 0 || n > 16_000_000) return null
        val buf = ByteArray(n)
        i.readFully(buf)
        val plain = if (k != null) SyncCrypto.open(k, buf) else buf
        return JSONObject(String(plain))
    }

    /** Sends one message on the link, in order. */
    private fun send(m: JSONObject) {
        tx.execute { sendTx(m) }
    }

    private fun sendTx(m: JSONObject) {
        val o = out ?: return
        val k = key ?: return
        try {
            synchronized(this) { writeFrame(o, m, k) }
        } catch (e: Exception) {
            Log.i(TAG, "send: $e")
            runCatching { socket?.close() }
        }
    }

    // ---- sending (main thread unless marked Tx) ----

    private fun settingsJson(): JSONObject {
        val prefs = Prefs(app!!)
        val out = JSONObject()
        for (k in Prefs.SYNC_KEYS.keys) {
            val v = prefs.value(k) ?: continue
            out.put(k, JSONObject().put("v", v).put("ts", prefs.changedAt(k)))
        }
        return out
    }

    private val resetAt get() = p?.getLong("statsReset", 0) ?: 0

    private fun sendState() {
        val id = peerId ?: return
        val have = History.peerCount(id)
        haveFromPeer = have
        send(JSONObject().put("t", "state").put("id", deviceId).put("name", deviceName).put("have", have)
            .put("settings", settingsJson()).put("stats", Stats.snapshot()).put("reset", resetAt)
            .put("learned", Learned.all()))
        clearDirty()
        statsDirty = false
        p?.edit()?.putBoolean("statsDirty", false)?.apply()
    }

    private fun sendSettings() {
        send(JSONObject().put("t", "settings").put("settings", settingsJson()))
        clearDirty()
    }

    private fun clearDirty() {
        dirty = emptySet()
        p?.edit()?.putStringSet("dirty", emptySet())?.apply()
    }

    private fun sendStats() {
        if (!connected) return
        send(JSONObject().put("t", "stats").put("stats", Stats.snapshot()).put("reset", resetAt))
        statsDirty = false
        p?.edit()?.putBoolean("statsDirty", false)?.apply()
    }

    /** Sends this phone's history lines the Mac doesn't have yet. On [tx]. */
    private fun sendEventsTx() {
        if (out == null) return
        val start = maxOf(peerHasV, sentUpTo)
        if (start >= History.ownCount) return
        val lines = History.ownLines(start)
        if (lines.isEmpty()) return
        sendTx(JSONObject().put("t", "events").put("from", start).put("lines", JSONArray(lines)))
        sentUpTo = start + lines.size
    }

    private fun gotAck(have: Int) {
        peerHas = have
        peerHasV = have
        p?.edit()?.putInt("peerHas", have)?.apply()
        tx.execute {
            if (have < sentUpTo) sentUpTo = have
            sendEventsTx()
        }
    }

    // ---- receiving (main thread) ----

    private fun handle(m: JSONObject) {
        lastSync = System.currentTimeMillis()
        p?.edit()?.putLong("lastSync", lastSync)?.apply()
        when (m.optString("t")) {
            "state" -> {
                m.optString("name").takeIf { it.isNotEmpty() && it != peerName }?.let {
                    peerName = it; p?.edit()?.putString("peerName", it)?.apply()
                }
                m.optJSONObject("settings")?.let { applySettings(it) }
                m.optJSONObject("learned")?.let { applyLearned(it) }
                applyStats(m)
                if (m.has("have")) gotAck(m.optInt("have"))
            }
            "events" -> {
                val id = peerId ?: return
                val from = m.optInt("from", -1)
                val arr = m.optJSONArray("lines") ?: return
                val lines = (0 until arr.length()).map { arr.optString(it) }
                tx.execute {
                    if (from == History.peerCount(id)) History.appendPeer(id, lines)
                    val have = History.peerCount(id)
                    main.post { haveFromPeer = have }
                    sendTx(JSONObject().put("t", "ack").put("have", have))
                }
            }
            "ack" -> gotAck(m.optInt("have"))
            "settings" -> m.optJSONObject("settings")?.let { applySettings(it) }
            "stats" -> applyStats(m)
            "learned" -> m.optJSONObject("learned")?.let { applyLearned(it) }
            "err" -> { note = m.optString("msg"); runCatching { socket?.close() } }
        }
    }

    private fun applySettings(set: JSONObject) {
        val prefs = Prefs(app!!)
        var any = false
        for (k in set.keys()) {
            val e = set.optJSONObject(k) ?: continue
            val v = e.opt("v") ?: continue
            val ts = e.optDouble("ts", 0.0).toLong()
            if (ts > prefs.changedAt(k) && prefs.applyRemote(k, v, ts)) {
                any = true
                dirty = dirty - k
            }
        }
        if (any) {
            p?.edit()?.putStringSet("dirty", dirty)?.apply()
            settingsVersion++
            onSettingsApplied?.invoke()
        }
    }

    private fun applyLearned(j: JSONObject) {
        if (Learned.merge(j)) onSettingsApplied?.invoke()
    }

    private fun applyStats(m: JSONObject) {
        val r = m.optDouble("reset", 0.0).toLong()
        if (r > resetAt) {
            p?.edit()?.putLong("statsReset", r)?.apply()
            Stats.resetFromSync(r)
        }
        m.optJSONObject("stats")?.let { Stats.updatePeer(it) }
    }

    // ---- finding the Mac ----

    /** Looks for the Mac on the local network for up to [timeoutMs]. Not on the main thread. */
    @Suppress("DEPRECATION")
    private fun discover(timeoutMs: Long): InetSocketAddress? {
        val nsd = app?.getSystemService(NsdManager::class.java) ?: return null
        val found = AtomicReference<InetSocketAddress?>(null)
        val latch = CountDownLatch(1)
        val resolving = AtomicBoolean(false)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!resolving.compareAndSet(false, true)) return
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(r: NsdServiceInfo) {
                        val host = r.host
                        if (host != null) { found.set(InetSocketAddress(host, r.port)); latch.countDown() }
                        else resolving.set(false)
                    }
                    override fun onResolveFailed(r: NsdServiceInfo, error: Int) { resolving.set(false) }
                })
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { latch.countDown() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        runCatching { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { return null }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        runCatching { nsd.stopServiceDiscovery(listener) }
        return found.get()
    }
}
