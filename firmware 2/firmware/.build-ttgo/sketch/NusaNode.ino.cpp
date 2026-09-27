#include <Arduino.h>
#line 1 "C:\\Users\\darma\\OneDrive\\Dokumen\\taa\\firmware 2\\firmware\\NusaNode\\NusaNode.ino"
// ============================================================================
//  NusaOS — Firmware Nusa Node
//  Board: LILYGO LoRa32 T3 v1.6.1 (ESP32 + SX1276) @ 923 MHz
//
//  Peran node: RELAY BODOH.
//  Ia menambah jangkauan, bukan menambah wewenang. Payload NusaMesh lewat
//  begitu saja dalam keadaan terenkripsi Noise — node tidak memegang kunci,
//  tidak menyimpan plaintext, dan tidak pernah menjadi otoritas.
//
//  Dua sisi dalam satu ESP32:
//    - Sisi user     : WiFi SoftAP + WebSocket biner  (nusa_link)
//    - Sisi backbone : LoRa SX1276 half-duplex        (nusa_radio)
//
//  Sebelum flash, ubah NODE_ID di config.h agar unik per unit.
// ============================================================================

#include "config.h"
#include "nusa_frame.h"
#include "nusa_appproto.h"
#include "nusa_radio.h"
#include "nusa_fragment.h"
#include "nusa_ui.h"

// Sisi user: BLE (Opsi A, menyatu dengan mesh HP) atau WiFi AP. Lihat config.h.
#if USER_LINK_BLE
  #include "nusa_ble.h"
  namespace UserLink = NusaBle;
#else
  #include "nusa_link.h"
  namespace UserLink = NusaLink;
#endif

// ---------------------------------------------------------------------------
//  Keadaan global
// ---------------------------------------------------------------------------
static NusaDedup s_dedup;

// Jam epoch node (dideklarasikan extern di nusa_appproto.h). Diserap dari
// timestamp paket HP pertama yang masuk — node tak punya RTC sendiri.
int64_t g_epochOffsetMs = 0;
bool    g_clockSynced   = false;

static bool     s_rangeTest   = RANGETEST_DEFAULT;
static uint32_t s_beaconSeq   = 0;
static uint32_t s_beaconRecv  = 0;
static uint32_t s_lastBeaconTx = 0;
static uint32_t s_lastBeaconRx = 0;
static float    s_beaconRssi  = 0, s_beaconSnr = 0;

static uint32_t s_lastStatus = 0, s_lastUi = 0, s_lastAnnounce = 0;
static uint32_t s_fromUsers = 0, s_toUsers = 0, s_relayed = 0;

// ---------------------------------------------------------------------------
//  LED indikator terima LoRa — bukti visual langsung tanpa buka serial.
//  Non-blocking: cukup catat kapan harus mati, dimatikan dari loop().
// ---------------------------------------------------------------------------
static uint32_t s_ledOffAt = 0;

#line 59 "C:\\Users\\darma\\OneDrive\\Dokumen\\taa\\firmware 2\\firmware\\NusaNode\\NusaNode.ino"
static void ledPulse();
#line 64 "C:\\Users\\darma\\OneDrive\\Dokumen\\taa\\firmware 2\\firmware\\NusaNode\\NusaNode.ino"
static void serviceLed();
#line 90 "C:\\Users\\darma\\OneDrive\\Dokumen\\taa\\firmware 2\\firmware\\NusaNode\\NusaNode.ino"
static void recordNeighbor(uint8_t id, float rssi, float snr);
#line 122 "C:\\Users\\darma\\OneDrive\\Dokumen\\taa\\firmware 2\\firmware\\NusaNode\\NusaNode.ino"
static uint8_t neighborCount();
#line 131 "C:\\Users\\darma\\OneDrive\\Dokumen\\taa\\firmware 2\\firmware\\NusaNode\\NusaNode.ino"
static bool bestNeighbor(float* outRssi, float* outSnr);
#line 145 "C:\\Users\\darma\\OneDrive\\Dokumen\\taa\\firmware 2\\firmware\\NusaNode\\NusaNode.ino"
static void printNeighbors();
#line 164 "C:\\Users\\darma\\OneDrive\\Dokumen\\taa\\firmware 2\\firmware\\NusaNode\\NusaNode.ino"
static void onPacketFromUser(const uint8_t* pkt, size_t len, uint8_t clientId);
#line 261 "C:\\Users\\darma\\OneDrive\\Dokumen\\taa\\firmware 2\\firmware\\NusaNode\\NusaNode.ino"
static void onPacketComplete(uint64_t msgId, uint8_t hop, const uint8_t* pkt, size_t len);
#line 297 "C:\\Users\\darma\\OneDrive\\Dokumen\\taa\\firmware 2\\firmware\\NusaNode\\NusaNode.ino"
static void onLoraFrame(const NusaFrame& f, float rssi, float snr);
#line 338 "C:\\Users\\darma\\OneDrive\\Dokumen\\taa\\firmware 2\\firmware\\NusaNode\\NusaNode.ino"
static void sendBeacon();
#line 367 "C:\\Users\\darma\\OneDrive\\Dokumen\\taa\\firmware 2\\firmware\\NusaNode\\NusaNode.ino"
static void serviceButton();
#line 386 "C:\\Users\\darma\\OneDrive\\Dokumen\\taa\\firmware 2\\firmware\\NusaNode\\NusaNode.ino"
void setup();
#line 442 "C:\\Users\\darma\\OneDrive\\Dokumen\\taa\\firmware 2\\firmware\\NusaNode\\NusaNode.ino"
void loop();
#line 59 "C:\\Users\\darma\\OneDrive\\Dokumen\\taa\\firmware 2\\firmware\\NusaNode\\NusaNode.ino"
static void ledPulse() {
  digitalWrite(LED_PIN, HIGH);
  s_ledOffAt = millis() + LED_BLINK_MS;
}

static void serviceLed() {
  if (s_ledOffAt && (int32_t)(millis() - s_ledOffAt) >= 0) {
    digitalWrite(LED_PIN, LOW);
    s_ledOffAt = 0;
  }
}

// ---------------------------------------------------------------------------
//  Tabel tetangga LoRa — node lain yang bisa kita dengar LANGSUNG (1 hop).
//
//  Diisi dari beacon HELLO yang tiap node siarkan berkala (NEIGHBOR_HELLO_MS).
//  Inilah yang membuat node "tahu tersambung ke siapa": kalau Node B muncul di
//  tabel Node A dengan RSSI wajar, backbone LoRa antar keduanya hidup. Kalau tak
//  pernah muncul, mereka di luar jangkauan — pesan takkan menyeberang, dan itu
//  bukan salah aplikasi.
// ---------------------------------------------------------------------------
struct Neighbor {
  uint8_t  id;
  float    rssi;
  float    snr;
  uint32_t lastSeen;   // millis() saat terakhir terdengar
  uint32_t count;      // total HELLO diterima dari node ini
  bool     used;
};
static Neighbor s_neighbors[MAX_NEIGHBORS];

static void recordNeighbor(uint8_t id, float rssi, float snr) {
  if (id == 0 || id == NODE_ID) return;   // abaikan tak dikenal & diri sendiri
  uint32_t now = millis();

  int slot = -1;
  for (int i = 0; i < MAX_NEIGHBORS; i++) {
    if (s_neighbors[i].used && s_neighbors[i].id == id) { slot = i; break; }
    if (!s_neighbors[i].used && slot < 0) slot = i;     // slot kosong pertama
  }
  // Tabel penuh & node ini belum tercatat → gantikan yang paling lama sunyi.
  if (slot < 0) {
    uint32_t oldest = 0;
    for (int i = 0; i < MAX_NEIGHBORS; i++) {
      uint32_t age = now - s_neighbors[i].lastSeen;
      if (age >= oldest) { oldest = age; slot = i; }
    }
  }

  bool baru = !s_neighbors[slot].used || s_neighbors[slot].id != id;
  s_neighbors[slot].count    = baru ? 1 : s_neighbors[slot].count + 1;
  s_neighbors[slot].id       = id;
  s_neighbors[slot].rssi     = rssi;
  s_neighbors[slot].snr      = snr;
  s_neighbors[slot].lastSeen = now;
  s_neighbors[slot].used     = true;

  if (baru) {
    Serial.printf("[TETANGGA] node %u BARU terdengar — RSSI %.0f dBm, SNR %.1f dB\n",
                  id, rssi, snr);
  }
}

static uint8_t neighborCount() {
  uint8_t n = 0;
  for (int i = 0; i < MAX_NEIGHBORS; i++) if (s_neighbors[i].used) n++;
  return n;
}

// Tetangga dengan RSSI terkuat (paling deket/paling reliable) — dipakai buat
// lapor kualitas backbone LoRa ke HP lewat LORA-HEALTH. Tetangga basi (lebih
// lama dari NEIGHBOR_STALE_MS) tidak dihitung, sama seperti printNeighbors().
static bool bestNeighbor(float* outRssi, float* outSnr) {
  uint32_t now = millis();
  int best = -1;
  for (int i = 0; i < MAX_NEIGHBORS; i++) {
    if (!s_neighbors[i].used) continue;
    if ((now - s_neighbors[i].lastSeen) > NEIGHBOR_STALE_MS) continue;
    if (best < 0 || s_neighbors[i].rssi > s_neighbors[best].rssi) best = i;
  }
  if (best < 0) return false;
  *outRssi = s_neighbors[best].rssi;
  *outSnr  = s_neighbors[best].snr;
  return true;
}

static void printNeighbors() {
  uint32_t now = millis();
  bool any = false;
  for (int i = 0; i < MAX_NEIGHBORS; i++) {
    if (!s_neighbors[i].used) continue;
    uint32_t agoMs = now - s_neighbors[i].lastSeen;
    bool basi = agoMs > NEIGHBOR_STALE_MS;
    Serial.printf("[TETANGGA] node %u: RSSI %.0f dBm  SNR %.1f dB  %lus lalu  (x%lu)%s\n",
                  s_neighbors[i].id, s_neighbors[i].rssi, s_neighbors[i].snr,
                  (unsigned long)(agoMs / 1000), (unsigned long)s_neighbors[i].count,
                  basi ? "  [BASI]" : "");
    any = true;
  }
  if (!any) Serial.println("[TETANGGA] (belum ada node lain terdengar via LoRa)");
}

// ---------------------------------------------------------------------------
//  Sisi user → backbone
// ---------------------------------------------------------------------------
static void onPacketFromUser(const uint8_t* pkt, size_t len, uint8_t clientId) {
  s_fromUsers++;

  if (!pkt || len == 0) return;

  // Lepas padding traffic-analysis-resistance app SEBELUM apa pun lain —
  // msgId, broadcast lokal, dan fragmentasi ke LoRa semua ikut memakai
  // panjang yang sudah dipangkas. Lihat nusaStripPadding() di nusa_appproto.h.
  len = nusaStripPadding(pkt, len);

  if (len == 0 || len > REASM_MAX_BYTES) return;
#if USER_LINK_BLE
  if (len > BLE_APP_PACKET_MAX) return;
#endif

  uint64_t msgId = nusaMsgId(pkt, len);

  // Duplikat bisa datang karena HP mengirim ulang, atau karena paket ini
  // sebenarnya baru saja kita terima dari LoRa dan diteruskan ke user.
  if (s_dedup.seen(msgId)) return;

  // Sebarkan ke user lain di node yang sama. Ini gratis — tidak menyentuh
  // radio LoRa sama sekali, jadi tidak dibatasi anggaran airtime.

  // Baru di sini airtime mulai dibelanjakan, jadi baru di sini rate limit
  // berlaku. Satu HP tidak boleh memonopoli backbone sedesa.
  if (!UserLink::rateAllows(clientId)) {
    Serial.printf("[NODE] user #%u kena rate limit, tidak diteruskan ke LoRa\n",
                  clientId);
    return;
  }

  NusaAppHeader h;
  NusaPrio prio = PRIO_LOCAL;
  if (nusaAppPeek(pkt, len, h)) {
    // Serap jam nyata dari timestamp HP supaya announce node bisa diterima.
    bool wasSynced = g_clockSynced;
    nusaLearnClock(h.timestamp);
    if (!wasSynced && g_clockSynced) {
      Serial.printf("[NODE] jam tersinkron dari HP: offset %lld ms\n",
                    (long long)g_epochOffsetMs);
      // Langsung umumkan diri begitu jam siap, tanpa menunggu siklus 30 detik,
      // supaya node cepat tampil sebagai peer setelah boot/reconnect.
      UserLink::sendAnnounce();
    }
    // HEALTH_BROADCAST (0x30) & TOPOLOGY_GOSSIP (0x31) boleh telat, boleh hilang.
    // ANNOUNCE (0x01) & NOISE_IDENTITY_ANNOUNCE (0x13) JUGA: app tiap HP
    // menyiarkannya OTOMATIS tiap 30s (BluetoothMeshService.kt), bukan hasil
    // ketikan user. Kalau disamakan prioritasnya dengan PESAN (0x04, chat
    // asli), makin banyak HP numpuk di satu node → makin sering ANNOUNCE
    // menyerobot slot airtime yang seharusnya buat chat, dan chat ikut telat
    // atau ke-drop. Turunkan ke GOSSIP supaya PESAN selalu diprioritaskan.
    if (h.type == 0x30 || h.type == 0x31 || h.type == 0x01 || h.type == 0x13) {
      prio = PRIO_GOSSIP;
    }
    // DELIVERY-ACK (0x0A) & READ-RECEIPT (0x0C): pendek, dan menahannya di
    // antrean cuma memicu HP pengirim retry — jauh lebih mahal daripada
    // meluluskannya sekarang. Ini persis kasus yang PRIO_ACK didesain untuk.
    else if (h.type == 0x0A || h.type == 0x0C) {
      prio = PRIO_ACK;
    }
    // PESAN (0x04): chat teks pendek yang diketik user, bukan lampiran.
    // Naikkan di atas PRIO_LOCAL (bulk) supaya tidak ikut mengantre di
    // belakang puluhan fragmen FRAGMENT_START/CONTINUE/END milik voice
    // note/gambar yang kebetulan sedang dikirim bersamaan — tanpa ini,
    // keduanya "user lokal" yang sama dan diundi murni oleh usia antrean.
    else if (h.type == 0x04) {
      prio = PRIO_TEXT;
    }
    // Selain itu (FRAGMENT_START/CONTINUE/END dari lampiran besar, FILE,
    // handshake Noise, dll.) tetap PRIO_LOCAL — nilai default di atas.

    // Log lalu lintas ke serial. Isi TIDAK bisa ditampilkan (terenkripsi Noise);
    // yang tampil hanya metadata. Gossip rutin (health/topology/announce)
    // disembunyikan supaya log tidak penuh — chat asli (PESAN) tetap tampil.
    if (h.type != 0x30 && h.type != 0x31 && h.type != 0x01 && h.type != 0x13) {
      Serial.printf("[MSG] HP -> LoRa: %s, %uB, %s (isi terenkripsi)\n",
                    nusaTypeName(h.type), (unsigned)len,
                    h.isBroadcast ? "broadcast" : "unicast");
    }
  }

  uint8_t frames = NusaFrag::sendPacket(pkt, len, LORA_HOP_LIMIT, prio);
  if (frames == 0) {
    Serial.printf("[NODE] paket %u B dari user #%u gagal diantrekan\n",
                  (unsigned)len, clientId);
    return;
  }

  s_dedup.admit(msgId);
  UserLink::broadcast(pkt, len, clientId);
  s_toUsers++;
}

// ---------------------------------------------------------------------------
//  Backbone → sisi user
// ---------------------------------------------------------------------------
static void onPacketComplete(uint64_t msgId, uint8_t hop,
                             const uint8_t* pkt, size_t len) {
  if (!s_dedup.admit(msgId)) return;

  // Log paket yang tiba dari node lain lewat LoRa (metadata; isi terenkripsi).
  NusaAppHeader h;
  if (nusaAppPeek(pkt, len, h) && h.type != 0x30 && h.type != 0x31) {
    Serial.printf("[MSG] LoRa -> HP: %s, %uB, hop tersisa %u (isi terenkripsi)\n",
                  nusaTypeName(h.type), (unsigned)len, hop);
  }

  // Antarkan ke semua user lokal.
#if USER_LINK_BLE
  if (len <= BLE_APP_PACKET_MAX) {
    UserLink::broadcast(pkt, len);
    s_toUsers++;
  }
#else
  UserLink::broadcast(pkt, len);
  s_toUsers++;
#endif

  // Teruskan ke node berikutnya bila masih ada jatah lompatan.
  //
  // Batas hop LoRa sengaja terpisah dari TTL aplikasi (maks 7). Satu lompatan
  // LoRa harganya ratusan kali satu lompatan BLE; membiarkan TTL app mengatur
  // backbone berarti mengizinkan satu pesan memakan airtime tujuh desa.
  if (hop > 1) {
    uint8_t frames = NusaFrag::sendPacket(pkt, len, hop - 1, PRIO_RELAY);
    if (frames) s_relayed++;
  }
}

// ---------------------------------------------------------------------------
//  Frame LoRa masuk
// ---------------------------------------------------------------------------
static void onLoraFrame(const NusaFrame& f, float rssi, float snr) {
  ledPulse();   // frame LoRa apa pun masuk (HELLO tetangga atau pesan asli)

  if (f.flags & NUSA_FLAG_BEACON) {
    s_beaconRecv++;
    s_lastBeaconRx = millis();
    s_beaconRssi = rssi;
    s_beaconSnr  = snr;

    uint32_t seq = 0;
    if (f.dataLen >= 4) {
      seq = ((uint32_t)f.data[0] << 24) | ((uint32_t)f.data[1] << 16) |
            ((uint32_t)f.data[2] << 8)  |  (uint32_t)f.data[3];
    }
    uint8_t fromNode = (f.dataLen >= 5) ? f.data[4] : 0;

    // Tiap beacon/HELLO membuktikan node itu terdengar langsung → catat tetangga.
    recordNeighbor(fromNode, rssi, snr);

    // Baris [BEACON] rinci hanya saat uji jarak, supaya log tak penuh saat
    // HELLO rutin berjalan (tabel tetangga sudah merangkumnya).
    if (s_rangeTest) {
      Serial.printf("[BEACON] dari node %u seq=%lu RSSI=%.1f dBm SNR=%.1f dB\n",
                    fromNode, (unsigned long)seq, rssi, snr);
    }
    return;
  }

  if (f.flags & NUSA_FLAG_ACK) {
    // Link-ACK: dirancang di blueprint, belum diimplementasikan.
    // Broadcast memang tidak akan pernah di-ACK — ACK serentak dari banyak
    // node justru menciptakan badai tabrakan.
    return;
  }

  NusaFrag::feed(f);
}

// ---------------------------------------------------------------------------
//  Beacon uji jarak
// ---------------------------------------------------------------------------
static void sendBeacon() {
  uint8_t payload[5];
  payload[0] = (uint8_t)(s_beaconSeq >> 24);
  payload[1] = (uint8_t)(s_beaconSeq >> 16);
  payload[2] = (uint8_t)(s_beaconSeq >> 8);
  payload[3] = (uint8_t)(s_beaconSeq);
  payload[4] = NODE_ID;

  NusaFrame f;
  f.version   = NUSA_FRAME_VERSION;
  f.flags     = NUSA_FLAG_BEACON;
  f.hop       = 1;
  f.msgId     = ((uint64_t)NODE_ID << 32) | s_beaconSeq;
  f.fragIdx   = 0;
  f.fragTotal = 1;
  f.data      = payload;
  f.dataLen   = sizeof(payload);

  uint8_t out[NUSA_FRAME_MAX];
  size_t n = nusaFrameEncode(out, f);

  if (NusaRadio::enqueue(out, n, PRIO_GOSSIP)) {
    s_beaconSeq++;
  }
}

// ---------------------------------------------------------------------------
//  Tombol BOOT — hidup/matikan mode uji jarak
// ---------------------------------------------------------------------------
static void serviceButton() {
  static bool     last = HIGH;
  static uint32_t changedAt = 0;

  bool now = digitalRead(BUTTON_PIN);
  if (now != last) { changedAt = millis(); last = now; }

  static bool handled = true;
  if (now == LOW && !handled && millis() - changedAt > 50) {
    handled = true;
    s_rangeTest = !s_rangeTest;
    Serial.printf("[NODE] mode uji jarak: %s\n", s_rangeTest ? "AKTIF" : "mati");
  }
  if (now == HIGH) handled = false;
}

// ---------------------------------------------------------------------------
//  Setup
// ---------------------------------------------------------------------------
void setup() {
  Serial.begin(SERIAL_BAUD);
  delay(300);

  Serial.println();
  Serial.println("=========================================");
  Serial.printf("  NusaOS — %s (node %d)\n", NODE_NAME, NODE_ID);
  Serial.println("  LILYGO T3 v1.6.1 / SX1276 / 923 MHz");
  Serial.println("=========================================");

  randomSeed(esp_random());
  pinMode(BUTTON_PIN, INPUT_PULLUP);
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);
  analogReadResolution(12);
  Serial.println("[SETUP] 1: pin OK"); Serial.flush();

  NusaUI::begin();
  Serial.println("[SETUP] 2: UI OK"); Serial.flush();
  NusaUI::showBoot("Menyalakan radio...", "");

  s_dedup.begin();
  Serial.println("[SETUP] 3: dedup OK"); Serial.flush();

  if (!NusaRadio::begin(onLoraFrame)) {
    NusaUI::showError("Radio LoRa gagal", "Cek pinout config.h");
    // Sengaja tidak berhenti: sisi user tetap dinyalakan agar node masih bisa
    // diakses untuk diagnosa, walau backbone LoRa-nya mati.
  }
  Serial.println("[SETUP] 4: radio OK"); Serial.flush();

  NusaFrag::begin(onPacketComplete);
  Serial.println("[SETUP] 5: frag OK"); Serial.flush();
  UserLink::begin(onPacketFromUser);
  Serial.println("[SETUP] 6: link OK"); Serial.flush();

  char l1[24], l2[24];
  snprintf(l1, sizeof(l1), "%s (%s)", NODE_NAME, USER_LINK_BLE ? "BLE" : "WiFi");
  snprintf(l2, sizeof(l2), "SF%d  %.1f MHz", LORA_SF, LORA_FREQ);
  NusaUI::showBoot(l1, l2);

  Serial.printf("[NODE] siap. Hop LoRa maks %d, batas airtime %s\n",
                LORA_HOP_LIMIT,
#if DUTY_ENABLED
                "duty cycle"
#elif DWELL_ENABLED
                "dwell time"
#else
                "TIDAK AKTIF (hanya untuk lab!)"
#endif
  );
}

// ---------------------------------------------------------------------------
//  Loop
// ---------------------------------------------------------------------------
void loop() {
  NusaRadio::loop();
  serviceLed();
  UserLink::loop();
  NusaFrag::loop();
  serviceButton();

  uint32_t now = millis();

  if (s_rangeTest && now - s_lastBeaconTx >= RANGETEST_PERIOD_MS) {
    s_lastBeaconTx = now;
    sendBeacon();
  }

  // HELLO berkala untuk pemetaan tetangga. Interval sengaja DIRANDOM, bukan
  // tetap: dua board yang di-flash & boot bersamaan akan memancar HELLO pada
  // detik yang sama persis, lalu BERTABRAKAN tiap kali (LoRa half-duplex — saat
  // mancar tak bisa dengar), sehingga rx tetap 0 selamanya walau radio keduanya
  // sehat. Fase awal + tiap interval diberi jitter besar (± satu periode) supaya
  // jadwal kedua board melenceng dan akhirnya saling dengar. randomSeed sudah
  // dari esp_random() di setup(), jadi tiap board punya urutan acak berbeda.
  static uint32_t s_helloAt = 0;
  if (s_helloAt == 0) s_helloAt = now + 1000 + random(NEIGHBOR_HELLO_MS);
  if (now >= s_helloAt) {
    sendBeacon();
    s_helloAt = now + NEIGHBOR_HELLO_MS + random(NEIGHBOR_HELLO_MS);
  }

  // Siarkan identitas node berkala supaya HP mendaftarkannya sebagai peer aktif
  // (dan menampilkannya sebagai titik akses, bukan perangkat asing).
  if (now - s_lastAnnounce >= ANNOUNCE_PERIOD_MS) {
    s_lastAnnounce = now;
    UserLink::sendAnnounce();
  }

  if (now - s_lastUi >= UI_PERIOD_MS) {
    s_lastUi = now;
    if (s_rangeTest) {
      NusaUI::showRangeTest(s_beaconSeq, s_beaconRecv,
                            s_beaconRssi, s_beaconSnr,
                            s_lastBeaconRx ? (now - s_lastBeaconRx) : 0xFFFFFFFF);
    } else {
      float uiBestRssi = 0, uiBestSnr = 0;
      bool uiHasBest = bestNeighbor(&uiBestRssi, &uiBestSnr);
      NusaUI::showStatus(UserLink::clientCount(),
                         NusaRadio::queueDepth(),
                         NusaRadio::budgetMs(),
                         NusaRadio::txCount(),
                         NusaRadio::rxCount(),
                         NusaUI::readBatteryVolts(),
                         neighborCount(), uiHasBest, uiBestRssi);
    }
  }

  if (now - s_lastStatus >= STATUS_PERIOD_MS) {
    s_lastStatus = now;
    Serial.printf("[STATUS] user=%u antre=%u airtime=%.1fs "
                  "tx=%lu rx=%lu crcErr=%lu iqLain=%lu buang=%lu | "
                  "user>%lu >user%lu relay=%lu | %.2fV\n",
                  UserLink::clientCount(),
                  NusaRadio::queueDepth(),
                  NusaRadio::budgetMs() / 1000.0f,
                  (unsigned long)NusaRadio::txCount(),
                  (unsigned long)NusaRadio::rxCount(),
                  (unsigned long)NusaRadio::crcErrCount(),
                  (unsigned long)NusaRadio::timeoutCount(),
                  (unsigned long)NusaRadio::dropCount(),
                  (unsigned long)s_fromUsers,
                  (unsigned long)s_toUsers,
                  (unsigned long)s_relayed,
                  NusaUI::readBatteryVolts());
    Serial.printf("[STATUS] tetangga LoRa terdengar: %u\n", neighborCount());
    printNeighbors();

    // Lapor jumlah tetangga LoRa ke HP yang connect, supaya app bisa
    // menampilkan status "backbone LoRa hidup atau tidak" tanpa buka serial.
    // Lokal BLE saja — sengaja TIDAK lewat NusaFrag::sendPacket (tidak perlu
    // membebani antrean/airtime LoRa untuk telemetri diri sendiri).
    float bestRssi = 0, bestSnr = 0;
    bool hasBest = bestNeighbor(&bestRssi, &bestSnr);

    uint8_t healthBuf[32];
    size_t healthLen = nusaBuildLoraHealth(healthBuf, sizeof(healthBuf),
                                           (uint8_t)neighborCount(),
                                           hasBest, bestRssi, bestSnr);
    if (healthLen) UserLink::broadcast(healthBuf, healthLen);
  }
}

