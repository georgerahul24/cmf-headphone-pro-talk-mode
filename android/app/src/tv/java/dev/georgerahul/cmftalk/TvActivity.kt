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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * The TV screen: one page made for a remote. Talk mode, noise control, stats (phone, Mac and TV
 * together), a few settings and sync with the Mac. The EQ is always Movie.
 */
class TvActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Stats.init(this)
        History.init(this)
        Hearing.init(this)
        Sync.init(this)
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

@Composable
private fun TvApp(prefs: Prefs, hasBt: Boolean, askBt: () -> Unit) {
    val now = rememberNow()
    // Settings shown here, reloaded when they arrive from the Mac.
    var talkEnabled by remember { mutableStateOf(prefs.enabled) }
    var talkPause by remember { mutableStateOf(prefs.talkPause) }
    var idleOff by remember { mutableStateOf(prefs.idleOff) }
    LaunchedEffect(Sync.settingsVersion) {
        talkEnabled = prefs.enabled; talkPause = prefs.talkPause; idleOff = prefs.idleOff
    }

    Row(
        Modifier.fillMaxSize().background(T.Bg).padding(horizontal = 48.dp, vertical = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Controls(hasBt, askBt, now)
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatsPanel(now)
            Panel("Settings") {
                Text("These sync with your phone and Mac.", color = T.Sub, fontSize = 13.sp)
                TvButton("Talk mode button: ${if (talkEnabled) "On" else "Off"}", Modifier.fillMaxWidth()) {
                    talkEnabled = !talkEnabled; prefs.enabled = talkEnabled
                }
                TvButton("While talking: ${if (talkPause) "pause the music" else "lower the volume to ${prefs.talkVolume}%"}",
                    Modifier.fillMaxWidth()) {
                    talkPause = !talkPause; prefs.talkPause = talkPause
                }
                TvButton("Battery saver: ${if (idleOff) "On, after ${prefs.idleMinutes} min with nothing playing" else "Off"}",
                    Modifier.fillMaxWidth()) {
                    idleOff = !idleOff; prefs.idleOff = idleOff
                    TalkService.instance?.idleSettingChanged()
                }
            }
            SyncPanel(now)
            UpdatesPanel()
        }
    }
}

@Composable
private fun Controls(hasBt: Boolean, askBt: () -> Unit, now: Long) {
    val st = HeadphoneState
    val svc = TalkService.instance
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Text("CMF Talk Mode", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = T.Text)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (st.connected) T.Teal else T.Faint))
        Spacer(Modifier.width(10.dp))
        Text(
            if (st.connected) buildString {
                append("CMF Headphone Pro")
                st.battery?.let { append(" · $it%"); if (st.charging) append(" charging") }
            } else st.status,
            color = T.Sub, fontSize = 16.sp,
        )
    }

    val ready = st.connected && svc != null
    when {
        !hasBt -> TvButton("Allow Bluetooth", Modifier.fillMaxWidth().focusRequester(first), big = true, onClick = askBt)
        !st.connected -> TvButton("Connect to the headphones", Modifier.fillMaxWidth().focusRequester(first), big = true) {
            svc?.connectNow()
        }
        else -> {
            val talk = st.talkSince?.let { " · ${clock(now - it)}" } ?: ""
            TvButton(if (st.talkActive) "End talk$talk" else "Talk", Modifier.fillMaxWidth().focusRequester(first),
                selected = st.talkActive, big = true, enabled = ready) { svc?.toggleTalk() }
        }
    }
    Text("Or press and hold the button on the headphones (set to Spatial Audio in Nothing X).",
        color = T.Sub, fontSize = 13.sp, lineHeight = 18.sp)

    Text("Noise control", color = T.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    val modes = listOf(Proto.ANC_OFF to "Off", Proto.ANC_TRANSPARENCY to "Transparency",
        Proto.ANC_HIGH to "Noise cancelling", Proto.ANC_ADAPTIVE to "Adaptive")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((mode, name) in modes) {
            val on = st.anc == mode || (mode == Proto.ANC_HIGH && st.anc in setOf(Proto.ANC_MID, Proto.ANC_LOW))
            TvButton(name, Modifier.weight(1f), selected = on, enabled = ready) { svc?.setAnc(mode) }
        }
    }
    if (st.connected) Text("Now: ${Proto.ancName(st.anc)}", color = T.Sub, fontSize = 13.sp)
    if (st.idleOff) Text("Turned off to save battery. It comes back when something plays.", color = T.Sub, fontSize = 13.sp)
    if (st.talkActive) Text("Talk mode is on: Transparency, with the music lowered or paused.", color = T.Pink, fontSize = 13.sp)

    Text("EQ: Movie (always on the TV)", color = T.Sub, fontSize = 14.sp)
}

@Composable
private fun StatsPanel(now: Long) {
    val st = HeadphoneState
    val liveTalk = st.talkSince?.let { now - it } ?: 0L
    val liveIdle = st.idleOffSince?.let { now - it } ?: 0L
    val since = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(Stats.allSince))
    Panel("Statistics") {
        Text(if (Sync.peerId != null) "Phone, Mac and TV together, since $since" else "Since $since",
            color = T.Sub, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(Modifier.weight(1f), "Times talked", Stats.allTalkCount.toString())
            Tile(Modifier.weight(1f), "Time talking", dur(Stats.allTalkMs + liveTalk))
            Tile(Modifier.weight(1f), "Average talk",
                if (Stats.allTalkCount == 0) "-" else dur((Stats.allTalkMs + liveTalk) / Stats.allTalkCount))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(Modifier.weight(1f), "Saver turned ANC off", Stats.allIdleOffCount.toString())
            Tile(Modifier.weight(1f), "ANC rested for", dur(Stats.allIdleOffMs + liveIdle))
            Tile(Modifier.weight(1f), "Hearing this week", "${(Stats.allDose * 100).toInt()}%")
        }
    }
}

@Composable
private fun SyncPanel(now: Long) {
    Panel("Sync with your Mac") {
        val name = Sync.peerName ?: "your Mac"
        if (Sync.peerId == null) {
            Text("Syncs stats, history and settings with the Mac app over your Wi-Fi. Encrypted, no internet.",
                color = T.Sub, fontSize = 13.sp, lineHeight = 18.sp)
            Text("On the Mac: open the app, Settings, Pair a device. Then enter the code here.",
                color = T.Sub, fontSize = 13.sp, lineHeight = 18.sp)
            var code by remember { mutableStateOf("") }
            Text(code.padEnd(6, '·').chunked(3).joinToString("  "), fontSize = 30.sp, letterSpacing = 6.sp,
                color = T.Text, fontWeight = FontWeight.SemiBold)
            // No number keys on most TV remotes: an on-screen pad.
            for (row in listOf("12345", "67890")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in row) TvButton(c.toString(), Modifier.weight(1f)) { if (code.length < 6) code += c }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton("Delete", Modifier.weight(1f)) { code = code.dropLast(1) }
                TvButton(if (Sync.pairing) "Pairing..." else "Pair with Mac", Modifier.weight(2f),
                    selected = code.length == 6) {
                    if (!Sync.pairing && code.length == 6) Sync.pair(code)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (Sync.connected) T.Teal else T.Faint))
                Spacer(Modifier.width(8.dp))
                Text(if (Sync.connected) "Connected to $name" else "$name is not on this network",
                    fontSize = 15.sp, fontWeight = FontWeight.Medium, color = T.Text)
            }
            if (Sync.lastSync > 0) Text("Last synced ${ago(Sync.lastSync, now)}", color = T.Sub, fontSize = 13.sp)
            History.version
            val waiting = Sync.waitingText
            Text(waiting ?: "Everything from this TV is synced", color = if (waiting == null) T.Teal else T.Text, fontSize = 13.sp)
            var armed by remember { mutableStateOf(false) }
            LaunchedEffect(armed) { if (armed) { delay(4000); armed = false } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton("Sync now", Modifier.weight(1f)) { Sync.syncNow() }
                TvButton(if (armed) "Press again to unpair" else "Unpair", Modifier.weight(1f)) {
                    if (armed) { Sync.unpair(); armed = false } else armed = true
                }
            }
        }
        Sync.note?.let { Text(it, color = T.Sub, fontSize = 13.sp, lineHeight = 18.sp) }
    }
}

@Composable
private fun UpdatesPanel() {
    val ctx = LocalContext.current
    Panel("Updates") {
        Text(Updater.status ?: "Version ${Updater.current(ctx)}", color = T.Sub, fontSize = 13.sp)
        TvButton(if (Updater.busy) "Checking..." else "Check for updates", Modifier.fillMaxWidth()) {
            Updater.check(ctx, install = true)
        }
    }
}

// ---------- parts ----------

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(T.Card).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = T.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        content()
    }
}

@Composable
private fun Tile(modifier: Modifier, label: String, value: String) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(T.Card2).padding(12.dp)) {
        Text(value, color = T.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = T.Sub, fontSize = 12.sp)
    }
}

/** A button for the remote: grows and gets a white outline when focused. */
@Composable
private fun TvButton(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    big: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val shape = RoundedCornerShape(if (big) 22.dp else 14.dp)
    Box(
        modifier
            .scale(if (focused) 1.04f else 1f)
            .clip(shape)
            .then(if (selected) Modifier.background(Accent) else Modifier.background(T.Card2))
            .border(2.dp, if (focused) Color.White else Color.Transparent, shape)
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = if (big) 22.dp else 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) T.Text else T.Faint, fontSize = if (big) 24.sp else 15.sp,
            fontWeight = if (big || selected) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center)
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
