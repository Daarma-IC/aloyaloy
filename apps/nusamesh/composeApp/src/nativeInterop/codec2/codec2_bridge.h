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
/* Persistent raw-frame session. Separate instances for encoder and decoder.
 * No NC2 header here; callers bundle whole codec frames and carry sample counts. */
struct nusa_codec2_stream;
struct nusa_codec2_stream *nusa_codec2_stream_create(uint8_t version);
void nusa_codec2_stream_destroy(struct nusa_codec2_stream *stream);
uint32_t nusa_codec2_stream_encode(struct nusa_codec2_stream *stream, const int16_t *pcm,
                                 uint32_t samples, uint8_t *out, uint32_t capacity);
uint32_t nusa_codec2_stream_decode(struct nusa_codec2_stream *stream, const uint8_t *data,
                                 uint32_t bytes, int16_t *pcm, uint32_t capacity);
#ifdef __cplusplus
}
#endif
#endif
