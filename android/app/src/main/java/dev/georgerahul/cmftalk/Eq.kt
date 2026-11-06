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
    // Tuned for the Energy Slider at full bass, as used for movies (the slider adds up to +8-10 dB
    // below ~150 Hz). Keeps the boom and rumble of explosions, adds a bit more of the lowest rumble,
    // and only trims the low-mid mud so voices stay clear on top.
    MOVIE("movie", "Movie",
        "Made for the Energy Slider at full bass. Big rumble and boom in explosions, with dialogue pushed forward so voices stay clear.",
        -4.5f, listOf(
            Band(0, 1.5f, 35f, 0.7f),    // more of the lowest rumble on top of the slider
            Band(1, -1f, 100f, 0.9f),    // keeps the boom, just a little tighter
            Band(1, -2.5f, 250f, 1f),    // clears the low-mid mud that covers voices
            Band(1, 1.5f, 1800f, 1.4f),  // body of the voice
            Band(1, 3.5f, 3000f, 1.4f),  // dialogue clarity, over the heavy bass
            Band(1, 3f, 4200f, 2.5f),    // fills the 4 kHz dip: consonants, detail in effects
            Band(1, -3f, 8000f, 3f),     // tames the 8 kHz peak: less harsh "s" and explosions
            Band(2, 1.5f, 13000f, 0.7f), // air and space for effects and score
        )),
    POP("pop", "Pop",
        "Full, punchy bass, vocals up front and smooth, with a bit of shine on top.",
        -3f, listOf(
            Band(0, 1f, 105f, 0.7f),     // full bass
            Band(1, 1f, 55f, 1.2f),      // deep 808 and synth bass drop
            Band(1, -1.5f, 250f, 1f),    // cleaner low-mids
            Band(1, 1.5f, 1800f, 1.4f),  // vocal body
            Band(1, 3f, 3000f, 1.4f),    // vocals forward
            Band(1, 2.5f, 4200f, 2.5f),  // fills the 4 kHz dip
            Band(1, -3f, 8000f, 3f),     // softer "s" sounds; pop vocals are bright
            Band(2, 2f, 12000f, 0.7f),   // shimmer and air
        )),
    ROCK("rock", "Rock",
        "Punchy kick drum, less muddy guitars, more crunch and bite, cymbals that sparkle.",
        -3.5f, listOf(
            Band(0, -1.5f, 35f, 0.7f),   // trims deep rumble rock doesn't use
            Band(1, 1f, 90f, 1.2f),      // kick drum punch
            Band(1, -2f, 250f, 1f),      // less bass and rhythm guitar mud
            Band(1, 1f, 1500f, 1.2f),    // guitar crunch, keeps the mids full
            Band(1, 2f, 3000f, 1.4f),    // bite and vocals
            Band(1, 3f, 4200f, 2.5f),    // fills the 4 kHz dip
            Band(1, -2f, 8000f, 3f),     // smaller cut than Pop so cymbals keep their sizzle
            Band(2, 2.5f, 11000f, 0.7f), // cymbal sparkle
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
