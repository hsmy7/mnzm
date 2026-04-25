# 功法槽位隐患审查与修复报告

## 版本信息
- **修复版本**: v2.5.17 / v2.5.18
- **修复日期**: 2026-04-25 / 2026-04-26
- **关联版本**: v2.5.13（装备槽位修复）

---

## 一、问题背景

### 1.1 装备槽位前车之鉴（v2.5.13 修复）

在前几个版本中，手动存档后弟子**装备槽位不显示**的问题被修复。根因是：

| 根因 | 具体表现 | 修复方式 |
|------|---------|---------|
| 竞态条件 | `equipEquipment` 中多个 property setter 产生独立异步更新，状态被覆盖 | 改为 `stateStore.update {}` 原子事务 |
| 传参错误 | `unequipItem` 传入 `slot.name` 而非实际装备 ID | 改为根据 slot 查找实际装备 ID |
| 搜索范围 | `bagStackIds` 搜索所有弟子储物袋 | 改为仅搜索当前弟子储物袋 |

### 1.2 功法槽位审查动机

用户反馈：弟子功法槽位可能发生了与装备类似的"槽位不显示"问题。

---

## 二、审查过程

### 2.1 装备 vs 功法对照检查

| 根因 | 装备（已修复） | 功法（当前） | 结论 |
|------|-------------|-----------|------|
| 竞态条件 | 已修复（原子事务） | `learnManual/forgetManual/replaceManual` 已在 `stateStore.update` 事务中 | 无此问题 |
| 传参错误 | 已修复 | `forgetManual` 直接传 `instanceId` | 无此问题 |
| 搜索范围 | 已修复 | `manualBagStackIds` 只搜当前弟子 | 无此问题 |

**结论**：装备槽位的三个根因在功法系统中均不存在。

### 2.2 功法系统独有隐患发现

通过深入审查，发现了功法系统**独有的4类隐患**：

| # | 隐患位置 | 问题描述 | 症状 |
|---|---------|---------|------|
| 1 | `GameEngine.learnManual` | **缺少槽位上限检查** | 弟子可学习超过 `maxManualSlots` 数量的功法，超出部分在 UI 中被 `take(maxSlots)` 截断，表现为"槽位不显示" |
| 2 | `GameEngine.learnManual` | 仅检查 MIND 类型冲突，未检查 ATTACK/DEFENSE/SUPPORT（**设计意图误解**） | 与自动学习逻辑不一致 |
| 3 | `ManualSelectionDialog` | **缺少槽位上限过滤** | 槽位已满时 UI 仍显示可选功法，操作后静默失败 |
| 4 | `DiscipleManualManager.processAutoLearn` | **缺少槽位上限检查** + 保留同类型冲突逻辑 | 自动学习路径不受槽位限制 |

---

## 三、v2.5.17 修复（首次修复）

### 3.1 修复内容

#### (1) `GameEngine.learnManual` — 添加槽位上限 + 同类型冲突检查

```kotlin
// 新增：槽位上限检查
val maxSlots = DiscipleStatCalculator.getMaxManualSlots(disciple)
if (disciple.manualIds.size >= maxSlots) return@update

// 新增：通用同类型冲突检查（替换原来仅检查 MIND 的逻辑）
val hasSameType = disciple.manualIds.any { mid ->
    manualInstances.find { it.id == mid }?.type == stack.type
}
if (hasSameType) return@update
```

#### (2) `GameEngine.replaceManual` — 添加通用同类型冲突检查

```kotlin
val hasTypeConflict = disciple.manualIds
    .filter { it != oldInstanceId }
    .any { mid -> manualInstances.find { m -> m.id == mid }?.type == newStack.type }
if (hasTypeConflict) return@update
```

#### (3) `GameEngine.rewardItemsToDisciple` — 添加同类型冲突 + 槽位上限

```kotlin
val canLearn = GameConfig.Realm.meetsRealmRequirement(disciple.realm, stack.minRealm) &&
    !disciple.manualIds.any { mid ->
        manualInstances.find { m -> m.id == mid }?.type == stack.type
    } &&
    disciple.manualIds.size < DiscipleStatCalculator.getMaxManualSlots(disciple)
```

#### (4) `ManualSelectionDialog` — 添加槽位上限 + 同类型过滤

```kotlin
if (currentManualIds.size >= maxManualSlots) {
    emptyList()
} else {
    val existingTypes = currentManualIds.mapNotNull { mid -> allManuals.find { it.id == mid }?.type }.toSet()
    manualStacks.filter { stack ->
        stack.type !in existingTypes && ...
    }
}
```

#### (5) `DiscipleManualManager.processAutoLearn` — 移除 MIND 特殊分支

将 MIND 类型的特殊处理分支移除，统一由通用同类型替换逻辑处理。

#### (6) `DiscipleManualManager.canLearn` — 添加同类型冲突 + 槽位上限

```kotlin
val maxSlots = DiscipleStatCalculator.getMaxManualSlots(disciple)
if (disciple.manualIds.size >= maxSlots) return false
val hasSameType = disciple.manualIds.any { mid -> manualInstances[mid]?.type == stack.type }
return !hasSameType
```

### 3.2 版本信息
- versionCode: 2085
- versionName: "2.5.17"

---

## 四、v2.5.18 修正（设计意图纠正）

### 4.1 问题发现

用户反馈：**弟子允许学习同类型功法（心法除外），这是设计意图**。弟子仅不能学习**相同功法**。

v2.5.17 错误地添加了"同类型功法冲突检查"，违反了原始设计。

### 4.2 回滚内容

#### (1) `GameEngine.learnManual` — 回滚同类型冲突，保留槽位上限

```kotlin
// 保留：槽位上限检查
val maxSlots = DiscipleStatCalculator.getMaxManualSlots(disciple)
if (disciple.manualIds.size >= maxSlots) return@update

// 回滚：恢复仅检查 MIND 类型的逻辑
if (stack.type == ManualType.MIND) {
    val hasMind = disciple.manualIds.any { mid ->
        manualInstances.find { it.id == mid }?.type == ManualType.MIND
    }
    if (hasMind) return@update
}
```

#### (2) `GameEngine.replaceManual` — 回滚同类型冲突，保留 MIND 检查

```kotlin
// 回滚：恢复 MIND 类型 blocked 检查
val blocked = newStack.type == ManualType.MIND && oldInstance.type != ManualType.MIND && disciple.manualIds
    .filter { it != oldInstanceId }
    .any { mid -> manualInstances.find { m -> m.id == mid }?.type == ManualType.MIND }
if (blocked) return@update
```

#### (3) `GameEngine.rewardItemsToDisciple` — 回滚同类型冲突

```kotlin
val canLearn = GameConfig.Realm.meetsRealmRequirement(disciple.realm, stack.minRealm) &&
    disciple.manualIds.size < DiscipleStatCalculator.getMaxManualSlots(disciple) &&
    !(stack.type == ManualType.MIND && disciple.manualIds.any { mid ->
        manualInstances.find { m -> m.id == mid }?.type == ManualType.MIND
    })
```

#### (4) `ManualSelectionDialog` — 回滚同类型过滤，保留心法过滤

```kotlin
val hasMindManual = currentManualIds.any { mid -> allManuals.find { it.id == mid }?.type == ManualType.MIND }
manualStacks.filter { stack ->
    !(hasMindManual && stack.type == ManualType.MIND) && ...
}
```

#### (5) 功法替换 UI — 回滚同类型过滤

```kotlin
val hasMindManual = disciple.manualIds.any { mid ->
    allManuals.find { it.id == mid }?.type == ManualType.MIND
}
manualStacks.filter { stack ->
    !(hasMindManual && manual.type != ManualType.MIND && stack.type == ManualType.MIND) && ...
}
```

#### (6) `DiscipleManualManager.processAutoLearn` — 彻底重写

**原逻辑**：按类型判断是否可学习/替换，阻止同类型功法学习。

**新逻辑**：
- 心法（MIND）特殊处理：已有则高稀有度替换，无则学习
- 非心法：槽位未满直接学习，槽位已满则替换**品质最低**的功法

```kotlin
val maxSlots = DiscipleStatCalculator.getMaxManualSlots(disciple)

for (stack in availableStacks.sortedByDescending { it.rarity }) {
    if (stack.type == ManualType.MIND) {
        val existingMindId = currentManualIds.find { manualInstances[it]?.type == ManualType.MIND }
        if (existingMindId != null) {
            // 高稀有度替换已有心法
            val existingRarity = manualInstances[existingMindId]?.rarity ?: 0
            if (stack.rarity > existingRarity) {
                // ... tryReplaceManual
            }
        } else {
            // 无心法且槽位未满则学习
            if (currentManualIds.size < maxSlots) {
                // ... learnNewManual
            }
        }
        continue
    }

    // 非心法
    if (currentManualIds.size < maxSlots) {
        // 槽位未满：直接学习
        // ... learnNewManual
    } else {
        // 槽位已满：替换品质最低的功法
        val lowestRarityId = currentManualIds.minByOrNull { manualInstances[it]?.rarity ?: 0 }
        if (lowestRarityId != null) {
            val existingRarity = manualInstances[lowestRarityId]?.rarity ?: 0
            if (stack.rarity > existingRarity) {
                // ... tryReplaceManual
            }
        }
    }
}
```

#### (7) `DiscipleManualManager.canLearn` — 添加心法唯一性检查

```kotlin
fun canLearn(disciple: Disciple, stack: ManualStack, manualInstances: Map<String, ManualInstance>): Boolean {
    if (disciple.realm > stack.minRealm) return false
    val maxSlots = DiscipleStatCalculator.getMaxManualSlots(disciple)
    if (disciple.manualIds.size >= maxSlots) return false
    if (stack.type == ManualType.MIND && disciple.manualIds.any { mid -> manualInstances[mid]?.type == ManualType.MIND }) return false
    return true
}
```

### 4.3 最终设计规则

| 规则 | 说明 |
|------|------|
| 槽位上限 | 弟子最多学习 `maxManualSlots` 本功法（基础6 + 天赋加成） |
| 心法唯一 | MIND 类型功法仅限一本 |
| 同类型允许 | ATTACK/DEFENSE/SUPPORT 类型可重复学习 |
| 相同功法禁止 | 同一本功法实例不能重复学习（由 `manualIds.contains` 保证） |
| 自动替换 | 槽位已满时，自动学习会替换品质最低的功法；心法则替换已有心法 |

---

## 五、修改文件清单

| 文件 | 修改内容 |
|------|---------|
| `GameEngine.kt` | `learnManual` 添加槽位上限检查；`replaceManual` 保留 MIND 检查；`rewardItemsToDisciple` 添加槽位上限 |
| `DiscipleDetailScreen.kt` | `ManualSelectionDialog` 添加 `maxManualSlots` 参数和槽位上限过滤；功法替换 UI 保留心法过滤 |
| `DiscipleManualManager.kt` | `processAutoLearn` 重写为槽位上限逻辑；`canLearn` 添加槽位上限和心法检查 |
| `build.gradle` | versionCode 2085/2086, versionName "2.5.17"/"2.5.18" |
| `CHANGELOG.md` | 更新日志 |

---

## 六、经验教训

1. **审查时需确认设计意图**：v2.5.17 的错误源于未确认"同类型功法是否允许学习"这一设计意图，直接根据代码模式推断。
2. **自动学习路径容易被忽视**：`DiscipleManualManager.processAutoLearn` 是 CultivationService 每月调用的自动路径，与手动路径分离，需要单独审查。
3. **UI 过滤与后端逻辑需一致**：`ManualSelectionDialog` 的过滤条件必须与 `learnManual` 的校验逻辑保持一致。
