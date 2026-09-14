<#
.SYNOPSIS
    W4 三批次并行的**构建串行令牌**（W4-00 并行前置批新增）。

.DESCRIPTION
    三个并行批次在各自 git worktree 上施工，但共享同一台机器的 Gradle daemon、
    KSP 缓存与 CMake 构建目录。历史实测（findings.md）已记录三类互踩：

      1. Windows 并行会话共享 Gradle daemon 的 `classes.jar` 文件锁，可持续数分钟；
      2. KSP 增量缓存在多进程并行下频繁损坏（症状：`NoSuchFileException *_Impl.java`）；
      3. `ctest` 的 per-test 进程会在 exe 被并行构建复写时给出**单帧假失败**。

    因此约定：**重活必须先取令牌**。轻活（单模块 `compileReleaseKotlin`）不必取，
    失败即重试。

    重活清单（必须取令牌）：
      - Gradle 全量单测（`:core:engine:testReleaseUnitTest` / `testReleaseUnitTest`）
      - `detekt`（六模块）
      - `:app:externalNativeBuildRelease`（NDK）与 `:app:lintRelease`
      - 桌面 GTest 构建与全量跑（`cmake --build build/desktop-test`）
      - 桌面 JNI 重建（`scripts/build-desktop-jni.ps1`）

.PARAMETER Acquire
    获取令牌（阻塞等待直到拿到或超时）。

.PARAMETER Release
    释放令牌（只有持有者可释放）。

.PARAMETER Status
    查看当前持有者（不获取）。

.PARAMETER Batch
    批次标识（如 w4a / w4b / w4c / w4-00），写入锁文件便于定位。

.PARAMETER TimeoutSec
    等待上限（默认 3600 秒）；超时以退出码 2 结束并打印持有者。

.EXAMPLE
    pwsh -File scripts/w4/build-token.ps1 -Acquire -Batch w4a
    ./gradlew.bat :core:engine:testReleaseUnitTest --max-workers=1
    pwsh -File scripts/w4/build-token.ps1 -Release -Batch w4a
#>
[CmdletBinding(DefaultParameterSetName = 'Status')]
param(
    [Parameter(ParameterSetName = 'Acquire')][switch]$Acquire,
    [Parameter(ParameterSetName = 'Release')][switch]$Release,
    [Parameter(ParameterSetName = 'Status')][switch]$Status,
    [string]$Batch = $env:DSH_W4_BATCH,
    [int]$TimeoutSec = 3600
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# 锁文件放在**主仓之外**：三个 worktree 各自位于独立目录，必须共享同一把锁，
# 故不能用工作树内路径（否则各持一把形同虚设）。
$LockPath = if ($env:W4_BUILD_LOCK) { $env:W4_BUILD_LOCK } else { 'C:\Mnzm\.w4-build.lock' }
$LockDir = Split-Path -Parent $LockPath
if (-not (Test-Path $LockDir)) { New-Item -ItemType Directory -Force -Path $LockDir | Out-Null }

if ([string]::IsNullOrWhiteSpace($Batch)) { $Batch = 'unknown' }

function Get-Holder {
    if (-not (Test-Path $LockPath)) { return $null }
    try { return (Get-Content -LiteralPath $LockPath -Raw -ErrorAction Stop).Trim() }
    catch { return '(锁文件读取失败/正被写入)' }
}

function Try-OpenLock {
    # FileShare::None ⇒ 拿到独占句柄；别人持有则抛 IOException
    try {
        return [System.IO.File]::Open(
            $LockPath, [System.IO.FileMode]::OpenOrCreate,
            [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
    } catch [System.IO.IOException] {
        return $null
    }
}

if ($Status -or (-not $Acquire -and -not $Release)) {
    $h = Get-Holder
    if ($null -eq $h) { Write-Output "build-token: FREE ($LockPath 不存在)" }
    else { Write-Output "build-token: HELD -> $h" }
    exit 0
}

if ($Release) {
    $stream = Try-OpenLock
    if ($null -eq $stream) {
        Write-Warning "build-token: 无法独占锁文件——持有者不是你，或令牌未被正确释放。持有者：$(Get-Holder)"
        exit 3
    }
    try {
        $stream.SetLength(0)
        $stream.Flush()
    } finally {
        $stream.Dispose()
    }
    Remove-Item -LiteralPath $LockPath -Force -ErrorAction SilentlyContinue
    Write-Output "build-token: RELEASED (batch=$Batch)"
    exit 0
}

# ── Acquire ────────────────────────────────────────────────────────────
$deadline = (Get-Date).AddSeconds($TimeoutSec)
$stream = Try-OpenLock
while ($null -eq $stream) {
    if ((Get-Date) -ge $deadline) {
        Write-Error ("build-token: 等待超时（${TimeoutSec}s）。当前持有者：" + (Get-Holder) +
            "`n提示：确认持有者是否已崩溃；确认后可用 -Release 强制接管。")
        exit 2
    }
    Start-Sleep -Seconds 5
    $stream = Try-OpenLock
}

try {
    $holder = "{0} | batch={1} | pid={2} | host={3} | acquired={4:o}" -f `
        'W4-BUILD-LOCK', $Batch, $PID, $env:COMPUTERNAME, (Get-Date)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($holder)
    $stream.SetLength(0)
    $stream.Write($bytes, 0, $bytes.Length)
    $stream.Flush()
} catch {
    $stream.Dispose()
    throw
}

Write-Output "build-token: ACQUIRED by batch=$Batch (pid=$PID)"
Write-Output "  锁文件：$LockPath"
Write-Output "  🔴 用完必须执行：pwsh -File scripts/w4/build-token.ps1 -Release -Batch $Batch"
# 句柄随进程退出而释放；锁文件保留作为「上次持有者」记录，由 -Release 删除。
$stream.Dispose()
exit 0
