package dev.georgerahul.cmftalk

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * A log of what the headphones did, kept on the phone for good (never deleted):
 * connected / disconnected, noise mode changes and battery level changes.
 * One line per event: "time,kind,value" (kind C = connected 1/0, A = noise mode, B = battery).
 */
object History {
    const val CONNECTED = 'C'
    const val ANC = 'A'
    const val BATTERY = 'B'

    class Event(val t: Long, val kind: Char, val v: Int)

    private var file: File? = null
    private val io = Executors.newSingleThreadExecutor()
    private val last = HashMap<Char, Int>()

    /** Bumped on every new event so the screen can refresh. */
    var version by mutableIntStateOf(0)
        private set

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "history.log")
        file = f
        // Close a connection left open by a killed app, at the last thing it recorded.
        val events = read(f)
        val lastConn = events.lastOrNull { it.kind == CONNECTED }
        if (lastConn != null && lastConn.v == 1) runCatching { f.appendText("${events.last().t},$CONNECTED,0\n") }
    }

    /** Records a value if it changed. Main thread. */
    fun log(kind: Char, v: Int) {
        if (kind != CONNECTED && last[kind] == v) return
        last[kind] = v
        if (kind == CONNECTED && v == 0) last.clear()
        val t = System.currentTimeMillis()
        val f = file ?: return
        io.execute { runCatching { f.appendText("$t,$kind,$v\n") } }
        version++
    }

    fun load(): List<Event> = file?.let(::read) ?: emptyList()

    /** The log as a CSV file to share: time, event, value. */
    fun exportCsv(ctx: Context): File {
        val out = File(ctx.cacheDir, "export/cmf-headphone-history.csv")
        out.parentFile?.mkdirs()
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        out.writeText(buildString {
            append("time,unix_ms,event,value\n")
            for (e in load()) {
                val (name, value) = when (e.kind) {
                    CONNECTED -> "connected" to (if (e.v == 1) "yes" else "no")
                    ANC -> "noise_mode" to Proto.ancName(e.v).replace(" · ", " ")
                    BATTERY -> "battery" to e.v.toString()
                    else -> e.kind.toString() to e.v.toString()
                }
                append("${fmt.format(java.util.Date(e.t))},${e.t},$name,$value\n")
            }
        })
        return out
    }

    private fun read(f: File): List<Event> = runCatching {
        f.readLines().mapNotNull { line ->
            val p = line.split(',')
            if (p.size != 3) null else runCatching { Event(p[0].toLong(), p[1][0], p[2].toInt()) }.getOrNull()
        }
    }.getOrDefault(emptyList())
}

/** Noise mode groups for charts. */
enum class ModeGroup { ANC, TRANSPARENCY, OFF }

fun modeGroup(m: Int): ModeGroup = when (m) {
    Proto.ANC_TRANSPARENCY -> ModeGroup.TRANSPARENCY
    Proto.ANC_OFF -> ModeGroup.OFF
    else -> ModeGroup.ANC
}

/** Everything the charts need for the last 7 days. */
class HistoryView(events: List<History.Event>, now: Long) {
    class Span(val start: Long, val end: Long, val mode: ModeGroup)

    /** Midnight of each of the last 7 days, oldest first. */
    val days: List<Long>
    /** Connected time per day, same order as [days]. */
    val usage: LongArray
    val spans = ArrayList<Span>()
    val connected = ArrayList<LongRange>()
    val battery = ArrayList<Pair<Long, Int>>()

    init {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val list = ArrayList<Long>()
        for (i in 0 until 7) {
            list.add(0, cal.timeInMillis)
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        days = list

        var connStart: Long? = null
        var mode: Int? = null
        var modeStart = 0L
        fun closeMode(t: Long) {
            val m = mode ?: return
            if (connStart != null && t > modeStart) spans += Span(modeStart, t, modeGroup(m))
        }
        for (e in events) {
            when (e.kind) {
                History.CONNECTED -> if (e.v == 1) {
                    if (connStart == null) connStart = e.t
                } else {
                    closeMode(e.t); mode = null
                    connStart?.let { connected += it..e.t }
                    connStart = null
                }
                History.ANC -> { closeMode(e.t); mode = e.v; modeStart = e.t }
                History.BATTERY -> battery += e.t to e.v
            }
        }
        closeMode(now)
        connStart?.let { connected += it..now }

        usage = LongArray(7) { i ->
            val a = days[i]
            val b = if (i < 6) days[i + 1] else a + 24 * 3600_000L
            connected.sumOf { r -> (minOf(r.last, b) - maxOf(r.first, a)).coerceAtLeast(0) }
        }
    }

    fun dayEnd(i: Int) = if (i < 6) days[i + 1] else days[i] + 24 * 3600_000L

    /** Mode spans clipped to one day. */
    fun spansOn(i: Int): List<Span> {
        val a = days[i]; val b = dayEnd(i)
        return spans.filter { it.end > a && it.start < b }.map { Span(maxOf(it.start, a), minOf(it.end, b), it.mode) }
    }

    fun modeTotals(i: Int): Map<ModeGroup, Long> =
        spansOn(i).groupBy { it.mode }.mapValues { (_, v) -> v.sumOf { it.end - it.start } }

    /** Battery readings for one day, plus the last one before it so the line starts at midnight. */
    fun batteryOn(i: Int): List<Pair<Long, Int>> {
        val a = days[i]; val b = dayEnd(i)
        val before = battery.lastOrNull { it.first < a }
        return listOfNotNull(before?.let { a to it.second }) + battery.filter { it.first in a until b }
    }
}
