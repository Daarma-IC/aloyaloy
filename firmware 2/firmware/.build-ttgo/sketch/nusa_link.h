#line 1 "C:\\Users\\darma\\OneDrive\\Dokumen\\taa\\firmware 2\\firmware\\NusaNode\\nusa_link.h"
// ============================================================================
//  NusaOS — Sisi user: WiFi SoftAP + WebSocket biner
//
//  HP tersambung ke SSID "NusaNode-<id>", lalu bicara WebSocket ke
//  ws://192.168.4.1:81/ . Paket NusaMesh dikirim apa adanya sebagai frame biner.
//
//  Node TIDAK PERNAH mendekripsi apa pun yang lewat sini.
// ============================================================================
#pragma once

#include <Arduino.h>
#include "config.h"

namespace NusaLink {

// Dipanggil saat sebuah paket app masuk dari salah satu HP.
typedef void (*PacketHandler)(const uint8_t* pkt, size_t len, uint8_t clientId);

void begin(PacketHandler handler);
void loop();

// Siarkan paket ke semua user lokal, kecuali [exceptClient] bila diisi.
// exceptClient = 255 berarti kirim ke semua.
void broadcast(const uint8_t* pkt, size_t len, uint8_t exceptClient = 255);

uint8_t clientCount();

// Batasi laju per user supaya satu HP tidak memonopoli backbone sedesa.
bool rateAllows(uint8_t clientId);

// Siarkan ANNOUNCE node ke semua user (paritas dengan mode BLE).
void sendAnnounce();

}  // namespace NusaLink
