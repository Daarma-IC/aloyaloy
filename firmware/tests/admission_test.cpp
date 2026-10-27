#include <cassert>
#include <iostream>
#include <thread>
#include "src/protocol/nusa_admission.h"
#include "src/protocol/nusa_appproto.h"
#include "nusa_radio.h"
using namespace NusaAdmissionTrace;
uint32_t testMillis=100;
int64_t g_epochOffsetMs=1700000000000LL;
bool g_clockSynced=true;
static NusaDedup dedup;
static std::vector<uint8_t> answer;
static bool allow=true,local=false,notify=true;
static unsigned called=0;
static void put64(uint8_t* p,uint64_t n){for(int i=0;i<8;++i)p[i]=n>>(56-8*i);}
static bool reply(const uint8_t* p,size_t n,uint8_t){answer.assign(p,p+n);return true;}
static void handler(const uint8_t* p,size_t n,uint8_t){
  ++called;assert(!(p[11]&0x10));
  auto id=nusaMsgId(p,n);if(!dedup.admit(id))return;
  if(local){current->localSent=notify;current->localRoute=true;return;}
  if(!allow){rate(3334);return;}
  NusaRadio::enqueuePacket(p,n,1,PRIO_LOCAL);
}
static std::vector<uint8_t> packet(size_t n=40,int serial=1){
  std::vector<uint8_t> p(n,0);p[0]=2;p[1]=0x22;p[2]=3;p[11]=0x10;
  put64(p.data()+3,1700000000000ULL+serial);p[15]=n-24;
  if(n-24>255)p[14]=(n-24)>>8;
  p[16]=42;return p;
}
static void control(uint8_t client,uint64_t session,bool query=false,uint64_t id=0){
  auto p=packet(50);p[1]=0x1b;p[2]=1;p[11]=1;p[15]=18;
  nusaNodePeerId(p.data()+24);p[32]=1;p[33]=query?2:1;
  put64(p.data()+34,session);put64(p.data()+42,id);
  NusaAdmission::dispatch(p.data(),p.size(),client,handler,reply);
}
static uint64_t idOf(std::vector<uint8_t> p){p[11]&=~0x10;return nusaMsgId(p.data(),p.size());}
int main(){
  NusaRadio::begin(nullptr);dedup.begin();
  control(0,123);assert(answer.size()==75&&answer[33]==0&&called==0);
  uint8_t health[64];size_t healthSize=nusaBuildLoraHealth(health,64,0,false,0,0,1,3,7,false);
  assert(healthSize==32&&health[31]==1);
  auto p=packet();auto id=idOf(p);
  NusaAdmission::dispatch(p.data(),p.size(),0,handler,reply);
  assert(answer[50]==ACCEPTED&&answer[52]==RADIO_QUEUE&&NusaRadio::queueDepth()==1&&called==1);
  // Same request and lost response query never enqueue or consume rate twice.
  NusaAdmission::dispatch(p.data(),p.size(),0,handler,reply);assert(called==1&&NusaRadio::queueDepth()==1);
  control(0,123,true,id);assert(answer[50]==ACCEPTED&&called==1);
  control(1,456);NusaAdmission::dispatch(p.data(),p.size(),1,handler,reply);
  assert(answer[50]==ACCEPTED&&called==1); // Two HPs share the known accepted packet.
  // Rate rejection rolls back only the new dedup reservation.
  allow=false;auto limited=packet(40,2);auto limitedId=idOf(limited);
  NusaAdmission::dispatch(limited.data(),limited.size(),0,handler,reply);
  assert(answer[50]==BUSY&&answer[51]==RATE&&!dedup.seen(limitedId));
  allow=true;control(0,124);NusaAdmission::dispatch(limited.data(),limited.size(),0,handler,reply);
  assert(answer[50]==ACCEPTED&&NusaRadio::queueDepth()==2);
  // Atomic batch admission: no partial RF enqueue when capacity is insufficient.
  uint8_t raw[12]={};while(NusaRadio::queueDepth()<20)assert(NusaRadio::enqueue(raw,12,PRIO_ACK));
  auto big=packet(500,3);auto depth=NusaRadio::queueDepth();
  NusaAdmission::dispatch(big.data(),big.size(),0,handler,reply);
  assert(answer[50]==BUSY&&answer[51]==QUEUE_FULL&&NusaRadio::queueDepth()==depth&&!dedup.seen(idOf(big)));
  auto oversized=packet(513,4);NusaAdmission::dispatch(oversized.data(),oversized.size(),0,handler,reply);
  assert(answer[50]==REJECTED&&answer[51]==SIZE);
  auto bad=packet(40,5);bad[15]=99;NusaAdmission::dispatch(bad.data(),bad.size(),0,handler,reply);
  assert(answer[50]==REJECTED&&answer[51]==FORMAT);
  // Local routing requires successful notify admission; it does not claim RF.
  local=true;auto l=packet(40,6);l[16]=99;NusaAdmission::dispatch(l.data(),l.size(),0,handler,reply);
  assert(answer[50]==ACCEPTED&&answer[52]==LOCAL_NOTIFY&&NusaRadio::queueDepth()==depth);
  assert(answer[24]==42); // Receipt targets bridge/session owner, not relayed packet sender 99.
  notify=false;auto fail=packet(40,7);NusaAdmission::dispatch(fail.data(),fail.size(),0,handler,reply);
  assert(answer[50]==BUSY&&answer[51]==LOCAL_FAILED);
  local=false;
  // Background radio work cannot satisfy another thread's ingress context.
  Context context;current=&context;
  std::thread other([]{radio(true);});other.join();assert(context.result==UNKNOWN);current=nullptr;
  NusaAdmission::disconnected(0);control(0,124,true,id);
  assert(answer[50]==UNKNOWN&&answer[51]==SESSION);
  control(0,125);testMillis+=60001;control(0,125,true,id);
  assert(answer[50]==UNKNOWN&&answer[51]==NOT_FOUND);
  allow=false;
  for(int i=0;i<70;++i){auto request=packet(40,100+i);NusaAdmission::dispatch(request.data(),request.size(),0,handler,reply);}
  assert(answer[50]==BUSY&&answer[51]==CACHE_FULL&&NusaRadio::queueDepth()==depth);
  NusaDedup concurrent;concurrent.begin();
  std::thread a([&]{for(int i=1;i<=32;++i)assert(concurrent.admit(i));});
  std::thread b([&]{for(int i=33;i<=64;++i)assert(concurrent.admit(i));});a.join();b.join();
  for(int i=1;i<=64;++i)assert(concurrent.seen(i));
  std::cout<<"PASS: capability, accepted/atomic busy, dedup rollback, cached query, two clients, local notify, bad size/format, TLS isolation, reconnect/expiry\n";
}
