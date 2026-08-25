package com.nuviostremio.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs: SharedPreferences =
                context.getSharedPreferences("nuvio_settings", Context.MODE_PRIVATE)
            if (prefs.getBoolean("auto_start", false)) {
                StremioForegroundService.start(context)
            }
        }
    }
}
