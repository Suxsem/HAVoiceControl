package com.suxsem.havoicecontrol

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.suxsem.havoicecontrol.ui.theme.ColorHomeAssistant
import kotlinx.coroutines.launch
import kotlin.getValue

class ConversationLauncher2 : ComponentActivity() {

    var onDismissListener: (() -> Unit)? = null
    private var currentText = ""
    private var currentAmplitude = 0f

    private val viewModel: MyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        setContent {
            MaterialTheme {
                SpeechOverlayUI(viewModel)
            }
        }
    }

    private class MyViewModel(application: Application) :
        AndroidViewModel(application) {

        var text by mutableStateOf("")
            private set

        var amplitude by mutableFloatStateOf(0f)
            private set

        var isTalking by mutableStateOf(false)
            private set

        fun updateText(newText: String) {
            text = newText
        }
        fun updateAmplitude(newAmplitude: Float) {
            amplitude = newAmplitude
        }
        fun setIsTalking(newIsTalking: Boolean) {
            isTalking = newIsTalking
        }

        fun dismiss() {
            viewModelScope.launch { ConversationEvents.notifyDismiss() }
        }

    }

    @Composable
    private fun SpeechOverlayUI(viewModel: MyViewModel) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets(0)) // Forza a ignorare padding automatici
                .pointerInput(Unit) {
                    detectTapGestures {
                        viewModel.dismiss()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Animazione Microfono (Semplice cerchio che pulsa)
                val scale by animateFloatAsState(targetValue = 1f + (viewModel.amplitude / 10f))
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .graphicsLayer(scaleX = scale, scaleY = scale)
                        .background(ColorHomeAssistant, shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (viewModel.isTalking) Icons.Default.Home else Icons.Default.Mic,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                }

                Spacer(modifier = Modifier.height(50.dp))

                // Testo Riconosciuto
                Text(
                    text = viewModel.text,
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
            }
        }
    }

}