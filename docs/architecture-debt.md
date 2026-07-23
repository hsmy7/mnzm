# 架构债务记录

> 本文件记录已知的架构债务和待完成的技术改进项。

## 待完成项

### 1. 通知系统单值覆盖问题

**状态：✅ 已完成（v4.0.58+）**

**修复内容：**
- `GameStateStore` 接口已新增 `notifications: StateFlow<List<GameNotification>>`、`enqueueNotification()`、`consumeNotification()` API
- 实现使用 `ConcurrentLinkedQueue<GameNotification>`（上限 200 条，超出丢弃最旧）
- 旧 `setPendingNotification` / `clearPendingNotification` 已标记 `@Deprecated`，保留实现体保证向后兼容
- 生产代码中已无旧 API 调用方（仅测试 Fake 类和实现体自身仍有覆盖）

**验证结果（2026-07-19）：**
- ✅ `GameStateStore.kt` — 队列 API 存在，旧 API 标记 @Deprecated
- ✅ `GameStateStoreImpl.kt` — `ConcurrentLinkedQueue` + `_notificationsFlow` 完整实现
- ✅ 生产代码零调用 `setPendingNotification`

---

### 2. `clearForgeSlotsIfNeeded` 的 `runBlocking` 阻塞 gameDispatcher

**状态：✅ 已完成（v4.0.58+）**

**修复内容：**
- `DiscipleLifecycleProcessor.clearForgeSlotsIfNeeded` 已改为 `scope.launch(Dispatchers.IO)` 异步执行
- 不再阻塞 gameDispatcher 线程

**验证结果（2026-07-19）：**
- ✅ `clearForgeSlotsIfNeeded` 使用 `scope.launch(Dispatchers.IO)`，无 `runBlocking`
- ✅ 生产代码中 `runBlocking` 实际调用归零（仅测试 192 处使用）
- ✅ compileReleaseKotlin 通过

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

**状态：✅ 已完成（2026-07-19）**

**修复内容：**
- `DiscipleDelegate.recruitDiscipleFromList` 中 `recruitingDiscipleIds` 的 `contains`/`add`/`remove` 全部包裹在 `synchronized(recruitingLock)` 块内
- `finally` 块中的 `remove` 操作也加了同步保护，确保协程取消时仍线程安全

**验证：**
- ✅ `recruitDiscipleFromList` — `contains`+`add` 在 `synchronized` 块内
- ✅ `finally` 块 — `remove` 也在 `synchronized` 块内
- ✅ `recruitAllDisciples` — 已有 `synchronized` 保护，未改动
- ✅ 编译通过，零回归

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

**状态：✅ 已完成（2026-07-19）**

**修复内容：**
- `GameData.discipleDesertionPopup: Boolean` 字段已从 Room Entity 中移除
- 新增 `MIGRATION_22_23`：`ALTER TABLE game_data DROP COLUMN discipleDesertionPopup`
- 不再占用存储空间

**验证结果：**
- ✅ `discipleDesertionPopup` 字段已从 `GameData.kt` 中移除
- ✅ `MIGRATION_22_23` 测试已添加
- ✅ compileReleaseKotlin 通过

### 8. 广告回调透传链膨胀（`onWatchAdBreakthroughBonus` / `onWatchAdMerchantRefresh`）

**问题描述：** 激励视频广告的回调参数 `onWatchAdBreakthroughBonus` 穿过 5 层（GameActivity → MainGameScreen → GameOverlayHost → DiscipleDetailScreen → DetailCultivationSection）。新增的 `onWatchAdMerchantRefresh` 同样透传 4 层（GameActivity → MainGameScreen → GameOverlayHost → MerchantDialog）。每新增一种广告类型就需要在所有中间层新增一个参数。

**影响范围（原）：**
- `GameActivity.kt`
- `MainGameScreen.kt`
- `GameOverlayHost.kt`
- `DiscipleDetailScreen.kt`
- `DetailCultivationSection.kt`
- `MerchantDialog.kt`

**当前改进（2026-07-20）：**
- `MainGameScreen`、`GameOverlayHost`、`DiscipleDetailScreen` 已移除透传参数
- 回调改为 GameActivity 直接设置到 `GameViewModel` 的属性（`onWatchAdBreakthroughBonus` / `onWatchAdMerchantRefresh`）
- `DetailCultivationSection` 和 `MerchantDialog` 直接从 `GameViewModel` 读取回调
- 透传层从 5 层降至 2 层（GameActivity → ViewModel → 消费端）

**剩余问题：**
- 仍使用 `var` 回调属性，未注入 `RewardVideoAdManager` 单例
- 回调非类型安全，新增广告类型仍需在 ViewModel 加字段

**修复方向：**
- 创建 `RewardVideoAdManager` 单例注入 GameViewModel
- ViewModel 直接处理广告回调，UI 层移除透传参数

**难度：** 低

---

### 9. `GameOverlayHost` 参数数量膨胀

**状态：✅ 已完成（v4.0.58+）**

**修复内容：**
- 新增 `OverlayViewModels` 和 `OverlayCallbacks` 两个 data class
- 18 个参数降为 2 个 data class 参数
- 1000+ 行内部代码保持不变

**验证结果：**
- ✅ `GameOverlayHost` 构造参数从 18 个降至 2 个聚合 data class
- ✅ 功能无回归

---

### 10. RunBlocking 消除后 `ProductionTransactionManager` 仍有 2 处残留

**状态：✅ 已完成（v4.0.58+）**

**修复内容：**
- `executeStartProductionByBuildingId` 已改为 `suspend` + `withContext(Dispatchers.IO)`
- 全链路 suspend 化：ProductionCoordinator → ProductionProcessor → CultivationService → GameEngineCore
- 2 处 `runBlocking` 全部替换为 `withContext`

**验证结果（2026-07-19）：**
- ✅ `ProductionTransactionManager.executeStartProductionByBuildingId` 为 suspend 函数，无 runBlocking
- ✅ 生产代码中 runBlocking 实际调用归零

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

### 12. `CultivationCore`（1139 行）未拆分

**状态：✅ 已完成（2026-07-20）**

**修复内容：**
- `CultivationCore.kt` 从 1139 行降至 356 行（消除 69%）
- 成功拆分为 6 个子服务：
  - `HpMpRecoveryService` — HP/MP 恢复
  - `AutoPillService` — 自动丹药检测 + 服用
  - `EquipmentNurtureService` — 装备孕养
  - `ManualProficiencyService` — 功法熟练度
  - `CultivationRateCalculator` — 修炼速率乘区计算
  - `BattleSettlementService` — 战斗前全量追赶
- `CultivationCore` 现在作为轻量协调器，委托给各子服务

**验证结果（2026-07-20）：**
- ✅ `CultivationCore.kt` 仅 356 行
- ✅ 6 个子服务文件全部存在且正常运作
- ✅ compileReleaseKotlin 通过

---

### 13. `DiscipleService`（995 行）未拆分

**状态：🔄 部分完成（620 行，4 子服务已提取）**

**修复内容：**
- `DiscipleService.kt` 从 995 行降至 620 行（减少 38%）
- 成功提取 4 个子服务：
  - `DiscipleLifecycleManager` — CRUD + 生命事件 + 状态管理
  - `DiscipleSlotManager` — 槽位类型构建器 + 状态同步
  - `DiscipleEquipmentService` — 装备穿卸
  - `DiscipleMasterApprenticeService` — 师徒系统
- `DiscipleService` 现在作为协调器委托给各子服务

**剩余工作：**
- 仍有 620 行，需进一步分解

**验证结果（2026-07-20）：**
- ✅ 4 个子服务文件全部存在且正常运作
- ✅ compileReleaseKotlin 通过

---

### 14. 广告回调透传链未重构（原债务 #8 未处理）

**问题描述：** 广告回调参数 `onWatchAdBreakthroughBonus` / `onWatchAdMerchantRefresh` 原以参数形式穿过 5 层（GameActivity → MainGameScreen → GameOverlayHost → DiscipleDetailScreen → DetailCultivationSection）。

**当前改进：**
- `MainGameScreen`、`GameOverlayHost`、`DiscipleDetailScreen` 已移除透传参数
- 回调改为 GameActivity 直接设置到 `GameViewModel` 属性（`var` 回调属性）
- `DetailCultivationSection` 和 `MerchantDialog` 直接读取 ViewModel 上的回调
- 透传层从 5 层降至 2 层

**剩余问题：**
- 仍使用 `var` 回调属性，未注入 `RewardVideoAdManager`
- 新增广告类型仍需在 ViewModel 加字段，非类型安全

**修复方向：**
- 创建 `RewardVideoAdManager` 单例注入 GameViewModel
- ViewModel 直接处理广告回调，UI 层移除透传参数

**难度：** 低

---

### 15. `GameEventBus` 未完成事件迁移

**状态：✅ 已完成（v4.0.58+ — 验证于 2026-07-19）**

**修复内容：**
- `GameEventBus` 已标记 `@Deprecated("迁移到 EventBus（GameEvents.kt）")`
- 所有 5 个事件类型已迁移到 `EventBus`（`GameEvents.kt`）
- 生产代码中已无 `GameEventBus` 引用（仅定义文件自身保留）

**验证结果（2026-07-19）：**
- ✅ 搜索 `GameEventBus` 引用 — 仅定义文件自身
- ✅ `EventBus`（`GameEvents.kt`）完整实现 `DomainEvent` 通道式分发
- ✅ compileReleaseKotlin 通过

---

---

### 16. 死代码未真删除

**状态：✅ 已完成（2026-07-20）**

**修复内容：**
- `GameStateStore.createSettlementShadow` / `swapFromShadow` — 接口和实现已移除
- `GameData.discipleDesertionPopup` — 已移除（MIGRATION_22_23 DROP COLUMN）
- 影子结算架构相关代码（SettlementStrategy 注解、合并引擎、writeGuard 兼容代码）已清理

**验证结果（2026-07-20）：**
- ✅ `GameStateStore.kt` 接口和 `GameStateStoreImpl.kt` 中无 `createSettlementShadow` / `swapFromShadow`
- ✅ `discipleDesertionPopup` 已从 `GameData.kt` 中移除
- ✅ 仅测试 Fake 实现保留过时代码（正常，无运行时影响）
- ✅ compileReleaseKotlin 通过

---

### 17. `assignToResidence` 覆盖已占用槽位时未释放原住户

**状态：✅ 已完成（2026-07-23）**

**修复内容：**
- 新增 `GameEngineAtomicAssign.kt` 中的 `assignToResidenceAtomic` 方法
- 写入新槽位前检查目标槽位原住户 ID，非空时通过 `DiscipleSlotCleanup.clearAllSlots` 释放
- `removeFromResidenceAtomic` 只清空指定槽位 + 释放 gate，不清除其他系统槽位

**验证结果：**
- ✅ 覆盖分配时原住户 gate 正确释放
- ✅ compileReleaseKotlin 通过
- ✅ ResidenceDialog + PatrolTowerDialog 调用适配

**问题描述：** `BuildingDelegate.assignToResidence` 覆盖目标 residence slot 时，只清理了"新弟子"的旧槽位，没有检查目标槽位是否已被其他弟子占用。原住户的 `DiscipleAssignmentGate` 注册残留，变成"幽灵注册"——无住所但 gate 认为有锁。

**影响范围：** `BuildingDelegate.assignToResidence`

**复现：** 住所 slotIndex=0 已有弟子 B → 选择弟子 A 分配到同一槽位 → B 的 gate 注册未释放

**触发场景：** 住所对话框"更换"按钮（先释放旧选择 → 调 `assignToResidence` 覆盖）

**修复方向：**
- 写入 `residenceSlots` 前检查目标槽位的旧住户口 ID
- 若旧住户存在，先对其调用 `releaseDiscipleFromAllSlotsAtomic` + `releaseDiscipleAssignment`
- 或改为不覆盖，先清除旧住户再写入新住户（原子事务内）

**难度：** 低

---

### 18. `assignToResidence` 非原子状态更新（4 次独立事务）

**状态：✅ 已完成（2026-07-23）**

**修复内容：**
- 6 个原子方法（`assignToResidenceAtomic`、`removeFromResidenceAtomic`、`assignPatrolAtomic`、`removePatrolAtomic`、`swapPatrolAtomic`、`autoAssignPatrolAtomic`）均在单次 `stateStore.update` 内完成
- 释放旧槽位 + 检查原住户 + 写入新槽位 + 登记 gate + 更新状态一体化
- BuildingDelegate 和 PatrolTowerViewModel 全部委托到新原子 API

**问题描述：** `assignToResidence` 将一个逻辑操作拆为 4 次独立的 `stateStore.update`：① `releaseDiscipleFromAllSlotsAtomic` ② `updateGameData(residenceSlots)` ③ `confirmAssignDisciple` ④ `updateDiscipleStatus`。违反架构规范 6.2 🔴。

**影响范围：** `BuildingDelegate.assignToResidence`

**风险场景：**
- T1→T2 窗口：弟子已从所有槽位释放（IDLE），但住所槽位未写入，结算系统可能将其分配给其他系统
- T2→T3 窗口：`residenceSlots` 已写入但 gate 未注册，其他对话框可能将其分配到别处（跨系统双占位）

**同模式问题：** `PatrolTowerViewModel.assignDisciple` / `swapDisciple` 也有相同结构。

**修复方向：**
- 将全部变更整合为单次 `stateStore.update`
- 或在事务外预计算全部变更后一次写入

**难度：** 中

---

### 19. `PatrolTowerViewModel.assignDisciple` 未重新抛出 `CancellationException`

**状态：✅ 已完成（2026-07-23）**

**修复内容：**
- `assignDisciple`、`swapDisciple`、`removeDisciple`、`autoAssign` 全部添加 `catch (e: CancellationException) { throw e }`
- 新增 `assignDiscipleAsync`/`removeDiscipleAsync` fire-and-forget 包装器（同样有 CancellationException 重抛）
- 原子方法内部通过 `DomainResult.catching` 自动处理 CancellationException

---

### 20. 住所/巡视楼分配 fire-and-forget 无返回值

**状态：✅ 已完成（2026-07-23）**

**修复内容：**
- `BuildingDelegate.assignToResidence` 改为 `suspend` 返回 `DomainResult<Unit>`
- `BuildingDelegate.removeFromResidence` 改为 `suspend` 返回 `DomainResult<Unit>`
- `PatrolTowerViewModel.assignDisciple` 改为 `suspend` 返回 `DomainResult<Unit>`
- `PatrolTowerViewModel.removeDisciple` 改为 `suspend` 返回 `DomainResult<Unit>`
- 保留 `assignDiscipleAsync`/`removeDiscipleAsync` fire-and-forget 包装器供现有对话框使用
