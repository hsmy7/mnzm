# 派发件 · EQ-B0 存档编号规划与冻结守卫（装备系统重构首批）
> ⚠️ **本派发件已过时（2026-09-29 02:48）**：装配后设计文档又经 0a54a234d 改版（六部位移除饰品、腿部回归第 6 位；武器/腿部主词条池新增待确认项）——**派发时必须按最新方案文档重新生成本文件**（尤其 §1 补充要点与 E1 冻结表引用），不得直接使用。

> 派发真源：`docs/design/equipment-batches/IMPLEMENTATION-BATCHES.md`（§1 铁律 / §2 门禁 / §4.1 B0 / §8 开工检查清单）+ `docs/design/equipment-set-system-refactor-plan.md`（权威方案，拍板已定稿至 55b1dd01d）。

## 0. 工作区与纪律

- 唯一工作区：`C:\Mnzm\XianxiaSectNative-equipment`（git worktree），分支 `feat/equipment-set`（基点 642754817，已含并网后实时结算线与装备两文档定稿）。开工第一件事：`git branch --show-current`（必须 = feat/equipment-set）+ `git status`（必须干净）。**禁止在主树 `C:\Mnzm\XianxiaSectNative` 改任何文件。**
- 本会话只实施 **EQ-B0 一批**（~5 文件，小批），完成即收官提交；不开下一批。
- 实施前先读：方案 `docs/design/equipment-set-system-refactor-plan.md`（§四编号表 / §15）+ `docs/design/equipment-batches/IMPLEMENTATION-BATCHES.md`（§1 铁律 E1–E13 / §4.1 / §8）+ 看护台账 `C:\Mnzm\XianxiaSectNative\docs\realtime-watch\DISPATCH-LEDGER.md`（只读；异议写其 §9 留言区）。
- 报告：`docs/design/equipment-batches/reports/report-B0.md`（新建，含门禁实测原数字——禁「应该通过」措辞、禁占位符残留），随收官笔入库。
- 收官：单笔提交，格式 `feat(equip): B0 存档编号规划与冻结守卫——<要点>`；**版本号不自增**。
- 构建副产物（`atlas-rgba-manifest.json`/`scene_uv_tables.h` 等 codegen 幽灵 diff）提交前 `git checkout --` 还原。
- 台账状态 `accepted` 由看护亲验后设置，实施会话不得自设。
- 🔴 **开工前置（E12，本批已预检）**：实时结算线已并网删支、执法堂/存档下线流已并网、装备设计文档已定稿入库——当前无他线在途；若开工时实测发现新占用水命即停手报告。
- 🔴 **Room 版本规则**：本批 **Room schema 零变更、无迁移**。后续批（B1 起）取号 = 合入时刻 `GameDatabaseConfig.DATABASE_VERSION`（现值 **61**）+1 顺延（v62/v63/v64），**禁预占**（方案表 v59→v60 系计划映射，以实际为准）。

## 1. 批次任务（IMPLEMENTATION-BATCHES §4.1 原文）

| 项 | 内容 |
|---|---|
| **目标** | 把全部编号分配**一次冻结**，后续批只能使用，不得临时新增（E1）。本批**不改任何业务语义**。 |
| **写入面** | ① `core/model/DiscipleSerializer.kt`（新增 `headId(112)…feetId(115)/innateDamageType(116)` 声明与搬运；**复用** `weaponId(17)`→武器部位 / `accessoryId(20)`→饰品部位；`18/19/24..27/47/69–72/98/99` 就地注释 `reserved`）② `core/data/.../model/SaveData.kt`（`equipmentStacks(53)` 注释 `reserved`）③ **新增** `EquipmentProtoNumberFrozenTest.kt` ④ **新增** 报告目录骨架 |
| **影响面** | 存储：新增字段声明但**不写入/不读取**（旧档读到空值即默认）⇒ 二进制向后兼容；UI/C++/经济：无影响。 |
| **验收判据** | ① `EquipmentProtoNumberFrozenTest` 绿（断言 `DiscipleSurrogate` 装备段/新增段/`reserved` 集合/`SaveData` 属性→编号映射与冻结表逐条一致，错误消息含操作指引）；② `compileReleaseKotlin` 绿；③ `testReleaseUnitTest --max-workers=1` 全绿（无行为变化）；④ Room schema **零变更**。 |
| **回滚** | 直接 revert（无数据副作用）。 |
| **规模** | 小（~5 文件，+150/-10 行）。 |

补充要点：`EquipmentSlot` 六部位口径以**方案最新拍板为准**（显示序 头/身/手/脚/**武**/饰，武器=原腿部池继承，见 55b1dd01d）；`EquipmentInstance` 的 `reserved` 注释本批**不落地**（B3 落）；编号冻结表以方案 §四为准，铁律 E1 行内清单为快照。

## 2. 门禁命令（收官前全跑；报告记实测原数字）

在 **equipment worktree** 的 `android/` 下执行；测试一律 `--max-workers=1`：

1. `./gradlew.bat compileReleaseKotlin`
2. `./gradlew.bat testReleaseUnitTest --max-workers=1 -Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative-equipment/android/core/engine/build/desktop-jni/libgamecorejni.so`（六模块全量**必须含 feature:game**；**必须带该参数**——IN8 出厂门防 Diff*Test 静默 skip）
3. engine Diff 门已含在第 2 条（本批未触 C++，复用拷入的 .so+指纹即可，勿重跑 build-desktop-jni.ps1）
4. `./gradlew.bat lintRelease detekt`
5. worktree 根：`node scripts/check-jni-count.mjs` + `node scripts/check-agent-instructions.mjs`（依赖已拷入仓库根 node_modules；本批不改规范文档，agent-instructions 应绿）
6. **codegen 漂移门（本批不改静态数据，可作基线自证）**：`node scripts/gen-templates.mjs ; git diff --stat`（重跑后不得出现删 `operator==`/`*TemplatesMutable()` 类差异——G0/D9 判据预演）

## 3. 环境教训（必读）

- ctest/桌面本批不涉及（未触 C++）。feature:game 全量 Robolectric 多 daemon 并存会 OOM：跑前清别线 java 进程。
- lintRelease/构建触碰 `atlas-rgba-manifest.json` 属构建副作用，checkout 还原勿混入提交。
- 诚实纪律：门禁失败须归因入报告。

## 4. 并网基线附录（看护填——实时结算线刚并网后的主基线）

1. 合并笔 `faaf1aa06`（实时线 B1–B10 并网）已在分支基点内；**并网后主树门禁**：ctest **1482/1482**、六模块 JVM **7541/0/18**（app 1028/2、domain 1585、data 831/15、engine 2949/1、ui 155、feature:game 993）、lint/detekt 六模块绿、jni-count **87/87**（基线已降：88→87，nativeLoopSetSpeed 随速度维度删除）。worktree 拷入的 .so+指纹即该次重建产物（02:09）。
2. **速度维度已整维删除**（单一时速）：`GameTimeClock` 无 speed/setSpeed/speedFlow，`MS_PER_PHASE=2000L` 单一常量；C++ PhaseClock/SettlementEngine 无速度状态机（双端守卫 SpeedDimensionRemovedGuardTest / speed_dimension_removed_guard_test.cpp 在位）——B0 编号工作与其无交集，但全仓 grep 时勿把守卫测试自身命中误判为残留。
3. **执法堂/监牢/赏善罚恶已整链删除**（含 `LawEnforcementConfig`、`kRewardPunishMonthly` 等）：E1 的 reserved 编号（18/19/24..27/47 等）与该删除无耦合，冻结表以方案 §四为准。
4. **实时结算已并网**：权威游戏毫秒轴 `elapsedGameMs` + 双轨 L0–L4（INV-1/2/3）+ 离线注入 `injectOfflineGameMs`；`GameTimeClock.phaseProgressFlow` 为旬内进度真源。B1 属性重构的战斗对拍基线（DiffBattle*）将基于此新语义（B1 批重录，B0 无涉）。
5. **Room 现处 v61**（实时线 B9 死值退役删列）——`DATABASE_VERSION=61`，B1 起按 §0 规则顺延取号。
6. 途中发现移交（实时线遗留，均与本批无涉、**勿顺手处置**）：`processAutoAlchemy` 生产零调用；bench `overBudgetCount==0` 断言噪声脆弱；`kMsPerPhase`（settlement.h 守卫钉死）与 `kGameMsPerPhase`（time_units 双端锚）同值并存待归一。
