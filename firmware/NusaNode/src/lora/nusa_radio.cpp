#include "nusa_radio.h"
#include <RadioLib.h>
#include <SPI.h>
#include <Preferences.h>
#include "nusa_receiver.h"

// ---------------------------------------------------------------------------
//  Perangkat keras
// ---------------------------------------------------------------------------
static SPIClass loraSpi(VSPI);
static SPISettings loraSpiSettings(8000000, MSBFIRST, SPI_MODE0);
static SX1276 radio = new Module(LORA_CS, LORA_DIO0, LORA_RST, LORA_DIO1,
                                 loraSpi, loraSpiSettings);

// ---------------------------------------------------------------------------
//  Keadaan
// ---------------------------------------------------------------------------
enum RState { R_IDLE, R_RX, R_TX };

static volatile bool  s_irq   = false;   // ditulis dari ISR, dibaca di loop
static RState         s_state = R_IDLE;
static NusaRxHandler  s_handler = nullptr;
static bool           s_healthy = false;

struct TxItem {
  uint8_t  data[NUSA_FRAME_MAX];
  uint8_t  len;
  uint8_t  prio;
  uint32_t queuedAt;
  bool     used;
};
static TxItem   s_q[TXQ_SLOTS];
static uint16_t s_qCount = 0;
static portMUX_TYPE s_queueMux = portMUX_INITIALIZER_UNLOCKED;
// Short critical sections only: no SPI/radio calls while the queue is locked.
struct QueueGuard {
  QueueGuard() { portENTER_CRITICAL(&s_queueMux); }
  ~QueueGuard() { portEXIT_CRITICAL(&s_queueMux); }
};

static float    s_budget      = DUTY_BUDGET_MAX_MS;
static uint32_t s_lastRefill  = 0;
static uint32_t s_txDoneAt    = 0;      // untuk model dwell time
static uint32_t s_holdUntil   = 0;      // backoff / diam wajib
static float    s_pendingAir  = 0;      // airtime frame yang sedang dipancarkan

static uint32_t s_txCount = 0, s_rxCount = 0, s_dropCount = 0;
static uint32_t s_crcErrCount = 0, s_timeoutCount = 0;
static uint32_t s_txErrors = 0, s_txStarted = 0, s_rxDeliveredDuringTx = 0;
static bool s_remoteTx = false, s_nextRemote = false;
static int s_remoteSlot = -1;
static uint32_t s_mainTxCount = 0, s_memberTxCount = 0;
static float    s_rssi = 0, s_snr = 0;

// SF saat berjalan (uji QoS). Permintaan dari callback BLE hanya menyetel
// s_pendingSf; radio baru disentuh di loop() — SPI radio tidak thread-safe.
static uint8_t          s_sf        = LORA_SF;
static volatile uint8_t s_pendingSf = 0;

// ---------------------------------------------------------------------------
//  ISR — hanya boleh menyetel flag. Semua kerja nyata di loop().
// ---------------------------------------------------------------------------
#if defined(ESP8266) || defined(ESP32)
ICACHE_RAM_ATTR
#endif
static void onIrq() { s_irq = true; }

static void deliver(const uint8_t* data, size_t len, float rssi, float snr) {
  NusaFrame frame;
  if (!nusaFrameDecode(data, len, frame)) return;
#if LORA_SEPARATE_RX && !LORA_FULL_DUPLEX && LORA_QUASI_TAKEOVER
  // Both receivers can hear the same frame. Suppress the second local copy.
  struct Seen { uint64_t id; uint8_t idx, hop, flags; uint32_t at; bool used; };
  static Seen seen[32] = {}; static uint8_t cursor = 0;
  for (const auto& item : seen) {
    if (item.used && item.id == frame.msgId && item.idx == frame.fragIdx &&
        item.hop == frame.hop && item.flags == frame.flags && millis() - item.at < 2000) return;
  }
  seen[cursor] = {frame.msgId, frame.fragIdx, frame.hop, frame.flags, millis(), true};
  cursor = (cursor + 1) % 32;
#endif
  if (s_handler) s_handler(frame, rssi, snr);
}

#if LORA_SEPARATE_RX
static bool s_pauseRequested = false;
static uint32_t s_rxWindowStarted = 0;
static void resumeIndependentRx() {
  s_pauseRequested = false; s_rxWindowStarted = 0;
  NusaReceiver::setListening(true);
}
#endif
static void returnToIdle() {
#if LORA_SEPARATE_RX
#if !LORA_FULL_DUPLEX && LORA_QUASI_TAKEOVER
  radio.setPacketReceivedAction(onIrq);
  // Unknown remote completion: keep local RX off until cancellation is ACKed.
  const bool safeRx = NusaReceiver::ready() && NusaReceiver::transmitState() != NusaDuplexWire::TX_BUSY;
  int16_t st = safeRx ? radio.startReceive() : radio.standby();
  s_state = safeRx ? R_RX : R_IDLE;
#else
  int16_t st = radio.standby();
  s_state = R_IDLE;
#endif
  resumeIndependentRx();
#else
  int16_t st = radio.startReceive();
  s_state = R_RX;
#endif
  if (st != RADIOLIB_ERR_NONE) {
    Serial.printf("[RADIO] returnToIdle failed code=%d\n", st);
    s_healthy = false;
  }
}
#if LORA_SEPARATE_RX
static NusaDuplexWire::Config receiverConfig(uint8_t sf) {
  const float freq = LORA_FULL_DUPLEX ? LORA_RX_FREQ : LORA_FREQ;
  return {(uint32_t)lroundf(freq * 1000), (uint32_t)lroundf(LORA_BW * 1000),
    sf, LORA_CR, LORA_SYNC, LORA_PREAMBLE, LORA_POWER};
}
static void onIndependentRx(const uint8_t* data, size_t len, float rssi, float snr) {
  s_rssi = rssi; s_snr = snr; ++s_rxCount;
  // UART delivery during TX is NOT a precision measurement of RF overlap.
  if (s_state == R_TX && !s_irq) ++s_rxDeliveredDuringTx;
  deliver(data, len, rssi, snr);
}
#endif

// ---------------------------------------------------------------------------
//  Airtime — rumus Semtech standar.
//
//  Ini bukan perkiraan kasar: seluruh penjadwalan duty cycle bersandar pada
//  angka ini, jadi ia harus benar.
// ---------------------------------------------------------------------------
float NusaRadio::airtimeMs(size_t len) {
  const float sf = s_sf;
  const float bw = LORA_BW;                     // kHz
  const float tSym = (float)(1UL << s_sf) / bw;   // ms

  // Low Data Rate Optimize wajib aktif saat durasi simbol > 16 ms,
  // yang terjadi pada SF11/SF12 di BW 125 kHz.
  const int de = (tSym > 16.0f) ? 1 : 0;

  const float tPreamble = (LORA_PREAMBLE + 4.25f) * tSym;

  int num = 8 * (int)len - 4 * (int)sf + 28 + 16;   // explicit header
  int den = 4 * ((int)sf - 2 * de);
  int nPayload = (int)ceil((float)num / (float)den) * LORA_CR;   // CR 5..8
  if (nPayload < 0) nPayload = 0;
  nPayload += 8;

  return tPreamble + nPayload * tSym;
}

// ---------------------------------------------------------------------------
//  Anggaran airtime
// ---------------------------------------------------------------------------
static void refillBudget() {
  uint32_t now = millis();
  uint32_t dt  = now - s_lastRefill;
  if (!dt) return;
  s_lastRefill = now;

#if DUTY_ENABLED
  // Batas tabungan minimal satu frame penuh (+10%) pada SF aktif. Tanpa ini, di SF12 (frame 255 B
  // = ±9 s) anggaran tak pernah cukup (maks 8 s) → frame itu tertahan selamanya & node bisu.
  float cap = NusaRadio::airtimeMs(NUSA_FRAME_MAX) * 1.1f;
  if (cap < DUTY_BUDGET_MAX_MS) cap = DUTY_BUDGET_MAX_MS;
  s_budget += dt * DUTY_RATIO;
  if (s_budget > cap) s_budget = cap;
#endif
}

static bool airtimeAllows(float air) {
  uint32_t now = millis();
  if (now < s_holdUntil) return false;

#if DWELL_ENABLED
  if (air > DWELL_MAX_MS) return false;                    // paksa fragmen lebih kecil
  if (now - s_txDoneAt < DWELL_OFF_MIN_MS) return false;   // diam wajib
#endif

#if DUTY_ENABLED
  if (s_budget < air) return false;
#endif

  return true;
}

// ---------------------------------------------------------------------------
//  Antrean
// ---------------------------------------------------------------------------
static int findBest() {
  QueueGuard guard;
  int best = -1;
  uint8_t bestPrio = 255;
  uint32_t oldest = 0;
  for (int i = 0; i < TXQ_SLOTS; i++) {
    if (!s_q[i].used) continue;
    uint32_t age = millis() - s_q[i].queuedAt;
    // Anti-kelaparan: item yang menunggu lebih dari 2 menit dinaikkan satu tingkat.
    uint8_t prio = s_q[i].prio;
    if (age > 120000UL && prio > 0) prio--;
    if (prio < bestPrio || (prio == bestPrio && age > oldest)) {
      bestPrio = prio; oldest = age; best = i;
    }
  }
  return best;
}

bool NusaRadio::enqueue(const uint8_t* frame, size_t len, NusaPrio prio) {
  if (!frame || len == 0 || len > NUSA_FRAME_MAX) return false;
  if (len >= NUSA_FRAME_HDR && (frame[0] & NUSA_FLAG_VOICE) && prio >= PRIO_VOICE) prio = PRIO_VOICE;
  QueueGuard guard;

  int slot = -1;
  for (int i = 0; i < TXQ_SLOTS; i++) {
    if (!s_q[i].used) { slot = i; break; }
  }

  if (slot < 0) { s_dropCount++; return false; }

  memcpy(s_q[slot].data, frame, len);
  s_q[slot].len      = (uint8_t)len;
  s_q[slot].prio     = prio;
  s_q[slot].queuedAt = millis();
  s_q[slot].used     = true;
  s_qCount++;
  return true;
}

uint8_t NusaRadio::enqueuePacket(const uint8_t* packet, size_t len, uint8_t hop, NusaPrio prio) {
  if (!packet || len == 0) return 0;
  return enqueuePacketWithId(packet, len, hop, prio, nusaMsgId(packet, len));
}

uint8_t NusaRadio::enqueuePacketWithId(const uint8_t* packet, size_t len, uint8_t hop,
                                       NusaPrio prio, uint64_t msgId) {
  if (!packet || len == 0 || len > REASM_MAX_BYTES) return 0;
  // Routing header [0xD1,dst,src,next] encloses the original app packet.
  const size_t off = packet[0] == 0xD1 ? 4 : 0;
  bool voice = len >= off + 24 && (packet[off] == 1 || packet[off] == 2) &&
    (packet[off + 11] & 0x08) && (packet[off + 1] == 0x22 ||
    (packet[off + 1] >= 0x05 && packet[off + 1] <= 0x07));
  if (voice && prio >= PRIO_VOICE) prio = PRIO_VOICE;
  const uint8_t total = (len + NUSA_FRAG_MAX_DATA - 1) / NUSA_FRAG_MAX_DATA;
  QueueGuard guard;
  const uint8_t reserve = prio >= PRIO_LOCAL ? 4 : (prio >= PRIO_VOICE ? 2 : 0);
  if (TXQ_SLOTS - s_qCount < total + reserve) { s_dropCount += total; return 0; }
  uint8_t fragment = 0;
  for (int slot = 0; slot < TXQ_SLOTS && fragment < total; ++slot) {
    if (s_q[slot].used) continue;
    const size_t offset = fragment * NUSA_FRAG_MAX_DATA;
    const size_t chunk = min((size_t)NUSA_FRAG_MAX_DATA, len - offset);
    NusaFrame frame;
    frame.version = NUSA_FRAME_VERSION;
    frame.flags = voice ? NUSA_FLAG_VOICE : 0;
    frame.hop = hop;
    frame.msgId = msgId;
    frame.fragIdx = fragment++;
    frame.fragTotal = total;
    frame.data = packet + offset;
    frame.dataLen = chunk;
    s_q[slot].len = nusaFrameEncode(s_q[slot].data, frame);
    s_q[slot].prio = prio;
    s_q[slot].queuedAt = millis();
    s_q[slot].used = true;
    ++s_qCount;
  }
  return total;
}

// ---------------------------------------------------------------------------
//  Init
// ---------------------------------------------------------------------------
bool NusaRadio::begin(NusaRxHandler handler) {
  s_healthy = false;
  s_state = R_IDLE; s_irq = false;
  s_handler = handler;
  for (int i = 0; i < TXQ_SLOTS; i++) s_q[i].used = false;
  s_qCount = 0;

  loraSpi.begin(LORA_SCK, LORA_MISO, LORA_MOSI, LORA_CS);

#if LORA_SF_FIXED
  s_sf = LORA_SF;                         // varian SF tetap: abaikan SF lama di NVS
#else
  {
    Preferences prefs;
    prefs.begin("nusaradio", true);
    uint8_t saved = prefs.getUChar("sf", LORA_SF);
    prefs.end();
    s_sf = (saved >= 7 && saved <= 12) ? saved : LORA_SF;
  }
#endif

  int16_t st = radio.begin(LORA_FREQ, LORA_BW, s_sf, LORA_CR,
                           LORA_SYNC, LORA_POWER, LORA_PREAMBLE);
  if (st != RADIOLIB_ERR_NONE) {
    Serial.printf("[RADIO] init GAGAL, kode %d\n", st);
    Serial.println("[RADIO] Hampir selalu salah pinout. Cek LORA_CS/RST/DIO0 di config.h");
    s_healthy = false;
    return false;
  }

  radio.setCurrentLimit(100);
  radio.setCRC(true);
  #if LORA_SEPARATE_RX
  radio.setPacketSentAction(onIrq);
  st = radio.standby();
  if (st == RADIOLIB_ERR_NONE && !NusaReceiver::begin(receiverConfig(s_sf), onIndependentRx)) {
    Serial.println("[DUPLEX] independent receiver init failed");
    return false;
  }
  #else
  radio.setPacketReceivedAction(onIrq);
  st = radio.startReceive();
  #endif
  if (st != RADIOLIB_ERR_NONE) {
    Serial.printf("[RADIO] startReceive gagal, kode %d\n", st);
    s_healthy = false;
    return false;
  }

  s_state      = LORA_SEPARATE_RX ? R_IDLE : R_RX;
#if LORA_SEPARATE_RX && !LORA_FULL_DUPLEX && LORA_QUASI_TAKEOVER
  // Member might still be transmitting after a main-board reboot.
  // RX starts in loop only after matching configuration/listen acknowledgement.
  radio.setPacketReceivedAction(onIrq);
  s_state = R_IDLE;
#endif
  s_lastRefill = millis();
  s_healthy    = true;

  Serial.printf("[RADIO] SX1276 siap — %.1f MHz SF%d BW%.0f CR4/%d %d dBm\n",
                LORA_FREQ, s_sf, LORA_BW, LORA_CR, LORA_POWER);
  Serial.printf("[RADIO] airtime frame penuh (%d B) = %.0f ms\n",
                NUSA_FRAME_MAX, airtimeMs(NUSA_FRAME_MAX));
#if LORA_SEPARATE_RX
  Serial.printf("[DUPLEX] %s backend=%u TX=%.3f RX=%.3f MHz\n",
    LORA_FULL_DUPLEX ? "FULL_EXPERIMENT" : "QUASI_TWO_RADIOS", LORA_RX_BACKEND,
    (double)LORA_FREQ, (double)(LORA_FULL_DUPLEX ? LORA_RX_FREQ : LORA_FREQ));
#else
  Serial.println("[DUPLEX] HALF: one radio alternates RX/TX");
#endif
  return true;
}

// ---------------------------------------------------------------------------
//  Servis RX
// ---------------------------------------------------------------------------
#if !LORA_SEPARATE_RX || (!LORA_FULL_DUPLEX && LORA_QUASI_TAKEOVER)
static void serviceRx() {
  uint8_t buf[NUSA_FRAME_MAX];
  size_t  len = radio.getPacketLength();
  if (len == 0 || len > NUSA_FRAME_MAX) {
    // IRQ menyala tapi bukan RX paket asli (mis. event lain di DIO0).
    // Dihitung terpisah dari rx supaya tak disalahartikan sebagai "diam total".
    s_timeoutCount++;
    radio.startReceive();
    return;
  }

  int16_t st = radio.readData(buf, len);
  if (st == RADIOLIB_ERR_NONE) {
    s_rssi = radio.getRSSI();
    s_snr  = radio.getSNR();
    s_rxCount++;

    deliver(buf, len, s_rssi, s_snr);
  } else if (st == RADIOLIB_ERR_CRC_MISMATCH) {
    // Frame TIBA tapi rusak. Dihitung (bukan cuma dibuang diam-diam) karena
    // ini beda penyebab dari "tak ada sinyal sama sekali": sering tanda radio
    // kelewat dekat (overload/silau) atau parameter SF/BW/sync tak cocok.
    s_crcErrCount++;
    s_rssi = radio.getRSSI();
    s_snr  = radio.getSNR();
    Serial.printf("[RADIO] frame TIBA tapi CRC gagal — RSSI %.1f SNR %.1f "
                  "(kelewat dekat / mismatch parameter?)\n", s_rssi, s_snr);
  } else {
    Serial.printf("[RADIO] readData kode %d\n", st);
  }

  radio.startReceive();
  s_state = R_RX;
}
#endif

// ---------------------------------------------------------------------------
//  Servis TX
// ---------------------------------------------------------------------------
static void serviceTx() {
  if (s_state == R_TX) return;          // sedang memancar
#if LORA_SEPARATE_RX
  if (!NusaReceiver::ready()) return;
#if LORA_FULL_DUPLEX
  NusaReceiver::setListening(true);
  if (!NusaReceiver::listening(true)) return;
#endif
#endif
  if (NusaRadio::queueDepth() == 0) {
#if LORA_SEPARATE_RX && !LORA_FULL_DUPLEX
    resumeIndependentRx();
#endif
    return;
  }

  int idx = findBest();
  if (idx < 0) return;

  float air = NusaRadio::airtimeMs(s_q[idx].len);
  if (!airtimeAllows(air)) {
#if LORA_SEPARATE_RX && !LORA_FULL_DUPLEX
    resumeIndependentRx();
#endif
    return;
  }

#if LORA_SEPARATE_RX && !LORA_FULL_DUPLEX
  // Quasi-duplex: offer an RX window, request PAUSE, and wait for the
  // companion to acknowledge standby BEFORE starting any local RF TX.
  if (!s_pauseRequested) {
    if (!NusaReceiver::listening(true)) { NusaReceiver::setListening(true); return; }
    if (!s_rxWindowStarted) { s_rxWindowStarted = millis(); return; }
    uint32_t window = max((uint32_t)LORA_RX_WINDOW_MS,
                          (uint32_t)(NusaRadio::airtimeMs(NUSA_FRAME_MAX) + 20));
    if (millis() - s_rxWindowStarted < window) return;
    NusaReceiver::setListening(false); s_pauseRequested = true;
  }
  if (!NusaReceiver::listening(false)) return;
  // Quasi ownership: stop the local receiver as well before either TX.
  if (radio.standby() != RADIOLIB_ERR_NONE) { s_healthy = false; return; }
  s_irq = false; s_state = R_IDLE;
#endif

  // Jitter acak DULU, sebelum menyentuh radio sama sekali: dua node yang
  // bertabrakan tanpa jitter akan mengulang bertabrakan pada percobaan
  // berikutnya. Selama menunggu, radio dibiarkan diam total di mode RX —
  // penting, karena scanChannel() (CAD di bawah) bila dipanggil di SETIAP
  // tick loop() (dulunya terjadi di sini) berbagi pin DIO0 dengan penerima
  // paket dan memicu interupsi RX palsu berulang, membuat node nyaris tuli
  // terhadap tetangganya. Sekarang CAD hanya dipanggil SEKALI, tepat saat
  // jitter habis dan node benar-benar siap mancar.
  static uint32_t jitterUntil = 0;
  if (jitterUntil == 0) {
    jitterUntil = millis() + random(BACKOFF_BASE_MS);
    return;
  }
  if (millis() < jitterUntil) return;
  jitterUntil = 0;

#if CAD_ENABLED
  // CAD pada SX1276 hanya mendeteksi preamble, dan buta terhadap hidden node.
  // Karena itu ia pertahanan pertama, bukan satu-satunya — jitter di atas
  // yang menanggung sisanya.
  int16_t cad = radio.scanChannel();

  // PENTING: scanChannel() (RadioLib SX127x::startChannelScan) me-remap DIO0
  // ke CAD_DONE/CAD_DETECTED lalu polling pin itu sampai berubah — pin FISIK
  // yang sama yang dipantau onIrq() lewat attachInterrupt(). Transisi CAD itu
  // sendiri hampir selalu memicu onIrq() dan menyalakan s_irq, walau tak ada
  // paket yang benar-benar tiba. Kalau flag basi ini dibiarkan, loop() abis
  // ini salah baca: kalau s_state jadi R_TX (channel bersih -> lgsg kirim),
  // finishTransmit() dipanggil PREMATUR sebelum TX asli selesai — pancaran
  // rusak/putus di tengah jalan. Makanya harus dibuang di sini, sebelum
  // dipakai buat mutuskan apa pun.
  s_irq = false;

  if (cad == RADIOLIB_PREAMBLE_DETECTED) {
    s_holdUntil = millis() + BACKOFF_BASE_MS + random(BACKOFF_MAX_MS);
    returnToIdle();
    return;
  }
#endif

  s_remoteTx = false;
#if LORA_SEPARATE_RX && !LORA_FULL_DUPLEX && LORA_QUASI_TAKEOVER
  s_remoteTx = s_nextRemote;
#endif
  int16_t st = RADIOLIB_ERR_NONE;
#if LORA_SEPARATE_RX
  if (s_remoteTx) {
    st = NusaReceiver::startTransmit(s_q[idx].data, s_q[idx].len) ? RADIOLIB_ERR_NONE : RADIOLIB_ERR_UNKNOWN;
  } else
#endif
  { radio.setPacketSentAction(onIrq); st = radio.startTransmit(s_q[idx].data, s_q[idx].len); }
  if (st != RADIOLIB_ERR_NONE) {
    Serial.printf("[RADIO] startTransmit kode %d\n", st);
    ++s_txErrors;
    returnToIdle();
    return;
  }

  s_pendingAir = air;
  s_txStarted  = millis();
  s_state      = R_TX;
  s_nextRemote = !s_remoteTx;
  if (s_remoteTx) s_remoteSlot = idx; // Retain slot until member accepts/rejects RF attempt.
  else { QueueGuard guard; s_q[idx].used = false; s_qCount--; }
}

// ---------------------------------------------------------------------------
//  Loop
// ---------------------------------------------------------------------------
static void applyPendingSf() {
  uint8_t sf = s_pendingSf;
  if (!sf || s_state == R_TX) return;     // tunggu pancaran selesai
  s_pendingSf = 0;
  if (sf == s_sf) return;

  radio.standby();
  int16_t st = radio.setSpreadingFactor(sf);
  if (st == RADIOLIB_ERR_NONE) {
    s_sf = sf;
#if LORA_SEPARATE_RX
    NusaReceiver::configure(receiverConfig(sf));
#endif
    Preferences prefs;
    prefs.begin("nusaradio", false);
    prefs.putUChar("sf", sf);
    prefs.end();
    Serial.printf("[RADIO] SF diganti ke SF%u (airtime frame penuh %.0f ms)\n",
                  sf, NusaRadio::airtimeMs(NUSA_FRAME_MAX));
  } else {
    Serial.printf("[RADIO] ganti SF%u gagal, kode %d\n", sf, st);
  }
  s_irq = false;                           // standby bisa memicu IRQ palsu
  returnToIdle();
}

void NusaRadio::loop() {
  if (!s_healthy) return;

#if LORA_SEPARATE_RX
  // Always drain RX, even when TX is active or companion readiness is lost.
  NusaReceiver::loop();
#endif
  applyPendingSf();
  refillBudget();
#if LORA_SEPARATE_RX && !LORA_FULL_DUPLEX && LORA_QUASI_TAKEOVER
  if (s_state == R_IDLE && !s_pauseRequested && NusaReceiver::ready() && NusaReceiver::listening(true)) {
    radio.setPacketReceivedAction(onIrq);
    if (radio.startReceive() == RADIOLIB_ERR_NONE) s_state = R_RX;
    else s_healthy = false;
  }
#endif

#if LORA_SEPARATE_RX
  if (s_state == R_TX && s_remoteTx && NusaReceiver::transmitState() != NusaDuplexWire::TX_BUSY) {
    const bool rejected = NusaReceiver::transmitState() == NusaDuplexWire::TX_REJECTED;
    if (NusaReceiver::transmitState() == NusaDuplexWire::TX_DONE) { ++s_txCount; ++s_memberTxCount; }
    else ++s_txErrors;
    if (!rejected && s_remoteSlot >= 0) { QueueGuard guard; s_q[s_remoteSlot].used = false; --s_qCount; }
    s_remoteSlot = -1;
    NusaReceiver::finishTransmit();
    if (!rejected) { s_budget -= s_pendingAir; s_txDoneAt = millis(); }
    else s_holdUntil = millis() + BACKOFF_BASE_MS + random(BACKOFF_MAX_MS);
    s_pendingAir = 0;
    s_irq = false; returnToIdle();
  }
#endif

  if (s_irq) {
    s_irq = false;
    if (s_state == R_TX && !s_remoteTx) {
      if (radio.finishTransmit() == RADIOLIB_ERR_NONE) { ++s_txCount; ++s_mainTxCount; }
      else ++s_txErrors;
      s_budget   -= s_pendingAir;         // bayar dari anggaran
      s_txDoneAt  = millis();
      s_pendingAir = 0;
      returnToIdle();
    } else if (s_state != R_TX) {
#if !LORA_SEPARATE_RX || (!LORA_FULL_DUPLEX && LORA_QUASI_TAKEOVER)
      serviceRx();
#endif
    }
  }

  // A missing TX_DONE must not wedge the scheduler forever. Charge airtime
  // conservatively even when completion is unknown; no retry/ACK is invented.
  if (s_state == R_TX && millis() - s_txStarted > (uint32_t)(s_pendingAir * 2 + 1000)) {
    ++s_txErrors; ++s_timeoutCount;
#if LORA_SEPARATE_RX
    if (s_remoteTx) {
      NusaReceiver::cancelTransmit();
      if (s_remoteSlot >= 0) { QueueGuard guard; s_q[s_remoteSlot].used = false; --s_qCount; }
      s_remoteSlot = -1;
    }
#endif
    s_budget -= s_pendingAir; s_pendingAir = 0; s_txDoneAt = millis();
    s_irq = false;
    Serial.println("[RADIO] TX timeout; recover to idle; frame delivery unknown");
    returnToIdle();
  }

  if (s_healthy) serviceTx();
#if LORA_SEPARATE_RX
  static uint32_t reported = 0;
  if (millis() - reported >= 5000) {
    reported = millis();
    Serial.printf("[DUPLEX] rxReady=%u rxDeliveredDuringTx=%lu wireBad=%lu wireDrop=%lu txErr=%lu q=%u mainTx=%lu memberTx=%lu\n",
      NusaReceiver::ready(), (unsigned long)s_rxDeliveredDuringTx,
      (unsigned long)NusaReceiver::wireErrors(), (unsigned long)NusaReceiver::wireDrops(),
      (unsigned long)s_txErrors, queueDepth(), (unsigned long)s_mainTxCount, (unsigned long)s_memberTxCount);
  }
#endif
}

// ---------------------------------------------------------------------------
//  Telemetri
// ---------------------------------------------------------------------------
uint16_t NusaRadio::queueDepth() { QueueGuard guard; return s_qCount; }
float    NusaRadio::budgetMs()   { return s_budget; }
uint32_t NusaRadio::txCount()    { return s_txCount; }
uint32_t NusaRadio::rxCount()    { return s_rxCount; }
uint32_t NusaRadio::dropCount()  { return s_dropCount; }
uint32_t NusaRadio::crcErrCount()   {
#if LORA_SEPARATE_RX
  return s_crcErrCount + NusaReceiver::crcErrors();
#else
  return s_crcErrCount;
#endif
}
uint32_t NusaRadio::timeoutCount()  { return s_timeoutCount; }
float    NusaRadio::lastRssi()   { return s_rssi; }
uint8_t  NusaRadio::spreadingFactor() { return s_sf; }

bool NusaRadio::requestSpreadingFactor(uint8_t sf) {
#if LORA_SF_FIXED
  (void)sf;
  return false;                           // varian SF tetap: ganti SF = flash folder lain
#endif
  if (sf < 7 || sf > 12) return false;
  s_pendingSf = sf;
  return true;
}
float    NusaRadio::lastSnr()    { return s_snr; }
bool     NusaRadio::healthy()    {
#if LORA_SEPARATE_RX
  return s_healthy && NusaReceiver::ready() &&
    (!LORA_FULL_DUPLEX || NusaReceiver::listening(true));
#else
  return s_healthy;
#endif
}
