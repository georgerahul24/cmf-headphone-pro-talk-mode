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
 * Battery forecast from the headphone history.
 *
 * 1. Drain per mode: every battery drop while connected is matched to the noise mode in use
 *    (noise cancelling, Transparency or Off), giving each mode its own %-per-hour rate. Until
 *    there's enough history, CMF's figures fill in: about 2 %/h with noise control on (50 h),
 *    about 1 %/h with it off (100 h).
 * 2. Your real use: average hours per day in each mode over the last 14 days, counting the days
 *    you didn't use them at all.
 * 3. Daily drain = hours in each mode x that mode's rate. Days until the battery reaches 10% gives
 *    the next charge day.
 */
object BatteryPredict {
    private const val DAY = 24 * 3600_000L
    private val SPEC = mapOf(ModeGroup.ANC to 2.0, ModeGroup.TRANSPARENCY to 2.0, ModeGroup.OFF to 1.0)

    class Forecast(
        /** Hours of listening left in the current mode. */
        val hoursLeft: Double,
        /** Days until the battery reaches 10% at your usual daily use, or null if you hardly use them. */
        val daysToCharge: Double?,
        /** Average hours of use per day. */
        val hoursPerDay: Double,
        /** True when the drain rates come from your own history. */
        val learned: Boolean,
    )

    fun forecast(level: Int, anc: Int?, sources: List<List<History.Event>>, now: Long): Forecast {
        val hv = HistoryView(sources, now)
        fun modeAt(t: Long): ModeGroup? = hv.spans.firstOrNull { t >= it.start && t < it.end }?.mode

        // 1. Drain per mode. The headphones report battery in 5% steps, so time is measured from one
        // step down to the next within the same connection (the first reading after connecting can
        // sit anywhere inside a step).
        val drop = HashMap<ModeGroup, Double>(); val time = HashMap<ModeGroup, Double>()
        for (events in sources) {
            var level: Int? = null
            var step: History.Event? = null
            for (e in events) {
                when (e.kind) {
                    History.CONNECTED -> { level = null; step = null }
                    History.BATTERY -> {
                        val l = level
                        if (l != null && e.v < l) {
                            val s = step
                            if (s != null && e.t > now - 30 * DAY) {
                                val dt = e.t - s.t
                                val m = modeAt(s.t + dt / 2)
                                if (dt in 1..8 * 3600_000L && m != null) {
                                    drop[m] = (drop[m] ?: 0.0) + (s.v - e.v); time[m] = (time[m] ?: 0.0) + dt / 3_600_000.0
                                }
                            }
                            step = e
                        } else if (l != null && e.v > l) step = null // charging
                        level = e.v
                    }
                }
            }
        }
        // Blend with CMF's figures (worth 6 hours of history), so a few readings can't swing it.
        val prior = 6.0
        var learned = false
        val rate = ModeGroup.entries.associateWith { m ->
            val d = drop[m] ?: 0.0; val h = time[m] ?: 0.0
            if (h >= 2) learned = true
            (d + SPEC.getValue(m) * prior) / (h + prior)
        }

        // 2. Average hours per day in each mode, over the last 14 days (or since the history starts).
        val first = sources.mapNotNull { it.firstOrNull()?.t }.minOrNull() ?: now
        val days = ((now - maxOf(first, now - 14 * DAY)).toDouble() / DAY).coerceAtLeast(1.0)
        val hours = HashMap<ModeGroup, Double>()
        for (sp in hv.spans) {
            val a = maxOf(sp.start, now - 14 * DAY); val b = minOf(sp.end, now)
            if (b > a) hours[sp.mode] = (hours[sp.mode] ?: 0.0) + (b - a) / 3_600_000.0
        }
        val perDay = ModeGroup.entries.sumOf { (hours[it] ?: 0.0) / days }
        val dailyDrain = ModeGroup.entries.sumOf { (hours[it] ?: 0.0) / days * rate.getValue(it) }

        // 3. Time left now, and the next charge day.
        val current = anc?.let { modeGroup(it) } ?: ModeGroup.ANC
        val hoursLeft = level / rate.getValue(current)
        val toCharge = if (dailyDrain < 0.2) null else ((level - 10).coerceAtLeast(0) / dailyDrain)
        return Forecast(hoursLeft, toCharge, perDay, learned)
    }

    fun hoursText(h: Double): String = when {
        h < 1 -> "Less than an hour of listening left"
        h < 10 -> "About %.1f h of listening left".format(h).replace(".0 h", " h")
        else -> "About ${h.toInt()} h of listening left"
    }

    /** "Charge today", "Next charge tomorrow", "Next charge around Thu 14 Nov (in ~6 days)". */
    fun chargeText(days: Double?, now: Long): String? {
        days ?: return null
        if (days < 0.5) return "Charge today"
        val d = Math.round(days).toInt()
        if (d <= 1) return "Next charge tomorrow"
        val date = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date(now + d * DAY))
        return "Next charge around $date (in ~$d days)"
    }
}
