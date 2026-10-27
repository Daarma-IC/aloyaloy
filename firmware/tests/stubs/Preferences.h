#pragma once
#include <cstdint>
#include <cstddef>
struct Preferences {
  bool begin(const char*, bool) { return true; }
  uint8_t getUChar(const char*, uint8_t fallback) { return fallback; }
  size_t putUChar(const char*, uint8_t) { return 1; }
  void end() {}
};
