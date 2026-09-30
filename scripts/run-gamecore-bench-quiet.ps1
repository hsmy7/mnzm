# ============================================================
# run-gamecore-bench-quiet.ps1 - 安静窗口跑 game-core 性能门禁（bench）
#
# 为什么需要：bench 是**墙钟口径**门禁（积分段 / 旬结算 / 差分耗时），
# 与其它构建或测试并发时会因 CPU 与缓存争抢产生假红——假红既掩盖真实退化，
# 也消耗排查成本。本脚本在启动前探测整机负载，不满足安静窗口即**拒绝运行**
# （不降级、不忽略），把"安静窗口"从口头纪律变成可执行入口。
#
# 前置：先完成桌面测试构建（GAMECORE_BUILD_BENCH=ON）
# 用法：pwsh -File scripts/run-gamecore-bench-quiet.ps1
#       pwsh -File scripts/run-gamecore-bench-quiet.ps1 -Filter 'AccrualSegmentBench'
# ============================================================
param(
    [string]$BuildDir = "$PSScriptRoot\..\android\app\src\main\cpp\gamecore\build\desktop-test",
    [string]$Filter = 'Bench\.',
    [string]$ToolchainPath = "$env:USERPROFILE\llvm-mingw",
    [double]$MaxLoadPercent = 20.0,
    [int]$ProbeMillis = 1500,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

# ── ctest 解析：PATH 优先，回退到 Android SDK 自带（本机 PATH 常不含 ctest）
function Resolve-CTest {
    $cmd = Get-Command ctest -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    # @() 包裹：单元素时若不加会把字符串当数组索引（$x[0] 取到首字符）
    $candidates = @(
        Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\cmake" -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { Join-Path $_.FullName 'bin\ctest.exe' } |
            Where-Object { Test-Path $_ }
    )
    if ($candidates.Count -gt 0) { return $candidates[0] }
    return $null
}

# ── 整机 CPU 利用率：进程 CPU 时间增量 / 窗口秒数 / 逻辑核数
function Get-SystemLoadPercent {
    param([int]$Millis)
    $before = (Get-Process -ErrorAction SilentlyContinue | Measure-Object -Property CPU -Sum).Sum
    Start-Sleep -Milliseconds $Millis
    $after = (Get-Process -ErrorAction SilentlyContinue | Measure-Object -Property CPU -Sum).Sum
    if ($null -eq $before -or $null -eq $after) { return 0.0 }
    $logical = [Environment]::ProcessorCount
    return (($after - $before) / ($Millis / 1000.0) / $logical) * 100.0
}

# ── 前置 1：无并发构建/测试进程（存在即强信号，不需看利用率）
$busyNames = @('ctest', 'cmake', 'ninja', 'clang', 'clang++', 'cl', 'msbuild', 'javac')
$busy = @(Get-Process -ErrorAction SilentlyContinue |
    Where-Object { $busyNames -contains $_.ProcessName.ToLowerInvariant() })
if ($busy.Count -gt 0 -and -not $Force) {
    Write-Host "[拒绝] 检测到构建/测试进程并发运行，bench 取数不可信：" -ForegroundColor Red
    $busy | ForEach-Object { Write-Host "  - $($_.ProcessName) (PID $($_.Id))" }
    Write-Host "请等其结束后重跑。"
    exit 2
}

# ── 前置 2：构建目录存在
if (-not (Test-Path $BuildDir)) {
    Write-Host "[拒绝] 构建目录不存在：$BuildDir" -ForegroundColor Red
    Write-Host "请先配置并构建：cmake -B build -DGAMECORE_BUILD_TESTS=ON -DGAMECORE_BUILD_BENCH=ON -DCMAKE_BUILD_TYPE=Release"
    exit 3
}

# ── 前置 3：整机负载低于阈值
$load = Get-SystemLoadPercent -Millis $ProbeMillis
$loadText = '{0:N1}%' -f $load
if ($load -gt $MaxLoadPercent -and -not $Force) {
    Write-Host "[拒绝] 整机 CPU 利用率 $loadText 高于阈值 $MaxLoadPercent%（探测窗口 $ProbeMillis ms）。" -ForegroundColor Red
    Write-Host "bench 是墙钟口径门禁，请降低背景负载后重跑（随时可重跑）。"
    exit 4
}
if ($Force) {
    Write-Host "[警告] -Force 已跳过前置检查；本次取数仅供诊断，不可作门禁结论。" -ForegroundColor Yellow
}

$ctest = Resolve-CTest
if (-not $ctest) {
    Write-Host "[拒绝] 未找到 ctest（PATH 与 Android SDK cmake 均无）。" -ForegroundColor Red
    exit 5
}

# ── 工具链运行库 PATH：bench 可执行依赖 llvm-mingw 的 libstdc++/libgcc/winpthread
# DLL，缺则全部用例以 0xc0000135 假失败。探测约定与 scripts/build-desktop-jni.ps1
# 一致：$ToolchainPath\bin 或 $ToolchainPath\llvm-mingw*\bin。
function Add-ToolchainToPath {
    param([string]$Root)
    if (-not (Test-Path $Root)) { return $false }
    $bin = Join-Path $Root 'bin'
    if (-not (Test-Path (Join-Path $bin 'clang++.exe'))) {
        $sub = Get-ChildItem $Root -Directory -Filter 'llvm-mingw*' -ErrorAction SilentlyContinue |
            Where-Object { Test-Path (Join-Path $_.FullName 'bin\clang++.exe') } |
            Select-Object -First 1
        if ($sub) { $bin = Join-Path $sub.FullName 'bin' }
    }
    if (-not (Test-Path (Join-Path $bin 'clang++.exe'))) { return $false }
    $archBin = Join-Path (Split-Path -Parent $bin) 'x86_64-w64-mingw32\bin'
    $env:PATH = "$bin;$archBin;$env:PATH"
    return $true
}
if (Add-ToolchainToPath -Root $ToolchainPath) {
    Write-Host "[工具链] 已注入 llvm-mingw 运行库路径（$ToolchainPath）"
} else {
    Write-Host "[警告] 未在 $ToolchainPath 找到 llvm-mingw；若用例报 0xc0000135，请用 -ToolchainPath 指定工具链根目录。" -ForegroundColor Yellow
}

Write-Host "[安静窗口] 整机 CPU 利用率 $loadText（阈值 $MaxLoadPercent%）" -ForegroundColor Green
Write-Host "[执行] $ctest --test-dir $BuildDir -R '$Filter'（串行；bench 用例已设 RUN_SERIAL + RESOURCE_LOCK）"
& $ctest --test-dir $BuildDir -R $Filter --output-on-failure
exit $LASTEXITCODE
