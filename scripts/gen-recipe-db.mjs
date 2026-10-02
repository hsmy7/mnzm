#!/usr/bin/env node
/**
 * gen-recipe-db.mjs — 锻造/炼丹配方快照生成器
 *
 * 与 gen-templates.mjs 同模式：在 Node 侧**等价复刻 Kotlin
 * 的生成逻辑**，产出 JSON 快照锚点：
 *   android/core/engine/src/test/resources/templates/recipe_db_sample.json
 *
 * 数据来源：
 *   1. 锻造配方（24 条 = 6 套 × 4 部位，四部位化 F3）：与
 *      ForgeRecipeDatabase.kt 同构派生（id = "forge_{pieceId}"，材料表
 *      按部位族 6 档 object 形状 tier1..tier6）。
 *   2. 丹药配方（660 条）：复刻 PillRecipeDatabase.kt 生成循环，其依赖的
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
const BASE_ATTR = { 1: 3, 2: 5, 3: 8, 4: 12, 5: 16, 6: 20 };

// ── 名称表（与 Kotlin 字面量逐值一致）────────────────────────────────
const NAMES = {
  speedCult: { 1: '引灵丹', 2: '聚灵丹', 3: '凝元丹', 4: '炼气丹', 5: '混元丹', 6: '仙灵丹' },
  skillSpeed: { 1: '悟法丹', 2: '通法丹', 3: '玄法丹', 4: '道法丹', 5: '天法丹', 6: '仙法丹' },
  cultAdd: { 1: '增元丹', 2: '培元丹', 3: '固元丹', 4: '真元丹', 5: '玄元丹', 6: '仙元丹' },
  skillAdd: { 1: '悟道丹', 2: '明心丹', 3: '通玄丹', 4: '慧灵丹', 5: '道悟丹', 6: '天机丹' },
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
  artifactRefining: { 1: '铸魂丹', 2: '灵铸丹', 3: '宝铸丹', 4: '玄铸丹', 5: '地铸丹', 6: '天铸丹' },
  pillRefining: { 1: '丹心丹', 2: '灵丹丹', 3: '宝丹丹', 4: '玄丹丹', 5: '地丹丹', 6: '天丹丹' },
  spiritPlanting: { 1: '灵植丹', 2: '灵耘丹', 3: '宝耘丹', 4: '玄耘丹', 5: '地耘丹', 6: '天耘丹' },
  teaching: { 1: '传道丹', 2: '灵传丹', 3: '宝传丹', 4: '玄传丹', 5: '地传丹', 6: '天传丹' },
  morality: { 1: '善行丹', 2: '灵善丹', 3: '宝善丹', 4: '玄善丹', 5: '地善丹', 6: '天善丹' },
  mining: { 1: '探矿丹', 2: '灵石丹', 3: '宝矿丹', 4: '玄矿丹', 5: '地矿丹', 6: '天矿丹' },
  // 双基础属性功能
  intelligenceComprehension: { 1: '智悟丹', 2: '灵悟丹', 3: '明悟丹', 4: '玄悟丹', 5: '地悟丹', 6: '天悟丹' },
  pillRefiningArtifactRefining: { 1: '双炼丹', 2: '灵炼丹', 3: '宝炼丹', 4: '玄炼丹', 5: '地炼丹', 6: '天炼丹' },
  spiritPlantingTeaching: { 1: '师农丹', 2: '灵师丹', 3: '宝师丹', 4: '玄师丹', 5: '地师丹', 6: '天师丹' },
  intelligenceCharm: { 1: '智魅丹', 2: '灵魅丹', 3: '明魅丹', 4: '玄魅丹', 5: '地魅丹', 6: '天魅丹' },
  comprehensionMorality: { 1: '悟德丹', 2: '灵德丹', 3: '宝德丹', 4: '玄德丹', 5: '地德丹', 6: '天德丹' },
};

// 属性中文名（单属性战斗/单基础属性功能描述用）
const ATTR_CN = {
  physicalAttack: '物攻', magicAttack: '法攻', physicalDefense: '物防', magicDefense: '法防',
  hp: '生命', mp: '灵力', speed: '速度',
  intelligence: '智力', charm: '魅力',
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

// ── B1 属性单列口径（方案 §15.4）：物法攻合并 attackAdd、物法防合并 defenseAdd
// （pillType/文案保留物法身份，效果键归并；与 Kotlin ItemDatabase 生成逻辑一致）
const ADD_KEY = { physicalAttack: 'attackAdd', magicAttack: 'attackAdd',
  physicalDefense: 'defenseAdd', magicDefense: 'defenseAdd' };
const addKeyOf = (a) => ADD_KEY[a] || `${a}Add`;

// ── PillTemplate 生成（复刻 ItemDatabase.kt）─────────────────────────
const pillTemplates = [];
const mkPill = (id, name, description, fields) => {
  pillTemplates.push({
    id, name, description,
    breakthroughChance: fields.breakthroughChance ?? 0.0,
    targetRealm: fields.targetRealm ?? 0,
    cultivationSpeedPercent: fields.cultivationSpeedPercent ?? 0.0,
    skillExpSpeedPercent: fields.skillExpSpeedPercent ?? 0.0,
    cultivationAdd: fields.cultivationAdd ?? 0,
    skillExpAdd: fields.skillExpAdd ?? 0,
    attackAdd: fields.attackAdd ?? 0,
    defenseAdd: fields.defenseAdd ?? 0,
    hpAdd: fields.hpAdd ?? 0,
    mpAdd: fields.mpAdd ?? 0,
    speedAdd: fields.speedAdd ?? 0,
    critRateAdd: fields.critRateAdd ?? 0.0,
    critEffectAdd: fields.critEffectAdd ?? 0.0,
    intelligenceAdd: fields.intelligenceAdd ?? 0,
    charmAdd: fields.charmAdd ?? 0,
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
  }
}
for (let tier = 1; tier <= 6; tier++) {
  const cultBase = rint(CULT_BASE[tier] * 0.375);
  const skillBase = rint(CULT_BASE[tier] * 0.1);
  const tierName = TIER_NAMES[tier];
  for (let g = 0; g < 3; g++) {
    const mult = GRADE_MULT[g];
    const gl = GRADE_LOWER[g];
    const gn = GRADE_DISPLAY[g];
    const cultAddVal = rint(cultBase * mult);
    const skillAddVal = rint(skillBase * mult);
    mkPill(`cultivationAdd_${tier}_${gl}`, NAMES.cultAdd[tier],
      `${tierName}${gn}境界修为丹，立即增加${cultAddVal}点境界修为`,
      { cultivationAdd: cultAddVal });
    mkPill(`skillExpAdd_${tier}_${gl}`, NAMES.skillAdd[tier],
      `${tierName}${gn}功法熟练丹，立即增加${skillAddVal}点功法熟练度`,
      { skillExpAdd: skillAddVal });
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
      fields[addKeyOf(pillType)] = val;
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
        const k = addKeyOf(a);
        fields[k] = (fields[k] || 0) + attrVal(a);
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
      for (const a of ['intelligence', 'charm', 'comprehension', 'artifactRefining', 'pillRefining', 'spiritPlanting', 'teaching', 'morality']) {
        fields[`${a}Add`] = attrVal(a);
      }
      mkPill(`${pillType}_${tier}_${GRADE_LOWER[g]}`, NAMES[pillType][tier],
        `${tierName}${GRADE_DISPLAY[g]}${descName}丹，永久增加${v1}点${attr1}和${v2}点${attr2}`, fields);
    }
  }
}

const pillById = new Map(pillTemplates.map((t) => [t.id, t]));

// ── 锻造配方(四部位化 F3:24 条套装部件配方 = 6 套 × 4 部位,
// 与 ForgeRecipeDatabase.kt 同构派生)──────────────────────────────
// 部位材料族:品阶 1..6 材料表(6 套间同构复用,Kotlin 字面量即同构复制)
const FORGE_HEAD_MATS = [
  { bearHide0: 3, bearBone0: 2 },
  { bearHide1: 4, bearBone1: 3 },
  { bearHide2: 5, bearBone2: 3, bearCore2: 2 },
  { bearHide3: 5, bearBone3: 4, bearCore3: 3 },
  { bearHide4: 6, bearBone4: 4, bearCore4: 2, dragonScale4: 2 },
  { bearHide5: 8, bearBone5: 5, bearCore5: 3, dragonClaw5: 3 },
];
const FORGE_BODY_MATS = [
  { bearHide0: 4, bearBone0: 2 },
  { bearHide1: 5, bearBone1: 2 },
  { snakeScale2: 5, snakeBlood2: 3, snakeCore2: 2 },
  { snakeScale3: 6, snakeBlood3: 4, snakeCore3: 2 },
  { snakeScale4: 6, snakeBlood4: 4, snakeCore4: 2, dragonScale4: 2 },
  { snakeScale5: 8, snakeBlood5: 5, snakeCore5: 3, dragonScale5: 3 },
];
const FORGE_HANDS_MATS = [
  { eagleClaw0: 3, eagleFeather0: 2 },
  { eagleClaw1: 4, eagleFeather1: 3 },
  { eagleFeather2: 5, eagleClaw2: 3, eagleCore2: 2 },
  { eagleFeather3: 6, eagleClaw3: 4, eagleCore3: 2 },
  { eagleFeather4: 6, eagleClaw4: 4, eagleCore4: 2, snakeCore4: 2 },
  { eagleFeather5: 7, eagleClaw5: 5, eagleCore5: 3, dragonCore5: 3 },
];
const FORGE_FEET_MATS = [
  { wolfHide0: 3, wolfBone0: 2 },
  { wolfHide1: 4, wolfBone1: 2 },
  { wolfHide2: 4, wolfBone2: 3, wolfCore2: 2 },
  { wolfHide3: 5, wolfBone3: 3, wolfCore3: 2 },
  { wolfHide4: 5, wolfTooth4: 4, wolfCore4: 2, dragonScale4: 2 },
  { wolfHide5: 7, wolfTooth5: 5, wolfCore5: 3, dragonScale5: 3 },
];

// 24 部件名/描述(EquipmentDatabase.SetPieceTemplate 派生结果,逐字)
const FORGE_SETS = [
  {
    id: 'lietian',
    names: ['裂天罡煞·头冠', '裂天罡煞·重铠', '裂天罡煞·战手', '裂天罡煞·战靴'],
    descs: ['裂天罡煞套装头冠，罡煞之气护持识海', '裂天罡煞套装重铠，煞气凝甲坚不可摧',
      '裂天罡煞套装护手，罡风附刃裂石开碑', '裂天罡煞套装战靴，踏罡步斗势如奔雷'],
  },
  {
    id: 'gengjin',
    names: ['庚金白虎·灵冠', '庚金白虎·法袍', '庚金白虎·灵护', '庚金白虎·云履'],
    descs: ['庚金白虎套装灵冠，白虎金睛洞察秋毫', '庚金白虎套装法袍，金气织体刀兵不侵',
      '庚金白虎套装灵护，锐金凝爪裂金断玉', '庚金白虎套装云履，虎啸风生金戈疾行'],
  },
  {
    id: 'qingmu',
    names: ['青木长生·灵冠', '青木长生·法袍', '青木长生·灵护', '青木长生·云履'],
    descs: ['青木长生套装灵冠，青木灵韵清心明神', '青木长生套装法袍，生生不息缠枝为衣',
      '青木长生套装灵护，藤蔓缠腕生机盎然', '青木长生套装云履，踏叶而行轻若春风'],
  },
  {
    id: 'xuanshui',
    names: ['玄水寒渊·灵冠', '玄水寒渊·法袍', '玄水寒渊·灵护', '玄水寒渊·云履'],
    descs: ['玄水寒渊套装灵冠，寒渊之息凝神静念', '玄水寒渊套装法袍，玄水环身百法不沾',
      '玄水寒渊套装灵护，寒潮覆掌冻结万机', '玄水寒渊套装云履，凌波微步踏水无痕'],
  },
  {
    id: 'lihuo',
    names: ['离火焚天·灵冠', '离火焚天·法袍', '离火焚天·灵护', '离火焚天·云履'],
    descs: ['离火焚天套装灵冠，离火真焰炼神涤魄', '离火焚天套装法袍，炎纹织体烈焰随身',
      '离火焚天套装灵护，火灵附掌焚尽八荒', '离火焚天套装云履，踏火而行燎原疾影'],
  },
  {
    id: 'houtu',
    names: ['厚土镇岳·灵冠', '厚土镇岳·法袍', '厚土镇岳·灵护', '厚土镇岳·云履'],
    descs: ['厚土镇岳套装灵冠，厚土之德沉稳心神', '厚土镇岳套装法袍，山岳之甲岿然不动',
      '厚土镇岳套装灵护，镇岳之力撼地崩山', '厚土镇岳套装云履，踏地生根移山填谷'],
  },
];

const FORGE_PART_MATS = [FORGE_HEAD_MATS, FORGE_BODY_MATS, FORGE_HANDS_MATS, FORGE_FEET_MATS];
const FORGE_PART_KEYS = ['HEAD', 'BODY', 'HANDS', 'FEET'];

const forgeRecipes = FORGE_SETS.flatMap((set) =>
  FORGE_PART_KEYS.map((part, partIdx) => ({
    id: `forge_${set.id}_${part}`,
    pieceId: `${set.id}_${part}`,
    setId: set.id,
    part,
    name: set.names[partIdx],
    description: set.descs[partIdx],
    tierMaterials: FORGE_PART_MATS[partIdx].reduce((acc, mats, tierIdx) => {
      acc[`tier${tierIdx + 1}`] = sortedMats(mats);
      return acc;
    }, {}),
  })),
);

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
  cultivationSpeedPercent: 0.0, skillExpSpeedPercent: 0.0,
  cultivationAdd: 0, skillExpAdd: 0,
  attackAdd: 0, defenseAdd: 0,
  hpAdd: 0, mpAdd: 0, speedAdd: 0, critRateAdd: 0.0, critEffectAdd: 0.0,
  intelligenceAdd: 0, charmAdd: 0, comprehensionAdd: 0,
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
    cultivationAdd: tpl.cultivationAdd, skillExpAdd: tpl.skillExpAdd,
    attackAdd: tpl.attackAdd, defenseAdd: tpl.defenseAdd,
    hpAdd: tpl.hpAdd, mpAdd: tpl.mpAdd, speedAdd: tpl.speedAdd,
    critRateAdd: tpl.critRateAdd, critEffectAdd: tpl.critEffectAdd,
    intelligenceAdd: tpl.intelligenceAdd, charmAdd: tpl.charmAdd,
    comprehensionAdd: tpl.comprehensionAdd, artifactRefiningAdd: tpl.artifactRefiningAdd,
    pillRefiningAdd: tpl.pillRefiningAdd, spiritPlantingAdd: tpl.spiritPlantingAdd,
    teachingAdd: tpl.teachingAdd, moralityAdd: tpl.moralityAdd, miningAdd: tpl.miningAdd,
  });
};

// 常规修炼配方
const STANDARD_PILL_TYPES = ['cultivationSpeed', 'skillExpSpeed', 'cultivationAdd', 'skillExpAdd'];
const HERB_PATTERNS = [[0, 3], [1, 6], [0, 7], [5, 8]];
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
const SINGLE_FUNC_TYPES = ['intelligence', 'charm', 'artifactRefining', 'pillRefining', 'spiritPlanting', 'teaching', 'morality', 'mining'];
// herbPatterns：与 Kotlin herbPatterns 逐项一致（灵草槽位固定，单/双功能配方各一份）
const SINGLE_FUNC_HERB_PATTERNS = [[1, 7], [2, 8], [5, 2], [6, 3], [7, 4], [8, 5], [0, 6], [1, 7]];
const DUAL_BASE_HERB_PATTERNS = [[0, 2], [2, 4], [3, 5], [4, 6], [5, 7]];
for (let tier = 1; tier <= 6; tier++) {
  const herbs = TIER_HERB_IDS[tier];
  for (let idx = 0; idx < SINGLE_FUNC_TYPES.length; idx++) {
    const [p1, p2] = SINGLE_FUNC_HERB_PATTERNS[idx];
    const materials = { [herbs[p1]]: 2, [herbs[p2]]: 2 };
    for (const grade of GRADE_LOWER) {
      const tpl = pillById.get(`${SINGLE_FUNC_TYPES[idx]}_${tier}_${grade}`);
      if (!tpl) continue;
      mkRecipe(tpl, tier, 'FUNCTIONAL', SINGLE_FUNC_TYPES[idx], materials);
    }
  }
  for (let idx = 0; idx < Object.keys(DUAL_BASE_CFG).length; idx++) {
    const pillType = Object.keys(DUAL_BASE_CFG)[idx];
    const [p1, p2] = DUAL_BASE_HERB_PATTERNS[idx];
    const materials = { [herbs[p1]]: 2, [herbs[p2]]: 2 };
    for (const grade of GRADE_LOWER) {
      const tpl = pillById.get(`${pillType}_${tier}_${grade}`);
      if (!tpl) continue;
      mkRecipe(tpl, tier, 'FUNCTIONAL', pillType, materials);
    }
  }
}

// ── 校验 ──────────────────────────────────────────────────────────────
// 静态数据单一源——数据权威在 scripts/data/*.json
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
