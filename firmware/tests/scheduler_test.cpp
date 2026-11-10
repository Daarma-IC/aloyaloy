#include <cassert>
#include <iostream>
#include <RadioLib.h>
#include "nusa_radio.h"
uint32_t testMillis = 1;
static uint8_t frame[13] = {};
static void put(uint8_t value, NusaPrio priority) {
  frame[12] = value;
  assert(NusaRadio::enqueue(frame, sizeof(frame), priority));
}
static void until(size_t count) {
  for (int i = 0; i < 100 && transmitted.size() < count; ++i) NusaRadio::loop();
  assert(transmitted.size() == count);
  NusaRadio::loop(); // Consume TX_DONE, with no additional clock tick.
}
static const size_t FROM_VALUE = 12 + 23;
static uint8_t fromOwner(uint8_t owner, uint8_t value, NusaPrio priority, size_t size = 24) {
  uint8_t packet[243] = {2, 0x04};
  packet[23] = value;
  NusaAdmissionTrace::OwnerScope scope(owner);
  return NusaRadio::enqueuePacket(packet, size, 1, priority);
}
static void resetQueue() {
  assert(NusaRadio::begin(nullptr));
  transmitted.clear();
  testMillis = 1000;
}
static void assertOrder(std::initializer_list<uint8_t> expected) {
  until(expected.size());
  size_t i = 0;
  for (uint8_t value : expected) assert(transmitted[i++][FROM_VALUE] == value);
}
static void testFairness() {
  // A spamming phone must alternate with a phone that arrived later.
  resetQueue();
  for (int i = 0; i < 6; ++i) assert(fromOwner(0, 10 + i, PRIO_TEXT) == 1);
  for (int i = 0; i < 2; ++i) assert(fromOwner(1, 20 + i, PRIO_TEXT) == 1);
  assertOrder({10, 20, 11, 21, 12, 13, 14, 15});

  // Per-phone caps: text 7, bulk 6, and ACK can use the remaining slot.
  resetQueue();
  for (int i = 0; i < 7; ++i) assert(fromOwner(0, i, PRIO_TEXT) == 1);
  assert(fromOwner(0, 7, PRIO_TEXT) == 0);
  assert(fromOwner(1, 30, PRIO_TEXT) == 1); // The spammer does not lock others out.
  assert(fromOwner(0, 8, PRIO_ACK) == 1);
  assert(fromOwner(0, 9, PRIO_ACK) == 0);
  resetQueue();
  for (int i = 0; i < 6; ++i) assert(fromOwner(2, i, PRIO_LOCAL) == 1);
  assert(fromOwner(2, 6, PRIO_LOCAL) == 0);
  assert(fromOwner(2, 7, PRIO_TEXT) == 1); // Its own text still gets through.

  // An oversized packet needs an empty flow, then fits under global limits only.
  resetQueue();
  uint8_t large[1024] = {2, 0x22};
  {
    NusaAdmissionTrace::OwnerScope scope(3);
    assert(NusaRadio::enqueuePacket(large, sizeof(large), 1, PRIO_LOCAL) == 5);
    assert(NusaRadio::enqueuePacket(large, sizeof(large), 1, PRIO_LOCAL) == 0);
  }

  // A phone joining mid-burst gets no credit for time it was idle.
  resetQueue();
  for (int i = 0; i < 3; ++i) assert(fromOwner(0, 40 + i, PRIO_TEXT) == 1);
  until(1);
  assert(fromOwner(1, 50, PRIO_TEXT) == 1);
  assertOrder({40, 41, 50, 42});

  // Fairness counts airtime: small frames are not punished by one large frame.
  resetQueue();
  assert(fromOwner(0, 60, PRIO_TEXT, 243) == 1);
  assert(fromOwner(0, 61, PRIO_TEXT, 243) == 1);
  assert(fromOwner(1, 70, PRIO_TEXT) == 1);
  assert(fromOwner(1, 71, PRIO_TEXT) == 1);
  assertOrder({60, 70, 71, 61});

  // Node relay traffic is its own flow and never hits the per-phone cap.
  resetQueue();
  for (int i = 0; i < 9; ++i) assert(fromOwner(NusaAdmissionTrace::OWNER_NODE, i, PRIO_TEXT) == 1);
  assert(NusaAdmissionTrace::currentOwner == NusaAdmissionTrace::OWNER_NODE);
}
int main() {
  fakeRandomValue = 0;
  assert(NusaRadio::begin(nullptr));
  for (int i = 0; i < 18; ++i) put(i, PRIO_GOSSIP);
  assert(!NusaRadio::enqueue(frame, sizeof(frame), PRIO_RELAY));
  uint8_t voice[512] = {2, 0x22}; voice[11] = 0x08;
  assert(NusaRadio::enqueuePacket(voice, sizeof(voice), 1, PRIO_LOCAL) == 3);
  put(40, PRIO_VOICE);
  assert(!NusaRadio::enqueue(frame, sizeof(frame), PRIO_VOICE));
  put(41, PRIO_TEXT);
  assert(!NusaRadio::enqueue(frame, sizeof(frame), PRIO_TEXT));
  put(42, PRIO_ACK);
  assert(NusaRadio::queueDepth() == TXQ_SLOTS);

  assert(NusaRadio::begin(nullptr)); transmitted.clear();
  put(1, PRIO_ACK); put(2, PRIO_ACK);
  until(1); put(3, PRIO_ACK); // Reuses slot 0 in the same millisecond.
  until(3);
  assert(transmitted[0][12] == 1 && transmitted[1][12] == 2 && transmitted[2][12] == 3);

  assert(NusaRadio::begin(nullptr)); transmitted.clear();
  put(90, PRIO_LOCAL);
  testMillis += 120001; // Old bulk may not age into the voice class.
  for (int i = 0; i < 6; ++i) put(10 + i, PRIO_VOICE);
  put(91, PRIO_TEXT); put(92, PRIO_ACK);
  until(9);
  assert(transmitted[0][12] == 92 && transmitted[1][12] == 91);
  assert(transmitted[2][12] == 10 && transmitted[3][12] == 11 && transmitted[4][12] == 12);
  assert(transmitted[5][12] == 90); // Bulk gets a bounded turn, voice remains FIFO.
  assert(transmitted[6][12] == 13 && transmitted[8][12] == 15);

  // Fairness must not stall affordable voice behind an unaffordable bulk turn.
  assert(NusaRadio::begin(nullptr)); transmitted.clear();
  uint8_t full[255] = {};
  for (int i = 0; i < 19; ++i) assert(NusaRadio::enqueue(full, sizeof(full), PRIO_ACK));
  until(19);
  for (int i = 0; i < 3; ++i) put(30 + i, PRIO_VOICE);
  until(22);
  assert(NusaRadio::budgetMs() < NusaRadio::airtimeMs(sizeof(full)));
  assert(NusaRadio::enqueue(full, sizeof(full), PRIO_LOCAL)); put(33, PRIO_VOICE);
  until(23); assert(transmitted.back()[12] == 33 && NusaRadio::queueDepth() == 1);

  // A CAD failure is not permission to transmit; elapsed cooldown survives wrap.
  testMillis = UINT32_MAX - 10;
  assert(NusaRadio::begin(nullptr)); transmitted.clear();
  fakeRadios().front()->cadResult = RADIOLIB_ERR_UNKNOWN;
  put(70, PRIO_TEXT); NusaRadio::loop(); NusaRadio::loop();
  assert(transmitted.empty() && NusaRadio::queueDepth() == 1);
  fakeRadios().front()->cadResult = RADIOLIB_CHANNEL_FREE;
  testMillis += 499; NusaRadio::loop(); assert(transmitted.empty());
  ++testMillis; until(1);
  assert(transmitted[0][12] == 70);

  // Busy-channel backoff also uses wrap-safe elapsed/deadline arithmetic.
  testMillis = UINT32_MAX - 10;
  assert(NusaRadio::begin(nullptr)); transmitted.clear();
  fakeRadios().front()->cadResult = RADIOLIB_PREAMBLE_DETECTED;
  put(71, PRIO_TEXT); NusaRadio::loop(); NusaRadio::loop();
  fakeRadios().front()->cadResult = RADIOLIB_CHANNEL_FREE;
  testMillis += BACKOFF_BASE_MS - 1; NusaRadio::loop(); assert(transmitted.empty());
  ++testMillis; until(1);
  assert(transmitted[0][12] == 71);
  testFairness();
  std::cout << "PASS: raw/batch QoS reserves, stable FIFO, voice/bulk fairness, priority aging, CAD failure, timer wrap, per-HP fairness\n";
}
