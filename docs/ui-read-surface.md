# UI 读取面清单（WS-1.4 镜像契约收敛）

| 项 | 内容 |
|---|---|
| 文档性质 | WS-1.4 交付物：C++→Kotlin 镜像的**合法内容上限** + 反向通道**分域关闭依据** |
| 依据 | docs/cpp-migration-implementation-plan.md WS-1 第 4 条（2026-09-04）+ 审计 P0-2 |
| 实施日期 | 2026-09-05（随 WS-1 同步通道降本批）；§4 于 2026-09-08 按 S4-S8+WS-5 清偿后逐域写者审计重写（结论：无域可关，关闭前置 = UI 操作面逐域下沉） |
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

### 4.1 逐域写者审计（2026-09-08，S4-S8 + WS-5 全部清偿后实测）

> **结论先行：当前没有任何一个域满足关闭条件。** S4-S8/WS-5 下沉的是**结算/事务
> 核心**（旬结七步、月结步骤 4a/4b/6/9、任务结算、秘境会话、生产排班事务、
> 手动/一键招募主路径、地形生成），而 §4.0 假设"关闭条件已达成"的各域实际仍存在
> 大量 **UI 操作面 Kotlin 直改写者**（约 265 个 `stateStore.update` 生产调用点，
> 15+ 域）——"玩家操作 Kotlin 产生 → tick ⑤ 反向回导 C++"是 2026-08-31 根因修复
> 后的现行设计契约。**反向通道按域全关的前置 = UI 操作面逐域下沉 C++**（每域一个
> WS-2 规模的批次），属长期主轴工作，不是收敛批可完成项。

| 域 | Kotlin 直改写者（2026-09-08 实测残余） | 下沉批次（关闭前置） |
|---|---|---|
| 库存 | ~~sell 六族/bulkSell、商人收购出售、上架/撤下、consumeMaterialByName~~ **✅ 出售/上架/材料消耗族已下沉（2026-09-11，W2-a，handover §2.41）**：单类出售六入口 + 批量出售 + 商人收购 + 玩家上架/撤下 + 按名称品阶材料消耗稳态写者归 C++（`inventory_tx.h` 事务，1520–1525），零 RNG 纯确定性变换，Kotlin 原路径降级回退臂；**残余**：商人购买（buyMerchantItem，含容量预测 + MerchantItemConverter 模板转换）、开袋（EXPLORATION 分区 RNG + `Random.Default` 模板抽取，风险最高）、充公（BagItemReconstructor 模板重建 + 装备实例回仓族 + 溢出抑制三态）、add/remove/sort/consolidate/lock 五族已 C++ 转发 + Kotlin 回退保留 | 出售/上架/材料消耗批 ✅ → 商人购买批 / 充公批 / 开袋批 |
| ~~建筑放置/拆除/升级~~ | ~~placeBuilding/moveBuilding/upgradeBuilding/removeBuilding~~（~~place/move/upgrade/remove~~） **✅ 四操作已下沉（2026-09-10，batch-06，handover §2.35）**：AUTHORITATIVE 稳态写者归 C++（building_tx.h 事务，1450–1454），Kotlin 原路径降级为回退臂；槽位派生/弟子释放残差留 Kotlin（BuildingFeatureRegistry 槽组语义，回执驱动）；剩余残余：enterSect/BootSequence 迁移（W2/W3 后续波次） | ~~建筑放置事务批~~ ✅（残余 → 后续波次） |
| ~~道路~~ | ~~placeRoad/removeRoad（Kotlin 直改 + 即时回导加固）~~ **✅ 已下沉（2026-09-10，batch-07，handover §2.36）**：AUTHORITATIVE 稳态写者归 C++（road_tx.h 事务，1470/1471），Kotlin 原路径降级为回退臂；RoadMaskTracker/RoadTiling 为纯 UI 缓存不迁（镜像回读驱动） | ~~道路事务批~~ ✅ |
| 巡逻/探索 | assign/remove/swap/autoAssignPatrolAtomic、worldLevels 战斗结算 | 巡逻批 |
| 弟子管理 | ~~装备穿脱/功法学习卸下/亲传+藏经阁任命卸任~~ **✅ 第一子批已下沉（2026-09-10，batch-08，handover §2.37）**：零 RNG 纯事务稳态写者归 C++（disciple_tx.h 六事务，1480–1485），Kotlin 原路径降级回退臂（GameEngineManualOps/GameEngineDiscipleSlotOps native 分支，GameViewModel/DiscipleDelegate/DiscipleEquipmentService 零改动）；**残余**：收徒/逐出/状态同步/婚姻/仓库驻守/玉符/**checkpoint**/长老单值槽任命（usecase 编排域）→ W3 后续子批 | 弟子管理族 ×N 批（第一子批 ✅） |
| 招募/派遣/俘虏 | 手动+一键主路径已 C++；回退路径、列表刷新/老化、lifeEvents 补写、startMission、奖励发放、俘虏装备物化 | 残余族 |
| 生产 | 手动排班/重置已 C++（S7）；自动续班、镜像槽维护、回退路径 Kotlin 直改 | S4 窗口对齐已兜月结；UI 面残余 |
| 秘境 | 会话三入口已 C++（S6）；start 换岗/到期守卫/回退路径 Kotlin 直改 | 平台段保留评估 |
| ~~外交/好感/附庸~~ | ~~diplomacy/vassal/favor 全部 Kotlin 直改~~ **✅ 已下沉（2026-09-10，batch-09，handover §2.38）**：赠礼/结盟/散盟/附属建立解除稳态写者归 C++（diplomacy_tx.h 事务，1500–1502），Kotlin 原路径降级回退臂；聊天响应模板（Random.Default 非游戏分区）与月结面（已下沉）不在写者面；宣战/停战/和平经审计无 UI 操作面 | ~~外交族批次~~ ✅ |
| 月年编排 | processMonthYearChange 边界效果、玉符、洞府探索、天劫、兑换码、宗门升级、政策开关、设置项、guide、邮件附件 | 边界效果域逐批 |
| aiSectDisciples 段 | 战斗阵亡/吞并（月结/遭遇战 Kotlin 回退事务内）、load 自愈、存档自愈 | 月变真相源切换批（S-15/S-16）后反转或关闭 |
| 时间/结算输出域 | 无（只读镜像） | **已关**（无写入者，反向窗口天然不含） |

### 4.2 顶层 @Transient 段的通道现状（2026-09-08 审计订正）

| 段 | 增量通道 | 说明 |
|---|---|---|
| `aiSectDisciples` | ✅ 独立全量段（S-15 变化检测） | 战斗/AI 域 Kotlin 写者（回退路径）增量可达 |
| `lockedBeastIds` | ✅ 独立全量段（**2026-09-08 补齐**，S-15 同族） | 缺口加固：UI lockBeastView/unlockBeastView 此前只能经全量回导兜底到达 C++，增量窗口内 AUTHORITATIVE 月结跳过判定（锁定妖兽不被 AI 攻击）对新弹窗失效；现为变化检测 + 整体替换语义 |
| `aiSectBeastDirectTargets` / `aiSectBeastSkipCooldowns` | ❌ 无增量段（**可接受**） | Kotlin 写者仅存在于 Kotlin 月结回退路径（native 未就绪时）；AUTHORITATIVE 稳态由 C++ 独占并前向镜像。回退→AUTHORITATIVE 切换经 `ensureAuthoritativeNative` 全量导入，四段全部可达——无增量缺口 |

### 4.3 关闭动作（原样有效，但前置未达成）

关闭动作 = `GameStateStoreImpl.captureReverseDirty` 停止捕获该域 + `StateSyncService`
信封不再携带该域段；每关闭一域，反向信封体积相应归零（可观测验收）。
**在 4.1 表全部域下沉前执行任何"停捕获"都是数据丢失缺陷**（该域 Kotlin 写入将永达
C++）。

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
