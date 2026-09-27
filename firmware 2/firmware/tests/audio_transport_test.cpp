#include <cassert>
#include <iostream>
#include <thread>
#include "nusa_fragment.h"
#include <RadioLib.h>
uint32_t testMillis = 1;
static std::vector<uint8_t> received;
static void complete(uint64_t, uint8_t, const uint8_t* data, size_t len) {
    received.assign(data, data + len);
}
int main() {
    NusaRadio::begin(nullptr);
    std::vector<uint8_t> packet(700);
    for (size_t i=0; i<packet.size(); ++i) packet[i] = i % 251;
    uint8_t frame[20] = {};
    for(int i=0;i<21;i++) assert(NusaRadio::enqueue(frame,sizeof(frame),PRIO_GOSSIP));
    assert(NusaRadio::queueFree()==3);
    assert(NusaRadio::packetSlotsRequired(packet.size())==3);
    assert(!NusaRadio::canEnqueuePacket(packet.size(),PRIO_LOCAL));
    // Three fragment slots plus two reserved slots do not fit: reject ALL.
    assert(NusaFrag::sendPacket(packet.data(),packet.size(),3,PRIO_LOCAL)==0);
    assert(NusaRadio::queueDepth()==21);
    assert(NusaRadio::enqueue(frame,sizeof(frame),PRIO_ACK));
    assert(NusaRadio::enqueue(frame,sizeof(frame),PRIO_ACK));
    assert(NusaRadio::enqueue(frame,sizeof(frame),PRIO_ACK));
    assert(!NusaRadio::enqueue(frame,sizeof(frame),PRIO_ACK));
    assert(NusaRadio::queueDepth()==24);
    NusaRadio::begin(nullptr);
    assert(NusaRadio::canEnqueuePacket(packet.size(),PRIO_LOCAL));
    assert(NusaFrag::sendPacket(packet.data(),packet.size(),3,PRIO_LOCAL)==3);
    assert(NusaRadio::queueDepth()==3);
    for(int i=0;i<20;i++) { testMillis+=10000; NusaRadio::loop(); }
    assert(transmitted.size()==3);
    // Actual encoded radio frames reassemble exactly, even out of order/duplicated.
    NusaFrag::begin(complete);
    for(int i: {2,0,0,1}) {
        NusaFrame f;
        assert(nusaFrameDecode(transmitted[i].data(),transmitted[i].size(),f));
        NusaFrag::feed(f);
    }
    assert(received==packet);
    received.clear();
    NusaFrag::begin(complete);
    NusaFrame f;
    assert(nusaFrameDecode(transmitted[0].data(),transmitted[0].size(),f));
    NusaFrag::feed(f);
    testMillis+=120000; NusaFrag::loop();
    assert(NusaFrag::activeSlots()==1); // Audio must survive the former 60s timeout.
    testMillis+=REASM_TIMEOUT_MS; NusaFrag::loop();
    assert(NusaFrag::activeSlots()==0);
    assert(received.empty());

    // Fragmen non-terakhir yang pendek harus ditolak agar tidak meninggalkan
    // lubang berisi RAM lama di tengah hasil reassembly.
    NusaFrag::begin(complete);
    uint8_t shortData[10] = {};
    NusaFrame malformed{NUSA_FRAME_VERSION,0,3,99,0,2,shortData,10};
    NusaFrag::feed(malformed);
    assert(NusaFrag::activeSlots()==0);

    // Slot aktif tidak boleh digusur oleh paket keempat yang baru datang.
    uint8_t fullData[NUSA_FRAG_MAX_DATA] = {};
    for(uint64_t id=1; id<=REASM_SLOTS+1; ++id) {
        NusaFrame partial{NUSA_FRAME_VERSION,0,3,100+id,0,2,
                          fullData,NUSA_FRAG_MAX_DATA};
        NusaFrag::feed(partial);
    }
    assert(NusaFrag::activeSlots()==REASM_SLOTS);
    assert(NusaFrag::droppedIncomplete()==1);

    std::vector<uint8_t> oversized(REASM_MAX_BYTES+1);
    assert(NusaFrag::sendPacket(oversized.data(),oversized.size(),3,PRIO_LOCAL)==0);
    // Two concurrent BLE producers cannot enqueue half of their packets.
    NusaRadio::begin(nullptr);
    std::vector<uint8_t> large(REASM_MAX_BYTES);
    uint8_t a=0,b=0;
    std::thread one([&]{a=NusaFrag::sendPacket(large.data(),large.size(),3,PRIO_LOCAL);});
    std::thread two([&]{b=NusaFrag::sendPacket(large.data(),large.size(),3,PRIO_LOCAL);});
    one.join(); two.join();
    assert(a==11 && b==11 && NusaRadio::queueDepth()==22);
    assert(NusaFrag::sendPacket(packet.data(),packet.size(),3,PRIO_LOCAL)==0);
    std::cout << "PASS: atomic queue admission, concurrent producers, no eviction, radio frame round-trip, timeout, bounds\n";
}
