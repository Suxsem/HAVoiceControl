package com.suxsem.havoicecontrol

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.widget.FrameLayout
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.livedata.observeAsState
import com.suxsem.havoicecontrol.ui.theme.HAVoiceControlTheme
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
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
            HAVoiceControlTheme {
                MainUI(viewModel, activity)
            }
        }

    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    fun createLauncherShortcut(label: String) {
        val shortcutId = "shortcut_${label.hashCode()}"
        val shortcutIntent = Intent(this, ConversationLauncher::class.java).apply {
            action = Intent.ACTION_VIEW
        }

        val shortcut = ShortcutInfoCompat.Builder(this, shortcutId)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(this, R.mipmap.listen))
            .setIntent(shortcutIntent)
            .build()

        if (ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)
        }
    }

    fun createQuickSettingsTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val statusBarManager: StatusBarManager = getSystemService(StatusBarManager::class.java)
            statusBarManager.requestAddTileService(
                ComponentName(
                    this,
                    QSTileService::class.java
                ),
                getString(R.string.app_name),
                android.graphics.drawable.Icon.createWithResource(
                    this,
                    R.drawable.quicksettings_tile_icon
                ),
                this.mainExecutor,
            ) {}
        }
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
                            val intent =
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
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

            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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

        private val _energyThreshold =
            mutableFloatStateOf(prefs.getFloat("energy_threshold", DEFAULT_ENERGY_TRESHOLD))
        val energyThreshold: State<Float> = _energyThreshold

        fun setEnergyThreshold(newValue: Float) {
            val roundedValue = round(newValue * 100f) / 100.0f
            _energyThreshold.floatValue = roundedValue
            prefs.edit { putFloat("energy_threshold", roundedValue) }
        }

        fun resetEnergyThreshold() {
            setEnergyThreshold(DEFAULT_ENERGY_TRESHOLD)
        }

        private val _haHost = mutableStateOf<String?>(prefs.getString("ha_host", null))
        val haHost: State<String?> = _haHost

        fun setHaHost(newValue: String?) {
            _haHost.value = newValue
            prefs.edit { putString("ha_host", newValue) }
        }

        private val _haPort = mutableStateOf<String?>(prefs.getString("ha_port", null))
        val haPort: State<String?> = _haPort

        fun setHaPort(newValue: String?) {
            _haPort.value = newValue
            prefs.edit { putString("ha_port", newValue) }
        }

        private val _haToken = mutableStateOf<String?>(prefs.getString("ha_token", null))
        val haToken: State<String?> = _haToken

        fun setHaToken(newValue: String?) {
            _haToken.value = newValue
            prefs.edit { putString("ha_token", newValue) }
        }

        fun setPrefs() {
            setMinScore(_minScore.floatValue)
            setMinGain(_minGain.floatValue)
            setMaxGain(_maxGain.floatValue)
            setEnergyThreshold(_energyThreshold.floatValue)
            setHaHost(_haHost.value)
            setHaPort(_haPort.value)
            setHaToken(_haToken.value)
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

    enum class DialogType { NONE, SHORTCUT }

    @Composable
    fun MainUI(viewModel: MyViewModel, activity: MainActivity) {

        val allMet = viewModel.requirements.all { it.isMet }
        val isRunning by ServiceState.isRunning.observeAsState(false)

        val autoStart by viewModel.autoStart
        val minScore by viewModel.minScore
        val minGain by viewModel.minGain
        val maxGain by viewModel.maxGain
        val energyThreshold by viewModel.energyThreshold
        val haHost by viewModel.haHost
        val haPort by viewModel.haPort
        val haToken by viewModel.haToken

        var activeDialog by remember { mutableStateOf(DialogType.NONE) }

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

                TextParamEditor(
                    enabled = !isRunning,
                    value = haHost,
                    label = "Home Assistant URL",
                    onValueChange = viewModel::setHaHost,
                    inputType = InputType.URL
                )

                TextParamEditor(
                    enabled = !isRunning,
                    value = haPort,
                    label = "Home Assistant Port Number",
                    onValueChange = viewModel::setHaPort,
                    inputType = InputType.NUMBER
                )

                TextParamEditor(
                    enabled = !isRunning,
                    value = haToken,
                    label = "Home Assistant Auth Token",
                    onValueChange = viewModel::setHaToken,
                    inputType = InputType.TEXT
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
                    value = energyThreshold,
                    label = "Energy Threshold",
                    onValueChange = viewModel::setEnergyThreshold,
                    onReset = viewModel::resetEnergyThreshold,
                    range = 0f..1f
                )

                // Row per i pulsanti
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            ContextCompat.startForegroundService(
                                activity,
                                Intent(activity, MainService::class.java)
                            )
                        }, enabled = allMet &&
                                haHost?.isNotEmpty() == true &&
                                haPort?.isNotEmpty() == true &&
                                haToken?.isNotEmpty() == true &&
                                !isRunning
                    ) {
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

                Button(
                    onClick = { activeDialog = DialogType.SHORTCUT },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Add Shortcut to Home")
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Button(
                        onClick = { activity.createQuickSettingsTile() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Add Tile to Quick Settings")
                    }
                }

                if (activeDialog != DialogType.NONE) {
                    ShortcutNameDialog(
                        title = if (activeDialog == DialogType.SHORTCUT) "Shortcut Name" else "Tile Name",
                        onDismiss = { activeDialog = DialogType.NONE },
                        onConfirm = { label ->
                            if (activeDialog == DialogType.SHORTCUT) {
                                activity.createLauncherShortcut(label)
                            }
                            activeDialog = DialogType.NONE
                        }
                    )
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

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {

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

    enum class InputType {
        TEXT,
        NUMBER,
        URL
    }

    @Composable
    fun TextParamEditor(
        enabled: Boolean = true,
        value: String?,
        label: String,
        inputType: InputType = InputType.TEXT,
        onValueChange: (String) -> Unit,
    ) {
        var currentText by remember(value) { mutableStateOf(value) }

        val keyboardOptions = when (inputType) {
            InputType.NUMBER -> KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            )

            InputType.URL -> KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go
            )

            InputType.TEXT -> KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {

            Text(text = label)

            OutlinedTextField(
                value = currentText ?: "",
                onValueChange = { newValue ->
                    // Filtro per il tipo NUMBER: accetta solo cifre
                    val filteredValue = if (inputType == InputType.NUMBER) {
                        newValue.filter { it.isDigit() }
                    } else {
                        newValue
                    }

                    currentText = filteredValue
                    onValueChange(filteredValue)
                },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = keyboardOptions,
                shape = RoundedCornerShape(12.dp),
            )
        }
    }

    @Composable
    fun ShortcutNameDialog(
        title: String,
        onDismiss: () -> Unit,
        onConfirm: (String) -> Unit
    ) {
        var text by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(text = title) },
            text = {
                Column {
                    Text("Enter a label for your home screen shortcut:")
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Label") },
                        placeholder = { Text("e.g., Listen Now") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (text.isNotBlank()) {
                            onConfirm(text)
                        }
                    },
                    enabled = text.isNotBlank() // Disabilita se vuoto
                ) {
                    Text("CREATE")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("CANCEL")
                }
            }
        )
    }

}