package dev.georgerahul.cmftalk

import android.content.Context

/**
 * Settings. Every one of them syncs with the paired Mac: each has a change time ("ts.<name>"),
 * and the newer change wins. Changing one here tells [Sync].
 */
class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    companion object {
        /** Shared sync name to the name stored on this phone. */
        val SYNC_KEYS = linkedMapOf(
            "talk.enabled" to "enabled", "talk.volume" to "talkVolume", "talk.pause" to "talkPause",
            "saver.on" to "idleOff", "saver.minutes" to "idleMinutes", "eq.mode" to "eqMode",
            "eq.music" to "musicEq", "eq.genre" to "eqGenre", "update.auto" to "autoUpdate",
        )
        private val LOCAL_TO_SYNC = SYNC_KEYS.entries.associate { (k, v) -> v to k }
    }

    private fun changed(local: String) {
        p.edit().putLong("ts.$local", System.currentTimeMillis()).apply()
        LOCAL_TO_SYNC[local]?.let { Sync.settingChanged(it) }
    }

    var enabled: Boolean
        get() = p.getBoolean("enabled", true)
        set(v) { p.edit().putBoolean("enabled", v).apply(); changed("enabled") }

    /** Music volume while talking, in percent of max. */
    var talkVolume: Int
        get() = p.getInt("talkVolume", 10)
        set(v) { p.edit().putInt("talkVolume", v).apply(); changed("talkVolume") }

    /** While talking: true = pause the music, false = lower the volume. */
    var talkPause: Boolean
        get() = p.getBoolean("talkPause", false)
        set(v) { p.edit().putBoolean("talkPause", v).apply(); changed("talkPause") }

    /** Set noise control to Off after a while with nothing playing. */
    var idleOff: Boolean
        get() = p.getBoolean("idleOff", true)
        set(v) { p.edit().putBoolean("idleOff", v).apply(); changed("idleOff") }

    /** Minutes with nothing playing before the battery saver acts. */
    var idleMinutes: Int
        get() = p.getInt("idleMinutes", 10)
        set(v) { p.edit().putInt("idleMinutes", v).apply(); changed("idleMinutes") }

    /** EQ: "auto", "movie", "pop", "rock" or "flat". */
    var eqMode: String
        get() = EqMode.normalize(p.getString("eqMode", EqMode.AUTO) ?: EqMode.AUTO)
        set(v) { p.edit().putString("eqMode", v).apply(); changed("eqMode") }

    /** Preset Auto uses for music: "pop" or "rock". */
    var musicEq: String
        get() = p.getString("musicEq", "pop") ?: "pop"
        set(v) { p.edit().putString("musicEq", v).apply(); changed("musicEq") }

    /** Auto picks Pop or Rock by the song's genre, looked up on Apple's iTunes Search. */
    var eqGenre: Boolean
        get() = p.getBoolean("eqGenre", true)
        set(v) { p.edit().putBoolean("eqGenre", v).apply(); changed("eqGenre") }

    /** Check GitHub for new versions and install them. */
    var autoUpdate: Boolean
        get() = p.getBoolean("autoUpdate", true)
        set(v) { p.edit().putBoolean("autoUpdate", v).apply(); changed("autoUpdate") }

    // ---- for sync ----

    fun value(key: String): Any? = when (key) {
        "talk.enabled" -> enabled
        "talk.volume" -> talkVolume
        "talk.pause" -> talkPause
        "saver.on" -> idleOff
        "saver.minutes" -> idleMinutes
        "eq.mode" -> eqMode
        "eq.music" -> musicEq
        "eq.genre" -> eqGenre
        "update.auto" -> autoUpdate
        else -> null
    }

    fun changedAt(key: String): Long = SYNC_KEYS[key]?.let { p.getLong("ts.$it", 0) } ?: 0

    /** A newer setting from the Mac. Saved without telling Sync, so it isn't sent back. False if the value was bad. */
    fun applyRemote(key: String, v: Any, ts: Long): Boolean {
        val local = SYNC_KEYS[key] ?: return false
        val e = p.edit()
        when (key) {
            "talk.enabled", "talk.pause", "saver.on", "update.auto", "eq.genre" -> e.putBoolean(local, v as? Boolean ?: return false)
            "talk.volume" -> e.putInt(local, ((v as? Number)?.toInt() ?: return false).coerceIn(0, 100))
            "saver.minutes" -> e.putInt(local, ((v as? Number)?.toInt() ?: return false).coerceAtLeast(1))
            "eq.mode" -> e.putString(local, (v as? String)?.let { EqMode.normalize(it) }?.takeIf { it == EqMode.AUTO || Eq.of(it) != null } ?: return false)
            "eq.music" -> e.putString(local, (v as? String)?.takeIf { Eq.of(it) != null } ?: return false)
        }
        e.putLong("ts.$local", ts).apply()
        return true
    }
}
