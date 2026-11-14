package dev.georgerahul.cmftalk

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Downloads optional live EQ tuning while retaining the bundled values as a safe fallback. */
object EqRemote {
    private const val ENDPOINT = "https://raw.githubusercontent.com/georgerahul24/cmf-headphone-pro-talk-mode/main/eq.json"
    private const val PREFS = "eq_remote"
    private const val JSON = "json"
    private const val HASH = "sha256"
    private const val TAG = "CMFEq"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var configs: Map<String, Config> = emptyMap()
    @Volatile private var checking = false
    @Volatile var version: String = "Bundled"
        private set

    private data class Config(val preamp: Float, val bands: List<Band>)

    fun init(ctx: Context, onApplied: () -> Unit) {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString(JSON, null)?.let { text -> runCatching { configs = parse(text) }.onFailure { Log.w(TAG, "cached EQ rejected", it) } }
        check(ctx, onApplied)
    }

    fun preamp(id: String): Float? = configs[id]?.preamp
    fun bands(id: String): List<Band>? = configs[id]?.bands

    private fun check(ctx: Context, onApplied: () -> Unit) {
        if (checking) return
        checking = true
        Thread({
            try {
                val c = URL(ENDPOINT).openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", "cmf-talk-mode-eq")
                c.connectTimeout = 15_000
                c.readTimeout = 15_000
                if (c.responseCode != HttpURLConnection.HTTP_OK) throw java.io.IOException("HTTP ${c.responseCode}")
                val bytes = c.inputStream.use { it.readBytes() }
                if (bytes.size > 64 * 1024) throw java.io.IOException("EQ config is too large")
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (hash == p.getString(HASH, null)) return@Thread
                val text = bytes.toString(Charsets.UTF_8)
                val parsed = parse(text)
                configs = parsed
                p.edit().putString(JSON, text).putString(HASH, hash).apply()
                main.post(onApplied)
            } catch (e: Exception) {
                Log.w(TAG, "live EQ unavailable; using bundled tuning", e)
            } finally {
                checking = false
            }
        }, "cmf-eq").start()
    }

    private fun parse(text: String): Map<String, Config> {
        val root = JSONObject(text)
        require(root.optInt("schema", -1) == 1) { "unsupported schema" }
        val revision = root.optString("version").takeIf { it.isNotBlank() } ?: "Remote"
        val updatedAt = root.optString("updatedAt").takeIf { it.isNotBlank() }
        version = listOfNotNull(revision, updatedAt).joinToString(" · ")
        val presets = root.getJSONObject("presets")
        val out = HashMap<String, Config>()
        for (id in listOf("movie", "pop", "rock", "classical")) {
            val p = presets.getJSONObject(id)
            val preamp = p.getDouble("preamp").toFloat().also { require(it in -6f..3f) }
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
            out[id] = Config(preamp, bands)
        }
        return out
    }
}
