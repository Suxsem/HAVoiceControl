package com.suxsem.havoicecontrol

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainService : Service() {

    private val self = this;
    private val conversation by lazy { Conversation(this) }
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var detector: WakeWordDetector? = null

    override fun onCreate() {

        super.onCreate()

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
        val channel = manager.getNotificationChannel(NOTIF_CHANNEL_PERSISTENT)
        if (channel?.importance == NotificationManager.IMPORTANCE_NONE) {
            stopSelf()
            return
        }

        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_PERSISTENT)
            .setContentTitle("HA Voice Control")
            .setContentText("Service running")
            //.setSmallIcon(R.drawable.ic_service) // usa la tua icona
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true) // la notifica non può essere rimossa dall'utente
            .build()

        ServiceCompat.startForeground(
            /* service = */ this,
            /* id = */ 100, // Cannot be 0
            /* notification = */ notification,
            /* foregroundServiceType = */
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            },
        )

        ServiceState.setRunning(true)

        serviceScope.launch {

            val minScore = prefs.getFloat("min_score", 0f)

            //TODO parametri
            detector = WakeWordDetector(applicationContext, "ei_fausta_20260403_201953.onnx", "ei_fausta_20260403_201953.onnx", minScore, 0f) { score ->
                Log.d("WakeWordService", "Wake word detected con score=$score")

                detector!!.pauseDetection()

                val intent = Intent(self, ConversationActivity::class.java).apply {
                    putExtra("EXTRA_DO_NOT_START_CHAT", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                serviceScope.launch {
                    conversation.chat()
                    detector!!.startDetection()
                }

            }

            detector!!.startDetection()

        }

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {

        detector?.pauseDetection()
        detector?.releaseResources()
        serviceScope.cancel()

        ServiceState.setRunning(false)
        super.onDestroy()
    }

}

object ServiceState {
    private val _isRunning = MutableLiveData(false)
    val isRunning: LiveData<Boolean> = _isRunning

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }
}