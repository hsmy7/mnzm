# report-E1 · 五行属性伤害系统（批次补报告）

> 批次本体：五行属性伤害系统，主干单批提交 `eaa146afb`（2026-09-30，父 `8c6818152`）——未走批次协议（无派发件、无看护亲验）。
> **报告性质**：验收整改 R1（A3 流程补课）补写，模板按 `docs/design/acceptance-remediation-plan.md` §7.1。
> 依据：方案 `docs/design/elemental-damage-system-plan.md`、ADR `docs/adr/elemental-damage-system.md`、
> 独立验收报告 `docs/design/acceptance-review-equipment-and-elemental.md`（§3 A3）。
> **诚实声明（RA7）**：本报告只填可复现的实跑数字——批次本体终验数字取自提交说明（git 在案，`git log -1 eaa146afb` 可查）；
> 复跑数字取自验收报告 `2a6a80553` 与整改 R1 实跑（`reports/report-R1.md`）。无法重跑的项标"未复核"。

---

## 1. 做了什么（分类表）

| 类别 | 内容 | 载体 |
|---|---|---|
| **六值化** | `DamageType` 6 活跃值（PHYSICAL + 金木水火土）；`MAGIC`/`MAGIC_DAMAGE_PCT` 按 name 序列化兼容保留退役段（方案的 ProtoNumber 假设经实测修正），禁新产出；`DamageTypeGuardTest` 钉死活跃集 | core:domain `GameConfig.kt`；新增 `TypeDamageBonuses.kt` |
| **灵根 gate** | `SpiritRoot.elementGate` 唯一入口：含→1.0 / 不含→恰 0.0 / 物理恒 1.0（纯开关，不做线性折算）；`typeDamageBonusesOf` 在弟子装配构造期折算进 Combatant 桶——**战斗公式零感知**；三条弟子装配线接线（补齐装备线 B1 的 EquipBonus 类型维断裂链） | `Disciple.kt`（elementGate）、`TypeDamageBonuses.kt` |
| **六套 36 部件** | 6 套（lietian/gengjin/qingmu/xuanshui/lihuo/houtu）× 0–6 件同构骨架，双端逐位（Kotlin `EquipmentSetDatabase` ↔ C++ `equip_set_bonus`） | 装备数据表 + 双端测试 |
| **11 项池** | `EquipAffixPool` 11 项、权重合计 100；类型词条 ×1.5 补偿档（`DAMAGE_PCT_TIERS` 0.006–0.030）；退役段权重/档位为 0（测试断言） | `EquipAffixPool` |
| **类型桶** | 类型通道 12 桶（6 加成 + 6 减伤）双端同构；`BattleJsonCodec` ↔ `battle_json.h` 协议 12 键；六桶全 0 时与装备线基准逐位一致（S19） | `Combatant`、`battle_json.h` |
| **功法元素** | 技能 = 功法自带元素；78 条 magic 功法按名义语义映射五行（雷属金 / 腐蚀因果属木 / 护体系水木 / 寂灭太初崩坏属土）；普攻类型本批语义为恒物理（后续 `dd3705288` 返工为角色配置驱动，见 §4） | 功法数据表、`gen-manual-db.mjs` |
| **装备供给线** | 掉落 / 兑换码 / 编队 / 敌人 / 任务 / 试炼 六入口"二选一"统一改 `ALL_IDS` 六选一 | 六处供给入口 |
| **素材** | 36 张精灵图按 EA4 程序化占位兜底 | 精灵注册 |
| **数值校准（E12）** | 装备占比带按 B4 方法论重锚 [0.30, 0.45]（词条池权重重定与 4 件套统一骨架的低品阶入口下移 2–3.6pp） | 实测表登记于测试 KDoc |

---

## 2. 验证（门禁实跑原数字）

| 层 | 门禁 | 实跑结果 |
|---|---|---|
| **批次本体终验**（提交说明在案） | 六模块 JVM `testReleaseUnitTest --max-workers=1`（JNI 注入） | **3005/0/5 skip**（Diff 家族含） |
| 同上 | `ctest` 全量 | **1528/1529**——唯一红 `AccrualSegmentBench.SegmentUnderBudgetAt5000`（后经整改定性为**断言设计缺陷 A5-a**：`overBudgetCount==0` 在 best≈850µs 基线下结构性不可能绿；非本批性能回归，归因见 `report-R2.md`） |
| 同上 | `detekt` / `lintRelease` | 零违规 |
| 同上 | G0 codegen 幂等 | 零漂移（`gen-manual-db.mjs` 补齐 `operator==`/Mutable 容器生成，顺带修复 G0 幂等链断裂） |
| 同上 | `check-agent-instructions` | 482 引用全绿 |
| **独立验收复跑**（`2a6a80553` 在案） | `ctest` 全量 | 1528/1529（同一红项，5/5 确定性红） |
| 同上 | `check-agent-instructions` | 全绿 |
| **整改 R1 复跑**（断言修复后，原数字见 `report-R1.md`） | `ctest` 全量 | **1529/1529 全绿** |
| 同上 | bench 硬门禁 5 连跑（安静窗口） | best **823.5–833.2 µs**（best-of-bests 823.5）、p50 832.6–838.7 µs，均 < 1000 µs 预算，余量 ≈ 17.6% |
| 同上 | 六模块 JVM 全量 | **7666/0/22 skip** 全绿 |
| 同上 | `lintRelease detekt` | 全模块绿 |
| 同上 | G0 / `gen-game-data --check` / `check-agent-instructions` | 零差异 / sha256 校验通过 / 484 引用全绿 |

---

## 3. 旧用例处置表

| 用例 / 文件 | 处置 | 理由 |
|---|---|---|
| `DamageTypeGuardTest.kt`（新） | 新增 | E1 活跃集不含 MAGIC + gate 全组合守卫 |
| `ElementalDamageSystemTest.kt`（新，271 行） | 新增 | E2–E7 / E11 / E12 系统级用例群 |
| `equip_set_bonus_test.cpp` | 改断言 | 六套 0–6 件全档扫描重写（六套套装语义） |
| `single_column_stat_test.cpp` | 改断言 | `InnateTypeDrivesBasicAttackAndSkillOverrides` → `NormalAttackAlwaysPhysicalAndSkillTypeFollowsSkill`（本批语义：普攻恒物理；后续 `dd3705288` 返工为配置驱动并再改此用例） |
| `equipment_db_test.cpp` / `equip_affix_test.cpp` | 改断言 | 六部件结构 / 11 项池与退役段权重·档位 0 |
| `BattleCalculatorTest.kt`（:app） | 改断言 | `generateBattleMessage - magic damage message` → `elemental damage message` |
| `DiffBattleCalculatorTest.kt` | 协议面扩列 | Combatant JSON 两 magic 键替换为五行 10 键（加成/减伤 ×5），12 桶双端同构 |
| `EquipmentSetDatabaseTest` / `EquipmentSetBonusTest` | 改断言 | 六套结构 |
| `EquipmentPowerParityTest` | 改断言 | 占比带重锚 [0.30, 0.45]（E12） |
| `ForgeRecipeDatabaseTest` | 改断言 | 锻造配方 36 条 |
| `TemplateRegistryGuardTest` / `EquipmentSingleSourceGuardTest` / `EquipmentRarityGateTest` / `EquipStatResolverTest` / `BattleExecutionRouterTest` / `data_store_test.cpp` / `battle_calculator_test.cpp` | 改断言 / 结构适配 | 六值化联动（枚举/表形状/ 词条键） |
| bench 三件（accrual / phase_settlement / rest_domain_diff） | 结构适配 + 预存缺陷修复 | 六部位结构适配（armorIds→bodyIds、slot→part）+ DiscipleStore 列长==弟子行数不变量（TimingPerPhase SEGFAULT 根因）——提交说明"顺手修复预存问题①" |
| `manual_db_sample.json` | 样本资源更新 | 功法表 codegen 样本同步 |
| （删除） | **无（0 文件删除）** | — |
| `bench_out.txt`（仓库根，6 行） | **误入库登记** | PhaseSettlementBench 一次性输出转储，违反一次性产物纪律——整改 R4-A6 一并清理 |

---

## 4. 未完成 / 登记

| 项 | 状态 |
|---|---|
| **E9 双端逐位对拍缺口**（验收 A2） | 整改 R4 落地 `DiffElementalDamageTest`（元素场景 Kotlin↔C++ 同场景逐位）——见 `report-R4.md`；桥面零扩展（12 桶 + 六值协议已在 `battle_json.h`/`DiffBattleCalculatorTest` 通达） |
| **A1 UI 类型标签二值** | `HeavenlyTrialBattleDialog.kt:343` ——整改 R1 修复（`innateDamageType.displayName` 六类显示） |
| **A5 bench 断言确定性红** | 整改 R1 改口径（best + P50 双判据）+ R2 三点归因 |
| **E2 语义演进** | 本批普攻 = 恒物理（`innateDamageType` 退役不读取）；`dd3705288` 返工为角色配置驱动（当前名册全物理，行为不变）——验收报告按 HEAD 取证为"实现更优" |
| **在册例外** | EQ-I13 秘境旧堆叠轨（不阻塞）；EQ-I11 装备对拍两缺口（与 E9 同族，本整改不扩面） |

---

## 5. 风险

**已核实**：
- 类型通道 12 桶协议双端同构（Diff 家族全绿 + 协议 12 键逐字段对应）
- 六桶全 0 与装备线基准逐位一致（S19：`single_column_stat_test` 与 `ElementalDamageSystemTest` E6 双证）
- 现役名册全物理 ⇒ 元素通道当前零行为差（E2 数据守卫 + `InnateDamageTypeGuardTest` 双钉）

**推测（待观察）**：
- 78 条功法的五行映射为名义语义归类（雷属金等），实际手感与平衡未经玩家验证
- 元素词条 ×1.5 档位的实战强度未经调优，仅静态区间断言（E12）兜底
