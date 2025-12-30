package com.suxsem.havoicesatellite

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.runtime.livedata.observeAsState
import com.suxsem.havoicesatellite.ui.theme.HAVoiceSatelliteTheme
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.text.input.KeyboardType
import kotlin.math.min
import kotlin.math.round
import androidx.core.net.toUri

class MainActivity : ComponentActivity() {

    private val viewModel: MyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val activity = this

        createNotificationsChannels()
        viewModel.setPrefs()

        setContent {
            HAVoiceSatelliteTheme {
                MainUI(viewModel, activity)
            }
        }

    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    fun createNotificationsChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIF_CHANNEL_PERSISTENT,
                "Persistent Channel",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIF_CHANNEL_DETECTED,
                "Detected Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIF_CHANNEL_STANDARD,
                "Standard Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
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
                    fixAction = { activity ->
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
                    fixAction = { activity ->
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        activity.startActivity(intent)
                    }
                ),
                Requirement(
                    id = "overlay_permission",
                    label = "Display over other apps",
                    isMet = Settings.canDrawOverlays(context),
                    fixAction = { activity ->
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            "package:${activity.packageName}".toUri()
                        )
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

    companion object {
        const val DEFAULT_MIN_SCORE = 0.25f
        const val DEFAULT_MIN_GAIN = 1f
        const val DEFAULT_MAX_GAIN = 4f
        const val DEFAULT_ENERGY_TRESHOLD = 0.15f
    }

    private val _minScore = mutableFloatStateOf(prefs.getFloat("min_score", DEFAULT_MIN_SCORE))
    val minScore: State<Float> = _minScore

    fun setMinScore(newValue: Float) {
        val roundedValue = round(newValue * 100f) / 100.0f
        _minScore.floatValue = roundedValue
        prefs.edit { putFloat("min_score", roundedValue) }
    }

    fun resetMinScore() {
        setMinScore(DEFAULT_MIN_SCORE)
    }

    private val _minGain = mutableFloatStateOf(prefs.getFloat("min_gain", DEFAULT_MIN_GAIN))
    val minGain: State<Float> = _minGain

    fun setMinGain(newValue: Float) {
        val roundedValue = round(newValue * 100f) / 100.0f
        _minGain.floatValue = roundedValue
        prefs.edit { putFloat("min_gain", roundedValue) }
    }

    fun resetMinGain() {
        setMinGain(DEFAULT_MIN_GAIN)
    }

    private val _maxGain = mutableFloatStateOf(prefs.getFloat("max_gain", DEFAULT_MAX_GAIN))
    val maxGain: State<Float> = _maxGain

    fun setMaxGain(newValue: Float) {
        val roundedValue = round(newValue * 100f) / 100.0f
        _maxGain.floatValue = roundedValue
        prefs.edit { putFloat("max_gain", roundedValue) }
    }

    fun resetMaxGain() {
        setMaxGain(DEFAULT_MAX_GAIN)
    }

    private val _energyTreshold =
        mutableFloatStateOf(prefs.getFloat("energy_treshold", DEFAULT_ENERGY_TRESHOLD))
    val energyTreshold: State<Float> = _energyTreshold

    fun setEnergyTreshold(newValue: Float) {
        val roundedValue = round(newValue * 100f) / 100.0f
        _energyTreshold.floatValue = roundedValue
        prefs.edit { putFloat("energy_treshold", roundedValue) }
    }

    fun resetEnergyTreshold() {
        setEnergyTreshold(DEFAULT_ENERGY_TRESHOLD)
    }

    fun setPrefs() {
        setMinScore(_minScore.floatValue)
        setMinGain(_minGain.floatValue)
        setMaxGain(_maxGain.floatValue)
        setEnergyTreshold(_energyTreshold.floatValue)
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        requirements.forEach { req ->
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
fun MainUI(viewModel: MyViewModel, activity: Activity) {

    val allMet = viewModel.requirements.all { it.isMet }
    val isRunning by ServiceState.isRunning.observeAsState(false)

    val autoStart by viewModel.autoStart
    val minScore by viewModel.minScore
    val minGain by viewModel.minGain
    val maxGain by viewModel.maxGain
    val energyTreshold by viewModel.energyTreshold

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            RequirementsPanel(
                activity = activity,
                requirements = viewModel.requirements,
            )

            ParamSelector(
                enabled = !isRunning,
                value = minScore,
                label = "Min Score",
                onValueChange = viewModel::setMinScore,
                onReset = viewModel::resetMinScore,
                range = 0f..1f
            )

            ParamSelector(
                enabled = !isRunning,
                value = minGain,
                label = "Min Gain",
                onValueChange = viewModel::setMinGain,
                onReset = viewModel::resetMinGain,
                range = 0f..5f
            )

            ParamSelector(
                enabled = !isRunning,
                value = maxGain,
                label = "Max Gain",
                onValueChange = viewModel::setMaxGain,
                onReset = viewModel::resetMaxGain,
                range = 0f..10f
            )

            ParamSelector(
                enabled = !isRunning,
                value = energyTreshold,
                label = "Energy Treshold",
                onValueChange = viewModel::setEnergyTreshold,
                onReset = viewModel::resetEnergyTreshold,
                range = 0f..1f
            )

            // Row per i pulsanti
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = {
                    ContextCompat.startForegroundService(
                        activity,
                        Intent(activity, MainService::class.java)
                    )
                }, enabled = allMet && !isRunning) {
                    Text("Start")
                }

                Button(onClick = {
                    activity.stopService(Intent(activity, MainService::class.java))
                }, enabled = isRunning) {
                    Text("Stop")
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isRunning) "RUNNING" else "STOPPED",
                    color = if (isRunning) Color.Green else Color.Red
                )
            }

            // Checkbox sotto
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = autoStart,
                    onCheckedChange = viewModel::setAutoStart,
                    enabled = allMet
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Start on Boot")
            }
        }
    }
}

@Composable
fun ParamSelector(
    enabled: Boolean = true,
    value: Float,
    label: String,
    onValueChange: (Float) -> Unit,
    onReset: () -> Unit,
    range: ClosedFloatingPointRange<Float> = 0f..1f
) {
    // Stato locale per il testo, per permettere l'editing fluido
    var textValue by remember(value) { mutableStateOf(value.toString()) }

    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "${label}: ${"%.2f".format(value)}")

            IconButton(onClick = onReset, enabled = enabled) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reset default",
                    tint = Color.Gray
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Slider
            Slider(
                enabled = enabled,
                value = value,
                onValueChange = onValueChange,
                valueRange = range,
                modifier = Modifier.weight(1f)
            )

            // Casella di testo
            OutlinedTextField(
                value = textValue,
                enabled = enabled,
                onValueChange = { newValue ->
                    textValue = newValue
                    // Tentiamo il parsing solo se è un numero valido nel range
                    val parsed = newValue.toFloatOrNull()
                    if (parsed != null && parsed in range) {
                        onValueChange(parsed)
                    }
                },
                label = { Text("Val") },
                modifier = Modifier.width(80.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
        }
    }
}