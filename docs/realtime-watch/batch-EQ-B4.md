# 派发件 · EQ-B4 数值对齐与验收

> 派发真源：`docs/design/equipment-batches/IMPLEMENTATION-BATCHES.md`（§2 门禁 / §4.5 B4 / §8）+ `docs/design/equipment-set-system-refactor-plan.md` HEAD 版（§13 数值 / S9/S16/S17/S18）。

## 0. 工作区与纪律

- 唯一工作区：`C:\Mnzm\XianxiaSectNative-equipment`（git worktree），分支 `feat/equipment-set`（HEAD = EQ-B3 收官笔之后，开工先 `git status` 核干净）。**禁止在主树改任何文件。**
- 本会话只实施 **EQ-B4 一批**，完成即收官提交；不开下一批。
- 实施前先读：方案 HEAD（§13 数值口径 / S9/S16/S17/S18 / §3.4.2 池权重）+ IMPLEMENTATION-BATCHES（§4.5 / §8）+ EQ-B3 报告（数值事实）+ 看护台账（只读；异议写其 §9）。
- 报告：`docs/design/equipment-batches/reports/report-B4.md`（门禁实测原数字+对比表；禁「应该通过」/占位符），随收官笔入库。
- 收官：单笔提交，格式 `test(equip): B4 数值对齐与验收——<要点>`；**版本号不自增**；**双 changelog 归 EQ-B5**（既定分工，本批不动）。
- 构建副产物提交前 `git checkout --` 还原。`accepted` 由看护亲验后设置。
- **调权重时已生成实例的既有词条不重 roll**（避免玩家资产漂移），只在报告登记差异。
- 🔴 **E12 开工前置**：`git status` 干净 + 无他线在途——命中即停手报告。

## 1. 批次任务（IMPLEMENTATION-BATCHES §4.5 原文）

| 项 | 内容 |
|---|---|
| **目标** | 用可测判据把 B1/B3 的数值落到「装备占比 40%±5% / 一套满级 ≈ 1 个月产出 / 池权重即概率」三条拍板口径上，并交付速度/灵力拆分对比与期望获取成本量化。 |
| **前置** | EQ-B3 完成（两侧数值已存在 ✓）。 |
| **写入面** | ① `EquipmentPowerParityTest`（**分维度**断言：装备贡献 ∈ 总战力 [35%,45%]，含 2/4/6 件套；速度/灵力单列输出）；② `EquipmentEconomyCalibrationTest`（满级消耗 ÷ 月产出 ∈ [0.75,1.25]）；③ `EquipmentRarityGateTest`；④ `EquipmentStatHotPathBenchmark`（不劣化 >10%）；⑤ 数值表微调：`scripts/data/equipment_db_sample.json`（主词条基数 × `k`）；⑥ **期望获取成本量化报告**（「理想件期望掉落数 / 凑齐一套期望次数」，I9 触发基线，非门禁）；⑦ 速度/灵力塌陷对比报告（决定是否启用备选） |
| **影响面** | 仅数值与测试；静态数据改动需重跑 codegen + `game-data.json`。 |
| **兼容性** | 数值调整**不改存档结构**；调权重时已生成实例的既有词条**不重 roll**，只在报告登记差异。 |
| **验收判据** | ① **S9/S16/S17/S18** 达标；② 分维度对比表产出并给出「是否需启用速度/灵力补偿」的结论；③ 期望成本表产出并登记进 I9；④ 门禁全绿；⑤ 数值报告归档 `reports/report-B4.md`。 |
| **旧用例处置** | 改断言：以「总战力持平」为判据的用例改为分维度。 |
| **回滚** | 数值 revert + 重跑 codegen（无存档影响）。 |
| **规模** | 中（~20 文件，多为测试与数据）。 |

## 2. 门禁命令（收官前全跑；报告记实测原数字）

worktree `android/` 下执行；测试一律 `--max-workers=1`：

1. `./gradlew.bat compileReleaseKotlin`
2. `./gradlew.bat testReleaseUnitTest --max-workers=1 -Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative-equipment/android/core/engine/build/desktop-jni/libgamecorejni.so`（六模块全量含 feature:game）
3. 若触 C++（预期不触）：`pwsh -NoProfile -File scripts/build-desktop-jni.ps1`（worktree 内）→ engine Diff 门
4. 桌面 ctest 全量（llvm-mingw PATH；构建目录 `app/src/main/cpp/gamecore/build`）——**B3 前基线 1521**
5. `./gradlew.bat lintRelease detekt`
6. worktree 根：`node scripts/check-jni-count.mjs`（**87/87**）+ `node scripts/check-agent-instructions.mjs`
7. **codegen 门（G0 严格判据已在位）**：改 `equipment_db_sample.json` 后重跑 `gen-templates.mjs` + `gen-game-data.mjs`，重跑 `git diff` 仅允许本批有意变更的文件（**零意外差异 = D9/D10 收口后常态**）；`gen-game-data.mjs --check` 通过

## 3. 环境教训（必读）

- feature:game 全量 Robolectric 多 daemon 会 OOM：跑前清别线 java。
- ctest 缺 llvm-mingw PATH = 假红。本批预期不触 C++（.so 复用 EQ-B3 产物 07:26）。
- `atlas-rgba-manifest.json` 等构建副作用 checkout 还原勿混入提交。
- 5 小时用量上限触顶会暂停——重置后续跑，现场勿动勿删。
- 诚实纪律：门禁失败须归因入报告。

## 4. 前批交付事实附录（EQ-B3，看护填）

1. EQ-B3 收官笔 `45bf909cd`（**410 文件 +44069/−36227**）；**看护已亲验 accepted**（G0 零差异/ctest 1521 亲跑）；worktree 树净，HEAD 即该笔。
2. **门禁基线**：六模块 JVM **7640/0/0 22skip**（domain 1600、data 881/15、engine 2985/5、ui/app/fg 全绿）；ctest **1521/1521**（含 equipment_tx/equip_affix/equip_main_stat/equip_set_bonus 四新套件）；jni-count **87/87**；detekt 零违规（57 项 B3 新面违规全清）；agent-instructions 全绿。
3. **G0 已恢复严格判据**（EQ-B3 A 面收口 D9/D10）：gen-templates 重跑零差异为本批常态；`gen-game-data.mjs --check` 通过（sha256 7c1e5377…）。
4. **S19/S20 已锁**（EQ-B1）：类型桶默认 0 逐位一致、战力线性恒等 k=1——B4 分维度断言在此基线上叠加。
5. **16 处主库真根因修复在 EQ-B3 落地**（报告 §3）：EquipStatResolver 复合赋值翻倍修复后装备加成数值为新口径——B4 的 40% 占比校准基于修复后数值（勿按旧口径校准）。
6. **装备实例轨已立**：`equipment_instances` 表 + 六部位列 + 年度追踪（merchant:N 面）——B4 期望成本量化的产出链事实以此为准。
7. **.so**：EQ-B3 期 worktree 重编（07:26，259 源）——B4 不触 C++ 则复用。
8. **实时线遗留三项勿顺手处置**；双 changelog 归 EQ-B5（B4 不动双更新日志）。
