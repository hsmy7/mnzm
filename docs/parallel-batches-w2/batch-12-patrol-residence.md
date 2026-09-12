# Batch-12：巡逻 / 住所分配族下沉 C++

| 项 | 内容 |
|---|---|
| 批次 | 12 ｜ 并行组 A（与 11/13 文件零交集） |
| 模块 | C++（新 `system/patrol_tx.h`）+ Kotlin（`GameEngineAtomicAssign.kt` / `GameEnginePatrolOps.kt`） |
| 性质 | WS-2 规模下沉：六入口（住所 2 + 巡逻 4），零 RNG 纯事务 |
| 来源 | [ui-read-surface](../ui-read-surface.md) §4.1「巡逻/探索 —— assign/remove/swap/autoAssignPatrolAtomic」 |
| 预分配 | handover **§2.43**；**ActionId 段 1550–1569** |

## 1. 范围

`GameEngineAtomicAssign.kt` 的六个 UI 操作面入口（**全部**，含住所——同文件同族，一并收口避免二批撞车）：

| # | 入口 | 语义要点 |
|---|---|---|
| 1 | `assignToResidenceAtomic(...)` | 住所分配（`includeResidence` 语义的另一侧） |
| 2 | `removeFromResidenceAtomic(...)` | 住所移除 |
| 3 | `assignPatrolAtomic(discipleId, globalIndex)` | 释放原 occupant（仅清巡逻槽）→ 清新弟子**其它**槽位 → 写新槽位；**事务外** gate.release/confirmAssign + Room 生产槽清理 + 状态同步 |
| 4 | `removePatrolAtomic(globalIndex)` | 空槽无操作；清槽 → 事务外 release + 状态同步 |
| 5 | `swapPatrolAtomic(from, to)` | 同索引无操作；双方清槽 → 互换 occupant（含姓名/境界/立绘） |
| 6 | `autoAssignPatrolAtomic(assignments)` | 前置校验（重复槽索引 / 同一弟子多槽）→ 锁内校验（越界 / 弟子存在且存活）→ 逐槽处理 → 收集 pendingReleases/pendingConfirms → 事务外批量执行 |

**Out-of-scope（勿扩范围）**：
- **Gate 注册表**（`assignmentGate.release/confirmAssign`）、**Room 生产槽 Repository 清理**（`clearDiscipleFromProductionRepository`）、**弟子状态同步**（`syncSingleDiscipleStatus` / `syncAllDiscipleStatuses`）—— 三类均为 Kotlin 侧残差，**保留 Kotlin 在事务外执行**（batch-06 拆除残差同口径）。
- `GameEnginePatrolOps` 的 `updatePatrolSlots` / `updatePatrolConfig(s)` / `updateSpiritMineSlots` / `validateAndFixSpiritMineData` / `updateYearlySalary` —— **整体镜像覆写类 API**（UI 设置面），**本批只审计不实施**，结论写进 §2.43；若确认为 UI 活写者则登记 `batch-23`（避免本批膨胀）。
- 巡逻战斗/奖励结算（`PatrolBattleSystem`，月结/旬结域）—— 不动。

## 2. 写者审计（第 1 步）

产出「入口 → 判定序 → 触碰字段 → RNG → 残差归属」表进 handover §2.43。已知事实（复核并补全）：

- **判定序**（`require` 抛出 → 被 `DomainResult.catching` 捕获成 Failure）：巡逻 3 入口均为
  ① 弟子存在（`id in discipleTables.ids`）② 弟子存活（`isAlive != 0`）③ 槽位越界；`autoAssign` 额外有**事务外**的重复槽/同弟子多槽前置校验。
- **触碰字段**：`gameData.patrolSlots`（写入）；`clearAllSlotsDataOnly` 波及 **12 类槽位集合**
  （spiritMineSlots / librarySlots / elderSlots / residenceSlots(仅 includeResidence) / activeBloodRefinements /
  patrolSlots / warehouseGarrisons / battleTeams / worldMapSects.garrisonSlots / productionSlots /
  caveExplorationTeams / activeMissions），其中 `includeResidence = false`（工作分配保留住所）。
- **展示字段**：`discipleName` / `discipleRealm` / `portraitRes` 来自 `discipleTables.assemble(id)`。
  **两条落地路线**：① C++ 从 `DiscipleStore` 直读 + `realmName` 派生（`secret_realm_session.h:discipleRealmName` 已有等价实现）；
  ② Kotlin 随请求传入（`disciple_tx.h` 的 `DISCIPLE_TX_ASSIGN_SLOT` 已用此先例）。**推荐 ①**（少一次跨语言字段搬运，但须复刻 `Disciple.realmName` 的 `realm==0` 特例）。
- **RNG**：预期**全链零抽取**——须以签名级 + GTest 全分区快照差分双重证据落实。

## 3. 地基（已就绪）

- **`system/slot_cleanup.h`**：`SlotCleanupInput`（12 类槽位入参）+ `clearAllSlotsDataOnly(in, discipleId, includeResidence)` + `toMissionLiteList` / `mergeMissionLiteList`（`activeMissions` 全量 ↔ Lite 互转）——**这正是 `DiscipleSlotCleanup.clearAllSlotsDataOnly` 的 C++ 等价物**，本批直接复用（勿重写）。
- **`patrolSlots` 已在镜像协议**（`models.h` `gameData.patrolSlots`，字段 index/discipleId/discipleName/discipleRealm/portraitRes/buildingInstanceId）。
- **先例**：`disciple_tx.h`（batch-08）的 `assignSlotTransaction`/`unassignSlotTransaction` 就是"clearAllSlots + 槽位覆写 + 回执 occupant"的同族形态，**照抄其信封与回执设计**。

## 4. 实施步骤

1. **写者审计**（§2 表）。
2. **C++ 事务**（`gamecore/include/gamecore/system/patrol_tx.h`，纯头）：
   - `assignPatrolTx(state, discipleId, globalIndex)` → 信封含 `releasedOccupantId`
   - `removePatrolTx(state, globalIndex)` → 信封含 `removedDiscipleId`
   - `swapPatrolTx(state, from, to)` → 信封含 `fromDiscipleId` / `toDiscipleId`
   - `autoAssignPatrolTx(state, assignments)` → 信封含 `releasedIds[]` / `confirmedIds[]`（供 Kotlin 事务外 gate 操作）
   - `assignToResidenceTx` / `removeFromResidenceTx`（住所两入口，`includeResidence` 语义按各自 Kotlin 实现逐字对齐）
   - **全部失败零写入**；零 RNG 论证进头注释。
3. **协议**：段 1550–1569（`gen-action-ids.mjs` 追加 → 再生成）→ `execute_dispatch.cpp` 独立 `handlePatrolTx` + 中央一行。
4. **Kotlin 接线**：`GameEngineAtomicAssign.kt` 六入口内部改为「native 臂（AUTHORITATIVE 门控 → `tryExecuteNative`）→ 成功则仅执行事务外残差（gate / Room / 状态同步）/ 失败或降级走原 Kotlin 事务体」。
   **建议**：把 native 臂抽到**同包新文件** `GameEnginePatrolNativeOps.kt`（batch-08 的 `GameEngineManualOps`/`GameEngineDiscipleSlotOps` 同款落位），保持 `GameEngineAtomicAssign.kt` 规模稳定。
5. **测试**：见 §7。
6. **文档**：handover §2.43 + §3 行 + §4.1 巡逻行勾销 + 双更新日志。

## 5. 文件所有权与冲突面（含一条 **架构守卫** 硬约束）

- **本批独占**：`GameEngineAtomicAssign.kt`、新 `GameEnginePatrolNativeOps.kt`、新 `patrol_tx.h` / `patrol_tx_test.cpp`。
- **共享面**：按 [README](README.md) §3.1 原子变更集处理。
- 🔴 **架构守卫必读**：`SlotCategoryCoverageTest.kt` 内含**按文件路径的入口清单**，第 150 行明确列有
  `"com/xianxia/sect/core/engine/GameEngineAtomicAssign.kt", // 巡逻 3 入口`。
  本批新增入口文件（`GameEnginePatrolNativeOps.kt`）或改变入口落位后，**必须同步更新该守卫的清单与计数**，
  否则引擎全量套件会红（§2.28 曾因守卫源路径清单未随拆分同步而首轮 2 失败）。
  同族守卫：`CheckpointCallSiteGuardTest` / `InventoryAddPathGuardTest` / `CurrentAlphaDeterminismGuardTest` —— 逐一核对是否按路径/文件名扫描本批文件。
- **禁改**：`models.h`、`GameStateStoreImpl.kt`、`StateSyncService.kt`、`GameViewModel.kt`（含 `PatrolTowerViewModel` 之外的 ViewModel）、`GameCoreBridge.*`。
  `PatrolTowerViewModel.kt` 属 feature:game，**本批不改**（门面/Ops 层接线即可）。

## 6. RNG 与确定性要求

- **零抽取**预期。必须给：① 头文件签名级论证（API 不接受 RngManager/种子）；② GTest「双运行全状态 JSON 逐位一致」；③ 分发面「全分区 RNG 快照差分」。
- **顺序敏感点**：`autoAssign` 的 `assignments` 处理序、`pendingReleases` 的 `distinct()` 语义、`swap` 的两侧写入序 —— 逐位保持 Kotlin 原序（GTest 用固定 assignments 列表锁定）。

## 7. 验收

| 项 | 标准 |
|---|---|
| 写者审计 | 六入口判定序/字段/RNG/残差归附表进 §2.43；`PatrolOps` 覆写族结论同批登记 |
| C++ 黄金用例 | `patrol_tx_test.cpp`：住所 2 入口 happy + 校验失败臂零写入；巡逻 assign（释放原 occupant / 清新弟子其它槽位 / 建筑 id 保留）；remove（空槽无操作）；swap（同索引无操作 / 一方为空 / 双方互换含展示字段）；autoAssign（重复槽索引拒绝 / 同弟子多槽拒绝 / 越界拒绝 / 弟子不存在拒绝 / 弟子已死拒绝 / 清空槽语义 / 回执 releasedIds+confirmedIds）；**零 RNG 全分区快照差分**；**双运行逐位一致** |
| Kotlin 门控 | 新建 `GameEnginePatrolNativeTxGateTest`：六入口 flag OFF / 桥未加载双模式降级 null + 回退臂语义不变（**既有 `GameEngineAtomicAssignTest` / `GameEngineDualSlotGuardTest` 必须零改动通过**） |
| 守卫 | `SlotCategoryCoverageTest` 等路径型守卫清单已同步且全绿 |
| 门禁 | [README](README.md) §6 ①–⑦ 全绿 |
| 文档 | §2.43 + §3 行 + §4.1 勾销 + 双更新日志 |

## 8. 本批触碰文件声明

- **新**：`system/patrol_tx.h`、`test/patrol_tx_test.cpp`、`GameEnginePatrolNativeOps.kt`、`GameEnginePatrolNativeTxGateTest.kt`。
- **改**：`GameEngineAtomicAssign.kt`、`gen-action-ids.mjs` + 两生成物、`execute_dispatch.cpp`、`test/CMakeLists.txt`、
  `SlotCategoryCoverageTest.kt`（若清单需同步）、handover、`CHANGELOG.md`、`changelog_entries.json`。
- **不触碰**：`models.h`、`json_codec.*`、`GameStateStoreImpl.kt`、`StateSyncService.kt`、`GameCoreBridge.*`、`PatrolTowerViewModel.kt`。

## 9. 风险与回退

| 风险 | 处置 |
|---|---|
| `clearAllSlotsDataOnly` 的 12 类槽位在 C++ 侧漏一类 | 逐类对照 Kotlin `DiscipleSlotCleanup.clearAllSlotsDataOnly` 与 C++ `slot_cleanup.h`，写一条**穷尽性断言用例**（构造每类槽位都含该弟子 → 清理后逐类断言为空） |
| `activeMissions` 全量/Lite 互转丢字段 | 复用既有 `toMissionLiteList`/`mergeMissionLiteList`，不要自己写转换；补一条"清理后非目标弟子的任务字段逐字段不变"用例 |
| 守卫清单漏同步 → 引擎套件红 | 开批第 1 步先跑一次引擎全量建立基线，改完再跑，红则先修守卫 |
| native 成功后重复执行 Kotlin 事务 | 接线时明确：**native 成功 → 只跑事务外残差**；GTest + Kotlin 门控测试双向覆盖 |
