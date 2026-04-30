# 弟子每日血量/灵力恢复问题 - 分析与修复报告

## 一、问题描述

用户反馈：弟子没有每日自动恢复血量和灵力。

## 二、调用链分析

```
GameEngineCore.tickInternal()
  └─ stateStore.update { ... }
      └─ while (dayAccumulator >= 1.0)
          └─ SystemManager.onDayTick(state: MutableGameState)
              └─ CultivationService.onDayTick(state)
                  └─ processDailyEvents(day, month, year)
                      └─ processDailyRecovery()
```

调用链完整且正确。`CultivationService` 已注册为 `GameSystem`（`@SystemPriority(order = 200)`），`onDayTick` 会被 `SystemManager` 按序调用。

## 三、processDailyRecovery 原始逻辑

```kotlin
private fun processDailyRecovery() {
    currentDisciples = currentDisciples.map { disciple ->
        if (!disciple.isAlive) return@map disciple

        val maxHp = disciple.maxHp
        val maxMp = disciple.maxMp
        val currentHp = disciple.combat.currentHp
        val currentMp = disciple.combat.currentMp

        val hpRecovery = (maxHp * 0.01).toInt().coerceAtLeast(1)  // 1%
        val mpRecovery = (maxMp * 0.01).toInt().coerceAtLeast(1)  // 1%

        val newHp = if (currentHp < 0) currentHp else (currentHp + hpRecovery).coerceAtMost(maxHp)
        val newMp = if (currentMp < 0) currentMp else (currentMp + mpRecovery).coerceAtMost(maxMp)

        if (newHp == currentHp && newMp == currentMp) return@map disciple

        disciple.copyWith(currentHp = newHp, currentMp = newMp)
    }
}
```

数据写入路径：`currentDisciples` setter → 事务内 `reusableMutableState.disciples` → 事务结束写入 `_state` → UI 通过 `StateFlow` 观察更新。路径正确。

## 四、发现的问题

### 问题1（核心）：恢复量极低，用户几乎感知不到

每日恢复量为 maxHp 的 1%，最低 1 点。

| 弟子 maxHp | 每日恢复量 | 从 50% 恢复满需天数 | 换算游戏时间 |
|-----------|-----------|-------------------|------------|
| 100 | 1 点 | 50 天 | ~1.7 月 |
| 1,000 | 10 点 | 50 天 | ~1.7 月 |
| 10,000 | 100 点 | 50 天 | ~1.7 月 |
| 100,000 | 1,000 点 | 50 天 | ~1.7 月 |

从 50% 血量恢复到满血需要约 50 天（1.7 个月游戏时间），从 10% 恢复到满需要约 90 天（3 个月）。

### 问题2（次要）：注释与实现不一致

注释写的是 `except those in battle`，但代码中没有检查 `DiscipleStatus.IN_TEAM`，战斗中的弟子也会被恢复。这是设计意图，注释需修正。

### 问题3（次要）：processDailyEvents 无异常隔离

`processDailyEvents` 中 8 个方法顺序执行，没有 try-catch。如果前面方法（如 `processAIBattleTeamMovement`）抛出异常，`processDailyRecovery()` 会被跳过，导致当日恢复不执行。

### 问题4（UI Bug）：秘境队伍成员 HP 数据源错误

`SecretRealmDialogs.kt` 中：
```kotlin
val currentHp = disciple.statusData["currentHp"]?.toIntOrNull() ?: disciple.maxHp
```
`statusData["currentHp"]` 从未被写入，始终为 null，回退到 `disciple.maxHp`（即 `baseHp`，不含丹药/境界加成）。导致秘境队伍成员始终显示满血。

### 问题5（API Bug）：DiscipleAggregate.maxHp 不含加成

```kotlin
val maxHp: Int get() = baseHp  // 不含丹药/境界加成
```
而 `Disciple.maxHp` 返回 `getBaseStats().maxHp`（含加成）。`DiscipleAggregate` 中需要用 `maxHpFinal` 才能得到含加成的值，API 不一致。

## 五、修复方案

### 修复1：恢复量从 1% 提升至 5%

**文件**: `CultivationService.kt`

```kotlin
// 修改前
val hpRecovery = (maxHp * 0.01).toInt().coerceAtLeast(1)
val mpRecovery = (maxMp * 0.01).toInt().coerceAtLeast(1)

// 修改后
val hpRecovery = (maxHp * 0.05).toInt().coerceAtLeast(1)
val mpRecovery = (maxMp * 0.05).toInt().coerceAtLeast(1)
```

修复后恢复效果：从半血恢复满需 10 天；从 10% 恢复满需 19 天。

### 修复2：注释修正

**文件**: `CultivationService.kt`

```kotlin
// 修改前
// Daily recovery: disciples recover 1% HP and MP (except those in battle)

// 修改后
// Daily recovery: disciples recover 5% HP and MP (including those in battle)
```

### 修复3：processDailyEvents 异常隔离

**文件**: `CultivationService.kt`

每个子调用独立 try-catch，单个事件异常不再阻断后续事件：

```kotlin
private suspend fun processDailyEvents(day: Int, month: Int, year: Int) {
    try { updateExplorationTeamsMovement(day, month, year) } catch (e: Exception) { Log.e(TAG, "Error in updateExplorationTeamsMovement", e) }
    try { updateCaveExplorationTeamsMovement(day, month, year) } catch (e: Exception) { Log.e(TAG, "Error in updateCaveExplorationTeamsMovement", e) }
    try { processAIBattleTeamMovement() } catch (e: Exception) { Log.e(TAG, "Error in processAIBattleTeamMovement", e) }
    try { checkGameOverCondition() } catch (e: Exception) { Log.e(TAG, "Error in checkGameOverCondition", e) }
    try { processPlayerBattleTeamMovement() } catch (e: Exception) { Log.e(TAG, "Error in processPlayerBattleTeamMovement", e) }
    try { checkExplorationArrivals() } catch (e: Exception) { Log.e(TAG, "Error in checkExplorationArrivals", e) }
    try { processChildBirth(year) } catch (e: Exception) { Log.e(TAG, "Error in processChildBirth", e) }
    try { processDailyRecovery() } catch (e: Exception) { Log.e(TAG, "Error in processDailyRecovery", e) }
    try { processPillDurationDecay() } catch (e: Exception) { Log.e(TAG, "Error in processPillDurationDecay", e) }
    try { processAutoUseItems(year, month, day) } catch (e: Exception) { Log.e(TAG, "Error in processAutoUseItems", e) }
    try { discipleService.syncAllDiscipleStatuses() } catch (e: Exception) { Log.e(TAG, "Error in syncAllDiscipleStatuses", e) }
}
```

### 修复4：秘境队伍 HP 数据源修正

**文件**: `SecretRealmDialogs.kt`

```kotlin
// 修改前
val currentHp = if (isDead) 0 else disciple.statusData["currentHp"]?.toIntOrNull() ?: disciple.maxHp
val hpPercent = disciple.maxHp.takeIf { it > 0 }?.let { ... }

// 修改后
val currentHp = if (isDead) 0 else (if (disciple.currentHp < 0) disciple.maxHpFinal else disciple.currentHp)
val hpPercent = disciple.maxHpFinal.takeIf { it > 0 }?.let { ... }
```

### 修复5：DiscipleAggregate.maxHp 包含加成

**文件**: `DiscipleAggregate.kt`

```kotlin
// 修改前
val maxHp: Int get() = baseHp
val maxMp: Int get() = baseMp

// 修改后
val maxHp: Int get() = maxHpFinal
val maxMp: Int get() = maxMpFinal
```

## 六、修改文件清单

| 文件 | 修改内容 |
|------|---------|
| `CultivationService.kt` | 恢复量 1%→5%；注释更新；processDailyEvents 异常隔离 |
| `SecretRealmDialogs.kt` | HP 数据源从 `statusData["currentHp"]` 改为 `disciple.currentHp` + `maxHpFinal` |
| `DiscipleAggregate.kt` | `maxHp`/`maxMp` 从 `baseHp`/`baseMp` 改为 `maxHpFinal`/`maxMpFinal` |
| `ChangelogData.kt` | 新增 v2.6.07 条目 |
| `CHANGELOG.md` | 新增 v2.6.07 条目 |

## 七、构建验证

`compileDebugKotlin` — BUILD SUCCESSFUL
