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
| 弟子管理 | ~~装备穿脱/功法学习卸下/亲传+藏经阁任命卸任~~ **✅ 第一子批（2026-09-10，batch-08，§2.37）**（`disciple_tx.h` 六事务，1480–1485）；**✅ 第二子批（2026-09-11，batch-14，§2.45）**（逐出/拜师/婚姻批准/释放思过/年俸开关，`disciple_lifecycle_tx.h`，1590–1594）**+ 14b 名字随机源分区化**；**✅ 第三子批（2026-09-12，batch-15，§2.46）**（长老单值槽任命卸任 / 仓库驻守 / 洗炼消耗三族，`appointment_tx.h`，1610–1616）；**残余**：收徒（`recruitDisciple` 待拍板名字种子策略）/ 状态同步族 / 特质 confirm 两入口（纯数据写） | 弟子管理族（**三子批全部 ✅**，残余归后续波次） |
| 招募/派遣/俘虏 | ~~手动+一键主路径已 C++；回退路径、列表刷新/老化、lifeEvents 补写~~ **✅ 残余三直调点已下沉（2026-09-12，batch-16，§2.47）**：`removeFromRecruitList` / `refreshRecruitList` / `ageRecruitList` → `recruit_tx.h` 复用年度权威链零复制（ActionId 1630–1632）；**审计判定不下沉**：MerchantAndRecruitService 零招募写者、派遣 `startMission`（惰性门留月变真相源批）、奖励发放（结算域已 C++）、`id=""` 候选跨年去重（拍板项） | 残余族 ✅ |
| 生产 | 手动排班/重置已 C++（S7）；**✅ 生产 UI 面 + 灵田种植族已下沉（2026-09-12，batch-17，§2.48）**（四生产槽 UI 事务 + 四灵田种植事务，ActionId 1650–1657）；**审计判定保留 Kotlin**：`autoHarvestCompletedAlchemySlots`（读档路径/AUTHORITATIVE 基线窗口——迁此会在首月读档产生"免费收获"）、`MaterialConsumptionLog`（平台效应）、自动续班启动（S4 月结末尾已 C++） | **UI 面 ✅**；S4 窗口对齐已兜月结 |
| 秘境 | ~~会话三入口已 C++（S6）；start 换岗/到期守卫/回退路径 Kotlin 直改~~ **✅ 平台段已下沉（2026-09-12，batch-20a，handover §2.51）**：唯一未下沉写者 `continueSecretRealmExploration`（读档恢复：到期关闭/死局 endSession/成员净化/gate 重建）归 C++（`secret_realm_platform_tx.h`，ActionId 1710）；`autoAssignSecretRealmTeam` 审计为**纯只读选择器**（无写者）、pause/resume/renew 为**运行时时钟平台残差**（S5/S6 口径留 Kotlin）；**顺手根治** `secret_realm_settlement.h` 的 `kOpenYears` 移植缺陷（50→5，此前 AUTHORITATIVE 下秘境满 5 年还要再挂 45 年） | ~~平台段批次~~ ✅ |
| 月年编排 | ~~processMonthYearChange 边界效果、玉符、洞府探索、天劫、兑换码、宗门升级、政策开关、设置项、guide、邮件附件~~ **✅ 已审计收窄（2026-09-12，batch-18，§2.49）**：六候选域中仅 **guide 计数面三写者**（increment/batchUpdate/backfill → `boundary_tx.h`，1670–1672）与**政策开关三入口**（toggle/openRecruitment/spiritMineBoost → `government.h`，1680–1682）为 Kotlin 独占活路径，已下沉；**审计判定不下沉**：月年边界效果 / 战后 HP-MP / 游戏结束判定 / YearlyOpsQueue / 通用写入口 / claimGuideReward 六域（死代码 / 月变事务内步骤（C++ 权威已存在）/ 通用写入口 / Kotlin 注册表不可复刻）。**✅ 玉符/宗门升级（2026-09-12，batch-19，§2.50）**（`jade_tx.h`，1690–1693）；**不下沉**：兑换码 `redeemCode`（C++ 无物品随机生成器，RNG 红线）、邮件附件（无新写者）；**待办**：设置项 / 天劫（`HeavenlyTrial*`）/ 洞府探索（`CaveExplorationProcessor` 结算域）→ 若审计确认独立 UI 写者则另立批 | 边界效果域（**guide/政策/玉符/宗门 ✅**；设置项·天劫·洞府待审） |
| ~~外交/好感/附庸~~ | ~~diplomacy/vassal/favor 全部 Kotlin 直改~~ **✅ 已下沉（2026-09-10，batch-09，handover §2.38）**：赠礼/结盟/散盟/附属建立解除稳态写者归 C++（diplomacy_tx.h 事务，1500–1502），Kotlin 原路径降级回退臂；聊天响应模板（Random.Default 非游戏分区）与月结面（已下沉）不在写者面；宣战/停战/和平经审计无 UI 操作面 | ~~外交族批次~~ ✅ |
| aiSectDisciples 段 | ~~战斗阵亡/吞并（月结/遭遇战 Kotlin 回退事务内）、load 自愈、存档自愈~~ **⚠️ 部分下沉（2026-09-13，batch-20b，handover §2.51b）**：**攻宗的阵亡守军清理已下沉**（`sect_attack_tx.h removeDeadDefendersTx`，ActionId 1711——本段最大 UI 触发写者）**+ 魂魄发放（1712）**；**残余**：月结回退路径的战斗阵亡/吞并写者 + load/存档自愈写者 → 关闭前置仍差此二者复核 | 月变真相源切换批（S-15/S-16）后反转或关闭 |
| 时间/结算输出域 | 无（只读镜像） | **已关**（无写入者，反向窗口天然不含） |

**2026-09-14 batch-23/24 滚动更新（§2.55/§2.56/§2.57）**：

| 域 | 本轮处置 |
|---|---|
| `lockedBeastIds` UI 操作面 | **✅ 已下沉（batch-23）**：`GameEngine.lockBeastView`/`unlockBeastView`（`:319/:324`，`launchOnEngine` 调用面 = 妖兽详情弹窗开/关）稳态写者归 C++（`lock_beast_tx.h` `BEAST_VIEW_LOCK_TX=1730`；Set 语义 + 保序 + 幂等 + lockedCount 回执）；反向增量段（§2.21.2 补齐）维持不变 |
| 设置项域（原「月年编排残余」中的设置项） | **✅ 已下沉（batch-23）**：17 个 gameData 字段经 `SETTINGS_PATCH_TX=1731` 通用补丁——覆盖 `SettingsDelegate`（6：音频 2 + 战报弹窗 + 中/高阶自动出售 + 显示全部弟子）/ `AutoAssignDelegate`（7：突破丹药 2 + 自动装备 2 + 自动学习 2 + 道侣禁止灵根数 + 道侣同意）/ `DiscipleDelegate`（2：自动招募/自动拒绝过滤，1..5 预筛 + 惰性门残差留 Kotlin）；平台效应（`AudioConfig` / 惰性门 / 待处理提议清理）保留 Kotlin |
| 弟子管理残余 | **✅ 已清（batch-24）**：`confirmSpiritRootWash` / `confirmTraitWash` 两入口下沉（`appointment_tx.h` 事务 8/9，ActionId 1732/1733）——纯数据写（零玉符/零 RNG）+ checkpoint 与 lifespan 同步同事务；三态拒绝文案由 C++ 信封 `executeRaw` 回传。**弟子管理域至此无稳态 Kotlin 直改写者** |
| `aiSectDisciples` 段 | **⚠️ 改判：登记不下沉（附证据链，见 handover §2.57）**——`checkAndRepairAiSectDisciples`（load/save 自愈）消费 `AISectDiscipleManager` 的**独立 AI RNG 分区**，该分区状态**不在快照协议 `rngStates` 段内**，C++ 无镜像状态；复刻需先统一 AI RNG 通道（WS-4/AI 域规模）。**故该域构成 batch-21 剩余前置之一** |
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

> **前置现状（2026-09-13 实测）**：4.1 表中**多数域已 ✅**，但**尚未全部下沉**——剩余项：
> ① 库存**开袋**（双重 RNG，路线 B 备案不下沉，待拍板）；② 弟子管理残余（`recruitDisciple`
> 名字种子策略待拍板 / 状态同步族 / 特质 confirm 两入口）；③ `aiSectDisciples` 段残余
> （月结回退路径阵亡吞并 + load/存档自愈）；④ `lockedBeastIds` UI 操作面
> （`lockBeastView`/`unlockBeastView`，**至今未派工**）；⑤ 月年编排残余（设置项 / 天劫
> `HeavenlyTrial*` / 洞府探索 —— 待判断是否独立 UI 写者）。
> **结论：关闭批（batch-21）仍不可开**——须先处置上述五项（③④⑤ 需先审计定界）。

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
