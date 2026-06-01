# XianxiaSectNative - Code Wiki

## 1. 项目概述

**项目名称**: XianxiaSectNative（仙侠宗门）  
**应用ID**: `com.xianxia.sect`  
**当前版本**: 3.1.96 (versionCode: 3196)  
**项目类型**: Android 原生应用 — 修仙宗门经营模拟游戏  
**开发语言**: Kotlin 2.0.21, JVM Target 17  
**最低 SDK**: 24 (Android 7.0) | **目标 SDK**: 35 (Android 15)

---

## 2. 整体架构

### 2.1 两层状态架构

```
┌─────────────────────────────────────────────────────────────────┐
│ Layer 2: UI (ViewModel + Jetpack Compose)                       │
│   - 通过 StateFlow 订阅 GameStateStore.unifiedState              │
│   - DialogStateManager 管理对话框状态                             │
│   - collectAsState() 触发 Compose 重组                           │
├─────────────────────────────────────────────────────────────────┤
│ Layer 1: GameEngineCore + GameEngine                             │
│   - EngineCore: 游戏循环控制 (start/stop/tick, 200ms 间隔)       │
│   - Engine: 核心业务逻辑门面 (修炼/战斗/生产/外交等)               │
│   - 状态写入 GameStateStore → unifiedState Flow 自动派生          │
│   - SystemManager 按优先级调度所有 GameSystem                     │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 架构分层图

```
┌──────────────────────────────────────────────────────────┐
│                    Android Framework                      │
├──────────┬──────────┬───────────┬────────────────────────┤
│ Activity │ Compose  │ ViewModel │  DialogStateManager    │
│          │   UI     │   Layer   │                        │
├──────────┴──────────┴───────────┴────────────────────────┤
│                    GameEngine (门面)                       │
├──────────────────────────────────────────────────────────┤
│  GameEngineCore (循环控制)  │  SystemManager (系统调度)    │
├──────────────────────────────────────────────────────────┤
│  Service 层  │  System 层  │  Subsystem 层               │
├──────────────────────────────────────────────────────────┤
│              GameStateStore (统一状态存储)                  │
├──────────────────────────────────────────────────────────┤
│              Registry (静态数据注册表)                      │
├──────────────────────────────────────────────────────────┤
│   StorageFacade → StorageEngine → Room DB + WAL + Cache   │
├──────────────────────────────────────────────────────────┤
│   Hilt DI  │  EventBus  │  Performance Monitor           │
└──────────────────────────────────────────────────────────┘
```

### 2.3 数据流

```
用户操作 → ViewModel → GameEngine 方法调用
                         ↓
                   GameStateStore.update { }
                         ↓
              SystemManager.onSecondTick/onDayTick/...
                         ↓
              各 GameSystem 修改 MutableGameState
                         ↓
              GameStateStore 发布 UnifiedGameState
                         ↓
              StateFlow → ViewModel → Compose UI 重组
```

---

## 3. 目录结构

```
XianxiaSectNative/
├── android/                          # Android 项目根目录
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/xianxia/sect/
│   │   │   │   ├── XianxiaApplication.kt    # Application 入口
│   │   │   │   ├── core/                    # 核心业务层
│   │   │   │   │   ├── engine/              # 游戏引擎
│   │   │   │   │   │   ├── GameEngineCore.kt
│   │   │   │   │   │   ├── GameEngine.kt
│   │   │   │   │   │   ├── BattleSystem.kt
│   │   │   │   │   │   ├── service/         # 领域服务
│   │   │   │   │   │   ├── system/          # ECS 系统
│   │   │   │   │   │   ├── subsystem/       # 子系统
│   │   │   │   │   │   ├── production/      # 生产系统
│   │   │   │   │   │   └── coordinator/     # 协调器
│   │   │   │   │   ├── model/              # 数据模型
│   │   │   │   │   ├── state/              # 状态管理
│   │   │   │   │   ├── registry/           # 静态数据注册表
│   │   │   │   │   ├── config/             # 配置加载
│   │   │   │   │   ├── repository/         # 仓库层
│   │   │   │   │   ├── usecase/            # 用例层
│   │   │   │   │   ├── warehouse/          # 仓库管理
│   │   │   │   │   ├── event/              # 事件总线
│   │   │   │   │   ├── performance/        # 性能监控
│   │   │   │   │   ├── concurrent/         # 并发工具
│   │   │   │   │   ├── util/               # 工具类
│   │   │   │   │   ├── transaction/        # 事务管理
│   │   │   │   │   ├── GameConfig.kt       # 游戏配置常量
│   │   │   │   │   ├── CrashHandler.kt     # 崩溃处理
│   │   │   │   │   └── ChangelogData.kt    # 更新日志
│   │   │   │   ├── data/                   # 数据持久化层
│   │   │   │   │   ├── facade/             # 存储门面
│   │   │   │   │   ├── engine/             # 存储引擎
│   │   │   │   │   ├── local/              # Room 数据库 + DAO
│   │   │   │   │   ├── unified/            # 统一存取模型
│   │   │   │   │   ├── serialization/      # 序列化
│   │   │   │   │   ├── compression/        # 数据压缩
│   │   │   │   │   ├── crypto/             # 加密
│   │   │   │   │   ├── wal/                # 预写日志
│   │   │   │   │   ├── cache/              # 缓存层
│   │   │   │   │   ├── recovery/           # 崩溃恢复
│   │   │   │   │   ├── backup/             # 备份策略
│   │   │   │   │   ├── archive/            # 数据归档
│   │   │   │   │   ├── incremental/        # 增量变更追踪
│   │   │   │   │   ├── concurrent/         # 并发控制
│   │   │   │   │   ├── config/             # 存储配置
│   │   │   │   │   ├── memory/             # 动态内存管理
│   │   │   │   │   ├── model/              # 存储模型
│   │   │   │   │   ├── result/             # 操作结果
│   │   │   │   │   ├── validation/         # 存储验证
│   │   │   │   │   ├── SessionManager.kt   # 会话管理
│   │   │   │   │   └── StorageConstants.kt # 存储常量
│   │   │   │   ├── ui/                     # UI 层
│   │   │   │   │   ├── MainActivity.kt     # 主 Activity
│   │   │   │   │   ├── PrivacyConsentScreen.kt
│   │   │   │   │   ├── SaveSelectScreen.kt
│   │   │   │   │   ├── game/               # 游戏界面
│   │   │   │   │   │   ├── GameActivity.kt
│   │   │   │   │   │   ├── GameViewModel.kt
│   │   │   │   │   │   ├── MainGameScreen.kt
│   │   │   │   │   │   ├── BaseViewModel.kt
│   │   │   │   │   │   ├── tabs/           # 标签页
│   │   │   │   │   │   ├── dialogs/        # 对话框
│   │   │   │   │   │   ├── components/     # UI 组件
│   │   │   │   │   │   └── map/            # 世界地图
│   │   │   │   │   ├── components/         # 共享组件
│   │   │   │   │   ├── theme/              # 主题
│   │   │   │   │   └── state/              # UI 状态
│   │   │   │   ├── di/                     # Hilt 依赖注入
│   │   │   │   ├── network/                # 网络层
│   │   │   │   └── taptap/                 # TapTap SDK
│   │   │   ├── assets/
│   │   │   │   ├── config/buildings.json   # 建筑配置
│   │   │   │   └── data/manuals.json       # 功法数据
│   │   │   ├── proto/                      # Protobuf 定义
│   │   │   │   ├── save_data.proto
│   │   │   │   └── templates.proto
│   │   │   ├── res/                        # 资源文件
│   │   │   └── AndroidManifest.xml
│   │   ├── schemas/                        # Room 数据库 Schema
│   │   └── build.gradle                    # 应用构建配置
│   ├── build.gradle                        # 项目构建配置
│   ├── settings.gradle                     # 项目设置
│   └── gradle.properties                   # Gradle 属性
├── docs/                                   # 设计文档
├── rules/                                  # 开发规范
│   ├── build-quality.md
│   ├── database-migration.md
│   └── version-release.md
├── scripts/                                # 工具脚本
├── CHANGELOG.md
└── CLAUDE.md                               # AI 辅助开发指引
```

---

## 4. 主要模块职责

### 4.1 核心引擎层 (`core/engine/`)

游戏的核心运行时，负责游戏循环、系统调度和业务逻辑。

| 模块 | 职责 |
|------|------|
| `GameEngineCore` | 游戏循环控制器，200ms tick 间隔，管理 start/stop/pause/resume，看门狗检测卡住状态 |
| `GameEngine` | 业务逻辑门面，注入所有 Service，暴露高级 API 给 ViewModel |
| `service/` | 领域服务层，每个服务负责一个业务域 |
| `system/` | ECS 风格系统层，实现 `GameSystem` 接口，由 `SystemManager` 按优先级调度 |
| `subsystem/` | 子系统层，处理建筑/生产/经济等复合逻辑 |
| `production/` | 生产协调器，管理炼丹/锻造等生产流程 |
| `coordinator/` | 存档协调器，管理保存/加载流程 |

#### 4.1.1 Service 层 (`core/engine/service/`)

| 服务类 | 职责 |
|--------|------|
| `CultivationService` | 修炼系统：修炼速度计算、境界突破、高频数据管理 |
| `CombatService` | 战斗系统：战斗计算、伤害处理、战斗日志 |
| `DiscipleService` | 弟子管理：弟子招募、属性计算、状态更新 |
| `BuildingService` | 建筑系统：建筑建造、升级、功能管理 |
| `DiplomacyService` | 外交系统：宗门关系、礼物赠送、外交事件 |
| `ExplorationService` | 探索系统：洞府探索、秘境探索 |
| `EventService` | 事件系统：游戏事件生成与管理 |
| `SaveService` | 存档服务：保存/加载协调 |
| `FormulaService` | 公式服务：战斗公式、长老加成计算 |
| `RedeemCodeService` | 兑换码服务：兑换码验证与奖励发放 |
| `MailService` | 邮件服务：在线拉取+内置加载、附件领取（8种类型）、一键已读、限时机制、存取档绑定 |

#### 4.1.2 System 层 (`core/engine/system/`)

| 系统类 | 职责 |
|--------|------|
| `TimeSystem` | 时间系统：游戏时间推进（年/月/日） |
| `InventorySystem` | 物品系统：物品增删、库存管理 |
| `BuildingSubsystem` | 建筑子系统：建筑状态更新 |
| `SystemManager` | 系统管理器：注册、初始化、按优先级调度所有系统 |

#### 4.1.3 独立引擎模块 (`core/engine/` 根目录)

| 模块 | 职责 |
|------|------|
| `BattleSystem` | 战斗系统：战斗流程、伤害计算、战斗结果 |
| `HerbGardenSystem` | 药园系统：灵草种植、收获 |
| `MissionSystem` | 任务系统：任务生成、刷新、完成 |
| `ManualProficiencySystem` | 功法熟练度系统 |
| `EquipmentNurtureSystem` | 装备培养系统 |
| `DiscipleStatCalculator` | 弟子属性计算器 |
| `DisciplePurchaseSystem` | 弟子购买系统 |
| `DisciplePillManager` | 弟子丹药管理 |
| `DiscipleManualManager` | 弟子功法管理 |
| `DiscipleEquipmentManager` | 弟子装备管理 |
| `DiffUpdateSystem` | 差异更新系统 |
| `WorldMapGenerator` | 世界地图生成器 |
| `EnemyGenerator` | 敌人生成器 |
| `CaveGenerator` | 洞府生成器 |
| `CaveExplorationSystem` | 洞府探索系统 |
| `SectWarehouseManager` | 宗门仓库管理器 |
| `RedeemCodeManager` | 兑换码管理器 |
| `AISectDiscipleManager` | AI 宗门弟子管理 |
| `AISectAttackManager` | AI 宗门攻击管理 |
| `AICaveTeamGenerator` | AI 洞府队伍生成 |

### 4.2 数据模型层 (`core/model/`)

定义游戏中的所有数据结构，部分类同时作为 Room Entity。

| 模型类 | 职责 | Room Entity |
|--------|------|:-----------:|
| `GameData` | 游戏核心数据：宗门名、资源、时间、月俸配置等 | ✅ 主键 `(id, slot_id)` |
| `Disciple` | 弟子完整模型 | ✅ |
| `DiscipleCore` | 弟子核心属性 | ✅ |
| `DiscipleCombatStats` | 弟子战斗属性 | ✅ |
| `DiscipleEquipment` | 弟子装备信息 | ✅ |
| `DiscipleExtended` | 弟子扩展属性 | ✅ |
| `DiscipleAttributes` | 弟子天赋属性 | ✅ |
| `DiscipleAggregate` | 弟子聚合视图（只读） | ❌ |
| `Items` | 物品系统：装备/丹药/材料/灵草/种子 | ✅ |
| `EquipmentStack` / `EquipmentInstance` | 装备堆叠/实例 | ✅ |
| `ManualStack` / `ManualInstance` | 功法堆叠/实例 | ✅ |
| `Pill` | 丹药 | ✅ |
| `Material` | 材料 | ✅ |
| `Herb` / `Seed` | 灵草/种子 | ✅ |
| `GameEvent` | 游戏事件 | ✅ |
| `BattleLog` | 战斗日志 | ✅ |
| `ExplorationTeam` | 探索队伍 | ✅ |
| `Alliance` | 联盟 | ❌ (嵌入 GameData) |
| `BuildingState` | 建筑状态 | ✅ |
| `CultivatorCave` | 修仙者洞府 | ✅ |
| `Mission` | 任务模型 | ❌ |
| `Rarity` | 稀有度枚举 | ❌ |
| `RedeemCode` | 兑换码 | ❌ |
| `ElderSlotType` | 长老槽位类型 | ❌ |
| `GiftPreferenceType` | 礼物偏好类型 | ❌ |
| `EconomicState` | 经济状态 | ❌ |
| `ExplorationState` | 探索状态 | ❌ |
| `SectOrganizationState` | 宗门组织状态 | ❌ |
| `WorldMapState` | 世界地图状态 | ❌ |
| `MapCoordinateSystem` | 地图坐标系 | ❌ |
| `GridBuildingData` | 网格建筑数据 | ❌ |
| `MapPreloadData` | 地图预加载数据 | ❌ |
| `AlchemySystem` | 炼丹系统模型 | ❌ |
| `MaterialChecker` | 材料检查器 | ❌ |
| `production/ProductionSlot` | 生产槽位 | ✅ |
| `production/SlotStateMachine` | 槽位状态机 | ❌ |
| `production/GameTime` | 游戏时间模型 | ❌ |
| `production/ProductionParams` | 生产参数 | ❌ |
| `production/ProductionError` | 生产错误 | ❌ |

#### 4.2.7 邮件 (`MailEntity.kt`)
| 类 | 说明 |
|----|------|
| `MailEntity` | 邮件 Room Entity（mails 表，14字段+3索引，1000封上限） |
| `MailAttachment` | 附件数据类（type/name/quantity/rarity），支持8种类型 |

### 4.3 状态管理层 (`core/state/`)

| 类 | 职责 |
|----|------|
| `GameStateStore` | 中央状态存储，持有 `MutableStateFlow<UnifiedGameState>`，提供事务性更新 (`update {}`)，Mutex 保证线程安全 |
| `UnifiedGameState` | 不可变统一状态数据类，包含所有游戏状态（弟子/物品/事件等），派生属性如 `aliveDisciples`/`idleDisciples` |
| `UnifiedGameStateManager` | 状态管理器，封装 `GameStateStore` 的暂停/加载/保存状态切换，维护观察者列表 |
| `MutableGameState` | 可变游戏状态，用于事务内修改，避免频繁创建不可变副本 |
| `TickContext` | Tick 上下文，提供 tick 执行时的环境信息 |
| `BagUtils` | 储物袋工具函数 |

### 4.4 注册表层 (`core/registry/`)

静态游戏数据注册表，提供模板/配置的查询接口。

| 注册表 | 职责 |
|--------|------|
| `EquipmentRegistry` / `EquipmentDatabase` | 装备模板注册 |
| `ManualRegistry` / `ManualDatabase` | 功法模板注册，从 `manuals.json` 加载 |
| `HerbRegistry` / `HerbDatabase` | 灵草模板注册 |
| `ForgeRecipeRegistry` / `ForgeRecipeDatabase` | 锻造配方注册 |
| `PillRecipeRegistry` / `PillRecipeDatabase` | 丹方注册 |
| `PillTemplateRegistry` | 丹药模板注册 |
| `MaterialTemplateRegistry` | 材料模板注册 |
| `BeastMaterialRegistry` / `BeastMaterialDatabase` | 妖兽材料注册 |
| `TalentRegistry` / `TalentDatabase` | 天赋注册 |
| `ItemDatabase` | 物品数据库 |
| `TemplateRegistry` | 通用模板注册 |
| `BaseTemplateRegistry` | 模板注册基类 |
| `GameDataManager` | 游戏数据管理器 |

### 4.5 数据持久化层 (`data/`)

#### 4.5.1 存储门面 (`data/facade/`)

| 类 | 职责 |
|----|------|
| `StorageFacade` | 存储系统唯一外部 API，封装 save/load/delete/healthCheck 操作，提供进度回调和健康报告 |

#### 4.5.2 存储引擎 (`data/engine/`)

| 类 | 职责 |
|----|------|
| `StorageEngine` | 内部存储编排器，协调数据库/缓存/WAL/压缩/归档等子系统 |
| `StorageCircuitBreaker` | 熔断器，防止存储操作连续失败导致系统崩溃 |
| `StorageIntegrity` | 数据完整性校验 |
| `StorageBackup` | 存储备份 |
| `StorageWal` | WAL 存储集成 |
| `StorageMetrics` | 存储指标收集 |
| `ProactiveMemoryGuard` | 主动内存守护 |
| `DataPruningScheduler` | 数据清理调度 |
| `DataArchiveScheduler` | 数据归档调度 |

#### 4.5.3 Room 数据库 (`data/local/`)

| 类 | 职责 |
|----|------|
| `GameDatabase` | Room 数据库定义，版本 24，统一单实例 `xianxia_sect.db`，包含所有 Migration |
| `Daos` | 所有 DAO 接口定义（GameDataDao, DiscipleDao, EquipmentStackDao 等） |
| `ProtobufConverters` | Protobuf 类型转换器 |
| `SaveSlotMetadata` / `SaveSlotMetadataDao` | 存档元数据 |
| `ProductionSlotDao` | 生产槽位 DAO |

#### 4.5.4 其他数据子模块

| 子模块 | 职责 |
|--------|------|
| `data/unified/` | 统一存取模型：SaveFileHandler, MetadataManager, BackupManager, SerializationHelper, SaveResult, StorageModels |
| `data/serialization/` | 序列化引擎：UnifiedSerializationEngine, SaveDataConverter, NullSafeProtoBuf, SerializableSaveData |
| `data/compression/` | 数据压缩：DataCompressor (LZ4 + Zstd), CompressionAlgorithm |
| `data/crypto/` | 加密：SaveCrypto, SecureKeyManager, KeyRotationManager, CryptoModule |
| `data/wal/` | 预写日志：WALProvider, FunctionalWAL |
| `data/cache/` | 缓存层：CacheLayer, CacheKey, CacheConfig, GameDataCacheManager |
| `data/recovery/` | 崩溃恢复：RecoveryManager |
| `data/backup/` | 备份策略：BackupStrategy |
| `data/archive/` | 数据归档：DataArchiver, ArchiveEntities, ArchiveDaos |
| `data/incremental/` | 增量变更：ChangeTracker, ChangeLogPersistence, ChangeLogEntity, ChangeLogDao |
| `data/concurrent/` | 并发控制：SlotLockManager |
| `data/config/` | 存储配置：StorageConfig, SaveLimitsConfig |
| `data/memory/` | 动态内存：DynamicMemoryManager |
| `data/validation/` | 存储验证：StorageValidator |
| `data/model/` | 存储模型：SaveData |
| `data/result/` | 操作结果：StorageResult |

### 4.6 UI 层 (`ui/`)

#### 4.6.1 Activity 与导航

| 组件 | 职责 |
|------|------|
| `MainActivity` | 入口 Activity，处理隐私同意、存档选择、跳转 GameActivity |
| `GameActivity` | 游戏 Activity，承载 MainGameScreen |
| `PrivacyConsentScreen` | 隐私政策同意界面 |
| `SaveSelectScreen` | 存档选择界面 |

导航模式：不使用 `NavHost`，`MainGameScreen` 通过 `MainTab` 枚举切换标签页，功能界面通过 `DialogStateManager.openDialog()` 以对话框形式打开。

#### 4.6.2 ViewModel 层

| ViewModel | 职责 |
|-----------|------|
| `GameViewModel` | 主 ViewModel，桥接 UI 与引擎，持有 DialogStateManager |
| `BaseViewModel` | 基类，提供 showError/showSuccess/showInfo/withLoading |
| `SaveLoadViewModel` | 存档加载 ViewModel |
| `WorldMapViewModel` | 世界地图 ViewModel |
| `DiscipleViewModel` | 弟子管理 ViewModel |
| `ProductionViewModel` | 生产系统 ViewModel |
| `HerbGardenViewModel` | 药园 ViewModel |
| `ForgeViewModel` | 锻造 ViewModel |
| `SpiritMineViewModel` | 灵矿 ViewModel |
| `SectViewModel` | 宗门管理 ViewModel |
| `AlchemyViewModel` | 炼丹 ViewModel |
| `BattleViewModel` | 战斗 ViewModel |

#### 4.6.3 游戏界面 (`ui/game/`)

| 界面 | 职责 |
|------|------|
| `MainGameScreen` | 主游戏界面，Tab 布局 + 对话框覆盖 |
| `LoadingScreen` | 加载界面 |
| `tabs/BuildingsTab` | 建筑标签页 |
| `tabs/DisciplesTab` | 弟子标签页 |
| `tabs/WarehouseTab` | 仓库标签页 |
| `tabs/SettingsTab` | 设置标签页 |
| `AlchemyScreen` | 炼丹界面 |
| `ForgeScreen` | 锻造界面 |
| `HerbGardenScreen` | 药园界面 |
| `LibraryScreen` | 藏经阁界面 |
| `SpiritMineScreen` | 灵矿界面 |
| `TianshuHallScreen` | 天枢堂界面 |
| `LawEnforcementHallScreen` | 执法堂界面 |
| `MissionHallScreen` | 任务堂界面 |
| `WenDaoPeakScreen` | 问道峰界面 |
| `QingyunPeakScreen` | 青云峰界面 |
| `ReflectionCliffScreen` | 面壁崖界面 |
| `DiscipleDetailScreen` | 弟子详情界面 |
| `InventoryScreen` | 物品栏界面 |
| `EventLogScreen` | 事件日志界面 |
| `MerchantScreen` | 商人界面 |
| `RecruitScreen` | 招募界面 |
| `SalaryConfigScreen` | 月俸配置界面 |

#### 4.6.4 世界地图 (`ui/game/map/`)

| 组件 | 职责 |
|------|------|
| `WorldMapScreen` | 世界地图主界面 |
| `MapCanvas` | Compose Canvas 绘制地图 |
| `MapCameraState` | 地图相机状态（缩放/平移） |
| `MapControls` | 地图控制按钮 |
| `MapStyle` | 地图样式配置 |
| `MapItem` / `MapItemMapper` | 地图元素映射 |
| `markers/SectMarker` | 宗门标记 |
| `markers/CaveMarker` | 洞府标记 |
| `markers/BattleMarker` | 战斗标记 |
| `markers/ScoutTeamMarker` | 侦察队标记 |

#### 4.6.5 UI 状态管理

| 类 | 职责 |
|----|------|
| `DialogStateManager` | 对话框状态管理，单例，管理当前打开的对话框类型和参数 |
| `DialogType` | 对话框类型密封类，定义 30+ 种对话框类型 |

### 4.7 依赖注入层 (`di/`)

| Module | 职责 |
|--------|------|
| `AppModule` | 提供 SessionManager, GameDatabase, 所有 DAO, CacheConfig, GameDataCacheManager, ChangeTracker |
| `CoreModule` | 提供 SystemManager 及其所有依赖的 GameSystem 集合 |
| `RepositoryModule` | 提供 ProductionSlotDao, ProductionTransactionManager |
| `StorageModule` | 提供存储子系统：StorageEngine, WALProvider, SlotLockManager, DynamicMemoryManager, SerializationModule, MetadataManager, BackupManager, DataCompressor, DataArchiver, RecoveryManager, KeyRotationManager 等 |
| `ApplicationScopeProvider` | 提供应用级 CoroutineScope |

### 4.8 事件系统 (`core/event/`)

| 类 | 职责 |
|----|------|
| `EventBus` | 事件总线，Channel 缓冲容量 256，支持发布/订阅模式，通知历史记录 |
| `DomainEvent` | 领域事件接口 |
| `CultivationEvent` | 修炼事件 |
| `BreakthroughEvent` | 突破事件 |
| `CombatEvent` | 战斗事件 |
| `DeathEvent` | 死亡事件 |
| `ItemEvent` | 物品事件 |
| `SectEvent` | 宗门事件 |
| `TimeEvent` | 时间事件 |
| `SaveEvent` | 存档事件 |
| `ErrorEvent` | 错误事件 |
| `NotificationEvent` | 通知事件 |
| `DiscipleUpdatedEvent` | 弟子更新事件 |
| `CultivationProgressEvent` | 修炼进度事件 |
| `ItemCraftedEvent` | 物品制作事件 |
| `BattleCompletedEvent` | 战斗完成事件 |

### 4.9 网络层 (`network/`)

| 类 | 职责 |
|----|------|
| `SecureHttpClient` | 安全 HTTP 客户端，配置证书锁定和超时 |
| `RequestSigner` | 请求签名器 |
| `CertificatePinnerProvider` | 证书锁定提供者 |
| `NetworkSecurityConfig` | 网络安全配置 |
| `NetworkUtils` | 网络工具 |

### 4.10 TapTap SDK 集成 (`taptap/`)

| 类 | 职责 |
|----|------|
| `TapTapAuthManager` | TapTap 登录认证管理 |
| `TapDBManager` | TapTap 数据分析 |
| `ComplianceManager` | 合规管理（防沉迷） |
| `LoginData` | 登录数据 (Java) |

---

## 5. 关键类与函数说明

### 5.1 GameEngineCore

```kotlin
@Singleton
class GameEngineCore @Inject constructor(
    stateStore: GameStateStore,
    stateManager: UnifiedGameStateManager,
    eventBus: EventBus,
    unifiedPerformanceMonitor: UnifiedPerformanceMonitor,
    systemManager: SystemManager,
    applicationScopeProvider: ApplicationScopeProvider
)
```

**核心方法**:

| 方法 | 说明 |
|------|------|
| `initialize()` | 初始化所有系统 |
| `startGameLoop()` | 启动游戏循环，200ms tick |
| `stopGameLoop()` | 停止游戏循环 |
| `stopGameLoopAndWait(timeoutMs)` | 停止并等待完成 |
| `shutdown()` | 关闭引擎，释放所有资源 |
| `pause()` / `resume()` | 暂停/恢复游戏 |
| `pauseForBackground()` | 后台暂停 |
| `tick()` | 单次 tick 执行 |
| `createSnapshot()` | 创建游戏状态快照 |
| `loadSnapshot(snapshot)` | 从快照恢复状态 |
| `forceResetStuckStates()` | 强制重置卡住的 isSaving/isLoading |

**tick 逻辑**:
1. 检查 `isPaused`/`isLoading`/`isSaving`，若为 true 则跳过（看门狗检测超时）
2. 递增 tickCount
3. 调用 `stateStore.update {}` 开启事务
4. 在事务内调用 `systemManager.onSecondTick(state)`
5. 累积 dayAccumulator，满 1 天时调用 `onDayTick`
6. 检测月份/年份变化，调用 `onMonthTick`/`onYearTick`
7. 按配置触发自动存档

### 5.2 GameEngine

```kotlin
@Singleton
class GameEngine @Inject constructor(
    gameEngineCore: GameEngineCore,
    stateStore: GameStateStore,
    inventorySystem: InventorySystem,
    battleSystem: BattleSystem,
    productionCoordinator: ProductionCoordinator,
    eventService: EventService,
    discipleService: DiscipleService,
    combatService: CombatService,
    explorationService: ExplorationService,
    buildingService: BuildingService,
    saveService: SaveService,
    cultivationService: CultivationService,
    diplomacyService: DiplomacyService,
    redeemCodeService: RedeemCodeService,
    formulaService: FormulaService
)
```

**职责**: 作为所有业务逻辑的门面，ViewModel 通过 GameEngine 调用业务方法，不直接访问 Service。

**关键属性**: 暴露 `stateStore` 的所有 StateFlow 投影（gameData, disciples, equipmentStacks 等）。

### 5.3 GameStateStore

```kotlin
@Singleton
class GameStateStore @Inject constructor(
    applicationScopeProvider: ApplicationScopeProvider
)
```

**核心机制**:
- 持有 `MutableStateFlow<UnifiedGameState>` 作为唯一状态源
- 通过 `update {}` 方法提供 Mutex 保护的事务性更新
- 事务内使用 `MutableGameState` 可变对象避免频繁创建不可变副本
- 事务结束时构建新的 `UnifiedGameState` 并发布
- 各子状态通过 `.map {}.distinctUntilChanged().stateIn()` 派生为独立 StateFlow

### 5.4 GameSystem 接口

```kotlin
interface GameSystem {
    val systemName: String
    fun initialize() {}
    fun release() {}
    suspend fun clear() {}
    suspend fun clearForSlot(slotId: Int) { clear() }
    suspend fun onSecondTick(state: MutableGameState) {}
    suspend fun onDayTick(state: MutableGameState) {}
    suspend fun onMonthTick(state: MutableGameState) {}
    suspend fun onYearTick(state: MutableGameState) {}
}
```

**调度机制**: `SystemManager` 通过 `@SystemPriority(order)` 注解确定执行顺序，默认优先级 500。

### 5.5 StorageFacade

存储系统唯一外部 API，封装以下操作：
- `saveGame(slotId, gameData, ...)` — 保存游戏
- `loadGame(slotId)` — 加载游戏
- `deleteGame(slotId)` — 删除存档
- `healthCheck()` — 存储健康检查
- `getStorageStats()` — 存储统计
- `shutdown()` — 关闭存储子系统

---

## 6. 依赖关系

### 6.1 主要第三方依赖

| 类别 | 依赖 | 版本 |
|------|------|------|
| UI 框架 | Jetpack Compose (BOM) | 2025.02.00 |
| UI 组件 | Material3 | 1.3.1 |
| 导航 | Navigation Compose | 2.8.5 |
| 数据库 | Room | 2.6.1 |
| DI | Hilt | 2.56 |
| 序列化 | Kotlinx Serialization (JSON/Protobuf/CBOR) | 1.6.3 |
| 网络 | Retrofit + OkHttp | 2.11.0 / 4.12.0 |
| KV 存储 | MMKV | 2.3.0 |
| 偏好存储 | DataStore Preferences | 1.1.3 |
| 压缩 | LZ4 Java / Zstd JNI | 1.8.0 / 1.5.6-6 |
| Protobuf | Protobuf Java | 4.27.2 |
| 认证 | TapTap SDK | 4.10.0 |
| 安全 | AndroidX Security Crypto | 1.1.0-alpha06 |
| JSON | Gson | 2.12.1 |
| 协程 | Kotlinx Coroutines Android | 1.8.1 |
| 测试 | JUnit / Mockito / Robolectric | 4.13.2 / 5.14.2 / 4.13 |

### 6.2 构建工具

| 工具 | 版本 |
|------|------|
| Android Gradle Plugin | 8.8.0 |
| Kotlin | 2.0.21 |
| KSP | 2.0.21-1.0.28 |
| Compose Compiler | 1.5.8 (via plugin) |
| Protobuf Gradle Plugin | 0.9.4 |

### 6.3 模块间依赖关系

```
UI (ViewModel/Compose)
  ↓ 依赖
GameEngine (门面)
  ↓ 依赖
GameEngineCore ← SystemManager ← GameSystem 实现 (Service/System/Subsystem)
  ↓ 依赖
GameStateStore ← UnifiedGameStateManager
  ↓ 依赖
StorageFacade → StorageEngine → Room DB + WAL + Cache + Compression + Crypto
  ↑ 依赖
DI (Hilt Modules) — 贯穿所有层
```

---

## 7. 项目运行方式

### 7.1 环境要求

- Android SDK: compileSdk 35, minSdk 24, targetSdk 35
- JDK 17
- Kotlin 2.0.21
- Gradle (使用项目自带 wrapper)

### 7.2 构建命令

所有命令在 `android/` 目录下执行：

```bash
# 编译检查（快速反馈）
cd android && ./gradlew.bat compileReleaseKotlin

# 构建 Release APK
cd android && ./gradlew.bat assembleRelease

# 构建 Debug APK
cd android && ./gradlew.bat assembleDebug

# 运行单元测试
cd android && ./gradlew.bat test

# 运行单个测试类
cd android && ./gradlew.bat test --tests "com.xianxia.sect.core.engine.BattleSystemTest"

# Lint 检查
cd android && ./gradlew.bat lintRelease

# 清理构建
cd android && ./gradlew.bat clean
```

### 7.3 签名配置

- 需要在 `android/` 目录下创建 `keystore.properties` 文件（参考 `keystore.properties.example`）
- 密钥库文件默认路径：`../../../keystore/keystore.jks`
- 若密钥库不存在，Release 构建将不带签名

### 7.4 API 配置

- 需要在 `android/` 目录下创建 `api.properties` 文件（参考 `api.properties.example`）
- 配置项：`API_BASE_URL`, `TAPTAP_CLIENT_ID`, `TAPTAP_CLIENT_TOKEN`, `TAPTAP_IS_CN`, `TAPDB_CHANNEL`, `APK_SIGNATURE_HASH`

### 7.5 应用启动流程

```
1. XianxiaApplication.onCreate()
   ├── SaveCrypto.initialize()         # 加密初始化
   ├── GameMonitorManager.initialize() # 性能监控初始化
   ├── ManualDatabase.initializeSync() # 功法数据库加载
   └── RecoveryManager.startupRecovery() # 崩溃恢复检查

2. MainActivity (LAUNCHER)
   ├── PrivacyConsentScreen            # 隐私政策同意
   └── SaveSelectScreen               # 存档选择

3. GameActivity
   ├── GameViewModel 初始化            # 注入 GameEngine + DialogStateManager
   ├── GameEngineCore.initialize()     # 初始化所有系统
   ├── GameEngineCore.startGameLoop()  # 启动游戏循环
   └── MainGameScreen                  # 渲染游戏界面
```

---

## 8. 开发规范

### 8.1 数据库迁移

修改任何 `@Entity` 类前必须阅读 `rules/database-migration.md`。核心规则：
- 不可删除列，必须通过 Migration 操作
- 不确定时保留旧字段，用 `@Ignore` 标注新字段
- 当前数据库版本：24

### 8.2 版本发布

- `versionCode` 每次递增 1
- `versionName` 三段式 `x.x.xx`，末段零填充（如 `2.6.09` → `2.6.10`）
- 详见 `rules/version-release.md`

### 8.3 更新日志

每次功能/修复完成后必须更新：
1. `ChangelogData.kt` — 游戏内更新日志
2. 根目录 `CHANGELOG.md` — 外部更新日志

### 8.4 文本颜色规范

游戏内所有文本必须使用 `Color.Black`，禁止灰色/白色/彩色文字。

### 8.5 编码规范

- 所有 Java/Kotlin 编译强制 UTF-8
- 使用阿里云 Maven 镜像
- ViewModel 继承 `BaseViewModel`
- 状态变更通过 `GameEngine` 方法，UI 层不直接修改 `GameStateStore`

---

## 9. 邮件系统 (`v3.1.96`)

### 9.1 架构

```
MailService (GameSystem, @SystemPriority 960)
  ├── resetAndInitSlot()  ← 清空+重建（Mutex 原子操作）
  ├── claimAttachment()   ← 单封领取（容量检查→发放→写入 GameData.claimedMailIds）
  ├── markAllAsRead()      ← 一键已读（含自动领取）
  ├── loadBuiltinMails()  ← 内置邮件加载（含 deadlineMs 限时检查）
  ├── fetchOnlineMails()  ← 在线邮件拉取（启动+onMonthTick 轮询）
  └── cleanExpired()      ← 30天过期清理

数据流: MailEntity (Room mails表) → MailService.activeMails (StateFlow) → ViewModel → Compose UI
领取状态: GameData.claimedMailIds (List<String>) 随存档序列化，读档即还原
```

### 9.2 文件清单

| 文件 | 说明 |
|------|------|
| `core/model/MailEntity.kt` | Room Entity + MailAttachment 数据类 |
| `data/local/MailDao.kt` | DAO（含 insertWithEnforceLimit/markAllAsRead 等） |
| `core/engine/service/MailService.kt` | 核心服务（GameSystem 实现） |
| `core/config/BuiltinMailConfig.kt` | 内置邮件配置（含 deadlineMs 限时） |
| `ui/game/dialogs/MailDialog.kt` | 邮件对话框（4:6 分栏） |
| `ui/game/components/GameActionButtons.kt` | 邮件入口按钮+红点 |

### 9.3 关键设计决策

| 决策 | 原因 |
|------|------|
| 领取状态存 `GameData` 而非独立审计表 | 单机游戏，邮件状态应随存档走 |
| `resetAndInitSlot` 原子操作 | 避免 clear+init 分步导致的 StateFlow 竞态 |
| StateFlow 主动推送（不用 flatMapLatest） | 避免 slotId 快速变化时订阅失效 |
| `deadlineMs` 绝对时间戳 | 发布日起 N 天后新老玩家均不再发放 |

---

## 10. 后续优化项

- [ ] 邮件全文搜索（标题/正文关键词）
- [ ] 在线邮件推送通知（FCM/本地通知）
- [ ] 邮件附件预览（领取前确认窗口）
- [ ] 弟子阵亡/建筑摧毁等游戏事件自动生成邮件
- [ ] `CODE_WIKI.md` 版本号与实际版本同步更新机制
- [ ] 恢复 `claimed_mail_records` 审计表（联机模式时防刷）
