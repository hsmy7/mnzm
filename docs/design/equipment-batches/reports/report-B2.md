# EQ-B2 实施报告 · 孕养类加成丹药退役（R11）+ 补偿

> 批次：B2（装备系统重构 · R11 孕养丹退役 + §5.7 补偿）
> 真源：`docs/design/equipment-set-system-refactor-plan.md` §5.7/§四/§六 + `docs/design/equipment-batches/IMPLEMENTATION-BATCHES.md` §1/§4.3/§5/§8
> 工作区：`C:\Mnzm\XianxiaSectNative-equipment`（git worktree，分支 `feat/equipment-set`，基线 = EQ-B1 收官笔内容 `9068049a1`）
> 状态：**实施完成，门禁全绿，待看护核验 accepted**

---

## 1. 做了什么（分类表）

### 1.1 领域层（`:core:domain`）

| 项 | 变更 |
|---|---|
| `ItemEffect` | 删 `nurtureSpeedPercent(3)`/`nurtureAdd(8)` 两字段 → `// reserved 3,8;` 注释（旧档字节按未知字段忽略，禁复用） |
| `PillEffect`（物品定义） | 删 `nurtureSpeedPercent(6)`/`nurtureAdd(9)` → `// reserved 6,9;`；`Pill` 便捷属性 `nurtureSpeedPercent`/`nurtureAdd` 同删 |
| `DiscipleSerializer` | `DiscipleSurrogate` 删 `pillNurtureSpeedBonus(47)` → `// reserved 47;`（兑现 E1 冻结表退役在册）；`buildSurrogate`/`withDiscipleValues` 两处搬运行删 |
| `PillEffects`（弟子组件） | 删 `pillNurtureSpeedBonus` 字段（Room 平铺列随之消失，v63 删列）；KDoc 12→11 字段 |
| `DiscipleCombatStats`/`DiscipleAggregate`/`DiscipleDelegates` | `pillNurtureSpeedBonus` 字段/聚合读取/搬运/deprecated 扩展属性全删 |
| `DiscipleTables` 列式族 | `pillNurtureSpeedBonuses` 表 + ColumnRegistry 登记 + Write 写入 + Assemblers 组装 + AssembleGroup PILL 分组五行全删 |
| `GameData` | 新增 `nurturePillsRetired` **@ProtoNumber(168)** + `@ColumnInfo(nurture_pills_retired, DEFAULT 0)`（补偿幂等标记；168 = 当前最大顺延号 167+1，未占 112–123 已用段、未触退役预留号） |
| `ItemDatabase` | 删 `PillTemplate.nurtureSpeedPercent/nurtureAdd` 字段、两族名称表（养器丹…天养丹 / 蕴器丹…天蕴丹）、`addCultivationSpeedGradePills`/`addCultivationValueGradePills` 的孕养生成段与签名参数、toPill 搬运两行（**模板 36 条退役**：6 tier × 3 grade × 2 族） |
| `PillRecipeDatabase` | `PillRecipe` 字段删 2；`pillTypes` 6→4 项；`herbPatterns` 同步删 idx2/idx5（`{2,4}`/`{3,7}`）保材料对齐（**配方 36 条退役**，660→624） |

### 1.2 存储层（`:core:data`）

| 项 | 变更 |
|---|---|
| `GameDatabaseMigrationsV63.kt`（**新增**） | `MIGRATION_62_63` 四步全幂等：① `pills` 删 `nurtureSpeedPercent/nurtureAdd` 两列（`rebuildTableDroppingColumns`，禁 DROP COLUMN，5 索引重建）② `disciples` 删 `pillNurtureSpeedBonus` 一列（同法，5 索引重建，复合主键保留）③ `game_data` ADD `nurture_pills_retired INTEGER NOT NULL DEFAULT 0`（columnExists 幂等门）④ `recipes` 表前缀 DELETE 孕养配方行（每配方一行，`ESCAPE '\\'` 精确匹配）。**与派发件差异**：写入面仅列了 disciples 删列；pills 表两列是 Room schema 校验实测逼出的必删面（PillEffect @Embedded 平铺），已补齐并入库 `RoomMigrationTest` 全链证明 |
| `GameDatabase.kt` | `DATABASE_VERSION` 62→63（开工实测复核）；`ALL_MIGRATIONS` 注册 `MIGRATION_62_63`；版本注释补 v63 行 |
| schema `63.json` | KSP 自动导出入库（pills 47→45 列、disciples 84→83 列、game_data +1 列） |
| `NurturePillRetirementRule.kt`（**新增**） | R11 补偿规则（order=26，链尾）：仓库堆叠/储物袋/邮件附件三类孕养资产按**退役时刻价格快照**100% 折算 → 单封补偿邮件（source=`nurture_pill_retirement`、永久有效、下品灵石附件）→ `unlockedRecipes` 孕养配方回滚 → `nurturePillsRetired` 标记同事务置位；无存量恒 Passed 不落盘；零抛异常（框架转 Corrupted 语义不受影响） |
| `SaveValidationRuleDefaults.kt` | `registerDefaults()` 加一行注册 |
| `NumericSanitizeRule` | `pillEffects` 消毒面删 `pillNurtureSpeedBonus`（收集表两处 + copy 行） |
| 价格快照 | `TIER_BASE_PRICES = [4_000, 16_000, 80_000, 480_000, 3_360_000, 26_880_000]`（= `GameConfig.Rarity.pillBasePrice`，rarity==tier）× `GRADE_MULTIPLIERS = {low:0.5, medium:1.0, high:2.0}`（= `PillGrade.priceMultiplier`）——静态表内嵌规则，不依赖运行时模板（§5.7 折算表口径） |

### 1.3 引擎层（`:core:engine`）

| 项 | 变更 |
|---|---|
| `PillEffectApplier` | `noBattleOrSpeedEffect` 判据删 `nurtureSpeedPercent` 分支；`pillNurtureSpeedBonus` 写入行删 |
| `AutoPillService` | 列式表写入行删；**`nurtureEffect` 回调链整链删除**（`processAutoUsePills(disciple, nurtureEffect)` → `processAutoUsePills(disciple)`；`applyNurtureAddToEquipped` 死函数删） |
| `HpMpRecoveryService` | 过期清零行删 |
| `DiscipleFacadeImpl` / `战斗Ops2` | `hasBattleOrSpeedEffect` 判据删孕养分支；三处表写入/清零行删；checkpoint 判据删孕养分支 |
| `DisciplePillManager` | `classify` 两分支删孕养 pillType；`pillToItemEffect` 搬运两行删；`tryConsumePill` 的 `nurtureAdd>0` 消费分支删 |
| `DisciplePurchaseService` / `MerchantItemConverter` | 丹药效果搬运两行/一行删（商店货源=模板，模板退役即断源） |
| 镜像链（三端同 commit） | `game_view.proto`：`optional double pillNurtureSpeedBonus = 63` 删 → `// reserved 63;`（禁复用）；`gameview_encode.cpp` tag 63 行删（B1「52-55 退役 reserved」同款先例）；`GameViewDiscipleRows` 四处（has 表/组装/表写入/builder）删；`GameViewMirrorCodec` dbl 行删 |
| `OverflowMailSender` | `SOURCE_DISPLAY_NAMES` 登记 `"nurture_pill_retirement" to "孕养丹药退役补偿"` |

### 1.4 C++（`gamecore`，16 文件）

| 文件 | 变更 |
|---|---|
| `data/recipe_db.h` | 两结构（PillRecipeSpec/PillTemplateSpec）字段删 4；`kNurtureBase`/`kNurtureSpeedNames`/`kNurtureAddNames` 三表删；两生成函数孕养 push_back 块 + 中间变量删；**其余 11 处 `PillTemplateSpec{...}` 位置参数聚合初始化 26 值→24 值逐位重排**（删值位 4/7，脚本 split_top 引号/括号感知）；分类判据两分支收窄；模板→丹药/配方两映射行删；`kStandardPillTypes[6]→[4]` + `kHerbPatterns` 对齐 + 循环上限 6→4 |
| `system/pill_system.h` | `classify` 两分支收窄；`applyBattleAttrAndTemp` 判据 + 写入行删；`applyClearAll` 清零行删 |
| `system/disciple_tx.h` | `facadeItemEffect` 搬运两行删；`hasBattleOrSpeed` 判据 + checkpoint 判据收窄；三处表写入/清零行删 |
| `state/models.h` | `ItemEffect`/`PillEffect`/`Disciple`（PillEffects 平铺段）三结构孕养字段删 |
| `system/merchant_settlement.h` / `system/production.h` / `system/secret_realm_session.h` / `system/disciple_purchase.h` | 商人收购/丹房产出/秘境/弟子购买四条产出链的效果搬运行删（各 2 行） |
| `data/data_json.h` | `jread` 两行 + `to_json` 两键删（PillRecipeTemplate 编解码） |
| `state/column_dirty.h` + `state/disciple_store.h` + `src/disciple_store.cpp` + `src/game_core.cpp` | `DiscipleColumn::PillNurtureSpeedBonus` 枚举 + 列名映射 + applyDirty 分支 + `pillNurtureSpeedBonuses` vector 全生命周期（push_back/reserve/clear/erase/swap/物化回填）+ 全列清单登记点，列式链逐点删 |
| `src/gameview_encode.cpp` | tag 63 行删（reserved 注释，禁复用） |
| `src/json_codec.cpp` | GameData Disciple 段 `GC_TO/GC_FROM` 各 1 行；ItemEffect/PillEffect to/from 四处各 2 字段（第一轮漏 GC_FROM 侧，schema 校验红后补） |
| `system/month_settlement.h` / `phase_settlement.h` | 月衰减/全清两处清零行删；`writePillResult` 写回行删；**`applyNurtureEffect` 整函数 + A2 调用点删**（`applyNurtureExp`/`kNurtureGainPerPhase` 保留——装备每旬自然孕养属 B3 装备面，本批不碰） |

### 1.5 奖励池与 UI

| 面 | 处置 |
|---|---|
| 商店/自动购买 | 货源 = `ItemDatabase` 模板 + `MerchantItemConverter` 转换——模板退役即断源，`AutoBuyService` 按 "pill" 大类采购无孕养残留 |
| 兑换码/邮件产出链 | 产出点均从模板/配方生成，随模板退役断源；**存量**未领取附件由 `NurturePillRetirementRule` 折算摘除（type=="pill" + itemId 判定） |
| UI 四件 | `PillEffects.kt`（即时判定 + 两行效果文案）、`ItemDetailEffects.kt`（显示名映射行）、`ItemDetailOtherEffects.kt`（Pill/ItemEffect 两套六行）、`PillStatLine.kt`（配方孕育值行）删；装备孕养等级展示（`nurtureLevel`）属 B3 面未动 |

### 1.6 静态数据 / 生成器

| 项 | 变更 |
|---|---|
| `scripts/gen-recipe-db.mjs` | 复刻段与 Kotlin 同步删（NAMES 两行、`NURTURE_BASE`、mkPill 两字段、两生成段、recipeFields/mkRecipe 搬运、STANDARD_PILL_TYPES 6→4 + HERB_PATTERNS 对齐） |
| `scripts/data/recipe_db_sample.json`（中性源） | 孕养条目清洗：`pillRecipes` 660→624，`pillRecipeCount` 同步；**死键清扫**——存量 624 条内残留的 `nurtureSpeedPercent`/`nurtureAdd` 键（1248 处）全删（消费面已不读，S13 归零语义收口），两份 JSON 产物随生成器同步 |
| `android/core/engine/src/test/resources/templates/recipe_db_sample.json` | `gen-recipe-db.mjs` 重刷 |
| `android/app/src/main/assets/data/game-data.json` + `.hash.txt` | `gen-game-data.mjs` 重生成（sha256 = `d837fe96d0a130982b1b69a78194a0c8baa21729fd74f63f17a4171840b37d36`） |

### 1.7 文档

| 项 | 变更 |
|---|---|
| `docs/knowledge-base.md` | 经济基线表登记「孕养丹退役补偿」纯新增源（一次性、2000 万上限、幂等标记、产出缺口说明）——验收判据 ⑤ |

---

## 2. 验证（门禁实跑数值原文）

| # | 门禁 | 实测 |
|---|---|---|
| 1 | `compileReleaseKotlin` | BUILD SUCCESSFUL（4 轮迭代：AutoPillService 回调链、规则 nullable/括号、死 import） |
| 2 | `testReleaseUnitTest --max-workers=1`（六模块全量，jni.path 指 worktree .so） | **7565/0/0/18 skipped**（app 1028 + core 合计 5544 + feature:game 993；对账 = B1 基线 7556 +18skipped 同基线 + 新增 12（NurturePillRetirementTest 9 + RoomMigrationV62To63Test 3）− 删 3（A2 回调用例 2 + NumericSanitize 孕养消毒 1）= 7565，逐位吻合） |
| 3 | `build-desktop-jni.ps1`（worktree 内重编） | 253 源文件指纹，`libgamecorejni.so` 生成（13:43 第二轮，含 json_codec GC_FROM 补修） |
| 4 | 桌面 ctest 全量（llvm-mingw + cmake 3.22.1） | **1480/1480 全绿**（1481→1480 = phase_settlement 删 A2 孕养均分用例；data_store_test 660→624 补齐后一轮通过） |
| 5 | `lintRelease detekt` | BUILD SUCCESSFUL（首红 9 违规全部修复：规则 execute 拆四段 strip + RefundLedger 账本、retiredPillPrice return 6→3、parse catch @Suppress+Log.w 带异常（MailDisciple 先例）、unused import ×2、迁移测试 @Suppress("LongMethod") ×2（V61To62 同先例）+ unused 常量删；baseline 零新增） |
| 6 | `check-jni-count.mjs` / `check-agent-instructions.mjs` | **87/87** ✓ / 路由闭包 42 篇 468 引用全绿 ✓ |
| 7 | codegen 门（G0 修正语义） | 重跑+人检+还原：gen-templates 输出面仅 equipment_db.h/herb_db.h + 两 JSON 副本，重跑后 diff 人检 = **D9 现状复现**（operator==/*TemplatesMutable 被删，E5 补全在 B3）非本批引入 → 四文件 `git checkout --` 还原，本批修改面（recipe_db.h/data_json.h 等）不在其输出面、零污染；`gen-game-data.mjs --check` 校验通过（sha256 `d837fe96d0a130982b1b69a78194a0c8baa21729fd74f63f17a4171840b37d36`；死键清扫后 domain/engine 定向回归复跑绿） |

**S13 全仓 grep 归零**（`nurtureAdd`/`nurtureSpeed`/`pillNurtureSpeedBonus`，生产链路面）：

- 归零面：Kotlin 生产代码（domain/data/engine/feature）、C++ include+src、脚本生成器、静态数据——全部 0 命中；
- 保留命中（**非生产引用，逐类豁免**）：① `reserved`/退役注释与迁移 KDoc（退役标记本身）② 测试负断言（`recipe_db_test` `EXPECT_FALSE(pillRecipeById("nurtureSpeed_*"))` = 判据④实现、冻结守卫 reserved 清单）③ 新规则/新迁移/新测试的语义引用（补偿逻辑本体）④ 历史迁移 `GameDatabaseMigrationsV21ToV30/V39/V62` 与 `OldSerializableSaveData`（历史迁移链禁改）⑤ 历史 schema `1..62.json`（不可变快照）。

**验收判据逐条**：

| 判据 | 结果 |
|---|---|
| ① S13 归零 | ✅（豁免面见上） |
| ② NurturePillRetirementTest | ✅ 9 用例：折算公式（快照 6 档 + 非法 id）/ 仓库+储物袋折算移除 / 邮件附件折算摘除 / 配方回滚 / 幂等（标记 Passed + 双跑单发）/ 无存量 Passed / **2000 万上限截断** / 上限边界足额 |
| ③ RoomMigrationV62To63Test | ✅ 3 用例：真实 Room schema 校验 / 删列+game_data 加列+recipes 清行+零丢失 / 幂等；`RoomMigrationTest` 全链 v11→63 绿 |
| ④ C++ recipe_db 断言两类配方零产出 | ✅ `RecipeDbTest.PillRecipeCount` 624（102+288+234，每 tier −6）+ `LookupHelpers` 两 id `EXPECT_FALSE` |
| ⑤ 经济基线表登记 | ✅（§1.7） |
| ⑥ 门禁全绿 | ✅（表见上） |

---

## 3. 旧用例处置表

| 文件 | 用例 | 处置 | 理由 |
|---|---|---|---|
| `DisciplePillManagerAutoUseEnhancementTest` | `A2 - 孕养度丹触发回调且扣袋`、`A2 - 无回调时孕养度丹照常消费` + `nurturePill` helper | **删除** | nurtureEffect 回调链随 R11 整链退役（生产代码已删，测试对象不存在） |
| `DisciplePillManagerExecutionOrderTest` | classify 两断言行（nurtureAdd/nurtureSpeed） | **删除两行**（保留其余断言） | 孕养 pillType 分类分支已退役 |
| `NumericSanitizeRuleTest` | `negative pill nurture bonus reset` | **删除** | 消毒字段已退役；其余负值消毒分支保留 |
| `CultivationCoreRealtimeAutoPillsTest` | 镜像写入一行 + import | **删除行** | 列式表已删 |
| `MirrorProtoFeedFixture` | `pillNurtureSpeedBonus = 0.09` | **删除参数** | 镜像字段已退役 |
| `DiscipleModelsTest` | 两处 `pillNurtureSpeedBonus` 默认值断言 | **删除两行** | 字段已删 |
| `EquipmentProtoNumberFrozenTest` | `pillNurtureSpeedBonus to 47` 从「退役在册」移入 reserved 清单 | **改断言** | 47 号本批退役，转入「reserved 禁复用」守卫面 |
| `RoomMigrationV58To59/V60To61/V61To62Test` | 终版列数断言 84 → 83 | **改断言** | 全链升到 v63 后 disciples 终版列数 −1（预期变更，附注释） |
| `OverflowMailSenderTest` | 扫描根扩 `core/data/src/main/java` | **扩守卫** | 补偿邮件来源点落在数据层规则（守卫语义增强，非豁免） |
| `BaselineFieldCoverageGuardTest` / `GameDataFieldPatchGuardTest` | `nurturePillsRetired` 登入排除表 | **登记** | 纯 Kotlin 读档链标记，C++ GameData 未建模（先登记再实现纪律） |
| C++ `recipe_db_test` | 计数 660→624、138→102、每 tier −6、LookupHelpers 负断言 | **改断言** | 判据④ |
| C++ `phase_settlement_test` | `NurturePillDistributesToEquippedInstances` 整用例 | **删除** | A2 孕养丹均分链退役（`EquipmentNurtureLevelsUpGoldenSequence` 等装备自然孕养用例保留） |

---

## 4. 未完成 / 登记

1. **pills 表删列为派发件外补齐面**：派发件写入面②只点名 `safeDropColumns("disciples","pillNurtureSpeedBonus")`；`pills.nurtureSpeedPercent/nurtureAdd` 两列（PillEffect @Embedded 平铺）由 `RoomMigrationTest` 真实 schema 校验红字逼出，同迁移补齐——已在 §1.2 与 KDoc 标注，非超范围（同一退役对象的列面完整性）。
2. **production_slots.recipeId 残留**：`unlockedRecipes`/`recipes` 已清，但 `production_slots` 表若存在指向已删配方的在途槽位，本批未清（无规则守 recipeId 死引用）。风险关闭依据：实时线在案 `processAutoAlchemy` 生产零调用 + 配方解锁面已断（无法再开新槽）。B3 装备批删堆叠时一并复核。
3. **实时线遗留三项**（派发件附录 8）：`processAutoAlchemy` 零调用 / bench `overBudgetCount` 断言噪声 / `kMsPerPhase` 归一——本批未顺手处置（纪律：不做超范围的事）。
4. **changelog 归 B5**（与 G13 起的批次分工一致），本批不动双更新日志与版本号。
5. **补偿邮件 slotId 取存量邮件首条**（SaveData 无槽位上下文）；若存档零邮件则回退 0——mails 表按槽位整对象替换回写语义下与存量邮件同槽，无跨槽错位。

## 5. 风险

**已核实**：
- 补偿幂等：标记+发放+资产清除同份快照（单事务落盘）；无存量恒 Passed，无读档写盘放大；
- 价格快照与退役前模板逐值一致（`retiredPillPrice` 6 档断言 + `Pill.basePrice` 同公式）；
- 迁移幂等（二次执行零改写断言）+ 迁移前备份机制不受影响。

**推测（低风险）**：
- 巨量旧档（单档孕养丹总价 > 2000 万）玩家会感知截断——§5.7 拍板口径，已记日志可审计；
- `nurture_pills_retired` 在云存档跨版本恢复时若回落旧 schema（v62 备份恢复路径）会重新触发补偿评估——但资产已清 ⇒ 走「无存量 Passed」，不会双发。

## 6. 提交

- 单笔收官：`feat(equip): B2 孕养丹退役+补偿——<要点>`（工作树仅本批改动；`atlas-rgba-manifest.json` 等构建副产物提交前 `git checkout --` 还原）；版本号不自增。
