#pragma once
#include <Arduino.h>
namespace NusaAdmission {
using Handler=void (*)(const uint8_t*,size_t,uint8_t);
using Reply=bool (*)(const uint8_t*,size_t,uint8_t);
void dispatch(const uint8_t* data,size_t len,uint8_t client,Handler handler,Reply reply);
void disconnected(uint8_t client);
}
