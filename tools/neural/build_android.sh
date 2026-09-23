#!/bin/bash
# ニューラル変換のnative生成物（llama.cppの共有ライブラリとJNIの橋渡し）を、Gradleの外でAndroid arm64-v8a向けにビルドする。
# 出力先の<abi>/にstrip済みの.soを置き、manifest.txtへcommitとSHA-256を記録する。出力先をuzumi.neuralArtifactsDirに指定すると、
# Gradleが取り込む。llama.cppのソース、NDK、cmake・ninjaは.local-build/にあるもの（docs/neural-probe.md）を使う。
# 使い方: build_android.sh [出力先]
set -euo pipefail
LOCAL=/Users/marutyan/PrivateDev/uzumi/.local-build
COMMIT=66afb885a8ddcc0511798aec9fb5a01017941018
SRC=$LOCAL/neural-probe/src/llama.cpp-$COMMIT
NDK=$LOCAL/mozc-probe/mozc/src/third_party/ndk/android-ndk-r29
TOOLS=$LOCAL/neural-probe/tools/bin
OUT=${1:-$LOCAL/neural-artifacts}
ABI=arm64-v8a
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
BUILD=$LOCAL/neural-probe/build-android-bridge
mkdir -p "$BUILD" "$OUT/$ABI"
# CPU最適化はarmv8.2-a+dotprod（Pixel 6以降を想定した仮の値、docs/neural-probe.mdと同じ）までに留める。
"$TOOLS/cmake" -S "$REPO/app/src/main/cpp/neural" -B "$BUILD" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$TOOLS/ninja" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=$ABI \
  -DANDROID_PLATFORM=android-30 \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_C_FLAGS="-march=armv8.2-a+dotprod" \
  -DCMAKE_CXX_FLAGS="-march=armv8.2-a+dotprod" \
  -DLLAMA_SOURCE_DIR="$SRC" > "$BUILD/configure.log" 2>&1
"$TOOLS/cmake" --build "$BUILD" -j 8 --target uzumi_neural > "$BUILD/build.log" 2>&1
STRIP=$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip
rm -f "$OUT/$ABI"/*.so
for lib in libuzumi_neural.so libllama.so libggml.so libggml-base.so libggml-cpu.so; do
  path=$(find "$BUILD" -name "$lib" -type f | head -1)
  if [ -z "$path" ]; then echo "missing $lib" >&2; exit 1; fi
  "$STRIP" --strip-unneeded -o "$OUT/$ABI/$lib" "$path"
done
{
  echo "llama.cpp commit: $COMMIT"
  echo "bridge source: app/src/main/cpp/neural (repository commit $(cd "$REPO" && git rev-parse HEAD 2>/dev/null || echo unknown))"
  echo "ndk: $(basename "$NDK")"
  (cd "$OUT/$ABI" && shasum -a 256 *.so && ls -l *.so)
} > "$OUT/manifest.txt"
cat "$OUT/manifest.txt"
