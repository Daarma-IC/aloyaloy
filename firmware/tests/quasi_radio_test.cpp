#include <cassert>
#include <iostream>
#include <RadioLib.h>
#include <HardwareSerial.h>
#include "nusa_radio.h"
#include "../NusaNode/src/protocol/nusa_duplex_wire.h"
using namespace NusaDuplexWire;
uint32_t testMillis = 1;
static unsigned received = 0;
static void rx(const NusaFrame&, float, float) { ++received; }
static Message take(uint8_t type) {
  Parser parser; Message message{}, found{};
  for (auto b : fakeUart().output) if (parser.feed(b, message) && message.type == type) found = message;
  fakeUart().output.clear(); assert(found.type == type); return found;
}
static void inject(uint8_t type, uint16_t seq, const uint8_t* data, size_t size) {
  uint8_t wire[MAX_ENCODED]; size_t len = encode(type, seq, data, size, wire, sizeof(wire));
  assert(len); fakeUart().input.insert(fakeUart().input.end(), wire, wire + len);
}
static void status(const Message& config, bool listening, uint16_t token) {
  uint8_t data[STATUS_SIZE] = {};
  put16(data, crc16(config.data, config.len)); data[4] = 1;
  data[17] = listening; put16(data + 18, token);
  inject(MSG_STATUS, config.seq, data, sizeof(data)); NusaRadio::loop();
}
int main() {
  fakeAutoTxDone = false;
  assert(NusaRadio::begin(rx)); assert(!NusaRadio::healthy());
  Message config = take(MSG_CONFIG); Config decoded;
  assert(unpackConfig(config.data, config.len, decoded) && decoded.freqKhz == 921000);
  status(config, false, 0); // Newly booted companion stays in standby.
  Message enable = take(MSG_LISTEN);
  assert(enable.data[0] == 1);
  status(config, true, get16(enable.data + 1));
  assert(NusaRadio::healthy());
  uint8_t raw[255], value = 11;
  NusaFrame frame{}; frame.version = NUSA_FRAME_VERSION; frame.hop = 1;
  frame.msgId = 1; frame.fragTotal = 1; frame.data = &value; frame.dataLen = 1;
  size_t len = nusaFrameEncode(raw, frame);
  assert(NusaRadio::enqueue(raw, len, PRIO_LOCAL));
  value = 22; frame.msgId = 2; len = nusaFrameEncode(raw, frame);
  assert(NusaRadio::enqueue(raw, len, PRIO_TEXT));
  NusaRadio::loop();
  testMillis += 100; NusaRadio::loop(); assert(transmitted.empty());
  testMillis += 600; NusaRadio::loop();
  Message pause = take(MSG_LISTEN); assert(pause.data[0] == 0);
  testMillis += 100; NusaRadio::loop(); assert(transmitted.empty());
  // A stale gate acknowledgement must never allow local RF TX.
  status(config, false, get16(enable.data + 1)); assert(transmitted.empty());
  status(config, false, get16(pause.data + 1));
  testMillis += 100; NusaRadio::loop();
  assert(transmitted.size() == 1 && NusaRadio::txCount() == 0);
  NusaFrame sent;
  assert(nusaFrameDecode(transmitted[0].data(), transmitted[0].size(), sent) && sent.data[0] == 22);
  // RX frames received after PAUSE acknowledgement are not delivered while TX.
  uint8_t payload[260]; payload[0] = len; put16(payload + 1, (uint16_t)(int16_t)-800);
  put16(payload + 3, 100); memcpy(payload + 5, raw, len);
  inject(MSG_RX, config.seq, payload, len + 5); NusaRadio::loop();
  assert(received == 0);
  fakeRadios().front()->irq(); NusaRadio::loop(); assert(NusaRadio::txCount() == 1);
  enable = take(MSG_LISTEN); assert(enable.data[0] == 1);
  status(config, true, get16(enable.data + 1));
  inject(MSG_RX, config.seq, payload, len + 5); NusaRadio::loop(); assert(received == 1);
  // An RX opportunity is preserved before the next queued/bulk frame.
  testMillis += 100; NusaRadio::loop(); assert(transmitted.size() == 1);
  testMillis += 600; NusaRadio::loop(); pause = take(MSG_LISTEN);
  assert(pause.data[0] == 0 && transmitted.size() == 1);
  testMillis += LORA_COMPANION_STALE_MS + 1; NusaRadio::loop();
  assert(!NusaRadio::healthy() && transmitted.size() == 1 && NusaRadio::queueDepth() == 1);
  take(MSG_CONFIG);
  // After companion reboot, CONFIG ACK alone cannot authorize TX.
  status(config, false, 0); assert(transmitted.size() == 1);
  pause = take(MSG_LISTEN); assert(pause.data[0] == 0);
  status(config, false, get16(pause.data + 1));
  testMillis += 100; NusaRadio::loop();
  assert(transmitted.size() == 2);
  std::cout << "PASS: quasi RX windows, PAUSE ACK before TX, stale gate rejection, text priority, resume RX, safe reconnect\n";
}
