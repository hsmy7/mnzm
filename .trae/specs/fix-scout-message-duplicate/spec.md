# 修复宗门消息重复显示探查队伍信息 Spec

## Why
探查队伍到达目标宗门后，宗门消息会重复显示探查战斗结果的消息。这是因为在 `processScoutTeamMovement()` 函数中，当探查队伍到达目标宗门时，战斗触发逻辑存在缺陷，导致每次调用 `advanceDay()` 时都会重复触发战斗并添加消息。

## What Changes
- 修复 `triggerScoutBattleOnArrival()` 函数，在触发战斗前检查队伍状态是否仍然为 `SCOUTING`
- 确保战斗完成后队伍状态正确更新，避免重复触发

## Impact
- Affected code: `GameEngine.kt` 中的 `processScoutTeamMovement()` 和 `triggerScoutBattleOnArrival()` 函数

## ADDED Requirements

### Requirement: 探查队伍战斗触发唯一性
系统应确保探查队伍到达目标宗门后，战斗只触发一次，不会重复触发。

#### Scenario: 探查队伍到达目标宗门
- **WHEN** 探查队伍的 `moveProgress >= 1f` 且状态为 `SCOUTING`
- **THEN** 系统触发一次战斗并添加一条消息
- **AND** 战斗完成后队伍状态变为 `COMPLETED`
- **AND** 后续调用 `advanceDay()` 不会再次触发战斗

## Root Cause Analysis

问题出在 `processScoutTeamMovement()` 函数中：

```kotlin
private fun processScoutTeamMovement() {
    val updatedTeams = _teams.value.map { team ->
        if (team.status == ExplorationStatus.SCOUTING && team.moveProgress < 1f) {
            // 更新移动进度
        } else {
            team
        }
    }
    _teams.value = updatedTeams
    
    // 问题：updatedTeams 包含了 moveProgress >= 1f 的队伍
    // 这些队伍会触发战斗，但战斗完成后状态更新到 _teams.value
    // 而 updatedTeams 仍然是旧的状态
    updatedTeams.filter { it.status == ExplorationStatus.SCOUTING && it.moveProgress >= 1f }.forEach { team ->
        triggerScoutBattleOnArrival(team)
    }
}
```

当 `advanceDay()` 被多次调用时（例如 `daysPerSecond > 1`），每次都会检查 `updatedTeams` 中的队伍，如果队伍的 `moveProgress >= 1f` 且状态为 `SCOUTING`，就会触发战斗。虽然第一次战斗完成后状态会更新为 `COMPLETED`，但 `updatedTeams` 是在战斗触发前计算的，所以后续调用仍然会使用旧的队伍状态。

## Solution

在 `triggerScoutBattleOnArrival()` 函数中添加状态检查，确保只对状态仍为 `SCOUTING` 的队伍触发战斗：

```kotlin
private fun triggerScoutBattleOnArrival(team: ExplorationTeam) {
    // 再次检查队伍状态，避免重复触发
    val currentTeam = _teams.value.find { it.id == team.id }
    if (currentTeam?.status != ExplorationStatus.SCOUTING) {
        return
    }
    
    val data = _gameData.value
    val targetSect = data.worldMapSects.find { it.id == team.scoutTargetSectId }
    
    if (targetSect != null) {
        triggerScoutBattle(team, targetSect, data.gameYear, data.gameMonth)
    }
}
```
