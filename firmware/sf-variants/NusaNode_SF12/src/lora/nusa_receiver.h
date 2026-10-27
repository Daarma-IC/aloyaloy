#pragma once
#include "../../config.h"
#include "../protocol/nusa_duplex_wire.h"
namespace NusaReceiver {
using Handler = void (*)(const uint8_t*, size_t, float, float);
bool begin(const NusaDuplexWire::Config& config, Handler handler);
void configure(const NusaDuplexWire::Config& config);
void loop();
bool ready();
void setListening(bool enabled);
bool listening(bool enabled);
// TX is serialized by the hub. UART acceptance is not RF completion.
bool startTransmit(const uint8_t* data, size_t len);
NusaDuplexWire::TxState transmitState();
int16_t transmitError();
void cancelTransmit();
void finishTransmit();
uint32_t crcErrors();
uint32_t wireErrors();
uint32_t wireDrops();
}
