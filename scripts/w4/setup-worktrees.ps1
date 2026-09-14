<#
.SYNOPSIS
    建立 W4 三批次并行的三个 git worktree（W4-00 并行前置批新增，幂等）。

.DESCRIPTION
    为 W4-A / W4-B / W4-C 各建一个**独立工作树 + 独立分支**，使三批源码互不可见、
    `git status` 快照天然可信（历史事故：多批共享工作树时 git status 快照不可信、
    暂存区被交叉 `git add`）。

    工作树位置（与主仓同级，避免嵌套）：
      C:\Mnzm\XianxiaSectNative-w4a   ← w4/a-disciple-building
      C:\Mnzm\XianxiaSectNative-w4b   ← w4/b-court-economy
      C:\Mnzm\XianxiaSectNative-w4c   ← w4/c-battle-world

    幂等：已存在的分支/工作树会跳过，不报错。

.NOTES
    🔴 清理工作树请用 `scripts/w4/remove-worktrees.ps1`——
       直接 `Remove-Item -Recurse` 会**穿透 node_modules 目录联接（junction）
       删掉主仓真实内容**（findings.md 记录的已发生事故）。
#>
[CmdletBinding()]
param(
    [string]$MainRepo = '',
    [string]$BaseDir = '',
    [string]$BaseRef = 'main'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($MainRepo)) {
    # 脚本位于 <repo>/scripts/w4/ ⇒ 上溯两级即主仓
    $MainRepo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}
if ([string]::IsNullOrWhiteSpace($BaseDir)) {
    $BaseDir = Split-Path -Parent $MainRepo
}

$batches = @(
    @{ Id = 'w4a'; Branch = 'w4/a-disciple-building' },
    @{ Id = 'w4b'; Branch = 'w4/b-court-economy' },
    @{ Id = 'w4c'; Branch = 'w4/c-battle-world' }
)

Write-Output "主仓：$MainRepo"
Write-Output "基线：$BaseRef"
Write-Output ''

foreach ($b in $batches) {
    $path = Join-Path $BaseDir ("{0}-{1}" -f (Split-Path -Leaf $MainRepo), $b.Id)
    $branch = $b.Branch

    if (Test-Path $path) {
        Write-Output "[skip] 目录已存在：$path"
    } else {
        Write-Output "[worktree] $path  <=  $branch"
        # 分支已存在则直接挂，不存在则以 BaseRef 新建
        $exists = (& git -C $MainRepo branch --list $branch) -ne ''
        if ($exists) {
            & git -C $MainRepo worktree add $path $branch
        } else {
            & git -C $MainRepo worktree add -b $branch $path $BaseRef
        }
        if ($LASTEXITCODE -ne 0) { throw "worktree add 失败：$path / $branch" }
    }

    # 中文路径显示（findings：git 默认对中文路径做引号转义）
    & git -C $path config core.quotepath false
    & git -C $path config core.autocrlf true

    # local.properties 含 SDK 路径/签名口令，未入库 ⇒ 从主仓复制（缺则提示）
    $src = Join-Path $MainRepo 'android\local.properties'
    $dst = Join-Path $path 'android\local.properties'
    if ((Test-Path $src) -and -not (Test-Path $dst)) {
        Copy-Item -LiteralPath $src -Destination $dst
        Write-Output "  [copy] android/local.properties"
    }
}

Write-Output ''
Write-Output '完成。下一步：'
Write-Output '  1) 各批进入自己的工作树：cd <worktree>\android'
Write-Output '  2) 重活前取构建令牌：pwsh -File scripts/w4/build-token.ps1 -Acquire -Batch w4a'
Write-Output '  3) JNI 对拍路径必须指向**本工作树**的 libgamecorejni.so'
& git -C $MainRepo worktree list
