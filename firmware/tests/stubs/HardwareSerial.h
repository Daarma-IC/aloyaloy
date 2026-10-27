#pragma once
#include "Arduino.h"
#include <deque>
#include <vector>
#define SERIAL_8N1 0
struct FakeUartState {
  std::deque<uint8_t> input;
  std::vector<uint8_t> output;
  int capacity = 4096;
};
inline FakeUartState& fakeUart(int id = 2) { static FakeUartState ports[3]; return ports[id]; }
struct HardwareSerial {
  int id;
  HardwareSerial(int port) : id(port) {}
  size_t setRxBufferSize(size_t size) { return size; }
  size_t setTxBufferSize(size_t size) { return size; }
  void begin(int, int, int, int) {}
  int availableForWrite() { return fakeUart(id).capacity; }
  int available() { return fakeUart(id).input.size(); }
  int read() { auto& q = fakeUart(id).input; if (q.empty()) return -1; int b = q.front(); q.pop_front(); return b; }
  size_t write(const uint8_t* data, size_t len) {
    auto& out = fakeUart(id).output; out.insert(out.end(), data, data + len); return len;
  }
};
