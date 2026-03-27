package com.suxsem.havoicecontrol

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import kotlinx.coroutines.*
import java.nio.FloatBuffer
import java.util.ArrayDeque
import java.util.Collections

class WakeWordDetector(
    private val context: Context,
    private val modelFile: String,
    private val minScore: Float,
    private val onDetected: (prob: Float) -> Unit
) {
    private val audioFrameSize = 512
    private val sampleRate = 16000
    private val frameSamples = 1280 // 80ms a 16kHz
    private val melSize = 32
    private val embeddingSize = 96
    private val numEmbeddings = 16
    private val melWindowSize = 76

    private val ringBuffer = AudioRingBuffer(sampleRate * 8) // 8 secondi

    private val melBuffer = ArrayDeque<FloatArray>(melWindowSize)
    private val embeddingBuffer = ArrayDeque<FloatArray>(numEmbeddings)

    private val flatMelBuffer = FloatArray(melWindowSize * melSize)
    private val flatMelFloatBuffer = FloatBuffer.wrap(flatMelBuffer)
    private val flatClassBuffer = FloatArray(numEmbeddings * embeddingSize)
    private var isVADActive = false
    private var vadCycleCount = 0
    private var cooldownCycles = 0
    private val cooldownTotalCycles = 40 // = 1.28 secondi

    private val env = OrtEnvironment.getEnvironment()
    private lateinit var melSession: OrtSession
    private lateinit var embSession: OrtSession
    private lateinit var classifierSession: OrtSession

    private val vad = VadSilero(context, SampleRate.SAMPLE_RATE_16K, FrameSize.FRAME_SIZE_512, Mode.NORMAL, 300, 150)

    private val minAudioBufSize = AudioRecord.getMinBufferSize(
        SampleRate.SAMPLE_RATE_16K.value,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    private val tempBuffer = ShortArray(audioFrameSize)

    init {
        loadModels()
        repeat(melWindowSize) {
            melBuffer.add(FloatArray(melSize)) // Inizializza con frame "vuoti"
        }
        repeat(numEmbeddings) {
            embeddingBuffer.add(FloatArray(embeddingSize)) // Inizializza con embedding "vuoti"
        }
    }

    private fun loadModels() {
        val opts = OrtSession.SessionOptions()
        melSession = env.createSession(context.assets.open("melspectrogram.onnx").readBytes(), opts)
        embSession = env.createSession(context.assets.open("embedding_model.onnx").readBytes(), opts)
        classifierSession = env.createSession(context.assets.open(modelFile).readBytes(), opts)
    }

    @SuppressLint("MissingPermission")
    suspend fun startDetection() = withContext(Dispatchers.Default) {
        val audioRecordBuilder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minAudioBufSize * 2)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            audioRecordBuilder.setPrivacySensitive(false)
        }
        val audioRecord = audioRecordBuilder.build()
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(audioRecord.audioSessionId).enabled = true
        if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(audioRecord.audioSessionId).enabled = true

        audioRecord.startRecording()

        try {
            while (isActive) {
                val read =
                    audioRecord.read(tempBuffer, 0, audioFrameSize, AudioRecord.READ_BLOCKING)
                if (read == audioFrameSize) {
                    ringBuffer.addBlock(tempBuffer, read)

                    val speechDetected = vad.isSpeech(tempBuffer)

                    // Gestione Cooldown
                    if (cooldownCycles > 0) {
                        cooldownCycles--
                        // Mentre siamo in cooldown, resettiamo gli stati del VAD
                        isVADActive = false
                        vadCycleCount = 0
                        continue // Salta l'inferenza per questo frame
                    }

                    if (speechDetected) {
                        if (isVADActive) {
                            vadCycleCount++
                            if (vadCycleCount == 5) {
                                // Abbiamo esattamente 2560 campioni nuovi (80ms * 2)
                                runStep(numSteps = 2)
                                vadCycleCount = 0
                            }
                        } else {
                            Log.d("WakeWordDetector", "VAD attivato")
                            runStep(numSteps = 16)
                            isVADActive = true
                            vadCycleCount = 0
                        }
                    } else {
                        isVADActive = false;
                    }
                }
            }
        } finally {
            audioRecord.stop()
            audioRecord.release()
        }
    }

    /**
     * Esegue il mantenimento della pipeline per N step da 80ms
     */
    private fun runStep(numSteps: Int) {
        // Per N step, abbiamo bisogno di (N * 1280) nuovi + 480 iniziali di contesto
        val contextSize = 480
        val totalAudioToFetch = (numSteps * frameSamples) + contextSize

        val audioData = ringBuffer.getLastSamples(totalAudioToFetch)
        val floatAudio = FloatArray(audioData.size) { audioData[it] / 32768.0f }

        // Prepariamo il batch per ONNX: [numSteps, 1760]
        val batchInput = FloatArray(numSteps * 1760)

        for (i in 0 until numSteps) {
            // Ogni riga i inizia con 480 campioni di contesto e prosegue con 1280 nuovi
            // Riga 0: offset 0 in floatAudio
            // Riga 1: offset 1280 in floatAudio...
            val sourceOffset = i * frameSamples
            System.arraycopy(floatAudio, sourceOffset, batchInput, i * 1760, 1760)
        }

        runMelInferenceBatch(batchInput, numSteps)
        checkWakeWord()
    }

    /**
     * Esegue l'inferenza Mel su un batch di audio.
     * @param audio L'array di campioni (es. 1280 per batch 1, 2560 per batch 2)
     * @param batchSize Il numero di blocchi da 80ms contenuti nell'audio
     */
    /**
     * Esegue l'inferenza Mel e restituisce una lista piatta di frame (8 frame per ogni 80ms).
     */
    /**
     * Legge direttamente dal buffer nativo di ONNX e aggiorna la coda Mel.
     * Zero allocazioni di liste intermedie.
     */
    private fun runMelInferenceBatch(audio: FloatArray, batchSize: Int) {

        val tensorInput = OnnxTensor.createTensor(env, FloatBuffer.wrap(audio), longArrayOf(batchSize.toLong(), 1760L))

        tensorInput.use {
            val output = melSession.run(Collections.singletonMap(melSession.inputNames.iterator().next(), it))
            output.use { melOut ->
                val outTensor = melOut[0] as OnnxTensor
                val flatBuffer = outTensor.floatBuffer
                flatBuffer.rewind()

                // Ciclo Batch: ONNX ha lavorato una volta, noi distribuiamo i risultati
                repeat(batchSize) {
                    // 1. Spostiamo la finestra Mel di 80ms (8 frame)
                    repeat(8) {
                        val reusableFrame = melBuffer.removeFirst()
                        for (f in 0 until melSize) {
                            reusableFrame[f] = (flatBuffer.get() / 10.0f) + 2.0f
                        }
                        melBuffer.addLast(reusableFrame)
                    }

                    // 2. Chiamata DIRETTA all'embedding per ogni step del batch
                    // Ora la melBuffer è perfettamente allineata
                    runEmbeddingInference()

                }
            }
        }
    }

    /**
     * Genera un embedding partendo dai 76 frame contenuti nella melBuffer.
     * Utilizza buffer pre-allocati per massimizzare le performance.
     */
    private fun runEmbeddingInference() {
        // 1. Copiamo i dati dalla Deque (76 frame) al buffer piatto
        // Ogni frame è un FloatArray da 32
        var offset = 0
        for (frame in melBuffer) {
            System.arraycopy(frame, 0, flatMelBuffer, offset, melSize)
            offset += melSize
        }

        // Reset della posizione per l'SDK ONNX
        flatMelFloatBuffer.rewind()

        // 2. Creazione del Tensor [1, 76, 32, 1]
        // Usiamo il wrap del buffer pre-allocato
        val tensor = OnnxTensor.createTensor(env, flatMelFloatBuffer, longArrayOf(1, 76, 32, 1))

        tensor.use {
            // Esecuzione sulla sessione dell'Embedding Model (es. Google AudioSet)
            val output = embSession.run(Collections.singletonMap(embSession.inputNames.iterator().next(), tensor))

            output.use {
                val outTensor = it[0] as OnnxTensor
                val outBuffer = outTensor.floatBuffer

                val reusableEmbedding = embeddingBuffer.removeFirst()
                outBuffer.get(reusableEmbedding)
                embeddingBuffer.addLast(reusableEmbedding)
            }
        }
    }

    private fun checkWakeWord() {

        // 2. Compattazione degli embedding nel buffer piatto (Zero-copy)
        var offset = 0
        for (emb in embeddingBuffer) {
            System.arraycopy(emb, 0, flatClassBuffer, offset, embeddingSize)
            offset += embeddingSize
        }

        // 3. Creazione del Tensore [1, 16, 96]
        val shape = longArrayOf(1, numEmbeddings.toLong(), embeddingSize.toLong())
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(flatClassBuffer), shape)

        tensor.use {
            // Recupero dinamico del nome dell'input (es: "onnx::Flatten_0")
            val inputName = classifierSession.inputNames.iterator().next()

            // Esecuzione dell'inferenza sul classificatore finale
            val output = classifierSession.run(Collections.singletonMap(inputName, it))

            output.use { res ->
                // Estrazione della probabilità (Output atteso: [1, 1])
                @Suppress("UNCHECKED_CAST")
                val outValues = res[0].value as Array<FloatArray>
                val probability = outValues[0][0]

                // 4. Soglia di attivazione diretta
                if (probability >= minScore) {
                    cooldownCycles = cooldownTotalCycles
                    onDetected(probability)
                }
            }
        }
    }

    class AudioRingBuffer(private val capacity: Int) {
        private val buffer = ShortArray(capacity)
        private var writeIndex = 0

        fun addBlock(samples: ShortArray, size: Int) {
            val limit = if (size > capacity) capacity else size
            val spaceToEnd = capacity - writeIndex
            if (limit <= spaceToEnd) {
                System.arraycopy(samples, 0, buffer, writeIndex, limit)
            } else {
                System.arraycopy(samples, 0, buffer, writeIndex, spaceToEnd)
                System.arraycopy(samples, spaceToEnd, buffer, 0, limit - spaceToEnd)
            }
            writeIndex = (writeIndex + limit) % capacity
        }

        fun getLastSamples(n: Int): ShortArray {
            val result = ShortArray(n)
            var readIndex = writeIndex - n
            while (readIndex < 0) readIndex += capacity

            val spaceToEnd = capacity - readIndex
            if (n <= spaceToEnd) {
                System.arraycopy(buffer, readIndex, result, 0, n)
            } else {
                System.arraycopy(buffer, readIndex, result, 0, spaceToEnd)
                System.arraycopy(buffer, 0, result, spaceToEnd, n - spaceToEnd)
            }
            return result
        }
    }

}

