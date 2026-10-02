#!/bin/sh
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/third_party/codec2/src"
GEN="$ROOT/composeApp/src/nativeInterop/codec2-generated"
BRIDGE="$ROOT/composeApp/src/nativeInterop/codec2"
OUT="$ROOT/composeApp/build/codec2-ios"
SOURCES="codec2.c codec2_fft.c dump.c interp.c kiss_fft.c kiss_fftr.c lpc.c lsp.c mbest.c newamp1.c nlp.c pack.c phase.c postfilter.c quantise.c sine.c"

build_arch() {
  sdk="$1"; arch="$2"; minflag="$3"; dir="$OUT/$sdk-$arch"
  mkdir -p "$dir"
  sdkpath="$(xcrun --sdk "$sdk" --show-sdk-path)"
  cc="$(xcrun --sdk "$sdk" --find clang)"
  objects=""
  for name in $SOURCES; do
    obj="$dir/${name%.c}.o"; "$cc" -arch "$arch" -isysroot "$sdkpath" "$minflag" -O2 -fembed-bitcode -DCODEC2_MODE_EN_DEFAULT=0 -DCODEC2_MODE_3200_EN=1 -I"$SRC" -I"$BRIDGE/include" -c "$SRC/$name" -o "$obj"; objects="$objects $obj"
  done
  for file in "$GEN"/codebook.c "$GEN"/codebookd.c "$GEN"/codebookjmv.c "$GEN"/codebookge.c "$GEN"/codebooknewamp1.c "$GEN"/codebooknewamp1_energy.c "$BRIDGE/codec2_bridge.c"; do
    obj="$dir/$(basename "${file%.c}").o"; "$cc" -arch "$arch" -isysroot "$sdkpath" "$minflag" -O2 -fembed-bitcode -DCODEC2_MODE_EN_DEFAULT=0 -DCODEC2_MODE_3200_EN=1 -I"$SRC" -I"$BRIDGE" -I"$BRIDGE/include" -c "$file" -o "$obj"; objects="$objects $obj"
  done
  xcrun ar rcs "$dir/libnusamesh_codec2.a" $objects
}

mkdir -p "$BRIDGE/include/codec2" "$OUT/iphoneos" "$OUT/iphonesimulator"
cp "$BRIDGE/codec2_bridge.h" "$BRIDGE/include/"
printf '%s\n' '#define CODEC2_HAVE_VERSION' '#define CODEC2_VERSION_MAJOR 1' '#define CODEC2_VERSION_MINOR 2' '#define CODEC2_VERSION_PATCH 0' '#define CODEC2_VERSION "1.2.0"' > "$BRIDGE/include/codec2/version.h"
build_arch iphoneos arm64 -miphoneos-version-min=14.0
build_arch iphonesimulator arm64 -mios-simulator-version-min=14.0
build_arch iphonesimulator x86_64 -mios-simulator-version-min=14.0
cp "$OUT/iphoneos-arm64/libnusamesh_codec2.a" "$OUT/iphoneos/"
xcrun lipo -create "$OUT/iphonesimulator-arm64/libnusamesh_codec2.a" "$OUT/iphonesimulator-x86_64/libnusamesh_codec2.a" -output "$OUT/iphonesimulator/libnusamesh_codec2.a"
