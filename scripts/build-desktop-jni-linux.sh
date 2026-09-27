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
#
# 源清单 = glob（src/*.cpp + jni/GameCoreJni.cpp）：不逐文件硬编码——新增 .cpp 自动入编，
# 避免 ps1/sh/CMake 三处独立登记漏编（漏编只在链接期/运行期以缺符号暴露）。
mapfile -t SOURCES < <(find "$SRC/src" -maxdepth 1 -name '*.cpp' | LC_ALL=C sort)
SOURCES+=("$SRC/jni/GameCoreJni.cpp")

g++ -shared -fPIC -std=c++20 -O2 -ffp-contract=off \
    -I "$SRC/include" \
    -I "$SRC/third_party" \
    -I "$JNI_INCLUDE" \
    "${SOURCES[@]}" \
    -o "$OUT"

# 同源指纹旁挂文件（<so>.fingerprint）：DiffBridgeSourceSyncGuardTest 逐文件校验，
# 桥与 C++ 源码不同源（改源未重编 / 旧产物复制入树）时该守卫判红并给出重建指令
node "$ROOT/android/scripts/desktop-jni-fingerprint.mjs" "$OUT"

echo "Desktop JNI diff library generated: $OUT"
echo "Run diff tests (note: quote the -D argument):"
echo "  ./gradlew.bat :core:engine:testReleaseUnitTest --tests 'com.xianxia.sect.core.nativebridge.DiffRngTest' \"-Dgamecore.jni.path=$OUT\" --max-workers=1"
