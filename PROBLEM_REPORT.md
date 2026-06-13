# XianxiaSectNative 项目问题报告

> 扫描日期：2026-06-13
> 扫描范围：项目代码质量、技术架构、性能隐患
> 不涉及：游戏设计问题（已单独分析）

---

## 目录

1. [总览](#1-总览)
2. [根本矛盾：不可变数据模型 vs 高频可变状态](#2-根本矛盾)
3. [问题1：Disciple.copyWith 60+ 参数巨型方法](#3-问题1)
4. [问题2：List 全量重建与 O(n) 遍历泛滥](#4-问题2)
5. [问题3：状态架构过度分层，同一数据 4+ 种形态](#5-问题3)
6. [问题4：GameData 985 行 God Object](#6-问题4)
7. [问题5：CultivationCore 双重状态访问模式](#7-问题5)
8. [问题6：结算路径与 tick 路径的逻辑重复](#8-问题6)
9. [问题7：SettlementCoordinator 线程安全隐患](#9-问题7)
10. [问题8：数据库层问题](#10-问题8)
11. [问题9：测试覆盖与工程配置](#11-问题9)
12. [问题严重度矩阵](#12-问题严重度矩阵)

---

## 1. 总览

项目是一款修仙宗门模拟经营手游，技术栈为 Kotlin + Jetpack Compose + Room + Hilt，采用多模块架构（core/domain、core/engine、core/data、feature/game）。

项目在架构设计上做了大量工作（分域调度、Shadow 结算机制、热管理、性能监控），但存在一个根本性的技术矛盾：**选择了 Kotlin data class + StateFlow 的不可变响应式架构，但游戏引擎需要每 100ms 对大量实体做高频可变更新**。这两个设计目标直接冲突，导致了一系列连锁问题。

---

## 2. 根本矛盾

| 设计选择 | 要求 | 实际需求 | 冲突 |
|----------|------|----------|------|
| `data class` 不可变模型 | 每次修改创建新对象 | 100ms tick 内修改 N 个弟子 | 大量临时对象分配 |
| `StateFlow<List<T>>` | 整个列表作为原子单元更新 | 只修改列表中的一个元素 | 全量 List 重建 |
| `GameData.copy()` | 复制所有 60+ 字段 | 只修改一个字段 | 大对象频繁复制 |
| Shadow 结算机制 | 结算期间隔离状态 | tick 和结算并发 | 复杂的三路合并逻辑 |

这个矛盾导致了四个层面的连锁问题：

- **性能**：大量临时对象分配 + O(n) List 遍历，随实体数量增长急剧恶化
- **复杂度**：shadow/transaction/settlement 机制本质上是给不可变模型打补丁
- **一致性**：同一数据的多份拷贝增加同步风险
- **维护性**：60+ 参数的 copyWith、985 行的 GameData、重复的业务逻辑

---

## 3. 问题1：Disciple.copyWith 60+ 参数巨型方法

**严重度**：高
**文件**：`android/core/domain/src/main/java/com/xianxia/sect/core/model/Disciple.kt` 第197-419行

### 现状

Disciple 类包含 6 个 `@Embedded` 子组件（CombatAttributes、PillEffects、EquipmentSet、SocialData、SkillStats、UsageTracking），合计 60+ 个字段。由于 Kotlin data class 的 `copy()` 无法跨越 `@Embedded` 边界，项目手写了一个 `copyWith` 方法，包含 60+ 个命名参数。

```kotlin
// 修改一个 loyalty 字段，需要构造 60+ 参数的完整对象
d = d.copyWith(loyalty = (d.skills.loyalty + loyaltyDelta).coerceAtLeast(0))
```

### 影响

- 全项目 `copyWith` 调用 **87 次**（26 个文件），`copy` 调用更多
- 每次调用在堆上分配新的 Disciple 对象 + 6 个 Embedded 子对象
- 100ms tick 内对 N 个弟子执行 = N 次大对象分配
- GC 压力随弟子数量线性增长
- 新增字段时必须同步更新 copyWith，极易遗漏

### 数据

| 指标 | 数值 |
|------|------|
| copyWith 参数数量 | 60+ |
| copyWith 调用次数 | 87 次 / 26 文件 |
| Disciple 类总行数 | ~512 行（含 copyWith 约 220 行） |
| @Embedded 子组件数 | 6 个 |

---

## 4. 问题2：List 全量重建与 O(n) 遍历泛滥

**严重度**：高
**影响范围**：engine 模块核心热路径

### 现状

项目中对弟子/装备/物品的更新模式几乎全是：

```kotlin
// 模式1：修改单个弟子 —— O(n) 遍历 + 全量 List 重建
shadow.disciples = shadow.disciples.map { d ->
    if (d.id == targetId) updatedDisciple else d
}

// 模式2：查找单个弟子 —— O(n) 线性查找
val disciple = stateStore.disciples.value.find { it.id == discipleId }

// 模式3：构建 Map 索引 —— 每次重新构建
val allDisciples = currentDisciples.associateBy { it.id }
```

### 统计数据

| 模式 | 出现次数 | 涉及文件数 |
|------|----------|-----------|
| `.find { it.id == }` | 165 次 | 29 个文件 |
| `.map { if (it.id == ...) }` | 52 次 | 8 个文件 |
| `.associateBy { it.id }` | 73 次 | 31 个文件 |

### 关键热路径

1. **SettlementCoordinator**（`SettlementCoordinator.kt`）：
   - 第267-269行：焦点弟子更新，`shadow.disciples.map {}`
   - 第320-328行：批量弟子更新，先 `toMutableList()` 再逐个替换
   - 第397-420行：脏弟子批量更新，再次全量替换

2. **CultivationCore**（`CultivationCore.kt`）：
   - 第360-417行：`applyAccumulator` 对装备栈做多次 `.map` + `.filter`
   - 第433-494行：`updateMonthlyCultivation` 对所有弟子做 `.map` 修炼更新

3. **GameEngineCoordination**：8 处 `.map { if (it.id == ...) }` 模式

### 影响

- 弟子数 100 时，每次修改 1 个弟子需遍历 100 个元素
- 弟子数 500 时，性能退化 5 倍
- SettlementCache 已经做了 `discipleMap` 优化，但只覆盖结算路径，tick 路径仍然全量遍历

---

## 5. 问题3：状态架构过度分层，同一数据 4+ 种形态

**严重度**：中高
**文件**：`android/core/domain/src/main/java/com/xianxia/sect/core/state/GameStateStore.kt`

### 现状

同一个"弟子列表"在系统中同时存在以下形态：

| 形态 | 位置 | 用途 | 更新频率 |
|------|------|------|----------|
| `StateFlow<List<Disciple>>` | GameStateStore | UI 观察 | 每次 update |
| `MutableGameState.disciples` | MutableGameState | 引擎更新 | 每次 update |
| `SettlementCache.discipleMap` | SettlementCache | 结算优化 | 每次结算 |
| `HighFrequencyData.cultivationUpdates` | CultivationService | 实时修炼增量 | 每 100ms |
| `UnifiedGameState.disciples` | UnifiedGameState | UI 聚合 | 每次 combine |

GameStateStore 定义了三层 StateFlow 架构：

```kotlin
val highFreqState: StateFlow<HighFreqState>    // 高频：灵石/时间
val entityState: StateFlow<EntityState>          // 实体：弟子/装备/物品
val configState: StateFlow<ConfigState>          // 配置：政策/俸禄
val unifiedState: StateFlow<UnifiedGameState>    // 聚合：全部合并
```

加上原始的独立 StateFlow（disciples, pills, herbs...），**同一批数据被派生了至少 4 次**。

### 影响

- 每次 `stateStore.update {}` 触发多个 StateFlow 的 combine 重新计算
- `UnifiedGameState.getDiscipleById()` 用 `disciples.find { }` 做 O(n) 查找，每次访问都遍历
- 多层 StateFlow 的 distinctUntilChanged 判断增加 CPU 开销
- 新增字段时需同步更新多处 StateFlow 定义

---

## 6. 问题4：GameData 985 行 God Object

**严重度**：高
**文件**：`android/core/domain/src/main/java/com/xianxia/sect/core/model/GameData.kt`

### 现状

GameData 有 **60+ 个字段**，涵盖：

- 游戏时间（gameYear, gameMonth, gamePhase）
- 资源（spiritStones, spiritHerbs, sectCultivation）
- 建筑与槽位（placedBuildings, spiritMineSlots, librarySlots, residenceSlots, patrolSlots...）
- 世界地图（worldMapSects, sectDetails, aiSectDisciples, exploredSects, scoutInfo...）
- 外交（alliances, sectRelations, playerAllianceSlots...）
- 商人（travelingMerchantItems, playerListedItems, merchantAcquisitionItems...）
- 弟子招募（recruitList, lastRecruitYear...）
- 探索（cultivatorCaves, caveExplorationTeams, worldLevels...）
- 宗门政策（sectPolicies, yearlySalary, yearlySalaryEnabled...）
- 战斗（battleTeams, aiBattleTeams...）
- 血炼（bloodRefinements, activeBloodRefinements...）
- 天道试炼（heavenlyTrialState）
- 签到（signInState）
- 邮件（mailRecords）
- 兑换码（usedRedeemCodes）

每次修改任何一个字段，`GameData.copy()` 都要复制整个对象（包括所有嵌套的 List 和 Map）。

### SettlementStrategy 注解

为解决 Shadow 结算时的字段合并问题，项目引入了 `@SettlementStrategy` 注解（`SettlementStrategy.kt`），定义了 5 种合并策略：

| 策略 | 语义 | 适用场景 |
|------|------|----------|
| PRESERVE_OLD | 保留旧值 | 游戏设置 |
| USE_SHADOW | 使用结算值 | 结算独占字段 |
| DELTA | 三路增量合并 | spiritStones |
| THREE_WAY_ID | 三路 ID 合并 | recruitList, alliances |
| CUSTOM | 自定义合并 | worldLevels, worldMapSects... |

这个设计本身精巧，但它的存在恰恰说明了问题的复杂度——因为 GameData 太大太耦合，才需要这么复杂的合并机制。GameData 上有 **13 个 CUSTOM 策略字段**，每个都需要手写合并函数。

### 影响

- `GameData.copy()` 是高频操作，每次复制 60+ 字段 + 所有嵌套集合
- 新增字段必须同时考虑 SettlementStrategy，遗漏会导致合并错误
- 任何子系统的修改都可能影响 GameData 的 copy 性能
- 测试困难：修改一个字段需要构造完整的 GameData 实例

---

## 7. 问题5：CultivationCore 双重状态访问模式

**严重度**：中高
**文件**：`android/core/engine/src/main/java/com/xianxia/sect/core/service/CultivationCore.kt` 第36-77行

### 现状

CultivationCore 自定义了 `currentGameData` / `currentDisciples` 等属性访问器：

```kotlin
private var currentGameData: GameData
    get() = stateStore.currentTransactionMutableState()?.gameData ?: stateStore.gameData.value
    set(value) {
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) { ts.gameData = value; return }      // 路径1：事务内，直接写
        scope.launch { stateStore.update { gameData = value } } // 路径2：事务外，异步写
    }
```

### 问题

1. **行为不确定**：同一个属性的 setter 有两条路径——事务内直接写、事务外异步写。调用方无法确定赋值是否即时生效
2. **时序风险**：异步路径 `scope.launch` 意味着赋值不是即时的，后续读取可能拿到旧值
3. **绕过统一机制**：这种模式绕过了 GameStateStore 的 `update {}` 统一更新机制，破坏了状态一致性保证
4. **多处使用**：CultivationCore 中定义了 6 个这样的双重访问器（gameData, disciples, equipmentStacks, equipmentInstances, manualStacks, manualInstances）

---

## 8. 问题6：结算路径与 tick 路径的逻辑重复

**严重度**：中
**影响范围**：CultivationCore、SettlementCoordinator、DiscipleBreakthroughHandler

### 重复逻辑对照

| 功能 | tick 路径 | 结算路径 | 差异 |
|------|-----------|----------|------|
| 修炼速度计算 | `CultivationCore.calculateDiscipleCultivationPerSecond` | `SettlementCache.cultivationRateCache`（缓存结果） | 结算路径做了缓存优化 |
| 突破处理 | `DiscipleBreakthroughHandler.processRealtimeBreakthroughs` | `SettlementCoordinator.processBreakthroughForDisciple` | 两套独立实现 |
| HP/MP 恢复 | `CultivationCore.recoverHpMpForAllDisciples` | `SettlementCoordinator.isDiscipleFullHpMp`（独立实现） | 逻辑重复 |
| 熟练度计算 | `CultivationCore.updateMonthlyCultivation` | `SettlementCoordinator.calculateProficiencyGains` | 几乎相同的逻辑写了两遍 |
| 养成计算 | `CultivationCore.updateFocusedDisciple` 中的 nurture 逻辑 | `SettlementCoordinator.calculateNurtureGains` | 逻辑重复 |

### 影响

- 修改业务逻辑时需要同步更新两处，极易遗漏
- 两套实现的细微差异可能产生不一致行为
- 增加了测试负担

---

## 9. 问题7：SettlementCoordinator 线程安全隐患

**严重度**：中高
**文件**：`android/core/engine/src/main/java/com/xianxia/sect/core/domain/settlement/SettlementCoordinator.kt` 第41-44行

### 现状

```kotlin
@Volatile
private var shadowState: MutableGameState? = null
@Volatile
private var currentCache: SettlementCache? = null
```

### 问题

1. **`@Volatile` 不保证原子性**：只保证可见性，不保证复合操作的原子性。`shadowState` 的读-改-写不是原子的
2. **并行处理中的共享可变状态**：`processCleanDiscipleBatch`（第298-319行）和 `processDirtyDiscipleBatch`（第356-394行）使用 `async(Dispatchers.Default)` 并行处理，但都读写 `shadow.disciples`
3. **cancelPendingWork 的竞态**：第212-216行直接置空 shadowState 和 currentCache，如果此时另一个线程正在读取，可能导致 NPE
4. **fingerprint 缓存**：第83-96行的缓存命中判断依赖 `lastFingerprint` 和 `reusableCache`，这两个变量不是 `@Volatile` 的，存在可见性问题

---

## 10. 问题8：数据库层问题

**严重度**：中
**文件**：`android/core/data/src/main/java/com/xianxia/sect/data/local/Daos.kt`

### 问题清单

1. **Disciple 表过宽**：Disciple 包含 6 个 `@Embedded` 子组件，单表约 50+ 列。Room 的 `@Embedded` 不会创建子表，所有字段平铺在一张表中。这导致：
   - 查询任何子集都需要加载整行
   - 修改任何一个字段都需要写入整行
   - Room 的 `@Update` 是全字段 UPDATE

2. **缺少部分索引**：Disciple 表已有 `realm+realmLayer`、`isAlive+status` 等索引，但部分高频查询路径（如按 discipleType 过滤、按 loyalty 排序）缺少组合索引

3. **序列化开销**：`SaveDataConverter.kt` 和 `WorldAndSectConverter.kt` 对大型嵌套对象做 JSON 序列化/反序列化。GameData 中的 `worldMapSects`、`sectDetails`、`aiSectDisciples` 等字段包含大量嵌套数据，序列化开销显著

4. **全量保存**：存档机制是全量序列化所有状态，而非增量保存。随着游戏进度推进，存档体积和耗时都会增长

---

## 11. 问题9：测试覆盖与工程配置

**严重度**：中

### 测试覆盖

| 模块 | 测试文件数 | 关键覆盖 | 缺失 |
|------|-----------|----------|------|
| core/domain | ~15 | 模型验证、枚举、配置加载 | 缺少 GameData 复杂合并策略的测试 |
| core/engine | ~15 | 战斗系统、公式、仓库、建筑 | 缺少 SettlementCoordinator 集成测试、CultivationCore tick 路径测试 |
| feature/game | 1 | ViewModel 架构测试 | 缺少 UI 测试、交互测试 |
| core/data | 0 | 无 | 序列化/反序列化、DAO 操作 |

### 工程配置

- **Detekt**：已配置（`config/detekt/detekt.yml`），但存在 detekt-baseline 文件，说明有大量已知违规被压制
- **Android Lint**：已配置，有 baseline 文件
- **CI/CD**：存在 `.github/workflows/device-test.yml`，但仅覆盖设备测试
- **Baseline Profile**：已配置（`baselineprofile/` 模块），但不确定是否持续维护

---

## 12. 问题严重度矩阵

| # | 问题 | 严重度 | 影响范围 | 修复难度 | 优先级 |
|---|------|--------|----------|----------|--------|
| 1 | Disciple.copyWith 60+ 参数 | 高 | 全项目 | 高 | P1 |
| 2 | List 全量重建 O(n) 遍历 | 高 | engine 热路径 | 中 | P0 |
| 3 | 状态架构过度分层 | 中高 | 全局状态管理 | 高 | P2 |
| 4 | GameData God Object | 高 | 全项目 | 高 | P1 |
| 5 | CultivationCore 双重状态访问 | 中高 | 修炼系统 | 中 | P1 |
| 6 | 结算/tick 逻辑重复 | 中 | 修炼+结算 | 中 | P2 |
| 7 | SettlementCoordinator 线程安全 | 中高 | 结算系统 | 中 | P1 |
| 8 | 数据库层问题 | 中 | 持久化层 | 中 | P2 |
| 9 | 测试覆盖不足 | 中 | 全项目 | 低 | P2 |

### 优先级说明

- **P0（立即修复）**：List 全量重建——直接影响运行时性能，随弟子数量增长会急剧恶化，且修复方向明确（Map 索引替代 List 遍历）
- **P1（短期修复）**：线程安全隐患、双重状态访问、copyWith/God Object——影响正确性和可维护性
- **P2（中期规划）**：状态分层重构、逻辑去重、数据库优化、测试补充——影响长期可维护性

---

## 附录：关键文件索引

| 文件 | 路径 | 关键问题 |
|------|------|----------|
| Disciple.kt | `android/core/domain/.../model/Disciple.kt` | 60+ 参数 copyWith |
| GameData.kt | `android/core/domain/.../model/GameData.kt` | 985 行 God Object |
| GameStateStore.kt | `android/core/domain/.../state/GameStateStore.kt` | 过度分层 |
| MutableGameState.kt | `android/core/domain/.../state/MutableGameState.kt` | 可变状态定义 |
| UnifiedGameState.kt | `android/core/domain/.../state/UnifiedGameState.kt` | O(n) 查找 |
| SettlementStrategy.kt | `android/core/domain/.../state/SettlementStrategy.kt` | 复杂合并策略 |
| GameEngineCore.kt | `android/core/engine/.../GameEngineCore.kt` | 100ms tick 循环 |
| CultivationCore.kt | `android/core/engine/.../service/CultivationCore.kt` | 双重状态访问 |
| CultivationService.kt | `android/core/engine/.../service/CultivationService.kt` | 委托链过长 |
| SettlementCoordinator.kt | `android/core/engine/.../domain/settlement/SettlementCoordinator.kt` | 线程安全、逻辑重复 |
| FormulaService.kt | `android/core/engine/.../service/FormulaService.kt` | List 遍历 |
| Daos.kt | `android/core/data/.../local/Daos.kt` | 表设计、索引 |
| SaveDataConverter.kt | `android/core/data/.../serialization/unified/SaveDataConverter.kt` | 序列化开销 |
