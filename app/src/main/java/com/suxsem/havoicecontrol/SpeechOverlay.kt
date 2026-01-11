package com.suxsem.havoicecontrol

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
import android.graphics.Point
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.suxsem.havoicecontrol.ui.theme.ColorHomeAssistant

class SpeechOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var composeView: ComposeView? = null
    private var wrapperView: MyView? = null

    var onDismissListener: (() -> Unit)? = null

    // Gestore per il ViewModelStore (può essere lo stesso per sempre)
    private val globalViewModelStore = ViewModelStore()

    private var currentText = ""
    private var currentAmplitude = 0f
    private var isTalking = false

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

        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

            // Configurazione Flag
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
            }

            format = PixelFormat.TRANSLUCENT

            gravity = Gravity.START or Gravity.TOP

            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        composeView = ComposeView(context).apply {
            currentText = LISTENING_LABEL
            currentAmplitude = 0f
            isTalking = false
            updateContent()
        }

        wrapperView = MyView(context).apply {
            setViewTreeViewModelStoreOwner(sessionOwner)
            setViewTreeSavedStateRegistryOwner(sessionOwner)
            setViewTreeLifecycleOwner(sessionOwner)
            addView(composeView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        sessionOwner.start()

        windowManager.addView(wrapperView, params)
    }

    fun updateText(text: String) {
        currentText = text
        updateContent()
    }

    fun updateAmp(amp: Float) {
        currentAmplitude = amp
        updateContent()
    }

    fun updateIsTalking(isTalking: Boolean) {
        this.isTalking = isTalking
        updateContent()
    }

    fun onDmississ() {

    }

    private fun updateContent() {
        composeView?.setContent { SpeechOverlayUI(currentText, currentAmplitude, isTalking, onDismissListener) }
    }

    fun hide() {
        wrapperView?.let { view ->
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

    @Composable
    private fun SpeechOverlayUI(
        text: String,
        amplitude: Float,
        isTalking: Boolean,
        onDismiss: (() -> Unit)?
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets(0)) // Forza a ignorare padding automatici
                .background(Color.Black.copy(alpha = 0.7f))
                .pointerInput(Unit) {
                    detectTapGestures {
                        onDismiss?.invoke()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Animazione Microfono (Semplice cerchio che pulsa)
                val scale by animateFloatAsState(targetValue = 1f + (amplitude / 10f))
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .graphicsLayer(scaleX = scale, scaleY = scale)
                        .background(ColorHomeAssistant, shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isTalking) Icons.Default.Home else Icons.Default.Mic,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
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
}

class MyView(context: Context) : FrameLayout(context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val point = Point()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {

        val screenSize = point.also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                it.set(bounds.width(), bounds.height())
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealSize(it)
            }
        }

        super.onMeasure(
            MeasureSpec.makeMeasureSpec(screenSize.x, MeasureSpec.getMode(widthMeasureSpec)),
            MeasureSpec.makeMeasureSpec(screenSize.y, MeasureSpec.getMode(heightMeasureSpec))
        )
    }

}