#!/bin/sh
set -eu
TEST_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TEST_BIN=$(mktemp "${TMPDIR:-/tmp}/nusa-mobility-test.XXXXXX")
trap 'rm -f "$TEST_BIN"' EXIT
c++ -std=c++17 -Wall -Wextra -I"$TEST_DIR/stubs" -I"$TEST_DIR/../NusaNode" \
  "$TEST_DIR/mobility_test.cpp" "$TEST_DIR/../NusaNode/nusa_mobility.cpp" -o "$TEST_BIN"
"$TEST_BIN"
