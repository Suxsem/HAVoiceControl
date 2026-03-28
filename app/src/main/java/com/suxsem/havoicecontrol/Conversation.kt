package com.suxsem.havoicecontrol

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ConversationManager {
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

    private val _dismiss = MutableSharedFlow<Unit>()
    val dismiss = _dismiss.asSharedFlow()

    suspend fun notifyDismiss() {
        _dismiss.emit(Unit)
    }

    private val _authRequests = MutableSharedFlow<CompletableDeferred<Boolean>>()
    val authRequests = _authRequests.asSharedFlow()

    suspend fun requestAuthentication(): Boolean {
        val result = CompletableDeferred<Boolean>()
        _authRequests.emit(result)
        return result.await()
    }

}

class Conversation (private val context: Context){

    private var speechRecognizer: SpeechRecognizer? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var resultDeferred: CompletableDeferred<Unit?>? = null
    private var client: HomeAssistantConversationClient? = null
    private var tts: AndroidTTSManager? = null
    private var conversationId: String? = null
    private val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    private val secured = prefs.getBoolean("secured", true)
    private val haHost = prefs.getString("ha_host", null)
    private val haPort = prefs.getString("ha_port", null)
    private val haToken = prefs.getString("ha_token", null)

    init {
        scope.launch {
            ConversationManager.dismiss.collect {
                speechRecognizer?.cancel()
            }
        }
    }

    suspend fun chat() {
        resultDeferred = CompletableDeferred()

        startSTT();

        val cleanupJob = scope.launch {
            resultDeferred!!.await()

            ConversationManager.notifyDismiss()
            speechRecognizer?.destroy()
            speechRecognizer = null
            client?.close()
            client = null
            tts?.shutdown()
            tts = null
            conversationId = null
        }

        cleanupJob.join()
    }

    fun startSTT() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer!!.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {

                if (tts == null) {
                    tts = AndroidTTSManager(context)
                }
                if (client == null) {
                    if (haHost.isNullOrEmpty() || haPort.isNullOrEmpty() || haToken.isNullOrEmpty()) {
                        Toast.makeText(context, "Home Assistant not configured", Toast.LENGTH_LONG).show()
                        resultDeferred?.complete(Unit)
                        return
                    }
                    client = HomeAssistantConversationClient(haHost, haPort.toInt(), haToken)
                }
            }

            override fun onRmsChanged(rmsdB: Float) {
                // rmsdB è la potenza del suono, la usiamo per l'animazione
                ConversationManager.updateAmplitude(rmsdB)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val data = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ConversationManager.updateText(data?.get(0) ?: "")
            }

            override fun onResults(results: Bundle?) {
                val data = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = data?.get(0) ?: ""
                if (text.isEmpty()) {
                    resultDeferred?.complete(Unit)
                    return
                }
                ConversationManager.updateText(text)
                scope.launch {
                    val authenticated = if (secured && isKeyguardLocked(context)) ConversationManager.requestAuthentication() else true

                    if (authenticated) {
                        val result = client!!.process(text, conversationId)

                        result.onFailure { error ->
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    context,
                                    "Error: ${error.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            resultDeferred?.complete(Unit)
                            return@launch
                        }

                        result.onSuccess { intentRes ->
                            ConversationManager.setIsTalking(true)

                            conversationId = intentRes.conversation_id
                            val speech = intentRes.response.speech
                            val text = speech.text

                            ConversationManager.updateText(text ?: "")

                            if (!text.isNullOrEmpty()) {
                                tts?.speak(text, intentRes.response.language)
                            }

                            if (intentRes.continue_conversation) {
                                startSTT();
                            } else {
                                resultDeferred?.complete(Unit)
                            }
                        }
                    } else {
                        Toast.makeText(context, "Authentication failed", Toast.LENGTH_LONG).show()
                        resultDeferred?.complete(Unit)
                    }
                }
            }

            override fun onBeginningOfSpeech() {
            }

            override fun onBufferReceived(p0: ByteArray?) {
            }

            override fun onEndOfSpeech() {
                ConversationManager.updateAmplitude(0f)
            }

            override fun onError(error: Int) {
                resultDeferred?.complete(Unit)
            }
            override fun onEvent(p0: Int, p1: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer!!.startListening(intent)
    }

    fun isKeyguardLocked(context: Context): Boolean {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return keyguardManager.isKeyguardLocked
    }

}