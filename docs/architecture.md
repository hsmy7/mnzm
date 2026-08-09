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
- [待完成项登记](#待完成项登记2026-08-05-清理历史完成项已移除详见-changelog-40086-40088)
- [实施记录（2026-08-08：D-01 / D-03 / D-05~D-09 / D-15~D-17 十项）](#实施记录2026-08-08d-01--d-03--d-05d-09--d-15d-17-十项已完成)

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

## 待完成项登记（2026-08-05 清理：历史完成项已移除，详见 CHANGELOG 4.00.86~4.00.88）

> 2026-08-02 综合优化遗留（T1~T3）、2026-08-04 代码质量优化（函数级 4 项/上帝对象 5 项/UI 3 项）、2026-08-04 战斗系统函数级 11 项 + T-C1~C4、2026-08-05 存档链路 C1~C13 + T1、预存问题 P-01~P-15/P-17 均已实施（详情见 CHANGELOG 4.00.86~4.00.88）。以下为维持现状决策与待验证项。

### 维持现状决策（记录在案）

| 项 | 位置 | 现状 | 说明 |
|---|---|---|---|
| W4 AISectAttackManager object → class | `core/engine/.../domain/battle/AISectAttackManager.kt` | **维持现状（2026-08-05 决策）** | 探索确认 EnemyGenerator 同样未被 class 化（同为 object + 文件级 RNG var 注入模式）；两处注入点均在 GameEngine 初始化必然执行、error() 为防御兜底，非缺陷；object→class 构造注入波及 20+ 调用点收益不明确 |
| AI 引擎 turnAdvance（拉条）移植 | `core/engine/.../domain/battle/AISectAttackManager.kt` | 不纳入（维持记录） | 鹰妖"天翔一闪"等拉条技能在宗门战无效（主引擎 BattleSystem 已实现）；为稀有技能特性，移植需完整实现（行动记录/冷却/伤害结算） |
| P6 已评估不做 | `core/ui` 5 处平台 Dialog / `clearPendingNotification` / `ui-layout-unification` 分支 | 记录在案 | 组件库基础设施不能自我迁移；`_pendingNotificationFlow` 仍有 GameOverlayHost 消费者；分支已删除/合并无合回风险 |
| P-11 附带：engine 模块其余 122 条 InvalidPackageDeclaration | engine 全模块旧目录布局 | 维持冻结 | battle 域 13 文件已归位（2026-08-05）；全量归位需机械移动约 122 文件，另行立项 |

### 待真机验证

| # | 项 | 位置 | 验证指引 |
|---|---|---|---|
| P-16 | UI 迁移真机冒烟 | SettingsTab/DiscipleDetailScreen/OverlayDialogRouter | 发布前检查 4 个迁移弹窗（其他设置/年俸/存档管理/更新日志）逐一打开关闭 + OverlayDialogRouter 34 分支逐项打开一次；判定：无崩溃/白屏/交互完整/叠层路由正常 |
| P-18 | 排行榜 rank 0/1 起始语义 | `feature/game/.../taptap/TapTapLeaderboardApi.kt` | 已做 0→1 归一化兜底（rank<1 显示 1）。真机观察：首名显示 #1 且次名重复 #1 → 服务端 1 起始，移除归一化；次名 #2 → 保留现状。抓原始 rank 与显示值对照 ≥3 次 |
| P-19 | 一月卡顿根治性能量化（2026-08-09 批次登记，代码已提交 commit `7dae538b`） | `core/engine/.../service/CultivationEventMonthlyOps.kt` safelyRunInState 耗时日志（SLOW_OP_THRESHOLD_MS=25）+ `GameEngineCore.kt` "Tick over budget" 日志 | 真机装 4.00.93 包，游戏内跨过至少 1 个游戏年（1 月），抓 logcat：① 1 月前后 `op[...] took Nms` 各处理器耗时；② "Tick over budget" 是否出现。判定：1 月 tick 从旧版数秒收敛至单帧级（目标 <100ms）；AI 修炼日志仅出现在 3/6/9/12 月（1 月不再触发）。**目前仅算法复杂度论证，无实测数据** |

### 途中发现待办（2026-08-05 引擎确定性加固时登记 D-01~D-06；2026-08-06 三崩溃批次对抗性审查新增 D-07~D-10；2026-08-06 建筑点击无效批次对抗性审查新增 D-11~D-15，详见 CHANGELOG 4.00.89~4.00.90；2026-08-07 商人/宗门交易品阶动态刷新批次新增 D-16；2026-08-07 灵田收获卡死+草药不入库批次对抗性审查新增 D-17~D-20；2026-08-08 数量器升级批次对抗性审查新增 D-21~D-23；2026-08-09 一月卡顿根治批次登记 D-24~D-25，详见 CHANGELOG 4.00.93）

> 本次实施（RNG 事务快照/UI RNG 治理/奖励统一入口/事务合并/缓存）后对抗性审查登记的未修项。P-19/P-20/P-21 已随本次实施完成（奖励收编 InventorySystem + 守卫补漏 + 签到 catch 移出事务）。

| # | 项 | 位置 | 现状与处理指引 |
|---|---|---|---|
| ~~D-01~~ ✅ 已修 | 溢出邮件非事务化（300ms 内存队列） | `core/engine/.../service/OverflowMailSender.kt`（pendingDrafts 异步防抖队列） | ✅ **已修（2026-08-08 架构债务批次）**：草稿入队即持久化（新表 overflow_mail_drafts/direct_mail_drafts）+ 事务世代号（提交钩子恰一次落盘、回滚丢弃）；崩溃恢复经 startGameLoop 统一 drain；DATABASE_VERSION 42→43 |
| D-02 | ~~里程碑失败后无法重试（预存）~~ | ~~`core/engine/.../service/DailySignInService.kt` claimDailySignIn 里程碑循环~~ | ✅ **已关闭（2026-08-07）**——活动与每日签到功能整体移除，待办自然失效 |
| ~~D-03~~ ✅ 已修 | 放背包失败转邮件（体验） | `core/engine/.../domain/disciple/DiscipleFacadeImpl.kt` rewardEquipment/rewardManual 放背包路径 | ✅ **已修（2026-08-08 架构债务批次）**：袋独立存储重构，**容量无上限**——袋条目 payload 持有数据，写入永不因袋满失败；物化迁移兼容老存档 |
| D-04 | 试炼敌人属性从随机变固定（C1 行为变化） | `core/engine/.../domain/battle/HeavenlyTrialService.kt` enemySeed | 敌人生成改确定性派生种子后：同一关卡敌人属性恒定（预览=战斗一致、零全局 RNG 污染）。产品侧确认可接受（固定挑战）；如需随机化，改为进入战斗时取种子本地生成 |
| ~~D-05~~ ✅ 已修 | `GameEngineDiplomacyOps.interactWithSect` 未实现存根 | `core/engine/.../GameEngineDiplomacyOps.kt:70` | ✅ **已修（2026-08-08 架构债务批次）**：死代码删除（与 D-16 一并清理） |
| ~~D-06~~ ✅ 已修 | 全库通配符 import 显式化（约 750 处） | main 482 处 + test 271 处，主要分布：`core.model.*` 125 / `foundation.layout.*` 97 / `runtime.*` 81 / `material3.*` 44 / `core.engine.*` 27 / `core.state.*` 16 / `room.*` 14 / 其余约 25 个包 | ✅ **已修（2026-08-08 架构债务批次）**：自有包约 260 处显式化 + Compose 生态白名单（detekt.yml 21 条 excludeImports）+ baseline 摘除 + 工具 `scripts/expand-wildcard-imports.mjs` |
| ~~D-07~~ ✅ 已修 | `stopGameLoop`/`shutdown` 与 `emergencyRestartGameLoop` 无生命周期互斥（孤儿循环/双速） | `core/engine/.../GameEngineCore.kt`（stopGameLoop/shutdown/emergencyRestartGameLoop） | ✅ **已修（2026-08-08 架构债务批次）**：LoopPhase 状态机 CAS（RUNNING/RESTARTING/STOPPING/STOPPED），并发交错根治；含 GameEngineCoreLifecycleInterleavingTest |
| ~~D-08~~ ✅ 已修 | `ThermalMonitor.start()/stop()` 无调用者——ADPF 热降载实际未接线 | `core/engine/.../perf/ThermalMonitor.kt` start/stop | ✅ **已修（2026-08-08 架构债务批次）**：`startGameLoop` 接线 start/stop（含 emergency 换线程重建语义），热状态不再恒 NORMAL |
| ~~D-09~~ ✅ 已修 | `ThermalMonitor.createHintSession` 异常分支不可测 | `core/engine/.../perf/ThermalMonitor.kt` hintManager（private lazy） | ✅ **已修（2026-08-08 架构债务批次）**：hintManager 改 internal var 接缝注入，Robolectric 补异常/null 分支用例 |
| D-10 | 主线程 HWUI 阻塞无看门狗覆盖 | 三层看门狗（`GameTimeProgressMonitor` / `GameLoopDelegate` HealthCheck / `AlarmWatchdogReceiver`）均只监控"游戏时间推进" | 2026-08-06 #2037 ANR 归因时登记：主线程卡 `syncAndDrawFrame`（HWUI 绘制阻塞，非输入阻塞）无检测无恢复；阻塞中任何恢复都无法执行（事后监控价值有限）。可选演进：`FrameMetricsMonitor` 严重 jank（>50ms）接上报/降载信号（降 targetFps），属长期项 |
| ~~D-11~~ ✅ 已修 | 宗门被夺回时 activeSectId 残留致本宗地图整体变空 | `core/engine/.../engine/BuildingLoadSelfHeal.kt` `purifyStaleActiveSectId`（boot Step 3 读档净化） | 2026-08-06 建筑点击无效批次对抗性审查登记（数据篡改者发现 2），2026-08-06 批次补全修复：读档时 activeSectId 非空且不对应"现存且玩家持有（isPlayerSect/isPlayerOccupied）"宗门 → 归回本宗 ""（worldMapSects 为空同样归 ""）。含 `BootSequenceControllerTest` boot 净化生效守卫 |
| ~~D-12~~ ✅ 已修 | 拖拽中建筑被渲染命令总线双渲染 | `feature/game/.../GameViewModel.kt` movingInstanceId 通道（bus 推送键改 (activeSectId, placedBuildings, movingId) 三元组并排除移动中建筑）+ `MainGameScreen.kt` `LaunchedEffect(movingBuilding)` 单点接线 | 2026-08-06 建筑点击无效批次对抗性审查登记（状态破坏者 F1），2026-08-06 批次补全修复：拖拽期间总线不再在旧位置渲染该建筑（双渲染消除）；点击索引/占用检测与总线同源，拖拽窗口期点不中/可叠建窗口关闭。含 `GameViewModelTest` +3 |
| ~~D-13~~ ✅ 已修 | 旧档 sectId 不匹配建筑完全不可管理 | `core/engine/.../engine/BuildingLoadSelfHeal.kt` `normalizeOrphanBuildingSectIds`（boot Step 3 读档归一化） | 2026-08-06 建筑点击无效批次对抗性审查登记（数据篡改者 F4/状态破坏者 F4），2026-08-06 批次补全修复（主修复）：sectId 非空且 worldMapSects 无对应宗门 → 归入本宗 ""（同步 `SpiritMineSlot.sectId`；worldMapSects 为空跳过防误伤；幂等）。归一化在溢出迁移之前执行（boot Step 3.5），孤儿与既有建筑重叠由迁移拆除退款。含 `BuildingLoadSelfHealTest`（归一化 8 例 + 归一化→迁移守卫）、`BootSequenceControllerTest` boot 生效守卫 |
| ~~D-14~~ ✅ 已修 | 旧档 2×2 矿场移到地图边缘后读档 fixup 撑大越界 | `core/engine/.../config/BuildingConfigService.kt` fixupBuildingSizes（加世界尺寸默认参数，尺寸变化时钳制坐标回地图界内） | 2026-08-06 建筑点击无效批次对抗性审查登记（边界狂魔 F3），2026-08-06 批次补全修复：2×2 矿场 @gridX=126 撑大到 4×4 钳回 124；负坐标钳 0；尺寸不变的健康数据零副作用；钳入 3 格边界区由既有边界迁移（50% 退款）兜底。含 `BuildingConfigServiceFixupTest` 5 例 |
| ~~D-15~~ ✅ 已修 | `AISectBattleProcessor` 795 行/21 函数超限 | `core/engine/.../engine/service/AISectBattleProcessor.kt` | ✅ **已修（2026-08-08 架构债务批次）**：ABC 三块拆分（AISectOccupationResolver 占领结算 + PlayerDefenseProcessor 玩家防守），原类保留编排职责 |
| ~~D-16~~ ✅ 已修 | `generateSectTradeItems` 无 sectId 回退路径死代码链 | `core/engine/.../GameEngineDiplomacyOps.kt:6` → `DiplomacyFacade.kt:12` → `DiplomacyFacadeImpl.kt:38-39` → `DiplomacyService.kt:211`（sectId 默认 null 分支） | ✅ **已修（2026-08-08 架构债务批次）**：死代码链删除（与 D-05 一并清理） |
| ~~D-17~~ ✅ 已修 | 预存 9 个 detekt 违规（baseline 签名失配重新暴露） | `core/engine/.../domain/disciple/DiscipleFacadeImpl.kt:23` LongParameterList；`GameEngineSelfHealOps.kt:31` LongMethod + Cyclomatic + LoopWithTooManyJumpStatements；`ProductionProcessor.kt:503/:623` processAutoAlchemySlot/processAutoForgeSlot LongMethod；`ProductionCoordinator.kt:424` TooGenericExceptionCaught | ✅ **已修（2026-08-08 架构债务批次）**：拆函数/抽共用守卫/baseline 签名更新，9 条清零 |
| D-18 | 灵田收获对抗性审查不修 5 项（记录在案） | `ProductionProcessor.kt` 收获路径（`buildHarvestMaturityContext`/`buildHarvestHerbStore`/`updateSlotAfterHarvest`） | 2026-08-07 灵田收获批次对抗性审查（4 Agent）确认不修：① aura 索引 `associate` vs 原 `find` 查找一致性问题（仅损坏数据可达）；② 续种不刷新 growTime/expectedYield（无消费者，种植时固定）；③ completionMonth 不含加速（无消费者）；④ F5 计数语义（guideCounters HERBS_HARVESTED 与 annualHerbCount 计数口径，既有设计 T4 锚定）；⑤ add 对锁定堆叠合并（store 通用被动入账语义，全系统一致）。如后续需求变化（如续种刷新产量）单独评估 |
| D-19 | 灵泉灌溉政策真实产量 +15% 数值变更（本次仅改文案） | `core/domain/.../GameConfig.kt:707`（SPIRIT_SPRING_YIELD）+ `feature/game/.../TianshuHallDialog.kt:411` | 2026-08-07 灵田收获批次明确不做数值变更：政策实际为生长加速乘区（非产量），文案已更正为"灵草生长速度+15%"消除误导。改真实产量属平衡性决策（政策永久生效、expectedYield 种植时固定、影响中后期草药经济），需产品定夺后单独立项 |
| D-20 | 收获是否移出月结事务（性能进一步保障） | `core/engine/.../service/ProductionProcessor.kt` processSpiritFieldHarvest（月结 `systemManager.onMonthlyEvent` 事务内调用） | 2026-08-07 灵田收获批次主因修复：复杂度 O(n×(d+b+n+h)) → O(n+d+b+h)（n=300 时 <1ms 量级），已不再构成 UI 阻塞，事务结构本次不动（外科手术式）。若极端存档（数千地块）仍有卡顿疑虑，可单独立项把收获移出月结事务（涉及惰性结算引擎架构调整） |
| ~~D-21~~ ✅ 已修 | 商人交易价格校验缺口（负价/0 价商品 + 同类收购缺口） | `core/engine/.../domain/inventory/InventoryFacadeImpl.kt` buyMerchantItem / sellToMerchant | ✅ **已修（2026-08-09 批次）**：buyMerchantItem 入口校验 `price <= 0 \|\| quantity <= 0` 拒绝购买 + DomainLog.w；**举一反三同类缺口**：sellToMerchant 收购路径先移除仓库物品、后 wallet.add（`amount<=0` 静默拒绝）入账 → 负价/0 价收购致玩家物品丢失无补偿，入口校验 `price <= 0` 拒绝。实测修正登记描述：负价购买实际被 `SpiritStoneWallet.deduct` 的 `amount<=0 → Invalid` 兜底拒绝（事务回滚），原"灵石反增"不成立，真实问题是防御纵深缺口。AutoBuyService（deduct 失败即 skip 无状态变更）与上架（引擎定价恒正）核查安全。含 `MerchantPriceValidationTest` 6 条守卫 |
| ~~D-22~~ ✅ 已修 | 商人刷新后 selectedItem 失效购买静默失败 | `feature/game/.../dialogs/MerchantDialog.kt:63/132/231`（selectedItem 状态 / refreshTravelingMerchantManual 刷新按钮 / onConfirm buyFromMerchant） | ✅ **已修（2026-08-09 批次，最小修复）**：刷新按钮点击后清空 `selectedItem` + `buyQuantity`（对齐切 Tab/切筛选先例；刷新后商品可能被替换/变价，旧选中按旧价预期扣款也是体验问题）；引擎侧 `buyMerchantItem` 商品不存在分支补 DomainLog.w 兜底日志。静默失败路径正常不可达，未做接口签名改造（用户决策） |
| ~~D-23~~ ✅ 已修 | MerchantDialog.kt:140 预存死条件判断 | `feature/game/.../dialogs/MerchantDialog.kt:140`（`if (viewModel != null)` 恒 true） | ✅ **已修（2026-08-09 批次）**：去除判断直接调用 viewModel（Image 块内容不变，仅删条件与缩进） |
| ~~D-24~~ ✅ 已关闭 | 最终 CI 变体完整验证未跑（testReleaseUnitTest + kover 覆盖率） | 2026-08-09 一月卡顿批次验证只跑了 `test`（debug 变体）+ detekt + lintRelease + compileReleaseKotlin | ✅ **已关闭（2026-08-09 批次）**：`./gradlew.bat testReleaseUnitTest --max-workers=1` + `koverHtmlReport` + `detekt` + `lintRelease` 全绿。精确计数：**394 测试类 / 5957 用例 / 0 失败**（一月卡顿批次 "2258 tests" 计数修正——最终代码重跑后实测）。覆盖率：koverHtmlReport 生成成功，engine 行覆盖约 33%（历史预存缺口，不属本批次阻塞项，详见 P- 批次记录） |
| D-25 | ShardedSlotLock 锁粒度（对抗性审查接受不修，记录在案） | `core/engine/.../repository/ProductionSlotRepository.kt` ShardedSlotLock（按 buildingType+slotIndex 分片） | 2026-08-09 一月卡顿批次对抗性审查（状态破坏者视角）确认不修：理论上有并发逐槽更新与 batchUpdate 回滚的交错窗口，但引擎为单写线程模型（stateStore.update ReentrantLock 串行化），不触发；回滚覆盖窗口比"内存/DB 分叉"危害小得多。防御性设计债，如未来多写者架构立项时评估 |

## 实施记录（2026-08-08：D-01 / D-03 / D-05~D-09 / D-15~D-17 十项）——已完成

> ✅ 全部 10 项已于 2026-08-08 完成并提交（commit `3570cb97`，267 文件 +14628/-3033）。验证：串行全量测试 `./gradlew.bat test --max-workers=1` BUILD SUCCESSFUL、全模块 detekt 通过、compileReleaseKotlin 通过。详细技术说明见 CHANGELOG.md「架构债务清理（2026-08-08）」。

| 项 | 用户决策 | 实施要点 |
|---|---------|---------|
| D-03 | **彻底重构**（容量无上限） | 袋条目 payload 持有数据（Equipment/Stacked），写入永不因袋满失败；物化迁移兼容老存档 + 悬空条目删除；DATABASE_VERSION 不变 |
| D-01 | **彻底根治** | 草稿入队即持久化（新表 overflow_mail_drafts/direct_mail_drafts）+ 事务世代号（提交恰一次落盘、回滚丢弃）；崩溃恢复经 startGameLoop drain；DATABASE_VERSION 42→43 |
| D-07 | 状态机互斥 | LoopPhase CAS（RUNNING/RESTARTING/STOPPING/STOPPED），孤儿循环/双速根治 |
| D-15 | ABC 三块拆分 | AISectOccupationResolver（占领结算）+ PlayerDefenseProcessor（玩家防守），原类保留编排 |
| D-08+D-09 | 接线 + internal 接缝 | startGameLoop 接线 start/stop（换线程重建）；hintManager 改 internal 接缝 + Robolectric 补分支用例 |
| D-05+D-16 | 删除 | interactWithSect / generateSectTradeItems 死代码链清理 |
| D-17 | 真修 + baseline | 9 条预存违规清零（拆函数/抽共用守卫/baseline 签名更新） |
| D-06 | 自有包显式化 + Compose 白名单 | detekt.yml 21 条 excludeImports + 自有包约 260 处显式化 + 工具 `android/scripts/expand-wildcard-imports.mjs` |

**不纳入**：D-04（产品已确认固定挑战）、D-10（HWUI 看门狗长期项）、D-18/D-19/D-20（已决策不修）、P-16/P-18/P-19（待真机验证）。

### 途中发现（登记在案）

- `withOverflowMailSuppressed` 8 个调用点在 D-01 新机制下语义变为纯"凭据类不转邮件"，是否保留列入后续审计
- D-03 途中修复预存复制 bug：`DiscipleLifecycleProcessor.returnEquipmentToWarehouse` Failure(Full) 时邮件已发但实例保留（已随 D-03 处理）

## 实施记录（2026-08-09：D-21 / D-22 / D-23 三项 + D-24 关闭）——已完成

> ✅ 三项已于 2026-08-09 完成（商人交易防御批次）。验证：`MerchantPriceValidationTest` 6 条全绿 + 串行全量 `testReleaseUnitTest` + `koverHtmlReport` + compileReleaseKotlin + lintRelease（D-24 关闭）。

| 项 | 实施要点 |
|---|---------|
| D-21 | 入口校验 `price <= 0` 拒绝购买 + DomainLog.w；**举一反三同类缺口**：sellToMerchant 收购路径防物品丢失（先移除后入账、wallet.add 静默拒绝），入口校验拒绝。实测修正登记描述（负价购买实为静默失败而非灵石反增，wallet.deduct 兜底）。AutoBuyService/上架核查安全 |
| D-22 | 最小修复（用户决策）：刷新按钮清空 selectedItem + buyQuantity（对齐切 Tab/切筛选先例）；引擎侧商品不存在分支补兜底日志。未做接口签名改造 |
| D-23 | 删除 `if (viewModel != null)` 恒 true 死条件 |
| D-24 | CI 精确变体 `testReleaseUnitTest` + `koverHtmlReport` + lint 全绿（补 4.00.93 一月卡顿批次未跑尾巴） |

**不纳入**：P-16/P-18/P-19（待真机验证，保留验证指引）、D-25（对抗性审查接受不修）、D-04/D-10/D-18/D-19/D-20（已决策）、维持现状决策项（W4/拉条移植/P6/P-11 附带）。
