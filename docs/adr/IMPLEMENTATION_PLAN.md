# 根治方案：组件化实体存储架构

> 版本：v2.0 | 日期：2026-06-13
> 前置阅读：`docs/adr/game-state-architecture-research-report.md`
> 变更量：~150 文件 | 预计工期：8-12 个工作日

---

## 一、诊断

### 根本矛盾

```
Kotlin data class (不可变) + StateFlow<List<T>> (响应式列表) + 100ms tick (高频可变更新)
       ↑                            ↑                              ↑
   每次修改 = 新建对象             每次修改 = 全量列表重建          每次 tick = 大量修改
```

这三者**不可调和**。当前代码用 Shadow Transaction、SettlementCache、自定义 getter/setter 打补丁——每个补丁都增加了复杂度，但矛盾依然存在。

### 为什么之前的方案不够

| 方案 | 本质 |
|------|------|
| EntityStore（SparseArray 包装 List） | 把 O(n) 改成 O(log n)，但 Disciple 仍是 97 字段不可变对象 |
| 移除双重访问器 | 修了一处绕过 Shadow 的代码，但其他 20+ Service 仍在绕过 |
| 统一切结算路径 | 合并了 5 处重复，但 copyWith 仍被调用 87 次 |
| 热冷分离 | 减少了 GameData.copy 开销，但 Disciple.copyWith 开销原封不动 |
| ProtoBuf | 加快了序列化，但内存分配压力没变 |

**所有方案都没有动 Disciple 本身。而 Disciple 的 97 字段不可变 data class 是所有问题的源头。**

---

## 二、新架构：组件表（Component Table）

### 核心思想

**不再把"一个弟子"存为"一个大对象"，而是把"所有弟子的同一种数据"存为"一张窄表"。**

```
旧：disciple_1{name, realm, hp, loyalty, weaponId, ...}   ← 97 字段挤在一个对象里
    disciple_2{name, realm, hp, loyalty, weaponId, ...}
    disciple_3{name, realm, hp, loyalty, weaponId, ...}
    → 改一个 loyalty，复制整个 97 字段对象

新：names      → [id1→"张三",     id2→"李四",     id3→"王五"]
    realms     → [id1→9,          id2→8,          id3→7]
    cultivation→ [id1→3500.0,     id2→1200.0,     id3→8000.0]
    loyalty    → [id1→85,         id2→60,         id3→92]
    weaponIds  → [id1→"sword_1",  id2→"staff_3",  id3→"bow_2"]
    ...
    → 改一个 loyalty，只改 loyalty[id1] = 90，其他表完全不受影响
```

### 数据结构

**一张组件表就是一个 `IntMap<T>` —— Android 的 `SparseArray<T>`。int 键是弟子编号。**

```kotlin
// 每个组件都是独立的、窄的、可独立修改的数据容器
class ComponentTable<T> {
    private val store = SparseArray<T>()   // id → value

    operator fun get(id: Int): T = store[id]
    operator fun set(id: Int, value: T) { store.put(id, value) }
    fun update(id: Int, block: (T) -> T) { store[id] = block(store[id]) }
    val ids: IntArray get() = IntArray(store.size()) { store.keyAt(it) }
    val size: Int get() = store.size()
    fun forEach(action: (Int, T) -> Unit) {
        for (i in 0 until store.size()) action(store.keyAt(i), store.valueAt(i))
    }
}
```

**Disciple 实体 = 所有组件表中键相同的行的集合。**

```
Component Tables (每列一张表，独立索引)

┌──────────┬──────────┬──────────┬──────────┬──────────┐
│  names   │  realms  │loyalties │weaponIds │   ...    │
├──────────┼──────────┼──────────┼──────────┼──────────┤
│1→"张三" │ 1→9      │ 1→85     │1→"sword" │          │
│2→"李四" │ 2→8      │ 2→60     │2→"staff" │          │
│3→"王五" │ 3→7      │ 3→92     │3→"bow"   │          │
└──────────┴──────────┴──────────┴──────────┴──────────┘

"弟子1" = names[1] + realms[1] + loyalty[1] + weaponIds[1] + ...
         ↑ 仅在需要"完整弟子视图"时临时组装（UI 渲染、序列化）
```

### 与当前架构的对比

| 操作 | 旧（Disciple data class） | 新（组件表） |
|------|--------------------------|-------------|
| 查找弟子 | `disciples.find { it.id == "3" }` → O(n) | `tables.name[3]` → O(log n) |
| 改忠诚度 | `d.copyWith(loyalty = 90)` → 复制 97 字段 | `tables.loyalty[3] = 90` → 只写一个 int |
| tick 更新 N 个弟子 | `disciples.map { it.copyWith(...) }` → N 次大对象分配 | `for (id in ids) tables.cultivation[id] += rate` → N 次小写 |
| 获取全量列表 | `disciples` 直接返回 | 遍历 ids 组装（仅 UI 需要时） |
| 持久化 | 序列化整个 `List<Disciple>` | 每张表独立序列化 |

---

## 三、九大问题一次性解决

| # | 问题 | 旧方案（补丁式） | 新方案（根治式） |
|---|------|----------------|----------------|
| 1 | copyWith 97 参数 | 继续用 copyWith | **消灭 copyWith** — 每个字段独立更新 |
| 2 | O(n) List 遍历 | EntityStore 包装 | **消灭 List<Disciple>** — 按表直接索引 |
| 3 | 状态分层过度 | 保留 4 层 StateFlow | **简化** — 每张组件表一个 StateFlow |
| 4 | GameData 985 行 | 热冷分离 | **Disciple 字段不再存于 GameData** — 最多存 counts |
| 5 | 双重访问器 | 删除 getter/setter | **消灭全局可变引用** — 所有操作走 tables |
| 6 | 结算/tick 重复 | 合并到 updateDiscipleState | **统一函数操作组件表** — tick 和结算调同一函数 |
| 7 | 线程安全 | 加 Mutex | **单线程 tick** — 组件表只在 tick 内写 |
| 8 | 数据库层 | 表仍 50+ 列 | **拆分为 ~8 张窄表** + Room 联合查询 |
| 9 | 测试覆盖 | 补充测试 | **组件表天然可测** — 独立输入输出 |

---

## 四、实施步骤

### 步骤 1：建 ComponentTable 基础设施

**新建 1 个文件**，零依赖。

**文件**：`android/core/domain/src/main/java/com/xianxia/sect/core/state/ComponentTable.kt`

```kotlin
package com.xianxia.sect.core.state

import android.util.SparseArray

/**
 * 组件表：存储"所有实体的同一种属性"。
 *
 * 这是整个新架构的基础数据结构。
 * 一张 ComponentTable 就是 id → value 的映射，内部使用 SparseArray。
 *
 * @param T 值类型。对于 Int/Double/Long 等基本类型，优先使用
 *          ComponentTable（避免装箱）而非 ComponentTable<Int>。
 *          字符串、枚举、List 等引用类型直接使用 ComponentTable<T>。
 */
class ComponentTable<T> @JvmOverloads constructor(
    initialCapacity: Int = 64
) {
    private val store = SparseArray<T>(initialCapacity)

    // === 读取 ===

    /** O(log n) 获取 */
    operator fun get(id: Int): T = store[id]
        ?: throw NoSuchElementException("ComponentTable: no entry for id=$id")

    /** O(log n) 获取，可能为 null */
    fun getOrNull(id: Int): T? = store[id]

    /** O(log n) 用默认值获取 */
    fun getOrDefault(id: Int, default: T): T = store[id] ?: default

    // === 写入 ===

    /** 设置值 */
    operator fun set(id: Int, value: T) {
        store.put(id, value)
    }

    /** 原子更新（读取 → 变换 → 写回） */
    inline fun update(id: Int, block: (T) -> T) {
        store[id] = block(store[id])
    }

    // === 遍历 ===

    /** 所有键 */
    fun ids(): IntArray {
        val result = IntArray(store.size())
        for (i in 0 until store.size()) result[i] = store.keyAt(i)
        return result
    }

    /** 大小 */
    val size: Int get() = store.size()

    /** 是否为空 */
    fun isEmpty(): Boolean = store.size() == 0

    /** 包含 ID */
    fun contains(id: Int): Boolean = store.indexOfKey(id) >= 0

    /** 迭代 */
    inline fun forEach(action: (Int, T) -> Unit) {
        for (i in 0 until store.size()) {
            action(store.keyAt(i), store.valueAt(i))
        }
    }

    /** 迭代（仅值） */
    inline fun forEachValue(action: (T) -> Unit) {
        for (i in 0 until store.size()) action(store.valueAt(i))
    }

    /** 映射为列表（仅值） */
    fun values(): List<T> {
        return (0 until store.size()).map { store.valueAt(it) }
    }

    // === 增删 ===

    /** 插入 */
    fun put(id: Int, value: T) {
        store.put(id, value)
    }

    /** 删除 */
    fun remove(id: Int) {
        store.remove(id)
    }

    /** 清空 */
    fun clear() {
        store.clear()
    }
}

/**
 * 基本类型组件表：int 值，无装箱。
 * 用于 loyalty, hp, realm 等 int 字段。
 */
class IntComponentTable(initialCapacity: Int = 64) {
    private val store = SparseIntArray(initialCapacity)

    operator fun get(id: Int): Int = store[id]
    fun getOrDefault(id: Int, default: Int): Int = store.get(id, default)
    operator fun set(id: Int, value: Int) { store.put(id, value) }
    inline fun update(id: Int, block: (Int) -> Int) { store[id] = block(store[id]) }
    fun ids(): IntArray {
        val result = IntArray(store.size())
        for (i in 0 until store.size()) result[i] = store.keyAt(i)
        return result
    }
    val size: Int get() = store.size()
    fun contains(id: Int): Boolean = store.indexOfKey(id) >= 0
    inline fun forEach(action: (Int, Int) -> Unit) {
        for (i in 0 until store.size()) action(store.keyAt(i), store.valueAt(i))
    }
    fun values(): List<Int> = (0 until store.size()).map { store.valueAt(it) }
    fun put(id: Int, value: Int) { store.put(id, value) }
    fun remove(id: Int) { store.remove(id) }
    fun clear() { store.clear() }
}

/**
 * 基本类型组件表：double 值，无装箱。
 * 用于 cultivation 等 double 字段。
 */
class DoubleComponentTable(initialCapacity: Int = 64) {
    // 使用 SparseArray<DoubleArray> 的包装来存 double
    // Android 没有 SparseDoubleArray，用 SparseArray<kotlin.Double> 装箱成本可接受
    // 因为弟子数 < 500，性能不是瓶颈
    private val store = SparseArray<Double>(initialCapacity)

    operator fun get(id: Int): Double = store[id] ?: 0.0
    fun getOrDefault(id: Int, default: Double): Double = store[id] ?: default
    operator fun set(id: Int, value: Double) { store.put(id, value) }
    inline fun update(id: Int, block: (Double) -> Double) {
        store[id] = block(store[id] ?: 0.0)
    }
    fun ids(): IntArray {
        val result = IntArray(store.size())
        for (i in 0 until store.size()) result[i] = store.keyAt(i)
        return result
    }
    val size: Int get() = store.size()
    fun contains(id: Int): Boolean = store.indexOfKey(id) >= 0
    inline fun forEach(action: (Int, Double) -> Unit) {
        for (i in 0 until store.size()) action(store.keyAt(i), store.valueAt(i))
    }
    fun values(): List<Double> = (0 until store.size()).map { store.valueAt(i) }
    fun put(id: Int, value: Double) { store.put(id, value) }
    fun remove(id: Int) { store.remove(id) }
    fun clear() { store.clear() }
}
```

### 步骤 2：建 DiscipleTables — Disciple 的组件表集合

**新建 1 个文件**，替代 Disciple data class 的内存存储。

**文件**：`android/core/domain/src/main/java/com/xianxia/sect/core/state/DiscipleTables.kt`

```kotlin
package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.*

/**
 * 弟子组件表集合。
 *
 * 替代旧的 `MutableGameState.disciples: List<Disciple>`。
 * 每张组件表存储所有弟子的某一种属性。
 *
 * 全部操作 O(log n)，无对象分配（int/double 基本类型零装箱）。
 *
 * 使用方式：
 *   val name = tables.names[id]
 *   tables.loyalty[id] = 90
 *   tables.cultivation.update(id) { it + rate * delta }
 *   for (id in tables.ids) { ... }
 */
class DiscipleTables {

    // === 标识 ===
    val ids = mutableListOf<Int>()          // 所有弟子 ID 的有序列表（遍历用）

    // === 基础信息（ComponentTable<String>） ===
    val names = ComponentTable<String>()          // id → name
    val surnames = ComponentTable<String>()       // id → surname
    val genders = ComponentTable<String>()        // id → "male"/"female"
    val portraitRes = ComponentTable<String>()    // id → 头像资源
    val discipleTypes = ComponentTable<String>()  // id → "outer"/"inner"/"elder"
    val spiritRootTypes = ComponentTable<String>()// id → "metal"/"fire"/...
    val slotIds = IntComponentTable()             // id → slot_id (持久化用)

    // === 境界与修为（Int/Double 基本类型表） ===
    val realms = IntComponentTable()              // id → realm (9=练气 ... 0=仙人)
    val realmLayers = IntComponentTable()         // id → layer (1-9)
    val cultivations = DoubleComponentTable()     // id → cultivation progress
    val ages = IntComponentTable()                // id → age
    val lifespans = IntComponentTable()           // id → lifespan
    val isAlive = IntComponentTable()             // id → 0/1 (用 Int 避免 Boolean 装箱)
    val soulPowers = IntComponentTable()          // id → soulPower

    // === 修炼加速 ===
    val cultivationSpeedBonuses = DoubleComponentTable()
    val cultivationSpeedDurations = IntComponentTable()

    // === 自动行为 ===
    val autoLearnFromWarehouse = IntComponentTable()   // id → 0/1
    val autoEquipFromWarehouse = IntComponentTable()   // id → 0/1

    // === 列表类型（ComponentTable<List<T>>） ===
    val manualIds = ComponentTable<List<String>>()        // id → [manualId1, ...]
    val talentIds = ComponentTable<List<String>>()        // id → [talentId1, ...]
    val manualMasteries = ComponentTable<Map<String, Int>>()

    // === 状态 ===
    val statuses = ComponentTable<DiscipleStatus>()
    val statusData = ComponentTable<Map<String, String>>()

    // === 战斗属性（窄表） ===
    // 旧架构：CombatAttributes（18 个字段） → 新架构：18 张窄表
    val baseHps = IntComponentTable()
    val baseMps = IntComponentTable()
    val basePhysicalAttacks = IntComponentTable()
    val baseMagicAttacks = IntComponentTable()
    val basePhysicalDefenses = IntComponentTable()
    val baseMagicDefenses = IntComponentTable()
    val baseSpeeds = IntComponentTable()
    val hpVariances = IntComponentTable()
    val mpVariances = IntComponentTable()
    val physicalAttackVariances = IntComponentTable()
    val magicAttackVariances = IntComponentTable()
    val physicalDefenseVariances = IntComponentTable()
    val magicDefenseVariances = IntComponentTable()
    val speedVariances = IntComponentTable()
    val totalCultivations = ComponentTable<Long>()
    val breakthroughCounts = IntComponentTable()
    val breakthroughFailCounts = IntComponentTable()
    val currentHps = IntComponentTable()
    val currentMps = IntComponentTable()

    // === 丹药效果 ===
    val pillPhysicalAttackBonuses = IntComponentTable()
    val pillMagicAttackBonuses = IntComponentTable()
    val pillPhysicalDefenseBonuses = IntComponentTable()
    val pillMagicDefenseBonuses = IntComponentTable()
    val pillHpBonuses = IntComponentTable()
    val pillMpBonuses = IntComponentTable()
    val pillSpeedBonuses = IntComponentTable()
    val pillEffectDurations = IntComponentTable()
    val pillCritRateBonuses = DoubleComponentTable()
    val pillCritEffectBonuses = DoubleComponentTable()
    val pillCultivationSpeedBonuses = DoubleComponentTable()
    val pillSkillExpSpeedBonuses = DoubleComponentTable()
    val pillNurtureSpeedBonuses = DoubleComponentTable()
    val activePillCategories = ComponentTable<String>()

    // === 装备 ===
    val weaponIds = ComponentTable<String>()
    val armorIds = ComponentTable<String>()
    val bootsIds = ComponentTable<String>()
    val accessoryIds = ComponentTable<String>()
    val weaponNurtures = ComponentTable<EquipmentNurtureData>()
    val armorNurtures = ComponentTable<EquipmentNurtureData>()
    val bootsNurtures = ComponentTable<EquipmentNurtureData>()
    val accessoryNurtures = ComponentTable<EquipmentNurtureData>()
    val storageBagItems = ComponentTable<List<StorageBagItem>>()
    val storageBagSpiritStones = ComponentTable<Long>()
    val discipleSpiritStones = IntComponentTable()
    val cultivationCompletionMonths = IntComponentTable()
    val cultivationCompletionPhases = IntComponentTable()
    val manualCompletionMonths = IntComponentTable()
    val manualCompletionPhases = IntComponentTable()
    val equipmentNurturingCompletionMonths = IntComponentTable()
    val equipmentNurturingCompletionPhases = IntComponentTable()

    // === 社交 ===
    val partnerIds = ComponentTable<String?>()       // nullable
    val partnerSectIds = ComponentTable<String?>()
    val parentId1s = ComponentTable<String?>()
    val parentId2s = ComponentTable<String?>()
    val lastChildYears = IntComponentTable()
    val childBirthMonths = ComponentTable<Int?>()    // nullable
    val griefEndYears = ComponentTable<Int?>()

    // === 技能属性 ===
    val intelligences = IntComponentTable()
    val charms = IntComponentTable()
    val loyalties = IntComponentTable()
    val comprehensions = IntComponentTable()
    val artifactRefinings = IntComponentTable()
    val pillRefinings = IntComponentTable()
    val spiritPlantings = IntComponentTable()
    val minings = IntComponentTable()
    val teachings = IntComponentTable()
    val moralities = IntComponentTable()
    val salaryPaidCounts = IntComponentTable()
    val salaryMissedCounts = IntComponentTable()

    // === 使用追踪 ===
    val usedFunctionalPillTypes = ComponentTable<List<String>>()
    val usedExtendLifePillIds = ComponentTable<List<String>>()
    val recruitedMonths = IntComponentTable()
    val hasReviveEffects = IntComponentTable()    // 0/1
    val hasClearAllEffects = IntComponentTable()  // 0/1

    // === 弟子总数 ===
    val count: Int get() = ids.size

    /* ================================================================
     * 核心 API
     * ================================================================ */

    /**
     * 添加一个新弟子。所有组件表同时插入一行。
     */
    fun insert(disciple: Disciple) {
        val id = disciple.id.toInt()
        ids.add(id)

        names[id] = disciple.name
        surnames[id] = disciple.surname
        genders[id] = disciple.gender
        portraitRes[id] = disciple.portraitRes
        discipleTypes[id] = disciple.discipleType
        spiritRootTypes[id] = disciple.spiritRootType
        slotIds[id] = disciple.slotId

        realms[id] = disciple.realm
        realmLayers[id] = disciple.realmLayer
        cultivations[id] = disciple.cultivation
        ages[id] = disciple.age
        lifespans[id] = disciple.lifespan
        isAlive[id] = if (disciple.isAlive) 1 else 0
        soulPowers[id] = disciple.soulPower

        cultivationSpeedBonuses[id] = disciple.cultivationSpeedBonus
        cultivationSpeedDurations[id] = disciple.cultivationSpeedDuration

        autoLearnFromWarehouse[id] = if (disciple.autoLearnFromWarehouse) 1 else 0
        autoEquipFromWarehouse[id] = if (disciple.equipment.autoEquipFromWarehouse) 1 else 0

        manualIds[id] = disciple.manualIds
        talentIds[id] = disciple.talentIds
        manualMasteries[id] = disciple.manualMasteries

        statuses[id] = disciple.status
        statusData[id] = disciple.statusData

        // 战斗属性
        val c = disciple.combat
        baseHps[id] = c.baseHp; baseMps[id] = c.baseMp
        basePhysicalAttacks[id] = c.basePhysicalAttack
        baseMagicAttacks[id] = c.baseMagicAttack
        basePhysicalDefenses[id] = c.basePhysicalDefense
        baseMagicDefenses[id] = c.baseMagicDefense
        baseSpeeds[id] = c.baseSpeed
        hpVariances[id] = c.hpVariance; mpVariances[id] = c.mpVariance
        physicalAttackVariances[id] = c.physicalAttackVariance
        magicAttackVariances[id] = c.magicAttackVariance
        physicalDefenseVariances[id] = c.physicalDefenseVariance
        magicDefenseVariances[id] = c.magicDefenseVariance
        speedVariances[id] = c.speedVariance
        totalCultivations[id] = c.totalCultivation
        breakthroughCounts[id] = c.breakthroughCount
        breakthroughFailCounts[id] = c.breakthroughFailCount
        currentHps[id] = c.currentHp; currentMps[id] = c.currentMp

        // 丹药效果
        val p = disciple.pillEffects
        pillPhysicalAttackBonuses[id] = p.pillPhysicalAttackBonus
        pillMagicAttackBonuses[id] = p.pillMagicAttackBonus
        pillPhysicalDefenseBonuses[id] = p.pillPhysicalDefenseBonus
        pillMagicDefenseBonuses[id] = p.pillMagicDefenseBonus
        pillHpBonuses[id] = p.pillHpBonus; pillMpBonuses[id] = p.pillMpBonus
        pillSpeedBonuses[id] = p.pillSpeedBonus
        pillEffectDurations[id] = p.pillEffectDuration
        pillCritRateBonuses[id] = p.pillCritRateBonus
        pillCritEffectBonuses[id] = p.pillCritEffectBonus
        pillCultivationSpeedBonuses[id] = p.pillCultivationSpeedBonus
        pillSkillExpSpeedBonuses[id] = p.pillSkillExpSpeedBonus
        pillNurtureSpeedBonuses[id] = p.pillNurtureSpeedBonus
        activePillCategories[id] = p.activePillCategory

        // 装备
        val e = disciple.equipment
        weaponIds[id] = e.weaponId; armorIds[id] = e.armorId
        bootsIds[id] = e.bootsId; accessoryIds[id] = e.accessoryId
        weaponNurtures[id] = e.weaponNurture
        armorNurtures[id] = e.armorNurture
        bootsNurtures[id] = e.bootsNurture
        accessoryNurtures[id] = e.accessoryNurture
        storageBagItems[id] = e.storageBagItems
        storageBagSpiritStones[id] = e.storageBagSpiritStones
        discipleSpiritStones[id] = e.spiritStones
        cultivationCompletionMonths[id] = disciple.cultivationCompletionMonth
        cultivationCompletionPhases[id] = disciple.cultivationCompletionPhase
        manualCompletionMonths[id] = disciple.manualCompletionMonth
        manualCompletionPhases[id] = disciple.manualCompletionPhase
        equipmentNurturingCompletionMonths[id] = disciple.equipmentNurturingCompletionMonth
        equipmentNurturingCompletionPhases[id] = disciple.equipmentNurturingCompletionPhase

        // 社交
        val s = disciple.social
        partnerIds[id] = s.partnerId; partnerSectIds[id] = s.partnerSectId
        parentId1s[id] = s.parentId1; parentId2s[id] = s.parentId2
        lastChildYears[id] = s.lastChildYear
        childBirthMonths[id] = s.childBirthMonth
        griefEndYears[id] = s.griefEndYear

        // 技能
        val sk = disciple.skills
        intelligences[id] = sk.intelligence; charms[id] = sk.charm
        loyalties[id] = sk.loyalty; comprehensions[id] = sk.comprehension
        artifactRefinings[id] = sk.artifactRefining; pillRefinings[id] = sk.pillRefining
        spiritPlantings[id] = sk.spiritPlanting; minings[id] = sk.mining
        teachings[id] = sk.teaching; moralities[id] = sk.morality
        salaryPaidCounts[id] = sk.salaryPaidCount; salaryMissedCounts[id] = sk.salaryMissedCount

        // 使用追踪
        val u = disciple.usage
        usedFunctionalPillTypes[id] = u.usedFunctionalPillTypes
        usedExtendLifePillIds[id] = u.usedExtendLifePillIds
        recruitedMonths[id] = u.recruitedMonth
        hasReviveEffects[id] = if (u.hasReviveEffect) 1 else 0
        hasClearAllEffects[id] = if (u.hasClearAllEffect) 1 else 0
    }

    /**
     * 从组件表组装一个完整的 Disciple 对象。
     * 仅在需要"完整弟子视图"时调用：
     *   - UI 渲染（Screen 层）
     *   - 序列化/持久化
     *   - 网络同步
     * 不应在 tick 热路径中调用。
     */
    fun assemble(id: Int): Disciple {
        return Disciple(
            id = id.toString(),
            slotId = slotIds[id],
            name = names[id],
            surname = surnames[id],
            realm = realms[id],
            realmLayer = realmLayers[id],
            cultivation = cultivations[id],
            spiritRootType = spiritRootTypes[id],
            age = ages[id],
            lifespan = lifespans[id],
            isAlive = isAlive[id] == 1,
            gender = genders[id],
            portraitRes = portraitRes[id],
            manualIds = manualIds[id],
            talentIds = talentIds[id],
            manualMasteries = manualMasteries[id],
            status = statuses[id],
            statusData = statusData[id],
            cultivationSpeedBonus = cultivationSpeedBonuses[id],
            cultivationSpeedDuration = cultivationSpeedDurations[id],
            discipleType = discipleTypes[id],
            autoLearnFromWarehouse = autoLearnFromWarehouse[id] == 1,
            soulPower = soulPowers[id],
            cultivationCompletionMonth = cultivationCompletionMonths[id],
            cultivationCompletionPhase = cultivationCompletionPhases[id],
            manualCompletionMonth = manualCompletionMonths[id],
            manualCompletionPhase = manualCompletionPhases[id],
            equipmentNurturingCompletionMonth = equipmentNurturingCompletionMonths[id],
            equipmentNurturingCompletionPhase = equipmentNurturingCompletionPhases[id],
            combat = CombatAttributes(
                baseHp = baseHps[id], baseMp = baseMps[id],
                basePhysicalAttack = basePhysicalAttacks[id],
                baseMagicAttack = baseMagicAttacks[id],
                basePhysicalDefense = basePhysicalDefenses[id],
                baseMagicDefense = baseMagicDefenses[id],
                baseSpeed = baseSpeeds[id],
                hpVariance = hpVariances[id], mpVariance = mpVariances[id],
                physicalAttackVariance = physicalAttackVariances[id],
                magicAttackVariance = magicAttackVariances[id],
                physicalDefenseVariance = physicalDefenseVariances[id],
                magicDefenseVariance = magicDefenseVariances[id],
                speedVariance = speedVariances[id],
                totalCultivation = totalCultivations[id],
                breakthroughCount = breakthroughCounts[id],
                breakthroughFailCount = breakthroughFailCounts[id],
                currentHp = currentHps[id], currentMp = currentMps[id]
            ),
            pillEffects = PillEffects(
                pillPhysicalAttackBonus = pillPhysicalAttackBonuses[id],
                pillMagicAttackBonus = pillMagicAttackBonuses[id],
                pillPhysicalDefenseBonus = pillPhysicalDefenseBonuses[id],
                pillMagicDefenseBonus = pillMagicDefenseBonuses[id],
                pillHpBonus = pillHpBonuses[id], pillMpBonus = pillMpBonuses[id],
                pillSpeedBonus = pillSpeedBonuses[id],
                pillEffectDuration = pillEffectDurations[id],
                pillCritRateBonus = pillCritRateBonuses[id],
                pillCritEffectBonus = pillCritEffectBonuses[id],
                pillCultivationSpeedBonus = pillCultivationSpeedBonuses[id],
                pillSkillExpSpeedBonus = pillSkillExpSpeedBonuses[id],
                pillNurtureSpeedBonus = pillNurtureSpeedBonuses[id],
                activePillCategory = activePillCategories[id]
            ),
            equipment = EquipmentSet(
                weaponId = weaponIds[id], armorId = armorIds[id],
                bootsId = bootsIds[id], accessoryId = accessoryIds[id],
                weaponNurture = weaponNurtures[id],
                armorNurture = armorNurtures[id],
                bootsNurture = bootsNurtures[id],
                accessoryNurture = accessoryNurtures[id],
                autoEquipFromWarehouse = autoEquipFromWarehouse[id] == 1,
                storageBagItems = storageBagItems[id],
                storageBagSpiritStones = storageBagSpiritStones[id],
                spiritStones = discipleSpiritStones[id]
            ),
            social = SocialData(
                partnerId = partnerIds[id], partnerSectId = partnerSectIds[id],
                parentId1 = parentId1s[id], parentId2 = parentId2s[id],
                lastChildYear = lastChildYears[id],
                childBirthMonth = childBirthMonths[id],
                griefEndYear = griefEndYears[id]
            ),
            skills = SkillStats(
                intelligence = intelligences[id], charm = charms[id],
                loyalty = loyalties[id], comprehension = comprehensions[id],
                artifactRefining = artifactRefinings[id],
                pillRefining = pillRefinings[id],
                spiritPlanting = spiritPlantings[id],
                mining = minings[id], teaching = teachings[id],
                morality = moralities[id],
                salaryPaidCount = salaryPaidCounts[id],
                salaryMissedCount = salaryMissedCounts[id]
            ),
            usage = UsageTracking(
                usedFunctionalPillTypes = usedFunctionalPillTypes[id],
                usedExtendLifePillIds = usedExtendLifePillIds[id],
                recruitedMonth = recruitedMonths[id],
                hasReviveEffect = hasReviveEffects[id] == 1,
                hasClearAllEffect = hasClearAllEffects[id] == 1
            )
        )
    }

    /** 组装全部弟子的 List<Disciple>（用于序列化、旧 API 兼容） */
    fun assembleAll(): List<Disciple> = ids.map { assemble(it) }
}
```

### 步骤 3：替代 MutableGameState 中的 `List<Disciple>`

**修改文件**：`android/core/domain/src/main/java/com/xianxia/sect/core/state/MutableGameState.kt`

```kotlin
// 改后
data class MutableGameState(
    var gameData: GameData,
    var discipleTables: DiscipleTables,        // ← 替代 List<Disciple>
    var equipmentStacks: List<EquipmentStack>,
    var equipmentInstances: List<EquipmentInstance>,
    var manualStacks: List<ManualStack>,
    var manualInstances: List<ManualInstance>,
    var pills: List<Pill>,
    var materials: List<Material>,
    var herbs: List<Herb>,
    var seeds: List<Seed>,
    var storageBags: List<StorageBag>,
    var teams: List<ExplorationTeam>,
    var battleLogs: List<BattleLog>,
    var isPaused: Boolean,
    var isLoading: Boolean,
    var isSaving: Boolean,
    var pendingNotification: GameNotification? = null,
    var isSettlementShadow: Boolean = false
)
```

### 步骤 4：改造 GameStateStore

**修改接口**：`GameStateStore.kt` — 新增 `discipleTables`，保留 `disciples: StateFlow<List<Disciple>>`（UI 兼容）

```kotlin
// 新增
val discipleTables: DiscipleTables  // Engine/Service 层直接操作组件表

// 保留（由 discipleTables.assembleAll() 派生，UI 层兼容）
val disciples: StateFlow<List<Disciple>>
```

**修改实现**：`GameStateStoreImpl.kt`

```kotlin
private val _discipleTables = DiscipleTables()
override val discipleTables: DiscipleTables get() = _discipleTables

// createShadow 改为拷贝 discipleTables 快照
override fun createShadow(): MutableGameState {
    // 深拷贝组件表（所有值类型表是值拷贝，引用类型表需 copy）
    val tablesCopy = DiscipleTables()
    // ... 逐表复制 ...
    return MutableGameState(
        gameData = _gameDataFlow.value,
        discipleTables = tablesCopy,
        // ...
    )
}

// swapFromShadow 改为一对一替换
override suspend fun swapFromShadow(shadow: MutableGameState) {
    update {
        this.discipleTables = shadow.discipleTables
        // ...
    }
}

// disciples StateFlow 改为从 discipleTables 派生
override val disciples: StateFlow<List<Disciple>> = _disciplesFlow
// 在每个 tick/settlement 结束时发射
```

### 步骤 5：改造热路径

**这是最大的步骤——约 37 个文件，292 处改动。** 以下是最关键的改动模式。

#### 5.1 消除 copyWith

```kotlin
// 改前（DiscipleService.kt，DiscipleFacadeImpl.kt 等）
val updated = disciple.copyWith(loyalty = newLoyalty)
shadow.disciples = shadow.disciples.map { if (it.id == targetId) updated else it }

// 改后
tables.loyalty[targetId.toInt()] = newLoyalty
// 不需要 map，不需要 copy，不需要分配新 List
```

#### 5.2 消除 .find

```kotlin
// 改前（GameEngineCoordination.kt，14 处）
val d = disciples.find { it.id == discipleId }

// 改后
val id = discipleId.toInt()
val name = tables.names[id]
val realm = tables.realms[id]
// ... 或只取需要的字段
```

#### 5.3 消除 tick 路径的 copyWith

```kotlin
// 改前（CultivationCore.kt 中）
currentDisciples = currentDisciples.map { d ->
    d.copyWith(
        cultivation = (d.cultivation + rate * delta).coerceIn(0.0, d.maxCultivation),
        loyalty = (d.skills.loyalty + loyaltyDelta).coerceAtLeast(0)
    )
}

// 改后
val tables = stateStore.discipleTables
for (id in tables.ids) {
    if (tables.isAlive[id] == 0) continue
    val rate = calculateCultivationRate(id, tables, data)
    val loyaltyDelta = calculateLoyaltyDelta(id, tables, data)
    tables.cultivations.update(id) { (it + rate * delta).coerceIn(0.0, maxCultivation(id, tables)) }
    if (loyaltyDelta != 0) tables.loyalties.update(id) { (it + loyaltyDelta).coerceAtLeast(0) }
}
```

#### 5.4 消除结算路径的并行 + copyWith

```kotlin
// 改前（SettlementCoordinator，async(Dispatchers.Default) + copyWith）
async(Dispatchers.Default) {
    chunk.map { (index, d) ->
        d.copy(cultivation = ...)
        d.copyWith(loyalty = ...)
    }
}

// 改后
// 去掉 async，改为串行，直接在组件表上操作
for (id in tables.ids) {
    if (tables.isAlive[id] == 0 || id == focusedId.toInt()) continue
    if (!cache.shouldProcess(id)) continue
    tables.cultivations.update(id) { (it + rate * monthSeconds).coerceIn(0.0, maxCultivation(id, tables)) }
    tables.loyalties.update(id) { (it + loyaltyDelta).coerceAtLeast(0) }
}
```

#### 5.5 消除 associBy

```kotlin
// 改前
val allDisciples = currentDisciples.associateBy { it.id }

// 改后
// 不需要了！组件表本身就是按 ID 索引的
val name = tables.names[targetId.toInt()]
```

### 步骤 6：删除死代码

**删除以下内容：**

| 文件 | 删除内容 |
|------|---------|
| `Disciple.kt` 第 197-419 行 | **整个 `copyWith` 方法（222 行）** |
| `CultivationCore.kt` 第 36-77 行 | **6 个双重访问器** |
| `CultivationCore.kt` | `currentGameData`、`currentDisciples` 等 6 个属性 |
| `SettlementCoordinator.kt` | `async(Dispatchers.Default)` 并行处理代码 |
| `DiscipleDelegates.kt` | 委托属性文件（如果存在）——不再需要，改为直接读组件表 |

**Disciple.kt 保留的内容：**
- 数据类声明（作为 `assemble()` 的输出类型和 Room Entity）
- 计算属性（`canCultivate`, `realmName`, `maxCultivation` 等）——这些只在 UI 渲染时需要
- Room 注解（供持久化使用）
- `CombatAttributes`、`PillEffects`、`EquipmentSet`、`SocialData`、`SkillStats`、`UsageTracking` — 作为序列化/UI 的数据容器保留

### 步骤 7：改造 Room 持久化

Disciple 仍然是一个 Room Entity（`assemble()` 的产物）。**但数据库表拆分为独立的组件表。**

**新建 DAO**：`DiscipleTablesDao.kt`

```kotlin
@Dao
interface DiscipleTablesDao {
    // 批量读取：一次事务中加载全部弟子
    @Transaction
    @Query("SELECT * FROM disciples WHERE slot_id = :slotId")
    suspend fun loadAll(slotId: Int): List<Disciple>

    // 批量写入：替换全部弟子
    @Transaction
    @Query("DELETE FROM disciples WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(disciples: List<Disciple>)
}
```

**持久化流程**：
```
保存：DiscipleTables → assembleAll() → List<Disciple> → DAO.insertAll()
加载：DAO.loadAll() → List<Disciple> → DiscipleTables.insert() 逐条
```

注意：`assembleAll()` 和 `insert()` 仅在保存/加载时调用，不在热路径中。

### 步骤 8：改造 GameData

**从 GameData 中移除与 Disciple 表存在冗余的字段。** GameData 只保留宗门级别的聚合数据和配置。

比如：
- `recruitList: List<Disciple>` → 移入 `DiscipleTables`（新增 `isRecruited` 标记列）
- `aiSectDisciples: Map<String, List<Disciple>>` → 每个 AI 宗门维护自己的 `DiscipleTables`

这步约减少 GameData 200+ 行，让它从 986 行降到 ~600 行。

### 步骤 9：删除 Settlement 机制中的 GameData copy

`@SettlementStrategy` 注解在 Disciple 层面不再需要——因为组件表是**原地修改**的，不存在"Shadow 结算时 merge 两个 GameData"的问题。

SettlementCoordinator 直接修改 `shadow.discipleTables`（shadow 是一个副本，包含独立的 DiscipleTables 实例）。`swapFromShadow` 时直接替换整个 `discipleTables`，不需要逐字段 merge。

`@SettlementStrategy` 仅对 GameData 中的非 Disciple 字段仍有意义（如 `worldMapSects`、`alliances` 等）。

### 步骤 10：改 UI 层

UI 层改动**最小**。因为：
1. `StateFlow<List<Disciple>>` 接口保留
2. `Disciple` 数据类保留（作为 `assemble()` 的产物）
3. 所有 Compose `key = { it.id }` 仍然生效

唯一的改动：将所有 `.find { it.id == }` 替换为 `discipleTables.get(id)`（在 ViewModel 层）。

**涉及文件**（feature/game 目录下的 ViewModel + Dialog + Tab）：

| 文件 | 改动处 | 改动方式 |
|------|--------|---------|
| `DiscipleViewModel.kt` | ~6 处 `.find` | `tables.xxx[id]` |
| `DiscipleDetailScreen.kt` | ~5 处 | `tables.xxx[id]` |
| `WarehouseDiscipleSelectDialog.kt` | ~6 处 | `tables.xxx[id]` |
| `ProductionViewModel.kt` | ~3 处 | `tables.xxx[id]` |
| `SectViewModel.kt` | ~3 处 | `tables.xxx[id]` |
| 其余 ~20 个 Dialog | 少量 | 按需 |

### 步骤 11：迁移兼容

**旧存档兼容性**：加载旧存档时：
1. 从 `disciples` 表读出 `List<Disciple>`（旧格式）
2. 逐条 `discipleTables.insert(d)`（加载到新内存结构）
3. 删除内存中的 `List<Disciple>` 副本
4. 下次保存时，`assembleAll()` 输出仍为 `List<Disciple>`，格式不变

**DB Migration 不需要**——Disciple 的 Room Entity 定义不变，表结构不变。仅内存表示改变。

### 步骤 12：测试

新增测试文件：

**文件**：`android/core/domain/src/test/java/com/xianxia/sect/core/state/DiscipleTablesTest.kt`

```kotlin
class DiscipleTablesTest {
    @Test
    fun `insert and retrieve basic fields`() { /* ... */ }
    @Test
    fun `update loyalty directly`() { /* ... */ }
    @Test
    fun `update cultivation in-place`() { /* ... */ }
    @Test
    fun `assemble full Disciple from tables`() { /* ... */ }
    @Test
    fun `assembleAll matches input`() { /* ... */ }
    @Test
    fun `bulk update all disciples cult progress`() { /* tick simulation */ }
    @Test
    fun `remove disciple cleans all tables`() { /* ... */ }
}
```

**回归测试**：确保所有现有测试通过（`./gradlew.bat test`）。

---

## 五、改动文件清单

| # | 类别 | 文件 | 操作 |
|---|------|------|------|
| 1 | 新建 | `core/domain/.../state/ComponentTable.kt` | 新建 |
| 2 | 新建 | `core/domain/.../state/DiscipleTables.kt` | 新建 |
| 3 | 修改 | `core/domain/.../state/MutableGameState.kt` | `List<Disciple>` → `DiscipleTables` |
| 4 | 修改 | `core/domain/.../state/GameStateStore.kt` | 新增 `discipleTables` + 保留 `disciples` |
| 5 | 修改 | `app/.../state/GameStateStoreImpl.kt` | 实现组件表存储 + Shadow |
| 6 | 修改 | `core/engine/.../domain/settlement/SettlementCoordinator.kt` | 直接用组件表，去 async + copyWith |
| 7 | 修改 | `core/engine/.../domain/settlement/SettlementCache.kt` | 适配组件表索引 |
| 8 | 修改 | `core/engine/.../service/CultivationCore.kt` | 去双重访问器，直接操作组件表 |
| 9 | 修改 | `core/engine/.../GameEngineCoordination.kt` | ~14 处 .find → 组件表 |
| 10 | 修改 | `core/engine/.../GameEngineBattleOps.kt` | ~8 处 .find → 组件表 |
| 11 | 修改 | `core/engine/.../service/CultivationEventProcessor.kt` | ~4 处 .find → 组件表 |
| 12 | 修改 | `core/engine/.../service/CultivationSettlement.kt` | ~9 处 → 组件表 |
| 13 | 修改 | `core/engine/.../service/DiscipleLifecycleProcessor.kt` | ~2 处 → 组件表 |
| 14 | 修改 | `core/engine/.../service/DiscipleBreakthroughHandler.kt` | ~2 处 → 组件表 |
| 15 | 修改 | `core/engine/.../domain/disciple/DiscipleService.kt` | ~13 处 .find + .map → 组件表 |
| 16 | 修改 | `core/engine/.../domain/disciple/DiscipleFacadeImpl.kt` | ~19 处 → 组件表 |
| 17 | 修改 | `core/engine/.../domain/disciple/DiscipleEquipmentManager.kt` | ~6 处 → 组件表 |
| 18 | 修改 | `core/engine/.../domain/disciple/DiscipleManualManager.kt` | ~4 处 → 组件表 |
| 19 | 修改 | `core/engine/.../domain/disciple/DisciplePillManager.kt` | ~3 处 → 组件表 |
| 20 | 修改 | `core/engine/.../domain/inventory/InventoryFacadeImpl.kt` | 装备查找 → 组件表 |
| 21 | 修改 | `core/engine/.../service/CaveExplorationProcessor.kt` | ~8 处 → 组件表 |
| 22 | 修改 | `core/engine/.../domain/exploration/ExplorationService.kt` | ~2 处 → 组件表 |
| 23 | 修改 | `core/engine/.../domain/battle/CombatService.kt` | 弟子战斗属性 → 组件表 |
| 24 | 修改 | `core/engine/.../domain/battle/BattleSystem.kt` | ~4 处 → 组件表 |
| 25 | 修改 | `core/engine/.../domain/battle/AISectAttackManager.kt` | 弟子查找 → 组件表 |
| 26 | 修改 | `core/engine/.../domain/battle/AISectGarrisonManager.kt` | 弟子查找 → 组件表 |
| 27 | 修改 | `core/engine/.../domain/diplomacy/DiplomacyService.kt` | ~10 处 → 组件表 |
| 28 | 修改 | `core/engine/.../domain/diplomacy/DiplomacyFacadeImpl.kt` | ~1 处 → 组件表 |
| 29 | 修改 | `core/engine/.../domain/building/BuildingService.kt` | ~2 处 → 组件表 |
| 30 | 修改 | `core/engine/.../system/PartnerSystem.kt` | 弟子查找 → 组件表 |
| 31 | 修改 | `core/engine/.../system/ChildBirthSystem.kt` | 弟子查找 → 组件表 |
| 32 | 修改 | `core/engine/.../service/ProductionSubsystem.kt` | 弟子查找 → 组件表 |
| 33 | 修改 | `core/engine/.../usecase/ElderManagementUseCase.kt` | 弟子查找 → 组件表 |
| 34 | 修改 | `core/engine/.../LazyEvaluationDispatcher.kt` | 弟子遍历 → 组件表 |
| 35 | 修改 | `app/.../usecase/RecruitDiscipleUseCase.kt` | `discipleTables.insert()` |
| 36 | 修改 | `core/engine/.../service/DiplomacyEventProcessor.kt` | ~3 处 → 组件表 |
| 37 | 修改 | `core/domain/.../model/Disciple.kt` | **删除 copyWith（第 197-419 行）** |
| 38 | 修改 | `core/domain/.../model/GameData.kt` | 移除重复字段 |
| 39 | 修改 | `app/.../core/state/GameStateStoreImpl.kt` | createShadow / swapFromShadow 适配 |
| 40 | 修改 | `core/data/.../DiscipleRepository.kt` | 加载/保存适配 |
| 41 | 修改 | `core/data/.../SaveDataConverter.kt` | 序列化适配 |
| 42 | 修改 | 全部 feature/game UI 文件（~30 个） | `.find { it.id == }` → `tables[id]` |
| 43 | 新建 | `core/domain/src/test/.../DiscipleTablesTest.kt` | 组件表单元测试 |
| 44 | 新建 | `core/domain/src/test/.../ComponentTableTest.kt` | 基础设施测试 |

---

## 六、验证计划

```bash
# 1. 编译
cd android && ./gradlew.bat compileReleaseKotlin
# → BUILD SUCCESSFUL

# 2. 全部单测
cd android && ./gradlew.bat test
# → 全部通过

# 3. Lint
cd android && ./gradlew.bat lintRelease
# → 无新增警告

# 4. 手动回归
# - 新游戏 → 弟子列表正常
# - 修炼 tick → 数值正确
# - 月度结算 → 数值与 tick 一致
# - 弟子详情 → 属性正确
# - 招募弟子 → insert 正常
# - 剔除弟子 → remove 正常
# - 存档/读档 → 数据一致
# - 旧存档兼容 → 能正常加载
```

---

## 七、效果评估

| 指标 | 旧架构 | 新架构 | 改进 |
|------|--------|--------|------|
| 按 ID 查找 | O(n) 遍历 284 次 | O(log n) 直接索引 | **~100x（n=300时）** |
| 单字段修改 | copy 97 字段 + 分配新对象 | 写一个数组元素 | **~97x 内存分配减少** |
| tick 弟子更新 | `.map {}` 全量 List 重建 | `for (id in ids)` 原地更新 | **零 List 分配** |
| copyWith 调用 | 87 次 × 26 文件 | **0 次** | **彻底消除** |
| GameData.copy | 每次修改复制 60+ 字段 | Disciple 字段移出，减少 ~30 | **~2x 更快** |
| Settlement 并行 | async + Volatile 竞态 | 单线程串行 | **线程安全** |
| 状态分层 | 4 层 StateFlow | 2 层（discipleTables + unified） | **~2x 更少** |
| 结算/tick 重复 | 5 处逻辑重复 | 同一函数操作同一组件表 | **零重复** |
