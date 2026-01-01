package com.suxsem.havoicesatellite

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Conversation (private val context: Context){

    private var speechRecognizer: SpeechRecognizer? = null
    private var overlay: SpeechOverlay = SpeechOverlay(context)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var resultDeferred: CompletableDeferred<Unit?>? = null
    private var client: HomeAssistantConversationClient? = null
    private var tts: AndroidTTSManager? = null
    private var conversationId: String? = null

    init {
        overlay.onDismissListener = {
            speechRecognizer?.cancel()
        }
    }

    suspend fun chat() {
        resultDeferred = CompletableDeferred()

        startSTT();

        val cleanupJob = scope.launch {
            resultDeferred!!.await()

            overlay.hide()
            speechRecognizer?.destroy()
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
                overlay.show()
                overlay.updateIsTalking(false)
                if (tts == null) {
                    tts = AndroidTTSManager(context)
                }
                if (client == null) {
                    val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
                    val haHost = prefs.getString("ha_host", null)
                    val haPort = prefs.getString("ha_port", null)
                    val haToken = prefs.getString("ha_token", null)
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
                overlay.updateAmp(rmsdB)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val data = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                overlay.updateText(data?.get(0) ?: "")
            }

            override fun onResults(results: Bundle?) {
                val data = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = data?.get(0) ?: ""
                if (text.isEmpty()) {
                    resultDeferred?.complete(Unit)
                    return
                }
                overlay.updateText(text)
                scope.launch {
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
                        overlay.updateIsTalking(true)

                        conversationId = intentRes.conversation_id
                        val speech = intentRes.response.speech
                        val text = speech.text

                        overlay.updateText(text ?: "")

                        if (!text.isNullOrEmpty()) {
                            tts?.speak(text, intentRes.response.language)
                        }

                        if (intentRes.continue_conversation) {
                            startSTT();
                        } else {
                            resultDeferred?.complete(Unit)
                        }
                    }
                }
            }

            override fun onBeginningOfSpeech() {
            }

            override fun onBufferReceived(p0: ByteArray?) {
            }

            override fun onEndOfSpeech() {
                overlay.updateAmp(0f)
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

}