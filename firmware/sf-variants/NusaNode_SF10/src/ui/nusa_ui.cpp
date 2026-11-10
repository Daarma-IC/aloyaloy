#include "nusa_ui.h"
#include <U8g2lib.h>
#include <Wire.h>

namespace {

// Draw using software I2C; detect the OLED with a bounded hardware probe.
U8G2_SSD1306_128X64_NONAME_F_SW_I2C s_oled(
    U8G2_R0, OLED_SCL, OLED_SDA, (OLED_RST < 0 ? U8X8_PIN_NONE : OLED_RST));
bool s_ready = false;

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

  // Release hardware reset before probing: a held-reset SSD1306 cannot ACK.
  if (OLED_RST >= 0) {
    pinMode(OLED_RST, OUTPUT);
    digitalWrite(OLED_RST, LOW);
    delay(10);
    digitalWrite(OLED_RST, HIGH);
    delay(10);
  }
  // Hardware probe with a bounded timeout; no flash/reset GPIO is touched.
  uint8_t address = 0x3C;
  uint8_t sda = OLED_SDA, scl = OLED_SCL;
  auto probe = [&](uint8_t dataPin, uint8_t clockPin) {
    Wire.begin(dataPin, clockPin, 100000);
    Wire.setTimeOut(25);
    delay(100);
    bool found = false;
    for (uint8_t candidate = 0x3C; candidate <= 0x3D; ++candidate) {
      Wire.beginTransmission(candidate);
      uint8_t result = Wire.endTransmission();
      Serial.printf("[UI] SDA=%u SCL=%u addr=0x%02X result=%u\n",
                    dataPin, clockPin, candidate, result);
      if (result == 0) { address = candidate; found = true; break; }
    }
    Wire.end();
    return found;
  };
  bool present = probe(sda, scl);
  if (!present && (sda != 4 || scl != 15)) {
    sda = 4; scl = 15;
    present = probe(sda, scl);
  }
  if (present) {
    s_oled = U8G2_SSD1306_128X64_NONAME_F_SW_I2C(
        U8G2_R0, scl, sda, U8X8_PIN_NONE);
  }
  Serial.printf("[UI] probe I2C pin SDA=%d/SCL=%d: OLED %s\n",
                sda, scl, present ? "TERDETEKSI" : "tidak menjawab");
  Serial.flush();

  if (!present) {
    s_ready = false;
    Serial.println("[UI] lanjut headless — BLE+LoRa tetap jalan. Bila board PUNYA "
                   "OLED, pin-nya beda: coba ubah OLED_SDA/OLED_SCL di config.h "
                   "(banyak board LoRa lama pakai SDA=4 SCL=15).");
    return;
  }

  s_oled.setI2CAddress(address << 1);  // U8g2 uses an 8-bit address.
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
  s_oled.drawStr(0, 12, "Nusa Node");
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
  // Snapshot counters on each refresh; timestamps are uptime at observation,
  // with UI_PERIOD_MS resolution. Keep the last TX and RX visible when idle.
  static uint32_t previousTx = 0, previousRx = 0;
  static char lastTx[22] = "TX: belum ada";
  static char lastRx[22] = "RX: belum ada";
  const unsigned long seconds = (unsigned long)(millis() / 1000UL);
  if (tx != previousTx) {
    snprintf(lastTx, sizeof(lastTx), "%lus TX +%lu", seconds,
             (unsigned long)(tx - previousTx));
    previousTx = tx;
  }
  if (rx != previousRx) {
    snprintf(lastRx, sizeof(lastRx), "%lus RX +%lu", seconds,
             (unsigned long)(rx - previousRx));
    previousRx = rx;
  }
  (void)budgetMs;
  (void)battVolts;
  char b[32];
  s_oled.clearBuffer();
  s_oled.setFont(u8g2_font_7x13B_tf);
  s_oled.drawStr(0, 12, "Nusa Node");
  s_oled.setFont(u8g2_font_6x10_tf);
  snprintf(b, sizeof(b), "User:%u  Antre:%u", users, txq);
  s_oled.drawStr(0, 23, b);
  // Fit even when both counters reach ten digits.
  s_oled.setFont(u8g2_font_5x7_tf);
  snprintf(b, sizeof(b), "T:%lu R:%lu", (unsigned long)tx, (unsigned long)rx);
  s_oled.drawStr(0, 33, b);
  s_oled.setFont(u8g2_font_6x10_tf);
  if (hasBestNeighbor) {
    snprintf(b, sizeof(b), "Node:%u %.0fdBm", neighborCount, bestRssi);
  } else {
    snprintf(b, sizeof(b), "Node:%u --dBm", neighborCount);
  }
  s_oled.drawStr(0, 43, b);
  s_oled.drawStr(0, 53, lastTx);
  s_oled.drawStr(0, 63, lastRx);
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
