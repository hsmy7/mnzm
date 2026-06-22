# 弟子自动服用丹药重构方案

## 一、问题确认

### 1.1 你质疑的问题已确认存在

当前代码中，控制"突破自动用丹"的字段位于 [GameData.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/domain/src/main/java/com/xianxia/sect/core/model/GameData.kt#L342-L346)：

```kotlin
var breakthroughAutoPillFocused: Boolean = false,
var breakthroughAutoPillRootCounts: Set<Int> = emptySet(),
```

UI 入口在 [DiscipleManagementDialog.kt](file:///c:/Mnzm/XianxiaSectNative/android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/DiscipleManagementDialog.kt#L87-L96)，标题为"弟子突破时自动使用仓库中突破丹药"。**天枢殿的 [SectPoliciesDialog](file:///c:/Mnzm/XianxiaSectNative/android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/TianshuHallDialog.kt#L294-L381) 中没有这个开关。**

问题出在两处突破逻辑：

- [SettlementCoordinator.processBreakthroughForDisciple](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/domain/settlement/SettlementCoordinator.kt#L647-L684)
- [DiscipleBreakthroughHandler.processRealtimeBreakthroughs](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/service/DiscipleBreakthroughHandler.kt#L29-L121)

它们都先调用 `qualifiesForSectAutoPublic(..., breakthroughAutoPillFocused, ...)` 判断弟子是否符合"自动服用仓库丹"条件。如果不符合，直接跳过整个突破丹服用逻辑——**既不吃仓库突破丹，也不吃该弟子储物袋里的突破丹**，导致仓库和储物袋被混为一谈。

正确做法：仓库突破丹的开关只应决定是否从宗门仓库取药，不应拦截弟子自己储物袋里的突破丹。

### 1.2 当前储物袋自动服用的问题

[DisciplePillManager.processAutoUsePills](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/domain/disciple/DisciplePillManager.kt#L17-L60) 的现有逻辑：

- 不区分永久属性丹、临时属性丹、持续性增益丹、直接修为丹，统一处理。
- 仅按 `rarity` 降序服用，未按收益优先级排序。
- `PillEffects.activePillCategory` 只记录一个字符串，导致 CULTIVATION 类别下三种速度丹互相误拦截。
- 永久属性丹使用记录 `usedFunctionalPillTypes` 只记录 `pillType`，缺少品阶维度，且与延寿丹记录字段语义不一致。

## 二、重构目标

1. 仓库突破丹开关只控制宗门仓库，不再影响储物袋中的突破丹。
2. 储物袋自动服用遵循以下规则：
   - **永久基础属性丹**：按"品阶 + 效果字段"去重，每个弟子每个品阶每种效果字段只能服用一次；双属性丹任一字段冲突即不可服用。
   - **永久寿命丹**：按 `pillType` 去重，每个弟子每种寿命丹只能服用一次。
   - **直接修为/功法/孕养丹**（`cultivationAdd` / `skillExpAdd` / `nurtureAdd`）：可重复服用。
   - **持续性增益丹**（`cultivationSpeed` / `skillExpSpeed` / `nurtureSpeed`）：同 `pillType` 效果存续期间不可再服，效果不叠加。
   - **临时战斗属性丹**：保持临时效果，同 `pillType` 效果存续期间不可再服，效果不叠加。
   - **突破丹**：失败后可继续服用。
3. 统一并收敛效果应用层，消除手动服用与自动服用的逻辑分叉。
4. 兼容旧存档。

## 三、数据模型改动

### 3.1 `UsageTracking`（[DiscipleComponents.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/domain/src/main/java/com/xianxia/sect/core/model/DiscipleComponents.kt#L165-L171)）

当前：

```kotlin
var usedFunctionalPillTypes: List<String> = emptyList(),
var usedExtendLifePillIds: List<String> = emptyList(),
```

改为：

```kotlin
// 永久属性丹已服用的去重 key："${tier}#${effectField}"
// 示例："1#intelligence", "2#physicalAttack"
var usedPermanentPillKeys: Set<String> = emptySet(),

// 寿命丹按 pillType 去重（所有品阶共享）
var usedExtendLifePillTypes: Set<String> = emptySet(),
```

### 3.2 `PillEffects`（[DiscipleComponents.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/domain/src/main/java/com/xianxia/sect/core/model/DiscipleComponents.kt#L71-L86)）

当前只存 `activePillCategory: String`，导致 CULTIVATION 下三种速度丹互相误拦截。

改为：

```kotlin
// 当前生效中的临时/持续丹药效果，按 pillType 记录
var activePillTypes: Set<String> = emptySet(),

// 剩余效果时间（旬）
var pillEffectDuration: Int = 0,

// 原有具体数值字段保留
var pillPhysicalAttackBonus: Int = 0,
// ... 其他数值字段不变

// activePillCategory 废弃，保留字段仅用于旧存档反序列化
var activePillCategory: String = ""
```

### 3.3 `ItemEffect`（[Disciple.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/domain/src/main/java/com/xianxia/sect/core/model/Disciple.kt#L438-L477)）

增加 `tier` 字段，用于永久属性丹的去重：

```kotlin
val tier: Int = 0,
```

需要在 [DisciplePillManager.pillToItemEffect](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/domain/disciple/DisciplePillManager.kt#L207-L248) 中把 `Pill` 的品阶带下来。如果 `Pill` 当前没有显式 `tier`，可以通过 `rarity` 与品阶的映射关系推导。

## 四、丹药分类规则引擎

在 `DisciplePillManager` 中新增 `PillClassifier`：

```kotlin
enum class PillRule(val priority: Int) {
    PERMANENT_BASE_ATTR(4),  // 基础属性丹：按 tier+effectField 终身限一次
    PERMANENT_LIFE(4),       // 延寿丹：按 pillType 终身限一次
    PERMANENT_BATTLE(4),     // 永久战斗属性丹（预留，当前没有）
    INSTANT_CULTIVATION(3),  // cultivationAdd/skillExpAdd/nurtureAdd：可重复
    SUSTAINED_SPEED(2),      // cultivationSpeed/skillExpSpeed/nurtureSpeed：按 pillType 不可叠加
    TEMPORARY_BATTLE(1),     // 战斗属性丹：按 pillType 不可叠加
    BREAKTHROUGH(0)          // 突破丹：可重复，失败可再吃
}

fun classify(pill: StorageBagItem): PillRule = when (pill.effect?.pillType) {
    "extendLife" -> PERMANENT_LIFE
    "cultivationAdd", "skillExpAdd", "nurtureAdd" -> INSTANT_CULTIVATION
    "cultivationSpeed", "skillExpSpeed", "nurtureSpeed" -> SUSTAINED_SPEED
    "breakthrough" -> BREAKTHROUGH
    else -> {
        if (hasAnyBaseAttrAdd(pill.effect)) PERMANENT_BASE_ATTR
        else if (hasAnyBattleAttrAdd(pill.effect)) TEMPORARY_BATTLE
        else throw IllegalStateException("未定义规则的丹药：${pill.name}")
    }
}
```

## 五、服用资格检查

重构 `canUsePill`：

```kotlin
fun canUsePill(disciple: Disciple, pillItem: StorageBagItem): PillUseCheck {
    val effect = pillItem.effect ?: return no("无效果数据")
    if (!meetsRealm(disciple, effect.minRealm)) return no("境界不足")

    return when (classify(pillItem)) {
        PERMANENT_BASE_ATTR -> {
            val usedKeys = buildUsedKeys(effect, pillItem.tier)
            if (usedKeys.any { it in disciple.usage.usedPermanentPillKeys })
                return no("已服用过同类属性丹药")
            ok()
        }
        PERMANENT_LIFE -> {
            if (effect.pillType in disciple.usage.usedExtendLifePillTypes)
                return no("已服用过同类延寿丹药")
            ok()
        }
        SUSTAINED_SPEED, TEMPORARY_BATTLE -> {
            if (effect.pillType in disciple.pillEffects.activePillTypes)
                return no("同类型丹药效果生效中")
            ok()
        }
        INSTANT_CULTIVATION, BREAKTHROUGH -> ok()
    }
}
```

`buildUsedKeys` 根据 `ItemEffect` 中所有非零基础属性字段生成 `tier#field` 集合：

```kotlin
fun buildUsedKeys(effect: ItemEffect, tier: Int): Set<String> {
    val fields = mutableListOf<String>()
    if (effect.intelligenceAdd > 0) fields += "intelligence"
    if (effect.charmAdd > 0) fields += "charm"
    if (effect.loyaltyAdd > 0) fields += "loyalty"
    if (effect.comprehensionAdd > 0) fields += "comprehension"
    if (effect.artifactRefiningAdd > 0) fields += "artifactRefining"
    if (effect.pillRefiningAdd > 0) fields += "pillRefining"
    if (effect.spiritPlantingAdd > 0) fields += "spiritPlanting"
    if (effect.teachingAdd > 0) fields += "teaching"
    if (effect.moralityAdd > 0) fields += "morality"
    if (effect.miningAdd > 0) fields += "mining"
    return fields.map { "$tier#$it" }.toSet()
}
```

## 六、效果应用层收敛

当前效果应用有两套代码：

1. [DisciplePillManager.applyPillEffect](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/domain/disciple/DisciplePillManager.kt#L95-L205) —— 储物袋自动服用
2. [DiscipleFacadeImpl.applyPillEffectsToDisciple](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/domain/disciple/DiscipleFacadeImpl.kt#L368-L461) —— 手动/奖励服用

两套逻辑不完全一致，应收敛为 `PillEffectApplier.applyToDisciple(disciple, pill): Disciple`，两处统一调用。

应用后同步更新使用记录：

```kotlin
when (classify(pill)) {
    PERMANENT_BASE_ATTR -> {
        usage.usedPermanentPillKeys += buildUsedKeys(pill.effect, pill.tier)
    }
    PERMANENT_LIFE -> {
        usage.usedExtendLifePillTypes += pill.effect.pillType
    }
    SUSTAINED_SPEED, TEMPORARY_BATTLE -> {
        pillEffects.activePillTypes += pill.effect.pillType
        pillEffects.pillEffectDuration = maxOf(
            pillEffects.pillEffectDuration,
            pill.effect.duration // 统一以"旬"为单位
        )
    }
    else -> {}
}
```

## 七、储物袋自动服用逻辑重构

### 7.1 排序策略

[DisciplePillManager.processAutoUsePills](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/domain/disciple/DisciplePillManager.kt#L17-L60) 当前只按 `rarity` 降序。新规则下排序应保证：

1. 永久属性丹 / 延寿丹（终身收益）
2. 直接修为丹（无冲突）
3. 持续增益丹 / 临时战斗属性丹

同优先级内按品阶高优先：

```kotlin
val sortedPills = pillItems.sortedWith(
    compareByDescending<PillRule> { it.priority }
        .thenByDescending { it.rarity }
)
```

### 7.2 服用流程

```kotlin
fun processAutoUsePills(disciple: Disciple) {
    val pillItems = disciple.equipment.storageBagItems.filter { it.itemType == "pill" }
    val sorted = pillItems.sortedWith(ruleThenRarity)

    for (pillItem in sorted) {
        val check = canUsePill(disciple, pillItem)
        if (!check.ok) continue

        val result = PillEffectApplier.applyToDisciple(disciple, pillItem)
        // 扣除储物袋中丹药数量，数量为 0 时移除
    }
}
```

## 八、突破丹仓库/储物袋路径分离

改造 [SettlementCoordinator.processBreakthroughForDisciple](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/domain/settlement/SettlementCoordinator.kt#L647-L684) 和 [DiscipleBreakthroughHandler.processRealtimeBreakthroughs](file:///c:/Mnzm/XianxiaSectNative/android/core/service/DiscipleBreakthroughHandler.kt#L29-L121)：

```kotlin
// 1. 先尝试仓库突破丹（受弟子管理开关控制）
var pillBonus = 0.0
if (qualifiesForWarehouseAuto(disciple, focused, rootCounts)) {
    val warehousePill = findBestBreakthroughPill(state.pills, targetRealm)
    if (warehousePill != null) {
        consumeWarehousePill(...)
        pillBonus = warehousePill.effect.breakthroughChance
    }
}

// 2. 仓库没有则尝试储物袋突破丹（不受仓库开关控制）
if (pillBonus == 0.0) {
    val bagPill = findBestBreakthroughPill(disciple.equipment.storageBagItems, targetRealm)
    if (bagPill != null) {
        consumeBagPill(...)
        pillBonus = bagPill.effect.breakthroughChance
    }
}
```

关键点：`qualifiesForWarehouseAuto` 只决定是否查仓库，不拦截储物袋。

## 九、持续/临时效果的每旬衰减

当前 [CultivationCore.applyMonthlyDurationDecay](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/service/CultivationCore.kt#L229-L269) 和 [DisciplePillManager.applyPillEffect](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/domain/disciple/DisciplePillManager.kt#L110-L115) 中存在 `duration * 30` 的魔法数字换算。建议统一以"旬"为单位：

- `PillEffect.duration` 在数据表中即表示"持续旬数"，不再做 `*30`。
- 每旬 tick 时 `pillEffectDuration--`。
- 当 `pillEffectDuration <= 0` 时清空 `activePillTypes` 和所有临时数值加成。

## 十、旧存档迁移

旧存档已有 `usedFunctionalPillTypes` 和 `usedExtendLifePillIds`。启动时做一次迁移：

```kotlin
// 把旧 list 转成 set
usedExtendLifePillTypes = usedExtendLifePillIds.toSet()

// 旧 usedFunctionalPillTypes 只记录了 FUNCTIONAL 类的 pillType，没有 tier
// 无法精确恢复每个品阶的服用记录，保守处理：视为所有品阶都已服用过该类型
usedPermanentPillKeys = usedFunctionalPillTypes.flatMap { type ->
    (1..6).map { tier -> "$tier#$type" }
}.toSet()
```

这会导致老存档里吃过一种基础属性丹后，所有品阶同类型都不能再吃，是最安全的折中。

## 十一、涉及文件清单

| 文件 | 改动内容 |
|---|---|
| [DiscipleComponents.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/domain/src/main/java/com/xianxia/sect/core/model/DiscipleComponents.kt) | 改造 `UsageTracking`、`PillEffects` |
| [Disciple.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/domain/src/main/java/com/xianxia/sect/core/model/Disciple.kt) | `ItemEffect` 增加 `tier` |
| [DisciplePillManager.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/domain/disciple/DisciplePillManager.kt) | 新增 `PillRule`、`PillClassifier`、重构 `canUsePill` 和 `processAutoUsePills` |
| 新建 `PillEffectApplier.kt` | 统一效果应用层 |
| [DiscipleFacadeImpl.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/domain/disciple/DiscipleFacadeImpl.kt) | 改用手动/奖励服用调用 `PillEffectApplier` |
| [CultivationCore.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/service/CultivationCore.kt) | 统一以"旬"为单位衰减 |
| [SettlementCoordinator.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/domain/settlement/SettlementCoordinator.kt) | 分离仓库/储物袋突破丹路径 |
| [DiscipleBreakthroughHandler.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/service/DiscipleBreakthroughHandler.kt) | 分离仓库/储物袋突破丹路径 |
| 存档迁移入口 | 旧字段转新字段 |

## 十二、边界确认记录

以下边界问题已与需求方确认：

| 问题 | 确认结果 |
|---|---|
| 战斗属性丹是否改为永久？ | 不改，保持临时 |
| 永久属性丹去重维度？ | 品阶 + 效果字段；双属性丹任一字段冲突即不可服用 |
| 突破丹失败后可否再吃？ | 可以 |
| 回血回蓝/复活/清除类丹药？ | 当前没有，方案中不处理 |
| 持续增益与临时增加是否合并处理？ | 是，同一套"效果存续期间不可再服"机制 |
| 设置入口是否调整？ | 不调整，仓库开关只控制宗门仓库丹药 |
