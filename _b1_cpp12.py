# -*- coding: utf-8 -*-
"""B1 C++ 脚本 12：battle_json/determinism_probe/disciple_tx/game_core/data_json"""
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

# ── battle_json.h：Combatant JSON 编解码（对拍桥协议，与 BattleJsonCodec.kt 同键） ──
patch('include/gamecore/system/battle_json.h', [
    ('''        {"physicalAttack", c.physicalAttack}, {"magicAttack", c.magicAttack},
        {"physicalDefense", c.physicalDefense}, {"magicDefense", c.magicDefense},''',
     '''        {"attack", c.attack}, {"defense", c.defense},
        {"innateDamageType", c.innateDamageType == DamageType::kMagic ? "MAGIC" : "PHYSICAL"},
        {"physicalDamageBonus", c.physicalDamageBonus}, {"magicDamageBonus", c.magicDamageBonus},
        {"physicalDamageReduction", c.physicalDamageReduction},
        {"magicDamageReduction", c.magicDamageReduction},'''),
    ('''    c.physicalAttack = j.value("physicalAttack", 0);
    c.magicAttack = j.value("magicAttack", 0);
    c.physicalDefense = j.value("physicalDefense", 0);
    c.magicDefense = j.value("magicDefense", 0);''',
     '''    c.attack = j.value("attack", 0);
    c.defense = j.value("defense", 0);
    c.innateDamageType =
        j.value("innateDamageType", "PHYSICAL") == "MAGIC" ? DamageType::kMagic
                                                           : DamageType::kPhysical;
    c.physicalDamageBonus = j.value("physicalDamageBonus", 0.0);
    c.magicDamageBonus = j.value("magicDamageBonus", 0.0);
    c.physicalDamageReduction = j.value("physicalDamageReduction", 0.0);
    c.magicDamageReduction = j.value("magicDamageReduction", 0.0);'''),
])

# ── determinism_probe.h（找路径） ──
import glob
probes = glob.glob('**/determinism_probe.h', recursive=True)
if probes:
    pp = probes[0]
    s = rd(pp)
    s = s.replace('''    {"physicalAttack", c.physicalAttack}, {"magicAttack", c.magicAttack},
        {"physicalDefense", c.physicalDefense}, {"magicDefense", c.magicDefense},''',
'''    {"attack", c.attack}, {"defense", c.defense},''')
    s = s.replace('''d.physicalAttack''', 'd.attack').replace('d.magicAttack', 'd.defense')
    # 通用：字段直引
    s = s.replace('c.physicalAttack', 'c.attack').replace('c.magicAttack', 'c.defense')
    s = s.replace('c.physicalDefense', 'c.defense').replace('c.magicDefense', 'c.defense')
    wr(pp, s)
    print('probe ok:', pp)

# ── disciple_tx.h：丹药清零/写入段 ──
patch('include/gamecore/system/disciple_tx.h', [
    ('''ds.pillPhysicalAttackBonuses[idx] = 0;
    ds.pillMagicAttackBonuses[idx] = 0;
    ds.pillPhysicalDefenseBonuses[idx] = 0;
    ds.pillMagicDefenseBonuses[idx] = 0;''',
     '''ds.pillAttackBonuses[idx] = 0;
    ds.pillDefenseBonuses[idx] = 0;'''),
])

s = rd('include/gamecore/system/disciple_tx.h')
import re
# 其余 pillPhysicalAttackBonuses 引用（写效果段）
s2 = re.sub(r'ds\.pillPhysicalAttackBonuses\[(\w+)\] = (\w+)\.physicalAttackAdd;\s*\n\s*ds\.pillMagicAttackBonuses\[\1\] = \2\.magicAttackAdd;\s*\n\s*ds\.pillPhysicalDefenseBonuses\[\1\] = \2\.physicalDefenseAdd;\s*\n\s*ds\.pillMagicDefenseBonuses\[\1\] = \2\.magicDefenseAdd;',
            r'ds.pillAttackBonuses[\1] = \2.AttackAddTotal();\n    ds.pillDefenseBonuses[\1] = \2.DefenseAddTotal();', s)
print('tx effect write sub:', s2 != s)
wr('include/gamecore/system/disciple_tx.h', s2)

# ── game_core.cpp：DiscipleColumn 清零段 ──
patch('src/game_core.cpp', [
    ('''case DiscipleColumn::PillPhysicalAttackBonus:
    case DiscipleColumn::PillMagicAttackBonus:
    case DiscipleColumn::PillPhysicalDefenseBonus:
    case DiscipleColumn::PillMagicDefenseBonus:''',
     '''case DiscipleColumn::PillAttackBonus:
    case DiscipleColumn::PillDefenseBonus:'''),
])
# game_core.cpp 其他枚举引用
s = rd('src/game_core.cpp')
s = s.replace('case DiscipleColumn::BasePhysicalAttack:', 'case DiscipleColumn::BaseAttack:')
s = s.replace('case DiscipleColumn::BaseMagicAttack:\n        case DiscipleColumn::BasePhysicalDefense:\n        case DiscipleColumn::BaseMagicDefense:\n', '')
s = s.replace('case DiscipleColumn::BasePhysicalDefense:', 'case DiscipleColumn::BaseDefense:')
s = s.replace('case DiscipleColumn::BaseMagicDefense:\n', '')
s = s.replace('case DiscipleColumn::PhysicalAttackVariance:', 'case DiscipleColumn::AttackVariance:')
s = s.replace('case DiscipleColumn::MagicAttackVariance:\n        case DiscipleColumn::PhysicalDefenseVariance:\n        case DiscipleColumn::MagicDefenseVariance:\n', '')
s = s.replace('case DiscipleColumn::PhysicalDefenseVariance:', 'case DiscipleColumn::DefenseVariance:')
s = s.replace('case DiscipleColumn::MagicDefenseVariance:\n', '')
wr('src/game_core.cpp', s)
print('game_core ok')

print('patch12 ok')
