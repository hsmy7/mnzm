<#
.SYNOPSIS
    把仓库里的隐私政策页（docs/index.html）发布到 GitHub Pages，使线上与实际集成一致。

.DESCRIPTION
    ## 为什么需要这个脚本（2026-09-15 核查发现的真实不一致）

    GitHub Pages（https://hsmy7.github.io/mnzm/）发布的是 **master** 分支，而 master 上的
    docs/index.html 停留在 **2026-06-04 版**：

      | 面 | 版本 | 声明的广告/统计 SDK |
      |---|---|---|
      | 线上（Pages ← master） | 2026-06-04 | 仅 TapTap + MMKV + Dirichlet Ad SDK |
      | 仓库（main 与本地工作区） | **2026-08-13** | TapTap(含 **TapDB**) + MMKV + **TapADN 聚合**（穿山甲/优量汇/爱奇艺/百青藤）+ GAID + 个性化广告开关 |
      | 应用内（`PrivacyConsentScreen.kt`） | **2026-08-13** | 同上（与仓库网页版一致） |

    而 `android/app/build.gradle` 中上述 SDK **均已集成** ⇒ **线上政策未声明实际在用的 SDK**，
    属隐私合规缺口（CLAUDE.md 设计方案规则第 5 条：隐私政策必须双入口同步）。

    ## 本脚本做什么

    `-Mode SourceBranch`（默认）：在 Pages **源分支**（默认 `master`）上把
      `docs/index.html` 更新为仓库当前版本 —— 文件级最小改动，不动 Pages 配置。

    `-Mode PagesTarget`：把 Pages 的**发布源分支**切到 `main`（`main` 上的
      `docs/index.html` 与仓库当前版本逐字节相同，故**零内容变更**即生效）。
      好处是一次性消除"两份政策页"；代价是此后 `main` 的每次推送都会触发一次 Pages 构建。

    两种模式都先做**三项前置校验**，任一不过即中止（防误发旧版/防打错仓库）：
      ① 本地 `docs/index.html` 的 git blob == `-ExpectBlob`；
      ② 通过 GitHub API 读取远端 `main` 的同文件 blob，须与本地一致（证明内容同源）；
      ③ 目标仓库/分支存在且可写。

    ## 凭据（脚本不接收明文口令）

    需要一个对 `hsmy7/mnzm` 有 **Contents: Read and write**（`PagesTarget` 另需
    **Pages: Read and write**）权限的 token，放在环境变量 `GITHUB_TOKEN` 或 `GH_TOKEN`；
    也可先 `gh auth login`（脚本会在未设置环境变量时尝试 `gh auth token`）。

    ## 用法

    ```powershell
    # 1) 演练（默认 -DryRun，只打印将要发送的请求，不做任何写操作）
    pwsh -File scripts/publish-privacy-policy.ps1

    # 2) 真正执行：把 8 月版政策更新到 master
    $env:GITHUB_TOKEN = '<你的 token>'
    pwsh -File scripts/publish-privacy-policy.ps1 -DryRun:$false

    # 3) 备选：把 Pages 源切到 main（零内容变更）
    pwsh -File scripts/publish-privacy-policy.ps1 -Mode PagesTarget -DryRun:$false
    ```

    ## 兜底：完全手工（无需 token，只要 git 推送权限）

    ```bash
    git fetch origin master
    git checkout -b policy-sync origin/master
    git checkout main -- docs/index.html        # 取 8 月版
    git commit -m "docs(privacy): 同步 8 月版隐私政策（补声明 TapADN 聚合广告 SDK 与 TapDB）"
    git push origin HEAD:master
    ```
    随后在仓库 Settings → Pages 确认发布源仍为 `master` / `docs`，等 1~2 分钟生效后核对
    https://hsmy7.github.io/mnzm/ 顶部日期为「2026年8月13日」。

.PARAMETER Mode
    SourceBranch（默认）| PagesTarget

.PARAMETER Repo
    目标仓库，默认 hsmy7/mnzm

.PARAMETER SourceBranch
    Pages 源分支，默认 master

.PARAMETER ExpectBlob
    期望的本地 git blob（防误发旧版），默认 566e84fa…（8 月版）

.PARAMETER DryRun
    默认 $true：只打印请求，不写。执行时显式传 -DryRun:$false
#>
[CmdletBinding()]
param(
    [ValidateSet('SourceBranch', 'PagesTarget')]
    [string]$Mode = 'SourceBranch',
    [string]$Repo = 'hsmy7/mnzm',
    [string]$SourceBranch = 'master',
    [string]$ExpectBlob = '566e84fac1cc70d95ea22dfb46bcc477797cc3a6',
    [bool]$DryRun = $true
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$File = 'docs/index.html'
$ApiBase = "https://api.github.com/repos/$Repo"
$Headers = @{ 'Accept' = 'application/vnd.github+json'; 'User-Agent' = 'xianxia-sect-privacy-sync' }

function Get-Token {
    foreach ($name in @('GITHUB_TOKEN', 'GH_TOKEN')) {
        $v = [Environment]::GetEnvironmentVariable($name)
        if (-not [string]::IsNullOrWhiteSpace($v)) { return @{ Name = $name; Value = $v } }
    }
    if (Get-Command gh -ErrorAction SilentlyContinue) {
        $v = (gh auth token 2>$null)
        if (-not [string]::IsNullOrWhiteSpace($v)) { return @{ Name = 'gh auth token'; Value = $v.Trim() } }
    }
    return $null
}

function Read-Text {
    param([string]$Path)
    # 以 UTF-8(无 BOM) 读写：GitHub Contents API 的 content 字段要求 base64 的 UTF-8 字节，
    # 若把 BOM 一并编码进去，页面头部会多出不可见字符。
    $bytes = [System.IO.File]::ReadAllBytes((Resolve-Path $Path))
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        $bytes = $bytes[3..($bytes.Length - 1)]
    }
    return [System.Text.Encoding]::UTF8.GetString($bytes)
}

function Invoke-Api {
    param([string]$Method, [string]$Uri, $Body, [hashtable]$Auth)
    $h = $Headers.Clone()
    if ($Auth) { $h['Authorization'] = "Bearer $($Auth.Value)" }
    $args = @{ Method = $Method; Uri = $Uri; Headers = $h; TimeoutSec = 60 }
    if ($Body) { $args['Body'] = ($Body | ConvertTo-Json -Depth 8 -Compress); $args['ContentType'] = 'application/json' }
    try {
        return Invoke-RestMethod @args
    } catch {
        # 把 GitHub 的原始报错（限流 / 网络 / 权限）原样带出来，便于定位而非笼统报"失败"
        $detail = $_.Exception.Message
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $detail = $_.ErrorDetails.Message }
        throw "GitHub API 调用失败（$Method $Uri）：$detail"
    }
}

# ── 前置校验 ────────────────────────────────────────────────────────────
Write-Host '── 前置校验 ──'

$localBlob = (& git rev-parse "HEAD:$File").Trim()
Write-Host "  ① 本地 $File blob = $localBlob"
if ($localBlob -ne $ExpectBlob) {
    throw "本地文件不是期望的版本（expect $ExpectBlob）——先确认工作区是 8 月版再发布"
}

$auth = Get-Token
if (-not $auth) {
    Write-Warning @'
  ⚠️ 未找到可用凭据（环境变量 GITHUB_TOKEN / GH_TOKEN，或已登录的 gh CLI）。
     只读校验与写操作都需要它（未认证请求配额极低，且无法写）。请先提供：
       $env:GITHUB_TOKEN = '<token>'     # 需 Contents: Read and write
     或   gh auth login
'@
} else {
    Write-Host "  ③ 凭据来源：$($auth.Name)"
}

$text = Read-Text -Path $File
$b64 = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($text))
Write-Host "  本地内容：$($text.Length) 字符 / base64 $($b64.Length) 字节"

# ② 与远端 main 同源校验（需要网络 + API 配额；失败时降级为告警，不掩盖原因）
$remoteChecked = $false
try {
    $remoteMain = Invoke-Api -Method Get -Uri "$ApiBase/contents/$File`?ref=main" -Auth $auth
    Write-Host "  ② 远端 main  $File blob = $($remoteMain.sha)"
    if ($remoteMain.sha -ne $localBlob) {
        throw "本地与远端 main 的 $File 不一致（$localBlob vs $($remoteMain.sha)）——内容未同源，先核对"
    }
    $remoteChecked = $true
} catch {
    Write-Warning "  ⚠️ ② 同源校验未能完成：$($_.Exception.Message)"
    Write-Warning '     内容仍以 ① 本地 blob 为准（离线可验证）；网络/配额恢复后重跑即自动补上该校验。'
    if (-not $DryRun) {
        throw ' 写操作需要可用的 GitHub API（读 sha + 提交）；请恢复网络/配额或提供 token 后重试'
    }
}

# ── 动作 ────────────────────────────────────────────────────────────────
if ($Mode -eq 'SourceBranch') {
    Write-Host "`n── 动作：把 $File 更新到 $Repo@$SourceBranch（文件级最小改动）──"
    $curSha = '<执行时获取>'
    $curSize = '?'
    try {
        $cur = Invoke-Api -Method Get -Uri "$ApiBase/contents/$File`?ref=$SourceBranch" -Auth $auth
        $curSha = $cur.sha
        $curSize = $cur.size
        Write-Host "  当前 $SourceBranch blob = $curSha（$curSize 字节）"
        if ($curSha -eq $localBlob) {
            Write-Host '  已是目标版本，无需变更。'
            return
        }
    } catch {
        Write-Warning "  ⚠️ 无法读取 $SourceBranch 当前版本：$($_.Exception.Message)"
        if (-not $DryRun) { throw }
    }
    $body = @{
        message = 'docs(privacy): 同步 8 月版隐私政策（补声明 TapADN 聚合广告 SDK：穿山甲/优量汇/爱奇艺/百青藤，及 TapDB、GAID、个性化广告开关）'
        content = $b64
        sha     = $curSha
        branch  = $SourceBranch
    }
    if ($DryRun) {
        Write-Host "  [DryRun] 将 PUT $ApiBase/contents/$File （branch=$SourceBranch, 当前 sha=$curSha, 变更 $curSize → $($text.Length) 字符）"
        Write-Host '  去掉 -DryRun:$false 即真正执行。'
        return
    }
    $r = Invoke-Api -Method Put -Uri "$ApiBase/contents/$File" -Body $body -Auth $auth
    Write-Host "  ✅ 已提交：$($r.commit.sha) → $($r.commit.html_url)"
    Write-Host '  等 1~2 分钟后核对 https://hsmy7.github.io/mnzm/ 顶部日期应为「2026年8月13日」'
}
else {
    Write-Host "`n── 动作：把 Pages 发布源切到 $SourceBranch（零内容变更）──"
    $body = @{ source = @{ branch = $SourceBranch; path = '/docs' } }
    if ($DryRun) {
        Write-Host "  [DryRun] 将 PUT $ApiBase/pages  body=$($body | ConvertTo-Json -Compress)"
        Write-Host '  注：本模式需要 token 额外具备 Pages: Read and write 权限。'
        return
    }
    $r = Invoke-Api -Method Put -Uri "$ApiBase/pages" -Body $body -Auth $auth
    Write-Host "  ✅ Pages 源已切到 $($r.source.branch)$($r.source.path)（状态：$($r.status)）"
}
