package com.suxsem.havoicecontrol

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "com.suxsem.havoicecontrol.TEST_BOOT") {

            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("auto_start", false)) {
                showStartNotification(context)
            }
        }
    }

    private fun showStartNotification(context: Context) {

        // Creiamo l'Intent per far partire il servizio
        val serviceIntent = Intent(context, MainService::class.java)

        // PendingIntent per il servizio
        // Nota: Usiamo FLAG_IMMUTABLE per sicurezza su Android 12+
        val pendingIntent = PendingIntent.getForegroundService(
            context,
            0,
            serviceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notification = NotificationCompat.Builder(context, NOTIF_CHANNEL_STANDARD)
            .setContentTitle("Voice Control for Home Assistant")
            .setContentText("Tap to start the background service")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1001, notification)
    }
}