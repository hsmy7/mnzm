# 架构债务记录

> 本文件记录已知的架构债务和待完成的技术改进项。

## 待完成项

### 1. `DiscipleSlotManager.syncAllDiscipleStatuses` 重复实现（已标记 @Deprecated）

**问题描述：** `DiscipleSlotManager` 中有一个 `syncAllDiscipleStatuses` 的实现，与 `DiscipleStatusService` 中的功能重复。该实现已标记 `@Deprecated` 指向 `DiscipleStatusService`，但代码本体未删除。

**影响范围：** `DiscipleSlotManager.kt`

**修复方向：** 删除重复代码及相关的 `fixInvalidMiningSlots`、`buildXxxIds` 等私有辅助方法。

**难度：** 低

---

### 2. `openStorageBag` 双事务（储物袋消耗与奖励发放分离）

**问题描述：** `InventoryFacadeImpl.openStorageBag` 分两阶段执行：Phase 1（单事务）消耗储物袋，然后 RNG 生成奖励，Phase 2（单事务）发放奖励物品。若进程在 Phase 1 提交后、Phase 2 开始前崩溃，储物袋已消耗但奖励未发放。

**影响范围：** `InventoryFacadeImpl.kt` ~835-1018 行

**修复方向：** 将储物袋消耗移入 Phase 2 同一事务内——先计算奖励列表，再在单事务内同时执行消耗袋子 + 发放奖励。

**难度：** 低

---

### 3. `processAutoAssign` 5 步非原子（事务分裂）

**问题描述：** `ProductionProcessor.processAutoAssign` 使用 5 个独立 `stateStore.update` 事务分别处理住所/灵植/灵矿/炼丹/锻造的自动分配。若进程在中间步骤崩溃，部分分配已提交但后续步骤未执行，导致弟子状态不一致。

**影响范围：** `ProductionProcessor.kt` ~571-775 行

**修复方向：** 将所有分配步骤合并为单次 `stateStore.update` 事务。

**难度：** 高（需要对 `takeCandidate` 等辅助函数做事务上下文改造）

---

### 4. 住所分配不更新弟子状态表

**问题描述：** `ProductionProcessor.processAutoAssign` 中的住所分配只更新 `residenceSlots`，不更新 `discipleTables.statuses`。已入住的弟子状态保持 `IDLE`，UI 仍显示可手动分配给其他槽位，可能导致"既住住所又在生产"的不一致状态。

**影响范围：** `ProductionProcessor.kt` ~681-694 行

**修复方向：** 在住所分配的事务内同步设置 `discipleTables.statuses[id] = IDLE`（或专用状态 `RESIDING` 的新增更合适）。

**难度：** 低

---

### 5. 广告回调透传链（原 #8/#14）

**问题描述：** `GameViewModel` 上的 `onWatchAdBreakthroughBonus` / `onWatchAdMerchantRefresh` 仍使用 `var` 回调属性，未注入 `RewardVideoAdManager` 单例。新增广告类型仍需在 ViewModel 加字段，非类型安全。

**当前改进（2026-07-20）：**
- 透传层从 5 层降至 2 层（GameActivity → ViewModel → 消费端）
- `RewardVideoAdManager` 已是 Kotlin `object` 单例

**修复方向：**
- 移除 ViewModel 的 `var` 回调属性
- 消费端（`DetailCultivationSection`、`MerchantDialog`）改为直接调用 `RewardVideoAdManager`
- 新增广告类型无需改 ViewModel

**难度：** 低

**优先级：** ⏸️ 低（当前实现可接受）

---

### 6. `lastTheftMonths` / `lastTheftMonth` 写而不再读

**问题描述：** 移除单弟子偷盗冷却后，`UsageTracking.lastTheftMonth` 和 `DiscipleTables.lastTheftMonths` 在成功偷盗后仍在写入，但不再用于冷却判定。冷却逻辑已由 `annualTheftCount` 年上限完全替代。

**影响范围：** `DiscipleComponents.kt:188`、`DiscipleTables.kt:202`、`LawEnforcementProcessor.kt`

**修复方向：**
- 从 `UsageTracking` 和 `DiscipleTables` 中移除 `lastTheftMonth` / `lastTheftMonths` 字段
- 删除 `executeSuccessfulTheft` 两版本中的 `.copy(lastTheftMonth = currentMonth)` 写入

**难度：** 低

**注意：** `DiscipleComponents.kt` 中 `lastTheftMonth` 字段删除会改变 Room schema，如需彻底清除需：
1. 新 Migration 从 game_data 表 DROP COLUMN
2. DiscipleTables 组件表对应列一并移除

## 写入守卫架构债务（⏸️ 暂不修复）

详见 [architecture-debt-write-guard.md](architecture-debt-write-guard.md)，6 项低风险守卫设计限制记录在案：
1. `store` 底层存储绕过守卫
2. `requireWrite` / `onWrite` 为 `@JvmField var`
3. `writeGuardEnabled` 全局开关
4. `ids` public MutableList
5. `deathRecords` public MutableList
6. 影子结算死代码（已移除守卫兼容代码）

---

## 已完成清单（备查）

以下 31 项架构债务已全部完成：

| # | 项目 | 完成日期 | 核心变更 |
|---|------|---------|---------|
| 1 | 通知系统单值覆盖 | v4.0.58+ | ConcurrentLinkedQueue + StateFlow API |
| 2 | clearForgeSlotsIfNeeded runBlocking | v4.0.58+ | scope.launch(IO) 异步化 |
| 3 | handleDiscipleDeath 多事务 | 2026-07-24 | processDiscipleAging 合并为单事务 |
| 4 | recruitingDiscipleIds 锁 | 2026-07-19 | synchronized 包裹所有操作 |
| 5 | **processCompletedMissionsLazy 单事务** | **2026-07-24** | **Phase 1→Phase 2 合并（CultivationEventProcessor）** |
| 6 | 执法/偷盗读-写窗口 | 2026-07-24 | CEP 委托 LawEnforcementProcessor |
| 7 | discipleDesertionPopup 废弃字段 | 2026-07-19 | MIGRATION_22_23 DROP COLUMN |
| 8 | 广告回调透传尾(部分) | 2026-07-20 | MainGameScreen/OverlayHost 透传消除 |
| 9 | GameOverlayHost 参数膨胀 | v4.0.58+ | 18→2 聚合 data class |
| 10 | RunBlocking 残留 | v4.0.58+ | ProductionTransactionManager 全链路 suspend |
| 11 | CEP ↔ LawEnforcementProcessor 重复 | 2026-07-24 | CEP 委托 + 471 行死代码删除 |
| 12 | CultivationCore 拆分 | 2026-07-20 | 1139→356 行，6 子服务 |
| 13 | DiscipleService 拆分(部分) | 2026-07-20 | 995→574 行，4 子服务 |
| 14 | **DiscipleStatusService 提取** | **2026-07-24** | **+新服务，DiscipleService 358 行** |
| 15 | GameEventBus 迁移 | v4.0.58+ | 5 事件迁移到 EventBus |
| 16 | 死代码清理 | 2026-07-20 | 影子结算 + SettlementStrategy 移除 |
| 17 | assignToResidence 覆盖槽位 | 2026-07-23 | 原子方法 + 原住户释放 |
| 18 | assignToResidence 非原子 | 2026-07-23 | 6 原子方法单事务 |
| 19 | CancellationException 重抛 | 2026-07-23 | 全部 catch 块添加重抛 |
| 20 | fire-and-forget 返回值 | 2026-07-23 | DomainResult 返回值 |
| 21 | SaveValidator 4 规则 | 2026-07-24 | 5 新规则 (15→19) |
| 22 | SaveValidator 补充修复 | 2026-07-24 | SaveValidatorFixes + CorruptedResultHandler + StorageEngine 二次验证 |
| 23 | EntityCountBoundsRule | 2026-07-24 | 3 阈值警告 |
| 24 | handleDiscipleDeath 单事务 | 2026-07-24 | Phase 2+3 合并 |
| 25 | CEP 执法委托 + 死代码 | 2026-07-24 | 471 行删除，CEP 1189→718 |
| 26 | **buyMerchantItem 扣灵石后移** | **2026-07-24** | **先加物品后扣灵石（InventoryFacadeImpl）** |
| 27 | **sortWarehouse 单事务** | **2026-07-24** | **consolidate+sort 合并（InventorySystem）** |
| 28 | **AutoBuyService → StackableItemStore** | **2026-07-24** | **addToWarehouse 统一入口** |
| 29 | **openStorageBag 单事务** | **2026-07-24** | **~20 次 update 合并为 1 次** |
| 30 | **bulkSellItems 简化** | **2026-07-24** | **deductStack 辅助消除 6 路重复** |
| 31 | **processAutoAssign 迁入 stateStore** | **2026-07-24** | **batchAssignToProductionSlots 去 Room DAO** |
