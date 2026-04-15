# XianxiaSectNative 彻底重构方案

> 基于对整个代码库的深度分析，本文档定义了 5 个阶段的重构计划（不含持久化层精简）。

---

## 问题全景

当前架构的 7 个核心问题及其因果链：

```
问题1: Service/System 双轨并存
  ├─ Service 层直接写 stateStore.mutableXxx (13个文件)
  ├─ System 层有自己的内部 MutableStateFlow (如 TimeSystem._gameData)
  └─ 同一领域两套实现（CultivationService vs CultivationSystem）

问题2: 状态写入双路径
  ├─ 路径A: Service → stateStore.mutableXxx (无观察者通知、无事件发射)
  ├─ 路径B: UnifiedGameStateManager → stateStore (有通知+事件)
  └─ StateChangeRequestBus (CQRS命令总线) 完全未使用

问题3: GameViewModel 4000+ 行
  ├─ 存档管理、对话框路由、战斗队伍、生产系统全混在一起
  └─ 其他 ViewModel (Battle/Sect/Disciple) 与 GameViewModel 职责重叠

问题4: 库存系统三套并存
  ├─ InventorySystem (system/)
  ├─ InventorySystemV2 (system/inventory/) + 7个辅助类
  ├─ InventoryService (service/) 包装 InventorySystem
  └─ UnifiedItemService + ItemTransactionManager + OptimizedWarehouseManager

问题5: Coordinator 层废弃
  ├─ GameLoopCoordinator / MonthlyEventCoordinator / BattleCoordinator / RealtimeDataCoordinator
  └─ 全部只被自身文件引用，从未被注入或调用

问题6: 持久化层过度工程化（不在本次重构范围）

问题7: 重复的 DiscipleAggregate 转换
  └─ 4个 ViewModel 各自独立做 disciples.map { it.toAggregate() }
```

---

## 阶段一：统一状态写入路径（P0）

> 所有后续重构的前提，必须最先完成。

### 1.1 封闭 GameStateStore

**当前问题**：`GameStateStore` 暴露了 `mutableXxx` 属性，13 个文件直接写入，绕过所有观察者通知和事件发射机制。

**重构方案**：将所有 `MutableStateFlow` 设为 `private`，对外只暴露只读 `StateFlow` 和统一的 `update` 方法。

```kotlin
@Singleton
class GameStateStore @Inject constructor() {
    private val _gameData = MutableStateFlow(GameData())
    private val _disciples = MutableStateFlow<List<Disciple>>(emptyList())
    private val _equipment = MutableStateFlow<List<Equipment>>(emptyList())
    private val _buildings = MutableStateFlow<List<Building>>(emptyList())
    private val _teams = MutableStateFlow<List<Team>>(emptyList())
    private val _worldMap = MutableStateFlow(WorldMapData())
    private val _inventory = MutableStateFlow(InventoryData())
    private val _diplomacy = MutableStateFlow(DiplomacyData())
    private val _missions = MutableStateFlow(List<Mission>())
    private val _alchemySlots = MutableStateFlow(List<AlchemySlot>())
    private val _forgeSlots = MutableStateFlow(List<ForgeSlot>())

    private val transactionMutex = Mutex()

    val gameData: StateFlow<GameData> get() = _gameData
    val disciples: StateFlow<List<Disciple>> get() = _disciples
    val equipment: StateFlow<List<Equipment>> get() = _equipment
    val buildings: StateFlow<List<Building>> get() = _buildings
    val teams: StateFlow<List<Team>> get() = _teams
    val worldMap: StateFlow<WorldMapData> get() = _worldMap
    val inventory: StateFlow<InventoryData> get() = _inventory
    val diplomacy: StateFlow<DiplomacyData> get() = _diplomacy
    val missions: StateFlow<List<Mission>> get() = _missions
    val alchemySlots: StateFlow<List<AlchemySlot>> get() = _alchemySlots
    val forgeSlots: StateFlow<List<ForgeSlot>> get() = _forgeSlots

    val unifiedState: StateFlow<UnifiedGameState> = combine(
        _gameData, _disciples, _equipment, _buildings,
        _teams, _worldMap, _inventory, _diplomacy,
        _missions, _alchemySlots, _forgeSlots
    ) { values ->
        UnifiedGameState(
            gameData = values[0] as GameData,
            disciples = values[1] as List<Disciple>,
            equipment = values[2] as List<Equipment>,
            buildings = values[3] as List<Building>,
            teams = values[4] as List<Team>,
            worldMap = values[5] as WorldMapData,
            inventory = values[6] as InventoryData,
            diplomacy = values[7] as DiplomacyData,
            missions = values[8] as List<Mission>,
            alchemySlots = values[9] as List<AlchemySlot>,
            forgeSlots = values[10] as List<ForgeSlot>
        )
    }.stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, UnifiedGameState())

    val discipleAggregates: StateFlow<List<DiscipleAggregate>> = _disciples
        .map { list -> list.map { it.toAggregate() } }
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, emptyList())

    suspend fun update(block: MutableGameState.() -> Unit) {
        transactionMutex.withLock {
            val mutable = MutableGameState(
                gameData = _gameData.value,
                disciples = _disciples.value,
                equipment = _equipment.value,
                buildings = _buildings.value,
                teams = _teams.value,
                worldMap = _worldMap.value,
                inventory = _inventory.value,
                diplomacy = _diplomacy.value,
                missions = _missions.value,
                alchemySlots = _alchemySlots.value,
                forgeSlots = _forgeSlots.value
            )
            mutable.block()
            _gameData.value = mutable.gameData
            _disciples.value = mutable.disciples
            _equipment.value = mutable.equipment
            _buildings.value = mutable.buildings
            _teams.value = mutable.teams
            _worldMap.value = mutable.worldMap
            _inventory.value = mutable.inventory
            _diplomacy.value = mutable.diplomacy
            _missions.value = mutable.missions
            _alchemySlots.value = mutable.alchemySlots
            _forgeSlots.value = mutable.forgeSlots
        }
    }

    suspend fun loadFromSnapshot(snapshot: GameStateSnapshot) {
        transactionMutex.withLock {
            _gameData.value = snapshot.gameData
            _disciples.value = snapshot.disciples
            _equipment.value = snapshot.equipment
            _buildings.value = snapshot.buildings
            _teams.value = snapshot.teams
            _worldMap.value = snapshot.worldMap
            _inventory.value = snapshot.inventory
            _diplomacy.value = snapshot.diplomacy
            _missions.value = snapshot.missions
            _alchemySlots.value = snapshot.alchemySlots
            _forgeSlots.value = snapshot.forgeSlots
        }
    }
}

data class MutableGameState(
    var gameData: GameData,
    var disciples: List<Disciple>,
    var equipment: List<Equipment>,
    var buildings: List<Building>,
    var teams: List<Team>,
    var worldMap: WorldMapData,
    var inventory: InventoryData,
    var diplomacy: DiplomacyData,
    var missions: List<Mission>,
    var alchemySlots: List<AlchemySlot>,
    var forgeSlots: List<ForgeSlot>
)
```

### 1.2 迁移 Service 层写入

当前每个 Service 都通过 `stateStore.mutableXxx` 直接写入，需要统一迁移到 `stateStore.update { }`。

**迁移模式**：

```kotlin
// 迁移前
private val _gameData get() = stateStore.mutableGameData
_gameData.value = _gameData.value.copy(gameYear = newYear)

// 迁移后
stateStore.update {
    gameData = gameData.copy(gameYear = newYear)
}
```

**涉及文件（13个）**：

| 文件 | 当前写入方式 | 迁移动作 |
|------|-------------|---------|
| `core/engine/GameEngine.kt` | `stateStore.mutableGameData` | 迁移到 `stateStore.update` |
| `core/engine/service/CultivationService.kt` | `stateStore.mutableGameData` + `stateStore.mutableDisciples` | 迁移到 `stateStore.update` |
| `core/engine/service/DiscipleService.kt` | `stateStore.mutableDisciples` | 迁移到 `stateStore.update` |
| `core/engine/service/CombatService.kt` | `stateStore.mutableGameData` + `stateStore.mutableTeams` | 迁移到 `stateStore.update` |
| `core/engine/service/BuildingService.kt` | `stateStore.mutableBuildings` | 迁移到 `stateStore.update` |
| `core/engine/service/DiplomacyService.kt` | `stateStore.mutableDiplomacy` | 迁移到 `stateStore.update` |
| `core/engine/service/ExplorationService.kt` | `stateStore.mutableTeams` + `stateStore.mutableGameData` | 迁移到 `stateStore.update` |
| `core/engine/service/EventService.kt` | `stateStore.mutableGameData` | 迁移到 `stateStore.update` |
| `core/engine/service/InventoryService.kt` | `stateStore.mutableEquipment` | 迁移到 `stateStore.update` |
| `core/engine/service/FormulaService.kt` | `stateStore.mutableGameData` | 迁移到 `stateStore.update` |
| `core/engine/service/RedeemCodeService.kt` | `stateStore.mutableGameData` | 迁移到 `stateStore.update` |
| `core/engine/service/SaveService.kt` | `stateStore.mutableXxx` (全量恢复) | 迁移到 `stateStore.loadFromSnapshot` |
| `core/state/UnifiedGameStateManager.kt` | `stateStore.mutableXxx` | 迁移到 `stateStore.update` |

### 1.3 删除 StateChangeRequestBus

`StateChangeRequestBus` 实现了 CQRS 命令总线模式，但从未被任何业务代码使用（仅 `CoreModule` 注册 + 自身文件引用）。直接删除。

**删除文件**：
- `core/state/StateChangeRequestBus.kt`
- `core/state/StateChangeRequest.kt`

**同步修改**：
- `di/CoreModule.kt`：移除 `StateChangeRequestBus` 的 `@Provides` 和 `@Singleton` 声明

### 1.4 重新定位 UnifiedGameStateManager

当前 `UnifiedGameStateManager` 试图做观察者通知 + 事件发射 + 锁 + 状态更新，但实际被 Service 层绕过。重构后职责收窄为：

- **观察者通知**：保留 `UnifiedStateObserver` 机制，但改为监听 `GameStateStore.unifiedState` 的变化
- **UI 元状态**：保留 `setPaused`、`setLoading`、`setSaving`
- **删除**：所有 `updateGameData`、`updateDisciple`、`updateDisciples` 等 `updateXxx` 方法（与 `GameStateStore.update` 重复）

```kotlin
@Singleton
class UnifiedGameStateManager @Inject constructor(
    private val stateStore: GameStateStore,
    private val eventBus: EventBus
) {
    val state: StateFlow<UnifiedGameState> get() = stateStore.unifiedState

    private val observers = ListenerManager<UnifiedStateObserver>(TAG)

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            stateStore.unifiedState.collect { newState ->
                observers.forEach { it.onStateChanged(newState) }
            }
        }
    }

    suspend fun setPaused(paused: Boolean) {
        stateStore.update { gameData = gameData.copy(isPaused = paused) }
    }

    suspend fun setLoading(loading: Boolean) {
        stateStore.update { gameData = gameData.copy(isLoading = loading) }
    }

    suspend fun setSaving(saving: Boolean) {
        stateStore.update { gameData = gameData.copy(isSaving = saving) }
    }

    fun addObserver(observer: UnifiedStateObserver) = observers.add(observer)
    fun removeObserver(observer: UnifiedStateObserver) = observers.remove(observer)
}
```

### 1.5 阶段一验收标准

- [ ] `GameStateStore` 中所有 `MutableStateFlow` 为 `private`，无 `mutableXxx` 公开属性
- [ ] 全局搜索 `stateStore.mutable` 返回 0 结果
- [ ] 所有状态修改通过 `stateStore.update { }` 或 `stateStore.loadFromSnapshot`
- [ ] `StateChangeRequestBus` 和 `StateChangeRequest` 已删除
- [ ] `UnifiedGameStateManager` 不再有 `updateXxx` 方法
- [ ] 游戏循环正常运行，存档/读档功能正常

---

## 阶段二：合并 Service/System 双轨（P0）

> 依赖阶段一完成。核心架构简化，消除同一领域的两套实现。

### 2.1 确立统一架构：System 层为唯一业务逻辑层

**重构前**：
```
GameEngine → Service层(11个) → stateStore.mutableXxx
SystemManager → System层(14个) → 各自内部 StateFlow (从未同步到 stateStore)
```

**重构后**：
```
GameEngine → System层(统一) → GameStateStore.update { }
```

**选择 System 层而非 Service 层的原因**：

1. System 层有 `GameSystem` 接口规范（`initialize/release/clear`），Service 层没有
2. System 层有 `TickableSystem.onGameTick()` 回调机制，Service 层没有
3. System 层不持有状态（除 TimeSystem 外），更符合无状态计算的设计
4. Service 层的"有状态服务"模式（直接写 MutableStateFlow）是架构混乱的根源

### 2.2 System 层新接口

```kotlin
interface GameSystem {
    val systemName: String

    fun initialize() {}
    fun release() {}
    suspend fun clear() {}

    suspend fun onSecondTick(state: MutableGameState) {}
    suspend fun onDayTick(state: MutableGameState) {}
    suspend fun onMonthTick(state: MutableGameState) {}
    suspend fun onYearTick(state: MutableGameState) {}
}
```

System 只需实现自己关心的回调，其他留空走默认实现。

### 2.3 逐领域合并

每个领域的合并策略：

| 领域 | Service (废弃) | System (保留+增强) | 合并动作 |
|------|----------------|-------------------|---------|
| 修炼 | CultivationService (153KB!) | CultivationSystem | 将 Service 逻辑迁入 System，System 增加 `onMonthTick` |
| 弟子 | DiscipleService | DiscipleSystem | 将 Service 逻辑迁入 System |
| 战斗 | CombatService | — (无对应System) | 新建 CombatSystem |
| 探索 | ExplorationService | ExplorationSystem | 合并 |
| 事件 | EventService | EventSubsystem | 合并 |
| 建筑 | BuildingService | BuildingSubsystem | 合并 |
| 库存 | InventoryService | InventorySystem | 合并（详见阶段三） |
| 外交 | DiplomacyService | DiplomacySubsystem | 合并 |
| 时间 | — | TimeSystem | 删除 TimeSystem 内部 `_gameData`，改为从 GameStateStore 读取 |
| 生产 | — | ProductionSubsystem | 保留 |
| 炼丹 | — | AlchemySubsystem | 保留 |
| 锻造 | — | ForgingSubsystem | 保留 |
| 药园 | — | HerbGardenSubsystem | 保留 |
| 商人 | — | MerchantSubsystem | 保留 |

### 2.4 合并示例：CultivationSystem

```kotlin
@Singleton
class CultivationSystem @Inject constructor(
    private val stateStore: GameStateStore,
    private val eventBus: EventBus
) : GameSystem {
    override val systemName = "CultivationSystem"

    override suspend fun onMonthTick(state: MutableGameState) {
        val disciples = state.disciples
        val updatedDisciples = disciples.map { disciple ->
            processMonthlyCultivation(disciple, state.gameData)
        }
        state.disciples = updatedDisciples
    }

    fun assignCultivationMethod(discipleId: String, methodId: String): Boolean {
        // 从 CultivationService 迁移的逻辑
    }

    fun breakthrough(discipleId: String): BreakthroughResult {
        // 从 CultivationService 迁移的逻辑
    }

    private fun processMonthlyCultivation(disciple: Disciple, gameData: GameData): Disciple {
        // 从 CultivationService.processMonthlyCultivation 迁移
    }
}
```

### 2.5 GameEngine 瘦身为纯门面

重构后 GameEngine 不再持有 `transactionMutex` 和 `engineScope`，所有逻辑委托给 System：

```kotlin
@Singleton
class GameEngine @Inject constructor(
    private val stateStore: GameStateStore,
    private val systemManager: SystemManager,
    private val eventBus: EventBus
) {
    val gameData: StateFlow<GameData> get() = stateStore.gameData
    val disciples: StateFlow<List<Disciple>> get() = stateStore.disciples
    val discipleAggregates: StateFlow<List<DiscipleAggregate>> get() = stateStore.discipleAggregates
    val equipment: StateFlow<List<Equipment>> get() = stateStore.equipment
    val buildings: StateFlow<List<Building>> get() = stateStore.buildings
    val teams: StateFlow<List<Team>> get() = stateStore.teams
    val worldMap: StateFlow<WorldMapData> get() = stateStore.worldMap
    val inventory: StateFlow<InventoryData> get() = stateStore.inventory
    val diplomacy: StateFlow<DiplomacyData> get() = stateStore.diplomacy

    suspend fun advanceDay() {
        stateStore.update { state ->
            systemManager.onDayTick(state)
        }
    }

    suspend fun advanceMonth() {
        stateStore.update { state ->
            systemManager.onMonthTick(state)
        }
    }

    suspend fun advanceYear() {
        stateStore.update { state ->
            systemManager.onYearTick(state)
        }
    }

    // 用户操作入口 — 委托给对应 System
    fun getCultivationSystem(): CultivationSystem = systemManager.getSystem()
    fun getDiscipleSystem(): DiscipleSystem = systemManager.getSystem()
    fun getCombatSystem(): CombatSystem = systemManager.getSystem()
    fun getInventorySystem(): InventorySystem = systemManager.getSystem()
    fun getBuildingSystem(): BuildingSubsystem = systemManager.getSystem()
    fun getDiplomacySystem(): DiplomacySubsystem = systemManager.getSystem()
    fun getExplorationSystem(): ExplorationSystem = systemManager.getSystem()
    fun getEventSystem(): EventSubsystem = systemManager.getSystem()
}
```

### 2.6 TimeSystem 状态同步

`TimeSystem` 内部持有 `_gameData: MutableStateFlow<GameData>`，与 `GameStateStore` 中的 `gameData` 完全独立。重构为：

```kotlin
@Singleton
class TimeSystem @Inject constructor(
    private val stateStore: GameStateStore
) : GameSystem {
    override val systemName = "TimeSystem"

    private var dayAccumulator = 0.0

    override suspend fun onSecondTick(state: MutableGameState) {
        val daysPerTick = GameConfig.Time.DAYS_PER_MONTH.toDouble() /
            (GameConfig.Time.SECONDS_PER_REAL_MONTH * GameConfig.Time.TICKS_PER_SECOND)
        dayAccumulator += daysPerTick
    }

    override suspend fun onDayTick(state: MutableGameState) {
        val gd = state.gameData
        var newDay = gd.gameDay + 1
        var newMonth = gd.gameMonth
        var newYear = gd.gameYear

        if (newDay > GameConfig.Time.DAYS_PER_MONTH) {
            newDay = 1
            newMonth++
            if (newMonth > GameConfig.Time.MONTHS_PER_YEAR) {
                newMonth = 1
                newYear++
            }
        }

        state.gameData = gd.copy(gameDay = newDay, gameMonth = newMonth, gameYear = newYear)
    }
}
```

### 2.7 SystemManager 增强

```kotlin
@Singleton
class SystemManager @Inject constructor(
    private val systems: Set<@JvmSuppressWildcards GameSystem>
) {
    private val systemMap = mutableMapOf<KClass<out GameSystem>, GameSystem>()

    init {
        systems.forEach { system ->
            systemMap[system::class] = system
        }
    }

    inline fun <reified T : GameSystem> getSystem(): T {
        return systemMap[T::class] as? T
            ?: throw IllegalStateException("System ${T::class.simpleName} not found")
    }

    suspend fun onSecondTick(state: MutableGameState) {
        systems.forEach { it.onSecondTick(state) }
    }

    suspend fun onDayTick(state: MutableGameState) {
        systems.forEach { it.onDayTick(state) }
    }

    suspend fun onMonthTick(state: MutableGameState) {
        systems.forEach { it.onMonthTick(state) }
    }

    suspend fun onYearTick(state: MutableGameState) {
        systems.forEach { it.onYearTick(state) }
    }

    fun initializeAll() {
        systems.forEach { it.initialize() }
    }

    fun releaseAll() {
        systems.forEach { it.release() }
    }

    suspend fun clearAll() {
        systems.forEach { it.clear() }
    }
}
```

### 2.8 阶段二验收标准

- [ ] 全局搜索 `core/engine/service/` 下所有 Service 文件已删除或标记为 `@Deprecated`
- [ ] 所有业务逻辑通过 `GameSystem` 实现类承载
- [ ] `GameEngine` 不再持有 `transactionMutex`，所有状态修改委托给 System
- [ ] `TimeSystem` 不再有内部 `_gameData`，从 `GameStateStore` 读取
- [ ] 游戏循环、修炼、弟子、战斗、探索、建筑、外交功能正常

---

## 阶段三：统一库存系统（P1）

> 可与阶段二并行执行。

### 3.1 当前库存相关类

| 类 | 位置 | 状态 |
|----|------|------|
| InventorySystem | `system/InventorySystem.kt` | 活跃，被 InventoryService 包装 |
| InventorySystemV2 | `system/inventory/InventorySystemV2.kt` | 废弃，无外部引用 |
| InventoryService | `service/InventoryService.kt` | 活跃，包装 InventorySystem |
| UnifiedItemService | `service/UnifiedItemService.kt` | 废弃，无外部引用 |
| ItemTransactionManager | `transaction/ItemTransactionManager.kt` | 废弃，无外部引用 |
| OptimizedWarehouseManager | `warehouse/OptimizedWarehouseManager.kt` | 废弃，无外部引用 |
| WarehouseManager 相关 (8个) | `warehouse/` | 部分活跃 |

### 3.2 重构方案

1. **删除** InventorySystemV2 及 `system/inventory/` 下全部 8 个文件
2. **删除** UnifiedItemService、ItemTransactionManager、OptimizedWarehouseManager
3. **合并** InventoryService 逻辑到 InventorySystem，使 InventorySystem 直接通过 `GameStateStore.update` 操作
4. **验证** Warehouse 层中各文件的实际引用，删除无引用的文件

### 3.3 统一后的 InventorySystem

```kotlin
@Singleton
class InventorySystem @Inject constructor(
    private val stateStore: GameStateStore,
    private val eventBus: EventBus
) : GameSystem {
    override val systemName = "InventorySystem"

    fun addEquipment(equipment: Equipment) {
        stateStore.update {
            equipmentList = equipmentList + equipment
        }
    }

    fun removeEquipment(equipmentId: String): Boolean {
        val exists = stateStore.equipment.value.any { it.id == equipmentId }
        if (!exists) return false
        stateStore.update {
            equipmentList = equipmentList.filter { it.id != equipmentId }
        }
        return true
    }

    fun updateEquipment(equipment: Equipment) {
        stateStore.update {
            equipmentList = equipmentList.map {
                if (it.id == equipment.id) equipment else it
            }
        }
    }

    fun getEquipmentById(id: String): Equipment? {
        return stateStore.equipment.value.find { it.id == id }
    }

    fun equipItem(discipleId: String, equipmentId: String, slot: EquipmentSlot): Boolean {
        // 从 InventoryService + InventorySystem 合并的逻辑
    }

    fun unequipItem(discipleId: String, slot: EquipmentSlot): Boolean {
        // 从 InventoryService + InventorySystem 合并的逻辑
    }
}
```

### 3.4 删除文件清单

| 文件 | 删除原因 |
|------|---------|
| `core/engine/system/inventory/InventorySystemV2.kt` | 无外部引用 |
| `core/engine/system/inventory/InventoryLockManager.kt` | 仅被 InventorySystemV2 引用 |
| `core/engine/system/inventory/ItemOperationResult.kt` | 仅被 InventorySystemV2 引用 |
| `core/engine/system/inventory/ConcurrentItemContainer.kt` | 仅被 InventorySystemV2 引用 |
| `core/engine/system/inventory/ItemTypeInfo.kt` | 仅被 InventorySystemV2 引用 |
| `core/engine/system/inventory/ItemContainer.kt` | 仅被 InventorySystemV2 引用 |
| `core/engine/system/inventory/InventoryEvent.kt` | 仅被 InventorySystemV2 引用 |
| `core/engine/system/inventory/InventoryItem.kt` | 仅被 InventorySystemV2 引用 |
| `core/service/UnifiedItemService.kt` | 无外部引用 |
| `core/transaction/ItemTransactionManager.kt` | 无外部引用 |
| `core/warehouse/OptimizedWarehouseManager.kt` | 仅被 ItemTransactionManager 引用 |

### 3.5 阶段三验收标准

- [ ] `system/inventory/` 目录已删除
- [ ] `UnifiedItemService`、`ItemTransactionManager`、`OptimizedWarehouseManager` 已删除
- [ ] `InventoryService` 已删除，逻辑合并到 `InventorySystem`
- [ ] 所有库存操作（添加/删除/装备/卸下）功能正常
- [ ] 全局搜索 `InventorySystemV2`、`UnifiedItemService`、`ItemTransactionManager` 返回 0 结果

---

## 阶段四：拆分 GameViewModel（P1）

> 依赖阶段二的 GameEngine 门面 API 稳定。

### 4.1 当前 GameViewModel 职责分析

| 职责 | 行数估算 | 目标 ViewModel |
|------|---------|---------------|
| 存档管理（save/load/slot） | ~800 | SaveLoadViewModel |
| 对话框路由 | ~300 | 保留在 GameViewModel |
| 战斗队伍管理 | ~400 | BattleViewModel（已有，需增强） |
| 生产系统（炼丹/锻造/药园） | ~500 | ProductionViewModel |
| 世界地图数据 | ~200 | WorldMapViewModel |
| 核心游戏数据订阅 | ~500 | 保留在 GameViewModel |
| UI 状态（loading/error/success） | ~300 | 保留在 GameViewModel |

### 4.2 新 ViewModel 结构

```kotlin
// 核心游戏状态 — 保留，大幅瘦身
@HiltViewModel
class GameViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val gameEngineCore: GameEngineCore,
    private val stateManager: UnifiedGameStateManager,
    val dialogStateManager: DialogStateManager
) : ViewModel() {
    val gameData: StateFlow<GameData> = gameEngine.gameData
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), gameEngine.gameData.value)

    val disciples: StateFlow<List<DiscipleAggregate>> = gameEngine.discipleAggregates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isLoading: StateFlow<Boolean> = stateManager.state.map { it.isLoading }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isSaving: StateFlow<Boolean> = stateManager.state.map { it.isSaving }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // 对话框路由
    fun showDialog(type: DialogType) { dialogStateManager.show(type) }
    fun dismissDialog() { dialogStateManager.dismiss() }

    // 游戏循环控制
    fun pauseGame() { viewModelScope.launch { gameEngineCore.pause() } }
    fun resumeGame() { viewModelScope.launch { gameEngineCore.resume() } }
    fun setGameSpeed(speed: Int) { viewModelScope.launch { gameEngineCore.setSpeed(speed) } }
}

// 存档管理 — 从 GameViewModel 剥离
@HiltViewModel
class SaveLoadViewModel @Inject constructor(
    private val storageFacade: RefactoredStorageFacade,
    private val savePipeline: SavePipeline,
    private val gameEngine: GameEngine,
    private val stateManager: UnifiedGameStateManager
) : ViewModel() {
    private val _saveSlots = MutableStateFlow<List<SaveSlot>>(emptyList())
    val saveSlots: StateFlow<List<SaveSlot>> = _saveSlots.asStateFlow()

    private val _saveLoadState = MutableStateFlow<SaveLoadState>(SaveLoadState.Idle)
    val saveLoadState: StateFlow<SaveLoadState> = _saveLoadState.asStateFlow()

    init {
        viewModelScope.launch { refreshSlots() }
    }

    fun saveGame(slot: Int) {
        viewModelScope.launch {
            _saveLoadState.value = SaveLoadState.Saving
            // ...
        }
    }

    fun loadGame(slot: Int) {
        viewModelScope.launch {
            _saveLoadState.value = SaveLoadState.Loading
            // ...
        }
    }

    fun deleteSave(slot: Int) {
        viewModelScope.launch { /* ... */ }
    }

    private suspend fun refreshSlots() {
        // ...
    }

    sealed class SaveLoadState {
        object Idle : SaveLoadState()
        object Saving : SaveLoadState()
        object Loading : SaveLoadState()
        data class Success(val message: String) : SaveLoadState()
        data class Error(val message: String) : SaveLoadState()
    }
}

// 生产系统 — 新建
@HiltViewModel
class ProductionViewModel @Inject constructor(
    private val gameEngine: GameEngine
) : ViewModel() {
    val alchemySlots: StateFlow<List<AlchemySlot>> = gameEngine.gameData.map { it.alchemySlots }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val forgeSlots: StateFlow<List<ForgeSlot>> = gameEngine.gameData.map { it.forgeSlots }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val productionSlots: StateFlow<List<ProductionSlot>> = /* ... */

    fun startAlchemy(slotIndex: Int, recipeId: String) {
        viewModelScope.launch {
            gameEngine.getCultivationSystem().startAlchemy(slotIndex, recipeId)
        }
    }

    fun startForging(slotIndex: Int, recipeId: String) {
        viewModelScope.launch {
            gameEngine.getCultivationSystem().startForging(slotIndex, recipeId)
        }
    }

    fun collectProduction(slotIndex: Int) {
        viewModelScope.launch { /* ... */ }
    }
}

// 世界地图 — 新建
@HiltViewModel
class WorldMapViewModel @Inject constructor(
    private val gameEngine: GameEngine
) : ViewModel() {
    val worldMapRenderData: StateFlow<WorldMapRenderData> = gameEngine.worldMap
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WorldMapRenderData())

    val battleTeamSlots: StateFlow<List<BattleTeamSlot>> = /* ... */

    fun moveBattleTeam(targetSectId: String) {
        viewModelScope.launch { /* ... */ }
    }

    fun recallBattleTeam() {
        viewModelScope.launch { /* ... */ }
    }
}
```

### 4.3 消除 DiscipleAggregate 重复转换

当前 4 个 ViewModel 各自独立做 `disciples.map { it.toAggregate() }`，重构为在 `GameStateStore` 层面提供聚合数据：

```kotlin
// GameStateStore 中新增
val discipleAggregates: StateFlow<List<DiscipleAggregate>> = _disciples
    .map { list -> list.map { it.toAggregate() } }
    .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, emptyList())
```

所有 ViewModel 直接订阅 `stateStore.discipleAggregates`，消除 4 处重复转换。

### 4.4 UseCase 层处理

当前 UseCase 层（`domain/usecase/`）存在两种模式：

**模式A**：纯代理，直接调用 GameEngine（如 AlchemyUseCase）
```kotlin
// AlchemyUseCase — 建议删除，ViewModel 直接调用 GameEngine
suspend fun startAlchemy(params: StartAlchemyParams): AlchemyResult {
    val success = gameEngine.startAlchemy(params.slotIndex, params.recipeId)
    return if (success) AlchemyResult.Success(params.slotIndex) else AlchemyResult.Error("炼丹启动失败")
}
```

**模式B**：包含编排逻辑（如 CombatUseCase 同时操作 GameEngine + stateManager + eventBus）
```kotlin
// CombatUseCase — 建议保留，但迁移到 System 层内部
suspend fun startExploration(...): ExplorationResult {
    gameEngine.startExploration(...)
    val teamId = stateManager.currentState.teams.lastOrNull()?.id
    eventBus.emit(NotificationEvent(...))
    return ExplorationResult(...)
}
```

**处理策略**：
- 模式A 的 UseCase 删除，ViewModel 直接调用 `GameEngine.getXXXSystem()`
- 模式B 的 UseCase 逻辑迁入对应 System，ViewModel 调用 System 方法

### 4.5 阶段四验收标准

- [ ] GameViewModel 行数 < 1000 行
- [ ] SaveLoadViewModel 独立处理存档管理
- [ ] ProductionViewModel 独立处理生产系统
- [ ] WorldMapViewModel 独立处理世界地图
- [ ] 4 个 ViewModel 不再有重复的 `disciples.map { it.toAggregate() }`
- [ ] 模式A 的 UseCase 已删除
- [ ] 模式B 的 UseCase 逻辑已迁入 System 层

---

## 阶段五：清理死代码和废弃层（P2）

> 最后执行，依赖前面所有迁移完成。

### 5.1 删除废弃的 Coordinator 层

以下 4 个 Coordinator 只被自身文件引用，从未被注入或调用：

| 文件 | 删除原因 |
|------|---------|
| `core/engine/coordinator/GameLoopCoordinator.kt` | 无外部引用，GameEngineCore 已实现游戏循环 |
| `core/engine/coordinator/MonthlyEventCoordinator.kt` | 无外部引用，EventSystem 已处理月度事件 |
| `core/engine/coordinator/BattleCoordinator.kt` | 无外部引用，CombatSystem 已处理战斗 |
| `core/engine/coordinator/RealtimeDataCoordinator.kt` | 无外部引用，GameStateStore 已提供实时数据 |

**保留**：
- `core/engine/coordinator/SavePipeline.kt` — 异步存档管道，活跃使用
- `core/engine/coordinator/SaveLoadCoordinator.kt` — 存档监控，被 SavePipeline 引用
- `core/engine/coordinator/GameLoopCoordinator.kt` — 删除（见上表）

### 5.2 删除废弃的库存子系统

已在阶段三完成，此处确认清单：

- `system/inventory/` 下全部 8 个文件
- `core/service/UnifiedItemService.kt`
- `core/transaction/ItemTransactionManager.kt`
- `core/warehouse/OptimizedWarehouseManager.kt`

### 5.3 删除其他死代码

| 文件/目录 | 删除原因 | 验证方式 |
|-----------|---------|---------|
| `core/warehouse/WarehouseItemPool.kt` | 对象池模式在 Kotlin 中收益极低 | 搜索引用 |
| `core/warehouse/WarehousePager.kt` | 分页查询未使用 | 搜索引用 |
| `core/warehouse/InventoryTransaction.kt` | 事务管理已由 GameStateStore.update 替代 | 搜索引用 |
| `core/warehouse/InventorySearch.kt` | 搜索功能未使用 | 搜索引用 |
| `core/warehouse/InventorySerializer.kt` | 序列化已由 Protobuf 替代 | 搜索引用 |
| `core/warehouse/CompactInventory.kt` | 紧凑存储未使用 | 搜索引用 |
| `core/warehouse/WarehouseCompressor.kt` | 压缩已由 UnifiedCompressionLayer 替代 | 搜索引用 |
| `core/warehouse/WarehouseModels.kt` | 模型已由 core/model 替代 | 搜索引用 |

> 以上 warehouse 文件需逐一验证引用后再删除，不可盲目操作。

### 5.4 删除 Service 层残留

阶段二完成后，确认以下 Service 文件已无引用并删除：

- `core/engine/service/CultivationService.kt`
- `core/engine/service/DiscipleService.kt`
- `core/engine/service/CombatService.kt`
- `core/engine/service/ExplorationService.kt`
- `core/engine/service/EventService.kt`
- `core/engine/service/BuildingService.kt`
- `core/engine/service/InventoryService.kt`
- `core/engine/service/DiplomacyService.kt`
- `core/engine/service/FormulaService.kt`
- `core/engine/service/RedeemCodeService.kt`
- `core/engine/service/SaveService.kt`

### 5.5 删除模式A UseCase

| 文件 | 删除原因 |
|------|---------|
| `domain/usecase/AlchemyUseCase.kt` | 纯代理，ViewModel 直接调用 System |
| `domain/usecase/BuildingOperationUseCase.kt` | 纯代理 |
| `domain/usecase/DiscipleManagementUseCase.kt` | 纯代理 |
| `domain/usecase/ForgingUseCase.kt` | 纯代理 |
| `domain/usecase/BattleTeamUseCase.kt` | 纯代理 |
| `domain/usecase/DiplomacyUseCase.kt` | 纯代理 |

> 模式B UseCase（CombatUseCase、TradeUseCase、ProductionUseCase、SaveLoadUseCase）的逻辑已在阶段二迁入 System 层，此处确认删除。

### 5.6 阶段五验收标准

- [ ] 全局搜索 `GameLoopCoordinator`、`MonthlyEventCoordinator`、`BattleCoordinator`、`RealtimeDataCoordinator` 返回 0 结果
- [ ] `system/inventory/` 目录不存在
- [ ] `core/engine/service/` 目录不存在（或为空）
- [ ] `domain/usecase/` 目录不存在（或为空）
- [ ] 所有 warehouse 文件已验证并清理
- [ ] 项目编译通过，所有功能正常

---

## 重构后的目标架构

```
┌──────────────────────────────────────────────────────────────┐
│  UI Layer                                                     │
│  GameActivity                                                 │
│  ├─ GameViewModel (核心状态 + 对话框路由, <1000行)             │
│  ├─ SaveLoadViewModel (存档管理)                               │
│  ├─ ProductionViewModel (生产系统)                             │
│  ├─ WorldMapViewModel (世界地图)                               │
│  ├─ BattleViewModel (战斗)                                     │
│  ├─ SectViewModel (宗门)                                      │
│  └─ DiscipleViewModel (弟子)                                  │
├──────────────────────────────────────────────────────────────┤
│  Engine Layer (单轨)                                          │
│  GameEngineCore (游戏循环)                                     │
│  └─ GameEngine (门面)                                         │
│      └─ SystemManager                                        │
│          ├─ TimeSystem                                        │
│          ├─ SectSystem                                        │
│          ├─ DiscipleSystem (合并 DiscipleService)              │
│          ├─ CultivationSystem (合并 CultivationService)        │
│          ├─ CombatSystem (合并 CombatService)                  │
│          ├─ InventorySystem (合并 InventoryService)            │
│          ├─ ExplorationSystem (合并 ExplorationService)        │
│          ├─ BuildingSubsystem (合并 BuildingService)           │
│          ├─ DiplomacySubsystem (合并 DiplomacyService)         │
│          ├─ MerchantSubsystem (合并 EventService 商人部分)      │
│          ├─ EventSubsystem (合并 EventService 事件部分)         │
│          ├─ AlchemySubsystem                                   │
│          ├─ ForgingSubsystem                                   │
│          ├─ HerbGardenSubsystem                                │
│          └─ ProductionSubsystem                                │
├──────────────────────────────────────────────────────────────┤
│  State Layer (单路径)                                         │
│  GameStateStore                                               │
│  ├─ 14个 MutableStateFlow (私有)                               │
│  ├─ unifiedState: StateFlow<UnifiedGameState> (combine 派生)   │
│  ├─ discipleAggregates: StateFlow<List<DiscipleAggregate>>    │
│  └─ suspend fun update(block: MutableGameState.() -> Unit)   │
│  UnifiedGameStateManager (观察者通知 + UI元状态)                │
│  EventBus (DomainEvent 通道)                                   │
├──────────────────────────────────────────────────────────────┤
│  Data Layer (保持现状)                                        │
│  RefactoredStorageFacade                                      │
│  ├─ UnifiedStorageEngine                                      │
│  ├─ SavePipeline                                              │
│  ├─ BackupManager                                             │
│  ├─ SlotLockManager                                           │
│  └─ StartupRecoveryCoordinator                                │
└──────────────────────────────────────────────────────────────┘
```

---

## 执行顺序和依赖关系

```
阶段一 (状态路径统一)
  │  ← 所有后续重构的前提，必须先完成
  ▼
阶段二 (Service/System 合并)
  │  ← 依赖阶段一的 GameStateStore.update API
  │
  ├──── 阶段三 (库存统一) ──── 可与阶段二并行
  │
  ▼
阶段四 (ViewModel 拆分)
  │  ← 依赖阶段二的 GameEngine 门面 API 稳定
  ▼
阶段五 (死代码清理) ← 最后执行，依赖前面所有迁移完成
```

---

## 预估影响

| 指标 | 当前 | 重构后预估 |
|------|------|-----------|
| 总代码量 | ~2.5M 字符 | ~1.5-1.8M 字符 (减少 30-40%) |
| 删除文件数 | — | ~30-40 个 |
| 重构文件数 | — | ~50 个 |
| 最大单文件 | CultivationService 153KB | CultivationSystem ~80-100KB |
| GameViewModel | 4000+ 行 | <1000 行 |
| 状态写入路径 | 2条 (mutableXxx + UnifiedGameStateManager) | 1条 (GameStateStore.update) |
| 业务逻辑层 | 2层 (Service + System) | 1层 (System) |
| 库存实现 | 3套 | 1套 |
