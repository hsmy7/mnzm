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
- [存档验证规则引擎](#存档验证规则引擎-savevalidator-rule-engine)

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
- **`EngineContextDispatcher` 接口** — 提取 `withEngineContext` 为接口（`core/engine/EngineContextDispatcher.kt`），`GameEngineCore` 实现，`GameEngine.engineContextDispatcher` 注入。测试用 `FakeEngineContextDispatcher` 绕过 Mockito suspend 泛型限制。所有直接调 `stateStore.update{}` 的 `suspend` 引擎方法自动派发到引擎线程

### 看门狗统一判据（2026-08-04 起）

历史教训（27 次"游戏时间停止"修复）：三层看门狗（引擎内 Watchdog / 主线程 HealthCheck / Alarm 兜底）此前全部只判 `tickCount` 停滞且全部豁免 `isPaused`——`isPaused` 卡死（秘境锁残留）与 `speed=0` 假运行两类冻结形态完全失明。现升级为**游戏时间推进监控**：

- **`GameTimeProgressMonitor`**（`core/engine/.../monitor/`，纯 JVM 纯函数组件）— 快照三元组 `tickCount + totalPhases + accumulatedGameMs` + flags（暂停/保存/加载/秘境锁/租约），输出 sealed `StallVerdict`：`Healthy` / `LoopStalled`（循环无活动）/ `FakeRunDetected`（tick 在跑但时间不动）/ `PausedByOwner`（用户暂停或秘境租约有效，豁免）/ `StalePauseDetected`（秘境锁残留需自愈）
- **统一出口** — 引擎循环每迭代采样 `sampleProgressSnapshot()`（含暂停分支），三层看门狗统一消费 `GameEngineCore.progressVerdict()`；自愈动作收敛到 `handleWatchdogVerdict()`（换新线程重启 / speed=0 恢复 1x / 清残留锁）
- **秘境暂停租约** — UI 探索界面每 15s `renewSecretRealmPauseLease()` 续约，中断 45s 判锁残留自动自愈（消除 Activity 重建丢失 `exitExploration` 的永久冻结路径）
- **OEM 线程挂起恢复** — 所有恢复路径统一走 `emergencyRestartGameLoop()`（`recreateGameDispatcher()` 换全新线程）+ 60s 限频（原 `restartGameLoopInternal` 在同一被挂线程重启无效，已删除）
- **暂停来源区分** — `resumeFromBackground` 按 `wasUserPausedBeforeBackground || secretRealmPauseLock` 补回暂停（后台往返不清掉用户暂停，也保持秘境 S4 语义）

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

---

## 抗冻结架构：自适应忙等

忙等自适应化（v4.0.38, R3）：正常时纯 `delay()`，检测到异常时自动启用忙等，恢复后禁用。OEM 参数简化为 3 档。

---

## 帧预算监控

`FrameQuality` 枚举 (SMOOTH/ACCEPTABLE/JANKY/FREEZE)，连续 3 帧 jank 触发 `loadReductionRequested`。

---

## Key Source Directories

**Core:** `core/engine/`(game loop/services/systems), `core/engine/domain/`(per-domain services), `core/engine/system/`(ECS systems), `core/domain/`(data classes), `core/state/`(GameStateStore), `core/registry/`(static game data), `core/config/`(JSON config)

**Data:** `data/`(Room DB/serialization/compression), `data/facade/`(StorageFacade API), `data/engine/`(StorageEngine), `data/local/`(Room DB + 18 个领域 DAO 文件), `data/integrity/`(SaveValidator + 规则引擎), `data/integrity/rules/`(验证规则)

**UI:** `ui/game/`(screens/ViewModels/dialogs), `ui/game/tabs/`(tab content), `ui/game/map/`(world map/Canvas), `ui/components/`(shared components), `ui/theme/`

**UseCase:** `app/.../core/usecase/`(14 UseCase classes), `.../core/state/`(GameStateStoreImpl), `.../core/util/`(ObjectPool/CircularBuffer), `.../core/CrashHandler.kt`

**Infrastructure:** `app/.../di/`(Hilt modules), `.../network/`(Retrofit/OkHttp), `taptap/`(TapTap SDK wrappers)

---

## 状态层快照隔离：列级 Copy-on-Write（v4.0.82+）

`DiscipleTables.deepCopy` 从"每次 update 全量深拷贝约 100 张组件表"重构为**列级 COW 快照隔离**：

- **机制**：`ComponentTable.store` 存储引用化（`adopt` 共享源存储 O(1)），事务缓冲**首次写入某列**时 `ensureOwned` 私有化（Int/Double 平铺数组整体 copyOf / SparseArray clone，O(capacity)），未触及列共享引用。旧快照（UI 持有）引用旧存储，事务永不原地修改源存储，天然隔离。
- **13 张 Mutable 列**（List/Set/Map：manualIds/lifeEvents/storageBagItems 等）走 `adoptDeep` **急切深拷贝**（防值对象原地修改泄漏），与旧 copyTo 语义逐字一致。
- **脏判定**：`GameStateStoreImpl.update` 事务提交后以 `dirtyTracker.isDirty` 判定"本次事务是否真的改了弟子数据"——纯 UI 事务（无弟子数据变更）不再触发全量 assembleAll（旧逻辑恒真触发）。`lastAssembledMutationVersion` 已删除。
- **锁外组装串行化**：增量 assembleAllIncremental 在专用单线程调度器执行（并发交错会互相覆盖丢弟子——burst 更新实测丢 2/50）。
- **兜底开关**：`DiscipleTables.forceFullCopy = true` 走旧逐元素全量复制路径（仅回归调试用）。

**性能基准**：100 弟子 × 1000 次 deepCopy + 写 3 列 = 122μs/次（重构前每次约 10,000 次 SparseArray put + 回调 + 锁）。

### 每旬热点削减（v4.0.82+）

- `CultivationRateCalculator.calculateCultivationPerPhaseById` **列直读速率**（无 Disciple 组装），与对象式入口数学等价（`CultivationRateEquivalenceTest` 30+ fixtures 1e-9 守卫）。
- **每旬 checkpointDisciple 移除**：checkpoint 只在速率变化点（政策/长老/丹药/突破）更新——政策切换已补 `checkpointAllDisciples`（`SectPolicyToggleUseCase` 三个修炼政策）。`getEffectiveCultivation` 投影语义保持。
- **每旬共享映射**：`checkBreakthroughsAndPills` 循环顶部一次性构建 equipmentMap/manualMap（O(D×N)→O(D+N)）；`manualProficiencies` 不再每弟子重建全量 outer map（O(D×P)→O(P)）。

### 聚合链合并（v4.0.82+）

`discipleAggregates`（sample 200）+ `sectCombatPower`（sample 300）两条独立全量扫描链合并为单一 `DerivedAggregation` 派生链（sample 100 + 专用单线程调度器 + 指纹缓存）。语义保持：aggregates 覆盖全部弟子（含死亡），combatPower 仅累计存活。纯 UI 事务不触发重扫。

### 待完成项（预存缺陷，2026-08-01 对抗性审查发现）

| 待办 | 现状 | 说明 |
|------|------|------|
| GameViewModelTest 18 个失败 | ✅ 已修复 | **原诊断有误**：失败根因不是 mockkStatic/Kotlin 2.2 兼容（测试 XML 证据：18 个失败全为异步路径、21 个通过全为同步路径），而是 relaxed mock 上 `launchOnEngine` 返回 mock Job、lambda 永不执行。2026-08-01 修复：捕获 lambda 到 engineBlocks 列表 + 测试内显式执行 + IoDispatcher 注入 TestDispatcher + 建筑注册表/宗门等级 stub。39/39 通过 |
| 全量测试必须 `--max-workers=1` 串行 | ✅ 已修复 | CI 全部 gradle 命令加 `--max-workers=1` + `-Pkotlin.incremental=false`；各模块显式 `maxParallelForks = 1`（共享静态状态跨类污染）。本地保留并行 |
| Mutable 列值对象共享（F4） | ✅ 已修复 | 13 张 Mutable 列改为 O(1) 浅共享（全库审计无原地修改）；`mutableValueGuardEnabled`（Debug/CI 开）unmodifiable 包装——未来原地修改立即抛异常。隔离测试 5 项覆盖 |
| 半幽灵防御不一致（F3） | ✅ 已修复 | assembleAll/assembleAllIncremental/deepCopy 统一三表判据 `isCompleteId`（isAlive+names+realms）；空名防御保持 assembleAll 独有（有意差异，有注释）；`DiscipleTablesGhostDefenseTest` 固化 4 类幽灵行为与 `deepCopy().assembleAll()==assembleAll()` 不变量 |
| CI 从未跑过 testReleaseUnitTest 全量 | ✅ 已就绪 | CI 全量测试为硬性门槛（`.github/workflows/ci.yml` L42 `testReleaseUnitTest`，已移除 `\|\| true`）+ 全部命令 `--max-workers=1`；`gradle.properties` 的 Windows 硬编码路径已移除（此前 ubuntu CI 守护进程启动即失败） |

### 待完成项（2026-08-01 第二轮对抗性审查遗留，LOW 级）

| 待办 | 现状 | 说明 |
|------|------|------|
| `_ids` 注释与实现不符 | ✅ 已修复 | 注释如实化：`mutableListOf` + 写点 `synchronized(_ids)` 互斥 + 读侧暴露可变引用约定；删除 CopyOnWriteArrayList/读写锁/`[idsLock]` 悬空引用 |
| `MAX_CATCHUP_MS` 死常量 | ✅ 已修复 | 常量与 `coerceAtMost` 裁剪已删除（行为等价：无论裁剪与否 `MAX_PHASES_PER_TICK` 按速度缩放的上限必然截断）；追补上限统一由 `MAX_PHASES_PER_TICK` 承担 |
| XianxiaApplication 后台执行器不 shutdown | ✅ 已修复 | 执行器提升为类字段，`onTerminate` 中幂等 shutdown（null 检查天然防重入） |
| Bugly mapping 上传任务恒空 | ✅ 已修复 | 改读 `apiProperties`（与 BuildConfig 同源）；`BUGLY_APP_ID/KEY` 缺失时明确 `logger.warn` 并跳过上传，不再静默用空 ID 请求 |
| 时序依赖测试抖动风险 | ✅ 已修复 | 新建共享 `TestPolling.awaitCondition` 轮询目标状态（5s 超时 + 20ms 间隔 + 失败带实际状态）；3 个测试文件 12 处固定等待全部替换 |
| generateFootprintHeader 正则未锚定 | ✅ 已修复 | 行级锚定 `FOOTPRINT_BY_NAME_INDEX` 声明块（干扰行验证不被捕获）；新增 FOOTPRINT 条目数 == BUILDING_NAMES 条目数守卫，失配即抛 GradleException |
| SaveDataReconcilerTest "缺字段"测试名不符 | ✅ 已修复 | 原测试重命名为"显式编码 false 触发重建"；新增真实缺失字段测试（TLV 剥离 field 55 构造旧二进制），缺失字段解码路径被真实覆盖 |
| onCleared 异步窗口期 | ✅ 已收尾 | 风险保持"已接受"（boot 序列 `isGameLoaded` 兜底）；catch 空块补 `Log.w` + `CancellationException` 重新抛出 |
| DeathEvent 无消费方 | ✅ 已确认安全 | `startListening` 空 collect 删除后，EventBus 自身 `startProcessing()` 是 Channel 唯一消费者（Channel 256 + trySend 丢弃不阻塞），背压无依赖 |

### 待完成项（仓库容量溢出专项，2026-08-01 对抗性审查发现，已由"容量不足提示框 + 溢出转邮件"方案处理）

| # | 待办 | 现状 | 说明 |
|---|------|------|------|
| P1 | 兑换码容量不足无提示，UI 显示"兑换成功"但物品未入账 | ✅ 已修复 | `RedeemResult` 加 `capacityInsufficient` 字段，失败时路由统一容量提示框 |
| P2 | 宗门等级领取静默关闭对话框，无容量反馈 | ✅ 已修复 | `SectLevelClaimResult` 新增 `CapacityInsufficient` 分支，路由统一提示框 |
| P3 | 引导任务奖励无 UI 提示 | ✅ 已修复 | `claimGuideReward` 返回 sealed `GuideClaimResult`，GameViewModel 路由提示 |
| P4 | 天劫容量不足走全局"错误"标题 | ✅ 已修复 | 改走统一 `showCapacityWarning` 通道 |
| P5 | 储物袋开启静默丢弃溢出物品（袋已消耗） | ✅ 已修复 | openStorageBag 迁移 addXxx，溢出自动转邮件 + 通知 |
| P6 | 灵田自动收获静默丢弃（仅日志） | ✅ 已修复 | addHarvestedHerbsToState 迁移 addHerb，溢出自动转邮件 |
| P7 | AutoBuyService 预检通过后仍可 Partial 静默丢弃 | ✅ 已修复 | addToWarehouse 迁移 addXxx，溢出自动转邮件 |
| P8 | 商人购买灵石已扣物品丢失 | ✅ 已修复 | buyMerchantItem 迁移 addXxx；Partial 视为发放成功（溢出转邮件，灵石照扣） |
| P9 | 多处战斗奖励无 withTrackingSource（来源为 unknown） | ✅ 已修复 | 妖兽战/洞穴战/妖兽侵袭/巡视塔/任务路径补 source 包裹 |
| P10 | 洞府探索 grantManualReward 用 isSuccess 误判 Partial 为成功 | ✅ 已修复 | 改用穷尽 when（Partial 计溢出，统一机制接管） |
| P11 | warehouseFullEvent 只带 Unit 无法区分拒绝/转邮件 | ✅ 已修复 | 类型升级为 `MutableSharedFlow<String>` 携带文案 |

### 待完成项（2026-08-02 综合优化对抗性审查遗留，预存问题/设计决策）

本次综合优化（性能批量改造/状态管道重构/代码精简）经 3 个对抗性审查代理（边界狂魔/状态破坏者/数据篡改者）审查，**新增引入的 15 项问题已全部修复**（见提交说明）；以下为**预存问题或设计权衡**，需人工决策后处理：

| # | 待办 | 现状 | 说明 |
|---|------|------|------|
| T1 | 混合 0/非 0 sequenceId 回填破坏单调递增 | ⏸️ 记录 | 旧档 `[0,0,5]` 回填为 `[6,7,5]`——靠前 0 序号条目拿到比靠后非零条目更大的序号。当前唯一消费者是 LazyColumn key（无排序依赖），无即时后果；任何未来按 sequenceId 排序/取"最新"的消费方会读错顺序。修复方案：回填时对非 0 序号也做整体重编号（一次性 O(N)） |
| T2 | restart 与 load 无互斥 | ⏸️ 记录 | `restartGame` 不设 isLoading、不查 loadLock；`loadGame` 不查 `_isRestarting`/saveLock。重启期间读档可双 boot 竞态（`bootInProgress` CAS 使第二次失败 + loadFromSnapshot 覆写已重置的新世界）。修复需在两者之间加互斥标志（改动涉及 SaveLoadViewModel 全流程，预存设计缺口） |
| T3 | 组装任务与 load 原地清表并发 | ⏸️ 记录 | 组装任务 T0 通过 gen 检查后与 load 的 `clear()+insert()` 并发遍历同一 `_discipleTables`（可能产出半截列表瞬时写回）；load 自身任务 T1（FIFO 后置）兜底最终一致。gen 检查为单点入口设计，中间窗口无法完全消除；观察窗口内 UI 闪旧/错数据（丢弟子外观、陈尸闪现） |
| T4 | changedIdTracker MAX_SAFE_CAPACITY 守卫缺口 | ⏸️ 记录 | crafted 存档含 id ≥ 10_000_000 的弟子 + 同事务其他弟子有修改时，大 id 弟子被 `record(id)` 静默拒绝但 changedIds 非空 → 走增量路径 → 快照保留其陈旧数据。注释声称"全量兜底"仅在 changedIds 完全为空时成立。修复：容量拒绝时强制整体退化全量组装 |
| T5 | 双保存竞态（isSaving 标志跨协程覆写） | ✅ 部分修复 | `saveGame` 已加 isSaving 守卫（快速连点第二次被拒绝）；仍存在的窗口：首次保存进行中再次触发（守卫生效），修复后残余风险低 |
| T6 | RedeemCodeManager 服务端 config 校验 | ✅ 已修复 | minAge>maxAge 崩溃、quantity 负数吞码、spiritRootCount≤0 空灵根——已加 coerce 兜底；服务端侧建议同步校验（防御纵深） |
| P12 | 外交宗门交易扣款后 add 失败灵石已扣物品丢失 | ✅ 已修复 | 购买前容量预检拒绝购买 + Partial 溢出自动转邮件 |

---

## Architecture Docs

- [宗门地图渲染架构](map-rendering-architecture.md) — 三层按格实时绘制（地面/装饰/建筑分离），v4.0.42+
- [加载阶段后台任务架构](loading-architecture.md) — BootSequenceController.boot() 顺序 8 步（ResourcePreloader 内部 2+3 路 async 并行）
- [弟子分配门卫架构](disciple-assignment-architecture.md) — DiscipleAssignmentGate + 11槽位统一注册表，v4.0.58

---

## 存档验证规则引擎（SaveValidator Rule Engine）

`SaveValidator`（`data/integrity/SaveValidator.kt`）从单体 8 项硬编码检查重构为可扩展规则引擎（v4.0.67）。

### 架构

```
SaveValidator.validate(SaveData)
  │
  ├─ ensureRegistered() → SaveValidationRuleRegistry.registerDefaults()
  │
  ├─ RuleContext 预计算 (equipmentIds, buildingIds, removedDiscipleIds)
  │
  ├─ 遍历 SaveValidationRuleRegistry.all (按 order 排序)
  │   ├─ [order=1]  SectNameRule           sectName 非空
  │   ├─ [order=2]  GameDateRule           year/month 范围
  │   ├─ [order=3]  DiscipleAgePositiveRule age >= 0
  │   ├─ [order=4]  GamePhaseRangeRule     phase 范围 [0,2]
  │   ├─ [order=5]  CultivationCapRule     修为上限
  │   ├─ [order=6]  EquipmentRefRule       装备引用存在性
  │   ├─ [order=7]  AgeLifespanRule        年龄 vs 寿命
  │   ├─ [order=8]  BuildingRefRule        建筑引用存在性
  │   ├─ [order=9]  DuplicateDiscipleIdRule 重复弟子 ID
  │   ├─ [order=10] GhostDiscipleCleanupRule 幽灵弟子清理
  │   ├─ [order=11] GhostRefCleanupRule     幽灵引用清理
  │   ├─ [order=12] SpiritStoneNonNegativeRule 灵石非负
  │   ├─ [order=13] DiscipleRealmConsistencyRule realm/layer 合法性
  │   └─ [order=14] DiscipleDeadStatusRule   死亡装备清理
  │
  └─ 聚合所有 RuleOutcome → IntegrityResult
```

### 核心接口

| 组件 | 文件 | 说明 |
|------|------|------|
| `SaveValidationRule` | `rules/SaveValidationRule.kt` | 规则接口：`fun execute(data, context): RuleOutcome` |
| `RuleContext` | `rules/RuleContext.kt` | 预计算上下文（equipmentIds, buildingIds, removedDiscipleIds） |
| `RuleOutcome` | `rules/RuleOutcome.kt` | 结果 sealed interface：Passed / Skipped / Repaired / Corrupted |
| `SaveValidationRuleRegistry` | `rules/SaveValidationRuleRegistry.kt` | 规则注册表 object（CopyOnWriteArrayList + order 排序） |
| `SaveValidator` | `SaveValidator.kt` | Facade 入口，保留 `validate(SaveData): IntegrityResult` 签名不变 |

### 规则顺序依赖

- `order` 字段控制执行顺序，小→大执行
- 关键依赖链：GhostDiscipleCleanupRule(order=10) → 写入 `context.removedDiscipleIds` → GhostRefCleanupRule(order=11) 消费
- `computeMaxCultivation` 从 `SaveValidator` 移至 `CultivationCapRule.kt` 顶层函数，`SaveValidator` 保留委托桥接

### 注册机制

- `SaveValidationRuleRegistry.registerDefaults()` 注册全部内置规则（惰性初始化，首次 `validate()` 时调用）
- 测试中 `SaveValidationRuleRegistry.clear()` 后只注册目标规则，实现细粒度单规则测试
- 新规则只需：新建 Rule 文件 + 在 `registerDefaults()` 加一行

### 调用方兼容

`SaveValidator.validate()` 签名不变，`IntegrityResult` 密封接口不变。两个调用点（`StorageEngine.load()` 和 `save()`）无感知。修复结果现在正确写回数据库（`save()` 路径先前忽略 `Repaired` 结果）。

### 测试

20 个测试类覆盖全部规则，位于 `data/src/test/.../integrity/rules/`。每规则独立覆盖通过/修复/损坏三类路径。

## 待完成项（2026-08-04 代码质量优化方案登记）

> 以下为 2026-08-04 代码质量优化 + 冗余清理方案（P0-P7）执行后的待完成项登记。
> 已完成项见方案提交记录（P0-P2 完整、P3C 完整、P4B/4D 完成、P5 SettingsTab、P6 大部分小项）。
> 未完成项按优先级排序，完成标准以"≤60 行函数 / 构造依赖 ≤7"等方案量化指标为准。

### 函数级（超限未拆）

| 项 | 位置 | 现状 | 目标 |
|---|---|---|---|
| executeCombatantTurn 主函数 | `core/engine/.../domain/battle/BattleSystem.kt` | 130 行 | ≤60 行（补抽 selectCombatantSkill / checkCombatantKill） |
| attackSect | `core/engine/.../GameEngineBattleOps.kt` | 156 行 | ≤60 行（补抽 selectAttackTarget / execSectBattle / processSectCasualties / updateSectReputation） |
| encounter | `core/engine/.../domain/battle/EncounterBattleService.kt` | 66 行 | ≤60 行 |
| processBattleCasualties | `core/engine/.../domain/battle/CombatService.kt` | 122 行 | ≤60 行（阶段 2 update 块再拆） |

### 上帝对象（未拆）

| 项 | 位置 | 现状 | 目标 |
|---|---|---|---|
| P4A GameViewModel | `feature/game/.../GameViewModel.kt` | 20 构造依赖（Delegate 化已存在） | 按域拆 Delegate 使构造依赖下降 |
| P4C GameEngine | `core/engine/.../GameEngine.kt` | 33 构造依赖（Ops 架构已成型） | 新建 Exploration/Cultivation/Economy 3 个 Facade 归组 |
| P4D CultivationCore | `core/engine/.../service/CultivationCore.kt` | 15 构造依赖（门面委托已存在） | 拆 AutoPillProcessor / BreakthroughProcessor / CultivationCheckpointOps |
| P4D ExplorationService | `core/engine/.../domain/exploration/ExplorationService.kt` | 12 构造依赖 | 按死亡/队伍两域收尾 |
| P4D CaveExplorationProcessor | `core/engine/.../service/CaveExplorationProcessor.kt` | 12 构造依赖 | 纯工具（rngManager/thermalMonitor）改参数传入 |

### UI（未拆/未迁）

| 项 | 位置 | 现状 | 目标 |
|---|---|---|---|
| P5 OverlayDialogRoute | `feature/game/.../components/OverlayDialogRouter.kt` | 409 行分派表（30 分支） | when 分支按 DialogType 分组到独立文件 |
| P6 raw Dialog（4 处） | `feature/game/.../tabs/SettingsTab.kt` | 平台 Dialog + 手写守卫 | 迁移 UnifiedGameDialog（已评估：复杂嵌套 + 已含守卫，风险>收益——**执行前需先评估 UnifiedGameDialog 全屏嵌套兼容**） |
| P6 raw Dialog（1 处） | `feature/game/.../DiscipleDetailScreen.kt` | Material3 AlertDialog | 可选迁移（Material3 标准组件，守卫相对完整） |

### P6 已评估不做（记录在案）

- `core/ui` 5 处平台 Dialog：组件库基础设施（UnifiedGameDialog/StandardPromptDialog/SmallScreenDialog 本体），不能自我迁移
- `clearPendingNotification`：`_pendingNotificationFlow` 仍有 GameOverlayHost UI 消费者，通知系统迁移完成前保留
- `ui-layout-unification` 分支 4 个超限文件：分支已删除/合并，无合回风险（FileLengthRule 仍是通用门槛）
