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

& $clang -shared -fPIC -std=c++20 -O2 `
    @staticArgs `
    -I (Join-Path $src 'include') `
    -I (Join-Path $src 'third_party') `
    @jniIncludeArgs `
    (Join-Path $src 'jni\GameCoreJni.cpp') `
    (Join-Path $src 'src\rng.cpp') `
    (Join-Path $src 'src\game_core.cpp') `
    (Join-Path $src 'src\json_codec.cpp') `
    (Join-Path $src 'src\execute_dispatch.cpp') `
    (Join-Path $src 'src\dirty_tracker.cpp') `
    (Join-Path $src 'src\disciple_store.cpp') `
    -o $out

if ($LASTEXITCODE -ne 0) { throw "Build failed (exit=$LASTEXITCODE)" }
Write-Host "Desktop JNI diff library generated: $out"
Write-Host "Run diff tests (note: quote the -D argument):"
Write-Host "  ./gradlew.bat :core:engine:testReleaseUnitTest --tests 'com.xianxia.sect.core.nativebridge.DiffRngTest' `"-Dgamecore.jni.path=$out`" --max-workers=1"
