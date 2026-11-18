package dev.georgerahul.cmftalk

import android.content.Context
import android.content.pm.ApplicationInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One EQ filter. Type: 0 low shelf, 1 peak, 2 high shelf. Gain in dB. */
class Band(val type: Int, val gain: Float, val freq: Float, val q: Float)

/**
 * EQ presets for the CMF Headphone Pro. Every value (bands, preamp, which presets use Nothing's own
 * preset, the description) comes from eq.json at the repo root: the app ships a copy of it and
 * downloads the latest (see [EqRemote]). Only Flat lives here, since it is "no EQ".
 */
enum class Eq(val id: String, val label: String) {
    MOVIE("movie", "Movie"),
    POP("pop", "Pop"),
    ROCK("rock", "Rock"),
    CLASSICAL("classical", "Classical"),
    FLAT("flat", "Flat");

    /** Nothing's own preset on the headphones (1 Rock, 2 Electronic, 3 Pop, 4 Enhance vocals, 5 Classical), or null for the bands. */
    val factory: Int? get() = if (this == FLAT) null else EqRemote.factory(id)
    val about: String get() = if (this == FLAT) "No EQ: bass, mid and treble all at 0." else EqRemote.about(id) ?: ""
    val preamp: Float get() = if (this == FLAT) 0f else EqRemote.preamp(id) ?: 0f
    /** Flat: Nothing's 3 bands at 0. A preset missing from eq.json (never, it ships with the app) also plays flat. */
    val bands: List<Band> get() = (if (this == FLAT) null else EqRemote.bands(id)) ?: threeBand(bass = 0f, mid = 0f, treble = 0f)

    /** Payload for [Proto.EQ_CUSTOM_SET]: count, preamp, then type/gain/freq/Q per band (float32 LE), 3 zero bytes per band. */
    fun payload(): IntArray {
        val b = ByteBuffer.allocate(5 + bands.size * 16).order(ByteOrder.LITTLE_ENDIAN)
        b.put(bands.size.toByte()).putFloat(preamp)
        for (x in bands) b.put(x.type.toByte()).putFloat(x.gain).putFloat(x.freq).putFloat(x.q)
        return b.array().map { it.toInt() and 0xFF }.toIntArray()
    }

    /**
     * The preset's EQ curve in dB at [f] Hz, from standard biquad filters (RBJ cookbook) at
     * 48 kHz. Close to what the headphones' DSP does; for drawing only. Same math as the Mac.
     */
    fun gain(f: Double): Double = preamp + bands.sumOf { bandGain(it, f) }

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }

        /** The presets with a character (everything but Flat). */
        val presets get() = entries.filter { it != FLAT }

        /** 20 Hz to 20 kHz, evenly on a log scale. */
        val PLOT_FREQS: List<Double> = List(120) { 20.0 * Math.pow(1000.0, it / 119.0) }

        fun bandGain(b: Band, f: Double): Double {
            val fs = 48_000.0
            val a = Math.pow(10.0, b.gain / 40.0)
            val w0 = 2 * Math.PI * b.freq / fs
            val cw = Math.cos(w0); val alpha = Math.sin(w0) / (2 * b.q); val sa = 2 * Math.sqrt(a) * alpha
            val c: DoubleArray = when (b.type) {
                0 -> doubleArrayOf(a * ((a + 1) - (a - 1) * cw + sa), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - sa),
                    (a + 1) + (a - 1) * cw + sa, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - sa)
                2 -> doubleArrayOf(a * ((a + 1) + (a - 1) * cw + sa), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - sa),
                    (a + 1) - (a - 1) * cw + sa, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - sa)
                else -> doubleArrayOf(1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a)
            }
            val w = 2 * Math.PI * f / fs
            fun mag2(c0: Double, c1: Double, c2: Double) =
                c0 * c0 + c1 * c1 + c2 * c2 + 2 * (c0 * c1 + c1 * c2) * Math.cos(w) + 2 * c0 * c2 * Math.cos(2 * w)
            return 10 * Math.log10(mag2(c[0], c[1], c[2]) / mag2(c[3], c[4], c[5]))
        }

        fun freqText(f: Float) = if (f < 1000) "${f.toInt()} Hz"
            else if (f % 1000 == 0f) "${(f / 1000).toInt()} kHz" else "%.1f kHz".format(f / 1000)

        fun gainText(g: Float): String {
            val s = if (g % 1 == 0f) g.toInt().toString() else "%.1f".format(g)
            return (if (g > 0) "+" else "") + s + " dB"
        }
    }
}

/** Nothing's 3 bands for the CMF Headphone Pro: mid peak 980 Hz, treble high shelf 3.5 kHz, bass low shelf 140 Hz. */
fun threeBand(bass: Float, mid: Float, treble: Float) = listOf(
    Band(1, mid, 980f, 0.7f), Band(2, treble, 3500f, 1f), Band(0, bass, 140f, 0.8f),
)

/** EQ choices on the screen: Auto or one of the [Eq] presets. */
object EqMode {
    const val AUTO = "auto"
    /** Older versions had "stock" (the factory Pop preset); it is Flat now. */
    fun normalize(id: String) = if (id == "stock") Eq.FLAT.id else id
    fun label(id: String) = when (id) {
        AUTO -> "Auto"
        else -> Eq.of(id)?.label ?: id
    }
}

/** Is an app for watching or for listening? Used by Auto EQ. */
object EqApps {
    private val video = setOf(
        "com.netflix.mediaclient", "com.amazon.avod.thirdpartyclient", "in.startv.hotstar",
        "in.startv.hotstaronly", "com.jio.media.ondemand", "com.jio.jioplay.tv", "com.google.android.youtube",
        "app.revanced.android.youtube", "com.vanced.android.youtube", "com.google.android.apps.youtube.kids",
        "com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro", "org.videolan.vlc", "is.xyz.mpv",
        "com.brouken.player", "com.sonyliv", "com.graymatrix.did", "com.disney.disneyplus", "com.wbd.stream",
        "com.hbo.hbonow", "com.apple.atve.android.appletv", "com.google.android.videos",
        "com.google.android.apps.youtube.unplugged", "com.plexapp.android", "org.jellyfin.mobile",
        "com.crunchyroll.crunchyroid", "com.mubi", "com.zee5", "com.tv.v18.viola", "com.erosnow",
        "com.balaji.alt", "com.ahaflix.aha", "com.sunnxt", "com.lionsgateplay.videoapp", "tv.twitch.android.app",
        "com.google.android.apps.photos", "com.miui.videoplayer", "com.samsung.android.video", "com.nothing.gallery",
    )
    private val music = setOf(
        "com.spotify.music", "com.google.android.apps.youtube.music", "app.revanced.android.apps.youtube.music",
        "com.jio.media.jiobeats", "com.apple.android.music", "com.amazon.mp3", "com.gaana", "com.bsbportal.music",
        "deezer.android.app", "com.soundcloud.android", "com.aspiro.tidal", "com.pandora.android",
        "com.shazam.android", "com.maxmpz.audioplayer", "com.kodarkooperativet.blackplayerex",
        "in.krosbits.musicolet", "com.musicplayer.retro", "com.google.android.music", "com.hungama.myplay.activity",
        "com.qobuz.music", "com.nothing.music",
    )

    /** true = watching (Movie), false = listening (music), null = can't tell. */
    fun isVideo(ctx: Context, pkg: String): Boolean? {
        if (pkg in video) return true
        if (pkg in music) return false
        val cat = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0).category }.getOrNull()
        return when (cat) {
            ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_IMAGE -> true
            ApplicationInfo.CATEGORY_AUDIO -> false
            else -> null
        }
    }

    fun label(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
