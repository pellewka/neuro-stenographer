#!/usr/bin/env bash
# Downloads everything that is too large for git: llama.cpp sources and the
# on-device models bundled into the APK assets. Safe to re-run.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets/models"
LLAMA_DIR="$ROOT/third_party/llama.cpp"
LLAMA_COMMIT="cf659f240b232e15700b1721bbb43c1d973c52e9"
SHERPA_AAR="$ROOT/app/libs/sherpa-onnx-2.0.1.aar"

GH="https://github.com/k2-fsa/sherpa-onnx/releases/download"
GIGAAM="sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16"
PYANNOTE="sherpa-onnx-pyannote-segmentation-3-0"
QWEN_URL="https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"

fetch() { # url dest
  if [ -s "$2" ]; then echo "ok   $2"; return; fi
  echo "get  $1"
  curl -fL --retry 3 -o "$2.part" "$1" && mv "$2.part" "$2"
}

mkdir -p "$ASSETS/gigaam-v3-punct" "$ROOT/third_party" "$(dirname "$SHERPA_AAR")"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fetch "$GH/v2.0.1/sherpa-onnx-2.0.1.aar" "$SHERPA_AAR"

if [ ! -s "$ASSETS/gigaam-v3-punct/encoder.int8.onnx" ]; then
  fetch "$GH/asr-models/$GIGAAM.tar.bz2" "$TMP/gigaam.tar.bz2"
  tar xjf "$TMP/gigaam.tar.bz2" -C "$TMP"
  cp "$TMP/$GIGAAM"/{encoder.int8.onnx,decoder.onnx,joiner.onnx,tokens.txt,LICENSE} "$ASSETS/gigaam-v3-punct/"
fi

if [ ! -s "$ASSETS/pyannote-segmentation-3.0.onnx" ]; then
  fetch "$GH/speaker-segmentation-models/$PYANNOTE.tar.bz2" "$TMP/pyannote.tar.bz2"
  tar xjf "$TMP/pyannote.tar.bz2" -C "$TMP"
  cp "$TMP/$PYANNOTE/model.onnx" "$ASSETS/pyannote-segmentation-3.0.onnx"
fi

fetch "$GH/speaker-recongition-models/3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common.onnx" \
  "$ASSETS/3dspeaker-eres2netv2.onnx"
fetch "$GH/asr-models/silero_vad.onnx" "$ASSETS/silero_vad.onnx"
fetch "$GH/speech-enhancement-models/dpdfnet2.onnx" "$ASSETS/dpdfnet2.onnx"
fetch "$GH/source-separation-models/UVR-MDX-NET-Voc_FT.onnx" "$ASSETS/uvr-mdx-voc-ft.onnx"
fetch "$QWEN_URL" "$ASSETS/qwen3-1.7b-q4_k_m.gguf"

if [ ! -f "$LLAMA_DIR/CMakeLists.txt" ]; then
  git clone --filter=blob:none https://github.com/ggml-org/llama.cpp "$LLAMA_DIR"
  git -C "$LLAMA_DIR" checkout -q "$LLAMA_COMMIT"
fi

echo "Done. Models:"
du -sh "$ASSETS"/*
