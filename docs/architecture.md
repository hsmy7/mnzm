# 项目架构文档

> 本文档描述了 XianxiaSectNative 的核心架构设计。对应 CLAUDE.md 中的「架构」相关章节。
> 编码规范、设计方案规则等见 [CLAUDE.md](../CLAUDE.md)。

---

## 目录

- [双层状态模型 + Frame-Driven 游戏循环](#architecture-two-layer-state-model--frame-driven-game-loop)
- [游戏循环：Frame-Driven Accumulator](#game-loop-architecture-frame-driven-accumulator-pattern)
- [结算架构：惰性结算引擎](#settlement-architecture-lazy-settlement-engine)
- [线程架构：双游戏线程 + Watchdog](#threading-architecture-two-game-threads)
- [GameSystem 生命周期](#gamesystem-生命周期)
- [乘区法公式架构](#formula-architecture-zone-multiplier-system)
- [生命周期：BootPhase / RunState 双层状态机](#lifecycle-architecture-bootphase--runstate-双层状态机)
- [抗冻结架构：自适应忙等](#抗冻结架构自适应忙等)
- [帧预算监控](#帧预算监控)
- [关键源码目录](#key-source-directories)
- [架构文档索引](#architecture-docs)

---

## Architecture: Two-Layer State Model + Frame-Driven Game Loop

```
Layer 2: UI (ViewModel + Compose) — 订阅 GameStateStore，DialogStateManager 管理对话框
Layer 1: GameEngineCore + GameEngine — 游戏循环 + 业务逻辑，写入 GameStateStore._state
```

### Data Flow

```
User Action → ViewModel calls GameEngine → Service reads/writes GameStateStore._state → StateFlow emits → ViewModel.collectAsState() → UI recomposition
```

- **GameEngine** is the single entry point for all state mutations from the UI layer. ViewModels never write to `GameStateStore` directly.
- **GameEngineCore** drives a frame-driven accumulator game loop (R1), advancing game logic at 100ms fixed steps via deltaTime accumulation.
- **GameStateStore** is the single source of truth — one `MutableStateFlow<UnifiedGameState>` containing all game state. Individual `StateFlow` projections are derived via `.map {}`.

---

## Game Loop Architecture: Frame-Driven Accumulator Pattern

游戏循环从 v4.0.38 起从 **timer-driven**（`delay(100ms)` 固定频率循环）重构为 **frame-driven accumulator 模式**。

```
while (isActive) {
    deltaNs = nanoTime() - lastFrameTime                 // 实际流逝时间
    accumulatorNs += deltaNs.coerceAtMost(MAX_ACCUM)     // 累加（防爆炸）

    while (accumulatorNs >= LOGIC_DT_NS) {               // 固定步长消费
        tickInternal()                                    // 100ms 逻辑步
        accumulatorNs -= LOGIC_DT_NS
    }

    currentAlpha = accumulatorNs / LOGIC_DT_NS            // 插值因子 (0~1)
    delay(waitMs)                                         // 空闲时让出 CPU
}
```

| 维度 | 旧 (timer-driven) | 新 (frame-driven + GameTimeClock) |
|------|-------------------|------------------------------------|
| 循环频率 | 固定 10Hz (delay 100ms) | 可变，最快每帧 |
| 旬推进 | 每循环 1 旬 | GameTimeClock 累积器按游戏时间推进：1x=2000ms/旬，2x=1000ms/旬 |
| 追赶卡顿 | 自适应降速×1.5（恶性降频） | accumulator clamp（自动限制） |
| 插值因子 | 无 | `currentAlpha` 供 UI 平滑渲染 |
| 空闲功耗 | 高（2ms微延迟+忙等） | 低（无事 delay 让出 CPU） |
| delay抖动 | 直接影响 tick 间隔 | deltaTime 补偿，不影响精度 |

---

## Settlement Architecture: Lazy Settlement Engine

结算系统从 v4.0.43 起从 **四轨制（实时轨/批量轨/月事件/年事件）** 重构为 **惰性结算引擎（Lazy Settlement Engine）**，对标 Supercell Clash of Clans 的时间戳差分模式 + VoidForge Checkpoint 快照法。

```
tickInternal():
  Level 0 — 时间推进 (每旬)         ← GameTimeClock 驱动
    └─ TimeSystem.onPhaseTick → 更新 gamePhase

  Level 1 — 每旬最小检查 (每旬)      ← RimWorld Rare Tick 模式
    ├─ HP/MP 恢复
    ├─ 自动装备/学习
    ├─ 修炼累积（速率×1旬）
    ├─ 自动丹药到期补服
    └─ 突破检测

  Level 2 — 惰性生产结算 (UI打开时)  ← Supercell 时间戳模式
    ├─ 灵矿场: rate × (currentMonth - lastSettledMonth)
    ├─ 炼丹/锻造: 动态重算 duration → 完成检查
    └─ 灵田/灵植: 动态重算 growTime → 成熟检查

  Level 3 — 月变事件 (月变时)       ← 定时事件模式
    ├─ 外交/盗窃/执法/任务/叛逃
    ├─ 月度系统事件 (Alchemy/Forge/HerbGarden/Planting)
    └─ 伴侣配对 + 忠诚度衰减

  Level 4 — 年变事件 (年变时)
    └─ 老化/招募/盟约
```

### 核心原则

- **时间戳懒惰计算** — 不跑后台循环，仅存 `lastSettledTime`，按需计算：`产出 = rate × (currentTime - lastSettledTime)`
- **Checkpoint 快照法** — 修炼/炼丹/锻造在速率变化因子（政策/长老/装备/丹药）改变时，通过 `checkpointAllProduction()` 重算有效 duration 和 completionMonth，保留已完成的进度比例
- **修炼 VoidForge 模式** — `cultivationCheckpoints` + `cultivationCheckpointGameMonths` 双字段存储检查点，`getEffectiveCultivation(checkpoint + rate × delta)` 实时投影
- **生产系统动态 duration** — 每月完成检查时用当前政策/长老状态重算有效 duration（`baseDuration` 存储配方基础值，加成每月算），政策切换立即生效
- **无焦点域** — FocusDomain + InterfaceDomainMap 已移除，UI 不再驱动系统 tick
- **无 SettlementCoordinator** — 指纹检测、批量轨调度、年结编排全部移除
- **每旬 5 项最小检查** — 对标 RimWorld Rare Tick：HP/MP 恢复、自动装备/学习、修炼累积、丹药、突破

---

## Threading Architecture: Two Game Threads

惰性结算引擎移除了并行计算基础设施，不再需要 ParallelDispatcher。简化后的线程模型：

```
GameEngine-Thread(单线程,MAX)       游戏循环 + stateStore 写入口
BackgroundDispatcher(2线程,MIN+1)   后台 Job / 存档 IO
Watchdog(单线程,NORM)              监控 GameThread 卡死
Compose UI Thread(Main)            Android 主线程
```

| 调度器 | 线程数 | 优先级 | 用途 |
|--------|--------|--------|------|
| `GameDispatcher` (GameEngine-Thread) | 1 | MAX (-19) | 游戏循环 + stateStore 写入口 |
| `backgroundDispatcher` | 2 | MIN+1 | 后台 Job/存档 IO |
| `Watchdog` | 1 | NORM | 监控卡死 |

### 关键设计决策

- **无并行结算** — `ParallelExecutionContext`、`CultivationBatchResult`、`ParallelPhaseResult` 已全部移除。所有结算在 GameEngine-Thread 上串行执行
- **`stateStore.update` ReentrantLock** — 唯一的写锁，所有状态变更在此事务内原子完成。挂起时不会释放锁（与 `Mutex` 不同），消除协程交错导致的并发崩溃
- **引擎核心非挂起化** — `stateStore.update` 闭包内调用的核心路径（DiscipleService/DiscipleFacade 等）为非 `suspend`。IO/网络/存档路径（SavePipeline/MailService/Room DAO）保留 `suspend`——它们不在 `stateStore.update` 内调用，无死锁风险
- **`_discipleTables` 进入 deepCopy** — 每次 `stateStore.update {}` 在副本上操作，退出时原子替换引用，保证协程挂起后其他 update 看到完整一致的状态
- **生产系统 Checkpoint** — 政策/长老变化时通过 `fun checkpointAllProduction()` 在 GameEngine-Thread 上重算所有活跃槽位的 `duration` 和 `completionMonth`

---

## GameSystem 生命周期

惰性结算引擎使用简化后的 GameSystem 接口：

```kotlin
interface GameSystem {
    fun onMonthlyEvent(state: MutableGameState)  // 月变事件（非挂起）
    fun onYearlyEvent(state: MutableGameState)   // 年变事件（非挂起）
}
```

`onMonthlyEvent`/`onYearlyEvent` 均非挂起（全链路同步化），在 `stateStore.update {}` 事务内调用。异步操作（网络/DB I/O）使用 `runBlocking` 在事务外执行。不再有 `onPhaseTick`（逐旬回调）、`computePhaseTick`（并行计算）、`supportsParallelTick`。

---

## Formula Architecture: Zone Multiplier System

所有数值计算遵循**"乘区内加算、乘区间乘算"**的乘区法设计：

```
最终值 = 基础值 × Π(1 + Σ(各乘区内部加成))
```

### 已统一为乘区法的系统

| 系统 | 乘区结构 | 所在文件 |
|------|---------|---------|
| 修炼速度 | `CultivationSpeedZones`（5乘区：资质/资源/社交/状态/临时） | `DiscipleStatCalculator.kt` |
| 战斗伤害 | `DamageZones`（攻击Buff/防御穿透/暴伤/增伤/减伤） | `BattleCalculator.kt` |
| 突破概率 | `BreakthroughZones`（长老指导/自身加成/状态惩罚） | `DiscipleStatCalculator.kt` |
| 灵矿产出 | `SpiritMineZones`（采矿技能/执事道德/政策） | `CultivationSettlement.kt` |
| 生产成功率 | `SuccessRateZones`（境界/天赋/政策/长老） | `FormulaService.kt` |
| 生产速度 | `DurationZones`（技能/政策/长老） | `FormulaService.kt` |
| 灵植成熟 | `HerbGardenMaturityZones`（长老/光环/政策） | `ProductionProcessor.kt` |
| HP/MP恢复 | `RecoveryZones`（建筑/丹药/境界 预留） | `CultivationCore.kt` |

**核心工具：** `ZoneCalculator`（`core/engine/.../util/ZoneCalculator.kt`）提供 `calculate()` / `calculateProbability()` / `calculateAcceleratedTime()` 等公共方法。

### 新增计算规则

1. 每个乘区用一个 data class 表示，字段为各因子加算和
2. 使用 `ZoneCalculator.calculate(base, zone1, zone2, ...)` 计算结果
3. 概率型（突破率）使用 `calculateProbability(baseProb, positiveSum, penaltySum)` 自动 clamp [0,1]
4. 时间型使用 `calculateAcceleratedTime(base, speedBonus1, speedBonus2, ...)`
5. 新增影响数值的 buff/效果时，先确定它属于哪个乘区，在该乘区内加算
6. 新增乘区时，参照 `CultivationSpeedZones` 模式：创建 data class → `buildZones()` → 公式引用 → 测试验证

---

## Lifecycle Architecture: BootPhase / RunState 双层状态机

游戏启动和运行时生命周期从 v4.0.48 起从**单向 GameLifecycle** 重构为 **BootPhase + RunState 双层设计**。

```
BootPhase（启动序列 — 单向，只推进一次）
  UNINITIALIZED ──→ DATA_READY ──→ SYSTEMS_READY ──→ MAP_READY ──→ BOOT_COMPLETE

RunState（运行时状态 — 可循环回退）
  IDLE ──→ PLAYING ⇄ RELOADING ──→ PLAYING
```

### 核心原则

- **BootPhase** 只向前、一次性，由 `BootSequenceController.boot()` 内部驱动。外部只读。
- **RunState** 在 PLAYING 和 RELOADING 之间循环（读档/重启时）。
- `gameLifecycle`（`@Deprecated`）由 `computeGameLifecycle(bootPhase, runState)` 组合派生，保持旧代码兼容。

### 关键变化

| 旧 API | 新 API | 说明 |
|--------|--------|------|
| `GameLifecycle` enum (5值) | `BootPhase`(5值) + `RunState`(4值) | 职责分离 |
| `transitionTo(ordinal+1)` | `advanceBootPhase()` | 同样严格校验 |
| `forceLifecycle(任意)` | `setReloading() → resetBootPhase() → boot()` | 统一入口 |
| `_isGameLoaded` 独立标志 | `runState == PLAYING` | 单一真相源 |

### 错误恢复

- `BootSequenceController.recoverWithPartialData()` 在 `boot()` 失败但 engine 有部分数据时尝试恢复
- 恢复成功则走正常 success 路径（不再返回 failure 误导用户）
- 恢复失败则 onError + return failure

### 已知状态

- ✅ 双层写入原子性 — 已修复（`LifecycleState` data class 单入口）
- ⏸️ 重入串行化硬屏障 — 低优先级，当前 CAS 软屏障工作正常
- ⏸️ LOADING 状态可达补充 — 低优先级，纯 UI 优化
- ⏸️ 取消时状态自动回滚 — 低优先级，极少触发

---

## 抗冻结架构：自适应忙等

忙等自适应化（v4.0.38, R3）：正常时纯 `delay()`，检测到异常时自动启用忙等，恢复后禁用。OEM 参数简化为 3 档。

---

## 帧预算监控

`FrameQuality` 枚举 (SMOOTH/ACCEPTABLE/JANKY/FREEZE)，连续 3 帧 jank 触发 `loadReductionRequested`。

---

## Key Source Directories

**Core:** `core/engine/`(game loop/services/systems), `core/engine/domain/`(per-domain services), `core/engine/system/`(ECS systems), `core/domain/`(data classes), `core/state/`(GameStateStore), `core/registry/`(static game data), `core/config/`(JSON config)

**Data:** `data/`(Room DB/serialization/compression), `data/facade/`(StorageFacade API), `data/engine/`(StorageEngine), `data/local/`(Room DB + 18 个领域 DAO 文件)

**UI:** `ui/game/`(screens/ViewModels/dialogs), `ui/game/tabs/`(tab content), `ui/game/map/`(world map/Canvas), `ui/components/`(shared components), `ui/theme/`

**UseCase:** `app/.../core/usecase/`(14 UseCase classes), `.../core/state/`(GameStateStoreImpl), `.../core/util/`(ObjectPool/CircularBuffer), `.../core/CrashHandler.kt`

**Infrastructure:** `app/.../di/`(Hilt modules), `.../network/`(Retrofit/OkHttp), `taptap/`(TapTap SDK wrappers)

---

## Architecture Docs

- [宗门地图渲染架构](map-rendering-architecture.md) — 三层按格实时绘制（地面/装饰/建筑分离），v4.0.42+
- [加载阶段后台任务架构](loading-architecture.md) — 7模块并行加载（UI预组合/弟子快照/存档校验/图集约/地图并行/字体/音频）
- [弟子分配门卫架构](disciple-assignment-architecture.md) — DiscipleAssignmentGate + 11槽位统一注册表，v4.0.58
- [架构债务清单](architecture-debt.md)
- [架构债务写守卫](architecture-debt-write-guard.md)

---

## 待完成架构优化（行业对标建议）

以下优化项基于 [行业对标分析](knowledge-base.md#行业对标分析报告)（来源包括 UE/Supercell/RimWorld/MineColonies 等）。

### 1️⃣ 存档系统：双缓冲回退机制
n**完成内容：** `SaveFileManager` 提供 write-tmp → rename 原子写入 + CRC32C 完整性校验 + `.sav`/`.bak` 双文件回退。`StorageEngine.save()` 接入保存前校验和自动重试，`load()` 接入 CRC32C 校验 + 自动 `.bak` 恢复。`FunctionalWAL` 接入主保存路径。自动存档跳过备份

**对标：** 移动端增量存档行业标准（双缓冲/主+备份模式）
**现状：** 依赖 Room WAL 模式的事务原子性，无独立 .bak 回退
**建议：** 在 `StorageEngine.save()` 中增加 `write-tmp → rename → keep .bak` 流程，确保崩溃恢复

### 2️⃣ 角色状态系统：纯推导式迁移（长期）

**对标：** RimWorld（状态从当前执行任务推导，不手动设置）、MineColonies（三层状态机推导）
**现状：** 显式式 + 推导修正混合模式（`markDiscipleAssigned` 直接写 + `syncAllDiscipleStatuses` 修正）
**建议：** 逐步废除 `markDiscipleAssigned` 直接写入，使 `syncAllDiscipleStatuses` 成为唯一状态真相源。新增状态时只需更新推导函数，无需同时修改两处代码。

### 3️⃣ 自动分配管线：全量单事务写入

**对标：** Supercell（action 原子提交）、UE（Game Thread 快照一次性完成）
**现状：** `processAutoAssign` 中炼丹/锻造仍通过 `productionSlotRepository.batchUpdate`（Room IO）写入，与 `stateStore.update` 不在同一事务
**建议：** 将生产槽位数据迁入 `GameStateStore` 的 `MutableGameState` 体系，使所有自动分配在同一事务内完成

### 4️⃣ 渲染管线：游戏状态变更即时同步到渲染线程

**对标：** Unreal Engine（`ENQUEUE_RENDER_COMMAND` 显式推送 + Scene Proxy 并行数据结构）、Unity（命令队列保证送达）、脏标记模式（Dirty Flag，确定性状态变更通知）

**现状：** 建筑放置/移动/拆除的数据从 `placedBuildings StateFlow` 到渲染线程 `currentFrame` 的传播路径完全依赖 Compose 反应式管线：

```
placedBuildings → collectAsStateWithLifecycle → derivedStateOf → remember(key.equals)
→ AndroidView.update → 帧率门控 → updateRenderState → currentFrame
```

**三个不可靠环节串联：**
1. `remember` key 的 `equals()` 比较在某些 Compose 快照时机下不可靠
2. 帧率门控（16ms/33ms 间隔限制）可能阻止 RenderFrame 推送
3. 渲染数据流依赖 Compose 重组时机，无独立送达保证

**症状：** 建造建筑后"建筑1"有概率消失，拖动视角后恢复。原因：数据未送达 `currentFrame`，渲染线程读到的块缓存不包含新建筑。

**建议：** 增加一条不依赖 Compose 反应式管线的直达推送路径（`pushBuildingData` + `LaunchedEffect`，类似 UE 的 `ENQUEUE_RENDER_COMMAND`），确保建筑放置后数据直达 `currentFrame`，消除 Compose 时机竞争导致的渲染不一致。

**优先级：** 🟡 中（影响视觉体验但数据不丢失，拖动可恢复）

### 5️⃣ 月度事件管线：全量单事务提交
n**完成内容：** `processMonthlyEvents` 中 10/13 个子服务合并为单次 `stateStore.update` 原子执行（原 13 次降为 4 次 StateFlow 发射）。`processCompletedMissionsLazy` 改为两阶段模式（事务外计算 + 事务内写入），消除 CancellationException 奖励丢失风险。执法/偷窃系统内部方法全部 MutableGameState 化，消除读-写窗口

**对标：** Supercell（单个 game tick 内的所有状态变更原子提交）、RimWorld（Long Tick 在单个锁内完成全量结算）

**现状：** `GameEngineCore.processMonthYearChange()` → `CultivationEventProcessor.processMonthlyEvents()` 内部调用约 13 个子服务（AI操作、巡查过期、执法、偷窃、任务刷新、灵矿结算、修炼结算等），每个子服务独立调用 `stateStore.update {}`，产生 10+ 次独立事务。中间状态通过 `StateFlow` 暴露给 UI 层，可能导致 UI 读到部分已更新/部分未更新的不一致状态。

```kotlin
// 当前：13+ 次独立事务
stateStore.update { /* 政策成本 */ }         // 事务 1
processAISectOperations()                      // 事务 2 (内部可能多次)
checkGameOverCondition()                       // 事务 3
processScoutInfoExpiryLazy()                   // 事务 4
stateStore.update { processRemainingTargets() } // 事务 5
processTheftIfNeeded()                         // 事务 6+
processLawEnforcementMonthly()                 // 事务 7+
processMissionRefreshIfDue()                   // 事务 8+
processCompletedMissionsLazy()                 // 事务 9+
processSpiritMineProductionMonthly()           // 事务 10
processMonthlyCultivationAndAuto()             // 事务 11
// ...
```

**重构方案：** 将 `processMonthlyEvents` 内部所有子服务逐步重构为接受 `MutableGameState` 参数、不自建 `stateStore.update` 的纯函数模式（pure transformation on MutableGameState）。最终让月度事件管线在单次 `stateStore.update {}` 内完成全量结算。

```kotlin
// 目标：1 次事务
stateStore.update {
    processPolicyCosts(this)              // 已在此事务内
    processAISectOperations(this, year, month)
    checkGameOverCondition(this)
    processTheft(this)
    processLawEnforcement(this)
    processSpiritMineProduction(this)
    processMonthlyCultivation(this)
    // ... 所有月度事件
}
```

**影响范围：** 约 10-15 个服务类需要增加 `MutableGameState` 重载方法，原 `stateStore.update` 调用迁移到调用方。完成后需全量回归月度事件。

**优先级：** 🟡 中（当前单线程引擎架构降低了实际风险，但限制未来多线程扩展）

---

> 行业对标参考来源：
> - [Unreal Engine Threaded Rendering](https://dev.epicgames.com/documentation/unreal-engine/threaded-rendering)
> - [UE Low-Latency Frame Syncing](https://dev.epicgames.com/documentation/unreal-engine/low-latency-frame-syncing-in-unreal-engine)
> - [Game Programming Patterns - Dirty Flag](https://github.com/claudiouzelac/game-programming-patterns/blob/34da9e749d44695a12f1d423fa40391c1173ef65/book/dirty-flag.markdown)
> - [Activision COD Controller-to-Display Latency Research](https://www.activision.com/cdn/research/Hogge_Akimitsu_Controller_to_display.pdf)
> - [GDNet Threading Architecture Discussion](https://gamedev.net/forums/topic/688552-new-api-rendering-architecture/)

**对标：** Supercell（action 原子提交）、UE（Game Thread 快照一次性完成）
**现状：** `processAutoAssign` 中炼丹/锻造仍通过 `productionSlotRepository.batchUpdate`（Room IO）写入，与 `stateStore.update` 不在同一事务
**建议：** 将生产槽位数据迁入 `GameStateStore` 的 `MutableGameState` 体系，使所有自动分配在同一事务内完成

### 7️⃣ 偷盗系统：事务内守卫查询走 StateFlow 而非事务内状态

**场景：** `LawEnforcementProcessor.tryStealthDetection()` 从事务内路径（6 处道德变更钩子）调用时，守卫数据通过 `stateStore.disciples.value` 读取，而非当前事务 `MutableGameState` 的 `discipleTables`。

**当前影响：** 无。所有 6 处钩子只修改盗贼的道德值，不涉及守卫数据，读到的 StateFlow 快照与事务内状态一致。

**触发条件：** 仅在同一事务内同时修改守卫属性 + 触发盗贼偷盗时才会读到旧守卫数据。当前代码路径不存在此场景。

**建议修复方案：** 给 `tryStealthDetection` 增加 `state: MutableGameState?` 可选参数，事务内路径传入 `state` 并从 `state.discipleTables` 读取守卫。

**优先级：** 🔴 低（当前无触发路径，纯防御性记录）
