# 状态回退问题定位报告 v2

> 日期：2026-06-03
> 范围：装备回退、宗门交易回退、气血灵力不恢复、赏赐回退
> 状态：仅定位，未修复

---

## 一、问题清单

| # | 问题 | 严重度 | 根因分类 |
|---|------|--------|----------|
| 1 | 给弟子穿戴装备后装备回退 | 高 | mergeDiscipleAfterSettlement 整体覆盖 |
| 2 | 宗门交易回退 | 高 | 异步写入竞态 + sectDetails 合并时序 |
| 3 | 弟子气血和灵力不足不会自动恢复 | 高 | 结算期间跳过 HP/MP 恢复 + combat 整体覆盖 |
| 4 | 赏赐弟子回退 | 高 | mergeDiscipleAfterSettlement 整体覆盖 |
| 5 | 学习功法回退（新发现） | 高 | manualIds 整体覆盖 |
| 6 | 使用丹药效果回退（新发现） | 高 | pillEffects/skills/lifespan 无条件从 shadow |
| 7 | 结算期间弟子脱离后槽位残留（新发现） | 中 | elderSlots 等属于 PRESERVE_OLD |

---

## 二、架构背景

### 2.1 Shadow 机制

结算（settlement）期间使用 `MutableGameState`（shadow state）进行批量计算，结算完成后通过 `swapFromShadow(shadow)` 一次性写回主状态。

```
createShadow()          →  保存 shadowOrigin 快照（结算开始时的状态）
结算各阶段修改 shadow   →  shadow 包含结算结果
swapFromShadow(shadow)  →  三路合并（origin / shadow / oldState）写回主状态
```

### 2.2 三路合并

`swapFromShadow` 使用 origin（结算开始时的快照）、shadow（结算结果）、old（当前主状态）进行合并：

- **GameData**：`mergeGameData()` 按字段 @SettlementStrategy 分类合并
- **Disciple**：`mergeDiscipleAfterSettlement()` 按字段分类合并
- **Inventory 字段**（equipmentStacks 等）：从 oldState 保留（e9ba656f 修复后）

### 2.3 StateAccessor 双写

`CultivationService` 的 `currentDisciples`/`currentGameData` 等属性通过 `StateAccessorFactory` 实现：

```kotlin
private var currentDisciples: List<Disciple>
    get() = stateStore.currentTransactionMutableState()?.disciples ?: stateStore.disciples.value
    set(value) {
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) { ts.disciples = value; return }      // 写入 shadow
        scope.launch { stateStore.update { disciples = value } } // 写入主状态
    }
```

当 `beginShadowTransaction` 激活时，setter 写入 shadow；否则异步写入主状态。

### 2.4 结算期间的游戏循环

```kotlin
// GameEngineCore.tickInternal()
stateStore.update {
    if (settlementCoordinator.hasPendingWork) {
        systemManager.getSystem(TimeSystem::class).onPhaseTick(this)  // 仅推进时间
    } else {
        systemManager.onPhaseTick(this)  // 全部系统，包含 CultivationTickSystem
    }
}
```

结算期间 `processPhaseTick`（含 HP/MP 恢复、自动装备、自动学习、自动用丹等）**不被调用**。

---

## 三、根因分析

### 3.1 共同根因：mergeDiscipleAfterSettlement 字段合并策略不足

当前合并逻辑（[GameStateStore.kt:965-996](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/state/GameStateStore.kt#L965)）：

```kotlin
private fun mergeDiscipleAfterSettlement(
    mainDisciple: Disciple, shadowDisciple: Disciple, originDisciple: Disciple
): Disciple {
    val equipChanged = originDisciple.equipment != shadowDisciple.equipment
    val combatChanged = originDisciple.combat != shadowDisciple.combat
    val manualsChanged = originDisciple.manualIds != shadowDisciple.manualIds

    return mainDisciple.copy(
        cultivation = shadowDisciple.cultivation,           // 无条件 shadow
        realm = shadowDisciple.realm,                       // 无条件 shadow
        realmLayer = shadowDisciple.realmLayer,             // 无条件 shadow
        lifespan = shadowDisciple.lifespan,                 // 无条件 shadow
        skills = shadowDisciple.skills,                     // 无条件 shadow
        cultivationSpeedBonus = shadowDisciple.cultivationSpeedBonus,  // 无条件 shadow
        cultivationSpeedDuration = shadowDisciple.cultivationSpeedDuration, // 无条件 shadow
        pillEffects = shadowDisciple.pillEffects,           // 无条件 shadow
        isAlive = if (died || revived) shadowDisciple.isAlive else mainDisciple.isAlive, // 条件覆盖 ✓
        equipment = if (equipChanged) shadowDisciple.equipment else mainDisciple.equipment,  // 整体覆盖 ✗
        combat = if (combatChanged) shadowDisciple.combat else mainDisciple.combat,          // 整体覆盖 ✗
        manualIds = if (manualsChanged) shadowDisciple.manualIds else mainDisciple.manualIds, // 整体覆盖 ✗
        discipleType = mainDisciple.discipleType,           // 保留 main ✓
        status = mainDisciple.status,                       // 保留 main ✓
        statusData = mainDisciple.statusData                // 保留 main ✓
    )
}
```

**问题模式**：当 shadow 和 main 对同一复合字段的**不同子字段**进行修改时，二选一策略无法保留双方的修改。

这与历史修复 `f64b6354`（isAlive 按变更来源取值）是同一模式，但 equipment/combat/manualIds 仍未修复。

---

### 3.2 问题 1：装备回退

**触发路径**：

1. 玩家给弟子穿戴武器 → `DiscipleService.equipEquipment()` → `stateStore.update { ... }` 修改主状态弟子 `equipment.weaponId`
2. 结算期间 `processAutoEquip` 修改 shadow 中同一弟子的 `equipment.storageBagItems`（自动装备到背包）
3. `equipChanged = true`（origin.equipment != shadow.equipment，因为 storageBagItems 变了）
4. 合并时 `shadowDisciple.equipment` **整体覆盖** `mainDisciple.equipment`
5. 玩家穿戴的 weaponId 丢失

**EquipmentSet 子字段**（[DiscipleComponents.kt:88-110](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/model/DiscipleComponents.kt#L88)）：

| 子字段 | 结算修改 | 玩家修改 |
|--------|---------|---------|
| weaponId | 否 | 是（穿戴/赏赐） |
| armorId | 否 | 是（穿戴/赏赐） |
| bootsId | 否 | 是（穿戴/赏赐） |
| accessoryId | 否 | 是（穿戴/赏赐） |
| weaponNurture | 是（孕养） | 否 |
| armorNurture | 是（孕养） | 否 |
| bootsNurture | 是（孕养） | 否 |
| accessoryNurture | 是（孕养） | 否 |
| autoEquipFromWarehouse | 否 | 是（设置） |
| storageBagItems | 是（自动装备/自动用丹） | 是（赏赐） |
| storageBagSpiritStones | 否 | 是（偷盗） |
| spiritStones | 否 | 是（赏赐） |

结算修改的是 nurture 和 storageBagItems，玩家修改的是 weaponId/armorId 等，两者互不冲突但被整体覆盖。

---

### 3.3 问题 2：宗门交易回退

**触发路径 A — 异步竞态**：

1. 玩家购买物品 → `DiplomacyService.buyFromSectTrade()` ([DiplomacyService.kt:807](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/domain/diplomacy/DiplomacyService.kt#L807))
2. 验证使用 `stateStore.gameData.value`（快照），更新通过 `scope.launch { stateStore.update { ... } }` 异步执行
3. 如果 `swapFromShadow` 的 `update {}` 先获取 mutex，异步更新排队等待
4. `swapFromShadow` 完成后，异步更新获取 mutex，基于旧快照的 `v.updatedSectDetails` 写入，覆盖合并结果

**触发路径 B — spiritStones 竞态**：

`buyFromSectTrade` 在 `stateStore.update` 中修改 `gameData.spiritStones`，但 `mergeGameData` 对 spiritStones 使用 DELTA 合并：

```kotlin
spiritStones = oldState.spiritStones + (shadow.spiritStones - origin.spiritStones)
```

如果异步更新在 `swapFromShadow` 之后执行，`gameData.spiritStones - v.totalPrice` 基于旧 gameData 计算，会覆盖 DELTA 合并后的正确值。

**sectDetails CUSTOM 合并器**（[GameStateStore.kt:367-385](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/state/GameStateStore.kt#L367)）已正确处理 tradeItems 的玩家修改检测，但如果异步写入在 swapFromShadow 之后执行，仍会覆盖合并结果。

---

### 3.4 问题 3：气血灵力不恢复

**根因 A — 结算期间跳过恢复**：

HP/MP 恢复逻辑在 `CultivationService.processPhaseTick()` ([CultivationService.kt:800-807](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/service/CultivationService.kt#L800))：

```kotlin
val hpRecovery = (maxHp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * multiplier).toInt().coerceAtLeast(1)
val newHp = if (curHp < 0) curHp else (curHp + hpRecovery).coerceAtMost(maxHp)
```

但 `GameEngineCore` ([GameEngineCore.kt:319](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/GameEngineCore.kt#L319)) 在 `hasPendingWork` 时只调用 `TimeSystem.onPhaseTick`，不调用 `CultivationTickSystem.onPhaseTick`。结算可能持续多个月，期间弟子 HP/MP 不会恢复。

**根因 B — combat 整体覆盖**：

即使非结算期间 HP/MP 恢复正常执行，`combat` 字段的合并策略可能导致恢复被覆盖。结算期间突破失败会修改 shadow 中弟子的 `combat`（设 currentHp/currentMp 为 10%）：

```kotlin
// SettlementCoordinator.kt:488-493
d = d.copyWith(
    cultivation = 0.0,
    currentHp = (curHp * 0.1).toInt().coerceAtLeast(1),
    currentMp = (curMp * 0.1).toInt().coerceAtLeast(1)
)
```

合并时 `combatChanged = true`，shadow 的 combat（低 HP/MP）覆盖主状态中已恢复的 combat。

**CombatAttributes 子字段**（[DiscipleComponents.kt:10-37](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/model/DiscipleComponents.kt#L10)）：

| 子字段 | 结算修改 | 玩家/恢复修改 |
|--------|---------|--------------|
| baseHp/baseMp 等 | 突破重算 | 否 |
| hpVariance/mpVariance 等 | 否 | 否 |
| currentHp | 突破失败设 10% | HP 恢复、丹药回血 |
| currentMp | 突破失败设 10% | MP 恢复、丹药回蓝 |
| totalCultivation | 突破统计 | 否 |
| breakthroughCount/breakthroughFailCount | 突破统计 | 否 |

---

### 3.5 问题 4：赏赐回退

与问题 1 同根因。`rewardItemsToDisciple()` ([DiscipleFacadeImpl.kt:199](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/domain/disciple/DiscipleFacadeImpl.kt#L199)) 通过 `stateStore.update` 修改主状态弟子的 `equipment` 字段（设置 weaponId/armorId 等）和 `storageBagItems`。如果结算期间 shadow 中同一弟子的 `equipment` 发生变化，`equipChanged = true`，shadow 的 equipment 整体覆盖赏赐操作。

赏赐丹药时还修改 `combat.currentHp`（healMaxHpPercent）和 `pillEffects`，这些也会被 shadow 覆盖。

---

### 3.6 问题 5：学习功法回退（新发现）

**触发路径**：

1. 玩家手动学习功法 → `DiscipleFacadeImpl.learnManual()` → `stateStore.update { ... }` 修改主状态弟子 `manualIds`
2. 结算期间 `processAutoLearn` 修改 shadow 中同一弟子的 `manualIds`（自动学习新功法）
3. `manualsChanged = true`（origin.manualIds != shadow.manualIds）
4. 合并时 `shadowDisciple.manualIds` **整体覆盖** `mainDisciple.manualIds`
5. 玩家手动学习的功法丢失

`manualIds` 是 `List<String>`，无法做子字段合并，需要做集合合并（shadow ∪ 玩家新增 - 玩家删除）。

---

### 3.7 问题 6：使用丹药效果回退（新发现）

**受影响字段**：

| 字段 | 合并策略 | 结算修改 | 玩家修改 | 风险 |
|------|----------|---------|---------|------|
| pillEffects | 无条件 shadow | processPhaseTick 衰减 duration、到期清零 | usePill 设置新效果（14个子字段） | **高** |
| skills | 无条件 shadow | 结算忠诚度变化 | usePill 增加多种属性（12个子字段） | **中** |
| lifespan | 无条件 shadow | 突破增加寿命 | usePill 延寿丹增加寿命 | **中** |
| cultivation | 无条件 shadow | 结算修炼进度 | usePill 修炼丹增加 | **低** |
| cultivationSpeedBonus/Duration | 无条件 shadow | processPhaseTick 衰减 duration | usePill 设置新 buff | **中** |

**典型场景**：

1. 玩家给弟子使用攻击丹药 → 主状态弟子 `pillEffects.pillPhysicalAttackBonus = 50, pillEffectDuration = 300`
2. 结算期间 `processPhaseTick` 在 shadow 上衰减 duration → `shadowDisciple.pillEffects.pillEffectDuration -= 10`
3. 合并时 `pillEffects = shadowDisciple.pillEffects`（无条件从 shadow）
4. 玩家刚使用的攻击丹药效果被 shadow 的旧值覆盖

**PillEffects 子字段**（[DiscipleComponents.kt:66-81](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/model/DiscipleComponents.kt#L66)）：

| 子字段 | 结算修改 | 玩家修改 |
|--------|---------|---------|
| pillPhysicalAttackBonus | 否 | 是（攻击丹） |
| pillMagicAttackBonus | 否 | 是（法攻丹） |
| pillPhysicalDefenseBonus | 否 | 是（防御丹） |
| pillMagicDefenseBonus | 否 | 是（法防丹） |
| pillHpBonus | 否 | 是（生命丹） |
| pillMpBonus | 否 | 是（灵力丹） |
| pillSpeedBonus | 否 | 是（速度丹） |
| pillCritRateBonus | 否 | 是（暴击丹） |
| pillCritEffectBonus | 否 | 是（暴伤丹） |
| pillCultivationSpeedBonus | 否 | 是（修炼加速丹） |
| pillSkillExpSpeedBonus | 否 | 是（技能加速丹） |
| pillNurtureSpeedBonus | 否 | 是（孕养加速丹） |
| pillEffectDuration | 是（衰减） | 是（设置） |
| activePillCategory | 否 | 是（设置） |

---

### 3.8 问题 7：结算期间弟子脱离后槽位残留（新发现）

**触发路径**：

1. 结算期间 `processLawEnforcementMonthly` → 弟子脱离
2. `clearDiscipleFromAllSlots()` 修改 `currentGameData`（通过 StateAccessor）→ 写入 shadow 的 gameData
3. `swapFromShadow` 合并时，`elderSlots`/`spiritMineSlots`/`librarySlots` 属于 `PRESERVE_OLD` 策略（从 oldState 取值）
4. shadow 中清除的槽位引用被 oldState 的旧值覆盖
5. 脱离弟子占用的槽位引用残留

---

## 四、历史修复对照

| 提交 | 问题 | 根因 | 修复方式 | 当前状态 |
|------|------|------|----------|----------|
| `59c3caf0` v3.0.97 | 脱离/偷盗提示框重复弹出 | update() 事务内 pendingNotification 无条件覆盖 | 检测 block 是否显式修改 notification | 已修复 ✓ |
| `8f72db93` v3.1.87 | swapFromShadow 全量覆盖 | 无三路合并 | 引入三路合并 | 已修复 ✓ |
| `e9ba656f` v3.1.90 | 库存/经济字段遗漏 | 三路合并遗漏 inventory 字段 | 库存从 oldState 保留；spiritStones DELTA | 已修复 ✓ |
| `f64b6354` | isAlive 无条件覆盖 | 结算修改弟子→isAlive 被还原 | isAlive 仅结算真的杀了/复活时用 shadow | 已修复 ✓ |
| `75a1f154` | 弟子脱离不被删除 | filter 移除弟子但合并只遍历 oldState | mapNotNull：shadow 无但 origin 有→null | 已修复 ✓ |
| `0fbbe39e` v3.2.00 | swapFromShadow 绕过 mutex | 竞态写 StateFlow | swapFromShadow 改为 suspend + update{} | 已修复 ✓ |

**当前问题与历史修复的同一模式**：`f64b6354` 修复了 `isAlive` 的"按变更来源取值"，但 `equipment`/`combat`/`manualIds`/`pillEffects`/`skills` 仍是整体覆盖或无条件从 shadow。

---

## 五、受影响字段完整风险矩阵

### 高风险（玩家操作会丢失）

| 字段 | 合并策略 | 复合字段子项数 | 结算修改子项 | 玩家修改子项 | 冲突方式 |
|------|----------|-------------|------------|------------|---------|
| equipment | 条件整体覆盖 | 14 | nurture×4, storageBagItems | weaponId 等×4, storageBagItems, autoEquip | 不同子字段修改，整体覆盖 |
| combat | 条件整体覆盖 | 18 | currentHp/Mp (突破失败), baseXxx (突破重算) | currentHp/Mp (恢复/丹药) | 不同子字段修改，整体覆盖 |
| manualIds | 条件整体覆盖 | List | 自动学习新增 ID | 手动学习新增 ID | 集合合并需求，整体覆盖 |
| pillEffects | 无条件 shadow | 14 | pillEffectDuration (衰减) | 全部 14 子字段 (使用丹药) | 不同子字段修改，无条件覆盖 |

### 中风险

| 字段 | 合并策略 | 结算修改 | 玩家修改 | 冲突方式 |
|------|----------|---------|---------|---------|
| skills | 无条件 shadow | loyalty (结算) | intelligence/charm 等 (丹药) | 不同子字段修改，无条件覆盖 |
| cultivationSpeedBonus | 无条件 shadow | 衰减 duration | 设置新 buff | 同一字段，无条件覆盖 |
| cultivationSpeedDuration | 无条件 shadow | 衰减 duration | 设置新 duration | 同一字段，无条件覆盖 |

### 低风险（可做 delta 合并）

| 字段 | 合并策略 | 结算修改 | 玩家修改 | 建议策略 |
|------|----------|---------|---------|---------|
| lifespan | 无条件 shadow | 突破增加 | 延寿丹增加 | DELTA: main + (shadow - origin) |
| cultivation | 无条件 shadow | 修炼增加 | 修炼丹增加 | DELTA: main + (shadow - origin) |

### 无风险

| 字段 | 合并策略 | 说明 |
|------|----------|------|
| realm/realmLayer | 无条件 shadow | 只有结算修改 |
| isAlive | 条件覆盖 | 已修复 |
| discipleType/status/statusData | 保留 main | 玩家操作字段 |
| manualMasteries | copy() 默认保留 main | 未参与合并 |
| usage | copy() 默认保留 main | 未参与合并 |
| social | copy() 默认保留 main | 未参与合并 |

---

## 六、额外发现

### 6.1 DiplomacyService 异步写入模式

`buyFromSectTrade` 使用 `scope.launch { stateStore.update { ... } }` 异步更新，存在 stale read 风险：验证基于 `stateStore.gameData.value` 快照，但更新在异步协程中执行，可能基于过时数据。

### 6.2 结算期间 processPhaseTick 被跳过

`GameEngineCore` 在 `hasPendingWork` 时只调用 `TimeSystem.onPhaseTick`，跳过 `CultivationTickSystem.onPhaseTick`。这意味着结算期间以下功能不执行：
- HP/MP 自动恢复
- 自动装备（processAutoEquip）
- 自动学习（processAutoLearn）
- 自动使用丹药（processAutoUsePills）
- 修炼速度 buff 衰减

### 6.3 processMonthlyEventsOnShadow 的双写问题

`processMonthlyEventsOnShadow` 调用 `processMonthlyEvents`，后者调用 `processLawEnforcementMonthly`。该方法通过 `currentDisciples`/`currentGameData`（StateAccessor）修改状态，在 `beginShadowTransaction` 激活时写入 shadow。但 `clearDiscipleFromAllSlots` 修改的 `elderSlots` 等字段属于 `PRESERVE_OLD`，shadow 中的清除操作会被主状态旧值覆盖。

---

## 七、关键代码位置索引

| 文件 | 行号 | 说明 |
|------|------|------|
| [GameStateStore.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/state/GameStateStore.kt#L965) | 965-996 | mergeDiscipleAfterSettlement |
| [GameStateStore.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/state/GameStateStore.kt#L493) | 493-530 | swapFromShadow |
| [GameStateStore.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/state/GameStateStore.kt#L839) | 839-927 | mergeGameData |
| [GameStateStore.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/state/GameStateStore.kt#L367) | 367-385 | sectDetails CUSTOM 合并器 |
| [GameStateStore.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/state/GameStateStore.kt#L674) | 674-762 | update() 事务机制 |
| [GameEngineCore.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/GameEngineCore.kt#L307) | 307-358 | tickInternal + 结算调度 |
| [CultivationService.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/service/CultivationService.kt#L100) | 100-130 | StateAccessor 双写 |
| [CultivationService.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/service/CultivationService.kt#L745) | 745-926 | processPhaseTick |
| [CultivationService.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/service/CultivationService.kt#L1992) | 1992-2030 | processLawEnforcementMonthly |
| [SettlementCoordinator.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/domain/settlement/SettlementCoordinator.kt#L488) | 488-493 | 突破失败设 HP/MP=10% |
| [DiplomacyService.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/domain/diplomacy/DiplomacyService.kt#L807) | 807-820 | buyFromSectTrade 异步更新 |
| [DiscipleFacadeImpl.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/domain/disciple/DiscipleFacadeImpl.kt#L199) | 199-629 | rewardItemsToDisciple |
| [DiscipleComponents.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/model/DiscipleComponents.kt#L10) | 10-165 | CombatAttributes/PillEffects/EquipmentSet/SkillStats 结构 |
