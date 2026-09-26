#!/usr/bin/env node
/**
 * action-catalog/gacha.mjs — 角色卡池（G 批）ActionId 清单。
 *
 * ## 所有权
 * 仅 G 批（character-gacha-implementation）可写本文件。
 * 新增动作 = CATALOG 末尾追加 → `node scripts/gen-action-ids.mjs` 重生成
 * `action_ids.h` / `ActionIds.kt`（禁止手改生成物）。
 *
 * ## 预分配段
 *   1870–1889  角色卡池（G01 预留；G09 落 GACHA_PULL_ONCE/TEN 等）
 *
 * ## 纪律
 * - id 全局唯一；不得跨批占段（gen 脚本有区间重叠与 ownership 断言）
 * - ActionId 只增不复用（含改名 1740 留洞）
 * - 结构：`{ id, name, desc }`
 */
export const CATALOG = [
  // 1870–1889 · 角色卡池（G01 预留空段，G09 实跑分配）
  { id: 1870, name: 'GACHA_FRAGMENT_GRANT_TX', desc: '角色碎片入账事务（零 RNG；碎片累加+满 100 升星；抽卡/兑换码/邮件/活动共用）' },
  { id: 1871, name: 'GACHA_PULL_ONCE', desc: '寻访单抽事务（校验→扣灵石→roll 类别/角色/品阶→碎片与入库→历史→保底计数；消费 GACHA 分区）' },
  { id: 1872, name: 'GACHA_PULL_TEN', desc: '寻访十连事务（一笔事务内顺序 10 次单抽语义，共享同一抽卡流与保底计数；一次性扣费）' },
];
