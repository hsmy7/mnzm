# 独立验收报告：装备系统重构 + 五行属性伤害系统

> 验收时间：2026-09-30 19:0x–19:2x（本机）
> 验收人：设计会话（**独立取证，不依赖交付方报告**：所有结论均来自我实跑门禁、读代码实物、核对测试存在性）
> 验收对象：
> ① 装备系统重构 `docs/design/equipment-set-system-refactor-plan.md`（批次 EQ-B0–B5，合并笔 `372925b97`）
> ② 五行属性伤害系统 `docs/design/elemental-damage-system-plan.md`（实现笔 `eaa146afb`）
> 结论摘要：**装备重构 = 有条件通过**；**五行 = 实现忠实但门禁未全绿 + 流程未走批次协议**。**当前不可发布**（见 §4 阻塞项）。

---

## 1. 验收方法与范围

| 手段 | 说明 |
|---|---|
| **实跑门禁** | `check-agent-instructions.mjs`、`ctest`（C++ 全量，含重建）、codegen 幂等性（待补，见 §5） |
| **代码实物核验** | 逐项读实现（枚举/池/套装/gate/伤害通道/迁移），不采信报告结论 |
| **验收标准对账** | 方案 S1–S21（装备）与 E1–E12（五行）逐条判定，按**内容**而非文件名核查测试覆盖 |
| **未跑项** | Kotlin 全量 `testReleaseUnitTest`、`lintRelease detekt`（理由见 §5） |

---

## 2. 实跑门禁结果（原数字）

| 门禁 | 结果 | 备注 |
|---|---|---|
| `node scripts/check-agent-instructions.mjs` | ✅ **全绿** | 规则①根 25026/32768、③引用全解析、④7 个 AGENTS.md 全登记、⑤最坏链路 30555/32768 |
| `cmake --build .`（gamecore desktop-test） | ✅ 构建成功（84/84，1 warning） | 主树 `android/app/src/main/cpp/gamecore/build/desktop-test` |
| `ctest`（全量） | ⚠️ **1528/1529 通过（99%），1 红** | 唯一红：`AccrualSegmentBench.SegmentUnderBudgetAt5000` |
| 该红项复跑 | ❌ **5/5 连续失败**（非噪声） | 详见 §3 A5 |
| codegen 幂等（G0） | ⏳ 未跑 | 见 §5 |

---

## 3. 发现的缺陷与缺口（A1–A6）

### 🔴 A5【阻塞发布】C++ 门禁 1 红，且实测暴露性能余量收窄

`AccrualSegmentBench.SegmentUnderBudgetAt5000` 失败，实测输出：

```
[AccrualSegmentBench] accrue(100ms) D=5000 core:
  best 884.7 us (last 992 us, max 1073 us, samples 18, overBudget 2)
  tel.overBudgetCount     ← 失败点
```

- **判据两条**：① `best < 1000µs`（硬预算）② `tel.overBudgetCount == 0`。**① 通过（884.7 < 1000）**，**② 失败**（overBudget = 2，max 1073µs）。
- **归因**：`overBudget==0` 是**已知脆弱断言**——台账 B9/B10 记录"bench `overBudget==0` 断言噪声脆弱"（B9 移交、未处置），B9 曾做 A/B 对照实验归因环境噪声。
- **但**：本次 **5/5 确定性红**（不是偶发），且 **best 从 B9 的 656–666µs 涨到 884.7µs（+33%）**，余量从约 34% 收窄到 **12%**。
- **判定**：**不是功能回归**（主判据达标），但**是性能退化信号 + 门禁确定性红**。CI 会红 ⇒ **阻塞发布**。
- **建议**：① 立即把断言从"overBudgetCount == 0"改为"best < 预算 且 采样 P95 < 预算"（去掉对单次抖动的敏感性）；② 用 `--rerun` 在**安静窗口**复测 best，确认 884.7µs 里有多少是三次重构（B3 六部位 + 五行六路 + 单列化）的净开销；③ 若净开销确为 +30%，评估是否需在结算热点做缓存（方案 §6.6 已预留"不劣化 >10%"的判据 —— **目前不满足**）。

### 🟠 A1【真实缺陷·中】UI 类型标签仍是二值，未复用单一真源

`android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/HeavenlyTrialBattleDialog.kt:343`

```kotlin
val typeName = if (enemy.innateDamageType == DamageType.MAGIC) "法术" else "物理"
```

- `DamageType` 已有 `displayName`（含"金木水火土"），此处**硬编码二值**：敌人 `innateDamageType` 为元素时会**显示"物理"**。
- **当前不可观测**（名册 6 角色与敌人默认全 `PHYSICAL`），属**潜在缺陷**；一旦配置元素/MAGIC 敌人即显示错误。
- **修法**（1 行）：`enemy.innateDamageType.displayName`。**违反**"单一真源"纪律（装备重构刚立的 E6 同族要求）。

### 🟠 A2【缺口·中·未登记】五行六值缺跨语言端到端对拍

- `DiffElementalDamageTest` **不存在**；`Diff*Test` 中**零命中** `DamageType.FIRE/METAL/...`。
- 协议面已支持（`battle_json.h:34/103/61/144` 传 `damageType`/`innateDamageType`），C++ 有 GTest（`single_column_stat_test.cpp`、`equip_set_bonus_test.cpp`），Kotlin 有单测（`ElementalDamageSystemTest`）⇒ **覆盖是"两侧各自测"，不是"同场景逐位对拍"**。
- 与 **EQ-I11** 同族（装备生成/套装结算的端到端逐位缺口），但**元素侧未单独登记** ⇒ 建议登记为 **EQ-I17**。
- 影响：六值类型判定/gate/六桶减伤的"跨端一致性"只能靠单侧测试推定，任一侧算法漂移不会被对拍捕获。

### 🟠 A3【流程缺口·中】五行实现未走批次协议

| 项 | 装备线 | 五行线 |
|---|---|---|
| 派发件 | `batch-EQ-B0..B5.md` ✅ | **无** |
| 批次报告 | `report-B0..B5.md`（7 份）✅ | **无** `report-E*.md` |
| 看护亲验 | 6 批全部 `accepted` ✅ | **无台账登记** |
| 提交方式 | 按批串行 + 看护核验 | **直接提交主干 `eaa146afb`** |

- 后果：交付方**没有"实跑门禁原数字"可核对**，验收只能靠我逐项取证（本次已做）。
- 建议：补一份 `report-E1.md`（含 §2 的门禁原数字与旧用例处置表），并在台账登记；**否则该批在流程上等于未验收**。

### 🟡 A4【历史遗留·已登记】秘境背包仍是旧堆叠轨

- `android/core/engine/src/main/java/com/xianxia/sect/core/engine/domain/exploration/SecretRealmRuinsResolver.kt:145` 仍构造 `EquipmentStack`（代码注释自述"B3 过渡…待 B4"），但 **B4 已 accepted 且未清**。
- 现登记为 **EQ-I13**（`docs/architecture.md:279`，含触发条件与白名单说明）⇒ 属**登记在册的例外**，非隐瞒遗漏。
- 影响：**R6/S6「无堆叠」为有条件通过**（秘境背包轨未迁实例；守卫测试白名单在册）。

### 🟡 A6【仓库卫生·中】`.clash-repair/` 约 70+ 临时文件已入库

- `git ls-files .clash-repair` 列出 `_*.txt`（60+）、`edit*.py`、`_head_heavy*.kt`、`c-root-acl.*.txt` 等**临时诊断脚本与转储**。
- 违反用户公约 13（清理一次性代码）；**归属大概率不是装备/五行线**（与 Clash/推送通道修复相关），但**在库中**。
- 建议：确认无引用后 `git rm -r .clash-repair` 并加 `.gitignore`。

---

## 4. 验收判定表

### 4.1 装备系统重构（方案 §1.2 S1–S21）

| 标准 | 判定 | 依据（我方取证） |
|---|---|---|
| S1 无孕养/堆叠语义 | 🟡 **有条件通过** | 剩余命中全为**豁免类**（迁移 `GameDatabaseMigrationsV64`、旧档读表 `EquipmentLegacyTableReader`、补偿规则、守卫测试自身）；**例外 EQ-I13**（秘境旧轨，已登记） |
| S2 装卸保真 | ✅ 通过 | `EquipmentLevelPersistGuardTest` 存在 |
| S3 套装 2/4/6 | ✅ 通过 | `EquipmentSetBonusTest` + `equip_set_bonus_test.cpp` 存在；6 套 id 实测齐全（lietian/gengjin/qingmu/xuanshui/lihuo/houtu） |
| S4 等级 1–30 | ✅ 通过 | 随 B3 交付（报告 B3 §…） |
| S5 部位池/词条池 | ✅ 通过 | `EquipMainStatPoolTest` 存在；**池实测 11 项**、权重 12/12/13/13/13/7/6/6/6/6/6 |
| S6 无堆叠 | 🟡 **有条件通过** | `EquipmentStackRemovalGuardTest` 在册；例外 **EQ-I13** |
| S7 旧档升级 | ✅ 通过 | `RoomMigrationV63To64Test` + 全链 `RoomMigrationTest`（迁移链 V2→V64 实测 22 个测试文件） |
| S8 编号冻结 | ✅ 通过 | `EquipmentProtoNumberFrozenTest` 存在 |
| S9/S16 数值占比 | ✅ 通过 | `EquipmentPowerParityTest`、`EquipmentEconomyCalibrationTest` 存在（B4 报告：5/0/0、4/0/0 亲跑） |
| **S10 双端确定性** | ❌ **部分未达成** | `DiffEquipmentUpgradeTest` ✅；**`DiffEquipmentGenerationTest` / `DiffEquipmentSetBonusTest` 实测缺失**（与 EQ-I11 登记一致） |
| **S11 门禁全绿** | ❌ **未通过** | **ctest 1528/1529（1 红，见 A5）**；Kotlin 全量/detekt 未跑（§5） |
| S12 无死字段 | ✅ 通过 | `EquipmentSingleSourceGuardTest` 存在；`EquipmentRegistry` 降级为转发 |
| S13 孕养丹归零 | ✅ 通过 | 剩余命中**全为豁免**：`NurturePillRetirementRule`（补偿规则）、`GameDatabaseMigrationsV63`（删列）、`OldSerializableSaveData`（旧档兼容字段）、测试 |
| S14 不提供速度/灵力 | ✅ 通过 | `EquipStat` 实测 **13 项**（无 SPEED/MP；含 `MAGIC_DAMAGE_PCT` 退役段编号 8） |
| S15 部位池/顺序单一真源 | ✅ 通过 | `EquipmentSlotOrderGuardTest` 存在 |
| S17 品阶门槛 | ✅ 通过 | `EquipmentRarityGateTest` 存在 |
| S18 无硬上限 | ✅ 通过 | `EquipmentNoCapGuardTest` 存在 |
| S19 单列化 | ✅ 通过 | `SingleColumnStatGuardTest` 存在；`DamageZones.typeDamageBonus/typeDamageReduction` **默认 0.0 时可与基准逐位一致**（注释与 `ElementalDamageSystemTest` E6 用例双证） |
| S20 旧值映射 | ✅ 通过 | `RoomMigrationV61To62/62To63/63To64Test` 覆盖 `baseAttack` 旧值映射（无同名测试类，但覆盖存在） |
| S21 固有属性 | ✅ 通过 | `InnateDamageTypeGuardTest` 存在 |
| codegen 幂等（G0） | ⏳ 未跑 | §5 |

### 4.2 五行属性伤害（方案 §1.2 E1–E12）

| 标准 | 判定 | 依据 |
|---|---|---|
| E1 恰好 6 值 | ✅ 通过 | 实测：6 **活跃**值（PHYSICAL+五行）+ `MAGIC` 退役段（注释"仅旧档兼容保留，禁新产出"）；`element` 映射与 `SpiritRoot.TYPES` 一一对应；`displayName` 六类齐备；`DamageTypeGuardTest` 断言 `ACTIVE`/`ELEMENTAL` 不含 MAGIC |
| E2 普攻类型 | ✅ 通过（实现更优） | `BattleCalculator:412-413` 普攻走 `attacker.innateDamageType`（**配置驱动**，非硬编码物理）；名册 6 角色实测全 `PHYSICAL`；`ElementalDamageSystemTest` 有 E2 两例（全物理 + 非物理可配置） |
| E3 技能元素由功法定 | ✅ 通过 | 技能走 `skill.damageType`；`ElementalDamageSystemTest` 有 E3 用例（同功法不同弟子属性一致） |
| E4 灵根 gate | ✅ **通过（逐条符合方案）** | `Disciple.elementGate(element)`：`null→1.0`（物理不受 gate）、含→`1.0`、不含/空串/未知→`0.0`。**纯开关、不做线性折算**，与方案 §3.3 逐条一致；五路折算在 `属性Ops3:318-322`；`ElementalDamageSystemTest` 有 E4 全组合用例 |
| E5 类型隔离 | ✅ 通过 | `ElementalDamageSystemTest` E5（六类各只作用对应类型） |
| E6 六桶减伤 | ✅ 通过 | `ElementalDamageSystemTest` E6（互不串扰 + 全 0 与基准逐位一致） |
| E7 六套套装 | ✅ 通过 | `ElementalDamageSystemTest` E7（0–6 件 + 跨套不串扰）；C++ `equip_set_bonus_test.cpp` |
| E8 池 11 项 | ✅ 通过 | `EquipAffixPool` 实测 11 项 + 权重合计 100；`DAMAGE_PCT_TIERS = 0.006…0.030`（**×1.5 补偿已落地**）；`EquipAffixPoolTest` 有"退役段权重/档位为 0"断言 |
| **E9 双端逐位对拍** | ❌ **未达成** | 无 `DiffElementalDamageTest`；`Diff*Test` 零命中元素（见 **A2**，未登记） |
| **E10 门禁全绿** | ❌ **未通过** | ctest 1 红（**A5**） |
| E11 物理路径不回退 | ✅ 通过 | `ElementalDamageSystemTest` E11（物理词条不受 gate） |
| E12 元素词条效率区间 | ✅ 通过 | `ElementalDamageSystemTest` E12（区间断言） |
| （未在方案内）UI 六类显示 | ❌ **缺陷** | **A1**：`HeavenlyTrialBattleDialog:343` 二值标签 |

---

## 5. 未跑项与理由（诚实登记）

| 未跑 | 理由 | 建议 |
|---|---|---|
| Kotlin 全量 `testReleaseUnitTest --max-workers=1` | 单次约 10+ 分钟，且与 C++ 重建争抢资源；交付方报告称已亲跑（B1 1481/1481、B3 1521/1521、B4 定向 5/0/0+4/0/0） | 安静窗口由看护复跑一次，入台账 |
| `lintRelease detekt` | 同上 | 同上 |
| codegen 幂等（G0） | 需跑 `gen-templates.mjs` 后比对（会改工作树，须随后还原） | 下一轮验收补 |
| `build-desktop-jni.ps1` + `Diff*` 定向 | 需 JNI 重建；本批元素对拍本就不存在（E9） | 与 A2 一并处置 |

---

## 6. 结论与放行条件

**总体：有条件通过。**

| 对象 | 交付质量 | 阻塞项 |
|---|---|---|
| 装备重构 B0–B5 | **高**：六批全部走完派发→报告→亲验；测试实物齐备；测试命名与方案标准**可追溯**；未达成项（S10 对拍缺口、秘境旧轨）**诚实登记在册**（EQ-I11/I13 等 6 项） | **S11 门禁红（A5）** |
| 五行属性伤害 | **实现忠实度高**：门禁语义（gate 开关 1.0/0.0）、六值、六套、11 项池、×1.5 档位、类型桶默认 0 保位一致 —— 与方案**逐条对得上**；且把验收用例命名为 E2–E12（可追溯，超出方案要求的可维护性） | **E10 门禁红（A5）**、**E9 对拍缺口（A2）**、**A1 UI 缺陷**、**A3 无报告** |

### 放行前必须处置（按优先级）

1. **A5** 修 `overBudgetCount == 0` 脆弱断言（改 best/P95 口径）→ 门禁转绿；并归因 +33% 性能退化的净开销。
2. **A1** `HeavenlyTrialBattleDialog:343` 改用 `DamageType.displayName`（1 行）。
3. **A2** 补元素端到端双端对拍，或登记为 **EQ-I17**（与 EQ-I11 同族）。
4. **A3** 补 `report-E1.md` + 台账登记（否则五行批在流程上未验收）。
5. **A6** 清理 `.clash-repair/`（确认无引用后删除并入 `.gitignore`）。
6. **A4**（非阻塞）EQ-I13 在后续触秘境链的批次收口。

### 附：本报告未改动任何代码与既有文档
仅新增本文件；验收过程只做只读取证（`git grep`/`git ls-files`/读文件/`ctest`）。
