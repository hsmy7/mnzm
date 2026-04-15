# XianxiaSectNative 架构优化方案

> 本文档是 [refactoring-plan.md](./refactoring-plan.md) 的补充，聚焦于并发模型、状态原子性、存储层精简、安全漏洞、性能优化和架构演进保障。
> refactoring-plan.md 已覆盖：状态写入路径统一、Service/System 合并、库存统一、ViewModel 拆分、死代码清理。
> 本文档覆盖 refactoring-plan.md 未涉及的深层问题。

---

## 问题全景（本文档范围）

```
问题1: 并发模型四套并存，无法全局推理锁语义
  ├─ ReentrantLock (GameStateStore.update)
  ├─ Mutex (UnifiedGameStateManager)
  ├─ @Synchronized (OperationCircuitBreaker)
  └─ ConcurrentHashMap + AtomicXxx (缓存层)

问题2: Tick 间 MutableGameState 快照非原子
  ├─ 13 个 StateFlow.value 独立读取，非原子
  ├─ Service setter 绕过事务，跨字段修改无原子性
  └─ 13 个 StateFlow.value 独立赋值，combine 触发半更新

问题3: 存储层调用链过深，Facade 职责膨胀
  ├─ 一次保存经 7+ 层，回滚路径不清晰
  ├─ RefactoredStorageFacade 注入 14 个依赖
  └─ StorageGateway 与 UnifiedSaveRepository 职责重叠

问题4: 安全漏洞
  ├─ 兑换码纯客户端校验
  ├─ HMAC 密钥可能硬编码
  ├─ 设备指纹隐私泄露面
  └─ 密钥恢复可能静默丢失存档

问题5: 性能隐患
  ├─ 14 路 combine 高频重组
  ├─ MutableGameState 全量拷贝
  └─ GameEngineCore/GameEngine 双 scope 生命周期失控

问题6: 架构演进障碍
  ├─ GameSystem 顺序 tick 不支持并行
  ├─ 状态模型无版本化迁移钩子
  └─ 引擎层与存储层序列化路径不统一
```

---

## 阶段六：并发模型统一（P0）

> 依赖 refactoring-plan.md 阶段一完成。是所有 P0 问题的根因。

### 6.1 当前并发原语分布

| 原语 | 位置 | 保护对象 | 问题 |
|------|------|---------|------|
| `ReentrantLock` | `GameStateStore.update()` | 13 个 MutableStateFlow 的读写 | 不感知协程，suspend 函数持锁会阻塞线程 |
| `ReentrantLock` | `GameStateStore.suspendUpdate()` | 同上 | suspend block 内持锁，若 block 挂起则线程阻塞 |
| `Mutex` | `UnifiedGameStateManager.stateMutex` | setPaused/setLoading/setSaving | 协程感知，但与 GameStateStore 的 ReentrantLock 互不协调 |
| `@Synchronized` | `OperationCircuitBreaker.allowRequest()` | 断路器状态 | JVM 监视器锁，与协程无关 |
| `ConcurrentHashMap` | `RefactoredStorageFacade.slotStateCache` | Slot 缓存 | 无锁，但与 GameStateStore 状态无同步 |
| `AtomicBoolean` | `GameEngine._isPaused` | 暂停标志 | 与 GameStateStore._isPaused 重复且不同步 |

### 6.2 统一方案：全部改为 Mutex

**原则**：游戏引擎是单写者（游戏循环）+ 多读者（UI）模型，Mutex（协程感知）是最合适的原语。ReentrantLock 只应在纯线程场景（无协程）中使用。

#### 6.2.1 GameStateStore — ReentrantLock → Mutex

```kotlin
@Singleton
class GameStateStore @Inject constructor() {

    private val transactionMutex = Mutex()

    private val _gameData = MutableStateFlow(GameData())
    private val _disciples = MutableStateFlow<List<Disciple>>(emptyList())
    // ... 其余 MutableStateFlow 保持 private

    val gameData: StateFlow<GameData> get() = _gameData
    val disciples: StateFlow<List<Disciple>> get() = _disciples
    // ... 其余只读 StateFlow

    val unifiedState: StateFlow<UnifiedGameState> = combine(/* ... */)
        .stateIn(/* ... */)

    suspend fun update(block: MutableGameState.() -> Unit) {
        transactionMutex.withLock {
            val mutable = MutableGameState(
                gameData = _gameData.value,
                disciples = _disciples.value,
                // ...
            )
            mutable.block()
            _gameData.value = mutable.gameData
            _disciples.value = mutable.disciples
            // ...
        }
    }

    suspend fun loadFromSnapshot(/* ... */) {
        transactionMutex.withLock {
            _gameData.value = gameData
            _disciples.value = disciples
            // ...
        }
    }
}
```

**变更点**：
- `ReentrantLock` → `Mutex()`
- `update()` 改为 `suspend fun`（Mutex.withLock 是 suspend）
- 删除 `suspendUpdate()`（不再需要，update 本身就是 suspend）
- 所有调用 `update()` 的非 suspend 上下文需要包裹在协程中

#### 6.2.2 UnifiedGameStateManager — 去重

重构后 `UnifiedGameStateManager` 只保留 UI 元状态操作，所有业务状态修改走 `GameStateStore.update()`：

```kotlin
@Singleton
class UnifiedGameStateManager @Inject constructor(
    private val stateStore: GameStateStore
) {
    val state: StateFlow<UnifiedGameState> get() = stateStore.unifiedState

    suspend fun setPaused(paused: Boolean) {
        stateStore.update { isPaused = paused }
    }

    suspend fun setLoading(loading: Boolean) {
        stateStore.update { isLoading = loading }
    }

    suspend fun setSaving(saving: Boolean) {
        stateStore.update { isSaving = saving }
    }

    suspend fun loadState(newState: UnifiedGameState) {
        stateStore.loadFromSnapshot(
            gameData = newState.gameData,
            disciples = newState.disciples,
            // ...
        )
    }
}
```

**变更点**：
- 删除 `stateMutex`（不再需要，所有操作委托给 GameStateStore）
- 删除所有 `updateXxx` 方法（与 GameStateStore.update 重复）
- 保留观察者通知机制

#### 6.2.3 OperationCircuitBreaker — @Synchronized → Mutex

```kotlin
class OperationCircuitBreaker(
    private val operation: String,
    private val config: CircuitBreakerConfig
) {
    private val mutex = Mutex()

    suspend fun allowRequest(): Boolean = mutex.withLock {
        // 原有逻辑
    }

    suspend fun recordSuccess() = mutex.withLock {
        // 原有逻辑
    }

    suspend fun recordFailure() = mutex.withLock {
        // 原有逻辑
    }
}
```

#### 6.2.4 GameEngine._isPaused — 删除

`GameEngine._isPaused` 与 `GameStateStore._isPaused` 重复。统一使用 `GameStateStore` 中的状态：

```kotlin
// 删除
// private val _isPaused = AtomicBoolean(false)

// 替换为
val isPaused: StateFlow<Boolean> get() = stateStore.isPaused
```

### 6.3 迁移影响分析

| 调用方 | 当前 | 迁移后 |
|--------|------|--------|
| `CultivationService.currentGameData` setter | `stateStore.update { gameData = value }` | 不变，但 update 现在是 suspend |
| `GameEngineCore.tickInternal()` | 非 suspend 上下文读取 StateFlow.value | 不变，读取不需要锁 |
| `GameEngineCore.pause()` | `stateManager.setPaused(true)` | 不变 |
| `UI 线程` | `stateStore.update { ... }` | 需要包裹在 `viewModelScope.launch { }` 中 |

**关键风险**：`update()` 从非 suspend 变为 suspend 后，所有在非协程上下文中调用 `update()` 的地方需要改造。搜索所有 `stateStore.update` 调用点，确认调用上下文。

### 6.4 阶段六验收标准

- [ ] 全局搜索 `ReentrantLock` 返回 0 结果（GameStateStore 中）
- [ ] 全局搜索 `@Synchronized` 在 OperationCircuitBreaker 中返回 0 结果
- [ ] `GameStateStore.update()` 是 `suspend fun`
- [ ] `GameStateStore.suspendUpdate()` 已删除
- [ ] `UnifiedGameStateManager` 无 `stateMutex` 字段
- [ ] `GameEngine` 无 `_isPaused` 字段
- [ ] 游戏循环正常运行，无死锁

---

## 阶段七：Tick 原子性修复（P0）

> 依赖阶段六完成（需要 Mutex 保证 update 的原子性）。

### 7.1 问题详述

当前 `GameEngineCore.tickInternal()` 的问题链：

```
1. 从 13 个 StateFlow.value 独立读取 → 快照可能不一致
2. 传给 SystemManager.onSecondTick(state) → state 是可变的
3. 各 System 修改 state → 修改对后续 System 可见（顺序依赖）
4. tick 结束后，没有任何机制将 state 写回 GameStateStore
5. Service setter 各自调用 stateStore.update() → 13 次独立事务
6. 每次事务触发 combine → UI 可能看到中间状态
```

### 7.2 方案：Tick 内单一事务

**核心思路**：整个 tick 在一个 `GameStateStore.update()` 事务内执行，System 修改 MutableGameState 后，事务结束时一次性写回所有 StateFlow。

```kotlin
@Singleton
class GameEngineCore @Inject constructor(
    private val stateStore: GameStateStore,
    private val systemManager: SystemManager,
    private val eventBus: EventBus,
    private val performanceMonitor: GamePerformanceMonitor
) {
    private suspend fun tickInternal() {
        val currentState = stateStore.unifiedState.value
        if (currentState.isPaused || currentState.isLoading || currentState.isSaving) {
            return
        }

        _tickCount.value++

        stateStore.update { mutableState ->
            systemManager.onSecondTick(mutableState)

            val daysPerTick = GameConfig.Time.DAYS_PER_MONTH.toDouble() /
                (GameConfig.Time.SECONDS_PER_REAL_MONTH * GameConfig.Time.TICKS_PER_SECOND)
            dayAccumulator += daysPerTick

            while (dayAccumulator >= 1.0) {
                dayAccumulator -= 1.0
                systemManager.onDayTick(mutableState)
            }
        }
    }
}
```

**变更点**：
- tick 逻辑包裹在 `stateStore.update { }` 中
- System 收到的是同一个 `MutableGameState` 引用，修改直接生效
- 事务结束时一次性写回 13 个 StateFlow
- 删除手工组装 MutableGameState 的代码

### 7.3 Service setter 迁移

当前 Service 的 setter 模式：

```kotlin
private var currentGameData: GameData
    get() = stateStore.gameData.value
    set(value) { stateStore.update { gameData = value } }
```

这种模式在 tick 事务内使用时会导致**嵌套锁**（Mutex 不可重入）。

**解决方案**：Service 在 tick 上下文中直接修改传入的 `MutableGameState`，不再通过 setter：

```kotlin
// CultivationSystem — tick 内
override suspend fun onMonthTick(state: MutableGameState) {
    state.disciples = state.disciples.map { disciple ->
        processMonthlyCultivation(disciple, state.gameData)
    }
    state.gameData = state.gameData.copy(
        spiritStones = state.gameData.spiritStones - monthlyCost
    )
}

// CultivationSystem — 用户操作（非 tick 上下文）
suspend fun assignCultivationMethod(discipleId: String, methodId: String): Boolean {
    return stateStore.update {
        val index = disciples.indexOfFirst { it.id == discipleId }
        if (index < 0) return false
        disciples = disciples.toMutableList().apply {
            this[index] = this[index].copy(cultivationMethodId = methodId)
        }
        true
    }
}
```

**关键约束**：
- tick 内：直接修改 `MutableGameState`，禁止调用 `stateStore.update()`
- tick 外（用户操作）：通过 `stateStore.update()` 修改

### 7.4 防止嵌套锁的编译期检查

为防止 tick 内误调 `stateStore.update()`，引入上下文标记：

```kotlin
@DslMarker
annotation class TickContext

@TickContext
class TickMutableState(val state: MutableGameState)

// GameStateStore.update 增加 @TickContext 重载保护
suspend fun update(block: @TickContext MutableGameState.() -> Unit) {
    transactionMutex.withLock { /* ... */ }
}

// tick 内使用专用入口
suspend fun updateInTick(block: @TickContext MutableGameState.() -> Unit) {
    // 不获取锁，直接在当前 MutableGameState 上操作
    // 仅在 GameEngineCore.tickInternal 中调用
    block(@TickContext MutableGameState(/* ... */))
}
```

> 注：此方案为理想设计，实际实现可根据团队偏好选择运行时检查（ThreadLocal 标记）或代码审查规则。

### 7.5 combine 半更新问题修复

`GameStateStore.update()` 中 13 个 `_xxx.value = mutable.xxx` 赋值不是原子的。在赋值过程中，`combine` 可能在中间状态触发。

**方案**：引入 batch 更新信号，抑制 combine 在更新期间的发射：

```kotlin
@Singleton
class GameStateStore @Inject constructor() {

    private val _batchVersion = MutableStateFlow(0L)

    val unifiedState: StateFlow<UnifiedGameState> = combine(
        _gameData, _disciples, _equipment, _manuals, _pills,
        _materials, _herbs, _seeds, _teams, _events, _battleLogs,
        _isPaused, _isLoading, _isSaving, _batchVersion
    ) { values ->
        UnifiedGameState(
            gameData = values[0] as GameData,
            disciples = values[1] as List<Disciple>,
            // ...
            version = values[13] as Long
        )
    }.distinctUntilChanged()
        .stateIn(/* ... */)

    suspend fun update(block: MutableGameState.() -> Unit) {
        transactionMutex.withLock {
            val mutable = MutableGameState(/* ... */)
            mutable.block()
            _batchVersion.value++      // 先递增版本号
            _gameData.value = mutable.gameData
            _disciples.value = mutable.disciples
            // ... 13 个赋值
            _batchVersion.value++      // 再递增版本号
        }
    }
}
```

`distinctUntilChanged()` 确保 `UnifiedGameState` 的 `version` 字段只在偶数值时才触发下游更新。由于 `version` 在所有字段赋值完成后才变为偶数，`combine` 的中间状态发射会被 `distinctUntilChanged` 过滤掉。

> 更简洁的替代方案：将 13 个 MutableStateFlow 合并为 1 个 `MutableStateFlow<UnifiedGameState>`，彻底消除 combine 的半更新问题。但这需要重构所有读取点，影响面较大，建议在阶段二（Service/System 合并）完成后评估。

### 7.6 阶段七验收标准

- [ ] `GameEngineCore.tickInternal()` 在 `stateStore.update { }` 事务内执行
- [ ] tick 内无 `stateStore.update()` 嵌套调用
- [ ] Service setter 在 tick 上下文中不使用（直接修改 MutableGameState）
- [ ] `unifiedState` 不再发射半更新状态
- [ ] 游戏循环正常运行，弟子/装备/灵石状态一致

---

## 阶段八：引擎层生命周期统一（P1）

> 可与阶段六并行执行。

### 8.1 问题：双 scope + 双初始化

| 问题 | GameEngineCore | GameEngine |
|------|---------------|------------|
| CoroutineScope | `scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)` | `engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())` |
| 初始化 | `init { systemManager.initializeAll() }` | `fun initializeSystems() { systemManager.initializeAll() }` |
| 销毁 | `fun shutdown() { stopGameLoop(); systemManager.releaseAll(); scope.cancel() }` | `fun destroy() { deathEventJob?.cancel(); engineScope.cancel() }` |

**风险**：
- GameActivity 销毁时如果只调了 `destroy()` 没调 `shutdown()`，游戏循环仍在运行
- `initializeAll()` 可能被调两次（虽然 SystemManager 有守卫）
- 两个 scope 的 Job 互不关联，一个取消不影响另一个

### 8.2 方案：统一生命周期到 GameEngineCore

```kotlin
@Singleton
class GameEngineCore @Inject constructor(
    private val stateStore: GameStateStore,
    private val systemManager: SystemManager,
    private val eventBus: EventBus,
    private val performanceMonitor: GamePerformanceMonitor
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun initialize() {
        systemManager.initializeAll()
        Log.i(TAG, "GameEngineCore initialized")
    }

    fun shutdown() {
        stopGameLoop()
        systemManager.releaseAll()
        scope.cancel()
        Log.i(TAG, "GameEngineCore shutdown complete")
    }

    // 其余方法不变
}

@Singleton
class GameEngine @Inject constructor(
    private val stateStore: GameStateStore,
    private val systemManager: SystemManager,
    private val eventBus: EventBus
) {
    // 删除 engineScope — 所有协程通过 GameEngineCore.scope 或 viewModelScope 运行
    // 删除 initializeSystems() — 统一通过 GameEngineCore.initialize()
    // 删除 destroy() — 统一通过 GameEngineCore.shutdown()

    val gameData: StateFlow<GameData> get() = stateStore.gameData
    val disciples: StateFlow<List<Disciple>> get() = stateStore.disciples
    // ... 纯门面，不持有任何协程或生命周期

    fun getCultivationSystem(): CultivationSystem = systemManager.getSystem()
    fun getDiscipleSystem(): DiscipleSystem = systemManager.getSystem()
    // ...
}
```

### 8.3 GameActivity 生命周期绑定

```kotlin
@AndroidEntryPoint
class GameActivity : ComponentActivity() {

    @Inject lateinit var gameEngineCore: GameEngineCore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gameEngineCore.initialize()
        // ...
    }

    override fun onDestroy() {
        gameEngineCore.shutdown()
        super.onDestroy()
    }
}
```

> 注意：`GameEngineCore` 是 `@Singleton`，在进程生命周期内只创建一次。`shutdown()` 后如果需要重新启动，需要重新调用 `initialize()`。如果 GameActivity 可能被系统回收后重建，应考虑在 `onStart`/`onStop` 而非 `onCreate`/`onDestroy` 中管理游戏循环。

### 8.4 阶段八验收标准

- [ ] `GameEngine` 无 `engineScope` 字段
- [ ] `GameEngine` 无 `initializeSystems()` 方法
- [ ] `GameEngine` 无 `destroy()` 方法
- [ ] `GameEngineCore` 是唯一持有 CoroutineScope 的引擎组件
- [ ] `GameActivity` 正确绑定 `initialize()`/`shutdown()`

---

## 阶段九：存储层精简（P1）

> 可与阶段六/七/八并行执行。

### 9.1 当前调用链

```
GameViewModel
  → RefactoredStorageFacade (14 个依赖)
    → StorageGateway (读路径)
    → StorageOrchestrator (写路径编排)
      → UnifiedStorageEngine (存储引擎)
        → UnifiedSaveRepository (仓库)
          → Room DAO (持久化)
        → UnifiedSerializationEngine (序列化)
        → SaveCrypto + SecureKeyManager (加密)
        → IntegrityValidator (完整性)
      → SlotValidationInterceptor (校验)
      → SlotLockManager (并发锁)
      → StorageCircuitBreaker (断路器)
    → UnifiedSaveRepository (backup/integrity/emergency)
    → ProactiveMemoryGuard (内存守护)
    → DataPruningScheduler (数据修剪)
    → DataArchiveScheduler (数据归档)
    → StorageScopeManager (协程作用域)
    → DeleteCoordinator (删除协调)
    → StorageExporter (导出)
    → StorageHealthChecker (健康检查)
    → StorageStatsCollector (统计)
    → SaveLimitsConfig (配额)
```

### 9.2 精简原则

1. **Facade 只编排，不实现**：Facade 负责定义操作流程，具体实现委托给下层
2. **消除中间层**：如果一层只做透传，删除它
3. **合并职责重叠的类**：Gateway 和 Repository 二选一
4. **懒加载非核心功能**：归档、修剪、统计等按需激活

### 9.3 精简后的目标架构

```
GameViewModel
  → StorageFacade (6 个依赖)
    ├─ StorageEngine (读写引擎，合并 Engine + Repository + Gateway)
    │   ├─ Room DAO (持久化)
    │   ├─ SerializationModule (序列化，合并 SerializationEngine + Converter + ProtobufCacheSerializer)
    │   ├─ CryptoModule (加密，合并 SaveCrypto + SecureKeyManager + IntegrityValidator)
    │   └─ CacheLayer (缓存，合并 CacheManager + DiskCache + TieredMemoryCache)
    ├─ SavePipeline (原子保存管道，保留)
    ├─ SlotLockManager (并发锁，保留)
    ├─ BackupManager (备份，保留)
    └─ RecoveryManager (恢复，合并 StartupRecoveryCoordinator + MultiLevelRecoveryManager)
```

### 9.4 具体合并计划

#### 9.4.1 合并 StorageGateway + UnifiedSaveRepository + UnifiedStorageEngine → StorageEngine

当前三者职责重叠：
- `StorageGateway`：读路径入口
- `UnifiedSaveRepository`：写路径 + backup + integrity
- `UnifiedStorageEngine`：读写引擎 + 进度追踪

合并为 `StorageEngine`：

```kotlin
@Singleton
class StorageEngine @Inject constructor(
    private val database: GameDatabase,
    private val serialization: SerializationModule,
    private val crypto: CryptoModule,
    private val cache: CacheLayer
) {
    val progress: StateFlow<EngineProgress>

    suspend fun save(slot: Int, data: SaveData): StorageResult<Unit>
    suspend fun load(slot: Int): StorageResult<SaveData>
    suspend fun delete(slot: Int): StorageResult<Unit>
    suspend fun getSlotMetadata(slot: Int): StorageResult<SlotMetadata>
    suspend fun listSlots(): StorageResult<List<SlotMetadata>>

    suspend fun createBackup(slot: Int): StorageResult<Unit>
    suspend fun restoreBackup(slot: Int): StorageResult<Unit>

    suspend fun validateIntegrity(slot: Int): StorageResult<IntegrityReport>
}
```

#### 9.4.2 合并序列化层

当前序列化相关类：
- `UnifiedSerializationEngine`
- `UnifiedSerializationModule`
- `SaveDataConverter`
- `ProtobufCacheSerializer`
- `StreamingProtobufSerializer`
- `SerializationEngine`
- `NullSafeProtoBuf`
- `UnifiedCompressionLayer`
- `DeltaEncodingEngine`
- `OptimizedDeltaSerializer`
- `CompressionPolicy`
- `UnifiedSerializationConstants`

合并为 `SerializationModule`：

```kotlin
@Singleton
class SerializationModule @Inject constructor(
    private val compression: CompressionLayer,
    private val delta: DeltaEngine
) {
    fun serialize(data: SaveData): ByteArray
    fun deserialize(bytes: ByteArray): SaveData
    fun serializeProto(data: SaveDataProto): ByteArray
    fun deserializeProto(bytes: ByteArray): SaveDataProto
}
```

#### 9.4.3 合并加密层

当前加密相关类：
- `SaveCrypto` (object 单例)
- `SecureKeyManager`
- `IntegrityValidator`

合并为 `CryptoModule`：

```kotlin
@Singleton
class CryptoModule @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun encrypt(data: ByteArray, slot: Int): EncryptedData
    fun decrypt(encrypted: EncryptedData, slot: Int): ByteArray
    fun computeChecksum(data: ByteArray): String
    fun verifyChecksum(data: ByteArray, expectedChecksum: String): Boolean
    fun validateIntegrity(slot: Int): IntegrityReport
}
```

#### 9.4.4 合并缓存层

当前缓存相关类：
- `GameDataCacheManager`
- `UnifiedDiskCache`
- `TieredMemoryCache`
- `SimpleBloomFilter`
- `CountMinSketch`
- `CacheKey`
- `CacheTypes`
- `CacheConfig`
- `CacheEntry`
- `UnifiedWritePipeline`
- `WriteCoalescer`
- `StartupWarmupScheduler`
- `CacheHealthDashboard`

合并为 `CacheLayer`：

```kotlin
@Singleton
class CacheLayer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun <T> get(key: CacheKey<T>): T?
    fun <T> put(key: CacheKey<T>, value: T)
    fun invalidate(slot: Int)
    fun warmup(slot: Int)
    fun clear()
    fun getStats(): CacheStats
}
```

#### 9.4.5 合并恢复层

当前恢复相关类：
- `StartupRecoveryCoordinator`
- `MultiLevelRecoveryManager`

合并为 `RecoveryManager`：

```kotlin
@Singleton
class RecoveryManager @Inject constructor(
    private val engine: StorageEngine,
    private val backup: BackupManager
) {
    suspend fun recover(slot: Int): RecoveryResult
    suspend fun emergencyRecover(slot: Int): RecoveryResult
}
```

#### 9.4.6 删除纯中间层

| 类 | 删除原因 |
|----|---------|
| `StorageOrchestrator` | 编排逻辑移入 StorageFacade |
| `StorageGateway` | 合并入 StorageEngine |
| `UnifiedSaveRepository` | 合并入 StorageEngine |
| `UnifiedStorageEngine` | 合并入 StorageEngine |
| `SlotValidationInterceptor` | 校验逻辑移入 StorageEngine |
| `StorageCircuitBreaker` | 断路器逻辑移入 StorageEngine |
| `StorageScopeManager` | 协程管理移入 StorageFacade |
| `StoragePerformanceMonitor` | 监控移入 StorageFacade |
| `StorageStatsCollector` | 统计移入 CacheLayer |
| `StorageHealthChecker` | 健康检查移入 RecoveryManager |
| `StorageExporter` | 导出逻辑移入 StorageFacade |
| `StorageQuotaManager` | 配额检查移入 StorageFacade |
| `DataPruningScheduler` | 修剪逻辑移入 StorageFacade |
| `DataArchiveScheduler` | 归档逻辑移入 StorageFacade |
| `ProactiveMemoryGuard` | 内存守护移入 StorageFacade |
| `DeleteCoordinator` | 删除逻辑移入 StorageEngine |
| `SaveCoordinator` | 保存逻辑移入 SavePipeline |

### 9.5 精简后的 RefactoredStorageFacade

```kotlin
@Singleton
class StorageFacade @Inject constructor(
    private val engine: StorageEngine,
    private val pipeline: SavePipeline,
    private val lockManager: SlotLockManager,
    private val backupManager: BackupManager,
    private val recoveryManager: RecoveryManager
) {
    val isInitialized: StateFlow<Boolean>
    val saveProgress: StateFlow<FacadeSaveProgress>

    suspend fun saveGame(slot: Int): SaveResult<Unit>
    suspend fun loadGame(slot: Int): SaveResult<SaveData>
    suspend fun deleteGame(slot: Int): SaveResult<Unit>
    suspend fun listSaveSlots(): List<SaveSlot>

    suspend fun exportSave(slot: Int, uri: Uri): SaveResult<Unit>
    suspend fun importSave(uri: Uri): SaveResult<Int>

    suspend fun validateSave(slot: Int): SaveResult<IntegrityReport>
    suspend fun recoverSave(slot: Int): SaveResult<RecoveryResult>
}
```

从 14 个依赖降到 5 个，每个依赖职责清晰。

### 9.6 阶段九验收标准

- [ ] `StorageGateway`、`StorageOrchestrator`、`UnifiedSaveRepository`、`UnifiedStorageEngine` 已删除
- [ ] `RefactoredStorageFacade` 依赖数 ≤ 6
- [ ] 存储层文件总数减少 40%+
- [ ] 保存/加载/删除功能正常
- [ ] 备份/恢复功能正常
- [ ] 加密/完整性校验功能正常

---

## 阶段十：安全漏洞修复（P1）

> 可与阶段六/七/八并行执行。

### 10.1 兑换码服务端校验

**当前问题**：`RedeemCodeService` 纯客户端校验，兑换码列表和验证逻辑打包在 APK 中。

**修复方案**：

```kotlin
@Singleton
class RedeemCodeService @Inject constructor(
    private val stateStore: GameStateStore,
    private val secureClient: SecureHttpClient,
    private val eventService: EventService
) : GameSystem {

    suspend fun redeemCode(code: String): RedeemResult {
        val trimmedCode = code.trim().takeIf {
            it.length in 4..32 && it.all { c -> c.isLetterOrDigit() || c == '-' }
        } ?: return RedeemResult(success = false, message = "兑换码格式无效")

        val usedCodes = stateStore.gameData.value.usedRedeemCodes
        if (trimmedCode in usedCodes) {
            return RedeemResult(success = false, message = "该兑换码已使用")
        }

        val request = Request.Builder()
            .url("${BuildConfig.API_BASE_URL}redeem/verify")
            .post("{\"code\":\"${trimmedCode}\"}".toRequestBody())
            .build()

        return try {
            val response = secureClient.execute(request)
            val body = response.body?.string() ?: return RedeemResult(
                success = false, message = "服务器响应异常"
            )
            val result = Gson().fromJson(body, RedeemResponse::class.java)

            if (result.success) {
                stateStore.update {
                    gameData = gameData.copy(
                        usedRedeemCodes = gameData.usedRedeemCodes + trimmedCode
                    )
                    applyReward(result.rewards)
                }
                RedeemResult(success = true, message = result.message)
            } else {
                RedeemResult(success = false, message = result.message)
            }
        } catch (e: Exception) {
            RedeemResult(success = false, message = "网络异常，请稍后重试")
        }
    }
}
```

**过渡策略**：服务端 API 就绪前，保留客户端校验作为 fallback，但增加本地校验的防篡改措施（校验 APK 签名）。

### 10.2 HMAC 密钥动态获取

**当前问题**：`RequestSigner` 的 HMAC 密钥如果硬编码在 APK 中，反编译即可提取。

**修复方案**：

```kotlin
@Singleton
class RequestSigner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureClient: SecureHttpClient
) {
    @Volatile
    private var hmacKey: SecretKeySpec? = null

    @Volatile
    private var keyExpiry: Long = 0

    private val keyLock = Mutex()

    private suspend fun ensureKey(): SecretKeySpec {
        hmacKey?.let { if (System.currentTimeMillis() < keyExpiry) return it }
        return keyLock.withLock {
            hmacKey?.let { if (System.currentTimeMillis() < keyExpiry) return it }
            fetchKeyFromServer()
        }
    }

    private suspend fun fetchKeyFromServer(): SecretKeySpec {
        val request = Request.Builder()
            .url("${BuildConfig.API_BASE_URL}auth/signing-key")
            .get()
            .build()

        val response = secureClient.execute(request)
        val body = response.body?.string() ?: throw IOException("Failed to fetch signing key")
        val json = JSONObject(body)
        val keyBytes = Base64.decode(json.getString("key"), Base64.NO_WRAP)
        keyExpiry = json.optLong("expires_at", System.currentTimeMillis() + 3600_000)

        return SecretKeySpec(keyBytes, HMAC_ALGO).also { hmacKey = it }
    }

    suspend fun signRequest(request: Request): Request {
        val key = ensureKey()
        // 原有签名逻辑
    }
}
```

### 10.3 设备指纹最小化

**当前问题**：`X-Device-Fp` 头发送 16 位设备指纹前缀，可能构成跨应用追踪标识。

**修复方案**：

1. 减少暴露长度到 8 位
2. 使用可重置的标识符（如 ANDROID_ID，用户可恢复出厂重置）
3. 不组合不可重置的硬件信息（如 IMEI、序列号）

```kotlin
private const val DEVICE_FP_PREFIX_LENGTH = 8  // 从 16 降到 8

private fun computeDeviceFingerprint(): String {
    val components = mutableListOf<String>()
    // 只使用可重置的标识符
    components.add(Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ANDROID_ID
    ))
    // 不使用 Build.SERIAL、IMEI 等不可重置标识符
    return components.joinToString("|').sha256()
}
```

### 10.4 密钥恢复安全确认

**当前问题**：`SecureKeyManager.KeyRecoveryCallback` 可能在非 UI 上下文中被触发，用户不知情地选择 `GENERATE_NEW_KEY` 导致存档永久丢失。

**修复方案**：

1. 密钥恢复回调必须通过 UI 层确认
2. `GENERATE_NEW_KEY` 选项需要二次确认
3. 后台自动保存时遇到密钥丢失，应暂停保存而非自动恢复

```kotlin
class UiKeyRecoveryCallback(
    private val activityProvider: () -> Activity?
) : KeyRecoveryCallback {

    override fun onKeyRecoveryRequired(reason: String): KeyRecoveryDecision {
        val activity = activityProvider() ?: return KeyRecoveryDecision.CANCEL

        val result = CompletableDeferred<KeyRecoveryDecision>()

        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("存档密钥异常")
                .setMessage("$reason\n\n如果选择生成新密钥，旧存档将永久无法恢复。")
                .setPositiveButton("导入恢复令牌") { _, _ ->
                    result.complete(KeyRecoveryDecision.IMPORT_TOKEN)
                }
                .setNegativeButton("取消") { _, _ ->
                    result.complete(KeyRecoveryDecision.CANCEL)
                }
                .setNeutralButton("生成新密钥（旧存档将丢失）") { _, _ ->
                    // 二次确认
                    AlertDialog.Builder(activity)
                        .setTitle("确认生成新密钥？")
                        .setMessage("此操作不可撤销，所有旧存档将永久无法恢复。")
                        .setPositiveButton("确认") { _, _ ->
                            result.complete(KeyRecoveryDecision.GENERATE_NEW_KEY)
                        }
                        .setNegativeButton("取消") { _, _ ->
                            result.complete(KeyRecoveryDecision.CANCEL)
                        }
                        .show()
                }
                .setOnCancelListener {
                    result.complete(KeyRecoveryDecision.CANCEL)
                }
                .show()
        }

        return runBlocking { result.await() }
    }
}
```

### 10.5 InputValidator 扩展

**当前问题**：只验证宗门名和弟子名，兑换码、存档名等无校验。

**修复方案**：

```kotlin
object InputValidator {

    fun validateRedeemCode(code: String): ValidationResult {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return ValidationResult.Error("兑换码不能为空")
        if (trimmed.length > 64) return ValidationResult.Error("兑换码过长")
        if (!trimmed.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            return ValidationResult.Error("兑换码包含非法字符")
        }
        return ValidationResult.Success(trimmed)
    }

    fun validateSaveName(name: String): ValidationResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return ValidationResult.Error("存档名称不能为空")
        if (trimmed.length > 30) return ValidationResult.Error("存档名称过长")
        if (INVALID_CHARS.containsMatchIn(trimmed)) {
            return ValidationResult.Error("存档名称包含非法字符")
        }
        return ValidationResult.Success(trimmed)
    }

    // 保留原有方法
    fun validateSectName(name: String): ValidationResult { /* ... */ }
    fun validateDiscipleName(name: String): ValidationResult { /* ... */ }
    fun validateSpiritStones(amount: Long, minRequired: Long = 0): ValidationResult { /* ... */ }
    fun validateQuantity(quantity: Int, min: Int = 1, max: Int = Int.MAX_VALUE): ValidationResult { /* ... */ }
}
```

### 10.6 阶段十验收标准

- [ ] 兑换码校验通过服务端 API（或客户端 fallback + APK 签名校验）
- [ ] HMAC 密钥从服务端动态获取，无硬编码
- [ ] 设备指纹暴露长度 ≤ 8 位，不使用不可重置标识符
- [ ] 密钥恢复必须通过 UI 确认，`GENERATE_NEW_KEY` 需二次确认
- [ ] `InputValidator` 覆盖兑换码和存档名

---

## 阶段十一：性能优化（P2）

> 依赖阶段七完成（Tick 原子性修复后才能安全优化 combine 策略）。

### 11.1 combine 重组优化

**当前问题**：14 路 `combine` 中任何一个子 Flow 变化都触发重组。一次 tick 内多个 Service 分别更新不同 Flow，导致 `unifiedState` 重组多次。

**方案 A：单一 MutableStateFlow（推荐）**

```kotlin
@Singleton
class GameStateStore @Inject constructor() {

    private val _state = MutableStateFlow(UnifiedGameState())

    val unifiedState: StateFlow<UnifiedGameState> = _state.asStateFlow()

    val gameData: StateFlow<GameData> = _state.map { it.gameData }
        .distinctUntilChanged()
        .stateIn(/* ... */)

    val disciples: StateFlow<List<Disciple>> = _state.map { it.disciples }
        .distinctUntilChanged()
        .stateIn(/* ... */)

    // ... 其余派生 Flow

    suspend fun update(block: MutableGameState.() -> Unit) {
        transactionMutex.withLock {
            val mutable = MutableGameState(
                gameData = _state.value.gameData,
                disciples = _state.value.disciples,
                // ...
            )
            mutable.block()
            _state.value = UnifiedGameState(
                gameData = mutable.gameData,
                disciples = mutable.disciples,
                // ...
            )
        }
    }
}
```

**优势**：
- 一次 `update` 只触发一次 `unifiedState` 重组
- 派生 Flow 通过 `distinctUntilChanged` 过滤无关变化
- 彻底消除半更新问题

**劣势**：
- 每个派生 Flow 需要 `map + distinctUntilChanged + stateIn`，有初始计算开销
- 需要重构所有直接访问 `_gameData.value` 的内部代码

**方案 B：sample 窗口（轻量替代）**

如果方案 A 影响面太大，可在 `unifiedState` 上加 `sample` 限制发射频率：

```kotlin
val unifiedState: StateFlow<UnifiedGameState> = combine(/* ... */)
    .sample(50)  // 最多每 50ms 发射一次
    .stateIn(/* ... */)
```

50ms 对于 200ms tick 间隔是安全的，且对 UI 响应性影响极小（人类感知阈值 ~16ms）。

### 11.2 MutableGameState 拷贝优化

**当前问题**：每次 `update()` 都创建新的 `MutableGameState`，拷贝 13 个列表引用。

**方案：MutableGameState 复用**

```kotlin
suspend fun update(block: MutableGameState.() -> Unit) {
    transactionMutex.withLock {
        val mutable = reusableMutableState.apply {
            gameData = _state.value.gameData
            disciples = _state.value.disciples
            // ...
        }
        mutable.block()
        _state.value = UnifiedGameState(
            gameData = mutable.gameData,
            disciples = mutable.disciples,
            // ...
        )
    }
}

private val reusableMutableState = MutableGameState(
    gameData = GameData(),
    disciples = emptyList(),
    // ...
)
```

> 注意：此方案仅在 Mutex 保护下安全（同一时刻只有一个协程在修改 reusableMutableState）。如果采用方案 A（单一 MutableStateFlow），此优化自然成立。

### 11.3 SystemManager 错误传播

**当前问题**：SystemManager 吞异常，游戏继续运行但状态可能已损坏。

**方案**：

```kotlin
data class SystemError(
    val systemName: String,
    val tickType: String,
    val error: Throwable
)

@Singleton
class SystemManager @Inject constructor(
    systems: Set<@JvmSuppressWildcards GameSystem>
) {
    private val _errors = Channel<SystemError>(Channel.BUFFERED)
    val errors: Flow<SystemError> = _errors.receiveAsFlow()

    suspend fun onSecondTick(state: MutableGameState) {
        systemOrder.forEach { kClass ->
            systemMap[kClass]?.let { system ->
                try {
                    system.onSecondTick(state)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onSecondTick for ${system.systemName}", e)
                    _errors.trySend(SystemError(system.systemName, "onSecondTick", e))
                }
            }
        }
    }
}
```

UI 层订阅 `systemManager.errors` 并显示错误提示：

```kotlin
// GameViewModel
init {
    viewModelScope.launch {
        systemManager.errors.collect { error ->
            _errorMessage.value = "系统异常：${error.systemName}"
        }
    }
}
```

### 11.4 System 执行顺序契约化

**当前问题**：系统执行顺序由 `setOf` 的迭代顺序决定，是实现细节而非契约。

**方案**：引入 `@SystemPriority` 注解或显式排序：

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class SystemPriority(val order: Int)

@SystemPriority(0)  // 最先执行
class TimeSystem : GameSystem { /* ... */ }

@SystemPriority(10)
class CultivationSystem : GameSystem { /* ... */ }

@SystemPriority(20)
class DiscipleSystem : GameSystem { /* ... */ }

@SystemPriority(100)  // 最后执行
class SaveService : GameSystem { /* ... */ }

// SystemManager 中按 priority 排序
init {
    systems.sortedBy { it.javaClass.getAnnotation(SystemPriority::class.java)?.order ?: 50 }
        .forEach { system ->
            systemMap[system::class] = system
            systemOrder.add(system::class)
        }
}
```

### 11.5 阶段十一验收标准

- [ ] `unifiedState` 在一次 tick 内只重组一次（或通过 sample 限制频率）
- [ ] `SystemManager.errors` Flow 可被 UI 层订阅
- [ ] 系统执行顺序由 `@SystemPriority` 契约化
- [ ] 游戏循环性能：tick 耗时 < 50ms（中端设备）

---

## 阶段十二：架构演进保障（P2）

> 依赖前面所有阶段完成。

### 12.1 状态版本化迁移

**当前问题**：`UnifiedGameState` 是 data class，新增字段后旧存档加载为默认值，无迁移钩子。

**方案**：

```kotlin
data class UnifiedGameState(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val gameData: GameData = GameData(),
    // ...
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }

    fun migrate(): UnifiedGameState {
        var state = this
        while (state.schemaVersion < CURRENT_SCHEMA_VERSION) {
            state = when (state.schemaVersion) {
                1 -> state.migrateV1toV2()
                else -> state.copy(schemaVersion = CURRENT_SCHEMA_VERSION)
            }
        }
        return state
    }

    private fun migrateV1toV2(): UnifiedGameState {
        return copy(
            schemaVersion = 2,
            gameData = gameData.copy(
                playerProtectionEnabled = true,
                playerProtectionStartYear = gameData.gameYear
            )
        )
    }
}
```

在 `StorageFacade.loadGame()` 中调用 `migrate()`：

```kotlin
suspend fun loadGame(slot: Int): SaveResult<SaveData> {
    val result = engine.load(slot)
    if (result is SaveResult.Success) {
        val migrated = result.data.toUnifiedGameState().migrate()
        stateStore.loadFromSnapshot(migrated)
    }
    return result
}
```

### 12.2 序列化路径一致性校验

**当前问题**：引擎层 `UnifiedGameState` 与存储层 `SaveData` / Protobuf 之间的转换可能遗漏字段。

**方案**：引入编译期/运行时双向校验：

```kotlin
@Singleton
class SchemaConsistencyChecker @Inject constructor() {

    fun verify(): List<SchemaInconsistency> {
        val issues = mutableListOf<SchemaInconsistency>()

        val gameStateFields = UnifiedGameState::class.memberProperties.map { it.name }.toSet()
        val saveDataFields = SaveData::class.memberProperties.map { it.name }.toSet()

        val inStateNotInSave = gameStateFields - saveDataFields - setOf("schemaVersion", "version")
        if (inStateNotInSave.isNotEmpty()) {
            issues.add(SchemaInconsistency(
                "UnifiedGameState 有字段未在 SaveData 中映射: $inStateNotInSave"
            ))
        }

        val protoFields = SaveDataProto.getDescriptor().fields.map { it.name }.toSet()
        val inSaveNotInProto = saveDataFields - protoFields - setOf("schemaVersion")
        if (inSaveNotInProto.isNotEmpty()) {
            issues.add(SchemaInconsistency(
                "SaveData 有字段未在 Protobuf schema 中定义: $inSaveNotInProto"
            ))
        }

        return issues
    }
}

data class SchemaInconsistency(val message: String)
```

在 Debug 构建中启动时自动运行：

```kotlin
// XianxiaApplication.onCreate()
if (BuildConfig.DEBUG_MODE) {
    val checker = SchemaConsistencyChecker()
    val issues = checker.verify()
    issues.forEach { Log.w("SchemaCheck", it.message) }
}
```

### 12.3 无依赖系统并行 tick（远期）

**当前问题**：所有 System 顺序执行，扩展性受限。

**远期方案**：声明系统间依赖关系，无依赖的系统并行执行：

```kotlin
@SystemPriority(10, dependsOn = [TimeSystem::class])
class CultivationSystem : GameSystem { /* ... */ }

@SystemPriority(10, dependsOn = [TimeSystem::class])
class DiscipleSystem : GameSystem { /* ... */ }

@SystemPriority(20, dependsOn = [CultivationSystem::class, DiscipleSystem::class])
class CombatSystem : GameSystem { /* ... */ }

// SystemManager 中构建 DAG，并行执行无依赖的系统
suspend fun onSecondTick(state: MutableGameState) {
    val dag = buildDag()
    val visited = mutableSetOf<KClass<out GameSystem>>()

    while (visited.size < systemMap.size) {
        val ready = dag.nodes.filter { node ->
            node !in visited && node.dependencies.all { it in visited }
        }

        coroutineScope {
            ready.forEach { node ->
                launch {
                    systemMap[node]?.onSecondTick(state)
                }
            }
        }

        visited.addAll(ready)
    }
}
```

> 注：此方案要求 System 对 `MutableGameState` 的修改不冲突（无写写冲突），或引入字段级锁。当前阶段不建议实施，仅作为远期方向记录。

### 12.4 阶段十二验收标准

- [ ] `UnifiedGameState` 有 `schemaVersion` 字段和 `migrate()` 方法
- [ ] 存档加载时自动执行迁移
- [ ] Debug 构建启动时运行 `SchemaConsistencyChecker`
- [ ] 新增字段有对应的迁移逻辑

---

## 执行顺序和依赖关系

```
refactoring-plan.md:
  阶段一 (状态路径统一) ← 前置
  阶段二 (Service/System 合并)
  阶段三 (库存统一) ← 可并行
  阶段四 (ViewModel 拆分)
  阶段五 (死代码清理)

本文档:
  阶段六 (并发模型统一) ← 依赖阶段一
  阶段七 (Tick 原子性修复) ← 依赖阶段六
  阶段八 (引擎层生命周期统一) ← 可与六/七并行
  阶段九 (存储层精简) ← 可与六/七/八并行
  阶段十 (安全漏洞修复) ← 可与六/七/八并行
  阶段十一 (性能优化) ← 依赖阶段七
  阶段十二 (架构演进保障) ← 依赖前面所有阶段
```

```
阶段一 ──→ 阶段六 ──→ 阶段七 ──→ 阶段十一
  │                      ↑
  ├──→ 阶段八 ──────────┘
  ├──→ 阶段九 (并行)
  └──→ 阶段十 (并行)
                          ──→ 阶段十二 (最后)
```

---

## 预估影响

| 指标 | 当前 | 优化后预估 |
|------|------|-----------|
| 并发原语种类 | 4 种 (ReentrantLock + Mutex + @Synchronized + Atomic) | 1 种 (Mutex) |
| Tick 事务性 | 无（13 次独立写入） | 单一事务 |
| combine 半更新 | 有 | 无 |
| StorageFacade 依赖数 | 14 | 5 |
| 存储层文件数 | ~90 | ~50 |
| 安全漏洞 | 5 个 | 0 个 |
| Tick 重组次数/帧 | 3-5 次 | 1 次 |
| 状态迁移能力 | 无 | schemaVersion + migrate() |
| 系统执行顺序 | 隐式（Set 迭代） | 显式（@SystemPriority） |
