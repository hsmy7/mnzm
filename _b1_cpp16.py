# -*- coding: utf-8 -*-
"""B1 C++ 脚本 16：test/ 逐文件语义迁移（Combatant 单列取主值；装备面保留）"""
import os
import re
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

# ── battle_calculator_test.cpp：Combatant 成对赋值 → 单列（取和；期望值随 reduction 非线性需重算——
#    策略：fixture 物攻/物防取旧主值，法攻/法防行删除，期望值不变） ──
patch('test/battle_calculator_test.cpp', [
    ('''    c.physicalAttack = 120;
    c.magicAttack = 100;
    c.physicalDefense = 60;
    c.magicDefense = 50;''',
     '''    // 单列口径（B1）：fixture 取旧主值（伤害公式行为不变，期望值沿用）
    c.attack = 120;
    c.defense = 60;'''),
])

# ── battle_ai_test.cpp ──
patch('test/battle_ai_test.cpp', [
    ('''    c.physicalAttack = 120;
    c.magicAttack = 100;
    c.physicalDefense = 60;
    c.magicDefense = 50;''',
     '''    c.attack = 120;
    c.defense = 60;'''),
    ('    enemy1.physicalAttack = 200;', '    enemy1.attack = 200;'),
    ('    enemy2.physicalAttack = 100;', '    enemy2.attack = 100;'),
    ('''    enemy1.physicalAttack = 300;
    enemy1.physicalDefense = 20;''',
     '''    enemy1.attack = 300;
    enemy1.defense = 20;'''),
    ('''    enemy2.physicalAttack = 50;
    enemy2.physicalDefense = 90;''',
     '''    enemy2.attack = 50;
    enemy2.defense = 90;'''),
])

# ── battle_execution_test.cpp：逐对替换（取主值，期望值不变） ──
p = 'test/battle_execution_test.cpp'
s = rd(p)
pairs = [
    ('''    c.physicalAttack = 120;
    c.magicAttack = 100;
    c.physicalDefense = 60;
    c.magicDefense = 50;''', '''    c.attack = 120;
    c.defense = 60;'''),
    ('''        d2.physicalAttack = 180;
        d2.physicalDefense = 90;''', '''        d2.attack = 180;
        d2.defense = 90;'''),
    ('    b1.physicalAttack = 150;', '    b1.attack = 150;'),
    ('        b2.magicAttack = 140;', '        b2.attack = 140;'),
    ('    d1.physicalAttack = 500;', '    d1.attack = 500;'),
    ('    b1.physicalDefense = 20;', '    b1.defense = 20;'),
    ('''    d1.physicalDefense = 100000;
    d1.magicDefense = 100000;''', '''    d1.defense = 100000;'''),
    ('''    b1.physicalAttack = 1;
    b1.magicAttack = 1;
    b1.physicalDefense = 100000;
    b1.magicDefense = 100000;''', '''    b1.attack = 1;
    b1.defense = 100000;'''),
]
for old, new in pairs:
    if old in s:
        s = s.replace(old, new)
    else:
        print('SKIP exec:', repr(old[:50]))
wr(p, s)
print('battle_execution ok')

# ── mission_completion_test.cpp ──
patch('test/mission_completion_test.cpp', [
    ('''    EXPECT_EQ(beast.physicalAttack, 148);
    EXPECT_EQ(beast.magicAttack, 148);
    EXPECT_EQ(beast.physicalDefense, 55);
    EXPECT_EQ(beast.magicDefense, 55);''',
     '''    // 单列口径（B1）：妖兽物=法同源两半相加（148×2 / 55×2）
    EXPECT_EQ(beast.attack, 296);
    EXPECT_EQ(beast.defense, 110);'''),
    ('    EXPECT_EQ(e1[0].physicalAttack, e2[0].physicalAttack);',
     '    EXPECT_EQ(e1[0].attack, e2[0].attack);'),
])

# ── json_codec_test.cpp：EquipmentStats 单列 ──
patch('test/json_codec_test.cpp', [
    ('''    es.physicalAttack = 12;
    es.magicAttack = 3;''',
     '''    es.attack = 15;'''),
])

# ── secret_realm_test.cpp ──
patch('test/secret_realm_test.cpp', [
    ('''    EXPECT_GE(stats.physicalAttack, 1);
    EXPECT_EQ(stats.physicalAttack, stats.magicAttack);   // Kotlin atk 两用
    EXPECT_EQ(stats.physicalDefense, stats.magicDefense); // Kotlin def 两用''',
     '''    // 单列口径（B1）：物=法同源两半相加（≥2 = 单半 ≥1）
    EXPECT_GE(stats.attack, 2);
    EXPECT_GE(stats.defense, 2);'''),
])

# ── disciple_factory_test.cpp：方差断言单列（跑后钉值，先改结构） ──
p = 'test/disciple_factory_test.cpp'
s = rd(p)
s = s.replace('''    EXPECT_EQ(a.physicalAttackVariance, b.physicalAttackVariance);
    EXPECT_EQ(a.magicAttackVariance, b.magicAttackVariance);
    EXPECT_EQ(a.physicalDefenseVariance, b.physicalDefenseVariance);
    EXPECT_EQ(a.magicDefenseVariance, b.magicDefenseVariance);''',
'''    EXPECT_EQ(a.attackVariance, b.attackVariance);
    EXPECT_EQ(a.defenseVariance, b.defenseVariance);''')
s = s.replace('''    EXPECT_EQ(10, d.physicalAttackVariance);
    EXPECT_EQ(16, d.magicAttackVariance);
    EXPECT_EQ(8, d.physicalDefenseVariance);
    EXPECT_EQ(6, d.magicDefenseVariance);''',
'''    EXPECT_EQ(10, d.attackVariance);
    EXPECT_EQ(8, d.defenseVariance);''')
s = s.replace('''    EXPECT_EQ(-9, d.physicalAttackVariance);
    EXPECT_EQ(-9, d.magicAttackVariance);''',
'''    EXPECT_EQ(-9, d.attackVariance);''')
wr(p, s)
print('factory test ok')

# ── phase_settlement_test.cpp：丹药效果写新字段 ──
patch('test/phase_settlement_test.cpp', [
    ('    battlePill.effect->physicalAttackAdd = 20;', '    battlePill.effect->attackAdd = 20;'),
])

print('patch16 ok')
