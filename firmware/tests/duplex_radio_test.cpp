#include <cassert>
#include <cmath>
#include <iostream>
#include <RadioLib.h>
#include <HardwareSerial.h>
#include "nusa_radio.h"
#include "../NusaNode/src/protocol/nusa_duplex_wire.h"
using namespace NusaDuplexWire;
uint32_t testMillis = 1;
static unsigned delivered = 0;
static void rx(const NusaFrame& frame, float rssi, float snr) {
  assert(frame.dataLen == 3 && frame.data[0] == 1);
  assert(std::abs(rssi + 91.2f) < 0.1f && std::abs(snr - 7.5f) < 0.1f);
  ++delivered;
}
static Message lastConfig() {
  Parser parser; Message message{}, found{};
  for (auto byte : fakeUart().output) if (parser.feed(byte, message) && message.type == MSG_CONFIG) found = message;
  fakeUart().output.clear(); assert(found.type == MSG_CONFIG); return found;
}
static void inject(uint8_t type, uint16_t seq, const uint8_t* data, size_t size) {
  uint8_t encoded[MAX_ENCODED]; size_t len = encode(type, seq, data, size, encoded, sizeof(encoded));
  assert(len); fakeUart().input.insert(fakeUart().input.end(), encoded, encoded + len);
}
static void ack(const Message& config, bool wrongDigest = false) {
  uint8_t status[STATUS_SIZE] = {};
  put16(status, crc16(config.data, config.len) ^ (wrongDigest ? 1 : 0));
  status[4] = 1;
  status[17] = 1; put16(status + 18, 1);
  inject(MSG_STATUS, config.seq, status, sizeof(status)); NusaRadio::loop();
}
int main() {
  fakeAutoTxDone = false;
  assert(NusaRadio::begin(rx)); assert(!NusaRadio::healthy());
  Message config = lastConfig(); Config decoded;
  assert(unpackConfig(config.data, config.len, decoded) && decoded.sf == 7 && decoded.freqKhz == 922500);
  uint8_t bytes[3] = {1,2,3}, raw[255];
  NusaFrame frame{}; frame.version = NUSA_FRAME_VERSION; frame.hop = 1;
  frame.msgId = 42; frame.fragTotal = 1; frame.data = bytes; frame.dataLen = 3;
  size_t len = nusaFrameEncode(raw, frame);
  assert(NusaRadio::enqueue(raw, len, PRIO_TEXT));
  for (int i=0;i<4;++i) { testMillis += 100; NusaRadio::loop(); }
  assert(transmitted.empty() && NusaRadio::queueDepth() == 1);
  ack(config, true); assert(!NusaRadio::healthy());
  ack(config); assert(NusaRadio::healthy());
  for (int i=0;i<4 && transmitted.empty();++i) { testMillis += 100; NusaRadio::loop(); }
  assert(transmitted.size() == 1 && NusaRadio::txCount() == 0);
  // Incoming frame is delivered while TX_DONE has deliberately not occurred.
  uint8_t payload[260]; payload[0] = len;
  put16(payload + 1, (uint16_t)(int16_t)-912); put16(payload + 3, 75);
  memcpy(payload + 5, raw, len);
  inject(MSG_RX, config.seq, payload, len + 5); NusaRadio::loop();
  assert(delivered == 1 && NusaRadio::rxCount() == 1 && NusaRadio::txCount() == 0);
  assert(NusaRadio::requestSpreadingFactor(8));
  NusaRadio::loop(); // SF change must wait until TX completes.
  assert(NusaRadio::spreadingFactor() == 7);
  fakeRadios().front()->irq(); NusaRadio::loop(); NusaRadio::loop();
  assert(NusaRadio::txCount() == 1 && NusaRadio::spreadingFactor() == 8 && !NusaRadio::healthy());
  config = lastConfig();
  assert(unpackConfig(config.data, config.len, decoded) && decoded.sf == 8);
  ack(config); assert(NusaRadio::healthy());
  // A stale/wrong ACK never authorizes sending; heartbeat loss holds TX.
  testMillis += LORA_COMPANION_STALE_MS + 1;
  assert(!NusaRadio::healthy());
  assert(NusaRadio::enqueue(raw, len, PRIO_TEXT));
  NusaRadio::loop(); assert(transmitted.size() == 1 && NusaRadio::queueDepth() == 1);
  Message retry = lastConfig(); assert(retry.seq == config.seq);
  ack(retry); assert(NusaRadio::healthy());
  for (int i=0;i<4 && transmitted.size()<2;++i) { testMillis += 100; NusaRadio::loop(); }
  assert(transmitted.size() == 2);
  // Lost TX interrupt recovers instead of leaving the scheduler wedged.
  testMillis += 2000; NusaRadio::loop();
  assert(NusaRadio::timeoutCount() == 1 && NusaRadio::txCount() == 1);
  fakeTxFailure = true;
  assert(NusaRadio::enqueue(raw, len, PRIO_TEXT));
  for (int i=0;i<10;++i) { testMillis += 100; NusaRadio::loop(); }
  assert(transmitted.size() == 2 && NusaRadio::queueDepth() == 0); // Ambiguous start is never replayed.
  std::cout << "PASS: ACK gating, RX delivered during active TX, SF synchronization, stale-link hold/retry, TX timeout/start failure\n";
}
