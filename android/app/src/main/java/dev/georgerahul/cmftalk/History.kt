package dev.georgerahul.cmftalk

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.Calendar
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * A log of what the headphones did, kept on the phone for good (never deleted):
 * connected / disconnected, noise mode changes and battery level changes.
 * One line per event: "time,kind,value" (kind C = connected 1/0, A = noise mode, B = battery).
 *
 * history.log holds this phone's own events. Events synced from the paired Mac go to
 * history-<mac id>.log, line for line, so each side knows exactly how much it has.
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

    /** Lines in this phone's own log (counted when logged, before the write finishes). */
    @Volatile var ownCount = 0
        private set
    /** Called after a new own event, for sync. Main thread. */
    var onLog: (() -> Unit)? = null

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "history.log")
        file = f
        // Close a connection left open by a killed app, at the last thing it recorded.
        val events = read(f)
        val lastConn = events.lastOrNull { it.kind == CONNECTED }
        if (lastConn != null && lastConn.v == 1) runCatching { f.appendText("${events.last().t},$CONNECTED,0\n") }
        ownCount = lines(f).size
    }

    private fun lines(f: File): List<String> =
        runCatching { f.readLines().filter { it.isNotEmpty() } }.getOrDefault(emptyList())

    private fun peerFile(id: String) = File(file!!.parentFile, "history-${id.filter { it.isLetterOrDigit() || it == '-' }}.log")

    /** This phone's own lines from [index] on, for sending to the Mac. Any thread. */
    fun ownLines(index: Int, max: Int = 2000): List<String> {
        val f = file ?: return emptyList()
        return io.submit(Callable {
            val all = lines(f)
            if (index >= all.size) emptyList() else all.subList(index, minOf(all.size, index + max))
        }).get()
    }

    /** How many of the Mac's lines this phone has. Any thread. */
    fun peerCount(id: String): Int {
        if (file == null) return 0
        return io.submit(Callable { lines(peerFile(id)).size }).get()
    }

    /** Adds the Mac's lines to its file. Any thread. */
    fun appendPeer(id: String, add: List<String>) {
        if (add.isEmpty() || file == null) return
        val text = add.joinToString("") { it.replace("\n", "").replace("\r", "") + "\n" }
        io.submit(Callable { runCatching { peerFile(id).appendText(text) } }).get()
        android.os.Handler(android.os.Looper.getMainLooper()).post { version++ }
    }

    private fun peerFiles(): List<File> {
        val dir = file?.parentFile ?: return emptyList()
        return dir.listFiles { f -> f.name.startsWith("history-") && f.name.endsWith(".log") }?.sortedBy { it.name } ?: emptyList()
    }

    /** Every history file: this phone's first, then the Mac's. */
    fun loadAll(): List<List<Event>> = listOf(load()) + peerFiles().map { read(it) }

    /** Records a value if it changed. Main thread. */
    fun log(kind: Char, v: Int) {
        if (kind != CONNECTED && last[kind] == v) return
        last[kind] = v
        if (kind == CONNECTED && v == 0) last.clear()
        val t = System.currentTimeMillis()
        val f = file ?: return
        io.execute { runCatching { f.appendText("$t,$kind,$v\n") } }
        version++
        ownCount++
        onLog?.invoke()
    }

    fun load(): List<Event> = file?.let(::read) ?: emptyList()

    /** The log as a CSV file to share: time, event, value. */
    fun exportCsv(ctx: Context): File {
        val out = File(ctx.cacheDir, "export/cmf-headphone-history.csv")
        out.parentFile?.mkdirs()
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        out.writeText(buildString {
            append("time,unix_ms,device,event,value\n")
            val peer = Sync.peerName ?: "Mac"
            for ((i, list) in loadAll().withIndex()) for (e in list) {
                val (name, value) = when (e.kind) {
                    CONNECTED -> "connected" to (if (e.v == 1) "yes" else "no")
                    ANC -> "noise_mode" to Proto.ancName(e.v).replace(" · ", " ")
                    BATTERY -> "battery" to e.v.toString()
                    else -> e.kind.toString() to e.v.toString()
                }
                append("${fmt.format(java.util.Date(e.t))},${e.t},${if (i == 0) "this phone" else peer},$name,$value\n")
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

/**
 * Everything the charts need for the last 7 days. [sources]: this phone's events first, then the
 * Mac's. When both were connected at the same time, that time counts once, and this phone's noise
 * mode record wins.
 */
class HistoryView(sources: List<List<History.Event>>, now: Long) {
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

        val conn = ArrayList<LongRange>()
        for ((i, events) in sources.withIndex()) {
            // A synced log that ends while connected: count it only up to its last event.
            val end = if (i == 0) now else events.lastOrNull()?.t ?: now
            val sp = ArrayList<Span>()
            parse(events, end, sp, conn, battery)
            for (x in sp) spans += subtract(x, spans)
        }
        spans.sortBy { it.start }
        battery.sortBy { it.first }
        connected += union(conn)

        usage = LongArray(7) { i ->
            val a = days[i]
            val b = if (i < 6) days[i + 1] else a + 24 * 3600_000L
            connected.sumOf { r -> (minOf(r.last, b) - maxOf(r.first, a)).coerceAtLeast(0) }
        }
    }

    private fun parse(events: List<History.Event>, end: Long, spans: MutableList<Span>,
                      conn: MutableList<LongRange>, bat: MutableList<Pair<Long, Int>>) {
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
                    connStart?.let { conn += it..e.t }
                    connStart = null
                }
                History.ANC -> { closeMode(e.t); mode = e.v; modeStart = e.t }
                History.BATTERY -> bat += e.t to e.v
            }
        }
        closeMode(end)
        connStart?.let { if (end > it) conn += it..end }
    }

    /** The parts of [s] not already covered by [taken]. */
    private fun subtract(s: Span, taken: List<Span>): List<Span> {
        var parts = listOf(s.start to s.end)
        for (t in taken) {
            if (t.end <= s.start || t.start >= s.end) continue
            parts = parts.flatMap { (a, b) ->
                if (t.end <= a || t.start >= b) listOf(a to b)
                else listOfNotNull(if (t.start > a) a to t.start else null, if (t.end < b) t.end to b else null)
            }
        }
        return parts.map { Span(it.first, it.second, s.mode) }
    }

    /** Overlapping time ranges joined, so time on two devices counts once. */
    private fun union(r: List<LongRange>): List<LongRange> {
        val out = ArrayList<LongRange>()
        for (x in r.sortedBy { it.first }) {
            val last = out.lastOrNull()
            if (last != null && x.first <= last.last) out[out.size - 1] = last.first..maxOf(last.last, x.last) else out += x
        }
        return out
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
