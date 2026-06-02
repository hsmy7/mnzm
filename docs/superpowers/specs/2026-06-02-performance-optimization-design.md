# 方案 B：架构级性能优化设计

> **版本**：v2（经行业对标评估后修正）| **评估报告**：[2026-06-02-performance-optimization-evaluation.md](./2026-06-02-performance-optimization-evaluation.md)

## 1. 问题定位

### 1.1 现状数据

| 指标 | 当前值 | 目标值 |
|------|--------|--------|
| 重组次数/秒 | ~10-30 次（tick 1s/次 + 用户操作） | < 10 次 |
| 帧时间（低配 4GB） | 16-50ms | < 20ms（P95） |
| GC 停顿 | 5-100ms | < 10ms |
| 内存占用（低配 4GB） | ~180-250MB | < 150MB |
| tick 耗时 | 5-100ms | < 10ms |

> **注意**：当前 tick 间隔实际为 **1000ms**（`GameEngineCore.kt:60` `TICK_INTERVAL_MS = 1000L`），自适应上限 2000ms，而非 CLAUDE.md 文档中记载的 200ms。此差异影响模块 1/2 的投入产出比判断——每秒仅 1 次全量派发，优化紧迫性低于原估计。

### 1.2 根因链

```
GameStateStore.update() 每 1 秒创建新 UnifiedGameState
  → 15+ 个 .map{}.distinctUntilChanged().stateIn() 全部重算
    → GameViewModel 中 30+ 个 StateFlow 订阅全部收到新值
      → 139 个 collectAsState() 在 17 个文件中触发 Compose 读取
        → 重组范围扩散到 MainGameScreen
          → 低配设备帧时间 > 16ms → 掉帧卡顿
```

核心矛盾：**单一巨对象 + 全量派发**。任何字段变化都触发全链路重算。但 tick 频率已从 200ms 降至 1000ms（v3.1.76），tick 驱动重组的压力已大幅降低，当前主要瓶颈可能在**用户交互触发的大量小粒度更新**和**Canvas 绘制管线**。

---

## 2. 优化模块

### 模块 1：增量派发 — 从「全量发射」到「按变化发射」

> **核心思路**：将 `_state.update{}` 由「不区分哪个字段变了，全部重新发射」改为「事务内检测每个子状态是否实际变化，只发射变化的子流」。这是模块 2 脏标记的**上游替代方案**——不需要额外维护 12 个 `@Volatile`，而是在 `update()` 事务退出时做一次新旧对比，开销极低。

#### 2.1.1 现状 vs 目标

```
现状（全量派发）：
  tick → update { gameData.spiritStones += 10 }
    → _state.value = 新的 UnifiedGameState（22 个字段全部新引用）
      → 15 个 .map{} 全部执行（每 tick 1 次 = 每秒 15 次 map）
        → distinctUntilChanged 拦截 14 个未变化的
          → 只有 1 个下游订阅真正收到新值

目标（增量派发）：
  tick → update { gameData.spiritStones += 10 }
    → 事务退出时检测：gameData 变了 ≠ disciples 没变 ≠ buildings 没变
      → 只 emit _gameData StateFlow
        → 其他 14 个子流无发射，零 .map{} 执行，零 distinctUntilChanged 开销
```

**关键洞察**：`distinctUntilChanged` 阻止了下游重组，但它阻止不了 **`.map{}` 本身执行**。15 个 `.map{}` 每 tick 白白跑一遍，然后被 `distinctUntilChanged` 扔掉 14 个结果。增量派发从源头消除这 14 次无用的 `.map{}`。

#### 2.1.2 设计：按变更频率分 3 层，事务内做对比

```
┌──────────────────────────────────────────────────────────────┐
│ Layer 1: _gameData / _highFreq  (每 tick 必变)                │
│   spiritStones, gameYear, gameMonth, gamePhase, gameSpeed    │
│   → 独立 MutableStateFlow<HighFrequencyState>                │
│   → 每次 tick 大概率变化，直接发射（不做对比）                  │
├──────────────────────────────────────────────────────────────┤
│ Layer 2: _entities  (玩家操作 / 月度结算 / 战斗结束才变)        │
│   disciples, equipment, manuals, pills, materials,           │
│   herbs, seeds, teams, battleLogs                            │
│   → 独立 MutableStateFlow<EntityState>                       │
│   → tick 内做引用对比（===），变了才 emit                      │
├──────────────────────────────────────────────────────────────┤
│ Layer 3: _config  (玩家手动修改 / 极低频)                      │
│   placedBuildings, elderSlots, sectPolicies,                 │
│   productionSlots, recruitList, activeMissions               │
│   → 独立 MutableStateFlow<ConfigState>                       │
│   → 几乎不会在 tick 内变化，变了才 emit                         │
└──────────────────────────────────────────────────────────────┘
```

**增量检测策略**：在 `update()` 事务退出时，将 `reusableMutableState` 中每个子状态的新旧引用做 `===`（引用相等）对比。未变 → 不发射。这是引用级比较，开销为 O(1) × 3 个子状态。

#### 2.1.3 数据结构

```kotlin
data class HighFrequencyState(
    val spiritStones: Long = 0,
    val gameYear: Int = 1,
    val gameMonth: Int = 1,
    val gamePhase: Int = 0,
    val gameSpeed: Int = 1,
    val cultivationProgress: Map<String, Float> = emptyMap()
)

data class EntityState(
    val disciples: List<Disciple> = emptyList(),
    val equipmentStacks: List<EquipmentStack> = emptyList(),
    val equipmentInstances: List<EquipmentInstance> = emptyList(),
    val manualStacks: List<ManualStack> = emptyList(),
    val manualInstances: List<ManualInstance> = emptyList(),
    val pills: List<Pill> = emptyList(),
    val materials: List<Material> = emptyList(),
    val herbs: List<Herb> = emptyList(),
    val seeds: List<Seed> = emptyList(),
    val teams: List<ExplorationTeam> = emptyList(),
    val battleLogs: List<BattleLog> = emptyList()
)

data class ConfigState(
    val placedBuildings: List<GridBuildingData> = emptyList(),
    val elderSlots: List<ElderSlot> = emptyList(),
    val sectPolicies: SectPolicies = SectPolicies(),
    val autoSaveIntervalMonths: Int = 3,
    val monthlySalary: Map<String, Long> = emptyMap(),
    val patrolConfig: PatrolConfig = PatrolConfig(),
    val productionSlots: List<ProductionSlot> = emptyList(),
    val recruitList: List<Disciple> = emptyList(),
    val activeMissions: List<ActiveMission> = emptyList()
)
```

#### 2.1.4 GameStateStore 改造：核心增量发射逻辑

```kotlin
@Singleton
class GameStateStore @Inject constructor(
    private val applicationScopeProvider: ApplicationScopeProvider
) {
    // 三个独立 StateFlow（替代原来的单一 _state）
    private val _highFreq = MutableStateFlow(HighFrequencyState())
    private val _entities = MutableStateFlow(EntityState())
    private val _config = MutableStateFlow(ConfigState())

    val highFreq: StateFlow<HighFrequencyState> = _highFreq.asStateFlow()
    val entities: StateFlow<EntityState> = _entities.asStateFlow()
    val config: StateFlow<ConfigState> = _config.asStateFlow()

    // ── 核心：增量 emit ──
    // 在 update() 事务退出时，只发射实际变化的子流
    // reusableMutableState 是已有对象，复用它的字段来做新旧对比

    suspend fun update(block: suspend MutableGameState.() -> Unit) {
        transactionMutex.withLock {
            // 1. 从当前 3 个子流快照填充 reusableMutableState
            val oldHighFreq = _highFreq.value
            val oldEntities = _entities.value
            val oldConfig = _config.value

            reusableMutableState.apply {
                gameData = buildGameData(oldHighFreq, oldConfig)
                disciples = oldEntities.disciples
                equipmentStacks = oldEntities.equipmentStacks
                // ... 其余 entity 字段
                placedBuildings = oldConfig.placedBuildings
                // ... 其余 config 字段
            }

            // 2. 执行业务逻辑（现有代码零改动）
            reusableMutableState.block()

            // 3. 提取新值，做引用对比，只发射变化的
            val newHighFreq = extractHighFreq(reusableMutableState)
            val newEntities = extractEntities(reusableMutableState)
            val newConfig = extractConfig(reusableMutableState)

            // HighFreq：几乎每 tick 都变，直接发（不做对比省开销）
            if (newHighFreq != oldHighFreq) {
                _highFreq.value = newHighFreq
            }

            // Entities：大概率不变，用引用对比
            if (hasEntitiesChanged(oldEntities, newEntities)) {
                _entities.value = newEntities
            }

            // Config：极大概率不变，用引用对比
            if (hasConfigChanged(oldConfig, newConfig)) {
                _config.value = newConfig
            }
        }
    }
}

// 引用对比辅助函数（O(N) 但 N 很小 = 字段数）
private fun hasEntitiesChanged(old: EntityState, new: EntityState): Boolean {
    return old.disciples !== new.disciples ||
        old.equipmentStacks !== new.equipmentStacks ||
        old.equipmentInstances !== new.equipmentInstances ||
        old.manualStacks !== new.manualStacks ||
        old.manualInstances !== new.manualInstances ||
        old.pills !== new.pills ||
        old.materials !== new.materials ||
        old.herbs !== new.herbs ||
        old.seeds !== new.seeds ||
        old.teams !== new.teams ||
        old.battleLogs !== new.battleLogs
}

private fun hasConfigChanged(old: ConfigState, new: ConfigState): Boolean {
    return old.placedBuildings !== new.placedBuildings ||
        old.elderSlots !== new.elderSlots ||
        old.sectPolicies != new.sectPolicies ||  // data class，用值等
        old.productionSlots !== new.productionSlots ||
        old.recruitList !== new.recruitList ||
        old.activeMissions !== new.activeMissions
}
```

**为什么用 `===` 引用对比？**
- 每个子状态内的 `List`、`Map` 等，如果业务逻辑没修改该字段，`reusableMutableState` 中保留了旧引用
- `===` 是 O(1) 开销，比 `==`（结构性相等，需遍历所有元素）快 100-1000 倍
- 业务代码修改了 `disciples` 字段 → 新 List 引用 → `===` 检测到变化 → 发射
- 业务代码没改 `equipmentStacks` → 保留旧引用 → `===` 检测无变化 → 不发射

#### 2.1.5 消费端改造

现有消费端通过 15 个 `.map{}.distinctUntilChanged().stateIn()` 派生 Flow。改为直接暴露 3 个子流：

```kotlin
// 改造前：15 个 derived Flow
val disciples: StateFlow<List<Disciple>> = _state
    .map { it.disciples }.distinctUntilChanged().stateIn(...)
val equipmentStacks: StateFlow<List<EquipmentStack>> = _state
    .map { it.equipmentStacks }.distinctUntilChanged().stateIn(...)
// ... 13 more

// 改造后：直接从子流派生，零 .map{} 开销
val disciples: StateFlow<List<Disciple>> = _entities
    .map { it.disciples }.distinctUntilChanged().stateIn(...)
val equipmentStacks: StateFlow<List<EquipmentStack>> = _entities
    .map { it.equipmentStacks }.distinctUntilChanged().stateIn(...)
// ... 但 entities 流不会每 tick 都发射！只有 entities 真变了才触发这些 .map{}
```

#### 2.1.6 向后兼容

对于仍需要 `UnifiedGameState` 的代码（如存档序列化、settlement coordinator），通过 `combine` 组装：

```kotlin
val unifiedState: StateFlow<UnifiedGameState> = combine(
    _highFreq, _entities, _config
) { hf, entity, config ->
    UnifiedGameState(
        gameData = buildGameData(hf, config),
        disciples = entity.disciples,
        equipmentStacks = entity.equipmentStacks,
        equipmentInstances = entity.equipmentInstances,
        // ... 其余字段
    )
}.stateIn(scope, SharingStarted.Eagerly, UnifiedGameState())
```

> ⚠️ `combine` 发射新 `UnifiedGameState` 的频率 = 3 个子流中最频变的那个。大部分 tick 只有 `_highFreq` 变，但在 `combine` 中会触发 `UnifiedGameState` 重建。建议：逐步将消费者从 `unifiedState` 迁移到 `highFreq`/`entities`/`config` 直接订阅，最终可以移除 `combine`。

#### 2.1.7 迁移策略

| 步骤 | 内容 | 改动范围 | 风险 |
|------|------|---------|------|
| 1 | 新增 `_highFreq` / `_entities` / `_config` 三个 StateFlow | GameStateStore | 零（加代码不改行为） |
| 2 | 修改 `update()` 增加前后对比 + 增量 emit | GameStateStore.update() | 低（引用对比逻辑简单） |
| 3 | 迁移 ViewModel 中的 Flow 声明：改从子流 `.map{}` | GameViewModel（~12 处） | 低（用子流替换 `_state.map{}`） |
| 4 | 逐步迁移 UI 消费者直接订子流，绕过 `unifiedState` | 各个 Composable | 中（逐文件改，功能回归） |
| 5 | 移除对 `_state` 旧 MutableStateFlow 的依赖 | GameStateStore | 低（所有消费者迁移完后） |

#### 2.1.8 预期收益

| 场景 | 改造前（每 tick） | 改造后（每 tick） |
|------|-----------------|-----------------|
| tick 更新灵石 | `_state` emit → 15 个 `.map{}` 执行 → 14 个被 `distinctUntilChanged` 丢弃 | `_highFreq` emit → 0 个 `.map{}` 额外执行（entities/config 未 emit） |
| 月度结算更新弟子列表 | `_state` emit → 15 个 `.map{}` 全部执行 | `_entities` emit → `_highFreq` 也 emit（灵石/日期变了）→ 共 ~12 个 `.map{}` 执行（entity 的 11 个 + highFreq 的 1 个） |
| 玩家放置建筑 | `_state` emit → 15 个 `.map{}` 全部执行 | `_config` emit → 仅 config 相关的 ~3 个 `.map{}` 执行 |

**核心收益不是 tick 期间的 CPU（每秒 15 次 `.map{}` 微不足道），而是架构改进**：
- 下游消费者可以精确订阅它们需要的子流，减少不必要的重组触发
- 未来新增模块不用在 15 个 derived Flow 上再叠加
- 调试更容易——状态变化来源清晰可追踪

#### 2.1.9 与 Compose Snapshot 系统的关系

Compose 的 `mutableStateOf` / `SnapshotStateList` 在**元素粒度**上比 StateFlow 更精确（修改 `list[3]` 只触发读取了 index 3 的 Composable 重组）。但在本项目中：

- 游戏状态来自 `Dispatchers.Default`（非主线程），`mutableStateOf` 需要 `Snapshot.withMutableSnapshot{}` 包裹，增加复杂度
- `StateFlow` 的 `.update{}` 自带线程安全，更适合游戏引擎场景
- 现有 139 个 `collectAsState()` 迁移到 `mutableStateOf` 的工作量过大

**结论**：保持 StateFlow 体系，通过分层 + 增量 emit 实现接近 Snapshot 系统的粒度，同时保持线程安全。

---

### 模块 2：脏标记系统 — 与增量派发互补，在子流内部做字段级跳过

> **定位调整**：模块 1 的增量派发解决「Entities 整体变了 vs 没变」的粗粒度问题。但当 Entities 内部只有一个字段变化（如只改了 `pills` 而 `disciples` 等 10 个字段未变），增量派发仍会 emit 整个 `EntityState`，下游 11 个 `.map{}` 全部执行。脏标记在**子流内部**做更细粒度的跳过——仅 `pills.map{}` 执行，其他 10 个 `.map{}` 不执行。

#### 2.2.1 两层过滤的协作关系

```
增量派发（模块 1）：粗粒度
  tick → update() 对比 → 只有 entities 变了才 emit _entities
    但 entities 里可能只改了 pills，没改 disciples

脏标记（模块 2）：细粒度  
  _entities emit → 对比脏标记 → 只让 pills 的 .map{} 执行
    disciples 等 10 个 .map{} 跳过
```

两层各有职责，互为补充。

#### 2.2.2 设计

在 `MutableGameState` 事务内记录哪些字段实际被修改，下游 Flow 只在脏标记为 true 时重新计算。

```kotlin
data class DirtyFlags(
    @Volatile var gameData: Boolean = false,
    @Volatile var disciples: Boolean = false,
    @Volatile var equipmentStacks: Boolean = false,
    @Volatile var equipmentInstances: Boolean = false,
    @Volatile var manualStacks: Boolean = false,
    @Volatile var manualInstances: Boolean = false,
    @Volatile var pills: Boolean = false,
    @Volatile var materials: Boolean = false,
    @Volatile var herbs: Boolean = false,
    @Volatile var seeds: Boolean = false,
    @Volatile var teams: Boolean = false,
    @Volatile var battleLogs: Boolean = false
)
```

#### 2.2.2 集成到 update 事务

```kotlin
suspend fun update(block: suspend MutableGameState.() -> Unit) {
    transactionMutex.withLock {
        val dirty = DirtyFlags()
        val current = _state.value
        reusableMutableState.apply { /* 从 current 复制 */ }
        currentTransactionState = reusableMutableState
        currentDirtyFlags = dirty
        try {
            reusableMutableState.block()
            // 只发布脏字段变化后的状态
            _state.update { oldState ->
                buildUpdate(oldState, reusableMutableState, dirty)
            }
        } finally {
            currentTransactionState = null
            currentDirtyFlags = null
        }
    }
}
```

#### 2.2.3 DirtyFlags 自动标记

通过 `MutableGameState` 的 setter 隐式标记：

```kotlin
class MutableGameState(...) {
    var gameData: GameData
        set(value) { field = value; currentDirtyFlags?.gameData = true }
    var disciples: List<Disciple>
        set(value) { field = value; currentDirtyFlags?.disciples = true }
    // ... 其余字段同理
}
```

#### 2.2.4 下游 Flow 利用脏标记

```kotlin
val disciples: StateFlow<List<Disciple>> = _state
    .map { it.disciples }
    .distinctUntilChanged()  // 引用相等性检查已有，脏标记进一步减少上游触发
    .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

脏标记主要收益在 `discipleAggregates` 和 `sectCombatPower` 等重计算 Flow：

```kotlin
val discipleAggregates: StateFlow<List<DiscipleAggregate>> = _state
    .map { it.disciples }
    .distinctUntilChanged { old, new -> old === new }
    .map { disciples -> disciples.map { it.toAggregate() } }
    .stateIn(...)
```

当前问题：即使 disciples 引用未变，只要 `_state` 更新了，`.map { it.disciples }` 就会执行。脏标记让 `update` 事务只在字段实际变化时才更新对应子 StateFlow。

#### 2.2.5 预期收益

- tick 内只修改 `gameData`（高频场景）→ 只有 `gameData` 脏标记为 true → 弟子/装备/物品等 Flow 跳过重算
- `sectCombatPower` 从每秒 5 次重算降为弟子属性变化时才重算

---

### 模块 3：对象池 — 减少 GC 压力

#### 2.3.1 设计

针对高频分配的短生命周期对象引入对象池：

| 对象类型 | 分配频率 | 池化策略 |
|----------|---------|---------|
| `MutableGameState` | 每 tick 1 次 | 单例复用（已有 `reusableMutableState`） |
| `DiscipleAggregate` | tick 时 × N 个弟子 | 缓存池，指纹匹配时复用（需先 Profile 确认必要性） |
| `Path`（**世界地图 MapCanvas**） | 每帧 ~150 个 | 按路径 ID 缓存 |
| `DirtyFlags` | 每 tick 1 次 | 单例复用 + reset |

> **注意**：`SectGroundCanvas`（山门地图）使用 `drawImage`/`drawRect`/`drawLine`/`drawCircle`，**零 Path 对象分配**。Path 分配集中在世界地图 `MapCanvas.kt`（每帧 120-200 个 Path），优化目标应对准世界地图。

#### 2.3.2 DiscipleAggregate 缓存

当前 `discipleAggregates` 每次都执行 `disciples.map { it.toAggregate() }`，即使弟子属性未变也重新创建 Aggregate 对象。

```kotlin
private val aggregateCache = ConcurrentHashMap<String, DiscipleAggregate>()
private val aggregateFingerprintCache = ConcurrentHashMap<String, Int>()

val discipleAggregates: StateFlow<List<DiscipleAggregate>> = _state
    .map { it.disciples }
    .distinctUntilChanged { old, new -> old === new }
    .map { disciples ->
        disciples.map { disciple ->
            val fp = disciple.fingerprint()
            val cached = aggregateCache[disciple.id]
            if (cached != null && aggregateFingerprintCache[disciple.id] == fp) {
                cached
            } else {
                val aggregate = disciple.toAggregate()
                aggregateCache[disciple.id] = aggregate
                aggregateFingerprintCache[disciple.id] = fp
                aggregate
            }
        }
    }
    .stateIn(...)
```

#### 2.3.3 世界地图 Path 缓存（MapCanvas）

当前 `MapCanvas`（世界地图，`ui/game/map/MapCanvas.kt`）每帧创建 120-200 个 `Path()` 对象用于绘制宗门间连线：

```kotlin
// 改造前（MapCanvas.kt）
paths.forEach { pathData ->
    val pathObj = Path()  // 每帧新分配，~150 次/帧
    // ... 构建 path
}

// 改造后
private val pathCache = mutableMapOf<String, Path>()

@Composable
fun MapCanvas(...) {
    val cachedPaths = remember(paths) {
        pathCache.clear()
        paths.mapIndexed { index, pathData ->
            val key = "path_$index"
            val path = pathCache.getOrPut(key) { Path() }
            path.reset()
            // ... 构建 path
            key to path
        }
    }
    Canvas(modifier = modifier.fillMaxSize()) {
        withTransform({ translate(-cameraState.cameraX, -cameraState.cameraY) }) {
            cachedPaths.forEach { (_, pathObj) ->
                drawPath(path = pathObj, ...)
            }
        }
    }
}
```

#### 2.3.4 预期收益

- GC 频率降低 40-60%（减少短生命周期对象分配）
- Canvas 帧时间降低 10-15%（减少 Path 对象分配和 GC）

---

### 模块 4：Canvas 离屏缓冲 — 分层渲染

#### 2.4.1 设计

> **已有基础**：`GameActivity.kt:184-214` 已使用 `android.graphics.Canvas` 将地形+装饰物预渲染为单张 `fullMapBmp`。`SectGroundCanvas` 绘制时只需 1 次 `drawImage(fullMapBmp)`。本模块在此基础上扩展——将建筑贴图也 bake 进静态层。

当前 `SectGroundCanvas` 每帧重绘全部内容：背景图 + 所有建筑 + 网格线 + 预览。

优化策略（两种互补方案）：

- **方案 A（Bitmap 离屏缓冲）**：扩展现有 `fullMapBmp` 预渲染管线，将建筑贴图 bake 进 Bitmap，建筑变化时重建
- **方案 B（Compose graphicsLayer）**：使用 `Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }`，将整个地图层渲染到 GPU 纹理，后续帧直接复用纹理——Google 官方推荐，代码更简单

```
┌─────────────────────────────────────────────┐
│ Layer 0: 静态背景 (GPU 纹理，建筑变化时重建)   │
│   fullMapBmp + 建筑贴图 + 装饰物              │
│   使用 graphicsLayer(Offscreen) 或手动 Bitmap │
├─────────────────────────────────────────────┤
│ Layer 1: 动态覆盖 (每帧重绘)                  │
│   网格线 + 放置预览 + 移动预览 + 光环          │
└─────────────────────────────────────────────┘
```

#### 2.4.2 实现

```kotlin
@Composable
private fun SectGroundCanvas(...) {
    // 静态层：只在 placedBuildings 变化时重绘
    val staticLayerBmp = remember(placedBuildings, fullMapBmp) {
        val bmp = fullMapBmp.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(bmp)
        for (building in placedBuildings) {
            val buildingBmp = buildingBitmaps[building.displayName]?.asAndroidBitmap()
            if (buildingBmp != null) {
                canvas.drawBitmap(
                    buildingBmp,
                    null,
                    android.graphics.Rect(
                        building.gridX * tileSize,
                        building.gridY * tileSize,
                        (building.gridX + building.width) * tileSize,
                        (building.gridY + building.height) * tileSize
                    ),
                    null
                )
            }
        }
        bmp.asImageBitmap()
    }

    Canvas(modifier = modifier.pointerInput(...)) {
        withTransform({ translate(-cameraState.cameraX, -cameraState.cameraY) }) {
            // Layer 0: 静态背景（一次 drawImage）
            drawImage(staticLayerBmp, topLeft = Offset.Zero)

            // Layer 1: 动态覆盖（网格线、预览等）
            if (isPlacing || buildingBarExpanded || isMoving) {
                // 只绘制可见区域的网格线
                drawVisibleGridLines(cameraState, tileSize, worldWidthCells, worldHeightCells)
            }
            // ... 放置预览、移动预览
        }
    }
}
```

#### 2.4.3 视口裁剪

当前网格线绘制范围是 `0..worldPixelHeight`，改为只绘制可见区域：

```kotlin
private fun DrawScope.drawVisibleGridLines(
    cameraState: CameraState,
    tileSize: Int,
    worldWidthCells: Int,
    worldHeightCells: Int
) {
    val gridColor = Color(0xFFE4DDD0)
    val visibleStartX = cameraState.cameraX
    val visibleEndX = cameraState.cameraX + size.width
    val visibleStartY = cameraState.cameraY
    val visibleEndY = cameraState.cameraY + size.height

    val firstCol = (visibleStartX / tileSize).toInt().coerceAtLeast(0)
    val lastCol = (visibleEndX / tileSize).toInt().coerceAtMost(worldWidthCells)
    val firstRow = (visibleStartY / tileSize).toInt().coerceAtLeast(0)
    val lastRow = (visibleEndY / tileSize).toInt().coerceAtMost(worldHeightCells)

    for (col in firstCol..lastCol) {
        val x = (col * tileSize).toFloat()
        drawLine(gridColor, Offset(x, visibleStartY), Offset(x, visibleEndY), strokeWidth = 1f)
    }
    for (row in firstRow..lastRow) {
        val y = (row * tileSize).toFloat()
        drawLine(gridColor, Offset(visibleStartX, y), Offset(visibleEndX, y), strokeWidth = 1f)
    }
}
```

#### 2.4.4 预期收益

| 场景 | 改造前 | 改造后 |
|------|--------|--------|
| 静态浏览地图 | 每帧 N 次 drawImage | 1 次 drawImage（静态层）+ 少量动态 |
| 放置建筑中 | 每帧 N 次 drawImage + 网格 | 1 次 drawImage + 可见区域网格 |
| 帧时间 | 16-50ms | 8-20ms |

---

### 模块 5：Compose 重组优化

> **Strong Skipping Mode**：项目使用 Kotlin 2.0.21 + Compose 插件，已默认启用 Strong Skipping。在此模式下，不稳定参数不再污染整个 Composable——运行时会用 `equals()` 比较所有参数。因此"参数细粒度化"的边际收益降低，真正高收益的是**延迟 `.value` 读取到最深 Composable**。

#### 2.5.1 延迟 State 读取（优先）

Compose 重组的核心原则：**在需要数据的最近 Composable 中读取 State**，而非在父级读取后向下传递。

```kotlin
// ❌ 父级读取 → 父级重组 → 所有子级检查
@Composable
fun ParentView(state: State<Int>) {
    val value by state  // 父级重组
    Column {
        ChildA(value)    // 参数变了
        ChildB(value)    // 参数变了
    }
}

// ✅ 延迟读取 → 只有最需要的 Composable 重组
@Composable
fun ParentView(state: State<Int>) {
    Column {
        ChildA({ state.value })  // 读在 ChildA 内
        ChildB()                  // 不使用，永远不重组
    }
}
```

对本项目的指导：优先审查 `GameOverlayHost`（45 个 `collectAsState`）和 `WarehouseTab`（21 个）中哪些 State 可以延迟读取。

#### 2.5.2 derivedStateOf key 修正（立即修复）

当前 `MainGameScreen.kt:137`：

```kotlin
val aliveDisciples = remember(disciples) {  // ← BUG: key 每次都新引用
    derivedStateOf { disciples.filter { it.isAlive } }
}
```

**Bug 确认**：`disciples` 来自 `collectAsState()`，每次都是 Compose State 的新值。`remember(disciples)` 的 key 永远失效，`derivedStateOf` 对象每一帧都被重建。

修正：去掉 key 参数。

```kotlin
val aliveDisciples = remember {
    derivedStateOf { disciples.filter { it.isAlive } }
}
```

`derivedStateOf` 内部读取的 `disciples` 是 Compose State，自动追踪变化。

#### 2.5.3 SectInfoCard 参数优化

当前 `SectInfoCard(gameData, ...)` 传入整个 `GameData` 对象。改造为原始类型参数，配合 Strong Skipping 让 Compose 通过 `equals()` 精确判断哪些参数变了：

```kotlin
// 改造后
@Composable
fun SectInfoCard(
    sectName: String,
    gameYear: Int,
    gameMonth: Int,
    gamePhaseDisplayName: String,
    spiritStones: Long,
    discipleCount: Int,
    combatPower: Long,
    modifier: Modifier = Modifier
)
```

> ⚠️ Strong Skipping 下此优化的收益小于传统 Compose，但仍建议做——语义更清晰，测试更容易。

#### 2.5.4 Dialog 惰性订阅

当前 `GameOverlayHost` 内部可能同时订阅多个 Dialog 的状态。改造为只在 Dialog 可见时订阅：

```kotlin
@Composable
fun GameOverlayHost(viewModel: GameViewModel, ...) {
    val currentDialog by viewModel.currentDialogRoute.collectAsState()

    when (currentDialog) {
        is DialogRoute.Alchemy -> {
            val state by remember(currentDialog) {
                alchemyViewModel.uiState
            }.collectAsState()
            AlchemyDialog(state = state, ...)
        }
        // 其他 Dialog 同理
        DialogRoute.None -> { /* 无订阅 */ }
    }
}
```

#### 2.5.5 动画属性使用 graphicsLayer（新增）

地图平移/缩放、进度条、按钮缩放等纯视觉动画，使用 `Modifier.graphicsLayer` 在**绘制阶段**处理，完全跳过重组：

```kotlin
// ❌ 每帧重组
.offset(x = animatedValue.dp)

// ✅ 绘制阶段直接修改，零重组
.graphicsLayer {
    translationX = animatedValue * density
}
```

适用场景：`CameraState` 平移动画、修炼进度条、按钮 press 缩放。

#### 2.5.6 collectAsStateWithLifecycle 迁移（新增）

全代码库 139 个 `collectAsState()` 调用，**0 个** `collectAsStateWithLifecycle()`。应用切后台后所有 StateFlow 收集仍在活跃——浪费 CPU 和电池。

```kotlin
// 改造前（全代码库 139 处）
val state by viewModel.stateFlow.collectAsState()

// 改造后（Lifecycle 2.7+）
val state by viewModel.stateFlow.collectAsStateWithLifecycle()
```

> 需添加依赖 `androidx.lifecycle:lifecycle-runtime-compose:2.8.0+`

#### 2.5.7 预期收益

| 优化项 | 改造前 | 改造后 |
|--------|--------|--------|
| derivedStateOf key 修正 | 每次重组重建实例 | 仅在 disciples 值变化时重建 |
| graphicsLayer 动画 | 每帧触发重组 | **零重组**（绘制阶段） |
| collectAsStateWithLifecycle | 后台持续收集 139 个 Flow | 后台自动暂停所有收集 |
| Dialog 惰性订阅 | 始终订阅 | 不可见时零订阅 |

---

### 模块 6：构建优化

#### 2.6.1 Compose Compiler 版本对齐（立即执行）

**现状确认**：`android/app/build.gradle:135` 存在 `composeOptions { kotlinCompilerExtensionVersion = '1.5.8' }`，但项目已使用 `org.jetbrains.kotlin.plugin.compose` 插件（Kotlin 2.0.21）。此配置**冗余且可能冲突**——Kotlin 2.0+ 将 Compose Compiler 合并进 Kotlin 编译器插件，不再需要独立的 `kotlinCompilerExtensionVersion`。

```gradle
// 移除 composeOptions 块
// composeOptions {
//     kotlinCompilerExtensionVersion = '1.5.8'  // ← 删除此行
// }
```

移除后自动获得：
- **Strong Skipping Mode**（不稳定参数不污染 Composable）
- **更强的稳定性推断**（data class 自动推断为 stable）
- **编译期重组范围更精确**

> 行业验证：Compose Compiler 2.0+ 配合 Kotlin 2.0+，低端设备运行时帧时间可降低 10-20%（Google I/O 2024 数据）。

#### 2.6.2 Baseline Profile

添加 Baseline Profile 生成，加速关键路径的 AOT 编译。7 个行业公开案例均获 **20-51% 冷启动改善**，且**低端设备效果更显著**：

| 案例 | 冷启动改善 | 其他收益 |
|------|----------|---------|
| Reddit | 51%（中位数） | 冻结帧 -36%，ANR -30% |
| Duolingo | ~30% | JIT 线程占用 25%→3% |
| Meta | 最高 40% | 滚动/导航全面改善 |
| NordVPN | 24% | 登录流程快 60% |

关键路径包括：
- `GameEngineCore.tick()` → `SystemManager.onPhaseTick()`
- `GameStateStore.update()`
- `MainGameScreen` Compose 重组路径
- `SectGroundCanvas` 绘制路径

```gradle
// build.gradle
dependencies {
    implementation "androidx.profileinstaller:profileinstaller:1.4.1"
    baselineProfile project(':baselineprofile')
}
```

> 需在开发者本地真机生成 profile 后提交到仓库，CI 环境无法自动生成。

#### 2.6.3 R8 规则完善

现有 `proguard-rules.pro` 无 Compose 专用规则。建议补充：

```proguard
# Compose
-keepclassmembers class * extends androidx.compose.runtime.Composer
-dontwarn androidx.compose.**

# StateFlow
-keepclassmembers class * extends kotlinx.coroutines.flow.StateFlow {
    ** getValue();
}
```

> 现有规则已覆盖 Kotlin Coroutines / Room / Protobuf / Gson / Hilt / MMKV，补充量较小。

#### ~~2.6.4 android:largeHeap~~（删除）

> ❌ **已删除此项**。`AndroidManifest.xml:49` 早已设置 `android:largeHeap="true"`，无需新增。
>
> 更重要的：Google 官方建议**避免**在低配设备使用 `largeHeap`——会增加 GC 停顿时间、提高被 LMK 杀掉的概率，且在 4GB 设备上**不保证增加堆上限**。正确的方向是通过对象池减少分配，而非扩大堆空间。

#### 2.6.5 设备分级内存目标（新增）

替代 `largeHeap` 的正确做法——基于 2025-2026 年最新设备分布数据制定分级策略。

**数据来源**：AnTuTu 2025 Q4 实测（12GB+ 占 84.4%）+ TrendForce 2026 DRAM 涨价预测（低端退回 4GB，中端退回 6-8GB）。

| 设备等级 | RAM | 2025 市占 | 2026 趋势 | 内存目标 | 帧率目标 | 策略 |
|----------|-----|----------|----------|---------|---------|------|
| 低配 | 4GB | ~15-20% | ⬆ DRAM 涨价导致回升 | < 120MB | 锁定 30fps | 关闭离屏缓冲、建筑贴图降分辨率、减少粒子特效 |
| 标准 | 6-8GB | ~15-20% | ⬆ 12GB 降配至此区间 | < 200MB | 稳定 30fps | 适度离屏缓冲、RGB_565 贴图格式 |
| 高配 | 12GB | ~42% | ⬇ 部分降配 | < 400MB | 60fps | 全部优化开启 |
| 旗舰 | 16GB+ | ~43% | ⬇ DRAM 涨价导致减少 | < 500MB | 60-120fps | 全部开启 + 高分辨率贴图 |

> **设计原则**：腾讯 TGPA 标准「稳定帧率 > 峰值帧率，内存预算按等级递减」。本项目为 2D 模拟经营，内存目标显著低于 3D MMO（王者荣耀低端 800MB → 本项目低端 120MB）。
>
> `DynamicMemoryManager` 已实现设备等级检测（LOW/MEDIUM/HIGH/ULTRA），可直接在此基础上配置策略。注意其现有 ULTRA 等级对应的可能是 16GB+ 旗舰。

#### 2.6.6 预期收益

| 优化项 | 收益 |
|--------|------|
| Compose Compiler 对齐 | Kotlin 2.0 原生编译器，Strong Skipping + 稳定性推断 |
| Baseline Profile | 冷启动快 20-40%，运行时帧时间降低 10-15% |
| R8 规则 | APK 体积减小，运行时反射开销降低 |
| 设备分级 | 低配不超预算，高配物尽其用 |

---

### 模块 7：GCOptimizer 改造

#### 2.7.1 问题

当前 `GCOptimizer.performGC()` 直接调用 `System.gc()`，在 ART 上可能触发 stop-the-world，低配设备上可能导致 50-100ms 卡顿。行业对标：腾讯 TGPA 体系通过对象池 + 逻辑分帧 + 大小核绑定控制 GC 压力，而非依赖 `System.gc()`。

#### 2.7.2 改造方案

移除主动 `System.gc()` 调用（SOFT/HARD 级别），改为被动式内存管理。注意：`CacheLayer` 和 `ObjectPoolRegistry` 为新增概念，需从零构建。

```kotlin
@Singleton
class GCOptimizer @Inject constructor(
    private val memoryMonitor: MemoryMonitor,
    private val dynamicMemoryManager: DynamicMemoryManager,  // 已有
    private val applicationScopeProvider: ApplicationScopeProvider
) {
    fun performGC(type: GCType): GCOptimizationResult {
        val memoryBefore = memoryMonitor.getCurrentMemoryInfo()?.usedPercent ?: 0.0

        when (type) {
            GCType.SOFT -> {
                // 清空非必要缓存（需实现 CacheLayer）
                // CacheLayer.evictColdEntries()
            }
            GCType.HARD -> {
                // 缩减对象池 + 清空缓存（需实现 ObjectPoolRegistry）
                // ObjectPoolRegistry.shrinkAll()
                // CacheLayer.evictAll()
            }
            GCType.CRITICAL -> {
                // 紧急清理 + 建议 VM 回收
                // ObjectPoolRegistry.clearAll()
                // CacheLayer.clearAll()
                // 仅在 CRITICAL 和 MANUAL 时建议 GC
                Runtime.getRuntime().gc()
            }
            GCType.MANUAL -> {
                Runtime.getRuntime().gc()
            }
            GCType.NONE -> {}
        }
        // ... 返回值
    }
}
```

#### 2.7.3 预期收益

- SOFT/HARD 级别不再触发 stop-the-world GC
- 低配设备上 GC 停顿从 50-100ms 降为 < 5ms（缓存清理是微秒级操作）

---

## 3. 实施计划

### 阶段 1：立即执行 — 零风险确定性收益（1 天）

| 任务 | 模块 | 风险 | 验证方式 |
|------|------|------|---------|
| Compose Compiler 版本对齐（移除 `kotlinCompilerExtensionVersion`） | 模块 6 | **零** | 编译通过 + `*-composables.txt` 确认 Strong Skipping |
| `derivedStateOf` key 修正（去掉 `remember(disciples)` 的 key） | 模块 5 | **零** | Compose Layout Inspector 确认不再重建 |
| 网格线视口裁剪（线跨全图 → 仅可见范围） | 模块 4 | **零** | 帧时间对比 |
| 删除方案中的 `largeHeap` 新增建议（已存在 + 不适合低配） | 模块 6 | **零** | 确认 manifest 已有，移除方案中的新增建议 |

### 阶段 2：低风险 — Profile 验证后执行（2-3 天）

| 任务 | 模块 | 风险 | 验证方式 |
|------|------|------|---------|
| Baseline Profile 生成 | 模块 6 | 低 | 冷启动时间对比（目标 20-30% 改善） |
| `MapCanvas` Path 缓存（120-200 Path/帧 → 缓存复用） | 模块 3 | 低 | Allocation Tracker 确认 Path 分配减少 |
| `collectAsStateWithLifecycle()` 迁移 | 模块 5 | 低 | 切后台后 CPU trace 确认收集暂停 |
| GCOptimizer 减少 `System.gc()`（SOFT/HARD 移除） | 模块 7 | 低 | GC 停顿时间对比（目标 < 10ms） |
| `graphicsLayer` 用于地图平移/按钮动画 | 模块 5 | 低 | Compose Layout Inspector 确认零重组 |
| Dialog 惰性订阅 | 模块 5 | 低 | Compose Layout Inspector |

### 阶段 3：中等风险 — 增量派发 + 脏标记双保险（4-6 天）

| 任务 | 模块 | 风险 | 验证方式 |
|------|------|------|---------|
| 状态分层：新增 3 个子 StateFlow + update() 增量 emit | 模块 1 步骤 1-2 | 中 | 单元测试（新旧对比逻辑）+ 集成测试（全功能回归） |
| 脏标记系统：MutableGameState setter 标记 + 子流内字段级跳过 | 模块 2 | 中 | 单元测试（标记正确性）+ 性能对比（子流内 .map{} 执行次数） |
| Canvas 建筑静态层（扩展现有 `fullMapBmp` + graphicsLayer Offscreen） | 模块 4 | 中 | 帧时间 + 视觉回归 + 低配内存 |
| ViewModel Flow 迁移（从 `_state.map{}` 改到从子流 `.map{}`） | 模块 1 步骤 3 | 中 | 逐个 ViewModel 迁移 + Compose Layout Inspector |
| `SectInfoCard` 参数细粒度化 | 模块 5 | 低 | Compose Layout Inspector（Strong Skipping 下收益需验证） |
| `DiscipleAggregate` 缓存（先 Profiler 确认分配量） | 模块 3 | 低 | Allocation Tracker |
| 设备分级内存目标（基于 `DynamicMemoryManager`） | 模块 6 | 低 | 各等级设备内存监控 |

### 阶段 4：渐进迁移 — UI 层消费子流（按需推进）

| 任务 | 模块 | 风险 | 触发条件 |
|------|------|------|---------|
| UI 消费者逐步迁移到直接订子流 | 模块 1 步骤 4 | 中 | 逐个 Composable 迁移，每次迁移后功能回归 |
| 移除旧 `_state` 依赖 | 模块 1 步骤 5 | 中 | 所有消费者迁移完毕 |

---

## 4. 验证指标

### 4.1 性能基准

每个阶段完成后采集数据。分级标准参考腾讯 TGPA 体系 + 本项目实际复杂度：

| 设备等级 | RAM | 代表设备 | 帧率目标 | 帧时间 P95 | 内存目标 | GC 停顿 P95 | 冷启动 |
|----------|-----|---------|---------|-----------|---------|-----------|--------|
| 低配 | 4GB | Redmi 14C, OPPO A3 | 锁定 30fps | < 20ms | < 120MB | < 15ms | < 3.5s |
| 标准 | 6-8GB | Redmi Note 14, vivo Y100 | 稳定 30fps | < 12ms | < 200MB | < 10ms | < 2.5s |
| 高配 | 12GB | Xiaomi 15, OnePlus 13 | 60fps | < 10ms | < 400MB | < 5ms | < 2s |
| 旗舰 | 16GB+ | Samsung S25 Ultra, 游戏手机 | 60-120fps | < 8ms | < 500MB | < 5ms | < 1.5s |

> 数据依据：AnTuTu 2025 Q4 — 12GB+ 占 84.4%（16GB 42.9% + 12GB 41.6%）。2026 TrendForce 预测低端退至 4GB、中端退至 6-8GB。本游戏为 2D 模拟经营类，复杂度远低于 3D MMO/MOBA（腾讯王者荣耀低端目标 800MB）。

### 4.2 采集方式

已有基础设施：
- `UnifiedPerformanceMonitor` — tick 耗时、帧时间（Choreographer.FrameCallback）、内存、FPS
- `MemoryMonitor` — 内存快照、低内存标志
- `DynamicMemoryManager` — 设备等级检测

需新增采集：
```kotlin
// Compose 重组计数（需 Compose Compiler Metrics 或自定义 RecomposeLogger）
unifiedPerformanceMonitor.recordRecompositionCount(tag)

// 内存分配量（Debug.MemoryInfo 或 Allocation Tracker）
unifiedPerformanceMonitor.recordMemoryAllocation(bytes)
```

### 4.3 通过标准

| 指标 | 低配 (4GB) | 标准 (6-8GB) | 高配 (12GB) | 旗舰 (16GB+) |
|------|-----------|-------------|------------|-------------|
| 帧时间 P95 | < 20ms | < 12ms | < 10ms | < 8ms |
| 重组次数/秒 | < 10 | < 8 | < 5 | < 5 |
| GC 停顿 P95 | < 15ms | < 10ms | < 5ms | < 5ms |
| 内存占用 | < 120MB | < 200MB | < 400MB | < 500MB |
| 冷启动 | < 3.5s | < 2.5s | < 2s | < 1.5s |
| 1h 运行内存增长 | < 10% | < 10% | < 5% | < 5% |

---

## 5. 风险与缓解

| 风险 | 影响 | 概率 | 缓解措施 |
|------|------|------|---------|
| 状态分层后 `combine` 派生 `unifiedState` 破坏 `===` 身份检查 | 6 处身份检查失效，UI 可能异常 | 高 | 见模块 1 已知风险：改为 `==` 或直接订阅分层 Flow |
| 离屏缓冲内存增加（ARGB_8888 = 36MB/层） | 低配设备 OOM | 中 | 设备分级自动关闭；考虑 RGB_565（18MB/层） |
| 脏标记遗漏导致 UI 不更新 | 功能 bug | 中 | 保留 `distinctUntilChanged` 作为安全网 + setter 标记统一在 MutableGameState 基类实现 + 单元测试覆盖每个 setter |
| Compose Compiler 移除 `kotlinCompilerExtensionVersion` 导致编译失败 | 阻塞开发 | 低 | 已有 Compose 插件，移除的是冗余配置；独立分支验证 |
| Baseline Profile 生成需要真机 | CI 环境限制 | 中 | 在开发者本地设备生成后提交；考虑 Cloud Profiles（Google Play 自动生成） |
| `graphicsLayer(Offscreen)` 导致 GPU 内存增加 | 低配设备显存不足 | 中 | 低配设备使用 Bitmap 方案而非 GPU 纹理 |
| 设备分级未覆盖部分机型 | 特定机型性能差 | 低 | 基于 RAM 分级 + `DynamicMemoryManager` 已有实现 |

---

## 6. 行业对标

### 6.1 性能标准对标（腾讯 TGPA）

腾讯游戏性能优化体系覆盖 30+ 产品（王者荣耀、和平精英、CODM），核心标准：

| 维度 | 腾讯做法 | 本项目对标 |
|------|---------|-----------|
| **设备分级** | 4 档（4GB/6-8GB/12GB/16GB+），各有帧率/内存/DrawCall 目标 | 4 档（4GB/6-8GB/12GB/16GB+），见模块 6 |
| **GC 控制** | 对象池 + 逻辑分帧 + TGPA 大小核绑定，**不依赖 System.gc()** | 模块 7 减少主动 GC，模块 3 对象池 |
| **内存目标** | 低端 <=800MB，高端 <=1.8GB（3D MMO） | <150MB（2D 模拟经营，远低于 3D 标准） |
| **帧率稳定性 > 峰值** | 稳定 30fps 优于 30-60fps 波动 | 锁定 30fps（低配）/ 60fps（高配） |
| **Overdraw 控制** | 全屏平均 <=2.5 层 | Canvas 分层渲染（模块 4） |

### 6.2 技术方案对标

| 优化手段 | 本项目 | 米哈游（原神/星铁） | 腾讯手游 | 行业共识 |
|----------|--------|-------------------|---------|---------|
| **状态分层** | 单一 StateFlow → 按频率分层（待评估） | Unity ECS Component 粒度变更追踪 | MVVM + LiveData 分层，高频走 Channel | 按变更频率分层是通用模式 |
| **对象池** | `reusableMutableState`（已有）+ Path 缓存 | Unity ObjectPool（特效、子弹） | C++ 层对象池（腾讯自研引擎） | 移动游戏标配 |
| **离屏缓冲** | `fullMapBmp`（已有）+ graphicsLayer Offscreen | GPU RenderTexture 多 Pass | SurfaceView 双缓冲 | 静态内容缓存为纹理 |
| **Compose 优化** | derivedStateOf + 延迟读取 + graphicsLayer | N/A（使用 Unity UGUI） | N/A | Strong Skipping + 延迟读取 |
| **Baseline Profile** | 新增（行业平均 20-40% 冷启动改善） | 已有（Unity IL2CPP AOT） | 已有 | Google 推荐，低端收益更大 |
| **设备分级** | 新增（基于已有 DynamicMemoryManager） | 3 档画质 | 4 档画质 + TGPA 软硬协同 | 必须，低配不能套用高配配置 |

### 6.3 关键行业数据

| 来源 | 数据点 | 对本项目的参考 |
|------|--------|-------------|
| Reddit + Baseline Profile + R8 | 冷启动中位数改善 51% | 目标 30% 合理 |
| Duolingo + Baseline Profile | JIT 线程占比 25%→3% | 运行时收益可能比冷启动更大 |
| 腾讯 TGPA | 低帧率降低 10-40%，加载缩短 15-30% | 软硬协同在自研引擎上效果显著，本项目通过 Compose 优化等效 |
| Google Compose 官方 | Strong Skipping 使不稳定参数不再污染 Composable | 参数细粒度化边际收益降低 |
| Google Compose 官方 | `graphicsLayer` 在绘制阶段处理，零重组 | 地图平移/动画首选方案 |
| Kotlinlang (romainguy) | `graphicsLayer(Offscreen)` 栅格化为 GPU 纹理后续复用 | 替代手动 Bitmap 缓存的更简方案 |

### 6.4 本项目特殊性

与对标产品的关键差异决定了优化策略不同：

| 差异 | 影响 |
|------|------|
| **2D 模拟经营 vs 3D MMO/MOBA** | 渲染复杂度低得多，内存目标远低于腾讯标准 |
| **Kotlin/Compose vs Unity/Unreal** | 无法直接套用 C++/Unity 层优化，但有 Compose 专用工具（Strong Skipping、graphicsLayer） |
| **tick 1000ms vs 游戏 16ms 帧循环** | tick 驱动重组的压力远小于 3D 游戏的逐帧更新——脏标记系统大概率不需要 |
| **单机为主 vs 强联网** | 无网络延迟和同步压力，性能瓶颈集中在本地 tick + Canvas 绘制 |

---

## 7. 新增：行业标准优化补充

以下为行业标准做法但原方案未覆盖的优化项，评估后已纳入本 v2 方案：

| 优化项 | 纳入位置 | 行业依据 | 收益估计 |
|--------|---------|---------|---------|
| `snapshotFlow` for 高频状态 | 待后续专项 | 王者荣耀：高频状态走 Channel 非 StateFlow | 修炼进度条等逐帧动画零重组 |
| `graphicsLayer` for 动画 | 模块 5.5 | Google 官方 Compose 性能指南 | 地图平移/缩放/按钮动画零重组 |
| `graphicsLayer(Offscreen)` for 静态层 | 模块 4 | Google (romainguy)：栅格化为 GPU 纹理复用 | 比手动 Bitmap 更简单 |
| `collectAsStateWithLifecycle()` | 模块 5.6 | Google 官方最佳实践 | 后台自动暂停 139 个收集器 |
| 设备分级目标 | 模块 6.5 | 腾讯 TGPA 4 级标准 | 低配不超预算，高配物尽其用 |
| Compose Compiler Metrics | 待后续专项 | Google 官方工具 | `*-composables.txt` 找非 skippable |
| `FrameMetricsAggregator` | 待后续专项 | 腾讯 PerfDog 标准 | 三阶段帧时间（重组/布局/绘制） |

---

## 8. 新增：代码库已有优化清单

以下为代码库中已存在的性能优化（来源：changelog + Explore Agent 扫描），方案不再重复：

| 已有优化 | 版本/位置 | 说明 |
|----------|----------|------|
| `reusableMutableState` 单例复用 | `GameStateStore.kt:456` | 每次 `update()` 避免分配新 `MutableGameState` |
| 地形完整 Bitmap 预渲染 | `GameActivity.kt:184-214` | 48×48×64px → 单张 `fullMapBmp`，1 次 `drawImage` |
| 自适应 tick 间隔 | `GameEngineCore.kt:60-65` | 超时 1.5x 扩大至 2000ms，正常后 0.8x 恢复 |
| StateFlow 优化（移除冗余 stateIn） | v3.1.70 | ViewModel 19 个 passthrough Flow 改为 `get()` 委托 |
| 移除 replayExpirationMillis | v3.1.72 | 修复后台 >35s 白屏 |
| tick 频率下调 | v3.1.76 | 200ms → 1000ms |
| `@Immutable` 注解 | v3.1.80 | GameData、DiscipleAggregate 等 11 个核心类 |
| `@Stable` 注解 | `MapCameraState.kt:19` | 避免相机状态触发无关重组 |
| derivedStateOf for alive disciples | v3.1.87 | 减少存活弟子筛选计算 |
| Background task scheduler 精简 | v3.1.81 | 13 协程 → 4 协程 |
| ProtoBuf `encodeDefaults=false` | v3.1.65 | 序列化体积优化 |
| `android:largeHeap="true"` | `AndroidManifest.xml:49` | 已存在（但不建议用于低配设备） |
