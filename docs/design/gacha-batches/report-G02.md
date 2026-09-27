# report-G02 · 删寿命/年龄/忠诚/叛逃/偷盗/神魂 + 仓库驻守下线

**日期**：2026-09-23
**分支**：`feat/gacha-m0-m1`
**依赖**：G01（协议字段）、G07（玩家侧已先改为重伤，本批删除其唯一保留的寿元真死亡路径）
**落点依据**：[`recon-G02-G03.md`](recon-G02-G03.md) `## G02` 全表 + `§9` G07 收口项；[`EXECUTION-PROTOCOL.md`](EXECUTION-PROTOCOL.md) 铁律

## 做了什么

### ① 删除面（按系统）

| 系统 | 删除内容 | 落点 |
|---|---|---|
| 弟子行级字段（6 列） | `age` / `lifespan` / `soulPower` / `skills.loyalty` / `usedExtendLifePillTypes` / `usedExtendLifePillIds` + 非协议稀疏列 `lastTheftJudgementYears` | `models.h` → `DiscipleColumn` → `disciple_store.{h,cpp}`（45 处列操作）→ `column_dirty.h` → `json_codec.cpp` → `gameview_encode.cpp` → `game_view.proto`（`reserved 10,11,26,88,104,106`）→ Kotlin `Disciple/Components/Serializer/Tables*/MirrorCodec/Dao` → Room `MIGRATION_54_55` |
| 月度叛逃/偷盗整段 | `processLawEnforcementMonthly` / `processTheftMonthlyFallback` / `judgeSingleTheftCandidate` / 捕获率 / `desertDiscipleCleanup` + 14 个 `law*` 配置（`game_config.h` + `game_config.json` lawEnforcement 段 + `minLoyalty/maxLoyalty`） | `month_settlement.h` **-759 行**；子事件 16→14 |
| 教化之道偷盗钩子 / 住所忠诚 / 矿工忠诚 / 年俸忠诚 / 政策忠诚 | 全部写点与常量（`government.h` 只剩道德常量；`policyMonthlyDeltas` → `policyMonthlyMoralityDelta`） | `month_settlement.h`、`year_settlement.h`、`government.h` |
| 老死链 | `processDiscipleAgingStep`（含 11 槽清理/哀悼传播/解绑 helper 全套）、`processSectDisciplesAging`、`processReflectionRelease`、招募老化段（`processRecruitAging` 收敛为纯净化） | `year_settlement.h` **-402 行**；`lifecycle.h` **整文件删除**（含其 CMake 条目） |
| 突破/修炼 寿元乘区 | `calculateBreakthroughLifespanGain`、`lifespanGainForRealm`、`calculateLifespan{RemainingPercent,CultivationPenalty,BreakthroughPenalty}`、`computeLifespan`、`realmMaxAge` helper、`discipleAgeMax`、`lifespanBonusOf` 同步段 | `disciple.h`、`disciple_stats.h`、`disciple_factory.h`、`breakthrough.h`、`phase_settlement.h`、`appointment_tx.h`、`ai_sect_ops.h` |
| 魂力 | `soulPowerBreakthroughBonus` 乘区、任务完成 +1、宗门战授予事务（1712 删实现留洞）、`worldLevelVictoryTx` 收敛为纯 TOCTOU 重查 | `disciple_stats.h`、`mission_completion.h`、`sect_attack_tx.h`、`battle_residual_tx.h` |
| 战斗残差收口（§9.1/9.3） | `isOutsideSect` 参数与 `false` 侧完整死亡链（悲痛支 + 物化 + 槽位/装备/熟练度清理）、`applyDeterministicWinAttr`、`casualty_detail::applyGriefToRelativesBattle`；`settleBattleCasualtiesTx` 收敛为「重伤写 + 幸存者 HP/MP 回写」 | `battle_residual_tx.h`；Kotlin `CombatService` legacy 链同批删除 |
| 思过释放 | 1593 删实现留洞（`releaseReflectionTransaction` + dispatch case）；**`REFLECTING` 枚举/状态与监牢拆除释放路径保留**（旧档归一化） | `disciple_lifecycle_tx.h`、`execute_dispatch.cpp` |
| **仓库驻守子系统（整链）** | `WarehouseGarrisonSlot` 模型 + `GameData.warehouseGarrisons` + JSON 编解码 + `SlotCleanupInput/Result` 成员与 10 处接线 + `warehouseGarrisonAssignTx`（1612 删实现留洞）+ `SlotGroupKind::Warehouse` + 引导任务 23 改单条件 + `deriveDiscipleStatus` 分支 | 全 C++ 面归零；**`DiscipleStatus.WAREHOUSE_GARRISON` 枚举值保留**（旧档反序列化） |
| ActionId | 1103/1106/1593/1612/1712 标注「已退役，编号禁复用」；1301/1632/1744/1780/1781/1860 描述收敛为当前状态；`gen-action-ids` 重生成 | `scripts/action-catalog/*.mjs` → `action_ids.h` + `ActionIds.kt` |
| 配置源与数据（丹方/词条） | 下架丹方 pillType ×4（`extendLife`/`loyalty`/`comprehension`/`charmLoyalty`），模板与配方 732→660；天赋 109→94（`base_oyal`×3、`base_comp`×3、`lifespan`×6、`pos_law_enforcement`×3）；词条 71→61（`aff_base_comp`×3、`aff_lifespan`×3、`aff_pos_law_enforcement`×3、`neg_aff_lifespan`）；`neg_base_social` 删除 `loyaltyFlat` 键 | Kotlin 四 Registry + C++ `recipe_db.h`/`trait_db.h` 镜像 + `scripts/data/*_sample.json` + `gen-recipe-db.mjs`/`gen-trait-db.mjs` + `game-data.json`（sha256 重算） |
| `loyaltyAdd` 效果字段 | `ItemEffect`/`PillEffect` 两结构 + 编解码 + 全部装配/施效/比较消费点 | `models.h`、`json_codec.cpp`、`disciple_tx.h`、`pill_system.h`、`merchant/production/secret_realm/disciple_purchase/recruit_settlement` |
| Kotlin 生产/UI 面 | `LawEnforcementProcessor`/`LawEnforcementTheftTxOps`/`LawEnforcementHallDialog`/`WarehouseDiscipleSelectDialog`/`DiscipleAgePolicy` **整文件删**；忠诚/神魂/年龄 UI 行与筛选项；`GameConfig` law 段与 `MIN/MAX_LOYALTY`；`GameCoreBridge` law `external fun` 面；聊天/丹药/突破/战斗残差的忠诚寿元写点 | 见 Kotlin 子批清单（下「分工」节） |

### ② 分工与并行实施

| 面 | 承担 | 状态 |
|---|---|---|
| C++ 生产面 + 三端字段链 + ActionId + 桥/配置头 | 主线程 | ✅ 桌面 `game-core` 库编译 EXIT=0 |
| Kotlin 生产/UI 删除（G02-4 两表 + §9.2/9.3 收口） | 子代理 A | 报告见附录 |
| Kotlin 三端链 + Room `MIGRATION_54_55` + proto `reserved` | 子代理 B | 报告见附录 |
| 丹方/词条/配置源 + 生成器 | 子代理 E | ✅ `gen-game-data.mjs --check` 绿；`game-data.json` 1,132,046→1,069,742 字节 |
| C++ 测试面（编译修复 + 断言处置 + RNG 平移分类） | 子代理 T | ✅ ctest 1532/1536，报告见附录 |
| Kotlin 测试面（整类删/改断言/RNG 登记/Room v55 新用例） | 子代理 c340 | ✅ 改 ~110 / 删 10 / 增 1，报告见附录 C |
| Kotlin 集成门禁 + 本报告定稿 | 主线程（集成阶段） | ✅ compile 绿 / detekt 绿（JUnit job 运行中） |

## 验证

| 门 | 结果 |
|---|---|
| 桌面 `game-core` 库编译 | ✅ EXIT=0（多轮复验） |
| `node scripts/gen-action-ids.mjs` | ✅ 198 动作 / maxId=1861，双产物重生成（1103/1106/1593/1612/1712 退役标注 + 1301/1632/1733/1744/1780/1781/1860 描述收敛） |
| `node scripts/check-jni-count.mjs` | ✅ 89/89，双桥无扩散（多轮复验含 A 代理两跑） |
| `node scripts/gen-game-data.mjs --check` | ✅ 校验通过（sha256 `915563485e9f…`；6 组中性源↔测试快照逐字节一致） |
| G02-7 生产面 grep | ✅ `loyalty/loyaltyAdd/loyaltyFlat/soulPower/usedExtendLife*/law*配置/theft*/desert叛逃写点/computeMaxAge 族` 生产命中 = 0（残余全部为下方登记保留项） |
| 游戏内 changelog | ✅ 当前版本 4.01.14 首条目 3→7 行，JSON 解析通过 |
| 桌面 ctest 全量 | ✅ **1532/1536**（基线 1606 − 本批删 70 例 = 1536；编译零错误）。A 类断言漂移 10/10 修复清零；余 **4 失败 = B 类黄金序列平移，逐例登记 G10 重录窗口**（见下表），非 G02 缺陷 |
| `compileReleaseKotlin` | ✅ **BUILD SUCCESSFUL**（三轮收口共 4 错：`突破Ops5` 构造参数 11→10、`ItemDetailOtherEffects` 删已退役 `PillTemplate.extendLife` 展示行×2、`DiscipleChatDialog:199` 删 A 批删忠诚分支遗留的孤立括号） |
| `:core:*` JUnit 全量 | ✅ **7893/7893 全绿**（app 1015 / data 817 / domain 1708 / engine 3235 / ui 146 / feature:game 972；`--max-workers=1` + `-Dgamecore.jni.path` JNI 桥 → **Diff 跨语言对拍 51 套件 271 测试全真跑、0 skip**（IN8 出厂门生效，首轮 256 静默跳过已消除）） |
| detekt 六模块 | ✅ **EXIT=0 全绿**（终验 BUILD SUCCESSFUL；过程收敛：首轮 14 条 → 移交/自查清零；含删死函数后孤儿 import、`seedDisciple` 孤儿） |

## 旧用例处置表

（**双端均已完成**：C++ 侧 T 代理 42 文件 = 整例删 70 + 断言改写 10 + 夹具清 ~20（详单见附录 T）；Kotlin 侧 c340 代理 = **整类删 10 + 新增 1（RoomMigrationV54To55Test）+ 改断言 ~110 文件**（详单见附录 C，含招募链纯签名重写、Diff 族改写、仓储槽位 11 文件、配置三件套、协议族、detekt 15 条清零）。两代理 RNG 平移项共 8 条（C++ 4 + Kotlin K1-K4）登记 G10 未自行重录。已知必红点登记：`recipe_db_test.cpp:251/257`、`data_store_test.cpp:141`（732→660）、`trait_effects_test.cpp` 已删 id、`DiffTraitEffectsTest.FLOOR 100→90`、`DiscipleAgeCalculatorTest`/`AgeLifespanRuleTest` 等 lifespan 链（已整类删）、`month_settlement_test` 叛逃/偷盗 20+ 用例（已删）、`lifecycle_test.cpp`（整文件）、warehouse 6 文件（已删）。）

## 分类表（本批）

| 类别 | 面 |
|---|---|
| ① 搬迁 | — |
| ② 删除/只读 | 寿命/年龄/忠诚/魂力字段与全部读写点；叛逃/偷盗/执法堂结算；老死链；思过释放；仓库驻守子系统；延寿/忠诚/悟性丹方与相关词条；对应 UI 入口 |
| ③ 命令进 C++ / 回执出 C++ | 无新增 ActionId / 无新增 JNI 端口；1103/1106/1593/1612/1712 **删实现留洞**；1301 回执改 `moralityDelta` 单字段；1744/1743 回执 `theftCandidate`→`baseAttrApplied`；1780 签名去 `isOutsideSect`；1781 签名收敛 `(state, levelId)` 且回执只剩 `applied`；1860 参数去 `loyaltyDelta`；`nativeSetGameConfig` JNI 由 16 参收敛为 2 参 |

## 保留项登记（grep 残余逐条裁决）

| # | 保留项 | 理由 |
|---|---|---|
| 1 | `annualDesertedDisciples` / `YearlyReport.desertedDisciples`（C++ 4 写点 + 年报 2 读点） | handover §5#3：与 G06 玩家逐出共用字段，**保留**；G02 已删其叛逃写入方，剩余写点=逐出（G06 删） |
| 2 | `lawEnforcementElder` / `lawEnforcementDisciples` 长老槽位 + `LAW_ENFORCING` 状态 + 执法堂建筑 | 槽位链不在 G02-7 grep 目标（pattern 为大写 `LawEnforcement` 文件族，已随 3 文件删除归零）；**职责（捕获率）已失** → 登记为后续收口项（含 guide 任务 17/18、UI 任命入口是否退役待产品确认）。⚠️ **勘误（2026-09-27）**：本行「guide 任务 17/18」为**失真**——17/18 是问道塔/青云塔任务，实测受执法堂影响的是**任务 13/14**；该收口项已由 `docs/design/remove-law-enforcement-and-prison-implementation-plan.md` 实施完成（双端删 13/14/25 + 空号登记） |
| 3 | `DiscipleStatus.REFLECTING` / `WAREHOUSE_GARRISON` 枚举值 + `deriveDiscipleStatus` 的 REFLECTING 保持分支 + 监牢拆除/秘境换岗/任务派遣的思过键清理 | **旧档反序列化兼容**（删枚举值会炸档）；生产写入方已全部下线，仅存量归一化 |
| 4 | `REDEEM_RESOLVE_AGE_LIFESPAN`(1436) + `resolveAgeAndLifespan` + `RealmConfig.maxAge` | 口径 #3「留洞（§6.10 另批）」：兑换码改道归 **G08**，届时一并清零 |
| 5 | `ItemEffect.extendLife` / `PillEffect.extendLife` 协议字段 + `pill_system` 的 `kPermanentLife` 分类 | 旧存档袋内丹药条目兼容；生产者（延寿丹配方）已下线，施效点已摘除，字段恒 0 |
| 6 | `RECRUIT_AGE_TX`(1632) 实现与命名 | impl 已收敛为纯净化（desc 已更新），招募链整体退役归 **G05** |
| 7 | `YearSettlementDraft`/`AgedDeathDraft`/`BereavementDraft` 信封 | 填充点（老死链+悲痛）已全删 → **恒空**；结构体与 `nativeSettleYear` 传输契约保留至 **G03** 整体退役 |
| 8 | `TalentType.LIFESPAN/BASE_LOYAL/POSITION_LAW_ENFORCEMENT`、`AffixType.LIFESPAN` 枚举值 | 旧档特质 id 解析兼容（条目已删、mapNotNull 静默丢弃） |
| 9 | `disciple_tx` 回执 `moralityAfter` | 中性命名（施效后道德终值），Kotlin 侧读取口保留 |
| 10 | `GameConfig` 政策忠诚常量 ×5（`BENEVOLENT/RELAXED_MGMT/STRICT_TRAINING/ENHANCED_SECURITY/CURFEW_LOYALTY_PER_MONTH`） | 落点表未列（G02-6 只圈 `:126-127/:920-942`）；消费方（政策忠诚段）已随 A 批删除 → **零生产引用**；政策本体（非忠诚效果与旧档开启态）存续，与 #11 同口径保留待产品收口 |
| 11 | `GameConfig.LawEnforcementConfig` 对象壳 + `THEFT_*` 常量 ×14 | 落点表未列（A 代理登记「只列 getter 段」）；生产消费方已随执法域归零 → 保留待后续收口 |
| 12 | `GameEventRecord` `THEFT_CAUGHT/WAREHOUSE_THEFT/THEFT_DESERTION` 事件类型常量 + `SpiritStoneTransaction.Theft` + `BattleLogDialogs`「Theft→盗窃」映射 | 旧档 game_events/灵石账本历史记录回显兼容；**零新写点**（系统已下线） |
| 13 | `realmLawEnforcement`（json/GameConfigData/GameConfig.Elder）+ `TalentType.POSITION_LAW_ENFORCEMENT` 枚举值 + `RedeemCode.loyalty` 配置字段 | 长老境界配置（长老系统存续，落点表未列）、旧档特质 id 兑换码配置（§6.10/G08 域）→ 保留 |
| 14 | `law_enforcement_hall` 建筑（buildings.json/atlas/Defaults.kt/BuildingFeatureBoot/ProductionSlot.BuildingType）+ 引导任务 13/14 | **建筑与任命槽位存续**（长老系统/G02-4 未列建筑面）；引导 13/14 按任命完成、条件生产方健在 |

## 未完成 / 登记

- **RNG 平移（G10 唯一权威重录窗口）**：月结 SYSTEM（叛逃/偷盗/教化钩子/配对年龄过滤）、年结 SYSTEM（思过释放/招募年龄 roll 删除）、关卡胜利 SYSTEM（偷盗钩子）、创建期 RNG（`rollSkills` 少 roll 忠诚、AI 生成少 3 次消费）、模板池 732→660 与特质池缩小引起的索引空间平移、`determinism_probe` 哈希输入变化 → **全部对拍基线与黄金序列由 G10 一次性重录**；本批测试代理已明令禁止自行重录，失败项按「B 类」逐条登记。
- **主线程跨代理抓获并修复的生产问题（举一反三清单）**：
  1. `traitAddConfirmTx` 误删 `currentIds.push_back(newId)`（T 守卫抓住：特质新增永不写入）→ 已恢复；根因=删 lifespan 同步块时编辑边界连带，已对照审查 `traitWashConfirm` 替换段（完好，T 测试绿）。
  2. `phase_settlement.h` 用 `kMsPerPhase1x` 缺 `#include settlement.h`（靠包含序兜底）→ 补 include（根因修复）。
  3. 引导任务 23/25 条件生产方死亡导致引导卡死 → C++/Kotlin 双端单条件化 + `DISCIPLE_IMPRISONED` 计数器退役。
  4. `DiscipleStats.loyalty`、`GameConfigData.minLoyalty/maxLoyalty` 遗留字段（落点表未列但违反 grep#1 归零口径）→ 删除，测试同步交 c340。
  5. 思过释放 native 臂恒失败转发（1593 留洞但 Kotlin 每次仍发）→ 删 `tryNativeReleaseReflection`，facade 直走 Kotlin 归一化路径；文件头「五事务」→「三事务」。
  6. `guide_reward_tx.h` 「全为默认2」注释过时（23/25 为 1）→ 已清。
  7. 给 S 的 `ChildBirthSystem.kt` 路径笔误（`system/child_birth/`）→ 实为 `engine/system/`，已发纠正。
- **`sect_defense_battle.h` 疑似 G07 遗漏**：`:388` 守方全员仍走 `markAllDead`（重伤语义下 OK）但其前的 `propagateGriefToRelatives` 调用随 G03 删除（该函数依赖亲缘列）；`slot_cleanup.h` 死 include 已随本批删除（§9.9 收口 ✓）。
- **`isOutsideSect` 强类型化 / `markDead` 改名**（口径 #8）：继续登记为后续收口项，不在 G02 实施；本批仅删除该参数（1780 签名已无此参）。
- **`GameEngineCoordination.applyConversationEffectAtomic`** 已无 `loyaltyDelta`；`CultivationRateCalculator.lifespanGainForRealm`/`tables.lifespans` 读点、`RecruitIntegrity` 年龄容差、购买日志「岁：」格式消费方、`GameCoreBridge.nativeSetGameConfig` 签名核对 → 归**集成阶段**收口（编译驱动）。
- **双 changelog**（`changelog_entries.json` + `CHANGELOG.md`）→ 集成阶段随提交同批更新。
- **活文档同步登记**：`docs/knowledge-base.md` / `docs/cpp-engine.md` 中对寿命/叛逃/偷盗/执法堂/仓库驻守等子系统的现状描述 → 按交接文档分工归 **G14 文档收口批**统一改写（本批不改 docs，避免与 G14 双写冲突）。

### 集成阶段收口清单（预侦察已固定，均为无主 Kotlin 生产文件）

| 文件 | 残留 | 处置 |
|---|---|---|
| `core/data/.../integrity/rules/DiscipleAgePositiveRule.kt` | `disciple.age` 校验 | 整规则删 + `SaveValidationRuleRegistry` 注册行删 |
| `core/data/.../integrity/rules/NumericSanitizeRule.kt` / `SaveValidationRuleDefaults.kt` / `SaveValidator.kt` | age/lifespan/soulPower/loyalty 数值清洗与默认段 | 剥除对应键（现场逐行核） |
| `core/domain/.../model/RecruitIntegrity.kt` | 年龄容差 isSamePerson、`age in 1..10000` 合法性、`MAX_REASONABLE_AGE` | 与 C++ 对齐：签名相等判定 + 删 age 合法性 |
| `core/engine/.../CultivationRateCalculator.kt` | `lifespanGainForRealm`、`tables.lifespans` 装配、`getLifespanGainForRealm` | 删函数与装配行 |
| `core/engine/.../DiscipleFacadeImpl功法Ops1.kt` | age 日志 ×4、lifespan 同步、isSamePerson | 日志去「N岁：」前缀；lifespan 同步段删 |
| `core/engine/.../GameEngineTraitWashOps.kt` | `syncLifespanForTraitChange` | 整段删（对齐 C++ appointment_tx） |
| `core/engine/.../RedeemCodeRewardOps.kt` | lifespan/loyalty 赋值 | 最小剥离（兑换码改道归 G08） |
| `core/engine/.../GameEngineRecruitOps.kt` / `Generation.kt` / `PartnerSystem.kt` / `BuildingServiceSlotOps.kt` / `AutoPillService.kt` / `CultivationCore.kt` | 单点 age/lifespan 引用 | 编译驱动逐点剥除 |
| 购买/装备/拜师日志消费方 | 「N岁：」格式 | 格式收敛为无年龄前缀 |
| `GameCoreBridge.kt:392 nativeSetGameConfig` | 须与 C++ 2 参 JNI 一致 | 与 A 核对，缺则补 |
- **`neg_base_social` 保留但删除 `loyaltyFlat` 键**（recon 差异① 裁决：天赋本体不在四组清单内，仅剥除忠诚键以满足 grep#1）。
- **Kotlin 测试面预侦察**（集成后委派）：732 个 `*Test*.kt` 中，删字段 83 文件/334 处、叛逃偷盗执法 30 文件/199 处（`LawEnforcementProcessorTest` 87 处整类删）、仓库驻守 14 文件/86 处、思过释放 5 文件/30 处（`DiscipleReflectionReleaseTest` 整类删）、老化寿元 13 文件/108 处、魂力 14 文件/34 处、聊天忠诚 3 文件/14 处、已删特质/丹方 id 9 文件/22 处；外加 recon G02-5 既有表与 E 移交的 `DiffTraitEffectsTest.TALENT_ENTRY_COUNT_FLOOR=100`（94 必红）。处置口径同 C++ 侧：断言能改则改、RNG 平移登记 G10。

## 附录：子代理报告（摘要）

### A · Kotlin 生产/UI 删除面 ✅
- **整文件删 6**：`LawEnforcementProcessor`(499行)、`LawEnforcementTheftTxOps`(498)、`LawEnforcementHallDialog`(475)、`WarehouseDiscipleSelectDialog`(316)、`DiscipleAgePolicy`、`AgeLifespanRule`。
- **约 85 文件**：DialogType/GameRoute/Router/Navigation 等路由引用点全清；`GameConfig` law14 getter + MIN/MAX_LOYALTY、`GameConfigData.LawEnforcementSection`、Provider/NativeBridge law 面、`GameCoreBridge.nativeSetGameConfig` 缩 2 参；`CombatService` §9.2/9.3 legacy 链 9 成员删（`computeSurvivorUpdates/applySurvivorHpMpUpdates/SurvivorUpdate/clearDeadFromProductionRepository` 证实无他消费者→保留）；`CultivationSettlement` 忠诚/年俸惩罚/住所忠诚/矿工衰减/政策忠诚全删（扣费与道德保留）；stat calculator 魂力与忠诚分支、突破寿元增益、Factory/Facade/Pill/AI/Recruit/Exploration/Patrol/BattleOps 系列逐条清；UI 忠诚/神魂/年龄行、Tianshu 5 忠诚政策开关、聊天 loyalty 段、LoadingTips 忠诚叛逃偷盗提示、Warehouse 驻守段全清。
- **门禁**：`check-jni-count` 两跑 89/89 绿；点名符号（LawEnforcementProcessor/DialogType.LawEnforcementHall/computeMaxAge/AgeLifespanRule 等）src/main 归零。
- **登记**：赏赐入口随 WarehouseDiscipleSelectDialog 移除（产品确认）；Tianshu 5 开关删除后严苛训练/松弛管理等**非忠诚效果**失去新开关入口（旧档已开启者继续生效，产品确认）；`applyGriefUpdatesToTables` 零调用方（G03 删，detekt 风险已提示）；执法长老槽位 4 个查询 API 无调用方（登记）。

### B · Kotlin 三端字段链 + Room v54→v55 + 仓库驻守行为面 + loyaltyAdd ✅
- **字段链**：`Disciple` 删 age/lifespan/soulPower + `Index(loyalty)/Index(age)`（余5索引）+ `canCultivate=realmLayer!=0`/`realmName` 去 age 分支；`DiscipleComponents` 删 `SkillStats.loyalty` + `UsageTracking` 两字段；`DiscipleSerializer` 12 映射行/6 surrogate 字段 + `reserved 7,8,29,50,76,88`（102→96 字段）；Aggregate/Core/Attributes/Extended/Equipment/Delegates 同步；`DiscipleTables` 7 列 + `ColumnRegistry` 7 条目（余序与 C++ `DiscipleColumn` 删项对齐）+ Assemblers/Write/AssembleGroup。
- **GameData/协议**：`GameData` 删 3 字段（`reserved 133,134`+`146` 双侧）；`WarehouseGarrisonSlot` 类删；`Items` `PillEffect.loyaltyAdd reserved 24`、`ItemEffect.loyaltyAdd reserved 23`（双侧）；`OldSerializableSaveData` 双侧全量 reserved + `SerializableWarehouseGarrisonSlot` 类删。
- **Room**：新建 `GameDatabaseMigrationsV55.kt`——`disciples` 删 `age/lifespan/soulPower/loyalty/usage_usedExtendLifePillIds` 5 列（5 索引重建，`index_disciples_loyalty/age` 随列退役）、`game_data` 删 `annual_theft_count/theft_judgements_this_month/warehouseGarrisons` 3 列（5 索引重建），均 `rebuildTableDroppingColumns`（create-copy-drop-rename，幂等）；`DATABASE_VERSION` 55 + `ALL_MIGRATIONS` 追加 + v55 注释；`DiscipleDao` 删 `getLowLoyalty/getByMinAge`；`CollectionConverters` 删 slot 转换器对；`SlotRefRule` 去仓库段。
- **仓库驻守 Kotlin 行为面**：`GameEngineWarehouseOps.kt` **整文件删** + AppointmentNativeOps native 臂、SelfHeal/SlotWinner/SlotCleanup/AssignmentGate/StatusService（14→13 flag 侧）/ProductionProcessor/ServiceOps/BuildingFeature(Warehouse 组收敛为 0 槽壳)/MonthlyOps 年结重置、`BuildingState`/`SlotAssignment.SlotCategory.WAREHOUSE_GARRISON`/`GameViewDiscipleRows`/`GameDataFieldPatch` 同步。
- **门禁自查**：三树 src/main `loyaltyAdd` 归零（余 4 处 reserved 注释=交付物）；`annualTheftCount/theftJudgementsThisMonth` 归零。
- **四项偏差（主线程已裁决 ✅）**：① `GameViewMirrorCodec` cause 解码保留、缺省 `"age"→"unknown"`（删行会丢 battle/scout 真实死因）；② `canCultivate = realmLayer != 0`；③ 引导任务 23 删除条件分支（与 C++ 侧单条件改法双端对齐）；④ `usage_usedExtendLifePillTypes` 经 54.json 核验**无 Room 列**（`@Ignore` 运行时 Set），不入 drop 清单——recon G02-0 该行有误，现取证为准。

### E · 丹方/词条/配置源 + 生成器（两轮）✅
- **下架**：丹方 pillType ×4（extendLife/loyalty/comprehension/charmLoyalty）模板与配方 732→660；天赋 109→94（base_oyal×3/base_comp×3/lifespan×6/pos_law_enforcement×3）；词条 71→61（aff_base_comp×3/aff_lifespan×3/aff_pos_law_enforcement×3/neg_aff_lifespan）。
- **第二轮收尾**：`neg_base_social` 剥 `loyaltyFlat` 键+desc 收敛（TraitRegistryGuardTest 逐字段比对强制，recon 差异①裁决：天赋本体保留）；Kotlin `PillRecipe/PillTemplate` 删 `extendLife+loyaltyAdd` 字段与 1320 个恒 0 JSON 键（与 C++ recipe 面对称）。
- **配置**：`game_config.json` 删 lawEnforcement 整段 + minLoyalty/maxLoyalty。
- **门禁**：`gen-recipe-db`（72锻造+660丹药）/`gen-trait-db`（94+24+61）/`gen-game-data` 全 exit 0；**`--check` 绿**，sha256 `915563485e9fd41d14c77d76b2f25ff711c535e82f384d34a4faca5b10be84d2`；`game-data.json` 1,132,046→1,049,918 字节；6 组中性源↔测试快照 SHA256 逐字节一致；`loyaltyAdd`/`loyaltyFlat` 生产面全域归零（G02-7 grep#1 硬指标 ✅）。
- **保留裁决**：`comprehensionAdd` 双属性丹（intelligenceComprehension/comprehensionMorality）保留（悟性本体 G04）；`neg_base_craft/neg_base_comprehension` 保留；`TalentType.LIFESPAN/BASE_LOYAL/POSITION_LAW_ENFORCEMENT`、`AffixType.LIFESPAN` 枚举值保留（旧档解析，条目已删 mapNotNull 静默丢弃）。

### T · C++ 测试面清理 ✅
- **42 测试文件修改 + `lifecycle_test.cpp` 既有删除**：月结 -28 例（Theft×20/叛逃×3/教化钩子×3/负忠诚钳制/住所忠诚）、年结 -9 例（思过释放×3/老死链×4/AI 老化/年俸掉忠诚）、断言改写（chat 6 参、worldLevelVictory applied-only、applyBreakthroughSuccess 单参、isSamePerson 纯签名、guide 23/25 单条件、退役号豁免集 {1103,1106,1593,1612,1712} + 反向断言、仓库簇 12→11 槽类/7→6 组、trait/recipe/data 计数 94/61/660）、纯夹具六列与 `d.age/d.lifespan` 行清。
- **门禁：ctest 1532/1536，编译零错误**；A 类 10/10 修复清零（GuideReward 锚点/日志去年龄前缀×7/DispatchGuard 退役豁免/SpiritMine 计数器）。
- **T 抓获并移交生产回归 1 例**：`traitAddConfirmTx` 误删 `currentIds.push_back(newId)`（特质新增永不写入）——主线程已修复（守卫测试 `TraitAddConfirmAppendsAndCheckpoints` 转绿）；另移交 `phase_settlement.h` 缺 `#include settlement.h`（已补）与 `guide_reward_tx.h` 陈旧计数注释（已清）。
- **B 类登记表（→ G10 权威重录，铁律禁中间批重录）**：
| 失败用例 | 平移证据 | 归因 |
|---|---|---|
| `ChildBirth.GoldenSequenceSingleBirth` | 技能段右移一格（morality 43→50 等 6 字段错位）+ affix 黄金 `dmg_amp→dmg_reduce` | G02 删 rollSkills 忠诚抽取致 SYSTEM 序平移 + 词条池变动 |
| `DiscipleFactory.GoldenSequenceSeed42` | 技能段错位一格（66/51/81→33/66/51） | 同上 |
| `DiscipleFactory.GoldenSequenceSeed987654321Female` | portraitRes `_8→_14`、intelligence/charm/morality/artifactRefining 变 + affix 黄金变 | 模板池索引空间平移 |
| `DeterminismProbeTest.DigestMatchesGoldenBaseline` | `kGoldenDigest 0x490e8dc522e12921` vs 实际 `0xb4f3c6912207f597` | G02 删除面致整体 digest 平移 |

### C · Kotlin 测试面清理（c340 代理）✅（src/test：改 ~110 / 整删 10 / 新增 1）
- **整类删 10**：`LawEnforcementProcessorTest`（87 处）、`LawEnforcementDesertionScopeTest`、`LifespanGainOnBreakthroughTest`、`DiscipleStatCalculatorLifespanGainTest`、`AgeLifespanRuleTest`、`DiscipleAgePositiveRuleTest`（生产规则整删）、`DiscipleAgeCalculatorTest`、`DiscipleReflectionReleaseTest`（授权）、`DiscipleComponentsTest`（formatDiscipleAge 生产删）、`DiffLifecycleTest`（代理自行判定：JNI computeMaxAge/ageDisciple/ageAlive 三 op 退役，对拍主体消失）。
- **新增 1**：`RoomMigrationV54To55Test`——v54 真实 Room 校验走 ALL_MIGRATIONS 链尾 + v54 插种子（PRAGMA 动态整行 INSERT）→ 断言 5+3 列消失、loyalty/age 索引退役不重建、存续索引重建、存续列数据完整（正向断言补齐）。
- **改断言核心面**：`lawEnforcementProcessor` 构造参 15 文件、`DiffMonthSettlementTest` 大改（删场景⑬教化偷盗钩子、忠诚/叛逃断言组 → `assertPairingAndFixtureEffects`、12 处 age 剥、金黄锚 id=17/资质82/月费×6 保持）、招募链 6 文件改纯签名语义（RecruitIntegrity 面重写 + 新增正向断言）、生命周期族（老化 7 用例删 + 新增 `processDiscipleAging→verify(syncAllDiscipleStatuses)`）、仓储槽位 11 文件（`WAREHOUSE_GARRISON` 移入 nonDerivedStatuses 旧档兼容集）、配置三件套（law 12 条/loyalty 断言删，**MIN_AGE/MAX_AGE 保留**）、协议族（Payload.Purchase 3→2 参、年结 JSON 去 age/grievingAge、loyaltyDelta→moralityDelta）、`DiffTraitEffects FLOOR 100→90`、`CultivationEventProcessor` 叛逃/偷盗 14 用例段整删、detekt 12+3 条清零。
- **裁量项（主线程复核采纳）**：① `RoomMigrationTest:241-244/:881-884` 的 `index_disciples_loyalty/age` 断言**保留**——目标是历史态 v39/v31（当时索引存在、历史迁移 SQL 按 F2 冻结，断言仍绿，历史保真优先；链尾 v55 由新增用例经 ALL_MIGRATIONS 校验）；② `StorageSystemBenchmark` 本地 proto `val age` 保留（测试自足载荷）。
- **其生产发现主线程处置**：`GameEngineExplorationNativeOps:146/:221`「授予魂力/winAttr」注释 → 已改 TOCTOU 重查现状；`GameConfig` 政策忠诚常量 = 已登记保留 #10/#11；`DiffAuthoritativeTick` w3-13 注释语义待核（登记）。

### G10 Kotlin 侧平移登记（c340 交付，基线一律未动）

| # | 测试 | 归因 | 处置 |
|---|---|---|---|
| **K1** | `DiffMonthSettlementTest.child birth matches Kotlin bit-for-bit` | 绝对金黄锚（新生儿名/体质对/资质80）依赖月结 SYSTEM 序；G02 删叛逃+偷盗兜底+教化钩子 → 序平移 | **保持原样，G10 权威重录**（唯一硬红候选） |
| K2 | DiffMonthSettlement 主/采购/12月/关卡/排班/生育 SYSTEM 终态 | 消费序平移；相对对拍自平衡（配对 4 组合经 partnerId 预置保持） | 登记备查，预期绿 |
| K3 | `GameEngineTraitAdd/Wash` 随机解析分支 | 15 天赋/10 词条下线 → 池索引空间平移 | 红则按平移重录，不改基线 |
| K4 | DiffAuthoritativeTick/Year/Phase/Mission/State/DiscipleFactory/Government/TraitEffects | 同类平移，均相对/动态注册表对拍（资质82=id 散列非 RNG） | 预期绿，登记备查 |

（与 C++ 侧 T 代理登记的 4 例并表，共 **8 条 G10 重录项**。**终局核销**：带 `-Dgamecore.jni.path` 全量实跑后 Kotlin 侧 K1-K4 **全部实测绿**（K1 预测的硬红未发生——c340 的 partnerId 预置保序设计生效；真分歧项 DiffDiscipleFactory 经 #10 根因修复转绿）→ Kotlin 侧零待重录，G10 待重录项 = C++ 4 例。）

### S · Kotlin age/lifespan 消费方清扫 ✅（42 落点全清，~50 主源文件）

- **核心语义改写**：`RecruitIntegrity.isSamePerson` = 纯签名相等（容差/`MAX_REASONABLE_AGE` 删）、`isValidRecruit` 无年龄校验；`BuildingServiceSlotOps` `age<5` → `realmLayer==0`；`DisciplePillManager.PERMANENT_LIFE` 资格恒过（对齐 C++ `return true`）；`applyAliveUpdates` 年龄推进块整体删、`cause="age"`→`unknown`、`BereavementRecord` 去 age；`processAging`(AI) 收敛为原样返回、AI 生成删 `16+rng.nextInt(14)` 与 lifespan 块；`RecruitService` 老化 map/常量/seed age 删（native 净化臂 1632 保留）；`DiscipleFactory` `computeLifespan`/`DiscipleSeed.age` 删（三调用点同步）；StatCalculator 族 `lifespanPenalty` 全链删（修炼 statusBonus=政策−丧亲、突破 statusPenalty=丧亲、`calculateLifespan*` 三函数删）；特质洗炼/追加 `syncLifespanForTraitChange` 删；`applyLifeExtend`/`extendLife` 购买装配删；准入门 6 文件过滤收敛 `realmLayer>0`；`寿命` InfoItem 与「寿元将尽」明细删；全仓「N岁：」日志前缀 → 动作句；G03 域（ChildBirth/Partner/RelativeGift/CombatService 悲痛）与 §6.10（Redeem 构造实参删、`resolveAgeAndLifespan` 保留）均最小剥离。
- **S 登记项主线程裁决执行**：`grievingAge` 三端剥离（C++ struct+2 处序列化 / Kotlin 字段+2 处解析+文案「N岁：」→ 0 前缀）、`AgedDeathDraft.age` 与 `cause` 缺省 `"age"→"unknown"` 对称删改、5 处纯注释收口（Coordination/CaveExploration/MonthlyOps/DiscipleTables/LifeLogDialog）、「陨落（寿元耗尽）」→「陨落」、`DisciplePurchaseService` extendLife 购买装配删（与 pillToItemEffect 对称）。
