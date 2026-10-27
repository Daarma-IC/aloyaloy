#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "codec2_bridge.h"
#include "codec2.h"
static void put32(uint8_t *p, uint32_t n) { p[0]=n>>24;p[1]=n>>16;p[2]=n>>8;p[3]=n; }
int main(void) {
  const uint32_t counts[] = {1,319,320,321,8000,80000};
  assert(nusa_codec2_encoded_size(0)==0);
  for(unsigned i=0;i<sizeof(counts)/sizeof(counts[0]);++i) {
    uint32_t n=counts[i], size=nusa_codec2_encoded_size(n);
    assert(size==12+((n+319)/320)*6);
    int16_t *pcm=malloc((n+1)*sizeof(int16_t)), *out=malloc((n+1)*sizeof(int16_t));
    uint8_t *encoded=malloc(size+6);
    for(uint32_t k=0;k<n;++k) pcm[k]=(int16_t)(5000*sin(k*2*3.141592653589793*440/8000));
    out[n]=12345;
    assert(nusa_codec2_encode(pcm,n,encoded,size-1)==0);
    assert(nusa_codec2_encode(pcm,n,encoded,size)==size);
    assert(nusa_codec2_decoded_samples(encoded,size)==n);
    assert(nusa_codec2_decode(encoded,size,out,n-1)==0);
    assert(nusa_codec2_decode(encoded,size,out,n)==n && out[n]==12345);
    assert(nusa_codec2_decoded_samples(encoded,size-1)==0);
    assert(nusa_codec2_decoded_samples(encoded,size+6)==0);
    put32(encoded+4,0xffffffff); assert(nusa_codec2_decoded_samples(encoded,size)==0);
    free(pcm);free(out);free(encoded);
  }
  // Predictor state must span arbitrary transport bundles: bitstream equals one-shot encoding.
  {
    int16_t pcm[8000]; uint8_t whole[162], streamed[150];
    for(unsigned i=0;i<8000;++i)pcm[i]=(int16_t)(6000*sin(i*2*3.141592653589793*220/8000));
    assert(nusa_codec2_encode(pcm,8000,whole,sizeof(whole))==162);
    struct nusa_codec2_stream *enc=nusa_codec2_stream_create(2),*dec=nusa_codec2_stream_create(2);
    assert(enc&&dec&&!nusa_codec2_stream_create(99));
    assert(nusa_codec2_stream_encode(enc,pcm,319,streamed,150)==0);
    assert(nusa_codec2_stream_encode(enc,pcm,5120,streamed,95)==0);
    assert(nusa_codec2_stream_encode(enc,pcm,5120,streamed,96)==96);
    assert(nusa_codec2_stream_encode(enc,pcm+5120,2880,streamed+96,54)==54);
    assert(!memcmp(whole+12,streamed,150));
    int16_t out[8001];out[8000]=12345;
    assert(nusa_codec2_stream_decode(dec,streamed,95,out,8000)==0);
    assert(nusa_codec2_stream_decode(dec,streamed,96,out,5119)==0);
    assert(nusa_codec2_stream_decode(dec,streamed,96,out,5120)==5120);
    assert(nusa_codec2_stream_decode(dec,streamed+96,54,out+5120,2880)==2880);
    assert(out[8000]==12345);
    nusa_codec2_stream_destroy(enc);nusa_codec2_stream_destroy(dec);nusa_codec2_stream_destroy(NULL);
  }
  // Legacy NC2 v1 uses the real 3200 codec, not a fake frame stub.
  struct CODEC2 *c=codec2_create(CODEC2_MODE_3200); assert(c);
  uint8_t old[20]={'N','C','2',1}; int16_t pcm[160]={0}, decoded[160];
  put32(old+4,160);put32(old+8,1);codec2_encode(c,old+12,pcm);codec2_destroy(c);
  assert(nusa_codec2_decode(old,sizeof(old),decoded,160)==160);
  puts("PASS: real Codec2 1200 roundtrip/frame boundaries, capacity checks, corrupt headers, legacy 3200 decode");
}
