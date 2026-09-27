// ============================================================================
//  NusaOS — Konfigurasi Nusa Node
//  Board: LILYGO LoRa32 T3 v1.6.1 (ESP32 + Semtech SX1276), 923 MHz
// ============================================================================
#pragma once

// ---------------------------------------------------------------------------
//  1. IDENTITAS NODE  — WAJIB diubah per unit sebelum flash
// ---------------------------------------------------------------------------
#define NODE_ID              2         // 1..254, harus UNIK per node
#define NODE_NAME            "NusaNode-B"   // Board A = ID 1/NusaNode-A · Board B = ID 2/NusaNode-B

// ---------------------------------------------------------------------------
//  2. PINOUT LoRa (SX1276)  — [VERIFIKASI ke silkscreen board Anda]
//
//  Ini pinout standar TTGO/LILYGO LoRa32 T3. Revisi board bisa berbeda meski
//  nomor versinya sama. Kalau radio gagal init, 90% penyebabnya di sini.
// ---------------------------------------------------------------------------
#define LORA_SCK             5
#define LORA_MISO            19
#define LORA_MOSI            27
#define LORA_CS              18
#define LORA_RST             23
#define LORA_DIO0            26
#define LORA_DIO1            33

// ---------------------------------------------------------------------------
//  3. PERIFERAL BOARD
// ---------------------------------------------------------------------------
#define OLED_ENABLED         0         // 1 = LCD hidup. Init I2C aman: nusa_ui
                                        // probe alamat OLED dulu (timeout 50 ms)
                                        // sebelum init, jadi boot tak menggantung
                                        // walau layar bermasalah/tak ada.
#define OLED_SDA             21
#define OLED_SCL             22
#define OLED_RST             16         // set -1 bila layar justru kacau
#define BATT_ADC             35         // pembagi tegangan 1:2 ke baterai
#define BUTTON_PIN           0          // tombol BOOT/PRG

// LED indikator terima LoRa — nyala sesaat tiap ada frame masuk dari node
// tetangga (HELLO maupun pesan asli). GPIO 2 adalah dugaan umum untuk LED
// onboard varian TTGO/LILYGO; [VERIFIKASI ke silkscreen board Anda] — kalau
// tak nyala sama sekali atau nyala terus, coba GPIO 25 atau cek datasheet.
// Kebanyakan unit hanya punya SATU LED (biasanya biru), bukan hijau — kalau
// mau warna spesifik, sambungkan LED eksternal sendiri ke pin ini.
#define LED_PIN               25
#define LED_BLINK_MS          150       // durasi nyala tiap kali terima frame

// ---------------------------------------------------------------------------
//  4. PARAMETER RADIO  — HARUS SAMA PERSIS di semua node
//
//  Node dengan SF/BW/freq/syncword berbeda TIDAK saling mendengar sama sekali.
// ---------------------------------------------------------------------------
#define LORA_FREQ            921.0f     // MHz. Indonesia 920-923 [VERIFIKASI REGULASI]
#define LORA_BW              125.0f     // kHz
#define LORA_SF              7          // 7..12 — lihat tabel airtime di bawah. Dipilih 7
                                        // (bukan 10) buat prioritas banyak-user+latensi
                                        // kecil: simulasi ns-3 (contrib/nusamesh) nunjukin
                                        // latensi rata-rata turun dari ~83s ke ~1.1s dan
                                        // antrean nol drop di beban 6 node/2 user tiap node,
                                        // dengan konsekuensi jangkauan per node lebih pendek
                                        // (~3.9km vs ~8.4km di model path-loss yang dipakai).
#define LORA_CR              5          // 5..8 artinya 4/5..4/8
#define LORA_SYNC            0x12       // 0x12 = LoRa privat (0x34 = LoRaWAN, hindari)
#define LORA_POWER           14         // dBm. 14 = 25 mW [VERIFIKASI REGULASI]
#define LORA_PREAMBLE        8

//  Airtime 1 frame penuh (243 B) pada BW125 / CR4-5:
//    SF7  ~0.39 s     SF9  ~1.13 s     SF11 ~4.20 s
//    SF8  ~0.70 s     SF10 ~2.20 s     SF12 ~7.80 s
//  Naik satu tingkat SF ≈ waktu pancar dua kali lipat.

// ---------------------------------------------------------------------------
//  5. KEBIJAKAN AIRTIME
//
//  Rezim regulasi Indonesia belum dipastikan, jadi dua model didukung.
//  Pilih salah satu; yang tidak dipakai cukup diabaikan.
// ---------------------------------------------------------------------------
#define DUTY_ENABLED         1          // Dinyalakan lagi: rate limit PER-HP saja
                                        // terbukti tidak cukup begitu >1 HP nyambung
                                        // bersamaan — masing-masing dijatah sendiri,
                                        // tapi totalnya tetap bisa membanjiri radio
                                        // (rx macet 90 detik walau per-HP sudah
                                        // diketatkan). Ini jatah TOTAL radio, berlaku
                                        // untuk semua HP gabungan.
#define DUTY_RATIO           0.05f      // TESTING: 5% (produksi: 0.01 = 1%, JAUH lebih
                                        // ketat — akan terasa lambat sekali saat tes
                                        // multi-pesan, tapi itu memang realita produksi)
#define DUTY_BUDGET_MAX_MS   8000       // batas menabung, cegah burst panjang

#define DWELL_ENABLED        0          // 1 = model dwell time (alternatif)
#define DWELL_MAX_MS         400        // durasi maks sekali pancar
#define DWELL_OFF_MIN_MS     2000       // diam wajib setelah pancar

// ---------------------------------------------------------------------------
//  6. MESH
// ---------------------------------------------------------------------------
#define LORA_HOP_LIMIT       3          // lompatan antar-NODE (terpisah dari TTL app)
#define DEDUP_SLOTS          512        // cache msgId; ~4 KB RAM
#define DEDUP_TTL_MS         600000UL   // 10 menit

// --- Tabel tetangga: node lain yang terdengar LANGSUNG (1 hop) via LoRa ---
//  Tiap node menyiarkan HELLO berkala; penerima mencatat id+RSSI+kapan terdengar.
//  Inilah yang membuat node "tahu tersambung ke siapa" dan jadi alat diagnosa
//  jangkauan: bila node lawan tak pernah muncul, mereka di luar jarak dengar.
#define MAX_NEIGHBORS        16
#define NEIGHBOR_HELLO_MS    10000UL    // TESTING: HELLO tiap 10s (produksi: 60000)
#define NEIGHBOR_STALE_MS    180000UL   // >3x periode tak terdengar → tandai BASI

#define TXQ_SLOTS            24         // antrean frame keluar
#define REASM_SLOTS          3          // perakitan ulang paralel
#define REASM_MAX_BYTES      2560       // paket app terbesar yang diterima
#define REASM_TIMEOUT_MS     300000UL

#define CAD_ENABLED          1          // Root cause dugaan lama (scanChannel()
                                        // "melepas" hook DIO0) ternyata salah.
                                        // Yang sebenarnya: scanChannel() remap
                                        // DIO0 ke CAD_DONE lalu polling pin yg
                                        // sama dgn onIrq() -> s_irq basi nyala
                                        // walau bukan RX asli, dan kalau tak
                                        // dibuang bikin finishTransmit() atau
                                        // serviceRx() dipanggil prematur di
                                        // loop() berikutnya. Sudah dibuang di
                                        // nusa_radio.cpp (s_irq = false setelah
                                        // scanChannel()). Perlu tes ulang di
                                        // 2 board sebelum dianggap final.
#define BACKOFF_BASE_MS      120        // dasar jitter acak sebelum pancar
#define BACKOFF_MAX_MS       2000

// ---------------------------------------------------------------------------
//  7. AKSES USER — pilih SATU cara HP menyambung ke node
//
//    USER_LINK_BLE = 1  → BLE (Opsi A). Node MENYATU dengan mesh BLE HP:
//                         beriklan dengan UUID mesh yang sama, sehingga HP
//                         auto-connect dan me-relay antar-HP menuju node.
//                         C jauh dari node tetap tersambung lewat B. Hemat
//                         daya — cocok node solar di pos ronda. Maks ~3 HP
//                         connect LANGSUNG (sisanya lewat relay antar-HP).
//
//    USER_LINK_BLE = 0  → WiFi AP + WebSocket. Lebih banyak HP connect langsung
//                         (~8), lebih boros daya, HP harus pindah WiFi.
// ---------------------------------------------------------------------------
#define USER_LINK_BLE        1

// --- Setelan BLE (mode USER_LINK_BLE = 1) ---
// Batas koneksi langsung. NimBLE default 3; menaikkannya butuh flag build
// CONFIG_BT_NIMBLE_MAX_CONNECTIONS. Untuk pos ronda, 3 langsung + relay HP cukup.
#define BLE_MAX_CLIENTS      3

// Satu write/notification BLE harus muat di ATT MTU (517 - 3 byte ATT).
// Voice note wajib dipecah aplikasi menjadi paket NusaMesh <= nilai ini;
// Codec2 tetap berjalan di HP, node hanya membawa byte terenkripsinya.
#define BLE_APP_PACKET_MAX   500

// --- Setelan WiFi AP (mode USER_LINK_BLE = 0) ---
#define AP_SSID_PREFIX       "NusaNode-"
#define AP_PASSWORD          "nusamesh123"   // minimal 8 karakter
#define AP_CHANNEL           6
#define AP_MAX_CLIENTS       8               // batas esp_wifi = 10
#define WS_PORT              81

// --- Interval siaran ANNOUNCE node (kedua mode) ---
#define ANNOUNCE_PERIOD_MS   30000UL   // 30s: cukup untuk menjaga node terdaftar

// ---------------------------------------------------------------------------
//  8. MODE UJI JARAK
//
//  Untuk dua board pertama: satu memancar beacon, semua menampilkan RSSI/SNR.
//  Tekan tombol BOOT untuk hidup/matikan saat berjalan.
// ---------------------------------------------------------------------------
#define RANGETEST_DEFAULT    0          // 1 = langsung aktif saat boot
#define RANGETEST_PERIOD_MS  5000

// ---------------------------------------------------------------------------
//  9. LAIN-LAIN
// ---------------------------------------------------------------------------
#define SERIAL_BAUD          115200
#define STATUS_PERIOD_MS     5000
#define UI_PERIOD_MS         500
