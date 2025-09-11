package com.suxsem.havoicesatellite;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;

public class AudioRecorderThread extends Thread {
    private static final double MIN_SCORE = 0.05;
    private static final int COOLDOWN_MS = 3000;
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private long lastWakeWordTime = 0L;

    private AudioRecord audioRecord;
    private boolean isRecording = false;

    private final Model model;
    private final WakeWordListener listener;

    public AudioRecorderThread(Model model, WakeWordListener listener) {
        this.model = model;
        this.listener = listener;
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
            float[] floatBuffer = new float[audioBuffer.length];

            for (int i = 0; i < audioBuffer.length; i++) {
                floatBuffer[i] = audioBuffer[i] / 32768.0f;
            }

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
