# TASKBOOK-G15 · 师徒系统整体下线

> **本文件是 G15 派工细则的唯一真源**（口径与 [`HANDOVER-m1-remaining-3.md`](HANDOVER-m1-remaining-3.md) §10 一致，
> 但 §10.2 的文件面经本轮 §3.4 侦察缺口回查后**已被本文件取代**）。
> 通用门禁基线、九条实操坑、A/B 类判据、五步循环见 HANDOVER-3 §2/§3，本文件不重复。
> **更新时点**：2026-09-25 开工前实测侦察（HEAD `a055bf9fe`）。所有行号是快照，执行时逐处 grep 复核。

---

## 1. 范围与产品口径

**P-1 拍板（2026-09-24）**：连根拆掉整个师徒玩法，不只是删「关系」对话框 UI。

删除面（八项）：

| # | 面 | 内容 |
|---|---|---|
| 1 | 关系列 | `Disciple.social.masterId` / `DiscipleTables.masterIds` / C++ `DiscipleStore::masterIds` / Room `disciples.social_masterId` / proto `masterId` |
| 2 | 拜师事务 | ActionId 1591 `DISCIPLE_LIFECYCLE_APPRENTICE`（保号退役）+ C++ `apprenticeTransaction` + Kotlin native 臂与回退臂 |
| 3 | 师徒加成 | 修炼速度乘区 + 突破率乘区（境界差算术，零 RNG）——含 `masterDiscipleBonus` **形参整链拆除** |
| 4 | 赠礼系统 | `relative_gift.h` + `RelativeGiftHandler.kt` + `GiftRelationshipType.kt` **三文件整删**（G03 已把关系收到只剩师徒 2 类，删师徒 ⇒ 赠礼系统整体消失）+ **两个**消费方接线摘除 |
| 5 | UI | `MasterApprenticeSelectDialog.kt` + `DetailActionButtons.kt`（整个文件只剩 RelationsDialog，师徒是其唯一内容）+「关系」「拜师」两个按钮与对话框状态 |
| 6 | 死亡解绑 | `DiscipleLifecycleProcessor.unbindMasterColumns` |
| 7 | 日志重建 | `DiscipleLifecycleManager` 的「拜X为师」事件重建段 |
| 8 | 死配置 | `GameConfigData.RelativeGiftSection`（全仓零消费者的纯默认段，`assets/config/game_config.json` 内亦无此键） |

**保留面（不得顺手删）**：

- `viceSectMaster`（副宗主）、`preachingMasters` / `qingyunPreachingMasters` / `PeakPreachingMasterConfig`（讲道）、
  `MASTER_TEACHING_*` / `mastersBonus`（讲道传授加成）、`manualMasteries` / `MasteryLevel`（功法精通）、
  `GiftService` / `diplomacy_tx` 赠礼（外交赠礼，与师徒无关）——**这些都命中 `master` 关键词但是假阳性**。
- `LifeEventDraft` / `lifeEvents`（仍服务突破日志，report-G03 §二-4 口径）。
- `FAVOR_GIFT` 1501（好感赠送，G03 已确认保留）。
- `DiscipleChatDialog` / 好感面（好感 ≠ 师徒）。
- 年报 `annualNewDisciples` 等计数。
- `disciples` 表 5 个 `Index` 声明（与 masterId 无关，重建表时原样带回）。

**架构级判定（用户公约 11）**：本批是**简单删除批**，不是架构问题。唯一触及架构的是
`masterDiscipleBonus` 作为**公式形参**存在于 `DiscipleStatsProvider` 接口与 C++ `disciple_stats.h` 两个 input struct——
拆它是让接口回归「只带活乘区」，属正常收敛，不改架构。

---

## 2. §3.4 侦察缺口回查结论（🔴 HANDOVER-3 §10.2 的漏项，全部已补入本文件）

### 2.1 交叉归属回查

| 漏项 | 后果若未补 | 归属 |
|---|---|---|
| `battle_residual_tx.h` 是 relative_gift 的**第二个**消费方（include + `processGiftsForBreakthrough` 调用） | 整删 `relative_gift.h` 后 C++ 编译失败 | T-15b |
| `GameCoreJni.cpp` 有对拍端口 `opName == "masterDiscipleBonus"` | 删 `disciple.h` 三函数后编译失败；`DiffDiscipleTest` 红 | T-15b |
| `models.h` `Disciple::masterId` + `json_codec.cpp` `GC_TO/GC_FROM` + `gameview_encode.cpp` 列规格 + `game_view.proto` 字段 | 违反铁律 6（三端链同 commit），首轮 JUnit A 类爆红 | T-15c / A15-d |
| `disciple_stats.h` 两处 input struct 的 `masterDiscipleBonus` 字段 + 两处乘区聚合式 | C++ 侧乘区残留死参 | T-15b |
| `game_core.cpp` `kBoundaryColumns[]` 含 `DiscipleColumn::MasterId` | 编译失败 | T-15c |
| `SocialData` 类删空后必须**整类拆除**（不留空数据类），连带 `@Embedded(prefix="social_")`、`AssembleGroup.SOCIAL` 组、`writeSocialFields`、`assembleSocial` | 空 `@Embedded` 数据类 + 死组装组 = 偷工减料尾巴 | A15-a |
| `DiscipleStatsProvider.kt`（`:core:domain`）四条方法签名各带 `masterDiscipleBonus` | 接口与实现漂移、8 处测试 fake 编译不过 | A15-c |
| `GameSystemRegistryDefaults.kt` 有 `register("engine.service", "RelativeGiftHandler")` | `GameSystemRegistryCoverageTest` 守卫红 | A15-b |
| `ProtoNumberUniquenessTest.kt` 的 `discipleRetired` 常量表须追加 93 | 守卫不覆盖新退役号（§9.5 跨域同步点） | c340-15c |
| `GameConfigData.RelativeGiftSection` 死配置段 | 留死配置 | A15-c |
| `NullSafeProtoBuf.kt` / `Serializers.kt` 的 KDoc 以 masterId 为示例 | 注释失真（`rules/code-comment.md` 七项） | A15-d |
| `YearSettlementResidualExecutor.kt` KDoc 提「师徒解绑」 | 注释失真 | A15-b |
| `DiscipleService.kt:145` / `DiscipleFactory.kt:85,122` / `GameViewDiscipleRows.kt:211,270` 构造 `SocialData()` | 删类后编译失败 | A15-b / A15-d |

### 2.2 假阳性剔除（HANDOVER-3 §10.2 误列入，本批**不改**）

- `SectViewModel.kt`、`ProductionViewModelElderOps.kt` —— 命中项是 `viceSectMaster`，与本批无关。
- `DiscipleStatsProvider.kt` 原未列 → **改为需动**（见上表）。

### 2.3 三端环逐环确认（非弟子列不适用；本批删的是弟子列，按 §3.4-2 的另一形状核对）

| 环 | 落点 | 片 |
|---|---|---|
| C++ 结构字段 | `models.h:383 std::string masterId;` | T-15c |
| C++ SoA 列 | `disciple_store.h:113 MasterId`（枚举）+ `:236 masterIds`（vector） | T-15c |
| C++ 列↔结构 六面 | `disciple_store.cpp` materialize / upsert / reserve / clear / erase / swap（6 处） | T-15c |
| C++ 脏列名表 | `column_dirty.h` `nameOf` + `serialize`（2 处 switch case） | T-15c |
| C++ JSON 编解码 | `json_codec.cpp` `GC_TO` + `GC_FROM` | T-15c |
| C++ 审计写列并集 | `game_core.cpp` `kBoundaryColumns[]` | T-15c |
| C++→Kotlin 镜像编码 | `gameview_encode.cpp` `{"masterId", 85, RowKind::kString}` | T-15c |
| proto | `game_view.proto:207 optional string masterId = 85` → `reserved 85; reserved "masterId";` | A15-d |
| Kotlin 镜像解码 | `GameViewMirrorCodec.kt:520 str("masterId", …)` | A15-d |
| Kotlin 行应用 | `GameViewDiscipleRows.kt` 4 处 | A15-d |
| Kotlin 列表 | `DiscipleTables.kt` + `ColumnRegistry` + `Assemblers` + `Write` | A15-a |
| Kotlin 域模型 | `Disciple.kt` `@Embedded social` / `DiscipleComponents.kt` `SocialData` / `DiscipleExtended.kt` / `DiscipleAggregate.kt` | A15-a |
| Kotlin 序列化 | `DiscipleSerializer.kt` `@ProtoNumber(93)` + `OldSerializableSaveData.kt:257` → 两处 `reserved 93` | A15-d |
| Room | `disciples.social_masterId` → v59 删列 | **主线程** |
| `lock_beast_tx.h` SETTINGS_PATCH | **不适用**（本批不删 `GameData` 设置字段） | — |

---

## 3. Room / ActionId / ProtoBuf 协议面

### 3.1 Room v58 → v59

- `GameDatabaseConfig.DATABASE_VERSION` = **59**（禁止硬编码到他处）。
- 新文件 `core/data/src/main/java/com/xianxia/sect/data/local/GameDatabaseMigrationsV59.kt`
  （模板 = `GameDatabaseMigrationsV58.kt`）：`V59_DISCIPLES_DROPPED_COLUMNS = listOf("social_masterId")` +
  `MIGRATION_58_59 = object : Migration(58, 59)`，走 `rebuildTableDroppingColumns`，
  `pkColumns = listOf("id","slot_id")`，**索引 5 条原样带回**（name / realm+realmLayer / isAlive+realm / isAlive+status / discipleType）。
- `ALL_MIGRATIONS` 追加 `MIGRATION_58_59`；`GameDatabase` KDoc 追加 v59 行。
- `schemas/com.xianxia.sect.data.local.GameDatabase/59.json` 由 KSP 产出并入库（`disciples` 91 → **90 列**，`game_data` 128 列不变）。
  🔴 坑 1：KSP 若就地改写历史 `54.json…58.json` → `git checkout --` 还原，只允许新增 `59.json`。
- 新测试 `RoomMigrationV58To59Test.kt`（模板 = `RoomMigrationV57To58Test.kt`）：种子非零 `social_masterId` →
  迁移后列消失、5 索引回来、其余 90 列逐格全等、行数行序不变。
- 🔴 **既有迁移测试不改**：`GameDatabaseMigrationsV2ToV10 / V21ToV30 / V39 / V57` 与
  `RoomMigrationLegacyTest / RoomMigrationTest / RoomMigrationV43To46Test / RoomMigrationV56To57Test / RoomMigrationV57To58Test`
  里的 `social_masterId` 是**历史 schema 事实**，迁移链不可改写。
  ⚠️ **`RoomMigrationSupport.verifyDisciplesColumnsExist` 实测判定（本文件初版的疑问已关闭）**：
  它由 `verifyFullChainColumns` 调用，而**该链尾是 v40 不是当前版本**（`RoomMigrationTest` 的
  v2→v39/v40 两条链用例）⇒ 在 v40 端点 `social_masterId` **仍在场**，判据必须保持 `assertTrue`。
  v59 端点的「列已消失」判据落在新建的 `RoomMigrationV58To59Test` 内自证，不复用该 helper
  （误改为 `assertFalse` 会让 `RoomMigrationTest` 全链用例红——首轮实测已撞到，据此改回）。

### 3.2 ActionId 1591 退役

路线与 G02/G03/G04/G05/G06 一致：**catalog 保留条目 + 保号 + desc 标废弃**（口径 3，禁中途换路线）。

1. `scripts/action-catalog/core.mjs:208` desc 改为 `【已退役，编号禁复用】拜师事务（师徒玩法整体下线）`；
   同段注释「拜师/年俸开关两事务」→ 只剩年俸开关，并把 1591 挪进「已退役」列举。
2. `execute_dispatch.cpp`：删 `case action::DISCIPLE_LIFECYCLE_APPRENTICE` 整块；
   函数头 KDoc 与 `handleDiscipleLifecycleTx` 注释同步。
3. 🔴 **分派区间起点迁移**：`actionId >= DISCIPLE_LIFECYCLE_APPRENTICE(1591) && <= DISCIPLE_LIFECYCLE_SALARY_TOGGLE`
   → 改为 `actionId >= DISCIPLE_LIFECYCLE_SALARY_TOGGLE(1594) && <= DISCIPLE_LIFECYCLE_SALARY_TOGGLE`
   （单值区间，照同文件 `SECT_ATTACK_REMOVE_DEAD_DEFENDERS_TX` 先例）。
   ⚠️ **理由校正（实施期实测——本文件初版「区间起点留在 1591 会让 `dispatch_guard_test` 变红」是错的）**：
   `isDispatchGap()` 同时接受 `NOT_IMPLEMENTED` **和** `UNKNOWN_ACTION`，而 `handleDiscipleLifecycleTx` 的
   `default:` 正返回 `UNKNOWN_ACTION` ⇒ 即便区间起点不动，1591/1592/1593 仍被判为分派缺口、退役断言照样绿。
   迁移的**真实理由**是语义正确性：退役编号不该被路由进活领域 handler 再靠 `default:` 兜底，而应落到
   `GameCore::execute` 的最终 `NOT_IMPLEMENTED` 兜底；断裂区间同时是给后人留的复用陷阱。
   已核实 1591–1593 不被任何 W4 端口或前序区间认领 ⇒ 迁移后退役断言仍成立。
4. `test/dispatch_guard_test.cpp` `retired` 集追加 `action::DISCIPLE_LIFECYCLE_APPRENTICE, //1591 师徒下线（G15）`
   → 退役集 **21 → 22**。
5. `node scripts/gen-action-ids.mjs` regen 双产物；零漂移自证 = `git diff --exit-code` 双产物。
   目标：**198 动作 / maxId=1861 不变**。

### 3.3 ProtoBuf

- `DiscipleSerializer.kt`：删 `@ProtoNumber(93) val masterId` → 在既有 reserved 注释块追加
  `// reserved 93;（masterId 字段号已退役，禁止复用）`。
- `OldSerializableSaveData.kt`：同法处理 `SerializableDisciple` 的 93。
- `game_view.proto:207`：删字段 → `reserved 85;` + `reserved "masterId";`。
- 守卫：`ProtoNumberCoverageTest`、`ProtoNumberUniquenessTest`（后者 `discipleRetired` 常量须 +93）。

---

## 4. C++ 乘区与对拍（🔴 确定性注意项）

- `disciple_stats.h`：`CultivationRateInput::masterDiscipleBonus`、`BreakthroughChanceInput::masterDiscipleBonus` 删除；
  两处聚合式改为
  `socialBonus = extra.preachingElderBonus + extra.preachingMastersBonus;`
  `selfBonus = in.pillBonus + comprehensionBreakthroughBonus(baseComprehension(d));`
  🔴 **剩余项的相加顺序不得重排** —— `x + 0.0 == x`（IEEE754）保证删项前后结果逐位相同，
  但 `(a+b)+c` 与 `a+(b+c)` 不等价。改完必须实测 `Diff*` 家族保持绿（它们比的是 C++ == Kotlin legacy，非黄金值）。
- `phase_settlement.h`：删 `masterBonusFor()`、两处 `extra.masterDiscipleBonus = …`、突破段的
  `in.masterDiscipleBonus` 提取块、`relative_gift` include + `processGiftsForBreakthrough` 调用，
  以及 7 处提及师徒的注释（25/53-54/1201/1322/1335/1339/1360）。
  🔴 删赠礼调用后，**SYSTEM 分区每亲属一次 `nextDouble` 的消费归零** ⇒ 旬/年序列二次平移 →
  按 §3.3 判据登记 B 类，**G15 内不重录**。
- `battle_residual_tx.h`：删 include（19 行）+ 循环内赠礼调用（269 行）——注意循环体其余
  （境界日志草稿 / `recordGameEvent`）必须保留，删的只是 `if ((realmChanged || layerChanged) …)` 赠礼段，
  并把 `rngSystem` 局部变量按实际是否还有别的用途决定去留（不留死变量）。
- `GameCoreJni.cpp`：删 `opName == "masterDiscipleBonus"` 分支 + 端口注释行 615 的示例。
  ⚠️ `external fun` 计数不变（分支在既有函数体内），`check-jni-count.mjs` 仍应 **86 / 86**。
- `disciple.h`：删 `getMasterDiscipleRealmGap` / `getMasterDiscipleCultivationBonus` /
  `getMasterDiscipleBreakthroughBonus` + `kMasterCultBonusPerGap` / `kMasterBreakBonusPerGap`（定义处 grep 定位）
  + 「── 师徒 ──」分节标题。
- `disciple_lifecycle_tx.h`：删 `apprenticeTransaction`、`ApprenticeResult`、`detail::countAliveApprentices`、
  `detail::kMaxApprenticesPerMaster`；`clearAllDiscipleSlotsForRemoval` 若拜师是唯一调用方则一并删
  （🔴 实测：G06 已登记它「零调用方」→ 本批删拜师后确认其唯一消费者，归零则删；见 report-G06 §G10 登记）。
  头注释重写为「单事务（境界年俸开关）」口径，RNG 契约行同步。

---

## 5. 切片表（🔴 ≤10 文件/片，共 12 片 + 主线程面）

> 分片文件面**互斥**；每片只允许改「允许改」列出的文件，越界即回退。
> 🔴 子代理在途期间整棵树不可编译（跨片签名互锁），**禁止任何分片跑 Gradle / cmake**；
> 只做精确编辑 + 逐处 grep 自检 + 交回「改动清单 + 遗留不确定点」。全部门禁由主线程终树复跑。

| 片 | 文件清单 | 要点 |
|---|---|---|
| **T-15a** | `system/disciple_lifecycle_tx.h`、`src/execute_dispatch.cpp`、`test/dispatch_guard_test.cpp`、`scripts/action-catalog/core.mjs` | §3.2 五步；区间起点迁移 |
| **T-15b** | `system/disciple.h`、`system/disciple_stats.h`、`system/phase_settlement.h`、`system/battle_residual_tx.h`、`system/relative_gift.h`（**整删**）、`jni/GameCoreJni.cpp`、`test/relative_gift_test.cpp`（**整删**）、`test/disciple_test.cpp`、`test/disciple_lifecycle_tx_test.cpp`、`test/CMakeLists.txt` | §4；`disciple_test.cpp` 删 `MasterDiscipleTest` 两用例；`disciple_lifecycle_tx_test.cpp` 删拜师用例保留年俸用例 |
| **T-15c** | `state/models.h`、`state/disciple_store.h`、`src/disciple_store.cpp`、`state/column_dirty.h`、`src/json_codec.cpp`、`src/gameview_encode.cpp`、`src/game_core.cpp` | §2.3 列环；枚举项删除会平移后续索引 ⇒ 只按名/按枚举符号引用，不得留裸数字 |
| **A15-a** | `core/domain/…/model/Disciple.kt`、`DiscipleComponents.kt`、`DiscipleExtended.kt`、`DiscipleAggregate.kt`、`state/DiscipleTables.kt`、`DiscipleTablesColumnRegistry.kt`、`DiscipleTablesAssemblers.kt`、`DiscipleTablesWrite.kt`、`AssembleGroup.kt` | `SocialData` 整类拆 + `AssembleGroup.SOCIAL` 组拆（ordinal 位图自动收窄，确认无他处按名取 SOCIAL） |
| **A15-b1** | `engine/domain/disciple/DiscipleMasterApprenticeService.kt`（**整删**）、`DiscipleService.kt`、`DiscipleFacade.kt`、`DiscipleFacadeImpl.kt`、`DiscipleLifecycleNativeTx.kt`、`DiscipleLifecycleManager.kt`、`DiscipleFactory.kt`、`engine/GameEngineDiscipleOps.kt` | `DiscipleService` 去 `discipleMasterApprenticeService` 构造依赖与 `apprenticeToMaster` 转发；`DiscipleFactory.SeedData` 去 `social` 字段；`DiscipleLifecycleNativeTx.kt` 删事务 2 后确认 `appendLifeEventDraft` 等辅助是否零消费者（G06 登记的「拜师信封」面） |
| **A15-b2** | `engine/service/RelativeGiftHandler.kt`（**整删**）、`DiscipleLifecycleProcessor.kt`、`YearSettlementResidualExecutor.kt`、`registry/GameSystemRegistryDefaults.kt` | 删 `unbindMasterColumns`；`DiscipleBreakthroughHandler` 的赠礼依赖在 A15-c；`GameSystemRegistryDefaults` 去注册行否则 `GameSystemRegistryCoverageTest` 红；`YearSettlementResidualExecutor` 只改 KDoc |
| **A15-c** | `core/domain/…/model/DiscipleStatsProvider.kt`、`engine/domain/disciple/DiscipleStatCalculator.kt`、`DiscipleStatCalculator属性Ops4.kt`、`DiscipleStatCalculator修炼Ops6.kt`、`DiscipleStatCalculator突破Ops5.kt`、`engine/service/CultivationRateCalculator.kt`、`engine/service/DiscipleBreakthroughHandler.kt`、`core/domain/…/config/GameConfigData.kt`、`app/…/XianxiaApplication.kt` | 乘区签名链：`DiscipleStatsProvider` 4 个方法去 `masterDiscipleBonus` ⇒ 实现体（XianxiaApplication 匿名实现）+ 全部调用点同步；`BreakthroughBonusDetail` 去该字段（UI 展示行同步，见 A15-e） |
| **A15-d** | `engine/gameview/GameViewDiscipleRows.kt`、`engine/nativebridge/GameViewMirrorCodec.kt`、`core/engine/src/main/proto/game_view.proto`、`core/data/…/serialization/Serializers.kt`、`NullSafeProtoBuf.kt`、`backwardcompat/OldSerializableSaveData.kt` | §3.3 + 注释示例改写 |
| **A15-e** | `feature/game/…/components/detail/MasterApprenticeSelectDialog.kt`（**整删**）、`DetailActionButtons.kt`（**整删**，文件唯一内容是 RelationsDialog 族）、`DetailRightPanel.kt`、`DiscipleDetailScreen.kt`、`DetailBasicInfoSection.kt`、`DetailCultivationSection.kt`、`delegate/DiscipleDelegate.kt` | 「关系」「拜师」两个操作按钮与对话框状态全拆；`DetailUiState`/`onShowRelations`/`onShowApprentice` 回调面收窄 |
| **主线程** | `GameDatabase.kt`、`GameDatabaseMigrationsV59.kt`、`59.json`、`RoomMigrationV58To59Test.kt`、双 changelog、`report-G15.md`、本文件、所有提交与门禁 | §3.1 |

**测试面（c340，Wave 2 派工，主源编译通过后）**

| 片 | 文件 |
|---|---|
| c340-15a | `engine/service/RelativeGiftHandlerTest.kt`（**整删**）、`engine/domain/disciple/DiscipleServiceApprenticeTest.kt`（**整删**）、`DiscipleLifecycleNativeTxGateTest.kt`、`DiscipleLifecycleEventsTest.kt`、`DiscipleServiceCrudTest.kt`、`DiscipleLibrarySlotSwapTest.kt` |
| c340-15b | `DiscipleStatCalculatorCombatBonusTest.kt`、`CultivationRateEquivalenceTest.kt`、`CultivationCoreTest.kt`、`CultivationCoreProficiencyNurtureTest.kt`、`BattleSystemTest.kt`、`AISectAttackManagerTest.kt`、`SecretRealmRestAreaTest.kt`、`DiscipleBreakthroughHandlerTest.kt` |
| c340-15c | `DeathPipelineEquivalenceTest.kt`、`DiscipleLifecycleProcessorTest.kt`、`SalaryPlanColumnEquivalenceTest.kt`、`DiscipleFactoryTest.kt`、`DiffDiscipleFactoryTest.kt`、`DiscipleModelsTest.kt`、`AssemblePatchEquivalenceTest.kt`、`GameViewDiscipleColumnApplyEquivalenceTest.kt`、`nativebridge/Diff*`（`DiffDiscipleTest`/`DiffPhaseSettlementTest`/`DiffMonthSettlementFixture`/`DiffYearSettlementTest`/`DiffAuthoritativeTickTest`）、`MirrorProtoFeed{Fixture,EquivalenceTest}`、`ProtoNumberUniquenessTest`、`RoomMigrationSupport`（若 §3.1 判定需改） |

---

## 6. 旧用例处置表（填全后交 report-G15）

| 用例 | 处置 | 理由 |
|---|---|---|
| `DiscipleServiceApprenticeTest` | 整类删 | 拜师链下线 |
| `RelativeGiftHandlerTest` | 整类删 | 赠礼系统下线 |
| `relative_gift_test.cpp` | 整删 | 同上 |
| `disciple_lifecycle_tx_test.cpp` 拜师用例 | 删；年俸用例保留 | 事务 1 下线、事务 2 存续 |
| `MasterDiscipleTest.{RealmGap,CultivationBonus}` | 整删 | 三函数下线 |
| `DeathPipelineEquivalenceTest` 师徒解绑断言 | 改断言：删解绑面，保留死亡面 | 解绑随列消失 |
| `CultivationRateEquivalenceTest` 师徒乘区用例 | 删该用例，其余保留 | 乘区下线 |
| `BreakthroughBonusDetail.masterDiscipleBonus` 相关断言 | 改断言 | 字段下线 |
| `DiffDiscipleTest` `op=masterDiscipleBonus` 用例 | 整删 | JNI 端口下线 |
| `RoomMigrationV56To57Test` / `V57To58Test` 的 `social_masterId` 存续断言 | **不改** | 历史 schema 事实（v56/v57/v58 确实含该列） |

---

## 7. 门禁目标值（终树复跑，逐门贴真实输出）

| 门 | 目标 |
|---|---|
| `cmake --build .` + `ctest`（desktop-test） | EXIT=0；ctest 总数较 G04 的 1413 减少（`relative_gift_test.cpp` 整删 + 拜师/师徒加成用例删），失败集 ⊆ {3 条 B 类} ∪ {本轮新增 B 类} |
| `compileReleaseKotlin` | EXIT=0 |
| 五模块 `compileReleaseUnitTestKotlin --max-workers=1 --continue` | 0 错误 |
| 全量 JUnit（重建 JNI 库后，带 `--continue`，按 XML 逐模块汇总） | 0 失败；总数较 G04 的 7397 减少（整删 2 个测试类） |
| `detekt`（六模块） | EXIT=0，baseline 只缩不增 |
| `lintRelease` | BUILD SUCCESSFUL |
| `scripts/build-desktop-jni.ps1` | EXIT=0（改过 C++ 必重建） |
| `check-jni-count.mjs` | **86 / 86** |
| `gen-action-ids.mjs` + `git diff --exit-code` 双产物 | **198 / maxId=1861**，零漂移 |
| `gen-game-data.mjs --check` | sha256 不变（本批不动配置源）；若变则记录新值并说明 |
| `check-agent-instructions.mjs` | EXIT=0 |
| Room | `DATABASE_VERSION=59`、`59.json` 入库、`disciples` **90 列**、`game_data` 128 列、索引 5+5 |

---

## 8. 完成报告格式（report-G15.md 必含）

1. 做了什么（分类表：①搬迁 ②删除或只读 ③命令进 C++/回执出 C++）
2. 门禁实跑数值（不得写「应该通过」；🔴 **报告声称绿不作证据**，须复核会话同轮重跑）
3. §6 旧用例处置表（逐条）
4. 本批新增 B 类清单（交 G10）
5. 「G10 登记」逐条（含诚实发现的预存问题）
6. 独立 grep 终态双向贴证：删除模式全 0 **且** 保留清单命中
7. 树指纹（门禁前后各一次 `git rev-parse HEAD` + `git status --porcelain` 行数）
