package com.suxsem.havoicesatellite

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class Conversation (context: Context){

    private var speechRecognizer: SpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
    private var overlay: SpeechOverlay = SpeechOverlay(context)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var resultDeferred: CompletableDeferred<Unit?>? = null

    init {

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                overlay.show()
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
                overlay.updateText(data?.get(0) ?: "")
                scope.launch {
                    delay(1000)
                    overlay.hide()
                    resultDeferred?.complete(Unit)
                }
            }

            override fun onBeginningOfSpeech() {
            }

            override fun onBufferReceived(p0: ByteArray?) {
            }

            override fun onEndOfSpeech() {
                //TODO
            }

            override fun onError(error: Int) {
                overlay.hide()
                resultDeferred?.complete(Unit)
            }
            override fun onEvent(p0: Int, p1: Bundle?) {}
        })
    }

    suspend fun chat() {
        resultDeferred = CompletableDeferred()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        speechRecognizer.startListening(intent)
        resultDeferred!!.await()
        speechRecognizer.destroy()
    }

}