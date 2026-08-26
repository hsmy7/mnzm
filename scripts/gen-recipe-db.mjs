#!/usr/bin/env node
/**
 * gen-recipe-db.mjs — 锻造/炼丹配方快照生成器（Kotlin→C++ 迁移批次 2 剩余子步）
 *
 * 与 gen-templates.mjs / gen-trait-db.mjs 同模式：在 Node 侧**等价复刻 Kotlin
 * 的生成逻辑**，产出 JSON 快照锚点：
 *   android/core/engine/src/test/resources/templates/recipe_db_sample.json
 *
 * 数据来源：
 *   1. 锻造配方（72 条）：ForgeRecipeDatabase.kt 的静态字面量（逐字转录）。
 *   2. 丹药配方（732 条）：复刻 PillRecipeDatabase.kt 生成循环，其依赖的
 *      ItemDatabase.getPillById 模板（id/name/description/效果字段）同样在
 *      本脚本内复刻（660 个 PillTemplate）。
 *
 * 该快照供后续 Kotlin 守卫测试（TemplateRegistryGuardTest 模式）对比 Kotlin
 * 实时数据，并间接锚定 C++ 表（recipe_db.h 用同样的梯度/拼接规则生成）。
 *
 * 用法：node scripts/gen-recipe-db.mjs
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const SAMPLE_DIR = join(ROOT, 'android/core/engine/src/test/resources/templates');

// ── 与 Kotlin Double.roundToInt 一致（Math.round 正值半进位相同）──────
const rint = (v) => Math.round(v);

// 材料键排序（确定性输出，与 C++ std::map 有序迭代一致）
const sortedMats = (mats) => {
  const sorted = {};
  for (const k of Object.keys(mats).sort()) sorted[k] = mats[k];
  return sorted;
};

// ── 通用常量（与 Kotlin 字面量逐值一致）───────────────────────────────
const TIER_NAMES = { 1: '凡品', 2: '灵品', 3: '宝品', 4: '玄品', 5: '地品', 6: '天品' };
const GRADE_DISPLAY = ['下品', '中品', '上品'];          // LOW/MEDIUM/HIGH
const GRADE_LOWER = ['low', 'medium', 'high'];
const GRADE_MULT = [0.5, 1.0, 2.0];
const BREAK_CHANCE_BY_GRADE = [0.05, 0.12, 0.20];        // LOW/MEDIUM/HIGH

const REF_BASE = {
  hp: { 1: 120, 2: 780, 3: 2040, 4: 5400, 5: 13200, 6: 69600 },
  mp: { 1: 60, 2: 390, 3: 1020, 4: 2700, 5: 6600, 6: 34800 },
  pa: { 1: 12, 2: 78, 3: 204, 4: 540, 5: 1320, 6: 6960 },
  ma: { 1: 12, 2: 78, 3: 204, 4: 540, 5: 1320, 6: 6960 },
  pd: { 1: 10, 2: 65, 3: 170, 4: 450, 5: 1100, 6: 5800 },
  md: { 1: 8, 2: 52, 3: 136, 4: 360, 5: 880, 6: 4640 },
  spd: { 1: 15, 2: 97, 3: 255, 4: 675, 5: 1650, 6: 8700 },
};
const CULT_BASE = { 1: 600, 2: 8000, 3: 20000, 4: 50000, 5: 100000, 6: 300000 };
const SPEED_PCT = { 1: 0.30, 2: 0.35, 3: 0.40, 4: 0.50, 5: 0.60, 6: 0.80 };
const CRIT_RATE = { 1: 0.03, 2: 0.05, 3: 0.07, 4: 0.10, 5: 0.13, 6: 0.16 };
const CRIT_EFFECT = { 1: 0.10, 2: 0.15, 3: 0.20, 4: 0.25, 5: 0.30, 6: 0.40 };
const EXTEND_LIFE = { 1: 5, 2: 10, 3: 20, 4: 35, 5: 50, 6: 80 };
const BASE_ATTR = { 1: 3, 2: 5, 3: 8, 4: 12, 5: 16, 6: 20 };
const NURTURE_BASE = [50, 100, 200, 400, 800, 1600];      // [tier-1]

// ── 名称表（与 Kotlin 字面量逐值一致）────────────────────────────────
const NAMES = {
  speedCult: { 1: '引灵丹', 2: '聚灵丹', 3: '凝元丹', 4: '炼气丹', 5: '混元丹', 6: '仙灵丹' },
  skillSpeed: { 1: '悟法丹', 2: '通法丹', 3: '玄法丹', 4: '道法丹', 5: '天法丹', 6: '仙法丹' },
  nurtureSpeed: { 1: '养器丹', 2: '灵养丹', 3: '宝养丹', 4: '玄养丹', 5: '地养丹', 6: '天养丹' },
  cultAdd: { 1: '增元丹', 2: '培元丹', 3: '固元丹', 4: '真元丹', 5: '玄元丹', 6: '仙元丹' },
  skillAdd: { 1: '悟道丹', 2: '明心丹', 3: '通玄丹', 4: '慧灵丹', 5: '道悟丹', 6: '天机丹' },
  nurtureAdd: { 1: '蕴器丹', 2: '灵蕴丹', 3: '宝蕴丹', 4: '玄蕴丹', 5: '地蕴丹', 6: '天蕴丹' },
  extendLife: { 1: '延寿丹', 2: '续命丹', 3: '长生丹', 4: '不老丹', 5: '万寿丹', 6: '永生丹' },
  // 单属性战斗
  physicalAttack: { 1: '虎力丹', 2: '熊力丹', 3: '龙力丹', 4: '神力丹', 5: '霸力丹', 6: '天力丹' },
  magicAttack: { 1: '灵火丹', 2: '真火丹', 3: '三昧丹', 4: '玄火丹', 5: '地火丹', 6: '天火丹' },
  physicalDefense: { 1: '铁甲丹', 2: '铜墙丹', 3: '金刚丹', 4: '玄盾丹', 5: '地罡丹', 6: '天罡丹' },
  magicDefense: { 1: '灵盾丹', 2: '法盾丹', 3: '神盾丹', 4: '玄罡丹', 5: '地护丹', 6: '天护丹' },
  hp: { 1: '气血丹', 2: '血精丹', 3: '血魂丹', 4: '玄血丹', 5: '地血丹', 6: '天血丹' },
  mp: { 1: '回灵丹', 2: '汇灵丹', 3: '凝灵丹', 4: '玄灵丹', 5: '地灵丹', 6: '天灵丹' },
  speed: { 1: '疾风丹', 2: '迅风丹', 3: '神风丹', 4: '玄风丹', 5: '地风丹', 6: '天风丹' },
  // 双属性战斗
  physicalAttackDefense: { 1: '战体丹', 2: '战魂丹', 3: '战意丹', 4: '战心丹', 5: '战圣丹', 6: '战神丹' },
  magicAttackDefense: { 1: '法体丹', 2: '法魂丹', 3: '法意丹', 4: '法心丹', 5: '法圣丹', 6: '法神丹' },
  attackMixed: { 1: '双攻丹', 2: '灵攻丹', 3: '龙攻丹', 4: '玄攻丹', 5: '地攻丹', 6: '天攻丹' },
  defenseMixed: { 1: '双御丹', 2: '灵御丹', 3: '龙御丹', 4: '玄御丹', 5: '地御丹', 6: '天御丹' },
  hpMp: { 1: '生灵丹', 2: '命灵丹', 3: '元灵丹', 4: '真灵丹', 5: '混灵丹', 6: '圣灵丹' },
  attackSpeed: { 1: '疾攻丹', 2: '迅攻丹', 3: '神攻丹', 4: '玄攻丹', 5: '地攻丹', 6: '天攻丹' },
  magicSpeed: { 1: '疾法丹', 2: '迅法丹', 3: '神法丹', 4: '玄法丹', 5: '地法丹', 6: '天法丹' },
  // 暴击类
  critRate: { 1: '破击丹', 2: '锐击丹', 3: '必杀丹', 4: '玄击丹', 5: '绝杀丹', 6: '天击丹' },
  critEffect: { 1: '烈击丹', 2: '猛击丹', 3: '暴烈丹', 4: '玄烈丹', 5: '毁灭丹', 6: '天裂丹' },
  // 单基础属性功能
  intelligence: { 1: '慧根丹', 2: '灵慧丹', 3: '明慧丹', 4: '玄慧丹', 5: '地慧丹', 6: '天慧丹' },
  charm: { 1: '仙姿丹', 2: '灵姿丹', 3: '玉姿丹', 4: '玄姿丹', 5: '地姿丹', 6: '天姿丹' },
  loyalty: { 1: '忠心丹', 2: '赤诚丹', 3: '铁心丹', 4: '玄心丹', 5: '地心丹', 6: '天心丹' },
  comprehension: { 1: '悟道丹', 2: '明悟丹', 3: '通悟丹', 4: '玄悟丹', 5: '地悟丹', 6: '天悟丹' },
  artifactRefining: { 1: '铸魂丹', 2: '灵铸丹', 3: '宝铸丹', 4: '玄铸丹', 5: '地铸丹', 6: '天铸丹' },
  pillRefining: { 1: '丹心丹', 2: '灵丹丹', 3: '宝丹丹', 4: '玄丹丹', 5: '地丹丹', 6: '天丹丹' },
  spiritPlanting: { 1: '灵植丹', 2: '灵耘丹', 3: '宝耘丹', 4: '玄耘丹', 5: '地耘丹', 6: '天耘丹' },
  teaching: { 1: '传道丹', 2: '灵传丹', 3: '宝传丹', 4: '玄传丹', 5: '地传丹', 6: '天传丹' },
  morality: { 1: '善行丹', 2: '灵善丹', 3: '宝善丹', 4: '玄善丹', 5: '地善丹', 6: '天善丹' },
  mining: { 1: '探矿丹', 2: '灵石丹', 3: '宝矿丹', 4: '玄矿丹', 5: '地矿丹', 6: '天矿丹' },
  // 双基础属性功能
  intelligenceComprehension: { 1: '智悟丹', 2: '灵悟丹', 3: '明悟丹', 4: '玄悟丹', 5: '地悟丹', 6: '天悟丹' },
  charmLoyalty: { 1: '忠媚丹', 2: '灵忠丹', 3: '宝忠丹', 4: '玄忠丹', 5: '地忠丹', 6: '天忠丹' },
  pillRefiningArtifactRefining: { 1: '双炼丹', 2: '灵炼丹', 3: '宝炼丹', 4: '玄炼丹', 5: '地炼丹', 6: '天炼丹' },
  spiritPlantingTeaching: { 1: '师农丹', 2: '灵师丹', 3: '宝师丹', 4: '玄师丹', 5: '地师丹', 6: '天师丹' },
  intelligenceCharm: { 1: '智魅丹', 2: '灵魅丹', 3: '明魅丹', 4: '玄魅丹', 5: '地魅丹', 6: '天魅丹' },
  comprehensionMorality: { 1: '悟德丹', 2: '灵德丹', 3: '宝德丹', 4: '玄德丹', 5: '地德丹', 6: '天德丹' },
};

// 属性中文名（单属性战斗/单基础属性功能描述用）
const ATTR_CN = {
  physicalAttack: '物攻', magicAttack: '法攻', physicalDefense: '物防', magicDefense: '法防',
  hp: '生命', mp: '灵力', speed: '速度',
  intelligence: '智力', charm: '魅力', loyalty: '忠诚', comprehension: '悟性',
  artifactRefining: '炼器', pillRefining: '炼丹', spiritPlanting: '种植',
  teaching: '教学', morality: '道德', mining: '采矿',
};

// 双属性配置（attr1/attr2/descName 与 Kotlin DualAttrConfig 一致）
const DUAL_CFG = {
  physicalAttackDefense: ['physicalAttack', 'physicalDefense', '物攻物防'],
  magicAttackDefense: ['magicAttack', 'magicDefense', '法攻法防'],
  attackMixed: ['physicalAttack', 'magicAttack', '物法双攻'],
  defenseMixed: ['physicalDefense', 'magicDefense', '物法双防'],
  hpMp: ['hp', 'mp', '生命灵力'],
  attackSpeed: ['physicalAttack', 'speed', '攻速'],
  magicSpeed: ['magicAttack', 'speed', '法速'],
};
const DUAL_BASE_CFG = {
  intelligenceComprehension: ['intelligence', 'comprehension', '智悟'],
  charmLoyalty: ['charm', 'loyalty', '魅忠'],
  pillRefiningArtifactRefining: ['pillRefining', 'artifactRefining', '炼丹炼器'],
  spiritPlantingTeaching: ['spiritPlanting', 'teaching', '种植教学'],
  intelligenceCharm: ['intelligence', 'charm', '智魅'],
  comprehensionMorality: ['comprehension', 'morality', '悟德'],
};

// 突破丹（tier → [targetRealm, 名称]）
const BREAKTHROUGH_TIERS = [
  [1, [[9, '聚气丹']]],
  [2, [[8, '筑基丹'], [7, '凝金丹'], [6, '结婴丹']]],
  [3, [[5, '化神丹'], [4, '破虚丹']]],
  [5, [[3, '合道丹']]],
  [6, [[2, '大乘丹'], [1, '渡劫丹'], [0, '登仙丹']]],
];

// 境界名称（GameConfig.Realm.getName）
const REALM_NAME = {
  9: '炼气', 8: '筑基', 7: '金丹', 6: '元婴', 5: '化神', 4: '炼虚',
  3: '合体', 2: '大乘', 1: '渡劫', 0: '仙人',
};

// ── PillTemplate 生成（复刻 ItemDatabase.kt）─────────────────────────
const pillTemplates = [];
const mkPill = (id, name, description, fields) => {
  pillTemplates.push({
    id, name, description,
    breakthroughChance: fields.breakthroughChance ?? 0.0,
    targetRealm: fields.targetRealm ?? 0,
    cultivationSpeedPercent: fields.cultivationSpeedPercent ?? 0.0,
    skillExpSpeedPercent: fields.skillExpSpeedPercent ?? 0.0,
    nurtureSpeedPercent: fields.nurtureSpeedPercent ?? 0.0,
    cultivationAdd: fields.cultivationAdd ?? 0,
    skillExpAdd: fields.skillExpAdd ?? 0,
    nurtureAdd: fields.nurtureAdd ?? 0,
    physicalAttackAdd: fields.physicalAttackAdd ?? 0,
    magicAttackAdd: fields.magicAttackAdd ?? 0,
    physicalDefenseAdd: fields.physicalDefenseAdd ?? 0,
    magicDefenseAdd: fields.magicDefenseAdd ?? 0,
    hpAdd: fields.hpAdd ?? 0,
    mpAdd: fields.mpAdd ?? 0,
    speedAdd: fields.speedAdd ?? 0,
    critRateAdd: fields.critRateAdd ?? 0.0,
    critEffectAdd: fields.critEffectAdd ?? 0.0,
    extendLife: fields.extendLife ?? 0,
    intelligenceAdd: fields.intelligenceAdd ?? 0,
    charmAdd: fields.charmAdd ?? 0,
    loyaltyAdd: fields.loyaltyAdd ?? 0,
    comprehensionAdd: fields.comprehensionAdd ?? 0,
    artifactRefiningAdd: fields.artifactRefiningAdd ?? 0,
    pillRefiningAdd: fields.pillRefiningAdd ?? 0,
    spiritPlantingAdd: fields.spiritPlantingAdd ?? 0,
    teachingAdd: fields.teachingAdd ?? 0,
    moralityAdd: fields.moralityAdd ?? 0,
    miningAdd: fields.miningAdd ?? 0,
  });
};

// 修炼速度/加值丹
for (let tier = 1; tier <= 6; tier++) {
  const speedPct = SPEED_PCT[tier];
  const tierName = TIER_NAMES[tier];
  for (let g = 0; g < 3; g++) {
    const mult = GRADE_MULT[g];
    const gl = GRADE_LOWER[g];
    const gn = GRADE_DISPLAY[g];
    const pct = rint((speedPct * mult) * 100);
    mkPill(`cultivationSpeed_${tier}_${gl}`, NAMES.speedCult[tier],
      `${tierName}${gn}修炼速度丹，提升境界修炼速度${pct}%，持续9旬`,
      { cultivationSpeedPercent: speedPct * mult });
    mkPill(`skillExpSpeed_${tier}_${gl}`, NAMES.skillSpeed[tier],
      `${tierName}${gn}功法速度丹，提升功法熟练度修炼速度${pct}%，持续9旬`,
      { skillExpSpeedPercent: speedPct * mult });
    mkPill(`nurtureSpeed_${tier}_${gl}`, NAMES.nurtureSpeed[tier],
      `${tierName}${gn}孕养速度丹，提升装备孕养等级修炼速度${pct}%，持续9旬`,
      { nurtureSpeedPercent: speedPct * mult });
  }
}
for (let tier = 1; tier <= 6; tier++) {
  const cultBase = rint(CULT_BASE[tier] * 0.375);
  const skillBase = rint(CULT_BASE[tier] * 0.1);
  const nurtureBase = NURTURE_BASE[tier - 1];
  const tierName = TIER_NAMES[tier];
  for (let g = 0; g < 3; g++) {
    const mult = GRADE_MULT[g];
    const gl = GRADE_LOWER[g];
    const gn = GRADE_DISPLAY[g];
    const cultAddVal = rint(cultBase * mult);
    const skillAddVal = rint(skillBase * mult);
    const nurtureAddVal = rint(nurtureBase * mult);
    mkPill(`cultivationAdd_${tier}_${gl}`, NAMES.cultAdd[tier],
      `${tierName}${gn}境界修为丹，立即增加${cultAddVal}点境界修为`,
      { cultivationAdd: cultAddVal });
    mkPill(`skillExpAdd_${tier}_${gl}`, NAMES.skillAdd[tier],
      `${tierName}${gn}功法熟练丹，立即增加${skillAddVal}点功法熟练度`,
      { skillExpAdd: skillAddVal });
    mkPill(`nurtureAdd_${tier}_${gl}`, NAMES.nurtureAdd[tier],
      `${tierName}${gn}孕养度丹，立即增加${nurtureAddVal}点装备孕养度`,
      { nurtureAdd: nurtureAddVal });
  }
}
// 突破丹
for (const [tier, targets] of BREAKTHROUGH_TIERS) {
  for (const [targetRealm, name] of targets) {
    for (let g = 0; g < 3; g++) {
      const chance = BREAK_CHANCE_BY_GRADE[g];
      const pct = rint(chance * 100);
      mkPill(`breakthrough_${targetRealm}_${GRADE_LOWER[g]}`, name,
        `增加${REALM_NAME[targetRealm]}期突破成功率${pct}%`,
        { breakthroughChance: chance, targetRealm });
    }
  }
}
// 单属性战斗
for (const [pillType, attrName] of Object.entries(ATTR_CN)) {
  if (!['physicalAttack', 'magicAttack', 'physicalDefense', 'magicDefense', 'hp', 'mp', 'speed'].includes(pillType)) continue;
  const refMap = { physicalAttack: REF_BASE.pa, magicAttack: REF_BASE.ma, physicalDefense: REF_BASE.pd, magicDefense: REF_BASE.md, hp: REF_BASE.hp, mp: REF_BASE.mp, speed: REF_BASE.spd }[pillType];
  for (let tier = 1; tier <= 6; tier++) {
    const mediumVal = rint(refMap[tier] * 0.375);
    const tierName = TIER_NAMES[tier];
    for (let g = 0; g < 3; g++) {
      const mult = GRADE_MULT[g];
      const val = rint(mediumVal * mult);
      const fields = {};
      fields[`${pillType}Add`] = val;
      mkPill(`${pillType}_${tier}_${GRADE_LOWER[g]}`, NAMES[pillType][tier],
        `${tierName}${GRADE_DISPLAY[g]}${attrName}丹，增加${val}点${attrName}，持续9旬`, fields);
    }
  }
}
// 双属性战斗（格式陷阱：描述用英文属性键）
for (const [pillType, [attr1, attr2, descName]] of Object.entries(DUAL_CFG)) {
  const ref1 = { physicalAttack: REF_BASE.pa, magicAttack: REF_BASE.ma, physicalDefense: REF_BASE.pd, magicDefense: REF_BASE.md, hp: REF_BASE.hp, mp: REF_BASE.mp, speed: REF_BASE.spd }[attr1];
  const ref2 = { physicalAttack: REF_BASE.pa, magicAttack: REF_BASE.ma, physicalDefense: REF_BASE.pd, magicDefense: REF_BASE.md, hp: REF_BASE.hp, mp: REF_BASE.mp, speed: REF_BASE.spd }[attr2];
  for (let tier = 1; tier <= 6; tier++) {
    const med1 = rint(ref1[tier] * 0.375 * 0.6);
    const med2 = rint(ref2[tier] * 0.375 * 0.6);
    const tierName = TIER_NAMES[tier];
    for (let g = 0; g < 3; g++) {
      const mult = GRADE_MULT[g];
      const v1 = rint(med1 * mult);
      const v2 = rint(med2 * mult);
      const attrVal = (a) => (a === attr1 ? v1 : a === attr2 ? v2 : 0);
      const fields = {};
      for (const a of ['physicalAttack', 'magicAttack', 'physicalDefense', 'magicDefense', 'hp', 'mp', 'speed']) {
        fields[`${a}Add`] = attrVal(a);
      }
      mkPill(`${pillType}_${tier}_${GRADE_LOWER[g]}`, NAMES[pillType][tier],
        `${tierName}${GRADE_DISPLAY[g]}${descName}丹，增加${v1}点${attr1}和${v2}点${attr2}，持续9旬`, fields);
    }
  }
}
// 暴击类
for (let tier = 1; tier <= 6; tier++) {
  const tierName = TIER_NAMES[tier];
  for (let g = 0; g < 3; g++) {
    const mult = GRADE_MULT[g];
    const cr = CRIT_RATE[tier] * mult;
    mkPill(`critRate_${tier}_${GRADE_LOWER[g]}`, NAMES.critRate[tier],
      `${tierName}${GRADE_DISPLAY[g]}暴击率丹，增加${rint(cr * 100)}%暴击率，持续9旬`,
      { critRateAdd: cr });
    const ce = CRIT_EFFECT[tier] * mult;
    mkPill(`critEffect_${tier}_${GRADE_LOWER[g]}`, NAMES.critEffect[tier],
      `${tierName}${GRADE_DISPLAY[g]}暴击效果丹，增加${rint(ce * 100)}%暴击效果，持续9旬`,
      { critEffectAdd: ce });
  }
}
// 延寿丹
for (let tier = 1; tier <= 6; tier++) {
  const tierName = TIER_NAMES[tier];
  for (let g = 0; g < 3; g++) {
    const lifeVal = rint(EXTEND_LIFE[tier] * GRADE_MULT[g]);
    mkPill(`extendLife_${tier}_${GRADE_LOWER[g]}`, NAMES.extendLife[tier],
      `${tierName}${GRADE_DISPLAY[g]}延寿丹，增加${lifeVal}年寿元`,
      { extendLife: lifeVal });
  }
}
// 单基础属性功能
for (const [pillType, attrName] of Object.entries(ATTR_CN)) {
  if (['physicalAttack', 'magicAttack', 'physicalDefense', 'magicDefense', 'hp', 'mp', 'speed'].includes(pillType)) continue;
  for (let tier = 1; tier <= 6; tier++) {
    const tierName = TIER_NAMES[tier];
    for (let g = 0; g < 3; g++) {
      const val = rint(BASE_ATTR[tier] * GRADE_MULT[g]);
      const fields = {};
      fields[`${pillType}Add`] = val;
      mkPill(`${pillType}_${tier}_${GRADE_LOWER[g]}`, NAMES[pillType][tier],
        `${tierName}${GRADE_DISPLAY[g]}${attrName}丹，永久增加${val}点${attrName}`, fields);
    }
  }
}
// 双基础属性功能（格式陷阱：描述用英文属性键；miningAdd 不参与）
for (const [pillType, [attr1, attr2, descName]] of Object.entries(DUAL_BASE_CFG)) {
  for (let tier = 1; tier <= 6; tier++) {
    const med = BASE_ATTR[tier];
    const tierName = TIER_NAMES[tier];
    for (let g = 0; g < 3; g++) {
      const mult = GRADE_MULT[g];
      const v1 = rint(rint(med * 0.6) * mult);
      const v2 = v1;
      const attrVal = (a) => (a === attr1 ? v1 : a === attr2 ? v2 : 0);
      const fields = {};
      for (const a of ['intelligence', 'charm', 'loyalty', 'comprehension', 'artifactRefining', 'pillRefining', 'spiritPlanting', 'teaching', 'morality']) {
        fields[`${a}Add`] = attrVal(a);
      }
      mkPill(`${pillType}_${tier}_${GRADE_LOWER[g]}`, NAMES[pillType][tier],
        `${tierName}${GRADE_DISPLAY[g]}${descName}丹，永久增加${v1}点${attr1}和${v2}点${attr2}`, fields);
    }
  }
}

const pillById = new Map(pillTemplates.map((t) => [t.id, t]));

// ── 锻造配方（复刻 ForgeRecipeDatabase.kt 静态字面量）─────────────────
const FORGE_RECIPES = [
  // tier 1
  ['ironSword', '精铁剑', 'WEAPON', 1, 1, '普通铁匠打造的精铁剑', { tigerBlood0: 3, tigerTooth0: 2 }, 3, 0.70],
  ['bronzeDagger', '精铁刀', 'WEAPON', 1, 1, '精铁锻造的宝刀', { tigerTooth0: 4, eagleClaw0: 2 }, 3, 0.70],
  ['woodenStaff', '桃木杖', 'WEAPON', 1, 1, '百年桃木制成的法杖', { snakeBlood0: 3, snakeCore0: 2 }, 3, 0.70],
  ['crystalOrb', '碧木扇', 'WEAPON', 1, 1, '蕴含微量灵气的碧木扇', { snakeCore0: 3, foxCore0: 2 }, 3, 0.70],
  ['leatherArmor', '皮甲', 'ARMOR', 1, 1, '野兽皮革制成的护甲', { bearHide0: 4, bearBone0: 2 }, 3, 0.70],
  ['chainMail', '锁子甲', 'ARMOR', 1, 1, '铁环相扣的护甲', { bearBone0: 5, bearHide0: 2 }, 3, 0.70],
  ['bronzePlate', '精铁甲', 'ARMOR', 1, 1, '精铁铸造的铠甲', { turtleShell0: 4, turtleBone0: 2 }, 3, 0.70],
  ['clothRobe', '灵竹衣', 'ARMOR', 1, 1, '灵竹纤维制成的衣物', { turtleShell0: 3, snakeScale0: 2 }, 3, 0.70],
  ['clothBoots', '青澜靴', 'BOOTS', 1, 1, '青澜丝线织就的轻靴', { wolfHide0: 3, wolfBone0: 2 }, 3, 0.70],
  ['leatherBoots', '兽皮靴', 'BOOTS', 1, 1, '兽皮鞣制的厚靴', { wolfHide0: 4, wolfTooth0: 1 }, 3, 0.70],
  ['jadeRing', '玉戒指', 'ACCESSORY', 1, 1, '蕴含微量灵气的玉戒指', { foxCore0: 2, foxBone0: 3 }, 3, 0.70],
  ['copperNecklace', '铜项链', 'ACCESSORY', 1, 1, '铜制项链', { foxBone0: 3, foxTail0: 2 }, 3, 0.70],
  // tier 2
  ['spiritSword', '灵锋剑', 'WEAPON', 2, 2, '注入灵气的锋利长剑', { tigerBlood1: 4, tigerTooth1: 3 }, 6, 0.65],
  ['battleAxe', '凌华刀', 'WEAPON', 2, 2, '刀光凌厉如华', { tigerBlood1: 5, eagleClaw1: 2 }, 6, 0.65],
  ['jadeStaff', '碧玉杖', 'WEAPON', 2, 2, '碧玉雕刻的法杖', { snakeBlood1: 4, snakeCore1: 2 }, 6, 0.65],
  ['spiritFan', '灵风扇', 'WEAPON', 2, 2, '可扇出灵风的法器', { eagleFeather1: 4, snakeCore1: 2 }, 6, 0.65],
  ['ironPlate', '碧叶甲', 'ARMOR', 2, 2, '碧玉叶片打造的护甲', { bearHide1: 5, bearBone1: 2 }, 6, 0.65],
  ['steelArmor', '丹羽衣', 'ARMOR', 2, 2, '丹砂羽线织成的法衣', { bearBone1: 4, bearCore1: 2 }, 6, 0.65],
  ['spiritRobe', '灵丝袍', 'ARMOR', 2, 2, '灵蚕丝织成的法袍', { turtleShell1: 4, snakeScale1: 2 }, 6, 0.65],
  ['cloudRobe', '云纹袍', 'ARMOR', 2, 2, '绣有云纹的法袍', { turtleShell1: 3, turtleBone1: 3 }, 6, 0.65],
  ['swiftBoots', '疾风靴', 'BOOTS', 2, 2, '穿上可大幅提升移动速度', { wolfHide1: 4, wolfBone1: 2 }, 6, 0.65],
  ['lightBoots', '轻羽靴', 'BOOTS', 2, 2, '如羽毛般轻盈', { wolfHide1: 3, eagleFeather1: 3 }, 6, 0.65],
  ['spiritPendant', '灵玉佩', 'ACCESSORY', 2, 2, '蕴含灵气的玉佩', { foxCore1: 3, foxBone1: 3 }, 6, 0.65],
  ['healthRing', '蕴灵戒', 'ACCESSORY', 2, 2, '可蕴养灵力的戒指', { wolfTooth1: 3, foxTail1: 2 }, 6, 0.65],
  // tier 3
  ['frostBlade', '青碧刃', 'WEAPON', 3, 3, '蕴含青碧灵力的宝刀', { tigerBlood2: 5, snakeScale2: 3, tigerTooth2: 2 }, 12, 0.60],
  ['flameSword', '烈焰剑', 'WEAPON', 3, 3, '燃烧着火焰的灵剑', { tigerBlood2: 4, tigerCore2: 2, tigerTooth2: 3 }, 12, 0.60],
  ['thunderStaff', '玄雷杖', 'WEAPON', 3, 3, '可召唤雷电的法杖', { snakeBlood2: 4, snakeCore2: 3, eagleFeather2: 2 }, 12, 0.60],
  ['frostOrb', '玄冰扇', 'WEAPON', 3, 3, '蕴含玄冰之力的宝扇', { snakeCore2: 4, foxCore2: 2, snakeBlood2: 2 }, 12, 0.60],
  ['scaleArmor', '青鳞铠', 'ARMOR', 3, 3, '妖兽青鳞打造的铠甲', { snakeScale2: 5, snakeBlood2: 3, snakeCore2: 2 }, 12, 0.60],
  ['plateArmor', '银板铠', 'ARMOR', 3, 3, '厚重的银板护甲', { bearHide2: 4, bearBone2: 3, bearCore2: 2 }, 12, 0.60],
  ['mysticRobe', '汐流衣', 'ARMOR', 3, 3, '蕴含汐流之力的法衣', { turtleShell2: 5, turtleBone2: 3, turtleCore2: 2 }, 12, 0.60],
  ['starRobe', '星辰袍', 'ARMOR', 3, 3, '绣有星辰图案的法袍', { bearHide2: 3, snakeScale2: 3, bearCore2: 2 }, 12, 0.60],
  ['windBoots', '追风靴', 'BOOTS', 3, 3, '追逐风的速度', { wolfHide2: 4, wolfBone2: 3, wolfCore2: 2 }, 12, 0.60],
  ['mistBoots', '云栖靴', 'BOOTS', 3, 3, '云栖之处步履轻盈', { wolfHide2: 4, eagleFeather2: 2, wolfTooth2: 2 }, 12, 0.60],
  ['storageRing', '灵泉戒', 'ACCESSORY', 3, 3, '蕴含灵泉之力的戒指', { foxCore2: 4, foxBone2: 3, foxTail2: 2 }, 12, 0.60],
  ['wisdomOrb', '迅捷珠', 'ACCESSORY', 3, 3, '可提升身法速度的宝珠', { wolfTooth2: 4, eagleClaw2: 2, foxCore2: 2 }, 12, 0.60],
  // tier 4
  ['thunderSword', '雷霆剑', 'WEAPON', 4, 4, '引动天雷的玄妙飞剑', { tigerBlood3: 6, tigerHide3: 4, tigerCore3: 2 }, 36, 0.35],
  ['shadowBlade', '暗影刃', 'WEAPON', 4, 4, '融入暗影的短刃', { tigerBlood3: 5, eagleClaw3: 4, foxCore3: 2 }, 36, 0.35],
  ['voidStaff', '虚华杖', 'WEAPON', 4, 4, '虚华流转的玄妙法杖', { snakeBlood3: 5, snakeCore3: 4, foxCore3: 2 }, 36, 0.35],
  ['phoenixFan', '凰焰扇', 'WEAPON', 4, 4, '凰焰淬炼的神扇', { eagleFeather3: 6, eagleClaw3: 3, eagleCore3: 2 }, 36, 0.35],
  ['dragonScale', '龙鳞铠', 'ARMOR', 4, 4, '真龙鳞片锻造的铠甲', { snakeScale3: 6, snakeBlood3: 4, snakeCore3: 2 }, 36, 0.35],
  ['titanArmor', '渊岩铠', 'ARMOR', 4, 4, '深渊岩铁铸造的铠甲', { bearHide3: 5, bearBone3: 4, bearCore3: 3 }, 36, 0.35],
  ['voidRobe', '瑶光袍', 'ARMOR', 4, 4, '蕴含瑶光之力的法袍', { turtleShell3: 6, turtleBone3: 4, turtleCore3: 2 }, 36, 0.35],
  ['moonRobe', '月华袍', 'ARMOR', 4, 4, '吸收月华之力织成的法袍', { bearHide3: 5, snakeScale3: 3, bearCore3: 3 }, 36, 0.35],
  ['cloudBoots', '踏云履', 'BOOTS', 4, 4, '踏云而行的仙家法宝', { wolfHide3: 5, eagleFeather3: 4, wolfCore3: 2 }, 36, 0.35],
  ['thunderBoots', '奔雷靴', 'BOOTS', 4, 4, '如雷电般迅捷的靴子', { wolfHide3: 5, wolfBone3: 3, wolfCore3: 3 }, 36, 0.35],
  ['dragonEye', '龙灵珠', 'ACCESSORY', 4, 4, '真龙之灵凝聚的宝珠', { foxCore3: 5, foxBone3: 4, foxHide3: 2 }, 36, 0.35],
  ['phoenixHeart', '凤羽坠', 'ACCESSORY', 4, 4, '凤凰羽翼炼制的坠饰', { wolfTooth3: 5, eagleClaw3: 3, foxTail3: 3 }, 36, 0.35],
  // tier 5
  ['dragonSlayer', '凤炎刃', 'WEAPON', 5, 5, '蕴含凤炎之力的绝世神兵', { tigerBlood4: 6, tigerTooth4: 4, tigerCore4: 2, dragonHorn4: 2 }, 72, 0.30],
  ['godSlayer', '青莲剑', 'WEAPON', 5, 5, '自青莲中诞生的神剑', { tigerBlood4: 5, tigerTooth4: 4, eagleClaw4: 3, tigerCore4: 2 }, 72, 0.30],
  ['phoenixWing', '阴阳扇', 'WEAPON', 5, 5, '蕴含阴阳之力的神扇', { eagleFeather4: 6, eagleClaw4: 4, eagleCore4: 2, snakeCore4: 2 }, 72, 0.30],
  ['celestialOrb', '天玄杖', 'WEAPON', 5, 5, '蕴含天玄之力的神杖', { snakeBlood4: 5, snakeCore4: 4, foxCore4: 2, dragonCore4: 2 }, 72, 0.30],
  ['earthArmor', '玄幽袍', 'ARMOR', 5, 5, '承载玄幽之力的法袍', { snakeScale4: 6, snakeBlood4: 4, snakeCore4: 2, dragonScale4: 2 }, 72, 0.30],
  ['divinePlate', '墨幽铠', 'ARMOR', 5, 5, '墨幽玄铁铸造的铠甲', { bearHide4: 6, bearBone4: 4, bearClaw4: 2, bearCore4: 2 }, 72, 0.30],
  ['celestialRobe', '凌星袍', 'ARMOR', 5, 5, '凌驾星辰之力的法袍', { turtleShell4: 6, turtleBone4: 4, turtleCore4: 2, dragonScale4: 2 }, 72, 0.30],
  ['voidShadowRobe', '定海铠', 'ARMOR', 5, 5, '定海之力凝聚的铠甲', { bearHide4: 5, snakeScale4: 3, bearCore4: 3, foxCore4: 2 }, 72, 0.30],
  ['voidBoots', '溯光靴', 'BOOTS', 5, 5, '溯光逐影穿梭虚空', { wolfHide4: 5, wolfTooth4: 4, wolfCore4: 2, dragonScale4: 2 }, 72, 0.30],
  ['shadowStepBoots', '赤煞靴', 'BOOTS', 5, 5, '赤煞之气凝聚的战靴', { wolfHide4: 5, eagleClaw4: 3, wolfCore4: 2, foxTail4: 2 }, 72, 0.30],
  ['earthCore', '渡厄佩', 'ACCESSORY', 5, 5, '可渡厄解难的灵佩', { foxCore4: 6, foxBone4: 4, foxHide4: 2, dragonCore4: 2 }, 72, 0.30],
  ['dragonEyePendant', '隐云佩', 'ACCESSORY', 5, 5, '隐于云端的灵佩', { wolfTooth4: 5, eagleClaw4: 4, foxTail4: 2, wolfCore4: 2 }, 72, 0.30],
  // tier 6
  ['immortalSword', '诛仙剑', 'WEAPON', 6, 6, '上古仙人遗留的仙器', { tigerBlood5: 8, tigerTooth5: 5, tigerCore5: 3, dragonHorn5: 3 }, 120, 0.25],
  ['chaosBlade', '玄玉刃', 'WEAPON', 6, 6, '玄玉淬炼的神刃', { tigerBlood5: 6, tigerTooth5: 5, eagleClaw5: 4, tigerCore5: 3 }, 120, 0.25],
  ['primordialStaff', '天星杖', 'WEAPON', 6, 6, '凝聚天星之力的法杖', { snakeBlood5: 7, snakeCore5: 5, eagleFeather5: 3, dragonCore5: 3 }, 120, 0.25],
  ['yinYangOrb', '天玄扇', 'WEAPON', 6, 6, '蕴含天玄道韵的至宝', { snakeBlood5: 6, snakeCore5: 5, foxCore5: 3, dragonCore5: 3 }, 120, 0.25],
  ['immortalArmor', '不朽铠', 'ARMOR', 6, 6, '仙界神甲', { snakeScale5: 8, snakeBlood5: 5, snakeCore5: 3, dragonScale5: 3 }, 120, 0.25],
  ['primordialArmor', '苍罡铠', 'ARMOR', 6, 6, '苍罡之力凝聚的神甲', { bearHide5: 8, bearBone5: 5, bearCore5: 3, dragonClaw5: 3 }, 120, 0.25],
  ['immortalRobe', '曦光铠', 'ARMOR', 6, 6, '蕴含曦光之力的铠甲', { turtleShell5: 8, turtleBone5: 5, turtleCore5: 3, dragonScale5: 3 }, 120, 0.25],
  ['chaosRobe', '云影袍', 'ARMOR', 6, 6, '云影交织的法袍', { bearHide5: 6, snakeScale5: 4, bearCore5: 4, dragonClaw5: 3 }, 120, 0.25],
  ['immortalBoots', '鸾羽履', 'BOOTS', 6, 6, '鸾鸟仙羽织就的灵履', { wolfHide5: 7, wolfTooth5: 5, wolfCore5: 3, dragonScale5: 3 }, 120, 0.25],
  ['chaosStepBoots', '鹤岚靴', 'BOOTS', 6, 6, '鹤翔岚雾而行', { wolfHide5: 6, eagleClaw5: 4, wolfCore5: 4, dragonClaw5: 3 }, 120, 0.25],
  ['chaosBead', '幽朔珠', 'ACCESSORY', 6, 6, '蕴含幽朔之力的灵珠', { foxCore5: 8, foxBone5: 5, foxHide5: 3, dragonCore5: 3 }, 120, 0.25],
  ['heavenRing', '长明坠', 'ACCESSORY', 6, 6, '长明不灭的灵坠', { wolfTooth5: 7, eagleClaw5: 5, foxTail5: 3, dragonScale5: 3 }, 120, 0.25],
];

const forgeRecipes = FORGE_RECIPES.map(([id, name, type, tier, rarity, description, materials, duration, successRate]) => ({
  id, name, type, tier, rarity, description,
  materials: sortedMats(materials), duration, successRate,
}));

// ── 丹药配方生成（复刻 PillRecipeDatabase.kt）─────────────────────────
const TIER_DURATION = { 1: 3, 2: 6, 3: 12, 4: 36, 5: 72, 6: 120 };
const TIER_SUCCESS_RATE = { 1: 0.75, 2: 0.65, 3: 0.60, 4: 0.45, 5: 0.35, 6: 0.20 };
const TIER_HERB_IDS = {
  1: ['spiritGrass1', 'spiritGrass2', 'spiritGrass3', 'spiritFlower1', 'spiritFlower2', 'spiritFlower3', 'spiritFruit1', 'spiritFruit2', 'spiritFruit3'],
  2: ['spiritGrass4', 'spiritGrass5', 'spiritGrass6', 'spiritFlower4', 'spiritFlower5', 'spiritFlower6', 'spiritFruit4', 'spiritFruit5', 'spiritFruit6'],
  3: ['spiritGrass7', 'spiritGrass8', 'spiritGrass9', 'spiritFlower7', 'spiritFlower8', 'spiritFlower9', 'spiritFruit7', 'spiritFruit8', 'spiritFruit9'],
  4: ['spiritGrass10', 'spiritGrass11', 'spiritGrass12', 'spiritFlower10', 'spiritFlower11', 'spiritFlower12', 'spiritFruit10', 'spiritFruit11', 'spiritFruit12'],
  5: ['spiritGrass13', 'spiritGrass14', 'spiritGrass15', 'spiritFlower13', 'spiritFlower14', 'spiritFlower15', 'spiritFruit13', 'spiritFruit14', 'spiritFruit15'],
  6: ['spiritGrass16', 'spiritGrass17', 'spiritGrass18', 'spiritFlower16', 'spiritFlower17', 'spiritFlower18', 'spiritFruit16', 'spiritFruit17', 'spiritFruit18'],
};

const herbMat = (tier, indices) => {
  const herbs = TIER_HERB_IDS[tier];
  const result = {};
  for (const idx of indices) {
    const herbId = herbs[Math.min(Math.max(idx, 0), herbs.length - 1)];
    result[herbId] = (result[herbId] ?? 0) + 2;
  }
  return result;
};

const pillRecipes = [];
const recipeFields = () => ({
  breakthroughChance: 0.0, targetRealm: 0,
  cultivationSpeedPercent: 0.0, skillExpSpeedPercent: 0.0, nurtureSpeedPercent: 0.0,
  cultivationAdd: 0, skillExpAdd: 0, nurtureAdd: 0,
  physicalAttackAdd: 0, magicAttackAdd: 0, physicalDefenseAdd: 0, magicDefenseAdd: 0,
  hpAdd: 0, mpAdd: 0, speedAdd: 0, critRateAdd: 0.0, critEffectAdd: 0.0,
  extendLife: 0, intelligenceAdd: 0, charmAdd: 0, loyaltyAdd: 0, comprehensionAdd: 0,
  artifactRefiningAdd: 0, pillRefiningAdd: 0, spiritPlantingAdd: 0, teachingAdd: 0,
  moralityAdd: 0, miningAdd: 0,
});
const mkRecipe = (tpl, tier, category, pillType, materials, breakthroughChance = 0.0, targetRealm = 0) => {
  pillRecipes.push({
    id: tpl.id, name: tpl.name, tier, rarity: tier, category, grade: tpl.id.split('_').pop(),
    pillType, description: tpl.description, materials: sortedMats(materials),
    duration: TIER_DURATION[tier], successRate: TIER_SUCCESS_RATE[tier],
    breakthroughChance, targetRealm,
    cultivationSpeedPercent: tpl.cultivationSpeedPercent, skillExpSpeedPercent: tpl.skillExpSpeedPercent,
    nurtureSpeedPercent: tpl.nurtureSpeedPercent, cultivationAdd: tpl.cultivationAdd,
    skillExpAdd: tpl.skillExpAdd, nurtureAdd: tpl.nurtureAdd,
    physicalAttackAdd: tpl.physicalAttackAdd, magicAttackAdd: tpl.magicAttackAdd,
    physicalDefenseAdd: tpl.physicalDefenseAdd, magicDefenseAdd: tpl.magicDefenseAdd,
    hpAdd: tpl.hpAdd, mpAdd: tpl.mpAdd, speedAdd: tpl.speedAdd,
    critRateAdd: tpl.critRateAdd, critEffectAdd: tpl.critEffectAdd, extendLife: tpl.extendLife,
    intelligenceAdd: tpl.intelligenceAdd, charmAdd: tpl.charmAdd, loyaltyAdd: tpl.loyaltyAdd,
    comprehensionAdd: tpl.comprehensionAdd, artifactRefiningAdd: tpl.artifactRefiningAdd,
    pillRefiningAdd: tpl.pillRefiningAdd, spiritPlantingAdd: tpl.spiritPlantingAdd,
    teachingAdd: tpl.teachingAdd, moralityAdd: tpl.moralityAdd, miningAdd: tpl.miningAdd,
  });
};

// 常规修炼配方
const STANDARD_PILL_TYPES = ['cultivationSpeed', 'skillExpSpeed', 'nurtureSpeed', 'cultivationAdd', 'skillExpAdd', 'nurtureAdd'];
const HERB_PATTERNS = [[0, 3], [1, 6], [2, 4], [0, 7], [5, 8], [3, 7]];
for (let tier = 1; tier <= 6; tier++) {
  for (let idx = 0; idx < STANDARD_PILL_TYPES.length; idx++) {
    const materials = herbMat(tier, HERB_PATTERNS[idx]);
    for (const grade of GRADE_LOWER) {
      const tpl = pillById.get(`${STANDARD_PILL_TYPES[idx]}_${tier}_${grade}`);
      if (!tpl) continue;
      mkRecipe(tpl, tier, 'CULTIVATION', STANDARD_PILL_TYPES[idx], materials);
    }
  }
}
// 突破配方
const EXPLICIT_BREAKTHROUGH = { 9: { spiritGrass2: 2, spiritFruit2: 2 } };
for (const [tier, targets] of BREAKTHROUGH_TIERS) {
  const herbs = TIER_HERB_IDS[tier];
  for (let idx = 0; idx < targets.length; idx++) {
    const [targetRealm] = targets[idx];
    let materials = EXPLICIT_BREAKTHROUGH[targetRealm]
      ? { ...EXPLICIT_BREAKTHROUGH[targetRealm] }
      : { [herbs[(idx * 2) % herbs.length]]: 2, [herbs[(idx * 2 + 3) % herbs.length]]: 2 };
    if (tier >= 3) {
      materials[herbs[(idx * 2 + 5) % herbs.length]] = 2;
    }
    for (const grade of GRADE_LOWER) {
      const tpl = pillById.get(`breakthrough_${targetRealm}_${grade}`);
      if (!tpl) continue;
      mkRecipe(tpl, tier, 'CULTIVATION', 'breakthrough', materials, tpl.breakthroughChance, targetRealm);
    }
  }
}
// 战斗类
const SINGLE_BATTLE_TYPES = ['physicalAttack', 'magicAttack', 'physicalDefense', 'magicDefense', 'hp', 'mp', 'speed'];
for (let tier = 1; tier <= 6; tier++) {
  const herbs = TIER_HERB_IDS[tier];
  for (let idx = 0; idx < SINGLE_BATTLE_TYPES.length; idx++) {
    const materials = { [herbs[idx % herbs.length]]: 2, [herbs[(idx + 4) % herbs.length]]: 2 };
    for (const grade of GRADE_LOWER) {
      const tpl = pillById.get(`${SINGLE_BATTLE_TYPES[idx]}_${tier}_${grade}`);
      if (!tpl) continue;
      mkRecipe(tpl, tier, 'BATTLE', SINGLE_BATTLE_TYPES[idx], materials);
    }
  }
  for (let idx = 0; idx < Object.keys(DUAL_CFG).length; idx++) {
    const pillType = Object.keys(DUAL_CFG)[idx];
    const materials = { [herbs[idx % herbs.length]]: 2, [herbs[(idx + 3) % herbs.length]]: 2 };
    for (const grade of GRADE_LOWER) {
      const tpl = pillById.get(`${pillType}_${tier}_${grade}`);
      if (!tpl) continue;
      mkRecipe(tpl, tier, 'BATTLE', pillType, materials);
    }
  }
  const critMaterials = { [herbs[0]]: 2, [herbs[5]]: 2 };
  for (const pillType of ['critRate', 'critEffect']) {
    for (const grade of GRADE_LOWER) {
      const tpl = pillById.get(`${pillType}_${tier}_${grade}`);
      if (!tpl) continue;
      mkRecipe(tpl, tier, 'BATTLE', pillType, critMaterials);
    }
  }
}
// 功能类
const SINGLE_FUNC_TYPES = ['extendLife', 'intelligence', 'charm', 'loyalty', 'comprehension', 'artifactRefining', 'pillRefining', 'spiritPlanting', 'teaching', 'morality', 'mining'];
for (let tier = 1; tier <= 6; tier++) {
  const herbs = TIER_HERB_IDS[tier];
  for (let idx = 0; idx < SINGLE_FUNC_TYPES.length; idx++) {
    const materials = { [herbs[idx % herbs.length]]: 2, [herbs[(idx + 6) % herbs.length]]: 2 };
    for (const grade of GRADE_LOWER) {
      const tpl = pillById.get(`${SINGLE_FUNC_TYPES[idx]}_${tier}_${grade}`);
      if (!tpl) continue;
      mkRecipe(tpl, tier, 'FUNCTIONAL', SINGLE_FUNC_TYPES[idx], materials);
    }
  }
  for (let idx = 0; idx < Object.keys(DUAL_BASE_CFG).length; idx++) {
    const pillType = Object.keys(DUAL_BASE_CFG)[idx];
    const materials = { [herbs[idx % herbs.length]]: 2, [herbs[(idx + 2) % herbs.length]]: 2 };
    for (const grade of GRADE_LOWER) {
      const tpl = pillById.get(`${pillType}_${tier}_${grade}`);
      if (!tpl) continue;
      mkRecipe(tpl, tier, 'FUNCTIONAL', pillType, materials);
    }
  }
}

// ── 校验 ──────────────────────────────────────────────────────────────
// 计划 v2 阶段 3（T-CPP-2）：静态数据单一源——数据权威在 scripts/data/*.json
//（中性源），生成器只读中性源校验并刷新测试快照。
const DATA_DIR = join(ROOT, 'scripts/data');
const recipeJson = JSON.parse(
  readFileSync(join(DATA_DIR, 'recipe_db_sample.json'), 'utf-8'));
const neutralForgeRecipes = recipeJson.forgeRecipes;
const neutralPillRecipes = recipeJson.pillRecipes;
if (!Array.isArray(neutralForgeRecipes) || !Array.isArray(neutralPillRecipes)) {
  console.error('错误：中性源 recipe_db_sample.json 缺少 forgeRecipes/pillRecipes 数组');
  process.exit(1);
}
// 中性源覆盖内嵌生成结果（单一源权威）
forgeRecipes.length = 0;
forgeRecipes.push(...neutralForgeRecipes);
pillRecipes.length = 0;
pillRecipes.push(...neutralPillRecipes);

for (const [name, list] of [['锻造配方', forgeRecipes], ['丹药配方', pillRecipes]]) {
  const seen = new Set();
  for (const e of list) {
    if (seen.has(e.id)) {
      console.error(`错误：重复${name} id ${e.id}`);
      process.exit(1);
    }
    seen.add(e.id);
  }
}
// 材料键必须与 HerbDatabase 的灵草 id 对应（防御性检查）
const herbIds = new Set(Object.values(TIER_HERB_IDS).flat());
for (const r of pillRecipes) {
  for (const matId of Object.keys(r.materials)) {
    if (!herbIds.has(matId)) {
      console.error(`错误：丹药配方 ${r.id} 引用了未知灵草 ${matId}`);
      process.exit(1);
    }
  }
}

// ── 输出 ──────────────────────────────────────────────────────────────
mkdirSync(SAMPLE_DIR, { recursive: true });
writeFileSync(join(SAMPLE_DIR, 'recipe_db_sample.json'), JSON.stringify({
  forgeRecipeCount: forgeRecipes.length,
  pillRecipeCount: pillRecipes.length,
  forgeRecipes,
  pillRecipes,
}, null, 1));

console.log(`锻造/炼丹配方快照生成完成：${forgeRecipes.length} 锻造 + ${pillRecipes.length} 丹药`);
console.log(`  -> ${join(SAMPLE_DIR, 'recipe_db_sample.json')}`);
