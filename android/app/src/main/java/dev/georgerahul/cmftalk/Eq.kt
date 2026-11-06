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
            Band(1, 2.5f, 32f, 1f),      // deep rumble you feel, where movie bass effects live
            Band(1, 2f, 80f, 1f),        // boom and punch for fights and explosions
            Band(1, -3f, 220f, 1f),      // keeps the bass from smearing over voices and detail
            Band(1, 1f, 1000f, 1f),      // body of voices
            Band(1, 2.5f, 2400f, 1.3f),  // dialogue clarity
            Band(1, 3f, 3800f, 1.6f),    // consonants and faint sounds; fills the 4 kHz dip
            Band(1, -2f, 8000f, 3.5f),   // only the narrow harsh peak, so detail stays
            Band(2, 1.5f, 10500f, 0.7f), // sparkle and space: glass, rattles, room sound
        )),
    // Slider at zero. English, Hindi and Malayalam pop: Bollywood and Malayalam film mixes put the singer
    // up front over a strong bass line, and bright female vocals meet this headphone's 8 kHz peak.
    POP("pop", "Pop",
        "Big, full bass like the factory Pop, with vocals clearly in front and smooth, never harsh. Tuned for English, Hindi and Malayalam songs.",
        0f, listOf(
            Band(0, 4f, 105f, 0.7f),     // bass shelf, like the factory Pop preset
            Band(1, 1f, 50f, 1f),        // extra deep bass for dance drops
            Band(1, -2f, 250f, 1f),      // less low-mid mud so vocals stay clean over the bass
            Band(1, 2f, 2300f, 1.4f),    // vocal body and clarity
            Band(1, 3.5f, 3800f, 1.6f),  // fills the 4 kHz dip: the main reason vocals sounded muted
            Band(1, -1f, 6300f, 2f),     // softer "s" on bright female vocals
            Band(1, -3f, 8000f, 3f),     // tames the stock 8 kHz peak
            Band(1, -1f, 11000f, 1.5f),  // keeps cymbals from splashing
        )),
    ROCK("rock", "Rock",
        "Weighty kick and bass guitar, less muddy guitars, vocals that cut through, and cymbals without the harshness.",
        0f, listOf(
            Band(0, 3f, 100f, 0.7f),     // weight for kick and bass guitar
            Band(1, 1f, 60f, 1.2f),      // kick drum punch
            Band(1, -1.5f, 250f, 1f),    // less guitar and bass mud
            Band(1, 2f, 2500f, 1.2f),    // guitar bite and snare crack
            Band(1, 3f, 3800f, 1.6f),    // vocals over dense guitars; fills the dip
            Band(1, -1f, 6300f, 2f),     // softens distorted guitar fizz
            Band(1, -3.5f, 8000f, 3f),   // cymbal harshness; rock masters are bright
            Band(1, -1f, 11000f, 1.5f),  // hi-hat splash
        ));

    /** Payload for [Proto.EQ_CUSTOM_SET]: count, preamp, then type/gain/freq/Q per band (float32 LE), 3 zero bytes per band. */
    fun payload(): IntArray {
        val b = ByteBuffer.allocate(5 + bands.size * 16).order(ByteOrder.LITTLE_ENDIAN)
        b.put(bands.size.toByte()).putFloat(preamp)
        for (x in bands) b.put(x.type.toByte()).putFloat(x.gain).putFloat(x.freq).putFloat(x.q)
        return b.array().map { it.toInt() and 0xFF }.toIntArray()
    }

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
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
