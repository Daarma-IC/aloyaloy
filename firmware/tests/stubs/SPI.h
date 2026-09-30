#pragma once
#define VSPI 0
#define MSBFIRST 0
#define SPI_MODE0 0
struct SPISettings { SPISettings(int,int,int) {} };
struct SPIClass { SPIClass(int) {} void begin(int,int,int,int) {} };
