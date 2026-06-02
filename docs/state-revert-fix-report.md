# 状态回退问题修复报告

## 问题描述

用户反馈：切换弟子内门/外门身份后，过一会操作会自动回退；种植灵草同样存在回退现象。此问题与之前的"弟子脱离提示框重复弹出"属于同一类架构缺陷。

## 根因分析

### 根因 1：`swapFromShadow()` 无互斥保护

**文件**：`GameStateStore.kt`

`swapFromShadow()` 直接读写 `_gameDataFlow.value` / `_disciplesFlow.value`，没有经过 `transactionMutex`。玩家操作（种植灵草等）通过 `stateStore.update { ... }` 提交修改，两者可以交错执行：

```
时间线：
T1  swapFromShadow() 读取当前状态（快照 A）
T2  玩家种植操作通过 stateStore.update { ... } 提交修改（快照 B）
T3  swapFromShadow() 基于快照 A 的合并结果写回 → 覆盖了快照 B 的修改
```

### 根因 2：`syncAllDiscipleStatuses()` 从 `unifiedState` 读取陈旧数据

**文件**：`DiscipleService.kt`

`currentDisciples` 使用 `disciplesFromUnified()` 读取 `unifiedState`，而 `unifiedState` 是通过 `combine` + `stateIn(Dispatchers.Default)` 派生的，存在更新延迟。

当 `changeDiscipleType()` 先调用 `updateDisciple()`（直接更新 `_disciplesFlow`），再调用 `syncAllDiscipleStatuses()` 时：

```
时间线：
T1  updateDisciple() 更新 _disciplesFlow.value（discipleType = "inner"）
T2  syncAllDiscipleStatuses() 从 unifiedState.value 读取（仍是旧值 discipleType = "outer"）
T3  syncAllDiscipleStatuses() 通过 StateAccessor 异步写回 → 覆盖了 discipleType 修改
```

### 根因 3：弟子合并遗漏玩家操作字段

**文件**：`GameStateStore.kt` `swapFromShadow()` 内的弟子合并逻辑

三路合并（origin → shadow → 主状态）的 `copy()` 调用中，未显式保留 `discipleType`、`status`、`statusData` 字段。当影子结算期间这些字段未变化时，合并逻辑不会从主状态取值，而是从影子状态取值，导致玩家操作被覆盖。

## 修复方案

### 核心原则

游戏引擎的状态修改（结算合并）与玩家操作的状态修改必须通过同一把互斥锁序列化。这是单机模拟类游戏处理"后台结算 vs 前台操作"的标准模式。

### 修复 1：`swapFromShadow()` 加 mutex 保护

**改动文件**：`GameStateStore.kt`

- `fun` → `suspend fun`
- 整个读-合并-写周期包裹在 `stateStore.update { ... }` 中
- 读取从 `this.gameData` / `this.disciples` 获取事务状态
- 写入通过 `this.xxx =` 赋值
- `shadowOrigin` 在 `update` 块外读取和清除（`@Volatile` 字段安全）

### 修复 2：弟子合并补充玩家操作字段保留

**改动文件**：`GameStateStore.kt` `swapFromShadow()` 内

在 `mainDisciple.copy(...)` 中显式保留：

```kotlin
// 玩家操作字段：始终保留玩家最新值，不被影子覆盖
discipleType = mainDisciple.discipleType,
status = mainDisciple.status,
statusData = mainDisciple.statusData
```

### 修复 3：`changeDiscipleType()` 原子化

**改动文件**：`GameEngine.kt`、`DiscipleDelegate.kt`

新增 `changeDiscipleTypeAtomic()` 方法，在同一 `stateStore.update { ... }` 事务中完成类型变更 + 状态同步：

```kotlin
suspend fun changeDiscipleTypeAtomic(discipleId: String, newType: String) {
    stateStore.update {
        // 1. 变更弟子类型
        val list = disciples.toMutableList()
        val index = list.indexOfFirst { it.id == discipleId }
        if (index >= 0) {
            list[index] = list[index].copy(discipleType = newType)
            disciples = list
        }
        // 2. 在同一事务中同步所有弟子状态
        discipleFacade.syncAllDiscipleStatuses()
    }
}
```

### 修复 4：`DiscipleService` 消除陈旧读取

**改动文件**：`DiscipleService.kt`

将 `FromUnified` 访问器替换为直接访问器：

| 修改前 | 修改后 |
|--------|--------|
| `state.gameDataFromUnified()` | `state.gameData()` |
| `state.disciplesFromUnified()` | `state.disciples()` |
| `state.teamsFromUnified()` | `state.teams()` |

`FromUnified` 的 fallback 从 `unifiedState` 读取（有延迟），直接访问器的 fallback 从 `StateFlow.value` 读取（实时）。

### 修复 5：`SettlementCoordinator` 适配 suspend

**改动文件**：`SettlementCoordinator.kt`

`onSettlementComplete()` 改为 `suspend fun`，适配 `swapFromShadow()` 的签名变更。

## 循环复查发现的同类问题

### A 类：`updateXxxDirect` + `syncAllDiscipleStatuses` 非原子操作（12 处）

| # | 文件 | 方法 |
|---|------|------|
| A1 | DiscipleFacadeImpl | `updateElderSlots` |
| A2 | DiscipleFacadeImpl | `assignDirectDisciple` |
| A3 | DiscipleFacadeImpl | `removeDirectDisciple` |
| A4 | DiscipleFacadeImpl | `assignDiscipleToLibrarySlot` |
| A5 | DiscipleFacadeImpl | `removeDiscipleFromLibrarySlot` |
| A6-A8 | ProductionViewModel | `addReserveDisciple` / `addReserveDisciples` / `removeReserveDisciple` |
| A9-A10 | SectViewModel | `addReserveDisciple` / `removeReserveDisciple` |
| A11 | ForgeViewModel | `removeForgeReserveDisciple` |
| A12 | AlchemyViewModel | `removeAlchemyReserveDisciple` |

**修复方式**：
- DiscipleFacadeImpl：合并进 `stateStore.update { }` 事务
- ViewModel 层：新增 `GameEngine.updateGameDataAndSync()` 原子方法，替换 `updateGameData` + `syncAllDiscipleStatuses` 两步调用

### B 类：`updateXxxDirect` 绕过 mutex 直接写入（4 处）

| # | 文件 | 方法 |
|---|------|------|
| B1 | GameEngine | `enterSect` |
| B2 | DiscipleFacadeImpl | `imprisonTheftDisciple` |
| B3 | DiscipleFacadeImpl | `releaseTheftDisciple` |
| B4 | BuildingFacadeImpl | `moveBuildingDirect` |

**修复方式**：`updateXxxDirect` → `stateStore.update { }`，`fun` → `suspend fun`，接口声明和调用方同步适配。

### 确认安全无需修改的

| 位置 | 原因 |
|------|------|
| CultivationService 的 `updateXxxDirect` | 结算写回路径，已实现事务内外双模式，在 `stateStore.update { }` 内调用时走事务分支 |
| GameEngine AI战斗/探索后的 `updateDisciplesDirect` | 在结算 mutex 内执行，受保护 |

## 修改文件清单

| 文件 | 改动类型 |
|------|---------|
| `GameStateStore.kt` | `swapFromShadow` 加 mutex + 弟子字段保留 |
| `GameEngine.kt` | 新增 `changeDiscipleTypeAtomic`、`updateGameDataAndSync`；`enterSect` 改 suspend |
| `DiscipleDelegate.kt` | 调用原子化方法 |
| `DiscipleService.kt` | `FromUnified` → 直接访问器 |
| `DiscipleFacadeImpl.kt` | 5 处非原子操作原子化 + 2 处 `updateDisciplesDirect` 改 `stateStore.update` |
| `DiscipleFacade.kt` | 接口声明加 `suspend` |
| `BuildingFacadeImpl.kt` | `moveBuildingDirect` 改 `stateStore.update` |
| `BuildingFacade.kt` | 接口声明加 `suspend` |
| `SettlementCoordinator.kt` | `onSettlementComplete` 改 `suspend` |
| `ProductionViewModel.kt` | 3 处改用 `updateGameDataAndSync` |
| `SectViewModel.kt` | 2 处改用 `updateGameDataAndSync` |
| `ForgeViewModel.kt` | 1 处改用 `updateGameDataAndSync` |
| `AlchemyViewModel.kt` | 1 处改用 `updateGameDataAndSync` |
| `GameViewModel.kt` | 调用方适配 suspend |

## 架构改进总结

```
修复前：
  玩家操作 ──→ stateStore.update { mutex 保护 }
  结算合并 ──→ swapFromShadow() { 无 mutex，直接写 StateFlow.value }
                                    ↑ 竞态条件

修复后：
  玩家操作 ──→ stateStore.update { mutex 保护 }
  结算合并 ──→ stateStore.update { mutex 保护 }
                                    ↑ 互斥序列化
```

所有游戏状态修改路径现在统一经过 `stateStore.update { }` 事务，消除了竞态条件和陈旧读取问题。
