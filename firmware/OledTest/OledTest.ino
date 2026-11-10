#include <Arduino.h>
#include <Wire.h>
#include <U8g2lib.h>

// LILYGO T3 V1.6.1: SDA=21, SCL=22, no external OLED reset.
// GPIO16 belongs to the ESP32-PICO-D4 internal flash: do not drive it.
U8G2_SSD1306_128X64_NONAME_F_HW_I2C oled(
    U8G2_R0, U8X8_PIN_NONE, 22, 21);

void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("[OLED TEST] OLED only, SDA=21 SCL=22 RESET=none");
  Wire.begin(21, 22, 100000);
  Wire.setTimeOut(25);
  unsigned found = 0;
  for (uint8_t address = 1; address < 127; ++address) {
    Wire.beginTransmission(address);
    if (Wire.endTransmission() == 0) {
      Serial.printf("[I2C] found 0x%02X\n", address);
      ++found;
    }
    delay(1);
  }
  Serial.printf("[I2C] devices=%u; initializing display regardless of ACK\n", found);
  oled.setBusClock(100000);
  oled.setI2CAddress(0x3C << 1);
  oled.begin();
  oled.setPowerSave(0);
  oled.setContrast(255);
  Serial.println("[OLED TEST] display initialized; rendering every second");
}

void loop() {
  static uint32_t tick = 0;
  // Try both normal SSD1306 addresses, even if the scan got no response.
  const uint8_t address = ((tick / 10) % 2) ? 0x3D : 0x3C;
  if (tick % 10 == 0) {
    oled.setI2CAddress(address << 1);
    oled.begin();
    oled.setPowerSave(0);
    oled.setContrast(255);
  }
  oled.clearBuffer();
  oled.drawFrame(0, 0, 128, 64);
  oled.setFont(u8g2_font_7x13B_tf);
  oled.drawStr(8, 19, "NUSA NODE");
  oled.setFont(u8g2_font_6x10_tf);
  oled.drawStr(8, 34, "OLED TEST OK");
  char line[24];
  snprintf(line, sizeof(line), "0x%02X  detik: %lu", address, (unsigned long)tick);
  oled.drawStr(8, 48, line);
  oled.drawBox(8, 54, 1 + tick % 112, 4);
  oled.sendBuffer();
  Serial.printf("[OLED TEST] tick=%lu addr=0x%02X\n", (unsigned long)tick, address);
  ++tick;
  delay(1000);
}
