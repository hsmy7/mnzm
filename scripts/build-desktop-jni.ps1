# ============================================================
# build-desktop-jni.ps1 - Build desktop JNI bridge for diff testing (Windows local)
#
# Output: android/core/engine/build/desktop-jni/libgamecorejni.so
#         (loaded by JUnit diff tests via -Dgamecore.jni.path)
#
# Requires: llvm-mingw (portable) - default C:\Users\<user>\llvm-mingw
#           or pass -ToolchainPath <dir>
# Usage: pwsh -File scripts/build-desktop-jni.ps1
# ============================================================
param(
    [string]$ToolchainPath = "$env:USERPROFILE\llvm-mingw"
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root 'android\app\src\main\cpp\gamecore'
$outDir = Join-Path $root 'android\core\engine\build\desktop-jni'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# Probe toolchain bin dir (support $ToolchainPath\bin or $ToolchainPath\<llvm-mingw-*>\bin)
$toolchainBin = Join-Path $ToolchainPath 'bin'
if (-not (Test-Path (Join-Path $toolchainBin 'clang++.exe'))) {
    $sub = Get-ChildItem $ToolchainPath -Directory -Filter 'llvm-mingw*' -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin\clang++.exe') } | Select-Object -First 1
    if ($sub) { $toolchainBin = Join-Path $sub.FullName 'bin' }
}
$clang = Join-Path $toolchainBin 'clang++.exe'
if (-not (Test-Path $clang)) {
    Write-Error "llvm-mingw clang++ not found: $clang (download from https://github.com/mstorsjo/llvm-mingw/releases and extract to $ToolchainPath)"
}

# include paths: gamecore include + third_party + jni.h
# jni.h: vendored clean copy (gamecore/jni-include, from NDK sysroot — platform
# independent interface; a dedicated dir avoids Bionic/libc++ header conflicts)
$jniIncludeArgs = @('-I', (Join-Path $src 'jni-include'))

$out = Join-Path $outDir 'libgamecorejni.so'

# -static: link libc++/libunwind statically -> self-contained .so (no runtime DLL deps)
$staticArgs = @('-static', '-static-libgcc', '-static-libstdc++')

# -ffp-contract=off：FP 确定性钉死（R0.2）——桌面对拍基线与 arm64 真机
# （NDK CMake 同选项）位一致的前提，缺省 FMA 融合会造成跨架构漂移
#
# 源清单 = glob（src/*.cpp + jni/GameCoreJni.cpp）：不逐文件硬编码——新增 .cpp 自动入编，
# 避免 ps1/sh/CMake 三处独立登记漏编（漏编只在链接期/运行期以缺符号暴露）。
$sources = @(
    (Get-ChildItem (Join-Path $src 'src') -Filter '*.cpp' -File | Sort-Object Name | ForEach-Object { $_.FullName })
    (Join-Path $src 'jni\GameCoreJni.cpp')
)

& $clang -shared -fPIC -std=c++20 -O2 -ffp-contract=off `
    @staticArgs `
    -I (Join-Path $src 'include') `
    -I (Join-Path $src 'third_party') `
    @jniIncludeArgs `
    @sources `
    -o $out

if ($LASTEXITCODE -ne 0) { throw "Build failed (exit=$LASTEXITCODE)" }
Write-Host "Desktop JNI diff library generated: $out"

# 同源指纹旁挂文件（<so>.fingerprint）：DiffBridgeSourceSyncGuardTest 逐文件校验，
# 桥与 C++ 源码不同源（改源未重编 / 旧产物复制入树）时该守卫判红并给出重建指令
$fingerprintScript = Join-Path $PSScriptRoot '..\android\scripts\desktop-jni-fingerprint.mjs'
if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    throw "node 未安装：无法生成对拍桥同源指纹（$out.fingerprint），守卫测试将判红"
}
& node $fingerprintScript $out
if ($LASTEXITCODE -ne 0) { throw "同源指纹生成失败 (exit=$LASTEXITCODE)" }

Write-Host "Run diff tests (note: quote the -D argument):"
Write-Host "  ./gradlew.bat :core:engine:testReleaseUnitTest --tests 'com.xianxia.sect.core.nativebridge.DiffRngTest' `"-Dgamecore.jni.path=$out`" --max-workers=1"
