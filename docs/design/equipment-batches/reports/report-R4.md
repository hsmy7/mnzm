# report-R4 · 验收整改 R4：仓库卫生 + 元素端到端对拍

> 依据：`docs/design/acceptance-remediation-plan.md` §5.4/§6（A6 清理 + A2 对拍）
> 实施会话：整改实施；日期 2026-09-30
> 本批两笔提交：A6 清理单独成笔（`dddebd2d8`，便于独立 revert）+ A2 对拍测试（`dd3c41703`）

---

## 1. A6 · `.clash-repair/` 清理（方案 §6 规程逐步执行）

| 步骤 | 命令 | 结果 |
|---|---|---|
| ① 引用检查 | `git grep -n clash-repair`（删前） | 命中全部位于 `.clash-repair/` **自身内部**（一次性诊断转储自引用）；外部（代码/脚本/文档链接）零引用 |
| ② 清点 | `git ls-files .clash-repair \| wc -l` | **72**（方案预估 ≈70+ ✓） |
| ③ 删除 + ignore | `git rm -r --quiet .clash-repair` | ✅ 72 文件 −2883 行 |
| ④ 附带清理 | `git rm bench_out.txt` | 五行批（`eaa146afb`）误入库的 6 行 gtest 输出转储，零引用（report-E1 §3 途中发现登记项） |
| ⑤ 防复发 | `.gitignore` 追加 `.clash-repair/` 与 `bench_out.txt` | ✅ |

**删后复核原文**（判据①）：
- `git ls-files .clash-repair | wc -l` → **0**
- `git grep -n clash-repair` 残留命中 **3 文件、全为任务记录性文档提及**（整改实施文档自身 §1/§4/§5.4 的 A6 条目描述、验收报告 §3 A6 条目、batch-SR0.md 历史记录）——**非功能性引用**，文档如实记录不改写；门禁⑤引用无死链实测通过（484 条内部引用全解析，删后复跑）

## 2. A2 · 元素端到端对拍（选"落地"，不选登记 EQ-I17）

**选择声明（DoD #8 二选一）**：本整改**落地**了 `DiffElementalDamageTest`——桥面零扩展
（复用 `nativeCoreBattleOp` 的 combatantDamage op 与 `battle_json.h` 既有 12 键协议，与
`DiffBattleCalculatorTest` 同源），无需新桥端口 ⇒ 不触发 §3.3 的 EQ-I17 升级条款。

| 用例 | 场景数 |
|---|---|
| 普攻类型跟随 innateDamageType 六值（×3 种子） | 18 |
| 技能类型跟随 damageType 六值（×2 种子） | 12 |
| 攻方六路类型加成同类型生效 | 6 |
| 攻方类型加成异类型隔离 | 2 |
| 守方六桶类型减伤同类型 | 6 |
| 攻防类型 6×6 全矩阵 | 36 |
| 灵根 gate 折算（`SpiritRoot.elementGate` 唯一入口 × 5 单灵根 × 6 攻型） | 30 |
| 物理路径不回退（E11，普攻/物理技 × 2 种子） | 4 |
| **合计** | **114 场景 × 6 字段断言 = 684 逐位断言** |

**实跑**：engine 模块全量内 **8/8 用例绿、0 skip**（`-Dgamecore.jni.path` 真实 JNI 注入，
对拍桥与 gamecore 源同源指纹 259 文件）；遍历锚定 `DamageType.ACTIVE`/`ELEMENTAL`
生产常量，活跃集扩展自动纳入。

## 3. 终态门禁（A6+A2 落盘后复跑）

| 门禁 | 结果 |
|---|---|
| `lintRelease detekt` | ✅ 全绿（含 A2 新测试文件的 detekt 面） |
| `gen-templates.mjs`（G0） | ✅ exit 0，`git status` 零漂移 |
| `check-agent-instructions.mjs` | ✅ 规则③ 484 条引用全解析 + 五规则全绿 |
| `ctest` / 六模块 JVM | ✅ 本整改终态已于 R3 批后全量实跑（1532/1532、7684/0/22skip）；A6 为纯删除（零代码引用）、A2 的测试文件在该轮全量中已编译实跑（untracked 同内容），无需重跑全量 |

---

## 4. 验收整改 DoD（方案 §8 十二条）逐项核对

| # | 判据 | 状态 | 证据 |
|---|---|---|---|
| 1 | ctest 全绿（新口径）+ 安静窗口 best 原始记录 | ✅ | 1532/1532；R1 报告 §2②（823.5–833.2）、R2 §2（三点全录）、R3 §3/§6（双臂 + 终态 641.5） |
| 2 | Kotlin 全量 `--max-workers=1` 全绿 | ✅ | **7684 / 0 / 22skip** 六模块终态（R1 时点 7666；暴击/常驻池并网增例 18） |
| 3 | `lintRelease detekt` 全绿 | ✅ | R1 / R3 / R4 三轮全绿 |
| 4 | G0 幂等零差异 | ✅ | R1 / R3 / R4 三轮 exit 0 |
| 5 | `check-agent-instructions.mjs` 全绿 | ✅ | 482→484 引用全解析（终态复跑） |
| 6 | A1 UI 六类显示合入 | ✅ | `67039d34f`（displayName） |
| 7 | A3 report-E1 落盘 + 台账登记 | ✅ | R1 批；台账 §9 两条登记 |
| 8 | A2 对拍落地 **或** 登记 EQ-I17 | ✅ **落地** | `dd3c41703`，114×6 逐位全绿 |
| 9 | A6 删除 + ignore + 引用检查原文入报告 | ✅ | `dddebd2d8`；本文 §1 |
| 10 | A5-b R2 归因落报告；需优化则 R3 达标 | ✅ | 分支①（装备 80%）；R3 best 641.5µs ≤800 且 ≥10%（双达标） |
| 11 | 各批 report-R*.md 落盘含实跑数字 | ✅ | report-R1/R2/R3/R4 + report-E1 五份 |
| 12 | 台账 §9 登记本整改（含跨线声明） | ✅ | R1 批登记 + 本批终态登记 |

## 5. 途中发现汇总（不在整改写入面，均已登记）

1. **战斗描述/动画 4 处二值标签**（`BattleDescriptionGenerator` ×3、`回合Ops2`、`伤害Ops3`）——五行批"动画二分保留"既定决策；未来元素敌人上线时战斗文案会误显示"法术"，建议触战斗文案链的批次六类型化收口（report-R1 §4）。
2. **暴击/常驻池两线整改期间并网 main**（`6b202b01e`/`11c9cd803`）——首轮 JVM 10 失败系 .so 工件与合并后源不一致，清目录重编即愈；终态全量门禁以合并后源复验通过（report-R3 §6）。
3. **有装备名册的逐 tick `EquipBonus` 解析成本不变**——B9 前连续轨设计形态，仍属方案 §7.2/D1 已登记观察项（report-R3 §2 边界声明）。
