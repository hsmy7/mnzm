# report-SS6 · 事件触发自动存档

> 批次真源：[`TASKBOOK-SS6.md`](TASKBOOK-SS6.md)（开工补卡版）｜上位方案 §2.5（自动存档：短间隔节拍 + 关键事件立即落盘）。
> 分支 `feat/single-save-SS6`（基线 `2b0168e4f`，含 SS0–SS5 并网结果）。协议：[`DISPATCH-ledger.md`](DISPATCH-ledger.md) §4/§7 + [`../gacha-batches/EXECUTION-PROTOCOL.md`](../gacha-batches/EXECUTION-PROTOCOL.md)。

---

## 1. 做了什么（分类表）

| 类 | 内容 | 文件 |
|---|---|---|
| ①新增 | `CriticalSaveEventBus`（core:domain，@Singleton 纯协程组件）：关键事件请求流（`events`，buffer 64）+ 保存完成信号流（`saveCompleted`，buffer 8）+ 涉钱挂起等待面（`awaitNextSaveCompletion`，超时降级）+ `CriticalSaveKind` 四类 | `core/domain/.../state/CriticalSaveEventBus.kt`（新） |
| ②扩展 | `AutoSaveTrigger` +2 值：`CRITICAL_EVENT`（500ms 合并窗）/ `CRITICAL_EVENT_MONEY`（立即冲刷，取消待触发窗并带走窗内已积累触发）；`submit` 立即冲刷分支由 BACKGROUND 单值扩为二值 | `feature/game/.../saveload/SaveOrchestrator.kt` |
| ③旗标 | `SaveTriggerFlag.saveOnCriticalEvent`（默认开 = 回滚臂纪律）；`requestAutoSave` 的 `when(trigger)` 穷尽扩展 | `core/data/.../SaveTriggerFlag.kt`、`feature/game/.../SaveLoadViewModelAutoSaveOps.kt` |
| ④接线 | 八个事件点 `notify`（见 §2 enumerated 表）+ 涉钱两点挂起等待（`DiscipleDelegate`/`MerchantOpsDelegate` Success 后 `awaitNextSaveCompletion`）+ `SaveLoadViewModel` 订阅（init collect → `requestAutoSave`）与保存完成信号（`performLocalSave` 最外层 finally `notifySaveCompleted`） | 见 §2 |
| ⑤参数名收口 | `shouldAutoSave(flagOn, hasActiveSlot, engineLoaded)` → `(flagOn, hasSaveSpace, engineLoaded)`（SS4 报告 §6.2 登记项随批收口），行为零变更；调用点 2 处（`requestAutoSave`/`GameActivity.triggerBackgroundSaveIfEnabled`）具名参数同步 | `SaveTriggerFlag.kt`、`SaveLoadViewModelAutoSaveOps.kt`、`GameActivity.kt` |
| ⑥测试 | 合并窗计数断言（验收④）+ 涉钱立即冲刷断言 + 反馈映射穷尽 + bus 语义四例 + 时序断言（Robolectric 真打 Room DB，验收③） | 见 §4 |

**防风暴语义保持**：事件一律经 `SaveOrchestrator.submit(trigger)`（既有 500ms 合并窗），不直接调保存；仅涉钱类（`CRITICAL_EVENT_MONEY`）走立即冲刷（既有 BACKGROUND 分支扩展）。节拍常量 10s、合并窗 500ms、onStop 链、增量路径（SS5 写本体）、C++ 全部未动。

## 2. 事件点 enumerated 表（与方案 §2.5 五类逐类对账）

| # | 触发位置（文件:方法） | 方案类别 | `CriticalSaveKind` | 合并窗类型 | 事件发起线程核实 |
|---|---|---|---|---|---|
| 1 | `GameEngineJadePurchaseOps.tryNativeJadeBreakthroughBonus`（native 臂落账成功返回 `Success` 前）+ `JadeSymbolService.deduct`（回退臂同事务落账成功） | 涉钱（玉符流水 append，`SPEND_BREAKTHROUGH_BONUS`） | `MONEY` | **flushNow 立即冲刷** | 购买入口 UI 主线程协程（`JadePurchaseFlow` `scope.launch`）→ 引擎扩展内部 `withEngineContext` 落账（引擎线程）→ 返回主线程后由 `DiscipleDelegate` 挂起等待 |
| 2 | `GameEngineJadePurchaseOps.tryNativeJadeMerchantRefresh`（native 臂）+ `JadeSymbolService.deduct`（回退臂） | 涉钱（`SPEND_MERCHANT_REFRESH`） | `MONEY` | **flushNow 立即冲刷** | 同 #1（`MerchantDialog` → `MerchantOpsDelegate`） |
| 3 | `JadeSymbolService.grantFromAd`（native 臂回执成功 / 回退臂 `appendLedger` 后） | 涉钱（`GRANT_AD`） | `MONEY` | **flushNow 立即冲刷** | SDK 回调线程 → `AdsDelegate.launchOnEngine` 派发引擎线程 notify；**非挂起方法，不做同步等待**（见 §5 裁定 3） |
| 4 | `JadeSymbolService.settleGrants`（native 臂回执成功 / 回退臂 `appendLedger` 后；仅发放分支，零余额变化不触发） | 涉钱（`GRANT_TIME`） | `MONEY` | **flushNow 立即冲刷** | 引擎线程游戏循环 tick 内；**tick 非挂起，不做同步等待**（见 §5 裁定 3） |
| 5 | `GachaViewModel.onPullResult`（`GachaPullResult.Success` 分支；镜像 `applyDirtyFromNative` 已在 `GachaFacadeImpl.pull` 返回前同步完成） | 不可逆随机结果（抽卡出货：角色/碎片/高品阶物品） | `GACHA` | 500ms 合并窗 | 引擎线程（`GachaDelegate.pull` 的 `onResult` 回调在引擎线程执行） |
| 6 | `RedeemCodeFragmentOps.grantFragmentReward`（`GachaGrantResult.Granted`；含满 100 自动升星——当前代码无独立「合成/升星」入口，见 §5 裁定 2） | 不可逆消耗（碎片入账/升星） | `IRREVERSIBLE_CONSUME` | 500ms 合并窗 | 引擎线程（兑换码链路 `withEngineContext`） |
| 7 | `HeavenlyTrialViewModel.claimClearReward`（`ClaimClearRewardResult.Success`；每关一次性领取 `claimedRewardLevels` = 首通语义承载） | 唯一性里程碑（首次通关） | `MILESTONE` | 500ms 合并窗 | UI 发起 → `launchOnEngine` 引擎线程 notify |
| 8 | `BreakthroughAnalyticsObserver.reportNewBreakthroughs`（旬前后 `breakthroughCounts` 列差分 > 0；生产 AUTHORITATIVE 路径突破判定在 C++，本点为 Kotlin 镜像消费钩子） | 唯一性里程碑（渡劫/突破**成功**） | `MILESTONE` | 500ms 合并窗 | 引擎线程（每旬镜像后对拍点） |

**五类对账（v2 适配）**：
- **涉钱** ✅ 四点全覆盖（#1–#4），notify 收口于 `JadeSymbolService.notifyMoneyLedgerChanged` 单点（native 臂购买成功处经 `GameEngineJadePurchaseOps` 调用）——购买链不会双触发（delegate 只等待不 notify）。
- **不可逆随机** ✅ #5。十连在 `GachaFacade` 是一笔事务一次回执 → 1 事件；验收④的「10 事件」防风暴口径在 `SaveOrchestratorTest` 以 10 次 submit 断言（合并窗语义层），时序测试以 10×notify 真链路复核。
- **不可逆消耗** ⚠️ 见 §5 裁定 2：#6 覆盖碎片入账+自动升星；「熔炼」引擎入口存在但 UI 未接线（`dismantleEquipment` 零生产调用）。
- **唯一性里程碑** ⚠️ #7（首通）+ #8（渡劫成功）覆盖；**成就系统无承载面**（全库 `achievement|成就` 零命中，v2 适配如实登记）；**渡劫失败侧**无既有 Kotlin 消费钩子（C++ 内部结果无镜像差分），由 10s 节拍兜底。
- **版本节点（`MIGRATION_*`）** ——迁移链已随 SS0 退役，**该事件不存在**（v2 适配，报告注明 ✓）。
- **删档/重置成功** ⚠️ 见 §5 裁定 1：不新增 submit 接线（v2 偏离登记）。

## 3. 涉钱同步落盘：时序语义与线程核实

**同步承诺面**（D-4）：#1/#2 两处玩家购买操作。链路：`purchase` 成功返回 `Success` → delegate 挂起 `awaitNextSaveCompletion(5s)`；同一时刻 `JadeSymbolService` 已 notify → `SaveLoadViewModel`（viewModelScope）collect → `orchestrator.submit(CRITICAL_EVENT_MONEY)` → 立即冲刷 `onFire` → `saveGame`（ioDispatcher 协程）→ `performSaveOperation` 成功路径落盘 → `performLocalSave` finally 发 `notifySaveCompleted` → 等待方返回。**快照构造（`buildSaveSnapshot`）晚于流水 append，故该次保存必然包含本笔流水**。

**主线程阻塞面核实（任务书 §6.3 风险项）**：
- **已核实**：全部事件点（#1–#8）的 notify 均为 `tryEmit` 非挂起；落盘 IO 全部发生在 `viewModelScope.launch(ioDispatcher)` 协程 + Room 自有执行器——**主线程与引擎线程均无阻塞 IO，只有协程挂起等待**（购买 UI 的完成回调最多推迟一个落盘周期）。`withEngineContext` 的落账在引擎线程，涉钱等待挂起发生在主线程协程。
- **等待竞态（如实登记）**：`awaitNextSaveCompletion` 等待「notify 之后完成的任意一次保存」。若 notify 瞬间恰有在途保存（isSaving 互斥使涉钱触发的保存被拒、日志「下次触发重试」），该在途保存的快照可能不含本笔流水——等待方收到的是它的完成信号。后果 = 该笔流水落盘推迟至下一次节拍（≤10s），仍远优于改造前的常态丢失窗口；碰撞概率 ≈ 节拍保存耗时/10s 周期（增量路径毫秒级，<0.2%）。购买 UI 等待上限 5s（`MONEY_SAVE_ACK_TIMEOUT_MS`），超时返回不拖死交互。

**落盘耗时实测（JVM/Robolectric，30 弟子+40 丹+60 材料+20 战报+10 邮件规模、单行变更脏集）**：增量路径单次写 best=**11.7ms** / p50=**14.7ms** / max=22.5ms（n=20，预热 3 次）。任务书预期「亚毫秒~毫秒级」在 JVM 环境未达（Robolectric SQLite 驱动 + 测试调度开销）；量级结论：涉钱同步等待的用户可感知延迟 <20ms，购买交互无感。【已核实：JVM 侧实测；推测：真机 Android SQLite/WAL 预期更低，未做真机测量（真机验证归 pending-device 清单）】

## 4. 验证（门禁实跑数值）

| 门禁 | 结果 |
|---|---|
| `compileReleaseKotlin` | ✅ BUILD SUCCESSFUL |
| JVM 四模块 `:core:data :core:engine :feature:game :app`（`--max-workers=1 --rerun-tasks`，worktree 自编桥） | ✅ **5715/0 全绿**（data 700 + engine 3023 + feature:game 976 + app 1016）；detekt 实修后的 engine 终树复跑 ✅ |
| detekt 四模块（data/engine/feature:game/app） | ✅ 全绿，baseline 零新增；途中 2 处违规**实修**（`JadeSymbolService` TooManyFunctions 20→19：`notifyMoneyLedgerChanged` 收敛外移同包扩展 `JadeSymbolServiceCriticalSaveOps.kt`；`RedeemCodeService` 未用 import 删） |
| `node scripts/check-agent-instructions.mjs` | ✅ EXIT=0（规则①–⑤全过，485 引用闭包可解析） |

途中门禁插曲（与记忆中 SS5 验收教训同型）：首轮 JVM 全量以主树 `.so` 跑出 `DiffBridgeSourceSyncGuardTest` 红（对拍桥与 worktree 源不同源 180 文件）——**worktree 重编桌面 JNI 桥**（`build-desktop-jni.ps1`，259 文件同源指纹）后全量绿。本批零 C++ 触碰，属桥工件与检出源不同源的环境问题。

新增/扩展测试：
- `SaveOrchestratorTest` +2 例：十连十事件窗内恰好 1 次 fire（**验收④**）；涉钱立即冲刷并吞并窗内节拍触发；反馈映射扩事件组合。
- `CriticalSaveEventBusTest`（core:domain）4 例：事件按序到达 / 等待挂起至信号 / 超时返回 false / 超时不消费后续信号。
- `CriticalEventSaveTimingTest`（feature:game，Robolectric `@Config(sdk=[34])`，真 Room in-memory）2 例：**涉钱信号到达时 game_data 行已含该笔流水**（验收③，真打 DB）+ 十次 GACHA 事件真链路恰好 1 次落盘。测试注释标注 runTest 虚拟时间与真实 Room IO 的竞争面及规避写法（窗口期断言只用非挂起内存观测）。
- `SaveTriggerFlagTest`：新旗标默认开锁定 + 参数名同步。

### 旧用例处置表

| 用例 | 处置 | 理由 |
|---|---|---|
| `SaveOrchestratorTest` 既有 5 例 | 保留原断言（月变措辞为历史 KDoc 语境，行为断言全部有效——`REALTIME` 即节拍触发） | 合并窗/冲刷/作废语义未变 |
| `SaveTriggerFlagTest` 4 例判定 + 2 例默认值 | 保留，具名参数 `hasActiveSlot`→`hasSaveSpace` 同步 | SS6-d 参数名收口 |
| `JadeSymbolServiceTest`/`GachaViewModelTest`/`HeavenlyTrial*`/`RedeemCode*`/`AdsDelegate*`/`GameViewModel*` 既有用例 | 零改动全绿 | 新构造参数全部带默认值（测试直构兼容），notify 为无订阅零副作用 |

## 5. v2 适配裁定（4 条，逐条因果链）

1. **删档/重置成功不新增 submit 接线**（验收①字面偏离，登记待验收裁定）：`SaveWipeCoordinator` 的两路完成点均无落盘对象——`wipeIfNeeded` 在 `Application.onCreate`（数据库未打开、无引擎、`SaveOrchestrator` 归 `SaveLoadViewModel` 尚不存在，接线即死代码）；`requestWipeOnNextLaunch` 置标记后 `killProcess` 重启走同一启动路径。删档的持久化由**文件删除本身**完成（账号数据空间整树删除），其后 `AutoEntryResolver` 自动建档 → `performInitialSaveForNewGame` 显式直存（新档首存）关闭丢失窗口；运行时「重置」（restart）已有预存（`protectivePreSaveBeforeRestart`）+ 重置后落新档（`performRestartSave`）双直存。**没有任何一秒数据处于「未持久化且无兜底」状态**，submit 接线无增益。
2. **合成/升星/熔炼类的承载面**：当前代码无独立「手动碎片合成」「装备升星」「熔炼」入口——升星内嵌于碎片入账（满 100 自动升星，`GachaFragmentLedger`），唯一活跃入账面是兑换码（#6 接线）；`dismantleEquipment`（分解）引擎入口存在但 UI 零调用（接线即死路径）。装备强化不在方案五类枚举内，未接。
3. **广告玉符（#3）与时长发放（#4）不做同步等待**：调用上下文物理不可挂起（#3 = SDK 回调 fire-and-forget；#4 = 引擎 tick 非挂起，且 `runBlocking` 为 🔴 禁用项）——两点的 notify 走立即冲刷（毫秒级内落盘发起），丢失窗口由 10s 节拍兜底。D-4 的同步承诺兑现于 #1/#2 玩家交互路径。
4. **事件传输面引入 `CriticalSaveEventBus`**（YAGNI 自检）：事件源分布在 core:engine 服务（3 处）与 feature:game ViewModel/delegate（5 处），而 `SaveOrchestrator` 归 `SaveLoadViewModel` 私有——依赖方向禁止 core 层反向依赖 feature 层，总线是唯一的单向汇入面。生产消费者 = 本批全部 8 个事件点 + `SaveLoadViewModel` 订阅。

## 6. 未完成 / 登记

1. **（SS7）** 关键事件落盘成功入云队列：`performSaveOperation` 成功路径的 `maybeEnqueueCloudUploadAfterLocalSave(saveData)`（`SaveLoadViewModelSaveOps.kt`）对**全部保存链**生效（含事件触发的自动保存，复用同一 `saveGame` 链）——验收⑤零新代码满足；LEGACY 模式短路语义与手动/节拍保存一致（不因事件触发而变化）。涉钱「立即入云队列」的入队即此；上传排空仍走队列既有 2s 去抖/限频/退避。
2. **（SS10）** 玩家可见变更（关键数据丢失窗口关闭）并入 4.2.00 唯一条目（既有登记，本批未动 changelog）。
3. **（真机 pending-device）** 增量写真机耗时实测（本报告 §3 的 JVM 数据不能外推为真机值）。
4. **渡劫失败侧里程碑**：无既有 Kotlin 消费钩子（C++ 内部结果，无镜像差分面），未接线；丢失窗口 ≤10s 节拍。若产品要求成败同权，需 SS9 后续批补 C++ 侧差分通道。
5. **验收⑥** `shouldAutoSave` 参数名收口已完成（`hasActiveSlot`→`hasSaveSpace`）；判定逻辑与调用语义零变更（`requestAutoSave`/`GameActivity` 两处仍传 `isGameLoaded`，与 SS4 前一致）。

## 7. 风险

- 【已核实】涉钱等待的在途保存竞态（§3）：后果 = 单笔流水落盘推迟 ≤10s，概率 <0.2%；购买 UI 等待上限 5s 防拖死。
- 【已核实】`CriticalSaveEventBus` 为无 replay SharedFlow：`SaveLoadViewModel` 销毁期间（理论上门禁内不可达——事件全部发生在游戏运行中）发出的事件丢弃，节拍兜底。
- 【推测】消息栏反馈口径：事件触发落盘复用 `AutoNotice`（消息栏常驻一行），抽卡/购买后消息栏时间戳刷新一次——频率玩家操作级，未做 UX 评审；如需静默化改 `saveFeedbackFor` 一行。
- 【已核实】本批零 C++ 触碰、零 Room schema 变更（无版本递增、无迁移面）、`MirrorReadOnlyGuardTest` 符号面零新增（无任何 `stateStore.update` 新增写入——事件点只读结果后 notify）。
