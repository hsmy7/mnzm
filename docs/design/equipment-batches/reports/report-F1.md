# report-F1 — 装备四部位化 F1 批：领域与静态数据

> 日期：2026-10-02 ｜ 分支：`feat/equipment-four-slot-f1`（自 main `a7d623fd1`）
> 方案：`docs/design/equipment-four-slot-refactor-plan.md`（v1.0，Q1–Q5 已闭环）
> 性质：对未上线装备系统的收缩型重构；测试期 destructive rebuild，**无迁移、无补偿**。

## 一、交付范围（实测后落地面）

### 1.1 方案 F1 清单逐项

| # | 项 | 落点 | 终态 |
|---|---|---|---|
| 1 | 部位枚举 6→4 | `Items.kt` `EquipmentSlot` | 恰 4 值 `HEAD(10)/BODY(11)/HANDS(12)/FEET(13)`；`WEAPON(14)/LEGS(15)` 退役 reserved 禁复用；`displayOrder` 4 项单一真源 |
| 2 | 主词条池 4 部位 | `EquipMainStatPool.kt` + 中性源 `mainStatPools` | 头(血/防,1.00)、身(防/攻/暴率/暴伤,1.00)、手(攻/暴率/暴伤,1.15)、脚(防/攻/暴率/暴伤/血,0.95)；**部位系数表 4 项**，单件数值表（基数表/副词条档位）一字未改 |
| 3 | 套装档位 2/4 | `EquipmentSetDatabase.kt` + C++ `disciple_stats.h` | `bonus6` 改名 **`bonusFull`**（4 件触发）；`activeBonuses`：`>=2`→bonus2，`>=4`→bonus4+bonusFull；`count >= 6` 分支**删除非注释**（FA1）；满套 = 本系 +30% + 暴击率 +12% 守恒 |
| 4 | 中性源 36→24 | `scripts/data/equipment_db_sample.json` | `setPieces` 24（6 套 × 4 部位）、`pieceCount=24`、池 4 部位、sets 键 `bonusFull` |
| 5 | codegen 重生成 | `gen-templates.mjs` → 4 张 C++ 头 + 测试快照；`gen-game-data.mjs` → `game-data.json` + hash | C++ 侧 `part` 为字符串无枚举耦合，重生后 `equipment_db.h` 0 处 WEAPON/LEGS；**G0 幂等零漂移**（sha256 全表比对 OK） |
| 6 | 枚举守卫 | `EquipmentSlotOrderGuardTest` 重写 | 4 值 + 编号 10..13 钉死 + **新增退役断言**（退役部位名不回潮、编号 14/15 未复用） |

### 1.2 编译前置连带面（枚举收缩强制，超出 F-WP1 清单的部分）

枚举 4 值是全仓编译前置（方案 §八），以下为主源连带修改（每处均为**机械分支删除/回退值更换**，深层字段删除留 F2/F3）：

- **Kotlin 主源 17 文件**：`AlchemySystem`（ForgeSlot 默认 HEAD）、`DiscipleEquipment`/`DiscipleComponents`/`AISectDiscipleManagerMisc`/`CaptiveGearUtils`/`DiscipleEquipmentService`/`DiscipleFacadeImpl功法Ops1`/`DisciplePurchaseService`（槽位 when 分支 6→4）、`Gear`（applyGear 弃 weapon/legs 查表）、`HeavenlyTrialBuildOps`/`HeavenlyTrialService`（试炼装备选取 4 部位）、`DetailEquipmentSection`（UI 回退 HEAD）、`JsonConverters`（注释口径）。
- **C++ 手写面 7 文件**：`disciple_stats.h`（套装档位 2/4，与 Kotlin 同批——保 C++ AUTHORITATIVE 与 Diff 逐位一致）、`data_json.h`（`bonusFull` 注入键）、`mission_completion.h:579`、`ai_sect_recruit.h:199`、`ai_sect_ops.h:302`（**三处槽位洗牌/遍历 6→4**——Kotlin 侧 `EquipmentSlot.entries` 自动缩 4 会改变洗牌 RNG 消耗量，C++ 不同步则 Diff 全红）、`disciple_purchase.h`（购买部位回退 WEAPON→HEAD，与 Kotlin 对称）、`models.h`（注释口径）。
- **Kotlin 配方 36→24**：`ForgeRecipeDatabase.kt` 删 12 条武器/腿部配方块（枚举引用编译强制）；`recipe_db_sample.json`（72 条旧通用配方族，与套装配方族互不校验）不动，归 F3。

## 二、门禁与实跑数字

| 门禁 | 结果 |
|---|---|
| `compileReleaseKotlin` / `compileReleaseUnitTestKotlin`（全模块） | BUILD SUCCESSFUL |
| Kotlin 全量 `testReleaseUnitTest --max-workers=1`（含桌面 JNI Diff 家族） | **7489 tests / 0 fail / 0 err / 19 skip**（domain+data+engine+ui 5500、feature 968、app 1021） |
| `ctest`（gamecore 全量，含 bench） | **1538/1538 全绿**（61.96s） |
| 桌面 JNI 桥 | `build-desktop-jni.ps1` 重编（259 源同源指纹；.so 17:57 晚于全部头文件 17:04）；`Diff*` 家族全绿 |
| codegen 零漂移 | 重跑 `gen-templates.mjs` + `gen-game-data.mjs`，8 个产物 sha256 逐一比对 **OK** |
| `lintRelease` | BUILD SUCCESSFUL（40 warnings 均为主预存，3 项 baseline 过滤，0 error） |
| `detekt` | BUILD SUCCESSFUL（顺手清 2 处主预存 UnusedImports，见 §五） |
| `check-jni-count.mjs` | 87/87 双桥无扩散 |
| `check-agent-instructions.mjs` | 全绿（规则④ 7 个 AGENTS.md、规则⑤ 32574/32768 字节） |
| Room | **本批零 schema 变更**（枚举存 TEXT 列不受值域影响；`weaponId(17)/legsId(116)` 字段保留至 F2 删除时随列删除递增版本） |
| 版本号 | 未动（4.01.16） |

## 三、旧用例处置表

| 处置 | 文件 | 说明 |
|---|---|---|
| 重写（守卫语义升级） | `EquipmentSlotOrderGuardTest` | 4 值 + 编号钉死 + 退役名/退役编号双断言 |
| 重写（档位口径） | `EquipmentSetDatabaseTest`、`EquipmentSetBonusTest`、C++ `equip_set_bonus_test.cpp`、`ElementalDamageSystemTest` E7 | 2/4 两档：满套（4 件）= bonus2+bonus4+bonusFull 三条目同发，类型 +30% + 暴击 +12% |
| 重写（四部位世界） | `EquipmentPowerParityTest` | 满套 4 件（头/身/手/脚）；件数梯度 2/3/4；**占比带实测重锚 [0.22,0.40]**（五入口中位 0.259/0.276/0.297/0.345/0.320，与方案预测约 27% 吻合；F4 复核定稿） |
| 计数更新 | `EquipmentSingleSourceGuardTest`（144/24）、`TemplateRegistryGuardTest`（144、bonusFull 键）、C++ `equipment_db_test.cpp`（24/144 + 退役条目零命中断言）、`equip_main_stat_test.cpp`（4 池）、C++ `data_store_test.cpp`（24/144/counts 24） | |
| 场景改 4 部位 | Diff 六件（Upgrade/Execute/MonthSettlement/PhaseSettlement/State/StateSync）、`production_test`（forge_lietian_HEAD）、`month_settlement_test`（autoBuy 头冠）、`year_settlement_test`（头冠价格）、`ai_sect_ops_test`（headId 快照）、`inventory_tx_test`（商人头冠） | 双臂同输入，逐位断言保持 |
| 机械替换（部位值无关断言） | 22 文件单行 `EquipmentSlot.WEAPON→HANDS / LEGS→FEET` | 构造实例部位值不影响断言语义 |
| 槽位口径修正 | `DiscipleEquipmentManagerBagAutoEquipTest`、`EquipmentLevelPersistGuardTest`、`CultivationEventProcessorAutoWarehouseTest`、`DiscipleFacadeRewardTest`、`AISectDiscipleManagerTest`（达标判定）、`CaptiveGearMaterializationTest`、`HpMpRecoveryEquivalenceTest`（四件全配）、`EquipmentStatHotPathBenchmark`、`ReplaceSelectionDataTest` | 实例 part=HANDS ⇒ 断言/预装备改 hands 口径；俘虏物化改头/身两件 |
| 规则语义扩展 | `EquipmentRefRule`（+`data` 模块 6 个失败用例复原绿） | 退役槽位字段 weaponId/legsId 在删除前仍按孤儿判据清理（合法引用不误清） |
| 注释口径同步 | `CaptiveGearUtils`、`HpMpColumnCoverageTest`、`JsonConverters` 等 | 六部位 → 四部位最终状态描述 |

## 四、遗留与交接（F2/F3 写入面量化）

| 批 | 交接面 |
|---|---|
| **F2 引擎与存档** | `weaponId/legsId` 字段与列：Kotlin 主源 **25 文件 78 处**（`DiscipleComponents.EquipmentSet`/`DiscipleEquipment`/`DiscipleSerializer` proto 17/116、`DiscipleTables` 列、`NullSafeProtoBuf`）+ C++ `models.h`/`disciple_store.h`/`column_dirty.h`/`disciple_tx.h`/`auto_gear.h`/`json_codec`/`gameview_encode` 六列链；`Combatant.weaponName`/`legsName` 显示链；Room 版本递增 + `MigrationRequiredGuardTest.BASELINE_ENTITIES` 同 commit |
| **F3 外围清理** | C++ 旧 72 条通用锻造配方族（`recipe_db.h` 手写复刻 + `recipe_db_sample.json`）——**与 Kotlin 24 条套装配方族互不校验**，`processAutoForgeStep` 仍从旧族选配方（完成面按部件反查失败，产出零）；`RecipeRegistryGuardTest` 锻造面自 B3 起静默跳过（快照旧形状 assumeTrue，见 §五）；掉落/商店/AI 配装深层清理与 24 精灵图（`*_weapon`/`*_legs` 图与注册项）、UI 部位文案 |

## 五、途中发现（预存问题处置）

1. **`TemplateCodegenIntegrityGuardTest` scripts 路径错配（已修）**：`scripts/` 在仓库根，测试以 `android/` 为根拼路径 ⇒ 「生成器输出面」「中性源结构」两用例长期**静默跳过**（assumeTrue 不命中），且残留 `pieceCount 应为 12` 陈旧断言。已修路径（`repoRoot` 上溯三级）并更新为 24；守卫自此真实生效。
2. **detekt 主预存 UnusedImports ×2（已顺手修）**：`CriticalSaveEventBus.kt`（CompletableDeferred）/`CriticalSaveEventBusTest.kt`（advanceTimeBy），非本批引入（SS6 批源文件），按「新违规必须修复而非入 baseline」清除。
3. **`RecipeRegistryGuardTest` 锻造面休眠（留 F3）**：快照 72 条旧形状 vs Kotlin 24 条套装配方，`stale` assumeTrue 恒跳过；`gen-recipe-db.mjs` 头注释「ForgeRecipeDatabase.kt 逐字转录」自 B3 起与实际不符。F3 配方清理批统一收口。
4. **F1 方案表「Room schema 变更（同 commit 更新基线）」实测不成立**：枚举值收缩不改变 TEXT 列 schema；`weaponId/legsId` 列删除在 F2 ⇒ 版本递增与基线更新随 F2 落（本批 Room 零变更，无违反 7.1 之虞）。

## 六、DoD 对照（本批相关项）

| DoD | 状态 |
|---|---|
| 1. EquipmentSlot 恰 4 值 + 退役 reserved 禁复用 | ✅（守卫断言） |
| 3. 套装档位恰 2/4、4 件套双效果同发、无 `>= 6` 代码 | ✅（双端，FA1 符号面删除非注释） |
| 4. 部件恰 24（配方产出面 4 部位由 F3 收口旧通用族） | ✅ 部件 24；配方运行时产出 4 部位（部件反查唯一入口） |
| 7. 弟子槽字段恰 4 | ⏳ F2（本批仅枚举/分支面，字段保留） |
| 8/9. 门禁全绿 + codegen 零漂移 + Diff 逐位 | ✅ |
| 2/5/6/10/11. 退役守卫全仓符号面/占比实测报告/配方掉落产出/精灵图/双 changelog | ⏳ F2–F4 |
