<#
.SYNOPSIS
  G 批看护「本轮 fire 文本」装配器（薄脚本，不含业务逻辑）。

.DESCRIPTION
  把两样东西拼成一份自包含的 fire 文本，写到 docs/gacha-watch/_fire-latest.md：
    1) WATCHDOG-PROMPT.md 全文（看护的唯一提示词真源）
    2) dispatch-ledger.md 的《当前状态》最新 30 行（让看护不必重新读整份台账）
  并打印「可直接执行」的 ZCode 无头命令（默认模型、新会话、不抢焦点）。

  本脚本**只装配、不启动**任何实施会话，也不安装/修改定时任务：
  调度由用户按 WATCHDOG-PROMPT.md §3 的方案 A（任务计划程序）或方案 B（ZCode 自动化）自行启用。

.EXAMPLE
  pwsh -NoProfile -File docs\gacha-watch\fire.ps1
  pwsh -NoProfile -File docs\gacha-watch\fire.ps1 -RunWatchdog       # 装配后立即以看护身份执行
  pwsh -NoProfile -File docs\gacha-watch\fire.ps1 -DispatchBatch G10 # 装配后立即派发 G10 实施会话
#>
[CmdletBinding()]
param(
    [string]$Repo = 'C:\Mnzm\XianxiaSectNative',
    [switch]$RunWatchdog,
    [ValidateSet('', 'G10', 'G12', 'G13', 'G14')]
    [string]$DispatchBatch = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$watchDir  = Join-Path $Repo 'docs\gacha-watch'
$promptF   = Join-Path $watchDir 'WATCHDOG-PROMPT.md'
$ledgerF   = Join-Path $watchDir 'dispatch-ledger.md'
$outF      = Join-Path $watchDir '_fire-latest.md'
$cli       = Join-Path $env:LOCALAPPDATA 'Programs\ZCode\resources\glm\zcode.cjs'

foreach ($f in @($promptF, $ledgerF)) {
    if (-not (Test-Path $f)) { throw "缺少文件：$f" }
}

$prompt = Get-Content $promptF -Raw -Encoding UTF8
$ledger = Get-Content $ledgerF -Encoding UTF8
$stateStart = ($ledger | Select-String -Pattern '^##\s*当前状态' | Select-Object -First 1).LineNumber
if (-not $stateStart) { throw '台账里找不到《当前状态》小节' }
$stateLines = $ledger[($stateStart)..([Math]::Min($stateStart + 29, $ledger.Count - 1))]

$stamp = Get-Date -Format 'yyyy-MM-dd HH:mm'
$fire = @()
$fire += "# 本轮 fire（$stamp）"
$fire += ''
$fire += '> 装配自 `WATCHDOG-PROMPT.md` + `dispatch-ledger.md`。**追加指令若出现在本轮 fire 末尾，优先级最高。**'
$fire += ''
$fire += '## 台账·当前状态（最新在上，截取 30 行）'
$fire += ''
$fire += $stateLines
$fire += ''
$fire += '---'
$fire += ''
$fire += '## 看护提示词全文'
$fire += ''
$fire += $prompt
$fire | Set-Content -Path $outF -Encoding UTF8

Write-Host "fire 文本已装配：$outF  ($((Get-Item $outF).Length) 字节)" -ForegroundColor Green
Write-Host ''
Write-Host '—— 看护轮（只观测/核验/派发，不做实施）——' -ForegroundColor Cyan
Write-Host "node `"$cli`" --prompt (Get-Content '$outF' -Raw) --cwd '$Repo' --mode yolo --json --no-color --surface terminal --locale zh-CN"

if ($DispatchBatch) {
    $tb = Join-Path $Repo "docs\design\gacha-batches\TASKBOOK-$DispatchBatch.md"
    if (-not (Test-Path $tb)) { throw "缺少任务书：$tb" }
    $dispatchF = Join-Path $watchDir "_dispatch-$DispatchBatch.md"
    $preamble = @"
【$DispatchBatch · 派发文本（由 fire.ps1 装配）】

■ 会话纪律：本会话只实施本批；完成后写 report-$DispatchBatch.md 并停止，不开始下一批；不得自登记 accepted（接受与否留用户/看护）；如实登记失败、未完成与 pending 项，禁止虚报；遇产品歧义按任务书口径执行并登记，不停下询问。
■ 施工面（唯一）：主树 $Repo（分支 feat/gacha-m0-m1）。开工前先 git status 确认树净；有他批在途改动就停手报告。提交一律明确文件名 git add <file>，禁止 git add -A。
■ 必读（按序）：① 本批任务书全文（下方）；② 根 AGENTS.md + 相关模块级 AGENTS.md；③ docs/design/gacha-batches/EXECUTION-PROTOCOL.md；④ 上一批 report-Gxx.md 的交付事实/登记节。
■ 默认模型：使用 ZCode 默认模型（不要切换 /model）。

■ 本批任务书全文：
"@
    ($preamble + (Get-Content $tb -Raw -Encoding UTF8)) | Set-Content -Path $dispatchF -Encoding UTF8
    Write-Host ''
    Write-Host "—— 实施派发（$DispatchBatch，新会话）——" -ForegroundColor Yellow
    Write-Host "node `"$cli`" --prompt (Get-Content '$dispatchF' -Raw) --cwd '$Repo' --mode yolo --json --no-color --surface terminal --locale zh-CN"
}

if ($RunWatchdog) {
    Write-Host ''
    Write-Host '以看护身份执行本轮 fire …' -ForegroundColor Cyan
    node $cli --prompt (Get-Content $outF -Raw) --cwd $Repo --mode yolo --json --no-color --surface terminal --locale zh-CN
}
