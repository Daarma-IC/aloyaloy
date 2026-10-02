#include "codec2_bridge.h"
#include "codec2.h"
#include <stdlib.h>
#include <string.h>

#define NUSA_MODE CODEC2_MODE_3200
#define HEADER 12
static void put32(uint8_t *p,uint32_t v){p[0]=v>>24;p[1]=v>>16;p[2]=v>>8;p[3]=v;}
static uint32_t get32(const uint8_t*p){return((uint32_t)p[0]<<24)|((uint32_t)p[1]<<16)|((uint32_t)p[2]<<8)|p[3];}

uint32_t nusa_codec2_encoded_size(uint32_t samples){
  struct CODEC2*c=codec2_create(NUSA_MODE);if(!c)return 0;
  uint32_t frames=(samples+codec2_samples_per_frame(c)-1)/codec2_samples_per_frame(c);
  uint32_t size=HEADER+frames*codec2_bytes_per_frame(c);codec2_destroy(c);return size;
}
uint32_t nusa_codec2_decoded_samples(const uint8_t*in,uint32_t size){
  if(!in||size<HEADER||memcmp(in,"NC2\1",4))return 0;
  uint32_t samples=get32(in+4),frames=get32(in+8);
  struct CODEC2*c=codec2_create(NUSA_MODE);if(!c)return 0;
  uint32_t expected=HEADER+frames*codec2_bytes_per_frame(c),max=frames*codec2_samples_per_frame(c);
  codec2_destroy(c);return(frames&&size==expected&&samples<=max)?samples:0;
}
uint32_t nusa_codec2_encode(const int16_t*pcm,uint32_t samples,uint8_t*out,uint32_t cap){
  if(!pcm||!out)return 0;struct CODEC2*c=codec2_create(NUSA_MODE);if(!c)return 0;
  int spf=codec2_samples_per_frame(c),bpf=codec2_bytes_per_frame(c);uint32_t frames=(samples+spf-1)/spf,need=HEADER+frames*bpf;
  if(cap<need){codec2_destroy(c);return 0;}memcpy(out,"NC2\1",4);put32(out+4,samples);put32(out+8,frames);
  int16_t*frame=calloc(spf,sizeof(int16_t));if(!frame){codec2_destroy(c);return 0;}
  for(uint32_t i=0;i<frames;i++){uint32_t start=i*spf,count=samples-start<(uint32_t)spf?samples-start:(uint32_t)spf;memset(frame,0,spf*sizeof(int16_t));memcpy(frame,pcm+start,count*sizeof(int16_t));codec2_encode(c,out+HEADER+i*bpf,frame);}
  free(frame);codec2_destroy(c);return need;
}
uint32_t nusa_codec2_decode(const uint8_t*in,uint32_t size,int16_t*pcm,uint32_t cap){
  uint32_t samples=nusa_codec2_decoded_samples(in,size);if(!samples||!pcm||cap<samples)return 0;
  struct CODEC2*c=codec2_create(NUSA_MODE);if(!c)return 0;int spf=codec2_samples_per_frame(c),bpf=codec2_bytes_per_frame(c);uint32_t frames=get32(in+8);int16_t*frame=calloc(spf,sizeof(int16_t));if(!frame){codec2_destroy(c);return 0;}
  for(uint32_t i=0;i<frames;i++){codec2_decode(c,frame,in+HEADER+i*bpf);uint32_t start=i*spf,count=samples-start<(uint32_t)spf?samples-start:(uint32_t)spf;memcpy(pcm+start,frame,count*sizeof(int16_t));}
  free(frame);codec2_destroy(c);return samples;
}
