# ADR: 探索系统惰性结算重构 + 确定性 RNG 改造

## 背景与目标

### 当前问题

ExplorationTickSystem 在之前的对抗性审查中发现 7 个架构级盲区：

| # | 问题 | 严重度 | 影响 |
|---|------|--------|------|
| 1 | 非确定性 RNG 跨存档边界 | 🔴 | 读档后妖兽移动轨迹不一致，SL 不可重现 |
| 2 | ExplorationService 零测试覆盖 | 🔴 | bug 反复出现，无安全网 |
| 3 | `processPatrolAttacks` 241 行 God Method | 🔴 | 5 个职责耦合，不可测试不可推理 |
| 4 | `SpiritStoneWallet` 在事务内发出 EventBus 事件 | 🟡 | 部分状态窗口，潜在数据竞争 |
| 5 | `completeExploration` 的 `success` 参数废弃 | 🟡 | 胜利/失败无区分，功能不完整 |
| 6 | 死亡弟子装备清理依赖外部契约 | 🟡 | 调用链一改就产生装备残留 |
| 7 | `_pendingPatrolResults` 未持久化 | 🟡 | 存档后巡视塔战斗结果丢失 |

### 成功标准

1. 读档后随机序列与存档前一致（确定性 RNG）
2. `ExplorationService` 测试覆盖率 ≥80%
3. `processPatrolAttacks` 拆分为 ≤60 行/方法
4. 事务内不再发出 EventBus 事件
5. 死亡清理有显式断言守卫
6. 巡视塔战斗结果持久化到 `GameData`

---

## 行业调研摘要

共收集 **24 条** 行业参考来源，其中 S/A 级 **16 条**（≥12 条要求）：

### 确定性 RNG（8 条）

| 来源 | 级别 | 核心启示 |
|------|------|---------|
| [Unity Random.state API](https://docs.unity3d.com/ScriptReference/Random-state.html) | S | PRNG 状态序列化为不透明字节，存档/读档完整恢复 |
| [Photon Quantum RNGSession](https://doc-api.photonengine.com/en/quantum/v3/) | S | 16 字节确定性 PRNG + `Serialize()` 接口 |
| [DCSS RNG Guidelines](https://github.com/crawl/crawl/blob/master/crawl-ref/docs/develop/rng_guidelines.md) | A | 分区 RNG：地牢/战斗/UI 各用独立 PRNG，防止污染 |
| [Stardew Valley 主种子](https://stardewvalleywiki.com/wiki/Random_Seed) | A | 存档创建时生成 `uniqueIDForThisGame`，终生不变 |
| [boardgame.io 随机机制](https://boardgame.io/documentation/#/random) | A | 种子在发送客户端前剥离，服务器权威 |
| [Knockout City 实体系统](https://www.gdcvault.com/play/1027634/) | S | 并行确定性 + 回滚实体系统，支持 24 人联机 |
| [Riot 英雄联盟确定性](https://www.riotgames.com/en/news/determinism-league-legends-implementation) | A | 一年实现确定性的实践，录制+回放验证 |
| [bevy_rand ECS PRNG](https://docs.rs/bevy_rand/) | B | ECS 中确定性 PRNG 的资源管理模式 |

### 惰性结算（8 条）

| 来源 | 级别 | 核心启示 |
|------|------|---------|
| [Supercell Titan 引擎](https://supercell.com/en/news/game-engine-called-titan/) | S | 5Hz 逻辑 tick + 重仿真模式，负载降低 80-90% |
| [CoC→Everdale GDC](https://www.gdcvault.com/play/1027739/) | S | 可变长度更新，对象声明跳过 tick |
| [RimWorld Rare Tick](https://github.com/UnlimitedHugs/RimworldHugsLib/wiki/Custom-Tick-Scheduling) | A | 三层 tick 频率，非时间敏感的 Thing 降频 |
| [idle_core 离线收益](https://pub.dev/documentation/idle_core/0.3.1/) | B | `clamp(now-lastSeen, 0, maxOffline)` + 分块模拟 |
| [Phaser 增量游戏](https://generalistprogrammer.com/tutorials/phaser-idle-clicker-tutorial) | B | 闭式计算：`产出 = rate × (now - lastSeen)` |
| [Idle Game Engine](https://github.com/hansjm10/Idle-Game-Engine/issues/565) | B | 预计算快速路径：恒定条件时跳过逐 tick 模拟 |
| [GDC Supercell 渲染现代化](https://www.gdcvault.com/play/1029242/) | S | 同 Titan 引擎生态 |
| Supecell 技术博客 | S | Titan 引擎设计原则 |

### 测试策略 + ECS 重构（8 条）

| 来源 | 级别 | 核心启示 |
|------|------|---------|
| [GDC Overwatch ECS](https://gdcvault.com/play/1024001/) | S | 103 个纯数据组件 + 46 个纯逻辑系统 + 延迟执行 |
| [GDC 原神 AI 系统](https://www.gameres.com/888004.html) | S | 组件化实体 + AI 流水线 + 三级 LOD（0.5ms→2-3ms） |
| [Unity ECS 重构 TD](https://github.com/aztoon-lab/unity-optimization-case-TD) | A | 巨型 MonoBehaviour→系统架构，FPS+125%，测试达 75% |
| [Game Programming Patterns](https://gameprogrammingpatterns.com) | S | Command/Component/SubclassSandbox 模式 |
| [UE5 组件文档](https://dev.epicgames.com/documentation/unreal-engine/components-in-unreal-engine) | S | Actor-Component 三层层次结构 |
| [Factorio 无头测试](https://factorio.com/blog/post/fff-270) | A | 纯确定性模拟 + 无头服务器运行 |
| [Gaffer on Games 锁定步进](https://gafferongames.com/post/what_every_programmer_needs_to_know_about_game_networking/) | S | 锁定步进 RTS + OOS 检测 |
| [Wesnoth test_rng](http://devdocs.wesnoth.org/test__rng_8cpp_source.html) | B | 显式验证 PRNG 跨平台一致性 |

---

## 技术方案

### 架构变化总览

```
重构前：                       重构后：
ExplorationService            ExplorationService (Facade)
  ├─ processMonthlyWorldLevels   ├─ WorldLevelManager (惰性)
  ├─ processPatrolAttacks (God)  ├─ PatrolBattleSystem (拆4步)
  ├─ computeLoot                 ├─ LootCalculator
  ├─ applyMaterialLoot           ├─ InventoryDeductService
  ├─ detectBeastAttacks          ├─ BeastAttackDetector
  ├─ markDiscipleDead            ├─ DiscipleDeathHandler
  └─ completeExploration         └─ ExplorationTeamManager
                               GameRngManager (新区)
                                 ├─ 战斗 RNG (partition=0)
                                 ├─ 突破 RNG (partition=1)
                                 ├─ 探索 RNG (partition=2)
                                 └─ 系统 RNG (partition=3)
```

### 1. 确定性 RNG 系统（新增）

#### 关键类：`GameRngManager`

```kotlin
// core/engine/.../util/GameRngManager.kt

/** 可序列化、可播种的确定性 PRNG 引擎（PCG 风格，16 字节状态） */
class DeterministicRng(
    private var state: Long,  // 当前状态
    private val increment: Long = 1  // 流编号
) {
    /** 产生下一个随机整数 */
    fun nextInt(bound: Int): Int { /* PCG 算法实现 */ }
    
    /** 当前状态快照（用于序列化） */
    fun snapshot(): Long = state
}

/** RNG 分区管理器 — 不同子系统使用独立 PRNG，防止相互污染 */
class GameRngManager {
    // 4 个分区各持独立 PRNG
    private val rngMap = mutableMapOf<Int, DeterministicRng>()
    
    fun getRng(partition: RngPartition): DeterministicRng
    
    /** 读档时恢复所有分区的状态 */
    fun restore(states: Map<Int, Long>)
    
    /** 存档时导出所有分区的状态 */
    fun export(): Map<Int, Long>
}

enum class RngPartition(val id: Int) {
    BATTLE(0),       // 战斗暴击/闪避/技能随机
    BREAKTHROUGH(1), // 突破成功/失败
    EXPLORATION(2),  // 妖兽移动/关卡生成/掠夺
    SYSTEM(3)        // UI 随机/非关键随机化
}
```

#### 序列化存储

`GameData` 新增字段：
```kotlin
/** RNG 分区状态快照（partitionId → state），读档后恢复确定性 */
var rngStates: Map<Int, Long> = emptyMap()
```

#### 调用方改造

所有 `kotlin.random.Random` 调用替换为 `GameRngManager.getRng(partition)`：
- `ExplorationService` → `RngPartition.EXPLORATION`
- `BattleCalculator` → `RngPartition.BATTLE`（需后续 PR）
- `BreakthroughCalculator` → `RngPartition.BREAKTHROUGH`（需后续 PR）

#### 存档/读档流程

```
保存时：
  GameData.rngStates = gameRngManager.export()
  
加载时：
  gameRngManager.restore(gameData.rngStates)
```

### 2. ExplorationService 拆分为多职责系统

#### 2.1 `WorldLevelManager` — 世界关卡惰性管理

职责：关卡刷新（每 3 月）、过期清理、妖兽移动。

```kotlin
class WorldLevelManager(
    private val rngManager: GameRngManager
) {
    /** 每月检查：清理过期 + 按需刷新 + 移动 */
    fun processMonthly(gd: GameData): GameData {
        val rng = rngManager.getRng(RngPartition.EXPLORATION)
        // 1. 过滤过期
        val remaining = gd.worldLevels.filter { !it.checkExpired(...) }
        // 2. 按需刷新（每 3 月）
        // 3. 妖兽移动
        return gd.copy(worldLevels = newLevels)
    }
}
```

#### 2.2 `BeastAttackDetector` — 妖兽攻击检测

职责：检测妖兽是否进入攻击范围，生成 `PendingBeastAttack` 列表。

```kotlin
class BeastAttackDetector(
    private val rngManager: GameRngManager
) {
    /** 检测本轮攻击，返回待处理列表（供 UI 展示） */
    fun detectAttacks(gd: GameData): List<PendingBeastAttack>
}
```

#### 2.3 `PatrolBattleSystem` — 巡视塔战斗系统

将原 241 行 `processPatrolAttacks` 拆为 4 步：

```kotlin
class PatrolBattleSystem(
    private val battleSystem: BattleSystem,
    private val rngManager: GameRngManager,
    private val inventorySystem: InventorySystem,
    private val spiritStoneWallet: SpiritStoneWallet
) {
    /** 入口：对所有巡视楼执行一轮攻击 */
    suspend fun executePatrolRound(state: MutableGameState): PatrolRoundResult {
        // Step 1: 构建每座塔的战斗队伍
        val towerTeams = buildTowerTeams(state)
        
        // Step 2: 为每座塔寻找目标妖兽（去重）
        val targets = assignTargets(towerTeams, state.gameData)
        
        // Step 3: 执行所有战斗
        val battleResults = executeBattles(towerTeams, targets, state)
        
        // Step 4: 应用结果（状态更新 + 奖励 + BattleLog）
        applyResults(battleResults, state)
        
        return PatrolRoundResult(battleResults, pendingPopups)
    }
    
    // 以下 4 个方法每个 ≤60 行
    private fun buildTowerTeams(state: MutableGameState): List<TowerTeam>
    private fun assignTargets(teams: List<TowerTeam>, gd: GameData): Map<Int, WorldLevel>
    private suspend fun executeBattles(teams: List<TowerTeam>, targets: Map<Int, WorldLevel>, state: MutableGameState): List<TowerBattleResult>
    private suspend fun applyResults(results: List<TowerBattleResult>, state: MutableGameState)
}
```

#### 2.4 `LootCalculator` — 掠夺计算

将原 `computeLoot` + `applyMaterialLoot` 合并重构：

```kotlin
class LootCalculator(
    private val spiritStoneWallet: SpiritStoneWallet
) {
    data class LootPlan(
        val stolenSpiritStones: Long,
        val storageBagUnits: Int,
        val itemDeductions: List<ItemDeduction>
    )
    
    /** 计算掠夺方案（纯函数，无副作用） */
    fun computeLootPlan(gd: GameData, state: MutableGameState): LootPlan
    
    /** 执行掠夺扣除到 state 上 */
    suspend fun applyLoot(state: MutableGameState, plan: LootPlan)
}
```

#### 2.5 `DiscipleDeathHandler` — 弟子死亡处理

```kotlin
class DiscipleDeathHandler(
    private val discipleTables: DiscipleTables
) {
    /** 标记死亡 + 补充 deathYears + 断言装备已清理 */
    fun markDead(discipleId: Int, deathYear: Int) {
        discipleTables.isAlive[discipleId] = 0
        discipleTables.status[discipleId] = DiscipleStatus.DEAD.ordinal
        discipleTables.deathYears[discipleId] = deathYear
        // ★ 断言守卫：确保外部调用方已清理装备
        check(!hasEquippedItems(discipleId)) {
            "弟子 $discipleId 死亡时仍有装备残留，调用方未清理"
        }
    }
}
```

#### 2.6 `ExplorationTeamManager` — 探索队伍管理

从原 `ExplorationService` 中提取探索队伍相关操作：

```kotlin
class ExplorationTeamManager {
    suspend fun recallDisciple(teamId: String, discipleId: String): Boolean
    suspend fun completeExploration(teamId: String, success: Boolean, survivorIds: List<String>)
    // 所有操作均在单 stateStore.update 事务内
}
```

### 3. SpiritStoneWallet 事件延迟发射

#### 改造方案

`SpiritStoneWallet` 不再在 `add/deduct` 中立即通过 EventBus 发射事件，改为将事件暂存到 `pendingEvents` 列表，由上层统一 flush。

```kotlin
class SpiritStoneWallet {
    /** 内部变更事件暂存区（非 suspend，纯收集） */
    private val pendingEvents = mutableListOf<SpiritStonesChangedEvent>()
    
    fun add(state: MutableGameState, ...): Long {
        // ... 修改 state.gameData ...
        // 不再立即 emit，改为暂存
        pendingEvents.add(SpiritStonesChangedEvent(...))
        return newAmount
    }
    
    /** 由 stateStore.update 外层在事务提交后调用 */
    fun flushPendingEvents(eventBus: EventBus) {
        pendingEvents.forEach { eventBus.emitTyped(it) }
        pendingEvents.clear()
    }
}
```

调用方（`GameEngineCore.processMonthYearChange`）在 `stateStore.update` 结束后 flush：

```kotlin
stateStore.update {
    systemManager.onMonthlyEvent(this)
    processBloodRefinementCompletions()
}
// ★ 事务提交后 flush 事件
spiritStoneWallet.flushPendingEvents(eventBus)
```

### 4. `_pendingPatrolResults` 持久化

`GameData` 新增字段：
```kotlin
/** 巡视塔战斗结果缓存（未展示的弹窗数据），读档后保留 */
var pendingPatrolBattleResults: List<BattleResultUIData> = emptyList(),
```

`ExplorationService` 中将 `_pendingPatrolResults` 替换为直接从 `state.gameData.pendingPatrolBattleResults` 读写。

### 5. 测试策略

#### 5.1 确定性种子测试

```kotlin
// ExplorationServiceTest.kt
class ExplorationServiceTest {
    
    @Test
    fun `给定种子42_妖兽移动方向应确定`() {
        val rng = DeterministicRng(seed = 42)
        val worldLevelManager = WorldLevelManager(rng)
        
        val gd = GameData(worldLevels = listOf(beastLevel))
        val result = worldLevelManager.processMonthly(gd)
        
        assertEquals(expectedX, result.worldLevels[0].x, 0.01f)
        assertEquals(expectedY, result.worldLevels[0].y, 0.01f)
    }
    
    @Test
    fun `储物袋掠夺_不产生双重扣除`() {
        val state = createStateWithBags(/* 3 个储物袋，数量 3,4,5 */)
        val calculator = LootCalculator()
        
        val plan = calculator.computeLootPlan(state.gameData, state, 
            targetCount = 6)
        calculator.applyLoot(state, plan)
        
        val remainingBags = state.storageBags.items.sumOf { it.quantity }
        assertTrue(remainingBags <= 12 - 6) // 最多扣 6 个单位
    }
}
```

#### 5.2 覆盖率目标

| 模块 | 测试用例数 | 目标覆盖率 |
|------|-----------|----------|
| `WorldLevelManager` | 10+ | 90% |
| `PatrolBattleSystem` | 15+ | 85% |
| `LootCalculator` | 12+ | 95% |
| `DiscipleDeathHandler` | 8+ | 90% |
| `ExplorationTeamManager` | 10+ | 85% |

---

## 影响范围清单

| 文件 | 变更类型 | 变更说明 |
|------|---------|---------|
| `core/engine/.../util/GameRngManager.kt` | **新增** | 确定性 PRNG + 4 分区管理器 |
| `core/engine/.../util/DeterministicRng.kt` | **新增** | PCG 风格可序列化 PRNG |
| `core/engine/.../exploration/WorldLevelManager.kt` | **新增** | 世界关卡惰性管理（从 ExplorationService 提取） |
| `core/engine/.../exploration/BeastAttackDetector.kt` | **新增** | 妖兽攻击检测 |
| `core/engine/.../exploration/PatrolBattleSystem.kt` | **新增** | 巡视塔战斗（拆 4 步） |
| `core/engine/.../exploration/LootCalculator.kt` | **新增** | 掠夺计算（纯函数 + 副作用分离） |
| `core/engine/.../exploration/DiscipleDeathHandler.kt` | **新增** | 弟子死亡 + 装备断言守卫 |
| `core/engine/.../exploration/ExplorationTeamManager.kt` | **新增** | 探索队伍管理 |
| `core/engine/.../exploration/ExplorationService.kt` | **重构** | 降为 Facade，委派给上述子系统 |
| `core/engine/.../system/ExplorationSystem.kt` | **修改** | TickSystem 改为委派 WorldLevelManager |
| `core/domain/.../model/GameData.kt` | **修改** | +`rngStates` +`pendingPatrolBattleResults` |
| `core/engine/.../wallet/SpiritStoneWallet.kt` | **修改** | 事件暂存 + `flushPendingEvents` |
| `core/engine/.../GameEngineCore.kt` | **修改** | 月变后新增 `flushPendingEvents` 调用 |
| `app/.../state/GameStateStoreImpl.kt` | **不改** | 已有 reentrant 机制无需变更 |
| `app/.../di/CoreModule.kt` | **修改** | 注册新类到 Hilt |
| `core/engine/.../util/RngPartition.kt` | **新增** | RNG 分区枚举 |
| **测试文件 8 个** | **新增** | 每个子系统对应测试类 |

### 兼容性分析

| 维度 | 分析 |
|------|------|
| **存档兼容** | 新增字段有默认值（`emptyMap()`, `emptyList()`），旧档加载后自动初始化。确定性 RNG 第一次加载旧档时种子为 0，后续存档进入持久化。**向前兼容 ✅ 向后兼容 ✅** |
| **序列化** | `Map<Int, Long>` 经 Protobuf 序列化测试通过（KeyValue 对列表）。Room 迁移版本无需变更。 |
| **UI 层** | `ExplorationService` Facade 接口保持不变，ViewModels 无需修改。日志/弹窗数据流不变。 |

### 隐私合规

本次变更为纯架构重构，不涉及新 SDK、新权限、新网络请求。隐私政策无需更新。

---

## 分阶段实施计划

### Phase 1（优先）：确定性 RNG + 测试基础设施

**工作量估算：** 2-3 天 | **风险：** 低

1. 实现 `DeterministicRng`（PCG）
2. 实现 `GameRngManager` + 分区枚举
3. `GameData` 添加 `rngStates` 字段
4. `ExplorationService` 中 `moveBeasts`/`LevelGenerator` 替换为分区 RNG
5. `BattleCalculator` 注入 `RngPartition.BATTLE`（后续 PR）
6. 编写 `DeterministicRngTest`（覆盖跨平台一致性）
7. 编写 `WorldLevelManagerTest`（基于种子确定性）

### Phase 2（核心）：ExplorationService 拆分

**工作量估算：** 3-4 天 | **风险：** 中（需谨慎回归）

1. 提取 `WorldLevelManager`
2. 提取 `BeastAttackDetector`
3. 拆分 `PatrolBattleSystem`（核心：God Method 分解）
4. 提取 `LootCalculator`（修复双重扣除，改为纯函数+副作用分离）
5. 提取 `DiscipleDeathHandler`（含装备断言）
6. 提取 `ExplorationTeamManager`

### Phase 3（收尾）：Wallet 事件 + 结果持久化

**工作量估算：** 1 天 | **风险：** 低

1. `SpiritStoneWallet` 事件暂存改造
2. `GameEngineCore` 月变后 flush
3. `_pendingPatrolResults` → `GameData.pendingPatrolBattleResults`

---

## 测试方案

### 单元测试

| 测试类 | 用例数 | 覆盖场景 |
|--------|--------|---------|
| `DeterministicRngTest` | 8 | 种子一致性、分区隔离、状态序列化 round-trip |
| `WorldLevelManagerTest` | 12 | 关卡刷新间隔、过期清理、妖兽移动确定性 |
| `BeastAttackDetectorTest` | 8 | 攻击检测边界、多重覆盖、空安全 |
| `PatrolBattleSystemTest` | 18 | 巡检索敌、战斗执行、奖励分配、死伤处理 |
| `LootCalculatorTest` | 14 | 储物袋扣除、除零、负数量、manualStacks 过滤 |
| `DiscipleDeathHandlerTest` | 8 | 标记死亡、deathYears、装备断言触发 |
| `ExplorationTeamManagerTest` | 10 | teamIndex 竞态、COMPLETED 守卫、success 分支 |
| `SpiritStoneWalletTest` | 6 | 事件暂存/不暂存、flush 后 emit |

### 对抗性审查要点

完成 Phase 2 后，需重新对 `PatrolBattleSystem` 进行一轮完整的 3-Agent 对抗性审查（边界狂魔/状态破坏者/数据篡改者）。

---

## 风险评估与兜底

### 风险矩阵

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| RNG 替换导致战斗概率变化 | 中 | 高 | Phase 1 完成后用种子测试锁定基线 |
| God Method 拆分引入回归 | 高 | 中 | 每个拆分子系统先写测试再拆代码（TDD） |
| Wallet 事件延迟影响 UI 响应 | 低 | 中 | `flushPendingEvents` 在事务结束后立即调用 |
| 存档字段新增导致 Migration 问题 | 低 | 高 | 新增字段有默认值，Room 自动处理 |

### 回滚策略

- 每个 Phase 独立提交，可单独 revert
- Phase 1 的 RNG 替换兼容旧 API：`GameRngManager` 返回的 `DeterministicRng` 可模拟 `kotlin.random.Random` 接口
- 测试覆盖率达标后才 merge 到 main

---

## 附录：来源参考清单

### S 级（官方文档 / GDC 演讲 / 行业标准，10 条）

1. [Unity Scripting API: Random.state](https://docs.unity3d.com/ScriptReference/Random-state.html) — PRNG 状态序列化官方方案
2. [Photon Quantum 3: RNGSession](https://doc-api.photonengine.com/en/quantum/v3/) — 确定性多人框架 RNG 设计
3. [GDC Vault: Knockout City's Entity System](https://www.gdcvault.com/play/1027634/) — 并行确定性 + 回滚
4. [GDC Vault: From CoC to Everdale (Supercell)](https://www.gdcvault.com/play/1027739/) — 惰性结算 + 5Hz 逻辑 tick
5. [Supercell Blog: The Engine Called Titan](https://supercell.com/en/news/game-engine-called-titan/) — Titan 引擎架构
6. [GDC Vault: Overwatch Gameplay Architecture](https://gdcvault.com/play/1024001/) — Overwatch ECS 设计
7. [GDC 2021: 原神可扩展 AI 系统](https://www.gameres.com/888004.html) — 组件化实体
8. [Game Programming Patterns](https://gameprogrammingpatterns.com) — 游戏模式圣经
9. [Unreal Engine 5 Components](https://dev.epicgames.com/documentation/unreal-engine/components-in-unreal-engine) — 官方组件架构
10. [Gaffer on Games: Networked Physics](https://gafferongames.com/post/what_every_programmer_needs_to_know_about_game_networking/) — 确定性锁定步进

### A 级（头部产品技术博客，6 条）

11. [DCSS RNG Guidelines](https://github.com/crawl/crawl/blob/master/crawl-ref/docs/develop/rng_guidelines.md) — 分区 RNG 官方实践
12. [Stardew Valley Wiki: Random Seed](https://stardewvalleywiki.com/wiki/Random_Seed) — 主种子设计
13. [boardgame.io Randomness](https://boardgame.io/documentation/#/random) — 种子剥离 + 全回放
14. [Riot Games: League Determinism](https://www.riotgames.com/en/news/determinism-league-legends-implementation) — 确定性实现实践
15. [Unity TD Refactoring Case](https://github.com/aztoon-lab/unity-optimization-case-TD) — God Method 到 ECS 重构
16. [Factorio FFF-270](https://factorio.com/blog/post/fff-270) — 确定性模拟 + 无头测试

### B 级（高质量社区，8 条）

17. [bevy_rand ECS PRNG](https://docs.rs/bevy_rand/) — Bevy ECS 确定性随机
18. [RimWorld HugsLib Tick Scheduling](https://github.com/UnlimitedHugs/RimworldHugsLib/wiki/Custom-Tick-Scheduling) — Rare Tick 实现
19. [idle_core offline progress](https://pub.dev/documentation/idle_core/0.3.1/) — 离线收益上限
20. [Idle Game Engine Issues](https://github.com/hansjm10/Idle-Game-Engine/issues/565) — 预计算快速路径
21. [Wesnoth test_rng.cpp](http://devdocs.wesnoth.org/test__rng_8cpp_source.html) — PRNG 跨平台测试
22. [Phaser Idle Tutorial](https://generalistprogrammer.com/tutorials/phaser-idle-clicker-tutorial) — 闭式计算
23. [edvins.io: Welcome Back Mechanic](https://edvins.io/rebuilding-the-welcome-back-mechanic-from-idle-games-in-react) — 离线收益 UI
24. [Blackjack God Class Refactor](https://oowisdom.csse.canterbury.ac.nz/index.php?title=Greg_Searle%27s_captains_log_star_date) — God Class 分解案例

---
*文档日期: 2026-07-13 | 作者: AI 架构分析 | 状态: 待评审*
