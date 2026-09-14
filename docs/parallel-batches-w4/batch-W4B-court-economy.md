# W4-B 批次方案：内政与经济运营轴（UI 操作面收尾 + 平台效应回执化）

> ## 🚧 本批是**并行方案**——实施人员开工前必读
>
> **本批与 [W4-A](batch-W4A-disciple-building.md)、[W4-C](batch-W4C-battle-world.md) 是同一波次的三个并行批次，三者会同时在各自的工作树上施工。**
> 并行度是设计出来的，不是"注意避让"出来的——请先读 [README](README.md) §2（拆分依据）与 §4（协作协议），再开工。
>
> | 你必须知道的 | 内容 |
> |---|---|
> | **你的工作区（独占）** | `C:\Mnzm\XianxiaSectNative-w4b`，分支 `w4/b-court-economy`（由 `scripts/w4/setup-worktrees.ps1` 建立）。**不要**在主仓 `C:\Mnzm\XianxiaSectNative` 或另外两个工作树里改代码 |
> | **硬前置** | **W4-00（并行前置批）必须已合入 `main`**。判据：`scripts/action-catalog/w4b.mjs`、`src/dispatch_w4b.cpp`、`test/w4b_tests.cmake`、`reversechannel/W4BChannelClosures.kt` 四者均已存在，且 `git tag` 有 `w4-base` |
> | **你的独占文件** | `scripts/action-catalog/w4b.mjs`｜`src/dispatch_w4b.cpp`｜`test/w4b_tests.cmake`｜`reversechannel/W4BChannelClosures.kt`｜`FakeAtomicStateStore.kt`｜`SaveFacadeImpl.kt`｜`GameEngineSectLevelOps.kt` |
> | **🔴 绝对禁改**（另两批的面向对象，或全局共享面） | `scripts/action-catalog/w4a.mjs`、`w4c.mjs`｜`src/dispatch_w4a.cpp`、`dispatch_w4c.cpp`｜`test/w4a_tests.cmake`、`w4c_tests.cmake`｜`reversechannel/W4A*`、`W4C*`｜`scripts/gen-action-ids.mjs`｜`action_ids.h`、`ActionIds.kt`｜`src/execute_dispatch.cpp`｜`src/CMakeLists.txt`、`test/CMakeLists.txt`｜`ReverseChannelPolicy.kt`｜`GameStateStoreImpl.kt`｜`StateSyncService.kt`｜`GameViewModel.kt`｜`GameEngineCore.kt`｜**宿主文件族**（`CultivationEventMonthlyOps.kt`/`MonthSettlementResidualExecutor.kt`/`YearSettlementResidualExecutor.kt`/`GameEngineCoreMonthOps.kt`/`GameEngineCoreYearOps.kt`）｜**协议面**（`models.h`/`json_codec.cpp`/`game_core.*`/`GameData.kt`/`GameDatabase.kt`，归 W4-C）｜`RngSourceGuardTest.kt`（归 W4-A）｜`RngEngineIsolationGuardTest.kt`（归 W4-D）｜`CHANGELOG.md`、`changelog_entries.json`、handover §3/§4.1/§5/§6、`ui-read-surface.md` §4.4（收口人独占） |
> | **重活必须先取构建令牌** | `pwsh -File scripts/w4/build-token.ps1 -Acquire -Batch w4b` → 跑 Gradle 全量测试/detekt/NDK/lint/ctest 构建 → `-Release`。**不取令牌并跑 = 会让另两批的构建随机失败**（Gradle `classes.jar` 锁 / KSP 缓存损坏 / ctest 假失败） |
> | **提交纪律** | `git commit -- <本批触碰文件清单>`，**禁止整仓 `git add`**（历史事故：混入 433 个并行改动文件）；每个子批提交后打 `w4b/<nn>` tag + `git bundle`（`.git` 曾两次被毁） |
> | **跨批接口约定** | `SaveFacadeImpl.getStateSnapshot()` 的**数据源语义不得改变**（W4-C 的 WS-5b 依赖它"只读 `stateStore.*Snapshot`"）——详见本批 §2.3 B4 与 W4-C §2.3.3 R6 |
> | **合并** | 由收口人按 W4-A → W4-B → W4-C → W4-D 顺序合并；**冲突禁取 theirs**；生成物冲突一律"重生成" |
>
> **判据（机器可执行）**：`git diff --name-only w4-base..w4/b-court-economy` 中若出现上表"绝对禁改"里的任一文件 ⇒ **本批打回**。

| 项 | 内容 |
|---|---|
| 批次代号 | **W4-B**（worktree `C:\Mnzm\XianxiaSectNative-w4b`，分支 `w4/b-court-economy`） |
| 覆盖的 w3 子批 | **w3-03 → w3-12**（串行）｜**w3-04**｜**w3-05** |
| 支线工作项 | Jade 凭据持久化环境缺陷专项｜死代码：`GameEnginePatrolOps.updatePatrolConfig` 死 API、洞府探索整族死链 |
| ActionId 段 | **1760–1765**（w3-03）｜**1766–1769**（w3-04）｜**1770–1779**（w3-05）｜**1840–1849**（w3-12） |
| 决策分级 | **架构级重构**（跨模块/跨语言、含"平台效应回执化"架构改造） |
| 硬前置 | **W4-00 已完成并合入 `main`** |
| 只读（禁止触碰） | `GameStateStoreImpl.kt`｜`StateSyncService.kt`｜`GameViewModel.kt`｜`GameEngineCore.kt`｜`src/execute_dispatch.cpp`｜`test/CMakeLists.txt`｜`scripts/gen-action-ids.mjs`｜`ReverseChannelPolicy.kt`｜`state/models.h`｜`src/json_codec.cpp`｜`game_core.h/.cpp`｜`GameData.kt`｜`GameDatabase.kt`（以上协议面归 W4-C，见 README §5.4 租约）｜`RngSourceGuardTest.kt`（归 W4-A）｜`RngEngineIsolationGuardTest.kt`（归 W4-D）｜**宿主文件族**（`CultivationEventMonthlyOps.kt` / `MonthSettlementResidualExecutor.kt` / `YearSettlementResidualExecutor.kt` / `GameEngineCoreMonthOps.kt` / `GameEngineCoreYearOps.kt`，README §2.3） |
| handover 章节 | **§2.63**（只在本小节内追加） |

---

## 1. 背景与目标

### 1.1 需求要点

本批负责**巡逻/住所/矿场域、玉符运行时域、库存（邮件附件/行商）域、外交/好感/附庸域、存档自愈与运行态域**的剩余稳态写者搬迁。

这五个域有一个**共同的结构特征**，决定了本批的技术重心与其它两批不同：

> 它们的"写者"大多不是玩法逻辑，而是**平台效应的回执**——墙钟读数（玉符跨天）、网络投递结果（邮件附件）、平台生命周期（存档前自愈、内存压力裁剪）、UI 直改槽位（灵矿）。按 [ADR §9.1-4/9.2](../adr/reverse-channel-elimination.md)，行业明确反对把平台副作用写回确定性核心，正确形态是"**命令进 C++ 事务、回执出 C++，平台副作用留宿主层**"。

因此本批的核心设计动作 = **把 ③ 类（平台效应）逐点重构为"命令 + 回执"**，而不是把它们 1:1 搬进 C++。

支线两项：

1. **Jade 凭据持久化环境缺陷**：`FakeAtomicStateStore` 的事务缓冲与 `sectLevelClaimRecords` 交互（已复现，handover §2.50 登记"待专项"）。它会让本批的玉符 GateTest 出现"诡异失败"，必须在 w3-04 之前独立清偿。
2. **死代码**：`updatePatrolConfig` 死 API（零生产调用方，UI 走 plural 版）与洞府探索整族死链（唯一入口 `CultivationService` 零调用）——两者都在本批域内，**顺手删除**以免日后被接线成新的丢数据点。

### 1.2 成功标准（可验收）

| # | 标准 | 度量 |
|---|---|---|
| 1 | 五个域的 `STEADY_KOTLIN` 写者归零 | 批内穷尽扫描表无 `STEADY_KOTLIN` 残留；残余仅 `FALLBACK_ONLY` / `LOAD_BOOT` / `MIRROR` |
| 2 | 传输单元关闭 | `W4BChannelClosures.kt` 覆盖本批全部单元；**关闭域写入检测零命中** |
| 3 | **平台效应全部外置** | 墙钟/邮件投递/内存压力/Room 回放的**副作用**仍在 Kotlin；进 C++ 的只有"读数"与"决策结果" |
| 4 | 玉符账目一致性 | 玉符（绝对值覆盖写模型）的扣减**在 C++ 事务内承扣**；`JadeSymbolConsumptionGuardTest` 绿；无双花/回涨 |
| 5 | 行为零变更 | 各域 GateTest 逐位一致 + 47 个 `Diff*` 全绿 + 桌面 C++ 全量绿 + `:core:engine` 全量绿 |
| 6 | 死代码清零 | `updatePatrolConfig` 与洞府探索整族从生产源码消失；`git grep` 归零 |
| 7 | Jade 环境缺陷清偿 | `FakeAtomicStateStore` 缺陷复现用例由红转绿（或以"绕过 + 登记"结案并写明排除依据） |

---

## 2. 技术方案

### 2.1 交付节奏（批次内串行序）

```
B0  Jade 环境缺陷专项        ── 必须最先（否则 w3-04 的 GateTest 会受污染）
B1  w3-03 巡逻/住所/矿场自愈  （1760–1765）── 顺手删 updatePatrolConfig 死 API
B2  w3-04 玉符运行时         （1766–1769）
B3  w3-05 邮件附件 + 行商刷新 （1770–1779）
B4  w3-12 外交/自愈/运行态    （1840–1849）← 必须在 B1 之后；顺手删洞府探索死链
```

> **B4 ← B1 的理由**（w3 §5 串行点 `w3-12 ← w3-03`）：w3-12 的"存档前自愈 / 内存裁剪 / 检查点重锚"要在**巡逻域已收口**之后才能判定"哪些自愈步骤仍需要"（自愈的目的正是修复被 Kotlin 写坏的槽位数据；写者归 C++ 后自愈的输入面变了）。

### 2.2 ③类（平台效应）的**统一处置模板**（本批核心架构动作）

对每一个 ③ 类写者，按固定四步处置，**禁止**"原样搬进 C++"：

| 步 | 动作 | 判据 |
|---|---|---|
| ① | **识别读数与副作用** | 哪部分是"平台给的事实"（墙钟 `nowWall`、热档、网络投递结果、内存压力等级），哪部分是"游戏状态的变更" |
| ② | **读数作为参数推入 C++** | C++ 事务签名接受读数参数（如 `nowWallMs`），**禁止 C++ 内直接取系统时间**（`rules/cpp-priority.md` §3.3：现实时间一律 `Clock` 注入） |
| ③ | **C++ 签发结果（含回执）** | C++ 事务完成校验 + 状态写 + 返回回执信封（受影响 id 列表 / 应发总额 / 应投递清单） |
| ④ | **Kotlin 只执行副作用** | 通知/支付/网络投递/Room 回放仍在 Kotlin，由回执驱动；**副作用本身不写游戏状态** |

**红线**：读数推送点必须在批内显式定义并加测试（**同一 tick 内幂等**）——ADR 盲区 3 明列"平台读数的时序假设"为风险项。

### 2.3 各子批的技术要点

#### B0 · Jade 凭据持久化环境缺陷专项（无 ActionId）

| 项 | 内容 |
|---|---|
| 现象 | 首领奖后 `writeSectLevelRewards` 已完成（`allSucceeded=true` 分支已执行）但 `stateStore.gameDataSnapshot.sectLevelClaimRecords` 仍为 0 条（handover §2.50 坑 + §3 未达项 6 + §4.1） |
| **实测现状** | **仍开放**——无修复记录、无对应测试守卫。`JadeNativeTxGateTest.kt:233-254` 首次领取链仍真实执行；冷却用例 `:257-282` 的注释 `:259-261` **自述绕开**（`:262-268` 直接 `store.update { gameData = gameData.copy(sectLevelClaimRecords = listOf(...)) }` 播种凭据） |
| 缺陷面代码 | `FakeAtomicStateStore.kt`（336L，`internal class` `:58`）：事务缓冲 `writeDepth`/`activeMutable` `:242-289`；**`update` `:252-260` / `updateAndReturn` `:262-271` 仅当 `writeDepth == 0` 才 `syncFlows`**；`syncFlows` `:295-311`（把事务缓冲写回各 StateFlow，含 `_gameData.value`）；`newMutable` `:319-335`（`gameData` 取自 `_gameData.value`，而 `discipleTables`/`materials` 是**持久实例**） |
| 已知同族 | ① `FakeAtomicStateStore.materials` 是**持久化 EntityStore**，不经 StateFlow 回灌 ⇒ 测试里 `store.materials.value = listOf(...)` 不进事务缓冲（findings 条 17）；② `GameEngineSectLevelOps.kt:94-104` 生产侧已按根因修复（凭据不写即报失败，禁止 Success；KDoc `:90-93`） |
| 目标 | 定位"事务缓冲与部分字段不互通"的**根因**：是 fake 的缺陷，还是 `GameStateStore` 契约本身要求"事务内读写必须同源"？<br>· **fake 缺陷** ⇒ 根因修复 fake（**禁止特判绕过**）；<br>· **契约要求** ⇒ 写守卫测试把该契约落成可执行断言 + 更新 fake 使其与契约一致 |
| 交付 | 修复 + ≥2 用例（缺陷复现用例转绿 + 契约守卫用例）；若最终判定"不可在 fake 内修复"⇒ 必须给出**为什么不可能**的因果链 + 替代验证口径（不得只写"待专项"） |
| 连带项 | `GameEngineSectLevelOps.kt:210 bloodMaterials.random()` 的结果经 `prepared` **传入 C++ `SECT_LEVEL_CLAIM_TX`**（`:80/:86/:494-525`，KDoc `:484-486` 自述模板解析留 Kotlin）⇒ **C++ 实际发放的兽血材料由 Kotlin 全局随机决定**；既有"native 臂零 RNG"表述掩盖了该输入侧随机。**同域 ⇒ 本批一并分区化**（`getRng(RngPartition.*)` 或由 Kotlin 预生成阶段显式传参） |

#### B1 · w3-03 巡逻/住所/矿场自愈（ActionId 1760–1765）

| 写者 | 分类 | 处置 |
|---|---|---|
| `SpiritMineViewModel.kt:89/:147/:183/:252`（灵矿槽位 UI 直改） | ① | **UI 直改是架构违规**（四处全为 `gameEngine.updateGameData { }`，已逐行实测）⇒ 槽位变更经 `patrol_tx.h` 新增事务（复用 batch-12 的十事务地基与 `SlotCleanupInput`）；ViewModel 只发命令 |
| `GameEnginePatrolOps.kt:49 validateAndFixSpiritMineData`（矿场自愈） | ① | 改为 **C++ 自愈纯函数 + 回执驱动** Kotlin 侧 gate/Room 残差（ADR §2.2 w3-03 定式）；C++ 侧 `validateAndFixSpiritMineDataTx` 已在 `patrol_tx.h:627` |
| **`GameEnginePatrolOps.kt:105 updatePatrolConfig`（死 API）** | — | **删除**（实测零调用方；`PatrolTowerViewModel.kt:161` 是同名不同函数）——留着的唯一后果是日后被接线成丢数据点 |
| `updatePatrolSlots`（死 API） | — | batch-12 已登记"不为死 API 扩协议"；本次**删除** |

**复用既有地基（零重写）**：`patrol_tx.h`（十事务，1550–1559）、`slot_cleanup.h`（12 类槽位）、`toMissionLiteList`/`mergeMissionLiteList`；展示字段（`name`/`realmName`/`portraitRes`）**从 `DiscipleStore` 直读**（少一次跨语言字段搬运）；`Disciple.realmName` 语义经 `disciple::realmConfig` 复刻（含 `age<5 || realmLayer==0 → "无境界"` 与 `realm==0` 特例）。

#### B2 · w3-04 玉符运行时（ActionId 1766–1769）

> **本批技术上最敏感的子批**。玉符是**绝对值覆盖写模型**，历史上出过两类真实缺陷：绕过 `totalCount` 同步导致 `checkpointNow` 把余额写回覆盖前值（玉符回涨）；Kotlin 侧承扣导致 tick 滞后窗口内 C++ 余额检查读到滞后值（**双花**）。

| 写者 | 分类 | 处置 |
|---|---|---|
| `JadeSymbolService.kt:192/:220/:279/:326/:338/:374/:383`（循环钩子累加 / 跨天重置 / checkpoint） | ① + ③ | **墙钟是平台输入**：Kotlin 只推 `nowWall` 与限额；**累加/跨天/结算移入 C++**（ADR §2.2 w3-04 定式）。C++ 侧存"应发总额"由事务落账 |
| 玉符扣减（消耗路径） | ① | **在 C++ 事务内承扣**（校验 + 扣减 + 抽取原子完成）；Kotlin native 臂成功后必须经 `syncJadeRuntimeAfterNative(cost)` / `syncBalanceFromSnapshot()` 把运行时 `totalCount` 锚回快照绝对值 |
| 消耗收敛 | ① | **仍必须收敛于 `JadeSymbolService` 唯一入口**（`JadeSymbolConsumptionGuardTest` 拦截）；禁止在 Service/GameEngine 直接 `copy(jadeSymbols = ...)` |

**红线**：
1. 墙钟读数推送点显式定义 + **同一 tick 内幂等**测试（ADR 盲区 3）；
2. 玉符事务**失败零抽取**（"扣减失败但已抽取"违反失败臂零抽取红线）；
3. 对拍必须覆盖 `jadeSymbols` / `jadeSymbolsToday` / `jadeAccumMs` / `jadeDayAnchorMs` 四字段。

#### B3 · w3-05 邮件附件 + 行商刷新（ActionId 1770–1779）

| 写者 | 分类 | 处置 |
|---|---|---|
| `MailAttachmentDistributeOps.kt:124/:183`（附件领取账本） | ① + ③ | 账本 `mailRecords` 与"可领清单"入 C++（ADR §2.2 w3-05 定式）；**邮件投递本身留 Kotlin**（③平台效应）。124 在 `grantAttachments`@117、183 在 `claimAttachmentInternal`@162（**已逐行实测**） |
| `MerchantAndRecruitService.kt:65/:319`（行商刷新凭据/物品池） | ① | 商人池（`travelingMerchantItems`）+ 刷新凭据（`merchantLastRefreshYear` / `merchantRefreshCount` / `merchantRefreshChances`）入 C++；刷新判定链整体移入事务。> ⚠️ **引用勘误**：w3 README 记作 `:322`——实测 `:322` 是 `merchantRefreshChances = … - 1`，位于 **`:319` 的 `stateStore.updateAndReturn {`**（`refreshTravelingMerchantManual`@317）事务体内 |
| 邮件附件"凭据类溢出抑制" | ① | 附件领取属**凭据类**（玩家可重试）⇒ 必须包裹 `withOverflowMailSuppressed`（溢出不转邮件，失败保留凭据重试补齐）——CLAUDE.md 13.3 的溢出语义类别红线 |

**红线**：物品发放必须走 `InventorySystem.addXxx` 统一入口 + `withTrackingSource("来源名")`（来源名须在 `OverflowMailSender.SOURCE_DISPLAY_NAMES` 映射内），`InventoryAddPathGuardTest` 会拦截。

#### B4 · w3-12 外交/自愈/运行态收尾（ActionId 1840–1849）

| 写者 | 分类 | 处置 |
|---|---|---|
| `DiplomacyService.kt:145/:261`（结盟请求/散盟） | ① | 延续 batch-09 的 `diplomacy_tx.h`（1500–1502）地基，补齐稳态段。函数真值：`:145` ∈ `requestAllianceSimple`@119、`:261` ∈ `dissolveAllianceSimple`@255（**注意** `ReverseChannelPolicy.kt:346` 写的 `requestAllianceKotlin` 是过期函数名） |
| `VassalService.kt:99/:324`（年度贡赋 / 月度脱离） | ① | 年度贡赋属月年编排内的 Kotlin 活路 ⇒ **状态写移入 C++**；`:99` ∈ `processYearlyTribute`@89、`:324` = `stateStore.update { processMonthlyBreakawayCheck(this) }`@323。> ⚠️ **宿主文件冻结**：年度贡赋的编排宿主 `CultivationEventMonthlyOps.kt:125/:126` 属宿主文件族（W4-D 独占）⇒ 本批**只下沉被调用方**，调用点清理留 W4-D |
| `GameEngineDiplomacyOps.kt:18`（预警去重） | ② 或 ① | 若 `shownWarningStageIds` 只影响 UI 去重 ⇒ ② 改只读派生；若参与存档 ⇒ ① 移入 C++。**逐点判定并成文** |
| `SaveFacadeImpl.kt:56 regenerateSectsBeforeSave`（存档前世界/AI 池自愈） | ① + ③ | 自愈族改为 **C++ 自愈事务 + Kotlin 只读**；"存档前"这一时机为平台生命周期（③）⇒ 时机留 Kotlin，自愈内容进 C++。（同文件 `:72/:97` 另有 `jadeSymbolService.checkpointNow()` 写路径，同批核对） |
| `GameEngineServiceOps.kt:40`（修炼检查点重锚） | ① | 重锚语义移入 C++（`ElderManagementUseCase` 调用面） |
| `GameEngineServiceOps.kt:77`（内存压力裁剪） | ③ | **平台效应**：`onMemoryPressure` 是平台回调 ⇒ 裁剪**命令**进 C++（返回应裁剪清单），Kotlin 执行实际释放 |
| **洞府探索生命周期入口死链** | — | **删除范围收窄（实测）**：可删的是**洞府探索链**——**双证**：① 入口 `CultivationService.kt:178-179 processCaveLifecycle` 外部调用方 **0**（全仓 4 命中 = 2 定义 + 2 注释）；② 主源内 `CaveExplorationTeam(` 构造 **0 处**（仅测试构造：`DiffSlotCleanupTest.kt:110`、`ProductionSlotDualWriteGuardTest.kt:297`、`DiscipleSlotCleanupTest.kt:311/330`）⇒ 洞府探索在现版本**无法推进**。<br>🔴 **同族方法均活，禁删**：`processSectDisciplesAging`（`CultivationEventMonthlyOps.kt:176`，年变 T2 #4，回退臂可达）、`processAISectOperations`（`:62/:95` + `CaveExplorationProcessor.kt:240`）、`currentAiThermalBatchSize`（`GameEngineCoreMonthOps.kt:77`，月结前热控推送，**非回退臂**）⇒ **"整族死链"表述不成立** |

### 2.4 数据流（本批终态：命令 + 回执）

```
平台事件（墙钟 / 邮件投递结果 / 内存压力 / 存档时机）
  └─ Kotlin 平台层（读数采集，不写状态）
       └─ GameEnginexxxOps / Facade（Kotlin 入口：首行 native 臂）
            ├─ native 可用 → dispatchW4B(core, actionId, params + 读数) → C++ 事务
            │                  └─ 回执信封（受影响 id / 应发总额 / 应投递清单）
            │                       ├─ 状态变更 → applyDirtyFromNative → Kotlin 只读镜像
            │                       └─ 副作用指令 → Kotlin 执行（通知/支付/网络/Room）
            └─ native 不可用 → 回退臂（Kotlin 原路径，逐位一致，GateTest 守卫）
```

---

## 3. 影响范围清单

### 3.1 Kotlin 侧

| 文件 | 变更 | 说明 |
|---|---|---|
| `feature/game/.../ui/game/SpiritMineViewModel.kt` | 修改 | 灵矿槽位 UI 直改 → 命令化（改走 Ops 层） |
| `.../engine/GameEnginePatrolOps.kt` | 修改 + **删除死 API** | 矿场自愈改回执驱动；删 `updatePatrolConfig` / `updatePatrolSlots` |
| `.../engine/service/JadeSymbolService.kt` | 修改 | 循环钩子/跨天/checkpoint 移入 C++；保留为唯一消耗入口 |
| `.../engine/service/MailAttachmentDistributeOps.kt` | 修改 | 账本入 C++，投递留 Kotlin |
| `.../engine/service/MerchantAndRecruitService.kt` | 修改 | 商人池 + 刷新凭据入 C++ |
| `.../engine/domain/diplomacy/DiplomacyService.kt` | 修改 | 稳态段 native 臂 |
| `.../engine/domain/diplomacy/VassalService.kt` | 修改 | 贡赋/脱离状态下沉 |
| `.../engine/GameEngineDiplomacyOps.kt` | 修改 | 预警去重逐点判定（②或①） |
| **`SaveFacadeImpl.kt`**（存档前自愈） | 修改 | 自愈改 C++ 事务 + Kotlin 只读。> 🔴 **跨批接口约定（W4-C/WS-5b 依赖）**：`getStateSnapshot()`（`:94-117`）的**数据源语义不得改变**——它必须继续"只读 `stateStore.*Snapshot` + `gameRngManager.exportStates()`"。WS-5b 的地形段正是依赖"`GameData` 新字段经 `stateStore.gameDataSnapshot` 自动携带"这一性质；若本批改动该语义，W4-C 的存档链路会静默失效 |
| `.../engine/GameEngineServiceOps.kt` | 修改 | 检查点重锚下沉；内存裁剪改"命令 + 回执" |
| `.../engine/service/CaveExplorationProcessor.kt` | **部分删除** | **只删洞府探索生命周期入口链**（`CultivationService.kt:178` 委托 + `:89 processCaveLifecycle` 及其独占调用面）；`processSectDisciplesAging` / `processAISectOperations` / `currentAiThermalBatchSize` **保留**（活路） |
| `.../engine/GameEngineSectLevelOps.kt` | 修改 | B0：宗门领奖输入侧随机（`:210 bloodMaterials.random()` → `SECT_LEVEL_CLAIM_TX`）分区化；Jade 凭据路径核对 |
| `.../engine/FakeAtomicStateStore.kt`（测试源） | 修改 | B0：Jade 环境缺陷根因修复（事务缓冲 `writeDepth` 与 `syncFlows` 的交互） |
| ~~`RngEngineIsolationGuardTest.kt`~~ | **不改** | 白名单收缩统一留 W4-D/D5（README §5.1） |
| `core/domain/.../state/reversechannel/W4BChannelClosures.kt` | **新增** | 本批关闭单元 + 保留字段 + 域级 verdict（只改本文件） |

### 3.2 C++ 侧

| 文件 | 变更 | 说明 |
|---|---|---|
| `include/gamecore/system/patrol_tx.h` | 修改 | 灵矿槽位 + 矿场自愈事务（复用 batch-12 地基） |
| `include/gamecore/system/jade_tx.h` | 修改 | 墙钟读数参数化 + 累加/跨天/结算 |
| `include/gamecore/system/mail_tx.h` | **新增** | 邮件附件账本事务（凭据类溢出抑制） |
| `include/gamecore/system/merchant_tx.h` | **新增** | 行商刷新判定链 + 池管理 |
| `include/gamecore/system/diplomacy_selfheal_tx.h` | **新增** | 外交稳态段 + 存档前自愈 + 检查点重锚 |
| `src/dispatch_w4b.cpp` | **新增/填充** | 本批 handler 实现 |
| `test/w4b_tests.cmake` | **新增/填充** | 本批 GTest 源清单 |
| `test/patrol_tx_test.cpp`（+31 例基线）/ `jade_tx_test.cpp` / 新增 `mail_tx_test.cpp` / `merchant_tx_test.cpp` / `diplomacy_selfheal_tx_test.cpp` | 修改/新增 | 黄金用例 |
| `scripts/action-catalog/w4b.mjs` | **新增/填充** | 本批 ActionId 条目 |

### 3.3 标签项

| 标签 | 结论 |
|---|---|
| **经济** | **有直接影响，必须列源与汇**：① 玉符（`jadeSymbols` 绝对值覆盖写模型）—— 源 = 循环钩子累加/购买/奖励，汇 = 消耗（洗炼/购买加成）；本批只改"谁写"，**不改数值**；必须证明"扣减在 C++ 事务内承扣 + `totalCount` 锚回快照"⇒ 无双花、无回涨。② 行商刷新凭据（`merchantRefreshChances`）—— 源 = 年度/月结发放，汇 = 手动刷新消耗。③ 邮件附件（九类物品 + 灵石）—— 凭据类溢出抑制必须生效（否则物品重复发放或丢失）。**奖励价值审计沿用既有结论，本批不新增发放点** |
| **iOS** | **正面影响（本批最显著）**：平台效应被显式外置为可注入读数（墙钟/内存压力/存档时机）⇒ iOS 侧只需实现同一组平台接口，无需触碰逻辑核心 |
| **隐私合规** | **无影响**：不新增 SDK / 权限 / 网络请求 / 数据收集；邮件与行商为**游戏内**系统，非真实网络；不触碰 `PrivacyConsentScreen.kt` 与 `docs/index.html` |
| **存档** | **不变**：见 §4 |

---

## 4. 兼容性分析

| 面 | 结论 |
|---|---|
| **存档格式** | **不变**。不改任何 `@ProtoNumber` 字段、不改 Room Entity ⇒ **无需 Migration** |
| **存档语义** | 不变（关闭传输单元不改变谁持有什么值——关闭前该域已无稳态写者） |
| **协议** | ActionId **只增**（1760–1765 / 1766–1769 / 1770–1779 / 1840–1849），落在预分配段内 |
| **前后兼容** | native 臂失败即回退；旧 .so 对未注册动作返回 `NOT_IMPLEMENTED` ⇒ 回退 |
| **降级路径** | `NativeEngineFlag.OFF` / `.so` 未加载 / 初始化失败 ⇒ 全链路回退 Kotlin |
| **墙钟语义** | 🔴 **必须显式定义**：C++ 事务接受 `nowWallMs` 参数而非内部取时；同一 tick 内多次调用必须幂等（ADR 盲区 3 的兜底已在本方案 §2.2/§2.3 B2 落地） |
| **迁移窗口** | 每子批合入即生效（无存档迁移窗口） |

---

## 5. 测试方案

### 5.1 C++ GTest（每事务必备四类）

同 W4-A §5.1（正常路径 / 校验链全失败臂零写入 / 边界与篡改防御 / **零 RNG 或分区 RNG 全分区快照差分**）+ **双运行全状态 JSON 逐位一致**。

### 5.2 本批专项测试

| # | 测试 | 断言 |
|---|---|---|
| 1 | **墙钟幂等** | 同一 tick 内以同一 `nowWallMs` 重复调用玉符事务 ⇒ 状态不变（第二次为 no-op）+ 回执 `changed=false` |
| 2 | **墙钟回退** | `nowWallMs` 倒退（用户改系统时间）⇒ 不发放、不重置、不抛异常；状态不变 |
| 3 | **玉符双花防护** | 余额不足时事务**零抽取**；余额充足时"校验 + 扣减 + 抽取"原子完成；`totalCount` 锚回后 `checkpointNow` 不回涨 |
| 4 | **凭据类溢出抑制** | 邮件附件领取时仓库已满 ⇒ 溢出不转邮件（`withOverflowMailSuppressed`）+ 凭据保留可重试；**不得**产生重复发放 |
| 5 | **自愈幂等** | 对健康数据跑 C++ 自愈事务 ⇒ 零变更（`changed=false`）；对损坏数据 ⇒ 修复且可重复执行 |
| 6 | **内存裁剪只读** | `onMemoryPressure` 路径返回"应裁剪清单"，Kotlin 执行释放 ⇒ 游戏状态**零变更**（纯运行态） |
| 7 | **死代码回归网** | `updatePatrolConfig` / 洞府探索整族删除后全量编译 + 引擎全量测试绿（证明无隐式调用方） |

### 5.3 Kotlin GateTest（每域必备）

同 W4-A §5.2 四条（结果值一致 / 状态逐字段一致 / 失败信封回退臂重执行校验链 / mock 下不 NPE）。

### 5.4 对抗性审查要点（本批专项）

| # | 审查点 |
|---|---|
| 1 | **平台读数时序**：读数推送点是否在批内显式定义？同一 tick 内是否幂等？（ADR 盲区 3） |
| 2 | **玉符消耗唯一入口**：是否有绕过 `JadeSymbolService` 的 `copy(jadeSymbols = ...)`？（`JadeSymbolConsumptionGuardTest` 必须绿） |
| 3 | **溢出语义类别**：新增发放路径是**凭据类**（须 `withOverflowMailSuppressed`）还是**发放类**（溢出自动转邮件）？选错 ⇒ 重复发放或丢失（对抗性审查 C1/C2/C3/H1/H2 教训） |
| 4 | **物品发放统一入口**：是否走 `InventorySystem.addXxx` + `withTrackingSource`？来源名是否在 `OverflowMailSender.SOURCE_DISPLAY_NAMES` 内？ |
| 5 | **事务外调用返回值被忽略**：是否出现"扣种走事务外且忽略返回值"式缺陷（batch-17 的"免费种田"）？ |
| 6 | **整包替换 + 宽松 `readField`**：跨语言整包覆写核对两端默认值编码策略（`encodeDefaults`） |
| 7 | **`using` 声明陷阱**：新增事务头在 `dispatch_w4b.cpp` 中的 include 序自证不改变非限定名解析 |
| 8 | **Dagger 环**：新增协作服务若引入 `GameEngineCore` 依赖，检查是否形成 `GameEngineCore → XxxService → … → GameEngineCore` 环（用 `Provider<T>` 惰性边破环） |
| 9 | **手工单例服务**：`StateSyncService` 式手工单例**不可构造注入**（会分叉实例 = 通道损坏）；如需扩展，走公开工厂直构传同引用 |
| 10 | **`FakeAtomicStateStore` 播种**：材料/持久化 EntityStore 必须 `store.update { ... }` 播种，不能 `.value =` |

### 5.5 测试墙钟成本核算

| 项 | 评估 |
|---|---|
| `patrol_tx_test.cpp` 基线 31 例 | 复用，新增例 4–8/事务 |
| 玉符/邮件/行商/外交自愈新增 | 各 5–10 例 GTest + 各 8–20 例 GateTest |
| 死代码删除 | 零新增测试（以全量编译 + 全量引擎测试作回归网） |
| 全量对拍 | 批内按子批推进；重活经**构建令牌**串行 |
| 上限约束 | 单测试 < 30 秒；无外部路径依赖 |

---

## 6. 风险评估与兜底

| 风险 | 概率 | 影响 | 兜底 |
|---|---|---|---|
| **玉符双花 / 回涨**（历史上已发生过两类） | 中 | **高**（经济漏洞） | 扣减在 C++ 事务内承扣；`syncJadeRuntimeAfterNative` 锚回快照；`JadeSymbolConsumptionGuardTest` + 专项 GTest（§5.2 第 3 条） |
| **墙钟读数时序变化改变行为** | 中 | 中 | 读数推送点在批内显式定义 + 同 tick 幂等测试 + 墙钟回退测试（§5.2 第 1/2 条） |
| **溢出语义类别选错**（凭据类 vs 发放类） | 中 | 高（重复发放/丢失） | 逐路径判定并成文；`§5.4` 第 3 条列为必审项 |
| **自愈族下沉后"过度修复"**（把玩家合法状态改掉） | 中 | 高 | 自愈事务必须**幂等**且对健康数据零变更（§5.2 第 5 条）；C++ 自愈判定链与 Kotlin 逐位对拍 |
| **Jade 环境缺陷未根因修复就推进 w3-04** | 中 | 中 | **B0 必须在 B2 之前**；若判定不可修复 ⇒ 必须在批文档给出"为什么不可能"的因果链 + 替代验证口径（禁止只写"待专项"） |
| **洞府探索删除误删仍在用的公共面** | 低 | 中 | 删除前 `git grep` 双证（生产 + 测试）+ `compileReleaseUnitTestKotlin` + 全量引擎测试 |
| **行商刷新凭据的"同实体两条路径不同取价"** | 中 | 中 | 保持既有语义（`ManualStack.basePrice` 走品阶基准价；**上架**路径走 `ManualDatabase.getByName(name)?.price`）——**不得统一口径**，否则与回退臂金额分歧（findings W2-a 条 16） |
| **与 W4-A/C 的隐藏文件交集** | 低 | 中 | README §5 所有权矩阵 + 冻结文件出现在本批 diff 中即打回 |

---

## 7. 未来场景推演（≥6 个月档）

| 维度 | 推演 | 结论 |
|---|---|---|
| **规模增长** | 邮件记录/行商池随游戏时长线性增长：本批把账本判定移入 C++ 后，Kotlin 侧不再对全表做判定 ⇒ 每次操作 O(变更)；新增同类系统只需在 `w4b.mjs` + `dispatch_w4b.cpp` 追加 | 线性可控 |
| **生命周期** | 构建/重启/清缓存：不涉及生成物以外的缓存；墙钟读数在**每次进程启动**后重新注入 ⇒ 无跨进程残留假设 | 一致 |
| **平台扩张（iOS）** | 本批把"墙钟/内存压力/存档时机"三类平台读数显式参数化 ⇒ iOS 侧对应 `CACurrentMediaTime` / `didReceiveMemoryWarning` / `applicationWillResignActive` 等即可，逻辑核心不改 | **本波最大的 iOS 净收益** |
| **运营演进** | 6 个月内调整玉符产出/行商概率：属数值面（配置），本批不改；若新增"玉符获取点"，只需新增 C++ 事务 + ActionId，**不需要**改 Kotlin 运行时累加逻辑 | 无需发版 |
| **兼容回退** | ① 单域回退 `reopenDomain(PATROL/BOUNDARY/INVENTORY/DIPLOMACY/SAVE_LOAD)`；② native 不可用 → 回退臂；③ 批次回退 → `archive/w4-b` tag | 可回退，不发版 |

---

## 8. 技术债与偿还计划

| 债项 | 产生原因（为何本批不全做） | 偿还时机（可判断的触发条件） |
|---|---|---|
| `VassalService` 年度贡赋的月年编排扇出 | 属 w3-11 域，W4-D 统一处置 | W4-D/D2 |
| `RedeemCodeService`（兑换码）登记不下沉 | C++ 无物品随机生成器（`EquipmentDatabase.generateRandom` 无对应物），非分区随机源不可逐位复刻 | C++ 侧具备物品随机生成器（模板 codegen 下沉）后重议 |
| `autoHarvestCompletedAlchemySlots` 保留 Kotlin | 读档路径 + AUTHORITATIVE 基线窗口，迁移会产生"首月读档免费收获" | 生产编排与读档基线窗口统一后重议（**非本批**） |
| `MaterialConsumptionLog` 保留 Kotlin | UI 流 / 平台效应 | 出现非 UI 消费者时重议 |
| 失败信封回退臂仍可能写状态 | 双实现一致契约已保证；结构性禁止需逐域三态化 | 本批玉符域改 `executeRaw` 三态（经济敏感域优先）；其余随 W4-D 汇总 |
| `FakeAtomicStateStore` 若判定"不可在 fake 内修复" | 需重构 fake 的事务缓冲模型（影响面大） | 触发条件 = 出现第三个受同一缺陷影响的域；届时单列 fake 重构批 |

---

## 9. 盲区自查与完善建议

| # | 盲点 / 未验证假设 | 影响 | 处置 |
|---|---|---|---|
| 1 | **"墙钟是平台输入"是否覆盖全部玉符路径？** 若存在"后台/离线也累加"的路径，读数注入点不止一处 | 漏一处 ⇒ 行为分歧 | 已回写 §2.3 B2：读数推送点**逐点枚举**并加"同 tick 幂等"测试；离线段按"打开时一次性补算"口径处理（与 Kotlin 现行为对拍锁定） |
| 2 | **`GameEngineDiplomacyOps.kt:18` 的预警去重是 ① 还是 ②？** | 判错 ⇒ 该字段关闭后丢数据 | 已回写 §2.3 B4：**逐点判定并成文**；不确定时按 ① 处置（保守） |
| 3 | **行商刷新是否含真实网络请求？** | 若有，属 ③ 且涉及隐私合规面 | 已核实：行商为**游戏内**系统（`MerchantAndRecruitService` 无网络栈），隐私合规标注"无影响"；若发现网络面，须回写并评估隐私政策双入口 |
| 4 | **自愈族下沉后，`SaveFacadeImpl.kt:56` 的"存档前"时机是否需要回执？** | 若自愈失败如何处理（继续存档 vs 阻断）语义未定义 | 已回写 §2.3 B4：时机留 Kotlin；**自愈失败语义必须显式定义**（默认沿用现行为：记录 + 继续），并写用例 |
| 5 | **内存压力裁剪的"应裁剪清单"是否含玩家可见数据？** | 裁错 ⇒ 玩家资产丢失 | 裁剪面仅限**纯运行态缓存**（无存档面）；用例断言"裁剪前后存档指纹一致" |
| 6 | **三分类表是否覆盖"月变/年变内的 Kotlin 活路"？** 这类写者不在 UI 面，容易被扫漏 | 漏域 ⇒ 关闭后丢数据 | 扫描口径必须含"月年编排内的活路"（w3 §2.2 第 1 步的扫描面已含，本批按 batch-21 的六类判定逐站点复核） |
| 7 | **非功能属性**：邮件账本/行商池的增长是否引入内存压力 | 极端情况下长局内存增长 | 账本入 C++ 后由 `dirty_tracker` 增量镜像（非全量）；真机批观测（非并行轨） |
| 8 | **隐私合规** | —— | ✅ 无影响（已核实无网络/SDK/权限变化），已回写 §3.3 |
| 9 | **流程盲区**：关闭域写入检测是运行期 ERROR，单测不触发 | 单测全绿但生产仍有漏网写者 | README §7.1 第 7 条（本地跑一段业务周期）+ 真机批复测 |
| 10 | **Jade 缺陷若为"契约要求"而非 fake 缺陷** | 则所有使用 `FakeAtomicStateStore` 的既有测试都建立在错误契约上 ⇒ 影响面远超本批 | 已回写 §2.3 B0：先做**契约考古**（读 `GameStateStore` 接口 KDoc + 真实实现），判定为契约问题 ⇒ 升级为 W4-D 的专项并评估全量 fake 重构 |

> 实质影响方案主体的结论（#1 读数逐点枚举、#2 保守判定、#3 合规核实、#4 失败语义、#10 契约考古）**已回写** §2.2/§2.3/§3.3。
