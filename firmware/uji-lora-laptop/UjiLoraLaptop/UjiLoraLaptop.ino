// UjiLoraLaptop: firmware uji LoRa yang dikendalikan laptop lewat USB serial.
//
// Firmware mandiri, terpisah dari NusaNode. Satu firmware dipakai untuk dua
// board: board yang diberi perintah TX menjadi pengirim, board lain cukup
// mendengarkan dan meneruskan setiap frame yang diterima ke laptop.
//
// Protokol serial (115200 baud, satu perintah per baris):
//   PING                          -> PONG UjiLoraLaptop 1
//   CFG <MHz> <SF> <BWkHz> <CR> <dBm>
//                                 -> OK CFG ...      | ERR CFG <kode>
//   TX <hex>                      -> TXOK <byte> <us_ukur> <us_hitung>
//                                                    | ERR TX <kode>
//   STAT                          -> STAT ...
// Pesan tanpa diminta dari board:
//   READY ...                     setelah boot
//   RX <hex> <rssi> <snr>         frame LoRa diterima, CRC benar
//   RXERR <kode>                  frame rusak (crc) atau gagal dibaca

#include <Arduino.h>
#include <RadioLib.h>
#include <SPI.h>

namespace {

constexpr char kVersion[] = "UjiLoraLaptop 1";
constexpr uint32_t kBaud = 115200;

// Pin radio LilyGO T3 v1.6.1, sama dengan firmware/NusaNode/config.h.
constexpr int kPinSck = 5;
constexpr int kPinMiso = 19;
constexpr int kPinMosi = 27;
constexpr int kPinCs = 18;
constexpr int kPinRst = 23;
constexpr int kPinDio0 = 26;
constexpr int kPinDio1 = 33;

constexpr uint8_t kSyncWord = 0x12;  // sama dengan NusaNode
constexpr uint16_t kPreamble = 8;
constexpr size_t kMaxFrame = 255;

// Batas aman uji meja. Frekuensi di pita AS923 Indonesia (920-923 MHz);
// batas daya resmi tetap harus dicek ke aturan Komdigi.
constexpr float kMinFreqMhz = 920.0f;
constexpr float kMaxFreqMhz = 923.0f;
constexpr int kMinPowerDbm = 2;
constexpr int kMaxPowerDbm = 14;

struct RadioConfig {
  float freqMhz = 921.0f;
  int sf = 7;
  float bwKhz = 125.0f;
  int cr = 5;            // 5..8 berarti 4/5..4/8
  int powerDbm = 2;      // rendah untuk uji di meja
};

SPIClass radioSpi(VSPI);
SPISettings spiSettings(4000000, MSBFIRST, SPI_MODE0);
SX1276 radio = new Module(kPinCs, kPinDio0, kPinRst, kPinDio1,
                          radioSpi, spiSettings);

RadioConfig config;
volatile bool radioIrq = false;
bool healthy = false;

uint32_t txCount = 0;
uint32_t rxCount = 0;
uint32_t rxCrcErrors = 0;
uint32_t rxOtherErrors = 0;

char line[2 * kMaxFrame + 32];
size_t lineLen = 0;
bool lineOverflow = false;

uint8_t frame[kMaxFrame];
char hexOut[2 * kMaxFrame + 1];

void IRAM_ATTR onRadioIrq() { radioIrq = true; }

int hexValue(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  if (c >= 'A' && c <= 'F') return c - 'A' + 10;
  return -1;
}

void printConfig(const char* prefix) {
  Serial.printf("%s %.3f MHz SF%d BW%.1f CR4/%d %d dBm\n", prefix,
                config.freqMhz, config.sf, config.bwKhz, config.cr,
                config.powerDbm);
}

int16_t startReceive() {
  radioIrq = false;
  return radio.startReceive();
}

// Menerapkan konfigurasi ke radio. Mengembalikan kode RadioLib.
int16_t applyConfig(const RadioConfig& c) {
  int16_t state = radio.standby();
  if (state == RADIOLIB_ERR_NONE) state = radio.setFrequency(c.freqMhz);
  if (state == RADIOLIB_ERR_NONE) state = radio.setBandwidth(c.bwKhz);
  if (state == RADIOLIB_ERR_NONE) state = radio.setSpreadingFactor(c.sf);
  if (state == RADIOLIB_ERR_NONE) state = radio.setCodingRate(c.cr);
  if (state == RADIOLIB_ERR_NONE) state = radio.setOutputPower(c.powerDbm);
  if (state == RADIOLIB_ERR_NONE) state = startReceive();
  return state;
}

void handleConfig(const char* args) {
  RadioConfig next;
  int fields = sscanf(args, "%f %d %f %d %d", &next.freqMhz, &next.sf,
                      &next.bwKhz, &next.cr, &next.powerDbm);
  if (fields != 5) {
    Serial.println("ERR CFG format: CFG <MHz> <SF> <BWkHz> <CR> <dBm>");
    return;
  }
  if (next.freqMhz < kMinFreqMhz || next.freqMhz > kMaxFreqMhz) {
    Serial.println("ERR CFG frekuensi harus 920-923 MHz");
    return;
  }
  if (next.sf < 7 || next.sf > 12 || next.cr < 5 || next.cr > 8) {
    Serial.println("ERR CFG SF 7..12, CR 5..8");
    return;
  }
  if (next.powerDbm < kMinPowerDbm || next.powerDbm > kMaxPowerDbm) {
    Serial.printf("ERR CFG daya %d..%d dBm\n", kMinPowerDbm, kMaxPowerDbm);
    return;
  }
  int16_t state = applyConfig(next);
  if (state != RADIOLIB_ERR_NONE) {
    Serial.printf("ERR CFG %d\n", state);
    applyConfig(config);  // kembali ke konfigurasi sebelumnya
    return;
  }
  config = next;
  printConfig("OK CFG");
}

void handleTransmit(const char* hex) {
  if (!healthy) {
    Serial.println("ERR TX radio belum siap");
    return;
  }
  size_t digits = strlen(hex);
  if (digits == 0 || digits % 2 != 0 || digits / 2 > kMaxFrame) {
    Serial.printf("ERR TX panjang 1..%u byte\n", (unsigned)kMaxFrame);
    return;
  }
  size_t len = digits / 2;
  for (size_t i = 0; i < len; ++i) {
    int hi = hexValue(hex[2 * i]);
    int lo = hexValue(hex[2 * i + 1]);
    if (hi < 0 || lo < 0) {
      Serial.println("ERR TX hex tidak valid");
      return;
    }
    frame[i] = static_cast<uint8_t>((hi << 4) | lo);
  }

  uint32_t started = micros();
  int16_t state = radio.transmit(frame, len);
  uint32_t measuredUs = micros() - started;
  int16_t rxState = startReceive();

  if (state != RADIOLIB_ERR_NONE) {
    Serial.printf("ERR TX %d\n", state);
  } else {
    ++txCount;
    Serial.printf("TXOK %u %lu %lu\n", (unsigned)len,
                  (unsigned long)measuredUs,
                  (unsigned long)radio.getTimeOnAir(len));
  }
  if (rxState != RADIOLIB_ERR_NONE) Serial.printf("ERR RX %d\n", rxState);
}

void printStats() {
  Serial.printf("STAT tx=%lu rx=%lu crc=%lu rxerr=%lu sehat=%d ",
                (unsigned long)txCount, (unsigned long)rxCount,
                (unsigned long)rxCrcErrors, (unsigned long)rxOtherErrors,
                healthy ? 1 : 0);
  printConfig("cfg");
}

void handleLine() {
  line[lineLen] = '\0';
  if (strcmp(line, "PING") == 0) {
    Serial.printf("PONG %s\n", kVersion);
  } else if (strcmp(line, "STAT") == 0) {
    printStats();
  } else if (strncmp(line, "CFG ", 4) == 0) {
    handleConfig(line + 4);
  } else if (strncmp(line, "TX ", 3) == 0) {
    handleTransmit(line + 3);
  } else {
    Serial.println("ERR perintah: PING | STAT | CFG ... | TX <hex>");
  }
}

void readSerial() {
  while (Serial.available()) {
    char c = static_cast<char>(Serial.read());
    if (c == '\r' || c == '\n') {
      if (lineOverflow) Serial.println("ERR baris terlalu panjang");
      else if (lineLen > 0) handleLine();
      lineLen = 0;
      lineOverflow = false;
    } else if (lineLen < sizeof(line) - 1) {
      line[lineLen++] = c;
    } else {
      lineOverflow = true;
    }
  }
}

void serviceReceive() {
  if (!radioIrq) return;
  radioIrq = false;

  size_t len = radio.getPacketLength();
  int16_t state = RADIOLIB_ERR_PACKET_TOO_LONG;
  if (len > 0 && len <= kMaxFrame) state = radio.readData(frame, len);
  float rssi = radio.getRSSI();
  float snr = radio.getSNR();
  startReceive();

  if (state == RADIOLIB_ERR_CRC_MISMATCH) {
    ++rxCrcErrors;
    Serial.println("RXERR crc");
    return;
  }
  if (state != RADIOLIB_ERR_NONE) {
    ++rxOtherErrors;
    Serial.printf("RXERR %d\n", state);
    return;
  }

  ++rxCount;
  static const char kDigits[] = "0123456789abcdef";
  for (size_t i = 0; i < len; ++i) {
    hexOut[2 * i] = kDigits[frame[i] >> 4];
    hexOut[2 * i + 1] = kDigits[frame[i] & 0x0F];
  }
  hexOut[2 * len] = '\0';
  Serial.print("RX ");
  Serial.print(hexOut);
  Serial.printf(" %.1f %.2f\n", rssi, snr);
}

}  // namespace

void setup() {
  Serial.setRxBufferSize(2048);
  Serial.begin(kBaud);
  delay(200);

  pinMode(kPinCs, OUTPUT);
  digitalWrite(kPinCs, HIGH);
  radioSpi.begin(kPinSck, kPinMiso, kPinMosi, kPinCs);

  int16_t state = radio.begin(config.freqMhz, config.bwKhz, config.sf,
                              config.cr, kSyncWord, config.powerDbm,
                              kPreamble);
  if (state == RADIOLIB_ERR_NONE) state = radio.setCRC(true);
  if (state == RADIOLIB_ERR_NONE) {
    radio.setPacketReceivedAction(onRadioIrq);
    state = startReceive();
  }
  if (state != RADIOLIB_ERR_NONE) {
    Serial.printf("ERR RADIO init %d\n", state);
    return;
  }
  healthy = true;
  Serial.printf("READY %s ", kVersion);
  printConfig("cfg");
}

void loop() {
  readSerial();
  if (healthy) serviceReceive();
}
