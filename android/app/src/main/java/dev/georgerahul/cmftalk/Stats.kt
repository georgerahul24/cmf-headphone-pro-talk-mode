package dev.georgerahul.cmftalk

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

/**
 * Usage counters. This phone's own counters are kept here; the Mac's come in through [Sync].
 * Screens show the total of both ("all*"). Main thread only.
 */
object Stats {
    private var p: SharedPreferences? = null

    var talkCount by mutableIntStateOf(0); private set
    var talkMs by mutableLongStateOf(0L); private set
    var idleOffCount by mutableIntStateOf(0); private set
    var idleOffMs by mutableLongStateOf(0L); private set
    var restoreCount by mutableIntStateOf(0); private set
    var since by mutableLongStateOf(0L); private set

    /** The Mac's counters from its last sync, or null. */
    var peer by androidx.compose.runtime.mutableStateOf<org.json.JSONObject?>(null); private set

    val allTalkCount get() = talkCount + (peer?.optInt("talkCount") ?: 0)
    val allTalkMs get() = talkMs + (peer?.optLong("talkMs") ?: 0)
    val allIdleOffCount get() = idleOffCount + (peer?.optInt("idleOffCount") ?: 0)
    val allIdleOffMs get() = idleOffMs + (peer?.optLong("idleOffMs") ?: 0)
    val allRestoreCount get() = restoreCount + (peer?.optInt("restoreCount") ?: 0)
    val allSince get() = peer?.optLong("since")?.takeIf { it > 0 }?.let { minOf(it, since) } ?: since

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
        peer = prefs.getString("peer", null)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
    }

    /** This phone's counters, for sending to the Mac. */
    fun snapshot(): org.json.JSONObject = org.json.JSONObject()
        .put("talkCount", talkCount).put("talkMs", talkMs).put("idleOffCount", idleOffCount)
        .put("idleOffMs", idleOffMs).put("restoreCount", restoreCount).put("since", since)

    /** The Mac's counters arrived. */
    fun updatePeer(j: org.json.JSONObject?) {
        peer = j
        p?.edit()?.apply { if (j == null) remove("peer") else putString("peer", j.toString()) }?.apply()
    }

    /** The Mac was reset: reset this phone's counters too. */
    fun resetFromSync(at: Long) {
        talkCount = 0; talkMs = 0; idleOffCount = 0; idleOffMs = 0; restoreCount = 0
        since = at
        save()
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

    /** Reset pressed: resets the Mac's counters too. */
    fun reset() {
        talkCount = 0; talkMs = 0; idleOffCount = 0; idleOffMs = 0; restoreCount = 0
        since = System.currentTimeMillis()
        updatePeer(null)
        save()
        Sync.statsReset()
    }

    private fun save() {
        p?.edit()
            ?.putInt("talkCount", talkCount)?.putLong("talkMs", talkMs)
            ?.putInt("idleOffCount", idleOffCount)?.putLong("idleOffMs", idleOffMs)
            ?.putInt("restoreCount", restoreCount)?.putLong("since", since)
            ?.apply()
        Sync.statsChanged()
    }
}
