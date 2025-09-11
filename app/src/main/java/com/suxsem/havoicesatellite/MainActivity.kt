package com.suxsem.havoicesatellite

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.livedata.observeAsState
import com.suxsem.havoicesatellite.ui.theme.HAVoiceSatelliteTheme
import androidx.compose.runtime.State

class MainActivity : ComponentActivity() {

    private val viewModel: MyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val activity = this

        createPersistentNotificationsChannel()

        setContent {
            HAVoiceSatelliteTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        RequirementsPanel(
                            activity = activity,
                            requirements = viewModel.requirements,
                        )
                        ServiceControlUI(viewModel)
                    }
                }
            }
        }

    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    fun createPersistentNotificationsChannel() {
        val serviceChannel = NotificationChannel(
            "persistent_channel",
            "Persistent Channel",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

}

class MyViewModel(application: Application) : AndroidViewModel(application) {

    private val _requirements = mutableStateListOf<Requirement>()
    val requirements: List<Requirement> = _requirements

    fun refresh() {

        val context = getApplication<Application>().applicationContext

        _requirements.clear()
        _requirements.addAll(
            listOf(
                Requirement(
                    id = "record_audio",
                    label = "Microphone permission",
                    isMet = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED,
                    fixAction = { activity ->
                        ActivityCompat.requestPermissions(
                            activity,
                            arrayOf(Manifest.permission.RECORD_AUDIO),
                            0
                        )
                    }
                ),
                Requirement(
                    id = "post_notifications",
                    label = "Notifications permission",
                    isMet = if (Build.VERSION.SDK_INT >= 33) {
                        ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                    } else true,
                    fixAction = { activity ->
                        if (Build.VERSION.SDK_INT >= 33) {
                            ActivityCompat.requestPermissions(
                                activity,
                                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                                1
                            )
                        }
                    }
                ),
                Requirement(
                    id = "global_notifications",
                    label = "Notifications enabled",
                    isMet = NotificationManagerCompat.from(context).areNotificationsEnabled(),
                    fixAction = { activity ->
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        activity.startActivity(intent)
                    }
                ),
                Requirement(
                    id = "channel_notifications",
                    label = "Persistent notifications channel enabled",
                    isMet = isChannelEnabled("persistent_channel"),
                    fixAction = {activity ->
                        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .putExtra(Settings.EXTRA_CHANNEL_ID, "persistent_channel")
                        activity.startActivity(intent)
                    }
                ),
                Requirement(
                    id = "battery_optimization",
                    label = "Excluded from battery optimizations",
                    isMet = !isBatteryOptimized(),
                    fixAction = {activity ->
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        activity.startActivity(intent)
                    }
                )
            )
        )

    }

    private fun isChannelEnabled(channelId: String): Boolean {
        val context = getApplication<Application>().applicationContext

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = manager.getNotificationChannel(channelId)
        return channel?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun isBatteryOptimized(): Boolean {
        val context = getApplication<Application>().applicationContext

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val packageName = context.packageName
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    private val prefs = application.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    private val _checked = mutableStateOf(prefs.getBoolean("auto_start", false))
    val autoStart: State<Boolean> = _checked

    fun setAutoStart(newValue: Boolean) {
        _checked.value = newValue
        prefs.edit { putBoolean("auto_start", newValue) }
    }
}

data class Requirement(
    val id: String,
    val label: String,
    val isMet: Boolean,
    val fixAction: (Activity) -> Unit
)

@Composable
fun RequirementsPanel(activity: Activity, requirements: List<Requirement>) {

    LazyColumn {
        items(requirements) { req ->
            RequirementRow(activity, req)
        }
    }
}

@Composable
fun RequirementRow(activity: Activity, req: Requirement) {

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = req.label,
            modifier = Modifier.weight(1f)
        )
        if (req.isMet) {
            Icon(Icons.Default.Check, contentDescription = "OK", tint = Color.Green)
        } else {
            Button(onClick = { req.fixAction(activity) }) {
                Text("Fix")
            }
        }
    }
}

@Composable
fun ServiceControlUI(viewModel: MyViewModel) {

    val allMet = viewModel.requirements.all { it.isMet }
    val isRunning by ServiceState.isRunning.observeAsState(false)

    val autoStart by viewModel.autoStart
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Row per i pulsanti
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = {
                ContextCompat.startForegroundService(context,
                    Intent(context, MainService::class.java)
                )
            }, enabled = allMet && !isRunning) {
                Text("Start")
            }

            Button(onClick = {
                context.stopService(Intent(context, MainService::class.java))
            }, enabled = isRunning) {
                Text("Stop")
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = if (isRunning) "RUNNING" else "STOPPED",
                color = if (isRunning) Color.Green else Color.Red)
        }

        // Checkbox sotto
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = autoStart,
                onCheckedChange = viewModel::setAutoStart
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Start on Boot")
        }
    }
}