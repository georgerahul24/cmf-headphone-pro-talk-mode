package dev.georgerahul.cmftalk

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The EQ values, all from eq.json at the repo root; the app has none of its own. The build ships
 * a copy of it (assets/eq.json), and the latest is downloaded when the service starts, every 12
 * hours and with "Check for updates". Whichever copy is newest ("updatedAt") is used. A file that
 * fails any check is ignored.
 */
object EqRemote {
    private const val ENDPOINT = "https://raw.githubusercontent.com/georgerahul24/cmf-headphone-pro-talk-mode/main/eq.json"
    private const val PREFS = "eq_remote"
    private const val JSON = "json"
    private const val HASH = "sha256"
    private const val TAG = "CMFEq"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var configs: Map<String, Config> = emptyMap()
    @Volatile private var checking = false
    private var loaded = false

    /** The tuning in use, like "2026-11-15.1 · 2026-11-15T23:18:40Z". */
    var version by mutableStateOf("none"); private set
    /** "updatedAt" of the tuning in use, to tell which copy is newer. ISO dates sort as text. */
    @Volatile private var updatedAt = ""
    /** The result of the last check, for the screen. */
    var status by mutableStateOf<String?>(null); private set
    /** Called on the main thread when new tuning arrived, so the headphones get it. Set by the service. */
    var onApplied: (() -> Unit)? = null

    private class Config(val about: String?, val factory: Int?, val preamp: Float, val bands: List<Band>)
    private class Parsed(val version: String, val updatedAt: String, val configs: Map<String, Config>)

    /** Loads the copy shipped with the app and the last download, and uses the newer one. Main thread; safe to call often. */
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val bundled = runCatching { parse(ctx.assets.open("eq.json").use { it.readBytes() }.toString(Charsets.UTF_8)) }
            .onFailure { Log.e(TAG, "the eq.json shipped with the app is broken", it) }.getOrNull()
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = p.getString(JSON, null)?.let { text ->
            runCatching { parse(text) }.onFailure { Log.w(TAG, "saved EQ rejected", it) }.getOrNull()
        }
        listOfNotNull(bundled, saved).maxByOrNull { it.updatedAt }?.let { use(it) }
    }

    private fun use(t: Parsed) {
        configs = t.configs
        version = t.version
        updatedAt = t.updatedAt
    }

    fun about(id: String): String? = configs[id]?.about
    fun factory(id: String): Int? = configs[id]?.factory
    fun preamp(id: String): Float? = configs[id]?.preamp
    fun bands(id: String): List<Band>? = configs[id]?.bands

    /** Downloads eq.json and applies it if it changed. Main thread. */
    fun check(ctx: Context) {
        load(ctx)
        if (checking) return
        checking = true
        status = "Checking the EQ tuning..."
        val app = ctx.applicationContext
        Thread({
            val result = try {
                // The query skips GitHub's few-minute cache, so a new tuning shows up right away.
                val c = URL("$ENDPOINT?t=${System.currentTimeMillis() / 60_000}").openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", "cmf-talk-mode-eq")
                c.setRequestProperty("Cache-Control", "no-cache")
                c.useCaches = false
                c.connectTimeout = 15_000
                c.readTimeout = 15_000
                if (c.responseCode != HttpURLConnection.HTTP_OK) throw java.io.IOException("HTTP ${c.responseCode}")
                val bytes = c.inputStream.use { it.readBytes() }
                if (bytes.size > 64 * 1024) throw java.io.IOException("EQ file is too large")
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (hash == p.getString(HASH, null) && configs.isNotEmpty()) {
                    "EQ tuning is up to date"
                } else {
                    val text = bytes.toString(Charsets.UTF_8)
                    val parsed = parse(text) // throws if anything is out of range
                    p.edit().putString(JSON, text).putString(HASH, hash).apply()
                    if (parsed.updatedAt < updatedAt) {
                        "EQ tuning is up to date" // the copy in the app is newer than GitHub's
                    } else {
                        main.post { use(parsed); onApplied?.invoke() }
                        "New EQ tuning: ${parsed.version}"
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "live EQ unavailable; keeping the current tuning", e)
                "Couldn't check the EQ tuning (${e.javaClass.simpleName})"
            }
            main.post { status = result; checking = false }
        }, "cmf-eq").start()
    }

    /** Checks everything before anything is used. No side effects. */
    private fun parse(text: String): Parsed {
        val root = JSONObject(text)
        require(root.optInt("schema", -1) == 1) { "unsupported schema" }
        val revision = root.optString("version").takeIf { it.isNotBlank() } ?: "Remote"
        val updatedAt = root.optString("updatedAt").takeIf { it.isNotBlank() }
        val presets = root.getJSONObject("presets")
        val out = HashMap<String, Config>()
        for (id in listOf("movie", "pop", "rock", "classical")) {
            val p = presets.getJSONObject(id)
            val about = p.optString("about").takeIf { it.isNotBlank() }?.take(500)
            // Optional: use Nothing's own preset on the headphones (1 to 5) instead of the bands.
            val factory = if (p.has("factory")) p.getInt("factory").also { require(it in 1..5) { "$id factory" } } else null
            val preamp = p.getDouble("preamp").toFloat().also { require(it in -6f..3f) { "$id preamp" } }
            val a = p.getJSONArray("bands")
            require(a.length() == 8) { "$id must have 8 bands" }
            val bands = (0 until a.length()).map {
                val b = a.getJSONObject(it)
                val type = b.getInt("type").also { n -> require(n in 0..2) }
                val gain = b.getDouble("gain").toFloat().also { n -> require(n in -12f..12f) }
                val freq = b.getDouble("freq").toFloat().also { n -> require(n in 20f..20000f) }
                val q = b.getDouble("q").toFloat().also { n -> require(n in 0.1f..10f) }
                Band(type, gain, freq, q)
            }
            out[id] = Config(about, factory, preamp, bands)
        }
        return Parsed(listOfNotNull(revision, updatedAt).joinToString(" · "), updatedAt ?: "", out)
    }
}
