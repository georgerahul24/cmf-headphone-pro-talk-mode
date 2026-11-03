package dev.georgerahul.cmftalk

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

/** Usage counters, kept on the phone only. Main thread only. */
object Stats {
    private var p: SharedPreferences? = null

    var talkCount by mutableIntStateOf(0); private set
    var talkMs by mutableLongStateOf(0L); private set
    var idleOffCount by mutableIntStateOf(0); private set
    var idleOffMs by mutableLongStateOf(0L); private set
    var restoreCount by mutableIntStateOf(0); private set
    var since by mutableLongStateOf(0L); private set

    fun init(ctx: Context) {
        if (p != null) return
        val prefs = ctx.applicationContext.getSharedPreferences("stats", Context.MODE_PRIVATE)
        p = prefs
        talkCount = prefs.getInt("talkCount", 0)
        talkMs = prefs.getLong("talkMs", 0)
        idleOffCount = prefs.getInt("idleOffCount", 0)
        idleOffMs = prefs.getLong("idleOffMs", 0)
        restoreCount = prefs.getInt("restoreCount", 0)
        since = prefs.getLong("since", 0).takeIf { it > 0 } ?: System.currentTimeMillis().also {
            prefs.edit().putLong("since", it).apply()
        }
    }

    fun talkStarted() { talkCount++; save() }
    fun talkEnded(ms: Long) { talkMs += ms.coerceAtLeast(0); save() }
    fun idleTurnedOff() { idleOffCount++; save() }

    /** Noise control came back on after an idle turn-off ([restored]: by the app, not by hand). */
    fun idleEnded(ms: Long, restored: Boolean) {
        idleOffMs += ms.coerceAtLeast(0)
        if (restored) restoreCount++
        save()
    }

    fun reset() {
        talkCount = 0; talkMs = 0; idleOffCount = 0; idleOffMs = 0; restoreCount = 0
        since = System.currentTimeMillis()
        save()
    }

    private fun save() {
        p?.edit()
            ?.putInt("talkCount", talkCount)?.putLong("talkMs", talkMs)
            ?.putInt("idleOffCount", idleOffCount)?.putLong("idleOffMs", idleOffMs)
            ?.putInt("restoreCount", restoreCount)?.putLong("since", since)
            ?.apply()
    }
}
