#include <jni.h>
#include <string>
#include <vector>
#include "speex/include/speex/speex_preprocess.h"

// Puntatore globale allo stato del preprocessore
// In un'app reale, gestiscilo in modo più sicuro (es. in una classe C++)
static SpeexPreprocessState *st = nullptr;
static int frame_size = 256;
static int sample_rate = 16000;

extern "C" {

// Inizializzazione del preprocessore
JNIEXPORT void JNICALL
Java_com_suxsem_havoicecontrol_SpeexWrapper_initSpeex(JNIEnv *env, jobject instance) {
    if (st == nullptr) {
        st = speex_preprocess_state_init(frame_size, sample_rate);

		// 1. Denoise (Riduzione rumore)
		int denoise = 1;
		int noise_suppress = -25; // Più spinto di -25. Oltre -60 inizi a sentire artefatti metallici.
		speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_DENOISE, &denoise);
		speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_NOISE_SUPPRESS, &noise_suppress);

		// 2. AGC (Guadagno automatico)
		int agc = 1;
		float agc_level = 24000; // Valore target alto (range tipico 0-32767)
		int agc_max_gain = 25;   // Limita il guadagno massimo per non esagerare con il rumore di fondo
		speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_AGC, &agc);
		speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_AGC_LEVEL, &agc_level);
		speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_AGC_MAX_GAIN, &agc_max_gain);

		// 3. Dereverb (Soppressione dell'eco ambientale)
		int dereverb = 1;
		float dereverb_decay = 0.2f;
		float dereverb_level = 0.3f;
		speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_DEREVERB, &dereverb);
		speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_DEREVERB_DECAY, &dereverb_decay);
		speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_DEREVERB_LEVEL, &dereverb_level);

    }
}

// Elaborazione del buffer audio
JNIEXPORT void JNICALL
Java_com_suxsem_havoicecontrol_SpeexWrapper_processAudio(JNIEnv *env, jobject instance, jshortArray audioData) {
    if (st == nullptr) return;

    // Otteniamo il puntatore ai dati grezzi dell'array JNI
    jshort *buffer = env->GetShortArrayElements(audioData, nullptr);
    jsize len = env->GetArrayLength(audioData);
		
    // Elaborazione (speex_preprocess_run lavora in-place sull'array)
    if (len >= frame_size) {
        speex_preprocess_run(st, buffer);
    }

    // Rilasciamo l'array comunicando al sistema che abbiamo finito
    env->ReleaseShortArrayElements(audioData, buffer, 0);
}

// Distruzione dello stato (Chiamalo quando chiudi l'app)
JNIEXPORT void JNICALL
Java_com_suxsem_havoicecontrol_SpeexWrapper_destroySpeex(JNIEnv *env, jobject instance) {
    if (st != nullptr) {
        speex_preprocess_state_destroy(st);
        st = nullptr;
    }
}

} // extern "C"