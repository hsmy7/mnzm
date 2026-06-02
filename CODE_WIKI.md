# 修仙宗门 — 代码架构 Wiki

> 最后更新：2026-06-02 (v3.2.00)

## 目录

1. [架构总览](#架构总览)
2. [引擎层 — 领域 Facade 架构](#引擎层--领域-facade-架构)
3. [状态管理 — GameStateStore](#状态管理--gamestatestore)
4. [游戏引擎 — GameEngineCore](#游戏引擎--gameenginecore)
5. [结算管线 — SettlementCoordinator](#结算管线--settlementcoordinator)
5. [Canvas 渲染管线](#canvas-渲染管线)
6. [性能基础设施](#性能基础设施)
7. [构建与 Profile](#构建与-profile)
8. [后续优化项](#后续优化项)

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

### Facade 模式

每个 Facade 定义接口契约，Impl 通过 Hilt `@Singleton` 注入。GameEngine 通过接口依赖 Facade：

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

### 独立流一致性保证 (v3.1.98)

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

### GCOptimizer

| GC Type | 触发条件 | System.gc() |
|---------|---------|------------|
| SOFT | 75% 内存 | ❌ 清除非必要缓存 |
| HARD | 85% 内存 | ❌ 缩减对象池+清空缓存 |
| CRITICAL | 92% 内存 | ✅ |
| MANUAL | 手动触发 | ✅ |

### DynamicMemoryManager

设备等级（已有，v3.1.97 中用于 Canvas 烘焙决策）：

| Tier | RAM | heap | Canvas 策略 |
|------|-----|------|------------|
| LOW | < 4GB | < 256MB | 跳过烘焙，动态绘制 |
| MEDIUM | 4-6GB | 256-384MB | RGB_565 (18MB/层) |
| HIGH | 6-12GB | 384-512MB | ARGB_8888 (36MB/层) |
| ULTRA | 12GB+ | > 512MB | ARGB_8888 全开 |

### UnifiedPerformanceMonitor

已有：tick 耗时、帧时间(Choreographer.FrameCallback)、内存、FPS、保存队列。
待加：重组计数、内存分配追踪。

---

## 构建与 Profile

### 版本

| 字段 | 值 |
|------|-----|
| versionCode | 3197 |
| versionName | 3.1.97 |
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

### Lifecycle 感知收集

- **依赖**：`lifecycle-runtime-compose:2.8.7`
- **模式**：`collectAsStateWithLifecycle()` 替代 `collectAsState()`
- **覆盖**：`MainGameScreen`(7) + `GameOverlayHost`(38)

---

## 后续优化项

| 优先级 | 描述 | 预估收益 |
|--------|------|---------|
| P1 | `snapshotFlow` 用于修炼进度条等逐帧动画（绕过重组） | 减少高频动画重组 |
| P1 | `FrameMetricsAggregator` 集成（重组/布局/绘制三阶段帧时间） | 精确定位瓶颈 |
| P2 | 模块 1 完整实施：3 层 StateFlow 拆分（HighFreq/Entity/Config） | 进一步减少 tick 流发射 |
| P2 | `graphicsLayer` 用于地图平移/按钮缩放等视觉动画 | 零重组动画 |
| P3 | Cloud Profiles 替代本地生成 Baseline Profile | CI 自动化 |
| P3 | R8 full mode (`-Pandroid.enableR8.fullMode=true`) | 更激进字节码优化 |
| 持续 | UI 消费者从 `unifiedState` 迁移到独立子流 | 渐进降低 `_state` 耦合 |
