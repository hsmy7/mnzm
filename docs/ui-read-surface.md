# UI 读取面清单（WS-1.4 镜像契约收敛）

| 项 | 内容 |
|---|---|
| 文档性质 | WS-1.4 交付物：C++→Kotlin 镜像的**合法内容上限** + 反向通道**分域关闭依据** |
| 依据 | docs/cpp-migration-implementation-plan.md WS-1 第 4 条（2026-09-04）+ 审计 P0-2 |
| 实施日期 | 2026-09-05（随 WS-1 同步通道降本批）；**§4 于 2026-09-08 按 S4-S8+WS-5 清偿后逐域写者审计重写**（当时结论：无域可关，关闭前置 = UI 操作面逐域下沉）；**§4.1 表于 2026-09-13 按 W2-a/W2-b 交付实测滚动更新**——已下沉域逐行标 ✅（建筑/道路/外交/弟子管理三子批/招募残余/生产 UI 面/月年边界编排/玉符宗门/秘境平台段/库存出售与商人购买/巡逻住所/攻宗确定性写段），**剩余未下沉项在同表内显式标注** |
| 维护纪律 | 新增 UI 读取字段必须先确认在 §2 镜像合法面内；不在则先扩 C++ 协议（编码+对拍），禁止从 UI 侧直接加镜像字段 |

---

## 1. 定位

AUTHORITATIVE 模式下 C++ GameCore 是模拟真相源；Kotlin `GameStateStore` 是 UI/存档链路的
只读镜像（写入仅两条合法通道：前向镜像 `StateSyncService.applyDirty/syncFromNative`、
UI 操作事务）。本清单回答两个问题：

1. **镜像最多能装什么**——镜像合法内容上限（§2）。UI 读取面不得超出它；
   超出的字段根本不该出现在镜像里。
2. **反向通道（Kotlin→C++ 回导）何时可以关**——按域逐个列出写入者与关闭条件（§4）。
   反向通道存在的唯一理由是"该域仍有 Kotlin 侧写入者"；写入者下沉（WS-2 流水线）后
   该域通道即应关闭。

---

## 2. 镜像合法内容上限（= C++ exportState 协议面）

镜像内容的唯一来源是 C++ `GameCore::exportStateJson`（`json_codec.cpp` 编解码面）：

### 2.1 gameData（`json_codec.cpp to_json/json GameData`，与 kotlinx 双向对齐）
- 标量/时间：`id, sectName, currentSlot, gameYear, gameMonth, gamePhase, spiritStones,
  midGradeSpiritStones, highGradeSpiritStones, spiritHerbs, sectCultivation, activeSectId,
  mapSeed, lastSaveTime, saveVersion, isGameOver, …`
- 运营开关/筛选：`playerProtectionEnabled, playerHasAttackedAI, autoRecruitSpiritRootFilter,
  prisonerSpiritRootFilter, autoRejectSpiritRootFilter, breakthroughAutoPill*,
  autoEquipFromWarehouse*, autoLearnFromWarehouse*, daoCompanion*, autoSellMid/HighGradeForPurchase,
  showAllAvailableDisciples, soundEnabled, musicEnabled, …`
- 容器/嵌套：`yearlySalary(+Enabled), jadeSymbols(+Today), unlockedRecipes/Manuals,
  worldMapSects, worldLevels, recruitList, elderSlots, productionSlots, placedBuildings, roads,
  spiritFieldPlants, residenceSlots, patrolConfig(+s), alliances, vassalContracts, sectRelations,
  sectBattleRecords, sectPolicies, sectDetails, scoutInfo, mailRecords, bloodRefinements(+Totals),
  yearlyReports, availableMissions, secretRealm*, librarySlots, gameEventRecords, …`
- **明确不在 gameData 序列化面**（@Transient 运行态，Kotlin 侧权威）：`aiSectDisciples、
  aiSectBeastDirectTargets、aiSectBeastSkipCooldowns、lockedBeastIds、rngStates`
  （rngStates 是 C++ live RNG 的导出镜像，反向回导永久剔除）。

### 2.2 实体集合（10 个，与 `state::DirtyTracker` 跟踪清单一致）
`disciples（DiscipleStore 列存储）、equipmentStacks、equipmentInstances、manualStacks、
manualInstances、pills、materials、herbs、seeds、storageBags`

### 2.3 顶层运行态载体（`NativeGameState` 顶层可空字段，非空才导出/宽松导入）
`aiSectDisciples、aiSectBeastDirectTargets、aiSectBeastSkipCooldowns、lockedBeastIds`
（批 10-4/13-1 协议；镜像"非空才覆盖、永不主动清空"）

**上限纪律**：不在上述面的字段/集合，`GameStateStore` 镜像通道不得引入；UI 若需消费
C++ 新状态，先扩 C++ 协议（`json_codec` + DirtyTracker + 对拍），再进镜像，再进 UI。

---

## 3. UI 实际读取面（2026-09-05 审计基准）

证据源：`GameViewModel`（feature/game）与 `GameStateStore` 公开 StateFlow。
**结论：UI 读取面 ⊆ 镜像合法面，无越界读取。**

### 3.1 三层 StateFlow（`GameStateStore` 统一重建，防重组雪崩）
| 层 | 字段 | 源 |
|---|---|---|
| HighFreqState | lowGrade/mid/highGradeSpiritStones、gameYear/Month/Phase、isPaused | gameData 时间/灵石 + 引擎运行态 |
| EntityState | 10 实体集合 + battleLogs* | 镜像集合（*battleLogs 为 Kotlin 战斗域运行态，见 §3.4） |
| ConfigState | sectPolicies、yearlySalary(+Enabled)、elderSlots、placedBuildings、autoRecruitSpiritRootFilter、gameSpeed | gameData 配置字段 + 引擎速度 |

### 3.2 派生 UI 流（GameViewModel，逐条注明消费的 gameData 字段）
| UI 流 | 消费字段 |
|---|---|
| guideClaimedRewardIds / watchedItemIds | `guideClaimedRewardIds` / `watchedItemIds` |
| spiritStoneTotals | `spiritStones` 三阶 |
| jadeSymbolState | `jadeSymbols/jadeSymbolsToday/jadeDayAnchorMs/jadeAccumMs`（玉符域派生） |
| attackWarnings / shownWarningStageIds | `activeAttackWarnings` / `shownWarningStageIds` |
| placedBuildings / elderSlots / sectPolicies / manualProficiencies / residenceSlots | 同名字段 |
| playerSectLevel | `worldMapSects[isPlayerSect].level` |
| sectLevelRewardClaimable | `sectLevelClaimRecords` + playerSectLevel |
| recruitListAggregates | `recruitList` |
| playerSectId/activeSectId、sectName、currentSlot/slotId、mapSeed（存档链路） | 同名字段 |
| prisonerSpiritRootFilter、autoRecruit/autoRejectSpiritRootFilter | 同名字段 |
| disciples / aliveDisciples / discipleAggregates / sectCombatPower / aiSectCombatPowers | `disciples` 表 + 镜像 `aiSectDisciples` |

### 3.3 个体 Field StateFlow
`gameData、disciples、equipmentStacks、equipmentInstances、manualStacks、manualInstances、
pills、materials、herbs、seeds、storageBags`（全部在 §2.2 集合面内）

### 3.4 非镜像运行态通道（Kotlin 域事件，与镜像无关，不受本清单上限约束）
`pendingBattleResult、pendingBattleRewardCards、rewardCardQueue、pendingNotification、
notifications、pendingBeastAttacks、pendingMarriageProposals、battleLogs、warehouseFullEvent、
lifecycleState/bootPhase/runState`——事件/弹窗/生命周期类，生产者是 Kotlin 域服务，
不进 C++ 协议。

---

## 4. 反向通道分域关闭依据

反向增量回导（`applyDirtyToNative`，WS-1.2 后 gameData 段为字段级 dirty 集）按域关闭。

### 4.1 逐域写者审计（**首裁 2026-09-08**；表体于 2026-09-13 滚动更新）

> 🔴 **2026-09-15 口径更正（batch-21 穷尽审计，§4.4）**：本表的 ✅ 行**只表示"该行所列写者已下沉"**，
> **不等于"该域已无稳态 Kotlin 写者"**——全仓穷尽扫描（288 写入点）实测：**14 个域无一可整体关闭**，
> 本表未列出的稳态写者（弟子赏赐/服药、血炼、婚姻审批、玉符运行时、邮件附件、行商刷新、灵矿槽位 UI 直改、
> 弟子状态派生、native 事务后的 Kotlin 残差等）见 §4.4 域级结论表。**判断某域可否关闭时，一律以 §4.4 为准**。

> ⚠️ **本节标题与下表为两层信息**：标题记的是**首裁时点**（2026-09-08 实测），表体已按 W2-a/W2-b 交付
> **逐行滚动更新**——读表不看标题。
>
> **当前结论（2026-09-13）**：多数域已 ✅ 下沉；**局部关闭已可行，全量关闭不行**——
> 剩余五项见 §4.3 前置现状块（开袋待拍板 / 弟子管理残余 / `aiSectDisciples` 段残余 /
> `lockedBeastIds` UI 操作面 / 月年编排残余）。若某个域在表中已 ✅ 且其"关闭动作"
> 无顶层 `@Transient` 段依赖，则该域**可在 batch-21 内单独关闭**（§4.3 按域独立验收）。
>
> **首裁原文（2026-09-08，保留以便追溯）**："当前没有任何一个域满足关闭条件"——S4-S8/WS-5 下沉的是**结算/事务
> 核心**（旬结七步、月结步骤 4a/4b/6/9、任务结算、秘境会话、生产排班事务、
> 手动/一键招募主路径、地形生成），而 §4.0 假设"关闭条件已达成"的各域实际仍存在
> 大量 **UI 操作面 Kotlin 直改写者**（约 265 个 `stateStore.update` 生产调用点，
> 15+ 域）——"玩家操作 Kotlin 产生 → tick ⑤ 反向回导 C++"是 2026-08-31 根因修复
> 后的现行设计契约。**反向通道按域全关的前置 = UI 操作面逐域下沉 C++**（每域一个
> WS-2 规模的批次），属长期主轴工作，不是收敛批可完成项。

| 域 | Kotlin 直改写者残余（**表体 2026-09-13 滚动更新**；✅ 行 = 已下沉，行内"残余"为剩余项） | 下沉批次（关闭前置） |
|---|---|---|
| 库存 | ~~sell 六族/bulkSell、商人收购出售、上架/撤下、consumeMaterialByName~~ **✅ 出售/上架/材料消耗族已下沉（2026-09-11，W2-a，handover §2.41）**：单类出售六入口 + 批量出售 + 商人收购 + 玩家上架/撤下 + 按名称品阶材料消耗稳态写者归 C++（`inventory_tx.h` 事务，1520–1525），零 RNG 纯确定性变换，Kotlin 原路径降级回退臂；**✅ 商人购买/充公已下沉（2026-09-12，batch-11，handover §2.42）**（ActionId 1530–1531）；**残余**：**开袋**（EXPLORATION 分区 `nextInt(16)`+逐件 `nextInt(7)` 与分支内 `Random.Default` 模板抽取**双重 RNG**——路线 B 备案不下沉，需拍板是否接受路线 A 行为基线变化）、add/remove/sort/consolidate/lock 五族已 C++ 转发 + Kotlin 回退保留 | 出售/上架/材料消耗批 ✅ → 商人购买/充公批 ✅ → **仅余开袋（待拍板）** |
| ~~建筑放置/拆除/升级~~ | ~~placeBuilding/moveBuilding/upgradeBuilding/removeBuilding~~（~~place/move/upgrade/remove~~） **✅ 四操作已下沉（2026-09-10，batch-06，handover §2.35）**：AUTHORITATIVE 稳态写者归 C++（building_tx.h 事务，1450–1454），Kotlin 原路径降级为回退臂；槽位派生/弟子释放残差留 Kotlin（BuildingFeatureRegistry 槽组语义，回执驱动）；剩余残余：enterSect/BootSequence 迁移（W2/W3 后续波次） | ~~建筑放置事务批~~ ✅（残余 → 后续波次） |
| ~~道路~~ | ~~placeRoad/removeRoad（Kotlin 直改 + 即时回导加固）~~ **✅ 已下沉（2026-09-10，batch-07，handover §2.36）**：AUTHORITATIVE 稳态写者归 C++（road_tx.h 事务，1470/1471），Kotlin 原路径降级为回退臂；RoadMaskTracker/RoadTiling 为纯 UI 缓存不迁（镜像回读驱动） | ~~道路事务批~~ ✅ |
| 巡逻/探索 | ~~assign/remove/swap/autoAssignPatrolAtomic、worldLevels 战斗结算~~ **✅ 巡逻/住所族已下沉（2026-09-13，batch-12，handover §2.43）**：`GameEngineAtomicAssign` 六入口（住所 2 + 巡逻 4）+ `GameEnginePatrolOps` 三活写者（`updatePatrolConfigs` / `validateAndFixSpiritMineData` / `updateYearlySalary`）+ `updateSpiritMineSlots`（活 API）稳态写者归 C++（`system/patrol_tx.h` 十事务，ActionId 1550–1559），Kotlin 原路径降级回退臂；**全链零 RNG**（签名级 + GTest 全分区快照差分 + 双运行逐位一致）；事务外残差（gate release/confirmAssign、Room 生产槽仓清理、弟子状态同步）保留 Kotlin 经 `releasedIds`/`confirmedIds` 回执驱动。`updatePatrolSlots` 实测生产零调用（死 API，不为它扩协议）；**worldLevels 战斗结算已随 batch-13 下沉**（ActionId 1570） | ~~巡逻批~~ ✅ |
| 弟子管理 | ~~装备穿脱/功法学习卸下/亲传+藏经阁任命卸任~~ **✅ 第一子批（2026-09-10，batch-08，§2.37）**（`disciple_tx.h` 六事务，1480–1485）；**✅ 第二子批（2026-09-11，batch-14，§2.45）**（逐出/拜师/婚姻批准/释放思过/年俸开关，`disciple_lifecycle_tx.h`，1590–1594）**+ 14b 名字随机源分区化**；**✅ 第三子批（2026-09-12，batch-15，§2.46）**（长老单值槽任命卸任 / 仓库驻守 / 洗炼消耗三族，`appointment_tx.h`，1610–1616）；**残余**：收徒（`recruitDisciple` 待拍板名字种子策略）/ 状态同步族 / 特质 confirm 两入口（纯数据写）→ **⚠️ 2026-09-15 事实核查更正**：本行**高估**完成度——`GameEngine.kt:276 approveMarriageProposal`（"婚姻批准"声明有 native 臂但**实测未接线**：`DISCIPLE_LIFECYCLE_MARRY_APPROVE=1592` 在 Kotlin 侧零引用，C++ handler+3 GTest 齐备）与 `GameEngineManualOps.kt:137 replaceManual`（功法替换，活 UI `DiscipleDetailScreen.kt:954`，**无 native 臂且从未登记**）均为**未登记稳态写者** ⇒ 弟子管理域**不可关闭**（w3-01/w3-02 批范围，见 §4.4） | 弟子管理族（**三子批已下沉，但域级关闭前置不成立**） |
| 招募/派遣/俘虏 | ~~手动+一键主路径已 C++；回退路径、列表刷新/老化、lifeEvents 补写~~ **✅ 残余三直调点已下沉（2026-09-12，batch-16，§2.47）**：`removeFromRecruitList` / `refreshRecruitList` / `ageRecruitList` → `recruit_tx.h` 复用年度权威链零复制（ActionId 1630–1632）；**审计判定不下沉**：MerchantAndRecruitService 零招募写者、派遣 `startMission`（惰性门留月变真相源批）、奖励发放（结算域已 C++）、`id=""` 候选跨年去重（拍板项） | 残余族 ✅ |
| 生产 | 手动排班/重置已 C++（S7）；**✅ 生产 UI 面 + 灵田种植族已下沉（2026-09-12，batch-17，§2.48）**（四生产槽 UI 事务 + 四灵田种植事务，ActionId 1650–1657）；**审计判定保留 Kotlin**：`autoHarvestCompletedAlchemySlots`（读档路径/AUTHORITATIVE 基线窗口——迁此会在首月读档产生"免费收获"）、`MaterialConsumptionLog`（平台效应）、自动续班启动（S4 月结末尾已 C++） | **UI 面 ✅**；S4 窗口对齐已兜月结 |
| 秘境 | ~~会话三入口已 C++（S6）；start 换岗/到期守卫/回退路径 Kotlin 直改~~ **✅ 平台段已下沉（2026-09-12，batch-20a，handover §2.51）**：唯一未下沉写者 `continueSecretRealmExploration`（读档恢复：到期关闭/死局 endSession/成员净化/gate 重建）归 C++（`secret_realm_platform_tx.h`，ActionId 1710）；`autoAssignSecretRealmTeam` 审计为**纯只读选择器**（无写者）、pause/resume/renew 为**运行时时钟平台残差**（S5/S6 口径留 Kotlin）；**顺手根治** `secret_realm_settlement.h` 的 `kOpenYears` 移植缺陷（50→5，此前 AUTHORITATIVE 下秘境满 5 年还要再挂 45 年） | ~~平台段批次~~ ✅ |
| 月年编排 | ~~processMonthYearChange 边界效果、玉符、洞府探索、天劫、兑换码、宗门升级、政策开关、设置项、guide、邮件附件~~ **✅ 已审计收窄（2026-09-12，batch-18，§2.49）**：六候选域中仅 **guide 计数面三写者**（increment/batchUpdate/backfill → `boundary_tx.h`，1670–1672）与**政策开关三入口**（toggle/openRecruitment/spiritMineBoost → `government.h`，1680–1682）为 Kotlin 独占活路径，已下沉；**审计判定不下沉**：月年边界效果 / 战后 HP-MP / 游戏结束判定 / YearlyOpsQueue / 通用写入口 / claimGuideReward 六域（死代码 / 月变事务内步骤（C++ 权威已存在）/ 通用写入口 / Kotlin 注册表不可复刻）。**✅ 玉符/宗门升级（2026-09-12，batch-19，§2.50）**（`jade_tx.h`，1690–1693）；**不下沉**：兑换码 `redeemCode`（C++ 无物品随机生成器，RNG 红线）、邮件附件（无新写者）。**✅ 设置项（2026-09-14，batch-23，§2.55）**：17 字段 → `SETTINGS_PATCH_TX=1731`；**天劫/洞府探索经 §2.57 定界登记不下沉**（理由见下方滚动更新表） | 边界效果域**全部收口**（设置项 ✅ / 天劫·洞府：登记不下沉） |
| ~~外交/好感/附庸~~ | ~~diplomacy/vassal/favor 全部 Kotlin 直改~~ **✅ 已下沉（2026-09-10，batch-09，handover §2.38）**：赠礼/结盟/散盟/附属建立解除稳态写者归 C++（diplomacy_tx.h 事务，1500–1502），Kotlin 原路径降级回退臂；聊天响应模板（Random.Default 非游戏分区）与月结面（已下沉）不在写者面；宣战/停战/和平经审计无 UI 操作面 | ~~外交族批次~~ ✅ |
| aiSectDisciples 段 | ~~战斗阵亡/吞并（月结/遭遇战 Kotlin 回退事务内）、load 自愈、存档自愈~~ **⚠️ 部分下沉（2026-09-13，batch-20b，handover §2.51b）**：**攻宗的阵亡守军清理已下沉**（`sect_attack_tx.h removeDeadDefendersTx`，ActionId 1711——本段最大 UI 触发写者）**+ 魂魄发放（1712）**；**残余两项定界（2026-09-14，§2.57）**：① 月结回退路径的战斗阵亡/吞并写者 = **回退臂**（AUTHORITATIVE 月结走 C++ `runMonthSettlement`，不触达该路径 ⇒ 非稳态写者，捕获关闭后回退路径仍正确）；② **load/存档自愈 = 稳态写者，已拍板下沉**（见下方滚动更新表的 AI RNG 归一改判） | ② 经 [ADR](adr/rng-determinism-remediation.md) 阶段 1 下沉后本段可关 |
| 时间/结算输出域 | 无（只读镜像） | **已关**（无写入者，反向窗口天然不含） |

**2026-09-14 batch-23/24 滚动更新（§2.55/§2.56/§2.57）**：

| 域 | 本轮处置 |
|---|---|
| `lockedBeastIds` UI 操作面 | **✅ 已下沉（batch-23）**：`GameEngine.lockBeastView`/`unlockBeastView`（**`:329/:340`**——2026-09-15 核查更正，旧记 `:319/:324` 已漂移；`launchOnEngine` 调用面 = 妖兽详情弹窗开/关）稳态写者归 C++（`lock_beast_tx.h` `BEAST_VIEW_LOCK_TX=1730`；Set 语义 + 保序 + 幂等 + lockedCount 回执）；反向增量段（§2.21.2 补齐）**已随 batch-21 关闭**（§4.4，仅剩回退臂写者） |
| 设置项域（原「月年编排残余」中的设置项） | **✅ 已下沉（batch-23）**：17 个 gameData 字段经 `SETTINGS_PATCH_TX=1731` 通用补丁——覆盖 `SettingsDelegate`（6：音频 2 + 战报弹窗 + 中/高阶自动出售 + 显示全部弟子）/ `AutoAssignDelegate`（7：突破丹药 2 + 自动装备 2 + 自动学习 2 + 道侣禁止灵根数 + 道侣同意）/ `DiscipleDelegate`（2：自动招募/自动拒绝过滤，1..5 预筛 + 惰性门残差留 Kotlin）；平台效应（`AudioConfig` / 惰性门 / 待处理提议清理）保留 Kotlin |
| 弟子管理残余 | **✅ 已清（batch-24）**：`confirmSpiritRootWash` / `confirmTraitWash` 两入口下沉（`appointment_tx.h` 事务 8/9，ActionId 1732/1733）——纯数据写（零玉符/零 RNG）+ checkpoint 与 lifespan 同步同事务；三态拒绝文案由 C++ 信封 `executeRaw` 回传。**弟子管理域至此无稳态 Kotlin 直改写者** |
| `aiSectDisciples` 段 | **⚠️ 已拍板下沉（2026-09-14 审计勘误 + [ADR](adr/rng-determinism-remediation.md) 阶段 1）**——`checkAndRepairAiSectDisciples`（load/save 自愈）消费 `AISectDiscipleManager._rng`，该对象是**真源的影子拷贝**：真源 `GameRngManager.getRng(AI_SECT)` 在 AUTHORITATIVE 下**已委托到 C++ `kAiSect` 分区，且该分区本就在 `rngStates` 协议面内**（`exportStates()` 遍历全部 8 分区）；影子只在 `createNewGame`/`loadData` 两处 `initForSlot(mapSeed)` 重播，之后与真源各自漂移。**故下沉路径 = 先归一（影子摘除）再下沉自愈**，非"需大立项"（缺的 C++ 原语仅编排三件，生成/装备/截断已在 `ai_sect_recruit.h`）——**该域构成 batch-21 剩余前置之一** |
| 天劫 / 洞府探索 | **登记不下沉**：天劫 `claimClearReward`（模板非确定性随机 + 凭据溢出抑制同一 `stateStore.update`，原子性不可拆）与 `recordPhaseClear`（与领奖共用 `heavenlyTrialState` 段，拆分即撕裂事务）；洞府探索 `processSectDisciplesAging`/`processCaveLifecycle` 经 `CultivationEventMonthlyOps` **只在月结 Kotlin 完整编排内**（回退臂，非稳态写者） |

### 4.2 顶层 @Transient 段的通道现状（2026-09-08 审计订正）

| 段 | 增量通道 | 说明 |
|---|---|---|
| `aiSectDisciples` | ✅ 独立全量段（S-15 变化检测） | 战斗/AI 域 Kotlin 写者（回退路径）增量可达。**2026-09-13（batch-20b，§2.51b）收窄**：攻宗阵亡守军清理（`sect_attack_tx.h removeDeadDefendersTx`，1711）已归 C++——原 `GameEngineBattleOps:172` 写者降级为回退臂 |
| `lockedBeastIds` | ✅ 独立全量段（**2026-09-08 补齐**，S-15 同族） | 缺口加固：UI lockBeastView/unlockBeastView 此前只能经全量回导兜底到达 C++，增量窗口内 AUTHORITATIVE 月结跳过判定（锁定妖兽不被 AI 攻击）对新弹窗失效；现为变化检测 + 整体替换语义 |
| `aiSectBeastDirectTargets` / `aiSectBeastSkipCooldowns` | ❌ 无增量段（**可接受**） | Kotlin 写者仅存在于 Kotlin 月结回退路径（native 未就绪时）；AUTHORITATIVE 稳态由 C++ 独占并前向镜像。回退→AUTHORITATIVE 切换经 `ensureAuthoritativeNative` 全量导入，四段全部可达——无增量缺口 |

### 4.3 关闭动作（原样有效，但前置未达成）

关闭动作 = `GameStateStoreImpl.captureReverseDirty` 停止捕获该域 + `StateSyncService`
信封不再携带该域段；每关闭一域，反向信封体积相应归零（可观测验收）。
**在 4.1 表全部域下沉前执行任何"停捕获"都是数据丢失缺陷**（该域 Kotlin 写入将永达
C++）。

> **前置现状（2026-09-14 实测，batch-23/24 收口后）**：4.1 表中**仅剩 2 项未关闭**，且两项
> 均已**拍板立项**（[ADR rng-determinism-remediation](adr/rng-determinism-remediation.md) 阶段 1）——
> 立项原因：两项都属「随机源治理」同一架构漏洞的出口，单独打补丁无法阻止复发。
> **⚠️ 该口径已被 2026-09-15 batch-21 穷尽审计推翻（见 §4.4）——本节表体仅覆盖 4.1 表列出的
> 写者，非全仓穷尽扫描结果。**
>
> ① 库存**开袋**——**已拍板走路线 A**（下沉 C++，ActionId 1734+）。实测澄清：开袋的
> 「抽到哪一件」走 `templates.random()` / `generateRandomPill` / `generateRandomMaterial` 的
> 默认参数（`kotlin.random.Random.Default`，**不入存档、不随档走**），而件数与类型走
> `EXPLORATION` 分区；故"路线 A 会破坏行为基线"的前提**不成立**——同一存档两次开袋结果
> **今天就已经不同**，改造只是把"每次不同"变为"可复现"。
> ② **`aiSectDisciples` load/save 自愈**（`checkAndRepairAiSectDisciples`，boot Step 5 与
> `upgradeSectLevel` 修复路径）——**已拍板**：先做 **AI RNG 归一**（`AISectDiscipleManager._rng`
> 是 `GameRngManager.getRng(AI_SECT)` 的影子拷贝；真源在 AUTHORITATIVE 下已委托 C++ `kAiSect`
> 分区，且该分区**本就在 `rngStates` 协议面内**）再下沉自愈；缺的 C++ 原语仅为编排三件
> （`initializeSectDisciples` / `fillDisciplesToTarget` / `isGearCompleteForLevel`），
> 生成/装备/截断原语已在 `ai_sect_recruit.h`。
>
> **已收口**：③ `lockedBeastIds` UI 操作面 → ✅ batch-23（`BEAST_VIEW_LOCK_TX=1730`）；
> ④ 弟子管理残余 → ✅ batch-24（`SPIRIT_ROOT_WASH_CONFIRM_TX=1732` /
> `TRAIT_WASH_CONFIRM_TX=1733`；`recruitDisciple` 经复核已有 native 臂（`tryNativeManualRecruit`，
> batch-14 名字随机源分区化），状态同步族为槽位派生逻辑、无 C++ 状态写入者）；
> ⑤ 月年编排残余 → ✅ 设置项（batch-23，`SETTINGS_PATCH_TX=1731`，17 字段）；
> 天劫（非确定性模板随机与凭据溢出抑制同一事务、`recordPhaseClear` 与领奖共用
> `heavenlyTrialState` 段）与洞府探索（仅月结 Kotlin 回退编排内）**登记不下沉**。
>
> **结论：关闭批（batch-21）前置 = ADR 阶段 1 的两批交付**（不再有"待拍板"阻塞项）。

> **✅ 前置达成（2026-09-14 收口批 §2.58 实测更新）**：ADR **阶段 1 三项全部交付**——
> ① 库存开袋 → `storage_bag_tx.h` + `STORAGE_BAG_OPEN_TX=1734`，7 处抽签全部显式传
> `EXPLORATION` 分区 rng（`InventoryFacadeImplBagOps.kt`）；
> ② AI RNG 归一 → 影子流摘除、`rng` 改解析式（委托模式取通道分区 9 / 非委托取分区 6），
> **并修复了播种态根因缺陷**（`initForSlot` 曾写裸种子而非 `fromSeed` 混种态 ⇒
> 同一 `aiSeed` 两侧两条序列；新增 `DiffAiRngSeedingTest` 跨语言逐位锁守）。
>
> **`aiSectDisciples` 自愈下沉 C++ 改判为待拍板**：RNG 归一后该路径已**无自持流、无影子拷贝**
> （消费真源分区），下沉收益不明 ⇒ 按"登记不下沉、可复议"处置（handover §4.1，
> [ADR](adr/rng-determinism-remediation.md) §10 债表）。
>
> **⚠️ 关闭前仍需先收敛一项**：`DiffYearSettlementTest` 1 例 AI 招募逐字段分歧
> （分歧窗口已收窄到"第二名 AI 弟子的装备/功法段"；该例暴露的是 Kotlin 夹具与 C++ 生产编排
> 之间的 AI 分区消费差——通道关闭后**无兜底**，须先钉死再执行 §4.3 关闭动作）。
> → **✅ 已于 2026-09-13 清偿（handover §2.59.1，根因 = 夹具快照 9 号通道键垃圾值）**。

> **🔴 2026-09-15 滚动更新（batch-21 前置复核实测，推翻本条"前置达成 ⇒ 可开"口径）**：
> 本条上方的"✅ 前置达成"仅覆盖 **ADR 阶段 1 三项交付**与**待拍板项清零**，**不等于**
> "各域稳态写者归 C++"。**288 站点穷尽审计（§4.4）实测：14 个域无一可整体关闭**——
> 弟子表稳态写者 46 站点、9 类实体集合全部有稳态写者（82 站点）、64 个 gameData 字段
> 仍有稳态写者（UI 操作面无 native 臂 / native 事务后 Kotlin 残差 / 月年编排内活路 /
> 平台效应与自愈族）。**⇒ "反向通道按域全关"的前置再次改判为不成立**，属长期主轴：
> 每完成一个域的 UI 操作面下沉，才可关闭该域（机制与回滚见 §4.4）。
> 本批已落地**可证关闭面**：67 个 gameData 字段 + 顶层段 `lockedBeastIds`。
> 残余写者清单即下一步下沉目标，落 §4.4 域级结论表。


### 4.4 反向捕获写入点穷尽审计（2026-09-15，batch-21 前置复核）

> **结论：关闭前置不成立**——**288 个写入点 / 100 文件**逐条判定后，**14 个域无一可整体关闭**；
> 可证关闭面 = **68 个状态单元**（67 个 gameData 字段 + 1 个顶层段 `lockedBeastIds`），
> 其余单元（弟子通道 + 9 类实体集合 + 64 个 gameData 字段）**必须继续回导**。
> 本结论推翻 §4.3 曾记的"前置达成 ⇒ batch-21 可开"口径（见 §4.3 末尾滚动更新）。

**审计口径（六类判定，逐站点带 file:line 证据）**：

| 类 | 含义 | 判定要点 |
|---|---|---|
| `MIRROR` | 镜像事务（`updateMirror`） | **不参与**反向捕获，与关闭无关 |
| `FALLBACK_ONLY` | 仅 native 臂未执行时可达 | 前置 `if (nativeTx.x()) return` / `tryExecuteNative ?: run{}` / `!NativeEngineFlag.authoritative`；**业务失败信封也会触发回退臂** |
| `NATIVE_ARM_RESIDUAL` | native 事务成功后仍执行的 Kotlin 残余 | 残留派生清理 / 平台效应 / Room 回放 / 状态同步 ⇒ 必须回导 |
| `STEADY_KOTLIN` | 稳态可达且该操作无 native 臂 | UI 直改 / 循环钩子 / 墙钟运行态 / 月年编排内活路 ⇒ 必须回导 |
| `LOAD_BOOT` | 仅新档/读档/重启/boot 序列 | 随后全量导入 C++（`importToNative`）吸收 |
| `UNKNOWN` | 无法判定 | 本轮实测=**零调用者死代码**（统一登记为死码清理项） |

**域级结论表**（稳态写者 = 必须保留回导的理由；关闭单元 = 本批已摘出信封的单元）：

| 域 | 稳态写者（代表站点） | 关闭单元 |
|---|---|---|
| 库存 | `InventoryFacadeImpl.kt:678`（开袋逐件入库）、`InventorySystem.kt:138/装备Ops4.kt:107/丹药Ops5.kt:147`（统一入库入口）、`InventoryDelegate.kt:157/:177`（自动购买列表 UI 直改） | 4 字段（商人收购池/上架池/刷新凭据） |
| 建筑 | `BuildingNativeTx.kt:163`（拆除残差）、`BuildingFacadeImpl同步Ops.kt:281`（月变没收）、`BuildingDelegate.kt:145`（放置槽位残差） | 0 |
| 道路 | `RoadFacadeImpl.kt:41/:141`（native 臂后槽位/回执残差） | 1 字段（`roads`） |
| 巡逻/住所/矿场 | `SpiritMineViewModel.kt:89/:147/:183/:252`（灵矿槽位 UI 直改）、`GameEnginePatrolOps.kt:49`（矿场自愈） | 4 字段（含**死 API** `patrolConfig`） |
| 弟子管理 | `GameEngineCoordination.kt:99/:120/:138`、`DiscipleFacadeImpl战斗Ops2.kt:93/:119/:138/:157/:271`（赏赐/服药）、`DiscipleStatusService.kt:225/:279/:373`（状态派生）、`DiscipleSlotManager.kt:59`、**`GameEngineManualOps.kt:137`（功法替换——2026-09-15 事实核查新增：活 UI `DiscipleDetailScreen.kt:954`，无 native 臂且从未登记）**、**`GameEngine.kt:276`（婚姻审批——声明有 native 臂但实测未接线，`DISCIPLE_LIFECYCLE_MARRY_APPROVE=1592` Kotlin 零引用）** | 5 字段（血炼完成链/待入特质/队伍初始化） |
| 招募/派遣/俘虏 | `DiscipleFacadeImpl功法Ops1.kt:71`（入宗 lifeEvent）、`DiscipleService.kt:142`、`RecruitService.kt:398` | 3 字段（`availableMissions` 因对拍 harness 保留） |
| 生产/灵田 | `ProductionProcessorCleaOps3.kt:291`（月结前 repo→镜像对齐）、`ProductionProcessor构筑Ops2.kt:250/:341/:405` | 2 字段（配方/功法解锁） |
| 秘境 | `GameEngineSecretRealmOps.kt:57`（出发换岗）、`SecretRealmNativeOps.kt:100/:263`（到期兜底/战报） | 1 字段（`cultivatorCaves`，洞府整族死链） |
| 月年编排 | `GameEngineCoreMonthOps.kt:90` / `YearOps.kt:126`（native 结算后 Kotlin 扇出）、`GuideOps.kt:57`、`RedeemCodeService.kt:153/:402` | 27 字段（政策/引导/设置项 17/年度报告等） |
| 外交/好感/附庸 | `DiplomacyService.kt:145/:261`、`VassalService.kt:99/:324`、`GameEngineDiplomacyOps.kt:18` | 6 字段 |
| AI 宗门 | `SaveFacadeImpl.kt:56`（存档前自愈）、`GameEngineBattleOps.kt:176/:339`、`LifecycleOps.kt:177/:196` | 1 字段（`aiSectPersonalities`） |
| 战斗/探索 | `CombatService.kt:78`（伤亡残差）、`WorldBattleOps.kt:188/:288`、`GameEngineBattleOps.kt:66/:274/:339/:366`、`ExplorationNativeOps.kt:134` | 6 字段 + **顶层段 `lockedBeastIds`** |
| 弟子生命周期 | `DiscipleLifecycleProcessor.kt:489`（槽位清理）、`DiscipleLifecycleManager.kt:100/:121`、`GameEngine.kt:277/:306`（婚姻提议审批） | 0 |
| 存档/读档/自愈 | `SaveFacadeImpl.kt:56`、`GameEngineServiceOps.kt:40/:77` | 9 字段（时间三件/槽位/版本/种子/`rngStates`） |

**滚动更新（2026-09-15，W4-A/B/C 三批集成后复核）**——上表所列稳态写者中**已就地消除**的站点（逐条带 `file:line` 证据；**其余站点本轮未复核**，域级"可整体关闭"结论**仍无一成立**，关闭判定统一留 w3-13/W4-D 的"先禁用 + 完整业务周期观察"门禁）：

| 域 | 原稳态写者站点 | 集成后形态（证据） | 来源 |
|---|---|---|---|
| 弟子管理 | `GameEngineCoordination.kt:99/:120/:138` | 现为 native 臂：`:123` RENAME / `:149` CHANGE_TYPE / `:173` TOGGLE_FOLLOW（`ActionIds.DISCIPLE_OP_*`） | §2.62 |
| 弟子管理 | `DiscipleFacadeImpl战斗Ops2.kt:93/:119`（赏赐/服药） | 现为 native 臂：`:99` REWARD_ITEM / `:296` USE_PILL | §2.62 |
| 弟子管理 | `GameEngineManualOps.kt:137` 功法替换（无 native 臂、从未登记） | 现为 native 臂：`:141` `tryDiscipleTxNative(DISCIPLE_OP_REPLACE_MANUAL)` | §2.62 |
| 弟子管理 | `DiscipleStatusService.kt:225/:279/:373`（状态派生） | 现为 native 臂：`:296` SYNC_ALL_STATUSES / `:409` SYNC_STATUS | §2.62 |
| 弟子管理 | （血炼完成链） | `GameEngineBloodRefinementOps.kt:66` `DISCIPLE_OP_START_BLOOD_REFINEMENT` native 臂 | §2.62 |
| 弟子生命周期 | `GameEngine.kt:276` 婚姻审批（**声明有 native 臂但实测未接线**） | 已接线：`:288` `tryDiscipleOpNative(DISCIPLE_LIFECYCLE_MARRY_APPROVE=1592)` | §2.62.2 |
| 弟子生命周期 | `GameEngine.kt:277/:306` 婚姻提议审批/拒绝 | 拒绝侧下沉：`:339` `tryDiscipleOpNative(DISCIPLE_LIFECYCLE_MARRY_REJECT=1750)` | §2.62.2 |
| 巡逻/住所/矿场 | `SpiritMineViewModel.kt:89/:147/:183/:252`（灵矿槽位 UI 直改） | 槽位整表覆写改走统一 native 面 `PATROL_UPDATE_SPIRIT_MINE_SLOTS`（`:142/:179/:250` 三处调用点）；同批删 2 个死 API | §2.63.B1 |
| 秘境 | `cultivatorCaves`（洞府整族死链） | 死链入口已删（`CaveExplorationRewardOps.kt` 整文件删除 + `CultivationService` 死委托移除）；`processSectDisciplesAging` / `processAISectOperations` 等**活路保留** | §2.63.B4 |
| 战斗 | `CombatService.kt:78`（伤亡残差） | 现为 native 臂：`GameEngineNativeOps.tryExecuteNative(BATTLE_CASUALTY_SETTLE_TX=1780)`；标记/装备/槽位/HP 残差留 Kotlin | §2.64.4 |
| 战斗/探索 | `WorldBattleOps` / `GameEngineBattleOps` / `ExplorationNativeOps` | 战前结算 1782 + 胜利发奖 1781 下沉；秘境换岗 1800 / 到期兜底 1801 下沉 | §2.64.4 |
| 月年编排 | （W4-A A4 生产残差核对） | **零代码改动**：续炼链已 C++ 直辖；"对齐窗口"删除登记 W4-D | §2.62.4 |
| 月年编排 | `GameEngineGuideOps.kt:57` claimGuideReward（引导领奖，无 native 臂） | 已下沉：`GUIDE_REWARD_CLAIM_TX=1830`（`guide_reward_tx.h`——25 任务注册表/9 类条件求值/可行性预检/SYSTEM 2×nextLong UUID 复刻/凭据溢出抑制）；`guideClaimedRewardIds` 转入关闭（W4-D 分片）；Kotlin 残余 = 回退臂-only；UI 奖励卡片两臂同形 | §2.73 |
| 月年编排 | `GameEngineCoreMonthOps.kt:90` / `YearOps.kt:126` 残留执行器 | 逐条判定收口：purchaseLogs/丧亲 = lifeEvents 瞬态列（@Ignore 非协议）⇒ Kotlin 日志；秘境关闭邮件 = DAO 通知；死亡链袋物化 = 平台效应链（openStorageBag 逐件入库仍为两臂共用稳态写者 ⇒ 物化下沉无关闭收益，不迁）；兑换码 `RedeemCodeService.kt:153/:402` 登记不下沉（RNG 红线） | §2.73 |
| 月年编排 | 附庸年贡/附属年贡/月度脱离（B4 转入项） | 实裁：C++ 逻辑已在位（`year_settlement.h` T1 #1/#2 + `month_settlement.h` 子事件 12，AUTHORITATIVE 原生执行）；Kotlin 调用点（`CultivationEventMonthlyOps.kt:73/:105/:125/:126`）保留为 flag-OFF 回退臂——开臂即双重扣贡/抽取 ⇒ 不占号 | §2.73 |

**新增关闭机制（`ReverseChannelPolicy`，core:domain）**：

- **双端同源闸门**——捕获侧（`GameStateStoreImpl.captureReverseDirty`：弟子通道 + 集合段停载荷构造）与信封侧
  （`StateSyncService.buildReverseEnvelope`：gameData 字段级 dirty 集 + 顶层段 + 集合段）读同一策略。
- **逐域回滚**——`reopenDomain(domain)` 一键恢复该域全部单元（单域回滚，无需改代码）。
- **关闭域写入检测**——关闭后若仍有 Kotlin 写者：集合/弟子通道在捕获侧（引用变化即命中）、
  gameData 字段在信封构建时（与"C++ 已知值基线"比较，容忍 `12000` vs `12000.0` 输出形式差异）
  命中即 `DomainLog.e` + 诊断计数 ⇒ **漏域从静默丢数据变为可归因缺陷**。
- **穷尽分类守卫**（`ReverseChannelPolicyGuardTest`）——`closed ∪ transported == GameData 序列化面全字段`
  （135 字段，新增字段未分类即测试红）；域结论完整性与证据格式；**红线**：弟子通道与
  `aiSectDisciples` 段必须保持传输（稳态写者实测存在），关闭即测试红。

**关闭的验收门禁 = Diff 对拍全绿**：关闭使 `DiffAuthoritativeTickTest` 分歧的字段
（`spiritMineLastSettledMonth` / `annualAlchemyCount` / `availableMissions` / `yearlyReports`）
一律**保留传输**并登记——该 harness 的 AUTHORITATIVE 管线把 **Kotlin 月/年完整编排**纳入稳态
（`runBoundary` 直调 `MonthSettlementExecutor.execute` / `YearSettlementExecutor.execute`），
而生产 AUTHORITATIVE 月结走 C++（`GameEngineCoreMonthOps.kt:68` 前置 native 就绪）⇒
**harness 为生产超集**；后续把 harness 对齐生产（C++ 月结 + Kotlin 残差）后这批字段可重评。

**体积构成实测**（`ReverseChannelVolumeProfileTest`，单元 harness）：稳态窗口（40 弟子 / 20 丹药 /
3 gameData 字段 / 1 弟子 / 1 集合变更）= **合计 20121B**，其中 **gameData 段 38B**、
**弟子段 2462B**、**pills 段 17549B** ⇒ 信封体积主因是**实体通道的全实体 upsert**，
gameData 字段级 dirty 集已非瓶颈；这与"弟子通道 + 集合段必须保留"的审计结论互为印证——
**体积归零的前置不是字段级裁剪，而是弟子/库存域稳态写者归零**。

---

## 5. WS-1 批实测基准（2026-09-05）

| 指标 | 改动前 | 改动后 | 实测 |
|---|---|---|---|
| 反向 gameData 段（典型单字段窗口） | 全量 5349B / 134 键 | 字段级 dirty 集 | **70B / 1 键（-98.7%）**；含每旬 C++ 镜像回流变更字段（时间/灵石等）仍约 -94% |
| C++ diffToJson（每旬全脏 100 弟子） | 10.6ms | 8.0ms | **-24%**（两次全量序列化+深拷贝 → 单次序列化+缓存树比较） |
| C++ diffToJson（1000 弟子） | 122ms | 96ms | **-22%** |
| C++ diffToJson（5000 弟子） | 712ms | 555ms | **-22%**（idle 场景 -32~-40%） |
| 库存操作镜像 | 每次动作全量导出+全表替换 | exportDirty 字段级回读（查询类动作零镜像） | O(全状态) → O(变更) |
| 弟子镜像应用 | assembleAll+replaceAll O(N) | 信封 id 行级精确应用 | O(N) → O(k) |

> 残留口径：每旬弟子全脏场景下，镜像成本受"全量实体 JSON 序列化"支配（协议形状决定，
> 非本批可消）；列级 delta / 二进制通道随计划 v2 阶段 3 数据导向存储（dirty_tracker.h
> 自述既定待办）落地。bench：`dirty_tracker_bench_test.cpp`（桌面 ctest）。

---

## 6. 表现流取用方式口径（2026-09-15，W4 §2.B 落地）

`PresentationRandom` 的消费口径由"共享单例流"收口为"**按存档 `mapSeed` × 场景键派生**"：
`BootSequenceController.generateMapPreloadData` 播种（全仓唯一接线点，守卫锁死），
调用方经 `scene(key)` 取**独立场景流** ⇒ 同一存档同一场景结果恒定（跨会话一致）、
零协议面、不污染决策流。场景键登记表见 [rng-source-inventory.md §7](rng-source-inventory.md)。
云层/装饰保持"持续变化"语义不改。决策类零扰动（`Diff*` 对拍全绿为证）。
