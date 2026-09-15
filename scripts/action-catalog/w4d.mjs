#!/usr/bin/env node
/**
 * action-catalog/w4d.mjs — **W4-D 汇流波（串行收口）**的 ActionId 清单。
 *
 * ## 所有权（docs/parallel-batches-w4/README.md §3.1 项 1 / §6）
 * 🔴 **只有 W4-D（收口人）可写本文件**（W4-D 为 A/B/C 合入后的串行汇流波，
 * 与三批并行纪律无关；本文件的加入伴随 gen-action-ids.mjs 的一次性 import 扩展——
 * 该扩展属 W4-D 自身，非三批越界）。
 * 新增动作 = 在本文件 `CATALOG` 数组**末尾**追加一行；随后运行
 * `node scripts/gen-action-ids.mjs` 重生成两份产物（`action_ids.h` / `ActionIds.kt`）。
 *
 * ## 预分配段（README §6；段内用不满则余量留空，**禁止跨批复用**）
 *   1830–1839  w3-11 月年编排残差（引导领奖事务等）
 *   1860–1869  W4-D 机动（harness 对齐相关的必要事务）
 *
 * ## 纪律
 * - id 全局唯一（生成器有重复即断言失败）；**不得**占用他人段（有区间重叠断言）
 * - 条目结构：`{ id: number, name: string, desc: string }`
 * - 追加式改动；改动后必须 `node scripts/gen-action-ids.mjs && git diff --exit-code` 自证无漂移
 * - 🔴 兑换码（`RedeemCodeService`）**登记不下沉**（C++ 无物品随机生成器，RNG 红线，
 *   §2.50/B3 同先例）⇒ 不得为它在本文件占段
 */
export const CATALOG = [
  // ── 1830–1839 · w3-11 月年编排残差 ──
  // D2 实施口径（2026-09-15）：引导领奖事务下沉（guide_reward_tx.h）。
  // 承接 Kotlin 写者 = GameEngineGuideOps.claimGuideReward（guideClaimedRewardIds
  // + 凡品储物袋×2 凭据发放，SYSTEM 分区 2×nextLong 造 UUID——校验链/可行性预检
  // 先行、抽取位其后，双臂抽取序逐位一致）。UI 奖励卡片与溢出抑制语义两臂同形。
  { id: 1830, name: 'GUIDE_REWARD_CLAIM_TX', desc: '引导领奖事务（任务校验链 + 储物袋发放 + 已领标记，凭据类溢出抑制）' },
  // 1831–1839 留空（w3-11 其余扇出项判定结论：附庸年贡/脱离 C++ 已在位——
  // year_settlement.h / month_settlement.h 子事件 12，开臂即双重执行；
  // 兑换码登记不下沉；月/年残留执行器 = 通知/平台效应留 Kotlin）。

  // ── 1860–1869 · W4-D 机动 ──
  // D4 续批实施口径（2026-09-15）：弟子交谈效果写面下沉（chat_effect_tx.h）。
  // 承接 Kotlin 写者 = DiscipleDelegate.applyConversationEffects →
  // updateDisciple（弟子通道最后一个协议列数据丢失风险写者，W4-A·A5 登记
  // "写入面留待 W4-D"）。增量数值由引擎侧 CHAT 分区签发后参数传入——
  // 事务零 RNG，双臂抽取增量恒 0。
  { id: 1860, name: 'DISCIPLE_CHAT_EFFECT_TX', desc: '弟子交谈效果事务（修炼/道德/忠诚/悟性参数化应用 + lastChatYear 冷却标记，零 RNG）' },
  // D4 续批·任务域收口（2026-09-15）：任务派遣事务下沉（mission_start_tx.h）。
  // 承接 Kotlin 写者 = GameEngineMissionOps.startMission（activeMissions 追加 +
  // 逐队员槽位清理/状态重置——弟子通道关闭的协议列阻断写者）。ActiveMission.id
  // 由 Kotlin UUID.randomUUID() 生成后参数传入（Java 随机非游戏分区，原基线
  // 零抽取）⇒ 事务零 RNG，双臂抽取增量恒 0。
  { id: 1861, name: 'MISSION_START_TX', desc: '任务派遣事务（模板快照 + 全槽位清理含住所保留 + 状态重置 IDLE，零 RNG）' },
  // 1862–1869 留空（W4-D 机动余量）。
];
