package com.suxsem.havoicecontrol

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

class QSTileService: TileService() {

    override fun onClick() {
        super.onClick()

        val intent = Intent(this, ConversationActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (isSecure) {
            startActivity(intent)
        } else {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                @SuppressLint("StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
        }
    }

}

