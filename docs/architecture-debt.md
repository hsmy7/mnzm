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

## 不纳入债务（已完成评估）

| 项 | 判定 | 说明 |
|---|------|------|
| 深层 DAO 链路 suspend（ProductionSlotRepository 12 方法/SavePipeline/MailService） | ✅ 不需要做 | IO/网络/存档路径保留 suspend 是合理设计，不在 `stateStore.update` 内调用，无死锁风险 |
