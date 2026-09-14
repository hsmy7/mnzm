#!/usr/bin/env node
/**
 * action-catalog/w4c.mjs — **W4-C 批次独占**的 ActionId 清单（战斗与世界协议轴）。
 *
 * ## 所有权（docs/parallel-batches-w4/README.md §3.1 项 1 / §5.1）
 * 🔴 **只有 W4-C 可写本文件**。另两批（W4-A / W4-B）与任何其他工作一律不得改本文件。
 * 新增动作 = 在本文件 `CATALOG` 数组**末尾**追加一行；随后运行
 * `node scripts/gen-action-ids.mjs` 重生成两份产物（`action_ids.h` / `ActionIds.kt`）。
 *
 * ## 预分配段（README §6；段内用不满则余量留空，**禁止跨批复用**）
 *   1780–1789  w3-06 战斗/探索残差（伤亡写回、世界关卡胜败事务、战前结算）
 *   1790–1799  w3-07 宗门战战后段（战前结算）
 *   1800–1809  w3-08 秘境残差（出发换岗、到期兜底关闭）
 *   1855–1859  RNG 阶段 3·战斗侧（`BattleDescriptionGenerator`）— **条件段**：
 *              仅当 ADR 阶段 3 判定"需 C++ 事务签发结果"时启用；默认走分区化，不占段
 *
 * ## 纪律
 * - id 全局唯一（生成器有重复即断言失败）；**不得**占用他人段（有区间重叠断言）
 * - 条目结构：`{ id: number, name: string, desc: string }`
 * - 追加式改动；改动后必须 `node scripts/gen-action-ids.mjs && git diff --exit-code` 自证无漂移
 * - 🔴 **WS-5b（地图冻结）不需要 ActionId**（地形由生命周期触发，非玩家操作）
 *   ⇒ 不得为它在本文件占段；判据见 README §5.3"`execute_dispatch.cpp` 冻结"，
 *   以及 docs/parallel-batches-w4/batch-W4C-battle-world.md §2.3.4
 * - 战利品生成族（`generateWarRewards` 等）走非分区随机源 `Random.Default`
 *   ⇒ 整族**不下沉**，不得为其占段（RNG 红线，batch-20b 登记）
 */
export const CATALOG = [
  // ── 1780–1789 · w3-06 战斗/探索残差 ──
  // C1 实施口径（2026-09-15）：三事务下沉（battle_residual_tx.h）。
  // 伤亡残差（CombatService.processBattleCasualties 阶段 2 写面）与关卡胜利
  // 事务（soulPowers/winAttr/偷盗钩子——C++ 不写 defeated，batch-13 TOCTOU
  // 口径；defeated + battleLogs 残差留 Kotlin）与战前突破结算
  // （forceSettleDisciplesBeforeBattle——限定队伍 id 集，行序 == Kotlin
  // discipleTables.ids 序，BREAKTHROUGH/SYSTEM 分区抽取序逐位不变）。
  // 失败事务 :288 判定为 battleLogs 显示域 + UI 通道，无状态可下沉（登记）。
  { id: 1780, name: 'BATTLE_CASUALTY_SETTLE_TX', desc: '战斗伤亡残差事务（悲痛/标死袋物化/物品清理/槽位清理/幸存者回写，零 RNG）' },
  { id: 1781, name: 'WORLD_VICTORY_REWARDS_TX', desc: '世界关卡胜利事务（TOCTOU 重查 + 魂力 + 确定性 winAttr + 道德偷盗钩子；不写 defeated）' },
  { id: 1782, name: 'BATTLE_PRESETTLE_TX', desc: '战前突破结算（实时突破管线限定队伍 id 集；BREAKTHROUGH/SYSTEM 分区同序）' },

  // ── 1790–1799 · w3-07 宗门战战后段 ──
  // C2 实施口径（2026-09-15）：**全部登记不下沉、零 ActionId 占用**——
  // :274 recordSectBattleRecord（战史 + battleLogs Kotlin 显示域撕裂事务）、
  // :339 occupySectRewards / :366 crushSectRewards（与 grantWarRewardsInside
  // 同一 stateStore.update 原子事务，奖励段不可复刻，原子性不可拆；
  // 依据已存 sect_attack_tx.h KDoc + W4CChannelClosures 证据）。
  // 战前结算（:66）与 C1 的 1782 同一事务界面，不另占号。

  // ── 1800–1809 · w3-08 秘境残差 ──
  // C3 实施口径（2026-09-15）：两事务下沉（secret_realm_residual_tx.h；
  // 出发换岗 = releaseDiscipleToIdleInside 的 GameData 槽位/状态段，
  // includeResidence=false；到期兜底 = rejectIfSecretRealmExpired 快照判定 +
  // closeSecretRealmByExpiry 状态段（secret_realm_settle 复用）+ 草稿信封，
  // 关闭邮件 + gate 释放留 Kotlin（applyExpiryCloseDraft 既有通道）。
  // :263 战报显示域登记不下沉（S6 决策③既有口径）。
  { id: 1800, name: 'SECRET_REALM_START_RELEASE_TX', desc: '秘境出发换岗（11 类槽位清理 + 思过/血炼状态重置 IDLE，零 RNG）' },
  { id: 1801, name: 'SECRET_REALM_EXPIRY_GUARD_TX', desc: '秘境到期兜底（到期判定 + 关闭状态段 + 关闭草稿信封，零 RNG）' },

  // ── 1855–1859 · RNG 阶段 3·战斗侧（条件段） ──
  // （条件启用，默认空置）
];
