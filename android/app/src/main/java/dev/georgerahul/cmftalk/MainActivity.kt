package dev.georgerahul.cmftalk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

// ---------- look ----------

private object C {
    val Bg = Color(0xFF15141E)
    val BgTop = Color(0xFF221E36)
    val Card = Color(0xFF211F30)
    val Card2 = Color(0xFF2B293D)
    val Line = Color(0xFF363449)
    val Text = Color(0xFFF4F3FA)
    val Sub = Color(0xFF908DAA)
    val Faint = Color(0xFF5E5B78)
    val Blue1 = Color(0xFF3B6FF5)
    val Blue2 = Color(0xFF5FA2FF)
    val Purple = Color(0xFF8B5CF6)
    val Violet = Color(0xFFB46CF7)
    val Pink = Color(0xFFEC5C9A)
    val Teal = Color(0xFF35D0B2)
}

private val Accent = Brush.linearGradient(listOf(C.Purple, C.Violet))
private val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
)
private val Tnum = TextStyle(fontFeatureSettings = "tnum")

@Composable
private fun tnum() = LocalTextStyle.current.merge(Tnum)

/** What the battery saver card shows. */
private class SaverView(val big: String, val small: String, val p: Float, val c: List<Color>)

/** Settings the screen shows, kept in sync with [Prefs]. */
private class UiPrefs(val p: Prefs) {
    var talkEnabled by mutableStateOf(p.enabled)
    var talkVolume by mutableIntStateOf(p.talkVolume)
    var idleOff by mutableStateOf(p.idleOff)
}

class MainActivity : ComponentActivity() {
    private val perms = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
    private var permTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clear = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(SystemBarStyle.dark(clear), SystemBarStyle.dark(clear))
        Stats.init(this)
        val ui = UiPrefs(Prefs(this))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = C.Purple, background = C.Bg, surface = C.Card)) {
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                DisposableEffect(lifecycle) {
                    val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) permTick++ }
                    lifecycle.addObserver(obs)
                    onDispose { lifecycle.removeObserver(obs) }
                }
                permTick // re-read setup state on resume
                App(ui, Setup(hasBt(), hasNotif(), batteryOk(), { requestPermissions(perms, 1) }, ::askBattery))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasBt()) TalkService.start(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(code: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (hasBt()) {
            TalkService.start(this)
            if (!batteryOk()) askBattery()
        }
        permTick++
    }

    private fun hasBt() = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    private fun hasNotif() = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun batteryOk() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun askBattery() {
        // Asks Android to stop putting this app to sleep ("Unrestricted" battery).
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }
}

private class Setup(
    val hasBt: Boolean, val hasNotif: Boolean, val batteryOk: Boolean,
    val askPerms: () -> Unit, val askBattery: () -> Unit,
) {
    val needed get() = !hasBt || !hasNotif || !batteryOk
}

// ---------- app shell ----------

@Composable
private fun App(ui: UiPrefs, setup: Setup) {
    val st = HeadphoneState
    val svc = TalkService.instance
    val ready = st.connected && svc != null
    var tab by rememberSaveable { mutableIntStateOf(0) }

    CompositionLocalProvider(LocalTextStyle provides TextStyle(fontFamily = Poppins, color = C.Text)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to C.BgTop, 0.45f to C.Bg))) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (fadeIn(tween(280, delayMillis = 60)) +
                        slideInHorizontally(tween(380, easing = EaseOutCubic)) { it / 10 * dir })
                        .togetherWith(fadeOut(tween(150)) +
                            slideOutHorizontally(tween(260)) { -it / 10 * dir })
                },
                label = "tab",
            ) { t ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .statusBarsPadding().navigationBarsPadding()
                        .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 110.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (t) {
                        0 -> HomeTab(ui, setup) { tab = it }
                        1 -> SoundTab(ready)
                        3 -> StatsTab()
                        else -> SettingsTab(ui, setup)
                    }
                }
            }
            BottomBar(
                tab = tab, onTab = { tab = it }, talkActive = st.talkActive, ready = ready,
                setupDot = setup.needed, onTalk = { svc?.toggleTalk() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun BottomBar(
    tab: Int, onTab: (Int) -> Unit, talkActive: Boolean, ready: Boolean,
    setupDot: Boolean, onTalk: () -> Unit, modifier: Modifier,
) {
    Box(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, C.Bg.copy(alpha = 0.92f), C.Bg)))
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(68.dp)
                .shadow(24.dp, RoundedCornerShape(26.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .clip(RoundedCornerShape(26.dp)).background(C.Card)
                .border(1.dp, C.Line.copy(alpha = 0.5f), RoundedCornerShape(26.dp))
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem(Ico.Home, tab == 0, Modifier.weight(1f)) { onTab(0) }
            NavItem(Ico.Sound, tab == 1, Modifier.weight(1f)) { onTab(1) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { TalkFab(talkActive, ready, onTalk) }
            NavItem(Ico.Stats, tab == 3, Modifier.weight(1f)) { onTab(3) }
            NavItem(Ico.Settings, tab == 4, Modifier.weight(1f), dot = setupDot) { onTab(4) }
        }
    }
}

@Composable
private fun NavItem(ico: Ico, selected: Boolean, modifier: Modifier, dot: Boolean = false, onClick: () -> Unit) {
    val color by animateColorAsState(if (selected) C.Text else C.Faint, tween(250), label = "nav")
    val bar by animateDpAsState(if (selected) 16.dp else 0.dp, spring(dampingRatio = 0.6f, stiffness = 500f), label = "bar")
    Column(
        modifier.fillMaxHeight().pressable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Glyph(ico, color)
            if (dot) Box(Modifier.align(Alignment.TopEnd).size(7.dp).clip(CircleShape).background(C.Pink))
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.width(bar).height(3.dp).clip(CircleShape).background(Accent))
    }
}

@Composable
private fun TalkFab(active: Boolean, ready: Boolean, onClick: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val p by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = EaseOutCubic)), label = "p")
    val c1 by animateColorAsState(if (active) C.Pink else C.Purple, tween(400), label = "c1")
    val c2 by animateColorAsState(if (active) C.Violet else C.Violet, tween(400), label = "c2")
    Box(contentAlignment = Alignment.Center) {
        if (active) Box(
            Modifier.size(52.dp).graphicsLayer {
                scaleX = 1f + p * 0.5f; scaleY = 1f + p * 0.5f; alpha = (1f - p) * 0.6f
            }.clip(RoundedCornerShape(18.dp)).background(c1)
        )
        Box(
            Modifier.size(52.dp).graphicsLayer { alpha = if (ready) 1f else 0.45f }
                .shadow(18.dp, RoundedCornerShape(18.dp), ambientColor = c1, spotColor = c1)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(c1, c2)))
                .pressable(enabled = ready, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(active, transitionSpec = {
                (scaleIn(spring(dampingRatio = 0.55f)) + fadeIn()).togetherWith(scaleOut() + fadeOut())
            }, label = "fab") { on -> Glyph(if (on) Ico.Close else Ico.Talk, Color.White, 24.dp) }
        }
    }
}

// ---------- home ----------

@Composable
private fun HomeTab(ui: UiPrefs, setup: Setup, onTab: (Int) -> Unit) {
    val st = HeadphoneState
    val now = rememberNow()
    Reveal(0) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Accent), contentAlignment = Alignment.Center) {
                Glyph(Ico.Talk, Color.White, 20.dp)
            }
            Spacer(Modifier.width(10.dp))
            Text("talk mode", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            StatusPill(st.connected, st.status)
        }
    }
    Reveal(1) {
        Column(Modifier.padding(top = 10.dp)) {
            Text("Hello,", color = C.Sub, fontSize = 20.sp)
            Text("Headphone Pro", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, lineHeight = 36.sp)
            AnimatedVisibility(!st.connected) {
                Text(st.status, color = C.Sub, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    if (setup.needed) Reveal(2) { SetupCard(setup) }
    Reveal(3) { HeroCard(ui, now) }
    Reveal(4) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MiniTile(Modifier.weight(1f), "Noise control", ancShort(st.anc), Brush.linearGradient(listOf(C.Blue1, C.Blue2))) { onTab(1) }
            MiniTile(Modifier.weight(1f), "Spatial audio", spatialName(st.spatial), Accent) { onTab(1) }
        }
    }
    Reveal(5) { Label("Battery saver") }
    Reveal(6) { SaverCard(ui, now) }
}

@Composable
private fun StatusPill(connected: Boolean, status: String) {
    val dot by animateColorAsState(if (connected) C.Teal else C.Faint, tween(400), label = "dot")
    val blink = rememberInfiniteTransition(label = "blink")
    val a by blink.animateFloat(1f, 0.3f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    val connecting = status.startsWith("Connecting")
    Row(
        Modifier.clip(CircleShape).background(C.Card).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).graphicsLayer { alpha = if (connecting) a else 1f }.clip(CircleShape).background(dot))
        Spacer(Modifier.width(7.dp))
        Text(
            when { connected -> "Connected"; connecting -> "Connecting"; else -> "Offline" },
            color = C.Sub, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun HeroCard(ui: UiPrefs, now: Long) {
    val st = HeadphoneState
    val talk = st.talkActive
    val c1 by animateColorAsState(if (talk) C.Purple else C.Blue1, tween(600), label = "h1")
    val c2 by animateColorAsState(if (talk) C.Pink else C.Blue2, tween(600), label = "h2")
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier.fillMaxWidth()
            .shadow(28.dp, shape, ambientColor = c1, spotColor = c1)
            .clip(shape)
            .background(Brush.linearGradient(listOf(c1, c2)))
            .drawBehind {
                drawCircle(Color.White.copy(alpha = 0.08f), size.height * 0.75f, Offset(size.width * 0.95f, 0f))
                drawCircle(Color.White.copy(alpha = 0.06f), size.height * 0.45f, Offset(size.width * 0.15f, size.height * 1.1f))
            }
            .padding(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Chip(
                    when { talk -> "TALKING"; st.charging -> "CHARGING"; else -> "BATTERY" },
                )
                Spacer(Modifier.height(12.dp))
                AnimatedContent(talk, transitionSpec = {
                    (fadeIn(tween(300)) + slideInVertically { it / 3 }).togetherWith(fadeOut(tween(150)))
                }, label = "hero") { t ->
                    if (t) {
                        Text(clock(now - (st.talkSince ?: now)), fontSize = 44.sp, fontWeight = FontWeight.SemiBold,
                            color = Color.White, style = tnum())
                    } else Row(verticalAlignment = Alignment.Bottom) {
                        AnimatedContent(st.battery, transitionSpec = {
                            (slideInVertically { -it } + fadeIn()).togetherWith(slideOutVertically { it } + fadeOut())
                        }, label = "bat") { b ->
                            Text(b?.toString() ?: "--", fontSize = 48.sp, fontWeight = FontWeight.SemiBold,
                                color = Color.White, lineHeight = 52.sp)
                        }
                        Text("%", fontSize = 20.sp, color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.padding(bottom = 10.dp, start = 3.dp))
                    }
                }
                Text(
                    if (talk) "Transparency on · music at ${ui.talkVolume}%" else if (st.connected) Proto.ancName(st.anc) else "Not connected",
                    color = Color.White.copy(alpha = 0.82f), fontSize = 13.sp,
                )
            }
            Box(Modifier.size(86.dp), contentAlignment = Alignment.Center) {
                Ring(
                    if (talk) 1f else (st.battery ?: 0) / 100f, Modifier.fillMaxSize(), 8.dp,
                    Color.White.copy(alpha = 0.18f), Brush.linearGradient(listOf(Color.White, Color.White)),
                )
                Glyph(if (talk) Ico.Talk else Ico.Bolt, Color.White, 26.dp)
            }
        }
    }
}

@Composable
private fun MiniTile(modifier: Modifier, label: String, value: String, brush: Brush, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).background(C.Card).pressable(onClick = onClick).padding(16.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(brush))
        Spacer(Modifier.height(14.dp))
        Text(label, color = C.Sub, fontSize = 12.sp)
        AnimatedContent(value, transitionSpec = { fadeIn(tween(250)).togetherWith(fadeOut(tween(120))) }, label = "mini") {
            Text(it, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SaverCard(ui: UiPrefs, now: Long) {
    val st = HeadphoneState
    val quiet = st.quietSince?.let { dur(now - it) }
    val deadline = st.idleDeadline
    val s = when {
        !ui.idleOff -> SaverView("Off", "Battery saver is off. Turn it on in Settings.", 0f, listOf(C.Faint, C.Faint))
        !st.connected -> SaverView("Waiting", "Connect your headphones to start.", 0f, listOf(C.Faint, C.Faint))
        st.idleOff -> SaverView("Saving", "Noise control off for ${dur(now - (st.idleOffSince ?: now))}. " +
            "It comes back when audio plays.", 1f, listOf(C.Teal, C.Blue2))
        st.talkActive -> SaverView("Talking", "The timer waits until you finish talking.", 0f, listOf(C.Faint, C.Faint))
        st.playing -> SaverView("Playing", "The 10 minute timer starts when audio stops.", 0f, listOf(C.Faint, C.Faint))
        deadline != null -> {
            val left = (deadline - now).coerceAtLeast(0)
            SaverView(clock(left + 999), "until noise control turns off. Nothing played for $quiet.",
                1f - left.toFloat() / TalkService.IDLE_MS, listOf(C.Purple, C.Pink))
        }
        st.anc == Proto.ANC_OFF -> SaverView("Idle", "Nothing played for ${quiet ?: "a while"}. Noise control is already off.",
            0f, listOf(C.Faint, C.Faint))
        else -> SaverView("Idle", "Nothing played for ${quiet ?: "a while"}.", 0f, listOf(C.Faint, C.Faint))
    }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(78.dp), contentAlignment = Alignment.Center) {
                Ring(s.p, Modifier.fillMaxSize(), 7.dp, C.Card2, Brush.sweepGradient(s.c))
                Glyph(Ico.Leaf, if (st.idleOff) C.Teal else C.Sub, 24.dp)
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(s.big, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, style = tnum())
                Text(s.small, color = C.Sub, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
private fun SetupCard(setup: Setup) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.Card)
            .border(1.dp, Brush.linearGradient(listOf(C.Purple, C.Pink)), RoundedCornerShape(24.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Finish setup", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text("These keep the app working in the background.", color = C.Sub, fontSize = 13.sp)
        if (!setup.hasBt || !setup.hasNotif) GradientButton("Allow Bluetooth and notifications", setup.askPerms)
        if (!setup.batteryOk) GradientButton("Allow running in background", setup.askBattery)
    }
}

// ---------- sound ----------

@Composable
private fun SoundTab(ready: Boolean) {
    val st = HeadphoneState
    val svc = TalkService.instance
    val isAnc = st.anc in Proto.ANC_LEVELS
    Reveal(0) { Title("Sound", "Noise control and spatial audio") }
    Reveal(1) {
        Panel {
            Label("Noise control")
            AnimatedContent(if (isAnc) "Noise cancelling" else Proto.ancName(st.anc), transitionSpec = {
                (fadeIn(tween(250)) + slideInVertically { it / 2 }).togetherWith(fadeOut(tween(120)))
            }, label = "anc") { Text(it, fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
            Segmented(
                listOf(Proto.ANC_OFF to "Off", Proto.ANC_TRANSPARENCY to "Transparency", -1 to "ANC"),
                if (isAnc) -1 else st.anc, ready,
            ) { if (it == -1) svc?.setAnc(if (isAnc) st.anc!! else Proto.ANC_ADAPTIVE) else svc?.setAnc(it) }
            AnimatedVisibility(
                isAnc,
                enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 300f)) + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Text("Level", color = C.Sub, fontSize = 13.sp)
                    Segmented(
                        listOf(Proto.ANC_LOW to "Low", Proto.ANC_MID to "Mid", Proto.ANC_HIGH to "High",
                            Proto.ANC_ADAPTIVE to "Adaptive"),
                        st.anc, ready, Brush.linearGradient(listOf(C.Blue1, C.Blue2)),
                    ) { svc?.setAnc(it) }
                }
            }
        }
    }
    Reveal(2) {
        Panel {
            Label("Spatial audio")
            Segmented(Spatial.MODES, st.spatial, ready) { svc?.setSpatial(it) }
            Text("Your talk button stays on Spatial Audio. The mode you pick here is kept.",
                color = C.Sub, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

// ---------- stats ----------

@Composable
private fun StatsTab() {
    val st = HeadphoneState
    val now = rememberNow()
    val liveTalk = st.talkSince?.let { now - it } ?: 0L
    val liveIdle = st.idleOffSince?.let { now - it } ?: 0L
    val since = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(Stats.since))
    Reveal(0) { Title("Statistics", "Since $since") }
    Reveal(1) {
        Panel {
            val top = maxOf(Stats.talkCount, Stats.idleOffCount, Stats.restoreCount, 1).toFloat()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
                    Ring(Stats.talkCount / top, Modifier.fillMaxSize(), 9.dp, C.Card2, Brush.sweepGradient(listOf(C.Purple, C.Pink)))
                    Ring(Stats.idleOffCount / top, Modifier.size(98.dp), 9.dp, C.Card2, Brush.sweepGradient(listOf(C.Blue1, C.Blue2)))
                    Ring(Stats.restoreCount / top, Modifier.size(64.dp), 9.dp, C.Card2, Brush.sweepGradient(listOf(C.Teal, C.Blue2)))
                }
                Spacer(Modifier.width(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Legend(C.Pink, "Talk mode", Stats.talkCount)
                    Legend(C.Blue2, "Battery saver", Stats.idleOffCount)
                    Legend(C.Teal, "Auto restored", Stats.restoreCount)
                }
            }
        }
    }
    Reveal(2) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(Modifier.weight(1f), Ico.Talk, Accent, "Times talked", Stats.talkCount.toString())
            StatTile(Modifier.weight(1f), Ico.Clock, Accent, "Time talking", dur(Stats.talkMs + liveTalk))
        }
    }
    Reveal(3) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(Modifier.weight(1f), Ico.Leaf, Brush.linearGradient(listOf(C.Teal, C.Blue2)),
                "Saver turned ANC off", Stats.idleOffCount.toString())
            StatTile(Modifier.weight(1f), Ico.Bolt, Brush.linearGradient(listOf(C.Teal, C.Blue2)),
                "ANC rested for", dur(Stats.idleOffMs + liveIdle))
        }
    }
    Reveal(4) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(Modifier.weight(1f), Ico.Sound, Brush.linearGradient(listOf(C.Blue1, C.Blue2)),
                "Restored when music played", Stats.restoreCount.toString())
            StatTile(Modifier.weight(1f), Ico.Stats, Brush.linearGradient(listOf(C.Blue1, C.Blue2)),
                "Average talk", if (Stats.talkCount == 0) "-" else dur((Stats.talkMs + liveTalk) / Stats.talkCount))
        }
    }
    Reveal(5) {
        var armed by remember { mutableStateOf(false) }
        LaunchedEffect(armed) { if (armed) { delay(3000); armed = false } }
        Text(
            if (armed) "Tap again to reset" else "Reset statistics",
            color = if (armed) C.Pink else C.Faint, fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(CircleShape)
                .pressable { if (armed) { Stats.reset(); armed = false } else armed = true }
                .padding(12.dp),
        )
    }
}

@Composable
private fun Legend(color: Color, label: String, value: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, fontSize = 13.sp)
            Text("$value", color = C.Sub, fontSize = 12.sp, style = tnum())
        }
    }
}

@Composable
private fun StatTile(modifier: Modifier, ico: Ico, brush: Brush, label: String, value: String) {
    Column(modifier.height(140.dp).clip(RoundedCornerShape(22.dp)).background(C.Card).padding(16.dp)) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(brush), contentAlignment = Alignment.Center) {
            Glyph(ico, Color.White, 18.dp)
        }
        Spacer(Modifier.weight(1f))
        AnimatedContent(value, transitionSpec = {
            (slideInVertically { it / 2 } + fadeIn()).togetherWith(slideOutVertically { -it / 2 } + fadeOut())
        }, label = "stat") {
            Text(it, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, style = tnum(), maxLines = 1)
        }
        Text(label, color = C.Sub, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2)
    }
}

// ---------- settings ----------

@Composable
private fun SettingsTab(ui: UiPrefs, setup: Setup) {
    val svc = TalkService.instance
    val ctx = LocalContext.current
    Reveal(0) { Title("Settings", "How the app behaves") }
    if (setup.needed) Reveal(1) { SetupCard(setup) }
    Reveal(2) {
        Panel {
            Label("Talk mode")
            SwitchRow("Button starts talk mode", "Off: the button works as normal Spatial Audio", ui.talkEnabled) {
                ui.talkEnabled = it; ui.p.enabled = it
            }
            HorizontalDivider(color = C.Card2)
            var vol by remember { mutableFloatStateOf(ui.talkVolume.toFloat()) }
            Row {
                Text("Music volume while talking", fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text("${vol.toInt()}%", color = C.Sub, fontSize = 15.sp, style = tnum())
            }
            Slider(
                value = vol,
                onValueChange = { vol = (it / 5).toInt() * 5f },
                onValueChangeFinished = { ui.talkVolume = vol.toInt(); ui.p.talkVolume = vol.toInt() },
                valueRange = 0f..50f,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = C.Purple,
                    inactiveTrackColor = C.Card2, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
            )
        }
    }
    Reveal(3) {
        Panel {
            Label("Battery saver")
            SwitchRow("Turn off noise control when idle",
                "After 10 min with nothing playing, ANC or Transparency goes Off. It comes back when audio plays.",
                ui.idleOff) {
                ui.idleOff = it; ui.p.idleOff = it; svc?.idleSettingChanged()
            }
        }
    }
    Reveal(4) {
        Panel {
            Label("Good to know")
            Text("Force stop Nothing X. Only one app can talk to the headphones at a time.",
                color = C.Sub, fontSize = 13.sp, lineHeight = 18.sp)
            Text("The app runs in the background and starts again after a restart.",
                color = C.Sub, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
    Reveal(5) {
        val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() }
        Text("CMF Talk Mode ${version ?: ""}\nUnofficial app. Not made by Nothing or CMF.",
            color = C.Faint, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 18.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            Text(sub, color = C.Sub, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onChange(it) },
            colors = SwitchDefaults.colors(
                checkedTrackColor = C.Purple, checkedThumbColor = Color.White, checkedBorderColor = Color.Transparent,
                uncheckedTrackColor = C.Card2, uncheckedThumbColor = C.Sub, uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

// ---------- building blocks ----------

@Composable
private fun Title(title: String, sub: String) {
    Column(Modifier.padding(top = 10.dp, bottom = 2.dp)) {
        Text(title, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        Text(sub, color = C.Sub, fontSize = 14.sp)
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = C.Sub, fontSize = 14.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.Card).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun Chip(text: String) {
    Text(
        text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
        modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun GradientButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(16.dp)).background(Accent).pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

/** Pill selector with a sliding highlight. */
@Composable
private fun Segmented(
    options: List<Pair<Int, String>>, selected: Int?, enabled: Boolean,
    brush: Brush = Accent, onPick: (Int) -> Unit,
) {
    val idx = options.indexOfFirst { it.first == selected }
    val haptic = LocalHapticFeedback.current
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Card2).padding(4.dp)) {
        val w = maxWidth / options.size
        val x by animateDpAsState(w * idx.coerceAtLeast(0), spring(dampingRatio = 0.75f, stiffness = 420f), label = "seg")
        val shown by animateFloatAsState(if (idx >= 0) 1f else 0f, tween(200), label = "segA")
        Box(
            Modifier.offset(x = x).width(w).height(44.dp).graphicsLayer { alpha = shown }
                .clip(RoundedCornerShape(12.dp)).background(brush)
        )
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (value, label) ->
                val color by animateColorAsState(
                    when { i == idx -> Color.White; enabled -> C.Sub; else -> C.Faint }, tween(200), label = "segT")
                Box(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = enabled, interactionSource = null, indication = null) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onPick(value)
                        },
                    contentAlignment = Alignment.Center,
                ) { Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
            }
        }
    }
}

/** Progress ring that grows in from empty. */
@Composable
private fun Ring(progress: Float, modifier: Modifier, stroke: Dp, track: Color, brush: Brush) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(progress) { a.animateTo(progress.coerceIn(0f, 1f), tween(900, easing = EaseOutCubic)) }
    Canvas(modifier) {
        val s = stroke.toPx()
        val tl = Offset(s / 2, s / 2)
        val sz = Size(size.width - s, size.height - s)
        drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(s))
        rotate(-90f) {
            if (a.value > 0.001f) drawArc(brush, 0f, 360f * a.value, false, tl, sz, style = Stroke(s, cap = StrokeCap.Round))
        }
    }
}

/** Fades and slides content in, a little later for each index. */
@Composable
private fun Reveal(i: Int, content: @Composable () -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(45L * i); a.animateTo(1f, tween(480, easing = EaseOutCubic)) }
    Box(Modifier.fillMaxWidth().graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 28.dp.toPx() }) {
        content()
    }
}

/** Shrinks a little while pressed, with a light tap feel. */
private fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.93f else 1f, spring(dampingRatio = 0.5f, stiffness = 700f), label = "press")
    val haptic = LocalHapticFeedback.current
    graphicsLayer { scaleX = s; scaleY = s }
        .clickable(interactionSource = src, indication = null, enabled = enabled) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick()
        }
}

@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
    }
    return now
}

private enum class Ico { Home, Sound, Stats, Settings, Talk, Close, Bolt, Leaf, Clock }

/** Small line icons drawn in code. */
@Composable
private fun Glyph(ico: Ico, color: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val sw = w * 0.085f
        val st = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun o(x: Float, y: Float) = Offset(x * w, y * w)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, o(x1, y1), o(x2, y2), sw, StrokeCap.Round)
        when (ico) {
            Ico.Home -> {
                drawPath(Path().apply {
                    moveTo(.17f * w, .45f * w); lineTo(.5f * w, .18f * w); lineTo(.83f * w, .45f * w)
                    lineTo(.83f * w, .83f * w); lineTo(.17f * w, .83f * w); close()
                }, color, style = st)
                line(.5f, .83f, .5f, .64f)
            }
            Ico.Sound -> {
                line(.25f, .2f, .25f, .8f); line(.5f, .2f, .5f, .8f); line(.75f, .2f, .75f, .8f)
                drawCircle(color, w * .09f, o(.25f, .62f)); drawCircle(color, w * .09f, o(.5f, .36f))
                drawCircle(color, w * .09f, o(.75f, .56f))
            }
            Ico.Stats -> { line(.25f, .55f, .25f, .8f); line(.5f, .22f, .5f, .8f); line(.75f, .42f, .75f, .8f) }
            Ico.Settings -> {
                drawCircle(color, w * .25f, o(.5f, .5f), style = st)
                drawCircle(color, w * .08f, o(.5f, .5f))
                for (k in 0 until 8) {
                    val ang = Math.toRadians(k * 45.0)
                    val c = Math.cos(ang).toFloat(); val s = Math.sin(ang).toFloat()
                    line(.5f + c * .3f, .5f + s * .3f, .5f + c * .4f, .5f + s * .4f)
                }
            }
            Ico.Talk -> {
                drawRoundRect(color, o(.15f, .2f), Size(.7f * w, .48f * w), CornerRadius(.16f * w), style = st)
                drawPath(Path().apply {
                    moveTo(.32f * w, .68f * w); lineTo(.28f * w, .84f * w); lineTo(.48f * w, .68f * w)
                }, color, style = st)
                for (x in listOf(.36f, .5f, .64f)) drawCircle(color, w * .045f, o(x, .44f))
            }
            Ico.Close -> { line(.3f, .3f, .7f, .7f); line(.7f, .3f, .3f, .7f) }
            Ico.Bolt -> drawPath(Path().apply {
                moveTo(.56f * w, .14f * w); lineTo(.26f * w, .56f * w); lineTo(.48f * w, .56f * w)
                lineTo(.44f * w, .86f * w); lineTo(.74f * w, .44f * w); lineTo(.52f * w, .44f * w); close()
            }, color, style = st)
            Ico.Leaf -> {
                drawPath(Path().apply {
                    moveTo(.2f * w, .8f * w)
                    cubicTo(.18f * w, .4f * w, .42f * w, .18f * w, .82f * w, .18f * w)
                    cubicTo(.82f * w, .58f * w, .6f * w, .82f * w, .2f * w, .8f * w)
                }, color, style = st)
                line(.2f, .8f, .56f, .44f)
            }
            Ico.Clock -> {
                drawCircle(color, w * .34f, o(.5f, .5f), style = st)
                line(.5f, .5f, .5f, .3f); line(.5f, .5f, .64f, .58f)
            }
        }
    }
}

// ---------- text helpers ----------

private fun clock(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    val h = s / 3600; val m = s % 3600 / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private fun dur(ms: Long): String {
    val m = ms / 60_000
    return when {
        ms <= 0 -> "0 min"
        ms < 60_000 -> "${ms / 1000} s"
        m < 60 -> "$m min"
        else -> "${m / 60} h ${m % 60} min"
    }
}

private fun ancShort(m: Int?): String = when (m) {
    null -> "-"
    Proto.ANC_OFF -> "Off"
    Proto.ANC_TRANSPARENCY -> "Transparency"
    Proto.ANC_HIGH -> "ANC · High"
    Proto.ANC_MID -> "ANC · Mid"
    Proto.ANC_LOW -> "ANC · Low"
    Proto.ANC_ADAPTIVE -> "ANC · Adaptive"
    else -> Proto.ancName(m)
}

private fun spatialName(v: Int?): String = Spatial.MODES.firstOrNull { it.first == v }?.second ?: "-"
