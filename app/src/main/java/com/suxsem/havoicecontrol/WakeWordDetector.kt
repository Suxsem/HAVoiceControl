package com.suxsem.havoicecontrol

import android.content.Context
import android.media.AudioFormat
import android.media.MediaRecorder
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.annotation.SuppressLint
import android.media.AudioRecord
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import kotlinx.coroutines.*
import java.nio.FloatBuffer
import java.util.ArrayDeque
import java.util.Collections
import kotlin.math.sqrt

/*
IMPROVEMENTS:
- webrtc prima di silero. non semplice perché silero vuole audio costante
- https://mvnrepository.com/artifact/com.microsoft.onnxruntime/onnxruntime-android-qnn
- silero senza liberia per avere controllo sul runtime
*/

class WakeWordDetector(
    private val context: Context,
    private val modelFile: String,
    private val verifierFile: String,
    private val minScore: Float,
    private val verifierScore: Float,
    private val onDetected: (prob: Float) -> Unit
) {
    private val maxBatchSize = 16 // per riempire esattamente la finestra di osservazione del modello
    private val audioFrameSize = 256
    private val sampleRate = 16000
    private val frameSamples = 1280 // 80ms a 16kHz
    private val embeddingSize = 96
    private val numEmbeddings = 16

    @SuppressLint("MissingPermission")
    private val audioRecord = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        sampleRate * 5 // 5 seconds
    )
    private var hwAgc: AutomaticGainControl? = null
    private var hwNs: NoiseSuppressor? = null

    private val ringBuffer = AudioRingBuffer(sampleRate * 8) // 8 secondi

    private val melSize = 32          // Numero di coefficienti Mel per ogni frame
    private val melWindowSize = 76    // Finestra temporale richiesta dal modello (76 frame)
    private val melFrameStep = 8      // Avanzamento temporale per ogni step (8 frame)

    private val melBuffer = ArrayDeque<FloatArray>(melWindowSize + (maxBatchSize * melFrameStep))
    private val embeddingBuffer = ArrayDeque<FloatArray>(numEmbeddings)

    private val flatMelBuffer = FloatArray(maxBatchSize * melWindowSize * melSize)
    private val flatClassBuffer = FloatArray(numEmbeddings * embeddingSize)
    private val modelInputBuffer = FloatArray(numEmbeddings * 1760) // max 5 steps

    private var isVADActive = false
    private var vadCycleCount = 0
    private var cooldownCycles = 0
    private val cooldownTotalCycles = 40 // = 1.28 secondi
    private var isPaused = false
    private val env = OrtEnvironment.getEnvironment()
    private lateinit var melSession: OrtSession
    private lateinit var embSession: OrtSession
    private lateinit var classifierSession: OrtSession
    private lateinit var verifierSession: OrtSession


    private var sileroVad: VadSilero? = null
    private var speexWrapper = SpeexWrapper()

    private val tempBuffer = ShortArray(audioFrameSize)
    private var vadActivationTime: Long = 0L

    init {
        loadModels()
        speexWrapper.initSpeex()

        if (AutomaticGainControl.isAvailable()) {
            hwAgc = AutomaticGainControl.create(audioRecord.audioSessionId)
            hwAgc!!.enabled = true
        }

        if (NoiseSuppressor.isAvailable()) {
            hwNs = NoiseSuppressor.create(audioRecord.audioSessionId)
            hwNs!!.enabled = true
        }
    }

    private fun loadModels() {
        val opts = OrtSession.SessionOptions()
        // Attiva il massimo livello di ottimizzazione
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

        val numCores = Runtime.getRuntime().availableProcessors()
        val optimalThreads = numCores.coerceAtLeast(1).coerceAtMost(2)
        opts.setIntraOpNumThreads(optimalThreads)

        melSession = env.createSession(context.assets.open("melspectrogram.onnx").readBytes(), opts)
        embSession = env.createSession(context.assets.open("embedding_model.onnx").readBytes(), opts)
        classifierSession = env.createSession(context.assets.open(modelFile).readBytes(), opts)
        verifierSession = env.createSession(context.assets.open(verifierFile).readBytes(), opts)
    }

    private fun reset() {
        // 1. Reset del RingBuffer Audio
        ringBuffer.clear()

        // 2. Reset delle code Mel ed Embedding (ripristina lo stato iniziale "vuoto")
        melBuffer.clear()
        repeat(melWindowSize + (maxBatchSize * melFrameStep)) {
            melBuffer.add(FloatArray(melSize))
        }
        embeddingBuffer.clear()
        repeat(numEmbeddings) {
            embeddingBuffer.add(FloatArray(embeddingSize))
        }

        // 3. Reset variabili di stato
        isVADActive = false
        vadCycleCount = 0
        cooldownCycles = 0

        sileroVad?.close()
        sileroVad = VadSilero(context, SampleRate.SAMPLE_RATE_16K, FrameSize.FRAME_SIZE_512, Mode.NORMAL, 300, 150)

        Log.d("WakeWordDetector", "All buffers and states cleared")
    }

    fun releaseResources() {
        isPaused = true

        hwAgc?.enabled = false
        hwAgc?.release()
        hwAgc = null

        hwNs?.enabled = false
        hwNs?.release()
        hwNs = null

        audioRecord.stop()
        audioRecord.release()

        sileroVad?.close()
        sileroVad = null

        env.close() // Chiude anche l'ambiente ONNX
        speexWrapper.destroySpeex()
    }

    fun pauseDetection() {
        isPaused = true
        audioRecord.stop()
    }

    suspend fun startDetection() = withContext(Dispatchers.Default) {

        reset()
        isPaused = false
        audioRecord.startRecording()

        while (isActive && !isPaused) {

            repeat (2) { // silero vuole 512 campioni
                if (audioRecord.read(
                        tempBuffer,
                        0,
                        audioFrameSize,
                        AudioRecord.READ_BLOCKING
                    ) != audioFrameSize
                ) {
                    if (isPaused) break
                    continue // WebRTC non ha ancora prodotto abbastanza dati
                }

                speexWrapper.processAudio(tempBuffer)

                if (isPaused) break

                ringBuffer.addBlock(tempBuffer, audioFrameSize)

            }

            val speechDetected = sileroVad?.isSpeech(ringBuffer.getLastSamples(512)) ?: false

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
                    vadActivationTime = System.currentTimeMillis()
                    Log.d("WakeWordService", "VAD attivato")
                    runStep(numSteps = maxBatchSize)
                    isVADActive = true
                    vadCycleCount = 0
                }
            } else {
                isVADActive = false
            }

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

        for (i in 0 until numSteps) {
            // Ogni riga i inizia con 480 campioni di contesto e prosegue con 1280 nuovi
            // Riga 0: offset 0 in floatAudio
            // Riga 1: offset 1280 in floatAudio...
            val sourceOffset = i * frameSamples
            val destinationOffset = i * 1760

            for (j in 0 until 1760) {
                // Conversione diretta Short -> Float senza array intermedi
                // Non dividiamo per 32768 come confermato da utils.py
                modelInputBuffer[destinationOffset + j] = audioData[sourceOffset + j].toFloat()
            }
        }

        runMelInferenceBatch(numSteps)
        runEmbeddingInferenceBatch(numSteps)
        checkWakeWord()
    }

    /**
     * Esegue l'inferenza Mel e restituisce una lista piatta di frame (8 frame per ogni 80ms).
     */
    /**
     * Legge direttamente dal buffer nativo di ONNX e aggiorna la coda Mel.
     * Zero allocazioni di liste intermedie.
     */
    /**
     * Esegue l'inferenza Mel su un batch di audio.
     * @param batchSize Il numero di blocchi da 80ms contenuti nell'audio
     */
    private fun runMelInferenceBatch(batchSize: Int) {

        val inputBuffer = FloatBuffer.wrap(modelInputBuffer)
        inputBuffer.limit(batchSize * 1760)

        val tensorInput = OnnxTensor.createTensor(env, inputBuffer, longArrayOf(batchSize.toLong(), 1760L))

        tensorInput.use {
            val output = melSession.run(Collections.singletonMap(melSession.inputNames.iterator().next(), it))
            output.use { melOut ->
                val outTensor = melOut[0] as OnnxTensor

                val flatBuffer = outTensor.floatBuffer
                flatBuffer.rewind()

                // 1. Aggiorniamo la coda Mel (la nostra storia temporale)
                repeat(batchSize * 8) {
                    val reusableFrame = melBuffer.removeFirst()
                    for (f in 0 until melSize) {
                        reusableFrame[f] = (flatBuffer.get() / 10.0f) + 2.0f
                    }
                    melBuffer.addLast(reusableFrame)
                }
            }
        }

        val framesList = melBuffer.toList()
        val totalAvailable = framesList.size

        for (b in 0 until batchSize) {
            val batchOffset = b * melWindowSize * melSize
            val stepsBack = (batchSize - 1 - b) * melFrameStep
            val startFrameIdx = (totalAvailable - melWindowSize) - stepsBack

            for (f in 0 until melWindowSize) {
                val frame = framesList[startFrameIdx + f]
                System.arraycopy(frame, 0, flatMelBuffer, batchOffset + (f * melSize), melSize)
            }
        }
    }

    private fun runEmbeddingInferenceBatch(batchSize: Int) {
        val totalElements = batchSize * melWindowSize * melSize
        val bufferForOnnx = FloatBuffer.wrap(flatMelBuffer, 0, totalElements)

        val tensor = OnnxTensor.createTensor(env, bufferForOnnx,
            longArrayOf(batchSize.toLong(), melWindowSize.toLong(), melSize.toLong(), 1L))

        tensor.use {
            val output = embSession.run(Collections.singletonMap(embSession.inputNames.iterator().next(), it))
            output.use { embOut ->
                val outTensor = embOut[0] as OnnxTensor
                val outBuffer = outTensor.floatBuffer

                repeat(batchSize) {
                    val reusableEmbedding = embeddingBuffer.removeFirst()
                    outBuffer.get(reusableEmbedding)
                    embeddingBuffer.addLast(reusableEmbedding)
                }
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

                    if (verifierScore > 0) {

                        // --- STEP 2: VERIFIER (Solo se il primo ha dato l'OK) ---
                        val verifierInputName = verifierSession.inputNames.iterator().next()
                        val verifierOutput =
                            verifierSession.run(Collections.singletonMap(verifierInputName, it))

                        verifierOutput.use { vRes ->
                            @Suppress("UNCHECKED_CAST")
                            val vValues = vRes[0].value as Array<FloatArray>

                            // Nota: In Python fa [0][-1]. Se il tuo ONNX del verifier
                            // ha output [1, 1], usa [0][0]. Se ha [1, 2], usa .last()
                            val verifiedScore =
                                vValues[0].last() //TODO verificare forma output modello

                            if (verifiedScore >= verifierScore) {
                                wakeWordDetected(verifierScore)
                            }
                        }

                    } else {
                        wakeWordDetected(probability)
                    }
                }
            }
        }
    }

    private fun wakeWordDetected(score: Float) {
        val detectionTime = System.currentTimeMillis()
        val delta = detectionTime - vadActivationTime

        Log.d("WakeWordDetector", "Rilevamento avvenuto in: ${delta}ms con score: $score")

        cooldownCycles = cooldownTotalCycles
        onDetected(score)
    }

    private fun applyAGC(samples: ShortArray): ShortArray {
        val targetRms = 3000f
        val rms = sqrt(samples.map { it.toDouble() * it }.average()).toFloat()
        if (rms < 1f) return samples
        val gain = (targetRms / rms).coerceIn(0.1f, 10f)
        return ShortArray(samples.size) {
            (samples[it] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
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

        fun clear() {
            writeIndex = 0
            buffer.fill(0)
        }
    }

}

