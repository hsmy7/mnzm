# G04 任务书（派工就绪 · 含主线程前置核验结论）

> **本文件用途**：G03 收官（`cc66d7918`）后，把 G04 拆成**可直接贴给子代理的细粒度分片**，并把主线程本轮**已实测核验**的三条前置结论固化下来，避免下一会话重复推导。
> **不重复** `recon-G04.md` 的落点表（子代理自己会读该文件）；本文件只放「侦察说过但经实测不成立」的纠正、口径变更、切片边界、门禁基线。
> **上游**：[`HANDOVER-m1-remaining.md`](HANDOVER-m1-remaining.md) §4 口径 15（悟性保留）、[`HANDOVER-m1-remaining-2.md`](HANDOVER-m1-remaining-2.md) §2/§3（作业规程）、[`report-G03.md`](report-G03.md) §八（G04 开工前置两件事）。

---

## 1. G04 范围（已按「悟性保留」收窄）

| 删 | 保留（不在本批） |
|---|---|
| 灵根洗炼（wash） | 🔴 **悟性 `comprehension` 全链**（用户 2026-09-24 拍板） |
| 资质 `aptitude` | `comprehensionBreakthroughBonus` / `baseComprehension` / `ELDER_SKILL_BASELINE` / `ELDER_BONUS_DIVISOR` / `ELDER_BREAKTHROUGH_MAX_STEPS` / `ELDER_BONUS_PER_STEP` |
| 天赋 / 体质 / 词条 三表（`talentIds`/`physiqueIds`/`affixIds` + 静态数据表本体） | 长老指导乘区结构（`innerElderComprehension` × `(1+innerPos)` 形态保留） |
| 血炼（四字段族 + 血炼池建筑消费面 + `randomBloodRefineStat`） | `masterId` 师徒链 —— **G04 不动，但已判给紧随其后的 G15 整体下线**（`HANDOVER-m1-remaining-3.md` §10） |
| 职位特质 `PositionBonus` 数值源（随天赋/词条表消失） | `renameSect`/没收/执法/`materializeDiscipleBagAndMarkDead`/`kDeadStatusName` 等前批保留面 |
| 战斗随机成长 `winBattleRandomAttrPlus` / `applyDeterministicWinAttr` | `blood_refining_pool` 素材名（`build-atlas.mjs`、webp、manifest —— §6.12「素材暂闲置」白名单） |

### 1.0 🔴 血炼池建筑**连建筑一起拆掉**（用户 2026-09-24 拍板，覆盖 recon §6.12「素材暂闲置」的暧昧表述）

不走 G02「建筑保留、只断功能」先例。 ⇒ 除血炼玩法链外，本批**额外承担**：

| 必改面 | 文件（实测 grep 得出） |
|---|---|
| 建筑定义 | `android/app/src/main/assets/config/buildings.json`（`blood_refining_pool` 条目） |
| 默认表 / 名称 / 装配 | `core/engine/.../config/Defaults.kt`、`core/engine/.../util/BuildingNames.kt`、`core/engine/.../domain/building/BuildingFeature.kt`（+ `BuildingFeatureTestRegistration.kt`） |
| 建造列表 UI 与槽位 | `BuildingsTab.kt`、`model/production/ProductionSlot.kt`（`SlotCategory.BLOOD_REFINEMENT`）、`GameEngineBloodRefinementOps.kt`、`GameEngineSelfHealOps.kt`（血炼自愈） |
| 🔴 **玩家已建建筑的读档清理** | 必须新增/扩展一条 `SaveValidator` 规则把旧档里的血炼池建筑与对应槽位清空（照 G05 `RecruitListCleanupRule` 恒清空先例），否则旧档会出现指向已删建筑类型的悬垂实例 |
| 🔴 **引导任务判据** | `core/domain/.../model/guide/GuideTask.kt` 若以血炼池建筑为条件 → 必须改判据，否则**引导卡死**（G02「引导防卡死」先例） |
| 守卫测试 | `BuildingTypeCoverageTest`、`BuildingBatchRemovalTest`、`BuildingRemovalSlotCleanupTest`、`BuildingSpriteFootprintGuardTest`、`SpriteAtlasDefGeneratedTest`、`GameEngineDualSlotGuardTest`、`BuildingNamesTest` |
| 素材 | `blood_refining_pool` 的 webp / `android/scripts/build-atlas.mjs` / atlas manifest **仍按 §6.12 白名单保留**（拆建筑不等于删图；图闲置无害，删图会牵动图集重生成） |

### 1.1 🔴 必须从 `recon-G04.md` §8 grep 清单里划掉的三条（否则会把保留的东西删掉）

| §8 条目 | 原文 | **实际口径** |
|---|---|---|
| **#14** | `mergeEffects\|talentEffectsFor\|affixEffectsFor\|physiqueCultivationBonusFor\|hpMpEffectsFor\|positionEffectBonus\|`**`baseComprehension`**`\|`**`comprehensionBreakthroughBonus`** | 前 6 项归零正确；**`baseComprehension` 与 `comprehensionBreakthroughBonus` 必须保留并有命中** —— 悟性保留。该条要拆成 #14a（归零）+ #14b（反向守卫） |
| **#18** | `comprehension`（排除 `comprehensionAdd` 前先删该键） | **整条作废**。`comprehension` 是保留面 |
| **#19** | `comprehensionAdd`（属性丹目标分支） | ✅ **已实测判定：保留，本批不删**。`recipe_db_sample.json` 有 **36 条非零**（智悟丹/悟德丹/灵悟丹/明悟丹…），`pill_system.h:219` `d.comprehension = clampSkill(d.comprehension + e.comprehensionAdd)` 真实生效，且 `pill_system.h:67/106` 把它列入丹效分类 ⇒ **不是死字段**。（G02 下架的是另一组 `pillType=comprehension` 丹方，与本字段无关） |

### 1.2 ✅ 主线程已实测推翻的侦察断言：**§12 开放项 #9 的 `data_inject.h` 陷阱因果写反**

侦察原文：「若仍要求 `db.talents/physiques/affixes` 三键存在，删键后注入会整体 `return false` → 10 张表**静默落内联兜底**」。

实测 `include/gamecore/data/data_inject.h:121-135`：

```cpp
if (db.contains("talents")) {
    if (!db["talents"].is_array() || db["talents"].empty()) return false;
    ...
}
```

⇒ 三键**整个不存在时是 `contains()` 为假直接跳过**，不触发 `return false`，10 张表照常注入。
**真正的降级触发条件是「键还在、数组为空」**（例如只删了 `gen-trait-db` 的表体却没删键）。

因此 G04 的正确动作是：
1. 删三表时，**中性源 `scripts/data/trait_db_sample.json` 与生成物 `game-data.json` 里三个键必须整个消失**，不能留空数组 —— 这才是防静默兜底的真红线；
2. `data_inject.h:121-135` 三个块与 `AppliedCounts.talents/physiques/affixes`（`:46-48`、`:151-153`）属**死代码清理**（顺手删，保持"注释只描述当前状态"），**不是**防御性修复；
3. 若 `StaticDataSingleSourceGuardTest:35` 之类守卫按「键存在」立据，须同批改断言。

### 1.3 🔴 本批必须自带的前置扫描（G03 连踩两次的结构性缺口）

G03 实测两处「侦察落点表按维度切分导致的漏项」：
- §9.7 判归 G03 的战场哀悼块未进 §G03-2 → **编译期**暴露；
- `GameData.daoCompanion*` 的 **C++ 环**（`models.h` + `json_codec.cpp` `GC_TO/GC_FROM` + `lock_beast_tx.h` SETTINGS_PATCH 白名单）在 §G03-1 字段链表**整表无行** → **运行期**才由 JUnit 抓获（16 条 `Diff*` 抛 `unknown key`）。

⇒ **G04 派工前主线程必须先跑一遍**：对每个待删的**非弟子列**持久化字段（至少 `bloodRefinements` / `activeBloodRefinements` / `bloodRefinementBonusTotals` / `bloodRefinementPctTotals`），逐一确认
`models.h` 声明 + `json_codec.cpp` 的 `to_json`/`from_json`（`GC_TO`/`GC_FROM` 宏）+ `game_data` Room 列 + `@ProtoNumber` reserved + **`lock_beast_tx.h` SETTINGS_PATCH 三处清单（apply / isSettingUnchanged / isKnownSettingField，删一个字段要三处同步 + 计数注释）** 是否都在任务书里有一行。
`BaselineFieldCoverageGuardTest` 会在 JUnit 抓漏网，但**编译期/派工期抓住的成本远低于运行期**。

---

## 2. 门禁基线（G03 收官实测；G04 开工即以此为准）

| 门 | 命令位置 | **G03 收官实测值** |
|---|---|---|
| 桌面 C++ 编译 | `build/desktop-test` → `cmake --build .` | EXIT=0 |
| 桌面 ctest | 同目录 `ctest` | **1470 总 / 1467 过 / 3 败**；3 败 = B 类（`DiscipleFactory.GoldenSequenceSeed42`、`DiscipleFactory.GoldenSequenceSeed987654321Female`、`DeterminismProbeTest.DigestMatchesGoldenBaseline`） |
| Kotlin 编译 | `cd android && ./gradlew.bat compileReleaseKotlin` | EXIT=0 |
| 测试源编译 | 五模块 `compileReleaseUnitTestKotlin` | **0 错误** |
| JUnit 全量 | `testReleaseUnitTest --max-workers=1 --rerun-tasks "-Dgamecore.jni.path=<绝对 .so>"` | **7662 / 0 / 0 / 17 skip**（app 1011 / domain 1646 / data 808 / engine 3085 / ui 146 / feature:game 966） |
| detekt | 六模块 | EXIT=0，baseline 只缩不增 |
| lint | `lintRelease` | BUILD SUCCESSFUL（17m） |
| JNI 计数 | `node scripts/check-jni-count.mjs` | **86 / 86** |
| ActionId | `node scripts/gen-action-ids.mjs` | **198 动作 / maxId=1861**；零漂移自证 = `git diff --exit-code` |
| 游戏数据 | `node scripts/gen-game-data.mjs --check` | sha256 `915563485e9fd41d14c77d76b2f25ff711c535e82f384d34a4faca5b10be84d2` |
| 规范门禁 | `node scripts/check-agent-instructions.mjs` | EXIT=0（改 `docs/`、`rules/`、任何 `AGENTS.md` 后必跑） |
| Room | `DATABASE_VERSION` = **57**；`schemas/…/57.json` 已入库 | G04 → **v58** |
| 跨语言对拍库 | `pwsh -File scripts/build-desktop-jni.ps1` | EXIT=0；**改任何 C++ 后必须重建再跑 JUnit** |

⚠️ **B 类判据（G03 血的教训，见 report-G03 §二·补）**：`Diff*` 家族红**不要顺手归 B 类**。`Diff*` 断言的是「C++ 输出 == Kotlin legacy 实现」的跨语言等价，**不是录制好的黄金数值** —— 两侧同步删除后它应当继续绿；一旦红了，先读异常类型与抛出点：`JsonDecodingException`/`unknown key`/缺符号 = **A 类协议漂移，必修**；只有 C++ 侧持固定摘要/固定序列常量的 GTest 金序列才是 B 类。
推论：**G10 重录窗口的实际工作量集中在 C++ GTest 金序列 + `DeterminismProbe` 摘要**，Kotlin Diff 家族预计无需重录（G10 终树实测确认后才可定论）。

---

## 3. 分片切法（**按 ≤10 文件/片**，禁按概念面切）

> **为什么**：G03 的 5 个分片（A1/T1/T2/A3/c340）**全部撞上子代理 150 轮硬上限中断**，改动虽落盘但无收尾报告，全靠主线程接管 + 二次细分片（S1/S2/S3，其中 S3 亦撞顶）才收口。
> 单个 subagent 的实际产能 ≈ **8–12 个需要 grep 复核 + Edit 的源码文件**。G04 的测试面（`aptitude` 单键 200+ 命中）远超此量，必须切到 6–8 片以上。

| 片 | 文件面（互不相交） | 上限 |
|---|---|---|
| **T-a** | C++ 洗炼/特质事务：`appointment_tx.h`（6 个 tx 函数 + `traitIdsOf`/`traitKindOf`/`rollCandidatesOf`/`topRarityPoolOf`/`deprecatedTalentTypes`/`lifespanBonusOf`/`resolveOne` helper）、`execute_dispatch.cpp` 对应 6 case | 2–3 |
| **T-b** | C++ 三表静态数据：`data/trait_db.h`、`data/data_inject.h:121-135`+`:46-48`+`:151-153`、`data_store`、`test/CMakeLists.txt`、`disciple_factory.h`（步 2 悟性资质 roll + 步 3 三段特质 roll —— ⚠️ **步 2 只删 `aptitude` 那次抽取，`comprehension` 那次必须保留**） | 5–6 |
| **T-c** | C++ 列链三端环：`state/models.h`、`state/disciple_store.h`（`DiscipleColumn` 枚举删项 → **列索引平移**）、`state/column_dirty.h`（`discipleColumnName` switch + `kDiscipleColumnCount`）、`src/json_codec.cpp`（含 GameData 血炼四字段）、`src/gameview_encode.cpp`、`proto/game_view.proto`、`jni/GameCoreJni.cpp` | 7–8 |
| **T-d** | C++ 公式乘区：`disciple.h`（`aptitudeCultivationBonus` 及 3 常数、`kWinAttr*`）、`disciple_stats.h`（`mergeEffects`/`talentEffectsFor`/`affixEffectsFor`/`physiqueCultivationBonusFor`/`hpMpEffectsFor`/`positionEffectBonus`；⚠️ `baseComprehension`/`comprehensionBreakthroughBonus` **保留**）、`battle_residual_tx.h`（`applyDeterministicWinAttr` + 分支 9 残段）、`blood_refinement.h`、`sect_defense_battle.h` | 5–6 |
| **T-e** | C++ 测试（列双射五件 + `appointment_tx_test` + `disciple_factory_test` + `battle_residual_tx_test` + `trait_db_test`/`diff_trait_effects` + `dispatch_guard_test` 退役集） | ≤10 |
| **A-a** | Kotlin 生产：`GameEngineSpiritRootOps.kt`(整删)/`GameEngineTraitAddOps.kt`/`GameEngineAppointmentNativeOps.kt`/`GameEngineResidualNativeOps.kt`/`GameEngineWorldBattleOps.kt`/`DiscipleDelegateWashOps.kt` | 6 |
| **A-b** | Kotlin UI：`SpiritRootWashDialog.kt`(整删)/`TraitWashDialog`/`TraitAddDialog`/`BloodRefiningViewModel`/血炼池建筑面/`DiscipleDetailDialogs.kt`/`DetailBasicInfoSection.kt`/`DetailCultivationSection.kt` | 8–10 |
| **A-c** | Kotlin 模型/镜像：`Disciple.kt`(SkillStats)/`DiscipleComponents.kt`/`DiscipleSerializer.kt`/`GameData.kt`(血炼四字段)/`DiscipleTables.kt`+`ColumnRegistry`+`Assemblers`+`Write`+`SelfHeal`/`GameViewMirrorCodec.kt`/`DiscipleStatCalculator*.kt` 多文件类 | 8–10 |
| **A-d** | 配置与静态数据源：`GameConfig.kt`（wash/trait 段）、`game_config.json`、`scripts/data/trait_db_sample.json`、`scripts/gen-trait-db.mjs`、`scripts/data/recipe_db_sample.json`、跑 `gen-trait-db`/`gen-recipe-db`/`gen-game-data` 并记录新 sha256；⚠️ **三表键整个消失，不得留空数组**（§1.2） | 6–8 |
| **c340-×N** | Kotlin 测试按目录切 **3–4 片**：① `core/domain` 模型/序列化 ② `core/data` 迁移链+proto 守卫+夹具 ③ `core/engine` service/gameview ④ `feature/game`+`app` | 各 ≤10 |
| **主线程** | Room v58 四件套（迁移类 + `DATABASE_VERSION` 57→58 + 注册 + `58.json`）+ 迁移测试本体 + 双 changelog + `report-G04.md` + 全门禁 + 单次提交；**并复核 §1.3 前置扫描是否每片都收全** | — |

**每片 prompt 必须自包含**（子代理看不到会话上下文）：背景一句 + 范围删/保留两栏 + §1.1 的三条 grep 口径纠正 + §1.2 的 data_inject 结论 + §2 基线数字 + 文件面白名单 + 禁改清单 + 验证命令（含「Bash 必须用 `dir_path` 参数，裸 `cd` 绝对路径跑 `./gradlew.bat` 会 EXIT=127」）+ 「多行改动只用 Edit/Write，禁用 node/perl/sed 脚本改源码（CRLF 静默损坏事故先例）」+ 「不 commit、不 `git checkout`/`reset`/`stash`」。

**同机只允许一路 Gradle 组合门**（并行会互锁 `classes.jar` 制造假红）→ 子代理优先只跑 `compile*`，全量测试留主线程终树复跑。

---

## 4. 串行与共享文件约束

- G04 与已合入的 G02/G03 **共享** `models.h` / `disciple_store.h` / `column_dirty.h` / `DiscipleTables*.kt` / `Disciple.kt` / `battle_residual_tx.h` / `execute_dispatch.cpp` → **每片开工前 `git status` 必须干净**（本轮 G03 起点即 `3f0297708` 干净树，收官 `cc66d7918`）。
- `DiscipleColumn` 枚举删项（`talentIds`/`physiqueIds`/`affixIds`/`aptitude`/血炼相关）会**平移后续全部列索引** → T-c 与 T-e 必须同 commit 内改完 `column_dirty.h` switch、`gameview_encode.cpp` 字段表、Kotlin `columnGroupByIndex`、`DiscipleTablesColumnRegistry` 四处双射面（守卫：`column_dirty_test.cpp` + `gameview_encode_test.cpp` 9 用例）。
- ActionId：**沿用「保号 + desc 标【已退役，编号禁复用】+ 删 dispatch case + `dispatch_guard_test` 退役集登记」口径**（G03 的 1592/1750 先例）。
  ⚠️ 侦察 §5.2「建议」写的是「catalog 保留条目」，但**它同时提到 `kAllActionIdsCount` 不变** —— 实测生成器 `:163 kAllActionIdsCount = ACTION_CATALOG.length`，**保留条目则计数不变、删条目则计数减少**。G03/G02/G05/G06 全部走「保留条目 + 保号」路线并维持 **198/1861**，G04 沿用即可，**不要中途换路线**（换路线要写 ADR）。
  涉及：1613 / 1614 / 1615 / 1616 / 1732 / 1733 / 1746（7 个）。
- 血炼池素材走 §6.12 白名单：`android/scripts/build-atlas.mjs:125/368`、`android/scripts/lib/atlas-offline-rgba-lib.mjs:202`、`drawable*/blood_refining_pool.webp` + atlas manifest **全部保留**。

---

## 5. 状态

| 项 | 值 |
|---|---|
| M1 进度 | **4 / 9**（G02 `5dbaac1e3`、G05 `8e593a71b`、G06 `e1a69d8e9`、**G03 `cc66d7918`**） |
| 当前 HEAD | `cc66d7918` |
| 工作树 | 干净；素材两目录已由 `.gitignore` 兜住（`rules/media-source-assets.md`），未跟踪仅剩 `docs/research/`×2 不提交 |
| 下一批 | **G04**（本文件） |
| 其后 | **G15（师徒下线）→ G16（素材批）→ G08 → G09 → G11 → G10**（顺序与依赖见 `HANDOVER-m1-remaining-3.md` §1.2） |
| 遗留待拍板 | ~~三项已全部关闭~~（2026-09-24）：① 师徒 → **整体下线**，拆给 **G15**（`HANDOVER-m1-remaining-3.md` §10）；② `comprehensionAdd` → 实测**活字段，保留**（§1.1 #19）；③ 素材 → 单独开 **G16**（§11），且两个素材目录**只登记不入库**（`rules/media-source-assets.md`）。本批新增范围：**血炼池连建筑拆掉**（§1.0） |

---

## 6. 🔴 主线程开工前置扫描结论（2026-09-24 实测，派工前必读）

> 按 `HANDOVER-m1-remaining-3.md` §3.4 两项侦察缺口回查执行；以下结论全部来自本轮 grep 实测（HEAD `af3550152`）。

### 6.1 实测推翻 / 修正侦察的项

| # | 结论 | 证据 |
|---|---|---|
| 1 | **C++ 侧战斗随机成长残留已被 G02 清掉**：`battle_residual_tx.h`/`dispatch_w4c.cpp`/`exploration_tx.h:43` 对 `applyDeterministicWinAttr`/`winAttr`/`soulPower` **零命中** → §6.5 只剩 Kotlin 面 | `grep -n` 实测 |
| 2 | **`computeLifespan`/`loyaltyFlat` 已随 G02 消失**，C++ include/src 零命中 → §10 交叉点表这两行失效 | 同上 |
| 3 | **`lock_beast_tx.h` 对血炼字段零命中** → SETTINGS_PATCH 三处清单**本批不需要改**（比 G03 的 daoCompanion 少一环） | 同上 |
| 4 | 🔴 **血炼 C++ 生产面实为 ~35 文件**（recon §4.2 只列 ~9），三类形态：① 8 个事务头文件的 `in.activeBloodRefinements = state.gameData.activeBloodRefinements;` + 写回复制对（`appointment_tx.h:157/171`、`disciple_lifecycle_tx.h:61/76`、`disciple_tx.h:310/325`、`exploration_tx.h:486/499`、`mission_start_tx.h:103/129`、`production.h:1163/1176`、`patrol_tx.h:168/181`、`secret_realm_residual_tx.h:86/99`）；② `brPct` 统计透传（`exploration_tx.h:271/368`、`mission_completion.h:518-950`、`sect_conquest.h:126-128`、`phase_settlement.h:114-146`、`sect_power.h:50-82`、`secret_realm_session.h:749/905`）；③ 零散（`guide_reward_tx.h` `kBloodRefinementCompleted` 条件枚举、`building_residual_tx.h:179` 建筑拆除清血炼、`disciple_tx.h` `startBloodRefinementTx` 事务13+`BloodRefinementStartParams`、`dispatch_w4a.cpp:102-115` 血炼启动端口、`year_settlement.h:15` include） | 逐文件 grep |
| 5 | 🔴 **三表消费方 13 文件**（recon §2 只铺 stats/factory）：`ai_sect_ops.h`(16 命中)、`ai_sect_recruit.h`(8)、`phase_settlement.h`(7)、`pill_system.h`(6)、`breakthrough.h`(6)、`data_json.h`(6)、`production.h`(4)、`sect_power.h`(3)、`disciple_tx.h`(3)、`auto_gear.h`(3)、`mission_completion.h`(2)、`index_snapshot.h`(2)、`data_store.h`(1) | 逐文件计数 |
| 6 | 🔴 **`GameData.pendingTraitAdds`（@ProtoNumber(1002)、Room 列 `pending_trait_adds`、`Strategy.PRESERVE_OLD`）+ `PendingTraitAdd` data class（`GameData.kt:40`）整条链在 recon 字段链表无行** —— trait add 链的 GameData 级字段，随三表下线一并删 | `GameData.kt:827-832` |
| 7 | **引导任务 #24「血炼强化」= `BuildingCount("血炼池",1)` + `BloodRefinementCompleted(1)` 两条件**（`GuideTask.kt:444-450`）→ 整任务删除 + `GuideCondition.BloodRefinementCompleted`（:165-174）删 + C++ `ConditionKind::kBloodRefinementCompleted`（`guide_reward_tx.h:63/129/231-232`）删 | 实测 |
| 8 | **`BloodRefinementRefRule`（order=17）整删**（其被检字段随批消失）；`ManualTalentRefRule`（order=22，talentIds 悬空清理）整删或收窄；`ItemRefConsistencyRule`（order=18）talentIds 段收窄；**新增血炼池建筑+`BLOOD_REFINEMENT` 槽位恒清空规则**（照 `RecruitListCleanupRule` 先例）并注册进 `SaveValidationRuleDefaults` | `SaveValidationRuleDefaults.kt` |
| 9 | **`GameDatabaseMigrationSupport.kt:151-154` 的 game_data CREATE TABLE 含血炼四列** → Room 四件套连带（fresh-install schema 必须与 v58 实体一致） | 实测 |
| 10 | **dispatch_guard_test 退役集现有 13 条** → 本批 +7（1613/1614/1615/1616/1732/1733/1746）= 20 条 | `dispatch_guard_test.cpp:105` |
| 11 | **`SlotAssignment.kt:33 BLOOD_REFINEMENT`、`GameEventRecord.kt:60 BLOOD_REFINEMENT`**（行号较 recon 漂移） | 实测 |
| 12 | **悟性保留导致的反向修正**：recon §6.2 中 `DiscipleUtils.kt` comprehension 排序键、`AttributeFilterOption:23` 悟性项、`DetailCombatSection:37` 悟性行、`AutoManagementDialog` 悟性阈值、`DiscipleComponents:310` 悟性行**全部保留不动**（侦察写于口径 15 之前） | 口径 15 |

### 6.2 悟性面收窄细则（口径 15 的实施级展开，各片必读）

1. `comprehension` 列、`SkillStats.comprehension`、`comprehensionBreakthroughBonus`、`ELDER_SKILL_BASELINE/ELDER_BONUS_DIVISOR/ELDER_BREAKTHROUGH_MAX_STEPS/ELDER_BONUS_PER_STEP`、突破率三乘区**全部保留**。
2. `BreakthroughChanceInput`（C++）/`BreakthroughZoneBonusInput`（Kotlin）的 `innerElderPositionBonus/outerElderPositionBonus` 字段**保留**（三表删除后恒 0，组装点传 0）；`positionEffectBonus` 函数本体**删除**。
3. `baseComprehension` 保留，但其 `comprehensionFlat`（三表特效键）贡献项**删除** → 收窄为悟性本体读取；C++ JNI 对拍 op `baseComprehension` 的 `talentIds/affixIds` 参数同步收窄（`DiffDiscipleTest` 对应 op 断言两侧同改）。
4. `BaseStatsInput.talentEffects`/`baseStats(WithBr)` 的特质聚合填充**删除**（数据源消失；口径 15 只保突破率乘区结构，不含特质数值通道）。
5. `intel ligenceAdd` 被标「悟性」的 5 处预存文案 bug **不动**（recon §12#7）。

## 7. 修订切片表（按实测文件面重切，替代 §3；每片 ≤10 文件）

| 片 | 文件面 |
|---|---|
| **T-a** | `system/appointment_tx.h`、`src/execute_dispatch.cpp`、`src/dispatch_w4a.cpp`（wash/trait 6 tx + 7 case 退役 + 血炼启动 case + in/out 复制对 + SpiritRootWashOutcome/TraitWashOutcome + GameConfig 常量段洗炼部分） |
| **T-b** | `data/trait_db.h` 整删、`data/data_inject.h`、`data/data_json.h`、`data/data_store.h`、`data/index_snapshot.h`、`system/disciple_factory.h`（步2 只删资质 roll、悟性 roll 保留；步3 特质 roll 整删）、`test/CMakeLists.txt` |
| **T-c** | 列双射核心 8 文件：`state/models.h`、`state/disciple_store.h`、`src/disciple_store.cpp`、`state/column_dirty.h`、`src/json_codec.cpp`（弟子 4 列 + GameData 血炼四字段 + pendingTraitAdds）、`src/gameview_encode.cpp`、`proto/game_view.proto`、`jni/GameCoreJni.cpp` |
| **T-d** | `system/disciple.h`、`system/disciple_stats.h`、`system/blood_refinement.h`（`eraseDiscipleDerivedMaps` 收窄为 manualProficiencies 或随调用方迁移）、`system/battle_residual_tx.h`、`system/sect_defense_battle.h`、`system/month_settlement.h` |
| **T-e** | `system/disciple_tx.h`、`system/building_residual_tx.h`、`system/exploration_tx.h`、`system/mission_start_tx.h`、`system/production.h`、`system/patrol_tx.h`、`system/secret_realm_residual_tx.h`、`system/secret_realm_session.h`、`system/slot_cleanup.h` |
| **T-f** | `system/guide_reward_tx.h`、`system/mission_completion.h`、`system/phase_settlement.h`、`system/sect_conquest.h`、`system/sect_power.h`、`system/disciple_lifecycle_tx.h` |
| **T-g** | `system/ai_sect_ops.h`、`system/ai_sect_recruit.h`、`system/auto_gear.h`、`system/breakthrough.h`、`system/pill_system.h`、`system/year_settlement.h`（include 摘除 + `eraseDiscipleDerivedMaps` 收口配合 T-d） |
| **A-a** | `GameEngineSpiritRootOps.kt` 整删、`GameEngineTraitAddOps.kt` 整删、`GameEngineTraitWashOps.kt` 整删、`GameEngineTraitWashRoll.kt` 整删、`GameEngineAppointmentNativeOps.kt`、`GameEngineResidualNativeOps.kt`、`GameEngineJadePurchaseOps.kt`、`service/JadeSymbolService.kt` |
| **A-b** | `gameview/GameDataFieldPatch.kt`、`gameview/GameViewDiscipleRows.kt`、`nativebridge/GameViewMirrorCodec.kt`、`registry/GameDataManager.kt`、`usecase/SectPolicyToggleUseCase.kt`、`service/CultivationRateCalculator.kt`、`service/FormulaService.kt`、`service/DiscipleBreakthroughHandler.kt`、`BootSequenceController.kt` |
| **A-c** | `DiscipleStatCalculator.kt`、`修炼Ops1/属性Ops3/属性Ops4/战斗Ops2/突破Ops5/修炼Ops6`、`SectCombatPowerCalculator.kt`、`DiscipleStatsProvider.kt`、`domain/disciple/DiscipleFactory.kt` |
| **A-d** | `GameEngineWorldBattleOps.kt`（winAttr 整段）、`GameEngineBattleOps.kt`、`GameEngineMissionOps.kt`、`GameEngineScoutOps.kt`、`GameEngineServiceOps.kt`、`GameEngineDiscipleSlotOps.kt`、`GameEngineSelfHealOps.kt`、`SlotWinner.kt`、`domain/battle/BattleSystem.kt`、`domain/battle/AISectAttackManager.kt` |
| **A-e** | `EncounterBattleService.kt`、`ExplorationService.kt`、`MissionSystem.kt`、`CaveExplorationSystem.kt`、`PatrolBattleSystem.kt`、`BattleDescriptionGenerator.kt`、`HpMpRecoveryService.kt`、`CultivationEventMissionOps.kt`、`MonthSettlementExecutor.kt`、`SecretRealmService.kt` |
| **A-f** | `Disciple.kt`、`DiscipleSerializer.kt`、`DiscipleComponents.kt`、`DiscipleExtended.kt`、`DiscipleAggregate.kt`、`DiscipleAttributes.kt` |
| **A-g** | `DiscipleTables.kt`、`DiscipleTablesAssemblers.kt`、`DiscipleTablesColumnRegistry.kt`、`DiscipleTablesWrite.kt`、`DiscipleTablesAptitude.kt` 整删、`AssembleGroup.kt` |
| **A-h** | `GameData.kt`（血炼四字段 + pendingTraitAdds + reserved 登记）、`GameDataBloodRefinement.kt` 整删、`guide/GuideTask.kt`（#24 删）、`TalentDatabase/PhysiqueDatabase/AffixDatabase/TalentRegistry` 四整删、`registry/WeightedRoll.kt`（特质分布删） |
| **A-i** | `DiscipleAssignmentGate.kt`、`DiscipleDerivedMapsCleanup.kt`、`DiscipleSlotCleanup.kt`、`BuildingFeature.kt`、`BuildingNativeTx.kt`、`util/BuildingNames.kt`、`AISectDiscipleManager.kt`、`AISectDiscipleManagerMisc.kt`、`config/Defaults.kt`（blood_refining_pool 条目+别名） |
| **A-j** | data 模块：`OldSerializableSaveData.kt`（血炼四字段 + pendingTraitAdds + SerializableBloodRefinement* 类删 + reserved）、`CollectionConverters.kt`、`EnumConverters.kt`、`BloodRefinementRefRule.kt` 整删、`ManualTalentRefRule.kt`、`ItemRefConsistencyRule.kt`、`SaveValidationRuleDefaults.kt`、**新增** `BloodPoolBuildingCleanupRule.kt` |
| **A-k** | `SpiritRootWashDialog.kt` 整删、`TraitWashDialog.kt` 整删、`TraitAddDialog.kt` 整删、`WashSessionControl.kt` 整删、`BloodRefiningPoolDialog.kt` 整删、`BloodRefiningViewModel.kt` 整删、`DiscipleDetailDialogs.kt`、`DiscipleDetailScreen.kt`、`DetailActionButtons.kt`、`DetailBasicInfoSection.kt` |
| **A-l** | `DetailCultivationSection.kt`、`DetailCombatSection.kt`、`AttributeFilterOption.kt`、`DiscipleDelegate.kt`、`DiscipleDelegateWashOps.kt` 整删、`DiscipleDelegateTraitAddOps.kt` 整删、`DiscipleDelegateLifecycleOps.kt`、`NavigationDelegate.kt`、`BuildingFeatureBoot.kt`、`SpiritMineViewModel.kt` |
| **A-m** | `MainGameScreen.kt`、`GameOverlayHost.kt`、`OverlayDialogRouter.kt`、`BuildingSelection.kt`、`AttackHpGuard.kt`、`MissionHallDialog.kt`、`AttackDiscipleDialog.kt`、`LevelDetailDialog.kt`、`HeavenlyTrialViewModel.kt`、`SettingsDelegate.kt` |
| **A-n** | `core/ui/navigation/GameRoute.kt`、`DialogProductionRoutes.kt`、`core/ui/DiscipleComponents.kt`、`core/domain/dialog/DialogType.kt`、`SlotAssignment.kt`、`GameEventRecord.kt`、`app/XianxiaApplication.kt`（statsProvider 形参）、`app/GameActivity.kt`、`app/GameStateStoreImpl.kt` |
| **A-o** | 配置源：`core/domain/GameConfig.kt`（wash/trait 段）、`assets/config/buildings.json`（条目+别名）、`scripts/data/trait_db_sample.json` 整删、`scripts/gen-trait-db.mjs` 整删、`scripts/gen-game-data.mjs`（三表段）、`core/engine/src/test/resources/templates/trait_db_sample.json` 整删 |
| **主线程** | Room v58 四件套（`MIGRATION_57_58` + `DATABASE_VERSION` + 注册 + `58.json`）+ `GameDatabase.kt` + `GameDatabaseMigrationSupport.kt`（:151-154 血炼四列）+ 迁移测试本体 + `game-data.json` 重生成 + hash + ActionId regen + 全门禁 + 双 changelog + report + 提交 |

测试面（Wave 3，派工时按当轮 grep 现值再切）：C++ 测试 ~22 文件切 2 片；Kotlin 测试 ~97 文件按模块切 8~9 片。

> **派工提示**：侦察 §1.1（wash/trait 三端字段链）、§2（公式乘区）、§4（C++ 行号）、§6（Kotlin UI 行号）是各片的落点细节来源——**行号系 G02/G03 前快照，施工前必须逐处 grep 复核**；本表只定文件面边界与互斥性。
