package com.suxsem.havoicesatellite;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;

import java.io.IOException;

import ai.onnxruntime.OrtException;

public class AudioRecorderThread extends Thread {
    private static final int COOLDOWN_MS = 3000;
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private static final float INT16_NORMALIZATION_FACTOR = 1.0f / 32768.0f;
    private float currentGain = 1.0f;
    private static final float TARGET_RMS = 0.15f; // Il volume "ideale" che vogliamo raggiungere
    private static final float ADAPTATION_SPEED = 0.05f; // Quanto velocemente cambia il gain

    private long lastWakeWordTime = 0L;

    private final Context context;

    private AudioRecord audioRecord;
    private boolean isRecording = false;

    private final WakeWordListener listener;

    public AudioRecorderThread(Context context, WakeWordListener listener) {
        this.context = context;
        this.listener = listener;
    }

    public float[] applyAGCAndNormalize(short[] audioBuffer, float minGain, float maxGain) {
        float[] floatBuffer = new float[audioBuffer.length];
        double sumSq = 0;

        // 1. Applichiamo il gain attuale e normalizziamo
        for (int i = 0; i < audioBuffer.length; i++) {
            floatBuffer[i] = (audioBuffer[i] * INT16_NORMALIZATION_FACTOR) * currentGain;
            sumSq += floatBuffer[i] * floatBuffer[i];
        }

        // 2. Calcoliamo l'RMS del buffer appena modificato
        float rms = (float) Math.sqrt(sumSq / audioBuffer.length);

        // 3. AGGIORNAMENTO DEL GAIN (Feedback loop)
        // Se il volume è troppo basso, aumentiamo lentamente il gain per il prossimo buffer
        // Se è troppo alto, lo abbassiamo.
        if (rms > 0.001f) { // Evitiamo di calcolare sul silenzio assoluto
            float error = TARGET_RMS / rms;
            // Applichiamo l'aggiustamento in modo graduale (smoothing)
            currentGain += (error - 1.0f) * ADAPTATION_SPEED;

            // Clamp del gain tra i valori min/max
            currentGain = Math.max(minGain, Math.min(maxGain, currentGain));
        }

        return floatBuffer;
    }

    @SuppressLint("MissingPermission")
    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);

        SharedPreferences prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE);
        float minScore = prefs.getFloat("min_score", 0);
        float minGain = prefs.getFloat("min_gain", 0);
        float maxGain = prefs.getFloat("max_gain", 0);
        float energyTreshold = prefs.getFloat("energy_treshold", 0);

        ONNXModelRunner modelRunner = null;
        try {
            modelRunner = new ONNXModelRunner(context.getAssets());
        } catch (IOException | OrtException e) {
            throw new RuntimeException(e);
        }
        var model = new Model(modelRunner, energyTreshold);

        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        int bufferSizeInShorts = 1280;
        if (minBufferSize / 2 < bufferSizeInShorts) {
            minBufferSize = bufferSizeInShorts * 2;
        }

        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBufferSize);

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            return;
        }

        short[] audioBuffer = new short[bufferSizeInShorts];
        audioRecord.startRecording();
        isRecording = true;

        while (isRecording) {
            audioRecord.read(audioBuffer, 0, audioBuffer.length);

            // Converte, normalizza e aggiusta il volume tutto in un colpo solo
            float[] floatBuffer = applyAGCAndNormalize(audioBuffer, minGain, maxGain);

            String res = model.predict_WakeWord(floatBuffer);
            float score = Float.parseFloat(res);

            long now = System.currentTimeMillis();
            if (listener != null && score > minScore && now - lastWakeWordTime >= COOLDOWN_MS) {
                listener.onWakeWordDetected(score);
                lastWakeWordTime = now;
            }
        }

        releaseResources();
    }

    public void stopRecording() {
        isRecording = false;
    }

    private void releaseResources() {
        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }
    }
}
