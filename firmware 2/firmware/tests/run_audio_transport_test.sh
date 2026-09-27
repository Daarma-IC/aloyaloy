#!/bin/sh
set -eu
TEST_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TEST_BIN=$(mktemp "${TMPDIR:-/tmp}/nusa-audio-test.XXXXXX")
trap 'rm -f "$TEST_BIN"' EXIT
c++ -std=c++17 -pthread -I"$TEST_DIR/stubs" -I"$TEST_DIR/../NusaNode" \
  "$TEST_DIR/audio_transport_test.cpp" "$TEST_DIR/../NusaNode/nusa_fragment.cpp" \
  "$TEST_DIR/../NusaNode/nusa_radio.cpp" -o "$TEST_BIN"
"$TEST_BIN"
