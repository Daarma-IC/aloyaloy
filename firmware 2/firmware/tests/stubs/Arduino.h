#pragma once
#include <cstdint>
#include <cstddef>
#include <cstring>
#include <cmath>
#include <algorithm>
#include <mutex>
using std::min;
using std::max;
extern uint32_t testMillis;
inline uint32_t millis() { return testMillis; }
inline long random(long) { return 1; }
struct SerialStub { template<class... T> void printf(const char*, T...) {} void println(const char*) {} };
inline SerialStub Serial;
using portMUX_TYPE = std::mutex;
#define portMUX_INITIALIZER_UNLOCKED {}
#define portENTER_CRITICAL(m) (m)->lock()
#define portEXIT_CRITICAL(m) (m)->unlock()
