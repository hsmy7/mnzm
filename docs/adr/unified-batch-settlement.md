# ADR: 统一批量结算模式（移除活跃/空闲双模式）

- **日期**: 2026-06-26
- **更新**: 2026-06-27
- **状态**: ✅ 已完成 — 两阶段全部实施完毕
- **决策者**: 架构评审

---

## Context

当前游戏引擎存在两套结算路径：

### 活跃模式（用户 30s 内有交互）
- 月变时立即调用 `scheduleMonthly` / `scheduleYearly`（分阶段结算，SettlementScheduler 驱动）
- 阶段包括：BuildCache → FocusedDisciple → CleanBatch → DirtyBatch → Production → WorldEvents → Aging
- 设备过热时进入热控分批（`ACTIVE_NON_FOCUS`），非焦点域推迟结算

### 空闲模式（用户 30s 无交互）
- 全部域进入 `accumulateBatch` 累积
- 30s 墙壁时钟触发全量结算（`doBatchFullSettle`）
- 用户回归时通过 `pendingReturnFromIdleSettle` 等待当前 tick 完成结算

### 两模式共享
- **焦点域**（当前 Tab/Dialog 决定的 FocusDomain）：100ms tick
- **实时轨**（进度 ≥80% 的槽位，通过 `classifySlotsProgress` 判定）：100ms tick
- **批量轨**（其余所有）：活跃模式按月立即结算 / 空闲模式 30s 批量

### 问题
1. `tickInternal()` 有两个几乎一样的分支（~150 行），月变事件处理写了两遍
2. 模式切换是 bug 高发区：`pendingReturnFromIdleSettle` 时序、`onUserInteraction` 空闲退出逻辑、后台暂停时空闲状态清理
3. 热控分批（`ACTIVE_NON_FOCUS`）仅设备过热时触发，常温下逻辑几乎不执行，但复杂度散布在 `checkBatchClock`、`resolveThermalBatchSize`、`accumulateBatch` 中
4. 存在死代码：`doIdleFullSettle()`、`fullIdleSettle()` 声明但从未被调用
5. `scheduleMonthly`/`scheduleYearly` 的分阶段 Scheduler 驱动方式与 `accumulateBatch` 的累积微结算方式功能等价但实现不同，增加维护负担

---

## Decision

**移除活跃/空闲双模式，统一为批量累积模式：**

### 核心规则
- **实时轨 + 焦点域**：始终 100ms tick
- **批量轨**：始终 30s 墙壁时钟批量结算
- **月变/年变**：仅触发事件（商人刷新、招募刷新、任务检测），不再触发 `scheduleMonthly`/`scheduleYearly`

### 统一后的 tickInternal 流程

```
tickInternal()
  → gameClock.tick(isSettlementPending = false)
  → for each phase:
      → systemManager.onPhaseTickWithDomainFilter(activeDomains)
      → HP/MP 恢复（焦点域）
      → 月变/年变检测
  → 月变/年变事件
  → accumulateBatch(phasesToAdd, monthChanged, yearChanged, ...)
      → 双指纹检测 → 变化? → 微结算 + 重建缓存
      → 30s 墙壁时钟? → 全量结算 + swap + 重建窗口
```

### 具体改动

#### SettlementCoordinator
1. 删除 `BatchMode` 枚举（`IDLE` / `ACTIVE_NON_FOCUS`），替换为 `isInBatchMode: Boolean`
2. 删除 `resolveThermalBatchSize()` — 热控批次大小逻辑
3. `checkBatchClock()` 统一为墙壁时钟判断（原 IDLE 分支）
4. 删除 `fullIdleSettle()` 死代码
5. 新增 `resetBatchClock()` — 用户交互时重置 30s 计时器
6. `enterBatchMode()` 移除 `mode` 参数

#### GameEngineCore
1. 删除字段：`isInIdleState`、`lastUserInteractionTime`、`pendingReturnFromIdleSettle`、`previousFocusDomain`、`isForceCompleting`
2. 删除常量：`IDLE_DETECTION_MS`
3. 删除方法：`enterIdleMode()`、`cleanupIdleState()`、`doIdleFullSettle()`、`forceCompleteSettlement()`
4. `getActiveDomains()` 简化为：`computeDomainsFromView() + batchRealtimeDomains`
5. `onUserInteraction()` 简化为：`settlementCoordinator.resetBatchClock()`
6. `catchUpDomain()` 简化为：`domainLastTickTime.remove(domain)`
7. `tickInternal()` 统一为单一路径（无空闲/活跃分支）
8. `startGameLoop()` 中初始化批量模式

#### GameEngineCoordination
1. `setFocusedDiscipleId()` / `setActiveTab()` / `setActiveDialog()` 移除 `onUserInteraction()` 调用
2. `notifyUserInteraction()` 保留（UI 层用它重置批量时钟）

#### 测试
1. `IdleModeSettlementTest` — 删除空闲域过滤测试（~7 个）+ onUserInteraction 退出测试（~3 个），重命名为 `BatchSettlementTest`
2. `SettlementCoordinatorTest` — 删除热控批次测试（~15 个）
3. `DomainMappingTest` / `GameStateStoreMergeTest` — 无需改动

---

## Consequences

### 正面影响
1. **`tickInternal` 复杂度降低 ~50%**：从 ~210 行双分支 → ~100 行单一路径
2. **消除模式切换 bug**：不再有 `isInIdleState` 状态转换、`pendingReturnFromIdleSettle` 等待逻辑
3. **去除未使用的抽象**：`BatchMode` 枚举、热控分批、`BACKGROUND` 域特殊处理
4. **清理死代码**：`doIdleFullSettle()`、`fullIdleSettle()`、`resolveThermalBatchSize()`
5. **代码更易理解**：一条结算路径，不需要在两种模式间切换心智

### 负面影响
1. **非焦点域最多延迟 30s**：在旧活跃模式下非焦点域每月立即结算，统一后延迟到批量结算窗口。但焦点域 + 实时轨已覆盖玩家可见内容，非焦点域延迟不可感知
2. **`scheduleMonthly`/`scheduleYearly` 成为未使用 API**：保留但不再从 `tickInternal` 调用
3. **改动触及游戏循环核心**：`tickInternal` 是最高风险变更点

### 风险评估
- **风险等级**：中等。逻辑上等价（统一模式 = 空闲模式行为），但改动范围涉及核心循环
- **回滚方案**：git revert，改动集中在 3 个核心文件
- **验证方案**：编译 + 全部单元测试 + 运行时验证（月变事件、焦点域更新、批量结算触发）

### 不改动的内容
- `scheduleMonthly` / `scheduleYearly` 保留在 `SettlementCoordinator` 公开 API 中
- `SettlementScheduler` 及相关阶段类保留
- `FocusDomain.BACKGROUND` 枚举值保留（系统仍使用）
- UI 层 `onUserInteraction` 回调链路保留

---

---

## 实施进度（2026-06-27）

### 已完成

1. **GameSystem 接口升级** — `onPhaseTick(state, phasesToSettle: Int = 1)`，批量轨传入跳过旬数
2. **SystemManager 累积调度** — `onPhaseTickWithDomainFilter` 新增 `getPhasesToSettle` lambda
3. **GameEngineCore 跳过计算** — `phasesSkippedForDomain` 根据域上次执行时间 + 游戏速度计算跳过旬数
4. **修炼直接写入** — `batchSettleCultivation` 直接写 `tables.cultivations`，不再走 HFD 中转
5. **HP/MP 倍率恢复** — `recoverHpMpForAllDisciples(state, phasesToSettle)`，批量轨一次补完
6. **月度结算修炼阶段移除** — `scheduleMonthly(skipCultivation=true)`，Phases_BuildCache/FocusedDisciple/CleanBatch/DirtyBatch 永久跳过
7. **防双计代码清理** — `addFocusedPhaseCount` 删除，修炼单一路径不存在双计

### 当前结算路径

```
实时轨 (100ms)     → onPhaseTick(1)     焦点域 + 80%进度
批量轨 (30s)       → onPhaseTick(N)     非焦点域累积补完
月度结算 (剩)      → 生产 + 世界事件     scheduleMonthly(skipCultivation=true)
月度事件           → 外交/盗窃/任务      processMonthlyEvents
年度结算/事件      → 老化/死亡/招募/盟约  scheduleYearly + processYearlyEvents
```

---

## 待优化：进一步削减结算路径

以下系统按月结算，可逐个迁入 `onPhaseTick(N)`，进一步消灭 `scheduleMonthly`：

### 可直接迁（简单计算，无依赖问题）

| 系统 | 当前路径 | 迁入方式 |
|------|----------|----------|
| EconomySubsystem | scheduleMonthly → Phase_WorldEvents | `economyService.processPolicyCosts(state)` × 跨月数 |
| MailSystem | scheduleMonthly → processWorldEventsMonthlyOnly | `mailSystem.onMonthTick(state)` × 跨月数 |
| ChildBirthSystem | 同上 | `childBirthSystem.onMonthTick(state)` × 跨月数 |
| PartnerSystem | 同上 | `partnerSystem.onMonthTick(state)` × 跨月数 |
| ProductionSubsystem | scheduleMonthly → Phase_Production | 将 `onMonthTick` 逻辑迁入 `onPhaseTick`（已有框架） |

### 可迁但需注意（有 shadow 外交互）

| 系统 | 注意点 |
|------|--------|
| 外交好感 | 当前在 `processMonthlyEvents`（shadow 外 `stateStore.update`）。迁入 `onPhaseTick` 后改用事务内 `state` 直接写 |
| 任务刷新/侦察过期 | 同上，注意 shadow 内外差异 |

### 暂不值得迁

| 系统 | 原因 |
|------|------|
| 年度老化/死亡 | 多阶段串行，年变频率极低（一年一次） |
| 年度盟约过期/好感衰减 | 同上 |
| 招募刷新 | 同上 |

### 迁完后的理想状态

```
只剩 2 条结算路：
  实时轨 (100ms) → 所有焦点域系统 onPhaseTick(1)
  批量轨 (30s)   → 所有非焦点域系统 onPhaseTick(N)

年度事件        → 老化/死亡/招募/盟约（一年一次，保留独立路径）
```

`scheduleMonthly` 完全移除，`SettlementCoordinator` 仅保留指纹计算和 80% 实时轨分类。

---

## 参考

- [结算管线 — SettlementCoordinator](../../CODE_WIKI.md#结算管线--settlementcoordinator) — 当前架构文档
- [FocusDomain.kt](../core/engine/src/main/java/com/xianxia/sect/core/system/FocusDomain.kt) — 焦点域定义
- [GameEngineCore.kt](../core/engine/src/main/java/com/xianxia/sect/core/GameEngineCore.kt) — 游戏循环入口
- [SettlementCoordinator.kt](../core/engine/src/main/java/com/xianxia/sect/core/domain/settlement/SettlementCoordinator.kt) — 结算协调器
