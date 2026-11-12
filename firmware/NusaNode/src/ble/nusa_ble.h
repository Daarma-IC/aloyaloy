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
#include "../../config.h"

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

// Statistik koneksi HP sejak boot. lastReason = kode alasan putus NimBLE terakhir
// (mis. 0x208 = supervision timeout, 0x213 = diputus HP, 0x216 = diputus lokal).
struct LinkStats {
  uint32_t connects;
  uint32_t disconnects;
  uint32_t notifyFails;
  int lastReason;
  uint32_t lastDisconnectAgeS;   // 0xFFFFFFFF = belum pernah putus
};
LinkStats linkStats();

// Rate limit per user agar satu HP tak memonopoli backbone LoRa sedesa.
bool rateAllows(uint8_t clientId);

// Siarkan ANNOUNCE node supaya HP mendaftarkannya sebagai peer aktif.
void sendAnnounce();

// Bitmask slot yang terputus sejak panggilan terakhir (bit i = slot i), lalu
// direset. Dipanggil dari loop() supaya mobility layer tak disentuh task NimBLE.
uint32_t takeClosedSlots();

}  // namespace NusaBle
