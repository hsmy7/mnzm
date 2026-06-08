# 修仙宗门 — 代码架构 Wiki

> 最后更新：2026-06-08 (v4.0.00 架构重构)

## 目录

1. [架构总览](#架构总览)
2. [引擎层 — 领域 Facade 架构](#引擎层--领域-facade-架构)
3. [GameEngine 拆分](#gameengine-拆分v4000)
4. [CultivationService 拆分](#cultivationservice-拆分v4000)
5. [状态管理 — GameStateStore](#状态管理--gamestatestore)
6. [数据库 — 从零开始](#数据库--从零开始v4000)
7. [UI 组件拆分](#ui-组件拆分v4000)
8. [代码质量基础设施](#代码质量基础设施v4000)
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
│   - EngineCore: game loop (200ms tick)          │
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

GameEngine（精简协调器）→ 9 个按域拆分的扩展文件 + 7 个领域 Facade 接口：

```
GameEngine.kt (精简协调器)
  ├── 9 个扩展文件（按域拆分，v4.0.00）：
  │     GameEngineBattleOps.kt / BuildingOps.kt / Coordination.kt
  │     DiplomacyOps.kt / DiscipleOps.kt / Extensions.kt
  │     InventoryOps.kt / ProductionOps.kt / SaveOps.kt
  └── 7 个领域 Facade（v3.1.99）：
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

### GameService / GameSystem 职责标注 (v3.2.03)

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

### CultivationService 拆分 (v4.0.00)

3804 行的 CultivationService 按职责拆分为 1 个 Facade + 10 个子模块：

```
core/engine/service/
├── CultivationService.kt          (~300行) Facade 协调器
├── CultivationCore.kt             (~400行) 修炼速率/相位推进
├── CultivationEventProcessor.kt   (~350行) 修炼事件/天劫/奇遇
├── CultivationSettlement.kt       (~350行) 月/年度修炼结算
├── CultivationSharedState.kt      (~200行) 共享状态
├── DiscipleBreakthroughHandler.kt (~500行) 突破全流程
├── DiscipleLifecycleProcessor.kt  (~250行) 弟子寿命/年龄/死亡
├── CaveExplorationProcessor.kt    (~300行) 洞府探索
├── MerchantAndRecruitService.kt   (~300行) 云游商人/弟子招募
├── PhaseTickAccumulator.kt        (~150行) 相位推进累加器
└── ProductionSubsystem.kt         (~250行) 生产子系统
```

### GameEngine 拆分 (v4.0.00)

3000 行的 GameEngine 拆分为 1 个精简协调器 + 9 个域扩展文件：

```
core/engine/
├── GameEngine.kt                  (~400行) 精简协调器
├── GameEngineBattleOps.kt         (~300行) 战斗域操作
├── GameEngineBuildingOps.kt       (~250行) 建筑域操作
├── GameEngineCoordination.kt      (~200行) 跨域协调
├── GameEngineDiplomacyOps.kt      (~200行) 外交域操作
├── GameEngineDiscipleOps.kt       (~350行) 弟子域操作
├── GameEngineExtensions.kt        (~200行) 扩展/convenience方法
├── GameEngineInventoryOps.kt      (~250行) 物品域操作
├── GameEngineProductionOps.kt     (~200行) 生产域操作
└── GameEngineSaveOps.kt           (~200行) 存档域操作
```

### 数据库 — 从零开始 (v4.0.00)

**DB 版本重置为 1**，全部历史 Migration（9个，从 MIGRATION_1_26 到 MIGRATION_33_34）已移除。

```
首次启动（4.0）：
  create() 入口 → 检测旧 db 文件 → 删除 db/wal/shm
  → Room 发现无文件 → onCreate() → 全新空库（version=1）
  → schema/1.json 为唯一 schema 文件
```

旧存储清空覆盖：
| 存储类型 | 清空方式 |
|---------|---------|
| Room DB (v34) | `context.getDatabasePath().delete()` + wal/shm |
| .sav 文件 | SavMigrator 遍历删除 + 跳过所有迁移 |
| MMKV | `MMKV.defaultMMKV().clearAll()` |
| SharedPreferences | sav_migration / crash_handler / app_session 逐个 clear |

### UI 组件拆分 (v4.0.00)

```
DiscipleDetailScreen（2647行 → 542行）：
  ui/game/components/detail/
  ├── DetailHeaderSection.kt       (~250行)
  ├── DetailCultivationSection.kt  (~300行)
  ├── DetailEquipmentSection.kt    (~350行)
  ├── DetailManualSection.kt       (~300行)
  ├── DetailPillSection.kt         (~250行)
  ├── DetailCombatSection.kt       (~250行)
  └── DetailActionButtons.kt       (~200行)

ItemDetailDialog（1548行 → 603行）：
  ui/game/components/
  ├── ItemDetailDialog.kt          (~600行) @Composable 函数
  ├── ItemDetailEffects.kt         (~600行) 装备/功法/丹药效果
  └── ItemDetailOtherEffects.kt    (~400行) 材料/灵草/商人等效果

SaveDataConverter（2002行 → 拆分为 7 个 Converter）：
  data/serialization/unified/
  ├── SaveDataConverter.kt         (~300行) 协调器
  ├── DiscipleConverter.kt
  ├── EquipmentConverter.kt
  ├── ItemConverter.kt
  ├── ManualConverter.kt
  ├── SlotConverter.kt
  ├── TeamAndBattleConverter.kt
  └── WorldAndSectConverter.kt

WarehouseTab（1568行 → 拆分为 4 个 Section + 3 个 Dialog）：
  ui/game/tabs/
  ├── WarehouseTab.kt              (~300行)
  ├── EquipmentSection.kt
  ├── ManualSection.kt
  ├── MaterialSection.kt
  ├── PillSection.kt
  ├── WarehouseBulkSellDialog.kt
  ├── WarehouseDetailDialog.kt
  └── WarehouseDiscipleSelectDialog.kt

ProtobufConverters（1145行 → 544行）：
  data/local/
  ├── ProtobufConverters.kt        (~550行) 保留原名
  ├── CollectionConverters.kt      类型转换器
  ├── EnumConverters.kt            枚举转换器
  └── JsonConverters.kt            JSON 转换器

ChangelogData（1999行 → 44行）：
  ChangelogData.kt 仅保留加载逻辑，条目数据外置到 assets/changelog_entries.json
```

### 代码质量基础设施 (v4.0.00)

**反模式清零**：
| 指标 | 改造前 | 改造后 |
|------|--------|--------|
| `!!` 强制解包 | 110 处 | 0 |
| `runBlocking` | 17 处 | 0 |
| TODO 遗留 | 14 处 | 0 |
| `@Suppress` 抑制 | 60+ 处 | 15 处（5 文件） |

**静态分析工具链**：
```
android/
├── config/detekt/detekt.yml       Detekt 配置
├── app/detekt-baseline.xml        基线（屏蔽历史问题）
├── app/lint-baseline.xml          Lint 基线
└── build.gradle                   lint.checkReleaseBuilds = true
```

**构建检查**：`./gradlew lintRelease && ./gradlew detekt`

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

| 入口 | 方法 | 并发保护 | 适用场景 |
|------|------|---------|---------|
| **主事务** | `suspend fun update(block)` | `transactionMutex.withLock { }` | tick 驱动更新、玩家操作 |
| **快照加载** | `suspend fun loadFromSnapshot(...)` | `transactionMutex.withLock { }` | 存档加载 |
| **结算合并** | `suspend fun swapFromShadow(shadow)` | 在 `update { }` 内执行 | 月度/年度结算 |
| **重置** | `suspend fun reset()` | `transactionMutex.withLock { }` | 新游戏 / 清档 |

> ⚠️ `updateGameDataDirect()` / `updateXxxDirect()` 方法已废弃（v3.2.00）。这些方法直接写 StateFlow.value，绕过 `transactionMutex`，存在竞态条件。所有外部调用已迁移到 `stateStore.update { }`。保留仅为内部兼容，不建议新代码使用。

### 向后兼容

`unifiedState: StateFlow<UnifiedGameState>` 保留，来自 `_state.asStateFlow()`。`_state` 在每个入口同步更新。消费者可逐步迁移到独立流。

### 指纹缓存

- `discipleAggregates`：`ConcurrentHashMap<String, DiscipleAggregate>` 按弟子 ID 缓存，`sourceRef === disciple` 引用有效性检查
- `sectCombatPower`：`CachedPower(fingerprint, power)` 按战力指纹缓存，仅在 `combine(disciplesFlow, equipmentInstancesFlow, manualInstancesFlow)` 任一变化时重算
- 两个缓存在 `loadFromSnapshot()` / `reset()` / `swapFromShadow()` 时清空

### DomainStateProvider — 领域状态提供者 (v3.2.03)

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

### 状态一致性：统一 Mutex 序列化（v3.2.02 修复）

#### 问题背景

`swapFromShadow()` 直接读写 `_xxxFlow.value` 绕过 `transactionMutex`，与玩家操作（`stateStore.update { }`）形成竞态条件，导致状态回退（弟子身份/状态/灵草种植被覆盖）。

#### 解决方案

```
修复前：                            修复后：
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

**Disciple**：`mergeDiscipleAfterSettlement()` 集中管理 + `DiscipleMergeCoverageTest` 编译期检查。v3.2.03 从**整体覆盖**重构为**子字段级合并**：

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

### Save Slot 写入顺序 (v3.2.03 修复)

`updateSpiritMineSlots` / `updatePatrolSlots` 走 `updateGameDataSync → launchInScope`，是 fire-and-forget。在 ViewModel 中与 `updateDiscipleStatus`（suspend）混用时，slot 更新可能延迟执行，导致状态不一致。

**修复原则**：先 `updateGameData(suspend)` 保存 slot，再 `updateDiscipleStatus(suspend)` 更新弟子状态。

```kotlin
// ❌ 错误 — slot fire-and-forget + 弟子状态 suspend
gameEngine.updateDiscipleStatus(discipleId, MINING)  // await
gameEngine.updateSpiritMineSlots(slots)               // fire-and-forget，可能延迟

// ✅ 正确 — 先槽位后弟子，都 await
gameEngine.updateGameData { it.copy(spiritMineSlots = slots) }  // await
gameEngine.updateDiscipleStatus(discipleId, MINING)              // await
```

**修复范围**：SpiritMineViewModel（autoAssign / remove / swap）、PatrolTowerViewModel（autoAssign / assign / remove / swap），共 7 处。

### 重型数据 BLOB 直存 (v3.2.17)

`game_heavy_data` 表存储 **7 个**重型字段（aiSectDisciples / sectDetails / exploredSects / scoutInfo / manualProficiencies / recruitList / worldMapSects），以 **Protobuf BLOB** 列直存。此前使用 Base64 TEXT 列存储，长时间存档中大字段（如 `aiSectDisciples` 包含数百宗门 × 上百弟子）序列化后的 Base64 编码可导致峰值内存超过 300MB（139MB ByteArray + 186MB Base64 String），触发 `OutOfMemoryError`。

**v3.2.17 解决方案：增量编码 + BLOB 直存**
- **列类型 TEXT → BLOB**：`data_value` 改为 `@ColumnInfo(typeAffinity = ColumnInfo.BLOB) ByteArray`，Room 原生支持，无需 TypeConverter，消除 Base64 33% 膨胀
- **增量编码**：不再一次性 `encodeToByteArray(全集)`，改为逐项迭代（Map 每项独立一行，List 每 N 条一批），每批独立写入 DB **后立即释放** ByteArray
- **内存守卫**：写入前 `Runtime.getRuntime().freeMemory()` 检查，<100MB 时跳过自动存档
- **扩展卸载**：相比 v3.2.16 新增 `recruitList` 和 `worldMapSects` 两个大字段的卸载
- **DB Migration 33→34**：`game_heavy_data` 表重建，`CAST(data_value AS BLOB)` 无损迁移旧 Base64 数据

**Key 格式变更**：旧格式 `aiSectDisciples_chunk_0` → 新格式 `aiSectDisciples/青云宗`（`/` 分隔前缀和实体标识）。`_overflow_N` 后缀仅用于单条目超 900KB 的极端情况。

```
保存流程：
  for each (sectName, disciples) in aiSectDisciples:
      bytes = encodeToByteArray(ListSerializer, disciples)  → ~1-3MB per sect
      → GameHeavyData(slotId, "aiSectDisciples/$sectName", bytes)
      → heavyDao.upsertAll() 立即写入，立即释放 bytes

  GameData 主表行清空所有重型字段为 emptyMap/emptyList

加载流程：
  loadHeavyDataSafe() → 逐 key 容错加载所有 BLOB 行
  → decodeXxxFromRows() 按前缀匹配 → 每行独立 protobuf 解码 → 组装完整对象
  → mergeHeavyData() 合并到 GameData
```

**向下兼容**：`decodeFromBlobInternal()` 两步回退 — 先直接 protobuf 解码（新 BLOB），失败则 `decodeToString` → `Base64.decode` → protobuf 解码（旧 CAST 数据）。旧存档首次加载后下次保存自动转为新格式。

**关键类**：

| 类/方法 | 职责 |
|---------|------|
| `GameHeavyData.chunk(slot, key, value: ByteArray)` | 拆分大 BLOB（>900KB 的极端情况）为溢出分块 |
| `GameHeavyData.reassemble(rows)` | 从原始行列表重组 `Map<String, ByteArray>` |
| `GameHeavyData.chunkKey(prefix, id)` | 构造分块 key：`"prefix/id"` |
| `GameHeavyData.parseChunkKey(key, prefix)` | 从 key 提取 id |
| `ProtobufConverters.encodeXxxIncremental()` | 逐项 protobuf 编码（永不分配完整集合的 ByteArray）|
| `ProtobufConverters.decodeXxxFromRows()` | 按前缀过滤行 → 逐行解码 → 组装 |
| `ProtobufConverters.decodeFromBlobInternal()` | 两步回退解码（protobuf → Base64 → default）|
| `GameHeavyDataDao.deleteByKeyPrefix(slot, prefix)` | 写入前按前缀批量清理旧数据 |
| `GameHeavyDataDao.getByPrefix(slot, prefix)` | 按前缀批量查询（替代逐 key 查询） |
| `StorageEngine.writeAllDataToDatabase()` | 内存守卫 + 增量编码写入 + 轻型 GameData 写入 |
| `StorageEngine.mergeHeavyData()` | 加载后合并重型数据到 GameData |
| `GameEngine.ensureHeavyDataLoaded()` | 启动时安全加载 7 个重型字段 + 2 个新增字段 |

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

## 游戏时间系统 — GameTimeClock (v3.2.25)

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
| IDLE_TICK_INTERVAL_MS | 2000ms | `GameEngineCore.kt` (10秒无操作后降频) |
| IDLE_DETECTION_MS | 10000ms | `GameEngineCore.kt` |
| NON_FOCUS_TICK_INTERVAL | 30000ms | `GameEngineCore.kt` (非焦点域结算间隔) |
| MS_PER_PHASE_1X | 2000ms | `GameTimeClock.kt` |

### 焦点分频机制 (v3.2.06)

每个 `GameSystem` 声明 `focusDomain`(`FocusDomain` 枚举)：
- **ALWAYS** — 每 tick 必执行（TimeSystem）
- **DISCIPLES** — 弟子相关（修炼、突破、HP/MP 恢复）
- **BUILDINGS** — 建筑/生产（生产队列、经济、药园、炼丹、锻器）
- **WAREHOUSE** — 仓库/物品
- **WORLD_MAP** — 世界地图
- **DIPLOMACY** — 外交
- **EXPLORATION** — 探索/巡逻/战斗
- **BACKGROUND** — 后台系统（邮件、生育、道侣、AI 宗门）

**两档制**：活跃域每 tick(100ms)执行，非活跃域最长 30 秒一次。玩家切换 Tab/Dialog/弟子焦点时触发 `catchUpDomain()` 立即追赶。

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

### 热管理与看门狗 (v3.2.03)

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

## 血炼池 — Blood Refining Pool (v3.2.04)

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

### 架构

```
tickInternal()
  → monthChanged?
    → stateStore.createSettlementShadow()  // 浅拷贝：仅结算修改字段（gameData/disciples/equipmentInstances/pills/manualInstances）
    → settlementCoordinator.scheduleMonthly(shadow)  // 调度结算阶段
      → computeFingerprint() → 命中? → 跳过 Cache Build（Dirty Flag 增量重建）
  → executeStep()  // 每 tick 执行结算（前 3 帧 12ms 激进预算，之后 1.5ms 保守预算）
    → 完成? → onSettlementComplete() [suspend]
      → swapFromShadow() [suspend, 在 stateStore.update { } 内]
        → 结算 shadow 仅同步修改字段，跳过未拷贝字段
        → mergeGameData() + mergeDiscipleAfterSettlement() → 写回主状态
```

### 优化项 (v3.2.22)

| 优化 | 技术 | 收益 |
|------|------|------|
| Cache 增量重建 | `CultivationRateFingerprint` + Dirty Flag 模式 | 90%+ 月份跳过 Cache Build（3-15ms→0ms） |
| Shadow 浅拷贝 | `createSettlementShadow()` + `isSettlementShadow` 标记。跳过 `storageBags`/`teams`/`battleLogs`，其余 10 字段全量拷贝（含生产必需的 herbs/materials/seeds） | 拷贝开销减 ~30%（3 字段/14 字段） |
| 弟子并行处理 | `coroutineScope { async(Dispatchers.Default) }` 分片并行 | 批量处理减 40-60% |
| 时间预算动态调整 | 激进 12ms（3 帧）+ 保守 1.5ms | 月结帧数 12-65→1-3 |
| 生产并行化 | 炼丹/锻造并行（herbs/materials 无冲突），矿场/分配串行 | 生产阶段减 20-30% |

### 结算阶段（按月）

| 阶段 | 职责 |
|------|------|
| `Phase_BuildCache` | 构建 SettlementCache（脏标记、修炼速率）。指纹命中时跳过 |
| `Phase_FocusedDisciple` | 处理关注弟子（立即结算） |
| `Phase_CleanDiscipleBatch` | 并行处理无变化弟子的被动增长（100 弟子/片，`Dispatchers.Default`） |
| `Phase_DirtyDiscipleBatch` | 并行计算 + 串行合并（突破消耗丹药需串行，每帧 100 弟子） |
| `Phase_Production` | 生产系统月结算（炼丹/锻造并行，其余串行） |
| `Phase_WorldEvents` | 世界事件（探索、外交、生育等） |

### 异常恢复 (v3.1.98)

- `executeStep()` 包裹 try-catch，异常时调用 `resetOnError()` 清空 `shadowState`/`currentCache`/`scheduler`
- `shadowState` / `currentCache` 标记 `@Volatile` 防止 UI 线程 `cancelPendingWork()` 并发问题
- 结算异常 → 状态重置 → 下个 tick 正常继续 → 下个月重新结算（不丢数据，只推迟）

### forceCompleteSettlement()

当月变/年变时若仍有 pending 结算，循环执行 `executeStep()` 直到完成。调度器内部管理激进/保守预算切换。

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

### 增量绘制与装饰清除 (v3.2.03)

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

### ThermalMonitor (v3.2.03)

```kotlin
@Singleton
class ThermalMonitor @Inject constructor(@ApplicationContext context: Context) {
    val currentThermalStatus: Int           // 0=NONE ~ 6=SHUTDOWN
    fun shouldReduceWorkload(): Boolean     // MODERATE+ 降负载
    fun shouldEmergencySave(): Boolean      // SEVERE+ 紧急保存
}
```

基于 Android ADPF Thermal API（`PowerManager.getCurrentThermalStatus()`）。在 `GameEngineCore.tickInternal()` 入口处优先检查，过热时跳过整个 tick 或限流执行。

### BuildingSpatialIndex (v3.2.03)

```kotlin
class BuildingSpatialIndex {
    fun rebuild(buildings: List<GridBuildingData>)  // 按网格单元建索引
    fun findBuildingAt(gridX: Int, gridY: Int): GridBuildingData?  // O(1) 查找
}
```

将建筑按占用的所有网格单元建 Hash 索引（Long key = (gridX << 32) | gridY），触控检测从 O(n) 线性查找改为 O(1) Hash 查找。在 `SectGroundCanvas` 的 `pointerInput` 手势处理中使用。

---

## 构建与 Profile

### 测试架构（v3.2.02 新增）

| 测试类 | 目的 | 测试数 |
|--------|------|--------|
| `StateRevertRegressionTest` | 状态回退 bug 不重现：玩家字段保留、身份不丢失、灵草不丢失 | 3 |
| `DiscipleMergeCoverageTest` | **编译期安全网**：Disciple 新增字段强制归类到结算修改/玩家操作/不变 | 4 |
| `GameDataSettlementCoverageTest` | **编译期安全网**：GameData 每个字段必须有 `@SettlementStrategy` 注解 | 1 |

```bash
cd android && ./gradlew.bat test                              # 全部测试 (~930)
cd android && ./gradlew.bat testDebugUnitTest \
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
- **已移除**：`composeOptions { kotlinCompilerExtensionVersion = '1.5.8' }`（冗余/冲突）
- **默认启用**：Strong Skipping Mode
- **稳定性配置**：`stability_config.conf` — 26 个类的显式稳定性声明
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

## 事件驱动惰性求值 (v3.2.15)

### 核心思想

每种耗时操作存储 `completionMonth` + `completionPhase`，仅在 `currentMonth >= completionMonth && currentPhase >= completionPhase` 时才结算。**焦点域强制立即结算**（100ms tick），保证玩家体验。

### 关键文件

- `LazyEvaluationDispatcher` — 统一调度器：`shouldSettle()` / `shouldSettleWithThermal()` / `isInFocusDomain()`
- `GameSystem.settlementPhase` — 每个系统声明自己属于哪个结算旬（1=上旬/2=中旬/3=下旬/0=每旬）
- `SystemManager.onPhaseTickWithDomainFilter()` — 分旬过滤 + 热状态联动

### 数据模型新增字段

- `Disciple`: `cultivationCompletionMonth/Phase`, `manualCompletionMonth/Phase`, `equipmentNurturingCompletionMonth/Phase`
- `ProductionSlot`: `completionMonth`, `completionPhase`
- `SpiritFieldPlant`: `completionMonth`, `completionPhase`
- DB Migration v32→v33：ALTER TABLE 新增 8 列

### 焦点域实时化 (v3.2.16)

- **DISCIPLES Tab**: `processDiscipleTick` 每 100ms 推进全体弟子修炼值（`rate × 0.1s`）、HP/MP 恢复、buff 时效。`updateFocusedDisciple` 对焦点弟子额外推进功法熟练度 + 装备孕养
- **BUILDINGS Tab**: `ProductionSubsystem.onPhaseTick` 每 200ms 检测生产槽位完成 + 触发自动锻造/自动炼丹
- **三重兜底**: 实时 tick + 月度结算扣除（`highFreqData.cultivationUpdates`）+ 战斗前正常恢复（`CombatService`，满状态跳过）

### 修炼惰性结算 (v3.2.16)

- `SettlementCache.farFromCompletionIds`：距突破 >2 月的弟子跳过月度结算
- 距突破 ≤2 月自动进入窗口，逐月推进
- 修炼速度变化 → 脏标记 → 强制下一次结算 → 重算 `completionMonth`
- 突破被动触发：仅 `cultivation >= maxCultivation` 时判定
- Cache 增量重建 (v3.2.22)：`CultivationRateFingerprint` 检测住所/长老/传功/政策变化，未变化时复用 `SettlementCache`

### 其他性能改进

- `GameStateStore` 版本计数器 + `sample(50)` 批处理 StateFlow 发射
- `ThermalMonitor` ADPF Performance Hint API 集成（API 31+）
- `MainGameScreen` 热状态自适应渲染分辨率（NORMAL→1.0 / MODERATE→0.75 / SEVERE→0.6 / EMERGENCY→0.5）
- `CultivationService` 微批次 yield（每 50 人 yield）、PhaseTickAccumulator 合并副作用
- `GameEngineCore` 专用游戏线程（`GAME_DISPATCHER`）、空闲检测保留 tick 改降域
- 月度结算精简：薪水年度化、盗窃提前退出、执法被动触发、洞府移除、侦察/任务惰性化、外交限制 2 次/月、任务刷新每 3 月
- 自动装备/自动学习脏标记：仅储物袋有物品或装备/功法变更时检测（`ConcurrentHashMap.newKeySet`）
- **战斗前 HP/MP 恢复**：`recoverHpMpForBattleParticipants` 仅对非满状态弟子做正常恢复结算（`rate × multiplier`），满 HP+MP 跳过

---

## 后续优化项

| 优先级 | 描述 | 预估收益 | 状态 |
|--------|------|---------|------|
| P2 | MainGameScreen 继续拆分（尚余 1311 行） | 可维护性 | 待实施 |
| P2 | SaveLoadViewModel 继续拆分（尚余 1437 行，协调器复杂度高） | 可维护性 | 待实施 |
| P3 | 核心引擎层测试覆盖率从 ~5% 提升至 60% | 回归拦截 | 待实施 |
| P3 | ViewModel 层测试（当前为零） | UI 正确性 | 待实施 |
| P4 | 并发压力测试（100+ 协程） | 验证极端场景 | 待实施 |
| P4 | 事件溯源审计日志 | 时间旅行调试 | 待实施 |

> 已删除/已完成项（v4.0.00）：
> - ✅ **巨型文件拆分**：CultivationService(3804→拆10文件)、GameEngine(3000→拆9文件)、DiscipleDetailScreen(2647→542)、SaveDataConverter(2002→拆7文件)、ItemDetailDialog(1548→拆3文件)、WarehouseTab(1568→拆8文件)、ChangelogData(1999→44)、ProtobufConverters(1145→544)
> - ✅ **反模式清零**：!! 110→0、runBlocking 17→0、TODO 14→0、@Suppress 60+→15
> - ✅ **静态分析工具链**：Detekt + Lint 集成 + Baseline 生成
> - ✅ **DB 重置**：版本号 → 1，9 个历史 Migration 全部移除，旧 db 文件直接删除
> - ✅ **BUGLY 密钥防泄漏**：硬编码默认值从 build.gradle 移除
> - ✅ **R8 日志剥离**：release 构建自动去除 Log.d/v/i
> - ~~Disciple 字段注解驱动合并~~ → ✅ 已完成（v3.2.21）
> - ~~`updateXxxDirect` 方法移除~~ → ✅ 已完成（v3.2.21）
