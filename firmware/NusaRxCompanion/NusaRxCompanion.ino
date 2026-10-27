// Second LilyGO = coordinated RX/TX radio member of ONE NusaNode hub.
// Main TX13 -> companion RX34; companion TX13 -> main RX34; GND common.
// Power both by their own USB. Do not connect their 5V pins together.
#include <Arduino.h>
#include <SPI.h>
#include <RadioLib.h>
#include <HardwareSerial.h>
#include <math.h>
#include "src/protocol/nusa_duplex_wire.h"
using namespace NusaDuplexWire;
HardwareSerial uplink(2);
SPIClass radioSpi(VSPI);
SPISettings settings(8000000, MSBFIRST, SPI_MODE0);
SX1276 radio = new Module(18, 26, 23, 33, radioSpi, settings);
Parser parser;
volatile bool rxIrq = false;
bool radioReady = false;
bool listening = false;
uint16_t gateToken = 0;
uint16_t configSeq = 0, configDigest = 0;
int16_t configError = -999;
uint32_t received = 0, crcErrors = 0, uartDrops = 0, lastStatus = 0;
uint16_t txToken = 0;
TxState txState = TX_IDLE;
int16_t txError = 0;
uint32_t txStarted = 0, txTimeout = 0;
void IRAM_ATTR onRxIrq() { rxIrq = true; }
bool sendMessage(uint8_t type, const uint8_t* payload, size_t size) {
  uint8_t encoded[MAX_ENCODED];
  size_t len = encode(type, configSeq, payload, size, encoded, sizeof(encoded));
  if (!len || uplink.availableForWrite() < (int)len) return false;
  return uplink.write(encoded, len) == len;
}
void sendStatus() {
  uint8_t status[STATUS_SIZE];
  put16(status, configDigest); put16(status + 2, (uint16_t)configError);
  status[4] = radioReady;
  put32(status + 5, received); put32(status + 9, crcErrors); put32(status + 13, uartDrops);
  status[17] = listening; put16(status + 18, gateToken);
  put16(status + 20, txToken); status[22] = txState; put16(status + 23, (uint16_t)txError);
  sendMessage(MSG_STATUS, status, sizeof(status));
  lastStatus = millis();
}
void configureRadio(const Message& message) {
  Config config;
  if (!message.seq || !unpackConfig(message.data, message.len, config)) return;
  uint16_t digest = crc16(message.data, message.len);
  if (radioReady && message.seq == configSeq && digest == configDigest) { sendStatus(); return; }
  // A new session/configuration cancels old RF activity before acknowledging.
  radio.standby(); radioReady = false; rxIrq = false;
  txToken = 0; txState = TX_IDLE; txError = 0;
  configSeq = message.seq; configDigest = digest;
  // begin() initializes the chip when the first configuration arrives.
  configError = radio.begin(config.freqKhz / 1000.0f, config.bwHz / 1000.0f,
    config.sf, config.cr, config.sync, config.power, config.preamble);
  if (configError == RADIOLIB_ERR_NONE) configError = radio.setCRC(true);
  radio.setPacketReceivedAction(onRxIrq);
  // Start paused: a reboot during main TX must not silently enable RF RX.
  if (configError == RADIOLIB_ERR_NONE) configError = radio.standby();
  radioReady = configError == RADIOLIB_ERR_NONE;
  listening = false; gateToken = 0;
  Serial.printf("[COMPANION] config seq=%u RX=%.3f SF%u ready=%u code=%d\n",
    configSeq, config.freqKhz / 1000.0f, config.sf, radioReady, configError);
  sendStatus();
}
void setListening(const Message& message) {
  if (!radioReady || message.seq != configSeq || message.len != 3 || message.data[0] > 1) return;
  if (txState == TX_BUSY) { sendStatus(); return; }
  uint16_t token = get16(message.data + 1);
  if (!token) return;
  bool enabled = message.data[0] == 1;
  if (token != gateToken || listening != enabled) {
    rxIrq = false;
    if (enabled) radio.setPacketReceivedAction(onRxIrq);
    configError = enabled ? radio.startReceive() : radio.standby();
    if (configError == RADIOLIB_ERR_NONE) { listening = enabled; gateToken = token; }
    else { radioReady = false; listening = false; }
  }
  sendStatus();
}
void transmit(const Message& message) {
  if (!radioReady || message.seq != configSeq || message.len < 3 || message.len > 257) return;
  uint16_t token = get16(message.data);
  if (!token) return;
  // One attempt per token; duplicate UART commands never repeat RF TX.
  if (token == txToken) { sendStatus(); return; }
  if (txToken && (int16_t)(token - txToken) <= 0) { sendStatus(); return; }
  if (listening || txState == TX_BUSY || !gateToken) { sendStatus(); return; }
  txToken = token; rxIrq = false;
  radio.setPacketSentAction(onRxIrq);
  int16_t cad = radio.scanChannel(); rxIrq = false;
  txError = cad == RADIOLIB_PREAMBLE_DETECTED ? cad :
    (cad == RADIOLIB_CHANNEL_FREE ? radio.startTransmit(const_cast<uint8_t*>(message.data + 2), message.len - 2) : cad);
  txState = txError == RADIOLIB_ERR_NONE ? TX_BUSY : TX_REJECTED;
  if (txState == TX_REJECTED) {
    if (radio.standby() != RADIOLIB_ERR_NONE) radioReady = false;
    radio.setPacketReceivedAction(onRxIrq);
  }
  txStarted = millis(); txTimeout = radio.getTimeOnAir(message.len - 2) / 1000UL * 2 + 1000UL;
  sendStatus();
}
void cancel(const Message& message) {
  if (!radioReady || message.seq != configSeq || message.len != 2) return;
  uint16_t token = get16(message.data);
  if (!token || (txToken && token != txToken && (int16_t)(token - txToken) <= 0)) return;
  // Also acknowledge a command that never arrived: cancel must close that token.
  if (token != txToken || txState == TX_BUSY) {
    txError = radio.standby(); rxIrq = false;
    txToken = token; txState = TX_UNKNOWN; listening = false;
    if (txError != RADIOLIB_ERR_NONE) radioReady = false;
  }
  sendStatus();
}
void serviceTx() {
  if (txState != TX_BUSY) return;
  if (rxIrq) {
    rxIrq = false; txError = radio.finishTransmit();
    txState = txError == RADIOLIB_ERR_NONE ? TX_DONE : TX_FAILED;
    if (radio.standby() != RADIOLIB_ERR_NONE) radioReady = false;
    sendStatus();
  } else if (millis() - txStarted > txTimeout) {
    txError = radio.standby(); rxIrq = false; txState = TX_UNKNOWN;
    if (txError != RADIOLIB_ERR_NONE) radioReady = false;
    sendStatus();
  }
  if (txState != TX_BUSY) radio.setPacketReceivedAction(onRxIrq);
}
void serviceRx() {
  if (!radioReady || !listening || !rxIrq) return;
  rxIrq = false;
  uint8_t payload[260]; size_t len = radio.getPacketLength();
  int16_t st = len && len <= 255 ? radio.readData(payload + 5, len) : RADIOLIB_ERR_UNKNOWN;
  float rssi = radio.getRSSI(), snr = radio.getSNR();
  int16_t rearm = radio.startReceive();
  if (rearm != RADIOLIB_ERR_NONE) { radioReady = false; configError = rearm; sendStatus(); }
  if (st == RADIOLIB_ERR_CRC_MISMATCH) { ++crcErrors; return; }
  if (st != RADIOLIB_ERR_NONE) return;
  ++received;
  payload[0] = len;
  put16(payload + 1, (uint16_t)(int16_t)lroundf(rssi * 10));
  put16(payload + 3, (uint16_t)(int16_t)lroundf(snr * 10));
  if (!sendMessage(MSG_RX, payload, len + 5)) ++uartDrops;
}
void setup() {
  Serial.begin(115200); delay(300);
  pinMode(18, OUTPUT); digitalWrite(18, HIGH);
  radioSpi.begin(5, 19, 27, 18);
  uplink.setRxBufferSize(4096); uplink.setTxBufferSize(2048);
  uplink.begin(115200, SERIAL_8N1, 34, 13);
  Serial.println("[COMPANION] quasi RX/TX member; UART2 TX13 RX34; waiting hub configuration; wire v3");
}
void loop() {
  serviceTx();
  serviceRx();
  Message message;
  for (size_t i = 0; i < 2048 && uplink.available(); ++i) {
    if (!parser.feed((uint8_t)uplink.read(), message)) continue;
    if (message.type == MSG_CONFIG) configureRadio(message);
    else if (message.type == MSG_LISTEN) setListening(message);
    else if (message.type == MSG_TX) transmit(message);
    else if (message.type == MSG_CANCEL) cancel(message);
  }
  if (millis() - lastStatus >= 1000) sendStatus();
}
