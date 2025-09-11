package com.suxsem.havoicesatellite

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData


class MainService : Service() {

    private val PERSISTENT_CHANNEL_ID = "persistent_channel"
    private val STANDARD_CHANNEL_ID = "standard_channel"

    private var recorderThread: AudioRecorderThread? = null

    override fun onCreate() {
        super.onCreate()

        ServiceState.setRunning(true)

        createNotificationChannels()

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }

        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            stopSelf()
            return
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = manager.getNotificationChannel(PERSISTENT_CHANNEL_ID)
        if (channel?.importance == NotificationManager.IMPORTANCE_NONE) {
            stopSelf()
            return
        }


        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, PERSISTENT_CHANNEL_ID)
            .setContentTitle("HA Voice Satellite")
            .setContentText("Service running")
            //.setSmallIcon(R.drawable.ic_service) // usa la tua icona
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true) // la notifica non può essere rimossa dall'utente
            .build()

        startForeground(1, notification)

        val modelRunner = ONNXModelRunner(assets)
        val model = Model(modelRunner);

        recorderThread = AudioRecorderThread(model) { score ->
            // ⚡ Sei in un thread di background
            Log.d("WakeWordService", "Wake word detected con score=$score")

            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                val notification = NotificationCompat.Builder(this, STANDARD_CHANNEL_ID)
                    .setContentTitle("WAKEWORD RILEVATA")
                    .setContentText("WAKEWORD RILEVATA")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setAutoCancel(true)
                    .build()

                val notificationManager = NotificationManagerCompat.from(this)
                val notificationId = (0..Int.MAX_VALUE).random() // id casuale per ogni notifica

                notificationManager.notify(notificationId, notification)
            }

            /*
            // esempio: invio broadcast locale all’activity
            val broadcast = Intent("WAKEWORD_DETECTED")
            broadcast.putExtra("score", score)
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast)
             */
        }

        recorderThread?.start()

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        recorderThread?.stopRecording()
        recorderThread?.join()
        recorderThread = null
        ServiceState.setRunning(false)
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        val wakewordChannel = NotificationChannel(
            STANDARD_CHANNEL_ID,
            "Standard Channel",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(wakewordChannel)
    }
}

object ServiceState {
    private val _isRunning = MutableLiveData(false)
    val isRunning: LiveData<Boolean> = _isRunning

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }
}