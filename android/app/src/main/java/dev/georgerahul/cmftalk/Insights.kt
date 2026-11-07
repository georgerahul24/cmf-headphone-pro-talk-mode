package dev.georgerahul.cmftalk

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow

/**
 * Battery time left, learned from the headphone history: how fast the battery dropped while
 * connected over the last 7 days (charging is left out). With too little history it uses CMF's
 * figures: about 50 h with noise control on, about 100 h with it off.
 */
object BatteryPredict {
    private const val WEEK = 7 * 24 * 3600_000L

    /** Percent per hour while connected, or null with too little history. */
    fun drainPerHour(sources: List<List<History.Event>>, now: Long): Double? {
        var drop = 0.0; var time = 0L
        for (events in sources) {
            var connected = false
            var last: History.Event? = null
            for (e in events) {
                when (e.kind) {
                    History.CONNECTED -> { connected = e.v == 1; last = null }
                    History.BATTERY -> {
                        val l = last
                        if (connected && l != null && e.t > now - WEEK) {
                            val d = l.v - e.v; val dt = e.t - l.t
                            // Only drops, and not across long gaps (the history can miss a disconnect).
                            if (d > 0 && dt in 1..6 * 3600_000L) { drop += d; time += dt }
                        }
                        if (connected) last = e
                    }
                }
            }
        }
        if (drop < 5 || time < 30 * 60_000L) return null
        return drop / (time / 3_600_000.0)
    }

    /** Hours left, and whether it came from your own history. */
    fun hoursLeft(level: Int, anc: Int?, sources: List<List<History.Event>>, now: Long): Pair<Double, Boolean> {
        drainPerHour(sources, now)?.let { return level / it to true }
        val fullHours = if (anc == Proto.ANC_OFF) 100.0 else 50.0
        return level / 100.0 * fullHours to false
    }

    fun text(hours: Double): String = when {
        hours < 1 -> "less than an hour left"
        hours < 10 -> "about %.1f h left".format(hours).replace(".0 h", " h")
        else -> "about ${hours.toInt()} h left"
    }
}

/**
 * Hearing safety, following the WHO guideline: 80 dB for 40 hours a week is safe, and every 3 dB
 * louder halves the safe time. The headphones can't measure loudness, so it's estimated from the
 * volume: about 100 dB at full volume, 50 dB less at the bottom of the scale. A guide, not a
 * measurement. Kept per day for the last 7 days.
 */
object Hearing {
    private const val MAX_DB = 100.0
    private const val SAFE_WEEK_S = 40 * 3600.0
    private var p: SharedPreferences? = null
    private val day = SimpleDateFormat("yyyyMMdd", Locale.US)

    /** Bumped on every change, so screens refresh. */
    var version by mutableIntStateOf(0); private set
    private var loudSince = 0L
    private var lastLoudAlert = 0L
    private var lastWeekAlertDay = ""

    fun init(ctx: Context) {
        if (p != null) return
        p = ctx.applicationContext.getSharedPreferences("hearing", Context.MODE_PRIVATE)
        lastWeekAlertDay = p!!.getString("weekAlertDay", "") ?: ""
    }

    /** Estimated level in dB for a volume from 0 to 1. */
    fun db(volume: Double): Double = if (volume <= 0) 0.0 else MAX_DB - 50 * (1 - volume.coerceIn(0.0, 1.0))

    /** Share of the weekly safe amount used by [seconds] at [db]. */
    private fun dose(db: Double, seconds: Double) = seconds * 2.0.pow((db - 80) / 3) / SAFE_WEEK_S

    /**
     * Some listening happened: [seconds] at [volume]. Returns an alert to show, if any.
     * Main thread.
     */
    fun add(volume: Double, seconds: Double, now: Long = System.currentTimeMillis()): String? {
        val prefs = p ?: return null
        if (seconds <= 0 || volume <= 0) { loudSince = 0; return null }
        val level = db(volume)
        val d = day.format(Date(now))
        prefs.edit()
            .putFloat("dose.$d", prefs.getFloat("dose.$d", 0f) + dose(level, seconds).toFloat())
            .putFloat("secs.$d", prefs.getFloat("secs.$d", 0f) + seconds.toFloat())
            .putFloat("dbs.$d", prefs.getFloat("dbs.$d", 0f) + (level * seconds).toFloat())
            .apply()
        prune(prefs, now)
        version++
        // Very loud for 30 minutes in a row: once an hour at most.
        if (level >= 85) { if (loudSince == 0L) loudSince = now } else loudSince = 0
        if (loudSince > 0 && now - loudSince > 30 * 60_000L && now - lastLoudAlert > 3600_000L) {
            lastLoudAlert = now
            return "You've been listening loud for 30 minutes. Turning it down a little protects your hearing."
        }
        if (week() >= 1.0 && lastWeekAlertDay != d) {
            lastWeekAlertDay = d
            prefs.edit().putString("weekAlertDay", d).apply()
            return "You've used this week's safe listening amount. Lower volume or a break helps your ears recover."
        }
        return null
    }

    private fun lastDays(now: Long) = (0 until 7).map { day.format(Date(now - it * 24 * 3600_000L)) }

    private fun prune(prefs: SharedPreferences, now: Long) {
        val keep = lastDays(now).toSet()
        val old = prefs.all.keys.filter { k -> k.contains('.') && k.substringAfter('.') !in keep && k != "weekAlertDay" }
        if (old.isNotEmpty()) prefs.edit().apply { old.forEach { remove(it) } }.apply()
    }

    /** This device's share of the weekly safe amount (1.0 = 100%). */
    fun week(now: Long = System.currentTimeMillis()): Double =
        lastDays(now).sumOf { (p?.getFloat("dose.$it", 0f) ?: 0f).toDouble() }

    /** Today's listening time in seconds and average level in dB. */
    fun today(now: Long = System.currentTimeMillis()): Pair<Double, Double> {
        val d = day.format(Date(now))
        val s = (p?.getFloat("secs.$d", 0f) ?: 0f).toDouble()
        val dbs = (p?.getFloat("dbs.$d", 0f) ?: 0f).toDouble()
        return s to (if (s > 0) dbs / s else 0.0)
    }
}
