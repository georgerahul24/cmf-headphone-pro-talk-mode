package dev.georgerahul.cmftalk

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/** Starts the service after a reboot and after the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        runCatching { TalkService.start(ctx) }
            .onFailure { Log.w(TalkService.TAG, "could not start on ${intent.action}: $it") }
    }
}
