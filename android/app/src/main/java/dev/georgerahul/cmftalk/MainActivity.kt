package dev.georgerahul.cmftalk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner

private val Black = Color(0xFF000000)
private val Card = Color(0xFF141414)
private val CardHi = Color(0xFF1F1F1F)
private val White = Color(0xFFFFFFFF)
private val Grey = Color(0xFF8A8A8A)
private val Red = Color(0xFFE5202B)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val Dot = FontFamily(
    Font(R.font.doto, FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(800), FontVariation.Setting("ROND", 100f)))
)

class MainActivity : ComponentActivity() {
    private val perms = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
    private var permTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = Prefs(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Red, background = Black, surface = Card)) {
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                DisposableEffect(lifecycle) {
                    val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) permTick++ }
                    lifecycle.addObserver(obs)
                    onDispose { lifecycle.removeObserver(obs) }
                }
                permTick // re-read setup state on resume
                Screen(
                    prefs = prefs,
                    hasBt = hasBt(),
                    hasNotif = hasNotif(),
                    batteryOk = batteryOk(),
                    askPerms = { requestPermissions(perms, 1) },
                    askBattery = ::askBattery,
                )
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

@Composable
private fun Screen(
    prefs: Prefs,
    hasBt: Boolean,
    hasNotif: Boolean,
    batteryOk: Boolean,
    askPerms: () -> Unit,
    askBattery: () -> Unit,
) {
    val st = HeadphoneState
    val svc = TalkService.instance
    val ready = st.connected && svc != null

    Column(
        Modifier
            .fillMaxSize()
            .background(Black)
            .verticalScroll(rememberScrollState())
            .systemBarsPadding()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header(st.connected, st.status, st.battery, st.charging)

        if (!hasBt || !hasNotif || !batteryOk) {
            SetupCard(hasBt, hasNotif, batteryOk, askPerms, askBattery)
        }

        TalkCard(prefs, ready, st.talkActive) { svc?.toggleTalk() }

        NoiseCard(prefs, st.anc, st.idleOff, ready, onIdleChanged = { svc?.idleSettingChanged() }) { svc?.setAnc(it) }

        SpatialCard(st.spatial, ready) { svc?.setSpatial(it) }

        Text(
            "Unofficial app. Not made by Nothing or CMF.",
            color = Grey, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun Header(connected: Boolean, status: String, battery: Int?, charging: Boolean) {
    Column(Modifier.padding(top = 18.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) Red else Grey))
            Spacer(Modifier.width(8.dp))
            Text(if (connected) "CONNECTED" else status.uppercase(), color = Grey, fontSize = 12.sp, letterSpacing = 1.5.sp)
        }
        Spacer(Modifier.height(10.dp))
        Text("CMF HEADPHONE PRO", fontFamily = Dot, color = White, fontSize = 30.sp, lineHeight = 34.sp)
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(battery?.let { "$it" } ?: "--", fontFamily = Dot, color = White, fontSize = 72.sp, lineHeight = 72.sp)
            Text("%", fontFamily = Dot, color = Grey, fontSize = 32.sp, modifier = Modifier.padding(bottom = 10.dp, start = 4.dp))
            if (charging) Text("  CHARGING", color = Red, fontSize = 12.sp, letterSpacing = 1.5.sp,
                modifier = Modifier.padding(bottom = 16.dp))
        }
        if (battery != null) BatteryDots(battery)
    }
}

@Composable
private fun BatteryDots(level: Int) {
    val total = 20
    val on = (level * total + 50) / 100
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 8.dp)) {
        repeat(total) { i ->
            Box(Modifier.size(7.dp).clip(CircleShape).background(
                when {
                    i >= on -> CardHi
                    level <= 15 -> Red
                    else -> White
                }))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Card).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title.uppercase(), color = Grey, fontSize = 12.sp, letterSpacing = 1.5.sp)
        content()
    }
}

@Composable
private fun Segmented(options: List<Pair<Int, String>>, selected: Int?, enabled: Boolean, onPick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(CardHi).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            val bg by animateColorAsState(if (on) White else Color.Transparent, tween(180), label = "seg")
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(50)).background(bg)
                    .clickable(enabled = enabled) { onPick(value) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Black else if (enabled) White else Grey,
                    fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TalkCard(prefs: Prefs, ready: Boolean, active: Boolean, onToggle: () -> Unit) {
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var volume by remember { mutableFloatStateOf(prefs.talkVolume.toFloat()) }
    val bg by animateColorAsState(if (active) Red else Card, tween(220), label = "talk")

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(bg).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("TALK MODE", color = if (active) White else Grey, fontSize = 12.sp, letterSpacing = 1.5.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (active) "ON" else "OFF", fontFamily = Dot, color = White, fontSize = 40.sp, lineHeight = 44.sp)
                Text(
                    if (active) "Transparency on · music at ${prefs.talkVolume}%"
                    else "Press your Spatial Audio button to talk",
                    color = if (active) White else Grey, fontSize = 13.sp,
                )
            }
            Box(
                Modifier.size(64.dp).clip(CircleShape)
                    .background(if (active) White else Red)
                    .clickable(enabled = ready, onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (active) "END" else "TALK", color = if (active) Red else White,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
        AnimatedVisibility(!active) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HorizontalDivider(color = CardHi)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Button triggers talk mode", color = White, fontSize = 15.sp)
                        Text("Off: the button works as normal Spatial Audio", color = Grey, fontSize = 12.sp)
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it; prefs.enabled = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = Red, checkedThumbColor = White),
                    )
                }
                Text("Music volume while talking · ${volume.toInt()}%", color = White, fontSize = 15.sp,
                    modifier = Modifier.padding(top = 6.dp))
                Slider(
                    value = volume,
                    onValueChange = { volume = (it / 5).toInt() * 5f },
                    onValueChangeFinished = { prefs.talkVolume = volume.toInt() },
                    valueRange = 0f..50f,
                    colors = SliderDefaults.colors(thumbColor = White, activeTrackColor = Red, inactiveTrackColor = CardHi),
                )
            }
        }
    }
}

@Composable
private fun NoiseCard(
    prefs: Prefs, anc: Int?, idleOff: Boolean, ready: Boolean,
    onIdleChanged: () -> Unit, onPick: (Int) -> Unit,
) {
    val isAnc = anc in Proto.ANC_LEVELS
    var idleSaver by remember { mutableStateOf(prefs.idleOff) }
    SectionCard("Noise control") {
        Text(
            when {
                anc == null -> "-"
                isAnc -> "Noise cancelling"
                else -> Proto.ancName(anc)
            },
            fontFamily = Dot, color = White, fontSize = 26.sp,
        )
        if (idleOff) Text("Turned off to save battery. Comes back when audio plays.",
            color = Red, fontSize = 12.sp)
        Segmented(
            listOf(Proto.ANC_OFF to "Off", Proto.ANC_TRANSPARENCY to "Transparency", -1 to "ANC"),
            if (isAnc) -1 else anc, ready,
        ) { if (it == -1) onPick(if (isAnc) anc!! else Proto.ANC_ADAPTIVE) else onPick(it) }
        AnimatedVisibility(isAnc) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Level", color = Grey, fontSize = 13.sp)
                Segmented(
                    listOf(Proto.ANC_LOW to "Low", Proto.ANC_MID to "Mid", Proto.ANC_HIGH to "High",
                        Proto.ANC_ADAPTIVE to "Adaptive"),
                    anc, ready, onPick,
                )
            }
        }
        HorizontalDivider(color = CardHi)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Turn off when idle", color = White, fontSize = 15.sp)
                Text("After 10 min with nothing playing, noise control goes Off to save battery. " +
                    "It comes back when audio plays.", color = Grey, fontSize = 12.sp)
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = idleSaver,
                onCheckedChange = { idleSaver = it; prefs.idleOff = it; onIdleChanged() },
                colors = SwitchDefaults.colors(checkedTrackColor = Red, checkedThumbColor = White),
            )
        }
    }
}

@Composable
private fun SpatialCard(spatial: Int?, ready: Boolean, onPick: (Int) -> Unit) {
    SectionCard("Spatial audio") {
        Segmented(Spatial.MODES, spatial, ready, onPick)
        Text("Your talk button stays on Spatial Audio. The mode you pick here is kept.",
            color = Grey, fontSize = 12.sp)
    }
}

@Composable
private fun SetupCard(hasBt: Boolean, hasNotif: Boolean, batteryOk: Boolean, askPerms: () -> Unit, askBattery: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).border(1.dp, Red, RoundedCornerShape(22.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("SETUP", color = Red, fontSize = 12.sp, letterSpacing = 1.5.sp)
        Text("A few permissions keep the app running in the background.", color = White, fontSize = 14.sp)
        if (!hasBt || !hasNotif) SetupButton("Allow Bluetooth and notifications", askPerms)
        if (!batteryOk) SetupButton("Allow running in background", askBattery)
        Text("Also force stop Nothing X. Only one app can talk to the headphones at a time.",
            color = Grey, fontSize = 12.sp)
    }
}

@Composable
private fun SetupButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(White).clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Black, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}
