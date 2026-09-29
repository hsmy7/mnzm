# -*- coding: utf-8 -*-
"""B1 C++ 脚本 8：column_dirty.h 两段精确重写"""
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
            raise SystemExit('NOT FOUND in %s:\n%s' % (path, old[:120]))
        s = s.replace(old, new)
    wr(path, s)

p = 'include/gamecore/state/column_dirty.h'

patch(p, [
    # 列名映射段
    ('''        case DiscipleColumn::BasePhysicalAttack: return "basePhysicalAttack";
        case DiscipleColumn::BaseMagicAttack: return "baseMagicAttack";
        case DiscipleColumn::BasePhysicalDefense: return "basePhysicalDefense";
        case DiscipleColumn::BaseMagicDefense: return "baseMagicDefense";
        case DiscipleColumn::BaseSpeed: return "baseSpeed";
        case DiscipleColumn::HpVariance: return "hpVariance";
        case DiscipleColumn::MpVariance: return "mpVariance";
        case DiscipleColumn::PhysicalAttackVariance: return "physicalAttackVariance";
        case DiscipleColumn::MagicAttackVariance: return "magicAttackVariance";
        case DiscipleColumn::PhysicalDefenseVariance: return "physicalDefenseVariance";
        case DiscipleColumn::MagicDefenseVariance: return "magicDefenseVariance";
        case DiscipleColumn::SpeedVariance: return "speedVariance";''',
     '''        case DiscipleColumn::BaseAttack: return "baseAttack";
        case DiscipleColumn::BaseDefense: return "baseDefense";
        case DiscipleColumn::BaseSpeed: return "baseSpeed";
        case DiscipleColumn::HpVariance: return "hpVariance";
        case DiscipleColumn::MpVariance: return "mpVariance";
        case DiscipleColumn::AttackVariance: return "attackVariance";
        case DiscipleColumn::DefenseVariance: return "defenseVariance";
        case DiscipleColumn::InnateDamageType: return "innateDamageType";
        case DiscipleColumn::SpeedVariance: return "speedVariance";'''),
    ('''        case DiscipleColumn::PillPhysicalAttackBonus: return "pillPhysicalAttackBonus";
        case DiscipleColumn::PillMagicAttackBonus: return "pillMagicAttackBonus";
        case DiscipleColumn::PillPhysicalDefenseBonus: return "pillPhysicalDefenseBonus";
        case DiscipleColumn::PillMagicDefenseBonus: return "pillMagicDefenseBonus";
        case DiscipleColumn::PillHpBonus: return "pillHpBonus";''',
     '''        case DiscipleColumn::PillAttackBonus: return "pillAttackBonus";
        case DiscipleColumn::PillDefenseBonus: return "pillDefenseBonus";
        case DiscipleColumn::PillHpBonus: return "pillHpBonus";'''),
    # 序列化导出 switch 段
    ('''        case DiscipleColumn::BasePhysicalAttack:
            row["basePhysicalAttack"] = ds.basePhysicalAttacks[r];
            break;
        case DiscipleColumn::BaseMagicAttack:
            row["baseMagicAttack"] = ds.baseMagicAttacks[r];
            break;
        case DiscipleColumn::BasePhysicalDefense:
            row["basePhysicalDefense"] = ds.basePhysicalDefenses[r];
            break;
        case DiscipleColumn::BaseMagicDefense:
            row["baseMagicDefense"] = ds.baseMagicDefenses[r];
            break;
        case DiscipleColumn::BaseSpeed: row["baseSpeed"] = ds.baseSpeeds[r]; break;
        case DiscipleColumn::HpVariance: row["hpVariance"] = ds.hpVariances[r]; break;
        case DiscipleColumn::MpVariance: row["mpVariance"] = ds.mpVariances[r]; break;
        case DiscipleColumn::PhysicalAttackVariance:
            row["physicalAttackVariance"] = ds.physicalAttackVariances[r];
            break;
        case DiscipleColumn::MagicAttackVariance:
            row["magicAttackVariance"] = ds.magicAttackVariances[r];
            break;
        case DiscipleColumn::PhysicalDefenseVariance:
            row["physicalDefenseVariance"] = ds.physicalDefenseVariances[r];
            break;
        case DiscipleColumn::MagicDefenseVariance:
            row["magicDefenseVariance"] = ds.magicDefenseVariances[r];
            break;
        case DiscipleColumn::SpeedVariance: row["speedVariance"] = ds.speedVariances[r]; break;''',
     '''        case DiscipleColumn::BaseAttack: row["baseAttack"] = ds.baseAttacks[r]; break;
        case DiscipleColumn::BaseDefense: row["baseDefense"] = ds.baseDefenses[r]; break;
        case DiscipleColumn::BaseSpeed: row["baseSpeed"] = ds.baseSpeeds[r]; break;
        case DiscipleColumn::HpVariance: row["hpVariance"] = ds.hpVariances[r]; break;
        case DiscipleColumn::MpVariance: row["mpVariance"] = ds.mpVariances[r]; break;
        case DiscipleColumn::AttackVariance:
            row["attackVariance"] = ds.attackVariances[r];
            break;
        case DiscipleColumn::DefenseVariance:
            row["defenseVariance"] = ds.defenseVariances[r];
            break;
        case DiscipleColumn::InnateDamageType:
            row["innateDamageType"] = ds.innateDamageTypes[r];
            break;
        case DiscipleColumn::SpeedVariance: row["speedVariance"] = ds.speedVariances[r]; break;'''),
    ('''        case DiscipleColumn::PillPhysicalAttackBonus:
            row["pillPhysicalAttackBonus"] = ds.pillPhysicalAttackBonuses[r];
            break;
        case DiscipleColumn::PillMagicAttackBonus:
            row["pillMagicAttackBonus"] = ds.pillMagicAttackBonuses[r];
            break;
        case DiscipleColumn::PillPhysicalDefenseBonus:
            row["pillPhysicalDefenseBonus"] = ds.pillPhysicalDefenseBonuses[r];
            break;
        case DiscipleColumn::PillMagicDefenseBonus:
            row["pillMagicDefenseBonus"] = ds.pillMagicDefenseBonuses[r];
            break;
        case DiscipleColumn::PillHpBonus: row["pillHpBonus"] = ds.pillHpBonuses[r]; break;''',
     '''        case DiscipleColumn::PillAttackBonus:
            row["pillAttackBonus"] = ds.pillAttackBonuses[r];
            break;
        case DiscipleColumn::PillDefenseBonus:
            row["pillDefenseBonus"] = ds.pillDefenseBonuses[r];
            break;
        case DiscipleColumn::PillHpBonus: row["pillHpBonus"] = ds.pillHpBonuses[r]; break;'''),
])

print('column_dirty ok')
