#include "nusa_radio.h"
#include <RadioLib.h>
#include <SPI.h>
#include <Preferences.h>

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
  const uint8_t total = (len + NUSA_FRAG_MAX_DATA - 1) / NUSA_FRAG_MAX_DATA;
  QueueGuard guard;
  const uint8_t reserve = prio >= PRIO_LOCAL ? 2 : 0;
  if (TXQ_SLOTS - s_qCount < total + reserve) { s_dropCount += total; return 0; }
  uint8_t fragment = 0;
  for (int slot = 0; slot < TXQ_SLOTS && fragment < total; ++slot) {
    if (s_q[slot].used) continue;
    const size_t offset = fragment * NUSA_FRAG_MAX_DATA;
    const size_t chunk = min((size_t)NUSA_FRAG_MAX_DATA, len - offset);
    NusaFrame frame;
    frame.version = NUSA_FRAME_VERSION;
    frame.flags = 0;
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
  radio.setPacketReceivedAction(onIrq);

  st = radio.startReceive();
  if (st != RADIOLIB_ERR_NONE) {
    Serial.printf("[RADIO] startReceive gagal, kode %d\n", st);
    s_healthy = false;
    return false;
  }

  s_state      = R_RX;
  s_lastRefill = millis();
  s_healthy    = true;

  Serial.printf("[RADIO] SX1276 siap — %.1f MHz SF%d BW%.0f CR4/%d %d dBm\n",
                LORA_FREQ, s_sf, LORA_BW, LORA_CR, LORA_POWER);
  Serial.printf("[RADIO] airtime frame penuh (%d B) = %.0f ms\n",
                NUSA_FRAME_MAX, airtimeMs(NUSA_FRAME_MAX));
  return true;
}

// ---------------------------------------------------------------------------
//  Servis RX
// ---------------------------------------------------------------------------
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

    NusaFrame f;
    if (nusaFrameDecode(buf, len, f) && s_handler) {
      s_handler(f, s_rssi, s_snr);
    }
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

// ---------------------------------------------------------------------------
//  Servis TX
// ---------------------------------------------------------------------------
static void serviceTx() {
  if (s_state == R_TX) return;          // sedang memancar
  if (NusaRadio::queueDepth() == 0) return;

  int idx = findBest();
  if (idx < 0) return;

  float air = NusaRadio::airtimeMs(s_q[idx].len);
  if (!airtimeAllows(air)) return;

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
    radio.startReceive();
    s_state = R_RX;
    return;
  }
#endif

  int16_t st = radio.startTransmit(s_q[idx].data, s_q[idx].len);
  if (st != RADIOLIB_ERR_NONE) {
    Serial.printf("[RADIO] startTransmit kode %d\n", st);
    radio.startReceive();
    s_state = R_RX;
    return;
  }

  s_pendingAir = air;
  s_state      = R_TX;
  { QueueGuard guard; s_q[idx].used = false; s_qCount--; }
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
  radio.startReceive();
  s_state = R_RX;
}

void NusaRadio::loop() {
  if (!s_healthy) return;

  applyPendingSf();
  refillBudget();

  if (s_irq) {
    s_irq = false;
    if (s_state == R_TX) {
      radio.finishTransmit();
      s_budget   -= s_pendingAir;         // bayar dari anggaran
      s_txDoneAt  = millis();
      s_txCount++;
      s_pendingAir = 0;
      radio.startReceive();
      s_state = R_RX;
    } else {
      serviceRx();
    }
  }

  serviceTx();
}

// ---------------------------------------------------------------------------
//  Telemetri
// ---------------------------------------------------------------------------
uint16_t NusaRadio::queueDepth() { QueueGuard guard; return s_qCount; }
float    NusaRadio::budgetMs()   { return s_budget; }
uint32_t NusaRadio::txCount()    { return s_txCount; }
uint32_t NusaRadio::rxCount()    { return s_rxCount; }
uint32_t NusaRadio::dropCount()  { return s_dropCount; }
uint32_t NusaRadio::crcErrCount()   { return s_crcErrCount; }
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
bool     NusaRadio::healthy()    { return s_healthy; }
