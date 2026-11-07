package dev.georgerahul.cmftalk

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * EQ picks you made for what's playing, remembered by name: the show, film or song ("mr robot"),
 * and the artist or channel. Next time something with the same name plays, Auto uses your pick.
 * Episode numbers are ignored, so a pick on one episode covers the whole series. Nothing is looked
 * up online. Syncs with the Mac: each entry has a change time and the newer one wins; forgetting
 * a pick is kept as an empty entry so it syncs too. Same rules as the Mac app (Learned.swift).
 */
object Learned {
    private var p: SharedPreferences? = null

    /** Bumped on every change, so screens refresh. */
    var version by mutableIntStateOf(0); private set
    /** Called after a change made on this phone, for sync. */
    var onChange: (() -> Unit)? = null

    fun init(ctx: Context) {
        if (p == null) p = ctx.applicationContext.getSharedPreferences("learned", Context.MODE_PRIVATE)
    }

    /** "Mr. Robot S1E3 | eps1.2_d3bug" -> "mr robot". Null if nothing is left. */
    fun titleKey(title: String?): String? {
        var t = title?.lowercase() ?: return null
        t = t.replace(Regex("[(\\[{][^)\\]}]*[)\\]}]"), " ")
        t = t.split(Regex("\\s+[|\\u2014\\u2013-]\\s+|:\\s+")).first()
        // Cut at the first episode marker ("3x07 One Minute" is the episode's own name), then drop any left.
        val marker = Regex("\\b(s\\d+\\s*e\\d+|\\d+x\\d+|season\\s*\\d+|episode\\s*\\d+|ep\\.?\\s*\\d+|part\\s*\\d+|e\\d+)\\b")
        marker.find(t)?.let { if (it.range.first > 0) t = t.substring(0, it.range.first) }
        t = t.replace(marker, " ")
        t = t.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")
        t = t.replace(Regex("(\\s\\d+)+$"), "")
        return t.ifBlank { null }
    }

    /** "Anirudh Ravichander, Super Subu and Heisenberg" -> "anirudh ravichander". */
    fun artistKey(artist: String?): String? {
        val first = artist?.split(",", "&")?.first()?.split(" and ")?.first() ?: return null
        return first.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ").ifBlank { null }
    }

    private fun entry(key: String): Pair<String, Long>? {
        val v = p?.getString("k:$key", null) ?: return null
        val i = v.lastIndexOf('|')
        if (i < 0) return null
        return v.substring(0, i) to (v.substring(i + 1).toLongOrNull() ?: 0L)
    }

    /** Your pick for this title or, failing that, this artist: the preset and the name it matched. */
    fun lookup(title: String?, artist: String?): Pair<String, String>? {
        titleKey(title)?.let { k -> entry("t:$k")?.first?.takeIf { it.isNotEmpty() }?.let { return it to k } }
        artistKey(artist)?.let { k -> entry("a:$k")?.first?.takeIf { it.isNotEmpty() }?.let { return it to k } }
        return null
    }

    /** The pick stored for exactly this title, if any (for highlighting the buttons). */
    fun forTitle(title: String?): String? = titleKey(title)?.let { entry("t:$it")?.first }?.takeIf { it.isNotEmpty() }

    /** Remembers [preset] for this title and artist; null forgets them. */
    fun set(title: String?, artist: String?, preset: String?) {
        val prefs = p ?: return
        val now = System.currentTimeMillis()
        val e = prefs.edit()
        titleKey(title)?.let { e.putString("k:t:$it", "${preset.orEmpty()}|$now") }
        artistKey(artist)?.let { e.putString("k:a:$it", "${preset.orEmpty()}|$now") }
        e.apply()
        version++
        onChange?.invoke()
    }

    /** Everything, for sending to the Mac: {key: {p, ts}}. */
    fun all(): JSONObject {
        val out = JSONObject()
        p?.all?.forEach { (k, _) ->
            if (k.startsWith("k:")) entry(k.removePrefix("k:"))?.let { (preset, ts) ->
                out.put(k.removePrefix("k:"), JSONObject().put("p", preset).put("ts", ts))
            }
        }
        return out
    }

    /** Entries from the Mac; newer ones win. True if anything changed. */
    fun merge(j: JSONObject): Boolean {
        val prefs = p ?: return false
        val e = prefs.edit()
        var changed = false
        for (k in j.keys()) {
            if (!(k.startsWith("t:") || k.startsWith("a:")) || k.length > 300) continue
            val o = j.optJSONObject(k) ?: continue
            val preset = o.optString("p", "")
            if (preset.isNotEmpty() && Eq.of(preset) == null) continue
            val ts = o.optDouble("ts", 0.0).toLong()
            if (ts > (entry(k)?.second ?: -1L)) { e.putString("k:$k", "$preset|$ts"); changed = true }
        }
        if (changed) { e.apply(); version++ }
        return changed
    }
}
