// ============================================================================
//  NusaOS — Link-ACK per hop untuk paket TERARAH (mobility layer, header 0xD1)
//
//  Masalah yang ditambal: satu paket app (mis. fragmen gambar ±480 B) dipancarkan
//  sebagai 2+ frame LoRa. Satu frame hilang di udara = paket gagal di hop itu,
//  dan selama ini satu-satunya pemulihan adalah FRAGMENT_NACK end-to-end dari HP
//  penerima (menunggu diam >=45 s, maks 4 putaran). Link-ACK memperbaikinya di
//  hop tempat kehilangan terjadi, dalam hitungan detik.
//
//  Aturan:
//   - Hanya paket terarah (next hop jelas). Broadcast/flood TIDAK di-ACK: ACK
//     serentak dari banyak node = badai tabrakan (lihat README).
//   - Penerima (next hop) mengirim ACK begitu paket lengkap, SEBELUM dedup, supaya
//     kiriman ulang yang ternyata sudah diterima tetap dijawab (ACK-nya hilang).
//   - Pengirim menyimpan salinan; timer ACK baru jalan setelah semua frame paket
//     keluar dari antrean TX (antrean duty cycle bisa lama). Tanpa ACK → kirim
//     ulang, maks LINKACK_MAX_RETRIES. Setelah itu NACK end-to-end tetap jadi
//     jaring pengaman terakhir.
//   - Kompatibel mundur: firmware lama mengabaikan frame ber-flag ACK.
//
//  Format frame ACK (header 12 B standar + 2 B):
//    flags = NUSA_FLAG_ACK, hop = 1, msgId = msgId paket yang di-ACK,
//    fragIdx = 0, fragTotal = 1, data = [nodeAsalAck, nodeTujuanAck]
// ============================================================================
#pragma once

#include <Arduino.h>
#include "../../config.h"
#include "../protocol/nusa_frame.h"

namespace NusaLinkAck {

struct Stats {
  uint32_t tracked;      // paket terarah yang dipantau ACK-nya
  uint32_t acked;        // ACK diterima tepat waktu
  uint32_t retransmits;  // kiriman ulang karena ACK tak datang
  uint32_t gaveUp;       // menyerah setelah batas kirim ulang
  uint32_t evicted;      // pemantauan digusur karena slot penuh
  uint32_t acksSent;     // ACK yang kita kirim sebagai penerima
};

void begin(uint8_t selfNodeId);

// Pengirim: panggil SETELAH paket terarah berhasil masuk antrean radio.
// buf = paket lengkap ber-header 0xD1 (nextHop di buf[3]).
void track(const uint8_t* buf, size_t len, uint8_t hop, uint64_t msgId);

// Penerima: panggil saat paket terarah lengkap dirakit, sebelum dedup.
// Mengirim ACK bila kita adalah next hop paket itu.
void onDirectedComplete(const uint8_t* buf, size_t len, uint64_t msgId);

// Frame ber-flag NUSA_FLAG_ACK masuk. Mengembalikan id node pengirim ACK
// (untuk tabel tetangga), atau 0 bila frame tak sah.
uint8_t onAckFrame(const NusaFrame& f);

// Panggil dari loop(): mempersenjatai timer dan mengirim ulang yang kedaluwarsa.
void loop();

uint8_t pending();
const Stats& stats();

}  // namespace NusaLinkAck
