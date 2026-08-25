#!/usr/bin/env node
/**
 * gen-trait-db.mjs — 天赋/体质/词条快照生成器（Kotlin→C++ 迁移批次 2 剩余子步）
 *
 * 与 gen-templates.mjs 不同，TalentDatabase/PhysiqueDatabase/AffixDatabase 的数据
 * 是**程序化生成**的（config 梯度列表 + 循环拼接字符串），正则无法提取字面量。
 * 因此本脚本在 Node 侧**等价复刻 Kotlin 的生成逻辑**，产出 JSON 快照锚点：
 *   android/core/engine/src/test/resources/templates/trait_db_sample.json
 *
 * 该快照供后续 Kotlin 守卫测试（TemplateRegistryGuardTest 模式）对比 Kotlin 实时数据，
 * 并间接锚定 C++ 表（trait_db.h 用同样的梯度/拼接规则生成）。
 *
 * 用法：node scripts/gen-trait-db.mjs
 */
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const SAMPLE_DIR = join(ROOT, 'android/core/engine/src/test/resources/templates');

// ── 与 Kotlin String.format(Locale.ROOT, "%.Nf", val) 等价 ──────
function pct(value, decimals) {
  const scaled = value * 100;
  if (decimals === 0) return String(Math.round(scaled));
  return (Math.round(scaled * 10) / 10).toFixed(1);
}

// effects 键排序（确定性输出，与 C++ std::map 有序迭代一致）
const sortedEffects = (effects) => {
  const sorted = {};
  for (const k of Object.keys(effects).sort()) sorted[k] = effects[k];
  return sorted;
};

// ── 数值配置（与 Kotlin 字面量逐值一致）──────────────────────────
const cultSpeedConfigs = [
  { rarity: 1, value: 0.06 }, { rarity: 2, value: 0.10 }, { rarity: 3, value: 0.15 },
  { rarity: 4, value: 0.22 }, { rarity: 5, value: 0.25 }, { rarity: 6, value: 0.32 },
];
const breakChanceConfigs = [
  { rarity: 1, value: 0.01 }, { rarity: 2, value: 0.015 }, { rarity: 3, value: 0.03 },
  { rarity: 4, value: 0.04 }, { rarity: 5, value: 0.05 }, { rarity: 6, value: 0.07 },
];
const lifespanConfigs = [
  { rarity: 1, value: 0.10 }, { rarity: 2, value: 0.16 }, { rarity: 3, value: 0.25 },
  { rarity: 4, value: 0.35 }, { rarity: 5, value: 0.45 }, { rarity: 6, value: 0.60 },
];
const batAtkDefSpeedConfigs = [
  { rarity: 1, value: 0.06 }, { rarity: 2, value: 0.13 }, { rarity: 3, value: 0.22 },
];
const batHpConfigs = [
  { rarity: 1, value: 0.10 }, { rarity: 2, value: 0.18 }, { rarity: 3, value: 0.30 },
];
const batMpConfigs = [
  { rarity: 1, value: 0.10 }, { rarity: 2, value: 0.18 }, { rarity: 3, value: 0.30 },
];
const batCritConfigs = [
  { rarity: 1, value: 0.04 }, { rarity: 2, value: 0.08 }, { rarity: 3, value: 0.14 },
];
const baseFlatConfigs = [
  { rarity: 1, value: 4 }, { rarity: 2, value: 10 }, { rarity: 3, value: 18 },
];
const positionBonusConfigs = [
  { rarity: 1, value: 0.07 }, { rarity: 2, value: 0.14 }, { rarity: 3, value: 0.22 },
];

// 旧版 rarity(1-6) → 品级(1-3)
const talentGrade = (r) => r <= 2 ? 1 : r <= 4 ? 2 : r <= 6 ? 3 : 1;

// ── 天赋表生成 ───────────────────────────────────────────────────
const tPositions = [
  ['vice_sect_master', '辅政之才', 'VICE_SECT_MASTER', '政策效果加成', 'POSITION_VICE_SECT_MASTER'],
  ['herb_garden', '灵田灵手', 'HERB_GARDEN', '灵药成熟速度加成', 'POSITION_HERB_GARDEN'],
  ['alchemy', '丹道宗师', 'ALCHEMY', '炼丹成功率加成', 'POSITION_ALCHEMY'],
  ['forge', '器道宗师', 'FORGE', '炼器成功率加成', 'POSITION_FORGE'],
  ['outer_elder', '外门栋梁', 'OUTER_ELDER', '外门弟子突破指导加成', 'POSITION_OUTER_ELDER'],
  ['preaching', '传道大师', 'PREACHING', '外门弟子传道修炼速度加成', 'POSITION_PREACHING'],
  ['law_enforcement', '执法金刚', 'LAW_ENFORCEMENT', '叛逃/偷盗捕获率加成', 'POSITION_LAW_ENFORCEMENT'],
  ['inner_elder', '内门柱石', 'INNER_ELDER', '内门弟子突破指导加成', 'POSITION_INNER_ELDER'],
  ['recruiting', '招贤伯乐', 'RECRUITING', '招募弟子数上限加成', 'POSITION_RECRUITING'],
  ['cloud_preaching', '青云传道', 'CLOUD_PREACHING', '内门弟子传道修炼速度加成', 'POSITION_CLOUD_PREACHING'],
];
const baseTalents = [
  ['base_int', '天慧', '智力', 'intelligenceFlat', 'BASE_INT'],
  ['base_charm', '仙姿', '魅力', 'charmFlat', 'BASE_CHARM'],
  ['base_loyal', '赤诚', '忠诚', 'loyaltyFlat', 'BASE_LOYAL'],
  ['base_comp', '顿悟', '悟性', 'comprehensionFlat', 'BASE_COMP'],
  ['base_arti', '天工', '炼器', 'artifactRefiningFlat', 'BASE_ARTI'],
  ['base_pill', '天丹', '炼丹', 'pillRefiningFlat', 'BASE_PILL'],
  ['base_plant', '青帝', '灵植', 'spiritPlantingFlat', 'BASE_PLANT'],
  ['base_teach', '夫子', '传道', 'teachingFlat', 'BASE_TEACH'],
  ['base_moral', '仁心', '德行', 'moralityFlat', 'BASE_MORAL'],
  ['base_mining', '地眼', '采矿', 'miningFlat', 'BASE_MINING'],
];

function buildTalents() {
  const out = [];
  const mk = (id, name, description, rarity, effects, isNegative, type, template, positionBonus) =>
    out.push({ id, name, description, rarity, effects: sortedEffects(effects), isNegative, type, template, positionBonus });

  // 旧天赋
  for (const c of cultSpeedConfigs)
    mk(`r${c.rarity}_cult_speed`, '灵脉流转', `修炼速度+${pct(c.value, 0)}%`, talentGrade(c.rarity),
       { cultivationSpeed: c.value }, false, 'CULT_SPEED', 'cult_speed', null);
  for (const c of breakChanceConfigs)
    mk(`r${c.rarity}_break_chance`, '悟道通玄', `突破概率+${pct(c.value, 1)}%`, talentGrade(c.rarity),
       { breakthroughChance: c.value }, false, 'BREAK_CHANCE', 'break_chance', null);
  for (const c of lifespanConfigs)
    mk(`r${c.rarity}_lifespan`, '寿元绵长', `寿命+${pct(c.value, 0)}%`, talentGrade(c.rarity),
       { lifespan: c.value }, false, 'LIFESPAN', 'lifespan', null);
  mk('r6_manual_slot', '天衍道藏', '功法槽位+1', 3, { manualSlot: 1.0 }, false, 'MANUAL_SLOT', 'manual_slot', null);
  mk('r6_win_growth', '百战通神', '每胜利一场战斗后，随机一个属性+1（无上限）', 3,
     { winBattleRandomAttrPlus: 1.0 }, false, 'WIN_GROWTH', 'win_growth', null);

  // 新天赋：战斗属性百分比
  for (const c of batAtkDefSpeedConfigs)
    mk(`r${c.rarity}_bat_phy_atk`, '勇武', `物攻+${pct(c.value, 0)}%`, c.rarity,
       { physicalAttack: c.value }, false, 'BAT_PHY_ATK', 'bat_phy_atk', null);
  for (const c of batAtkDefSpeedConfigs)
    mk(`r${c.rarity}_bat_mag_atk`, '神通', `法攻+${pct(c.value, 0)}%`, c.rarity,
       { magicAttack: c.value }, false, 'BAT_MAG_ATK', 'bat_mag_atk', null);
  for (const c of batAtkDefSpeedConfigs)
    mk(`r${c.rarity}_bat_phy_def`, '铁骨', `物防+${pct(c.value, 0)}%`, c.rarity,
       { physicalDefense: c.value }, false, 'BAT_PHY_DEF', 'bat_phy_def', null);
  for (const c of batAtkDefSpeedConfigs)
    mk(`r${c.rarity}_bat_mag_def`, '玄清', `法防+${pct(c.value, 0)}%`, c.rarity,
       { magicDefense: c.value }, false, 'BAT_MAG_DEF', 'bat_mag_def', null);
  for (const c of batAtkDefSpeedConfigs)
    mk(`r${c.rarity}_bat_speed`, '疾风', `速度+${pct(c.value, 0)}%`, c.rarity,
       { speed: c.value }, false, 'BAT_SPEED', 'bat_speed', null);
  for (const c of batHpConfigs)
    mk(`r${c.rarity}_bat_hp`, '体健', `气血+${pct(c.value, 0)}%`, c.rarity,
       { maxHp: c.value }, false, 'BAT_HP', 'bat_hp', null);
  for (const c of batMpConfigs)
    mk(`r${c.rarity}_bat_mp`, '气海', `法力+${pct(c.value, 0)}%`, c.rarity,
       { maxMp: c.value }, false, 'BAT_MP', 'bat_mp', null);
  for (const c of batCritConfigs)
    mk(`r${c.rarity}_bat_crit`, '锋锐', `暴击+${pct(c.value, 0)}%`, c.rarity,
       { critRate: c.value }, false, 'BAT_CRIT', 'bat_crit', null);

  // 新天赋：基础属性扁平
  for (const [tmpl, name, prefix, key, type] of baseTalents)
    for (const c of baseFlatConfigs)
      mk(`r${c.rarity}_${tmpl}`, name, `${prefix}+${c.value}`, c.rarity,
         { [key]: c.value }, false, type, tmpl, null);

  // 职务天赋
  for (const [tmpl, name, slotType, bonusDesc, type] of tPositions)
    for (const c of positionBonusConfigs)
      mk(`r${c.rarity}_pos_${tmpl}`, name, `${bonusDesc}+${pct(c.value, 0)}%`, c.rarity,
         {}, false, type, `pos_${tmpl}`, { slotType, effectBonus: c.value });

  // 负面天赋
  mk('neg_base_comprehension', '神识迟钝', '悟性/智力/传道 -8', 0,
     { comprehensionFlat: -8, intelligenceFlat: -8, teachingFlat: -8 }, true, 'BASE_COMP', 'neg_base_comprehension', null);
  mk('neg_base_craft', '百艺生疏', '炼器/炼丹/种植 -6', 0,
     { artifactRefiningFlat: -6, pillRefiningFlat: -6, spiritPlantingFlat: -6 }, true, 'BASE_ARTI', 'neg_base_craft', null);
  mk('neg_base_social', '心性偏执', '魅力/忠诚/道德 -6', 0,
     { charmFlat: -6, loyaltyFlat: -6, moralityFlat: -6 }, true, 'BASE_CHARM', 'neg_base_social', null);
  mk('neg_battle_offense', '怯战失锋', '物攻/法攻/暴击下降', 0,
     { physicalAttack: -0.10, magicAttack: -0.10, critRate: -0.02 }, true, 'BAT_PHY_ATK', 'neg_battle_offense', null);
  mk('neg_battle_survival', '体魄亏空', '生存属性下降', 0,
     { maxHp: -0.15, maxMp: -0.08, physicalDefense: -0.10, magicDefense: -0.10, speed: -0.06 },
     true, 'BAT_HP', 'neg_battle_survival', null);

  return out;
}

// ── 体质表生成 ───────────────────────────────────────────────────
const pSpeedConfigs = [{ rarity: 1, value: 0.08 }, { rarity: 2, value: 0.16 }, { rarity: 3, value: 0.28 }];
const pAmpConfigs = [{ rarity: 1, value: 0.05 }, { rarity: 2, value: 0.11 }, { rarity: 3, value: 0.20 }];
const pReduceConfigs = [{ rarity: 1, value: 0.04 }, { rarity: 2, value: 0.09 }, { rarity: 3, value: 0.16 }];
const pCritDmgConfigs = [{ rarity: 1, value: 0.10 }, { rarity: 2, value: 0.22 }, { rarity: 3, value: 0.38 }];
const pDefConfigs = [{ rarity: 1, value: 0.06 }, { rarity: 2, value: 0.13 }, { rarity: 3, value: 0.22 }];
const pHybridOffConfigs = [{ rarity: 1, amp: 0.03, crit: 0.06 }, { rarity: 2, amp: 0.07, crit: 0.14 }, { rarity: 3, amp: 0.12, crit: 0.24 }];
const pHybridDefConfigs = [{ rarity: 1, reduce: 0.02, def: 0.04 }, { rarity: 2, reduce: 0.05, def: 0.08 }, { rarity: 3, reduce: 0.09, def: 0.14 }];

function buildPhysiques() {
  const out = [];
  const mk = (id, name, description, rarity, c, amp, reduce, crit, def, isNegative, type, template) =>
    out.push({ id, name, description, rarity, cultivationSpeedBonus: c, damageAmplification: amp,
               damageReduction: reduce, critDamageBonus: crit, defenseBonus: def, isNegative, type, template });
  for (const x of pSpeedConfigs)
    mk(`r${x.rarity}_phys_cult_speed`, '灵脉天成', `修炼速度+${pct(x.value, 0)}%`, x.rarity, x.value, 0, 0, 0, 0, false, 'CULT_SPEED', 'phys_cult_speed');
  for (const x of pAmpConfigs)
    mk(`r${x.rarity}_phys_dmg_amp`, '九阳真身', `伤害加成+${pct(x.value, 0)}%`, x.rarity, 0, x.value, 0, 0, 0, false, 'DAMAGE_AMP', 'phys_dmg_amp');
  for (const x of pReduceConfigs)
    mk(`r${x.rarity}_phys_dmg_reduce`, '金身不坏', `减伤+${pct(x.value, 0)}%`, x.rarity, 0, 0, x.value, 0, 0, false, 'DAMAGE_REDUCTION', 'phys_dmg_reduce');
  for (const x of pCritDmgConfigs)
    mk(`r${x.rarity}_phys_crit_dmg`, '天眼通', `暴击伤害+${pct(x.value, 0)}%`, x.rarity, 0, 0, 0, x.value, 0, false, 'CRIT_DAMAGE', 'phys_crit_dmg');
  for (const x of pDefConfigs)
    mk(`r${x.rarity}_phys_defense`, '玄铁体质', `防御加成+${pct(x.value, 0)}%`, x.rarity, 0, 0, 0, 0, x.value, false, 'DEFENSE_BONUS', 'phys_defense');
  for (const x of pHybridOffConfigs)
    mk(`r${x.rarity}_phys_hybrid_off`, '战魔之体', `伤害加成+${pct(x.amp, 0)}%，暴击伤害+${pct(x.crit, 0)}%`, x.rarity, 0, x.amp, 0, x.crit, 0, false, 'HYBRID_OFFENSE', 'phys_hybrid_off');
  for (const x of pHybridDefConfigs)
    mk(`r${x.rarity}_phys_hybrid_def`, '磐石体质', `减伤+${pct(x.reduce, 0)}%，防御加成+${pct(x.def, 0)}%`, x.rarity, 0, 0, x.reduce, 0, x.def, false, 'HYBRID_DEFENSE', 'phys_hybrid_def');
  // 负面
  mk('neg_phys_cult', '经脉堵塞', '修炼速度-20%', 0, -0.20, 0, 0, 0, 0, true, 'CULT_SPEED', 'neg_phys_cult');
  mk('neg_phys_defense', '体弱多病', '减伤-10%，防御加成-15%', 0, 0, 0, -0.10, 0, -0.15, true, 'HYBRID_DEFENSE', 'neg_phys_defense');
  mk('neg_phys_offense', '灵根残缺', '伤害加成-12%，暴击伤害-20%', 0, 0, -0.12, 0, -0.20, 0, true, 'HYBRID_OFFENSE', 'neg_phys_offense');
  return out;
}

// ── 词条表生成 ───────────────────────────────────────────────────
const aPositions = [
  ['vice_sect_master', '辅政', 'VICE_SECT_MASTER', '政策效果加成'],
  ['herb_garden', '灵田', 'HERB_GARDEN', '灵药成熟速度加成'],
  ['alchemy', '丹道', 'ALCHEMY', '炼丹成功率加成'],
  ['forge', '器道', 'FORGE', '炼器成功率加成'],
  ['outer_elder', '外门', 'OUTER_ELDER', '外门弟子突破指导加成'],
  ['preaching', '传道', 'PREACHING', '外门弟子传道修炼速度加成'],
  ['law_enforcement', '执法', 'LAW_ENFORCEMENT', '叛逃/偷盗捕获率加成'],
  ['inner_elder', '内门', 'INNER_ELDER', '内门弟子突破指导加成'],
  ['recruiting', '招贤', 'RECRUITING', '招募弟子数上限加成'],
  ['cloud_preaching', '青云', 'CLOUD_PREACHING', '内门弟子传道修炼速度加成'],
];
const affBaseFlatConfigs = [{ rarity: 1, value: 3 }, { rarity: 2, value: 7 }, { rarity: 3, value: 12 }];
const affBatPctConfigs = [{ rarity: 1, value: 0.04 }, { rarity: 2, value: 0.09 }, { rarity: 3, value: 0.16 }];
const affCultSpeedConfigs = [{ rarity: 1, value: 0.05 }, { rarity: 2, value: 0.11 }, { rarity: 3, value: 0.20 }];
const affLifespanConfigs = [{ rarity: 1, value: 0.08 }, { rarity: 2, value: 0.16 }, { rarity: 3, value: 0.28 }];
const affDmgAmpConfigs = [{ rarity: 1, value: 0.03 }, { rarity: 2, value: 0.07 }, { rarity: 3, value: 0.13 }];
const affDmgReduceConfigs = [{ rarity: 1, value: 0.03 }, { rarity: 2, value: 0.06 }, { rarity: 3, value: 0.11 }];
const affCritDmgConfigs = [{ rarity: 1, value: 0.06 }, { rarity: 2, value: 0.14 }, { rarity: 3, value: 0.24 }];
const affDefConfigs = [{ rarity: 1, value: 0.04 }, { rarity: 2, value: 0.09 }, { rarity: 3, value: 0.15 }];
const affPositionConfigs = [{ rarity: 1, value: 0.06 }, { rarity: 2, value: 0.12 }, { rarity: 3, value: 0.20 }];

function buildAffixes() {
  const out = [];
  const mk = (id, name, description, rarity, effects, isNegative, type, template, positionBonus) =>
    out.push({ id, name, description, rarity, effects: sortedEffects(effects), isNegative, type, template, positionBonus });

  const affBase = [
    ['aff_base_int', '聪慧', '智力', 'intelligenceFlat'],
    ['aff_base_comp', '灵慧', '悟性', 'comprehensionFlat'],
    ['aff_base_charm', '风采', '魅力', 'charmFlat'],
  ];
  for (const [tmpl, name, prefix, key] of affBase)
    for (const c of affBaseFlatConfigs)
      mk(`r${c.rarity}_${tmpl}`, name, `${prefix}+${c.value}`, c.rarity, { [key]: c.value }, false, 'BASE_FLAT', tmpl, null);

  for (const c of affBatPctConfigs)
    mk(`r${c.rarity}_aff_bat_atk`, '锐利', `物攻+${pct(c.value, 0)}%`, c.rarity,
       { physicalAttack: c.value, magicAttack: c.value }, false, 'BAT_PCT', 'aff_bat_atk', null);
  for (const c of affBatPctConfigs)
    mk(`r${c.rarity}_aff_bat_hp`, '厚甲', `气血+${pct(c.value, 0)}%`, c.rarity,
       { maxHp: c.value }, false, 'BAT_PCT', 'aff_bat_hp', null);
  for (const c of affBatPctConfigs)
    mk(`r${c.rarity}_aff_bat_speed`, '轻盈', `速度+${pct(c.value, 0)}%`, c.rarity,
       { speed: c.value }, false, 'BAT_PCT', 'aff_bat_speed', null);
  for (const c of affCultSpeedConfigs)
    mk(`r${c.rarity}_aff_cult_speed`, '悟道', `修炼速度+${pct(c.value, 0)}%`, c.rarity,
       { cultivationSpeed: c.value }, false, 'CULT_SPEED', 'aff_cult_speed', null);
  for (const c of affLifespanConfigs)
    mk(`r${c.rarity}_aff_lifespan`, '延年', `寿命+${pct(c.value, 0)}%`, c.rarity,
       { lifespan: c.value }, false, 'LIFESPAN', 'aff_lifespan', null);
  mk('r3_aff_manual_slot', '道藏', '功法槽位+1', 3, { manualSlot: 1.0 }, false, 'MANUAL_SLOT', 'aff_manual_slot', null);
  mk('r3_aff_win_growth', '战悟', '每胜利一场战斗后，随机一个属性+1（无上限）', 3,
     { winBattleRandomAttrPlus: 1.0 }, false, 'WIN_GROWTH', 'aff_win_growth', null);
  for (const c of affDmgAmpConfigs)
    mk(`r${c.rarity}_aff_dmg_amp`, '煞气', `伤害加成+${pct(c.value, 0)}%`, c.rarity,
       { damageAmplification: c.value }, false, 'DAMAGE_AMP', 'aff_dmg_amp', null);
  for (const c of affDmgReduceConfigs)
    mk(`r${c.rarity}_aff_dmg_reduce`, '护体', `减伤+${pct(c.value, 0)}%`, c.rarity,
       { damageReduction: c.value }, false, 'DAMAGE_REDUCTION', 'aff_dmg_reduce', null);
  for (const c of affCritDmgConfigs)
    mk(`r${c.rarity}_aff_crit_dmg`, '破军', `暴击伤害+${pct(c.value, 0)}%`, c.rarity,
       { critDamageBonus: c.value }, false, 'CRIT_DAMAGE', 'aff_crit_dmg', null);
  for (const c of affDefConfigs)
    mk(`r${c.rarity}_aff_defense`, '坚壁', `防御加成+${pct(c.value, 0)}%`, c.rarity,
       { defenseBonus: c.value }, false, 'DEFENSE_BONUS', 'aff_defense', null);
  for (const [tmpl, name, slotType, bonusDesc] of aPositions)
    for (const c of affPositionConfigs)
      mk(`r${c.rarity}_aff_pos_${tmpl}`, `${name}之印`, `${bonusDesc}+${pct(c.value, 0)}%`, c.rarity,
         {}, false, 'POSITION', `aff_pos_${tmpl}`, { slotType, effectBonus: c.value });

  // 负面词条
  mk('neg_aff_base', '愚钝', '智力/悟性/魅力 -5', 0,
     { intelligenceFlat: -5, comprehensionFlat: -5, charmFlat: -5 }, true, 'BASE_FLAT', 'neg_aff_base', null);
  mk('neg_aff_battle', '虚弱', '物攻/法攻/气血 -8%', 0,
     { physicalAttack: -0.08, magicAttack: -0.08, maxHp: -0.08 }, true, 'BAT_PCT', 'neg_aff_battle', null);
  mk('neg_aff_lifespan', '夭折', '寿命-15%', 0, { lifespan: -0.15 }, true, 'LIFESPAN', 'neg_aff_lifespan', null);
  return out;
}

// ── main ─────────────────────────────────────────────────────────
const talents = buildTalents();
const physiques = buildPhysiques();
const affixes = buildAffixes();

// 去重校验（id 唯一）
for (const [name, list] of [['天赋', talents], ['体质', physiques], ['词条', affixes]]) {
  const seen = new Set();
  for (const e of list) {
    if (seen.has(e.id)) {
      console.error(`错误：重复${name} id ${e.id}`);
      process.exit(1);
    }
    seen.add(e.id);
  }
}

mkdirSync(SAMPLE_DIR, { recursive: true });
writeFileSync(join(SAMPLE_DIR, 'trait_db_sample.json'), JSON.stringify({
  talentCount: talents.length,
  physiqueCount: physiques.length,
  affixCount: affixes.length,
  talents,
  physiques,
  affixes,
}, null, 1));

console.log(`天赋/体质/词条快照生成完成：${talents.length} 天赋 + ${physiques.length} 体质 + ${affixes.length} 词条`);
console.log(`  -> ${join(SAMPLE_DIR, 'trait_db_sample.json')}`);
