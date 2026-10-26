// LilyGO T3 v1.6.1 + external SPI SX1276. Bench test, not NusaMesh firmware.
// Flash this ESP32 once: it configures BOTH radios over SPI.
#include <Arduino.h>
#include <SPI.h>
#include <RadioLib.h>

#ifndef NUSA_ROLE_A
#define NUSA_ROLE_A 1  // 1 = node A; change to 0 for node B before upload.
#endif
static_assert(NUSA_ROLE_A == 0 || NUSA_ROLE_A == 1, "Role must be 0 or 1");

constexpr float TX_MHZ = NUSA_ROLE_A ? 920.5f : 922.5f;
constexpr float RX_MHZ = NUSA_ROLE_A ? 922.5f : 920.5f;
constexpr uint8_t SELF = NUSA_ROLE_A ? 1 : 2;
constexpr uint8_t PEER = NUSA_ROLE_A ? 2 : 1;
constexpr uint8_t SF = 7;
constexpr size_t PACKET_LEN = 160;
constexpr uint32_t PERIOD_MS = 10000;
constexpr uint32_t TX_TIMEOUT_MS = 2000;

SPIClass radioSpi(VSPI);
SPISettings settings(4000000, MSBFIRST, SPI_MODE0);
// Module(CS, DIO0, RESET, DIO1, SPI, settings).
SX1276 txRadio = new Module(18, 26, 23, 33, radioSpi, settings);
// DIO1 unused in this continuous-RX test. Do not connect it.
SX1276 rxRadio = new Module(13, 34, 14, RADIOLIB_NC, radioSpi, settings);

volatile bool txDone = false;
volatile bool rxDone = false;
volatile bool txActive = false;
volatile bool rxIrqDuringTx = false;
bool txEnabled = true;
bool healthy = false;
uint32_t startedAt = 0, nextTxAt = 0, lastStatus = 0;
uint32_t seq = 0, sent = 0, received = 0, crcErrors = 0;
uint32_t overlapRx = 0, txErrors = 0, rxErrors = 0, rejected = 0;
uint8_t txBuffer[PACKET_LEN];  // Kept alive until finishTransmit().

void IRAM_ATTR onTxDone() { txDone = true; }
void IRAM_ATTR onRxDone() {
  rxIrqDuringTx = txActive && !txDone;
  rxDone = true;
}

bool check(int16_t status, const char* operation) {
  if (status == RADIOLIB_ERR_NONE) return true;
  Serial.printf("[ERROR] %s code=%d\n", operation, status);
  return false;
}

void setup() {
  Serial.begin(115200);
  delay(300);
  // Both slaves deselected BEFORE either radio is initialized.
  pinMode(18, OUTPUT); digitalWrite(18, HIGH);
  pinMode(13, OUTPUT); digitalWrite(13, HIGH);
  radioSpi.begin(5, 19, 27, 18);
  Serial.printf("\n[DUAL TEST] node=%c onboard TX=%.3f external RX=%.3f MHz SF%u\n",
                NUSA_ROLE_A ? 'A' : 'B', TX_MHZ, RX_MHZ, SF);
  // 2 dBm is an initial bench setting; spacing/filtering still need validation.
  if (!check(txRadio.begin(TX_MHZ, 125.0f, SF, 5, 0x12, 2, 8), "TX init")) return;
  if (!check(rxRadio.begin(RX_MHZ, 125.0f, SF, 5, 0x12, 2, 8), "RX init")) return;
  if (!check(txRadio.setCRC(true), "TX CRC")) return;
  if (!check(rxRadio.setCRC(true), "RX CRC")) return;
  txRadio.setPacketSentAction(onTxDone);
  rxRadio.setPacketReceivedAction(onRxDone);
  if (!check(rxRadio.startReceive(), "RX start")) return;
  healthy = true;
  nextTxAt = millis() + 1000;
  Serial.println("[READY] t=toggle local TX, s=request send (10s minimum spacing)");
}

void serviceRx() {
  if (!rxDone) return;
  // Continuous RX has one hardware FIFO; this is not a lossless event queue.
  const bool overlapped = rxIrqDuringTx;
  rxDone = false;
  uint8_t data[255];
  size_t len = rxRadio.getPacketLength();
  int16_t status = len && len <= sizeof(data)
      ? rxRadio.readData(data, len) : RADIOLIB_ERR_UNKNOWN;
  float rssi = rxRadio.getRSSI(), snr = rxRadio.getSNR();
  // Rearm before serial output; this touches only the external receiver.
  if (!check(rxRadio.startReceive(), "RX rearm")) healthy = false;
  if (status == RADIOLIB_ERR_CRC_MISMATCH) { ++crcErrors; return; }
  if (status != RADIOLIB_ERR_NONE) { ++rxErrors; return; }
  if (len != PACKET_LEN || data[0] != 'N' || data[1] != 'D' ||
      data[2] != 1 || data[3] != PEER) { ++rejected; return; }
  uint32_t peerSeq = ((uint32_t)data[4] << 24) | ((uint32_t)data[5] << 16) |
                     ((uint32_t)data[6] << 8) | data[7];
  ++received;
  if (overlapped) ++overlapRx;
  Serial.printf("[RX] peer=%u seq=%lu RSSI=%.1f SNR=%.1f irqDuringTx=%u\n",
                PEER, (unsigned long)peerSeq, rssi, snr, overlapped);
}

void startTx() {
  memset(txBuffer, 0x55, sizeof(txBuffer));
  txBuffer[0] = 'N'; txBuffer[1] = 'D'; txBuffer[2] = 1; txBuffer[3] = SELF;
  ++seq;
  txBuffer[4] = seq >> 24; txBuffer[5] = seq >> 16;
  txBuffer[6] = seq >> 8; txBuffer[7] = seq;
  txDone = false;
  // The external radio stays in RX throughout this operation.
  txActive = true;
  startedAt = millis();
  int16_t status = txRadio.startTransmit(txBuffer, sizeof(txBuffer));
  nextTxAt = millis() + PERIOD_MS;
  if (!check(status, "TX start")) {
    txActive = false;
    ++txErrors;
    if (!check(txRadio.standby(), "TX recovery")) healthy = false;
  }
}

void loop() {
  if (!healthy) { delay(10); return; }
  serviceRx();
  if (!healthy) return;
  if (txActive && txDone) {
    txActive = false;
    txDone = false;
    if (check(txRadio.finishTransmit(), "TX finish")) ++sent;
    else { ++txErrors; healthy = false; }
  } else if (txActive && millis() - startedAt > TX_TIMEOUT_MS) {
    txActive = false;
    ++txErrors;
    if (!check(txRadio.standby(), "TX timeout recovery")) healthy = false;
    txDone = false;
    Serial.println("[ERROR] TX timeout; check onboard DIO0 GPIO26");
  }
  if (!healthy) return;
  if (Serial.available()) {
    char command = Serial.read();
    if (command == 't') {
      txEnabled = !txEnabled;
      Serial.printf("[TX] %s (RX remains active)\n", txEnabled ? "ON" : "OFF");
    }
    if (command == 's' && !txActive &&
        (seq == 0 || millis() - startedAt >= PERIOD_MS)) nextTxAt = millis();
  }
  if (txEnabled && !txActive && (int32_t)(millis() - nextTxAt) >= 0) startTx();
  if (millis() - lastStatus >= 5000) {
    lastStatus = millis();
    Serial.printf("[STATUS] tx=%lu rx=%lu rxIrqDuringTx=%lu crc=%lu txErr=%lu rxErr=%lu rejected=%lu\n",
      (unsigned long)sent, (unsigned long)received, (unsigned long)overlapRx,
      (unsigned long)crcErrors, (unsigned long)txErrors,
      (unsigned long)rxErrors, (unsigned long)rejected);
  }
}
