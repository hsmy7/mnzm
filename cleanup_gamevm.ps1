$filePath = "c:\Mnzm\XianxiaSectNative\android\app\src\main\java\com\xianxia\sect\ui\game\GameViewModel.kt"
$lines = [System.IO.File]::ReadAllLines($filePath, [System.Text.Encoding]::UTF8)

$deletePatterns = @(
    'private val _loadingProgress',
    'val loadingProgress',
    'private val _pendingSlot',
    'private val _pendingAction',
    'val saveLoadState',
    'val isLoading',
    'val isSaving',
    'fun cancelSaveLoad',
    'fun resetSaveLoadState',
    'fun resetSaveLoadStateAsync',
    'fun setPendingSave',
    'fun setPendingLoad',
    'fun clearPendingAction',
    'private fun trimSaveData',
    'private val _isRestarting',
    'val isRestarting',
    'private val _timeScale',
    'val timeScale',
    'private val _isStartingAlchemy',
    'private val _isStartingForge',
    'private val _saveSlots',
    'val saveSlots',
    'private val _autoSaveInterval',
    'val autoSaveInterval',
    'private fun canPerformSaveOperation',
    'private fun performGarbageCollection',
    'private val _isTimeRunning',
    'val isTimeRunning',
    'private fun startGameLoop',
    'private fun enqueueAutoSave',
    'fun performAutoSave',
    'fun createSaveDataSync',
    'private fun stopGameLoop',
    'fun startNewGame',
    'fun isGameAlreadyLoaded',
    'fun loadGame\b',
    'fun saveGame',
    'fun togglePause',
    'fun pauseGame',
    'fun pauseAndSaveForBackground',
    'fun resumeGame',
    'fun setTimeSpeed',
    'fun setAutoSaveInterval',
    'fun setAutoSaveIntervalMonths',
    'fun resetAllDisciplesStatus',
    'fun showSaveManagementDialog',
    'fun dismissSaveDialog',
    'fun restartGame',
    'fun improveSectRelation',
    'fun formAlliance',
    'fun openSectTradeDialog',
    'fun closeSectTradeDialog',
    'fun openGiftDialog',
    'fun closeGiftDialog',
    'fun openScoutDialog',
    'fun closeScoutDialog',
    'fun startScoutMission',
    'fun getEligibleScoutDisciples',
    'fun giftSpiritStones',
    'fun giftItem\b',
    'fun openAllianceDialog',
    'fun closeAllianceDialog',
    'fun openEnvoyDiscipleSelectDialog',
    'fun closeEnvoyDiscipleSelectDialog',
    'fun openOuterTournamentDialog',
    'fun resetOuterTournamentClosedFlag',
    'fun closeOuterTournamentDialog',
    'fun promoteSelectedDisciplesToInner',
    'fun requestAlliance',
    'fun dissolveAlliance',
    'fun getEligibleEnvoyDisciples',
    'fun getAllianceCost',
    'fun isAlly\b',
    'fun getAllianceRemainingYears',
    'fun getPlayerAllies',
    'fun buyFromSectTrade',
    'fun startAlchemy',
    'fun collectAlchemyResult',
    'fun clearAlchemySlot',
    'fun startForge',
    'fun collectForgeResult',
    'fun clearForgeSlot',
    'fun startHerbGardenPlanting',
    'fun selectPlantSlot',
    'fun clearPlantSlot',
    'fun autoPlantAllSlots',
    'fun autoAlchemyAllSlots',
    'fun autoForgeAllSlots',
    'private val _battleTeamMoveMode',
    'val battleTeamMoveMode',
    'private val _battleTeamSlots',
    'val battleTeamSlots',
    'fun openBattleTeamDialog',
    'fun closeBattleTeamDialog',
    'fun getAvailableEldersForBattleTeam',
    'fun getAvailableDisciplesForBattleTeam\b',
    'fun assignDiscipleToBattleTeamSlot',
    'fun removeDiscipleFromBattleTeamSlot',
    'fun createBattleTeam',
    'fun disbandBattleTeam',
    'fun hasBattleTeam\b',
    'fun returnStationedBattleTeam',
    'fun hasBattleTeamAtSect',
    'fun startBattleTeamMoveMode',
    'fun cancelBattleTeamMoveMode',
    'fun selectBattleTeamTarget',
    'fun getMovableTargetSectIds',
    'fun getDisciplePosition',
    'fun hasDisciplePosition',
    'private fun getWorkStatusPositionIds',
    'fun isReserveDisciple',
    'fun isPositionWorkStatus',
    'fun getAvailableDisciplesForBattleTeamSlot',
    'fun addDiscipleToBattleTeamSlot',
    'fun openWorldMapDialog',
    'fun closeWorldMapDialog',
    'fun openSecretRealmDialog',
    'fun closeSecretRealmDialog',
    'fun openBattleLogDialog',
    'fun closeBattleLogDialog',
    'private val _showSectTradeDialog',
    'val showSectTradeDialog',
    'private val _selectedTradeSectId',
    'val selectedTradeSectId',
    'private val _sectTradeItems',
    'val sectTradeItems',
    'private val _showGiftDialog',
    'val showGiftDialog',
    'private val _selectedGiftSectId',
    'val selectedGiftSectId',
    'private val _showScoutDialog',
    'val showScoutDialog',
    'private val _selectedScoutSectId',
    'val selectedScoutSectId',
    'private val _showAllianceDialog',
    'val showAllianceDialog',
    'private val _selectedAllianceSectId',
    'val selectedAllianceSectId',
    'private val _showEnvoyDiscipleSelectDialog',
    'val showEnvoyDiscipleSelectDialog',
    'private val _showOuterTournamentDialog',
    'val showOuterTournamentDialog',
    'private var _isOuterTournamentManuallyClosed'
)

$linesToDelete = @{}
$i = 0
$totalLines = $lines.Count

while ($i -lt $totalLines) {
    $line = $lines[$i]
    $stripped = $line.Trim()
    
    $shouldDelete = $false
    foreach ($pattern in $deletePatterns) {
        if ($stripped -match $pattern) {
            $shouldDelete = $true
            break
        }
    }
    
    if ($shouldDelete) {
        if ($stripped.StartsWith('val ') -or $stripped.StartsWith('private val ') -or $stripped.StartsWith('private var ')) {
            $j = $i
            $parenDepth = 0
            $braceDepth = 0
            $bracketDepth = 0
            $foundAny = $false
            while ($j -lt $totalLines) {
                $currentLine = $lines[$j]
                $openP = ([regex]::Matches($currentLine, '\(')).Count
                $closeP = ([regex]::Matches($currentLine, '\)')).Count
                $openB = ([regex]::Matches($currentLine, '\{')).Count
                $closeB = ([regex]::Matches($currentLine, '\}')).Count
                $openBr = ([regex]::Matches($currentLine, '\[')).Count
                $closeBr = ([regex]::Matches($currentLine, '\]')).Count
                $parenDepth += $openP - $closeP
                $braceDepth += $openB - $closeB
                $bracketDepth += $openBr - $closeBr
                if ($openP -gt 0 -or $openB -gt 0 -or $openBr -gt 0) { $foundAny = $true }
                $linesToDelete[$j] = $true
                if ($foundAny -and $parenDepth -le 0 -and $braceDepth -le 0 -and $bracketDepth -le 0) {
                    # Check if next non-empty line is a continuation (starts with . or operator)
                    $nextJ = $j + 1
                    while ($nextJ -lt $totalLines -and $lines[$nextJ].Trim() -eq '') {
                        $nextJ++
                    }
                    if ($nextJ -lt $totalLines) {
                        $nextStripped = $lines[$nextJ].Trim()
                        if ($nextStripped.StartsWith('.') -or $nextStripped.StartsWith('?:') -or $nextStripped.StartsWith('?:')) {
                            $j = $nextJ - 1
                            $foundAny = $false
                            $j++
                            continue
                        }
                    }
                    break
                }
                $j++
            }
            $i = $j + 1
        } else {
            $depth = 0
            $foundOpen = $false
            $j = $i
            while ($j -lt $totalLines) {
                $current = $lines[$j]
                $openCount = ([regex]::Matches($current, '\{')).Count
                $closeCount = ([regex]::Matches($current, '\}')).Count
                $depth += $openCount - $closeCount
                if ($openCount -gt 0) {
                    $foundOpen = $true
                }
                $linesToDelete[$j] = $true
                if ($foundOpen -and $depth -le 0) {
                    break
                }
                $j++
            }
            $i = $j + 1
        }
    } else {
        $i++
    }
}

$newLines = @()
for ($k = 0; $k -lt $totalLines; $k++) {
    if (-not $linesToDelete.ContainsKey($k)) {
        $newLines += $lines[$k]
    }
}

$finalLines = @()
$blankCount = 0
foreach ($line in $newLines) {
    if ($line.Trim() -eq '') {
        $blankCount++
        if ($blankCount -le 2) {
            $finalLines += $line
        }
    } else {
        $blankCount = 0
        $finalLines += $line
    }
}

[System.IO.File]::WriteAllLines($filePath, $finalLines, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Deleted $($linesToDelete.Count) lines"
Write-Host "Original: $totalLines lines"
Write-Host "Result: $($finalLines.Count) lines"
