# Test timing analyzer (2026-08-14)
# Aggregates testsuite time from all modules' build/test-results/testReleaseUnitTest/*.xml,
# prints TopN slowest classes + totals. Read-only.
#
# Usage (from repo root or android/):
#   powershell -ExecutionPolicy Bypass -File android/scripts/test-timing.ps1
#   powershell -ExecutionPolicy Bypass -File android/scripts/test-timing.ps1 -Top 50
#   powershell -ExecutionPolicy Bypass -File android/scripts/test-timing.ps1 -Module engine
param(
    [int]$Top = 20,
    [string]$Module = "*"
)

$androidDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

# Recursive scan for test result XMLs (module paths are 1-2 levels deep: app, core\engine, ...)
$xmlFiles = Get-ChildItem "$androidDir" -Recurse -Filter "*.xml" -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -like "*build\test-results\testReleaseUnitTest*" -and $_.FullName -like "*$Module*" }

$results = $xmlFiles | ForEach-Object {
    [xml]$x = Get-Content $_.FullName -Raw
    [pscustomobject]@{
        Class    = $x.testsuite.name
        Time     = [double]$x.testsuite.time
        Tests    = [int]$x.testsuite.tests
        Failures = [int]$x.testsuite.failures
        Module   = ($_.FullName -replace [regex]::Escape($androidDir + '\'), '') -split '\\' | Select-Object -First 1
    }
}

if (-not $results) {
    Write-Host "No test result XML found (run tests first)"
    exit 1
}

Write-Host "=== Top $Top slowest test classes ==="
$results | Sort-Object Time -Descending | Select-Object -First $Top |
    Format-Table Class, Time, Tests, Failures, Module -AutoSize

Write-Host "=== Per-module totals ==="
$results | Group-Object Module | ForEach-Object {
    [pscustomobject]@{
        Module = $_.Name
        Classes = $_.Count
        TotalSec = [math]::Round(($_.Group | Measure-Object Time -Sum).Sum, 1)
    }
} | Format-Table -AutoSize

$total = ($results | Measure-Object Time -Sum).Sum
Write-Host ("ALL: {0} classes, {1}s" -f $results.Count, [math]::Round($total, 1))
$failed = $results | Where-Object { $_.Failures -gt 0 }
if ($failed) {
    Write-Host ("!!! Failed classes: {0}" -f ($failed.Class -join ', '))
}
