package dev.georgerahul.cmftalk

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlin.math.roundToInt

/** Shared state between the service and the screen. Main thread only. */
object TalkState {
    var status = "Not started"
    var ancMode: Int? = null
    var battery: Int? = null
    var active = false
    val listeners = mutableSetOf<() -> Unit>()
    fun changed() = listeners.forEach { it() }
}

/**
 * Always-on foreground service. Keeps an RFCOMM link to the CMF Headphone Pro
 * and runs the talk-mode logic:
 *
 * The headphone button is set to "Spatial Audio" in Nothing X. A press makes
 * the headphones report Spatial Audio as on (seq 0xFF). We switch it back off
 * and toggle talk mode. Reports of Spatial Audio being off are ignored, so our
 * own switch-back never triggers anything.
 *
 * Talk on:  remember noise mode + music volume, set Transparency, volume 10%.
 * Talk off: restore the exact noise mode (Off / Transparency / any ANC level)
 *           and the volume from before.
 */
class TalkService : Service() {
    companion object {
        const val TAG = "CMFTalk"
        const val ACTION_TOGGLE = "dev.georgerahul.cmftalk.TOGGLE"
        const val CHANNEL = "status"
        private const val HEADPHONE_NAME = "CMF Headphone Pro"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, TalkService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private lateinit var prefs: Prefs

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var running = true
    private var worker: Thread? = null
    private val wake = Object()
    private var seq = 0

    private var savedAnc: Int? = null
    private var savedVolume: Int? = null
    private var lastPress = 0L

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // Headphones connected (or Bluetooth turned on): try right away.
            synchronized(wake) { wake.notifyAll() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        prefs = Prefs(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Talk mode status", NotificationManager.IMPORTANCE_LOW)
        )
        goForeground()
        registerReceiver(btReceiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction("android.bluetooth.adapter.action.STATE_CHANGED")
        }, RECEIVER_EXPORTED)
        worker = Thread(::connectLoop, "cmf-rfcomm").also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (intent?.action == ACTION_TOGGLE) toggle()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        unregisterReceiver(btReceiver)
        runCatching { socket?.close() }
        synchronized(wake) { wake.notifyAll() }
        writer.shutdown()
        super.onDestroy()
    }

    // ---- notification ----

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val toggle = PendingIntent.getService(this, 1,
            Intent(this, TalkService::class.java).setAction(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE)
        val text = if (TalkState.active) "Talk mode ON" else
            "${TalkState.status} · ${Proto.ancName(TalkState.ancMode)}"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle("CMF Talk Mode")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null,
                if (TalkState.active) "End talk" else "Talk", toggle).build())
            .build()
    }

    private fun goForeground() {
        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    private fun publish() {
        main.post {
            TalkState.changed()
            getSystemService(NotificationManager::class.java).notify(1, buildNotification())
        }
    }

    private fun setStatus(s: String) {
        Log.i(TAG, "status: $s")
        main.post {
            TalkState.status = s
            if (s != "Connected") { TalkState.ancMode = null; TalkState.battery = null }
            publish()
        }
    }

    // ---- connection ----

    @SuppressLint("MissingPermission")
    private fun findHeadphones(): BluetoothDevice? {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        if (!adapter.isEnabled) return null
        return runCatching {
            adapter.bondedDevices.firstOrNull { (it.name ?: "").contains(HEADPHONE_NAME) }
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun isConnected(d: BluetoothDevice): Boolean = runCatching {
        d.javaClass.getMethod("isConnected").invoke(d) as Boolean
    }.getOrDefault(true)

    @SuppressLint("MissingPermission")
    private fun connectLoop() {
        while (running) {
            val dev = findHeadphones()
            if (dev == null || !isConnected(dev)) {
                setStatus(if (dev == null) "Headphones not paired / Bluetooth off" else "Headphones not connected")
                sleep(30_000)
                continue
            }
            try {
                setStatus("Connecting...")
                val s = dev.createRfcommSocketToServiceRecord(Proto.SERVICE_UUID)
                s.connect()
                socket = s
                setStatus("Connected")
                send(Proto.ANC_GET)
                send(Proto.SPATIAL_GET)
                send(Proto.BATTERY_GET)
                readLoop(s)
            } catch (e: Exception) {
                Log.w(TAG, "connection failed: $e")
                setStatus("Could not connect (is Nothing X open?)")
            } finally {
                runCatching { socket?.close() }
                socket = null
            }
            if (running) setStatus("Disconnected")
            sleep(5_000)
        }
    }

    private fun sleep(ms: Long) {
        synchronized(wake) { runCatching { wake.wait(ms) } }
    }

    private fun readLoop(s: BluetoothSocket) {
        val parser = FrameParser()
        val buf = ByteArray(1024)
        val input = s.inputStream
        while (running) {
            val n = input.read(buf)
            if (n < 0) break
            for (f in parser.feed(buf, n)) main.post { handle(f) }
        }
    }

    // One writer thread keeps packets in order and off the main thread.
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()

    @Synchronized
    private fun send(type: Int, vararg payload: Int) {
        val s = socket ?: return
        seq = if (seq >= 250) 1 else seq + 1
        val p = Proto.encode(type, payload, seq)
        writer.execute {
            runCatching { s.outputStream.write(p); s.outputStream.flush() }
                .onFailure { Log.w(TAG, "write failed: $it") }
        }
    }

    // ---- talk-mode logic (main thread) ----

    private fun handle(f: Frame) {
        when (f.type) {
            Proto.ANC_NOTIFY, Proto.ANC_REPLY -> if (f.payload.size >= 2) {
                TalkState.ancMode = f.payload[1]; publish()
            }
            Proto.BATTERY_NOTIFY, Proto.BATTERY_REPLY -> if (f.payload.size >= 3) {
                TalkState.battery = f.payload[2] and 0x7F; publish()
            }
            Proto.SPATIAL_NOTIFY -> {
                val v = f.payload.firstOrNull() ?: return
                if (v == 0 || !prefs.enabled) return
                send(Proto.SPATIAL_SET, 0x00) // undo the Spatial Audio change
                if (f.seq == Proto.UNSOLICITED_SEQ) pressed()
            }
        }
    }

    private fun pressed() {
        val now = System.currentTimeMillis()
        if (now - lastPress < 700) return
        lastPress = now
        toggle()
    }

    private fun toggle() {
        if (TalkState.active) stopTalk() else startTalk()
    }

    private fun startTalk() {
        savedAnc = TalkState.ancMode
        savedVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        Log.i(TAG, "talk ON (saved ${Proto.ancName(savedAnc)}, vol $savedVolume)")
        send(Proto.ANC_SET, 0x01, Proto.ANC_TRANSPARENCY, 0x00)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (max * prefs.talkVolume / 100.0).roundToInt()
            .coerceIn(if (prefs.talkVolume > 0) 1 else 0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        TalkState.active = true
        publish()
    }

    private fun stopTalk() {
        Log.i(TAG, "talk OFF (restore ${Proto.ancName(savedAnc)}, vol $savedVolume)")
        savedAnc?.let { send(Proto.ANC_SET, 0x01, it, 0x00) }
        savedVolume?.let { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
        TalkState.active = false
        publish()
    }
}
