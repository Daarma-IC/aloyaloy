// ============================================================================
//  NusaOS — Tampilan OLED SSD1306 128x64
//
//  Layar ini bukan hiasan. Saat menguji jarak di lapangan, ia satu-satunya
//  cara membaca RSSI/SNR tanpa membawa laptop.
// ============================================================================
#pragma once

#include <Arduino.h>
#include "config.h"

namespace NusaUI {

void begin();
void showBoot(const char* line1, const char* line2);
void showError(const char* line1, const char* line2);

// Layar utama operasional.
// neighborCount/hasBestNeighbor/bestRssi: status backbone LoRa (tetangga 1-hop),
// sama seperti yang dilaporkan ke HP lewat paket LORA-HEALTH — supaya kualitas
// link kelihatan langsung di board, tanpa perlu buka app atau serial monitor.
void showStatus(uint8_t users, uint16_t txq, float budgetMs,
                uint32_t tx, uint32_t rx, float battVolts,
                uint8_t neighborCount, bool hasBestNeighbor, float bestRssi);

// Layar khusus uji jarak: angka besar, terbaca sambil berjalan.
void showRangeTest(uint32_t seqSent, uint32_t seqRecv,
                   float rssi, float snr, uint32_t lastRecvAgoMs);

float readBatteryVolts();

}  // namespace NusaUI
