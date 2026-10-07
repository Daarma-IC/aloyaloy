// ============================================================================
//  NusaOS — Lapisan radio LoRa: penjadwal sadar-airtime, CAD, antrean TX
//
//  SX1276 half-duplex: selama memancar, node TULI. Karena itu aturan tunggal
//  di sini adalah "RX adalah keadaan default" — TX hanya terjadi saat benar-
//  benar perlu, dan selalu lewat antrean berprioritas.
// ============================================================================
#pragma once

#include <Arduino.h>
#include "config.h"
#include "nusa_frame.h"

// Prioritas antrean. Angka kecil dilayani lebih dulu.
//
// PRIO_TEXT dipisah dari PRIO_LOCAL supaya chat teks pendek tidak ikut
// mengantre di belakang lampiran besar (voice note/gambar, dikirim sebagai
// banyak fragmen FRAGMENT_START/CONTINUE/END — lihat NusaNode.ino). Tanpa ini,
// keduanya sama-sama "dari user lokal" dan diundi murni oleh usia antrean:
// chat baru bisa nyangkut di belakang puluhan fragmen lampiran yang sudah
// duluan mengantre.
enum NusaPrio : uint8_t {
  PRIO_ACK    = 0,   // pendek; menahannya justru memicu retry yang jauh lebih mahal
  PRIO_TEXT   = 1,   // chat teks pendek dari user lokal — biar tetap "ngalir"
  PRIO_LOCAL  = 2,   // lampiran/pesan besar dari user lokal (fragmen FRAGMENT_*, FILE)
  PRIO_RELAY  = 3,   // meneruskan pesan node lain
  PRIO_GOSSIP = 4    // topologi/health — boleh telat, boleh hilang
};

// Dipanggil saat sebuah frame LoRa diterima utuh.
typedef void (*NusaRxHandler)(const NusaFrame& frame, float rssi, float snr);

namespace NusaRadio {

bool begin(NusaRxHandler handler);
void loop();

// Antre satu frame. Mengembalikan false bila antrean penuh; fragmen yang
// sudah diterima tidak digusur agar paket tidak terpotong.
bool enqueue(const uint8_t* frame, size_t len, NusaPrio prio);

// Atomic all-or-nothing enqueue of a complete application packet.
uint8_t enqueuePacket(const uint8_t* packet, size_t len, uint8_t hop, NusaPrio prio);

// Sama, tapi msgId ditentukan pemanggil — dipakai mobility layer: paket
// terarah membawa header routing yang berubah tiap hop, sedangkan dedup harus
// tetap memakai id paket app aslinya.
uint8_t enqueuePacketWithId(const uint8_t* packet, size_t len, uint8_t hop,
                            NusaPrio prio, uint64_t msgId);

// Airtime (ms) sebuah paket sepanjang len byte pada setelan radio saat ini.
float airtimeMs(size_t len);

// Spreading factor aktif. Default LORA_SF; bisa diganti saat berjalan untuk uji
// QoS (PSP/RSSI vs jarak per SF) dan tersimpan di NVS sampai diganti lagi.
// INGAT: node dengan SF berbeda tidak saling mendengar — ganti di SEMUA node.
uint8_t spreadingFactor();

// Minta ganti SF (7..12). Diterapkan di loop() saat radio tidak sedang
// memancar, jadi aman dipanggil dari callback BLE. false = nilai tidak sah.
bool requestSpreadingFactor(uint8_t sf);

// --- telemetri untuk UI & keputusan penjadwalan ---
uint16_t queueDepth();
float    budgetMs();        // sisa anggaran airtime
uint32_t txCount();
uint32_t rxCount();
uint32_t dropCount();       // frame dibuang karena antrean penuh
uint32_t crcErrCount();     // frame TIBA tapi rusak (beda dari "tak ada sinyal
                            // sama sekali") — indikator klasik overload jarak
                            // dekat/mismatch parameter, bukan "tidak ada koneksi"
uint32_t timeoutCount();    // IRQ nyala tapi getPacketLength()==0 (bukan RX asli)
float    lastRssi();
float    lastSnr();
bool     healthy();

}  // namespace NusaRadio
