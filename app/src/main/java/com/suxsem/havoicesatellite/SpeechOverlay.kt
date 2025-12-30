package com.suxsem.havoicesatellite

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.platform.ComposeView
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

class SpeechOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var composeView: ComposeView? = null

    // Gestore per il ViewModelStore (può essere lo stesso per sempre)
    private val globalViewModelStore = ViewModelStore()

    private var currentText = ""
    private var currentAmplitude = 0f

    fun show() {
        if (composeView != null) {
            return
        }

        // 1. CREIAMO IL "PROPRIETARIO TUTTOFARE" per questa sessione
        // Questo oggetto implementa tutto ciò che Compose richiede
        val sessionOwner = object : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
            private val lifecycleRegistry = LifecycleRegistry(this)
            private val savedStateController = SavedStateRegistryController.create(this)

            override val lifecycle: Lifecycle = lifecycleRegistry
            override val viewModelStore: ViewModelStore = globalViewModelStore
            override val savedStateRegistry: SavedStateRegistry = savedStateController.savedStateRegistry

            init {
                savedStateController.performRestore(null)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            }

            fun start() {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }

        }

        /*
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        )
        */

        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

            // Configurazione Flag
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED

            format = PixelFormat.TRANSLUCENT

            // Usa Gravity.FILL per forzare l'occupazione di tutto lo spazio
            gravity = Gravity.FILL

            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        composeView = ComposeView(context).apply {
            // 2. COLLEGIAMO L'OGGETTO SESSIONE ALLA VIEW
            setViewTreeLifecycleOwner(sessionOwner)
            setViewTreeViewModelStoreOwner(sessionOwner)
            setViewTreeSavedStateRegistryOwner(sessionOwner)
            currentText = LISTENING_LABEL
            currentAmplitude = 0f
            updateContent()
        }

        // 3. AVVIAMO IL CICLO DI VITA
        sessionOwner.start()

        windowManager.addView(composeView, params)
    }

    fun updateText(text: String) {
        currentText = text
        updateContent()
    }

    fun updateAmp(amp: Float) {
        currentAmplitude = amp
        updateContent()
    }

    private fun updateContent() {
        composeView?.setContent { SpeechOverlayUI(currentText, currentAmplitude) }
    }

    fun hide() {
        composeView?.let { view ->
            // 4. RECUPERIAMO IL PROPRIETARIO E LO FERMIAMO
            view.findViewTreeLifecycleOwner()?.let {
                val registry = it.lifecycle as? LifecycleRegistry
                registry?.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                registry?.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                registry?.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }

            windowManager.removeView(view)
            composeView = null
        }
    }
}

@Composable
private fun SpeechOverlayUI(text: String, amplitude: Float) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets(0)) // Forza a ignorare padding automatici
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Animazione Microfono (Semplice cerchio che pulsa)
            val scale by animateFloatAsState(targetValue = 1f + (amplitude / 10f))
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .graphicsLayer(scaleX = scale, scaleY = scale)
                    .background(Color.Cyan, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Face, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
            }

            Spacer(modifier = Modifier.height(50.dp))

            // Testo Riconosciuto
            Text(
                text = text,
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp)
            )
        }
    }
}