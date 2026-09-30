#pragma once
#include "SPI.h"
#include <vector>
#define RADIOLIB_ERR_NONE 0
#define RADIOLIB_ERR_CRC_MISMATCH -1
#define RADIOLIB_PREAMBLE_DETECTED 1
inline std::vector<std::vector<uint8_t>> transmitted;
struct Module { Module(int,int,int,int,SPIClass&,SPISettings&) {} };
struct SX1276 {
    SX1276(Module*) {}
    void (*irq)() = nullptr;
    int begin(float,float,int,int,int,int,int) { return 0; }
    void setCurrentLimit(int) {}
    void setCRC(bool) {}
    void setPacketReceivedAction(void (*fn)()) { irq=fn; }
    int startReceive() { return 0; }
    size_t getPacketLength() { return 0; }
    int readData(uint8_t*,size_t) { return 0; }
    float getRSSI() { return -80; }
    float getSNR() { return 10; }
    int scanChannel() { return 0; }
    int startTransmit(uint8_t* data,size_t size) {
        transmitted.emplace_back(data,data+size);
        if(irq) irq();
        return 0;
    }
    void finishTransmit() {}
};
