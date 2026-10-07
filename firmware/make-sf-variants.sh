#!/usr/bin/env bash
# Buat ulang firmware per-SF (sf-variants/NusaNode_SF7 .. NusaNode_SF12) dari sumber utama NusaNode/.
# Jalankan lagi tiap kali NusaNode/ diubah — JANGAN edit file di sf-variants/ (akan tertimpa),
# kecuali NODE_ID/NODE_NAME di config.h sebelum flash tiap node.
set -euo pipefail
cd "$(dirname "$0")"
for sf in 7 8 9 10 11 12; do
  dir="sf-variants/NusaNode_SF$sf"
  rm -rf "$dir" && mkdir -p "$dir"
  cp NusaNode/*.h NusaNode/*.cpp "$dir/"
  cp NusaNode/NusaNode.ino "$dir/NusaNode_SF$sf.ino"      # Arduino: nama .ino = nama folder
  {
    echo "// VARIAN SF$sf — dibuat otomatis oleh firmware/make-sf-variants.sh dari NusaNode/."
    echo "// SF dikunci: tidak bisa diganti dari app, SF lama di memori node diabaikan."
    echo "#define LORA_SF        $sf"
    echo "#define LORA_SF_FIXED  1"
    cat NusaNode/config.h
  } > "$dir/config.h"
done
echo "Varian dibuat: $(ls sf-variants | tr '\n' ' ')"
