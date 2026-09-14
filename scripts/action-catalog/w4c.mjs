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
  // （待 W4-C 填充）

  // ── 1790–1799 · w3-07 宗门战战后段 ──
  // （待 W4-C 填充）

  // ── 1800–1809 · w3-08 秘境残差 ──
  // （待 W4-C 填充）

  // ── 1855–1859 · RNG 阶段 3·战斗侧（条件段） ──
  // （条件启用，默认空置）
];
