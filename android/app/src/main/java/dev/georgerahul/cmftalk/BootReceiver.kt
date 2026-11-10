package dev.georgerahul.cmftalk

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/** Starts the service after a reboot and after the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // Android 12 and newer ask for Bluetooth at runtime; older versions (the TV app) grant it at install.
        if (Build.VERSION.SDK_INT >= 31 &&
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        runCatching { TalkService.start(ctx) }
            .onFailure { Log.w(TalkService.TAG, "could not start on ${intent.action}: $it") }
    }
}
