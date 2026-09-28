# 装备系统重构 · 分批实施文档（IMPLEMENTATION-BATCHES）

> 版本：v1.0（2026-09-28）
> 权威方案：`docs/design/equipment-set-system-refactor-plan.md`（**本文档不重复方案内容，只做批次编排与作业规程**）
> 通用作业规程参考：`docs/design/gacha-batches/EXECUTION-PROTOCOL.md`（铁律 4/5/6/7/9/10/11/12/13 为本项目通用条款，本文档只列**装备+属性专属**条款与差异项）
> 依据规范：`rules/design-plan-review.md`（第零节「批次化实施的边界」）、`rules/database-migration.md`、`rules/cpp-priority.md`、`rules/build-quality.md`、`rules/pr-review-checklist.md`

---

## 0. 位置与边界（先看这条）

### 0.1 与方案文档的分工

| 文档 | 职责 |
|---|---|
| `docs/design/equipment-set-system-refactor-plan.md` | **做什么**：需求、现状调查、目标架构、影响范围清单、兼容性、测试方案、风险、盲区 |
| **本文档** | **怎么分批做**：批次划分与依赖、每批的写入面与验收判据、跨批串行约束、交付形态、报告模板 |

### 0.2 完备性声明（回应 `rules/design-plan-review.md` 第零节的边界口径）

该节禁止"把一个方案拆成先做一半、剩下的以后再说"，但**不禁止按批次编排的工程实施**，判据是：**每个批次单独拿出时方案是否残缺**。

本文档的 6 个批次**全部满足**：
1. 每批有独立的**目标 / 写入面（文件级）/ 影响面（存储·UI·C++·测试·经济）/ 兼容性分析 / 验收判据 / 旧用例处置 / 回滚策略 / 报告路径**；
2. 每批合入后**主干可编译、可测、可发布**（判据：§2 门禁全绿），不存在"半成品中间态"；
3. 唯一例外 **B3 被显式声明为原子批**（不可再拆）——因为"删除全部旧装备 + 移除堆叠"会让全仓约 497 个文件的引用面同时断裂，任何拆分都会留下**不可编译**的中间态。B3 内部按 6 个**互不重叠的写面**并行分工，但**合并必须一次完成**。

### 0.3 范围

覆盖方案文档的 R1–R12 需求、D1–D10 缺陷处置、§15 属性机制重构、WP0–WP8 全部工作包。

---

## 1. 本批域铁律（违反即任务未完成）

> 通用条款（Room 禁 DROP COLUMN / ProtoNumber 只增不复用 / ActionId codegen / 配置源单一真源 / UI 不直写 Store / 确定性 RNG / 入库唯一入口 / 测试串行 / detekt baseline 只缩不增 / 注释只描述当前状态 / 不做超范围的事）**直接沿用** `docs/design/gacha-batches/EXECUTION-PROTOCOL.md` §1，不重复。以下是**装备与属性域专属**条款：

| # | 铁律 |
|---|---|
| E1 | **存档编号一次规划、分批落地**：编号分配表在 **B0 冻结**；后续批只允许**使用**已分配编号，禁止临时新增。`DiscipleSurrogate` 新增段：`headId(112)/bodyId(113)/handsId(114)/feetId(115)/legsId(116)/innateDamageType(117)`；复用 `accessoryId(20)`；退役 `weaponId(17)/armorId(18)/bootsId(19)/weaponNurture(24..27)/pillNurtureSpeedBonus(47)/equipmentNurturingCompletionMonth·Phase(98,99)`、`basePhysicalAttack(69)/baseMagicAttack(70)/basePhysicalDefense(71)/baseMagicDefense(72)`、属性方差(62–66 中两项)、`ItemEffect(3,8)`、`SaveData.equipmentStacks(53)`、`EquipmentInstance(3,4,7,10,13,14,15,50–56)`、`EquipmentSlot` 枚举 0–3（`EquipStat` 无退役：新枚举）。 |
| E2 | **`DiscipleSerializer` 是弟子存档装备面**：`EquipmentSet` 不直接进存档 proto（被扁平代理摊平）。任何"改装备字段"的批**只改 `DiscipleSurrogate` + `buildSurrogate`/`withEquipmentUsage*`**；改 `EquipmentSet` 自身不产生存档效果。 |
| E3 | **C++ AUTHORITATIVE + 双守护**：装备/属性的写入（穿卸、升级、分解、词条 roll）走 native 事务 + `applyDirtyFromNative`；**每个 C++ 业务实现必须同时具备 GTest 黄金序列与 JUnit 跨语言对拍**（`DiffEquipmentGenerationTest` / `DiffEquipmentUpgradeTest` / `DiffEquipmentSetBonusTest` / `DiffBattle*`），缺一即该批未完成。 |
| E4 | **静态数据只走 codegen**：装备/套装/词条池/角色模板/配方一律"改 `scripts/data/*_sample.json` → 跑生成器 → 提交产物"；**禁手改** `equipment_db.h` / `equip_set_db.h` / `equip_main_stat_db.h` / `equip_affix_db.h` / `game-data.json`。 |
| E5 | **codegen 幂等是硬门禁（G0）**：`node scripts/gen-templates.mjs` 重跑后 `git diff` **不得出现"删掉 `operator==` / `*TemplatesMutable()`"** 之类的差异（D9 判据）；生成器补全输出与死代码清理在 **B3 同批**完成。 |
| E6 | **装备单一数据源**：`EquipmentRegistry` 只允许作为 `EquipmentDatabase` 的纯转发层，**不得再写入模板字面量**（`EquipmentSingleSourceGuardTest` 源码扫描拦截）；D1 双表漂移在 **B3 同批**闭合（该批会重写这两个文件，分开做等于改两遍）。 |
| E7 | **一行一实例、无堆叠**：装备禁用 `quantity>1` 语义；仓库/储物袋/邮件/结算中**不存在**装备数量>1 的条目；装备**不设硬上限**（仅告警）、**不走溢出邮件**。 |
| E8 | **等级随实例单点**：等级/经验/词条/强化次数**只存 `EquipmentInstance.growth`**；禁止在弟子槽位上另存一份（D2 根因）。穿→卸→穿往返必须逐位保真。 |
| E9 | **属性单列口径（B1 起生效）**：`DiscipleStats`/`Combatant` 只有 `attack`/`defense`；物法之分只走三条通道（普攻 `innateDamageType`、技能 `damageType`、类型增伤/减伤分桶）。旧双列映射 = **取两列之和 × 系数 k**。 |
| E10 | **类型桶默认 0.0 时与旧公式逐位一致**：`physicalDamageBonus/magicDamageBonus`、`physicalDamageReduction/magicDamageReduction` 四个桶在默认值下不得改变既有伤害数值（S19 判据），否则对拍基线不可解释。 |
| E11 | **数值验收按维度拆分**：`EquipmentPowerParityTest` 必须**分维度**断言（攻击/防御/血量/暴击 与 速度/灵力 分开），禁止只看"总战力持平"（否则会掩盖速度/灵力塌陷，§13-13）。 |
| E12 | **每批开工前 `git status` 必须干净**；发现他批/他人在途改动（实测存在并行的"法务/监牢下线"与"自动存档下线"实施流）→ **停手报告，不要叠加**。 |
| E13 | **文档必须入库**：本方案与批次文档曾以**未跟踪文件**存在，被并行实施流的 `git clean` 清除且无 git 历史可恢复。方案/批次/报告文档**必须提交**，不得长期停留未跟踪状态。 |

---

## 2. 门禁命令（每批收尾必跑；判据 = 命令输出原文，不得写"应该通过"）

工作目录 = `C:\Mnzm\XianxiaSectNative\android`。

```powershell
# 1) Kotlin 编译（每批必跑）
.\gradlew.bat compileReleaseKotlin --console=plain

# 2) 全量单测（串行，强制 --max-workers=1）
.\gradlew.bat testReleaseUnitTest --max-workers=1 --console=plain

# 3) 相关模块单测（省时；.so 路径必须是完整绝对路径，-D 单横线）
.\gradlew.bat :core:domain:testReleaseUnitTest :core:engine:testReleaseUnitTest `
  :core:data:testReleaseUnitTest :app:testReleaseUnitTest --max-workers=1 `
  "-Dgamecore.jni.path=C:\Mnzm\XianxiaSectNative\android\core\engine\build\desktop-jni\libgamecorejni.so" `
  --console=plain

# 4) Lint + detekt（baseline 只缩不增）
.\gradlew.bat lintRelease detekt --console=plain

# 5) 桌面 C++（触碰 C++ 的批必跑）
cd app\src\main\cpp\gamecore\build\desktop-test
$env:PATH = "C:\Users\cp050\llvm-mingw\llvm-mingw-20260616-ucrt-x86_64\bin;C:\Users\cp050\llvm-mingw\llvm-mingw-20260616-ucrt-x86_64\x86_64-w64-mingw32\bin;$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin;$env:PATH"
cmake --build . ; ctest
# 返回仓库根：
pwsh -File scripts/build-desktop-jni.ps1     # 桌面 JNI 重建（对拍用）

# 6) codegen 漂移门禁（改静态数据/proto/catalog 必跑）
node scripts/gen-templates.mjs ; git diff --stat -- android/app/src/main/cpp/gamecore/include/gamecore/data android/core/engine/src/test/resources/templates
node scripts/gen-game-data.mjs --check
node scripts/check-jni-count.mjs
node scripts/check-agent-instructions.mjs    # 改 AGENTS.md/rules/docs 后必跑
```

> **G0 判据（装备域专属）**：第 6 条 `gen-templates.mjs` 重跑后，diff **只允许**包含本批有意变更的文件；出现"`*TemplatesMutable()` / `operator==` 被删"即 **D9 回归，立即红**。

---

## 3. 批次总览

### 3.1 依赖图（**串行**，原因见 §3.3）

```
B0 存档编号规划与冻结 ──► B1 属性机制重构 ──► B2 孕养丹退役 ──► B3 装备体系原子替换 ──► B4 数值对齐与验收 ──► B5 文档与发布
   （小·绿灯）              （大·绿灯）            （中·绿灯）        （最大·原子·绿灯）        （中·绿灯）           （小·绿灯）
```

### 3.2 批次一览

| 批 | 名称 | 规模 | 是否碰 C++ | Room 迁移 | 可独立绿灯 | 报告路径 |
|---|---|---|---|---|---|---|
| **B0** | 存档编号规划与冻结守卫 | 小（~5 文件） | 否 | 无 | ✅ | `reports/report-B0.md` |
| **B1** | 属性机制重构（单列 + 类型通道 + 固有伤害属性） | **大（~120 文件）** | 是 | v59→**v60** | ✅ | `reports/report-B1.md` |
| **B2** | 孕养类加成丹药退役（R11）+ 补偿 | 中（~45 文件） | 是 | v60→**v61** | ✅ | `reports/report-B2.md` |
| **B3** | 装备体系原子替换（六部位/套装/升级/词条/删堆叠/删旧装备/D1/D9/D10/UI/迁移/补偿） | **最大（~500 文件）** | 是 | v61→**v62** | ✅（**原子，合并且仅合一次**） | `reports/report-B3.md` |
| **B4** | 数值对齐与验收（40% 占比 / 1 个月满级 / 池权重 / 速度灵力对比） | 中（~20 文件，多为测试） | 是（对拍基线） | 无 | ✅ | `reports/report-B4.md` |
| **B5** | 文档、ADR、双更新日志、债登记、跨版本提示 | 小（~12 文件） | 否 | 无 | ✅ | `reports/report-B5.md` |

> **迁移版本号规则**：上表是**计划映射**；实际执行时**每一批以合入时刻的 `GameDatabaseConfig.DATABASE_VERSION` 为基准 +1**（单一真源），若某批被跳过/延后，后续批顺延取号，**不得预占**。

### 3.3 为什么批次之间基本串行（诚实说明）

| 共享写入面 | 涉及批次 | 结论 |
|---|---|---|
| `DiscipleSerializer.kt`（`DiscipleSurrogate`） | B0 / B1 / B2 / B3 | **必须串行**：同一文件的同一字段区（60–73 段与装备段）会被多批改写 |
| `models.h` / `disciple_store.h` / `column_dirty.h` / `json_codec.cpp` | B1 / B2 / B3 | **必须串行**：三端字段链同 commit 是硬约束 |
| `DiscipleTables*.kt` / `Disciple.kt` / `EquipmentSet` | B1 / B2 / B3 | **必须串行** |
| `GameDatabase.kt` + 迁移文件 | B1 / B2 / B3 | **必须串行**（版本号递增 + `ALL_MIGRATIONS` 注册） |
| `assets/data/game-data.json` + codegen 产物 | B1 / B2 / B3 | **必须串行**（生成器输出单一真源） |
| 战斗对拍基线（`DiffBattle*` 等） | B1 / B3 / B4 | **必须串行**（基线重录一次性） |

⇒ **本重构天然是"串行批次 + 批内并行写面"**。批内并行的实际抓手在 B3（§4.4）。**不要试图让 B1/B2/B3 并行**——共享面覆盖了每一项。

### 3.4 与外部并行实施流的协调（**开工前置，未解决**）

工作树中实测存在另一实施流（法务/监牢下线、自动存档下线；已改 `version.properties`、`CHANGELOG.md`、`models.h`、存档管线），并**抢占过 Gradle 构建产物**（`core/domain/.../classes.jar` 被占用导致测试失败），其 `git clean` **曾清除本方案的未跟踪文档**。本重构的写入面与它在 `models.h`、`CHANGELOG.md`、`docs/knowledge-base.md`、`docs/architecture.md`、存档管线上**重叠**。

**每批开工第一件事**：`git status` + 检查上述共享文件是否被他批占用；命中即 **停手报告**（E12）。

---

## 4. 批次详述（TASKBOOK 级）

> 每批统一按"目标 → 前置 → 写入面 → 影响面 → 兼容性 → 验收判据 → 旧用例处置 → 回滚 → 报告"九项给出。文件路径以方案文档 §四为索引，此处只列**主锚点**。

### 4.1 B0 · 存档编号规划与冻结守卫

| 项 | 内容 |
|---|---|
| **目标** | 把全部编号分配**一次冻结**，后续批只能使用，不得临时新增（E1）。本批**不改任何业务语义**。 |
| **前置** | 无（首批）。`git status` 干净。 |
| **写入面** | ① `core/model/DiscipleSerializer.kt`（新增 `headId(112)…legsId(116)/innateDamageType(117)` 声明与搬运；`17/18/19/24..27/47/69–72/98/99` 就地注释 `reserved`）② `core/data/.../model/SaveData.kt`（`equipmentStacks(53)` 注释 `reserved`）③ **新增** `EquipmentProtoNumberFrozenTest.kt` ④ **新增** 报告目录骨架 |
| **影响面** | 存储：**新增字段声明但不写入/不读取**（旧档读到空值即默认）⇒ 二进制**向后兼容**；UI/C++/经济：无影响。 |
| **兼容性** | 旧档可读（新字段默认值）；新档被旧客户端读时未知字段被 proto 忽略 ⇒ 双向兼容。`EquipmentInstance` 的 `reserved` 注释本批不落地（B3 落）。 |
| **验收判据** | ① `EquipmentProtoNumberFrozenTest` 绿（断言 `DiscipleSurrogate` 装备段/新增段/`reserved` 集合/`SaveData` 的属性→编号映射与冻结表逐条一致，错误消息含操作指引）；② `compileReleaseKotlin` 绿；③ `testReleaseUnitTest --max-workers=1` 全绿（无行为变化）；④ Room schema **零变更**。 |
| **旧用例处置** | 不改任何行为 ⇒ 无删改；仅新增 1 个守卫类。 |
| **回滚** | 直接 revert（无数据副作用）。 |
| **规模** | 小（~5 文件，+150/-10 行）。 |

### 4.2 B1 · 属性机制重构（单列 + 类型通道 + 固有伤害属性）

| 项 | 内容 |
|---|---|
| **目标** | 按方案 §15 把 `物攻/法攻 + 物防/法防` 四列收敛为 `attack/defense` 两列；物法差异改由三条通道承载（普攻 `innateDamageType`、技能 `damageType`、类型增伤/减伤分桶）；旧值映射 = **取两列之和 × 系数 k**（Q7）。 |
| **前置** | B0 完成（编号已冻结）。 |
| **写入面** | ① 领域：`Disciple.kt`(`DiscipleStats`/`ItemEffect`)、`DiscipleComponents.kt`(`CombatAttributes`/`PillEffects`)、`DiscipleSerializer.kt`(60–73 段 + 117)、`CharacterTemplate.kt`(+codegen) ② 数据：`GameDatabaseMigrationsV60.kt`（**新增**）、`GameDatabase.kt`（版本 +1、注册）、schema `60.json` ③ 引擎：`DiscipleStatCalculator*`、`BattleCalculator*`、`DamageZones`、`SectCombatPowerCalculator`、`EnemyGenerator`、`AISectAttackManager`、`HeavenlyTrial*` ④ C++：`disciple.h`/`disciple_stats.h`/`battle.h`/`battle_calculator.h`/`battle_json.h`/`state/models.h`/`disciple_store.h`/`column_dirty.h`/`json_codec.cpp`/`data/beast_config.h` ⑤ UI：弟子面板四行→两行 + 固有属性标签 ⑥ 测试：全部 `DiffBattle*`/`Diff*Settlement*` 基线**一次性重录** |
| **影响面** | 存储：Room `disciples` 4 列 → 3 列（`baseAttack/baseDefense/innateDamageType`）+ ProtoBuf 编号退役/新增；**战斗平衡一次重算**（全部弟子/妖兽/AI/试炼/宗门战）；UI 面板字段变化；经济：丹药/功法数值口径不变（结算层相加），**无源汇变化**。 |
| **兼容性** | ① Migration：`safeDropColumns` 删 4 列 + `ADD COLUMN` 3 列（带 DEFAULT），**禁 DROP COLUMN**；② 旧档回填：`attack = 旧物攻+旧法攻`、`defense = 旧物防+旧法防`、`innateDamageType` 按 `templateId` 从模板取（**幂等**）；③ ProtoBuf：旧编号 `reserved`（禁复用）+ 新编号；④ 跨端：三端字段链同 commit；⑤ 对拍基线重录需在报告中给出"新旧数值对照表"。 |
| **验收判据** | ① `ctest` 全绿（含新增 `single_column_stat_test`）；② `DiffBattle*` / `DiffBattleExecution*` / `DiffSectBattle*` 全绿；③ **S19**：默认桶全 0.0 时伤害与旧公式逐位一致；④ **S20**：`LegacyStatMigrationTest` 断言迁移前后总战力比 ∈ [0.98,1.02]；⑤ **S21**：`InnateDamageTypeGuardTest`；⑥ 全仓符号面 `physicalAttack`/`magicAttack`/`physicalDefense`/`magicDefense` 作为**属性名**归零（`SingleColumnStatGuardTest`）；⑦ `compileReleaseKotlin` + `lintRelease detekt` 绿。 |
| **旧用例处置** | 给"旧用例处置表"：断言四列数值的用例 → 改断言单列和值（附换算依据）；`BattleCalculatorTest` 期望值 → 按新公式重算并注明；不保留"双列断言"。 |
| **回滚** | 代码 revert + **迁移前备份恢复**；**不可逆点**：迁移已执行且用户已存新档 ⇒ 靠备份。 |
| **规模** | 大（~120 文件；其中 ~40 为测试基线）。 |

### 4.3 B2 · 孕养类加成丹药退役（R11）+ 补偿

| 项 | 内容 |
|---|---|
| **目标** | 删除 `nurtureAdd` / `nurtureSpeedPercent` 两类丹药的**全部定义与效果链**，并对玩家已持有资产按方案 §5.7 补偿（幂等、可审计）。**本批不碰装备模型**。 |
| **前置** | B1 完成（共享 `ItemEffect`/`PillEffects`/`DiscipleSurrogate`，必须串行）。 |
| **写入面** | ① 领域：`ItemEffect` 3/8 → `reserved`、`PillEffects.pillNurtureSpeedBonus` 退役、`DiscipleSerializer.kt`(47 → `reserved`) ② 存储：`GameDatabaseMigrationsV61.kt`（**新增**）：`safeDropColumns("disciples","pillNurtureSpeedBonus")` + 清 `unlockedRecipes` 内孕养丹配方 ③ 完整性：**新增** `NurturePillRetirementRule.kt`（幂等标记同事务）+ `SaveValidationRuleRegistry.registerDefaults()` 加一行 ④ 引擎：`PillEffectApplier`/`AutoPillService`/`HpMpRecoveryService`/`DiscipleFacadeImpl战斗Ops2`/`CultivationCore`/`NumericSanitizeRule` 删判据与写入点 ⑤ C++：`data/recipe_db.h`（配方生成段/分类判据/模板映射/类型清单）、`system/pill_system.h`、`system/disciple_tx.h`、`state/models.h`、`merchant_settlement.h`、`secret_realm_session.h`、`data/data_json.h`、`state/column_dirty.h` ⑥ 奖励池：邮件/兑换码/商店/自动购买的孕养丹条目移除 ⑦ UI：相关详情/列表条目移除 |
| **影响面** | 存储：删 1 列 + 道具条目清理；**经济**：新增"补偿发放"这一**纯新增源**（须做额度审计）；产出：奖励池缺口需登记；UI：丹药图鉴/详情少两类。 |
| **兼容性** | 旧档：`ItemEffect(3,8)` 与 `pillNurtureSpeedBonus(47)` 保留声明 + `reserved` ⇒ 可反序列化但**不再读取**（读取点全删）；补偿幂等标记用 proto 预留段编号；合成品/邮件附件中的孕养丹按方案 §5.7 折算。 |
| **验收判据** | ① **S13**：全仓 `grep` 归零（`nurtureAdd`/`nurtureSpeed`/`pillNurtureSpeedBonus`，含 C++ `recipe_db.h`）；② `NurturePillRetirementTest`：折算公式 / 配方回滚 / **幂等** / 单档 2000 万上限截断；③ `RoomMigrationV60ToV61Test` 绿（删列 + 其它数据零丢失）；④ C++ `recipe_db` 断言两类配方零产出；⑤ 经济基线表已登记补偿额度与产出缺口；⑥ 门禁全绿。 |
| **旧用例处置** | 删/改：涉及孕养丹效果与配方的用例（给出清单与处置）。 |
| **回滚** | 代码 revert + 迁移前备份；补偿已发放则**不回滚**（记录为已发生事件）。 |
| **规模** | 中（~45 文件）。 |

### 4.4 B3 · 装备体系原子替换（**不可拆分**）

| 项 | 内容 |
|---|---|
| **目标** | 一次完成：六部位（头/身/手/脚/腿/饰）+ 套装（物理套/法术套，2/4/6 件套）+ 升级 1–30（替换孕养，装卸不改等级）+ 主词条部位池随机 + 3 副词条（7 项池，权重 13/13/14/15/15/15/15）+ **删除全部旧装备** + **移除堆叠** + 产出链/镜像/C++/UI 全量适配 + Room v62 迁移 + 旧装备与旧档折算补偿 + 同批清偿 **D1/D9/D10**。 |
| **前置** | B2 完成（`ItemEffect`/`PillEffects`/`DiscipleSurrogate`/`models.h` 均已稳定）。 |
| **为什么不可拆** | "删除 `EquipmentStack`"与"旧 72 模板作废"会让 **~497 个文件（280 生产 + 217 测试）** 的引用面同时断裂；按层拆（先领域后引擎再 UI）会留下**不可编译**的中间态，违反"每批合入后主干可编译"。因此 B3 按**写面**并行、按**合并**原子。 |
| **6 个并行写面（互不重叠）** | **A 领域+静态数据**（方案 WP1 + WP0 的 `EquipmentInstance` `reserved`：`EquipStat.kt`/`EquipAffix.kt`/`Items.kt`/`DiscipleComponents.kt`/`EquipmentDatabase.kt`/`EquipmentSetDatabase.kt`/`EquipMainStatPool.kt`/`EquipAffixPool.kt`/`EquipmentRegistry.kt`(降级转发)/`ForgeRecipeDatabase.kt`/`EquipmentFactory.kt`/`scripts/data/equipment_db_sample.json`/`scripts/gen-templates.mjs`(补全输出 + 删死代码 → D9/D10)）<br>**B 存档与迁移**（WP2：`GameDatabaseMigrationsV62.kt`/`EquipmentDaos.kt`/`SaveDataReconciler.kt`/`OldSaveFormatDeserializer.kt`/`EquipmentRefRule`/`EquipmentDedupeRule`/`EquipmentValueSanitizeRule`(新)/`EntityCountBoundsRule`/`JsonConverters.kt`/schema `62.json`）<br>**C Kotlin 引擎**（WP3：`EquipmentLevelSystem`/`EquipmentUpgradeService`/`EquipStatResolver`/`DiscipleEquipmentService`/`DiscipleEquipmentManager`/`DiscipleStatCalculator*`/`CaptiveGearUtils`/产出链 10 处/`RngPartition.EQUIPMENT(13)`/镜像 `StateSyncService`/`GameViewMirrorCodec`/`GameViewDiscipleRows`）<br>**D C++**（WP4：`equip_affix.h`/`equip_set_bonus.h`/`equip_main_stat.h`/`equipment_tx.h`(新)/`disciple_tx.h`/`auto_gear.h`/`disciple_stats.h`/`inventory.h`/`ai_sect_ops.h`/`ai_sect_recruit.h`/`rng_manager.h` + 静态表/编解码/分发 + GTest）<br>**E UI**（WP5 + WP7 的 0.2-1/0.2-12/0.2-13：六宫格/套装面板/升级与分解对话框/仓库去角标+筛选排序批量分解/锻造 12 配方/商店/邮件/精灵图 12 张 + `ItemSortUtils` 关注键改实例 id + `Combatant` 装备展示字段六部位化）<br>**F 测试与文档**（217 个装备测试文件对齐 + `Equipment*GuardTest` 新增 + 双更新日志 + ADR） |
| **影响面** | 存储：删 `equipment_stacks` 表 + 重建 `equipment_instances`（v62）+ `disciples` 8 列删/5 列增；静态数据：72 旧模板 → 12 部件 × 6 品阶；C++：装备子系统重写；UI：装备相关 5 类界面；经济：分解回收（新汇）+ 升级消耗（新汇）+ 旧装备折算补偿（新增源）。 |
| **兼容性** | ① 迁移：`DROP TABLE equipment_stacks`、`DROP+CREATE equipment_instances`、`safeDropColumns` 8 列 + `ADD` 5 列、**清空**全部旧装备行与六个部位列（`ACCESSORY` 新旧同名，不清行会复活幽灵件 → 方案 §6.5 A1）；② ProtoBuf：`SaveData(53)` + `DiscipleSurrogate` 17/18/19+24..27 `reserved`、复用 `accessoryId(20)`、新增 112–116；③ 旧资产：100% `basePrice` 折算补偿，单档 1 亿上限，幂等标记同事务；④ 云存档：读档三步中"堆叠重建"仅剩功法；⑤ 镜像协议：`docs/ui-read-surface.md` §2 **先登记再实现**。 |
| **验收判据** | ① 方案 **S1–S8 / S12 / S14 / S17 / S18** 全部达标；② `RoomMigrationV61ToV62Test` + `RoomMigrationTest` 全链 v2→v62 绿；③ `DiffEquipmentGenerationTest`/`DiffEquipmentUpgradeTest`/`DiffEquipmentSetBonusTest` 逐位一致；④ `ctest` 含 `equipment_tx_test`/`equip_affix_test`/`equip_main_stat_test`/`equip_set_bonus_test` 全绿；⑤ `EquipmentSingleSourceGuardTest`（D1）+ `TemplateCodegenIntegrityGuardTest`（D9/D10）+ 门禁 G0；⑥ `EquipmentLevelPersistGuardTest` + `EquipmentSetBonusTest`（0–6 件）+ `EquipmentValueSanitizeRuleTest`；⑦ 217 个装备测试文件全绿；⑧ `lintRelease detekt`（baseline 只缩不增）。 |
| **旧用例处置** | 必须给"旧用例处置表"：按 `EquipmentStack` 删除 / 孕养→升级 / 四槽→六部位 三类分组，逐文件标注"删除 / 改断言 / 保留 + 理由"。 |
| **回滚** | **无运行时开关可关**（方案 §9 I1）；兜底 = 迁移前备份 + 云存档 + 强制更新；**必须在更新日志与登录流程写明"不可回退"**。 |
| **规模** | 最大（~500 文件；其中 ~217 为测试）。 |

### 4.5 B4 · 数值对齐与验收

| 项 | 内容 |
|---|---|
| **目标** | 用可测判据把 B1/B3 的数值落到"装备占比 40%±5% / 一套满级 ≈ 1 个月产出 / 池权重即概率"三条拍板口径上，并交付速度/灵力拆分对比与期望获取成本量化。 |
| **前置** | B3 完成（两侧数值已存在）。 |
| **写入面** | ① `EquipmentPowerParityTest`（**分维度**断言：装备贡献 ∈ 总战力 [35%,45%]，含 2/4/6 件套；速度/灵力单列输出）；② `EquipmentEconomyCalibrationTest`（满级消耗 ÷ 月产出 ∈ [0.75,1.25]）；③ `EquipmentRarityGateTest`；④ `EquipmentStatHotPathBenchmark`（不劣化 >10%）；⑤ 数值表微调：`scripts/data/equipment_db_sample.json`（主词条基数 × `k`）；⑥ **期望获取成本量化报告**（"理想件期望掉落数 / 凑齐一套期望次数"，I9 触发基线，非门禁）；⑦ 速度/灵力塌陷对比报告（决定是否启用备选） |
| **影响面** | 仅数值与测试；静态数据改动需重跑 codegen + `game-data.json`。 |
| **兼容性** | 数值调整**不改存档结构**；调权重时已生成实例的既有词条**不重 roll**（避免玩家资产漂移），只在报告登记差异。 |
| **验收判据** | ① **S9/S16/S17/S18** 达标；② 分维度对比表产出并给出"是否需启用速度/灵力补偿"的结论；③ 期望成本表产出并登记进 I9；④ 门禁全绿；⑤ 数值报告归档 `reports/report-B4.md`。 |
| **旧用例处置** | 改断言：以"总战力持平"为判据的用例改为分维度。 |
| **回滚** | 数值 revert + 重跑 codegen（无存档影响）。 |
| **规模** | 中（~20 文件，多为测试与数据）。 |

### 4.6 B5 · 文档、ADR、双更新日志、债登记

| 项 | 内容 |
|---|---|
| **目标** | 收口：把 R1–R12、D1–D10、I1–I10、R12–R17 的最终状态写进活文档与两个更新日志，并交付 ADR。 |
| **前置** | B4 完成。 |
| **写入面** | ① `CHANGELOG.md` + `android/app/src/main/assets/changelog_entries.json`（**两个一起更新**，玩家版文案通俗无术语、不泄数值）；② `docs/knowledge-base.md`（经济基线表：升级/分解/两笔补偿；子系统索引）；③ `docs/architecture.md`（属性与装备体系描述 + 债 D/I 登记）；④ `docs/cpp-engine.md`；⑤ **新增** `docs/adr/equipment-set-system.md`（含"单列属性"与"删堆叠"两项决策）；⑥ `CODE_WIKI.md`；⑦ `docs/ui-read-surface.md` §2（最终镜像面）；⑧ `docs/threading-contract.md`（若新增跨线程交互则登记，否则声明不涉及） |
| **影响面** | 纯文档 + 玩家可见文案。 |
| **兼容性** | 必须写明"**存档版本不可回退**"（>v59）与补偿公告。 |
| **验收判据** | ① `node scripts/check-agent-instructions.mjs` 全绿（引用无死链）；② 两个更新日志同批更新（漏一个即未完成）；③ ADR 落 `docs/adr/`；④ 债表 I1–I10 全景登记；⑤ `git status` 只含本批改动。 |
| **旧用例处置** | 无。 |
| **回滚** | 文档 revert。 |
| **规模** | 小（~12 文件）。 |

---

## 5. 跨批串行约束（不可违反）

1. `DiscipleSerializer.kt` / `Disciple.kt` / `DiscipleComponents.kt` / `DiscipleTables*.kt`：**B0–B3 逐批串行**，禁止两批同时编辑。
2. `GameDatabase.kt` + 迁移文件 + `ALL_MIGRATIONS`：**逐批串行**，版本号从**运行时实际值 +1**（禁预占）。
3. `models.h` → `DiscipleColumn` → `disciple_store.{h,cpp}` → `column_dirty.h` → `json_codec.cpp` → `gameview_encode.cpp` → `game_view.proto` → JNI → Kotlin 镜像 → Room：**同一 commit 改完**。
4. `scripts/data/*_sample.json` + codegen 产物 + `game-data.json` + `game-data.hash.txt`：**同一 commit**，构建副产物提交前 `git checkout --` 还原。
5. 战斗对拍基线（`DiffBattle*` 等）：**B1 一次性重录**，B3/B4 只做增量（禁各批各录一份）。
6. `execute_dispatch.cpp`：新 ActionId 走 `scripts/action-catalog/*.mjs` + `gen-action-ids.mjs`；`execute_dispatch.cpp` 只加端口认领行。
7. 每批开工前 `git status` 干净；发现他批/他人（含"法务/监牢下线""自动存档下线"两条外部实施流）在途改动 → **停手报告**。
8. **方案/批次/报告文档必须已入库**（E13），不得停留未跟踪状态。

---

## 6. 交付形态（每批必交）

1. **代码**：一次覆盖本批**全部**影响点（UI、存储、测试、旧数据兼容），不留"后续优化"；
2. **测试**：新增/修改单测 + **旧用例处置表**（删除 / 改断言 / 保留 + 理由）+ 本批对应的守卫测试；
3. **报告**：`docs/design/equipment-batches/reports/report-B{0..5}.md`，含——
   - **做了什么**（分类表）；
   - **验证**（门禁**实跑数值**原文，不得写"应该通过"）；
   - **旧用例处置表**；
   - **未完成 / 登记**（含跨批收口项与产品歧义）；
   - **风险**（区分"已核实"与"推测"）；
4. **commit**：中文说明，格式 `feat|refactor|fix|chore(equip|attr|battle|equip-ui|save): Bx <一句话>`；共享五件套（`scripts/data/*_sample.json` / codegen 产物 / `models.h` 链 / 迁移文件 / 对拍基线）**同一 commit**；
5. **工作树只留本批改动**；构建副产物（`atlas-rgba-manifest.json` 等）提交前还原。

### 6.1 汇报格式（回报给父 agent）

```
批次：Bx
commit：<sha> <标题>
门禁：ctest N/N · engine JUnit N/N · domain/data/app 面 · detekt 全绿 · compileReleaseKotlin 绿 · codegen 零漂移（G0）
改动规模：N 文件 +A/-D
迁移：<无 | MIGRATION_MM_NN + RoomMigrationV..Test 结果>
旧用例处置：删除 N / 改断言 N / 保留 N（附清单）
未完成/登记：<逐条>
风险：<逐条，区分已核实与推测>
```

---

## 7. 批次 × 方案条目对照（防漏项）

| 方案条目 | 落在 | 门禁/判据 |
|---|---|---|
| R1 六部位（不设武器位，顺序头/身/手/脚/腿/饰） | B3-A/E | `EquipmentSlotOrderGuardTest` |
| R2 删除全部旧装备 + 补偿 | B3-A/B/F | `RoomMigrationV61ToV62Test` + `EquipmentLegacyCompensationTest` |
| R3 套装 2/4/6 | B3-A/C/D | `EquipmentSetBonusTest` + `equip_set_bonus_test.cpp` + 对拍 |
| R4 升级 1–30 替换孕养 | B3-A/C/D | `EquipmentLevelSystemTest` + `equipment_tx_test.cpp` |
| R5 装卸不改等级 | B3-C/D | `EquipmentLevelPersistGuardTest` + 对拍 |
| R6 移除堆叠 | B3-B/C/D/E | `EquipmentStackRemovalGuardTest` |
| R7/R9/R10 词条与部位池（权重 13/13/14/15/15/15/15） | B3-A + B4 | `EquipAffixPoolTest`/`EquipMainStatPoolTest` + 对拍 |
| R8 物理套 + 法术套 | B3-A | `EquipmentSetDatabaseTest` |
| R11 删除孕养丹 + 补偿 | **B2** | S13 + `NurturePillRetirementTest` |
| R12 属性单列 + 类型通道 | **B1** | S19/S20/S21 + `DiffBattle*` |
| D1 装备双表漂移 | B3-A | `EquipmentSingleSourceGuardTest` |
| D2 孕养等级双源 | B3-A/C | `EquipmentLevelPersistGuardTest` |
| D3 暴击伤害死字段 | B3-C/D | `DiffBattle*` + 暴击伤害用例 |
| D4 孕养 checkpoint 列 | B3-B | 迁移测试 |
| D5 三处价格口径 | B3-A | `EquipmentDatabaseTest` |
| D6 枚举回退值 | B3-B | `JsonConverters` 断言 + 迁移测试 |
| D7 堆叠阈值 | B3-B | S18 |
| D8（非缺陷，已更正） | — | — |
| D9 codegen 非幂等 | B3-A | 门禁 **G0** + `TemplateCodegenIntegrityGuardTest` |
| D10 生成器死代码 | B3-A | 同上 |
| §0.2-1 关注键失效 | B3-E | `ItemSortUtils` 断言（按实例 id） |
| §0.2-2 仓库容量崩塌 | B3-B/E | S18（无上限 + 仅告警 + 不走溢出邮件） |
| §0.2-3 40% 战力占比 | **B4** | S9/S16 |
| §0.2-4 无保底 | B4（量化基线） | 期望成本报告 + I9 触发条件 |
| §0.2-5 品阶受境界约束 | B3-A + **B4** | S17 |
| §0.2-6 满级 ≈ 1 个月 | **B4** | S16 |
| §0.2-7 乘区叠加规则 | B3-A | `EquipmentSetDef` 相加口径断言 |
| §0.2-9 AI 装备口径 | B3-D | `ai_sect_ops_test.cpp` + 宗门战难度回归 |
| §0.2-10 精灵图占位 | B3-E | `EquipmentSpriteGuardTest` |
| §0.2-11 最小 FTUE | B3-E/B5 | 升级界面首次气泡 |
| §0.2-12 战斗实体装备字段 | B3-C/D | `DiffBattle*` |
| §0.2-13 镜像与体积 | B3-C/E + B4 | `CloudPayloadSizeBenchTest` + `ui-read-surface.md` 登记 |
| §0.2-14 死亡/逐出/俘虏六件 | B3-C/D | `InventoryBagTransferTest` 等 |
| I1–I10 债与触发条件 | B5 | 债表登记 |
| R12–R17 风险 | 各批 + B5 | 报告风险节 + 债表 |

---

## 8. 开工检查清单（每批复制执行）

- [ ] 方案文档与本文档**已在 git 库中**（E13；曾被 `git clean` 清除一次）
- [ ] `git status` 干净；无他批/外部实施流在途改动（E12）
- [ ] 本批前置批次已合入且门禁绿
- [ ] `GameDatabaseConfig.DATABASE_VERSION` 实际值已确认（迁移取号基准）
- [ ] 本批写入面与他批**无重叠**（对照 §5）
- [ ] 若触碰 `docs/ui-read-surface.md` 契约面 → **先登记再实现**
- [ ] 若新增 `ActionId` → `scripts/action-catalog/*.mjs` + `gen-action-ids.mjs` 双产物
- [ ] 收尾：§2 门禁全跑 + G0 幂等 + 旧用例处置表 + 报告 + 单 commit（工作树只留本批改动）
