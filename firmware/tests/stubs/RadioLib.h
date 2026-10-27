#pragma once
#include "SPI.h"
#include <vector>
#define RADIOLIB_ERR_NONE 0
#define RADIOLIB_CHANNEL_FREE -15
#define RADIOLIB_ERR_CRC_MISMATCH -7
#define RADIOLIB_PREAMBLE_DETECTED -14
#define RADIOLIB_ERR_UNKNOWN -1
#define RADIOLIB_NC -1
inline std::vector<std::vector<uint8_t>> transmitted;
inline bool fakeAutoTxDone = true;
inline bool fakeTxFailure = false;
struct SX1276;
inline std::vector<SX1276*>& fakeRadios() { static std::vector<SX1276*> radios; return radios; }
struct Module { int cs; Module(int pin,int,int,int,SPIClass&,SPISettings&) : cs(pin) {} };
struct SX1276 {
    int cs;
    SX1276(Module* module) : cs(module->cs) { fakeRadios().push_back(this); }
    void (*irq)() = nullptr;
    int receiveStarts = 0;
    int cadResult = RADIOLIB_CHANNEL_FREE;
    int standbyResult = 0;
    int txStartResult = 0;
    bool transmitting = false, listening = false;
    std::vector<uint8_t> rxData;
    int begin(float,float,int,int,int,int,int) { return 0; }
    void setCurrentLimit(int) {}
    int setCRC(bool) { return 0; }
    void setPacketReceivedAction(void (*fn)()) { irq=fn; }
    void setPacketSentAction(void (*fn)()) { irq=fn; }
    int standby() { if (standbyResult) return standbyResult; transmitting = false; listening = false; return 0; }
    unsigned long getTimeOnAir(size_t) { return 400000; }
    int setSpreadingFactor(uint8_t) { return 0; }
    int startReceive() { listening = true; ++receiveStarts; return 0; }
    size_t getPacketLength() { return rxData.size(); }
    int readData(uint8_t* out,size_t size) { memcpy(out,rxData.data(),size); rxData.clear(); return 0; }
    float getRSSI() { return -80; }
    float getSNR() { return 10; }
    int scanChannel() { return cadResult; }
    int startTransmit(uint8_t* data,size_t size) {
        if(fakeTxFailure || txStartResult) return txStartResult ? txStartResult : -10;
        transmitting = true; listening = false;
        transmitted.emplace_back(data,data+size);
        if(fakeAutoTxDone && irq) irq();
        return 0;
    }
    int finishTransmit() { transmitting = false; return 0; }
};
