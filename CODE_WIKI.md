# 修仙宗门 — 代码架构 Wiki

> 最后更新：2026-06-02 (v3.2.01)

## 目录

1. [架构总览](#架构总览)
2. [引擎层 — 领域 Facade 架构](#引擎层--领域-facade-架构)
3. [状态管理 — GameStateStore](#状态管理--gamestatestore)
4. [游戏引擎 — GameEngineCore](#游戏引擎--gameenginecore)
5. [结算管线 — SettlementCoordinator](#结算管线--settlementcoordinator)
6. [增量保存与数据库](#增量保存与数据库)
7. [Canvas 渲染管线](#canvas-渲染管线)
8. [性能基础设施](#性能基础设施)
9. [构建与 Profile](#构建与-profile)
10. [后续优化项](#后续优化项)

---

## 架构总览

```
┌──────────────────────────────────────────────────┐
│ Layer 2: UI (ViewModel + Compose)                │
│   - Subscribes to GameStateStore.* StateFlows    │
│   - Dialogs managed by DialogStateManager        │
├──────────────────────────────────────────────────┤
│ Layer 1: GameEngineCore + GameEngine             │
│   - EngineCore: game loop (1000ms tick)          │
│   - Engine: business logic (cultivation, battle, │
│     production, diplomacy, exploration, etc.)    │
│   - Writes to GameStateStore via update()        │
└──────────────────────────────────────────────────┘
```

**数据流**：`UI → ViewModel → GameEngine → Service → GameStateStore.update() → StateFlow → Compose`

**核心类**：参见 CLAUDE.md「Key Classes」章节。

---

## 引擎层 — 领域 Facade 架构 (v3.1.99)

### 架构

GameEngine（103 方法，纯协调器）→ 7 个领域 Facade 接口 → 各域 Service/System：

```
GameEngine (协调器, 103方法)
  ├── DiscipleFacade   → DiscipleService, DiscipleEquipmentManager, ...
  ├── BattleFacade     → CombatService, BattleSystem, AISectAttackManager, ...
  ├── BuildingFacade   → BuildingService, HerbGardenSystem, ...
  ├── InventoryFacade  → OptimizedWarehouseManager, ...
  ├── ProductionFacade → ProductionCoordinator, ProductionSubsystem, ...
  ├── DiplomacyFacade  → DiplomacyService, AISectDiscipleManager, ...
  └── SaveFacade       → SaveService, SaveLoadCoordinator, SavePipeline
```

### 目录结构

```
core/engine/domain/
├── battle/       (BattleFacade, BattleFacadeImpl, CombatService, BattleSystem, ...)
├── building/     (BuildingFacade, BuildingFacadeImpl, BuildingService, ...)
├── diplomacy/    (DiplomacyFacade, DiplomacyFacadeImpl, DiplomacyService, ...)
├── disciple/     (DiscipleFacade, DiscipleFacadeImpl, DiscipleService, ...)
├── exploration/  (ExplorationService, MissionSystem, CaveExplorationSystem, ...)
├── inventory/    (InventoryFacade, InventoryFacadeImpl, ...)
├── production/   (ProductionFacade, ProductionFacadeImpl, ProductionCoordinator, ...)
├── save/         (SaveFacade, SaveFacadeImpl, SaveService, SaveLoadCoordinator, ...)
└── settlement/   (SettlementCoordinator, SettlementCache, SettlementScheduler, ...)
```

### ViewModel Facade 直接注入 (v3.2.01)

GameViewModel 除 GameEngine 外，新增 7 个 Facade 直接注入：

```kotlin
class GameViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val discipleFacade: DiscipleFacade,
    private val productionFacade: ProductionFacade,
    private val inventoryFacade: InventoryFacade,
    private val buildingFacade: BuildingFacade,
    private val battleFacade: BattleFacade,
    private val diplomacyFacade: DiplomacyFacade,
    private val saveFacade: SaveFacade,
    ...
)
```

已迁移调用：`buildingFacade.addProductionSlot/moveBuildingDirect/startManualPlanting`、`discipleFacade.updateFocusedDisciple/approveMarriage/clearPendingNotification/updateMonthlySalaryEnabled`。GameEngine 保留为跨域操作协调器。

```kotlin
interface DiscipleFacade {
    suspend fun recruitDisciple(): Disciple
    val disciples: StateFlow<List<Disciple>>
    // ...
}

@Singleton
class DiscipleFacadeImpl @Inject constructor(
    private val stateStore: GameStateStore,
    private val discipleService: DiscipleService
) : DiscipleFacade { ... }
```

### GameData 拆分 (Phase A)

新增 5 个领域 Entity（独立 Room 表），game_data 旧字段保留：

| 新表 | 字段 | DAO |
|------|------|-----|
| `diplomacy_state` | sectRelations, alliances, sectDetails, exploredSects, scoutInfo | DiplomacyStateDao |
| `production_state` | spiritFieldPlants, unlockedRecipes, unlockedManuals, manualProficiencies | ProductionStateDao |
| `patrol_state` | patrolSlots, patrolConfig, patrolConfigs | PatrolStateDao |
| `world_map_state` | worldLevels, cultivatorCaves, caveExplorationTeams | WorldMapStateDao |
| `sect_policy_state` | sectPolicies, autoRecruitFilter, breakthroughConfig 等 | SectPolicyStateDao |

DB v26 迁移：`CREATE TABLE IF NOT EXISTS` — Phase A 零风险。

### DiscipleCompact 轻量表 (v3.2.01 新增)

ECS 风格内存优化：`disciple_compact` Room 表（14 字段 vs 原 Disciple 50+），高频查询场景使用精简模型。

| 字段 | 说明 |
|------|------|
| id, slotId, name | 基础标识 |
| cultivation, realm, realmLayer | 修炼核心数据 |
| lifespan, maxLifespan, isAlive, age | 寿命状态 |
| spiritRoot, combatPower | 灵根/战力 |
| cultivationSpeed, cultivationSpeedBonus, cultivationSpeedDuration, status | 修炼速率/状态 |

`DiscipleCompact.fromDisciple()` / `toDisciple()` 工厂方法双向转换。独立 DAO `DiscipleCompactDao` + 2 个索引（slot_id, slot_id+isAlive）。

DB v27 迁移：`MIGRATION_26_27` 创建 disciple_compact 表 + `MIGRATION_1_26` 合并 v1→v26 顺序迁移链。

### EventBus (25 种事件)

```kotlin
interface DomainEvent { val type: String }
// 修炼: CultivationEvent, BreakthroughEvent
// 战斗: CombatEvent, BattleCompletedEvent, PatrolEvent
// 弟子: DiscipleUpdatedEvent, DiscipleJoinedEvent, DiscipleLeftEvent
// 建筑: BuildingCompletedEvent
// 经济: SpiritStonesChangedEvent
// 外交: SectRelationChangedEvent
// 物品: ItemCraftedEvent, ItemAcquiredEvent
// ... 等 25 种
```

EventBus 通过 `EventBusPort` 接口暴露，支持测试替换。

---

## 状态管理 — GameStateStore

### v3.2.01 架构：三层 StateFlow 拆分

在 v3.1.99 独立流架构之上，新增三层分层 StateFlow，UI 按需订阅：

```
HighFreqState  (spiritStones, gameYear/Month/Phase, isPaused) — 每 tick 变化，UI 高频消费
EntityState    (disciples, equipment*, manuals*, pills, materials, herbs, seeds...) — 实体变化时发射
ConfigState    (sectPolicies, monthlySalary, elderSlots, placedBuildings...) — 极少变化
```

- **HighFreqState**：`combine(5 个 _gameDataFlow.map{}.distinctUntilChanged())`，仅在对应字段实际变化时发射
- **EntityState**：`combine(12 个独立流)`，实体列表变化时发射
- **ConfigState**：`_gameDataFlow.map{}.distinctUntilChanged()`，配置字段变化时发射
- 所有三层 StateFlow 标注 `@Immutable`，配合 Compose Strong Skipping Mode 自动跳过不变重组
- 通过 `GameEngine.highFreqState / entityState / configState` 暴露给 ViewModel
- `unifiedState` 保留为 LEGACY 兼容（仅 GameEngineCore.state 和 UnifiedGameStateManager 使用）

### v3.1.99 架构：独立流单写

> `_state: MutableStateFlow` 已移除。`unifiedState` 从 17 个独立流 `combine` 派生，只读。独立流为唯一事实源，消除双写不一致风险。

### v3.1.97 架构：增量发射（已被 v3.1.99 取代）

> 原架构：单一 `_state: MutableStateFlow<UnifiedGameState>` + 15 个 `.map{}.distinctUntilChanged().stateIn()` 派生流。每 tick 全部 `.map{}` 执行。
>
> 现架构：16 个独立 `MutableStateFlow`，`update()` 事务内 `!==` 引用对比，仅发射实际变化的流。

```
                    ┌────────────────────────────┐
                    │     GameStateStore         │
                    │                            │
  tick → update() ──┤  1. copy current → reusable│
                    │  2. block()                │
                    │  3. !== compare (13 fields)│
                    │  4. emit only changed:     │
                    │     _gameDataFlow ✓         │
                    │     _disciplesFlow ✗ (same)│
                    │     _pillsFlow ✗ (same)    │
                    │     ...                    │
                    │  5. _state.update{} (bw compat)
                    └────────────────────────────┘
```

### 公开 StateFlow 清单

| Flow | 类型 | 发射频率 | 消费者 |
|------|------|---------|--------|
| `gameData` | `StateFlow<GameData>` | 每 tick | SectInfoCard, 存档序列化 |
| `disciples` | `StateFlow<List<Disciple>>` | 弟子变化时 | 弟子列表, 修炼 View |
| `equipmentStacks` | `StateFlow<List<EquipmentStack>>` | 装备变化时 | 仓库 Tab |
| `equipmentInstances` | 同上 | 装备变化时 | 仓库详情 |
| `manualStacks` | 同上 | 功法变化时 | 仓库 Tab |
| `manualInstances` | 同上 | 功法变化时 | 仓库详情 |
| `pills` | 同上 | 丹药变化时 | 仓库/炼丹 |
| `materials` | 同上 | 材料变化时 | 仓库/锻造 |
| `herbs` | 同上 | 草药变化时 | 仓库/炼丹 |
| `seeds` | 同上 | 种子变化时 | 仓库/种植 |
| `storageBags` | 同上 | 储物袋变化时 | 仓库 |
| `teams` | 同上 | 队伍变化时 | 探索 |
| `battleLogs` | 同上 | 战斗结算时 | 战斗结果 |
| `pendingBattleResult` | `StateFlow<BattleResultUIData?>` | 战斗触发时 | BattleResultDialog |
| `pendingNotification` | `StateFlow<GameNotification?>` | 通知触发时 | GameOverlayHost |
| `discipleAggregates` | `StateFlow<List<DiscipleAggregate>>` | 弟子变化时 | UI 消费（带指纹缓存） |
| `sectCombatPower` | `StateFlow<Long>` | 战力变化时 | SectInfoCard |
| `aiSectCombatPowers` | `StateFlow<Map<String, Long>>` | AI 弟子变化时 | 外交 |

### 更新入口与同步规则

所有修改必须经过以下受控入口，确保独立流与 `_state` 同步：

| 入口 | 方法 | 适用场景 |
|------|------|---------|
| **主事务** | `suspend fun update(block)` | tick 驱动更新、玩家操作 |
| **快照加载** | `suspend fun loadFromSnapshot(...)` | 存档加载 |
| **结算合并** | `fun swapFromShadow(shadow)` | 月度/年度结算 |
| **直接更新** | `updateGameDataDirect()` / `updateXxxDirect()` | 特定字段 UI 快速更新 |
| **重置** | `suspend fun reset()` | 新游戏 / 清档 |

### 向后兼容

`unifiedState: StateFlow<UnifiedGameState>` 保留，来自 `_state.asStateFlow()`。`_state` 在每个入口同步更新。消费者可逐步迁移到独立流。

### 指纹缓存

- `discipleAggregates`：`ConcurrentHashMap<String, DiscipleAggregate>` 按弟子 ID 缓存，`sourceRef === disciple` 引用有效性检查
- `sectCombatPower`：`CachedPower(fingerprint, power)` 按战力指纹缓存，仅在 `combine(disciplesFlow, equipmentInstancesFlow, manualInstancesFlow)` 任一变化时重算
- 两个缓存在 `loadFromSnapshot()` / `reset()` / `swapFromShadow()` 时清空

### 增量保存与脏追踪 (v3.2.01)

**GameStateRepository** 提供字段级脏追踪：

```kotlin
class GameStateRepository {
    data class DirtySet(gameData, disciples, equipmentStacks, ...)  // 13 个布尔标记
    
    fun markDirty(gameData = false, disciples = false, ...)  // 标记脏字段
    fun markAllDirty()  // 全部标记（存档加载时）
    
    suspend fun flushDirtyState(gameData, disciples, ...) {
        // 仅写入脏字段
        // coroutineScope { launch(Dispatchers.IO) { deleteAll + insertAll } } 并行写入
    }
}
```

- 所有 `updateXxxDirect()` 方法在写入后自动调用 `repository.markDirty(xxx = true)`
- `update()` 事务内变更检测后自动标记脏字段
- `flushDirtyState()` 仅写入变化的表，脏字段间 `coroutineScope` 并行执行
- `StorageEngine.incrementalSave(slot)` 从 unifiedState 快照提取脏数据，保存延迟从 ~200ms 降至 ~20ms

### SystemManager 依赖图并行 (v3.2.01)

```kotlin
// 系统按 @SystemPriority 分组
priorityGroups = systems.groupBy { it.annotation.order }.toSortedMap().values

// 同级并行，组间串行
private suspend fun executeInParallelGroups(state, action) {
    for (group in priorityGroups) {
        if (group.size == 1) {
            action(group.first(), state)  // 单系统跳过协程开销
        } else {
            coroutineScope {
                group.forEach { system -> launch { action(system, state) } }
            }
        }
    }
}
```

独立 MutableStateFlow 的更新在 `_state.update {}` **之后**、`transactionMutex.withLock {}` **之内**执行，确保：
- 不受 `_state.update {}` 内部 CAS 重试影响
- `transactionMutex` 防止与 `updateGameDataDirect()` 等入口并发
- `_state` 是最新值后再同步独立流，保证一致性

### manualStacks 数据流

`manualStacks` 直接从 `_manualStacksFlow` 透传，经 `GameViewModel` 以 `.stateIn()` 暴露给 UI。**不含**跨弟子背包过滤——功法选择 UI 自行按当前弟子的已学功法做同名去重。

---

## 游戏引擎 — GameEngineCore

### Tick 循环

| 参数 | 值 | 位置 |
|------|-----|------|
| TICK_INTERVAL_MS | 1000ms | `GameEngineCore.kt:60` |
| MIN_TICK_DELAY_MS | 50ms | `GameEngineCore.kt:61` |
| ADAPTIVE_MAX_INTERVAL_MS | 2000ms | `GameEngineCore.kt:65` |
| 自适应策略 | 连续 3 次超时 → ×1.5；正常后 ×0.8 恢复 | `GameEngineCore.kt:158-169` |

### 关键路径

```
startGameLoop() → Dispatchers.Default coroutine
  → tick() → tickInternal()
    → stateStore.update { ... }
      → systemManager.onPhaseTick()
      → auto-save check
    → settlement coordinator (shadow swap)
    → patrol battle results
```

---

## 结算管线 — SettlementCoordinator

### 架构

```
tickInternal()
  → monthChanged?
    → stateStore.createShadow()  // 快照当前状态
    → settlementCoordinator.scheduleMonthly(shadow)  // 调度结算阶段
  → executeStep(timeBudgetMs=1)  // 每 tick 执行 1ms 预算的结算
    → 完成? → onSettlementComplete() → swapFromShadow() → 结算数据写入 _state
```

### 结算阶段（按月）

| 阶段 | 职责 |
|------|------|
| `Phase_BuildCache` | 构建 SettlementCache（脏标记、修炼速率） |
| `Phase_FocusedDisciple` | 处理关注弟子（立即结算） |
| `Phase_CleanDiscipleBatch` | 处理无变化弟子的被动增长 |
| `Phase_DirtyDiscipleBatch` | 批量处理有变化弟子（突破、装备等） |
| `Phase_Production` | 生产系统月结算 |
| `Phase_WorldEvents` | 世界事件（探索、外交、生育等） |

### 异常恢复 (v3.1.98)

- `executeStep()` 包裹 try-catch，异常时调用 `resetOnError()` 清空 `shadowState`/`currentCache`/`scheduler`
- `shadowState` / `currentCache` 标记 `@Volatile` 防止 UI 线程 `cancelPendingWork()` 并发问题
- 结算异常 → 状态重置 → 下个 tick 正常继续 → 下个月重新结算（不丢数据，只推迟）

### forceCompleteSettlement()

当月变/年变时若仍有 pending 结算，循环执行 `executeStep(timeBudgetMs=5)` 直到完成。已在 `executeStep` 层面保护，不会死循环。

---

## Canvas 渲染管线

### 山门地图 (SectGroundCanvas)

```
┌──────────────────────────────────────────┐
│ 设备分级判断 (Runtime.maxMemory >= 256MB?)│
├──────────────────────────────────────────┤
│ 高配/中配: 建筑预烘焙                     │
│   Layer 0: bakedMapBmp (fullMapBmp + 建筑) │
│     ARGB_8888 (高配 ≥384MB)               │
│     RGB_565 (中配 256-384MB, 省50%内存)    │
│     remember(fullMapBmp, placedBuildings)  │
│   Layer 1: 动态 (网格+预览+移动建筑0.5α)   │
├──────────────────────────────────────────┤
│ 低配: 建筑动态绘制                         │
│   Layer 0: fullMapBmp (纯地形)            │
│   Layer 1: 所有建筑 drawImage             │
│   Layer 2: 网格+预览+移动建筑0.5α          │
└──────────────────────────────────────────┘
```

**网格线**：行列索引视口裁剪 + 线长从全图(3072px)裁剪至可见范围(~1080px)。

**移动建筑**：从烘焙层排除，0.5f alpha 独立绘制——每帧不重建 Bitmap。

### 世界地图 (MapCanvas)

| 优化项 | 实现 |
|--------|------|
| `paths` Path 缓存 | `remember(paths)` — 仅在宗门关系变化时重建 |
| `caveExplorationPaths` Path 缓存 | `remember(caveExplorationPaths)` — 探索路径变化时重建 |
| Color/Stroke 提取 | Canvas lambda 外提取，避免每帧重复创建对象 |

---

## 性能基础设施

### GCOptimizer (v3.2.01 更新)

| GC Type | 触发条件 | 动作 |
|---------|---------|------|
| SOFT | 75% 内存 | 清除非必要缓存 |
| HARD | 85% 内存 | 缩减对象池+清空缓存 |
| CRITICAL | 92% 内存 | 日志提示，委托 ART 自主管理 |
| MANUAL | 手动触发 | 日志提示，委托 ART 自主管理 |

> **v3.2.01 变更**：移除 `System.gc()` 和 `System.runFinalization()` 调用。ART 分代并发 GC（Concurrent Copying）自主管理更高效，显式 gc() 触发 Full GC Stop-The-World 导致游戏卡顿。

### DynamicMemoryManager

设备等级（已有，v3.1.97 中用于 Canvas 烘焙决策）：

| Tier | RAM | heap | Canvas 策略 |
|------|-----|------|------------|
| LOW | < 4GB | < 256MB | 跳过烘焙，动态绘制 |
| MEDIUM | 4-6GB | 256-384MB | RGB_565 (18MB/层) |
| HIGH | 6-12GB | 384-512MB | ARGB_8888 (36MB/层) |
| ULTRA | 12GB+ | > 512MB | ARGB_8888 全开 |

### FrameMetricsMonitor (v3.2.01 新增)

```kotlin
@Singleton
class FrameMetricsMonitor {
    val jankEvents: SharedFlow<FrameMetricsEvent>  // jank 事件流
    fun startMonitoring(window: Window)   // 注册 OnFrameMetricsAvailableListener
    fun stopMonitoring(window: Window)    // 注销监听器
    fun getStats(): FrameMetricsStats     // 统计汇总
    fun resetStats()                      // 重置统计
}
```

- **Jank 检测**：16.6ms（60fps）/ 50ms（严重 jank）双阈值
- **指标**：TOTAL_DURATION / DRAW_DURATION（API 31+）/ LAYOUT_MEASURE_DURATION（API 31+）
- **生命周期绑定**：GameActivity.onResume 启动 / onPause + onDestroy 停止
- **统计输出**：总帧数、jank 帧数/率、严重 jank 数、平均帧时间

### UnifiedPerformanceMonitor

已有：tick 耗时、帧时间(Choreographer.FrameCallback)、内存、FPS、保存队列。
待加：重组计数、内存分配追踪。

---

## 构建与 Profile

### 版本

| 字段 | 值 |
|------|-----|
| versionCode | 3201 |
| versionName | 3.2.01 |
| compileSdk / targetSdk | 35 |
| minSdk | 24 |
| Kotlin | 2.0.21 |
| Compose BOM | 2025.02.00 |

### Compose Compiler

- **插件**：`org.jetbrains.kotlin.plugin.compose`（Kotlin 2.0 原生）
- **已移除**：`composeOptions { kotlinCompilerExtensionVersion = '1.5.8' }`（冗余/冲突）
- **默认启用**：Strong Skipping Mode
- **指标**：`composeCompiler { reportsDestination / metricsDestination }` → `build/compose_metrics/`

### Baseline Profile

- **模块**：`:baselineprofile`（`com.android.test` plugin）
- **生成器**：`BaselineProfileGenerator.collect(packageName="com.xianxia.sect", includeInStartupProfile=true)`
- **生成方式**：本地真机运行 `:baselineprofile:generateReleaseBaselineProfile`，生成文件提交 `app/src/main/baseline-prof.txt`

### Lifecycle 感知收集 (v3.2.01 全量完成)

- **依赖**：`lifecycle-runtime-compose:2.8.7`
- **模式**：`collectAsStateWithLifecycle()` 全量替代 `collectAsState()`
- **覆盖**：14 个 UI 文件共 158 处订阅全部迁移，零 `collectAsState()` 残余
- **新增注入**：GameViewModel 新增 7 个 Facade 直接注入 + 4 个独立 StateFlow（elderSlots/sectPolicies/manualProficiencies/residenceSlots）

---

## 后续优化项

| 优先级 | 描述 | 预估收益 | 状态 |
|--------|------|---------|------|
| P1 | `snapshotFlow` 用于修炼进度条等逐帧动画（绕过重组） | 减少高频动画重组 | 待实施 |
| P1 | FrameMetrics 接入 UnifiedPerformanceMonitor 统一框架 | 监控统一 | 待实施 |
| P2 | `graphicsLayer` 用于地图平移/按钮缩放等视觉动画 | 零重组动画 | 待实施 |
| P2 | 完成 Phase B：GameData 重型字段读取路径切换到领域实体表 | 大幅减少 Room 读取 | 部分实施 |
| P2 | 消除 Protobuf Base64 中间层（TEXT → BLOB 直存 ByteArray）| 序列化性能提升 30-40% | 待实施 |
| P3 | Cloud Profiles 替代本地生成 Baseline Profile | CI 自动化 | 待实施 |
| P3 | R8 full mode (`-Pandroid.enableR8.fullMode=true`) | 更激进字节码优化 | 待实施 |
| ~~P1~~ | ~~FrameMetricsAggregator 集成~~ | ~~已完成~~ (v3.2.01) | ✅ |
| ~~P2~~ | ~~3 层 StateFlow 拆分~~ | ~~已完成~~ (v3.2.01) | ✅ |
| ~~持续~~ | ~~UI 消费者从 unifiedState 迁移到独立子流~~ | ~~已完成~~ (v3.2.01) | ✅ |
