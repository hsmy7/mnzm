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
  { id: 1027, name: 'INV_CONSOLIDATE', desc: '仓库堆叠合并（批 8-3）' },
  { id: 1028, name: 'INV_SORT', desc: '仓库整理=合并+排序（含实例轨道）' },
  { id: 1029, name: 'INV_TOGGLE_LOCK', desc: '堆叠锁定翻转（按 id+类型）' },

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

  // ── 计划 v2 阶段 4 批 4-1：LevelGenerator（世界关卡生成） ──
  { id: 1402, name: 'LEVEL_SELECT_BEAST_REALM', desc: '按年份加权随机选取妖兽境界' },
  { id: 1403, name: 'LEVEL_GENERATE_LEVELS', desc: '生成世界关卡（妖兽/洞府，含属性预生成）' },

  // ── 计划 v2 阶段 4 批 4-2：DiscipleDeathHandler（死亡物化） ──
  { id: 1404, name: 'DISCIPLE_MARK_DEAD', desc: '标记弟子死亡（isAlive/status/deathYears + 年死亡计数 + 装备断言）' },
  { id: 1405, name: 'DISCIPLE_BACKFILL_DEATH_YEARS', desc: '列表 copy 模式补写 deathYears（replaceAll 清空后恢复）' },

  // ── 计划 v2 阶段 4 批 4-3：SecretRealm（远古秘境状态机核心） ──
  { id: 1406, name: 'SECRET_REALM_PLAYER_AVG_REALM', desc: '存活成员平均境界（全灭取上限）' },
  { id: 1407, name: 'SECRET_REALM_ROLL_BEAST_REALM', desc: '秘境妖兽境界随机 [avg-1, avg+2] clamp 0..9' },
  { id: 1408, name: 'SECRET_REALM_GENERATE_BEAST_EVENT', desc: '生成遭遇妖兽事件（类型/境界/层数/数量）' },
  { id: 1409, name: 'SECRET_REALM_ROLL_NEXT_EVENT', desc: '方向选择后下一事件（一次 nextDouble 分段判定）' },
  { id: 1410, name: 'SECRET_REALM_BUILD_BEAST_STATS', desc: '妖兽最终属性预生成（层数倍率+随机方差+偷袭减血）' },
  { id: 1411, name: 'SECRET_REALM_ROLL_BEAST_LOOT', desc: '妖兽战斗胜利掉落（加权选取，每只 2 材料）' },
  { id: 1412, name: 'SECRET_REALM_GENERATE_RUINS_TREASURE', desc: '遗迹秘宝描述符生成（候选模板列表参数化）' },
  { id: 1413, name: 'SECRET_REALM_RESOLVE_RUINS', desc: '遗迹探索结算（离开/搜寻判定+结果文本+方向事件）' },
  { id: 1414, name: 'SECRET_REALM_LOOT_LOSS', desc: '战斗失败丢失背包物品（比例+洗牌选取）' },
  { id: 1415, name: 'SECRET_REALM_AI_DISPATCH', desc: 'AI 宗门探索队伍派遣（存活境界最高 4 名）' },
  { id: 1416, name: 'SECRET_REALM_FIND_POSITION', desc: '秘境空闲位置寻找（避宗门随机+兜底最远扫描）' },
  { id: 1417, name: 'SECRET_REALM_STAMINA', desc: '选择选项后体力计算（非法消耗 clamp 防篡改）' },
  { id: 1418, name: 'SECRET_REALM_YEARLY_SPAWN_CHECK', desc: '年变现世冷却判据（负冷却 clamp 防篡改）' },
  { id: 1419, name: 'SECRET_REALM_ROLL_SPRITE', desc: '秘境精灵变体随机（1×nextInt）' },

  // ── 计划 v2 阶段 4 批 4-4：外交（决策引擎/战力/品阶曲线/宗门交易） ──
  { id: 1420, name: 'SECT_DECISION_CHANCE', desc: 'AI 四因素加权判定概率（攻击/结盟/附属）' },
  { id: 1421, name: 'SECT_DECISION_BREAKAWAY', desc: '附属脱离概率（战力/丢失/胜负/好感度反向）' },
  { id: 1422, name: 'SECT_POWER_DISCIPLE', desc: '弟子战力（永久基础属性公式）' },
  { id: 1423, name: 'SECT_POWER_BEAST', desc: '妖兽战力（同公式 + 防篡改 clamp）' },
  { id: 1424, name: 'SECT_POWER_FINGERPRINT', desc: '永久基础属性缓存指纹（Java hashCode）' },
  { id: 1425, name: 'SECT_RARITY_ROLL', desc: '品阶时间曲线抽样（1×nextDouble）' },
  { id: 1426, name: 'SECT_RARITY_MAX', desc: '年份可出最高品阶' },
  { id: 1427, name: 'SECT_RARITY_PITY', desc: '年份保底品阶（下一分段）' },
  { id: 1428, name: 'SECT_RARITY_WEIGHTS', desc: '年份品阶权重表（归一化）' },
  { id: 1429, name: 'SECT_TRADE_SEED', desc: '宗门交易确定性种子（sectId.hashCode + year）' },
  { id: 1430, name: 'SECT_TRADE_STOCK', desc: '商品库存量抽样（消耗品/耐用品两档曲线）' },
  { id: 1431, name: 'SECT_TRADE_PRICE', desc: '商品价格波动（±20% 一位小数截断）' },
  { id: 1432, name: 'SECT_TRADE_SPIRIT_STONE', desc: '灵石商品映射（上品/中品 + 年份上限判定）' },

  // ── 计划 v2 阶段 4 批 4-5：11 槽分配清理（DiscipleSlotCleanup） ──
  { id: 1433, name: 'SLOT_CLEAR_ALL', desc: '清除弟子全部槽位引用（11 类槽位纯数据变换）' },

  // ── 计划 v2 阶段 4 批 4-6：兑换码 + 邮件附件（RedeemCodeManager） ──
  { id: 1434, name: 'REDEEM_VALIDATE_INPUT', desc: '兑换码格式校验（trim/长度/字符集）' },
  { id: 1435, name: 'REDEEM_ROLL_SPIRIT_ROOT', desc: '灵根类型解析（配置/数量随机/权重生成 + java.util.Random 洗牌）' },
  { id: 1436, name: 'REDEEM_RESOLVE_AGE_LIFESPAN', desc: '年龄区间 + 境界寿元 ±10% 波动' },
  { id: 1437, name: 'REDEEM_ROLL_SKILLS', desc: '灵根阶梯属性掷点 + 避开哨兵 50' },
  { id: 1438, name: 'REDEEM_GENERATE_VARIANCE', desc: '属性方差生成（-50..50）' },
  { id: 1439, name: 'MAIL_ATTACHMENT_ENCODE', desc: '邮件附件列表 → JSON 字符串（kotlinx 对齐）' },
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
