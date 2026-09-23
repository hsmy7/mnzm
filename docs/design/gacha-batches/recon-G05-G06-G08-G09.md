# 角色卡池重构 G05 / G06 / G08 / G09 · 精确落点侦察报告

---

## 时效说明（2026-09-23 复核）

| 项 | 状态 |
|---|---|
| 工作树 | **已回净**（仅 `android/app/src/main/assets/atlas/atlas-rgba-manifest.json` 与 `android/scripts/sprite-uid-map.json` 为 M，属精灵管线产物；另有 4 项 untracked：`docs/design/gacha-batches/EXECUTION-PROTOCOL.md`、`docs/research/android-game-perf-sota-2026-09.md`、`docs/research/mobile-perf-quality-adaptation-benchmark.md`、`模拟宗门美术素材/`、`模拟宗门音乐音效/`） |
| G01 | `89281bcc9 feat(gacha): G01 脚手架…` |
| **G07（已落库）** | `19dffb6d2 feat(gacha): G07 玩家侧战死改重伤——全量调用点摘除 + 复用既有每旬回血` |

### G07 对本报告的影响面（施工前必复核行号）

| 影响项 | 变化 | 对本报告的影响 |
|---|---|---|
| `InventorySystem.materializeDiscipleBagAndMarkDead` | 已改为**只写重伤（HP=1）**，不再 `markDead` | `InventorySystem.kt` 行号基本稳定（复核后 `:285`）；G06 逐出袋物化路径不受影响，但 `disciple_death` 来源名语义变化 |
| `CombatService` | 新增 `applyBattleInjuries` / `applyLegacyCasualtyChain` | 与 G05/G06/G08/G09 无直接交叉；G07 报告独立 |
| **`GameConfig.kt`** | **行号漂移 +6** | 本报告 §G09-1 / §G11-5 中 `object Gacha` 的旧行号 `204-238` **已失效**，现为 **`:210-245`**（详见 §G11-5） |
| **`DiscipleTables.kt`** | 行号漂移 | 本报告 §G08-5 的 `DiscipleTables.kt:109 templateIds` **须复核** |
| **`GachaDelegate.kt`** | **构造参数已去掉未用的 `gameEngine`** | 当前签名 = `GachaDelegate(private val gachaFacade: GachaFacade)`（文件 21 行，`:12-14`）。本报告 §G09-1 旧记录（双参构造）**已失效**，以本行为准 |
| `DiscipleLifecycleProcessor.kt` / `PatrolBattleSystem.kt` / `DiscipleDeathHandler.kt` / `sect_defense_battle.h` / `death_handler.h` / `battle_residual_tx.h` / `secret_realm_session.h` | G07 已提交 | 与 G05/G06/G08/G09 无交叉 |

> **纪律**：本报告为**读取时快照**。凡标记 ⚠️ 或 §X-4「尚需复核项」的行号，施工前必须重新 grep 确认。**禁止盲信行号**。

---

> 侦察性质：**只读**。未修改仓库任何代码文件（本文件为唯一写入产物，经父 agent 明确授权）。
> 未运行 gradle / ctest / cmake；未运行任何会写盘的 node 脚本。
> 时段：G01 `89281bcc9` 之后、G07 `19dffb6d2` 之前（故部分行号相对 G07 后有所漂移，见上方时效说明）。

---

## 0. 侦察时段与并发观察（历史记录）

| 项 | 事实 |
|---|---|
| 侦察时 HEAD | `89281bcc9`（G01） |
| 侦察时工作树 | 29 项未跟踪/已修改（25 M + 4 ??） |
| **并发变动（侦察时已核实）** | 两次 `git status` 之间文件集合变化：首见 `game-data.json` / `game-data.hash.txt` / `.scout-g07-callpoints.md`，第二次全部消失；`.scout-g07-callpoints.md` 已不存在 |
| 结论 | 侦察期有另一进程在同一工作树内写入（G07 侦察痕迹 + 内存重构 MR 批次）。**G07 现已落库（`19dffb6d2`），工作树已回净**，但上述观察说明「施工期需假定存在并行写者」 |

---

## 1. 六个「与任务书前提不符」的核实结论（先看）

| # | 任务书前提 | 核实结果 | 证据 |
|---|---|---|---|
| A | `system/gacha_tx.h`（G01 已建空壳） | ❌ **不存在**。`glob include/gamecore/system/*.h` 无 `gacha_tx.h`；`grep gacha` 在 C++ 只命中 `models.h` 与 `json_codec.cpp` 的**协议字段**。G01 只落了 Kotlin 骨架 | `models.h:1169-1179,1400-1403`；`json_codec.cpp:905-911,1327-1328,1418-1419` |
| B | `characterTemplates` 读没读 | ❌ **无任何生产读取方**。全仓 grep 只命中：① `GachaConfigGuardTest.kt`（测试直读 JSON）② 生成脚本 ③ JSON 产物。**Kotlin 无 Registry、C++ 无 data 表、`GameConfigData` 无对应字段** | `GachaConfigGuardTest.kt:51-56,90-95`；`gen-game-data.mjs:129-135,152,158-173`；`GameConfigData.kt` grep `gacha` 零命中 |
| C | 开局 50,000 灵石 | ⚠️ `GameConfig.Gacha.START_SPIRIT_STONES = 50000` 已定义但**全仓零引用**（死常量）。新档实际 = `GameData.spiritStones` 默认 **1000**（差 49 倍） | `GameConfig.kt`（G07 后 `:217`）；`GameData.kt:143 var spiritStones: Long = 1000`；`GameDataRepositoryImpl.kt:27` |
| D | 「探索随机人类敌人」造 `Disciple` | ❌ **不造 Disciple**，造战斗体 `Combatant`（`enemy.id="human_enemy_N"`），`ENEMY_GEN` 分区，无 `templateId`/`portraitRes` 概念 | `EnemyGenerator.kt:24-74`；`mission_completion.h:624-766` |
| E | `disciple_expelled` 事件 | ⚠️ **只定义、无发布点**（死事件） | grep 仅 5 命中，全在 `GameEvents.kt:182-186` 与 `GameEventsTest.kt` |
| F | `gen-action-ids.mjs --check` | ❌ **无 `--check` 参数**。脚本零参数、**直接覆写**两份产物；零漂移自证靠 `git diff --exit-code` | `gen-action-ids.mjs` 全文 203 行无 `process.argv`；`action-catalog/README.md:37-50` |

**另**：G08 提到的 `RedeemCodeRewardOps`（已核实存在）、`initialSaveData` / `GameStateFactory` / `newGame` 三个名字（**全仓零命中**，方案虚构）。真实落点为 `GameEngine.createNewGame` + `GameDataRepositoryImpl.initializeNewGame`（见 §G08-2）。

---

# G05 · 删招募链

## G05-1 完整调用图（UI → … → C++ 事务 → ActionId）

| 层 | 落点（已核实） |
|---|---|
| **UI 按钮** | `GameActionButtons.kt:102-105 <br>FloatingActionButton(text="招募", spriteName="ui_recruit_button"){ viewModel.navigateToDialog(DialogType.Recruit) }` |
| **备用入口** | `NavigationDelegate.kt:84-86 openRecruitDialog()` → `onNavigate(GameRoute.Recruit)` |
| **路由映射** | `GameRoute.kt:63 GameRoute.Recruit to DialogType.Recruit` |
| **DialogType** | `DialogType.Recruit`（`core/domain/.../domain/dialog/DialogType.kt:32`） |
| **路由分发** | `OverlayDialogRouter.kt:35`（`is DialogType.Recruit` 归入 feature 路由分支）→ `DialogFeatureRoutes.kt:43 DialogType.Recruit -> renderRecruit(...)` |
| **渲染壳** | `DialogFeatureRoutes.kt:27 import RecruitDialog`；`:59-67 private fun renderRecruit`（读 `viewModel.recruitListAggregates`） |
| **对话框本体** | `RecruitDialog.kt:31 入口`；`:88 RecruitListContent`；`:155 RecruitManagementDialog`；`:230 RootCountFilterGrid`；`:256 AutoRecruitFilterRow` |
| **VM 状态源** | `GameViewModel.kt:535-537 recruitListAggregates = gameData.map{ it.recruitList.distinctBy{id}.map{toAggregate()} }` |
| **Delegate** | `DiscipleDelegate.kt:204 recruitDisciple(agg)` → `:120 recruitDiscipleFromList(id)`；`:166 recruitAllDisciples()`；`:198 rejectDiscipleFromList(id)`；`:257 setAutoRecruitFilter`；`:261 setAutoRejectFilter` |
| **Engine 扩展** | `GameEngineRecruitOps.kt:21 recruitAllFromList()`；`:120 removeFromRecruitList(id)`；`DiscipleFacadeImpl.kt:217 recruitDiscipleFromList`；`GameEngineSettingsAssignOps.kt:62 setAutoRecruitFilterValidated` |
| **单招 native 臂** | `DiscipleFacadeImpl功法Ops1.kt:45 GameCoreBridge.nativeManualRecruitFromList(discipleId)` |
| **一键招 native 臂** | `GameEngineRecruitOps.kt:37-54 tryNativeRecruitAll()` → `:38 GameCoreBridge.nativeRecruitAllFromList()`；回退 `:57 recruitAllFromListLegacy()` |
| **JNI 声明** | `GameCoreBridge.kt:284 nativeManualRecruitFromList`、`:295 nativeRecruitAllFromList`、`:137 nativeResetAutoRecruitIdle` |
| **JNI 实现** | `GameCoreBridge.cpp:544`（manual）、`:558`（all）、`:417`（reset idle） |
| **C++ 事务** | `recruit_settlement.h:780 manualRecruitFromList`、`:837 manualRecruitAll`、`:647 processAutoRecruit`、`:354-408 allocateAndInsert 等价` |
| **C++ execute 通道** | `recruit_tx.h:81 removeRecruitTx / :104 refreshRecruitTx / :128 ageRecruitTx` |
| **ActionId** | C++ `action_ids.h:403 RECRUIT_REMOVE_TX=1630`、`:406 RECRUIT_REFRESH_TX=1631`、`:409 RECRUIT_AGE_TX=1632`；Kotlin `ActionIds.kt` 同名同号 |
| **dispatch 注册** | `execute_dispatch.cpp:2291-2319 handleRecruitTx`（switch）；dispatch 链 `:2746-2748` |
| **Kotlin native 臂** | `RecruitService.kt:476-498 tryNativeRefreshRecruitList`、`:539-551 tryNativeAgeRecruitList` |
| **旧档读档净化** | `GameEngineLoadDataOps.kt:207-218 sanitizeRecruitListAfterLoad()`（注释明写「cache 命中路径绕过 SaveValidator」） |

> ⚠️ `execute_dispatch.cpp:2769-2772` 头注释声明 **「本文件此后冻结」**——W4 三批次后新域应走独立 `dispatch_w4X.cpp`。G09 应新增 `src/dispatch_gacha.cpp` + `test/gacha_tests.cmake`（模式见 `action-catalog/README.md:52-53`），**不要**改 `execute_dispatch.cpp`。

## G05-2 `autoRecruit*` / `processAutoRecruit` 全量落点

| 类别 | 落点 |
|---|---|
| Kotlin 定义 | `RecruitService.kt:107 processAutoRecruit`、`:271 processAutoReject`、`:168 resolveAutoRecruitFilter`、`:320 sanitizeRecruitList`、`:343 resetAutoRecruitIdle`、`:354 resetAutoRejectIdle`、`:366-369 object RecruitLazyState{autoRecruitIdle,autoRejectIdle}` |
| Kotlin 调用点 | `CultivationEventMonthlyOps.kt:57 / :90 / :137`；`CaveExplorationProcessor.kt:96`；`ChildBirthSystem.kt:124`；`RecruitService.kt:456`；`GameEngineRecruitOps.kt:63` |
| 持久化字段 | `GameData.kt:566 autoRecruitSpiritRootFilter: Set<Int>`；`SectPolicyState.kt:21`；`GameStateStore.kt:93`；`SectPolicyDomainState.kt:17,41,59` |
| Proto | `OldSerializableSaveData.kt:88 @ProtoNumber(101) autoRecruitSpiritRootFilter` |
| C++ 字段 | `models.h:1328 std::vector<int32_t> autoRecruitSpiritRootFilter`；`:1474 bool autoRecruitIdle`；`:1479 bool autoRejectIdle` |
| C++ 消费 | `recruit_settlement.h:647-722`（`:649` 惰性早退，`:676 / :722` 置位）；`year_settlement.h:1522-1524`；`ai_sect_recruit.h:412-414`；`child_birth.h:220` |
| 设置写入 ActionId | `SETTINGS_PATCH_TX = 1731` → `lock_beast_tx.h:182-183`（写 `autoRecruitSpiritRootFilter`）、`:226 / :270-271`（白名单 + 等值比较） |
| UI 开关 | `RecruitDialog.kt:160-163`（初值）、`:185-190`、`:206-211`；`DiscipleDelegate.kt:257-262` |
| JNI 复位 | `GameCoreBridge.kt:137 nativeResetAutoRecruitIdle` → `GameCoreBridge.cpp:417`；C++ 侧 `game_core.h:129` 注释、`game_core.cpp:409` |

## G05-3 年结招募刷新

| 项 | 落点 |
|---|---|
| Kotlin 年结调用 | `CultivationEventMonthlyOps.kt:130-135 safelyRunInState("refreshRecruitList")`（差值门 `year - lastRecruitYear >= RECRUIT_REFRESH_INTERVAL_YEARS`）；`:145-147 safelyRunInState("recruitAging")` |
| Kotlin 臂 | `RecruitService.kt:395-460 refreshRecruitList`、`:521-529 ageRecruitList`；门面 `CultivationService.kt:170-171` |
| 另两个调用点 | `GameEngineLoadDataOps.kt:408 refreshRecruitList(1)`（**新档初始化内**）；`GameEngineLifecycleOps.kt:219 refreshRecruitList(gd.gameYear)`（读档自愈） |
| C++ 权威链 | `year_settlement.h:1431-1525 processRefreshRecruitList`（`:1437` 差值门、`:1448-1453` 宗门等级区间、`:1473-1476` 数量抽取、`:1484-1488` 广纳门徒 +50%、`:1498-1516` 逐候选生成、`:1520` `lastRecruitYear = year`、`:1522-1524` 惰性复位 + `processAutoRecruit`） |
| 常量 | `year_settlement.h:95 kOpenRecruitmentPoolBonus = 0.50`、`:97 kRecruitRefreshIntervalYears = 3`；Kotlin `GameConfig.kt:778 RECRUIT_MONTHLY_LIMIT = 30` |
| Kotlin 常量 | `CultivationEventProcessor.RECRUIT_REFRESH_INTERVAL_YEARS`（`RecruitService.kt:481` 引用） |
| `lastRecruitYear` 三端 | C++ `models.h`（`json_codec.cpp` GC_TO/GC_FROM）；Kotlin `GameData.kt:277 var lastRecruitYear: Int = 0`、`:996`；Proto `OldSerializableSaveData.kt:46 @ProtoNumber(25)`；Room 列 `GameDatabaseMigrationsV11ToV20.kt:85,329,380,473,530`、`GameDatabaseMigrationSupport.kt:116`；`ExplorationState.kt:17` |
| `RECRUIT_*` 清单文件 | `scripts/action-catalog/core.mjs:229-231`（`RECRUIT_REMOVE_TX 1630` / `RECRUIT_REFRESH_TX 1631` / `RECRUIT_AGE_TX 1632`），**`core.mjs` 为冻结文件**（README §2）→ G05 删条目需找收口人串行修订，或**只删代码保号留洞**（推荐，符合「ActionId 只增不复用」） |

## G05-4 `recruitList` 三端链与读档迁移点

| 端 | 落点（已核实） |
|---|---|
| C++ 字段 | `models.h`（`Disciple` 向量）；`json_codec.cpp` GC_TO/GC_FROM |
| Kotlin 模型 | `GameData.kt:274 var recruitList: List<Disciple>`（`:995` copy 传递）；`ExplorationState.kt:16` |
| ProtoBuf | `OldSerializableSaveData.kt:45 @ProtoNumber(24) recruitList` |
| Room | `GameDatabaseMigrationsV11ToV20.kt:85,329,343,380,473,488,530,557`；`GameDatabaseMigrationSupport.kt:116,138`（`recruitList TEXT NOT NULL`） |
| **重数据分块 key** | `GameHeavyData.kt:52 const val KEY_RECRUIT_LIST = "recruitList"` |
| **写档** | `StorageEngineWriteOps.kt:29`（日志）、`:102`（encode）、`:121 recruitList = emptyList()`（槽位重置） |
| **读档** | `StorageEngineHeavyDataOps.kt:167-169 decodeHeavyDataFromRows`（空值时才解码填充）；`:136` 回退时「无 domain state 表可恢复，保持原值」；`:284` 日志 |
| **回填** | `StorageEngineHeavyDataBackfill.kt:65` 注释「recruitList 无 domain state 表可恢复——缺失时保持原值（不臆造）」 |
| **版本迁移缩放** | `SaveDataVersionMigrator.kt:84 recruitList.map { it.scaleCultivation(scaleFactor) }`（**含测例** `SaveDataVersionMigratorTest.kt:39-93`） |
| **读档清空候选点** | `GameEngineLoadDataOps.kt:211-217`（`stateStore.update { RecruitService.sanitizeRecruitList(this) }`）——G05 把「净化」改成「整表清空」的最自然落点 |

### `RecruitListCleanupRule` 现状（已核实）

| 项 | 值 |
|---|---|
| 定义 | `android/core/data/src/main/java/com/xianxia/sect/data/integrity/rules/RecruitListCleanupRule.kt:19` |
| 接口形态 | `object RecruitListCleanupRule : SaveValidationRule`；`override val id = "recruit_list_cleanup"`（`:20`）；`override val order = 20`（`:21`） |
| 现有语义 | `:23-35`：调 `RecruitIntegrity.sanitizeRecruitList(recruits, sectDisciples)`；`removedCount == 0 → RuleOutcome.Passed`；否则 `RuleOutcome.Repaired(data.copy(gameData = ...copy(recruitList = report.cleaned)), details)` |
| **注册** | `SaveValidationRuleDefaults.kt:30 RecruitListCleanupRule, // order=20`（`registerDefaults()`，`:6`） |
| **执行框架** | `SaveValidator.kt:56 fun validate(saveData): IntegrityResult`；`:77 is RuleOutcome.Repaired ->`；`:110 SaveValidationRuleRegistry.registerDefaults()` |
| 另一执行处 | `integrity/corrupted/CorruptedResultHandler.kt:32 validateRestoredData(slot, restoredData)` |
| 测试 | `RecruitListCleanupRuleTest.kt`（`:41/:58/:73/:90/:122` 断言清空） |
| 依赖的净化实现 | `core/domain/.../RecruitIntegrity.kt:136 sanitizeRecruitList` |
| **「改恒空」需要动** | ① `RecruitListCleanupRule.kt:23-34` 整段 → `RuleOutcome.Repaired(data.copy(gameData = data.gameData.copy(recruitList = emptyList())), listOf("招募链已下线，招募列表清空"))`（恒空语义：**无论是否为空都返回 Repaired**，以触发落盘）② `RecruitListCleanupRuleTest.kt` 全量改断言 ③ `NumericSanitizeRule.kt:51-52,68` 的 `recruitList` 分支（会与恒空规则抢写，需同批处理） |

> ⚠️ **额外牵出（任务书未列）**：`NumericSanitizeRule.kt:51-52` 也写 `recruitList`（`fixedRecruit`），`order=0` 早于 `RecruitListCleanupRule`（order=20）。恒空后此分支成为死代码，必须同批清理，否则「recruitList 生产写入点」grep 不为零。

## G05-5 `annualNewDisciples` 全部写入点（已核实）

| 端 | 落点 | 语义 |
|---|---|---|
| 结构定义 | Kotlin `GameData.kt:801 var annualNewDisciples: Int = 0`；C++ `models.h:1391 int32_t annualNewDisciples = 0`；Proto `OldSerializableSaveData.kt:117 @ProtoNumber(130)` | — |
| 年报读/清零 | C++ `year_settlement.h:183 report.newDisciples = gd.annualNewDisciples`；`:206 gd.annualNewDisciples = 0`；Kotlin `CultivationEventMonthlyOps.kt:230`（写报告）、`:248`（清零） | 保留 |
| C++ 自动招募 | `recruit_settlement.h:719`（`processAutoRecruit`）、`:912`（`manualRecruitAll`） | **删** |
| C++ 单招 | `recruit_settlement.h:829`（`manualRecruitFromList`） | **删** |
| Kotlin 自动招募 | `RecruitService.kt:234`（`settleAutoRecruit`） | **删** |
| Kotlin 一键招 | `GameEngineRecruitOps.kt:107`（legacy 臂） | **删** |
| Kotlin 新档弟子 | `DiscipleService.kt:172`（`recruitDisciple`） | **G08 改道点（必须保留）** |
| Kotlin 手动单招 native 残差 | `DiscipleFacadeImpl功法Ops1.kt:135` | **删** |
| Kotlin 兑换码 | `RedeemCodeService.kt:339`、`:504` | **G08 改道点（必须保留）** |
| 镜像补丁面 | `GameDataFieldPatch.kt:350` | 保留（协议面） |
| **G08 接线点** | — | 「解锁入宗计入」→ 在 G09 解锁分支（C++）新增 `gd.annualNewDisciples += 1`；**这是 G05 预留、G08 定论的唯一新写入点** |

## G05-6 其余删除面落点

| 删除对象 | 落点 |
|---|---|
| 广纳门徒政策（成本/月扣） | C++ `government.h:189-204`（每 3 年扣费 + 写 `openRecruitmentLastPaidMonth`）；`:409 field 映射 `"openRecruitment"`；`:515-545 openRecruitmentToggleTx`；`:536/:541` 置位/清位 |
| 广纳门徒 ActionId | `action_ids.h:448 GOV_OPEN_RECRUITMENT_TOGGLE_TX = 1681`；catalog `core.mjs:261`；dispatch `execute_dispatch.cpp:2572`（`sys::openRecruitmentToggleTx`），区间 `:2752-2754` |
| 广纳门徒 Kotlin | `SectPolicyToggleUseCase.kt:288-325 toggleOpenRecruitment`（native 臂 `:294`）、`:324-325 isOpenRecruitmentEnabled`；VM `ProductionViewModelPolicyGovernanceOps.kt:17-19`、`SectViewModelPolicyGovernanceOps.kt:17-19` |
| 广纳门徒政策字段 | Kotlin `GameDataSectModels.kt:55 @ProtoNumber(29) openRecruitment`；`GameData.kt:488 openRecruitmentLastPaidMonth`；`SectPolicyDomainState.kt:16,40,58`；C++ `models.h:587 bool openRecruitment`、`:1326 openRecruitmentLastPaidMonth` |
| 广纳门徒经济常量 | `GameConfig.kt:839 OPEN_RECRUITMENT_COST = 50000L`、`:840 OPEN_RECRUITMENT_COOLDOWN_MONTHS = 36`、`:852 OPEN_RECRUITMENT_NAME`、`:879 OPEN_RECRUITMENT_POOL_BONUS = 0.50` |
| 广纳门徒另一处月结 | `CultivationSettlement.kt:292-300`（Kotlin 月结回退臂） |
| 天书「招募弟子数上限+50%」 | `TianshuHallDialog.kt:784-790`（`PolicyItem(title="广纳门徒", effect="招募弟子数上限+50%", cost="5万灵石/3年", checked=sectPolicies?.openRecruitment, onCheckedChange={ productionViewModel.toggleOpenRecruitment() })`）；`:57 import toggleOpenRecruitment` |
| 招贤（词条） | `AffixDatabase.kt:327 Triple("recruiting","招贤",ElderSlotType.RECRUITING) to "招募弟子数上限加成"`；C++ `data/trait_db.h:218 {"recruiting","招贤","RECRUITING","招募弟子数上限加成"}`；样本 `test/resources/templates/trait_db_sample.json:2640,2654,2668` |
| 招贤伯乐（天赋） | `TalentDatabase.kt:56 POSITION_RECRUITING`、`:448 Triple("recruiting","招贤伯乐",…)`、`:460`；C++ `data/trait_db.h:197 {"recruiting","招贤伯乐","RECRUITING",…,"POSITION_RECRUITING"}`；样本 `trait_db_sample.json:1306,1320,1334` |
| 招贤伯乐职务加成乘区 | `year_settlement.h:1456-1472`（`stats::positionEffectBonus(ds,row,"RECRUITING")`）；`RecruitService.kt:374-385` |
| `ai_sect_recruit.h:409-412` 占领后自动招募钩子 | **已核实**：`:389-390 generateYearlyAiRecruits`（保留=AI 宗周期性招募）；`:391-393` 玩家占领分支（并入 `gd.recruitList`）；`:394-399` 被占领 AI 宗分支；`:400-404` 常规分支（保留）；`:407 gd.recruitList = std::move(...)`；`:408 gd.lastAiSectRecruitYear = year`（保留）；**`:409-414` 是任务书所指「占领后自动招募钩子」**（惰性复位 + `recruit_settle::processAutoRecruit`） |
| 招募按钮占位改造点 | `GameActionButtons.kt:102-105`（唯一按钮落点）；备选 `NavigationDelegate.kt:84-86` + `GameRoute.kt:63`；精灵 `ui_recruit_button`（`resource-registry.json:270`、`sprite-uid-map.json:430`、`ui_recruit_button.webp` 双模块） |
| 招募相关测试（旧用例处置表用） | Kotlin：`RecruitServiceTest.kt`、`RecruitNativeTxGateTest.kt`、`GameEngineRecruitTest.kt`、`RecruitAllEnvelopeTest.kt`、`ManualRecruitEnvelopeTest.kt`、`DiscipleFacadeImplRecruitTest.kt`、`DiscipleDelegateRecruitGuardTest.kt`、`RecruitListCleanupRuleTest.kt`、`RecruitIntegrityTest.kt`、`RecruitDedupeEquivalenceTest.kt`、`DiscipleTablesRecruitTest.kt`、`MerchantAndRecruitServiceTest.kt`、`DiffMonthSettlementTest.kt`、`DiffYearSettlementTest.kt`、`DiffAuthoritativeTickTest.kt`、`DiffProductionSettlementTest.kt`、`DiffMissionSettlementTest.kt`、`GameEngineCoordinationTest.kt`、`GameEngineDiscipleOpsNativeTxGateTest.kt`、`GameEngineResidualNativeTxGateTest.kt`、`DialogTypeRenderCoverageTest.kt`、`GameViewModelTest.kt:568-572`。C++：`manual_recruit_test.cpp`、`recruit_tx_test.cpp`、`month_settlement_test.cpp:1960-2100`、`year_settlement_test.cpp:971+` |
| 招募域其它 Kotlin 源 | `RecruitAllEnvelope.kt`、`ManualRecruitEnvelope.kt`、`CultivationServiceRecruitOps.kt`、`RecruitIntegrity.kt`（`core/domain`）、`RecruitDialog.kt` |

---

# G06 · 删逐出弟子 + 弟子改名

## G06-1 `expelDisciple` 完整调用图

| 层 | 落点（已核实） |
|---|---|
| **UI 入口 1（详情面板）** | `DetailRightPanel.kt:244-248 DetailActionButton(text="驱逐", onClick={dismissDropdown(); actions.onShowExpelConfirm()})`；回调声明 `:304 val onRenameDisciple: (() -> Unit)?`（`:76 onNameClick`） |
| **UI 状态** | `DiscipleDetailScreen.kt:115 var showExpelConfirmDialog`、`:130 var showRenameDialog`、`:162 onShowExpelConfirm`、`:165 onRenameDisciple`、`:300`（覆盖层必须渲染在根 Box 内） |
| **UI 确认框** | `DiscipleDetailScreen.kt:648-656 StandardPromptDialog(title="确认驱逐", text="确定要驱逐弟子 …吗？此操作不可撤销。", onConfirm={ viewModel?.disciple?.expelDisciple(disciple.id); … })` |
| **UI 入口 2（思过崖）** | `ReflectionCliffDialog.kt:37 onExpelDisciple: (String) -> Unit`、`:41 showExpelConfirmDialog`、`:51 onExpel`、`:55-62`、`:172 Text("驱逐")`、`:182-197`（确认弹窗） |
| **UI 接线 2** | `DialogFunctionalBuildingRoutes.kt:168 onExpelDisciple = { discipleId -> viewModel.disciple.expelDisciple(discipleId) }` |
| **Delegate** | `DiscipleDelegate.kt:50-52 expelDisciple(id) { gameEngine.launchOnEngine { gameEngine.expelDisciple(id) } }`；`:10 import` |
| **Engine 扩展** | `GameEngineDiscipleOps.kt:33 suspend fun GameEngine.expelDisciple(id): DomainResult<Unit> = discipleFacade.expelDisciple(id)` |
| **Facade 接口** | `DiscipleFacade.kt:31 fun expelDisciple(discipleId: String): DomainResult<Unit>` |
| **Facade 实现** | `DiscipleFacadeImpl.kt:112-118`（`:116 tryNativeExpelDisciple(id)?.let{return it}`；`:117 return discipleService.expelDisciple(id)`）；另 `:191 expelDisciple(discipleId)`（批量/别的入口，**待复核**） |
| **UseCase（app 层）** | `android/app/src/main/java/com/xianxia/sect/core/usecase/ExpelDiscipleUseCase.kt:9-13`（`invoke` → `discipleFacade.expelDisciple`） |
| **native 臂** | `DiscipleLifecycleNativeTx.kt:81-109 tryNativeExpelDisciple`（`:82 lifecycleTx(ActionIds.DISCIPLE_LIFECYCLE_EXPEL)`；`:88 clearDiscipleFromAllSlots`；`:94-101 parseBagItemDrafts` + `withTrackingSource("disciple_expel")` + `materializeBagItemsToWarehouse`；`:105 gameEngineCore.rebaselineNativeMirror("逐出袋物化")`；`:24 EXPEL_TRACKING_SOURCE = "disciple_expel"`） |
| **Kotlin 回退臂** | `DiscipleService.kt:183-240 expelDisciple`（校验链 `:186-201`；写段 `:203-234`；`:209 withTrackingSource("disciple_expel")`；`:233 annualDesertedDisciples + 1`） |
| **C++ 事务** | `disciple_lifecycle_tx.h:216-273 expelTransaction`（`:225` 签名；`:229-250` 校验链 NotFound/NotAlive/SlotInvalid；`:254` bagItems 捕获；`:257 clearAllDiscipleSlotsForRemoval`；`:260 destroyWornInstances`；`:263 eraseDiscipleDerivedMaps`；`:266 ds.removeById`；**`:269 state.gameData.annualDesertedDisciples += 1`**） |
| **ActionId** | `DISCIPLE_LIFECYCLE_EXPEL = 1590`（`action_ids.h`，`kAllActionIds` 含 1590–1594）；Kotlin `ActionIds.DISCIPLE_LIFECYCLE_EXPEL` |
| **dispatch** | `execute_dispatch.cpp:2734-2736`（区间 `DISCIPLE_LIFECYCLE_EXPEL..DISCIPLE_LIFECYCLE_SALARY_TOGGLE` → `handleDiscipleLifecycleTx`） |

## G06-2 `annualDesertedDisciples` 现有写入点（区分「玩家逐出」vs「执法/叛逃」）

| # | 落点 | 语义 | G06 处置 |
|---|---|---|---|
| 1 | `disciple_lifecycle_tx.h:269` | **玩家逐出**（C++ 权威） | **删** |
| 2 | `DiscipleService.kt:232-234`（`:233` 为 `+1`） | **玩家逐出**（Kotlin 回退臂） | **删** |
| 3 | `month_settlement.h:1322`（函数 `processLawEnforcementMonthly` 内的叛逃移除链，函数域 `:1295-1332`；注释「叛逃带走装备」） | **执法/叛逃**（非玩家逐出） | **保留** |
| 4 | `LawEnforcementTheftTxOps.kt:339` | 偷盗/执法 | **保留** |
| 5 | `LawEnforcementTheftTxOps.kt:443` | 同上（回退臂） | **保留** |
| 6 | `LawEnforcementTheftTxOps.kt:469` | 同上 | **保留** |
| 结构定义 | Kotlin `GameData.kt:811`；C++ `models.h:1393`；Proto `OldSerializableSaveData.kt:119 @ProtoNumber(132)` | — | 保留（执法仍在用） |

> 任务书「年报 `annualDesertedDisciples` 玩家逐出计数」= **仅 #1 / #2 两处**。#3–#6 是执法叛逃，**不得动**。

## G06-3 `renameDisciple` 完整调用图

| 层 | 落点（已核实） |
|---|---|
| **UI** | `DiscipleDetailScreen.kt:526-535`（`if (state.showRenameDialog) RenameDiscipleDialog(currentName, onConfirm={ viewModel?.disciple?.renameDisciple(disciple.id, newName) }, …)`）；触发 `:165 onRenameDisciple = { showRenameDialog = true }`；`DetailRightPanel.kt:76 onNameClick = actions.onRenameDisciple?.let { … }`（**改名是点头像/姓名，不是独立按钮**） |
| **共享弹窗** | `dialogs/shared/RenameDialog.kt:12`（类注释「RenameSectDialog/RenameDiscipleDialog 同构合并」）；`:57`（宗门改名弹窗）；`:79-99`（`RenameDiscipleDialog` + 配置） |
| **Delegate** | `DiscipleDelegate.kt:106-117 renameDisciple(id,newName)`；`:16 import` |
| **Engine 扩展** | `GameEngineCoordination.kt:173-197 suspend fun GameEngine.renameDisciple`（`:176 tryDiscipleOpNative(ActionIds.DISCIPLE_OP_RENAME)`；`:182-196 Kotlin 回退臂`含 `RecruitIntegrity.isSamePerson` 净化 `:191-194`） |
| **native 转发** | `GameEngineCoordination.kt:176` → `tryDiscipleOpNative`（同文件/附近 helper，ActionIds.DISCIPLE_OP_RENAME） |
| **C++ 事务** | `disciple_tx.h:1283-1313 renameDiscipleTx`（`:1300 ds.names[row] = newName`；`:1301-1310` recruitList 同人净化，**依赖 `recruit_settle::isSamePerson`** `:1306`） |
| **dispatch** | `dispatch_w4a.cpp:62-68 case action::DISCIPLE_OP_RENAME`（`:63 disciple_tx::renameDiscipleTx`，`:67 return ok({{"renamed",true}})`） |
| **ActionId** | `DISCIPLE_OP_RENAME = 1740`（C++ `action_ids.h:520`；Kotlin `ActionIds.kt:516`） |
| **目录文件** | ⚠️ **不在 `scripts/action-catalog/core.mjs`**。`DISCIPLE_OP_*` 全族（1740–1748）由 **W4-A 段**拥有：`scripts/action-catalog/w4a.mjs`（段 1740–1749）。已核实 `core.mjs` grep `DISCIPLE_OP` 零命中 |
| **同族兄弟（勿误删）** | `disciple_tx.h:1315-1333 changeDiscipleTypeTx(1741)`、`:1335+ toggleFollowTx(1742)`、`rewardItemTx(1743)`、`usePill(1744)`、`replaceManual(1745)`、`startBloodRefinement(1746)`、`syncStatus(1747)`、`syncAllStatuses(1748)`；`dispatch_w4a.cpp:62-140` 整段 |

## G06-4 宗门改名 `renameSect`（**保留**）与「是否共享代码」判定

| 层 | 落点（已核实） |
|---|---|
| UI 状态路由 | `DialogSystemRoutes.kt:46 { newName: String -> viewModel.sectDelegate.renameSect(newName) }` |
| Delegate | `SectDelegate.kt:31-36 renameSect(newName)`；`:8 import` |
| Engine 扩展 | `GameEngineLifecycleOps.kt:269-274`（注释「宗门改名（UI 入口 SectDelegate.renameSect 迁入引擎层——w3-13 通道关闭配套）」；`:274 suspend fun GameEngine.renameSect(newName: String)`） |
| 弹窗 | `RenameDialog.kt:57 RenameSectDialog` |
| 其他 | `MainGameScreen.kt:1381`（仅主宗门可改名的门槛） |

**共享性判定（已核实，结论：不共享执行路径）**

| 维度 | 弟子改名 | 宗门改名 | 共享？ |
|---|---|---|---|
| C++ 事务 | `disciple_tx.h renameDiscipleTx`（1740，`dispatch_w4a.cpp:62`） | **无 C++ 事务**（`GameEngineLifecycleOps.kt:274` 纯 Kotlin `stateStore` 写） | ❌ |
| Facade | 无（走 `GameEngine` 扩展 + `tryDiscipleOpNative`） | 无 | ❌ |
| Delegate | `DiscipleDelegate.kt:106` | `SectDelegate.kt:31` | ❌ |
| UseCase | 无 | 无 | ❌ |
| **共享点（唯一）** | `RenameDialog.kt` 的 **UI 容器 + 校验器配置**（`:99 改名弹窗配置（宗门/弟子共用，差异：标题/占位符/长度/校验器）`） | 同文件 `:57` | ✅ **仅 UI 弹窗壳** |

> ⇒ G06 删 `renameDisciple` 时**只删 `RenameDiscipleDialog`（`:79-99` 与其配置项）**，保留 `RenameDialog` 通用容器与 `RenameSectDialog`。测试 `GameEngineSectIdentityOpsTest.kt:90-102` 必须保持绿。

## G06-5 没收 / 执法 / 仓库存取（保留）与逐出的耦合点

| 功能 | 入口落点 | 与 expel 的耦合 |
|---|---|---|
| 没收储物袋 | `DiscipleDelegate.kt:80-90 confiscateStorageBagItem` → `InventoryFacadeImpl.kt:151 withTrackingSource("confiscate")`；来源名 `OverflowMailSender.kt:117 "confiscate" to "储物袋回收"` | 仅**同属** `DiscipleDelegate` 类（`:80` vs `:50`）；不共享事务 |
| 执法/叛逃 | `LawEnforcementProcessor.kt`、`LawEnforcementTheftTxOps.kt:339,443,469`；C++ `month_settlement.h:1327+ processLawEnforcementMonthly` | **共享** `annualDesertedDisciples` 字段（见 G06-2）与 `eraseDiscipleDerivedMaps`（`month_settlement.h:1319` vs `disciple_lifecycle_tx.h:263`）——**唯一实质交叉** |
| 仓库存取 | `InventoryFacadeImpl.kt:722 withTrackingSource("storage_bag")`；`storage_bag_tx.h` | 与 expel 共享 `materializeBagItemsToWarehouse`（`DiscipleLifecycleNativeTx.kt:99` / `DiscipleService.kt:210`）——**保留该函数，只删调用点** |
| 驻守/生产槽位清理 | `disciple_lifecycle_tx.h:257 detail::clearAllDiscipleSlotsForRemoval`、`slot_cleanup.h:78` | 共享 `slot_cleanup.h`（**保留**） |

## G06-6 测试清单

| 测试 | 处置 |
|---|---|
| `DiscipleServiceCrudTest.kt:212-244`（expel 三分支 + 计数断言） | **删** |
| `DiscipleLifecycleNativeTxGateTest.kt:125-153`（`tryNativeExpelDisciple`） | **删** |
| `disciple_lifecycle_tx_test.cpp:279,313`（`annualDesertedDisciples`） | **删逐出部分**（拜师/婚姻/思过/年俸保留） |
| `UseCaseInvocationTest.kt:77-98`（ExpelDiscipleUseCase） | **删** |
| `GameEngineRenameTest.kt`（5 用例，`:54-137`） | **删** |
| `disciple_ops_tx_test.cpp:151-165,513`（RENAME） | **删** |
| `GameEngineDiscipleOpsNativeTxGateTest.kt:124-146`（rename 降级） | **删** |
| `GameEngineSectIdentityOpsTest.kt:90-102`（renameSect） | **保留（回归门）** |
| `GameViewModelTest.kt:750-798`（renameSect 三例） | **保留** |
| 思过崖驱逐相关（如 `ReflectionCliff*Test`） | 需 glob 复核（**推测**存在） |

---

# G08 · 角色模板层 + 开局 + 兑换码改道

## G08-1 现有**全部**弟子构造路径（穷举）

| # | 路径 | 落点（文件:行 + 函数） | 写 portraitRes | 写 name | 写 spiritRoots | 备注 |
|---|---|---|---|---|---|---|
| 1 | **C++ 主工厂** | `disciple_factory.h:411-480 createDisciple(const DiscipleCreationSeed&, rng)`；种子定义 `:399-408` | ✅ `:455-457`（`malePortraits()/femalePortraits()` + `rng.nextInt(size)`） | ❌（由 seed 传入） | ❌（由 seed 传入） | **单点收敛目标**；入口唯一 |
| 2 | C++ 招募年结刷新 | `year_settlement.h:1498-1516`（`:1504 DiscipleCreationSeed seed;` `:1513 createDisciple(seed, rngSystem)`） | 经 #1 | 经 #1 | 经 #1 | **G05 删** |
| 3 | C++ 生育 | `child_birth.h:126-135`（`:127 DiscipleCreationSeed seed;` `:135 createDisciple(seed, rng)`；头注释 `:16` 列固定消费序） | 经 #1 | 经 #1 | 经 #1 | **G03 删** |
| 4 | C++ AI 宗弟子 | `ai_sect_recruit.h:116-174 generateRandomAiDisciple`（**不走** `createDisciple`！自建字段：`:157-158 d.portraitRes = portraits[rng.nextInt(size)]`） | ✅ `:157-158` | ✅ `:122-124` | ✅ `:126` | **旁路工厂**，G08「收敛单点」必须处理 |
| 5 | C++ 招募落库（入宗） | `recruit_settlement.h:388-408 allocateAndInsert` / `:780 manualRecruitFromList` / `:837 manualRecruitAll` | ❌（搬运已有 Disciple） | ❌ | ❌ | **G05 删** |
| 6 | C++ 俘虏装备物化 | `recruit_settlement.h:560 materializeCaptiveGear` | ❌ | ❌ | ❌ | 只造 Equipment/Manual 实例 |
| 7 | **C++ 对拍探针** | `determinism_probe.h:176-185`（`DiscipleCreationSeed seed; createDisciple(seed, systemRng)`） | 经 #1 | 经 #1 | 经 #1 | 测试用，非生产 |
| 8 | **C++ JNI 直造** | `jni/GameCoreJni.cpp:219-242`（`:226 DiscipleCreationSeed seed;` `:240 createDisciple(seed, *g_rng)` `:242 out["portraitRes"] = d.portraitRes`） | 经 #1 | 经 #1 | 经 #1 | 对拍专用导出 |
| 9 | **Kotlin 工厂** | `DiscipleFactory.kt:117-174 create(seed: DiscipleSeed)`（seed 定义 `:101-114`）；`:141-143 portraitRes = PortraitPool.getRandomPortrait(seed.gender){ r(0,bound) }`；`:129-134` 天赋/体质/词条三分类 | ✅ `:141-143` | ❌ | ❌ | **Kotlin 臂单点**；`:112` 注释强制分区 PRNG |
| 10 | Kotlin 新档初始 3 弟子 | `GameEngineLoadDataOps.kt:306 repeat(3) { discipleService.recruitDisciple(realm = 9) }` | 经 #11→#9 | 经 #11 | 经 #11 | **G08 主改点：改为实例化周明** |
| 11 | Kotlin `recruitDisciple` | `DiscipleService.kt:125-178`（`:136-138 NameService.generateName`；`:140-153 discipleFactory.create(...)`；`:161-172 allocateAndInsert + lifeEvents + guideCounters + **`annualNewDisciples + 1`** `:172`） | 经 #9 | ✅ `:136` | ✅ `:145 SpiritRootGenerator.generate` | 新档路径 #10 的唯一实现 |
| 12 | Kotlin 招募列表刷新 | `RecruitService.kt:423-446`（`:430 discipleFactory.create(DiscipleFactory.DiscipleSeed(...))`） | 经 #9 | 经 #9 | ✅ `:435 SpiritRootGenerator.generate` | **G05 删** |
| 13 | Kotlin 手动招募 | `DiscipleFacadeImpl功法Ops1.kt:45` → C++ `manualRecruitFromList`；`:135 annualNewDisciples + 1` | #5 | #5 | #5 | **G05 删** |
| 14 | **兑换码（弟子奖励）** | `RedeemCodeRewardOps.kt:88-114 addDiscipleRewards` → `:100 generateDisciple(...)` | 经 `buildRedeemDisciple` | ✅ | ✅ | **G08 改道：改发碎片** |
| 15 | **兑换码（主体构建）** | `RedeemCodeRewardOps.kt:229-279 buildRedeemDisciple`（**`:249 portraitRes = PortraitPool.getRandomPortrait(context.gender){ random.nextInt(it) }`**；`:253-279` 写 combat/skills） | ✅ `:249` | ✅ `:241-242` | ✅ `:245` | 直造弟子的旁路，**必须收敛或删除** |
| 16 | 兑换码新手包 | `RedeemCodeRewardOps.kt:118-157 addStarterPackRewards`（`:135 repeat(5) generateDisciple(...)`） | 经 #15 | 经 #15 | 经 #15 | 同上 |
| 17 | 兑换码技能/资质 | `RedeemCodeRewardOps.kt:283-324 buildRedeemSkills` / `rollBySpiritRootCount`、`:328-352 resolveSpiritRoot`、`:356-373 resolveAgeAndLifespan`、`:377-384 resolveTalentIds`、`:388 generateVariance`、`:392 avoidSentinel50` | 间接 | 间接 | ✅ `:328-352` | 同上 |
| 18 | Kotlin 兑换码服务 | `RedeemCodeService.kt:339 annualNewDisciples + quantity`、`:504 annualNewDisciples + result.disciples.size` | 经 #15 | 经 #15 | 经 #15 | **G08 改道点** |
| 19 | 探索随机人类敌人 | ❌ **不造 Disciple**（造 `Combatant`）：`EnemyGenerator.kt:24-74`；C++ `mission_completion.h:624-766 generateHumanEnemies`（`:766 enemy.id = "human_enemy_N"`） | 不适用 | 不适用 | 不适用 | **无 G08 影响** |
| 20 | 世界生成 AI 宗弟子 | `GameEngineLoadDataOps.kt:404 WorldMapGenerator.generateWorldSects` → `:424 aiSectDisciples`（C++ 侧 `ai_sect_recruit.h` 是 #4） | 间接 | 间接 | 间接 | Kotlin 侧 `AISectDiscipleManager` **未核实** |
| 21 | 测试夹具 | `disciple_factory_test.cpp`、`DiscipleTablesSelfHealTest.kt:172`、`SaveDataDirectSerializationTest.kt:91` 等 | — | — | — | 非生产；G08 需评估 |

> **portraitRes 现有 roll 落点合计 4 处**：`disciple_factory.h:455-457`、`ai_sect_recruit.h:157-158`、`DiscipleFactory.kt:141-143`、`RedeemCodeRewardOps.kt:249`。G08「模板强制覆盖」必须覆盖全部 4 处。

## G08-2 新档初始化灵石与初始名册的确切落点

| 项 | 落点 | 现状 |
|---|---|---|
| 新档入口（UI→VM） | `MainActivity.kt:708-711`（`newGame=true`）→ `GameActivity.kt:799-801 SaveLoadViewModel.startNewGame(sectName, slot)` | 已核实 |
| VM | `SaveLoadViewModel.kt:294-336 startNewGame` → `SaveLoadViewModelNewGameOps.kt:19-70 performStartNewGame`（`:29 gameEngine.createNewGame(sectName, slot)`；`:42 performInitialSaveForNewGame`；`:48 performNewGameBoot`） | 已核实 |
| **引擎新档** | `GameEngineLoadDataOps.kt:253-323 createNewGame`（`:258 resetForSlot`；`:265 EngineEntropy.nextWorldSeed()`；`:269 gameRngManager.initSystemSeed`；`:271 initializeWorldAndServices`；`:274-307 stateStore.update{…}`；**`:306 repeat(3) { discipleService.recruitDisciple(realm = 9) }`**；`:308 addInitialStorageBags()`；`:313 mailService.resetAndInitSlot`；`:321 syncNativeBaselineAfterLoad()`） | **初始名册 = 3 名随机弟子，逐字段随机** |
| 世界/服务 | `GameEngineLoadDataOps.kt:402-430 initializeWorldAndServices`（`:408 cultivationService.refreshRecruitList(1)` ← **G05 删**；`:413-416 AI 宗立绘分配`） |
| 重启同构臂 | `GameEngineLoadDataOps.kt:329-399 restartGameInternal`（`:374 repeat(3) { discipleService.recruitDisciple(realm = 9) }`） | **必须同改** |
| 灵石默认值 | `GameData.kt:141-143 @ColumnInfo(name="spiritStones") var spiritStones: Long = 1000` | **实际新档 = 1000**，与 G08 要求的 50000 差 49 倍 |
| 仓库层初始化 | `GameDataRepositoryImpl.kt:26-30 initializeNewGame() { val gameData = GameData(id="game_data_0"); insert; return }` | 未设灵石 |
| 常量（已落未用） | `GameConfig.kt` `object Gacha { … START_SPIRIT_STONES = 50000 … }`（G07 后 `:217`） | **零引用** |
| 配置（已落未读） | `game-data.json → gachaDefaults.startSpiritStones = 50000`、`startupTemplateId = "zhouming"`、`startBonusFragments = 0`、`historyRingSize=50`、`starBattlePctPerStar=0.08`、`starCultPctPerStar=0.05`、`injuryHealPctPerPhase=0.2`、`breakthroughCompBonus=0.02` | 无读取代码 |
| 其它初始化入口 | `GameEngineLifecycleOps.kt:19 initializeNewGameSuspend(gameData)`；`BootSequenceController.kt:33,69`（注释） | 需复核是否生产路径 |

## G08-3 `characterTemplates` 在 `game-data.json` 的实际结构与读取现状

**产物位置**：`android/app/src/main/assets/data/game-data.json`（1,132,046 字节，**单行 minified**）
**顶层键**：`db, gachaColors, gachaDefaults, generatedBy, note, schemaVersion`
**`db` 键**：`affixes, beastMaterials, characterTemplates, equipment, forgeRecipes, gachaPools, herbs, manuals, physiques, pillRecipes, seeds, talents`

```json
// db.characterTemplates —— 原样（字段序按 JSON 实际）
[
  { "avatarKey":"avatar_zhouming", "gender":"M", "id":"zhouming", "name":"周明",
    "portraitKey":"portrait_zhouming", "spiritRoots":["metal"] },
  { "avatarKey":"avatar_suqing",   "gender":"F", "id":"suqing",   "name":"苏晴",
    "portraitKey":"portrait_suqing",   "spiritRoots":["water"] },
  { "avatarKey":"avatar_linxuetang","gender":"F","id":"linxuetang","name":"林雪棠",
    "portraitKey":"portrait_linxuetang","spiritRoots":["wood","water"] },
  { "avatarKey":"avatar_xuhe",     "gender":"F", "id":"xuhe",     "name":"许荷",
    "portraitKey":"portrait_xuhe",     "spiritRoots":["wood","earth"] },
  { "avatarKey":"avatar_xieche",   "gender":"M", "id":"xieche",   "name":"谢澈",
    "portraitKey":"portrait_xieche",   "spiritRoots":["metal","water"] },
  { "avatarKey":"avatar_zhaoyan",  "gender":"M", "id":"zhaoyan",  "name":"赵言",
    "portraitKey":"portrait_zhaoyan",  "spiritRoots":["fire","earth"] }
]
```

```json
// db.gachaPools[0]（poolId=standard）
{
  "categories":[
    {"kind":"character_single","weightPct":11,"templateIds":["zhouming","suqing"]},
    {"kind":"character_double","weightPct":10,"templateIds":["linxuetang","xuhe","xieche","zhaoyan"]},
    {"kind":"beast_material","weightPct":26,"itemSource":"beastMaterials","maxRarity":4},
    {"kind":"herb","weightPct":26,"itemSource":"herbs","maxRarity":4},
    {"kind":"seed","weightPct":27,"itemSource":"seeds","maxRarity":4}
  ],
  "enabled":true, "fragmentsPerStar":100, "maxStar":5,
  "itemRarityWeights":[{"rarity":4,"weightPct":12},{"rarity":3,"weightPct":33},
                       {"rarity":2,"weightPct":33},{"rarity":1,"weightPct":22}],
  "pity":{"fragmentCount":5,"pickMode":"random","pullThreshold":10},
  "poolId":"standard", "pricePerPull":5000
}
```

**读取现状（已核实，逐一 grep）**

| 读者 | 位置 | 性质 |
|---|---|---|
| 守卫测试 | `GachaConfigGuardTest.kt:45-46 loadRoot()`（`gameDataPath().readText()` → `root.getValue("db").jsonObject`）、`:56 gachaPools`、`:95 characterTemplates` | **测试直读文件**，非生产 |
| 生成器 | `scripts/gen-game-data.mjs:129-135 loadGacha()`（读 `gacha_config_sample.json`）、`:169-170` 写入 `db.gachaPools/characterTemplates`、`:172-173` 写 `gachaDefaults/gachaColors` | 构建期 |
| 中立源 | `scripts/data/gacha_config_sample.json`（130 行，来源） | 配置 |
| **Kotlin 生产读取** | **无**。`GameConfigData.kt` grep `gacha` 零命中；`ConfigLoader.kt:45,92-100` 只反序列化 `GameConfigData` | ❌ 缺失 |
| **C++ 生产读取** | **无**。`gamecore/data/` 无 character_templates；`data::` 命名空间只有 talent/physique/affix 模板 | ❌ 缺失 |

> ⇒ **G08 首要工作 = 新建读取层**（Kotlin Registry 或 C++ `data/character_template_db.h` + codegen），当前只有配置源与守卫。

## G08-4 `DiscipleCreationSeed` 定义与构造点

| 端 | 定义 | 构造点 |
|---|---|---|
| C++ | `disciple_factory.h:399-408`（`std::string id / gender / fullName / surname / spiritRootType; int32_t age=16, realm=9, realmLayer=1`） | `year_settlement.h:1504-1512`、`child_birth.h:127-134`、`determinism_probe.h:176`、`GameCoreJni.cpp:226` |
| Kotlin 对应 | `DiscipleFactory.kt:101-114 data class DiscipleSeed(id, gender, nameResult, spiritRootType, age, realm=9, realmLayer, social, nextInt, random)` | `DiscipleService.kt:141-152`、`RecruitService.kt:431-442`（G05 删） |

> G08 需在**两侧**加 `templateId`（C++ seed + Kotlin seed），并把 `portraitRes` 从 seed/工厂 roll 改为模板强制。

## G08-5 弟子实例 `templateId` 三端链（G01 已落，现无写入者）

| 端 | 落点 |
|---|---|
| C++ 结构体字段 | `models.h`（`Disciple` 内）——⚠️ **行号待复核** |
| C++ 列式存储 | `disciple_store.h`（`templateIds` 列，与 `portraitRes` 同族）——⚠️ **待复核**；`column_dirty.h` 的 `DiscipleColumn` 枚举（`PortraitRes` 在 `:97,224`，`templateId` **待复核**） |
| Kotlin 实体 | `Disciple.kt:91-92 @ColumnInfo(name="templateId", defaultValue="") var templateId: String = ""` |
| Kotlin 序列化 | `DiscipleSerializer.kt:59`（写）、`:209`（读）、`:365 @ProtoNumber(111) templateId` |
| Kotlin 列式表 | `DiscipleTables.kt:109 val templateIds = ComponentTable<String>() // id → 角色模板 id（空=存量旧弟子）`——⚠️ **G07 后行号待复核** |
| 列注册 | `DiscipleTablesColumnRegistry.kt:98 RefTableRef(templateIds, DiscipleTables::templateIds, "templateIds")` |
| 装配 | `DiscipleTablesAssemblers.kt:70 templateId = templateIds.getOrDefault(id, "")` |
| 写表 | `DiscipleTablesWrite.kt:87 templateIds[id] = disciple.templateId` |
| GameView 镜像 | `GameViewDiscipleRows.kt:167 "templateId" to DiscipleRow::hasTemplateId`、`:201`、`:532`、`:771` |
| 镜像编解码 | `GameViewMirrorCodec.kt:445 str("templateId", { it.hasTemplateId() }, { it.templateId })` |
| Room migration | `GameDatabaseMigrationsV54.kt:14-21`（`ALTER TABLE disciples ADD COLUMN templateId TEXT NOT NULL DEFAULT ''`）；`GameDatabase.kt:94 DATABASE_VERSION = 54` |
| 测试 | `RoomMigrationV53To54Test.kt:79,93-95`；`DiscipleMergeCoverageTest.kt:63`；`DiffDiscipleFactoryTest.kt:52`；`SaveDataDirectSerializationTest.kt:66,82` |
| **写入者** | **零**（无生产代码写 `templateId`）——G08 新落点 |

## G08-6 相关测试

`disciple_factory_test.cpp`（含 `:47,92,132-134` portraitRes 黄金断言——**模板覆盖后必改**）、`DiffDiscipleFactoryTest.kt:52`、`child_birth_test.cpp:85`（`EXPECT_EQ("male_disciple_1", child.portraitRes)`）、`RoomMigrationV53To54Test.kt`、`SaveDataDirectSerializationTest.kt:45-82`、`RedeemCodeServiceTest.kt:111`、`RedeemCodeManagerTest.kt`、`DiffRedeemCodeTest.kt`、`GameEngineCoordinationTest.kt:138,173,278`（createNewGame）、`InitialMineSizeGuardTest.kt:31`。

---

# G09 · 抽卡核心

## G09-1 G01 骨架现有内容（全文）

**`GachaFacade.kt`**（40 行，`android/core/engine/.../domain/gacha/GachaFacade.kt`）
```kotlin
interface GachaFacade {
    val pityCounters: StateFlow<Map<String, Int>>      // :15 poolId → 已抽次数
    val fragmentCounts: StateFlow<Map<String, Int>>    // :18 templateId → x
    val starMap: StateFlow<Map<String, Int>>           // :21 templateId → star
    val history: StateFlow<List<GachaHistoryEntry>>    // :24
    suspend fun pullOnce(poolId: String = "standard"): GachaPullResult   // :30
    suspend fun pullTen(poolId: String = "standard"): GachaPullResult    // :33
}
sealed interface GachaPullResult {                     // :37
    data object NotReady : GachaPullResult             // :38
    data class Failure(val reason: String) : GachaPullResult  // :39
}
```
**`GachaService.kt`**（39 行）：`@Singleton class GachaService @Inject constructor(stateStore, scopeProvider)`；`:24-38` 四个 `stateStore.gameData.map{…}.stateIn(scope, SharingStarted.Eagerly, …)`。**不写 Store**。
**`GachaFacadeImpl.kt`**（28 行）：`:14-27`；`pullOnce/pullTen` 恒返回 `GachaPullResult.NotReady`。**无 ActionId 调用、无 GameEngine 依赖**。
**`GachaDelegate.kt`**（21 行，**G07 后已改**）：`class GachaDelegate(private val gachaFacade: GachaFacade)`（`:12-14`）；`:16-20` 两个 suspend 转发。**构造参数 `gameEngine` 已移除**（原双参签名失效）。

> ⚠️ **G01 骨架的三个缺口**（G09 必补）：① 无 `GachaPullResult.Success` DTO（10 个结果项无处承载）；② `GachaFacadeImpl` 无 `gameEngineCore`/`StateSyncService`，无法走 `tryExecuteNative`；③ `GachaDelegate` 未接入 `GameViewModel`（无 `viewModel.gacha` 字段，`GachaDelegate` 全仓**零实例化**）。
> DI 注册已做：`CoreModule.kt:151-152 provideGachaFacade(impl: GachaFacadeImpl): GachaFacade = impl`。

## G09-2 GameData gacha 字段实际签名

| 字段 | Kotlin（`GameData.kt`） | C++（`models.h`） | 序列化 | Room |
|---|---|---|---|---|
| `gachaFragmentCounts` | `:900-903 @ProtoNumber(164) @ColumnInfo("gacha_fragment_counts", defaultValue="{}") @SettlementStrategy(PRESERVE_OLD) var gachaFragmentCounts: Map<String,Int> = emptyMap()` | `:1400 std::map<std::string,int32_t> gachaFragmentCounts` | `json_codec.cpp:1327 GC_TO` / `:1418 GC_FROM` | `GameDatabaseMigrationsV54.kt:24` |
| `gachaStarMap` | `:906-909 @ProtoNumber(165) "gacha_star_map"` | `:1401` | `:1327 / :1418` | `:27` |
| `gachaPityCounters` | `:912-915 @ProtoNumber(166) "gacha_pity_counters"` | `:1402` | `:1328 / :1419` | `:30` |
| `gachaHistory` | `:918-921 @ProtoNumber(167) "gacha_history" defaultValue="[]" List<GachaHistoryEntry>` | `:1403 std::vector<GachaHistoryEntry>` | `:1328 / :1419` | `:33` |
| `GachaHistoryEntry` | `GachaHistoryEntry.kt:13-18`（`@ProtoNumber(1..3)`；**完整字段与 ProtoNumber 待复核**） | `models.h:1170-1179`（`poolId/category/templateId/itemId/rarity/count/isPity/gameMonthIndex`） | `json_codec.cpp:905-911 to/from_json`；`json_codec.h:105-106` | 走 CollectionConverters |
| Room 转换器 | `CollectionConverters.kt:24 import GachaHistoryEntry`、`:618-624 fromGachaHistoryEntryList / toGachaHistoryEntryList`（`encodeToBase64(ListSerializer(...))`） | — | — | — |
| 镜像补丁 | `GameDataFieldPatch.kt:368-379`（四个 `f("gachaXxx", …)` 分支）；`:18 import` | — | — | — |
| Room 版本 | `GameDatabase.kt:94 DATABASE_VERSION = 54` | — | — | — |

> ⚠️ **Kotlin `Map<String,Int>` 走 Room** 但 `core/data/AGENTS.md` 规范说「ProtoBuf 仅 List，禁止 Set/Map」。G01 已用 `Map`（`:903,909,915`）——**推测**有专门 Map 转换器（待复核）；G09 不应再复制该模式给新字段。

## G09-3 ActionId 基础设施

| 项 | 事实（已核实） |
|---|---|
| 生成器 | `scripts/gen-action-ids.mjs`（203 行） |
| **产物（仅 2 份）** | ① `android/app/src/main/cpp/gamecore/include/gamecore/action_ids.h`（`:19-20`）② `android/core/engine/src/main/java/com/xianxia/sect/core/nativebridge/ActionIds.kt`（`:21-22`） |
| 用法 | `node scripts/gen-action-ids.mjs`（零参数；`:196-203` 直接 `writeFileSync` 覆写并打印 `N actions (maxId=…)`） |
| **无 `--check`** | 全文无 `process.argv`。零漂移自证 = `git diff --exit-code -- <两份产物>`（`action-catalog/README.md:45-50`） |
| npm script | 仓库根与 `android/scripts/` 的 `package.json` grep `gen-action` **零命中**（无 npm 入口，直接 node） |
| 目录清单 | `core.mjs`（33021B，段 1000–1734，**冻结**）、`w4a.mjs`（1740–1749/1750–1759/1810–1819/1820–1829/1850–1854）、`w4b.mjs`（1760–1765/1766–1769/1770–1779/1840–1849）、`w4c.mjs`（1780–1789/1790–1799/1800–1809/1855–1859）、`w4d.mjs`（1830–1839/1860–1869）、**`gacha.mjs`（746B，段 1870–1889）**、`README.md` |
| `gacha.mjs` 现状 | 全文仅 20 行，`export const CATALOG = []`（`:18-20`），注释「1870–1889 · 角色卡池（G01 预留空段，G09 实跑分配）」 |
| 生成器自检（新增即失败） | `:71-78` id 全局唯一；`:81-101` 段两两不相交（GACHA 段 `:86 ranges:[[1870,1889]]`）；`:105-121 checkOwnership(GACHA_CATALOG,'GACHA','action-catalog/gacha.mjs')`；`:124-129 core 不得落批段 |
| `GACHA_PULL_ONCE/TEN` 现状 | ❌ **不存在**。`action_ids.h` grep `GACHA` 零命中；`kAllActionIds`（`:614`）max = **1861**，无 1870+ 项；`kAllActionIdsCount = 198`（`:617`）；`ActionIds.kt` 同样无 |
| **段状态结论** | 1870–1889 **仅存在于 catalog 段声明与生成器区间表**，未产出任何常量。G09 需在 `gacha.mjs` 追加两条 + 跑生成器 |
| 生成物额外产物 | `action_ids.h:606-617` 自动产出 `action::kAllActionIds[]`（升序）与 `kAllActionIdsCount`，供 `test/dispatch_guard_test.cpp` 的**分派覆盖守卫**枚举——新 ActionId **必须分派可达**，否则守卫红 |

## G09-4 C++ 事务既有范式（推荐模板 + 注册方式）

### 推荐模板 A（状态写 + 零 RNG，结构最清晰）：`government.h openRecruitmentToggleTx`
`government.h:515-545`：单一出口结构 `PolicyToggleOutcome{ok, errorType, message, wasEnabled}` → 校验 → 写 → 回执。同文件可按需参考 `:189-204` 的「周期扣费 + 月戳」。

### 推荐模板 B（RNG 契约 + 描述符草稿，注释最完整）：`storage_bag_tx.h`
全 134 行，最佳范式说明（**G09 应抄其头注释结构**）：
```cpp
namespace gamecore::system::storage_bag_tx {           // :51
inline constexpr int32_t kMinRewardCount = 5;          // :54 常量集中声明
struct RewardDraw { int32_t kind = 0; };               // :70 描述符（不物化模板）
struct OpenOutcome { bool ok; std::string errorType, message; std::vector<RewardDraw> draws; };  // :74
inline OpenOutcome openStorageBagTx(rng::DeterministicRng& rng,      // :105 —— RNG 由调用方传入，签名级可审
                                    const std::string& bagId, int32_t rarity) {
    OpenOutcome out;
    if (bagId.empty()) { out.errorType="BagNotFound"; …; return out; }        // :109 校验先行
    if (!detail::isValidRarity(rarity)) { out.errorType="InvalidRarity"; …; } // :114 失败零抽取
    const int32_t count = kMinRewardCount + rng.nextInt(kRewardCountSpan);    // :123 抽取段
    for (int32_t i=0;i<count;++i) { RewardDraw draw; draw.kind=rng.nextInt(kRewardKindCount); out.draws.push_back(draw); }
    out.ok = true; return out;
}
}  // :134
```
**关键先例**（`storage_bag_tx.h:19-23`）：**②「物化 + 入仓」必须留 Kotlin** —— 因 `InventorySystem.addXxx` 唯一入口约束（`InventoryAddPathGuardTest`）与 Kotlin 模板库（`EquipmentDatabase/ManualDatabase/ItemDatabase/HerbDatabase`）C++ 不可复刻。⇒ **G09 的 `gacha_tx.h` 应同构：C++ 只做 roll/保底/碎片/星/扣费/描述符，物品描述符回传 Kotlin 物化入库**。

### 注册进 `execute_dispatch.cpp`（G09 推荐走独立端口）
- 现有 `handleXxxTx(GameCore*, actionId, params)` 范式：`execute_dispatch.cpp:2291-2319 handleRecruitTx`（switch + `fail(errorType,message)` / `ok({{...}})`）
- 主 dispatch 链：`execute_dispatch.cpp:2700-2791`（连续区间 `else if`）；**推荐范式** = `:2774-2787` 的 W4 端口认领：
  ```cpp
  } else if (auto w4a = dispatchW4A(*this, actionId, params); w4a.has_value()) { result = std::move(*w4a); }
  ```
- ⚠️ `execute_dispatch.cpp:2769-2772` 注释：**「本文件此后冻结」**，新域应新建 `src/dispatch_gacha.cpp` + 在此追加一个端口（这一步仍是改 `execute_dispatch.cpp`，但只加 2 行端口，与 README §3 口径一致）
- **区间吞号事故先例**（必读）：`:2719-2724` 注释——1730 曾被 1520–1531 区间吞进库存 handler；故新段用**独立端口函数**而非裸区间最安全

### 「新 tx 同批五件套」清单（对齐实施计划 §S2.2 项 2）
| # | 文件 | G09 动作 |
|---|---|---|
| 1 | `scripts/action-catalog/gacha.mjs` | `CATALOG` 追加 `{id:1870,name:'GACHA_PULL_ONCE',desc:'…（SYSTEM 分区）'}`、`{id:1871,name:'GACHA_PULL_TEN',desc:'…'}` |
| 2 | `include/gamecore/action_ids.h` | **生成物**：跑 `node scripts/gen-action-ids.mjs`（禁手改） |
| 3 | `core/engine/.../nativebridge/ActionIds.kt` | **生成物**：同上 |
| 4 | `src/dispatch_gacha.cpp`（新）+ `execute_dispatch.cpp` 端口 1 处 | 新建 + 追加 `else if (auto g = dispatchGacha(*this, actionId, params); g.has_value())` |
| 5 | `test/CMakeLists.txt` | `:170-176` 是 W4 段（`${W4A_TEST_SOURCES}` 等）。**G09 应新建 `test/gacha_tests.cmake`**（含 `gacha_tx_test.cpp` + `dispatch_gacha_test.cpp`）并在 `:176` 后追加 `${GACHA_TEST_SOURCES}`；`dispatch_guard_test.cpp`（`:169`）自动枚举新号，**必须可 dispatch** |
| 6 | 新增头 `include/gamecore/system/gacha_tx.h` | 新建（**当前不存在**） |

## G09-5 `InventorySystem` 签名与 `withTrackingSource`

| 项 | 落点 |
|---|---|
| 统一入口接口 | `ItemAdder.kt:15-22`：`addPill(Pill)`、`addMaterial(Material)`、`addHerb(Herb)`、`addSeed(Seed)`、`addManualStack(...)` → `DomainResult<T>` |
| 实现（单参便捷版） | `InventorySystem.kt:216 addPill(item) = addPill(item, merge=true)`、`:217 addMaterial(item, merge=true)`、`:218 addHerb(item, merge=true)`、`:219 addSeed(item, merge=true)` |
| 实现（全参版） | `InventorySystem.kt:119 addEquipmentStack`、`:157 addEquipmentInstance`、`:178 addManualStack(...)`、`:201 addManualInstance`、`:309 addStorageBag` |
| `withTrackingSource` | `InventorySystem.kt:222-226`：`fun <T> withTrackingSource(source: String, block: () -> T): T`（`prev/restore` 语义）；`:67 internal var trackingSource = "unknown"` |
| 溢出抑制（凭据类） | `InventorySystem.kt:234 withOverflowMailSuppressed` |
| 调用范式（妖兽材料） | `GameEngineWorldBattleOps.kt:327 val result = inventorySystem.withTrackingSource("beast_world") { inventorySystem.addMaterial(material) }` |
| 调用范式（草药/种子/丹药） | `BuildingService.kt:483 withTrackingSource("building") { addPill(pill) }`；`ProductionProcessor处理Ops1.kt:105 withTrackingSource("alchemy")`、`:131 "forge"` |
| 守卫 | `core/engine/src/test/.../architecture/InventoryAddPathGuardTest.kt`（`:119 no overflow truncation via coerceAtMost in any add path`、`:133 no direct list append to warehouse stacks`、`:145 no inline quantity-merge`、`:196 no hand-written StackableItemStore`）；`core/domain/.../DomainInventoryAddPathGuardTest.kt:10` |
| **G09 必读约束** | 手写 `find`+追加 / `coerceAtMost` 截断 / 手写 `StackableItemStore(` 会被守卫拦截 ⇒ 抽卡入库**只能** `inventorySystem.withTrackingSource("仙缘寻访") { addHerb/addSeed/addMaterial(...) }` |
| G07 影响 | `InventorySystem.kt:285 materializeDiscipleBagAndMarkDead` 已改为只写重伤（HP=1）；`withTrackingSource` 行号经复核**未漂移**（`:222-226`） |

## G09-6 `OverflowMailSender.SOURCE_DISPLAY_NAMES`

| 项 | 值 |
|---|---|
| 定义 | `android/core/engine/src/main/java/com/xianxia/sect/core/engine/service/OverflowMailSender.kt:96-122`（`companion object` 内 `val SOURCE_DISPLAY_NAMES: Map<String,String>`） |
| 现有 25 项 | `battle/beast_world/cave_world/cave/beast_raid/patrol/forge/alchemy/spirit_field/storage_bag/merchant/redeem/mail/disciple_reward/disciple_unequip/trial/sect_level/sect_trade/quest/building/confiscate/secret_realm/disciple_death/disciple_expel/unknown` |
| 解析 | `:125-126 sourceDisplayName(source) = SOURCE_DISPLAY_NAMES[source] ?: "未知"` |
| **守卫（G09 必须满足）** | `OverflowMailSenderTest.kt:150-160`：`SOURCE_DISPLAY_NAMES covers all withTrackingSource literals in engine source` —— 用 `Regex("withTrackingSource\\(\"([^\"]+)\"\\)")` 扫 engine 主源，**缺失即失败** ⇒ 新增 `"仙缘寻访"` 必须同批加入 `SOURCE_DISPLAY_NAMES`（**注意 key 是 source 字面量**：若 `withTrackingSource("仙缘寻访")` 用中文则 map key 必须同为中文，或改用 ASCII key + 中文显示值） |
| 溢出转邮件范式 | `InventorySystem.kt:244-264`（`:252 source: String`、`:264 source = source`）；`OverflowMail.kt:9`（`@param source withTrackingSource 的 source 值`）；`DiscipleLifecycleNativeTx.kt:96-101`（完整调用 + `rebaselineNativeMirror` 兜底） |

## G09-7 妖兽材料 / 草药 / 种子 配置表与 rarity

| 表 | JSON 路径 | 结构（原样片段） | rarity 值域 | 条目数 |
|---|---|---|---|---|
| 草药 | `db.herbs` | `{"category":"grass","description":"吸收天地灵气而生的灵草，炼丹基础材料","id":"spiritGrass1","name":"聚灵草","rarity":1,"tier":1}` | **1–6** | 54 |
| 种子 | `db.seeds` | `{"description":"种植后可收获聚灵草","growTime":36,"id":"spiritGrass1Seed","name":"聚灵草种","rarity":1,"tier":1,"yield":5}` | **1–6** | 54 |
| 妖兽材料 | `db.beastMaterials` | `{"category":"hide","description":"凡品虎妖的皮毛，蕴含狂暴之力","dropWeight":1,"icon":"🟧","id":"tigerHide0","materialCategory":"BEAST_HIDE","name":"凡虎皮","price":400,"rarity":1,"tier":1}` | **1–6** | 192 |
| 读取（草药/种子） | `HerbDatabase.kt:218 getByRarity(rarity)`、`:220 getSeedsByRarity(rarity)`、`:234-242 generateRandomHerb(minRarity=1,maxRarity=6,random)`、`:244-252 generateRandomSeed(...)` | — | — | — |
| 读取（材料） | `ItemDatabase.kt:777 generateRandomMaterial(minRarity=1,maxRarity=6,random)` | — | — | — |
| 通用模板注册表 | `TemplateRegistry.kt:32 fun getByRarity(rarity)`、`BaseTemplateRegistry.kt:55 override fun getByRarity` | — | — | — |

> ⚠️ **G09 的 maxRarity=4 是池内截断**，不是配置表上限（表到 6 阶）。G09 必须传 `minRarity=1, maxRarity=4`——`generateRandomXxx` 的 `minRarity..maxRarity` 过滤已支持。守卫已在 `GachaConfigGuardTest.kt:70` 断言 `maxRarity <= 4`（配置侧），**代码侧无**。
> ⚠️ 这 3 张表在 Kotlin **Registry**（`:core:domain`），C++ 不可复刻 ⇒ 与 `storage_bag_tx.h` 同结论：**物品两级 roll 若要在 C++ 做，只能 roll「品阶 + 表内索引」，实际模板物化回 Kotlin**。

## G09-8 RNG SYSTEM 分区取法

| 端 | 取法 | 落点 |
|---|---|---|
| **C++** | `rng::RngManager::getRng(rng::RngPartition::kSystem)` | `rng_manager.h:97-99`；分区枚举 `:49-62`（`kBattle=0,kBreakthrough=1,kExploration=2,kSystem=3,kEnemyGen=4,kMail=5,kAiSect=6,kSecretRealm=7,kMission=8,kAiSectMirror=9,kChat=10,kResidual=11`）；`kMaxPartitionId = kResidual`（`:69`）；播种 `:75-94`（`seed + id`） |
| C++ 调用范式 | `year_settlement.h:1438 auto& rngSystem = rng.getRng(rng::RngPartition::kSystem);`（招募 = SYSTEM 分区先例，G09 **应复用 SYSTEM**，因为保底/类别 roll 属系统级随机） | — |
| C++ 传入范式 | `recruit_tx.h:104-106 refreshRecruitTx(state, year, rng::RngManager& rng)` → `execute_dispatch.cpp:2303-2304 recruit_tx::refreshRecruitTx(state, params.value("year",1), core->rng())`；或 `storage_bag_tx.h:105` 直接收 `DeterministicRng&`（**推荐**，签名级可审） |
| **Kotlin** | `GameRngManager.getRng(RngPartition.SYSTEM)` | `RecruitService.kt:61 private val rng get() = rngManager.getRng(RngPartition.SYSTEM)`；`RngPartition.kt`（`core/engine/.../util/`） |
| 新增分区？ | **不需要**。G09 用既有 `kSystem`，因此**不动** `rng_manager.h` / `RngPartition.kt` / `RngSourceGuardTest.kt`（`:225 R4 RngPartition 枚举新增值必须登记`、`:238 R4 分区 id 与名字一一对应无漂移`） | 降低 G09 风险面 |

## G09-9 G01 守卫壳测试现状

| 测试 | 落点 | 状态 |
|---|---|---|
| 池权重和 / 品阶和 / 保底 / 每星 | `GachaConfigGuardTest.kt:48-85` | `:52-57` 两层 `assumeTrue`（缺 `gachaPools` 键 / 数组空）——**但配置已落，实际不跳过，正在真跑** |
| 六模板 id 集合 | `GachaConfigGuardTest.kt:87-113` | `:91-96 assumeTrue`（同上，已可跑） |
| Q31 色值 | `GachaConfigGuardTest.kt:115-129` | 恒跑；断言 `GameConfig.Gacha.rarityColor(1..6)` / `spiritRootCountColor(1..5)` |
| 色表单源实现 | `GameConfig.kt` `object Gacha`（G07 后 `:210-245`） | 已落 |
| 「模板 id 集合」「池权重和」在**代码侧**的守卫 | ❌ 无（只有配置侧）；G09 需补 `碎片 key ⊆ 模板`、`pity key ⊆ pool` 守卫（实施计划 §测试方案） |
| 其他相关测试 | `RoomMigrationV53To54Test.kt`、`SaveDataDirectSerializationTest.kt:37-82`、`GameDataFieldPatchGuardTest.kt`（字段补丁面）、`MirrorReadOnlyGuardTest.kt` |

---

# §G11 最简寻访 UI 落点

> 本批（G11）依赖 G09。目标：招募按钮 → 寻访主界面（池信息、一次/十次、x/10、灵石不足禁用）→ 结果页（Q30/Q31）→ 图鉴 6 格最小 → 升星全屏层 → 历史按抽。
> 本节目的是**「复用而非重造」**：逐项指出最近既有实现。

## G11-1 招募按钮现有落点与改路由方式

| 项 | 落点（已核实） |
|---|---|
| **按钮宿主** | `GameActionButtons.kt:86-116 LeftSideButtons(viewModel, modifier)`；`Column(modifier.padding(start=32.dp, top=8.dp), verticalArrangement=Arrangement.spacedBy(6.dp))`（`:93-97`） |
| **按钮本体** | `GameActionButtons.kt:102-105`：`FloatingActionButton(text = "招募", spriteName = "ui_recruit_button") { viewModel.navigateToDialog(DialogType.Recruit) }` |
| **精灵名** | `"ui_recruit_button"`（`android/scripts/resource-registry.json:270`、`android/scripts/sprite-uid-map.json:430 uid=324`、`ui_recruit_button.webp` 双模块 `feature/game` + `app` 的 `drawable-nodpi`） |
| 相邻按钮（改文案参照） | `:98-101 "设置"/ui_settings_button`、`:111-114 "日志"/ui_log_button`、`:106-110 "邮件"/ui_mail_button`（带 `badge`） |
| 组件实现 | `GameActionButtons.kt:118+ FloatingActionButton(text, spriteName, badge, onClick)` |

**改路由到寻访主界面（3 处最小改动，二选一）**

| 方案 | 改动点 | 说明 |
|---|---|---|
| **A（推荐，改动最小）** | ① `DialogType.kt` 新增 `data object Gacha : DialogType`（放在 `:32 DialogType.Recruit` 附近）② `GameActionButtons.kt:102-105` 的 `text` 改 `"寻访"`（`spriteName` 可保留 `ui_recruit_button` 或换 `ui_gacha_button`）与 `navigateToDialog(DialogType.Gacha)` ③ `OverlayDialogRouter.kt:35-40` 的 feature 组加入 `is DialogType.Gacha` → `DialogFeatureRoutes.kt` 加 `DialogType.Gacha -> renderGacha(...)` | 复用现有 Recruit 槽位，不动导航层 |
| **B（G05 已定的占位路线）** | G05 先把 `DialogType.Recruit` 分支改成占位「寻访未就绪」；G11 再把该分支体替换为 `GachaDialog` | **与 G05 的占位衔接**，避免 G05→G11 间出现死按钮 |

> ⚠️ **与 G05 的接口**：G05 不得删按钮（实施计划 §盲区自查 1 明写「**G11 前不得删除按钮**」）。若 G05 已把 `DialogType.Recruit` 分支改为占位，G11 **只改分支体，不改 `DialogType` 名**（避免二次改动 `DialogTypeRenderCoverageTest` 的集合）。

## G11-2 `DialogType` 枚举与 `GameOverlayHost` 渲染 when 分支

| 项 | 落点 |
|---|---|
| **`DialogType` 定义** | `android/core/domain/src/main/java/com/xianxia/sect/core/domain/dialog/DialogType.kt:11 sealed interface DialogType`（`:13 val domainKey`）；分段：`:15-27` 主 Tab、`:29-56` 功能性、`:58-74` 带实例 ID 生产、`:76-90` 功能建筑、`:92-95` 引导、`:97-118` 系统、`:121 data object None` |
| **G11 新增值落点** | 若走方案 A：在 `:32 DialogType.Recruit` 后追加 `data object Gacha : DialogType`；若走方案 B：**不新增** |
| **穷尽分派** | `android/feature/game/.../components/OverlayDialogRouter.kt:27-59 when (type) { … }`（**无 `else` 分支**，编译期穷尽）；feature 组在 `:35-40` |
| **路由体（feature 域）** | `DialogFeatureRoutes.kt:36 renderFeatureRoutes(vms, gameData, onDismiss)`；`:42-55 when`；新增分支写在 `:43 DialogType.Recruit ->` 位置 |
| **宿主渲染链** | `GameOverlayHost.kt:76 GameOverlayHost(...)`（`:53 OverlayViewModels` / `:69 OverlayCallbacks`）→ `:366-417 GameOverlayDialogs` → `:421-439 GameDialogRouteSection`（`:427 if (currentDialogType != DialogType.None)`；`:429 gameDataUi` 订阅；`:431 key(currentDialogType)`）→ `:432 OverlayDialogRoute` |
| **宿主入口调用点** | `MainGameScreen.kt:998-999 GameOverlayHost(...)` |
| **遮罩（不可自画）** | `GameDialog.kt:60-69 LocalDialogScrimHosted`（宿主单例遮罩 `GameOverlayScrim`）；`:107-109 scrimActuallyEnabled`。**新对话框必须让 `UnifiedGameDialog` 自处理，不要自画 scrim** |
| **`DialogSystemBarGuard` 要求** | 定义 `core/ui/.../components/StandardPromptDialog.kt:187 fun DialogSystemBarGuard()`；**凡是使用 Compose `Dialog()` 或 Material3 `AlertDialog` 的组件必须显式加**（`GameDialog.kt:130`、`SmallScreenDialog.kt:80`、`ProfessionUi.kt:238`、`SectTransitionOverlay.kt:77`、`ElderBonusInfoButton.kt:80`、`ComplianceLimitDialogs.kt:47`）。用 `UnifiedGameDialog` / `SmallScreenDialog` 的**自动继承**（`GameDialog.kt:126-130` 已在 `Dialog {}` 块内挂载 `DialogSoftInputGuard()` + `DialogSystemBarGuard()`） |
| **最接近的可复用容器（推荐首选）** | `GameDialog.kt:72-98 UnifiedGameDialog(onDismissRequest, title, mode, headerActions, headerContent, scrollableContent, titleColor, titleFontSize, titleAlignment, showCloseButton, showHeader, overlay, backgroundRes=`bg_horizontal`, closeButtonRes=`ui_close_button`, freezeSystemBars, content)` —— **支持 `mode = DialogMode.Full`（全屏，结果页/寻访主界面要用）+ `titleColor`（金色标题）+ `headerActions`（右上灵石栏）+ `overlay`（升星全屏层！）** |
| 现有用法参照 | `RecruitDialog.kt:40-51`（`UnifiedGameDialog(mode = DialogMode.Full, scrollableContent = false, headerActions = { GameButton("招募管理") })`）——**G11 主界面可直接照抄该骨架** |
| 小屏容器（备选） | `core/ui/.../components/SmallScreenDialog.kt:80`（自带 `DialogSystemBarGuard`）；`RewardDisplayDialog.kt:31` 用它 |
| **守卫测试（必改）** | `DialogTypeRenderCoverageTest.kt:26-64 renderedDialogTypes: Set<DialogType>`（**新增 DialogType 必须在此登记**，`:31` 是 `DialogType.Recruit`）；`:67 intentionallyExcluded = setOf(DialogType.None::class.java)`；`:72 allTypes = DialogType::class.nestedClasses`；`:77-83` 失败消息「修复指引：在 GameOverlayHost 的渲染 when 中添加对应分支（含实际 UI 内容）」 |
| 规范文档 | `android/feature/game/AGENTS.md:17-20`（标准流程：注册 `DialogType` → `GameOverlayHost` when 加穷尽分支）；`rules/new-dialog-checklist.md`；`rules/dialog-scrim-standard.md`；`rules/dialog-soft-input-guard.md` |

## G11-3 结果页 Q30/Q31 规格的可复用组件（复用而非重造）

| Q30/Q31 视觉元素 | 最接近的既有实现（**首选复用**） | 落点 | 说明 |
|---|---|---|---|
| **半透明面板 / 对话框容器** | `UnifiedGameDialog` | `core/ui/.../components/GameDialog.kt:72-98` | `backgroundRes = SpriteResRegistry.resolve("bg_horizontal")`；`mode = DialogMode.Full` 可全屏。**禁止自画 scrim**（宿主单例） |
| **半透明遮罩** | `GameOverlayScrim`（宿主单例） | `GameDialog.kt:60-69 LocalDialogScrimHosted`；`GameOverlayHost.kt` 根节点 | `rules/dialog-scrim-standard.md` 统一 `Color(0x99000000)` |
| **金色标题** | `UnifiedGameDialog(titleColor = …)` + `GameColors.Gold` | `GameDialog.kt:83 titleColor: Color = Color.Black`、`:84 titleFontSize`；色值 `core/ui/.../theme/Color.kt:31 val Gold = Color(0xFFFFD700)`、`:32 GoldDark = Color(0xFFB8860B)` | **G11 只需传 `titleColor = GameColors.Gold`，无需新组件** |
| **方形奖励框（含品阶边框 + 名称条 + 数量角标）** | **`UnifiedItemCard`** | `core/ui/.../components/ItemCard.kt:64-112`（参数 `data: ItemCardData, size: Dp = 60.dp, showQuantity = true, onClick, nameFontSize`）；主体 `:132+ ItemCardBody`；**品阶边框/背景 `:191 .background(rarityColor)`**；**数量角标 `:245-254`（BottomEnd，`GameUtils.formatNumber(data.quantity)`，8.sp 白色）**；名称区 `:258-277`（白底黑字单行）；锁定角标 `:222-232`；品阶文字 `:234-243` | **结果页 2×5 直接用 `UnifiedItemCard(size = …)` + `showQuantity = true` 即可** |
| **奖励卡片 → ItemCardData 适配** | `RewardCardItem.toItemCardData()` | `core/ui/.../components/RewardDisplayDialog.kt:54-66` | 已处理 `isPill/isHerb/isMaterial/isSeed/spiritStoneGrade/isBag/isManual` 与 `beastMaterial` 别名 |
| **奖励网格 + 聚合** | `RewardDisplayDialog(cards, title, confirmLabel, onConfirm, onDismiss)` + `cards.mergeRewardCards()` | `RewardDisplayDialog.kt:24-51`（`SmallScreenDialog` + `FlowRow` + `UnifiedItemCard`）；`core/model/mergeRewardCards` | **结果页可先照抄此结构，把 `FlowRow` 换成固定 2×5（`Row`×2 / `LazyVerticalGrid(GridCells.Fixed(5))`）** |
| **固定列数网格** | `LazyVerticalGrid(GridCells, items)` | `RecruitDialog.kt:7-9,105-110`（`LazyVerticalGrid` + `items` + `sortedBy`） | G11 结果页用 `GridCells.Fixed(5)` |
| **奖励条目数据模型** | `RewardCardItem(itemName, itemType, rarity, quantity)` | `core/model/`（`GameEngineWarRewardOps.kt:83-84`、`GameEngineMissionOps.kt:396-397` 为构造先例）；`BattleRewardItem` | G09 结果 DTO 应复用/对齐该形状，便于 UI 直接吃 |
| **数量角标（独立）** | `ItemCard.kt:245-254` | 同上 | **无需新组件** |
| **边框流光 / 稀有度提升动效** | ❌ **无动画流光既有实现**。最接近：`DiscipleComponents.kt:46-61 Modifier.discipleCardBorder(shape, background)`（`Brush.linearGradient(listOf(SurfaceLightGray, ButtonDisabled))` **静态**渐变边框） | `feature/game/.../components/DiscipleComponents.kt:46-61`（**唯一 gradient border**）；`:39-44 object DiscipleCardStyles{smallShape/mediumShape/largeShape/cardPadding}` | **G11 需自建流光**（`rememberInfiniteTransition` + `Brush.linearGradient` 位移）；`grep InfiniteTransition` 在 `core/ui` + `feature/game` **零命中** ⇒ 确认为新增面。低端降级路径见实施计划 §G12 |
| **Battle 结算卡片行（2 行奖励布局先例）** | `BattleRewardCardRow` | `feature/game/.../dialogs/BattleResultDialog.kt:308`；调用 `:208`、`:230` | **最接近「结果页多行卡片」的既有布局**，值得先读 |
| **旧色表（G11 禁用）** | `getRarityColor(rarity)` → `GameColors.Rarity*` | `ItemCard.kt:339-347`；色值 `theme/Color.kt:40 RarityCommon = Color(0xFFB8B8B8)`、`:45 RarityHeaven = Color(0xFFE3A0A0)` | ⚠️ **与 Q31 不一致**（Q31 六阶 = `#ffd700`，旧表六阶 = `#E3A0A0`）。实施计划 §技术债明列。**G11 结果页 + 灵根徽章强制读 Q31**（`GameConfig.Gacha.rarityColor`），不得复用 `getRarityColor` |
| **图鉴 6 格最小** | `UnifiedItemCard`（`isDisciple = true` → `SpriteResRegistry.resolve("disciple_portrait")`） | `ItemCard.kt:125`、`ItemCardData.isDisciple: Boolean = false`（`:60`） | ⚠️ 现在全部弟子共用 `disciple_portrait` 单图；G11 需按 `avatarKey` 区分（见 G11-4） |

## G11-4 精灵注册现状与美术素材入库管线

### 6 角色头像/立绘注册现状（**已核实：全部未注册**）

| 检查点 | 结果 |
|---|---|
| `grep 'avatar_zhouming\|portrait_zhouming\|avatar_suqing\|portraitKey\|avatarKey'` 全仓 | **仅 1 处命中**：`android/app/src/main/assets/data/game-data.json`（配置源本身）。**无任何 Kotlin/C++ 代码、无 registry、无映射** |
| `android/scripts/resource-registry.json` grep `avatar\|portrait` | 仅 `:368 "category": "PORTRAIT"` + `:370 { "name": "disciple_portrait", "res": "disciple_portrait" }`（**唯一肖像条目**，共 374 行） |
| `android/scripts/sprite-uid-map.json` grep `avatar_\|portrait_` | **零命中** |
| `drawable-nodpi` 目录 glob `*zhouming*` | `feature/game/src/main/res/drawable-nodpi` 与 `app/src/main/res/drawable-nodpi` **均无匹配** |
| 结论 | **6 角色头像（`avatar_*`）与立绘（`portrait_*`）共 12 个键全部未注册、无 WebP、无 uid** ⇒ **G11 前置阻塞项**（属实施计划 §盲区自查 5「G11 前素材盘点」） |

### 源素材现状（`模拟宗门美术素材/` 为 untracked 目录）

| 目录 | 内容 |
|---|---|
| `模拟宗门美术素材/` 下含 6 角色目录 | `周明（男）` / `苏晴（女）` / `林雪棠（女)` / `许荷（女）` / `谢澈(男）` / `赵言(男）`（**注意目录名含全/半角括号混用**）；另有 `月城雪` |
| 其他分类目录 | `弟子肖像图/`、`ui/`、`建筑/`、`装备/`、`材料/`、`草药/`、`草药生长期/`、`种子/`、`丹药/`、`储物袋/`、`功法/`、`妖兽/`、`道路/`、`地图关卡场景/`、`宗门地图/`、`装饰物/`、`背景图/`、`视频/` |
| 权威源路径 | `rules/static-resources.md:92` 明写权威源 = `D:\模拟宗门美术素材`（本机 `C:\Mnzm\XianxiaSectNative\模拟宗门美术素材` 为仓库内副本，untracked） |

### 入库管线（`rules/static-resources.md:90-130` 七步，逐条落点）

| 步 | 动作 | 命令 / 文件（已核实） |
|---|---|---|
| 1 | 源图放入 `模拟宗门美术素材/<分类>/<中文名>.png`（如 `周明（男）/头像.png`、`周明（男）/全身像.png`） | `rules/static-resources.md:99-100`；新增分类需先在 `SpriteCategory` 枚举定义 |
| 2 | `android/scripts/resource-registry.json` 对应分类登记 `{ "name": …, "res": … }` | `:102-103`；PORTRAIT 分类现有 `:368-371` |
| 3 | `node scripts/scaffold-source-mapping.mjs`（自动扫 registry + 源目录 → 生成/更新 `source-mapping.json`；**PORTRAIT 走 MANUAL_OVERRIDES 或按固定表**） | `:105-110`；脚本 `android/scripts/scaffold-source-mapping.mjs`（`:256 ui_recruit_button: 'ui/招募.png'` 为命名先例） |
| 4 | 若生成 `source=null`（待补）或命名不规则 → **手补** `android/scripts/source-mapping.json` 条目 `{ "drawable": …, "source": "周明（男）/头像.png", "modules": ["feature/game","app"], "bake": {…} }`；大图用 `{ "bake": { "preserve": true } }` | `:112-115` |
| 5 | `node scripts/import-art-assets.mjs`（可先 `--dry-run`）：按 bake 规则烘焙**无损 WebP** → 写入 `feature/game` 与 `app` 双模块 `drawable-nodpi`；内容 hash 增量 + fail-fast | `:117-119`；脚本 `android/scripts/import-art-assets.mjs` |
| 6 | 界面用统一入口显示：`SpriteImage(name = "avatar_zhouming")` 或 `SpriteResRegistry.resolve("avatar_zhouming")` | `:121-122`；API 见 `:146-175` |
| 7 | 守卫自动兜底：`SpriteSourceMappingGuardTest`（registry 全覆盖）、`ResourceManifestCompletenessTest`（WebP 双模块入清单）、`SpriteCodegenSyncTest`（注册代码与 registry 一致）；编译 `./gradlew compileReleaseKotlin` | `:124-129` |

**关键注意（原文）**：必须跑步骤 3（否则 `SpriteSourceMappingGuardTest` 的 registry 全覆盖失败）；必须跑步骤 5（否则 WebP 未生成/未双模块放置）；`source-mapping.json` 是权威映射数据；首屏可见资源必须在 `priority=0/1` 分类注册（`:140-144`）。

**注册代码落点**：`XianxiaApplication.kt` 经 `SpriteResRegistry.register(...)`（根 `AGENTS.md` §6.6）；预加载清单 `feature/game/.../ResourcePreloader.kt:205 PortraitPool.allPortraitNames() + "disciple_portrait"`（**现有预加载点，新增 12 个角色键需在此附近同步**）；`PortraitPool.kt:13-56`（`male_disciple_1..20` + `female_disciple_1..17` = 37 个通用肖像名，与 6 角色模板**命名空间不同**）。

## G11-5 `GameConfig.Gacha` 现有常量与结果页要用到的键

> **行号已按 G07 后复核**：`object Gacha` 现位于 `android/core/domain/src/main/java/com/xianxia/sect/core/GameConfig.kt:210-245`（旧记录 `204-238` 已失效，漂移 +6）。

```kotlin
object Gacha {                                    // GameConfig.kt:210
    const val HISTORY_RING_SIZE = 50              // :211  ← 历史页容量
    const val FRAGMENTS_PER_STAR = 100            // :212  ← 图鉴 x/100
    const val MAX_STAR = 5                        // :213  ← 星级上限
    const val PITY_PULL_THRESHOLD = 10            // :214  ← 主界面 x/10
    const val PITY_FRAGMENT_COUNT = 5             // :215  ← 保底格数量
    const val PRICE_PER_PULL = 5000               // :216  ← 单价（禁用判定）
    const val START_SPIRIT_STONES = 50000         // :217  ← 开局（G08 用；当前零引用）
    const val STAR_BATTLE_PCT_PER_STAR = 0.08     // :218  ← 升星战力
    const val STAR_CULT_PCT_PER_STAR = 0.05       // :219  ← 升星修炼
    const val INJURY_HEAL_PCT_PER_PHASE = 0.2     // :220  ← 重伤回血（G07）
    const val BREAKTHROUGH_COMP_BONUS = 0.02      // :221  ← 突破补偿

    val RARITY_COLORS: Map<Int, String> = mapOf(  // :224-231 Q31 物品六阶
        1 to "#b8b8b8", 2 to "#4caf50", 3 to "#2196f3",
        4 to "#9c27b0", 5 to "#f44336", 6 to "#ffd700",
    )
    val SPIRIT_ROOT_COUNT_COLORS: Map<Int, String> = mapOf(  // :234-240 Q31 灵根数
        1 to "#ffd700", 2 to "#f44336", 3 to "#9c27b0",
        4 to "#2196f3", 5 to "#b8b8b8",
    )
    fun rarityColor(rarity: Int): String = …      // :242
    fun spiritRootCountColor(rootCount: Int): String = …   // :243-244（约）
}
```

| 结果页/主界面用途 | 读哪个键 |
|---|---|
| 单抽/十连按钮文案与禁用（灵石不足） | `PRICE_PER_PULL`（×1 / ×10） |
| 主界面保底进度 `x/10` | `PITY_PULL_THRESHOLD` |
| 保底格标注（碎片 ×5） | `PITY_FRAGMENT_COUNT` |
| 图鉴 `x/100` 与星级上限 | `FRAGMENTS_PER_STAR` / `MAX_STAR` |
| 奖励框边框色 / 灵根徽章色 | `rarityColor(rarity)` / `spiritRootCountColor(n)`（**返回 `String` 十六进制，需 `Color(android.graphics.Color.parseColor(…))` 换算——现有 `getRarityColor` 返回 `Color`，G11 需自建换算 helper 或扩 `GameConfig`**） |
| 历史页条数 | `HISTORY_RING_SIZE` |

> ⚠️ **类型摩擦**：`GameConfig.Gacha.rarityColor` 返回 `String`（如 `"#ffd700"`，供公示/JSON 同源），而 Compose 要 `Color`。既有 `core/ui` 的 `getRarityColor(rarity): Color` 是**旧色表**（`GameColors.Rarity*`），**不得直接用于 G11**。建议 G11 在 `core/ui` 加薄换算（`Color(android.graphics.Color.parseColor(GameConfig.Gacha.rarityColor(r)))`），并把「结果页色 = Q31」写进守卫测试。
> ⚠️ 任务书提到的 `WASH`（洗炼）与 Gacha **无关**：`GameConfig.TraitWash.WASH_PITY_THRESHOLD` / `SpiritRootWash` 属 G04 删除面，**G11 不得引用**。

## G11-6 灵石余额 / 费用展示与禁用态的现有范式（参照实现）

| 参照项 | 落点（已核实） | 可复用点 |
|---|---|---|
| **首选参照（最接近「单价 + 余额 + 禁用」）** | `feature/game/.../dialogs/MerchantDialog.kt:590-628`：<br>`val totalPrice = item.price * quantity`（`:594`）<br>`val canAfford = spiritStones >= totalPrice`（`:595`）<br>单价文本 `:603`、总价文本 `:624`（`color = if (canAfford) GameColors.GoldDark else Color.Red`，`:625`）<br>`GameButton(text = "确认购买", onClick = onConfirm, enabled = canAfford && quantity > 0)`（`:628`） | **G11 主界面「十连 50000 灵石」+ 余额不足时按钮 `enabled = false` + 价格变红** 可逐字照搬该三元组 |
| **灵石余额栏（顶部展示）** | `MerchantDialog.kt:250-261`：`GameUtils.formatNumber(gameData?.spiritStones ?: 0)`（`:261`） | 寻访主界面顶部灵石栏 |
| 出售侧禁用范式 | `MerchantDialog.kt:641-673`（`totalPrice`、价格色 `:667`、`enabled = sellQuantity > 0 && maxSellable > 0`，`:673`） | 备选 |
| 仓库容量不足禁用 | `MerchantDialog.kt:526 GameButton(text="出售", onClick={}, enabled = false)` | 纯禁用态最简形 |
| 政策费用展示（含月耗文案） | `TianshuHallDialog.kt:784-790 PolicyItem(title, effect, cost="5万灵石/3年", checked, onCheckedChange)` | 费用文案格式先例 |
| 建造升级费用 + 不足原因（**带原因文案**） | `BuildingUpgradeCalculator.kt:53-54`（`if (data.spiritStones < cost) reasons += "灵石不足（升级需$cost 灵石，当前${data.spiritStones}）"`）；UI `BuildingFacadeImpl.kt:623-626` | **若要显示「为何禁用」的原因链**，参照此模式 |
| 灵石写入/扣费原语 | `SpiritStoneWallet.add/deduct(state, amount, SpiritStoneGrade.LOW, …)`（`SectPolicyToggleUseCase.kt:306-307`、`GameEngineWarRewardOps.kt:44`、`CultivationEventProcessor.kt:225`） | **G09 C++ 侧扣费**；Kotlin 侧展示余额只读 `gameData.spiritStones` |
| 数字格式化 | `GameUtils.formatNumber(...)`（`MerchantDialog.kt:261,521`；`ItemCard.kt:247`） | 统一千分位/万缩写 |
| 按钮规范 | `ButtonSizes.StandardWidth` (72dp) × `StandardHeight` (38dp)（根 `AGENTS.md` §11.1）；`GameButton(text, onClick, enabled)` | 结果页双按钮（连抽/关闭）必须用该尺寸 |
| 对话框遮罩规范 | `rules/dialog-scrim-standard.md`；`GameDialog.kt:60-69 LocalDialogScrimHosted` | 不可自画 scrim |

## G11-7 G11 施工前置阻塞项（必须先在 G05/G08/G09 解决）

| # | 阻塞项 | 依赖批次 | 证据 |
|---|---|---|---|
| 1 | `GachaPullResult` 无 `Success` DTO（结果页无数据可渲染） | **G09** | `GachaFacade.kt:37-39`（仅 `NotReady` / `Failure`） |
| 2 | `GachaDelegate` 未接入 `GameViewModel`（无 `viewModel.gacha`） | **G09/G11** | `GachaDelegate.kt:12-14`；全仓零实例化 |
| 3 | 6 角色头像/立绘 **12 个精灵键全未注册**（图鉴/结果页/升星层无图可显示） | **G11 素材批** | 见 §G11-4 五项检查 |
| 4 | `characterTemplates` 无生产读取层 ⇒ UI 拿不到姓名/性别/灵根/立绘键 | **G08** | 见 §G08-3 |
| 5 | 「寻访」按钮路由未定（`DialogType.Recruit` 占位 vs 新增 `DialogType.Gacha`） | **G05 已定占位 → G11 决定** | 见 §G11-1 |
| 6 | Q31 色为 `String`、旧 `getRarityColor` 为 `Color` 且色值不一致 ⇒ 需换算 + 守卫 | **G11** | 见 §G11-3 / §G11-5 |
| 7 | 流光/稀有度动效**无既有实现**（低端降级路径需自建） | **G11/G12** | `grep InfiniteTransition` 零命中；仅 `DiscipleComponents.kt:46-61` 静态渐变边框 |
| 8 | 「保底 x/10」「图鉴 x/100」的 UI 数据面（`pityCounters` 等）已由 G01 落的 StateFlow 提供，但需 G09 真实写入 | **G09** | `GachaFacade.kt:15-24`、`GachaService.kt:24-38` |

---

# 交叉风险（G05 / G06 / G08 / G09 / G11）

## X-1 共享文件矩阵（★ = 多批同时改，必须串行/分区 commit）

| 文件 | G05 | G06 | G08 | G09 | G11 | 冲突性质 |
|---|---|---|---|---|---|---|
| **`execute_dispatch.cpp`** ★ | 删 `handleRecruitTx` + 区间 `:2746-2748` | 删 `:2734-2736`（DISCIPLE_LIFECYCLE 区间本身若只删事务 1 则保留） | 可能加构造入口 | **加 gacha 端口** | — | **文件级锁**；`:2769-2772` 声明「此后冻结」 |
| **`disciple_factory.h`** ★ | 招募删后 `createDisciple` 调用点减少 | — | **主改点**（seed 加 templateId、portraitRes 模板覆盖） | 解锁实例化调用它 | — | G08 独占改造；G05 只删调用点 ⇒ **G05 先行、G08 后改** |
| **`Disciple.kt`** ★ | 招募弟子字段 | 改名删字段？ | 加/用 `templateId` | 读 `templateId`/star | 图鉴读 star/avatarKey | 按字段分区 commit |
| **`game-data.json` + `gacha_config_sample.json` + `gen-game-data.mjs`** ★ | 删 `talents/physiques/affixes` 表（属 G04） | — | **新增读取层** | 读 `gachaPools`/`gachaDefaults` | — | **G08 建层 → G09 消费**；产物**禁手改** |
| **`DiscipleTables*.kt`（4 文件）** ★ | recruitList | 逐出删行 | `templateIds` 写入 | star 需回镜像 | 图鉴读 | G07 后 `DiscipleTables.kt` 行号已漂移 |
| **`GameData.kt`** ★ | 删 recruitList/lastRecruitYear/autoRecruit* | — | — | gacha 字段（已落） | 读状态 | 字段分区 |
| **`RecruitService.kt`** | **整类近乎全删** | — | — | — | — | G05 独占 |
| **`recruit_tx.h` / `recruit_settlement.h` / `year_settlement.h`** | **主改点** | — | `year_settlement.h:1513 createDisciple` 删 | `:183,206` 年报字段保留 | — | G05 独占；**G09 不动 `year_settlement.h`** |
| **`ai_sect_recruit.h`** | 摘钩子 `:409-414` | — | AI 弟子构造是否纳入模板层 | — | — | G05 只删 `:409-414`，**不动 `:116-174`** |
| **`InventorySystem.kt` + `OverflowMailSender.kt`** ★ | — | 逐出袋物化保留 | — | **加 `"仙缘寻访"` 来源 + 入库** | — | G09 独占；G07 已改 `materializeDiscipleBagAndMarkDead` |
| **`DiscipleService.kt`** ★ | `:209 withTrackingSource("disciple_expel")` | 删 `:183-240` | 改 `:125-178` + `:172` | — | — | 同文件不同函数，按函数分区 |
| **`DiscipleLifecycleNativeTx.kt`** | — | 删 `:81-109` | — | — | — | G06 独占 |
| **`disciple_lifecycle_tx.h`** ★ | — | 删事务 1 `:216-273` | — | — | — | 与 G03（婚姻事务 3）冲突 |
| **`disciple_tx.h`** ★ | — | 删 `:1283-1313` | — | — | — | 与 G04（trait/blood 事务）冲突 |
| **`dispatch_w4a.cpp`** | — | 删 `:62-68` | — | — | — | G06 独占（段属 W4-A） |
| **`scripts/action-catalog/gacha.mjs`** | — | — | — | **唯一写者** | — | 无冲突（G01 已隔离段） |
| **`scripts/action-catalog/core.mjs`** | 删 1630-1632（**冻结文件**） | — | — | — | — | **禁改** ⇒ 保号留洞 |
| **`scripts/action-catalog/w4a.mjs`** | — | 删/留 `DISCIPLE_OP_RENAME 1740` | — | — | — | 同左，保号留洞 |
| **`GameConfig.kt`** ★ | 删 `OPEN_RECRUITMENT_*`/`RECRUIT_MONTHLY_LIMIT` | — | 用 `Gacha.START_SPIRIT_STONES` | 用 `Gacha.*` 常量 | **读 `Gacha.*`（`:210-245`）** | G07 后行号 +6 |
| **`DialogType.kt` + `OverlayDialogRouter.kt` + `DialogFeatureRoutes.kt` + `DialogTypeRenderCoverageTest.kt`** ★ | 改占位分支 | — | — | — | **新增 `DialogType.Gacha`（若走方案 A）** | **G05 与 G11 同文件**：G05 改分支体、G11 可能改枚举+集合 |
| **`MirrorReadOnlyGuardTest` 面** | 删 Kotlin 写路径 | 删 | 开局写走 native | **抽卡结果必须 native 写** | UI 只读 | G09 主要合规风险 |
| **`OverflowMailSenderTest` 覆盖守卫** | 删 `disciple_expel` 来源名（若逐出删净） | 同左 | — | 加 `仙缘寻访` | — | **G06 与 G09 都动 `SOURCE_DISPLAY_NAMES`** |

## X-2 语义级交叉

| # | 交叉 | 说明 | 建议 |
|---|---|---|---|
| 1 | **G05 ↔ G08：招募删 + 构造收敛** | 招募是 `createDisciple`/`DiscipleFactory.create` 的最大调用方 | 保持 **G05 → G08** 串行 |
| 2 | **G06 ↔ G05：`renameDiscipleTx` 依赖 `recruit_settle::isSamePerson`** | `disciple_tx.h:1306` | 若并行：**G06 先合、G05 后合** |
| 3 | **G06 ↔ G05：`RecruitListCleanupRule` 恒空 vs 改名净化** | `disciple_tx.h:1301-1310` 成死代码 | G06 删除时确认 G05 已合或同批 |
| 4 | **G08 ↔ G05：`annualNewDisciples` 写入点交接** | G05 删 6 点，**保留** `DiscipleService.kt:172`、`RedeemCodeService.kt:339/504` | 删除清单显式排除 |
| 5 | **G08 ↔ G09 ↔ G11：模板读取层** | 当前**不存在** | **G08 建**（Kotlin Registry + C++ codegen），G09/G11 只消费 |
| 6 | **G09 ↔ G05：`GachaConfigGuardTest` 两层 `assumeTrue`** | 键缺失/数组空时静默跳过 ⇒ 假绿 | G09 落地后改硬断言 |
| 7 | **G09 ↔ 物品表上限** | 池 `maxRarity=4`，表到 6 阶 | G09 必传 `maxRarity=4`；补代码侧守卫 |
| 8 | **G09 ↔ `InventoryAddPathGuardTest`** | `addEquipmentStack` 用 `"$trackingSource:${item.rarity}"` 作年度报告键 | 用 `withTrackingSource` + `addXxx`；勿新建入库函数 |
| 9 | **G09 ↔ `MirrorReadOnlyGuardTest`** | 抽卡结果必须 native 写 | `tryExecuteNative(ActionIds.GACHA_PULL_*)` + `applyDirtyFromNative` |
| 10 | **G09 ↔ GameView/`column_dirty.h`** | 解锁写 `templateId` 需导出 | 复用 G01 已落通路 |
| 11 | **组 A 并行（G02/G03/G04）** | 共享 `models.h`/`Disciple.kt`/`disciple_lifecycle_tx.h`/`disciple_tx.h`/`month_settlement.h` | 顺序 **G02→G05→G06→G03→G04** |
| 12 | **G05 招贤伯乐 ↔ G04 特质表整删** | 职责重叠 | G05 只删**招募数值效果**，特质条目交 G04 |
| 13 | **G06 思过崖驱逐 ↔ 执法保留** | 同文件有 `onExpel` 与 `onRelease`（ActionId 1593 保留） | 只删 `:37/:41/:51/:55-62/:172/:182-197` |
| 14 | **G08 AI 宗弟子 ↔ 模板层** | `ai_sect_recruit.h:116-174` 独立旁路 | **需产品澄清**（推测：AI 保持旁路、`templateId=""`） |
| 15 | **G08 兑换码 ↔ 既有测试** | 4 个测试依赖「兑换码造弟子」 | 改发碎片 ⇒ 必须同批改 + 清死代码 |
| 16 | **G05 ↔ G11：招募按钮/`DialogType.Recruit`** | 共享 `GameActionButtons.kt:102-105`、`DialogType.kt:32`、`OverlayDialogRouter.kt:35`、`DialogFeatureRoutes.kt:43`、`DialogTypeRenderCoverageTest.kt:31` | G05 只改**分支体为占位**，保留 `DialogType.Recruit`；G11 决定是否新增 `Gacha` 枚举 |
| 17 | **G11 ↔ G04：Q31 vs 旧 Rarity 色** | `ItemCard.getRarityColor` 用 `GameColors.Rarity*`（六阶 `#E3A0A0`）≠ Q31（`#ffd700`） | G11 结果页/徽章**强制 Q31**；旧色表属 §技术债（G12 或债票） |
| 18 | **G11 ↔ `SpriteSourceMappingGuardTest`** | 新增 12 个角色精灵键若漏跑 scaffold/import ⇒ 守卫红 | 严格走 §G11-4 七步；`compileReleaseKotlin` 前先跑守卫 |

## X-3 实施顺序建议

```
G05（招募：含 recruitList 恒空 + 占位按钮）
  └─ 删掉 createDisciple/Factory 最大调用方，为 G08 收敛缩小分母
G06（逐出+改名）
  └─ 与 G05 共享 {disciple_tx.h, RecruitListCleanupRule 语义, SOURCE_DISPLAY_NAMES}；若必须并行，G06 先合
G08（模板层 + 开局 + 兑换码）
  └─ 依赖 G05 已删招募构造路径；产出「模板读取层」供 G09/G11 消费
  └─ 同时决定 AI 弟子 templateId 策略（需产品澄清）
G09（gacha_tx）
  └─ 依赖 G08 的模板读取层 + 实例化入口
G10（对拍基线重录）  ← 串行汇合点
G11（最简寻访 UI）
  └─ 依赖 G09（Success DTO / 真实写入）+ 精灵素材入库（§G11-4 七步）
```

**每批必跑门禁**：
- `node scripts/gen-action-ids.mjs` + `git diff --exit-code -- android/app/src/main/cpp/gamecore/include/gamecore/action_ids.h android/core/engine/src/main/java/com/xianxia/sect/core/nativebridge/ActionIds.kt`（G09）
- 桌面 GTest；`:core:engine:testReleaseUnitTest --max-workers=1`；`detekt`
- `MirrorReadOnlyGuardTest` + `InventoryAddPathGuardTest` + `OverflowMailSenderTest`（来源名覆盖）+ `GachaConfigGuardTest`（G09 后改硬断言）
- G11 追加：`SpriteSourceMappingGuardTest` + `ResourceManifestCompletenessTest` + `SpriteCodegenSyncTest` + `DialogTypeRenderCoverageTest`

## X-4 尚需复核项（16 条）

| # | 项 |
|---|---|
| 1 | `models.h` 中 `Disciple.templateId` 字段行号 |
| 2 | `column_dirty.h` 是否有 `DiscipleColumn::TemplateId`；`disciple_store.h` 是否有 `templateIds` 列 |
| 3 | `GachaHistoryEntry.kt` 完整字段与 `@ProtoNumber`（C++ 有 8 字段，Kotlin 侧仅见 3 个编号） |
| 4 | C++/Kotlin `GachaHistoryEntry` 字段一致性守卫是否存在 |
| 5 | Kotlin `Map` 走 Room 的转换器（`gachaFragmentCounts` 等） |
| 6 | `AISectDiscipleManager`（Kotlin 侧 AI 弟子生成） |
| 7 | `DiscipleFacadeImpl.kt:191 expelDisciple(...)` 的上下文（批量入口？） |
| 8 | `DiscipleService.kt` 是否有第二处 `templateId`/gacha 相关写入 |
| 9 | `GameEngineLifecycleOps.kt:219 refreshRecruitList` 的调用场景 |
| 10 | `RecruitListCleanupRule` 恒空后 `NumericSanitizeRule.kt:45-75` 的连带修改 |
| 11 | `scripts/action-catalog/w4a.mjs` 中 `DISCIPLE_OP_RENAME` 条目行号与 `desc` 文本 |
| 12 | 思过崖驱逐的测试文件（glob `*ReflectionCliff*Test*`） |
| 13 | `android/scripts/package.json` 是否有 codegen npm script |
| 14 | `GameConfig.Gacha.spiritRootCountColor` 的精确行号（本报告标 `:243-244（约）`） |
| 15 | **G07 后 `DiscipleTables.kt` 的 `templateIds` 精确行号**（原 `:109`，G07 已改该文件） |
| 16 | **`模拟宗门美术素材/<6 角色>/` 内的实际文件名**（`头像.png` / `全身像.png` 是否如此命名）——决定 §G11-4 步骤 4 的手补映射条目 |
