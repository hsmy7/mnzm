<#
.SYNOPSIS
    安全清理 W4 三个并行工作树（W4-00 并行前置批新增）。

.DESCRIPTION
    🔴 **为什么不能直接 `Remove-Item -Recurse -Force`**：
    worktree 里的 `node_modules` 可能是指回主仓的**目录联接（junction）**，
    PowerShell 的 `Remove-Item -Recurse` 会**沿联接删除目标内容**——
    findings.md 记录了真实事故：清理游离 worktree 时把主仓真实的
    `node_modules` 内容删掉了（症状：`build-atlas.mjs` 报 `ERR_MODULE_NOT_FOUND: sharp`）。

    本脚本的做法：
      1. 逐个探测 reparse point（`Get-Item -Force` 的 `LinkType`/`Target`）；
      2. 联接/符号链接一律用 `cmd /c rmdir`（只删链接本身，不跟随）；
      3. 其余目录才用 `Remove-Item -Recurse`；
      4. 最后 `git worktree prune` + 可选删分支。

.PARAMETER RemoveBranches
    同时删除三个 W4 分支（未合并时会失败，属预期保护）。

.PARAMETER ArchiveTagPrefix
    删分支前先打归档 tag（默认 `archive/w4`；空串则不打）。
#>
[CmdletBinding()]
param(
    [string]$MainRepo = '',
    [string]$BaseDir = '',
    [switch]$RemoveBranches,
    [string]$ArchiveTagPrefix = 'archive/w4'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($MainRepo)) {
    $MainRepo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}
if ([string]::IsNullOrWhiteSpace($BaseDir)) {
    $BaseDir = Split-Path -Parent $MainRepo
}

$repoName = Split-Path -Leaf $MainRepo
$worktrees = @(
    @{ Id = 'w4a'; Branch = 'w4/a-disciple-building' },
    @{ Id = 'w4b'; Branch = 'w4/b-court-economy' },
    @{ Id = 'w4c'; Branch = 'w4/c-battle-world' }
)

function Remove-Safely {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return }

    Get-ChildItem -LiteralPath $Path -Force | ForEach-Object {
        $item = $_
        $isLink = $null -ne $item.LinkType -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)
        if ($isLink) {
            Write-Output "  [link] 只删链接本身：$($item.Name) -> $($item.Target)"
            if ($item.PSIsContainer) {
                & cmd /c rmdir "`"$($item.FullName)`"" | Out-Null
            } else {
                Remove-Item -LiteralPath $item.FullName -Force
            }
        } elseif ($item.PSIsContainer) {
            Remove-Safely -Path $item.FullName
        } else {
            Remove-Item -LiteralPath $item.FullName -Force
        }
    }
    Remove-Item -LiteralPath $Path -Force
}

foreach ($w in $worktrees) {
    $path = Join-Path $BaseDir ("{0}-{1}" -f $repoName, $w.Id)
    if (-not (Test-Path -LiteralPath $path)) {
        Write-Output "[skip] 不存在：$path"
        continue
    }
    Write-Output "[remove] $path"
    # 先让 git 解除登记（无论目录随后如何删）
    & git -C $MainRepo worktree remove --force $path 2>$null
    if (Test-Path -LiteralPath $path) { Remove-Safely -Path $path }
}

& git -C $MainRepo worktree prune
Write-Output ''
& git -C $MainRepo worktree list

if ($RemoveBranches) {
    foreach ($w in $worktrees) {
        if ($ArchiveTagPrefix -ne '') {
            $tag = "{0}-{1}" -f $ArchiveTagPrefix, $w.Id
            & git -C $MainRepo tag -f $tag $w.Branch 2>$null | Out-Null
            Write-Output "[tag] $tag -> $($w.Branch)"
        }
        & git -C $MainRepo branch -d $w.Branch
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "分支未删除（多半尚未合并）：$($w.Branch)——确认后手工 git branch -D"
        }
    }
}
