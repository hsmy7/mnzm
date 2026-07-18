# 架构债务记录

> 本文件记录已知的架构债务和待完成的技术改进项。

## 待完成项

### 1. 通知系统单值覆盖问题

**问题描述：** `pendingNotification` 是 `GameStateStore` 上的单值字段（`MutableStateFlow<GameNotification?>`），不是队列。每次 `stateStore.update` 直接覆盖。如果同帧内多个系统设置通知（如招募失败 + 血炼完成），只有最后一个生效，前面的通知丢失。

**影响范围：** `GameStateStore.setPendingNotification` 所有调用方

**修复方向：**
- 改为通知队列 `MutableStateFlow<List<GameNotification>>`，UI 逐个消费
- 或引入 EventBus 事件，对话框由 EventBus 驱动而非 StateFlow

**难度：** 中（涉及 UI 渲染层改动）

---

### 2. `clearForgeSlotsIfNeeded` 的 `runBlocking` 阻塞 gameDispatcher

**问题描述：** `DiscipleLifecycleProcessor.clearForgeSlotsIfNeeded` 在 gameDispatcher 线程上执行 `runBlocking(Dispatchers.IO)`，阻塞游戏循环等待 Room DB 写入。多名弟子同时死亡时循环重复阻塞。

**影响范围：**
- `DiscipleLifecycleProcessor.kt:348`
- `ProductionSlotRepository.updateSlotByBuildingId`

**修复方向：**
- 将 `clearForgeSlotsIfNeeded` 改为异步：`scope.launch { ... }`
- 或将 forge 槽位数据纳入 `GameData`，消除对 `productionSlotRepository` 的依赖
- 或使全链路支持 suspend（需改变 `clearDiscipleFromAllSlots` → `handleDiscipleDeath` → `processDiscipleAging` 签名链）

**难度：** 高

---

### 3. `handleDiscipleDeath` 多事务非原子

**问题描述：** `processDiscipleAging` 中每个 `handleDiscipleDeath` 包含多个独立 `stateStore.update` 事务（槽位清理 + replaceAll + 死亡记录）。中间异常由 `safelyRun` 隔离但可能导致部分更新（槽位已清空但死亡未记录）。

**影响范围：** `DiscipleLifecycleProcessor.processDiscipleAging`

**修复方向：**
- 将死亡处理合并为单事务
- 或在事务外预计算全部变更后一次写入

**难度：** 中

---

### 4. `recruitingDiscipleIds` 守卫锁不一致

**问题描述：** `DiscipleDelegate.recruitDiscipleFromList` 中 `recruitingDiscipleIds` 的 `contains/add/remove` 不使用 `synchronized(recruitingLock)`，而 `recruitAllDisciples` 中的检查使用。当前单线程主线程访问安全，但若切换为多线程调度器立即失效。

**影响范围：** `DiscipleDelegate.kt`

**修复方向：**
- 统一使用 `synchronized(recruitingLock)` 保护 `recruitingDiscipleIds` 的所有读写
- 或改用 `ConcurrentHashMap.newKeySet()`

**难度：** 低

---

### 5. `processCompletedMissionsLazy` 多事务 + God Method

**问题描述：** `CultivationEventProcessor.processCompletedMissionsLazy`（约 67 行）混合循环控制、事务管理、奖励分发、状态更新四个职责。每个任务完成产生约 10+ 个独立 `stateStore.update` 事务（灵石/材料/丹药/装备/功法各一个）。异常时：

- 部分已提交的奖励不回滚，但任务从 `activeMissions` 移除，玩家永久丢失剩余奖励
- `CancellationException` 在 `completedIds.add()` 执行后抛出导致任务永久消失且部分奖励已发放

**影响范围：** `CultivationEventProcessor.kt:830-897`

**修复方向：**
- 单个任务的所有奖励分发和状态变更整合为单原子事务
- `completedIds` 收集与 `remainingActive` 构建应在同一事务内完成
- `CancellationException` 应在任务循环内部用 `runCatching` 隔离

**难度：** 高

---

### 6. 执法/偷盗窃取处理读-写窗口

**问题描述：** `CultivationEventProcessor.processLawEnforcementMonthly()` 和 `processTheftMonthly()` 在函数开始时读取快照（`atRiskIds`），稍后在循环中逐条打开 `stateStore.update` 写入。各 `safelyRun` 块顺序调用，快照与写入之间状态可能已被前一块修改。

- `assemble(id) ?: return@update` 在弟子已被前序事务移除时静默跳过，无日志记录
- 整体事务拆分模式无法保证跨块一致性

**影响范围：** `CultivationEventProcessor.kt:387-658`

**修复方向：**
- 将整批执法/偷窃合并为单事务内操作
- 或改用乐观锁+重试模式
- `return@update` 的静默跳过路径应增加 `DomainLog.w` 记录

**难度：** 高

---

### 7. `GameData.discipleDesertionPopup` 废弃字段

**问题描述：** 消息栏系统移除了"弟子脱离宗门弹出提示框"功能，`GameData.discipleDesertionPopup: Boolean` 字段不再被 UI 引用。但字段仍留在 Room schema 和 ProtoBuf 序列化中，占用存储。

**影响范围：** `GameData.kt`（字段）、`GameDatabase`（Migration 历史中有该列）

**修复方向：**
- 在下一次 Schema 版本变更时删除该字段
- 需新 Migration 做列删除（`ALTER TABLE ... DROP COLUMN`，SQLite ≥ 3.35.0）

**难度：** 低## 不纳入债务（已完成评估）

| 项 | 判定 | 说明 |
|---|------|------|
| 深层 DAO 链路 suspend（ProductionSlotRepository 12 方法/SavePipeline/MailService） | ✅ 不需要做 | IO/网络/存档路径保留 suspend 是合理设计，不在 `stateStore.update` 内调用，无死锁风险 |
