#!/usr/bin/env bash
# Unduh semua model super-resolution (TFLite Qualcomm AI Hub + baseline OpenCV) dan set foto uji.
# Semua model BSD-3 (ESRGAN: Apache-2.0); BSD100/Urban100 = set benchmark SR standar (riset).
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p models data
QAI=https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models
VER=v0.63.0
for m in xlsr quicksrnetsmall quicksrnetmedium quicksrnetlarge sesr_m5 real_esrgan_general_x4v3; do
  for q in float w8a8; do
    [ -d "models/$m-$q" ] && continue
    curl -fL --retry 3 -o "models/$m-$q.zip" "$QAI/$m/releases/$VER/$m-tflite-$q.zip"
    unzip -qo "models/$m-$q.zip" -d "models/$m-$q" && rm "models/$m-$q.zip"
  done
done
if [ ! -d models/esrgan-float ]; then   # besar (±67 MB), pembanding kelas berat
  curl -fL --retry 3 -o models/esrgan-float.zip "$QAI/esrgan/releases/$VER/esrgan-tflite-float.zip"
  unzip -qo models/esrgan-float.zip -d models/esrgan-float && rm models/esrgan-float.zip
fi
# Baseline klasik untuk modul dnn_superres OpenCV
curl -fL --retry 3 -o models/FSRCNN_x4.pb https://raw.githubusercontent.com/Saafke/FSRCNN_Tensorflow/master/models/FSRCNN_x4.pb
curl -fL --retry 3 -o models/ESPCN_x4.pb https://raw.githubusercontent.com/fannymonori/TF-ESPCN/master/export/ESPCN_x4.pb
curl -fL --retry 3 -o models/LapSRN_x4.pb https://raw.githubusercontent.com/fannymonori/TF-LapSRN/master/export/LapSRN_x4.pb
for d in BSD100 Urban100; do
  [ -d "data/$d" ] && continue
  curl -fL --retry 3 -o "data/${d}_HR.tar.gz" "https://huggingface.co/datasets/eugenesiow/$d/resolve/main/data/${d}_HR.tar.gz"
  mkdir -p "data/$d" && tar -xzf "data/${d}_HR.tar.gz" -C "data/$d" && rm "data/${d}_HR.tar.gz"
done
echo "Selesai. Model di models/, foto uji di data/."
