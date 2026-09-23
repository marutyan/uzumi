#!/bin/bash
# 段階0の推論プログラム（stage0_probe.cpp）を、Mac向けにビルド済みのllama.cpp（66afb885）の静的ライブラリへリンクする。
# llama.cppのソースとビルドはGit追跡外の.local-build/neural-probe/にあり、docs/neural-probe.mdの手順で作ったもの。
# 使い方: build_stage0_probe.sh [出力先]
set -euo pipefail
NP=/Users/marutyan/PrivateDev/uzumi/.local-build/neural-probe
SRC=$NP/src/llama.cpp-66afb885a8ddcc0511798aec9fb5a01017941018
BUILD=$NP/build-mac-b9637
OUT=${1:-/Users/marutyan/PrivateDev/uzumi/.local-build/phase3a/bin/stage0_probe}
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$(dirname "$OUT")"
LIBS=$(find "$BUILD" -name 'lib*.a' ! -name 'libcommon.a' ! -name 'libllava*' ! -name 'libmtmd*' ! -name 'libcpp-httplib*' | sort)
# Appleのldは静的ライブラリ間の参照順に依存しないため、一覧を1回だけ渡す。
clang++ -std=c++17 -O2 -I"$SRC/include" -I"$SRC/ggml/include" "$HERE/stage0_probe.cpp" \
  $LIBS \
  -framework Metal -framework Foundation -framework Accelerate -framework MetalKit \
  -o "$OUT"
ls -l "$OUT"
