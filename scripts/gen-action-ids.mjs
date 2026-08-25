#!/usr/bin/env node
/**
 * gen-action-ids.mjs — ActionId 协议单一数据源生成器
 *
 * 生成两份产物（提交 git，防漂移，与 build-atlas.mjs → TextureAtlas.h 同模式）：
 *   1. android/app/src/main/cpp/gamecore/include/gamecore/action_ids.h   （C++）
 *   2. android/core/engine/src/main/java/com/xianxia/sect/core/nativebridge/ActionIds.kt （Kotlin）
 *
 * 清单：本脚本内嵌 ACTION_CATALOG（批次 0 为空，随子系统迁移逐步填充）。
 * 约定：actionId 唯一、稳定（存档无关，仅进程内协议；可自由演进）。
 *
 * 用法：node scripts/gen-action-ids.mjs
 */
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const CPP_HEADER = join(
  ROOT, 'android/app/src/main/cpp/gamecore/include/gamecore/action_ids.h');
const KT_FILE = join(
  ROOT, 'android/core/engine/src/main/java/com/xianxia/sect/core/nativebridge/ActionIds.kt');

/**
 * ActionId 清单（单一事实源）
 * 结构：{ id: number, name: string, desc: string }
 * id 分配：1000 起为业务动作段；系统动作（生命周期/tick/快照）不走 execute，无需占位。
 * 批次 0 为空骨架；批次 1+ 按子系统迁移顺序追加（每批新增动作 id 连续递增）。
 *
 * 批次 4-8 已落地 C++ 系统（经济/库存/灵田/弟子/修炼/突破/生命周期/战斗/内政/探索）：
 * 对应 execute 动作注册如下。批次 9 由 Kotlin 转发层按 ActionId 分发调用。
 */
const ACTION_CATALOG = [
  // ── 批次 4：经济（SpiritStoneWallet） ──
  { id: 1000, name: 'WALLET_ADD', desc: '灵石增加（事务内）' },
  { id: 1001, name: 'WALLET_DEDUCT', desc: '灵石扣除（含自动售卖补差价）' },
  { id: 1002, name: 'WALLET_BATCH', desc: '灵石批量变更（原子）' },
  { id: 1003, name: 'WALLET_BALANCE', desc: '灵石余额查询（按品阶）' },
  { id: 1004, name: 'WALLET_TOTAL_SELL_VALUE', desc: '全部品阶按售卖价总价值' },

  // ── 批次 4：库存（InventorySystem） ──
  { id: 1010, name: 'INV_ADD_EQUIPMENT_STACK', desc: '添加装备堆叠（合并+溢出转邮件）' },
  { id: 1011, name: 'INV_ADD_EQUIPMENT_INSTANCE', desc: '添加装备实例' },
  { id: 1012, name: 'INV_ADD_MANUAL_STACK', desc: '添加功法堆叠' },
  { id: 1013, name: 'INV_ADD_MANUAL_INSTANCE', desc: '添加功法实例' },
  { id: 1014, name: 'INV_ADD_PILL', desc: '添加丹药（按品阶合并）' },
  { id: 1015, name: 'INV_ADD_MATERIAL', desc: '添加材料' },
  { id: 1016, name: 'INV_ADD_HERB', desc: '添加草药' },
  { id: 1017, name: 'INV_ADD_SEED', desc: '添加种子' },
  { id: 1018, name: 'INV_ADD_STORAGE_BAG', desc: '添加储物袋' },
  { id: 1019, name: 'INV_REMOVE_EQUIPMENT', desc: '移除装备（按 id+数量）' },
  { id: 1020, name: 'INV_REMOVE_MANUAL', desc: '移除功法' },
  { id: 1021, name: 'INV_REMOVE_PILL', desc: '移除丹药' },
  { id: 1022, name: 'INV_REMOVE_MATERIAL', desc: '移除材料' },
  { id: 1023, name: 'INV_REMOVE_HERB', desc: '移除草药' },
  { id: 1024, name: 'INV_REMOVE_SEED', desc: '移除种子' },
  { id: 1025, name: 'INV_CAN_ADD_ITEM', desc: '仓库是否有空余槽位' },
  { id: 1026, name: 'INV_CAPACITY_INFO', desc: '仓库容量信息' },

  // ── 批次 4c：灵田收获 ──
  { id: 1030, name: 'SPIRIT_FIELD_HARVEST', desc: '灵田月度收获（成熟判定+续种+年度报告）' },

  // ── 批次 5：弟子属性/修炼/突破/生命周期 ──
  { id: 1100, name: 'DISCIPLE_BASE_STATS', desc: '弟子基础属性乘区法计算' },
  { id: 1101, name: 'DISCIPLE_CULTIVATION_PER_PHASE', desc: '每旬修炼速度（乘区法）' },
  { id: 1102, name: 'DISCIPLE_BREAKTHROUGH_CHANCE', desc: '突破概率（乘区法）' },
  { id: 1103, name: 'DISCIPLE_MAX_AGE', desc: '弟子最大寿元' },
  { id: 1104, name: 'DISCIPLE_CHECKPOINT', desc: '修炼检查点同步' },
  { id: 1105, name: 'DISCIPLE_ACCUMULATE_CULTIVATION', desc: '每旬修炼累积（钳制上限）' },
  { id: 1106, name: 'DISCIPLE_AGE', desc: '弟子老化（年龄+1/5岁回正/寿元判定）' },
  { id: 1107, name: 'DISCIPLE_BREAKTHROUGH', desc: '突破执行（连续突破循环）' },
  { id: 1108, name: 'DISCIPLE_ESTIMATE_BREAKTHROUGH_MONTH', desc: '突破完成月份预估' },

  // ── 批次 6：战斗 ──
  { id: 1200, name: 'BATTLE_FINAL_DAMAGE', desc: '乘区法最终伤害计算' },
  { id: 1201, name: 'BATTLE_REALM_GAP_FACTORS', desc: '境界压制三因子' },
  { id: 1202, name: 'BATTLE_CHECK_INSTANT_KILL', desc: '跨境界斩杀判定' },
  { id: 1203, name: 'BATTLE_DODGE_CHANCE', desc: '闪避概率' },
  { id: 1204, name: 'BATTLE_SHIELD_ABSORPTION', desc: '护盾吸收' },
  { id: 1205, name: 'BATTLE_DOT', desc: 'DoT 持续伤害结算' },
  { id: 1206, name: 'BATTLE_COOLDOWN_UPDATE', desc: '技能冷却更新' },

  // ── 批次 7：内政 ──
  { id: 1300, name: 'GOV_POLICY_COSTS', desc: '政策月度成本（三模式扣除）' },
  { id: 1301, name: 'GOV_POLICY_MONTHLY_EFFECTS', desc: '政策月度忠诚/道德效果' },
  { id: 1302, name: 'GOV_SPIRIT_MINE_MONTHLY', desc: '灵矿月度产出（时间戳差分）' },
  { id: 1303, name: 'GOV_ANNUAL_SALARY', desc: '年度年俸发放' },
  { id: 1304, name: 'GOV_ZONE_CALCULATE', desc: '乘区法通用计算' },

  // ── 批次 8：探索/世界关卡 ──
  { id: 1400, name: 'WORLD_LEVEL_MONTHLY', desc: '世界关卡月度处理（清理+刷新+移动）' },
  { id: 1401, name: 'WORLD_LEVEL_CHECK_EXPIRED', desc: '关卡过期判定' },
];

const MAX_ID = ACTION_CATALOG.reduce((m, a) => Math.max(m, a.id), 0);

function genCpp() {
  const lines = [];
  lines.push('// 由 scripts/gen-action-ids.mjs 生成 — 禁止手改（与 ActionIds.kt 同源）');
  lines.push('#pragma once');
  lines.push('');
  lines.push('// ============================================================');
  lines.push('// ActionId 协议（业务操作码）— 与 Kotlin ActionIds.kt 同步生成');
  lines.push('// 参数/结果一律 JSON 字节（nlohmann/json ↔ kotlinx.serialization）');
  lines.push('// ============================================================');
  lines.push('namespace gamecore {');
  lines.push('');
  lines.push('/// 业务操作码（Kotlin GameCoreBridge.nativeExecute 的 actionId）');
  lines.push('namespace action {');
  for (const a of ACTION_CATALOG) {
    lines.push(`/// ${a.desc}`);
    lines.push(`inline constexpr int32_t ${a.name} = ${a.id};`);
    lines.push('');
  }
  lines.push('}  // namespace action');
  lines.push('');
  lines.push('}  // namespace gamecore');
  lines.push('');
  return lines.join('\n');
}

function genKotlin() {
  const lines = [];
  lines.push('package com.xianxia.sect.core.nativebridge');
  lines.push('');
  lines.push('/**');
  lines.push(' * ActionIds — 业务操作码（与 C++ action_ids.h 由 gen-action-ids.mjs 同源生成）。');
  lines.push(' * 禁止手改：修改清单后运行 `node scripts/gen-action-ids.mjs`。');
  lines.push(' */');
  // 批次 0 骨架：ACTION_CATALOG 为空 → 空对象体触发 detekt EmptyClassBlock，
  // 由生成器在清单为空时显式抑制（清单非空后抑制自动消失）
  if (ACTION_CATALOG.length === 0) {
    lines.push('@Suppress("EmptyClassBlock") // 批次 0 骨架：ACTION_CATALOG 为空，随子系统迁移填充');
  }
  lines.push('object ActionIds {');
  for (const a of ACTION_CATALOG) {
    lines.push('    /** ' + a.desc + ' */');
    lines.push(`    const val ${a.name}: Int = ${a.id}`);
    lines.push('');
  }
  lines.push('}');
  lines.push('');
  return lines.join('\n');
}

mkdirSync(dirname(CPP_HEADER), { recursive: true });
mkdirSync(dirname(KT_FILE), { recursive: true });
writeFileSync(CPP_HEADER, genCpp());
writeFileSync(KT_FILE, genKotlin());

console.log(`gen-action-ids: ${ACTION_CATALOG.length} actions (maxId=${MAX_ID})`);
console.log(`  -> ${CPP_HEADER}`);
console.log(`  -> ${KT_FILE}`);
