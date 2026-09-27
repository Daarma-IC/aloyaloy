#include "config.h"

// Modul WiFi hanya dikompilasi pada mode WiFi AP. Pada mode BLE (Opsi A),
// seluruh isi berkas ini dilewati sehingga library WiFi/WebSockets tidak ikut.
#if !USER_LINK_BLE

#include "nusa_link.h"
#include "nusa_appproto.h"
#include <WiFi.h>
#include <WebSocketsServer.h>

namespace {

WebSocketsServer      s_ws(WS_PORT);
NusaLink::PacketHandler s_handler = nullptr;
uint8_t               s_clients = 0;

// Token bucket sederhana per user. Tanpa ini, satu HP yang mengirim beruntun
// bisa menghabiskan jatah airtime seluruh desa.
struct Bucket { float tokens; uint32_t last; };
Bucket s_bucket[AP_MAX_CLIENTS + 1];

constexpr float RATE_PER_SEC = 0.3f;   // TESTING: ~1 pesan/3 detik (produksi: 0.1)
constexpr float RATE_BURST   = 3.0f;   // produksi: 3

void onWsEvent(uint8_t num, WStype_t type, uint8_t* payload, size_t len) {
  switch (type) {
    case WStype_CONNECTED: {
      IPAddress ip = s_ws.remoteIP(num);
      s_clients++;
      if (num <= AP_MAX_CLIENTS) {
        s_bucket[num].tokens = RATE_BURST;
        s_bucket[num].last   = millis();
      }
      Serial.printf("[LINK] user #%u tersambung dari %s (total %u)\n",
                    num, ip.toString().c_str(), s_clients);
      break;
    }

    case WStype_DISCONNECTED:
      if (s_clients) s_clients--;
      Serial.printf("[LINK] user #%u terputus (sisa %u)\n", num, s_clients);
      break;

    case WStype_BIN:
      if (s_handler && len > 0) s_handler(payload, len, num);
      break;

    case WStype_TEXT:
      // Jalur teks hanya untuk diagnosa manual lewat browser/wscat.
      Serial.printf("[LINK] teks dari #%u: %.*s\n", num, (int)len, payload);
      break;

    default:
      break;
  }
}

}  // namespace

void NusaLink::begin(PacketHandler handler) {
  s_handler = handler;

  char ssid[32];
  snprintf(ssid, sizeof(ssid), "%s%d", AP_SSID_PREFIX, NODE_ID);

  WiFi.mode(WIFI_AP);
  bool ok = WiFi.softAP(ssid, AP_PASSWORD, AP_CHANNEL, 0, AP_MAX_CLIENTS);
  if (!ok) {
    Serial.println("[LINK] softAP GAGAL");
    return;
  }

  // Daya WiFi diturunkan: jangkauan HP→node hanya perlu puluhan meter, dan
  // daya penuh membuang baterai yang seharusnya dipakai radio LoRa.
  WiFi.setTxPower(WIFI_POWER_11dBm);

  s_ws.begin();
  s_ws.onEvent(onWsEvent);

  for (int i = 0; i <= AP_MAX_CLIENTS; i++) {
    s_bucket[i].tokens = RATE_BURST;
    s_bucket[i].last   = millis();
  }

  Serial.printf("[LINK] AP \"%s\" aktif di %s, WebSocket port %d\n",
                ssid, WiFi.softAPIP().toString().c_str(), WS_PORT);
}

void NusaLink::loop() { s_ws.loop(); }

void NusaLink::broadcast(const uint8_t* pkt, size_t len, uint8_t exceptClient) {
  if (!pkt || len == 0) return;
  if (exceptClient == 255) {
    s_ws.broadcastBIN((uint8_t*)pkt, len);
    return;
  }
  for (uint8_t i = 0; i < AP_MAX_CLIENTS; i++) {
    if (i == exceptClient) continue;
    s_ws.sendBIN(i, (uint8_t*)pkt, len);
  }
}

uint8_t NusaLink::clientCount() { return s_clients; }

bool NusaLink::rateAllows(uint8_t clientId) {
  if (clientId > AP_MAX_CLIENTS) return true;
  Bucket& b = s_bucket[clientId];
  uint32_t now = millis();
  b.tokens += (now - b.last) / 1000.0f * RATE_PER_SEC;
  if (b.tokens > RATE_BURST) b.tokens = RATE_BURST;
  b.last = now;
  if (b.tokens < 1.0f) return false;
  b.tokens -= 1.0f;
  return true;
}

void NusaLink::sendAnnounce() {
  uint8_t buf[64];
  size_t n = nusaBuildAnnounce(buf, sizeof(buf));
  if (n > 0) s_ws.broadcastBIN(buf, n);
}

#endif  // !USER_LINK_BLE
