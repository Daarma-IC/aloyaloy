#line 1 "C:\\Users\\darma\\OneDrive\\Dokumen\\taa\\firmware 2\\firmware\\NusaNode\\nusa_ble.cpp"
#include "config.h"

// Modul ini hanya dikompilasi pada mode BLE (Opsi A). Lihat config.h.
#if USER_LINK_BLE

#include "nusa_ble.h"
#include "nusa_appproto.h"
#include <NimBLEDevice.h>

// ---------------------------------------------------------------------------
//  UUID — HARUS identik dengan mesh BLE aplikasi
//  (BluetoothGattServerManager.kt & BluetoothGattClientManager.kt)
// ---------------------------------------------------------------------------
static const char* SERVICE_UUID = "A1B2C3D4-E5F6-7890-1234-567890ABCDEF";
static const char* CHAR_UUID    = "A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D";
// CCCD 0x2902 dibuat otomatis oleh NimBLE untuk karakteristik NOTIFY.

namespace {

NusaBle::PacketHandler s_handler = nullptr;
NimBLEServer*          s_server  = nullptr;
NimBLECharacteristic*  s_chr     = nullptr;

// Pemetaan slot koneksi → conn handle BLE, agar bisa notify per-klien
// (broadcast kecuali pengirim) dan rate limit per user.
struct Client { bool used; uint16_t handle; };
Client s_clients[BLE_MAX_CLIENTS];

// Token bucket per klien.
struct Bucket { float tokens; uint32_t last; };
Bucket s_bucket[BLE_MAX_CLIENTS];
// Diketatkan lagi setelah terbukti 1.0/10 memicu banjir TX yang membuat
// radio nyaris tak sempat balik ke mode dengar (LoRa half-duplex): rx macet,
// tetangga jadi basi, karena node sibuk mancar terus-menerus. Ini PERSIS
// skenario "banyak user = chat kespam" yang perlu dicegah rate limit.
constexpr float RATE_PER_SEC = 0.3f;   // TESTING: ~1 pesan/3 detik (produksi: 0.1)
constexpr float RATE_BURST   = 3.0f;   // produksi: 3

int slotOfHandle(uint16_t h) {
  for (int i = 0; i < BLE_MAX_CLIENTS; i++)
    if (s_clients[i].used && s_clients[i].handle == h) return i;
  return -1;
}

int claimSlot(uint16_t h) {
  for (int i = 0; i < BLE_MAX_CLIENTS; i++) {
    if (!s_clients[i].used) {
      s_clients[i].used = true;
      s_clients[i].handle = h;
      s_bucket[i].tokens = RATE_BURST;
      s_bucket[i].last = millis();
      return i;
    }
  }
  return -1;
}

// -------------------------------------------------------------------------
//  Callback server: lacak koneksi, jaga iklan tetap hidup untuk klien baru.
// -------------------------------------------------------------------------
class ServerCbs : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* srv, NimBLEConnInfo& info) override {
    int slot = claimSlot(info.getConnHandle());
    Serial.printf("[BLE] HP tersambung (handle %u, slot %d)\n",
                  info.getConnHandle(), slot);
    // NimBLE berhenti beriklan saat ada koneksi; nyalakan lagi supaya HP lain
    // (dan node lain) tetap bisa menemukan kita.
    NimBLEDevice::startAdvertising();
  }

  void onDisconnect(NimBLEServer* srv, NimBLEConnInfo& info, int reason) override {
    int slot = slotOfHandle(info.getConnHandle());
    if (slot >= 0) s_clients[slot].used = false;
    Serial.printf("[BLE] HP terputus (handle %u, alasan %d)\n",
                  info.getConnHandle(), reason);
    NimBLEDevice::startAdvertising();
  }

  void onMTUChange(uint16_t mtu, NimBLEConnInfo& info) override {
    Serial.printf("[BLE] MTU handle %u = %u\n", info.getConnHandle(), mtu);
  }
};

// -------------------------------------------------------------------------
//  Callback karakteristik: paket masuk dari HP, dan pelacakan subscribe.
// -------------------------------------------------------------------------
class ChrCbs : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* chr, NimBLEConnInfo& info) override {
    NimBLEAttValue v = chr->getValue();
    if (v.length() == 0) return;
    if (v.length() > BLE_APP_PACKET_MAX) return;
    int slot = slotOfHandle(info.getConnHandle());
    uint8_t clientId = (slot >= 0) ? (uint8_t)slot : 255;
    if (s_handler) s_handler(v.data(), v.length(), clientId);
  }

  void onSubscribe(NimBLECharacteristic* chr, NimBLEConnInfo& info,
                   uint16_t subValue) override {
    // subValue bit0 = notifications aktif. Saat HP subscribe, kirimi ANNOUNCE
    // agar ia langsung mendaftarkan node sebagai peer.
    if (subValue & 0x0001) {
      Serial.printf("[BLE] handle %u subscribe → kirim announce\n",
                    info.getConnHandle());
      NusaBle::sendAnnounce();
    }
  }
};

ServerCbs s_serverCbs;
ChrCbs    s_chrCbs;

}  // namespace

// ---------------------------------------------------------------------------
//  API publik
// ---------------------------------------------------------------------------
void NusaBle::begin(PacketHandler handler) {
  s_handler = handler;
  for (int i = 0; i < BLE_MAX_CLIENTS; i++) s_clients[i].used = false;

  NimBLEDevice::init(NODE_NAME);
  NimBLEDevice::setMTU(517);                       // sama seperti permintaan HP
  NimBLEDevice::setPower(ESP_PWR_LVL_P9);          // jangkauan HP→node maksimal

  s_server = NimBLEDevice::createServer();
  s_server->setCallbacks(&s_serverCbs);
  s_server->advertiseOnDisconnect(true);

  NimBLEService* svc = s_server->createService(SERVICE_UUID);
  s_chr = svc->createCharacteristic(
      CHAR_UUID,
      NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE |
      NIMBLE_PROPERTY::WRITE_NR | NIMBLE_PROPERTY::NOTIFY);
  s_chr->setCallbacks(&s_chrCbs);

  // Iklan: Service UUID di paket utama; peerID (8 byte) di scan response
  // sebagai service data — persis yang dibaca handleScanResult() di HP.
  uint8_t peer[8];
  nusaNodePeerId(peer);

  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  adv->setName(NODE_NAME);

  NimBLEAdvertisementData scanData;
  scanData.setServiceData(NimBLEUUID(SERVICE_UUID), peer, 8);
  adv->setScanResponseData(scanData);
  adv->enableScanResponse(true);

  NimBLEDevice::startAdvertising();

  Serial.printf("[BLE] GATT server aktif sebagai \"%s\"\n", NODE_NAME);
  Serial.printf("[BLE] Service %s\n", SERVICE_UUID);
  Serial.printf("[BLE] peerID %02x%02x%02x%02x%02x%02x%02x%02x  (maks %d klien)\n",
                peer[0],peer[1],peer[2],peer[3],peer[4],peer[5],peer[6],peer[7],
                BLE_MAX_CLIENTS);
}

void NusaBle::loop() {
  // NimBLE berjalan di task-nya sendiri; tidak ada yang perlu dipompa di sini.
}

void NusaBle::broadcast(const uint8_t* pkt, size_t len, uint8_t exceptClient) {
  if (!s_chr || len == 0) return;
  s_chr->setValue(pkt, len);
  for (int i = 0; i < BLE_MAX_CLIENTS; i++) {
    if (!s_clients[i].used) continue;
    if (i == exceptClient) continue;
    s_chr->notify(s_clients[i].handle);
  }
}

uint8_t NusaBle::clientCount() {
  uint8_t n = 0;
  for (int i = 0; i < BLE_MAX_CLIENTS; i++) if (s_clients[i].used) n++;
  return n;
}

bool NusaBle::rateAllows(uint8_t clientId) {
  if (clientId >= BLE_MAX_CLIENTS) return true;
  Bucket& b = s_bucket[clientId];
  uint32_t now = millis();
  b.tokens += (now - b.last) / 1000.0f * RATE_PER_SEC;
  if (b.tokens > RATE_BURST) b.tokens = RATE_BURST;
  b.last = now;
  if (b.tokens < 1.0f) return false;
  b.tokens -= 1.0f;
  return true;
}

void NusaBle::sendAnnounce() {
  if (!s_chr) return;
  uint8_t buf[64];
  size_t n = nusaBuildAnnounce(buf, sizeof(buf));
  if (n == 0) return;
  s_chr->setValue(buf, n);
  // notify() tanpa handle → ke semua subscriber. Lebih andal daripada per-handle.
  bool ok = s_chr->notify();
  Serial.printf("[BLE] announce %u B dikirim ke %u klien (ok=%d)\n",
                (unsigned)n, clientCount(), ok ? 1 : 0);
}

#endif  // USER_LINK_BLE
