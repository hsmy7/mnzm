# 弟子分配门卫架构（DiscipleAssignmentGate）

> 本文档记录 v4.0.58 引入的弟子分配门卫系统架构设计。
> 对应 PR 审查清单规则 13.3 中新增 SlotCategory 的 4 处必改项。

---

## 问题背景

v4.0.58 之前，弟子分配（槽位系统）存在以下问题：

1. **分散管理** — 每个槽位系统（长老/生产/灵矿/藏经阁/住所等）各自维护自己的分配逻辑，没有统一的"弟子当前在哪个槽位"查询入口
2. **重复分配** — 弟子可以被分配到多个槽位而互不知晓，导致同一弟子出现在两个系统中
3. **清理遗漏** — 死亡/释放弟子时，各个槽位需要各自触发清理，遗漏导致僵尸数据
4. **读档不一致** — 读档后没有重建分配索引，导致无法查询弟子当前分配

## 整体架构

```
┌─────────────────────────────────────────────────────┐
│                 DiscipleAssignmentGate               │
│  ┌─────────────────────────────────────────────────┐ │
│  │          DiscipleAssignmentRegistry              │ │
│  │     (Identity Map: discipleId → SlotAssignment)  │ │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐      │ │
│  │  │ disciple │→│ SlotRef  │  │ disciple │→│...│      │ │
│  │  └──────────┘  └──────────┘  └──────────┘      │ │
│  └─────────────────────────────────────────────────┘ │
│                                                       │
│  ┌─ 11 个槽位系统（SlotCategory）─────────────────────┐ │
│  │ ELDER_POSITION / PRODUCTION_SLOT / SPIRIT_MINE    │ │
│  │ LIBRARY_SLOT / RESIDENCE_SLOT / PATROL_SLOT       │ │
│  │ WAREHOUSE_GARRISON / BATTLE_TEAM / GARRISON_SLOT  │ │
│  │ BLOOD_REFINEMENT / EXPLORATION_TEAM*              │ │
│  └───────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────┘
```

### 核心组件

| 组件 | 文件 | 职责 |
|------|------|------|
| `SlotCategory` | `model/SlotAssignment.kt` | 槽位类别枚举（11 种） |
| `SlotRef` | `model/SlotAssignment.kt` | 槽位引用（category + slotType + displayKey） |
| `SlotAssignment` | `model/SlotAssignment.kt` | 分配记录（discipleId → SlotRef） |
| `DiscipleAssignmentGate` | `domain/disciple/DiscipleAssignmentGate.kt` | 门卫 Facade：分配登记/释放/查询/过滤/读档重建 |
| `DiscipleAssignmentRegistry` | `domain/disciple/DiscipleAssignmentRegistry.kt` | 身份映射表：discipleId → SlotAssignment |
| `DiscipleSlotCleanup` | `domain/disciple/DiscipleSlotCleanup.kt` | 死亡/释放时清理所有槽位的标准化入口 |

## 数据模型

### SlotCategory 枚举

```kotlin
enum class SlotCategory {
    ELDER_POSITION,      // 长老/亲传位置
    PRODUCTION_SLOT,     // 生产（炼丹/锻造/灵植）
    SPIRIT_MINE,         // 灵矿场
    LIBRARY_SLOT,        // 藏经阁
    RESIDENCE_SLOT,      // 住所
    PATROL_SLOT,         // 巡视塔
    WAREHOUSE_GARRISON,  // 仓库驻守
    BATTLE_TEAM,         // 战斗队伍
    GARRISON_SLOT,       // 驻军（世界地图）
    BLOOD_REFINEMENT,    // 血炼池
    EXPLORATION_TEAM;    // 探索队伍（不持久化，UI 已主动过滤）
}
```

### SlotRef

```kotlin
data class SlotRef(
    val category: SlotCategory,   // 槽位类别
    val slotType: String,         // 槽位类型标识（如 "elder_viceSectMaster"）
    val displayKey: String,       // 显示用唯一键（如 "elder_viceSectMaster"）
)
```

### SlotAssignment

```kotlin
data class SlotAssignment(
    val discipleId: String,
    val slotRef: SlotRef,
)
```

## 核心流程

### 分配流程

```
releaseDiscipleFromAllSlotsAtomic(discipleId)  // Step 1: 释放旧槽位 + gate.release()
    ↓
stateStore.update { gameData = gameData.copy(elderSlots = ...) }  // Step 2: 写入新槽位
    ↓
gate.confirmAssign(discipleId, newSlotRef)    // Step 3: 登记新分配
```

**关键设计决策：**
- Gate **不阻止分配**，调用方自行调用 `releaseDiscipleFromAllSlotsAtomic` 释放旧槽位
- `confirmAssign` 覆盖现有记录（因为旧槽位已在 Step 1 清理）
- 分配入口统一使用 `confirmAssign`，5 个副作用口使用 `manualRegister`

### 读档重建流程

```
BootSequenceController.boot()
    ↓
GameEngine.initGameData() / loadGame()
    ↓
gate.rebuildFromGameData(gameData, productionSlots)
    ↓
registry.clear() + scanAndRegister(gameData, productionSlots)
    ↓
  ├─ scanElderSlots(elderSlots)       — 10 种长老 + 7 种亲传
  ├─ scanListSlots(gameData)           — 8 种列表槽位
  └─ scanProductionSlots(productionSlots) — N 个生产槽位
```

### 死亡/释放清理流程

```
DiscipleLifecycleProcessor / DiscipleSlotCleanup
    ↓
clearAllSlots(id) / deathPath(id)
    ↓
ElderSlots, SpiritMineSlots, LibrarySlots, ResidenceSlots,
PatrolSlots, WarehouseGarrisons, BattleTeams, GarrisonSlots,
BloodRefinements, ProductionSlots
    ↓ (each)
gate.release(id)   // 统一清理注册表
```

## 新增槽位系统的必改清单

当新增 `SlotCategory` 枚举值时，需要同步更新以下 4 处：

1. **`DiscipleAssignmentGate.scanAndRegister`** — 在 `scanElderSlots()` / `scanListSlots()` / `scanProductionSlots()` 中添加扫描逻辑，确保读档时重建注册表
2. **`DiscipleSlotCleanup.clearAllSlots`** — 死亡/释放时清理新系统的槽位数据
3. **分配入口** — 调用 `releaseDiscipleFromAllSlotsAtomic` + `confirmAssign`
4. **`SlotCategoryCoverageTest`** — 将新值加入对应的检查集合

### 守卫测试

`SlotCategoryCoverageTest` 自动守卫上述 1、2 两项。测试失败时提示：

```
新增 SlotCategory 未在 scanAndRegister 中覆盖！
以下类别需要添加到 DiscipleAssignmentGate.scanAndRegister 的扫描逻辑中
```

## 与现有架构的关系

```
┌───────────────────────────────────────────────────────────┐
│  UI Layer (ViewModel)                                     │
│  ├─ BuildingDelegate / ProductionViewModel / ...          │
│  └─ releaseDiscipleFromAllSlotsAtomic() → Gate           │
├───────────────────────────────────────────────────────────┤
│  Engine Layer (UseCase / Service / System)                │
│  ├─ ElderManagementUseCase / DiscipleFacadeImpl / ...     │
│  ├─ DiscipleAssignmentGate (门卫)                         │
│  ├─ DiscipleSlotCleanup (死亡清理)                        │
│  └─ DiscipleLifecycleProcessor (生命周期)                  │
├───────────────────────────────────────────────────────────┤
│  Data Layer                                               │
│  ├─ GameData (ElderSlots / spiritMineSlots / ...)         │
│  └─ StateStore.update { } (事务内写入)                    │
└───────────────────────────────────────────────────────────┘
```

## 与 ComponentTable 写入守卫的关系

`ComponentTable` 字段级写入守卫（v4.0.58）与 `DiscipleAssignmentGate` 互补：

| 守卫类型 | 保护目标 | 实现方式 |
|---------|---------|---------|
| ComponentTable 写入守卫 | 所有 `DiscipleTables` 列在 `stateStore.update` 事务外写入时抛异常 | `requireWrite` 回调 |
| DiscipleAssignmentGate | 弟子重复分配 / 分配泄漏 | `registry.unregister()` + `confirmAssign()` |
| SlotCategoryCoverageTest | 新增槽位系统时忘记注册 | 枚举守卫测试 |

两者结合后，3 种防止幽灵弟子的机制（ComponentTable 事务守卫 + Gate 分配追踪 + 读档重建）形成完整防线。

## 架构债务

详见 [architecture-debt-write-guard.md](architecture-debt-write-guard.md) 中记录的 6 项待完成项：

1. `store` 底层存储绕过守卫
2. `requireWrite` / `onWrite` 为 `@JvmField var` 可被覆盖
3. `writeGuardEnabled` 全局开关可关闭所有守卫
4. `ids` 为 public `MutableList` 可被直接变异
5. `deathRecords` 为 public `MutableList` 可被直接变异
6. `mergeDiscipleTables` / `createSettlementShadow` 死代码
