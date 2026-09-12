# Batch-20：秘境平台段 + 攻宗 / 执法 / 战利品残余下沉 C++

| 项 | 内容 |
|---|---|
| 批次 | 20 ｜ 并行组 D（独占战斗/秘境/执法文件族） |
| 模块 | C++（`system/secret_realm_session.h` 扩展 + `system/sect_battle.h` 等）+ Kotlin（`GameEngineSecretRealmOps.kt` / `GameEngineBattleOps.kt` / `LawEnforcement*.kt` / `AISectDiscipleManager.kt` / `GameEngineWarRewardOps.kt`） |
| 性质 | WS-2 规模下沉：含真实宗门战与执法事务；**可二分**（见 §9） |
| 来源 | [ui-read-surface](../ui-read-surface.md) §4.1「秘境 —— 平台段保留评估」+「月年编排 —— 天劫」+ 战斗/执法残余 |
| 预分配 | handover **§2.51**（20a 实用 §2.51 / 20b 实用 **§2.51b**）；**ActionId 段 1710–1729**（20a 实用 1710；20b 实用 1711–1712） |
| 前置 | S6（秘境交互会话）/ S8（AI 兽战）/ batch-09（外交）已下沉——**不得回退** |
| **交付状态** | **二分交付完成（2026-09-13）**：**20a ✅**（2026-09-12，handover §2.51——秘境平台段 `continueSecretRealmExploration` 下沉，ActionId 1710）；**20b ✅**（2026-09-13，handover §2.51b——写者审计 + 覆盖面核实后**实测改判批面**：只落两段确定性写回 1711–1712，另三条显式登记不下沉）。**本文件保留为批次设计记录**（下方 §1 表 5 行、§2 第 1/4 点、§7 验收中"AI 参战准备/执法堂"相关行已按实测结论修正） |

## 1. 范围（审计后定稿）

| # | 族 | 入口 | 文件 | 实测裁决（2026-09-13） |
|---|---|---|---|---|
| 1 | **秘境平台段** | `startSecretRealmExploration`（换岗/到期守卫）/ `autoAssignSecretRealmTeam` / `continueSecretRealmExploration` / `pauseForSecretRealm` / `resumeFromSecretRealm` / `renewSecretRealmPauseLease` | `GameEngineSecretRealmOps.kt`（8 处 update） | **20a 已交付**：start/choose/end 已于 S6 下沉；`autoAssign` 为纯只读选择器、pause/resume/renew 为运行时时钟平台残差（S5/S6 口径留 Kotlin）；唯一未下沉写者 `continueSecretRealmExploration` 落 `secret_realm_platform_tx.h`（1710） |
| 2 | **攻宗** | `attackSect(sectId, attackSlots)` | `GameEngineBattleOps.kt`（6 处 update） | **战斗执行覆盖面已在 C++**（见 §2）；20b 只下沉阵亡守军清理（1711）+ 魂魄发放（1712）两段零 RNG 写；奖励生成/奖励入账/战绩记录三条登记不下沉 |
| 3 | **执法/偷盗** | `LawEnforcementTheftTxOps.kt`（7）/ `LawEnforcementProcessor.kt`（5） | 执法堂事务 | ⛔ **实测无 UI 直调写者**——`processSingleDiscipleTheft(id, state)` 调用面全为**结算域钩子**（受控状态变更 / 旬结丹药 / 月结修炼 / 年结生命周期 / 世界关卡战斗），月结·旬结偷盗链早已下沉 `month_settlement.h` / `phase_settlement.h` → **登记不下沉**（见 §2 第 4 点修正） |
| 4 | **战利品发放** | `GameEngineWarRewardOps.kt` | 攻宗战利品入仓 | ⛔ **不下沉（RNG 红线 + 原子性）**——生成族走 `Random.Default` 非分区随机域、入账与生成同一 `stateStore.update` 原子事务；详见 §2 第 1 点修正 |
| 5 | **AI 弟子参战准备** | `AISectDiscipleManager.prepareDisciplesForBattle` | 13.3 红线项（见 §2） | ⛔ **非写者，无下沉对象**——纯只读变换（`List<Disciple> → AIPreparedBattle`）；13.3 红线由 C++ `ai_sect_ops.h aiPrepareDisciplesForBattle` 在既有下沉路径满足（见 §2 第 1 点修正） |

**Out-of-scope**：
- 月结内的 AI 兽战 / 洞天 AI（S8 已下沉 `ai_sect_ops.h`）——不动。
- 天劫（`HeavenlyTrial*`）—— 若审计确认其 UI 面写者独立于上述文件，**登记 `batch-23`**，不在本批扩范围。
- 战报**展示重建**（`recordPlayerBattle` / `buildSummaryMessage`）—— 平台/UI 效应，留 Kotlin（S5/S6 口径）。

## 2. 写者审计（第 1 步）

产出「入口 → 判定序 → 触碰字段 → RNG 面 → 残差归属」表进 §2.51 / §2.51b。**重点（含 2026-09-13 实测修正）**：

1. 🔴 **13.3 红线（AI 弟子参战）——实测修正原口径**：原文要求"任何涉及 AI 弟子参战的战斗路径**必须**调用 `AISectDiscipleManager.prepareDisciplesForBattle()`"。
   **实测：该函数是纯只读变换**（`AISectDiscipleManager.kt:237`，签名 `List<Disciple> → AIPreparedBattle`，零 state 写入）——**它是"参战数据准备"，不是写者，故无下沉对象**；
   且 C++ 等价物 `ai_sect_ops.h:540 aiPrepareDisciplesForBattle` 已在 `exploration_tx.h:373` / `secret_realm_session.h:1008` / `sect_conquest.h:290/328` / `sect_defense_battle.h:551` 内被**已下沉路径**消费 ⇒ **红线已满足**。
   Kotlin 侧 6 个调用点（`EncounterBattleService` / `SecretRealmService` / `AISectBeastAttackProcessor`×3 / `PatrolBattleSystem`×2）**全部位于未下沉域或既有 C++ 等价路径**，无需改动。
2. **秘境平台段与 S6 会话域的边界**：S6 已下沉 start/choose/end 会话与战斗链；
   20a 只收 **Kotlin 侧仍直改的平台段**（换岗、到期守卫、暂停/恢复租约、自动编队）。**已按此交付**。
3. **攻宗战斗**：RNG 面在 `BATTLE` 分区。**覆盖面核实结论（2026-09-13）**：战斗执行**已在 C++**——
   `AISectAttackManager.executeSectBattleCore` 经 `tryExecuteUnifiedNative`（`sect_battle.h executeAiBattle`）、
   占领判定 `computeCanOccupy` 经 `sect_attack_decision.h`、攻击条件经 `nativeCheckAttackConditions`、
   **玩家 Combatant 组装经 `mission_completion.h discipleToCombatant`（实例表语义，`exploration_tx.h:272` 同源先例）**
   ⇒ 无需下沉；实际只落两段 Kotlin 独占的零 RNG 写（阵亡守军清理 + 魂魄发放）。
   **🔴 奖励族不下沉（RNG 红线）**：`generateWarRewards` 的模板抽取走 `templates.random(random)`，而
   `AISectTeamComposer.kt:143/158/171/182/194/208` 六个 `addWar*` 调用点**均未传 `random`** ⇒ 实际消费
   `kotlin.random.Random`（`Random.Default`）**非游戏分区随机域**，C++ 无法逐位复刻；`sectBattleRewardCount`
   的 BATTLE 分区 `nextInt(7)` 被夹在该非分区域中间，拆分即"半吊子混合态" ⇒ **整族留 Kotlin**。
   **🔴 奖励入账不下沉（原子性）**：`occupySectRewards` / `crushSectRewards` 与 `grantWarRewardsInside`
   同一 `stateStore.update` 原子事务，奖励段不可复刻 ⇒ 整段留 Kotlin。
   **🔴 战绩记录不下沉（协议形状）**：`recordSectBattleRecord` 与 `battleLogs`（Kotlin **显示域**，
   `sect_defense_battle.h:33` 口径"不入 C++ 状态"）同一事务，拆出 `sectBattleRecords` 会产生**撕裂事务**。
4. **执法堂偷盗事务——实测修正原口径**：原文要求"区分月结域已下沉与 UI 触发面仍直改两部分，只做后者"。
   **实测：后者的集合为空**——`processSingleDiscipleTheft(id, state)` 的全部调用面均为结算域钩子
   （`DiscipleFacadeImpl:324` 受控状态变更 / `AutoPillService:208` 旬结 / `CultivationSettlement:373` 月结 /
   `DiscipleLifecycleProcessor:478` 年结 / `GameEngineWorldBattleOps:243` 世界关卡战斗），
   `LawEnforcementTheftTxOps.kt` 内 7 个 `stateStore.update` 全部是**这些钩子的实现体自身**（非 UI 直调）
   ⇒ **无 UI 触发面可下沉，登记不下沉**。
5. **战利品发放**：溢出语义类别（发放类 → 溢出转邮件）+ 来源名必须在 `OverflowMailSender.SOURCE_DISPLAY_NAMES` 内。
   **按第 3 点结论整族不下沉**，故本约束不适用（既有 Kotlin 路径已满足）。

## 3. 地基（已就绪）

- **`system/secret_realm_session.h`**（75KB）：S6 会话编排 + 战斗链 + 袋物化 + `discipleRealmName` 等助手。
- **`system/sect_battle.h`** / `sect_conquest.h` / `sect_defense_battle.h`：宗门战既有实现。
- **`system/ai_sect_ops.h`**：`aiPrepareDisciplesForBattle` / `aiCreateBattle` / `aiExecuteVersusBeast` / `aiMarkBeastDefeated`。
- **`system/battle_execution.h`** / `battle_calculator.h` / `battle_ai.h`：战斗执行与计算原语。
- **`system/slot_cleanup.h`**：弟子阵亡后的全槽位清理。

## 4. 实施步骤

1. **写者审计**（§2 表）+ **战斗执行覆盖面核实**（决定裁剪形态）→ PR 显式声明。
2. **C++ 事务**：按族实现（秘境平台段 / 攻宗 / 执法 / 战利品 / AI 参战准备）；失败零写入；RNG 面逐点标注。
3. **协议**：段 1710–1729 → `execute_dispatch.cpp` **按族独立 handler**（勿把四族塞进一个巨型 handler）+ 中央逐行 case。
4. **Kotlin 接线**：五处 native 臂；战报重建/UI 卡片/平台段残差保留 Kotlin。
5. **测试**：见 §7。
6. **文档**：handover §2.51 + §3 行 + §4.1 秘境行勾销 + 双更新日志。

## 5. 文件所有权与冲突面

- **本批独占**：`GameEngineSecretRealmOps.kt`、`GameEngineBattleOps.kt`、`LawEnforcementTheftTxOps.kt`、`LawEnforcementProcessor.kt`、`GameEngineWarRewardOps.kt`、`AISectDiscipleManager.kt`。
- **共享面**：按 [README](README.md) §3.1 原子变更集。
- **禁改**：`models.h`、`GameStateStoreImpl.kt`、`StateSyncService.kt`、`GameViewModel.kt`、`GameCoreBridge.*`、
  S6/S8 已下沉的 C++ 事务（只能追加）、`ai_sect_ops.h` 既有函数。

## 6. RNG 与确定性要求

- 攻宗/秘境战斗 = `BATTLE` 分区：**抽取序必须与 Kotlin 原路径逐位一致**；给「双运行逐位一致」+「终态 `rngStates` 锁定」。
  **实测补充**：攻宗**战斗执行本身已在 C++**（见 §2 第 3 点），故本批不涉抽取序；**奖励生成族因走 `Random.Default` 非分区随机域登记不下沉**，其抽取序不在对拍范围内。
- 执法/战利品/平台段：逐点标注；零 RNG 的给全分区快照差分。**实测**：平台段（20a）与阵亡清理/魂魄发放（20b）**全链零抽取**，已由全分区快照差分 + 双运行逐位一致双证。
- **AI 弟子参战准备**若含 `javaShuffle` 类洗牌 —— C++ 侧已有复刻（`ai_sect_ops.h`），**复用勿重写**；实测该函数为纯只读变换，**无下沉对象**（§2 第 1 点）。

## 7. 验收

| 项 | 标准 | 实测结果 |
|---|---|---|
| 写者审计 | 全表 + 战斗执行覆盖面结论 + 与 S6/S8 边界声明进 §2.51 | ✅ 20a 落 §2.51；20b 落 §2.51b（含**两处口径修正**：AI 参战非写者 / 执法堂无 UI 写者） |
| C++ 黄金用例 | 秘境平台段（换岗/到期守卫/暂停恢复租约/自动编队）；攻宗（校验链全失败臂零写入 + 伤亡写回 + 战利品）；执法事务；AI 参战准备（装备/功法生成断言 + **不吃丹药/无血炼**断言）；零 RNG 族快照差分；有 RNG 族双运行逐位一致 | ✅ 平台段 `secret_realm_platform_tx_test.cpp` **12/12**；攻宗 `sect_attack_tx_test.cpp` **9/9**（阵亡清理 + 魂魄发放 + 空集无操作 + 池/宗门缺失容错 + 零 RNG 快照差分 + 双运行逐位一致 + 信封面）。**"战利品/AI 参战准备/执法事务"三项用例按改成改判不下沉，不再适用**（其语义仍由既有 GTest 覆盖：`sect_attack_decision_test` / `ai_sect_ops_test` / `month_settlement_test`） |
| Kotlin 门控 | 新门控测试：入口 flag OFF / 桥未加载降级 null + 回退臂语义不变；**秘境/战斗/执法域既有测试零改动通过** | ✅ `SecretRealmContinueNativeTxGateTest` 4/4（20a）；20b 两入口为 `GameEngineBattleOps` 私有方法（数据流经 battle 域既有测试覆盖），未新增门控类 |
| 门禁 | [README](README.md) §6 ①–⑦ 全绿 | ⚠️ **部分未达（非本批归属）**：① 桌面 C++ **1284/1284** ✅｜② JNI 重建 ✅｜③ 引擎全量 3237 用例（27 处失败全为并行线在途/预存，**本批 0 归属**）｜④ detekt 触碰面（core:engine）**0 违规** ✅（`feature:game` 6 处为纹理并行线）｜⑤ core:engine 主源+测试源编译 ✅（`feature:game` 被纹理/KTX 在途破损阻断）｜⑥ NDK/lint **未达**（同上阻断）｜⑦ 模块回归未跑（同上）。详见 handover §3 |
| 文档 | §2.51 + §3 行 + §4.1 勾销 + 双更新日志 | ✅ §2.51 + §2.51b + §3 验证行 + ui-read-surface §4.1 + CHANGELOG 两条目 + `changelog_entries.json` 三条玩家向文案 |

## 8. 本批触碰文件声明（实际交付口径）

- **新**：`test/secret_realm_platform_tx_test.cpp`（20a）、`test/sect_attack_tx_test.cpp`（20b）、`include/gamecore/system/secret_realm_platform_tx.h`（20a）、`include/gamecore/system/sect_attack_tx.h`（20b）、`GameEngineSecretRealmNativeOps.kt`（20a 接线落点）、`GameEngineSectAttackNativeOps.kt`（20b）、`SecretRealmContinueNativeTxGateTest.kt`。
- **改**：`GameEngineSecretRealmOps.kt`（20a）、`GameEngineBattleOps.kt`（20b 两入口 native 臂）、`gen-action-ids.mjs` + 两生成物、`execute_dispatch.cpp`、`test/CMakeLists.txt`、handover、`CHANGELOG.md`、`changelog_entries.json`。
- **按改判未触碰**：`LawEnforcementTheftTxOps.kt`、`LawEnforcementProcessor.kt`、`GameEngineWarRewardOps.kt`、`AISectDiscipleManager.kt`（三条登记不下沉 + 一条非写者）。
- **不触碰**：`GameStateStoreImpl.kt`、`StateSyncService.kt`、`GameViewModel.kt`、`GameCoreBridge.*`、batch-13/17/18/19 所有权文件。（注：`models.h` / `json_codec.cpp` 因 batch-12 补齐 `GridBuildingData.sectId` 协议漂移而被触碰——属**平行批的协议变更**，非本批）

## 9. 风险与回退

| 风险 | 处置 | 实测落地 |
|---|---|---|
| 范围过大（五族） | **开批第 2 步二分**：`batch-20a 秘境平台段`（§2.51）与 `batch-20b 攻宗/执法/战利品`（§2.51b）；优先做 **20a**（与 S6 连续性最强，风险最低） | ✅ 已二分交付；20b 经审计进一步收窄为**两段**（1711–1712） |
| AI 弟子参战路径违规（空装备映射） | 13.3 红线：必须走 `prepareDisciplesForBattle` 等价物；GTest 加"装备非空 + 无丹药效果"断言 | ✅ 无需改动——红线由 **C++ `aiPrepareDisciplesForBattle`** 在已下沉路径满足；Kotlin 侧保持原调用面（§2 第 1 点） |
| 与 S6/S8 双执行 | 先读 S6 接线点；native 成功只跑残差；Kotlin 门控测试覆盖 | ✅ 20a 按此接线（native 成功后只跑 gate 释放/关闭邮件/净化后 gate 重建） |
| 战斗执行未完全 C++ 化 | 按 batch-13 §2 的 a/b/c 判据裁剪并在 PR 声明，**禁止半吊子混合态** | ✅ 核实为**已完全 C++ 化**（§2 第 3 点）；剩余奖励族按 RNG 红线**整族**留 Kotlin（非半吊子拆分） |
