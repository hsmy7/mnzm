# report-R1 · 验收整改 R1：阻塞解除

> 依据：`docs/design/acceptance-remediation-plan.md` §5.1（A5-a 断言 + A1 UI + A3 文档 + A7 补跑门禁）
> 实施会话：整改实施（本批）；日期 2026-09-30
> **跨线声明（RA3）**：本批修改 `android/app/src/main/cpp/gamecore/test/bench/accrual_segment_bench_test.cpp`
> ——该文件为 **realtime 线 B8 的产物**（B9/B10 曾登记"overBudget==0 断言噪声脆弱"移交未处置）。
> 本批按整改方案 §2.3① 修改其断言口径，**请看护复验**（realtime 线 accepted 状态不受影响的主张：
> 仅测试断言口径变更，零生产代码、零遥测语义变更）。

---

## 1. 写入面（与方案 §5.1 逐项对应）

| # | 项 | 文件 | 内容 |
|---|---|---|---|
| ① | A5-a | `android/app/src/main/cpp/gamecore/test/bench/accrual_segment_bench_test.cpp` | 断言口径改 **§2.3①**：`best < 预算` 且 `P50 < 预算` 双判据；`max`/遥测 `overBudgetCount` 降级为**诊断打印**（尾延迟观察项）。采样函数 `bestAccrueUs` → `sampleAccrueUs`（升序全采样，返回 best/p50/max 结构体）；`kWarmupRuns=3`/`kSampleRuns=15` 提为命名常量；断言失败时 gtest 自动打印实测值 vs 预算值，stdout 另有全量采样统计 |
| ② | A1 | `android/feature/game/.../dialogs/HeavenlyTrialBattleDialog.kt` | `:343` 二值标签 `if (== MAGIC) "法术" else "物理"` → `enemy.innateDamageType.displayName`（六类一次到位；`DamageType` import 随之移除） |
| ③ | A3 | `docs/design/equipment-batches/reports/report-E1.md`（新） | 五行批补报告（五节，含实跑原数字与旧用例处置表） |
| ④ | 登记 | `docs/realtime-watch/DISPATCH-LEDGER.md` §9 | 五行批实现登记 + 本整改登记（含跨线声明） |

**影响面**：零生产逻辑改动、零存档影响、零 Room 变更。UI 改动为显示层复用既有 `displayName`——当前名册全 PHYSICAL，行为对既有内容不变（未来元素敌人显示正确）。

## 2. 验收判据（方案 §5.1 ①–⑦，实跑原数字）

| # | 判据 | 结果 |
|---|---|---|
| ① | `ctest` 全绿（含 bench，新口径） | ✅ **1529/1529**（63.16s，安静窗口） |
| ② | `ctest -R AccrualSegmentBench` 单跑 **best < 预算** | ✅ 5 连跑原始采样：best **830.1 / 831.3 / 828.2 / 823.5 / 833.2 µs**（best-of-bests **823.5**）；p50 **838.2 / 835.6 / 832.6 / 838.7 / 838.6 µs**；均 < 1000 µs。max 1002–1215 µs、overBudget 1–2 次（诊断打印，不判红——尾部抖动为调度噪声，best≈823µs 量级下 18 采样 max 结构性越预算） |
| ③ | `compileReleaseKotlin` + 全量 `testReleaseUnitTest --max-workers=1` | ✅ 编译绿；六模块 **7666 tests / 0 failures / 0 errors / 22 skipped**（app 1028/2、core:data 881/15、core:domain 1604、core:engine 3006/5、core:ui 155、feature:game 992；JNI 注入 5m29s） |
| ④ | `lintRelease detekt` | ✅ 全模块绿（8m11s，BUILD SUCCESSFUL） |
| ⑤ | `gen-templates.mjs` 后零差异（G0） | ✅ exit 0，`git status` 仅含本批两处代码改动，零 codegen 漂移 |
| ⑥ | `check-agent-instructions.mjs` | ✅ 五规则全绿（预算 25026/32768；引用 484 条全解析——较五行批 482 净增 2，为整改文档自身引用） |
| ⑦ | `report-E1.md` 落盘 + 台账登记 | ✅ 本批同交 |

**附加证据**：`gen-game-data.mjs --check` 通过（sha256 `befe462d…`）——非方案判据项，顺跑补证。

## 3. 旧用例处置

| 用例 | 处置 | 理由 |
|---|---|---|
| `SegmentUnderBudgetAt5000` 断言 | **改断言** | 旧口径 `overBudgetCount == 0`（= 18 采样 max 不得越 1ms）在 best≈823µs 基线下**结构性不可能绿**（验收 5/5 确定性红实证）；新口径 best+P50 守住"中位性能不超预算"，尾部降级观察项。方案 §2.3 三候选中取默认 ①；②（提预算至 1200µs）会掩盖退化，明确否决 |
| UI "物理/法术"文案断言用例 | 无（不存在） | 全库扫描无断言该对话框文案的测试（`grep HeavenlyTrialBattleDialog *Test*` 仅 `InnateDamageTypeGuardTest` 数据守卫，无文案断言） |

## 4. 途中发现（不在本批写入面，登记不扩面）

1. **同类二值标签 4 处**（举一反三扫描）：`BattleDescriptionGenerator.kt:139/193/272`、`BattleSystem回合Ops2.kt:41`、`BattleSystem伤害Ops3.kt:333` 均按 `isPhysical` 二分输出"物理/法术"。判定：属战斗动画/描述文案链（`BattleActionData.damageType` 混合承载状态与类型），五行批提交说明明言"**动画二分保留**"为既定决策 ⇒ 与 A1 不同类，不在本批修复。当前名册全物理不可观测；未来元素敌人上线时战斗描述会误显示"法术"，建议后续触战斗文案链的批次六类型化收口。
2. **`bench_out.txt` 误入库**：五行批将 6 行 gtest 输出转储提交至仓库根（`git show eaa146afb` 的 A 类文件），零引用。归入 R4-A6 仓库卫生批一并清理。
3. **best 余量 ≈ 17.6% < 20%**：按方案 §2.2 分支③的债触发条件（余量 <20% 即 800µs），R2 归因后定性（见 `report-R2.md`）。

## 5. 回滚

直接 revert 本批提交（无数据副作用）。bench 断言口径如需回旧口径将复现确定性红（A5-a 未修状态）。
