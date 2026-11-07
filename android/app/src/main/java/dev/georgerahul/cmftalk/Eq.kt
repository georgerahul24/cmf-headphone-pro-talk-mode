package dev.georgerahul.cmftalk

import android.content.Context
import android.content.pm.ApplicationInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One EQ filter. Type: 0 low shelf, 1 peak, 2 high shelf. Gain in dB. */
class Band(val type: Int, val gain: Float, val freq: Float, val q: Float)

/**
 * EQ presets for the CMF Headphone Pro, picked for what each one is for rather than copied from
 * the factory presets. Same values as the Mac app.
 */
enum class Eq(val id: String, val label: String, val about: String, val preamp: Float, val bands: List<Band>) {
    // 8 bands, each at its own frequency and width. The headphones apply all 8: in a listening test,
    // changing only bands 4-8 (with Nothing's 3 bands at 0) was clearly audible. Every preset is
    // measured from Flat. Preamp 0: the DSP has headroom (the Energy Slider adds up to +10 dB).
    MOVIE("movie", "Movie",
        "Made for the Energy Slider at full bass. Big rumble and boom in fights and explosions, while dialogue, faint sounds and small details like clinking glass stay clear.",
        0f, listOf(
            Band(1, 5f, 30f, 0.9f), // huge rumble you feel, where movie bass effects live
            Band(1, 3f, 70f, 1f), // boom and punch for fights and explosions
            Band(1, -3.5f, 220f, 1f), // keeps the bass from smearing over voices and detail
            Band(1, 1f, 1000f, 1f), // body of voices
            Band(1, 3f, 2500f, 1.3f), // dialogue clarity
            Band(1, 3.5f, 3800f, 1.6f), // consonants and faint sounds; fills the 4 kHz dip
            Band(1, -2.5f, 8000f, 3.5f), // only the narrow harsh peak, so detail stays
            Band(2, 2.5f, 10500f, 0.7f), // sparkle and space: glass, rattles, room sound
        )),
    POP("pop", "Pop",
        "Big, full bass with vocals clearly in front and smooth, never harsh. Tuned for English, Hindi and Malayalam songs.",
        0f, listOf(
            Band(0, 5.5f, 105f, 0.7f), // big warm bass shelf
            Band(1, 1f, 50f, 1f), // deep bass for dance drops
            Band(1, -2.5f, 250f, 1f), // less low-mid mud under the vocals
            Band(1, 2.5f, 2300f, 1.4f), // vocal body and clarity
            Band(1, 4f, 3800f, 1.6f), // vocals forward; fills the 4 kHz dip
            Band(1, -1.5f, 6300f, 2f), // softer "s" on bright female vocals
            Band(1, -3f, 8000f, 3f), // tames the headphones' 8 kHz peak
            Band(2, 2f, 12000f, 0.7f), // shimmer and air
        )),
    ROCK("rock", "Rock",
        "Punchy kick and bass guitar, crunchy guitars, vocals that cut through, and bright cymbals.",
        0f, listOf(
            Band(0, 3.5f, 90f, 0.7f), // weight for kick and bass guitar
            Band(1, 2f, 60f, 1.2f), // kick drum punch
            Band(1, -3f, 300f, 1f), // less guitar and bass mud
            Band(1, 2.5f, 1500f, 1.2f), // guitar crunch
            Band(1, 2f, 3000f, 1.4f), // bite and vocals
            Band(1, 3f, 4200f, 2f), // fills the 4 kHz dip
            Band(1, -3f, 8000f, 3f), // cymbal harshness
            Band(2, 3f, 10000f, 0.7f), // cymbal sparkle and air
        )),
    CLASSICAL("classical", "Classical",
        "Natural and open: clean low strings, clear soloists, soft violins and the air of the hall.",
        0f, listOf(
            Band(0, -1f, 105f, 0.7f),    // a touch less bass, so cellos and basses sound true
            Band(1, 1f, 40f, 1f),        // keeps the depth of organs and timpani
            Band(1, -2f, 250f, 1f),      // clears the muddy low-mid boom of large halls
            Band(1, 1f, 700f, 1f),       // body and wood of the instruments
            Band(1, 1.5f, 2500f, 1.2f),  // presence of soloists and voices
            Band(1, 3f, 4000f, 2f),      // detail; fills the 4 kHz dip
            Band(1, -3.5f, 8000f, 3f),   // softens harsh violins and sopranos
            Band(2, 2f, 12000f, 0.7f),   // the air and space of the hall
        )),
    FLAT("flat", "Flat",
        "No EQ: bass, mid and treble all at 0. Every preset is measured from here.",
        0f, threeBand(bass = 0f, mid = 0f, treble = 0f));

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
