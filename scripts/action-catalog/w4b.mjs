#!/usr/bin/env node
/**
 * action-catalog/w4b.mjs — **W4-B 批次独占**的 ActionId 清单（内政与经济运营轴）。
 *
 * ## 所有权（docs/parallel-batches-w4/README.md §3.1 项 1 / §5.1）
 * 🔴 **只有 W4-B 可写本文件**。另两批（W4-A / W4-C）与任何其他工作一律不得改本文件。
 * 新增动作 = 在本文件 `CATALOG` 数组**末尾**追加一行；随后运行
 * `node scripts/gen-action-ids.mjs` 重生成两份产物（`action_ids.h` / `ActionIds.kt`）。
 *
 * ## 预分配段（README §6；段内用不满则余量留空，**禁止跨批复用**）
 *   1760–1765  w3-03 巡逻/住所/矿场自愈（灵矿槽位、矿场自愈）
 *   1766–1769  w3-04 玉符运行时（循环钩子累加/跨天重置/结算，墙钟读数参数化）
 *   1770–1779  w3-05 邮件附件账本 + 行商刷新凭据
 *   1840–1849  w3-12 外交/好感/附庸稳态段 + 存档前自愈 + 检查点重锚 + 内存裁剪命令
 *
 * ## 纪律
 * - id 全局唯一（生成器有重复即断言失败）；**不得**占用他人段（有区间重叠断言）
 * - 条目结构：`{ id: number, name: string, desc: string }`
 * - 追加式改动；改动后必须 `node scripts/gen-action-ids.mjs && git diff --exit-code` 自证无漂移
 * - 平台效应（墙钟/邮件投递/内存压力/存档时机）**只把读数或决策结果作为参数**，副作用留 Kotlin
 */
export const CATALOG = [
  // ── 1760–1765 · w3-03 巡逻/住所/矿场自愈 ──
  // B1 实施口径（2026-09-15）：**零新增 ActionId**——灵矿槽位 UI 直改与矿场自愈
  // 全部复用 batch-12 已就绪的事务面（PATROL_UPDATE_SPIRIT_MINE_SLOTS /
  // PATROL_FIX_SPIRIT_MINE / PATROL_UPDATE_CONFIG，patrol_tx.h 事务 7/8/9），
  // 亲传槽位卸任复用 DISCIPLE_TX_UNASSIGN_SLOT（family=elderDirect）。
  // ViewModel 四处 updateGameData 直改已改走统一 native 面；死 API
  // updatePatrolConfig（单参）/ updatePatrolSlots 已删除。段内余量留空。

  // ── 1766–1769 · w3-04 玉符运行时 ──
  { id: 1766, name: 'JADE_RUNTIME_SETTLE_TX', desc: '玉符结算发放（settleGrants 下沉：整除发放/封顶冻结，回执回写运行时）' },
  { id: 1767, name: 'JADE_RUNTIME_DAY_RESET_TX', desc: '玉符跨天重置/首锚（maybeDayReset 下沉：午夜锚点由 Kotlin 计算传入）' },
  { id: 1768, name: 'JADE_RUNTIME_CHECKPOINT_TX', desc: '玉符 checkpoint（四字段绝对值覆盖写；拿满冻结复用）' },
  { id: 1769, name: 'JADE_RUNTIME_GRANT_AD_TX', desc: '玉符广告发放（grantFromAd 落账段；广告 SDK 平台效应留 Kotlin）' },

  // ── 1770–1779 · w3-05 邮件附件 + 行商刷新 ──
  // （待 W4-B 填充）

  // ── 1840–1849 · w3-12 外交/自愈/运行态 ──
  // （待 W4-B 填充）
];
