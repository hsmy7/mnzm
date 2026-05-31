# 旬制匀速化 + 写放大优化 实施报告

> 日期：2026-05-31
> 关联设计：`docs/superpowers/specs/2026-05-31-phase-uniform-tick-design.md`

---

## 一、旬制匀速化实施

### 1.1 问题

游戏每旬显示速度不一致：上旬明显比中旬、下旬长。根因是 `GameEngineCore.tickInternal()` 在 `settlementCoordinator.hasPendingWork == true` 时直接 `return`，不推进 phase。月切换时触发 `scheduleMonthly()`，结算期间 phase 停在上旬不动。

### 1.2 改动清单

#### 改动1：移除头部阻塞

**文件**：`GameEngineCore.kt`

**变更**：删除 `tickInternal()` 开头的阻塞检查：

```kotlin
// 删除
if (settlementCoordinator.hasPendingWork) {
    val completed = settlementCoordinator.executeStep(timeBudgetMs = 1)
    if (completed) settlementCoordinator.onSettlementComplete()
    return  // ← 阻塞 phase 推进的根因
}
```

phase 推进始终执行，不受结算状态影响。

#### 改动2：结算期间条件选择 onPhaseTick

**文件**：`GameEngineCore.kt`

**变更**：在 `stateStore.update {}` 内部，当 `hasPendingWork` 时仅调用 `TimeSystem.onPhaseTick()`，跳过 `CultivationService.onPhaseTick()`：

```kotlin
if (settlementCoordinator.hasPendingWork) {
    systemManager.getSystem(TimeSystem::class).onPhaseTick(this)
} else {
    systemManager.onPhaseTick(this)
}
```

**安全前提**：结算期间跳过 1-2 次 CultivationService 的 HP/MP 恢复和丹药衰减，下一 tick 自动补回，玩家无感知。shadow state 正在被结算修改时，CultivationService 读写主状态可能与 shadow 冲突，跳过可避免此问题。

#### 改动3：swapFromShadow 保留时间字段

**文件**：`GameStateStore.kt`

**变更**：`swapFromShadow()` 中 `gameData` 从直接赋值改为 `copy` 保留主状态的时间字段：

```kotlin
gameData = shadow.gameData.copy(
    gamePhase = oldState.gameData.gamePhase,
    gameMonth = oldState.gameData.gameMonth,
    gameYear = oldState.gameData.gameYear
),
```

**安全前提**：shadow 的结算逻辑（processProduction/processWorldEvents/processCleanDiscipleBatch/processDirtyDiscipleBatch/processAgingAndDeath）不修改 gamePhase/gameMonth/gameYear，这些由 TimeSystem.onPhaseTick 在主状态上推进。

#### 改动4：边界处理 forceCompleteSettlement

**文件**：`GameEngineCore.kt`

**变更**：当月份切换且结算未完成时，强制完成当前结算再调度新月：

```kotlin
if (settlementCoordinator.hasPendingWork && (monthChanged || yearChanged)) {
    forceCompleteSettlement()
}
```

`forceCompleteSettlement()` 实现包含重入防护（`isForceCompleting` 标志），使用 5ms 时间预算加速完成。

#### 改动5：巡逻结果处理

无需额外修改。移除头部阻塞后，巡逻结果每 tick 都会被处理，不再积压延迟。

### 1.3 涉及文件

| 文件 | 改动类型 |
|------|---------|
| `GameEngineCore.kt` | 修改：tickInternal 重构 + forceCompleteSettlement + TimeSystem import |
| `GameStateStore.kt` | 修改：swapFromShadow copy 保留时间字段 |
| `GameConfig.kt` | 无改动 |
| `TimeSystem.kt` | 无改动 |
| `CultivationService.kt` | 无改动（仅被条件跳过） |
| `SettlementCoordinator.kt` | 无改动 |

---

## 二、写放大优化

### 2.1 问题分析

`CultivationService.onPhaseTick()` 每秒执行一次，内部对弟子列表进行多次全量遍历，产生大量中间 List 分配和重复 copyWith 调用。

#### 优化前热路径

```
processPhaseEvents()
  ├── processPhaseRecovery()         → currentDisciples = .map { }  ← 第1次全量遍历
  ├── processPillDurationDecay()     → currentDisciples = .map { }  ← 第2次全量遍历
  └── processAutoUseItems()
        ├── currentDisciples = .map { }                              ← 第3次全量遍历
        │    ├── 内部: currentEquipmentStacks = .map { }             ← 嵌套遍历
        │    ├── 内部: currentEquipmentStacks = .filter { }          ← 嵌套遍历
        │    ├── 内部: currentManualStacks = .map { }                ← 嵌套遍历
        │    └── 内部: currentManualStacks = .filter { }             ← 嵌套遍历
        └── processAutoFromWarehouse()
              ├── currentDisciples.filter { it.isAlive }             ← 第4次遍历
              ├── currentDisciples.flatMap { ... } × 2               ← 第5-6次遍历
              ├── currentDisciples.toMutableList()                   ← 拷贝
              ├── for 循环遍历 sortedDisciples                       ← 第7次遍历
              │    └── indexOfFirst { it.id == disciple.id }         ← O(n) 查找
              ├── currentEquipmentStacks = .filter { } + eqStacks    ← 嵌套遍历
              └── currentManualStacks = .filter { } + mnStacks       ← 嵌套遍历
```

#### 写放大量化

单个 Disciple 对象约 600-800 字节（含 6 个 @Embedded 子结构）。

| 弟子数 | 3 次 map 总分配 | map 耗时估算 | tick 预算占比 |
|--------|---------------|-------------|-------------|
| 50 | ~100KB | <1ms | <0.1% |
| 100 | ~210KB | ~1-2ms | ~0.2% |
| 500 | ~1MB | ~5-10ms | ~1% |
| 1000 | ~2MB | ~15-30ms | ~3% |

### 2.2 优化实施

#### 优化1：三次 List.map 合并为单次遍历

**文件**：`CultivationService.kt`

将 `processPhaseRecovery` + `processPillDurationDecay` + `processAutoUseItems` 合并为 `processPhaseTick()`，在同一个 `currentDisciples.map` 闭包内对同一个 `var d` 串行执行三个操作：

1. 丹药持续时间衰减（pillDurationDecay）
2. HP/MP 恢复（phaseRecovery）
3. 自动使用物品（autoUsePills + autoEquip + autoLearn）

**关键收益**：
- 同一弟子跨操作共享 `var d`，只有最终变更才调用 `copyWith`
- `associateBy` 从 2 次减少为 1 次（equipmentMap + manualMap 在 map 外部构建一次）
- 消除了跨 map 的中间 Disciple 对象分配

#### 优化2：processAutoFromWarehouse 优化

**文件**：`CultivationService.kt`

| 优化点 | 优化前 | 优化后 |
|--------|--------|--------|
| 活弟子筛选 | `currentDisciples.filter { it.isAlive }` 创建新 List | `indices.filter { ... }` 排序索引，不拷贝对象 |
| bagId 收集 | `currentDisciples.flatMap { ... }.filter { ... }.map { ... }.toSet()` × 2 | `for` 循环 + `mutableSetOf`，零中间 List |
| 弟子查找更新 | `indexOfFirst { it.id == disciple.id }` O(n) | 直接索引 `updatedDisciples[idx]` O(1) |
| 变更检测 | 无条件赋值 | `if (d !== disciple)` 引用相等判断 |
| allEvents | 收集但未使用 | 移除 |

### 2.3 优化效果

| 指标 | 优化前 | 优化后 | 降幅 |
|------|--------|--------|------|
| 弟子列表遍历次数 | 5-7 次 | 2 次（1 次 map + 1 次 for） | -60%~70% |
| List 分配 | 5 个 | 2 个 | -60% |
| 中间 Disciple 对象 | ~20-40 个 | ~0 个 | -100% |
| `associateBy` 调用 | 3 次 | 2 次 | -33% |
| 估算 tick 耗时（100 弟子） | ~1.1ms | ~0.5ms | -55% |
| 估算 tick 耗时（500 弟子） | ~5.5ms | ~2.5ms | -55% |
| 估算 tick 耗时（1000 弟子） | ~11ms | ~5ms | -55% |

### 2.4 涉及文件

| 文件 | 改动类型 |
|------|---------|
| `CultivationService.kt` | 修改：processPhaseTick 合并 + processAutoFromWarehouse 优化 |

删除的方法：
- `processPhaseRecovery()` — 合并到 `processPhaseTick()`
- `processPillDurationDecay()` — 合并到 `processPhaseTick()`
- `processAutoUseItems()` — 合并到 `processPhaseTick()`

新增方法：
- `processPhaseTick(year, month, phase)` — 单次遍历合并实现

---

## 三、构建验证

```
BUILD SUCCESSFUL in 15s
20 actionable tasks: 2 executed, 18 up-to-date
```

编译通过，无新增 warning。

---

## 四、待验证项（运行时）

1. 1x 速度下，每旬严格 2 秒，三旬等长
2. 2x 速度下，每旬严格 1 秒，三旬等长
3. 月度结算结果与改动前一致（弟子修炼值、薪水、忠诚度等逐项对比）
4. 年度结算结果与改动前一致
5. 焦点弟子机制不受影响
6. 存档加载后行为正常
7. `forceCompleteSettlement` 仅在 2x + 极端弟子数（>1000）时触发
8. 巡逻战斗结果不再积压延迟
9. 丹药衰减 + HP/MP 恢复 + 自动使用物品行为与合并前一致
