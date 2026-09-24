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
