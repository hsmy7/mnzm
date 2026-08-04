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

## 扩展性架构预留（2026-08-04 起）

> 本节点明未来扩展（商业化/社交/数据/离线收益/iOS）的架构预留点与现状基线。事实基线见 `docs/knowledge-base.md#扩展性现状盘点`；接入约束见各 rules/*.md 扩展规范（expansion-playbook / commercialization / social-system / data-analytics / economy-design / code-quality）。

### 1. RemoteConfig 远程配置

- **现状**：`RemoteConfigProvider`（core/domain 接口）+ `HttpRemoteConfigProvider`（core/engine 实现，10s 超时）已存在但**未绑定**——`CoreModule.kt:156-158` 处于注释状态，`ConfigLoader(assetReader)` 纯本地
- **激活前置**：先补服务端能力（JSON 托管端点/版本管理/下发策略）→ 再改 `CoreModule` 绑定；每个配置项必须带本地默认值兜底（配置缺失不崩溃）
- **约束**：`rules/commercialization.md` 第 4 节（Key 命名 `模块.配置名`、版本化、A/B 分组）

### 2. 商业化接入点

- 广告：`AdService`（接口，core/engine）→ `AdServiceImpl`（app 层，白名单守卫）→ `RewardVideoAdManager`（TapTap SDK）；新 AdPurpose 引擎层枚举注册 + `watchAd()` 统一入口（knowledge-base 白名单章节）
- IAP/月卡/战令：**0 现有代码**，接入约束见 `rules/commercialization.md` 第 2 节（购买校验/领取窗口/战令设计）
- 运营活动：`LizhanDialog` 历战卡片轮转（卡片注册 + 时间窗三态）；运营邮件 `BuiltinMailConfig` 客户端内置 → 未来 RemoteConfig 推送

### 3. 埋点接入点（建议方案，未实现）

- **推荐独立通道**：新建 `AnalyticsService`（接口，core/engine）→ `AnalyticsServiceImpl`（app 层）+ 异步批量上报队列——**不复用 GameEventBus**（EventBus 是游戏内事件审计，语义不同；埋点带 PII 风险需独立隔离）
- 事件字典登记 `docs/knowledge-base.md`；约束见 `rules/data-analytics.md`

### 4. 离线收益引擎接入点

- **现状基线**：后台纯暂停，无放置产出（`GameEngineCore.kt` 后台暂停逻辑）——**不改基线**
- 接入点：收益结算挂 L0 时间推进（惰性结算引擎四层中的时间推进层），禁止另起结算循环；12h 挂机收益上限强制每日 2 次回访
- 约束：`rules/expansion-playbook.md` 离线收益预留 + `rules/economy-design.md` 第 4 节（收益数学）

### 5. 社交隔离层

- 社交/排行独立模块（Service/Store 隔离层），**禁止修改 `engine/domain/diplomacy/` 既有 AI 外交代码**
- 客户端不可信前提：服务端排行数据必须签名/防重放
- 约束：`rules/social-system.md`

### 6. iOS 迁移预留（游戏未来做 iOS 端）

| 技术栈 | Android 现状 | iOS 迁移方案 | 风险 |
|--------|-------------|-------------|------|
| core:domain / core:engine | 零 Android 依赖（基线 ✅） | KMP 直接复用 | 低 |
| C++ 渲染引擎 | Vulkan（Android 独占）+ JNI | Metal 或软件渲染（`SoftwareCanvasBackend` 纯软渲染可跨平台）；JNI → 平台桥 | 中 |
| Compose UI | Jetpack Compose（Android 独占） | Compose Multiplatform 或重写 | 高（评估点） |
| Room | Room 2.6.1 | SQLDelight / 原生 SQLite | 中（迁移风险点，新数据层组件优先跨平台选型） |
| Hilt DI | Hilt 2.56 | Koin / 手写 DI | 中 |
| DataStore | DataStore（Android 独占） | MMKV（已跨平台）替代 | 低 |
| 网络 | Retrofit + OkHttp + Gson | Ktor 或接口抽象 | 低（已走接口） |
| 平台 SDK | TapTap（登录/云存档/广告）+ Bugly | TapTap iOS SDK；Bugly → 对应崩溃上报 | 中 |

**迁移前置原则**（约束新代码）：core 层禁 Android 独占 API、平台能力接口抽象（`RemoteConfigProvider`/`AdService` 模式）、新平台依赖方案中给 iOS 对等实现——详见 `rules/code-quality.md` 第 1.5 节。

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

### 待完成项（2026-08-02 综合优化对抗性审查遗留，预存问题/设计决策）

本次综合优化（性能批量改造/状态管道重构/代码精简）经 3 个对抗性审查代理（边界狂魔/状态破坏者/数据篡改者）审查，**新增引入的 15 项问题已全部修复**（见提交说明）；以下为**预存问题或设计权衡**，需人工决策后处理：

| # | 待办 | 现状 | 说明 |
|---|------|------|------|
| T1 | 混合 0/非 0 sequenceId 回填破坏单调递增 | ⏸️ 记录 | 旧档 `[0,0,5]` 回填为 `[6,7,5]`——靠前 0 序号条目拿到比靠后非零条目更大的序号。当前唯一消费者是 LazyColumn key（无排序依赖），无即时后果；任何未来按 sequenceId 排序/取"最新"的消费方会读错顺序。修复方案：回填时对非 0 序号也做整体重编号（一次性 O(N)） |
| T2 | restart 与 load 无互斥 | ⏸️ 记录 | `restartGame` 不设 isLoading、不查 loadLock；`loadGame` 不查 `_isRestarting`/saveLock。重启期间读档可双 boot 竞态（`bootInProgress` CAS 使第二次失败 + loadFromSnapshot 覆写已重置的新世界）。修复需在两者之间加互斥标志（改动涉及 SaveLoadViewModel 全流程，预存设计缺口） |
| T3 | 组装任务与 load 原地清表并发 | ⏸️ 记录 | 组装任务 T0 通过 gen 检查后与 load 的 `clear()+insert()` 并发遍历同一 `_discipleTables`（可能产出半截列表瞬时写回）；load 自身任务 T1（FIFO 后置）兜底最终一致。gen 检查为单点入口设计，中间窗口无法完全消除；观察窗口内 UI 闪旧/错数据（丢弟子外观、陈尸闪现） |

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

## 待完成项（2026-08-04 战斗系统全面核查修复遗留）

> 战斗系统全面核查修复（commit 239d13de：9 项正确性 Bug + 双引擎收敛 + 对抗性审查 12 项整改）已完成。
> 以下为本次未完成项与对抗性审查确认的预存低危项，按优先级排序，完成标准以"≤60 行函数 / 构造依赖 ≤7"等量化指标为准。

### 函数级（God Method 剩余拆分，每批 1 个方法 + 现有测试回归，RNG 调用序不变）

| 项 | 位置 | 现状 | 目标 |
|---|---|---|---|
| processBattleCasualties | `core/engine/.../domain/battle/CombatService.kt` L42-178 | 136 行 | ≤60 行（按死亡/复活分支提取） |
| attackSect | `core/engine/.../GameEngineBattleOps.kt` L35-195 | 160 行 | ≤60 行（提取胜负结算/奖励/关系变更） |
| attackWorldLevel | 同文件 L301-445 | 144 行 | ≤60 行 |
| scoutSect | 同文件 L446-540 | 94 行 | ≤60 行（纯提取优先） |
| executeTeamConflict | `core/engine/.../exploration/PatrolBattleSystem.kt` L279-403 | 124 行 | ≤60 行（提取胜负分支） |
| distributeRewardItems | `core/engine/.../engine/domain/battle/HeavenlyTrialService.kt` L363-488 | 125 行 | ≤60 行（提取掉落/邮件逻辑） |
| EncounterBattleService 各函数 | `core/engine/.../domain/battle/EncounterBattleService.kt` | 各 79-109 行 | ≤60 行（提取伤害应用/战报组装） |
| executeAIEncounterBattle | `core/engine/.../exploration/AISectBeastAttackProcessor.kt` L191-275 | 84 行 | ≤60 行 |
| resolveBeastAttackFight | `core/engine/.../service/ExplorationService.kt` L169-261 | 92 行 | ≤60 行 |
| resolveDefendersAndBattle / decidePlayerAttack | `core/engine/.../domain/battle/AISectAttackManager.kt` L160-248/L419-501 | 88/82 行 | ≤60 行（提取防守选择/攻击决策） |
| executeSupportSkill | `core/engine/.../util/BattleCalculator.kt` L563-654 | 91 行 | ≤60 行（提取治疗/护盾/buff 分发） |

> 注：EnemyGenerator.generateHumanEnemy 已于本次拆分完成（装备/功法生成提取子函数，RNG 调用序不变）。

### 架构级（未完成）

| 项 | 位置 | 现状 | 说明 |
|---|---|---|---|
| W4 AISectAttackManager object → class | `core/engine/.../domain/battle/AISectAttackManager.kt` | object + 文件级 `aisRngManager` var 注入 | 与 EnemyGenerator 同模式（注入点在 GameEngine 初始化必然执行，error() 为防御兜底）；object→class 构造注入波及全部调用点，待专项重构 |
| AI 引擎 turnAdvance（拉条）移植 | `core/engine/.../domain/battle/AISectAttackManager.kt` | 无 processTurnAdvance 实现 | 鹰妖"天翔一闪"等拉条技能在宗门战无效（主引擎 BattleSystem 已实现）；为稀有技能特性，移植需完整实现（行动记录/冷却/伤害结算） |
| C4 战报保留策略 | `core/data` 战报持久化 | BattleLog rounds/actions 全量入库无保留策略 | 长期 DB 增长问题；当前无性能数据支撑（无 Bugly 反馈/无热点证据），待有数据时设计保留窗口/摘要策略 |

### 对抗性审查确认的预存低危（记录在案）

| # | 待办 | 现状 | 说明 |
|---|---|---|---|
| T-C1 | estimateDamage 不含 damageModifier | ⏸️ 记录 | 严苛训练 +5% 时 AI 决策估算略低于实际（预存接口未随 W1 扩展）；轻微一致性偏差 |
| T-C2 | 斩杀分支 maxHp=0/负边界 | ⏸️ 记录 | 斩杀伤害 = defender.maxHp，maxHp=0 时伤害 0、为负时负伤害；需配合存档篡改才可达（hp≤0 目标战斗前已过滤），低危 |
| T-C3 | EnemyGenerator nextInt 配置依赖 | ⏸️ 记录 | `nextInt(realmMax + 1 - realmMin)` 依赖配置 `enemyRealmMin ≤ enemyRealmMax`（当前 MissionDifficulty 全部满足）；配置反转即 IllegalArgumentException，预存数据依赖 |
| T-C4 | 超长 buffs 列表性能 | ⏸️ 记录 | buildDamageZones 每次攻击多次 filter 遍历，buffs 篡改为十万级时游戏线程卡顿（存档篡改场景） |
| T-C5 | 超长弟子名日志开销 | ⏸️ 记录 | 玩家输入弟子名无长度限制，MB 级名字只带来战报内存/日志开销（非本次引入） |

## 待完成项（2026-08-05 存档链路对抗性审查发现，预存问题）

> 存档链路修复（T7~T16 + T4）经 3 个对抗性审查代理（边界狂魔/状态破坏者/数据篡改者）审查，
> **本次引入的 6 项缺陷已全部修复**（见 CHANGELOG.md 4.00.87 对抗性审查整改小节）；
> 以下为**预存问题或既有设计权衡**，按优先级排序，需人工决策后处理：

| # | 严重度 | 待办 | 现状 | 说明 |
|---|--------|------|------|------|
| C1 | 严重 | 主菜单云读档自阻塞 | ⏸️ 记录 | `loadFromCloudSave` 持有 `cloudDownloadLock` 期间 Success 分支调 `loadGame`（SaveLoadViewModel L766→L1477→L718），被 loadGame 第一道守卫 `cloudDownloadLock.get()`（L532）拒绝——云档已写本地但内存加载永不执行，功能必失败。修复：`handleCloudLoadSuccess` 调 `loadGameFromSlot` 前释放锁或 loadGame 对该路径豁免 |
| C2 | 中等 | loadGameFromSlot(0) 自阻塞 | ⏸️ 记录 | `loadGameFromSlot(0)`（L691）先 `setSaveLoadState(isLoading=true)`（同步生效）再调 `downloadFromCloudSave`（L1339 isLoading 守卫恒 true 拒绝）——SettingsTab 云槽位读取必失败（CloudSaveDialog 直调不受影响故日常路径掩盖）。修复：下载前置 isLoading 或复用 CloudSaveDialog 路径 |
| C3 | 中等 | crafted 大 id 弟子 OOM 崩溃 | ⏸️ 记录 | `id=9,999,999`（恰低于 MAX_SAFE_CAPACITY=10M）单弟子触发 ~60 张平铺表扩容至 1000 万容量（≈10GB+）→ OutOfMemoryError（Error 非 Exception，`loadFromSnapshot` catch 接不住）→ 进程崩溃且重试即崩溃循环。修复：MAX_SAFE_CAPACITY 降至 ~100 万或 OOM 纳入 load 失败处理 |
| C4 | 中等 | 操作 finally 无主清理（restart 窗口误杀） | ⏸️ 记录 | `registerActiveLoadJob` 无条件 cancel 旧 job（原子性已修，见整改 6）：restart 的 `stopGameLoopAndWait` 窗口内（saveLock 已持有、isSaving 未置）点保存 → saveGame 通过守卫注册 → 取消 restart 协程；被取消操作 finally 无条件 `clearActiveLoadJob`+清标志会抹掉在途操作状态（S12 torn 回归风险）。修复：finally 只在 `activeLoadJob === 自己` 时清理 |
| C5 | 轻微 | saveGame 双 tap 异步窗口 | ⏸️ 记录 | isSaving 由协程内异步设置，两次快速 tap 在协程启动前均可通过守卫 → job2 注册取消 job1（磁盘已写但 currentSlot 回滚不一致 + 双提示）。危害有限（atomicWrite+Room 事务保证不 torn） |
| C6 | 轻微 | 备份修复失败不反馈 | ⏸️ 记录 | `readWithFallback` 中 `bakFile.copyTo(savFile)` 失败仅 Log.w 仍返回 RECOVERED——.sav 保持损坏反复回退，调用方无法感知。修复：修复失败反映到结果状态 |
| C7 | 轻微 | 文件格式版本不校验 | ⏸️ 记录 | `readAndVerify` 只判 `formatVersion >= 0x0101`，0xFFFF/任意未来版本按当前格式解析（当前无实际危害，格式演进后旧 App 静默误解析新文件）。修复：非 0x0100/0x0101 判损坏 |
| C8 | 轻微 | ensureRegistered 与注册表全局状态耦合 | ⏸️ 记录 | `SaveValidator.registered` 首次 validate 后恒 true，`SaveValidationRuleRegistry.clear()`（测试 @After 常用）不重置——instrumented 场景 clear 后 validate 以空规则运行全部 Passed（生产路径不受影响）。修复：`size == 0` 时重新注册 |
| C9 | 轻微 | AI 宗门弟子修炼值量级不封顶 | ⏸️ 记录 | `NumericSanitizeRule` 对 aiSectDisciples 只做 NaN/负值消毒不封顶，1e308 有限值通过（AI 战力走 base stats 不受影响，后续计算路径引用会放大） |
| C10 | 轻微 | 堆叠截断后储物袋悬空引用 | ⏸️ 记录 | `EntityCountBoundsRule` 截断装备/功法堆叠后 `storageBagItems` 中的 itemId 未清理（只清 4 槽位 + manualIds）；`fixStorageBagReferences` 在 buildSaveDataFromDatabase 时基于未截断堆叠先跑。UI 查无此堆叠时空显示 |
| C11 | 轻微 | delete-then-rename 崩溃窗口 | ⏸️ 记录 | `atomicWrite` 中 `savFile.delete()` 与 `renameTo` 之间进程崩溃 → .sav 缺失走 .bak（损失仅最新一次保存，有备份兜底） |
| C12 | 轻微 | ensureHeavyDataLoaded 空操作标记 | ⏸️ 记录 | 实现为 `if (heavyDataLoaded) return; heavyDataLoaded = true`——从不加载/检查数据，recoverWithPartialData 中该"守卫"是装饰性的（真实保护由不短路的 ensureGameDataIntegrity 承担）。若未来把真实加载逻辑放进此函数并依赖短路即成漏洞。修复：短路前置 `worldMapSects.isNotEmpty()` 校验或删除空调用 |
| C13 | 轻微 | BattleLogRefRule 次要字段未校验 | ⏸️ 记录 | 未校验 `beastsDefeated` 负数等次要字段（当前仅 year/month/turns/teamCasualties/空条目） |

