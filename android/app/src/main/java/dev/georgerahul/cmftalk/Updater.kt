package dev.georgerahul.cmftalk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates from GitHub Releases. Checks the latest release, downloads the APK and hands it to
 * Android's installer. The first update asks you to tap Install; after that Android usually lets
 * the app update itself without asking (Android 12 and newer).
 */
object Updater {
    private const val API = "https://api.github.com/repos/georgerahul24/cmf-headphone-pro-talk-mode/releases/latest"
    private const val TAG = "CMFUpdate"
    const val CHANNEL = "updates"

    /** Shown in Settings. */
    var status by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    private val main = Handler(Looper.getMainLooper())

    fun current(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

    /** Newer if any number is bigger, left to right: 2.10.0 > 2.9.3. */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    private fun post(s: String?, done: Boolean = false) = main.post { status = s; if (done) busy = false }

    /** Checks GitHub; with [install], downloads and installs a newer version. Safe to call often. */
    fun check(ctx: Context, install: Boolean) {
        if (busy) return
        busy = true
        status = "Checking..."
        val app = ctx.applicationContext
        Thread({
            try {
                val c = URL(API).openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "cmf-talk-mode")
                c.connectTimeout = 15_000; c.readTimeout = 15_000
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val tag = j.getString("tag_name").removePrefix("v")
                val cur = current(app)
                if (!newer(tag, cur)) { post("Up to date ($cur)", true); return@Thread }
                val assets = j.getJSONArray("assets")
                val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name").endsWith(".apk") }
                if (asset == null) { post("Version $tag is out, but has no APK yet", true); return@Thread }
                if (!install) { post("Version $tag is available", true); return@Thread }
                val size = asset.getLong("size")
                val dir = File(app.cacheDir, "update").apply { mkdirs() }
                val f = File(dir, "CMF-Talk-Mode-$tag.apk")
                dir.listFiles()?.filter { it != f }?.forEach { it.delete() }
                download(asset.getString("browser_download_url"), f, size, tag)
                if (f.length() != size) { post("Download failed. Will try again later.", true); return@Thread }
                post("Installing $tag...")
                installApk(app, f)
                post("Installing $tag...", true)
            } catch (e: Exception) {
                Log.w(TAG, "update check failed: $e")
                post("Couldn't check for updates. Will try again later.", true)
            }
        }, "cmf-update").start()
    }

    /** Downloads with resume, so a dropped connection carries on where it stopped. */
    private fun download(url: String, f: File, size: Long, tag: String) {
        repeat(6) {
            if (f.length() == size) return
            if (f.length() > size) f.delete()
            runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", "cmf-talk-mode")
                c.connectTimeout = 15_000; c.readTimeout = 30_000
                val have = f.length()
                if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                val append = have > 0 && c.responseCode == 206
                c.inputStream.use { input ->
                    java.io.FileOutputStream(f, append).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            post("Downloading $tag... ${(f.length() * 100 / size).coerceAtMost(100)}%")
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "download: $it"); Thread.sleep(3_000) }
        }
    }

    private fun installApk(ctx: Context, f: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("app.apk", 0, f.length()).use { out ->
                f.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val done = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, UpdateReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            s.commit(done.intentSender)
        }
    }
}

/** Gets the installer's answer. If Android wants you to confirm, shows the Install screen or a notification. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (code) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                Updater.status = "Tap Install to finish the update"
                if (MainActivity.visible) { runCatching { ctx.startActivity(confirm) }; return }
                val nm = ctx.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel(Updater.CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT))
                val open = PendingIntent.getActivity(ctx, 8, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                nm.notify(3, Notification.Builder(ctx, Updater.CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("CMF Talk Mode update ready")
                    .setContentText("Tap to install")
                    .setContentIntent(open).setAutoCancel(true).build())
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.status = "Updated"
            else -> Updater.status = "Update failed: " +
                (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "code $code")
        }
    }
}

/**
 * Turned on under Notification access. Lets Auto EQ see which app is playing
 * (Netflix, Spotify...). It reads no notifications.
 */
class MediaListener : NotificationListenerService()
