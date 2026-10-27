#include <cassert>
#include <iostream>
#include <vector>
#include "../NusaNode/src/protocol/nusa_duplex_wire.h"
using namespace NusaDuplexWire;
int main() {
  assert(crc16((const uint8_t*)"123456789", 9) == 0x29b1);
  // Every supported payload size, binary zeros and long nonzero COBS runs.
  for (size_t size = 0; size <= MAX_PAYLOAD; ++size) {
    for (int pattern = 0; pattern < 3; ++pattern) {
      uint8_t data[MAX_PAYLOAD], encoded[MAX_ENCODED];
      for (size_t i = 0; i < size; ++i) data[i] = pattern == 0 ? 0 : pattern == 1 ? 0x7f : i;
      size_t len = encode(MSG_RX, 0x1234, data, size, encoded, sizeof(encoded));
      assert(len > 0 && len <= MAX_ENCODED && encoded[len - 1] == 0);
      Parser parser; Message decoded; bool ready = false;
      for (size_t i = 0; i < len; ++i) if (parser.feed(encoded[i], decoded)) ready = true;
      assert(ready && !parser.errors && decoded.type == MSG_RX && decoded.seq == 0x1234);
      assert(decoded.len == size && !memcmp(data, decoded.data, size));
    }
  }
  uint8_t encoded[MAX_ENCODED], data[] = {0,1,2,0,3,4};
  size_t len = encode(MSG_CONFIG, 4, data, sizeof(data), encoded, sizeof(encoded));
  // Truncation followed by delimiter, and overlong garbage, must resynchronize.
  Parser parser; Message message;
  for (size_t i = 0; i < len / 2; ++i) assert(!parser.feed(encoded[i], message));
  assert(!parser.feed(0, message));
  for (size_t i = 0; i < MAX_ENCODED + 20; ++i) assert(!parser.feed(1, message));
  assert(!parser.feed(0, message));
  uint32_t errors = parser.errors; assert(errors >= 2);
  for (size_t i = 0; i < len; ++i) parser.feed(encoded[i], message);
  assert(message.seq == 4 && message.len == sizeof(data) && parser.errors == errors);
  // Flip a data bit (not delimiter) and reject by CRC/COBS validation.
  encoded[3] ^= 0x40;
  bool accepted = false;
  for (size_t i = 0; i < len; ++i) accepted |= parser.feed(encoded[i], message);
  assert(!accepted && parser.errors > errors);
  Config config{922500,125000,7,5,0x12,8}, decoded;
  uint8_t packed[CONFIG_SIZE]; packConfig(packed, config);
  assert(unpackConfig(packed, sizeof(packed), decoded) && decoded.freqKhz == 922500);
  packed[8] = 1; assert(!unpackConfig(packed, sizeof(packed), decoded));
  assert(!encode(MSG_RX, 0, data, MAX_PAYLOAD + 1, encoded, sizeof(encoded)));
  std::cout << "PASS: binary framing all sizes/patterns, CRC, truncation, overflow, resync, config validation\n";
}
