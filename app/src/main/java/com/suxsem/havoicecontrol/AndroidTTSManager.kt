package com.suxsem.havoicecontrol

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.*
import kotlin.coroutines.resume

class AndroidTTSManager(context: Context) : TextToSpeech.OnInitListener {

    // Inizializziamo il motore. Il 'context' deve essere preferibilmente quello dell'applicazione
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext, this)

    // Questo Deferred gestisce l'attesa del caricamento iniziale del motore
    private val isReady = CompletableDeferred<Boolean>()

    /**
     * Chiamata dal sistema quando il motore è pronto.
     */
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isReady.complete(true)
        } else {
            isReady.complete(false)
        }
    }

    /**
     * Funzione suspend che attende l'inizializzazione (se necessario) e
     * termina solo a riproduzione conclusa.
     */
    suspend fun speak(text: String, language: String) {
        // 1. Attesa asincrona del motore (non blocca la UI)
        val ready = isReady.await()
        if (!ready) return

        // 2. Trasformazione della callback in coroutine
        suspendCancellableCoroutine<Unit> { continuation ->
            val utteranceId = UUID.randomUUID().toString()

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}

                override fun onDone(id: String?) {
                    if (id == utteranceId) continuation.resume(Unit)
                }

                override fun onError(id: String?) {
                    if (id == utteranceId) continuation.resume(Unit)
                }
            })

            // Impostazione lingua (Locale moderno)
            val locale = Locale.forLanguageTag(language)
            tts.language = locale

            if (text.isNotEmpty()) {
                // Avvio riproduzione
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            } else {
                continuation.resume(Unit)
            }

            // Se la coroutine viene cancellata, fermiamo l'audio
            continuation.invokeOnCancellation {
                tts.stop()
            }
        }
    }

    /**
     * Essendo un'istanza normale, devi ricordarti di chiamare questo metodo
     * per evitare leak di memoria quando l'oggetto non serve più.
     */
    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}