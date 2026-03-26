package com.suxsem.havoicecontrol

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.sqrt

class WakeWordDetector_old(
    private val context: Context,
    private val modelPath: String
) {
    private val sampleRate = 16000
    private val windowSize = sampleRate * 1 // 1 secondo di finestra
    private val ringBuffer = AudioRingBuffer(windowSize)

    // Intervallo minimo tra due inferenze (5Hz = ogni 200ms)
    private val inferenceThrottleMs = 200L

    private val vad = VadSilero(
        context = context,
        sampleRate = SampleRate.SAMPLE_RATE_16K,
        frameSize = FrameSize.FRAME_SIZE_512,
        mode = Mode.AGGRESSIVE,
        silenceDurationMs = 300,
        speechDurationMs = 150
    )

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channelConfig,
            audioFormat,
            minBufferSize * 2
        ).apply {
            if (state != AudioRecord.STATE_INITIALIZED) {
                throw RuntimeException("AudioRecord non inizializzato. Controlla i permessi!")
            }
        }
    }

    suspend fun waitForWakeWord() = suspendCancellableCoroutine<Unit> { continuation ->
        val audioRecord = createAudioRecord()

        // Attivazione filtri hardware se disponibili
        val audioSessionId = audioRecord.audioSessionId
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(audioSessionId).enabled = true
        if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(audioSessionId).enabled = true

        val job = CoroutineScope(Dispatchers.Default).launch {
            try {
                audioRecord.startRecording()

                val audioFrameSize = 320 // 20ms a 16kHz
                val tempBuffer = ShortArray(1024)

                // BUFFER RIUTILIZZABILI: Evitiamo il Garbage Collector nel loop
                val vadFrame = ShortArray(audioFrameSize)
                val inferenceFloatBuffer = FloatArray(windowSize)
                var lastInferenceTime = 0L

                while (isActive) {
                    val read = audioRecord.read(tempBuffer, 0, tempBuffer.size)
                    if (read > 0) {
                        // 1. Scrittura rapida nel buffer circolare
                        ringBuffer.addBlock(tempBuffer, read)

                        // 2. Analisi VAD a "fette" di 20ms
                        var offset = 0
                        while (offset + audioFrameSize <= read) {
                            System.arraycopy(tempBuffer, offset, vadFrame, 0, audioFrameSize)

                            if (checkVAD(vadFrame)) {
                                val currentTime = System.currentTimeMillis()

                                // 3. THROTTLING: Eseguiamo l'IA solo se è passato abbastanza tempo
                                if (currentTime - lastInferenceTime > inferenceThrottleMs && ringBuffer.isReady()) {

                                    val fullWindowShorts = ringBuffer.getFullWindow()

                                    // 4. NORMALIZZAZIONE JIT (Just-In-Time)
                                    for (i in fullWindowShorts.indices) {
                                        inferenceFloatBuffer[i] = fullWindowShorts[i] / 32768.0f
                                    }

                                    // 5. INFERENZA
                                    if (runInference(inferenceFloatBuffer) > 0.9) {
                                        Log.d("WakeWordDetector", "Wake word rilevata!")
                                        if (continuation.isActive) continuation.resume(Unit)
                                        return@launch
                                    }
                                    lastInferenceTime = currentTime
                                }
                            }
                            offset += audioFrameSize
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("WakeWordDetector", "Errore nel loop audio", e)
            } finally {
                audioRecord.stop()
                audioRecord.release()
            }
        }

        continuation.invokeOnCancellation {
            job.cancel()
            // L'audioRecord viene rilasciato nel 'finally' del job
        }
    }

    private fun checkVAD(audioFrame: ShortArray): Boolean {
        // LIVELLO 0: RMS (Energia)
        if (!hasEnoughEnergy(audioFrame)) return false

        // LIVELLO 1: WebRTC VAD (Placeholder per chiamata JNI)
        return vad.isSpeech(audioFrame)
    }

    private fun hasEnoughEnergy(audioFrame: ShortArray): Boolean {
        var sum = 0.0
        for (sample in audioFrame) {
            val normalized = sample.toDouble() / 32768.0
            sum += normalized * normalized
        }
        val rms = sqrt(sum / audioFrame.size)
        return rms > 0.01 // Soglia circa -40dB
    }

    private fun runInference(audioData: FloatArray): Float {
        // Qui andrà l'estrazione MFCC + TFLite Interpreter
        // mfcc = extractMFCC(audioData)
        // return tfliteModel.predict(mfcc)
        return 0.0f
    }

    /**
     * Buffer circolare ottimizzato con System.arraycopy
     */
    class AudioRingBuffer(val capacity: Int) {
        private val buffer = ShortArray(capacity)
        private var writeIndex = 0
        private var isFull = false

        fun addBlock(samples: ShortArray, size: Int) {
            if (size > capacity) return

            val spaceToEnd = capacity - writeIndex
            if (size <= spaceToEnd) {
                System.arraycopy(samples, 0, buffer, writeIndex, size)
            } else {
                System.arraycopy(samples, 0, buffer, writeIndex, spaceToEnd)
                System.arraycopy(samples, spaceToEnd, buffer, 0, size - spaceToEnd)
                isFull = true
            }
            writeIndex = (writeIndex + size) % capacity
        }

        fun getFullWindow(): ShortArray {
            val result = ShortArray(capacity)
            val firstPartSize = capacity - writeIndex
            System.arraycopy(buffer, writeIndex, result, 0, firstPartSize)
            System.arraycopy(buffer, 0, result, firstPartSize, writeIndex)
            return result
        }

        fun isReady(): Boolean = isFull
    }

}

