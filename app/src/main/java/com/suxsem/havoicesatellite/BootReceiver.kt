package com.suxsem.havoicesatellite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("auto_start", false)) {
                ContextCompat.startForegroundService(context,
                    Intent(context, MainService::class.java)
                )
            }
        }
    }
}