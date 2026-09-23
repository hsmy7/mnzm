# G02 / G03 删除重构 · 只读侦察落点清单

> **性质**：只读侦察产出，作为 G02 / G03 实施批次的工作依据。
> **权威清单**：[character-gacha-redesign-2026-09-23.md](../../character-gacha-redesign-2026-09-23.md) §5(L240-263)、§6.1–6.5(L269-308)。
> **生成方式**：全量 grep（ripgrep 语法）+ 逐文件 read 实测，未修改任何代码、未提交、未运行 gradle / ctest。
> **行号口径**：正文（§0–§8）为 **G07 之前（`89281bcc9` 基线上）实测**；§9 为 **G07 之后（`19dffb6d2`）实测**。两者差异见下方「时效说明」。

---

## 时效说明（G07 之后为准）

### A. 工作树状态

- G07 已落库：提交 **`19dffb6d2`** `feat(gacha): G07 玩家侧战死改重伤`。此前报告中列出的 21 个在途改动文件**已全部回净**。
- 当前 `git status` 仅剩与本批无关的产物：`android/app/src/main/assets/atlas/atlas-rgba-manifest.json`(M)、`android/scripts/sprite-uid-map.json`(M)、`docs/research/android-game-perf-sota-2026-09.md`(?)、`docs/research/mobile-perf-quality-adaptation-benchmark.md`(?)、`模拟宗门美术素材/`(?)、`模拟宗门音乐音效/`(?)。**无 G02/G03 相关在途改动**。
- 因此 **§0 的 F1 警告已失效**（原「工作区脏、行号会二次漂移」不再成立）；但 §0 的 **F2（历史 Migration 禁改 SQL）依然有效**。

### B. G07 对行号的影响（加权说明）

| 文件 | G07 是否改动 | 行号漂移 | 说明 |
|---|---|---|---|
| `battle_residual_tx.h` | ✅ 改动 | **有漂移（±3~8 行）** | `settleBattleCasualtiesTx` 已改为 `isOutsideSect=true` 走**重伤**、`false` 走**完整死亡链**；`worldLevelVictoryTx` 内 soulPower / winAttr 调用**已删**（只剩 `(void)` 占位）。§0 报告的 :347/:349/:358-397/:401-429 等行号需以 §9 为准 |
| `death_handler.h` | ✅ 改动 | **语义变了，行号基本同构** | `markDead` 语义从「标死」改为「**重伤**（`currentHp=1`、`isAlive` 保持 1、不写 status / deathYears、不增年死亡计数）」。全文 94 行 |
| `secret_realm_session.h` | ✅ 改动（约 -116 行） | **有漂移** | `materializeDiscipleBagAndMarkDead` 现位于 **:513** |
| `models.h` / `disciple_store.h` | ❌ 未动列定义 | **行号基本不变** | §0 中所有 `models.h` / `disciple_store.h` / `column_dirty.h` / `DiscipleColumn` 行号可**直接沿用** |
| `DiscipleTables.kt` | ✅ 改动 | **行号漂移** | `markDead` 语义已改（`DiscipleTables.kt:883`，KDoc @:875-879「名字保留兼容调用点」）；字符串列声明区间不变（`comprehensions` :213 / `aptitudes` :220 仍有效） |
| `CombatService.kt` | ✅ 改动 | **新增两个私有函数** | `applyBattleInjuries` @**:105**、`applyLegacyCasualtyChain` @**:127**；`processBattleCasualties` @:75 新增 `isOutsideSect: Boolean = true` @:79 |
| `DiscipleDeathHandler.kt` | ✅ 改动（约 -81 行） | **有漂移** | `markInjured` @**:30** 为新增；`backfillDeathYears` @**:50** 保留 |
| `CombatService` 调用点 | ✅ 改动 | — | `PatrolBattleSystem.kt`、`ExplorationServiceBeastRaidOps.kt` 等已改为传 `isOutsideSect=true` |
| `DiscipleSlotComponents.kt` / `DetailCultivationSection.kt` | ✅ 改动 | 小漂移 | 与本批 UI 落点相关 |
| 测试（`death_handler_test.cpp` / `battle_residual_tx_test.cpp` / `exploration_tx_test.cpp` / `DiffDeathHandlerTest.kt` / `DiscipleDeathHandlerTest.kt` / `InventorySystemDeathMaterializeTest.kt`） | ✅ 改动 | 有漂移 | §0 测试表相关行号需现场复核 |

**使用规则**：正文 §0–§8 用于**定位系统性落点**（哪些环、哪些函数、哪些测试类）；**具体行号**在开工前用一次 grep 复核即可，或直接采用 §9 给出的 G07 后实测行号。

### C. 本次追加内容

新增 **§9 G07 后新增的收口项（G02 顺带完成）**——列出 G07 遗留、应由 G02 一并收口的死码 / 命名 / 语义过载问题及精确落点。

---

# 正文：G02 / G03 只读侦察落点清单

## ⚠ 0. 两个必须先知道的现场事实

**F1｜工作区是脏的（21 文件在途改动，未提交）**：`battle_residual_tx.h`、`death_handler.h`、`secret_realm_session.h`、`execute_dispatch.cpp`、`DiscipleTables.kt`、`DiscipleLifecycleProcessor.kt`、`DiscipleSlotComponents.kt`、`DetailCultivationSection.kt` 等已改。其中 **`battle_residual_tx.h:421-426` 已删掉 worldLevelVictoryTx 的 soulPower+1 与 winAttr 调用**（只剩 `(void)currentMonth; (void)rngSystem; (void)world;`），但 `applyDeterministicWinAttr`（`:358-397`，含 loyalty/comprehension/道德偷盗钩子）与 `applyGriefToRelativesBattle`（`:120-168`）**仍在**。施工前须先处理在途批次，否则行号二次漂移。
> **【已失效 — 见「时效说明」A】** G07（`19dffb6d2`）已落库，工作树已回净，本警告不再适用；但下一条 F2 依然有效。

**F2｜历史 Migration 是追加式档案，禁改 SQL**。`GameDatabaseMigrationsV*.kt` 里 `social_partnerId`/`parentId1`/`soulPower`/`loyalty` 是当时 schema 快照（V21ToV30:223-319、V39:71-176/221-278）。只能**新增 v54→v55**（`MigrationChainGuardTest`/`RoomMigrationTest` 会红）。

---

## G02 · 寿命 / 年龄 / 忠诚 / 叛逃 / 偷盗 / 神魂

### G02-0. Room 列 vs ProtoBuf 存档字段（关键分类）

| 字段 | Room 列（表 `disciples`） | ProtoBuf 存档 | 结论 |
|---|---|---|---|
| `age` | ✅ `age`（`Disciple.kt:82`；索引 `index_disciples_age` @ `:60`） | ✅ `@ProtoNumber(8)`（`DiscipleSerializer.kt:361`）；旧档 `OldSerializableSaveData.kt:178 @ProtoNumber(7)` | **两者都是** |
| `lifespan` | ✅ `lifespan`（`Disciple.kt:83`） | ✅ `@ProtoNumber(8)`（`:361`）；旧档 `:179` | **两者都是** |
| `soulPower` | ✅ `soulPower`（`Disciple.kt:109`） | ✅ `@ProtoNumber(29)`（`:376`）；旧档 `:204` | **两者都是** |
| `loyalty` | ✅ `loyalty`（`@Embedded skills`→`DiscipleComponents.kt:152`；索引 `index_disciples_loyalty` @ `Disciple.kt:59`） | ✅ `@ProtoNumber(50)`（`:448`）；旧档 `:230` | **两者都是** |
| `comprehension` | ✅（`DiscipleComponents.kt:153`） | ✅ `@ProtoNumber(51)`（`:449`） | **两者都是** |
| `aptitude` | ✅（`:161`，v46 加） | ✅ `@ProtoNumber(110)`+`@EncodeDefault(ALWAYS)`（`:464`） | **两者都是** |
| `usedExtendLifePillTypes` | ✅ `usage_usedExtendLifePillTypes`（`:188`，`@Embedded(prefix="usage_")`） | ✅ `@ProtoNumber(88)`（`:470`） | **两者都是** |
| `usedExtendLifePillIds` | ✅ `usage_usedExtendLifePillIds`（`:191`） | ✅ `@ProtoNumber(76)`（`:468`） | **两者都是** |
| `annualDesertedDisciples`/`annualTheftCount`/`theftJudgementsThisMonth`（GameData） | ✅ `annual_deserted_disciples`（`GameData.kt:809-811`）/`annual_theft_count`（`:814-816`）/`theft_judgements_this_month`（`:819-821`） | ✅ `OldSerializableSaveData.kt:119/120/121 @ProtoNumber(132/133/134)` | **两者都是** |

**Room 处置（rules/database-migration.md §7.1/7.2）**：禁 `ALTER TABLE DROP COLUMN`。两条合法路 —（1）**保留旧列 + Kotlin 字段 `@Ignore`**；（2）**create-copy-drop-rename**，仓库先例 `GameDatabaseMigrationsV21ToV30.kt:325 rebuildDisciplesDroppingLastTheftMonth` + `GameDatabaseMigrationSupport.kt` 的 `safeDropColumns`。本批建议走（1）。⚠ 旧列保留后 **`Disciple.kt:59-60` 的 `Index` 声明不得删**，否则 Room schema 校验与旧库不一致。
DB 版本单一源 `GameDatabase.kt:94 = 54`；`@Database(version)` @ `:199`；`ALL_MIGRATIONS` @ `:67-79`（尾项 `MIGRATION_53_54`）；`addMigrations` @ `:520`。
`disciples_core/_combat/_equipment/_extended/_attributes/disciple_compact` 六张镜像表 **v53 已 DROP**（`GameDatabaseMigrationsV53.kt:12-19`）——本批不要再动。

**ProtoBuf 退役口径**：编号禁复用 → 删除的 `@ProtoNumber` 须在 `SerializableDisciple` 与 `OldSerializableSaveData.SerializableDisciple` 两处留 `reserved`；守卫 `ProtoNumberUniquenessTest`/`ProtoNumberCoverageTest`。

### G02-1. 三端字段链落点

| 环 | `age` | `lifespan` | `soulPower` | `loyalty` | `comprehension` | `aptitude` | `usedExtendLife*` |
|---|---|---|---|---|---|---|---|
| C++ `models.h` | :310 | :311 | :331 | :403 | :404 | :411 | :421/:423 |
| `DiscipleColumn` 枚举 | :48 | :49 | :63 | :130 | :131 | :138 | :147/:149 |
| `DiscipleStore` 列 | :183 | :184 | :190 | :274 | :275 | :282 | :292/:294 |
| `disciple_store.cpp`（materialize/append/reserve/clear/erase/swap） | :43/:127/:184/:263/:319/:487/:606/:743 同族 | 同 | :43/:184/:319/:487/:606/:743 | :127/:263 | 同 | 同 | 同 |
| `column_dirty.h` nameOf | :93/:94 | :94 | :108 | :170 | :171 | :178 | :186/:188 |
| `column_dirty.h` serialize | :220/:221 | :221 | :239 | :363 | :364 | :375 | :393/:399 |
| `json_codec.cpp` GC_TO | :242 | :242 | :249 | :288 | :289 | :292 | :297/:298 |
| `json_codec.cpp` GC_FROM | :306 | :306 | :313 | :352 | :353 | :356 | :361/:362 |
| `gameview_encode.cpp` 列号表 | `{age,10}`:131 | `{11}`:132 | `{26}`:147 | `{88}`:209 | `{89}`:210 | `{96}`:217 | `{104}`:225/`{106}`:227 |
| `game_view.proto` | :116 | :117 | :137 | :215 | :216 | :223 | :233/:235 |
| JNI `GameCoreJni.cpp` | in :234/out :269/op :750,:841-847,:955-966/:347 | :269,:750-751,:841-847,:933-954 | op :642,:858-860 | in :787/out :810/:1037 | :810 | — | — |
| Kotlin `Disciple.kt` | :82 | :83 | :109 | — | — | — | — |
| Kotlin `DiscipleComponents.kt` | — | — | — | :152 | :153 | :161 | :188/:191 |
| Kotlin `DiscipleSerializer.kt` | — | :55/:205/:361 | :70/:220/:376 | :168/:320/:448 | :169/:321/:449 | :176/:328/:464 | :154/:305/:468；:156/:307/:470 |
| `GameViewMirrorCodec.kt` | :283,:294,:298,:439 | :440 | :456 | :551 | :552 | :559 | :570/:572 |
| `DiscipleTables.kt` | :118 | :119 | :122 | ~:211 | :213 | :220 | :230/:232 |
| `DiscipleTablesColumnRegistry.kt` | :9 | :10 | :13 | ~:55 | :57 | :64 | :111/:112；:123/:124 |
| `DiscipleTablesAssemblers.kt` | :65 | :66 | :81 | :166 | :166 | :173 | :184/:186 |
| `DiscipleTablesWrite.kt` | :96 | :96 | :97 | :180 | :180 | :184 | :194/:196 |
| `AssembleGroup.kt` | — | — | — | — | :81 | :88 | :97/:99 |
| `DiscipleDelegates.kt` | — | — | — | :163-164 | :165-166 | — | :188-190 |
| `game_core.cpp` 边界列 | :272 | — | :275 | :265 | — | — | — |
| `GameDataFieldPatch.kt` | — | — | — | — | — | — | —（本批仅删 :354 `theftJudgementsThisMonth`） |

**spec 未列但实测存在（必须一并清）**：`DiscipleDao.kt:55-56`（叛逃候选 `loyalty < :threshold` SQL）；`DiscipleAggregate.kt:27/95/124/128/134/135/136/236/250/305/307/343/344/351`；`DiscipleExtended.kt:30/58`；`DiscipleCore.kt:24/25/67`；`DiscipleAttributes.kt:14/15/22/36/37/44`；`DiscipleEquipment.kt:23/44`。

### G02-2. C++ 结算代码落点（实测起止）

| 系统 | 文件 | 起止 | 内容 |
|---|---|---|---|
| 老死判定链 | `year_settlement.h` | **1676-1758** | `processDiscipleAgingStep` |
| 寿元权威 | `year_settlement.h` | **285-308** | `discipleAgeMax()` |
| 候选老化 | `year_settlement.h` | **479-538**（老化段 486-493） | `processRecruitAging` |
| AI 宗老化 | `year_settlement.h` | **540-568**（写点 558-565） | `processSectDisciplesAging` |
| 哀悼到期 | `year_settlement.h` | **1205-1223**；调用 :1864 | `processGriefExpiry` |
| 思过释放 | `year_settlement.h` | **1252-1306**；常量 :1227-1229；调用 :1834 | `processReflectionRelease` |
| 年俸 ±忠诚 | `year_settlement.h` | **259-277**（写点 :274）/ **1765-1787**（扣点 :1778） | |
| 哀悼/解绑/判定 | `year_settlement.h` | **1536-1541** / **1555-1594** / **1596-1607**；调用 :1709/:1711 | |
| 结构体常量 | `year_settlement.h` | :82、:86-87、:125-135、:138-143 | |
| 政策忠诚 | `government.h` | **57-63**、**154-163**、**211-221**、**403-418** | |
| 月度政策写回 | `month_settlement.h` | **140-197**（忠诚 145-151、写回 178-181、钩子 185-195） | |
| 住所忠诚 | `month_settlement.h` | **798-817**；调用 :2435 | |
| 矿工忠诚衰减 | `month_settlement.h` | **941-965**（-1 @ :959）；调用 :984 | |
| **月度叛逃整段** | `month_settlement.h` | **1096-1377** | 注释 1096-1103；getter 1105-1141；免疫 1143-1148；从众 1150-1167；捕获率 1169-1208；概率 1210-1216；捕获思过 1218-1235；逃脱清理 1237-1325；主流程 1327-1377 |
| **月度偷盗整段** | `month_settlement.h` | **1379-1852** | 常量 1399-1426；LootedItemEntry 1428-1435；概率 1437-1445；被捕 1447-1466；仓库守卫 1468-1493；金额 1495-1524；选品 1526-1617；扣库 1619-1654；成功偷窃 1656-1708；单候选 1710-1777；兜底主流程 1779-1852 |
| 子事件分发 | `month_settlement.h` | 偷盗 **2032-2034**、叛逃 **2035-2036**；头注释 58-94、1013-1018 | |
| 战斗后道德偷盗钩子 | `battle_residual_tx.h` | **358-397**（loyalty :370、comprehension :368、道德+偷盗 :376-386）；:347/:349/:92 | |
| worldLevelVictoryTx | `battle_residual_tx.h` | **401-429**（已在途删，剩 :424-425） | |
| 魂力+1（秘境任务） | `mission_completion.h` | **1110-1120**（写点 :1117） | |
| 魂力+1（宗门战） | `sect_attack_tx.h` | **69-72** / **134-160**（写点 :156） | |
| 魂力乘区 | `disciple.h` | **33-34** / **317-320** | |
| 乘区接入 | `disciple_stats.h` | **869-874**；:484/:516/:59；增益公式 **886-** | |
| 突破寿元增益 | `breakthrough.h` | **68-85**（写点 :82）/ **110-147**（:117/:135） | |
| 旬结寿元增益 | `phase_settlement.h` | **900-905** | |
| AI 突破寿元 | `ai_sect_ops.h` | **212-227**（写点 **:224**） | |
| 寿元公式 | `disciple_factory.h` | **129** / **370-390** / **477** | |
| 年龄工具 | `lifecycle.h` | **33** / **39-45** / **58-** / **74-** | |
| 寿元词条同步 | `appointment_tx.h` | **803-816**（写点 :812）、**:1052** | |
| 延寿丹 | `recipe_db.h` | :111/172、:228、:241、:294、:306-316、**594-640**（604 描述串）、:628、:665、:771、:834/837/838、:1102/1105/1106、:1244-1257 | |
| 忠诚丹/功能丹 | `disciple_tx.h` :856/:947-958；`production.h` :337；`merchant_settlement.h` :304/:344；`pill_system.h` :38/:67/:107/:230；`disciple_purchase.h` :557 | | |
| 忠诚/寿元词条 | `trait_db.h` | :346/:347、:380-382、:386-387、:574-579、:679-686、:268-271、:624-626 | |
| 聊天忠诚 | `chat_effect_tx.h` :14/:58/:66/:84；派发 :397/:1037 | | |
| 偷盗配置 | `game_config.h` | **29-43**（14 个 `law*`） | |
| 边界写列 | `game_core.cpp` | **264-289** | |
| 派发 | `execute_dispatch.cpp` :397、:598、:857-859/:897、**:2620**、**:2629**；`dispatch_w4c.cpp` :143；`dispatch_w4d.cpp` :81/:91 | | |
| JNI op | `GameCoreJni.cpp` | :234、:255、:269、:637-642、:750-751、:787、:810、**839-847**、**855-857**、**858-860**、:933-954、:955-966、:1037 | |

### G02-3. ActionId 处置

| 编号 | 常量 | 定义处 | 处置 |
|---|---|---|---|
| 1103 | `DISCIPLE_MAX_AGE` | `action_ids.h:94` / `ActionIds.kt:90` / `scripts/action-catalog/core.mjs` | **留洞** |
| 1106 | `DISCIPLE_AGE` | `:103` / `:99` / core.mjs | **留洞** |
| 1436 | `REDEEM_RESOLVE_AGE_LIFESPAN` | `:256` / `:252` / core.mjs | **留洞**（§6.10 另批） |
| 1593 | `DISCIPLE_LIFECYCLE_RELEASE_REFLECTION` | `:376` / `:372` / core.mjs | **删实现 + 留洞** |
| 1612 | `WAREHOUSE_GARRISON_TX` | `:388` / 同段 / core.mjs | **删实现 + 留洞** |
| 1632 | `RECRUIT_AGE_TX` | `:409` / `:405` / core.mjs | **留洞**（旧招募退役） |
| 1712 | `SECT_ATTACK_GRANT_SOUL_POWERS_TX` | `:502` / `:498` / `scripts/action-catalog/w4c.mjs:31` | **删实现 + 留洞** |
| 1572/1573 | `EXPLORE_TX_ASSIGN_GARRISON`/`REMOVE_GARRISON` | `:361`/`:364` | **保留**（分舵驻守，非仓库驻守） |
| 1590 | `DISCIPLE_LIFECYCLE_EXPEL` | `:367` | §5.3 逐出批 → 本两批外，**留洞** |

**编号规则（已核实）**：`action_ids.h:1` —「由 `scripts/gen-action-ids.mjs` 生成 — 禁止手改（与 ActionIds.kt 同源）」。**三源同改**：`action_ids.h` + `android/core/engine/src/main/java/com/xianxia/sect/core/nativebridge/ActionIds.kt` + `scripts/action-catalog/*.mjs`，然后 `node scripts/gen-action-ids.mjs`，再跑 `scripts/check-jni-count.mjs`。留洞 = 保留编号、禁复用、禁重排。

### G02-4. Kotlin/UI 落点

**引擎/领域（生产）**

| 文件 | 行 | 处置 |
|---|---|---|
| `core/domain/.../domain/disciple/DiscipleAgePolicy.kt` | 25-35 + :38 | 整函数退役 |
| `core/engine/.../service/DiscipleLifecycleProcessor.kt` | :156/:14 | 删老死判定 |
| `core/engine/.../service/RecruitService.kt` | :250/:20 | 随旧招募删 |
| `core/engine/.../diplomacy/AISectDiscipleManager.kt` | :404/:42、:290 | 删老化 + loyalty 赋值 |
| `core/engine/.../service/LawEnforcementProcessor.kt` | **全文 499 行**（:189/:212/:238/:274/:294/:312/:335/:416/:426/:437/:445/:454/:484） | **整文件删** |
| `core/engine/.../service/LawEnforcementTheftTxOps.kt` | **全文 498 行**（:98/:190） | **整文件删** |
| `core/engine/.../service/CultivationSettlement.kt` | :80/:158/:192/:319/:329-348/:364/:540 | 删忠诚段，保留扣费 |
| `core/engine/.../service/CultivationEventProcessor.kt` | :257 | 删 |
| `core/engine/.../GameEngineBattleOps.kt` | :314/:243 | 删 |
| `core/engine/.../GameEngineWorldBattleOps.kt` | :212/:129 | 删 |
| `core/engine/.../domain/exploration/ExplorationService.kt` | :335/:548 | 删 |
| `core/engine/.../exploration/PatrolBattleSystem.kt` | :572、:878-884 | 删 |
| `core/engine/.../domain/disciple/DiscipleStatCalculator.kt` | :16-17 | 删 |
| `.../DiscipleStatCalculator突破Ops5.kt` | :84-85/:112 | 删 |
| `.../DiscipleStatCalculator属性Ops4.kt` | :431/:463/:497 | 删 |
| `.../DiscipleStatCalculator修炼Ops1.kt` | :69- | 删 |
| `core/engine/.../service/DiscipleBreakthroughHandler.kt` | :32/:167 | 删 |
| `core/engine/.../domain/disciple/DiscipleFactory.kt` | :223 | 删 |
| `core/engine/.../domain/disciple/DiscipleFacadeImpl.kt` | :307 | 删 |
| `core/engine/.../domain/disciple/PillEffectApplier.kt` | :135 | 删 |
| `core/engine/.../RedeemCodeRewardOps.kt` | :296 | §6.10 批，本批保留 |
| `core/domain/.../GameConfig.kt` | :126-127、:920-942 | 删 |
| `core/domain/.../config/GameConfigData.kt` | :201-213、:33 | 删 |
| `core/engine/.../config/GameConfigProvider.kt` | :61-73 | 删 |
| `core/engine/.../config/GameConfigNativeBridge.kt` | :53-66 | 删 |
| `core/engine/.../nativebridge/GameCoreBridge.kt` | :396-409 + 全部 law/theft/soulPower `external fun` | 删 |

**UI**

| 文件 | 行 | 处置 |
|---|---|---|
| `ui/components/DiscipleComponents.kt` | **:311** 忠诚行；:69-72/:205-230/:296 年龄 | 删；第三行改灵根徽章 |
| `ui/game/components/detail/DetailCombatSection.kt` | :46 忠诚、:131 神魂 | 删 |
| `ui/game/components/detail/DetailBasicInfoSection.kt` | :39/:70/:87/:186/:199/:398 | G03 删 |
| `ui/game/components/detail/DetailCultivationSection.kt` | :303-304/:376 | 删 |
| `ui/game/dialogs/LawEnforcementHallDialog.kt` | **全文 475 行** | **整文件删** |
| `ui/game/dialogs/TianshuHallDialog.kt` | :663/:703/:753-762/:795-820 | 删 5 政策开关 |
| `ui/game/dialogs/PillStatLine.kt` | :36 | 删 |
| `ui/game/components/ItemDetailOtherEffects.kt` | :234/:252/:554/:572 | 删 |
| `ui/game/components/PillEffects.kt` | :53/:77 | 删 |
| `ui/game/components/ItemDetailEffects.kt` | :35 | 删 |
| `ui/components/DiscipleDetailDialogs.kt` | :47/:159/:314 | 删 |
| `ui/game/dialogs/DiscipleChatDialog.kt` | :47-60/:108-198/:233/:270-272/:360 | 删 loyalty 段 |
| `ui/game/delegate/DiscipleDelegate.kt` | :232/:245 | 删 |
| `ui/game/AttributeFilterOption.kt` | :26/:43 | 删 |
| `ui/game/dialogs/WarehouseDialog.kt` | :44/:88/:110-115/:140-170 | 删 |
| `ui/game/dialogs/WorldMapSectDetailDialog.kt` | :392/:405/:435/:502/:524 | **保留**（分舵驻守） |
| `ui/game/tabs/WarehouseDiscipleSelectDialog.kt` | **全文 304 行** | 删 |
| `ui/game/ProductionViewModel.kt` | :47 | 删 |
| `ui/game/delegate/BuildingDelegate.kt` | :120/:180 | 删 |
| `ui/game/LoadingTips.kt` | :13-15/:25 | 删 |
| `ui/game/dialogs/BattleLogDialogs.kt` | :296/:308/:318 | `disciple_expel` 属逐出批，保留 |

### G02-5. 测试落点

**C++ GTest**

| 文件 | 行 / 用例 | 处置 |
|---|---|---|
| `lifecycle_test.cpp` | :12/:48/:55/:67 | **删除** |
| `disciple_test.cpp` | :194 `SoulPowerBonus` | **删除** |
| `government_test.cpp` | :108 `LoyaltyDeltas` | **删除** |
| `month_settlement_test.cpp` | :174/:201/:218/:553 | **改断言** |
| `month_settlement_test.cpp` | :784/:800/:846 | **删除**（叛逃） |
| `month_settlement_test.cpp` | :901/:951/:1003/:1076/:1128/:1207/:1230/:1249/:1295/:1309/:1323/:1341/:1356/:1383/:1426/:1472/:1513/:1587/:1640/:1673（20 例 `Theft*`） | **全部删除** |
| `battle_residual_tx_test.cpp` | :175/:183-238 | **改断言** |
| `sect_attack_tx_test.cpp` | :190/:206/:233/:249 | **删除** |
| `mission_completion_test.cpp` | :271/:300/:376 | **改断言** |
| `recruit_tx_test.cpp` | :148/:171 | 随旧招募删 |
| `trait_db_test.cpp`/`trait_effects_test.cpp` | lifespan/loyalty/comprehension 键 | **改断言**（先删 lifespan/loyalty） |
| `disciple_factory_test.cpp` | :59/:104/:168-169 | **改断言** |
| `year_settlement_test.cpp` | :83/:111/:115-137/:142-168/:193/:684/:700/:722-729/:751-758 | **改断言** |
| `chat_effect_tx_test.cpp` | :57/:64/:90/:106/:163 | **改断言** |
| `disciple_store_test`/`column_export_equivalence_test`/`disciple_store_bench_test`/`json_codec_test`/`gameview_encode_test`/`column_dirty_test`/`dirty_tracker_test` | 列双射守卫 | **必须同步改** |

**Kotlin JUnit**

| 文件 | 行 | 处置 |
|---|---|---|
| `core/engine/.../service/LawEnforcementProcessorTest.kt` | :40 | **整类删** |
| `core/engine/.../service/LawEnforcementDesertionScopeTest.kt` | :31 | **整类删** |
| `core/engine/.../service/LifespanGainOnBreakthroughTest.kt` | :15 | **整类删** |
| `core/engine/.../service/CultivationEventProcessorTest.kt` | :270-272/:319-321 | **删除** |
| `core/engine/.../nativebridge/DiffMonthSettlementTest.kt` | :131/:383-399 | **删除** |
| `core/engine/.../nativebridge/DiffMissionSettlementTest.kt` | :212-245 | **改断言** |
| `core/engine/.../nativebridge/DiffStateTest.kt` | :159/:307 | **改断言** |
| `core/engine/.../nativebridge/DiffGovernmentTest.kt` | :137-142 | **改断言** |
| `core/engine/.../DiscipleStatCalculatorTest.kt`/`...CombatBonusTest.kt` | soulPower/乘区 | **改断言** |
| `core/domain/.../GameAndDiscipleConfigTest.kt` | :35/:40 | **删除** |
| `core/domain/.../config/GameConfigConsistencyTest.kt` | :87-89/:123/:129/:248/:296/:302/:308 | **删 law 段** |
| `core/domain/.../config/ConfigLoaderTest.kt` | :103/:111-113 | **删 law 段** |
| `DiscipleTables*Test.kt`（12 文件 @ `core/domain/src/test/.../state/`）+ `app/src/test/.../DiscipleMergeCoverageTest.kt` + `DiscipleTablesIntegrationTest.kt` | 列级守卫 | **必须同步** |
| `core/data/src/test/.../ProtoNumberCoverageTest.kt` :257 / `ProtoNumberUniquenessTest.kt` :109 | reserved 后 | **保留** |
| `core/data/src/test/.../RoomMigrationTest.kt` :925 / `MigrationChainGuardTest.kt` :60 / `RoomMigrationLegacyTest.kt` :303 | 迁移链 | **新增 v54→v55** |
| `core/data/src/test/.../engine/CloudPayloadSizeBenchTest.kt` :230 / `ArchivePayloadRoundTripTest.kt` :73 | 种子数据 | **改断言** |
| `DiscipleModelsTest.kt` :366 / `GameDataTest.kt` :309 / `DiscipleSerializerProfessionTest.kt` | **改断言** |
| `MirrorProtoFeedFixture.kt` :90 / `DiffMirrorArmConvergenceTest.kt` :232 / `DiffColumnExportMergeConvergenceTest.kt` :173 | **改断言** |
| `GameViewDiscipleColumnApplyEquivalenceTest.kt` :75 / `BaselineFieldCoverageGuardTest.kt` :93 / `GameViewStoreGuardTest.kt` :196 / `GameDataFieldPatchGuardTest.kt` :258 / `HpMpColumnCoverageTest.kt` :70 | 覆盖守卫 | **必须同步** |
| `feature/game/src/test/.../DiscipleDelegateRecruitGuardTest.kt`/`GameViewModelTest.kt`/`DiscipleChatDialogTest.kt` | **改断言** |

### G02-6. 配置源落点

| 源 | 键/行 | 处置 |
|---|---|---|
| `core/domain/.../config/GameConfigData.kt` | :201-213 `LawEnforcementSection`（loyaltyThreshold 30 / moralityThreshold 30 / herdLoyaltyThreshold 50 / probPerPoint 0.01 / maxProb / baseCaptureRate / intelligenceBase / elderBonusPerPoint / discipleIntelligenceStep / discipleBonusPerStep / reflectionYears 5 / newDiscipleProtectionMonths 12）+ :33 | **整段删** |
| `core/domain/.../GameConfig.kt` | :920-942 law getter；:126-127 MIN/MAX_LOYALTY | **删** |
| `android/app/src/main/assets/config/game_config.json` | :9 `"minLoyalty": 0`（`maxLoyalty` 同段）；`lawEnforcement` 段 | **删段** |
| `android/app/src/main/assets/data/game-data.json` | `"generatedBy":"scripts/gen-game-data.mjs"`「禁止手改」；含 `r1/r2/r3_base_loyal`（loyaltyFlat）、`base_comp`、`POSITION_LAW_ENFORCEMENT`（执法金刚）、`neg_base_social` | **改脚本后重生成** |
| `scripts/data/trait_db_sample.json` | `base_loyal`/`base_comp`/`law_enforcement`/`lifespan` 组 | **删条目** |
| `scripts/data/recipe_db_sample.json` | `extendLife`/`loyalty`/`comprehension`/`charmLoyalty` | **丹方下架** |
| `scripts/gen-trait-db.mjs` | :45、:79、:87-88、:109-111、:155-160、:213、:235、:255-256、:279、:282 | **删生成项** |
| `scripts/gen-recipe-db.mjs` | :69、:92、:93、:106、:113、:129-134、:174、:177-178、:302-304、:332、:459、:475-477、:546 | **删生成项** |
| `scripts/gen-game-data.mjs` | 汇总写 `game-data.json` + `game-data.hash.txt` | **重跑** |
| `scripts/gen-action-ids.mjs` + `scripts/action-catalog/core.mjs:209` 等 | 双产物 | **改后重生成** |
| `game_view.proto` | :116/:117/:137/:215/:216/:223/:233/:235 → `reserved 10,11,26,88,89,96,104,106;` | **改** |

### G02-7. grep 清零清单（ripgrep）

> `rg -n --glob '!**/build/**' --glob '!.worktrees/**' '<正则>' android scripts`。中文标识符/注释会命中：`忠诚`、`寿元`、`叛逃`、`偷盗`、`神魂`、`延寿`、`思过`、`驻守`。

| # | 正则 | 预期命中归属 |
|---|---|---|
| 1 | `\bloyalty\b\|Loyalty\|LOYALTY\|loyaltyAdd\|loyaltyFlat\|MIN_LOYALTY\|MAX_LOYALTY\|kMaxLoyalty\|kWinAttrMaxLoyalty` | 生产+测试+文档；目标归零（`reserved`/历史 Migration/docs/CHANGELOG 例外） |
| 2 | `\bsoulPower\b\|SoulPower\|SOUL_POWER\|soulPowers\|soulPowerBreakthroughBonus\|GrantSoulPowers\|GRANT_SOUL_POWERS` | 生产+测试；归零 |
| 3 | `usedExtendLifePillTypes\|usedExtendLifePillIds\|usage_usedExtendLife\|UsedExtendLifePill` | 生产+测试+Proto；归零（旧档类留 reserved 注释） |
| 4 | `computeMaxAge\|discipleAgeMax\|kAbsoluteMaxAgeCeiling\|ABSOLUTE_MAX_AGE_CEILING\|realmMaxAge\|computeLifespan\|calculateBreakthroughLifespanGain\|DiscipleAgePolicy\|SOUL_POWER_DIVISOR` | 生产+测试；归零 |
| 5 | `\b(disciple\|d\|ds)\.age\b\|ds\.ages\b\|\.age\s*=\|kPairingMinAge\|kRecruitAgeMin\|kRecruitAgeRange\|RECRUIT_AGE_TX\|DISCIPLE_AGE\|DISCIPLE_MAX_AGE` | 生产+测试；归零（`\bage\b` 单用噪声大，按此收敛写法） |
| 6 | `lawLoyaltyThreshold\|lawMoralityThreshold\|lawHerdLoyaltyThreshold\|lawProbPerPoint\|lawMaxProb\|lawBaseCaptureRate\|lawIntelligenceBase\|lawElderBonusPerPoint\|lawDiscipleIntelligenceStep\|lawDiscipleBonusPerStep\|lawReflectionYears\|lawNewDiscipleProtectionMonths\|lawMaxTheftPerYear\|lawMaxTheftJudgementsPerMonth\|LawEnforcement` | 生产+测试+配置；归零 |
| 7 | `desert\|Desert\|desertion\|annualDesertedDisciples\|annual_deserted_disciples\|叛逃` | 生产+测试+文档；归零（`annualDesertedDisciples` 玩家逐出属 §5.3，需与用户确认是否同批） |
| 8 | `theft\|Theft\|theftJudgements\|lastTheftJudgement\|annualTheftCount\|annual_theft_count\|偷盗\|warehouse_theft\|theft_caught\|theft_desertion` | 生产+测试+文档；归零（道德保留） |
| 9 | `warehouseGarrison\|WarehouseGarrison\|warehouse_garrison\|WAREHOUSE_GARRISON_TX\|仓库驻守\|驻守弟子` | 生产+测试+UI+Proto；归零（**`WorldMapSectDetailDialog` 的分舵驻守需人工排除**） |
| 10 | `reflection\|Reflection\|REFLECTING\|reflectionEndYear\|reflectionStartYear\|思过\|RELEASE_REFLECTION` | 生产+测试；归零 |
| 11 | `realmMaxAge\|maxAge\|MAX_AGE` | 生产+配置；归零（`realmConfig().maxAge` 也要清） |

**守卫建议**：扩展既有 `column_export_equivalence_test.cpp`、`BaselineFieldCoverageGuardTest`、`GameViewStoreGuardTest`、`GameDataFieldPatchGuardTest`，断言 `DiscipleColumn::kCount` ↔ `DiscipleTablesColumnRegistry` ↔ `game_view.proto` 三处列数一致。

### G02-8. RNG 影响

| 删除项 | 分区 | 影响 | G10 重录 |
|---|---|---|---|
| 月度叛逃检测（每 at-risk 弟子 1~2 次 `nextDouble`） | **SYSTEM** | 月结序列直接平移 | ✅ **必须** |
| 月度偷盗兜底 + 单候选（最多 6 类抽取/候选，含无条件"偷后叛逃"1 次） | **SYSTEM** | 月结序列**大幅平移** | ✅ **必须** |
| 教化之道钩子（`month_settlement.h:189-193`，内嵌弟子循环序） | **SYSTEM** | 同上 | ✅ |
| 战斗后道德偷盗钩子（`battle_residual_tx.h:381-382`） | **SYSTEM** | 关卡胜利序列平移 | ✅ |
| 年结思过释放内的条件性偷盗钩子（`:1834`） | **SYSTEM** | 年结序列平移 | ✅ |
| 招募候选老化 | 零 RNG（`:483` 注释） | 改招募生成输入集 → 消费数可能变 | ✅ 建议 |
| AI 宗弟子老化 | 零 RNG（`:545`） | 改 AI 存活集 → 战力判定分支变 | ✅ 建议 |
| 老死判定链 `processDiscipleAgingStep` | 零 RNG（`:1678`） | **活弟子集变化 → 一切按行序抽取的序列平移** | ✅ **必须（最大扰动源）** |
| 突破寿元增益 | BREAKTHROUGH | 零 RNG 消耗（仅移除赋值） | ❌ |
| 魂力突破乘区 | 无 RNG | 改 `nextDouble() < chance` 的**判定结果** | ✅ 行为基线变 |
| 政策忠诚 / 住所忠诚 / 矿工忠诚 / 年俸 ±忠诚 | 零 RNG | 无 | ❌ |

**分区定义（已核实）**：`rng_manager.h:49-57` — `kBattle=0, kBreakthrough=1, kExploration=2, kSystem=3, …, kSecretRealm=7`；播种 `:78-85`（`seed + n`）。Kotlin 对应 `RngPartition.SYSTEM`（`LawEnforcementProcessor.kt:200`）。
**结论**：G02 平移 **月结 SYSTEM、年结 SYSTEM、关卡胜利 SYSTEM** 三条序列，并因活弟子集/行序变化污染**所有**按行序抽取的分区（含 BREAKTHROUGH、EXPLORATION）→ **G10 必须重录全部对拍基线**。

---

## G03 · 生育 / 道侣 / 亲缘

### G03-0. Room 列 vs ProtoBuf

| 字段 | Room 列（`disciples`） | ProtoBuf | 结论 |
|---|---|---|---|
| `partnerId` | ✅ `social_partnerId` TEXT（`DiscipleComponents.kt:129`） | ✅ `@ProtoNumber(11)`（`DiscipleSerializer.kt:436`）；旧档 `:182` | **两者都是** |
| `partnerSectId` | ✅ `social_partnerSectId`（`:130`） | ✅ `@ProtoNumber(12)`（`:437`） | **两者都是** |
| `parentId1` | ✅ `social_parentId1`（`:131`） | ✅ `@ProtoNumber(13)`（`:438`） | **两者都是** |
| `parentId2` | ✅ `social_parentId2`（`:132`） | ✅ `@ProtoNumber(14)`（`:439`） | **两者都是** |
| `lastChildYear` | ✅ `social_lastChildYear` NOT NULL（`:133`） | ✅ `@ProtoNumber(15)`（`:440`） | **两者都是** |
| `childBirthMonth` | ✅ `social_childBirthMonth`（`:134`） | ✅ `@ProtoNumber(102)`（`:441`） | **两者都是** |
| `griefEndYear` | ✅ `social_griefEndYear`（`:135`） | ✅ `@ProtoNumber(16)`（`:442`） | **两者都是** |
| `daoCompanionBannedRootCounts`/`daoCompanionConsentRequired`（GameData） | ✅ `GameData.kt:615/:620` | ✅ `OldSerializableSaveData.kt:89 @ProtoNumber(102)`/`:90 @ProtoNumber(103)` | **两者都是** |
| `warehouseGarrisons`（G02 侧） | ✅ `GameData.kt:410` | ✅ `OldSerializableSaveData.kt:133 @ProtoNumber(146)` | **两者都是** |

**Room 处置同上**（本批 7 列含 `social_` 前缀；`social_masterId`/`:136` **保留**）。`DiscipleSerializer.kt:294-300` 的 `ifEmpty { null }`/`takeIf { it != 0 }`/`takeIf { it != NULL_INT_SENTINEL }` 归一化同步摘除。
**`reserved` 清单（两处 proto 定义）**：`SerializableDisciple` → `reserved 11,12,13,14,15,16,102;`；`OldSerializableSaveData.SerializableDisciple` → 同上；GameData 级 → `reserved 102,103;`。

### G03-1. 三端字段链落点

| 环 | partnerId | partnerSectId | parentId1 | parentId2 | lastChildYear | childBirthMonth | griefEndYear |
|---|---|---|---|---|---|---|---|
| `models.h` | :391 | :392 | :393 | :394 | :395 | :396 | :397 |
| `DiscipleColumn` | :119 | :120 | :121 | :122 | :123 | :124 | :125 |
| `DiscipleStore` | :262 | :263 | :264 | :265 | :266 | :267 | :268 |
| `column_dirty.h` nameOf | :160 | :161 | :162 | :163 | :164 | :165 | :166 |
| `column_dirty.h` serialize | :351 | :352 | :353 | :354 | :355 | :356-358 | :359 |
| `json_codec.cpp` TO | :283 | :283 | :284 | :284 | :285 | :285 | :286 |
| `json_codec.cpp` FROM | :347 | :347 | :348 | :348 | :349 | :349 | :350 |
| `gameview_encode.cpp` | `{78}`:199 | `{79}`:200 | `{80}`:201 | `{81}`:202 | `{82}`:203 | `{83}`:204 | `{84}`:205 |
| `game_view.proto` | :203 | :204 | :205 | :206 | :207 | :208 | :209 |
| `GameCoreJni.cpp` | :755 相邻 | — | :855（op） | — | — | — | — |
| `DiscipleComponents.kt` | :129 | :130 | :131 | :132 | :133 | :134 | :135 |
| `DiscipleSerializer.kt` | :143/:294/:436 | :144/:295/:437 | :145/:296/:438 | :146/:297/:439 | :147/:298/:440 | :148/:299/:441 | :149/:300/:442 |
| `DiscipleTables.kt` | :200 | :201 | :202 | :203 | :204 | :205 | :206 |
| `DiscipleTablesColumnRegistry.kt` | :138 | :139 | :140 | :141 | — | :143 | :142 |
| `DiscipleTablesAssemblers.kt` | :153 | :154 | :155 | :156 | :157 | :158 | :159 |
| `DiscipleTablesWrite.kt` | :168 | :168 | :169 | :169 | :170 | :171 | :172 |
| `AssembleGroup.kt` | :69 | :70 | :71 | :72 | :73 | :74 | :75 |
| `GameViewMirrorCodec.kt` | :541 | :542 | :543 | :544 | :545 | :546 | :547 |
| `DiscipleExtended.kt` | :22/:50 | :23/:51 | :24/:52 | :25/:53 | :26/:54 | — | :27/:55 |
| `DiscipleAggregate.kt` | :116/:183/:330 | :117/:331 | :118/:332 | :119/:333 | :120/:334 | — | :121/:335 |
| `DiscipleDelegates.kt` | :142-143 | :144-145 | :146-147 | :148-149 | :150-151 | :152-153 | :154-155 |
| `game_core.cpp` | :267 | — | — | — | — | :288 | :271 |

### G03-2. C++ 结算落点

| 系统 | 文件 | 起止 | 说明 |
|---|---|---|---|
| **生育整文件** | `child_birth.h` | **1-231 整文件删** | `kRootCountWeights` :51-55；`rootElements` :58-62；`generateSpiritRoot` :67-90；`createChild` :100-139（灵根继承 **116-124**、`parentId1/2` 写 **136-137**）；`processMonthlyBirth` :152-229 |
| 月结生育接线 | `month_settlement.h` | `#include` **:16**；`processChildBirthStep` **211-219**；调用 **2345-2348**；边界注释 :83 | 摘除 |
| 月结自动配对 | `month_settlement.h` | `kPairingProbability` **:98**；`kPairingMinAge` **:100**；`hasBloodRelation` **226-237**；`processPartnerMatching` **239-308**（marriage 事件 **301-304**）；调用 **2427-2428** | **整段删** |
| 年结哀悼 | `year_settlement.h` | `isRelatives` **1536-1541**；`applyGriefToRelativesStep` **1555-1594**；`unbindPartnerColumnsStep` **1596-1607**；调用 **1709/1711** | 删（`unbindMasterColumnsStep` **1609-** **保留**） |
| 年结哀悼到期 | `year_settlement.h` | **1205-1223**；调用 :1864；哨兵 :82 | 删 |
| BereavementDraft | `year_settlement.h` | **138-143**；导出 :1791 | 删 |
| 战斗丧亲 | `battle_residual_tx.h` | `LifeEventDraft` **94-100**；`casualty_detail::applyGriefToRelativesBattle` **111-170**（关系判定 131-139、写点 146、文案 147-164）；`lifeEvents` :107 | 删 |
| 亲缘 7 列 | `disciple_store.h` **261-269** / `models.h` **390-398** | | 删 |
| 父母加成 | `phase_settlement.h` | `parentBonusFor` **300-312**；接入 **358-359**、**1016-1017** | 删（`masterBonusFor` **314-328** **保留**） |
| 亲属赠礼 | `relative_gift.h` | **全文 438 行**：概率常量 **47-52**；`GiftRelationshipType` **62-69**；`giftProbability` **71-81**；查找/分类 121-438 | **收缩为仅师徒（师 0.40/徒 0.30）** |
| 赠礼接线 | `phase_settlement.h` | `#include` **:26**；调用 **1175-1189** | 保留 |
| AI 宗依赖 | `ai_sect_recruit.h` | `#include child_birth.h` **:43**；`generateSpiritRoot` **:126**；`age=16+nextInt(14)` :160；`loyalty` :166；`lifespan=computeLifespan` :188 | **改接 `name_service.h`（:45 已 include）** |
| 婚姻事务 | `disciple_lifecycle_tx.h` | `MarriageApproveResult` **:200**；`approveMarriageTransaction` **355-396**（双绑 384-385）；`rejectMarriageTransaction` **404-**；头注释 :12/:37 | **整段删** |
| 派发接线 | `execute_dispatch.cpp` | **2620-2625**；**2629-2631** | 删 |
| 边界列 | `game_core.cpp` | :267/:271/:288/:289 | 删 |

### G03-3. ActionId 处置

| 编号 | 常量 | 定义处 | 处置 |
|---|---|---|---|
| **1592** | `DISCIPLE_LIFECYCLE_MARRY_APPROVE` | `action_ids.h:373` / `ActionIds.kt:369` / `core.mjs:209` | **删实现 + 留洞**（spec §6.5 明确） |
| **1750** | `DISCIPLE_LIFECYCLE_MARRY_REJECT` | `action_ids.h:547` / `ActionIds.kt:543` / core.mjs | **删实现 + 留洞**（spec 未列但同族，建议同批） |
| 1591 | `DISCIPLE_LIFECYCLE_APPRENTICE` | `action_ids.h:370` | **保留**（师徒） |
| 1590 | `DISCIPLE_LIFECYCLE_EXPEL` | `:367` | §5.3 批 → 留洞 |
| 1501 | `FAVOR_GIFT` | `:325` | 保留（好感≠亲属，需现场确认） |
| 1103/1106/1436/1593/1612/1632/1712 | 见 G02-3 | | 留洞 |

### G03-4. Kotlin/UI 落点

| 文件 | 行 | 处置 |
|---|---|---|
| `core/engine/.../system/ChildBirthSystem.kt` | **全文 186 行**（`:25 @SystemPriority(235)`、:44/:48/:52/:87/:95/:137/:176-182） | **整文件删** |
| `core/engine/.../system/PartnerSystem.kt` | **全文 157 行** | **整文件删** |
| `core/engine/.../system/SystemManager.kt` | :1-100 | **删两处注册** |
| `core/engine/.../service/RelativeGiftHandler.kt` | **全文 420 行** | **收缩为仅师徒** |
| `core/domain/.../model/GiftRelationshipType.kt` | **全文 9 行** | **收缩** |
| `core/domain/.../state/PendingMarriageProposal.kt` | **全文 17 行** | **整文件删** |
| `GameEngine*.kt` `approveMarriageProposal`/`rejectMarriageProposal` | 待现场 grep | 删 |
| `core/engine/.../service/DiscipleLifecycleProcessor.kt` | :14 + 哀悼/解绑段 | 删 |
| `core/domain/.../state/DiscipleTables.kt` | `GRIEF_YEAR_NULL_SENTINEL` :249 + 列 | 删 |
| `core/domain/.../model/Disciple.kt` | `hasPartner` :196 | 删 |
| `core/domain/.../model/DiscipleAggregate.kt` | :116-121/:183/:330-335 | 删 |
| `core/domain/.../model/GameData.kt` | :615/:620 | 删 |
| `ui/game/dialogs/DaoCompanionManagementDialog.kt` | **全文 159 行** | **整文件删** |
| `ui/game/dialogs/SectManagementDialog.kt` | :152/:164 | 删按钮 |
| `ui/game/dialogs/GameNotificationDialog.kt` | :14/:17 `MarriageApprovalDialog` | 删 |
| `ui/game/components/GameOverlayHost.kt` | :22/:42/:49/:51/:106/:142/:217/:246/:258/:264/:286/:294/:345-359 | 删整段 |
| `ui/game/delegate/DiscipleDelegateLifecycleOps.kt` | :15-23 | 删 |
| `ui/game/GameViewModel.kt` | :74/:556 `pendingMarriageProposals` | 删 |
| `ui/game/delegate/AutoAssignDelegate.kt` | :15/:17/:26/:31 | 删两项设置 |
| `ui/game/components/detail/DetailActionButtons.kt` | :284-307/:373-374 | **删亲缘段**（师徒段保留） |
| `ui/game/components/detail/DetailBasicInfoSection.kt` | :39/:70/:87/:186/:199/:398 | 删 |
| `ui/game/dialogs/DiscipleChatDialog.kt` | 结缘入口（待 grep） | 删 |
| `ui/game/dialogs/AutoManagementDialog.kt` | 全文 305 行 | 删道侣项 |
| `ui/game/tabs/DisciplesTab.kt`（137 行）/`DiscipleManagementDialog.kt`（165 行） | 待确认 | — |

### G03-5. 测试落点

| 文件 | 行 | 处置 |
|---|---|---|
| `child_birth_test.cpp` | **全文 166 行** | **整文件删** |
| `month_settlement_test.cpp` | :345/:374/:395 `PartnerMatching*`；:1136-1140 | **删除** |
| `relative_gift_test.cpp` | :122 `PartnerBeatsParent` 等（全文 407 行） | **改断言**（仅师徒） |
| `battle_residual_tx_test.cpp` | 丧亲用例 | **删除** |
| `year_settlement_test.cpp` | :751-758 哀悼段 | **删除** |
| `disciple_store_test`/`column_export_equivalence_test`/`json_codec_test`/`gameview_encode_test`/`column_dirty_test` | 列双射 | **必须同步** |
| `ai_sect_ops_test.cpp`（279 行）/`manual_recruit_test.cpp`/`recruit_tx_test.cpp` | 灵根/亲缘残留 | **改断言** |
| `core/engine/.../system/ChildBirthSystemTest.kt` | :31 | **整类删** |
| `core/engine/.../system/PartnerSystemTest.kt` | :20 | **整类删** |
| `core/engine/.../GameEngineMarriageProposalTest.kt` | :198 | **整类删** |
| `core/engine/.../service/RelativeGiftHandlerTest.kt` | :41 | **改断言** |
| `core/engine/.../domain/disciple/DiscipleServiceApprenticeTest.kt` | :210 | **保留**（师徒） |
| `core/domain/.../model/DiscipleModelsTest.kt` | social 段 | **改断言** |
| `core/data/src/test/.../serialization/unified/*`（`ProtoNumberCoverageTest`:257/`ProtoNumberUniquenessTest`:109/`SaveDataDirectSerializationTest`:164/`SaveDataReconcilerTest`:177/`SaveDataMailWireRoundtripTest`:178） | reserved 后 | **必须同步** |
| `RoomMigrationTest.kt`:925/`MigrationChainGuardTest.kt`:60/`RoomMigrationLegacyTest.kt`:303/`RoomMigrationRecoveryTest.kt`:143 | 迁移链 | **新增 v54→v55** |
| `ArchivePayloadRoundTripTest.kt`/`CloudPayloadSizeBenchTest.kt` | 夹具 | **改断言** |
| `MirrorProtoFeedFixture.kt`/`MirrorProtoFeedEquivalenceTest.kt`:211/`DiffStateTest.kt`/`DiffColumnExportMergeConvergenceTest.kt`:164/`DiffMirrorArmConvergenceTest.kt` | 镜像夹具 | **改断言** |
| `core/engine/.../gameview/*`（`GameViewDiscipleProjectionTest`:146/`GameViewDiscipleColumnApplyEquivalenceTest`:300/`GameDataFieldPatchGuardTest`:258/`BaselineFieldCoverageGuardTest`:93） | 列覆盖守卫 | **必须同步** |
| `core/domain/.../state/DiscipleTables*Test.kt`（12 文件） | 列级守卫 | **必须同步** |
| `feature/game/src/test/.../saveload/*`（`SaveMigrationCoordinatorTest`:442 等） | | 同步 |

### G03-6. 配置源落点

| 源 | 键 | 处置 |
|---|---|---|
| `core/domain/.../config/GameConfigData.kt` | `PAIRING`/`DaoCompanion`/`MARRIAGE`/`GRIEF`/`BIRTH` 段（待现场 grep 行号） | **删段** |
| `core/domain/.../GameConfig.kt` | 配对概率/最小年龄/道侣禁灵根 | **删** |
| `assets/config/game_config.json` | `daoCompanion*`/`pairing*`/`marriage*` 段 | **删段** |
| `assets/data/game-data.json` | 由 `gen-game-data.mjs` 生成 | **重生成** |
| `scripts/gen-game-data.mjs` | 汇总；无亲缘键 | 重跑 |
| `game_view.proto` | `78,79,80,81,82,83,84` → reserved | **改** |
| `scripts/data/gacha_config_sample.json` | G01 新增 | 保留 |

### G03-7. grep 清零清单

| # | 正则 | 预期命中归属 |
|---|---|---|
| 1 | `partnerId\|partnerSectId\|partnerIds\|partnerSectIds\|PartnerId\|PartnerSectId\|social_partner` | 生产+测试+历史 Migration+Proto；归零（历史 Migration 例外） |
| 2 | `parentId1\|parentId2\|parentId1s\|parentId2s\|ParentId1\|ParentId2\|social_parentId` | 同上；归零 |
| 3 | `lastChildYear\|lastChildYears\|LastChildYear\|social_lastChildYear\|childBirthMonth\|childBirthMonths\|ChildBirthMonth\|social_childBirthMonth` | 同上；归零 |
| 4 | `griefEndYear\|griefEndYears\|GriefEndYear\|GRIEF_YEAR\|social_griefEndYear\|[Gg]rief\|丧亲\|哀悼\|悲痛` | 生产+测试+UI+Proto；归零 |
| 5 | `ChildBirth\|child_birth\|processMonthlyBirth\|createChild\|inheritName\|inheritSpiritRoot\|SpiritRootGenerator\|generateSpiritRoot` | 生产+测试；**`SpiritRootGenerator` 被 `ai_sect_recruit.h:126` 依赖 → 改接 `name_service` 后才可归零** |
| 6 | `PartnerSystem\|processPartnerMatching\|PAIRING\|kPairingProbability\|kPairingMinAge\|daoCompanionBannedRootCounts\|daoCompanionConsentRequired\|[Pp]endingMarriageProposal` | 生产+测试+UI；归零 |
| 7 | `RelativeGift\|relative_gift\|GiftRelationshipType\|kPartnerGiftProb\|kParentGiftProb\|kChildGiftProb\|kSiblingGiftProb` | 生产+测试；**收缩后只应剩 `kMasterGiftProb=0.40`/`kApprenticeGiftProb=0.30`** |
| 8 | `hasBloodRelation\|areRelatives\|isRelatives\|unbindPartner\|applyGriefToRelatives\|computeBereavement\|BereavementDraft` | 生产+测试；归零 |
| 9 | `MARRY_APPROVE\|MARRY_REJECT\|approveMarriage\|rejectMarriage\|MarriageApprovalDialog\|MARRIAGE` | 生产+测试+UI+ActionId 目录；归零（目录留退役条目） |
| 10 | `MARRIAGE\|结为道侣\|道侣\|结缘\|配偶` | 生产+UI+事件文案+文档；归零 |
| 11 | `parentBonusFor\|getParentSpiritRootBonus\|parentSpiritRootBonus\|calculateParentBonus` | 生产+测试；归零 |
| 12 | `masterId\|masterIds\|MasterId\|师徒\|拜师\|APPRENTICE` | **反向守卫：必须仍有命中** |

### G03-8. RNG 影响

| 删除项 | 分区 | 影响 | G10 重录 |
|---|---|---|---|
| 月结生育 `processMonthlyBirth`（性别 1 + inheritName N + 灵根 1(+洗牌 5) + createDisciple ~34 次） | **SYSTEM** | 月结序列**大幅平移** | ✅ **必须** |
| 月结自动配对 `processPartnerMatching`（每个通过过滤的 (男,女) 组合 1 次 `nextDouble`） | **SYSTEM** | 月结序列平移 | ✅ **必须** |
| 亲属赠礼（每亲属 1 次 `nextDouble`） | **SYSTEM** | 关系从 6 类收缩为 2 类 → 亲属集合变小 → 旬结序列平移 | ✅ **必须** |
| 年结哀悼传播/到期 | 零 RNG（`:1576` 段无抽取） | 无直接平移，改弟子状态集 | ⚠ 建议 |
| AI 宗弟子生成 | **AI 独立分区**（`systemSeed + AI_SECT.id(6)×31337`） | 改接 `name_service` 后**必须保持灵根生成消费顺序逐位一致** | ✅ **必须** |
| 亲缘 7 列删除 | 无 RNG | 删列不动行序，安全 | ❌ |
| `PendingMarriageProposal` 删除 | 无 RNG（UI 运行态） | 无 | ❌ |

**关键红线**（`disciple_store.h:22-26`）：「**行序 == JSON 数组序 == Kotlin ids 序**——突破（BREAKTHROUGH）与伴侣配对（SYSTEM）的 RNG 抽取序列依赖该顺序；禁止任何重排/压缩」。删列不动行序安全；删「配对」消费点会平移 SYSTEM。

---

## 附：施工顺序建议

1. **先决**：~~处理在途脏工作区（F1）~~ → G07 已落库、工作树已回净（见「时效说明」A）；直接以 `19dffb6d2` 为基线开工。
2. **G02 C++ 面**（`month_settlement.h:1096-1852` → `year_settlement.h:285-308/479-568/1676-1758` → `game_core.cpp:264-289`）→ 再 C++ 测试；同批完成 **§9 的 G07 收口项**。
3. **G02 三端字段链**（`models.h` → `DiscipleColumn` → `disciple_store` → `column_dirty` → `json_codec` → `gameview_encode` → `game_view.proto` → JNI → Kotlin 模型/表/镜像 → Room 实体 + v54→v55）。
4. **G03** 同序；`child_birth.h` 整文件删为第一步；`ai_sect_recruit.h:126` 改接 `name_service` 是**唯一破坏 AI 分区序列**的点，单独验证。
5. **ActionId**：改 `scripts/action-catalog/*.mjs` → `node scripts/gen-action-ids.mjs` → `scripts/check-jni-count.mjs`。
6. **配置源**：改 `scripts/data/*_sample.json` + `gen-*.mjs` → 跑 `gen-game-data`/`gen-trait-db`/`gen-recipe-db` → 提交 `game-data.json` + `game-data.hash.txt`。
7. **G10**：重录 GTest 黄金序列 + 全部 `Diff*Test` 对拍基线（3 条 SYSTEM 序列 + BREAKTHROUGH + EXPLORATION 全受影响）。
8. **必跑守卫**：`RoomMigrationTest`、`MigrationChainGuardTest`、`ProtoNumberUniquenessTest`、`ProtoNumberCoverageTest`、`column_export_equivalence_test`、`GameViewStoreGuardTest`、`GameDataFieldPatchGuardTest`、`BaselineFieldCoverageGuardTest`、`DiscipleTables*Test`(12)、`MirrorReadOnlyGuardTest`、`DiffAuthoritativeTickTest`、`GameConfigConsistencyTest`。

### 未核实/推测项

- **推测**：`assets/config/game_config.json` 中 `lawEnforcement`/`daoCompanion*` 段的**具体行号**（紧凑 JSON，行号意义有限）；`ConfigLoaderTest.kt:111-113` 已证明 `lawEnforcement.loyaltyThreshold/probPerPoint/newDiscipleProtectionMonths` 存在。
- **推测**：`GameEngine.kt` 中 `approveMarriageProposal`/`rejectMarriageProposal` 的定义行；`GameEngineMarriageProposalTest.kt` 证明其存在。
- **推测**：`settleBattleCasualtiesTx` 内 `applyGriefToRelativesBattle` 的**调用点行号**（G07 后已实测为 `battle_residual_tx.h:266`，见 §9）。
- **推测**：`DiscipleTables.kt` 中 `loyalties`/`moralities` 的**精确行号**（`comprehensions:213`/`aptitudes:220` 命中，loyalty 应在 :211-212 区间）。
- **未覆盖**：`:core:ui` 与 `:app` 模块 UI；`changelog_entries.json` + `CHANGELOG.md`（§12.4 强制）未列。
- **未覆盖**：`docs/`、`CODE_WIKI.md`、`docs/architecture.md`、`docs/cpp-engine.md`、`docs/ui-read-surface.md` 的同步义务点（属 G10 文档批）。

---

# §9 G07 后新增的收口项（G02 顺带完成）

> 以下行号均为 **G07（`19dffb6d2`）之后实测**。G07 把「玩家侧战死」改成「重伤（`currentHp=1` 存活）」，因此留下了一批**只在非战斗/旧档路径可达**的分支与命名与语义不符的符号。这些应在 G02（删寿元链、叛逃、偷盗、神魂）时**一并收口**，避免两批各删一半造成"半死码"。

## 9.1 `battle_residual_tx.h` 的 `isOutsideSect=false` 分支

`settleBattleCasualtiesTx` 现在是「同一函数、两条互斥链」，G02 删除寿元链后 `false` 侧再无生产调用方。

| 落点 | 行 | 说明 |
|---|---|---|
| 签名参数 `bool isOutsideSect` | **:177** | 位置参数，调用方靠字面量 `true/false` 传意 |
| `if (!isOutsideSect)` 宗门内回收段 | **:199-217** | 四槽装备 + 袋内装备/功法 + 已学功法收集（`collectInSectItemLoss` 等价） |
| `if (isOutsideSect) { … } else { … }` | **:256-283** | `true` = `markDead` 重伤（:257-262）；`false` = 悲痛传播（**:266**）+ `materializeDiscipleBagAndMarkDead`（**:274**）/ 兜底标死（:277-279） |
| `if (!isOutsideSect) {` C 段 | **:285-** | 装备/功法/熟练度清理 |
| `casualty_detail::applyGriefToRelativesBattle` | **:120-168**（namespace 闭于 :170） | G03 主删对象（依赖 `partnerIds`/`parentId1s`/`parentId2s`/`griefEndYears`）；调用点 **:266** |
| `LifeEventDraft` 结构 | **:96-100** | 悲痛日志草稿载体；G03 后仅剩突破日志用途（`:436`/`:519-520`） |
| `kDeadStatusName` 写入 | **:278** | 见 §9.8 |
| `#include death_handler.h` | **:14**（注释 `// kDeadStatusName`） | 若 :278 删除则注释失效 |
| `#include secret_realm_session.h` | **:21**（注释 `// … materializeDiscipleBagAndMarkDead`） | 若 :274 删除则需降级为前向声明或删 include |

**收口动作**：G02 删除寿元链后，`isOutsideSect=false` 侧（`:199-217`、`:264-283` else 支、`:285-` C 段）应**整段删除**，函数只剩重伤写 + 幸存者 HP/MP 回写（`:285-` 之后的 E 段 `computeSurvivorUpdates` 结果回写，位于 `:332-336`）。

**注意**：`worldLevelVictoryTx`（**:401-429**）现已无实质写入（`:421-426` 只剩 `(void)` 占位），G02 删除 `applyDeterministicWinAttr`（**:358-397**）与 `WorldVictoryOutcome::soulPowerCount`（**:347**）/`winAttrCount`（**:348**）/`theftCandidateIds`（**:349**）后，该事务应整体退役或收敛为「只写 `defeated`」（当前注释明确「不写 defeated——残差留 Kotlin 臂」）。

## 9.2 `materializeDiscipleBagAndMarkDead` 在寿元链删除后成死码

⚠ **精确结论：不是全域死码，而是"玩家侧战斗路径"的消费方清零**——C++/Kotlin 两侧各仍有余下消费方，收口时必须区分。

| 侧 | 落点 | 行 | 处置 |
|---|---|---|---|
| C++ 定义 | `secret_realm_session.h` | **:513**（`:518` 内调 `markDead`；`:6`/`:24-25` 注释；`:681` 内部调用） | 保留（仍有消费方） |
| C++ 消费 | `battle_residual_tx.h` | **:274** | **G02 删**（isOutsideSect=false 分支） |
| C++ 消费 | `exploration_tx.h` | **:181**（`writeBackExplorationCasualties`，探索/世界关卡队伍成员 `c.isDead()`） | **保留**（探索域，非寿元链） |
| Kotlin 定义 | `InventorySystem.kt` | **:285**（`:62` 注释） | 保留 |
| Kotlin 消费 | `CombatService.kt` | **:329**（在 `applyLegacyCasualtyChain` 内） | **G02 删** |
| Kotlin 消费 | `SecretRealmService.kt` | **:931** | **保留**（秘境） |
| Kotlin 消费 | `GameEngineScoutOps.kt` | **:201**（`cause="scout"`） | **保留**（侦察） |
| Kotlin 消费 | `GameEngineWorldBattleOps.kt` | **:144** | 需现场确认是否属世界关卡死亡链 |
| 测试 | `InventorySystemDeathMaterializeTest.kt` | :20/:66/:84/:100/:112 | 随消费方调整 |
| 测试 | `SecretRealmServiceTest.kt` :69、`BattleResidualNativeTxGateTest.kt` :131 | verify 调用 | 需同步 |

**收口动作**：删除 `battle_residual_tx.h:274` + `CombatService.kt:329` 两个消费方；**保留** `exploration_tx.h:181`、`SecretRealmService.kt:931`、`GameEngineScoutOps.kt:201`。同时修正 `secret_realm_session.h:6/:24-25` 与 `InventorySystem.kt:62` 里「死亡袋物化」的注释（G07 后语义已是"重伤或非战斗死亡"）。

## 9.3 `CombatService.applyLegacyCasualtyChain` 及其依赖链

| 落点 | 行 | 处置 |
|---|---|---|
| `processBattleCasualties`（公开入口） | **:75** | 保留；`:79 isOutsideSect: Boolean = true` 见 §9.5 |
| `:82-83` 分支注释 + `if (isOutsideSect && deadMemberIds.isNotEmpty())` | **:82-83** | 收口后改为单一重伤入口 |
| `tryNativeCasualtySettle(...)` | **:186**（调用 :91；`:205` put `"isOutsideSect"`；`:213` `emitNativeDeathEvents`） | 保留（native 主路径），`:205`/`:213` 的 `isOutsideSect` 传参见 §9.5 |
| **`applyLegacyCasualtyChain`** | **:127**（调用 :96） | **G02 删**（KDoc :124-125 已自述「G07 后生产无调用方…待 G02 删除寿元链后一并退役」） |
| `CasualtyData` 数据类 | **:383** | **删**（仅被 `collectCasualtyData` 用） |
| `SurvivorUpdate` 数据类 | **:429** | 需现场确认（`computeElderSlotUpdates` 返回类型） |
| **`collectCasualtyData`** | **:395**（内部 `:415 if (isOutsideSect)`） | **删** |
| **`markCasualtiesDead`** | **:322** | **删** |
| **`removeCasualtyItems`** | **:334** | **删** |
| **`computeElderSlotUpdates`** | **:432** | **删** |
| `computeSurvivorUpdates` | **:254** | 需现场确认是否被 native 路径复用（若仅 legacy 用 → 删） |
| `applyGriefUpdatesToTables` | **:277** | **G03 删**（悲痛链路） |
| `applySurvivorHpMpUpdates` | **:355** | 需现场确认 |
| `clearDeadFromProductionRepository` | **:369** | 需现场确认（G07 后玩家侧无死亡 → 可能成死码） |
| `collectGriefUpdates`（文件级私有） | **:490** | **G03 删** |
| `collectInSectItemLoss`（文件级私有） | **:509** | 随 `applyLegacyCasualtyChain` 删 |
| `clearDeadDirectSlots`（文件级私有） | **:530** | 随 `applyLegacyCasualtyChain` 删 |

**收口动作**：整条 legacy 链（`:127/:322/:334/:383/:395/:432` + 文件级 `:490/:509/:530`）一次性删除；删除前先用一次 grep 确认 `:254`/`:355`/`:369`/`:429` 无其他消费者。删完后 `processBattleCasualties` 只剩「重伤写 + native 尝试 + 幸存者回血」三段。

## 9.4 `backfillDeathYears` 的保留判据（旧档）

| 落点 | 行 | 保留理由 |
|---|---|---|
| Kotlin `DiscipleDeathHandler.backfillDeathYears` | **:50** | 只对 `!it.isAlive` 的行补写 → **历史已死亡行专用** |
| Kotlin 唯一生产调用 | `ExplorationService.kt` **:509** | 读档/探索初始化时的旧档自愈 |
| C++ `death_handler.h:backfillDeathYears` | **:78**（注释 :75-77「对快照列表中 `!isAlive` 且 `deathYears` 无记录的弟子补写」） | 同上 |
| JNI 派发 | `execute_dispatch.cpp` **:2038-2046**（`case action::DISCIPLE_BACKFILL_DEATH_YEARS`）、段判定 **:2687-2688** | 契约保留 |
| 测试 | `death_handler_test.cpp` **:99**、`DiffDeathHandlerTest.kt` **:185/189/194/216** | 保留 |

**判据结论**：**保留**。理由 —— G07 只改了**玩家侧战斗**的死活语义；以下路径仍会产生 `isAlive=false` 行：(a) 旧存档（`deathYears` 缺省 0）；(b) AI 宗弟子死亡（`ai_sect_ops.h:642/657`）；(c) AI 秘境队员死亡（`secret_realm_session.h:972`）；(d) 非玩家侧战斗（`PatrolBattleSystem.kt:757`、`AISectBeastAttackProcessor.kt:301/407`、`EncounterBattleService.kt:401`、`SecretRealmService.kt:721`）。G02 删寿元链**不改变**这些来源。
**唯一需处理**：`DiscipleLifecycleProcessor.kt:329`（`discipleTables.statuses[id] = DiscipleStatus.DEAD`）属**老死链**，随 G02 删除 → 玩家侧 `isAlive=false` 的最后一个生产写入方消失，**需在 KDoc 显式登记「backfill 仅服务旧档 + AI 侧，玩家侧无新写入方」**，避免后人误判为死码删除。

## 9.5 `isOutsideSect` 布尔语义过载 → 显式命名参数

| 侧 | 落点 | 行 |
|---|---|---|
| Kotlin 默认参数 | `CombatService.kt` | **:79** `isOutsideSect: Boolean = true` |
| Kotlin 分支 | `CombatService.kt` | **:82-83**、**:91**（传 `tryNativeCasualtySettle`）、**:96**（传 legacy）、**:131**（legacy 形参）、**:190**（native 形参）、**:205**（`put("isOutsideSect", …)`）、**:213**、**:397**（`collectCasualtyData` 形参）、**:415** |
| C++ 形参 | `battle_residual_tx.h` | **:177**；分支 **:199**、**:256**、**:285** |
| JNI 线协议键 | `CombatService.kt:205` → `execute_dispatch.cpp` 对应 read（`isOutsideSect`） | 需 grep 复核 C++ 侧 `params.value("isOutsideSect"…)` |

**语义冲突实证**：`isOutsideSect == true` 当前表示 **"宗门外的战斗败北" → 重伤**（`battle_residual_tx.h:256-262`、`CombatService.kt:83`）；而 `false` 表示 **"宗门内/非战斗路径" → 完整死亡链**。名字字面（"是否在宗门外"）与行为（"是否重伤"）**不一致**，且默认值 `true` 使"漏传参数"静默走重伤——危险默认值。

**收口动作（三选一，需产品/架构确认）**
1. 拆分函数：`settleBattleInjuriesTx`（重伤）+ `settleFullDeathChainTx`（完整链，G02 后删除）；
2. 保留单函数，参数改名 `battleDefeatResultsInInjury` 并**去掉默认值**（强制显式传参）；
3. 引入强类型枚举 `enum class DefeatSemantics { kInjury, kFullDeath }` 替代 `bool`。
任一方案均需同步：`CombatService.kt:79/:91/:96/:131/:190/:205/:213/:397/:415`、`battle_residual_tx.h:177/:199/:256/:285`、JNI JSON 键、`BattleResidualNativeTxGateTest.kt`、`DiffDeathHandlerTest.kt`。

## 9.6 `markDead` / `markAllDead` 命名与语义不符 → 改名落点

G07 后「`markDead`」实际写的是**重伤**；「`markAllDead`」实际是**批量重伤**。注释已多处自述「名字保留兼容调用点」，属显式技术债。

**C++ 侧**

| 落点 | 行 | 现名 → 建议名 |
|---|---|---|
| `death_handler.h` 常量 `kInjuredHp` | :29 | 保留 |
| `death_handler.h` `kDeadStatusName` | **:32** | 见 §9.8 |
| `death_handler.h` `MarkDeadResult` | :38-41 | `MarkInjuredResult` |
| `death_handler.h` `markDead(...)` | **:51** | `markInjured` |
| `death_handler.h` `markAllDead(...)` | **:65** | `markAllInjured` |
| `death_handler.h` 文件头注释 | :10-25 | 同步 |
| `battle_residual_tx.h` 调用 | **:259** | 改名后同步 |
| `secret_realm_session.h` 调用 | **:518** | 改名后同步 |
| `sect_defense_battle.h` include 注释 + 调用 | **:49**、**:388** | 改名后同步 |
| `sect_conquest.h` 调用 | **:402** | 改名后同步 |

**Kotlin 侧**

| 落点 | 行 | 现名 → 建议名 |
|---|---|---|
| `DiscipleDeathHandler.markDead(state, Int, deathYear)` | **:25** | `markInjured` |
| `DiscipleDeathHandler.markInjured(tables, id)` | **:30**（内部调 `tables.markDead(id, currentYear = 0, cause = "battle")` @**:31**） | 已是新名，保留 |
| `DiscipleDeathHandler.markDead(state, String, deathYear)` | **:34** | `markInjured`（重载） |
| `DiscipleDeathHandler.markAllDead(state, Set<String>, deathYear)` | **:39** | `markAllInjured` |
| `DiscipleDeathHandler.isInjured` | **:69** | 保留 |
| `DiscipleTables.markDead(id, currentYear, cause)` | **:883**（KDoc :875-879 已注「名字保留兼容调用点」） | `markInjured`；`:884` 的 `VALID_DEATH_CAUSES` 校验语义亦需重审 |
| `DiscipleTables.kt` 缓存失效注释 | :70、:1047 | 同步文案 |

**JNI 契约（编号不动，只改常量名 + 目录描述）**

| 编号 | 现常量名 | C++ / Kotlin / 目录 | 处置 |
|---|---|---|---|
| **1404** | `DISCIPLE_MARK_DEAD` | `action_ids.h:160` / `ActionIds.kt:156` / `scripts/action-catalog/*.mjs` | 改名 `DISCIPLE_MARK_INJURED`，**编号 1404 不复用/不重排** |
| **1405** | `DISCIPLE_BACKFILL_DEATH_YEARS` | `action_ids.h:163` / `ActionIds.kt:159` / 目录 | 名可保留（语义确为"回填死亡年份"） |
| 派发实现 | `execute_dispatch.cpp` **:2012-2013**（注释）、**:2019**（`case action::DISCIPLE_MARK_DEAD`）、**:2038**、**:2046** | 同步 |
| 段判定 | `execute_dispatch.cpp` **:2687-2688**（`actionId >= DISCIPLE_MARK_DEAD && actionId <= DISCIPLE_BACKFILL_DEATH_YEARS`） | 改名不影响区间；**但若将来在 1404–1405 间插值需重审** |
| 生成链 | `scripts/gen-action-ids.mjs` → `action_ids.h` + `ActionIds.kt` | 改目录源后重生成；跑 `scripts/check-jni-count.mjs` |

**测试引用点（改名后必须同步）**

| 文件 | 行 |
|---|---|
| `death_handler_test.cpp` | **:44**（`EXPECT_NE(..., kDeadStatusName)`）、**:80**（`markAllDead`）、**:99**（`backfillDeathYears`） |
| `exploration_tx_test.cpp` | **:293**（`EXPECT_NE(gamecore::system::kDeadStatusName, …)`） |
| `battle_residual_tx_test.cpp` | **:125**（`EXPECT_NE(ds.statuses[doomed], "DEAD")`） |
| `sect_defense_battle_test.cpp` | :219、:229、:301 |
| `sect_conquest_test.cpp` | :215 |
| `ai_sect_ops_test.cpp` | :271（AI 侧 `"DEAD"`，与改名无关） |
| `ai_corpse_budget_test.cpp` | :33 |
| `DiffDeathHandlerTest.kt` | **:28**（类注释）、**:115/:143/:162**（`ActionIds.DISCIPLE_MARK_DEAD`）、**:185/:189/:194/:216**（`backfillDeathYears` / `ActionIds.DISCIPLE_BACKFILL_DEATH_YEARS`） |
| `DiscipleDeathHandlerTest.kt` | 全文（G07 已改） |
| `InventorySystemDeathMaterializeTest.kt` | :20/:66/:84/:100/:112 |
| `SecretRealmServiceTest.kt` | :69 |
| `BattleResidualNativeTxGateTest.kt` | :131 |

## 9.7 `propagateGriefToRelatives`（`sect_defense_battle.h`）与其依赖 `isRelativeOf` 的归属

| 落点 | 行 | 说明 |
|---|---|---|
| `kGriefEndYearNone` 常量 | **:73-75**（注释自述「与 `year_settlement.h kGriefYearNullSentinel` 同值——互包不可共享」） | 随 `griefEndYears` 列删 |
| `isRelativeOf(ds, a, b)` | **:312** | 依赖 `partnerIds`/`parentId1s`/`parentId2s`（G03 删） |
| `propagateGriefToRelatives(ds, …)` | **:329**（段注释 :307/:326-328） | 依赖 `griefEndYears` 列 |
| 内部判定 | **:336-341**（`:336 isRelativeOf`、`:337-340 max 语义`、`:341` 列写） | — |
| 调用点 | **:388**（`markAllDead` 同段，注释 :328「必须在 markAllDead **之前**逐死者调用」） | — |
| `#include death_handler.h` | **:49**（注释 `// markAllDead（死亡统一入口）`） | 见 §9.8 |
| `#include slot_cleanup.h` | **:50**（注释 `// clearAllSlotsDataOnly`） | 见 §9.9 |

**归属结论**：**全部归 G03**。`propagateGriefToRelatives` + `isRelativeOf` + `kGriefEndYearNone` 依赖的四个列（`partnerIds`/`parentId1s`/`parentId2s`/`griefEndYears`）**全部是 G03 删除对象**，因此这三者应在 G03 批次随列删除一并退役，**不应留在 G02**。G02 只需删除 `battle_residual_tx.h` 侧的同类逻辑（§9.1）。
**注意**：`sect_defense_battle.h:388` 的 `markAllDead` 调用本身属**玩家防守战败北**——G07 后是否也应是重伤？需现场核实该路径是否已改（若仍是 `markAllDead`，则 G07 有遗漏，应登记）。同时 `:328` 注释「必须在 markAllDead 之前」在 G03 删除悲痛后需重写。

## 9.8 `death_handler.h` 的 `kDeadStatusName` 在玩家侧不再写入后还有哪些消费者

**定义**：`death_handler.h:32` `inline constexpr const char* kDeadStatusName = "DEAD";`（注释 :31「保留兼容；玩家侧不再写入」）。

**消费者清单（实测）**

| 消费者 | 文件:行 | 性质 | G02 后处置 |
|---|---|---|---|
| 写入（玩家侧战斗死亡链） | `battle_residual_tx.h` **:278** | **生产写入** | **G02 删**（isOutsideSect=false 分支） |
| include 注释引用 | `battle_residual_tx.h` **:14** | 注释 | 同步改/删 include |
| 断言（不再写 DEAD） | `death_handler_test.cpp` **:44** | 测试 | 保留（反向断言价值） |
| 断言（不再写 DEAD） | `exploration_tx_test.cpp` **:293** | 测试 | 保留 |
| include 注释引用 | `sect_defense_battle.h` **:49** | 注释 | 与 §9.7 同批 |

**其它 DEAD 写入方（不使用本常量，各自字面量）——解释"玩家侧不再写入"的边界**

| 写入方 | 文件:行 | 归属 |
|---|---|---|
| AI 宗弟子死亡 | `ai_sect_ops.h` **:642**、**:657**（`d.status = "DEAD"`） | AI 侧，保留 |
| AI 秘境队员死亡 | `secret_realm_session.h` **:972** | AI 侧，保留 |
| 通用状态常量 | `disciple_tx.h` **:1085** `kStatusDead = "DEAD"` | 保留 |
| **玩家侧老死链** | `DiscipleLifecycleProcessor.kt` **:329** `discipleTables.statuses[id] = DiscipleStatus.DEAD` | **G02 删**（这是玩家侧 `DEAD` 的最后一个生产写入方） |
| 状态派生（只读） | `DiscipleStatusService.kt` **:82**（`if (!isAlive) return DiscipleStatus.DEAD`） | 保留（派生规则） |
| 门控（只读） | `ResignGateResult.kt` **:33**（`!isAlive \|\| … == DEAD`） | 保留 |
| AI/巡逻/遭遇战等 | `PatrolBattleSystem.kt:757`、`AISectBeastAttackProcessor.kt:301/407`、`EncounterBattleService.kt:401`、`SecretRealmService.kt:721` | AI 侧，保留 |

**收口结论**：G02 删除 `battle_residual_tx.h:278` 与 `DiscipleLifecycleProcessor.kt:329` 后，`kDeadStatusName` **在生产代码中零写入方**，仅剩两处测试的**反向断言**（"不应写 DEAD"）使用。
**建议**：**保留常量 + 保留反向断言**（它们正是防止 G07 语义回退的守卫），但必须：
1. 修正 `death_handler.h:31` 注释为「玩家侧零写入方；仅测试反向断言 + AI 侧字面量独立」；
2. 修正 `battle_residual_tx.h:14` 的 include 理由注释（该 include 仍需要 `markDead`，只是不再需要 `kDeadStatusName`）；
3. **不要**顺手删常量——两处 `EXPECT_NE(..., kDeadStatusName)` 若改成字面量 `"DEAD"` 会失去"集中常量"的守卫价值。

## 9.9 `slot_cleanup.h` 在 `sect_defense_battle.h` 的 include 是否成死引用

| 落点 | 行 | 实测 |
|---|---|---|
| `#include "gamecore/system/slot_cleanup.h"` | **:50**（注释 `// clearAllSlotsDataOnly`） | **同文件内 `clearAllSlotsDataOnly` / `SlotCleanupInput` 命中数 = 0** |

**结论**：**是死引用**。G07 把玩家侧防守战败北改为重伤后，`sect_defense_battle.h` 不再执行槽位清理，include 与注释同时失效。
**收口动作**：G02 删除 `battle_residual_tx.h:278`（`isOutsideSect=false` 侧）后，全局 `clearAllSlotsDataOnly` 的消费者需**重新盘点**：
- `month_settlement.h` **:1279**（`desertDiscipleCleanup` 内，G02 删除叛逃链后消失）
- `slot_cleanup.h` 自身定义（**保留**，若仍有其它消费方）
- ⚠ **开工前必须跑一次** `rg -n "clearAllSlotsDataOnly" android/app/src/main/cpp` 确认删除后是否还有消费者；若归零则 `slot_cleanup.h` 整文件与 `slot_cleanup_test.cpp`（200 行）一并退役。

## 9.10 §9 收口项优先级与依赖

| 优先级 | 项 | 依赖 | 备注 |
|---|---|---|---|
| P0 | §9.1 `battle_residual_tx.h` `isOutsideSect=false` 段删除 | G02 寿元链删除 | 与 G03 的 `applyGriefToRelativesBattle` 删除同批 |
| P0 | §9.3 `CombatService` legacy 链删除 | §9.1 + §9.2 | 删前先确认 `:254/:355/:369/:429` 无他消费者 |
| P0 | §9.2 两个消费方删除（`battle_residual_tx.h:274`、`CombatService.kt:329`） | §9.1/§9.3 | **保留**其余三个消费方 |
| P1 | §9.9 `slot_cleanup.h` 死引用 + `clearAllSlotsDataOnly` 盘点 | P0 完成 | 可能连锁退役 `slot_cleanup.h` 整文件 |
| P1 | §9.5 `isOutsideSect` 强类型化 | 需架构决策 | 影响 JNI 线协议键 |
| P1 | §9.6 `markDead` 改名 + ActionId 1404 改名 | `gen-action-ids.mjs` 重生成 | 编号禁复用 |
| P2 | §9.4 `backfillDeathYears` KDoc 登记 | §9.6 同批 | 功能保留，只改文案 |
| P2 | §9.7 `propagateGriefToRelatives`/`isRelativeOf` 退役 | **G03** | 不属 G02 |
| P2 | §9.8 `kDeadStatusName` 注释修正 | §9.1 完成 | 常量与断言**保留** |

---

**只读声明（本次文件写入除外）**：除本文件 `docs/design/gacha-batches/recon-G02-G03.md` 外，未修改、未新增、未删除任何仓库文件，未执行任何提交，未运行 gradle / ctest。
