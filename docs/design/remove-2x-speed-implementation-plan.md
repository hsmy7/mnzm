# 删除「二倍速」实施文档

> 版本：v1.0（2026-09-24）
> 范围：全仓（Kotlin 引擎 + UI + C++ 引擎 + JNI + 看门狗 + 双端对拍 + 文档）
> 依据规范：`rules/design-plan-review.md`（原则 1–6 + 第一~八节自检清单）、`rules/database-migration.md`、`rules/cpp-priority.md`、`rules/code-comment.md`、`docs/AGENTS.md`

---

## 〇、决策分级声明（`rules/design-plan-review.md` 第六节）

| 级别 | 判定 | 依据 |
|---|---|---|
| **中间地带** | 按架构级出完整方案，同时标注"最小切入路径"备选 | "速度"这一维度在 **6 个子系统**同构复现（Kotlin 时钟 / Kotlin 消费侧 cap / C++ PhaseClock / C++ SettlementEngine / 看门狗判据 / 崩溃归因），属"同模式问题 ≥3 处"；但**不改架构分层、不改镜像协议、不改存档格式、不改 Room schema**，故不构成架构级重构 |

**最小切入路径（备选，本方案不推荐）**：只删 `SettingsTab` 的 1x/2x 两键 + `SaveLoadViewModel.setTimeSpeed()`，保留 `GameTimeClock.speed` 与整套 C++ 速度状态机。

- 不推荐理由：① 速度状态机仍可被 `setSpeed(2)` 写入（`GameTimeClock.setSpeed` 仍是 `public`，看门狗、测试、未来任何调用点都能把 2x 重新点亮）→ **回归无门禁**；② 留下 21 处与新语义不符的死代码/死字段/错位注释，直接违反 §5 编码规范 0.2/0.3 与用户公约 9/15；③ 双端 `maxPhasesPerTick(speed)` 的"单一来源公式"失去唯一消费者却仍需双端同步维护，形成永久漂移风险。
- 结论：**采纳整维删除**（本方案正文即整维删除的完整实施文档）。

---

## 一、背景与目标

### 1.1 需求

删除游戏的「二倍速」（2x）机制。删除后游戏只以 **1x 单一时速**运行，暂停/继续能力**保持不变**。

### 1.2 成功标准

| 编号 | 标准 | 验证方式 |
|---|---|---|
| S1 | 游戏内不再出现任何「1倍速 / 2倍速」字样与切换入口 | 全仓 `grep` 归零（`倍速`、`timeSpeed`、`timeScale`、`SpeedToggleButton`） |
| S2 | 时间推进只由墙钟差值累加驱动，**无任何乘数** | `GameTimeClock` / `PhaseClock` 中 `speed` 符号面归零 |
| S3 | 1 旬 = 2000ms 墙钟、1 游戏月 = 6s、1 游戏年 = 72s（与删除前的 1x 逐位一致） | `GameTimeClockTest` + `engine_loop_test.cpp` 1x 用例全绿；`DiffTimeTest` / `DiffEngineLoopTest` 对拍绿 |
| S4 | 暂停/继续全链零行为变化 | `GameEngineCoreResumeTest`、`GameEngineCoreWatchdogTest`、`DiffWatchdogTest`（非 speed 用例）全绿 |
| S5 | 看门狗对「世界时间冻结」的检出能力不下降 | FakeRunDetected 由 `totalPhases` 冻结窗口（90s）承担，用例覆盖保留 |
| S6 | 无死字段/死参数/死分支残留 | detekt 无未用符号告警；`ConfigState`/`UnifiedGameState`/`GameTimeProgressMonitor` 中 `speed`/`gameSpeed` 归零 |
| S7 | 门禁全绿 | 见 §6 测试方案与 §8 实施步骤 |

### 1.3 产品影响（给用户看的结论）

- **玩家实际节奏不变**：二倍速**当前不可持久化**（见 §2.4），每次启动/读档都回到 1x。删除后唯一变化是"设置页少一个按钮"。
- **删除后 1 游戏年仍是 72 秒真实时间**（3 旬/月 × 6 秒 = 72 秒），挂机节奏、自动存档频率（`SaveTriggerFlag` 月月必存 = 每 6 秒）**均不变**。
- **顺带根治一项 A 级存档缺陷**：`docs/save-system-audit-2026-09-21.md:331` 登记的"2× 玩家每次回到 1×"——删除后该缺陷的载体（速度选择）不再存在，缺陷自然闭合（详见 §2.5 缺陷 D1）。

---

## 二、现状调查：二倍速机制的完整实现链

> 本节全部结论均为**逐行读实**（file:line 均来自当前工作树），非推测。

### 2.1 时间推进模型（速度如何生效）

`GameTimeClock`（`android/core/engine/src/main/java/com/xianxia/sect/core/engine/system/GameTimeClock.kt`）是**全项目唯一时间推进入口**，三层模型：

| 层 | 载体 | 说明 |
|---|---|---|
| 单调时钟 | `SystemClock.elapsedRealtime()`（`TimeSource`） | 不受 NTP/用户改时影响 |
| 游戏时间 | `accumulatedGameMsInternal += realDelta × speed`（`GameTimeClock.kt:153-155`） | **倍数施加点** |
| 旬推进 | `phases = accumulatedGameMs / msPerPhase`（`GameTimeClock.kt:157`） | `msPerPhase` 随速度变（`:55-61`：speed=1 → 2000ms，speed=2 → 1000ms） |

有效语义：**2x 把真实时间 ×2 后再按 2000ms/旬 换算**，等价于句间隔 1000ms——**1 真实秒 = 1 旬**，1 游戏月 = 3 秒。

`AUTHORITATIVE` 模式下**时间真相源在 C++**（`NativeEngineFlag.authoritative`），Kotlin `GameTimeClock` 降级为 UI 展示镜像（`GameTimeClock.kt:122-130` `mirrorFromNative`）。因此速度维度**必须双端同时删除**，否则两端时钟分叉。

### 2.2 UI 入口链（点击「2倍速」后发生什么）

```
SettingsTab.kt:845-852          listOf(1, 2).forEach { SpeedToggleButton(...) }
  → SettingsTab.kt:850          saveLoadViewModel.setTimeSpeed(speed)
  → SaveLoadViewModel.kt:593-604 setTimeSpeed(speed)
        ├ _timeScale.value = clamped              (UI 按钮高亮，:598)
        └ gameClock.setSpeed(clamped)             (:599)
  → GameTimeClock.kt:100-112    setSpeed()
        ├ 旧速度结算累积（切换零丢失）             (:103-105)
        ├ speed = newSpeed.coerceIn(0, 2)        (:107)
        ├ _speedFlow.value = speed               (:108)  → UI 的 timeSpeed 流
        └ onSpeedChanged?.invoke(speed)          (:111)
  → GameEngineCore.kt:178-182   钩子（init 注册）
  → GameCoreBridge.kt:291       external fun nativeLoopSetSpeed(speed)
  → GameCoreBridge.cpp:802-805  g_gameCore->loop().time().setSpeed(speed)
  → engine_loop.h:87-96         PhaseClock::setSpeed → speed_ (atomic)
  → engine_loop.h:101-107       msPerPhase(): speed==2 → 1000ms
  → engine_loop.h:140           accumulatedGameMs_ += rawDelta * s
```

同时 `timeSpeed` 流（`SaveLoadViewModel.kt:587-588` ← `gameClock.speedFlow`）回灌 `SettingsTab.kt:163` 用于按钮高亮与 `speedAlpha`。

### 2.3 「速度」在 6 个子系统的复现点（这就是不能只删 UI 的根因）

| # | 子系统 | 触点 |
|---|---|---|
| 1 | **Kotlin 时钟状态机** | `GameTimeClock` `speed`(48) / `_speedFlow`(51-52) / `msPerPhase`(55-61) / `setSpeed`(100-112) / `onSpeedChanged`(119-120) / `tick` 乘数(153-155) / `maxPhasesPerTick(speed)`(240) |
| 2 | **Kotlin 消费侧追补上限** | `GameEngineCoreAuthoritativeOps.kt:56` `phasesToAdvance.coerceAtMost(GameTimeClock.maxPhasesPerTick(gameClock.speed))` |
| 3 | **C++ PhaseClock** | `engine_loop.h` `speed_`(196) / `setSpeed`(87-96) / `speed()`(98) / `msPerPhase`(101-107) / `tick` 乘数(140) / `maxPhasesPerTick(s)`(144) / `resetForTest`(183) |
| 4 | **C++ SettlementEngine（第二份速度状态机）** | `settlement.h` `speed_`(153) / `setSpeed`(115) / `speed()`(116) / `advance` 乘数(73-74) / `maxPhasesPerTick(speed_)`(77) + 公式定义(31-40) |
| 5 | **看门狗判据** | `GameTimeProgressMonitor.kt` `speed`(36-37) / 首调即判(107-110) / 判据(159-162)；`watchdog.h` `ProgressSnapshot.speed`(38) / 判据(84)、(127-130)；`GameEngineCorePrepOps2.kt:415` 采样、`:352` 注释；`game_core.cpp:511` |
| 6 | **崩溃归因** | `GameEngineCorePrepOps2.kt:136` `"speed" to gameClock.speed` |

外加：**JNI 通道**（`GameCoreBridge.kt:291` / `GameCoreBridge.cpp:802-805` / 对拍桥 `DiffRngBridge.kt:163` / `GameCoreJni.cpp:503-506`）、**配置镜像死字段**（`GameStateStore.ConfigState.gameSpeed:92`、`UnifiedGameState.gameSpeed:39`、`SaveService.GameStateSnapshot.gameSpeed:26,66`）、**UI 死代理**（§2.5 D2）、**测试对拍面**（§2.6）、**文档面**（§2.7）。

### 2.4 持久化真相：倍速**当前不可持久化**

| 证据 | 结论 |
|---|---|
| `GameData`（`android/core/domain/src/main/java/com/xianxia/sect/core/model/GameData.kt`）**无 `gameSpeed` 字段** | ProtoBuf 面已无该字段 |
| Room 列 `gameSpeed` 在 **v20 迁移被删除**：`GameDatabaseMigrationsV11ToV20.kt:283-298`、重建函数 `:504-615` | 当前 DB 版本 **59**（`GameDatabase.kt:95`），列已消失 39 个版本 |
| `RoomMigrationTest.kt:613-626` 锁定"v20 后 `gameSpeed` 列不存在" | 有回归门禁 |
| `SaveService.kt:66` `gameSpeed = 1` 硬编码 | 存档快照里该值恒 1 |
| `GameViewModel.kt:472-481` 构造 `ConfigState` 时**不传 `gameSpeed`** | `ConfigState.gameSpeed` **恒为默认值 1** |

⇒ **玩家选 2x ⇒ 一旦重启/读档即回 1x**。倍速只是一个会话内的临时开关，从未真正成为"游戏设置"。

### 2.5 调查途中发现的既有缺陷（预存问题，均须在本方案一次清偿）

| 编号 | 缺陷 | 证据 | 与本方案的关系 |
|---|---|---|---|
| **D1** | **倍速不持久化**（A 级存档缺陷，已登记未修） | `docs/save-system-audit-2026-09-21.md:331`（"2× 玩家每次回到 1×"，等级 A）+ §2.4 全部证据 | 删除速度维度后**载体消失，缺陷闭合** |
| **D2** | **`gameSpeed` 降级为死字段后遗留的 UI 语义断裂**：弟子详情的三条进度条"暂停时冻结"门控**恒不生效** | `ConfigState.gameSpeed` 的**两个生产构造点均不传该字段** ⇒ 恒为默认值 1：`android/app/src/main/java/com/xianxia/sect/core/state/GameStateStoreImpl.kt:420-431`（真源）与 `GameViewModel.kt:472-481`（投影）⇒ `DiscipleDetailScreen.kt:422` `gameSpeed = 1` 硬编码 ⇒ `DetailBasicInfoSection.kt:60/85/105/259/295/311/327/381` ⇒ `DetailCultivationSection.kt:137/153/157` `paused = gameSpeed == 0` ⇒ **恒 false** ⇒ 暂停时进度条动画仍每 100ms 追赶 | **必须随本方案修复**（同一字段的删除余波） |
| **D3** | **`SettlementEngine::setSpeed` / `speed_` 生产零调用者**（第二份速度状态机的死代码） | 全仓 `grep`：仅 `time_system_test.cpp:139/154` 调用；生产路径无 `settlement().setSpeed` / `settlement_.setSpeed` | 随本方案删除 |
| **D4** | **`GameCore::advance` 生产零 JNI 调用者**（仅 `game_core_test.cpp:39/47` + `time_system_test.cpp` 的 `SettlementEngine::advance`） | `GameCoreBridge.cpp` 无 `advance` 端口；对拍桥走 `nativeCoreAdvancePhases`（另一函数） | **保留但 1x 化**（见 §4.3.3），死端口登记见 §10 |
| **D5** | **两处错位复制粘贴注释**：注释写"当前速度：0=暂停, 1=1x, 2=2x"，实际紧邻字段是**角色属性 `speed`（速度/身法）** | `FormulaService.kt:337`（下一行 `var speed = 0.0` 累积的是长老/亲传加成，`:328-350`）；`HeavenlyTrialBuildOps.kt:190`（下一行 `var speed = 0` 累积的是功法身法加成，`:186-209`） | 随本方案删除错位注释行 |
| **D6** | `phaseProgress` / `remainingPhaseMs` / `forceConsumeOnePhase` **生产零消费者**（KDoc 自称"UI 进度条用"，实际只有测试调用） | 全仓 `grep` 仅命中定义与测试 | **本方案不动**（与二倍速无因果关系），登记见 §10 |

### 2.6 测试对拍面（删除时必须同步，否则门禁红）

| 测试 | 位置 | 处置 |
|---|---|---|
| `GameTimeClockTest` | `core/engine/src/test/.../system/GameTimeClockTest.kt` L56-95、L142-210 | 删 2x/暂停/速度切换用例；1x 用例保留 |
| `GameTimeClockPhaseCapParityTest` | 同目录 | 重锚为「常量 = 3 且与速度无关」 |
| `DiffEngineLoopTest` | `core/engine/src/test/.../nativebridge/DiffEngineLoopTest.kt` L101-137、L149 | 删 `speed2x doubles accumulation both ends`；`setSpeed(2)/(0)` 改造；cap 断言保留 |
| `DiffRngBridge` | 同目录 L163 | 删 `nativeCoreLoopSetSpeed` |
| `SaveLoadViewModelLoadTest` | `feature/game/src/test/.../SaveLoadViewModelLoadTest.kt` L924-934 | 删 `setTimeSpeed - paused state does not auto resume` |
| `GameEngineCoreCrashReportTest` | `core/engine/src/test/.../GameEngineCoreCrashReportTest.kt` L158 | 期望键集删 `"speed"` |
| `DiffWatchdogTest` | `core/engine/src/test/.../nativebridge/DiffWatchdogTest.kt` L60/78、L149/151/281 | 删 `speed` 参数与 speed=0 用例 |
| `GameTimeProgressMonitorTest` | `core/engine/src/test/.../monitor/GameTimeProgressMonitorTest.kt` L22/35/38、L119-120、L323 | 同上 |
| `GameEngineCoreResumeTest` | `core/engine/src/test/.../GameEngineCoreResumeTest.kt` L194 | 删 `gameClock.setSpeed(0)`，用例改走 `isPaused` |
| `GameEngineCoreWatchdogTest` | `core/engine/src/test/.../GameEngineCoreWatchdogTest.kt` L83 | speed=0 自愈用例转「世界时间冻结」路径 |
| `engine_loop_test.cpp` | `app/src/main/cpp/gamecore/test/engine_loop_test.cpp` L58-132、L365、L386 | 删 2x/0 速用例；cap/累计语义用例保留并去速度参数 |
| `time_system_test.cpp` | `app/src/main/cpp/gamecore/test/time_system_test.cpp` L139、L154 | 删 `setSpeed(2)/(0)` 用例 |
| `game_core_test.cpp` | `app/src/main/cpp/gamecore/test/game_core_test.cpp` L39/47 | `advance` 语义改为 1x 断言（保持覆盖） |

### 2.7 文档面

| 文档 | 位置 | 处置 |
|---|---|---|
| `docs/architecture.md` | L67（"1x=2000ms/旬，2x=1000ms/旬"）、L176、L179（看门狗"speed=0 假运行"表述） | 改写为单一时速 |
| `docs/ui-read-surface.md` | L88（`ConfigState` 字段列含 `gameSpeed`） | 该行删 `gameSpeed`（契约文档，须与代码同改） |
| `docs/cpp-engine.md` | L307（PhaseClock 逐位移植描述含"速度切换旧速度结算"） | 改写 |
| `CHANGELOG.md` + `android/app/src/main/assets/changelog_entries.json` | 当前版本条目 | **双更新日志**（`rules/version-release.md`：漏一个即任务未完成） |
| `docs/save-system-audit-2026-09-21.md:331` | A 级缺陷 D1 行 | 标注"随二倍速删除闭合" |
| `docs/threading-contract.md` | 已核对：`grep` `loopSetSpeed|Speed|速度` **零命中** | 该通道未登记 ⇒ **无需改**（删除无需登记，只新增才须先登记） |
| `docs/knowledge-base.md` | 已核对：`grep` `速度|倍速|GameTimeClock|phaseProgress` **零命中** | 无需改 |

### 2.8 已确认**不涉及**的面（防止方案扩散）

| 面 | 结论 | 证据 |
|---|---|---|
| Room schema / Migration | **不涉及**。无需新增 `MIGRATION_N_M`、`@Database(version)` 不变（59） | `gameSpeed` 列 v20 已删；`GameData` 无该字段（§2.4） |
| ProtoBuf 字段号 | **不涉及**。无字段增删，`@ProtoNumber` 零变更 | 同上 |
| JNI 协议 `LoopFramePlan`（17 槽 LongArray） | **不涉及**。速度不在帧计划协议内 | `engine_loop.h:45-69` |
| 渲染双路径（Vulkan / Canvas） | **不涉及** | 时间推进与渲染无耦合 |
| 经济系统（货币/奖励） | **不涉及**。删除倍速不改变任何产出速率（1x 产出 = 删除前 1x 产出） | `rules/economy-design.md` 交叉核对见 §11 |
| 隐私合规 | **不涉及**。无 SDK/权限/网络/数据收集变更 | `rules/commercialization.md` §6 |
| 广告点位 / 埋点 | **不涉及**。无埋点事件与倍速耦合 | `rules/data-analytics.md` |
| Compose 稳定性配置 | **不涉及**。`android/stability_config.conf:7/26` 列的 `UnifiedGameState` 与 `GameStateStore.ConfigState` 本次**只删字段不删类**，`Int` 字段原为 stable，删字段不改变类的稳定性推断 | `android/stability_config.conf` |
| RNG | **不涉及**。倍速不改变每旬 RNG 抽取次数（旬数不变） | `docs/adr/rng-determinism-remediation.md` |

---

## 三、目标形态

### 3.1 删除后的时间模型（单一时速）

| speed | 旬间隔 | 月间隔 | 年间隔 |
|---|---|---|---|
| ~~0（暂停）~~ | — | — | — |
| **1（唯一）** | **2.0s 墙钟** | **6.0s** | **72.0s** |
| ~~2~~ | ~~1.0s~~ | ~~3.0s~~ | ~~36.0s~~ |

**规则**：

1. **时间推进 = 墙钟差值直接累加，无乘数**：`accumulatedGameMs += realDelta`。
2. **暂停语义唯一载体 = `GameStateStore.isPaused`**（`+ isLoading`）。`EngineLoop::iterate(pausedOrLoading, isSaving)` 的暂停分支（死区消费 + 累加清零）**保持不变**；不再存在 `speed == 0` 这第二套暂停表示。
3. **单 tick 追补上限 = 常量 3 旬**（`sum = 3`），不再随速度缩放。语义回到"防御 OEM 挂起/看门狗重启的爆炸式跳变"，与 1x 原子行为逐位一致。
4. **看门狗 `FakeRunDetected` 判据 = `totalPhases` 在 90s 窗口内未推进**（`GameTimeProgressMonitor.kt:163-176`、`watchdog.h:131-139`）。删除 `speed == 0` 的"首调即判"快路径——该路径在删除前已经是**不可达死分支**（见 §3.2）。

### 3.2 为什么删除 `speed == 0` 判定不构成能力下降

- **生产路径从无 `setSpeed(0)`**：全仓 `grep` 生产主源集（`src/main`）内 `setSpeed` 仅有 3 处——`GameEngineCore.kt:178`（钩子赋值）、`GameTimeClock.kt:100/107`（定义）、`GameEngineCoreHandOps3.kt:48`（自愈写 1）、`SaveLoadViewModel.kt:599`（`coerceIn(1, 2)` 封死 0）。UI 已显式封死 0（`SaveLoadViewModel.kt:594-597` 注释自陈原因）。
- 故 `speed == 0` 判定在删除前**已是不可达分支**；删除后 `FakeRunDetected` 完全由 `totalPhases` 窗口承担（该分支本就是"持续抛异常的世界冻结"这一真实病理的检出路径，`:156-158` 注释自陈设计意图）。
- **覆盖不降级**：`GameTimeProgressMonitorTest` / `DiffWatchdogTest` / `watchdog.h` 的 `totalPhases` 窗口用例全部保留（§6）。

---

## 四、技术方案

### 4.1 分层删除清单（D 系列）

> 变更类型：**删** = 删除符号/分支；**改** = 改写实现或注释；**增** = 新增。

#### 4.1.1 Kotlin 引擎层（`:core:engine`）

| 编号 | 文件 | 类型 | 变更说明 |
|---|---|---|---|
| K1 | `.../engine/system/GameTimeClock.kt` | 删/改 | 删 `speed`(48)、`_speedFlow`/`speedFlow`(51-52)、`setSpeed`(96-112)、`onSpeedChanged`(119-120)、`maxPhasesPerTick(speed)` 参数化(240) 与 `MAX_PHASES_PER_TICK` 的"随速度缩放"KDoc(226-239)。`msPerPhase`(:55-61) 改为常量 `MS_PER_PHASE`（删 0/2 分支）；`tick()`(:143-174) 去 `× speed`、`phaseCap` 直接用 `MAX_PHASES_PER_TICK`；`forceConsumeOnePhase`/`phaseProgress`/`remainingPhaseMs` 改用常量并**删 `speed == 0` 分支**；类 KDoc 速度映射表（:32-38）改为单行事实；**常量更名** `MS_PER_PHASE_1X` → `MS_PER_PHASE`（:224，理由：`_1X` 后缀在删除后主动暗示"存在 2X"，是回归诱因） |
| K1r | 常量更名的引用同步（**零行为**，纯改名，须一并完成否则编译红） | 改 | `android/core/engine/src/main/java/com/xianxia/sect/core/engine/EquipmentNurtureSystem.kt:14`、`android/core/engine/src/main/java/com/xianxia/sect/core/engine/ManualProficiencySystem.kt:98` —— 两处 `GameTimeClock.MS_PER_PHASE_1X` 改名为 `MS_PER_PHASE` |
| K2 | `.../engine/GameEngineCore.kt` | 删 | 删 `init { gameClock.onSpeedChanged = ... }` 整块（175-183） |
| K3 | `.../engine/GameEngineCoreAuthoritativeOps.kt` | 改 | `:56` `maxPhasesPerTick(gameClock.speed)` → `GameTimeClock.MAX_PHASES_PER_TICK`；`:54-55` 注释改写为"单 tick 追补上限（常量 3 旬）" |
| K4 | `.../engine/GameEngineCoreHandOps3.kt` | 删/改 | `handleWatchdogVerdict` 的 `FakeRunDetected` 分支删 `speed == 0` 特例（41-57），统一走 `performWatchdogRecovery()`；注释改写 |
| K5 | `.../engine/GameEngineCorePrepOps2.kt` | 删/改 | `:136` 崩溃上下文删 `"speed"` 键；`:352` 注释去掉 `speed=0`；`:415` 快照删 `speed = gameClock.speed` |
| K6 | `.../engine/monitor/GameTimeProgressMonitor.kt` | 删/改 | `GameTimeProgressSnapshot` 删 `speed`(36-37)；`evaluate` 删首调即判(106-110)；`classify` 删 `speed == 0`(159-162)；类 KDoc(`:8`、`:22`) 去掉 `speed=0` 表述 |
| K7 | `.../nativebridge/GameCoreBridge.kt` | 删 | 删 `external fun nativeLoopSetSpeed(speed: Int)`(286-291) 与其 KDoc |
| K8 | `.../engine/domain/save/SaveService.kt` | 删 | 删 `GameStateSnapshot.gameSpeed`(26) 与 `gameSpeed = 1`(66) |
| K9 | `.../engine/service/FormulaService.kt` | 删 | 删错位注释 `/** 当前速度：0=暂停, 1=1x, 2=2x */`(337)（D5） |
| K10 | `.../engine/domain/battle/HeavenlyTrialBuildOps.kt` | 删 | 删错位注释(190)（D5） |

#### 4.1.2 Kotlin 领域/状态层（`:core:domain`）

| 编号 | 文件 | 类型 | 变更说明 |
|---|---|---|---|
| D-1 | `.../state/GameStateStore.kt` | 删 | `ConfigState` 删 `gameSpeed: Int = 1`(92) |
| D-2 | `.../state/UnifiedGameState.kt` | 删 | 删 `gameSpeed: Int = 1`(39)（该 data class 全仓零消费者，字段为死字段） |
| D-3 | `.../state/SettlementStrategy.kt` | 改 | `:17` KDoc 举例去掉 `gameSpeed`（改举"兑换码记录"） |

#### 4.1.3 C++ 引擎（`game-core`，零 Android 依赖）

| 编号 | 文件 | 类型 | 变更说明 |
|---|---|---|---|
| C1 | `.../system/engine_loop.h` | 删/改 | `PhaseClock` 删 `speed_`(196)、`setSpeed`(87-96)、`speed()`(98)、`msPerPhase` 的 0/2 分支(101-107)、`tick()` 的 `× s`(138-141) 与 `maxPhasesPerTick(s)`(144)、`resetForTest` 中 `speed_`(183)；类头注释(15-35) 改写为单一时速。**`iterate(pausedOrLoading, isSaving)` 暂停分支与 17 槽协议零变更**。**常量更名** `kMsPerPhase1x` → `kMsPerPhase`（:104-105 使用点） |
| C2 | `.../system/settlement.h` | 删/改 | `SettlementEngine` 删 `speed_`(153)、`setSpeed`(115)、`speed()`(116)；`advance()` 去乘数（：73-75）与 `maxPhasesPerTick(speed_)` → `kMaxPhasesPerTick`(77)；`maxPhasesPerTick(int)` 函数(34-40) **删除**（无消费者）；类头注释(16-27) 改写。**`advancePhases` / `settleOnePhase` / 月年边界标志语义零变更**。**常量更名** `kMsPerPhase1x` → `kMsPerPhase`（:31 定义、:76/:83 使用） |
| C2r | `kMsPerPhase1x` 更名的引用同步（**零行为**，纯改名，须一并完成否则编译红） | 改 | `.../system/phase_settlement.h:25`（include 注释）、`:363`（**真实使用点**）；`.../system/ai_sect_ops.h:51`（include 注释）、`:271`（注释）；`.../system/nurture_constants.h:29`（注释）；`.../test/bench/phase_settlement_bench_test.cpp:12`（include 注释）；`.../test/disciple_store_bench_test.cpp:7`（include 注释）；`.../test/engine_loop_test.cpp:89/147/188/210/389`（见 T11） |
| C3 | `.../system/watchdog.h` | 删/改 | `ProgressSnapshot` 删 `speed`(38)；`evaluate` 删首调即判(83-87)；`classify` 删 `speed == 0`(127-130)；类头注释(12) 去掉 `speed` |
| C4 | `.../game_core.h` | 改 | `:109` `advance` KDoc 去掉"× speed / 上限 3×speed"；`:178`、`:181-182` KDoc 去掉 speed |
| C5 | `.../src/game_core.cpp` | 删 | `watchdogVerdict` 删 `snapshot.speed = loop_.time().speed();`(511) |
| C6 | `android/app/src/main/cpp/GameCoreBridge.cpp` | 删 | 删 JNI `nativeLoopSetSpeed`(802-805) 与文件头注释(57) 中的端口名 |
| C7 | `.../gamecore/jni/GameCoreJni.cpp` | 删 | 删对拍桥 `nativeCoreLoopSetSpeed`(503-506) |

#### 4.1.4 Kotlin 消费侧 cap 与配置回声

| 编号 | 文件 | 类型 | 变更说明 |
|---|---|---|---|
| E1 | `.../gameview/GameViewStore.kt` | 改 | `:26` 注释"gameSpeed 属运行态不在镜像面"改写（该字段已整体删除） |
| E2 | `feature/game/.../GameViewModel.kt` | 改 | `:470` KDoc 去掉 `gameSpeed` 说明 |

#### 4.1.5 UI 层（`:feature:game` + `:core:ui`）

| 编号 | 文件 | 类型 | 变更说明 |
|---|---|---|---|
| U1 | `.../ui/game/tabs/SettingsTab.kt` | 删/改 | 删 `SpeedToggleButton`(890-918)；`TimeSpeedControlItem`(816-854) **保留暂停/继续**并改名 `PauseControlItem`、删 `timeSpeed` 参数与 `listOf(1,2)` 循环；`SettingsTab`(:163/175/233/250) 删 `timeSpeed` 形参与传参。**暂停/继续能力零损失**（主界面顶栏 `MainGameScreen.kt:1389-1399` 另有独立暂停入口，一并在位） |
| U2 | `feature/game/.../ui/game/SaveLoadViewModel.kt` | 删 | 删 `_timeScale` / `timeScale`(584-585)、`timeSpeed`(587-588)、`setTimeSpeed()`(593-604) |
| U3 | `.../components/detail/DetailCultivationSection.kt` | 删 | `HpMpBars` 删 `gameSpeed: Int = 1`(137) 与两处 `paused = gameSpeed == 0`(153/157) → 调用 `rememberChasingProgress(target = ...)` |
| U4 | `.../components/detail/DetailBasicInfoSection.kt` | 删 | `BasicInfoSection` 删 `gameSpeed`(60)、`RealmRowData.gameSpeed`(381)、`BasicInfoRealmRow`(259/295)、`CultivationProgressRow`(311/325-328) 全链删除；`HpMpBars(...)`(105) 去参 |
| U5 | `feature/game/.../ui/game/DiscipleDetailScreen.kt` | 删 | `:422 gameSpeed = 1` 删 |
| U6 | `core/ui/.../ui/components/ProgressAnimation.kt` | 删 | `rememberChasingProgress` 删 `paused` 参数(59/65/70) 与 KDoc(53)。**依据**：全仓 8 个调用点中，仅 U3/U4 两处曾传 `paused`（且恒 false，D2）；其余 6 处（`ProductionTheme.kt:265`、`LoadingScreen.kt:68`、`MissionHallDialog.kt:176/290`、`ItemDetailDialog.kt:279/535`）从未传 ⇒ 删除后 `paused` 零消费者，按 `rules/design-plan-review.md` 第三节 YAGNI 反向检查必须移除（测试不是消费者） |

#### 4.1.6 测试面

| 编号 | 文件 | 类型 | 变更说明 |
|---|---|---|---|
| T1 | `.../system/GameTimeClockTest.kt` | 删/改 | 删 `speed2x_1000ms_advances2Phases`(56-66)、`speed2x_3000ms_atScaledCap`(67)、速度切换保存累积(82-95)、`speed2x_10seconds_cappedAtScaled`(165-170)、冻结 20s 2x 上限(185-191)、`setSpeed(0)` 相关(77/158-160/241)；1x 用例（44-54、98-117、173-182、193-206）保留并按常量断言 |
| T2 | `.../system/GameTimeClockPhaseCapParityTest.kt` | 改 | 重锚为「`MAX_PHASES_PER_TICK == 3` 且**与速度无关**（符号面已无 speed 参数）」；双端行为等价由 `DiffEngineLoopTest` 承接 |
| T3 | `.../nativebridge/DiffEngineLoopTest.kt` | 删/改 | 删 `speed2x doubles accumulation both ends`(101-110)；`setSpeed(2)/(0)`(123/135) 改造为常量 1x 双端对拍；`assertEquals(MAX_PHASES_PER_TICK, kotlinPhases)`(149) 保留 |
| T4 | `.../nativebridge/DiffRngBridge.kt` | 删 | 删 `external fun nativeCoreLoopSetSpeed`(163) |
| T5 | `feature/game/.../SaveLoadViewModelLoadTest.kt` | 删 | 删 `setTimeSpeed - paused state does not auto resume (fix)`(924-934) |
| T6 | `.../GameEngineCoreCrashReportTest.kt` | 改 | `:158` 期望键集删 `"speed"` |
| T7 | `.../nativebridge/DiffWatchdogTest.kt` | 删/改 | `snapshot(...)` 删 `speed` 形参与透传(44/60/78)；删 speed=0 三用例(149/151/281) |
| T8 | `.../monitor/GameTimeProgressMonitorTest.kt` | 删/改 | 同上（22/35/38 + 119-120/323） |
| T9 | `.../GameEngineCoreResumeTest.kt` | 改 | `:194 setSpeed(0)` 改由 `setPausedDirect(true)` 构造暂停态（语义等价、覆盖不降级） |
| T10 | `.../GameEngineCoreWatchdogTest.kt` | 改 | `:83 setSpeed(0)` 的既愈用例转「世界时间冻结」路径（`totalPhases` 窗口） |
| T11 | `gamecore/test/engine_loop_test.cpp` | 删/改 | 删 2x 三例(58-70、102-106、117-122)、0 速两例(72-76、108-115)；`Speed1x*`(48-56)、`MultiPhaseInOneTickCappedAt3`(97-100)、`Freeze60sCappedTo3`(124-126)、`UnderCapPreservesRemainder`(134-138)、`phaseProgress/remainingPhaseMs`(85-95)、`ForceConsumeOnePhaseDeducts`(140-148)、`AccumulatedGameMs*`(150-158) 保留；`loop.time().setSpeed(2)`(365/386) 去参 |
| T12 | `gamecore/test/time_system_test.cpp` | 删/改 | 删 `setSpeed(2)`(139) 与 `setSpeed(0)`(154) 用例，改按单一 1x 断言（含 cap=3 与余量保留） |
| T13 | `gamecore/test/game_core_test.cpp` | 改 | `advance` 两条用例(39/47) 改为 1x 语义断言（保持 `advance` 覆盖） |

#### 4.1.7 门禁与文档

| 编号 | 文件 | 类型 | 变更说明 |
|---|---|---|---|
| G1 | `scripts/jni-count.baseline.json` | 改 | `GameCoreBridge.kt` 38 → **37**、`total` 86 → **85**（`node scripts/check-jni-count.mjs --update`） |
| G2 | `docs/architecture.md` | 改 | L67 改单一时速；L176/L179 看门狗表述去 `speed=0` |
| G3 | `docs/ui-read-surface.md` | 改 | L88 `ConfigState` 字段列删 `gameSpeed`（契约文档同步义务） |
| G4 | `docs/cpp-engine.md` | 改 | L307 `PhaseClock` 描述去"速度切换/3×speed" |
| G5 | `docs/save-system-audit-2026-09-21.md` | 改 | L331 A 级缺陷行标注"随二倍速删除闭合" |
| G6 | `CHANGELOG.md` | 增 | 当前版本段落追加（开发者视角技术细节） |
| G7 | `android/app/src/main/assets/changelog_entries.json` | 增 | 当前版本条目 `changes` 数组**末尾追加**（玩家视角通俗文案，见 §4.4） |

### 4.2 关键改造前后对照

#### 4.2.1 `GameTimeClock`（核心契约）

```kotlin
// ── 改造前（节选） ──
var speed: Int = 1                       // 0=暂停,1=1x,2=2x
val speedFlow: StateFlow<Int>
val msPerPhase: Long get() = when (speed) { 0 -> MAX_VALUE; 1 -> 2000; 2 -> 1000; else -> 2000 }

fun setSpeed(newSpeed: Int) {
    val now = timeSource.elapsedRealtime()
    if (speed > 0) accumulatedGameMsInternal += (now - lastWallMs) * speed   // 旧速度结算
    lastWallMs = now
    speed = newSpeed.coerceIn(0, 2)
    _speedFlow.value = speed
    onSpeedChanged?.invoke(speed)                                            // → native
}

fun tick(isSettlementPending: Boolean): TickResult {
    ...
    if (speed > 0) accumulatedGameMsInternal += realDelta * speed
    var phases = (accumulatedGameMsInternal / msPerPhase).toInt()
    val phaseCap = maxPhasesPerTick(speed)                                   // 3 × max(speed,1)
    ...
}

fun maxPhasesPerTick(speed: Int): Int = MAX_PHASES_PER_TICK * speed.coerceAtLeast(1)

// ── 改造后 ──
/** 每旬对应的真实时间毫秒数（单一时速） */
val msPerPhase: Long get() = MS_PER_PHASE

fun tick(isSettlementPending: Boolean): TickResult {
    ...
    accumulatedGameMsInternal += realDelta
    var phases = (accumulatedGameMsInternal / MS_PER_PHASE).toInt()
    val phaseCap = MAX_PHASES_PER_TICK                                     // 常量 3
    ...
}

// companion object: 仅保留 MS_PER_PHASE / MAX_PHASES_PER_TICK 两个常量
```

`MS_PER_PHASE_1X` 更名为 `MS_PER_PHASE`（"1X"后缀在删除后成为误导性命名，违反 §5 编码规范 0.2"命名清晰、意图明确"）；C++ 侧 `kMsPerPhase1x` 同步更名为 `kMsPerPhase`。

#### 4.2.2 看门狗 `FakeRunDetected`（Kotlin + C++ 双端同构）

```kotlin
// ── 改造前 ──
if (last == null) {
    classifyFlags(current)?.let { prev = current; return it }
    if (current.speed == 0) { prev = current; return StallVerdict.FakeRunDetected }  // 死分支
    prev = current; return StallVerdict.Healthy
}
...
if (current.speed == 0) return StallVerdict.FakeRunDetected                          // 死分支
if (current.totalPhases != last.totalPhases) { lastPhaseProgressedAtMs = current.recordedAtMs; return Healthy }
val progressStaleMs = ...
return if (progressStaleMs > fakeRunWindowMs) FakeRunDetected else Healthy

// ── 改造后（删两处 speed 分支，其余逐行不变） ──
if (last == null) {
    classifyFlags(current)?.let { prev = current; return it }
    prev = current; return StallVerdict.Healthy
}
...
if (current.totalPhases != last.totalPhases) { lastPhaseProgressedAtMs = current.recordedAtMs; return Healthy }
val progressStaleMs = ...
return if (progressStaleMs > fakeRunWindowMs) FakeRunDetected else Healthy
```

`watchdog.h::ProgressMonitor` 逐行同构改造（`evaluate` 删 83-87、`classify` 删 127-130）。

#### 4.2.3 追补上限（双端单一来源的新形态）

```kotlin
// 改造前：函数化（为速度缩放而生）
fun maxPhasesPerTick(speed: Int): Int = MAX_PHASES_PER_TICK * speed.coerceAtLeast(1)
// 消费点：GameEngineCoreAuthoritativeOps.kt:56

// 改造后：常量即真相源（符号面唯一、双端各自锁定）
const val MAX_PHASES_PER_TICK: Int = 3
// 消费点：phasesToAdvance.coerceAtMost(GameTimeClock.MAX_PHASES_PER_TICK)
```

```cpp
// settlement.h 改造前
constexpr int maxPhasesPerTick(int speed) { return kMaxPhasesPerTick * std::max(speed, 1); }
// engine_loop.h:144   const int phaseCap = maxPhasesPerTick(s);
// 改造后：删该函数；engine_loop.h 直接 const int phaseCap = kMaxPhasesPerTick;
```

**跨语言一致性门禁改锚**：原 `GameTimeClockPhaseCapParityTest` 锁定"同公式"；删除后公式消失 ⇒ 改为 ① Kotlin 侧断言 `MAX_PHASES_PER_TICK == 3` 且符号面无速度参数；② GTest 侧 `engine_loop_test.cpp::MultiPhaseInOneTickCappedAt3` 断言 8000ms → 3 旬；③ `DiffEngineLoopTest` 双端同输入同输出对拍（3000ms / 8000ms / 60000ms）。**三处齐备才算等价锁成立**。

#### 4.2.4 UI（设置页）

```kotlin
// ── 改造前 ──
val timeSpeed by saveLoadViewModel.timeSpeed.collectAsStateWithLifecycle()
Row { PauseToggleButton(...); listOf(1, 2).forEach { SpeedToggleButton(speed = it, ...) } }

// ── 改造后 ──
Row { PauseToggleButton(...) }        // 仅暂停/继续；节标题「时间流速」改为「暂停」
```

### 4.3 兼容性分析

#### 4.3.1 存档兼容（**零迁移**）

| 项 | 结论 |
|---|---|
| Room schema | **不变**（`@Database(version) = 59` 不动）。`gameSpeed` 列 v20 已删（§2.4） |
| Room Migration | **不新增**（无字段变更 ⇒ 无 `MIGRATION_N_M`、无 `MigrationChainGuardTest` 变更） |
| ProtoBuf | **不变**（`GameData` 无 `gameSpeed` 字段；`@ProtoNumber` 零变更、无 `reserved` 需求） |
| 新旧档互读 | **完全兼容**。删除的字段本就是 `@Transient`/不存在于序列化面的运行态，旧档中即便残留未知字段也由 `ignoreUnknownKeys = true` 宽松解析跳过 |
| 云存档 | **不变**。`SaveData` 结构无 `gameSpeed`，云端无该字段 |
| 迁移前备份 / 三层防御 | **不受影响** |
| `SaveService.GameStateSnapshot` | 删 `gameSpeed` 字段——该 data class 仅用于统计/快照，非持久化载体（`SaveService.kt:21-46` 全为派生计数） |

**⇒ 本方案是"零迁移"变更**，符合用户公约 9（不留尾巴）且风险面显著小于任何带 Migration 的改动。

#### 4.3.2 存档连续性（旧档中"选过 2x"的状态）

删除前：玩家在会话内选 2x ⇒ 仅内存 `gameSpeed` 镜像；**从不落盘**（§2.4）。删除后读同一份旧档：速度维度不存在 ⇒ 直接以 1x 运行。**与"删除前重启后"的行为完全一致**，无状态丢失、无补偿需求。

#### 4.3.3 `SettlementEngine::advance` / `GameCore::advance` 的处置（D4）

- 生产零 JNI 调用者（`GameCoreBridge.cpp` 无该端口；对拍走 `nativeCoreAdvancePhases`）。
- **保留并 1x 化**，理由：① 它是 `SettlementEngine` 的"墙钟批量推进"入口，`time_system_test.cpp` 用它承载**追补上限 + 余量丢弃**的行为锁（`:118-127`）；删除等于丢掉这层测试锚；② 与本次"删速度维度"的因果链只在 `speed_` 一处，删函数属范围外的死代码清扫。
- 死端口本身登记进 §10 技术债表（触发条件明确）。

#### 4.3.4 iOS 对等（`rules/code-quality.md` §1.5 / 原则 4）

| 项 | 结论 |
|---|---|
| 平台能力依赖 | **零**。时间推进在 `game-core`（C++20、零 Android 依赖、桌面可编译），已是为跨平台而生的形态 |
| iOS 对等实现 | **同一份 `PhaseClock`/`EngineLoop` 直接复用**。删除速度维度后 `PhaseClock` 的接口面更小（无 `setSpeed`、无 `speed()`），iOS 侧 JNI 等价物（Objective-C++/C 桥）无需额外适配 |
| iOS 侧禁止事项 | **iOS 平台层不得重新引入倍速乘数**。写入 §4.1.3/C1 的接口契约：`PhaseClock` 对外只暴露 `tick()/consumeDeadTime()/refundPhases()/accumulatedGameMs()`，无任何 timescale 参数 |
| 删除对 iOS 立项的影响 | **正向**（减少跨平台面）。`docs/adr/ios-migration-plan.md` 无需新增 ADR（无架构决策变更，只减符号） |
| 结论 | Android 与 iOS 均可落地，**无平台独占能力**，无需替代方案 |

### 4.4 双更新日志文案

**玩家视角**（`android/app/src/main/assets/changelog_entries.json`，当前版本条目 `changes` 数组**末尾追加**，通俗、无术语、不泄露数值）：

> `"调整：移除了游戏内的「1倍速 / 2倍速」切换——游戏以固定节奏推进，暂停与继续照常可用"`

**开发者视角**（`CHANGELOG.md`，当前版本段落追加）：

> `- **移除二倍速（整维删除）**：删除「速度」这一时间倍率维度，时间推进回到「墙钟差值直接累加」单一时速模型（1 旬 = 2000ms 墙钟，1 游戏月 = 6s，1 游戏年 = 72s）。删除面：Kotlin \`GameTimeClock\` 的 \`speed/speedFlow/setSpeed/onSpeedChanged/maxPhasesPerTick(speed)\`、\`GameEngineCore\` 速度钩子、\`GameCoreBridge.nativeLoopSetSpeed\`（JNI 面 86 → 85）、C++ \`PhaseClock.speed_/setSpeed\` 与 \`SettlementEngine.setSpeed/speed_\`（第二份速度状态机，生产零调用者）、看门狗 \`ProgressSnapshot.speed\` 与 \`speed==0\` 判定（生产路径从无 \`setSpeed(0)\`，属不可达分支）、崩溃归因 \`speed\` 键、设置页 1x/2x 两键与 \`SaveLoadViewModel.setTimeSpeed\`。追补上限由 \`3 × max(speed,1)\` 收敛为常量 3 旬（1x 原子行为逐位一致）。顺带根治：\`ConfigState.gameSpeed\` 死字段导致的弟子详情进度条暂停门控恒失效（\`gameSpeed == 0\` 恒 false）；\`rememberChasingProgress\` 的零消费者 \`paused\` 参数；\`FormulaService\`/\`HeavenlyTrialBuildOps\` 两处错位注释。零 Room 迁移、零 ProtoBuf 变更、零存档格式变更、零渲染面变更。`

---

## 五、影响范围清单

> 格式：`文件路径 — 变更类型 — 变更说明`。共 **55 个文件**（Kotlin 生产源 23 / C++ 生产源 10 / 测试 15 / 门禁与文档 7——其中含双更新日志 2）。

### 5.1 Kotlin 生产源（`android/`）

| 文件 | 变更类型 | 变更说明 |
|---|---|---|
| `android/core/engine/src/main/java/com/xianxia/sect/core/engine/system/GameTimeClock.kt` | 删/改 | 速度状态机整删；`msPerPhase` 常量；`MS_PER_PHASE_1X` → `MS_PER_PHASE` |
| `android/core/engine/src/main/java/com/xianxia/sect/core/engine/GameEngineCore.kt` | 删 | 删 speed 钩子 init 块 |
| `android/core/engine/src/main/java/com/xianxia/sect/core/engine/GameEngineCoreAuthoritativeOps.kt` | 改 | cap 用常量；注释改写 |
| `android/core/engine/src/main/java/com/xianxia/sect/core/engine/GameEngineCoreHandOps3.kt` | 删/改 | 看门狗 `speed==0` 特例删除 |
| `android/core/engine/src/main/java/com/xianxia/sect/core/engine/GameEngineCorePrepOps2.kt` | 删/改 | 崩溃上下文删 `speed` 键；快照删 speed；注释改写 |
| `android/core/engine/src/main/java/com/xianxia/sect/core/engine/monitor/GameTimeProgressMonitor.kt` | 删/改 | 快照删 `speed`；两处 `speed==0` 判定删除 |
| `android/core/engine/src/main/java/com/xianxia/sect/core/nativebridge/GameCoreBridge.kt` | 删 | 删 `nativeLoopSetSpeed` |
| `android/core/engine/src/main/java/com/xianxia/sect/core/engine/domain/save/SaveService.kt` | 删 | 删 `GameStateSnapshot.gameSpeed` 与硬编码 1 |
| `android/core/engine/src/main/java/com/xianxia/sect/core/engine/service/FormulaService.kt` | 删 | 删错位注释（D5） |
| `android/core/engine/src/main/java/com/xianxia/sect/core/engine/domain/battle/HeavenlyTrialBuildOps.kt` | 删 | 删错位注释（D5） |
| `android/core/engine/src/main/java/com/xianxia/sect/core/engine/EquipmentNurtureSystem.kt` | 改 | `MS_PER_PHASE_1X` → `MS_PER_PHASE`（K1r，零行为） |
| `android/core/engine/src/main/java/com/xianxia/sect/core/engine/ManualProficiencySystem.kt` | 改 | 同上（K1r，零行为） |
| `android/core/engine/src/main/java/com/xianxia/sect/core/gameview/GameViewStore.kt` | 改 | 注释改写（去 gameSpeed 说明） |
| `android/core/domain/src/main/java/com/xianxia/sect/core/state/GameStateStore.kt` | 删 | `ConfigState` 删 `gameSpeed` |
| `android/core/domain/src/main/java/com/xianxia/sect/core/state/UnifiedGameState.kt` | 删 | 删死字段 `gameSpeed` |
| `android/core/domain/src/main/java/com/xianxia/sect/core/state/SettlementStrategy.kt` | 改 | KDoc 举例替换 |
| `android/core/ui/src/main/java/com/xianxia/sect/ui/components/ProgressAnimation.kt` | 删 | 删零消费者 `paused` 参数（YAGNI） |
| `android/feature/game/src/main/java/com/xianxia/sect/ui/game/SaveLoadViewModel.kt` | 删 | 删 `timeScale` / `timeSpeed` / `setTimeSpeed` |
| `android/feature/game/src/main/java/com/xianxia/sect/ui/game/tabs/SettingsTab.kt` | 删/改 | 删倍速两键与 `SpeedToggleButton`；保留暂停 |
| `android/feature/game/src/main/java/com/xianxia/sect/ui/game/components/detail/DetailCultivationSection.kt` | 删 | 删 `gameSpeed` 参数与 `paused` 门控（D2） |
| `android/feature/game/src/main/java/com/xianxia/sect/ui/game/components/detail/DetailBasicInfoSection.kt` | 删 | 同上（含 `RealmRowData`） |
| `android/feature/game/src/main/java/com/xianxia/sect/ui/game/DiscipleDetailScreen.kt` | 删 | 删 `gameSpeed = 1` |
| `android/feature/game/src/main/java/com/xianxia/sect/ui/game/GameViewModel.kt` | 改 | KDoc 改写 |

### 5.2 C++ 生产源（`android/app/src/main/cpp/`）

| 文件 | 变更类型 | 变更说明 |
|---|---|---|
| `android/app/src/main/cpp/gamecore/include/gamecore/system/engine_loop.h` | 删/改 | `PhaseClock` 速度状态机整删；逐位移植类头注释改写 |
| `android/app/src/main/cpp/gamecore/include/gamecore/system/settlement.h` | 删/改 | `SettlementEngine` 删 `speed_`/`setSpeed`/`speed`/`maxPhasesPerTick(int)`；`advance` 1x 化 |
| `android/app/src/main/cpp/gamecore/include/gamecore/system/watchdog.h` | 删/改 | `ProgressSnapshot.speed` 与两处 `speed==0` 判定删除 |
| `android/app/src/main/cpp/gamecore/include/gamecore/system/phase_settlement.h` | 改 | `kMsPerPhase1x` → `kMsPerPhase`（C2r，零行为；`:363` 为真实使用点） |
| `android/app/src/main/cpp/gamecore/include/gamecore/system/ai_sect_ops.h` | 改 | 同上（C2r，注释面） |
| `android/app/src/main/cpp/gamecore/include/gamecore/system/nurture_constants.h` | 改 | 同上（C2r，注释面） |
| `android/app/src/main/cpp/gamecore/include/gamecore/game_core.h` | 改 | `advance` 与 `loop()` KDoc 去 speed |
| `android/app/src/main/cpp/gamecore/src/game_core.cpp` | 删 | `watchdogVerdict` 删 `snapshot.speed` |
| `android/app/src/main/cpp/GameCoreBridge.cpp` | 删 | 删 JNI `nativeLoopSetSpeed` |
| `android/app/src/main/cpp/gamecore/jni/GameCoreJni.cpp` | 删 | 删对拍桥 `nativeCoreLoopSetSpeed` |

### 5.3 测试（15 个：§4.1.6 T1–T13 + C2r 的 2 个 bench 文件改名）

增补 C2r 的两个 bench 文件（`kMsPerPhase1x` → `kMsPerPhase` 改名，零行为）：

- `android/app/src/main/cpp/gamecore/test/bench/phase_settlement_bench_test.cpp:12`
- `android/app/src/main/cpp/gamecore/test/disciple_store_bench_test.cpp:7`

### 5.4 门禁与文档（7 个 + 双更新日志，清单见 §4.1.7 G1–G7）

### 5.5 标签项

- **经济影响**：🟢 **不适用**。本方案不新增/变更任何货币、资源、奖励的产出源或消耗汇；删除倍速后每旬产出与删除前 1x **逐位相同**（旬数不变、每旬结算不变）。`rules/economy-design.md` 的源汇闭环无需重算。
- **iOS 影响**：🟢 **已分析（§4.3.4）**。时间推进在零 Android 依赖的 `game-core` C++ 层，iOS 直接复用，**无平台独占能力**，无需 iOS 对等替代实现；同时写入"iOS 平台层不得重新引入倍速乘数"的接口契约。
- **隐私合规**：🟢 **不适用**（无 SDK / 权限 / 网络 / 数据收集 / 广告 / 分析变更）。
- **存档/迁移**：🟢 **零迁移**（§4.3.1）。
- **渲染双端**：🟢 **不涉及**。

---

## 六、测试方案

### 6.1 单元测试（改造后必须覆盖的语义）

| 用例 | 目标语义 | 位置 |
|---|---|---|
| 1x 2000ms → 1 旬；1x 6000ms → 3 旬（1 月） | 单一时速基准 | `GameTimeClockTest`（保留既有） |
| 8000ms → cap 常量 3 旬（余量丢弃） | 追补上限与速度解耦 | `GameTimeClockTest` + `engine_loop_test.cpp` |
| 60000ms → 3 旬（余量丢弃） | 长冻结防护 | 同上 |
| 7000ms → 3 旬 + 1000ms → 1 旬 | 未触顶时余量保留 | 同上 |
| `forceConsumeOnePhase` 扣 1 旬 | 旬消费语义 | 同上 |
| `phaseProgress ∈ [0,1]`、`remainingPhaseMs` | UI 展示镜像 | 同上 |
| `MAX_PHASES_PER_TICK == 3` 且符号面无速度参数 | 双端单一来源新锚 | `GameTimeClockPhaseCapParityTest`（重锚） |
| 3000ms / 8000ms / 60000ms 双端同输入同输出 | 跨语言逐位等价 | `DiffEngineLoopTest` |
| 暂停分支：`pausedOrLoading=true` → 死区消费 + 累加清零 + 0 旬 | 暂停唯一载体 = `isPaused` | `engine_loop_test.cpp`（`EngineLoop` 既有用例，去 setSpeed） |
| 速度维度符号面归零 | 防复发守卫（见 §6.3） | 新增守卫测试 |

### 6.2 集成/回归测试（零行为变化的证据面）

| 面 | 用例 | 期望 |
|---|---|---|
| 暂停/继续 | `GameEngineCoreResumeTest` | 用户暂停跨后台存活、Watchdog 不自愈用户暂停、秘境租约语义不变 |
| 看门狗 | `GameEngineCoreWatchdogTest`、`DiffWatchdogTest`、`GameTimeProgressMonitorTest` | 五判定（Healthy/LoopStalled/FakeRunDetected/PausedByOwner/StalePauseDetected）覆盖不降级；FakeRunDetected 由 `totalPhases` 窗口触发 |
| 崩溃归因 | `GameEngineCoreCrashReportTest` | 键集 = 年/月/旬/tickCount/scene/isPaused/isSaving/isLoading/lastTickMs/watchdogAttempts/oem（**无 speed**） |
| 时间对拍 | `DiffTimeTest`（含 `nativeCoreAdvancePhases(1000)`） | 全绿、零 skip |
| 跨语言对拍全量 | 全部 `Diff*Test`（49–51 套件、270+ 用例） | 0 失败 / **0 skip**（`Diff*` 真跑是门禁硬项） |
| 跳秒/帧计划 | `DiffEngineLoopTest` 其余用例 | 17 槽协议、暂停帧、跳过帧语义零变化 |
| UI | 弟子详情进度条在暂停/恢复下无跳变；设置页仅剩暂停 | 手工/截图核对（本环境不可自动化 ⇒ 记 `pending-device`，不虚报） |

### 6.3 新增防复发守卫（`§5` 编码规范 9.5 守卫测试三要素）

新增 `SpeedDimensionRemovedGuardTest`（`:core:engine` 测试源集，符号面守卫）：

- **① 枚举/入口驱动**：扫描白名单文件集（`GameTimeClock.kt`、`PhaseClock`/`SettlementEngine` 头、`GameTimeProgressMonitor.kt`、`SaveLoadViewModel.kt`、`SettingsTab.kt`、`GameCoreBridge.kt`）
- **② 明确标注故意排除项**：`intentionallyExcluded` = 角色属性 `speed`（身法）相关的 `Disciple`/`Equipment`/`BattleCombatant`/`ManualDatabase` 等文件（**游戏内 `speed` 是角色属性，与游戏倍速同名不同义**，必须显式排除，否则守卫会误报）
- **③ 错误消息带操作指引**：`assert` 消息写明"「游戏倍速」维度已删除（见 docs/design/remove-2x-speed-implementation-plan.md）。若确需重新引入时间倍率，须先扩 C++ PhaseClock 协议并双端对拍，禁止在 UI/ViewModel 层加回 timescale 开关"

对称增加 GTest 侧结构守卫（`gamecore/test/`，与既有 `GpuAllocatorGuard` 同族思路）：断言 `PhaseClock` 无 `setSpeed`/`speed()` 符号、`SettlementEngine` 无 `speed_`。

**墙钟成本核算**（`rules/design-plan-review.md` 第四节）：

| 检查项 | 结论 |
|---|---|
| 新测试墙钟成本 | 符号面守卫 = 2 个文件读取 + 正则扫描 ≈ **< 50ms**；GTest 结构守卫 = 编译期断言 ≈ **0ms** 运行成本 |
| 单测试 > 30s？ | 否 |
| 确定性偏差 | 守卫是确定性静态检查，**单次迭代即可捕获**（无需多轮迭代） |
| 环境依赖 | 守卫读**仓库内源文件**（相对路径，非构建产物、非本机绝对路径）⇒ 无环境依赖；不挂 Gradle 生成物任务 |

### 6.4 对抗性审查要点（实施后逐条自问）

1. **"删除 0 速分支"是否真的无损失？** → 逐条核 §3.2 证据；并用 `grep -rn "setSpeed(0)" android --include=*.kt` 证明生产主源集零命中。
2. **看门狗是否还能检出"世界时间冻结"？** → `GameTimeProgressMonitorTest` 的 `totalPhases` 冻结用例必须**在删除后仍全绿**（这些用例从不传 speed=0）。
3. **暂停是否被误删？** → `isPaused` 链路零触碰（`GameStateStore.isPaused`、`EngineLoop::iterate(pausedOrLoading, ...)`、`MainGameScreen` 暂停按钮）；`GameEngineCoreResumeTest` 全绿为证。
4. **1x 是否与删除前逐位一致？** → `DiffTimeTest` + `DiffEngineLoopTest` + `engine_loop_test.cpp` 的 1x 用例（2000/6000ms）全绿；且改造只删乘数分支（`× 1` 恒等），不触碰任何结算。
5. **JNI 面收缩是否被门禁正确登记？** → `check-jni-count.mjs` 输出 `total=85/85`、双桥无扩散。
6. **`ConfigState` 字段删除是否引发 UI 重组/编译错？** → `compileReleaseKotlin` 六模块全绿。

---

## 七、风险评估与兜底

| 风险 | 等级 | 触发条件 | 后果 | 兜底/缓解 |
|---|---|---|---|---|
| **跨语言时间分叉**（只删一端） | 高 | 漏删 C++ 或漏删 Kotlin 任一端的乘数 | 双端旬数不一致 → 状态镜像漂移、结算错位 | 严格按 §4.1.3 双端同步；`DiffEngineLoopTest`/`DiffTimeTest` 对拍为门禁；§6.3 双端结构守卫 |
| **`MAX_PHASES_PER_TICK` 语义被误改** | 中 | 把 `3 × max(speed,1)` 误写为 `3 × speed` 或改成 6 | 长冻结防护失效/过度丢弃 | 常量值锁定（Kotlin + GTest 双断言）；`MultiPhaseInOneTickCappedAt3` 守住 8000ms → 3 |
| **看门狗检出能力下降** | 中 | 误删 `totalPhases` 窗口判据（与 `speed==0` 分属两处） | 世界时间冻结不再自愈 → 回到历史"27 次游戏时间停止"病灶 | §6.2 强制 `GameTimeProgressMonitorTest` 全绿；C++ 侧 `watchdog.h` 逐行同构核验 |
| **暂停被连带删除** | 中 | 误把"暂停"当作"倍速"一起删 | 玩家无法暂停 | §6.4-3 专项核对；`GameEngineCoreResumeTest` 为门禁 |
| **JNI baseline 漏更新** | 低 | 删端口未跑 `--update` | `check-jni-count.mjs` 报"收缩合法，建议降基线"（**不阻断**，但违反"随 PR 降基线"约定） | §8 步骤 10 显式包含 `node scripts/check-jni-count.mjs --update` |
| **旧档兼容** | 极低 | — | — | 零迁移（§4.3.1）；`RoomMigrationTest` 不受影响 |
| **回滚路径** | 低 | 上线后发现缺陷 | — | **无旗标回滚臂**（这是删除型变更的固有属性）。回滚 = `git revert` 单笔提交；因方案为整维删除且零 schema 变更，revert 无数据面副作用（旧档无需重建）。**明确记录：本方案不提供运行时开关**——`rules/design-plan-review.md` 第一节"兼容回退"维度的答案是"revert 提交"，理由：速度维度已被证明在生产中不可达 0、且 2x 不被持久化，故无灰度必要的运行时风险面 |

---

## 八、实施步骤（可执行顺序）

> 每步结束即验证，不攒到最后。**C++ 改动后必须重建对拍桥**。

| 步骤 | 动作 | 验证命令 | 期望 |
|---|---|---|---|
| **1** | 删 Kotlin 时钟与看门狗面（K1–K6）+ 领域/状态层（D-1–D-3）+ 消费侧（E1–E2） | `cd android && ./gradlew.bat compileReleaseKotlin` | BUILD SUCCESSFUL（先暴露 compile error 面） |
| **2** | 删 UI 面（U1–U6） | 同上 | BUILD SUCCESSFUL |
| **3** | 删 JNI 端口（K7）+ 崩溃/快照键（K5）+ 死字段（K8–K10） | 同上 | BUILD SUCCESSFUL |
| **4** | 改 C++（C1–C7） | `cd android/app/src/main/cpp/gamecore/build/desktop-test && cmake --build .` （PATH 需含 llvm-mingw `bin` + `x86_64-w64-mingw32/bin` + SDK cmake） | 编译 EXIT=0 |
| **5** | 重建对拍桥 .so | `pwsh scripts/build-desktop-jni.ps1` | EXIT=0，`.so` 时间戳新于全部 C++ 源 |
| **6** | 改测试（T1–T13） | 见步骤 7/8 | — |
| **7** | 桌面 C++ 全量 + 单进程直跑复核 | `ctest --test-dir build/desktop-test --output-on-failure` + `./test/game-core-tests.exe` 单进程直跑 | 0 failed；用例数 = 基线 − 删除数（**算术闭合**：逐条登记删了哪几条） |
| **8** | Kotlin 全量测试（必须 `--max-workers=1`） | `cd android && ./gradlew.bat testReleaseUnitTest --max-workers=1 --rerun-tasks -Dgamecore.jni.path=<.so 绝对路径>` | 六模块 0 失败；`Diff*` **0 skip**（真跑） |
| **9** | 静态门禁 | `./gradlew.bat lintRelease detekt` | BUILD SUCCESSFUL；`detekt-baseline.xml` **零新增** |
| **10** | JNI 计数门禁降基线 | `node scripts/check-jni-count.mjs --update` 然后 `node scripts/check-jni-count.mjs` | `total=85`，双桥无扩散 |
| **11** | 规范分发门禁 | `node scripts/check-agent-instructions.mjs` | EXIT=0 |
| **12** | 文档与双更新日志（G2–G7） | 人工核对 §4.4 文案 | 两处日志齐备 |
| **13** | 新守卫（§6.3） | 定向跑守卫测试 | 绿 |
| **14** | 提交（单笔，任务全部完成后） | — | 提交说明写明根因删面 + 算术闭合的用例数 |

**红线自检**（每步遵守）：① 不在任务中途提交；② 不为通过测试而弱化断言（只做"删用例"而非"放宽断言"）；③ 一次性代码（临时诊断日志/脚手架）在提交前清理。

---

## 九、未来场景推演（≥6 个月档）

| 维度 | 必答问题 | 本方案答案 |
|---|---|---|
| **规模增长** | 同类内容 ×10 时成本是否仍线性可控？ | 时间维度是**唯一**的、不是"可增长集合"。删除后新增任何玩法系统都不再需要感知速度（少一个乘区）。反而比保留速度维度**更可控** |
| **生命周期** | "构建/重启/重建/清缓存"全周期行为一致吗？ | 一致。速度本就**不持久化**（§2.4），删除后连"会话内临时态"都不存在 ⇒ 行为在重启/读档/清缓存三条路径上唯一确定 |
| **平台扩张** | 未来 iOS 端是否需要重做？ | **不需要**，且变简单：`PhaseClock` 接口面变小（无 `setSpeed`/`speed()`），iOS 直接复用同一 C++ 核心（§4.3.4） |
| **运营演进** | 6 个月内数值/配置/活动调整时是否需要发版？ | 若未来运营需要"加速挂机"（如双倍卡/月卡加速），**不能**在 UI 层加回倍速——必须按 §6.3 守卫提示走"C++ `PhaseClock` 扩协议 + 双端对拍"的正路。届时若要做，应当是**道具化的时限加速**（有明确起止与可视化），而非"永久倍速开关" |
| **兼容回退** | 上线后若发现缺陷，能关闭开关还是必须发版？ | **必须 revert 单笔提交**（无运行时开关，理由见 §7 最后一行）。因零 schema 变更，revert 无数据面副作用 |

**6 个月后的关键判断**：如果届时产品决定要有"加速"玩法，本方案留下的形态（`PhaseClock` 无 timescale 参数）是**最干净的起点**——加速应当作为"时间源的一层"引入（例如 `TimeScaleSource` 注入 + 明确的生效区间），而不是重新内联进 `tick()`。

---

## 十、技术债与偿还计划

| 债项 | 产生原因（为何现在不全做） | 偿还时机（明确触发条件） |
|---|---|---|
| `GameCore::advance` / `SettlementEngine::advance` 生产零 JNI 调用者（仅 GTest 使用） | 属 PRE-AUTHORITATIVE 时代的墙钟批量入口；本方案只删其 `speed_` 依赖（保持 1x 化的测试锚），不做函数级删除以免丢 `time_system_test` 的追补上限行为锁 | **下一次"死 JNI 端口清理批"立项时**（触发条件：新增/审计 JNI 端口面，或 `docs/threading-contract.md` 端口清理轮）——届时连同 `advance` 与其 GTest 锚一并删除，覆盖由 `settleOnePhase` + `Diff*` 承接 |
| `GameTimeClock.phaseProgress` / `remainingPhaseMs` / `forceConsumeOnePhase` 生产零消费者（KDoc 自称"UI 进度条用"，实际仅测试） | 与二倍速**无因果关系**（是"旬进度展示"能力从未接线）；纳入本方案属范围外清扫，违反"只改必须改的" | **下一次新增"旬进度条/旬倒计时"UI 需求时**（触发条件：出现 UI 消费者，或反向清理时删除） |
| `UnifiedGameState`（`:core:domain`）整个 data class 全仓零消费者 | 同上——本方案只删其 `gameSpeed` 死字段，不删整个死类型（避免扩大 diff 与审查面） | **下一次 `:core:domain` 死代码清理**（触发条件：domain 模块符号面收敛轮，或 Konsist 死类型检查启用时） |

**本方案自身不新增债**：所有删除面均一次删净，无"先注释掉以后再说"，无旗标、无兼容桩。

---

## 十一、全局影响交叉核对（`rules/design-plan-review.md` 第五节）

| 规范文件 | 是否触碰 | 核对结论 |
|---|---|---|
| `rules/code-quality.md` | ✅ | §5 编码规范 0.2/0.3/0.4（命名清晰、无死代码、无魔法数字）；未用符号删净后 detekt 无新违规；`MS_PER_PHASE_1X` 更名符合 0.2 |
| `rules/design-plan-review.md` | ✅ | 本方案即按该文件结构编写；第零节 6 原则、第一~八节自检全过（见 §12.1） |
| `rules/database-migration.md` | ✅ | **零 Entity / 零表结构 / 零 ProtoBuf 变更** ⇒ 无 Migration 需求（已核 §4.3.1） |
| `rules/cpp-priority.md` | ✅ | 引擎时间推进逻辑的删除**同时落双端**（Kotlin 转发层 + C++ 真相源），未产生"Kotlin 先行、C++ 后补"的相悖形态；C++ 侧删除更彻底（第二份状态机一并清） |
| `rules/testing.md` | ✅ | 删测用例为"能力消失后的用例消失"，非放宽断言；新增守卫符合三要素 |
| `rules/code-comment.md` | ✅ | 所有改写注释只描述**当前状态**，不写"之前是 2x/原来有倍速"等历史表述；D5 两处错位注释删除 |
| `rules/version-release.md` | ✅ | 双更新日志必做（G6/G7）；**不擅自更新版本号**（由用户指令决定） |
| `rules/economy-design.md` | ✅ | 不涉及（§5.5 经济标签已声明不适用并给理由） |
| `rules/commercialization.md` §6 | ✅ | 不涉及隐私合规/付费点位/RemoteConfig |
| `rules/data-analytics.md` | ✅ | 不新增埋点；崩溃归因上下文键删 `speed` **不属于**埋点事件三处同步范畴（是 `engineCrashReporter.postCatchedException` 的 map 上下文） |
| `rules/pr-review-checklist.md` | ✅ | 提交前逐条过（§8 步骤 14） |
| `rules/build-quality.md` | ✅ | 门禁命令按该文件（含 `--max-workers=1`、`--rerun-tasks`、`-Dgamecore.jni.path`）；新增警告检查 |
| `rules/expansion-playbook.md` | ✅ | 不新增玩法系统；反而**减少**新系统需感知的横切维度 |
| `rules/ad-cooldown.md` / `rules/social-system.md` / `rules/sdk-init-lifecycle.md` | ✅ | 不涉及 |
| `rules/industry-benchmark.md` | 🟡 **不适用** | 本方案是**删除**而非新功能设计，无对标需求（该文件约束的是"设计玩法/商业化/社交/数据类新功能"） |
| `docs/ui-read-surface.md` | ✅ | §2 镜像合法面：删除的是 `ConfigState`（UI 消费面），**未扩 C++ 协议** ⇒ 不触"UI 要新状态必须先扩协议"纪律；契约文档同步更新（G3） |
| `docs/threading-contract.md` | ✅ | 已 grep 核对：`nativeLoopSetSpeed` **未登记**在册 ⇒ 删除无需登记（只"新增跨线程交互"须先登记）；文件不改 |
| `docs/knowledge-base.md` | ✅ | 已 grep 核对：无速度相关条目 ⇒ 不改 |

### 11.1 YAGNI 反向检查（第三节）

| 本方案引入的新抽象 | 当前生产消费者 |
|---|---|
| **无**（本方案是纯删除 + 常量收敛，未引入任何新类/接口/字段/参数） | — |
| 反向核对：被删除的 `rememberChasingProgress(paused)` 参数 | 删除前**唯一**两个传参点（U3/U4）恒传 false ⇒ **无有效消费者** ⇒ 按 YAGNI 必须删（已纳入 U6） |
| 反向核对：被删除的 `ConfigState.gameSpeed` / `UnifiedGameState.gameSpeed` / `GameStateSnapshot.gameSpeed` | 删除前消费者：① 恒为 1 的 UI 暂停代理（D2，实为失效门控）② 无 ③ 无 ⇒ **无有效消费者** |

---

## 十二、盲区自查与完善建议

> 立场切换为审查者/对抗者，逐维度回答。**实质性建议已回写正文**（标注 ↩）。

| 维度 | 自查结论 |
|---|---|
| **需求理解** | **存在第二种合理解读**：① "删除二倍速" = 删 2x 这一档，但**保留 1x 选择器与速度框架**（最小切入路径）；② "删除二倍速" = 删除整个"时间倍速"能力，游戏固定单一时速。**本文按 ② 出方案**（理由：① 会留下可被重新点亮的 `setSpeed` 通路与 21 处死代码，违反 0.2/0.3 与用户公约 9/15；且 ① 下 `maxPhasesPerTick(speed)` 的双端单一来源公式失去唯一消费者却仍需维护，形成永久漂移风险）。**若用户实际要的是 ①，请明确告知**——届时按"最小切入路径"改派。↩（§0 已并列两路径） |
| **边界与极端** | ① **0 速不可达**已用生产 `grep` 证明（§3.2），故删除不降低防护；② **负值/超大值**：`setSpeed` 原本有 `coerceIn(0,2)` 消毒，删除后入口消失 ⇒ 无需消毒；③ **并发**：`speed` 曾为 `@Volatile`/`std::atomic` 供看门狗跨线程读，删除后**少一个跨线程可见字段**（风险下降）；④ **中断/时序**：`setSpeed` 的"旧速度结算累积（切换零丢失）"逻辑删除后，不存在"切换时丢进度"的时序窗口（能力消失 ⇒ 窗口消失）；⑤ **帧计划协议**：17 槽 `LongArray` **零变更**（速度不在协议内，`engine_loop.h:45-69` 已核） |
| **系统耦合** | 结算四层（L0-L4）**零触碰**（只改 L0 时间推进的乘数）；`EventBus` 不涉及；存档链路不涉及；引导/配置开关不涉及；**RNG 分区不涉及**（倍速不改变每旬抽取次数——旬数不变。已核 `docs/adr/rng-determinism-remediation.md`） |
| **假设有效性** | 假设 1："2x 当前不可持久化" → **已验证**（§2.4 五条证据）；若该假设不成立（例如云端/其他路径写回速度），删除后玩家会从 2x 掉到 1x ⇒ 需在测试中确认 `SaveData` 无 `gameSpeed`（已核）。假设 2："生产无 `setSpeed(0)`" → **已验证**（§3.2）；若不成立，看门狗将失去即时检出路径，改由 90s 窗口承担（延迟更长但仍在）——§6.2 已保留窗口用例作为前提验证。假设 3："`advance` 生产零调用者" → **已验证**（`GameCoreBridge.cpp` 无端口）；若后续有人接上孔，仍为 1x 语义（不影响） |
| **数据与兼容** | 旧档：**零迁移、零字段、宽宽松解析**（§4.3.1）；旧配置：无速度配置项；中断后半完成态：不存在（无迁移事务）；回滚路径：`git revert` 单笔（§7） |
| **非功能属性** | **性能**：删除后每 tick 少一次乘法与一个分支（可忽略的正向）；**内存**：少一个 `@Volatile`/`atomic` 字段与两条 StateFlow（`speedFlow` 链）；**功耗**：不变；**安全**：不变；**隐私合规**：不适用（§5.5） |
| **生命周期** | 见 §9（5 维度逐条）。关键结论：删除使"运营层重新加回倍速"变成**显式架构动作**（须扩 C++ 协议 + 双端对拍），而不是改个 UI 开关就能悄悄加回——**这正是本方案最大的长期价值** |
| **流程盲区** | ① **测试覆盖不到的部分**：真机暂停/恢复手感、弟子详情进度条视觉无跳变——本环境不可自动化 ⇒ 按仓库惯例记 `pending-device`，**不虚报**；② **监控盲区**：崩溃归因上下文删 `speed` 键后，若有线上崩溃依赖该键定位速度异常——已核实该键在删除后无意义（速度恒 1），且 `GameEngineCoreCrashReportTest` 同步锁定新键集；③ **发布/回滚薄弱环节**：无运行时开关（§7 已显式声明）——若用户在"必须可灰度关闭"上有硬要求，需改为"保留 `NativeEngineFlag` 式开关"，但那会与"删除"目标冲突，故本方案**明确选择不发无谓旗标**并登记该取舍。↩ |
| **额外发现（§12 用户公约要求报告）** | ① **D3** `SettlementEngine::setSpeed`/`speed_` 生产零调用者（已纳入删除）；② **D4** `GameCore::advance`/`SettlementEngine::advance` 生产零 JNI 调用者（登记 §10）；③ **D6** `phaseProgress`/`remainingPhaseMs`/`forceConsumeOnePhase` 生产零消费者（登记 §10）；④ **`UnifiedGameState` 整个 data class 零消费者**（登记 §10）；⑤ **D2** 弟子详情进度条暂停门控恒失效（**纳入本方案修复**）；⑥ **D5** 两处错位复制粘贴注释（**纳入本方案删除**）。①③④⑥ 与二倍速无因果关系 ⇒ **只登记不动手**，避免范围扩散；②⑤ 与删除因果直接相关（② 是速度维度残留消费者，⑤ 是 `gameSpeed` 字段的删除余波）⇒ **一并处理** |

### 12.1 完成度自检（第八节逐项勾选）

- [x] 未来场景推演小节已写（≥6 个月档）— §9
- [x] 技术债与偿还计划小节已写（含 3 条债 + "本方案自身不新增债"声明）— §10
- [x] 盲区自查与完善建议小节已写（末章，逐维度 + 三要素；实质性问题已回写 §0/§7/§12）— §12
- [x] 每个新抽象有当前生产消费者 — §11.1（本方案零新抽象）
- [x] 新测试墙钟成本已核算 — §6.3（守卫 < 50ms，单次迭代足够）
- [x] `rules/` 交叉核对完成 — §11（逐文件列结论）
- [x] 决策分级已声明 — §0（中间地带 + 最小切入路径备选）
- [x] 影响范围清单含经济/iOS 标签项 — §5.5（经济：不适用 + 理由；iOS：已分析 + 对等实现结论）

---

## 十三、附：证据索引（可直接跳转核验）

| 结论 | 位置 |
|---|---|
| 旬间隔随速度变化 | `android/core/engine/src/main/java/com/xianxia/sect/core/engine/system/GameTimeClock.kt:55-61` |
| 时间乘数施加点（Kotlin） | `GameTimeClock.kt:153-155` |
| 时间乘数施加点（C++） | `android/app/src/main/cpp/gamecore/include/gamecore/system/engine_loop.h:138-141` |
| 追补上限随速度缩放（Kotlin / C++） | `GameTimeClock.kt:240` / `.../system/settlement.h:34-40` |
| UI 倍速两键 | `android/feature/game/src/main/java/com/xianxia/sect/ui/game/tabs/SettingsTab.kt:845-852、890-918` |
| UI → 时钟通路 | `android/feature/game/src/main/java/com/xianxia/sect/ui/game/SaveLoadViewModel.kt:584-604` |
| UI 封死 speed=0 | `SaveLoadViewModel.kt:594-597` |
| 速度钩子 → native | `android/core/engine/src/main/java/com/xianxia/sect/core/engine/GameEngineCore.kt:175-183` |
| JNI 端口 | `android/core/engine/src/main/java/com/xianxia/sect/core/nativebridge/GameCoreBridge.kt:286-291` / `android/app/src/main/cpp/GameCoreBridge.cpp:802-805` |
| 对拍桥端口 | `android/core/engine/src/test/java/com/xianxia/sect/core/nativebridge/DiffRngBridge.kt:163` / `android/app/src/main/cpp/gamecore/jni/GameCoreJni.cpp:503-506` |
| 看门狗 speed 判据（Kotlin / C++） | `.../engine/monitor/GameTimeProgressMonitor.kt:107-110、159-162` / `.../system/watchdog.h:83-87、127-130` |
| 崩溃归因 speed 键 | `android/core/engine/src/main/java/com/xianxia/sect/core/engine/GameEngineCorePrepOps2.kt:136` |
| `ConfigState.gameSpeed` 恒 1（两个生产构造点都不传） | `android/core/domain/src/main/java/com/xianxia/sect/core/state/GameStateStore.kt:92` + `android/app/src/main/java/com/xianxia/sect/core/state/GameStateStoreImpl.kt:420-431` + `android/feature/game/src/main/java/com/xianxia/sect/ui/game/GameViewModel.kt:472-481` |
| 进度条暂停门控恒 false | `android/feature/game/src/main/java/com/xianxia/sect/ui/game/DiscipleDetailScreen.kt:422` + `.../components/detail/DetailCultivationSection.kt:137、153、157` |
| `gameSpeed` 列 v20 已删 | `android/core/data/src/main/java/com/xianxia/sect/data/local/GameDatabaseMigrationsV11ToV20.kt:283-298、504-615` + `android/core/data/src/test/java/com/xianxia/sect/data/local/RoomMigrationTest.kt:613-626` |
| 当前 DB 版本 59 | `android/core/data/src/main/java/com/xianxia/sect/data/local/GameDatabase.kt:95` |
| 倍速不持久化缺陷登记 | `docs/save-system-audit-2026-09-21.md:331` |
| `SettlementEngine::setSpeed` 零调用者 | 全仓 `grep` 仅 `android/app/src/main/cpp/gamecore/test/time_system_test.cpp:139、154` |
| 错位注释（D5） | `.../engine/service/FormulaService.kt:337`、`.../engine/domain/battle/HeavenlyTrialBuildOps.kt:190` |
| JNI 计数基线 | `scripts/jni-count.baseline.json`（`GameCoreBridge.kt` 38 / `total` 86） |
