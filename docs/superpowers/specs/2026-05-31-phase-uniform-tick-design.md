# 旬制时间显示匀速化设计（行业对标改进版）

> 理论基础：Fixed Timestep Accumulator Pattern（Glenn Fiedler "Fix Your Timestep!"）— 模拟以固定频率运行，渲染/显示与模拟解耦。RimWorld TickerType、Factorio Deterministic Lockstep、Paradox CK3 日/月/年分级 tick 均采用相同原则。

## 1. 问题描述

游戏每旬的显示速度不一致：上旬明显比中旬、下旬长。

### 根因

`GameEngineCore.tickInternal()` 中，当 `settlementCoordinator.hasPendingWork == true` 时直接 `return`，不推进 phase。

月切换（下旬→下月上旬）时触发 `settlementCoordinator.scheduleMonthly()`，此后所有 tick 都在执行结算步骤，phase 停在上旬不动，直到结算完成。结算耗时被"算在"上旬头上。

### 时间推进参数

| 参数 | 值 | 来源 |
|------|-----|------|
| TICK_INTERVAL_MS | 1000L | GameEngineCore |
| SECONDS_PER_REAL_MONTH | 6 | GameConfig.Time |
| PHASES_PER_MONTH | 3 | GamePhase |
| phasesPerTick (1x) | 0.5 | 计算值 |
| phasesPerTick (2x) | 1.0 | 计算值 |
| 理论旬时长 (1x) | 2秒 | 2 tick × 1秒 |
| 理论旬时长 (2x) | 1秒 | 1 tick × 1秒 |

## 2. 设计目标

- 旬制仅作为"节奏指示器"，每旬严格 2秒(1x) / 1秒(2x)
- 月度结算在一月内完成，不阻塞时间推进
- 改动最小，复用现有 SettlementScheduler 增量机制
- 数据一致性有保障

## 3. 方案：显示与结算完全解耦

### 3.1 核心思路

phase 推进走固定计时器，结算在后台增量执行，两者互不阻塞。这正是行业标准的 Fixed Timestep 模式——高频轻量操作（phase 推进）不受低频重操作（月度结算）影响。

### 3.2 改动1：GameEngineCore.tickInternal() — 移除阻塞

**当前逻辑**（简化）：

```
tickInternal():
    if hasPendingWork → executeStep → return  // 阻塞phase推进 ← 根因
    update { phaseAccumulator推进; onPhaseTick }
    if monthChanged → scheduleMonthly
    if hasPendingWork → executeStep
```

**改为**：

```
tickInternal():
    update { phaseAccumulator推进; onPhaseTick }  // 始终推进
    if monthChanged → scheduleMonthly
    if hasPendingWork → executeStep               // 结算不阻塞
```

关键变化：
1. 移除开头的 `if (hasPendingWork) { executeStep; return }` 阻塞检查
2. phase 推进始终执行，不受结算状态影响
3. 原有的巡逻结果处理逻辑移到非结算帧（见改动5）

### 3.3 改动2：结算期间跳过 CultivationService.onPhaseTick

`systemManager.onPhaseTick(state)` 当前实际执行两个系统的 onPhaseTick：

| 系统 | 优先级 | onPhaseTick 行为 | 结算期间是否需要 |
|------|--------|-----------------|----------------|
| TimeSystem | 0 | 推进 gamePhase/gameMonth/gameYear | **必须** |
| CultivationService | 200 | HP/MP恢复、丹药衰减、自动使用物品、状态同步 | **可跳过** |

> 验证：代码中其他 14 个已注册 `GameSystem` 的 `onPhaseTick` 均为默认空操作。只有这两个系统实际在 phase tick 时执行工作。

结算期间跳过 CultivationService 的原因：
- shadow state 正在被结算修改（`processProduction`/`processWorldEvents` 通过 `beginShadowTransaction` 包装），此时 CultivationService 读写主状态可能与 shadow 冲突
- 跳过 1-2 个 phase 的恢复/衰减，下一 phase 自动补回，玩家无感知

实现方式：在 `tickInternal()` 中，当 `hasPendingWork` 时，直接调用 `TimeSystem.onPhaseTick()` 而非 `systemManager.onPhaseTick()`。

```kotlin
// 在 stateStore.update {} 内部
if (settlementCoordinator.hasPendingWork) {
    // 结算进行中：仅推进时间，不执行 CultivationService
    systemManager.getSystem(TimeSystem::class).onPhaseTick(this)
} else {
    systemManager.onPhaseTick(this)
}
```

### 3.4 改动3：swapFromShadow() 保留主状态时间字段

shadow 在月切换时创建，其 `gameData.gamePhase = 0`（上旬）。结算完成后 swap 回主状态时，主状态的 phase 可能已推进到 1 或 2。

**当前 swapFromShadow 已保留的字段**（`GameStateStore.kt:255-281`）：
- `isPaused` / `isLoading` / `isSaving` — 从独立 StateFlow 取值
- `pendingBattleResult` — 从 `oldState` 保留
- `pendingNotification` — shadow 优先，fallback `oldState`

**需要新增保留的字段**：`gamePhase`、`gameMonth`、`gameYear`

```kotlin
fun swapFromShadow(shadow: MutableGameState) {
    _state.update { oldState ->
        val finalPaused = _isPaused.value
        val finalLoading = _isLoading.value
        val finalSaving = _isSaving.value
        UnifiedGameState(
            gameData = shadow.gameData.copy(
                // 保留主状态的时间字段（结算不修改时间）
                gamePhase = oldState.gameData.gamePhase,
                gameMonth = oldState.gameData.gameMonth,
                gameYear = oldState.gameData.gameYear
            ),
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
            isPaused = finalPaused,
            isLoading = finalLoading,
            isSaving = finalSaving,
            pendingBattleResult = oldState.pendingBattleResult,
            pendingNotification = shadow.pendingNotification ?: oldState.pendingNotification
        )
    }
}
```

> **安全前提**：shadow 的结算逻辑（`processProduction`/`processWorldEvents`/`processCleanDiscipleBatch`/`processDirtyDiscipleBatch`/`processAgingAndDeath`）**不修改 gamePhase/gameMonth/gameYear**。这些由 `TimeSystem.onPhaseTick` 在主状态上推进。copy 覆盖是安全的。

### 3.5 改动4：边界处理 — 新月到达时结算未完成

理论分析：1x=6秒/月 vs ~1-2秒结算，正常情况下结算在当月上旬内完成。2x=3秒/月时结算可能跨越中旬，极端情况下接近下旬。

策略：在 `tickInternal()` 中，当检测到月份切换且 `hasPendingWork` 时，强制完成当前结算再调度新月。

```kotlin
// 在 stateStore.update {} 外部，monthChanged/yearChanged 检测之后
if (settlementCoordinator.hasPendingWork && (monthChanged || yearChanged)) {
    forceCompleteSettlement()
}

if (yearChanged) {
    settlementCoordinator.scheduleYearly(shadow)
} else if (monthChanged) {
    settlementCoordinator.scheduleMonthly(shadow)
}
```

`forceCompleteSettlement()` 实现：

```kotlin
private var isForceCompleting = false  // 重入防护

private suspend fun forceCompleteSettlement() {
    if (isForceCompleting) return  // 防止嵌套调用死锁
    isForceCompleting = true
    try {
        while (settlementCoordinator.hasPendingWork) {
            settlementCoordinator.executeStep(timeBudgetMs = 5)  // 加大预算，加速完成
        }
        settlementCoordinator.onSettlementComplete()
    } finally {
        isForceCompleting = false
    }
}
```

> **为什么需要重入防护？** `forceCompleteSettlement` 内部调用 `executeStep`，执行阶段可能触发 `processProduction`（影子事务），而影子事务内部可能间接调用其他路径。`isForceCompleting` 标志防止递归调用导致的栈溢出。

### 3.6 改动5：巡逻结果处理时机调整

移除头部的 `hasPendingWork` 检查后，巡逻结果不再被阻塞跳过。在结算期间，巡逻结果每 tick 都会被处理（而非之前"积压到结算完成后批量弹出"）。

**影响**：正面 — 巡逻战斗结果不再延迟。无需额外修改，现有逻辑自动适应。

```kotlin
// 巡逻结果始终处理（不移除这段代码）
val patrolResults = explorationService.consumePendingPatrolResults()
for (result in patrolResults) {
    stateStore.setPendingBattleResult(result)
}
```

## 4. 数据一致性分析

| 场景 | 影响 | 严重程度 | 处理 |
|------|------|---------|------|
| 结算期间跳过1-2次 CultivationService.onPhaseTick | 下一 tick 自动补回（HP/MP 回复、丹药衰减都在下次 tick 执行） | 无感知 | 无需处理 |
| swapFromShadow 保留时间字段 | shadow 不修改时间字段（已验证） | 无风险 | copy 覆盖 |
| 用户在结算期间修改 GameData | shadow swap 可能覆盖玩家操作 | 低概率 | 后续可加 `isSettling` 状态锁，本次不处理 |
| 极端：2x 时结算跨月 | `forceCompleteSettlement` 强制完成 | 低概率 | 加大时间预算(5ms)，重入防护 |
| 结算期间巡逻结果弹出 | 战斗对话框可能在结算帧出现 | 可接受 | 比之前"积压延迟"更优 |
| phaseAccumulator 精度 | 浮点 0.5 累加无精度问题；2x=1.0 时每 tick 精确触发 1 phase | 无风险 | 无需处理 |
| 影子事务内部调用 `stateStore.update{}` | `GameStateStore.update()` 检测到 `currentTransactionState != null` 后抛 `IllegalStateException` | 不会发生 | 已确认 `processMonthlyEventsOnShadow` 等路径不调用 `update{}`，仅读取 |

## 5. 涉及文件

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| GameEngineCore.kt | 修改 | tickInternal() 移除阻塞；条件选择 TimeSystem/CultivationService phase tick；新增 forceCompleteSettlement |
| GameStateStore.kt | 修改 | swapFromShadow() copy 保留 gamePhase/gameMonth/gameYear |
| GameConfig.kt | 无改动 | 现有配置足够 |
| TimeSystem.kt | 无改动 | 行为不变 |
| CultivationService.kt | 无改动 | 行为不变，仅被条件跳过 |
| SettlementCoordinator.kt | 无改动 | 增量机制完全复用 |

## 6. 验证标准

1. 1x 速度下，每旬严格 2 秒，三旬等长
2. 2x 速度下，每旬严格 1 秒，三旬等长
3. 月度结算结果与改动前一致（弟子修炼值、薪水、忠诚度等逐项对比）
4. 年度结算结果与改动前一致
5. 焦点弟子机制不受影响
6. 存档加载后行为正常
7. `forceCompleteSettlement` 仅在 2x + 极端弟子数（>1000）时触发，正常情况不触发
8. 巡逻战斗结果不再积压延迟

---

## 附录：行业参考来源

- [Fix Your Timestep! — Glenn Fiedler](https://gafferongames.com/post/fix_your_timestep/) — Fixed Timestep Accumulator Pattern 经典
- [Factorio — Deterministic Lockstep](https://forums.factorio.com/viewtopic.php?p=229095) — 模拟与渲染完全解耦
- [RimWorld Multiplayer — Async Time System](https://deepwiki.com/rwmt/Multiplayer/7.1-async-time-system) — TickerType 多频分离
- [CK3 Dev Diary #187 — Performance & Optimization](https://admin-forum.paradoxplaza.com/forum/developer-diary/dev-diary-187-performance-optimization.1861437/) — 日/月/年分级 tick
- [Idle Game Engine — sim worker + frame pump](https://github.com/hansjm10/Idle-Game-Engine/issues/788) — 模拟帧与 UI 帧分离
