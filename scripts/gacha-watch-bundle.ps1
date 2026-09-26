<#
.SYNOPSIS
  G 批「剩余工作总包」生成器 —— 把看护提示词 + 台账 + 四份批任务书打成一份自包含文件。

.DESCRIPTION
  生成 docs/gacha-watch/BATCH-PLAN-ALL.md（默认路径），用途：
    ① 人读：一个文件看完全部剩余工作的实施要求；
    ② 交接：换机器 / 交给人 / 一次性粘给别的工具；
    ③ 审计：文件头带每份源文件的字节数与 sha256，可核对是否与真源一致。

  🔴 真源仍是 WATCHDOG-PROMPT.md + dispatch-ledger.md + 四份 TASKBOOK-Gxx.md；
     本文件是**派生件**，禁止手改（手改会在下次生成时丢失）。

.PARAMETER Repo
  仓库根，默认取脚本所在目录的上级（scripts/ 的父目录）。

.PARAMETER Out
  输出路径，默认 <Repo>\docs\gacha-watch\BATCH-PLAN-ALL.md。

.PARAMETER Check
  只校验：若现有输出与"重新生成的结果"（忽略生成时间行）不同 ⇒ 退出码 1，并打印差异摘要。
  用途：看护每轮开工前自检总包是否过期；CI/文档门禁可挂。

.PARAMETER Quiet
  不打印控制台摘要（-Check 模式除外）。

.EXAMPLE
  pwsh -NoProfile -File scripts\gacha-watch-bundle.ps1
  pwsh -NoProfile -File scripts\gacha-watch-bundle.ps1 -Check
#>
[CmdletBinding()]
param(
    [string]$Repo = '',
    [string]$Out = '',
    [switch]$Check,
    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not $Repo) { $Repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path }
if (-not (Test-Path $Repo)) { throw "仓库根不存在：$Repo" }

$watchDir = Join-Path $Repo 'docs\gacha-watch'
$batchDir = Join-Path $Repo 'docs\design\gacha-batches'
if (-not $Out) { $Out = Join-Path $watchDir 'BATCH-PLAN-ALL.md' }

# ── 打包清单（顺序 = 总包内出现顺序；派发顺序 G10 → G12 → G13 → G14）─────────
$plan = @(
    @{ Path = (Join-Path $watchDir 'WATCHDOG-PROMPT.md');                    Title = '看护提示词（调度 / 状态机 / 核验口径 / 派发模板）'; Tag = '§3' }
    @{ Path = (Join-Path $watchDir 'dispatch-ledger.md');                    Title = '看护台账（状态真源：批次总表 / 锁 / pending-device / 监控行）'; Tag = '§4' }
    @{ Path = (Join-Path $batchDir 'TASKBOOK-G10.md');                       Title = 'TASKBOOK-G10 · RNG 基线重录 + 全量回归收口 + 死代码清零 + 文档收口（M1 末批）'; Tag = '§5.1' }
    @{ Path = (Join-Path $batchDir 'TASKBOOK-G12.md');                       Title = 'TASKBOOK-G12 · 体验完成（M2-1）'; Tag = '§5.2' }
    @{ Path = (Join-Path $batchDir 'TASKBOOK-G13.md');                       Title = 'TASKBOOK-G13 · 数值落地（M2-2）'; Tag = '§5.3' }
    @{ Path = (Join-Path $batchDir 'TASKBOOK-G14.md');                       Title = 'TASKBOOK-G14 · 文档与发布收口（M2-3）'; Tag = '§5.4' }
)

foreach ($p in $plan) {
    if (-not (Test-Path $p.Path)) { throw "缺少打包源文件：$($p.Path)" }
}

function Get-Rel([string]$abs) { return $abs.Substring($Repo.Length).TrimStart('\') -replace '\\', '/' }

# ── 组装 ──────────────────────────────────────────────────────────────────
$lines = New-Object System.Collections.Generic.List[string]
$now = Get-Date -Format 'yyyy-MM-dd HH:mm'

$lines.Add('# G 批剩余工作总包（BATCH-PLAN-ALL）')
$lines.Add('')
$lines.Add("> 生成时间：$now ｜ 生成器：``scripts/gacha-watch-bundle.ps1`` ｜ 仓库根：``$Repo``")
$lines.Add('> 🔴 **本文件是派生件，禁止手改** —— 真源 = ``docs/gacha-watch/WATCHDOG-PROMPT.md`` +')
$lines.Add('> ``docs/gacha-watch/dispatch-ledger.md`` + 四份 ``docs/design/gacha-batches/TASKBOOK-G{10,12,13,14}.md``；')
$lines.Add('> 重新生成：``pwsh -NoProfile -File scripts/gacha-watch-bundle.ps1``；校验是否过期：``… -Check``（退出码 1 = 已过期）。')
$lines.Add('')
$lines.Add('---')
$lines.Add('')
$lines.Add('## §1 怎么用这份总包')
$lines.Add('')
$lines.Add('| 用途 | 做法 |')
$lines.Add('|---|---|')
$lines.Add('| **人读** | 按 §3 → §5.1…§5.4 顺序读：先看护规程，再逐批任务书 |')
$lines.Add('| **交接 / 换机器** | 整份贴给接手的 agent 或人；它自带派发命令、核验口径与全部任务书 |')
$lines.Add('| **审计** | 核对 §2 的「源文件指纹表」（字节数 + sha256）与真源是否一致；不一致即总包过期 |')
$lines.Add('')
$lines.Add('## §2 源文件指纹表（生成时快照）')
$lines.Add('')
$lines.Add('| # | 文件（仓库根相对） | 字节 | sha256（前 16） | 总包章节 |')
$lines.Add('|---|---|---|---|---|')
$i = 0
$fingerprints = @()
foreach ($p in $plan) {
    $i++
    $fi = Get-Item $p.Path
    $h = (Get-FileHash $p.Path -Algorithm SHA256).Hash
    $fingerprints += [pscustomobject]@{ Rel = (Get-Rel $p.Path); Bytes = $fi.Length; Sha = $h }
    $lines.Add("| $i | ``$(Get-Rel $p.Path)`` | $($fi.Length) | ``$($h.Substring(0,16).ToLower())`` | $($p.Tag) |")
}
$lines.Add('')
$lines.Add('## §2.1 派发链（每批一个**新会话**，ZCode **默认模型**，无头不抢焦点）')
$lines.Add('')
$lines.Add('```powershell')
$lines.Add('$repo = ''<仓库根>''')
$lines.Add('$cli  = "$env:LOCALAPPDATA\Programs\ZCode\resources\glm\zcode.cjs"')
$lines.Add('')
$lines.Add('# ① 看护轮（只观测/核验/派发，不做实施）')
$lines.Add('pwsh -NoProfile -File docs\gacha-watch\fire.ps1            # 生成 _fire-latest.md')
$lines.Add('')
$lines.Add('# ② 派发某一批（生成 _dispatch-<批>.md = 纪律前言 + 该批任务书全文逐字）')
$lines.Add('pwsh -NoProfile -File docs\gacha-watch\fire.ps1 -DispatchBatch G10')
$lines.Add('')
$lines.Add('# ③ 启动**新会话**实施（不传 --resume/--continue；不传模型参数 ⇒ 走默认 bigmodel/glm-5.1）')
$lines.Add('node $cli --prompt (Get-Content "$repo\docs\gacha-watch\_dispatch-G10.md" -Raw) `')
$lines.Add('  --cwd $repo --mode yolo --json --no-color --surface terminal --locale zh-CN')
$lines.Add('```')
$lines.Add('')
$lines.Add('🔴 派发顺序 **G10 → G12 → G13 → G14**；上一批未通过交付核验（收官笔 + 报告含门禁原文数字 + 树净）前**不派下一批**；')
$lines.Add('🔴 看护**禁止任何 GUI/桌面操作**（上一套自动化因抢用户焦点被中止）；**单飞**（同时只允许一个实施会话）。')
$lines.Add('')
$lines.Add('---')
$lines.Add('')

foreach ($p in $plan) {
    $lines.Add("## $($p.Tag) $($p.Title)")
    $lines.Add('')
    $lines.Add("> 源：``$(Get-Rel $p.Path)``（sha256 前 16 = ``$((Get-FileHash $p.Path -Algorithm SHA256).Hash.Substring(0,16).ToLower())``）")
    $lines.Add('')
    $lines.Add((Get-Content $p.Path -Raw -Encoding UTF8).TrimEnd())
    $lines.Add('')
    $lines.Add('---')
    $lines.Add('')
}
# 去掉末尾多余分隔
while ($lines.Count -gt 0 -and ($lines[$lines.Count-1] -eq '' -or $lines[$lines.Count-1] -eq '---')) { $lines.RemoveAt($lines.Count-1) }
$lines.Add('')
$lines.Add("<!-- 总包结束：共 $($plan.Count) 份源文件；重新生成见文件头。 -->")

$new = ($lines -join "`n") + "`n"

# ── 输出 / 校验 ────────────────────────────────────────────────────────────
function Normalize([string]$text) {
    # 归一化：行尾统一 LF + 忽略"生成时间"行 + 去掉尾部空行（便于 -Check 稳定比较）
    $t = ($text -replace "`r`n", "`n") -replace "`r", "`n"
    $ls = @($t -split "`n" | Where-Object { $_ -notmatch '^> 生成时间：' })
    $n = $ls.Count
    while ($n -gt 0 -and $ls[$n - 1] -eq '') { $n-- }
    if ($n -eq 0) { return '' }
    return (($ls[0..($n - 1)]) -join "`n")
}

if ($Check) {
    if (-not (Test-Path $Out)) { Write-Host "总包不存在：$Out" -ForegroundColor Red; exit 1 }
    $old = [System.IO.File]::ReadAllText($Out)
    if ((Normalize $old) -ceq (Normalize $new)) {
        Write-Host "✅ 总包与真源一致：$Out" -ForegroundColor Green
        exit 0
    }
    Write-Host "❌ 总包已过期（与真源不一致）：$Out" -ForegroundColor Red
    $oldL = @((Normalize $old) -split "`n"); $newL = @((Normalize $new) -split "`n")
    Write-Host ("   现行 {0} 行 ／ 应为 {1} 行 —— 重新生成：pwsh -NoProfile -File scripts/gacha-watch-bundle.ps1" -f $oldL.Count, $newL.Count)
    exit 1
}

# 固定编码（UTF-8 无 BOM）与固定行尾（LF），保证生成结果逐字节可复现
[System.IO.File]::WriteAllText($Out, $new, (New-Object System.Text.UTF8Encoding($false)))
if (-not $Quiet) {
    Write-Host "总包已生成：$Out" -ForegroundColor Green
    Write-Host ("  源文件 {0} 份；总包 {1} 字节 / {2} 行" -f $plan.Count, (Get-Item $Out).Length, ((Get-Content $Out) | Measure-Object -Line).Lines)
    Write-Host '  指纹表：'
    $fingerprints | ForEach-Object { Write-Host ("    {0,-56} {1,7} 字节  {2}" -f $_.Rel, $_.Bytes, $_.Sha.Substring(0,16).ToLower()) }
}
