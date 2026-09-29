# -*- coding: utf-8 -*-
"""B1 C++ 脚本 11：auto_gear/manualTypeMatch + mission_completion 单列"""
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
            print('SKIP:', path, repr(old[:70]))
            continue
        s = s.replace(old, new)
    wr(path, s)

patch('include/gamecore/system/auto_gear.h', [
    ('''/// 攻击类型匹配度（功法按 skillDamageType）
inline int32_t manualTypeMatch(const Disciple& d, const ManualCandidate& c) {
    const bool prefersPhysical = d.basePhysicalAttack >= d.baseMagicAttack;''',
     '''/// 攻击类型匹配度（功法按 skillDamageType；单列口径 B1 §15.4）
inline int32_t manualTypeMatch(const Disciple& d, const ManualCandidate& c) {
    const bool prefersPhysical = d.innateDamageType != "MAGIC";'''),
])

patch('include/gamecore/system/mission_completion.h', [
    # 妖兽 Combatant 组装（单列两半相加）
    ('''    beast.physicalAttack = scaled(rs.attack, type.atkMod);
    beast.magicAttack = scaled(rs.attack, type.atkMod);
    beast.physicalDefense = scaled(rs.defense, type.defMod);
    beast.magicDefense = scaled(rs.defense, type.defMod);''',
     '''    // 单列口径（B1）：物=法同源两半相加
    beast.attack = scaled(rs.attack, type.atkMod) * 2;
    beast.defense = scaled(rs.defense, type.defMod) * 2;'''),
    # 弟子 Combatant 组装
    ('''    c.physicalAttack = stats.physicalAttack;
    c.magicAttack = stats.magicAttack;
    c.physicalDefense = stats.physicalDefense;
    c.magicDefense = stats.magicDefense;''',
     '''    c.attack = stats.attack;
    c.defense = stats.defense;'''),
    # 装备累加
    ('''            const auto fs = gamecore::stats::equipmentFinalStats(inst);
            eqPa += fs.physicalAttack;
            eqMa += fs.magicAttack;
            eqPd += fs.physicalDefense;
            eqMd += fs.magicDefense;''',
     '''            const auto fs = gamecore::stats::equipmentFinalStats(inst);
            eqPa += fs.attack;
            eqPd += fs.defense;'''),
])

# 妖兽 Combatant 补 innateDamageType + 散修敌人数值段
s = rd('include/gamecore/system/mission_completion.h')
old = '''    beast.critRate = 0.05 + realmIndex * 0.01;
    beast.realm = realmIndex;'''
if 'beast.innateDamageType' not in s:
    assert old in s
    s = s.replace(old, '''    beast.critRate = 0.05 + realmIndex * 0.01;
    beast.innateDamageType =
        (type.element == "metal" || type.element == "earth")
            ? gamecore::battle::DamageType::kPhysical
            : gamecore::battle::DamageType::kMagic;
    beast.realm = realmIndex;''')
wr('include/gamecore/system/mission_completion.h', s)
print('beast innate ok')

# 散修敌人（enemy.physicalAttack 段）——单列两半各自 round 相加
patch('include/gamecore/system/mission_completion.h', [
    ('''        enemy.physicalAttack = static_cast<int32_t>(
            static_cast<double>(rc.basePhysicalAttack) * rngVar() * layerMult) + eqPa + mPa;
        enemy.magicAttack = static_cast<int32_t>(
            static_cast<double>(rc.baseMagicAttack) * rngVar() * layerMult) + eqMa + mMa;''',
     '''        // 单列口径（B1）：物法两半各自 round 后相加（同一方差乘区）
        const double atkVarE = rngVar();
        enemy.attack = static_cast<int32_t>(
            static_cast<double>(rc.basePhysicalAttack) * atkVarE * layerMult) +
            static_cast<int32_t>(
            static_cast<double>(rc.baseMagicAttack) * atkVarE * layerMult) + eqPa + mPa;'''),
])
# 后续 defense 段
s = rd('include/gamecore/system/mission_completion.h')
import re
m = re.search(r'enemy\.physicalDefense = static_cast<int32_t>\(\n.*?\+ eqPd \+ mPd;', s, re.S)
print('defense seg:', bool(m))
if m:
    old = m.group(0)
    new = '''enemy.defense = static_cast<int32_t>(
            static_cast<double>(rc.basePhysicalDefense) * rngVar() * layerMult) +
            static_cast<int32_t>(
            static_cast<double>(rc.baseMagicDefense) * rngVar() * layerMult) + eqPd + mPd;'''
    s = s.replace(old, new)
    wr('include/gamecore/system/mission_completion.h', s)
    print('defense ok')

print('patch11 ok')
