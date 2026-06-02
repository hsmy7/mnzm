# XianxiaSectNative 模块化架构评估报告

> 评估日期：2026-06-02 | 版本：v3.1.97 | 评估方法：四轮循环检查 + 行业对标

---

## 目录

1. [架构现状总览](#1-架构现状总览)
2. [量化指标](#2-量化指标)
3. [行业对标分析](#3-行业对标分析)
4. [模块化问题识别](#4-模块化问题识别)
5. [模块化优势](#5-模块化优势)
6. [改进方向与判断节点](#6-改进方向与判断节点)
7. [交叉验证与误判检查](#7-交叉验证与误判检查)
8. [综合评分](#8-综合评分)

---

## 1. 架构现状总览

```
┌─────────────────────────────────────────────────────────────┐
│ UI Layer: Compose + ViewModel (21个ViewModel + Delegate)    │
│   ├── game/ (主游戏界面、对话框、地图)                       │
│   ├── components/ (共享UI组件)                               │
│   ├── theme/ (设计系统)                                      │
│   └── navigation/ (路由)                                    │
├─────────────────────────────────────────────────────────────┤
│ Engine Layer: GameEngine(Facade) → GameEngineCore(Loop)     │
│   ├── service/ (10个领域Service)                             │
│   │   └── CultivationService, CombatService, DiscipleService│
│   │       BuildingService, DiplomacyService, ExplorationService│
│   │       SaveService, FormulaService, MailService,          │
│   │       RedeemCodeService                                  │
│   ├── system/  (7个ECS-like System)                          │
│   │   └── TimeSystem, InventorySystem, PartnerSystem,        │
│   │       ChildBirthSystem + ServiceInterfaces/GameSystem     │
│   ├── subsystem/ (ProductionSubsystem, EconomySubsystem)    │
│   ├── settlement/ (SettlementCoordinator + 4辅助类)         │
│   ├── production/ (ProductionCoordinator)                    │
│   └── coordinator/ (SaveLoadCoordinator, SavePipeline)      │
├─────────────────────────────────────────────────────────────┤
│ State Layer: GameStateStore (16个独立StateFlow)              │
│   + UnifiedGameStateManager (Observer模式)                   │
│   + UnifiedGameState (全局状态快照)                          │
│   + MutableGameState (事务内可变状态)                        │
├─────────────────────────────────────────────────────────────┤
│ Data Layer: StorageFacade → StorageEngine                    │
│   ├── local/ (Room DB, 25个版本迁移, 15+ DAO)               │
│   ├── serialization/ (Protobuf + JSON + CBOR)                │
│   ├── crypto/ (加密存储)                                     │
│   ├── wal/ (预写日志)                                        │
│   ├── cache/ (缓存层)                                        │
│   ├── compression/ (LZ4/Zstd)                               │
│   ├── backup/ + archive/ + recovery/                         │
│   └── incremental/ (变更追踪)                                │
├─────────────────────────────────────────────────────────────┤
│ Infra: DI(Hilt 4模块) / Network(Retrofit+OkHttp) / TapTap  │
└─────────────────────────────────────────────────────────────┘
```

### 数据流

```
User Action (Compose UI)
  → ViewModel calls GameEngine method
    → GameEngine delegates to Service (e.g., CombatService)
      → Service reads from / writes to GameStateStore._state
        → StateFlow emits new UnifiedGameState
          → ViewModel.collectAsState() triggers recomposition
            → Compose UI renders updated state
```

---

## 2. 量化指标

| 指标 | 数值 | 说明 |
|------|------|------|
| Kotlin 源文件总数 | ~200+ | `android/app/src/main/java/com/xianxia/sect/` |
| 接口定义 | 67个 | 跨31个文件 |
| @Singleton 类 | 103处 | 跨82个文件 |
| @Inject 使用 | 107处 | 跨95个文件 |
| GameEngine 方法数 | ~519 | 含属性访问器 |
| GameEngine 属性数 | ~162 | 含委托属性 |
| GameEngine 文件体积 | 186KB | 单文件 |
| GameEngine 构造函数依赖 | 17个 | 注入的服务/系统 |
| GameEngine 直接消费者 | 21个文件 | import GameEngine |
| GameStateStore 方法数 | ~29 | 核心更新入口 |
| GameStateStore 属性数 | ~124 | 含StateFlow和快照 |
| GameData 属性数 | ~263 | Room @Entity |
| DB 迁移版本数 | 25 | 1.json ~ 25.json |
| Service 文件数 | 10 | `core/engine/service/` |
| System 文件数 | 7 | `core/engine/system/` (不含辅助) |
| EventBus 事件类型 | 13 | DomainEvent 子类 |
| ViewModel 数量 | 21 | 含SaveLoadViewModel |
| UI Delegate | 4 | Navigation/Inventory/Disciple/Planting |
| UseCase 类 | 3 | DisciplePosition/ElderManagement/SectPolicyToggle |

---

## 3. 行业对标分析

### 3.1 ECS (Entity-Component-System) 架构

**行业代表**：守望先锋(Overwatch)、Unity DOTS、Minecraft Bedrock Edition

**ECS 核心原则**：
- Entity = 纯ID，无数据无行为
- Component = 纯数据，连续内存存储（SoA布局）
- System = 纯逻辑，按Component筛选批量处理
- 数据与行为彻底分离，系统间通过Component读写隐式通信
- 天然支持并行：声明读写依赖后，无依赖的系统可并行执行

| 维度 | ECS 行业标准 | 当前项目 | 差距等级 |
|------|-------------|---------|---------|
| Entity | 纯ID | 无Entity概念，Disciple等是富数据类 | ★★★ |
| Component | 纯数据，SoA布局 | Data Class混合数据与计算属性，AoS布局 | ★★☆ |
| System | 纯逻辑，无状态 | Service/System混合，部分System持有状态 | ★★☆ |
| 数据布局 | SoA (Structure of Arrays) | AoS (Array of Structures) | ★★★ |
| 系统并行 | 声明读写依赖，自动并行调度 | Mutex串行，单线程tick | ★★★ |

**评估**：项目自称"ECS-like"，但实际是**传统 OOP 服务层架构**。与真正的 ECS 差距显著。但对于修仙模拟器这种实体数量有限（通常<1000弟子）的游戏，ECS 的性能优势并不关键，当前架构在业务复杂度管理上是合理的选择。完全迁移到 ECS 的ROI不高。

### 3.2 Clean Architecture / 分层架构

**行业代表**：现代 Android 官方推荐、Genshin Impact 客户端、网易游戏框架

| 维度 | Clean Architecture | 当前项目 | 评估 |
|------|-------------------|---------|------|
| 分层 | UI → Domain → Data，严格单向 | UI → Engine → State → Data | ✅ 基本符合 |
| 依赖规则 | 内层不依赖外层 | Engine不依赖UI，Data不依赖Engine | ✅ 符合 |
| UseCase | 每个用例一个类 | 3个UseCase + ViewModel直接调Engine | ⚠️ 部分缺失 |
| 接口隔离 | 面向接口编程 | ServiceInterfaces.kt 定义了9个接口 | ✅ 有意识 |
| 跨层调用 | 通过Repository接口 | ViewModel直接注入GameEngine | ⚠️ 绕过Domain层 |
| 依赖注入 | 构造函数注入 | Hilt 4模块，103处@Singleton | ✅ 完善 |

### 3.3 事件驱动架构

**行业代表**：Unreal Engine Gameplay System、腾讯游戏框架

| 维度 | 事件驱动架构 | 当前项目 | 评估 |
|------|-------------|---------|------|
| 事件总线 | 系统间通过事件解耦 | EventBus + DomainEvent 体系完整 | ✅ |
| 事件使用率 | 核心通信方式 | EventBus存在但使用率低，多数通过直接方法调用 | ⚠️ |
| 响应式 | 系统订阅感兴趣的事件 | 仅少数场景使用subscribe | ⚠️ |
| 事件溯源 | 事件可回放 | 无事件溯源机制 | — 不适用 |

### 3.4 同类游戏架构参考

| 游戏 | 架构风格 | 可借鉴点 |
|------|---------|---------|
| 了不起的修仙模拟器 | 模块化修仙引擎2.0 + 插件系统 | 功能模块可插拔，新门派通过配置添加 |
| RimWorld | Def系统 + 事件驱动 + 按功能域拆分状态 | GameData按功能域拆分为多个Saveable |
| Oxygen Not Included | 状态机 + 组件化实体 | 每个建筑/生物是独立组件集合 |
| 原神/星铁 | ECS变体 + 分层 + 热更新 | Service接口化支持热更 |

---

## 4. 模块化问题识别

### 🔴 P0-1：GameEngine 上帝类

**位置**：`core/engine/GameEngine.kt`

**数据**：
- ~519个方法、~162个属性，文件体积 186KB
- 构造函数注入 17 个依赖
- 大量方法是一行委托（`fun xxx() = service.xxx()`），但混入了大量业务逻辑
- 21 个文件直接依赖 GameEngine

**具体问题**：
1. **职责过多**：同时承担 Facade路由、业务编排、状态代理、生命周期管理
2. **委托膨胀**：每个 Service 的公开方法都在 GameEngine 有一行委托，导致方法数持续增长
3. **业务逻辑泄漏**：`loadData()` 270行、`createNewGame()` 等方法包含复杂编排逻辑
4. **测试困难**：无法单独测试某个功能而不启动整个 Engine

**行业对比**：守望先锋的 GameEngine 核心类仅负责系统调度，不包含任何业务方法。Unity 的 GameManager 也是纯协调角色。

### 🔴 P0-2：GameData 巨型数据类

**位置**：`core/model/GameData.kt`

**数据**：
- ~263个属性
- Room @Entity，25个数据库版本迁移
- 包含从游戏时间到外交关系到生产槽位到巡逻配置等所有状态

**具体问题**：
1. **字段膨胀**：任何功能新增都向 GameData 添加字段
2. **迁移成本**：每次字段变更都需要 DB Migration，已累计25次
3. **结算合并复杂**：`GameStateStore.mergeGameData()` 有 ~90行自定义合并策略（7个CUSTOM字段）
4. **copy() 开销**：每次 tick 都要 copy 整个 GameData（即使只改了一个字段）
5. **ProtoBuf 限制**：不支持 Set/Map，导致部分字段设计受限

**行业对比**：ECS 中每个 Component 只包含一类数据。RimWorld 按 Def 类型拆分存档。即使是传统架构，也应按领域拆分状态。

### 🟡 P1-1：Service 与 System 职责边界模糊

**位置**：`core/engine/service/` vs `core/engine/system/`

**现状**：
- `service/`：10个文件，侧重"UI调用的业务操作"
- `system/`：7个文件，侧重"tick驱动的自动逻辑"
- 但 CultivationService 同时实现 `GameSystem` 接口和 `CultivationSystem` 接口
- DiscipleService、BuildingService 等也同时实现 `GameSystem`
- SystemManager 注册的16个"系统"中，大部分是 Service

**行业对比**：ECS 中 System 是纯逻辑无状态，Service 是有状态的领域服务，两者不应混同。守望先锋严格区分 System（每帧执行）和 Manager（按需调用）。

### 🟡 P1-2：GameStateStore 双写问题

**位置**：`core/state/GameStateStore.kt`

**现状**：
- `_state: MutableStateFlow<UnifiedGameState>` + 16个独立 `MutableStateFlow`
- 每次 `update()` 都要同时更新 `_state` 和独立流
- `swapFromShadow()` 合并逻辑极其复杂（~90行弟子合并 + ~90行GameData合并）
- `loadFromSnapshot()` 中 `storageBags` 硬编码为 `emptyList()` 的 bug

**风险**：双写增加不一致风险。`_state` 是向后兼容的遗留物，独立流是性能优化的新设计，两者并存增加了维护负担。

### 🟡 P1-3：EventBus 使用率低

**位置**：`core/event/GameEvents.kt`

**现状**：
- EventBus 实现完整（Channel + Subscriber + 13种事件类型）
- 但系统间通信主要靠直接方法调用和共享 MutableGameState
- EventBus 更多用于日志/通知，而非系统间解耦

**行业对比**：Unreal 的 Gameplay System 完全基于事件驱动。守望先锋的 ECS 系统通过 Component 读写隐式通信。直接方法调用导致系统间紧耦合。

### 🟢 P2-1：UI 层 ViewModel 直接依赖 GameEngine

**现状**：
- 21 个文件直接 import GameEngine
- ViewModel 通过 GameEngine 的委托方法操作业务逻辑
- 已有 Delegate 模式（NavigationDelegate、DiscipleDelegate、InventoryDelegate、PlantingDelegate）但覆盖不全

**行业对比**：Clean Architecture 推荐通过 UseCase/Repository 接口隔离。项目已有3个 UseCase 但大部分 ViewModel 仍直接依赖 GameEngine。

### 🟢 P2-2：core/engine/ 目录下散落的独立系统

**现状**：
- `BattleSystem.kt`、`HerbGardenSystem.kt`、`CaveExplorationSystem.kt`、`MissionSystem.kt` 等直接放在 `core/engine/` 根目录
- `DisciplePillManager.kt`、`DiscipleEquipmentManager.kt`、`DiscipleManualManager.kt` 是弟子子域的 Manager
- `AISectAttackManager.kt`、`AISectDiscipleManager.kt`、`AISectGarrisonManager.kt` 是 AI 子域
- 目录组织缺乏一致的分类标准

---

## 5. 模块化优势

### 已做好的部分

| # | 实践 | 位置 | 说明 |
|---|------|------|------|
| 1 | DI 体系完善 | `di/` (4模块) | Hilt管理82个Singleton，依赖关系显式声明 |
| 2 | 数据层高度模块化 | `data/` | StorageFacade作为唯一外部API，内部WAL/加密/压缩/备份/归档/恢复/增量追踪完整 |
| 3 | 增量发射优化 | `GameStateStore` | 16个独立StateFlow + `!==`引用对比，避免不必要UI重组 |
| 4 | Service 接口契约 | `ServiceInterfaces.kt` | 9个领域接口，GameEngine通过接口依赖 |
| 5 | 结算系统独立 | `settlement/` | SettlementCoordinator + Shadow事务 + 三路合并，与主tick循环隔离 |
| 6 | GameSystem 统一接口 | `GameSystem.kt` | 所有系统实现统一接口，SystemManager按优先级调度 |
| 7 | UI Delegate 模式 | `ui/game/delegate/` | 部分ViewModel已使用Delegate拆分逻辑 |
| 8 | 数据流单向 | 全局 | UI → ViewModel → GameEngine → Service → GameStateStore，不反向引用 |
| 9 | 防御性编程 | 全局 | 异常就地捕获，StateFlow有初始值，模块异常不导致应用崩溃 |
| 10 | 性能分级 | Canvas/GCOptimizer | 设备分级(LOW/MEDIUM/HIGH/ULTRA)，自适应渲染策略 |

---

## 6. 改进方向与判断节点

| 优先级 | 问题 | 行业参考方案 | 关键判断节点 |
|--------|------|-------------|-------------|
| P0 | GameEngine 519方法 | Facade拆分为多个领域Facade（DiscipleFacade、BattleFacade、DiplomacyFacade等） | 是否接受短期内大量文件修改？ViewModel需同步更新依赖 |
| P0 | GameData 263属性 | 按领域拆分为多个Entity（SectState、DiplomacyState、ProductionState等） | 是否接受DB迁移成本？拆分后跨Entity事务如何处理？ |
| P1 | Service/System混同 | 严格区分：System=无状态tick逻辑，Service=有状态业务操作 | CultivationService这类双重身份的类如何处理？是否需要引入Mediator？ |
| P1 | GameStateStore双写 | 消除`_state`，仅保留独立流；或消除独立流，仅保留`_state`+精确map | 哪种迁移路径的回归风险更低？ |
| P1 | EventBus使用率低 | 系统间通信改为事件驱动（如战斗结果→通知→UI更新） | 是否会引入事件顺序依赖问题？调试复杂度是否可接受？ |
| P2 | ViewModel→GameEngine直耦 | 扩展UseCase层，ViewModel仅依赖UseCase | 新增的UseCase类是否值得维护成本？ |
| P2 | engine/目录散落系统 | 按子域组织（combat/、disciple/、ai-sect/、production/） | 纯目录重组，风险低但需同步更新import |

### P0 改进路径示例：GameEngine 拆分

```
当前:
  GameEngine (519方法)
    ├── 弟子相关 (~80方法)
    ├── 战斗相关 (~30方法)
    ├── 建筑相关 (~40方法)
    ├── 外交相关 (~20方法)
    ├── 仓库相关 (~50方法)
    ├── 生产相关 (~60方法)
    ├── 存档相关 (~30方法)
    └── 其他 (~209方法)

目标:
  GameEngine (协调者，~50方法)
    ├── DiscipleFacade (~80方法)
    ├── BattleFacade (~30方法)
    ├── BuildingFacade (~40方法)
    ├── DiplomacyFacade (~20方法)
    ├── InventoryFacade (~50方法)
    ├── ProductionFacade (~60方法)
    └── SaveFacade (~30方法)
```

### P0 改进路径示例：GameData 拆分

```
当前:
  GameData (263属性)
    ├── 核心时间/速度 (~10)
    ├── 弟子招募列表 (~5)
    ├── 建筑放置 (~10)
    ├── 外交/宗门 (~30)
    ├── 生产/槽位 (~20)
    ├── 巡逻/执法 (~15)
    ├── 经济/灵石 (~10)
    ├── 世界地图 (~30)
    └── 配置/策略 (~133)

目标:
  GameData (核心，~30属性) — 时间/速度/基础宗门信息
  DiplomacyState — 外交关系/宗门详情/贸易
  ProductionState — 生产槽位/炼丹/锻造
  PatrolState — 巡逻配置/执法
  WorldMapState — 世界地图/洞府/探索
  SectPolicyState — 宗门策略/自动配置
```

---

## 7. 交叉验证与误判检查

| # | 初步判断 | 验证方式 | 验证结果 | 是否误判 |
|---|---------|---------|---------|---------|
| 1 | GameEngine 是上帝类 | 统计方法/属性/依赖/消费者 | 519方法/162属性/17依赖/21消费者 | ❌ 确认 |
| 2 | GameData 过大 | 统计属性/迁移/合并逻辑 | 263属性/25次迁移/90行合并 | ❌ 确认 |
| 3 | 存在循环依赖 | 扫描所有import链 | 未发现A→B→A循环 | ✅ 无循环依赖 |
| 4 | EventBus 未充分使用 | 检查subscribe调用和事件发射点 | 13种事件定义但系统间仍用直接调用 | ❌ 确认 |
| 5 | Service/System边界模糊 | 检查类同时实现GameSystem+Service接口 | CultivationService同时实现两者 | ❌ 确认 |
| 6 | 缺少接口抽象 | 统计interface定义 | 67个接口，ServiceInterfaces有9个领域接口 | ⚠️ 部分误判——接口存在但GameEngine本身无接口 |
| 7 | 数据层过度工程 | 检查各子模块使用情况 | WAL/加密/压缩等均有实际使用场景 | ⚠️ 部分误判——对单机游戏而言部分设施偏重 |
| 8 | loadFromSnapshot bug | 代码审查 | `storageBags = emptyList()` 硬编码 | ❌ 确认是bug |

---

## 8. 综合评分

| 维度 | 评分(1-10) | 说明 |
|------|-----------|------|
| 分层清晰度 | 7/10 | UI→Engine→State→Data 四层清晰，但 Engine 内部混乱 |
| 模块内聚性 | 5/10 | Data层高内聚，Engine层低内聚（GameEngine承担过多） |
| 模块间耦合 | 5/10 | GameEngine是耦合枢纽，21个文件依赖它 |
| 接口抽象 | 6/10 | 有ServiceInterfaces但覆盖不全，GameEngine无接口 |
| 可测试性 | 4/10 | GameEngine 519方法难以单元测试，需启动完整引擎 |
| 可扩展性 | 5/10 | 新增功能需修改GameEngine，违反开闭原则 |
| 数据管理 | 8/10 | StorageFacade设计优秀，增量发射优化到位 |
| 状态管理 | 7/10 | 单一StateStore+增量发射好，但双写和GameData过大是隐患 |

**综合模块化评分：5.9/10**

### 核心矛盾

数据层和状态层模块化程度高（8/10），但引擎层是瓶颈（4/10）。GameEngine 和 GameData 两个巨型类拖累了整体模块化水平。

### 评分分布可视化

```
分层清晰度  ████████░░  7/10
模块内聚性  █████░░░░░  5/10
模块间耦合  █████░░░░░  5/10
接口抽象    ██████░░░░  6/10
可测试性    ████░░░░░░  4/10
可扩展性    █████░░░░░  5/10
数据管理    ████████░░  8/10
状态管理    ███████░░░  7/10
─────────────────────────
综合评分    ██████░░░░  5.9/10
```

---

## 附录：评估方法论

1. **第一轮**：全面扫描项目源码结构，理解模块划分现状（目录树+文件列表+CLAUDE.md+CODE_WIKI.md）
2. **第二轮**：深入分析各模块的耦合度、职责边界、依赖关系（源码阅读+import分析+方法统计）
3. **第三轮**：对比行业标准（ECS/Clean Architecture/事件驱动），识别模块化缺陷与改进方向
4. **第四轮**：交叉验证，对每个初步判断进行反证检查，标注误判项

行业参考来源：
- ECS架构：守望先锋GDC演讲、Unity DOTS文档、Minecraft Bedrock技术博客
- Clean Architecture：Robert C. Martin原著、Android官方架构指南
- 事件驱动：Unreal Engine Gameplay System文档、腾讯游戏框架实践
- 同类游戏：了不起的修仙模拟器、RimWorld、Oxygen Not Included
