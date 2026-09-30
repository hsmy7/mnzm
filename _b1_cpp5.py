# -*- coding: utf-8 -*-
"""B1 C++ 脚本 5：json_codec.cpp 存档编解码单列化（含旧档归一化）"""
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

p = 'src/json_codec.cpp'
patch(p, [
    # ── to_json（写档：只写新键） ──
    ('''    // CombatAttributes
    GC_TO(v, j, baseHp); GC_TO(v, j, baseMp);
    GC_TO(v, j, basePhysicalAttack); GC_TO(v, j, baseMagicAttack);
    GC_TO(v, j, basePhysicalDefense); GC_TO(v, j, baseMagicDefense);
    GC_TO(v, j, baseSpeed);
    GC_TO(v, j, hpVariance); GC_TO(v, j, mpVariance);
    GC_TO(v, j, physicalAttackVariance); GC_TO(v, j, magicAttackVariance);
    GC_TO(v, j, physicalDefenseVariance); GC_TO(v, j, magicDefenseVariance);
    GC_TO(v, j, speedVariance);''',
     '''    // CombatAttributes（单列口径 B1：只写新键；旧物法双列键退役不再写出）
    GC_TO(v, j, baseHp); GC_TO(v, j, baseMp);
    GC_TO(v, j, baseAttack); GC_TO(v, j, baseDefense);
    GC_TO(v, j, baseSpeed);
    GC_TO(v, j, hpVariance); GC_TO(v, j, mpVariance);
    GC_TO(v, j, attackVariance); GC_TO(v, j, defenseVariance);
    GC_TO(v, j, innateDamageType);
    GC_TO(v, j, speedVariance);'''),
    ('''    // PillEffects
    GC_TO(v, j, pillPhysicalAttackBonus); GC_TO(v, j, pillMagicAttackBonus);
    GC_TO(v, j, pillPhysicalDefenseBonus); GC_TO(v, j, pillMagicDefenseBonus);
    GC_TO(v, j, pillHpBonus); GC_TO(v, j, pillMpBonus);''',
     '''    // PillEffects（单列口径 B1：只写新键）
    GC_TO(v, j, pillAttackBonus); GC_TO(v, j, pillDefenseBonus);
    GC_TO(v, j, pillHpBonus); GC_TO(v, j, pillMpBonus);'''),
    # ── from_json（读档：旧物法双列键归一化取和/均值——与 DiscipleSerializer、
    #    Room v62 迁移回填同口径） ──
    ('''    // CombatAttributes
    GC_FROM(j, v, baseHp); GC_FROM(j, v, baseMp);
    GC_FROM(j, v, basePhysicalAttack); GC_FROM(j, v, baseMagicAttack);
    GC_FROM(j, v, basePhysicalDefense); GC_FROM(j, v, baseMagicDefense);
    GC_FROM(j, v, baseSpeed);
    GC_FROM(j, v, hpVariance); GC_FROM(j, v, mpVariance);
    GC_FROM(j, v, physicalAttackVariance); GC_FROM(j, v, magicAttackVariance);
    GC_FROM(j, v, physicalDefenseVariance); GC_FROM(j, v, magicDefenseVariance);
    GC_FROM(j, v, speedVariance);''',
     '''    // CombatAttributes（单列口径 B1：新键直读；旧档物法双列键取和/均值归一化，
    // 与 DiscipleSerializer 读面、Room v62 迁移回填逐条同口径）
    GC_FROM(j, v, baseHp); GC_FROM(j, v, baseMp);
    GC_FROM(j, v, baseAttack); GC_FROM(j, v, baseDefense);
    GC_FROM(j, v, baseSpeed);
    GC_FROM(j, v, hpVariance); GC_FROM(j, v, mpVariance);
    GC_FROM(j, v, attackVariance); GC_FROM(j, v, defenseVariance);
    GC_FROM(j, v, speedVariance);
    GC_FROM(j, v, innateDamageType);
    v.baseAttack += j.value("basePhysicalAttack", 0) + j.value("baseMagicAttack", 0);
    v.baseDefense += j.value("basePhysicalDefense", 0) + j.value("baseMagicDefense", 0);
    v.attackVariance += (j.value("physicalAttackVariance", 0) +
                         j.value("magicAttackVariance", 0)) / 2;
    v.defenseVariance += (j.value("physicalDefenseVariance", 0) +
                          j.value("magicDefenseVariance", 0)) / 2;'''),
    ('''    // PillEffects
    GC_FROM(j, v, pillPhysicalAttackBonus); GC_FROM(j, v, pillMagicAttackBonus);
    GC_FROM(j, v, pillPhysicalDefenseBonus); GC_FROM(j, v, pillMagicDefenseBonus);
    GC_FROM(j, v, pillHpBonus); GC_FROM(j, v, pillMpBonus);''',
     '''    // PillEffects（单列口径 B1：新键直读 + 旧四列归一化）
    GC_FROM(j, v, pillAttackBonus); GC_FROM(j, v, pillDefenseBonus);
    GC_FROM(j, v, pillHpBonus); GC_FROM(j, v, pillMpBonus);
    v.pillAttackBonus += j.value("pillPhysicalAttackBonus", 0) +
                         j.value("pillMagicAttackBonus", 0);
    v.pillDefenseBonus += j.value("pillPhysicalDefenseBonus", 0) +
                          j.value("pillMagicDefenseBonus", 0);'''),
])

print('json_codec part1 ok')

# ItemEffect / PillEffect 编解码（旧四键归一化 + 新键直读）
s = rd(p)
# 找 ItemEffect 的 GC_TO/GC_FROM（1061/1080/1119/1136 行附近，两份：ItemEffect + PillEffect）
old_to = '''    GC_TO(v, j, physicalAttackAdd); GC_TO(v, j, magicAttackAdd);'''
# 查看出错前后的上下文行
i = s.find('GC_TO(v, j, physicalAttackAdd)')
print('--- ItemEffect GC_TO 上下文 ---')
print(s[i-200:i+400])
