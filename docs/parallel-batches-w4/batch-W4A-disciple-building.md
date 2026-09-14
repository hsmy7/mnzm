# W4-A 批次方案：弟子与建设轴（UI 操作面收尾 + 随机源决策类下沉）

> ## 🚧 本批是**并行方案**——实施人员开工前必读
>
> **本批与 [W4-B](batch-W4B-court-economy.md)、[W4-C](batch-W4C-battle-world.md) 是同一波次的三个并行批次，三者会同时在各自的工作树上施工。**
> 并行度是设计出来的，不是"注意避让"出来的——请先读 [README](README.md) §2（拆分依据）与 §4（协作协议），再开工。
>
> | 你必须知道的 | 内容 |
> |---|---|
> | **你的工作区（独占）** | `C:\Mnzm\XianxiaSectNative-w4a`，分支 `w4/a-disciple-building`（由 `scripts/w4/setup-worktrees.ps1` 建立）。**不要**在主仓 `C:\Mnzm\XianxiaSectNative` 或另外两个工作树里改代码 |
> | **硬前置** | **W4-00（并行前置批）必须已合入 `main`**。判据：`scripts/action-catalog/w4a.mjs`、`src/dispatch_w4a.cpp`、`test/w4a_tests.cmake`、`reversechannel/W4AChannelClosures.kt` 四者均已存在，且 `git tag` 有 `w4-base` |
> | **你的独占文件** | `scripts/action-catalog/w4a.mjs`｜`src/dispatch_w4a.cpp`｜`test/w4a_tests.cmake`｜`reversechannel/W4AChannelClosures.kt`｜`RngSourceGuardTest.kt` |
> | **🔴 绝对禁改**（另两批的面向对象，或全局共享面） | `scripts/action-catalog/w4b.mjs`、`w4c.mjs`｜`src/dispatch_w4b.cpp`、`dispatch_w4c.cpp`｜`test/w4b_tests.cmake`、`w4c_tests.cmake`｜`reversechannel/W4B*`、`W4C*`｜`scripts/gen-action-ids.mjs`｜`action_ids.h`、`ActionIds.kt`｜`src/execute_dispatch.cpp`｜`src/CMakeLists.txt`、`test/CMakeLists.txt`｜`ReverseChannelPolicy.kt`｜`GameStateStoreImpl.kt`｜`StateSyncService.kt`｜`GameViewModel.kt`｜`GameEngineCore.kt`｜**宿主文件族**（`CultivationEventMonthlyOps.kt`/`MonthSettlementResidualExecutor.kt`/`YearSettlementResidualExecutor.kt`/`GameEngineCoreMonthOps.kt`/`GameEngineCoreYearOps.kt`）｜**协议面**（`models.h`/`json_codec.cpp`/`game_core.*`/`GameData.kt`/`GameDatabase.kt`，归 W4-C）｜`RngEngineIsolationGuardTest.kt`（归 W4-D）｜`CHANGELOG.md`、`changelog_entries.json`、handover §3/§4.1/§5/§6、`ui-read-surface.md` §4.4（收口人独占） |
> | **重活必须先取构建令牌** | `pwsh -File scripts/w4/build-token.ps1 -Acquire -Batch w4a` → 跑 Gradle 全量测试/detekt/NDK/lint/ctest 构建 → `-Release`。**不取令牌并跑 = 会让另两批的构建随机失败**（Gradle `classes.jar` 锁 / KSP 缓存损坏 / ctest 假失败） |
> | **提交纪律** | `git commit -- <本批触碰文件清单>`，**禁止整仓 `git add`**（历史事故：混入 433 个并行改动文件）；每个子批提交后打 `w4a/<nn>` tag + `git bundle`（`.git` 曾两次被毁） |
> | **合并** | 由收口人按 W4-A → W4-B → W4-C → W4-D 顺序合并；**冲突禁取 theirs**；生成物冲突一律"重生成" |
>
> **判据（机器可执行）**：`git diff --name-only w4-base..w4/a-disciple-building` 中若出现上表"绝对禁改"里的任一文件 ⇒ **本批打回**。

| 项 | 内容 |
|---|---|
| 批次代号 | **W4-A**（worktree `C:\Mnzm\XianxiaSectNative-w4a`，分支 `w4/a-disciple-building`） |
| 覆盖的 w3 子批 | **w3-01 → w3-02**（串行）｜**w3-09**｜**w3-10** |
| 支线工作项 | RNG 阶段 3·弟子侧（`DiscipleChatDialog`）｜死代码：血炼完成链死路径 |
| ActionId 段 | **1740–1749**（w3-01）｜**1750–1759**（w3-02）｜**1810–1819**（w3-09）｜**1820–1829**（w3-10）｜**1850–1854**（RNG 阶段 3·弟子侧，**条件段**） |
| 决策分级 | **架构级重构**（跨模块/跨语言、触及长期主轴） |
| 硬前置 | **W4-00 已完成并合入 `main`**（共享面切分 + 冻结清单 + 租约表 + `w4-base` tag） |
| 只读（禁止触碰） | `GameStateStoreImpl.kt`｜`StateSyncService.kt`｜`GameViewModel.kt`｜`GameEngineCore.kt`｜`src/execute_dispatch.cpp`｜`test/CMakeLists.txt`｜`scripts/gen-action-ids.mjs`｜`ReverseChannelPolicy.kt`｜`state/models.h`｜`src/json_codec.cpp`｜`game_core.h/.cpp`｜`GameData.kt`｜`GameDatabase.kt`（以上协议面归 W4-C，见 README §5.4 租约）｜`RngEngineIsolationGuardTest.kt`（归 W4-D）｜**宿主文件族**（`CultivationEventMonthlyOps.kt` / `MonthSettlementResidualExecutor.kt` / `YearSettlementResidualExecutor.kt` / `GameEngineCoreMonthOps.kt` / `GameEngineCoreYearOps.kt`，README §2.3） |
| handover 章节 | **§2.62**（只在本小节内追加） |

---

## 1. 背景与目标

### 1.1 需求要点

反向通道的关闭前置是"**各域稳态写者归 C++**"（[handover §2.53](../cpp-migration-handover-m0.md)：288 写入点穷尽审计实测 **14 域无一可整体关闭**）。本批负责**弟子管理域、弟子生命周期域、建筑域、道路域、生产域**的剩余稳态写者搬迁，并在完成后把对应传输单元写入 `reversechannel/W4AChannelClosures.kt`。

本批同时承接两项与弟子/建设轴同域的支线：

1. **RNG 阶段 3·弟子侧**：`DiscipleChatDialog` 在 UI 层消费随机并写弟子 `skills`/`cultivation`（决策类，[ADR rng §8](../adr/rng-determinism-remediation.md) 判为"归阶段 3，需 ActionId + C++ 事务"），是 R3 不变量（表现流不得被决策路径调用）的**当前违规点**。
2. **死代码**：血炼完成链死路径（`GameEngineBloodRefinementOps.kt` 零生产调用方）——删除后其字段关闭结论（`bloodRefinements` / `bloodRefinementBonusTotals` / `bloodRefinementPctTotals` 已在 batch-21 关闭清单内）方可长期成立。

### 1.2 成功标准（可验收）

| # | 标准 | 度量 |
|---|---|---|
| 1 | 五个域的稳态写者全部归 C++ | 批内写者穷尽扫描表（入口 → 三类 → 是否仅回退臂）产出且无 `STEADY_KOTLIN` 残留 |
| 2 | 五个域的传输单元关闭 | `W4AChannelClosures.kt` 内的 `w4AClosedUnits` 覆盖本批全部单元；**关闭域写入检测零命中**（运行期无 `noteClosedWrite` ERROR） |
| 3 | 行为零变更 | 各域 GateTest（flag OFF vs ON）逐位一致 + 全部 **47** 个 `Diff*` 对拍绿 + 桌面 C++ 全量绿 + `:core:engine` 全量绿 |
| 4 | 回退臂可用 | `.so` 未加载 / `NativeEngineFlag.authoritative=false` 下各域功能与迁移前一致（GateTest 覆盖） |
| 5 | 协议零漂移 | `node scripts/gen-action-ids.mjs && git diff --exit-code` 空；`models.h` 无 diff（或已按租约登记） |
| 6 | 随机源治理 | `DiscipleChatDialog` 的 3 处随机不再落 UI 层、不再消费非分区随机源；`RngSourceGuardTest` 登记上限**只缩不增** |

---

## 2. 技术方案

### 2.1 交付节奏（批次内串行序，不可打乱）

```
A1  w3-01 弟子操作面        （1740–1749）──┐
A2  w3-02 弟子生命周期第二波 （1750–1759）←─┘ 必须在 A1 之后
A3  w3-09 建筑/道路残差      （1810–1819）── 与 A1/A2 文件零交集，可与之并行推进
A4  w3-10 生产残差          （1820–1829）── 与 A3 共享 BuildingFacadeImpl 面 ⇒ 必须在 A3 之后
A5  RNG 阶段 3·弟子侧       （1850–1854，条件）── 与 A1/A2 同域，建议置最后
A6  死代码：血炼完成链      随 A1 顺手删除
```

> **A4 必须在 A3 之后**：w3-10 的写者位于 `ProductionProcessor*.kt`，但生产槽位的 UI 面在 `BuildingFacadeImpl*` 家族（batch-17 的先例），二者同批串行以避免同文件并发编辑。

### 2.2 每子批的统一交付形态（w3 协议 + 行业四阶段切换顺序）

**先分类，再搬迁**（feature parity trap 教训：1:1 复刻会把无人使用的旧行为与历史 workaround 一并固化）。每子批开工先对该域的每个写入点做三分类：

| 类 | 判定 | 处置 |
|---|---|---|
| ① **影响模拟结果** | 该写入会成为结算/对拍的输入，或会被 C++ 后续写入覆盖 | **搬迁**：C++ 事务 + Kotlin 回退臂 |
| ② **纯表现** | 只影响 UI 展示（文案/动画/排序缓存），不进 `rngStates`/存档/结算 | **删除或改只读派生**（不进 C++，也不需回导） |
| ③ **平台效应回执** | 平台 IO 副作用（通知/支付/网络/邮件投递/墙钟读数） | **重构为"命令进 C++ 事务、回执出 C++"**，副作用留 Kotlin |

**四阶段切换顺序**（Fowler *Event Interception*）：① 暗发布 + parity 校验 → ② 拦读 → ③ 拦写（新事务成为 System of Record，Kotlin 写路径降级回退臂）→ ④ 搬业务规则。

随后固定七步：

1. **写者复核**：`stateStore.update` 扫描 → 「入口 → 三类 → 是否仅回退臂」表（batch-21 给出基线，批内复核增量）。
2. **C++ 事务**：`system/<domain>_tx.h` 纯函数事务（判定链先行 → 失败零写入 → 零 RNG 或分区 RNG 逐位复刻 → 回执信封）。
3. **Kotlin native 臂**：域门面/引擎入口首行 `if (nativeTx.x()) return`；**Kotlin 原路径保留为回退臂**。
4. **GateTest**：同一入口 flag OFF vs ON → 结果 sealed 值 + 状态逐字段一致。
5. **关闭该域**：把该域单元写入 `reversechannel/W4AChannelClosures.kt`（**只改本批文件**）。
6. **验收门禁**：README §7.1 十条（缺一不可）。
7. **文档**：handover §2.62 追加 + PR 描述给收口人"文档同步项"。

### 2.3 各子批的技术要点

#### A1 · w3-01 弟子操作面（ActionId 1740–1749）

| 写者（实测路径） | 分类 | 处置 |
|---|---|---|
| `GameEngineCoordination.kt:99/:120/:138`（弟子属性/改名/类型直改） | ① | C++ 事务：属性写 + 改名 + 类型写（判定链：弟子存在 → 存活 → 值域合法） |
| `DiscipleFacadeImpl战斗Ops2.kt:93/:119/:138/:157/:271`（赏赐/服药） | ① | C++ 事务：物品从仓库扣除 + 弟子数值变更**同一事务**（防"扣了没给"/"给了没扣"）；服药走既有丹药分类链 |
| `DiscipleStatusService.kt:225/:279/:373`（槽位状态派生同步） | ① | **派生列唯一计算方 = C++**（ADR 盲区 2：槽位/派生列双算会漂移）；Kotlin 侧改只读投影 |
| `DiscipleSlotManager.kt:59`、`DiscipleLifecycleNativeTx.kt:132`（native 事务后残差） | ① | 残差下沉；回执驱动 Kotlin 侧 gate 登记 |
| **`GameEngineBloodRefinementOps.kt:60 startBloodRefinementAtomic`（血炼启动清槽）** | ① | 复用既有 `slot_cleanup.h` 的 `SlotCleanupInput` + `clearAllSlotsDataOnly`（batch-12 地基，**零重写**）。> ⚠️ **引用勘误**：w3 README 把该写者记作 `DiscipleSlotCleanup.kt:120`——**文件与函数双错**（`DiscipleSlotCleanup.kt:120` 实为纯函数 `clearAllSlotsDataOnly`@56 内的 `battleTeams = updatedBattleTeams,`）。真值已在 §1.1 与本表修正，`ui-read-surface.md:245` 与 `ReverseChannelPolicy.kt:319` 由收口人在 W4-D/D6 同修 |
| **`GameEngineManualOps.kt:139 replaceManual`（功法替换）** | ① | **2026-09-15 事实核查新增的未登记稳态写者**（活 UI `DiscipleDetailScreen.kt:954` → `DiscipleDelegateGearOps.kt:99`，**无 native 臂**；同文件 55/64/94/201 已有其他 tx 而此处没有）⇒ 本批新增事务 |
| `GameEngineBloodRefinementOps.kt:99`（GameEngine 层 suspend 包装） | — | **删除死包装**（零生产调用方，仅 `GameEngineDualSlotGuardTest.kt:370` 引用）。> ⚠️ **w3 §1.1 的"血炼完成链死路径"结论只能部分采纳**：同文件 `:167 MutableGameState.processBloodRefinementCompletions` 仍在 `MonthSettlementExecutor.kt:60` 的调用面上（**回退臂活路**）⇒ **逻辑不可删**，只删包装 |

**复用既有地基（零重写）**：`disciple_tx.h`（batch-08 六事务）、`appointment_tx.h`（batch-15/24）、`slot_cleanup.h`（batch-12）、`disciple_lifecycle_tx.h`（batch-14）。

#### A2 · w3-02 弟子生命周期第二波（ActionId 1750–1759）

| 写者 | 分类 | 处置 |
|---|---|---|
| **`GameEngine.kt:276 approveMarriageProposal`（低成本起手项）** | ① | C++ 事务 `DISCIPLE_LIFECYCLE_MARRY_APPROVE=1592` **已存在**（`execute_dispatch.cpp` 有 handler + 3 个 GTest），Kotlin 侧零引用 ⇒ **本批只需接线 native 臂 + GateTest** |
| `GameEngine.kt:277/:306`（婚姻提议审批/拒绝） | ① | 同上；拒绝路径同批下沉 |
| `DiscipleLifecycleProcessor.kt:489`（槽位清理双路：偷盗叛逃事务内 + 永久属性丹） | ① | C++ 事务内完成清槽；**注意**：偷盗链已在旬结 C++ 侧（`phase_settlement.h`），下沉时核对不产生双重清槽 |
| `DiscipleLifecycleManager.kt:100/:121`（月变自动装备 lifeEvent / UI 查看补写） | ① | 月变路径的 lifeEvent 写入归 C++ 月结；UI 查看补写若为 ② 则删除 |

**红线**：婚姻批准对"提议残留 + 弟子已亡/被逐"边界，Kotlin 原路径会写幽灵列条目，**列式/SoA 模型无法表达** ⇒ native 返回 `NotFound` 信封回退 Kotlin 原路径保行为（batch-14 既有口径，[findings](../../findings.md) 已登记）。**不得**为了"沉下去"而改变该边界语义。

#### A3 · w3-09 建筑/道路残差（ActionId 1810–1819）

| 写者 | 分类 | 处置 |
|---|---|---|
| `BuildingNativeTx.kt:163`（native 拆除后的槽位/弟子释放残差） | ① | 根因是 **C++ `GridBuildingData` 无槽位字段**；按 [ADR §2.2](../adr/reverse-channel-elimination.md) 给的**备选路线**：**改由 C++ 侧槽位表承载**（避免动 `models.h`，从而不与 W4-C 的 WS-5b 争用协议面）。若最终判定必须补模型字段 ⇒ **走 README §5.4 租约（顺序 C→A→B）**，未取得租约前不得改 |
| `BuildingFacadeImpl同步Ops.kt:281`（月变没收建筑） | ① | `GameEngineCoreMonthOps.kt:95` 的调用点属 **宿主文件族**（W4-D 独占，README §2.3）⇒ 本批**只把 `removeBuildingsInternal` 内部的状态写改幂等/下沉**，**不改调用点**；扇出清理留 W4-D |
| `BuildingDelegate.kt:145`（放置槽位残差） | ① | 回执驱动：C++ 放置事务回传受影响的槽位/弟子 id，Kotlin 只执行 Room 侧平台效应 |
| **`RoadFacadeImpl.kt:67 placeRoad` / `:85 removeRoad`**（道路写入点） | ① | 复用 `road_tx.h`（native 臂已在 `:58`/`:80`）；`RoadMaskTracker`/`RoadTiling` 为**纯 UI 缓存**（②），不下沉。> ⚠️ **引用勘误**：w3 README 记作 `RoadFacadeImpl.kt:41/:141`——实测 `:41` 是构造形参、`:141` 是私有函数 `canPlaceCell` 的形参，**均非写入点** |

#### A4 · w3-10 生产残差（ActionId 1820–1829）

| 写者 | 分类 | 处置 |
|---|---|---|
| `ProductionProcessorCleaOps3.kt:291 alignMirrorFromRepository`（月结前 repo→镜像整表对齐） | ① | **根因消除**：ADR §2.2 w3-10 定"评估改为 C++ 直读 repo 快照（消除'窗口对齐'这一临时手段）"⇒ 本批把对齐窗口替换为"C++ 权威 + Kotlin 只读"，随后删除 `alignMirrorFromRepository` / `restoreRepositoryFromMirror` 这一对临时设施（S4 登记项） |
| `ProductionProcessor构筑Ops2.kt:250/:341/:405`（自动续炼槽位写者） | ① | 复用 `production.h` 的 `startProductionTransaction` / `resetProductionSlotTransaction`（S7 地基）；`validateAutoSlot` 的判定链整体移入 C++ |
| `MaterialConsumptionLog`（消耗日志） | ③ | **保留 Kotlin**（UI 流 / 平台效应）——batch-18 与 batch-17 既有结论 |
| `autoHarvestCompletedAlchemySlots` | ③ | **保留 Kotlin**：读档路径 + AUTHORITATIVE 基线窗口，迁移会产生"首月读档免费收获"（batch-17 既有结论） |

> ⚠️ **宿主文件冻结（README §2.3/§4.4 第 11 条）**：A3/A4 涉及的调用点位于 `GameEngineCoreMonthOps.kt`（`:95` 建筑没收、`:99 restoreProductionSlotsFromMirror`），该文件属**宿主文件族，W4-A 禁改**。
> 本批只允许"把被调用方（`BuildingFacadeImpl同步Ops.removeBuildingsInternal` / `ProductionProcessorCleaOps3`）改成幂等或只读"；**调用点的清理/删除统一在 W4-D 执行**。判据：`git diff --name-only w4-base..w4/a-disciple-building` 中不得出现宿主文件族任一文件。

#### A5 · RNG 阶段 3·弟子侧（ActionId 1850–1854，**条件段**）

> **开工第一步（两步，顺序不可颠倒）**：
>
> **第 1 步 · 先补机器可判定面（🔴 必做，否则本项无闸门）**
> `RngSourceGuardTest.kt:120` 的 ② 类正则 = `\.random\(\)|Random\.Default|Math\.random`，**不匹配 `Random.nextInt/nextDouble`**。实测：`feature/game` 主源 ② 类**代码**命中 **0 行**（5 处命中全在注释里：`LoadingScreen.kt:162`、`LoadingTips.kt:34`、`CloudLayerAnimator.kt:25/27`、`NativeSurfaceView.kt:546`），登记上限却是 **1**；而 `DiscipleChatDialog` 的 **5 个 `Random.nextInt/nextDouble` 调用点**（`:200` / `:204`×2 / `:209` / `:210`）**不被任何类别计数**，仅存在于 `docs/rng-source-inventory.md` 表内。
> ⇒ **先扩展正则 + 登记 `DiscipleChatDialog`**，再动治理；否则"改登记值 = 改守卫"，只缩不增纪律形同虚设。
> （同族抽查：主源 `Random.nextX` 形式共 23 行命中，逐个核实调用方后其余均为**死代码/仅测试**——`PillGrade.random` 唯一调用方是 `ProductionProcessorAlchemyTest.kt:24`；`AISectPersonality.random` 仅测试；`BeastMaterialDatabase.getRandomMaterialByRealm` 仅测试；`BaseTemplateRegistry.pickWeightedRandom/generateTieredRarity` 仅测试 ⇒ **盲区实际掩盖的活文件只有 `DiscipleChatDialog` 一个**，恰好就是本项目标。）
>
> **第 2 步 · 定界治理方式（ADR 口径修正）**
> [ADR rng-determinism-remediation](../adr/rng-determinism-remediation.md) **并未要求 ActionId + C++ 事务**——阶段 3 的落地形态是"**按调用点粒度下沉**"，硬要求只有 `R1`（影响状态的随机必须取自 `GameRngManager.getRng(RngPartition.*)`）。
> - **优先路线（默认，成本最低、零协议改动）**：**形参化 / 分区化** —— 把 `DiscipleChatDialog` 的 5 个调用点改为**显式传入**随机源；影响状态的部分走 `getRng(RngPartition.*)` 或由引擎侧事务签发，纯展示部分走 `PresentationRandom`（不落盘）。
> - **仅当**确认"必须由 C++ 事务签发结果"时才启用 **1850–1854**（新增 `system/disciple_chat_tx.h`）。
> - 退段须在本批文档登记结论与依据。

**写者事实（已实测）**：`DiscipleChatDialog.kt:311` → `DiscipleDelegate.kt:231-261` → `updateDisciple { copy(cultivation = …, skills = …, statusData = lastChatYear) }`（`:246-252`）⇒ **决策类成立**；调用面 `randomOne()` 5 处（`:289/:291/:298/:299/:316`）、`randomizeEffect` 1 处（`:304`）。

**红线**：R3——表现类随机不得被决策路径调用；判定口径 = ADR §8："该随机结果是否写入 `GameData` / 实体表 / 影响数值"。**同一 `stateStore.update` 事务内的不得拆开**（禁止撕裂事务）。

### 2.4 数据流（本批终态）

```
UI（Compose）
  └─ ViewModel/Delegate（Kotlin，禁改 GameViewModel.kt）
       └─ GameEnginexxxOps / Facade（Kotlin 入口：首行 native 臂）
            ├─ native 可用 → dispatchW4A(core, actionId, params) → C++ 事务（判定链 → 写 → 回执）
            │                  └─ 回执 → applyDirtyFromNative（同域脏段回读）→ Kotlin 镜像（只读）
            └─ native 不可用 → 回退臂（Kotlin 原路径，逐位一致，GateTest 守卫）
```

---

## 3. 影响范围清单

> 格式：`文件路径 — 变更类型 — 变更说明`。每项标注语言（`C++`/`Kotlin`）与模块。

### 3.1 Kotlin 侧

| 文件 | 变更 | 说明 |
|---|---|---|
| `android/core/engine/.../engine/GameEngineCoordination.kt` | 修改 | 弟子属性/改名/类型直改 → native 臂 + 回退臂 |
| `.../engine/domain/disciple/DiscipleFacadeImpl战斗Ops2.kt` | 修改 | 赏赐/服药五入口 native 臂 |
| `.../engine/domain/disciple/DiscipleStatusService.kt` | 修改 | 状态派生改只读投影（派生列唯一计算方 = C++） |
| `.../engine/domain/disciple/DiscipleSlotManager.kt` | 修改 | 槽位残差下沉，回执驱动 |
| `.../engine/domain/disciple/DiscipleLifecycleNativeTx.kt` | 修改 | native 事务后残差下沉 |
| `.../engine/domain/disciple/DiscipleSlotCleanup.kt` | 修改 | 血炼启动清槽 → native 臂（复用 `slot_cleanup.h`） |
| `.../engine/GameEngineManualOps.kt` | 修改 | **新发现稳态写者** `replaceManual`（`:139`）→ 新增 native 事务 |
| `.../engine/GameEngineBloodRefinementOps.kt` | 修改（**部分删除**） | 下沉 `:60 startBloodRefinementAtomic` 的真实写入；**删除 `:99` 零调用 suspend 包装**；`:167 processBloodRefinementCompletions` 是回退臂活路，**保留** |
| `.../engine/GameEngine.kt` | 修改（**租约文件**） | 婚姻提议审批/拒绝接线 native 臂 |
| `.../engine/domain/disciple/DiscipleLifecycleManager.kt` | 修改 | lifeEvent 补写归 C++ 月结 / UI 补写改只读派生 |
| `.../engine/service/DiscipleLifecycleProcessor.kt` | 修改 | 槽位清理双路 native 化，核对不双清 |
| `.../engine/domain/building/BuildingNativeTx.kt` | 修改 | 拆除残差下沉（C++ 槽位表承载） |
| `.../engine/domain/building/BuildingFacadeImpl同步Ops.kt` | 修改 | 没收步骤状态下沉（扇出改写留 W4-D） |
| `.../engine/domain/road/RoadFacadeImpl.kt` | 修改 | 道路槽位/回执残差下沉 |
| `feature/game/.../ui/game/delegate/BuildingDelegate.kt` | 修改 | 放置槽位残差改回执驱动 |
| `.../engine/service/ProductionProcessorCleaOps3.kt` | 修改/删除 | 窗口对齐设施退役（`alignMirrorFromRepository` / `restoreRepositoryFromMirror`） |
| `.../engine/service/ProductionProcessor构筑Ops2.kt` | 修改 | 自动续炼槽位写者下沉 |
| `feature/game/.../ui/game/dialogs/DiscipleChatDialog.kt` | 修改 | 移除 UI 层随机（RNG 阶段 3·弟子侧，5 个调用点） |
| `android/core/engine/src/test/.../core/architecture/RngSourceGuardTest.kt` | **修改（本批独占）** | 扩展机器可判定面（② 类正则补 `Random.nextInt/nextDouble`）+ 登记 `DiscipleChatDialog`。🔴 本文件**只有 W4-A 可改**（README §5.1）；W4-C 的随机点下沉不需要改它（登记上限"不下调也仍然绿"），收缩统一留 W4-D |
| `core/domain/.../state/reversechannel/W4AChannelClosures.kt` | **新增** | 本批关闭单元 + 保留字段 + 域级 verdict（只改本文件） |

### 3.2 C++ 侧

| 文件 | 变更 | 说明 |
|---|---|---|
| `include/gamecore/system/disciple_tx.h` | 修改 | 追加弟子属性/改名/类型/赏赐/服药/功法替换事务 |
| `include/gamecore/system/disciple_lifecycle_tx.h` | 修改 | 婚姻批准接线（handler 已存在）+ 拒绝路径 |
| `include/gamecore/system/appointment_tx.h` | 复用（不改） | batch-15/24 地基 |
| `include/gamecore/system/slot_cleanup.h` | 复用（不改） | batch-12 地基 |
| `include/gamecore/system/building_residual_tx.h` | **新增** | 拆除/放置槽位残差事务（C++ 槽位表承载） |
| `include/gamecore/system/production_residual_tx.h` | **新增** | 自动续炼槽位事务 |
| `include/gamecore/system/disciple_chat_tx.h` | **新增（条件）** | RNG 阶段 3·弟子侧 |
| `src/dispatch_w4a.cpp` | **新增/填充** | 本批 handler 实现（W4-00 建立骨架） |
| `test/w4a_tests.cmake` | **新增/填充** | 本批 GTest 源清单（W4-00 建立骨架） |
| `test/disciple_tx_test.cpp` / `disciple_lifecycle_tx_test.cpp` / 新增 `building_residual_tx_test.cpp` / `production_residual_tx_test.cpp` | 修改/新增 | 黄金用例 |
| `scripts/action-catalog/w4a.mjs` | **新增/填充** | 本批 ActionId 条目（只写自己的段） |

### 3.3 标签项

| 标签 | 结论 |
|---|---|
| **经济** | **有间接影响**：赏赐/服药涉及物品与灵石在仓库与弟子间的转移 ⇒ 必须"扣除与给予同一事务"（失败零写入），并对拍 `spiritStones` 三阶与九类物品集合；**不改**任何数值/倍率/源汇设计 |
| **iOS** | **正面影响**：新增 C++ 事务均在 `game-core`（纯 C++20、零 Android 依赖）；弟子域逻辑进一步与平台层解耦 |
| **隐私合规** | **无影响**：不新增 SDK / 权限 / 网络请求 / 数据收集；不触碰 `PrivacyConsentScreen.kt` 与 `docs/index.html` |
| **存档** | **不变**：见 §4 |

---

## 4. 兼容性分析

| 面 | 结论 |
|---|---|
| **存档格式** | **不变**。存档为 Kotlin kotlinx ProtoBuf；本批不改任何 `@ProtoNumber` 字段、不改 Room Entity ⇒ **无需 Migration** |
| **存档语义** | 不变。关闭反向传输单元不改变"谁持有什么值"——因为关闭前该域已无稳态写者（关闭即"该单元不再需要回导"） |
| **协议** | ActionId **只增**（1740–1749 / 1750–1759 / 1810–1819 / 1820–1829 / 1850–1854），全部落在预分配段内 |
| **前后兼容** | 每批 native 臂失败即回退 ⇒"新 Kotlin + 旧 .so"可运行；反之旧 .so 对未注册动作返回 `NOT_IMPLEMENTED` ⇒ 回退 ⇒ 亦可运行 |
| **降级路径** | `NativeEngineFlag.OFF` / `.so` 未加载 / 初始化失败：全链路回退 Kotlin（GateTest 必覆盖） |
| **迁移窗口** | 每子批合入即生效（无存档迁移窗口） |
| **`models.h`** | **默认零改动**（w3-09 走"C++ 侧槽位表承载"备选路线）；若必须改 ⇒ 租约 + `json_codec` 双向编解码 + 对拍键集红线声明 |

---

## 5. 测试方案

### 5.1 C++ GTest（每事务必备四类）

| 类 | 内容 |
|---|---|
| 正常路径 | 黄金序列：输入 → 期望状态（逐字段） |
| 校验链全失败臂 | 每个校验点各一例，断言 **零状态变更** + failure 信封 `code` |
| 边界与篡改防御 | 越界 id、重复 id、空集、极值、非数字 id、超上限 |
| **零 RNG / 分区 RNG 快照差分** | 全分区 `rngStates` 前后快照：零 RNG 事务必须**逐位相同**；分区事务必须与 Kotlin 臂**逐位一致** |

**双运行全状态 JSON 逐位一致**：同一输入跑两次 ⇒ `exportStateJson` 逐字节相同。

### 5.2 Kotlin GateTest（每域必备）

同一入口分别以 `NativeEngineFlag.authoritative=false/true` 执行 ⇒ 断言：
1. 返回的 sealed 结果值一致（含失败 `code`）；
2. `gameData` + 十类实体集合逐字段一致；
3. 失败信封路径下**回退臂重执行校验链**，结果与 C++ 一致；
4. mock 环境（`stateSyncServiceRef` 返回 null）下**不 NPE**（先赋可空局部再判空）。

### 5.3 对抗性审查要点（本批专项）

| # | 审查点 |
|---|---|
| 1 | **派生列双算漂移**（ADR 盲区 2）：槽位标记/弟子状态是否仍有 Kotlin 侧独立计算？派生列唯一计算方必须显式成文 |
| 2 | **幽灵列边界**（batch-14 教训）：婚姻批准对"提议残留 + 已亡/被逐"的行为是否与回退臂一致（native 返回 `NotFound` 回退） |
| 3 | **双重清槽**：旬结偷盗链（C++）与 w3-02 的生命周期清理是否会各清一次 |
| 4 | **`using` 声明陷阱**：新增事务头在 `execute_dispatch.cpp` 中的包含序（本批不改该文件，但 `dispatch_w4a.cpp` 的 include 序需自证不改变非限定名解析） |
| 5 | **原子写指针纪律**：实例表 `erase`/堆叠整摞扣减后禁用旧指针（batch-08 教训） |
| 6 | **整包替换 + 宽松 `readField`**：跨语言整包覆写必须核对两端默认值编码策略（batch-18 教训：`encodeDefaults`） |
| 7 | **事务内快照取在扣费之前**：`val data = gameData` 必须在 `wallet.deduct` 之后取（batch-18 gate 暴露的"扣费静默丢失"） |
| 8 | **`FakeAtomicStateStore.materials` 是持久化 EntityStore**：测试播种必须 `store.update { materials.replaceAll(...) }`，不能 `.value = listOf(...)` |
| 9 | **`GameData` 默认值即"开局值"**：`spiritStones = 1000`（非 0）⇒ 断言灵石绝对值的用例必须显式清零 |

### 5.4 测试墙钟成本核算

| 项 | 评估 |
|---|---|
| 新增 C++ GTest | 每事务 4–8 例；`game-core-tests` 单进程直跑（全套当前秒级—十秒级） |
| 新增 Kotlin GateTest | 每域 8–20 例；`runTest` 虚拟时间，无真实等待 |
| 全量对拍 | `:core:engine` 3281 用例；**批内按子批推进**（每子批合并前一次全量），不每次编辑都跑 |
| 重活串行 | 全量引擎测试 / detekt / NDK / lint 必须经**构建令牌**串行（README §4.1） |
| 上限约束 | 单个新测试墙钟 < 30 秒（超限即拆小）；无生成物路径依赖（§5.5） |

### 5.5 测试环境依赖

- GTest 运行时需 `llvm-mingw-<版本>-ucrt-x86_64\bin` 在 PATH；
- 对拍需**本工作树**的 `libgamecorejni.so` 绝对路径 + `--rerun-tasks`；
- 不依赖本机路径/文件存在/构建产物以外的外部资源。

---

## 6. 风险评估与兜底

| 风险 | 概率 | 影响 | 兜底 |
|---|---|---|---|
| 弟子域写者最多（46 站点中的大头），"以为搬完了" | **高** | 高（关闭即丢数据） | 批内穷尽扫描表 + `noteClosedWrite` 零命中门禁 + 逐子批关闭（不一次性关整域） |
| 赏赐/服药跨"仓库 ↔ 弟子"双端写入，事务边界切错 | 中 | 高（物品凭空产生/消失） | 单事务内完成扣除与给予；失败零写入；对拍灵石三阶 + 九类集合 |
| w3-09 若必须改 `models.h` ⇒ 与 WS-5b 争用协议面 | 中 | 中 | 默认走"C++ 侧槽位表承载"；确需改则走租约（顺序 C→A→B），等 W4-C 的 WS-5b 合入后再动 |
| 生产槽位"窗口对齐"退役后出现读档首月异常 | 中 | 中 | 退役前先写"窗口对齐 = 历史临时手段"的证据链；保留 `autoHarvestCompletedAlchemySlots` 于 Kotlin（既有结论）；对拍覆盖读档场景 |
| `DiscipleChatDialog` 判定为"部分表现 / 部分决策"导致切分撕裂 | 中 | 中 | 按 ADR §8 口径逐处判定（是否写 GameData/实体表/影响数值）；同一 `stateStore.update` 事务内的不得拆开 ⇒ 整段进 C++ 或整段留 Kotlin（**禁止撕裂事务**，batch-20b 先例） |
| 血炼完成链删除误删被测试引用的公共面 | 低 | 中 | 先跑 `compileReleaseUnitTestKotlin`（主源编译不编译测试源）+ 全量引擎测试 |
| 与 W4-B/C 的隐藏文件交集 | 低 | 中 | README §5 所有权矩阵 + **冻结文件出现在本批 diff 中即打回** |

---

## 7. 未来场景推演（≥6 个月档）

| 维度 | 推演 | 结论 |
|---|---|---|
| **规模增长** | 弟子规模 100 → 5000：本批把"属性/状态/槽位"的写权收归 C++，Kotlin 侧不再对每名弟子做派生重算 ⇒ 每次操作的 Kotlin 成本 O(k) 而非 O(N)；新增弟子类操作只需在 `w4a.mjs` + `dispatch_w4a.cpp` 追加，不动任何共享文件 | 随规模收益放大 |
| **生命周期** | 构建/重启/清缓存：不涉及生成物以外的缓存；新事务头在 `game-core` 内自包含；`w4a_tests.cmake` 随 CMake 重配生效 | 全生命周期一致 |
| **平台扩张（iOS）** | 新增逻辑全在 `game-core`（零 Android 依赖）；弟子域不再依赖 Kotlin 侧派生 ⇒ iOS 侧只需实现同一组平台接口 | 净收益 |
| **运营演进** | 6 个月内新增弟子操作（如新赏赐类型）：走"ActionId + C++ 事务 + 回退臂 + GateTest"模板，**不需要**改 Kotlin 派生逻辑；数值调整仍走既有配置面 | 无需发版 |
| **兼容回退** | ① 单域回退 `ReverseChannelPolicy.reopenDomain(DISCIPLE/LIFE_CYCLE/BUILDING/ROAD/PRODUCTION)`（一行）；② native 不可用 → 回退臂；③ 批次回退 → `archive/w4-a` tag | 可回退，不发版 |

---

## 8. 技术债与偿还计划

| 债项 | 产生原因（为何本批不全做） | 偿还时机（可判断的触发条件） |
|---|---|---|
| `BuildingFacadeImpl同步Ops.kt:281` 的月变没收扇出 | 属月年编排扇出（w3-11 域），W4-D 统一处置 | W4-D/D2（w3-11） |
| 失败信封回退臂仍可能写状态 | 双实现一致契约（GateTest）已保证；结构性禁止需逐域三态化 | 本批高频域（赏赐/服药）改 `executeRaw` 三态；其余随 W4-D 汇总 |
| `id=""` 镜像生成字段按 id 去重时坍缩 | 既有 AUTHORITATIVE 基线行为，本批零行为变更不修复 | 跨年手动招募池容量出现可复现玩家可见问题时重议（招募域，非本批） |
| `DiscipleChatDialog` 若判定为"部分决策" | 撕裂事务禁止 ⇒ 只能整段处置 | ADR 阶段 3 分批推进时按域重议（触发条件 = 该域事务可独立表达） |
| `GameData` 死函数 / `GameSettingsData.autoSave` | `GameData.kt` 与 W4-C 的 WS-5b 同文件 | W4-D/D5（`autoSave` **已拍板按"清理"执行**，2026-09-15） |

---

## 9. 盲区自查与完善建议

| # | 盲点 / 未验证假设 | 影响 | 处置 |
|---|---|---|---|
| 1 | **"弟子管理域无稳态写者"是否真的可达？** `DiscipleStatusService` 693L 的派生面可能含"仅 UI 可见"的缓存列 | 误关即丢数据 | 开工第一步产出**逐站点三分类表**；`STEADY_KOTLIN` 类一律不关并登记（README §13 #14） |
| 2 | **`GameEngine.kt` 租约冲突**：W4-B/C 若也需接线入口 | 需串行化 | 已回写 README §5.4；W4-A 优先取租约，其他批走"域门面/Ops 层接线"（w2 既有口径，禁改 `GameViewModel.kt`） |
| 3 | **RNG 阶段 3 的 ActionId 条件段若退段** | `1850–1854` 空置，方案工作项缩水 | 已回写 §2.3 A5：开工第一步定界；退段须在批内文档登记结论与依据 |
| 4 | **赏赐/服药的"物品实例 vs 堆叠"双轨** | 事务只覆盖一轨 ⇒ 另一轨仍留 Kotlin 写者 | 三分类时**按轨枚举**（`equipmentInstances` / `equipmentStacks` / `pills` / `materials` / `herbs` 五类实体集合逐一判定） |
| 5 | **清槽幂等性**（血炼启动 / 偷盗叛逃 / 永久属性丹三路） | 重复清槽会造成弟子状态丢失 | 复用 `clearAllSlotsDataOnly` 的幂等语义；三路各写一条 GTest（同弟子重复触发 ⇒ 状态不变） |
| 6 | **非功能属性**：弟子属性写是否进每帧路径 | 若进，JNI 往返会成热点 | 实测确认这些入口均为**用户操作面**（弹窗/按钮），非 tick 路径；GTest 不设性能阈值，但真机批（非并行轨）观测 |
| 7 | **隐私合规** | —— | ✅ 无影响（不新增 SDK/权限/网络/采集），已回写 §3.3 |
| 8 | **流程盲区**：关闭域写入检测是"运行期 ERROR 日志"，单测环境不产生 | 单测全绿但生产仍有漏网写者 | 门禁第 7 条要求"该域运行期零命中"——以桌面/模拟器长跑（本地跑一段业务周期）作为补强；真机批（非并行轨）复测 |
| 9 | **`w4a.mjs` 与生成物的一致性由谁保证** | 半提交会让干净检出无法编译 | README §4.2"共享文件整组提交" + 每批提交前 `node scripts/gen-action-ids.mjs && git diff --exit-code` |

> 实质影响方案主体的结论（#1 逐站点三分类、#2 租约、#3 条件段、#8 运行期观测）**已回写** §2.2/§2.3 与 README §5.4/§7.1。
