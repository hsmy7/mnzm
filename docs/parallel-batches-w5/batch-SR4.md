# SR-4 月变自动存档 + onStop 收口施工卡（自动触发面）

> 立卡：2026-09-22（用户口头「完成第四批」直接驱动，看护 cron 已于 SR-3 终局删除；
> 本会话 = 实施会话，非派发）。
> 权威依据 = `docs/save-system-refactor-plan-2026-09-21.md` §4「SR-4」+ §0 D3/D6 +
> §2 目标架构/触发矩阵 + §5 门 2/3；前置资产 = SR-2（SaveBackend/UploadQueue/SaveArbiter/
> 三态开关/postSaveWarning 告警面）+ SR-3（云主路径，批次停 pending-device）。
> **方案文档 untracked，直读工作区，不自行提交**。
> 编排台账 = `dispatch-ledger.md` SR 系列批次总表（看护已终局，SR 行由实施会话自记，
> 沿用 SR-0..SR-3 口径；`accepted` 仍归用户，本卡不自行登记）。
> 纪律：本会话只做 SR-4 一批，不开下一批；**LEGACY 模式手动保存/读档链行为零变化红线不变**；
> 迁移链与 wire/schema 面零触碰（本批无协议变更）；每子项独立 commit、不夹带。

---

## 0. 定位

SR 系列的**自动触发面批**：把 D6 拍板的两个自动触发点（游戏月月变 + `onStop`）接到既有保存链，
并补上方案 §2 要求的编排层去抖合并。四件事：

1. **月变自动存档**：`GameEngineCore.processMonthYearChange` 月副作用**完整结算之后**发布月变事件
   → 保存侧编排点触发一次静默全量保存（本地事务 + 非 LEGACY 入队）；
2. **`onStop` 收口**：打开 `870be9771` 的旗标（D6 授权），行为 = 本地事务 + 上传队列排空尝试；
3. **去抖合并**：窗口内多触发合并为一次快照（手动保存即存 ⇒ 作废待触发自动窗）；
4. **不再静默**：自动保存成功给消息栏一行"已自动存档"，失败走既有告警通道。

**门**：月变触发时序单测 + 编排器状态机单测 + 定向测试 + 六模块组合门 + `Diff*` 0 skip（IN8）
+ 桌面 ctest 照跑 + **真机后台杀场景**（本环境不可自动化 ⇒ 逐项 pending-device 登记，不虚报）。

---

## 1. 频率口径（本批最大的产品级事实，已与用户确认）

`GameTimeClock.kt:224` `MS_PER_PHASE_1X = 2000L`，1 月 = 3 旬 ⇒ **游戏月 = 6 秒真实时间（2x 下 3 秒）**。
"月变即自动存档"因此不是"每分钟一次"，而是**每 6 秒一次全量快照 + Room 事务**（2x 下 3 秒）。

**用户 2026-09-22 拍板 = 严格按 D6 字面：月月必存**（给出三选：60s 节流 / 月月必存 / 5min 节流，
用户选月月必存）。⇒ 本批**不加墙钟节流下限**（IN2 也禁止用时钟做"谁新"判定；此处时钟只作节奏，
但既然拍板为月月必存，编排层不做任何按秒合并）。

实施侧必须如实承担并登记的后果（写进完成报告，不粉饰）：
- 每旬快照/落盘成本 ×每 6 一次的频率 = 常驻 IO/GC 压力，与 B/G 系列性能目标的张力；
- 非 LEGACY 下入队频率（6s）≫ TapTap 限频（1 次/分钟，SR-0 §2.3）⇒ 队列窗口合并是**唯一**
  不violating限频的机制，去抖窗参数即为限频适配面（`UploadQueue.Config.debounceMs`）；
- 消息栏可见性：**不得**每 6 秒弹一次 snackbar ⇒ 自动存档可见性走**常驻一行状态**（消息栏），
  失败仍走 snackbar 告警通道（不静默）；
- 真机帧率/发热/耗电 = pending-device 硬门（本批代码不声称已达标）。

---

## 2. 关键勘察结论（实施前提，2026-09-22 直读代码确认）

1. **月变唯一发布点**：`GameCoreBridge.FLAG_MONTH_CHANGED` 仅在
   `GameEngineCoreAuthoritativeOps.kt:79-84` 消费 ⇒ `processMonthYearChange` 是月边界的唯一编排入口
   （`processAuthoritativeTick` 单调用点，实测 grep 确认）。年变先于月变的既有顺序不触碰。
2. **月分支尾务在两分支重复**（`GameEngineCorePausOps4.kt:136-137/146-148`：`missionCheck` +
   `spiritStoneWallet.flushPendingEvents`，顺序相同）⇒ 提取 `finalizeMonthBoundary()` 置于 if/else 之后
   = **零行为变更的去重**，同时让"月副作用完整结算之后"成为结构事实（发布点必然在结算与政策
   checkpoint 之后），而不是两个分支各写一遍。
3. **发布通道用 SharedFlow，不用回调属性**：`GameEngineCore` 已是 `@Singleton` 且有
   `_stuckResetEvents`（VM init 收集）先例；引擎回调属性（`missionCheck` 型）由 `GameEngine` 自持，
   若让 VM 注册回调会在 Activity 销毁后留下引擎→VM 强引用。⇒ 新增
   `monthSettledEvents`：`MutableSharedFlow<Unit>(extraBufferCapacity=1, DROP_OLDEST)`，
   引擎线程 `tryEmit` 非阻塞、无订阅者即丢（不重放 = 新 VM 起来不会被陈旧月变多存一次）。
4. **保存链反馈必须参数化**（`SaveLoadViewModelSaveOps.kt:30-31` 已明写"如需严格静默需给保存链加
   `silent` 形参，属后续项"——SR-4 即该后续项）：`SaveFeedback` 沿
   `saveGame → performLocalSaveToSlot → performSaveOperation` 透传，**默认 Manual ⇒ 手动路径逐行不变**
   （硬红线守卫用默认值断言锚定）。
5. **onStop 现状**：`GameActivity.triggerBackgroundSaveIfEnabled()`（`:868`）→
   `saveOnBackground() = saveGame()`（旗标默认关 ⇒ 第一行短路）。本批改走编排点（合并 + 排空尝试），
   且旗标默认开（D6）；`SaveTriggerFlag` KDoc 的"默认值不得改为 true"纪律条目同步改写为
   **D6/SR-4 拍板来源**，保留关闭态 = 回滚臂语义与守卫测试。
6. **viewModelScope 在 onStop 不被取消**（仅 `onCleared`）⇒ 月变去抖窗在退后台后仍会落地；
   但进程可能被杀 ⇒ `BACKGROUND` 触发**跳过合并窗立即执行**（最后一次机会），这是"排空尝试"的
   前半段，后半段 = `UploadQueue` 的排空提示。
7. **`UploadQueue` 已具备惰性单飞 + 窗口合并**：worker 空闲时阻塞在 `wake.receive()`，
   `enqueue` 已 `trySend` 唤醒 ⇒ 新增 `requestDrain()` 只做两件事：置"跳窗"标记（下一次 `process`
   不再 `delay(debounceMs)`）+ 再 `wake.trySend`。**LEGACY 下不得被调用**（调用侧模式门控，
   沿用 `shouldEnqueueCloudUpload` 纯函数先例），避免 LEGACY 拉起 worker 协程。
8. **消息栏落点**：`MainGameScreen.kt:1434` `MessageBarHost(events=gameEventRecords)`，
   数据源是**存档内**的 `GameEventRecord` ⇒ 自动存档提示**不得**写进 gameEventRecords
   （每 6 秒一条 = 污染存档事件流 + 撑大云档 payload，违 IN5/IN7 精神）。改为
   `MessageBarHost`/`MessageBarCollapsed` 增一个可空 `noticeLine: String?` 形参，
   来源 = `SaveLoadViewModel.autoSaveNotice: StateFlow<String?>`（纯 UI 态，不落盘）。
9. **互斥与重叠**（C6 审查项）：`DataPruningScheduler`（300s）/`DataArchiveScheduler`（600s）此前
   与"仅手动保存"并存；本批把保存频率抬到每 6 秒 ⇒ 需核实：修剪/归档的 DB 写与保存事务是否
   只靠 SQLite 事务串行即可安全（Room `withTransaction` 可重入串行）、`.sav` 文件镜像写序、
   `SlotLockManager` 与 `saveLock` 的关系。结论入报告；发现真实危害才最小修复（不夹带重构）。
10. **detekt 阈值**（SR-1/2/3 连续触发）：编排器与自动存档接入**全部落新文件**
    （`SaveOrchestrator.kt`、`SaveLoadViewModelAutoSaveOps.kt`、`SaveFeedback.kt`）；
    `SaveLoadViewModel.kt` 只增 1 个 StateFlow + 1 个收集协程；`GameEngineCore.kt` 只增 2 行流声明；
    `processMonthYearChange` 因尾务提取而**变短**。
11. **测试基建先例**：coroutines-test 的 `backgroundScope` 不被 `advanceTimeBy` 推进
    （SR-2 §5.2 登记）⇒ 编排器单测用自建 scope；VM 测试的 SharedFlow 依赖必须桩真实
    `MutableSharedFlow`（relaxed mock 的 `collect` 抛 `KotlinNothingValueException`，SR-2/SR-3 实证）
    —— 本批 VM init 新增 `gameEngineCore.monthSettledEvents` 收集，相关 setUp 需同步补桩。
12. **Room 2.7.0 嵌套事务警示**（SR-0 §5）：本批不新增嵌套事务；自动保存完全复用
    `storageFacade.save` 既有事务链。

---

## 3. 子项分解与 commit 切分

| # | 子项 | 内容 | commit |
|---|---|---|---|
| C1 | 施工卡 + 台账 | 本卡 + 台账 SR-4 行 in_progress（实施会话自记） | 1 笔 |
| C2 | 引擎月变发布 | `GameEngineCore.monthSettledEvents` + `finalizeMonthBoundary()` 提取（两分支尾务去重）+ 发布点；时序单测（尾务次序 missionCheck→flush→发布；无月变不发布） | 1 笔 |
| C3 | 编排器 | `SaveOrchestrator`（MONTHLY 开合并窗 / BACKGROUND 立即冲刷 / `invalidate` 作废窗 / 纯函数 `autoSaveFeedback` 映射）+ 状态机单测（虚拟时间） | 1 笔 |
| C4 | 保存链接入 | `SaveFeedback`（Manual/AutoNotice/Silent）透传 + `SaveLoadViewModelAutoSaveOps`（月变收集 → 旗标门控 → submit → `autoSave(...)`）+ `autoSaveNotice` StateFlow + 消息栏一行（`MessageBarHost`/`MessageBarCollapsed`/`MainGameScreen` 传参）+ VM 单测 | 1 笔 |
| C5 | onStop + 排空 | `SaveTriggerFlag`（`saveOnBackground=true` + 新增 `autoSaveOnMonthChange=true`，KDoc 改写为 D6 来源）+ `UploadQueue.requestDrain()` + 队列单测 + `GameActivity` 走编排点（模式门控排空尝试） | 1 笔 |
| C6 | 互斥审查 | 修剪/归档 ×自动保存审查结论（§2.9）+ 若有真实危害的最小修复/守卫 | 随 C7 或独立笔 |
| C7 | 门禁 | 定向测试 + 六模块组合门（XML executed 计数 + 时间戳判绿）+ `Diff*` 0 skip + LEGACY 零行为变化守卫 + ctest | 结果入完成报告 |
| C8 | 真机登记 | 后台杀场景 + 月月必存的帧率/发热/耗电/IO 实测（本环境不可测 ⇒ pending-device 逐项登记） | 入完成报告 |
| C9 | 收尾 | 完成报告 `report-SR4-completion-2026-09-22.md` + 台账 SR-4 行 delivered（含 pending-device 与频率风险） | 1 笔 |

---

## 4. 红线复述（实施中逐条对照）

- **LEGACY / 手动零行为变化**：手动保存链（默认 `SaveFeedback.Manual`）、旧云链、读档链逐行不变；
  `requestDrain` 只在非 LEGACY 调用；LEGACY 守卫测试锚定；
- **IN1 原子性**：自动保存第一步 = 本地事务；后置上传失败只降级不回滚（队列既有语义）；
- **IN2 仲裁无时钟**：本批不引入任何"谁新"时钟比较；月变频率拍板是**触发节奏**，不参与仲裁；
- **IN3 接口隔离**：新增消费面只依赖 `StorageFacade`/`SaveBackend`/`UploadQueue`，零 SDK 类型
  （既有 konsist 守卫自动覆盖新文件）；
- **IN6 机制要有调用者 + 测试**：`autoSaveNotice` 必须有 UI 消费点（消息栏），否则不落地；
  旗标必须有 OFF 态守卫测试；
- **IN7 boot 只读不写 / 不污染存档**：自动存档提示只走 UI 态流，绝不写 `gameEventRecords`；
- **IN8**：`Diff*` 对拍 0 skip；本批零 C++/wire/schema 变更 ⇒ ctest 预期 `ninja: no work to do.`；
- **不静默**：自动保存失败一律经告警通道如实呈现（后台场景不可见属已知限制，沿用 `:30-31` 登记）；
- **不夹带**：`DataPruning`/`DataArchive` 仅在审查确证危害时动，其余登记不动。

---

## 5. 真机硬门清单（本环境不可自动化，逐项 pending-device 登记）

- 月月必存口径下真机帧率/发热/耗电/IO 抖动（6s 一次全量快照，2x 下 3s）；
- 后台杀场景：`onStop` 本地事务是否完成、进程被杀后队列待传指针（账本）能否在下次启动续传；
- 消息栏"已自动存档"常驻一行在真机上的可读性（6s 刷新是否可接受）；
- 非 LEGACY 下 TapTap 限频与入队频率错位的真实表现（窗口合并是否真的收敛到 ≤1 次/分钟）；
- 与 `DataPruning`(300s)/`DataArchive`(600s) 并发时的 DB 锁竞争真机观测。

---

## 6. 交付物清单

- `android/core/engine/src/main/java/com/xianxia/sect/core/engine/`：`GameEngineCore.kt`
  （月变事件流）、`GameEngineCorePausOps4.kt`（尾务提取 + 发布）
- `android/core/data/src/main/java/com/xianxia/sect/data/`：`SaveTriggerFlag.kt`（默认值 + 新旗标）
- `android/core/data/src/main/java/com/xianxia/sect/data/cloud/UploadQueue.kt`（`requestDrain`）
- `android/feature/game/src/main/java/com/xianxia/sect/ui/game/`：`saveload/SaveOrchestrator.kt`（新）、
  `saveload/SaveFeedback.kt`（新）、`SaveLoadViewModelAutoSaveOps.kt`（新）、
  `SaveLoadViewModel.kt`（流 + 收集）、`SaveLoadViewModelSaveOps.kt`（feedback 透传）、
  `components/messagebar/MessageBarHost.kt`+`MessageBarCollapsed.kt`（noticeLine）、
  `MainGameScreen.kt`（传参）
- `android/app/src/main/java/com/xianxia/sect/ui/game/GameActivity.kt`（onStop 走编排点 + 排空尝试）
- 测试：`GameEngineCoreMonthSettledTest`（新）、`SaveOrchestratorTest`（新）、
  `SaveLoadViewModelAutoSaveTest`（新）、`UploadQueueTest`（补 drain 例）、
  `SaveTriggerFlagTest`（改默认值断言 + 新旗标）
- 文档：完成报告 + 台账 SR-4 行
