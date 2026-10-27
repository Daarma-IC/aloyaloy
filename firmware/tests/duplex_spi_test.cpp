#include <cassert>
#include <iostream>
#include <RadioLib.h>
#include "nusa_radio.h"
uint32_t testMillis = 1;
static int received = 0;
static void rx(const NusaFrame& f, float rssi, float snr) {
  assert(f.dataLen == 1 && f.data[0] == 77 && rssi == -80 && snr == 10);
  ++received;
}
int main() {
  fakeAutoTxDone = false;
  assert(NusaRadio::begin(rx) && NusaRadio::healthy());
  assert(fakeRadios().size() == 2);
  SX1276 *txRadio = nullptr, *rxRadio = nullptr;
  for (auto* radio : fakeRadios()) { if (radio->cs == LORA_CS) txRadio = radio; else if (radio->cs == LORA_EXT_CS) rxRadio = radio; }
  assert(txRadio && rxRadio);
  assert(txRadio->receiveStarts == 0 && rxRadio->receiveStarts == 1);
  uint8_t byte = 77, raw[255];
  NusaFrame frame{}; frame.version = NUSA_FRAME_VERSION; frame.hop = 1;
  frame.msgId = 5; frame.fragTotal = 1; frame.data = &byte; frame.dataLen = 1;
  size_t len = nusaFrameEncode(raw, frame);
  assert(NusaRadio::enqueue(raw, len, PRIO_TEXT));
  for (int i=0;i<15 && transmitted.empty();++i) { testMillis += 100; NusaRadio::loop(); }
  assert(transmitted.size() == 1 && NusaRadio::txCount() == 0);
  rxRadio->rxData.assign(raw, raw + len); rxRadio->irq();
  NusaRadio::loop();
#if LORA_FULL_DUPLEX
  assert(received == 1 && NusaRadio::txCount() == 0);
  assert(txRadio->receiveStarts == 0 && rxRadio->receiveStarts == 2);
  txRadio->irq(); NusaRadio::loop(); assert(NusaRadio::txCount() == 1);
  std::cout << "PASS: SPI receiver serviced/rearmed independently while TX active; TX radio never enters RX\n";
#else
  assert(received == 0 && NusaRadio::txCount() == 0);
  assert(rxRadio->receiveStarts == 1);
  txRadio->irq(); NusaRadio::loop(); assert(NusaRadio::txCount() == 1);
  assert(rxRadio->receiveStarts == 2);
  rxRadio->rxData.assign(raw, raw + len); rxRadio->irq(); NusaRadio::loop();
  assert(received == 1 && rxRadio->receiveStarts == 3 && txRadio->receiveStarts == 0);
  std::cout << "PASS: quasi SPI pauses RX before TX and resumes after TX completion\n";
#endif
}
