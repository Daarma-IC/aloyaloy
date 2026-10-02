#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "codec2.h"

#define NUSA_MODE CODEC2_MODE_3200
#define NUSA_HEADER_SIZE 12

static void put_u32(uint8_t *p, uint32_t v) {
    p[0]=(uint8_t)(v>>24); p[1]=(uint8_t)(v>>16); p[2]=(uint8_t)(v>>8); p[3]=(uint8_t)v;
}
static uint32_t get_u32(const uint8_t *p) {
    return ((uint32_t)p[0]<<24)|((uint32_t)p[1]<<16)|((uint32_t)p[2]<<8)|p[3];
}

JNIEXPORT jbyteArray JNICALL
Java_id_nusamesh_app_media_AndroidCodec2_encode(JNIEnv *env, jobject ignored, jshortArray input) {
    (void)ignored;
    struct CODEC2 *codec = codec2_create(NUSA_MODE);
    if (!codec) return NULL;
    const int samples_per_frame = codec2_samples_per_frame(codec);
    const int bytes_per_frame = codec2_bytes_per_frame(codec);
    const jsize sample_count = (*env)->GetArrayLength(env, input);
    const uint32_t frame_count = (sample_count + samples_per_frame - 1) / samples_per_frame;
    const size_t output_size = NUSA_HEADER_SIZE + (size_t)frame_count * bytes_per_frame;
    uint8_t *output = calloc(output_size, 1);
    int16_t *pcm = calloc(samples_per_frame, sizeof(int16_t));
    if (!output || !pcm) { free(output); free(pcm); codec2_destroy(codec); return NULL; }
    memcpy(output, "NC2\1", 4);
    put_u32(output + 4, (uint32_t)sample_count);
    put_u32(output + 8, frame_count);
    for (uint32_t i=0; i<frame_count; ++i) {
        const int start = (int)i * samples_per_frame;
        const int remaining = sample_count - start;
        const int count = remaining < samples_per_frame ? remaining : samples_per_frame;
        memset(pcm, 0, samples_per_frame * sizeof(int16_t));
        if (count > 0) (*env)->GetShortArrayRegion(env, input, start, count, pcm);
        codec2_encode(codec, output + NUSA_HEADER_SIZE + (size_t)i * bytes_per_frame, pcm);
    }
    jbyteArray result = (*env)->NewByteArray(env, (jsize)output_size);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)output_size, (jbyte*)output);
    free(output); free(pcm); codec2_destroy(codec);
    return result;
}

JNIEXPORT jshortArray JNICALL
Java_id_nusamesh_app_media_AndroidCodec2_decode(JNIEnv *env, jobject ignored, jbyteArray input) {
    (void)ignored;
    const jsize input_size = (*env)->GetArrayLength(env, input);
    if (input_size < NUSA_HEADER_SIZE) return NULL;
    uint8_t header[NUSA_HEADER_SIZE];
    (*env)->GetByteArrayRegion(env, input, 0, NUSA_HEADER_SIZE, (jbyte*)header);
    if (memcmp(header, "NC2\1", 4) != 0) return NULL;
    struct CODEC2 *codec = codec2_create(NUSA_MODE);
    if (!codec) return NULL;
    const int samples_per_frame = codec2_samples_per_frame(codec);
    const int bytes_per_frame = codec2_bytes_per_frame(codec);
    const uint32_t sample_count = get_u32(header + 4);
    const uint32_t frame_count = get_u32(header + 8);
    const uint64_t expected = NUSA_HEADER_SIZE + (uint64_t)frame_count * bytes_per_frame;
    if (!frame_count || sample_count > frame_count*(uint32_t)samples_per_frame || expected != (uint64_t)input_size) {
        codec2_destroy(codec); return NULL;
    }
    jbyte *encoded = (*env)->GetByteArrayElements(env, input, NULL);
    jshortArray result = (*env)->NewShortArray(env, (jsize)sample_count);
    int16_t *pcm = calloc(samples_per_frame, sizeof(int16_t));
    if (!encoded || !result || !pcm) {
        if (encoded) (*env)->ReleaseByteArrayElements(env, input, encoded, JNI_ABORT);
        free(pcm); codec2_destroy(codec); return NULL;
    }
    for (uint32_t i=0; i<frame_count; ++i) {
        codec2_decode(codec, pcm, (uint8_t*)encoded + NUSA_HEADER_SIZE + (size_t)i * bytes_per_frame);
        const uint32_t start = i * samples_per_frame;
        const int count = sample_count-start < (uint32_t)samples_per_frame ? (int)(sample_count-start) : samples_per_frame;
        if (count > 0) (*env)->SetShortArrayRegion(env, result, (jsize)start, count, pcm);
    }
    (*env)->ReleaseByteArrayElements(env, input, encoded, JNI_ABORT);
    free(pcm); codec2_destroy(codec);
    return result;
}
