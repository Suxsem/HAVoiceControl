cmake_minimum_required(VERSION 3.14)
project("speex-wrapper" LANGUAGES C CXX)

# Elenco dei file essenziali per il pre-processing e resample
set(SPEEX_SOURCES
    speex/libspeexdsp/preprocess.c
    speex/libspeexdsp/resample.c
    speex/libspeexdsp/filterbank.c
    speex/libspeexdsp/fftwrap.c
    speex/libspeexdsp/mdf.c
    speex/libspeexdsp/kiss_fft.c
    speex/libspeexdsp/kiss_fftr.c
    speex/libspeexdsp/buffer.c
    speex/libspeexdsp/jitter.c
    speex/libspeexdsp/scal.c
    speex/libspeexdsp/smallft.c
)
add_library(speex-lib SHARED native-lib.cpp ${SPEEX_SOURCES})

# Qui puntiamo alle cartelle che contengono gli header
target_include_directories(speex-lib PRIVATE 
    speex/include
    speex/include/speex
    speex/libspeexdsp
)

target_compile_definitions(speex-lib PRIVATE
    FIXED_POINT=1
    EXPORT=
    USE_KISS_FFT=1
    HAVE_STDINT_H=1
)

find_library(log-lib log)
target_link_libraries(speex-lib ${log-lib})

target_compile_options(speex-lib PRIVATE -UHAVE_CONFIG_H)

# Forza l'allineamento a 16KB per la compatibilità con Android 15+
set_target_properties(speex-lib PROPERTIES 
    LINK_FLAGS "-Wl,-z,max-page-size=16384"
)