#line 1 "C:\\Users\\darma\\OneDrive\\Dokumen\\taa\\firmware 2\\firmware\\NusaNode\\nusa_ble.h"
// ============================================================================
//  NusaOS — Sisi user: BLE GATT server yang MENYATU dengan mesh NusaMesh
//
//  Opsi A: node beriklan dengan Service UUID dan Characteristic UUID yang sama
//  persis dengan mesh BLE antar-HP. Akibatnya HP memperlakukan node sebagai
//  peer biasa dan menyambunginya OTOMATIS — tanpa perubahan aplikasi.
//
//  Konsekuensinya untuk skenario pos ronda:
//    - A dekat node  → connect langsung.
//    - C jauh dari node tapi dekat B → paket C di-relay B (mesh HP biasa)
//      hingga sampai ke node. Node tak perlu menjangkau C.
//
//  Node tetap RELAY BODOH: payload terenkripsi Noise lewat begitu saja.
// ============================================================================
#pragma once

#include <Arduino.h>
#include "config.h"

namespace NusaBle {

// Dipanggil saat sebuah paket app masuk dari salah satu HP.
// clientId = indeks koneksi lokal (untuk rate limit & anti-echo).
typedef void (*PacketHandler)(const uint8_t* pkt, size_t len, uint8_t clientId);

void begin(PacketHandler handler);
void loop();

// Kirim paket ke semua HP yang tersambung, kecuali [exceptClient].
// exceptClient = 255 berarti ke semua.
void broadcast(const uint8_t* pkt, size_t len, uint8_t exceptClient = 255);

uint8_t clientCount();

// Rate limit per user agar satu HP tak memonopoli backbone LoRa sedesa.
bool rateAllows(uint8_t clientId);

// Siarkan ANNOUNCE node supaya HP mendaftarkannya sebagai peer aktif.
void sendAnnounce();

}  // namespace NusaBle
