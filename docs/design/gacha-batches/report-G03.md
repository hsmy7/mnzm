# G03 · 删生育 / 道侣 / 亲缘（`parentId` 全链） — 批次报告

> 批次：G03（M1 序列第 4 批，深度 4 = 最重批次；前置 G02 `5dbaac1e3` + G05 `8e593a71b` + G06 `e1a69d8e9`）
> 侦察：`recon-G02-G03.md` `## G03`（§G03-0~8）+ §9.7；**行号系 G02 前快照，施工前逐处 grep 复核**（实测到多处漂移，见 §三）
> 执行协议：`EXECUTION-PROTOCOL.md`；交接：`HANDOVER-m1-remaining-2.md` §4（本批为失败复跑，首次尝试已全量回退未产生提交）
> 分片：A1（Kotlin 字段链）→ T1（C++ 结算）/ T2（C++ 列链+ActionId）/ A2（feature:game UI）/ A3（core:engine+配置源）/ c340（测试）→ S1/S2/S3（测试编译收尾）+ 主线程集成

## 一、做了什么

### 1. 删除面（三端）

| 端 | 删除/收缩 |
|---|---|
| **C++ 结算** | `child_birth.h` **整文件删**（223 行：`kRootCountWeights`/`rootElements`/`generateSpiritRoot`/`createChild`/`processMonthlyBirth`）；`month_settlement.h` 摘 include + `processChildBirthStep` + 调用，**月结自动配对整段删**（`kPairingProbability`/`kPairingMinAge`/`hasBloodRelation`/`processPartnerMatching` 含 marriage 事件 + 调用）；`year_settlement.h` 删 `isRelatives`/`applyGriefToRelativesStep`/`unbindPartnerColumnsStep`/哀悼到期段/`BereavementDraft` + 导出 + 哨兵；`battle_residual_tx.h` 删 `applyGriefToRelativesBattle`/丧亲 `LifeEventDraft` 消费链；`phase_settlement.h` 删 `parentBonusFor` + 两接入点（**`masterBonusFor` 保留**）；`disciple_lifecycle_tx.h` 删 `MarriageApproveResult`/`approveMarriageTransaction`/`rejectMarriageTransaction`，头注释「三事务」→「拜师/年俸开关两事务」；`execute_dispatch.cpp` 摘婚姻两处派发 |
| **C++ 赠礼收缩** | `relative_gift.h` 438→约 280 行，**仅师徒**（`kMasterGiftProb=0.40`/`kApprenticeGiftProb=0.30`），删 partner/parent/child/sibling 概率常量、正向/反向查找与分类、优先级排序；`GiftRelationshipType` 枚举收缩为两类。`phase_settlement.h` 的赠礼接线（include + 调用）**保留** |
| **C++ 列链三端环** | 亲缘 **7 列**（`partnerId`/`partnerSectId`/`parentId1`/`parentId2`/`lastChildYear`/`childBirthMonth`/`griefEndYear`）全环删除：`models.h` → `DiscipleColumn` 枚举（**列索引整体平移**）→ `disciple_store.h` → `column_dirty.h`（`nameOf` + serialize）→ `json_codec.cpp`（GC_TO/GC_FROM）→ `gameview_encode.cpp`（`{78}`-`{84}` 字段表）→ `game_view.proto` → `GameCoreJni.cpp` → `game_core.cpp` 边界列 |
| **C++ 公式乘区** | `disciple_stats.h`：`CultivationRateInput.parentCultivationBonus` 字段删（两处社交乘区组装同步）；`BreakthroughChanceInput.griefBreakthroughPenalty` 字段删 + `penaltyMult` 项删；`disciple.h`：`kGriefCultivationPenalty`/`kGriefBreakthroughPenalty`/`getParentSpiritRootBonus`/`isGrieving` 删（后两者仅有测试直调、零生产消费者） |
| **C++ 战场哀悼** | `sect_defense_battle.h`：`kGriefEndYearNone` + `isRelativeOf` + `propagateGriefToRelatives` 整块删（**侦察 §9.7 判归 G03 但未进 §G03-2 落点表 → 任务书漏项，主线程补做**，详见 §五-1） |
| **Kotlin 系统/UI** | 整文件删：`ChildBirthSystem.kt`、`PartnerSystem.kt`、`PendingMarriageProposal.kt`、`DaoCompanionManagementDialog.kt`、`GameNotificationDialog.kt`（全文仅 `MarriageApprovalDialog` 一个声明）、`DiscipleStatCalculator修炼Ops7.kt`（亲属判定 `areRelatives`）；`GameEngine` 的 `approveMarriageProposal`/`rejectMarriageProposal` + `pendingMarriageProposals`；`GameOverlayHost` MarriageApproval 整段（含 `currentProposal`/`marriageProposalVisible`/私有 Composable + 参数链掐净）；`DiscipleDelegateLifecycleOps` 两扩展；`SectManagementDialog`「道侣管理」按钮 + `onDaoCompanion` 参数链；`AutoAssignDelegate` 两项 daoCompanion 设置；`DetailActionButtons.RelationsDialog` **收缩为仅师父/徒弟**（删 5 组亲属 remember + `RelationsData`；`RelationCategory`/`RelationItem` 与拜师入口保留）；`DetailBasicInfoSection` 丧亲辅助 + `gameYear`/`griefBreakthroughPenalty` 形参；`DetailCultivationSection` 丧亲条目 + 公式 `penaltySum` 分支；`DiscipleDetailScreen` 的 `gameYear` 四层死链整掐；`DiscipleLifecycleProcessor` 哀悼/道侣解绑段（**`unbindMasterColumns` 保留**） |
| **Kotlin 字段链** | 7 social 列 + `GameData.daoCompanion*` 两字段，全环：`DiscipleComponents`/`DiscipleSerializer`（含 `ifEmpty`/`takeIf` 归一化摘除）/`DiscipleTables`/`DiscipleTablesColumnRegistry`/`DiscipleTablesAssemblers`/`DiscipleTablesWrite`/`AssembleGroup`/`GameViewMirrorCodec`/`GameViewDiscipleRows`/`GameDataFieldPatch`/`DiscipleExtended`/`DiscipleAggregate`/`DiscipleDelegates`/`Disciple`(`hasPartner`)/`DiscipleStatsProvider`(接口形参收窄)/`GameData`/`SectPolicyState`/`SectPolicyDomainState`/`OldSerializableSaveData`/`Serializers`/`NullSafeProtoBuf`/`StorageEngineWriteOps`/`GameViewStreamEvent`（`Payload.YearSettled` 删，`Kind.YEAR_SETTLED` 降级为纯在场标记）；`RelativeGiftHandler` 419→仅师徒；`GiftRelationshipType` 收缩 |
| **配置源** | `GameConfigData.RelativeGiftSection` 5 字段删（仅余 `masterGiftProb=0.40`/`apprenticeGiftProb=0.30`）；`GameConfig`/`game_config.json` 经 grep 实测**本无** pairing/daoCompanion/marriage 键（侦察为推测项，见 §三-3） |
| **Room** | **v56→v57**：`MIGRATION_56_57`（`rebuildTableDroppingColumns` create-copy-drop-rename）**3 表 11 列** —— `disciples` 7（`social_*`，`social_masterId` 保留）+ `game_data` 2 + `sect_policy_state` 2；`DATABASE_VERSION` 56→57 + `ALL_MIGRATIONS` 注册 + `@Database` 注释 + `schemas/57.json` 入库；`disciples` 102→95 列 |
| **ActionId** | `DISCIPLE_LIFECYCLE_MARRY_APPROVE=1592`（**core.mjs**）、`DISCIPLE_LIFECYCLE_MARRY_REJECT=1750`（**w4a.mjs**）→ 保号 + desc 改【已退役，编号禁复用】+ dispatch case 删 + `dispatch_guard_test` 退役集 +2；regen 后 **198 动作 / maxId=1861** 不变 |
| **ProtoBuf** | `SerializableDisciple` 与 `OldSerializableSaveData.SerializableDisciple` → `reserved 11,12,13,14,15,16,102`；GameData 级 → `reserved 102,103`；`game_view.proto` → `reserved 78,79,80,81,82,83,84`（四处均实测已登记） |

### 2. 改动规模
**166 文件 +5874 / −5142**。增行主体是新增产物（`57.json` 4600 行、`RoomMigrationV56To57Test` 361 行、`GameDatabaseMigrationsV57` 138 行）；删行主体是被下线系统（婚姻/生育/道侣测试与 `child_birth.h`）。

### 3. 🟢 悟性系统整体保留（本批口径变更，非删除面）
用户 2026-09-24 拍板「**保留悟性系统不做删除**」→ G04 删除面收窄为「洗炼/资质/天赋/体质/词条/血炼/职位特质/战斗随机成长」八项，**悟性不在其中**；§5#1 的「突破率公式无定义」缺口随之自动消解（口径已写入 `HANDOVER-m1-remaining.md` §4 口径 15）。
本批实测存续证据：C++ `comprehension` 81 处 / `comprehensionBreakthroughBonus` 4 处 / `baseComprehension` 8 处；Kotlin `comprehension` 90 处 / `ELDER_SKILL_BASELINE` 11 处。突破率公式现为
`chance = clamp( baseZone(realm,rootCount,layer) × (1 + 长老悟性指导 + 自身悟性 + 丹药 + 师徒) + 广告扁平, 0, 1 )`
—— 仅去掉随丧亲下线的 `× (1 − 丧亲罚)` 乘区（该乘区在生产侧已恒为 1，删除**不改变任何数值**）。

## 二、主线程裁决与补做记录

| # | 裁决/补做 | 依据 |
|---|---|---|
| 1 | **补做侦察 §9.7 的 C++ 战场哀悼块**（`sect_defense_battle.h` 的 `kGriefEndYearNone`/`isRelativeOf`/`propagateGriefToRelatives`）——该项 §9.7 明判「全部归 G03」，但 §G03-2 落点表未列，导致**五份任务书无人认领**；首次 G03 尝试失败的可能成因之一 | 主线程编译暴露 `no member named 'partnerIds' in DiscipleStore` 后回溯 §9.7 归属结论 |
| 2 | **补做 `game_core.cpp` 年结 `bereavements` 双写点**（视图事件 + 信封 JSON）——T2 撞轮次上限未收；`kYearSettled` 事件保留为**纯在场标记**（载荷 `"{}"`），因镜像 codec 本就 `Kind.YEAR_SETTLED -> null`，Kotlin 只用作「本年已结算」证明 | `GameViewMirrorCodec.kt:244` + `GameEngineCoreYearOps.kt:42-52` KDoc 自述 |
| 3 | **修 `GameEngine.kt` 重复 import**（A3 中断遗留，`:feature:game` 及全链被阻塞）+ 5 条孤儿 import | detekt 实跑 |
| 4 | **`BattleCasualtyOutcome::lifeEvents` 与 `LifeEventDraft` 保留**（任务书写「删」）——`LifeEventDraft` 仍服务**大境界突破日志**（同文件 `:193` 有真实填充点 `:277-281`），且 `battle_residual_tx_test.cpp:120` 断言空信封；删掉会打断活功能 | 逐点 grep 消费者 |
| 5 | **`disciple_stats.h` 社交乘区去掉 `parentCultivationBonus` 后 FP 加法序不变**：该字段自 G02 后无任何生产者（恒 0.0），`(a+b)+0.0+d ≡ (a+b)+d`（IEEE754 下 +0.0 无副作用），**不引入序列变化** | 生产者 grep 归零 |
| 6 | **`ai_sect_ops.h` 注释双处过期一并修正**：除丧亲措辞外，还写着「寿命惩罚参与」——该乘区随 G02 寿命面已消失，属预存失真 | `rules/code-comment.md` 只描述当前状态 |
| 7 | **`RoomMigrationV56To57Test` 两处 detekt 实修**（`seedDiscipleRows` 复杂度 18>15 → 改列名→字面量表驱动；`snapshotRows` 嵌套超限 → 抽 `Cursor.rowSnapshot` 扩展）；**未动 baseline**（红线：baseline 只缩不增） | detekt 实跑 |
| 8 | 🔴 **A 类缺陷根因修复：C++ `GameData` 的 daoCompanion 两字段整条链未删**（首轮 JUnit 29 红中 **16 条** 的共因）—— 症状是 `DiffEconomy/DiffInventory/DiffExecute/DiffNestedTypes/DiffSpiritField/NativeBenchmark` 全抛 `JsonDecodingException: Encountered an unknown key 'daoCompanionBannedRootCounts' at path: $.gameData.currentSlot`。因果链：Kotlin `GameData` 已删该两字段 → **C++ `models.h:1304/1307` 仍声明、`json_codec.cpp` 的 `to_json(GameData)` 仍 `GC_TO` 写出** → C++ 导出的 gameData JSON 带未知键 → Kotlin `ignoreUnknownKeys=false` 严格解析在**导入入口**即抛，故红点散落到与经济/库存逻辑毫无关系的对拍类。**这不是 RNG 序列平移（B 类），是协议漂移（A 类）**，已按根因修：`models.h` 两字段删 + `json_codec.cpp` `GC_TO`/`GC_FROM` 各两处删 + `lock_beast_tx.h` SETTINGS_PATCH 的 apply/read/whitelist 三处同步（字段总数 15→13、布尔 11→10、Int 集 5→4）+ `lock_beast_tx_test.cpp` 对应用例实参收窄（`appliedFields` 11→9）+ 四处 KDoc 口径 | `BaselineFieldCoverageGuardTest`（**本批为该漂移专门设计的守卫如实报警**：`基线字段表 ⊈ 序列化面: [daoCompanionBannedRootCounts, daoCompanionConsentRequired]`）+ 失败堆栈逐条实读 |
| 9 | **`GameStateStoreTransientQueueGuardTest` 期望清单同步**：瞬态队列守卫仍把 `_pendingMarriageProposalsFlow` 列为已知字段 → 换成在册活字段 `_pendingBattleRewardCardsFlow`，**保留该守卫「防字段改名后空转」的原始目的**而非删断言；类 KDoc 的「§0 补充发现：婚配提议队列…」历史表述随之收掉 | `rules/code-comment.md` + 守卫三要素② |

## 二·补 A 类缺陷的判据方法（避免误归 B 类）

首轮 JUnit 29 红里，**16 条集中在 `DiffEconomy/Inventory/Execute/NestedTypes/SpiritField` 家族**——这些类测钱袋与库存，与「生育/道侣/亲缘」**语义正交**。按交接文档 §6 的口径，`Diff*` 家族红很容易被顺手归成「SYSTEM 序列平移 = B 类」而放行，**那是错误归因**。实际判据是读失败堆栈：

| 观察 | 结论 |
|---|---|
| 异常类型 `JsonDecodingException`，非数值 diff | 不是序列漂移 |
| 抛出点是 `nativeCoreImportState`（状态**导入**入口），键名 `daoCompanionBannedRootCounts` | 三端字段链的 C++ 写出侧漏改 |
| 专用守卫 `BaselineFieldCoverageGuardTest` 同步报「基线字段表 ⊈ 序列化面」并**逐名列出这两个字段** | 协议漂移确证 |
| 修复后 `Diff*` 家族一并转绿（见 §六） | 因果链闭合 |

⇒ 判 A/B 类的依据是**失败消息的异常类型与抛出点**，不是失败用例所属的测试家族。

## 三、侦察快照漂移登记（施工前 grep 复核纪律的实证）

1. **`unbindMasterColumnsStep「必须保留」是失效标注**：`git log -S` 实测该符号在 **G02 `5dbaac1e3`** 删老死链时已从 C++ 消失，recon 与 `HANDOVER-m1-remaining-2.md` §4.3.1/§4.4-6 引用的都是 G02 前快照。**不是误删**；现役师徒解绑在 Kotlin `DiscipleLifecycleProcessor.kt:115/160`（完好，且该文件已零 partner/grief 引用）。
2. **§G03-4 四个 UI 落点是空指**：`AutoManagementDialog`(305 行)、`DiscipleChatDialog`、`DisciplesTab`、`DiscipleManagementDialog` 实测 `道侣|daoCompanion|结缘` **全零命中**（道侣设置实际只存在于已删的 `DaoCompanionManagementDialog`）。与 report-G06 已登记的「recon 行号过期」同类。
3. **§G03-6 三个配置源键不存在**：`GameConfig.kt` 无配对概率/最小年龄/道侣禁灵根常量，`game_config.json` 无 `daoCompanion*`/`pairing*`/`marriage*` 段（侦察本身标了「待现场 grep 行号」）。道侣设置项的真实载体是 `GameData` 两字段 + `RelativeGiftSection` 概率。
4. **`SpiritRootGenerator` 的改接目标不是 `name_service.h`**：`ai_sect_recruit.h:126` 实际改接到 `redeem_code.h` 中**早已存在**的 `spiritRootGenerate`（`name_service.h` 只提供名字生成）。
5. **`generateSpiritRoot` 与 `spiritRootGenerate` 是两份实现**，故 AI 分区序列一致性**必须实测对拍**而非默认相同 —— 见 §四-1。
6. 🔴 **`GameData` 侧 `daoCompanion*` 两字段的 C++ 环在侦察表里根本没有行**：§G03-0 只列了它们的 **Room 列 + ProtoBuf** 身份，§G03-1「三端字段链落点」表的 `models.h`/`json_codec.cpp`/`game_core.cpp` 各行**只覆盖 7 个弟子列**、无 GameData 行 → 五份任务书按表派工，**C++ `GameData` 两字段与 `lock_beast_tx.h` SETTINGS_PATCH 白名单无人认领**，最终由 JUnit 门禁抓获并按根因修（§二-8）。**这是本批第二处「侦察表按字段维度切分、漏掉非弟子表的三端环」的结构性缺口**，与 §9.7 战场哀悼块漏项（§二-1）同因。

## 四、双端一致性与 RNG 论证

### 1. 🔴 AI 分区 RNG 消费序列逐位一致（本批最高风险点，主线程亲自复核）
`ai_sect_recruit.h` 灵根生成从 `child_birth.h::generateSpiritRoot` 改接为 `redeem_code.h::spiritRootGenerate`（两份**不同**实现）。逐位对照结论 **一致**：

| 维度 | 原 `generateSpiritRoot` | 现 `spiritRootGenerate` | 判定 |
|---|---|---|---|
| 定根数 | 1×`nextDouble()` | 1×`nextDouble()` | 同 |
| 权重表 | `{1:0.01, 2:0.03, 3:0.26, 4:0.30, 5:0.40}` | 同表同序 | 同 |
| 兜底 | 权重和 <1 时 `rootCount=5` | 同 | 同 |
| 元素表 | `metal,wood,water,fire,earth` | 同 | 同 |
| 洗牌抽取 | `for i=4→1: nextInt(i+1)` ⇒ **nextInt(5),(4),(3),(2)** | `for i=5→2: nextInt(i)` ⇒ **nextInt(5),(4),(3),(2)** | **同界同序** |
| 交换位 | `swap(list[i], list[j])`，i=4..1 | `swap(list[i-1], list[j])`，i=5..2 ⇒ 同一组下标 | 同 |
| 取用 | 前 rootCount 个逗号连接 | 同 | 同 |

**独立实证**（强于代码比对）：`test/ai_sect_ops_test.cpp` **一行未改**，改接后 ctest 全绿 ⇒ AI 分区灵根序列与黄金基线仍然逐位吻合。

### 2. 列双射对齐（存档损坏头号风险）
`DiscipleColumn` 删 7 项 → 后续列索引整体平移。由**五件 C++ 双射测试**（`disciple_store_test`/`column_export_equivalence_test`/`json_codec_test`/`gameview_encode_test`/`column_dirty_test`，T2 已同步）+ Kotlin 列守卫（`DiscipleTables*Test` 12 文件、`GameViewDiscipleColumnApplyEquivalenceTest`、`BaselineFieldCoverageGuardTest`、`GameDataFieldPatchGuardTest`、`MirrorProtoFeedEquivalenceTest`）+ 跨语言 `Diff*` 家族三面夹住；`game_view.proto` 78–84 转 `reserved` 防字段号复用。**全部实测绿**（§五 门禁）。

### 3. RNG 序列平移面（预期，登记不重录）
`SYSTEM` 分区月结序列因**生育整链 + 自动配对 + 亲属赠礼分类收缩**三个消费点删除而**大幅平移**；`AI` 分区经 §四-1 论证**保持不动**；亲缘 7 列删除**不动行序**（`disciple_store.h` 行序==JSON 数组序==Kotlin ids 序 红线不触碰）。

**金黄/baseline 零改动、零重录**（全局唯一重录窗口 = G10）。

## 五、旧用例处置表

**C++ 测试（净删 13 条 TEST，与 ctest 1483→1470 算术闭合）**
- 整文件删：`child_birth_test.cpp`（4 例，含既有 B 类 `ChildBirth.GoldenSequenceSingleBirth` → **登记「随文件删除」**）；`test/CMakeLists.txt` 注册同步
- 删段：`month_settlement_test.cpp`（PartnerMatching×3 + 边界断言）、`year_settlement_test.cpp`（哀悼段）、`battle_residual_tx_test.cpp`（丧亲例）、`disciple_test.cpp`（`ParentBonusTest.SpiritRootCount` + `GriefTest.IsGrieving`，主线程补）、`sect_defense_battle_test.cpp`（重伤三元里的 `griefEndYears` 一行，主线程补）
- 改断言：`relative_gift_test.cpp`（仅师徒口径，`PartnerBeatsParent` 等删）、`dispatch_guard_test.cpp`（retired +2）
- 列双射五件：同步 7 列

**Kotlin 测试（51 文件，改 44 / 整删 4 / 新增 1）**
- 整类删：`ChildBirthSystemTest`、`PartnerSystemTest`、`GameEngineMarriageProposalTest`、`GameEngineMarriageNativeTxGateTest`
- 整例删（判据 = 被测生产函数/列本身已下线，摘完无残留有效断言）：`DiscipleLifecycleProcessorTest` `processGriefExpiry`×3 + `partner relationship is unbound`；`DeathPipelineEquivalenceTest` 哀悼×2；`CultivationRateEquivalenceTest` `grief sentinel -1`；`DiscipleLifecycleEventsTest` partner 例；`GameEngineResidualNativeTxGateTest` daoCompanion consent 例
- **只摘几行**（同用例其他断言独立有效，一律保留）：`GameEngineResidualNativeTxGateTest` 回退臂例仅摘道侣两行（余 11 字段全留）；`BattleResidualNativeTxGateTest` 三臂降级/重伤/不计年报全留，仅去 `griefEndYears` 断言；`GameViewDiscipleColumnApplyEquivalenceTest` 社交哨兵例改**师徒哨兵**并补 `masterId="ms9"` 非空等价（覆盖面不缩）；`GameViewEventEnvelopeAssemblyTest` 改断言 `payload == null` 并补「仅在场标记 → 空信封」与「无标记 → null 回退」区分断言
- 结构性同步（非 RNG 锚）：`CultivationEventMonthlyOpsTest` T2 入队计数 `10→8`（生产 `enqueueYearlyOps` 现 8 项）
- 替身契约收窄：`FakeAtomicStateStore`/`FakeGameStateStore`/`BootSequenceControllerTest`/`GameEngineCoordinationTest`/`GameEngineWatchItemTest`/`HeavenlyTrialClaimRewardTest` 删 `pendingMarriageProposals`/`clearPendingMarriageProposals` override；`CultivationServiceIntegrationTest`/`SecretRealmRestAreaTest`/`CultivationCoreRealtimeAutoPillsTest` provider 替身形参同步
- 迁移链：`RoomMigrationV56To57Test` 新增（真实 Room schema 校验 + 3 表 11 列删除 + 存续列逐值不变 + 行序不变，种子数据非零标记值）；`RoomMigrationTest`/`MigrationChainGuardTest`/`RoomMigrationLegacyTest`/`RoomMigrationRecoveryTest` 期望集按「注册删列集 ∩ 起点既有列」交集语义接 v57；`ProtoNumberCoverage/Uniqueness`、`SaveDataDirectSerialization/Reconciler/MailWireRoundtrip` 随 reserved 同步

## 六、验证（全部实跑）

| 门 | 结果 |
|---|---|
| 桌面 `cmake --build`（终树复跑 ×2） | ✅ EXIT=0 |
| 桌面 ctest | ✅ **1470 总 / 1467 过 / 3 败**；3 败与 G06 基线**逐条同名**（`DiscipleFactory.GoldenSequenceSeed42`、`DiscipleFactory.GoldenSequenceSeed987654321Female`、`DeterminismProbeTest.DigestMatchesGoldenBaseline`），第 4 条 `ChildBirth.GoldenSequenceSingleBirth` 随 `child_birth_test.cpp` 整删消失；**零新增失败、零 A 类**；总数 `1483→1470` = 删 13 例，算术闭合 |
| `compileReleaseKotlin`（全链） | ✅ EXIT=0 |
| 五模块 `compileReleaseUnitTestKotlin` | ✅ EXIT=0、**0 错误**（开工时 236 条） |
| JUnit 六模块（`--max-workers=1 --rerun-tasks` + `-Dgamecore.jni.path` 跨语言桥真跑） | ✅ **7662 全绿 / 0 失败 / 0 错误 / 17 skipped**（app 1011 / domain 1646 / data 808 / engine 3085 / ui 146 / feature:game 966；BUILD SUCCESSFUL 11m54s）。**首轮曾 29 红**，全部按 §二-8/9 的 A 类根因修尽后复跑归零；**未重录任何锚**。用例数 `7709→7662 = −47` 与 diff 侧「删 50 个 `@Test` + 新增 3 个」**算术闭合，无隐性掉测** |
| detekt 六模块 | ✅ EXIT=0（首跑 8 条违规全部实修，**baseline 零新增**） |
| `build-desktop-jni.ps1` | ✅ EXIT=0（C++ 改动后重建，对拍库 9,094,656 B） |
| `gen-action-ids.mjs` | ✅ **198 动作 / maxId=1861**；双产物仅 2 条退役 desc 变化 |
| `check-jni-count.mjs` | ✅ **86 / 86**（本批不涉 JNI 导出面） |
| `gen-game-data.mjs --check` | ✅ sha `915563485e9fd41d…be84d2` **不变**（配置源改动仅触及 `GameConfigData` 默认值，不在 game-data 生成链） |
| `check-agent-instructions.mjs` | ✅ EXIT=0（410 引用无死链、路由表 7/7；2 条告警系预存） |
| Room schema 链 | ✅ 仅 `57.json` 新增，**历史快照零改写**；`disciples` 102→95 列；11 待删列在 v57 实体中残留 **0**；`social_masterId` 存续 |
| 暂存区危险项扫描 | ✅ 命中 0；构建副产物 `atlas-rgba-manifest.json` 已 `git checkout --` 还原 |

## 七、grep 终态证据

- **删除模式归零**（`src/main` 生产面 + gamecore 源面）：
  `partnerId`/`partnerSectId`/`parentId1`/`parentId2`/`lastChildYear`/`childBirthMonth`/`griefEndYear` **0**；
  `ChildBirth`/`child_birth`/`processMonthlyBirth`/`createChild` **0**；
  `PartnerSystem`/`processPartnerMatching`/`hasBloodRelation`/`kPairingProbability`/`kPairingMinAge` **0**；
  `PendingMarriage`/`MarriageApproval`/`approveMarriage`/`rejectMarriage`/`MARRY_*`（除退役保号常量与 guard 登记） **0**；
  `daoCompanion`/`DaoCompanion` **0**；`BereavementDraft`/`applyGriefToRelatives`/`isRelatives`/`unbindPartner`/`parentBonusFor`/`getParentSpiritRootBonus`/`isGrieving` **0**；
  `kPartnerGiftProb`/`kParentGiftProb`/`kChildGiftProb`/`kSiblingGiftProb` **0**；
  玩家可见文案「道侣/结缘/配偶/结为道侣」+「丧亲/哀悼/悲痛」**0**（唯一保留：历史迁移链列名、`reserved` 注释、`BuiltinMailConfig` 七夕邮件已改写）
- **反向守卫 #12 必须仍有命中**（贴证）：C++ `masterIds`/`MasterId`/`masterBonusFor`/`kMasterGiftProb`/`kApprenticeGiftProb`/`DISCIPLE_LIFECYCLE_APPRENTICE` 全在；`social_masterId` 10 处（含 v57 实体与 Room 列）；Kotlin `DiscipleLifecycleProcessor.kt:115/160` 师徒解绑、`DetailActionButtons` 师父/徒弟段、`MasterApprenticeSelectDialog` 全文件、`DiscipleDelegate.apprenticeToMaster`、测试侧 `masterIds` 断言 40+ 处
- **悟性存续**（本批口径变更）：见 §一-3 实数

## 八、G10 登记（本批新增）

**B 类（RNG 平移）**：C++ 侧 **3 条既有 + 1 条随文件删除**（`ChildBirth.GoldenSequenceSingleBirth`）；**Kotlin 侧实测 0 条**。

🔑 **重要澄清（供 G10 排期，纠正交接文档 §6 的预期）**：本批 SYSTEM 分区序列确实大幅平移（生育/配对/赠礼三消费点删除），但 **Kotlin `Diff*` 家族实测全绿且零锚改动** —— 因为 `Diff*` 断言的是「**C++ 输出 == Kotlin legacy 实现**」的**跨语言等价**，不是录制好的黄金数值；两侧按同一口径同步删除后依然互相吻合，序列平移对它是透明的。**真正被平移打到的只有 C++ 侧持固定摘要/固定序列常量的 GTest 金序列**（即那 3 条既有 B 类）。
⇒ **G10 重录窗口的实际工作量为 C++ GTest 金序列 + `DeterminismProbe` 摘要**，Kotlin Diff 家族预计无需重录（仍须在 G10 终树实测确认后才可下结论）。
S1/S2/S3 三分片亦逐条自证其负责文件内不含任何黄金锚（Rate/Death 为 object↔column 形状对拍、`RelativeGiftHandlerTest` 用恒定注入 `FixedRollRng`、MonthlyOps/SecretRealm/GameView 的 `rngManager` 是 `mockSmart` 桩）。**全批金黄/baseline 零重录。**

**途中发现（本批不动，公约 12）**
1. 🔴 **侦察文档结构性缺陷（本批实测两处，同一根因）**：
   - §9.x 收口项的「归属结论」与 §G02/G03-2 落点表**不双向校验** —— `sect_defense_battle.h` 哀悼块判归 G03 却不在 G03 落点表内，五份任务书全部漏它（编译期暴露，主线程补做）。
   - §G03-1「三端字段链落点」表**按字段维度只铺了弟子 7 列**，`GameData.daoCompanion*` 两字段的 C++ 环（`models.h` + `json_codec.cpp` + `lock_beast_tx.h` SETTINGS_PATCH 白名单）**整表无行** —— 任务书无人认领，直到 JUnit 才由 `BaselineFieldCoverageGuardTest` 与 16 条 `Diff*` 红抓获（**运行期才暴露，代价高一个量级**）。
   ⇒ **G04/G08/G09/G11 开工前必须做两件事**：① 逐条回查各 recon 的 §9.x / 交叉归属段与批次落点表是否互相覆盖；② 凡 `@Entity`/`GameData` 级字段删除，**除弟子列链外单独追一遍 C++ `GameData` 模型 + `to_json/from_json` + SETTINGS_PATCH 白名单**三处，不得只依赖字段链表的行。
2. `ai_sect_ops.h:166-167` 与 `disciple_stats.h:771-772` 各有一行 `if (!x.empty()) rootCount = 1;` **自赋值无效语句**（预存，非本批引入），G10 死代码清零连带。
3. `DiscipleLifecycleProcessor.applyCombatInjury` 仍调 `deathHandler.markDead(...)`（行为=重伤、名字=死亡），且 `DiscipleLifecycleProcessorTest.kt:75` 注释写「markDead 写 isAlive=0 + status=DEAD」与 G07 实现不符 —— 属 report-G02 已登记的 `markDead` 命名收口项（§9.6），G10 一并处理。
4. `BattleResidualNativeTxGateTest` 弟子 101 命名 `"阵亡者"` 与 G07「重伤不死亡」语义相悖（绑在 `DeathEvent` verify 实参上），G10 命名收口连带。
5. 裸 `mock(GameRngManager::class.java)`（`RelativeGiftHandlerTest.kt:79`、`SecretRealmRestAreaTest.kt:58`）违反 `rules/testing.md` §2「一律 `mockSmart`」；final 类裸 mock 是在册 flaky 根因。预存。
6. `FakeAtomicStateStore` 是 `:core:engine` 共享替身但**未登记进 `rules/testing.md` 的共享工厂表** —— 生产接口成员每变一次就有 N 个测试替身连带红，建议正式登记并注明「生产接口成员变动须同批改此替身」。
7. **UI 文案待拍板**：弟子详情「关系」对话框现只剩师父/徒弟（6 类亲属→1 类），入口按钮仍叫「关系」，是否改名「师徒」属产品判断，本批按铁律 14 未自改。`SectManagementDialog` 按钮区 3→2 为 `FlowRow` 自适应，无空洞无需占位。
8. `prisonerSpiritRootFilter`（G05 已登记零生产者死设置）的 UI 写入面 `AutoAssignDelegate.setPrisonerSpiritRootFilter`/`togglePrisonerFilter` 仍在，G10 一并清。
9. **本会话工程事实（供交接）**：子代理硬上限 **150 轮**，G03 的 5 个分片中 A1/T1/T2/A3/c340 **全部撞顶中断**（改动已落盘、无收尾报告），实际靠「主线程接管 + 二次细分片 S1/S2/S3（其中 S3 亦撞顶）」完成。后续批次（G04 尤重）应**直接把分片切到 ≤10 文件粒度**，不要再按概念面切。

## 九、附录：分工

| 分片 | 交付 | 验证 |
|---|---|---|
| A1 · Kotlin 字段链 | 31 文件改 + 4 整删（`:core:domain`/`:core:data` 全链 + reserved 登记），−931 行 | 撞 150 轮上限无收尾报告；成果由主线程 + A2/A3 复核 |
| T1 · C++ 结算 | `child_birth.h` 整删、月/年结生育配对哀悼、`relative_gift.h` 收缩、婚姻事务、AI 分区改接 | 撞上限；ctest/序列由主线程终树复跑自证 |
| T2 · C++ 列链 + ActionId | 7 列三端环 + `DiscipleColumn` 平移 + proto reserved + 1592/1750 退役 + 列双射五件 | 撞上限；`gen-action-ids` 198/1861、ctest 由主线程终树复跑 |
| A2 · feature:game UI | 11 文件 +20/−269；婚姻提议/道侣入口/亲缘显示全链 + `gameYear` 四层死链 | ✅ 完整收尾报告；`:feature:game:detekt` EXIT=0；11 条 grep 归零 + #12 反向 26 处命中 |
| A3 · core:engine + 配置源 | 引擎 System/Handler/Processor/StatsProvider 形参收窄 + 相对赠礼收缩 + `GameConfigData` | 撞上限；遗留 1 条重复 import 由主线程修 |
| c340 · Kotlin 测试 | 26 文件（含 `RoomMigrationV56To57Test` 新建） | 撞上限；10 文件遗留错误由 S2 收 |
| S1 · 测试收尾（9 文件） | 替身成员删除 + 道侣/丧亲断言摘除 + provider 替身 `masterDiscipleBonus` 断链修正 | ✅ 完整报告；自身 9 文件 0 错误 |
| S2 · 测试收尾（10 文件） | 服务/gameview 测试签名收窄 + 哀悼例处置 + 师徒覆盖补强 | ✅ 完整报告；`:core:engine` 单跑 129 例 0 失败 |
| S3 · 测试收尾（13 文件） | nativebridge Diff 家族 + 迁移链 + proto 守卫 + app/data/feature 替身 | 撞上限；全模块编译由主线程终树复跑至 0 错误 |
| 主线程 | Room v57 四件套、§9.7 与 `game_core.cpp` 漏项补做、`sect_defense_battle.h` 哀悼块删除、公式乘区收口、注释口径 6 处、detekt 8 条实修、全门禁、双 changelog、本报告、提交 | 见 §六 |
