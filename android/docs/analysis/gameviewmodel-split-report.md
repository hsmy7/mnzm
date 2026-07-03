# GameViewModel.kt 拆分分析报告

## 概述

- **文件路径**: `feature/game/src/main/java/com/xianxia/sect/ui/game/GameViewModel.kt`
- **当前行数**: 2069 行（含末尾数据类、枚举）
- **构造参数**: 17 个（远超 7 个上限）
- **现有委托**: 4 个（PlantingDelegate, DiscipleDelegate, NavigationDelegate, InventoryDelegate）
- **目标**: 提取 5 个新 Delegate，将行数降至 800-900 行

## 当前架构

```
GameViewModel
  ├── PlantingDelegate (已提取) - 灵田种植
  ├── DiscipleDelegate (已提取) - 弟子操作
  ├── NavigationDelegate (已提取) - 对话框导航
  ├── InventoryDelegate (已提取) - 仓库/坊市
  ├── BuildingDelegate (待提取) - 建筑
  ├── BeastAttackDelegate (待提取) - 兽袭
  ├── WarningDelegate (待提取) - 宗门进攻预警
  ├── AutoAssignDelegate (待提取) - 自动分配/道侣策略
  └── SectDelegate (待提取) - 宗门等级/改名
```

---

## Delegate 1: BuildingDelegate (~200 行)

### 方法清单

| # | 方法 | 行号 | 签名 | 描述 | 写 Store | 调 Engine | 调 Dialog |
|---|------|------|------|------|:--------:|:----------:|:----------:|
| 1 | `placeBuilding` | 321-420 | `(name, gridX, gridY, width, height)` | 放置建筑，扣灵石，创建槽位 | 是 | 是 | 否 |
| 2 | `getBuildingCost` | 428-430 | `(displayName): Long` | 查询建筑造价 | 否 | 否 | 否 |
| 3 | `getBuildingGridSize` | 438-440 | `(displayName): Pair<Int, Int>` | 查询建筑网格尺寸 | 否 | 否 | 否 |
| 4 | `batchPlaceBuilding` | 447-471 | `(goldFingerState): Unit` | 金手指批量建造 | 是 | 是 | 否 |
| 5 | `moveBuilding` | 480-482 | `(instanceId, newGridX, newGridY)` | 移动建筑 | 是 | 是 | 否 |
| 6 | `demolishBuilding` | 489-500 | `(instanceId)` | 拆除建筑，返还一半造价 | 是 | 是 | 否 |
| 7 | `fixupBuildingSizesIfNeeded` | 503-511 | `()` | 修正旧存档建筑尺寸 | 是 | 是 | 否 |
| 8 | `canUpgradeResidence` | 2011-2015 | `(buildingInstanceId): Boolean` | 判断单人住所可升级 | 否 | 是 | 否 |
| 9 | `upgradeSingleResidence` | 2022-2037 | `(buildingInstanceId)` | 单人住所→中级单人住所 | 是 | 是 | 否 |
| 10 | `assignToResidence` | 1956-1983 | `(instanceId, slotIndex, discipleId)` | 分配弟子到住所槽位 | 是 | 是 | 否 |
| 11 | `removeFromResidence` | 1991-2003 | `(instanceId, slotIndex)` | 从住所移出弟子 | 是 | 是 | 否 |
| 12 | `plantSeed` | 1347-1356 | `(slotIndex, seed)` | 在槽位种植 | 否 | 是 (buildingFacade) | 否 |
| 13 | `openBuildingDetailDialog` | 899-901 | `(buildingId)` | 打开建筑详情（写 _selectedBuildingId） | 否 | 否 | 是 |

### 连带 StateFlow 属性

| 属性名 | 行号 | 类型 | 来源 |
|--------|------|------|------|
| `placedBuildings` | 521-524 | `StateFlow<List<GridBuildingData>>` | gameData 派生 |
| `residenceSlots` | 541-544 | `StateFlow<List<ResidenceSlot>>` | gameData 派生 |
| `_selectedBuildingId` | 837-838 | `MutableStateFlow<String?>` | 内部状态 |
| `_selectedPlantSlotIndex` | 840-841 | `MutableStateFlow<Int?>` | 内部状态 |

### 注入依赖

- `GameEngine` — `updateGameData()`, `removeBuilding()`, `currentActiveSectId()`, `gameDataSnapshot`, `gameData`
- `BuildingConfigService` — `getBuildingConfigByDisplayName()`, `getBuildingGridSize()`, `fixupBuildingSizes()`, `getSlotCountByDisplayName()`
- `BuildingFacade` — `moveBuildingDirect()`, `startManualPlanting()`, `addProductionSlot()`
- `viewModelScope` — 协程作用域
- Callbacks: `showSuccess(String)`, `showError(String)` — BaseViewModel 事件通道

### 引用静态类型（需同步 import）

- `com.xianxia.sect.ui.game.building.BuildingDef`
- `com.xianxia.sect.ui.game.building.BuildingRegistry`
- `com.xianxia.sect.core.model.production.BuildingType`
- `com.xianxia.sect.core.model.production.ProductionSlot`
- `com.xianxia.sect.core.model.GridBuildingData`
- `com.xianxia.sect.core.model.SpiritMineSlot`
- `com.xianxia.sect.core.model.PatrolSlot`
- `com.xianxia.sect.core.model.PatrolConfig`
- `com.xianxia.sect.core.model.ResidenceSlot`
- `com.xianxia.sect.core.model.SpiritFieldPlant`
- `com.xianxia.sect.ui.game.sect.GoldFingerState`

### 注意事项

- `placeBuilding` 包含 production slot 创建和多种建筑类型的槽位初始化逻辑，是最大的方法（约100行）
- `demolishBuilding` 使用 `showSuccess()` 通知用户
- `batchPlaceBuilding` 循环调用 `placeBuilding`，提取后变成同 delegate 内部调用

---

## Delegate 2: BeastAttackDelegate (~20 行)

### 方法清单

| # | 方法 | 行号 | 签名 | 描述 | 写 Store | 调 Engine |
|---|------|------|------|------|:--------:|:----------:|
| 1 | `resolveBeastAttackPayTribute` | 231-233 | `(beastLevelId: String)` | 兽袭进贡 | 是 | 是 |
| 2 | `resolveBeastAttackFight` | 240-244 | `(beastLevelId: String)` | 兽袭战斗（launch 协程） | 是 | 是 |
| 3 | `clearPendingBeastAttacks` | 247-249 | `()` | 清空待处理兽袭 | 是 | 是 |

### 注入依赖

- `GameEngine` — 三个方法都直接委托
- `viewModelScope` — 仅 `resolveBeastAttackFight`

### 注意事项

- 非常轻量，仅适配层转发
- 可考虑未来合并入其他 delegate 或保持独立

---

## Delegate 3: WarningDelegate (~30 行)

### 方法清单

| # | 方法 | 行号 | 签名 | 描述 | 写 Store | 调 Engine |
|---|------|------|------|------|:--------:|:----------:|
| 1 | `resolveAttackWarningAppease` | 275-279 | `(sectId: String)` | 预警安抚 | 是 | 是 |
| 2 | `resolveAttackWarningVassal` | 286-290 | `(sectId: String)` | 预警附庸 | 是 | 是 |
| 3 | `markWarningStageShown` | 297-301 | `(stageKey: String)` | 标记预警已展示 | 是 | 是 |

### 连带 StateFlow 属性

| 属性名 | 行号 | 类型 | 来源 |
|--------|------|------|------|
| `attackWarnings` | 252-259 | `StateFlow<List<AttackWarning>>` | gameData 派生 `activeAttackWarnings` |
| `shownWarningStageIds` | 261-268 | `StateFlow<List<String>>` | gameData 派生 `shownWarningStageIds` |

### 注入依赖

- `GameEngine` — `appeaseAttackingSect()`, `becomeVassalOfAttacker()`, `markWarningStageShown()`, `gameData`
- `viewModelScope`

---

## Delegate 4: AutoAssignDelegate (~100 行)

### 方法清单

| # | 方法 | 行号 | 签名 | 描述 | 写 Store | 调 Engine |
|---|------|------|------|------|:--------:|:----------:|
| 1 | `setAutoAssignSettings` | 1091-1113 | 8参数(各建筑聚焦/灵根/境界) | 批量设置自动分配策略 | 是 | 是 |
| 2 | `setBreakthroughAutoPillSettings` | 1121-1125 | `(focused, rootCounts)` | 突破自动丹药设置 | 是 | 是 |
| 3 | `setAutoEquipSettings` | 1133-1137 | `(focused, rootCounts)` | 自动装备设置 | 是 | 是 |
| 4 | `setAutoLearnSettings` | 1145-1149 | `(focused, rootCounts)` | 自动学习功法设置 | 是 | 是 |
| 5 | `setDaoCompanionBannedRootCounts` | 1058-1062 | `(counts: Set<Int>)` | 禁止道侣灵根数 | 是 | 是 |
| 6 | `setDaoCompanionConsentRequired` | 1069-1073 | `(required: Boolean)` | 道侣需同意 | 是 | 是 |

### 注入依赖

- `GameEngine` — `updateGameData()`
- `viewModelScope`

### 注意事项

- 所有方法都走 `gameEngine.updateGameData { it.copy(...) }` 统一模式
- `setAutoAssignSettings` 有 8 个参数（可考虑拆成 4 组独立 setter，但保持原样更简单）

---

## Delegate 5: SectDelegate (~50 行)

### 方法清单

| # | 方法 | 行号 | 签名 | 描述 | 写 Store | 调 Engine | 调 Dialog |
|---|------|------|------|------|:--------:|:----------:|:----------:|
| 1 | `navigateToSectLevelDetail` | 599-601 | `()` | 打开宗门等级详情 | 否 | 否 | 是（navigateToDialog） |
| 2 | `renameSect` | 604-617 | `(newName: String)` | 修改宗门名称 | 是 | 是 | 是（dismissDialog） |
| 3 | `claimSectLevelReward` | 620-634 | `(level: Int)` | 领取等级周奖励 | 是 | 是 | 否 |
| 4 | `upgradeSectLevel` | 637-651 | `()` | 手动升级宗门等级 | 是 | 是 | 否 |

### 连带 StateFlow 属性

| 属性名 | 行号 | 类型 | 来源 |
|--------|------|------|------|
| `playerSectLevel` | 582-587 | `StateFlow<Int>` | gameData 派生 |
| `sectLevelRewardClaimable` | 590-596 | `StateFlow<Boolean>` | `combine(gameData, playerSectLevel)` |

### 注入依赖

- `GameEngine` — `updateGameData()`, `claimSectLevelReward()`, `upgradeSectLevel()`, `gameData`
- `viewModelScope`
- Callbacks: `navigateToDialog(DialogRoute)`, `dismissDialog()`, `showSuccess(String)`, `showError(String)`

### 注意事项

- `navigateToSectLevelDetail` 和 `renameSect` 需要 dialog 导航回调
- `SectLevel`, `SectLevelClaimResult`, `SectLevelUpgradeResult` 类型需 import

---

## 保留在 GameViewModel 的内容

以下内容与 Compose 层耦合较紧或已有足够封装，建议保留：

### 核心 StateFlow 暴露（~30 个属性，行 513-737）

| 属性 | 行号 | 说明 |
|------|------|------|
| `gameData`, `gameDataUi` | 513-519 | 主数据流 |
| `elderSlots`, `sectPolicies` | 526-534 | StateFlow 派生 |
| `disciples`, `aliveDisciples` | 570-575 | 弟子列表 |
| `equipmentStacks`, `manualStacks`, `pills`, `materials`, `herbs`, `seeds` | 663-687 | 仓库数据 |
| `alchemySlots`, `forgeSlots`, `plantSlots` | 707-875 | 生产槽位 |
| `highFreqState`, `entityState`, `configState` | 546-548 | 引擎状态 |
| `pendingNotification`, `rewardCardQueue` | 550-551 | 通知/奖励 |
| `battleLogs`, `pendingBattleResult`, `pendingBeastAttacks` | 693-697 | 战斗状态 |

### 弟子详情覆盖层（行 804-835）

- `showDiscipleDetail(DiscipleDetailRequest)` — 写 `_detailDisciple` + `pushOverlay`
- `dismissDiscipleDetail()` — 清 `_detailDisciple` + `popOverlay`
- `navigateDiscipleDetail(DiscipleAggregate)` — 切换详情弟子
- `_detailDisciple: MutableStateFlow<DiscipleDetailRequest?>`

**原因**: 同时操作 `_detailDisciple` 和 `_overlayOrder`，与 Compose 渲染层紧耦合。

### Overlay 栈管理（行 781-802）

- `pushOverlay(TopOverlay)`, `popOverlay(TopOverlay)`
- `_overlayOrder: SnapshotStateList<TopOverlay>`

**原因**: 使用 Compose `mutableStateListOf`，和 `MainGameScreen` 的 z-order 渲染强耦合。

### 签到系统 DailySignIn（行 1832-1943）

- `signInState`, `canClaimToday`, `claimableDays`, `claimedMilestones`
- `claimDailySignIn()`, `getRewardForWeekday()`, `getDayState()`, `getDaysInMonth()`
- `_signInCapacityWarning`, `enqueueRewardCards()`

**原因**: 已委托给 `dailySignInService`，足够精简（~110 行）。

### 邮件系统（行 1760-1829）

- `mails`, `mailUnreadCount`, `mailRewardCards`
- `markMailAsRead()`, `claimMailAttachment()`, `markAllMailsAsRead()`, `enqueueMailRewardCards()`
- `deleteAllReadAndClaimedMails()`

**原因**: 已委托给 `mailService`，含 mutex 保护的卡片队列。

### 储物袋（行 1286-1332）

- `openStorageBag()`, `openAllStorageBags()`, `enqueueBagRewardCards()`
- `pendingBagCards`, `_bagRewardCards`

**原因**: 仅缓存 + 转发，行数少。

### 一键出售（行 1537-1622）

- `bulkSellItems(selectedRarities, selectedTypes)`

**原因**: 较大方法（85 行）但完全基于现有的 `equipmentStacks`/`manualStacks`/`pills`/`materials`/`herbs`/`seeds` StateFlow，提取成本高收益低。

### 兑换码（行 1706-1756）

- `openRedeemCodeDialog()`, `closeRedeemCodeDialog()`, `redeemCode()`, `clearRedeemResult()`
- `_showRedeemCodeDialog`, `_redeemResult`

**原因**: 简单 dialog 状态管理。

### 单一设置方法（行 1156-1186）

- `setPatrolBattleResultPopup(Boolean)`
- `setAutoSellMidGradeForPurchase(Boolean)`
- `setAutoSellHighGradeForPurchase(Boolean)`

**原因**: 每个只有 5-6 行，归属模糊。

### 其他小方法

| 方法 | 行号 | 说明 |
|------|------|------|
| `approveMarriage` / `rejectMarriage` | 1203-1213 | 道侣审批 |
| `setYearlySalary` / `setYearlySalaryEnabled` | 1392-1411 | 薪资系统 |
| `onMemoryPressure` / `clearResources` | 1672-1693 | 生命周期 |
| `startMission` | 1654-1664 | 任务（stub） |
| `hasDisciplePosition` 系列 | 745-777 | 职位查询 |
| `enterSect` | 959-961 | 世界地图 |
| `clearNotification` | 941-943 | 通知清空 |
| `onCleared` | 2041-2048 | 清理 |

---

## 提取后预期

| 组件 | 方法数 | StateFlow 属性 | 预估行数 |
|------|:------:|:--------------:|:--------:|
| **BuildingDelegate** | 13 | 4 | ~200 |
| **BeastAttackDelegate** | 3 | 0 | ~20 |
| **WarningDelegate** | 3 | 2 | ~30 |
| **AutoAssignDelegate** | 6 | 0 | ~100 |
| **SectDelegate** | 4 | 2 | ~50 |
| 保留在 GameViewModel | ~40 | ~30 | ~800-900 |
| **总计** | ~69 | ~38 | ~1200-1300 |

移除约 **500-600 行**，原 VM 从 **2069 行降至 800-900 行**。

## 提取注意事项（residual / edge cases）

1. **BaseViewModel 回调传递**: 每个 delegate 需要 `showSuccess()/showError()`。方案：定义接口 `ViewMessageBus { fun showSuccess(msg); fun showError(msg) }`，GameViewModel 实现并传入 delegate。

2. **SectDelegate 导航回调**: `navigateToSectLevelDetail` 和 `renameSect` 需要 `navigateToDialog()` / `dismissDialog()`。方案：构造函数传入 `(DialogRoute) -> Unit` 和 `() -> Unit` lambda。

3. **BuildingDelegate.placeBuilding 静态引用**: 直接使用了 `BuildingDef`, `BuildingRegistry` 的常量。提取后 import 即可解决。

4. **现有的 4 个 Delegate 保持不动**: PlantingDelegate, DiscipleDelegate, NavigationDelegate, InventoryDelegate 已经稳定，不在此次提取范围内。

5. **`enqueueBattleRewardCards`（行 304-310）**: 读取 `gameEngine.pendingBattleRewardCards.value`，归属模糊，建议保留在 VM。

6. **`getLifeEvents`/`initializeLifeEvents`（行 933-938）**: 委托给 `discipleFacade`，但未通过 `DiscipleDelegate`。可考虑移到 DiskipleDelegate 或留在 VM 中。
