# 架构债务记录

> 本文件记录已知的架构债务和待完成的技术改进项。

## 待完成项

### 1. `processCompletedMissionsLazy` 多事务 + God Method

**问题描述：** `CultivationEventProcessor.processCompletedMissionsLazy` Phase 1 通过 `inventorySystem.addMaterial()` / `addPill()` / `addEquipmentStack()` / `addManualStack()` 等 **Room DAO 操作** 提交奖励，Phase 2 再开单次 `stateStore.update` 处理灵石 + 弟子状态 + `activeMissions` 更新。若 Phase 1 IO 操作成功后、Phase 2 stateStore.update 前进程崩溃 → 材料/丹药/装备/功法已写入 Room 数据库，但灵石未更新、任务未从 `activeMissions` 移除 → 玩家永久丢失灵石奖励、且任务不会再重新完成（IO 物品已双倍发放风险）。

**影响范围：** `CultivationEventProcessor.kt`

**修复方向：**
- 将 `inventorySystem` 操作并入 `stateStore.update` 事务内
- 或先将库存数据迁入 `GameStateStore` 体系，使所有奖励分发在同一原子事务内完成

**前置依赖：** 需评估 `inventorySystem` 的数据流向——库存数据是走 Room DAO 还是 stateStore

**难度：** 高

---

### 2. `DiscipleService`（574 行）待进一步分解

**状态：🔄 部分完成（已从 995 行降至 574 行，4 子服务已提取）**

**已完成：**
- `DiscipleService.kt` 从 995 行降至 574 行（减少 42%）
- 成功提取 4 个子服务：
  - `DiscipleLifecycleManager` — CRUD + 生命事件 + 状态管理
  - `DiscipleSlotManager` — 槽位类型构建器 + 状态同步
  - `DiscipleEquipmentService` — 装备穿卸
  - `DiscipleMasterApprenticeService` — 师徒系统
- `DiscipleService` 作为协调器委托给各子服务

**剩余工作：**
- 仍有 574 行，需进一步分解（可提取 `DiscipleStatusService` 处理状态同步逻辑）

**难度：** 低

---

### 3. 广告回调透传链（原 #8/#14）

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

以下 26 项架构债务已全部完成：

| # | 项目 | 完成日期 | 核心变更 |
|---|------|---------|---------|
| 1 | 通知系统单值覆盖 | v4.0.58+ | ConcurrentLinkedQueue + StateFlow API |
| 2 | clearForgeSlotsIfNeeded runBlocking | v4.0.58+ | scope.launch(IO) 异步化 |
| 3 | handleDiscipleDeath 多事务 | 2026-07-24 | processDiscipleAging 合并为单事务 |
| 4 | recruitingDiscipleIds 锁 | 2026-07-19 | synchronized 包裹所有操作 |
| 6 | 执法/偷盗读-写窗口 | 2026-07-24 | CEP 委托 LawEnforcementProcessor |
| 7 | discipleDesertionPopup 废弃字段 | 2026-07-19 | MIGRATION_22_23 DROP COLUMN |
| 8 | 广告回调透传尾(部分) | 2026-07-20 | MainGameScreen/OverlayHost 透传消除 |
| 9 | GameOverlayHost 参数膨胀 | v4.0.58+ | 18→2 聚合 data class |
| 10 | RunBlocking 残留 | v4.0.58+ | ProductionTransactionManager 全链路 suspend |
| 11 | CEP ↔ LawEnforcementProcessor 重复 | 2026-07-24 | CEP 委托 + 471 行死代码删除 |
| 12 | CultivationCore 拆分 | 2026-07-20 | 1139→356 行，6 子服务 |
| 13 | DiscipleService 拆分(部分) | 2026-07-20 | 995→574 行，4 子服务 |
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
