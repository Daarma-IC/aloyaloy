// ============================================================================
//  NusaOS — Format frame LoRa + identitas pesan + cache deduplikasi
//
//  Node adalah RELAY BODOH: ia membaca header NusaMesh (yang memang plaintext)
//  hanya untuk keperluan routing, dan TIDAK PERNAH menyentuh isi payload.
// ============================================================================
#pragma once

#include <Arduino.h>
#include "config.h"

// ---------------------------------------------------------------------------
//  Header frame LoRa — 12 byte
//
//    [0]      versi(4 bit tinggi) | flags(4 bit rendah)
//    [1]      hop tersisa antar-node
//    [2..9]   msgId (uint64, big-endian)
//    [10]     indeks fragmen
//    [11]     total fragmen
//
//  Yang sengaja TIDAK ada di sini, dan alasannya:
//    - MAGIC   : LoRa sudah punya sync word di level PHY, jadi mubazir.
//    - fragLen : explicit header LoRa sudah membawa panjang paket.
//  Pada tautan ~51 bps efektif, tiap byte yang dihemat itu berarti.
// ---------------------------------------------------------------------------
#define NUSA_FRAME_VERSION   1
#define NUSA_FRAME_HDR       12
#define NUSA_FRAME_MAX       255                              // batas SX1276
#define NUSA_FRAG_MAX_DATA   (NUSA_FRAME_MAX - NUSA_FRAME_HDR)  // 243 byte

#define NUSA_FLAG_ACK        0x01   // frame ini adalah Link-ACK
#define NUSA_FLAG_BEACON     0x02   // beacon uji jarak, bukan paket app
#define NUSA_FLAG_CTRL       0x04   // kontrol mobility layer (TAU), bukan paket app

struct NusaFrame {
  uint8_t  version;
  uint8_t  flags;
  uint8_t  hop;
  uint64_t msgId;
  uint8_t  fragIdx;
  uint8_t  fragTotal;
  const uint8_t* data;   // menunjuk ke dalam buffer asal, tidak menyalin
  uint8_t  dataLen;
};

// ---------------------------------------------------------------------------
//  FNV-1a 64-bit
//
//  Lebar 64 bit dipilih dengan sadar. Pada varian 32-bit, cache 10.000 pesan
//  punya peluang tabrakan ~1,2% — dan tabrakan berarti pesan sah dibuang
//  diam-diam sebagai "duplikat". Gagal senyap seperti itu nyaris mustahil
//  didiagnosis di lapangan.
// ---------------------------------------------------------------------------
static inline uint64_t fnv1a64(const uint8_t* d, size_t n, uint64_t h = 0xcbf29ce484222325ULL) {
  while (n--) { h ^= *d++; h *= 0x100000001b3ULL; }
  return h;
}

// ---------------------------------------------------------------------------
//  msgId sebuah paket NusaMesh.
//
//  Byte TTL (offset 2) dinolkan karena nilainya berubah tiap lompatan. Kalau
//  ikut dihitung, paket yang sama akan punya msgId berbeda di tiap hop dan
//  deduplikasi gagal total — persis kegagalan yang harus dihindari di tautan
//  berbatas airtime.
// ---------------------------------------------------------------------------
static inline uint64_t nusaMsgId(const uint8_t* pkt, size_t len) {
  uint64_t h = 0xcbf29ce484222325ULL;
  for (size_t i = 0; i < len; i++) {
    uint8_t b = (i == 2) ? 0 : pkt[i];
    h ^= b; h *= 0x100000001b3ULL;
  }
  return h;
}

// ---------------------------------------------------------------------------
//  Encode / decode header frame
// ---------------------------------------------------------------------------
static inline size_t nusaFrameEncode(uint8_t* out, const NusaFrame& f) {
  out[0]  = (uint8_t)((f.version << 4) | (f.flags & 0x0F));
  out[1]  = f.hop;
  for (int i = 0; i < 8; i++) out[2 + i] = (uint8_t)(f.msgId >> (56 - 8 * i));
  out[10] = f.fragIdx;
  out[11] = f.fragTotal;
  if (f.data && f.dataLen) memcpy(out + NUSA_FRAME_HDR, f.data, f.dataLen);
  return NUSA_FRAME_HDR + f.dataLen;
}

static inline bool nusaFrameDecode(const uint8_t* in, size_t len, NusaFrame& f) {
  if (len < NUSA_FRAME_HDR) return false;
  f.version = in[0] >> 4;
  if (f.version != NUSA_FRAME_VERSION) return false;
  f.flags   = in[0] & 0x0F;
  f.hop     = in[1];
  f.msgId   = 0;
  for (int i = 0; i < 8; i++) f.msgId = (f.msgId << 8) | in[2 + i];
  f.fragIdx   = in[10];
  f.fragTotal = in[11];
  f.data      = in + NUSA_FRAME_HDR;
  f.dataLen   = (uint8_t)(len - NUSA_FRAME_HDR);
  return true;
}

// ---------------------------------------------------------------------------
//  Pembacaan header NusaMesh — hanya untuk tampilan & keputusan routing.
//
//  Tata letak (protocol/BinaryProtocol.kt):
//    [0] versi  [1] tipe  [2] TTL  [3..10] timestamp  [11] flags
//    v1: [12..13] panjang, senderID di offset 14
//    v2: [12..15] panjang, senderID di offset 16
// ---------------------------------------------------------------------------
struct NusaAppHeader {
  uint8_t  version;
  uint8_t  type;
  uint8_t  ttl;
  uint64_t timestamp;   // epoch ms milik pengirim (dipakai node untuk sinkron jam)
  bool     hasRecipient;
  bool     isBroadcast;
};

static inline bool nusaAppPeek(const uint8_t* pkt, size_t len, NusaAppHeader& h) {
  if (len < 14) return false;
  h.version = pkt[0];
  h.type    = pkt[1];
  h.ttl     = pkt[2];
  // Timestamp: 8 byte big-endian di offset 3..10.
  h.timestamp = 0;
  for (int i = 0; i < 8; i++) h.timestamp = (h.timestamp << 8) | pkt[3 + i];
  uint8_t flags = pkt[11];
  h.hasRecipient = (flags & 0x01) != 0;

  size_t senderOff = (h.version >= 2) ? 16 : 14;
  h.isBroadcast = true;
  if (h.hasRecipient && len >= senderOff + 16) {
    const uint8_t* rcpt = pkt + senderOff + 8;
    for (int i = 0; i < 8; i++) {
      if (rcpt[i] != 0xFF) { h.isBroadcast = false; break; }
    }
  }
  return true;
}

// ---------------------------------------------------------------------------
//  Cache deduplikasi — ring sederhana, cukup untuk beban satu node.
// ---------------------------------------------------------------------------
class NusaDedup {
 public:
  void begin() {
    for (int i = 0; i < DEDUP_SLOTS; i++) { _id[i] = 0; _at[i] = 0; }
    _next = 0;
  }

  // true bila msgId ini BARU (dan langsung dicatat); false bila duplikat.
  bool admit(uint64_t msgId) {
    uint32_t now = millis();
    for (int i = 0; i < DEDUP_SLOTS; i++) {
      if (_id[i] == msgId) {
        if (now - _at[i] < DEDUP_TTL_MS) return false;  // masih dalam masa berlaku
        _at[i] = now;                                   // sudah basi, segarkan
        return true;
      }
    }
    _id[_next] = msgId;
    _at[_next] = now;
    _next = (_next + 1) % DEDUP_SLOTS;
    return true;
  }

  bool seen(uint64_t msgId) const {
    uint32_t now = millis();
    for (int i = 0; i < DEDUP_SLOTS; i++) {
      if (_id[i] == msgId && (now - _at[i]) < DEDUP_TTL_MS) return true;
    }
    return false;
  }

 private:
  uint64_t _id[DEDUP_SLOTS];
  uint32_t _at[DEDUP_SLOTS];
  uint16_t _next = 0;
};
