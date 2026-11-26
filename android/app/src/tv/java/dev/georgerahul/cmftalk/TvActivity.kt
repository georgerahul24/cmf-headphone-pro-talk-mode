package dev.georgerahul.cmftalk

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * The TV screen: one page made for a remote, with no scrolling. Talk mode, noise control, stats
 * (phone, Mac and TV together), a few settings and sync with the Mac. The EQ is always Movie.
 */
class TvActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Stats.init(this)
        History.init(this)
        Sync.init(this)
        EqRemote.load(this)
        val prefs = Prefs(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = T.Purple, background = T.Bg, surface = T.Card)) {
                tick // re-check permission on resume
                TvApp(prefs, hasBt()) { requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Screen.visible = true
        tick++
        if (hasBt()) TalkService.start(this)
    }

    override fun onPause() {
        super.onPause()
        Screen.visible = false
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(code: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (hasBt()) TalkService.start(this)
        tick++
    }

    /** Android 11 and older grant Bluetooth at install; 12 and newer ask. */
    private fun hasBt() = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}

private object T {
    val Bg = Color(0xFF15141E)
    val Card = Color(0xFF211F30)
    val Card2 = Color(0xFF2B293D)
    val Text = Color(0xFFF4F3FA)
    val Sub = Color(0xFF908DAA)
    val Faint = Color(0xFF5E5B78)
    val Purple = Color(0xFF8B5CF6)
    val Violet = Color(0xFFB46CF7)
    val Pink = Color(0xFFEC5C9A)
    val Teal = Color(0xFF35D0B2)
}

private val Accent = Brush.linearGradient(listOf(T.Purple, T.Violet))

/** The screen is drawn for 960 x 540 dp (a 1080p TV) and shrinks or grows to fit, so nothing is ever cut off. */
private const val DESIGN_W = 960f
private const val DESIGN_H = 540f

@Composable
private fun TvApp(prefs: Prefs, hasBt: Boolean, askBt: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(T.Bg), contentAlignment = Alignment.Center) {
        val fit = minOf(maxWidth.value / DESIGN_W, maxHeight.value / DESIGN_H)
        val d = LocalDensity.current
        // Font size from the TV's settings is left out: a bigger font would no longer fit on one screen.
        CompositionLocalProvider(LocalDensity provides Density(d.density * fit, fontScale = 1f)) {
            Box(Modifier.requiredSize(DESIGN_W.dp, DESIGN_H.dp)) { Page(prefs, hasBt, askBt) }
        }
    }
}

@Composable
private fun Page(prefs: Prefs, hasBt: Boolean, askBt: () -> Unit) {
    val now = rememberNow()
    // Settings shown here, reloaded when they arrive from the Mac.
    var talkEnabled by remember { mutableStateOf(prefs.enabled) }
    var talkPause by remember { mutableStateOf(prefs.talkPause) }
    var idleOff by remember { mutableStateOf(prefs.idleOff) }
    LaunchedEffect(Sync.settingsVersion) {
        talkEnabled = prefs.enabled; talkPause = prefs.talkPause; idleOff = prefs.idleOff
    }

    // TVs can hide the screen's edges, so keep clear of them.
    Row(
        Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Panel(null, Modifier.weight(1f).fillMaxHeight()) { Controls(hasBt, askBt, now) }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StatsPanel(now, Modifier.weight(1f))
            Panel("Settings (synced)") {
                TvButton("Talk button: ${if (talkEnabled) "On" else "Off"}", Modifier.fillMaxWidth()) {
                    talkEnabled = !talkEnabled; prefs.enabled = talkEnabled
                }
                TvButton(if (talkPause) "While talking: pause music" else "While talking: lower to ${prefs.talkVolume}%",
                    Modifier.fillMaxWidth()) {
                    talkPause = !talkPause; prefs.talkPause = talkPause
                }
                TvButton(if (idleOff) "Battery saver: after ${prefs.idleMinutes} min" else "Battery saver: Off",
                    Modifier.fillMaxWidth()) {
                    idleOff = !idleOff; prefs.idleOff = idleOff
                    TalkService.instance?.idleSettingChanged()
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SyncPanel(now, Modifier.weight(1f))
            UpdatesPanel()
        }
    }
}

@Composable
private fun ColumnScope.Controls(hasBt: Boolean, askBt: () -> Unit, now: Long) {
    val st = HeadphoneState
    val svc = TalkService.instance
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Text("CMF Talk Mode", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = T.Text)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (st.connected) T.Teal else T.Faint))
        Spacer(Modifier.width(8.dp))
        Line(
            if (st.connected) buildString {
                append("CMF Headphone Pro")
                st.battery?.let { append(" · $it%"); if (st.charging) append(" charging") }
            } else st.status,
            T.Sub, 13,
        )
    }

    val ready = st.connected && svc != null
    when {
        !hasBt -> TvButton("Allow Bluetooth", Modifier.fillMaxWidth().focusRequester(first), big = true, onClick = askBt)
        !st.connected -> TvButton("Connect", Modifier.fillMaxWidth().focusRequester(first), big = true) {
            svc?.connectNow()
        }
        else -> {
            val talk = st.talkSince?.let { " · ${clock(now - it)}" } ?: ""
            TvButton(if (st.talkActive) "End talk$talk" else "Talk", Modifier.fillMaxWidth().focusRequester(first),
                selected = st.talkActive, big = true, enabled = ready) { svc?.toggleTalk() }
        }
    }
    Line("Or hold the headphones' button (Spatial Audio in Nothing X).", T.Sub, 11, lines = 2)

    Line("Noise control", T.Text, 14, FontWeight.Medium)
    val modes = listOf(Proto.ANC_OFF to "Off", Proto.ANC_TRANSPARENCY to "Transparency",
        Proto.ANC_HIGH to "Noise cancel", Proto.ANC_ADAPTIVE to "Adaptive")
    for (row in modes.chunked(2)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((mode, name) in row) {
                val on = st.anc == mode || (mode == Proto.ANC_HIGH && st.anc in setOf(Proto.ANC_MID, Proto.ANC_LOW))
                TvButton(name, Modifier.weight(1f), selected = on, enabled = ready) { svc?.setAnc(mode) }
            }
        }
    }
    when {
        st.talkActive -> Line("Talk mode on: Transparency, music ${if (Prefs(LocalContext.current).talkPause) "paused" else "lowered"}.", T.Pink, 12, lines = 2)
        st.idleOff -> Line("Off to save battery. Comes back when something plays.", T.Sub, 12, lines = 2)
        st.connected -> Line("Now: ${Proto.ancName(st.anc)}", T.Sub, 12)
    }
    Spacer(Modifier.weight(1f))
    Line("EQ: Movie (always on the TV)", T.Sub, 12)
    Line("Tuning ${EqRemote.version}", T.Faint, 10)
}

@Composable
private fun StatsPanel(now: Long, modifier: Modifier) {
    val st = HeadphoneState
    val liveTalk = st.talkSince?.let { now - it } ?: 0L
    val liveIdle = st.idleOffSince?.let { now - it } ?: 0L
    val since = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(Stats.allSince))
    Panel("Statistics", modifier) {
        Line(if (Sync.peerId != null) "All devices, since $since" else "Since $since", T.Sub, 11)
        val tiles = listOf(
            "Times talked" to Stats.allTalkCount.toString(),
            "Time talking" to dur(Stats.allTalkMs + liveTalk),
            "Average talk" to if (Stats.allTalkCount == 0) "-" else dur((Stats.allTalkMs + liveTalk) / Stats.allTalkCount),
            "Restored by music" to Stats.allRestoreCount.toString(),
            "Saver turned ANC off" to Stats.allIdleOffCount.toString(),
            "ANC rested for" to dur(Stats.allIdleOffMs + liveIdle),
        )
        for (row in tiles.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, value) in row) Tile(Modifier.weight(1f), label, value)
            }
        }
    }
}

@Composable
private fun SyncPanel(now: Long, modifier: Modifier) {
    Panel("Sync with your Mac", modifier) {
        val name = Sync.peerName ?: "your Mac"
        if (Sync.peerId == null) {
            Line("On the Mac: Settings, Pair a device. Enter its code:", T.Sub, 11, lines = 2)
            var code by remember { mutableStateOf("") }
            Line(code.padEnd(6, '·').chunked(3).joinToString("  "), T.Text, 22, FontWeight.SemiBold)
            // No number keys on most TV remotes: an on-screen pad.
            for (row in listOf("12345", "67890")) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in row) TvButton(c.toString(), Modifier.weight(1f), small = true) { if (code.length < 6) code += c }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TvButton("Delete", Modifier.weight(1f), small = true) { code = code.dropLast(1) }
                TvButton(if (Sync.pairing) "Pairing..." else "Pair", Modifier.weight(1f), small = true,
                    selected = code.length == 6) {
                    if (!Sync.pairing && code.length == 6) Sync.pair(code)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (Sync.connected) T.Teal else T.Faint))
                Spacer(Modifier.width(8.dp))
                Line(if (Sync.connected) "Connected to $name" else "$name is not on this network", T.Text, 13, FontWeight.Medium, lines = 2)
            }
            if (Sync.lastSync > 0) Line("Last synced ${ago(Sync.lastSync, now)}", T.Sub, 12)
            History.version
            val waiting = Sync.waitingText
            Line(waiting ?: "Everything from this TV is synced", if (waiting == null) T.Teal else T.Text, 12, lines = 2)
            var armed by remember { mutableStateOf(false) }
            LaunchedEffect(armed) { if (armed) { delay(4000); armed = false } }
            TvButton("Sync now", Modifier.fillMaxWidth()) { Sync.syncNow() }
            TvButton(if (armed) "Press again to unpair" else "Unpair", Modifier.fillMaxWidth()) {
                if (armed) { Sync.unpair(); armed = false } else armed = true
            }
        }
        Sync.note?.let { Line(it, T.Sub, 11, lines = 2) }
    }
}

@Composable
private fun UpdatesPanel() {
    val ctx = LocalContext.current
    Panel("Updates") {
        Line(Updater.status ?: "Version ${Updater.current(ctx)}", T.Sub, 11, lines = 2)
        Line(EqRemote.status ?: "EQ tuning ${EqRemote.version}", T.Sub, 11, lines = 2)
        TvButton(if (Updater.busy) "Checking..." else "Check for updates", Modifier.fillMaxWidth()) {
            Updater.check(ctx, install = true) // checks the EQ tuning too
        }
    }
}

// ---------- parts ----------

/** A card. With a [modifier] holding a weight, it fills its share of the column. */
@Composable
private fun Panel(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(T.Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null) Line(title, T.Text, 15, FontWeight.Medium)
        content()
    }
}

/** Text that never grows past [lines] lines, so the layout can't be pushed off the screen. */
@Composable
private fun Line(text: String, color: Color, size: Int, weight: FontWeight = FontWeight.Normal, lines: Int = 1) {
    Text(text, color = color, fontSize = size.sp, fontWeight = weight, lineHeight = (size + 4).sp,
        maxLines = lines, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Tile(modifier: Modifier, label: String, value: String) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(T.Card2).padding(horizontal = 10.dp, vertical = 7.dp)) {
        Line(value, T.Text, 17, FontWeight.SemiBold)
        Line(label, T.Sub, 10)
    }
}

/** A button for the remote: grows and gets a white outline when focused. */
@Composable
private fun TvButton(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    big: Boolean = false,
    small: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val shape = RoundedCornerShape(if (big) 18.dp else 12.dp)
    Box(
        modifier
            .scale(if (focused) 1.04f else 1f)
            .clip(shape)
            .then(if (selected) Modifier.background(Accent) else Modifier.background(T.Card2))
            .border(2.dp, if (focused) Color.White else Color.Transparent, shape)
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = if (big) 14.dp else if (small) 6.dp else 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) T.Text else T.Faint, fontSize = if (big) 22.sp else 13.sp,
            fontWeight = if (big || selected) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { delay(1000); now = System.currentTimeMillis() }
    }
    return now
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

private fun dur(ms: Long): String {
    val m = ms / 60_000
    return when {
        ms < 60_000 -> "${ms / 1000} s"
        m < 60 -> "$m min"
        else -> "${m / 60} h ${m % 60} min"
    }
}

private fun ago(ms: Long, now: Long): String {
    val s = (now - ms) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> "${s / 86400} days ago"
    }
}
