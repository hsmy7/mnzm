# 装备四部位化改造方案（6 部位 → 4 部位；套装改 2/4 两档）

> 版本：v1.0（2026-10-02）
> **需求**：装备系统改为**四件套**，部位仅 **头部 / 身体 / 手部 / 脚部**（删除武器、腿部）。
> **性质**：对**已实现但未上线**的装备系统做收缩型重构。因未上线 ⇒ **无存档迁移、无存量补偿**（用户明确"装备改动还未上线，无产出的装备"；且现行规范为测试期 destructive rebuild）。
> 依据规范：`rules/design-plan-review.md`、`rules/database-migration.md`、`rules/cpp-priority.md`、`rules/testing.md`、`rules/static-resources.md`、`rules/code-comment.md`

---

## 〇、决策留档（本会话已闭环，实施时无需再问）

| # | 问题 | 用户拍板 | 影响 |
|---|---|---|---|
| Q1 | 套装档位 | **2 件套 + 4 件套**（两档） | 删除 6 件套档；原 6 件套效果并入 4 件套档（§3.2） |
| Q2 | 武器 / 腿部 | **彻底删除** | 部位、实例、配方、掉落、存档字段、词条池、C++ 六处全链清理 |
| Q3 | 数值口径 | **维持装备占总战力 40%±5%，单件加成上调** | 引入单一强度系数（§3.3），由数值校准批定标 |
| Q4 | 套装数量 | **仍 6 套 × 4 件 = 24 部件** | 精灵图 36 → **24 张** |
| Q5 | 存量装备 | **未上线、无存量** ⇒ 不补偿、不迁移 | 直接删字段 + destructive rebuild |

---

## 一、需求转译与成功标准

### 1.1 需求转译

| # | 原话 | 工程口径 |
|---|---|---|
| P1 | "部位为头部、身体、手部、脚部" | `EquipmentSlot` 由 **6 值收缩为 4 值**（HEAD/BODY/HANDS/FEET）；`WEAPON(14)`/`LEGS(15)` **退役 reserved**，编号**禁复用** |
| P2 | "改为四件套套装" | 套装件数 = 4；**档位由 2/4/6 改为 2/4**；原 6 件套效果并入 4 件套档 |
| P3 | （隐含）部件总量 | 6 套 × 4 件 = **24 部件**（原 36）；精灵图 36 → **24** |
| P4 | （隐含）数值守恒 | 装备总贡献仍 **40%±5%**；因件数 6→4，单件加成**上调**（单一强度系数，§3.3） |

### 1.2 成功标准（可验证）

| # | 标准 | 验证方式 |
|---|---|---|
| F-1 | `EquipmentSlot` 恰 **4 个活跃值**，`WEAPON`/`LEGS` 退役且编号不复用 | `EquipmentSlotOrderGuardTest`（枚举值 + 顺序 + 退役断言） |
| F-2 | 全链**无武器/腿部残留**（除退役注释与 `reserved` 声明） | 符号面 grep 归零 + `EquipmentSlotRetirementGuardTest` |
| F-3 | 套装档位恰 **2 档（2/4）**；6 件套不可达且无死代码 | `EquipmentSetBonusTest` + C++ `equip_set_bonus_test.cpp` |
| F-4 | 部件总数 **24**（6 套 × 4 部位 × 6 品阶 = 144 条装备实例定义） | `equipment_db_test.cpp` + 中性源条目计数 |
| F-5 | 装备占总战力 **40%±5%**（含套装效果） | `EquipmentPowerParityTest`（数值校准批产出报告） |
| F-6 | 锻造/掉落/商店/任务奖励**不再产出**武器/腿部 | `ForgeRecipeSlotGuardTest` + 掉落表守卫 |
| F-7 | 弟子槽位字段恰 4 个；存档 schema 变更同 commit 更新 `MigrationRequiredGuardTest.BASELINE_ENTITIES` | 该守卫 + `DestructiveRebuildBaselineTest` |
| F-8 | 双端确定性：4 部位槽位/套装 2/4 档/装备汇总的 Kotlin 与 C++ 逐位一致 | `ctest` + `Diff*` 家族全绿 |
| F-9 | 门禁全绿 | `ctest`、Kotlin 全量 `--max-workers=1`、`lintRelease detekt`、codegen 零漂移、`check-agent-instructions.mjs` |
| F-10 | 24 张精灵图齐备（或程序化占位兜底） | `SpriteResRegistry` 注册面 + 缺图兜底 |
| F-11 | 双更新日志已更新（`CHANGELOG.md` + `changelog_entries.json`） | 人工核对 |
| F-12 | 装备战力占比回归：`EQUIP_POWER_SCALE` 单点可调，调一处即可重定标 | 系数为命名常量、单一引用点 |

---

## 二、现状调查（实测，2026-10-02）

| 项 | 现状 | 出处 |
|---|---|---|
| 部位枚举 | **6 值**：`HEAD(10)/BODY(11)/HANDS(12)/FEET(13)/WEAPON(14)/LEGS(15)`；编号 0..3 为更早退役段（reserved） | `Items.kt:265-273` |
| 部位显示名 | 头部/身体/手部/脚部/武器/腿部 | `Items.kt:275-281` |
| 套装档位 | **硬编码三档**：`if (count >= 2) … / >= 4 … / >= 6 …` | `EquipmentSetDatabase.kt:43-45` |
| 套装数量 | **6 套**：`lietian`（物理）/`gengjin`（金）/`qingmu`（木）/`xuanshui`（水）/`lihuo`（火）/`houtu`（土） | `EquipmentSetDatabase.kt:62-102` |
| 部件数据 | 中性源 `scripts/data/equipment_db_sample.json` 共 **36 条**（6 部位各 6 条 = 6 套 × 6 件） | 实测分组计数 |
| 部位主词条池 | 6 部位各有池；系数 `HEAD 1.00 / BODY 1.00 / HANDS 1.15 / FEET 0.95 / WEAPON 1.15 / LEGS 0.95` | `EquipMainStatPool.kt:25-44` |
| 副词条池 | **11 项**（攻击力/防御力/血量/暴击率/暴击伤害/物理伤害加成/金/木/水/火/土），权重合计 100 | 前批实测 |
| 弟子槽字段 | `headId/bodyId/handsId/feetId/weaponId/legsId` **6 个** + `EquipmentSlot ↔ 字段` 映射 + 清空逻辑 | `DiscipleComponents.kt:115-157` |
| 锻造配方 | `recipe_db.h` 中 **25 处**武器/腿部引用 | 实测 |
| C++ 引用面 | `models.h`/`disciple_store.h`/`column_dirty.h`/`disciple_stats.h`/`auto_gear.h`/`mission_completion.h`/`ai_sect_*`/`json_codec.cpp`/`gameview_encode.cpp` 等 | 实测 |
| 存档编号 | 部位段 `112..116`（head/body/hands/feet/legs）+ `innateDamageType(117)`；**`weaponId(17)` 为复用锚定** | 前批 B0 方案 |
| 测试期迁移策略 | **不写迁移**：`fallbackToDestructiveMigration(dropAllTables=true)`，`DeadCompatRemovalGuardTest` 禁 `MIGRATION_N_M` 回流 | 根 `AGENTS.md` §5 7.1/7.2 |

---

## 三、目标架构

### 3.1 `EquipmentSlot`：6 → 4 值

```kotlin
enum class EquipmentSlot {
    // 编号 0..3 与 14/15 为退役段（更早的 WEAPON/ARMOR/BOOTS/ACCESSORY 与
    // 本轮的 WEAPON/LEGS）：保留 reserved 语义，禁复用。
    @ProtoNumber(10) HEAD,    // 头部
    @ProtoNumber(11) BODY,    // 身体
    @ProtoNumber(12) HANDS,   // 手部
    @ProtoNumber(13) FEET;    // 脚部
}
```
- **`WEAPON(14)` / `LEGS(15)` 退役**：枚举值删除、编号 reserved、不复用（防旧档把"武器"解成其它部位）。
- `displayOrder` = `listOf(HEAD, BODY, HANDS, FEET)`（4 项，单一真源）。
- UI 展示序、配装界面、套装件数统计、守卫遍历全部随之收敛为 4。

### 3.2 套装档位：2/4（6 件套效果并入 4 件套）

**口径 = 满套强度守恒**（保证 §3.3 的 40%±5% 中"套装部分"不变）：

| 档次 | 现在（6 部位） | 改后（4 部位） |
|---|---|---|
| 2 件套 | 本系伤害 **+10%** | 本系伤害 **+10%**（不变） |
| 4 件套 | 暴击率 **+12%** | 暴击率 **+12%**（不变） |
| 6 件套 | 本系伤害 **+20%** | **并入 4 件套档**：本系伤害 **+20%** |

⇒ 4 件套档同时给 **本系伤害 +20% + 暴击率 +12%**；**满套合计 = 本系 +30% + 暴击率 +12%**，与原 6 件满套**完全一致**。

- 实现：`resolveSetBonus` 的 `count >= 6` 分支**删除**（不留死代码）；`>= 4` 分支同时追加 `bonus4` 与 `bonus6` 两条效果。
- 数据：`EquipmentSetDatabase` 6 套的 `bonus6` 字段保留语义但**改为在 4 件时触发**（或重命名 `bonusFull`，见 §3.4 命名口径）。

### 3.3 单件数值上调：单一强度系数

**问题**：装备总贡献 = `Σ(单件主词条 + 单件副词条) + 套装效果`。件数 6 → 4 后，若单件不变，装备总占比会掉到约 27%（破坏 40%±5%）；而套装部分按 §3.2 守恒不变。

**方案**：在**装备汇总的单一入口**引入命名常量强度系数，把"件数补偿"集中在一处：

```
kEquipPowerScale = 1.5      // 6 件 → 4 件的等效补偿（初值，由数值校准批定标）
effective = floor(raw × kEquipPowerScale)
```

| 项 | 说明 |
|---|---|
| 落点 | `EquipStatResolver`（Kotlin）与 `disciple_stats.h`（C++）**同一语义**，均引用该常量的镜像值（Kotlin `const val` / C++ `constexpr`，双侧同值由守卫断言） |
| 为什么不用改两张数值表 | 主词条基数表 + 副词条档位表分别 ×1.5 需改两处且易漂移；单系数**单点可调**、可回归（F-12） |
| 副词条池 | **11 项不变**（权重不变；仅档位效果经系数放大） |
| 主词条池 | 部位池 6 → **4**（删武器池/腿部池）；部位系数表 6 → 4（§3.4） |
| 定标 | 数值校准批按 `EquipmentPowerParityTest` 判定 40%±5%；系数初值 1.5 |

### 3.4 部位主词条池与系数（删除武器/腿部）

| 部位 | 主词条池 | 部位系数 |
|---|---|---|
| 头部 | 血量 / 防御力 | 1.00 |
| 身体 | 防御力 / 攻击力 / 暴击率 / 暴击伤害 | 1.00 |
| 手部 | 攻击力 / 暴击率 / 暴击伤害 | 1.15 |
| 脚部 | 防御力 / 血量 / 暴击率 / 暴击伤害 / 攻击力 | 0.95 |
| ~~武器~~ | 池删除（输出向池退役） | — |
| ~~腿部~~ | 池删除 | — |

- 武器池（攻击力/暴击率/暴击伤害）**整体退役**；其"输出向"定位由**手部**（系数 1.15，同样 3 词条）承接 —— 这是删除武器后保持输出流派可配性的关键。
- 腿部池（5 词条）退役；其"均衡向"由**脚部**（系数 0.95）承接。

### 3.5 套装与部件命名（24 件）

| # | 套装 | id | 部位命名（头/身/手/脚） |
|---|---|---|---|
| 1 | 裂天罡煞（物理） | `lietian` | 头冠 / 重铠 / 战手 / 战靴 |
| 2 | 庚金白虎（金） | `gengjin` | 灵冠 / 法袍 / 灵护 / 云履 |
| 3 | 青木长生（木） | `qingmu` | 同上后缀表 |
| 4 | 玄水寒渊（水） | `xuanshui` | 同上 |
| 5 | 离火焚天（火） | `lihuo` | 同上 |
| 6 | 厚土镇岳（土） | `houtu` | 同上 |

⇒ 24 个名字 = 物理套 4 个既有名 + 5 元素套 × 统一后缀表（灵冠/法袍/灵护/云履）。

### 3.6 副词条池与属性通道（不受本次改动影响）

- 副词条池 **11 项**、权重合计 100、类型词条档位值（含 ×1.5 补偿）**均不变**。
- 五行类型通道（`DamageType` 6 活跃值 + 灵根 gate + 6 桶减伤）**不变**：部位收缩不影响伤害侧。
- `EquipStat` 枚举（13 项，含 `MAGIC_DAMAGE_PCT` 退役段）**不变**。

### 3.7 精灵图：36 → 24 张

- 命名沿用 `<setId>_<slot>`：如 `lietian_head`、`gengjin_body` … `houtu_feet`。
- 已产出的 `*_weapon` / `*_legs` 精灵图**删除**（含注册项与图集清单，防死引用）。
- 走 `rules/static-resources.md` 7 步流程；缺图仍由程序化占位兜底。

### 3.8 外围系统清理（武器/腿部彻底删除）

| 系统 | 处置 |
|---|---|
| 锻造配方 | 删除武器/腿部配方（`recipe_db.h` 25 处引用 + 中性源配方条目）；剩余配方只产 4 部位 |
| 掉落表 | 扫描并删除武器/腿部产出（`mission_completion.h` 等） |
| 商店 / 兑换 / 活动奖励 | 同上（含 `RedeemCode` 若涉及） |
| AI 配装 | `auto_gear.h` / `ai_sect_ops.h` / `ai_sect_recruit.h` 的 6 槽位改 4 槽位 |
| 弟子面板 / 装备界面 | 部位展示 6 → 4；空槽位提示 4 个 |
| 排序 / 筛选 / 背包 | `ItemSortUtils` 等按部位的分组改 4 |

### 3.9 存档与字段（无迁移）

| 项 | 处置 |
|---|---|
| `DiscipleComponents` 槽字段 | `weaponId` / `legsId` **删除**（测试期可直接删字段；老库由 destructive rebuild 重建） |
| `EquipmentSlot ↔ 字段` 映射 / 清空逻辑 | 6 → 4 分支 |
| 存档编号 | `legsId(116)` 退役 reserved；**`weaponId(17)` 一并退役**（其"复用锚定"随武器删除失去对象）；`112..115` 保留；`117 innateDamageType` 不变 |
| Room schema | 版本递增 + **同 commit 更新 `MigrationRequiredGuardTest.BASELINE_ENTITIES`**（根 `AGENTS.md` 7.1 硬要求） |
| 迁移 | **不写**（测试期）；禁 `MIGRATION_N_M` 回流（`DeadCompatRemovalGuardTest`） |
| 存量补偿 | **不需要**（未上线、无产出装备） |

### 3.10 C++ 对偶

| 文件 | 变更 |
|---|---|
| `state/models.h` | 弟子 6 槽位结构 → 4；装备实例部位取值域 6 → 4 |
| `state/disciple_store.h` / `column_dirty.h` | 槽位列与脏列 6 → 4 |
| `system/disciple_stats.h` | 装备汇总 4 部位 + `kEquipPowerScale` 镜像 |
| `system/auto_gear.h` | 自动配装 4 部位 |
| `system/mission_completion.h` / `ai_sect_*.h` / `disciple_tx.h` / `death_handler.h` | 掉落与配装清理 |
| `src/json_codec.cpp` / `src/gameview_encode.cpp` | 序列化与视图编码 4 部位 |
| `data/equip_main_stat_db.h` / `equipment_db.h` / `recipe_db.h` | 24 部件 + 配方收敛（**禁手改生成物**，走 codegen） |
| `test/*` | §6 清单 |

---

## 四、影响面清单（按写入面分组）

| WP | 范围 | 代表文件 |
|---|---|---|
| **F-WP1 领域与静态数据** | 枚举 / 池 / 系数 / 套装表 / 中性源 / codegen | `Items.kt`、`EquipMainStatPool.kt`、`EquipmentSetDatabase.kt`、`EquipmentDatabase.kt`、`EquipAffixPool.kt`（仅引用）、`scripts/data/equipment_db_sample.json`、`scripts/gen-templates.mjs` 产物 |
| **F-WP2 弟子与存档** | 槽字段、映射、序列化、schema 基线 | `DiscipleComponents.kt`、`DiscipleEquipment.kt`、`DiscipleDelegates.kt`、`DiscipleSerializer.kt`、`NullSafeProtoBuf.kt`、`MigrationRequiredGuardTest` |
| **F-WP3 引擎（Kotlin + C++）** | 结算、汇总、套装判定、双端对偶 | `EquipStatResolver.kt`、`DiscipleStatCalculator属性Ops3.kt`、C++ `models.h`/`disciple_store.h`/`disciple_stats.h`/`column_dirty.h`/`json_codec.cpp`/`gameview_encode.cpp` |
| **F-WP4 外围系统** | 锻造 / 掉落 / 商店 / AI 配装 / 排序筛选 | `recipe_db.h`、`mission_completion.h`、`auto_gear.h`、`ai_sect_*.h`、`ItemSortUtils.kt`、`EquipmentRefRule.kt`、`EquipmentDedupeRule.kt` |
| **F-WP5 UI 与资源** | 面板部位 4 项 / 24 精灵图 / 文案 | 装备界面、弟子面板、`SpriteResRegistry`、图集清单 |
| **F-WP6 测试与文档** | 守卫更新 + 数值校准 + 文档/ADR/双日志 | §6 清单、`docs/architecture.md`、`docs/knowledge-base.md`、`docs/cpp-engine.md`、`CHANGELOG.md`、`changelog_entries.json` |

---

## 五、兼容性与数据

| 项 | 结论 |
|---|---|
| 存档迁移 | **不需要**（未上线 + 测试期 destructive rebuild） |
| 枚举编号 | `14`/`15` 退役 reserved，**禁复用**（`EquipmentProtoNumberFrozenTest` 增加退役断言） |
| `weaponId(17)` | 退役（原为复用锚定，随武器删除失去对象）；编号 reserved |
| 旧档兼容 | 无（未上线，无真实玩家档） |
| iOS 对等 | 全部改动为**纯数据 + 逻辑**（无平台独占能力）⇒ iOS 天然对等；无需备选方案 |
| 数值影响 | 装备总占比守恒（40%±5%）；套装满套口径不变；伤害侧（五行）零影响 |
| 死数据风险 | 武器/腿部精灵图、配方、掉落条目、枚举值必须**同步删净**，否则留半套残留（F-2） |

---

## 六、测试方案

### 6.1 守卫更新表（**逐条**）

| 测试 | 变更 |
|---|---|
| `EquipmentSlotOrderGuardTest` | 枚举 4 值 + `displayOrder` 4 项；**新增**退役断言（`WEAPON`/`LEGS` 不在枚举内、编号 14/15 未复用） |
| `EquipmentSlotRetirementGuardTest` | **新增**：全仓符号面扫描，武器/腿部残留仅允许出现在退役注释/`reserved` 声明/迁移说明中 |
| `EquipmentSetBonusTest` / `equip_set_bonus_test.cpp` | 档位 2/4；**4 件套 = 本系 +20% + 暴击率 +12%**；满套 = 本系 +30% + 暴击率 +12%；6 件（不可能）无分支 |
| `EquipmentDatabaseTest` / `equipment_db_test.cpp` | 部件数 **24**；每套 4 件；无武器/腿部条目 |
| `EquipMainStatPoolTest`（若存在，否则新增） | 池恰 4 部位；无武器/腿部池 |
| `ForgeRecipeSlotGuardTest` | **新增**：配方产出部位 ∈ 4 部位集合 |
| `EquipmentPowerParityTest` | 重定标至 40%±5%（系数 1.5 初值，按报告校准） |
| `DiffEquipmentUpgradeTest` / `DiffElementalDamageTest` | 场景改 4 部位；逐位断言保持 |
| `MigrationRequiredGuardTest` | schema 基线同 commit 更新（F-7） |
| `DestructiveRebuildBaselineTest` | 沿用（测试期基线） |
| `TemplateCodegenIntegrityGuardTest` / `EquipmentSingleSourceGuardTest` | 24 部件同源校验 |
| `SingleColumnStatGuardTest` / `InnateDamageTypeGuardTest` / `DamageTypeGuardTest` | **不受影响**（伤害侧未动） |

### 6.2 门禁

与既有装备线一致：`ctest`（全量，含 bench 串行）/ Kotlin 全量 `testReleaseUnitTest --max-workers=1` / `lintRelease detekt` / `gen-templates.mjs` 零漂移 / `check-agent-instructions.mjs`；`Diff*` 家族全绿。

### 6.3 对抗性审查要点

| # | 对抗点 | 期望结论 |
|---|---|---|
| FA1 | 6 件套分支被"注释掉"而非删除 | 断言源码无 `>= 6` 判定（符号面） |
| FA2 | 武器/腿部"改名保留"（如把 LEGS 改成 SLOT_A） | 符号面 + 数据源双重扫描：部位 key 集合恰 4 个 |
| FA3 | 24 部件中混入武器/腿部条目 | 数据源逐条断言 `part ∈ 4 部位` |
| FA4 | 单件系数 ×1.5 只改了一侧（Kotlin 或 C++） | 双侧常量同值守卫 + `Diff*` 逐位 |
| FA5 | 精灵图删了但注册项未删（死引用） | `SpriteResRegistry` 注册面与图集清单一致性守卫 |
| FA6 | 4 件套档两条效果只生效一条 | 用例断言两条效果**同时**存在 |

---

## 七、风险

| # | 风险 | 概率 | 影响 | 兜底 |
|---|---|---|---|---|
| FR1 | 件数 6→4 后**单件感知过强**（单件 ×1.5 使极品单件极强） | 中 | 中 | 数值校准批判定分布；必要时把补偿拆到"主词条 ×1.3 + 副词条 ×1.2" |
| FR2 | 武器删除后**输出流派配装手段减少** | 中 | 中 | 手部承接武器池（同 3 词条、系数 1.15）已在 §3.4 落实 |
| FR3 | 套装更易凑齐 ⇒ 满套覆盖率上升，达成过快 | 中 | 中 | 由数值校准批评估；必要时调掉率/套装件数门槛（不在本次架构内） |
| FR4 | 武器/腿部残留漏删（精灵图/配方/掉落/枚举值） | 高 | 中 | `EquipmentSlotRetirementGuardTest` + `ForgeRecipeSlotGuardTest` + 数据源逐条断言 |
| FR5 | 已并网的五行/伤害侧被误伤 | 低 | 高 | 本次不改伤害侧；`DiffElementalDamageTest` 与 `DamageTypeGuardTest` 必须仍绿 |
| FR6 | codegen 非幂等（历史坑） | 中 | 中 | G0 零漂移门禁（生成后 `git diff` 必须空） |

---

## 八、批次编排（F1–F4）

```
F1 领域与静态数据（枚举 4 值/池/系数/套装表/中性源 24 条 + codegen）
   └─► F2 引擎结算（Kotlin + C++ 槽位 4 + 套装 2/4 + 单件系数）
          └─► F3 外围系统清理（锻造/掉落/商店/AI 配装/UI 与 24 图）
                 └─► F4 数值校准 + 守卫全绿 + 文档/ADR/双日志收口
```

| 批 | 内容 | 规模 | Room | 报告 |
|---|---|---|---|---|
| **F1** | 部位枚举 4 值 + 退役编号 + 主词条池/系数 4 部位 + 套装表档位 2/4 + 中性源 36→24 + codegen + 枚举守卫 | 中 | schema 变更（同 commit 更新基线） | `reports/report-F1.md` |
| **F2** | 弟子槽字段 6→4 + 装备汇总单件系数 + 套装判定两档 + C++ 对偶 + JSON/视图编码 | 大 | — | `reports/report-F2.md` |
| **F3** | 锻造配方/掉落/商店清理 + AI 配装 4 部位 + 装备 UI 4 部位 + 24 精灵图 | 中 | — | `reports/report-F3.md` |
| **F4** | 数值校准（40%±5%）+ 全量门禁 + 文档/ADR + 双 changelog + 债登记 | 中 | — | `reports/report-F4.md` |

**串行约束**：F1 必须先（枚举与数据源是所有人的编译前置）；F2 依赖 F1 产物；F3 的"UI/资源"子集可与 F2 并行但**不得改** F2 的写入面（§四）；F4 最后。

---

## 九、验收判据（DoD）

1. `EquipmentSlot` 恰 4 活跃值 + `WEAPON/LEGS` 退役 reserved，编号不复用（F-1）
2. 武器/腿部全链零残留（除退役注释）——`EquipmentSlotRetirementGuardTest` 绿（F-2）
3. 套装档位恰 2/4；4 件套同时给"本系 +20%"与"暴击率 +12%"；无 `>= 6` 代码（F-3/FA1）
4. 部件恰 **24**、配方/掉落产出部位 ∈ 4 部位（F-4/F-6）
5. 装备占总战力 **40%±5%**（含套装），报告出具校准过程（F-5）
6. 弟子槽字段恰 4；Room 版本与 `BASELINE_ENTITIES` 同 commit 更新（F-7）
7. `ctest` 全量 + Kotlin 全量 + detekt 绿；`Diff*` 逐位一致（F-8/F-9）
8. codegen 零漂移；`check-agent-instructions.mjs` 绿（F-9）
9. 24 张精灵图或占位兜底（F-10）
10. 双 changelog 已更新（F-11）
11. 各批 `report-F*.md` 含**实跑数字**与**旧用例处置表**

---

## 十、盲区自查

| # | 盲区 | 处置 |
|---|---|---|
| FB1 | 需求边界 | "四件套"已确认＝4 部位满套；档位 2/4 已确认；**若未来要"五部位（含法宝）"需重新立项** |
| FB2 | 边界值 | 弟子装备 0–4 件全部覆盖（含空槽、重复部位防御性校验） |
| FB3 | 系统耦合 | 锻造/掉落/商店/AI 配装/排序筛选/背包去重（`EquipmentDedupeRule`）全部在 §四 清单内 |
| FB4 | 假设有效性 | 假设"未上线、无存量"成立（用户确认）⇒ 若期间有内测包流出，需补一次性清档说明 |
| FB5 | 非功能 | 部件数减少 ⇒ 背包与存档体积下降；结算热点无新增分支（不影响 bench 门禁） |
| FB6 | 生命周期 | 24 张图与删图同步；注册面/图集/清单三处一致 |
| FB7 | 流程 | 本次属装备线后续批，完工后在 `DISPATCH-LEDGER.md` §8 追加"F 批"并登记 |

---

## 附：交付状态

| 项 | 状态 |
|---|---|
| 本方案 | ✅ 已完成（纯文档，零代码改动） |
| 决策待定项 | **无**（Q1–Q5 全部闭环） |
| 下一步 | 等用户指令"实施"后按 F1→F4 串行执行；执行前重新实测 §二 各项（因并网代码持续变动） |
