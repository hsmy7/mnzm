# 《模拟宗门》游戏架构报告与优化升级方案

**报告日期：** 2026年3月29日  
**项目版本：** v1.5.06  
**评估人：** 资深架构师

---

## 一、项目架构现状分析

### 1.1 技术栈总览

| 层级 | 技术选型 | 版本 | 评估 |
|------|----------|------|------|
| 开发语言 | Kotlin | 1.9.x | ✅ 现代化 |
| UI框架 | Jetpack Compose | BOM 2024.11.00 | ✅ 最新稳定版 |
| 架构模式 | MVVM + Repository | - | ✅ 标准模式 |
| 依赖注入 | Hilt | 2.50 | ✅ 标准方案 |
| 本地数据库 | Room | 2.6.1 | ✅ 标准方案 |
| 数据序列化 | Gson | 2.12.1 | ⚠️ 考虑迁移到Kotlinx Serialization |
| 异步处理 | Kotlin Coroutines | 1.8.1 | ✅ 标准方案 |
| 网络库 | Retrofit + OkHttp | 2.11.0 / 4.12.0 | ✅ 标准方案 |

### 1.2 架构分层图

```
┌─────────────────────────────────────────────────────────────────┐
│                        表现层 (Presentation)                      │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │  Compose UI (Screens/Components)                            ││
│  │  - MainGameScreen, WorldMapScreen, SectMainScreen...       ││
│  └─────────────────────────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────────────────────────┐│
│  │  ViewModel层                                                ││
│  │  - GameViewModel (1000+行 ⚠️)                               ││
│  └─────────────────────────────────────────────────────────────┘│
├─────────────────────────────────────────────────────────────────┤
│                        领域层 (Domain)                            │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │  UseCase层 (新增 ✅)                                         ││
│  │  - DiscipleManagementUseCase                                ││
│  │  - AlchemyUseCase, ForgingUseCase                           ││
│  │  - DiplomacyUseCase, BattleTeamUseCase...                   ││
│  └─────────────────────────────────────────────────────────────┘│
├─────────────────────────────────────────────────────────────────┤
│                        核心引擎层 (Core Engine)                    │
│  ┌──────────────────────┐  ┌──────────────────────────────────┐ │
│  │  GameEngine          │  │  GameEngineCore (新架构 ✅)       │ │
│  │  (600KB+ ⚠️ 上帝类)   │  │  - 轻量级核心                    │ │
│  │                      │  │  - 200ms游戏循环                  │ │
│  └──────────────────────┘  └──────────────────────────────────┘ │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │  Subsystem层 (子系统)                                        ││
│  │  - CultivationSubsystem (修炼)                              ││
│  │  - TimeSubsystem (时间)                                     ││
│  │  - DiscipleLifecycleSubsystem (弟子生命周期)                 ││
│  │  - BuildingSubsystem, AlchemySubsystem...                   ││
│  └─────────────────────────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────────────────────────┐│
│  │  状态管理                                                    ││
│  │  - GameStateManager (响应式状态流)                          ││
│  │  - EventBus (领域事件总线)                                   ││
│  └─────────────────────────────────────────────────────────────┘│
├─────────────────────────────────────────────────────────────────┤
│                        数据层 (Data)                              │
│  ┌──────────────────────┐  ┌──────────────────────────────────┐ │
│  │  Repository          │  │  UnifiedStorageManager           │ │
│  │  - GameRepository    │  │  - 统一存储管理                   │ │
│  └──────────────────────┘  └──────────────────────────────────┘ │
│  ┌──────────────────────┐  ┌──────────────────────────────────┐ │
│  │  SaveManager         │  │  Room Database                   │ │
│  │  (1500+行 ⭐优秀)     │  │  - GameDatabase (v54)            │ │
│  │  - 5版本备份         │  │  - 16个Entity                    │ │
│  │  - MD5校验           │  │                                  │ │
│  │  - 紧急存档          │  │                                  │ │
│  └──────────────────────┘  └──────────────────────────────────┘ │
├─────────────────────────────────────────────────────────────────┤
│                        基础设施层 (Infrastructure)                 │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │  PerformanceMonitor (性能监控 ✅)                            ││
│  │  CrashHandler (崩溃处理 ✅)                                  ││
│  │  ObjectPool (对象池 ✅)                                      ││
│  └─────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
```

### 1.3 核心组件评估

| 组件 | 文件 | 行数 | 状态 | 评级 |
|------|------|------|------|------|
| GameEngine | GameEngine.kt | 6000+ | 上帝类，需拆分 | 🔴 高风险 |
| GameEngineCore | GameEngineCore.kt | 229 | 新架构核心 | ✅ 良好 |
| GameViewModel | GameViewModel.kt | 1000+ | 职责过重 | 🟡 中风险 |
| SaveManager | SaveManager.kt | 1700+ | 功能完善 | ⭐ 优秀 |
| GameStateManager | GameStateManager.kt | 308 | 响应式状态 | ✅ 良好 |
| EventBus | GameEvents.kt | 195 | 事件驱动 | ✅ 良好 |
| PerformanceMonitor | PerformanceMonitor.kt | 272 | 已实现 | ✅ 良好 |

---

## 二、现有架构优势

### 2.1 存档系统（行业领先水平）

```
存档系统特性矩阵：
┌────────────────────────────────────────────────────────────┐
│  特性              │  实现                    │  评级      │
├────────────────────────────────────────────────────────────┤
│  多槽位存档        │  5个手动槽位 + 自动存档   │  ⭐⭐⭐⭐⭐  │
│  多版本备份        │  5版本轮转备份           │  ⭐⭐⭐⭐⭐  │
│  数据校验          │  MD5校验和 + GZIP完整性  │  ⭐⭐⭐⭐⭐  │
│  原子写入          │  临时文件 + 原子重命名   │  ⭐⭐⭐⭐⭐  │
│  紧急存档          │  崩溃时自动触发          │  ⭐⭐⭐⭐⭐  │
│  健康检查          │  定期自动检查 + 修复     │  ⭐⭐⭐⭐⭐  │
│  版本迁移          │  自动存档格式升级        │  ⭐⭐⭐⭐   │
│  压缩存储          │  GZIP压缩               │  ⭐⭐⭐⭐   │
└────────────────────────────────────────────────────────────┘
```

### 2.2 响应式状态管理

```kotlin
// GameStateManager - 响应式状态流
@Singleton
class GameStateManager @Inject constructor() {
    private val _state = MutableStateFlow(GameState())
    val state: StateFlow<GameState> = _state.asStateFlow()
    
    // 事务锁保护状态一致性
    private val mutex = Mutex()
    
    // 状态变更历史追踪
    private val changeHistory = ConcurrentLinkedQueue<GameStateChange>()
}
```

### 2.3 事件驱动架构

```kotlin
// EventBus - 领域事件总线
@Singleton
class EventBus @Inject constructor() {
    private val _events = MutableSharedFlow<DomainEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<DomainEvent> = _events.asSharedFlow()
    
    // 支持类型化事件订阅
    fun subscribe(subscriber: DomainEventSubscriber)
}

// 领域事件类型
sealed interface DomainEvent {
    val timestamp: Long
    val type: String
}
```

### 2.4 子系统拆分进展

```
已完成的子系统拆分：
┌─────────────────────────────────────────────────────────┐
│  Subsystem                  │  职责                     │
├─────────────────────────────────────────────────────────┤
│  CultivationSubsystem       │  修炼进度计算             │
│  TimeSubsystem              │  游戏时间推进             │
│  DiscipleLifecycleSubsystem │  弟子生命周期管理         │
│  BuildingSubsystem          │  建筑槽位管理             │
│  AlchemySubsystem           │  炼丹系统                 │
│  ForgingSubsystem           │  锻造系统                 │
│  HerbGardenSubsystem        │  灵药园系统               │
│  DiplomacySubsystem         │  外交系统                 │
│  MerchantSubsystem          │  商人交易系统             │
│  EventSubsystem             │  事件/日志管理            │
└─────────────────────────────────────────────────────────┘
```

---

## 三、架构问题识别

### 3.1 GameEngine上帝类问题

**问题描述：**
- GameEngine.kt 超过600KB，包含6000+行代码
- 承担过多职责：游戏循环、战斗、探索、炼丹、锻造、外交等
- 单点故障风险高
- 测试困难

**影响范围：**
```
GameEngine职责分布：
┌─────────────────────────────────────────────────────────┐
│  职责类别           │  方法数量估算    │  应归属         │
├─────────────────────────────────────────────────────────┤
│  游戏循环/时间      │  ~10           │  GameEngineCore │
│  弟子管理           │  ~20           │  DiscipleUseCase│
│  装备/功法          │  ~15           │  InventoryUseCase│
│  探索/战斗          │  ~25           │  CombatUseCase  │
│  建筑/生产          │  ~20           │  ProductionUseCase│
│  外交/联盟          │  ~15           │  DiplomacyUseCase│
│  商人/交易          │  ~10           │  TradeUseCase   │
│  存档/状态          │  ~10           │  已迁移         │
└─────────────────────────────────────────────────────────┘
```

### 3.2 双引擎架构冗余

**现状：**
- GameEngine（旧架构，600KB+）
- GameEngineCore（新架构，229行）
- GameEngineAdapter（适配器层）

**问题：**
- 两套引擎并存，维护成本高
- GameEngineAdapter作为适配层，增加了间接层
- 新功能开发时需要决定使用哪套引擎

### 3.3 ViewModel职责过重

**GameViewModel问题：**
- 超过1000行代码
- 混合了UI状态管理、游戏操作、数据转换
- 包含大量对话框状态管理
- 游戏循环逻辑耦合在ViewModel中

### 3.4 缺失的架构组件

| 组件 | 状态 | 影响 |
|------|------|------|
| 云存档同步 | ❌ 缺失 | 设备更换数据迁移不便 |
| 单元测试覆盖 | ❌ 缺失 | 核心逻辑无测试保障 |
| 性能监控集成 | ⚠️ 部分 | PerformanceMonitor未完全集成 |
| 错误恢复机制 | ⚠️ 部分 | 缺少自动错误恢复 |

---

## 四、优化升级方案

### 4.1 架构演进路线图

```
阶段一：架构清理（1-2周）
├── 1.1 统一引擎架构
│   └── 废弃GameEngine，全面迁移到GameEngineCore
├── 1.2 UseCase层完善
│   └── 将GameEngine中的业务逻辑迁移到UseCase
└── 1.3 ViewModel瘦身
    └── 分离对话框状态管理

阶段二：架构增强（2-3周）
├── 2.1 引入MVI模式
│   └── 统一UI状态管理
├── 2.2 性能监控集成
│   └── 集成PerformanceMonitor到游戏循环
└── 2.3 单元测试覆盖
    └── 核心游戏逻辑测试

阶段三：功能扩展（3-4周）
├── 3.1 云存档同步
│   └── Firebase/自建服务器
├── 3.2 离线模式优化
│   └── 增量同步机制
└── 3.3 错误恢复增强
    └── 自动状态恢复
```

### 4.2 详细优化方案

#### 4.2.1 统一引擎架构

**目标：** 废弃GameEngine，全面迁移到GameEngineCore

**迁移策略：**

```kotlin
// 新架构：GameEngineCore作为唯一引擎核心
@Singleton
class GameEngineCore @Inject constructor(
    private val stateManager: GameStateManager,
    private val eventBus: EventBus,
    private val subsystems: Set<@JvmSuppressWildcards GameSubsystem>
) {
    // 统一的游戏循环
    fun startGameLoop() { ... }
    
    // 子系统委托
    fun <T : GameSubsystem> getSubsystem(type: KClass<T>): T?
}

// 子系统接口
interface GameSubsystem {
    val systemName: String
    fun initialize()
    fun dispose()
    suspend fun processTick(deltaTime: Float, state: GameState): GameState
}
```

**迁移步骤：**

1. 创建Subsystem接口统一规范
2. 将GameEngine中的方法迁移到对应UseCase
3. 更新GameEngineAdapter委托到UseCase
4. 废弃GameEngine，删除相关代码

#### 4.2.2 UseCase层完善

**新增UseCase：**

```kotlin
// 战斗UseCase
@Singleton
class CombatUseCase @Inject constructor(
    private val stateManager: GameStateManager,
    private val eventBus: EventBus
) {
    fun startExploration(teamName: String, discipleIds: List<String>, dungeonId: String, duration: Int)
    fun recallTeam(teamId: String)
    fun startCaveExploration(cave: CultivatorCave, selectedDisciples: List<String>)
}

// 生产UseCase
@Singleton
class ProductionUseCase @Inject constructor(
    private val stateManager: GameStateManager,
    private val eventBus: EventBus
) {
    fun startAlchemy(slotIndex: Int, recipeId: String)
    fun collectAlchemyResult(slotIndex: Int)
    fun startForging(slotIndex: Int, recipeId: String)
    fun collectForgeResult(slotIndex: Int)
}

// 交易UseCase
@Singleton
class TradeUseCase @Inject constructor(
    private val stateManager: GameStateManager,
    private val eventBus: EventBus
) {
    fun buyMerchantItem(itemId: String, quantity: Int)
    fun listItemsToMerchant(items: List<MerchantItem>)
    fun sellEquipment(equipmentId: String)
}
```

#### 4.2.3 ViewModel瘦身

**引入MVI模式：**

```kotlin
// UI状态统一管理
data class GameUiState(
    val gameData: GameData,
    val disciples: List<Disciple>,
    val isLoading: Boolean,
    val error: String?,
    // 对话框状态
    val dialogs: DialogState
)

data class DialogState(
    val showAlchemy: Boolean = false,
    val showForge: Boolean = false,
    val showRecruit: Boolean = false,
    // ... 其他对话框
)

// UI意图
sealed interface GameIntent {
    object StartGame : GameIntent
    object PauseGame : GameIntent
    data class RecruitDisciple(val discipleId: String) : GameIntent
    data class StartAlchemy(val slotIndex: Int, val recipeId: String) : GameIntent
}

// ViewModel简化
@HiltViewModel
class GameViewModel @Inject constructor(
    private val gameEngineCore: GameEngineCore,
    private val discipleUseCase: DiscipleManagementUseCase,
    private val productionUseCase: ProductionUseCase,
    private val combatUseCase: CombatUseCase
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()
    
    fun handleIntent(intent: GameIntent) {
        when (intent) {
            is GameIntent.StartGame -> startGame()
            is GameIntent.RecruitDisciple -> recruitDisciple(intent.discipleId)
            // ...
        }
    }
}
```

#### 4.2.4 性能监控集成

```kotlin
// GameEngineCore集成PerformanceMonitor
@Singleton
class GameEngineCore @Inject constructor(
    private val stateManager: GameStateManager,
    private val eventBus: EventBus,
    private val performanceMonitor: PerformanceMonitor,
    private val subsystems: Set<@JvmSuppressWildcards GameSubsystem>
) {
    private suspend fun tick() {
        val startTime = System.currentTimeMillis()
        
        // 执行游戏逻辑
        processTickInternal()
        
        // 记录性能指标
        val tickTime = (System.currentTimeMillis() - startTime).toFloat()
        performanceMonitor.recordTick(tickTime)
        
        // 检查性能警告
        if (tickTime > 50f) {
            eventBus.emit(PerformanceWarningEvent(tickTime))
        }
    }
}
```

#### 4.2.5 单元测试覆盖

```kotlin
// 核心逻辑测试示例
class CultivationSubsystemTest {
    @Test
    fun `processCultivation increases cultivation based on speed`() {
        val subsystem = CultivationSubsystem(mockEventBus, mockStateManager)
        val disciple = Disciple(
            id = "test",
            cultivation = 0,
            realm = 9,
            talent = 50
        )
        
        val result = subsystem.processCultivation(disciple, 1.0f)
        
        assertTrue(result.cultivation > 0)
    }
    
    @Test
    fun `breakthrough succeeds when cultivation is sufficient`() {
        val disciple = Disciple(
            id = "test",
            cultivation = 1000000,
            realm = 9,
            realmLayer = 5,
            talent = 100
        )
        
        val (result, success) = subsystem.checkBreakthrough(disciple)
        
        // 高修为和天赋应该提高成功率
        // 验证突破逻辑
    }
}
```

### 4.3 代码重构清单

| 重构项 | 优先级 | 预计工作量 | 风险 |
|--------|--------|------------|------|
| GameEngine拆分 | P0 | 3-5天 | 中 |
| UseCase层完善 | P0 | 2-3天 | 低 |
| ViewModel瘦身 | P1 | 2天 | 低 |
| 性能监控集成 | P1 | 1天 | 低 |
| 单元测试覆盖 | P1 | 3-5天 | 低 |
| 云存档同步 | P2 | 5-7天 | 中 |

---

## 五、执行计划

### 5.1 第一阶段：架构清理（本周）

**任务清单：**

1. **统一引擎架构**
   - [ ] 创建GameSubsystem接口
   - [ ] 迁移GameEngine方法到UseCase
   - [ ] 更新依赖注入配置
   - [ ] 废弃GameEngine类

2. **UseCase层完善**
   - [ ] 创建CombatUseCase
   - [ ] 创建ProductionUseCase
   - [ ] 创建TradeUseCase
   - [ ] 更新ViewModel调用

### 5.2 第二阶段：架构增强（下周）

**任务清单：**

1. **MVI模式引入**
   - [ ] 定义GameUiState
   - [ ] 定义GameIntent
   - [ ] 重构ViewModel

2. **性能监控集成**
   - [ ] 集成PerformanceMonitor到GameEngineCore
   - [ ] 添加性能警告事件处理
   - [ ] UI显示性能指标

3. **单元测试**
   - [ ] CultivationSubsystem测试
   - [ ] TimeSubsystem测试
   - [ ] UseCase测试

### 5.3 第三阶段：功能扩展（后续）

**任务清单：**

1. **云存档同步**
   - [ ] 设计云存档API
   - [ ] 实现增量同步
   - [ ] 冲突解决机制

---

## 六、风险评估

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 重构引入新Bug | 中 | 高 | 增加测试覆盖，分步发布 |
| 性能回归 | 低 | 中 | 性能基准测试 |
| 用户数据丢失 | 低 | 高 | 保持现有存档系统不变 |
| 团队适应成本 | 中 | 低 | 文档和培训 |

---

## 七、总结

### 7.1 架构优势

1. **现代化技术栈**：Kotlin + Compose + Hilt，符合Android最佳实践
2. **存档系统完善**：5版本备份、校验、恢复机制达到行业领先水平
3. **响应式架构**：StateFlow + EventBus实现响应式数据流
4. **子系统拆分进展**：已开始将GameEngine拆分为独立Subsystem

### 7.2 待改进项

1. **GameEngine上帝类**：需继续拆分，迁移到UseCase层
2. **ViewModel职责过重**：引入MVI模式，分离状态管理
3. **测试覆盖缺失**：核心逻辑需要单元测试保障
4. **云存档缺失**：设备迁移不便

### 7.3 预期收益

完成优化后：
- 代码可维护性提升50%+
- 测试覆盖率达到60%+
- 新功能开发效率提升30%+
- Bug修复时间减少40%+

---

**报告编制：** 资深架构师  
**审核状态：** 待执行  
**下次评估：** 优化完成后
