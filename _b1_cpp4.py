# -*- coding: utf-8 -*-
"""B1 C++ 脚本 4：battle.h DamageZones + battle_calculator.h 公式全链"""
import os
os.chdir(r'C:\Mnzm\XianxiaSectNative-equipment\android\app\src\main\cpp\gamecore')

def rd(p):
    return open(p, encoding='utf-8').read()

def wr(p, s):
    open(p, 'w', encoding='utf-8', newline='\n').write(s)

def patch(path, subs):
    s = rd(path)
    for old, new in subs:
        if old not in s:
            raise SystemExit('NOT FOUND in %s:\n%s' % (path, old[:200]))
        s = s.replace(old, new)
    wr(path, s)

# ── battle.h：DamageZones 单列口径 ──
patch('include/gamecore/system/battle.h', [
    ('''struct DamageZones {
    double attackBuffs = 0.0;                // 按攻击类型注入的攻防 Buff
    double physicalAttackBuffs = 0.0;        // 物理攻击 Buff 分桶
    double magicAttackBuffs = 0.0;           // 魔法攻击 Buff 分桶
    double damageAmplification = 0.0;        // 增伤乘区
    double damageReduction = 0.0;            // 减伤乘区
    double realmGapDamageAmplification = 0.0;  // 境界压制增伤（独立乘算）
    double realmGapDamageReduction = 0.0;      // 境界压制减伤（独立乘算）
    double majorRealmDamageAmplification = 0.0; // 大境界增伤（独立乘算）
};''',
     '''struct DamageZones {
    // 单列口径（B1，方案 §15.2/§15.6.1）：物法攻 buff 分桶迁移为类型增伤、
    // 物法防 buff 分桶迁移为类型减伤；typeDamage* 为按本次伤害类型选桶合并后
    // 的结算位（固有桶 + buff 桶），全 0.0 时与基准公式逐位一致（S19）
    double physicalAttackBuffs = 0.0;        // 物理类型增伤 buff 分桶
    double magicAttackBuffs = 0.0;           // 法术类型增伤 buff 分桶
    double physicalDefenseBuffs = 0.0;       // 物理类型减伤 buff 分桶（守方）
    double magicDefenseBuffs = 0.0;          // 法术类型减伤 buff 分桶（守方）
    double damageAmplification = 0.0;        // 增伤乘区
    double damageReduction = 0.0;            // 减伤乘区
    double typeDamageBonus = 0.0;            // 类型增伤结算位（选桶合并后）
    double typeDamageReduction = 0.0;        // 类型减伤结算位（选桶合并后）
    double realmGapDamageAmplification = 0.0;  // 境界压制增伤（独立乘算）
    double realmGapDamageReduction = 0.0;      // 境界压制减伤（独立乘算）
    double majorRealmDamageAmplification = 0.0; // 大境界增伤（独立乘算）
};'''),
])

# ── battle_calculator.h：判定/管线/估算/CombatantStats/FinalDamage ──
p = 'include/gamecore/system/battle_calculator.h'
patch(p, [
    # tryInstantKill
    ('''    const bool isPhysical =
        skill ? skill->damageType == DamageType::kPhysical
              : attacker.physicalAttack >= attacker.magicAttack;
    DamageResult r;
    r.damage = std::max(0, defender.maxHp);  // T-C2：maxHp 篡改钳制''',
     '''    const bool isPhysical =
        skill ? skill->damageType == DamageType::kPhysical
              : attacker.innateDamageType == DamageType::kPhysical;
    DamageResult r;
    r.damage = std::max(0, defender.maxHp);  // T-C2：maxHp 篡改钳制'''),
    # tryDodge
    ('''    r.isPhysical = isSkillAttack
        ? (skill ? skill->damageType == DamageType::kPhysical : true)
        : attacker.physicalAttack >= attacker.magicAttack;''',
     '''    r.isPhysical = isSkillAttack
        ? (skill ? skill->damageType == DamageType::kPhysical : true)
        : attacker.innateDamageType == DamageType::kPhysical;'''),
    # computeDamagePipeline
    ('''    const bool isPhysical = isSkillAttack
        ? (skill ? skill->damageType == DamageType::kPhysical : true)
        : attacker.physicalAttack >= attacker.magicAttack;
    const int32_t attack =
        isPhysical ? attacker.physicalAttack : attacker.magicAttack;
    const int32_t defense =
        isPhysical ? defender.effectivePhysicalDefense() : defender.effectiveMagicDefense();

    const bool isCrit = rng.nextDouble() < attacker.effectiveCritRate();
    const double skillMultiplier = skill ? skill->damageMultiplier : 1.0;
    const double variance = calculateDamageVariance(rng);

    DamageZones baseZones = zones ? *zones : buildDamageZones(attacker, &defender);
    // 攻击 Buff 按攻击类型注入分桶 + damageModifier 注入增伤乘区
    baseZones.attackBuffs = baseZones.attackBuffs +
        (isPhysical ? baseZones.physicalAttackBuffs : baseZones.magicAttackBuffs);
    baseZones.damageAmplification = baseZones.damageAmplification + (damageModifier - 1.0);''',
     '''    // 单列口径（B1）：技能按 skill->damageType、普攻按固有伤害属性
    const bool isPhysical = isSkillAttack
        ? (skill ? skill->damageType == DamageType::kPhysical : true)
        : attacker.innateDamageType == DamageType::kPhysical;
    const int32_t attack = attacker.attack;
    const int32_t defense = defender.defense;

    const bool isCrit = rng.nextDouble() < attacker.effectiveCritRate();
    const double skillMultiplier = skill ? skill->damageMultiplier : 1.0;
    const double variance = calculateDamageVariance(rng);

    DamageZones baseZones = zones ? *zones : buildDamageZones(attacker, &defender);
    // 类型通道选桶合并（固有类型桶 + buff 分桶 → 结算位）+ damageModifier 注入
    baseZones.typeDamageBonus =
        (isPhysical ? attacker.physicalDamageBonus : attacker.magicDamageBonus) +
        (isPhysical ? baseZones.physicalAttackBuffs : baseZones.magicAttackBuffs);
    baseZones.typeDamageReduction =
        (isPhysical ? defender.physicalDamageReduction : defender.magicDamageReduction) +
        (isPhysical ? baseZones.physicalDefenseBuffs : baseZones.magicDefenseBuffs);
    baseZones.damageAmplification = baseZones.damageAmplification + (damageModifier - 1.0);'''),
    # calculateDamage（CombatantStats 版）
    ('''    const bool usePhysical = isPhysicalAttack.value_or(
        attacker.physicalAttack >= attacker.magicAttack);
    const int32_t attack = usePhysical ? attacker.physicalAttack : attacker.magicAttack;
    const int32_t defense = usePhysical ? defender.physicalDefense : defender.magicDefense;''',
     '''    // 单列口径（B1）：无技能时按攻击方固有伤害属性判定
    const bool usePhysical = isPhysicalAttack.value_or(
        attacker.innateDamageType == DamageType::kPhysical);
    const int32_t attack = attacker.attack;
    const int32_t defense = defender.defense;'''),
    # estimateDamage
    ('''    const bool isPhysical = skill.damageType == DamageType::kPhysical;
    const int32_t atk = isPhysical ? attacker.physicalAttack : attacker.magicAttack;
    const int32_t def =
        isPhysical ? defender.effectivePhysicalDefense() : defender.effectiveMagicDefense();

    DamageZones baseZones = zones ? *zones : buildDamageZones(attacker, &defender);
    baseZones.attackBuffs = baseZones.attackBuffs +
        (isPhysical ? baseZones.physicalAttackBuffs : baseZones.magicAttackBuffs);
    baseZones.damageAmplification = baseZones.damageAmplification + (damageModifier - 1.0);''',
     '''    const bool isPhysical = skill.damageType == DamageType::kPhysical;
    const int32_t atk = attacker.attack;
    const int32_t def = defender.defense;

    DamageZones baseZones = zones ? *zones : buildDamageZones(attacker, &defender);
    // 类型通道选桶合并（与实际伤害一致）
    baseZones.typeDamageBonus =
        (isPhysical ? attacker.physicalDamageBonus : attacker.magicDamageBonus) +
        (isPhysical ? baseZones.physicalAttackBuffs : baseZones.magicAttackBuffs);
    baseZones.typeDamageReduction =
        (isPhysical ? defender.physicalDamageReduction : defender.magicDamageReduction) +
        (isPhysical ? baseZones.physicalDefenseBuffs : baseZones.magicDefenseBuffs);
    baseZones.damageAmplification = baseZones.damageAmplification + (damageModifier - 1.0);'''),
])

s = rd(p)
# estimateDamage 尾部公式（attackBuffs 残留 + 类型乘区）
old = '''    const double preCritDmg = static_cast<double>(atk) * (1.0 + baseZones.attackBuffs) *
        skill.damageMultiplier * (1.0 - reduction);'''
new = '''    const double preCritDmg = static_cast<double>(atk) *
        skill.damageMultiplier * (1.0 - reduction);'''
assert old in s
s = s.replace(old, new)
# estimateDamage 乘区段（找 rawDmg 段）
import re
m = re.search(r'const double rawDmg = preCritDmg \* avgCritMult \*\n(.*?)skill\.hits;', s, re.S)
if m and 'typeDamageBonus' not in m.group(0):
    old2 = m.group(0)
    new2 = old2.replace('(1.0 + baseZones.damageAmplification) *',
                        '(1.0 + baseZones.damageAmplification + baseZones.typeDamageBonus) *')
    new2 = new2.replace('(1.0 - baseZones.damageReduction) *',
                        '(1.0 - baseZones.damageReduction - baseZones.typeDamageReduction) *')
    s = s.replace(old2, new2)
wr(p, s)
print('estimateDamage tail ok')

# CombatantStats 接口（battle.h？grep 定位）
print('--- CombatantStats 定义位置 ---')
os.system('grep -rn "struct CombatantStats" include/ | head -3')
