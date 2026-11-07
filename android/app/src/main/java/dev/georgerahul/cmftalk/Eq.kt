package dev.georgerahul.cmftalk

import android.content.Context
import android.content.pm.ApplicationInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One EQ filter. Type: 0 low shelf, 1 peak, 2 high shelf. Gain in dB. */
class Band(val type: Int, val gain: Float, val freq: Float, val q: Float)

/**
 * EQ presets, tuned for the CMF Headphone Pro from measurements, not copied from the factory presets.
 *
 * Out of the box these headphones have (vs. the Harman target): about +2.5 dB sub-bass, +2 dB
 * mid-bass and 200-250 Hz, a 3-6 dB dip at 2-4.5 kHz (voices sound a bit far away) and a +4 dB
 * peak at 8 kHz (sharp "s" sounds). Sources: SoundGuys B&K 5128 data, RTINGS, Kuulokenurkka,
 * Pragmatic Audio. The headphones take up to 8 filters with any frequency and Q (checked on a
 * real pair), so each preset uses all 8 to fix these one by one, instead of plain bass/mid/treble.
 */
enum class Eq(val id: String, val label: String, val about: String, val preamp: Float, val bands: List<Band>) {
    // All presets: preamp 0. The factory presets lift bass 6-10 dB with no loss of level (Pragmatic Audio
    // measurements), and the Energy Slider adds +8-10 dB the same way, so the headphones' DSP has the
    // headroom. A negative preamp only made ours sound quieter and thinner than stock.

    // Energy Slider at full bass (+8-10 dB below ~150 Hz), for a basshead who also wants every detail.
    // Big rumble (movie bass effects live at 20-40 Hz) and boom for fights and explosions, then a cut at
    // 220 Hz so the bass doesn't smear over voices and small sounds. Speech bands lifted for dialogue and
    // faint sounds. Only the narrow 8 kHz peak is cut, and a gentle treble lift brings out environmental
    // detail: glass clinking, a train's rattle, footsteps, room sound.
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
    // Slider at zero. English, Hindi and Malayalam pop: Bollywood and Malayalam film mixes put the singer
    // up front over a strong bass line, and bright female vocals meet this headphone's 8 kHz peak.
    POP("pop", "Pop",
        "Big, full bass like the factory Pop, with vocals clearly in front and smooth, never harsh. Tuned for English, Hindi and Malayalam songs.",
        0f, listOf(
            Band(0, 5.5f, 105f, 0.7f), // big warm bass shelf
            Band(1, 1f, 50f, 1f), // deep bass for dance drops
            Band(1, -2.5f, 250f, 1f), // less low-mid mud under the vocals
            Band(1, 2.5f, 2300f, 1.4f), // vocal body and clarity
            Band(1, 4f, 3800f, 1.6f), // vocals forward; fills the 4 kHz dip
            Band(1, -1.5f, 6300f, 2f), // softer "s" on bright female vocals
            Band(1, -3f, 8000f, 3f), // tames the stock 8 kHz peak
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
        ));

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

/** EQ choices on the screen. "stock" = the headphones' own factory sound. */
object EqMode {
    const val AUTO = "auto"
    const val STOCK = "stock"
    fun label(id: String) = when (id) {
        AUTO -> "Auto"
        STOCK -> "Stock"
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
