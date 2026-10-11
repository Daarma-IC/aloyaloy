// ============================================================================
//  NusaOS — Mobility layer di atas HCMA (docs/HCMA.md, docs/MOBILITY_LAYER.md)
//
//  Tiga fungsi wajib, diadaptasi dari seluler:
//    1. Location registration  — tabel user yang dilayani node ini (langsung
//       lewat BLE, atau tak langsung di belakang HP perantara HCMA), lalu
//       diumumkan ke node lain lewat LoRa (analog Tracking Area Update).
//    2. Directed delivery       — pesan unicast diarahkan ke node tempat
//       penerima terdaftar lewat next hop, bukan di-flood ke semua node.
//    3. Data forwarding saat handover — pesan yang tiba di node lama setelah
//       user pindah ditahan sebentar, lalu diteruskan ke node baru begitu
//       node baru mengumumkan registrasi user tsb (analog source→target cell).
//
//  Modul ini logika murni (tanpa radio/BLE); I/O lewat Hooks supaya bisa diuji
//  di host (firmware/tests/mobility_test.cpp). Isi pesan tetap terenkripsi —
//  node hanya membaca senderID/recipientID di header plaintext.
// ============================================================================
#pragma once

#include <Arduino.h>
#include "config.h"

// Paket terarah = header routing 4 byte + paket app utuh. Byte pertama paket
// app selalu versi (0x01/0x02), jadi 0xD1 tak mungkin tertukar dengan paket app.
#define MOB_DIRECTED_MAGIC   0xD1
#define MOB_DIRECTED_HDR     4      // [magic][dstNode][srcNode][nextHop]

// Payload frame kontrol (frame LoRa ber-flag NUSA_FLAG_CTRL).
#define MOB_CTRL_LOC_UPDATE  0x01
#define MOB_LOC_HDR          8      // [type][origin][bootId][seqHi][seqLo][lastHop][hops][n]
#define MOB_LOC_ENTRY        9      // [userId 8][flags: bit0 = langsung]

// Tipe paket app yang dipakai mobility layer (lokal BLE HP↔node, TTL 1).
#define APP_TYPE_NODE_REGISTER      0x40   // HP → node: "saya dilayani node ini"
#define APP_TYPE_NODE_REGISTER_ACK  0x41   // node → HP: registrasi diterima

// ---------------------------------------------------------------------------
//  Pembacaan ID di header paket app (plaintext; lihat nusaAppPeek di nusa_frame.h)
//    v1: senderID @14, v2: senderID @16; recipientID tepat setelahnya bila flag 0x01.
// ---------------------------------------------------------------------------
static inline bool nusaAppSenderId(const uint8_t* pkt, size_t len, uint8_t out[8]) {
  size_t off = (len > 0 && pkt[0] >= 2) ? 16 : 14;
  if (len < off + 8) return false;
  memcpy(out, pkt + off, 8);
  return true;
}

// false bila paket broadcast / tanpa recipient.
static inline bool nusaAppRecipientId(const uint8_t* pkt, size_t len, uint8_t out[8]) {
  if (len < 14 || (pkt[11] & 0x01) == 0) return false;
  size_t off = ((pkt[0] >= 2) ? 16 : 14) + 8;
  if (len < off + 8) return false;
  bool bcast = true;
  for (int i = 0; i < 8; i++) if (pkt[off + i] != 0xFF) { bcast = false; break; }
  if (bcast) return false;
  memcpy(out, pkt + off, 8);
  return true;
}

namespace NusaMob {

enum RouteDecision : uint8_t {
  ROUTE_LOCAL_ONLY = 0,   // penerima dilayani node ini: cukup BLE, 0 airtime LoRa
  ROUTE_DIRECTED   = 1,   // kirim ke next hop menuju node pelayanan penerima
  ROUTE_FLOOD      = 2    // penerima tak dikenal / rute tak ada → perilaku lama
};

struct Hooks {
  // Antrekan satu frame kontrol LoRa (payload ≤ 243 B, satu fragmen).
  bool (*sendCtrl)(const uint8_t* payload, size_t len);
  // Antrekan paket terarah (header routing + paket app) dengan msgId app,
  // supaya dedup tetap sama dengan jalur flood.
  bool (*sendDirected)(const uint8_t* buf, size_t len, uint8_t hop, uint64_t msgId);
  // Flood biasa (dipakai bila rute terarah tak tersedia) dengan msgId eksplisit.
  bool (*sendFlood)(const uint8_t* pkt, size_t len, uint8_t hop, uint64_t msgId);
  // Serahkan paket app ke HP lokal (broadcast BLE).
  void (*deliverLocal)(const uint8_t* pkt, size_t len);
  // true bila node LoRa ini masih terdengar langsung (tabel tetangga).
  bool (*neighborFresh)(uint8_t nodeId);
};

struct Stats {
  uint32_t locOriginated;       // TAU yang kita buat
  uint32_t locRelayed;          // TAU node lain yang kita teruskan
  uint32_t directedOriginated;  // paket user yang kita kirim terarah
  uint32_t directedRelayed;     // paket terarah yang kita teruskan (kita next hop)
  uint32_t directedIgnored;     // paket terarah yang bukan urusan kita
  uint32_t localOnly;           // paket unicast yang tak perlu LoRa sama sekali
  uint32_t fallbackFlood;       // unicast yang terpaksa di-flood (tak ada rute)
  uint32_t held;                // pesan ditahan untuk user yang baru pergi
  uint32_t forwardedHandover;   // pesan tertahan yang diteruskan ke node baru
  uint32_t holdExpired;         // pesan tertahan yang kedaluwarsa (gagal)
};

void begin(uint8_t selfNodeId, uint8_t bootId, const Hooks& hooks);
void loop(uint32_t now);

// --- Registrasi (sisi BLE) ---
// HP mengirim NODE_REGISTER: [slot] kini dimiliki [user]. prevNode = node
// sebelumnya (0 = tidak ada) → TAU dikirim segera, tanpa debounce.
void onRegister(uint8_t slot, const uint8_t user[8], uint8_t prevNode, uint32_t now);
// Koneksi BLE slot ini putus: user langsung + user tak langsung di belakangnya pergi.
void onSlotDisconnected(uint8_t slot, uint32_t now);
// HCMA: paket dari [sender] tiba lewat [slot] milik HP lain → sender dilayani
// tak langsung lewat HP perantara itu.
void noteSender(uint8_t slot, const uint8_t sender[8], uint32_t now);

// --- Keputusan kirim untuk paket unicast dari user lokal ---
RouteDecision route(const uint8_t recipient[8], uint8_t* dstNode, uint8_t* nextHop);
// Bungkus + antrekan paket terarah. false = gagal antre (pemanggil boleh flood).
bool sendDirected(const uint8_t* pkt, size_t len, uint64_t msgId, uint8_t dstNode, uint8_t nextHop);

// --- Dari LoRa ---
void onCtrl(const uint8_t* data, size_t len, uint32_t now);
// Paket terarah utuh (sudah lolos dedup msgId). hop = sisa lompatan.
void onDirected(const uint8_t* buf, size_t len, uint64_t msgId, uint8_t hop, uint32_t now);

// --- Telemetri ---
uint8_t localUserCount(bool directOnly);
bool    isLocalUser(const uint8_t user[8]);
int     directoryLookup(const uint8_t user[8]);   // node pelayanan, -1 = tak dikenal
int     routeNextHop(uint8_t node);                // -1 = tak ada rute
const Stats& stats();

}  // namespace NusaMob
