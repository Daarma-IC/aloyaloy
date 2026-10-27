#include "nusa_admission.h"
#include "nusa_appproto.h"
#include "nusa_frame.h"
#include "../lora/nusa_radio.h"
#include <esp_system.h>

namespace {
using namespace NusaAdmissionTrace;
constexpr uint8_t CONTROL=0x1b, STATUS=0x1c, TRACK=0x10;
constexpr size_t MAX_PACKET=512, CLIENTS=32, CACHE=64;
constexpr uint32_t TTL=60000;
uint64_t sessions[CLIENTS]={};
uint8_t owners[CLIENTS][8]={};
struct Entry { bool used; uint8_t client; uint64_t session,id; uint32_t at; Result result; Reason reason; Scope scope; uint32_t retry; };
Entry entries[CACHE]={};
portMUX_TYPE mux=portMUX_INITIALIZER_UNLOCKED;
struct Lock { Lock(){portENTER_CRITICAL(&mux);} ~Lock(){portEXIT_CRITICAL(&mux);} };
uint32_t epoch=0;
uint16_t u16(const uint8_t* p){return (uint16_t(p[0])<<8)|p[1];}
uint32_t u32(const uint8_t* p){return (uint32_t(u16(p))<<16)|u16(p+2);}
uint64_t u64(const uint8_t* p){return (uint64_t(u32(p))<<32)|u32(p+4);}
void put16(uint8_t* p,uint16_t v){p[0]=v>>8;p[1]=v;}
void put32(uint8_t* p,uint32_t v){put16(p,v>>16);put16(p+2,v);}
void put64(uint8_t* p,uint64_t v){put32(p,v>>32);put32(p+4,v);}
uint64_t fingerprint(const uint8_t* p,size_t n){uint64_t h=0xcbf29ce484222325ULL;
  for(size_t i=0;i<n;++i){uint8_t b=i==2?0:p[i];if(i==11)b&=~TRACK;h^=b;h*=0x100000001b3ULL;}return h;}
void respond(NusaAdmission::Reply reply,uint8_t client,const uint8_t* recipient,
             uint64_t session,uint64_t id,uint8_t kind,Result result,Reason reason,Scope scope,uint32_t retry=0){
  uint8_t out[75]={}; out[0]=2;out[1]=STATUS;out[2]=1;
  put64(out+3,nusaNowEpochMs());out[11]=1;put32(out+12,43);
  nusaNodePeerId(out+16);memcpy(out+24,recipient,8);
  uint8_t* p=out+32;p[0]=1;p[1]=kind;put64(p+2,session);put64(p+10,id);
  p[18]=result;p[19]=reason;p[20]=scope;p[21]=NusaRadio::healthy()?1:0;
  uint16_t depth=NusaRadio::queueDepth();put16(p+22,depth<TXQ_SLOTS?TXQ_SLOTS-depth:0);
  put16(p+24,MAX_PACKET);put32(p+26,(uint32_t)lroundf(NusaRadio::queueAirtimeMs()));
  put32(p+30,(uint32_t)(int32_t)lroundf(NusaRadio::budgetMs()));put32(p+34,retry);
  p[38]=NusaRadio::spreadingFactor();put32(p+39,epoch);
  if(reply)reply(out,sizeof(out),client);
}
bool validPacket(const uint8_t* p,size_t n){
  if(n<24 || (p[0]!=1&&p[0]!=2))return false;
  size_t h=p[0]==2?16:14;
  if(n<h+8)return false;
  uint32_t payload=p[0]==2?u32(p+12):u16(p+12);
  uint64_t expected=h+8+((p[11]&1)?8:0)+((p[11]&2)?64:0)+(uint64_t)payload;
  return expected==n || (expected+1==n&&p[n-1]==0);
}
}
void NusaAdmission::disconnected(uint8_t client){
  if(client>=CLIENTS)return;
  Lock lock;sessions[client]=0;for(auto& e:entries)if(e.used&&e.client==client)e.used=false;
}
void NusaAdmission::dispatch(const uint8_t* data,size_t len,uint8_t client,Handler handler,Reply reply){
  if(!data||len<12){if(handler)handler(data,len,client);return;}
  const bool control=data[1]==CONTROL;
  if(!control && !(data[11]&TRACK)){if(handler)handler(data,len,client);return;}
  len=nusaStripPadding(data,len);
  if(client>=CLIENTS)return;
  if(!validPacket(data,len)){
    if(!control&&len>=24&&(data[0]==1||data[0]==2)){
      uint64_t session;{Lock lock;session=sessions[client];if(!epoch)epoch=esp_random()|1;}
      uint8_t target[8];{Lock lock;memcpy(target,session?owners[client]:data+(data[0]==2?16:14),8);}
      respond(reply,client,target,session,fingerprint(data,len),1,REJECTED,FORMAT,NO_SCOPE);
    }
    return;
  }
  const uint8_t* sender=data+(data[0]==2?16:14);
  uint8_t target[8];{Lock lock;memcpy(target,sessions[client]?owners[client]:sender,8);}
  {Lock lock;if(!epoch){epoch=esp_random();if(!epoch)epoch=1;}}
  nusaLearnClock(u64(data+3));
  if(control){
    size_t off=(data[0]==2?16:14)+8+((data[11]&1)?8:0);
    uint8_t me[8];nusaNodePeerId(me);
    if(data[2]!=1||!(data[11]&1)||memcmp(sender+8,me,8)||len!=off+18||data[off]!=1)return;
    uint8_t kind=data[off+1];uint64_t session=u64(data+off+2),id=u64(data+off+10);
    if(!session)return;
    Entry found{};bool matches=false;
    {Lock lock;
      if(kind==1){
        if(sessions[client]!=session){sessions[client]=session;memcpy(owners[client],sender,8);for(auto& e:entries)if(e.used&&e.client==client)e.used=false;}
        matches=true;
      }else if(kind==2){
        matches=sessions[client]==session;
        if(matches)for(const auto& e:entries)if(e.used&&e.client==client&&e.session==session&&e.id==id&&millis()-e.at<TTL){found=e;break;}
      }else return;
    }
    if(kind==1)respond(reply,client,sender,session,0,0,UNKNOWN,NONE,NO_SCOPE);
    else respond(reply,client,sender,session,id,1,found.used?found.result:UNKNOWN,
      found.used?found.reason:(matches?NOT_FOUND:SESSION),found.used?found.scope:NO_SCOPE,found.retry);
    return;
  }
  uint8_t packet[MAX_PACKET];
  if(len>MAX_PACKET){uint64_t session;{Lock lock;session=sessions[client];}
    respond(reply,client,target,session,fingerprint(data,len),1,REJECTED,SIZE,NO_SCOPE);return;}
  memcpy(packet,data,len);packet[11]&=~TRACK;
  const uint64_t id=nusaMsgId(packet,len);
  uint64_t session=0;Entry cached{};int slot=-1;
  {Lock lock;session=sessions[client];
    for(size_t i=0;i<CACHE;++i){auto& e=entries[i];
      if(e.used&&millis()-e.at>=TTL)e.used=false;
      if(e.used&&e.id==id&&((e.client==client&&e.session==session)||e.result==ACCEPTED)){cached=e;break;}
      if(!e.used&&slot<0)slot=i;
    }
    if(session&&!cached.used&&slot>=0)entries[slot]={true,client,session,id,millis(),UNKNOWN,NO_OUTCOME,NO_SCOPE,0};
  }
  if(!session){respond(reply,client,target,0,id,1,UNKNOWN,SESSION,NO_SCOPE);return;}
  if(cached.used){respond(reply,client,target,session,id,1,cached.result,cached.reason,cached.scope,cached.retry);return;}
  if(slot<0){respond(reply,client,target,session,id,1,NusaAdmissionTrace::BUSY,CACHE_FULL,NO_SCOPE,1000);return;}
  Context trace;auto* previous=current;current=&trace;
  if(handler)handler(packet,len,client);
  current=previous;
  if(trace.localRoute){trace.result=trace.localSent?ACCEPTED:NusaAdmissionTrace::BUSY;trace.reason=trace.localSent?NONE:LOCAL_FAILED;trace.scope=LOCAL_NOTIFY;}
  if(trace.result!=ACCEPTED && trace.undo)trace.undo(trace.dedup,trace.id);
  {Lock lock;auto& e=entries[slot];if(e.used&&e.client==client&&e.session==session&&e.id==id){
    e.result=trace.result;e.reason=trace.reason;e.scope=trace.scope;e.retry=trace.retry;
  }}
  respond(reply,client,target,session,id,1,trace.result,trace.reason,trace.scope,trace.retry);
}
