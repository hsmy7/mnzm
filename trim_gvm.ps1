$file = "c:\Mnzm\XianxiaSectNative\android\app\src\main\java\com\xianxia\sect\ui\game\GameViewModel.kt"
$lines = [System.IO.File]::ReadAllLines($file)

$deleteRanges = @(
    @(262, 397),
    @(412, 413),
    @(416, 417),
    @(421, 427),
    @(495, 1398),
    @(1450, 1716),
    @(3015, 3227),
    @(3341, 3345)
)

$deleteSet = @{}
foreach ($range in $deleteRanges) {
    $start = $range[0]
    $end = $range[1]
    for ($i = $start; $i -le $end; $i++) {
        $deleteSet[$i] = $true
    }
}

$newLines = New-Object System.Collections.ArrayList
for ($i = 0; $i -lt $lines.Count; $i++) {
    if (-not $deleteSet.ContainsKey($i + 1)) {
        [void]$newLines.Add($lines[$i])
    }
}

[System.IO.File]::WriteAllLines($file, $newLines.ToArray(), [System.Text.Encoding]::UTF8)
Write-Host "Original: $($lines.Count) lines"
Write-Host "Deleted: $($deleteSet.Count) lines"
Write-Host "Remaining: $($newLines.Count) lines"
