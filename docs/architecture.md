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
- [待完成项登记（R 系列余量）](#待完成项登记)

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
    ├─ T1 立即组 (11 项, 单事务)    ← 年龄不变量/招募三件套/驻军报告
    └─ T2 延迟组 (11 项, 入队)      ← YearlyOpsQueue 逐 tick 预算 drain (30ms)
```

### 年变分帧（2026-08-09 引入）

年变 22 项处理器 + 年俸 + 月度事件原先在同一 tick 无预算串行执行，总工作量随存档规模无界增长
（AI 弟子池最多 30,000、招募池 1000+），引擎线程被占期间世界静止 → 玩家感知"每年一月卡住数秒"。

**拆分策略**（`CultivationEventMonthlyOps.processYearlyEvents`）：

- **T1 立即组（11 项，单事务保原相对序）** — 必须当月立即：玩家老化+死亡（年龄不变量）、
  招募三件套（刷新年新弟子被当年 recruitAging +1）、garrisonAndReport（与纳贡同事务，
  annual* 字段必须计入年报）
- **T2 延迟组（11 项，入队延迟执行）** — 全部有自愈语义：差值判据（lastRecruitYear/
  lastAiSectRecruitYear/lastTradeYear ≥ N，跳过次年自动补跑）或延迟无感（AI 老化晚 1 tick、
  外交/秘境明年可补）
- **drain 预算** — `YearlyOpsQueue.drain(30ms)` 每 tick 调用（GameEngineCore.tickInternal），
  至少执行 1 个 op；非 1 月 forceDrain 跨月兜底；存档前 flush 保证"快照 ⇒ 队列已空"

**并发与一致性不变量**（对抗性审查闭环）：

- 入队发生在 T1 事务**内**（最后一步）→ 事务提交与全部入队原子，快照窗口闭合
- `flushYearlyOpsQueue` 先空事务拿 transactionLock（与在途 T1 串行）→ forceDrain 全清
- `YearlyOpsQueue.consumerLock` — drain（引擎 tick）与 forceDrain（存档线程）同刻至多
  一个消费者，FIFO 顺序恒成立（不存在 op2 先于 op1 的交错）
- 读档入口 `clearYearlyOpsQueue` — 丢弃旧档残留延迟组，防跨存档污染
- 崩溃语义 — 队列为进程内态崩溃即丢，但差值判据自愈 + flush-on-save 保证存档时已执行

**AI 修炼季度降频（L2）** — `AISectBattleProcessor.THERMAL_NORMAL_BATCH` 1→3：首次相位
基准 = (当前月-1) 向下取 3 的倍数（mod 3 = 0）→ settle 月恒为 3/6/9/12，每个 1 月跳过
AI 修炼，年均总量不变（`repeat(batchMonths)` 语义）；热控降级链 REDUCE=6 / EMERGENCY=12
保持单调（1 月可能 settle，仅热控时，文档化接受）。

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
| 战斗伤害 | `DamageZones`（攻击Buff/防御穿透/暴伤/增伤/减伤）+ 境界压制独立因子（每小层 ±30%，不并入任何乘区，独立乘算） | `BattleCalculator.kt` |
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
  │   ├─ [order=1]  DiscipleIdBoundsRule  弟子 ID 越界（>200K/负值）判损坏（C3-b，防大 id 扩容 OOM）
  ├─ [order=1]  SectNameRule           sectName 非空
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

## 待完成项登记

> 2026-08-15 债务根治批次后重开：D 系列历史待办（D-01~D-49）已全量处置完毕，
> 处置要点与"不纳入"决策理由归档至 CHANGELOG.md（4.00.86~4.00.93 及「债务根治（2026-08）」条目），
> 本文件不再保留历史实施记录（2026-08-15 清理）。
> 交付盘点发现的清单外存量问题登记为 R 系列。
> 处置惯例：完成后记入 CHANGELOG；决策不修项记录理由；条件式未来工作归入下方"偿还触发条件档案"。

### 余量待办 R 系列登记表（2026-08-15 根治批次交付盘点）

| # | 来源 | 待办内容 | 严重度 | 治理方向 |
|---|---|---|---|---|
| R-01 | 根治批次交付盘点 | **detekt baseline 存量约 3058 条违规未登记**（app 254 / data 464 / domain 511 / engine 1167 / ui 23 / game 639）：TooGenericExceptionCaught / ReturnCount / CyclomaticComplexMethod / ThrowsCount / UnusedParameter 等历史存量，不在架构文档原登记范围，baseline"只缩不增"下仍真实存在 | 🟡 中 | 专项批次逐条真修或评估销账；新违规必须直接修复 |
| R-02 | 根治批次交付盘点 | **core/engine 存在 33 处 `import android.*`**（android.util.Log / android.os.Build / android.content.Context / PerformanceHintManager / SystemClock 等，分布于 thermal/perf/config/registry/save 等领域），与"零 Android 依赖"声明不符（本批次仅清零 audio 包） | 🟡 中 | 平台能力接口化专项（参照 G1/G3 已建模式：core 层接口 + app 层实现） |
| R-03 | 根治批次交付盘点 | **163 处 @Suppress 拆分妥协**：LongMethod 为真拆根除，但拆分搬移引出的 LongParameterList / CyclomaticComplexMethod / ReturnCount / UnusedParameter 等 163 处采用 @Suppress 压制（语义保真已验证） | 🟢 低 | 长期项：参数聚合数据类/策略模式等真拆，逐步消除 Suppress |
| R-04 | 根治批次交付盘点 | **lintRelease 存量 10 条警告 + 3 条基线过滤**（app/lint-baseline.xml） | 🟢 低 | 逐条销账或补豁免理由，目标零警告 |
| R-05 | 根治批次交付盘点 | **proguard 宽规则"按序试删"未实际执行**：kotlinx.serialization / coroutines / lifecycle / room 整包 keep 保留（assembleRelease 已通过，但未逐条试删验证可否进一步收窄） | 🟡 中 | 按 T-PRO 顺序在下次 R8 发布验证时实际执行试删（每次删一条 + 完整 R8 + 存档读写回归） |
| R-06 | 根治批次交付盘点 | **测试代码数百处 `!!` 断言风格**（生产代码已清零，测试保留） | 🟢 低 | 项目决策：纳入规范统一清理或正式豁免测试断言 |
| R-07 | 根治批次交付盘点 | **CI 全绿未经真实 push 验证**：GitHub Actions 仅在 push 后实跑，本地验证门不等同 CI 结果 | 🟢 低 | 下次 push 后观察首次实跑；失败即修 |
| R-08 | 根治批次交付盘点 | **Kotlin 2.2 注解目标警告（KT-73255）**：`@ApplicationContext` 等限定符注解在构造参数上，全库同模式（AndroidAudioPlayer/BuglyCrashReporter 编译时已现警告） | 🟢 低 | 项目级决策：`-Xannotation-default-target=param-property` 或逐处 `@param:` 迁移 |
| R-09 | 2026-08-08 批次"途中发现" | **`withOverflowMailSuppressed` 8 个调用点语义审计**：D-01 新机制下语义变为纯"凭据类不转邮件"，是否保留待审计 | 🟡 中 | 逐调用点核对语义与 CLAUDE.md 13.3 溢出语义分类 |
| R-10 | docs/build-perf/test-split.md 已知限制 | **app / feature:game 测试拆分门控未实施**（Robolectric 占比 41% / 39% 未过门控） | 🟢 低 | 按 test-split.md 门控执行模块拆分 |
| R-11 | docs/build-perf 遗留调查项 | **build-perf 文档两处机制未完全解释**：baseline 139.8s 失真机制、Kover 掩盖 Collector 问题机制 | 🟢 低 | 补调查并更新对应 build-perf 文档 |
| R-12 | 2026-08-21 detekt 全量排查（core:engine 15 项清偿后暴露） | **core:domain detekt 3 项违规**：`StackableItemStore.add` LongMethod 63/60（溢出邮件合并功能）；`GameConfig.SectMap.GATE_X/GATE_Y` MayBeConst ×2（灵矿场/门楼配置）——均为 2026-08-19~21 新功能引入、未冻结 baseline | 🟢 低 | 低风险清理：`add` 拆辅助函数；GATE_X/GATE_Y 改 `const val`（表达式引用常量可 const），修后 `:core:domain:detekt` 归零 |
| R-13 | 2026-08-21 detekt 全量排查（core:engine 15 项清偿后暴露） | **feature:game detekt 6 项违规**：`MainGameScreen` FileLength（1744 行 UI 文件，需独立拆分工程）；`BuildingDelegate` TooManyFunctions 22/20；`MaterialSelectorDialog` LongMethod 88/60（血炼池）；`ResidenceDialog` LongMethod 60/60 + `ResidenceDialogContent` LongParameterList 11/8（住所升级对话框）；`MainGameScreenDemolishControls` LongMethod 60/60 | 🟡 中 | 除 MainGameScreen 拆分（独立重构工程，涉及上千行 Compose 迁移与独立回归）外，其余 5 项为低风险清理（拆函数/参数分组/@Suppress 豁免评估）；MainGameScreen 拆分单列专项 |

### C++ 引擎迁移待办 C 系列（2026-08-22 批次 0-3 完成登记）

> 总方案：`docs/adr/cpp-engine-migration.md`；进度：`docs/cpp-engine.md`。
> 已完成批次 0（基础设施）/1（状态模型+快照）/2 第一子步（装备表 codegen）/3（时间系统+结算引擎）。
> 以下为**活跃待办**——批次 4-10 为后续迁移批次，C-01~C-05 为批次 0-3 审查登记项。

| # | 待办内容 | 触发/计划 |
|---|---|---|
| C-01 | **批次 4：经济/生产/库存系统**（SpiritStoneWallet、InventorySystem、生产槽结算、溢出邮件、灵田/矿场/炼丹/锻造）——月变结算钩子（onMonthChange）首批实现 | 下一批次 |
| C-02 | **批次 5：弟子系统**（属性计算乘区法、修炼 Checkpoint、突破、丹药/装备/功法、死亡/婚姻、11 槽分配）——onPhaseSettle 钩子（修炼推进） | 批次 4 后 |
| C-03 | **批次 6：战斗系统**（BattleSystem、AI 宗门/妖兽、宗门战） | 批次 5 后 |
| C-04 | **批次 7：世界/内政**（建筑、政策、外交、宗门等级、年俸、邮件、兑换码）——onYearChange 钩子（年度报告） | 批次 6 后 |
| C-05 | **批次 8：探索/秘境**（Exploration、SecretRealm、Cave、HeavenlyTrial、Patrol） | 批次 7 后 |
| C-06 | **批次 9：集成切换**（GameEngine 族 ~275 方法 + Facade 117 方法转发、StateSyncService 镜像、tick 桥、事件回调、feature flag、存档对接、性能基准） | 批次 8 后 |
| C-07 | **批次 10：Kotlin 引擎退役**（删除重复逻辑、对拍转回归、清理临时工具、文档收尾） | 批次 9 后 |
| C-08 | **批次 1 剩余：低频嵌套类型**（秘境/血炼/探索队伍/功法精通/侦查信息等 ~20 个）与重型 @Transient 字段（aiSectDisciples 等） | 随批次 3-8 子系统迁移配套 |
| C-09 | **批次 2 剩余：Registry 提取器扩展**（丹药配方/功法/天赋/体质/词缀/材料/锻造配方/妖兽材料，格式差异逐表适配） | 随批次 4-8 |
| C-10 | **批次 3 剩余：月变/年变结算钩子系统实现**（政策成本/生产/年俸/年度报告等，依赖批次 4-7 系统） | 随批次 4-7 |
| C-11 | **审查登记：C++ `shuffled(rng)` 未实现**——实现时必须用 `std::stable_sort`（Kotlin sortedBy 稳定），且确定性对拍 | 批次 5+（涉及随机打乱时） |
| C-12 | **审查登记：nextGaussian 跨语言精度风险**——JVM Math.cos/log/sqrt 与 C++ std::cos/log/sqrt 可能最后一位差异；对拍验证，发现差异则内嵌 fdlibm | 批次 5（弟子属性生成） |
| C-13 | **审查登记：读档后 RNG 分区状态恢复**——GameCore.rng_ 需从 GameData.rngStates 恢复（import 时），当前未接线 | 批次 5（首个 RNG 消费系统） |
| C-14 | **审查登记：float 字段对拍覆盖**（WorldSect.x/y、WorldLevel.x/y）——批次 1 已覆盖抽样，全量 float 语义随批次扩展 | 随批次 4-8 |
| C-15 | **审查登记：Diff 对拍基准为内联复刻**（DiffTimeTest 复刻 TimeSystem.onPhaseTick；批次 9 集成时切换为真实引擎对拍） | 批次 9 |

### 偿还触发条件档案（2026-08 根治批次建立）

> 本档案收纳"决策不修/客观受限"项的偿还触发条件与实施要点——**不是待办**：
> 触发条件未满足前不产生任何工作；条件满足时按要点实施。上方 R 系列为活跃待办表，
> 本档案是"条件式未来工作"的独立清单（触发前不占工作队列）。

| # | 项 | 偿还触发条件 | 实施要点 |
|---|----|-------------|---------|
| T-D46 | `TapDBServerReporter`（服务端 REST 上报库） | 接入自建后端 / 新增 IAP 需服务端对账 / 需精确 eCPM 对账 | 按 `docs/knowledge-base.md#服务端接入契约` 实现：OkHttp POST、注入式 HttpClient 便于测试、runCatching 静默降级、`device_id` 取 `TapTapEvent.getDeviceId()`、`user_id` 与 `setUser` 一致 |
| T-D47 | 广告 eCPM 估算值 RemoteConfig 化 | RemoteConfig 绑定（`CoreModule.kt` HttpRemoteConfigProvider 激活） | `AdRevenueConfig.estimatedEcpmFen` 迁移为 `analytics.ad_ecpm_*` 键，本地默认值兜底（rules/commercialization.md 规范） |
| T-D48 | OAID 手动模式（msa SDK） | 广告填充率不达标 / 经第三方聚合（TopOn/GroMore）接入 TapADN | 引入 msa OAID SDK（证书 + supplierconfig.json 外部资产）+ `TapDBManager.setOaid` 手动上报 API |
| T-D49 | TapDB 账号/设备属性与静态通用属性 API | 需要账号维度属性分析 / 注册跨事件通用属性 | 按 TapTap 官方客户端接入文档实现 `userUpdate`/`deviceUpdate`/`addCommon`（`setLevel`/`setServer` 迁入 userUpdate） |
| T-D40A | Aligned16KB ×3（Bugly `libBugly.so`、Dirichlet `libdirichlet.so`） | SDK 方发布 16KB 对齐新版本 | 升级 SDK 并复查 lint Aligned16KB 清零（Android 15+ 内存页对齐要求） |
| T-D40B | glide 4.9.0 → 5.x | 广告 SDK（TapADN）确认兼容 glide 5 | 升级 + 全量测试 + 真机广告链路冒烟 |
| T-A2 | 音频 `release()` 接线 | 产品新增"退出游戏回主菜单"流程 | `GameForegroundService`/`GameActivity` 退出路径调 `AudioPlayerFacade.release()`（audio-thread-audit.md A2） |
| T-RB | Robolectric 4.13 卡死（52 处 `@Config(sdk=34)` pin） | TapTap SDK 字节码修复（Collector 补 StackMapTable）或 Robolectric 提供 COMPUTE_FRAMES 开关 | 升级后重查字节码 + 性能评估（4.16 比 4.13 慢 ~2 倍），见 docs/build-perf/robolectric-4.16-evaluation.md |
| T-CONV | 各模块 SDK 配置再收编（convention plugin 已建立） | 模块再增长或 KMP 迁移 | 新模块直接应用 `xianxia.android[.application/.test]` convention plugin（docs/build-perf/stage3-config-cache.md） |
| T-PRO | proguard 宽规则进一步收窄 | 每次 R8 相关发布验证 | 按序尝试删除 kotlinx.serialization → coroutines → lifecycle/room 整包规则（官方 consumer rules 兜底），每次完整 R8 验证 + 存档读写回归 |
| T-CPP-1 | C++ 引擎未实现 kotlinx-proto 编解码（存档经 Kotlin 镜像，格式零变更） | iOS 立项且需无 Kotlin 的纯 C++ 存档 | 按 kotlinx-serialization protobuf 标准 wire 兼容方案实现（2174 个 @ProtoNumber schema 生成 + 默认值省略规则 + Map/Set KeyValue/packed + .sav 两层头 + CRC32C）；当前镜像方案已覆盖全部场景（详见 docs/adr/cpp-engine-migration.md） |
| T-CPP-2 | 静态数据双份（Kotlin Registry + C++ 表） | 批次 10 Kotlin 引擎退役后 | 统一为单一源：Kotlin Registry 删除或改 codegen 生成（docs/cpp-engine.md 批次 2 说明）；迁移期以抽样守卫测试防漂移 |

### 待真机验证指引（2026-08-09 归档保留，真机验证时查阅）

| # | 项 | 验证指引 |
|---|---|---|
| P-16 | UI 迁移真机冒烟 | SettingsTab/DiscipleDetailScreen/OverlayDialogRouter：4 个迁移弹窗（其他设置/年俸/存档管理/更新日志）逐一打开关闭 + OverlayDialogRouter 34 分支逐项打开一次；判定：无崩溃/白屏/交互完整/叠层路由正常 |
| P-18 | 排行榜 rank 0/1 起始语义 | `TapTapLeaderboardApi.kt`：首名显示 #1 且次名重复 #1 → 服务端 1 起始，移除归一化；次名 #2 → 保留现状。抓原始 rank 与显示值对照 ≥3 次 |
| P-19 | 一月卡顿根治性能量化（代码已提交 commit `7dae538b`） | 真机装 4.00.93 包跨过至少 1 个游戏年，抓 logcat：`CultivationEventMonthlyOps.kt` 各处理器 `op[...] took Nms` 耗时 + `GameEngineCore.kt` "Tick over budget" 是否出现。判定：1 月 tick 收敛至单帧级（目标 <100ms）；AI 修炼日志仅出现在 3/6/9/12 月。**目前仅算法复杂度论证，无实测数据** |

> 历史实施记录（D-01~D-25、2026-08-08 / 2026-08-09 批次、2026-08 债务根治批次）已于 2026-08-15 随本文件清理移除——完整实施要点、验证结果与"不纳入"决策理由见 CHANGELOG.md 4.00.86~4.00.93 及「债务根治（2026-08 架构文档债务全量根治批次）」条目与 git 历史。
