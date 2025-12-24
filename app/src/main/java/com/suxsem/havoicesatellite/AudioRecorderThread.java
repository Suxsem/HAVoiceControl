package com.suxsem.havoicesatellite;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;

public class AudioRecorderThread extends Thread {
    private static final double MIN_SCORE = 0.20;
    private static final int COOLDOWN_MS = 3000;
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private static final float INT16_NORMALIZATION_FACTOR = 1.0f / 32768.0f;

    private float currentGain = 1.0f;
    private static final float TARGET_RMS = 0.15f; // Il volume "ideale" che vogliamo raggiungere
    private static final float MAX_GAIN = 10.0f;   // Non vogliamo amplificare il fruscio all'infinito
    private static final float MIN_GAIN = 0.5f;    // Non vogliamo ammutolire il segnale
    private static final float ADAPTATION_SPEED = 0.05f; // Quanto velocemente cambia il gain

    private long lastWakeWordTime = 0L;

    private AudioRecord audioRecord;
    private boolean isRecording = false;

    private final Model model;
    private final WakeWordListener listener;

    public AudioRecorderThread(Model model, WakeWordListener listener) {
        this.model = model;
        this.listener = listener;
    }

    public float[] applyAGCAndNormalize(short[] audioBuffer) {
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
            currentGain = Math.max(MIN_GAIN, Math.min(MAX_GAIN, currentGain));
        }

        return floatBuffer;
    }

    @SuppressLint("MissingPermission")
    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);

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
            float[] floatBuffer = applyAGCAndNormalize(audioBuffer);

            String res = model.predict_WakeWord(floatBuffer);
            double score = Double.parseDouble(res);

            long now = System.currentTimeMillis();
            if (listener != null && score > MIN_SCORE && now - lastWakeWordTime >= COOLDOWN_MS) {
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
