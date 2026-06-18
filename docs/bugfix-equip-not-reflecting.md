# 弟子装备穿不上(UI不刷新) — 根因分析与修复方案

> 日期: 2026-06-18
> 状态: 根因已确认,修复方案已对齐,待实施
> 影响: v4.0.01 起(三大系统重构 1e58fe27 引入)

---

## 一、用户报告的现象

1. 点击弟子装备槽位 → 弹出选择武器界面 → 选择装备 → 点"确认装备" → 界面自动关闭
2. **弟子装备槽位上没有显示该装备**
3. 打开仓库 → **仓库里也没有了该装备**
4. 再次点击弟子槽位打开选择界面 → **该装备还显示在列表里**(陈旧缓存)

> 新建游戏立刻复现,不限于旧存档。

---

## 二、根因分析

### 2.1 一句话总结

**三大系统重构(1e58fe27)引入的 `assembleAll` 脏检测优化用了错误的判据(`ids.hashCode()`),导致所有"原地修改 discipleTables 字段"的操作(穿/卸装备、用丹药、突破等)执行成功但 UI 不刷新。**

### 2.2 证据链

#### 写入确实发生了(仓库没了)

`DiscipleService.equipEquipment`(:628-643)的 stack 分支:

```kotlin
// line 632: 扣减仓库堆叠数量 — EntityStore.update 分配新 List 引用
equipmentStacks.update(equipmentId) { it.copy(quantity = it.quantity - 1) }
// line 636: 创建装备实例并加入 instances — EntityStore.add 分配新 List 引用
equipmentInstances = equipmentInstances + equippedItem
// line 638: 写入弟子槽位 — 原地修改 discipleTables.weaponIds[id]
discipleTables.weaponIds[id] = equippedId
```

提交阶段(`GameStateStoreImpl.kt:779-780`):
```kotlin
if (reusableMutableState.equipmentStacks.items !== curES) _equipmentStacksFlow.value = ...  // ✅ 检测到变化,仓库 UI 刷新
if (reusableMutableState.equipmentInstances.items !== curEI) _equipmentInstancesFlow.value = ...  // ✅ 检测到变化
```

EntityStore 的所有写操作(add/remove/update)都**分配新 List 引用**(`EntityStore.kt:108,115,123`),所以 `!==` 引用比较检测通过 → 仓库 Flow 更新 → "仓库没了该装备"。

#### UI 不刷新(弟子槽位没变)

提交阶段对 discipleAggregates 的检测(`GameStateStoreImpl.kt:791-796`):

```kotlin
val disciplesChanged = reusableMutableState.discipleTables !== _discipleTables   // ① 引用比较
val fp = reusableMutableState.discipleTables.ids.hashCode()                       // ② 只看弟子 id 集合
if (disciplesChanged || fp != lastAssembledIdsFingerprint) {                      // ③ 门控
    _disciplesFlow.value = reusableMutableState.discipleTables.assembleAll()     // ④ UI 刷新
    lastAssembledIdsFingerprint = fp
}
```

穿戴装备时 `discipleTables.weaponIds[id] = equippedId` 是**原地修改** ComponentTable 内部字段:
- ① `discipleTables` 引用没换(还是同一个对象) → `disciplesChanged = false`
- ② 穿戴不改弟子 id 集合 → `fp`(ids.hashCode())不变 → 门控 false
- → `_disciplesFlow` 不更新 → UI 看不到新装备

### 2.3 重构前后对比

重构前(1e58fe27 之前,无条件 assembleAll):
```kotlin
val disciplesChanged = reusableMutableState.discipleTables !== _discipleTables
_disciplesFlow.value = reusableMutableState.discipleTables.assembleAll()  // ← 无条件刷新
```

重构后(1e58fe27,加了 fp 门控):
```kotlin
val fp = reusableMutableState.discipleTables.ids.hashCode()  // ← 错误的判据
if (disciplesChanged || fp != lastAssembledIdsFingerprint) { // ← 条件不满足时跳过
    _disciplesFlow.value = reusableMutableState.discipleTables.assembleAll()
}
```

### 2.4 影响范围

**不只是装备!** 所有原地修改 discipleTables 字段但不增删弟子的操作都被静默跳过 UI 刷新:

| 操作 | 修改的字段 | 是否被影响 |
|---|---|---|
| 手动穿/卸装备 | `weaponIds/armorIds/bootsIds/accessoryIds` | ✅ 被影响 |
| 自动穿/卸装备 | 同上 | ✅ 被影响 |
| 使用丹药 | `combat`/`pillEffects` 相关字段 | ✅ 被影响 |
| 实时突破 | `realms`/`realmLayers` | ✅ 被影响 |
| 修炼进度(实时路径) | `cultivations` | ✅ 被影响 |
| 分配/忘记功法 | `manualIds` | ✅ 被影响 |
| 增删弟子 | `ids` 列表 | ❌ 不受影响(指纹变化) |

### 2.5 已有的基础设施

`DiscipleTables` 里已经有一套**设计正确但未接全**的版本号脏检测机制:

```kotlin
// DiscipleTables.kt:21-26
@Volatile var mutationVersion: Long = 0
    private set
fun markMutated() { mutationVersion++ }
```

`markMutated()` 只在 4 个结构方法里被调用:
- `insert()` (:280) — 插入弟子
- `update(disciple)` (:391) — 全量更新弟子
- `remove()` (:560) — 删除弟子
- `clear()` (:608) — 清空所有

**遗漏了所有字段级原地写**(`weaponIds[id] = x`、`realms[id] = x`、`cultivations.update(id){...}` 等)。

而且提交门控(`GameStateStoreImpl.kt:792`)用的是 `ids.hashCode()`,**完全没用 `mutationVersion`**。

### 2.6 次要问题

**DiscipleViewModel.equipItem 吞掉 DomainResult**(`DiscipleViewModel.kt:182-191`):

```kotlin
fun equipItem(discipleId: String, equipmentId: String) {
    viewModelScope.launch {
        try {
            gameEngine.equipItem(discipleId, equipmentId)  // 返回 Unit,DomainResult 被丢弃
            showSuccess("装备成功")                         // ← 无论成败都显示"装备成功"
        } catch (e: Exception) { showError(...) }
    }
}
```

`GameEngineCoordination.kt:458` 中 `gameEngine.equipItem` 是 `suspend fun` 返回 Unit,
`DiscipleService.equipEquipment` 返回的 `DomainResult` 被完全丢弃。
境界不足/槽位冲突等失败时 UI 会显示"装备成功"。

---

## 三、修复方案: onWrite 回调下沉

### 3.1 设计思路

**目标**:保留 `assembleAll` 跳过优化,但用正确的脏检测判据替代错误的 `ids.hashCode()`。

**做法**:
1. 给三种 ComponentTable(`ComponentTable<T>`/`IntComponentTable`/`DoubleComponentTable`)加可选 `onWrite` 回调
2. `DiscipleTables` 初始化时把所有子表的 `onWrite` 指向自己的 `markMutated()`
3. `GameStateStoreImpl` 提交门控改用 `mutationVersion` 替代 `ids.hashCode()`
4. 业务代码零改动,字段级写自动 bump 版本号

### 3.2 需要修改的文件(共 3 个)

#### 文件 1: `ComponentTable.kt`
路径: `android/core/domain/src/main/java/com/xianxia/sect/core/state/ComponentTable.kt`

**改动内容**:给三种表加 `onWrite` 回调,在所有写方法(set/put/update/remove/clear)末尾调用。

```kotlin
// === ComponentTable<T> ===
class ComponentTable<T> @JvmOverloads constructor(initialCapacity: Int = 64) {
    @PublishedApi internal val store = SparseArray<T>(initialCapacity)
    
    /** 可选写入回调,由 DiscipleTables 注入以 bump mutationVersion */
    @JvmField var onWrite: (() -> Unit)? = null
    
    operator fun set(id: Int, value: T) { store.put(id, value); onWrite?.invoke() }
    inline fun update(id: Int, block: (T) -> T) { store[id] = block(store[id]); onWrite?.invoke() }
    fun put(id: Int, value: T) { store.put(id, value); onWrite?.invoke() }
    fun remove(id: Int) { store.remove(id); onWrite?.invoke() }
    fun clear() { store.clear(); onWrite?.invoke() }
    // 读取方法(get/getOrDefault/getOrNull/ids/size/contains/forEach/values)不加
}

// === IntComponentTable === 同样模式,在 set/update/put/remove/clear 末尾加 onWrite?.invoke()
// === DoubleComponentTable === 同样模式
```

> `onWrite` 默认 null,不影响纯表使用、测试、序列化。
> `?.invoke()` 在 null 时零开销,非 null 时可被 JIT 内联。

#### 文件 2: `DiscipleTables.kt`
路径: `android/core/domain/src/main/java/com/xianxia/sect/core/state/DiscipleTables.kt`

**改动内容**:
1. 添加一个 `bindOnWrite()` 私有方法,遍历所有子表设置 `onWrite = ::markMutated`
2. 在 `init` 块(或构造后立即)调用 `bindOnWrite()`
3. `deepCopy()` 出来的副本**不设置** `onWrite`(副本写不应影响原表的版本号)

```kotlin
class DiscipleTables {
    @Volatile var mutationVersion: Long = 0
        private set
    fun markMutated() { mutationVersion++ }
    
    // ... 所有子表声明保持不变 ...
    
    init { bindAllOnWrite() }
    
    /** 绑定所有子表的 onWrite → markMutated,确保字段级写自动 bump 版本号 */
    private fun bindAllOnWrite() {
        val cb: () -> Unit = ::markMutated
        // 基础信息
        names.onWrite = cb; surnames.onWrite = cb; genders.onWrite = cb
        portraitRes.onWrite = cb; discipleTypes.onWrite = cb; spiritRootTypes.onWrite = cb; slotIds.onWrite = cb
        // 境界与修为
        realms.onWrite = cb; realmLayers.onWrite = cb; cultivations.onWrite = cb
        ages.onWrite = cb; lifespans.onWrite = cb; isAlive.onWrite = cb; soulPowers.onWrite = cb
        // 修炼加速
        cultivationSpeedBonuses.onWrite = cb; cultivationSpeedDurations.onWrite = cb
        // 自动行为
        autoLearnFromWarehouse.onWrite = cb; autoEquipFromWarehouse.onWrite = cb
        // 列表类型
        manualIds.onWrite = cb; talentIds.onWrite = cb; manualMasteries.onWrite = cb
        // 状态
        statuses.onWrite = cb; statusData.onWrite = cb
        // 战斗属性 (19 个 IntComponentTable + 1 个 LongComponentTable)
        baseHps.onWrite = cb; baseMps.onWrite = cb
        basePhysicalAttacks.onWrite = cb; baseMagicAttacks.onWrite = cb
        basePhysicalDefenses.onWrite = cb; baseMagicDefenses.onWrite = cb
        baseSpeeds.onWrite = cb
        hpVariances.onWrite = cb; mpVariances.onWrite = cb
        physicalAttackVariances.onWrite = cb; magicAttackVariances.onWrite = cb
        physicalDefenseVariances.onWrite = cb; magicDefenseVariances.onWrite = cb
        speedVariances.onWrite = cb
        totalCultivations.onWrite = cb; breakthroughCounts.onWrite = cb
        breakthroughFailCounts.onWrite = cb
        currentHps.onWrite = cb; currentMps.onWrite = cb
        // 丹药效果
        pillPhysicalAttackBonuses.onWrite = cb; pillMagicAttackBonuses.onWrite = cb
        pillPhysicalDefenseBonuses.onWrite = cb; pillMagicDefenseBonuses.onWrite = cb
        pillHpBonuses.onWrite = cb; pillMpBonuses.onWrite = cb; pillSpeedBonuses.onWrite = cb
        pillEffectDurations.onWrite = cb; pillCritRateBonuses.onWrite = cb; pillCritEffectBonuses.onWrite = cb
        pillCultivationSpeedBonuses.onWrite = cb; pillSkillExpSpeedBonuses.onWrite = cb
        pillNurtureSpeedBonuses.onWrite = cb
        activePillCategories.onWrite = cb
        // 装备 (4 个 slot + nurtures + bag + spiritStones + completions)
        weaponIds.onWrite = cb; armorIds.onWrite = cb; bootsIds.onWrite = cb; accessoryIds.onWrite = cb
        weaponNurtures.onWrite = cb; armorNurtures.onWrite = cb; bootsNurtures.onWrite = cb; accessoryNurtures.onWrite = cb
        storageBagItems.onWrite = cb; storageBagSpiritStones.onWrite = cb; discipleSpiritStones.onWrite = cb
        cultivationCompletionMonths.onWrite = cb; cultivationCompletionPhases.onWrite = cb
        manualCompletionMonths.onWrite = cb; manualCompletionPhases.onWrite = cb
        equipmentNurturingCompletionMonths.onWrite = cb; equipmentNurturingCompletionPhases.onWrite = cb
        // 社交
        partnerIds.onWrite = cb; partnerSectIds.onWrite = cb
        parentId1s.onWrite = cb; parentId2s.onWrite = cb
        lastChildYears.onWrite = cb; childBirthMonths.onWrite = cb
        griefEndYears.onWrite = cb
        // 技能
        intelligences.onWrite = cb; charms.onWrite = cb; loyalties.onWrite = cb
        comprehensions.onWrite = cb; artifactRefinings.onWrite = cb; pillRefinings.onWrite = cb
        spiritPlantings.onWrite = cb; minings.onWrite = cb; teachings.onWrite = cb; moralities.onWrite = cb
        salaryPaidCounts.onWrite = cb; salaryMissedCounts.onWrite = cb
        // 使用记录
        usedFunctionalPillTypes.onWrite = cb; usedExtendLifePillIds.onWrite = cb
        recruitedMonths.onWrite = cb; hasReviveEffects.onWrite = cb; hasClearAllEffects.onWrite = cb
    }
    
    // deepCopy() 中创建的 copy 不调用 bindAllOnWrite() — 副本写不应 bump 原表版本号
    fun deepCopy(): DiscipleTables {
        val copy = DiscipleTables()
        // ... 现有的逐表拷贝代码保持不变 ...
        // copy 的 onWrite 保持 null(默认)
        return copy
    }
}
```

> **注意**: `insert()`/`update()`/`remove()`/`clear()` 里已有的 `markMutated()` 调用可以保留(双重 bump 无害),也可以在 bindAllOnWrite 后删掉以减少冗余。建议保留,因为 `insert` 里的 `ids.add(id)` 操作不经过 ComponentTable,需要显式 markMutated。

#### 文件 3: `GameStateStoreImpl.kt`
路径: `android/app/src/main/java/com/xianxia/sect/core/state/GameStateStoreImpl.kt`

**改动内容**:
1. 把 `lastAssembledIdsFingerprint: Int` 改为 `lastAssembledMutationVersion: Long`
2. 两处(update 和 updateAndReturn)把 `fp` 门控改为 `mutationVersion` 门控

**位置 1: 字段声明(:69)**
```kotlin
// 改前:
private var lastAssembledIdsFingerprint: Int = 0
// 改后:
private var lastAssembledMutationVersion: Long = 0
```

**位置 2: update() 提交阶段(:791-796)**
```kotlin
// 改前:
val disciplesChanged = reusableMutableState.discipleTables !== _discipleTables
val fp = reusableMutableState.discipleTables.ids.hashCode()
if (disciplesChanged || fp != lastAssembledIdsFingerprint) {
    _disciplesFlow.value = reusableMutableState.discipleTables.assembleAll()
    lastAssembledIdsFingerprint = fp
}

// 改后:
val disciplesChanged = reusableMutableState.discipleTables !== _discipleTables
val mutated = reusableMutableState.discipleTables.mutationVersion
if (disciplesChanged || mutated != lastAssembledMutationVersion) {
    _disciplesFlow.value = reusableMutableState.discipleTables.assembleAll()
    lastAssembledMutationVersion = mutated
}
```

**位置 3: updateAndReturn() 提交阶段(:936-941)**
```kotlin
// 改前:
val disciplesChanged = reusableMutableState.discipleTables !== _discipleTables
val fp = reusableMutableState.discipleTables.ids.hashCode()
if (disciplesChanged || fp != lastAssembledIdsFingerprint) {
    _disciplesFlow.value = reusableMutableState.discipleTables.assembleAll()
    lastAssembledIdsFingerprint = fp
}

// 改后:
val disciplesChanged = reusableMutableState.discipleTables !== _discipleTables
val mutated = reusableMutableState.discipleTables.mutationVersion
if (disciplesChanged || mutated != lastAssembledMutationVersion) {
    _disciplesFlow.value = reusableMutableState.discipleTables.assembleAll()
    lastAssembledMutationVersion = mutated
}
```

### 3.3 附带修复: DiscipleViewModel.equipItem 吞掉 DomainResult

路径: `android/feature/game/src/main/java/com/xianxia/sect/ui/game/DiscipleViewModel.kt:182-191`

```kotlin
// 改前:
fun equipItem(discipleId: String, equipmentId: String) {
    viewModelScope.launch {
        try {
            gameEngine.equipItem(discipleId, equipmentId)
            showSuccess("装备成功")
        } catch (e: Exception) {
            showError(e.message ?: "装备失败")
        }
    }
}

// 改后:
fun equipItem(discipleId: String, equipmentId: String) {
    viewModelScope.launch {
        try {
            val result = gameEngine.equipEquipment(discipleId, equipmentId)
            when (result) {
                is DomainResult.Success -> showSuccess("装备成功")
                is DomainResult.Failure -> showError(result.error.userMessage ?: "装备失败")
                is DomainResult.Partial -> showSuccess("装备部分成功")
            }
        } catch (e: Exception) {
            showError(e.message ?: "装备失败")
        }
    }
}
```

> 注意:这里从 `gameEngine.equipItem` 改为 `gameEngine.equipEquipment`,因为后者返回 `DomainResult<Unit>`,前者返回 Unit。`equipEquipment` 在 `GameEngineDiscipleOps.kt:20` 定义。

### 3.4 可选: ComponentTableTest 补充 onWrite 测试

路径: `android/core/domain/src/test/java/com/xianxia/sect/core/state/ComponentTableTest.kt`

新增测试:
```kotlin
@Test
fun `onWrite callback is invoked on set`() {
    val table = IntComponentTable()
    var writeCount = 0
    table.onWrite = { writeCount++ }
    table[1] = 10
    assertEquals(1, writeCount)
    table.update(1) { it + 1 }
    assertEquals(2, writeCount)
    table.remove(1)
    assertEquals(3, writeCount)
    table.put(2, 20)
    assertEquals(4, writeCount)
    table.clear()
    assertEquals(5, writeCount)
}

@Test
fun `onWrite defaults to null, no crash`() {
    val table = ComponentTable<String>()
    table[1] = "hello"  // 不应 crash
    table.clear()
}
```

---

## 四、验证清单

实施完成后逐项验证:

1. **手动穿戴装备**: 弟子详情 → 点空槽位 → 选装备 → 确认 → 槽位立即显示新装备 ✅
2. **手动卸下装备**: 点已穿戴装备 → 卸下 → 槽位变空 ✅
3. **自动穿戴装备**: 仓库有装备 + 开启自动 → tick 后弟子穿上 ✅
4. **仓库列表同步**: 穿戴后仓库数量正确扣减 ✅
5. **使用丹药**: UI 立即刷新弟子属性 ✅
6. **实时突破**: UI 立即刷新弟子境界 ✅
7. **修炼进度**: UI 刷新修炼值(如果走实时路径) ✅
8. **新建游戏**: 弟子招募后穿装备正常 ✅
9. **旧存档加载**: 加载后穿装备正常 ✅
10. **性能无回归**: 1Hz tick 下 assembleAll 在无字段变化时被跳过(断点/mLog 验证) ✅
11. **编译通过**: 无编译错误/警告 ✅
12. **单元测试**: ComponentTableTest + 现有测试全部通过 ✅

---

## 五、完整改动清单

| 文件 | 改动类型 | 改动量 |
|---|---|---|
| `ComponentTable.kt` | 给三种表加 `onWrite` 回调字段 + 5 个写方法加 invoke | ~15 行 |
| `DiscipleTables.kt` | 添加 `bindAllOnWrite()` 方法 + init 调用 | ~60 行 |
| `GameStateStoreImpl.kt` | 3 处:字段声明+2 处提交门控 | ~9 行改动 |
| `DiscipleViewModel.kt` | equipItem 返回值处理修复 | ~10 行改动 |
| `ComponentTableTest.kt` | 新增 onWrite 测试 | ~20 行 |

**总改动**: ~114 行新增/修改,3 个生产文件,1 个测试文件。

---

## 六、Git 回溯参考

| 提交 | 说明 |
|---|---|
| `1e58fe27` (2026-06-15) | 三大系统重构 — 引入 `ids.hashCode()` 脏检测(本次修复的根因引入点) |
| `bdf27397` (2026-06-13) | 组件化实体存储架构 — 创建 DiscipleTables + EntityStore + ComponentTable |
| `205a6119` (2026-06-17) | v4.0.06 — 影子事务异步覆盖修复(涉及 CultivationEventProcessor,与本题无关) |
