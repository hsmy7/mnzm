












# 修仙宗门 — 代码架构 Wiki

> 本文档描述项目**当前**的代码架构，供开发者快速了解整体设计。
> 
> **维护原则：** 增删改查永远是"现在长什么样"，不记录"以前什么样、怎么变的"。
> 已完成的历史变更不保留，仅保留**未完成/待执行的计划**。
> 修改代码后同步更新本文档，保持文档与代码一致。

## 目录

1. [架构总览](#架构总览)
2. [Gradle 模块化架构](#gradle-模块化架构)
3. [引擎层 — 领域 Facade 架构](#引擎层--领域-facade-架构)
4. [状态管理 — GameStateStore](#状态管理--gamestatestore)
5. [游戏时间系统 — GameTimeClock](#游戏时间系统--gametimeclock)
6. [游戏引擎 — GameEngineCore](#游戏引擎--gameenginecore)
7. [血炼池 — Blood Refining Pool](#血炼池--blood-refining-pool)
8. [结算管线 — SettlementCoordinator](#结算管线--settlementcoordinator)
9. [Canvas 渲染管线](#canvas-渲染管线)
10. [性能基础设施](#性能基础设施)
11. [世界地图重构](#世界地图重构)
12. [GPU 分级渲染系统](#gpu-分级渲染系统)
13. [活动系统](#活动系统)
14. [构建与 Profile](#构建与-profile)
15. [事件驱动惰性求值](#事件驱动惰性求值)

---

## 架构总览

```
┌──────────────────────────────────────────────────┐
│ Layer 2: UI (ViewModel + Compose)                │
│   - Subscribes to GameStateStore.* StateFlows    │
│   - Dialogs managed by DialogStateManager        │
├──────────────────────────────────────────────────┤
│ Layer 1: GameEngineCore + GameEngine             │
│   - EngineCore: frame-driven accumulator loop   │
│   - Engine: business logic (cultivation, battle, │
│     production, diplomacy, exploration, etc.)    │
│   - Writes to GameStateStore via update()        │
└──────────────────────────────────────────────────┘
```

**数据流**：`UI → ViewModel → GameEngine → Service → GameStateStore.update() → StateFlow → Compose`

**核心类**：参见 CLAUDE.md「Key Classes」章节。

---

## Gradle 模块化架构

### 模块结构

项目分为 **6 个 Gradle 模块**，按 Clean Architecture 分层：

```
android/
├── :app                              ← 入口壳（Application + MainActivity + Hilt DI 全局织入）
│
├── :core:domain                      ← 纯 Kotlin/JVM 模块（零 Android Framework 依赖）
│   ├── core/model/                   ← 数据类 (GameData, Disciple, Items, ...)
│   ├── core/state/                   ← GameStateStore 接口 + GameStateSnapshotProvider
│   ├── core/registry/                ← 静态数据 (EquipmentRegistry, ItemRegistry, ...)
│   ├── core/config/                  ← JSON 配置模型
│   ├── core/event/                   ← 游戏事件定义 (EventBusPort)
│   ├── core/repository/              ← Repository 接口（13 个域接口）
│   └── core/perf/                    ← GPU 分级检测
│
├── :core:engine                      ← Kotlin 模块 + 协程（依赖 domain，不依赖 data）
│   ├── core/engine/                  ← GameEngineCore + GameEngine + 9 域扩展文件
│   ├── core/engine/service/          ← 所有 Service (Cultivation, Mail, ...)
│   ├── core/engine/system/           ← ECS-like 系统 (TimeSystem, InventorySystem, ...)
│   ├── core/engine/domain/           ← 按领域分包 (battle/build/disciple/...)
│   ├── core/concurrent/              ← ShardedSlotLock 并发工具
│   ├── core/config/                  ← BuildingConfigService
│   └── core/util/                    ← 工具类 (CoroutineScopeProvider, DomainLog, ...)
│
├── :core:data                        ← Android 库模块（依赖 Room, MMKV, ProtoBuf）
│   ├── data/local/                   ← Room DB, DAOs, Migrations, TypeConverters
│   ├── data/facade/                  ← StorageFacade
│   ├── data/engine/                  ← StorageEngine, RecoveryManager, SavMigrator
│   ├── data/serialization/           ← 序列化层 (ProtoBuf/JSON)
│   ├── data/compression/             ← LZ4/Zstd 压缩
│   ├── data/crypto/                  ← 加密
│   ├── data/wal/                     ← WAL 日志
│   ├── data/memory/                  ← DynamicMemoryManager
│   ├── data/incremental/             ← ChangeTracker, ChangeLogPersistence
│   └── core/repository/              ← 6 个 Repository 实现 (从 engine 移入)
│
├── :core:ui                          ← Android 库模块（依赖 Compose, domain）
│   ├── ui/theme/                     ← 主题、颜色、字体
│   ├── ui/components/                ← 共享 Compose 组件 (GameButton, ItemCard, ...)
│   └── ui/navigation/                ← 导航定义
│
└── :feature:game                     ← Android 库模块（游戏 UI 实现）
    ├── ui/game/                      ← 所有游戏界面 (tabs, dialogs, map, ViewModels)
    ├── ui/game/tabs/                 ← 主标签页 (Disciples, Buildings, Warehouse, ...)
    ├── ui/game/dialogs/              ← 功能弹窗 (Alchemy, Forge, HerbGarden, ...)
    └── ui/game/map/                  ← 世界地图 (MapBackground, MapTileCache, ...)
```

### 依赖方向

```
feature:game  ──→  core:ui  ──→  core:domain
       │              │
       ├──────────→ core:engine ──→ core:domain
       │
       └──────────→ core:data ──→ core:domain
```

**严格规则：**
- `core:domain` — 零外部依赖（纯 Kotlin/注解），不依赖任何其他模块
- `core:engine` — 仅依赖 `core:domain`（不依赖 `core:data`） 
- `core:data` — 仅依赖 `core:domain`（Room Entity 需要 model）
- `core:ui` — 仅依赖 `core:domain`
- `feature:game` — 依赖所有 core 模块
- `:app` — 依赖所有模块（用于 Hilt 全局织入）

### 域接口清单

| 接口 | 模块 | 实现位置 | 用途 |
|------|------|---------|------|
| `DiscipleRepository` | domain | `:core:data` | 弟子 CRUD |
| `WorldRepository` | domain | `:core:data` | 探索队/建筑/配方/战斗日志 |
| `InventoryRepository` | domain | `:core:data` | 功法/丹药/材料/种子/草药 |
| `EquipmentRepository` | domain | `:core:data` | 装备栈/实例 |
| `ForgeRepository` | domain | `:core:data` | 锻造槽位 |
| `GameDataRepository` | domain | `:core:data` | 游戏主数据 CRUD + 清档 |
| `MailRepository` | domain | `:app` (MailRepositoryImpl) | 邮件持久化 |
| `SaveStorage` | domain | `:app` (SaveStorageImpl) | 存档持久化（封装 StorageFacade） |
| `ProductionSlotDataPort` | domain | `:core:data` | 生产槽位 DAO 抽象 |
| `GameHeavyDataPort` | domain | `:core:data` | 重型数据 BLOB 读写 |
| `HeavyDataDecoder` | domain | `:core:data` | 重型数据 Protobuf 解码 |

### Hilt DI 桥接层

所有域接口→实现绑定集中在 `app/.../di/BridgeBindingsModule.kt`：

```kotlin
@Module @InstallIn(SingletonComponent::class)
object BridgeBindingsModule {
    @Provides @Singleton
    fun provideDiscipleRepository(impl: DiscipleRepositoryImpl): DiscipleRepository = impl
    // ... 共 9 组绑定（6 Repository + 3 DataPort）
}
```

另有 `CoreModule` 提供 `MailRepository` / `SaveStorage` 绑定。

---

## 引擎层 — 领域 Facade 架构

### 架构

GameEngine（精简协调器）→ 9 个按域拆分的扩展文件 + 7 个领域 Facade 接口：

```
GameEngine.kt (精简协调器)
  ├── 9 个扩展文件：
  │     GameEngineBattleOps.kt / BuildingOps.kt / Coordination.kt
  │     DiplomacyOps.kt / DiscipleOps.kt / Extensions.kt
  │     InventoryOps.kt / ProductionOps.kt / SaveOps.kt
  └── 7 个领域 Facade：
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

### GameService / GameSystem 职责标注

两类标注注解用于标记 Service 和 System 的职责边界，便于代码导航和依赖审计：

```kotlin
// Annotation 定义 (core/engine/annotation/)
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class GameService(val name: String)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AutoTickSystem(val name: String)
```

| 标注 | 用途 | 文件数 |
|------|------|--------|
| `@GameService` | UI/Facade 驱动的业务 Service | 6 个 |
| `@AutoTickSystem` | Tick 自动执行的 System（仅文档用途） | 7 个 |

**边界规则**：
- Service 不得在 tick 内被直接调用
- System 之间不得直接调用（通过 EventBus 通知）
- `@AutoTickSystem` 使用 `@Retention(SOURCE)` 避免与 `core.engine.system.GameSystem` 接口同名冲突

### 代码质量基础设施

**编码规范遵循原则**：
- 禁止 `!!` 强制解包，通过 `?.` / `?:` / `checkNotNull()` 安全访问
- 禁止 `runBlocking`（仅测试可用 `runTest`）
- 禁止空 catch 块
- Detekt 违规零增长（baseline 只缩不增）

**GameViewModel Delegate 模式**：
GameViewModel 通过 **9 个 Delegate** 拆分领域逻辑：

```
delegate/
├── DiscipleDelegate.kt        弟子管理（招募/驱逐/装备/道侣）
├── InventoryDelegate.kt       物品管理（购买/出售/自动购买）
├── NavigationDelegate.kt      导航/对话框
├── PlantingDelegate.kt        种植
├── BuildingDelegate.kt        建筑（建造/拆除/搬迁/住宅）
├── BeastAttackDelegate.kt     凶兽袭击
├── WarningDelegate.kt         进攻预警
├── SectDelegate.kt            宗门等级/改名/奖励
└── AutoAssignDelegate.kt      自动委派策略
```

**Dao 拆分**：
`Daos.kt` 拆分为 **18 个领域文件**（按实体类型分组），Room KSP 自动发现。

**代码覆盖 & CI**：
- **Kover** 覆盖率工具已集成（根 + app + core/data）
- **CI 管道**：`.github/workflows/ci.yml`（compile + test + detekt + kover）

**构建检查**：`./gradlew compileReleaseKotlin testReleaseUnitTest detekt koverHtmlReport`

### DiscipleCompact 轻量表

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

### 三层 StateFlow 架构

状态管理采用三层 StateFlow 分层架构，UI 按需订阅：

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

| 入口 | 方法 | 并发保护 | 适用场景 |
|------|------|---------|---------|
| **主事务** | `suspend fun update(block)` | `transactionMutex.withLock { }` | tick 驱动更新、玩家操作 |
| **快照加载** | `suspend fun loadFromSnapshot(...)` | `transactionMutex.withLock { }` | 存档加载 |
| **结算合并** | `suspend fun swapFromShadow(shadow)` | 在 `update { }` 内执行 | 月度/年度结算 |
| **重置** | `suspend fun reset()` | `transactionMutex.withLock { }` | 新游戏 / 清档 |

> ⚠️ `updateGameDataDirect()` / `updateXxxDirect()` 方法已废弃。这些方法直接写 StateFlow.value，绕过 `transactionMutex`，存在竞态条件。所有外部调用已迁移到 `stateStore.update { }`。保留仅为内部兼容，不建议新代码使用。

### 向后兼容

`unifiedState: StateFlow<UnifiedGameState>` 保留，来自 `_state.asStateFlow()`。`_state` 在每个入口同步更新。消费者可逐步迁移到独立流。

### 指纹缓存

- `discipleAggregates`：`ConcurrentHashMap<String, DiscipleAggregate>` 按弟子 ID 缓存，`sourceRef === disciple` 引用有效性检查
- `sectCombatPower`：`CachedPower(fingerprint, power)` 按战力指纹缓存，仅在 `combine(disciplesFlow, equipmentInstancesFlow, manualInstancesFlow)` 任一变化时重算
- 两个缓存在 `loadFromSnapshot()` / `reset()` / `swapFromShadow()` 时清空

### DomainStateProvider — 领域状态提供者

为 GameData 拆分 Phase B 做准备，`DomainStateProvider` 从 `GameData` 的 263 个字段中按领域提取 5 个子 StateFlow：

```
stateStore.gameData (StateFlow<GameData>)
  ├── .map { it.extractDiplomacyState() }
  │   → diplomacyState: StateFlow<DiplomacyDomainState>
  ├── .map { it.extractProductionState() }
  │   → productionState: StateFlow<ProductionDomainState>
  ├── .map { it.extractPatrolState() }
  │   → patrolState: StateFlow<PatrolDomainState>
  ├── .map { it.extractWorldMapState() }
  │   → worldMapState: StateFlow<WorldMapDomainState>
  └── .map { it.extractSectPolicyState() }
      → sectPolicyState: StateFlow<SectPolicyDomainState>
```

每个 `extractXxxState()` / `mergeXxxState()` 定义在对应的 domain 模型中 (core/model/domain/)。
领域模型不标注 `@Serializable`，序列化仍由 `GameData` 负责，避免 ProtoBuf Set/Map 兼容问题。

Phase B 将把 Data 层读写逐步切换到领域 DAO（独立表），DomainStateProvider 届时改为从 Repository 读取。

### 状态一致性：统一 Mutex 序列化

#### 方案

```

玩家操作 ──→ update { mutex }       玩家操作 ──→ update { mutex }
结算合并 ──→ swapFromShadow() 无锁   结算合并 ──→ update { mutex }
              ↑ 竞态                          ↑ 互斥序列化
```

- `swapFromShadow()` 改为 `suspend fun`，整个合并包裹在 `stateStore.update { }` 中
- `shadowOrigin` 在锁外读取（`@Volatile`），合并和写回在锁内
- 所有 `updateXxxDirect()` 外部调用清零：`GameEngine.kt` 2 处 + `CultivationService.kt` 12 处全部迁移到 `stateStore.update { }`

#### 状态读取规范

| 场景 | ✅ 正确 | ❌ 错误 |
|------|---------|---------|
| 业务逻辑读 | `stateStore.disciples.value`（直接 StateFlow，零延迟） | `stateStore.unifiedState.value.disciples`（`stateIn` 有调度延迟） |
| UI 订阅 | `store.disciples.collectAsState()` | — |
| 事务内读 | `MutableGameState.disciples`（当前事务数据） | 读取外部 StateFlow |

#### 字段合并策略

**GameData**：`@SettlementStrategy` 注解驱动 + `GameDataSettlementCoverageTest` 编译期检查。槽位字段（elderSlots/spiritMineSlots/librarySlots）使用 CUSTOM 合并器，允许结算清除操作穿透（origin 有值 shadow 清空 → 对 main 也清除）。

**Disciple**：`mergeDiscipleAfterSettlement()` 集中管理 + `DiscipleMergeCoverageTest` 编译期检查。采用

```
mergeDiscipleAfterSettlement(main, shadow, origin):
  ├── 标量 delta 合并
  │   cultivation, lifespan: main + (shadow - origin)
  ├── 无条件 shadow（仅结算修改）
  │   realm, realmLayer
  ├── 条件保留（玩家可能修改 → main≠origin 时保留 main）
  │   cultivationSpeedBonus/Duration
  ├── 子字段级合并（5 个专用合并函数）
  │   ├── mergeEquipment(main, shadow, origin)
  │   │   ├ 结算域: nurture×4 → shadow
  │   │   ├ 玩家域: weaponId×4, autoEquip, spiritStones → main
  │   │   └ 共享域: storageBagItems → set delta (main + shadow新增 - shadow删除)
  │   ├── mergeCombat(main, shadow, origin)
  │   │   ├ 结算域: baseXxx×7, variance×7, 统计×3 → shadow
  │   │   └ 争议域: currentHp/Mp → shadow 仅当结算显式修改时（突破失败10%惩罚）
  │   ├── mergeManualIds(main, shadow, origin): 集合 delta
  │   ├── mergePillEffects(main, shadow, origin): bonus×13 → main, duration → delta
  │   └── mergeSkills(main, shadow, origin): loyalty/salary → shadow, 其余 → main
  ├── 条件覆盖（已有模式）
  │   isAlive: died||revived → shadow, else → main
  └── 玩家操作字段（显式保留）
      discipleType, status, statusData
  ← 其他所有字段由 copy() 默认保留
```

**设计对标**：子字段级合并方案对标 Unreal Engine GAS 的 AttributeSet Aggregator（BaseValue + Modifier 叠加）、Bevy ECS 的 Component 级 Change Detection、Photon Fusion 的 Predict-Reconcile 模式。

#### Do's and Don'ts

| ✅ DO | ❌ DON'T |
|-------|---------|
| 所有状态修改用 `stateStore.update { }` | 直接写 `_xxxFlow.value` |
| 读取快照用直接 StateFlow（`disciples.value`） | 在业务逻辑中读 `unifiedState.value` |
| 多步操作合并到一个 `update { }` | `updateGameData()` + `syncAllDiscipleStatuses()` 分两步 |
| 新增 Disciple 字段时更新 `DiscipleMergeCoverageTest` | 新增字段不分类 |
| 新增 GameData 字段时加 `@SettlementStrategy` | 新增字段不加注解 |

---

## 游戏时间系统 — GameTimeClock

全项目唯一的时间推进入口，基于三层时钟模型：

```
墙上时间 (System.currentTimeMillis())  ← 仅 GameTimeClock 调用
        ↓
  游戏时间 (墙上时间 × speed)          ← 暂停/1x/2x 速度控制
        ↓
  旬推进 (固定步长 2s/tick @ 1x)      ← 累积器消费游戏时间产出旬数
```

| 速度 | 每旬间隔 | 每月间隔 |
|------|---------|---------|
| 暂停 | ∞ | ∞ |
| 1x | 2.0s | 6.0s |
| 2x | 1.0s | 3.0s |

**下旬动态延长**：当月度结算未完成时，GameTimeClock 消费已累积的游戏时间但不推进旬数（时间暂停）。结算完成后立即推进至下月上旬。

**文件**：`core/engine/system/GameTimeClock.kt`  
**测试**：`GameTimeClockTest.kt` (15 个用例，覆盖率 100%)

## 游戏引擎 — GameEngineCore

### Tick 循环

| 参数 | 值 | 位置 |
|------|-----|------|
| TICK_INTERVAL_MS | 100ms | `GameEngineCore.kt` |
| MIN_TICK_DELAY_MS | 16ms | `GameEngineCore.kt` |
| ADAPTIVE_MAX_INTERVAL_MS | 1000ms | `GameEngineCore.kt` |
| batchIntervalMs | 动态5-15s | `GameEngineCore.kt` (切Tab加速5s，稳定态10s) |
| MS_PER_PHASE_1X | 2000ms | `GameTimeClock.kt` |

### 焦点分频机制

> ⚠️ **已废弃（2026-07-27 惰性结算重构移除）** — `FocusDomain`/`InterfaceDomainMap` 已删除约 3500 行，
> UI 不再驱动系统 tick。以下为移除前架构的留存记录，仅作历史参考；
> 现行调度见 [docs/architecture.md#惰性结算引擎](docs/architecture.md)（四层结算：时间推进/每旬检查/月变/年变）。

每个 `GameSystem` 声明 `focusDomain`(`FocusDomain` 枚举)：
- **ALWAYS** — 每 tick 必执行（TimeSystem）
- **DISCIPLES** — 弟子相关（修炼、突破、HP/MP 恢复）
- **BUILDINGS** — 建筑/生产（生产队列、经济、药园、炼丹、锻器）
- **WAREHOUSE** — 仓库/物品
- **WORLD_MAP** — 世界地图
- **DIPLOMACY** — 外交
- **EXPLORATION** — 探索/巡逻/战斗
- **BACKGROUND** — 后台系统（邮件、生育、道侣、AI 宗门）

**两档制**：焦点域随游戏时钟推进执行（1x=每2000ms/旬，phasesToSettle=1），非焦点域动态5-15s一次（phasesToSettle=N）。Tab切换时加速到5s并触发 `catchUpDomain()` 立即追赶。

**实现**：`SystemManager.onPhaseTickWithDomainFilter(state, activeDomains, shouldExecute, markExecuted)`

**Tick 频率自适应**：
- 正常：100ms
- 空闲(10s 无操作)：2000ms
- 热节流：150ms(LIGHT)/200ms(MODERATE)/500ms(SEVERE)
- 自适应：单 tick 超预算时降频系数自动增大

**后台行为**：`pauseForBackground()` 调用 `stopGameLoop()` 完全停止循环（不再空转）。`resumeFromBackground()` 重新启动。
| 自适应策略 | 连续 3 次超时 → ×1.5；正常后 ×0.8 恢复 | `GameEngineCore.kt` |

### 关键路径

```
startGameLoop() → gameClock.start()
  → tick() → tickInternal()
    → gameClock.tick(isSettlementPending) → TickResult(phasesToAdvance)
    → for each phase:
        → LATE + pending → gameClock.forceConsumeOnePhase() + break (时间暂停)
        → EARLY/MID → advancePhase() / advanceToNextMonth()
    → settlement coordinator (scheduleMonthly/scheduleYearly + executeStep)
    → patrol battle results
```

### 热管理与看门狗

**ThermalMonitor**：通过 ADPF Thermal API 监控设备热状态，过热时自动降负载或紧急保存：

```kotlin
@Singleton
class ThermalMonitor @Inject constructor(@ApplicationContext context: Context) {
    fun shouldReduceWorkload(): Boolean  // THERMAL_STATUS_MODERATE+ → 跳过非关键系统
    fun shouldEmergencySave(): Boolean   // THERMAL_STATUS_SEVERE+ → 紧急保存并暂停
}
```

在 `tickInternal()` 中优先检查热状态——过热时跳过 tick 或被限流执行。`@ApplicationContext` 限定符由 Hilt 自动提供。

**看门狗增强**：`activeSaveJob` / `activeLoadJob` 追踪当前运行的 save/load 协程。超时后 `forceResetStuckStates()` 主动 cancel 协程并重置状态位。`SaveLoadViewModel` 的所有 save/load 协程通过 `.also { registerActiveSaveJob(it) }` 注册，finally 块中 `clearActiveSaveJob()` 清除。

---

## 血炼池 — Blood Refining Pool

### 架构

```
点击血炼池 → BloodRefiningPoolDialog (半屏)
  ├── 材料槽位（复用 UnifiedDiscipleSlot 同款容器 52×88dp）
  │     ├── 空态: "材料" 灰色文字
  │     └── 已选: 精灵图 + 名称 + 库存/需求
  ├── 弟子槽位（DiscipleSlotWithActions）
  │     ├── 空态: "+" → 打开 DiscipleSelectorDialog
  │     └── 已选: 弟子肖像 + "卸任"/"更换"
  ├── 红色小字 "消耗 100 万灵石"（11sp）
  ├── "XX月" 时间显示
  └── 洗炼按钮 → BloodRefiningViewModel.startRefine()
        ├── 验证灵石/材料/弟子
        ├── 扣除灵石 + 材料
        ├── 随机选择属性（50/50）
        └── 记录 activeBloodRefinements[buildingId] = BloodRefinementProgress

每月结算 → SettlementCoordinator.processBloodRefinementProgress()
  ├── 检查到期 → 计算加成 → applyStatBonus()
  ├── 记录 bloodRefinements[discipleId] += materialId
  └── 清除 activeBloodRefinements 条目
```

### 数据模型

| 字段 | 类型 | 说明 |
|------|------|------|
| `GameData.bloodRefinements` | `Map<String, List<String>>` | discipleId → 已完成的材料ID列表 |
| `GameData.activeBloodRefinements` | `Map<String, BloodRefinementProgress>` | buildingId → 进行中的洗炼 |
| `BloodRefinementProgress` | data class | discipleId, materialId, startYear/Month, durationMonths, selectedStat, bonusPercent |

### 血种→属性映射

| 血种 | 属性A | 属性B |
|------|-------|-------|
| tigerBlood (虎) | basePhysicalAttack | baseMagicAttack |
| snakeBlood (蛇) | baseSpeed | baseHp |
| turtleBlood (龟) | basePhysicalDefense | baseMagicDefense |

### DB Migration

- v28→v29: `ALTER TABLE game_data ADD COLUMN bloodRefinements TEXT NOT NULL DEFAULT '{}'`
- v28→v29: `ALTER TABLE game_data ADD COLUMN activeBloodRefinements TEXT NOT NULL DEFAULT '{}'`

## 结算管线 — SettlementCoordinator

### 双轨制（实时轨 + 批量轨）

游戏引擎在每次旬推进时按双轨制调度系统：

```
tickInternal()
  → 暂停/加载/保存检查
  → gameClock.tick() → TickResult(phasesToAdvance)
  → for each phase:
      → 并行 pre-compute（只读快照，ParallelDispatcher N路并行）
      → stateStore.update {
            apply(parallelResults)  // 写入并行计算结果
            systemManager.onPhaseTickWithDomainFilter(activeDomains)
              → 实时轨: 焦点域系统 + 进度≥80%槽位, phasesToSettle=1
              → 批量轨: 非焦点域系统 + 进度<80%槽位, phasesToSettle=N
        }
  → 月变事件 / 年变事件
  → 结算待处理? → forceCompleteSettlement()
  → accumulateBatch(batchIntervalMs)  // 指纹检测（动态5-15s）
  → 年变? → scheduleYearly(shadow) + executeStep()
```

**双轨制**：

| 轨道 | 频率 | 判定条件 |
|------|------|---------|
| **实时轨** | 每旬推进时执行（1x≈2000ms/旬） | 焦点域系统 + 进度≥80%槽位，phasesToSettle=1 |
| **批量轨** | 动态5-15s（R12节律） | 非焦点域系统 + 进度<80%槽位，phasesToSettle=N（追赶跳过旬数） |

**实时轨准入条件（`classifySlotsProgress`，9 类系统）**：

| 系统 | 槽位标识 | ≥80% 判定 |
|------|---------|----------|
| 弟子修炼 | `cultivation:<id>` | cultivation / maxCultivation ≥ 0.8 |
| 装备温养 | `nurture:<eqId>` | nurtureLevel ≥ 5 |
| 功法熟练度 | `proficiency:<dId>:<manualId>` | proficiency / maxProficiency ≥ 0.8 |
| 血炼 | `bloodRefinement:<buildingId>` | 已过月数 / durationMonths ≥ 0.8 |
| 灵田种植 | `spiritField:<instanceId>` | 已过月数 / growTime ≥ 0.8 |
| 任务 | `mission:<id>` | 已过月数 / duration ≥ 0.8 |
| 炼丹/炼器 | `production:<slotId>` | getProgressPercent ≥ 80 |
| 思过 | `reflection:<id>` | 已过年数 / totalDuration ≥ 0.8 |
| 灵矿采矿 | （不入实时轨） | 持续收入型，无"完成"概念 |

> ⚠️ **已废弃** — 双指纹检测（`CultivationRateFingerprint`/`ProductionRateFingerprint`/`SettlementCoordinator`）已随惰性结算重构移除（2026-07-27）。
> 现行机制：生产系统使用 `checkpointAllProduction()` 在政策/长老变化时重算所有活跃槽位的 duration 与 completionMonth（见 CLAUDE.md 6.4）。

### 月度结算阶段（`scheduleMonthly`/`scheduleYearly`）

| 阶段 | 职责 |
|------|------|
| `Phase_BuildCache` | 构建 SettlementCache（脏标记、修炼速率）。指纹命中时跳过 |
| `Phase_FocusedDisciple` | 处理关注弟子（立即结算） |
| `Phase_CleanDiscipleBatch` | 并行处理无变化弟子的被动增长（100 弟子/片，`Dispatchers.Default`） |
| `Phase_DirtyDiscipleBatch` | 并行计算 + 串行合并（突破消耗丹药需串行，每帧 100 弟子） |
| `Phase_Production` | 生产系统月结算（炼丹/锻造并行，其余串行） |
| `Phase_WorldEvents` | 世界事件（探索、外交、生育等） |

### 批量轨微结算（`accumulateBatch` 路径）

| 方法 | 职责 |
|------|------|
| `cultivationMicroSettle` | 用旧缓存速率结算 N 旬修炼值 + HP/MP 恢复 + 持续效果衰减 + 突破检查，直接操作 DiscipleTables |
| `productionMicroSettle` | 逐月推进 `productionSubsystem.onMonthTick` + 经济/血炼/探索/邮件/生育/道侣 |

### 异常恢复

- `executeStep()` 包裹 try-catch，异常时调用 `resetOnError()` 清空 `shadowState`/`currentCache`/`scheduler`
- `shadowState` / `currentCache` 标记 `@Volatile` 防止 UI 线程 `cancelPendingWork()` 并发问题
- 结算异常 → 状态重置 → 下个 tick 正常继续 → 下个月重新结算（不丢数据，只推迟）

---

### 待执行：统一批量结算模式（ADR）

> 详见 [docs/adr/unified-batch-settlement.md](../../docs/adr/unified-batch-settlement.md)

**目标**：移除活跃/空闲双模式，统一为"实时轨（随游戏时钟推进，phasesToSettle=1）+ 批量轨（动态5-15s R12节律，phasesToSettle=N）"的单一模式。

**核心变更**：

```
统一后的 tickInternal()
  → 暂停/加载/保存检查（不变）
  → gameClock.tick(isSettlementPending = false)  // 始终 false
  → for each phase:
      → systemManager.onPhaseTickWithDomainFilter(activeDomains)
      → HP/MP 恢复（焦点域）
  → 月变/年变事件（不变，直接触发不通过结算）
  → accumulateBatch(phasesToAdd, monthChanged, yearChanged, ...)  // 始终调用
      → FullSettled? → 重建批量窗口
  → 巡逻结果（不变）
```

**删除项**：

| 删除 | 所在文件 |
|------|---------|
| `BatchMode` 枚举 | `SettlementCoordinator.kt` |
| `resolveThermalBatchSize()` | `SettlementCoordinator.kt` |
| `fullIdleSettle()` | `SettlementCoordinator.kt` |
| `doIdleFullSettle()` | `GameEngineCore.kt` |
| `isInIdleState` | `GameEngineCore.kt` |
| `lastUserInteractionTime` | `GameEngineCore.kt` |
| `pendingReturnFromIdleSettle` | `GameEngineCore.kt` |
| `enterIdleMode()` | `GameEngineCore.kt` |
| `cleanupIdleState()` | `GameEngineCore.kt` |
| `forceCompleteSettlement()` | `GameEngineCore.kt` |
| `IDLE_DETECTION_MS` | `GameEngineCore.kt` |

**保留项**：

| 保留 | 原因 |
|------|------|
| `scheduleMonthly` / `scheduleYearly` | 公开 API 保留，SettlementScheduler 及阶段类不变 |
| `onUserInteraction` 回调链路 | UI 层用它重置批量时钟 |

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

### 增量绘制与装饰清除

`bakedMapBmp` 从 `remember(fullMapBmp, effectivePlacedBuildings)` 全量重建改为 `remember(fullMapBmp)` 创建后 `LaunchedEffect(effectivePlacedBuildings)` 增量更新：

1. **装饰清除**：新建筑放置时，先用 `groundBmp`（纯地形）覆盖建筑区域，擦除装饰物
2. **增量绘制**：仅绘制新增建筑；移除的建筑区域从 `fullMapBmp` 恢复
3. **Bitmap 生命周期**：`DisposableEffect` 主动 `recycle()`，不依赖 GC

`previousBuildings` 追踪上次建筑列表用于 diff；`clearedDecorationCells` 避免重复擦除同一格。

### 世界地图 (MapCanvas)

| 优化项 | 实现 |
|--------|------|
| `paths` Path 缓存 | `remember(paths)` — 仅在宗门关系变化时重建 |
| `caveExplorationPaths` Path 缓存 | `remember(caveExplorationPaths)` — 探索路径变化时重建 |
| Color/Stroke 提取 | Canvas lambda 外提取，避免每帧重复创建对象 |

---

## 性能基础设施

### GCOptimizer

| GC Type | 触发条件 | 动作 |
|---------|---------|------|
| SOFT | 75% 内存 | 清除非必要缓存 |
| HARD | 85% 内存 | 缩减对象池+清空缓存 |
| CRITICAL | 92% 内存 | 日志提示，委托 ART 自主管理 |
| MANUAL | 手动触发 | 日志提示，委托 ART 自主管理 |

> ART 分代并发 GC（Concurrent Copying）自主管理，已移除显式 `System.gc()` 调用。

### DynamicMemoryManager

设备等级（用于 Canvas 烘焙决策）：

| Tier | RAM | heap | Canvas 策略 |
|------|-----|------|------------|
| LOW | < 4GB | < 256MB | 跳过烘焙，动态绘制 |
| MEDIUM | 4-6GB | 256-384MB | RGB_565 (18MB/层) |
| HIGH | 6-12GB | 384-512MB | ARGB_8888 (36MB/层) |
| ULTRA | 12GB+ | > 512MB | ARGB_8888 全开 |

### FrameMetricsMonitor

```kotlin
@Singleton
class FrameMetricsMonitor {
    val jankEvents: SharedFlow<FrameMetricsEvent>  // jank 事件流

### ThermalReader（v4.0.41+）

三通道温度读取策略，替代旧 SELinux 封锁的 sysfs 直读：

| 通道 | API | 最低版本 | 类型 |
|------|-----|---------|------|
| Channel 1 | `PowerManager.getThermalHeadroom(10)` | API 30 | 主动预测 |
| Channel 2 | `PowerManager.currentThermalStatus` | API 29 | 被动回调 |
| Channel 3 | sysfs `/sys/class/thermal/` + BatteryManager | API 24 | 降级回退 |

接口 `ThermalReader` 定义于 `core/engine/.../thermal/`，`AndroidThermalReader` 为 Android 实现。
iOS 移植通过 `ProcessInfo.thermalState` 实现 `ThermalReader` 接口。

### ThermalController 多级降级（v4.0.41+）

| 等级 | 并行度 | 渲染质量 | 目标帧率 | 粒子 | 后处理 |
|------|--------|---------|---------|------|--------|
| GREEN | 全(4线程) | 1.0(完整) | 60fps | 开启 | 开启 |
| YELLOW | 半(2线程) | 0.8 | 45fps | 开启 | 开启 |
| ORANGE | 单(1线程) | 0.6 | 30fps | 关闭 | 关闭 |
| RED | 单(1线程) | 0.4 | 30fps锁定 | 关闭 | 关闭 |

### Scene-Aware Frame Rate（v4.0.41+）

`GameEngineCore` 根据当前场景动态调整帧率预算：

| 场景 | 帧率 | 帧预算 | 触发条件 |
|------|------|--------|---------|
| IDLE | 10fps | 100ms | 无操作 ≥30s |
| MAP_SCROLL | 30fps | 33ms | 地图拖拽/惯性滑行 |
| GAMEPLAY | 60fps | 16ms | 正常游戏操作 |
| BATTLE | 60fps | 16ms | 战斗场景 |
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

### ThermalMonitor

```kotlin
@Singleton
class ThermalMonitor @Inject constructor(@ApplicationContext context: Context) {
    val currentThermalStatus: Int           // 0=NONE ~ 6=SHUTDOWN
    fun shouldReduceWorkload(): Boolean     // MODERATE+ 降负载
    fun shouldEmergencySave(): Boolean      // SEVERE+ 紧急保存
}
```

基于 Android ADPF Thermal API（`PowerManager.getCurrentThermalStatus()`）。在 `GameEngineCore.tickInternal()` 入口处优先检查，过热时跳过整个 tick 或限流执行。

### BuildingSpatialIndex

```kotlin
class BuildingSpatialIndex {
    fun rebuild(buildings: List<GridBuildingData>)  // 按网格单元建索引
    fun findBuildingAt(gridX: Int, gridY: Int): GridBuildingData?  // O(1) 查找
}
```

将建筑按占用的所有网格单元建 Hash 索引（Long key = (gridX << 32) | gridY），触控检测从 O(n) 线性查找改为 O(1) Hash 查找。在 `SectGroundCanvas` 的 `pointerInput` 手势处理中使用。

---

## 世界地图重构

### 概述

世界地图渲染管线采用增量更新架构，核心思路：**静态内容预烘焙为 Bitmap + 动态内容逐帧叠加**。

### 核心文件

| 文件 | 职责 |
|------|------|
| `ui/game/map/MapBackground.kt` | 地图背景预烘焙（Canvas→Bitmap），地形+宗门标记+等级图标缓存 |
| `ui/game/map/MapTileCache.kt` | 地图瓦片缓存，分块加载，视口裁剪 |
| `ui/game/map/WorldMapConnections.kt` | 宗门连接线 Path 缓存与绘制 |
| `ui/game/WorldMapViewModel.kt` | 相机状态管理（缩放/平移/居中）、焦点坐标追踪 |
| `ui/game/WorldMapInteractionViewModel.kt` | 触控手势处理（点击/拖拽/长按） |
| `ui/game/WorldMapGarrisonViewModel.kt` | 世界地图驻军管理 |

### 性能优化

- **预烘焙 Bitmap**：地形、宗门标记、等级图标合并为单张 `Bitmap`（`remember` 缓存），避免每帧重复绘制
- **视口裁剪**：仅绘制屏幕可见范围内的连接线和动态标记
- **连接线缓存**：宗门外交关系变化时才重建 Path 对象
- **瓦片缓存**：大地图分块存储，平移时仅加载新进入视口的瓦片
- **固定宗门坐标**：`FixedSectPositions.kt` 77 行硬编码 60+ 宗门坐标，消除运行时随机生成带来的地图抖动

### 数据流

```
GameData.worldMapSects → WorldMapViewModel.mapItems (derivedStateOf)
    → MapBackground.bakeMapBitmap() (once, recompose on mapItem changes)
        → Canvas.drawBitmap(bakedMap, ...)  (per frame, zero allocation)
    → MapOverlay: 连接线 + 动态标记 (per frame, viewport-culled)
```

### 建筑拆除

山门地图移动建筑模式下新增「拆除」功能，允许玩家拆除已放置建筑并回收部分资源：

```
移动模式 (movingBuilding != null)
  ├── 放置确认按钮（绿色✓）
  └── 拆除按钮（红色背景，"拆除"文字）
        └── click → 确认对话框
              ├── 标题："确认拆除"
              ├── 内容："确定要拆除「XX」吗？将返还 50% 建造灵石。"
              ├── 确认 → viewModel.demolishBuilding(instanceId)
              │     → GameEngine.removeBuilding(instanceId, refund)
              │       → BuildingFacade.removeBuilding(instanceId, refund)
              │         → BuildingFacadeImpl: 从 placedBuildings 移除
              │         → 清空该建筑关联槽位
              │         → 弟子下岗 (status → IDLE)
              │         → 灵石返还 (建造费用 × 50%)
              └── 取消 → 关闭对话框
```

**涉及文件**：
| 文件 | 职责 |
|------|------|
| `core/engine/domain/building/BuildingFacade.kt` | `removeBuilding(instanceId, refund)` 接口 |
| `core/engine/domain/building/BuildingFacadeImpl.kt` | 实现：建筑移除 + 槽位清理 + 弟子下岗 + 退款 |
| `core/engine/GameEngineBuildingOps.kt` | `GameEngine.removeBuilding()` 委托 |
| `feature/game/.../MainGameScreen.kt` | UI：拆除按钮 + 确认对话框（`PlacementConfirmButtons` composable） |
| `feature/game/.../GameViewModel.kt` | `demolishBuilding(instanceId)` → 计算退款金额 → 调用 Engine |

---

## GPU 分级渲染系统

### 概述

GPU 分级系统在应用启动时自动检测设备 GPU 能力，将设备分为 **LOW / MEDIUM / HIGH / ULTRA** 四级，为各级别预设最优渲染参数。

### 核心文件

| 文件 | 职责 |
|------|------|
| `core/perf/GpuTierDetector.kt` | GPU 检测（GameManager API → GL_RENDERER 字符串匹配）、分级分类器 |
| `core/perf/GpuTierDetector.kt` — `GpuRenderConfig` | 分层渲染参数 Data Class（7 个字段） |
| `core/perf/GpuTierDetector.kt` — `thermalRenderScale()` | GPU 分级 + 热状态双因子渲染缩放函数 |

### 检测流程

```
GameManager.getGamePerformanceClass()  ← Android 13+ 优先
    ↓ 失败/不可用
EGL Context → glGetString(GL_RENDERER)  ← 离屏 GL 查询
    ↓ 失败
默认 MEDIUM  ← 安全兜底
```

### GPU Tier 分类规则

| Tier | 典型 GPU | 匹配规则 |
|------|---------|---------|
| **ULTRA** | Adreno 830/750/740、Immortalis-G925/G720、Xclipse 950/940 | 3DMark WLE ~3800+ |
| **HIGH** | Adreno 735/730、Mali-G715 Immortalis、Maleoon 920 Pro、Xclipse 920 | 3DMark WLE ~2800-3800 |
| **MEDIUM** | Adreno 7xx/660/650、Mali-G78/G77/G76、Maleoon 910、Tensor G2-G4 | 3DMark WLE ~1200-2800 |
| **LOW** | Adreno 6xx/5xx/4xx、Mali-G57/G52/G51/G68、PowerVR、旧 Mali-T | 3DMark WLE <1200 |

### GpuRenderConfig 参数表

| 参数 | LOW | MEDIUM | HIGH | ULTRA |
|------|-----|--------|------|-------|
| `mapResolution` | 24 | 32 | 48 | 48 |
| `bakeBuildings` | false | true | true | true |
| `useArgb8888` | false | false | true | true |
| `baseRenderScale` | 0.6 | 0.8 | 1.0 | 1.0 |
| `showTrees` | false | true | true | true |
| `gridLineMode` | border | full | full | full |
| `auraEffectMode` | off | simple | full | full |
| `particleEffectMode` | off | simple | full | full |
| `textureLodOffset` | +1 (更模糊) | 0 | 0 | -1 (更清晰) |

### Canvas Overdraw 优化

- **网格线**: LOW 模式仅绘制放置区域边界 4 条线，替代 ~100 条完整网格线（减少 96%）
- **放置/移动预览**: 单一 `drawRect` 替代逐格嵌套循环 `for(gridX) for(gridY) drawRect()`（从 ~50 次→1 次）
- **光环效果**: LOW=关闭，MEDIUM=仅圆形轮廓，HIGH/ULTRA=完整逐格覆盖
- **装饰密度**: LOW=无草无树，MEDIUM=半密度(草3%/树5%)，HIGH/ULTRA=全密度(草6%/树10%)
- **贴图 LOD**: LOW 解码采样率翻倍（inSampleSize×2），解码内存减少 75%

### 温控联动

```kotlin
fun thermalRenderScale(gpuTier: GpuTier, thermalState: ThermalState): Float
```

双因子决策：GPU 等级决定基础缩放，热状态叠加降档。例如 LOW 设备在 NORMAL 状态已降至 0.7×，LIGHT 降至 0.5×。

---

## 活动系统

### 概述

活动系统已整体移除（2026-08-07）：活动界面（`ActivityDialog`/`ActivityViewModel`/`BuiltinActivityConfig`/`ActivityDef`）与每日签到（`DailySignInService`/`DailySignInDialog`/`SignInDelegate`）全部删除，"活动"入口按钮一并移除。

### 常驻玩法入口（保留）

历战入口（`LizhanDialog`）保留，承载天道试炼与远古秘境两个常驻玩法。

### 数据库兼容

- `game_data` 表 `sign_in_state_json` 列**保留兼容旧档**（TEXT, ProtoBuf 序列化 `SignInState`），禁止新代码读写
- `core/model/SignInState.kt`（原 `DailySignIn.kt`）、`CollectionConverters` 的 `fromSignInState`/`toSignInState`、迁移 SQL 均保留原样，零 Migration

---

---

---

---

---


## 构建与 Profile

### 测试架构

| 测试类 | 目的 | 测试数 |
|--------|------|--------|
| `StateRevertRegressionTest` | 状态回退 bug 不重现：玩家字段保留、身份不丢失、灵草不丢失 | 3 |
| `DiscipleMergeCoverageTest` | **编译期安全网**：Disciple 新增字段强制归类到结算修改/玩家操作/不变 | 4 |
| `GameDataSettlementCoverageTest` | **编译期安全网**：GameData 每个字段必须有 `@SettlementStrategy` 注解 | 1 |

```bash
# 测试必须串行（--max-workers=1），并行会因共享静态状态跨类污染出错（2026-08-04 起强制）
cd android && ./gradlew.bat test --max-workers=1              # 全部测试 (~930)
cd android && ./gradlew.bat testDebugUnitTest --max-workers=1 \
    --tests "com.xianxia.sect.core.state.*"                    # 状态层测试
```

### 版本

| 字段 | 值 |
|------|-----|
| versionCode | 3203 |
| versionName | 3.2.03 |
| compileSdk / targetSdk | 35 |
| minSdk | 24 |
| Kotlin | 2.0.21 |
| Compose BOM | 2025.02.00 |
| Gradle | 8.14.5 |

### Compose Compiler

- **插件**：`org.jetbrains.kotlin.plugin.compose`（Kotlin 2.0 原生）
- **Compose 编译器插件**：`org.jetbrains.kotlin.plugin.compose`（Kotlin 2.0 原生），无需 `composeOptions` 配置
- **默认启用**：Strong Skipping Mode
- **稳定性配置**：`stability_config.conf` — 26 个类的显式稳定性声明
- **指标**：`composeCompiler { reportsDestination / metricsDestination }` → `build/compose_metrics/`

### Baseline Profile

- **模块**：`:baselineprofile`（`com.android.test` plugin）
- **生成器**：`BaselineProfileGenerator.collect(packageName="com.xianxia.sect", includeInStartupProfile=true)`
- **生成方式**：本地真机运行 `:baselineprofile:generateReleaseBaselineProfile`，生成文件提交 `app/src/main/baseline-prof.txt`

### Lifecycle 感知收集

- **依赖**：`lifecycle-runtime-compose:2.8.7`
- **模式**：`collectAsStateWithLifecycle()` 全量替代 `collectAsState()`
- **覆盖**：14 个 UI 文件共 158 处订阅全部迁移，零 `collectAsState()` 残余
- **新增注入**：GameViewModel 新增 7 个 Facade 直接注入 + 4 个独立 StateFlow（elderSlots/sectPolicies/manualProficiencies/residenceSlots）

---

## 事件驱动惰性求值

### 核心思想

每种耗时操作存储 `completionMonth` + `completionPhase`，仅在 `currentMonth >= completionMonth && currentPhase >= completionPhase` 时才结算。**焦点域强制立即结算**，保证玩家体验。

### 关键文件

- `LazyEvaluationDispatcher` — 统一调度器：`shouldSettle()` / `shouldSettleWithThermal()` / `isInFocusDomain()`
- `GameSystem.settlementPhase` — 每个系统声明自己属于哪个结算旬（1=上旬/2=中旬/3=下旬/0=每旬）
- `SystemManager.onPhaseTickWithDomainFilter()` — 分旬过滤 + 热状态联动

### 数据模型新增字段

- `Disciple`: `cultivationCompletionMonth/Phase`, `manualCompletionMonth/Phase`, `equipmentNurturingCompletionMonth/Phase`
- `ProductionSlot`: `completionMonth`, `completionPhase`
- `SpiritFieldPlant`: `completionMonth`, `completionPhase`
- DB Migration v32→v33：ALTER TABLE 新增 8 列

### 焦点域实时化

> ⚠️ **已废弃** — 同"焦点分频机制"标注，FocusDomain 已随惰性结算重构移除，本段为历史记录。

- **DISCIPLES Tab**: 随游戏时钟推进修炼值（`rate × phasesToSettle`）、HP/MP 恢复、buff 时效。`updateFocusedDisciple` 对焦点弟子额外推进功法熟练度 + 装备孕养
- **BUILDINGS Tab**: 随旬推进检测生产槽位完成 + 触发自动锻造/自动炼丹
- **三重兜底**: 实时 tick + 月度结算扣除（`highFreqData.cultivationUpdates`）+ 战斗前正常恢复（`CombatService`，满状态跳过）

### 修炼月度结算

- 所有存活弟子每月强制结算修炼值，不再按距突破远近跳过（原 `farFromCompletionIds` 逻辑已移除）
- 修炼速度变化 → 脏标记 → 强制下一次结算 → 重算 `completionMonth`
- 突破被动触发：`cultivation >= maxCultivation` 且满血满蓝时判定，clean/dirty 批次均会检查
- Cache 增量重建：`CultivationRateFingerprint` 检测住所/长老/传功/政策/弟子列表变化，未变化时复用 `SettlementCache`

### 其他性能改进

- `GameStateStore` 版本计数器 + `sample(50)` 批处理 StateFlow 发射
- `ThermalMonitor` ADPF Performance Hint API 集成（API 31+）
- `MainGameScreen` GPU 分级 + 热状态联动渲染缩放（LOW 设备 NORMAL→0.7, MEDIUM→0.9, HIGH/ULTRA→1.0；逐级降至 EMERGENCY→0.25~0.5）
- `CultivationService` 微批次 yield（每 50 人 yield）、PhaseTickAccumulator 合并副作用
- `GameEngineCore` 专用游戏线程（`GAME_DISPATCHER`）、空闲检测保留 tick 改降域
- 月度结算精简：薪水年度化、盗窃提前退出、执法被动触发、洞府移除、侦察/任务惰性化、外交限制 2 次/月、任务刷新每 3 月
- 自动装备/自动学习脏标记：仅储物袋有物品或装备/功法变更时检测（`ConcurrentHashMap.newKeySet`）
- **战斗前 HP/MP 恢复已删除（2026-08-11）**：每旬恢复（20%/旬）已保证血量最新状态，战前补血 = 每次进攻白送血量且可反复触发；`recoverHpMpForBattleParticipants`/`BattleSettlementService` 整链移除，`forceSettleDisciplesBeforeBattle` 仅保留突破检测职责


