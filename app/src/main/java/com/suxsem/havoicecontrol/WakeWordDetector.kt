package com.suxsem.havoicecontrol

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import kotlinx.coroutines.*
import java.nio.FloatBuffer
import java.util.ArrayDeque

class WakeWordDetector(
    private val context: Context,
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

    private val melBuffer = ArrayDeque<FloatArray>(melWindowSize + numEmbeddings)
    private val embeddingBuffer = ArrayDeque<FloatArray>(numEmbeddings)

    private var isVADActive = false
    private var vadCycleCount = 0

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
    }

    private fun loadModels() {
        val opts = OrtSession.SessionOptions()
        melSession = env.createSession(context.assets.open("melspectrogram.onnx").readBytes(), opts)
        embSession = env.createSession(context.assets.open("embedding_model.onnx").readBytes(), opts)
        classifierSession = env.createSession(context.assets.open("hey_veekee.onnx").readBytes(), opts)
    }

    @SuppressLint("MissingPermission")
    suspend fun startDetection() = withContext(Dispatchers.Default) {
        val audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minAudioBufSize * 2)
        audioRecord.startRecording()

        try {
            while (isActive) {
                val read =
                    audioRecord.read(tempBuffer, 0, audioFrameSize, AudioRecord.READ_BLOCKING)
                if (read == audioFrameSize) {
                    ringBuffer.addBlock(tempBuffer, read)

                    val speechDetected = vad.isSpeech(tempBuffer)

                    if (speechDetected) {
                        if (isVADActive) {
                            vadCycleCount++
                            if (vadCycleCount == 5) {
                                // Abbiamo esattamente 2560 campioni nuovi (80ms * 2)
                                runStep(numSteps = 2)
                                vadCycleCount = 0
                            }
                        } else {
                            runBurstInference()
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

    private fun runBurstInference() {
        val totalNeededSamples = (melWindowSize + numEmbeddings - 1) * frameSamples
        val audioData = ringBuffer.getLastSamples(totalNeededSamples)
        val floatAudio = FloatArray(audioData.size) { audioData[it] / 32768.0f }

        melBuffer.clear()
        embeddingBuffer.clear()

        // 1. Burst Mel
        for (i in 0 until (melWindowSize + numEmbeddings - 1)) {
            val start = i * frameSamples
            val frame = floatAudio.copyOfRange(start, start + frameSamples)
            melBuffer.add(runMelInference(frame))
        }

        // 2. Burst Embedding
        val melList = melBuffer.toList()
        for (i in 0 until numEmbeddings) {
            val window = melList.subList(i, i + melWindowSize)
            embeddingBuffer.add(runEmbeddingInference(window))
        }

        checkWakeWord()
    }

    /**
     * Esegue il mantenimento della pipeline per N step da 80ms
     */
    private fun runStep(numSteps: Int) {
        val totalSamples = numSteps * frameSamples // 2 * 1280 = 2560
        val audioData = ringBuffer.getLastSamples(totalSamples)
        val floatAudio = FloatArray(audioData.size) { audioData[it] / 32768.0f }

        for (i in 0 until numSteps) {
            val start = i * frameSamples
            val frame = floatAudio.copyOfRange(start, start + frameSamples)

            // Aggiornamento Mel
            val newMel = runMelInference(frame)
            melBuffer.removeFirst()
            melBuffer.addLast(newMel)

            // Aggiornamento Embedding
            val newEmbedding = runEmbeddingInference(melBuffer.toList())
            embeddingBuffer.removeFirst()
            embeddingBuffer.addLast(newEmbedding)
        }

        // Classificazione finale dopo i 2 step
        checkWakeWord()
    }

    private fun runMelInference(audio: FloatArray): FloatArray {
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(audio), longArrayOf(1, audio.size.toLong()))
        tensor.use {
            val output = melSession.run(mapOf("input" to tensor))
            output.use {
                @Suppress("UNCHECKED_CAST")
                val res = output[0].value as Array<Array<FloatArray>>
                return res[0][0]
            }
        }
    }

    private fun runEmbeddingInference(melWindow: List<FloatArray>): FloatArray {
        val flatData = FloatArray(melWindowSize * melSize)
        for (i in melWindow.indices) {
            System.arraycopy(melWindow[i], 0, flatData, i * melSize, melSize)
        }

        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(flatData), longArrayOf(1, melWindowSize.toLong(), melSize.toLong(), 1))
        tensor.use {
            val output = embSession.run(mapOf("input_1" to tensor))
            output.use {
                @Suppress("UNCHECKED_CAST")
                val res = output[0].value as Array<Array<Array<FloatArray>>>
                return res[0][0][0]
            }
        }
    }

    private fun checkWakeWord() {
        if (embeddingBuffer.size < numEmbeddings) return

        val flatEmbeddings = FloatArray(numEmbeddings * embeddingSize)
        val embList = embeddingBuffer.toList()
        for (i in embList.indices) {
            System.arraycopy(embList[i], 0, flatEmbeddings, i * embeddingSize, embeddingSize)
        }

        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(flatEmbeddings), longArrayOf(1, numEmbeddings.toLong(), embeddingSize.toLong()))
        tensor.use {
            val output = classifierSession.run(mapOf("onnx::Flatten_0" to tensor))
            output.use {
                @Suppress("UNCHECKED_CAST")
                val res = output[0].value as Array<FloatArray>
                val prob = res[0][0]
                if (prob > 0.5f) {
                    onDetected(prob)
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

