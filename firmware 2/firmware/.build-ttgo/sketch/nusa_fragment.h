#line 1 "C:\\Users\\darma\\OneDrive\\Dokumen\\taa\\firmware 2\\firmware\\NusaNode\\nusa_fragment.h"
// ============================================================================
//  NusaOS — Fragmentasi & perakitan ulang paket NusaMesh di atas LoRa
//
//  Paket NusaMesh punya overhead 96 byte (header 16 + sender 8 + recipient 8 +
//  signature 64) sebelum satu byte payload pun ikut, dan sering melewati batas
//  255 byte satu frame LoRa. Modul ini memecah dan merakitnya kembali.
// ============================================================================
#pragma once

#include <Arduino.h>
#include "config.h"
#include "nusa_frame.h"
#include "nusa_radio.h"

namespace NusaFrag {

// Dipanggil saat sebuah paket app berhasil dirakit utuh dari fragmen-fragmennya.
typedef void (*CompleteHandler)(uint64_t msgId, uint8_t hop,
                                const uint8_t* pkt, size_t len);

void begin(CompleteHandler handler);
void loop();   // membuang perakitan yang kedaluwarsa

// Pecah satu paket app menjadi frame LoRa dan antrekan semuanya.
// Mengembalikan jumlah frame yang berhasil masuk antrean.
uint8_t sendPacket(const uint8_t* pkt, size_t len, uint8_t hop, NusaPrio prio);

// Umpankan satu frame masuk. Bila paket menjadi lengkap, handler dipanggil.
void feed(const NusaFrame& f);

uint8_t activeSlots();
uint32_t droppedIncomplete();

}  // namespace NusaFrag
