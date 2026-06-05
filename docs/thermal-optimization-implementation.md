# 游戏发热优化 — 实施操作文档

> 方案依据：[行业调研报告](./thermal-research-sources.md) | 架构背景：[CLAUDE.md](../CLAUDE.md)

## 硬约束

1. **焦点域（玩家当前看的 Tab）必须保持 100ms tick** — 体验底线
2. **游戏时间 2秒/旬、6秒/月恒定不变** — TimeSystem 始终运行
3. **所有优化减的是"每 tick 做了多少事"，不是"tick 多快"**

---

## 第一阶段：机械性去重（预计 5h，低风险）

### 任务 1.1：移除 focusedRefreshJob 双重刷新

**文件**：`android/app/src/main/java/com/xianxia/sect/ui/game/GameViewModel.kt`

**现状**：第 456-461 行有一个独立的 `focusedRefreshJob` 协程，100ms 循环调用 `discipleFacade.updateFocusedDisciple()`。主循环 `GameEngineCore.tick()` 的同一次 100ms tick 中 `processPhaseTick()` 已经处理了该弟子。这是双倍工作。

**改动**：删除 `focusedRefreshJob` 协程及其 `focusedRefreshJob?.cancel()` 调用。

**兜底**：弟子详情 UI 已通过 `highFreqState` 流订阅弟子数据，主 tick 处理完会自动反映到 UI。如果担心 UI 刷新不及时，将 UI 层的 `sample(400)` 临时改为 `sample(100)` 确保每 tick 都刷新。

**验证**：打开任意弟子详情，观察修炼进度条是否仍然平滑推进（改为依赖主 tick 后应该完全一致）。

---

### 任务 1.2：processPhaseTick 去重 — 地图提到循环外

**文件**：`android/app/src/main/java/com/xianxia/sect/core/engine/service/CultivationService.kt`

**现状**：`processPhaseTick()` 的 `currentDisciples.map{}` 循环内，每次迭代都重新构建 `equipmentMap`、`manualMap` 等映射结构。

**改动**：

```kotlin
// === 改前 ===
val result = currentDisciples.map { disciple ->
    val equipmentMap = ...  // 每名弟子都构建一次
    val manualMap = ...     // 每名弟子都构建一次
    // ...处理逻辑
}

// === 改后 ===
// 1. 一次性构建
val equipmentMap = gameData.equipmentInstances.associateBy { it.instanceId }
val manualMap = gameData.manualInstances.associateBy { it.instanceId }
val proficienciesMap = gameData.manualProficiencies

// 2. 提前过滤
val aliveDisciples = currentDisciples.filter { it.isAlive }

// 3. 统一累加器
val acc = PhaseTickAccumulator()

val result = aliveDisciples.map { disciple ->
    processDisciplineTick(disciple, equipmentMap, manualMap, proficienciesMap, acc)
}
// 4. 一次性应用副作用
applyAccumulator(acc)
```

**新增**：定义 `PhaseTickAccumulator` 数据类，合并分散的 11+ 个 `MutableList`/`MutableMap`。

**验证**：编译通过后运行游戏，确认弟子修炼、自动用药、自动装备行为不变。

---

### 任务 1.3：WAL 刷新间隔 — 热事件下延长

**文件**：`android/app/src/main/java/com/xianxia/sect/data/wal/FunctionalWAL.kt`

**改动**：`startFlushTimer()` 方法中，将固定的 `delay(WAL_FLUSH_INTERVAL_MS)` 改为动态：

```kotlin
val interval = when {
    thermalMonitor.shouldEmergencySave() -> 5000L
    thermalMonitor.shouldReduceWorkload() -> 3000L
    else -> StorageConstants.WAL_FLUSH_INTERVAL_MS
}
delay(interval)
```

同样在 `checkpoint()` 逻辑中，热事件时跳过检查点。

**注意**：`ThermalMonitor` 已存在于 DI 容器，通过构造函数注入或从 `GameEngineCore` 获取。

**验证**：热事件触发时（`shouldReduceWorkload() == true`），观察 WAL 刷新日志频率下降。

---

### 任务 1.4：空闲检测 — 保留 tick 但停非必要系统

**文件**：`android/app/src/main/java/com/xianxia/sect/core/engine/GameEngineCore.kt`

**改动**：

```kotlin
// 1. 收紧空闲阈值：IDLE_DETECTION_MS 从 60_000 改为 30_000
private const val IDLE_DETECTION_MS = 30_000L

// 2. 空闲时：不降 tick 间隔，改降焦点域
private fun handleIdleState() {
    if (idleTimeMs > IDLE_DETECTION_MS && currentFocusDomain != FocusDomain.BACKGROUND) {
        previousFocusDomain = currentFocusDomain
        currentFocusDomain = FocusDomain.BACKGROUND  // 所有系统按 30s 频率执行
        // TimeSystem 不受影响（ALWAYS 域）→ 时间流速不变
    }
}

// 3. 用户交互时恢复
override fun onUserInteraction() {
    if (currentFocusDomain == FocusDomain.BACKGROUND && previousFocusDomain != null) {
        currentFocusDomain = previousFocusDomain  // 恢复焦点域
    }
    lastInteractionTime = System.currentTimeMillis()
}
```

**验证**：30s 不操作后观察 tick 仍为 100ms 间隔但 CPU 负载降低。触屏后立即恢复。

---

## 第二阶段：事件驱动惰性求值（预计 20-25h，中风险）

> 这是核心优化。思路：每种耗时操作存一个 `completionMonth` + `completionPhase`，不到时间就跳过。焦点域强制立即结算。

### 任务 2.1：数据模型加字段

**涉及文件**（按实体逐一添加）：

| 实体类 | 文件 | 新增字段 |
|--------|------|---------|
| `Disciple` | `core/model/Disciple.kt` | `cultivationCompletionMonth: Int`, `cultivationCompletionPhase: Int`, `manualCompletionMonth`, `manualCompletionPhase`, `equipmentNurturingCompletionMonth`, `equipmentNurturingCompletionPhase` |
| `GridBuildingData` 或 `ProductionSlot` | 对应的生产槽位模型 | `completionMonth: Int`, `completionPhase: Int` |
| `SpiritFieldPlant` | `core/model/` | `completionMonth: Int`, `completionPhase: Int` |
| `AISectDisciple` | AI 弟子模型 | `cultivationCompletionMonth: Int`, `cultivationCompletionPhase: Int` |

**默认值**：新字段全部给 `0`（0 月 = 立即结算一次，初始化时计算为正确值）。

**⚠️ 关键**：修改 `@Entity` 类 → 必须加 DB Migration！详见 `rules/database-migration.md`。用 `ALTER TABLE ADD COLUMN`（SQLite 3.25+ 支持，minSdk 24 设备 Ok）。

---

### 任务 2.2：编写 LazyEvaluationDispatcher

**新建文件**：`android/app/src/main/java/com/xianxia/sect/core/engine/LazyEvaluationDispatcher.kt`

```kotlin
/**
 * 事件驱动惰性求值调度器。
 * - completionMonth: 预期完成的游戏月份（绝对编号，如 year*12+month）
 * - completionPhase: 预期完成的旬（1=上旬, 2=中旬, 3=下旬）
 * 
 * 规则（非焦点域）：
 *   currentMonth < completionMonth → 跳过
 *   currentMonth == completionMonth && currentPhase < completionPhase → 跳过
 *   否则 → 执行结算
 * 
 * 规则（焦点域）：
 *   无视上述规则，强制立即结算
 */
class LazyEvaluationDispatcher(
    private val stateStore: GameStateStore,
    private val thermalMonitor: ThermalMonitor
) {
    /** 判断实体是否应该在本旬结算 */
    fun shouldSettle(completionMonth: Int, completionPhase: Int,
                     currentMonth: Int, currentPhase: Int,
                     isInFocusDomain: Boolean): Boolean {
        if (isInFocusDomain) return true  // 焦点域强制
        if (currentMonth > completionMonth) return true  // 过期兜底
        if (currentMonth == completionMonth && currentPhase >= completionPhase) return true
        return false
    }
    // ...各系统专用结算方法
}
```

---

### 任务 2.3：各系统分旬调度表

每旬到来时（`TimeSystem.phase` = 1/2/3），按以下优先级处理：

```
上旬 (Phase 1)：焦点域 + 玩家弟子修炼 + 功法熟练度 + 装备孕养 + 任务
中旬 (Phase 2)：焦点域 + 锻造 + 炼丹 + 血炼池
下旬 (Phase 3)：焦点域 + 种植 + 灵矿 + AI 弟子修炼
```

**改动方式**：在 `SystemManager.onPhaseTickWithDomainFilter()` 中，调用各系统前增加 `shouldSettle()` 判断。焦点域系统不受影响。

**具体修改点**：

| 系统 | 文件 | 结算旬 | 改动 |
|------|------|--------|------|
| `CultivationTickSystem` | `core/engine/system/CultivationTickSystem.kt` | 上旬 | `processDisciple()` 前检查 `shouldSettle(cultivationCompletionMonth, cultivationCompletionPhase, ...)` |
| `InventorySystem`（功法/装备） | `core/engine/system/InventorySystem.kt` | 上旬 | 功法熟练度升级、装备孕养推进前检查 |
| `ProductionSubsystem`（锻造/炼丹/血炼） | `core/engine/domain/production/ProductionSubsystem.kt` | 中旬 | `processBuildingProduction()` 中检查每个槽位 |
| `ProductionSubsystem`（种植/灵矿） | 同上 | 下旬 | `processHerbGardenGrowth()`、`processSpiritMineProduction()` |
| AI 弟子系统 | AI 弟子相关 | 下旬 | 月度修炼推进前检查 |

---

### 任务 2.4：结算完成后计算下一个 completionMonth + phase

每个实体结算完成后，立即计算下一次：

```kotlin
// 示例：修炼结算
fun Disciple.withNextCultivationCompletion(currentMonth: Int): Disciple {
    val monthsToNext = estimateMonthsToNextBreakthrough(this)
    return copy(
        cultivationCompletionMonth = currentMonth + monthsToNext,
        cultivationCompletionPhase = 1  // 修炼始终上旬
    )
}

// 示例：锻造结算  
fun ProductionSlot.withNextCompletion(currentMonth: Int): ProductionSlot {
    val monthsForNextRecipe = getRecipeMonths(nextRecipe)
    return copy(
        completionMonth = currentMonth + monthsForNextRecipe,
        completionPhase = 2  // 锻造始终中旬
    )
}
```

**估算逻辑复用现有公式**：`estimateMonthsToNextBreakthrough()` 来自 `DiscipleStatCalculator` 的修炼速率公式。

---

### 任务 2.5：焦点域联动

**不改动现有 `FocusDomain` 枚举和切换逻辑**。在 `LazyEvaluationDispatcher.shouldSettle()` 中：

```kotlin
fun isInFocusDomain(entity: Any, currentFocusDomain: FocusDomain): Boolean = when (currentFocusDomain) {
    FocusDomain.DISCIPLES -> entity is Disciple  // 弟子 Tab → 所有弟子强制结算
    FocusDomain.BUILDINGS -> entity is ProductionSlot || entity is SpiritFieldPlant
    FocusDomain.EXPLORATION -> entity is CultivatorCave
    else -> false
}
```

玩家切 Tab = 自动触发强制结算，切走 = 自动恢复惰性求值。

---

### 任务 2.6：热状态联动

当 `thermalMonitor.shouldReduceWorkload()` 时，`shouldSettle()` 增加更严格的条件：

```kotlin
// 发热时：仅上旬结算的实体才处理，中旬下旬延迟到下月
if (thermalMonitor.shouldReduceWorkload() && !isInFocusDomain) {
    if (completionPhase > 1) return false  // 中旬下旬全部跳过
}
```

**不影响焦点域**。

---

### 任务 2.7：StateFlow 批处理

**文件**：`android/app/src/main/java/com/xianxia/sect/core/state/GameStateStore.kt`

**现状**：`combine` 17 个流 → 一次 `update()` 修改 3-4 字段发射 3-4 次。

**改动**：

```kotlin
// 1. 新增版本计数器
private val _updateVersion = MutableStateFlow(0L)

// 2. update() 末尾递增
fun update(block: (MutableGameState) -> Unit) {
    mutex.withLock {
        // ...existing update logic...
        _updateVersion.value++  // 仅一次递增
    }
}

// 3. unifiedState 改为按版本触发
val unifiedState: StateFlow<UnifiedGameState> = _updateVersion
    .sample(50)  // 50ms 内多次更新合并为一次
    .map { buildUnifiedState() }
    .stateIn(scope, SharingStarted.Eagerly, initialState)
```

**重要**：战斗结果等即时通知走 `eventBus`，不依赖 `unifiedState` 触发。

---

## 第三阶段：进阶（预计 25-30h，中高风险）

### 任务 3.1：专用游戏线程

**文件**：`android/app/src/main/java/com/xianxia/sect/core/engine/GameEngineCore.kt`

```kotlin
// 改前：engineScope = CoroutineScope(engineJob + Dispatchers.Default)
// 改后：
private val gameDispatcher = Executors.newSingleThreadExecutor { r ->
    Thread(r, "GameLoop").apply { 
        priority = Thread.NORM_PRIORITY 
    }
}.asCoroutineDispatcher()
engineScope = CoroutineScope(engineJob + gameDispatcher)
```

### 任务 3.2：Performance Hint API（API 31+）

**新建**：`android/app/src/main/java/com/xianxia/sect/core/perf/PerformanceHintManager.kt`

```kotlin
@RequiresApi(31)
class PerformanceHintManager(context: Context) {
    private var session: HintSession? = null
    
    fun start(targetWorkDurationNanos: Long) {
        val manager = context.getSystemService(PerformanceHintManager::class.java)
        session = manager.createHintSession(intArrayOf(Process.myTid()), targetWorkDurationNanos)
    }
    
    fun reportActual(actualDurationNanos: Long) {
        session?.reportActualWorkDuration(actualDurationNanos)
    }
    
    fun close() { session?.close() }
}
```

在 `GameEngineCore.tick()` 中：tick 开始取时间戳 → tick 结束 `reportActual(tickDurationNanos)`。

### 任务 3.3：修炼计算微批次 yield

**文件**：`android/app/src/main/java/com/xianxia/sect/core/engine/service/CultivationService.kt`

将聚焦域的全量弟子遍历拆为每批 50 人：

```kotlin
disciples.chunked(50).forEach { batch ->
    batch.forEach { processDisciple(it) }
    yield()  // 让出 CPU 给 Compose 渲染
    if (thermalMonitor.shouldReduceWorkload()) {
        delay(5)  // 发热时批次间暂停
    }
}
```

### 任务 3.4：Canvas 地图自适应分辨率

**文件**：Canvas 地图渲染（`MainGameScreen` 地图 Composable）

热余量 < 0.3 或帧率 < 30fps 连续 3 帧时：
1. 烘焙位图降至 75% 分辨率
2. 热度持续上升 → 降至 50%
3. 温度恢复后逐级恢复

---

## 验证清单

每阶段完成后执行：

```bash
# 1. 编译
cd android && ./gradlew.bat compileReleaseKotlin

# 2. 单元测试
cd android && ./gradlew.bat test

# 3. 真机测试（10 分钟连续游玩）
# 监控指标（已有 UnifiedPerformanceMonitor）：
#   - avgTickTimeMs < 25ms（当前 ~45ms）
#   - maxTickTimeMs < 80ms
#   - Compose 重组计数（Layout Inspector）
#   - 10min 温升 < 3°C（adb shell dumpsys battery）
```

### 正确性回归测试

- [ ] 弟子修炼速度不变（对比优化前后同一弟子日进度）
- [ ] 锻造/炼丹/种植完成时间不变
- [ ] 焦点域切 Tab 后数据立即刷新
- [ ] 切换存档、新建游戏正常
- [ ] 月度/年度结算全部正确触发
- [ ] AI 宗门弟子修炼不受影响

---

## DB Migration 注意事项

修改 `@Entity` 类新增字段时：

1. 在 `GameDatabase` 中新增 Migration（版本号 +1）
2. 使用 `ALTER TABLE ADD COLUMN`（SQLite 3.25+，minSdk 24 支持）
3. 默认值设为 0（首次加载时会立即结算一次并计算正确的 completionMonth）
4. **禁止使用 `DROP COLUMN`**（用 `safeDropColumns()` 代替）

```
// 示例 Migration
val MIGRATION_X_TO_Y = object : Migration(X, Y) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE disciples ADD COLUMN cultivationCompletionMonth INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE disciples ADD COLUMN cultivationCompletionPhase INTEGER NOT NULL DEFAULT 1")
        // ...其他字段
    }
}
```
