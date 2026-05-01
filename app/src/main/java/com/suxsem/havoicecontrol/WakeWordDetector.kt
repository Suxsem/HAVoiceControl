package com.suxsem.havoicecontrol

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtProvider
import ai.onnxruntime.OrtSession
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.Collections

private const val INT16_NORM_FACTOR = 1.0f / 32768.0f

private enum class VadMode {
    SPEEX_LISTENING,
    SILERO_STREAMING
}

class WakeWordDetector(
    private val context: Context,
    private val modelFile: String,
    private val verifierFile: String,
    private val minScore: Float,
    private val verifierScore: Float,
    private val onDetected: (prob: Float) -> Unit
) {

    // ── Audio capture constants ──────────────────────────────────────────────────

    private val sampleRate = 16000
    private val audioFrameSize = 256            // samples per AudioRecord read
    private val sileroFrameSize = 512           // samples per Silero VAD frame (2 × audioFrameSize)
    private val sileroWindowSize = 576          // Silero model input width (op15)
    private val frameSamples = 1280             // samples per 80 ms step

    // ── Wake-word pipeline constants ─────────────────────────────────────────────

    private val maxBatchSize = 16               // fills the full classifier observation window
    private val embeddingDim = 96
    private val numEmbeddings = 16
    private val melCoeffs = 32                  // mel coefficients per frame
    private val melWindow = 76                  // temporal window required by the embedding model
    private val melStep = 8                     // frame advance per 80 ms step

    // ── Silero VAD thresholds ────────────────────────────────────────────────────

    private val sileroThreshold = 0.6f
    private val sileroNegThreshold = sileroThreshold - 0.15f
    private val minSpeechFrames = 10            // ~300 ms at 32 ms/frame
    private val minSilenceFrames = 5            // ~150 ms at 32 ms/frame
    private val warmupChunks = 10               // Silero warmup frames on Speex→Silero transition
    private val sileroCooldownFrames = 312      // ~10 s at 32 ms/frame before Silero→Speex fallback

    // ── Wake-word cooldown ───────────────────────────────────────────────────────

    private val cooldownTotalCycles = 40        // ~1.28 s post-detection cooldown

    // ── Audio capture ────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private val audioRecord = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        sampleRate * 5
    )
    private var hwAgc: AutomaticGainControl? = null
    private var hwNs: NoiseSuppressor? = null

    // ── Ring buffers ─────────────────────────────────────────────────────────────

    private val audioRing = AudioRingBuffer(sampleRate * 8)                                         // 8 s of raw audio
    private val melRing = MelRingBuffer(melWindow + (maxBatchSize * melStep), melCoeffs)

    // ── Pre-allocated work buffers (direct for zero-copy ONNX tensor creation) ──

    private val readBuffer = ShortArray(audioFrameSize)
    private val vadScratchBuffer = ShortArray(sileroFrameSize)
    private val stepScratchBuffer = ShortArray(maxBatchSize * frameSamples + 480)

    private val vadAudioDirect = allocateDirectFloat(sileroWindowSize)
    private val vadStateDirect = allocateDirectFloat(256)
    private val sampleRateDirect = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        .asLongBuffer().apply { put(16000L); rewind() }

    private val melInputDirect = allocateDirectFloat(numEmbeddings * 1760)
    private val melOutputDirect = allocateDirectFloat(maxBatchSize * melWindow * melCoeffs)
    private val embOutputDirect = allocateDirectFloat(numEmbeddings * embeddingDim)

    // ── ONNX runtime ─────────────────────────────────────────────────────────────

    private val ortEnv = OrtEnvironment.getEnvironment()
    private lateinit var sileroSession: OrtSession
    private lateinit var melSession: OrtSession
    private lateinit var embeddingSession: OrtSession
    private lateinit var classifierSession: OrtSession
    private lateinit var verifierSession: OrtSession

    private lateinit var melInputName: String
    private lateinit var embeddingInputName: String
    private lateinit var classifierInputName: String
    private lateinit var verifierInputName: String

    private val sileroInputMap = HashMap<String, OnnxTensor>(3)

    // ── Embedding accumulator ────────────────────────────────────────────────────

    private val embeddingQueue = ArrayDeque<FloatArray>(numEmbeddings)

    // ── Speex native wrapper ─────────────────────────────────────────────────────

    private val speex = SpeexWrapper()

    // ── Mutable state ────────────────────────────────────────────────────────────

    private var isPaused = false

    // VAD state machine
    private var vadMode = VadMode.SPEEX_LISTENING
    private var sileroConsecutiveFalse = 0
    private var isSpeechTriggered = false
    private var speechFrameCount = 0
    private var silenceFrameCount = 0

    // Wake-word pipeline state
    private var pipelineActive = false
    private var pipelineCycleCount = 0
    private var cooldownRemaining = 0
    private var vadActivationTime = 0L

    // ── Initialization ───────────────────────────────────────────────────────────

    init {
        loadModels()
        speex.initSpeex()

        if (AutomaticGainControl.isAvailable()) {
            hwAgc = AutomaticGainControl.create(audioRecord.audioSessionId)
            hwAgc!!.enabled = true
        }
        if (NoiseSuppressor.isAvailable()) {
            hwNs = NoiseSuppressor.create(audioRecord.audioSessionId)
            hwNs!!.enabled = true
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────────

    suspend fun startDetection() = withContext(Dispatchers.Default) {
        resetAll()
        isPaused = false
        audioRecord.startRecording()

        while (isActive && !isPaused) {
            var speexActive = false

            // Read 512 samples (2 × 256) — one Silero VAD frame
            repeat(2) {
                if (audioRecord.read(readBuffer, 0, audioFrameSize, AudioRecord.READ_BLOCKING) != audioFrameSize) {
                    if (isPaused) return@repeat
                    return@repeat
                }
                if (speex.processAudio(readBuffer)) speexActive = true
                if (isPaused) return@repeat
                audioRing.addBlock(readBuffer, audioFrameSize)
            }

            // Post-detection cooldown — skip inference
            if (cooldownRemaining > 0) {
                cooldownRemaining--
                pipelineActive = false
                pipelineCycleCount = 0
                continue
            }

            val speechDetected = when (vadMode) {
                VadMode.SPEEX_LISTENING -> handleSpeexListening(speexActive)
                VadMode.SILERO_STREAMING -> handleSileroStreaming()
            }

            updatePipeline(speechDetected)
        }
    }

    fun pauseDetection() {
        isPaused = true
        audioRecord.stop()
    }

    fun releaseResources() {
        isPaused = true

        hwAgc?.release(); hwAgc = null
        hwNs?.release(); hwNs = null

        audioRecord.stop()
        audioRecord.release()

        // Close ONNX sessions before the environment
        try {
            if (::sileroSession.isInitialized) sileroSession.close()
            if (::melSession.isInitialized) melSession.close()
            if (::embeddingSession.isInitialized) embeddingSession.close()
            if (::classifierSession.isInitialized) classifierSession.close()
            if (::verifierSession.isInitialized) verifierSession.close()
        } catch (_: Exception) {}

        ortEnv.close()
        speex.destroySpeex()
    }

    // ── Model loading ────────────────────────────────────────────────────────────

    private fun loadModels() {
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 2)
            setIntraOpNumThreads(threads)
        }

        val providers = OrtEnvironment.getAvailableProviders()
        if (providers.contains(OrtProvider.NNAPI)) opts.addNnapi()
        if (providers.contains(OrtProvider.XNNPACK)) opts.addXnnpack(Collections.emptyMap())
        if (providers.contains(OrtProvider.QNN)) opts.addQnn(mapOf("backend_path" to "libQnnHtp.so"))

        fun loadAsset(name: String) = ortEnv.createSession(context.assets.open(name).readBytes(), opts)

        sileroSession = loadAsset("silero_vad_16k_op15.onnx")
        melSession = loadAsset("melspectrogram.onnx")
        embeddingSession = loadAsset("embedding_model.onnx")
        classifierSession = loadAsset(modelFile)
        verifierSession = loadAsset(verifierFile)

        melInputName = melSession.inputNames.first()
        embeddingInputName = embeddingSession.inputNames.first()
        classifierInputName = classifierSession.inputNames.first()
        verifierInputName = verifierSession.inputNames.first()
    }

    // ── State reset ──────────────────────────────────────────────────────────────

    private fun resetAll() {
        audioRing.clear()
        melRing.reset()

        embeddingQueue.clear()
        repeat(numEmbeddings) { embeddingQueue.add(FloatArray(embeddingDim)) }

        pipelineActive = false
        pipelineCycleCount = 0
        cooldownRemaining = 0

        resetSileroState()
        vadMode = VadMode.SPEEX_LISTENING
        sileroConsecutiveFalse = 0
    }

    private fun resetSileroState() {
        for (i in 0 until 256) vadStateDirect.put(i, 0f)
        speechFrameCount = 0
        silenceFrameCount = 0
        isSpeechTriggered = false
    }

    // ── VAD: Speex → Silero state machine ────────────────────────────────────────

    private fun handleSpeexListening(speexActive: Boolean): Boolean {
        if (!speexActive) return false
        resetSileroState()
        val warmupDetected = warmupSilero()
        vadMode = VadMode.SILERO_STREAMING
        sileroConsecutiveFalse = 0
        return warmupDetected
    }

    private fun handleSileroStreaming(): Boolean {
        val result = runSileroVad()
        if (result) {
            sileroConsecutiveFalse = 0
        } else {
            sileroConsecutiveFalse++
            if (sileroConsecutiveFalse >= sileroCooldownFrames) {
                resetSileroState()
                vadMode = VadMode.SPEEX_LISTENING
                pipelineActive = false
                pipelineCycleCount = 0
            }
        }
        return result
    }

    // ── VAD: Silero inference ────────────────────────────────────────────────────

    /** Run Silero VAD on the latest 512 samples from the audio ring buffer. */
    private fun runSileroVad(): Boolean {
        audioRing.getLastSamplesInto(vadScratchBuffer, sileroFrameSize)
        for (i in 0 until sileroFrameSize) {
            vadAudioDirect.put(i, vadScratchBuffer[i] * INT16_NORM_FACTOR)
        }
        return runSileroInference()
    }

    /**
     * Feed 10 chronological chunks from the ring buffer through Silero
     * to warm up its LSTM state after a Speex→Silero transition.
     */
    private fun warmupSilero(): Boolean {
        val totalSamples = warmupChunks * sileroFrameSize
        audioRing.getLastSamplesInto(stepScratchBuffer, totalSamples)
        var detected = false

        for (c in 0 until warmupChunks) {
            val offset = c * sileroFrameSize
            for (i in 0 until sileroFrameSize) {
                vadAudioDirect.put(i, stepScratchBuffer[offset + i] * INT16_NORM_FACTOR)
            }
            if (runSileroInference()) detected = true
        }
        return detected
    }

    /**
     * Run Silero VAD inference on [vadAudioDirect] (already populated).
     * Updates the recurrent state and the speech trigger state machine.
     */
    private fun runSileroInference(): Boolean {
        vadAudioDirect.rewind()
        val inputTensor = OnnxTensor.createTensor(ortEnv, vadAudioDirect, longArrayOf(1, sileroWindowSize.toLong()))
        sampleRateDirect.rewind()
        val srTensor = OnnxTensor.createTensor(ortEnv, sampleRateDirect, longArrayOf(1))
        vadStateDirect.rewind()
        val stateTensor = OnnxTensor.createTensor(ortEnv, vadStateDirect, longArrayOf(2, 1, 128))

        sileroInputMap.clear()
        sileroInputMap["input"] = inputTensor
        sileroInputMap["sr"] = srTensor
        sileroInputMap["state"] = stateTensor

        return try {
            @Suppress("UNCHECKED_CAST")
            sileroSession.run(sileroInputMap).use { results ->
                val probability = (results[0].value as Array<FloatArray>)[0][0]

                // Copy updated LSTM state back into the direct buffer
                val newState = results[1].value as Array<Array<FloatArray>>
                var offset = 0
                for (i in 0 until 2) {
                    vadStateDirect.position(offset)
                    vadStateDirect.put(newState[i][0], 0, 128)
                    offset += 128
                }

                // Speech trigger state machine (VADIterator-style)
                if (probability >= sileroThreshold) {
                    silenceFrameCount = 0
                    speechFrameCount++
                    if (speechFrameCount >= minSpeechFrames && !isSpeechTriggered) {
                        isSpeechTriggered = true
                    }
                } else if (probability < sileroNegThreshold) {
                    speechFrameCount = 0
                    if (isSpeechTriggered) {
                        silenceFrameCount++
                        if (silenceFrameCount >= minSilenceFrames) {
                            isSpeechTriggered = false
                        }
                    }
                }

                isSpeechTriggered
            }
        } finally {
            inputTensor.close()
            srTensor.close()
            stateTensor.close()
        }
    }

    // ── Wake-word pipeline control ───────────────────────────────────────────────

    private fun updatePipeline(speechDetected: Boolean) {
        if (speechDetected) {
            if (pipelineActive) {
                pipelineCycleCount++
                if (pipelineCycleCount == 5) {
                    runPipelineStep(numSteps = 2)
                    pipelineCycleCount = 0
                }
            } else {
                vadActivationTime = System.currentTimeMillis()
                runPipelineStep(numSteps = maxBatchSize)
                pipelineActive = true
                pipelineCycleCount = 0
            }
        } else {
            pipelineActive = false
        }
    }

    /** Run N × 80 ms through mel → embedding → classifier. */
    private fun runPipelineStep(numSteps: Int) {
        val contextSamples = 480
        val totalSamples = (numSteps * frameSamples) + contextSamples
        audioRing.getLastSamplesInto(stepScratchBuffer, totalSamples)

        for (i in 0 until numSteps) {
            val srcOffset = i * frameSamples
            val dstOffset = i * 1760
            for (j in 0 until 1760) {
                melInputDirect.put(dstOffset + j, stepScratchBuffer[srcOffset + j].toFloat())
            }
        }

        runMelBatch(numSteps)
        runEmbeddingBatch(numSteps)
        evaluateWakeWord()
    }

    // ── Mel spectrogram inference ────────────────────────────────────────────────

    private fun runMelBatch(batchSize: Int) {
        melInputDirect.rewind()
        melInputDirect.limit(batchSize * 1760)

        val tensor = OnnxTensor.createTensor(ortEnv, melInputDirect, longArrayOf(batchSize.toLong(), 1760L))
        tensor.use {
            melSession.run(Collections.singletonMap(melInputName, it)).use { out ->
                val flatBuffer = (out[0] as OnnxTensor).floatBuffer
                flatBuffer.rewind()
                repeat(batchSize * 8) {
                    val frame = melRing.advance()
                    for (f in 0 until melCoeffs) {
                        frame[f] = (flatBuffer.get() / 10.0f) + 2.0f
                    }
                }
            }
        }

        melInputDirect.clear()

        // Pack mel windows into the flat direct buffer for embedding inference
        val ringSize = melRing.size
        for (b in 0 until batchSize) {
            val batchOffset = b * melWindow * melCoeffs
            val stepsBack = (batchSize - 1 - b) * melStep
            val startIdx = (ringSize - melWindow) - stepsBack

            for (f in 0 until melWindow) {
                melOutputDirect.position(batchOffset + (f * melCoeffs))
                melOutputDirect.put(melRing[startIdx + f], 0, melCoeffs)
            }
        }
    }

    // ── Embedding inference ──────────────────────────────────────────────────────

    private fun runEmbeddingBatch(batchSize: Int) {
        val elements = batchSize * melWindow * melCoeffs
        melOutputDirect.rewind()
        melOutputDirect.limit(elements)

        val tensor = OnnxTensor.createTensor(
            ortEnv, melOutputDirect,
            longArrayOf(batchSize.toLong(), melWindow.toLong(), melCoeffs.toLong(), 1L)
        )
        tensor.use {
            embeddingSession.run(Collections.singletonMap(embeddingInputName, it)).use { out ->
                val outBuffer = (out[0] as OnnxTensor).floatBuffer
                repeat(batchSize) {
                    val emb = embeddingQueue.removeFirst()
                    outBuffer.get(emb)
                    embeddingQueue.addLast(emb)
                }
            }
        }

        melOutputDirect.clear()
    }

    // ── Wake-word classification ─────────────────────────────────────────────────

    private fun evaluateWakeWord() {
        embOutputDirect.clear()
        for (emb in embeddingQueue) {
            embOutputDirect.put(emb, 0, embeddingDim)
        }
        embOutputDirect.rewind()

        val shape = longArrayOf(1, numEmbeddings.toLong(), embeddingDim.toLong())
        val tensor = OnnxTensor.createTensor(ortEnv, embOutputDirect, shape)

        tensor.use {
            classifierSession.run(Collections.singletonMap(classifierInputName, it)).use { res ->
                @Suppress("UNCHECKED_CAST")
                val probability = (res[0].value as Array<FloatArray>)[0][0]

                if (probability < minScore) return

                if (verifierScore > 0) {
                    verifierSession.run(Collections.singletonMap(verifierInputName, it)).use { vRes ->
                        @Suppress("UNCHECKED_CAST")
                        val score = (vRes[0].value as Array<FloatArray>)[0].last() // TODO: verify output shape
                        if (score >= verifierScore) onWakeWordDetected(score)
                    }
                } else {
                    onWakeWordDetected(probability)
                }
            }
        }
    }

    private fun onWakeWordDetected(score: Float) {
        cooldownRemaining = cooldownTotalCycles
        onDetected(score)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    companion object {
        private fun allocateDirectFloat(count: Int) =
            ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }

    // ── Inner data structures ────────────────────────────────────────────────────

    /** Fixed-size circular buffer of [FloatArray] frames with O(1) indexed access. */
    class MelRingBuffer(val size: Int, private val frameSize: Int) {
        private val data = Array(size) { FloatArray(frameSize) }
        private var head = 0

        operator fun get(index: Int): FloatArray = data[(head + index) % size]

        /** Advance the write head and return the (now-oldest) frame for reuse. */
        fun advance(): FloatArray {
            val frame = data[head]
            head = (head + 1) % size
            return frame
        }

        fun reset() {
            head = 0
            for (frame in data) frame.fill(0f)
        }
    }

    /** Fixed-capacity circular buffer of 16-bit PCM samples with zero-allocation reads. */
    class AudioRingBuffer(private val capacity: Int) {
        private val buffer = ShortArray(capacity)
        private var writeIndex = 0

        fun addBlock(samples: ShortArray, size: Int) {
            val limit = size.coerceAtMost(capacity)
            val spaceToEnd = capacity - writeIndex
            if (limit <= spaceToEnd) {
                System.arraycopy(samples, 0, buffer, writeIndex, limit)
            } else {
                System.arraycopy(samples, 0, buffer, writeIndex, spaceToEnd)
                System.arraycopy(samples, spaceToEnd, buffer, 0, limit - spaceToEnd)
            }
            writeIndex = (writeIndex + limit) % capacity
        }

        fun getLastSamplesInto(dest: ShortArray, n: Int) {
            var readIndex = (writeIndex - n).mod(capacity)
            val spaceToEnd = capacity - readIndex
            if (n <= spaceToEnd) {
                System.arraycopy(buffer, readIndex, dest, 0, n)
            } else {
                System.arraycopy(buffer, readIndex, dest, 0, spaceToEnd)
                System.arraycopy(buffer, 0, dest, spaceToEnd, n - spaceToEnd)
            }
        }

        fun clear() {
            writeIndex = 0
            buffer.fill(0)
        }
    }
}
