package com.suxsem.havoicecontrol

class SpeexWrapper {

    // Carica la libreria compilata (libspeex-lib.so)
    init {
        System.loadLibrary("speex-lib")
    }

    // Dichiarazione dei metodi nativi
    external fun initSpeex()
    external fun processAudio(audioData: ShortArray): Boolean
    external fun destroySpeex()

}