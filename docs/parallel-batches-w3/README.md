# 反向通道根治批（W3）总览——UI 操作面收尾 → 反向通道删除

> 性质：**长期主轴收尾计划**（唯一目标 = 删除反向增量通道，使数据流单向：UI → C++ 事务 → Kotlin 只读镜像）。
> 依据：[ADR reverse-channel-elimination](../adr/reverse-channel-elimination.md)（方案权威）+ [handover §2.53](../cpp-migration-handover-m0.md)（batch-21 审计实况）+ [ui-read-surface §4.4](../ui-read-surface.md)（逐域残余写者清单）。
> 前置：batch-21（`24c429d`）已交付逐域关闭机制（`ReverseChannelPolicy` + 双端闸门 + 逐域回滚 + 关闭域写入检测 + 穷尽分类守卫）。

---

## 0. 为什么需要这个计划（一句话）

反向通道是"双实现并行期"的兼容设施。batch-21 穷尽审计（288 写入点）证明：**只要还有 1 个 Kotlin 稳态写者，关闭该域就是数据丢失缺陷**；实测 **14 个域无一可关**（弟子通道 46 站点 / 9 类集合 82 站点 / 64 个 gameData 字段）。
⇒ **删除通道的唯一路径 = 把剩余稳态写者逐域搬进 C++**。本计划把这件工作拆成可独立交付、可独立验收的 13 个批次，并在最后一批删除通道本体。

## 1. 批次总览

| # | 批次 | 域 | 主要稳态写者（审计证据见 §4.4） | 预估 ActionId 段 | 依赖 |
|---|---|---|---|---|---|
| w3-01 | 弟子操作面 | 弟子管理 | `GameEngineCoordination.kt:99/:120/:138`（属性/改名/类型）、`DiscipleFacadeImpl战斗Ops2.kt:93/:119/:138/:157/:271`（赏赐/服药）、`DiscipleStatusService.kt:225/:279/:373`（槽位状态派生）、`DiscipleSlotManager.kt:59`、`DiscipleLifecycleNativeTx.kt:132`、`DiscipleSlotCleanup.kt:120` | 1740–1749 | — |
| w3-02 | 弟子生命周期第二波 | 弟子生命周期 | `GameEngine.kt:277/:306`（婚姻提议审批/拒绝，**native 事务已就绪待接线**）、`DiscipleLifecycleProcessor.kt:489`（槽位清理双路）、`DiscipleLifecycleManager.kt:100/:121`（lifeEvent 补写） | 1750–1759 | w3-01 |
| w3-03 | 灵矿/住所/巡逻自愈 | 巡逻 | `SpiritMineViewModel.kt:89/:147/:183/:252`、`GameEnginePatrolOps.kt:49` | 1760–1765 | — |
| w3-04 | 玉符运行时 | 月年编排（玉符） | `JadeSymbolService.kt:192/:220/:279/:326/:338/:374/:383`（循环钩子累加/跨天重置/checkpoint） | 1766–1769 | — |
| w3-05 | 邮件附件 + 行商刷新 | 库存/月年编排 | `MailAttachmentDistributeOps.kt:124/:183`、`MerchantAndRecruitService.kt:65/:322` | 1770–1779 | — |
| w3-06 | 战斗/探索残差 | 战斗/探索 | `CombatService.kt:78`、`GameEngineWorldBattleOps.kt:188/:288`、`GameEngineExplorationNativeOps.kt:134` | 1780–1789 | — |
| w3-07 | 宗门战战后段 | 战斗 | `GameEngineBattleOps.kt:66/:274/:339/:366` | 1790–1799 | w3-06 |
| w3-08 | 秘境残差 | 秘境 | `GameEngineSecretRealmOps.kt:57`、`SecretRealmNativeOps.kt:100/:263` | 1800–1809 | w3-06 |
| w3-09 | 建筑/道路残差 | 建筑/道路 | `BuildingNativeTx.kt:163`、`BuildingFacadeImpl同步Ops.kt:281`、`BuildingDelegate.kt:145`、`RoadFacadeImpl.kt:41/:141` | 1810–1819 | — |
| w3-10 | 生产残差 | 生产/灵田 | `ProductionProcessorCleaOps3.kt:291`、`ProductionProcessor构筑Ops2.kt:250/:341/:405` | 1820–1829 | — |
| w3-11 | 月年编排残差 | 月年编排 | `GameEngineCoreMonthOps.kt:90` / `YearOps.kt:126`（扇出）、`GameEngineGuideOps.kt:57`、`RedeemCodeService.kt:153/:402` | 1830–1839 | w3-05/w3-06 |
| w3-12 | 外交/自愈/运行态收尾 | 外交 + 存档 | `DiplomacyService.kt:145/:261`、`VassalService.kt:99/:324`、`GameEngineDiplomacyOps.kt:18`、`SaveFacadeImpl.kt:56`、`GameEngineServiceOps.kt:40/:77` | 1840–1849 | w3-03 |
| w3-13 | **通道删除批（终局）** | 全部 | 删除 `captureReverseDirty` / `applyDirtyToNative` / 反向信封 / `ReverseChannelPolicy` + 守卫收口 | 无（只删不增） | w3-01…w3-12 全部合入 |

> ActionId 段为**预分配**（生成器 `scripts/gen-action-ids.mjs` 单一事实源，批内整组提交）；实际用不满则余量留空，禁止跨批复用。

### 1.1 已探明的"低成本起手项"（审计实测，可直接用）

| 项 | 实测事实 | 归属批次 |
|---|---|---|
| 婚姻审批 native 臂**已就绪但未接线** | `DISCIPLE_LIFECYCLE_MARRY_APPROVE=1592` 在 C++ 有 handler（`execute_dispatch.cpp:2617`）+ 3 个 GTest，但 **Kotlin 侧零调用**（`ActionIds.kt:375` 仅声明；`GameEngine.kt:276 approveMarriageProposal` 为纯 `stateStore.update`）⇒ 只需接 native 臂 + GateTest | w3-02 |
| 功法替换**从未登记** | `GameEngineManualOps.kt:137 replaceManual`（活 UI：`DiscipleDetailScreen.kt:954`）无 native 臂、无回退门控 ⇒ 属**新发现稳态写者**（2026-09-15 事实核查） | w3-01 |
| 宗门战战前结算未显式登记 | `GameEngineBattleOps.kt:66 forceSettleDisciplesBeforeBattle`（§2.51b 仅隐含） | w3-07 |
| `updatePatrolConfig` 死 API | `GameEnginePatrolOps.kt:105` 全仓零生产调用方（UI 走 plural 版）⇒ 直接删除该死入口，避免日后接线成丢数据点 | w3-03 顺手 |
| 洞府探索整族死链 | 唯一入口 `CultivationService:178` 零调用（与 `ui-read-surface.md` 旧记载不符）⇒ 删族或重新接线后下沉 | w3-12 顺手 |
| 血炼完成链死路径 | `GameEngineBloodRefinementOps.kt:99` 零生产调用方（仅测试）⇒ 删除死路径后，其字段关闭结论可保留 | w3-01 顺手 |
| `GameData` 零调用方辅助函数 | `withOrganization`/`withWorldMap`/`withExploration`/`totalSpiritStonesSellValue` 全仓零调用 | 清理批 |
| `GameSettingsData.autoSave` 孤儿模型 | 零消费者（全仓无属性/列/DAO 引用该类型）⇒ 可删（原"保留"判定依据不成立） | 清理批 |

## 2. 每批的统一交付形态（与 w2 协议一致 + 行业四阶段切换顺序）

**先分类，再搬迁**（行业"feature parity trap"教训：1:1 复刻会把无人使用的旧行为与历史 workaround 一并固化）——
每批开工先对该域的每个写入点做三分类，只对第 ① 类做搬迁：

| 类 | 判定 | 处置 |
|---|---|---|
| ① **影响模拟结果** | 该写入会成为结算/对拍的输入，或会被 C++ 后续写入覆盖 | **搬迁**：C++ 事务 + Kotlin 回退臂 |
| ② **纯表现** | 只影响 UI 展示（文案/动画/排序缓存），不进 `rngStates`/存档/结算 | **删除或改只读派生**（不进 C++，也不需回导） |
| ③ **平台效应回执** | 平台 IO 的副作用（通知/支付/网络/邮件投递/墙钟读数） | **重构为"命令进 C++ 事务、回执出 C++"**：C++ 签发结果，Kotlin 只执行副作用；平台副作用本身留 Kotlin |

**四阶段切换顺序**（Fowler *Event Interception* 真实案例，业界最贴近本方案的公开范本）：
**① 暗发布 + parity 校验**（新事务并行跑、结果比对，无业务影响）→ **② 拦读**（UI 读 C++ 镜像）
→ **③ 拦写**（新事务成为该域 System of Record，Kotlin 写路径降级为回退臂）→ **④ 搬业务规则**（把残留判定/派生逻辑整体移入 C++）。

随后统一的交付形态：

1. **写者复核**：先对该域做一次 `stateStore.update` 写者扫描，产出「入口 → 三类 → 是否仅回退臂」表（batch-21 已给出基线，批内复核增量）。
2. **C++ 事务**：`system/<domain>_tx.h` 纯函数事务（判定链先行 → 失败零写入 → 零 RNG 或分区 RNG 逐位复刻 → 回执信封）。
3. **Kotlin native 臂**：域门面/引擎入口首行 `if (nativeTx.x()) return`；Kotlin 原路径**保留为回退臂**（native 不可用时仍须正确）。
4. **GateTest**：双实现并行契约对拍（flag OFF vs ON 同一入口 → 结果与状态逐位一致）——即 Scientist 的 `use`/`try` 比对面。
5. **关闭该域**：把该域从 `ReverseChannelPolicy` 的关闭清单中补入（或随通道一起删除）；若该域仍有残余写者，则**不关**并登记。
6. **验收门禁**（缺一不可）：桌面 C++ 全量绿 + `:core:engine` 全量绿（含 47 个 `Diff*` 对拍类）+ 六模块 detekt 绿 + NDK arm64 + lintRelease + 该域 GateTest 绿 + **关闭域写入检测零命中**（运行期无 ERROR 日志）。
7. **文档**：handover 新 §2.x 批次记录 + `ui-read-surface §4.4` 表体滚动更新（该域转为 CLOSED）+ 双更新日志。

## 3. 全局红线（各批共用）

- 🔴 **关闭动作零行为变更**：关闭前后同一存档往返逐字段一致；关闭以"Diff 对拍全绿"为门禁（对拍红 ⇒ 保留传输并登记，**不得改对拍迁就关闭**）。
- 🔴 **回退臂不得退化**：native 不可用时（.so 未加载/初始化失败）该域功能必须与迁移前一致。
- 🔴 **RNG 逐位复刻**：涉及抽取的域必须走 `GameRngManager.getRng(RngPartition.*)` 双端同源（C++ 分区态为唯一真相源）；抽查以"全分区 rngStates 快照差分 + 双运行 JSON 逐位一致"证明。
- 🔴 **平台效应留 Kotlin**：支付/广告/通知/网络投递/TapDB/热状态读取/墙钟等**平台 IO** 不下沉（仅把其**决策结果**作为参数推送给 C++）。
- 🔴 **共享文件整组提交**：`action_ids.h` / `ActionIds.kt` / `execute_dispatch.cpp` / `test/CMakeLists.txt` / `gen-action-ids.mjs` 五件套必须同批同提交（历史事故：跨批共享文件半提交导致干净检出无法编译）。

## 4. 终局（w3-13 通道删除批）验收

> 验收规程对齐 Azure Well-Architected「安全下线」五步（**不以"遥测没流量"为删除依据**——
> 官方明确提醒"没有用户报障"可能只是"没有流量"）：跨完整业务周期验证不活跃 → 删前留快照 →
> 先禁用再删除 → 观察窗内任何意外活动即重置流程 → 清理残留引用。

| 项 | 标准 |
|---|---|
| 前置 | w3-01…w3-12 全部合入 `main`，且 `ReverseChannelPolicy` 关闭清单 = **全部**传输单元（无"在册保留"项） |
| **阶段 A：先禁用（不删）** | 关闭反向发送开关（信封体积 = 0 可观测），代码保留；跑**完整游戏业务周期**（≥1 游戏年 + 离线结算 + 跨旬/跨月 + 存档写读往返），期间**关闭域写入检测零命中**；任何一次命中 ⇒ 重置流程、回滚该域并回到对应 w3 批 |
| **阶段 B：状态指纹零差异** | 禁用前后同一存档 + 同一输入序列（长跑 ≥100 旬）⇒ C++ 导出状态与 Kotlin 镜像**逐字段一致**（同存档同输入 ⇒ 同指纹，行业确定性可复现口径） |
| **阶段 C：删前留快照** | 删除前打归档 tag（可回退点）+ 记录信封体积/耗时基线 |
| 删除面 | `GameStateStoreImpl.captureReverseDirty` / `ReverseDirtyAccumulator`、`StateSyncService.applyDirtyToNative` + 反向信封构建、`GameStateStore.consumeReverseDirty/resetReverseAccumulator/ReverseDirtySnapshot`、`GameCore::applyReverseDirty` + JNI 导出、`ReverseChannelPolicy` 本体 |
| 保留面 | 前向镜像（C++ → Kotlin）+ 全量导入（读档/新档基线）+ 各域回退臂 |
| 验收 | 引擎全量 + 桌面 C++ 全量 + NDK + lint + detekt 全绿；**稳态零写入断言**（AUTHORITATIVE 下 `stateStore.update` 命中即失败）转正式门禁 |
| 观测 | `git grep applyReverseDirty` 归零；六模块 + 桌面 C++ 全绿；长跑日志零 ERROR |
| **防复发（行业"只删写入点不切断写能力，写者会长回来"）** | 镜像侧补**只读契约**（Kotlin 镜像不可写：结构性/编译期约束优先于纪律），并让"稳态零写入断言"进入长期 CI |

## 5. 排期建议

**可并行组**：w3-01/w3-03/w3-05/w3-06/w3-09/w3-10 互不重叠（各自域 + 各自头文件）；
**串行点**：w3-02 ← w3-01；w3-07/w3-08 ← w3-06；w3-11 ← w3-05+w3-06；w3-12 ← w3-03；**w3-13 必须最后**。

## 6. 与既有文档的关系

- 本计划**取代** `parallel-batches-w2/batch-21-reverse-channel-closeout.md` 的"直接关闭"路线（该路线经审计判定前置不成立）；batch-21 已交付的部分（机制 + 可证关闭面）保留。
- `non-parallel-work.md` 中的"UI 操作面逐域下沉"条目**并入本计划**，不再单列。
