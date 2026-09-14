# C++ 迁移剩余工作·第四波（W4）三批次并行实施方案（总览与协作协议）

> **执行状态（2026-09-15 收口）**：W4-00 前置批 ✅ → 三批并行 ✅（W4-A `w4a/01`–`05` / W4-B `w4b/01`–`05` / W4-C `w4c/01`–`08`）→ **集成收口 ✅**（合并提交 `7c89deb19`，全部门禁绿；独立复验抓出并根因修复 3 处缺陷——A 测试源编译、C 迁移链 12 处、B 硬编码 ActionId，详见 [handover §2.68](../cpp-migration-handover-m0.md)）。**下一波 = 本文 §8 汇流波 W4-D（串行，未开工）**；§9 非并行轨需用户决策或硬件。

| 项 | 内容 |
|---|---|
| 文档性质 | **派工方案**（协调文档）：把 [handover](../cpp-migration-handover-m0.md) §4/§5/§6/§7 登记的**全部剩余工作**拆成 **3 个可并行实施、文件零交集、工作区互不干扰** 的批次，并补齐用户指令未覆盖的**并行前置批、汇流波、非并行轨** |
| 依据 | [handover §4 遗留待办](../cpp-migration-handover-m0.md) + §5 下轮建议 + §6 随机源治理 + §7 事实核查｜[w3 十三批计划](../parallel-batches-w3/README.md)｜[ADR reverse-channel-elimination](../adr/reverse-channel-elimination.md)｜[ADR rng-determinism-remediation](../adr/rng-determinism-remediation.md)｜[w2 协作协议](../parallel-batches-w2/README.md)｜[non-parallel-work](../parallel-batches-w2/non-parallel-work.md)｜`findings.md`（并行会话真实事故） |
| 决策分级 | **架构级重构**（跨模块、跨语言、触及长期主轴；按 CLAUDE.md「设计方案规则」全流程编写） |
| 基线（**W4-00 落地后实测，2026-09-15**） | ActionId **169 动作 / maxId 1734**（W4-00 净减 2 处死导出）；桌面 C++ **1326/1326**（基线 1322 + 4 新守卫用例）；`:core:engine` **3281 用例 / 296 类 / 0 失败 / 0 跳过**（`Diff*` 对拍 **47** 类）；`:core:domain` 1758/0；`:core:data` 707/0（15 既有跳过）；六模块 detekt 全绿 + baseline **全 0**；NDK arm64 `externalNativeBuildRelease` 成功；生成器幂等 + 产物零漂移 |
| 工作区 | `main`（唯一分支，工作区干净，无游离 worktree）→ 前置批 W4-00 合入后派生 3 个工作树 |
| 交付形态 | 本 README（总览/前置批/协作协议/所有权/验收/汇流/非并行轨）+ [batch-W4A](batch-W4A-disciple-building.md) + [batch-W4B](batch-W4B-court-economy.md) + [batch-W4C](batch-W4C-battle-world.md) |

---

> **并行度是设计出来的，不是"注意避让"出来的**：本波次的三个并行批次（[W4-A](batch-W4A-disciple-building.md) / [W4-B](batch-W4B-court-economy.md) / [W4-C](batch-W4C-battle-world.md)）会**同时在各自的工作树上施工**。每份批次文档的**顶部**都印有"🚧 本批是并行方案"告知块（工作区 / 硬前置 / 独占文件 / 绝对禁改清单 / 构建令牌 / 提交纪律 / 机器可执行判据）——**派工前请连同该告知块一起交给实施人员**。

---

## 0. 一句话结论

> **剩余代码工作可以拆成 3 个真正零交集的并行批次（W4-A 弟子与建设 / W4-B 内政与经济运营 / W4-C 战斗与世界协议），但"零交集"不是现状——现状有 6 处必然冲突的共享文件。**
> 因此本方案在三个并行批次之前插入一个**串行前置批 W4-00（共享面结构性切分）**，把"靠纪律避冲突"变成"结构上不可能冲突"；
> 并在三批之后保留一个**串行汇流波 W4-D**（`w3-11` 是 W4-B×W4-C 的 join 点、`w3-13` 通道删除必须最后、`batch-22a` 埋点与 `w3-13` 争用同一文件）。
> 三批 + 汇流波之外的项（真机验证 / 待拍板 / 待立项）**不可派工**，单列非并行轨。

---

## 1. 剩余工作全集（以代码实测为唯一裁判）

> 口径纪律（handover §7 教训）：本节每条结论都带 `文件:行号` 或命令实测证据；文档转述**不作为**证据。

### 1.1 可并行的代码工作（三批覆盖，共 12 个 w3 子批 + 6 个支线项）

| 编号 | 工作项 | 归属 | ActionId 段 | 规模 | 证据 |
|---|---|---|---|---|---|
| w3-01 | 弟子操作面（属性/改名/类型直改、赏赐/服药、状态派生、槽位清理、血炼启动）+ **新发现稳态写者 `GameEngineManualOps.replaceManual`** | **W4-A** | 1740–1749 | 大 | `GameEngineCoordination.kt:99/:120/:138`、`DiscipleFacadeImpl战斗Ops2.kt:93/:119/:138/:157/:271`、`DiscipleStatusService.kt:225/:279/:373`、`DiscipleSlotManager.kt:59`、`DiscipleLifecycleNativeTx.kt:132`（**逐行实测命中**）；⚠️ **w3 README 的 `DiscipleSlotCleanup.kt:120` 是误引**——真值 = `GameEngineBloodRefinementOps.kt:60`（`startBloodRefinementAtomic`, fun@40）；`GameEngineManualOps.kt:139`（真值，文档写 137） |
| w3-02 | 弟子生命周期第二波（婚姻提议审批/拒绝、槽位清理双路、lifeEvent 补写）+ **低成本起手：接线已就绪的 `DISCIPLE_LIFECYCLE_MARRY_APPROVE=1592`** | **W4-A** | 1750–1759 | 小 | `GameEngine.kt` 391L、`DiscipleLifecycleProcessor.kt` 538L、`DiscipleLifecycleManager.kt` 268L |
| w3-09 | 建筑/道路残差（拆除残差、月变没收、放置槽位残差、道路槽位/回执残差） | **W4-A** | 1810–1819 | 中 | `BuildingNativeTx.kt` 275L、`BuildingFacadeImpl同步Ops.kt` 327L、`BuildingDelegate.kt` 388L、`RoadFacadeImpl.kt` 222L |
| w3-10 | 生产残差（月结前 repo→镜像对齐、自动续炼槽位） | **W4-A** | 1820–1829 | 中 | `ProductionProcessorCleaOps3.kt` 416L、`ProductionProcessor构筑Ops2.kt` 419L |
| w3-03 | 灵矿/住所/巡逻自愈 + **顺手删死 API `GameEnginePatrolOps.updatePatrolConfig`**（实测零调用方；`PatrolTowerViewModel.kt:161` 是同名不同函数） | **W4-B** | 1760–1765 | 小 | `SpiritMineViewModel.kt:89/:147/:183/:252`（`updateGameData`）、`GameEnginePatrolOps.kt:49`（`updateGameDataSync`）**逐行命中**；可复用 `patrol_tx.h`（含 `validateAndFixSpiritMineDataTx`@627），**无需新建事务头** |
| w3-04 | 玉符运行时（循环钩子累加/跨天重置/checkpoint） | **W4-B** | 1766–1769 | 中 | `JadeSymbolService.kt:192/:220/:279/:326/:338/:374/:383` **7/7 精确**；⚠️ `jade_tx.h` 现有 5 个购买/领取 tx，**循环钩子/跨天重置/checkpoint 无对应 tx** ⇒ 需扩头或新建 |
| w3-05 | 邮件附件账本 + 行商刷新凭据 | **W4-B** | 1770–1779 | 小 | `MailAttachmentDistributeOps.kt:124/:183` 精确；`MerchantAndRecruitService.kt:65` 精确、**`:322` → 真值 `:319`**（`updateAndReturn` 事务体）；⚠️ **无 `mail_tx.h`，需新建** |
| w3-12 | 外交/好感/附庸稳态段 + 存档前自愈 + 内存裁剪 + 检查点重锚 + **顺手删洞府探索整族死链** | **W4-B** | 1840–1849 | 大 | `DiplomacyService.kt:145/:261` 精确（⚠️ 函数名真值 `requestAllianceSimple`@119 / `dissolveAllianceSimple`@255）、`VassalService.kt:99/:324`、`GameEngineDiplomacyOps.kt:18`、`SaveFacadeImpl.kt:56`、`GameEngineServiceOps.kt:40/:77` 全部精确；洞府死链双证成立（`CultivationService.kt:178` 零调用方）；⚠️ **存档/自愈无 tx 头，需新建** |
| w3-06 | 战斗/探索残差（native 战斗后伤亡残差、世界关卡胜败事务、战前结算） | **W4-C** | 1780–1789 | 中 | `CombatService.kt:78`、`GameEngineWorldBattleOps.kt:188/:288`、`GameEngineExplorationNativeOps.kt:134` 全部精确；可复用 `exploration_tx.h`；⚠️ `battle.h`/`sect_battle.h` 为**纯逻辑非 tx** ⇒ 战斗事务需新头 |
| w3-07 | 宗门战战后段（战前结算、战后段奖励/战史边界） | **W4-C** | 1790–1799 | 中 | `GameEngineBattleOps.kt:66/:274/:339/:366` **4/4 精确**；可复用 `sect_attack_tx.h`（1711/1712 已用） |
| w3-08 | 秘境残差（出发换岗、到期兜底、战报写回）+ **顺手修 `InventorySystem.materializeDiscipleBagAndMarkDead` 同签名遮蔽** | **W4-C** | 1800–1809 | 小 | `GameEngineSecretRealmOps.kt:57` 精确；⚠️ **w3 README 的 `SecretRealmNativeOps.kt` 文件不存在** ⇒ 真值 `GameEngineSecretRealmNativeOps.kt:100/:263`；可复用 `secret_realm_platform_tx.h`（`continueSessionTx`@75） |
| **WS-5b** | **地图冻结批（已拍板完整业界方案）**：生成即数据 + `mapGenVersion` + 协议全链 + RLE 仅存储编码 + 老档再生回填 + **Kotlin `GameData` 新字段 + Room `@Database` 50→51 + `MIGRATION_50_51`**（本次核实新增，handover §2.60.2 未写明） | **W4-C** | 无（纯协议/生命周期，**已实测确认**） | 大 | [handover §2.60.2](../cpp-migration-handover-m0.md)、`terrain.h` 234L、`models.h` 1475L、`json_codec.cpp` 1500L、`game_core.cpp` 701L、`SectTerrainBridge.kt` 85L；证据链见下文核实块 |
| **RNG 阶段 3·弟子侧** | `DiscipleChatDialog`（**实测 5 个随机调用点 / 4 行**：`Random.nextInt/nextDouble`；经 `DiscipleDelegate.kt:246-252` 写弟子 `cultivation`/`skills` ⇒ 决策类）+ **守卫可判定面扩展**（见下行） | **W4-A** | 1850–1854（**条件段**） | 小 | `DiscipleChatDialog.kt` 441L；调用链 `:311 → DiscipleDelegate.kt:231-261`；[ADR rng §8](../adr/rng-determinism-remediation.md) |
| **🔴 随机源守卫盲区**（本次核实新增，**必须与上项同批**） | `RngSourceGuardTest.kt:120` 的 ② 类正则 = `\.random\(\)\|Random\.Default\|Math\.random`，**不匹配 `Random.nextInt/nextDouble`** ⇒ `DiscipleChatDialog` 的 5 个活随机点**机器不可见**（`feature/game` 主源实测 ② 类命中 0 行，登记上限却是 1）。不修则"只缩不增"纪律对该文件无效 | **W4-A** | 无 | 小 | `RngSourceGuardTest.kt` 321L（`:116-121` 正则 / 登记表） |
| **RNG 阶段 3·战斗侧** | `BattleDescriptionGenerator`（**实测 14 个调用点 / 12 行** `.random()`；输出经 `BattleSystem回合Ops2.kt:45 → BattleLog.rounds` 落 Room `battle_logs` 实体 ⇒ 决策类；玩家可见战斗确经 Kotlin 路径生产可达） | **W4-C** | 1855–1859（**条件段**） | 小 | `BattleDescriptionGenerator.kt` 267L；调用方 `BattleSystem伤害Ops3.kt:194/210/218/229` |
| **RngManager 收敛** | 三处顶层可变 `xxxRngManager`（`EnemyGenerator.kt:22` / `AISectAttackManager.kt:40` / `AISectTeamComposer.kt:9`）→ 形参必传；三处赋值点集中在 `GameEngine.kt:170-172` | **W4-C** | 无 | 小 | 主源 `var x: GameRngManager` 实测 4 文件，全部在 `RngEngineIsolationGuardTest.kt:46-62` 白名单内（4 条）；守卫**白名单是 Map、无计数断言** ⇒ 新豁免不会被机器拦下（仅注释纪律） |
| **Jade 环境缺陷** | `FakeAtomicStateStore` 事务缓冲与 `sectLevelClaimRecords` 交互（**已复现，无修复记录、无守卫，仍开放**）：`update`/`updateAndReturn`（`:252-260`/`:262-271`）仅当 `writeDepth == 0` 才 `syncFlows`；`JadeNativeTxGateTest.kt:262-268` 仍以"直接播种凭据"绕开 | **W4-B** | 无 | 小 | `FakeAtomicStateStore.kt` 336L；handover §2.50 坑 / §3 未达项 6 / §4.1 |
| **宗门领奖的输入侧随机**（本次核实新增） | `GameEngineSectLevelOps.kt:210 bloodMaterials.random()` 的结果经 `prepared` **传入 C++ `SECT_LEVEL_CLAIM_TX`**（`:80/:86/:494-525`）⇒ **C++ 事务实际发放的兽血材料由 Kotlin 全局随机决定**；既有"native 臂零 RNG"表述掩盖了该输入侧随机 | **W4-B** | 无 | 小 | `GameEngineSectLevelOps.kt` 549L；KDoc `:484-486` 自述"模板解析留 Kotlin" |

> **⚠️ 引用勘误（本方案对 w3 README §1 表的实测修正；同一错误亦见于 `ui-read-surface.md:245/250` 与 `ReverseChannelPolicy.kt:306/319`，构成三处传播链）**
> 逐行实测 51 个 `文件:行号` 引用：**46 命中 + 3 处硬错误 + 2 处轻度漂移**。
> 硬错误：① `DiscipleSlotCleanup.kt:120`（该行是 `battleTeams = updatedBattleTeams,`，位于纯函数 `clearAllSlotsDataOnly`@56 内，既非写入点也非入口）⇒ 真值 = **`GameEngineBloodRefinementOps.kt:60`**（`startBloodRefinementAtomic`@40）；② `RoadFacadeImpl.kt:41/:141`（分别为构造形参与私有函数形参）⇒ 真值 = **`:67`（`placeRoad`）/`:85`（`removeRoad`）**；③ `SecretRealmNativeOps.kt`（**文件不存在**）⇒ 真值 = **`GameEngineSecretRealmNativeOps.kt`**。
> 轻度漂移：`MerchantAndRecruitService.kt:322` → `:319`；`YearOps.kt` → `GameEngineCoreYearOps.kt`。
> **本 README 与三个批次文档一律使用修正后的真值**；`handover §7.2/§7.3` 的勘误登记应由收口人在 W4-D/D6 一并补齐（**不重写历史小节**，按"勘误标注"格式追加）。
>
> **⚠️ 血炼"死路径"结论修正**：w3 §1.1 记"血炼完成链死路径（`GameEngineBloodRefinementOps.kt:99` 零生产调用方）⇒ 删除后其字段关闭结论可保留"——**只能部分采纳**：可删的是 `GameEngineBloodRefinementOps.kt:99` 的 GameEngine 层 suspend 包装（零调用方，仅 `GameEngineDualSlotGuardTest.kt:370` 引用），而 `:167 MutableGameState.processBloodRefinementCompletions` 仍在 `MonthSettlementExecutor.kt:60` 的调用面上（**回退臂活路**）⇒ **逻辑不可删**。
>
> **✅ WS-5b 的存档前提已核实（原标 UNVERIFIED，现为 VERIFIED）**：
> ① **"存档 = Kotlin kotlinx ProtoBuf"属实**——本地 = Room `game_data` 行 + `game_heavy_data` 分块侧车；云 = `SaveData` 的 ProtoBuf 字节。证据链：`SaveLoadViewModelSaveOps.kt:235→246→249`、`GameEngine.kt:208-213`、**`SaveFacadeImpl.kt:94-117 getStateSnapshot()` 只读 `stateStore.*Snapshot` + `gameRngManager.exportStates()`，全链无 `nativeExportState()` 调用**、`UnifiedSerializationEngine.kt:64`。
> ② ⇒ **地形入档必须新增 Kotlin `GameData` 的 `@ProtoNumber` + `@ColumnInfo` 字段，并递增 `@Database(version)` 50→51 写 `MIGRATION_50_51` + 更新 schema JSON**；handover §2.60.2 把 WS-5b 读起来像"纯 C++ 协议改动"，**漏写了这一步**。
> ③ **`@Transient` + `GameHeavyData` 侧车路线不可行**：`@Transient` 不进 `SaveData`，而云上传链（`SaveLoadViewModelCloudOps.kt:65-73`）**没有** heavy→cloud 补偿 ⇒ **云档会丢地形**。
> ④ C++ 侧也不能省（`DiffSurfaceAssertion.kt:45-50` 断言"C++ 导出而 Kotlin 缺失 = 协议漂移即红"；`StateSyncService.applySnapshot` 按 `exportedKeys` 白名单合并）⇒ **两端同存**。
> ⑤ 生成落点**重定义**：C++ `initialize`（`game_core.cpp:213-280`）**拿不到 mapSeed**、C++ **无 `newGame`** ⇒ 改落在 `importStateInternal` 的归一化族（`game_core.cpp:491-548`，与 `normalizeAICorpseEntries`/`normalizeLedgers` L533/536 同位），**新档与老档走同一条路径**。
> ⑥ `rules/database-migration.md`（128L）**需扩充**（现全文无 C++/协议版本戳章节）。
>
> **⚠️ RNG 阶段 3 的实施口径修正**：ADR **并未要求 ActionId + C++ 事务**——[ADR §8/阶段 3](../adr/rng-determinism-remediation.md) 的落地形态是"**按调用点粒度下沉**"，硬要求只有 `R1`（影响状态的随机必须取自 `GameRngManager.getRng(RngPartition.*)`）。⇒ 本方案的 1850–1854 / 1855–1859 是**派工层面的加法**，两个批次开工第一步先做**形参化 / 分区化**（成本最低、零协议改动）；只有确认"必须由 C++ 事务签发结果"时才启用 ActionId 段。
>
> **⚠️ 死代码清单的口径修正（本次核实）**：① handover §5④ 的"**18+11+5 处零调用者站点**"在仓库内**没有分项清单**（仅 ADR §8 的"33 处"聚合数，33 = 18+11+5 的算术推断）⇒ **不可作为验收基准**，须先补清单（见 §8 D5）；② "洞府探索**整族**死链"过宽——洞府链确死（**双证**：入口 `CultivationService.kt:178` 零调用 + 主源无 `CaveExplorationTeam(` 生产者），但 `processSectDisciplesAging`（`CultivationEventMonthlyOps.kt:176`）、`processAISectOperations`（`:62/:95`）、`currentAiThermalBatchSize`（`GameEngineCoreMonthOps.kt:77`）**均活，不可删**；③ `GameData` 四函数中 `withWorldMap:967` **有 1 个测试调用方**（`GameDataTest.kt:282`）⇒ 真零调用为 3 个，另 `withBuildings:974` / `withEconomy:980` 亦生产零调用（宜一并纳入）；④ "6 个 Diff 测试文件"实为 **5 个 `Diff*`（含 1 个夹具 `DiffMonthSettlementFixture`）+ 1 个非 Diff（`SettlementTransactionMergeTest.kt:34`）**。

### 1.2 汇流波（W4-D，串行，**不可**放进三批）

| 项 | 为何不可并行 | 证据 |
|---|---|---|
| **w3-11 月年编排残差** | 它是 **W4-B（w3-05）× W4-C（w3-06）的 join 点**——w3 计划明写"w3-11 ← w3-05 + w3-06"。放进任一批都会让该批等待另一批完成，破坏并行性；三批同时开工时它无法启动 | [w3 README §5 串行点](../parallel-batches-w3/README.md) |
| **`DiffAuthoritativeTickTest` harness 对齐生产** | 与 w3-11 同属"扇出对齐"（ADR §8 债表明写"w3-11 批内对齐"）；且它被 4 个字段的关闭结论依赖，必须与 w3-11 同批 | [ADR §8](../adr/reverse-channel-elimination.md) |
| **batch-22a debug 埋点小批** | 触碰 `StateSyncService.kt`（987L）——该文件是 **w3-13 通道删除批的独占面**（w2 §3.3 红线"仅 batch-21 可动"的延续）。两者必须串行，且 22a 必须在 13 之前（真机 E3/N5 需要"关闭前基线"） | [non-parallel-work §3](../parallel-batches-w2/non-parallel-work.md)、`StateSyncService.kt:987` |
| **w3-13 反向通道删除批（终局）** | ADR/ w3 硬约束："**必须最后**"，前置 = w3-01…w3-12 全部合入且 `ReverseChannelPolicy` 关闭清单 = 全部传输单元 | [w3 README §4](../parallel-batches-w3/README.md)、[ADR §4](../adr/reverse-channel-elimination.md) |
| **剩余死代码清零** | `GameData.kt` 位于 `core:domain/model/`，与 **WS-5b 的 `GameData` 地形字段新增（W4-C）** 同文件；`GameSettingsData.autoSave` **已拍板按"清理"执行**（2026-09-15）。两者都必须等 W4-C 落定 | `GameData.kt`、`GameDataMerchant.kt:29` |

### 1.3 非并行轨（**不派工**，见 §9）

真机（物理设备）验证批｜WS-4 NPC 移动（待玩法设计文档）｜WS-1 阶段 3 数据导向存储（待立项）｜`TimeSystem.onPhaseTick` 删除（待拍板）｜`PresentationRandom` 跨会话同构（待拍板）

---

## 2. 拆分依据：为什么是"3 个并行批 + 1 个汇流波"

### 2.1 依赖图（w3 §5 串行点的图论化）

```
独立根节点（彼此无依赖）：w3-01  w3-03  w3-05  w3-06  w3-09  w3-10
依赖边：
  w3-02 ← w3-01
  w3-12 ← w3-03
  w3-07 ← w3-06
  w3-08 ← w3-06
  w3-11 ← w3-05 + w3-06        ← 跨"独立根"的连接边 ⇒ 汇流点
  w3-13 ← 全部（终局）
```

把这张图切成 3 个**内部连通、彼此无边**的子图，只有一种切法能同时满足"不跨批依赖"：

| 子图 | 内容 | 内部串行序 |
|---|---|---|
| **W4-A** | w3-01 → w3-02；w3-09；w3-10 | w3-01 → w3-02（w3-09/w3-10 可与之并行，同批内自行排） |
| **W4-B** | w3-03 → w3-12；w3-04；w3-05 | w3-03 → w3-12 |
| **W4-C** | w3-06 → w3-07 / w3-08；WS-5b | w3-06 → w3-07、w3-06 → w3-08 |
| **W4-D（汇流）** | w3-11（← w3-05 + w3-06）、batch-22a、w3-13 | 严格串行 |

> 另一个看似可行的切法是"把 w3-05 与 w3-06 放进同一批以吃掉 w3-11"——实测该批将承载 **5 个 w3 子批 + WS-5b**（最重），
> 而其余两批只剩 3–4 个小子批，负载严重失衡，且 `w3-05`（邮件/行商，库存域）与 `w3-06`（战斗/探索）无任何文件相邻性，
> 强行同批只增加协调成本。故**不采纳**，w3-11 单列汇流波。

### 2.2 冲突面实测：**现状下"独享文件"不成立**

用户要求"每个批次独享文件"——但实测当前仓库形态下，三批**必然**在同一批文件上撞车。逐条给出证据与冲突机理：

| 共享文件 | 行数 | 为什么必然冲突 | 三批是否都要碰 |
|---|---|---|---|
| `scripts/gen-action-ids.mjs` | 427 | 单一 `ACTION_CATALOG` 数组（`const ACTION_CATALOG = [...]`），三批都只能**追加到数组末尾的同一位置** ⇒ hunk 重叠 | ✅ 全部（各自新增事务） |
| `android/.../action_ids.h`（生成物） | — | **整文件生成**（生成器 `writeFileSync` 重写），两个分支各自重生成 ⇒ 全文件冲突 | ✅ 全部 |
| `android/.../nativebridge/ActionIds.kt`（生成物） | — | 同上 | ✅ 全部 |
| `src/execute_dispatch.cpp` | 2778 | 分发表是**链尾 `if / else if / ... / else` 序列**，三批都只能在最终 `else` 之前插入自己的 `else if` ⇒ 同一插入点 | ✅ 全部 |
| `test/CMakeLists.txt` | — | GTest 源清单是**单一 `add_executable(...)` 参数列表**，三批都在末尾追加文件名 ⇒ 同一插入点 | ✅ 全部 |
| `core/domain/.../ReverseChannelPolicy.kt` | 519 | `closedUnits` / `verdicts` / `transportedGameDataFields` 三处**单一列表字面量**；每完成一个域就要"增关闭项 + 删保留项 + 改写该域 verdict"，三批各自动相邻行 | ✅ 全部（w3 §2 第 5 步"关闭该域"） |
| `models.h` / `json_codec.cpp` | 1475 / 1500 | WS-5b（W4-C）必须增地形段 + `mapGenVersion`；w2 §3.3 明令"原则上不改" | ⚠️ W4-C 必碰；A/B 默认禁改（见 §5.4 租约） |
| `StateSyncService.kt` / `GameStateStoreImpl.kt` | 987 / 1845 | w3-13 删反向捕获/信封面；batch-22a 加 debug 埋点 | ❌ 三批一律只读（红线） |
| `CHANGELOG.md` / `changelog_entries.json` | — | 规则要求"追加到**当前版本条目的 changes 数组末尾**" ⇒ 同一插入点 | ✅ 全部 |
| `docs/cpp-migration-handover-m0.md` §3/§4.1/§5/§6 | 670 | 同一表格追加行 | ✅ 全部 |
| `GameEngine.kt` | 391 | w3-02（A）必碰（`:276/:277/:305/:306`）；w3-04（B）用 `:152` 访问器、w3-09（A）用 `:165` handler 装配 | ⚠️ W4-A 主用（租约） |
| **扇出宿主族**（`CultivationEventMonthlyOps.kt` / `MonthSettlementResidualExecutor.kt` / `YearSettlementResidualExecutor.kt` / `GameEngineCoreMonthOps.kt` / `GameEngineCoreYearOps.kt`） | 69/43/160/136 | **实测跨批共引**：`CultivationEventMonthlyOps.kt` 被 w3-03（B）/w3-05（B）/w3-08（**C**）/w3-12（B）共引；`GameEngineCoreMonthOps.kt` 被 w3-09（A）/w3-10（A）/w3-11（D）共引；`GameEngineCoreYearOps.kt` 被 w3-11（D）+ w3-05/w3-08/w3-12 可能共引 | ⚠️ **跨批命中 ⇒ 统一冻结给 W4-D（见 §2.3）** |
| **装配宿主** `GameEngineCore.kt` | 845 | w3-04（B）`:143/:233/:589/:697`、w3-09（A）`:194`、w3-11（D）`:650/:663` | ⚠️ **三批冻结，见 §2.3** |

**结论**：若不做结构性切分，"三批并行"要么因反复解冲突而退化为串行，要么出现 findings 记录过的真实事故
（**"并行会话覆盖写入同一文件会静默回退本批编辑"**、**"合并冲突取 theirs 导致结构重构整体回退"**）。
⇒ **必须插入 W4-00 前置批**。

### 2.3 跨批共引的"宿主文件"：靠**冻结**而非切分解决

逐行实测发现一类**切分解决不了**的耦合：**扇出宿主**（月/年结算的残留执行器与其编排宿主）与**装配宿主**（`GameEngineCore.kt`）会被多个 w3 子批同时引用，且它们**本来就是 w3-11 的处理对象**（"native 结算后的 Kotlin 扇出"）。

| 文件 | 共引批次 | 证据 |
|---|---|---|
| `core/engine/.../service/CultivationEventMonthlyOps.kt` | w3-03、w3-05、**w3-08**、w3-12（**跨 2 个 W4 批**） | `:70/:103 spiritMineProduction`（03）｜`:140 giveMerchantRefreshChanceIfDue`、`:183 refreshMerchantAcquisition`（05）｜`:76/:108 secretRealmExpiry`、`:79/:111 secretRealmAiTeams`、`:199 ancientSecretRealmSpawn`（08）｜`:73/:105 vassalBreakaway`、`:125/:126` 年度贡赋（12） |
| `core/engine/.../GameEngineCoreMonthOps.kt` | w3-09、w3-10（A）、w3-11（D） | `:90` residual（11）｜`:95 seizedBuildingsHandler?.invoke(...)`（09）｜`:99 restoreProductionSlotsFromMirror`（10） |
| `core/engine/.../GameEngineCoreYearOps.kt` | w3-11（必）+ w3-05/w3-08/w3-12（可能） | `:126`；年变子事件经 `CultivationEventMonthlyOps.kt:125-199` |
| `core/engine/.../service/MonthSettlementResidualExecutor.kt` / `YearSettlementResidualExecutor.kt` | w3-11 主，w3-05/08/12 可能 | `execute`@58 / `execute`@48、`applyPlatformEffects`@65 |
| `core/engine/.../GameEngineCore.kt` | w3-04（B）、w3-09（A）、w3-11（D） | `:143/:233/:589/:697 jadeSymbolService`（04）｜`:194 var seizedBuildingsHandler`（09）｜`:650/:663 month/yearSettlementResidualExecutor`（11） |

> **处置规则（本方案的核心取舍，写入 §4.4 红线与 §5.3 冻结清单）**：
> 1. **上述 6 个宿主文件在 W4-A/B/C 期间一律冻结，由 W4-D（w3-11）独占**。
> 2. 三批对宿主文件的**唯一允许动作**是"把被调用方改成幂等/只读"——**调用点的清理、删除、改写统一在 W4-D 执行**。
> 3. 理由：① 它们本就是 w3-11 的处理面（ADR §2.2 w3-11 定式："native 结算后的 Kotlin 扇出"）；② 逐批改调用点会产生"半迁移态"（调用方已删但被调用方未就绪）；③ 冻结让"跨批共引"从**冲突**变成**依赖**（W4-D 天然在 A/B/C 之后）。
> 4. 同理，`w2 §3.3` 的既有口径"下沉接线一律在 Facade / Ops / 协作类层完成"在本波**升级为硬约束**：`GameEngineCore.kt` 三批禁改。

### 2.4 交付顺序（不可调整）

```
        ┌──────────────── W4-00 并行前置批（串行，必须先合入 main）────────────────┐
        │  共享面结构性切分：ActionId 目录三分 / 分发表三分 / 测试清单三分 /        │
        │  反向策略按批三分 / 构建令牌 / worktree 脚本 / 段号与文档小节预分配        │
        └──────────────────────────────────┬───────────────────────────────────────┘
                                           │ 打 w4-base tag + git bundle 备份
        ┌──────────────┬───────────────────┼───────────────────┬──────────────┐
        ▼              ▼                   ▼                   ▼              │
     W4-A           W4-B                W4-C               （并行，三工作树）   │
   弟子与建设     内政与经济运营       战斗与世界协议                          │
        └──────────────┴───────────────────┴───────────────────┘              │
                                           │ 三批全部合入 main                │
                                           ▼                                  │
                              W4-D 汇流波（串行）                              │
                   w3-11 → harness 对齐 → batch-22a → w3-13 通道删除 → 文档收口
```

---

## 3. 并行前置批 W4-00：共享面结构性切分（**必须先做，串行**）

> 性质：**纯结构性重构，零行为变更、零新增协议**。目标 = 把 §2.2 的 6 处"必然冲突面"变成"结构上不可能冲突"。
> 归属：`main` 上单批交付（不占 W4-A/B/C 的任何 ActionId 段）；完成后打 `w4-base` tag。
> **不做本批 ⇒ 三批并行的前提不成立**（§2.2 已证）。

### 3.1 工作项（11 项，全部落地才算完成）

| # | 项 | 落点 | 判据（可验证） | 状态 |
|---|---|---|---|---|
| 1 | **ActionId 目录三分** | `scripts/gen-action-ids.mjs` 改为 `import` 四份分段清单并拼接（`action-catalog/core.mjs` 保留既有条目 + `w4a/w4b/w4c.mjs` 空骨架）；新增四条**清单自检**（id 唯一 / 段区间不相交 / 条目须落自己段内 / `core.mjs` 不得落批次段） | `node scripts/gen-action-ids.mjs` 输出 `169 actions (maxId=1734)`；**`ActionIds.kt` 逐字节不变**；`action_ids.h` 仅新增枚举数组块（见项 11） | ✅ **已完成**（`action_ids.h` 见项 11 说明） |
| 2 | **生成物合并规则固化** | `scripts/action-catalog/README.md` | 明写：两份产物是**生成物**，合并冲突**一律"重生成"**，禁止手工解冲突；每批提交前必须 `git diff --exit-code` 自证无漂移 | ✅ **已完成** |
| 3 | **C++ 分发表三分** | 新增 `include/gamecore/dispatch_w4.h`（端口契约）+ `src/dispatch_w4a.cpp` / `_w4b.cpp` / `_w4c.cpp`（空骨架，各返回 `std::nullopt`）；`execute_dispatch.cpp` **一次性**在最终 `else` 之前插入三行调用；`gamecore/CMakeLists.txt` 追加三源文件；**`scripts/build-desktop-jni.ps1` 的源清单同步追加**（该脚本与 CMake 是两处独立登记点） | 改动后 `execute_dispatch.cpp` / `gamecore/CMakeLists.txt` / `build-desktop-jni.ps1` **冻结**（三批禁改，见 §5.3）；桌面 ctest 全绿；`1735` 仍返回 `NOT_IMPLEMENTED` | ✅ **已完成** |
| 4 | **测试清单三分** | 新增 `test/w4a_tests.cmake` / `w4b_tests.cmake` / `w4c_tests.cmake`（各 `set(W4X_TEST_SOURCES )`）；`test/CMakeLists.txt` 改为 `include()` 三行 + 把三个变量并入 `add_executable` | 改动后 `test/CMakeLists.txt` **冻结**；空列表下 ctest 全绿 | ✅ **已完成** |
| 5 | **反向策略按批三分** | `ReverseChannelPolicy.kt` 的三处审计数据（`closedUnits` 68 单元 / `transportedGameDataFields` 68 字段 / 14 个域级 `verdict` 证据）按批拆到 `core/domain/.../state/reversechannel/` 下 `W4AChannelClosures.kt` / `W4BChannelClosures.kt` / `W4CChannelClosures.kt` / `W4DChannelClosures.kt`（各导出 `w4XClosedUnits` / `w4XRetainedGameDataFields` / `w4XDomainEvidence`）+ 共用条目助手 `ChannelClosureEntries.kt`；`ReverseChannelPolicy` 改为聚合（状态仍由"关闭单元 × 证据"自动推导） | `ReverseChannelPolicyGuardTest` **6 用例全绿且零改动**；`ReverseChannelPolicy` 自身此后**冻结** | ✅ **已完成**（守卫 6/6 绿，零改动） |
| 6 | **构建串行令牌** | 新增 `scripts/w4/build-token.ps1`（`-Acquire/-Release/-Status`；`C:\Mnzm\.w4-build.lock` 独占句柄 + 持有者/批次/PID/时间戳；超时退出码 2） | 两个并发 shell 竞争时只有一个拿到；释放后另一个立即拿到 | ✅ **已完成** |
| 7 | **worktree 建立/清理脚本** | 新增 `scripts/w4/setup-worktrees.ps1`（幂等：三分支 + 三工作树 + `core.quotepath false` + 复制 `android/local.properties`）与 `scripts/w4/remove-worktrees.ps1` | 🔴 清理脚本**先探测 reparse point**，junction/符号链接一律 `cmd /c rmdir`——**禁止 `Remove-Item -Recurse`** | ✅ **已完成** |
| 8 | **协议面租约表** | 新增 `docs/parallel-batches-w4/protocol-lease.md` | 登记 `models.h` / `json_codec.cpp` / `game_core.{h,cpp}` / `GameData.kt` / `GameDatabase.kt` / `GameEngine.kt` 的默认所有者与租约顺序（§5.4） | ✅ **已完成** |
| 9 | **文档小节预分配** | 本 README §4.6 + handover §2.61/§2.62/§2.63/§2.64/§2.65 | 三批只在**自己的**预分配小节内追加；§3 表 / §4.1 / §5 / §6 / `ui-read-surface` / 双 CHANGELOG = **收口人独占** | ✅ **已完成**（§2.61 已落） |
| 10 | **基线打点与备份** | `git tag w4-base` + `git bundle create`（落盘到仓库外 `C:\Mnzm\backups\`） | 🔴 本仓 `.git` 对象库**曾两次被破坏且历史不可恢复** ⇒ 每批每个里程碑必须 tag + bundle | ✅ **已完成**（`w4-base` → `d4cad20`；bundle 346MB，`verify` = "records a complete history / is okay"）。⚠️ **连带发现并已根治**：仓库原处**半打包损坏态**（3 个悬空 `archive/*` tag / 孤儿 pack 索引 / 截断临时 pack / 坏 reflog / 4590 个松散对象且 `packs: 0`）⇒ 已由 **[§2.66 仓库对象库整理批](../../cpp-migration-handover-m0.md)** 清偿：`git fsck` **6 error + 2 warning → 0 + 0**，松散对象 **4590 → 70**（保留悬空对象）、`in-pack 4520`、`packs 1`、`garbage 0`，且 **`git bundle create --all` 恢复可用** |
| 11 | **分派覆盖守卫测试** | 生成器在 `action_ids.h` 追加升序枚举数组 `action::kAllActionIds` + `kAllActionIdsCount`；新增 `test/dispatch_guard_test.cpp`（4 用例）：清单非空 / 升序且唯一 / **每个已注册动作号分派可达且落到本域 handler** / 未注册号仍返回 `NOT_IMPLEMENTED` | 首跑即抓出 **2 处真实死导出**（见下）——正是"区间写法吞动作号"缺陷类 | ✅ **已完成（首跑抓出 2 处死导出并根因修复）** |

> **项 11 首跑战绩（本前置批的直接产出）**：`INV_ADD_EQUIPMENT_INSTANCE(1011)` 与
> `INV_ADD_MANUAL_INSTANCE(1013)` 一直**无 handler**（落 `handleInventory` 的 `default:`
> → `UNKNOWN_ACTION`），且全仓**零调用方**（仅存在于两份生成物 + `docs/cpp-engine.md` 的
> 计数表；实例轨新增实际走 Kotlin `InventorySystem.addEquipmentInstance` /
> `addManualInstance` 直调）。`docs/cpp-engine.md:320` 在批 8-4 已记录"判定确认无生产调用点，
> 无需补 handler"，但**未删除**——留下的是两处死协议面。
> 按项目既有的**死导出纪律**（WS-0.b 先例）**删除这两个 ActionId** ⇒ 动作总数
> **171 → 169**。`docs/cpp-engine.md` 的计数表同批更正。
>
> **为什么这个守卫值得存在**：症状极隐蔽——动作号**存在**、常量**存在**、Kotlin **能**发出去，
> 只是永远走不到自己的 handler。单点用例发现不了（要恰好在那个号上写用例），
> 只有"对**每一个**已注册动作号断言分派可达"才能拦住。生成器提供清单 ⇒ 新增动作忘接分派时
> 本测试**自动变红**并直接报出动作号。

### 3.2 W4-00 自身的验收门禁

```bash
# 工作目录：仓库根
node scripts/gen-action-ids.mjs && git diff --exit-code        # 生成物零漂移
cd android && ./gradlew.bat compileReleaseKotlin :core:engine:compileReleaseUnitTestKotlin --max-workers=1
./gradlew.bat :core:engine:testReleaseUnitTest --max-workers=1 --rerun-tasks \
  "-Dgamecore.jni.path=C:\Mnzm\XianxiaSectNative\android\core\engine\build\desktop-jni\libgamecorejni.so"
./gradlew.bat detekt                                            # 六模块全绿，baseline 全 0
cd app/src/main/cpp/gamecore/build/desktop-test && cmake --build . && ./game-core-tests.exe   # 单进程直跑
```

**W4-00 完成的定义**：上述全部绿 + `git grep` 确认 `closedUnits`/`verdicts`/`transportedGameDataFields` 三处字面量已从 `ReverseChannelPolicy.kt` 消失 + `w4-base` tag 已打 + bundle 已落盘。

---

## 4. 协作协议（三批必读）

### 4.1 工作区隔离（三层）

| 层 | 机制 | 说明 |
|---|---|---|
| **源码** | `git worktree` 三个独立工作树：`C:\Mnzm\XianxiaSectNative-w4a` / `-w4b` / `-w4c`，各挂独立分支 | 三批**不共享工作目录**；`git status` 快照天然可信 |
| **构建** | **构建令牌**（W4-00 项 6）+ 各工作树自带的 `.gradle`/`build` 目录 | 重活（Gradle 全量测试 / detekt 六模块 / NDK / lintRelease / 桌面 ctest 构建 / 桌面 JNI 重建）**必须先 `build-token.ps1 -Acquire`**，完事 `-Release`。理由：Windows 下多会话共享 Gradle daemon 的 `classes.jar` 文件锁可持续数分钟、KSP 增量缓存在多进程下频繁损坏、双 gradle 构建共享模块 jar 撞车（findings 实测） |
| **产物** | JNI 对拍路径按**本工作树**绝对路径传入 | 例：`-Dgamecore.jni.path=C:\Mnzm\XianxiaSectNative-w4a\android\core\engine\build\desktop-jni\libgamecorejni.so`——**误用别的工作树的 .so 会得到假绿/假红** |

### 4.2 分支与提交

| 项 | 协议 |
|---|---|
| 分支名 | `w4/a-disciple-building`｜`w4/b-court-economy`｜`w4/c-battle-world`｜`w4/d-convergence` |
| 提交粒度 | 一个 w3 子批 = 一个提交（含其 C++ 事务 + Kotlin native 臂 + GateTest + 该域的 `W4XChannelClosures.kt` 关闭项 + 文档小节） |
| 暂存纪律 | 🔴 **`git commit -- <本批触碰文件清单>`**，**禁止整仓 `git add`**（历史事故：整仓 add 混入 433 个并行改动文件） |
| 提交说明 | 中文；写明**根因 / 口径差异 / 验证数值**；共享文件必须**整组提交**（`scripts/action-catalog/w4X.mjs` + 两生成物同一次提交） |
| 里程碑备份 | 每个子批提交后：`git tag w4x/<nn>` + `git bundle create`（防 `.git` 损坏，见 §3.1 项 10） |

### 4.3 合并（收口人执行）

1. **顺序**：W4-A → W4-B → W4-C → W4-D（W4-D 必须最后）。
2. **禁止 `theirs` 整体覆盖**：合并前先判"分支基线是否为旧结构"——历史事故中取 theirs 把前一批的成员下放整体回退。冲突一律"取 HEAD 结构 + 只补本批新增"。
3. **生成物冲突**：`action_ids.h` / `ActionIds.kt` 冲突**不做手工合并**，改为在合并后的树上 `node scripts/gen-action-ids.mjs` 重生成 + `git diff --exit-code` 自证。
4. **冻结文件**：`execute_dispatch.cpp` / `test/CMakeLists.txt` / `ReverseChannelPolicy.kt` 在 W4-00 后**不应出现在三批的任何 diff 中**——若出现，说明切分被绕过，必须打回。
5. **干净检出复验**：合入 `main` 前，在**隔离的干净检出**（`git worktree add --detach` 到临时目录）跑一次全量门禁（§7）。主工作树的"自评通过"**不作为**交付依据（历史事故：batch-01 自评通过实为 357 编译错 + 216 测试失败）。
6. **合并后清理**：删分支前先打 `archive/w4-*` tag（非祖先提交）；清理 worktree 用 §3.1 项 7 的脚本（禁止 `Remove-Item -Recurse`）。

### 4.4 红线（三批共用，违者打回）

1. 🔴 **RNG 抽取序零变更**：任何下沉不得改变抽取集与顺序；涉及循环形状的改动必须双端同步 + 走拍板。
2. 🔴 **失败零写入**：事务校验链先行；任一校验失败 ⇒ 零状态变更 + failure 信封 ⇒ Kotlin 回退臂重执行校验链（用户可见文案由 Kotlin 臂产出）。
3. 🔴 **回退臂不得退化**：Kotlin 原路径**保留为降级回退臂**，本波**一律不删**（删除属 W4-D 的 w3-13）；GateTest 必须覆盖 flag-OFF 臂。
4. 🔴 **禁止新增 JNI 导出**：一律走 `GameEngineNativeOps.tryExecuteNative`（`executeRaw` 用于需保留失败信封的域，参照 batch-24 三态分派）。
5. 🔴 **禁改反向捕获/信封面**：`GameStateStoreImpl.kt` / `StateSyncService.kt` **三批只读**；每批只改自己域在 `W4XChannelClosures.kt` 里的关闭项。
6. 🔴 **禁改 `GameViewModel.kt`**：下沉接线一律在 Facade / Ops / 协作类层完成。
7. 🔴 **detekt baseline 只缩不增**（六模块现为全 0）：新违规**实修**或附理由 `@Suppress`；同声明多 `@Suppress` 必须**合并为单注解多参数**（重复注解＝编译错，且只生效其一）。
8. 🔴 **`stateSyncServiceRef` 非空声明 + mock = 调用点 NPE**：新 native 协作类必须先赋给可空局部再判空。
9. 🔴 **`models.h` 原则上不改**：确需新增字段 ⇒ 走 §5.4 租约 + 同步 `json_codec` 双向编解码 + 对拍键集红线声明。
10. 🔴 **验收以"关闭域写入检测零命中"为准**：`ReverseChannelPolicy.noteClosedWrite` 命中即 `DomainLog.e` + 计数 ⇒ 该域存在漏网写者，**不得**以"对拍没红"掩盖。
11. 🔴 **宿主文件冻结**（§2.3）：`CultivationEventMonthlyOps.kt` / `MonthSettlementResidualExecutor.kt` / `YearSettlementResidualExecutor.kt` / `GameEngineCoreMonthOps.kt` / `GameEngineCoreYearOps.kt` / `GameEngineCore.kt` —— **三批禁改**；调用点清理统一留 W4-D。
12. 🔴 **接线只能在域门面 / Ops / 协作类层**：`GameViewModel.kt` 与 `GameEngineCore.kt` 禁改；`GameEngine.kt` 按租约（§5.4）。
13. 🔴 **弟子通道（`DISCIPLE_CHANNEL`）与集合段（`COLLECTION`）的关闭动作统一在 W4-D 执行**：W4-A 只负责"把弟子写者归零 + 在 `W4AChannelClosures.kt` 标注 `discipleChannelClosable = true`"。理由：`StateSyncService.appendDiscipleSection`（`:543-575`）是弟子通道专属代码，而该文件是 w3-13 的删除面（§5.3 冻结）。

### 4.5 共享文件所有权（W4-00 之后）

| 文件 | W4-00 后的所有者 | 其他批 |
|---|---|---|
| `scripts/action-catalog/w4{a,b,c}.mjs` | 各自批次 | 互斥 |
| `scripts/gen-action-ids.mjs` / `action_ids.h` / `ActionIds.kt` | **冻结**（生成器脚本本身不改；生成物由提交者各自重生成，收口时统一重生成） | — |
| `src/execute_dispatch.cpp` / `src/CMakeLists.txt` / `test/CMakeLists.txt` | **冻结** | 各批只改 `src/dispatch_w4X.cpp` 与 `test/w4X_tests.cmake` |
| `ReverseChannelPolicy.kt` | **冻结** | 各批只改 `reversechannel/W4XChannelClosures.kt` |
| **宿主文件族**：`CultivationEventMonthlyOps.kt`、`MonthSettlementResidualExecutor.kt`、`YearSettlementResidualExecutor.kt`、`GameEngineCoreMonthOps.kt`、`GameEngineCoreYearOps.kt`、`GameEngineCore.kt` | **W4-D**（w3-11 扇出面） | 🔴 **三批禁改**（§2.3/§4.4 第 11/12 条） |
| `state/models.h` / `src/json_codec.cpp` | 见 §5.4 租约（默认 W4-C，WS-5b） | 需申请 |
| `GameStateStoreImpl.kt` / `StateSyncService.kt` | W4-D（w3-13 + batch-22a） | 三批**只读** |
| `GameEngine.kt` | W4-A（w3-02） | 其他批需申请租约 |
| `CHANGELOG.md` / `changelog_entries.json` / handover §3·§4.1·§5·§6 / `ui-read-surface.md` §4.4 / `docs/cpp-engine.md` §9 | **收口人独占** | 各批只在 PR 描述里给"文档同步项" |

### 4.6 handover 章节预分配

| 批 | handover 小节 |
|---|---|
| W4-00 | §2.61 |
| W4-A | §2.62 |
| W4-B | §2.63 |
| W4-C | §2.64 |
| W4-D | §2.65 |
| §2.66（仓库对象库整理批） | 已落（基础设施批，非代码批） |

> 各批**只在自己小节内追加**；`§3 验证表` / `§4.1 遗留待办` / `§5 下轮建议` / `§6 主轴剩余` 由收口人统一更新（避免三批同改一节）。

---

## 5. 文件所有权矩阵（跨批互斥，逐文件实测）

### 5.1 Kotlin 侧

| 文件（仓库相对路径） | 行数 | 所有者 | 备注 |
|---|---|---|---|
| `android/core/engine/.../engine/GameEngineCoordination.kt` | 153 | **W4-A** | w2 时代的"最大冲突源"，本轮由 W4-A 独占 |
| `.../engine/domain/disciple/DiscipleFacadeImpl战斗Ops2.kt` | 344 | **W4-A** | |
| `.../engine/domain/disciple/DiscipleStatusService.kt` | 693 | **W4-A** | |
| `.../engine/domain/disciple/DiscipleSlotManager.kt` | 207 | **W4-A** | |
| `.../engine/domain/disciple/DiscipleLifecycleNativeTx.kt` | 188 | **W4-A** | |
| `.../engine/domain/disciple/DiscipleSlotCleanup.kt` | 272 | **W4-A** | |
| `.../engine/domain/disciple/DiscipleLifecycleManager.kt` | 268 | **W4-A** | |
| `.../engine/service/DiscipleLifecycleProcessor.kt` | 538 | **W4-A** | |
| `.../engine/GameEngineManualOps.kt` | 222 | **W4-A** | 新发现稳态写者（`replaceManual`） |
| `.../engine/GameEngine.kt` | 391 | **W4-A** | 租约文件（§5.4） |
| `.../engine/domain/building/BuildingNativeTx.kt` | 275 | **W4-A** | |
| `.../engine/domain/building/BuildingFacadeImpl同步Ops.kt` | 327 | **W4-A** | |
| `.../engine/domain/road/RoadFacadeImpl.kt` | 222 | **W4-A** | |
| `.../feature/game/.../delegate/BuildingDelegate.kt` | 388 | **W4-A** | |
| `.../engine/service/ProductionProcessorCleaOps3.kt` | 416 | **W4-A** | |
| `.../engine/service/ProductionProcessor构筑Ops2.kt` | 419 | **W4-A** | |
| `.../feature/game/.../dialogs/DiscipleChatDialog.kt` | 441 | **W4-A** | RNG 阶段 3·弟子侧 |
| `.../feature/game/.../SpiritMineViewModel.kt` | 294 | **W4-B** | |
| `.../engine/GameEnginePatrolOps.kt` | 124 | **W4-B** | 含死 API 删除 |
| `.../engine/service/JadeSymbolService.kt` | 423 | **W4-B** | 玉符唯一消耗入口（守卫测试在位） |
| `.../engine/service/MailAttachmentDistributeOps.kt` | 436 | **W4-B** | |
| `.../engine/service/MerchantAndRecruitService.kt` | 383 | **W4-B** | |
| `.../engine/domain/diplomacy/DiplomacyService.kt` | 845 | **W4-B** | |
| `.../engine/domain/diplomacy/VassalService.kt` | 480 | **W4-B** | |
| `.../engine/GameEngineDiplomacyOps.kt` | 54 | **W4-B** | |
| `.../engine/domain/save/SaveFacadeImpl.kt` | 122 | **W4-B** | |
| `.../engine/GameEngineServiceOps.kt` | 214 | **W4-B** | |
| `.../engine/FakeAtomicStateStore.kt`（测试源，336L） | 336 | **W4-B** | Jade 环境缺陷专项 |
| `.../engine/domain/battle/CombatService.kt` | 406 | **W4-C** | |
| `.../engine/GameEngineWorldBattleOps.kt` | 398 | **W4-C** | |
| `.../engine/GameEngineExplorationNativeOps.kt` | 213 | **W4-C** | |
| `.../engine/GameEngineBattleOps.kt` | 389 | **W4-C** | |
| `.../engine/GameEngineSecretRealmOps.kt` | 257 | **W4-C** | |
| `.../engine/GameEngineSecretRealmNativeOps.kt` | 312 | **W4-C** | |
| `.../engine/domain/battle/BattleDescriptionGenerator.kt` | 267 | **W4-C** | RNG 阶段 3·战斗侧 |
| `.../engine/domain/battle/EnemyGenerator.kt` | 347 | **W4-C** | RngManager 收敛 |
| `.../engine/domain/battle/AISectAttackManager.kt` | 547 | **W4-C** | RngManager 收敛 |
| `.../engine/domain/battle/AISectTeamComposer.kt` | 214 | **W4-C** | RngManager 收敛 |
| `.../engine/util/SectTerrainBridge.kt` | 85 | **W4-C** | WS-5b |
| `.../core/domain/.../model/MapPreloadData.kt` | 47 | **W4-C** | WS-5b |
| `.../engine/GameEngineSaveOps.kt` | 20 | **W4-C** | WS-5b（存档快照携带地形段） |
| `.../core/architecture/RngSourceGuardTest.kt`（测试源） | 321 | **W4-A 独占** | 🔴 只有 W4-A 需要**扩展机器可判定面**（② 类正则补 `Random.nextInt/nextDouble` + 登记 `DiscipleChatDialog`）。W4-C 的随机点下沉**不要求改本文件**（登记上限是"实际命中 ≤ 上限"的只缩不增语义，不下调也仍然绿）——收缩统一留 W4-D |
| `.../core/architecture/RngEngineIsolationGuardTest.kt`（测试源） | 126 | **W4-D**（白名单收缩） | W4-C 收敛三处 `xxxRngManager` 后**不改本文件**（残留的白名单条目无害）；白名单收缩 + 条目数落成计数断言在 W4-D 统一执行（守卫**只缩不增**） |
| `.../engine/GameEngineSectLevelOps.kt` | 549 | **W4-B** | 宗门领奖输入侧随机（`:210`）+ Jade 相关 |
| `.../engine/GameEngineCoreAuthoritativeOps.kt` | 144 | **W4-D** | batch-22a 的每旬镜像成本埋点落点（`:50-83`，`:61 applyDirtyFromNative` / `:77 applyDirtyToNative`） |
| `.../state/GameStateStoreImpl.kt` | 1845 | **W4-D** | 三批只读 |
| `.../nativebridge/StateSyncService.kt` | 987 | **W4-D** | 三批只读 |
| `.../state/ReverseChannelPolicy.kt` + `reversechannel/*` | 519 | **W4-00 切分 / 各批改自己的文件** | §3.1 项 5 |
| `.../model/GameData.kt`（`core:domain`） | — | **W4-C**（租约第一顺位） | WS-5b 新增 `mapGenVersion` + 地形段（`@ProtoNumber`/`@ColumnInfo`）；W4-D/D5 的死函数清理须排在其后 |
| `core/data/.../local/GameDatabase.kt` + `GameDatabaseMigrationsV51.kt` + schema JSON | — | **W4-C** | WS-5b：`@Database` 50→51 + `MIGRATION_50_51` |

### 5.2 C++ 侧

| 文件 | 所有者 | 备注 |
|---|---|---|
| `include/gamecore/system/disciple_tx.h` / `disciple_lifecycle_tx.h` / `appointment_tx.h` | **W4-A** | 复用既有地基，**零重写** |
| 新建 `include/gamecore/system/building_residual_tx.h` / `production_residual_tx.h` | **W4-A** | |
| `include/gamecore/system/patrol_tx.h` / `jade_tx.h` | **W4-B** | 复用 |
| 新建 `include/gamecore/system/mail_tx.h` / `merchant_tx.h` / `diplomacy_selfheal_tx.h` | **W4-B** | |
| `include/gamecore/system/exploration_tx.h` / `battle*.h` / `secret_realm_platform_tx.h` | **W4-C** | 复用 |
| 新建 `include/gamecore/system/battle_residual_tx.h` / `battle_description_tx.h` | **W4-C** | |
| `include/gamecore/map/terrain.h`（234L）+ 新建地质/存档段编解码 | **W4-C** | WS-5b |
| `include/gamecore/state/models.h` / `src/json_codec.cpp` / `src/game_core.{h,cpp}` | **W4-C**（租约，§5.4） | WS-5b 主体 |
| `src/dispatch_w4{a,b,c}.cpp` | 各自批次 | W4-00 建立 |
| `test/w4{a,b,c}_tests.cmake` | 各自批次 | W4-00 建立 |
| `src/execute_dispatch.cpp` / `src/CMakeLists.txt` / `test/CMakeLists.txt` | **冻结**（W4-00 后） | 出现在三批 diff 中即打回 |

### 5.3 冻结清单（W4-00 交付后不得被三批触碰）

**结构性冻结（W4-00 切分产生的"新归属文件"）**：
`scripts/gen-action-ids.mjs`｜`scripts/action-catalog/core.mjs`｜`include/gamecore/action_ids.h`｜`ActionIds.kt`｜`src/execute_dispatch.cpp`｜`gamecore/CMakeLists.txt`｜`test/CMakeLists.txt`｜`scripts/build-desktop-jni.ps1`｜`ReverseChannelPolicy.kt`｜`reversechannel/ChannelClosureEntries.kt`｜`GameStateStoreImpl.kt`｜`StateSyncService.kt`｜`GameViewModel.kt`

**跨批共引宿主（§2.3，切分解决不了 ⇒ 冻结给 W4-D）**：
`CultivationEventMonthlyOps.kt`｜`MonthSettlementResidualExecutor.kt`｜`YearSettlementResidualExecutor.kt`｜`GameEngineCoreMonthOps.kt`｜`GameEngineCoreYearOps.kt`｜`GameEngineCore.kt`

> 冻结 ≠ 不能改：如需改，先由**收口人**串行化（把改动安排到 W4-D，或临时收回冻结状态并广播给另两批）。
> **判据可机器执行**：`git diff --name-only w4-base..<batch-branch>` 中若出现上述任一文件 ⇒ 该批打回。

### 5.4 协议面租约（`models.h` / `json_codec.cpp` / `game_core.*` / `GameEngine.kt` / `GameData.kt` / `GameDatabase.kt`）

| 资源 | 默认所有者 | 租约顺序 | 申请方式 |
|---|---|---|---|
| `state/models.h` + `src/json_codec.cpp` | **W4-C**（WS-5b 必须改） | W4-C → W4-A → W4-B | 在 `protocol-lease.md`（**由 W4-00 创建**）登记一行（批次 / 文件 / 字段 / 起止提交）；未拿到租约的批**必须走"Kotlin 组装参数传入"路线**（w2 §3.3 既有口径） |
| `game_core.h` / `game_core.cpp` | **W4-C**（WS-5b：`importStateInternal` 归一化族新增 `ensureTerrainGenerated`） | 同上 | 同上 |
| `core/domain/.../model/GameData.kt` | **W4-C**（WS-5b 新增 `mapGenVersion` + 地形段） | W4-C → W4-D（D5 死函数清理） | 同上 |
| `core/data/.../local/GameDatabase.kt`（+ Migration + schema JSON） | **W4-C**（`@Database` 50→51） | 独占（无其他批需要） | — |
| `GameEngine.kt` | **W4-A**（w3-02 必碰 `:276/:277/:305/:306`） | W4-A → W4-C → W4-B | W4-C 的 `:170-172` 赋值点随形参化移除需申请；未获租约时该项顺延 |

> **实测注记**：w3 十二批**无一批需要改 `models.h`/`json_codec.cpp`**（w3 §3 的原子变更集是"五件套"，不含模型面；14 个 `*_tx.h` 只是**只读 include**）⇒ 本租约预期**零争用**，保留仅为兜底。

---

## 6. ActionId 段分配（沿用 w3 已公布编号，零文档漂移）

> 实测基线：**169 动作 / maxId = 1734**（`node scripts/gen-action-ids.mjs` 输出；**W4-00 已落地**，含净减 2 处死导出——见 §3.1 项 11）。w3 §1 已公布 1740–1849 的逐批段号且**一条都未使用**，本方案**原样沿用**，只补"归属映射"，不做重编号（避免制造新的文档漂移）。

| 段 | 用途 | 归属批 | w3 子批 |
|---|---|---|---|
| 1735–1739 | 紧急修复预留 | — | — |
| 1740–1749 | 弟子操作面 | **W4-A** | w3-01 |
| 1750–1759 | 弟子生命周期第二波 | **W4-A** | w3-02 |
| 1760–1765 | 巡逻/住所/矿场自愈 | **W4-B** | w3-03 |
| 1766–1769 | 玉符运行时 | **W4-B** | w3-04 |
| 1770–1779 | 邮件附件 + 行商刷新 | **W4-B** | w3-05 |
| 1780–1789 | 战斗/探索残差 | **W4-C** | w3-06 |
| 1790–1799 | 宗门战战后段 | **W4-C** | w3-07 |
| 1800–1809 | 秘境残差 | **W4-C** | w3-08 |
| 1810–1819 | 建筑/道路残差 | **W4-A** | w3-09 |
| 1820–1829 | 生产残差 | **W4-A** | w3-10 |
| 1830–1839 | 月年编排残差 | **W4-D** | w3-11 |
| 1840–1849 | 外交/自愈/运行态 | **W4-B** | w3-12 |
| **1850–1854** | RNG 阶段 3·弟子侧（`DiscipleChatDialog` 对话效果事务）——**条件段**：仅当 ADR 阶段 3 判定"需 ActionId + C++ 事务"时启用，否则空置 | **W4-A** | 新增 |
| **1855–1859** | RNG 阶段 3·战斗侧（`BattleDescriptionGenerator`）——**条件段**，同上 | **W4-C** | 新增 |
| **1860–1869** | W4-D 机动（harness 对齐相关的必要事务） | **W4-D** | 新增 |

**纪律**：段内用不满则余量留空，**禁止跨批复用**；`w4{a,b,c}.mjs` 三文件各自只写自己的段（W4-00 §3.1 项 1）。

---

## 7. 统一验证命令模板与验收门禁

> 工作目录 = `android/`（Gradle 根）；**仓库根脚本须写 `../scripts/...`**。所有重活**先取构建令牌**（§4.1）。
> 坑（沿用 w2 §6 与 findings）：`-D` 单横线；JNI 路径必须**完整文件绝对路径**（传目录 ⇒ `UnsatisfiedLinkError` **假失败**）；系统属性非任务输入，必须 `--rerun-tasks`（否则 `UP-TO-DATE` **假绿**）；桌面套件以 `build/desktop-test/` 为准；GTest 运行时需 `llvm-mingw-<版本>-ucrt-x86_64\bin` 在 PATH（否则 `STATUS_DLL_NOT_FOUND`）。

```bash
# ① 生成物零漂移（每批提交前必跑）
node ../scripts/gen-action-ids.mjs && git diff --exit-code

# ② 桌面 C++ 全量单测（触碰 C++ 必跑；构建期并行会让 ctest per-test 假失败 ⇒ 追加单进程复核）
cd app/src/main/cpp/gamecore/build/desktop-test && cmake --build . && ctest
./game-core-tests.exe                      # 单进程直跑复核（防"exe 被并行构建复写"的假失败）

# ③ 重建桌面 JNI（对拍用；触碰 C++ 必跑）
pwsh -File ../scripts/build-desktop-jni.ps1

# ④ 引擎全量单测（对拍验收）——路径必须指向【本工作树】
./gradlew.bat :core:engine:testReleaseUnitTest --rerun-tasks --max-workers=1 \
  "-Dgamecore.jni.path=<本工作树绝对路径>\android\core\engine\build\desktop-jni\libgamecorejni.so"

# ⑤ 六模块 detekt（baseline 全 0，禁增）
./gradlew.bat :app:detekt :core:data:detekt :core:domain:detekt :core:engine:detekt :feature:game:detekt :core:ui:detekt

# ⑥ 编译（主源 + 测试源——改名/拆分批必须跑测试源；主源编译不编译测试源）
./gradlew.bat :core:engine:compileReleaseKotlin :core:engine:compileReleaseUnitTestKotlin \
  :feature:game:compileReleaseKotlin :feature:game:compileReleaseUnitTestKotlin \
  :app:compileReleaseKotlin :app:compileReleaseUnitTestKotlin

# ⑦ 提交门（触碰 C++ 必跑）
./gradlew.bat :app:externalNativeBuildRelease :app:lintRelease

# ⑧ 模块回归（按触碰面）
./gradlew.bat :core:data:testReleaseUnitTest --max-workers=1
./gradlew.bat :feature:game:testReleaseUnitTest --max-workers=1
./gradlew.bat :app:testReleaseUnitTest --max-workers=1
```

### 7.1 每批"完成"的判定（缺一不可）

| # | 门禁 |
|---|---|
| 1 | 桌面 C++ 全量绿（+ 单进程直跑复核） |
| 2 | `:core:engine` 全量绿（含 **47** 个 `Diff*` 对拍类）+ 本批 GateTest 绿（flag OFF vs ON 逐位一致） |
| 3 | 六模块 detekt 绿 + baseline 计数守卫未增长 |
| 4 | 主源 + 测试源编译绿 |
| 5 | `:app:externalNativeBuildRelease` + `:app:lintRelease` 绿 |
| 6 | `node scripts/gen-action-ids.mjs && git diff --exit-code` 空 |
| 7 | **关闭域写入检测零命中**（该域运行期无 `ReverseChannelPolicy.noteClosedWrite` 触发的 ERROR） |
| 8 | `ReverseChannelPolicyGuardTest` 6 用例绿（穷尽分类 / 域结论完整 / 证据格式 / 协议名校验 / 审计红线 / 逐域回滚） |
| 9 | 该域 `ui-read-surface.md` §4.4 残余清单在 PR 中给出"可关闭/仍保留 + 证据"的判定 |
| 10 | 每个子批已有 `w4x/<nn>` tag + git bundle |

---

## 8. 汇流波 W4-D（串行）

| 序 | 工作 | 前置 | 关键点 |
|---|---|---|---|
| D1 | **batch-22a debug 埋点小批** | — | 唯一代码面 = `StateSyncService`（反向信封**体积/耗时**：发送链 `:410-440` 目前**零埋点**，体积构成只有单测 harness `ReverseChannelVolumeProfileTest`）+ `GameEngineCoreAuthoritativeOps.kt`（每旬镜像成本：`:50-83`，现有仅**整 tick** 耗时 `GameEngineCorePausOps4.kt:36/62`，无镜像分段计时）——**只能 debug 构建输出、release 零开销**；⚠️ **必须先于 D4**（真机 E3/N5 需要"关闭前基线"） |
| D2 | **w3-11 月年编排残差** | W4-B（w3-05）、W4-C（w3-06） | 扇出项逐条判定：状态写 → C++；通知/日志 → Kotlin；引导领奖 `GameEngineGuideOps.claimGuideReward`（`:57`）下沉；兑换码 `RedeemCodeService`（`:153/:402`）按既有结论**登记不下沉**（C++ 无物品随机生成器，RNG 红线）。**本批同时消化 §2.3 冻结的宿主文件族调用点清理** |
| D3 | **`DiffAuthoritativeTickTest` harness 对齐生产** | D2 | ADR §8 债：把 harness 的 AUTHORITATIVE 管线从"Kotlin 月/年完整编排"改为"C++ 月结 + Kotlin 残差"，使测试面 = 生产面；随后**重评** 4 个被覆写字段（`spiritMineLastSettledMonth` / `annualAlchemyCount` / `availableMissions` / `yearlyReports`）的关闭结论。**＋ 并入项（2026-09-15 拍板，§2.68 复验发现）**：把 WS-5b 的 `terrainTiles` / `mapGenVersion` 从 `DiffSurfaceAssertion.diffIsMirrorGeneratedField` 的**对拍排除面**中移除——做法 = 让 harness 的 Kotlin 侧也跑与生产同款的 boot 回填（当前 harness 不跑 ⇒ Kotlin 期望快照恒无该段 ⇒ 当初只能排除），随后两字段按普通字段参与全状态逐字段对拍。验收 = 排除面删除后 47 个 `Diff*` 全绿 |
| D4 | **w3-13 反向通道删除批（终局）** | D1–D3 + W4-A/B/C 全部合入 | 按 ADR §4 五步：**先禁用（信封体积=0 但代码在）→ 跑完整业务周期（≥1 游戏年 + 离线结算 + 跨旬月 + 存档写读，期间关闭域写入检测零命中）→ 状态指纹零差异（同档同输入 ≥100 旬）→ 删前打归档 tag → 再删除**。删除面：`captureReverseDirty` / `ReverseDirtyAccumulator` / `applyDirtyToNative` + 反向信封构建 / `consumeReverseDirty` / `GameCore::applyReverseDirty` + JNI 导出 / `ReverseChannelPolicy` 本体。保留面：前向镜像 + 全量导入（读档/新档基线）+ 各域回退臂。**防复发**：镜像只读契约（结构性/编译期约束优先）+ "稳态零写入断言"进长期 CI |
| D5 | **死代码清零 + 守卫面收口** | W4-C（WS-5b 落定后） | ① 🔴 **先补清单**：handover §5④ 的"18+11+5 处零调用者站点"**仓库内无分项清单**（仅 ADR §8 的"33 处"聚合数）⇒ 无基准不可验收，本步第一件事是用 `git grep` 产出可核对的三份清单（生产调用 0 / 测试引用 n），再逐条删除；② **洞府探索生命周期入口死链**（**双证**：`CultivationService.kt:178` 零调用 + 主源 `CaveExplorationTeam(` 构造 0 处）——⚠️ **只删该入口链**，`processSectDisciplesAging` / `processAISectOperations` / `currentAiThermalBatchSize` **是活路，禁删**；③ `GameData` 死函数：`totalSpiritStonesSellValue:887` / `withOrganization:992` / `withExploration:1003`（真零调用）+ `withWorldMap:967` / `withBuildings:974` / `withEconomy:980`（生产零调用，仅测试引用——删除时同步清理 `GameDataTest`）；④ `GameSettingsData.autoSave` 孤儿模型（`GameDataMerchant.kt:29/33`；**实测细化**：`EnumConverters.kt:30/35` 的 TypeConverter 悬空、`AudioConfig.kt:21 updateFromSettings` 零调用、`:app` 全模块零引用）——**已拍板（2026-09-15）：按"清理"执行**（连同 TypeConverter 与 `updateFromSettings` 一并删除）；⑤ `RngSourceGuardTest` 登记上限与 `RngEngineIsolationGuardTest` 白名单的**收缩**（W4-A/W4-C 交付后，守卫只缩不增） |
| D6 | **文档与版本收口** | D1–D5 | handover §3 验证表 / §4.1 勾销 / §5 下轮建议 / §6 主轴剩余 / `ui-read-surface.md` §4.4 / `docs/cpp-engine.md` §9 计数表 / `CODE_WIKI.md` / 双更新日志（`CHANGELOG.md` + `changelog_entries.json`，**必须一起**；同版本条目追加到 `changes` 数组末尾，禁新建同版本第二条目）；`version.properties` 由**用户**决定是否递增 |

---

## 9. 非并行轨（不派工，需用户决策或硬件）

| 项 | 为什么不能进三批 | 需要什么 |
|---|---|---|
| **真机（物理设备）验证批** | 非代码批：需物理设备 + 人工操作 + logcat 观察。代码面仅 batch-22a（已入 W4-D/D1） | 物理设备。**串行约束**：E3/N5（反向信封体积/耗时真机绝对值）必须在 w3-13 关闭**之前**采基线 ⇒ 建议在 W4-A/B/C 进行期间就绪设备并跑 D1 后的埋点采基线 |
| **WS-4 NPC 移动系统** | 缺**玩法设计文档**（数量上限 / 生成规则 / 与弟子系统关系 / 可行走语义），未拍板前派工 = 白做 | 用户补充玩法设计文档 |
| **WS-1 阶段 3 数据导向存储** | 需**单独立项**：涉及约 145+ 列写点的回归面，协议形状变更会影响全部 47 个 `Diff*` 对拍与存档格式 | ✅ **已决策（2026-09-15，用户拍板"按桌面 Release 数据决策"）：不立项协议 v2**。桌面 Release 实跑：7.9ms@100 弟子（~0.10 ms/弟子/旬）× **玩家实际规模 ≈100** ⇒ 2x 下占旬间隔 **0.8%**，且每旬镜像在**引擎后台协程**（`GameEngineCoreAuthoritativeOps.kt:63`）不占渲染线程 ⇒ 无瓶颈。**再评估阈值（可机测）**：实际规模 **>400 弟子** 或 D1 埋点真机每旬镜像 **>100ms**（观测手段 = W4-D/D1 既有埋点，零额外成本）。详见 handover §4.1 |
| **`TimeSystem.onPhaseTick` 删除** | 待拍板。**实测确认**：它是 6 个测试文件的对拍基准（`DiffYearSettlementTest:750` / `DiffTimeTest:57` / `DiffPhaseSettlementTest:433/506/644` / `DiffMonthSettlementFixture:551` / `DiffAuthoritativeTickTest:396/523` / `SettlementTransactionMergeTest:34`）⇒ 删除须先把 6 个测试改写为纯 C++ 断言，**会失去独立 Kotlin 基准**（防"复刻漂移"的最后一道防线） | 用户拍板。**建议 = 保留**（零生产成本）；若只为消除"生产面死方法"，更省的替代是把基准实现**移入测试源集**（6 个测试仅改 import，基准不损失）。**配套事实**：`PhaseSettlementExecutor.execute()` 是同类基准路径，应同进同退（§4.2） |
| **`PresentationRandom` 跨会话同构** | ADR §11 盲区 3：是否需要跨会话同构待产品口径 | 用户拍板。**⚠️ 拍板前须先处置一处文档与实现不符**：`seedFromWorld(mapSeed)` **全仓零调用** ⇒ 种子恒为编译期常量，KDoc 的"按 `mapSeed` 派生 / 同会话可复现"不成立（实测见 handover §4.2）。**建议**：改为"按场景键派生"（跨会话一致 + 零协议面 + 不入档） |
| **`GameSettingsData.autoSave` / `GameData` 死函数清理** | 死代码本身可清，但 `GameData.kt` 与 **WS-5b 的地形字段新增（W4-C）** 同文件 ⇒ 排序到 W4-D/D5 | ✅ **已拍板（2026-09-15）：按"清理"执行**——删除 `GameSettingsData` 孤儿模型（`GameDataMerchant.kt:29`）+ 悬空 TypeConverter（`EnumConverters.kt:30/35`）+ 零调用方法 `AudioConfig.updateFromSettings`（`:21`），连同 `GameData` 死辅助函数（`totalSpiritStonesSellValue:887`/`withOrganization:992`/`withExploration:1003` + 生产零调用的 `withWorldMap:967`/`withBuildings:974`/`withEconomy:980`）在 **W4-D/D5** 一次性清除（须排 WS-5b 落定之后，因同文件） |

---

## 10. 风险评估与兜底

| 风险 | 概率 | 影响 | 兜底 |
|---|---|---|---|
| **三批并行导致共享文件冲突，退化为串行** | 高（未做 W4-00 时≈必然） | 高（进度与结构双损） | W4-00 结构性切分（§3）；冻结清单（§5.3）；收口人禁止 theirs 覆盖（§4.3） |
| **`models.h` 被两批同时改（协议漂移）** | 中 | 高（对拍键集红线破裂） | 租约表（§5.4）+ `sql`/`json_codec` 双向编解码 + 对拍全绿为门禁 |
| **`.git` 对象库再次损坏（历史已两次，不可恢复）** | 低 | **极高**（同时损失三批） | 每子批 tag + `git bundle`；W4-00 项 10 |
| **`git status`/暂存区交叉污染** | 高（历史实测） | 中 | 三独立 worktree + `git commit -- <pathspec>`（禁整仓 add） |
| **构建资源争用（Gradle 锁 / KSP 缓存 / ctest 假失败）** | 高（历史实测） | 中 | 构建令牌（§4.1）+ ctest 单进程直跑复核 |
| **清理 worktree 时 `Remove-Item -Recurse` 穿透 `node_modules` junction 删主仓内容** | 中（历史实测发生） | 高 | 用 `scripts/w4/remove-worktrees.ps1`（先探测 reparse point，`cmd /c rmdir`） |
| **某域"以为搬完了其实还有写者" ⇒ 关闭即丢数据** | 中 | 高 | 三闸门：批内写者穷尽扫描 + `noteClosedWrite` 检测零命中 + w3-13 前的"先禁用 + 完整业务周期观察" |
| **失败信封回退臂产生稳态写入** | 中 | 中 | GateTest 双实现逐位一致；高频域改 `executeRaw` 三态（业务拒绝不进写路径）——W4-D 债务项 |
| **并行批的未完成符号卡死共享模块编译** | 中 | 中 | 三独立 worktree 天然隔离；收口时在干净检出复验（§4.3 第 5 条） |
| **detekt baseline 被"重建"偷偷扩大** | 中 | 中 | 13.2 只缩不增 + 计数守卫 + 收口人复核（重建物必须按白名单过滤后装回） |
| **WS-5b 漏写 Room Migration / schema 未更新 ⇒ 存档损坏** | 中 | **极高** | W4-C §2.3.3 R1–R3 清单硬约束 + Migration 集成测试为门禁；**改 `@Entity` 前先读 `rules/database-migration.md`**；禁止 `ALTER TABLE DROP COLUMN` |
| **WS-5b 地形段每旬进入镜像载荷 ⇒ 性能回归** | 中 | 中 | 地形只在生成/回填时标脏一次；加"稳态窗口镜像体积"用例（对齐 `ReverseChannelVolumeProfileTest` 方法学） |

---

## 11. 未来场景推演（≥6 个月档）

| 维度 | 推演 | 结论 |
|---|---|---|
| **规模增长** | 域/子系统数量增长：W4-00 的"每批一个 closures 文件 + 一个 catalog 文件 + 一个 dispatch 文件 + 一个 tests.cmake"模式**可线性扩展**到 N 批并行（新增一批 = 新增四个文件 + 一行聚合），无需再改任何共享文件 | 方案随规模线性可控 |
| **生命周期** | 构建/重启/清缓存：构建令牌与 worktree 是**纯外部工具**，不进构建产物、不进存档；生成物重生成保证"干净检出必可编译" | 全生命周期一致 |
| **平台扩张（iOS）** | 本波不引入任何 Android 独占 API 到 core；`game-core` 仍零 Android 依赖（新增事务头均为纯 C++20）；WS-5b 的地形段落在 C++ 状态模型内，iOS 侧复用同一协议 | iOS 可移植性净收益 |
| **运营演进** | 6 个月内的数值/活动调整不涉及本波（本波只改"谁写状态"）；WS-5b 的 `mapGenVersion` 是**版本戳而非配置项**，运营期不需要发版即可新增地图 | 无需发版 |
| **兼容回退** | 三层回退：① 单域回退 `ReverseChannelPolicy.reopenDomain(domain)`（一行）；② native 不可用 → 回退臂（各域保留）；③ 批次级回退 → `archive/w4-*` tag + bundle | 可回退，不需发版 |

---

## 12. 技术债与偿还计划

| 债项 | 产生原因（为何本波不全做） | 偿还时机（可判断的触发条件） |
|---|---|---|
| 失败信封回退臂仍可能写状态 | 双实现一致契约（GateTest）已保证，结构性禁止需逐域三态化 | 该域搬迁批内改 `executeRaw` 三态；W4-D 汇总核对 |
| `PresentationRandom` 跨会话同构未定 | 属产品口径（表现类随机是否需跨会话一致） | 用户拍板后单独立项。**⚠️ 2026-09-15 实测补充（§2.68 复验）**：`PresentationRandom.seedFromWorld(mapSeed)` **全仓零调用**（生产与测试均无；该方法自 `85498c4c3` 引入起即无调用者）⇒ 表现流种子**恒为编译期常量 `DEFAULT_SEED`**，KDoc 所述"种子由 `mapSeed` 派生（同会话内可复现）"**与实现不符**。**拍板前必须先定"表现流是否按存档播种"**——否则"跨会话是否同构"没有基准 |
| WS-1 阶段 3（列级 delta / 二进制通道 + `dirty_tracker` 列级写屏障） | 145+ 列写点回归面，需协议 v2 设计文档先行 | ✅ **已决策不做（2026-09-15，用户拍板"按桌面 Release 数据决策"）**：实测 ~0.10 ms/弟子/旬（7.9ms@100 / 100.9ms@1000）+ **玩家实际规模 ≈100 弟子** + 每旬镜像在**引擎后台协程**（`GameEngineCoreAuthoritativeOps.kt:63`，不占渲染线程）⇒ 占旬间隔 **0.8%**（中端机 2.5× 估算 ≈2%，约 5 倍余量），**无瓶颈**。**再评估阈值（可机测）**：实际规模 **>400 弟子** 或 D1 埋点真机每旬镜像 **>100ms**（观测 = W4-D/D1 既有埋点，零额外成本）。**同时不做**非协议微优化（空闲 3.8ms@100；任何"跳过序列化"优化都需引入写屏障 = §2.34 已摘除的风险面） |
| WS-5b 地形 2 字段不在全状态对拍面内 | `DiffSurfaceAssertion.kt:76-77` 把 `terrainTiles` / `mapGenVersion` 列入镜像生成字段排除（harness 的 Kotlin 侧不跑 boot 回填 ⇒ 比对必假红，当初以排除代替对齐） | **已拍板并入 W4-D/D3（2026-09-15）**——见 §8 D3 并入项；届时 harness 与生产口径统一，两字段按普通字段参与对拍 |
| 每旬弟子全脏的镜像成本受"全量实体 JSON 序列化"支配 | 协议形状决定，非通道实现可消（`diffToJson` 恒做一次全状态全量序列化 + 缓存树深比较 ⇒ 脏标记只减"发出多少"不减"算多少"） | 同上——**在真实规模（≈100 弟子）下不构成瓶颈，本波不偿还**；规模突破 >400 弟子阈值时随 WS-1 阶段 3 一并立项 |
| w3-11 的兑换码（`RedeemCodeService`）登记不下沉 | C++ 无物品随机生成器（`EquipmentDatabase.generateRandom` 无对应物），非分区随机源不可逐位复刻 | 触发条件 = C++ 侧具备物品随机生成器（模板 codegen 下沉）后重议 |
| `id=""` 镜像生成字段按 id 去重时坍缩（招募候选） | 既有 AUTHORITATIVE 基线行为，本波零行为变更不修复 | 触发条件 = 跨年手动招募池容量出现可复现的玩家可见问题 |
| 三处顶层可变 `xxxRngManager` 的"同族"守卫白名单 | W4-C 收敛三处后，`RngEngineIsolationGuardTest` 白名单应同步缩至仅 `AISectDiscipleManager`（豁免理由：AI 随机源**解析器**） | W4-C 交付时同批缩白名单（守卫只缩不增） |
| **`RngEngineIsolationGuardTest` 白名单无计数断言** | 白名单是 `Map<String,String>`，"只缩不增"仅靠注释纪律 ⇒ 新豁免无法被机器拦下 | 触发条件 = W4-C 收敛三处后（此时白名单应恰为 1 条）⇒ 同批把条目数落成**显式计数断言**（与 `detekt-baseline-count.guard` 同款题型） |
| **随机源守卫的机器可判定面不完整** | `RngSourceGuardTest` ② 类正则不匹配 `Random.nextInt/nextDouble` ⇒ 该类随机点（`DiscipleChatDialog` 5 处）不受闸门约束 | 触发条件 = W4-A 治理 `DiscipleChatDialog` 时**同批扩展正则**（先扩面再治理，否则改登记值即等于改守卫） |
| **`GameEngineSectLevelOps.kt:210` 的输入侧随机流入 C++ 事务** | `bloodMaterials.random()`（全局）经 `prepared` 传入 `SECT_LEVEL_CLAIM_TX` ⇒ C++ 实际发放内容不可复现；"native 臂零 RNG"表述掩盖了它 | 触发条件 = W4-B 处置宗门领奖路径时同批分区化（与 Jade 环境缺陷同域） |

### 12.1 YAGNI 反向检查（每个新抽象是否都有当前生产消费者）

| 新抽象 | 当前消费者 | 判定 |
|---|---|---|
| `scripts/action-catalog/w4{a,b,c}.mjs` | 三批各自的 ActionId 新增（W4-A/B/C 都必增事务） | ✅ 保留 |
| `src/dispatch_w4{a,b,c}.cpp` | 三批各自的 handler（同上） | ✅ 保留 |
| `test/w4{a,b,c}_tests.cmake` | 三批各自的 GTest 源清单（同上） | ✅ 保留 |
| `reversechannel/W4{A,B,C}ChannelClosures.kt` | 三批各自的"关闭该域"动作（w3 §2 第 5 步是每批必做项） | ✅ 保留 |
| `test/dispatch_guard_test.cpp` | W4-00 自身 + 三批新增区间的持续校验；对治已发生的"区间吞动作号"缺陷 | ✅ 保留 |
| `scripts/w4/build-token.ps1` | 三批并行的构建互斥（findings 记录的 Gradle 锁/KSP 损坏/ctest 假失败） | ✅ 保留 |
| `scripts/w4/setup-worktrees.ps1` / `remove-worktrees.ps1` | 三工作树的建立与清理（清理脚本对治已发生的 junction 穿透删除事故） | ✅ 保留 |
| `docs/parallel-batches-w4/protocol-lease.md` | §5.4 的租约登记（WS-5b 必改 `models.h`，属真实争用面） | ✅ 保留（**由 W4-00 创建**） |

> **本方案无"为未来臆造"的抽象**：8 个新抽象全部有当前生产消费者；无消费者的候选（如"通用多批并行框架"）已主动剔除。

### 12.2 rules/ 交叉核对（触碰的规范文件）

| 规范文件 | 关系 | 处置 |
|---|---|---|
| `rules/database-migration.md` | **W4-C 必改**：WS-5b 新增 Room 列 + `@Database` 50→51 | 扩充"协议版本戳迁移判定口径 + C++/Kotlin 字段同步清单"（W4-C §2.3.3 R9） |
| `rules/cpp-priority.md` | 全波遵循：新增/修改引擎逻辑一律 C++；`*_tx.h` 纯 C++20、零 Android 依赖；静态数据走 codegen | 无冲突（本波所有新增逻辑均在 `gamecore/`） |
| `rules/code-quality.md`（跨平台章节） | 新增平台读数（墙钟/内存压力）走参数注入；iOS 对等 | 无冲突，且是净收益（W4-B §7） |
| `rules/design-plan-review.md` | 本方案按其八节自检 | 已逐项落实（§11/§12/§13/§12.1/§12.2 + 三个批次文档） |
| `rules/static-resources.md` | 本波不新增精灵/图集 | 不适用 |
| `rules/economy-design.md` | W4-B 触及玉符/行商/邮件附件 | 已在 W4-B §3.3 列"源与汇"分析；**本波不改数值** |
| `rules/commercialization.md` / `social-system.md` / `data-analytics.md` | 本波不新增付费点位/社交/埋点 | 不适用 |
| `rules/expansion-playbook.md` | 本波不新增玩法系统（WS-5b 是协议/生命周期改造） | 不适用；但 WS-5b 的"存档格式变更"已按 Migration 规则处置 |
| `rules/renderer-feature-checklist.md` | WS-5b 只改**数据**不改**渲染特性**（地形绘制路径不变，`island_cliff` 双路径不受影响） | 不适用；已在 W4-C §2.3.1 核实"崖壁换代不涉瓦片数据" |
| `CLAUDE.md` 13.3 PR 审查清单 | 各批逐项核对（`!!`/异常/死亡标记/物品统一入口/溢出语义类别/玉符收敛/精灵注册等） | 已分散写入三个批次文档的 §4 红线与 §5.4 对抗性审查要点 |

---

## 13. 索引

- 批次方案：[batch-W4A 弟子与建设](batch-W4A-disciple-building.md)｜[batch-W4B 内政与经济运营](batch-W4B-court-economy.md)｜[batch-W4C 战斗与世界协议](batch-W4C-battle-world.md)
- 上游计划：[parallel-batches-w3](../parallel-batches-w3/README.md)（w3-01…w3-13）｜[parallel-batches-w2](../parallel-batches-w2/README.md)（协作协议来源）｜[non-parallel-work](../parallel-batches-w2/non-parallel-work.md)
- 权威 ADR：[reverse-channel-elimination](../adr/reverse-channel-elimination.md)｜[rng-determinism-remediation](../adr/rng-determinism-remediation.md)｜[cpp-engine-migration](../adr/cpp-engine-migration.md)
- 事实基线：[handover](../cpp-migration-handover-m0.md)｜[ui-read-surface](../ui-read-surface.md)｜`findings.md`（坑与教训权威落档处）

---

## 14. 盲区自查与完善建议

> 立场切换：以下逐维度自查本方案**没有考虑到什么**。带「已回写」的条目已并入正文对应章节。

| # | 盲点 / 未验证假设 | 潜在影响 | 处置 |
|---|---|---|---|
| 1 | **"三个批次"是否真能同时开工？** | 若 W4-00 未完成就开工，三批会在 `execute_dispatch.cpp` 等处撞车 | ✅ 已回写 §2.2/§2.3/§3：W4-00 是三批的**硬前置**，且给出"出现在冻结文件 diff 中即打回"的机器可判据 |
| 2 | **用户的"三个批次"是否包含汇流项？** | 强行把 w3-11 塞进任一批会破坏并行性；不塞又"没覆盖全部剩余工作" | ✅ 已回写 §1.2/§1.3/§8/§9：明确"3 并行批 + 1 汇流波 + 非并行轨"三段式，并给图论证据（§2.1）与两条**被否**的替代切法理由 |
| 3 | **WS-5b 的"存档为 Kotlin kotlinx ProtoBuf"前提是否成立？** | 若不成立，地形入档路径完全不同 | ✅ **已核实（VERIFIED）**：断言成立，且结论更强——**地形入档必须在 Kotlin `GameData` 加 `@ProtoNumber`/`@ColumnInfo` 字段 + `@Database(version)` 50→51 + `MIGRATION_50_51`**（`@Transient` + heavy_data 路线会让**云档丢地形**）。已回写 §1.1 核实块 + W4-C §2.3.2/§2.3.3/§4 |
| 4 | **w3-09 是否会触碰 `models.h`（`GridBuildingData` 槽位字段）？** | 会与 W4-C 的 WS-5b 争用协议面 | ✅ **已核实**：w3 十二批**无一批需要改 `models.h`/`json_codec.cpp`**——w3 §3 的原子变更集是"五件套"（不含 models.h），14 个 `*_tx.h` 只是**只读 include**；w3-01 的新发现写者 `replaceManual` 实测只写既有面（`manualStacks`/`manualInstances`/`manualProficiencies`/`manualIds`/`storageBagItems`）⇒ 无需扩协议。仍保留租约（C→A→B）作为兜底，并维持 w3-09 的"C++ 侧槽位表承载"默认路线 |
| 5 | **RNG 阶段 3 是否真的需要 ActionId + C++ 事务？** | 若只是"改传显式 `PresentationRandom`"，则 1850–1859 两段为空置 | ✅ **已核实**：ADR **未要求** ActionId + C++ 事务（落地形态 = 按调用点粒度下沉；硬要求只有 `R1` 分区抽取）⇒ 两段标为**条件段**；两批开工第一步先做**形参化/分区化**，仅在确认"必须由 C++ 事务签发结果"时才启用。已回写 §1.1 修正块 + W4-A §2.3 A5 + W4-C §2.4 C7 |
| 5b | **随机源守卫能否覆盖阶段 3 的目标？** | 🔴 **不能**——`RngSourceGuardTest.kt:120` 的 ② 类正则不匹配 `Random.nextInt/nextDouble`，`DiscipleChatDialog` 的 5 个活随机点机器不可见，"只缩不增"纪律对它无效 | ✅ **已回写 §1.1（新增守卫盲区工作项，归 W4-A）+ W4-A §2.3 A5**：先扩展机器可判定面再治理，否则改登记值就等于改守卫 |
| 6 | **三处 `xxxRngManager` 收敛会不会改变行为？** | 若解析器在双引擎同进程场景下已按"后构造者覆写"运行，改形参必传会改变**测试夹具**的可见行为 | ✅ 已回写 §12 债表：改形参必传 = 从"串流"变"隔离"（生产单引擎下行为不变），须以 `RngEngineIsolationGuardTest` 白名单同步缩 + 全量对拍复验 |
| 7 | **并行批是否会造成测试墙钟爆炸？** | 三批各自跑全量引擎测试（3281 用例）≈ 单批成本 ×3；且重活必须串行（令牌） | ✅ 已回写 §4.1/§7：重活**串行令牌化**；轻活（单模块编译）不取令牌；批内按子批推进（一个子批一次全量，而非每次编辑） |
| 8 | **`stateStore.update` 稳态零写入断言（w3-13 的长期 CI 门禁）在 W4 期间是否开启？** | 若三批期间就开为硬门禁，会因为其余域仍有稳态写者而全红（无法推进） | ✅ 已回写 §8 D4：**观察模式**（命中即告警不阻断）→ w3-13 后转硬门禁 |
| 9 | **文档口径漂移（handover §7 已实测 31 FALSE / 82 STALE）** | 三批各自手抄数字会制造新的漂移 | ✅ 已回写 §4.5/§4.6：文档共享面收口人独占；ActionId 计数一律从 `gen-action-ids.mjs` 实跑取值，文档禁止手抄 |
| 10 | **真机验证批与 W4 的时序** | 若 W4-D 先删通道，真机 E3/N5 就失去"关闭前基线" | ✅ 已回写 §8 D1/§9：batch-22a 埋点提到 D1（先于 D4），并明确"设备需在 W4-A/B/C 期间就绪" |
| 11 | **非功能属性（性能 / 功耗）** | 本波不引入新的每帧路径：新增事务均在用户操作面或既有结算点；WS-5b 的地形入档会增加存档体积 | ✅ 已回写 §5.1/§12：WS-5b 用 RLE 仅存储编码（预期 16384 整数段压至 KB 级）；内存/协议面保持 flat IntArray 单一表示不变 |
| 12 | **隐私合规** | 本波不新增 SDK / 权限 / 网络请求 / 数据收集 | ✅ 无影响，显式声明：**不触碰 `PrivacyConsentScreen.kt` 与 `docs/index.html`** |
| 13 | **`FakeAtomicStateStore` 环境缺陷会不会污染三批的 GateTest？** | 该缺陷（事务缓冲与部分字段不互通）会让新写的 GateTest 出现"诡异失败" | ✅ 已回写 §1.1：列为 W4-B 的独立工作项（同域 = 玉符）；其余批遇到时按 "夹具播种走 `store.update {}` 而非 `.value =`" 的既有口径规避 |
| 14 | **"每批可独立验收"与"关闭动作"的耦合** | 关闭某域需要该域所有子批都完成；半途关闭 = 丢数据 | ✅ 已回写 §7.1 第 7/8 条与 §1.2：关闭动作**逐子批**执行（每个 w3 子批只关自己那部分单元），并强制"关闭域写入检测零命中" |
| 15 | **"18+11+5 处零调用者站点"没有分项清单** | 死代码清零**无验收基准**，无法证明"清零" | ✅ 已回写 §8 D5①：**先补清单再删**（用 `git grep` 产出"生产调用 0 / 测试引用 n"三份清单），否则该项不可派工 |
| 16 | **`RngEngineIsolationGuardTest` 的白名单是 `Map<String,String>`，没有计数断言** | "白名单只缩不增"**只是注释纪律**——手工加一条豁免不会被机器拦下 | ✅ 已回写 §12 债务表（偿还条件 = 白名单条目数落成显式计数断言，与 `detekt-baseline-count.guard` 同款题型） |
| 17 | **`ReverseChannelPolicy` 本体将被 w3-13 删除，而 WS-5b 必须往里加分类条目** | 新增字段的分类是**临时性**的；若 WS-5b 依赖"反向回导承载地形"会在 D4 后失效 | ✅ 已回写 W4-C §2.3.3 R7：地形段与 `mapGenVersion` 登记为 **CLOSED**（`LOAD_BOOT` 类写者，随 `importToNative` 全量导入吸收），**不依赖反向通道** |
| 18 | **`SaveFacadeImpl.kt`（真正的存档快照实现，122L）归 W4-B，而 WS-5b 需要"存档携带地形段"** | 跨批冲突隐患 | ✅ 已回写 W4-C §2.3.3 R6：走"`GameData` 新字段"路线则 `SaveFacadeImpl` **无需改动**（字段经 `stateStore.gameDataSnapshot` 自动携带）；确需改 ⇒ **停下找收口人**，不得自行修改另一批的文件 |
| 19 | **仓库处于半打包损坏态**（3 个悬空 `archive/*` tag + 孤儿 pack 索引 + 截断临时 pack + 坏 reflog；4590 个松散对象、`packs: 0`、10MB 垃圾） | ① `git bundle create --all` **直接失败**；② 仓库大且慢；③ 是第三次数据事故的信号 | ✅ **已根治（§2.66 仓库对象库整理批）**：先做**可恢复性实证**（bundle → `git clone` 到临时目录 → HEAD/tree/追踪文件数三项一致 + 克隆内 fsck 零错误），再删死 tag / 外科式清 2 行坏 reflog（**不用 `reflog expire --all`**）/ 删孤儿 idx 与截断 pack / 过期 commit-graph，最后 `git repack -a -d`（**刻意不用 `-A`、不跑 `gc`** ⇒ 6 个悬空对象**不 prune**）。结果：`fsck` **6 error + 2 warning → 0 + 0**、松散 **4590 → 70**、`in-pack 4520`、`packs 1`、`garbage 0`、对象总数 **4590 不变**、HEAD/tree/文件数**逐项一致**、`--all` **恢复可用**。详见 handover §2.66 |

### 14.1 对用户原始指令的补充与完善（明确超出原指令的部分）

| # | 原指令 | 本方案的补充 | 理由 |
|---|---|---|---|
| 1 | "拆分出三个可并行实施的批次" | **增加"并行前置批 W4-00"** | 现状有 6 处必然冲突的共享文件，"三批并行"在结构上不成立（§2.2） |
| 2 | 同上 | **增加"汇流波 W4-D"** | w3-11 是 B×C 的 join 点、w3-13 必须最后、batch-22a 与 w3-13 争用同一文件（§1.2/§2.1） |
| 3 | 同上 | **增加"非并行轨"** | 真机验证 / WS-4 / WS-1 阶段 3 / 两项待拍板**不可派工**，混进批次只会做废（§9） |
| 4 | "独享文件" | **提升为"结构性独享"**：切分 6 处共享面 + 冻结清单 + 租约表 | "靠纪律避冲突"在本仓历史上已被证伪（静默回退 / theirs 覆盖 / 整仓 add） |
| 5 | "工作区不可干扰" | **细化为三层隔离**：worktree（源码）+ 构建令牌（Gradle/CTest）+ 产物路径按工作树注入 | 源码隔离不足以避免构建期互踩（Gradle `classes.jar` 锁、KSP 缓存损坏、ctest 假失败） |
| 6 | （未提及） | **备份纪律**：每子批 tag + `git bundle` | 本仓 `.git` 对象库曾两次被破坏且历史不可恢复 |
| 7 | （未提及） | **每批的"完成定义"包含关闭动作 + 检测零命中** | 否则通道永远删不掉（w3-13 前置无法达成） |
| 8 | （未提及） | **"被否的替代切法"显式登记**（§2.1 注、§14 #2） | 便于后续复议时有据可依 |
| 9 | （未提及） | **条件段与 UNVERIFIED 项显式标注**（RNG 阶段 3 段号 / WS-5b 存档前提） | 诚实口径：不把未验证假设写成事实 |
| 10 | （未提及） | **文档口径纪律**：数字从生成器实跑取值；共享文档收口人独占 | handover §7 已实测 31 条 FALSE / 82 条 STALE |
