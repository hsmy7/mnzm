# CLAUDE.md

## 用户公约（产品经理思维）

本用户不懂技术，需求描述未必清晰、未必使用专业术语。

### AI 行为规范
1. **先确需再执行** — 收到需求后先用业务语言复述确认理解，再自行翻译为技术方案；不确定时立即提问，不猜测需求，不让用户解释技术细节
2. **提问要精准** — 简洁、直接、给出选项，不要让用户解释技术细节
3. **因果链确凿** — 定位问题时，必须从症状追溯到根因，每一步因果关系都要能说清楚。禁止仅凭相关性就下结论，必须有直接证据链
4. **举一反三排查** — 定位到问题后、动手修复前，先搜索代码库中是否存在同类模式的其他问题，一并纳入修复方案，再统一实施
5. **默认使用中文** — 所有回复、注释、commit message、文档均使用中文，除非涉及代码标识符或技术术语无合适翻译

---

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build / Test / Lint

All commands run from the `android/` directory using the Gradle wrapper:

```bash
# Compile check (fast feedback — do this after every change)
cd android && ./gradlew.bat compileReleaseKotlin

# Build release APK
cd android && ./gradlew.bat assembleRelease

# Build debug APK
cd android && ./gradlew.bat assembleDebug

# Run all unit tests (Robolectric + JUnit)
cd android && ./gradlew.bat test

# Run a single test class
cd android && ./gradlew.bat test --tests "com.xianxia.sect.core.engine.BattleSystemTest"

# Lint
cd android && ./gradlew.bat lintRelease

# Clean build (when KSP incremental cache breaks with NoSuchFileException *_Impl.java)
cd android && ./gradlew.bat clean
```

Tests live in `android/app/src/test/` and module-level `src/test/` dirs. They use JUnit 4, Mockito, Robolectric, and `kotlinx-coroutines-test`. Robolectric tests need `includeAndroidResources = true`.

```bash
# Code coverage (Kover)
cd android && ./gradlew.bat koverHtmlReport

# Static analysis
cd android && ./gradlew.bat detekt

# Full CI check (compile + test + detekt + coverage)
cd android && ./gradlew.bat compileReleaseKotlin testReleaseUnitTest detekt koverHtmlReport
```

## Tech Stack

- **Language**: Kotlin 2.0.21, JVM target 17
- **UI**: Jetpack Compose with Material3 (BOM 2025.02.00), no XML layouts
- **DI**: Hilt 2.56 (`@HiltAndroidApp`, `@HiltViewModel`, `@AndroidEntryPoint`)
- **Database**: Room 2.6.1 with KSP annotation processing; single shared DB file (`xianxia_sect.db`) for all save slots
- **Serialization**: Kotlinx Serialization (JSON + Protobuf + CBOR)
- **Storage**: MMKV (fast K-V), DataStore (preferences), LZ4/Zstd (compression)
- **Network**: Retrofit + OkHttp with Gson
- **Auth**: TapTap SDK (login, compliance, analytics)
- **Build**: AGP 8.8.0, Gradle with Aliyun mirrors for China

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

### Game Loop Architecture: Frame-Driven Accumulator Pattern

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

### Settlement Architecture: Lazy Settlement Engine + RimWorld 分类 Tick

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

**核心原则：**
- **时间戳懒惰计算** — 不跑后台循环，仅存 `lastSettledTime`，按需计算：`产出 = rate × (currentTime - lastSettledTime)`
- **Checkpoint 快照法** — 修炼/炼丹/锻造在速率变化因子（政策/长老/装备/丹药）改变时，通过 `checkpointAllProduction()` 重算有效 duration 和 completionMonth，保留已完成的进度比例
- **修炼 VoidForge 模式** — `cultivationCheckpoints` + `cultivationCheckpointGameMonths` 双字段存储检查点，`getEffectiveCultivation(checkpoint + rate × delta)` 实时投影
- **生产系统动态 duration** — 每月完成检查时用当前政策/长老状态重算有效 duration（`baseDuration` 存储配方基础值，加成每月算），政策切换立即生效
- **无焦点域** — FocusDomain + InterfaceDomainMap 已移除，UI 不再驱动系统 tick
- **无 SettlementCoordinator** — 指纹检测、批量轨调度、年结编排全部移除
- **每旬 5 项最小检查** — 对标 RimWorld Rare Tick：HP/MP 恢复、自动装备/学习、修炼累积、丹药、突破

### Threading Architecture: Two Game Threads（双游戏线程 + Watchdog）

惰性结算引擎移除了并行计算基础设施，不再需要 ParallelDispatcher。简化后的线程模型：

```
GameEngine-Thread(单线程,MAX)       游戏循环 + stateStore 写入口
BackgroundDispatcher(2线程,MIN+1)   后台 Job / 存档 IO
Watchdog(单线程,NORM)              监控 GameThread 卡死
Compose UI Thread(Main)            Android 主线程
```

**关键设计决策：**

- **无并行结算** — `ParallelExecutionContext`、`CultivationBatchResult`、`ParallelPhaseResult` 已全部移除。所有结算在 GameEngine-Thread 上串行执行
- **`stateStore.update` ReentrantLock** — 唯一的写锁，所有状态变更在此事务内原子完成。挂起时不会释放锁（与 `Mutex` 不同），消除协程交错导致的并发崩溃。DAO/Repository/Service 全链路非挂起化，无 `runBlocking` 桥接
- **`_discipleTables` 进入 deepCopy** — 每次 `stateStore.update {}` 在副本上操作，退出时原子替换引用，保证协程挂起后其他 update 看到完整一致的状态
- **生产系统 Checkpoint** — 政策/长老变化时通过 `fun checkpointAllProduction()` 在 GameEngine-Thread 上重算所有活跃槽位的 `duration` 和 `completionMonth`

### GameSystem 生命周期

惰性结算引擎使用简化后的 GameSystem 接口：

```kotlin
interface GameSystem {
    fun onMonthlyEvent(state: MutableGameState)  // 月变事件（非挂起）
    fun onYearlyEvent(state: MutableGameState)   // 年变事件（非挂起）
}
```

`onMonthlyEvent`/`onYearlyEvent` 均非挂起（全链路同步化），在 `stateStore.update {}` 事务内调用。异步操作（网络/DB I/O）使用 `runBlocking` 在事务外执行。不再有 `onPhaseTick`（逐旬回调）、`computePhaseTick`（并行计算）、`supportsParallelTick`。

### Formula Architecture: Zone Multiplier System（乘区法）

所有数值计算遵循**"乘区内加算、乘区间乘算"**的乘区法设计：

```
最终值 = 基础值 × Π(1 + Σ(各乘区内部加成))
```

已统一为乘区法的系统：

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

**新增计算规则：**
1. 每个乘区用一个 data class 表示，字段为各因子加算和
2. 使用 `ZoneCalculator.calculate(base, zone1, zone2, ...)` 计算结果
3. 概率型（突破率）使用 `calculateProbability(baseProb, positiveSum, penaltySum)` 自动 clamp [0,1]
4. 时间型使用 `calculateAcceleratedTime(base, speedBonus1, speedBonus2, ...)`
5. 新增影响数值的 buff/效果时，先确定它属于哪个乘区，在该乘区内加算
6. 新增乘区时，参照 `CultivationSpeedZones` 模式：创建 data class → `buildZones()` → 公式引用 → 测试验证

### Lifecycle Architecture: BootPhase / RunState 双层状态机（v4.0.48）

游戏启动和运行时生命周期从 v4.0.48 起从**单向 GameLifecycle** 重构为 **BootPhase + RunState 双层设计**。

```
BootPhase（启动序列 — 单向，只推进一次）
  UNINITIALIZED ──→ DATA_READY ──→ SYSTEMS_READY ──→ MAP_READY ──→ BOOT_COMPLETE

RunState（运行时状态 — 可循环回退）
  IDLE ──→ PLAYING ⇄ RELOADING ──→ PLAYING
```

**核心原则：**
- **BootPhase** 只向前、一次性，由 `BootSequenceController.boot()` 内部驱动。外部只读。
- **RunState** 在 PLAYING 和 RELOADING 之间循环（读档/重启时）。
- `gameLifecycle`（`@Deprecated`）由 `computeGameLifecycle(bootPhase, runState)` 组合派生，保持旧代码兼容。

**关键变化：**
| 旧 API | 新 API | 说明 |
|--------|--------|------|
| `GameLifecycle` enum (5值) | `BootPhase`(5值) + `RunState`(4值) | 职责分离 |
| `transitionTo(ordinal+1)` | `advanceBootPhase()` | 同样严格校验 |
| `forceLifecycle(任意)` | `setReloading() → resetBootPhase() → boot()` | 统一入口 |
| `_isGameLoaded` 独立标志 | `runState == PLAYING` | 单一真相源 |

**错误恢复：**
- `BootSequenceController.recoverWithPartialData()` 在 `boot()` 失败但 engine 有部分数据时尝试恢复
- 恢复成功则走正常 success 路径（不再返回 failure 误导用户）
- 恢复失败则 onError + return failure

**剩余待优化项：**
1. **双层写入原子性** — `_bootPhase` 和 `_runState` 是两个独立 MutableStateFlow，`setPlaying()` 等操作分两步写入。可改为单 `data class LifecycleState(bootPhase, runState)` 一次性发射，消除中间状态窗口
2. **重入串行化硬屏障** — 当前 `bootInProgress AtomicBoolean` 是软屏障，依赖调用方捕获异常。可改为 `Mutex.withLock` 或 `Channel` 串行化请求
3. **LOAING 状态达路径补充** — 初次加载时 `runState` 全程 IDLE 直到最后跳到 PLAYING。可补充 `setLoading()` 调用使状态机完整
4. **取消时状态自动回滚** — 可在 `BootSequenceController` 入口记录 `initialBootPhase/initialRunState`，取消时自动回滚而非用 `cleanupAfterCancellation()` 手动清理

### Key Source Directories

**Core:** `core/engine/`(game loop/services/systems), `core/engine/domain/`(per-domain services), `core/engine/system/`(ECS systems), `core/domain/`(data classes), `core/state/`(GameStateStore), `core/registry/`(static game data), `core/config/`(JSON config)
**Data:** `data/`(Room DB/serialization/compression), `data/facade/`(StorageFacade API), `data/engine/`(StorageEngine), `data/local/`(Room DB + 18 个领域 DAO 文件)
**UI:** `ui/game/`(screens/ViewModels/dialogs), `ui/game/tabs/`(tab content), `ui/game/map/`(world map/Canvas), `ui/components/`(shared components), `ui/theme/`
**UseCase:** `app/.../core/usecase/`(14 UseCase classes), `.../core/state/`(GameStateStoreImpl), `.../core/util/`(ObjectPool/CircularBuffer), `.../core/CrashHandler.kt`
**Infrastructure:** `app/.../di/`(Hilt modules), `.../network/`(Retrofit/OkHttp), `taptap/`(TapTap SDK wrappers)

### Architecture Docs

- [宗门地图渲染架构](docs/map-rendering-architecture.md) — 三层按格实时绘制（地面/装饰/建筑分离），v4.0.42+
- [加载阶段后台任务架构](docs/loading-architecture.md) — 7模块并行加载（UI预组合/弟子快照/存档校验/图集约/地图并行/字体/音频）

### Key Classes

- **`GameEngineCore`** — 游戏循环控制器（惰性结算引擎），仅推进时间 + 每旬 5 项最小检查 + 月变/年变事件
- **`GameEngine`** — 业务逻辑 Facade，注入到 ViewModel，写入 GameStateStore
- **`GameStateStore`** — 单一 MutableStateFlow<UnifiedGameState>，各字段通过 .map{} 派生。写操作由 `ReentrantLock` 串行化（非 `Mutex`，挂起时不释放锁），`_discipleTables` 进入 `deepCopy()` 提供快照隔离。生命周期状态采用 **BootPhase/RunState 双层设计**（见下文）
- **`BootSequenceController`** — 启动序列控制器：统一编排新游戏/读档/重启的 BootPhase 推进、RunState 切换、资源预加载(回调)、游戏循环启停、地图生成、错误恢复。`boot()` 为统一入口
- **`GameViewModel`** — 主 ViewModel (Hilt)，通过 9 个 Delegate 拆分领域逻辑
- **`MainGameScreen`** — Tab 布局 (OVERVIEW/DISCIPLES/BUILDINGS/WAREHOUSE/SETTINGS)，无 NavHost
- **`GameData`** — Room @Entity，主键 (id, slot_id)
- **`CultivationService`** — 修炼 Checkpoint 快照法入口：`checkpointDisciple()` / `accumulateCultivationPerPhase()` / `checkpointAllProduction()`

#### 探索系统（v4.1+ 子系统架构）

探索系统从 `ExplorationService`（Facade）拆分为 6 个独立职责的子系统：

| 子系统 | 文件 | 职责 |
|--------|------|------|
| `WorldLevelManager` | `exploration/WorldLevelManager.kt` | 关卡惰性管理：刷新/过期清理/妖兽移动（纯函数，分区 RNG） |
| `BeastAttackDetector` | `exploration/BeastAttackDetector.kt` | 妖兽攻击检测（纯函数，返回预警列表） |
| `PatrolBattleSystem` | `exploration/PatrolBattleSystem.kt` | 巡视塔战斗（拆 4 步：组队→索敌→战斗→结算） |
| `LootCalculator` | `exploration/LootCalculator.kt` | 掠夺计算（纯函数 + 副作用分离，修复双重扣除） |
| `DiscipleDeathHandler` | `exploration/DiscipleDeathHandler.kt` | 死亡标记 + deathYears + 装备断言守卫 |
| `ExplorationTeamManager` | `exploration/ExplorationTeamManager.kt` | 探索队伍管理（单事务内完成，竞态安全） |
| `ExplorationService` | `domain/exploration/ExplorationService.kt` | Facade：月度事件编排 + 保留玩家主动操作接口 |

#### 确定性 RNG 系统（v4.1+）

所有随机操作使用分区 PRNG 确保存档/读档后随机序列一致：

| 组件 | 文件 | 说明 |
|------|------|------|
| `DeterministicRng` | `util/DeterministicRng.kt` | PCG-XSH-RR 算法，16 字节状态，可序列化 |
| `GameRngManager` | `util/GameRngManager.kt` | 4 分区管理器（BATTLE / BREAKTHROUGH / EXPLORATION / SYSTEM） |
| `RngPartition` | `util/RngPartition.kt` | 分区枚举 |

**规则：** 新增任何使用随机数的逻辑，必须通过 `GameRngManager.getRng(RngPartition.xxx)` 调用，禁止直接使用 `kotlin.random.Random`。保存时 `exportStates()` 写入 `GameData.rngStates`，加载时 `restoreStates()` 恢复。

### Component Table Architecture (v4.0.41) / IntPackedArray + Cultivation Checkpoint

Disciple entities are stored in `DiscipleTables` — ~90 narrow `ComponentTable`/`IntComponentTable`/`DoubleComponentTable` columns. 底层使用 `IntPackedArray`（dense IntArray + idToIndex）和 `DoublePackedArray`（零装箱），查询 O(1)，删除 O(1) swap-on-remove。所有 CRUD 通过 `buildCopyableRefs()` 声明式列表驱动，新增列只需在列表加一行。

**修炼 Checkpoint（v4.0.43）：** `cultivationCheckpoints: DoubleComponentTable` + `cultivationCheckpointGameMonths: IntComponentTable`。修炼值 = checkpoint + rate × (currentMonth - cpMonth) × 3。Checkpoint 在每旬累积时更新，在速率变化时通过 `checkpointDisciple()` 同步。

**EntityStore 增量更新：** 其他实体类型用 `EntityStore<T : HasId>`，MutableList 原地修改 + `freeze()` 快照 + `isDirty` 标记检测。GC 分配降低 80%+。

**EntityStore 注意事项：** `plus(item)` 必须通过 `EntityStore(newItems)` 构造新实例，不可 `EntityStore()` + `items_.addAll()`，否则 `frozenSnapshot` 未正确初始化。

**生产系统 Checkpoint（v4.0.43）：** `ProductionSlot.baseDuration` 存储配方基础持续时间。政策/长老变化时 `checkpointAllProduction()` 遍历所有活跃槽位，重算 duration 和 completionMonth，保留已完成进度比例。灵田/灵植使用 `calculateSpiritFieldMaturityBonus` 动态重算 effectiveGrowTime。

**热控与温度读取（v4.0.41）：** `ThermalReader` 接口定义三通道温度获取策略，`AndroidThermalReader` 实现：1) `PowerManager.getThermalHeadroom(10)` (API 30+) 主动预测；2) `PowerManager.currentThermalStatus` (API 29+) 被动状态；3) sysfs + BatteryManager 降级回退。`ThermalController` 消费 `ThermalReader` 温度数据驱动四档降级阶梯（GREEN/YELLOW/ORANGE/RED），联动渲染质量、目标帧率。

**两线程调度模型：**
| 调度器 | 线程数 | 优先级 | 用途 |
|--------|--------|--------|------|
| `GameDispatcher` (GameEngine-Thread) | 1 | MAX (-19) | 游戏循环 + stateStore 写入口 |
| `backgroundDispatcher` | 2 | MIN+1 | 后台 Job/存档 IO |
| `Watchdog` | 1 | NORM | 监控卡死 |

**Key rules:**
- **Disciple updates**: Write directly to `tables.loyalty[id] = 90` — O(1) via IntPackedArray (was O(log n) SparseArray)
- **Disciple reads**: `tables.names[id]`, `tables.realms[id]` — O(1) via IntPackedArray (was O(log n))
- **Disciple assembly**: `tables.assemble(id)` creates a full `Disciple` data class (~200 fields, 5 nested layers) — ONLY for UI/Serialization, NEVER in hot path
- **Hot path column reads**: `cultivation` 热路径用列直读替代 `assemble()`。父母加成仅读 `isAlive` + `spiritRootTypes` 两列，讲道加成仅读 `isAlive` + `realms` + `teachings` 三列。300 弟子时 `assemble` 调用从 1500+ 次/100ms 降至 300 次/100ms（降低 80%）
- **Non-Disciple lookup**: `entityStore.get(id)` — O(1) via HashMap index
- **Non-Disciple update**: `entityStore.update(id) { transform }` — O(n) indexOfFirst + O(1) HashMap, 零分配
- **EntityStore snapshot**: `entityStore.freeze()` before StateFlow emission — 仅在 dirty 时分配新 List
- **MutableGameState fields**: `discipleTables: DiscipleTables`, `equipmentStacks: EntityStore<EquipmentStack>`, `productionSlots: List<ProductionSlot>`, `spiritMineSlots: List<SpiritMineSlot>`
- **Cultivation Checkpoint**: `tables.cultivationCheckpoints[id]` + `tables.cultivationCheckpointGameMonths[id]` — 每旬 `accumulateCultivationPerPhase()` 更新，`getEffectiveCultivation()` 实时投影
- **灵矿场结算**: `spiritMineLastSettledMonth` 时间戳差分，`产出 = rate × (currentMonth - lastSettled)`
- **炼丹/锻造 Checkpoint**: `ProductionSlot.baseDuration` 存储配方基础值，`recalculateAllCompletionMonths()` 按当前政策/长老重算 duration + successRate
- **政策/长老变更触发**: `SectPolicyToggleUseCase` 和 `ElderManagementUseCase` 在变更后调用 `checkpointAllProduction()`

### 抗冻结架构：自适应忙等 (R3, v4.0.38)
忙等自适应化：正常时纯 `delay()`，检测到异常时自动启用忙等，恢复后禁用。OEM 参数简化为 3 档。

### 帧预算监控 (R17, v4.0.38)
`FrameQuality` 枚举 (SMOOTH/ACCEPTABLE/JANKY/FREEZE)，连续 3 帧 jank 触发 `loadReductionRequested`。

### Mail & Reward System

Mail reward claims use Saga compensation: `stateStore.update {}` 原子写入物品+claim记录，
若 `distributeAttachmentsInline` 抛出则 `mailRecords` 不写入，邮件保持未领取。

- **Stable IDs**: 内置邮件用 BuiltinMailConfig 确定性 ID，在线邮件用 `"online_${remoteMailId}"`
- **GameData 存储**: `mailRecords: List<MailClaimRecord>`（含 mailId/claimedAt/source），非邮件内容
- **初始化**: `mailService.resetAndInitSlot()` 在世界初始化后调用
- **清理**: `StorageEngine.delete()` 清理已删档位的 mails 表

### Navigation Pattern

No `NavHost` is used for the main game. `MainGameScreen` switches content via `MainTab` enum. Feature screens (Alchemy, Forge, HerbGarden, etc.) are dialogs opened via `DialogStateManager.openDialog(DialogType, params)`. The two actual Activity transitions are:

1. `MainActivity` → `GameActivity` (in-game)
2. `MainActivity` → `SaveSelectScreen` (save select)

### ViewModel Conventions

- ViewModels extend `BaseViewModel` which provides `showError()`, `showSuccess()`, `showInfo()`, and `withLoading()`.
- Each feature gets its own ViewModel (e.g., `AlchemyViewModel`, `ForgeViewModel`, `ProductionViewModel`, `DiscipleViewModel`).
- ViewModels read from `GameStateStore.unifiedState` via `collectAsState()` or direct `.value` reads for snapshots.
- Mutations go through `GameEngine` methods, never directly to `GameStateStore` from the UI layer.

### UseCase / Facade Pattern

Business logic is organized as **UseCase** classes (in `app/.../core/usecase/`) that wrap **Facade** interfaces
(in `core/engine/domain/`). Each UseCase follows:

- Single `operator fun invoke()` as the entry point (or named methods for multi-operation cases)
- `suspend` for async operations, `StateFlow` for reactive streams
- `Result<T>` or `DomainResult<T>` for error handling — never bare exceptions

```
ViewModel → UseCase → Facade (interface) → Service (impl) → GameStateStore
```

Large ViewModels (e.g., `GameViewModel`) can be split into **Delegate** classes (in `ui/game/delegate/`)
for domain groupings: `BuildingDelegate`, `DisciplineDelegate`, `BeastAttackDelegate`, etc.

## 编码规范 (Coding Standards)

> 以下所有规则标注严重度：🔴 严重（必须遵守，违反导致构建/审查失败）、🟡 重要（应遵守，违反需在审查中说明理由）、🟢 建议（推荐遵循，逐步推广）。

---

### 0. 代码质量铁律

**0.1 🔴 代码必须有测试覆盖** — 所有新增/修改的业务逻辑代码必须有对应的单元测试覆盖。无测试的代码视为未完成，不得合并。测试需覆盖：
- 正常路径（Happy Path）
- 边界条件（空值、空列表、极值）
- 异常路径（错误处理、失败恢复）

**0.2 🔴 代码必须是可长期维护的优质代码** — 编写代码时必须考虑可读性、可扩展性和可维护性，而非仅满足于功能实现。具体要求：
- 命名清晰、意图明确，不需要注释就能理解
- 函数短小聚焦，单一职责
- 避免过度耦合，依赖注入优于硬编码
- 不引入隐性技术债务

**0.3 🔴 禁止"当前能跑就行"心态** — 代码审核时如发现以下"应付式"迹象，直接打回：
- 只处理了正常路径，边界条件和异常情况未处理
- 日志缺失或信息不足以定位问题
- 硬编码、重复代码
- 违反项目编码规范且无合理说明
- 明知有更好的实现方式却选择了更快捷但更糟糕的方案

**0.4 🔴 禁止硬编码数字（魔法数字）** — 所有具有业务含义的数字必须定义为命名常量（`const val` 顶层属性或 `companion object` 中的 `val`），禁止在业务逻辑中直接使用原始数字字面量。允许例外：0、1、-1 等自解释的循环/索引/增量上下文、detekt 配置中声明的游戏数学常量。

```kotlin
// ❌ BAD — 魔法数字 0.8 的含义不明确
if (progress >= 0.8) onComplete()

// ✅ GOOD — 命名常量说明含义
private const val COMPLETION_THRESHOLD = 0.8
if (progress >= COMPLETION_THRESHOLD) onComplete()
```

---

### 1. Kotlin 语言规范

**1.1 🔴 禁止 `!!` 操作符** — 除非有编译时证明（如 `lateinit var` 在初始化后访问）。所有可为空的值通过 `?.`、`?:` 或 `checkNotNull()` 安全访问。

```kotlin
// ❌ BAD
val data = gameEngine.gameDataSnapshot!!
// ✅ GOOD
val data = gameEngine.gameDataSnapshot ?: return
```

**1.2 🟡 优先 `val`** — 所有属性默认 `val`。`var` 仅在不可变 copy-on-write 不可行时使用，需注释说明理由。

**1.3 🔴 领域结果用 sealed class** — 所有可能失败的操作返回 sealed class 结果类型。可预期的业务失败（找不到、校验失败）用 sealed class，不抛异常；异常仅用于程序错误和基础设施故障。禁止裸 `Boolean` 代表成功/失败。

```kotlin
// ❌ BAD — 调用方不知道为何失败
fun togglePolicy(): Boolean
// ✅ GOOD — 明确的结果语义
sealed interface ToggleResult {
    data object Success : ToggleResult
    data class Error(val message: String) : ToggleResult
}
```

**1.4 🔴 协程规范：**
- 禁止 `runBlocking`（仅测试可用 `runTest`）
- Dispatcher 通过 Hilt `@Dispatcher(IO)` 注入，禁止硬编码 `Dispatchers.IO`

**1.5 🟢 扩展函数放专用文件** — 对某类型的大量扩展函数放入 `{TypeName}Ext.kt`，不堆积在 ViewModel/Service 中。

---

### 2. 模块架构规范

**2.1 🔴 依赖方向不可反转** — `:core:domain` ← `:core:data` / `:core:engine` / `:core:ui` ← `:feature:game` ← `:app`。`:core:domain` 零 Android 依赖（仅 `javax.inject` + `kotlinx.coroutines` + `kotlinx.serialization` + `room-common` 注解）。

**2.2 🔴 模块内容边界：**

| 模块 | 只能包含 | 禁止包含 |
|------|---------|---------|
| `:core:domain` | 数据类、接口、sealed class、注解、StateFlow 定义、Registry 静态数据 | Room DAO、Android Context、ViewModel、Compose |
| `:core:engine` | GameEngine、Service、System、游戏循环 | Compose UI、ViewModel、Activity |
| `:core:data` | Room DB/DAO/Migration、序列化、加密、Repository 实现 | ViewModel、Compose、游戏逻辑 |
| `:core:ui` | 共享 Compose 组件、Theme、导航工具 | ViewModel、Room DAO、游戏逻辑 |
| `:feature:game` | ViewModel、Screen 级 Compose、对话框 | Room DAO、直接写 GameStateStore |

**2.3 🟡 `internal` 默认可见性** — 非模块公开 API 的类/函数一律 `internal`。

**2.4 🔴 禁止循环依赖** — 模块间必须形成 DAG，CI 中通过 Konsist 检查。

**2.5 🟢 新建模块需 ADR** — 新增 Gradle 模块需 `docs/adr/` 记录决策，且至少包含 3 个内聚领域类。

---

### 3. 文件与代码行规范

**3.1 🔴 单文件最大 2000 行**（生成代码如 Room `_Impl`、ProtoBuf 生成代码除外）。

**3.2 🔴 单行最大 80 字符** — import 语句、KDoc `@param`/`@return` 标签、URL 除外。

**3.3 🟡 单函数体最大 60 行** — 超限须拆分为私有辅助函数。

**3.4 🔴 最大构造参数：类 7 个，Composable 函数 6 个** — 超限须分组为配置数据类或拆分类。

```kotlin
// ❌ BAD — 22 个构造参数
class GameViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val gameEngineCore: GameEngineCore,
    // ... 20 more
)
// ✅ GOOD — 分组为 Facade
class GameViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val gameEngineCore: GameEngineCore,
    private val battleFacade: BattleFacade,
    private val buildingFacade: BuildingFacade,
    private val inventoryFacade: InventoryFacade,
    private val discipleFacade: DiscipleFacade,
    private val productionFacade: ProductionFacade
)
```

**3.5 🔴 单一职责** — 类名必须反映唯一职责。避免 "Manager"、"Handler"、"Utils" 等模糊后缀，除非确实承担协调/处理/工具职责。

**3.6 🔴 上帝对象重构阈值** — 超过 10 个构造依赖且超过 2000 行的类必须有重构计划。

---

### 4. ViewModel 规范

**4.1 🔴 必须继承 `BaseViewModel`** — 所有 ViewModel 继承 `com.xianxia.sect.ui.game.BaseViewModel`，确保统一的 `showError()`/`showSuccess()` 事件通道。


**4.3 🔴 只读 StateFlow 暴露状态** — 禁止公开 `MutableStateFlow`，所有状态通过 `StateFlow`（只读）暴露给 Compose。

```kotlin
// ❌ BAD
val productionSlots = MutableStateFlow<List<ProductionSlot>>(emptyList())
// ✅ GOOD
private val _productionSlots = MutableStateFlow<List<ProductionSlot>>(emptyList())
val productionSlots: StateFlow<List<ProductionSlot>> = _productionSlots.asStateFlow()
```

**4.4 🔴 禁止直接访问 `GameStateStore`** — ViewModel 和 UI 层所有状态变更通过 `GameEngine` 方法，不直接调用 `stateStore.update()` 或 `gameEngine.updateGameData {}`。数据流单向：UI → ViewModel → GameEngine → Service → GameStateStore。

**4.5 🟡 UserAction/ActionResult 模式** — ViewModel 公开方法使用 sealed `UserAction` 统一入口，便于错误处理和日志。

**4.6 🟡 ViewModel 与 Screen 一对一** — 一个 ViewModel 只驱动一个 Screen，避免一个 ViewModel 驱动多个无关 Screen。

---

### 5. 引擎服务规范

**5.1 🔴 通过快照访问状态** — Service 不直接订阅 `StateFlow`，通过 GameEngine 传入的参数或构造注入的 snapshot 访问状态。

**5.2 🟡 方法签名：`suspend` 或返回 Result** — 所有执行 I/O 或领域逻辑的服务方法必须为 `suspend` 函数，或返回 `Result<T>`/sealed class 类型。

**5.3 🟡 服务间禁止共享可变状态** — 通过 EventBus 事件或协调器对象通信，不使用共享的 `MutableStateFlow` 或 `ConcurrentHashMap`。

**5.4 🔴 错误必须传播** — Service 内部禁止静默吞掉异常。`log-and-continue` 仅允许在非关键后台操作中使用。

**5.5 🔴 `@GameService` 注解** — 所有游戏领域逻辑类必须标注 `@GameService(name = "...")`。

---

### 6. 状态管理规范

**6.1 🔴 `GameStateStore` 是唯一真相源** — 禁止在 ViewModel/Service 中缓存 `GameData` 或实体列表的本地副本。

**6.2 🟡 多实体变更必须用单次 `stateStore.update`** — 所有状态修改在 `stateStore.update {}` 事务内原子完成，禁止多次孤立的 `update` 调用。

```kotlin
// ❌ BAD — 多次孤立的 update 调用
stateStore.update { it.copy(spiritStones = it.spiritStones + 100) }
stateStore.update { it.copy(gameMonth = it.gameMonth + 1) }

// ✅ GOOD — 一次原子 update 事务
stateStore.update {
    gameData = gameData.copy(
        spiritStones = gameData.spiritStones + 100,
        gameMonth = gameData.gameMonth + 1
    )
}
```

**6.3 🟡 Flow 派生规则** — 高频率 StateFlow 派生必须使用 `distinctUntilChanged()` + `sample(50)` + `stateIn(scope, WhileSubscribed(5000), initial)`。

**6.4 🔴 新增可变化数据需同步更新指纹** — 批量轨指纹检测依赖 `CultivationRateFingerprint` 检测修炼速率变化。新增以下内容时，必须同步更新对应指纹的 `compute` 方法：
- `DiscipleTables` 新增列（影响修炼速率计算）→ `SettlementCoordinator.computeFingerprint` 的 `perDiscipleHash`
- `ElderSlots` 新增槽位类型 → 若在 data class 内部自动覆盖；若单独建表需手动加入
- `SectPolicies` 新增政策 → 若在 data class 内部自动覆盖；否则需手动加入 `productionPolicyHash`
- 新增生产系统（如灵兽养殖等）→ `ProductionRateFingerprint` 新增字段 + `compute` 方法
- 新增影响修炼速率的数据维度 → `CultivationRateFingerprint` 新增字段
- 新增丹药类型（pillType）或 PillEffects 字段 → `CultivationCore.processRealtimeAutoPills` 的字段写回列表 + `DisciplePillManager.classify` 的分类规则需同步更新。丹药指纹检测在该方法内通过 `storageBagItems.any { it.itemType == "pill" }` 实现，无需单独指纹数据类

指纹的 `compute` 方法统一在 `SettlementCoordinator.kt`（修炼指纹）中。指纹检测每 30s 用临时影子计算指纹并比对，变化时重建 SettlementCache。详见 [ADR: 统一批量结算模式](docs/adr/unified-batch-settlement.md)。

**6.5 🔴 新增/改动界面必须重新评估焦点域映射** — 焦点域采用纯视角驱动 + 域声明系统：**每个 UI 界面对应一个 FocusDomain 枚举值，域通过 `systemClasses` 反向声明激活时需实时 tick 的系统**。

新增界面（Tab / Dialog）或改动现有界面展示内容时，必须同步更新：

1. `FocusDomain.kt` — 新增枚举值（含 `systemClasses` 声明）或修改现有域的系统列表
2. `InterfaceDomainMap` — 添加 UI 名 → 域的 1:1 映射
3. `DomainMappingTest.kt` — 添加对应的测试用例

判定原则：**界面显示随时间变化的数据（进度条、倒计时、数量增减），就应映射到对应的 FocusDomain。仅静态信息（历史记录、配置面板）的界面不在此表。**

**6.6 🔴 精灵图必须统一注册并使用统一入口** — 详见 `rules/static-resources.md`。所有静态图片资源必须：

1. **无损 WebP 格式** — 使用 `scripts/convert-remaining-pngs-to-webp.mjs`（lossless: true, effort: 6）
2. **两模块文件放置** — WebP 放入 `feature/game/src/main/res/drawable-nodpi/` 和 `app/src/main/res/drawable-nodpi/`
3. **注册** — 在 `XianxiaApplication.kt` 调用 `SpriteResRegistry.register(SpriteCategory.XXX, mapOf("名称" to R.drawable.xxx))`
4. **显示** — 使用 `SpriteImage("名称")`、Canvas 中 `drawSprite(name, cache, ...)` 或 `painterResource(id = SpriteResRegistry.resolve("名称") ?: 0)`
5. ❌ 禁止直引 `painterResource(R.drawable.xxx)`（注册代码除外），禁止硬编码 `R.drawable` 列表
6. ❌ 禁止提交 PNG/JPG 游戏图片（唯一例外：`ic_launcher-playstore.png`）

---

### 7. 数据库规范

**7.1 🔴 任何 Entity 变更必须有 Migration** — 详见 `rules/database-migration.md`。每次变更：递增 `@Database(version)` + 编写 `MIGRATION_N_M` + 注册到 `build()`。**修改 `@Entity` 前必须先读 migration 规则**，最常见的存档损坏原因就是改字段没写 Migration。拿不准时保留旧字段+新字段（`@Ignore`），永远不要删列。

**7.2 🔴 禁止 `ALTER TABLE DROP COLUMN`** — SQLite 3.35.0 才支持。使用 `db.safeDropColumns()` 或保留旧列 + `@Ignore`。

**7.3 🟡 ProtoBuf 仅 `List`，禁止 `Set`/`Map`** — 需要去重语义在业务层 `.toSet()` 转换。忽略会导致序列化静默失败，**存档变空**。

**7.4 🔴 Migration 必须有测试** — 旧版本插入种子数据 → 运行迁移 → 验证数据完整性。

---

### 8. 错误处理规范

**8.1 🔴 `CancellationException` 必须重新抛出** — 任何 `catch (e: Exception)` 前必须有 `catch (e: CancellationException) { throw e }`。

**8.2 🔴 禁止空 catch 块** — 每个 `catch` 至少包含 `Log.w(TAG, "...", e)`。

**8.3 🟡 UI 错误统一走 `BaseViewModel.showError()`** — ViewModel 不直接处理错误展示。

**8.4 🟡 引擎错误记录上下文** — `Log.e(TAG, "操作名 failed: id=$id, ctx=$ctx", e)`，信息足够定位问题。

---

### 9. 测试规范

**9.1 🔴 引擎服务 80%+ 行覆盖率** — `:core:engine` 模块目标 80% 行覆盖（Kover/JaCoCo 检测）。

**9.2 🔴 Migration 必须有集成测试** — 每条 Migration 验证旧数据能完整迁移。

**9.3 🟡 测试命名：`方法名_状态_预期行为`** — Given-When-Then 模式。

```kotlin
// ✅ GOOD
@Test
fun `addEquipmentStack - empty name returns INVALID_NAME`() { ... }
```

**9.4 🟢 优先 Fake 而非 Mock** — 手写 Fake 实现优于 Mockito mock，可复用、可读、可调试。

---

### 10. 性能规范

**10.1 🟡 Compose 稳定性注解** — 所有出现在 Compose State 中的数据类标注 `@Immutable`，或加入 `stability_config.conf`。

**10.2 🟡 禁止 Composition 内读 State** — 使用 `derivedStateOf` 计算派生值，避免不必要的 recomposition。

```kotlin
// ❌ BAD — gameData 每次变化都触发 recomposition
@Composable fun DiscipleName(id: String) {
    val name = viewModel.gameData.collectAsState().value.discipleName
}
// ✅ GOOD — 仅在 name 实际变化时 recompose
@Composable fun DiscipleName(id: String) {
    val name by remember { derivedStateOf { viewModel.getDiscipleName(id) } }
}
```

**10.3 🟡 `LazyColumn`/`LazyRow` 必须用稳定 key** — `key = { it.id }`，不可用 index（会导致排序/过滤时的错误 recomposition）。

**10.4 🟡 Canvas 用 `drawBehind{}`** — 静态绘制用 `Modifier.drawBehind {}`，跳过 Composition/Layout 阶段。动画用 `Animatable` + `LaunchedEffect`。

---

### 11. UI 样式规范

**11.1 🔴 Text 颜色仅黑色** — 所有 `Text()` composable 的 `color` 必须是 `Color.Black`。`GameColors.TextPrimary/TextSecondary/TextTertiary/TextOnPrimary` 均解析为 `Color(0xFF000000)`。

**11.2 🔴 按钮尺寸标准化** — 所有按钮使用 `ButtonSizes.StandardWidth` (72dp) × `ButtonSizes.StandardHeight` (38dp)。

---

### 12. 文档规范

**12.1 🟡 公开 API 必须有 KDoc** — `:core:domain` 和 `:core:engine` 中的所有 public 函数/类/属性必须有 KDoc（描述 + `@param` + `@return`）。

**12.2 🟢 架构决策记录到 `docs/adr/`** — 新模块、重要模式变更、大重构写入 ADR（Context / Decision / Consequences）。

**12.3 🟢 同步 `CODE_WIKI.md`** — 新增模块/模式后更新架构文档。

**12.4 🔴 功能变更必须更新 Changelog** — 功能完成后同步更新两个文件，变更从玩家视角用中文描述：

- **游戏内**: `android/app/src/main/java/com/xianxia/sect/core/ChangelogData.kt` — 追加 `ChangelogEntry`
- **外部**: `CHANGELOG.md`（项目根目录）— 追加到**当前版本**段落内，不强制递增版本号

**版本号变更规则：**
- 普通改动写入当前版本条目，不递增 `versionCode`/`versionName`
- **禁止擅自更新版本号**，由用户判断和指令
- 需要在 `build.gradle` 中更新版本号的场景（发布新版本、重大功能完成、存档兼容性变更等）由用户决定

---

### 13. 代码审查与强制执行

**13.1 🔴 Pre-commit 检查** — 每次提交前运行 `./gradlew.bat compileReleaseKotlin lintRelease`，必须 BUILD SUCCESSFUL。

**13.2 🔴 detekt baseline 只缩不增** — `detekt-baseline.xml` 只能减少条目，不能新增。新违规必须修复而非加入 baseline。

**13.3 🔴 PR 审查清单：**

| 严重度 | 检查项 |
|--------|--------|
| 🔴 | 无 `!!` 操作符 |
| 🔴 | `CancellationException` 已重新抛出 |
| 🔴 | 无空 catch 块 |
| 🔴 | Entity 变更有 Migration |
| 🔴 | UI 层无直接 `GameStateStore` 访问 |
| 🔴 | 文件不超过 2000 行 |
| 🔴 | 类构造参数不超过 7 个 |
| 🔴 | 新功能有测试 |
| 🔴 | 代码无"当前能跑就行"迹象（边界/异常/日志/硬编码） |
| 🔴 | 新增影响修炼速率的操作已添加 `checkpointDisciple()` 调用 |
| 🔴 | 新增随机数逻辑已使用 `GameRngManager.getRng(RngPartition.xxx)` 替代 `kotlin.random.Random` |
| 🔴 | 新增 `GameSystem` 已拆分为 ≤60 行/方法的子系统，不可出现 God Method |
| 🔴 | 新增系统的 `EventBus` 事件 emit 在 `stateStore.update` 事务外（参照 `flushPendingEvents` 模式） |
| 🔴 | 新增死亡标记路径已调用 `DiscipleDeathHandler.markDead` 或 `handleDiscipleDeath`，非手动写三个字段 |
| 🔴 | 新增影响炼丹/锻造/灵田速率因子已同步更新 `calculateWorkDurationWithAllDisciples` 或 `calculateSpiritFieldMaturityBonus` |
| 🔴 | 新增生产类政策已同步在 `SectPolicyToggleUseCase` 中触发 `checkpointAllProduction()` |
| 🔴 | 新增长老类型已同步在 `ElderManagementUseCase.productionElderTypes` 中注册 |
| 🔴 | 新增包含输入框的对话框已检查 `DialogSoftInputGuard` 保护（详见 `rules/dialog-soft-input-guard.md`） |
| 🔴 | 新增聊天/对话类对话框使用 `UnifiedGameDialog` 容器（详见 `rules/chat-dialog-design.md`） |
| 🔴 | 新增标记 `isAlive=0` / `status=DEAD` 的代码路径必须调用 `discipleTables.markDead(id, year)` 而非手动写三个字段；仅 `handleDiscipleDeath` 可豁免（已内置 deathYears 写入） |
| 🔴 | 新增精灵图已在 SpriteResRegistry 注册 + 文件已放两个模块 drawable-nodpi（详见 `rules/static-resources.md`） |
| 🔴 | 新增 UI 界面使用 `SpriteImage()` 或 `SpriteResRegistry.resolve()` 而非直接 `R.drawable.xxx` |
| 🔴 | 渲染特性变更（地图/Canvas/精灵）已同步实现 Vulkan 和 Canvas 两路径（见 `docs/renderer-feature-checklist.md`） |
| 🔴 | 新增渲染特性有对应的 `SoftwareCanvasBackend` 单元测试（`SoftwareCanvasBackendTest.kt`） |
| 🔴 | HW 加速决策已检查所有 Activity 入口（`MainActivity` 和 `GameActivity` 均需在 `super.onCreate()` 前检查 `isAccelerationDisabled()`） |
| 🔴 | 使用 `Build.SOC_MANUFACTURER`（API 31+）、`Build.SOC_MODEL`（API 31+）等新增 API 字段已添加 `Build.VERSION.SDK_INT` 守卫 |
| 🟡 | 新 Service 有 `@GameService` 注解 |
| 🟡 | State 数据类有 `@Immutable` |
| 🟡 | 公开 API 有 KDoc |
| 🟡 | Flow 派生用了 `distinctUntilChanged`/`sample`/`stateIn` |

**13.4 🔴 detekt 配置** (`android/config/detekt/detekt.yml`)：
```yaml
style:
  MaxLineLength:
    maxLineLength: 80       # import/KDoc标签/URL 除外
  WildcardImport:
    active: true
  MagicNumber:
    active: false           # 游戏数学常量
complexity:
  TooManyFunctions:
    thresholdInFiles: 15    # 从 30 收紧
  LongParameterList:
    functionThreshold: 6
    constructorThreshold: 7
empty-blocks:
  EmptyCatchBlock:
    active: true            # 已启用 (detekt 1.23+ 规则集为 empty-blocks)
```

---

## 设计方案规则

出方案或做设计决策时，**必须**先使用 `/deep-research` skill 并结合网络搜索，调研同游戏行业的先进设计，给出对标分析后再出最优方案。禁止凭经验直接写代码。

### 设计方案基本原则

设计方案必须遵循以下基本原则（第1~5条），作为方案评审的核心标准：

**1. 🔴 方案符合编写规范** — 设计方案必须使用统一结构编写，包含：**背景与目标**（需求要点+成功标准）、**技术方案**（架构变化+关键类/接口+数据流）、**影响范围清单**（所有受影响的文件/模块及其变更方式）、**兼容性分析**（Migration/序列化/存档）、**测试方案**（单元测试+对抗性审查要点）、**风险评估与兜底**。禁止使用非结构化的段落描述替代规范方案文档。方案文档必须独立可读，不依赖口头补充。

**2. 🔴 功能模块化设计** — 新增功能必须设计为可独立发布、可单独测试、可通过配置开关控制启停的模块。模块之间通过接口通信，内部实现变更不影响外部调用方。禁止将新功能硬编码嵌入既有类或函数中形成面条式代码。新增前必须检查是否可通过扩展已有模块（`:core:engine/domain/` 或 `:core:engine/system/`）实现，避免重复造轮。

**3. 🔴 全局视角** — 设计方案必须覆盖变更波及的所有子系统：UI（跨屏适配+输入法避让+对话框栈）、存储（Migration+向前兼容+向后兼容）、渲染（Vulkan + Canvas 双路径验证）、测试（单元测试+集成测试+对抗性审查）、后续平台扩展（iOS 接入点预留）。方案中必须包含**"影响范围清单"**（格式：`文件路径 — 变更类型 — 变更说明`），遗漏视为方案不完整。同时需检查变更是否与进行中或规划中的其他功能存在冲突，必要时协调优先级。

**4. 🔴 低高端设备兼容设计** — 所有涉及渲染、UI 框架、原生库加载的功能变更，必须预先评估在低端/老旧设备上的兼容性。具体要求：

- **渲染管线双路径** — 任何渲染特性变更必须同时验证 Vulkan 和 Canvas（软件渲染）两套路径，确保低端 GPU（Mali-G5x/6x、PowerVR、Adreno 6xx 等）在 Vulkan 驱动缺陷下可用软件渲染降级
- **系统 HWUI 层同步降级** — 关闭 Vulkan 渲染决策必须同步关闭 Activity 级硬件加速（`android:hardwareAccelerated="false"`），因为定制 ROM（Magic UI、澎湃OS、OriginOS 等）可能在 Android < 15 上回传 SkiaVK/Vulkan HWUI，仅关闭游戏渲染层不够
- **所有 Activity 入口统一检查** — `MainActivity` 和 `GameActivity` 等所有 Activity 的 `onCreate()` 必须在 `super.onCreate()` 前检查 `VulkanPolicy.isAccelerationDisabled()` 并切换主题。仅保护一个 Activity 会导致启动阶段崩溃
- **API 级别守卫** — 所有 `Build.SOC_MANUFACTURER`（API 31+）、`Build.SOC_MODEL`（API 31+）等新增 API 字段必须在访问前用 `Build.VERSION.SDK_INT >= 31` 守卫，禁止在 `Application.onCreate()` 等启动阶段无保护访问，否则低 API 设备触发 `NoSuchFieldError` 直接闪退
- **GPU 黑名单对齐行业标准** — 参考 Unity Vulkan Device Filtering（Mali GPU Vulkan API<1.0.61 自动降级）、Flutter Impeller（自动检测 MTK SoC 回退 OpenGL ES）、Chromium（Mali-G57 全面禁用 Vulkan）等行业标杆，持续更新已知问题 GPU/SoC 列表。新增黑名单条目需附带 Bugly 崩溃数据或行业报告引用

**5. 🔴 隐私合规与隐私政策同步更新** — 设计方案涉及以下变更时，必须同步评估和更新隐私政策：
- 新增或变更第三方 SDK（含 SDK 版本升级、初始化时机变化、数据收集范围变化）
- 新增或变更个人信息收集的类型、目的、方式
- 新增或变更权限申请（Android 权限或 iOS 隐私权限）
- 新增网络请求或变更数据传输方式（新增服务器端点、新增请求头信息等）
- 新增或变更数据共享第三方
- 新增或变更广告/分析/推送模块

隐私政策更新必须覆盖两个入口：
1. **游戏内隐私政策**（`PrivacyConsentScreen.kt`）— 更新展示内容或三方 SDK 链接
2. **网站版隐私政策**（`docs/index.html`，发布在 `https://hsmy7.github.io/mnzm/`）— 更新网页内容

变更说明需记录在方案文档的"影响范围清单"中，标注 `隐私合规` 标签。

**方案必须是可长期维护的成熟方案，禁止分阶段/渐进式交付。** 设计方案应当一次性完整覆盖所有影响点（包括 UI、存储、测试、旧数据兼容），不允许遗留"后续优化"。方案本身即为最终态，执行者照单实施即可，不应需要自行补充或二次设计。

**最优方案不计成本且需跨 iOS 平台：** 不考虑时间成本、人力成本等任何实施成本，要求**全量采纳**同类型热门游戏（原神、星铁、米哈游系、网易系、腾讯系、莉莉丝、鹰角、叠纸等）的先进设计。若因产品定位、技术栈限制等客观原因无法全量使用，必须逐条说明无法采纳的原因和替代方案。

**方案必须考虑 iOS 跨平台兼容性：** 所有设计方案（UI 组件、渲染管线、手势系统、存储格式、网络协议等）必须确保 Android 与 iOS 两套平台均可落地，优先选择跨平台一致的方案。若技术方案依赖 Android 独占 API 或平台特定特性，须在方案中给出 iOS 侧的对等实现方案。

**硬性指标：**
- 行业参考来源 **不得少于 20 条**，且必须来自权威渠道
- 所有参考数据必须是 **本年加前两年（当前年份和前两年）** 的最新数据（以当前日期为准），禁止引用过时资料
- 每条参考必须标注来源 URL 和发布日期，无法确认发布日期的来源不得使用
- 调研报告中必须包含参考来源清单及每条的核心摘要

**参考来源权威等级（优先采信高等级来源，低等级来源不计入 20 条配额）：**

| 等级 | 来源类型 | 计入配额 | 示例 |
|------|----------|----------|------|
| **S 级** | 官方文档 / 白皮书 | ✅ | Unity/Unreal 官方文档、Apple HIG、Google Material Design |
| **S 级** | 行业权威报告 | ✅ | Newzoo、Sensor Tower、data.ai、伽马数据、腾讯研究院 |
| **S 级** | 顶会演讲 / 论文 | ✅ | GDC Vault、SIGGRAPH、ACM/ IEEE 论文 |
| **A 级** | 头部产品官方技术博客 | ✅ | 米哈游 Tech Blog、Supercell 技术分享、Epic 技术博客 |
| **A 级** | 知名开发者 / 团队公开复盘 | ✅ | Riot Games 技术博客、Digital Foundry 分析 |
| **B 级** | 高质量社区技术文章 | ✅ | Medium 高赞（>500 claps）、知名技术博主 |
| **C 级** | 个人博客 / 论坛帖子 | ❌ 不计入 | 仅作补充参考 |

**来源优先级：官方文档 > 行业报告 > 顶会演讲 > 头部产品技术博客 > 知名团队复盘 > 社区文章。20 条配额中至少 12 条来自 S 级或 A 级来源。**

流程：
1. 明确需求 → 列出待调研的设计问题
2. 使用 `Skill` 工具调用 `deep-research` + `WebSearch` 搜索行业做法
3. 对标头部产品（原神、星铁、网易、腾讯系、米哈游系、莉莉丝、鹰角、叠纸等）的设计模式
4. 确保收集 ≥20 条有效参考后，输出对比分析报告，标注推荐方案和理由
5. 报告末尾附完整的参考来源清单（标题 + URL + 发布日期）
6. 用户确认后再执行

## Version Release

When releasing, update in `android/app/build.gradle`:
- `versionCode` — increment by 1
- `versionName` — three-segment format `x.x.xx` (two-digit last segment, zero-padded). E.g., `2.6.09` → `2.6.10`, `2.6.99` → `2.7.00`. Never `2.6.1` (missing zero-pad).

See `rules/version-release.md` for the full release checklist.

## Android SDK / Encoding

- `compileSdk = 35`, `minSdk = 24`, `targetSdk = 35`
- All Java/Kotlin compilation is forced to UTF-8 to prevent Chinese character corruption
- Uses Aliyun Maven mirrors for Gradle plugin and dependency resolution
