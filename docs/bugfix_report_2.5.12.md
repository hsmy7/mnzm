# Bug修复报告 v2.5.12

## 基本信息

| 项目 | 内容 |
|------|------|
| 版本号 | 2.5.12 |
| 修复日期 | 2026-04-25 |
| Bug描述 | 宗门仓库一键出售需要两次才能出售干净 |
| 影响范围 | 一键出售、单个物品出售、上架到商人 |

---

## 问题现象

用户在宗门仓库使用"一键出售"功能时，点击出售后并未完全出售被选中的物品，需要再次点击出售才能出售干净。单个物品出售和上架到商人功能也存在同样的问题。

---

## 根因分析

### 问题定位

问题根源位于 `InventorySystem` 类的移除方法中。以 `removeEquipment` 为例：

**文件**: `android/app/src/main/java/com/xianxia/sect/core/engine/system/InventorySystem.kt`

```kotlin
fun removeEquipment(equipmentId: String, quantity: Int): Boolean {
    var removed = false
    val ts = stateStore.currentTransactionMutableState()
    
    if (ts != null) {
        // 同步路径：在事务内执行
        val currentStacks = ts.equipmentStacks
        val stack = currentStacks.find { it.id == equipmentId }
        if (stack != null && !stack.isLocked && quantity in 1..stack.quantity) {
            ts.equipmentStacks = currentStacks.mapNotNull { ... }
            removed = true
        }
    } else {
        // 异步路径：使用 scope.launch
        scope.launch {
            stateStore.update {
                val currentStacks = equipmentStacks
                val stack = currentStacks.find { it.id == equipmentId }
                if (stack != null && !stack.isLocked && quantity in 1..stack.quantity) {
                    equipmentStacks = currentStacks.mapNotNull { ... }
                    removed = true  // 在协程内赋值，外部无法感知
                }
            }
        }
    }
    
    return removed  // 异步路径下始终返回 false
}
```

### 问题机制

1. **异步执行但立即返回**：当没有活跃事务时，`removeXxx` 方法使用 `scope.launch` 异步执行状态更新，但函数立即返回 `removed = false`
2. **连锁反应**：
   - `GameEngine.sellXxx()` 调用 `inventorySystem.removeXxx()` 得到 `false`
   - 判断 `!removed` 为 `true`，提前返回 `false`
   - `addSpiritStones()` 未被调用，灵石未添加
   - 物品虽被异步移除，但出售报告为失败

### 影响的方法

| 类 | 方法 | 影响功能 |
|----|------|----------|
| `InventorySystem` | `removeEquipment` | 装备出售/上架 |
| `InventorySystem` | `removeManual` | 功法出售/上架 |
| `InventorySystem` | `removePill` | 丹药出售/上架 |
| `InventorySystem` | `removeMaterial` | 材料出售/上架 |
| `InventorySystem` | `removeHerb` | 草药出售/上架 |
| `InventorySystem` | `removeSeed` | 种子出售/上架 |
| `GameEngine` | `sellXxx` (6个) | 单个物品出售 |
| `GameEngine` | `listItemsToMerchant` | 上架到商人 |

---

## 修复方案

### 核心策略

绕过 `InventorySystem.removeXxx` 的异步返回值问题，直接在 `GameEngine` 中使用 `stateStore.update` 事务同步执行所有操作。

### 具体修改

#### 1. GameEngine.kt - 新增批量出售方法

```kotlin
// 新增数据类
data class BulkSellOperation(
    val id: String,
    val name: String,
    val quantity: Int,
    val itemType: String
)

data class BulkSellResult(
    val soldCount: Int,
    val totalEarned: Long,
    val soldItemNames: List<String>,
    val failedItemNames: List<String>  // 新增：记录失败的物品
)

// 新增：批量出售方法（在单个事务中完成）
suspend fun bulkSellItems(operations: List<BulkSellOperation>): BulkSellResult {
    var totalEarned = 0L
    var soldCount = 0
    val soldItemNames = mutableListOf<String>()
    val failedItemNames = mutableListOf<String>()

    stateStore.update {
        for (op in operations) {
            var sold = false
            when (op.itemType) {
                "equipment" -> {
                    val stack = equipmentStacks.find { it.id == op.id }
                    if (stack != null && !stack.isLocked && op.quantity in 1..stack.quantity) {
                        // 更新装备列表
                        equipmentStacks = equipmentStacks.mapNotNull { ... }
                        totalEarned += GameConfig.Rarity.calculateSellPrice(stack.basePrice, op.quantity)
                        soldCount++
                        sold = true
                    }
                }
                // ... 其他5种物品类型的处理
            }
            if (sold) {
                soldItemNames.add(...)
            } else {
                failedItemNames.add(op.name)  // 记录失败
            }
        }
        // 一次性更新灵石
        if (totalEarned > 0) {
            gameData = gameData.copy(spiritStones = gameData.spiritStones + totalEarned)
        }
    }

    return BulkSellResult(soldCount, totalEarned, soldItemNames, failedItemNames)
}
```

#### 2. GameEngine.kt - 重写单个出售方法

将6个 `sellXxx` 方法改为 `suspend` 函数，在 `stateStore.update` 事务中执行：

```kotlin
// 修改前
fun sellEquipment(equipmentId: String, quantity: Int = 1): Boolean {
    val stack = stateStore.equipmentStacks.value.find { it.id == equipmentId } ?: return false
    if (stack.isLocked) return false
    if (quantity < 1 || quantity > stack.quantity) return false
    if (!inventorySystem.removeEquipment(equipmentId, quantity)) return false  // 问题所在
    addSpiritStones(GameConfig.Rarity.calculateSellPrice(stack.basePrice, quantity))
    return true
}

// 修改后
suspend fun sellEquipment(equipmentId: String, quantity: Int = 1): Boolean {
    var success = false
    stateStore.update {
        val stack = equipmentStacks.find { it.id == equipmentId }
        if (stack != null && !stack.isLocked && quantity in 1..stack.quantity) {
            equipmentStacks = equipmentStacks.mapNotNull { ... }
            gameData = gameData.copy(
                spiritStones = gameData.spiritStones + GameConfig.Rarity.calculateSellPrice(stack.basePrice, quantity)
            )
            success = true
        }
    }
    return success
}
```

同理修改：`sellManual`、`sellPill`、`sellMaterial`、`sellHerb`、`sellSeed`

#### 3. GameEngine.kt - 重写上架到商人方法

```kotlin
suspend fun listItemsToMerchant(items: List<Pair<String, Int>>) {
    val newItems = mutableListOf<MerchantItem>()
    stateStore.update {
        items.forEach { (itemId, quantity) ->
            // 直接在事务中查找并移除物品
            val eqStack = equipmentStacks.find { it.id == itemId }
            if (eqStack != null && !eqStack.isLocked && quantity in 1..eqStack.quantity) {
                equipmentStacks = equipmentStacks.mapNotNull { ... }
                newItems.add(MerchantItem(...))
                return@forEach
            }
            // ... 其他物品类型的处理
        }
        if (newItems.isNotEmpty()) {
            gameData = gameData.copy(playerListedItems = gameData.playerListedItems + newItems)
        }
    }
}
```

#### 4. GameViewModel.kt - 适配调用层

```kotlin
// sellItem 改为协程调用
fun sellItem(itemId: String, itemType: String, quantity: Int) {
    viewModelScope.launch {
        val result = when (itemType) {
            "equipment" -> gameEngine.sellEquipment(itemId, quantity)
            // ...
        }
        if (!result) {
            _errorMessage.value = "售卖失败，物品可能已被锁定或不存在"
        }
    }
}

// bulkSellItems 使用新方法
fun bulkSellItems(selectedRarities: Set<Int>, selectedTypes: Set<String>) {
    viewModelScope.launch {
        // ... 构建 operations 列表
        val result = gameEngine.bulkSellItems(operations)
        if (result.soldCount > 0) {
            val msg = buildString {
                append("成功出售 ${result.soldCount} 件物品，获得 ${result.totalEarned} 灵石")
                if (result.failedItemNames.isNotEmpty()) {
                    append("\n以下物品出售失败：${result.failedItemNames.joinToString("、")}")
                }
            }
            _successMessage.value = msg
        }
    }
}
```

#### 5. MainGameScreen.kt - UI适配

```kotlin
// 修改前
onConfirm = { quantity ->
    val success = viewModel.sellItem(itemId, itemType, quantity)
    if (success) {  // 依赖返回值
        showSellDialog = false
        ...
    }
}

// 修改后
onConfirm = { quantity ->
    viewModel.sellItem(itemId, itemType, quantity)
    showSellDialog = false  // 直接关闭，通过消息提示结果
    ...
}
```

---

## 修改文件清单

| 文件路径 | 修改类型 | 修改内容 |
|----------|----------|----------|
| `android/app/build.gradle` | 修改 | versionCode: 2079→2080, versionName: "2.5.11"→"2.5.12" |
| `android/app/src/main/java/com/xianxia/sect/core/engine/GameEngine.kt` | 修改 | 新增 `bulkSellItems`、`BulkSellOperation`、`BulkSellResult`；重写6个 `sellXxx` 方法；重写 `listItemsToMerchant` |
| `android/app/src/main/java/com/xianxia/sect/ui/game/GameViewModel.kt` | 修改 | 重写 `bulkSellItems`；修改 `sellItem` 为协程调用；删除 `SuspendableSellOperation` |
| `android/app/src/main/java/com/xianxia/sect/ui/game/MainGameScreen.kt` | 修改 | 修改出售确认回调，不再依赖返回值 |
| `CHANGELOG.md` | 修改 | 添加 v2.5.12 更新日志 |

---

## 测试验证

### 构建验证

```bash
./gradlew compileDebugKotlin
```

**结果**: ✅ 构建成功，无编译错误

### 功能验证要点

1. **一键出售**：选择多种物品一键出售，应一次性全部出售成功，灵石正确增加
2. **单个出售**：点击单个物品出售，应正确移除物品并增加灵石
3. **上架到商人**：选择物品上架，应正确从仓库移除并创建商人商品
4. **失败处理**：锁定物品或数量不足时，应正确提示失败
5. **边界情况**：
   - 出售数量为0或负数
   - 出售数量超过拥有数量
   - 物品在操作过程中被其他逻辑移除
   - 混合出售（部分成功部分失败）

---

## 兼容性说明

### 数据库迁移

本次修改不涉及数据库 schema 变更，无需数据库迁移。

### 旧存档兼容

- 存档数据格式未改变
- 游戏状态通过 `StateStore` 管理，修复后的代码与旧存档完全兼容

---

## 后续建议

1. **InventorySystem 重构**：考虑统一 `removeXxx` 方法的返回值处理，或完全移除异步路径，强制使用事务
2. **单元测试**：为 `bulkSellItems` 和 `sellXxx` 方法添加单元测试，覆盖成功、失败、部分成功等场景
3. **代码审查**：建议对 `InventorySystem` 中其他类似模式的方法进行审查，确认是否存在同样问题

---

## 提交记录

```
commit 52757af
Author: AI Assistant
Date:   2026-04-25

    fix: 修复宗门仓库一键出售需要两次才能出售干净的bug (v2.5.12)
    
    根因：InventorySystem.removeXxx方法在无活跃事务时使用scope.launch异步执行，
    但立即返回removed=false，导致sellXxx返回false，灵石未添加，物品虽被异步移除但出售报告为失败。
    
    修复内容：
    - GameEngine新增bulkSellItems方法，在单个stateStore.update事务中完成所有出售操作
    - 修复sellXxx六个方法，改为使用stateStore.update事务模式，确保同步执行
    - 修复listItemsToMerchant方法，同样使用事务模式
    - ViewModel的sellItem改为协程调用，BulkSellResult增加失败物品信息
    - 版本号更新至2.5.12

commit 4913f0c
Author: AI Assistant
Date:   2026-04-25

    docs: 更新2.5.12版本更新日志
```

---

## 附录

### 相关代码链接

- [InventorySystem.kt - removeEquipment](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/system/InventorySystem.kt#L415)
- [GameEngine.kt - bulkSellItems](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/GameEngine.kt#L2123)
- [GameEngine.kt - sellEquipment](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/GameEngine.kt#L1969)
- [GameViewModel.kt - bulkSellItems](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/ui/game/GameViewModel.kt#L702)
