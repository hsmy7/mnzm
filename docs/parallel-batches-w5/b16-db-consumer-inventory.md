# B16（R6.2 数值外置）— `data/` 头文件 DB 消费面枚举清单

> 来源：`docs/parallel-batches-w5/batch-R6B.md` 任务 1（"逐个盘点 `data/` 头文件 DB 的
> **行数、字段、C++ 消费点**（编译期常量表的每处引用），列出清单后动手"）。
> 基线 commit：`65b9d55bf`（B15 验收通过态）。
> 采集方式：`grep -rn`/`grep -rl` 全仓扫描 `include/gamecore/` + `test/`（排除 `build/`），
> 数据行数以「`{` 起始的行字面量」计数（含聚合初始化单行与多行两种形态）。
> 本文件为**动手前的盘点产物**，子项 ① 独立 commit。

## 0. 总览

| # | 头文件 | 行数 | 结构体 | 数据行数 | 消费文件数 | 外置难度 |
|---|---|---|---|---|---|---|
| 1 | `beast_config.h` | 230 | 3（`BeastRealmStats` / `BeastSkillSpec` / `BeastTypeSpec`） | 51 | 3 | 中（含 1 个整型 switch 计算函数） |
| 2 | `beast_material_db.h` | 257 | 1（`BeastMaterialTemplate`） | 192 | 7 | 低（纯表） |
| 3 | `equipment_db.h` | 542 | 1（`EquipmentTemplate`） | 72 | 13 | 低（纯表，**已有生成器先例**） |
| 4 | `herb_db.h` | 163 | 2（`HerbTemplate` / `SeedTemplate`） | 108（54+54） | 8 | 低（纯表，**已有生成器先例**） |
| 5 | `manual_db.h` | 7628 | 1（`ManualTemplate`） | 1620 | 9 | 中（体量大，字段多） |
| 6 | `recipe_db.h` | 1401 | 3（`ForgeRecipeTemplate` / `PillRecipeTemplate` / `PillTemplateSpec`） | 210 | 6 | **高**（表与派生逻辑交织） |
| 7 | `trait_db.h` | 747 | 5（`TalentTemplate` / `PhysiqueTemplate` / `AffixTemplate` / `PositionTplSpec` / `AffixPositionSpec`） | 72 | 6 | **高**（五行/品阶派生规则与表交织） |
| — | **合计** | **10,968** | **16** | **约 2,325** | **52（去重后 21 个文件）** | — |

注：`equipment_db.h` / `herb_db.h` 的「数据行数」由上一轮 `python` 统计口径给出
（144 / 108），其中 `equipment_db.h` 的 144 含 1 行 struct 默认成员初始化与部分多行
聚合初始化被重复计数，**真值以运行时 `equipmentTemplates().size()` 为准**（见
`test/equipment_db_test.cpp`）。下表逐文件的「权威行数」一栏在子项 ② 由守卫脚本
以**实测 size()** 回填。

## 1. `beast_config.h`（230 行 / 3 结构体 / 51 数据行）

**字段**

| 结构体 | 字段 |
|---|---|
| `BeastRealmStats` | `maxHp`(int32) `attack`(int32) `defense`(int32) `speed`(int32) |
| `BeastSkillSpec` | `id`(string) `name`(string) `power`(int32) `cooldown`(int32) `mpCost`(int32) |
| `BeastTypeSpec` | `name`(string) `realm`(int32) `minLevel`(int32) `maxLevel`(int32) |

**数据行构成**：`beastRealmStats(int32 realm)` 为**按 realm 复制的查表函数**（非数组），
内部 9 个 realm × 4 字段；8 个 `xxxSkills()` 函数（tiger/wolf/snake/bear/eagle/fox/
dragon/turtle）各 3–6 条 `BeastSkillSpec`；`beastTypes()` 9 条 `BeastTypeSpec`。

**C++ 消费点（3 文件）**

| 消费文件 | 引用入口 |
|---|---|
| `include/gamecore/system/exploration_tx.h` | `beastRealmStats` / `beastTypes` / `beastTypeByName` |
| `include/gamecore/system/mission_completion.h` | `beastTypes` / `beastTypeByName` / `beastRealmStats` |
| `include/gamecore/system/secret_realm_session.h` | `beastRealmStats` / `*Skills()` |

**外置注意**：`beastRealmStats(realm)` 是**函数式查表**（按 realm 返回 struct），不是
数组访问 ⇒ 外置后需保留同名函数签名，内部改为查 `data_store` 的 map/线性表。
`beastTypeByName` 同理（返回 `const BeastTypeSpec*`，**指针稳定性要求**）。

## 2. `beast_material_db.h`（257 行 / 1 结构体 / 192 数据行）

**字段**：`BeastMaterialTemplate { id, name, type, rarity, tier, basePrice, description }`
（字段以文件内 struct 定义为准，子项 ② 守卫逐字段比对）。

**C++ 消费点（7 文件）**

| 消费文件 | 引用入口 |
|---|---|
| `include/gamecore/system/merchant_settlement.h` | `beastMaterialTemplates` / `beastMaterialsByBeastType` |
| `include/gamecore/system/mission_completion.h` | `beastMaterialById` / `beastMaterialsByBeastType` |
| `include/gamecore/system/production.h` | `beastMaterialTemplates` |
| `include/gamecore/system/secret_realm.h` | `beastMaterialsByBeastType` |
| `include/gamecore/system/secret_realm_session.h` | `beastMaterialTemplates` / `beastMaterialById` |
| `include/gamecore/system/year_settlement.h` | `beastMaterialTemplates` |
| `test/beast_material_db_test.cpp` | 全入口 |

**外置注意**：三个入口均返回**指针**（`beastMaterialById` / `beastMaterialsByBeastType`
返回 `const BeastMaterialTemplate*` / `vector<const T*>`）⇒ 外置后**表容器的地址稳定性
是硬要求**（不得在注入后重新分配）。`beastMaterialsByBeastType` 返回**新 vector**
（每次分配）——保持现状即可。

## 3. `equipment_db.h`（542 行 / 1 结构体 / 72 数据行）

**字段**：`EquipmentTemplate { id, name, slot(string), rarity, physicalAttack,
magicAttack, physicalDefense, magicDefense, speed, hp, mp, critChance(double),
description, price }`。

**已有先例（关键）**：本文件**已由 `scripts/gen-templates.mjs` 从
`scripts/data/equipment_db_sample.json` 生成**（文件头注释："由 scripts/gen-templates.mjs
生成 — 禁止手改（与 Kotlin EquipmentDatabase 同源）"）。生成器已实现「中性源 JSON →
C++ 表」单向产出 + 去重校验 + 测试快照落盘。

**C++ 消费点（13 文件）**

`include/gamecore/data/trait_db.h`（DB 间依赖）、`system/ai_sect_ops.h`、
`system/ai_sect_recruit.h`、`system/auto_gear.h`、`system/inventory_tx.h`、
`system/jade_tx.h`、`system/merchant_settlement.h`、`system/mission_completion.h`、
`system/production.h`、`system/recruit_settlement.h`、`system/secret_realm_session.h`、
`system/year_settlement.h`、`test/equipment_db_test.cpp`。

**唯一入口**：`equipmentTemplates()`（返回 `const vector<EquipmentTemplate>&`）。

## 4. `herb_db.h`（163 行 / 2 结构体 / 108 数据行 = 54 灵草 + 54 种子）

**字段**

| 结构体 | 字段 |
|---|---|
| `HerbTemplate` | `id` `name` `tier` `rarity` `category` `description` |
| `SeedTemplate` | `id` `name` `tier` `rarity` `growTime` `yield` `description` |

**已有先例**：同 `equipment_db.h`，由 `scripts/gen-templates.mjs` 从
`scripts/data/herb_db_sample.json` 生成。

**C++ 消费点（8 文件）**：`data/trait_db.h`（DB 间依赖）、`system/merchant_settlement.h`、
`system/production.h`、`system/secret_realm_session.h`、`system/spirit_field.h`、
`system/year_settlement.h`、`test/herb_db_test.cpp`、`test/spirit_field_test.cpp`。

**入口**：`herbTemplates()` / `seedTemplates()`（引用）+ `herbIdFromSeedId(const string&)`
（**纯字符串派生，与表无关** ⇒ 外置时保持为头文件内纯函数，不进数据文件）。

## 5. `manual_db.h`（7628 行 / 1 结构体 / 1620 数据行）

**字段**：`ManualTemplate`（功法模板，字段含品阶/五行/属性成长等，详见文件 struct 定义；
子项 ② 守卫逐字段比对）。

**C++ 消费点（9 文件）**：`system/ai_sect_recruit.h`、`system/auto_gear.h`、
`system/inventory_tx.h`、`system/merchant_settlement.h`、`system/mission_completion.h`、
`system/recruit_settlement.h`、`system/secret_realm_session.h`、`system/year_settlement.h`、
`test/manual_db_test.cpp`。

**入口**：`manualTemplates()` / `manualById(const string&)`。

**外置注意**：**体量最大（7,628 行 / 1,620 行数据）**，是 JSON 产物体积的主要来源；
`manualById` 返回指针 ⇒ 地址稳定性要求同上。

## 6. `recipe_db.h`（1401 行 / 3 结构体 / 210 数据行）— **高难度**

**字段**

| 结构体 | 说明 |
|---|---|
| `ForgeRecipeTemplate` | 锻造配方（材料表 + 产物 + 时长） |
| `PillRecipeTemplate` | 炼丹配方（药材表 + 产物 + 品阶） |
| `PillTemplateSpec` | 丹药成品规格（属性增益，单/双属性分支） |

**表 ↔ 派生逻辑交织（外置难点）**：本文件除表外还含大量**派生构建函数**
（`buildForgeRecipes` / `buildPillRecipes` / `buildCultivationSpeedPills` /
`buildBattleCritRecipes` / `buildDualAttrBattlePills` / …共 20+ 个 `build*`），
以及**数值格式化/换算纯函数**（`roundToInt` / `formatPercent` / `recipeDurationByTier` /
`tierRarityMinRealm` / `pillGradeName` / `pillTierName` / `realmName` / `breakthroughName`）。
外置时**只外置"表"（配方条目 + 丹药规格），派生函数保持 C++ 逻辑不变**
（派生函数是"逻辑"，不是"数值"——改数值不应触发重编译，但改逻辑**本就应该**重编译）。

**C++ 消费点（6 文件）**：`system/merchant_settlement.h`、`system/mission_completion.h`、
`system/production.h`、`system/secret_realm_session.h`、`system/year_settlement.h`、
`test/recipe_db_test.cpp`。

## 7. `trait_db.h`（747 行 / 5 结构体 / 72 数据行）— **高难度**

**字段**

| 结构体 | 说明 |
|---|---|
| `TalentTemplate` | 天赋模板（含品阶、正负、位置加成） |
| `PhysiqueTemplate` | 体质模板 |
| `AffixTemplate` | 词条模板 |
| `PositionBonus` | 位置加成（`struct`，嵌入 Talent） |
| `PositionTplSpec` / `AffixPositionSpec` | 位置规格 |

**表 ↔ 派生规则交织（外置难点）**：本文件含大量**按五行/品阶展开的构建函数**
（`buildTalentTemplates` / `buildPhysiqueTemplates` / `buildAffixTemplates` /
`buildNegativeTalents` / `buildNegativePhysiques` / `buildPositionTalents` /
`buildOldCultSpeed` / `buildNewBattleHp` / `buildAffixPosition` / …共 40+ 个 `build*`），
以及**配置向量** `CultSpeedConfig` / `BreakChanceConfig` / `LifespanConfig` /
`BattlePctConfig` / `BaseFlatConfig` / `PositionBonusConfig`（这些是**真数值表**），
与格式化纯函数 `formatPercent` / `talentGrade` / `prefixedId`。

**外置边界**：外置「配置向量 + 模板条目」；保留「展开/派生函数 + 格式化纯函数」。

**C++ 消费点（6 文件）**：`system/ai_sect_recruit.h`、`system/appointment_tx.h`、
`system/disciple_factory.h`、`system/disciple_stats.h`、`test/trait_db_test.cpp`、
`test/trait_effects_test.cpp`。

## 8. DB 间依赖（外置顺序约束）

`data/trait_db.h` **同时 include** `data/equipment_db.h` 与 `data/herb_db.h`
（消费装备/灵草条目构建词条与体质）。⇒ 数据文件加载顺序必须满足
**equipment / herb 先于 trait**；若采用单文件聚合 JSON 则天然满足。

## 9. 消费面结论（决定实施形态）

1. **7 个 DB 的消费点全部通过「头文件内 inline 访问器函数」进入**，无任何直接访问
   `kTemplates` 常量的调用点 ⇒ **只改访问器函数体即可外置，52 个消费文件零改动**
   （这是本批最重要的结构性事实：外置的**爆炸半径被既有抽象层收束在 `data/` 目录内**）。
2. **两种既有形态并存**：`equipment_db.h` / `herb_db.h` 已有「中性源 JSON +
   `gen-templates.mjs` 生成」先例；其余 5 个 DB 为**手写编译期常量表**。
   ⇒ 子项 ② 应对**全部 7 个 DB 统一到同一形态**（生成物 + 数据文件），而非只改其中一部分
   （否则双真相源漂移风险）。
3. **指针稳定性是硬约束**：`beastMaterialById` / `manualById` / `talentById` /
   `affixById` / `physiqueById` / `beastTypeByName` 等返回 `const T*`
   ⇒ 注入后表容器**地址不得再变**（一次性注入 + 只读消费，与本批"注入仅初始化期一次"
   的纪律天然一致）。
4. **纯函数与派生逻辑不属于外置面**：`herbIdFromSeedId` / `roundToInt` /
   `formatPercent` / `talentGrade` / 全部 `build*` 派生函数保持 C++ 源码不变
   ⇒ 「改数值不重编译」的边界由此变得清晰可辩护。
