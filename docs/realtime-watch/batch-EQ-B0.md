# 派发件 · EQ-B0 存档编号规划与冻结守卫（装备系统重构首批）· v2 定稿版

> 派发真源：`docs/design/equipment-batches/IMPLEMENTATION-BATCHES.md`（§1 铁律 E1–E13 / §2 门禁 / §4.1 B0 / §8 开工检查清单）+ `docs/design/equipment-set-system-refactor-plan.md` **HEAD 版**（`94b910951` 及之后——§3.1/§3.4.2/§3.7 为六部位与池表唯一真源；设计会话已收官定稿，台账 §9 有定稿通知）。
> 本文件 v2（2026-09-29 03:0x 看护重生成），取代已过时的 v1。

## 0. 工作区与纪律

- 唯一工作区：`C:\Mnzm\XianxiaSectNative-equipment`（git worktree），分支 `feat/equipment-set`（基点 642754817；**开工先 `git merge main` 或 rebase 快进到最新 main**——设计定稿与规范精简笔在基点之后）。开工第一件事：`git branch --show-current`（必须 = feat/equipment-set）+ `git status`（必须干净）。**禁止在主树 `C:\Mnzm\XianxiaSectNative` 改任何文件。**
- 本会话只实施 **EQ-B0 一批**（~5 文件，小批），完成即收官提交；不开下一批。
- 实施前先读：方案 HEAD（§四编号表实况 / §3.1 / §3.4.2 / §15）+ IMPLEMENTATION-BATCHES（§1 E1–E13 / §4.1 / §8）+ 看护台账 `C:\Mnzm\XianxiaSectNative\docs\realtime-watch\DISPATCH-LEDGER.md`（只读；异议写其 §9 留言区）。
- 报告：`docs/design/equipment-batches/reports/report-B0.md`（新建，含门禁实测原数字——禁「应该通过」措辞、禁占位符残留），随收官笔入库。
- 收官：单笔提交，格式 `feat(equip): B0 存档编号规划与冻结守卫——<要点>`；**版本号不自增**。
- 构建副产物（`atlas-rgba-manifest.json`/`scene_uv_tables.h` 等 codegen 幽灵 diff）提交前 `git checkout --` 还原。
- 台账状态 `accepted` 由看护亲验后设置，实施会话不得自设。
- 🔴 **Room 版本规则**：本批 **Room schema 零变更、无迁移**。实测现值 `DATABASE_VERSION=61` ⇒ 设计定稿计划 **B1=v62 / B2=v63 / B3=v64**；每批开工以 `GameDatabaseConfig.DATABASE_VERSION` 实际值复核取号，**禁预占**（方案旧表 v59→v60 系计划映射，作废）。

## 1. 批次任务（IMPLEMENTATION-BATCHES §4.1 + 方案 HEAD 定稿编号表）

| 项 | 内容 |
|---|---|
| **目标** | 把全部编号分配**一次冻结**，后续批只能使用，不得临时新增（E1）。本批**不改任何业务语义**。 |
| **写入面** | ① `core/model/DiscipleSerializer.kt`（**新增** `headId(112)/bodyId(113)/handsId(114)/feetId(115)/legsId(116)` + `innateDamageType(117)` 声明与搬运；**复用 `weaponId(17)`→武器部位（仅此一个）**；**退役就地注释 `reserved`**：`accessoryId(20)/armorId(18)/bootsId(19)`、`weaponNurture(24..27)`、`pillNurtureSpeedBonus(47)`、`equipmentNurturingCompletion*`(98/99)）② `core/data/.../model/SaveData.kt`（`equipmentStacks(53)` 注释 `reserved`）③ **新增** `EquipmentProtoNumberFrozenTest.kt` ④ **新增** 报告目录骨架 |
| **影响面** | 存储：新增字段声明但**不写入/不读取**（旧档读到空值即默认）⇒ 二进制向后兼容；UI/C++/经济：无影响。 |
| **验收判据** | ① `EquipmentProtoNumberFrozenTest` 绿（断言 `DiscipleSurrogate` 装备段/新增段/`reserved` 集合/`SaveData` 属性→编号映射与**方案 §四定稿表**逐条一致，错误消息含操作指引；已 reserved 存量 `7,8,11–16,22,29,50,76,88,93,102,104,105,110` 一并锁定）；② `compileReleaseKotlin` 绿；③ `testReleaseUnitTest --max-workers=1` 全绿（无行为变化）；④ Room schema **零变更**。 |
| **回滚** | 直接 revert（无数据副作用）。 |
| **规模** | 小（~5 文件，+150/-10 行）。 |

补充要点：六部位定稿 = **头/身/手/脚/武器/腿部**（枚举 `HEAD(10)/BODY(11)/HANDS(12)/FEET(13)/WEAPON(14)/LEGS(15)`，声明序=显示序，**无饰品**）；主词条池定稿（头 血/防 1.00 · 身 防/攻/暴率/暴伤 1.00 · 手 攻/暴率/暴伤 1.15 · 脚 五项 0.95 · 武 攻/暴率/暴伤 1.15 · 腿 五项 0.95）——**编号与池表唯一真源 = 方案 HEAD**（`94b910951` 及之后），本表仅为快照，冲突时以方案为准；`EquipmentInstance` 的 `reserved` 注释本批**不落地**（B3 落）。

## 2. 门禁命令（收官前全跑；报告记实测原数字）

在 **equipment worktree** 的 `android/` 下执行；测试一律 `--max-workers=1`：

1. `./gradlew.bat compileReleaseKotlin`
2. `./gradlew.bat testReleaseUnitTest --max-workers=1 -Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative-equipment/android/core/engine/build/desktop-jni/libgamecorejni.so`（六模块全量**必须含 feature:game**；**必须带该参数**——IN8 出厂门防 Diff*Test 静默 skip）
3. engine Diff 门已含在第 2 条（本批未触 C++，复用拷入的 .so+指纹即可，勿重跑 build-desktop-jni.ps1）
4. `./gradlew.bat lintRelease detekt`
5. worktree 根：`node scripts/check-jni-count.mjs` + `node scripts/check-agent-instructions.mjs`（依赖在仓库根 node_modules 已拷入；本批不改规范文档，agent-instructions 应绿——注意根 AGENTS.md 已被设计会话精简至 30742/32768）
6. **codegen 漂移门（本批不改静态数据，作基线自证）**：`node scripts/gen-templates.mjs ; git diff --stat`（重跑后不得出现删 `operator==`/`*TemplatesMutable()` 类差异——G0/D9 判据预演）

## 3. 环境教训（必读）

- ctest/桌面本批不涉及（未触 C++）。feature:game 全量 Robolectric 多 daemon 并存会 OOM：跑前清别线 java 进程。
- lintRelease/构建触碰 `atlas-rgba-manifest.json` 属构建副作用，checkout 还原勿混入提交。
- 诚实纪律：门禁失败须归因入报告。

## 4. 并网基线附录（看护填——实时结算线刚并网后的主基线）

1. 合并笔 `faaf1aa06`（实时线 B1–B10 并网）已在分支基点内；**并网后主树门禁**：ctest **1482/1482**、六模块 JVM **7541/0/18**（app 1028/2、domain 1585、data 831/15、engine 2949/1、ui 155、feature:game 993）、lint/detekt 六模块绿、jni-count **87/87**（基线已降：88→87，nativeLoopSetSpeed 随速度维度删除）。worktree 拷入的 .so+指纹即该次重建产物（02:09）。
2. **速度维度已整维删除**（单一时速）：`GameTimeClock` 无 speed/setSpeed/speedFlow，`MS_PER_PHASE=2000L` 单一常量；C++ PhaseClock/SettlementEngine 无速度状态机（双端守卫 SpeedDimensionRemovedGuardTest / speed_dimension_removed_guard_test.cpp 在位）——全仓 grep 时勿把守卫测试自身命中误判为残留。
3. **执法堂/监牢/赏善罚恶已整链删除**（含 `LawEnforcementConfig`、`kRewardPunishMonthly` 等）：与本批 reserved 编号无耦合，冻结表以方案 HEAD §四为准。
4. **实时结算已并网**：权威游戏毫秒轴 `elapsedGameMs` + 双轨 L0–L4（INV-1/2/3）+ 离线注入 `injectOfflineGameMs`；`GameTimeClock.phaseProgressFlow` 为旬内进度真源。B1 属性重构的战斗对拍基线（DiffBattle*）将基于此新语义（B1 批重录，B0 无涉）。
5. **Room 现处 v61**（实时线 B9 死值退役删列）——取号规则见 §0。
6. 途中发现移交（实时线遗留，均与本批无涉、**勿顺手处置**）：`processAutoAlchemy` 生产零调用；bench `overBudgetCount==0` 断言噪声脆弱；`kMsPerPhase`（settlement.h 守卫钉死）与 `kGameMsPerPhase`（time_units 双端锚）同值并存待归一。
7. **设计会话已收官**（台账 §9 定稿通知 `47448deb2`；后续两笔 `6946a3c52`/`b4feb76c3` 为 AGENTS 规范精简，与本批无涉）：六部位/词条池/编号表三定稿，方案 HEAD 为唯一真源。
