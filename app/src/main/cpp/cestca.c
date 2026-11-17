// Pont entre l'application Java et le moteur Whisper (whisper.cpp)
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "whisper.h"

JNIEXPORT jlong JNICALL
Java_fr_remis_cestca_Whisper_charger(JNIEnv *env, jclass cls, jstring chemin) {
    const char *c = (*env)->GetStringUTFChars(env, chemin, NULL);
    struct whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(c, cp);
    (*env)->ReleaseStringUTFChars(env, chemin, c);
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT jbyteArray JNICALL
Java_fr_remis_cestca_Whisper_transcrire(JNIEnv *env, jclass cls, jlong ptr, jfloatArray son,
                                        jint threads, jint audioCtx) {
    struct whisper_context *ctx = (struct whisper_context *) (intptr_t) ptr;
    jsize n = (*env)->GetArrayLength(env, son);
    jfloat *data = (*env)->GetFloatArrayElements(env, son, NULL);

    struct whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.language = "fr";
    p.translate = false;
    p.no_context = true;
    p.no_timestamps = true;
    p.single_segment = false;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.suppress_blank = true;
    p.audio_ctx = audioCtx;

    int r = whisper_full(ctx, p, data, n);
    (*env)->ReleaseFloatArrayElements(env, son, data, JNI_ABORT);

    size_t cap = 1024, len = 0;
    char *buf = (char *) malloc(cap);
    if (r == 0) {
        int ns = whisper_full_n_segments(ctx);
        for (int i = 0; i < ns; i++) {
            const char *t = whisper_full_get_segment_text(ctx, i);
            size_t l = strlen(t);
            if (len + l + 2 > cap) {
                while (len + l + 2 > cap) cap *= 2;
                buf = (char *) realloc(buf, cap);
            }
            memcpy(buf + len, t, l);
            len += l;
            buf[len++] = ' ';
        }
    }
    jbyteArray res = (*env)->NewByteArray(env, (jsize) len);
    (*env)->SetByteArrayRegion(env, res, 0, (jsize) len, (const jbyte *) buf);
    free(buf);
    return res;
}

JNIEXPORT void JNICALL
Java_fr_remis_cestca_Whisper_liberer(JNIEnv *env, jclass cls, jlong ptr) {
    if (ptr) whisper_free((struct whisper_context *) (intptr_t) ptr);
}
