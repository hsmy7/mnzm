# REPORT-SS5 · 增量落盘（真增量写）

> 批次：SS5（单存档改造串行主链第 7 批）· 分支 `feat/single-save-SS5`（基线 `a5710c79e`）
> 上位方案：`single-save-and-persistence-consolidation-plan-2026-10-01.md` §2.5.1
> 派工真源：`TASKBOOK-SS5.md`（验收①–⑥ / D-1~D-6 / 切片 SS5-a~e）
> 执行协议：`EXECUTION-PROTOCOL.md` + `DISPATCH-ledger.md` §4/§7

---

## 1. 做了什么

### 1.1 脏集链路（验收①，切片 SS5-a）

| 环节 | 文件 | 内容 |
|---|---|---|
| 契约 | `core/domain/.../state/SaveDirtySet.kt`（新增） | `SaveDirtySet`（upsertIds / rewriteTables / heavyKeys）+ `SaveDirtyTables` 表名常量 + `SaveDirtyRecorder`（馈送口）+ `SaveDirtyDeltaSource`（出口，可空=兜底）+ Noop 安全默认 |
| 跟踪器 | `core/data/.../engine/DirtySetTracker.kt`（新增） | 累积区 + **move 语义捕获** + **代序号结算** + 溢出闩 + `resolveSavePath` / `resolveSaveDecisionWithCount` |
| 馈送 | `app/.../state/GameStateStoreImpl.kt` | 构造器注入 `SaveDirtyRecorder`（默认 Noop，测试零改动）；`commitUpdateState` 锁内调 `feedSaveDirtyRecorder`：gameData 7 个 heavy 字段引用比较 + 8 个 EntityStore/battleLogs 实例身份 diff + 弟子行 `changedIdTracker.snapshotChangedIds()`（非消费快照，与 dispatchAssemble 互不干扰）+ 容量拒绝 ⇒ `recordRequiresFullWrite`；`loadFromSnapshot` / `reset` 钩子 `resetForStateReplacement` |
| 捕获 | `core/engine/.../domain/save/SaveFacadeImpl.kt` + `GameEngine.kt` | `GameStateSnapshot` 增 `dirtySet` 字段；suspend 版 `getStateSnapshot`（唯一保存快照路径，`buildSaveSnapshot` 的引擎线程闭包内）装配时**晚于全部状态读取**取脏集——引擎线程串行保证脏集与快照内容严格对应 |
| 携带 | `SaveDataTrimmer.kt` + `core/data/.../model/SaveData.kt` | 快照脏集 → `SaveData.dirtySet`（**@Transient**：`.sav`/`.bak`/云载荷字节面与本批引入前逐位一致，不持久化） |

**捕获点选择的等价性论证（验收①「消费既有 applyDirty 变更集」）**：`StateSyncService.applyDirty/applyDirtyProto` 的全部状态写入（9 集合 upsert/remove、弟子 typed 投影与列级补丁、gameData 字段补丁）都经 `stateStore.updateMirror` → `GameStateStoreImpl.update` 单一提交段落表；本批在该提交段按「基线 vs 终态」diff 回传脏 id——**镜像变更集与本地 Kotlin 写在同一事务边界被同一捕获点观测**，信息面与直连 applyDirty 信封等价，且额外覆盖所有 Kotlin 本地写路径（battleLogs/配方派生源等），单一捕获点无第二接线面。零 C++ 触碰 ✓，无新增 Kotlin→C++ 通道 ✓（`MirrorReadOnlyGuardTest` 保持零命中，随全量 JVM 门禁复核）。

### 1.2 双路径写（验收②③，切片 SS5-b）

| 项 | 内容 |
|---|---|
| 增量路径（默认） | `GameDatabase.writeIncrementalDataToDatabase`：① 变化行 upsert（只传脏 id）② 每张行级表「已落盘 id ↔ 快照 id」双向对账（删除集 = persisted−snapshot 走 `deleteByIds` 分批；快照持有而 DB 缺失的行补写——归档调度搬行/异常缺行的结构性自愈）③ **未变 heavy key 跳过（不删不写）** ④ 变动 heavy key 整 key 重编码（删前缀 + 7 编码器按 key 分发）⑤ 轻量 game_data 行 / 5 张域状态表 / 邮件 / 生产槽恒整写（小表），配方表仅 `unlockedRecipes` 引用变化时整表重写 |
| 全量路径（兜底） | 既有 `writeAllDataToDatabase` **逐行保留**，接收者从 `StorageEngine` 收敛为 `GameDatabase`（行为零变更，双路径共用面直测免引擎装配） |
| 路径判定 | `resolveSavePath`：无基线（进程首保/读档/删档/重置后，验收⑥风险项）⇒ `NO_BASELINE` 全量；脏集缺失（云档落盘等不经 `buildSaveSnapshot` 的链路）⇒ `NO_DIRTY_SET` 全量；**脏集 upsert id ⊄ 快照 id 集 ⇒ `DIRTY_OUT_OF_SNAPSHOT` 回退全量并计数**（验收⑤，不得静默）；溢出 ⇒ `DIRTY_OVERFLOW` 回退 |
| 事务编排 | `performFullTransactionSave` 收敛为 `performTransactionSave`（增量/全量同一 `withTransaction` 边界，`CacheWriteAtomicityGuardTest` 双入口锚定）；决策一次、重试复用（增量写幂等） |
| 结算/回并 | `save()` 全部出口（成功/失败/熔断/校验拒绝/取消/异常）调 `settleSaveResult`：成功只清除「捕获后代序号未前进」的条目（**飞行期再变更不丢**，闭合丢更新时间窗）并建立基线；失败整组回并累积区 |
| 更名 | `clearCacheForSlot` → `clearSaveCache`（SS4 报告 §6.3 登记消费；单档后缓存唯一键，函数名去 Slot 残留），4 调用点 + 定义随批 |
| DAO 面 | 11 个 DAO 文件新增 `getAllIds()`（仅 id 列枚举，不物化实体）+ `deleteByIds(ids)`（调用方按 `MAX_BATCH_SIZE` 分批防 SQLite 变量上限）——**纯查询方法追加，零 schema 变更、零迁移**（铁律 17 不触发） |

### 1.3 Metrics 追加（验收⑤，切片 SS5-c）

`StorageMetrics` 快照扩 4 字段（既有 10 字段语义零变更）：`incrementalSaveCount` / `fullSaveCount`（路径分布）+ `dirtyFallbackCount`（越界/溢出异常回退，独立于首保类全量）+ `lastFullSaveReason`（原因归因，对称 `lastJadeLedgerDriftOp` 模式）。三处同步：

1. `AnalyticsEvents.kt`：`PROP_STORAGE_INCREMENTAL_SAVE_COUNT` / `PROP_STORAGE_FULL_SAVE_COUNT` / `PROP_STORAGE_DIRTY_FALLBACK_COUNT` / `PROP_STORAGE_LAST_FULL_SAVE_REASON`；
2. `docs/knowledge-base.md` `#storage_metrics_report` 字典行 +4 键；
3. `AnalyticsEventsDictionaryTest` **新增属性键白名单守卫**（常量集 ↔ 知识库字典行 15 键一一对应——任务书 SS5-c 自检项，此守卫此前不存在，本批补建）；`StorageMetricsReporter` 出报同步。

### 1.4 防全删补丁降级为断言（验收⑥，切片 SS5-d）

| 补丁 | 降级后形态 |
|---|---|
| `stacksSerialized` 条件删除（WriteOps，原 ：126-144） | `clearOldEntities` 改为 `check(data.stacksSerialized)`：删档重置（SS0）后全部生产者（快照构建 / DB 构建 / `SaveDataReconciler.reconcileStacks` 重建后置 true）恒为 true，false 只能是生产者回归 ⇒ fail-fast 拒绝落盘，不静默保留残留行。保护对象（无条件整表删除）随增量默认消失 |
| `skippedHeavyKeys` 集排除（HeavyDataOps :61/:231 记录侧） | **增量路径**：`writeChangedHeavyData` 重编码前 `check(key !in skippedHeavyKeys)`——被读档跳过的 key 内存值为空，标脏重写即以空值覆盖 DB 完整数据（审计 §12-A）⇒ 断言红 = 真回归；**全量路径保留** `heavyPrefixesToClear` 排除逻辑（其删侧无条件，`heavyPrefixesToClear` 及 `HeavyDataPreservationTest` 原样保留——见 §4 偏离登记 1） |

### 1.5 对拍铁门（验收④，切片 SS5-e）

`IncrementalSaveDualPathRoundTripTest`（Robolectric + 内存 Room，真实 GameDatabase 落盘）：

- **三形状 × 双路径读回逐字段全等**：新增一批（+弟子+丹药）/ 删除一批（−丹药−材料−战报，空脏集纯对账删除）/ 修改一批（弟子改列 + heavy exploredSects 增项 + 配方整表重写）——增量臂（基线全量 → 增量写）与全量对照臂（独立库全量写）读回快照逐字段断言相等；
- 读回面 = 全部持久化字段（gameData 行/弟子/丹/材/种/战报/heavy 行/邮件/生产槽/配方/域状态表）；heavy 行比较面 = `dataKey + dataValue`（`updatedAt` 为写侧时间戳元数据，不参与读档）；
- **未变 heavy key 不删不写**：`getByPrefix` 前后行内容逐字节相等；变动 key 重编码含新内容；
- **首保/次保路径判定证据**（验收⑥风险项 4）：`首保无基线判全量_次保有基线判增量` 用例——首保（`isFullWriteRequired()=true` + 脏集空）判 `NO_BASELINE` 全量；`settleSaveResult(success, writtenViaFull=true)` 建立基线后次保判增量；metrics 断言 full=1/incremental=1/fallback=0；
- **越界回退计数断言**（验收⑤）：合成越界脏 id ⇒ 判 `DIRTY_OUT_OF_SNAPSHOT` 全量 + `dirtyFallbackCount=1` + `lastFullSaveReason` 归因。

## 2. 验证（门禁实跑数值）

| 门禁 | 结果 |
|---|---|
| `compileReleaseKotlin` | BUILD SUCCESSFUL（全工程） |
| `:core:data:testReleaseUnitTest`（`--max-workers=1`） | **699 tests, 0 failed, 15 skipped**（含新增 `DirtySetTrackerTest` 13 例 + `IncrementalSaveDualPathRoundTripTest` 7 例） |
| `:core:data :app :core:engine testReleaseUnitTest`（`--max-workers=1` + jni.path + `--rerun-tasks`） | 见 §2.1（任务书门禁复跑） |
| `detekt :core:data :core:engine :app` | 见 §2.1 |
| `node scripts/check-agent-instructions.mjs` | EXIT=0（42 篇文档 / 485 条引用全解析） |
| 生成器零漂移 | 本批未触 proto/game-data/action-catalog（无生成面变更） |

### 2.1 终树复跑数值

| 门禁 | 实跑结果 |
|---|---|
| `compileReleaseKotlin`（全工程） | BUILD SUCCESSFUL |
| `:core:data:testReleaseUnitTest` | **699 tests / 0 failed / 15 skipped** |
| `:core:engine:testReleaseUnitTest` | **3023 tests / 0 failed / 5 skipped**（含 `MirrorReadOnlyGuardTest` 零命中、`DiffBridgeSourceSyncGuardTest` worktree 重编后同源、`WallClockReflowGuardTest` 59=59） |
| `:app:testReleaseUnitTest` | **1016 tests / 0 failed / 2 skipped** |
| 跑法 | 三模块同批 `--max-workers=1 --rerun-tasks` + `-Dgamecore.jni.path=<worktree>/core/engine/build/desktop-jni/libgamecorejni.so`（worktree 重编桥，180 tasks 全执行，BUILD SUCCESSFUL 8m10s） |
| `detekt :core:data :core:engine :app` | BUILD SUCCESSFUL（新违规 7 处全实修，baseline 零新增零触碰） |
| `node scripts/check-agent-instructions.mjs` | EXIT=0（42 篇 / 485 引用全解析） |
| 生成器零漂移 | 本批未触 proto / game-data / action-catalog（无生成面变更，无重跑义务） |

### 2.2 途中抓出的在册漂移（SS3 遗留，本批代为校正）

`WallClockReflowGuardTest`（:core:engine）在首次全量门禁判红：core:data 实测 59 < 登记 66。
因果链核实：SS3 摘除 FunctionalWAL 整族（`wal/` 三文件 905+185+78 行）随删 **7 处**裸墙钟
（`git diff a4c17acc2 d1d5940d1` 实证恰 7 行），SS3 交付门禁只跑了 data+app 两模块、
未复跑 :core:engine ⇒ 漂移潜伏至本批首次三模块全量。本批按守卫自身指引
（「债务已下降，请下调登记」= 只缩不增纪律的正向）将 `REGISTERED_RAW_COUNTS`
core:data 66 → 59 并留因果注。**SS3 遗留，非本批引入；SS4 验收未复跑该模块故未抓出。**

## 3. 旧用例处置表

| 用例 | 处置 | 理由 |
|---|---|---|
| `HeavyDataPreservationTest` | **保留原样** | `heavyPrefixesToClear` / `keysNeedingBackfill` 纯函数语义未变（全量路径防线保留） |
| `CacheWriteAtomicityGuardTest`「全量保存的 DB 写包在单个 withTransaction 内」 | **改语义**（不放宽） | 锚点 `performFullTransactionSave` → `performTransactionSave`；断言从单入口扩为**双入口**（增量 + 全量都必须在事务内、事务在前） |
| `MailSnapshotWritePathGuardTest`「writeAllDataToDatabase chain...」 | **改语义**（不放宽） | 接收者锚点 `StorageEngine.*` → `GameDatabase.*`；**新增增量路径邮件整对象替换断言**（删+写两段） |
| `EquipmentProtoNumberFrozenTest` | **改语义**（不放宽） | `protoNumbers` 跳过 `@Transient` 属性——不入序列化字节面的属性无编号语义；全部序列化属性的「缺 @ProtoNumber 即报错」保持 |
| `StorageMetricsTest` | **扩** | 新增路径分布/回退计数断言 + 既有 10 字段零变更断言补全 |
| `AnalyticsEventsDictionaryTest` | **扩** | 新增属性键白名单守卫方法（事件名守卫原样） |
| 其余 core:data 681 例 | 全绿未动 | 增量/全量读档行为语义未变 |

## 4. 偏离与决策登记

1. **`skippedHeavyKeys` 全量路径保留排除逻辑**（任务书验收⑥只说「降级为断言」）：全量兜底路径的删侧无条件（全删全写定义），若不同时保留排除，读档跳过 key 的 DB 完整数据会在每次全量兜底时被空内存值覆盖——数据丢失红线高于字面收敛。降级语义承载于增量路径断言（`checkForLoadSkippedHeavyKey`）+ 对应测试；全量排除逻辑原样保留并由 `HeavyDataPreservationTest` 继续看护。**已核实**：`heavyPrefixesToClear` 消费点唯一（`clearHeavyDataByPrefix`，仅全量路径）。
2. **捕获点 = store 提交段而非 StateSyncService 直连**（D-2 要求消费 applyDirty 变更集）：等价性论证见 §1.1；若直连信封还需第二条 Kotlin 写接线面（battleLogs/配方等不经过 applyDirty），两捕获点并存的复杂度与漏检面都更差。**已核实**：applyEnvelope 全部写路径经 updateMirror→update 单段。
3. **行级表删除不走脏集馈送，走每保存 id 双向对账**（D-4「persistedIds − pulledIds」照办 + 补反向）：`pulledIds − persistedIds`（快照有而 DB 无）补写防归档搬行后丢行——归档调度（600s 节拍）把已故弟子/溢出战报搬出主表，若只做单向删除对账，增量臂与全量臂读回会在归档行上分叉（对拍铁门红）。**已核实**：`DataArchiveScheduler` 搬行行为存在。
4. **`StorageEngineSaveOps.kt` 的 `getStateSnapshotSync`（sync 快照）不接捕获**：全仓生产调用者为零（grep 证实），仅 suspend 版经 `buildSaveSnapshot` 接入——避免非保存路径消耗脏集。
5. **越界校验的表缺失语义**：脏集出现未知表名（快照 id 集合无该表）按越界回退全量（不可判定 ⇒ 兜底），与「不得静默跳过」同向。

## 5. 未完成 / 登记（跨批）

1. **（SS6）** 事件触发自动存档依赖本批增量路径的耗时特征——`SaveOrchestrator.submit` 合并窗内「关键事件返回前落盘」的成本已从全量降为增量；SS6 时序断言可按增量耗时基线设定。
2. **（SS7）** 云载荷单 blob 全量不变；云增量上传 = 技术债（方案 §八）。
3. **（SS10）** 保存耗时性能收益玩家不可见，changelog 不强制；若写并入 4.2.00 唯一条目。
4. **（SS6 后）** `StorageEngine.kt` 事务编排段与 `StorageEngineWriteOps.kt` 本批已改完——**重新冻结**给 SS6 保存触发链（派工册 §4.3）。
5. **（途中发现，已随批修正）** `docs/knowledge-base.md`「存档槽位隔离」整段过期（slot_id 复合主键 / `resetForSlot(slotId)` / `writeAllDataToDatabase` 强制 slotId——SS1 去槽、SS2 分库后全部失效），本批改写为「增量落盘」真源段。**前批（SS1/SS2）文档同步遗漏，登记供验收会知悉。**
6. **（登记，不改）** `GameEngineSaveOps.getStateSnapshotSync`（sync 快照）生产调用者为零，属既有孤儿面——本批不越界清理（协议铁律 14）。
7. **（SS3 遗留，本批代为校正）** `WallClockReflowGuardTest` 登记 66 → 59（详见 §2.2）；对拍桥不同源判红（`DiffBridgeSourceSyncGuardTest`，主树 .so 与 worktree 源 180 文件不一致——SS4 并网后 scene_uv_tables 等构建重生成噪音滞留主树所致）按 worktree 重编 SOP 处置（`build-desktop-jni.ps1`），非源码问题。
8. **（detekt 实修记录）** 本批新增违规全部实修、baseline 零新增：增量写面拆独立文件 `StorageEngineIncrementalWriteOps.kt`（同时消 LongMethod/复杂度/TooManyFunctions）、馈送面拆三段、跟踪器结算拆三函数、跟踪器并入 `StorageInfraFacade`（StorageEngine 构造参数回落 9，`:app` provider 同步收敛）。

## 6. 风险

1. **【已核实】** 飞行期丢更新窗口：快照构建（引擎线程）与脏集捕获原子；保存飞行期间的再变更经代序号保留、归下一次保存——`DirtySetTrackerTest` 覆盖。
2. **【已核实】** 脏集越界（陈旧 id）回退全量：路径判定纯函数 + 合成用例断言；生产馈送面构造上不可能产生越界（馈送 id 来自终态 diff），守卫为防御性不变量。
3. **【推测→已验证】** 全工程行为面：三模块 JVM 门禁 + detekt 终树复跑全绿；`Diff*Test` 对拍族（C++↔Kotlin 行为面）零改动——本批零 C++ 触碰、零镜像协议变更。
4. **【已核实】** `.sav`/`.bak`/云载荷字节面：`SaveData.dirtySet` 为 `@Transient`，序列化输出与本批引入前逐位一致；`EquipmentProtoNumberFrozenTest`（proto 编号冻结守卫）绿。
