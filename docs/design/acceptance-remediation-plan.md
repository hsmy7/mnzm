# 验收整改实施文档（装备重构 + 五行属性伤害）

> 版本：v1.0（2026-09-30）
> **依据**：`docs/design/acceptance-review-equipment-and-elemental.md`（独立验收报告，提交 `2a6a80553`）
> **性质**：对**验收发现的 6 项（A1–A6）与 5 项未跑门禁**的整改实施文档。**执行由看护线装配派发件、实施会话执行**（本会话只出文档，不改代码）。
> **边界**：不推翻已 accepted 的 EQ-B0–B5 结论；本整改完成后**需看护复验**（涉及跨线改动，见 §7.2）。
> 依据规范：`rules/design-plan-review.md`、`rules/build-quality.md`、`rules/cpp-priority.md`、`rules/testing.md`、`rules/code-comment.md`

---

## 0. 前置与纪律

| 项 | 内容 |
|---|---|
| 开工前置 | `git status` 干净；装备线/五行线无在途改动（查 `docs/realtime-watch/DISPATCH-LEDGER.md` §8/§9） |
| **安静窗口纪律（本整改的关键约束）** | 凡涉及 bench 的批（R1/R2/R3），必须在**无其他构建/测试进程**时执行；判定取 `best`（多次采样最小值）而非 `max`；每个数据点**至少 5 连跑**并记录全部采样 |
| 跨线声明 | R1 会修改 `accrual_segment_bench_test.cpp`（**realtime 线 B8 的产物**）⇒ 属跨线改动，须在台账 §9 明示并经看护复验 |
| 不改的部分 | 已 accepted 的 B0–B5 交付物**只做本文件列明的改动**，禁止顺手重构 |
| 报告路径 | `docs/design/equipment-batches/reports/` 下按批新建（或 `report-E1.md`，见 §7.1） |

---

## 1. 整改项清单（现象 / 证据 / 影响 / 归属）

| # | 项 | 现象（实测证据） | 严重度 | 归属批 |
|---|---|---|---|---|
| **A5-a** | **bench 断言设计缺陷** | `overBudgetCount == 0` 在 best≈856µs 的基线下**不可能绿**（18 次采样的 max 必抖过 1000µs，实测 overBudget 2–7） | 🔴 阻塞（门禁确定性红） | **R1** |
| **A5-b** | **真实性能退化 +30%** | 安静窗口 5 连跑 best = **850.5 / 856.8 / 859.9 / 861.9 / 863.1 µs**（方差极小）vs B9 记录 **656–666 µs** ⇒ **+30% 为真实净开销** | 🟠 高（仍 < 1000µs 预算，余量 34%→14%） | **R2**（归因）→ 可能触发 R3 |
| **A1** | UI 类型标签硬编码二值 | `HeavenlyTrialBattleDialog.kt:343` `if (innateDamageType == MAGIC) "法术" else "物理"`；元素敌人会显示"物理"（当前全员 PHYSICAL 故不可观测） | 🟠 中 | **R1** |
| **A2** | 五行六值无跨语言端到端对拍 | 无 `DiffElementalDamageTest`；`Diff*Test` 零命中元素；协议面 `battle_json.h` 已支持 6 值 | 🟠 中 | **R4**（或登记 EQ-I17 后延） |
| **A3** | 五行批未走批次协议 | 无派发件/无 `report-E*.md`/无台账 accepted；仅主干提交 `eaa146afb` | 🟠 中 | **R1**（补文档，无代码） |
| **A4** | 秘境旧堆叠轨残留 | `SecretRealmRuinsResolver.kt:145` 仍构 `EquipmentStack`（自述"待 B4"）；**已登记 EQ-I13** | 🟡 低（已登记） | 不阻塞；后续触秘境链的批收口 |
| **A6** | `.clash-repair/` 约 70+ 临时文件入库 | `git ls-files .clash-repair` 列出 `_*.txt`/`edit*.py`/`_head_heavy*.kt` 等 | 🟠 中（仓库卫生） | **R4** |
| **A7** | 5 项未跑门禁 | Kotlin 全量 `testReleaseUnitTest`、`lintRelease detekt`、codegen G0 幂等 | 🟠 中（放行证据缺口） | **R1**（看护安静窗口补跑） |

---

## 2. A5-b 归因方法（先把 +30% 拆到具体来源）

### 2.1 已知数据

| 场景 | best | max | overBudget | 说明 |
|---|---|---|---|---|
| B9 基线（realtime 线记录，同机） | **656–666 µs** | — | 0 | 三门重构**之前** |
| 我·全量 ctest 同跑 | 884.7 µs | 1073 | 2 | 有并行负载 |
| 我·安静窗口 5 连跑 | **850.5–863.1 µs** | 1021–1889 | 2–7 | **稳定 ⇒ 真实** |

⇒ **必须三点 A/B 归因**（同一机器、同一安静窗口、各取 best）：

```
B = 检出 17161f5fc（B9 收官，realtime 线完成、装备线与五行尚未开始）
    → 构建 game-core-bench → 安静窗口 5 连跑 → best_B（预期 ≈ 656–666µs，用于校准当前机器）
C = 检出 45bf909cd（EQ-B3 收官，装备体系原子替换完成）
    → 同上 → best_C
D = 检出 eaa146afb（五行实现，当前 HEAD 附近）
    → 同上 → best_D（应 ≈ 856µs，用于校准与一致性校验）
```

- **贡献拆分**：`Δ装备 = best_C − best_B`、`Δ五行 = best_D − best_C`
- **一致性校验**：`best_B` 若显著偏离 656–666µs（如 >700µs），说明本机当前整体较慢 ⇒ 需按 `best_D / best_B` 的**相对比**判断，而非绝对值。

### 2.2 结论分支（决定是否开 R3）

| 分支 | 判据 | 处置 |
|---|---|---|
| ① 装备侧为主 | `Δ装备 / (best_D − best_B) > 60%` | 优化 B3 六部位结算路径：`disciple_stats.h` 装备段求和、`EquipBonus` 解析缓存（方案 §6.6 已预留"不劣化 >10%"判据） |
| ② 五行侧为主 | `Δ五行 / (best_D − best_B) > 60%` | 优化六路类型通道：把"按类型选桶"从逐路判断改为**类型→索引一次查表**；确认 `Combatant` 六路固有桶未在结算内重复求和 |
| ③ 均匀分摊 | 两者皆 ≤60% | 逐热点 profile（`accrue(100ms)` 路径）后再定；**可不优化**，仅按 A5-a 修断言 + 登记性能债（触发条件：余量 <20% 即 800µs） |
| ④ 归因后发现是环境 | `best_B ≈ best_D`（<5% 差） | 说明 B9 的 656µs 系异环境取值 ⇒ 只修断言（A5-a），撤销 A5-b |

> **无论哪一分支，A5-a（断言）都必须修**——因为 `overBudgetCount == 0` 在 850µs 基线下**结构性不可能绿**。

### 2.3 A5-a 断言修法候选（实施时择一，默认取①）

| # | 修法 | 优点 | 缺点 |
|---|---|---|---|
| **①（推荐）** | 断言改为 **`best < 预算` 且 `P50 < 预算`**；`max`/`overBudgetCount` 降级为**打印诊断**（不再断言） | 去掉对单次抖动的敏感性；仍守住"中位性能不超预算" | 极端尾延迟不再被门禁拦截 ⇒ 需另立"尾延迟观察项"（打印 + 人工看） |
| ② | 预算由 1000µs 提到 **1200µs**，保留 `overBudget==0` | 改动最小 | **掩盖退化**，与方案 §6.6"不劣化 >10%"冲突；不推荐 |
| ③ | 分级：`best < 1000` 硬断言 + `max < 1500` 软告警 | 兼顾中位与尾部 | 告警易被忽略；实现稍复杂 |

**实现要点**：判据常量必须是**命名常量**（禁止魔法数字，编码规范 0.4）；断言失败消息需给出**实测值 + 预算 + 采样数**（便于归因）。

---

## 3. A2 对拍方案（元素端到端双端逐位）

### 3.1 目标

补上"同一场景下 Kotlin 与 C++ 伤害结果逐位一致"的元素专项对拍，覆盖：类型判定（普攻/技能 6 值）、六路类型加成 × 灵根 gate、六桶减伤。

### 3.2 方案（沿用既有 `Diff*Test` 模式）

| 项 | 设计 |
|---|---|
| 测试类 | **新增** `android/core/engine/src/test/java/.../nativebridge/DiffElementalDamageTest.kt` |
| 驱动方式 | 构造 `Combatant` 场景（攻方 6 类型 × 5 灵根组合 × 守方 6 桶组合）→ 经 `BattleJsonCodec` 喂 C++（复用既有 `battle_json` 键：`damageType` / `innateDamageType` / 类型加成 / 类型减伤） |
| 断言 | 逐场景断言 Kotlin `BattleCalculator` 与 C++ 返回值**逐位相等**（含 `damage`、`isCrit`、`typeDamageBonus` 落点） |
| 基线 | **不需要重录既有基线**（元素是新增场景），但需在报告中给出"场景数 × 断言数"与全部通过 |
| 若缺桥端口 | 若现有 JNI 面无法注入"类型加成/类型减伤"，则**先补桌面测试桥端口**（与 EQ-I11 的 `nativeCoreGenerateEquipment` 同族），再落地对拍；此情形升级为 **EQ-I17 + 桥端口同批** |

### 3.3 与 EQ-I11 的关系

EQ-I11（装备生成/套装结算的端到端对拍缺口）与本项同族。**建议**：若两批相邻，**合并为一次桥端口扩展 + 对拍补齐**，避免两次改 JNI 面。

---

## 4. 批次编排

```
R1 阻塞解除（断言 A5-a + UI 1 行 A1 + 文档 A3 + 补跑门禁 A7）
   └─► R2 性能归因（A5-b 三点 A/B）
          └─► R3（条件批）按 R2 结论做热点优化（若判定需要）
   └─► R4 清理与对拍（A6 .clash-repair + A2 元素对拍）
```

| 批 | 内容 | 规模 | 是否碰 C++ | 报告 |
|---|---|---|---|---|
| **R1** | A5-a 断言 + A1 UI + A3 文档 + A7 补跑 | 小（~5 文件） | 是（测试文件） | `reports/report-R1.md` |
| **R2** | A5-b 三点归因（只测不改，除必要的探针） | 中（无生产改动） | 是（构建 3 次） | `reports/report-R2.md`（含归因数据表） |
| **R3** | 条件批：按 R2 结论优化结算热点 | 中 | 是 | `reports/report-R3.md` |
| **R4** | A6 清理 + A2 元素对拍 | 中（对拍含新增测试） | 是 | `reports/report-R4.md` |

> **依赖**：R3 **必须**在 R2 结论之后；R2 不通过（分支④）则 R3 取消。R1 与 R2/R4 无写面重叠，可并行（但 R1/R2 都跑 bench ⇒ **必须串行**，见 §0 安静窗口纪律）。

---

## 5. 批次详述（TASKBOOK 级）

### 5.1 R1 · 阻塞解除

| 项 | 内容 |
|---|---|
| **目标** | 门禁转绿（A5-a）+ 修 UI 显示缺陷（A1）+ 补齐五行批文档（A3）+ 补跑 5 项未跑门禁（A7） |
| **写入面** | ① `android/app/src/main/cpp/gamecore/test/bench/accrual_segment_bench_test.cpp`（断言口径，见 §2.3 候选①）② `android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/HeavenlyTrialBattleDialog.kt:343`（改用 `enemy.innateDamageType.displayName`）③ **新增** `docs/design/equipment-batches/reports/report-E1.md`（五行批补报告，模板见 §7.1）④ `docs/realtime-watch/DISPATCH-LEDGER.md` §9（台账登记） |
| **影响面** | 仅测试断言口径 + 1 行 UI 文案；**零生产逻辑改动**、**零存档影响**、**零 Room 变更** |
| **兼容性** | UI 改法为显示层复用既有 `displayName`，六类显示一次到位（当前全员 PHYSICAL ⇒ 行为对既有内容不变，仅未来元素敌人显示正确） |
| **验收判据** | ① `ctest` **全绿**（含 bench；记录安静窗口 best 与 overBudget 诊断值）② `ctest -R AccrualSegmentBench` 单跑 **best < 预算** ③ `compileReleaseKotlin` + 全量 `testReleaseUnitTest --max-workers=1` 绿 ④ `lintRelease detekt` 绿 ⑤ `gen-templates.mjs` 后零差异（G0）⑥ `check-agent-instructions.mjs` 绿 ⑦ `report-E1.md` 落盘且台账已登记 |
| **旧用例处置** | 给表：bench 断言口径变更 = **改断言**（附新口径与理由）；UI 无测试改动（若存在断言"物理/法术"文案的用例则一并改为六类） |
| **回滚** | 直接 revert（无数据副作用） |
| **规模** | 小（~5 文件） |

### 5.2 R2 · 性能归因（只测不改）

| 项 | 内容 |
|---|---|
| **目标** | 把 +30% 拆到"装备 B3"与"五行"两侧（§2.1 三点 A/B），给出结论分支（§2.2） |
| **前置** | R1 已合入（避免断言红干扰取数）；**安静窗口**（无其他构建/测试进程） |
| **写入面** | 无生产代码改动；仅 **新增** `reports/report-R2.md`（含全部原始采样）；如需探针（临时计时打印）须**同批删除**并说明 |
| **方法** | 三个提交点各检出到**独立 worktree**（避免污染主线）→ 各自构建 `game-core-bench` → 各 **5 连跑**取 best → 填表 §2.1 |
| **验收判据** | ① 三点各 ≥5 次采样，**全部原始值入报告**（禁只写结论）② `best_D` 与本次实测 856µs 偏差 <5%（一致性校验）③ 给出 §2.2 的分支判定与依据 ④ 若判定需优化，产出 R3 的**具体热点与预期收益**；若无需优化，登记性能债（触发条件 = 余量 <20%） |
| **回滚** | 文档 revert；worktree 用完即删 |
| **规模** | 中（3 次 C++ 构建 + 采样；无生产改动） |

### 5.3 R3 · 条件性能优化（仅当 R2 判定需要）

| 项 | 内容 |
|---|---|
| **目标** | 按 R2 结论优化结算热点，把 best 拉回预算余量 >20%（即 ≤800µs）或给出不优化的充分理由 |
| **候选热点** | ① 装备侧：`disciple_stats.h` 装备段求和 / `EquipBonus` 每旬解析缓存 ② 五行侧：类型→桶的逐路判断改**一次查表**；确认六路固有桶未重复求和 ③ 若两者皆非：`accrue(100ms)` 路径 profile 后另定 |
| **验收判据** | ① **同一安静窗口**下 best 相对 R2 的 `best_D` **改善 ≥10%** 或达到 ≤800µs ② `ctest` 全绿 ③ 全部 `Diff*` 与 C++ GTest 逐位一致（**禁止以牺牲逐位一致性换性能**）④ 报告给出"优化前/后 best + 采样数" |
| **回滚** | 直接 revert；性能类改动**不得**改数值语义（只改计算组织方式） |
| **规模** | 中 |

### 5.4 R4 · 清理与元素对拍

| 项 | 内容 |
|---|---|
| **目标** | A6 清理临时文件 + A2 补元素端到端对拍 |
| **写入面** | ① `.clash-repair/` 整目录删除 + `.gitignore` 加条目 ② **新增** `DiffElementalDamageTest.kt`（§3.2）＋必要时补桌面测试桥端口 |
| **A6 前置检查** | 删除前必须：`git grep -n 'clash-repair'` **零命中**（确认无代码/脚本引用）；在报告中贴出该命令输出为零的原文 |
| **验收判据** | ① `git grep 'clash-repair'` 零命中（原文入报告）② `.gitignore` 含该条目 ③ 对拍测试通过并给出**场景数 × 断言数**（§3.2）④ `ctest` + 全量 Kotlin 绿 ⑤ `check-agent-instructions.mjs` 绿（③规则引用无死链——删目录后若文档引用它需同步修） |
| **回滚** | `git revert` 单笔提交（A6 与 A2 建议**分两笔**提交，便于独立回滚） |
| **规模** | 中 |

---

## 6. A6 清理规程（细节）

```powershell
# 1) 引用检查（必须零命中才继续）
git grep -n 'clash-repair'            # 期望：无输出
# 2) 清点（入报告）
git ls-files .clash-repair | Measure-Object -Line    # 期望 ≈ 70+
# 3) 删除 + ignore（单独一笔，便于回滚）
git rm -r --quiet .clash-repair
Add-Content .gitignore "`n# 临时诊断转储（一次性产物，不入库）`n.clash-repair/"
git commit -m "chore: 清理入库的临时诊断产物 .clash-repair/ 并加入 .gitignore"
```

**注意**：若 `git grep 'clash-repair'` 有命中（例如某文档引用），**先修引用再删**，否则门禁③（引用无死链）会红。

---

## 7. 文档与流程补齐

### 7.1 `report-E1.md` 模板（五行批补报告）

必含五节（对齐装备线口径）：

1. **做了什么**（分类表：六值化 / 灵根 gate / 六套 36 部件 / 11 项池 / 类型桶 / 功法元素）
2. **验证**：**门禁实跑原数字**（`ctest N/N`、Kotlin 模块 N/N、detekt、codegen 零漂移、`check-agent-instructions`）——**禁"应该通过"**
3. **旧用例处置表**（删除/改断言/保留 + 理由）
4. **未完成 / 登记**（E9 对拍缺口 → EQ-I17；A1 UI；以及本项目元素的已知例外）
5. **风险**（区分"已核实"与"推测"）

> 现状：五行批**无任何报告**（验收报告 §3 A3）⇒ 本节是流程补课的硬要求。

### 7.2 台账登记（`DISPATCH-LEDGER.md` §9）

补两条：
- **五行批实现登记**：提交 `eaa146afb`、验收报告 `2a6a80553`、结论"实现忠实但与方案差异 3 处（见验收报告 §3）"
- **本整改登记**：R1–R4 的派发与收官，**明示 R1 跨线修改了 realtime 线 B8 的 bench 文件**，请看护复验

### 7.3 未跑门禁的补跑（A7）

| 门禁 | 安排 |
|---|---|
| Kotlin 全量 `testReleaseUnitTest --max-workers=1` | 看护安静窗口一次跑完，结果并入 `report-R1.md` |
| `lintRelease detekt` | 同上 |
| `gen-templates.mjs` 幂等（G0） | 跑后 `git diff` 必须零差异；有差异即红（D9 判据） |

---

## 8. 放行判据（Definition of Done）

整改全部完成 = **以下同时成立**：

1. `ctest` **全绿**（含 bench，断言按 §2.3① 口径）且安静窗口 best 有原始记录
2. Kotlin 全量 `testReleaseUnitTest --max-workers=1` 全绿
3. `lintRelease detekt` 全绿（baseline 只缩不增）
4. `gen-templates.mjs` 幂等（零差异）
5. `check-agent-instructions.mjs` 全绿
6. A1 修复已合入（UI 六类显示）
7. A3：`report-E1.md` 落盘 + 台账登记
8. A2：元素对拍落地**或**登记 EQ-I17（二者择一，需在报告中写明选了哪个）
9. A6：`.clash-repair/` 已删 + `.gitignore` 已加 + 引用检查零命中原文入报告
10. A5-b：R2 归因结论落报告；若判定需优化则 R3 达标（`best` 改善 ≥10% 或 ≤800µs）
11. 所有批的 `report-R*.md` 落盘，含**实跑数字**与**旧用例处置表**
12. 台账 §9 登记本整改（含跨线声明）

---

## 9. 风险与对抗点

| # | 风险/对抗点 | 兜底 |
|---|---|---|
| RA1 | bench 取数受并行负载污染（本项目实测过多次） | §0 安静窗口纪律；每点 ≥5 连跑取 best；原始值全入报告 |
| RA2 | 改断言被当作"绕过门禁" | 必须**同时**给出 A5-b 归因结论（R2）；若结论为"真实退化且不可接受"，禁止只改断言 |
| RA3 | 跨线改 `accrual_segment_bench_test.cpp` 导致 realtime 线 accepted 状态失效 | R1 提交说明与台账 §9 明示；请看护复验 |
| RA4 | 元素对拍需要新桥端口 → 工作量与 JNI 面变更 | 与 EQ-I11 合并同批；若无法同批则登记 EQ-I17（用户已同意二选一） |
| RA5 | 删 `.clash-repair` 断掉未知引用 | §6 强制引用检查；零命中原文入报告；单独一笔提交便于 revert |
| RA6 | 性能优化改坏逐位一致性 | R3 判据强制 `Diff*` + C++ GTest 逐位一致；**禁止改数值语义** |
| RA7 | 五行批补报告时"回忆式"填写导致数字不实 | 报告只允许填**可复现的实跑数字**；无法重跑的项写明"未复核" |

---

## 10. 附：本次验收已确认**无需整改**的项（备查）

以下项已由验收报告逐条取证通过，本整改**不涉及**：
- 装备：R1–R12 需求覆盖、S2/S3/S5/S7/S8/S9/S12/S13/S14/S15/S17/S18/S19/S20/S21
- 五行：E1（6 活跃值 + MAGIC 退役段）、E2/E3（普攻配置驱动、技能功法元素）、E4（gate = 1.0/0.0 纯开关，含空串/未知→0，物理恒 1.0）、E5/E6（六路隔离、六桶默认 0 与基准逐位一致）、E7（六套 0–6 件）、E8（11 项池 + ×1.5 档位）、E11、E12
- 例外在册：EQ-I11（对拍两缺口）、EQ-I13（秘境旧堆叠轨）、EQ-I12/14/15/16（数值与 AI 决策类）
