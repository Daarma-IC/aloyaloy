#pragma once
#include <stdint.h>
namespace NusaAdmissionTrace {
enum Result : uint8_t { UNKNOWN=0, ACCEPTED=1, BUSY=2, REJECTED=3 };
enum Reason : uint8_t { NONE=0, RATE=1, QUEUE_FULL=2, SIZE=3, FORMAT=4, DUPLICATE=5,
  LOCAL_FAILED=6, NO_OUTCOME=7, SESSION=8, NOT_FOUND=9, CACHE_FULL=10 };
enum Scope : uint8_t { NO_SCOPE=0, RADIO_QUEUE=1, LOCAL_NOTIFY=2 };
struct Context {
  Result result=UNKNOWN; Reason reason=NO_OUTCOME; Scope scope=NO_SCOPE;
  uint32_t retry=0; bool localRoute=false, localSent=false;
  void* dedup=nullptr; uint64_t id=0; void (*undo)(void*,uint64_t)=nullptr;
};
// A radio callback on another task cannot acknowledge this ingress.
inline thread_local Context* current=nullptr;
inline void radio(bool accepted, Reason reason=QUEUE_FULL) {
  if(!current)return;
  if(accepted){current->result=ACCEPTED;current->reason=NONE;current->scope=RADIO_QUEUE;}
  else if(current->result!=ACCEPTED){current->result=reason==SIZE||reason==FORMAT?REJECTED:BUSY;current->reason=reason;}
}
inline void rate(uint32_t retry) { if(current){current->result=BUSY;current->reason=RATE;current->retry=retry;} }
inline void duplicate() { if(current){current->result=UNKNOWN;current->reason=DUPLICATE;} }
}
