package com.suxsem.havoicecontrol

import androidx.biometric.BiometricPrompt
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.compose.animation.Crossfade
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.suxsem.havoicecontrol.ui.theme.ColorHomeAssistant
import kotlinx.coroutines.launch

class ConversationActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        ConversationManager.setIsTalking(false)
        ConversationManager.updateText("")
        ConversationManager.updateAmplitude(0f)

        val doNotStartChat = intent.getBooleanExtra("EXTRA_DO_NOT_START_CHAT", false)
        if (!doNotStartChat) {
            val conversation = Conversation(this)
            lifecycleScope.launch {
                conversation.chat()
            }
        }

        lifecycleScope.launch {
            ConversationManager.dismiss.collect {
                finish()
            }
        }

        lifecycleScope.launch {
            ConversationManager.authRequests.collect { deferred ->
                val biometricPrompt = BiometricPrompt(this@ConversationActivity, mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            deferred.complete(true)
                        }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            deferred.complete(false)
                        }
                        override fun onAuthenticationFailed() {
                        }
                    })

                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Autenticazione richiesta")
                    .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                    .build()

                biometricPrompt.authenticate(promptInfo)
            }
        }

        setContent {
            MaterialTheme {
                SpeechOverlayUI()
            }
        }
    }

    @Composable
    private fun SpeechOverlayUI() {
        val scope = rememberCoroutineScope()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets(0))
                .pointerInput(Unit) {
                    detectTapGestures {
                        scope.launch {
                            ConversationManager.notifyDismiss()
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                /*
                val scale by animateFloatAsState(targetValue = if (!ConversationManager.isTalking) { (1f + ConversationManager.amplitude / 10f) / 2 } else 1f)
                Box(
                    modifier = Modifier
                        .graphicsLayer(scaleX = scale, scaleY = scale)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Crossfade(targetState = ConversationManager.isTalking) { talking ->
                        Image(
                            painter = painterResource(
                                id = if (talking) R.drawable.conversation_listen else R.drawable.conversation_listen
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(200.dp)
                        )
                    }
                }
                 */
                val scale by animateFloatAsState(targetValue = if (!ConversationManager.isTalking) { (1f + ConversationManager.amplitude / 10f) / 2 } else 1f)
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .graphicsLayer(scaleX = scale, scaleY = scale)
                        .background(ColorHomeAssistant, shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Crossfade(targetState = ConversationManager.isTalking) { talking ->
                        Icon(
                            if (talking) Icons.Default.Home else Icons.Default.Mic,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(150.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(50.dp))

                Text(
                    text = ConversationManager.text,
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
            }
        }
    }

}