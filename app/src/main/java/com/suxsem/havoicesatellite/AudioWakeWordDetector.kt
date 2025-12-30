package com.suxsem.havoicesatellite

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class AudioWakeWordDetector(context: Context) {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val INT16_NORMALIZATION_FACTOR = 1.0f / 32768.0f
        private const val TARGET_RMS = 0.15f
        private const val ADAPTATION_SPEED = 0.05f
        private const val COOLDOWN_MS = 3000L
    }

    // Risorse persistenti caricate una sola volta nell'init
    private val modelRunner = ONNXModelRunner(context.assets)
    private val model: Model
    private val minBufferSize: Int
    private val bufferSizeInShorts = 1280

    // Parametri di configurazione caricati una volta sola
    private var minScore: Float = 0f
    private var minGain: Float = 0f
    private var maxGain: Float = 0f

    // Stato dinamico
    private var currentGain = 1.0f
    private var lastWakeWordTime = 0L

    init {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

        // 1. Carichiamo le soglie e i guadagni
        minScore = prefs.getFloat("min_score", 0f)
        minGain = prefs.getFloat("min_gain", 0f)
        maxGain = prefs.getFloat("max_gain", 0f)
        val energyThreshold = prefs.getFloat("energy_treshold", 0f)

        // 2. Inizializziamo il modello
        model = Model(modelRunner, energyThreshold)

        // 3. Prepariamo i parametri del microfono
        val tempMinSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        minBufferSize = if (tempMinSize / 2 < bufferSizeInShorts) bufferSizeInShorts * 2 else tempMinSize
    }

    /**
     * Sospende finché non rileva la wakeword.
     */
    @SuppressLint("MissingPermission")
    suspend fun waitForWakeWord(): Float = withContext(Dispatchers.IO) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            minBufferSize
        )

        val audioBuffer = ShortArray(bufferSizeInShorts)
        audioRecord.startRecording()

        try {
            while (isActive) {
                val readResult = audioRecord.read(audioBuffer, 0, audioBuffer.size)
                if (readResult <= 0) continue

                // Processamento audio
                val floatBuffer = applyAGCAndNormalize(audioBuffer)
                val res = model.predict_WakeWord(floatBuffer)
                val score = res?.toFloatOrNull() ?: 0f

                val now = System.currentTimeMillis()
                if (score > minScore && now - lastWakeWordTime >= COOLDOWN_MS) {
                    lastWakeWordTime = now
                    return@withContext score
                }
            }
            0f
        } finally {
            // Rilasciamo il microfono per la sessione di chat
            audioRecord.stop()
            audioRecord.release()
            model.reset()
        }
    }

    private fun applyAGCAndNormalize(audioBuffer: ShortArray): FloatArray {
        val floatBuffer = FloatArray(audioBuffer.size)
        var sumSq = 0.0
        for (i in audioBuffer.indices) {
            floatBuffer[i] = (audioBuffer[i] * INT16_NORMALIZATION_FACTOR) * currentGain
            sumSq += (floatBuffer[i] * floatBuffer[i]).toDouble()
        }
        val rms = Math.sqrt(sumSq / audioBuffer.size).toFloat()
        if (rms > 0.001f) {
            val error = TARGET_RMS / rms
            currentGain += (error - 1.0f) * ADAPTATION_SPEED
            currentGain = currentGain.coerceIn(minGain, maxGain)
        }
        return floatBuffer
    }

    fun close() {
        modelRunner.close()
    }
}