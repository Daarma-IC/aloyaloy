#include <cassert>
#include <iostream>
#include <RadioLib.h>
#include "nusa_radio.h"
uint32_t testMillis = 1;
static void rx(const NusaFrame&, float, float) {}
static void step() { testMillis += 100; NusaRadio::loop(); }
int main() {
  fakeAutoTxDone = false;
  assert(NusaRadio::begin(rx));
  SX1276 *local = nullptr, *member = nullptr;
  for (auto* r : fakeRadios()) { if (r->cs == LORA_CS) local = r; else member = r; }
  assert(local && member);
  uint8_t packet[700] = {2, 0x22};
  assert(NusaRadio::enqueuePacket(packet, sizeof(packet), 1, PRIO_LOCAL) == 3);
  for (int i = 0; i < 30 && transmitted.size() < 1; ++i) step();
  assert(transmitted.size() == 1 && local->transmitting && !member->listening);
  local->irq(); step();
  for (int i = 0; i < 30 && transmitted.size() < 2; ++i) step();
  assert(transmitted.size() == 2 && member->transmitting && !local->listening);
  member->irq(); step();
  assert(NusaRadio::txCount() == 2 && NusaRadio::queueDepth() == 1);
  for (int i = 0; i < 30 && transmitted.size() < 3; ++i) step();
  assert(transmitted.size() == 3 && local->transmitting && !member->listening);
  local->irq(); step();
  assert(NusaRadio::txCount() == 3 && NusaRadio::queueDepth() == 0);
  std::vector<uint8_t> assembled;
  uint64_t id = 0;
  for (size_t i = 0; i < transmitted.size(); ++i) {
    NusaFrame frame;
    assert(nusaFrameDecode(transmitted[i].data(), transmitted[i].size(), frame));
    if (!i) id = frame.msgId;
    assert(frame.msgId == id && frame.fragIdx == i && frame.fragTotal == 3);
    assembled.insert(assembled.end(), frame.data, frame.data + frame.dataLen);
  }
  assert(assembled == std::vector<uint8_t>(packet, packet + sizeof(packet)));
  assert(local->listening && member->listening);
  // A start error can follow an actual RF-mode write: discard as UNKNOWN,
  // charge the attempt, and never hand the same frame to the other radio.
  member->txStartResult = -10;
  assert(NusaRadio::enqueuePacket(packet, 100, 1, PRIO_LOCAL) == 1);
  for (int i = 0; i < 30; ++i) step();
  assert(transmitted.size() == 3 && NusaRadio::queueDepth() == 0 && NusaRadio::txCount() == 3);
  assert(local->listening && member->listening);
  member->txStartResult = 0;
  local->standbyResult = -10;
  assert(NusaRadio::enqueuePacket(packet, sizeof(packet), 1, PRIO_LOCAL) == 3);
  for (int i = 0; i < 30; ++i) step();
  assert(transmitted.size() == 3 && !NusaRadio::healthy()); // Fail closed if local RX cannot stop.
  std::cout << "PASS: direct SPI takeover, alternate TX ownership, both resume RX\n";
}
