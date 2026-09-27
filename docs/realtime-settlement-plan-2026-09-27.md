# 结算改造方案：取消旬/月/年边界，改为现实时间连续结算

> **决策分级：架构级重构**（`rules/design-plan-review.md` 第六节）。
> 判定依据：同一模式（"结算绑定游戏日历离散边界"）在 L1/L2/L3/L4 **四层全部存在**（≥3 处）＋
> 触及时间轴这一跨平台根本契约（Android/iOS）＋ 触及存档 schema 与 3000+ 结算相关测试的黄金序列。
> **本文档为最终态方案，一次性覆盖全部影响点（引擎/存储/UI/测试/旧档兼容/离线语义），禁止分阶段交付**
> （边界见 `rules/design-plan-review.md` 第零节"边界说明"）。
>
> 状态：**待用户批准后实施**（当前仅产出方案，未改动任何代码）。
> 日期：2026-09-27 ｜ 基线分支 `feat/gacha-m0-m1` ｜ 基线提交 `866ec165f`

---

## 一、背景与目标

### 1.1 需求要点（用户口径）

- 调查游戏结算功能，找出**按照旬、月、年结算**的全部结算项。
- 给出实施文档，把这些结算**均改为现实时间结算**。
- 已确认口径（本次会话问答）：**A 案** —— 消除旬/月/年边界，让结算按现实秒连续/每帧结算；
  覆盖范围 = **全部结算项**（每旬 + 月变 + 年变 + 惰性生产 + 离线收益）。

### 1.2 现状事实（已实测，非推测）

当前结算已经是"现实墙钟驱动"，但**触发点是游戏日历的离散边界**——这两件事必须分清：

| 事实 | 证据 |
|---|---|
| 时间推进由现实单调时钟累积驱动（`elapsedRealtime`，不受改系统时间影响） | `GameTimeClock.kt:22-38,143-174`；`settlement.h:10-28` |
| 1x 下 **2 现实秒 = 1 旬**；1 月 = 3 旬 = 6 现实秒；1 年 = 12 月 = 72 现实秒 | `GameTimeClock.kt:224`（`MS_PER_PHASE_1X=2000`）；`docs/knowledge-base.md:687` |
| 逻辑 tick 固定 100ms，1 帧最多 5 步 | `GameEngineCore.kt:302,312`；`engine_loop.h:39-43` |
| 每旬结算（L1）在**旬边界**触发，一次结算整整一旬（2 秒）的量 | `settlement.h:127-150`；`phase_settlement.h:1078-1300` |
| 月变（L3）在**下旬**触发；年变（L4）在**12 月下旬**触发 | `time_system.h:48-56`；`settlement.h:144-149` |
| 月/年边界经标量通道回传 Kotlin，由 `processMonthYearChange` 编排出整个月的活 | `GameEngineCoreAuthoritativeOps.kt:78-84`；`GameEngineCorePausOps4.kt:92-140` |
| 惰性生产（L2）按 `游戏年月整数差` 差分，**无法表达不足 1 月/1 旬的剩余** | `LazyEvaluationDispatcher.kt:10-40`；`GameConfig.kt:148-150` |
| 追补上限 `maxPhasesPerTick = 3 × speed`：超限**丢弃余量**（离线/挂起时间被丢弃） | `GameTimeClock.kt:157-172`；`settlement.h:76-84` |
| **没有离线收益机制**：后台纯暂停，`consumeDeadTime()` 不累积 | `docs/knowledge-base.md:685`；`docs/save-system-audit-2026-09-21.md:397` |
| 代码里**已经存在"每秒"口径**，只是被换算回每旬 | `EquipmentNurtureSystem.kt:10-14`（`AUTO_EXP_PER_SECOND`）；`ManualProficiencySystem.kt:93-99`（`perSecond × 2000/1000`） |

> 结论：本方案不是"把游戏时间换成现实时间"（那已经是现状），而是
> **把结算的单位从"日历边界事件"换成"现实时间积分"**，让游戏日历退化为**展示层与叙事层**。

### 1.3 成功标准

1. **单位一致性**：所有结算量以"每游戏秒"为单位表达（`perPhase / 2.0`、`perMonth / 6.0`、
   `perYear / 72.0`），不再有以旬/月/年为单位参与积分的量。
2. **无离散等待**：收益类状态在任何时刻都是"当前时刻的精确值"，
   不需要"等到下旬/月末/年末"才落地；UI 读数不再出现"差一点但结算不给"。
3. **精度补齐**：惰性生产/建造/种植剩余时间可表达到毫秒，不再被向上取整到月/旬。
4. **判定确定性不变**：突破/死亡/随机事件等离散判定的**判定次数与顺序**可复现，
   RNG 分区序列与改造前逐位等价（`Diff*` 对拍可证）。
5. **离线语义显式**：离线是否产出、上限多少、由什么时钟计量 —— 有明确规则并落地，
   不再依赖"追补上限静默丢弃"这一副作用。
6. **存档不丢**：旧档（当前版本）升级后状态**零丢失**，改造当天各面板读数与旧版口径一致。

### 1.4 已确认的两项产品口径

以下两项于本次会话由用户拍板，**已作为实施基线**（方案按下述口径落地）：

| 口径 | **已确认值** | 决策依据 / 影响面 |
|---|---|---|
| **离线收益上限** | 12 现实小时全额（1x 速率），超出后按 **50% 速率**继续累积至 **24h 硬顶**，再超出不再累积 | 复用 `rules/economy-design.md:37` 既有预留；避免"杀进程挂机"成为最优策略。影响：`economy-design.md` §4、`expansion-playbook.md` 离线收益预留（需同批改写） |
| **自动存档频率与触发源** | **删除游戏月月变触发**（原 = 每 6 游戏秒，随 1x/2x 变 6s/3s）；改为**现实墙钟每 10 秒一存**（`onStop` 后台保存保留） | 与"结算改现实时间"同一时间基：现实节拍与游戏速度、暂停、游戏日历全部解耦；2x 下不会变成 5 秒一存。**本项已实施**（见 §2.6） |

> 备选方案（未采纳）：5 秒节拍——全量快照 + Room 事务的写入时长与存档大小成正比，
> 5 秒在后期大型存档下成为持续 I/O 与卡顿源；10 秒在"丢失窗口"与"写入开销"间取平衡（约减半磁盘/电量占用）。
> "仅脏时写盘"需新增脏标记链路，收益是省白写（约 25% 的节拍落在两次结算之间无实质状态变化），
> 但会引入新的一致性面；本次不引入，登记为技术债 D6。

---

## 二、技术方案

### 2.1 核心设计：双轨时间

把当前"单轴（游戏日历）× 单粒度（旬）"改成**双轨**：

```
┌─ 轨道 A：连续积分轨（新增，承载全部资源/进度） ────────────────┐
│  状态量 = 速率(每游戏秒) × 现实时间差(游戏秒)                 │
│  标量状态：elapsedGameMs（累计游戏毫秒，单调，双端单一真相源）  │
│  离散状态：quantity（资源/经验/熟练度，double）                │
│  结算时机：引擎 tick（100ms）差分，无边界概念                  │
└───────────────────────────────────────────────────────────┘
┌─ 轨道 B：离散判定轨（保留，但脱离结算职责） ────────────────────┐
│  只承载"必须按整数次数发生"的事件：突破/死亡/老化/随机事件/    │
│  月变叙事/年变叙事                                            │
│  触发量：derived（年/月/旬仍由 elapsedGameMs 派生，用于展示与  │
│  叙事文字），判定次数由"事件计数"保证，不依赖帧率             │
└───────────────────────────────────────────────────────────┘
```

**关键不变量（本方案的架构契约）**：

- **INV-1｜游戏日历是派生投影，不是推进源。** `gameYear/gameMonth/gamePhase` 由
  `elapsedGameMs`（或绝对月份 + 月内月相）**纯函数派生**；唯一权威时间轴是
  单调时钟累计的游戏毫秒。存档仍持久化三个日历字段（向后兼容与 UI 免改），
  但**任何结算逻辑不得再以它们作为积分单位**。
- **INV-2｜时间累积不受追补上限影响。** 现有 `phaseCap` 丢弃余量的行为
  （`GameTimeClock.kt:166-172`）**不得**施加于连续积分轨；积分轨消费的是
  "现实时间差 × 速度"的**未截断值**。追补上限只继续作用于轨道 B 的判定次数（防单帧判定风暴）。
- **INV-3｜离散判定的次序由事件计数决定，与帧率/档位无关。**
  每次判定消费 RNG 的次数是常量，判定触发点由
  `floor(总游戏时间 / 周期)` 的整数差决定（而非"每帧判定一次"）。
  **有利事实**：每旬结算中**只有突破路径消耗 RNG**（`BREAKTHROUGH` 分区，
  每次尝试恰好一次 `nextDouble()`，按 ids 行序）——见
  `phase_settlement.h:44-47,926`。因此只要"突破判定次数与次序"不变，
  RNG 序列天然保持逐位等价，积分轨的引入**不影响 RNG**。
- **INV-4｜年龄是整数量子，不连续化。** 年龄/寿命/年报统计保持离散；
  连续化的只有"寿命积分"这类新增中间量（见 §2.3 例外清单）。
- **INV-5｜平台时间只经端口注入。** C++ 侧禁止读挂钟（`gamecore/AGENTS.md` 已规定）；
  Android/iOS 各自实现 `MonotonicClock` 端口（现有 `MonotonicClock` 已具备，见 `engine_loop.h:77`）。
- **INV-6｜取整模式变更必须显式登记。** 现状多处用"整数截断 + 至少 1"表达单次结算量
  （如 HP/MP 恢复 "恢复量 = maxValue × 0.2 × 1.0 × phases，`toInt` 截断后至少 1"，
  `phase_settlement.h:156-166`）。连续化后改为**小数累积 + 达阈值进位**，
  单位时间产出总量不变但**粒度发生改变（这是本方案的目的，不是副作用）**。所有此类取整点
  必须登记在 §3 清单的"取整模式"列，并由 §5.2 的积分轨等价口径验收。

### 2.2 时间单位常量栈（单一真相源）

新增 `android/core/domain/.../GameConfig.kt` 时间常量族（C++ 侧同源常量
`gamecore/system/time_units.h`，双端各自由守卫测试锁定，沿 `maxPhasesPerTick` 既有先例 `settlement.h:34-40`）：

| 常量 | 值 | 含义 | 替换的前身 |
|---|---|---|---|
| `MS_PER_GAME_SECOND` | 1000 | 游戏秒定义 | — |
| `GAME_SECONDS_PER_PHASE` | 2.0 | 1 旬 = 2 游戏秒（1x） | `MS_PER_PHASE_1X=2000` |
| `GAME_SECONDS_PER_MONTH` | 6.0 | 1 月 = 6 游戏秒 | 隐式（3 旬） |
| `GAME_SECONDS_PER_YEAR` | 72.0 | 1 年 = 72 游戏秒 | 隐式（36 旬） |
| `PHASES_PER_MONTH` | 3 | **仅用于日历派生/叙事**，不参与积分 | `GameConfig.kt:149` |
| `MONTHS_PER_YEAR` | 12 | 同上 | `GameConfig.kt:150` |

**换算规则（全仓唯一口径）**：

```kotlin
// 每旬量 → 每秒量
fun perPhaseToPerGameSecond(perPhase: Double): Double = perPhase / GAME_SECONDS_PER_PHASE   // ÷2.0
// 每月量 → 每秒量
fun perMonthToPerGameSecond(perMonth: Double): Double = perMonth / GAME_SECONDS_PER_MONTH   // ÷6.0
// 每年量 → 每秒量
fun perYearToPerGameSecond(perYear: Double): Double = perYear / GAME_SECONDS_PER_YEAR       // ÷72.0
```

> 既有代码已有同构先例（`ManualProficiencySystem.kt:93-99` 就是 `perSecond × 2.0`），
> 本方案把这些先例**升格为唯一口径**，其余全部收敛过来。

### 2.3 四层结算的改造映射

| 层 | 现状（边界事件） | 改造后（现实时间） | 判定 |
|---|---|---|---|
| **L0 时间推进** | 累积游戏毫秒 → 产出 N 旬 → 逐旬推进 `gamePhase` | 累积游戏毫秒（**不截断**）→ 派生日历投影 + 计算本 tick 游戏秒增量 Δt | 改造 |
| **L1 每旬（`phase_settlement.h`）** | 每 2 秒一次性结算整旬量 | **逐项拆分**：积分型改为 `rate_per_gs × Δt`；判定型保留（触发点由 `事件计数` 决定，见 INV-3） | 改造 |
| **L2 惰性生产（`LazyEvaluationDispatcher`/`ProductionSlot`）** | `游戏年月整数` 差分（`lastSettledMonth`、`completionMonth`+`completionPhase`） | `绝对游戏毫秒` 差分；槽位存 `durationGameMs` / `startAtGameMs` / `completeAtGameMs` | 改造 |
| **L3 月变（`month_settlement.h`）** | 月末一次性：政策扣除/月效/灵矿/丹药衰减/AI/任务刷新/秘境/偷盗/购买 | 积分型移入 L1 连续轨（÷6.0）；**周期判定型**保留月边界的"判定次数"但脱离资源积分；叙事与事件流保留 | 拆分改造 |
| **L4 年变（`year_settlement.h`）** | 年末一次性：老化死亡/年报/年俸/驻军/AI 招募/外交衰减/商人收购 | 积分型（年俸可折算）移入连续轨（÷72.0）；**老化/死亡/年报**保留整数年判定；**T2 延迟组**从"入队 drain"改为"独立调度" | 拆分改造 |
| **离线** | 无（后台纯暂停，追补超限丢弃） | 显式 `离线时段 ∩ 上限` 注入连续积分轨；日历投影同步跳变 | 新增 |

**不可连续化清单（本方案的显式例外，逐条给理由）**：

| 项 | 为何不能连续化 | 改造后的处理 |
|---|---|---|
| 弟子年龄 / 寿命 / 死亡判定 | 年龄是整数量子；`AISectDiscipleManager` 等按整年换算（`PHASES_PER_MONTH=3` 折算），非整数年龄会破坏生成器一致性与存档兼容 | 保持离散；仅把"死亡判定时刻"从"年末"改为"寿命积分跨过阈值时"，判定输入仍是整数年 |
| 突破判定 | 结果是"成功/失败"的二值事件，附 RNG 消费与 UI 通知；连续化会改变 RNG 序列长度 | 保留离散；判定周期不变（每旬），触发点改由事件计数保证（INV-3） |
| 丹药品阶 roll / 偷盗 / 赠送 / 执法 / 叛逃 | 同上（含 RNG 与 UI 通知面） | 保留离散 |
| 年报（`YearlyReport`） | 玩家可见的"年度"聚合语义，连续化后语义消失 | 保留年度聚合；数据源改为从连续轨按年区间汇总 |
| 玉符（在线时长货币） | 已是墙钟货币且**已获豁免**（`docs/knowledge-base.md:722`） | 不动（本方案不碰玉符） |

### 2.4 关键类与接口变化

| 模块 | 现状 | 改造后 |
|---|---|---|
| C++ `system/settlement.h` | `advance()` 产出旬数并丢弃超限余量 | 新增 `advanceByGameMs(state, rawWallDeltaMs)`：返回未截断 Δt；旬判定次数单独计算 |
| C++ `system/time_system.h` | `advancePhase()` 推进 `gamePhase` | 新增 `gameTimeMs(gd)` 权威时间轴 + `projectCalendar(gameTimeMs)` 纯函数派生日历 |
| C++ `system/phase_settlement.h` | `runPhaseSettlement(state, ...)` 整旬量 | 拆为 `accrueContinuous(state, gameDeltaMs)`（积分型）与 `runDiscreteChecks(state, index)`（判定型）两个入口 |
| C++ `system/month_settlement.h` | `runMonthSettlement()` 一次性 | 积分型析出至 `accrueContinuous`；保留 `runMonthEvents()`（判定/叙事/事件流） |
| C++ `system/year_settlement.h` | `runYearSettlement()` 一次性 | 同上；T2 延迟组改由独立调度器承担，不再依赖"年末入队" |
| Kotlin `GameTimeClock` | 旬累积器 + `phaseCap` 丢弃 | 保留（UI 镜像 + 回退臂）；新增 `gameTimeMs` 权威读取与 `addOfflineGameMs()` |
| Kotlin `GameEngineCoreAuthoritativeOps.processAuthoritativeTick` | `repeat(capped) { 单旬完整事务 }` | 改为**单 tick 单事务**：一次 `nativeAccrue(deltaGameMs)` + 一次镜像 + 一次边界编排派发 |
| Kotlin `ProductionSlot` / `LazyEvaluationDispatcher` | 年月整数 + 旬 | 改为游戏毫秒时间戳（见 §2.5 存储） |

### 2.5 数据流（改造后单 tick）

```
WallClock（平台单调时钟，端口注入）
  │  每次 engine tick（100ms）
  ▼
PhaseClock.advanceByGameMs(rawWallDelta)            [C++, settlement.h]
  ├─ elapsedGameMs += rawWallDelta × speed          （不截断，INV-2）
  ├─ Δt = 本次新增游戏毫秒
  ├─ 派生日历投影（年/月/旬，写回 gameYear/Month/Phase 供 UI 与叙事）  [time_system.h]
  ├─ accrueContinuous(state, Δt)                    ← 全部积分型结算（原 L1 积分项 + L2 + L3/L4 积分项）
  ├─ 离散判定次数 = f(elapsedGameMs) 的整数差          [INV-3]
  │    └─ 按相同次数执行判定（突破/丹药/偷盗/死亡阈值…），RNG 序列可复现
  └─ 返回边界标志（月/年跨越，用于叙事派发，不用于资源结算）
  ▼
Kotlin：单次增量镜像（StateSyncService.applyDirtyFromNative）
  ▼
Kotlin：边界叙事派发（月/年事件流、UI 通知、埋点）——不再是资源结算入口
  ▼
UI：读 GameViewStore 投影块（值随镜像更新，无需等待边界）
```

### 2.6 自动存档改造（**已实施**）

> 本项是与"结算现实时间化"配套的第一处落地：把自动存档的时间基从**游戏日历**换成**现实墙钟**。

| 项 | 改造前 | 改造后 |
|---|---|---|
| 触发源 | `AutoSaveTrigger.MONTHLY`（游戏月月变，`monthSettledEvents` 流） | `AutoSaveTrigger.REALTIME`（现实墙钟节拍） |
| 时间基 | 游戏月 = 3 旬 × 2000ms = **6 游戏秒**（2x 下 3 秒） | **10 现实秒**，与速度/暂停/日历解耦 |
| 门控旗标 | `SaveTriggerFlag.autoSaveOnMonthChange`（默认开） | `SaveTriggerFlag.realtimeTick`（默认开）；旧旗标 `@Deprecated` 保留仅为部署期兼容 |
| 保留项 | — | `onStop` 后台保存（`SaveTriggerFlag.saveOnBackground`）不变 |
| 节拍下限 | 无（月界即节拍） | 常量 `REALTIME_AUTO_SAVE_INTERVAL_MS = 10_000`；轮询步长 1 秒；**至多每 10 秒一次**，不补足错过周期 |

**改动文件**（均为单点、可独立回滚）：

- `android/core/data/.../SaveTriggerFlag.kt` — 新增 `realtimeTick`；`autoSaveOnMonthChange` 标 `@Deprecated`
- `android/feature/game/.../saveload/SaveOrchestrator.kt` — `MONTHLY` → `REALTIME`（规则与合并窗语义不变）
- `android/feature/game/.../SaveLoadViewModelAutoSaveOps.kt` — 新增 `REALTIME_AUTO_SAVE_INTERVAL_MS` / `REALTIME_AUTO_SAVE_POLL_MS` / `consumeRealtimeAutoSave`（纯函数）/ `onRealtimeAutoSaveTick`；触发旗标映射换 `realtimeTick`
- `android/feature/game/.../SaveLoadViewModel.kt` — 删除 `monthSettledEvents` 收集器，改为节拍循环；新增可观测 `realtimeAutoSaveElapsedMsFlow`
- 测试：`SaveTriggerFlagTest`（默认值断言换 `realtimeTick`）、`SaveOrchestratorTest`（枚举重命名）、`SaveLoadViewModelAutoSaveTest`（新增"未到点不落盘"守卫，去掉月变事件驱动）

**不变量**：
- 三前置（旗标 / 有效槽位 / 引擎已加载）不变；
- 合并窗（500ms）语义不变——只做"同刻多源合并"，**不构成节流下限**；
- 失败口径不变：自动失败走消息栏持久一行，不刷 snackbar；
- 关闭旗标 = 逐行回到"仅手动保存"的回滚臂。

**诚实边界**：节拍按**轮流补步**累计（每轮询 +1 秒），因此实际落盘时刻受轮询调度精度影响，
最坏偏差 ≈ 1 个轮询步长；OEM 挂起恢复后按"至多一次"落盘，不追补。若后续要求更高精度，
应改为持有单调时钟基准并做差分（本次不做，避免引入新的时钟注入面）。

---

## 三、影响范围清单

> 格式：`文件路径 — 变更类型 — 变更说明`。
> 本清单是实施账本（`rules/design-plan-review.md` 原则 3）。§3.1/§3.2 为总览，
> §3.3 存储、§3.4 结算项穷尽清单（含逐文件证据）、§3.5 测试、§3.6 文档规范为完整明细。

### 3.1 引擎核心（C++，`:app/src/main/cpp/gamecore`）

- `gamecore/include/gamecore/system/settlement.h` — 改造 — 新增未截断游戏毫秒推进；`phaseCap` 作用域收窄到判定轨
- `gamecore/include/gamecore/system/time_system.h` — 改造 — 新增权威时间轴与日历派生纯函数
- `gamecore/include/gamecore/system/phase_settlement.h` — 拆分 — 积分轨与判定轨两个入口
- `gamecore/include/gamecore/system/month_settlement.h` — 拆分 — 积分型析出，保留判定/叙事
- `gamecore/include/gamecore/system/year_settlement.h` — 拆分 — 同上；T2 延迟组调度独立化
- `gamecore/include/gamecore/system/engine_loop.h` — 改造 — `LoopFramePlan` 增补未截断游戏毫秒字段
- `gamecore/include/gamecore/state/models.h` — 改造 — GameData 时间字段（见 §4）
- `gamecore/src/json_codec.cpp` — 改造 — 新字段双向编解码，导出键与 Kotlin 字段名对齐
- `gamecore/include/gamecore/game_core.h` + `src/game_core.cpp` — 改造 — 钩子注册与 JNI 入口
- （逐步骤与公式明细见 §3.4.1/§3.4.2/§3.4.3）

### 3.2 Kotlin 引擎与平台层

- `android/core/engine/.../system/GameTimeClock.kt` — 改造 — 权威读取 + 离线注入
- `android/core/engine/.../GameEngineCoreAuthoritativeOps.kt` — 改造 — 单 tick 单事务
- `android/core/engine/.../GameEngineCorePausOps4.kt` — 改造 — 边界派发语义
- `android/core/engine/.../GameEngineCoreMonthOps.kt` / `GameEngineCoreYearOps.kt` — 改造 — 信封字段
- `android/core/engine/.../service/{Month,Year}Settlement*Executor.kt` — 拆分 — 残留职责归位
- `android/core/engine/.../LazyEvaluationDispatcher.kt` — 替换 — 改游戏毫秒判据
- `android/core/engine/.../domain/production/`、`transaction/ProductionTransactionManager.kt`、`repository/ProductionSlot*.kt` — 改造 — 槽位时间模型
- （其余逐文件项见 §3.4 各子节与 §3.3）

### 3.3 存储与存档

- 新增 `android/core/data/src/main/java/com/xianxia/sect/data/local/GameDatabaseMigrationsV60.kt` — 新增 — `MIGRATION_59_60`（`game_data` 新增 `elapsedGameMs`/`lastSettleGameMs`/`spiritMineLastSettledGameMs` 等列，`DEFAULT 0`）
- `android/core/data/.../local/GameDatabase.kt:68-80,95` — 改造 — `ALL_MIGRATIONS` 追加 `MIGRATION_59_60`；`DATABASE_VERSION` 59 → 60
- `android/core/domain/.../model/GameData.kt:106-118,344-347` — 改造 — 新增时间轴字段（`@ProtoNumber` 取 1000+ 预留段、`@ColumnInfo`、`@SettlementStrategy(PRESERVE_OLD)`；**禁止 `@Transient`**，见 `rules/database-migration.md:153`）
- `android/core/domain/.../model/production/ProductionSlot.kt:17-28,53-62,87-92,111-124` — 改造 — 槽位时间模型（新增 `startedAtGameMs`/`completeAtGameMs`，保留旧 `startYear/startMonth/duration/completionMonth/completionPhase`；3 个派生方法改签名）
- `android/core/domain/.../util/TimeProgressUtil.kt:5-33` — 重写 — 5 个年月整数签名方法
- `android/core/data/.../local/ProductionSlotDao.kt:60-65,118-123` — 改造 — **SQL 时间运算重写**（`startYear*12+startMonth+duration <= ...`）
- `android/core/data/.../model/SaveData.kt:81` + `serialization/backwardcompat/OldSerializableSaveData.kt:892-918` — 改造 — ProtoBuf 嵌套与向后兼容（旧整数时间可读入换算）
- `android/core/data/.../local/CollectionConverters.kt:371-377` — 改造 — `productionSlots` TEXT 列序列化
- `android/core/data/.../integrity/SaveValidationRuleDefaults.kt` + `rules/{GameDateRule,GamePhaseRangeRule,SlotRefRule,BloodPoolBuildingCleanupRule}.kt` — 新增/改造 — 时间轴合法性 + 引用修复
- `android/core/data/.../local/GameDatabaseMigrationsV39.kt:100` — 复核 — `cultivationCompletionPhase` 死值（`DEFAULT 1`）的清理决策
- `android/core/data/src/test/.../RoomMigrationSupport.kt:216,234` — 改造 — 硬编码列清单同步
- 新增 `android/core/data/src/test/.../RoomMigrationV59To60Test.kt` — 新增 — 迁移集成测试
- `android/app/src/main/cpp/gamecore/src/json_codec.cpp:435,448,796,802` — 改造 — 新字段双向编解码（导出键 = Kotlin 字段名，缺键宽松）
- `android/app/src/main/cpp/gamecore/include/gamecore/state/models.h:567-592` — 改造 — C++ 镜像模型时间字段
- `android/app/schemas/.../<60>.json` — 新增 — KSP 自动导出并入库

### 3.4 结算项穷尽清单（调查结论，改造账本）

> 本节是"哪些按旬/月/年结算"的权威答案。逐项给出 **实现位置 / 结算公式 / 当前单位 / RNG 分区 / 改现实秒的处理**。
> 换算总则：`每旬 ÷ 2 = 每游戏秒`、`每月 ÷ 6`、`每年 ÷ 72`（§2.2）。

#### 3.4.1 L1 每旬结算（`phase_settlement.h`）

| 步 | 结算项 | 实现位置（文件:行） | 结算公式 / 增量 | 当前单位 | RNG 分区 | 改现实秒 |
|---|---|---|---|---|---|---|
| 0 | 自动装备 / 自动学习 | `phase_settlement.h:1268,1306`；`auto_gear.h:850-942` | 每弟子每槽位**每旬至多 1 次**装配/替换 | 每旬 1 次机会 | 无 | **不能积分**：改事件驱动（无速率语义） |
| 1 | HP/MP 恢复 | `:167-191`（公式 `:159-164`） | `maxHp/maxMp × 0.2(kPhaseHpMpRecoveryRate) × 1.0(kRecoveryZoneTotal)`，`toInt` 截断后 `max(...,1)` | **每旬 0.2 × max** | 无 | **÷2** → `0.1 × max /游戏秒`；取整改小数累积（INV-6） |
| 2 | 修炼累积 | `:284-315`；速率 `disciple_stats.h:515-559`；基础表 `disciple.h:77` / `GameConfig.kt:175-186` | `rate = realmSpeedPerPhase(realm)/灵根数 × (1+建筑)(1+讲道)(1+状态)(1+临时)(1+星级)`，下限 `1.0`；`cultivation = min(cur+rate, max)`；`≥1e8` 跳过 | **每旬 rate 点** | 无 | **÷2**（`REALM_SPEED_PER_PHASE` 全表 ÷2） |
| 3 | 功法熟练度 | `:353-399` + `:323-348` | `6.0(kBaseProficiencyRate) × (1+0.5 藏经阁) × 2000/1000` = 6.0 或 12.0/旬；上限 30000 | **每旬 6/12 点** | 无 | **基数已是每秒**（`nurture_constants.h:23` 注释"6/s"）：把 `×2000/1000` 换成 `×Δ秒`，**无需除系数** |
| 4 | 装备孕养 | `:461-475` + `:417-430` | `nurtureProgress += 10.0`；达 `expRequiredForLevelUp` 升级，`nurtureMaxLevel(rarity)` 封顶 | **每旬 10.0 点** | 无 | **÷2**（基数 `5.0/s`，`nurture_constants.h:29`） |
| 5a | 批量提交熟练度 | `:402-412` | `nullopt` → erase，其余覆盖 | 每旬 1 次 | 无 | 合并语义保留 |
| 5b | 重建装备实例列表 | `:478-485` | 按 id 覆盖、保持原序 | 每旬 1 次 | 无 | 保留 |
| 6 | 自动丹药补服 | `:635-659`；指纹 `:495-527`；主流程 `:533-582`；写回 `:585-628` | 排序=规则优先级降序+稀有度降序（`stable_sort`）；排除突破丹/战斗临时丹；满修为跳过修为丹；服用后写 `cultivationCheckpoint` | 每旬 1 轮（0..N 颗） | 无 | 改**事件/阈值驱动**；注意丹药 `duration` 同时被月结"扣 3 旬"消费（双计风险） |
| 7 | 突破检测 | `:1011-1074`；单弟子 `:931-1002`；概率 `:703-755` | 候选=存活∧非秘境∧`realm>0`∧修为满∧HP/MP 满；`success = nextDouble() < chance`；成功修为清零+层数推进；失败修为清零+HP/MP×0.1 | **每旬 1 次判定窗口**（窗口内 while 可连续多次尝试） | **`kBreakthrough`，每次尝试恰 1 次 `nextDouble()`** | **🔴 不得连续化**：必须保持"每旬一次判定窗口"（`INV-3`），否则尝试次数随帧率漂移、RNG 序列分叉 |
| — | 突破完成时间预估 | `:899-923`；`breakthrough.h:137-143` | `completionMonth = currentMonth + ceil(ceil(remaining/rate)/3)`；`completionPhase` **硬编码 1** | 月+旬双编码 | 无 | 重写为绝对游戏秒；`cultivationCompletionPhase` 为死值（`=1`）却被 ProtoNumber(95)+Room 列+镜像三重承载 → 优先清理 |
| — | 突破事件收割 | `game_core.cpp:168,213` | 扫 `gameEventRecords` 事件序号增量 | 每旬 1 次 | 无 | 保留（事件驱动） |

**其他每旬口径消费点（不在 `phase_settlement.h` 内但同为单位来源）**：

| 项 | 位置 | 说明 |
|---|---|---|
| `REALM_SPEED_PER_PHASE` | `GameConfig.kt:175-186` | 境界基础修炼速率表（每旬） |
| `PHASE_HP_MP_RECOVERY_RATE = 0.2` | `GameConfig.kt:193` | 每旬恢复比例 |
| 道具/丹药 `duration`（以旬计数） | `month_settlement.h:85,549-580`；`HpMpRecoveryService.kt:265-296`；`DiscipleComponents.kt:86` | 月结按 `3 - focusedPhaseCount` 扣减，`focusedPhaseCount` 恒 0 |
| AI 宗门"3 旬=1 月"折算 | `ai_sect_ops.h:77,239,274,332`；`AISectDiscipleManager.kt:169`；`AISectDiscipleManagerMisc.kt:135,204,234` | **属月结路径**，易被 L1 改造漏掉 |
| UI 旬进度 | `ProductionTheme.kt:254-262`（`phaseFraction = gamePhase/3f`）；`SectInfoCard.kt:40,196-206`；`DetailBasicInfoSection.kt:284` | 旬概念消失后需改为时间进度 |
| 事件记录 `phase` | `phase_settlement.h:776`；`settlement_detail.h:133` | 事件发生旬分量 |
| 功法遗忘时间戳 `forgetPhase` | `Disciple.kt:386`（ProtoNumber 12）；`StorageBagUtils.kt:23-25` | `totalPhases = y*36 + (m-1)*3 + phase` |
| 投影 `getEffectiveCultivation` | `cultivation.h:72-93` | `checkpoint + rate × monthsElapsed × 3.0`（月量化，连续化后放大漂移） |

**每旬结构的隐含假设（改造必须逐条处理）**：

| # | 假设 | 位置 | 打破后果 |
|---|---|---|---|
| 1 | 旬是不可分原子：1 次 `advanceOnePhase` = 1 次钩子 | `settlement.h:89-100,127-150` | L1 触发机制整体消失 |
| 2 | `phaseCap = 3 × speed` 超限丢弃余量 | `settlement.h:38-40`；`GameTimeClock.kt:165-172`；`engine_loop.h:144-156` | 丢现实时间（必须按 INV-2 解耦） |
| 3 | 双端常量同源（改值须同步） | `settlement.h:31-32`；`GameTimeClock.kt:224-231`；`GameTimeClockPhaseCapParityTest.kt:20-29` | 单端改值 → 双端漂移 |
| 4 | 三进制时间编码 `gamePhase ∈ {0,1,2}` | `time_system.h:20,25-40`；`GameData.kt:117` | 存档迁移链（v11…v55 五处重建 `game_data`）受影响 |
| 5 | 每旬恰 1 次增量镜像 | `GameEngineCoreAuthoritativeOps.kt:68-71` | 镜像频率需与 tick/节流重定；100 旬管线断言失效 |
| 6 | 每旬恰 1 次标量通道 `nativeSettlePhase()` + 边界 flags | `:65`；`GameCoreBridge.kt:117`；`settlement.h:49-52` | 月/年编排失去触发源 |
| 7 | 每旬 1 次突破埋点基线捕获 + 1 次差分上报 | `:62,74-76`；`BreakthroughAnalyticsObserver.kt:17,23,33` | 埋点重复/漏报 |
| 8 | 突破每次尝试恰消费 1 次 `nextDouble()`（**非每旬一次**） | `phase_settlement.h:926,962` | 🔴 最脆弱：连续化后尝试次数漂移 → 全部 `Diff*` 与老档重放失效 |
| 9 | 步骤 3/4 暂存 key 在块间唯一 | `:1158-1160` | 同 id 多次暂存静默丢更新 |
| 10 | 步骤 7 入口桶视图一次构建、步骤内只读 | `:1017-1027` | 桶陈旧 → 突破判定用旧 maxHp |
| 11 | 长老悟性取"结算入口已提交视图" | `:1252-1259,697-702` | 无"入口"概念后需重定义 |
| 12 | 每槽位每旬至多 1 次自动装配 | `auto_gear.h:40` | 按帧触发会换装震荡 |
| 13 | 整批失败 `refundPhases(capped)` 回滚 | `GameEngineCoreAuthoritativeOps.kt:93-102` | 连续结算无"批"可退 |
| 14 | 每帧 ≤5 tick，`tickPhases[5]` 传 Kotlin | `engine_loop.h:41-43,50-68,253-267` | LongArray 17 槽协议同步改 |
| 15 | 修炼累积不写 checkpoint（仅速率变化点写） | `phase_settlement.h:281-283`；`CheckpointCallSiteGuardTest` | 投影"月量化"漂移放大 |
| 16 | AUTHORITATIVE 下 Kotlin 时钟降级为 UI 镜像 | `GameTimeClock.kt:122-130`；`engine_loop.h:71-158` | `phaseProgress`/`remainingPhaseMs` 语义消失 |
| 17 | 丹药 `duration` 旬计数 与 月结"扣 3 旬"双语义 | `month_settlement.h:85,549-580` | L1 与 L3 双计风险 |
| 18 | 每旬读全弟子列（讲道/长老加成），不可分块并行 | `:1290-1292,232-279` | 频率提升会同时放大热点与并发读面 |

**⚠️ 本次调查中途发现的预存缺陷（实施时应一并处理，详见 §9）**：

| 缺陷 | 位置 | 说明 |
|---|---|---|
| `processAuthoritativeTick` **文件头与函数体编号自相矛盾** | `GameEngineCoreAuthoritativeOps.kt:34-44`（头）vs `:61-85`（体） | 头写"① settle/② 镜像/②' 埋点/③ 边界"，函数体实为 **5 段**：① 基线捕获 → ② `nativeSettlePhase` → ③ 增量镜像 → ③' 突破埋点 → ④ 边界（`PhaseSegmentTimer.split` @ `:63,66,72,77,85` 佐证）。改造必须**以函数体为准** |
| **两套时基**：`SettlementEngine::advance(wallDeltaMs)` 生产未被使用 | `settlement.h:72-86`（有参累积器）vs `engine_loop.h:134`（生产 `PhaseClock::tick()` 无参） | 生产 AUTHORITATIVE 时基是 `PhaseClock`；`SettlementEngine::accumulatedGameMs_` 只服务 shadow/测试路径。**改造必须同时处理两套**，否则 shadow 路径静默失真 |
| **C++ 两条每旬路径互斥** | `game_core.cpp:164-169`（`onPhaseSettle`）vs `:204-214`（`onCoreSettle`），分流见 `settlement.h:136-142` | 生产走 `runPhaseSettlementCore`；`runPhaseSettlement` 只在非 core 模式（shadow/对拍）触发。只改生产路径会漏掉 shadow 路径 |
| `phase_settlement.h:34` 标题"六步结算"与实际列 **1–7 步不符** | `phase_settlement.h:34` | 文档级缺陷，改造时顺手改正 |
| `cultivationCompletionPhase` 是**死值 1** | `phase_settlement.h:922` 硬编码；`Disciple.kt:103`（ProtoNumber 95）；`GameDatabaseMigrationsV39.kt:100`（DEFAULT 1）；`GameViewMirrorCodec.kt:441-442` | 有协议成本、无实际语义 —— 建议随本次 Migration 清理 |
| AI 宗门 **"3 旬 = 1 月"折算属 L3 月结**，非 L1 | `ai_sect_ops.h:77,239,274,332`；`AISectDiscipleManager.kt:169`；`AISectDiscipleManagerMisc.kt:135,204,234` | 最容易被"只改 L1"漏掉 |
| 熟练度/孕养常量是 **"每游戏秒"基数 × 旬换算**，易改错 | `EquipmentNurtureSystem.kt:10-14`（`AUTO_EXP_PER_SECOND=1.0` + `NURTURE_GAIN_PER_PHASE = 5.0 × 2.0`）；`ManualProficiencySystem.kt:93-99`；`nurture_constants.h:23,29` | 这两项**不要再除 2**（否则产出腰斩） |

**每旬被打破后必失败的测试（优先重建）**：`PhaseSettlementTest`（27 用例，`phase_settlement_test.cpp`）、`SettlePhaseTest`（5 用例，`settle_phase_test.cpp`）、`TimeSystemTest`/`SettlementEngineTest`（`time_system_test.cpp:18-175`）、`PhaseClockTest`/`EngineLoopTest`（`engine_loop_test.cpp:49-393`）、`MonthSettlementTest`（50+）、`YearSettlementTest`（30+）、`breakthrough_test.cpp:194,215`、`memory_trim_test.cpp`（3）、`column_export_equivalence_test.cpp`（2）、JUnit `DiffPhaseSettlementTest`/`DiffAuthoritativeTickTest`（100 旬断言）/`DiffEngineLoopTest`（13）/`GameTimeClockTest`/`GameTimeClockPhaseCapParityTest`/`TimeSystemPureLogicTest`/`DiffTimeTest`/`DiffMonthSettlementTest`/`DiffYearSettlementTest`/`GameViewStoreGuardTest`/`GameDataFieldPatchGuardTest`/`BaselineFieldCoverageGuardTest`。

**每旬绑定存档字段**：`GameData.gamePhase`（`models.h:1205`；`GameData.kt:117`；`SaveSlotMetadata.kt:33-34`；`SaveService.kt:24,63,108`；`GameDataFieldPatch.kt:134`）、`Disciple.cultivationCompletionPhase`（`Disciple.kt:103`，ProtoNumber 95，`GameDatabaseMigrationsV39.kt:100` DEFAULT 1）、`Disciple.cultivationCheckpointGameMonth`（`cultivation.h:64-92`）、`StorageBagItem.forgetPhase`（`Disciple.kt:386`）、`GameEventRecord.phase`、`ProductionSlot.completionPhase`、灵田 `plant.completionPhase`（`spirit_field.h:224-349`）。

**每旬相关校验链**：`GamePhaseRangeRule.kt:6-21`（自愈 clamp）、`SaveService.kt:108-109`（越界报错）、`BaselineFieldCoverageGuardTest.kt:18`（序列化面 ↔ C++ 基线双射）。

#### 3.4.2 L3 月变结算（`month_settlement.h`）——编排与子事件

> 🔴 **口径修正（本次调查实测）**：文件头注释 `month_settlement.h:41` 写"**七步**事务序"，
> 但 `runMonthSettlement`（`:1239-1375`）实为 **八步**；Kotlin 侧 `MonthSettlementExecutor.kt:15` 亦写"七步"、
> `:38` 写"八步"，`GameEngineCoreMonthOps.kt:93` 称"八步 + 十六子事件"。**本方案以代码为准（八步）**。

**八步编排（`runMonthSettlement`，`month_settlement.h:1239-1375`）**：

| # | 步骤 | 实现位置 | 做什么 | 依赖"每月恰好一次" |
|---|---|---|---|---|
| 1 | 政策月度灵石扣除 | 调用 `:1247-1262`；实现 `government.h:117-180` | 10 项固定月费 + 4 项按弟子数月费逐项 `deduct`，不足则自动关政策并记 `disabledPolicies` | **强**（重复调用 = 重复扣费） |
| 2 | 政策月度道德效果 | 调用 `:1264-1265`；实现 `:113-130` | 教化之道开启时每存活弟子 `morality +1`，clamp 到 `kMoralEducationMax=70` | **强**（+1/月语义） |
| 3 | AI 兽袭目标预计算 | 调用 `:1267-1269`；实现 `:1185-1222` | 活跃妖兽按 id 升序，每妖兽取 2 个最近 AI 宗门门控判定，命中写 `aiSectBeastDirectTargets`，失败记冷却，末尾清 12 月前冷却 | **中**（抽取次数 ∝ 调用次数 × 妖兽数 × 候选数） |
| 4a/4b | Alchemy(210)/Forge(211) 完成结算 | 调用 `:1277`；实现 `production.h:841` | 槽位完成判定 + 成功率 roll + 产出入库 + 职业晋升 + 槽位重置 | **强** |
| 4c | Planting(214) 灵田成熟收获 + 续种 | 调用 `:1279`→`:137-142`；实现 `spirit_field.h` | 成熟地块收获 + 种子 roll + 续种/清地 | **强**（同地块重复收获） |
| 4d/4e | Exploration(240) 关卡清理 + 刷新生成 + 妖兽移动 | 调用 `:1283-1355`；实现 `exploration.h:65-69`、`level_generator.h` | 差值 ≥3 月门控 → 过期清理 → 生成（≤6）→ `moveBeasts` | **中**（刷新有差值门；但**移动每次调用都跑** → 抽取翻倍） |
| 5 | 月度自动排班 | 调用 `:1359-1361`；实现 `:412-544` | 11 类槽位占用扫描 → idle 池 → 住所分配 → 四类生产候选 take + 回流 → 原子写槽位 | **中**（靠"仅填空槽"幂等） |
| 6 | 丹药持续效果月度衰减 | 调用 `:1363-1364`；实现 `:550-609`；常量 `:85` | 每存活弟子 `pillEffectDuration -= 3`，≤0 时清零全部 pill 加成与 `activePillTypes/Categories` | **极强**（每次调用固定 −3，连续调用线性多扣） |
| 7 | 月度事件（子事件） | 调用 `:1366-1367`；实现 `:966-1048` | 见下表 | 见下表 |
| 8 | 自动续炼启动（autoRestart） | 调用 `:1369-1372`；实现 `production.h:687+` | `autoRestartEnabled && status==IDLE` 的炼丹/锻造槽启动新炼制（材料匹配 + 消耗） | **强**（重复启动/重复扣材料） |

**子事件清单（`processMonthlyEvents`，`month_settlement.h:966-1048`）**：

> ⚠️ **编号有空洞**：声明序（`:714-718`）列 14 项，代码实际编号跳到 16（**缺 2/3/4**），
> 故"十六子事件"是**编号上界**而非项数；`MonthSettlementResidualExecutor.kt:18-24` 自述"已下沉 12 件"，
> 与 C++ 实际 **15 项实现**（编号 1/5/6/6b/6c/7/8/9/10/11/12/13/14/15/16）不符 → **文档漂移**。
> `:714` 仍列 `autoRecruit`（招募链已随 DB v56 下线，`GameDatabaseMigrationsV56.kt:12-23`）。

| 编号 | 名称 | 位置 | 做什么 | RNG | "每月一次"依赖 |
|---|---|---|---|---|---|
| 1 | 招募月度计数归零 | `:973` | `recruitCountThisMonth = 0` | 零 | **强**（"本月"语义即月界） |
| 5 | 任务完成 | `:980` → `mission_settlement.h` | 到期任务奖励结算 + 战斗组装执行 | `MISSION`/`BATTLE`/`ENEMY_GEN` | **强** |
| 6 | 洞天 AI 月度操作 | `:985-988` → `ai_sect_ops.h:795` | 仓库清场 + AI 弟子分批修炼 + 等级同步 + 成员过滤 | **独立 AI RNG**（`aiRng`，seed=systemSeed+6×31337） | **强** |
| 6b | AI-vs-AI 征伐环 | `:996` → `sect_conquest.h` | 决策 + 快照守军 + 统一应用 | `BATTLE` | **强** |
| 6c | AI 攻玩家决策与防守战 | `:1004` → `sect_defense_battle.h` | 预警收敛 / 到期战书结算 / 新攻击决策 / 驻军填充 | `BATTLE` | **强** |
| 7 | 游戏结束检查 | `:1006` → `:689-711` | 玩家无受控宗门 → `isGameOver=true` | 零 | 弱（幂等） |
| 8 | 侦察信息过期清理 | `:1008-1009` → `:750-794` | `year>expiryYear \|\| (year==expiryYear && month>expiryMonth)` | 零 | 中（需每月检查才不失时） |
| 9 | AI 兽战余量 | `:1014` → `ai_sect_ops.h` | 兽战组装 + 击败标记 + 死亡处理 | `BATTLE` | **强** |
| 10 | 12 月自动购买 | `:1017-1019` → `merchant_settle.h` | `gameMonth==12` 才买 | 零 | **强**（`==12` 硬编码门；连续化下会"月月买"或"永不买"） |
| 11 | 灵矿月度产出 | `:1021` → `:670-685` | `rate × (currentMonth − lastSettledMonth)` 入钱包 → 推进 `lastSettledMonth` | 零 | **弱（唯一已差分化项）** |
| 12 | 弟子智能购买 | `:1025` → `disciple_purchase.h` | 按清单买弟子物品，产 `PurchaseLogDraft` | **`SYSTEM`**（`shuffled`） | **强** |
| 13 | 附庸脱离检查 | `:1028` → `:901-950` | 每契约门控后**恰抽 1 次** `nextDouble`，`< breakChance` → 移除 + 事件 | **`SYSTEM`**（1 次/契约） | **强**（抽取次数 = 契约数 × 调用次数） |
| 14 | 任务刷新 | `:1032` → `mission_settlement.h:509-518` | `month % 3 == 0` 才刷新，`refreshCount = nextInt(7)`，每任务 2 次 `nextDouble` | **`MISSION`** | **极强**（`%3` 门 + 刷新清空旧表） |
| 15 | 秘境现世期满关闭 | `:1036-1044` → `secret_realm_settlement.h:193-201` | `year >= spawnYear + kOpenYears(5)` → 关闭（钱包/背包/会话清场 + 冷却年 + 事件） | 零 | 中（年粒度；关闭后 `id` 清空 → 幂等） |
| 16 | 秘境 AI 队伍月度派遣 | `:1047` → `secret_realm_settle::processMonthlyAiTeams` | AI 宗门派 4 名存活弟子（境界升序）探索 | 零 | 中（幂等去重） |

**L3 的 RNG 消耗点（文件头 RNG 核对表 `month_settlement.h:53-64`，改造的对拍命门）**：

| 分区 | 触发 | 抽取次数 |
|---|---|---|
| `EXPLORATION` | 妖兽移动 `moveBeasts` | 每活跃妖兽 2 次 `nextDouble`（角度+距离）；步骤 3 每妖兽×每候选门通过 1 次（`:1168`） |
| `SYSTEM` | 灵田收获种子 roll | 每收获地块 1 次 `nextInt(5)` |
| `SYSTEM` | 弟子智能购买 `shuffled` / 附庸脱离每契约 | `shuffled`（`:1022-1024`）/ 1 次 `nextDouble`（`:897`） |
| `BATTLE` | 子事件 6b 征伐环 `checkAttackConditions` 门通过 | 恰 1 次 `nextDouble` + `executeAiBattle` 全回合 |
| `BATTLE` | 子事件 6c 防守环 `decidePlayerAttack` 六道闸通过 | 恰 1 次 `nextDouble` + 到期战书全回合 |
| `BATTLE` | 子事件 9 AI 兽战 | 与 Kotlin `BattleExecutionRouter` 同分区 |
| `MISSION` / `ENEMY_GEN` | 子事件 5 任务完成 / 子事件 14 刷新 | 每任务 2 次 `nextDouble`；刷新 1 次 `nextInt(7)` |
| 独立 AI RNG | 子事件 6/9 | `aiRng`（`month_settlement.h:1234`、`game_core.cpp:156-157`） |
| `BREAKTHROUGH` | — | 本钩子零消耗（旬结算是唯一入口） |

**关键常量**：`kMonthlyDecayPhases = 3`（`month_settlement.h:85`，每月 3 旬；月变 `÷6` 换算为每秒）。

**⚠️ 月变的两处结构性问题（改造前必须先解决）**：

1. 🔴 **绝对月口径有两套且不一致**：灵矿/关卡/AI 用 `year*12+month`（`month_settlement.h:673,1213,1285`；
   Kotlin `CultivationSettlement.kt:394`、`DiscipleService.kt:159`），而 Kotlin 生产域模型用
   `(year-1)*12+month`（`SlotStateMachine.kt:48`），C++/Kotlin 服务层又用 `year*12+month`
   （`production.h:668`、`ProductionProcessorFindOps5.kt:150`）—— **毫秒化会把该差异放大**，必须先统一。
2. 🔴 **幂等/去重靠结构而非时间戳**：生产槽靠 `validateTransition` + `resetSlot` + `settlementIdentityMatches`；
   灵田靠清地/续种；秘境靠 `id` 清空；AI 队伍靠幂等去重。**取消月界后每项都需显式引入"上次结算时间戳"**
   （目前只有灵矿/关卡/AI 热控有）。

> 以"每月"为单位的**常量族、公式与逐项改造口径**见 §3.4.5（政策开销 / 道德忠诚 / 灵矿月产 / 丹药时长 / 阈值限额 / AI 折算）。

#### 3.4.3 L4 年变结算（`year_settlement.h`）

> 🔴 **口径修正（本次调查实测）**：文档与注释多处记 **T1 11 项 + T2 11 项**（`year_settlement.h:43-45`、
> `GameEngineCoreYearOps.kt:104-106`、`YearSettlementResidualExecutor.kt:17-18`、`DiffAuthoritativeTickTest.kt:566`），
> 但**代码与测试均为 8 + 8**（`CultivationEventMonthlyOps.kt:97`「T1 立即组（8 项）」、`:119`「T2 延迟组（8 项）」、
> `CultivationEventMonthlyOpsTest.kt:126/154/159` 以 `8` 硬断言）。C++ 注释编号 `#1→#2→#4→#5→#6→#7→#8→#10→#11`
> 是旧 11 项清单残留。**本方案以代码为准（8+8）**，并顺带修正文档口径。
>
> 另注：年变触发点不是"12 月下旬触发"，而是**从 12 月下旬推进到次年 1 月上旬时**触发
> （`settlement.h:129-149`、`time_system.h:29-36`），且**年变钩子先于月变钩子**。

**T1 立即组（8 项，单事务，`CultivationEventMonthlyOps.kt:96-118`）**：

| # | label | 做什么 | RNG | "每年恰好一次"依赖 |
|---|---|---|---|---|
| 1 | `yearlyTribute` | 玩家为附庸时按上年收入比例向上主宗缴年贡 | 否 | 否，但**触发即扣，次数敏感** |
| 2 | `yearlyVassalTribute` | 附属宗门按等级纳贡（200k/800k/3M/10M 或默认 50k） | 否 | 是（`lastTributeYear >= year` 跳过） |
| 3 | `discipleAging` | **仅状态重推导**，非老化（`currentYear` 未使用，幂等） | 否 | 否 |
| 4 | `merchantRefreshChance` | 商人手动刷新机会 +1（上限 999，间隔 30 年） | 否 | 是（差值 ≥30） |
| 5 | `yearlyAging` | 清理死亡满 1 年弟子 | 否 | 是（`<= currentYear-1` 阈值） |
| 6 | `reflectionRelease` | 思过到期释放 → IDLE，道德 +5 | 否 | 否（`year >= endYear` 差值自愈） |
| 7 | `garrisonAndReport` | 驻军轮换 + 年报快照 + 15 个 `annual*` 清零 | 否 | **是（快照+清零，重复执行丢一年）** |
| 8 | `autoBuy` | 自动购买（显式传 1 月，置于快照后） | 未确认 | 是（购买时机） |

入队在同事务内最后一步（`enqueueYearlyOps(year)`，`:123`）——事务外入队存在"T1 提交 → 入队"竞态窗口。

**T2 延迟组（8 项，`CultivationEventMonthlyOps.kt:134-159`；FIFO 序 #4→#12→#13→#15→#16→#17→#19→#22）**：

| # | label | 做什么 | RNG 分区 | "每年恰好一次"依赖 |
|---|---|---|---|---|
| 1 | `sectDisciplesAging` | AI 弟子老化——**实测 no-op**（`AISectDiscipleManager.kt:333-335`） | 零抽取 | 否 |
| 2 | `refreshAcquisition` | 商人收购列表刷新（1..9 条） | **`SYSTEM`**（数量 `1×nextInt(9)` + 每 item 品阶 `1×nextDouble` + 选池 `1×nextInt`） | 否（直接覆盖写） |
| 3 | `sectTradeRefresh` | AI 宗门交易列表刷新（满 3 年/空兜底） | **局部种子，零分区消耗** | 是（差值 ≥3） |
| 4 | `allianceExpiry` | 联盟满 5 年解散 | 否 | 是（差值 ≥5） |
| 5 | `allianceFavorDrop` | 盟友好感 <80 解散 | 否 | 否（阈值式） |
| 6 | `aiAlliances` | AI 联盟建立——**空实现扩展点** | 无 | 否（no-op） |
| 7 | `favorDecay` | 好感衰减（`favor>80` 且距上次交互 ≥1 年 → −1，保底 80） | 否 | **是（`noGiftYears += 1`，非幂等）** |
| 8 | `ancientSecretRealmSpawn` | 秘境现世（冷却 50 年） | **`SECRET_REALM`** | 是（差值 ≥50） |

**年俸**（独立分支，不属 T1/T2）：`isJanuary` 时 `processAnnualSalary`（`YearSettlementExecutor.kt:39-41`；C++ `year_settlement.h:1228-1230`）。

**C++ `runYearSettlement` 与 Kotlin 的覆盖差（⚠️ 发现两处功能缺口）**：

| 组 | Kotlin | C++ 实现 | 缺口 |
|---|---|---|---|
| T1 | 8 项 | 6 项（#1/#2/#4/#5/#7 拆两调用/#8） | **#3 `discipleAging`**（C++ 侧状态推导已先行走 native）；**#6 `reflectionRelease` —— 全仓 C++ 无年度思过释放实现** |
| T2 | 8 项 | 6 实 + 2 no-op（#1/#6） | 无语义缺口 |

> 🔴 **`reflectionRelease` 在 AUTHORITATIVE 生产路径不可达**：`GameEngineCorePausOps4.kt:95` 优先走
> `settleYearNative()`，Kotlin 分支仅在 native 未就绪时执行 → **思过弟子不会自动释放**。
> 本方案实施时必须一并修复（或明确登记为既有缺陷），否则连续化后该缺口会被永久掩盖。

**T2 队列分帧机制（`YearlyOpsQueue`，本项目已有的分帧先例）**：

| 机制 | 位置 | 不变量 |
|---|---|---|
| 队列本体 | `YearlyOpsQueue.kt:24-102` | `ConcurrentLinkedQueue<MutableGameState.() -> Unit>`；**进程内态，崩溃即丢** |
| 入队 | `YearlyOpsQueue.kt:32-34`；`CultivationEventMonthlyOps.kt:154-158` | T1 事务**内**入队（闭合竞态窗口）；入口先 `clear()` 防年变双触发 |
| 预算 drain | `YearlyOpsQueue.kt:52-93`；`CultivationEventProcessor.kt:89` | `consumerLock` 互斥；`YEARLY_OPS_DRAIN_BUDGET_MS = 30L`；**至少执行 1 个 op** |
| 每 tick 调用 | `CultivationEventProcessor.kt:106-117`；`GameEngineCoreLoopOps.kt:61-62` | 1 月按 30ms 分摊；**非 1 月 `forceDrain` 全量**（不允许跨月残留） |
| flush-on-save | `CultivationEventProcessor.kt:120-128`；`GameEngineSaveOps.kt:34-37`；`GameEngine.kt:216` | 先空事务拿 `transactionLock`，再 `forceDrain` → 闭合"快照 ⇒ 队列已空" |
| 读档清除 | `CultivationEventProcessor.kt:131-133`；`GameEngineLoadDataOps.kt:83-86` | 丢弃旧档残留，防跨存档污染 |
| 自愈契约 | `CultivationEventMonthlyOps.kt:128-132`；`YearlyOpsQueue.kt:21-22` | T2 靠差值判据下年补跑；**唯二非幂等项 = `favorDecay`（`noGiftYears += 1`）与 `processYearlyTribute`（触发即扣）** |

**以"每年"为单位的判据清单（连续化改造点）**：

| 逻辑 | 位置 | 判据 | "每年一次"依赖 |
|---|---|---|---|
| 年变触发 | `settlement.h:144-146`；`time_system.h:25-40` | 跨年边界 → `onYearChange` 先于月变 | 是（边界即年） |
| 弟子老化/死亡判定 | `DiscipleLifecycleProcessor.kt:66-69` | **不存在**——`age`/`lifespan` 已于 v54→v55 整批删除（见下） | 否 |
| 死亡弟子清理 | `DiscipleLifecycleProcessor.kt:154-157`；`DiscipleTables.kt:964-972` | `deathYears[id] <= currentYear - 1` | 是 |
| AI 尸体压缩 | `year_settlement.h:383-393` | `(gameYear - deathYear) >= 3` | 是（差值自愈） |
| 年报统计 | `CultivationEventMonthlyOps.kt:171-204`；`year_settlement.h:137-177` | `report.year = gameYear-1`，拷贝 15 项 `annual*` 后清零，`takeLast(100)` | **是（快照语义不可重复）** |
| 年俸发放 | `year_settlement.h:184-240,1151-1166`；`government.h:286-294` | `Σ原额扣除` → 发 `round(salary × (frugality?0.7:1.0))` | 是（重复即多给） |
| 驻军报告 | `year_settlement.h:1045-1117` | 按 `realm` 升序前 10 留守、第 11 起填 10 槽 | 是（全量重算，幂等） |
| 商人收购刷新 | `year_settlement.h:609-631` | 重建列表 + 写 `LastRefreshYear` | 否 |
| 交易刷新 | `year_settlement.h:880-885` | `year - last >= 3 \|\| tradeItems.empty()` | 是（差值自愈） |
| 商人刷新机会 | `year_settlement.h:340-350` | `last==0 \|\| year-last >= 30` → `chances+1`（≤999） | 是 |
| 好感衰减 | `year_settlement.h:989-1011` | `favor>80 && year-lastInteractionYear>=1` → `favor-1`(≥80)，`noGiftYears+1` | **是（`+1` 累积，非幂等）** |
| 联盟到期 / 好感解散 | `year_settlement.h:918-939,944-984` | `year-startYear>=5` / `favor<80` | 是（差值）/ 否 |
| 思过释放 | `DiscipleLifecycleProcessor.kt:197-198` | `year >= statusData["reflectionEndYear"]` | 否（差值自愈） |
| 附庸年贡 / 附属纳贡 | `year_settlement.h:266-280,287-334` | `max(income×0.5, 1)`；`establishedYear/lastTributeYear >= year` 跳过 | **是（触发即扣）** |
| 秘境现世 | `SecretRealmService.kt:100-104`；`year_settlement.h:1128-1130` | `year - max(cooldown,0) >= 50` | 是（差值） |
| 玩家保护 | `sect_attack_decision.h:202`；`GameData.kt:516` | `(gameYear - playerProtectionStartYear) < N` | 否 |
| 生产槽时长 | `production.h:1190-1194` | `(gameYear-startYear)*12 + (gameMonth-startMonth)` | 否（绝对月差分 → 改毫秒） |

**🔴 年龄不变量的实测结论：年龄不变量已不存在**（这消除了连续化最难的一块）：

- `age`/`lifespan`/`soulPower`/`loyalty` 等 9 列已于 **v54→v55 整批删除**（`GameDatabaseMigrationsV55.kt:12-18,38-39`），
  删除论证为"全仓 `src/main` 对这 9 列的读写点为零"（`:57-61`）；索引 `index_disciples_age`/`index_disciples_loyalty` 不再重建（`:77`）
- Kotlin `Disciple` 无 `age` 字段；C++ `models.h:298-407` 的 `Disciple` 无 `age`/`lifespan`/`birthYear`
- `processDiscipleAging(currentYear)` 标 `@Suppress("UnusedParameter")`，仅 `syncAllDiscipleStatuses()`（`DiscipleLifecycleProcessor.kt:66-69`）
- AI 老化 `processAging` 直接 `return disciples`（no-op，`AISectDiscipleManager.kt:333-335`）
- 死代码残留：`GameConfig.kt:329-365,902` 的 `RealmConfig.maxAge` 无存续消费点（仅 `RealmConfigTest.kt:457-459` 断言 `minAge < maxAge`）→ **本次可顺手清理**
- **`gameAge` 字段全仓零命中**（勿按旧假设设计）
- 招募年刷链与 8 个 `last*Year` 字段已于 G05 批整删（`report-G02.md:17`、`report-G05.md:12`）

**仍存的"离散整数年"不变量（连续化的真正改动点）**：`reflectionEndYear`（字符串型 `statusData` 键）、
`deathYears` 清理阈值、`Alliance.startYear`、`merchantLastRefreshChanceGrantYear`、`tradeLastRefreshYear`、
`secretRealmCooldownYear`、`SectRelation.lastInteractionYear`、`VassalContract.establishedYear/lastTributeYear`、
`playerProtectionStartYear`。

**年变绑定存档字段**：`gameYear/gameMonth`；`annual*`（15 项，`@ProtoNumber(123)-(132),(135)-(137)`，`PRESERVE_OLD`）；
`yearlyReports`（`(158)`）；`yearlySalary`/`yearlySalaryEnabled`（`(10)(11)` 现役）；`merchantLastRefreshChanceGrantYear`（`(92)`）；
`merchantAcquisitionLastRefreshYear`（`(89)`，`USE_SHADOW`）；`merchantRefreshChances`（`(90)`）；
`lastYearSpiritStoneIncome`（`(117)`，`USE_SHADOW`）；`secretRealmCooldownYear`（`(213)`）；`SectDetail.tradeLastRefreshYear`；
`VassalContract.establishedYear/lastTributeYear`；`SectRelation.lastInteractionYear/noGiftYears`；`Alliance.startYear`；
`Disciple.deathYear`/`DiscipleStore.deathYears`；`playerProtectionStartYear`。

> ⚠️ **两个既有缺陷**（实施时需一并处理）：
> ① `lastYearSpiritStoneIncome` 唯一写点 `VassalService.recordYearlyIncome()`（`:110-116`）**全仓零调用**，
> C++ 仅读（`year_settlement.h:269`）→ 附庸年贡长期用 0 或旧值；
> ② `autoBuy` 内部是否消费 RNG **未确认**。
> **`SettlementStrategy` 影响改造**：年变字段分 `PRESERVE_OLD`（`annual*`/`yearlyReports`/`merchantRefreshChances`）
> 与 `USE_SHADOW`（`merchantAcquisitionLastRefreshYear`/`lastYearSpiritIncome`/玉符四字段）两类，改造须按策略同步调整合并语义。

**年变相关测试**：GTest `year_settlement_test.cpp`（33 用例，跨年界前 5 例必失败；直接调 `detail::` 传显式 year 的用例大多存活）、
`settle_phase_test.cpp:57-86`、`time_system_test.cpp:178-189`（4 旬 → 1 月 1 年，必失败）、
`column_export_equivalence_test.cpp:220,244`；JUnit `CultivationEventMonthlyOpsTest:126-181`（硬断言 8+8）、
`DiffYearSettlementTest:511-704`、`DiffAuthoritativeTickTest:442-555`（`TICKS=100`，断言 `gameYear==4`/`gameMonth==7`/年俸 `500×3×3`/`yearlyReports.size==3`）、
`YearlyOpsQueueTest:44-171`（与年界解耦，可存活）、`DiscipleLifecycleProcessorTest:122-187`、`CultivationSettlementConcurrencyTest:148-195`、
`SecretRealmServiceTest:215-263`、`GachaColorSingleSourceGuardTest:19,95,236`（钉住 `year_settlement.h` 文件路径）、
`ResidualExecutorPurityGuardTest:19,102,123-124`、`GameDataFieldPatchGuardTest:83-84,104,128,133,240-250`、`GameViewEventEnvelopeAssemblyTest:67`。

#### 3.4.4 L2 惰性生产 + 时间源

> 🔴 **重大发现：L2「惰性结算」名存实亡。** `LazyEvaluationDispatcher` 实例的 `shouldSettle()`
> （`:30-39`）与 `shouldSettleWithThermal()`（`:44-58`）在**全仓零调用点**（含 C++/脚本），
> 实例从未被注入 —— **是可删除的死代码**；`CODE_WIKI.md:987` 仍描述它含 `isInFocusDomain()`
> （该方法不存在）。真正在用的只有两个 companion 静态助手：`toAbsoluteMonth`（6 处）与
> `estimateMonthsToNextBreakthrough`（1 处）。
> **实际结算驱动是旬 tick（C++ 真相源），不是"UI 打开时才结算"** —— UI 只读
> `GameEngine.productionSlots` StateFlow 镜像。

**L2 时间戳差分 / 过期判定清单**：

| # | 项 | 位置（文件:行） | 公式 | 时间单位 | 精度损失 |
|---|---|---|---|---|---|
| A1 | 灵矿场月度产出 | `CultivationSettlement.kt:392-411`（调用 `:388-390`） | `currentMonth = year*12+month`；`monthlyRate = zones.calculateMonthly(baseOutput)`；`if (currentMonth > lastSettled && rate>0) delta = currentMonth - lastSettled; total = monthlyRate × delta`；末行无条件写 `spiritMineLastSettledMonth = currentMonth` | **游戏绝对月** | 无法表达不足 1 月；同月内重复结算恒 0；`<= lastSettled` 静默跳过（回档保护） |
| A2 | 灵矿存档字段 | `GameData.kt:344-347`；Proto `OldSerializableSaveData.kt:123`（`@ProtoNumber(138)`）；Room 列 `GameDatabaseMigrationsV11ToV20.kt:186-192`；旧档补丁 `GameEngineLoadDataOps.kt:225-235` | — | 月 | 同上 |
| A3 | 炼丹/锻造槽位完成时间 | 字段 `ProductionSlot.kt:54-92`；写入 `SlotStateMachine.kt:48-70`（`absMonth=(curY-1)*12+curM`；`completionMonth=absMonth+duration.coerceAtLeast(1)`；`completionPhase=2`）；`BuildingServiceSlotOps.kt:68-91,106-129`；`ProductionProcessorCleaOps3.kt:402-408`；`ProductionProcessorBatcOps4.kt:88-93` | `completionMonth = startAbsMonth + duration`；`completionPhase ∈ {1,2,3}` | **游戏绝对月 + 旬档位** | 严重：3 档无法表达月内任意时刻 |
| A4 | 槽位剩余/进度/完成判定 | `ProductionSlot.kt:111-124` → `TimeProgressUtil.kt:5-33` | `remainingMonths = duration - elapsed`；`progressPercent = elapsed/duration*100 toInt()` | 月 | `elapsed` 整月截断 → **进度条在月内完全不前进**（1 月 = 6 现实秒、仅 6 个离散状态） |
| A5 | 槽位动态完成判定（Checkpoint） | `ProductionProcessorFindOps5.kt:91-104` | `effectiveDuration = calculateWorkDurationWithAllDisciples(baseDuration, buildingId)`；`isTimeElapsed(...)` | 月 | 同上 |
| A6 | `checkpointAllProduction` 全量重算 | `ProductionProcessorFindOps5.kt:113-156` | 见下方语义分解 | 月 | `remainingMonths = round((1-progressRatio)*newDuration)` 整数月；**不重算 `completionPhase`**；写入在 `scope.launch(ioDispatcher)` **异步且非事务** |
| A7 | 灵田/灵植成熟 + 收获 | 字段 `GameDataFieldModels.kt:44-55`；收获 `ProductionProcessor处理Ops1.kt:266-299`；续种 `ProductionProcessor构筑Ops2.kt:93-112` | `elapsedMonths = (curY-plantYear)*12+(curM-plantMonth)`；`if (elapsedMonths >= effectiveGrowTime) 收获`；`completionPhase=3` | 月 | 光环缩减只作用整月；`>=` 整月判定，无月内结算 |
| A9 | 秘境界现世/期满 | `SecretRealmModels.kt:23-25`（Proto5/6 `spawnYear`/`spawnMonth`）；判据 `GameEngineSecretRealmOps.kt:70-72,141`；`SecretRealmService.kt:1061` | `gameYear >= realm.spawnYear + OPEN_YEARS` | **游戏年** | **最粗：粒度 = 1 年**；`spawnMonth` 只写不判 |
| A10 | 世界关卡（妖兽/洞府）过期 | 生成 `LevelGenerator.kt:198-201,246-249,211-214,259-262`；判据 `WorldLevel.kt:59-64` | `if (curY > expiryYear) true; if (curY==expiryYear && curM>=expiryMonth) true` | 月 | 整月；**当月上旬即过期** |
| A11 | 世界关卡刷新节拍 | `WorldLevelManager.kt:42-79`；`GameData.kt:290-293` | `lastRefreshMonth==0 \|\| (absMonth-lastRefresh) >= 3` | 月 | 整月；**`==0` 视为"从未刷过" → 读档后可能整批重刷**（无旧档补丁，见缺陷表） |
| A12 | 洞府（CultivatorCave） | `CultivatorCave.kt:224-263`；侦察生成 `GameEngineScoutOps.kt:263` | `remainingMonths = (expiryYear-curY)*12 + (expiryMonth-curM)` | 月 | 整月 |
| A13 | 任务完成 | `Mission.kt:326-342`（Proto9/10）；`CultivationEventMissionOps.kt:56-69`；`MissionSystem.kt:160-161` | `currentAbsoluteMonth >= start + duration && isComplete(year,month)` | 月 | 整月 |
| A14 | 联盟到期 | `GameDataAlliance.kt:14`（Proto3 `startYear`）；`DiplomacyEventProcessor.kt:41-47` | `year - startYear >= N` | **年** | 整年 |
| A15 | 商队刷新 | `DiplomacyService.kt:583-584`；`GameEngineLifecycleOps.kt:186-187` | 年差 | **年** | 整年 |
| A16 | 兑换码过期 | `RedeemCodeManager.kt:308-316`；`RedeemCode.kt:40-41` | `curY*12+curM > expireY*12+expireM` | 月 | 整月 |
| A17 | 突破月份预估 | `LazyEvaluationDispatcher.kt:70-78`；消费 `DiscipleBreakthroughHandler.kt:199-206`；C++ 同源 `breakthrough.h:134` | `ceil(remaining/ratePerPhase)` → `(phasesNeeded+2)/3` | 月（向上取整） | UI 倒计时最大误差 1 月 |
| A18 | 宗门等级奖励冷却（**墙钟**） | `SectLevelRewardCooldown.kt:26-31`；`GameEngineSectLevelOps.kt:62,347`；`GameData.kt:39`（Proto2）；C++ `jade_tx.h:170,183,250`、`models.h:1109` | `nowMs - lastClaimedAtEpochMs >= WEEK_MS` | **现实毫秒** | 精度足够；**可改钟作弊** |
| A19 | 玉符发放/跨天 | `JadeSymbolService.kt:435-483`；C++ 臂 `ActionIds.JADE_RUNTIME_DAY_RESET_TX` | 发放由单调钟；跨天用 `getTodayStartMs(wallClock)` | **现实毫秒** | 单 tick 差分上限 10s，OEM 挂起不补记 |
| A20 | 邮件 30 天过期 | `MailService.kt:92,137,152,165,234,392` | 现实 epoch ms | 现实毫秒 | 精度足够；**邮件不在 SaveData 快照内 → 不可回滚** |
| A21/A22 | 溢出发信 / 秘境过期邮件 | `OverflowMailSender.kt:74,194,219,242,304`；`MailAttachmentDistributeOps.kt:46,135,194`；`SecretRealmService.kt:1083,1109` | 墙钟 epoch | 现实毫秒 | OK |
| A23 | 建筑建造完成 | — | — | — | **不存在**（全仓无 `constructionEnd/underConstruction`） |

**槽位时间模型字段（连 Proto/Room 一起改）**：

| 字段 | 位置 | Proto | 单位 | 换代影响 |
|---|---|---|---|---|
| `startYear`/`startMonth` | `ProductionSlot.kt:53-56` | 8/9 | 游戏年/月 | → `startedAtGameMs` |
| `duration` | `:57-58` | 10 | 整月 | → 毫秒（或保留 Double 月） |
| `baseDuration` | `:59-62` | 22 | 配方基础月 | 同上 |
| `completionMonth` | `:87-89` | 19（default 0） | **绝对月** | 删或改 ms |
| `completionPhase` | `:90-92` | 20（default 1） | 旬档 1/2/3 | **删除**（3 档无意义；只有死代码读它，C++ `settlement.h` 完全不读，却经 `BuildingFacadeImpl生产UiOps.kt:199` 发往 native + `json_codec.cpp` 双向编解码 + Room 列 —— **最大的一块可删除负担**） |
| `remainingTime`/`getProgressPercent`/`isFinished` | `:111-124` | 派生 | 整月 | 三个签名改（现吃 `currentYear,currentMonth`） |

**`checkpointAllProduction()` 重算语义**（`GameEngineServiceOps.kt:92` → `CultivationServiceProductionOps.kt:12-14` → `ProductionProcessorFindOps5.kt:113-156`）：
① `currentMonth = year*12+month` → ② 遍历槽位 → ③ `effectiveBase = baseDuration>0 ? baseDuration : duration`（旧档回退）
→ ④ `oldDuration = duration.coerceAtLeast(1)` → ⑤ `elapsedMonths = max(0, (y-sy)*12+(m-sm))` → ⑥ `progressRatio = elapsed/old`
→ ⑦ `needsRecalc = isWorking && effectiveBase>0 && progressRatio<1.0 && 新 duration != slot.duration`
→ ⑧ `newDuration = calculateWorkDurationWithAllDisciples(...)` → ⑨ `newSuccessRate = recalculateSuccessRate(...)`
→ ⑩ `remainingMonths = round((1-progressRatio)*newDuration).coerceAtLeast(1)` → ⑪ `updateSlot { duration; completionMonth = currentMonth+remainingMonths; successRate }`
（**不重写 `startYear/startMonth`、不重算 `completionPhase`**；写入在 `scopeProvider.scope.launch(ioDispatcher)` 异步执行 —— 非单事务，存在丢更新/与月结竞态）。
触发点：`SectPolicyToggleUseCase.kt:116,203`、`ElderManagementUseCase.kt:160,258`、`GameEngineCorePausOps4.kt:132,139`。

**时间源 → 结算完整链路**：

```
[平台单调钟] AndroidTimeSource = SystemClock.elapsedRealtime()   PlatformTimeModule.kt:18-20
      │（AUTHORITATIVE 生产路径，真相源在 C++）
      ▼ C++ AndroidMonotonicClock = clock_gettime(CLOCK_BOOTTIME)   GameCoreBridge.cpp:187-217
GameEngineCore.authoritativeLoopIteration()                      GameEngineCoreLoopOps.kt:107-177
  ├─ nativeLoopFrame(pausedOrLoading, isSaving) → C++ EngineLoop::iterate   engine_loop.h:226-275
  │    ├─ deltaNs = min(now-lastFrame, kMaxAccumulatorNs=500ms)              :232-240
  │    ├─ pausedOrLoading → consumeDeadTime()+accumulator=0                  :243-249
  │    ├─ isSaving → consumeDeadTime(), tickKind=0                           :254-258
  │    └─ 每 tick PhaseClock::tick()：acc += wallDelta×speed；
  │         phases = acc/2000；cap = 3×max(speed,1)；超限 phases=cap 且 acc=0（丢弃余量） :134-158
  │    → LoopFramePlan（17 槽 LongArray）
  ├─ for step in 0 until plan.tickCount: tickAuthoritativeStep(tickPhases[step])   :141-151
  │    └─ sampleProgressSnapshot / tickThermalControl / processAuthoritativeTick(phases) :182-186
  │         └─ GameCoreBridge.nativeSettlePhase() → SettlementEngine::settleOnePhase  settlement.h:104-112
  │              advanceOnePhase → advancePhase(gd)：旬+1，满 3 进月，满 12 进年   time_system.h:25-40
  │              coreMode_==true → 只跑 onCoreSettle；月/年钩子不触发（flags 仍返回） settlement.h:136-141
  │            → applyDirtyFromNative（失败 → syncFromNative 全量兜底）
  │            → if flags != 0: processMonthYearChange(monthChanged, yearChanged)   :79-84
  │                 └─ yearChanged → settleYearNative()（回退 YearSettlementExecutor） GameEngineCorePausOps4.kt:95
  │                    monthChanged → settleMonthNative()（回退 MonthSettlementExecutor）
  │                    finalizeMonthBoundary() → missionCheck + flushPendingEvents + notifyMonthSettled
  └─ gameClock.mirrorFromNative(plan.accumulatedGameMs)   ← Kotlin GameTimeClock 降级为 UI 镜像
```

| 概念 | 位置 | 值/语义 |
|---|---|---|
| `kLogicDtNs` | `engine_loop.h:39` | 100ms —— **只控制 tick 调用频率，不参与时间换算** |
| `kMaxAccumulatorNs` | `:41` | `kLogicDtNs*5` = 500ms 单帧累积上限 |
| `kMaxStepsPerFrame` | `:43` | 5 → 逻辑 tick ≈ 10 Hz |
| 旬长度 | `settlement.h:31`；`GameTimeClock.kt:224` | `2000ms`@1x / `1000ms`@2x |
| `phaseCap` | `settlement.h:38-40`；`GameTimeClock.kt:240` | `3×max(speed,1)`；超限**丢弃余量**（双端由 `PhaseCapParityTest` 锁定） |
| `consumeDeadTime` | `engine_loop.h:161`；`GameTimeClock.kt:183-185` | 只刷 `lastWallMs`，**不累积游戏时间** |

**离线/后台语义（源码证据）**：

| # | 结论 | 证据 |
|---|---|---|
| D1 | **无离线收益** | 全仓无 offline 玩法实现；`SaveData` 无 lastSeen/离线时间戳 |
| D2 | 游戏内时间与墙钟彻底独立 | `GameEngineLoadDataOps.kt` 中 `currentTimeMillis` 零命中；生产/灵田全用 Int 绝对年月 |
| D3 | **切后台 = 停循环** | `GameActivity.onPause()` → `SaveLoadViewModel.kt:461-463` → `GameEngineCore.pauseForBackground()`（`:831-844`：`stopGameLoop()` + `resetHighFrequencyData()` + `_wasPausedByBackground=true`） |
| D4 | 回前台重启循环但保持暂停态 | `GameActivity.onResume()` → `resumeFromBackground()`（`GameEngineCore.kt:846-860`；KDoc 明确"时间不推进、无月变/年变"） |
| D5 | **暂停期间无追补** | native `paused` 分支 `consumeDeadTime()+accumulator=0`（`engine_loop.h:243-249`）；Kotlin 回退 `skipTickIfNeeded`（`GameEngineCorePausOps4.kt:75-87`） |
| D6 | **追补超限丢弃** | `GameTimeClock.kt:157-172`；`engine_loop.h:143-157`；Kotlin 消费侧二次 cap（`GameEngineCoreAuthoritativeOps.kt:56`） |
| D7 | 杀进程无追补 | `AlarmWatchdogReceiver` 只做看门狗拉起；拉起后走 `stopGameLoop`/重启而非时间补偿 |
| D8 | **⚠️ 反例（未确认）**：`START_STICKY` 后台重建可能无条件重启循环 | `GameForegroundService.kt:144` `return START_STICKY`；`:113-121` `ACTION_START, null -> if (!isGameLoopRunning) startGameLoop()` —— **null intent（系统重建）也启动循环，且不检查前台/暂停来源**。→ **"后台纯暂停"并非无条件成立，需真机验证** |
| D9 | 后台仍有闹钟链 | `AlarmWatchdogReceiver.scheduleAlarm`（每 15s，`GameForegroundService.kt:98`） |
| D10 | 后台保存 | `GameActivity.onStop()` → `triggerBackgroundSaveIfEnabled()`（默认开） |

**平台时间 API 使用点与 iOS 缺口**：

| 类型 | 位置 | 归类 |
|---|---|---|
| 可注入端口（正） | `TimeSource.elapsedRealtime()`（`GameTimeClock.kt:17-19`）；`AndroidTimeSource`（`PlatformTimeModule.kt:18-20`） | **玩法** |
| 可注入端口（正） | `WallClock.currentTimeMillis()`（`WallClock.kt:23-25`）+ `SystemWallClock`/`CalibratedWallClock` + Hilt 绑定（`:109-115`） | **墙钟概念**（玉符/邮件/冷却） |
| 可注入端口（正） | C++ `MonotonicClock`（`platform.h:32-59`）+ Android 实现（`GameCoreBridge.cpp:187-217`，`CLOCK_BOOTTIME`） | **玩法（C++ 真相源）** |
| ⚠️ 直调（绕开端口） | `SystemClock.elapsedRealtime()`：`GameEngineCoreLoopOps.kt:295,296`（忙等自旋）、`GameEngineCorePausOps4.kt:260`（fps 限频）、`EngineCrashReporterImpl.kt:40`、`AlarmWatchdogReceiver.kt:103`、`SystemBarFreezeScope.kt:48,119`、`DialogSystemBarFreezeScope.kt:51,154`、`TrimMemoryBridge.kt:46` | 混合（1 处属玩法） |
| ⚠️ 直调（**未走 `WallClock`，真缺口**） | `GameEngineNativeOps.kt:54,135`（`nowMs: Long = System.currentTimeMillis()` 默认参数）→ `nativeExecute(actionId, params, nowMs)` → `jade_tx.h:170,183,250`（宗门等级 7 天冷却） | **玩法（墙钟）** |
| ⚠️ 直调（测试默认，Hilt 是否覆盖未确认） | `MailService.kt:92`、`OverflowMailSender.kt:74`、`RedeemCodeService.kt:88`、`SecretRealmService.kt:88` | 墙钟概念 |
| 非玩法 | `System.nanoTime()`（渲染/动画/性能）；`System.currentTimeMillis()`（网络签名/WAL/存储/性能） | 非玩法 |
| **iOS 缺口** | 无 `ios/` 目录；缺：① `PlatformTimeModule` 的 iOS 绑定 ② C++ `AndroidMonotonicClock` 的 `mach_continuous_time` 等价实现 ③ 上表 6 处直调 `elapsedRealtime` 未走端口 ④ `GameEngineNativeOps.kt:54,135` 未走 `WallClock` ⑤ UI/平台层直调 | 项目规范要求对等实现，**当前全部未交付**（已有 `fun interface` 抽象可复用） |

**换时间模型的受影响位置（30+ 处，含 SQL 时间运算）**：

| # | 文件:行 | 类型 | 说明 |
|---|---|---|---|
| F1 | `ProductionSlot.kt:17-28` | **Room Entity** | `production_slots` 表结构变更 |
| F2 | `ProductionSlot.kt:53-62,87-92` | **Entity 字段 / ProtoBuf** | `startYear/startMonth/duration/baseDuration/completionMonth/completionPhase`；**字段号禁复用** → 新号或 `reserved` |
| F3 | `ProductionSlot.kt:111-124` | 计算 | 三个派生方法改签名 |
| F4/F5 | `GameDatabase.kt:95`（`DATABASE_VERSION=59`）、`:67-81`（`ALL_MIGRATIONS`） | DB 版本 + 注册表 | → 60 + `MIGRATION_59_60` |
| F6 | 新增 `GameDatabaseMigrationsV60.kt` | Migration | 需 create-copy-drop-rename（禁 `DROP COLUMN`） |
| F7 | `android/core/data/schemas/.../59.json` → 新增 `60.json` | schema JSON | 不提交即 schema 校验失败 |
| F8 | `RoomMigrationSupport.kt:216,234` | 测试 | 硬编码列清单 |
| F9 | `GameDatabaseMigrationsV2ToV10.kt:50-69`；`GameDatabaseMigrationsV11ToV20.kt:179-184` | 历史 Migration | 新列需保证历史链仍可插默认值 |
| **F10** | `ProductionSlotDao.kt:60-65` | **SQL 时间运算** | `WHERE (startYear*12+startMonth+duration) <= :currentYear*12+:currentMonth` —— **换 ms 后必须重写** |
| F11 | `ProductionSlotDao.kt:118-123` | SQL | `batchUpdateStatus` 列名耦合 |
| F12 | `SaveData.kt:81` | **ProtoBuf** | `@ProtoNumber(52) productionSlots: List<ProductionSlot>` |
| F13 | `OldSerializableSaveData.kt:892-918` | **ProtoBuf 向后兼容** | `SerializableProductionSlot`（8/9/10/19/20/22 号）需能读旧整数并换算 |
| F14 | `CollectionConverters.kt:371-377` | 序列化转换 | `ListSerializer` → Base64 存 `game_data.productionSlots` TEXT 列 |
| F15 | `TimeProgressUtil.kt:5-33` | 计算核心 | 5 个方法全为年月整数签名 → 整体重写或废弃 |
| F16-F20 | `SlotStateMachine.kt:43-72`；`ProductionProcessorFindOps5.kt:91-156`；`BuildingServiceSlotOps.kt:68-129`；`BuildingFacadeImpl.kt:367-468` + `同步Ops:245-248`；`ProductionProcessor{构筑Ops2:93-112,CleaOps3:402-408,BatcOps4:88-93}` | 计算 | 时间字段读写点 |
| F21 | `BuildingFacadeImpl生产UiOps.kt:185-199` | **镜像协议（Kotlin→C++ 事务参数）** | `put("startYear"/"startMonth"/"completionMonth"/"completionPhase")` |
| F22 | `GameDataFieldPatch.kt:195,198-199` | **镜像协议（C++→Kotlin）** | 经 `game_view.proto` 的 `JsonFieldChange`（`:79 gameDataChange=7`、`:334`）解码为 `List<ProductionSlot>` |
| F23 | C++ `models.h:567-592` | **C++ 镜像模型** | `int32_t startYear/startMonth/duration/baseDuration/completionMonth/completionPhase` |
| F24 | C++ `json_codec.cpp:435,448,796,802` | C++ 编解码 | `GC_TO/GC_FROM`（ProductionSlot + SpiritFieldPlant 两处） |
| F25 | C++ `execute_dispatch.cpp:1572-1573` | C++ 事务入口 | 读 `params["completionMonth"]/["completionPhase"]` |
| F26-F28 | `SlotRefRule.kt:8,23-24,73`；`BloodPoolBuildingCleanupRule.kt:32-46`；`GameDateRule.kt:6-24` + `GamePhaseRangeRule.kt:6-21` | 存档校验 | 引用修复 + 时间越界校验（`gamePhase` 退役需同步改） |
| F29 | `StorageEngine.kt:715` `validateSaveData` | 校验入口 | 新时间字段校验须 `registerDefaults()` 加一行 |
| F30 | `CultivationSettlement.kt:392-411` 等（灵矿全链） | 同构改造点 | `spiritMineLastSettledMonth` 同类绝对月差分 |
| F31 | `GameDatabaseMigrationSupport.kt:117-124` | Migration 辅助 | 重建 `game_data` 的 CREATE 模板 |
| F32 | `DiffSpiritFieldTest.kt:78-138`、`DiffProductionSettlementTest.kt:91-102`、`DiffMonthSettlementTest.kt:90`、`production_test.cpp:444-700`、`spirit_field_test.cpp:79-80`、`ProductionSlotSettlementRobustnessTest.kt:231-297`、`ProductionUiNativeTxGateTest.kt:300-343` | 测试 | golden 全量依赖整数时间语义，换模型须整体重录 |

**L2 章新增发现的预存缺陷（实施时一并处理）**：

| 缺陷 | 位置 | 说明 |
|---|---|---|
| `LazyEvaluationDispatcher` 是可删死代码 | `LazyEvaluationDispatcher.kt:30-58` | 零调用点、从未注入；`CODE_WIKI.md:987` 描述的方法不存在 |
| `completionPhase` 是"死字段但被全链搬运" | `ProductionSlot.kt:90-92` | 只有死代码读它；C++ `settlement.h` 不读；却经 native tx 参数 + `json_codec` 双向 + Room 列 —— **可删除负担最大的一块** |
| `worldLevelLastRefreshMonth == 0` **无旧档补丁** | `WorldLevelManager.kt:51` | 与灵矿（`GameEngineLoadDataOps.kt:225-235`）、商队（`GameEngineLifecycleOps.kt:186-187`）三处同型问题中唯一未打补丁者 |
| **墙钟双源不一致** | `GameEngineSectLevelOps.kt:62,347`（走 `CalibratedWallClock`）vs `GameEngineNativeOps.kt:54,135`（默认 `System.currentTimeMillis()`，未校正）→ `jade_tx.h:183` | 同一判据两路径可能给出不同答案 |
| `checkpointAllProduction` 写入**异步且非事务** | `ProductionProcessorFindOps5.kt:144-153` | "政策切换瞬间 + 月结并发"存在缓存/DAO 分叉风险 |
| `START_STICKY` 可能后台重启循环 | `GameForegroundService.kt:144,113-121` | 与"后台纯暂停"口径冲突，需真机验证（D8） |
| `processAuthoritativeTick` KDoc 过期 | `GameEngineCoreAuthoritativeOps.kt:45-46` | 仍写"墙钟消费/速度/暂停/refundPhases 仍由 Kotlin 独占"，实际已迁 C++ `PhaseClock` |
| `docs/longrun-stability-audit-report.md:253-254` 的 P2-16 记录已过期 | — | 源码 `GameEngineCoreAuthoritativeOps.kt:56` 已改为按速度缩放，文档未回改 |

#### 3.4.5 L3 月变结算补充明细（`month_settlement.h`）

**以"每月"为单位的数值/公式清单（改造 = ÷6 转"每游戏秒"，或改判定次数）**：

| 类别 | 项 | 位置 | 现状公式/常量 | 改造口径 |
|---|---|---|---|---|
| 政策开销 | 12 项政策月度灵石扣除 | `GameConfig.kt:765-776`（`SPIRIT_MINE_BOOST_MONTHLY=0`、`ENHANCED_SECURITY_MONTHLY=3000`、`ALCHEMY_INCENTIVE_MONTHLY=3000`、`FORGE_INCENTIVE_MONTHLY=3000`、`HERB_CULTIVATION_MONTHLY=3000`、`MANUAL_RESEARCH_MONTHLY=4000`、`CURFEW_MONTHLY=1000`、`REWARD_PUNISH_MONTHLY=3000`、`STRICT_TRAINING_MONTHLY=20000`、`RELAXED_MGMT_MONTHLY=3000`、`SPIRIT_SPRING_MONTHLY=2000`、`FRUGALITY_MONTHLY=0`） | 每游戏月一次性扣除 | **改连续税率**：`每游戏秒 = 常量 ÷ 6.0`，按 Δt 累积扣（总额等价，粒度连续） |
| 政策道德/忠诚 | 7 项 `*_PER_MONTH` | `GameConfig.kt:824-831`（`MORAL_EDUCATION_PER_MONTH=1`、`BENEVOLENT_LOYALTY_PER_MONTH=1`、`RELAXED_MGMT_LOYALTY_PER_MONTH=2`、`STRICT_TRAINING_LOYALTY_PER_MONTH=-1`、`ENHANCED_SECURITY_LOYALTY_PER_MONTH=-1`、`CURFEW_LOYALTY_PER_MONTH=-1`、`BENEVOLENT_GOVERNANCE_PER_MONTH=1`） | 每月 +/− 整数点，带上限 clamp | **改小数累积 + 达阈值进位**（INV-6）；C++ 实现在 `month_settlement.h:113-130`（`kMoralEducationMax` 上限） |
| 灵矿产出 | 月度产出结算 | `month_settlement.h:668-685`（乘区构建 `:619-666`） | `currentMonth = year*12+month`；`monthlyRate = calculateSpiritMineMonthly(zones, kSpiritMineBaseOutputPerMiner)`；`if (currentMonth > lastSettled && rate>0) total = monthlyRate × (currentMonth - lastSettled)`；写 `spiritMineLastSettledMonth = currentMonth` | **改连续积分**：`rate ÷ 6.0 × Δ秒`；字段 `spiritMineLastSettledMonth` → `spiritMineLastSettledGameMs`（保留旧字段，见 §4.1） |
| 丹药时长 | 月度持续效果衰减 | `month_settlement.h:85,549-580`（`kMonthlyDecayPhases=3`）；`HpMpRecoveryService.kt:265-296` | `newDuration = pillEffectDuration - (3 - focusedPhaseCount)`，`focusedPhaseCount` 恒 0（即每月扣 3 旬） | **duration 字段语义改毫秒/秒**；否则与 L1 按秒推进**双计**（INV 缺陷表第 17 项） |
| 阈值/限额 | 偷盗判定上限 / 招募月限 / 月度事件日志上限 | `GameConfig.kt:881`（`MAX_THEFT_JUDGEMENTS_PER_MONTH=3`）、`:722`（`RECRUIT_MONTHLY_LIMIT=30`）、`:716`（`MAX_MONTHLY_EVENT_LOGS=50`） | 每游戏月上限 | **窗口长度可改"每 6 游戏秒窗口"**（数值等价），或改连续速率上限——需产品确认（属经济口径） |
| 任务刷新 | 每 N 月刷新 | `MissionSystem.kt:75-85`（`currentMonth % REFRESH_INTERVAL_MONTHS == 0`） | 按绝对月取模 | 改"累计游戏秒跨过 N×6 秒"判定 |
| AI 月度折算 | AI 弟子修炼/孕养/熟练度"3 旬 = 1 月" | `ai_sect_ops.h:77`（`kAiPhasesPerMonth=3`）、`:239,274,332`；`AISectDiscipleManager.kt:169`；`AISectDiscipleManagerMisc.kt:135,204,234` | `cultivation = base + speed×3`；`kMonthlyGain = kNurtureGainPerPhase × 3.0` | **属 L3 而非 L1**（最易漏）；改为 `perGameSecond × Δ秒`，`×3` 折算整体退役 |
| 月度系统扇出 | 四系统 `@SystemPriority` 扇出 | `month_settlement.h:46-48`（Alchemy 210 → Forge 211 → Planting 214 → Exploration 240） | 每月一次 `onMonthlyEvent` | 积分型移入连续轨；判定型保留 |
| **自动存档触发** | 月变尾务 → `notifyMonthSettled()` | `GameEngineCorePausOps4.kt:157-162` → `GameEngineCore.kt:542-543` → `SaveLoadViewModel.kt:257` → `SaveLoadViewModelAutoSaveOps.kt:21-24`（`AutoSaveTrigger.MONTHLY`）；旗标 `SaveTriggerFlag.kt:26` | 每月自动存档一次 | 🔴 取消月界即取消触发源 → 必须在 §4.6 裁决（删除或改现实时间节流） |

**L3 逐项常量现状（改造的"改什么值"账本）**：

| 项 | 常量 | 现值 | 位置 | 是否已外置 |
|---|---|---|---|---|
| 灵矿基准产出/人 | `kSpiritMineBaseOutputPerMiner` | 170 | `government.h`；`game_config.json:34` | ✅ 已外置 |
| 灵矿采矿阈值 | `kSpiritMineMiningThreshold` | 70 | 同上（`:35`） | ✅ |
| 采矿加成率 | `kSpiritMineMiningBonusRate` | 0.02 | 同上（`:36`） | ✅ |
| 灵矿增产倍率 | `kSpiritMineBoostMultiplier` | 1.2 | `government.h:202-204`；Kotlin `CultivationSettlement.kt:422-423` | ❌ 硬编码双份 |
| 执事道德加成率 | `kDeaconMoralityBonusRate` | 0.01 | 同上 | ❌ 硬编码双份 |
| 执事道德基准 | `kElderSkillBaseline` | 80 | `government.h:206`；`disciple_stats.h:45`；`production.h:69` | ❌ 三处硬编码 |
| 道德月增量 / 上限 | `kMoralEducationPerMonth` / `kMoralEducationMax` | 1 / 70 | `government.h:53-54,186-189`；`month_settlement.h:113-130` | ❌ |
| 丹药月衰减旬数 | `kMonthlyDecayPhases` | 3 | `month_settlement.h:85` | ❌ |
| 任务刷新间隔 / 上限 | `kRefreshIntervalMonths` / `kMaxRefreshCount` | 3 / 6 | `mission_settlement.h:512,484-485`；`MissionSystem.kt:43` | ❌ |
| 关卡刷新间隔 / 新关卡上限 | `kLevelRefreshIntervalMonths` / `kMaxNewLevelsDefault` | 3 / 6 | `exploration.h:36,39` | ❌ |
| 秘境开启年数 | `kOpenYears` | 5 | `secret_realm_settlement.h:53,199`；`GameConfig.kt:1107-1111` | ❌ |
| AI 兽袭概率基/上限 / 最少弟子 / 冷却 | `kAiAttackProbBaseMultiplier` / `kAiAttackProbCap` / `kAiMinDisciplesForAttack` / `kAiSkipCooldownMonths` | 0.3 / 0.9 / 10 / 12 | `month_settlement.h:1075-1081,1152-1172` | 仅 `minDisciplesForAttack` ✅（`game_config.json:122`） |
| AI 热控分批 | 正常/降频/应急 | 3 / 6 / 12 | `ai_sect_ops.h:79-81,878-891`；`AISectBattleProcessor.kt:121` | ❌ |
| 附庸脱离基率档位 | 按战力比 | 0.0 / 0.05 / 0.12 / 0.20 / 0.35 | `sect_decision.h:46-50,192` | ❌ |
| 弟子购买上限 | 功法/装备/丹药 | 2 / 2 / 10 | `disciple_purchase.h:57-61` | ❌ |
| 12 月自动购买门 | — | `gameMonth == 12` 字面量 | `month_settlement.h:1017-1019` | ❌（**无"上次购买月"时间戳字段**，连续化需新增） |
| 灵田 growTime | 按种子 | 36/72/240/540/840/1440 | `android/app/src/main/assets/data/game-data.json` `db.seeds[].growTime` | ✅ 已外置 |
| 政策按弟子数单价 | 修行津贴/苦修令/教化之道/仁政爱徒 | 300（化神下）/800/100/100 | `government.h:159-178`；`GameConfig.kt:778-781` | ❌ 硬编码（`game_config.json:95` 的 `cultivationSubsidyCost=4000` **同名不同义，权威性未确认**） |
| 时间尺度 | `kMsPerPhase1x` / `kMaxPhasesPerTick` / 3 旬/月 / 12 月/年 | 2000 / 3 / 3 / 12 | `settlement.h:31-32`；`time_system.h:20-21`；`game_config.json:19-29`（`secondsPerRealMonth=6`） | ✅ 部分外置（C++ 为同值硬编码副本，`GameTimeClockPhaseCapParityTest` 锁定） |

**月变绑定存档时间戳字段**：`spiritMineLastSettledMonth`（月）、`worldLevelLastRefreshMonth`、
`merchantAcquisitionLastRefreshYear`、`tradeLastRefreshYear`、`lastTributeYear`、
`lastInteractionYear`/`noGiftYears`、`secretRealmCooldownYear`、
`production.completionMonth`/`completionPhase`、`plant.completionMonth`/`completionPhase`、
任务 `startYear`/`startMonth`、`missionCompletionMonth`、`RedeemCode.expireYear/expireMonth`、
`GameEventRecord.phase`。逐字段的换代建议见 §3.4.3（年变）与 §3.4.4（L2）。

**月变相关测试（必失败优先）**：GTest `month_settlement_test.cpp`（50+ 用例，`:87-1502`），
关键 `PillDurationDecayGoldenSequence`（`:144`，锁定"每月扣 3 旬"）、`VassalBreakaway*`（8 例，每契约恰 1 抽）、
`RecruitResetAndSystemDrawOrderLock`（`:390`，SYSTEM 抽取序）、`SpiritFieldHarvestSeedRollAndClearGolden`（`:186`）、
`WorldLevelsCleanupMoveAndExplorationAudit`（`:249`，EXPLORATION 恰 4 次）、`MissionSettlement.RefreshMonthGeneratesMissions`（`:1114`）、
`AutoBuySettlement.DecemberAutoBuy*`（3 例）、辅助 `crossMonth()`（`:78-83`，**全文件依赖**）；
JUnit `DiffMonthSettlementTest`（`:410,444,476,506,547`）、`GameEngineCoreMonthSettledTest`（`:70,87,99`）、
`SpiritMineMonthlySettlementTest`（13 例，**差分化范式可复用**）、`SettlementTransactionMergeTest`（`:43,72`）、
`SaveLoadViewModelAutoSaveTest`（`:92-236`，月变自动存档门）、`SecretRealmCountdownTest`、`AISectBattleProcessorTest:258`。
**不会失败（可直接复用）**：`SpiritMineMonthlySettlementTest:151-194`、`SpiritMineTest.SettleDifferential/SettleNoOlderSkips`（`government_test.cpp:126,139`）。

### 3.5 测试

- GTest（`android/app/src/main/cpp/gamecore/test/`）—— 重定基准，清单见 §3.4.1 尾部
- JUnit 对拍（`android/core/engine/src/test/.../nativebridge/`）—— 重定基准，清单见 §3.4.1 尾部
- 守卫测试 —— `GameTimeClockPhaseCapParityTest`/`BaselineFieldCoverageGuardTest`/`GameViewStoreGuardTest`/`GameDataFieldPatchGuardTest` 需按新字段面更新
- 新增 —— `ContinuousAccrualEquivalenceTest`（GTest）、`DiffRealtimeAccrualTest`、`DiffLegacySaveNormalizationTest`、`RealtimeRngSequenceGuardTest`、`GameTimeUnitsParityTest`、`RoomMigrationV59To60Test`（见 §5.1）

### 3.6 文档与规范（🔴 必须同批更新，否则规范与代码冲突）

- `rules/expansion-playbook.md:22,63-67` — **改写** — 第 7 项"禁止以现实时间为准"与离线收益预留节（本方案与之直接冲突，见 §4.6）
- `rules/economy-design.md:32-39` — 改写 — 离线收益数学从"预留"转"已定稿"
- `docs/architecture.md:75-146` — 改写 — 惰性结算引擎章节改为双轨时间
- `docs/knowledge-base.md:685,687,689-707` — 改写 — 离线收益/流速/经济基线表
- `docs/cpp-engine.md` — 改写 — C++ 结算入口清单
- `docs/ui-read-surface.md` — 登记 — 新增 UI 可读时间字段
- `docs/threading-contract.md` — 登记 — 离线注入的跨线程路径
- `docs/platform-abilities.md` — 登记 — 时间端口 iOS 对等实现
- `CHANGELOG.md` + `android/app/src/main/assets/changelog_entries.json` — 更新 — 双更新日志（`rules/version-release.md`）

---

## 四、兼容性分析

### 4.1 存档 schema（🔴 最高风险，先读 `rules/database-migration.md`）

当前 `GameData` 是**单一巨型 Room Entity**（`GameData.kt:71-118`，带 `@Entity` + `@ProtoNumber` + 索引
`Index(value=["gameYear","gameMonth"])`），云存档走 ProtoBuf 双路径。因此时间字段改造**必然**触发：

1. `@Database(version)` 递增 + `MIGRATION_N_M` + `build()` 注册 + schema JSON 入库
   —— **当前 `GameDatabaseConfig.DATABASE_VERSION = 59`**（`android/core/data/.../local/GameDatabase.kt:95`），
   故本次为 **v59 → v60**：新增
   `android/core/data/src/main/java/com/xianxia/sect/data/local/GameDatabaseMigrationsV60.kt`（`MIGRATION_59_60`），
   注册进 `GameDatabase.kt:68-80` 的 `ALL_MIGRATIONS`，并接受
   `MigrationChainGuardTest`（连续性守卫）+ `RoomMigrationV59To60Test` 约束
2. `GameDatabaseConfig.DATABASE_VERSION` 同步（禁止硬编码，`rules/database-migration.md:28`）
3. `SaveData` ProtoBuf 字段（取 1000+ 预留段编号，编号禁复用）
4. `SaveValidator` 规则注册（新增时间字段范围校验）
5. Room → ProtoBuf **双路径同步**（漏一侧 = 云存档与本地不一致）

**迁移策略（只增不删）**：

| 字段 | 变更 | 迁移 |
|---|---|---|
| `gameYear/gameMonth/gamePhase` | **保留**（派生投影 + UI 免改） | 不动（旧档直接可用） |
| `elapsedGameMs`（新增，Long） | 权威时间轴 | `ADD COLUMN elapsedGameMs INTEGER DEFAULT 0` |
| `lastSettleGameMs`（新增） | 连续积分差分基准 | `ADD COLUMN ... DEFAULT 0` |
| `ProductionSlot.completionMonth/Phase` | **保留**（兼容旧档与旧包） | 不动；新增 `completeAtGameMs` |
| `ProductionSlot.startAtGameMs`（新增） | 连续时长模型 | `ADD COLUMN ... DEFAULT 0` |
| `spiritMineLastSettledMonth` / `worldLevelLastRefreshMonth` 等 `last*Month/Year` | **保留 + 新增毫秒孪生字段** | 新字段默认 0 → 读档归一化时按旧字段换算回填 |

**🔴 必须迁移的"绝对月 / 年"时间戳字段全清单（27 项，L3 盘点实测）**：

| 字段 | 声明位置 | Proto | 语义 | 改造后形态 |
|---|---|---|---|---|
| `spiritMineLastSettledMonth` | `GameData.kt:344-347`；`models.h:1237` | 138（`PRESERVE_OLD`） | 灵矿上次结算绝对月 | `Long` 毫秒 + `Double` 月余量（避免取整漂移） |
| `worldLevelLastRefreshMonth` | `GameData.kt:290-293`；`models.h:1230` | 97 | 关卡上次刷新绝对月 | `Long` 毫秒；门改 `nowMs−lastMs ≥ 3×月时长` |
| `recruitCountThisMonth` | `GameData.kt:536-539`；`models.h:1223` | 163 | 本月已招募计数 | 保留计数 + 新增 `recruitWindowStartMs: Long`（滚动窗口） |
| `productionSlots[].completionMonth` | `ProductionSlot.kt:87-89`；`models.h:590` | 19 | 槽位预期完成绝对月 | `Long` 毫秒 + 保留 `durationMs: Long` |
| `productionSlots[].completionPhase` | `ProductionSlot.kt:90-92`；`models.h:591` | 20 | 完成旬 1/2/3 | 退役为兼容列（`@Ignore`） |
| `spiritFieldPlants[].completionMonth` | `GameDataFieldModels.kt:53`；`models.h:924` | 9 | 灵田成熟绝对月 | `Long` 毫秒 |
| `spiritFieldPlants[].completionPhase` | `GameDataFieldModels.kt:54`；`models.h:925` | 10 | 成熟旬 3 | 退役为兼容列 |
| `disciples[].cultivationCompletionMonth` | `Disciple.kt:101`；`models.h:324`；`disciple_store.h:175` | 94 | 下次修炼完成绝对月 | `Long` 毫秒 |
| `disciples[].manualCompletionMonth` | `Disciple.kt:105`；`models.h:326` | 96 | 功法研习完成绝对月 | `Long` 毫秒 |
| `disciples[].equipmentNurturingCompletionMonth` | `Disciple.kt:109`；`models.h:328` | 98 | 装备孕养完成绝对月 | `Long` 毫秒 |
| `usage.recruitedMonth` | `DiscipleComponents.kt:165`/`DiscipleCore.kt:27` | 59 | 入伍绝对月 | `Long` 毫秒（`recruitedAtMs`） |
| `lastSaveTime` | `GameData.kt:327-330`；`models.h:1242` | 32 | 存档时间（**注释明确"不用于离线时间差"**） | **已是毫秒，无需改造** |
| `merchantLastRefreshYear` | `GameData.kt:220-222`；`models.h:1218` | 18 | 商人列表刷新年 | `Long` 毫秒 |
| `merchantLastRefreshChanceGrantYear` | `GameData.kt:231-234`；`models.h:1221` | 92 | 上次获手动刷新次数年份 | `Long` 毫秒 |
| `merchantAcquisitionLastRefreshYear` | `GameData.kt:246-249`；`models.h:1326` | 89（`USE_SHADOW`） | AI 收购刷新年 | `Long` 毫秒 |
| `tradeLastRefreshYear` | `GameDataWorldModels.kt:76`；`models.h:863` | 9 | AI 交易列表刷新（每 3 年） | `Long` 毫秒 |
| `lastGiftYear` | `GameDataWorldModels.kt:77`；`models.h:864` | 10 | 上次赠礼年（好感衰减门） | `Long` 毫秒 |
| `lastTributeYear`（联盟） | `GameDataAlliance.kt:49`；`models.h:780` | 3 | 上次纳贡年 | `Long` 毫秒 |
| `SectRelation.lastInteractionYear` | `SectRelation.kt:19`；`models.h:788` | 4 | 上次互动年 | `Long` 毫秒 |
| `playerProtectionStartYear` | `GameData.kt:516`；`OldSerializableSaveData.kt:66`；`DiplomacyState.kt:23` | 47 | 保护期起始年 | `Long` 毫秒 |
| `secretRealmState.spawnYear` | `SecretRealmModels.kt:23`；`models.h:966` | 5 | 秘境现世起始年（+5 到期） | `Long` 毫秒 + `openDurationMs` |
| `worldLevels[].spawnYear` | `WorldLevel.kt:23`；`models.h:1081` | 11 | 关卡生成年 | `Long` 毫秒 |
| `VassalContract.establishedYear/lastTributeYear` | `models.h:780` | 嵌套 | 附庸建立/上次纳贡年 | `Long` 毫秒 |
| `Alliance.startYear` / `WorldSect.allianceStartYear` | `models.h` | 嵌套 | 联盟起始年 | `Long` 毫秒 |
| `Disciple.deathYear` / `DiscipleStore.deathYears` | `models.h:312`；`disciple_store.h:165` | — | 死亡年（尸体清理基准） | `Long` 毫秒 |
| `ActiveMission.completionMonth` | `models.h:924` | — | 任务完成绝对月 | `Long` 毫秒 |
| `AiMonthBatchState.lastSettleMonth` | `ai_sect_ops.h:872` | — | AI 热控分批基准（**纯内存，不入档**） | `Long` 毫秒；注意读档首调相位对齐（`month_settlement.h:1235`） |
| `yearlyReports` / `annual*`（15 项） | `GameData.kt:700-765` | 120-137,158 | 年报快照与年累计清零 | 取消年界需改**滚动窗口统计** |
| `lastYearSpiritStoneIncome` | `GameData.kt:649`；`models.h:1279` | 117（`USE_SHADOW`） | 上年灵石收入 | 保留（统计口径，非时间戳） |

> 🔴 **已退役字段（字段号禁复用，`reserved` 保护）**：`annualTheftCount`/`theftJudgementsThisMonth`（`GameData.kt:742`，v55 退役）、
> `lastRecruitYear`/`last_ai_sect_recruit_year`/`open_recruitment_last_paid_month`/`autoRecruitSpiritRootFilter`/`autoRejectSpiritRootFilter`
> + `sect_policy_state.autoRecruitSpiritRootFilter`（`GameDatabaseMigrationsV56.kt:12-23`，v56 删除）。
> **本方案不得复活这些字段名或字段号**；需要类似门控时用全新编号。

**第二张必须迁移的表：`production_slots`**（`ProductionSlot.kt:17-28`，`primaryKeys=["id","slot_id"]`）——
换时间模型时它是硬阻塞项，且带 **SQL 时间运算**与 **ProtoBuf 嵌套**：

| 面 | 位置 | 迁移要求 |
|---|---|---|
| Entity 字段 | `ProductionSlot.kt:53-62,87-92`（Proto 8/9/10/22/19/20） | 新增 `startedAtGameMs`/`completeAtGameMs`（新 `@ProtoNumber`，**编号禁复用**）；旧字段保留（`@ProtoNumber` 不动） |
| **SQL 时间运算** | `ProductionSlotDao.kt:60-65`：`WHERE (startYear*12 + startMonth + duration) <= :currentYear*12 + :currentMonth` | **必须重写**为毫秒比较；`batchUpdateStatus`（`:118-123`）列名同步 |
| ProtoBuf 嵌套 | `SaveData.kt:81`（`@ProtoNumber(52) productionSlots`）；向后兼容 `OldSerializableSaveData.kt:892-918` | 旧档整数时间必须能读入并换算；新字段走新编号 |
| 序列化转换 | `CollectionConverters.kt:371-377` | `ListSerializer` → Base64 存 `game_data.productionSlots` TEXT 列，字段改名同步 |
| 镜像协议 | `BuildingFacadeImpl生产UiOps.kt:185-199`（Kotlin→native tx 参数）；`GameDataFieldPatch.kt:195,198-199` + `game_view.proto:79,334`（C++→Kotlin）；C++ `models.h:567-592`、`json_codec.cpp:435,448,796,802`、`execute_dispatch.cpp:1572-1573` | 协议字段改名/改型即**镜像协议变更**（双端 + proto 注释同步） |
| 派生方法 | `ProductionSlot.kt:111-124`（3 个吃 `currentYear,currentMonth` 的方法） | 改签名 |
| 计算核心 | `TimeProgressUtil.kt:5-33`（5 个方法全为年月整数签名） | 整体重写或废弃 |
| 存档校验 | `SlotRefRule.kt:8,23-24,73`；`BloodPoolBuildingCleanupRule.kt:32-46`；`GameDateRule.kt:6-24`；`GamePhaseRangeRule.kt:6-21` | 时间越界校验同步；`gamePhase` 退役需改规则 |
| 校验入口 | `StorageEngine.kt:715` `validateSaveData` | 新规则须 `registerDefaults()` 加一行 |
| schema JSON | `android/core/data/schemas/.../60.json` | KSP 导出并入库（不提交即校验失败） |
| Migration 测试 | `RoomMigrationSupport.kt:216,234`（硬编码列清单） | 同步列名 |

**旧档归一化（一次性、幂等）**：读档路径在 `importStateInternal` 的归一化族
（先于 `resetBaseline`，沿 `rules/database-migration.md:152` 的 `mapGenVersion` 先例）执行：

```
若 elapsedGameMs == 0 且（gameYear/gameMonth/gamePhase 非初值）：
    elapsedGameMs = calendarToGameMs(gameYear, gameMonth, gamePhase)
    lastSettleGameMs = elapsedGameMs
   全部 last* 字段按旧单位 → 毫秒换算回填，新毫秒字段置同值
```

**语义等价性验收**：旧档升级后，**各面板读数与旧版同刻读数一致**（不是"接近"）。
验收方式：`DiffLegacySaveNormalizationTest` —— 同一旧档二进制，
在改造前/后两个构建上各跑 N 旬，逐字段对拍。

### 4.2 序列化与确定性

- 新增毫秒字段为 `Long`，**不参与浮点运算**；积分量用 `Double`，与既有 `cultivation` 同精度类别。
- RNG：**判定次数公式必须与改造前逐位等价**。风险最高的点 = 原来"每旬恰好消费 K 次随机"的
  判定（突破/丹药/偷盗），改成"每 tick 判定"会立刻改变序列 → 由 INV-3（事件计数）消除。
- 双端一致性：C++ 与 Kotlin 的整数除法/取整语义必须逐位一致（沿 `cpp/AGENTS` 既有 IE754 纪律），
  新增 `GameTimeUnitsParityTest`（双端各自锁定同一常量与同一换算公式）。

### 4.3 存档校验与自愈

- `SaveValidator` 新增规则：`elapsedGameMs >= 0`、`elapsedGameMs` 与日历投影一致性
  （不一致时以 `elapsedGameMs` 为准重算投影，并记日志）——防手改存档造成双真相源。
- 时间回拨：单调时钟天然免疫（`GameTimeClock.kt:26-28`）；离线跨"改系统时间"不可加速（沿用 `elapsedRealtime`）。

### 4.4 回滚路径

- 灰度旗标 `NativeEngineFlag.realtimeAccrual`（默认 false = 旧行为臂，跨一个版本周期）。
  沿既有旗标先例（`mirrorProtobufTransport` / `dirtyColumnExport` / `sceneStoreRender`，
  `docs/cpp-engine.md` 头部批次记载）。
- 双臂必须**同值**（换来源不换值）——由双存储对拍测试锁定（沿用 `DiffMirrorArmConvergenceTest` 模式）。
- 存档**不因回滚受损**：旧臂读取时忽略毫秒字段，按旧字段继续；新臂从毫秒字段继续。
  两臂切换点必须重算 `lastSettleGameMs`（否则回滚后积分基准错位）。

### 4.5 版本与发布

- 本次为**大版本行为变更**（玩家可感知：流速体感、离线收益、面板读数变化），
  需按 `rules/version-release.md` 更新双更新日志；**版本号由用户判断，AI 不得擅自递增**。

### 4.6 与既有规则的**直接冲突**（必须同批裁决）

| 规范 | 原文 | 冲突 | 处理 |
|---|---|---|---|
| `rules/expansion-playbook.md:22` | 「新系统进度必须锚定游戏时间（年/月/旬），**禁止以现实时间为准**（游戏流速 6 现实秒 = 1 游戏月，差异巨大）」 | 本方案正是"以现实时间为准" | **改写该条**为：进度锚定**唯一权威时间轴**（现实时间积分），游戏日历为投影；保留"禁止另起结算循环/新线程 tick"与"禁止两套时间真相源"的实质约束 |
| `rules/expansion-playbook.md:63-67` + `docs/architecture.md:330-334` | 「离线收益属未来扩展，**不改基线**」 | 本方案落地离线收益 | 从"预留"改为"已定稿"，同步 `rules/economy-design.md` §4 |
| `docs/knowledge-base.md:722` | 玉符墙钟豁免论证引用"L22 禁止以现实时间为准" | 该引用条款将被改写 | 更新引用文本（玉符本身不动） |
| `android/core/engine/AGENTS.md:19-23` | 「新逻辑必须落既有四层（L0–L4），禁止另起结算循环」 | 四层语义被重构 | **四层结构保留**（L0 时间推进 / L1 连续积分 / L2 惰性差分 / L3+L4 事件派发），仅层内语义改写；`AGENTS.md` 描述同步更新 |
| 根 `AGENTS.md` §3「存档为纯手动 — 禁止重新实现自动保存，禁止 `autoSave*` 命名」 | 与本项目**既有** `AutoSaveTrigger.MONTHLY`（月变触发自动存档）冲突 | 该例外**早已存在**（非本方案引入） | **已裁决并实施**（§2.6）：删除月变触发，改为现实墙钟每 10 秒一存；`rules/` 与 `docs/architecture.md` 需同批登记该例外（存档仍非"自动保存体系"，而是**现实时间节流的三前置门控触发**） |

> 裁决建议：改写的都是**约束的表述**，不是删除约束。原约束要防的三件事
> （① 两套时间真相源 ② 结算循环/线程走私 ③ 玩法节奏与流速脱钩）在新方案里都仍然被守住：
> ① 由 INV-1 单轴守住；② 由"禁止另起循环"原句保留守住；③ 由 §2.2 换算常量栈守住。

---

## 五、测试方案

### 5.1 双守护矩阵（`android/app/src/main/cpp/gamecore/AGENTS.md` 硬要求：C++ + Kotlin 两侧齐备）

| 类别 | 现有资产（改造后需重建/重定） | 新增 |
|---|---|---|
| C++ 黄金序列 GTest | `phase_settlement`/`month_settlement`/`year_settlement` 相关用例、`engine_loop_test.cpp`、`ColumnExportEquivalenceTest` | `ContinuousAccrualEquivalenceTest`（积分轨 vs 旧离散轨同口径对照）、`GameTimeUnitsTest` |
| JUnit 跨语言对拍 | `DiffPhaseSettlementTest`、`DiffMonthSettlementTest`、`DiffYearSettlementTest`、`DiffAuthoritativeTickTest`、`DiffTimeTest`、`DiffEngineLoopTest` | `DiffRealtimeAccrualTest`（逐 tick 对拍）、`DiffLegacySaveNormalizationTest` |
| 确定性守卫 | `RngSourceGuardTest`、`DiffAiRngSeedingTest` | `RealtimeRngSequenceGuardTest`（判定次数与 RNG 消耗序锁定） |
| 存档守卫 | `RoomMigrationTest` 全链 + 迁移注册守卫 | 新版本 Migration 集成测试 + `SaveValidator` 新规则测试 |
| 双端常量一致性 | `GameTimeClockPhaseCapParityTest`、`PhaseCapParityTest` | `GameTimeUnitsParityTest` |

### 5.2 对拍口径（"逐位等价"的重新定义）

改造后**不能再要求"与旧实现逐位相同"**（那正是被废弃的语义）。新对拍口径必须分两类：

1. **判定轨等价**（必须逐位）：离散判定的**次数与 RNG 消耗序**与旧实现逐位相同。
   证法：以"事件索引"为轴对照 —— 第 i 次旬判定、第 j 次月判定在两臂中对应同一状态。

   🔴 **本方案对判定轨的硬约束（来自 L1 调查的最致命发现）**：突破判定的 RNG 契约是
   **"每次尝试恰好 1 次 `nextDouble()`"**（`phase_settlement.h:926,962`），且**一次判定窗口内可连续多次尝试**
   （`:943` 的 `while (shouldContinue && realm > 0)`，无迭代上限）。因此：
   - **突破判定窗口必须保持"每旬一次"**（按游戏时间每 2 秒的整数倍触发），
     **不得**因积分轨让修为更快填满就提前判定 —— 否则尝试次数随帧率/数值漂移，RNG 序列分叉。
   - 连续化只作用于**积分量**（修为数值本身连续增长、UI 可实时显示），
     判定仍在该旬窗口内以完整修为触发。
   - 同理适用于其他消费 RNG 的判定（丹药批量 roll、偷盗/赠送、`EXPLORATION`/`SYSTEM`/`BATTLE`/`SECRET_REALM` 抽取）。
2. **积分轨等价**（必须可解析）：积分量满足
   `new(N旬) ≈ old(N旬)`，误差上界由浮点结合律给定（目标 ≤ 1e-9 相对误差），
   且**任意时刻**满足"积分量 = 速率 × 已过时间"这一闭式关系（可用闭式断言而非逐位断言）。

### 5.3 墙钟成本核算（`rules/design-plan-review.md` 第四节）

| 项 | 估计 |
|---|---|
| 新增对拍用例单次墙钟 | 目标 ≤ 3s（以 12 旬/20 旬真实结算为场景，沿用现有 `Diff*` 规模） |
| 全量回归预算 | 现状 JUnit 六模块 7000+ 用例约 10 分钟量级；本次改造会让 `Diff*` 结算类用例**全部需要重定基准**，预计首轮回归 +30~50% 墙钟（基准重建期），稳态回落 |
| 迁移测试 | 新增 1 条全链（v_current → v_next）+ 1 条近三版升级，单条 ≤ 10s |

### 5.4 对抗性审查要点（必做）

1. **帧率无关性**：同一段现实时间，在 30fps / 60fps / 120fps / 卡顿（单帧 5 步上限）下，
   积分量与判定次数必须一致（新增 4 档帧率夹具对拍）。
2. **档位无关性**：1x / 2x / 暂停-恢复 / 保存阻塞（`isSaving` 跳过 tick）下同上。
3. **离线边界**：离线 0s / 1s / 11h59m / 12h / 13h / 24h / 负数（时钟回拨）逐档断言。
4. **旧档**：当前发布版本存档、上一版本存档、手工构造的"半旧半新"存档（毫秒字段有值但日历字段缺失）。
5. **并发**：离线注入（跨线程）与引擎 tick 的竞争；`stateStore.update` 事务原子性。
6. **双端**：C++ 与 Kotlin 在同一输入序列下积分量差分（不是各自内部自洽）。

---

## 六、风险评估与兜底

| 风险 | 等级 | 影响 | 兜底 |
|---|---|---|---|
| 存档损坏（Entity 改动 + Migration 缺失） | 🔴 极高 | 玩家存档全空，不可逆 | 严格按 `rules/database-migration.md` 全流程；迁移前自动备份 + 启动验证恢复（既有三层防御 `GameDatabase.create()`）；`RoomMigrationTest` 全链守卫 |
| 经济增速失衡（连续积分让收益变"平滑但更多/更少"） | 🔴 高 | 通胀或进度崩塌 | `rules/economy-design.md` 源汇闭环审计；离线速率 50% + 封顶；上线前用既有基准脚本对拍同一现实时长的产出总量 |
| RNG 序列漂移（判定次数改变） | 🔴 高 | 对拍全红、玩法结果不可复现、云存档/本地漂移 | INV-3 事件计数；`RealtimeRngSequenceGuardTest`；灰度双臂对拍 |
| 性能回退（每 tick 全量积分 5000 弟子） | 🟡 中 | 帧率下降 / 掉帧 | 现状每旬 5000 弟子结算已实测 ~1.8ms（`docs/cpp-engine.md` R1 收官 bench）；连续轨是"同一公式更小步长"，但需削减"每 tick 全量物化"——沿用 R1.2 去物化 + R2.3 列级导出手段；新增 bench 门禁（每 tick 积分 < 1ms@5000） |
| UI 读数与结算不同步（镜像滞后） | 🟡 中 | 玩家看到"卡住"或"跳变" | 沿 R2.3 投影块机制（`GameViewStore`）；连续轨下读数天然平滑 |
| 规范与代码长期冲突（未改 rules） | 🟡 中 | 后续开发按旧规范实现，回归离散 | 本方案 §3.6 强制同批更新 + `node scripts/check-agent-instructions.mjs` 门禁 |
| iOS 平台时间端口缺失 | 🟢 低（当前无 iOS 代码） | 未来 iOS 迁移返工 | 端口化（`MonotonicClock`）已在 C++ 侧就绪；Kotlin 侧保持 `TimeSource` 注入（`GameTimeClock.kt:17-19`） |
| 回滚复杂度 | 🟡 中 | 双臂切换积分基准错位 | 灰度旗标 + 切换点强制重算 `lastSettleGameMs`；双存储对拍 |
| **月变自动存档触发源消失**（`AutoSaveTrigger.MONTHLY`） | 🟡 中 | 玩家进度丢失窗口变大 | 替换为基于现实时间的节流触发（如每 N 现实分钟 + 关键事件）+ 与"存档纯手动"规范裁决（§4.6） |
| **绝对月口径不统一**（`year*12+month` vs `(year-1)*12+month`） | 🔴 高 | 毫秒化后取整差异被放大 → 槽位/关卡到期错位 | 改造前置任务：先统一口径（§9.1 第 14 条），再毫秒化 |
| **幂等/去重隐式依赖"每月一次"** | 🔴 高 | 重复结算（多扣丹药/多扣材料/重复启动生产） | 逐项显式引入"上次结算时间戳"（§3.4.2 结构性问题 2）；新增幂等键守卫测试 |
| **常量外置不完整导致双端漂移** | 🟡 中 | 运营改 JSON 后 C++ 与 Kotlin 结算分歧 | 新增速率常量一律双端同源 + 补 C++ 侧一致性校验（§9.1 第 15 条） |

---

## 七、未来场景推演（≥6 个月档，`rules/design-plan-review.md` 第一节）

| 维度 | 本方案下的结论 |
|---|---|
| **规模增长**（弟子 ×10 → 5 万；生产槽 ×10） | 积分轨成本 = O(活跃实体数) × 每 tick 常数。实体 ×10 时每 tick 积分成本线性增长（现状每旬成本也是线性，但频次从 1/2s 提升到 1/0.1s = ×20）。**必须靠"只积分活跃实体 + 列级脏导出"控制**，否则 5000 弟子即可触顶。→ 列入技术债 D1，触发条件 = 真机 5000 弟子积分段 > 1ms。 |
| **生命周期**（构建/重启/重建/清缓存） | 权威时间轴 = 存档字段，重启后从存档续算；进程内 `accumulatedGameMs` 清零重建（`settlement.h:120-124` 既有 `reset()` 语义）。无"构建期时间戳进 hash"类陷阱。 |
| **平台扩张（iOS）** | C++ 连续轨零平台依赖，直接复用；仅需 iOS 侧 `MonotonicClock` 实现（`engine_loop.h:77` 已端口化）。**iOS 无返工**。 |
| **运营演进**（6 个月内调数值/加活动） | 数值已外置到 `android/app/src/main/assets/data/game-data.json`（R6.2，改数值不重编译）；新增玩法只需给出"每游戏秒"速率，不需判断该挂哪一层边界。**运营调整不发版**这一点比现状更好。 |
| **兼容回退** | 灰度旗标可单版本回滚；存档双向可读（旧臂忽略新字段、新臂从新字段续算）。**不需要发版回滚**（旗标关闭即可）。 |

---

## 八、技术债与偿还计划（`rules/design-plan-review.md` 第二节）

| 编号 | 债项 | 产生原因（为何现在不全做） | 偿还时机（明确触发条件） |
|---|---|---|---|
| D1 | 积分轨每 tick 全量遍历活跃实体 | 先去物化/列级导出的既有能力需按新频次重新标定（R1.2/R2.3 是为"每旬"设计的） | 真机 bench 积分段 > 1ms @5000 弟子，或掉帧率 > 1% |
| D2 | 旧离散字段（`completionMonth`/`last*Month`）保留孪生 | 兼容旧包回滚与旧档，不做破坏性迁移 | 灰度旗标移除（下一个大版本）后一个版本周期 |
| D3 | T2 延迟组（`YearlyOpsQueue`）暂保留入队机制 | 其"自愈语义（差值判据）"在新调度器下需重新论证 | 新调度器上线并稳定一个版本后，评估退役 |
| D4 | 离线速率的数值平衡（50%/12h/24h）为首版经验值 | 需真机数据校准（回流率/产出曲线） | 上线 2 周后按实际 D1/D7 与产出分布复盘调优 |
| D5 | 双端常量栈靠守卫测试同步（非单一生成源） | 沿既有 `maxPhasesPerTick` 先例，未引入 codegen | 出现第 2 次双端常量漂移事故时改为 codegen 单一源 |
| D6 | 自动存档**无脏判定**：节拍到点即落全量快照，未结算状态变化（纯 UI 事务、暂停期间零变化）也会写盘 | 引入脏标记链路需新增一致性面（"什么算脏"要与事件流/镜像世代对齐），本次先取简单形态 | 真机观测到自动存档 I/O 占用电量/卡顿超预算（如低端机单次 > 30ms 且占比 > 5%），或玩家反馈"频繁保存掉帧"时 |

> 方案完成后，D1/D2/D3 同步登记到 `docs/architecture.md` 待办登记表（D 系列）。

---

## 九、盲区自查与完善建议（`rules/design-plan-review.md` 第七节）

| 维度 | 自查结论 |
|---|---|
| **需求理解** | 存在第二种合理解读："把游戏日历本身换成现实时间单位（1 游戏天 = 1 现实天）"。本次按用户选定 A 案（消除边界但保留日历为投影）实施。**若真实意图是"现实日历同步"（如游戏内 1 年 = 现实 1 年），架构完全不同**——需按 §2.2 常量栈整体重定标，其余不动。已在 §1.4 列为阻塞决策项。 |
| **边界与极端** | 已定义：离线 0s/上限/超上限；时钟回拨（单调时钟免疫）；暂停/保存/读档期间的死区（`consumeDeadTime` 语义保留，不产生收益）；负数 Δt（`coerceAtLeast(0)`）。**未完全定义**：跨"存档回滚/云存档覆盖"后时间轴回退时的积分重算——建议：读档一律以存档内 `elapsedGameMs` 为权威，不追补到当前墙钟（否则读旧档立刻暴增收益）。 |
| **系统耦合** | 已枚举：四层结算、镜像通道（R2.x）、年变分帧、RNG 分区、存档双路径、热控降级（L2 热控延迟语义在新模型下的**意义变化**——建议：热控降级从"延迟结算"改为"降低积分精度（拉长 tick）"，避免掩盖收益）、看门狗判据（`totalPhases + accumulatedGameMs` 三元组需替换为 `elapsedGameMs`）。 |
| **假设有效性** | 关键假设 3 条：① 现实时间与游戏时间线性（速度档位有限）；② 判定次数可为整数差（所有判定周期都能对齐游戏毫秒）；③ 玩家可接受"离线 12h 上限"。①②  由 `GameTimeUnitsParityTest` 与事件计数守卫验证；③ 需产品确认（§1.4）。 |
| **数据与兼容** | 旧档一次性归一化（§4.1）+ 幂等；"半旧半新"存档（毫秒有值/日历缺失）以毫秒为权威；回滚路径见 §4.4。**回滚后再次前进**的积分基准重算点已显式规定。 |
| **非功能属性** | 性能：见风险表（bench 门禁）；内存：`elapsedGameMs` 为标量，无膨胀；功耗：积分频次 ×20 需评估（建议 tick 内仅做差分累加，重活仍按"变化时 checkpoint"）；安全：客户端本地时间轴可被手改（沿用现状风险，`SaveValidator` 加一致性钳制）；隐私合规：**不适用**（不新增 SDK/权限/网络/数据收集）。 |
| **生命周期** | 见第七节：规模/重启/平台/运营/回退五档均已回答。 |
| **流程盲区** | ① 监控盲区：现无"积分轨 vs 理论值"偏差监控 → 建议新增 1 个不变量断言（Dev 构建）与 1 项遥测（积分段耗时）；② 测试盲区：`Diff*` 基准需整体重建，首轮"全绿"可能是"新基准自洽"而非"语义正确" → 建议保留一版旧基准供对照；③ 发布盲区：灰度旗标默认值须为 false（安全默认）。 |

### 9.1 本次调查发现的预存缺陷（17 条，属"途中发现"，须显式报告）

> 规则要求（`AGENTS.md` §1 第 12 条）：任务完成后必须报告中途发现的预存问题。
> 下列缺陷**均非本方案引入**，是本方案实施时顺路可闭合的既有问题。分两类处置：
> **A 类**＝必须在本方案同批修复（否则会被连续化掩盖）；**B 类**＝登记待办（不阻塞本方案）。

| # | 缺陷 | 类型 | 位置 | 处置 |
|---|---|---|---|---|
| 1 | **`reflectionRelease`（思过到期释放）在 AUTHORITATIVE 生产路径不可达**：Kotlin 编排在 native 就绪时不执行，而 C++ `runYearSettlement` 无该实现 → **思过弟子永不自动释放** | 🔴 功能缺陷 | `GameEngineCorePausOps4.kt:95`；`year_settlement.h:1190-1205`（无对应实现） | **A 类**：必须随 L4 改造补 C++ 实现 + 双端对拍 |
| 2 | **墙钟双源不一致**：宗门等级奖励 Kotlin 侧走 `CalibratedWallClock`，native 事务侧走未校正的 `System.currentTimeMillis()` | 🔴 功能缺陷 | `GameEngineSectLevelOps.kt:62,347` vs `GameEngineNativeOps.kt:54,135` → `jade_tx.h:183` | **A 类**：统一走 `WallClock` 注入 |
| 3 | **`lastYearSpiritStoneIncome` 无生产写入点**：唯一写点 `VassalService.recordYearlyIncome()` 全仓零调用，C++ 只读 → 附庸年贡长期取 0/旧值 | 🔴 功能缺陷 | `VassalService.kt:110-116`（零调用）；`year_settlement.h:269`（读） | **A 类**：补写入点或改用实时余额 |
| 4 | **`worldLevelLastRefreshMonth == 0` 无旧档补丁**（三处同型问题中唯一未打补丁者）→ 读档后可能整批重刷世界关卡 | 🟡 数据缺陷 | `WorldLevelManager.kt:51`（对比 `GameEngineLoadDataOps.kt:225-235` / `GameEngineLifecycleOps.kt:186-187`） | **A 类**：补读档锚定 |
| 5 | **`checkpointAllProduction` 写入异步且非事务** → "政策切换瞬间 + 月结并发"存在缓存/DAO 分叉 | 🟡 一致性缺陷 | `ProductionProcessorFindOps5.kt:144-153` | **A 类**：随 L2 改造并入单事务 |
| 6 | **`processAuthoritativeTick` 文件头与函数体编号自相矛盾**（头 4 段 vs 体 5 段） | 🟡 文档缺陷 | `GameEngineCoreAuthoritativeOps.kt:34-44` vs `:61-85` | **A 类**：随改造改正 |
| 7 | **`phase_settlement.h:34` 标题"六步结算"与实际 1–7 步不符** | 🟢 文档缺陷 | `phase_settlement.h:34` | **A 类**：顺手改正 |
| 8 | **`LazyEvaluationDispatcher.shouldSettle/shouldSettleWithThermal` 是死代码**（零调用、从未注入）；`CODE_WIKI.md:987` 描述的方法不存在 | 🟡 死代码 | `LazyEvaluationDispatcher.kt:30-58`；`CODE_WIKI.md:987` | **A 类**：随 L2 改造删除 + 文档同步 |
| 9 | **`completionPhase` 是"死字段但被全链搬运"**：只有死代码读它，C++ `settlement.h` 不读，却经 native tx 参数 + `json_codec` 双向 + Room 列 + Proto | 🟡 死字段负担 | `ProductionSlot.kt:90-92` 等 | **A 类**：随 v59→v60 Migration 判定退役或改 ms |
| 10 | **`cultivationCompletionPhase` 是死值 1**（`phase_settlement.h:922` 硬编码）却由 ProtoNumber(95)+Room 列+镜像三重承载 | 🟡 死值负担 | `phase_settlement.h:922`；`Disciple.kt:103`；`GameDatabaseMigrationsV39.kt:100` | **A 类**：随 Migration 清理 |
| 11 | **`RealmConfig.maxAge` 死代码残留**（`age/lifespan` 已随 v54→v55 整批删除，配置项无人消费） | 🟢 死代码 | `GameConfig.kt:329-365,902`；`GameConfigData.kt:48` | **B 类**：登记待办，可随手清理 |
| 12 | **`START_STICKY` 后台重建可能无条件重启循环**（与"后台纯暂停"口径冲突，"离线收益"上限设计依赖该口径） | 🔴 待验证 | `GameForegroundService.kt:144,113-121`；`docs/longrun-stability-audit-report.md:298` | **A 类**：**本方案上线前必须真机验证**（决定离线计时起点是否可靠） |
| 13 | **月变自动存档触发源**（原 `AutoSaveTrigger.MONTHLY`） | ✅ **已裁决并实施**（§2.6） | 原：`GameEngineCorePausOps4.kt:157-162` → `notifyMonthSettled()` → `SaveLoadViewModel.kt:257` | 用户 2026-09-27 拍板：**删除月变触发**，改为现实墙钟每 10 秒一存；`onStop` 保留。改动见 §2.6 |
| 14 | **绝对月口径两套不一致**：`year*12+month` vs `(year-1)*12+month` 并存 | 🔴 口径缺陷 | `month_settlement.h:673,1213,1285` / `CultivationSettlement.kt:394` / `DiscipleService.kt:159`（前口径）vs `SlotStateMachine.kt:48`（后口径）；`production.h:668` / `ProductionProcessorFindOps5.kt:150`（前口径） | **A 类**：**毫秒化前必须先统一**，否则取整差异被放大 |
| 15 | **常量外置不完整 + 双端漂移风险**：`game_config.json` 只外置 `time.*`/`spiritMine*`/6 项政策费/`ai.minDisciplesForAttack`/`diplomacy.*`；`kSpiritMineBoostMultiplier=1.2`、`kDeaconMoralityBonusRate=0.01`、`kElderSkillBaseline=80`、`kAiSkipCooldownMonths=12`、`kLevelRefreshIntervalMonths=3`、`kOpenYears=5`、`kMonthlyDecayPhases=3` 等全部为 C++ 硬编码副本；`GameConfigConsistencyTest` **只校验 Kotlin 两源、不校验 C++** | 🟡 配置缺陷 | `android/app/src/main/assets/config/game_config.json`；`GameConfigNativeBridge.kt:47-51`（只注入 warehouse 容量）；`GameConfigConsistencyTest.kt:20-40` | **A 类**：本方案引入新速率常量时必须走**双端同源**，建议同时补 C++ 侧校验 |
| 16 | **三项下线系统的死常量**：`CURFEW_DESERTION_REDUCTION`/`CURFEW_EVENT_REDUCTION`/`REWARD_PUNISH_EFFECT`/`ENHANCED_SECURITY_EFFECT` 在生产源码零消费点（仅测试引用） | 🟢 死代码 | `GameConfig.kt:807,816-818`；消费点为零 | **B 类**：登记待办，可随手清理 |
| 17 | **月变相关文档系统性漂移**：文件头"七步"（实为八步）；`:51,713-718` "十四子事件" vs `GameEngineCoreMonthOps.kt:93` "十六" vs `MonthSettlementResidualExecutor.kt:18-24` "已下沉 12 件"（实为 15 项）；`:714` 仍列已下线的 `autoRecruit`；`game_core.cpp:170-173` 称"七系统扇出"（实为 4 个系统，Mail 已移除） | 🟡 文档缺陷 | `month_settlement.h:41,51,714,713-718`；`MonthSettlementExecutor.kt:15,38`；`game_core.cpp:170-173` | **A 类**：随改造统一口径（本方案 §3.4.2 已按代码校正） |

> 另有 3 条**过期记录**需回改（非代码缺陷）：`docs/longrun-stability-audit-report.md:253-254`（P2-16 已修）、
> `GameEngineCoreAuthoritativeOps.kt:45-46`（KDoc 称 Kotlin 独占时钟，实际已迁 C++）、
> 年变条目数"11+11"（实为 8+8，见 §3.4.3）。
### 盲区完善建议（已回写正文的项）

1. 看门狗判据替换 → 回写 §9 系统耦合 + §2.4 关键类。
2. 读档不追补墙钟 → 回写 §4.1 归一化策略。
3. 热控降级语义改造 → 回写 §9 系统耦合（实施时落 `rules` 说明）。
4. 灰度旗标安全默认 false → 回写 §4.4。

### 未采纳（有价值但本次不做，已入债表）

- **时间轴 codegen 单一源**（D5）：收益小，先靠守卫测试。
- **完全删除旧的年月旬字段**：与"旧包回滚"和"旧档兼容"冲突，入 D2。

---

## 十、实施批次编排（每批独立完备、可验收）

> 边界说明：本方案**本身是完整最终态**（`rules/design-plan-review.md` 第零节）。下表是按工程可控性编排的
> **执行批次**——每批自带验收标准，任一批单独拿出都是"完整的一批"，不是"被拆分的半个方案"。

| 批 | 内容 | 关键文件 | 验收标准 |
|---|---|---|---|
| **B1 时间语义基座** | 常量栈（§2.2）+ 权威时间轴 `elapsedGameMs` + 日历投影纯函数 + 双端同源守卫 | `GameConfig.kt`；新增 `gamecore/system/time_units.h`；`time_system.h` | 双端常量与换算公式逐位一致；`GameTimeUnitsParityTest` 绿；旧行为零变化（无人消费新常量） |
| **B2 时间推进层** | `PhaseClock` 未截断推进 + `LoopFramePlan` 增补字段 + 判定次数计算（INV-3） | `engine_loop.h`、`settlement.h`、`GameEngineCoreAuthoritativeOps.kt` | 帧率/档位无关性对拍绿；`phaseCap` 对判定轨仍生效 |
| **B3 存储底座** | v59→v60 Migration + ProtoBuf 字段 + 读档归一化 + SaveValidator 规则 | `GameDatabaseMigrationsV60.kt`、`GameData.kt`、`json_codec.cpp`、`SaveValidationRuleDefaults.kt` | `RoomMigrationV59To60Test` 绿；旧档升级后**逐字段等价**；`DiffLegacySaveNormalizationTest` 绿 |
| **B4 L1 积分轨** | 步骤 1/2/3/4 连续化 + 取整模式改造（INV-6）；步骤 0/5/6/7 判定轨保持 | `phase_settlement.h`、`disciple_stats.h`、`nurture_constants.h` | 积分轨等价（§5.2 第 2 类）+ 判定轨逐位（第 1 类）；`RealtimeRngSequenceGuardTest` 绿 |
| **B5 L2 差分轨** | 槽位/灵田/建造/任务/秘境时间模型改游戏毫秒 | `ProductionSlot.kt`、`LazyEvaluationDispatcher.kt`、`ProductionTransactionManager.kt` | 剩余时间精度达毫秒；旧槽位读档语义等价 |
| **B6 L3+L4 边界层拆分** | 积分型析出至 B4 轨；判定/叙事保留；`YearlyOpsQueue` 骨架复用 | `month_settlement.h`、`year_settlement.h` | 月/年判定项次数与 RNG 消耗序不变；年报/事件流不变 |
| **B7 离线语义** | 上限/速率/注入路径 + UI 回归提示（口径已定：12h 全额 + 50% 至 24h 硬顶，§1.4） | `GameTimeClock.kt`、`GameEngineCore*Ops`、UI 层 | 离线边界 7 档断言绿；经济总量对拍在容差内 |
| **B8 UI 与遥测** | 旬进度→时间进度、文案、`GameViewStore` 块、bench 门禁 | `ProductionTheme.kt`、`SectInfoCard.kt`、`GameData.kt:831` | 面板读数不再出现"差一点不结算"；积分段 < 1ms@5000 |
| **B9 测试基准重建 + 遗留清理** | 按 §3.4 清单重定基准；清理死值/死代码（`cultivationCompletionPhase`、`RealmConfig.maxAge`）；修复 `reflectionRelease` 缺口与两处时基 | 全仓测试 | 六模块全绿 + GTest 全绿 + detekt/lint 绿；`node scripts/check-agent-instructions.mjs` 绿 |
| **B10 文档与规范** | §3.6 全部条目 | `rules/`、`docs/`、双 CHANGELOG | 规范与代码零冲突；门禁绿 |

> **已完成子项**：**自动存档现实时间化**（§2.6）——删月变触发 + 现实墙钟每 10 秒一存，
> 独立于 B1–B10 先行落地（不依赖结算改造，且是"现实时间基"在存档面的第一处兑现）。

> **交付形态**：本方案不产生"半成品代码"——B1–B10 全部完成才是本方案完成。中间批次通过灰度旗标
> `NativeEngineFlag.realtimeAccrual`（默认 false）保证任何时刻可回退到旧行为。

---

## 十一、方案完成度自检（`rules/design-plan-review.md` 第八节）

- [x] 未来场景推演小节已写（≥6 个月档）
- [x] 技术债与偿还计划小节已写（5 条，均有明确触发条件）
- [x] 盲区自查与完善建议小节已写（方案末章，逐维度三要素，实质项已回写正文）
- [x] 每个新抽象有当前生产消费者（`elapsedGameMs`/积分轨/判定轨均有现存结算项消费）
- [x] 新测试墙钟成本已核算（§5.3）
- [x] rules/ 交叉核对完成（`expansion-playbook` / `economy-design` / `database-migration` / `cpp-priority` / `design-plan-review` / `pr-review-checklist` / `version-release`）
- [x] 决策分级已声明（架构级重构）
- [x] 影响范围清单含经济/iOS 标签项 —— **经济**：源汇闭环见 §6 风险表 + `rules/economy-design.md`；
  **iOS**：见 §7 平台扩张行 + §3.6 `docs/platform-abilities.md`
- [x] **影响范围清单逐文件项已完成**（§3.1 引擎 C++ / §3.2 Kotlin / §3.3 存储 / §3.4 结算项穷尽清单 / §3.5 测试 / §3.6 文档规范）
- [x] 实施批次编排已给出（§10，B1–B10，每批带验收标准）
- [x] 预存缺陷报告已完成（§9.1，17 条，含 A/B 类处置与 3 条过期记录回改）
- [x] 调查方法、覆盖度与未确认项已声明（附录 A）；不做的事已声明（附录 B）

> **交付状态**：方案文档完成，**无待补章节**。代码未改动（本任务只要求调查 + 实施文档）。
> 下一步：① 用户确认 §1.4 两项产品口径；② 用户批准后按 §10 批次实施。

---

## 附录 A：调查方法与覆盖度声明

本次调查为**只读代码审计**（未修改任何生产代码），分四个域并行盘点后合并入 §3.4：

| 域 | 覆盖 | 落到本方案 |
|---|---|---|
| **L1 每旬** | `phase_settlement.h` 全 8 步骤（步 0 自动装备/学习 → 步 7 突破）、每旬量清单与换算系数、18 条隐含假设、旬绑定存档字段与判定点、GTest 27+5 用例与 JUnit 对拍清单 | §3.4.1、§5.1 |
| **L3 月变** | 七步编排 + 十六子事件（`month_settlement.h:972-1045`）、RNG 消耗核对表、以"每月"为单位的常量族与阈值、`kMonthlyDecayPhases`、AI"3 旬=1 月"折算 | §3.4.2/§3.4.5、§2.3 |
| **L4 年变** | T1/T2 逐项（实测 **8+8**，修正文档"11+11"漂移）、C++/Kotlin 覆盖差（发现 2 处缺口）、`YearlyOpsQueue` 分帧骨架、以"每年"为单位的判据、年龄不变量实测（**已不存在**）、年变绑定存档字段 | §3.4.3、§9.1 |
| **L2 惰性 + 时间源** | 23 项时间戳/过期判定清单、槽位时间模型字段与 `checkpointAllProduction` 语义、时间源→结算完整链路、离线/后台语义源码证据（D1–D10）、平台时间 API 使用点与 iOS 缺口、换模型受影响位置 **33 处** | §3.4.4、§4.1 |

**覆盖度声明（诚实边界）**：

- ✅ **已穷尽**：以旬/月/年/绝对月为单位的结算量与判定点、RNG 消耗点、绑定存档字段、必失败测试、迁移受影响位置。
- ✅ **已核实并纠正 5 处文档口径漂移**：
  ① 年变条目数 11+11 → **8+8**（以代码与测试为准，`CultivationEventMonthlyOps.kt:97,119`）；
  ② 月变"七步" → **八步**（`month_settlement.h:41` vs `runMonthSettlement:1239-1375`）；
  ③ "十六子事件" → **编号上界为 16、实为 15 项实现**（缺 2/3/4；`MonthSettlementResidualExecutor.kt:18-24` 称"已下沉 12 件"亦不准）；
  ④ `LazyEvaluationDispatcher` 文档称存在 `isInFocusDomain()` → **该方法不存在**；
  ⑤ `longrun-stability-audit-report.md` P2-16 已修但文档未回改。
- ✅ **已实测消除一项重大顾虑**：**年龄不变量已不存在**（`age`/`lifespan` 于 v54→v55 整批删除），
  连续化最难的一块（整数年龄）不必处理（§3.4.3）。
- ✅ **已找到现成范式**：灵矿 `rate × (currentMonth − lastSettledMonth)` 是项目内**唯一已差分化的连续结算**，
  加上 `YearlyOpsQueue` 的 30ms/tick 预算分帧，二者可直接复用（§3.4.2 第 11 项、§3.4.3）。
- ⚠️ **未确认项（8 条，已在正文标注）**：
  ① `models.h:1139` `gameMonthIndex` 的用途与消费者；
  ② `autoBuy` 内部是否消费 RNG；
  ③ `GameViewMirrorCodec` 是否还有其它旬绑定派生字段（仅抽查了 `cultivationCompletionPhase` 与 `gamePhase`）；
  ④ `START_STICKY` 后台重建是否真的会推进时间（D8，需真机验证）；
  ⑤ `HpMpRecoveryService.applyMonthlyDurationDecay` 的 Kotlin 函数体未逐行核对（凭 C++ 等价移植推断）；
  ⑥ `game_config.json:95` `cultivationSubsidyCost=4000` 与 `GameConfig.kt:778` `CULTIVATION_SUBSIDY_PER_DISCIPLE=300` **同名不同义**的权威关系；
  ⑦ 秘境 AI 队伍"4 名"是否有命名常量（仅见行为断言）；
  ⑧ `ProductionSlot.completionPhase` 在 UI 层是否被读取（若被读则不能直接退役）。
- ℹ️ **未纳入范围**：玉符（已获墙钟豁免，本方案不动）、渲染/音频/网络等非结算域、iOS 侧实现（只登记缺口）。

---

## 附录 B：本方案不做的事（YAGNI 反向检查，`rules/design-plan-review.md` 第三节）

| 不做的事 | 原因 |
|---|---|
| 不把游戏日历（年/月/旬）从存档与 UI 删除 | UI 文案/叙事依赖它；删除破坏旧包回滚与旧档兼容。改为**派生投影**（INV-1） |
| 不引入新的结算循环或新线程 tick | `android/core/engine/AGENTS.md:21-23` 红线保留；本方案只在既有 tick 内改"结算什么"而不是"何时 tick" |
| 不为连续轨引入 ECS 新 System | 现有 `PhaseCoreBatchSystem`（`phase_settlement.h:1332-1346`）已承载每旬核心批次，改造是"换被调函数"而非"加系统" |
| 不做时间轴 codegen 单一生成源 | 收益小，先靠 `GameTimeUnitsParityTest` 守卫（入债表 D5） |
| 不动玉符 / 邮件 / 宗门等级奖励的墙钟语义 | 它们已是"现实时间"且语义正确（缺的只是**统一注入端口**，见 §9.1 第 2 条） |
| 不删除旧字段（`completionMonth` / `last*Month` 等） | 兼容旧档与旧包回滚（入债表 D2，触发条件明确） |
| 不复活已退役字段（`lastRecruitYear` 等 6 项，v55/v56 删除） | 字段号已 `reserved`，复活会破坏旧档字段编号语义 |
| 不为"背叛/偷盗/执法"等已下线系统补实现 | 相应常量（`CURFEW_DESERTION_REDUCTION` 等）已是死代码，随 B9 清理（§9.1 第 16 条） |
