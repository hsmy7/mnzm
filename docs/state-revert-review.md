# 状态回退修复 — 代码审查报告

> 日期：2026-06-03
> 审查范围：4 个文件，+187/-19 行
> 编译状态：✅ `compileReleaseKotlin` 通过

---

## 审查结论

整体**可以合并**，5 个合并函数逻辑正确，7 个已知问题中的 4 个已被本次改动完全消除。但发现 2 个需要修复的问题（1 HIGH, 1 MEDIUM），均在 `CultivationService.kt` 的 `recoverHpMpForAllDisciples` 中。

---

## 逐文件审查

### 文件 1/4：`GameStateStore.kt` — ✅ 通过（有 1 个 LOW 建议）

#### mergeDiscipleAfterSettlement 重写 ✅

| 字段 | 策略 | 审查结论 |
|------|------|---------|
| cultivation | delta `main + (shadow - origin)` | ✅ 正确，PN-Counter 模式 |
| lifespan | delta `main + (shadow - origin)` | ✅ 正确 |
| realm / realmLayer | 无条件 shadow | ✅ 正确，仅结算修改 |
| cultivationSpeedBonus/Duration | 条件保留（main≠origin 时保留 main） | ✅ 正确 |
| equipment | 子字段级 mergeEquipment() | ✅ 见下 |
| combat | 子字段级 mergeCombat() | ✅ 见下 |
| manualIds | 集合 mergeManualIds() | ✅ 见下 |
| pillEffects | 子字段级 mergePillEffects() | ✅ 见下 |
| skills | 子字段级 mergeSkills() | ✅ 见下 |
| isAlive | 条件覆盖 | ✅ 保留已有正确逻辑 |
| discipleType/status/statusData | 保留 main | ✅ 正确 |

#### mergeEquipment ✅

- 结算域（nurture×4）→ shadow ✅
- 玩家域（weaponId×4, autoEquip, spiritStones, storageBagSpiritStones）→ main（copy 默认）✅
- 共享域（storageBagItems）→ 集合 delta ✅

**逐字段比对**（对照 `EquipmentSet` 14 子字段）：

| 子字段 | 归属 | 是否在合并中处理 |
|--------|------|-----------------|
| weaponNurture | 结算 | ✅ shadow |
| armorNurture | 结算 | ✅ shadow |
| bootsNurture | 结算 | ✅ shadow |
| accessoryNurture | 结算 | ✅ shadow |
| weaponId | 玩家 | ✅ main（copy 默认） |
| armorId | 玩家 | ✅ main（copy 默认） |
| bootsId | 玩家 | ✅ main（copy 默认） |
| accessoryId | 玩家 | ✅ main（copy 默认） |
| autoEquipFromWarehouse | 玩家 | ✅ main（copy 默认） |
| storageBagItems | 共享 | ✅ delta 合并 |
| storageBagSpiritStones | 玩家 | ✅ main（copy 默认） |
| spiritStones | 玩家 | ✅ main（copy 默认） |
| weaponNurture.equipmentId | 结算 | ✅ shadow（包含在 NurtureData 中） |
| （其余 3 个 NurtureData 同理） | 结算 | ✅ shadow |

#### mergeCombat ✅

**逐字段比对**（对照 `CombatAttributes` 18 子字段）：

| 子字段 | 归属 | 是否在合并中处理 |
|--------|------|-----------------|
| baseHp/baseMp | 结算 | ✅ shadow |
| basePhysicalAttack/MagicAttack | 结算 | ✅ shadow |
| basePhysicalDefense/MagicDefense | 结算 | ✅ shadow |
| baseSpeed | 结算 | ✅ shadow |
| hpVariance/mpVariance | 结算 | ✅ shadow |
| physicalAttackVariance 等 5 个 | 结算 | ✅ shadow |
| totalCultivation | 结算 | ✅ shadow |
| breakthroughCount/FailCount | 结算 | ✅ shadow |
| currentHp | 争议 | ⚠️ 见下文 |
| currentMp | 争议 | ⚠️ 见下文 |

**currentHp/currentMp 合并逻辑**：

```kotlin
val baseStatsChanged = shadow.baseHp != origin.baseHp || shadow.baseMp != origin.baseMp
currentHp = if (baseStatsChanged) shadow.currentHp else main.currentHp
```

`baseStatsChanged` 在两种情况下为 true：
1. **突破失败**：baseHp/baseMp 被重算（通常变小）+ currentHp/currentMp 被设为 10% → 用 shadow 正确 ✅
2. **突破成功**：baseHp/baseMp 被重算（变大）+ currentHp/currentMp **未变** → 用 shadow = origin，覆盖了 main 中可能的丹药回血 ⚠️

**LOW 建议**：改为直接检测 currentHp/currentMp 是否被结算修改：

```kotlin
// 更精确：只有突破失败（currentHp 被显式设为 10%）时才用 shadow
currentHp = if (shadow.currentHp != origin.currentHp) shadow.currentHp else main.currentHp,
currentMp = if (shadow.currentMp != origin.currentMp) shadow.currentMp else main.currentMp,
```

> 当前实现的后果仅限于"突破成功的中途，玩家用丹药回血刚好也在同一结算周期"这个极罕见场景，影响极小。不改也不影响正常玩法。

#### mergeManualIds ✅

```kotlin
return (main.toSet() + (shadowSet - originSet) - (originSet - shadowSet)).toList()
```

✅ OR-Set 模式正确：main 做底 + 结算新增 - 结算删除。

#### mergePillEffects ✅

```kotlin
val durationDelta = shadow.pillEffectDuration - origin.pillEffectDuration
return main.copy(pillEffectDuration = (main.pillEffectDuration + durationDelta).coerceAtLeast(0))
```

✅ 13 个 bonus 字段保留 main（copy 默认），duration 做 delta 合并。边界情况：
- 玩家没操作（main==origin）→ `origin.duration + delta = shadow.duration` ✅
- 玩家用了丹药（main.duration > origin.duration）→ `main.duration + delta` 包含了衰减 ✅

#### mergeSkills ✅

```kotlin
return main.copy(
    loyalty = shadow.loyalty,
    salaryPaidCount = shadow.salaryPaidCount,
    salaryMissedCount = shadow.salaryMissedCount,
)
```

✅ 付费字段从 shadow（结算修改），8 个技能值从 main（丹药修改）。

#### 槽位 CUSTOM 合并器 ✅

**elderSlots 合并器**：✅ 9 个 string 槽位正确处理"origin 有值 shadow 清空 → 清除 main"。

> LOW 建议：elderSlots 还有 11 个 list 类型槽位（preachingMasters 等）未处理。但 DiscipleService 在 `clearDiscipleFromAllSlots` 后立即调用 `autoFillLawEnforcementSlots()`，自动补位会覆盖旧值，实际影响有限。

**spiritMineSlots / librarySlots 合并器**：✅ 逐 slot 检查清除。

---

### 文件 2/4：`CultivationService.kt` — ⚠️ 需修复（1 HIGH, 1 MEDIUM）

#### HIGH — `recoverHpMpForAllDisciples` 使用错误的 maxHp

**当前代码**（line 247-248）：
```kotlin
val maxHp = d.maxHp   // → DiscipleStatCalculator.getBaseStats().maxHp
val maxMp = d.maxMp
```

**processPhaseTick 原始代码**（line 821-822）：
```kotlin
val finalStats = DiscipleStatCalculator.getFinalStats(d, equipmentMap, manualMap, discipleProficiencies)
val maxHp = finalStats.maxHp   // base + equipment + manuals + pills
val maxMp = finalStats.maxMp
```

**差异**：`d.maxHp` 仅含基础属性（combat base + realm + talent），不含装备/功法/丹药加成。`getFinalStats` 包含全部加成。

**影响**：`coerceAtMost(maxHp)` 使用较低的基础 maxHp，可能导致：
1. 已穿戴装备的弟子 HP 被错误截断（例如：baseMax=100，装备+50，实际max=150，当前HP=140 → 被截断为 100）
2. 恢复量略低（恢复量基于 maxHp 计算）

**修复**：使用 `DiscipleStatCalculator.getFinalStats` 替代 `d.maxHp`：

```kotlin
fun recoverHpMpForAllDisciples(state: MutableGameState) {
    val equipmentMap = state.equipmentInstances.associateBy { it.id }
    val manualMap = state.manualInstances.associateBy { it.id }
    val allProficiencies = state.gameData.manualProficiencies
    val multiplier = phaseMultiplier.toDouble()

    state.disciples = state.disciples.map { d ->
        if (!d.isAlive) return@map d
        val curHp = d.combat.currentHp
        val curMp = d.combat.currentMp
        if (curHp < 0 && curMp < 0) return@map d

        val proficiencyMap = allProficiencies[d.id]?.associateBy { it.manualId } ?: emptyMap()
        val finalStats = DiscipleStatCalculator.getFinalStats(d, equipmentMap, manualMap, proficiencyMap)
        val maxHp = finalStats.maxHp
        val maxMp = finalStats.maxMp

        val hpRecovery = (maxHp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * multiplier).toInt().coerceAtLeast(1)
        val mpRecovery = (maxMp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * multiplier).toInt().coerceAtLeast(1)
        val newHp = if (curHp < 0) curHp else (curHp + hpRecovery).coerceAtMost(maxHp)
        val newMp = if (curMp < 0) curMp else (curMp + mpRecovery).coerceAtMost(maxMp)

        if (newHp != curHp || newMp != curMp) {
            d.copyWith(currentHp = newHp, currentMp = newMp)
        } else {
            d
        }
    }
}
```

**与 processPhaseTick 的对齐验证**：

| 代码行 | processPhaseTick | recoverHpMpForAllDisciples（修复后） |
|--------|-----------------|--------------------------------------|
| 装备 Map | `currentEquipmentInstances.associateBy { it.id }` | `state.equipmentInstances.associateBy { it.id }` |
| 功法 Map | `currentManualInstances.associateBy { it.id }` | `state.manualInstances.associateBy { it.id }` |
| 熟练度 | `allProficiencies.getOrDefault(d.id, emptyList()).associateBy { it.manualId }` | `allProficiencies[d.id]?.associateBy { it.manualId } ?: emptyMap()` |
| maxHp | `finalStats.maxHp` | `finalStats.maxHp` |
| 恢复公式 | `(maxHp * rate * multiplier).toInt().coerceAtLeast(1)` | ✅ 一致 |
| 上限 | `.coerceAtMost(maxHp)` | ✅ 一致 |
| copyWith | `d.copyWith(currentHp = newHp, currentMp = newMp)` | ✅ 一致 |

#### MEDIUM — 恢复公式中 multiplier 来源

`processPhaseTick` 的 multiplier 来自 `val multiplier = phaseMultiplier.toDouble()`（line 776），与 `recoverHpMpForAllDisciples` 一致（line 244）。✅

---

### 文件 3/4：`GameEngineCore.kt` — ✅ 通过

```kotlin
if (settlementCoordinator.hasPendingWork) {
    systemManager.getSystem(TimeSystem::class).onPhaseTick(this)
    cultivationService.recoverHpMpForAllDisciples(this)  // 新增
}
```

✅ 调用位置正确：在 `stateStore.update {}` 闭包内，`this` 是 `MutableGameState`。

---

### 文件 4/4：`DiplomacyService.kt` — ✅ 通过

```kotlin
@Deprecated(
    "Use buyFromSectTradeSync() — scope.launch 在 swapFromShadow 期间存在竞态风险",
    ReplaceWith("buyFromSectTradeSync(sectId, itemId, quantity)")
)
fun buyFromSectTrade(sectId: String, itemId: String, quantity: Int = 1) {
```

✅ `@Deprecated` + `ReplaceWith` 正确。调用方（`WorldMapViewModel:202`）已在使用 `buyFromSectTradeSync`。

---

## 汇总

| 级别 | 数量 | 内容 |
|------|------|------|
| CRITICAL | 0 | — |
| **HIGH** | 1 | `recoverHpMpForAllDisciples` 使用 `d.maxHp`（基础值）而非 `getFinalStats`（含装备/功法/丹药），可能导致已穿戴装备弟子的 HP 被错误截断 |
| MEDIUM | 0 | — |
| LOW | 2 | ① mergeCombat 的 `baseStatsChanged` 在突破成功时也会取 shadow 的 currentHp（实际值不变，无功能影响）；② elderSlots 合并器未处理 list 类型槽位（有 autoFillLawEnforcementSlots 兜底） |

## 待修复

只需要修 `CultivationService.kt` 的 `recoverHpMpForAllDispatients` 一处（替换 maxHp/maxMp 来源为 `getFinalStats`），代码见上文 HIGH 条目。
