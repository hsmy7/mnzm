# 根治方案：建筑 / 道具 / 弟子 三大系统彻底重构

> 版本：v1.0 | 日期：2026-06-15
> 前置阅读：`docs/adr/IMPLEMENTATION_PLAN.md`（组件表架构）
> 变更量：~45 文件 | 预计工期：6-8 个工作日
> 代码审查依据：3 轮代码质量审查 + 5 个 Explore agent 深度核实（每个决策有源码事实支撑）

---

## 一、诊断

### 三个系统共有的结构性顽疾

| 顽疾 | 现状 | 根因 |
|------|------|------|
| 裸 `Boolean` 领域返回 | 建筑 / 道具 / 弟子共 15+ 处 | `GameStateStore.update{}` block 返回 `Unit`，事务结果只能用 `var result=false` 闭包外捕获 |
| 5 套碎片化 sealed 结果类型 | `ProductionError` / `ProductionOperationResult`（均 @Deprecated）/ `ProductionResult.ProductionError`（与顶层同名）/ `ProductionOutcome` / `enum AddResult` | 统一方向（`AppError.Domain`）已确定但旧类型未拆 |
| 12 个全局可变 `object` | 全无 Hilt DI 足迹，静态调用 | Kotlin `object` 无法注入 |
| `InventorySystem` 1566 行 | 6 类物品 × 8 操作 ≈ 48 个同构方法 | `StackableItem` 接口**缺少 mergeKey 抽象** |
| `confiscateStorageBagItem` 6 类合并键**全错** | 都退化为 name+rarity，漏 slot/type/category/grade/growTime | 合并键散落无单一事实来源 |
| `DiscipleFacadeImpl.rewardItemsToDisciple` **404 行** | 内嵌 6 类物品分支 | 门面承担过多业务逻辑 |
| ComponentTable 架构意图落空 | `assembleAll()` + `clear()`+`insert()` 全量重建 | System 层未采用 O(log n) 窄字段访问 |
| warehouse 子系统过度设计 | 压缩/分页/diff/cache API 未接入生产 | 实现完整 + 有测试但死代码 |

### 三个被证实的真实功能 bug

1. **`confiscateStorageBagItem` 6 类合并键全错**（`InventoryFacadeImpl.kt:76,87,100,110,124,139`）— 与 `InventorySystem.addXxx` 不一致，造成同名同 rarity 不同 slot/type/category/grade 物品错误合并。
2. **`BuildingService.startAlchemy` 缺 else 致成功返 false**（`:122-205`）— `when{ !result.success->{...}; result.materialUpdate!=null->{...} }` 无 else，落到 `return false`。
3. **`WarehouseCompressor.decompress` rarity 截断**（`:53` `coerceIn(1,4)`）— 游戏 rarity 范围 1..6（`Rarity.kt:32`），传说(5)/神话(6)解压后降为史诗(4)。**经核实 decompressWarehouse 未被生产调用**，为潜在炸弹。

---

## 二、设计总则

| # | 原则 | 落实方式 |
|---|------|----------|
| G1 | **复用而非另起炉灶** | 领域结果复用已有 `AppError`；泛型 store 委托已有 `EntityStore`；事务复用已有 `update` Mutex |
| G2 | **单一事实来源** | 每种合并键、每个状态写点、每种领域错误只有**一处定义** |
| G3 | **编译期强制** | sealed interface + `when` 穷尽性 + Hilt 构造注入，让违规编译不过 |
| G4 | **改动收敛领域层** | 新基类放 `core/domain`（零 Android 依赖），Service 标 `@GameService`，object 全 DI 化 |
| G5 | **严格无尾巴** | 每个问题给出最终态，不遗留"后续优化" |

---

## 三、架构总览

```
┌─────────────────────────────────────────────────────────────┐
│ 第 0 层 基石（core/domain，零 Android 依赖）                 │
│   DomainResult<T>  ──复用──▶  AppError.Domain                │
│   StackableItemStore<T>  ──委托──▶  EntityStore<T>           │
│   GameStateStore.updateAndReturn<R>                          │
└─────────────────────────────────────────────────────────────┘
        ▲              ▲              ▲
        │              │              │
┌───────┴──────┐ ┌─────┴──────┐ ┌─────┴──────┐
│ 第1层 道具    │ │ 第2层 弟子  │ │ 第3层 建筑 │
│ InventorySystem│ │ DiscipleFacade│ │ BuildingService│
│ 1566→~400 行   │ │ 拆分+Factory │ │ 双写消除      │
│ StackableStore│ │ 热路径修复    │ │ Boolean修复   │
└───────────────┘ └──────────────┘ └──────────────┘
        │              │              │
        └──────────────┴──────────────┘
                       │
┌──────────────────────▼───────────────────────────────────────┐
│ 第4层 DI 化（engine 内全 class @Inject，UI 经 Facade 包装）   │
└──────────────────────────────────────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────────────┐
│ 第5层 超长函数拆分 + 第6层 清理收尾 + 测试 + CHANGELOG         │
└──────────────────────────────────────────────────────────────┘
```

---

## 四、第 0 层：基石（最先做，后续全依赖）

### 0.1 统一领域结果 `DomainResult<T>`

**核实**：`AppError`（`core/domain/.../util/AppError.kt`）已是嵌套 sealed 体系，含 `Domain.Production/Storage/Validation/GameState/GameLoop/Network`，`fromException` 已正确处理 `CancellationException`。**不另起炉灶**，在其上补齐三大领域子树 + 新增 Result 信封。

**新增文件 `core/domain/.../result/DomainResult.kt`**：

```kotlin
package com.xianxia.sect.core.result

/**
 * 领域操作统一结果类型。复用 [com.xianxia.sect.core.util.AppError] 作为错误载体。
 * 替代全项目的裸 Boolean 返回与 enum AddResult。
 *
 * - [Success]：操作成功
 * - [Partial]：部分成功（堆叠溢出，携带溢出量）
 * - [Failure]：操作失败（携带具体领域错误）
 */
sealed interface DomainResult<out T> {
    data class Success<out T>(val data: T) : DomainResult<T>
    data class Partial<out T>(val data: T, val overflow: Int) : DomainResult<T>
    data class Failure(val error: AppError.Domain) : DomainResult<Nothing>

    inline fun <R> map(transform: (T) -> R): DomainResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Partial -> Partial(transform(data), overflow)
        is Failure -> this
    }
    inline fun <R> flatMap(transform: (T) -> DomainResult<R>): DomainResult<R> = when (this) {
        is Success -> transform(data)
        is Partial -> transform(data)
        is Failure -> this
    }
    fun getOrNull(): T? = (this as? Success)?.data ?: (this as? Partial)?.data
    val isSuccess: Boolean get() = this is Success || this is Partial

    companion object {
        inline fun <T> catching(domain: AppError.Domain, block: () -> T): DomainResult<T> = try {
            Success(block())
        } catch (e: CancellationException) {
            throw e   // ← 修复 ProductionError.kt:160 的吞噬
        } catch (e: Exception) {
            Failure(AppError.Unknown(e.message ?: "未知错误", e) as AppError.Domain)
        }
    }
}
```

**扩展 `AppError.kt`**（在现有 `sealed class Domain` 内新增 3 个子树）：

```kotlin
sealed class Disciple : Domain() {
    data class NotFound(val discipleId: String, ...) : Disciple()   // DISCIPLE_001
    data class NotAlive(val discipleId: String) : Disciple()        // DISCIPLE_002
    data class RealmTooLow(val discipleId: String, val need: String) : Disciple()  // DISCIPLE_003
    data class AlreadyEquipped(val slot: String) : Disciple()       // DISCIPLE_004
    data class SlotInvalid(val detail: String) : Disciple()         // DISCIPLE_005
}
sealed class Inventory : Domain() {
    data class Full(...) : Inventory()      // INV_001
    data class NotFound(val itemId: String) : Inventory()   // INV_002
    data class InvalidName(...) : Inventory()  // INV_003
    data class InvalidRarity(val value: Int) : Inventory()   // INV_004
    data class InvalidQuantity(val value: Int) : Inventory() // INV_005
    data class Locked(val itemId: String) : Inventory()      // INV_006
    data class Insufficient(val itemId: String, val need: Int, val have: Int) : Inventory()  // INV_007
}
sealed class Building : Domain() {
    // 直接复用已有 Production 子树（SlotBusy/InsufficientMaterials/RecipeNotFound...）
    data class BuildingNotFound(val buildingId: String) : Building()  // BLD_001
    data class DiscipleBusy(val discipleId: String) : Building()      // BLD_002
}
```

### 0.2 `GameStateStore.updateAndReturn<R>`

**核实**：`update`（`GameStateStoreImpl.kt:762-886`）用 Mutex + 同线程重入旁路，block 返回 `Unit` 是 `var result=false` 反模式**根因**。重入旁路 `txState.block()` 天然支持返回 R，提交阶段（824-880）必须保留。

**接口 `GameStateStore.kt` 新增**：
```kotlin
/** 带返回值的事务更新。替代 update{} + var result 闭包捕获反模式。 */
suspend fun <R> updateAndReturn(block: suspend MutableGameState.() -> R): R
```

**实现 `GameStateStoreImpl.kt`**（镜像 `update`）：
```kotlin
override suspend fun <R> updateAndReturn(block: suspend MutableGameState.() -> R): R {
    // 重入旁路：与 update 一致，直接 return block() 结果
    if (transactionOwnerThread.get() == Thread.currentThread() && currentTransactionState != null) {
        return currentTransactionState!!.block()
    }
    transactionMutex.withLock {
        transactionOwnerThread.set(Thread.currentThread())
        // ... 灌入 reusableMutableState（同 update 785-819）...
        currentTransactionState = reusableMutableState
        try {
            val result = reusableMutableState.block()   // ← 替换原 block()
            // ... 提交阶段（824-880 完全保留）...
            return result                                // ← 提交后返回
        } finally {
            currentTransactionState = null
            transactionOwnerThread.set(null)
        }
    }
}
```

**清理死代码**（核实证据）：
- 删除 `isInTransaction()`（接口 + 实现，**0 调用点**）
- 删除 `createShadow()`（无生产调用点，仅测试用）— 改 `StateRevertRegressionTest` 用 `createSettlementShadow`

### 0.3 删 5 套碎片 sealed 类型

**核实**：散落 3 文件，其中 `ProductionParams.kt:90` 嵌套**与顶层同名的 `ProductionError`**。

| 动作 | 目标 |
|------|------|
| **删除** | `core/domain/.../model/production/ProductionError.kt` 整文件（@Deprecated） |
| **删除** | `ProductionParams.kt:84-112` 的 `ProductionResult` 及嵌套同名 `ProductionError` |
| **保留** | `SlotStateMachine.ProductionOutcome`（仍被使用），改引用 `AppError.Domain` |
| **迁移** | `ProductionCoordinator.kt:89-101` 的 `ProductionStartResult`/`CompleteResult` → `DomainResult<Slot>` / `DomainResult<ProductionOutcome>` |

---

## 五、第 1 层：道具 / 仓库系统

### 1.1 泛型 `StackableItemStore<T>`

**核实**：`StackableItem`（`core/domain/.../util/StackableItem.kt`）有 `withQuantity` 但**无 mergeKey 抽象**——6 套不一致根因。`EntityStore<T:HasId>`（`core/domain/.../state/EntityStore.kt`）有完整 O(1) 索引 + 写操作。

**新增 `core/domain/.../state/StackableItemStore.kt`**：

```kotlin
/**
 * 可堆叠物品的统一仓库。委托 [EntityStore] 做 O(1) ID 索引，
 * 叠加"按 stackKey 合并"语义。合并键由 [stackKeyOf] 单一定义，
 * 根除全项目 6 套不一致合并键。
 */
class StackableItemStore<T>(
    initialItems: List<T> = emptyList(),
    private val stackKeyOf: (T) -> StackKey,
    private val maxStack: Int,
    private val maxSlots: () -> Int,
    private val notFound: (String) -> AppError.Domain,
) where T : HasId, T : StackableItem {

    private val store = EntityStore(initialItems)
    private val keyIndex = HashMap<StackKey, String>()   // stackKey → id

    fun add(item: T, merge: Boolean = true): DomainResult<T> { /* 合并/新增/Full */ }
    fun remove(id: String, count: Int = 1): DomainResult<Unit> { /* Locked/Insufficient/删除 */ }
    fun get(id: String): T? = store.get(id)
    fun has(id: String): Boolean = store.contains(id)
    fun quantity(id: String): Int = store.get(id)?.quantity ?: 0
    fun all(): List<T> = store.all()
    fun replaceAll(items: List<T>) { store.replaceAll(items); rebuildKeyIndex() }
    fun snapshot(): EntityStore<T> = store
    private fun rebuildKeyIndex() { keyIndex.clear(); store.all().forEach { keyIndex[stackKeyOf(it)] = it.id } }
}

/** 类型化合并键（替代裸 String 拼接，避免拼错） */
data class StackKey(val parts: List<Any>) {
    companion object { fun of(vararg parts: Any) = StackKey(parts.toList()) }
}
```

**6 类合并键单一事实来源（修正 confiscate bug）**：

| 物品 | 现状（错） | 统一 stackKey | 修正 |
|------|----------|--------------|------|
| EquipmentStack | name+rarity | name+rarity+**slot** | ✅ 加 slot |
| ManualStack | name+rarity | name+rarity+**type** | ✅ 加 type |
| Pill | name+rarity+grade | name+rarity+**category**+grade | ✅ 加 category |
| Material | name+rarity | name+rarity+**category** | ✅ 加 category |
| Herb | name+rarity | name+rarity+**category** | ✅ 加 category |
| Seed | name+rarity | name+rarity+**growTime** | ✅ 加 growTime |

### 1.2 `InventorySystem` 重构：1566 → ~400 行

**核实**：构造为 `@Singleton class @Inject constructor(stateStore, scopeProvider, inventoryConfig)`。

- 持有 6 个 `StackableItemStore`（从 `MutableGameState` 的 `EntityStore` 实时构造，零额外内存）
- 所有 add/remove/update/has/getQuantity **一行委托**
- **移除 ~48 个同构方法**、移除 `validateStackableItem`（并入 `store.add`）、移除 `currentTransactionMutableState` 双分支（收敛 `storeWrite`）
- 补 `@GameService("InventorySystem")`

### 1.3 `AddResult` enum → 删除

**核实**：引用仅 7 文件，消费者全用 `==` 比较（sealed 后 `==` 仍可用）。
- 删 `InventoryTypes.kt` 的 `enum class AddResult`（含 dead 值 `ITEM_LOCKED`）
- `ItemAdder` 8 方法返回 `AddResult` → `DomainResult<对应类型>`
- 5 消费文件：`== AddResult.SUCCESS` → `.isSuccess`；`!= SUCCESS` → `!it.isSuccess`
- 测试改断言 `DomainResult.Success`

### 1.4 warehouse 死代码清理 + DI 化

**核实**：`SectWarehouseManager` 仅 `CaveExplorationProcessor` 用 2 次（lootLoss）。`compressWarehouse`/`loadPage`/`search` 等未接入。warehouse 4 object 调用全内化在 `OptimizedWarehouseManager`。

| 动作 | 详情 |
|------|------|
| **删除** | `WarehouseCompressor.kt`（含 rarity coerceIn(1,4) bug）+ 测试 |
| **删除** | `WarehousePager.kt` / `WarehouseDiffManager.kt` / `WarehouseCache.kt` + 测试（未接入） |
| **精简** | `SectWarehouseManager`：仅留 `calculateWarehouseLootLoss`/`applyLootLossToWarehouse`/`convertCaveRewardsToWarehouseItems`/`convertWarRewardsToWarehouseItems` |
| **精简** | `OptimizedWarehouseManager`：仅留 `addItem`/`addItems`/`addSpiritStones`/`removeItem`/`clear` |
| **DI 化** | 两者改 `@GameService @Singleton class @Inject constructor(inventoryConfig)`；`CaveExplorationProcessor` 注入（2 点改注入） |

---

## 六、第 2 层：弟子系统

### 2.1 `rewardItemsToDisciple` 拆分（404 → 6 委托）

**核实**：`DiscipleFacadeImpl.kt:204-608` 内嵌 6 类物品分支，与 `usePill`（:792-907）、`DisciplePillManager.applyPillEffect` 三处丹药逻辑重复。

```kotlin
fun rewardItemsToDisciple(discipleId: Int, itemType: String, ...): DomainResult<Unit> =
    when (itemType) {
        "equipment" -> discipleEquipmentManager.giveEquipment(discipleId, ...)
        "manual"    -> discipleManualManager.giveManual(discipleId, ...)
        "pill"      -> disciplePillManager.applyPillEffect(discipleId, ...)  // 复用，消除三处重复
        "material"  -> giveMaterialReward(discipleId, ...)
        "herb"      -> giveHerbReward(discipleId, ...)
        "seed"      -> giveSeedReward(discipleId, ...)
        else        -> DomainResult.Failure(AppError.Domain.Validation.InvalidInput("未知物品类型 $itemType"))
    }
```
- `usePill` 复用 `applyPillEffect`（消除与 rewardItems 的丹药重复）
- 三 Manager 改 `@GameService @Singleton class @Inject constructor`（各 2-3 调用点）

### 2.2 ComponentTable 热路径修复

**核实**：`DiscipleTables` ~95 个 public 字段（全 SparseArray）。`assembleAll` 是热点（`update` 提交 `GameStateStoreImpl.kt:845` 每帧）。`PartnerSystem.kt:36` 为查一个 partnerId 全量 assemble。

| 位置 | 现状（O(n)） | 修复后（O(log n)） |
|------|-------------|-------------------|
| `PartnerSystem.kt:36` | `ids.map{assemble}.find?.social?.partnerId` | `partnerIds[discipleId.toInt()]` |
| `ChildBirthSystem.kt:39,79` | `assembleAll()` 全量 | 遍历窄字段 |
| `DiscipleBreakthroughHandler.kt:32,152` | `disciples.value.map{}` + clear+insert | `tables.realms[id]` 直接读写 |
| `DiscipleLifecycleProcessor.kt:33,50,82,152` | 同上 | 同上 |
| `GameStateStoreImpl.kt:845` | 每帧 `assembleAll` | 保留但加 cheap dirty check（ids hash 不变则跳过） |

### 2.3 `DiscipleFactory` —— 统一三处构造

**核实**：`recruitDisciple` / `refreshRecruitList` / `createChild` 的 ①variance ②comprehension ③skills ④baseStats ⑤lifespan ⑥talentIds 六段**字符级一致**，仅 id/spiritRoot/name/age/realmLayer/social 6 个差异。

```kotlin
@GameService("DiscipleFactory")
@Singleton
class DiscipleFactory @Inject constructor() {
    data class DiscipleSeed(
        val id: String, val gender: String, val nameResult: NameService.NameResult,
        val spiritRootType: String, val age: Int, val realmLayer: Int,
        val social: SocialData, val random: GameRandom
    )
    /** 统一构造：内部做 variance/comprehension/skills/baseStats/lifespan/talents（六段固定逻辑） */
    fun create(seed: DiscipleSeed): Disciple
}
```
- 三处各算 `DiscipleSeed`（按各自规则）后调 `discipleFactory.create(seed)`
- **消除约 300 行重复**

### 2.4 弟子 Boolean → DomainResult

**核实**：`expelDisciple:512` / `equipEquipment:615` / `unequipEquipment:701` / `removeDisciple:63` 返 Boolean，`var result=false`。

```kotlin
suspend fun equipEquipment(discipleId: String, equipmentId: String): DomainResult<Unit> =
    stateStore.updateAndReturn {
        val disciple = discipleTables.get(discipleId.toInt())
            ?: return@updateAndReturn DomainResult.Failure(AppError.Domain.Disciple.NotFound(discipleId))
        // ... 不再 var result，直接 return@updateAndReturn DomainResult.Xxx（具体原因）
    }
```
- `equipEquipment` 内 10+ 处失败原因全部保留（NotFound/NotAlive/RealmTooLow/AlreadyEquipped/SlotInvalid）
- `DiscipleFacade` 接口同步改返回类型

---

## 七、第 3 层：建筑系统

### 3.1 消除 repository + gameData.productionSlots 双写

**核实**：`BuildingFacadeImpl` 每次改双写，`BuildingService` 和 `service.ProductionSubsystem` 只写 repository。

- **repository 为单一真相源**；`MutableGameState` 不再持 `productionSlots`（或改只读派生）
- Facade 三方法（`assignDiscipleToProductionSlot`/`removeDiscipleFromProductionSlot`/`toggleAutoRestart`）只写 repository
- UI 经 ViewModel 从 `repository.observeAll(): Flow<List<ProductionSlot>>` 派生（若 repository 无 Flow 则补）

### 3.2 重命名同名 `ProductionSubsystem`

**核实**：`core.service` 版（538 行，实际处理）vs `core.domain.production` 版（77 行，空壳转发）。
- service 版 → `ProductionProcessor`（`@GameService("ProductionProcessor")`）
- domain 版保留 `ProductionSubsystem`（GameSystem 调度器），内部调 `productionProcessor`
- 更新 `SystemManager` 注册 + 所有引用

### 3.3 建筑 Boolean → DomainResult（自动修复缺 else bug）

**核实**：`startAlchemy`/`startForging` 返 Boolean，下层 `ProductionStartResult` 被降级丢弃。`startAlchemy:122` 缺 else 致成功返 false。

```kotlin
suspend fun startAlchemy(slotIndex: Int, recipeId: String): DomainResult<ProductionSlot> =
    productionCoordinator.startProduction(...).let { result ->
        when (result) {
            is DomainResult.Success -> DomainResult.Success(result.data.slot)
            is DomainResult.Partial -> result
            is DomainResult.Failure -> result   // 透传 SlotBusy/InsufficientMaterials/RecipeNotFound
        }
    }
```
- `BuildingFacade` 接口 `Boolean` → `DomainResult<ProductionSlot>`

### 3.4 `removeBuilding`（129 行）拆分

内嵌 8 段按建筑分支清理 → 策略表：
```kotlin
private val buildingCleanups: Map<String, (MutableGameState, Int) -> Unit> = mapOf(
    "alchemy" to ::cleanupAlchemy, "forge" to ::cleanupForge, ...
)
```

### 3.5 `@GameService` 注解补齐

为 `BuildingFacadeImpl`/`ProductionCoordinator`/`ProductionProcessor`/`EconomySubsystem`/`DiscipleFacadeImpl`/`DiscipleBreakthroughHandler`/`DiscipleLifecycleProcessor`/`MerchantAndRecruitService` 补注解，从 `EngineServiceAnnotationTest` 白名单移除（白名单只缩不增规则满足）。

---

## 八、第 4 层：engine 内全 DI 化（UI 用 Facade 包装）

**核实**：12 个 object 全无 DI。`DiscipleStatCalculator` 138 调用点穿透 UI（极高）。warehouse 家族已随 1.4 处理。

| 分级 | object | 调用点 | 策略 |
|------|--------|--------|------|
| 低 | `DisciplePill/Equipment/ManualManager` | 各 2-3 | 改 `@Singleton class @Inject`，调用点注入 |
| 低 | `DiscipleSlotCleanup` | 16 点 3 文件 | 同上 |
| 中 | `MerchantItemConverter` | 67 点 4 文件 | 改 class 注入，`InventoryFactories` 也注入 |
| 内化 | warehouse 家族 | — | 已随 1.4 处理 |
| **极高** | `DiscipleStatCalculator` | 138 点 23 文件，**穿透 UI** | 改 `@Singleton class`；engine 内 ~19 文件注入；**UI 不直接持有**，静态常量留 `companion const`，计算能力经 `DiscipleFacade`/`Service` 已有方法暴露（无则补透传） |

---

## 九、第 5 层：超长函数批量拆分（>60 行）

| 函数 | 行数 | 拆分方式 |
|------|------|----------|
| `DiscipleFacadeImpl.rewardItemsToDisciple` | 404 | 见 2.1（6 委托） |
| `BuildingFacadeImpl.removeBuilding` | 129 | 见 3.4（策略表） |
| `DiscipleBreakthroughHandler.processRealtimeBreakthroughs` | 124 | 拆 `tryBreakthroughFor`（批）+ `applyPillBonus`（单） |
| `InventoryFacadeImpl.openStorageBag`/`buyMerchantItem`/`confiscate` | 118/114/111 | 随 1.1 泛型 store 自然消除（7 分支 when 变 `store.add` 委托） |
| `DiscipleFacadeImpl.usePill` | 115 | 见 2.1（复用 PillManager） |

---

## 十、第 6 层：清理收尾（零尾巴）

1. **删 @Deprecated 死代码**：`BuildingService.kt:527-580` 转换函数 + 对应 getter（:36-49）
2. **删 `DiscipleService.kt:36-38`** `currentDiscipleTables` 空 setter
3. **魔法字符串常量化**：buildingId（`"forge"`/`"alchemy"`/`"herbGarden"`）、itemType（`"equipment_stack"`/`"manual_instance"`）、elderSlotType → 现有 `BuildingType`/`ElderSlotType` enum 或 constants 文件
4. **魔法数字**：`baseline=80`（长老技能基线）、`/4.0`（减速系数）等 → 提常量 + KDoc
5. **`internal` 可见性**补全
6. **KDoc 补全**（核心 API：`DomainResult`/`StackableItemStore`/`DiscipleFactory`）
7. **更新 CHANGELOG.md + ChangelogData.kt**（规范 §12.4 强制）
8. **versionCode +1，versionName 递增**

---

## 十一、测试策略（规范 §9 强制）

| 类型 | 内容 |
|------|------|
| **新增** | `DomainResultTest`（map/flatMap/catching/CancellationException 不被吞） |
| **新增** | `StackableItemStoreTest`（6 类合并键正确性 + add/remove/Partial/Full） |
| **新增** | `DiscipleFactoryTest`（三处构造结果一致） |
| **新增** | `BuildingFacadeTest`（startAlchemy 返回 DomainResult 含具体失败原因） |
| **迁移** | `InventorySystemTest` 改断言 `DomainResult`（AddResult 已删） |
| **删除** | `WarehouseCache/Pager/Compressor/DiffManager Test`（文件已删） |
| **更新** | `EngineServiceAnnotationTest` 白名单缩减 |
| **回归** | `StateRevertRegressionTest` 改用 `createSettlementShadow` |

---

## 十二、执行顺序（依赖决定，不可乱序）

```
第 0 层（基石）
  0.1 DomainResult + AppError 扩展   ← 一切依赖
  0.2 updateAndReturn + 死代码清理   ← 2.4/3.3 依赖
  0.3 删 5 套碎片 sealed 类型        ← 依赖 0.1
        │
第 1 层（道具）
  1.1 StackableItemStore + 合并键   ← 1.2 依赖
  1.2 InventorySystem 重构           ← 1.3/1.4 依赖
  1.3 AddResult 删除
  1.4 warehouse 死代码 + DI 化
        │
第 2 层（弟子）
  2.3 DiscipleFactory               ← 2.1 可复用
  2.1 rewardItems 拆分
  2.2 ComponentTable 热路径修复
  2.4 Boolean → DomainResult
        │
第 3 层（建筑）
  3.1 双写消除 / 3.2 命名 / 3.3 Boolean / 3.4 拆分 / 3.5 注解
        │
第 4 层（DI 化）  低 → 中 → 极高 分批
        │
第 5 层（超长函数） + 第 6 层（清理）
        │
测试 + CHANGELOG + 版本号
```

**每层完成时执行验证检查点**：
```bash
cd android && ./gradlew.bat compileReleaseKotlin   # 编译通过
cd android && ./gradlew.bat test                   # 全测试通过
cd android && ./gradlew.bat lintRelease            # detekt/lint 无新增违规
```

---

## 十三、预期收益（量化）

| 指标 | 现状 | 目标 |
|------|------|------|
| `InventorySystem` 行数 | 1566 | ~400（消除 ~48 同构方法） |
| `DiscipleFacadeImpl` 单方法 | 404 行 | 每方法 ≤60 行 |
| 裸 `Boolean` 领域返回 | 15+ 处 | 0 |
| `@GameService` 缺失类 | 9+ 个 | 0（白名单清空） |
| 5 套碎片 sealed 类型 | 5 套 | 1 套（`AppError.Domain`） |
| 全局可变 `object` | 12 个 | 0（engine 内全 DI 化） |
| `confiscate` 合并键 bug | 6 类全错 | 0（单一 stackKey 定义点） |
| `assembleAll` 热路径滥用 | 5+ 处 | 0（改窄字段） |
| `CancellationException` 吞噬 | 2 处 | 0 |
| 死代码 | 8 文件 + 若干方法 | 0 |

---

## 十四、源码核实证据索引

> 本方案每个决策都基于以下深度核实（非臆测）。

| 核实项 | 证据文件:行号 |
|--------|--------------|
| `AppError` 已是 sealed 体系 + 处理 CancellationException | `core/domain/.../util/AppError.kt:3,344` |
| `update` Mutex + 重入旁路，block 返回 Unit | `GameStateStoreImpl.kt:762-886` |
| `StackableItem` 无 mergeKey 抽象 | `core/domain/.../util/StackableItem.kt:3-10` |
| `EntityStore` 完整 O(1)+写操作 | `core/domain/.../state/EntityStore.kt:13-165` |
| `InventorySystem` 1566 行 6×8 同构 | `core/engine/.../system/InventorySystem.kt` |
| `confiscateStorageBagItem` 6 类合并键全错 | `InventoryFacadeImpl.kt:76,87,100,110,124,139` |
| `AddResult` 引用仅 7 文件 | `InventoryTypes.kt:7-17` + 消费者 |
| `warehouse` 仅 lootLoss 接入 | `CaveExplorationProcessor.kt:708-709` |
| `WarehouseCompressor` rarity 截断（未接入） | `WarehouseCompressor.kt:53`；`Rarity.kt:32` 范围 1..6 |
| `DiscipleTables` ~95 字段全 SparseArray | `core/domain/.../state/DiscipleTables.kt` |
| `rewardItemsToDisciple` 404 行 | `DiscipleFacadeImpl.kt:204-608` |
| 三处弟子构造字符级一致 | `DiscipleService.kt:415` / `MerchantAndRecruitService.kt:292` / `ChildBirthSystem.kt:126` |
| `@GameService` 纯标记 + 架构守护测试 | `GameService.kt` + `EngineServiceAnnotationTest.kt` |
| Hilt 全构造器注入，object→class 无需新增注册 | `BuildingService.kt:21` / `DiscipleService.kt:26` / `InventorySystem.kt:30` |
| 12 object 全无 DI 足迹 | `DiscipleStatCalculator` 等 |
| `startAlchemy` 缺 else 致成功返 false | `BuildingService.kt:122-205` |
| `MutableGameState` 是 data class（非 interface） | `MutableGameState.kt:5-24` |
| `isInTransaction()` / `createShadow()` 死代码 | 0 生产调用点 |

---

**本方案为一次性完整覆盖的最终态，执行者照单实施，无需二次设计。**
