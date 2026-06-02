# 模块化架构最大化优化方案

> 基于 [2026-06-02-modularity-evaluation.md](../../2026-06-02-modularity-evaluation.md) 评估报告
> 方案日期：2026-06-02 | 行业数据来源：~20 次 WebSearch
> 设计原则：杀鸡用牛刀 — 每个问题都按最高标准处理

---

## 目录

1. [行业对标基准](#1-行业对标基准)
2. [P0-1：GameEngine 上帝类拆分](#2-p0-1gameengine-上帝类拆分)
3. [P0-2：GameData 巨型数据类拆分](#3-p0-2gamedata-巨型数据类拆分)
4. [P1-1：Service/System 职责边界清晰化](#4-p1-1servicesystem-职责边界清晰化)
5. [P1-2：GameStateStore 消除双写](#5-p1-2gamestatestore-消除双写)
6. [P1-3：EventBus 全面激活](#6-p1-3eventbus-全面激活)
7. [P2-1：ViewModel UseCase 层扩展](#7-p2-1viewmodel-usecase-层扩展)
8. [P2-2：目录结构重组](#8-p2-2目录结构重组)
9. [实施路线图](#9-实施路线图)
10. [风险评估与缓解](#10-风险评估与缓解)

---

## 1. 行业对标基准

### 1.1 顶级产品的架构标准

| 产品 | 架构模式 | 核心指标 |
|------|---------|---------|
| **守望先锋** (Blizzard) | 纯 ECS：Entity=ID, Component=纯数据, System=纯逻辑无状态 | 上百个 System，~40% Component 是 Singleton。System 声明读写依赖后自动并行调度 |
| **RimWorld** (Ludeon) | Def 系统 + 域分层 Component：GameComponent/WorldComponent/MapComponent/ThingComp | 游戏内容(XML)和引擎(C#)彻底分离。存档按域分层（每层独立 ExposeData） |
| **腾讯手游** (TGPA) | CQRS + Event Sourcing + 实时事件分析 | Apache Pulsar 消息总线，所有操作作为不可变事件记录 |
| **Android 官方** (2025) | Clean Architecture + Feature×Layer 模块化 + Core/Core-Impl 分离 | Domain 层纯 Kotlin，零 Android 依赖 |

### 1.2 本项目对标差距

| 维度 | 当前评分 | 守望先锋标准 | 差距 |
|------|---------|------------|------|
| 引擎内聚性 | 5/10 | 9/10 | GameEngine 519 方法 vs System 纯逻辑 |
| 状态结构 | 7/10 | 9/10 | GameData 263 字段 vs Component 粒度 |
| 系统耦合 | 5/10 | 9/10 | 直接调用 vs Component 声明式依赖 |
| 可测试性 | 4/10 | 8/10 | 需启动完整引擎 vs System 独立测试 |

---

## 2. P0-1：GameEngine 上帝类拆分

### 2.1 设计

**目标**：将 GameEngine（519 方法 / 162 属性 / 186KB）拆分为 7 个领域 Facade，GameEngine 降为纯协调器（~50 方法）。

**行业参考**：守望先锋的 System 按领域分（Movement System、Health System、Ability System），各 System 之间不直接调用。Unity Subsystem 模式：`GameManager → Subsystem.Register() → Subsystem.Update()`。

```
┌─────────────────────────────────────────────────────────────┐
│                    GameEngine (协调器 ~50方法)               │
│  - 生命周期管理 (init/start/stop/pause/resume)               │
│  - Facade 注册与依赖注入                                      │
│  - 跨领域协调 (settlement trigger, 存档触发)                  │
├─────────────────────────────────────────────────────────────┤
│  DiscipleFacade      (~80方法)  — 弟子 CRUD, 分配, 修炼     │
│  BattleFacade        (~30方法)  — 战斗, 巡逻, AI攻击         │
│  BuildingFacade      (~40方法)  — 建筑放置, 拆除, 交互       │
│  InventoryFacade     (~50方法)  — 仓库, 装备, 功法, 丹药     │
│  ProductionFacade    (~60方法)  — 生产, 炼丹, 锻造, 种植     │
│  DiplomacyFacade     (~20方法)  — 外交, 宗门关系, 贸易       │
│  SaveFacade           (~30方法)  — 存档, 加载, 迁移           │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 Facade 接口

每个 Facade 定义接口契约，GameEngine 通过接口依赖：

```kotlin
interface DiscipleFacade {
    suspend fun recruitDisciple(name: String): Disciple
    suspend fun expelDisciple(id: String)
    suspend fun assignWork(id: String, work: DiscipleWorkType)
    fun getAliveDisciples(): StateFlow<List<DiscipleAggregate>>
    // ... 其余 ~80 方法
}

@Singleton
class DiscipleFacadeImpl @Inject constructor(
    private val discipleService: DiscipleService,
    private val stateStore: GameStateStore
) : DiscipleFacade { ... }
```

### 2.3 迁移策略（零破坏性变更）

采用 **"Beautiful Boring" 增量迁移**（行业标准——dev.to 2025 年推荐）：

| 步骤 | 内容 | 对现有代码影响 |
|------|------|--------------|
| 1 | 创建 `DiscipleFacade` 接口 + `DiscipleFacadeImpl`，从 GameEngine 中**复制**弟子相关方法 | **零** — GameEngine 方法保留 |
| 2 | 修改 GameEngine 弟子方法为 `fun xxx() = discipleFacade.xxx()` 委托 | 零 — 调用方不变 |
| 3 | Hilt 绑定 `DiscipleFacade` → `DiscipleFacadeImpl`，GameEngine 构造函数加 `DiscipleFacade` | 零 — 编译期类型安全 |
| 4 | 逐步将 ViewModel 中 `gameEngine.recruitDisciple()` 改为 `discipleFacade.recruitDisciple()` | 逐文件迁移，每文件独立验证 |
| 5 | 全部迁移完毕后，删除 GameEngine 中的弟子方法 | 清理死代码 |
| 6 | 重复步骤 1-5，依次处理 BattleFacade → BuildingFacade → ... | 每次一个领域 |

### 2.4 预期收益

| 指标 | 改造前 | 改造后 |
|------|--------|--------|
| GameEngine 方法数 | 519 | ~50 |
| 单文件体积 | 186KB | ~15KB（GameEngine）+ 7×~20KB（Facade） |
| 构造函数依赖 | 17 | ~8（+ 7 Facade 接口） |
| 单元测试覆盖可行性 | 接近 0 | 每个 Facade 独立可测 |
| ViewModel 直接依赖 | 21 个文件 | 逐步减少到按 Facade 依赖 |

---

## 3. P0-2：GameData 巨型数据类拆分

### 3.1 设计

**目标**：将 GameData（263 属性）拆分为 1 个核心 Entity + 5 个领域 Entity，按域独立建表。

**行业参考**：
- RimWorld：GameComponent / WorldComponent / MapComponent 域分层，每层独立 `ExposeData()` 序列化
- Room 最佳实践（2025）：`@Embedded` + `@Relation` + `@Junction` 多表映射
- Android Clean Architecture：Entity 层纯粹 DTO，Domain 层用 Mapper 转换

### 3.2 拆分方案

```
改造前:                                    改造后:
┌─ GameData (263字段, 1个表) ─┐           ┌─ GameData (核心 ~35字段, 1个表) ─┐
│  基础宗门 (~10)              │           │  gameTime, sectName, spiritStones│
│  弟子/招募 (~5)              │           │  gamePhase, gameSpeed            │
│  建筑/放置 (~10)             │           ├─ DiplomacyState (~40字段, 独立表)┤
│  外交/宗门 (~40)     →       │           │  外交关系/宗门详情/贸易            │
│  生产/槽位 (~20)             │           ├─ ProductionState (~25字段, 独立表)┤
│  巡逻/执法 (~15)             │           │  生产槽位/炼丹/锻造               │
│  经济/灵石 (~10)             │           ├─ PatrolState (~20字段, 独立表)   │
│  世界地图 (~40)              │           │  巡逻配置/执法/囚犯               │
│  配置/策略 (~113)            │           ├─ WorldMapState (~45字段, 独立表)  │
└──────────────────────────────┘           │  世界地图/洞府/探索               │
                                           ├─ SectPolicyState (~98字段, 独立表)│
                                           │  宗门策略/自动配置                 │
                                           └──────────────────────────────────┘
```

### 3.3 数据库迁移

采用行业标准**两步法**（2025 Room 最佳实践）：

```kotlin
val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE diplomacy_state (
                slot_id INTEGER NOT NULL DEFAULT 1,
                sect_relations TEXT NOT NULL DEFAULT '{}',
                trade_routes TEXT NOT NULL DEFAULT '[]',
                -- ... 其余外交字段
                PRIMARY KEY (slot_id)
            )
        """)
        // 类似创建 production_state, patrol_state, world_map_state, sect_policy_state
        
        // 从旧 game_data 中复制对应列到新表（不需要数据迁移，保留在 game_data 中）
        // game_data 保留全部列，新 Entity 通过 @Ignore 或业务层读取旧字段
        
        // v1: 新表创建 + game_data 保留旧字段（零数据迁移风险）
        // v2: 业务层逐步迁移读写到新表
        // v3: game_data 删除旧字段（需要 Migration）
    }
}
```

### 3.4 迁移策略（最小风险）

> ⚠️ **不走"直接拆分+数据迁移"路线**——风险太高（用户存档不可逆）。

分 3 阶段：

| 阶段 | 操作 | 风险 |
|------|------|------|
| **Phase A** | 新 Entity 建表 + `@Ignore` 标记 GameData 中对应字段。业务层新增读写新表的路径，旧字段保留不删 | 零（只加不改） |
| **Phase B** | 业务层逐步改写：新功能写新表，旧功能双读（新表有值读新表，无值回退旧字段） | 低（渐进迁移） |
| **Phase C** | 全部业务层迁移完毕后，`ALTER TABLE game_data DROP COLUMN xxx` 逐列删除旧字段 | 中（需要 Migration） |

### 3.5 预期收益

| 指标 | 改造前 | 改造后 |
|------|--------|--------|
| 单 Entity 属性数 | 263 | 35 (GameData core) |
| DB 表数 | ~15 | ~20 |
| 新增功能迁移成本 | 每次修改 GameData | 修改对应领域 Entity |
| copy() 开销 | 263 字段全量 | 仅对应 Entity 的字段 |
| 合并逻辑复杂度 | 90行 mergeGameData | 各域独立合并，每域 ~10-20 行 |

---

## 4. P1-1：Service/System 职责边界清晰化

### 4.1 设计

**行业标准定义**：

| 类型 | 守望先锋定义 | 本项目新定义 |
|------|------------|------------|
| **System** | 纯逻辑，无状态。声明 Component 读写依赖，框架驱动执行 | 每 tick 执行的自动化逻辑，不持有业务状态，通过 `GameSystem.onPhaseTick()` 调度 |
| **Service** | 无（使用 Manager 处理有状态操作） | 被 UI/结算 调用的业务操作，持有临时状态（缓存、事务），通过 Facade 暴露 |
| **Manager** | 内部工具类 | 某个实体的子域管理器（如 DiscipleManualManager），辅助 Service |

**边界规则**：
- System **不得**持有业务状态（只有计时器/缓存等内部状态）
- Service **不得**在 tick 中被直接调用（只能通过 EventBus 或 System.finish() 回调）
- System 之间**不得**直接调用（通过 EventBus 通知）

### 4.2 实施

1. 将 `CultivationService` 拆分为 `CultivationSystem`（tick 修炼逻辑，无状态）+ `CultivationService`（突破/丹药等，有状态）
2. `SystemManager` 注册表只保留真正的 System（7 个），其余从注册表移除
3. Service 接口规范：`interface XxxService` 继承 `GameSystem` 的改为不再继承

### 4.3 预期收益

- SystemManager 注册表从 16 个精准到 ~7 个
- 每个 tick 的 System 调度链路更短
- Service 和 System 职责一目了然，新人无需猜测

---

## 5. P1-2：GameStateStore 消除双写

### 5.1 设计

**问题**：`_state`（向后兼容）+ 16 个独立 `MutableStateFlow`，每次 update 都要双写。

**行业参考**：Clean Architecture 单一事实源原则。守望先锋 ECS：每个 Component 一个存储，GameState 是 Component 存储的聚合视图。

**方案**：**消除 `_state`，独立流成为唯一事实源**。

```
改造前:
  _state + _gameDataFlow + _disciplesFlow + ... (17个流, 双写)

改造后:
  _gameDataFlow + _disciplesFlow + ... (16个流, 单一事实源)
  unifiedState = combine(16流) { ... }  ← 改为 derive, 不再 write
```

### 5.2 实施

1. 删除 `_state: MutableStateFlow<UnifiedGameState>`
2. `unifiedState` 改为 `combine(所有独立流) { ... }`（只读派生）
3. `update()` 不再构建 `UnifiedGameState`，只更新独立流
4. `loadFromSnapshot()` / `swapFromShadow()` 只设置独立流
5. 所有 snapshot getter（`gameDataSnapshot` 等）改为读独立流 `.value`
6. `mergeGameData()` 拆分为各域独立合并函数

### 5.3 预期收益

- 消除 1 个全局 MutableStateFlow + 17 处双写代码
- 一致性风险降为零（单写、无同步问题）
- `swapFromShadow` 合并逻辑从 ~180 行缩减为各域独立 ~20 行×5 域

---

## 6. P1-3：EventBus 全面激活

### 6.1 设计

**行业参考**：
- 守望先锋：System 通过 Component 读写隐式通信，不直接调用
- 腾讯手游：Apache Pulsar 消息总线，"一切操作都是事件"
- Unreal Engine：Gameplay Ability System 完全事件驱动

**方案**：将系统间通信从直接方法调用改为事件驱动。每个域只发布/订阅事件，不知道其他域的存在。

### 6.2 事件体系

```kotlin
// 现有 (13种) → 扩展为:
sealed class DomainEvent {
    // 战斗域
    data class BattleStarted(val attackerId: String, val defenderId: String) : DomainEvent()
    data class BattleEnded(val result: BattleResult) : DomainEvent()
    
    // 弟子域
    data class DiscipleBreakthrough(val discipleId: String, val newRealm: Int) : DomainEvent()
    data class DiscipleDied(val discipleId: String, val cause: DeathCause) : DomainEvent()
    
    // 建筑域
    data class BuildingCompleted(val buildingId: String, val gridX: Int, val gridY: Int) : DomainEvent()
    
    // 经济域
    data class SpiritStonesChanged(val delta: Long, val newTotal: Long) : DomainEvent()
    
    // 外交域
    data class SectRelationChanged(val sectId: String, val oldRelation: Int, val newRelation: Int) : DomainEvent()
}
```

### 6.3 激活策略

| 场景 | 当前 | 改造后 |
|------|------|--------|
| 战斗胜利 → 外交关系改变 | GameEngine 直接调用 DiplomacyService | BattleSystem 发布 `BattleEnded` → DiplomacyService 订阅 |
| 弟子突破 → 队友好感变化 | CultivationService 直接调用 PartnerSystem | CultivationService 发布 `DiscipleBreakthrough` → PartnerSystem 订阅 |
| 建筑完成 → 经济收入变化 | tick 中顺序调用 | BuildingSystem 发布 `BuildingCompleted` → EconomySubsystem 订阅 |

### 6.4 预期收益

- 系统间耦合降到零（各自只依赖 EventBus + DomainEvent 类型）
- 新增功能可以"热插拔"（订阅感兴趣的事件即可）
- 事件日志天然形成调试追踪链

---

## 7. P2-1：ViewModel UseCase 层扩展

### 7.1 设计

将 21 个 ViewModel 中直接调用 `gameEngine.xxx()` 的逻辑抽取为 UseCase 类。

### 7.2 实施

```kotlin
// 改造前
class DiscipleViewModel @Inject constructor(
    private val gameEngine: GameEngine
) {
    fun recruit(name: String) {
        viewModelScope.launch { gameEngine.recruitDisciple(name) }
    }
}

// 改造后
class RecruitDiscipleUseCase @Inject constructor(
    private val discipleFacade: DiscipleFacade
) {
    suspend operator fun invoke(name: String): Result<Disciple> = runCatching {
        discipleFacade.recruitDisciple(name)
    }
}

class DiscipleViewModel @Inject constructor(
    private val recruitDisciple: RecruitDiscipleUseCase,
    private val getDisciples: GetDisciplesUseCase,
    private val assignWork: AssignWorkUseCase
) { ... }
```

### 7.3 覆盖范围

优先覆盖 5 个主要 ViewModel（DiscipleViewModel, WarehouseViewModel, ProductionViewModel, DiplomacyDialog, BuildingDialog），每个 3-5 个 UseCase。

---

## 8. P2-2：目录结构重组

### 8.1 设计

```
改造前:
core/engine/
├── BattleSystem.kt           ← 散落
├── HerbGardenSystem.kt       ← 散落
├── CaveExplorationSystem.kt  ← 散落
├── DisciplePillManager.kt    ← 散落
├── AISectAttackManager.kt    ← 散落
├── service/
│   ├── CultivationService.kt
│   ├── CombatService.kt
│   └── ...
└── system/...

改造后:
core/engine/
├── domain/
│   ├── disciple/    (DiscipleService, DiscipleManualManager, DisciplePillManager, DiscipleEquipmentManager)
│   ├── battle/      (CombatService, BattleSystem, AISectAttackManager, AISectGarrisonManager)
│   ├── building/    (BuildingService, HerbGardenSystem)
│   ├── exploration/ (ExplorationService, CaveExplorationSystem, MissionSystem)
│   ├── production/  (ProductionSubsystem, EconomySubsystem, ProductionCoordinator)
│   ├── diplomacy/   (DiplomacyService, AISectDiscipleManager)
│   ├── settlement/  (保持不变)
│   └── save/        (SaveLoadCoordinator, SavePipeline)
├── service/         ← 清空（迁移到 domain/）
├── system/          ← 清空（迁移到 domain/）
└── GameEngine.kt    ← 协调器
```

纯重组，不改变任何逻辑。import 路径需全局更新（IDE 自动重构）。

---

## 9. 实施路线图

```
Phase 1 (1周): 零风险根基
├── P2-2 目录重组        ← 纯移动文件，IDE 自动重构 import
├── P2-1 首批 5 个 UseCase  ← DiscipleViewModel + 4 个主要 ViewModel
└── P1-3 新增 10 个事件类型 + 首批 3 个订阅

Phase 2 (2-3周): 域拆分 (逐域推进)
├── DiscipleFacade 创建 + GameEngine 委托改造
├── BattleFacade 创建
├── InventoryFacade 创建
├── BuildingFacade 创建
└── 每完成一个 Facade，对应 ViewModel 逐步迁移

Phase 3 (2周): 状态重构
├── P0-2 Phase A: 新 Entity 建表 (GameData 字段不动)
├── P0-2 Phase A: 新表读写 + 双读逻辑
└── P1-2 消除 _state 双写

Phase 4 (2-3周): 剩余 Facade + Service/System 清理
├── ProductionFacade, DiplomacyFacade, SaveFacade
├── P1-1 Service/System 边界调整
└── P1-3 EventBus 全面激活

Phase 5 (后续): 持续清理
├── P0-2 Phase B/C: 业务层迁移 + 旧字段删除
├── 剩余 ViewModel UseCase 化
└── CODE_WIKI.md 同步更新
```

---

## 10. 风险评估与缓解

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| Facade 委托遗漏 | 中 | UI 调用失败 | 编译器检查：委托方法签名与 Facade 接口一致 |
| DB Migration 数据丢失 | 低 | 玩家存档损坏 | Phase A 只加新表不删旧字段，双读机制保底 |
| EventBus 事件顺序 | 低 | 系统处理顺序错乱 | 保持 tick 内同步处理，不引入异步事件队列 |
| 目录重组遗漏引用 | 低 | 编译失败 | IDE 自动重构 + CI 编译检查 |
| 双写消除后独立流遗漏 | 中 | StateFlow 不会发射新值 | 抽象 `syncAllFlows()` 函数，每个更新入口调用 |

---

## 附录 A：行业参考来源

1. Overwatch GDC 2017 — "Gameplay Architecture and Netcode" (Tim Ford)
2. RimWorld Modding Wiki — Def System + ExposeData serialization architecture
3. Android Official (2025) — Clean Architecture + Feature×Layer modularization guide
4. Tencent Games (2024) — Real-Time Event-Driven Analytics System (HackerNoon)
5. Room Database (2025) — Entity splitting migration best practices
6. ProAndroidDev (2025) — Core/Core-Impl pattern for build performance
7. dev.to (2025) — "The Beautiful Boring: How I Refactored a Game Without Breaking it"

## 附录 B：与评估报告的对齐

| 评估问题 | 本方案位置 | 解决方案 |
|---------|----------|---------|
| P0-1 GameEngine 519方法 | §2 | 7 个领域 Facade + 增量委托 |
| P0-2 GameData 263属性 | §3 | 1 核心 + 5 领域 Entity，三阶段迁移 |
| P1-1 Service/System 模糊 | §4 | 严格边界定义 + 类型分离 |
| P1-2 GameStateStore 双写 | §5 | 消除 _state，独立流成唯一源 |
| P1-3 EventBus 使用率低 | §6 | 扩展事件类型 + 系统间事件通信 |
| P2-1 ViewModel→GameEngine 直耦 | §7 | UseCase 层扩展 |
| P2-2 目录散乱 | §8 | 按领域域重组 |
| loadFromSnapshot bug | — | 已在上次提交修复 (v3.1.98) |
