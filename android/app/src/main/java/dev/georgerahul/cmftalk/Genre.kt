package dev.georgerahul.cmftalk

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Song genre for Auto EQ: looks the song up on Apple's iTunes Search (free, no account; only the
 * title and artist are sent). Rock-type genres get Rock, classical music gets Classical, everything else Pop
 * (Bollywood, Regional Indian, Pop, R&B, Hip-Hop...). Results are kept in memory.
 */
object Genre {
    private val main = Handler(Looper.getMainLooper())
    private val rock = listOf("rock", "metal", "punk", "alternative", "grunge", "emo", "hardcore")
    private val classical = listOf("classical", "opera", "orchestral", "baroque", "choral", "chamber")

    /** Song key -> genre ("" = not found). */
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 300
    }
    private var pending: String? = null

    fun key(artist: String, title: String) = "${artist.trim().lowercase()}|${title.trim().lowercase()}"

    /** The cached genre, "" if the song wasn't found, or null if not looked up yet. */
    fun cached(key: String): String? = cache[key]

    /** Rock or Pop for a genre; [fallback] when the genre is unknown. */
    fun presetFor(genre: String, fallback: String): String = when {
        genre.isEmpty() -> fallback
        classical.any { genre.lowercase().contains(it) } -> Eq.CLASSICAL.id
        rock.any { genre.lowercase().contains(it) } -> Eq.ROCK.id
        else -> Eq.POP.id
    }

    /** "Kesariya (From "Brahmastra") [Official Video]" -> "Kesariya". */
    private fun clean(s: String) = s.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "")
        .replace(Regex("(?i)\\s+(ft\\.?|feat\\.?|featuring)\\s.*$"), "").trim()

    /** Looks the song up in the background, then calls [done] on the main thread. */
    fun lookup(artist: String, title: String, done: () -> Unit) {
        val k = key(artist, title)
        if (pending == k || cache.containsKey(k)) return
        pending = k
        Thread({
            val g = runCatching {
                // Only the first artist: long lists like "A, B, C and D" can make the search miss.
                val first = artist.split(",", "&").first().split(" and ").first()
                val term = URLEncoder.encode("${clean(title)} ${clean(first)}".trim(), "UTF-8")
                val country = Locale.getDefault().country.takeIf { it.length == 2 } ?: "IN"
                val c = URL("https://itunes.apple.com/search?term=$term&entity=song&limit=1&country=$country")
                    .openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 10_000
                c.setRequestProperty("User-Agent", "cmf-talk-mode")
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                j.optJSONArray("results")?.optJSONObject(0)?.optString("primaryGenreName") ?: ""
            }.onFailure { Log.i("CMFGenre", "lookup: $it") }.getOrNull()
            main.post {
                pending = null
                if (g != null) cache[k] = g // network errors aren't cached, so it tries again later
                done()
            }
        }, "cmf-genre").start()
    }
}
