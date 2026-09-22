# SR-4 完成报告——月变自动存档 + onStop 收口（自动触发面）

> 日期：2026-09-22　批次：SR-4（存档云唯一重构 SR 系列）
> 权威依据：`docs/save-system-refactor-plan-2026-09-21.md` §4「SR-4」+ §0 D3/D6 + §2 触发矩阵 +
> §5 门 2/3；施工卡 `docs/parallel-batches-w5/batch-SR4.md`；前置 = SR-2（SaveBackend/UploadQueue/
> 三态开关）+ SR-3（云主路径，停 pending-device）。
> 驱动方式：看护 cron 已于 SR-3 终局删除（`fca6faf55`），本批由用户「完成第四批」口头直接驱动，
> 本会话 = 实施会话；台账 SR-4 行由实施侧自记（沿用 SR-0..SR-3 口径），`accepted` 归用户。

---

## 0. 一句话结论

月变与 `onStop` 两个自动触发点已接入唯一保存编排点（`SaveOrchestrator`），本地事务 + 云上传
入队/排空尝试 + 消息栏常驻一行 + 失败不静默全部落地；**频率口径按用户拍板"月月必存"实施**
（游戏月 = 6 秒真实时间），由此产生的性能/限频/观感代价已量化登记并列为真机硬门（§5、§7）；
手动保存与 LEGACY 默认模式行为零变化由测试锚定。门禁数字见 §6。

---

## 1. 交付链（逐子项独立 commit）

| # | commit | 内容 |
|---|---|---|
| C1 | `800f326ad` | 施工卡 `batch-SR4.md`（勘察 12 结论 + 频率拍板记录）+ 台账 SR-4 行 in_progress |
| C2 | `44e7f5662` | 引擎月变发布：`GameEngineCore.monthSettledEvents` + `finalizeMonthBoundary()` 尾务提取（两分支去重）+ 时序单测 3 例 |
| C3 | `5268c8081` | `SaveOrchestrator`（合并窗 / BACKGROUND 立即冲刷 / invalidate 作废）+ `SaveFeedback` 三口径 + 纯函数映射 + 状态机单测 5 例 |
| C4 | `4e1c97e53` | 月变→编排→落盘接线 + 保存链 feedback 透传 + 消息栏一行（`MessageBarHost`/`Collapsed`/`MainGameScreen`）+ `SaveTriggerFlag` 按 D6 定默认 + `shouldSaveOnBackground`→`shouldAutoSave` + VM 测试 7 例 |
| — | `c25f507ed` | **自纠**：C4 笔误把构建副产物 `atlas-rgba-manifest.json` 一并提交（台账明令勿混提交），单独回退该文件并把本地再生成态恢复为未提交工作区状态；`4e1c97e53` 历史中仍含该 2 行，如实登记不掩盖 |
| C5 | `b5e3ebc00` | `UploadQueue.requestDrain()` 排空尝试（跳窗，不绕安全闸、不拉 worker）+ onStop 走编排点 + `GameActivity` KDoc；队列测试 12→15 例、VM 测试 7→9 例 |
| C5b | `55a717427` | 自动口径保存失败改消息栏**持久一行**（6 秒节奏下 snackbar 不可用）+ 5 个失败点全覆盖 + 测试 9→10 例 |
| C6 | 本报告 §5 | 后台修剪/归档互斥审查（**零代码改动**，结论 = 既有槽位排他锁已覆盖） |
| C7-1 | `189fc490e` | 组合门第一轮 detekt 打回自纠：新测试类 2 条未用 import（零语义变化） |
| C7 | 本报告 §6 + 收尾笔 | 门禁复跑（第一轮判红→第二轮全绿，实测数字入 §6）+ 完成报告 + CHANGELOG + 台账 |

交付面（`git diff 800f326ad..HEAD --stat -- android`）：**22 文件 / +1,142 / −63**
（含 `c25f507ed` 对副产物的回退，净变化为零），全部为 Kotlin 与文档，
**零 C++、零 wire/schema、零迁移链触及**（本批无协议变更）。

---

## 2. 子项逐项交付与判据

### C2 月变触发时序（方案门：「月副作用完成后才存」）

- 发布点：`GameEngineCorePausOps4.kt::finalizeMonthBoundary()` 末句 → `notifyMonthSettled()`。
  尾务原来是 native/回退两分支各写一遍的重复段（`missionCheck` + `spiritStoneWallet.flushPendingEvents`），
  本批提取为单函数并置于 if/else 之后：**顺序逐行不变**（去重），且"发布必在月结算与政策 checkpoint
  之后"由结构保证，不再依赖两处各摆一次不漏。
- 通道：`MutableSharedFlow<Unit>(replay=0, extraBufferCapacity=1, DROP_OLDEST)`。与
  `stuckResetEvents`（replay=1）刻意相反——自动存档幂等，空窗期陈旧月变重放会让新建 VM 多存一次。
- 引擎对存储层零依赖：反向只有一条事件流（IN3 合规，方向为"引擎发布 → 保存侧订阅"）。
- 判据：`GameEngineCoreMonthSettledTest` 3 例——① 尾务次序 `missionCheck → flush → publish`；
  ② 非月变（`monthChanged=false`）零发布；③ 无订阅者期事件不重放。实测 **3/3 绿、0 skip**。

### C3/C4 编排点与保存链接入

- `SaveOrchestrator`（feature/game/saveload，105 行）：
  `MONTHLY` 开 500ms 合并窗（**只做同刻多源合并，不是节流下限**）；`BACKGROUND` 取消窗立即冲刷
  （`viewModelScope` 在 onStop 不取消，但进程随时可能被杀，等窗 = 不存）；`invalidate()` 由手动保存
  触发——手动即存覆盖同一状态，窗内自动保存属重复（这就是方案"手动/月变/onStop 合并"里手动的语义）。
- 月变事件 → `requestAutoSave(MONTHLY)`：`shouldAutoSave(旗标, 槽位≥1, 引擎已加载)` 三前置
  （`MIN_AUTO_SAVE_SLOT=1`：slot 0 是云会话伪槽，自动存档不得落它）。
- 保存链 `SaveFeedback`：`Manual`（默认，逐行现状）/ `AutoNotice`（月变）/ `Silent`（onStop）。
  真失败（未初始化 / 超时 / 落盘失败 / OOM / 异常 / 等锁超时）三类口径一律不静默；
  "忙/互斥类拒绝"只对手动口径弹提示（自动口径降日志，下一触发点自然重试）。
- 判据：`SaveOrchestratorTest` 5 例（虚拟时间）+ `SaveLoadViewModelAutoSaveTest` 10 例
  （含 LEGACY 不 enqueue、LEGACY 不触 requestDrain、CLOUD_TRANSITION 恰一次 enqueue+drain、
  手动作废窗、旗标关零副作用、失败持久行）。

### C5 onStop 收口 + 排空尝试

- `SaveTriggerFlag`：`autoSaveOnMonthChange=true`、`saveOnBackground=true`（D6 授权；关闭态保留为
  回滚臂 + 守卫测试），KDoc 改写并写明"默认值改动必须重新对应到方案/用户拍板"。
- `UploadQueue.requestDrain()`：置"下一条跳窗"标记 + 唤醒空闲 worker；**不**绕冲突仲裁/共享冷却/
  退避/熔断，**不**拉起 worker（LEGACY 惰性零活动不变，模式门控在调用侧）。
- 判据：`UploadQueueTest` Q11（跳窗即传）/ Q11b（对照：不排空仍在窗内）/ Q12（空队列零副作用）；
  `SaveTriggerFlagTest` 6 例（两默认值 + 三前置穷尽 + 关闭态短路）。

### C4 用户可见（消息栏一行）

`SaveLoadViewModel.autoSaveNotice: StateFlow<String?>` → `MainGameScreenUiOverlay` →
`MessageBarHost(noticeLine=…)` → `MessageBarCollapsed` 首行渲染，成功文案
`已自动存档 · 第X年Y月`（降级追加 `（备份未写入）`，与审计 §12-C 同纪律），失败文案
`自动存档失败：<原因>`。**纯 UI 态，不写 `gameEventRecords`**——月变频率下每 6 秒一条事件会
污染存档并撑大云档 payload（违 IN5 精神）。

---

## 3. LEGACY / 手动零行为变化核对（硬红线）

| 面 | 结论 | 证据 |
|---|---|---|
| 手动保存链 | `SaveFeedback.Manual` 为默认形参，成功/失败文案与顺序逐行不变 | `SaveLoadViewModelLoadTest` 38 例全绿（零改动，仅补桩 `monthSettledEvents`） |
| 云上传投递 | 自动保存与手动同走 `maybeEnqueueCloudUploadAfterLocalSave`，LEGACY 在纯函数 `shouldEnqueueCloudUpload` 短路 | `SaveLoadViewModelAutoSaveTest` LEGACY 段 `exactly=0` enqueue |
| 队列排空 | `requestCloudUploadDrain()` 模式门控，LEGACY 不置位不唤醒 ⇒ 队列零协程活动不变 | 同上 `requestDrain` `exactly=0` |
| 引擎尾务 | `finalizeMonthBoundary` 为两分支重复段的等价提取；月变之外零发布 | 时序测试 ①② + Diff\* 对拍（§6） |
| 存档/协议/wire/schema | 零触及 | `git diff --name-only` 无 .proto/.cpp/.h/schema/迁移文件 |
| 迁移链历史基线 | 零触碰 | 本批无 schema 变更 |

**唯一的玩家可见变化 = 本批主题本身**（自动存档回归：月变 + onStop），来源 = 方案 D6 拍板 +
本批"月月必存"频率拍板，非红线违例。

---

## 4. 频率拍板与其直接代价（不粉饰）

实测：`GameTimeClock.kt:224` `MS_PER_PHASE_1X = 2000L`，1 月 = 3 旬 ⇒ **游戏月 = 6 秒真实时间**
（2x = 3 秒）。实施前向用户提请三选（60s 最小间隔节流 / 月月必存 / 5min 节流），
**用户选「严格按 D6 字面：月月必存」** ⇒ 编排层不做按秒节流。由此：

1. **每 6 秒一次全量快照 + Room 事务**：`buildSaveSnapshot`（引擎线程）+ 32 DAO 单事务 +
   SR-1 邮件表全量读 + `.sav`/`.bak` 文件镜像。
2. **保存期间游戏时钟停摆**：`saveGame` 同步置 `isSaving` → `skipTickIfNeeded()` 每 tick 早退并
   `gameClock.consumeDeadTime()` ⇒ 每次自动存档的时长从游戏时间里扣除（表现为"进度变慢"）。
   手动保存历史上是偶发，月月必存下成为常态。
3. **每次保存可能触发 `System.gc()`**：`canPerformSaveOperation`（<40% 余量）与
   `performGarbageCollection`（>70% 占用）两条启发式在 6 秒节奏下会周期性命中，内存紧的设备上
   表现为周期性 GC 停顿。
4. **非 LEGACY 下入队频率 ≫ TapTap 限频**（6 秒 vs 1 次/分钟，SR-0 §2.3）：唯一不违反限频的机制是
   `UploadQueue` 的窗口合并 + 共享冷却；本批 `debounceMs=2s` 远小于 60s ⇒ 稳态下会周期性触发
   RATE_LIMITED 退避（队列已具备该路径）。**建议后续批把合并窗按限频量级重设**（属调参，不改结构）。
5. **存储熔断会更常被打到**：`StorageCircuitBreaker` "save" 域阈值 5 次 / 恢复 30s ⇒
   存储退化时约 30 秒内自动存档被拒（失败行持续显示，不谎报成功）。

以上 1–5 的**真机量化**列为本批硬门（§7），本环境不可测，未声称达标。

---

## 5. C6 后台修剪/归档与自动保存互斥审查（结论：零改动）

审查对象与实测依据：

- `StorageEngine.save`（`:135`）＝ `core.lockManager.withWriteLockLight(slot)`；
- `DataPruningScheduler.performPruning → pruneStorageAreas`（`:195`）＝ 同族**每槽写锁**，
  原注释即"修剪与保存互斥：保存事务全量重写 battleLogs…须持槽位写锁"；
- `DataArchiveScheduler.performArchive`（`:107`）＝ 同族每槽写锁（battleLogs/disciples 读删）；
- `StorageEngine.load`（`:262`）与 `StorageFacade.restoreFromBackupIfCorrupted`（`:290`）＝
  同一把排他锁（`SlotLockManager` 的"读/写 Light"实为同一 Mutex，非可重入，源码 KDoc 已声明）。

结论：

1. **互斥已成立**，且自动保存完全复用 `storageFacade.save` ⇒ 新月变/onStop 触发点自动落入既有锁域，
   **无需新增锁**（新增反而是违例）。`SlotLockManager` 保留（SR-7 早已判归）。
2. **无锁序反转/死锁**：修剪与归档按槽位逐个取锁（一次一把），保存只取自身槽位；跨槽位的
   `withMultiple*LocksSuspend` 按 slot 升序取、逆序放（`SlotLockManager.kt:126-149`）。
   锁**非可重入**，`withWriteLockLight` 内不得再取同槽锁——本批新增路径未嵌套取锁
   （`SaveOrchestrator` 的 `Mutex` 与槽锁无交叉：`fireLocked` 内只调保存链入口，非挂起即返回）。
3. **频率抬升后的新代价 = 争用概率**，不是正确性：修剪每 300s、归档每 600s 各跑一轮
   `slotIds = 0..6`；自动保存每 6s 持锁一次 ⇒ 二者相遇时后到者短暂等待（两侧均为短事务）。
   实测真机等待时长列为 pending-device（§7）。
4. **WAL checkpoint 不在争议面**：两个调度器只**观测** `getWalFileSize()`，全仓 grep 无
   `wal_checkpoint` 于修剪/归档路径 ⇒ 不存在"检查点与保存事务对撞"的新增风险。
5. `DataPruningScheduler.isRunning` CAS 仍只防自我重叠（与保存无关），不改动。

---

## 6. 门禁实测（本轮实跑，判绿看 XML executed 计数 + 时间戳，不看 BUILD SUCCESSFUL）

**门 1 · 桌面 ctest**（纯 Kotlin 批，零 C++ 面）：`cmake --build .` → **`ninja: no work to do.`**
⇒ 本批零 C++ 变更实证；`ctest` → **100% tests passed, 0 tests failed out of 1561**（57.51s，
llvm-mingw-20260616 + SDK cmake 3.22.1 入 PATH）＝ SR-1/2/3 基线 1561 持平。

**门 2 · 六模块组合门（第一轮，判红）**：`BUILD FAILED in 13m42s`，`GATE_EXIT=1`，287 任务，
唯一失败 = `:feature:game:detekt` **2 条 `UnusedImports`**（新测试类 `SaveLoadViewModelAutoSaveTest`
抄 SR-3 夹具时残留 `BootSequenceController` / `TapCloudSaveManager` 两行 import）
→ 独立笔 `189fc490e` 修复，零语义变化。该轮六模块 `testReleaseUnitTest` **已全部实跑**
（总数与第二轮完全一致，见下），失败点仅在风格门。

**门 2 · 六模块组合门（第二轮，判绿轮）**：`BUILD SUCCESSFUL in 28m48s`，**`GATE_EXIT=0`**，
**404 个 Task 行**，命令 = `testReleaseUnitTest --max-workers=1 --rerun-tasks
-Dgamecore.jni.path=.../libgamecorejni.so detekt compileReleaseKotlin lintRelease`
（桥 `.so` mtime 2026-09-21 21:40：本批零 C++/JNI 面 ⇒ 复用，理由同上 SR-1..SR-3 豁免口径）。
逐模块 XML 实测（**时间戳全部落在本轮 03:12–03:19 UTC 窗口，非 UP-TO-DATE**）：

| 模块 | 测试类 | 用例 | skip | fail | err | XML 时间戳（UTC） |
|---|---|---|---|---|---|---|
| `:app` | 90 | 998 | 2（既有） | 0 | 0 | 03:12:09–03:13:20 |
| `:core:data` | 76 | 768 | 15（既有） | 0 | 0 | 03:13:38–03:13:56 |
| `:core:domain` | 87 | 1743 | 0 | 0 | 0 | 03:14:04–03:14:11 |
| `:core:engine` | 330 | 3399 | 0 | 0 | 0 | 03:14:58–03:16:55 |
| `:core:ui` | 20 | 146 | 0 | 0 | 0 | 03:17:10–03:17:23 |
| `:feature:game` | 89 | 932 | 0 | 0 | 0 | 03:18:14–03:19:40 |
| **合计** | 692 | **7,986** | **17** | **0** | **0** | — |

- 基线对照：SR-3 收官轮 7,964 → **7,986（+22 = 本批新用例）**，**skip 17 与基线完全一致零新增**；
- `detekt` 六模块全绿（第二轮）、`compileReleaseKotlin` 全绿、`lintRelease` 全绿；
- **门 3 `Diff*` 对拍（IN8）**：50 类 / **273 用例 / 0 失败 / 0 跳过**（携对拍桥真加载，非 skip 冒充）。

**门 4 · 本批新增定向单测**（逐类 XML，均出自第二轮判绿轮）：

| 测试类 | 用例 | 结果 |
|---|---|---|
| `GameEngineCoreMonthSettledTest`（新，C2 时序门） | 3 | 0 fail / 0 skip（03:15:29） |
| `SaveOrchestratorTest`（新，C3 状态机门） | 5 | 0 fail / 0 skip（03:19:23） |
| `SaveLoadViewModelAutoSaveTest`（新，C4/C5/C5b 接线与红线） | 10 | 0 fail / 0 skip（03:18:56） |
| `UploadQueueTest`（Q11/Q11b/Q12 新增，12→15） | 15 | 0 fail / 0 skip（03:13:44） |
| `SaveTriggerFlagTest`（默认值改判据，5→6） | 6 | 0 fail / 0 skip（03:13:38） |
| `SaveLoadViewModelLoadTest`（手动链回归对照，仅补桩） | 38 | 0 fail / 0 skip（03:19:01） |

**门 5 · 真机**：见 §7，本环境不可自动化，逐项 pending-device，未声称达标。
**门 6 · 文档三件套**：完成报告（本文件）+ `CHANGELOG.md` 4.01.15 段新增 SR-4 小节 +
台账 SR-4 行 delivered + 方案 §4 SR-4 实施补记（方案文档按 SR 纪律保持 untracked）。
**差异如实登记**：SR-0..SR-3 的收官笔只落"报告 + 台账"两件，未进 CHANGELOG；SR-4 含玩家可见
行为变更（自动存档回归），按 §5.6 补齐 CHANGELOG 一件——属对既有口径的修正而非夹带。

---

## 7. pending-device（真机硬门，本环境不可测，逐项不虚报）

1. **月月必存代价量化**：帧率/发热/耗电/IO 抖动 + 每次自动存档时长对游戏时间流速的影响（§4 条 1–3）；
2. **后台杀场景**：`onStop` 本地事务是否完成、进程被杀后账本待传指针下次启动能否续传（Q6 幂等重传配方）；
3. **非 LEGACY 限频真实表现**：入队 6s vs TapTap 1 次/分钟，队列合并/退避是否真收敛（§4 条 4）；
4. **消息栏常驻一行的可读性**：6 秒刷新是否可接受、是否挤占事件预览行（当前实现把 notice 置于首行）；
5. **Compose 渲染未经设备验证**：本批仅数据链路单测 + 代码走查，`MessageBarCollapsed` 的新增
   `noticeLine` 首行未在真机/预览环境目视验证（无设备农场）；
6. **修剪/归档争用**：真机观测自动保存与 300s/600s 后台任务相遇时的等待时长与是否出现锁长尾（§5 条 3）。

---

## 8. 遗留与建议（交用户裁决）

- **合并窗与限频对齐**：若维持"月月必存"，建议把 `UploadQueue.Config.debounceMs` 从 2s 提到
  与 TapTap 冷却同量级（结构性无需改动，纯调参），可显著减少 RATE_LIMITED 退避轮次；
- **自动存档是否复用"仅落关键变化"的低成本路径**（现每次都是全量快照）属后续批议题；
- SR-3 的 8 项 pending-device 与本批 6 项均需真机窗口；`aiSectDisciples` 拍板项仍 open。
