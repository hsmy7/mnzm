# Batch-20：秘境平台段 + 攻宗 / 执法 / 战利品残余下沉 C++

| 项 | 内容 |
|---|---|
| 批次 | 20 ｜ 并行组 D（独占战斗/秘境/执法文件族） |
| 模块 | C++（`system/secret_realm_session.h` 扩展 + `system/sect_battle.h` 等）+ Kotlin（`GameEngineSecretRealmOps.kt` / `GameEngineBattleOps.kt` / `LawEnforcement*.kt` / `AISectDiscipleManager.kt` / `GameEngineWarRewardOps.kt`） |
| 性质 | WS-2 规模下沉：含真实宗门战与执法事务；**可二分**（见 §9） |
| 来源 | [ui-read-surface](../ui-read-surface.md) §4.1「秘境 —— 平台段保留评估」+「月年编排 —— 天劫」+ 战斗/执法残余 |
| 预分配 | handover **§2.51**；**ActionId 段 1710–1729** |
| 前置 | S6（秘境交互会话）/ S8（AI 兽战）/ batch-09（外交）已下沉——**不得回退** |

## 1. 范围（审计后定稿）

| # | 族 | 入口 | 文件 |
|---|---|---|---|
| 1 | **秘境平台段** | `startSecretRealmExploration`（换岗/到期守卫）/ `autoAssignSecretRealmTeam` / `continueSecretRealmExploration` / `pauseForSecretRealm` / `resumeFromSecretRealm` / `renewSecretRealmPauseLease` | `GameEngineSecretRealmOps.kt`（8 处 update） |
| 2 | **攻宗** | `attackSect(sectId, attackSlots)` | `GameEngineBattleOps.kt`（6 处 update） |
| 3 | **执法/偷盗** | `LawEnforcementTheftTxOps.kt`（7）/ `LawEnforcementProcessor.kt`（5） | 执法堂事务 |
| 4 | **战利品发放** | `GameEngineWarRewardOps.kt` | 攻宗战利品入仓 |
| 5 | **AI 弟子参战准备** | `AISectDiscipleManager.prepareDisciplesForBattle` | 13.3 红线项（见 §2） |

**Out-of-scope**：
- 月结内的 AI 兽战 / 洞天 AI（S8 已下沉 `ai_sect_ops.h`）——不动。
- 天劫（`HeavenlyTrial*`）—— 若审计确认其 UI 面写者独立于上述文件，**登记 `batch-23`**，不在本批扩范围。
- 战报**展示重建**（`recordPlayerBattle` / `buildSummaryMessage`）—— 平台/UI 效应，留 Kotlin（S5/S6 口径）。

## 2. 写者审计（第 1 步）

产出「入口 → 判定序 → 触碰字段 → RNG 面 → 残差归属」表进 §2.51。**重点**：

1. 🔴 **13.3 红线（AI 弟子参战）**：任何涉及 AI 弟子参战的战斗路径**必须**调用 `AISectDiscipleManager.prepareDisciplesForBattle()` 生成模拟装备/功法，
   **禁止传 `emptyMap()` 或自行构建装备映射**；且 **AI 弟子不吃丹药、无血炼**。C++ 侧已有等价物（`ai_sect_ops.h:aiPrepareDisciplesForBattle`）——本批复用勿重写。
2. **秘境平台段与 S6 会话域的边界**：S6 已下沉 start/choose/end 会话与战斗链；
   本批只收 **Kotlin 侧仍直改的平台段**（换岗、到期守卫、暂停/恢复租约、自动编队）。**先读 S6 接线点，避免双执行**。
3. **攻宗战斗**：RNG 面在 `BATTLE` 分区；与 batch-13（世界关卡）同族但**文件不重叠**。
   同样须先核实战斗执行的 C++ 覆盖面（batch-13 §2 的 a/b/c 判据适用），按结论裁剪为"全量下沉"或"只下沉确定性段（校验/伤亡写回/战利品）"。
4. **执法堂偷盗事务**：handover §2.16/§2.26 记载执法堂/偷盗链已在**月结/旬结域**下沉；本批需区分"月结域已下沉"与"UI 触发面仍直改"两部分，只做后者。
5. **战利品发放**：溢出语义类别（发放类 → 溢出转邮件）+ 来源名必须在 `OverflowMailSender.SOURCE_DISPLAY_NAMES` 内。

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
- 执法/战利品/平台段：逐点标注；零 RNG 的给全分区快照差分。
- **AI 弟子参战准备**若含 `javaShuffle` 类洗牌 —— C++ 侧已有复刻（`ai_sect_ops.h`），**复用勿重写**。

## 7. 验收

| 项 | 标准 |
|---|---|
| 写者审计 | 全表 + 战斗执行覆盖面结论 + 与 S6/S8 边界声明进 §2.51 |
| C++ 黄金用例 | 秘境平台段（换岗/到期守卫/暂停恢复租约/自动编队）；攻宗（校验链全失败臂零写入 + 伤亡写回 + 战利品）；执法事务；AI 参战准备（装备/功法生成断言 + **不吃丹药/无血炼**断言）；零 RNG 族快照差分；有 RNG 族双运行逐位一致 |
| Kotlin 门控 | 新门控测试：入口 flag OFF / 桥未加载降级 null + 回退臂语义不变；**秘境/战斗/执法域既有测试零改动通过** |
| 门禁 | [README](README.md) §6 ①–⑦ 全绿 |
| 文档 | §2.51 + §3 行 + §4.1 勾销 + 双更新日志 |

## 8. 本批触碰文件声明

- **新**：（视裁剪）`test/secret_realm_platform_test.cpp` / `test/sect_attack_tx_test.cpp` 等；门控测试类。
- **改**：`GameEngineSecretRealmOps.kt`、`GameEngineBattleOps.kt`、`LawEnforcementTheftTxOps.kt`、`LawEnforcementProcessor.kt`、`GameEngineWarRewardOps.kt`、`AISectDiscipleManager.kt`、
  `system/secret_realm_session.h`（追加）、`gen-action-ids.mjs` + 两生成物、`execute_dispatch.cpp`、`test/CMakeLists.txt`、handover、`CHANGELOG.md`、`changelog_entries.json`。
- **不触碰**：`models.h`、`GameStateStoreImpl.kt`、`StateSyncService.kt`、`GameViewModel.kt`、`GameCoreBridge.*`、batch-13/17/18/19 所有权文件。

## 9. 风险与回退

| 风险 | 处置 |
|---|---|
| 范围过大（五族） | **开批第 2 步二分**：`batch-20a 秘境平台段`（§2.51）与 `batch-20b 攻宗/执法/战利品`（§2.51b）；优先做 **20a**（与 S6 连续性最强，风险最低） |
| AI 弟子参战路径违规（空装备映射） | 13.3 红线：必须走 `prepareDisciplesForBattle` 等价物；GTest 加"装备非空 + 无丹药效果"断言 |
| 与 S6/S8 双执行 | 先读 S6 接线点；native 成功只跑残差；Kotlin 门控测试覆盖 |
| 战斗执行未完全 C++ 化 | 按 batch-13 §2 的 a/b/c 判据裁剪并在 PR 声明，**禁止半吊子混合态** |
