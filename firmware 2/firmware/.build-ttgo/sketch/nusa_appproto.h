#line 1 "C:\\Users\\darma\\OneDrive\\Dokumen\\taa\\firmware 2\\firmware\\NusaNode\\nusa_appproto.h"
// ============================================================================
//  NusaOS — Pembangun paket NusaMesh tingkat-aplikasi (khusus ANNOUNCE)
//
//  Node adalah relay bodoh dan TIDAK membuat pesan pengguna. Tapi agar HP
//  mengenalinya sebagai peer mesh yang sah (dan menampilkannya sebagai titik
//  akses), node perlu menyiarkan satu paket ANNOUNCE — persis format yang
//  dihasilkan BinaryProtocol.encode() di aplikasi.
//
//  Layout header v2 (protocol/BinaryProtocol.kt):
//    [0]      versi   = 0x02
//    [1]      tipe    = 0x01 (ANNOUNCE)
//    [2]      ttl
//    [3..10]  timestamp (uint64, big-endian)
//    [11]     flags   = 0x00 (broadcast, tanpa signature, tanpa kompresi)
//    [12..15] panjang payload (uint32, big-endian)  ← v2 pakai 4 byte
//    [16..23] senderID (8 byte)
//    [24..]   payload = "nickname~gender"
//
//  Catatan penting soal padding: MessagePadding.unpad() HANYA melepas padding
//  bila ukuran paket persis 256/512/1024/2048. Paket ANNOUNCE kita jauh lebih
//  kecil dari itu, jadi HP mengembalikannya apa adanya — node tidak perlu
//  mengimplementasikan padding sama sekali.
// ============================================================================
#pragma once

#include <Arduino.h>
#include "config.h"

#define APP_TYPE_ANNOUNCE     0x01
#define APP_TYPE_LORA_HEALTH  0x18   // lapor jumlah tetangga LoRa ke HP (lokal BLE saja)

// Nama tipe paket NusaMesh untuk log serial (isi payload tetap terenkripsi).
static inline const char* nusaTypeName(uint8_t t) {
  switch (t) {
    case 0x01: return "ANNOUNCE";
    case 0x04: return "PESAN";
    case 0x05: return "FRAG-AWAL";
    case 0x06: return "FRAG-TENGAH";
    case 0x07: return "FRAG-AKHIR";
    case 0x0A: return "DELIVERY-ACK";
    case 0x0C: return "READ-RECEIPT";
    case 0x10: return "NOISE-INIT";
    case 0x11: return "NOISE-RESP";
    case 0x12: return "NOISE-ENC";
    case 0x13: return "NOISE-ID";
    case 0x18: return "LORA-HEALTH";
    case 0x22: return "FILE";
    case 0x30: return "HEALTH";
    case 0x31: return "TOPOLOGY";
    default:   return "LAIN";
  }
}

// ---------------------------------------------------------------------------
//  Jam epoch node — dipelajari dari lalu lintas mesh.
//
//  Node tidak punya RTC/NTP, tapi HP menandai tiap paket dengan epoch ms nyata.
//  HP menolak paket yang timestamp-nya meleset >5 menit dari waktunya (proteksi
//  replay). Maka node menyerap timestamp paket HP pertama yang masuk, menghitung
//  offset terhadap millis()-nya sendiri, lalu memakainya untuk announce.
//  Sebelum tersinkron, node tidak menyiarkan announce (pasti ditolak HP).
//
//  Variabel didefinisikan di NusaNode.ino (satu unit terjemahan).
// ---------------------------------------------------------------------------
extern int64_t g_epochOffsetMs;
extern bool    g_clockSynced;

static inline uint64_t nusaNowEpochMs() {
  return (uint64_t)((int64_t)millis() + g_epochOffsetMs);
}

static inline void nusaLearnClock(uint64_t pktEpochMs) {
  // Terima hanya bila plausibel (setelah 1 Jan 2020) supaya tak keracunan paket cacat.
  if (!g_clockSynced && pktEpochMs > 1577836800000ULL) {
    g_epochOffsetMs = (int64_t)pktEpochMs - (int64_t)millis();
    g_clockSynced = true;
  }
}

// ---------------------------------------------------------------------------
//  peerID node — 8 byte, stabil per unit.
//
//  Empat byte pertama menandai "NUSN" agar mudah dikenali di log; byte terakhir
//  membawa NODE_ID sehingga tiap node punya identitas berbeda namun tetap.
// ---------------------------------------------------------------------------
static inline void nusaNodePeerId(uint8_t out[8]) {
  out[0] = 'N'; out[1] = 'U'; out[2] = 'S'; out[3] = 'N';
  out[4] = 0x00; out[5] = 0x00; out[6] = 0x00;
  out[7] = (uint8_t)NODE_ID;
}

// ---------------------------------------------------------------------------
//  Bangun paket ANNOUNCE lengkap ke [out].
//  @return panjang byte, atau 0 bila buffer terlalu kecil.
// ---------------------------------------------------------------------------
static inline size_t nusaBuildAnnounce(uint8_t* out, size_t cap) {
  // Tanpa jam tersinkron, timestamp kita akan dianggap "paket lama" oleh HP
  // dan dibuang. Jadi jangan buat announce sampai jam mesh terserap.
  if (!g_clockSynced) return 0;

  const char* nick = NODE_NAME;         // dari config.h, mis. "NusaNode-A"
  const char gender = '\0';             // node tak bergender; kosong

  size_t nickLen = strlen(nick);
  size_t payloadLen = nickLen + 1;      // nickname + '~' (gender kosong)
  size_t total = 16 + 8 + payloadLen;   // header v2 + senderID + payload
  if (cap < total) return 0;

  uint64_t ts = nusaNowEpochMs();       // epoch ms nyata (hasil sinkron dari HP)
  uint8_t peer[8];
  nusaNodePeerId(peer);

  size_t i = 0;
  out[i++] = 0x02;                      // versi 2
  out[i++] = APP_TYPE_ANNOUNCE;         // tipe
  out[i++] = 0x03;                      // ttl 3, sama seperti app

  for (int b = 7; b >= 0; b--) out[i++] = (uint8_t)(ts >> (8 * b));  // timestamp BE

  out[i++] = 0x00;                      // flags: broadcast, tanpa sig/kompresi

  uint32_t plen = (uint32_t)payloadLen; // panjang payload (4 byte BE, v2)
  out[i++] = (uint8_t)(plen >> 24);
  out[i++] = (uint8_t)(plen >> 16);
  out[i++] = (uint8_t)(plen >> 8);
  out[i++] = (uint8_t)(plen);

  memcpy(out + i, peer, 8); i += 8;     // senderID

  memcpy(out + i, nick, nickLen); i += nickLen;
  out[i++] = '~';                       // pemisah nickname~gender
  (void)gender;                         // gender kosong, tak ada byte tambahan

  return i;
}

// ---------------------------------------------------------------------------
//  Bangun paket LORA-HEALTH: lapor jumlah tetangga LoRa node ini ke HP yang
//  connect via BLE, plus RSSI/SNR tautan ke tetangga TERKUAT-nya (bukan
//  tautan HP↔node BLE — itu urusan BLE RSSI biasa). HANYA lokal (broadcast
//  BLE), TIDAK PERNAH masuk antrean LoRa — murni telemetri "apakah backbone
//  node ini hidup dan seberapa bagus", bukan sesuatu yang perlu diteruskan ke
//  node lain. Payload 3 byte: [neighborCount][rssi int8][snr int8, x2 skala
//  0.5dB]. rssi/snr diisi -128 (sentinel) kalau hasBestNeighbor false.
//  @return panjang byte, atau 0 bila buffer terlalu kecil / jam belum sinkron.
// ---------------------------------------------------------------------------
static inline size_t nusaBuildLoraHealth(uint8_t* out, size_t cap, uint8_t neighborCount,
                                          bool hasBestNeighbor, float bestRssi, float bestSnr) {
  if (!g_clockSynced) return 0;

  const size_t payloadLen = 3;
  const size_t total = 16 + 8 + payloadLen;   // header v2 + senderID + payload
  if (cap < total) return 0;

  uint64_t ts = nusaNowEpochMs();
  uint8_t peer[8];
  nusaNodePeerId(peer);

  size_t i = 0;
  out[i++] = 0x02;                        // versi 2
  out[i++] = APP_TYPE_LORA_HEALTH;        // tipe
  out[i++] = 0x01;                        // ttl 1: tak perlu diteruskan siapa pun

  for (int b = 7; b >= 0; b--) out[i++] = (uint8_t)(ts >> (8 * b));  // timestamp BE

  out[i++] = 0x00;                        // flags: broadcast, tanpa sig/kompresi

  uint32_t plen = (uint32_t)payloadLen;
  out[i++] = (uint8_t)(plen >> 24);
  out[i++] = (uint8_t)(plen >> 16);
  out[i++] = (uint8_t)(plen >> 8);
  out[i++] = (uint8_t)(plen);

  memcpy(out + i, peer, 8); i += 8;        // senderID = identitas node ini

  out[i++] = neighborCount;

  int8_t rssiByte = -128;
  int8_t snrByte  = -128;
  if (hasBestNeighbor) {
    // Bulatkan manual (hindari lroundf/math.h — tak dipakai di tempat lain
    // pada firmware ini, jadi lebih aman daripada berasumsi ter-link).
    long r = (long)(bestRssi >= 0 ? bestRssi + 0.5f : bestRssi - 0.5f);
    long s = (long)((bestSnr * 2.0f) >= 0 ? (bestSnr * 2.0f) + 0.5f : (bestSnr * 2.0f) - 0.5f);
    if (r < -127) r = -127; if (r > 127) r = 127;
    if (s < -127) s = -127; if (s > 127) s = 127;
    rssiByte = (int8_t)r;
    snrByte  = (int8_t)s;
  }
  out[i++] = (uint8_t)rssiByte;
  out[i++] = (uint8_t)snrByte;

  return i;
}

// ---------------------------------------------------------------------------
//  Lepas padding traffic-analysis-resistance (MessagePadding.kt) sebelum
//  paket USER masuk ke jalur LoRa.
//
//  App membulatkan tiap paket ke blok 256/512/1024/2048 byte sebelum keluar
//  dari HP — bagus untuk privasi di BLE (menyembunyikan panjang asli pesan),
//  tapi di LoRa yang airtime-nya mahal ini bisa mengubah "ok" (~100B asli)
//  jadi 256B, atau paragraf (~600B) jadi 1024B — beberapa menit kebisuan
//  sedesa untuk amplop yang sebagian besar isinya byte acak kosong.
//  (NUSAOS_BLUEPRINT.md Section 6.2)
//
//  Aman dilepas di sini tanpa app tahu-menahu: padding-nya PKCS#7 (byte
//  terakhir = jumlah byte padding yang ditambahkan), dan MessagePadding.unpad()
//  di HP HANYA melepas padding bila ukuran paket PERSIS salah satu dari 4
//  blok itu (lihat catatan di atas nusaBuildAnnounce()). Begitu ukurannya
//  kita pangkas jadi BUKAN salah satu dari 4 blok itu, unpad() di HP otomatis
//  no-op dan decode() tetap membaca paket dengan benar — node penerima juga
//  tidak perlu mem-pad ulang sebelum broadcast ke user lokalnya.
// ---------------------------------------------------------------------------
static inline size_t nusaStripPadding(const uint8_t* pkt, size_t len) {
  if (len != 256 && len != 512 && len != 1024 && len != 2048) return len;

  uint8_t padLen = pkt[len - 1];
  if (padLen == 0 || padLen > len) return len;   // padding tak valid, biarkan apa adanya

  return len - padLen;
}
