#include "nusa_receiver.h"
#if LORA_SEPARATE_RX
#include <Arduino.h>
#include <math.h>
#if LORA_RX_BACKEND == 1
#include <HardwareSerial.h>
#include <esp_system.h>
#else
#include <RadioLib.h>
#include <SPI.h>
#endif

namespace {
using namespace NusaDuplexWire;
NusaReceiver::Handler callback = nullptr;
uint32_t crcCount = 0, dropCount = 0;
bool configured = false;
bool wantedListening = true, acknowledgedListening = false;
uint16_t txToken = 0;
TxState txState = TX_IDLE;
bool txPending = false, cancelling = false;
#if LORA_RX_BACKEND == 1
HardwareSerial companion(2);
uint32_t lastCancel = 0;
Parser parser;
Config desired;
uint16_t sequence = 0, digest = 0;
uint32_t lastStatus = 0, lastSend = 0;
bool sent = false;
uint16_t gateToken = 1, acknowledgedGate = 0;
uint32_t lastGateSend = 0;
bool gateSent = false;
void sendCancel() {
  uint8_t payload[2], wire[MAX_ENCODED]; put16(payload, txToken);
  size_t len = encode(MSG_CANCEL, sequence, payload, sizeof(payload), wire, sizeof(wire));
  if (companion.availableForWrite() >= (int)len && companion.write(wire, len) == len) lastCancel = millis();
}
void sendGate() {
  uint8_t payload[3], wire[MAX_ENCODED];
  payload[0] = wantedListening; put16(payload + 1, gateToken);
  size_t len = encode(MSG_LISTEN, sequence, payload, sizeof(payload), wire, sizeof(wire));
  if (companion.availableForWrite() < (int)len) return;
  if (companion.write(wire, len) == len) { gateSent = true; lastGateSend = millis(); }
}
bool sendConfig() {
  uint8_t payload[CONFIG_SIZE], wire[MAX_ENCODED];
  packConfig(payload, desired);
  size_t len = encode(MSG_CONFIG, sequence, payload, sizeof(payload), wire, sizeof(wire));
  if (!len || companion.availableForWrite() < (int)len) return false;
  if (companion.write(wire, len) != len) return false;
  sent = true; lastSend = millis(); return true;
}
#else
SPIClass receiverSpi(HSPI);
SPISettings receiverSettings(4000000, MSBFIRST, SPI_MODE0);
SX1276 receiver = new Module(LORA_EXT_CS, LORA_EXT_DIO0, LORA_EXT_RST,
                             RADIOLIB_NC, receiverSpi, receiverSettings);
volatile bool rxIrq = false;
void IRAM_ATTR onRxIrq() { rxIrq = true; }
#endif
}

bool NusaReceiver::begin(const NusaDuplexWire::Config& config, Handler handler) {
  callback = handler;
#if LORA_RX_BACKEND == 1
  companion.setRxBufferSize(4096);
  companion.setTxBufferSize(2048);
  companion.begin(LORA_COMPANION_BAUD, SERIAL_8N1, LORA_COMPANION_RX, LORA_COMPANION_TX);
  sequence = (uint16_t)esp_random();
  configure(config);
  Serial.printf("[DUPLEX] UART2 TX%d RX%d %lu baud; waiting companion MSG_CONFIG ACK\n",
    LORA_COMPANION_TX, LORA_COMPANION_RX, (unsigned long)LORA_COMPANION_BAUD);
  return true; // readiness is asynchronous; TX remains held until ACK.
#else
  pinMode(LORA_EXT_CS, OUTPUT); digitalWrite(LORA_EXT_CS, HIGH);
  receiverSpi.begin(LORA_EXT_SCK, LORA_EXT_MISO, LORA_EXT_MOSI, LORA_EXT_CS);
  configure(config);
  return configured;
#endif
}
void NusaReceiver::configure(const NusaDuplexWire::Config& config) {
  configured = false;
  wantedListening = true; acknowledgedListening = false;
#if LORA_RX_BACKEND == 1
  gateToken = 1; acknowledgedGate = 0; gateSent = false;
  desired = config;
  if (++sequence == 0) ++sequence;
  uint8_t payload[CONFIG_SIZE]; packConfig(payload, desired);
  digest = crc16(payload, sizeof(payload));
  sent = false;
  sendConfig();
#else
  receiver.standby(); rxIrq = false;
  int16_t st = receiver.begin(config.freqKhz / 1000.0f, config.bwHz / 1000.0f,
    config.sf, config.cr, config.sync, config.power, config.preamble);
  if (st == RADIOLIB_ERR_NONE) st = receiver.setCRC(true);
  receiver.setPacketReceivedAction(onRxIrq);
  if (st == RADIOLIB_ERR_NONE) st = receiver.startReceive();
  configured = st == RADIOLIB_ERR_NONE;
  acknowledgedListening = configured;
  Serial.printf("[DUPLEX] external SPI RX config code=%d ready=%u\n", st, configured);
#endif
}
void NusaReceiver::setListening(bool enabled) {
  if (wantedListening == enabled) return;
  wantedListening = enabled;
#if LORA_RX_BACKEND == 1
  if (++gateToken == 0) ++gateToken;
  gateSent = false;
  if (ready()) sendGate();
#else
  rxIrq = false;
  int16_t st = enabled ? receiver.startReceive() : receiver.standby();
  if (st == RADIOLIB_ERR_NONE) acknowledgedListening = enabled;
  else configured = false;
#endif
}
bool NusaReceiver::listening(bool enabled) {
  if (!ready() || wantedListening != enabled || acknowledgedListening != enabled) return false;
#if LORA_RX_BACKEND == 1
  return acknowledgedGate == gateToken;
#else
  return true;
#endif
}
bool NusaReceiver::ready() {
#if LORA_RX_BACKEND == 1
  return configured && !cancelling && millis() - lastStatus < LORA_COMPANION_STALE_MS;
#else
  return configured;
#endif
}
bool NusaReceiver::startTransmit(const uint8_t* data, size_t len) {
  if (!data || !len || len > 255 || txPending || !listening(false)) return false;
  if (++txToken == 0) ++txToken;
#if LORA_RX_BACKEND == 1
  uint8_t payload[257], wire[MAX_ENCODED]; put16(payload, txToken); memcpy(payload + 2, data, len);
  size_t size = encode(MSG_TX, sequence, payload, len + 2, wire, sizeof(wire));
  if (companion.availableForWrite() < (int)size || companion.write(wire, size) != size) return false;
#else
  rxIrq = false; receiver.setPacketSentAction(onRxIrq);
  int16_t cad = receiver.scanChannel(); rxIrq = false;
  if (cad != RADIOLIB_CHANNEL_FREE) return false;
  if (receiver.startTransmit(const_cast<uint8_t*>(data), len) != RADIOLIB_ERR_NONE) return false;
#endif
  txPending = true; txState = TX_BUSY;
  return true;
}
NusaDuplexWire::TxState NusaReceiver::transmitState() { return txState; }
void NusaReceiver::cancelTransmit() {
  if (!txPending) return;
#if LORA_RX_BACKEND == 1
  cancelling = true; sendCancel();
#else
  configured = receiver.standby() == RADIOLIB_ERR_NONE;
  rxIrq = false; txState = TX_UNKNOWN; txPending = false;
  receiver.setPacketReceivedAction(onRxIrq);
#endif
}
void NusaReceiver::finishTransmit() {
  if (txState == TX_BUSY || cancelling) return;
  txPending = false;
#if LORA_RX_BACKEND == 2
  receiver.setPacketReceivedAction(onRxIrq);
#endif
}
void NusaReceiver::loop() {
#if LORA_RX_BACKEND == 1
  Message message;
  for (size_t i = 0; i < 2048 && companion.available(); ++i) {
    if (!parser.feed((uint8_t)companion.read(), message)) continue;
    if (message.type == MSG_STATUS && message.len == STATUS_SIZE) {
      bool matches = message.seq == sequence && get16(message.data) == digest;
      bool wasReady = ready();
      configured = matches && message.data[4] == 1 && (int16_t)get16(message.data + 2) == 0;
      if (configured) {
        lastStatus = millis();
        crcCount = get32(message.data + 9);
        dropCount = get32(message.data + 13);
        acknowledgedListening = message.data[17] == 1;
        acknowledgedGate = get16(message.data + 18);
        if (txPending && get16(message.data + 20) == txToken && message.data[22] <= TX_REJECTED) {
          txState = (TxState)message.data[22];
          if (txState != TX_BUSY) { cancelling = false; txPending = false; }
        } else if (txPending && get16(message.data + 20) == 0 && acknowledgedGate == 0) {
          // Matching CONFIG ACK after a reboot proves the chip is in standby.
          txState = TX_UNKNOWN; cancelling = false; txPending = false;
        }
      }
      if (configured != wasReady) Serial.printf("[DUPLEX] companion ready=%u configSeq=%u code=%d\n",
        configured, message.seq, (int16_t)get16(message.data + 2));
    } else if (message.type == MSG_RX && message.seq == sequence && ready() && acknowledgedListening && message.len >= 5 &&
               message.data[0] && message.len == (size_t)message.data[0] + 5) {
      if (callback) callback(message.data + 5, message.data[0],
        (int16_t)get16(message.data + 1) / 10.0f, (int16_t)get16(message.data + 3) / 10.0f);
    }
  }
  if (cancelling) { if (millis() - lastCancel >= 500) sendCancel(); }
  if ((!configured || millis() - lastStatus >= LORA_COMPANION_STALE_MS) && (!sent || millis() - lastSend >= 1000)) sendConfig();
  else if (ready() && !listening(wantedListening) && (!gateSent || millis() - lastGateSend >= 1000)) sendGate();
#else
  if (txPending) {
    if (rxIrq) {
      rxIrq = false;
      txState = receiver.finishTransmit() == RADIOLIB_ERR_NONE ? TX_DONE : TX_FAILED;
      if (receiver.standby() != RADIOLIB_ERR_NONE) configured = false;
      txPending = false;
    }
    return;
  }
  if (!configured || !acknowledgedListening || !rxIrq) return;
  rxIrq = false;
  uint8_t data[255]; size_t len = receiver.getPacketLength();
  int16_t st = len && len <= sizeof(data) ? receiver.readData(data, len) : RADIOLIB_ERR_UNKNOWN;
  float rssi = receiver.getRSSI(), snr = receiver.getSNR();
  if (receiver.startReceive() != RADIOLIB_ERR_NONE) configured = false;
  if (st == RADIOLIB_ERR_CRC_MISMATCH) ++crcCount;
  if (st == RADIOLIB_ERR_NONE && callback) callback(data, len, rssi, snr);
#endif
}
uint32_t NusaReceiver::crcErrors() { return crcCount; }
uint32_t NusaReceiver::wireErrors() {
#if LORA_RX_BACKEND == 1
  return parser.errors;
#else
  return 0;
#endif
}
uint32_t NusaReceiver::wireDrops() { return dropCount; }
#endif // LORA_SEPARATE_RX
