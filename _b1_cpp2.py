# -*- coding: utf-8 -*-
"""B1 C++ 脚本 2：disciple.h / disciple_stats.h 结算单列"""
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

# ── disciple.h ──
patch('include/gamecore/system/disciple.h', [
    ('''/// 属性计算输入（对应 Kotlin VarianceInputs/SkillInputs 收拢）
struct BaseStatsInput {
    int32_t realm = 9;
    int32_t realmLayer = 1;
    int32_t hpVariance = 0;
    int32_t mpVariance = 0;
    int32_t physicalAttackVariance = 0;
    int32_t magicAttackVariance = 0;
    int32_t physicalDefenseVariance = 0;
    int32_t magicDefenseVariance = 0;
    int32_t speedVariance = 0;''',
     '''/// 属性计算输入（对应 Kotlin VarianceInputs/SkillInputs 收拢；单列口径 B1）
struct BaseStatsInput {
    int32_t realm = 9;
    int32_t realmLayer = 1;
    int32_t hpVariance = 0;
    int32_t mpVariance = 0;
    int32_t attackVariance = 0;
    int32_t defenseVariance = 0;
    int32_t speedVariance = 0;'''),
    ('''/// 最终属性（对应 Kotlin DiscipleStats）
struct DiscipleStats {
    int32_t hp = 0;
    int32_t maxHp = 0;
    int32_t mp = 0;
    int32_t maxMp = 0;
    int32_t physicalAttack = 0;
    int32_t magicAttack = 0;
    int32_t physicalDefense = 0;
    int32_t magicDefense = 0;
    int32_t speed = 0;''',
     '''/// 最终属性（对应 Kotlin DiscipleStats；单列口径 B1）
struct DiscipleStats {
    int32_t hp = 0;
    int32_t maxHp = 0;
    int32_t mp = 0;
    int32_t maxMp = 0;
    int32_t attack = 0;
    int32_t defense = 0;
    int32_t speed = 0;'''),
    ('''    s.physicalAttack = roundToInt(
        rc.basePhysicalAttack * safeVarianceMultiplier(in.physicalAttackVariance) *
        layerMult);
    s.magicAttack = roundToInt(
        rc.baseMagicAttack * safeVarianceMultiplier(in.magicAttackVariance) *
        layerMult);
    s.physicalDefense = roundToInt(
        rc.basePhysicalDefense * safeVarianceMultiplier(in.physicalDefenseVariance) *
        layerMult);
    s.magicDefense = roundToInt(
        rc.baseMagicDefense * safeVarianceMultiplier(in.magicDefenseVariance) *
        layerMult);''',
     '''    // 单列口径（B1 §15.4/Q2）：境界面物法两列各自 round 后相加进单列——
    // 同一方差乘区作用于物法两半，与战力取和公式线性一致（迁移前后战力不变）
    {
        const double atkVar = safeVarianceMultiplier(in.attackVariance);
        const double defVar = safeVarianceMultiplier(in.defenseVariance);
        s.attack = roundToInt(rc.basePhysicalAttack * atkVar * layerMult) +
                   roundToInt(rc.baseMagicAttack * atkVar * layerMult);
        s.defense = roundToInt(rc.basePhysicalDefense * defVar * layerMult) +
                    roundToInt(rc.baseMagicDefense * defVar * layerMult);
    }'''),
])

# ── disciple_stats.h ──
patch('include/gamecore/system/disciple_stats.h', [
    ('''struct EquipmentStats {
    int32_t physicalAttack = 0;
    int32_t magicAttack = 0;
    int32_t physicalDefense = 0;
    int32_t magicDefense = 0;
    int32_t speed = 0;
    int32_t hp = 0;
    int32_t mp = 0;
};''',
     '''struct EquipmentStats {
    // 单列口径（B1）：装备面板四列（EquipmentInstance 本体保留至 B3 退役）
    // 在 equipmentFinalStats 出口合并为单列
    int32_t attack = 0;
    int32_t defense = 0;
    int32_t speed = 0;
    int32_t hp = 0;
    int32_t mp = 0;
};'''),
    ('''    EquipmentStats s;
    s.physicalAttack = static_cast<int32_t>(eq.physicalAttack * m);
    s.magicAttack = static_cast<int32_t>(eq.magicAttack * m);
    s.physicalDefense = static_cast<int32_t>(eq.physicalDefense * m);
    s.magicDefense = static_cast<int32_t>(eq.magicDefense * m);
    s.speed = static_cast<int32_t>(eq.speed * m);
    s.hp = static_cast<int32_t>(eq.hp * m);
    s.mp = static_cast<int32_t>(eq.mp * m);
    return s;''',
     '''    EquipmentStats s;
    s.attack = static_cast<int32_t>(eq.physicalAttack * m) +
               static_cast<int32_t>(eq.magicAttack * m);
    s.defense = static_cast<int32_t>(eq.physicalDefense * m) +
                static_cast<int32_t>(eq.magicDefense * m);
    s.speed = static_cast<int32_t>(eq.speed * m);
    s.hp = static_cast<int32_t>(eq.hp * m);
    s.mp = static_cast<int32_t>(eq.mp * m);
    return s;'''),
    ('''    in.hpVariance = d.hpVariance;
    in.mpVariance = d.mpVariance;
    in.physicalAttackVariance = d.physicalAttackVariance;
    in.magicAttackVariance = d.magicAttackVariance;
    in.physicalDefenseVariance = d.physicalDefenseVariance;
    in.magicDefenseVariance = d.magicDefenseVariance;
    in.speedVariance = d.speedVariance;''',
     '''    in.hpVariance = d.hpVariance;
    in.mpVariance = d.mpVariance;
    in.attackVariance = d.attackVariance;
    in.defenseVariance = d.defenseVariance;
    in.speedVariance = d.speedVariance;'''),
    ('''        const EquipmentStats fs = equipmentFinalStats(it->second);
        total.maxHp += fs.hp;
        total.hp += fs.hp;
        total.maxMp += fs.mp;
        total.mp += fs.mp;
        total.physicalAttack += fs.physicalAttack;
        total.magicAttack += fs.magicAttack;
        total.physicalDefense += fs.physicalDefense;
        total.magicDefense += fs.magicDefense;
        total.speed += fs.speed;''',
     '''        const EquipmentStats fs = equipmentFinalStats(it->second);
        total.maxHp += fs.hp;
        total.hp += fs.hp;
        total.maxMp += fs.mp;
        total.mp += fs.mp;
        total.attack += fs.attack;
        total.defense += fs.defense;
        total.speed += fs.speed;'''),
    ('''        total.physicalAttack += static_cast<int32_t>(
            static_cast<double>(statOf("physicalAttack", "")) * masteryBonus);
        total.magicAttack += static_cast<int32_t>(
            static_cast<double>(statOf("magicAttack", "")) * masteryBonus);
        total.physicalDefense += static_cast<int32_t>(
            static_cast<double>(statOf("physicalDefense", "")) * masteryBonus);
        total.magicDefense += static_cast<int32_t>(
            static_cast<double>(statOf("magicDefense", "")) * masteryBonus);''',
     '''        // 功法保留物法双列数据（Q2），结算层各自 round 后相加（与 Kotlin 同式）
        total.attack += static_cast<int32_t>(
            static_cast<double>(statOf("physicalAttack", "")) * masteryBonus) +
            static_cast<int32_t>(
            static_cast<double>(statOf("magicAttack", "")) * masteryBonus);
        total.defense += static_cast<int32_t>(
            static_cast<double>(statOf("physicalDefense", "")) * masteryBonus) +
            static_cast<int32_t>(
            static_cast<double>(statOf("magicDefense", "")) * masteryBonus);'''),
    ('''        total.physicalAttack += d.pillPhysicalAttackBonus;
        total.magicAttack += d.pillMagicAttackBonus;
        total.physicalDefense += d.pillPhysicalDefenseBonus;
        total.magicDefense += d.pillMagicDefenseBonus;''',
     '''        total.attack += d.pillAttackBonus;
        total.defense += d.pillDefenseBonus;'''),
])

print('disciple.h / disciple_stats.h ok')
