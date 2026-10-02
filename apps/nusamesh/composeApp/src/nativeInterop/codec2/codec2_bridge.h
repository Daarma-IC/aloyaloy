#ifndef NUSAMESH_CODEC2_BRIDGE_H
#define NUSAMESH_CODEC2_BRIDGE_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
uint32_t nusa_codec2_encoded_size(uint32_t sample_count);
uint32_t nusa_codec2_decoded_samples(const uint8_t *input, uint32_t input_size);
uint32_t nusa_codec2_encode(const int16_t *pcm, uint32_t sample_count, uint8_t *output, uint32_t capacity);
uint32_t nusa_codec2_decode(const uint8_t *input, uint32_t input_size, int16_t *pcm, uint32_t capacity);
#ifdef __cplusplus
}
#endif
#endif
