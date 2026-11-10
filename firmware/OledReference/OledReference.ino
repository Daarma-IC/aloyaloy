#include <SPI.h>
#include <LoRa.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>

// User's known-working example, with prefixed pin constants to avoid
// collisions with SCK/MISO/MOSI/SS declared by the ESP32 board variant.
#define RADIO_SCK 5
#define RADIO_MISO 19
#define RADIO_MOSI 27
#define RADIO_SS 18
#define RADIO_RST 23
#define RADIO_DIO0 26
#define BAND 923E6
#define OLED_SDA 21
#define OLED_SCL 22
#define OLED_RST -1
#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 64

Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, OLED_RST);
int counter = 0;

void setup() {
  Serial.begin(115200);
  while (!Serial);
  Wire.begin(OLED_SDA, OLED_SCL);
  if (!display.begin(SSD1306_SWITCHCAPVCC, 0x3C)) {
    Serial.println(F("OLED gagal diinisialisasi!"));
    for (;;);
  }
  display.clearDisplay();
  display.setTextColor(WHITE);
  display.setTextSize(1);
  display.setCursor(0, 0);
  display.println("LILYGO LoRa 923MHz");
  display.println("Memulai system...");
  display.display();
  delay(1500);

  SPI.begin(RADIO_SCK, RADIO_MISO, RADIO_MOSI, RADIO_SS);
  LoRa.setPins(RADIO_SS, RADIO_RST, RADIO_DIO0);
  if (!LoRa.begin(BAND)) {
    Serial.println("Gagal memulai LoRa!");
    display.clearDisplay();
    display.setCursor(0, 0);
    display.println("LoRa Error!");
    display.display();
    while (1);
  }
  Serial.println("Inisialisasi LoRa Berhasil!");
  display.clearDisplay();
  display.setCursor(0, 0);
  display.println("LoRa Ready!");
  display.println("Freq: 923 MHz");
  display.display();
  delay(1500);
}

void loop() {
  Serial.print("Mengirim paket: ");
  Serial.println(counter);
  LoRa.beginPacket();
  LoRa.print("Halo dari LilyGO #");
  LoRa.print(counter);
  LoRa.endPacket();

  display.clearDisplay();
  display.setCursor(0, 0);
  display.setTextSize(1);
  display.println("--- TRANSMITTER ---");
  display.setTextSize(2);
  display.setCursor(0, 25);
  display.print("Sent: #");
  display.print(counter);
  display.display();
  counter++;
  delay(2000);
}
