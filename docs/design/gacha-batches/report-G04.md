# G04 · 删洗炼 / 资质 / 天赋·体质·词条三表 / 血炼（血炼池连建筑整建制拆除）/ 职位特质 / 战斗随机成长（悟性保留） — 批次报告

> 批次：G04（M1 序列第 5 批；前置 G02 `5dbaac1e3` + G05 `8e593a71b` + G06 `e1a69d8e9` + G03 `cc66d7918`）
> 侦察：`recon-G04.md`（行号系 G02 前快照）+ **`TASKBOOK-G04.md` §6 主线程前置扫描 / §7 修订切片表**（本批新增，按实测文件面重切）；执行协议 `EXECUTION-PROTOCOL.md`
> 分片：C++ 生产 T-a…T-g（7 片）→ Kotlin 生产 A-a…A-r（17 片，含补派 A-a 与孤儿收口 A-r）→ 测试 cT-1/cT-2 + cK-domain/data/e1…e7/f1（11 片）+ 主线程集成（Room v58、REFINING 残段、Combatant 链、图集管线）

## 一、做了什么

### 1. 删除面（三端）

| 域 | 删除/收缩 |
|---|---|
| **洗炼/特质事务** | `appointment_tx.h` 959→216 行（`spiritRootWashTx`/`traitAddRollTx`/`traitAddConfirmTx`/`traitWashSlotTx`/`spiritRootWashConfirmTx`/`traitWashConfirmTx` 六 tx + `TraitKind`/`traitIdsOf`/`rollCandidatesOf` 等特质族 + 洗炼常量 + `activeBloodRefinements` in/out 复制对），任命/卸任三事务保留；`execute_dispatch.cpp` 6 case + 中央区间分支收窄（无吞号）；Kotlin 四引擎 Ops 整删 + 六 UI 文件整删 + `AppointmentNativeOps/ResidualNativeOps` 洗炼臂删；ActionId **1613/1614/1615/1616/1732/1733/1746**（+1437 零消费者顺延）保号退役，retired 集 13→**21**，regen 198/1861 不变 |
| **资质** | `SkillStats/DiscipleStats.aptitude`、`aptitudeCultivationBonus` + 3 常数、资质阶梯/哨兵 50（`avoidSentinel50`/`rollAptitude`）、`DiscipleTablesAptitude.kt` 整删、`healDefaultAptitudes`/`DEFAULT_APTITUDE`、`createDisciple` 步 2 资质抽取（**悟性抽取保留**）、`ColumnRegistry`/`columnGroupByIndex` 平移 |
| **三表** | C++ `trait_db.h` 整删（748 行）+ `data_inject.h` 三表注入块与 `AppliedCounts.talents/physiques/affixes` + `data_json/data_store/index_snapshot` 清理；Kotlin `TalentDatabase/PhysiqueDatabase/AffixDatabase/TalentRegistry/WeightedRoll.kt` 整删；`trait_db_sample.json`（源+测试副本）与 `gen-trait-db.mjs` 整删、`gen-game-data.mjs` 三表段删；**game-data.json 三键整个消失**（§1.2 真红线），sha256 `035066cbcb891aa124397667e58879d51d4dd4124177ae38b550e1b2c22094ef` |
| **血炼（P-2：连建筑拆掉）** | C++ `blood_refinement.h` 整删、`startBloodRefinementTx`（事务 13，1746 端口）、月结 `settleSingleRefinement` 链、**8 事务头 `activeBloodRefinements` in/out 复制对**、6 文件 `brPct` 统计透传、`building_residual_tx` 拆除清血炼段 + `discipleIds` REFINING 破除段、`guide_reward_tx.h` `kBloodRefinementCompleted` + **引导任务 #24 双端删**；Kotlin `GameEngineBloodRefinementOps` 整删 + `BloodRefiningViewModel/BloodRefiningPoolDialog` 整删 + `SlotCategory.BLOOD_REFINEMENT`/`BuildingType.BLOOD_REFINING_POOL`/`DiscipleStatus.REFINING` 枚举删值 + 建筑注册/名称/Defaults 条目；**新增 `BloodPoolBuildingCleanupRule`**（order=17，旧档血炼池实例+关联槽位清理）；`buildings.json` 条目删 + 图集 LAYOUT/footprints/webp 删 + 图集重生成 |
| **职位特质** | `positionEffectBonus` 双端删（数据源随三表消失）；`BreakthroughChanceInput`/`BreakthroughZoneBonusInput` 的 `inner/outerElderPositionBonus` **字段保留、生产侧恒传 0**（口径 15：形参与公式形态不收窄）；`sect_power.h`/`SectCombatPowerCalculator` 战力指纹 10→8 项同构 |
| **战斗随机成长** | C++ `applyDeterministicWinAttr` 残留已被 G02 清掉（本批实测确认）；Kotlin `GameEngineWorldBattleOps`（`winBattleRandomAttrPlus` 判定 + `applyDeterministicWinAttr` 17 分支整删）、`PatrolBattleSystem`/`ExplorationService` 胜利随机成长链删；`applyWorldLevelVictoryTransaction` 收窄 `(levelId, updatedLogs)` |
| **战斗伤害乘区** | `battle.h`/`battle_calculator.h`/`battle_json.h` 与 Kotlin `BattleCalculator/BattleJsonCodec/BattleModels` 两侧同删 `PhysiqueCombatFactors`/`AffixCombatEffects` 与 `DamageZones` 8 个体质/词条乘区字段及公式因子（恒等因子删除，FP 语义无数值变化） |
| **GameData 级 5 字段** | 血炼四字段（proto 115/150/151/152）+ `pendingTraitAdds`（1002）三端整删（`models.h`/`json_codec` GC_TO/GC_FROM/Kotlin `GameData`/`OldSerializableSaveData`），双侧 reserved 登记；`PendingTraitAdd`/`SerializableBloodRefinement*` 类删 |

### 2. 🟢 保留面（红线核对）

- **悟性全链**：`comprehension` 列（DiscipleColumn/json_codec/gameview 89/proto）、`comprehensionBreakthroughBonus`、ELDER 四常数、长老有效悟性提取与「悟性加成」展示行、`comprehensionAdd` 丹效链、`rollComprehension`（弟子/AI 宗/兑换码三处生成面）——终态 grep 全部在位。
- **灵根系统**：`SpiritRootConfig`/`rollSpiritRootCount`/`spiritRootGenerate`（AI 宗接线）与 `GameConfig.SpiritRoot` 非洗炼段原样。
- **师徒链**：`masterId`/`masterBonusFor`/`social_masterId` 零触碰（G15 才删）。
- 每旬回血、月结/年结流程、10 类槽位清理、其余全部建筑、兑换码本体（天赋授予面删，G08 改道）。

### 3. Room v57→v58（主线程）

`MIGRATION_57_58`（`rebuildTableDroppingColumns` 两表 create-copy-drop-rename，幂等）——**2 表 9 列**：`disciples` 4（talentIds/physiqueIds/affixIds/aptitude）+ `game_data` 5（血炼四列 + `pending_trait_adds`）；`DATABASE_VERSION` 57→58 + `ALL_MIGRATIONS` 注册 + `schemas/58.json` 入库（disciples 95→91、game_data 133→128，5+5 索引全保留，comprehension 存续）；历史快照零改写（KSP 曾就地改写 57.json，`git checkout --` 还原后 bump 版本重生成，§2.3 坑 1 实证）。`RoomMigrationV57To58Test` 新增（真实 Room 校验 / 9 删列消失 + 悟性列逐值存活 / 扣列逐格全等 / 幂等）；`RoomMigrationV51To52Test` 全链期望集 +V58 五列。

### 4. 改动规模

**终树规模（复核会话修正与文档回写后实测）：359 个已跟踪文件改动（311 改 + 48 删）+ 5 个新增文件（4 源文件 + 本报告），+2482 / −25,970。**
原报「354 文件 +1660/−24,740」系中间态统计，已由 §二·补 表行 4 更正。整删 48 文件：C++ 生产 2 + C++ 测试 2 + Kotlin 生产 17 + Kotlin 测试 21 + 资源/脚本 6。增行主体：`58.json`、`GameDatabaseMigrationsV58.kt`、`RoomMigrationV57To58Test.kt`、`BloodPoolBuildingCleanupRule.kt`；删行主体：三静态表（`trait_db.h` 748 + 三 Registry 1,634 + trait_db_sample.json ×2 4,868）、血炼 Ops/UI（`GameEngineBloodRefinementOps` 301 + `BloodRefiningPoolDialog` 604）、洗炼/特质测试族（约 2,000）。

## 二、主线程前置扫描与裁决记录（TASKBOOK §6 的产出）

| # | 项 | 处置 |
|---|---|---|
| 1 | 🔴 **血炼 C++ 面实为 35 文件**（recon §4.2 只列 9）：8 事务头 in/out 复制对 + 6 文件 brPct 透传 + `guide_reward_tx`/`dispatch_w4a`/`building_residual`/`year_settlement` include | 前置扫描入任务书，T-e/T-f/T-g 三片分摊，全部归零 |
| 2 | 🔴 **三表消费方 13 文件**（recon §2 只铺 stats/factory）| T-g/T-e/T-f 分摊归零 |
| 3 | 🔴 **`GameData.pendingTraitAdds` 字段链在 recon 无行**（proto 1002/pending_trait_adds 列）| 归 T-c/A-h，三端整删 + reserved |
| 4 | **`lock_beast_tx.h` SETTINGS_PATCH 零命中**（比 G03 daoCompanion 少一环）| 免改，T-c 复核贴证 |
| 5 | **`applyDeterministicWinAttr`/`computeLifespan`/`loyaltyFlat` C++ 残留已被 G02 清掉**| recon §6.5/§10 相应行作废 |
| 6 | **`json_codec.h` 8 行悬空 ADL 声明**（任务书白名单漏项）| T-c 主动扩展白名单同批删（裁决正确） |
| 7 | **`redeem_code.h` 白名单外死链**（T-b 发现：唯一 avoidSentinel50 消费方）| T-g 收口；A-q 顺收 `RedeemCodeManager/RewardOps` 兑换码天赋面 |
| 8 | **A-a 片漏派**（引擎洗炼/特质 Ops 整删组）| 编译门暴露后补派，`GameEngineSpiritRootOps` 等 4 文件整删 |
| 9 | **REFINING 状态收口**：15 生产消费者散布多模块| 独立 A-p 片删枚举值（序列化四路优雅降级 IDLE 评估在案）+ 主线程清 C++ 字符串比较残段（`disciple_tx`/`sect_defense_battle`/`mission_start_tx`/`secret_realm_residual_tx`/`building_residual_tx` kRefiningStatusName + REFINING 破除段）|
| 10 | **孤儿断点批量收口（A-r）**：`Gear.kt` GEAR_ROLL 门、`Generation.kt` 死代码、`ProductionProcessor`、`BattleModels` Combatant.physique/affix、`BuildingsTab` 漏派分支、`BeastMaterialDatabase.BloodRefineRule` 孤儿、`WeightedRoll.kt` 空壳整删、`GameEngineExplorationNativeOps` skipNativeDomainWrites| 主线程补 survivorIds 形参链收口（`applyWorldLevelVictoryTransaction` 两参化）|
| 11 | **图集管线三重断层**（本批最大意外）：① `generateSpriteAtlasDef` 校验 buildings.json↔LAYOUT 一一对应——血炼池从 buildings.json 删除后 LAYOUT 必须同步（TASKBOOK §1.0「素材保留」假设被生成器硬约束推翻，P-2 拍板的直接后果）；② `footprints` 数组按索引对齐 buildingNames，删名必须同步删占地项（首版修复漏项被保真校验抓获）；③ 🔴 **R3.8/B13 预存缺陷**：tier1 字形（48 个合成精灵）混入 assets `atlas-manifest.json`（HEAD 基线 42 → 重生成 89），破坏 manifest↔SpriteAtlasDef 双向一致契约——**G04 是 R3.8/B13 合入后首个重生成图集的批次所以首爆**，修复 = manifest 写入面剔除 tier 精灵（tier rect 走 FLOAT_UV codegen，不属 manifest 契约），重生成后 41/`6a122ed2` 与 Kotlin 复现逐项一致 |
| 12 | **`ManualTalentRefRule` 整删的附带损失**：该规则还承担 manualIds 悬空清理| `ItemRefConsistencyRule` 已有 manualIds 段保留；差异语义登记 G10（§八）|

## 二·补、🔴 第二会话同轮复核发现的 4 处失实（全部已修，实测值见 §六）

> 复核方式：不采信本报告自证，按 `HANDOVER-m1-remaining-3.md` §3.5「不信子代理自证」在**同一棵树**上
> 复跑 C++/Kotlin 两侧门禁。结论：**原 §六 的 ctest / detekt / JUnit 三项「绿」均不可复现**，
> 根因是图集重打包与洗炼/血炼删除留下的**跨片孤儿断点**（分片白名单外、生成器不覆盖、编译不报错）。

| # | 原判据（本报告声称） | 同轮实测 | 根因 | 修正 |
|---|---|---|---|---|
| 1 | ctest 1413 中 **3 败**，与 B 类集逐条同名 | **6 败**：多出 `SceneEquivalenceTest.BuildingsWithStructureAllCameras` / `.FullSceneAllCamerasAndDegrade` / `.GeneratedUvTablesMatchKotlinFormulaFixture` | 🔴 这三条是 **A 类协议漂移**（不是数值红）。`scene_equivalence_test.cpp` 的「旧路径臂」夹具 `kotlinBuildingUv()` 是 **Kotlin SpriteAtlasDef 的手工快照复刻**（19 栋 + sect_gate = 20 项，含血炼池）；G04 从 `build-atlas.mjs` LAYOUT 删血炼池后生成物 `scene_uv_tables.h` 已 20→19（中级多人住所 rect `(1560,2072)`→`(1040,2072)`），**夹具未随动** ⇒ 两臂逐位比对错位、`kStructureNameBase` 19→18 未同步 | 夹具删血炼池行 + 中级多人住所 rect 改 `(1040,2072,512,512)` + nameIdx 19→18 + 两处口径注释；复跑 **1413 / 1410 过 / 3 败**，3 败与 B 类集同名 |
| 2 | detekt 六模块 EXIT=0 | **EXIT=1，6 条红**（`:core:engine`） | ① ② ③ 三处 `UnusedImports`（`AISectAttackManager`/`BattleSystem` 的 `DiscipleStatCalculator`、`BattleJsonCodec` 的 `putJsonObject`）随本批删除成为孤儿；④ ⑤ 本批新写的 `@Suppress("UnusedParameter") // …` 单行注释 135 字符 ×2 破 `MaxLineLength: 120`；⑥ `FormulaService.calculateSuccessRateBonus(disciple, buildingId)` 的 `buildingId` 随 talentZone 删除不再消费（即 §八-5 那条「保留」的登记项——detekt 不许保留） | 三 import 删；两条长注释拆到注解上方；**`calculateSuccessRateBonus` 死链整删**（`FormulaService` 函数 + `GameEngineFormulaOps` 零调用方桥接，实测全库仅 3 处命中且互为调用链，无第三方消费者）⇒ 复跑 EXIT=0 |
| 3 | JUnit 7647 全绿 | 两条守卫**必红**：`SpriteAtlasDefGeneratedTest`（期望 19 栋名 + 19 对占地，生成物实为 18/18）、`BuildingSpriteFootprintGuardTest`（期望表含「血炼池 4×3×4×3」，兜底 `Defaults.kt` 已删该条目 ⇒ 数量断言 19 vs 18） | 两文件**根本不在本批改动清单里**（`git status` 干净）——图集切片的文件面只覆盖了 Kotlin **生产侧** `Defaults.kt`/`build-atlas.mjs`，漏了这两处测试侧镜像。旁证：`SceneUvTablesMirrorGuardTest`/`AtlasLayoutSyncTest`/`FootprintTableSyncTest` 全部**动态**取 `SpriteAtlasDef.BUILDING_NAMES.size`，故生成物一致即绿，掩盖了「静态复刻表」这一类 | 期望表按生成物真值收窄（18 栋 / 18 对 / 17 条兜底尺寸 + 用例名「19 个/19 对」→「18」）；复跑见 §六 |
| 4 | §一-4 规模「354 文件 +1660/−24,740」 | `git diff --stat` 实测 **354 文件 +2419/−25,894**（修正前） | 原数字取自某次中间态统计，非终树 | 以本行实测为准，终树数值见 §一-4 更正 |

**同类排查（举一反三，公约 4）**：把「手工复刻的静态期望表」作为独立一类扫了一遍——`grep` 全部图集/建筑/列面测试，
命中并修复即上表 3 的两个文件；`AtlasManifestSyncTest`/`SpriteCodegenSyncTest`/`BuildingSpriteFootprintJsonGuardTest`
本批已由分片改到真值（`git status` 为 M）。C++ 侧同类风险只有 `scene_equivalence_test.cpp` 一处（上表 1）。

**规程回写（供 G15 及以后）**：图集/生成物变更批必须把
① `scene_equivalence_test.cpp` 的手工 UV/rect 夹具、② `SpriteAtlasDefGeneratedTest` 的解析期望表、
③ `BuildingSpriteFootprintGuardTest` 的兜底尺寸期望表 三处**列入分片文件面**——
它们都不被生成器覆盖、不报编译错、且其中两处只由 ctest/全量 JUnit 暴露。

---

## 三、A/B 类判据执行（G03 血的教训沿用）

- ctest 终树 **3 败与 B 类登记集逐条同名**（`DiscipleFactory.GoldenSequenceSeed42`/`GoldenSequenceSeed987654321Female`/`DeterminismProbeTest.DigestMatchesGoldenBaseline`）——零 A 类。⚠️ 本条原判定**曾漏计 3 条 SceneEquivalenceTest A 类红**，由第二会话复核补出并修复（见 §二·补 表行 1）。
- Kotlin 侧：`Diff*` 家族两侧同步删除后全绿（`DiffTraitEffectsTest` 整删、`DiffDiscipleTest` op 口径收窄、`DiffSectDiplomacyTest` 指纹 8 项）——**Kotlin 零 B 类**，继续支持「G10 重录工作量集中在 C++ 金序列」的推论。
- 中途 6 红（AtlasManifestSync×2/SpriteCodegenSync/BuildingSpriteFootprintJsonGuard/DiscipleMergeCoverage/BuildingNamesTest）**全部为 A 类陈旧期望或生成物错位**，逐条按根因修复（见 §二-11、§五），未发生「数值红误归 B 类」。

## 四、RNG 序列平移面（预期，登记不重录）

| 消费点删除 | 分区 | 序列影响 |
|---|---|---|
| `createDisciple` 步 2 资质 1×nextInt + 步 3 特质三段（次数不定）| 调用方分区 | **所有创建/招募/AI 宗生成下游平移**（recon §9.1 #5 补登记项兑现）|
| AI 宗 `generateRandomDisciple` 资质 roll + 特质三段 + `rollMissingCategories`（A-i 与 C++ T-g 同构删除，清单在案）| AI | AI 分区平移（Kotlin 镜像同删，双端等价保持）|
| 洗炼/特质六 tx（SYSTEM，玩家时序驱动）| SYSTEM | 不平移月结/年结序列（同 G03 论证）|
| `randomBloodRefineStat`（修炼Ops6）/血炼启动抽签 | SYSTEM | 玩家时序驱动，随血炼删除消失 |
| 突破率数值面变化（资质乘区删除→突破率分布变化→成功/失败分支消费差）| BREAKTHROUGH | 条件分支消费次数差，**月结/年结对拍基线受影响**——C++ 侧 3 条既有 B 类已涵盖（精确同名），Kotlin Diff 家族实测全绿 |

**金黄/baseline 零改动、零重录**（全局唯一重录窗口 = G10）。

## 五、旧用例处置表（删 21 文件 / 整删用例约 90 / 摘行改断言约 70 文件）

**C++ 测试（ctest 1470→1413，净删 57 例算术闭合）**
- 整文件删：`trait_db_test.cpp`、`trait_effects_test.cpp`（T-b）
- 整用例删约 40：洗炼/特质 tx 族（24）、血炼启动/月结/槽位/单利族、`GoldenSequenceBuffPhysiqueSeed99`（被测因子删除）、`DefenseBonusIndependent` 等
- 摘行/改断言：金序列×2（仅摘已删符号行，**黄金值未动**）、`column_dirty`/`json_codec`/`data_store`/`gameview_encode` 列面、`guide_reward_tx` 25→24、`sect_diplomacy` 指纹 8 参、`disciple_derived_maps` **重写为 manualProficiencies 单键语义守卫**（补上「空则删键」分支覆盖）
- `gameview_encode_test.cpp` **零改动**（cT-1 实证：从未断言 17/18/19/96——侦察 §7.2 推测项作废）

**Kotlin 测试（app 1011→996；整删 21 文件）**
- 整文件删：三 Database 测试、`WeightedRollTest`、`DiscipleTablesSelfHealTest`、`ManualTalentRefRuleTest`、洗炼/特质引擎测试 4 + Roll 测试 2、`RedeemCodeManagerTalentTest`、`BloodRefinementSimpleInterestTest`、`DiffTraitEffectsTest`、`TraitRegistryGuardTest`、UI 三测试
- 摘行/收窄：statsProvider fake 全链收窄（getTalentEffects 删 + getFinalStats 4 参）、`DiffDiscipleTest` baseStats op 口径改写（期望按现公式推导）、`SlotCategoryCoverageTest` 9 值、`BuildingTypeCoverageTest` 16 值、`DialogTypeRenderCoverageTest` 26 路由、`SpriteCodegenSyncTest` 41→40 + `中级多人住所` 坐标 (1560,2072)→(1040,2072)、`BuildingSpriteFootprintJsonGuardTest` 19→18、`DiscipleMergeCoverageTest` 分类清单、`ProtoNumberUniquenessTest` **新增 reserved 禁复用守卫**（GameData 115/150/151/152/1002 + DiscipleSerializer 22/104/105/110）

## 六、验证（全部实跑）

| 门 | 结果 |
|---|---|
| 桌面 `cmake --build`（终树复跑 ×2） | ✅ EXIT=0（含 battle 域清理后复验） |
| 桌面 ctest | ✅ **1413 总 / 1410 过 / 3 败**（第二会话终树复跑实测）；3 败与 B 类登记集逐条同名，零 A 类；总数 1470→1413 与删除面算术闭合。⚠️ 修复前实测为 **6 败**，多出 3 条 SceneEquivalenceTest A 类红（§二·补 表行 1） |
| `compileReleaseKotlin`（全链） | ✅ EXIT=0（生产面先行单独验证绿后进测试面） |
| 六模块 `compileReleaseUnitTestKotlin` | ✅ EXIT=0、**0 错误** |
| JUnit 六模块（`--max-workers=1 --rerun-tasks --continue` + `-Dgamecore.jni.path` 跨语言桥真跑） | ✅ **7397 / 0 失败 / 0 错误 / 18 skipped**（app 995 / domain 1578 / data 803 / engine 2927 / ui 146 / feature:game 948）——**第二会话终树实测，取代原报的 7647**；六模块 `testReleaseUnitTest` 任务全部实跑、678 份 XML 全部晚于本轮起跑时刻（陈旧计数 0）。原报 7647（app 996/domain 1646/data 808/engine 3085/feature 966）**不可复现**：其计数混入了首轮无 `--continue` 中断留下的**已删测试类陈旧 XML**（整删 21 个测试文件正是本批动作），故比真值高 250 例 |
| detekt 六模块 | ✅ `--continue` EXIT=0（baseline 零新增）。⚠️ 修复前实测 **EXIT=1 / 6 条红**（3 孤儿 import + 2 破 120 字符 + 1 UnusedParameter），见 §二·补 表行 2 |
| `lintRelease` | ✅ BUILD SUCCESSFUL |
| `build-desktop-jni.ps1` | ✅ EXIT=0（对拍库 8,560,640 B——较 G03 9,094,656 B 缩小，与删除面一致；JNI 变体构建暴露 `GameCoreJni.cpp` zones 对拍段残缺，desktop-test 不含 jni/ 目录故仅此门可见，已修）。第二会话复核：`find` 实测**无任何生产 C++ 源（include/src/jni/scene）晚于该 .so**，本会话改动仅落在 C++ 测试面与 Kotlin ⇒ **合法复用、未重建**，JUnit 跨语言对拍真跑仍成立 |
| `check-jni-count.mjs` | ✅ **86 / 86**（对拍 op 是统一导出内分支，删分支不动导出） |
| `gen-action-ids.mjs` | ✅ **198 动作 / maxId=1861**；diff vs HEAD 仅 8 条退役 desc + 1810 desc 修正（在役事务去「REFINING破除」失效表述） |
| `gen-game-data.mjs --check` | ✅ sha256 `035066cbcb891aa124397667e58879d51d4dd4124177ae38b550e1b2c22094ef`（三键消失后的新基线） |
| `check-agent-instructions.mjs` | ✅ EXIT=0（改 `TASKBOOK-G04.md`/`docs` 后必跑；2 条告警系预存） |
| Room schema 链 | ✅ 仅 `58.json` 新增；`disciples` 95→91 列、`game_data` 133→128；9 删列残留 0；`comprehension` 存续；索引 5+5 重建 |
| 图集管线 | ✅ `generateAstcAtlas` + `generateSpriteAtlasDef` 绿（89 精灵 KTX + manifest 41 精灵/`6a122ed2` 与 Kotlin 复现一致；显示尺寸保真校验 18 栋建筑 + 9 装饰通过） |
| 暂存区危险项扫描 | 见 §七提交段 |

## 七、grep 终态证据

- **删除归零**（生产面 = C++ gamecore include/src/jni + Kotlin 六模块 src/main，排除 build/）：
  `spiritRootWash|SpiritRootWash|SPIRIT_ROOT_WASH` **0**；`traitAdd|traitWash|TRAIT_ADD|TRAIT_WASH|traitIdsOf|traitKindOf|rollCandidatesOf|topRarityPoolOf|deprecatedTalentTypes` **0**；
  `talentIds|physiqueIds|affixIds` **0**（Room 历史迁移链 SQL 字符串、reserved 注释、迁移测试种子除外，逐条贴证豁免）；
  `aptitude|Aptitude`（`artifactRefining/pillRefining` 炼器炼丹字段除外）**0**；
  `bloodRefine|BloodRefine|BloodRefining|blood_refining_pool|BLOOD_REFINEMENT|REFINING`（退役 ActionId desc、BloodPoolBuildingCleanupRule 字面量、历史迁移链除外）**0**；
  `TalentDatabase|PhysiqueDatabase|AffixDatabase|TalentRegistry|WeightedRoll|tier1 残留|winBattleRandomAttrPlus|applyDeterministicWinAttr|kWinAttr|pendingTraitAdds|PendingTraitAdd|avoidSentinel50|GEAR_ROLL_MARKER|getPositionEffectBonus|comprehensionFlat` **0**
- **反向守卫（必须仍有命中）**：`comprehension`/`comprehensionBreakthroughBonus`/`kElderSkillBaseline` 系 C++ 15 处 + Kotlin 30+ 处；`masterId`/`masterBonusFor`/`social_masterId` 全在；`rollComprehension` 三处生成面在位；`spiritRootGenerate` 接线在位。
- 保留命中逐条已由各分片报告贴证（本报告汇总口径，明细见 docs/design/gacha-batches/ 各分片报告存档于会话记录）。

## 八、G10 登记（本批新增）

**B 类**：C++ 侧维持 3 条既有（精确同名）；Kotlin 侧实测 **0 条**。`Diff*` 家族两侧同步删除后全绿——进一步证实「G10 重录集中在 C++ GTest 金序列 + `DeterminismProbe` 摘要」。

**途中发现 / 待收口（本批不动，公约 12）**
1. `DiscipleFactory.h` 头注释「技能 9 × gaussianInt」与实际 8 次不符（预存，两处自相矛盾）——金序列重录时核对。
2. `PatrolBattleSystem.applyVictoryRewards` 上方注释「出生随机流走 SYSTEM 分区」与实际 EXPLORATION 分区不符（预存文案 bug）。
3. `ManualTalentRefRule` 删除后其 manualIds 非空悬空清理职责并入 `ItemRefConsistencyRule` 与否需拍板（现两处语义有重叠但不完全等价）。
4. `ai_sect_recruit.h` 头注释「年龄 1×nextInt」与函数体不符（G02 面预存失真）；`BeastMaterialDatabase` 的 `getBloodMaterials`/`getTierPercentage`/`getTierDuration`/`getTierPrefix` 辅助段零消费者（不引用已删符号，编译自洁，G10 死代码清零连带）。
5. ~~`FormulaService.calculateSuccessRateBonus` 的 `buildingId` 形参在 talentZone 删除后体内不消费（API 面因白名单外桥接保留）~~ → **第二会话复核改判：detekt `UnusedParameter` 直接判红，不许保留**。实测 `calculateSuccessRateBonus` 全库零外部消费者（仅 `FormulaService` 定义 + `GameEngineFormulaOps` 转发桥两处），**死链整删**；`GameEngineWorldBattleOps` 的 `survivorIds` 形参删除已与 `GameEngineExplorationNativeOps` 调用点同批完成，1781 `WORLD_VICTORY_REWARDS_TX` 评估结论 = **保留**（C++ handler 承载 TOCTOU 重查 + 奖励落账独立职责）。
6. 🔴 **图集 manifest 契约修复属管线级**：R3.8/B13 起 `buildSpriteList` 混入 tier1 字形导致 assets manifest 膨胀（42→89）——本批修复为「manifest 只写 map 精灵集」；若后续批次认为 manifest 应全量列出，需同步改 `AtlasManifestSyncTest.reproduceSpriteEntries` 并写 ADR（两条路线不得并存）。
7. `GameConfig` 洗炼段删除后 `JadePurchase` KDoc 与 `SKILL_MAX` 注释已同步现值口径；`EffectKeyNames`（`DiscipleDetailDialogs`）保留 27 条映射中部分键失去生产引用（零成本保留，G12 死文案扫描连带）。
8. 突破率 `inner/outerElderPositionBonus` 现恒 0——若产品未来要恢复长老突破贡献，口径③（配置常数化）预案在 recon §11.3。
9. **活文档仍描述已删玩法**（G04 按批次惯例不动文档，交 G10「文档收口」统一清）：`docs/cpp-engine.md` §目录树 `data/ … trait_db …` 行、序列化预算表「血炼三件套」「天赋体质词条 204」行；`docs/architecture.md`、`docs/knowledge-base.md`、`CODE_WIKI.md`、`docs/ui-read-surface.md` 各含血炼/洗炼/资质/天赋词条面描述。`node scripts/check-agent-instructions.mjs` 不校验此类语义陈旧（只校验死链），故不会自动报警。
10. **注释面残留「血炼」表述**（不引用已删符号，纯文案，G10 注释七项检查连带）：`DiscipleStatusData.kt` 类注释、`BeastMaterialDatabase.kt` 「血炼系统辅助方法」段头、`DerivedAggregationTest.kt:170`、`DiscipleFacadeRewardTest.kt:196`、`FakeAtomicStateStore.kt:62`、`GameDatabaseMigrationsV46.kt` 提及已删的 `healDefaultAptitudes()`（历史迁移注释，链不可改写，保留为历史事实）。

**工程事实（供交接）**：本批实切 30 分片（C++ 7 + Kotlin 生产 17 + 测试 11 + 主线程），无一撞 150 轮上限（≤10 文件/片 + 编辑密度预估生效）；主线程集成捕获跨片断点 16 项——「每片自包含 + 白名单硬纪律 + 白名单外只登记」的代价是主线程收口批次不可省。

## 九、诚实状态声明

- **已核实**：ctest 3 败与 B 类集逐条同名；Kotlin 六模块测试源编译 0 错误 + 全量测试绿；图集三重断层的根因链（buildings.json↔LAYOUT 同步、footprints 索引对齐、tier 混入 manifest）均有生成器源码与守卫输出直接证据。
- ✅ **第二会话独立复跑并追加核实**（不采信上表自证，逐门重跑）：桌面编译 EXIT=0、ctest 1413/1410/3、`compileReleaseKotlin` EXIT=0、六模块测试源编译 EXIT=0、detekt `--continue` EXIT=0、JUnit 六模块 7397 全绿（678 份 XML 零陈旧）、`check-jni-count` 86/86、`gen-action-ids` 重跑 198/1861 且**生成物零额外漂移**（两文件对 HEAD 的 diff 恰为 9 行/文件 = 8 条退役 desc + 1810 desc 修正，与 catalog 闭合）、`gen-game-data --check` sha256 一致且 `talents`/`physique`/`affixes` **三键整个消失**、`check-agent-instructions` EXIT=0、Room `DATABASE_VERSION=58` + `MIGRATION_57_58` 已注册 + `58.json` 逐列核对（91/128 列、5+5 索引、9 删列零残留、`comprehension`/`social_masterId` 存续、历史快照仅新增）、`BloodPoolBuildingCleanupRule` order=17 唯一且已进 `registerDefaults`、ActionId 退役集 dispatch_guard 与 catalog 双向计数一致（21 条）。
- ⚠️ **本批「一次性提交」尚未发生**：上一会话实施完成并写完本报告后停在工作树未提交状态（354 改 + 4 新增 + 本报告），提交动作由第二会话复核后执行。
- **推测 / 未核实**：「Kotlin Diff 家族 G10 无需重录」延续 G03 推论（本批实测全绿进一步支持）；`DiscipleStatus`/`BuildingType` 枚举删值的旧档安全性依据四路解析面代码证据（`safeDiscipleStatus`→IDLE、`BuildingTypeAsStringSerializer`→ALCHEMY、`BloodPoolBuildingCleanupRule` 持久化前清理），未做真机旧档实测。
- **未完成 / 遗留**：§八 1-10 项登记 G10/G12；真机验证批与 G15/G16/G08/G09/G11/G10 未开始（M1 进度 **5/9**）。
