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
- [待完成项登记（2026-08-09 清理归档）](#待完成项登记2026-08-09-清理归档全部条目已处置完毕待办表清空)
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

> 2026-08-09 归档：2026-08-02~2026-08-09 登记的全部待办项（T/P/D 系列）已于当日处置完毕，登记表清空。
> 2026-08-10 复启：测试 mock 模式根治批次（commit `1e563548`）途中发现 2 项待办（D-26 / D-27），登记见下。
> 处置惯例：已完成项的实施要点记入对应批次"实施记录"段落 + CHANGELOG；决策不修项记录决策理由；待真机验证项（非待办）归入"待真机验证指引"。
> 历史归档：D-01~D-25 处置记录见 CHANGELOG 4.00.86~4.00.93 与下方各"实施记录"段落。

### 途中发现待办 D 系列登记表（2026-08-10 复启）

| # | 来源 | 待办内容 | 决策 | 状态 |
|---|---|---|---|---|
| D-26 | 测试 mock 批次（commit `1e563548`） | `GameStateRepository`（app 模块 **final 具体类**）被 `GameStateStoreImpl` 相关测试裸 `mock(GameStateRepository::class.java)` 注入——与本次根治的 `ProductionSlotRepository` 同款风险：final 类 mock 拦截依赖类加载时机，顺序敏感 flaky（显式 stub 也救不了，stub 注册后的第一次调用可能真实执行方法体）。**治理方向**：改用真实实例（参考 engine 测试 `TestMockSupport.testProductionSlotRepository()` 工厂模式）或 `mockSmart` + doReturn 风格 | 待定 | 待办 |
| D-27 | 测试 mock 批次（commit `1e563548`） | 测试规范固化：新 Service 测试若需 `ProductionSlotRepository` 必须走共享工厂 `com.xianxia.sect.core.engine.testProductionSlotRepository()`（真实实例 + mockSmart 端口 + `loadSlots` 预填充），禁止自行 `ProductionSlotRepository(dao = mock(), ...)` 内联构造；返回 sealed 类型（`DomainResult` / `DeductResult`）的 stub 统一 doReturn 风格（ByteBuddy 无法代理 sealed）。可作为 `rules/testing.md` 或代码审查清单条目固化 | 待定 | 待办 |
| D-28 | Godot 对标重构（commit `e8c7bb97`） | **网络层 Gson 遗留清理**：项目其余处统一用 kotlinx.serialization，仅网络层遗留 Gson（knowledge-base iOS 基线表已标注"遗留"）——两套序列化并存易错；统一为 kotlinx.serialization（Retrofit converter 替换）；详见 platform-abilities.md G7 | 待定 | 待办 |
| D-29 | Godot 对标重构（commit `e8c7bb97`） | **DataStore/MMKV 双存储并存合并**：两套本地 K-V 并存；MMKV 已跨平台、DataStore Android 独占——偏好设置逐步迁入 MMKV，移除 DataStore 依赖（iOS 迁移前置项之一）；详见 platform-abilities.md G8 | 待定 | 待办 |
| D-30 | 广告 SDK 重复初始化修复批次（2026-08-15） | **`GameConfig.initialize` / `BuildingConfigService.initialize` 无幂等守卫**：每次游戏内读档/重开（boot）经 `ResourcePreloader.preloadGameResources` 重复执行——`GameConfig.initialize` 覆盖赋值（幂等但无守卫）、`BuildingConfigService.initialize` 每次重读 assets JSON（`config/buildings.json` 重复 I/O）。低危性能开销，非状态损坏。**治理方向**：进程级幂等守卫（参照本次 `SdkInitGuard` 模式），配置加载仅首次真正执行 | 待定 | 待办 |
| D-31 | 广告 SDK 重复初始化修复批次（2026-08-15） | **`GameEngineCore`（@Singleton）初始化状态被 `GameForegroundService` 生命周期驱动**：`onDestroy → shutdown()` 重置 `isInitialized=false` 并 `systemManager.releaseAll()`，每次退出重进游戏完整重跑 `systemManager.initializeAll()`（全部 GameSystem 重新 initialize/release 循环）；服务 START_STICKY 被系统重建时同样触发。当前各 GameSystem.initialize 均幂等（仅日志/无副作用），非 bug，但属架构级设计取舍。**治理方向**：引擎初始化状态改由进程级持有（Application 统一管理生命周期），Service 仅控制循环启停（start/stop/pause/resume），切断"每次进出游戏重复初始化引擎全部系统"的链路 | 待定 | 待办 |
| D-32 | 广告 SDK 重复初始化修复批次（2026-08-15） | **`MainActivity.kt` L280 预存超长行**：`Log.e(TAG, "StorageFacade initialization failed after $maxRetries attempts, proceeding with empty cache")` 长度 121 超规范 120 上限 1 字符（detekt baseline 内容忍）。**治理方向**：日志字符串拆分换行 | 待定 | 待办 |
| D-33 | 荣耀 X70 键盘频闪根治批次（commit `45203035`） | **编译恒真死条件 ×2**（compileReleaseKotlin warning "Condition is always 'true'"）：`SectTradeDialog.kt:86`、`SpiritMineDialog.kt:91`——与 D-23（已修复 `if (viewModel != null)` 恒 true）同类先例。**治理方向**：删除死条件分支，保留真实语义 | 待定 | 待办 |
| D-34 | 荣耀 X70 键盘频闪根治批次（commit `45203035`） | **lint `ConfigurationScreenWidthHeight` 全库 7 处**：`screenWidthDp`/`screenHeightDp` 应改用 `LocalWindowInfo.current.containerSize`（Android 15 edge-to-edge 下两者 insets 行为差异且取整精度不同）。位置：core/ui `StandardPromptDialog.kt` 4 处（211/212/423/424 行）+ feature/game `DetailManualSection.kt:73`、`DiscipleChatDialog.kt:385`、`SectDiplomacyDialog.kt:547`。**治理方向**：尺寸计算迁移 WindowInfo，注意 InlineStandardPromptDialog 的 remember 缓存语义同步保留 | 待定 | 待办 |
| D-35 | 荣耀 X70 键盘频闪根治批次（commit `45203035`） | **lint `ComposableNaming` 路由文件约 32 处**：`DialogFeatureRoutes.kt`/`DialogFunctionalBuildingRoutes.kt`/`DialogMainTabRoutes.kt`/`DialogProductionRoutes.kt`/`DialogSystemRoutes.kt` 的 route 函数以大写开头命名（被误判为工厂/类语义）。**治理方向**：统一改小驼峰 route 命名（纯重命名，无行为变化），或按项目先例集中 Suppress 并在审查清单注明 | 待定 | 待办 |
| D-36 | 荣耀 X70 键盘频闪根治批次（commit `45203035`） | **lint `UseKtx` 全库约 22 处**：`Bitmap.createBitmap` 等平台 API 改用 KTX 扩展。位置：core/ui `AtlasPacker.kt:83`、app `AdServiceImpl.kt:125` + feature/game 约 20 处（`DetailBasicInfoSection`/`DiscipleComponents`/`HerbGardenDialog`/`LawEnforcementHallDialog`×2/`LeaderboardManager`/`LibraryDialog`/`PeakScreenComponents`×2/`ProductionComponents`/`SectAtlasAssembler`/`SettingsTab`/`SoftwareCanvasBackend`×2/`SpiritMineDialog`×2/`SpiritRootWashDialog`/`TapCloudSaveManager`×4/`WarehouseDialog`/`WorldMapSectDetailDialog`）。Productivity 级纯风格。**治理方向**：批量机械替换 | 待定 | 待办 |
| D-37 | 荣耀 X70 键盘频闪根治批次（commit `45203035`） | **lint `AutoboxingStateCreation` 13 处**：`mutableStateOf(装箱类型)` 应改 `mutableIntStateOf` 等 primitive 专用 API（`DiscipleChatDialog.kt:220`/`HeavenlyTrialBattleDialog`×2/`HeavenlyTrialCombatScreen`×2/`HeavenlyTrialDiscipleDialog`/`HeavenlyTrialViewModel`×3/`MissionHallDialog`/`PatrolTowerDialog`/`ResidenceDialog`/`SectDiplomacyDialog`）。轻微重组开销。**治理方向**：逐个确认语义后替换 | 待定 | 待办 |
| D-38 | 荣耀 X70 键盘频闪根治批次（commit `45203035`） | **lint `ModifierParameter` 4 处**：Composable 可选参数中 `modifier: Modifier = Modifier` 未置首位——core/ui `GridRow.kt:34` + feature/game `GameActionButtons.kt:123`、`HeavenlyTrialComponents.kt:49`、`SectDiplomacyDialog.kt:352`。**治理方向**：参数重排（调用点全部具名参数即可零影响） | 待定 | 待办 |
| D-39 | 荣耀 X70 键盘频闪根治批次（commit `45203035`） | **lint 零散杂项 8 处**（各类 ≤2 处）：`LocalContextResourcesRead` ×2（`MainGameScreen.kt:427`/`MapBackground.kt:27`）、`MutableCollectionMutableState`（`HeavenlyTrialCombatScreen.kt:71`）、`DiscouragedApi`（`ResourcePreloader.kt:187`）、`IconDuplicates`（`ui_tooltip.webp` 与 `dialog_box.webp` 重复）、`ViewConstructor`（`NativeSurfaceView.kt:49`）、`ClickableViewAccessibility`（`NativeSurfaceView.kt:910`）。**治理方向**：逐条评估，个别（ViewConstructor 原生视图构造）可豁免登记理由 | 待定 | 待办 |
| D-40 | 荣耀 X70 键盘频闪根治批次（commit `45203035`） | **依赖与 SDK 侧 warning**：app `GradleDependency` ×9（`libs.versions.toml` 18/26/50/51/53/55/57/58/59 行依赖版本更新提示——例行维护）+ `Aligned16KB` ×3（Bugly `libBugly.so`、Dirichlet Ad SDK `libdirichlet.so` 未 16KB 对齐，Android 15+ 内存页对齐要求，需等 SDK 方发布新版本）。**治理方向**：依赖升级随版本发布批次例行处理；第三方 so 记录跟进 SDK 更新日志 | 待定 | 待办 |
| D-41 | 荣耀 X70 键盘频闪根治批次（commit `45203035`） | **测试编译 warning 批量**：`createAndroidComposeRule` deprecated ×4 文件（core/ui `StandardPromptDialogTest`/`SmallScreenDialogTest` + feature/game `TalentDetailDialogWashTest`/`MerchantDialogJadeFlowTest`——应迁 `androidx.compose.ui.test.junit4.v2`，但 v2 用 StandardTestDispatcher，依赖立即执行的既有用例需逐个核验时序）；`ExperimentalCoroutinesApi` opt-in 与 `Unchecked cast` 批量（`GameViewModelMovingBuildingBusTest`/`GameViewModelTest`/`SaveLoadViewModelLoadTest`/`BuildingDelegateOverlapTest`）。**治理方向**：Compose 测试规则迁移专项（防时序语义变化引入 flaky）+ opt-in 标注收敛 | 待定 | 待办 |
| D-42 | 广告 SDK 初始化时机修复批次（2026-08-15） | **游戏内防沉迷合规回调不生效**：`MainComplianceCallback` 绑定 MainActivity 实例，登录用户经 `launchGame` 进入 GameActivity 后 MainActivity 已 `finish()`，`runOnUiThreadIfAlive` 的 `isFinishing/isDestroyed` 检查直接丢弃后续全部合规回调（时长限制 CODE_DURATION_LIMIT / 时间限制 CODE_PERIOD_RESTRICT / 年龄限制等）——游戏内防沉迷限制无法弹出提示。改动前即存在，本次初始化时机调整后仍保持。**治理方向**：合规回调宿主从 MainActivity 迁移为进程级持有（转发到当前前台 Activity），或 GameActivity 独立注册合规回调并展示限制对话框 | 待定 | 待办 |
| D-43 | 广告 SDK 初始化时机修复批次（2026-08-15） | **CLAUDE.md 测试命令与项目配置不一致**：`./gradlew.bat test --tests "..." --max-workers=1` 在当前 Gradle 8.14.5 配置下报 `Unknown command-line option '--tests'`（`:app:test` 为 AGP 聚合任务非标准 Test 任务）；且未模块限定时 `--tests` 过滤会波及 core:data 等模块触发 "No tests found" 失败。实测可用：`./gradlew.bat :app:testReleaseUnitTest --tests "..." --max-workers=1`。**治理方向**：CLAUDE.md 命令更新为模块限定写法 | 待定 | 待办 |

### 待真机验证指引（2026-08-09 归档保留，真机验证时查阅）

### 待真机验证指引（2026-08-09 归档保留，真机验证时查阅）

| # | 项 | 验证指引 |
|---|---|---|
| P-16 | UI 迁移真机冒烟 | SettingsTab/DiscipleDetailScreen/OverlayDialogRouter：4 个迁移弹窗（其他设置/年俸/存档管理/更新日志）逐一打开关闭 + OverlayDialogRouter 34 分支逐项打开一次；判定：无崩溃/白屏/交互完整/叠层路由正常 |
| P-18 | 排行榜 rank 0/1 起始语义 | `TapTapLeaderboardApi.kt`：首名显示 #1 且次名重复 #1 → 服务端 1 起始，移除归一化；次名 #2 → 保留现状。抓原始 rank 与显示值对照 ≥3 次 |
| P-19 | 一月卡顿根治性能量化（代码已提交 commit `7dae538b`） | 真机装 4.00.93 包跨过至少 1 个游戏年，抓 logcat：`CultivationEventMonthlyOps.kt` 各处理器 `op[...] took Nms` 耗时 + `GameEngineCore.kt` "Tick over budget" 是否出现。判定：1 月 tick 收敛至单帧级（目标 <100ms）；AI 修炼日志仅出现在 3/6/9/12 月。**目前仅算法复杂度论证，无实测数据** |

> ~~途中发现待办 D 系列登记表（D-01~D-25）已于 2026-08-09 全部处置完毕并归档清空：已完成/已关闭项的实施要点见下方"实施记录"段落与 CHANGELOG 4.00.86~4.00.93；决策不修项（D-04 / D-10 / D-18~D-20 / D-25）的决策理由见各批次实施记录"不纳入"说明。~~

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

**不纳入**：P-16/P-18/P-19（待真机验证，指引保留于上方"待真机验证指引"小节）、D-25（对抗性审查接受不修）、D-04/D-10/D-18/D-19/D-20（已决策）、维持现状决策项（W4/拉条移植/P6/P-11 附带）。

> **2026-08-09 归档**：待完成项登记章节全部条目（T/P/D 系列）已处置完毕并清空，维持现状决策表 / 途中发现待办 D 系列表 / 待真机验证表均已清理，仅保留待真机验证指引（P-16/P-18/P-19）。历史记录见 CHANGELOG 4.00.86~4.00.93 与上方各实施记录段落。
