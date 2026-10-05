#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "codec2_bridge.h"

/* Tipis di atas codec2_bridge.c (dipakai bersama iOS) supaya format & mode voice note selalu sama. */

JNIEXPORT jbyteArray JNICALL
Java_id_nusamesh_app_media_AndroidCodec2_encode(JNIEnv *env, jobject ignored, jshortArray input) {
    (void)ignored;
    const jsize sample_count = (*env)->GetArrayLength(env, input);
    const uint32_t output_size = nusa_codec2_encoded_size((uint32_t)sample_count);
    if (sample_count <= 0 || output_size == 0) return NULL;
    jshort *pcm = (*env)->GetShortArrayElements(env, input, NULL);
    uint8_t *output = malloc(output_size);
    if (!pcm || !output) {
        if (pcm) (*env)->ReleaseShortArrayElements(env, input, pcm, JNI_ABORT);
        free(output); return NULL;
    }
    const uint32_t written = nusa_codec2_encode((const int16_t *)pcm, (uint32_t)sample_count, output, output_size);
    (*env)->ReleaseShortArrayElements(env, input, pcm, JNI_ABORT);
    jbyteArray result = written == output_size ? (*env)->NewByteArray(env, (jsize)output_size) : NULL;
    if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)output_size, (const jbyte *)output);
    free(output);
    return result;
}

JNIEXPORT jshortArray JNICALL
Java_id_nusamesh_app_media_AndroidCodec2_decode(JNIEnv *env, jobject ignored, jbyteArray input) {
    (void)ignored;
    const jsize input_size = (*env)->GetArrayLength(env, input);
    jbyte *encoded = (*env)->GetByteArrayElements(env, input, NULL);
    if (!encoded) return NULL;
    const uint32_t sample_count = nusa_codec2_decoded_samples((const uint8_t *)encoded, (uint32_t)input_size);
    int16_t *pcm = sample_count ? malloc((size_t)sample_count * sizeof(int16_t)) : NULL;
    jshortArray result = NULL;
    if (pcm && nusa_codec2_decode((const uint8_t *)encoded, (uint32_t)input_size, pcm, sample_count) == sample_count) {
        result = (*env)->NewShortArray(env, (jsize)sample_count);
        if (result) (*env)->SetShortArrayRegion(env, result, 0, (jsize)sample_count, pcm);
    }
    (*env)->ReleaseByteArrayElements(env, input, encoded, JNI_ABORT);
    free(pcm);
    return result;
}
