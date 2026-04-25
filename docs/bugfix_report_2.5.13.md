# Bug修复报告 v2.5.13

## 基本信息

| 项目 | 内容 |
|------|------|
| 版本号 | 2.5.13 |
| 修复日期 | 2026-04-25 |
| Bug描述 | 宗门仓库中有多件相同装备时手动穿戴装备后弟子装备槽位不显示被穿戴装备 |
| 影响范围 | 装备穿戴、装备卸下、弟子装备槽位显示 |

---

## 问题现象

用户在宗门仓库中有多件相同装备（quantity > 1）时，通过弟子详情页的装备槽位手动选择装备进行穿戴，操作后弟子装备槽位不显示被穿戴的装备。但如果仓库中该装备只有一件（quantity == 1），则穿戴后正常显示。

---

## 根因分析

### 问题定位

问题根源位于 `DiscipleService.equipEquipment()` 方法中。

**文件**: `android/app/src/main/java/com/xianxia/sect/core/engine/service/DiscipleService.kt`

### 问题机制

`DiscipleService` 使用 property setter 模式管理状态：

```kotlin
private var currentDisciples: List<Disciple>
    set(value) {
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) { ts.disciples = value; return }
        scope.launch { stateStore.update { disciples = value } }  // 独立异步更新
    }
```

当没有活跃事务时，每次 setter 调用都会 `scope.launch` 一个独立的 `stateStore.update {}` 异步事务。

**原 `equipEquipment` 的执行流程**：

1. 调用 `unequipEquipment()` 卸下旧装备（产生 2-3 个独立异步更新）
2. 修改 `currentEquipmentStacks`（异步更新 #1）
3. 修改 `currentEquipmentInstances`（异步更新 #2）
4. 修改 `currentDisciples`（异步更新 #3）

一次装备操作总共产生 **5-6 个独立的异步事务**，它们之间可以交错执行。当 quantity > 1 时，步骤 2 使用 `map` 修改堆叠数量（而非 `filter` 删除），导致后续的状态读取可能获取到中间状态，最终弟子装备槽位 ID 被某个后执行的 `unequipEquipment` 更新覆盖回空值。

### 为什么 quantity == 1 时正常

当 quantity == 1 时，步骤 2 使用 `filter` 直接删除整个堆叠条目，操作更简单，竞态条件触发的概率大幅降低。但本质上仍然存在竞态风险。

---

## 修复方案

### 核心策略

将整个 `equipEquipment` 的状态修改部分包裹在单个 `stateStore.update {}` 原子事务中。事务内 property setter 检测到活跃事务后直接写入 `MutableGameState`，不再 launch 独立异步更新。

### 具体修改

#### 1. DiscipleService.kt - 重写 equipEquipment 方法

**修改前**：

```kotlin
fun equipEquipment(discipleId: String, equipmentId: String): Boolean {
    val discipleIndex = currentDisciples.indexOfFirst { it.id == discipleId }
    // ... 前置验证 ...

    val slot = equipmentInstance?.slot ?: equipmentStack!!.slot
    when (slot) {
        EquipmentSlot.WEAPON -> disciple.equipment.weaponId.takeIf { it.isNotEmpty() }?.let { unequipEquipment(discipleId, it) }
        // ... 其他槽位 ...
    }

    val currentDisciple = currentDisciples[discipleIndex]

    if (equipmentStack != null) {
        currentEquipmentStacks = currentEquipmentStacks.map { ... }  // 异步更新 #1
        currentEquipmentInstances = currentEquipmentInstances + equippedItem  // 异步更新 #2
        currentDisciples = currentDisciples.toMutableList().also { ... }  // 异步更新 #3
    }
    // ...
}
```

**修改后**：

```kotlin
fun equipEquipment(discipleId: String, equipmentId: String): Boolean {
    val disciple = currentDisciples.find { it.id == discipleId } ?: return false
    // ... 前置验证（在事务外进行，避免无效事务）...

    val slot = equipmentInstance?.slot ?: equipmentStack!!.slot
    val equipName = equipmentStack?.name ?: equipmentInstance?.name ?: ""

    scope.launch {
        stateStore.update {
            val idx = currentDisciples.indexOfFirst { it.id == discipleId }
            if (idx < 0) return@update
            val d = currentDisciples[idx]

            // 在事务内卸下旧装备
            val oldEquipId = when (slot) {
                EquipmentSlot.WEAPON -> d.equipment.weaponId
                EquipmentSlot.ARMOR -> d.equipment.armorId
                EquipmentSlot.BOOTS -> d.equipment.bootsId
                EquipmentSlot.ACCESSORY -> d.equipment.accessoryId
                else -> ""
            }
            if (oldEquipId.isNotEmpty()) {
                unequipEquipment(discipleId, oldEquipId)  // 事务内调用，setter直接写入
            }

            // 在事务内重新读取最新状态
            val currentDisciple = currentDisciples[idx]
            val stack = currentEquipmentStacks.find { it.id == equipmentId }
            val instance = currentEquipmentInstances.find { it.id == equipmentId }

            if (stack != null) {
                val equippedId = UUID.randomUUID().toString()
                val equippedItem = stack.toInstance(id = equippedId, ownerId = discipleId, isEquipped = true)
                if (stack.quantity > 1) {
                    currentEquipmentStacks = currentEquipmentStacks.map { ... }
                } else {
                    currentEquipmentStacks = currentEquipmentStacks.filter { it.id != equipmentId }
                }
                currentEquipmentInstances = currentEquipmentInstances + equippedItem
                val updatedDisciple = when (slot) { ... }
                currentDisciples = currentDisciples.toMutableList().also { it[idx] = updatedDisciple }
            } else if (instance != null) {
                val updatedDisciple = when (slot) { ... }
                currentDisciples = currentDisciples.toMutableList().also { it[idx] = updatedDisciple }
                currentEquipmentInstances = currentEquipmentInstances.map { ... }
            }

            eventService.addGameEvent("${d.name} 装备了 $equipName", EventType.INFO)
        }
    }
    return true
}
```

**关键改进点**：
- 所有状态修改在单个 `stateStore.update {}` 事务中原子执行
- `unequipEquipment` 在事务内被调用，其 property setter 直接写入事务状态
- 事务内重新查找 disciple 和 equipment，获取最新状态
- 事件消息在事务内添加，确保与状态变更的原子性

#### 2. 修复 isEquipped 检查不充分

**修改前**：

```kotlin
if (equipmentInstance.isEquipped && equipmentInstance.ownerId != null && equipmentInstance.ownerId != discipleId) {
    // 只处理了装备在其他弟子身上的情况
}
```

**修改后**：

```kotlin
if (equipmentInstance.isEquipped) {
    if (equipmentInstance.ownerId == discipleId) return false  // 已装备在同一弟子身上
    eventService.addGameEvent("...", EventType.WARNING)
    return false
}
```

#### 3. GameEngine.kt - 修复 unequipItem 传入 slot.name 的 bug

**修改前**：

```kotlin
fun unequipItem(discipleId: String, slot: EquipmentSlot) {
    discipleService.unequipEquipment(discipleId, equipmentId = slot.name)  // "WEAPON" 不是 UUID
}
```

**修改后**：

```kotlin
fun unequipItem(discipleId: String, slot: EquipmentSlot) {
    val disciple = getDiscipleById(discipleId) ?: return
    val equipId = when (slot) {
        EquipmentSlot.WEAPON -> disciple.equipment.weaponId
        EquipmentSlot.ARMOR -> disciple.equipment.armorId
        EquipmentSlot.BOOTS -> disciple.equipment.bootsId
        EquipmentSlot.ACCESSORY -> disciple.equipment.accessoryId
    }
    if (equipId.isNotEmpty()) {
        discipleService.unequipEquipment(discipleId, equipId)
    }
}
```

#### 4. DiscipleService.kt - 修复 bagStackIds 搜索范围

**修改前**：

```kotlin
val bagStackIds = currentDisciples.flatMap { it.equipment.storageBagItems }
    .filter { it.itemType == "equipment_stack" }
    .map { it.itemId }
    .toSet()  // 搜索所有弟子的储物袋
```

**修改后**：

```kotlin
val bagStackIds = updatedDisciple.equipment.storageBagItems
    .filter { it.itemType == "equipment_stack" }
    .map { it.itemId }
    .toSet()  // 只搜索当前弟子的储物袋
```

---

## 修改文件清单

| 文件路径 | 修改类型 | 修改内容 |
|----------|----------|----------|
| `android/app/build.gradle` | 修改 | versionCode: 2080→2081, versionName: "2.5.12"→"2.5.13" |
| `android/app/src/main/java/com/xianxia/sect/core/engine/service/DiscipleService.kt` | 修改 | 重写 `equipEquipment` 为原子事务模式；修复 `isEquipped` 检查；修复 `bagStackIds` 搜索范围 |
| `android/app/src/main/java/com/xianxia/sect/core/engine/GameEngine.kt` | 修改 | 修复 `unequipItem` 传入 `slot.name` 的 bug |
| `CHANGELOG.md` | 修改 | 添加 v2.5.13 更新日志 |

---

## 测试验证

### 构建验证

```bash
./gradlew compileDebugKotlin
```

**结果**: ✅ 构建成功，无编译错误

### 功能验证要点

1. **多件相同装备穿戴**：仓库中有 3 件相同装备，给弟子手动穿戴 1 件，槽位应正确显示
2. **单件装备穿戴**：仓库中只有 1 件装备，给弟子手动穿戴，槽位应正确显示
3. **替换装备**：弟子已装备 A 装备，手动替换为 B 装备，旧装备应卸下放入储物袋，新装备正确显示
4. **重复装备检测**：已装备在其他弟子身上的装备，尝试穿戴时应提示失败
5. **同弟子重复穿戴**：同一弟子已装备某实例，再次尝试穿戴时应静默返回
6. **按槽位卸装**：点击装备槽位上的已装备物品进行卸下，应正确执行
7. **卸下装备归属**：卸下装备应只合并到当前弟子的储物袋中，不影响其他弟子

---

## 兼容性说明

### 数据库迁移

本次修改不涉及数据库 schema 变更，无需数据库迁移。

### 旧存档兼容

- 存档数据格式未改变
- 游戏状态通过 `StateStore` 管理，修复后的代码与旧存档完全兼容
- 装备/实例的 ID 格式未改变

---

## 后续建议

1. **代码审查**：建议对 `DiscipleService` 中其他修改状态的方法进行审查，确认是否存在类似的竞态条件风险
2. **单元测试**：为 `equipEquipment` 和 `unequipEquipment` 添加单元测试，覆盖多件装备、单件装备、替换装备、重复装备等场景
3. **UI 反馈**：装备操作成功后，考虑在 UI 上增加更明显的反馈（如装备图标闪烁、属性变化提示等）

---

## 提交记录

```
commit 7a5e151
Author: AI Assistant
Date:   2026-04-25

    v2.5.13: 修复装备穿戴竞态条件bug及多个相关问题

    修复内容：
    - equipEquipment改为在单个stateStore.update原子事务中执行所有状态更新
    - 修复equipEquipment中equipmentInstance已装备在同一弟子身上时未正确处理
    - 修复GameEngine.unequipItem传入slot.name作为equipmentId导致按槽位卸装失效
    - 修复unequipEquipment中bagStackIds搜索所有弟子储物袋的问题
    - 版本号更新至2.5.13
```

---

## 附录

### 相关代码链接

- [DiscipleService.kt - equipEquipment](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/service/DiscipleService.kt#L632)
- [DiscipleService.kt - unequipEquipment](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/service/DiscipleService.kt#L723)
- [GameEngine.kt - unequipItem](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/GameEngine.kt#L1388)
- [GameStateStore.kt - update](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/state/GameStateStore.kt#L187)
