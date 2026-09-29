# -*- coding: utf-8 -*-
"""B1 C++ 脚本 13：recipe_db/data_json PillRecipeTemplate + tx PillEffect 辅助 + 探索/月结/旬结"""
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

# ── 1) models.h：PillEffect 补 *Total 辅助（上次未落） ──
p = 'include/gamecore/state/models.h'
s = rd(p)
i = s.find('struct PillEffect')
j = s.find('\n};', i)
if i > 0 and 'AttackAddTotal' not in s[i:j]:
    helper = '''
    /// 有效攻击加成：新单列值 + 旧物法两列归一化
    int32_t AttackAddTotal() const { return attackAdd + physicalAttackAdd + magicAttackAdd; }
    /// 有效防御加成：口径同 AttackAddTotal
    int32_t DefenseAddTotal() const { return defenseAdd + physicalDefenseAdd + magicDefenseAdd; }
'''
    s = s[:j] + helper + s[j:]
    wr(p, s)
    print('PillEffect helper added')
else:
    print('PillEffect helper already/missing-struct')

# ── 2) recipe_db.h：PillTemplate/PillRecipeTemplate 结构加新字段 ──
p = 'include/gamecore/data/recipe_db.h'
s = rd(p)
n = s.count('    int32_t physicalAttackAdd = 0;')
print('recipe_db physicalAttackAdd count:', n)
s = s.replace('''    int32_t physicalAttackAdd = 0;
    int32_t magicAttackAdd = 0;
    int32_t physicalDefenseAdd = 0;
    int32_t magicDefenseAdd = 0;''',
'''    int32_t physicalAttackAdd = 0;
    int32_t magicAttackAdd = 0;
    int32_t physicalDefenseAdd = 0;
    int32_t magicDefenseAdd = 0;
    // 单列口径（B1）：物法攻合并 attackAdd、物法防合并 defenseAdd
    int32_t attackAdd = 0;
    int32_t defenseAdd = 0;''')
s = s.replace('    e.physicalAttackAdd = tpl.physicalAttackAdd;',
              '''    e.physicalAttackAdd = tpl.physicalAttackAdd;
    e.attackAdd = tpl.attackAdd;
    e.defenseAdd = tpl.defenseAdd;''')
s = s.replace('    r.physicalAttackAdd = t.physicalAttackAdd;',
              '''    r.physicalAttackAdd = t.physicalAttackAdd;
    r.attackAdd = t.attackAdd;
    r.defenseAdd = t.defenseAdd;''')
wr(p, s)
print('recipe_db ok')

# ── 3) data_json.h：PillRecipeTemplate 键 ──
p = 'include/gamecore/data/data_json.h'
s = rd(p)
s = s.replace('jread(j, "physicalAttackAdd", v.physicalAttackAdd);',
              '''jread(j, "physicalAttackAdd", v.physicalAttackAdd);
    jread(j, "attackAdd", v.attackAdd);
    jread(j, "defenseAdd", v.defenseAdd);''')
s = s.replace('{"physicalAttackAdd", v.physicalAttackAdd},',
              '''{"physicalAttackAdd", v.physicalAttackAdd}, {"attackAdd", v.attackAdd},
                       {"defenseAdd", v.defenseAdd},''')
wr(p, s)
print('data_json ok')

print('patch13 ok')
