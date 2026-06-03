# 状态回退问题 — 修复实施方案

> 日期：2026-06-03
> 背景文档：[定位报告](state-revert-locating-report-v2.md)

---

## 一、问题一句话

结算（settlement）通过 shadow state 批量计算后，用 `mergeDiscipleAfterSettlement` 三路合并写回主状态。但 `equipment`、`combat`、`manualIds`、`pillEffects`、`skills` 等复合字段采用**整体覆盖**（"shadow 变了就用 shadow 整体替换 main"），导致玩家在主状态上的操作（穿装备、用丹药、学功法等）被覆盖丢失。

## 二、方案一句话

将整体覆盖改为**子字段级合并**：每个复合字段按"结算修改了什么、玩家修改了什么"拆开，各取所需。

行业对标验证：Unreal GAS 的 AttributeSet Aggregator、Bevy ECS 的 Component 级 Change Detection、Photon Fusion 的 Predict-Reconcile 与本方案同构。架构无需推翻。

## 三、修改清单

涉及 4 个文件，~250 行改动，集中在 `GameStateStore.kt`。

---

### 改动 1/4：`GameStateStore.kt` — 新增 5 个子字段合并函数

**路径**：`android/app/src/main/java/com/xianxia/sect/core/state/GameStateStore.kt`

在 `mergeDiscipleAfterSettlement` 方法之前（约 930 行）插入以下 5 个函数：

```kotlin
// ==================== 子字段级合并函数 ====================

/**
 * EquipmentSet 子字段合并。
 * 结算域（nurture×4）：总是从 shadow。玩家域（weaponId 等）：总是从 main。
 * 共享域（storageBagItems）：main 做底 + 结算新增 - 结算删除。
 */
private fun mergeEquipment(
    main: EquipmentSet, shadow: EquipmentSet, origin: EquipmentSet
): EquipmentSet {
    val originBagIds = origin.storageBagItems.map { it.itemId }.toSet()
    val shadowBagIds = shadow.storageBagItems.map { it.itemId }.toSet()
    val addedBySettlement = shadow.storageBagItems.filter { it.itemId !in originBagIds }
    val removedBySettlement = originBagIds - shadowBagIds
    val mergedBag = (main.storageBagItems + addedBySettlement)
        .filter { it.itemId !in removedBySettlement }

    return main.copy(
        weaponNurture = shadow.weaponNurture,
        armorNurture = shadow.armorNurture,
        bootsNurture = shadow.bootsNurture,
        accessoryNurture = shadow.accessoryNurture,
        storageBagItems = mergedBag,
    )
}

/**
 * CombatAttributes 子字段合并。
 * baseXxx/variance/统计 从 shadow。currentHp/currentMp 仅在结算发生突破时从 shadow。
 */
private fun mergeCombat(
    main: CombatAttributes, shadow: CombatAttributes, origin: CombatAttributes
): CombatAttributes {
    // 突破是唯一会修改 baseHp/baseMp 的操作
    val baseStatsChanged = shadow.baseHp != origin.baseHp || shadow.baseMp != origin.baseMp

    return main.copy(
        baseHp = shadow.baseHp,
        baseMp = shadow.baseMp,
        basePhysicalAttack = shadow.basePhysicalAttack,
        baseMagicAttack = shadow.baseMagicAttack,
        basePhysicalDefense = shadow.basePhysicalDefense,
        baseMagicDefense = shadow.baseMagicDefense,
        baseSpeed = shadow.baseSpeed,
        hpVariance = shadow.hpVariance,
        mpVariance = shadow.mpVariance,
        physicalAttackVariance = shadow.physicalAttackVariance,
        magicAttackVariance = shadow.magicAttackVariance,
        physicalDefenseVariance = shadow.physicalDefenseVariance,
        magicDefenseVariance = shadow.magicDefenseVariance,
        speedVariance = shadow.speedVariance,
        totalCultivation = shadow.totalCultivation,
        breakthroughCount = shadow.breakthroughCount,
        breakthroughFailCount = shadow.breakthroughFailCount,
        currentHp = if (baseStatsChanged) shadow.currentHp else main.currentHp,
        currentMp = if (baseStatsChanged) shadow.currentMp else main.currentMp,
    )
}

/**
 * manualIds 集合合并：main + 结算新增 - 结算删除。
 */
private fun mergeManualIds(
    main: List<String>, shadow: List<String>, origin: List<String>
): List<String> {
    val originSet = origin.toSet()
    val shadowSet = shadow.toSet()
    return (main.toSet() + (shadowSet - originSet) - (originSet - shadowSet)).toList()
}

/**
 * PillEffects 子字段合并。
 * 13 个 bonus 字段从 main（只有玩家用丹药修改），pillEffectDuration 做 delta 合并。
 */
private fun mergePillEffects(
    main: PillEffects, shadow: PillEffects, origin: PillEffects
): PillEffects {
    val durationDelta = shadow.pillEffectDuration - origin.pillEffectDuration
    return main.copy(
        pillEffectDuration = (main.pillEffectDuration + durationDelta).coerceAtLeast(0)
    )
}

/**
 * Skills 子字段合并。
 * loyalty/salaryPaidCount/salaryMissedCount 从 shadow（结算修改），其余从 main。
 */
private fun mergeSkills(
    main: SkillStats, shadow: SkillStats, origin: SkillStats
): SkillStats {
    return main.copy(
        loyalty = shadow.loyalty,
        salaryPaidCount = shadow.salaryPaidCount,
        salaryMissedCount = shadow.salaryMissedCount,
    )
}
```

---

### 改动 2/4：`GameStateStore.kt` — 重写 `mergeDiscipleAfterSettlement`

替换原方法（当前约 965-996 行）：

```kotlin
private fun mergeDiscipleAfterSettlement(
    mainDisciple: Disciple,
    shadowDisciple: Disciple,
    originDisciple: Disciple
): Disciple {
    val died = originDisciple.isAlive && !shadowDisciple.isAlive
    val revived = !originDisciple.isAlive && shadowDisciple.isAlive

    return mainDisciple.copy(
        // 标量 delta 合并
        cultivation = mainDisciple.cultivation + (shadowDisciple.cultivation - originDisciple.cultivation),
        lifespan = mainDisciple.lifespan + (shadowDisciple.lifespan - originDisciple.lifespan),

        // 无条件 shadow（仅结算修改）
        realm = shadowDisciple.realm,
        realmLayer = shadowDisciple.realmLayer,

        // 条件保留（玩家可能用丹药修改）
        cultivationSpeedBonus = if (mainDisciple.cultivationSpeedBonus != originDisciple.cultivationSpeedBonus)
            mainDisciple.cultivationSpeedBonus else shadowDisciple.cultivationSpeedBonus,
        cultivationSpeedDuration = if (mainDisciple.cultivationSpeedDuration != originDisciple.cultivationSpeedDuration)
            mainDisciple.cultivationSpeedDuration else shadowDisciple.cultivationSpeedDuration,

        // 子字段级合并
        equipment = mergeEquipment(mainDisciple.equipment, shadowDisciple.equipment, originDisciple.equipment),
        combat = mergeCombat(mainDisciple.combat, shadowDisciple.combat, originDisciple.combat),
        manualIds = mergeManualIds(mainDisciple.manualIds, shadowDisciple.manualIds, originDisciple.manualIds),
        pillEffects = mergePillEffects(mainDisciple.pillEffects, shadowDisciple.pillEffects, originDisciple.pillEffects),
        skills = mergeSkills(mainDisciple.skills, shadowDisciple.skills, originDisciple.skills),

        // 条件覆盖（已有模式）
        isAlive = if (died || revived) shadowDisciple.isAlive else mainDisciple.isAlive,

        // 玩家操作字段
        discipleType = mainDisciple.discipleType,
        status = mainDisciple.status,
        statusData = mainDisciple.statusData,
    )
}
```

---

### 改动 3/4：槽位字段合并 + HP/MP 恢复

#### 3a. `GameStateStore.kt` — `customGameDataMergers` 新增 3 个合并器

在 `customGameDataMergers` map（约 346 行）末尾追加：

```kotlin
"elderSlots" to { origin, shadow, oldState ->
    val o = origin.elderSlots; val s = shadow.elderSlots
    var r = oldState.elderSlots
    if (o.viceSectMaster.isNotEmpty() && s.viceSectMaster.isEmpty()) r = r.copy(viceSectMaster = "")
    if (o.herbGardenElder.isNotEmpty() && s.herbGardenElder.isEmpty()) r = r.copy(herbGardenElder = "")
    if (o.alchemyElder.isNotEmpty() && s.alchemyElder.isEmpty()) r = r.copy(alchemyElder = "")
    if (o.forgeElder.isNotEmpty() && s.forgeElder.isEmpty()) r = r.copy(forgeElder = "")
    if (o.outerElder.isNotEmpty() && s.outerElder.isEmpty()) r = r.copy(outerElder = "")
    if (o.preachingElder.isNotEmpty() && s.preachingElder.isEmpty()) r = r.copy(preachingElder = "")
    if (o.lawEnforcementElder.isNotEmpty() && s.lawEnforcementElder.isEmpty()) r = r.copy(lawEnforcementElder = "")
    if (o.innerElder.isNotEmpty() && s.innerElder.isEmpty()) r = r.copy(innerElder = "")
    if (o.qingyunPreachingElder.isNotEmpty() && s.qingyunPreachingElder.isEmpty()) r = r.copy(qingyunPreachingElder = "")
    r
},
"spiritMineSlots" to { origin, shadow, oldState ->
    val oSlots = origin.spiritMineSlots.associateBy { it.index }
    val sSlots = shadow.spiritMineSlots.associateBy { it.index }
    oldState.spiritMineSlots.map { slot ->
        val os = oSlots[slot.index] ?: return@map slot
        val ss = sSlots[slot.index] ?: return@map slot
        if (os.discipleId.isNotEmpty() && ss.discipleId.isEmpty()) slot.copy(discipleId = "", discipleName = "") else slot
    }
},
"librarySlots" to { origin, shadow, oldState ->
    val oSlots = origin.librarySlots.associateBy { it.index }
    val sSlots = shadow.librarySlots.associateBy { it.index }
    oldState.librarySlots.map { slot ->
        val os = oSlots[slot.index] ?: return@map slot
        val ss = sSlots[slot.index] ?: return@map slot
        if (os.discipleId.isNotEmpty() && ss.discipleId.isEmpty()) slot.copy(discipleId = "", discipleName = "") else slot
    }
},
```

然后在 `mergeGameData()` 的 `shadow.copy()` 调用中做两件事：

**① 从 PRESERVE_OLD 块删除这 3 行**：
```kotlin
elderSlots = oldState.elderSlots,
spiritMineSlots = oldState.spiritMineSlots,
librarySlots = oldState.librarySlots,
```

**② 在 CUSTOM 块追加这 3 行**：
```kotlin
elderSlots = c["elderSlots"]!!(origin, shadow, oldState) as ElderSlots,
spiritMineSlots = c["spiritMineSlots"]!!(origin, shadow, oldState) as List<MineSlot>,
librarySlots = c["librarySlots"]!!(origin, shadow, oldState) as List<LibrarySlot>,
```

#### 3b. `CultivationService.kt` — 提取 HP/MP 恢复方法

**路径**：`android/app/src/main/java/com/xianxia/sect/core/engine/service/CultivationService.kt`

从 `processPhaseTick()` 中搬运 HP/MP 恢复段落，新增独立方法：

```kotlin
/**
 * 恢复所有弟子的 HP/MP。
 * 从 processPhaseTick 中提取，以便结算期间也可独立调用。
 */
fun recoverHpMpForAllDisciples(state: MutableGameState) {
    val disciples = state.disciples
    for (i in disciples.indices) {
        val d = disciples[i]
        if (!d.isAlive) continue
        val maxHp = d.maxHp
        val maxMp = d.maxMp
        val curHp = if (d.combat.currentHp < 0) maxHp else d.combat.currentHp
        val curMp = if (d.combat.currentMp < 0) maxMp else d.combat.currentMp
        if (curHp >= maxHp && curMp >= maxMp) continue

        val multiplier = 1.0
        val hpRecovery = (maxHp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * multiplier).toInt().coerceAtLeast(1)
        val mpRecovery = (maxMp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * multiplier).toInt().coerceAtLeast(1)
        val newHp = (curHp + hpRecovery).coerceAtMost(maxHp)
        val newMp = (curMp + mpRecovery).coerceAtMost(maxMp)

        if (newHp != curHp || newMp != curMp) {
            disciples[i] = d.copyWith(currentHp = newHp, currentMp = newMp)
        }
    }
    state.disciples = disciples
}
```

> **注意**：恢复公式中的 `multiplier` 等参数应与 `processPhaseTick` 现有逻辑完全一致，搬运时以实际代码为准。

#### 3c. `GameEngineCore.kt` — 结算期间调用恢复

**路径**：`android/app/src/main/java/com/xianxia/sect/core/engine/GameEngineCore.kt`

在 `tickInternal()` 中（约 319 行），结算分支追加一行：

```kotlin
// 修改前：
if (settlementCoordinator.hasPendingWork) {
    systemManager.getSystem(TimeSystem::class).onPhaseTick(this)

// 修改后：
if (settlementCoordinator.hasPendingWork) {
    systemManager.getSystem(TimeSystem::class).onPhaseTick(this)
    cultivationService.recoverHpMpForAllDisciples(this)
```

---

### 改动 4/4：`DiplomacyService.kt` — 废弃异步版本

**路径**：`android/app/src/main/java/com/xianxia/sect/core/engine/domain/diplomacy/DiplomacyService.kt`

在 `buyFromSectTrade()` 方法上（约 807 行）加 `@Deprecated`：

```kotlin
@Deprecated(
    "Use buyFromSectTradeSync() — scope.launch 在 swapFromShadow 期间存在竞态风险",
    ReplaceWith("buyFromSectTradeSync(sectId, itemId, quantity)")
)
fun buyFromSectTrade(sectId: String, itemId: String, quantity: Int = 1) {
```

> ViewModel 层（`WorldMapViewModel.kt:202`）已使用 `buyFromSectTradeSync`，此改动仅防止未来误用。

---

## 四、验证步骤

```bash
# 1. 编译
cd android && ./gradlew.bat compileReleaseKotlin

# 2. 现有测试
cd android && ./gradlew.bat test
```

手动验证 6 个场景：

| # | 操作 | 预期 |
|---|------|------|
| 1 | 结算期间穿装备 → 结算完成 | 装备不丢失 |
| 2 | 结算期间用丹药 → 结算完成 | 丹药效果保留，duration 正常衰减 |
| 3 | 结算期间宗门买物品 → 结算完成 | 物品到账，灵石正确扣除 |
| 4 | 结算期间学功法 → 结算完成 | 功法不丢失 |
| 5 | 结算弟子脱离 → 结算完成 | 槽位清除 |
| 6 | 突破失败 HP=10%，等 3 个月 | HP 逐步恢复 |

## 五、修复的问题覆盖

| 问题 | 根因 | 修复点 |
|------|------|--------|
| 装备回退 | equipment 整体覆盖 | 改动 1 mergeEquipment |
| 赏赐回退 | 同上 | 同上 |
| 功法回退 | manualIds 整体覆盖 | 改动 1 mergeManualIds |
| 丹药回退 | pillEffects/skills 无条件 shadow | 改动 1 mergePillEffects / mergeSkills |
| HP/MP 不恢复 | 结算跳过恢复 + combat 覆盖 | 改动 1 mergeCombat + 改动 3b/3c |
| 宗门交易回退 | scope.launch 竞态 | 改动 4 |
| 弟子脱离槽位残留 | 槽位 PRESERVE_OLD | 改动 3a |
