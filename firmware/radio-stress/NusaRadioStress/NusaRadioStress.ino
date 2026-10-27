// Two stock LilyGO T3 v1.6.1 boards, no breakout or inter-board wires.
// TX role produces bounded synthetic load; RX role measures actual packets.
#include <Arduino.h>
#include <SPI.h>
#include <RadioLib.h>
#include <esp_system.h>

#ifndef NUSA_ROLE_A
#define NUSA_ROLE_A 1  // 1 = load generator TX; 0 = receiver RX.
#endif
static_assert(NUSA_ROLE_A == 0 || NUSA_ROLE_A == 1, "Role must be 0 or 1");
constexpr bool IS_TX = NUSA_ROLE_A;
constexpr uint16_t MAX_PACKETS = 4096, QUEUE_CAP = 32;
constexpr size_t HEADER = 16, MAX_BYTES = 240;
constexpr float FREQ = 920.5f;
SPIClass radioSpi(VSPI);
SPISettings spiSettings(4000000, MSBFIRST, SPI_MODE0);
SX1276 radio = new Module(18, 26, 23, 33, radioSpi, spiSettings);
volatile bool irq = false;
void IRAM_ATTR onRadioIrq() { irq = true; }

struct Item { uint16_t seq; uint32_t queuedAt; };
Item queueItems[QUEUE_CAP];
uint16_t head = 0, depth = 0, peak = 0, target = 0, packetBytes = 200;
uint32_t runId = 0, intervalMs = 0, generated = 0, admitted = 0, dropped = 0;
uint32_t txOk = 0, txErr = 0, txTimeout = 0, startedAt = 0, nextOffer = 0;
uint32_t txAt = 0, maxWait = 0, totalWait = 0, attempted = 0, lastReport = 0;
bool healthy = false, running = false, txBusy = false, receiverRun = false;
uint8_t txData[MAX_BYTES], seen[MAX_PACKETS / 8];
uint32_t rxUnique = 0, rxDuplicates = 0, crcErr = 0, rxErr = 0, bad = 0;
char command[80];
size_t commandLen = 0;

void put32(uint8_t* p, uint32_t v) {
  p[0] = v >> 24; p[1] = v >> 16; p[2] = v >> 8; p[3] = v;
}
uint32_t get32(const uint8_t* p) {
  return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) |
         ((uint32_t)p[2] << 8) | p[3];
}
bool check(int16_t code, const char* op) {
  if (code == RADIOLIB_ERR_NONE) return true;
  Serial.printf("[ERROR] %s code=%d\n", op, code);
  return false;
}
void report(const char* tag) {
  uint32_t elapsed = millis() - startedAt;
  if (IS_TX) {
    Serial.printf("[%s] TX run=%lu offered=%lu admitted=%lu dropQueue=%lu "
      "txDone=%lu txErr=%lu timeout=%lu q=%u peak=%u waitMaxMs=%lu "
      "waitMeanMs=%.1f elapsedMs=%lu\n", tag, (unsigned long)runId,
      (unsigned long)generated, (unsigned long)admitted, (unsigned long)dropped,
      (unsigned long)txOk, (unsigned long)txErr, (unsigned long)txTimeout,
      depth, peak, (unsigned long)maxWait,
      attempted ? (double)totalWait / attempted : 0.0, (unsigned long)elapsed);
  } else {
    Serial.printf("[%s] RX run=%lu target=%u unique=%lu duplicate=%lu crc=%lu "
      "rxErr=%lu bad=%lu elapsedMs=%lu\n", tag, (unsigned long)runId,
      target, (unsigned long)rxUnique, (unsigned long)rxDuplicates,
      (unsigned long)crcErr, (unsigned long)rxErr, (unsigned long)bad,
      (unsigned long)elapsed);
  }
}
void startRun(uint32_t count, uint32_t gap, uint32_t bytes) {
  if (!IS_TX || running || txBusy) { Serial.println("[ERROR] TX idle required"); return; }
  if (!count || count > MAX_PACKETS || gap > 60000 || bytes < HEADER || bytes > MAX_BYTES) {
    Serial.println("[ERROR] run COUNT(1..4096) INTERVAL_MS(0..60000) BYTES(16..240)"); return;
  }
  runId = esp_random(); if (!runId) runId = 1;
  head = depth = peak = 0; target = count; packetBytes = bytes; intervalMs = gap;
  generated = admitted = dropped = txOk = txErr = txTimeout = 0;
  maxWait = totalWait = attempted = 0;
  startedAt = nextOffer = lastReport = millis();
  running = true;
  Serial.printf("[RUN] run=%lu count=%u offerEveryMs=%lu bytes=%u queue=%u "
    "SF7 BW125 CR4/5 2dBm noCAD noDutyPacing\n", (unsigned long)runId,
    target, (unsigned long)intervalMs, packetBytes, QUEUE_CAP);
}
void handleCommand() {
  command[commandLen] = 0;
  unsigned long count, gap, bytes;
  if (sscanf(command, "run %lu %lu %lu", &count, &gap, &bytes) == 3) startRun(count, gap, bytes);
  else if (!strcmp(command, "status")) report("STATUS");
  else if (!strcmp(command, "stop") && IS_TX) {
    // Cancel queued items; allow the current RF packet to finish.
    Serial.printf("[CANCEL] queued=%u notOffered=%lu\n", depth,
                  (unsigned long)(target - generated));
    depth = 0; running = false; report("STOP");
  } else if (!strcmp(command, "reset") && !IS_TX) {
    receiverRun = false; runId = target = 0;
    rxUnique = rxDuplicates = crcErr = rxErr = bad = 0;
    memset(seen, 0, sizeof(seen)); startedAt = millis();
    Serial.println("[RX] counters reset");
  } else Serial.println("[HELP] run COUNT INTERVAL_MS BYTES | status | stop(TX) | reset(RX)");
  commandLen = 0;
}
void serialCommands() {
  // Bound serial work so RX and TX completion remain responsive.
  for (int n = 0; n < 80 && Serial.available(); ++n) {
    char c = Serial.read();
    if (c == '\r' || c == '\n') { if (commandLen) handleCommand(); }
    else if (commandLen < sizeof(command) - 1) command[commandLen++] = c;
    else { commandLen = 0; Serial.println("[ERROR] command too long"); }
  }
}
void serviceTx() {
  if (txBusy && irq) {
    irq = false; txBusy = false;
    if (check(radio.finishTransmit(), "TX finish")) ++txOk; else ++txErr;
  } else if (txBusy && millis() - txAt > 2000) {
    txBusy = false; ++txErr; ++txTimeout;
    if (!check(radio.standby(), "TX timeout recovery")) healthy = false;
    irq = false;
  }
  if (!running || !healthy) return;
  uint32_t now = millis();
  // interval=0 offers the entire finite burst before draining the queue.
  while (generated < target && (intervalMs == 0 || (int32_t)(now - nextOffer) >= 0)) {
    uint16_t seq = generated++;
    if (depth == QUEUE_CAP) ++dropped;
    else {
      queueItems[(head + depth) % QUEUE_CAP] = {seq, now};
      ++depth; ++admitted; if (depth > peak) peak = depth;
    }
    nextOffer += intervalMs;
  }
  if (!txBusy && depth) {
    Item item = queueItems[head]; head = (head + 1) % QUEUE_CAP; --depth;
    uint32_t wait = now - item.queuedAt;
    totalWait += wait; if (wait > maxWait) maxWait = wait; ++attempted;
    txData[0] = 'N'; txData[1] = 'S'; txData[2] = 1; txData[3] = 0;
    put32(txData + 4, runId); put32(txData + 8, item.seq);
    txData[12] = target >> 8; txData[13] = target;
    txData[14] = packetBytes >> 8; txData[15] = packetBytes;
    for (size_t i = HEADER; i < packetBytes; ++i) txData[i] = (item.seq + i) % 251;
    irq = false; txAt = now; txBusy = true;
    if (!check(radio.startTransmit(txData, packetBytes), "TX start")) {
      txBusy = false; ++txErr;
      if (!check(radio.standby(), "TX start recovery")) healthy = false;
    }
  }
  if (generated == target && !depth && !txBusy) { running = false; report("DONE"); }
}
void serviceRx() {
  if (!irq) return;
  irq = false;
  uint8_t data[255]; size_t len = radio.getPacketLength();
  int16_t status = len && len <= sizeof(data) ? radio.readData(data, len) : RADIOLIB_ERR_UNKNOWN;
  if (!check(radio.startReceive(), "RX rearm")) healthy = false;
  if (status == RADIOLIB_ERR_CRC_MISMATCH) { ++crcErr; return; }
  if (status != RADIOLIB_ERR_NONE) { ++rxErr; return; }
  if (len < HEADER || data[0] != 'N' || data[1] != 'S' || data[2] != 1) { ++bad; return; }
  uint32_t incomingRun = get32(data + 4), seq = get32(data + 8);
  uint16_t count = ((uint16_t)data[12] << 8) | data[13];
  uint16_t declaredLen = ((uint16_t)data[14] << 8) | data[15];
  if (!incomingRun || !count || count > MAX_PACKETS || seq >= count ||
      len != declaredLen || len > MAX_BYTES) { ++bad; return; }
  for (size_t i = HEADER; i < len; ++i) {
    if (data[i] != (seq + i) % 251) { ++bad; return; }
  }
  if (!receiverRun || incomingRun != runId) {
    if (receiverRun) report("PREVIOUS");
    receiverRun = true; runId = incomingRun; target = count;
    rxUnique = rxDuplicates = 0; memset(seen, 0, sizeof(seen));
    startedAt = millis();
    Serial.printf("[RX_RUN] run=%lu target=%u\n", (unsigned long)runId, target);
  }
  if (target != count) { ++bad; return; }
  uint8_t mask = 1U << (seq % 8);
  if (seen[seq / 8] & mask) ++rxDuplicates;
  else { seen[seq / 8] |= mask; ++rxUnique; }
}
void setup() {
  Serial.begin(115200); delay(300);
  pinMode(18, OUTPUT); digitalWrite(18, HIGH);
  radioSpi.begin(5, 19, 27, 18);
  Serial.printf("\n[STRESS] role=%s same-channel=%.3f MHz SF7 2dBm; synthetic one-way test\n",
                IS_TX ? "TX" : "RX", FREQ);
  if (!check(radio.begin(FREQ, 125.0f, 7, 5, 0x12, 2, 8), "init")) return;
  if (!check(radio.setCRC(true), "CRC")) return;
  if (IS_TX) radio.setPacketSentAction(onRadioIrq);
  else {
    radio.setPacketReceivedAction(onRadioIrq);
    if (!check(radio.startReceive(), "RX start")) return;
  }
  healthy = true; startedAt = lastReport = millis();
  Serial.println("[READY] No automatic TX. Send newline: run 100 400 200 | status | stop | reset");
}
void loop() {
  if (!healthy) { delay(10); return; }
  if (IS_TX) serviceTx(); else serviceRx();
  serialCommands();
  if (millis() - lastReport >= 1000) {
    lastReport = millis();
    if (running || txBusy || (!IS_TX && receiverRun)) report("STATUS");
  }
}
