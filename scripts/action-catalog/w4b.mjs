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
  // B3 实施口径（2026-09-15）：行商族四事务下沉（池生成留 Kotlin——C++ data 层
  // 无物品生成器，jade_tx.h §2.50 同证）；**邮件附件领取登记不下沉**（12 类附件
  // 分发表含 7 类 MAIL 分区随机生成 + 凭据类「发放体 + mailRecords 同生共死」
  // 原子性禁止拆双写域——与 RedeemCodeService 同先例，触发条件 = C++ 具备物品
  // 随机生成器后重议）。
  { id: 1770, name: 'MERCHANT_CHANCE_GRANT_TX', desc: '行商手动刷新次数年度发放（每30年+1，达上限零写入）' },
  { id: 1771, name: 'MERCHANT_ACQUISITION_REFRESH_TX', desc: '收购池整表覆写（items 由 Kotlin 以 SYSTEM 分区预生成）' },
  { id: 1772, name: 'MERCHANT_TRAVELING_REFRESH_TX', desc: '旅行商人池整表覆写 + 年份/刷新计数（保底相位 Kotlin 预计算）' },
  { id: 1773, name: 'MERCHANT_MANUAL_REFRESH_TX', desc: '手动刷新（chances 校验先行 + 扣凭据 + 池覆写单事务原子）' },

  // ── 1840–1849 · w3-12 外交/自愈/运行态 ──
  // B4 实施口径（2026-09-15）：**仅实裁 1843**（shownWarningStageIds 按 ① 保守
  // 处置）。登记不下沉（逐点判定见 diplomacy_selfheal_tx.h 头注）：附庸年贡族
  // （C++ 逻辑已在位 year_settlement.h，调用点门控属 w3-11/W4-D）、月度脱离、
  // 内存裁剪（W4-D）、存档前自愈（W4-C WS-5b 同域）、修炼检查点重锚（W4-A 域 +
  // models.h 扩列需租约）。段内未实裁号不认领。
  { id: 1843, name: 'DIPLOMACY_WARNING_STAGE_TX', desc: '预警阶段标记（shownWarningStageIds 追加，不去重）' },
];
