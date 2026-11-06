package dev.georgerahul.cmftalk

import android.content.Context

class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = p.getBoolean("enabled", true)
        set(v) = p.edit().putBoolean("enabled", v).apply()

    /** Music volume while talking, in percent of max. */
    var talkVolume: Int
        get() = p.getInt("talkVolume", 10)
        set(v) = p.edit().putInt("talkVolume", v).apply()

    /** While talking: true = pause the music, false = lower the volume. */
    var talkPause: Boolean
        get() = p.getBoolean("talkPause", false)
        set(v) = p.edit().putBoolean("talkPause", v).apply()

    /** Set noise control to Off after a while with nothing playing. */
    var idleOff: Boolean
        get() = p.getBoolean("idleOff", true)
        set(v) = p.edit().putBoolean("idleOff", v).apply()

    /** Minutes with nothing playing before the battery saver acts. */
    var idleMinutes: Int
        get() = p.getInt("idleMinutes", 10)
        set(v) = p.edit().putInt("idleMinutes", v).apply()
}
