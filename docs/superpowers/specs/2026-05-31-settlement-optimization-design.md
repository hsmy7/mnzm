# 月度/年度结算性能优化设计（行业对标改进版）

> 行业参考：放置游戏 FIFO 批处理队列、DCS World 时间预算分批调度、RimWorld 多频率 TickManager、Dwarf Fortress 模拟渲染解耦、Paradox CK3 缓存失效机制、Data-Oriented Design 脏标记模式

## 设计前提：无离线机制

游戏**无离线追赶**。后台时 `pauseForBackground()` → `isPaused=true` → 游戏循环暂停。前台恢复时直接重启循环，不补算离线时间。

对优化方案的影响：
- 不需要处理离线批量追赶（不会出现跨越数年的大量结算）
- 结算量上限可预测：最坏情况 = 玩家连续在线期间的最大 tick 数
- 分帧结算期间如果玩家切后台 → 调用 `cancelPendingWork()` 丢弃中间状态，等 resume 后重算

---

## 问题分析

### 当前架构（改造前）

```
GameEngineCore.tick() [1000ms 间隔]
  └─ stateStore.update {
       systemManager.onPhaseTick(this)       // 每旬
       if (月份切换) systemManager.onMonthTick(this)  // 16个系统同步执行
       if (年份切换) systemManager.onYearTick(this)   // 月度+年度叠加
     }
```

> **注意**：代码中 `settlement/` 包已有部分骨架代码，但并不完整。以下方案描述的是完成改造后的目标架构。

### 性能瓶颈

| 瓶颈点 | 文件位置 | 问题 |
|--------|----------|------|
| 月度修炼批量更新 | CultivationService.kt:268-349 | 遍历全体弟子 `.map {}`，每弟子计算修炼+功法+孕养，`associateBy` 重建索引 |
| 月度突破检查 | CultivationService.kt:354-358 | 筛选+遍历，突破逻辑含 while 循环+丹药消耗+天赋计算 |
| 薪水发放 | CultivationService.kt:1887-1936 | 两次遍历全体弟子，多次 `currentDisciples =` 赋值 |
| 生产子系统 | ProductionSubsystem.kt:34-43 | 月度一次性调用 8 个生产函数 |
| 年度事件 | CultivationService.kt:1158-1194 | 年度=月度+12个额外函数，尤其老化+死亡处理 |
| 所有系统同频执行 | SystemManager | 外交事件（12%概率触发）每月检查、世界等级每6月才需生成却每月调度 |
| 状态写入模式 | 全局 | 每个系统独立读写 `currentDisciples`，多次触发 StateFlow 发射 |

### 根因

所有月度/年度工作在**单个 tick 内同步执行**，无时间分片、无增量计算、无脏标记、无批量写入、无系统频率分离。每个系统独立遍历弟子列表、独立写入状态，造成 N 次遍历 + N 次状态赋值 + N 次索引重建。

---

## 核心设计方案

### 核心原则

1. **焦点弟子永远走即时路径**，非焦点弟子走批量/延迟路径
2. **干净弟子走闭式公式**（`rate × days`），脏弟子走完整遍历
3. **分帧用时间预算**，不用固定数量
4. **结算写入影子状态**，完成后一次性 swap 到 StateFlow
5. **不同系统不同频率**，低频系统跳过无关调度
6. **薪水对所有弟子统一处理**，不因干净/脏标记而跳过

### 目标数据流

```
GameEngineCore.tick()
  │
  ├─ if (settlementCoordinator.hasPendingWork) {
  │     // 结算帧：执行一批，时间预算 1ms
  │     // 注意：结算帧跳过 phase tick，游戏时间暂停推进
  │     settlementCoordinator.executeStep(shadowState, timeBudgetMs = 1)
  │     if (!settlementCoordinator.hasPendingWork) {
  │       stateStore.swapFromShadow(shadowState)
  │     }
  │     return  // 跳过 phase tick 和巡逻结果处理
  │  }
  │
  │  // 正常 tick：先推进游戏时间（onPhaseTick）
  │  stateStore.update {
  │     systemManager.onPhaseTick(this)
  │  }
  │  // ⚠️ createShadow() 必须在 stateStore.update{} 外部调用
  │  // 原因：update{} 内部读写 reusableMutableState（事务态），
  │  // 而 _state.value 尚未更新。在内部调用 createShadow() 会导致
  │  // 影子缺少 onPhaseTick 的变更，swap 时回滚它们。
  │  val monthChanged = /* 月份是否切换 */;
  │  val yearChanged = /* 年份是否切换 */;
  │  if (monthChanged) {
  │     shadowState = stateStore.createShadow()  // _state.value 已是最新
  │     settlementCoordinator.scheduleMonthly(shadowState)
  │     settlementCoordinator.executeStep(shadowState, timeBudgetMs = 1)
  │  }
  │  // 年度与月度同时触发时：先完成月度 swap，再用新状态创建年度 shadow
  │  if (yearChanged && !settlementCoordinator.hasPendingWork) {
  │     shadowState = stateStore.createShadow()
  │     settlementCoordinator.scheduleYearly(shadowState)
  │     settlementCoordinator.executeStep(shadowState, timeBudgetMs = 1)
  │  }
  │
  └─ // 巡逻结果：结算帧期间积压，结算完成后下一帧统一处理
     if (!settlementCoordinator.hasPendingWork) {
        val patrolResults = consumePendingPatrolResults()
        for (result in patrolResults) stateStore.setPendingBattleResult(result)
     }
```

> **关于巡逻结果积压**：结算可能持续多个帧（最坏约 30 帧 = 3 秒），期间巡逻战斗结果在 `ExplorationService` 中排队。结算完成后的第一个非结算帧会一次性消费全部积压结果，可能同时弹出多个战斗结算对话框。这是可接受的行为——战斗结果不会丢失，只是延迟显示。

引入 `SettlementCoordinator`，用**影子状态 + 时间预算分帧**取代 `SystemManager` 对月度/年度 tick 的直接同步调度。

---

## 详细设计

### 1. 影子状态（Shadow State）— 最优先实现

结算期间写入临时副本，UI 不受影响。全部阶段完成后一次性 swap。

```kotlin
// GameStateStore 新增方法
fun createShadow(): MutableGameState {
    // 浅拷贝：复制所有顶层引用。因为 UnifiedGameState 内部数据类
    // 遵循不可变+写时复制模式，浅拷贝足够隔离影子状态和主状态。
    val current = _state.value
    return MutableGameState(
        gameData = current.gameData,
        disciples = current.disciples,
        equipmentStacks = current.equipmentStacks,
        equipmentInstances = current.equipmentInstances,
        manualStacks = current.manualStacks,
        manualInstances = current.manualInstances,
        pills = current.pills,
        materials = current.materials,
        herbs = current.herbs,
        seeds = current.seeds,
        teams = current.teams,
        battleLogs = current.battleLogs,
        isPaused = current.isPaused,
        isLoading = current.isLoading,
        isSaving = current.isSaving,
        pendingNotification = current.pendingNotification
    )
}

fun swapFromShadow(shadow: MutableGameState) {
    // 原子性替换，只发射一次 StateFlow
    _state.update { oldState ->
        UnifiedGameState(
            gameData = shadow.gameData,
            disciples = shadow.disciples,
            equipmentStacks = shadow.equipmentStacks,
            equipmentInstances = shadow.equipmentInstances,
            manualStacks = shadow.manualStacks,
            manualInstances = shadow.manualInstances,
            pills = shadow.pills,
            materials = shadow.materials,
            herbs = shadow.herbs,
            seeds = shadow.seeds,
            teams = shadow.teams,
            battleLogs = shadow.battleLogs,
            alliances = shadow.gameData.alliances,
            isPaused = _isPaused.value,    // 保留实际暂停状态
            isLoading = _isLoading.value,
            isSaving = _isSaving.value,
            pendingBattleResult = oldState.pendingBattleResult,  // 保留战斗结果
            pendingNotification = shadow.pendingNotification ?: oldState.pendingNotification
        )
    }
}

// 影子事务：让旧代码路径（如 ProductionSubsystem）透明地写入影子
fun beginShadowTransaction(shadow: MutableGameState) {
    currentTransactionState = shadow
}
fun endShadowTransaction() {
    currentTransactionState = null
}
```

**为什么是浅拷贝而非深拷贝？** Kotlin data class 的 `copy()` 方法已提供写时复制语义。`shadow.disciples = shadow.disciples.map { ... }` 创建新列表、新 Disciple 对象，不会影响主状态。深拷贝所有嵌套对象既昂贵也不必要。

**调用位置约束**：`createShadow()` 必须在 `stateStore.update {}` 事务提交**之后**调用。`swapFromShadow()` 保留 `isPaused`/`isLoading`/`isSaving`/`pendingBattleResult` 等运行时字段，避免覆盖非结算状态。

### 2. SettlementCache（预计算缓存 + 脏标记）

```kotlin
enum class DiscipleDirtyFlag {
    NONE,           // 仅修炼值纯增长，用闭式公式
    BREAKTHROUGH,   // 修炼值接近满（≥80% maxCultivation）→ 需要突破检查
    EQUIPMENT,      // 装备槽非空（hasEquippedItems）→ 需要孕养计算
    MANUAL,         // 功法槽非空 → 需要熟练度计算
}

class SettlementCache(state: MutableGameState) {
    // 索引缓存
    val equipmentInstanceMap: Map<String, EquipmentInstance> =
        state.equipmentInstances.associateBy { it.id }
    val manualInstanceMap: Map<String, ManualInstance> =
        state.manualInstances.associateBy { it.id }
    val residenceDiscipleIds: Set<String> =
        state.gameData.residenceSlots.filter { it.isActive }.map { it.discipleId }.toSet()
    val salaryAmountMap: Map<Int, Int> =
        state.gameData.monthlySalary
    val realmConfigCache: Map<Int, GameConfig.RealmConfig> =
        (0..9).associateWith { GameConfig.Realm.get(it) }
    val cultivationRateCache: Map<String, Double>  // 预计算每月修炼速率
    val spiritMineDiscipleIds: Set<String>
    val preachingBonusCache: Map<String, Pair<Double, Double>>
    val discipleMap: Map<String, Disciple> =
        state.disciples.filter { it.isAlive }.associateBy { it.id }

    // 脏标记
    val dirtyFlags: Map<String, Set<DiscipleDirtyFlag>>
    val cleanDiscipleIds: Set<String>
    val dirtyDiscipleIds: Set<String>

    init {
        dirtyFlags = buildDirtyFlags(state)
        cleanDiscipleIds = dirtyFlags.filter { DiscipleDirtyFlag.NONE in it.value }.keys
        dirtyDiscipleIds = dirtyFlags.filter { DiscipleDirtyFlag.NONE !in it.value }.keys
        cultivationRateCache = buildCultivationRateCache(state)
        preachingBonusCache = buildPreachingBonusCache(state)
    }

    private fun buildDirtyFlags(state: MutableGameState): Map<String, Set<DiscipleDirtyFlag>> {
        return state.disciples.filter { it.isAlive }.associate { d ->
            val flags = mutableSetOf<DiscipleDirtyFlag>()
            if (d.cultivation >= d.maxCultivation * 0.8) flags += BREAKTHROUGH
            if (d.equipment.hasEquippedItems) flags += EQUIPMENT
            if (d.manualIds.isNotEmpty()) flags += MANUAL
            d.id to (if (flags.isEmpty()) setOf(NONE) else flags)
        }
    }
}
```

构建时机：`scheduleMonthly`/`scheduleYearly` 时作为 Phase 0 构建一次。效果：消除每个系统重复的 `associateBy`/`filter`/`find`，预标脏标记。

**预期收益**：假设 1000 弟子，~900 个 NONE（仅修炼增长），~100 个需要完整遍历。**遍历量减少 90%。**

### 3. 焦点弟子即时结算

在月份切换的同一帧内完成全部结算，写入影子状态。**注意：必须包含薪水处理。**

```kotlin
private fun processFocusedDiscipleImmediate(shadow: MutableGameState, cache: SettlementCache) {
    val focusedId = focusedDiscipleId ?: return
    val disciple = shadow.disciples.find { it.id == focusedId && it.isAlive } ?: return

    val data = shadow.gameData
    val monthSeconds = GameConfig.Time.SECONDS_PER_REAL_MONTH.toDouble()
    val rate = cache.cultivationRateCache[disciple.id] ?: 0.0
    val monthlyGain = rate * monthSeconds

    // 扣除高频已发量
    val alreadyGained = highFrequencyData.cultivationUpdates[disciple.id] ?: 0.0
    val netGain = (monthlyGain - alreadyGained).coerceAtLeast(0.0)

    var updated = disciple.copy(
        cultivation = (disciple.cultivation + netGain).coerceIn(0.0, disciple.maxCultivation)
    )

    // 突破检查
    if (updated.cultivation >= updated.maxCultivation && isFullHpMp(updated)) {
        updated = processBreakthrough(updated, shadow, cache)
    }

    // 功法熟练度 + 装备孕养（扣除高频已发量）
    val profUpdates = calculateProficiencyGains(updated, data, cache, monthSeconds, ...)
    val nurtureUpdates = calculateNurtureGains(updated, shadow, cache, monthSeconds, ...)

    // 薪水处理（修复：焦点弟子也需处理薪水）
    val salaryResult = calculateSalaryChange(updated, data)
    if (salaryResult != null) {
        updated = applySalaryChange(updated, salaryResult)
        if (salaryResult.isPaid) {
            shadow.gameData = data.copy(
                spiritStones = data.spiritStones - salaryResult.amount
            )
        }
    }
    // 忠诚度
    val loyaltyDelta = calculateLoyaltyDelta(updated, cache, data)
    if (loyaltyDelta != 0) {
        updated = updated.copyWith(loyalty = (updated.skills.loyalty + loyaltyDelta).coerceAtLeast(0))
    }

    shadow.disciples = shadow.disciples.map { if (it.id == focusedId) updated else it }
    applyNurtureUpdates(shadow, nurtureUpdates)
    applyProficiencyUpdates(shadow, profUpdates)
    resetHighFrequencyData()
}
```

### 4. 干净弟子批量处理（闭式公式 + 薪水）

```kotlin
private fun processCleanDiscipleBatch(shadow: MutableGameState, cache: SettlementCache) {
    val data = shadow.gameData
    val monthSeconds = GameConfig.Time.SECONDS_PER_REAL_MONTH.toDouble()
    val focusedId = focusedDiscipleId

    var spiritStoneDelta = 0L
    val updatedDisciples = shadow.disciples.map { disciple ->
        if (!disciple.isAlive || disciple.id == focusedId) return@map disciple
        if (disciple.id !in cache.cleanDiscipleIds) return@map disciple

        var d = disciple
        // 闭式修炼增量
        val rate = cache.cultivationRateCache[d.id] ?: 0.0
        d = d.copy(cultivation = (d.cultivation + rate * monthSeconds)
            .coerceIn(0.0, d.maxCultivation))

        // 薪水处理（修复：干净弟子也需要发薪）
        val salaryResult = calculateSalaryChange(d, data)
        if (salaryResult != null) {
            d = applySalaryChange(d, salaryResult)
            if (salaryResult.isPaid) spiritStoneDelta -= salaryResult.amount.toLong()
        }
        // 忠诚度
        val loyaltyDelta = calculateLoyaltyDelta(d, cache, data)
        if (loyaltyDelta != 0) {
            d = d.copyWith(loyalty = (d.skills.loyalty + loyaltyDelta).coerceAtLeast(0))
        }
        d
    }

    shadow.disciples = updatedDisciples
    if (spiritStoneDelta != 0L) {
        shadow.gameData = data.copy(spiritStones = data.spiritStones + spiritStoneDelta)
    }
}
```

> **为什么干净弟子也需要薪水？** 薪水是按境界（realm）发放的宗门制度，与弟子是否有装备/功法无关。如果只给脏弟子发薪，90% 的弟子忠诚度会持续下降，最终导致大规模叛逃。

### 5. 脏弟子分帧批量计算

```kotlin
private fun processDirtyDiscipleBatch(
    shadow: MutableGameState, cache: SettlementCache, offset: Int
): Int {
    val data = shadow.gameData
    val monthSeconds = GameConfig.Time.SECONDS_PER_REAL_MONTH.toDouble()
    val focusedId = focusedDiscipleId

    val dirtyDisciples = shadow.disciples.filter {
        it.isAlive && it.id != focusedId && it.id in cache.dirtyDiscipleIds
    }
    val batch = dirtyDisciples.drop(offset).take(MAX_DIRTY_BATCH_SIZE)
    if (batch.isEmpty()) return 0

    val equipmentInstanceUpdates = mutableMapOf<String, EquipmentInstance>()
    var updatedManualProficiencies = data.manualProficiencies.toMutableMap()
    val updatedDisciples = shadow.disciples.toMutableList()
    var spiritStoneDelta = 0L

    for (disciple in batch) {
        var d = disciple
        val rate = cache.cultivationRateCache[d.id] ?: 0.0
        val monthlyGain = rate * monthSeconds
        val alreadyGained = highFrequencyData.cultivationUpdates[d.id] ?: 0.0
        val netGain = (monthlyGain - alreadyGained).coerceAtLeast(0.0)
        d = d.copy(cultivation = (d.cultivation + netGain).coerceIn(0.0, d.maxCultivation))

        // 突破检查（仅 BREAKTHROUGH 标记的弟子）
        if (BREAKTHROUGH in cache.dirtyFlags[d.id].orEmpty()) {
            if (d.cultivation >= d.maxCultivation && isFullHpMp(d)) {
                d = processBreakthrough(d, shadow, cache)
            }
        }

        // 功法熟练度（仅 MANUAL 标记）
        if (MANUAL in cache.dirtyFlags[d.id].orEmpty()) {
            val profUpdates = calculateProficiencyGains(d, data, cache, monthSeconds, ...)
            updatedManualProficiencies.putAll(profUpdates)
        }

        // 装备孕养（仅 EQUIPMENT 标记）
        if (EQUIPMENT in cache.dirtyFlags[d.id].orEmpty()) {
            val nurtureUpdates = calculateNurtureGains(d, shadow, cache, monthSeconds, ...)
            equipmentInstanceUpdates.putAll(nurtureUpdates)
        }

        // 薪水（所有弟子统一处理）
        val salaryResult = calculateSalaryChange(d, data)
        if (salaryResult != null) {
            d = applySalaryChange(d, salaryResult)
            if (salaryResult.isPaid) spiritStoneDelta -= salaryResult.amount.toLong()
        }

        // 忠诚度
        val loyaltyDelta = calculateLoyaltyDelta(d, cache, data)
        if (loyaltyDelta != 0) {
            d = d.copyWith(loyalty = (d.skills.loyalty + loyaltyDelta).coerceAtLeast(0))
        }

        val idx = updatedDisciples.indexOfFirst { it.id == d.id }
        if (idx >= 0) updatedDisciples[idx] = d
    }

    // 批量写入影子状态
    shadow.disciples = updatedDisciples.toList()
    if (equipmentInstanceUpdates.isNotEmpty()) {
        shadow.equipmentInstances = shadow.equipmentInstances.map { eq ->
            equipmentInstanceUpdates[eq.id] ?: eq
        }
    }
    if (updatedManualProficiencies != data.manualProficiencies) {
        shadow.gameData = data.copy(manualProficiencies = updatedManualProficiencies)
    }
    if (spiritStoneDelta != 0L) {
        shadow.gameData = shadow.gameData.copy(
            spiritStones = shadow.gameData.spiritStones + spiritStoneDelta
        )
    }
    return batch.size
}
```

> **注意**：代码中 `DiscipleBatchUpdate` 类及 `GlobalBatchUpdate` 已定义但实际未使用——脏弟子循环直接写入影子状态。这是因为 `MutableList` + `MutableMap` 就地修改比创建中间对象列表再批量 apply 更高效，且代码更简洁。如需保留批量抽象用于未来扩展（如网络同步），可在 P4 后重新评估。

### 6. 突破检查 while 循环（最大迭代保护）

```kotlin
private fun processBreakthrough(disciple: Disciple, shadow: MutableGameState, cache: SettlementCache): Disciple {
    var d = disciple
    var shouldContinue = true
    var iterations = 0
    val MAX_BREAKTHROUGH_ITERATIONS = 10  // 防止无限循环

    while (shouldContinue && d.realm > 0 && iterations < MAX_BREAKTHROUGH_ITERATIONS) {
        iterations++
        if (d.cultivation < d.maxCultivation) break

        // ... 丹药消耗、概率计算、成功/失败处理 ...
        if (success) {
            d = applyBreakthroughSuccess(d)
        } else {
            d = applyBreakthroughFailure(d)
            shouldContinue = false
        }
    }
    return d
}
```

> **为什么需要限制**：理论上连续突破可能跨越多个境界（虽然概率极低）。最大 10 次迭代覆盖了从练气到飞升的所有可能突破，同时防止逻辑错误导致的无限循环。

### 7. 生产/经济/世界事件阶段

```kotlin
private fun processProduction(shadow: MutableGameState) {
    stateStore.beginShadowTransaction(shadow)
    try {
        productionSubsystem.onMonthTick(shadow)
        economySubsystem.onMonthTick(shadow)  // 政策费用和灵石扣减
    } finally {
        stateStore.endShadowTransaction()
    }
}

private fun processWorldEvents(shadow: MutableGameState) {
    stateStore.beginShadowTransaction(shadow)
    try {
        cultivationService.processMonthlyEventsOnShadow(shadow)
        explorationService.onMonthTick(shadow)
        childBirthSystem.onMonthTick(shadow)
        partnerSystem.onMonthTick(shadow)
    } finally {
        stateStore.endShadowTransaction()
    }
}
```

> **关于 `beginShadowTransaction`/`endShadowTransaction`**：旧代码路径（如 ProductionSubsystem、CultivationService 的月度事件方法）内部可能调用 `stateStore.update {}`。通过设置事务状态为重定向到影子，这些旧方法无需修改即可透明地写入影子状态。`GameStateStore.update()` 在检测到活跃影子事务时会抛出异常，防止意外写入主状态。

### 8. 年度结算

```kotlin
fun scheduleYearly(shadow: MutableGameState) {
    // 年度 = 月度阶段 + 年度专属阶段
    scheduler.scheduleMonthly(shadow, cachePhase, focusedPhase, cleanPhase,
        dirtyPhase, productionPhase, worldEventsPhase)
    scheduler.appendYearlyPhases(
        Phase_AgingAndDeath { processAgingAndDeath(it) },
        Phase_RecruitRefresh { processRecruitRefresh(it) },
        Phase_AISectYearly { processAISectYearly(it) },
        Phase_AllianceExpiry { processAllianceExpiry(it) }
    )
}

private fun processAgingAndDeath(shadow: MutableGameState) {
    // 所有弟子都检查年龄增长
    // 但死亡风险检查仅针对年龄 > 寿命期望 80% 的弟子
    stateStore.beginShadowTransaction(shadow)
    try {
        cultivationService.processYearlyEventsOnShadow(shadow)
        childBirthSystem.onYearTick(shadow)
    } finally {
        stateStore.endShadowTransaction()
    }
}
```

> **衰老与死亡分离**：年龄增长对所有弟子生效。死亡风险检查仅针对高龄弟子（年龄 > 寿命期望 × 80%），从而跳过年轻弟子的死亡概率计算，减少计算量。

### 9. SettlementScheduler（分帧调度器 + 时间预算）

使用 sealed class 定义阶段类型：

```kotlin
sealed class SettlementPhase {
    abstract suspend fun execute(shadow: MutableGameState): Boolean
    // 返回 true = 阶段完成，false = 需要下一帧继续
}

// 可分帧的阶段（脏弟子批次）
class Phase_DirtyDiscipleBatch(
    private val onProcess: suspend (MutableGameState, SettlementCache, Int) -> Int
) : SettlementPhase() {
    var currentOffset: Int = 0
    override suspend fun execute(shadow: MutableGameState): Boolean {
        val processed = onProcess(shadow, cache, currentOffset)
        currentOffset += processed
        return processed == 0  // 没有更多弟子 = 完成
    }
}

// 一次性阶段
class Phase_Production(...) : SettlementPhase() {
    override suspend fun execute(shadow: MutableGameState): Boolean {
        onProcess(shadow); return true
    }
}
```

时间预算驱动：

```kotlin
class SettlementScheduler {
    private val pendingPhases = mutableListOf<SettlementPhase>()
    private var currentPhaseIndex = 0
    private var frameCount = 0
    val hasPendingWork: Boolean get() = currentPhaseIndex < pendingPhases.size

    suspend fun executeStep(shadow: MutableGameState, timeBudgetNs: Long): Boolean {
        if (!hasPendingWork) return true
        val deadline = System.nanoTime() + timeBudgetNs
        frameCount++

        while (System.nanoTime() < deadline && hasPendingWork) {
            val completed = pendingPhases[currentPhaseIndex].execute(shadow)
            if (completed) currentPhaseIndex++
        }
        return !hasPendingWork
    }

    fun reset() { pendingPhases.clear(); currentPhaseIndex = 0; frameCount = 0 }
}
```

> **时间预算 vs 固定数量**：在 `executeStep` 循环中，每次执行一个阶段（可能是一个批次或整个阶段），然后检查是否超预算。快速阶段（缓存构建、干净弟子）一帧内全部完成。慢速阶段（脏弟子）每帧处理一个批次后检查时间。

### 10. 并行计算（P4 阶段）

适用范围：
- ✅ 修炼增量、功法熟练度、装备孕养（弟子间无依赖）
- ❌ 突破检查（共享丹药库存，必须串行。按概率排序→逐个消耗→低概率跳过）
- ❌ 薪水发放（共享灵石余额，必须串行）

```kotlin
suspend fun processDirtyBatchParallel(
    disciples: List<Disciple>, cache: SettlementCache
): List<DiscipleData> = coroutineScope {
    val chunkSize = (disciples.size + 3) / 4
    disciples.chunked(chunkSize).map { chunk ->
        async(Dispatchers.Default) {
            chunk.map { d -> computeCultivationNurtureProficiency(d, cache) }
        }
    }.awaitAll().flatten()
}
```

### 11. SettlementCoordinator（协调器）

```kotlin
@Singleton
class SettlementCoordinator @Inject constructor(
    private val cultivationService: CultivationService,
    private val productionSubsystem: ProductionSubsystem,
    private val economySubsystem: EconomySubsystem,
    private val explorationService: ExplorationService,
    private val childBirthSystem: ChildBirthSystem,
    private val partnerSystem: PartnerSystem,
    private val stateStore: GameStateStore,
    private val scheduler: SettlementScheduler,
    private val metricsCollector: SettlementMetricsCollector
) {
    private var shadowState: MutableGameState? = null
    private var currentCache: SettlementCache? = null
    val hasPendingWork: Boolean get() = scheduler.hasPendingWork

    suspend fun executeStep(timeBudgetMs: Long = 1): Boolean {
        val shadow = shadowState ?: return true
        val timeBudgetNs = (timeBudgetMs * 1_000_000).toLong()
            .coerceAtMost(SettlementScheduler.DEFAULT_TIME_BUDGET_NS)
        val completed = scheduler.executeStep(shadow, timeBudgetNs)
        if (completed) onSettlementComplete()
        return completed
    }

    fun scheduleMonthly(shadow: MutableGameState) { /* 构建 Phase 0-5 */ }
    fun scheduleYearly(shadow: MutableGameState) { /* 月度 + 年度专属 Phase */ }

    private fun onSettlementComplete() {
        val shadow = shadowState ?: return
        stateStore.swapFromShadow(shadow)
        metricsCollector.record(metricsBuilder.build(...))
        shadowState = null; currentCache = null; scheduler.reset()
    }

    fun cancelPendingWork() {
        // 玩家切后台或退出时调用，丢弃中间状态
        shadowState = null; currentCache = null; scheduler.reset()
    }
}
```

### 12. 与 GameEngineCore 的集成（完整版）

```kotlin
private suspend fun tickInternal() {
    val currentState = stateStore.unifiedState.value
    if (currentState.isPaused || currentState.isLoading || currentState.isSaving) {
        checkAndResetStuckStates(currentState)
        return
    }

    _tickCount.value++

    // 有未完成的结算 → 继续分帧
    if (settlementCoordinator.hasPendingWork) {
        val completed = settlementCoordinator.executeStep(timeBudgetMs = 1)
        if (completed) settlementCoordinator.onSettlementComplete()
        return  // 结算帧：不推进游戏时间，不处理巡逻结果
    }

    // 正常 tick：推进游戏时间
    var monthChanged = false
    var yearChanged = false

    stateStore.update {
        val phasesPerTick = GamePhase.PHASES_PER_MONTH.toDouble() /
            (GameConfig.Time.SECONDS_PER_REAL_MONTH * GameConfig.Time.TICKS_PER_SECOND)
        phaseAccumulator += phasesPerTick

        while (phaseAccumulator >= 1.0) {
            phaseAccumulator -= 1.0
            val prevMonth = this.gameData.gameMonth
            val prevYear = this.gameData.gameYear

            systemManager.onPhaseTick(this)

            if (this.gameData.gameMonth != prevMonth) monthChanged = true
            if (this.gameData.gameYear != prevYear) yearChanged = true

            // 自动存档（不变）
            checkAutoSave(this.gameData)
        }
    }
    // ⚠️ update{} 事务已提交，_state.value 已更新

    if (monthChanged) {
        val shadow = stateStore.createShadow()  // 安全：读的是已提交的最新状态
        settlementCoordinator.scheduleMonthly(shadow)
    }
    if (yearChanged && !settlementCoordinator.hasPendingWork) {
        val shadow = stateStore.createShadow()
        settlementCoordinator.scheduleYearly(shadow)
    }

    // 立即执行第一批结算
    if (settlementCoordinator.hasPendingWork) {
        val completed = settlementCoordinator.executeStep(timeBudgetMs = 1)
        if (completed) settlementCoordinator.onSettlementComplete()
    }

    // 巡逻结果（仅在非结算帧处理）
    if (!settlementCoordinator.hasPendingWork) {
        val patrolResults = explorationService.consumePendingPatrolResults()
        for (result in patrolResults) stateStore.setPendingBattleResult(result)
    }
}
```

### 13. 多频率 Tick 分离

| 频率 | 系统 | 触发条件 |
|------|------|---------|
| 每月 | 弟子修炼、突破、薪水、生产、建筑 | 每次 `scheduleMonthly` |
| 每3月 | 外交事件、任务刷新 | `gameMonth % 3 == 0` |
| 每6月 | 世界等级生成 | `gameMonth % 6 == 0` |
| 每年 | 衰老/死亡、招募刷新、AI宗门、联盟 | `scheduleYearly` |

`scheduleMonthly` 中根据 `gameMonth` 条件判断是否添加中频阶段：

```kotlin
fun scheduleMonthly(shadow: MutableGameState) {
    // ... 基础阶段（缓存、焦点、干净、脏、生产）...
    if (shadow.gameData.gameMonth % 3 == 0) {
        pendingPhases.add(Phase_Diplomacy(...))
        pendingPhases.add(Phase_MissionRefresh(...))
    }
    if (shadow.gameData.gameMonth % 6 == 0) {
        pendingPhases.add(Phase_WorldLevelGeneration(...))
    }
}
```

### 14. 存档集成

结算期间如果触发存档（自动存档或玩家手动保存）：

- **方案 A（推荐）**：`SaveLoadViewModel.pauseAndSaveForBackground()` 检查 `settlementCoordinator.hasPendingWork`。如果有待结算，等待结算完成（`swapFromShadow` 后）再存档。超时上限 5 秒。
- **方案 B（降级）**：直接 `cancelPendingWork()` 丢弃中间结算，存档旧状态。下次 resume 时重新触发结算。

```kotlin
fun pauseAndSaveForBackground() {
    // 等待结算完成或超时
    val timeout = System.currentTimeMillis() + 5000
    while (settlementCoordinator.hasPendingWork && System.currentTimeMillis() < timeout) {
        runBlocking { settlementCoordinator.executeStep(timeBudgetMs = 1) }
    }
    if (settlementCoordinator.hasPendingWork) {
        // 超时：丢弃中间状态
        settlementCoordinator.cancelPendingWork()
    }
    // 正常存档...
}
```

### 15. 性能监控埋点

```kotlin
data class SettlementMetrics(
    val monthYear: Pair<Int, Int>,
    val totalDurationMs: Float,
    val cacheBuildMs: Float,
    val focusedDiscipleMs: Float,
    val cleanBatchMs: Float,
    val dirtyBatchMs: Float,
    val dirtyDiscipleCount: Int,
    val totalDiscipleCount: Int,
    val productionMs: Float,
    val worldEventsMs: Float,
    val shadowSwapMs: Float,
    val frameCount: Int
)

// SettlementMetricsCollector 每 10 次结算输出聚合报告
// Log.i(TAG, "SettlementMetrics: avg=${...}, max=${...}, frames=${...}")
```

---

## 迁移策略

| 阶段 | 改动 | 预期收益 | 风险 | 验证方式 |
|------|------|---------|------|---------|
| **P0** | SettlementCache + Metrics 埋点 | 减少 50%+ 重复索引构建 | 低 | 对比前后 Logcat 耗时 |
| **P1** | 合并弟子遍历 + 影子状态 + 薪水全员处理 | 遍历 N→1 次，消除半结算可见性，修复干净弟子缺薪 bug | 中 | 对比前后弟子修炼值/忠诚度一致性 |
| **P1.5** | 脏标记 + 闭式修炼增量 | 减少 ~90% 弟子遍历 | 中 | 逐弟子修炼值精度对比（差异 <1e-6） |
| **P2** | SettlementCoordinator + 时间预算分帧 + createShadow 外部调用 | 单帧耗时 <2ms | 中 | 帧耗时分布对比 |
| **P3** | 多频率 tick 分离 | 减少无关系统调度 | 低 | 月度事件触发频率验证 |
| **P4** | 协程并行（修炼/孕养/熟练度） | 多核加速 | 低 | Robolectric 测试 |

各阶段兼容性：
- P0-P1：不改变结算时机和结果，回滚成本低
- P1.5：闭式公式与逐弟子累加可能有微小浮点差异（<1e-6），上线前做全量对比
- P2：executeStep 在 `update{}` 外部调用，有 shadow 保护。降级方案 = 分帧即全量一帧（回退到 P1）
- P3-P4：可选优化

---

## 风险与缓解

| 风险 | 缓解措施 |
|------|---------|
| `createShadow()` 在 `update{}` 内部调用 → 影子缺数据 | **强制约束**：`createShadow()` 必须在 `stateStore.update {}` 事务提交后调用。Code review 检查点 |
| 干净弟子缺薪水 → 忠诚度下降 | P1 统一处理薪水，不分干净/脏 |
| 焦点弟子缺薪水 → 焦点弟子忠诚度异常 | processFocusedDiscipleImmediate 中加入 salary 逻辑 |
| 分帧中玩家切后台 | `cancelPendingWork()` 丢弃影子状态，resume 后重建 |
| 分帧中触发存档 | 等待结算完成（≤5s）或 cancelPendingWork 降级 |
| 突破丹药竞争 | 按突破概率排序，逐弟子串行。高概率优先消耗 |
| 突破 while 循环无上限 | 最大 10 次迭代保护 |
| 浮点精度差异 | P1.5 全量对比闭式公式 vs 逐弟子累加 |
| 年度+月度同时触发 → shadow 覆盖 | 先完成月度→swap→再建年度 shadow。`yearChanged && !hasPendingWork` 确保顺序 |
| 巡逻结果结算期间积压 | 可接受。战斗结果不丢失，结算完成后下一帧批量弹出 |
| 结算期间游戏时间暂停 | 1000 弟子约 1-2 秒完成，玩家不可感知 |
| 影子事务中意外调用 `stateStore.update()` | `update()` 检测活跃事务时抛出异常，fail-fast |

---

## 修改文件清单

| 文件 | 改动 | 说明 |
|------|------|------|
| `core/engine/GameEngineCore.kt` | 修改 | createShadow 移出 update{}；集成 SettlementCoordinator |
| `core/engine/settlement/SettlementCoordinator.kt` | **新增/完善** | 结算协调器：阶段调度、影子管理、metrics |
| `core/engine/settlement/SettlementCache.kt` | **新增/完善** | 预计算缓存 + 脏标记构建 |
| `core/engine/settlement/SettlementScheduler.kt` | **新增/完善** | sealed class 阶段 + 时间预算调度 |
| `core/engine/settlement/SettlementMetrics.kt` | **新增/完善** | 性能监控埋点 + 10 次聚合报告 |
| `core/state/GameStateStore.kt` | 修改 | createShadow()/swapFromShadow()/beginShadowTransaction()/endShadowTransaction() |
| `core/engine/service/CultivationService.kt` | 修改 | 拆出修炼增量/突破/薪水为独立可复用方法 |
| `core/engine/system/SystemManager.kt` | 修改 | onMonthTick/onYearTick 改为由 Coordinator 直接调用各系统 |

---

## 附录：行业参考来源

- [Idle Game Engine — Batch Mode Scheduling](https://github.com/hansjm10/Idle-Game-Engine/issues/540) — FIFO 批量队列
- [Idle Miner Architecture](https://codeshare.me/c/983b8b3d-69da-498e-810a-4e26f159a695/blob/README.md) — Delta 快照推 UI
- [DCS World — Time-Budgeted Batch Scheduling](https://forum.dcs.world/topic/384776-time-budgeted-batch-scheduling-process-thousands-of-things-in-under-a-second-without-stutter/) — 每帧 1ms 预算
- [RimWorld Multiplayer — Async Time System](https://deepwiki.com/rwmt/Multiplayer/7.1-async-time-system) — TickerType 多频率队列
- [Dwarf Fortress Wiki — FPS Death](http://dwarffortresswiki.org/index.php?title=Frames_per_second) — 模拟渲染解耦
- [CK3 Dev Diary #187 — Performance & Optimization](https://admin-forum.paradoxplaza.com/forum/developer-diary/dev-diary-187-performance-optimization.1861437/) — 缓存失效机制
- [Data-Oriented Design for Games (Manning)](https://www.manning.com/preview/data-oriented-design-for-games/chapter-3) — 脏标记 + 对象池
- [Game Mechanics Optimizations (GitHub)](https://github.com/raduacg/game-mechanics-optimizations) — 171 种优化模式汇总
