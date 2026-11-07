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
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import java.text.SimpleDateFormat
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

    // Noise mode chart colors (checked for color-blind separation on the card color).
    val ModeAnc = Color(0xFF9466F5)
    val ModeTrans = Color(0xFFC0832B)
    val ModeOff = Color(0xFF2BA88F)
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
    var talkPause by mutableStateOf(p.talkPause)
    var idleOff by mutableStateOf(p.idleOff)
    var idleMinutes by mutableIntStateOf(p.idleMinutes)
    var eqMode by mutableStateOf(p.eqMode)
    var musicEq by mutableStateOf(p.musicEq)
    var autoUpdate by mutableStateOf(p.autoUpdate)
    var alertBattery by mutableStateOf(p.alertBattery)
    var alertHearing by mutableStateOf(p.alertHearing)

    /** Settings came from the Mac: show them. */
    fun reload() {
        talkEnabled = p.enabled; talkVolume = p.talkVolume; talkPause = p.talkPause
        idleOff = p.idleOff; idleMinutes = p.idleMinutes
        eqMode = p.eqMode; musicEq = p.musicEq; autoUpdate = p.autoUpdate
        alertBattery = p.alertBattery; alertHearing = p.alertHearing
    }
}

class MainActivity : ComponentActivity() {
    companion object {
        /** The screen is open: the update installer can show its Install screen directly. */
        var visible = false
        /** Bumped on every return to the app, to re-check things like Notification access. */
        var resumes by mutableIntStateOf(0)
    }

    private val perms = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
    private var permTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clear = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(SystemBarStyle.dark(clear), SystemBarStyle.dark(clear))
        Stats.init(this)
        History.init(this)
        Sync.init(this)
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

    override fun onPause() {
        super.onPause()
        visible = false
    }

    override fun onResume() {
        super.onResume()
        visible = true
        resumes++
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
    LaunchedEffect(Sync.settingsVersion) { ui.reload() }
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
                        1 -> SoundTab(ui, ready)
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
            LogoMark(40.dp, live = st.playing || st.talkActive, fast = st.talkActive)
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
            AnimatedVisibility(
                !st.connected && !setup.needed,
                enter = expandVertically(spring(dampingRatio = 0.8f)) + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) { Box(Modifier.padding(top = 16.dp)) { ConnectCard(st.status) } }
        }
    }
    if (setup.needed) Reveal(2) { SetupCard(setup) }
    Reveal(3) { HeroCard(ui, now) }
    AnimatedVisibility(st.songTitle != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        NowPlayingCard(ui)
    }
    Reveal(4) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MiniTile(Modifier.weight(1f), "Noise control", ancShort(st.anc), Brush.linearGradient(listOf(C.Blue1, C.Blue2))) { onTab(1) }
            MiniTile(Modifier.weight(1f), "EQ", eqTitle(ui, st.eqNow), Accent) { onTab(1) }
        }
    }
    Reveal(5) { Label("Battery saver") }
    Reveal(6) { SaverCard(ui, now) }
    Reveal(7) { Label("Hearing") }
    Reveal(8) { HearingCard() }
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
                    if (talk) (if (ui.talkPause) "Transparency on · music paused" else "Transparency on · music at ${ui.talkVolume}%") else if (st.connected) Proto.ancName(st.anc) else "Not connected",
                    color = Color.White.copy(alpha = 0.82f), fontSize = 13.sp,
                )
                val b = st.battery
                if (!talk && st.connected && b != null) {
                    val f = remember(b, st.anc, now / 600_000) {
                        BatteryPredict.forecast(b, st.anc, History.loadAll(), System.currentTimeMillis())
                    }
                    Text(if (st.charging) "Charging" else BatteryPredict.hoursText(f.hoursLeft),
                        color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, style = tnum())
                    if (!st.charging) BatteryPredict.chargeText(f.daysToCharge, now)?.let {
                        Text(it, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, style = tnum())
                    }
                }
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
        st.playing -> SaverView("Playing", "The ${ui.idleMinutes} minute timer starts when audio stops.", 0f, listOf(C.Faint, C.Faint))
        deadline != null -> {
            val left = (deadline - now).coerceAtLeast(0)
            SaverView(clock(left + 999), "until noise control turns off. Nothing played for ${quiet ?: "a while"}.",
                1f - left.toFloat() / (ui.idleMinutes * 60_000L), listOf(C.Purple, C.Pink))
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
private fun SoundTab(ui: UiPrefs, ready: Boolean) {
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
    Reveal(3) { EqCard(ui) }
}

@Suppress("FunctionName")
private fun RoundedRectangle(r: Dp) = RoundedCornerShape(r)

/**
 * The app icon, drawn live: violet base with soft glows, a frosted glass panel and a five-bar
 * sound wave that bounces while audio plays (faster while talking). Same design as the launcher icon.
 */
@Composable
private fun LogoMark(size: Dp, live: Boolean, fast: Boolean) {
    val level by animateFloatAsState(if (live) 1f else 0f, tween(600), label = "logoLevel")
    val t = rememberInfiniteTransition(label = "logo")
    val phase by t.animateFloat(0f, (2 * Math.PI).toFloat() * 4, infiniteRepeatable(tween(if (fast) 3600 else 5600, easing = LinearEasing)), label = "logoPhase")
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 824f // icon grid: the 824 square of the 1024 design
        fun p(v: Float) = (v - 100f) * k
        val r = 185f * k
        drawRoundRect(Brush.linearGradient(listOf(Color(0xFF2A2347), Color(0xFF0E0D15)), Offset(p(250f), p(100f)), Offset(p(774f), p(924f))),
            cornerRadius = CornerRadius(r))
        clipPath(Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, this@Canvas.size.width, this@Canvas.size.height, CornerRadius(r))) }) {
            drawCircle(Brush.radialGradient(listOf(Color(0xBF8B5CF6), Color(0x008B5CF6)), Offset(p(640f), p(330f)), 400f * k), 400f * k, Offset(p(640f), p(330f)))
            drawCircle(Brush.radialGradient(listOf(Color(0x73EC5C9A), Color(0x00EC5C9A)), Offset(p(360f), p(730f)), 340f * k), 340f * k, Offset(p(360f), p(730f)))
        }
        val panelTl = Offset(p(232f), p(302f)); val panelSize = Size(560f * k, 420f * k)
        drawRoundRect(Brush.linearGradient(listOf(Color(0x38FFFFFF), Color(0x0FFFFFFF)), panelTl, Offset(p(792f), p(722f))),
            panelTl, panelSize, CornerRadius(132f * k))
        drawRoundRect(Color(0x59FFFFFF), panelTl, panelSize, CornerRadius(132f * k), style = Stroke(4f * k))
        val bars = listOf(392f to 96f, 452f to 176f, 512f to 250f, 572f to 176f, 632f to 96f)
        bars.forEachIndexed { i, (x, h) ->
            val wave = ((Math.sin(phase + i * 1.3) + Math.sin(phase * 0.7 + i * 2.1)) / 4 + 0.5).toFloat()
            val sc = 1 - level * 0.55f + level * 0.55f * wave * 1.6f
            val bh = (h * sc).coerceIn(38f, 380f) * k
            drawRoundRect(Color(0xF5FFFFFF), Offset(p(x) - 19f * k, p(512f) - bh / 2), Size(38f * k, bh), CornerRadius(19f * k))
        }
    }
}

/**
 * This week's listening against the WHO safe amount (80 dB for 40 hours a week), for both
 * devices. Estimated from the volume, since the headphones can't measure loudness.
 */
@Composable
private fun HearingCard() {
    Hearing.version
    val dose = Stats.allDose
    val (secs, db) = Hearing.today()
    val pct = (dose * 100).toInt()
    val color = when { dose >= 1.0 -> C.Pink; dose >= 0.6 -> Color(0xFFC0832B); else -> C.Teal }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.Card).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(78.dp), contentAlignment = Alignment.Center) {
            Ring(dose.coerceAtMost(1.0).toFloat(), Modifier.fillMaxSize(), 8.dp, C.Card2, Brush.sweepGradient(listOf(color, color)))
            Text("$pct%", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, style = tnum())
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(when { dose >= 1.0 -> "Give your ears a rest"; dose >= 0.6 -> "Getting close"; else -> "Safe so far" },
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("of this week's safe listening (WHO: 80 dB for 40 h a week)", color = C.Sub, fontSize = 12.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(6.dp))
            Text(if (secs > 0) "Today ${dur((secs * 1000).toLong())} at about ${db.toInt()} dB" else "Nothing played today",
                color = C.Sub, fontSize = 12.sp, style = tnum())
            Text("Estimated from your volume, not measured.", color = C.Faint, fontSize = 11.sp)
        }
    }
}

/**
 * What's playing: an artwork tile with bouncing music bars, the song, the artist and app, and
 * the EQ in use, and buttons to remember an EQ for this show, film, song or artist.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NowPlayingCard(ui: UiPrefs) {
    val st = HeadphoneState
    val eq = Eq.of(st.eqNow ?: "")
    val colors = when (eq) {
        Eq.MOVIE -> listOf(Color(0xFF8B5CF6), Color(0xFF3B6FF5))
        Eq.ROCK -> listOf(Color(0xFFEC5C9A), Color(0xFFC0832B))
        Eq.CLASSICAL -> listOf(Color(0xFF2BA88F), Color(0xFF3B6FF5))
        else -> listOf(Color(0xFFB46CF7), Color(0xFFEC5C9A))
    }
    val level by animateFloatAsState(if (st.playing) 1f else 0f, tween(500), label = "npLevel")
    val t = rememberInfiniteTransition(label = "np")
    val phase by t.animateFloat(0f, (2 * Math.PI).toFloat() * 3, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "npPhase")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.Card).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(colors)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(30.dp, 26.dp)) {
                val n = 4
                val w = size.width / (n * 2 - 1)
                for (i in 0 until n) {
                    val wave = ((Math.sin(phase + i * 1.7) + 1) / 2).toFloat()
                    val h = size.height * (0.25f + 0.75f * (if (level > 0f) (0.35f + 0.65f * wave) * level + 0.3f * (1 - level) else 0.3f))
                    drawRoundRect(Color.White, Offset(i * 2 * w, size.height - h), Size(w, h), CornerRadius(w / 2))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (st.songIsVideo) "Now watching" else "Now playing", color = C.Sub, fontSize = 11.sp)
            Text(st.songTitle ?: "", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.basicMarquee())
            Text(listOfNotNull(st.songArtist, st.eqApp).joinToString(" · "), color = C.Sub, fontSize = 12.sp, maxLines = 1,
                modifier = Modifier.basicMarquee())
            Spacer(Modifier.height(6.dp))
            val learned = st.learnedFor
            val line = when {
                ui.eqMode != EqMode.AUTO -> "EQ: ${EqMode.label(ui.eqMode)}"
                learned != null -> "Remembered for \"$learned\" → ${EqMode.label(st.eqNow ?: "")} EQ"
                st.songIsVideo -> "Video → Movie EQ"
                else -> "Music → ${EqMode.label(st.eqNow ?: ui.musicEq)} EQ"
            }
            AnimatedContent(line, transitionSpec = {
                (fadeIn(tween(250)) + slideInVertically { it / 2 }).togetherWith(fadeOut(tween(120)))
            }, label = "npLine") {
                Text(it, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(CircleShape).background(Brush.horizontalGradient(colors.map { c -> c.copy(alpha = 0.55f) }))
                        .padding(horizontal = 10.dp, vertical = 3.dp))
            }
            // Remember an EQ for this show, film or song (and its artist or channel). Tap again to forget.
            if (st.songTitle != null) {
                Learned.version
                val chosen = Learned.forTitle(st.songTitle)
                Spacer(Modifier.height(8.dp))
                Text("Remember for this", color = C.Faint, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    for (e in Eq.presets) {
                        val on = chosen == e.id
                        Text(e.label, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            color = if (on) Color.White else C.Sub,
                            modifier = Modifier.clip(CircleShape)
                                .background(if (on) Brush.horizontalGradient(colors) else Brush.horizontalGradient(listOf(C.Card2, C.Card2)))
                                .pressable {
                                    Learned.set(st.songTitle, st.songArtist, if (on) null else e.id)
                                    TalkService.instance?.eqSettingChanged()
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }
        }
    }
}

/** The EQ curve with a soft shadow under it, a 0 dB line and a dot on each band. */
@Composable
private fun EqCurve(eq: Eq?, height: Dp, full: Boolean) {
    val fs = Eq.PLOT_FREQS
    val g = remember(eq) { fs.map { eq?.gain(it) ?: 0.0 } }
    val range = 8.0
    Column {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val w = size.width; val h = size.height
            fun x(f: Double) = (Math.log10(f / 20) / 3).toFloat() * w
            fun y(db: Double) = h / 2 - (db.coerceIn(-range, range) / range).toFloat() * h / 2
            val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 6f))
            if (full) {
                for (db in listOf(-6.0, 6.0)) drawLine(C.Card2, Offset(0f, y(db)), Offset(w, y(db)), 2f, pathEffect = dash)
                for (f in listOf(100.0, 1000.0, 10000.0)) drawLine(C.Card2, Offset(x(f), 0f), Offset(x(f), h), 2f, pathEffect = dash)
            }
            drawLine(C.Faint.copy(alpha = 0.7f), Offset(0f, y(0.0)), Offset(w, y(0.0)), 2f)
            val line = Path()
            val area = Path().apply { moveTo(0f, y(0.0)) }
            fs.forEachIndexed { i, f ->
                if (i == 0) line.moveTo(x(f), y(g[i])) else line.lineTo(x(f), y(g[i]))
                area.lineTo(x(f), y(g[i]))
            }
            area.lineTo(w, y(0.0)); area.close()
            // the shadow: area between the curve and 0 dB
            drawPath(area, Brush.verticalGradient(listOf(C.Purple.copy(alpha = 0.5f), C.Purple.copy(alpha = 0.08f))))
            drawPath(line, Brush.horizontalGradient(listOf(C.Purple, C.Violet, C.Pink)),
                style = Stroke(width = if (full) 5f else 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (full && eq != null) for (b in eq.bands) {
                val c = Offset(x(b.freq.toDouble()), y(eq.gain(b.freq.toDouble())))
                drawCircle(C.Card, 9f, c)
                drawCircle(Color.White, 6f, c)
            }
        }
        if (full) BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp)) {
            for ((f, t) in listOf(20.0 to "20", 100.0 to "100", 1000.0 to "1k", 10000.0 to "10k", 20000.0 to "20k")) {
                val pos = (Math.log10(f / 20) / 3).toFloat()
                Text(t, color = C.Faint, fontSize = 10.sp, style = tnum(),
                    modifier = Modifier.offset(x = (maxWidth * pos - 8.dp).coerceIn(0.dp, maxWidth - 24.dp)))
            }
        }
    }
}

/** Every band of a preset, low to high: frequency and gain. */
@Composable
private fun EqBandList(eq: Eq) {
    val bands = eq.bands.sortedBy { it.freq }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in bands.chunked(4)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (b in row) Column(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(C.Card2).padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(Eq.freqText(b.freq), color = C.Sub, fontSize = 11.sp, style = tnum(), maxLines = 1)
                Text(Eq.gainText(b.gain), color = if (b.gain >= 0) C.Text else C.Sub, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, style = tnum(), maxLines = 1)
            }
            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

private val EqModes = listOf(EqMode.AUTO, Eq.MOVIE.id, Eq.POP.id, Eq.ROCK.id, Eq.CLASSICAL.id, Eq.FLAT.id)

/** "Auto (Movie)" in Auto mode, else the preset name. */
private fun eqTitle(ui: UiPrefs, now: String?): String =
    if (ui.eqMode == EqMode.AUTO) "Auto (${EqMode.label(now ?: ui.musicEq)})" else EqMode.label(ui.eqMode)

@Composable
private fun EqCard(ui: UiPrefs) {
    val st = HeadphoneState
    val ctx = LocalContext.current
    val svc = TalkService.instance
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("EQ")
            Spacer(Modifier.weight(1f))
            AnimatedContent(eqTitle(ui, st.eqNow), transitionSpec = {
                (fadeIn(tween(250)) + slideInVertically { it / 2 }).togetherWith(fadeOut(tween(120)))
            }, label = "eqNow") { Text(it, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        }
        // Two rows of three, so the names fit on a phone.
        for (row in EqModes.chunked(3)) Segmented(row.map { EqModes.indexOf(it) to EqMode.label(it) }, EqModes.indexOf(ui.eqMode), true) {
            ui.eqMode = EqModes[it]; ui.p.eqMode = EqModes[it]; svc?.eqSettingChanged()
        }
        // The preset in use (Auto shows what it picked).
        val shown = Eq.of(if (ui.eqMode == EqMode.AUTO) st.eqNow ?: ui.musicEq else ui.eqMode)
        Row {
            Text(shown?.let { "${it.label} curve" } ?: "Flat", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            if (shown == Eq.MOVIE) Text("plus the Energy Slider at full", color = C.Faint, fontSize = 11.sp)
        }
        Crossfade(shown, animationSpec = tween(300), label = "eqCurve") { EqCurve(it, 130.dp, true) }
        AnimatedVisibility(shown != null) { shown?.let { EqBandList(it) } }
        Text("All presets", color = C.Sub, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (e in Eq.entries.filter { it != Eq.FLAT }) {
                val on = ui.eqMode == e.id
                Column(
                    Modifier.weight(1f).clip(RoundedRectangle(12.dp)).background(if (on) C.Card2 else C.Card2.copy(alpha = 0.4f))
                        .border(1.dp, if (on) C.Purple else Color.Transparent, RoundedRectangle(12.dp))
                        .pressable { ui.eqMode = e.id; ui.p.eqMode = e.id; svc?.eqSettingChanged() }
                        .padding(8.dp),
                ) {
                    Text(e.label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (on) C.Text else C.Sub)
                    Spacer(Modifier.height(4.dp))
                    EqCurve(e, 40.dp, false)
                }
            }
        }
        AnimatedContent(ui.eqMode, transitionSpec = {
            (fadeIn() + expandVertically()).togetherWith(fadeOut() + shrinkVertically())
        }, label = "eqAbout") { mode ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val about = when (mode) {
                    EqMode.AUTO -> "Movie while you watch something, your music preset while you listen. " +
                        "It changes by itself when a different app starts playing."
                    else -> Eq.of(mode)?.about ?: ""
                }
                Text(about, color = C.Sub, fontSize = 13.sp, lineHeight = 18.sp)
                if (mode == EqMode.AUTO) {
                    Text("Music plays with", color = C.Sub, fontSize = 13.sp)
                    val music = listOf(Eq.POP, Eq.ROCK, Eq.CLASSICAL)
                    Segmented(music.mapIndexed { i, e -> i to e.label }, music.indexOfFirst { it.id == ui.musicEq }, true) {
                        val id = music[it].id
                        ui.musicEq = id; ui.p.musicEq = id; svc?.eqSettingChanged()
                    }
                    if (st.songTitle != null) NowPlayingCard(ui)
                    st.eqApp?.let { Text("Last playing: $it", color = C.Faint, fontSize = 12.sp) }
                    MainActivity.resumes
                    val access = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx)
                        .contains(ctx.packageName)
                    if (!access) {
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Card2)
                                .pressable {
                                    runCatching {
                                        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                    }
                                }.padding(14.dp),
                        ) {
                            Text("Let Auto see which app is playing", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("Turn on CMF Talk Mode under Notification access. It only checks which app plays, " +
                                "it doesn't read your notifications. Without it, Auto goes by what the app says it plays. " +
                                "If Android says it's restricted: App info, three dots, Allow restricted settings.",
                                color = C.Sub, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                    }
                }
            }
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
    val since = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(Stats.allSince))
    Reveal(0) { Title("Statistics", "Since $since") }
    // Re-read the log when something new is recorded, and once a minute.
    val hv = remember(History.version, now / 60_000) { HistoryView(History.loadAll(), System.currentTimeMillis()) }
    var day by rememberSaveable { mutableIntStateOf(6) }
    Reveal(1) { UsageCard(hv, day) { day = it } }
    Reveal(2) { ModesCard(hv, day) }
    Reveal(3) { BatteryCard(hv, day) }
    Reveal(4) { Label("Talk mode and battery saver") }
    Reveal(1) {
        Panel {
            val top = maxOf(Stats.allTalkCount, Stats.allIdleOffCount, Stats.allRestoreCount, 1).toFloat()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
                    Ring(Stats.allTalkCount / top, Modifier.fillMaxSize(), 9.dp, C.Card2, Brush.sweepGradient(listOf(C.Purple, C.Pink)))
                    Ring(Stats.allIdleOffCount / top, Modifier.size(98.dp), 9.dp, C.Card2, Brush.sweepGradient(listOf(C.Blue1, C.Blue2)))
                    Ring(Stats.allRestoreCount / top, Modifier.size(64.dp), 9.dp, C.Card2, Brush.sweepGradient(listOf(C.Teal, C.Blue2)))
                }
                Spacer(Modifier.width(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Legend(C.Pink, "Talk mode", Stats.allTalkCount)
                    Legend(C.Blue2, "Battery saver", Stats.allIdleOffCount)
                    Legend(C.Teal, "Auto restored", Stats.allRestoreCount)
                }
            }
        }
    }
    Reveal(2) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(Modifier.weight(1f), Ico.Talk, Accent, "Times talked", Stats.allTalkCount.toString())
            StatTile(Modifier.weight(1f), Ico.Clock, Accent, "Time talking", dur(Stats.allTalkMs + liveTalk))
        }
    }
    Reveal(3) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(Modifier.weight(1f), Ico.Leaf, Brush.linearGradient(listOf(C.Teal, C.Blue2)),
                "Saver turned ANC off", Stats.allIdleOffCount.toString())
            StatTile(Modifier.weight(1f), Ico.Bolt, Brush.linearGradient(listOf(C.Teal, C.Blue2)),
                "ANC rested for", dur(Stats.allIdleOffMs + liveIdle))
        }
    }
    Reveal(4) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(Modifier.weight(1f), Ico.Sound, Brush.linearGradient(listOf(C.Blue1, C.Blue2)),
                "Restored when music played", Stats.allRestoreCount.toString())
            StatTile(Modifier.weight(1f), Ico.Stats, Brush.linearGradient(listOf(C.Blue1, C.Blue2)),
                "Average talk", if (Stats.allTalkCount == 0) "-" else dur((Stats.allTalkMs + liveTalk) / Stats.allTalkCount))
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
            Text("When you talk", color = C.Sub, fontSize = 13.sp)
            Segmented(listOf(0 to "Lower music", 1 to "Pause music"), if (ui.talkPause) 1 else 0, true) {
                ui.talkPause = it == 1; ui.p.talkPause = it == 1
            }
            AnimatedContent(ui.talkPause, transitionSpec = {
                (fadeIn() + expandVertically()).togetherWith(fadeOut() + shrinkVertically())
            }, label = "talkMusic") { pause ->
                if (pause) {
                    Text("Music pauses when talk mode starts and plays again when it ends. If nothing was playing, nothing changes.",
                        color = C.Sub, fontSize = 13.sp, lineHeight = 18.sp)
                } else Column {
                    var vol by remember(ui.talkVolume) { mutableFloatStateOf(ui.talkVolume.toFloat()) }
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
        }
    }
    Reveal(3) {
        Panel {
            Label("Battery saver")
            SwitchRow("Turn off noise control when idle",
                "After ${ui.idleMinutes} min with nothing playing, ANC or Transparency goes Off. It comes back when audio plays.",
                ui.idleOff) {
                ui.idleOff = it; ui.p.idleOff = it; svc?.idleSettingChanged()
            }
            AnimatedVisibility(ui.idleOff, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Wait before turning off", color = C.Sub, fontSize = 13.sp)
                    Segmented(listOf(5, 10, 15, 30, 60).map { it to "$it min" }, ui.idleMinutes, true) {
                        ui.idleMinutes = it; ui.p.idleMinutes = it; svc?.idleSettingChanged()
                    }
                }
            }
        }
    }
    Reveal(4) {
        Panel {
            Label("Your data")
            Text("Every connection, noise mode change and battery reading is kept on this phone and never deleted.",
                color = C.Sub, fontSize = 13.sp, lineHeight = 18.sp)
            GradientButton("Export history (CSV)") {
                runCatching {
                    val f = History.exportCsv(ctx)
                    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "dev.georgerahul.cmftalk.files", f)
                    val send = Intent(Intent.ACTION_SEND).setType("text/csv")
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    ctx.startActivity(Intent.createChooser(send, "Export history"))
                }
            }
        }
    }
    Reveal(5) {
        Panel {
            Label("Reminders")
            SwitchRow("Low battery", "A notification when the headphones drop below 10%. Syncs with the Mac.", ui.alertBattery) {
                ui.alertBattery = it; ui.p.alertBattery = it
            }
            HorizontalDivider(color = C.Card2)
            SwitchRow("Hearing safety", "A notice after 30 minutes of loud listening, and when you pass this week's safe amount.", ui.alertHearing) {
                ui.alertHearing = it; ui.p.alertHearing = it
            }
        }
    }
    Reveal(5) { SyncPanel() }
    Reveal(5) {
        Panel {
            Label("Updates")
            SwitchRow("Update automatically",
                "Checks GitHub twice a day and installs new versions. The first time, Android asks you to tap Install.",
                ui.autoUpdate) { ui.autoUpdate = it; ui.p.autoUpdate = it }
            Updater.status?.let { Text(it, color = C.Sub, fontSize = 13.sp) }
            GradientButton(if (Updater.busy) "Checking..." else "Check for updates") {
                if (!Updater.busy) Updater.check(ctx, install = true)
            }
            AnimatedVisibility(Updater.failed) {
                Text("Open the download page", color = C.Purple, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.pressable {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.PAGE))) }
                    }.padding(vertical = 4.dp))
            }
        }
    }
    Reveal(5) {
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

private fun ago(ms: Long, now: Long): String {
    val m = (now - ms) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 24 * 60 -> "${m / 60} h ago"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
    }
}

@Composable
private fun SyncPanel() {
    val now = rememberNow()
    Panel {
        Label("Sync with your Mac")
        val name = Sync.peerName ?: "your Mac"
        if (Sync.peerId == null) {
            Text("Syncs history, stats and every setting with the Mac app over your Wi-Fi. Nothing goes to the internet, and everything is encrypted.",
                color = C.Sub, fontSize = 13.sp, lineHeight = 18.sp)
            Text("On the Mac: open the app, Settings, Pair a phone. Then type the code here.",
                color = C.Sub, fontSize = 13.sp, lineHeight = 18.sp)
            var code by remember { mutableStateOf("") }
            androidx.compose.material3.OutlinedTextField(
                value = code,
                onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
                label = { Text("6-digit code") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                textStyle = tnum().copy(fontSize = 22.sp, letterSpacing = 6.sp),
                modifier = Modifier.fillMaxWidth(),
            )
            GradientButton(if (Sync.pairing) "Pairing..." else "Pair with Mac") {
                if (!Sync.pairing && code.length == 6) Sync.pair(code)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (Sync.connected) C.Teal else C.Faint))
                Spacer(Modifier.width(8.dp))
                Text(if (Sync.connected) "Connected to $name" else "$name is not on this network",
                    fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            if (Sync.lastSync > 0) Text("Last synced ${ago(Sync.lastSync, now)}", color = C.Sub, fontSize = 13.sp)
            History.version
            val waiting = Sync.waitingText
            Text(waiting ?: "Everything from this phone is synced",
                color = if (waiting == null) C.Teal else C.Text, fontSize = 13.sp)
            Text("History: ${History.ownCount} events from this phone, ${Sync.haveFromPeer} from $name. Settings and stats sync too.",
                color = C.Sub, fontSize = 12.sp, lineHeight = 17.sp)
            GradientButton("Sync now") { Sync.syncNow() }
            var armed by remember { mutableStateOf(false) }
            Text(if (armed) "Tap again to unpair" else "Unpair", color = if (armed) C.Pink else C.Faint, fontSize = 13.sp,
                modifier = Modifier.pressable { if (armed) { Sync.unpair(); armed = false } else armed = true }.padding(vertical = 4.dp))
        }
        Sync.note?.let { Text(it, color = C.Sub, fontSize = 13.sp, lineHeight = 18.sp) }
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

// ---------- history charts ----------

private fun modeColor(m: ModeGroup) = when (m) {
    ModeGroup.ANC -> C.ModeAnc
    ModeGroup.TRANSPARENCY -> C.ModeTrans
    ModeGroup.OFF -> C.ModeOff
}

private fun modeLabel(m: ModeGroup) = when (m) {
    ModeGroup.ANC -> "Noise cancelling"
    ModeGroup.TRANSPARENCY -> "Transparency"
    ModeGroup.OFF -> "Off"
}

private val timeFmt: DateFormat get() = DateFormat.getTimeInstance(DateFormat.SHORT)

private fun dayName(hv: HistoryView, i: Int): String = when (i) {
    6 -> "Today"
    5 -> "Yesterday"
    else -> SimpleDateFormat("EEE d MMM").format(Date(hv.days[i]))
}

/** Seven bars of time with the headphones connected. Tap a bar to pick the day. */
@Composable
private fun UsageCard(hv: HistoryView, day: Int, onDay: (Int) -> Unit) {
    val top = maxOf(hv.usage.maxOrNull() ?: 0L, 60_000L).toFloat()
    val week = hv.usage.sum()
    val haptic = LocalHapticFeedback.current
    Panel {
        Label("Headphone time")
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                AnimatedContent(dur(hv.usage[day]), transitionSpec = {
                    (slideInVertically { it / 2 } + fadeIn()).togetherWith(slideOutVertically { -it / 2 } + fadeOut())
                }, label = "usage") { Text(it, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, style = tnum()) }
                Text(dayName(hv, day), color = C.Sub, fontSize = 13.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(dur(week), fontSize = 15.sp, fontWeight = FontWeight.Medium, style = tnum())
                Text("last 7 days", color = C.Sub, fontSize = 12.sp)
            }
        }
        Row(Modifier.fillMaxWidth().height(130.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            hv.usage.forEachIndexed { i, ms ->
                val h = remember { Animatable(0f) }
                LaunchedEffect(ms) { h.animateTo(ms / top, tween(700, delayMillis = i * 40, easing = EaseOutCubic)) }
                val sel = i == day
                val color by animateColorAsState(if (sel) C.ModeAnc else C.ModeAnc.copy(alpha = 0.35f), tween(250), label = "bar")
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .clickable(interactionSource = null, indication = null) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onDay(i)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        Box(
                            Modifier.fillMaxWidth(0.7f).fillMaxHeight(h.value.coerceIn(0.02f, 1f))
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(color)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(SimpleDateFormat("EEEEE").format(Date(hv.days[i])),
                        color = if (sel) C.Text else C.Faint, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** When each noise mode was used on the picked day, as a 24 hour strip. */
@Composable
private fun ModesCard(hv: HistoryView, day: Int) {
    val spans = remember(hv, day) { hv.spansOn(day) }
    val totals = remember(hv, day) { hv.modeTotals(day) }
    val all = totals.values.sum().coerceAtLeast(1)
    var picked by remember(hv, day) { mutableStateOf<HistoryView.Span?>(null) }
    val start = hv.days[day]
    val span = (hv.dayEnd(day) - start).toFloat()
    val grow = remember(day) { Animatable(0f) }
    LaunchedEffect(day) { grow.animateTo(1f, tween(700, easing = EaseOutCubic)) }
    Panel {
        Row {
            Label("Noise control")
            Spacer(Modifier.weight(1f))
            Text(dayName(hv, day), color = C.Faint, fontSize = 12.sp)
        }
        Canvas(
            Modifier.fillMaxWidth().height(26.dp).pointerInput(spans) {
                detectTapGestures { o ->
                    val t = start + (o.x / size.width * span).toLong()
                    picked = spans.firstOrNull { t in it.start..it.end }
                }
            }
        ) {
            val r = CornerRadius(4.dp.toPx())
            drawRoundRect(C.Card2, cornerRadius = r)
            val gap = 2.dp.toPx()
            val reveal = size.width * grow.value
            for (sp in spans) {
                var x0 = (sp.start - start) / span * size.width
                var x1 = (sp.end - start) / span * size.width
                if (x1 - x0 > gap * 2) { x0 += gap / 2; x1 -= gap / 2 }
                x1 = minOf(x1, reveal)
                if (x1 <= x0) continue
                val sel = picked == null || picked === sp
                drawRoundRect(modeColor(sp.mode).copy(alpha = if (sel) 1f else 0.4f),
                    Offset(x0, 0f), Size(maxOf(x1 - x0, 1.5f), size.height), r)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00", "06", "12", "18", "24").forEach { Text(it, color = C.Faint, fontSize = 10.sp, style = tnum()) }
        }
        AnimatedContent(picked, transitionSpec = { fadeIn(tween(200)).togetherWith(fadeOut(tween(120))) }, label = "pick") { p ->
            Text(
                if (p == null) { if (spans.isEmpty()) "No data for this day yet." else "Tap the strip to see times." }
                else "${modeLabel(p.mode)} · ${timeFmt.format(Date(p.start))} to ${timeFmt.format(Date(p.end))}",
                color = C.Sub, fontSize = 12.sp,
            )
        }
        ModeGroup.entries.forEach { m ->
            val ms = totals[m] ?: 0L
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(modeColor(m)))
                Spacer(Modifier.width(10.dp))
                Text(modeLabel(m), fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(dur(ms), fontSize = 14.sp, fontWeight = FontWeight.Medium, style = tnum())
                Text("  ${(ms * 100 / all)}%", color = C.Sub, fontSize = 12.sp, style = tnum(),
                    modifier = Modifier.width(44.dp), textAlign = TextAlign.End)
            }
        }
    }
}

/** Battery level across the picked day. Touch and drag to read a point. */
@Composable
private fun BatteryCard(hv: HistoryView, day: Int) {
    val pts = remember(hv, day) { hv.batteryOn(day) }
    val start = hv.days[day]
    val span = (hv.dayEnd(day) - start).toFloat()
    var touchX by remember(day) { mutableStateOf<Float?>(null) }
    var width by remember { mutableFloatStateOf(1f) }
    val near = touchX?.let { x ->
        val t = start + (x / width * span).toLong()
        pts.minByOrNull { kotlin.math.abs(it.first - t) }
    }
    val grow = remember(day) { Animatable(0f) }
    LaunchedEffect(day) { grow.animateTo(1f, tween(900, easing = EaseOutCubic)) }
    val line = C.Blue2
    Panel {
        Row(verticalAlignment = Alignment.Bottom) {
            Label("Battery")
            Spacer(Modifier.weight(1f))
            Text(
                near?.let { "${timeFmt.format(Date(it.first))} · ${it.second}%" } ?: dayName(hv, day),
                color = if (near != null) C.Text else C.Faint, fontSize = 12.sp, style = tnum(),
            )
        }
        if (pts.size < 2) {
            Text("No battery readings for this day yet.", color = C.Sub, fontSize = 13.sp)
        } else Row {
            Column(Modifier.height(140.dp).padding(end = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                listOf("100", "50", "0").forEach { Text(it, color = C.Faint, fontSize = 10.sp, style = tnum()) }
            }
            Canvas(
                Modifier.weight(1f).height(140.dp)
                    .pointerInput(pts) {
                        detectTapGestures(onPress = { o -> touchX = o.x; tryAwaitRelease(); touchX = null })
                    }
                    .pointerInput(pts) {
                        detectDragGestures(
                            onDragStart = { touchX = it.x },
                            onDragEnd = { touchX = null },
                            onDragCancel = { touchX = null },
                        ) { change, _ -> touchX = change.position.x.coerceIn(0f, size.width.toFloat()) }
                    }
            ) {
                width = size.width
                val h = size.height
                fun x(t: Long) = (t - start) / span * size.width
                fun y(v: Int) = h - v / 100f * h
                listOf(0, 50, 100).forEach { v ->
                    drawLine(C.Line.copy(alpha = 0.6f), Offset(0f, y(v)), Offset(size.width, y(v)), 1f)
                }
                // Break the line where there is no reading for 3 hours.
                val runs = ArrayList<MutableList<Pair<Long, Int>>>()
                for (p in pts) {
                    val cur = runs.lastOrNull()
                    if (cur == null || p.first - cur.last().first > 3 * 3600_000L) runs += mutableListOf(p) else cur += p
                }
                val clipX = size.width * grow.value
                for (run in runs) {
                    val path = Path()
                    run.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.first), y(p.second)) else path.lineTo(x(p.first), y(p.second)) }
                    val area = Path().apply {
                        addPath(path)
                        lineTo(x(run.last().first), h); lineTo(x(run.first().first), h); close()
                    }
                    clipRect(right = clipX) {
                        drawPath(area, Brush.verticalGradient(listOf(line.copy(alpha = 0.28f), line.copy(alpha = 0f))))
                        drawPath(path, line, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
                near?.let { p ->
                    val px = x(p.first); val py = y(p.second)
                    drawLine(C.Sub.copy(alpha = 0.6f), Offset(px, 0f), Offset(px, h), 1.dp.toPx())
                    drawCircle(C.Card, 6.dp.toPx(), Offset(px, py))
                    drawCircle(line, 4.dp.toPx(), Offset(px, py))
                }
            }
        }
        if (pts.size >= 2) Row(Modifier.fillMaxWidth().padding(start = 26.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00", "06", "12", "18", "24").forEach { Text(it, color = C.Faint, fontSize = 10.sp, style = tnum()) }
        }
    }
}

/** Shown on Home while the headphones aren't connected. */
@Composable
private fun ConnectCard(status: String) {
    val ctx = LocalContext.current
    val busy = status.startsWith("Connecting")
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Headphones not connected", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text("Turn them on, then tap Connect.", color = C.Sub, fontSize = 12.sp)
            }
        }
        Box(
            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(16.dp)).background(Accent)
                .graphicsLayer { alpha = if (busy) 0.6f else 1f }
                .pressable(enabled = !busy) { TalkService.instance?.connectNow() ?: TalkService.start(ctx) },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(busy, label = "conn") { b ->
                if (b) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Connecting", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                } else Text("Connect", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
        Text(
            "Open Bluetooth settings",
            color = C.Sub, fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(CircleShape)
                .pressable { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }.padding(8.dp),
        )
    }
}

