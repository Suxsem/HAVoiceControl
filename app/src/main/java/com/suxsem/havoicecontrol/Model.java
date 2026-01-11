package com.suxsem.havoicecontrol;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Random;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

class ONNXModelRunner {

    private static final int BATCH_SIZE = 1; // Replace with your batch size

    AssetManager assetManager;
    OrtSession session;
    OrtEnvironment hey_nugget_env = OrtEnvironment.getEnvironment();
    public ONNXModelRunner(AssetManager assetManager) throws IOException, OrtException {
        this.assetManager=assetManager;

        try {
            //alexa: score 0.6, min 1, max 4, th 0.1, comunque tanti falsi positivi
            //hey_veeke: score 0.6, min 1, max 4.5, th 0.1
            session = hey_nugget_env.createSession(readModelFile(assetManager, "hey_veekee.onnx"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // Load the ONNX model from the assets folder

    }

    public float[][] get_mel_spectrogram(float[] inputArray) throws OrtException, IOException {
        OrtSession session;
        try (InputStream modelInputStream = assetManager.open("melspectrogram.onnx")) {
            byte[] modelBytes = new byte[modelInputStream.available()];
            modelInputStream.read(modelBytes);
            session = OrtEnvironment.getEnvironment().createSession(modelBytes);
        }
        float[][] outputArray=null;
        int SAMPLES=inputArray.length;
        // Convert the input array to ONNX Tensor
        FloatBuffer floatBuffer = FloatBuffer.wrap(inputArray);
        OnnxTensor inputTensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), floatBuffer, new long[]{BATCH_SIZE, SAMPLES});

        // Run the model
        // Adjust this based on the actual expected output shape
        try (OrtSession.Result results = session.run(Collections.singletonMap(session.getInputNames().iterator().next(), inputTensor))) {

            float[][][][] outputTensor = (float[][][][]) results.get(0).getValue();
            // Here you need to cast the output appropriately
//            Object outputObject = outputTensor.getValue();

            // Check the actual type of 'outputObject' and cast accordingly
            // The following is an assumed cast based on your error message

            float[][] squeezed=squeeze(outputTensor);
            outputArray=applyMelSpecTransform(squeezed);


        }
        catch (Exception e)
        {
            e.printStackTrace();

        }
        finally {
            inputTensor.close();
            session.close();
        }
        OrtEnvironment.getEnvironment().close();
        return outputArray;
    }
    public static float[][] squeeze(float[][][][] originalArray) {
        float[][] squeezedArray = new float[originalArray[0][0].length][originalArray[0][0][0].length];
        for (int i = 0; i < originalArray[0][0].length; i++) {
            System.arraycopy(originalArray[0][0][i], 0, squeezedArray[i], 0, originalArray[0][0][0].length);
        }

        return squeezedArray;
    }
    public static float[][] applyMelSpecTransform(float[][] array) {
        float[][] transformedArray = new float[array.length][array[0].length];

        for (int i = 0; i < array.length; i++) {
            for (int j = 0; j < array[i].length; j++) {
                transformedArray[i][j] = array[i][j] / 10.0f + 2.0f;
            }
        }

        return transformedArray;
    }

    public float[][] generateEmbeddings(float[][][][] input) throws OrtException, IOException {
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        InputStream is = assetManager.open("embedding_model.onnx");
        byte[] model = new byte[is.available()];
        is.read(model);
        is.close();

        try (OrtSession session = env.createSession(model); OnnxTensor inputTensor = OnnxTensor.createTensor(env, input); OrtSession.Result results = session.run(Collections.singletonMap("input_1", inputTensor))) {
            // Extract the output tensor
            float[][][][] rawOutput = (float[][][][]) results.get(0).getValue();

            // Assuming the output shape is (41, 1, 1, 96), and we want to reshape it to (41, 96)
            float[][] reshapedOutput = new float[rawOutput.length][rawOutput[0][0][0].length];
            for (int i = 0; i < rawOutput.length; i++) {
                System.arraycopy(rawOutput[i][0][0], 0, reshapedOutput[i], 0, rawOutput[i][0][0].length);
            }
            return reshapedOutput;
        } catch (Exception e) {
            Log.d("exception", "not_predicted " + e.getMessage());
        }
        // You're doing this, which is good.
        // This should be added to ensure the session is also closed.
        env.close();
        return null;
    }

    public String predictWakeWord(float[][][] inputArray) throws OrtException {
        float[][] result = new float[0][];
        String resultant="";


        try (OnnxTensor inputTensor = OnnxTensor.createTensor(hey_nugget_env, inputArray)) {
            // Create a tensor from the input array
            // Run the inference
            OrtSession.Result outputs = session.run(Collections.singletonMap(session.getInputNames().iterator().next(), inputTensor));
            // Extract the output tensor, convert it to the desired type
            result = (float[][]) outputs.get(0).getValue();
            resultant = String.format(Locale.US, "%.5f", (double) result[0][0]);

        } catch (OrtException e) {
            e.printStackTrace();
        }
        // Add this to ensure the session is properly closed.
        return resultant;
    }
    private byte[] readModelFile(AssetManager assetManager, String filename) throws IOException {
        try (InputStream is = assetManager.open(filename)) {
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            return buffer;
        }
    }

    public void close() {
        try {
            if (session != null) {
                session.close();
            }
            if (hey_nugget_env != null) {
                hey_nugget_env.close();
            }
        } catch (OrtException e) {
            Log.e("ONNXModelRunner", "Errore durante la chiusura", e);
        }
    }

}


public class Model {

    int sampleRate = 16000;

    // Stato dell'Energy Gate
    private long lastActiveTime = 0;
    private static final long HANGOVER_MS = 1000;

    // Buffer audio circolari
    private final float[] circularAudioBuffer = new float[sampleRate * 10];
    private int circularWriteIndex = 0;
    private int totalSamplesInCircular = 0;
    private final float energyThreshold;

    int melspectrogramMaxLen = 10 * 97;
    int feature_buffer_max_len = 120;
    ONNXModelRunner modelRunner;
    float[][] featureBuffer;

    float[] raw_data_remainder = new float[0];
    float[][] melspectrogramBuffer;
    int accumulated_samples = 0;

    Model(ONNXModelRunner modelRunner, float energyThreshold) {
        this.modelRunner = modelRunner;
        this.energyThreshold = energyThreshold;
        reset(); // Inizializza i buffer usando il metodo reset
    }

    public void reset() {
        // 1. Resetta buffer audio circolare
        Arrays.fill(circularAudioBuffer, 0.0f);
        circularWriteIndex = 0;
        totalSamplesInCircular = 0;

        // 2. Resetta melspectrogramBuffer (riempiamo di 1.0 come nel costruttore originale)
        melspectrogramBuffer = new float[76][32];
        for (float[] row : melspectrogramBuffer) {
            Arrays.fill(row, 1.0f);
        }

        // 3. Resetta featureBuffer e altri contatori
        featureBuffer = null;
        accumulated_samples = 0;
        raw_data_remainder = new float[0];
        lastActiveTime = 0;

        // 4. Ri-popola il featureBuffer con dati dummy per evitare null pointer
        // e dare al modello uno stato "neutro" iniziale
        try {
            this.featureBuffer = this._getEmbeddings(this.generateRandomIntArray(16000 * 4), 76, 8);
        } catch (Exception e) {
            Log.e("Model", "Errore durante il reset del featureBuffer: " + e.getMessage());
        }
    }

    public float[][][] getFeatures(int nFeatureFrames, int startNdx) {
        int endNdx;
        if (startNdx != -1) {
            endNdx = (startNdx + nFeatureFrames != 0) ? (startNdx + nFeatureFrames) : featureBuffer.length;
        } else {
            startNdx = Math.max(0, featureBuffer.length - nFeatureFrames); // Ensure startNdx is not negative
            endNdx = featureBuffer.length;
        }

        int length = endNdx - startNdx;
        float[][][] result = new float[1][length][featureBuffer[0].length]; // Assuming the second dimension has fixed size.

        for (int i = 0; i < length; i++) {
            System.arraycopy(featureBuffer[startNdx + i], 0, result[0][i], 0, featureBuffer[startNdx + i].length);
        }

        return result;
    }

    // Java equivalent to _get_embeddings method
    private float[][] _getEmbeddings(float[] x, int windowSize, int stepSize) throws OrtException, IOException {

        float[][] spec = this.modelRunner.get_mel_spectrogram(x); // Assuming this method exists and returns float[][]
        ArrayList<float[][]> windows = new ArrayList<>();

        for (int i = 0; i <= spec.length - windowSize; i += stepSize) {
            float[][] window = new float[windowSize][spec[0].length];

            for (int j = 0; j < windowSize; j++) {
                System.arraycopy(spec[i + j], 0, window[j], 0, spec[0].length);
            }

            windows.add(window);
        }

        // Convert ArrayList to array and add the required extra dimension
        float[][][][] batch = new float[windows.size()][windowSize][spec[0].length][1];
        for (int i = 0; i < windows.size(); i++) {
            for (int j = 0; j < windowSize; j++) {
                for (int k = 0; k < spec[0].length; k++) {
                    batch[i][j][k][0] = windows.get(i)[j][k];  // Add the extra dimension here
                }
            }
        }

        try {
            return modelRunner.generateEmbeddings(batch);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // Assuming embeddingModelPredict is defined and returns float[][]
    }

    // Utility function to generate random int array, equivalent to np.random.randint
    private float[] generateRandomIntArray(int size) {
        float[] arr = new float[size];
        Random random = new Random();
        for (int i = 0; i < size; i++) {
            arr[i] = (float) random.nextInt(2000) - 1000; // range [-1000, 1000)
        }
        return arr;
    }

    public void bufferRawData(float[] data) {
        if (data == null || data.length == 0) return;

        int length = data.length;
        // Se i dati sono più lunghi del buffer stesso (improbabile), prendiamo solo gli ultimi
        if (length > circularAudioBuffer.length) {
            float[] truncated = new float[circularAudioBuffer.length];
            System.arraycopy(data, length - circularAudioBuffer.length, truncated, 0, circularAudioBuffer.length);
            data = truncated;
            length = data.length;
        }

        int spaceAtEnd = circularAudioBuffer.length - circularWriteIndex;

        if (length <= spaceAtEnd) {
            // I dati ci stanno senza ricominciare da capo
            System.arraycopy(data, 0, circularAudioBuffer, circularWriteIndex, length);
            circularWriteIndex += length;
        } else {
            // Dobbiamo dividere la copia: una parte alla fine, il resto all'inizio
            System.arraycopy(data, 0, circularAudioBuffer, circularWriteIndex, spaceAtEnd);
            System.arraycopy(data, spaceAtEnd, circularAudioBuffer, 0, length - spaceAtEnd);
            circularWriteIndex = length - spaceAtEnd;
        }

        // Aggiorniamo il totale dei campioni contenuti (fino al massimo della capacità)
        totalSamplesInCircular = Math.min(circularAudioBuffer.length, totalSamplesInCircular + length);

        if (circularWriteIndex >= circularAudioBuffer.length) {
            circularWriteIndex = 0;
        }
    }

    public void streamingMelSpectrogram(int n_samples) {
        // Il modello ha bisogno di n_samples + 480 (per il padding/overlap dello spettrogramma)
        int samplesToRead = n_samples + 480;

        if (totalSamplesInCircular < samplesToRead) {
            // Se non abbiamo abbastanza storia, leggiamo tutto quello che abbiamo
            samplesToRead = totalSamplesInCircular;
        }

        // Estraiamo i dati in modo efficiente
        float[] tempArray = getSamplesFromCircular(samplesToRead);

        // Chiamata al modello ONNX per lo spettrogramma
        float[][] new_mel_spectrogram;
        try {
            new_mel_spectrogram = modelRunner.get_mel_spectrogram(tempArray);
        } catch (OrtException | IOException e) {
            throw new RuntimeException("Errore ONNX Spectrogram: " + e.getMessage());
        }

        // Aggiornamento del melspectrogramBuffer (Matrice float[][])
        updateMelBuffer(new_mel_spectrogram);
    }

    private void updateMelBuffer(float[][] new_mel) {
        float[][] combined = new float[this.melspectrogramBuffer.length + new_mel.length][];

        // Copia il vecchio e il nuovo
        System.arraycopy(this.melspectrogramBuffer, 0, combined, 0, this.melspectrogramBuffer.length);
        System.arraycopy(new_mel, 0, combined, this.melspectrogramBuffer.length, new_mel.length);

        this.melspectrogramBuffer = combined;

        // Trim se eccede melspectrogramMaxLen (970 frame nel tuo codice)
        if (this.melspectrogramBuffer.length > melspectrogramMaxLen) {
            float[][] trimmed = new float[melspectrogramMaxLen][];
            System.arraycopy(this.melspectrogramBuffer,
                    this.melspectrogramBuffer.length - melspectrogramMaxLen,
                    trimmed, 0, melspectrogramMaxLen);
            this.melspectrogramBuffer = trimmed;
        }
    }

    public void updateBuffers(float[] audiobuffer) {
        // 1. Uniamo l'audio attuale con quello che era rimasto indietro ("avanzo")
        float[] combined;
        if (raw_data_remainder != null && raw_data_remainder.length > 0) {
            combined = new float[raw_data_remainder.length + audiobuffer.length];
            System.arraycopy(raw_data_remainder, 0, combined, 0, raw_data_remainder.length);
            System.arraycopy(audiobuffer, 0, combined, raw_data_remainder.length, audiobuffer.length);
        } else {
            combined = audiobuffer;
        }

        // 2. Calcoliamo quanti blocchi da 1280 possiamo processare
        int totalLen = combined.length;
        int processableLen = (totalLen / 1280) * 1280;
        int leftoverLen = totalLen % 1280;

        if (processableLen > 0) {
            // Estraiamo la parte divisibile per 1280
            float[] toProcess = new float[processableLen];
            System.arraycopy(combined, 0, toProcess, 0, processableLen);

            // Carichiamo nel buffer circolare e aggiorniamo il contatore per ONNX
            this.bufferRawData(toProcess);
            this.accumulated_samples += processableLen;
        }

        // 3. Salviamo l'avanzo per il prossimo ciclo (anche se processableLen era 0)
        if (leftoverLen > 0) {
            raw_data_remainder = new float[leftoverLen];
            System.arraycopy(combined, totalLen - leftoverLen, raw_data_remainder, 0, leftoverLen);
        } else {
            raw_data_remainder = new float[0];
        }
    }

    private float[] getSamplesFromCircular(int nSamples) {
        float[] result = new float[nSamples];
        if (nSamples > totalSamplesInCircular) {
            nSamples = totalSamplesInCircular; // Non possiamo dare più di quello che abbiamo
        }

        // Calcoliamo la posizione di partenza nell'array circolare
        // circularWriteIndex è dove scriveremo il PROSSIMO campione,
        // quindi l'ultimo scritto è a circularWriteIndex - 1.
        int startReadIndex = (circularWriteIndex - nSamples + circularAudioBuffer.length) % circularAudioBuffer.length;

        int spaceAtEnd = circularAudioBuffer.length - startReadIndex;

        if (nSamples <= spaceAtEnd) {
            // I dati sono contigui
            System.arraycopy(circularAudioBuffer, startReadIndex, result, 0, nSamples);
        } else {
            // I dati sono spezzati
            System.arraycopy(circularAudioBuffer, startReadIndex, result, 0, spaceAtEnd);
            System.arraycopy(circularAudioBuffer, 0, result, spaceAtEnd, nSamples - spaceAtEnd);
        }
        return result;
    }

    public int processOnnxInference() {
        int processed = 0;

        if (this.accumulated_samples >= 1280) {
            // 1. Genera lo spettrogramma per i nuovi campioni
            this.streamingMelSpectrogram(this.accumulated_samples);

            // 2. Calcola quanti nuovi blocchi di embedding dobbiamo generare
            // Ogni blocco da 1280 campioni (80ms) produce esattamente 8 nuovi frame di spettrogramma
            int numNewEmbeddingWindows = this.accumulated_samples / 1280;

            for (int i = 0; i < numNewEmbeddingWindows; i++) {
                // Calcoliamo la posizione della finestra nel melspectrogramBuffer
                // Vogliamo le ultime 76 righe terminando all'offset corrente
                // L'offset si sposta di 8 righe per ogni blocco da 1280 campioni
                int endNdx = melspectrogramBuffer.length - (numNewEmbeddingWindows - 1 - i) * 8;
                int startNdx = endNdx - 76;

                if (startNdx >= 0) {
                    float[][][][] inputTensorData = prepareEmbeddingInput(startNdx, endNdx);

                    try {
                        float[][] newFeatures = modelRunner.generateEmbeddings(inputTensorData);
                        appendFeatures(newFeatures);
                    } catch (Exception e) {
                        Log.e("Model", "Errore generazione embedding", e);
                    }
                }
            }

            processed = this.accumulated_samples;
            this.accumulated_samples = 0;
        }

        trimFeatureBuffer();
        return processed;
    }

    private float[][][][] prepareEmbeddingInput(int start, int end) {
        // Il modello ONNX si aspetta [1][76][32][1]
        float[][][][] input = new float[1][76][32][1];

        for (int t = 0; t < 76; t++) {
            // Copiamo l'intera riga di 32 valori mel-spec
            // melspectrogramBuffer[start + t] è un float[32]
            // Lo copiamo in input[0][t][...]
            for (int m = 0; m < 32; m++) {
                input[0][t][m][0] = melspectrogramBuffer[start + t][m];
            }
            // Nota: Qui il System.arraycopy è difficile perché l'ultima dimensione è 1.
            // Se il modello ONNX accettasse [1][76][32], sarebbe istantaneo.
            // Manteniamo il ciclo interno ma abbiamo ottimizzato il calcolo degli indici.
        }
        return input;
    }

    // Metodo helper per concatenare le matrici di embedding
    private void appendFeatures(float[][] newFeatures) {
        if (featureBuffer == null) {
            featureBuffer = newFeatures;
            return;
        }

        float[][] updatedBuffer = new float[featureBuffer.length + newFeatures.length][featureBuffer[0].length];

        // Copia il vecchio buffer (riferimenti alle righe)
        System.arraycopy(featureBuffer, 0, updatedBuffer, 0, featureBuffer.length);

        // Copia i nuovi embedding (riferimenti alle righe)
        System.arraycopy(newFeatures, 0, updatedBuffer, featureBuffer.length, newFeatures.length);

        featureBuffer = updatedBuffer;
    }

    private void trimFeatureBuffer() {
        if (featureBuffer != null && featureBuffer.length > feature_buffer_max_len) {
            // Creiamo la nuova matrice (destinazione)
            float[][] trimmed = new float[feature_buffer_max_len][featureBuffer[0].length];

            // System.arraycopy(sorgente, posizione_sorgente, destinazione, posizione_destinazione, numero_elementi)
            // Copiamo i riferimenti alle righe (molto più veloce del copia-valori)
            System.arraycopy(
                    featureBuffer,
                    featureBuffer.length - feature_buffer_max_len,
                    trimmed,
                    0,
                    feature_buffer_max_len
            );

            featureBuffer = trimmed;
        }
    }

    public String predict_WakeWord(float[] audiobuffer) {
        // 1. Aggiorna sempre i buffer audio grezzi e i resti (per mantenere il sync)
        this.updateBuffers(audiobuffer);

        // 2. Controllo Energia (RMS)
        double rms = calculateRMS(audiobuffer);
        // Gestione della soglia con Hangover (coda di mantenimento)
        boolean isActive = false;
        if (rms > energyThreshold) {
            lastActiveTime = System.currentTimeMillis();
            isActive = true;
        } else if (System.currentTimeMillis() - lastActiveTime < HANGOVER_MS) {
            isActive = true;
        }

        String result = "0.00000"; // Valore di default (silenzio/nessun rilevamento)

        if (isActive) {

            // 3. Esegui la catena ONNX: Spettrogramma -> Embedding
            // processed_samples sarà 1280 (o multipli) se un blocco è stato completato
            int processed_samples = this.processOnnxInference();

            // 4. Se abbiamo nuovi embedding pronti, eseguiamo la classificazione finale
            if (processed_samples > 0) {
                // Estraiamo gli ultimi 16 frame di embedding (la finestra temporale della wake word)
                float[][][] features = this.getFeatures(16, -1);

                try {
                    result = modelRunner.predictWakeWord(features);
                } catch (OrtException e) {
                    Log.e("Model", "Errore predizione WakeWord", e);
                }
            }
        } else {
            // 5. SILENZIO PROLUNGATO: Scarichiamo l'accumulo per evitare che il
            // contatore cresca all'infinito senza mai processare nulla
            if (this.accumulated_samples >= 1280) {
                this.accumulated_samples = 0;

                // Opzionale: inseriamo un frame di "zero" per far scorrere il tempo nel modello
                // Questo evita che "Hey" (silenzio lungo) "Nugget" venga visto come "HeyNugget"
                pushSilentEmbedding();
            }
        }

        return result;
    }

    // Metodo helper per mantenere la coerenza temporale durante il silenzio
    private void pushSilentEmbedding() {
        float[][] silent = new float[1][96]; // Assumendo 96 sia la dimensione dell'embedding
        appendFeatures(silent);
        trimFeatureBuffer();
    }

    private double calculateRMS(float[] buffer) {
        double sum = 0;
        for (float s : buffer) sum += s * s;
        return Math.sqrt(sum / buffer.length);
    }

    public void close() {
        if (modelRunner != null) {
            modelRunner.close();
        }
    }
}