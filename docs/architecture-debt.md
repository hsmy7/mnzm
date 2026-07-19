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

**难度：** 低

### 8. 广告回调透传链膨胀（`onWatchAdBreakthroughBonus` / `onWatchAdMerchantRefresh`）

**问题描述：** 激励视频广告的回调参数 `onWatchAdBreakthroughBonus` 穿过 5 层（GameActivity → MainGameScreen → GameOverlayHost → DiscipleDetailScreen → DetailCultivationSection）。新增的 `onWatchAdMerchantRefresh` 同样透传 4 层（GameActivity → MainGameScreen → GameOverlayHost → MerchantDialog）。每新增一种广告类型就需要在所有中间层新增一个参数。

**影响范围：**
- `GameActivity.kt`
- `MainGameScreen.kt`
- `GameOverlayHost.kt`
- `DiscipleDetailScreen.kt`
- `DetailCultivationSection.kt`
- `MerchantDialog.kt`

**修复方向：**
- 事件总线/单 `AdCallback` 接口统一管理所有广告回调
- 或注入 `RewardVideoAdManager` 到 ViewModel 层，由 ViewModel 直接处理回调，消除 UI 透传

**难度：** 低

---

### 9. `GameOverlayHost` 参数数量膨胀

**问题描述：** `GameOverlayHost` 参数列表包含 14 个 ViewModel + 约 10 个状态/回调参数。虽未突破 7 参数上限（非构造函数），但可读性和可维护性持续下降。

**影响范围：** `GameOverlayHost.kt`

**修复方向：**
- 将 ViewModel 组和回调组分别聚合为 data class 或接口
- 或拆分为多个专用 Overlay slot（如 `AdOverlayHost`、`DialogOverlayHost`）

**难度：** 低

| 项 | 判定 | 说明 |
|---|------|------|
| 深层 DAO 链路 suspend（ProductionSlotRepository 12 方法/SavePipeline/MailService） | ✅ 不需要做 | IO/网络/存档路径保留 suspend 是合理设计，不在 `stateStore.update` 内调用，无死锁风险 |

---

### 10. RunBlocking 消除后 `ProductionTransactionManager` 仍有 2 处残留

**问题描述：** `ProductionTransactionManager.executeStartProductionByBuildingId` 方法使用了 `runBlocking(Dispatchers.IO)` 等待 DB 结果（`repository.addSlot` 和 `repository.updateSlotByBuildingId`）。与其他 11 处 runBlocking 不同，这两处需要同步获取 DB 操作的返回值，无法简单替换为 `scope.launch`。已在 v4.0.59 中评估过将其改为 `suspend` + `withContext(Dispatchers.IO)`，但会使调用链（ProductionCoordinator → ProductionProcessor → CultivationService → GameEngineCore）全链路 suspend 化，影响较大。

**影响范围：**
- `ProductionTransactionManager.kt` — `executeStartProductionByBuildingId` 方法
- `ProductionCoordinator.kt` — 调用方
- `ProductionProcessor.kt` — 上游调用方

**修复方向：**
- 将 `executeStartProductionByBuildingId` 改为 `suspend` + `withContext(Dispatchers.IO)`
- 全链路 suspend 化上游调用方
- 替换 2 处 `runBlocking` 为 `withContext`

**难度：** 中

---

### 11. `LawEnforcementProcessor` 与 `CultivationEventProcessor` 代码重复

**问题描述：** v4.0.59 重构时从 `CultivationEventProcessor`（946 行）拆出了 `LawEnforcementProcessor`（270 行），但因行数过多、深度耦合，未将旧代码从 CEP 中移除。目前两处各有一套完整的执法/偷窃逻辑，代码功能相同但实现有细微差异。

**影响范围：**
- `CultivationEventProcessor.kt` — 保留 ~270 行旧执法/偷窃代码
- `LawEnforcementProcessor.kt` — 新拆分文件，相同的逻辑

**修复方向：**
- 将 CEP 的执法方法改为委托到 `LawEnforcementProcessor`（只需加构造参数 + 改 5 个方法为一行委托）
- 验证两套实现在边界条件下行为一致（属性值计算、RNG 序列）
- 通过后移除 CEP 中的旧代码

**难度：** 低（文本操作风险高，需 IDE 级重构工具）

---

### 11. `CultivationCore`（1139 行）未拆分

**问题描述：** `CultivationCore.kt`（1139 行）混合 HP/MP 恢复、自动丹药、装备孕养、功法熟练度、修炼速率计算、战斗结算等 6 项职责。设计拆分方案为 6 个子文件但未实施。

**影响范围：** `CultivationCore.kt`

**修复方向：**
- `HpMpRecoveryService` — HP/MP 恢复逻辑
- `AutoPillService` — 自动丹药检测 + 服用
- `EquipmentNurtureService` — 装备孕养
- `ManualProficiencyService` — 功法熟练度
- `CultivationRateCalculator` — 修炼速率乘区计算
- `BattleSettlementService` — 战斗前全量追赶

**难度：** 高（21 个构造参数深度耦合，需 IDE 级 Extract Class）

---

### 12. `DiscipleService`（995 行）未拆分

**问题描述：** `DiscipleService.kt`（995 行）包含 5+ 职责：弟子 CRUD、生命事件管理、状态管理、装备穿卸、招募/逐出、师徒系统。设计拆分方案为 4 个子文件但未实施。

**影响范围：** `DiscipleService.kt`

**修复方向：**
- `DiscipleLifecycleManager` — CRUD + 生命事件 + 状态管理
- `DiscipleSlotManager` — 槽位类型构建器 + 状态同步
- `DiscipleEquipmentService` — 装备穿卸
- `DiscipleMasterApprenticeService` — 师徒系统

**难度：** 高（深度耦合，需 IDE 级 Extract Class）

---

### 13. 广告回调透传链未重构（原债务 #8 未处理）

**问题描述：** 广告回调参数 `onWatchAdBreakthroughBonus` / `onWatchAdMerchantRefresh` 仍以参数形式穿过 5 层（GameActivity → MainGameScreen → GameOverlayHost → DiscipleDetailScreen → DetailCultivationSection）。原计划注入 `RewardVideoAdManager` 到 ViewModel 层消除透传，未实施。

**影响范围：**
- `GameActivity.kt`
- `MainGameScreen.kt`
- `GameOverlayHost.kt`
- `DiscipleDetailScreen.kt`
- `DetailCultivationSection.kt`
- `MerchantDialog.kt`

**修复方向：**
- 创建 `RewardVideoAdManager` 单例注入 GameViewModel
- ViewModel 直接处理广告回调，UI 层移除透传参数

**难度：** 低

---

### 14. `GameEventBus` 未完成事件迁移

**问题描述：** `GameEventBus` 已标记 `@Deprecated`，但事件未迁移到 `EventBus`（GameEvents.kt），两套总线仍并存运行。

**影响范围：**
- `GameEventBus.kt`
- `EventBus`（GameEvents.kt）

**修复方向：**
- 为 GameEventBus 的 5 事件类型在 EventBus 中创建对应事件子类
- 逐个迁移发射点
- 移除 GameEventBus

**难度：** 低

---

### 15. 死代码未真删除

**问题描述：** 以下死代码仅标记了 `@Deprecated` 或注释说明，未实际删除：
1. `GameStateStore.createSettlementShadow` / `swapFromShadow`
2. `GameStateStoreImpl` 中对应的实现
3. `GameData.discipleDesertionPopup` 字段（需 Migration）

**影响范围：**
- `GameStateStore.kt`
- `GameStateStoreImpl.kt`
- `GameData.kt`
- `GameDatabase.kt`（Migration 22→23）

**修复方向：**
- 移除接口和实现方法
- 移除字段 + 新增 Migration DROP COLUMN
- 清理测试中 Mock 桩方法

**难度：** 低
