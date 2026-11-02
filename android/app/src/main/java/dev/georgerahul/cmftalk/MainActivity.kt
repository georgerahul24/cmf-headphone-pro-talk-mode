package dev.georgerahul.cmftalk

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var toggleBtn: Button
    private lateinit var batteryBtn: Button
    private lateinit var permBtn: Button
    private val listener: () -> Unit = { render() }

    private val perms = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val dp = resources.displayMetrics.density
        fun pad(v: View) = v.apply { setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt()) }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt())
        }
        col.addView(TextView(this).apply { text = "CMF Talk Mode"; textSize = 26f })
        status = TextView(this).apply { textSize = 15f }
        col.addView(pad(status))

        permBtn = Button(this).apply {
            text = "1. Allow Bluetooth and notifications"
            setOnClickListener { requestPermissions(perms, 1) }
        }
        col.addView(permBtn)
        batteryBtn = Button(this).apply {
            text = "2. Allow running in background (Unrestricted)"
            setOnClickListener { askBattery() }
        }
        col.addView(batteryBtn)

        col.addView(pad(Switch(this).apply {
            text = "Talk mode button"
            textSize = 16f
            isChecked = prefs.enabled
            setOnCheckedChangeListener { _, on -> prefs.enabled = on }
        }))
        col.addView(TextView(this).apply {
            text = "When on, pressing the button you set to Spatial Audio in Nothing X toggles talk mode. " +
                "When off, that button works as normal Spatial Audio."
            textSize = 13f
            alpha = 0.7f
        })

        val volLabel = TextView(this).apply { textSize = 16f }
        fun showVol() { volLabel.text = "Music volume while talking: ${prefs.talkVolume}%" }
        showVol()
        col.addView(pad(volLabel))
        col.addView(SeekBar(this).apply {
            max = 20
            progress = prefs.talkVolume / 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                    if (user) { prefs.talkVolume = p * 5; showVol() }
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        })

        toggleBtn = Button(this).apply {
            setOnClickListener {
                startService(Intent(this@MainActivity, TalkService::class.java).setAction(TalkService.ACTION_TOGGLE))
            }
        }
        col.addView(toggleBtn)

        col.addView(pad(TextView(this).apply {
            text = "Tip: Nothing X keeps its own link to the headphones. If this app says it can't connect, " +
                "force stop Nothing X (or turn off its background use) after you set up the button."
            textSize = 13f
            alpha = 0.7f
            gravity = Gravity.START
        }))

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        TalkState.listeners.add(listener)
        if (hasPerms()) TalkService.start(this)
        render()
    }

    override fun onPause() {
        TalkState.listeners.remove(listener)
        super.onPause()
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        if (hasPerms()) {
            TalkService.start(this)
            if (!batteryOk()) askBattery()
        }
        render()
    }

    private fun hasPerms() = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun batteryOk() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun askBattery() {
        // Asks Android to stop putting this app to sleep ("Unrestricted" battery).
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun render() {
        val notifOk = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        permBtn.visibility = if (hasPerms() && notifOk) View.GONE else View.VISIBLE
        batteryBtn.visibility = if (batteryOk()) View.GONE else View.VISIBLE
        val s = TalkState
        status.text = buildString {
            append(if (hasPerms()) s.status else "Needs Bluetooth permission")
            append("\nNoise mode: ${Proto.ancName(s.ancMode)}")
            s.battery?.let { append("\nBattery: $it%") }
            append(if (s.active) "\nTalk mode is ON" else "")
        }
        toggleBtn.text = if (s.active) "End talk mode" else "Start talk mode"
        toggleBtn.isEnabled = s.status == "Connected"
    }
}
