# G04 扩容删除批 — 精确落点清单（侦察档，实施工作依据）

> 性质：**只读侦察产出**。行号 = 代码实测值，非文档推演。
> 权威产品清单：`docs/character-gacha-redesign-2026-09-23.md` **§6.6 / §6.11 / §6.12 / §6.13**。
> 实施计划：`docs/design/character-gacha-implementation.md` G04（v1.5 扩容）。

---

## §0 时效说明（施工前必读）

### 0.1 基线状态

| 项 | 值 |
|---|---|
| 侦察时 HEAD | `89281bcc9`（G01 脚手架） |
| **当前 HEAD** | **`19dffb6d2`**（G07 玩家侧战死 → 重伤，已落库） |
| 工作树 | **代码面已回净**。`git status --porcelain` 仅剩非代码项：`android/app/src/main/assets/atlas/atlas-rgba-manifest.json`、`android/scripts/sprite-uid-map.json`（M），以及未跟踪的 `docs/research/*.md` × 2、`模拟宗门美术素材/`、`模拟宗门音乐音效/` |
| 本档行号口径 | **以 `19dffb6d2` 之后为准**（本档已在 G07 之后重新逐项复核并就地修正，见 §0.2） |

### 0.2 G07 落库后的实测变化（本档已修正）

**G07 commit 范围**：`battle_residual_tx.h`(-36/-) · `death_handler.h` · `secret_realm_session.h` · `sect_defense_battle.h` · `execute_dispatch.cpp`(+1) · 6 个 C++ 测试 · `GameConfig.kt`(+6) · `DiscipleTables.kt`(+23/-) · `DiscipleTablesAssemblers.kt`(+10) · `CombatService.kt` · `ExplorationService.kt` · `ExplorationServiceBeastRaidOps.kt` · `DiscipleLifecycleProcessor.kt` · `SecretRealmService.kt` · `InventorySystem.kt` · `DiscipleDeathHandler.kt` · `PatrolBattleSystem.kt` · 8 个 Kotlin 测试。

**① 行号发生平移的文件（本档已就地修正为 G07 后真值）**

| 文件 | 项 | 侦察时（G01） | **G07 后真值** |
|---|---|---|---|
| `GameConfig.kt` | `object SpiritRoot {` | — | **`:420`** |
| | `WASH_JADE_COST`（SpiritRoot） | `:437` | **`:443`** |
| | `WASH_PITY_THRESHOLD`（SpiritRoot） | `:443` | **`:449`** |
| | `WASH_DOUBLE_WEIGHT` | `:446` | **`:452`** |
| | `WASH_ELEMENT_KEYS` | `:452` | **`:458`** |
| | `enum class TraitWashType` | — | **`:487`** |
| | `object TraitWash {` | — | **`:492`** |
| | `WASH_JADE_COST`（TraitWash） | — | **`:495`** |
| | `WASH_PITY_THRESHOLD`（TraitWash） | `:495` | **`:501`** |
| `DiscipleTablesAssemblers.kt` | `talentIds/physiqueIds/affixIds` 组装 | `:72-74` | **`:79-81`** |
| | `aptitude = aptitudes.getOrDefault(...)` | `:173` | **`:181`** |

**② 行号**未**平移（G07 后已实测确认，可直接采信）**

`battle_residual_tx.h`：`:90 kWinAttrSkillMax` · `:92 kWinAttrMaxLoyalty` · `:347 soulPowerCount` · `:348 winAttrCount` · `:352-397 applyDeterministicWinAttr` · `:396 ++out.winAttrCount` · `:401 worldLevelVictoryTx` · `:424 G02/G04 已删除注释`。
`disciple_stats.h`：`:91 safeBrPct` · `:173 mergeEffects` · `:502 baseStatsWithBr` · `:552 finalStats` · `:712/:772 calculateCultivationPerPhaseColumn` · `:835 comprehensionBreakthroughBonus`。
`disciple.h`：`:33/:34 kSoulPowerDivisor/kSoulPowerMaxSteps` · `:309 BreakthroughZones` · `:318-320 soulPowerBreakthroughBonus` · `:331-347 getBreakthroughChance`。
`execute_dispatch.cpp`：`:2194 handleAppointmentTx` · `:2200 ELDER_APPOINT_TX` · `:2224 SPIRIT_ROOT_WASH_TX` · `:2234 TRAIT_ADD_ROLL_TX` · `:2242 TRAIT_ADD_CONFIRM_TX` · `:2250 TRAIT_WASH_SLOT_TX` · `:2263 SPIRIT_ROOT_WASH_CONFIRM_TX` · `:2270 TRAIT_WASH_CONFIRM_TX` · `:2742/:2745` 中央分支。
`Disciple.kt`：`:50-51 @Entity(tableName="disciples")` · `:95-97` · `:109 soulPower` · `:198` · `:426 DiscipleStats` · `:447`。
`DiscipleSerializer.kt`：`:367 @ProtoNumber(22)` · `:368 (104)` · `:369 (105)` · `:449 (51)` · `:464 (110)`。
`gameview_encode.cpp`：`:138/139/140`（17/18/19）· `:210`（comprehension 89）· `:217`（aptitude 96）。
`game_view.proto`：`:127/128/129`（repeated string 17/18/19）· **`:216 optional int32 comprehension = 89;`** · **`:223 optional int32 aptitude = 96;`**（★ 本轮已直读确认，侦察时原为反推值）。
`DiscipleTables.kt`：`:132-134` · `:213` · `:220` · `:252-253` · `:450 columnGroupByIndex` · `:486-492` · `:665-677`；**G07 新增** `markDead` 重伤语义：注释块 `:875-888`、`fun markDead(...) :883`、`HP=1 保持存活 :888`。
`DiscipleTablesWrite.kt:104-105/:184`；`DiscipleTablesColumnRegistry.kt:64/:116-118` 均未变。

**③ G07 对 §6.13 的实际影响（已在 §6.5 反映）**

- `worldLevelVictoryTx` 内 `applyDeterministicWinAttr(...)` 调用与 `soulPowers[row] + 1` **已删**，`battle_residual_tx.h:421-426` 循环体只剩 `(void)` 占位与注释。
- **死代码残留（本批须清）**：`applyDeterministicWinAttr`（`:352-397`）、`kWinAttrSkillMax`（`:90`）、`kWinAttrMaxLoyalty`（`:92`）、`WorldVictoryOutcome::soulPowerCount/winAttrCount`（`:347-348`）、`++out.winAttrCount`（`:396`）、`dispatch_w4c.cpp:143-144` 回执。
- `DiscipleTables.markDead` 已改重伤语义（`:883`，HP=1 且保持存活，死亡三元组不写），**名字保留兼容调用点**。
- G07 已同步改了 `GameEngineSpiritRootWashTest.kt` / `GameEngineTraitAddTest.kt` / `GameEngineTraitWashTest.kt` 各 6-9 行（疑似测试夹具的 markDead 调用签名）→ **本批改这三个文件的断言前先 diff G07**。

### 0.3 施工前强制复核项

1. **`battle_residual_tx.h`** —— 确认 `applyDeterministicWinAttr` 是否仍为死代码（若 G02 又动过，行号会再移）。
2. **`DiscipleTables.kt`** —— G07 改了 `markDead` 与行数；本批要删的 `:486-492` 资质自愈块与 `:665-677 healDefaultAptitudes` 位置**已复核未变**，但 `columnGroupByIndex`（`:450`）的列数依赖本批删项，需重新生成。
3. **`GameConfig.kt`** —— G07 +6 行；本档已修正 WASH 全部行号，但若 G02/G05 再动 `GameConfig.kt`，段 `:420-460`（SpiritRoot 洗炼段）与 `:487-...`（TraitWash 段）需重扫。
4. **`DiscipleTablesAssemblers.kt`** —— 已修正为 `:79-81` / `:181`。

---

## §1 三端字段链清单

### 1.1 `talentIds` / `physiqueIds` / `affixIds`（3 字段 × 三端）【已核实】

| 端 | 落点 |
|---|---|
| C++ `models.h` | `:322 talentIds` `:323 physiqueIds` `:324 affixIds`（`struct Disciple`） |
| C++ `DiscipleColumn` 枚举 | `disciple_store.h:54 TalentIds` `:55 PhysiqueIds` `:56 AffixIds` |
| C++ `disciple_store.h` 列 | `:196-198`（`vector<vector<string>>`） |
| C++ `disciple_store.cpp` | 装载 `:49-51`；写回 `:190-192`；reserve `:323-325`；clear `:491-493`；erase `:610-612`；swap `:747-749` |
| C++ `column_dirty.h` | 列名 `:99-101`；to_json `:226-228` |
| C++ `json_codec.cpp` | to_json `:245-246`；from_json `:309-310` |
| C++ `gameview_encode.cpp` | 字段号表 `:138 {"talentIds",17}` `:139 {"physiqueIds",18}` `:140 {"affixIds",19}` |
| C++ `GameCoreJni.cpp` | `:270-272`（disciple→json）；`:700/703/706` `:716-717` `:721-722` `:727-728` `:731-732` `:753-755`（对拍 op） |
| C++ `execute_dispatch.cpp` | `:956-957`（`generateTraitsForDisciple` op 读 `talentIds`） |
| proto `game_view.proto` | `:127 talentIds=17` `:128 physiqueIds=18` `:129 affixIds=19`（`repeated string`） |
| Kotlin `Disciple.kt` | `:95-97`（`@Entity` 列；`List<String>`，经 `ProtobufConverters` 存 TEXT） |
| Kotlin `DiscipleExtended.kt` | `:13 talentIds` `:41` 拷贝 |
| Kotlin `DiscipleAggregate.kt` | `:98 talentIds` `:241` |
| Kotlin `DiscipleTables.kt` | `:132-134`（三个 `ComponentTable<List<String>>`） |
| Kotlin `DiscipleTablesAssemblers.kt` | **`:79-81`**（G07 后） |
| Kotlin `DiscipleTablesWrite.kt` | `:104-105` |
| Kotlin `DiscipleTablesColumnRegistry.kt` | `:116-118`（`MutableTableRef`，深拷贝 `it.toList()`） |
| Kotlin `DiscipleSerializer.kt` | to-surrogate `:61-63`；from-surrogate `:211-213`；`@ProtoNumber(22) talentIds` `:367`；`@ProtoNumber(104) physiqueIds` `:368`；`@ProtoNumber(105) affixIds` `:369` |
| Kotlin `GameViewMirrorCodec.kt` | `:447-449` |
| Kotlin `GameViewDiscipleRows.kt` | `:203-205`（行→域）；`:425-427`（`RepeatedClearer`）；`:534-536`（域→列）；`:773-775`（域→proto） |
| Room Migration | v36 时代（`GameDatabaseMigrationsV39.kt:89,184,226,252`、`V21ToV30.kt:231,289`）已是历史 recreate 语句，**新批不动** |
| 旧格式 | `OldSerializableSaveData.kt:193 @ProtoNumber(22) talentIds`（legacy 兼容面） |

**处置分类**：
- 🔴 **Room 列（`Disciple.kt`）**：`talentIds`/`physiqueIds`/`affixIds` 三列**禁止 DROP**。按 `rules/database-migration.md` 铁律 → 保留旧列 + 加 `@Ignore`（或转为实体上的派生空值）。⚠ 但 `@Ignore` 不能加在 `DiscipleSerializer.Surrogate` 上（那是独立 data class，直接删字段即可）。
- 🔴 **ProtoBuf 存档字段**：`@ProtoNumber(22)/(104)/(105)` **只增不复用** → 删除字段时**必须**在 surrogate 里写 `reserved 22, 104, 105;`（或至少留注释锁号），防未来复用。
- 🟢 纯内存：`DiscipleTables` 三表 + registry + `columnGroupByIndex` → **可整体删**（列数改变会牵动 SoA 列序，见 §4.3）。

### 1.2 `aptitude` + `comprehension`【已核实】

| 端 | 落点 |
|---|---|
| C++ `models.h` | `:404 comprehension` `:411 aptitude`（SkillStats 平铺块内） |
| C++ `DiscipleColumn` | `disciple_store.h:131 Comprehension` `:138 Aptitude` |
| C++ `disciple_store.h` 列 | `:275 comprehensions` `:282 aptitudes` |
| C++ `disciple_store.cpp` | 装载 `:128` `:135`；写回 `:264` `:271`；reserve `:391` `:398`；clear `:559` `:566`；erase `:678` `:685`；swap `:815` `:822` |
| C++ `column_dirty.h` | 列名 `:171` `:178`；to_json `:364` `:375` |
| C++ `json_codec.cpp` | to_json `:289 comprehension` `:292 aptitude`；from_json 对应块（`:328-345` 区间） |
| C++ `gameview_encode.cpp` | `:210 {"comprehension",89}` `:217 {"aptitude",96}` |
| C++ `GameCoreJni.cpp` | `:251-252` `:720/752` `:788-789` `:810-811` `:861-863`（`aptitudeCultivationBonus` op） |
| C++ `execute_dispatch.cpp` | `:398-399`（baseStats op）；`:783`（`avoidSentinel50` 生成 op 返回） |
| proto `game_view.proto` | **`:216 optional int32 comprehension = 89;`** **`:223 optional int32 aptitude = 96;`** |
| Kotlin `DiscipleComponents.kt` | `:153 comprehension` `:161 aptitude`（`SkillStats`） |
| Kotlin `Disciple.kt` | `:198 val aptitude get() = skills.aptitude`；`DiscipleStats` `:440 comprehension` `:447 aptitude` `:464` `:471`（`plus`） |
| Kotlin `DiscipleAttributes.kt` | `:15 comprehension` `:22 aptitude` `:37` `:44`（**内存投影，无 Room 表**——v53/SR-7 起 `disciples_attributes` 表已删，见文件头 `:4-5`） |
| Kotlin `DiscipleTables.kt` | `:213 comprehensions` `:220 aptitudes` `:252-253 DEFAULT_APTITUDE=50` `:486-492 allocateAndInsert 自愈` `:665-677 healDefaultAptitudes()` |
| Kotlin `DiscipleTablesAptitude.kt` | **整文件 1-30 行**（`rollHealedAptitude` + `APTITUDE_HASH_MULTIPLIER/OFFSET`） |
| Kotlin `DiscipleTablesAssemblers.kt` | **`:181 aptitude = aptitudes.getOrDefault(id, DEFAULT_APTITUDE)`**（G07 后） |
| Kotlin `DiscipleTablesWrite.kt` | `:184 aptitudes[id] = sk.aptitude` |
| Kotlin `DiscipleTablesColumnRegistry.kt` | `:64 IntTableRef(aptitudes…)` |
| Kotlin `AssembleGroup.kt` | `:88 "aptitudes" to AssembleGroup.SKILLS` |
| Kotlin `DiscipleSerializer.kt` | `:169 comprehension` `:176 aptitude`；`:321` `:328`；`@ProtoNumber(51) comprehension` `:449`；`@ProtoNumber(110) @EncodeDefault(ALWAYS) aptitude` `:464` |
| Kotlin `GameViewMirrorCodec.kt` | `:552 comprehension` `:559 aptitude` |
| Kotlin `GameViewDiscipleRows.kt` | `:150 "comprehension"` `:157 "aptitude"`（has-映射）；`:303` `:310`；`:689` `:696`；`:871` `:878` |
| Room Migration | `GameDatabaseMigrationsV46.kt:24-29`（`MIGRATION_45_46` 给 `disciples` + `disciples_attributes` 各加 `aptitude DEFAULT 50`）；`GameDatabase.kt:178-179` 注释 |
| ⚠ 遗留 | `disciples_attributes` 表已在 v53 删除（`GameDatabaseMigrationsV53.kt:28-31`）→ v46 迁移里第二条 `ALTER TABLE disciples_attributes` 现在作用于已不存在的表（历史链上的语句，新批不动） |

**处置分类**：
- 🔴 Room 列 `disciples.aptitude`：保留旧列 + `@Ignore`（同 §1.1）。
- 🔴 ProtoBuf `@ProtoNumber(51)`（comprehension）与 `(110)`（aptitude，**带 `@EncodeDefault(ALWAYS)`**）：删字段 → 必须 `reserved 51, 110;`。注意 `110` 曾因「50 非零默认必须 ALWAYS」而特殊，删除后该 `@EncodeDefault` 特例消失。
- 🟢 内存：`aptitudes`/`comprehensions` 列、`DiscipleTablesAptitude.kt` 整文件、`healDefaultAptitudes()`、`DEFAULT_APTITUDE`、`AssembleGroup` 映射、`DiscipleAttributes.aptitude/comprehension` → 可整体删。
- ⚠ **`comprehensionAdd` 是另一套概念**：`Disciple.kt:564`（`ItemEffect`）、`models.h:163/225`（`ItemEffect`/`PillRule`）、`recipe_db_sample.json` 全表 `comprehensionAdd: 0`、`json_codec.cpp:1140/1159/1199/1216`。属于 **属性丹目标分支**（§6.6 第 3 行），随 `pill_system.h:231` 一起删或恒 0 —— 不要误当成 `SkillStats.comprehension`。

### 1.3 血炼四字段族【已核实】

| 字段 | C++ | Kotlin | proto/Room |
|---|---|---|---|
| `bloodRefinements` | `models.h:1433`；`json_codec.cpp:1325/1416` | `GameData.kt`（行号未逐行核实）【推测】 | `game_data.bloodRefinements`（Room 列） |
| `bloodRefinementBonusTotals` | `models.h:1439`；`:1331/1422` | 同上 | `game_data` 列 |
| `bloodRefinementPctTotals` | `models.h:1440`；`:1331/1422` | 同上 | `game_data` 列 |
| `activeBloodRefinements` | `models.h:1441`；`:1332/1423` | 同上 | `game_data` 列 |
| 结构体 | `models.h:1183-1194 Progress` `:1196-1205 BonusTotal` `:1207-1216 PctTotal` | `GameDataBloodRefinement.kt` 整文件 | — |
| 收口函数 | `blood_refinement.h:82-88 eraseDiscipleDerivedMaps`（4 表统一清键） | `DiscipleDerivedMapsCleanup.kt:19` | — |
| 序列化转换 | `json_codec.cpp:919-960`（4 个 to/from_json） | `ProtobufConverters` | — |

**处置分类**：Room 列（`game_data.bloodRefinements` 等）→ 保留旧列 + `@Ignore`；ProtoBuf `SerializableGameData` 对应字段号 → `reserved`。

---

## §2 公式乘区落点

### 2.1 C++（`disciple_stats.h` / `disciple.h`）【已核实，G07 后行号未变】

| 项 | 起止行 | 内容 |
|---|---|---|
| `safeBrPct` | `disciple_stats.h:91-99` | 血炼百分比防御（**与 `disciple.h:146-149` 同名重复定义**） |
| `talentEffectsFor` | `:115-123` | 天赋聚合（消费 `trait_db.h`） |
| `affixEffectsFor` | `:128-134` | 词条聚合 |
| `physiqueEffectsFor` | `:150-165` | 体质聚合 |
| `physiqueCultivationBonusFor` | `:167-170` | 体质修炼加成 |
| `mergeEffects` | `:173-179` | 天赋+词条同 key 相加 |
| `hpMpEffectsFor` | `:191-215` | 天赋/词条 maxHp/maxMp 特化 |
| `baseComprehension(d)` | `:424-436` | 悟性 + `comprehensionFlat` |
| `baseComprehension(ds,row)` | `:438-445` | 同上列版 |
| `baseIntelligence` | `:449-466` | 智力 + `intelligenceFlat` |
| `baseStats(d)` | `:471-496` | **填 `in.comprehension`(:485) / `in.aptitude`(:486) / `in.talentEffects`(:493-494)** |
| `baseStatsWithBr(d, brPct)` | `:502-536` | **填 aptitude(:518) / talentEffects(:525-526) / 6 个 blood*BonusPct(:527-534)** |
| `finalStats(d, eq, manual, prof, brPct)` | `:552-...` | 第 5 参 `brPct`，体内 `:559 baseStatsWithBr(d, brPct)` |
| `positionEffectBonus(talentIds, affixIds, slotType)` | `:641-660` + `:666` | 天赋/词条职务加成 |
| 修炼速率（`Disciple&` 版） | **`:712-768`** | 资质乘区 `:725-730`；连乘式 `:763-767` 含 `(1.0 + aptitudeBonus)` |
| 修炼速率（SoA 版） | **`:772-830`** | 资质乘区 `:785-790`；连乘式 `:825-829` |
| `comprehensionBreakthroughBonus` | `:835-841` | 悟性突破率（80 基准每 4 点 +1%，上限 +10%）→ **§11 详述** |
| 突破概率消费点 | `:865` `:867` `:876` | 内/外门长老悟性 + 自身悟性 |
| `computeLifespan(talentIds, affixIds, realm)` | `:888-895` | 天赋/词条 lifespan 键 |

| `disciple.h` | 行 | 内容 |
|---|---|---|
| `kSoulPowerDivisor` / `kSoulPowerMaxSteps` | `:33` / `:34` | 20 / 5 |
| `kMaxBloodRefinementPct` | `:49` | 10.0 |
| `safeBrPct`（重复定义） | `:146-149` | 同上 |
| `aptitudeCultivationBonus(aptitude)` | `:152-156` | `(apt-80)+ × kAptitudeBonusPerPoint`，上限 `kAptitudeMaxBonus` |
| `BaseStatsInput` 结构 | `:159-190` | `:173 comprehension` `:174 aptitude=50` `:182 talentEffects` `:184-189 blood*BonusPct` |
| `DiscipleStats` 结构 | `:193-215` | `:207 comprehension` `:208 aptitude` |
| `computeBaseStats` | `:225-279` | 6 处血炼进乘区 `:229-241`；`s.comprehension :270`；`s.aptitude = in.aptitude :271` |
| `CultivationSpeedZones` | `:284-290` | `:285 aptitudeBonus` |
| `calculateCultivationPerPhase` | `:293-304` | 连乘式 `:297-303` 含 `(1+aptitudeBonus)` |
| `BreakthroughZones` | `:309-315` | 突破乘区结构 |
| `soulPowerBreakthroughBonus` | `:318-320` | 神魂突破加成（G02 负责） |
| `getBreakthroughChance(realm,rootCount,layer)` | `:331-347` | **baseZone 唯一来源**，只吃 境界×灵根数×层数 |

### 2.2 Kotlin 对等实现【已核实】

| 文件 | 行 | 内容 |
|---|---|---|
| `DiscipleStatCalculator.kt` | `:96` | `FinalStatsInput.bloodRefinementPct` 形参 |
| | `:105-111` | **`CultivationSpeedZones`**（`:106 aptitudeBonus`） |
| | `:119-136` | `CultivationZoneInput`（`:135 aptitude = DEFAULT_COLUMN_APTITUDE`） |
| `DiscipleStatCalculator修炼Ops1.kt` | `:14-16` | **`aptitudeCultivationBonus`** |
| | `:40-52` | `battleWritebackMaxHpMp`（`:49` 传 `bloodRefinementPctTotals[id]`） |
| `DiscipleStatCalculator战斗Ops2.kt` | `:29-32` | `getMergedEffects`（天赋+词条） |
| | `:92-130` | `computeBaseStats` 等价：`:100` hpBonus 含 `safeBrPct`，其余 5 项同式；`:26/33/59` 血炼入参 |
| `DiscipleStatCalculator属性Ops3.kt` | `:26/33/59/89/105-106/153/159-160/172/177/183-184` | `getPermanentBaseStats` / `getBaseStats(disciple,br)` / `getBaseStats(aggregate,br)` 三入口全带血炼形参 |
| `DiscipleStatCalculator属性Ops4.kt` | `:29/33/75/116/121` | 装备/功法叠加入口带血炼 |
| | `:158-172` | **`calculateCultivationPerPhase(realm, rootCount, zones)`**，连乘 `:165-171` |
| | `:175-218` | **`computeCultivationZones`**，`aptitudeBonus = mergedEffects["cultivationSpeed"] + physiqueEffects.cultivationSpeedBonus + aptitudeCultivationBonus(input.aptitude)`（`:179-181`），`:211-217` 返回 |
| | `:224-...` | `buildCultivationZones` |
| | `:244 / :289` | 两个 `mergedEffects = getMergedEffects(...)` 调用点 |
| `DiscipleStatCalculator修炼Ops6.kt` | `:34` | **`randomBloodRefineStat`**（血炼属性抽签，RNG 面） |
| `DiscipleStatCalculator.kt` | `:40` | `MAX_BLOOD_REFINEMENT_PCT = 10.0` |
| `SectCombatPowerCalculator.kt` | `:77-84` | `calculateDiscipleCombatPower(…, bloodRefinementPct)`；`:84 getPermanentBaseStats(aggregate, br)` |
| | `:91-112` | `sectPowerFingerprint` 等价，`:124-130` 血炼 6 项入 hashCode |
| `GameEngineWorldBattleOps.kt` | `:198-227` | `applyWorldLevelVictoryTransaction`，`:212 soulPowers[id]+1`、`:213-216 winBattleRandomAttrPlus` 判定；`:231-...` `applyDeterministicWinAttr`（17 分支，`@Suppress("CyclomaticComplexMethod")`） |
| `PatrolBattleSystem.kt` | `:572-575` | 巡逻战胜利同款 `soulPower+1` + `winBattleRandomAttrPlus` |
| `ExplorationService.kt` | `:335-338` | 探索战胜利同款 |
| `PatrolBattleSystem.kt:573` / `ExplorationService.kt:338` | | 消费 `talentIds` 查 `winBattleRandomAttrPlus` 键 |
| `DiscipleAgePolicy.kt` | `:27` | `TalentDatabase.calculateTalentEffects(talentIds)` 寿元 |
| `PhysiqueDatabase.kt` | `:69` | 体质修炼加成（进 `aptitudeBonus` 乘区） |
| `DiscipleStatCalculator突破Ops5.kt` | `:131/:134` | `getMaxManualSlots(getMergedEffects(...))` |
| `ZoneCalculator.kt` | `:11` | 注释引用 `DiscipleStatCalculator.CultivationSpeedZones` |

### 2.3 双端对拍测试名（需同步改断言 / 重录）

| 测试 | 落点 |
|---|---|
| `DiffDiscipleTest.kt` | `:110-133`（`cultivationRate` op 带 `aptitudeBonus`）、`:186-191`（`calculateBreakthroughChance`）、`:285-295`（`aptitudeCultivationBonus` op） |
| `DiffTraitEffectsTest.kt` | 整文件（`talentEffects`/`affixEffects`/`physiqueEffects`/`mergedTraitEffects`/`baseComprehension`/`baseHpMpFromTraits`/`cultivationRateFromTraits`） |
| `DiffDiscipleFactoryTest.kt` | `:72 aptitude` `:85 talentIds` `:87 affixIds` |
| `DiffStateTest.kt` | `:298-300` |
| `DiffDirtyEnvelopeEquivalenceTest.kt` | `:56`（JSON 字面量含四字段） |
| `DiffSectDiplomacyTest.kt` | `:185/201/216` |
| `DiffMonthSettlementTest.kt` | `:875 新生儿资质` `:935 自动招募资质散列（NEW_RECRUIT_APTITUDE）` |
| `DiffPhaseSettlementTest` / `DiffYearSettlementTest` / `DiffAuthoritativeTickTest` / `DiffMonthSettlementFixture` | statsProvider fake 5 参签名（含 `bloodRefinementPct`） |
| `DiffNestedTypesTest` | 血炼三件套（`docs/cpp-engine.md:211` 登记） |

---

## §3 配置源落点

### 3.1 生成链（谁生成 → 谁消费）【已核实】

```
scripts/data/trait_db_sample.json  (59,408 B; {talents, physiques, affixes})
   │
   ├─ scripts/gen-trait-db.mjs (:291-324)  ──► android/core/engine/src/test/resources/templates/trait_db_sample.json
   │                                          （唯一产物；不生成 C++/Kotlin 源码）
   │
   └─ scripts/gen-game-data.mjs (:114-118 读 → :165-167 写 db.talents/physiques/affixes)
         │  头部 :30-31 声明 `--check`（只校验不落盘）/ `--hash`（打印 sha256）
         │  :208-236 sha256 计算 + hash 门；:242 打印
         ▼
      android/app/src/main/assets/data/game-data.json  (1,132,046 B, 单行 minified)
      android/app/src/main/assets/data/game-data.hash.txt
         = c7626a0a92fbc7444a33e05510378005ec4a1c4a37fcdeb908d0463bbf7e680c
         │
         ▼  Kotlin 启动期 nativeSetGameData(json) 单端口批量注入
      C++ data_store.h（三态 kUninitialized/kLoadedFromFile/kFallbackDefault）
         └─ data_inject.h:121-135 段映射（talents :121-125 / physiques :126-130 / affixes :131-135；
                                  段缺失或空数组 → return false → 整表落兜底）
            :151-153 注入计数回执
         ▼
      trait_db.h 内联兜底（:398 buildTalentTemplates / :527 buildPhysiqueTemplates / :689 buildAffixTemplates）
                 静态兜底实例（:715-717 / :728-730 / :741-743）
                 查询访问器（:752 talentById / :762 physiqueById / :772 affixById，走 IdIndexSnapshot）
```

| 生成物 | 生成者 | 消费者 |
|---|---|---|
| `trait_db.h`（**手写等价复刻，非生成物**） | 人（`trait_db.h:1-18` 自陈"禁止手改数值，须同步更新本表与快照"） | `disciple_stats.h` 三聚合函数、`appointment_tx.h` 候选池、`disciple_factory.h` 特质 roll |
| `trait_db_sample.json`（中性源 + 测试快照） | 人 → `gen-trait-db.mjs` 复制到 test resources | `TraitRegistryGuardTest`、`game-data.json` |
| `game-data.json` | `gen-game-data.mjs` | C++ 启动注入 |

### 3.2 其它配置文件【已核实】

| 文件 | 相关行 | 说明 |
|---|---|---|
| `scripts/data/trait_db_sample.json` | `:259` `:2139` `winBattleRandomAttrPlus: 1`；多处 `comprehensionFlat` | 整文件删 |
| `android/core/engine/src/test/resources/templates/trait_db_sample.json` | 同上（副本） | 整文件删（guard 测试读它） |
| `scripts/gen-trait-db.mjs` | `:114` `:259` 生成 `winBattleRandomAttrPlus` 条目 | 整文件删 |
| `scripts/gen-game-data.mjs` | `:114-118` `:165-167` `:185-187` | 删三表段落 |
| `android/app/src/main/assets/data/game-data.json` | `db.affixes` / `db.talents` / `db.physiques` 三个顶层键 | 删键 + 重算 hash |
| `android/app/src/main/assets/data/game-data.hash.txt` | 整文件 | 随 gen-game-data 重生成 |
| `android/app/src/main/assets/config/buildings.json` | `:262-...`（`blood_refining_pool` 定义，`displayName:"血炼池"`）；`:310-311`（别名映射） | 删条目 |
| `android/core/engine/src/main/java/com/xianxia/sect/core/config/Defaults.kt` | `:284-...` `blood_refining_pool` BuildingConfigModel；`:381-382` 别名 | 删条目 |
| `android/app/src/main/assets/config/game_config.json` | **未发现** 洗炼/特质常量 | 无需改 |
| `android/app/src/main/assets/data/manuals.json` | 无关 | — |

### 3.3 守卫测试（会变红）【已核实】

| 守卫 | 落点 | 处置 |
|---|---|---|
| `TraitRegistryGuardTest.kt` | `:31` class；`:36` 读 `trait_db_sample.json`；`:105 physiques`；`:191 for (key in listOf("talents","physiques","affixes"))` | 整文件删 |
| `StaticDataSingleSourceGuardTest.kt` | `:35` 列表含 `"trait_db"` | 移除该项 |
| `trait_db_test.cpp` | `:28 TalentCount`（`109`）`:104 PhysiqueCount`（`24`）`:146 AffixCount`（`71`）`:205 IdsUnique` `:219 LookupHelpers` | 整文件删 |
| `trait_effects_test.cpp` | 整文件（三聚合对拍） | 整文件删 |
| `data_store_test.cpp` | 疑似含 traits 段注入用例 | 改断言【推测：未逐行读】 |
| `DataStoreGuardTest` | 未定位到独立文件（可能已并入 `data_store_test.cpp`） | 【推测】 |
| `WeightedRollTest.kt` | `:21/24/37/40/53/57/71/76/81/86-108`（`rollTraitCount`/`rollTraitQuality`/`rollWashTraitQuality`） | 删特质相关用例 |
| `PhysiqueDatabaseTest.kt` / `TalentDatabaseTest.kt` / `AffixDatabaseTest.kt` | 整文件 | 删 |
| `RedeemCodeTest.kt` | `:41/64/74` `config.talentIds` | 改断言/删字段 |

---

## §4 C++ 落点（精确起止行号）

### 4.1 灵根洗炼【已核实，G07 后行号未变】

| 文件 | 起止行 | 内容 | 处置 |
|---|---|---|---|
| `appointment_tx.h` | `:77-89` | GameConfig 常量（`kWashPityThreshold:80`、`kTraitWashTopRarity:86`、`kMaxTraitsPerCategory:89`） | 洗炼部分删，特质部分随 §6.11 删 |
| | `:229-386` | 特质族（`TraitKind` / `traitKindOf` / `TraitEntry` / `traitIdsOf` / `resolveOne` / `rollCandidatesOf` / `topRarityPoolOf` / `randomOf` / `rollWashTraitQuality` / `pickPositiveWash`） | 随 §6.11 整段删 |
| | `:388-396` | `lifespanBonusOf` | 删 |
| | `:429-435` | `SpiritRootWashOutcome` | 删 |
| | `:444-...` | `TraitWashOutcome` | 删 |
| | **`:588-656`** | **`spiritRootWashTx`**（判定序 + 保底/双灵根 + 元素洗牌） | **整段删** |
| | `:658-746` | `traitAddRollTx` | 删 |
| | `:748-832` | `traitAddConfirmTx` | 删 |
| | `:834-921` | `traitWashSlotTx` | 删 |
| | **`:923-...`（`:936` 起）** | **`spiritRootWashConfirmTx`** | **整段删** |
| | `:970-1063`（`:986` 起） | `traitWashConfirmTx` | 删 |
| `disciple_stats.h` | `detail::washElementKeys` / `isValidWashedRootType` | 被 `appointment_tx.h` 引用 | 删 |
| `execute_dispatch.cpp` | **`:2194-2290`** | `handleAppointmentTx`；case `SPIRIT_ROOT_WASH_TX:2224-2233`、`SPIRIT_ROOT_WASH_CONFIRM_TX:2263-2269`、`TRAIT_*:2234/2242/2250/2270` | 删 6 个 case（保留 1610/1611/1612） |
| | `:2742/:2745` | 中央范围分支 | 若 1613-1616/1732-1733 段整体消失需改分支 |
| `GameCoreJni.cpp` | 无洗炼专用 op | — | — |

### 4.2 血炼【已核实】

| 文件 | 起止行 | 内容 |
|---|---|---|
| `models.h` | `:1183-1194` `BloodRefinementProgress`；`:1196-1205` `BloodRefinementBonusTotal`；`:1207-1216` `BloodRefinementPctTotal` | 三结构体删 |
| `models.h` | `:1433 bloodRefinements` `:1439 BonusTotals` `:1440 PctTotals` `:1441 activeBloodRefinements` | 四字段删 |
| `blood_refinement.h` | **整文件 94 行**（`:40-51` 显示名、`:55-60` `calculateElapsedMonths`、`:64-74` `addPctToTotal`、`:82-88 eraseDiscipleDerivedMaps`、`:92 BLOOD_REFINEMENT_RECORDS_CAP`）——**但 `eraseDiscipleDerivedMaps` 也清 `manualProficiencies`，删除时需保留该分支** | 部分删 |
| `json_codec.cpp` | `:919-960`（4 个 to/from_json）；`:1325/1331/1332` to_json gameData；`:1416/1422/1423` from_json | 删 |
| `month_settlement.h` | `settleSingleRefinement` 与调用（`docs/longrun-stability-audit-report.md` 记 `:340,343-344`；本轮未逐行核实）【推测】 | 删 |
| `year_settlement.h` | `:1690-1695`（死亡清血炼 map）【来自文档，未逐行核实】 | 删 |
| `disciple_stats.h` | `safeBrPct:91-99`、`hpMpEffectsFor:222-245` 血炼参、`baseStatsWithBr:502-536`、`finalStats:552-559` | 删血炼入参 |
| `disciple.h` | `:49 kMaxBloodRefinementPct`、`:146-149 safeBrPct`、`:184-189`、`:229-241` | 删 |
| `slot_cleanup.h` | 血炼槽位清理分支 | 删 |
| `GameCoreJni.cpp` | 无血炼 op | — |

### 4.3 `DiscipleColumn` 枚举删项的连带风险 🔴

`disciple_store.h:38-155` 的 `enum class DiscipleColumn` 是 **SoA 列序 → 索引**的唯一定义；`column_dirty.h:74-75 kDiscipleColumnCount` 与行主序位图按索引寻址；`DiscipleTablesColumnRegistry` / `columnGroupByIndex`（`DiscipleTables.kt:450`）/ `gameview_encode.cpp kDiscipleRowFields` 三处按**列名**映射（有守卫锁双射）。

**结论**：删枚举项会平移后续所有列的数值索引 → **必须同时**改 `column_dirty.h` 的 `discipleColumnName` 双射 switch、`gameview_encode.cpp` 字段表、Kotlin `columnGroupByIndex`、`DiscipleTablesColumnRegistry`。守卫：`column_dirty_test.cpp`（双射）+ `gameview_encode_test.cpp`（9 用例字节级）。

---

## §5 ActionId 处置

### 5.1 涉及清单【已核实】

| id | 名称 | catalog | `action_ids.h` | `ActionIds.kt` | `execute_dispatch.cpp` | 处置 |
|---|---|---|---|---|---|---|
| 1610 | `ELDER_APPOINT_TX` | `core.mjs:216` | `:382` | `:378` | `:2200` | **保留**（但需删特质授予 → §6.11 R21） |
| 1611 | `ELDER_DISMISS_TX` | `core.mjs:217` | `:385` | `:381` | `:2208` | 保留 |
| 1612 | `WAREHOUSE_GARRISON_TX` | `core.mjs:218` | `:388` | `:384` | `:2216` | 保留（G02 范围） |
| **1613** | `SPIRIT_ROOT_WASH_TX` | `core.mjs:219` | `:391` | `:387` | `:2224` | **删** |
| **1614** | `TRAIT_ADD_ROLL_TX` | `core.mjs:220` | `:394` | `:390` | `:2234` | **删** |
| **1615** | `TRAIT_ADD_CONFIRM_TX` | `core.mjs:221` | `:397` | `:393` | `:2242` | **删** |
| **1616** | `TRAIT_WASH_SLOT_TX` | `core.mjs:222` | `:400` | `:396` | `:2250` | **删** |
| **1732** | `SPIRIT_ROOT_WASH_CONFIRM_TX` | 待确认（batch-24 段） | `:511` | `:507` | `:2263` | **删** |
| **1733** | `TRAIT_WASH_CONFIRM_TX` | 待确认 | `:514` | `:510` | `:2270` | **删** |
| 1746 | `DISCIPLE_OP_START_BLOOD_REFINEMENT` | `w4a.mjs:31` | `:538` | `:534` | （handleDisciple 内） | **删** |
| 1860-1869 | `DISCIPLE_CHAT_EFFECT_TX` 段 | — | — | — | — | 不在本批 |

### 5.2 删 vs 留洞【已核实】

- 生成器 `scripts/gen-action-ids.mjs`：`:70` 只校验 **id 全局唯一**，`:90-93` 校验范围不重叠，`:160 kAllActionIds[]`，`:163 kAllActionIdsCount = ACTION_CATALOG.length`，`:182` 断言目录非空。**不校验连续性、不校验固定总数** → **删条目合法，不会破门禁**。
- **仓库既有先例**：`docs/character-gacha-redesign-2026-09-23.md` §6.9 定 `DISCIPLE_OP_RENAME=1740` 为「**ActionId 编号禁复用（置废弃注释）**」；`gen-action-ids.mjs:10` 自述「actionId 唯一、稳定（存档无关，仅进程内协议；**可自由演进**）」。
- **建议（与 §6.9 口径一致）**：
  1. **`scripts/action-catalog/core.mjs:219-222` 保留条目，`desc` 改为「（G04 已下架，编号禁复用，无 dispatch case）」**——catalog 是唯一真源，保留它可锁定 `kAllActionIds[]` 成员稳定；
  2. `execute_dispatch.cpp` **删 6 个 case**（`default` 兜底 `UNKNOWN_ACTION`）；
  3. Kotlin 入口全删。
  - 该口径下 `kAllActionIdsCount` 不变、编号不复用、无死代码。
- ⚠ **互斥提醒**：**删 catalog 条目 = 删常量**。若选「删常量」路线，则 `action_ids.h` / `ActionIds.kt` 两生成物同步移除、`kAllActionIdsCount` 减少，且**编号进入永不复用区**（需 ADR 记录）。两条路线二选一，必须写进 ADR。

---

## §6 Kotlin / UI 落点

### 6.1 灵根洗炼【已核实】

| 文件 | 行 | 处置 |
|---|---|---|
| `core/engine/.../GameEngineSpiritRootOps.kt` | **整文件 179 行**（`SpiritRootWashResult :15-22`、`SpiritRootWashConfirmResult :25-28`、`SpiritRootWashRoll :31`、`rollSpiritRootWash :41-55`、`washSpiritRoot :68-...`、`confirmSpiritRootWash :138-...`） | 整文件删 |
| `GameEngineAppointmentNativeOps.kt` | `:71 SpiritRootWashReceipt`、`:145-154` native 臂 | 删 |
| `GameEngineResidualNativeOps.kt` | `:169-...` `confirmSpiritRootWashNative` | 删 |
| `ui/game/delegate/DiscipleDelegateWashOps.kt` | `:20-27`（`washSpiritRoot` / `confirmSpiritRootWash`）；`:15-16` 注释 | 删灵根部分，保留特质部分（随 §6.11 也删） |
| `ui/game/dialogs/SpiritRootWashDialog.kt` | **整文件**（`:67` 入口、`:100-194` 内容、`:195-256` 展示、`:257-290` 按钮、`:292-322` 结果分发） | 整文件删 |
| `ui/game/DiscipleDetailScreen.kt` | `:90 import`、`:137 openSpiritRootWash()`、`:491 onWashSpiritRootClick`、`:537 SpiritRootWashDialog(`、`:107/135-136/300/515/523-524` 洗炼互斥/保底注释 | 删 |
| `ui/game/components/detail/DetailBasicInfoSection.kt` | `:150-176`（灵根 + 洗炼 `+` 号入口）、`:164-175` | 删入口，保留灵根文本 |
| `ui/game/components/detail/DetailActionButtons.kt` | `:34/37/41/48/51` 特质栏注释（洗炼样式参照） | 随 §6.11 删 |
| `GameConfig.kt` | **`:420 object SpiritRoot`**、**`:443 WASH_JADE_COST`**、**`:449 WASH_PITY_THRESHOLD`**、**`:452 WASH_DOUBLE_WEIGHT`**、**`:458 WASH_ELEMENT_KEYS`**（G07 后行号） | 删洗炼四常量（`SpiritRoot` 其余内容保留） |
| `ui/game/dialogs/WashSessionControl.kt` | 整文件（灵根与特质共用） | 洗炼全删后整文件删 |
| `engine/service/JadeSymbolService.kt` | `:291` 注释引用 `rollSpiritRootWash` | 改注释 |
| `rules/pr-review-checklist.md:57` / `docs/knowledge-base.md:736` | 以 `GameEngineSpiritRootOps.washSpiritRoot` 为**范式引用** | 必须改指新范式（**易漏**） |

### 6.2 资质 / 悟性 UI + 逻辑【已核实】

| 文件 | 行 | 处置 |
|---|---|---|
| `ui/game/AttributeFilterOption.kt` | `:22 AttributeFilterOption("aptitude","资质")` `:23 ("comprehension","悟性")`；`:39-40 getAttributeValue` | 删 2 项 + 2 分支 |
| `ui/game/components/detail/DetailCombatSection.kt` | `:36 DiscipleAttrText("资质", baseStats.aptitude)` `:37 ("悟性", baseStats.comprehension)`；`:20-21` 注释 | 删 2 行 |
| `ui/game/components/detail/DetailCultivationSection.kt` | `:302 if (detail.selfComprehensionBonus > 0) add("悟性加成" ...)` | 删（详见 §11） |
| `ui/components/DiscipleComponents.kt` | `:310 DiscipleAttrText(name="悟性", value=disciple.comprehension)`；`:118/296` 注释 | 改为其他属性（§6.1 文档建议改灵根徽章） |
| `ui/game/components/detail/DetailBasicInfoSection.kt` | `:226-241 elderBreakthroughComprehension`（长老有效悟性）、调用 `:191-196` | 详见 §11 |
| `ui/game/dialogs/AutoManagementDialog.kt` | `:132/138 attrLabel = "悟性 ≥"`（自动入住策略阈值） | 改属性或下架 |
| `ui/game/delegate/AutoAssignDelegate.kt` | `:115` 注释「悟性阈值」 | 改 |
| `core/engine/.../DiscipleStatCalculator修炼Ops1.kt` | `:14-16 aptitudeCultivationBonus` | 删 |
| `DiscipleStatCalculator属性Ops4.kt` | `:179-181`、`:135`、`:211-217` | 删资质项 |
| `DiscipleStatCalculator.kt` | `:105-111 CultivationSpeedZones`、`:119-136 CultivationZoneInput` | 删 `aptitudeBonus` 字段（**会破所有构造点**） |
| `SectCombatPowerCalculator.kt` | `:84` | 去血炼 + 悟性 |
| `DiscipleFactory.kt`（Kotlin 侧） | 资质阶梯生成 + `avoidSentinel50` | 删 |
| `app/.../DiscipleUtils.kt` | `sortedByFollowAttributeAndRealm("comprehension")` 排序键 | 改属性 |
| `ui/game/dialogs/PillStatLine.kt:34` / `ItemDetailEffects.kt:33` / `ItemDetailOtherEffects.kt:250,570` / `PillEffects.kt:75` | `"悟性 +${intelligenceAdd}"` —— **命名错位**：`intelligenceAdd` 被标为「悟性」 | ⚠ 删 `comprehensionAdd` 时**不要**动这几行（它们实际是 intelligence）；但需修正文案（预存 bug，建议单独登记） |

### 6.3 天赋 / 体质 / 词条【已核实】

| 文件 | 行 | 处置 |
|---|---|---|
| `core/domain/.../registry/TalentDatabase.kt` | `:206 winBattleRandomAttrPlus`、`:678-690` | 整文件删 |
| `core/domain/.../registry/PhysiqueDatabase.kt` | `:69/288-301` | 整文件删 |
| `core/domain/.../registry/AffixDatabase.kt` | `:251 winBattleRandomAttrPlus`、`:406-424` | 整文件删 |
| `core/domain/.../registry/TalentRegistry.kt` | `:70-74` | 整文件删 |
| `core/engine/.../GameEngineTraitWashOps.kt` | 整文件 | 删 |
| `core/engine/.../GameEngineTraitWashRoll.kt` | `:174/185 WASH_PITY_THRESHOLD` | 删 |
| `GameEngineTraitAddOps.kt` | 整文件 | 删 |
| `ui/game/delegate/DiscipleDelegateTraitAddOps.kt` | 整文件（`:13-33`） | 删 |
| `ui/game/dialogs/TraitWashDialog.kt` | 整文件（`:86-...`） | 删 |
| `ui/game/dialogs/TraitAddDialog.kt` | 整文件（`:85-...`） | 删 |
| `ui/components/DiscipleDetailDialogs.kt` | `:40 "comprehensionFlat" to "悟性"`；`:79/90/205/210/262/267` | 删特质三套弹窗 |
| `ui/game/components/detail/DetailActionButtons.kt` | `:98/100/107 天赋`、`:161/163/170 体质`、`:224/226/233 词条` | 删三栏 |
| `ui/game/DiscipleDetailScreen.kt` | `:422/475/719`（`talentIds` → `getMaxManualSlots` / `getTalentsByIds`）；`:458/515/566/777/785/840/849/1045-1051` 特质覆盖层 | 删 |
| `GameConfig.kt` | **`:487 enum class TraitWashType`**、**`:492 object TraitWash`**（**`:495 WASH_JADE_COST`**、**`:501 WASH_PITY_THRESHOLD`**、`TOP_RARITY`）、`TraitAdd` | 删（G07 后行号） |
| 任命/晋升授予特质（R21） | `appointment_tx.h:388-396 lifespanBonusOf` 用于 `traitAddConfirmTx`/`traitWashConfirmTx`；Kotlin `GameEngineAppointmentNativeOps.kt` 回执 | 删 |
| `SaveValidator` 规则 | `ManualTalentRefRule.kt:30-47`（talentIds 悬空清理）、`ItemRefConsistencyRule.kt:36-46`（talentIds 空白清理）、`SaveValidationRuleDefaults.kt:32` | 删/改 |
| `DiscipleAgePolicy.kt:27` | 天赋寿元 | 删天赋项（G02 删寿命） |
| `RecruitIntegrity.kt` | `:187-194 samePersonSignature` 含 `sorted(talentIds)` | 改（招募退役后整段消失） |

### 6.4 血炼 + 血炼池建筑【已核实】

| 文件 | 行 | 处置 |
|---|---|---|
| `core/engine/.../GameEngineBloodRefinementOps.kt` | **整文件**（`:35 CAP`、`:43 startBloodRefinementAtomic`、`:112 cancelBloodRefinement`、`:191 processBloodRefinementCompletions`、`:219 settleSingleRefinement`、`:255-258`、`:275`、`:280`） | 整文件删 |
| `ui/game/BloodRefiningViewModel.kt` | **整文件**（`:41 class`、`:117 randomBloodRefineStat`、`:129 startBloodRefinementAtomic`、`:211 SlotCategory.BLOOD_REFINEMENT`、`:223 cancelBloodRefinement`） | 整文件删 |
| `ui/game/dialogs/BloodRefiningPoolDialog.kt` | **整文件**（`:51 entry`、`:423 MaterialSelectorDialog`、`:481 MaterialSelectorDialogs`） | 整文件删 |
| `ui/game/tabs/BuildingsTab.kt` | `:66` 描述、`:99 openBloodRefiningPoolDialog` | 删分支 |
| `ui/game/BuildingSelection.kt` | `:38 "blood_refining_pool" -> DialogType.BloodRefiningPool` | 删分支 |
| `ui/game/MainGameScreen.kt` | `:153 bloodRefiningViewModel` 参数、`:424` 分支 | 删 |
| `ui/game/components/GameOverlayHost.kt` | `:26 import`、`:63 bloodRefining` | 删 |
| `app/.../ui/game/GameActivity.kt` | `:135 bloodRefiningViewModel by viewModels()` | 删 |
| `ui/navigation/GameRoute.kt` | `:27-28 BloodRefiningPool` | 删 |
| `ui/game/components/dialog/DialogProductionRoutes.kt` | `:14 import`、`:166 BloodRefiningPoolDialog(` | 删 |
| `ui/game/delegate/NavigationDelegate.kt` | `:76 openBloodRefiningPoolDialog` | 删 |
| `ui/game/building/BuildingFeatureBoot.kt` | `:144-148` | 删 |
| `ui/game/delegate/DiscipleDelegateLifecycleOps.kt` | `:3` `:33` `:43` `:50-52`（REFINING 分流） | 删分支 |
| `ui/game/SpiritMineViewModel.kt` | `:6 import` `:267` `:278-284` | 删分支 |
| ResignGate（`ui/game/`） | REFINING 分支（`CHANGELOG.md:3161` 记 17 状态穷尽） | 删枚举值 |
| `core/engine/.../SlotWinner.kt` | `:76` | 删 |
| `domain/.../model/SlotAssignment.kt` | `:36 BLOOD_REFINEMENT` | 删枚举值（`SlotCategoryCoverageTest` 会红） |
| `domain/.../model/GameEventRecord.kt` | `:60 BLOOD_REFINEMENT` | 删 |
| `domain/.../model/GameDataBloodRefinement.kt` | 整文件 | 删 |
| `engine/domain/disciple/DiscipleAssignmentGate.kt` | `:151 registerIfNotEmpty(..., BLOOD_REFINEMENT, "blood")` | 删 |
| `engine/GameEngineSelfHealOps.kt` | `:67` `:115` `:182` | 删分支 |
| `engine/service/MonthSettlementExecutor.kt` | `:4 import` `:60 processBloodRefinementCompletions()` | 删 |
| `engine/domain/disciple/DiscipleDerivedMapsCleanup.kt` | `:19` | 删该键 |
| `engine/util/BuildingNames.kt` | `:31` | 删 |
| `engine/config/Defaults.kt` | `:284-...` `:381-382` | 删 |
| `engine/domain/disciple/DiscipleStatCalculator修炼Ops6.kt` | `:34 randomBloodRefineStat` | 删 |
| `engine/domain/disciple/DiscipleStatCalculator战斗Ops2.kt` | `:99` `:116 safeBrPct` | 删 |
| 血炼入参透传（13 处） | `GameEngineBattleOps.kt:178`、`GameEngineMissionOps.kt:108`、`GameEngineScoutOps.kt:75`、`ExplorationService.kt:318`、`BattleSystem.kt:146/252`、`MissionSystem.kt:356`、`AISectAttackManager.kt:341/356`、`CultivationEventMissionOps.kt:82`、`HpMpRecoveryService.kt:131/235`、`SecretRealmService.kt:593/898`、`GameEngineWorldBattleOps.kt:126` | 删第 5 参 |
| `app/.../XianxiaApplication.kt` | `:251-262` statsProvider 实现（血炼形参） | 改签名 |
| `ui/game/AttackHpGuard.kt` | `:12-14` `:39` `:47` | 改口径 |
| `ui/game/components/MissionHallDialog.kt` | `:64` 注释 | 改 |
| `slot_cleanup` / `DiscipleSlotCleanup` | 血炼清槽分支 | 删 |
| `rules/pr-review-checklist.md:46` | 「AI 弟子不吃丹药、**无血炼**」 | 改文案 |

### 6.5 战斗随机成长【已核实，G07 后更新】

| 端 | 落点 | 状态 |
|---|---|---|
| C++ `battle_residual_tx.h` | `:90 kWinAttrSkillMax` `:92 kWinAttrMaxLoyalty` `:347-348 soulPowerCount/winAttrCount` `:352-397 applyDeterministicWinAttr` `:396 ++out.winAttrCount` `:421-426 调用点（已掏空）` | **G07 已删调用点**；函数与常量 + 回执字段成 **死代码** → 本批清干净 |
| C++ `dispatch_w4c.cpp` | `:143-144` 回执 `soulPowerCount`/`winAttrCount` | 删两行 |
| C++ `exploration_tx.h` | `:43` 文件头注释（`soulPowers/winBattleRandomAttrPlus/defeated TOCTOU`） | 改注释 |
| Kotlin `GameEngineWorldBattleOps.kt` | `:212 soulPowers+1` `:213-216 winBattleRandomAttrPlus` `:231-... applyDeterministicWinAttr` | **仍活，需删** |
| Kotlin `PatrolBattleSystem.kt` | `:572-575` | 删 |
| Kotlin `ExplorationService.kt` | `:335-338` | 删 |
| Kotlin `BattleDescriptionGenerator.kt` | `:18` 注释引用 `GameEngineWorldBattleOps.applyDeterministicWinAttr` | 改注释 |
| 文案 | `ui/components/DiscipleDetailDialogs.kt:50/145/320`（`"winBattleRandomAttrPlus" to "胜利后随机属性成长（无上限）"`） | 删（G12 死文案扫描） |

---

## §7 测试落点

（`rg -l` 实测：**C++ 22 个测试文件 + Kotlin 97 个测试文件**命中删除目标）

### 7.1 建议整文件删（20）

| 文件 | 理由 |
|---|---|
| `trait_db_test.cpp` | 三表计数 + 抽样 + id 唯一 |
| `trait_effects_test.cpp` | 三聚合函数对拍 |
| `TraitRegistryGuardTest.kt` | 快照 ↔ C++ ↔ Kotlin 三向守卫 |
| `TraitWashRollTest.kt` | 特质洗炼抽取 |
| `TraitWashDialogWashActionTest.kt` | 特质/灵根洗炼 UI 集成段（4 用例） |
| `TraitAddDialogActionTest.kt` | 新增特质 UI（4 用例） |
| `TalentDetailDialogWashTest.kt` | 天赋详情洗炼 |
| `GameEngineTraitWashTest.kt` | 特质洗炼引擎（`:329/404` 保底）——⚠ G07 已改 9 行，删前先 diff |
| `GameEngineTraitAddTest.kt` | 新增特质引擎——⚠ G07 已改 6 行 |
| `SpiritRootWashRollTest.kt` | 灵根洗炼抽取（9 用例） |
| `GameEngineSpiritRootWashTest.kt` | 灵根洗炼引擎（13 用例，`:40` class）——⚠ G07 已改 6 行 |
| `DiffTraitEffectsTest.kt` | 跨语言三聚合对拍 |
| `BloodRefinementSimpleInterestTest.kt` | 血炼单利 |
| `PhysiqueDatabaseTest.kt` / `TalentDatabaseTest.kt` / `AffixDatabaseTest.kt` | 三 Registry |
| `DiscipleTablesSelfHealTest.kt` | 资质自愈（13 用例，`:42-225` 全为 aptitude） |
| `RedeemCodeManagerTalentTest.kt` | 兑换码天赋 |
| `ManualTalentRefRuleTest.kt` | talentIds 悬空清理 |
| `RoomMigrationV46To47Test.kt` | `pending_trait_adds` 列 |
| `WeightedRollTest.kt` 的 `rollTraitCount/rollTraitQuality/rollWashTraitQuality` 用例（`:21/37/53/71/76/81`） | 抽特质分布（文件保留，删特质用例） |

### 7.2 建议改断言 / 部分删（关键行）

| 文件 | 行 / 用例 | 处置 |
|---|---|---|
| `battle_residual_tx_test.cpp` | `:181`（`ds.talentIds[winner] = {"r6_win_growth"}`）`:198-200` | 已改；`talentIds` 赋值得删 |
| `disciple_factory_test.cpp` | 资质阶梯 / 特质三段 roll 断言 | 删对应断言 |
| `disciple_test.cpp` | SkillStats / baseStats aptitude | 改 |
| `column_dirty_test.cpp` | 列名双射（含 4 个删列） | 改既有用例 |
| `gameview_encode_test.cpp` | 9 用例（含 `talentIds/physiqueIds/affixIds` 段） | 改 |
| `data_store_test.cpp` | traits 段注入 | 改 |
| `disciple_derived_maps_test.cpp` | `:20-48` 血炼三 map | 删血炼段（保留 `manualProficiencies`） |
| `disciple_lifecycle_tx_test.cpp` | `:156-160` `:273-275` 血炼三 map | 删 |
| `month_settlement_test.cpp` | `:267-269` `:310-311` 血炼结算 | 删 |
| `slot_cleanup_test.cpp` | 血炼槽位 | 删 |
| `year_settlement_test.cpp` / `child_birth_test.cpp` / `ai_sect_ops_test.cpp` / `building_residual_tx_test.cpp` / `patrol_tx_test.cpp` / `sect_diplomacy_test.cpp` / `disciple_ops_tx_test.cpp` / `manual_recruit_test.cpp` / `recipe_db_test.cpp` | 特质/血炼/资质偶发引用 | 改 |
| `DiffDiscipleTest.kt` | `:110-133` `:186-191` `:285-295` | 删 aptitude/突破 op 用例 |
| `DiffDiscipleFactoryTest.kt` | `:72/85/87` | 删断言 |
| `DiffStateTest.kt` | `:298-300` | 删 |
| `DiffDirtyEnvelopeEquivalenceTest.kt` | `:56` | 改字面量 |
| `DiffSectDiplomacyTest.kt` | `:185/201/216` | 改 |
| `DiffMonthSettlementTest.kt` | `:875 NEW`（新生儿资质）`:935 NEW_RECRUIT_APTITUDE` | 删（RNG 平移另见 §9） |
| `DiffMonthSettlementFixture.kt` / `DiffPhaseSettlementTest` / `DiffYearSettlementTest` / `DiffAuthoritativeTickTest` | statsProvider fake 5 参 | 改签名 |
| `DiscipleStatCalculatorTest.kt` | `:105-289`（资质乘区/`CultivationSpeedZones(aptitudeBonus=…)`）、`:267/276-277`（突破率） | 改 |
| `DiscipleStatCalculatorCombatBonusTest.kt` | `:155-208`（fixture aptitude）、`:601-728`、`:800-803`（plus aptitude） | 删/改 |
| `CultivationRateEquivalenceTest.kt` | 资质 120 双入口等价 + 列缺省 50 等价 | 删用例 |
| `DiscipleModelsTest.kt` | `:30-39`、`:264-272`、`:403` | 删 |
| `DiscipleSerializerProfessionTest.kt` | `:71-93`（aptitude round-trip + 旧档哨兵 50） | 删 |
| `DiscipleMergeCoverageTest.kt` | `:64`（三 id 字段）`:110 aptitude` `:188` | 改 EXPECTED_FIELDS |
| `HpMpColumnCoverageTest.kt` | EXPECTED_FIELDS | 改 |
| `HpMpRecoveryEquivalenceTest.kt` | `:45-57/109` talentIds/affixIds fixture | 改 |
| `RoomMigrationTest.kt` | `:87/141`（`talentIds, physiqueIds, affixIds` 建表） | 改 |
| `RoomMigrationV43To46Test.kt` | `:501-545`（v45→46 aptitude 列） | 删用例（**但 Migration 45_46 本体保留**） |
| `RoomMigrationSupport.kt` | `:398-399 affixIds` | 改 |
| `RoomMigrationLegacyTest.kt` | 命中 | 改 |
| `AssemblePatchEquivalenceTest.kt` | `:37/73/78`（`comprehensions` 脏列） | 改 |
| `DiscipleTablesTest.kt` | `:408-513`（physiqueIds/affixIds 往返 5 用例） | 删 |
| `DiscipleTablesMutableColumnIsolationTest.kt` | `:36/100` | 改 |
| `DiscipleTablesCowTest.kt` / `DiscipleTablesIncrementalMergeTest.kt` | G07 已改 | 复核 |
| `ArchivePayloadRoundTripTest.kt` | `:64/92/169`（talentIds/aptitude） | 改 |
| `CloudPayloadSizeBenchTest.kt` | `:221/244` | 改 |
| `AgeLifespanRuleTest.kt` | `:73-78` | 改 |
| `SaveDataDirectSerializationTest.kt` | `:95-112` | 改 |
| `GameDataTest.kt` / `GuideTaskTest.kt` / `RecruitListCleanupRuleTest.kt` | 命中 | 复核 |
| `RecruitDedupeEquivalenceTest.kt` | `:58/67/123-137/171`（同人签名含 talentIds） | 改 |
| `RecruitIntegrityTest.kt` | `:212-215/263-264` | 改 |
| `DiscipleAgeCalculatorTest.kt` | `:19-107`（talentIds/affixIds 寿元） | G02 范围，本批复核 |
| `StaticDataSingleSourceGuardTest.kt` | `:35` | 改列表 |
| `MirrorProtoFeedFixture.kt` | `:82-83/149` | 改 |
| `TypedEnvelopeSizeBenchTest.kt` | 命中 | 复核 |
| `GameViewDiscipleColumnApplyEquivalenceTest.kt` | `:82 aptitude` | 改 |
| `GameDataFieldPatchGuardTest.kt` | 命中 | 复核 |
| `CheckpointCallSiteGuardTest.kt` | `:40`（洗炼确认必须 checkpoint） | 删该入口 |
| `GameEngineAppointmentNativeTxGateTest.kt` | `:49/273-296`（洗炼臂） | 删用例 |
| `GameEngineDualSlotGuardTest.kt` | `:309-382`（血炼 4 用例 + `:372 processBloodRefinementCompletions`） | 删 |
| `GameEngineDiscipleOpsNativeTxGateTest.kt` | `:262`（血炼启动） | 删 |
| `SlotCategoryCoverageTest.kt` | `:48/91 BLOOD_REFINEMENT` | 删枚举值 + 改守卫 |
| `BuildingFeatureTestRegistration.kt` | `:97 blood_refining_pool` | 删 |
| `BuildingRemovalSlotCleanupTest.kt` | `:77` | 删 |
| `BuildingBatchRemovalTest.kt` | `:302` | 删 |
| `DiscipleSlotCleanupTest.kt` | 血炼分支 | 删 |
| `HerbGardenAuraServiceTest.kt` | `:50/63`（`talentIds = listOf("r2_base_plant")`） | 改 |
| `AISectDiscipleManagerTest.kt` | 血炼/特质断言 | 改 |
| `FormulaServicePureLogicTest.kt` | 长老悟性 3 用例 | 详见 §11 |
| `DiscipleBreakthroughHandlerTest.kt` / `DiscipleLifecycleProcessorTest.kt` | 命中 | 改 |
| `CultivationCore*Test.kt`（4 个） | 命中 | 改 |
| `SectCombatPowerCalculatorTest.kt` | 血炼战力 | 改 |
| `BattleSystemTest.kt` | statsProvider fake 5 参 | 改 |
| `TransactionRngRollbackTest.kt` / `GameStateStoreRollbackTest.kt` / `DerivedAggregationTest.kt` | statsProvider fake 5 参 | 改 |
| `DiscipleFactoryTest.kt` | 资质阶梯镜像 + 避开哨兵 50 | 删 |
| `SecretRealm*Test.kt`（8 个） | 血炼入参 | 改 |
| `ExplorationPatrolRouteTest.kt` / `ProductionProcessorHarvestTest.kt` / `ProductionSlotDualWriteGuardTest.kt` / `DisciplePillManagerAutoUseEnhancementTest.kt` / `CultivationCoreRealtimeAutoPillsTest.kt` | 命中 | 复核 |
| `DiscipleFilterUtilsTest.kt` | `:26/54/256-322 comprehension` 筛选用例 | 删筛选键用例 |
| `DiscipleUtilsTest.kt` | `:9/31-243 comprehension` 排序 | 删 |
| `AttackHpGuardTest.kt` | `:4/38-84` 血炼口径 4 用例 | 改 |
| `JadePurchaseFlowTest.kt` | `:20` 注释引用 TraitWash | 改注释 |
| `BattleResidualNativeTxGateTest.kt` | G07 已改 47 行 | 复核 |
| `RngSourceGuardTest.kt` | `:126 registeredPartitionIds = setOf(0..11)` | **不删**（分区不删，见 §9） |

---

## §8 grep 清零清单

以下为**删完后必须零命中的正则**（建议做成 CI 守卫脚本）。标注当前预期命中归属。

| # | ripgrep 正则 | 当前命中归属（量级） |
|---|---|---|
| 1 | `SPIRIT_ROOT_WASH` | 生产 C++ 2 + Kotlin 2 + 测试 40+ + 文档 6 + changelog 6 |
| 2 | `washSpiritRoot\|WashSpiritRoot\|SpiritRootWash` | 生产 Kotlin 8 + C++ 4 + 测试 60+ + 文档/changelog 10+ |
| 3 | `rollSpiritRootWash\|WASH_PITY_THRESHOLD\|WASH_DOUBLE_WEIGHT\|WASH_ELEMENT_KEYS\|WASH_JADE_COST` | `GameConfig.kt:443/449/452/458/495/501` + `appointment_tx.h:80` + 测试 20+ |
| 4 | `WashSessionControl` | Kotlin 3 |
| 5 | `TRAIT_ADD_ROLL_TX\|TRAIT_ADD_CONFIRM_TX\|TRAIT_WASH_SLOT_TX` | catalog 3 + action_ids.h 3 + ActionIds.kt 3 + dispatch 3 + 测试 10+ |
| 6 | `traitAddRollTx\|traitAddConfirmTx\|traitWashSlotTx\|traitWashConfirmTx\|spiritRootWashTx\|spiritRootWashConfirmTx` | `appointment_tx.h` + `execute_dispatch.cpp` + 测试 30+ |
| 7 | `traitIdsOf\|traitKindOf\|rollCandidatesOf\|topRarityPoolOf\|deprecatedTalentTypes\|lifespanBonusOf\|resolveOne` | `appointment_tx.h` + 测试 |
| 8 | `TraitWashType\|TraitWash\|TraitAdd` | `GameConfig.kt:487-...` + 6 Kotlin 文件 + 测试 |
| 9 | `talentIds` | 生产 C++ 20+ / Kotlin 40+ / 测试 150+ / 文档 30+ |
| 10 | `physiqueIds\|physiqueById\|physiqueTemplates\|PhysiqueTemplate\|PhysiqueDatabase\|PhysiqueEffects` | 生产 C++ 15+ / Kotlin 20+ / 测试 20+ |
| 11 | `affixIds\|affixById\|affixTemplates\|AffixTemplate\|AffixDatabase` | 同上 |
| 12 | `TalentDatabase\|TalentRegistry\|talentById\|talentTemplates\|TalentTemplate` | 同上 |
| 13 | `trait_db` | `data_inject.h` / `data_store` / `trait_db.h` / `StaticDataSingleSourceGuardTest:35` / `CMakeLists.txt` / 文档 20+ |
| 14 | `mergeEffects\|talentEffectsFor\|affixEffectsFor\|physiqueCultivationBonusFor\|hpMpEffectsFor\|positionEffectBonus\|baseComprehension\|comprehensionBreakthroughBonus` | `disciple_stats.h` + `GameCoreJni.cpp` + Kotlin 等价 + 测试 |
| 15 | `comprehensionFlat\|cultivationSpeedBonus.*physique` | `trait_db_sample.json` 多处、`game-data.json` |
| 16 | `aptitude` | **生产 C++ 30+ / Kotlin 60+ / 测试 200+ / 文档 60+** |
| 17 | `aptitudeCultivationBonus\|aptitudeBonus\|DEFAULT_APTITUDE\|rollHealedAptitude\|healDefaultAptitudes\|avoidSentinel50\|APTITUDE_BASELINE\|APTITUDE_BONUS_PER_POINT\|APTITUDE_MAX_BONUS\|DEFAULT_COLUMN_APTITUDE` | `disciple.h` / `disciple_stats.h` / `disciple_factory.h` / `recruit_settlement.h` + 6 Kotlin + 测试 60+ |
| 18 | `comprehension`（排除 `comprehensionAdd` 前先删该键） | 生产 C++ 25+ / Kotlin 50+ / 测试 120+ |
| 19 | `comprehensionAdd`（**独立**：属性丹目标分支） | `models.h:163/225`、`Disciple.kt:564`、`json_codec.cpp:1140/1159/1199/1216`、`recipe_db_sample.json` 700+ 行 |
| 20 | `bloodRefine\|BloodRefine` | 生产 C++ 40+ / Kotlin 50+ / 测试 60+ / 文档 200+ |
| 21 | `blood_refining_pool\|血炼池` | `buildings.json:262/310/311`、`Defaults.kt:284/381/382`、`BuildingFeatureBoot.kt:144-148`、`BuildingNames.kt:31`、`BuildingsTab.kt:66`、asset 名（保留） |
| 22 | `activeBloodRefinements\|bloodRefinements\|bloodRefinementBonusTotals\|bloodRefinementPctTotals` | C++ `models.h` / `json_codec` + Kotlin `GameData` + 13 透传点 + 测试 |
| 23 | `winBattleRandomAttrPlus` | `gen-trait-db.mjs:114/259`、`trait_db_sample.json:259/2139`、`game-data.json`、`trait_db.h:281/636`、`AffixDatabase.kt:251`、`TalentDatabase.kt:206`、`GameEngineWorldBattleOps.kt:214`、`PatrolBattleSystem.kt:575`、`ExplorationService.kt:338`、`DiscipleDetailDialogs.kt:50/145/320` |
| 24 | `applyDeterministicWinAttr` | `battle_residual_tx.h:352/358` + 测试 + Kotlin `:231` + 文档 2 |
| 25 | `kWinAttrSkillMax\|kWinAttrMaxLoyalty\|winAttrCount\|soulPowerCount` | `battle_residual_tx.h:90/92/347/348/396` + `dispatch_w4c.cpp:143-144` + 测试 |

**允许保留的命中（白名单）**：
- `android/scripts/build-atlas.mjs:125/368` / `android/scripts/lib/atlas-offline-rgba-lib.mjs:202` 的 `blood_refining_pool` 素材名（§6.12「素材暂闲置」）
- `android/app/src/main/res/drawable*/blood_refining_pool.webp` + atlas manifest
- `CHANGELOG.md` / `android/app/src/main/assets/changelog_entries.json` 历史条目（不改历史）
- `docs/` 历史批次档案（`docs/parallel-batches*`、`docs/character-*-2026-*`、`docs/longrun-*`）
- `scripts/split_engine/specs/chunk_DiscipleStatCalculator.json:103 randomBloodRefineStat`（历史 spec）
- Room 旧列名（`aptitude` / `talentIds` / `physiqueIds` / `affixIds` / `bloodRefinements` 等，加 `@Ignore` 后仍出现在 `@Entity` 里）

⚠ **grep 清零不可能 100%** → 守卫需按「生产 Kotlin/C++ 源码（排除 `@Ignore` 行）零命中」定义。

---

## §9 RNG 影响

### 9.1 分区与抽取点【已核实】

| 分区 | Kotlin `RngPartition.kt` | C++ `rng_manager.h` | 种子 |
|---|---|---|---|
| SYSTEM | `:29 SYSTEM(3)` | `:53 kSystem = 3` | `seed+3` |
| MAIL | `:33 MAIL(5)` | `:55 kMail = 5` | `seed+5` |
| AI_SECT | `:35 AI_SECT(6)` | `:56 kAiSect = 6` | `seed+6` |
| 上限常量 | — | `:69 kMaxPartitionId = kResidual(11)` | — |
| 守卫 | `RngSourceGuardTest.kt:126 registeredPartitionIds = setOf(0..11)` | — | — |

| 抽取点 | 分区 | 抽取次数 | 删除影响 |
|---|---|---|---|
| `spiritRootWashTx`（`appointment_tx.h:595-656`） | **SYSTEM** | 保底路径 **5×nextInt**；普通路径 **1×nextDouble + 5×nextInt**；失败臂 0 | 🔴 **玩家时序驱动、非结算内** → 删它**不平移月结/年结序列**；但同一存档"洗炼过多"与"不洗炼"两档之间产生历史分叉（对拍夹具若含洗炼步骤需重录） |
| `traitAddRollTx`（`:664-746`） | SYSTEM | 1×nextDouble + 1×nextInt（预检失败 0） | 同上 |
| `traitWashSlotTx`（`:841-921`） | SYSTEM | 保底 1×nextInt / 普通 1×nextDouble+1×nextInt | 同上 |
| `rollSpiritRootWash`（Kotlin `GameEngineSpiritRootOps.kt:41-55`） | SYSTEM | 同 C++ 镜像 | 同上 |
| **`createDisciple`（`disciple_factory.h:411-479`）** | **调用方分区** | 步 1：7×gaussianInt（=14 nextInt）；**步 2：2×nextInt（悟性+资质）**；**步 3：天赋/体质/词条三段 roll（各 0-5 条，次数不定）**；步 4：1×nextInt 肖像；步 5：9×gaussianInt（=18 nextInt） | 🔴 **RNG 序列大幅平移**！删步 2 与步 3 会让每次创建少 **2 + 可变** 次抽取 → **所有创建/招募/AI 宗弟子生成的后续序列全变**。⚠ §6.6/§6.11 未登记，**必须补登记** |
| `allocateAndInsert`（`recruit_settlement.h:388-403`） | 零 RNG | 0 | 无 |
| `rollHealedAptitude`（`recruit_settlement.h:394-400` 调用；Kotlin `DiscipleTablesAptitude.kt:14-30`） | **零 RNG**（id 散列 `(id*527+31) % span`） | 0 | 无 |
| `winBattleRandomAttrPlus` / `applyDeterministicWinAttr` | **零 RNG**（id 散列 `(id*527+31) % 17`） | 0 | **零** —— 但分支 9 会触发 `judgeSingleTheftCandidate`（**SYSTEM 分区，1-2×nextDouble/nextInt**）。G07 已删调用点 → 该抽取**已消失**；§6.13 剩余部分不再影响 RNG。⚠ 该平移**至今未登记**，须补 |
| 血炼 `randomBloodRefineStat`（`DiscipleStatCalculator修炼Ops6.kt:34`，`BloodRefiningViewModel.kt:117` 传 `rngManager`） | **待确认分区**【推测：SYSTEM 或表现随机】 | 待确认 | 玩家时序驱动 |
| 修炼速率 / `finalStats` / 战力 | 无 RNG（纯数学） | 0 | 删 aptitude/特质/血炼乘区**只改数值不改序列** |

### 9.2 对拍基线需重录【已核实】

| 测试/夹具 | 行 |
|---|---|
| `DiffMonthSettlementTest.kt` | `:875`（`child.skills.aptitude == 80` 黄金值）`:935`（`NEW_RECRUIT_APTITUDE`） |
| `DiffMonthSettlementFixture.kt` | `:239-291`（statsProvider fake） |
| `DiffPhaseSettlementTest.kt` | `:226-278` |
| `DiffYearSettlementTest.kt` | `:235-287` |
| `DiffAuthoritativeTickTest.kt` | `:305-353` |
| `DiffDiscipleFactoryTest.kt` | 全量（工厂消费序黄金） |
| `DiffNestedTypesTest.kt` | 血炼三件套 |
| `gameview_encode_test.cpp` | 字节级确定性用例（列删项后字段表变） |
| `DiffDiscipleTest.kt` | `:186-191`（突破率对拍）—— 见 §11 |

### 9.3 shuffle 实现细节【已核实】

- C++：`appointment_tx.h:638-646` —— **逐元素 1×`nextInt()` 后 `std::stable_sort`**（非 Fisher-Yates）。
- Kotlin：`RngExt.shuffled`（`GameEngineSpiritRootOps.kt:41-55` 内调用），同源语义。
- 契约来源：`docs/cpp-migration-handover-m0.md:304`「`shuffled` = 逐元素 1×nextInt() 后稳定排序（非 Fisher-Yates）」。删除时若保留任何 shuffle 语义必须保持此实现。

---

## §10 与 G02 的交叉点（必须在清单里标注）

| 交叉点 | 共享文件:行 | 归属 |
|---|---|---|
| 战斗随机成长 17 分支 | `battle_residual_tx.h:352-397` | **G07 已删调用点与神魂 +1**；G04 只需删死函数 + 常量 + 回执字段 |
| 同一函数内的偷盗钩子 | `battle_residual_tx.h:376-386`（`judgeSingleTheftCandidate`） | G02 §6.3 要删月度偷盗 + 战斗后道德偷盗钩子 → **与 G04 整段删除重叠** |
| 同一文件的丧亲哀悼 | `battle_residual_tx.h:131-152` | 纯 G02 |
| 忠诚词条 | `trait_db.h` 中 `loyaltyFlat` 条目 + `disciple.h:269`（`s.loyalty = in.loyalty + effectValue(...,"loyaltyFlat")`） | G02 删忠诚 / G04 删整表 —— **同表同键** |
| 寿元词条 | `trait_db.h` `lifespan` 键 + `disciple_factory.h:477 computeLifespan(d.talentIds, d.affixIds, d.realm)` + `disciple_stats.h:888-895` + `DiscipleAgePolicy.kt:27` | G02 删寿命 / G04 删整表 |
| `appointment_tx.h` 的 lifespan 同步 | `:803-810`（`traitAddConfirmTx`）、`:1043-1050`（`traitWashConfirmTx`） | G02 删寿命 / G04 删特质 —— 同一函数体内 |
| `SkillStats` 块 | `models.h:400-417` | G02 删 `loyalty(403)` / G04 删 `comprehension(404)` `aptitude(411)` —— **同一平铺块，`DiscipleColumn` 枚举相邻** |
| `skillStats` 列自愈 | `DiscipleTables.kt:486-492` | G04 删 aptitude 自愈（该块同时含其他技能） |
| `eraseDiscipleDerivedMaps` | `blood_refinement.h:82-88` | G04 删 3 个血炼键 / 保留 `manualProficiencies`（P3-4 清理） |
| `DiscipleTables` COW 注册表 | `DiscipleTablesColumnRegistry.kt:114-124` | G02/G04 同时删 List 表项 → 同一数组 |
| `column_dirty.h` / `kDiscipleColumnCount` | `column_dirty.h:74-75, 82-...` | 两批同时删枚举项 → **必须一个 commit 内改完双射守卫** |
| 死亡清键 | `year_settlement.h:1690-1695` | G02 删寿命死亡链 / G04 删血炼 map |
| `SlotCategory.BLOOD_REFINEMENT` | `SlotAssignment.kt:36` + `SlotCategoryCoverageTest.kt:48/91` | 纯 G04 |
| 突破率神魂项 | `disciple_stats.h:869-870 soulPowerBonus` + `:874`、Kotlin `属性Ops4.kt:431/438` | G02 删神魂 / G04 见 §11 |

**建议**：`battle_residual_tx.h` 与 `column_dirty.h` / `disciple_store.h` 枚举属**同一物理段**，G02/G04 必须**串行合入**（禁止并行编辑），否则必冲突。

---

## §11 产品缺口：悟性删除后的突破率公式

### 11.1 现状公式（确切落点）【已核实】

**公式形态**（双端同构）：

```
chance = clamp( baseZone × (1 + elderGuidance + selfBonus) × max(1 − statusPenalty, 0) + adFlatBonus , 0 , 1 )

baseZone      = f(realm, rootCount, realmLayer)                      ← 唯一不含悟性的项
elderGuidance = compBonus(innerElderComp) × (1 + innerElderPos)      ← 内门长老
              + compBonus(outerElderComp) × (1 + outerElderPos)      ← 外门长老
selfBonus     = pillBonus + soulPowerBonus + masterDiscipleBonus
              + compBonus(selfComp)                                  ← 弟子自身悟性
statusPenalty = griefPenalty + lifespanPenalty
adFlatBonus   = 广告扁平加成
compBonus(x)  = min((x − 80) / 4, 10) × 0.01     （x < 80 → 0）
```

**C++ 落点**：

| 项 | 文件:行 |
|---|---|
| `compBonus` 本体 | `disciple_stats.h:835-841` |
| 公式常数 | `disciple_stats.h:54 kElderSkillBaseline=80` · `:55 kElderBonusDivisor=4` · `:56 kElderBreakthroughMaxSteps=10` · `:57 kElderBonusPerStep=0.01` |
| `BreakthroughChanceInput` 结构 | `disciple_stats.h:844-853`（`:845-846` 内/外门长老悟性；`:851-852` inner/outer `PositionBonus`） |
| 主函数 `calculateBreakthroughChance` | `disciple_stats.h:856-883` |
| 消费点 ①内门长老 | `disciple_stats.h:865-866` |
| 消费点 ②外门长老 | `disciple_stats.h:867-868` |
| `elderGuidance` 组装 | `disciple_stats.h:873` |
| 消费点 ③自身悟性 | `disciple_stats.h:876`（`comprehensionBreakthroughBonus(baseComprehension(d))`） |
| `selfBonus` 组装 | `disciple_stats.h:874-876` |
| `statusPenalty` 组装 | `disciple_stats.h:871-872` / `:878-879` |
| `baseZone` 唯一来源 | `disciple.h:331-347 getBreakthroughChance(realm, rootCount, realmLayer)`（**只吃境界 × 灵根数 × 层数**） |
| 神魂项 | `disciple.h:318-320 soulPowerBreakthroughBonus`、`:33/:34` 常数；消费 `disciple_stats.h:869-870` |
| `BreakthroughZones` 结构 | `disciple.h:309-315` |

**Kotlin 落点**：

| 项 | 文件:行 |
|---|---|
| `compBonus` 本体 | `DiscipleStatCalculator突破Ops5.kt:24-34` |
| 公式常数 | `GameConfig.kt:906 ELDER_SKILL_BASELINE=80`（⚠ 注释自陈「**同时被教学公式引用，勿改名**」）· `:908 ELDER_BONUS_DIVISOR=4` · `:910 ELDER_BREAKTHROUGH_MAX_STEPS=10`；`DiscipleStatCalculator.kt:15 ELDER_BONUS_PER_STEP=0.01` |
| 主函数 `calculateBreakthroughChance(zones)` | `DiscipleStatCalculator突破Ops5.kt:11-17` |
| 乘区结构 `BreakthroughZones` | `DiscipleStatCalculator.kt:166-172` |
| 乘区输入 `BreakthroughZoneBonusInput` | `DiscipleStatCalculator.kt:175-185`（`:176-177` 内/外门悟性；`:178 selfComprehension`；`:183-184` inner/outer PositionBonus） |
| **乘区组装 `computeBreakthroughZones`** | `DiscipleStatCalculator属性Ops4.kt:416-443`；`:425 baseZone`；`:427-430` 内/外门长老（`compBonus × (1+pos)`）；`:431 soulPower`；`:432 lifespanPenalty`；`:435-442` 组装返回 |
| `buildBreakthroughZones(Disciple)` | `DiscipleStatCalculator属性Ops4.kt:449-477`（`:469 selfComprehension = disciple.getBaseStats().comprehension`） |
| `buildBreakthroughZones(DiscipleAggregate)` | `DiscipleStatCalculator属性Ops4.kt:483-509`（`:503` 同） |
| 便捷入口 `getBreakthroughChance(Disciple/Aggregate)` | `DiscipleStatCalculator突破Ops5.kt:40-58` / `:64-82` |
| 明细 `getBreakthroughBonusDetail` | `DiscipleStatCalculator突破Ops5.kt:88-121`（`:108/109` 长老；`:116` 自身悟性） |
| 明细数据类 `BreakthroughBonusDetail` | `DiscipleStatCalculator.kt:187-202`（`:189-190` inner/outerElderBonus；`:192` talentBonus（已废弃，仅显示）；`:193` soulPowerBonus；`:198 selfComprehensionBonus） |

**长老有效悟性提取（UI 侧口径）**：

| 项 | 文件:行 |
|---|---|
| `elderBreakthroughComprehension(...)` | `ui/game/components/detail/DetailBasicInfoSection.kt:226-241`；判据 `:234`（弟子身份匹配）+ `:235`（长老存活且 `disciple.realm >= elder.realm`）；取值 `:237 elder.getBaseStats().comprehension`（注释自陈「含天赋 Flat，如顿悟+18，与突破结算口径一致」） |
| 调用点（内/外门） | `DetailBasicInfoSection.kt:191-196` |
| 传入 `getBreakthroughBonusDetail` | `DetailBasicInfoSection.kt:189-200`（`:198 masterDiscipleBonus`） |
| 明细弹窗展示 | `ui/game/components/detail/DetailCultivationSection.kt:294-304`（`:294 基础突破率`、`:295 内门长老加成`、`:296 外门长老加成`、`:297 天赋加成`、`:298 神魂加成`、`:301 师徒加成`、**`:302 悟性加成`**、`:303 丧亲减益`、`:304 寿元将尽`）；`:373-377` 正/负项汇总 |

**结算与其它调用点**：

| 项 | 文件:行 |
|---|---|
| 弟子突破结算 | `core/engine/service/DiscipleBreakthroughHandler.kt:348` |
| AI 宗弟子演化 | `core/engine/domain/diplomacy/AISectDiscipleManagerMisc.kt:180` |
| 契约来源 | `CHANGELOG.md:3175`「悟性重设计（**只管突破率**）……`comprehensionBreakthroughBonus(c)` 以 80 为基准，每高 4 点 +1%，最多 +10%（整数除法步进，与长老公式**共用同一函数**）；`ELDER_BONUS_DIVISOR` 5→4、`ELDER_BREAKTHROUGH_MAX_STEPS` 5→10」 |

### 11.2 删除后剩余项能否构成完整公式

把命题拆成「用户列举的 7 个来源」在**现状**是否已在突破率乘区内：

| 来源 | 现状是否进突破率 | 载体 | 删除后 |
|---|---|---|---|
| **境界** | ✅ **是** | `baseZone = getBreakthroughChance(realm, rootCount, realmLayer)` | 保留 |
| **灵根** | ✅ **是** | 同上（`rootCount` 决定 `BREAKTHROUGH_CHANCES[realm][rootCount]`） | 保留 |
| **星级** | ❌ **否** | — | 需**新增**乘区才能进来 |
| **资源**（建筑/灵田/功法） | ❌ **否** | 突破率无建筑/功法形参 | 同上 |
| **状态** | ⚠ 部分 | 仅 `griefPenalty`（G02 删）+ `lifespanPenalty`（G02 删） | **归零** |
| **社交** | ⚠ 仅师徒 | `masterDiscipleBonus`（`MASTER_DISCIPLE_BREAKTHROUGH_BONUS_PER_GAP = 0.03`，`DiscipleStatCalculator.kt:223`） | 保留；讲道/父母/传道**从不进突破率** |
| **装备** | ❌ **否** | — | 需**新增**乘区才能进来 |

**结论**：

1. **可以构成一个"完整但贫瘠"的公式** —— 删完 G02+G04 后剩余项为
   `chance = clamp( baseZone(realm, rootCount, layer) × (1 + pillBonus + masterDiscipleBonus) × 1 + adFlatBonus, 0, 1 )`
   （`statusPenalty` 三项全归零 → 乘区恒 1；`soulPowerBonus` 随 G02 归零）。这在**数学上闭合**，无需补任何新项。
2. **但三个来源直接消失且无替代**：
   - **长老指导 = 0**（唯一输入是悟性）→ **任命内/外门长老对突破率零贡献**，与 §6.11「任命只有槽位职责，无特质写入」叠加后，长老在突破面上彻底失去存在价值；
   - **自身悟性 = 0** → 玩家失去唯一可感知的"这个弟子资质好不好"的突破率体现（修炼速度侧本来也随 §6.6 删除）；
   - **状态惩罚 = 0** → 丧亲/寿元将尽不再影响突破（G02 范围）。
3. **星级 / 资源 / 装备 现状根本不参与突破率** → 若口径要"把权重转移过去"，属**新增乘区**而非保留，会引入新协议依赖（见口径②）。
4. 🔴 **本缺口在产品方案里无定义**：§6.6 只写了「修炼速度新秩序」，**从未提及突破率公式在悟性删除后如何取值**；`docs/design/gacha-batches/m0-economic-whitepaper.md` §4 也只重校准了修炼速度。**须产品拍板后再施工**。

### 11.3 三个可选口径

#### 口径① 随悟性一并删除（突破率只由境界 + 突破失败累积决定）

**语义**：`elderGuidance ≡ 0`、`selfComprehensionBonus ≡ 0` 项删除；公式收束为 `baseZone × (1 + pillBonus + masterDiscipleBonus) × (1 − statusPenalty) + adBonus`。长老槽位无突破贡献。

| 落点 | 改动 |
|---|---|
| `disciple_stats.h:835-841` | 删 `comprehensionBreakthroughBonus` 本体 |
| `disciple_stats.h:54/:55/:56/:57` | 删 4 个突破用途常数（⚠ **`:54 ELDER_SKILL_BASELINE` 若仍被教学公式引用则须改名保留**，见 `GameConfig.kt:905` 同源注释） |
| `disciple_stats.h:845-846` | 删 `innerElderComprehension` / `outerElderComprehension` 字段 |
| `disciple_stats.h:851-852` | 删 `innerElderPositionBonus` / `outerElderPositionBonus` 字段 |
| `disciple_stats.h:865-868` | 删 `innerBonus` / `outerBonus` 两式 |
| `disciple_stats.h:873` | `elderGuidance` 恒 0 → 删该变量并简化 `:877` 为 `1.0 + selfBonus` |
| `disciple_stats.h:876` | 删 `selfBonus` 内 `comprehensionBreakthroughBonus(baseComprehension(d))` 项 |
| `disciple_stats.h:424-445` | `baseComprehension` 双载（若无其他消费者）可删 |
| `突破Ops5.kt:24-34` | 删 `comprehensionBreakthroughBonus` |
| `突破Ops5.kt:108/109/116` | 明细三行删 |
| `属性Ops4.kt:427-430` | 删两式 |
| `属性Ops4.kt:437` | `elderGuidance = 0.0` |
| `属性Ops4.kt:439` | 删自身悟性项 |
| `属性Ops4.kt:469/:503` | 删 `selfComprehension = ...` 传参 |
| `DiscipleStatCalculator.kt:176-177/:178/:183-184/:189-190/:198` | 删结构字段 |
| `DiscipleStatCalculator.kt:15 ELDER_BONUS_PER_STEP` | 若无消费者则删 |
| `GameConfig.kt:906/:908/:910` | `ELDER_SKILL_BASELINE` **保留改名**（教学公式），`ELDER_BONUS_DIVISOR`/`ELDER_BREAKTHROUGH_MAX_STEPS` 删 |
| `DetailBasicInfoSection.kt:226-241` | 删 `elderBreakthroughComprehension`；`:191-196` 调用点删 → `getBreakthroughBonusDetail` 形参收窄 2 参 |
| `DetailCultivationSection.kt:295/296/302` | 删 3 个展示行；`:373-374` 汇总去项 |
| `AdvanceDetail` / `getBreakthroughChance` 形参 | 两处入口（`突破Ops5.kt:40/64`）与 `DiscipleBreakthroughHandler.kt:348`、`AISectDiscipleManagerMisc.kt:180` 调用点收窄 |
| `GameConfig.kt:906` 附近 | 长老槽位配置是否还有存在意义需产品确认 |

**对拍影响**：
- `DiffDiscipleTest.kt:186-191`（`calculateBreakthroughChance` 对拍）—— 数值面必须重算；
- `DiscipleStatCalculatorTest.kt:267/276-277`、`FormulaServicePureLogicTest.kt`（长老 3 用例）、`DiscipleBreakthroughHandlerTest.kt` 全部改断言；
- `Diff{Phase,Year}Authoritative}*` 系列 statsProvider fake 的 `getBreakthroughChance` **形参面收窄** → 10+ 文件同步改；
- 🔴 **隐蔽的 RNG 影响**：突破率数值整体下降（原自身悟性 +0~10%、长老指导 +0~20% 消失）→ **成功/失败次数分布变化** → 每次突破判定消费 1 次 `BREAKTHROUGH` 分区抽取，但**成功与失败走不同的后续代码路径**（`performBreakthrough` 成功分支推进境界 → 触发后续装备/功法刷新等分支）→ **下游消费次数不同**。这不是"序列平移"而是"**条件分支导致的消费次数差**"，**月结/年结/旬结对拍基线必重录**。

#### 口径② 把原悟性乘区的权重转移到星级 / 境界

**语义**：`elderGuidance + selfComprehensionBonus` 的期望权重（现状典型 +0.10~+0.30）改由 **星级** 或 **境界** 提供。

| 落点 | 改动 | 依赖 |
|---|---|---|
| `BreakthroughZoneBonusInput`（`DiscipleStatCalculator.kt:175-185`） | 新增 `starBonus: Double`（或 `realmTierBonus`）形参 | — |
| `属性Ops4.kt:435-442` | 组装时并入 `elderGuidance` 或新增独立乘区 | 需 `BreakthroughZones`（`:166-172`）同步加字段 |
| `属性Ops4.kt:449-477 / :483-509` | 两个 `buildBreakthroughZones` 需能取到星级 | 🔴 **星级真相不在弟子行上**——§8.4 明确「星级不落在弟子行上，弟子实例由星级派生加成，星级真相在图鉴/池状态侧」→ 真相源为 `game_data.gacha_star_map`（G01 已入 Room v54，`GameDatabaseMigrationsV54.kt:26-28`） |
| C++ `disciple_stats.h:844-853` | `BreakthroughChanceInput` 加同名字段 | 🔴 **C++ `state.gameData` 需新增 `gachaStarMap` 协议字段**（G01 只加了 Room 列，C++ 侧未同步）→ **跨批依赖，本批无法独立完成** |
| C++ `disciple_stats.h:856-883` | 并入新增项 | 同上 |
| `GameConfig.kt` | 新增权重常量（禁魔法数字，`§5 0.4`） | — |
| M0 数值白皮书 | 重校准表新增"突破率星级项" | 属 M0 范围 |

**对拍影响**：
- 除口径①的全部对拍项外，**额外新增**：C++ ↔ Kotlin 星级协议字段的双端一致性守卫（新增 `Diff*` 用例）、`gameview_encode` 若走镜像还需扩 `DiscipleRow`；
- RNG 影响同口径①（甚至更大，因数值调整幅度更大）。

**结论**：⚠ **本批内做不完整**。星级真相在图鉴侧、C++ 侧无该协议字段 → 应登记为 **G12 / M0 数值重校准** 项，而非 G04 内落地。

#### 口径③ 保留"长老指导"改纯槽位职责加成

**语义**：删掉悟性输入，把长老加成改为**纯职务加成**：`elderGuidance = innerPos + outerPos`（或 `(1+innerPos)×(1+outerPos)−1`）。

**杠杆（好消息）**：所需形参**已存在**——
- C++ `disciple_stats.h:851-852 innerElderPositionBonus / outerElderPositionBonus`；
- Kotlin `DiscipleStatCalculator.kt:183-184` 同名字段；
- 当前仅作为 `compBonus(...) × (1 + pos)` 的**乘算因子**（C++ `:865-868`；Kotlin `属性Ops4.kt:427-430`）。

| 落点 | 改动 |
|---|---|
| `disciple_stats.h:865-868` | `innerBonus = comprehensionBreakthroughBonus(...) × (1+pos)` → `innerBonus = in.innerElderPositionBonus`（去掉悟性因子） |
| `disciple_stats.h:873` | `elderGuidance = innerBonus + outerBonus` 不变 |
| `disciple_stats.h:835-841` + `:54-57` + `:845-846` | 删 `comprehensionBreakthroughBonus` 与只服务它的常数/字段（`:851-852` **保留**） |
| `属性Ops4.kt:427-430` | 同上改法 |
| `属性Ops4.kt:437` | `elderGuidance = innerElderBonus + outerElderBonus` 不变 |
| `突破Ops5.kt:108/109` | 明细改读 `bonuses.innerElderPositionBonus`（不再经 `compBonus`） |
| `突破Ops5.kt:24-34` | `comprehensionBreakthroughBonus` 仍需保留？**否**（`selfComprehensionBonus` 随悟性删除）→ 删 |
| `DiscipleStatCalculator.kt:178 selfComprehension`、`:198 selfComprehensionBonus` | 删 |
| `DetailBasicInfoSection.kt:226-241` | `elderBreakthroughComprehension` 删除（不再需要悟性数值） |

🔴 **阻塞点（必须正视）**：`innerElderPositionBonus / outerElderPositionBonus` 的**现有数值来源是 `getPositionEffectBonus(elder, slotType)`，而它消费的是「天赋非负面 + 词条 `PositionBonus`」**（`disciple_stats.h:641-660` + `:666`；`docs/cpp-engine.md:2102` 登记「`stats::positionEffectBonus`（天赋非负面 + 词条 PositionBonus 按 slotType 求和，**消费 trait_db 静态数据单一源**）」）。

→ **天赋/词条整表删除后，`PositionBonus` 的数据源归零 ⇒ 口径③ 里 `elderPos` 也自动变 0**，除非：
- ① 把 `PositionBonus` 改为**配置常数**（按 `ElderSlotType` 10 种给固定值，入 `game_config.json` / `GameConfig`）—— **新增配置源，属新工作项**；
- ② 或改为按**境界差 / 魅力 / 传道技能**派生（复用既有弟子字段，零新协议）—— **需产品确认口径**。

**对拍影响**：
- 同口径①的数值面 + 下游 RNG 重录；
- 若选「配置常数」路线：新增配置源 → 需 `GameConfig`/`game_config.json` 双端单源守卫（`StaticDataSingleSourceGuardTest` 类）+ 新常量守卫测试；
- 若选「既有字段派生」路线：需新增纯函数 + 双端对拍用例（`DiffDiscipleTest` 扩 op）。

### 11.4 三口径对比与建议

| 维度 | ① 一并删除 | ② 权重转移星级/境界 | ③ 长老改纯槽位加成 |
|---|---|---|---|
| 本批可否独立完成 | ✅ **可以** | ❌ 需 C++ 星级协议字段（跨 G12/M0） | ⚠ 需先定 `PositionBonus` 新数值源 |
| 新增协议字段 | 无 | `gachaStarMap` 入 C++ GameData | 视路线：新配置源 或 无 |
| 长老在突破面的价值 | **归零** | 保留（经星级间接） | **保留**（纯职务加成） |
| 数值重校准范围 | 中（数值全表下降） | 大 | 中 |
| 对拍重录 | 必做（含下游分支消费差） | 必做（更多） | 必做 |
| UI 改动 | 删 3 展示行 + 长老悟性提取 | 同 + 新增星级展示 | 展示改文案（"长老职务加成"） |
| 与 §6.11「任命无特质写入」一致性 | ⚠ 长老收益进一步缩水，需产品确认 | ✅ | ✅ 最贴合「任命只有槽位职责」的措辞 |

**建议**：**采用口径①作为本批落地方案**（唯一可独立完成、无新协议），同时在 M0 白皮书登记「突破率数值重校准 + 长老突破贡献归零是否可接受」；若产品要求保留长老价值，则**优先口径③ + `PositionBonus` 改为按 `ElderSlotType` 的配置常数**（本批内可做，但需新增配置源与守卫），**口径② 明确排除出本批**（跨 G12）。

**§11 的开放决策必须由产品拍板，不得由施工方默认取值。**

---

## §12 未解决 / 需父 Agent 决策的开放项

| # | 项 | 说明 |
|---|---|---|
| 1 | **`DiscipleColumn` 枚举删项 vs 留洞** | 删项会平移所有列索引（`column_dirty` 位图 + `gameview_encode` 字段表 + Kotlin 三处）；留洞则留死列。**建议：单 commit 内删 + 同步四处 + 改守卫** |
| 2 | **Room 列 vs ProtoBuf 的差异化处置** | 两侧都"删"，但机制完全不同（Room `@Ignore` 保留旧列 / ProtoBuf `reserved` 锁号）→ 需在 commit message 与 ADR 显式区分 |
| 3 | **ActionId 「删常量」 vs 「留常量+删 catalog」** | 二者互斥（catalog 是唯一真源）。建议 catalog 保留 + desc 标废弃（保 `kAllActionIdsCount` 稳定） |
| 4 | 🔴 **突破率悟性乘区归属未定义** | **已扩写为 §11，需产品拍板三口径之一** |
| 5 | 🔴 **`createDisciple` 删 2+N 次 RNG 抽取未登记** | §6.6/§6.11 的 RNG 表未列此项，但它是最强的序列平移源（影响所有招募/AI 宗生成）→ 必须补进对拍重录清单 |
| 6 | **分支 9 偷盗判定的 SYSTEM 抽取** | G07 已随调用点删除而消失；该平移**至今未登记**，须补进历史重录说明 |
| 7 | **`intelligenceAdd` 被 UI 标为「悟性」的预存 bug** | `PillStatLine.kt:34` / `ItemDetailEffects.kt:33` / `ItemDetailOtherEffects.kt:250,570` / `PillEffects.kt:75`。删 `comprehensionAdd` 时**不要被误导去改这些行**（实为 intelligence） |
| 8 | **素材白名单** | `blood_refining_pool` 精灵/图集/`build-atlas.mjs` 键按 §6.12「素材暂闲置」**保留** → grep 清零清单必须带白名单 |
| 9 | 🔴 **`data_inject.h` 的"段缺失即拒"陷阱** | `data_inject.h:121-135` 若仍要求 `db.talents/physiques/affixes` 三键存在，删键后注入会整体 `return false` → **10 张表全部落内联兜底（静默降级）**。**必须同批删 `data_inject.h:121-135` 与 `:46-48` 计数字段** |
| 10 | **G07 已改动的 3 个洗炼测试** | `GameEngineSpiritRootWashTest.kt` / `GameEngineTraitAddTest.kt` / `GameEngineTraitWashTest.kt` 已被 G07 各改 6-9 行 → 本批删除前先 `git diff 89281bcc9 19dffb6d2 -- <file>` 确认改动性质，避免回滚 G07 语义 |
| 11 | **`ELDER_SKILL_BASELINE` 的双重身份** | `GameConfig.kt:905` 注释自陈「同时被**教学公式**引用，勿改名」→ 三口径都要小心：删突破用途时**不能连常量一起删** |

---

**报告完成度**：§1-§4 的 C++ / Kotlin 字段链与公式行号**逐行核实**（G07 后重扫）；§6 的 UI/Facade 落点**已核实**（`rg -n` 实测）；§7 的测试清单基于 `rg -l` 文件级 + 抽样行号核实（标【推测】处为未逐行读）；§9 分区/RNG 已核实；§11 为新增产品缺口分析（公式落点已核实，三口径落点为推演）。

**侦察纪律**：本档产出过程**未修改任何代码文件、未提交、未运行 gradle/ctest**。
