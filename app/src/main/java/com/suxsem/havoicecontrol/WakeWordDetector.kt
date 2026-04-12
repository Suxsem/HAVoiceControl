package com.suxsem.havoicecontrol

import android.content.Context
import android.media.AudioFormat
import android.media.MediaRecorder
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import kotlinx.coroutines.*
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.ArrayDeque
import java.util.Collections
import kotlin.math.sqrt

/*
IMPROVEMENTS:
- webrtc prima di silero. non semplice perché silero vuole audio costante
- batch embeddings
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
    private val audioFrameSize = 512
    private val sampleRate = 16000
    private val frameSamples = 1280 // 80ms a 16kHz
    private val melSize = 32
    private val embeddingSize = 96
    private val numEmbeddings = 16
    private val melWindowSize = 76

    private val webRtcAudioBridge = AudioBridge(3200)
    private val ringBuffer = AudioRingBuffer(sampleRate * 8) // 8 secondi

    private val melBuffer = ArrayDeque<FloatArray>(melWindowSize)
    private val embeddingBuffer = ArrayDeque<FloatArray>(numEmbeddings)

    private val flatMelBuffer = FloatArray(melWindowSize * melSize)
    private val flatMelFloatBuffer = FloatBuffer.wrap(flatMelBuffer)
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

    private lateinit var webRtcAudioDeviceModule: JavaAudioDeviceModule

    private var sileroVad: VadSilero? = null

    private val tempBuffer = ShortArray(audioFrameSize)

    init {
        loadModels()
        setupWebRtcAudio(context)
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

    private fun setupWebRtcAudio(context: Context) {
        // 1. Inizializza l'ambiente nativo WebRTC
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )

        // 3. Configura il modulo con NS e AGC software attivi
        webRtcAudioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setInputSampleRate(16000)
            .setUseHardwareNoiseSuppressor(true) //fallback software
            .setUseHardwareAcousticEchoCanceler(true) //fallback software
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.ENCODING_PCM_16BIT)
            .setUseStereoInput(false)
            .setSamplesReadyCallback { audioFrame ->
                val pcmData = audioFrame.data
                val shorts = ShortArray(pcmData.size / 2)
                ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                //val amplified = applyAGC(shorts)
                webRtcAudioBridge.push(shorts) // Veloce, no boxing, no allocazioni extra
            }
            .createAudioDeviceModule()

    }

    private fun reset() {
        // 1. Reset del RingBuffer Audio
        ringBuffer.clear()
        webRtcAudioBridge.clear()

        // 2. Reset delle code Mel ed Embedding (ripristina lo stato iniziale "vuoto")
        melBuffer.clear()
        repeat(melWindowSize) {
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
        webRtcAudioDeviceModule.release()
        sileroVad?.close()
        env.close() // Chiude anche l'ambiente ONNX
    }

    fun pauseDetection() {
        isPaused = true
        webRtcAudioDeviceModule.requestStopRecording()
    }

    suspend fun startDetection() = withContext(Dispatchers.Default) {

        reset()
        isPaused = false
        webRtcAudioDeviceModule.requestStartRecording()


        while (isActive && !isPaused) {

            if (!webRtcAudioBridge.pull(tempBuffer)) {
                if (isPaused) break
                continue // WebRTC non ha ancora prodotto abbastanza dati
            }

            if (isPaused) break

            ringBuffer.addBlock(tempBuffer, audioFrameSize)

            val speechDetected = sileroVad?.isSpeech(tempBuffer) ?: false

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

    private fun checkWakeWord() : Boolean {

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
                                cooldownCycles = cooldownTotalCycles
                                onDetected(verifiedScore)
                            }
                        }

                    } else {
                        cooldownCycles = cooldownTotalCycles
                        onDetected(probability)
                        return true
                    }
                }
            }
        }
        return false
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

    class AudioBridge(capacity: Int) {
        private val buffer = ShortArray(capacity)
        private var writeIdx = 0
        private var readIdx = 0
        private var count = 0
        private val lock = Object()

        fun push(samples: ShortArray) {
            synchronized(lock) {
                for (s in samples) {
                    buffer[writeIdx] = s
                    writeIdx = (writeIdx + 1) % buffer.size
                }
                count = buffer.size.coerceAtMost(count + samples.size)
                lock.notifyAll()
            }
        }

        fun pull(target: ShortArray): Boolean {
            synchronized(lock) {
                val timeout = 100L
                val start = System.currentTimeMillis()
                while (count < target.size) {
                    val remaining = timeout - (System.currentTimeMillis() - start)
                    if (remaining <= 0) return false
                    lock.wait(remaining)
                }
                for (i in target.indices) {
                    target[i] = buffer[readIdx]
                    readIdx = (readIdx + 1) % buffer.size
                }
                count -= target.size
                return true
            }
        }

        /**
         * Svuota completamente il bridge, resettando i puntatori e il conteggio.
         * Da chiamare nel metodo reset() del WakeWordDetector.
         */
        fun clear() {
            synchronized(lock) {
                writeIdx = 0
                readIdx = 0
                count = 0
                // Opzionale: pulizia fisica dell'array per sicurezza (ma non strettamente necessaria)
                buffer.fill(0)
                Log.d("AudioBridge", "Bridge cleared and pointers reset")
            }
        }
    }

}

