#include <cassert>
#include <iostream>
#include <RadioLib.h>
#include <HardwareSerial.h>
#include <math.h>
#include "nusa_radio.h"
#include "../NusaNode/src/protocol/nusa_duplex_wire.h"
namespace member {
#include "../NusaRxCompanion/NusaRxCompanion.ino"
}
using namespace NusaDuplexWire;
uint32_t testMillis = 1;
static unsigned received = 0;
static void rx(const NusaFrame&, float, float) { ++received; }
static Message lastTx{};
static SX1276* localRadio() { for (auto* r : fakeRadios()) if (r != &member::radio) return r; return nullptr; }
static void pump(bool deliverToHub = true) {
  auto commands = fakeUart().output; fakeUart().output.clear();
  Parser parser; Message m;
  for (auto b : commands) if (parser.feed(b, m)) {
    if (m.type == MSG_CONFIG) member::configureRadio(m);
    if (m.type == MSG_LISTEN) member::setListening(m);
    if (m.type == MSG_TX) { lastTx = m; member::transmit(m); }
    if (m.type == MSG_CANCEL) member::cancel(m);
  }
  member::serviceTx();
  if (testMillis - member::lastStatus >= 1000) member::sendStatus();
  auto statuses = fakeUart().output; fakeUart().output.clear();
  if (deliverToHub) fakeUart().input.insert(fakeUart().input.end(), statuses.begin(), statuses.end());
  NusaRadio::loop();
  auto* local = localRadio();
  assert(!(local->transmitting && member::radio.transmitting));
  if (local->transmitting || member::radio.transmitting) assert(!local->listening && !member::radio.listening);
}
static void step(unsigned n = 1, bool deliver = true) { while (n--) { testMillis += 100; pump(deliver); } }
static void untilTx(size_t count) { for (int i = 0; i < 100 && transmitted.size() < count; ++i) step(); assert(transmitted.size() == count); }
int main() {
  fakeAutoTxDone = false;
  // Main reboots while its member still has an old RF transmission in flight.
  member::radioReady = true; member::txState = TX_BUSY; member::txToken = 99;
  member::radio.transmitting = true;
  assert(NusaRadio::begin(rx)); assert(!localRadio()->listening);
  pump(); pump();
  auto* local = localRadio();
  uint8_t image[100] = {2, 0x22}, voice[100] = {2, 0x22}; voice[11] = 0x08;
  assert(NusaRadio::enqueuePacket(image, sizeof(image), 1, PRIO_LOCAL));
  assert(NusaRadio::enqueuePacket(voice, sizeof(voice), 1, PRIO_LOCAL));
  untilTx(1);
  NusaFrame sent;
  assert(nusaFrameDecode(transmitted[0].data(), transmitted[0].size(), sent));
  assert(sent.flags & NUSA_FLAG_VOICE); // New audio overtakes queued image.
  assert(local->transmitting && !member::radio.transmitting);
  local->irq(); step();
  untilTx(2); assert(member::radio.transmitting && !local->transmitting);
  auto duplicate = lastTx; member::transmit(duplicate);
  assert(transmitted.size() == 2); // Duplicate UART command is idempotent.
  member::radio.irq(); step(); step();
  assert(NusaRadio::txCount() == 2 && member::listening);
  // The two receivers deliver only one copy into the hub.
  local->rxData = transmitted[0]; local->irq();
  member::radio.rxData = transmitted[0]; member::rxIrq = true; member::serviceRx();
  auto wire = fakeUart().output; fakeUart().output.clear();
  fakeUart().input.insert(fakeUart().input.end(), wire.begin(), wire.end());
  NusaRadio::loop(); assert(received == 1);
  // A missing external TX_DONE consumes budget, cancels, and never replays RF.
  assert(NusaRadio::enqueuePacket(image, sizeof(image), 1, PRIO_LOCAL)); untilTx(3);
  local->irq(); step();
  assert(NusaRadio::enqueuePacket(image, sizeof(image), 1, PRIO_LOCAL)); untilTx(4);
  assert(member::radio.transmitting);
  // Drop every status until main watchdog fires. It must hold subsequent TX.
  step(30, false); assert(NusaRadio::timeoutCount() == 1);
  assert(NusaRadio::enqueuePacket(image, sizeof(image), 1, PRIO_LOCAL));
  step(10, false); assert(transmitted.size() == 4);
  step(20); untilTx(5);
  assert(NusaRadio::txCount() == 3); // Unknown external completion is not counted as success.
  local->irq(); step();
  member::radio.cadResult = RADIOLIB_PREAMBLE_DETECTED;
  assert(NusaRadio::enqueuePacket(image, sizeof(image), 1, PRIO_LOCAL));
  untilTx(6); // Member rejects busy channel; same queued frame is taken by main.
  assert(local->transmitting && !member::radio.transmitting);
  member::radio.cadResult = 0;
  member::transmit(duplicate); assert(transmitted.size() == 6); // Old token stays rejected.
  Message missing{}; missing.type = MSG_CANCEL; missing.seq = member::configSeq; missing.len = 2;
  put16(missing.data, member::txToken + 1); member::cancel(missing);
  assert(member::txState == TX_UNKNOWN); // Cancel a TX command lost before acceptance.
  missing.type = MSG_TX; missing.len = 3; missing.data[2] = 1;
  member::transmit(missing); assert(transmitted.size() == 6);
  std::cout << "PASS: real companion command handler, alternate TX owners, no overlap, voice over image, dual RX dedup, idempotence, lost completion recovery\n";
}
