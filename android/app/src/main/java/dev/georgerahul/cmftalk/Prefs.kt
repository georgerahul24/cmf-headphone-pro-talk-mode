package dev.georgerahul.cmftalk

import android.content.Context

class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = p.getBoolean("enabled", true)
        set(v) = p.edit().putBoolean("enabled", v).apply()

    /** Which button slot the app set up as the talk button, or null. */
    var triggerSlot: String?
        get() = p.getString("triggerSlot", null)
        set(v) = p.edit().putString("triggerSlot", v).apply()

    /** What a button did before the app changed it, or null. */
    fun original(slot: ButtonSlot): Int? =
        if (p.contains("original.${slot.key}")) p.getInt("original.${slot.key}", 0) else null
    fun setOriginal(slot: ButtonSlot, v: Int?) {
        if (v == null) p.edit().remove("original.${slot.key}").apply()
        else p.edit().putInt("original.${slot.key}", v).apply()
    }

    /** Music volume while talking, in percent of max. */
    var talkVolume: Int
        get() = p.getInt("talkVolume", 10)
        set(v) = p.edit().putInt("talkVolume", v).apply()
}
