#include <Arduino.h>
#include <Wire.h>
#include <Adafruit_GFX.h>

// Protocol reference: Daarma-IC/teelem, telemjaya project:
// modules/esp-lib/esp-i2c.h and Common/thirdparty/gdisp.c.
// Follow its 800 kHz, page-at-a-time transport for CH1115/NFP1115 panels.
GFXcanvas1 canvas(128, 64);
constexpr uint8_t ADDRESS = 0x3C;

uint8_t commands(const uint8_t* bytes, size_t length) {
  Wire.beginTransmission(ADDRESS);
  Wire.write((uint8_t)0x00);
  Wire.write(bytes, length);
  return Wire.endTransmission(true);
}

void setup() {
  Serial.begin(115200);
  delay(1000);
  Wire.begin(21, 22, 800000);
  Wire.setBufferSize(130);
  Wire.setTimeOut(25);
  // Match the reference's page mode and orientation commands.
  const uint8_t init[] = {
    0xAE, 0xD5, 0x80, 0xA8, 0x3F, 0xD3, 0x00, 0x40,
    0x8D, 0x14, 0x20, 0x10, 0xA1, 0xC8, 0x81, 0xCF,
    0xD9, 0xF1, 0xDB, 0x40, 0x2E, 0xA4, 0xA6, 0xAF
  };
  const uint8_t remap[] = {0xA0};
  Serial.printf("[PAGE TEST] SDA21 SCL22 800kHz init=%u\n", commands(init, sizeof(init)));
  Serial.printf("[PAGE TEST] remap=%u\n", commands(remap, sizeof(remap)));
  const uint8_t allOn[] = {0xA5};
  commands(allOn, sizeof(allOn));
  delay(2000);
  const uint8_t ramDisplay[] = {0xA4};
  commands(ramDisplay, sizeof(ramDisplay));
}

void loop() {
  static uint32_t tick = 0;
  canvas.fillScreen(0);
  canvas.setTextColor(1);
  canvas.setTextSize(1);
  canvas.setCursor(0, 0);
  canvas.println("NUSA NODE");
  canvas.println("OLED PAGE TEST");
  canvas.println("TelemJaya protocol");
  canvas.print("Detik: ");
  canvas.println(tick);
  canvas.drawRect(0, 43, 128, 21, 1);
  canvas.fillRect(3, 47, 1 + tick % 120, 13, 1);

  unsigned errors = 0;
  for (uint8_t page = 0; page < 8; ++page) {
    const uint8_t position[] = {0x00, 0x10, (uint8_t)(0xB0 + page)};
    if (commands(position, sizeof(position))) ++errors;
    uint8_t data[128];
    for (unsigned x = 0; x < 128; ++x) {
      uint8_t column = 0;
      for (unsigned bit = 0; bit < 8; ++bit) {
        if (canvas.getPixel(127 - x, page * 8 + bit)) column |= 1U << bit;
      }
      data[x] = column;
    }
    Wire.beginTransmission(ADDRESS);
    Wire.write((uint8_t)0x40);
    Wire.write(data, sizeof(data));
    if (Wire.endTransmission(true)) ++errors;
  }
  Serial.printf("[PAGE TEST] tick=%lu failed_transfers=%u/16\n", (unsigned long)tick++, errors);
  delay(1000);
}
