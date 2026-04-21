# Equipment / Manual 仓库-实例分离重构方案

> 数据库版本：11 → 12 | 方案选型：EquipmentStack 保留 quantity（方案 A）

---

## 一、问题总览

`Equipment` 和 `Manual` 各自用**同一个 data class** 承载了两个本质不同的领域概念：

| | 仓库堆叠物品 | 已装备/已学习实例 |
|---|---|---|
| 身份 | 无独立身份，仅是模板的计数 | 有唯一 ID，绑定到特定弟子 |
| 堆叠 | 可堆叠（Equipment 上限待定，Manual 上限 99） | 不可堆叠，quantity 恒为 1 |
| 可变状态 | 仅 quantity | nurtureLevel/nurtureProgress、熟练度等 |
| 归属 | ownerId = null | ownerId = discipleId |
| 生命周期 | 获得 → 消耗/分配 | 装备 → 孕育 → 卸下 → 销毁 |

当前用 `isEquipped`/`isLearned` + `ownerId` 在同一模型内做区分，导致以下具体问题：

### 问题 1：堆叠逻辑与实例状态耦合

堆叠匹配条件中混入了实例状态判断：

```kotlin
// InventorySystem.kt:217-218
val existing = currentEquipment.find {
    it.name == item.name && it.rarity == item.rarity && it.slot == item.slot && !it.isEquipped
}

// InventorySystem.kt:313-314
val existing = currentManuals.find {
    it.name == item.name && it.rarity == item.rarity && it.type == item.type && !it.isLearned
}
```

如果未来增加更多状态维度（锁定、绑定等），堆叠条件会持续膨胀。

### 问题 2：数量拆分的连锁复杂性

当 `quantity > 1` 的堆叠物品被装备/学习时，需拆分为两个条目。**同一套拆分逻辑在三个地方重复实现**：

- `DiscipleEquipmentManager.processSlot()` (L140-148)
- `DiscipleManualManager.learnNewManual()` (L94-102)
- `DiscipleManualManager.tryReplaceManual()` (L159-167)
- `GameEngine.learnManual()` (L1292-1301)

且 `remainingEquipment`/`remainingManual` 需通过 Result 对象一路回传到 `CultivationService` 手动合并回全局列表，任何一环遗漏都会导致数据不一致。

### 问题 3：Equipment 堆叠配置矛盾

| 位置 | 值 |
|---|---|
| `InventoryConfig.typeSpecificStackLimits["equipment"]` | 1（不可堆叠） |
| `InventorySystem.MAX_STACK_SIZE` | 999（硬编码） |
| `InventorySystem.addEquipment()` | 有完整堆叠合并逻辑 |

`addEquipment` 未读取 `InventoryConfig`，直接使用硬编码 `MAX_STACK_SIZE = 999`。绕过 `InventoryConfig` 调用时，装备实际上会被堆叠。

### 问题 4：全局列表中的身份混乱

同一个"铁剑"在 `stateStore.equipment` 列表中可能存在多个条目：

```
Equipment(id=A, name="铁剑", isEquipped=false, quantity=3)    // 仓库堆叠
Equipment(id=B, name="铁剑", isEquipped=true, ownerId="弟子1") // 弟子1装备的实例
Equipment(id=C, name="铁剑", isEquipped=true, ownerId="弟子2") // 弟子2装备的实例
```

查询"仓库中有多少铁剑"需要 `filter { !it.isEquipped }.sumOf { it.quantity }`，查询"弟子1的装备"需要 `filter { it.ownerId == "弟子1" && it.isEquipped }`。两种查询模式完全不同，却混在同一个列表中。

### 问题 5：StorageBagItem 引用断裂风险

`StorageBagItem` 存储 `itemId`，但装备拆分时原 ID 可能被新 UUID 替换。`DiscipleEquipmentManager` 通过 `filter` 移除旧引用，但 `remainingEquipment` 并未被重新加入储物袋，而是留在全局列表中，导致储物袋引用与全局列表可能不同步。

---

## 二、新模型设计

### 2.1 EquipmentStack（仓库条目）

```kotlin
@Serializable
@Entity(
    tableName = "equipment_stacks",
    primaryKeys = ["id", "slot_id"],
    indices = [
        Index(value = ["name"]),
        Index(value = ["rarity"]),
        Index(value = ["slot"]),
        Index(value = ["rarity", "slot"]),
        Index(value = ["minRealm"])
    ]
)
data class EquipmentStack(
    @ColumnInfo(name = "id")
    val id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "slot_id")
    var slotId: Int = 0,

    val name: String = "",
    val rarity: Int = 1,
    val description: String = "",

    val slot: EquipmentSlot = EquipmentSlot.WEAPON,
    val physicalAttack: Int = 0,
    val magicAttack: Int = 0,
    val physicalDefense: Int = 0,
    val magicDefense: Int = 0,
    val speed: Int = 0,
    val hp: Int = 0,
    val mp: Int = 0,
    val critChance: Double = 0.0,

    val minRealm: Int = 9,

    val quantity: Int = 1,
    val isLocked: Boolean = false
) : GameItem(), StackableItem {

    override fun withQuantity(newQuantity: Int): EquipmentStack = copy(quantity = newQuantity)

    val basePrice: Int get() = GameConfig.Rarity.get(rarity).basePrice

    val stats: EquipmentStats get() = EquipmentStats(
        physicalAttack = physicalAttack,
        magicAttack = magicAttack,
        physicalDefense = physicalDefense,
        magicDefense = magicDefense,
        speed = speed,
        hp = hp,
        mp = mp
    )
}
```

**与旧 Equipment 的差异**：
- 移除 `ownerId`、`isEquipped`、`nurtureLevel`、`nurtureProgress`（实例独有状态）
- 保留 `quantity`（可堆叠）
- 保留模板属性快照（physicalAttack 等），用于仓库展示和创建实例时的初始值复制

### 2.2 EquipmentInstance（装备实例）

```kotlin
@Serializable
@Entity(
    tableName = "equipment_instances",
    primaryKeys = ["id", "slot_id"],
    indices = [
        Index(value = ["name"]),
        Index(value = ["rarity"]),
        Index(value = ["slot"]),
        Index(value = ["ownerId"]),
        Index(value = ["rarity", "slot"]),
        Index(value = ["minRealm"])
    ]
)
data class EquipmentInstance(
    @ColumnInfo(name = "id")
    val id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "slot_id")
    var slotId: Int = 0,

    val name: String = "",
    val rarity: Int = 1,
    val description: String = "",

    val slot: EquipmentSlot = EquipmentSlot.WEAPON,
    val physicalAttack: Int = 0,
    val magicAttack: Int = 0,
    val physicalDefense: Int = 0,
    val magicDefense: Int = 0,
    val speed: Int = 0,
    val hp: Int = 0,
    val mp: Int = 0,
    val critChance: Double = 0.0,

    val nurtureLevel: Int = 0,
    val nurtureProgress: Double = 0.0,

    val minRealm: Int = 9,

    val ownerId: String? = null,
    val isEquipped: Boolean = false
) : GameItem() {

    val basePrice: Int get() = GameConfig.Rarity.get(rarity).basePrice

    val totalMultiplier: Double
        get() = getNurtureMultiplier(nurtureLevel)

    private fun getNurtureMultiplier(level: Int): Double {
        if (level <= 0) return 1.0
        val maxLevel = 25
        val actualLevel = level.coerceAtMost(maxLevel)
        val totalBonus = actualLevel * (actualLevel + 1) / 2.0 * (3.0 / 325.0)
        return (1.0 + totalBonus).coerceAtMost(4.0)
    }

    fun getFinalStats(): EquipmentStats {
        val mult = totalMultiplier
        return EquipmentStats(
            physicalAttack = (physicalAttack * mult).toInt(),
            magicAttack = (magicAttack * mult).toInt(),
            physicalDefense = (physicalDefense * mult).toInt(),
            magicDefense = (magicDefense * mult).toInt(),
            speed = (speed * mult).toInt(),
            hp = (hp * mult).toInt(),
            mp = (mp * mult).toInt()
        )
    }

    val totalStatsDescription: String
        get() {
            val finalStats = getFinalStats()
            val stats = mutableListOf<String>()
            if (finalStats.physicalAttack > 0) stats.add("物攻+${finalStats.physicalAttack}")
            if (finalStats.magicAttack > 0) stats.add("法攻+${finalStats.magicAttack}")
            if (finalStats.physicalDefense > 0) stats.add("物防+${finalStats.physicalDefense}")
            if (finalStats.magicDefense > 0) stats.add("法防+${finalStats.magicDefense}")
            if (finalStats.speed > 0) stats.add("速度+${finalStats.speed}")
            if (finalStats.hp > 0) stats.add("生命+${finalStats.hp}")
            if (finalStats.mp > 0) stats.add("灵力+${finalStats.mp}")
            return if (stats.isEmpty()) "无属性" else stats.joinToString(", ")
        }
}
```

**与旧 Equipment 的差异**：
- 移除 `quantity`（实例恒为 1，不可堆叠）
- 移除 `isLocked`（锁定是仓库概念，实例不适用）
- `ownerId` 和 `isEquipped` 改为 `val`（不可变，创建时确定）
- 保留 `nurtureLevel`/`nurtureProgress`（实例独有可变状态）

### 2.3 ManualStack（仓库条目）

```kotlin
@Serializable
@Entity(
    tableName = "manual_stacks",
    primaryKeys = ["id", "slot_id"],
    indices = [
        Index(value = ["name"]),
        Index(value = ["rarity"]),
        Index(value = ["type"]),
        Index(value = ["rarity", "type"]),
        Index(value = ["minRealm"])
    ]
)
data class ManualStack(
    @ColumnInfo(name = "id")
    val id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "slot_id")
    var slotId: Int = 0,

    val name: String = "",
    val rarity: Int = 1,
    val description: String = "",

    val type: ManualType = ManualType.MIND,
    val stats: Map<String, Int> = emptyMap(),

    val skillName: String? = null,
    val skillDescription: String? = null,
    val skillType: String = "attack",
    val skillDamageType: String = "physical",
    val skillHits: Int = 1,
    val skillDamageMultiplier: Double = 1.0,
    val skillCooldown: Int = 3,
    val skillMpCost: Int = 10,
    val skillHealPercent: Double = 0.0,
    val skillHealType: String = "hp",
    val skillBuffType: String? = null,
    val skillBuffValue: Double = 0.0,
    val skillBuffDuration: Int = 0,
    val skillBuffsJson: String = "",
    val skillIsAoe: Boolean = false,
    val skillTargetScope: String = "self",

    val minRealm: Int = 9,

    val quantity: Int = 1,
    val isLocked: Boolean = false
) : GameItem(), StackableItem {

    override fun withQuantity(newQuantity: Int): ManualStack = copy(quantity = newQuantity)

    val basePrice: Int get() = GameConfig.Rarity.get(rarity).basePrice
}
```

### 2.4 ManualInstance（功法实例）

```kotlin
@Serializable
@Entity(
    tableName = "manual_instances",
    primaryKeys = ["id", "slot_id"],
    indices = [
        Index(value = ["name"]),
        Index(value = ["rarity"]),
        Index(value = ["type"]),
        Index(value = ["ownerId"]),
        Index(value = ["minRealm"]),
        Index(value = ["rarity", "type"])
    ]
)
data class ManualInstance(
    @ColumnInfo(name = "id")
    val id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "slot_id")
    var slotId: Int = 0,

    val name: String = "",
    val rarity: Int = 1,
    val description: String = "",

    val type: ManualType = ManualType.MIND,
    val stats: Map<String, Int> = emptyMap(),

    val skillName: String? = null,
    val skillDescription: String? = null,
    val skillType: String = "attack",
    val skillDamageType: String = "physical",
    val skillHits: Int = 1,
    val skillDamageMultiplier: Double = 1.0,
    val skillCooldown: Int = 3,
    val skillMpCost: Int = 10,
    val skillHealPercent: Double = 0.0,
    val skillHealType: String = "hp",
    val skillBuffType: String? = null,
    val skillBuffValue: Double = 0.0,
    val skillBuffDuration: Int = 0,
    val skillBuffsJson: String = "",
    val skillIsAoe: Boolean = false,
    val skillTargetScope: String = "self",

    val minRealm: Int = 9,

    val ownerId: String? = null,
    val isLearned: Boolean = false
) : GameItem() {

    val basePrice: Int get() = GameConfig.Rarity.get(rarity).basePrice

    // skill 属性计算逻辑从旧 Manual 迁移
    val skill: ManualSkill? get() = skillName?.let { /* 同旧 Manual.skill 逻辑 */ }
    val cultivationSpeedPercent: Double
        get() = stats["cultivationSpeedPercent"]?.toDouble() ?: 0.0
}
```

---

## 三、操作流程变化

### 3.1 装备流程

**改进前**：

```
1. 从全局 equipment 列表找到堆叠条目
2. 如果 quantity > 1：拆分为 equippedItem(新UUID, qty=1, isEquipped=true) + remaining(原ID, qty-1)
3. remainingEquipment 通过 Result 回传到 CultivationService 手动合并
4. 弟子 EquipmentSet.weaponId = equippedItem.id
5. StorageBagItem 引用需手动更新
```

**改进后**：

```
1. EquipmentStack.quantity -= 1
2. 如果 quantity == 0，删除该 Stack 条目
3. 创建 EquipmentInstance(id=新UUID, ownerId=discipleId, isEquipped=true, 属性从Stack复制)
4. 弟子 EquipmentSet.weaponId = instance.id
5. 卸下时：删除 Instance，对应 Stack 的 quantity += 1（或新建 Stack）
```

**消除的复杂性**：
- 不再需要 `quantity > 1` 的拆分逻辑
- 不再需要 `remainingEquipment`/`remainingManual` 的回传
- 不再需要 `!isEquipped`/`!isLearned` 的堆叠过滤条件

### 3.2 学习流程

**改进后**：

```
1. ManualStack.quantity -= 1
2. 如果 quantity == 0，删除该 Stack 条目
3. 创建 ManualInstance(id=新UUID, ownerId=discipleId, isLearned=true, 属性从Stack复制)
4. 弟子 manualIds += instance.id
5. 遗忘时：删除 Instance，对应 Stack 的 quantity += 1（或新建 Stack）
```

### 3.3 卸下/遗忘流程（新增统一回仓方法）

```kotlin
fun returnEquipmentToStack(instance: EquipmentInstance): AddResult {
    val existing = currentStacks.find {
        it.name == instance.name && it.rarity == instance.rarity && it.slot == instance.slot
    }
    return if (existing != null) {
        val newQty = (existing.quantity + 1).coerceAtMost(maxStack)
        updateStack(existing.id, existing.copy(quantity = newQty))
        AddResult.SUCCESS
    } else {
        addStack(instance.toStack(quantity = 1))
    }
}
```

---

## 四、InventorySystem 接口变化

### 4.1 StateFlow 暴露

```kotlin
// 改进前
val equipment: StateFlow<List<Equipment>>
val manuals: StateFlow<List<Manual>>

// 改进后
val equipmentStacks: StateFlow<List<EquipmentStack>>
val equipmentInstances: StateFlow<List<EquipmentInstance>>
val manualStacks: StateFlow<List<ManualStack>>
val manualInstances: StateFlow<List<ManualInstance>>
```

### 4.2 堆叠逻辑简化

```kotlin
// 改进前：需要过滤实例状态
val existing = currentEquipment.find {
    it.name == item.name && it.rarity == item.rarity && it.slot == item.slot && !it.isEquipped
}

// 改进后：Stack 表中不存在已装备条目，无需过滤
val existing = currentStacks.find {
    it.name == item.name && it.rarity == item.rarity && it.slot == item.slot
}
```

### 4.3 容量计算

```kotlin
// 改进前：混合列表统一计数
private fun getTotalSlotCount(): Int {
    return stateStore.equipment.value.size +
           stateStore.manuals.value.size + ...
}

// 改进后：Stack 和 Instance 分开计数，仓库容量只计 Stack
private fun getTotalStackCount(): Int {
    return stateStore.equipmentStacks.value.size +
           stateStore.manualStacks.value.size +
           stateStore.pills.value.size + ...
}
```

Instance 不计入仓库容量，因为它们是弟子的绑定物品。

### 4.4 堆叠上限统一

```kotlin
// InventoryConfig 修改
typeSpecificStackLimits["equipment_stack"] = 99   // EquipmentStack 堆叠上限
typeSpecificStackLimits["manual_stack"] = 99       // ManualStack 堆叠上限（不变）
```

`InventorySystem` 中的 `MAX_STACK_SIZE = 999` 硬编码应替换为读取 `InventoryConfig.getMaxStackSize()`。

---

## 五、GameStateStore 变化

### 5.1 MutableGameState

```kotlin
// 改进前
data class MutableGameState(
    var equipment: List<Equipment>,
    var manuals: List<Manual>,
    // ...
)

// 改进后
data class MutableGameState(
    var equipmentStacks: List<EquipmentStack>,
    var equipmentInstances: List<EquipmentInstance>,
    var manualStacks: List<ManualStack>,
    var manualInstances: List<ManualInstance>,
    // ...
)
```

### 5.2 StateFlow 暴露

```kotlin
val equipmentStacks: StateFlow<List<EquipmentStack>> = _state.map { it.equipmentStacks }
val equipmentInstances: StateFlow<List<EquipmentInstance>> = _state.map { it.equipmentInstances }
val manualStacks: StateFlow<List<ManualStack>> = _state.map { it.manualStacks }
val manualInstances: StateFlow<List<ManualInstance>> = _state.map { it.manualInstances }
```

---

## 六、DiscipleEquipmentManager 变化

### 6.1 processAutoEquip

```kotlin
// 改进后的核心流程
fun processAutoEquip(
    disciple: Disciple,
    equipmentStacks: List<EquipmentStack>,
    equipmentInstances: Map<String, EquipmentInstance>,
    gameYear: Int,
    gameMonth: Int,
    instantMessage: Boolean = false
): EquipmentProcessResult {
    // 从弟子 storageBagItems 中获取仓库引用
    val bagStackRefs = disciple.storageBagItems
        .filter { it.itemType == "equipment_stack" }
        .mapNotNull { ref -> equipmentStacks.find { it.id == ref.itemId } }
        .filter { disciple.realm <= it.minRealm }

    // 从弟子装备槽获取当前实例
    val currentInstances = disciple.equipment.equippedItemIds
        .mapNotNull { equipmentInstances[it] }

    // 对每个槽位：找到比当前实例稀有度更高的 Stack
    // 创建新 Instance，返回 Stack 更新指令
}
```

### 6.2 EquipmentProcessResult

```kotlin
data class EquipmentProcessResult(
    val disciple: Disciple,
    val newInstance: EquipmentInstance?,          // 新创建的装备实例
    val replacedInstance: EquipmentInstance?,     // 被替换的旧实例（需回仓）
    val stackUpdate: StackUpdate?,               // 仓库条目更新（quantity-1）
    val events: List<String>
)

data class StackUpdate(
    val stackId: String,
    val newQuantity: Int,          // -1 表示删除
    val isDeletion: Boolean = false
)
```

**关键改进**：不再需要 `remainingEquipment` 回传。`stackUpdate` 是一个简单的 quantity 递减指令，调用方直接在 Stack 列表上执行即可。

---

## 七、DiscipleManualManager 变化

同 Equipment 逻辑，`ManualLearnResult` 改为：

```kotlin
data class ManualLearnResult(
    val disciple: Disciple,
    val newInstance: ManualInstance?,
    val replacedInstance: ManualInstance?,
    val stackUpdate: StackUpdate?,
    val events: List<String>
)
```

---

## 八、GameEngine.learnManual 变化

```kotlin
// 改进前：手动拆分 quantity
if (manual.quantity > 1) {
    val learnedManualId = java.util.UUID.randomUUID().toString()
    val learnedManual = manual.copy(id = learnedManualId, quantity = 1, isLearned = true, ownerId = discipleId)
    val remainingQuantity = manual.quantity - 1
    manuals = manuals.map { if (it.id == manualId) it.copy(quantity = remainingQuantity) else it } + learnedManual
    // ...
}

// 改进后：Stack 减 quantity + 创建 Instance
suspend fun learnManual(discipleId: String, stackId: String) {
    stateStore.update {
        val stack = manualStacks.find { it.id == stackId } ?: return@update
        val disciple = disciples.find { it.id == discipleId } ?: return@update

        // 验证
        if (!GameConfig.Realm.meetsRealmRequirement(disciple.realm, stack.minRealm)) return@update
        if (stack.type == ManualType.MIND) {
            val hasMind = disciple.manualIds.any { mid ->
                manualInstances.find { it.id == mid }?.type == ManualType.MIND
            }
            if (hasMind) return@update
        }

        // Stack 减 quantity
        val newQty = stack.quantity - 1
        if (newQty <= 0) {
            manualStacks = manualStacks.filter { it.id != stackId }
        } else {
            manualStacks = manualStacks.map {
                if (it.id == stackId) it.copy(quantity = newQty) else it
            }
        }

        // 创建 Instance
        val instanceId = java.util.UUID.randomUUID().toString()
        val instance = stack.toInstance(id = instanceId, ownerId = discipleId, isLearned = true)
        manualInstances = manualInstances + instance

        // 更新弟子
        disciples = disciples.map {
            if (it.id == discipleId && !it.manualIds.contains(instanceId)) {
                it.copy(manualIds = it.manualIds + instanceId)
            } else it
        }
    }
}
```

---

## 九、StorageBagItem 变化

### 9.1 itemType 扩展

```kotlin
// 改进前
data class StorageBagItem(
    val itemId: String,
    val itemType: String,  // "equipment" / "manual" / "pill" 等
    // ...
)

// 改进后
data class StorageBagItem(
    val itemId: String,
    val itemType: String,  // "equipment_stack" / "equipment_instance" / "manual_stack" / "manual_instance" / "pill" 等
    // ...
)
```

### 9.2 引用稳定性

- 仓库中的 Stack 引用（`itemType = "equipment_stack"`）：Stack ID 在 quantity > 0 期间不变
- 弟子装备/功法引用（`itemType = "equipment_instance"`）：Instance ID 创建后永不变化
- 不再存在因拆分导致 ID 变更的问题

---

## 十、DAO 层变化

### 10.1 新增 DAO

```kotlin
// EquipmentStackDao
@Dao
interface EquipmentStackDao {
    @Query("SELECT * FROM equipment_stacks WHERE slot_id = :slotId")
    suspend fun getAll(slotId: Int): List<EquipmentStack>

    @Query("SELECT * FROM equipment_stacks WHERE slot_id = :slotId AND slot = :slot")
    suspend fun getBySlot(slotId: Int, slot: EquipmentSlot): List<EquipmentStack>

    @Query("SELECT * FROM equipment_stacks WHERE slot_id = :slotId AND rarity = :rarity ORDER BY name ASC")
    suspend fun getByRarity(slotId: Int, rarity: Int): List<EquipmentStack>

    @Query("SELECT * FROM equipment_stacks WHERE slot_id = :slotId AND minRealm <= :realm ORDER BY rarity DESC")
    suspend fun getByRealm(slotId: Int, realm: Int): List<EquipmentStack>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: EquipmentStack)

    @Update
    suspend fun update(item: EquipmentStack)

    @Query("DELETE FROM equipment_stacks WHERE id = :id AND slot_id = :slotId")
    suspend fun delete(id: String, slotId: Int)
}

// EquipmentInstanceDao
@Dao
interface EquipmentInstanceDao {
    @Query("SELECT * FROM equipment_instances WHERE slot_id = :slotId")
    suspend fun getAll(slotId: Int): List<EquipmentInstance>

    @Query("SELECT * FROM equipment_instances WHERE slot_id = :slotId AND ownerId = :discipleId")
    suspend fun getByOwner(slotId: Int, discipleId: String): List<EquipmentInstance>

    @Query("SELECT * FROM equipment_instances WHERE slot_id = :slotId AND id = :id")
    suspend fun getById(slotId: Int, id: String): EquipmentInstance?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: EquipmentInstance)

    @Update
    suspend fun update(item: EquipmentInstance)

    @Query("DELETE FROM equipment_instances WHERE id = :id AND slot_id = :slotId")
    suspend fun delete(id: String, slotId: Int)
}

// ManualStackDao / ManualInstanceDao 同理
```

### 10.2 旧 DAO 处理

`EquipmentDao` 和 `ManualDao` 在迁移完成后可删除，或保留为空壳以兼容过渡期。

---

## 十一、数据库迁移（v11 → v12）

### 11.1 迁移 SQL

```kotlin
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ===== Equipment =====

        // 1. 创建 equipment_stacks 表
        db.execSQL("""
            CREATE TABLE equipment_stacks (
                id TEXT NOT NULL,
                slot_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                rarity INTEGER NOT NULL,
                description TEXT NOT NULL,
                slot TEXT NOT NULL,
                physicalAttack INTEGER NOT NULL,
                magicAttack INTEGER NOT NULL,
                physicalDefense INTEGER NOT NULL,
                magicDefense INTEGER NOT NULL,
                speed INTEGER NOT NULL,
                hp INTEGER NOT NULL,
                mp INTEGER NOT NULL,
                critChance REAL NOT NULL,
                minRealm INTEGER NOT NULL,
                quantity INTEGER NOT NULL,
                isLocked INTEGER NOT NULL,
                PRIMARY KEY(id, slot_id)
            )
        """)

        // 2. 创建 equipment_instances 表
        db.execSQL("""
            CREATE TABLE equipment_instances (
                id TEXT NOT NULL,
                slot_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                rarity INTEGER NOT NULL,
                description TEXT NOT NULL,
                slot TEXT NOT NULL,
                physicalAttack INTEGER NOT NULL,
                magicAttack INTEGER NOT NULL,
                physicalDefense INTEGER NOT NULL,
                magicDefense INTEGER NOT NULL,
                speed INTEGER NOT NULL,
                hp INTEGER NOT NULL,
                mp INTEGER NOT NULL,
                critChance REAL NOT NULL,
                nurtureLevel INTEGER NOT NULL,
                nurtureProgress REAL NOT NULL,
                minRealm INTEGER NOT NULL,
                ownerId TEXT,
                isEquipped INTEGER NOT NULL,
                PRIMARY KEY(id, slot_id)
            )
        """)

        // 3. 迁移仓库数据（isEquipped = 0 的条目 → equipment_stacks）
        //    同名同稀有度同槽位的未装备条目需合并 quantity
        db.execSQL("""
            INSERT INTO equipment_stacks (id, slot_id, name, rarity, description, slot,
                physicalAttack, magicAttack, physicalDefense, magicDefense, speed, hp, mp, critChance,
                minRealm, quantity, isLocked)
            SELECT id, slot_id, name, rarity, description, slot,
                physicalAttack, magicAttack, physicalDefense, magicDefense, speed, hp, mp, critChance,
                minRealm, quantity, isLocked
            FROM equipment WHERE isEquipped = 0
        """)

        // 4. 合并同名同稀有度同槽位的 Stack 条目
        //    先找需要合并的组
        val stackCursor = db.query("""
            SELECT name, rarity, slot, slot_id, GROUP_CONCAT(id) as ids, SUM(quantity) as total_qty
            FROM equipment_stacks
            GROUP BY name, rarity, slot, slot_id
            HAVING COUNT(*) > 1
        """)
        stackCursor.use {
            while (it.moveToNext()) {
                val ids = it.getString(it.getColumnIndex("ids")).split(",")
                val totalQty = it.getInt(it.getColumnIndex("totalQty"))
                // 保留第一个 ID，更新 quantity
                db.execSQL("UPDATE equipment_stacks SET quantity = ? WHERE id = ?",
                    arrayOf(totalQty, ids[0]))
                // 删除其余
                for (i in 1 until ids.size) {
                    db.execSQL("DELETE FROM equipment_stacks WHERE id = ?", arrayOf(ids[i]))
                }
            }
        }

        // 5. 迁移实例数据（isEquipped = 1 的条目 → equipment_instances）
        db.execSQL("""
            INSERT INTO equipment_instances (id, slot_id, name, rarity, description, slot,
                physicalAttack, magicAttack, physicalDefense, magicDefense, speed, hp, mp, critChance,
                nurtureLevel, nurtureProgress, minRealm, ownerId, isEquipped)
            SELECT id, slot_id, name, rarity, description, slot,
                physicalAttack, magicAttack, physicalDefense, magicDefense, speed, hp, mp, critChance,
                nurtureLevel, nurtureProgress, minRealm, ownerId, isEquipped
            FROM equipment WHERE isEquipped = 1
        """)

        // 6. 删除旧表
        db.execSQL("DROP TABLE equipment")


        // ===== Manual =====

        // 7. 创建 manual_stacks 表
        db.execSQL("""
            CREATE TABLE manual_stacks (
                id TEXT NOT NULL,
                slot_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                rarity INTEGER NOT NULL,
                description TEXT NOT NULL,
                type TEXT NOT NULL,
                stats TEXT NOT NULL,
                skillName TEXT,
                skillDescription TEXT,
                skillType TEXT NOT NULL,
                skillDamageType TEXT NOT NULL,
                skillHits INTEGER NOT NULL,
                skillDamageMultiplier REAL NOT NULL,
                skillCooldown INTEGER NOT NULL,
                skillMpCost INTEGER NOT NULL,
                skillHealPercent REAL NOT NULL,
                skillHealType TEXT NOT NULL,
                skillBuffType TEXT,
                skillBuffValue REAL NOT NULL,
                skillBuffDuration INTEGER NOT NULL,
                skillBuffsJson TEXT NOT NULL,
                skillIsAoe INTEGER NOT NULL,
                skillTargetScope TEXT NOT NULL,
                minRealm INTEGER NOT NULL,
                quantity INTEGER NOT NULL,
                isLocked INTEGER NOT NULL,
                PRIMARY KEY(id, slot_id)
            )
        """)

        // 8. 创建 manual_instances 表
        db.execSQL("""
            CREATE TABLE manual_instances (
                id TEXT NOT NULL,
                slot_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                rarity INTEGER NOT NULL,
                description TEXT NOT NULL,
                type TEXT NOT NULL,
                stats TEXT NOT NULL,
                skillName TEXT,
                skillDescription TEXT,
                skillType TEXT NOT NULL,
                skillDamageType TEXT NOT NULL,
                skillHits INTEGER NOT NULL,
                skillDamageMultiplier REAL NOT NULL,
                skillCooldown INTEGER NOT NULL,
                skillMpCost INTEGER NOT NULL,
                skillHealPercent REAL NOT NULL,
                skillHealType TEXT NOT NULL,
                skillBuffType TEXT,
                skillBuffValue REAL NOT NULL,
                skillBuffDuration INTEGER NOT NULL,
                skillBuffsJson TEXT NOT NULL,
                skillIsAoe INTEGER NOT NULL,
                skillTargetScope TEXT NOT NULL,
                minRealm INTEGER NOT NULL,
                ownerId TEXT,
                isLearned INTEGER NOT NULL,
                PRIMARY KEY(id, slot_id)
            )
        """)

        // 9. 迁移仓库数据（isLearned = 0 → manual_stacks）
        db.execSQL("""
            INSERT INTO manual_stacks (id, slot_id, name, rarity, description, type, stats,
                skillName, skillDescription, skillType, skillDamageType, skillHits, skillDamageMultiplier,
                skillCooldown, skillMpCost, skillHealPercent, skillHealType, skillBuffType, skillBuffValue,
                skillBuffDuration, skillBuffsJson, skillIsAoe, skillTargetScope,
                minRealm, quantity, isLocked)
            SELECT id, slot_id, name, rarity, description, type, stats,
                skillName, skillDescription, skillType, skillDamageType, skillHits, skillDamageMultiplier,
                skillCooldown, skillMpCost, skillHealPercent, skillHealType, skillBuffType, skillBuffValue,
                skillBuffDuration, skillBuffsJson, skillIsAoe, skillTargetScope,
                minRealm, quantity, isLocked
            FROM manuals WHERE isLearned = 0
        """)

        // 10. 合并同名同稀有度同类型的 Stack 条目
        val manualStackCursor = db.query("""
            SELECT name, rarity, type, slot_id, GROUP_CONCAT(id) as ids, SUM(quantity) as total_qty
            FROM manual_stacks
            GROUP BY name, rarity, type, slot_id
            HAVING COUNT(*) > 1
        """)
        manualStackCursor.use {
            while (it.moveToNext()) {
                val ids = it.getString(it.getColumnIndex("ids")).split(",")
                val totalQty = it.getInt(it.getColumnIndex("total_qty"))
                db.execSQL("UPDATE manual_stacks SET quantity = ? WHERE id = ?",
                    arrayOf(totalQty, ids[0]))
                for (i in 1 until ids.size) {
                    db.execSQL("DELETE FROM manual_stacks WHERE id = ?", arrayOf(ids[i]))
                }
            }
        }

        // 11. 迁移实例数据（isLearned = 1 → manual_instances）
        db.execSQL("""
            INSERT INTO manual_instances (id, slot_id, name, rarity, description, type, stats,
                skillName, skillDescription, skillType, skillDamageType, skillHits, skillDamageMultiplier,
                skillCooldown, skillMpCost, skillHealPercent, skillHealType, skillBuffType, skillBuffValue,
                skillBuffDuration, skillBuffsJson, skillIsAoe, skillTargetScope,
                minRealm, ownerId, isLearned)
            SELECT id, slot_id, name, rarity, description, type, stats,
                skillName, skillDescription, skillType, skillDamageType, skillHits, skillDamageMultiplier,
                skillCooldown, skillMpCost, skillHealPercent, skillHealType, skillBuffType, skillBuffValue,
                skillBuffDuration, skillBuffsJson, skillIsAoe, skillTargetScope,
                minRealm, ownerId, isLearned
            FROM manuals WHERE isLearned = 1
        """)

        // 12. 删除旧表
        db.execSQL("DROP TABLE manuals")


        // ===== 索引 =====

        db.execSQL("CREATE INDEX index_equipment_stacks_name ON equipment_stacks(name)")
        db.execSQL("CREATE INDEX index_equipment_stacks_rarity ON equipment_stacks(rarity)")
        db.execSQL("CREATE INDEX index_equipment_stacks_slot ON equipment_stacks(slot)")
        db.execSQL("CREATE INDEX index_equipment_stacks_rarity_slot ON equipment_stacks(rarity, slot)")
        db.execSQL("CREATE INDEX index_equipment_stacks_minRealm ON equipment_stacks(minRealm)")

        db.execSQL("CREATE INDEX index_equipment_instances_name ON equipment_instances(name)")
        db.execSQL("CREATE INDEX index_equipment_instances_rarity ON equipment_instances(rarity)")
        db.execSQL("CREATE INDEX index_equipment_instances_slot ON equipment_instances(slot)")
        db.execSQL("CREATE INDEX index_equipment_instances_ownerId ON equipment_instances(ownerId)")
        db.execSQL("CREATE INDEX index_equipment_instances_rarity_slot ON equipment_instances(rarity, slot)")
        db.execSQL("CREATE INDEX index_equipment_instances_minRealm ON equipment_instances(minRealm)")

        db.execSQL("CREATE INDEX index_manual_stacks_name ON manual_stacks(name)")
        db.execSQL("CREATE INDEX index_manual_stacks_rarity ON manual_stacks(rarity)")
        db.execSQL("CREATE INDEX index_manual_stacks_type ON manual_stacks(type)")
        db.execSQL("CREATE INDEX index_manual_stacks_rarity_type ON manual_stacks(rarity, type)")
        db.execSQL("CREATE INDEX index_manual_stacks_minRealm ON manual_stacks(minRealm)")

        db.execSQL("CREATE INDEX index_manual_instances_name ON manual_instances(name)")
        db.execSQL("CREATE INDEX index_manual_instances_rarity ON manual_instances(rarity)")
        db.execSQL("CREATE INDEX index_manual_instances_type ON manual_instances(type)")
        db.execSQL("CREATE INDEX index_manual_instances_ownerId ON manual_instances(ownerId)")
        db.execSQL("CREATE INDEX index_manual_instances_minRealm ON manual_instances(minRealm)")
        db.execSQL("CREATE INDEX index_manual_instances_rarity_type ON manual_instances(rarity, type)")
    }
}
```

### 11.2 迁移注意事项

1. **StorageBagItem 引用更新**：旧数据中 `itemType = "equipment"` 的引用需区分更新：
   - 指向 `isEquipped=false` 的条目 → `itemType = "equipment_stack"`
   - 指向 `isEquipped=true` 的条目 → `itemType = "equipment_instance"`
   - 但 StorageBagItem 嵌入在 Disciple 的 JSON 字段中，Room 迁移无法直接操作，需在应用启动时做一次数据修复

2. **DiscipleEquipment 嵌入表**：`DiscipleEquipment` 中的 `weaponId`/`armorId`/`bootsId`/`accessoryId` 引用的是 Instance ID，迁移后这些 ID 存在于 `equipment_instances` 表中，无需变更

3. **manualIds 引用**：Disciple 的 `manualIds` 引用的是已学习功法的 ID，迁移后这些 ID 存在于 `manual_instances` 表中，无需变更

4. **manualProficiencies 引用**：`GameData.manualProficiencies` 中的 `manualId` 引用的是已学习功法的 ID，迁移后存在于 `manual_instances` 表中，无需变更

### 11.3 应用启动时的数据修复

```kotlin
// 在 StorageEngine.loadFromDatabase 后执行
fun fixStorageBagReferences(state: GameState): GameState {
    val stackIds = state.equipmentStacks.map { it.id }.toSet()
    val instanceIds = state.equipmentInstances.map { it.id }.toSet()
    val manualStackIds = state.manualStacks.map { it.id }.toSet()
    val manualInstanceIds = state.manualInstances.map { it.id }.toSet()

    val fixedDisciples = state.disciples.map { disciple ->
        val fixedItems = disciple.equipment.storageBagItems.map { item ->
            when {
                item.itemType == "equipment" -> {
                    when {
                        instanceIds.contains(item.itemId) -> item.copy(itemType = "equipment_instance")
                        stackIds.contains(item.itemId) -> item.copy(itemType = "equipment_stack")
                        else -> item // 引用丢失，保留原样
                    }
                }
                item.itemType == "manual" -> {
                    when {
                        manualInstanceIds.contains(item.itemId) -> item.copy(itemType = "manual_instance")
                        manualStackIds.contains(item.itemId) -> item.copy(itemType = "manual_stack")
                        else -> item
                    }
                }
                else -> item
            }
        }
        disciple.copyWith(equipment = disciple.equipment.copy(storageBagItems = fixedItems))
    }

    return state.copy(disciples = fixedDisciples)
}
```

---

## 十二、GameDatabase 注册

```kotlin
@Database(
    entities = [
        // ... 其他 Entity 不变
        EquipmentStack::class,       // 替换 Equipment::class
        EquipmentInstance::class,    // 新增
        ManualStack::class,          // 替换 Manual::class
        ManualInstance::class,       // 新增
        // Pill, Material, Herb, Seed 不变
    ],
    version = 12,
    exportSchema = true
)
abstract class GameDatabase : RoomDatabase() {
    // ...
    abstract fun equipmentStackDao(): EquipmentStackDao
    abstract fun equipmentInstanceDao(): EquipmentInstanceDao
    abstract fun manualStackDao(): ManualStackDao
    abstract fun manualInstanceDao(): ManualInstanceDao
    // 移除 abstract fun equipmentDao(): EquipmentDao
    // 移除 abstract fun manualDao(): ManualDao
}
```

---

## 十三、受影响文件清单

### 需修改的文件

| 文件 | 变更内容 |
|---|---|
| `Items.kt` | 新增 EquipmentStack/EquipmentInstance/ManualStack/ManualInstance，旧 Equipment/Manual 保留但标记 @Deprecated |
| `InventorySystem.kt` | 拆分为 Stack/Instance 两套操作接口，堆叠逻辑简化，MAX_STACK_SIZE 改为读取 InventoryConfig |
| `GameStateStore.kt` | MutableGameState 拆分字段，StateFlow 拆分暴露 |
| `DiscipleEquipmentManager.kt` | processAutoEquip 改为操作 Stack/Instance，消除拆分逻辑 |
| `DiscipleManualManager.kt` | processAutoLearn 改为操作 Stack/Instance，消除拆分逻辑 |
| `GameEngine.kt` | learnManual 等方法改为 Stack→Instance 流程 |
| `CultivationService.kt` | 调用方适配新的 Result 类型，消除 remainingEquipment/remainingManual 处理 |
| `InventoryConfig.kt` | equipment 堆叠上限从 1 改为 99，新增 equipment_stack/manual_stack 配置键 |
| `GameDatabase.kt` | 版本 11→12，注册新 Entity/DAO，添加 MIGRATION_11_12 |
| `Daos.kt` | 新增 EquipmentStackDao/EquipmentInstanceDao/ManualStackDao/ManualInstanceDao |
| `StorageEngine.kt` | loadFromDatabase 适配新表结构，新增 fixStorageBagReferences |
| `DiscipleComponents.kt` | StorageBagItem.itemType 扩展注释 |
| `Disciple.kt` | StorageBagItem 引用适配（通过 @Deprecated 过渡） |

### 需新增的文件

| 文件 | 内容 |
|---|---|
| `EquipmentStack.kt` | EquipmentStack data class（或放入 Items.kt） |
| `EquipmentInstance.kt` | EquipmentInstance data class（或放入 Items.kt） |
| `ManualStack.kt` | ManualStack data class（或放入 Items.kt） |
| `ManualInstance.kt` | ManualInstance data class（或放入 Items.kt） |

### 可删除的文件（过渡期后）

| 文件 | 原因 |
|---|---|
| 旧 `Equipment` data class | 被 EquipmentStack + EquipmentInstance 替代 |
| 旧 `Manual` data class | 被 ManualStack + ManualInstance 替代 |
| `EquipmentDao` | 被 EquipmentStackDao + EquipmentInstanceDao 替代 |
| `ManualDao` | 被 ManualStackDao + ManualInstanceDao 替代 |

---

## 十四、拆分逻辑的废弃说明

### 14.1 旧拆分机制回顾

在当前代码中，为解决"仓库堆叠物品"和"已装备/已学习实例"混为一体的问题，采用了**学习/装备时拆分堆叠**的方式：当 `quantity > 1` 的物品被装备或学习时，将一条记录裂变为两条——原条目保留 `quantity - 1`，新条目以新 UUID 标记为已装备/已学习。

该逻辑在以下 4 处独立实现：

| 位置 | 代码 |
|---|---|
| `DiscipleEquipmentManager.processSlot()` L140-148 | `if (betterEquip.quantity > 1)` 分支 |
| `DiscipleManualManager.learnNewManual()` L94-102 | `if (bestManual.quantity > 1)` 分支 |
| `DiscipleManualManager.tryReplaceManual()` L159-167 | `if (highestBag.quantity > 1)` 分支 |
| `GameEngine.learnManual()` L1292-1301 | `if (manual.quantity > 1)` 分支 |

同时，拆分产生的 `remainingEquipment`/`remainingManual` 需通过 Result 对象回传到 `CultivationService` 手动合并回全局列表。

数据库迁移 v8→v9 和 v9→v10 也为修复历史遗留的"堆叠已学习/已装备"异常数据，实现了运行时拆分逻辑的 SQL 版本。

### 14.2 重构后拆分逻辑不再需要

重构后 Stack 和 Instance 是不同的表/模型，操作从"同一列表内的记录裂变"变为"跨表的转移"：

```
旧方式（拆分）：
  Equipment(id=A, qty=3, isEquipped=false)
  → Equipment(id=A, qty=2, isEquipped=false)              // 原条目改 quantity
  + Equipment(id=B, qty=1, isEquipped=true, ownerId=X)    // 新条目，新 UUID

新方式（转移）：
  EquipmentStack(id=A, qty=3)
  → EquipmentStack(id=A, qty=2)                            // 同一条目，quantity-1
  + EquipmentInstance(id=B, ownerId=X)                     // 新表，新记录
```

两者的本质区别：

| | 拆分（旧） | 转移（新） |
|---|---|---|
| 操作对象 | 同一列表内的同类型记录 | 两个不同列表的不同类型记录 |
| 原 ID | 保留（仓库部分）或新建（实例部分） | Stack 保留原 ID，仅减 quantity |
| 回传需求 | remainingEquipment/remainingManual 必须回传 | 不需要，Stack 就地修改 |
| 重复代码 | 4 处独立实现同一拆分逻辑 | 消除，统一为 `stack.quantity -= 1` + `new Instance` |
| StorageBagItem | 实例部分 ID 变更导致引用断裂 | Instance ID 创建后不变 |

### 14.3 需要删除的代码

以下代码在重构后全部不再需要：

**拆分分支**：

- `DiscipleEquipmentManager.processSlot()` 中 `if (betterEquip.quantity > 1)` 的整个分支 → 替换为 `stack.quantity -= 1` + 创建 `EquipmentInstance`
- `DiscipleManualManager.learnNewManual()` 中 `if (bestManual.quantity > 1)` 的整个分支 → 替换为 `stack.quantity -= 1` + 创建 `ManualInstance`
- `DiscipleManualManager.tryReplaceManual()` 中 `if (highestBag.quantity > 1)` 的整个分支 → 同上
- `GameEngine.learnManual()` 中 `if (manual.quantity > 1)` 的整个分支 → 同上

**回传字段**：

- `EquipmentProcessResult.remainingEquipment` → 删除
- `ManualLearnResult.remainingManual` → 删除

**合并处理**：

- `CultivationService` 中对 `remainingEquipment` 的合并处理（L866-870）→ 删除
- `CultivationService` 中对 `remainingManual` 的合并处理（L906-908）→ 删除

### 14.4 统一替换为转移操作

```kotlin
// 装备：Stack → Instance
val stack = equipmentStacks.find { it.id == stackId } ?: return
val newQty = stack.quantity - 1
if (newQty <= 0) {
    equipmentStacks = equipmentStacks.filter { it.id != stackId }
} else {
    equipmentStacks = equipmentStacks.map { if (it.id == stackId) it.copy(quantity = newQty) else it }
}
val instance = stack.toInstance(id = UUID.randomUUID().toString(), ownerId = discipleId)
equipmentInstances = equipmentInstances + instance

// 卸下：Instance → Stack
val existing = equipmentStacks.find {
    it.name == instance.name && it.rarity == instance.rarity && it.slot == instance.slot
}
if (existing != null) {
    val newQty = (existing.quantity + 1).coerceAtMost(maxStack)
    equipmentStacks = equipmentStacks.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
} else {
    equipmentStacks = equipmentStacks + instance.toStack(quantity = 1)
}
equipmentInstances = equipmentInstances.filter { it.id != instance.id }
```

### 14.5 数据库迁移中的影响

v8→v9 和 v9→v10 的迁移逻辑为修复"堆叠已学习/已装备"异常数据而实现了 SQL 版的拆分。在 v11→v12 的迁移中，这些异常数据会被新表结构自然消解：

- 旧表中 `isLearned=1 & quantity>1` 的异常数据：迁移时进入 `manual_instances` 表，`manual_instances` 无 `quantity` 字段，异常状态不存在
- 旧表中 `isEquipped=1 & quantity>1` 的异常数据：迁移时进入 `equipment_instances` 表，`equipment_instances` 无 `quantity` 字段，异常状态不存在
- 旧表中 `isLearned=0` 的正常堆叠数据：迁移时进入 `manual_stacks` 表，`quantity` 字段保留

因此 v11→v12 的迁移 SQL 不需要再包含拆分逻辑，只需按 `isEquipped`/`isLearned` 状态分流到不同的新表即可。

---

## 十五、实施顺序

```
阶段 1：模型层（无行为变更）
  ├─ 新增 EquipmentStack / EquipmentInstance / ManualStack / ManualInstance
  ├─ 旧 Equipment / Manual 标记 @Deprecated
  ├─ 新增 DAO 接口
  └─ GameDatabase 注册新 Entity，版本 11→12，添加迁移

阶段 2：状态层
  ├─ GameStateStore 添加新字段
  ├─ StorageEngine 适配新表加载
  └─ fixStorageBagReferences 启动修复

阶段 3：逻辑层
  ├─ InventorySystem 拆分 Stack/Instance 操作
  ├─ DiscipleEquipmentManager 重写
  ├─ DiscipleManualManager 重写
  └─ GameEngine.learnManual 重写

阶段 4：调用方适配
  ├─ CultivationService 适配新 Result
  ├─ 商店/锻造/炼丹等产出装备的入口改为创建 Stack
  └─ UI 层适配（仓库展示 Stack 列表，弟子详情展示 Instance）

阶段 5：清理
  ├─ 删除旧 Equipment / Manual data class
  ├─ 删除旧 EquipmentDao / ManualDao
  └─ 删除 @Deprecated 包装方法
```

---

## 十六、改进收益总结

| 维度 | 改进前 | 改进后 |
|---|---|---|
| 模型数量 | 1 个 Equipment / 1 个 Manual | 2+2 个，每个职责单一 |
| 堆叠条件 | name+rarity+slot+**!isEquipped** | name+rarity+slot（无需状态过滤） |
| 装备/学习拆分 | 3+ 处重复的 quantity>1 拆分逻辑 | 消除，改为 Stack→Instance 创建流程 |
| Result 回传 | remainingEquipment/remainingManual 需手动合并 | 不需要，Stack 直接减 quantity |
| 堆叠配置矛盾 | InventoryConfig=1 vs MAX_STACK_SIZE=999 | 统一读取 InventoryConfig，EquipmentStack 上限 99 |
| 查询效率 | 全列表 filter by isEquipped | 分表查询，索引更精准 |
| StorageBagItem | 引用可能因拆分而失效 | 只引用 Instance ID，恒定不变 |
| 迁移风险 | — | 低：直接映射 + 同名合并，无 ID 变更（Instance 保留原 ID） |
