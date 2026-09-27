#line 1 "C:\\Users\\darma\\OneDrive\\Dokumen\\taa\\firmware 2\\firmware\\NusaNode\\nusa_ui.cpp"
#include "nusa_ui.h"
#include <U8g2lib.h>
#include <Wire.h>

namespace {

// SW (bit-bang) I2C, BUKAN HW I2C.
//
// HW I2C ESP32 bisa menggantung selamanya di endTransmission saat bus tak
// dijawab (SCL/SDA ketahan low, pin salah) → watchdog TG1WDT → boot loop, dan
// BLE tak pernah menyala. Driver SW hanya menggerakkan pin biasa: bila layar
// bermasalah ia diam saja, tak pernah memblokir. Pin diambil dari config
// (urutan argumen: clock=SCL, data=SDA, reset).
U8G2_SSD1306_128X64_NONAME_F_SW_I2C s_oled(
    U8G2_R0, OLED_SCL, OLED_SDA, (OLED_RST < 0 ? U8X8_PIN_NONE : OLED_RST));
bool s_ready = false;

// Ketuk satu alamat I2C dengan bit-bang murni (tanpa Wire). true bila ada
// device meng-ACK. Karena semua timing kita yang pegang, fungsi ini tak mungkin
// menggantung — beda dari Wire.endTransmission yang bisa memblokir selamanya
// pada bus nyangkut dan memicu boot-loop watchdog.
#if OLED_ENABLED
bool i2cPing(uint8_t sda, uint8_t scl, uint8_t addr) {
  const int T = 6;  // us per setengah fase (~80 kHz)
  #define SDA_HI() pinMode(sda, INPUT_PULLUP)
  #define SDA_LO() do { pinMode(sda, OUTPUT); digitalWrite(sda, LOW); } while (0)
  #define SCL_HI() pinMode(scl, INPUT_PULLUP)
  #define SCL_LO() do { pinMode(scl, OUTPUT); digitalWrite(scl, LOW); } while (0)

  SDA_HI(); SCL_HI(); delayMicroseconds(T);
  // Bila jalur tak bisa naik high (tak ada pull-up / pin salah), anggap kosong.
  if (digitalRead(scl) == LOW || digitalRead(sda) == LOW) return false;

  SDA_LO(); delayMicroseconds(T);          // START
  SCL_LO(); delayMicroseconds(T);

  uint8_t b = (uint8_t)(addr << 1);        // 7-bit alamat + R/W=0
  for (int i = 0; i < 8; i++) {
    if (b & 0x80) SDA_HI(); else SDA_LO();
    delayMicroseconds(T);
    SCL_HI(); delayMicroseconds(T);
    SCL_LO(); delayMicroseconds(T);
    b <<= 1;
  }

  SDA_HI(); delayMicroseconds(T);          // baca ACK (LOW = ada device)
  SCL_HI(); delayMicroseconds(T);
  bool ack = (digitalRead(sda) == LOW);
  SCL_LO(); delayMicroseconds(T);

  SDA_LO(); delayMicroseconds(T);          // STOP
  SCL_HI(); delayMicroseconds(T);
  SDA_HI(); delayMicroseconds(T);

  #undef SDA_HI
  #undef SDA_LO
  #undef SCL_HI
  #undef SCL_LO
  return ack;
}
#endif

}  // namespace

void NusaUI::begin() {
#if !OLED_ENABLED
  // OLED dimatikan lewat config (init I2C menggantung boot di unit ini).
  // Semua fungsi show* menjadi no-op karena s_ready tetap false.
  s_ready = false;
  Serial.println("[UI] OLED dimatikan (headless) — status lewat serial");
  return;
#else
  Serial.println("[UI] mulai init OLED (SW I2C)..."); Serial.flush();

  // Probe bit-bang: ketuk alamat OLED tanpa peripheral Wire, jadi TAK MUNGKIN
  // menggantung walau layar tak ada / pin salah. SSD1306 lazim di 0x3C (kadang
  // 0x3D). Bila tak ada yang menjawab → lanjut headless, board tetap boot.
  bool present = i2cPing(OLED_SDA, OLED_SCL, 0x3C) ||
                 i2cPing(OLED_SDA, OLED_SCL, 0x3D);
  Serial.printf("[UI] probe I2C pin SDA=%d/SCL=%d: OLED %s\n",
                OLED_SDA, OLED_SCL, present ? "TERDETEKSI" : "tidak menjawab");
  Serial.flush();

  if (!present) {
    s_ready = false;
    Serial.println("[UI] lanjut headless — BLE+LoRa tetap jalan. Bila board PUNYA "
                   "OLED, pin-nya beda: coba ubah OLED_SDA/OLED_SCL di config.h "
                   "(banyak board LoRa lama pakai SDA=4 SCL=15).");
    return;
  }

  s_ready = s_oled.begin();
  if (!s_ready) {
    Serial.println("[UI] OLED gagal init — lanjut headless");
    return;
  }
  s_oled.setFont(u8g2_font_6x10_tf);
  Serial.println("[UI] OLED aktif");
#endif  // !OLED_ENABLED
}

void NusaUI::showBoot(const char* line1, const char* line2) {
  if (!s_ready) return;
  s_oled.clearBuffer();
  s_oled.setFont(u8g2_font_7x13B_tf);
  s_oled.drawStr(0, 12, "NusaOS");
  s_oled.setFont(u8g2_font_6x10_tf);
  s_oled.drawStr(0, 30, line1);
  s_oled.drawStr(0, 42, line2);
  s_oled.sendBuffer();
}

void NusaUI::showError(const char* line1, const char* line2) {
  if (!s_ready) return;
  s_oled.clearBuffer();
  s_oled.setFont(u8g2_font_7x13B_tf);
  s_oled.drawStr(0, 12, "GAGAL");
  s_oled.setFont(u8g2_font_6x10_tf);
  s_oled.drawStr(0, 30, line1);
  s_oled.drawStr(0, 42, line2);
  s_oled.sendBuffer();
}

void NusaUI::showStatus(uint8_t users, uint16_t txq, float budgetMs,
                        uint32_t tx, uint32_t rx, float battVolts,
                        uint8_t neighborCount, bool hasBestNeighbor, float bestRssi) {
  if (!s_ready) return;
  char b[32];

  s_oled.clearBuffer();

  s_oled.setFont(u8g2_font_7x13B_tf);
  snprintf(b, sizeof(b), "%s%d", AP_SSID_PREFIX, NODE_ID);
  s_oled.drawStr(0, 12, b);

  s_oled.setFont(u8g2_font_6x10_tf);

  snprintf(b, sizeof(b), "User:%u  Antre:%u", users, txq);
  s_oled.drawStr(0, 26, b);

  snprintf(b, sizeof(b), "TX:%lu  RX:%lu", (unsigned long)tx, (unsigned long)rx);
  s_oled.drawStr(0, 37, b);

  snprintf(b, sizeof(b), "Airtime:%.1fs", budgetMs / 1000.0f);
  s_oled.drawStr(0, 48, b);

  // Status backbone LoRa (tetangga 1-hop) — ganti baris SF/battery lama,
  // supaya kualitas link ke node lain kelihatan langsung di board. Ringkas
  // (font 6x10, layar 128px lebar) — "N:" = jumlah tetangga.
  if (neighborCount == 0) {
    snprintf(b, sizeof(b), "N:0 --dBm  %.1fV", battVolts);
  } else if (hasBestNeighbor) {
    snprintf(b, sizeof(b), "N:%u %.0fdBm  %.1fV", neighborCount, bestRssi, battVolts);
  } else {
    snprintf(b, sizeof(b), "N:%u  %.1fV", neighborCount, battVolts);
  }
  s_oled.drawStr(0, 59, b);

  // Bar sisa anggaran airtime — cara tercepat melihat node sedang tercekik.
  int w = (int)(budgetMs / DUTY_BUDGET_MAX_MS * 40.0f);
  if (w < 0) w = 0;
  if (w > 40) w = 40;
  s_oled.drawFrame(86, 40, 42, 8);
  if (w > 0) s_oled.drawBox(87, 41, w, 6);

  s_oled.sendBuffer();
}

void NusaUI::showRangeTest(uint32_t seqSent, uint32_t seqRecv,
                           float rssi, float snr, uint32_t lastRecvAgoMs) {
  if (!s_ready) return;
  char b[32];

  s_oled.clearBuffer();

  s_oled.setFont(u8g2_font_7x13B_tf);
  s_oled.drawStr(0, 12, "UJI JARAK");

  s_oled.setFont(u8g2_font_10x20_tf);
  if (lastRecvAgoMs > 30000UL) {
    s_oled.drawStr(0, 34, "-- dBm");
  } else {
    snprintf(b, sizeof(b), "%.0f dBm", rssi);
    s_oled.drawStr(0, 34, b);
  }

  s_oled.setFont(u8g2_font_6x10_tf);
  snprintf(b, sizeof(b), "SNR %.1f dB", snr);
  s_oled.drawStr(0, 46, b);

  snprintf(b, sizeof(b), "kirim:%lu  terima:%lu",
           (unsigned long)seqSent, (unsigned long)seqRecv);
  s_oled.drawStr(0, 57, b);

  if (lastRecvAgoMs < 30000UL) {
    snprintf(b, sizeof(b), "%lus", (unsigned long)(lastRecvAgoMs / 1000));
    s_oled.drawStr(100, 12, b);
  }

  s_oled.sendBuffer();
}

float NusaUI::readBatteryVolts() {
  // T3 v1.6.1 memakai pembagi tegangan 1:2 ke pin ADC.
  // Kalibrasi terhadap multimeter bila angkanya meleset.
  uint32_t raw = 0;
  for (int i = 0; i < 8; i++) raw += analogRead(BATT_ADC);
  raw /= 8;
  return (float)raw / 4095.0f * 3.3f * 2.0f * 1.05f;
}
