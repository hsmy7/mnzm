#!/usr/bin/env bash
# ============================================================
# build-desktop-jni-linux.sh - Build desktop JNI bridge for diff testing (CI/Linux)
#
# Output: android/core/engine/build/desktop-jni/libgamecorejni.so
#         (loaded by JUnit diff tests via -Dgamecore.jni.path)
#
# Mirrors scripts/build-desktop-jni.ps1 (Windows/local) with g++ instead of
# clang++ (llvm-mingw) — same source list, no Android deps.
# Usage: bash scripts/build-desktop-jni-linux.sh
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/android/app/src/main/cpp/gamecore"
OUT_DIR="$ROOT/android/core/engine/build/desktop-jni"
mkdir -p "$OUT_DIR"
OUT="$OUT_DIR/libgamecorejni.so"

# jni.h: vendored clean copy (gamecore/jni-include, from NDK sysroot — platform
# independent interface; a dedicated dir avoids Bionic/libc++ header conflicts)
JNI_INCLUDE="$SRC/jni-include"

# -ffp-contract=off：FP 确定性钉死（R0.2）——桌面对拍基线与 arm64 真机
# （NDK CMake 同选项）位一致的前提，缺省 FMA 融合会造成跨架构漂移
g++ -shared -fPIC -std=c++20 -O2 -ffp-contract=off \
    -I "$SRC/include" \
    -I "$SRC/third_party" \
    -I "$JNI_INCLUDE" \
    "$SRC/jni/GameCoreJni.cpp" \
    "$SRC/src/rng.cpp" \
    "$SRC/src/game_core.cpp" \
    "$SRC/src/json_codec.cpp" \
    "$SRC/src/execute_dispatch.cpp" \
    "$SRC/src/dispatch_w4a.cpp" \
    "$SRC/src/dispatch_w4b.cpp" \
    "$SRC/src/dispatch_w4c.cpp" \
    "$SRC/src/dispatch_w4d.cpp" \
    "$SRC/src/dirty_tracker.cpp" \
    "$SRC/src/disciple_store.cpp" \
    -o "$OUT"

echo "Desktop JNI diff library generated: $OUT"
echo "Run diff tests (note: quote the -D argument):"
echo "  ./gradlew.bat :core:engine:testReleaseUnitTest --tests 'com.xianxia.sect.core.nativebridge.DiffRngTest' \"-Dgamecore.jni.path=$OUT\" --max-workers=1"
