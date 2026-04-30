# 设计方案：弟子自动穿戴宗门仓库装备 & 自动学习宗门仓库功法

> 日期：2026-05-01
> 版本：v1.1

---

## 一、需求概述

1. 在弟子信息界面**装备栏**标题右侧新增「自动穿戴仓库装备」圆形勾选框
2. 在弟子信息界面**功法栏**标题右侧新增「自动学习仓库功法」圆形勾选框
3. 勾选后，弟子每日检测宗门仓库，自动穿戴/学习符合条件的物品
4. 自动穿戴受装备**境界限制**
5. 自动穿戴**优先高品阶**装备
6. 自动学习功法受功法**境界限制**
7. 弟子**不可学习同名功法**
8. 弟子**只能学习一本心法**
9. 自动学习**优先高品阶**功法
10. **锁定物品不可自动穿戴/学习**
11. **仅检测空闲槽位**，不可替换已有装备/功法
12. **两个勾选框默认不勾选**
13. **多弟子竞争优先级**：已关注弟子 > 未关注弟子；同关注状态中高境界弟子 > 低境界弟子

---

## 二、调查结论

### 2.1 宗门仓库数据源

项目存在两套仓库体系：

| 体系 | 数据结构 | UI 可见 | 用途 |
|---|---|---|---|
| **主仓库** | `GameStateStore.equipmentStacks` / `manualStacks`（Room 表 `equipment_stacks` / `manual_stacks`） | 是（仓库页展示） | 装备/功法的完整存储，含全部属性 |
| **SectWarehouse** | `SectDetail.warehouse: SectWarehouse`（内含 `List<WarehouseItem>`） | 否 | 宗门战争专用简化存储，仅 name/rarity/quantity |

**新功能基于主仓库体系**。`WarehouseItem` 缺少装备/功法的完整属性（攻击、防御、技能等），无法用于自动穿戴/学习。

### 2.2 现有自动逻辑的数据范围

`DiscipleEquipmentManager.processAutoEquip` 和 `DiscipleManualManager.processAutoLearn` 仅从**弟子储物袋引用**（`storageBagItems` 中 `itemType == "equipment_stack"/"manual_stack"` 的条目）中查找可穿戴/学习的物品，**不扫描仓库中未被任何弟子持有的空闲 Stack**。

### 2.3 仓库空闲物品的判定

`GameViewModel` 中已有现成逻辑：过滤掉被弟子储物袋引用的 Stack ID，只展示"空闲"物品。引擎层需要同样的过滤机制。

### 2.4 isLocked 现状

| 场景 | 是否检查 isLocked |
|---|---|
| 出售（单个/批量/上架商人） | 检查，锁定不可出售 |
| 现有 processAutoEquip | **不检查** |
| 现有 processAutoLearn | **不检查** |
| 手动装备/学习对话框 | **不检查**（仅 UI 展示锁定标记） |
| 赏赐给弟子 | **不检查** |

**新功能尊重锁定**：锁定的仓库物品不可被自动穿戴/学习。

### 2.5 关注状态

弟子"关注"状态存储在 `statusData["followed"] == "true"` 中，通过扩展属性 `DiscipleAggregate.isFollowed` 访问（定义在 `DiscipleUtils.kt`）。已有排序工具方法 `sortedByFollowAndRealm()`：先按关注降序，再按境界升序（realm 值越小境界越高），再按境界层数降序。

---

## 三、数据层变更

### 3.1 新增弟子字段

| 字段 | 类型 | 默认值 | 存储位置 | 说明 |
|---|---|---|---|---|
| `autoEquipFromWarehouse` | `Boolean` | `false` | `EquipmentSet`（@Embedded）+ `DiscipleEquipment`（分表） | 自动穿戴仓库装备开关 |
| `autoLearnFromWarehouse` | `Boolean` | `false` | `Disciple` 主表 + `DiscipleExtended`（分表） | 自动学习仓库功法开关 |

### 3.2 数据库迁移（v21 → v22）

```sql
ALTER TABLE disciples ADD COLUMN autoLearnFromWarehouse INTEGER NOT NULL DEFAULT 0;
ALTER TABLE disciples_equipment ADD COLUMN autoEquipFromWarehouse INTEGER NOT NULL DEFAULT 0;
ALTER TABLE disciples_extended ADD COLUMN autoLearnFromWarehouse INTEGER NOT NULL DEFAULT 0;
```

### 3.3 需修改的模型文件

| 文件 | 修改内容 |
|---|---|
| `DiscipleComponents.kt` | `EquipmentSet` 新增 `var autoEquipFromWarehouse: Boolean = false` |
| `Disciple.kt` | 新增 `var autoLearnFromWarehouse: Boolean = false`，更新 `copyWith` |
| `DiscipleEquipment.kt` | 分表新增 `var autoEquipFromWarehouse: Boolean = false`，更新 `fromDisciple` |
| `DiscipleExtended.kt` | 分表新增 `var autoLearnFromWarehouse: Boolean = false`，更新 `fromDisciple` |
| `DiscipleAggregate.kt` | 暴露两个新字段的计算属性 |
| `GameDatabase.kt` | version 21→22，新增 `MIGRATION_21_22` |

---

## 四、业务逻辑层

### 4.1 DiscipleEquipmentManager 新增方法

```kotlin
fun processAutoEquipFromWarehouse(
    disciple: Disciple,
    warehouseStacks: List<EquipmentStack>,
    equipmentInstances: Map<String, EquipmentInstance>,
    gameYear: Int,
    gameMonth: Int,
    gameDay: Int,
    maxStack: Int = MAX_EQUIPMENT_STACK
): EquipmentProcessResult
```

**逻辑流程**：

```
对每个装备槽位 (WEAPON / ARMOR / BOOTS / ACCESSORY):
  1. 检查当前槽位是否为空 (weaponId/armorId/bootsId/accessoryId 是否为空字符串)
     - 如果槽位已有装备 → 跳过（不可替换）
  2. 从 warehouseStacks 中筛选:
     - slot == 当前槽位类型
     - disciple.realm <= stack.minRealm  (境界限制)
     - !stack.isLocked                   (锁定物品不可自动穿戴)
  3. 从筛选结果中取 rarity 最高的 Stack
  4. 如果找到:
     a. 新 Stack → quantity-1
        - 若 quantity 归零 → 从 warehouseStacks 中移除
        - 否则 → 更新 quantity
     b. stack.toInstance() 创建新 Instance 装备到弟子
     c. 更新弟子槽位 ID
     d. 记录事件: "{弟子名} 自动装备了 {装备名}"
```

**与现有 `processAutoEquip` 的区别**：

| 维度 | processAutoEquip（现有） | processAutoEquipFromWarehouse（新增） |
|---|---|---|
| 数据源 | 弟子储物袋引用 | 仓库空闲 Stack |
| isLocked | 不检查 | **检查，锁定不可自动穿戴** |
| 替换逻辑 | 可替换低品阶装备 | **不可替换，仅填充空槽位** |
| 旧装备去向 | 放入弟子储物袋 | **不涉及**（无替换则无旧装备） |
| 储物袋操作 | 涉及增减 StorageBagItem | **不涉及** |

### 4.2 DiscipleManualManager 新增方法

```kotlin
fun processAutoLearnFromWarehouse(
    disciple: Disciple,
    warehouseStacks: List<ManualStack>,
    manualInstances: Map<String, ManualInstance>,
    gameYear: Int,
    gameMonth: Int,
    gameDay: Int,
    maxStack: Int = MAX_MANUAL_STACK
): ManualLearnResult
```

**逻辑流程**：

```
1. 检查功法槽位是否已满 (manualIds.size >= maxSlots)
   - 如果已满 → 直接返回（不可替换已有功法）
2. 从 warehouseStacks 中筛选:
   - disciple.realm <= stack.minRealm  (境界限制)
   - !stack.isLocked                   (锁定物品不可自动学习)
   - stack.name !in learnedNames        (同名功法不可重复学习)
3. 如果已有心法 → 排除仓库中的心法 (ManualType.MIND)
4. 按 rarity 降序排列
5. 取最高品阶的可学习 Stack
6. 如果找到:
   a. 新 Stack → quantity-1
   b. stack.toInstance() 创建新 ManualInstance
   c. 更新弟子 manualIds
   d. 记录事件: "{弟子名} 自动学习了 {功法名}"
```

**与现有 `processAutoLearn` 的区别**：

| 维度 | processAutoLearn（现有） | processAutoLearnFromWarehouse（新增） |
|---|---|---|
| 数据源 | 弟子储物袋引用 | 仓库空闲 Stack |
| isLocked | 不检查 | **检查，锁定不可自动学习** |
| 替换逻辑 | 可替换低品阶功法 | **不可替换，仅填充空槽位** |
| 旧功法去向 | 放入弟子储物袋 | **不涉及**（无替换则无旧功法） |
| 储物袋操作 | 涉及增减 StorageBagItem | **不涉及** |

### 4.3 CultivationService 集成

在 `processAutoUseItems()` 方法中，在现有储物袋自动逻辑之后新增仓库自动逻辑。

#### 4.3.1 多弟子竞争优先级

当多个弟子开启自动穿戴/学习且竞争同一仓库物品时，按以下优先级排序处理：

1. **已关注弟子 > 未关注弟子**（`statusData["followed"] == "true"`）
2. **同关注状态中高境界 > 低境界**（`realm` 值越小境界越高，所以按 `realm` 升序）
3. **同境界按层数降序**（`realmLayer` 越大越优先）

这与现有 `DiscipleUtils.sortedByFollowAndRealm()` 排序逻辑一致。

#### 4.3.2 实现方式

使用 `fold` 替代 `map`，在每次迭代后同步更新仓库栈列表：

```kotlin
data class AutoProcessState(
    val disciples: List<Disciple>,
    val equipmentStacks: List<EquipmentStack>,
    val equipmentInstances: List<EquipmentInstance>,
    val manualStacks: List<ManualStack>,
    val manualInstances: List<ManualInstance>,
    val events: List<String>
)

val initialState = AutoProcessState(
    disciples = emptyList(),
    equipmentStacks = currentEquipmentStacks,
    equipmentInstances = currentEquipmentInstances,
    manualStacks = currentManualStacks,
    manualInstances = currentManualInstances,
    events = emptyList()
)

// 按优先级排序：已关注 > 未关注，高境界 > 低境界
val sortedDisciples = currentDisciples
    .filter { it.isAlive }
    .sortedWith(
        compareByDescending<Disciple> { it.statusData["followed"] == "true" }
            .thenBy { it.realm }
            .thenByDescending { it.realmLayer }
    )

val finalState = sortedDisciples.fold(initialState) { state, disciple ->
    var updatedDisciple = disciple
    var eqStacks = state.equipmentStacks
    var eqInstances = state.equipmentInstances
    var mnStacks = state.manualStacks
    var mnInstances = state.manualInstances
    val events = state.events.toMutableList()

    // 计算仓库空闲 Stack（排除被储物袋引用的）
    val bagEqIds = currentDisciples.flatMap { it.equipment.storageBagItems }
        .filter { it.itemType == "equipment_stack" }.map { it.itemId }.toSet()
    val freeEqStacks = eqStacks.filter { it.id !in bagEqIds && !it.isLocked }

    val bagMnIds = currentDisciples.flatMap { it.equipment.storageBagItems }
        .filter { it.itemType == "manual_stack" }.map { it.itemId }.toSet()
    val freeMnStacks = mnStacks.filter { it.id !in bagMnIds && !it.isLocked }

    // 仓库自动装备
    if (disciple.equipment.autoEquipFromWarehouse) {
        val result = DiscipleEquipmentManager.processAutoEquipFromWarehouse(
            disciple = updatedDisciple,
            warehouseStacks = freeEqStacks,
            equipmentInstances = eqInstances.associateBy { it.id },
            gameYear = year, gameMonth = month, gameDay = day,
            maxStack = inventoryConfig.getMaxStackSize("equipment_stack")
        )
        if (result.newInstances.isNotEmpty()) {
            updatedDisciple = result.disciple
            eqInstances = eqInstances + result.newInstances
            result.stackUpdates.forEach { update ->
                if (update.isDeletion) {
                    eqStacks = eqStacks.filter { it.id != update.stackId }
                } else {
                    eqStacks = eqStacks.map {
                        if (it.id == update.stackId) it.copy(quantity = update.newQuantity) else it
                    }
                }
            }
            events.addAll(result.events)
        }
    }

    // 仓库自动学习
    if (disciple.autoLearnFromWarehouse) {
        val result = DiscipleManualManager.processAutoLearnFromWarehouse(
            disciple = updatedDisciple,
            warehouseStacks = freeMnStacks,
            manualInstances = mnInstances.associateBy { it.id },
            gameYear = year, gameMonth = month, gameDay = day,
            maxStack = inventoryConfig.getMaxStackSize("manual_stack")
        )
        if (result.newInstance != null) {
            updatedDisciple = result.disciple
            mnInstances = mnInstances + result.newInstance
            result.stackUpdate?.let { update ->
                if (update.isDeletion) {
                    mnStacks = mnStacks.filter { it.id != update.stackId }
                } else {
                    mnStacks = mnStacks.map {
                        if (it.id == update.stackId) it.copy(quantity = update.newQuantity) else it
                    }
                }
            }
            events.addAll(result.events)
        }
    }

    state.copy(
        disciples = state.disciples + updatedDisciple,
        equipmentStacks = eqStacks,
        equipmentInstances = eqInstances,
        manualStacks = mnStacks,
        manualInstances = mnInstances,
        events = events.toList()
    )
}

// 写回
currentDisciples = finalState.disciples
currentEquipmentStacks = finalState.equipmentStacks
currentEquipmentInstances = finalState.equipmentInstances
currentManualStacks = finalState.manualStacks
currentManualInstances = finalState.manualInstances
finalState.events.forEach { eventService.addGameEvent(it, EventType.SUCCESS) }
```

---

## 五、UI 层变更

### 5.1 EquipmentSection 修改

**现有**：
```
Text("装备", fontSize = 12.sp, fontWeight = Bold, color = Black)
```

**改为**：
```
Row(verticalAlignment = CenterVertically) {
    Text("装备", fontSize = 12.sp, fontWeight = Bold, color = Black)
    Spacer(Modifier.weight(1f))
    Text("自动穿戴仓库装备", fontSize = 10.sp, color = Color(0xFF999999))
    Spacer(Modifier.width(4.dp))
    Checkbox(
        checked = autoEquipFromWarehouse,
        onCheckedChange = onAutoEquipToggle,
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape),
        colors = CheckboxDefaults.colors(...)
    )
}
```

`EquipmentSection` 签名新增：
- `autoEquipFromWarehouse: Boolean`
- `onAutoEquipToggle: (Boolean) -> Unit`

### 5.2 ManualsSection 修改

同理，标题行改为：
```
Row(verticalAlignment = CenterVertically) {
    Text("功法", fontSize = 12.sp, fontWeight = Bold, color = Black)
    Spacer(Modifier.weight(1f))
    Text("自动学习仓库功法", fontSize = 10.sp, color = Color(0xFF999999))
    Spacer(Modifier.width(4.dp))
    Checkbox(
        checked = autoLearnFromWarehouse,
        onCheckedChange = onAutoLearnToggle,
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape),
        colors = CheckboxDefaults.colors(...)
    )
}
```

`ManualsSection` 签名新增：
- `autoLearnFromWarehouse: Boolean`
- `onAutoLearnToggle: (Boolean) -> Unit`

### 5.3 DiscipleDetailDialog 传参

`DiscipleDetailDialog` 需要从 `DiscipleAggregate` 中读取两个新字段：
- `disciple.equipment?.autoEquipFromWarehouse ?: false`
- `disciple.extended?.autoLearnFromWarehouse ?: false`

传递给子 Section。勾选变更通过 `viewModel` 调用引擎更新。

---

## 六、ViewModel 层变更

### 6.1 GameViewModel 新增方法

```kotlin
fun toggleAutoEquipFromWarehouse(discipleId: String, enabled: Boolean) {
    gameEngine.updateDiscipleAutoEquip(discipleId, enabled)
}

fun toggleAutoLearnFromWarehouse(discipleId: String, enabled: Boolean) {
    gameEngine.updateDiscipleAutoLearn(discipleId, enabled)
}
```

### 6.2 GameEngine 新增方法

```kotlin
fun updateDiscipleAutoEquip(discipleId: String, enabled: Boolean) {
    gameEngineCore.launchInScope {
        stateStore.update {
            disciples = disciples.map { d ->
                if (d.id == discipleId) {
                    d.copyWith(equipment = d.equipment.copy(autoEquipFromWarehouse = enabled))
                } else d
            }
        }
    }
}

fun updateDiscipleAutoLearn(discipleId: String, enabled: Boolean) {
    gameEngineCore.launchInScope {
        stateStore.update {
            disciples = disciples.map { d ->
                if (d.id == discipleId) {
                    d.copy(autoLearnFromWarehouse = enabled)
                } else d
            }
        }
    }
}
```

---

## 七、完整文件修改清单

| # | 文件路径 | 修改内容 |
|---|---|---|
| 1 | `core/model/DiscipleComponents.kt` | `EquipmentSet` 新增 `autoEquipFromWarehouse` 字段 |
| 2 | `core/model/Disciple.kt` | 新增 `autoLearnFromWarehouse` 字段 + `copyWith` 支持 |
| 3 | `core/model/DiscipleEquipment.kt` | 分表新增 `autoEquipFromWarehouse` + `fromDisciple` 映射 |
| 4 | `core/model/DiscipleExtended.kt` | 分表新增 `autoLearnFromWarehouse` + `fromDisciple` 映射 |
| 5 | `core/model/DiscipleAggregate.kt` | 暴露两个新字段的计算属性 |
| 6 | `data/local/GameDatabase.kt` | version 21→22，新增 `MIGRATION_21_22` |
| 7 | `core/engine/DiscipleEquipmentManager.kt` | 新增 `processAutoEquipFromWarehouse()` 方法 |
| 8 | `core/engine/DiscipleManualManager.kt` | 新增 `processAutoLearnFromWarehouse()` 方法 |
| 9 | `core/engine/service/CultivationService.kt` | `processAutoUseItems()` 中集成仓库自动逻辑，弟子按优先级排序 |
| 10 | `ui/game/DiscipleDetailScreen.kt` | `EquipmentSection` / `ManualsSection` 新增勾选框 UI |
| 11 | `ui/game/GameViewModel.kt` | 新增 `toggleAutoEquipFromWarehouse` / `toggleAutoLearnFromWarehouse` |
| 12 | `core/engine/GameEngine.kt` | 新增 `updateDiscipleAutoEquip` / `updateDiscipleAutoLearn` |
| 13 | `core/ChangelogData.kt` | 更新日志 |
| 14 | `CHANGELOG.md` | 更新日志 |

---

## 八、风险与注意事项

| 风险 | 严重程度 | 应对措施 |
|---|---|---|
| 旧存档兼容 | 高 | 新增字段均有默认值 `false`，Migration 用 `DEFAULT 0`，旧存档自动关闭 |
| 多弟子竞争同一仓库物品 | 高 | 按优先级排序（已关注 > 未关注，高境界 > 低境界），使用 `fold` 同步更新仓库栈列表 |
| isLocked 保护 | 中 | 新方法中过滤 `!stack.isLocked`，锁定物品不可自动穿戴/学习 |
| 仅空槽位操作 | 中 | 不替换已有装备/功法，逻辑更简单，无需处理旧物品归还 |
| `copyWith` 扩展 | 低 | `Disciple.copyWith` 需支持新的 `EquipmentSet` 字段和 `autoLearnFromWarehouse` |
| 仓库栈列表与储物袋引用的一致性 | 中 | 空闲 Stack 的判定需排除所有弟子（含当前正在处理的弟子）的储物袋引用 |

---

## 九、测试要点

1. **默认关闭**：新存档和旧存档的弟子，两个开关默认均为 `false`，不触发任何自动行为
2. **手动勾选**：在弟子详情界面勾选后，次日 tick 应触发自动穿戴/学习
3. **境界限制**：仓库中有高品阶但境界不足的装备/功法，不应被自动穿戴/学习
4. **优先高品阶**：仓库中有多件同槽位/类型装备/功法，应选择最高品阶的
5. **心法唯一**：已有心法时，仓库中的心法不会被自动学习
6. **同名功法**：仓库中有与已学功法同名的，不可重复学习
7. **锁定保护**：锁定的仓库物品不可被自动穿戴/学习
8. **仅空槽位**：已有装备/功法的槽位不会被替换，只在空槽位时自动填充
9. **多弟子竞争优先级**：已关注弟子优先于未关注弟子，高境界弟子优先于低境界弟子
10. **取消勾选**：取消勾选后，次日 tick 不再触发自动行为
11. **功法槽位已满**：功法槽位已满时不触发自动学习
