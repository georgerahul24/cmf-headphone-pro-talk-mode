package dev.georgerahul.cmftalk

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Always-on foreground service. Keeps an RFCOMM link to the CMF Headphone Pro,
 * mirrors its state into [HeadphoneState], sends settings from the screen, and
 * runs talk mode.
 *
 * Talk trigger: a headphone button is set to "Spatial Audio". The app keeps a
 * "chosen" Spatial Audio mode. When the headphones report a different mode on
 * their own (seq 0xFF), that was a button press: we switch Spatial Audio back
 * to the chosen mode and toggle talk mode. Our own changes match the chosen
 * mode, so they never trigger anything.
 *
 * Talk on:  remember noise mode + music volume, set Transparency, volume 10%.
 * Talk off: restore the exact noise mode and the volume from before.
 *
 * Idle saver: after a set time (10 minutes by default) with nothing playing on the phone, noise
 * control is set to Off to save the headphone battery. When audio starts
 * again, the old mode comes back.
 */
class TalkService : Service() {
    companion object {
        const val TAG = "CMFTalk"
        const val ACTION_TOGGLE = "dev.georgerahul.cmftalk.TOGGLE"
        const val ACTION_IDLE = "dev.georgerahul.cmftalk.IDLE"
        const val CHANNEL = "status"
        private const val HEADPHONE_NAME = "CMF Headphone Pro"

        /** The running service, for the screen to send commands. Main thread only. */
        var instance: TalkService? = null
            private set

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, TalkService::class.java))
        }
    }

    private val st = HeadphoneState
    private val main = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private lateinit var prefs: Prefs

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var running = true
    private val wake = Object()
    private val writer = Executors.newSingleThreadExecutor()
    private var seq = 0

    private var spatialChosen: Int? = null
    private var savedAnc: Int? = null
    private var savedVolume: Int? = null
    /** True if talk mode paused the music, so ending it should play again. */
    private var pausedMusic = false
    private var lastPress = 0L

    /** EQ preset sent to the headphones since they connected, and what Auto picked last. */
    private var eqApplied: String? = null
    private var autoPick: String? = null
    private val updateTick = object : Runnable {
        override fun run() {
            if (prefs.autoUpdate && !st.talkActive) Updater.check(this@TalkService, install = true)
            main.postDelayed(this, 12 * 3600_000L)
        }
    }

    /** Noise mode from before the idle saver turned it off. */
    private var idleSavedAnc: Int? = null
    private var idleScheduled = false
    private lateinit var alarms: AlarmManager
    private val idleIntent by lazy {
        PendingIntent.getService(this, 2, Intent(this, TalkService::class.java).setAction(ACTION_IDLE),
            PendingIntent.FLAG_IMMUTABLE)
    }
    private val playbackCb = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = playbackChanged()
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // Headphones connected or Bluetooth turned on: try right away.
            synchronized(wake) { wake.notifyAll() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        audio = getSystemService(AudioManager::class.java)
        prefs = Prefs(this)
        Stats.init(this)
        History.init(this)
        Sync.init(this)
        Sync.onSettingsApplied = {
            idleSettingChanged()
            eqSettingChanged()
            refreshNotification()
        }
        Sync.start()
        alarms = getSystemService(AlarmManager::class.java)
        audio.registerAudioPlaybackCallback(playbackCb, main)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Status", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        goForeground()
        registerReceiver(btReceiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction("android.bluetooth.adapter.action.STATE_CHANGED")
        }, RECEIVER_EXPORTED)
        Thread(::connectLoop, "cmf-rfcomm").start()
        main.postDelayed(updateTick, 2 * 60_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_TOGGLE -> toggleTalk()
            ACTION_IDLE -> idleCheck()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        running = false
        unregisterReceiver(btReceiver)
        main.removeCallbacks(recheck)
        main.removeCallbacks(updateTick)
        Sync.stop()
        audio.unregisterAudioPlaybackCallback(playbackCb)
        cancelIdle()
        runCatching { socket?.close() }
        synchronized(wake) { wake.notifyAll() }
        writer.shutdown()
        super.onDestroy()
    }

    // ---- commands from the screen ----

    fun setAnc(mode: Int) {
        send(Proto.ANC_SET, 0x01, mode, 0x00)
        st.anc = mode; History.log(History.ANC, st.anc ?: 0)
        clearIdle()
        activityChanged()
        // Picking a mode by hand while talking: keep it when talk mode ends.
        if (st.talkActive && mode != Proto.ANC_TRANSPARENCY) savedAnc = mode
        refreshNotification()
    }

    fun setSpatial(mode: Int) {
        spatialChosen = mode
        st.spatial = mode
        send(Proto.SPATIAL_SET, mode)
    }

    fun toggleTalk() {
        if (st.talkActive) stopTalk() else startTalk()
    }

    /** The idle saver switch was flipped on the screen. */
    fun idleSettingChanged() {
        if (prefs.idleOff) { cancelIdle(); activityChanged() } else { cancelIdle(); restoreFromIdle() }
    }

    // ---- notification ----

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val toggle = PendingIntent.getService(this, 1,
            Intent(this, TalkService::class.java).setAction(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE)
        val text = when {
            st.talkActive -> "Talk mode on"
            st.connected && st.idleOff -> buildString {
                append("Noise control off while idle")
                st.battery?.let { append(" · $it%") }
            }
            st.connected -> buildString {
                append(Proto.ancName(st.anc))
                st.battery?.let { append(" · $it%") }
            }
            else -> st.status
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("CMF Headphone Pro")
            .setContentText(text)
            .apply { if (st.connected) setSubText("EQ ${eqTitle()}") }
            .setOngoing(true)
            .setContentIntent(open)
            .apply {
                if (st.connected) addAction(Notification.Action.Builder(null,
                    if (st.talkActive) "End talk" else "Talk", toggle).build())
            }
            .build()
    }

    private fun goForeground() {
        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(1, buildNotification())
    }

    private fun setStatus(s: String, connected: Boolean = false) {
        Log.i(TAG, "status: $s")
        main.post {
            st.status = s
            if (connected) {
                if (!st.connected) History.log(History.CONNECTED, 1)
                st.connected = true
                cancelIdle()
                activityChanged() // start the idle timer
            } else {
                if (st.connected) History.log(History.CONNECTED, 0)
                clearIdle()
                st.clear()
                cancelIdle()
            }
            refreshNotification()
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

    private fun isConnected(d: BluetoothDevice): Boolean = runCatching {
        d.javaClass.getMethod("isConnected").invoke(d) as Boolean
    }.getOrDefault(true)

    @SuppressLint("MissingPermission")
    /** Connect button: try to reach the headphones now, even if Android doesn't list them as connected. */
    fun connectNow() {
        forceTry = true
        failNote = null
        setStatus("Connecting...")
        synchronized(wake) { wake.notifyAll() }
    }

    @Volatile private var forceTry = false
    /** Shown instead of "Not connected" after a Connect press that failed. */
    @Volatile private var failNote: String? = null

    private fun connectLoop() {
        while (running) {
            val dev = findHeadphones()
            val force = forceTry
            forceTry = false
            if (dev == null || (!force && !isConnected(dev))) {
                setStatus(if (dev == null) "Not paired or Bluetooth off" else failNote ?: "Not connected")
                // Woken early by the "headphones connected" broadcast; this is a fallback.
                sleep(5 * 60_000)
                continue
            }
            try {
                setStatus("Connecting...")
                val s = dev.createRfcommSocketToServiceRecord(Proto.SERVICE_UUID)
                s.connect()
                socket = s
                failNote = null
                main.post { spatialChosen = null }
                setStatus("Connected", connected = true)
                main.post { eqApplied = null; applyEq() }
                send(Proto.ANC_GET)
                send(Proto.SPATIAL_GET)
                send(Proto.BATTERY_GET)
                readLoop(s)
            } catch (e: Exception) {
                Log.w(TAG, "connection failed: $e")
                if (force && !isConnected(dev)) {
                    // Pressed Connect but the headphones didn't answer: off or out of range.
                    failNote = "Couldn't reach them. Turn the headphones on and try again."
                    setStatus(failNote!!)
                    sleep(5 * 60_000)
                    continue
                }
                setStatus("Can't connect. Close Nothing X and try again.")
                sleep(5_000)
            } finally {
                runCatching { socket?.close() }
                socket = null
            }
            if (running && st.connected) setStatus("Disconnected")
            sleep(3_000)
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

    // ---- incoming (main thread) ----

    private fun handle(f: Frame) {
        when (f.type) {
            Proto.ANC_NOTIFY, Proto.ANC_REPLY -> if (f.payload.size >= 2) {
                st.anc = f.payload[1]; History.log(History.ANC, st.anc ?: 0)
                // Changed with the headphone button while idle-off: keep the new mode.
                if (st.anc != Proto.ANC_OFF) { clearIdle(); activityChanged() }
                refreshNotification()
            }
            Proto.BATTERY_NOTIFY, Proto.BATTERY_REPLY -> if (f.payload.size >= 3) {
                st.battery = f.payload[2] and 0x7F
                st.charging = f.payload[2] and 0x80 != 0
                History.log(History.BATTERY, st.battery!!)
                refreshNotification()
            }
            Proto.SPATIAL_NOTIFY -> f.payload.firstOrNull()?.let { spatialReport(it, f.seq) }
        }
    }

    private fun spatialReport(v: Int, seq: Int) {
        val chosen = spatialChosen
        when {
            // Reply to our own question, or first report: that is the chosen mode.
            seq != Proto.UNSOLICITED_SEQ || chosen == null -> { spatialChosen = v; st.spatial = v }
            v == chosen -> st.spatial = v
            // Talk mode off: the button just works as Spatial Audio.
            !prefs.enabled -> { spatialChosen = v; st.spatial = v }
            else -> {
                send(Proto.SPATIAL_SET, chosen) // undo the button's Spatial Audio change
                pressed()
            }
        }
    }

    // ---- talk mode (main thread) ----

    private fun pressed() {
        val now = System.currentTimeMillis()
        if (now - lastPress < 700) return
        lastPress = now
        toggleTalk()
    }

    private fun startTalk() {
        // If the idle saver turned noise control off, talk mode ends on the real mode.
        savedAnc = idleSavedAnc ?: st.anc
        clearIdle()
        send(Proto.ANC_SET, 0x01, Proto.ANC_TRANSPARENCY, 0x00)
        if (prefs.talkPause) {
            savedVolume = null
            pausedMusic = audio.isMusicActive
            if (pausedMusic) mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            Log.i(TAG, "talk ON (saved ${Proto.ancName(savedAnc)}, paused music: $pausedMusic)")
        } else {
            pausedMusic = false
            savedVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            Log.i(TAG, "talk ON (saved ${Proto.ancName(savedAnc)}, vol $savedVolume)")
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = (max * prefs.talkVolume / 100.0).roundToInt()
                .coerceIn(if (prefs.talkVolume > 0) 1 else 0, max)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }
        st.talkActive = true
        st.talkSince = System.currentTimeMillis()
        Stats.talkStarted()
        refreshNotification()
    }

    private fun stopTalk() {
        Log.i(TAG, "talk OFF (restore ${Proto.ancName(savedAnc)}, vol $savedVolume)")
        savedAnc?.let { send(Proto.ANC_SET, 0x01, it, 0x00) }
        savedVolume?.let { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
        if (pausedMusic) mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        pausedMusic = false
        st.talkActive = false
        st.talkSince?.let { Stats.talkEnded(System.currentTimeMillis() - it) }
        st.talkSince = null
        refreshNotification()
    }

    /** Sends a play or pause key to whatever app is playing media. */
    private fun mediaKey(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    // ---- EQ (main thread) ----

    /** EQ choice changed on the screen. */
    fun eqSettingChanged() {
        if (autoPick != null && autoPick != Eq.MOVIE.id) autoPick = prefs.musicEq
        autoEq()
        applyEq()
        refreshNotification()
    }

    /** "Auto (Movie)" in Auto mode, else the preset name. */
    private fun eqTitle(): String =
        if (prefs.eqMode == EqMode.AUTO) "Auto (${EqMode.label(st.eqNow ?: prefs.musicEq)})" else EqMode.label(prefs.eqMode)

    private fun wantedEq(): String =
        if (prefs.eqMode == EqMode.AUTO) autoPick ?: prefs.musicEq else prefs.eqMode

    /** Auto: Movie while a video app plays, the music preset while music plays. Nothing playing: no change. */
    private fun autoEq() {
        if (prefs.eqMode != EqMode.AUTO || !isPlaying()) return
        val (video, app) = playingKind() ?: return
        st.eqApp = app
        autoPick = if (video) Eq.MOVIE.id else prefs.musicEq
        applyEq()
    }

    private fun applyEq() {
        if (!st.connected) return
        val w = wantedEq()
        if (w == eqApplied) return
        eqApplied = w
        st.eqNow = w
        refreshNotification()
        val eq = Eq.of(w)
        Log.i(TAG, "EQ: $w")
        if (eq == null) {
            send(Proto.EQ_PRESET_SET, Proto.EQ_STOCK, 0x00)
        } else {
            send(Proto.EQ_CUSTOM_SET, *eq.payload())
            send(Proto.EQ_PRESET_SET, Proto.EQ_CUSTOM, 0x00)
        }
    }

    /** true = video, false = music, with the app's name if known. Null = can't tell. */
    private fun playingKind(): Pair<Boolean, String?>? {
        val pkg = playingPackage()
        val label = pkg?.let { EqApps.label(this, it) }
        if (pkg != null) EqApps.isVideo(this, pkg)?.let { return it to label }
        // What the playing apps say they play. (May include a paused app, so it comes second.)
        val attrs = runCatching { audio.activePlaybackConfigurations.map { it.audioAttributes } }.getOrDefault(emptyList())
        if (attrs.any { it.contentType == AudioAttributes.CONTENT_TYPE_MOVIE }) return true to label
        if (attrs.any { it.contentType == AudioAttributes.CONTENT_TYPE_MUSIC }) return false to label
        return if (pkg != null) false to label else null
    }

    /** The app that is playing, if Notification access is on for this app. */
    private fun playingPackage(): String? = runCatching {
        getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(this, MediaListener::class.java))
            .firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }?.packageName
    }.getOrNull()

    // ---- idle saver (main thread) ----

    /**
     * Sound is going out right now: music, video, games, or a call.
     * (The list of "active" players also holds paused ones, so it can't be used.)
     */
    private fun isPlaying(): Boolean =
        audio.isMusicActive ||
            audio.mode == AudioManager.MODE_IN_CALL ||
            audio.mode == AudioManager.MODE_IN_COMMUNICATION ||
            audio.mode == AudioManager.MODE_RINGTONE

    /** Android reports music as active for a few seconds after a pause, so check again. */
    private val recheck = Runnable { activityChanged() }

    private fun playbackChanged() {
        activityChanged()
        main.removeCallbacks(recheck)
        main.postDelayed(recheck, 5_000)
    }

    private val idleMs get() = prefs.idleMinutes * 60_000L

    private fun activityChanged() {
        val playing = isPlaying()
        st.playing = playing
        if (playing) {
            // Keep checking while playing, in case a stop is missed. The phone is awake anyway.
            main.removeCallbacks(recheck)
            main.postDelayed(recheck, 30_000)
            st.quietSince = null
            cancelIdle()
            restoreFromIdle()
            autoEq()
            return
        }
        if (!st.connected) return
        if (st.quietSince == null) st.quietSince = System.currentTimeMillis()
        if (!idleScheduled && prefs.idleOff && !st.idleOff) {
            idleScheduled = true
            st.idleDeadline = System.currentTimeMillis() + idleMs
            // Inexact alarm that still fires in Doze. Cheaper than keeping the phone awake.
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + idleMs, idleIntent)
        }
    }

    private fun cancelIdle() {
        idleScheduled = false
        st.idleDeadline = null
        alarms.cancel(idleIntent)
    }

    private fun idleCheck() {
        idleScheduled = false
        st.idleDeadline = null
        if (!st.connected || !prefs.idleOff || isPlaying()) return
        // Talking to someone with no music: leave Transparency on, check again later.
        if (st.talkActive) { activityChanged(); return }
        val mode = st.anc ?: return
        if (mode == Proto.ANC_OFF) return
        Log.i(TAG, "idle: noise control off (was ${Proto.ancName(mode)})")
        idleSavedAnc = mode
        send(Proto.ANC_SET, 0x01, Proto.ANC_OFF, 0x00)
        st.anc = Proto.ANC_OFF; History.log(History.ANC, st.anc ?: 0)
        st.idleOff = true
        st.idleOffSince = System.currentTimeMillis()
        Stats.idleTurnedOff()
        refreshNotification()
    }

    private fun restoreFromIdle() {
        val mode = idleSavedAnc ?: return
        clearIdle(restored = true)
        if (st.connected && st.anc == Proto.ANC_OFF) {
            Log.i(TAG, "audio back: restore ${Proto.ancName(mode)}")
            send(Proto.ANC_SET, 0x01, mode, 0x00)
            st.anc = mode; History.log(History.ANC, st.anc ?: 0)
        }
        refreshNotification()
    }

    /** The idle turn-off is over: by the app ([restored]) or by the user changing the mode. */
    private fun clearIdle(restored: Boolean = false) {
        if (idleSavedAnc == null) return
        idleSavedAnc = null
        st.idleOff = false
        st.idleOffSince?.let { Stats.idleEnded(System.currentTimeMillis() - it, restored) }
        st.idleOffSince = null
    }
}
