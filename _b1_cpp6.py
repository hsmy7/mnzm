# -*- coding: utf-8 -*-
"""B1 C++ 脚本 6：json_codec ItemEffect/PillEffect/WorldLevel 段 + disciple_store SoA"""
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
            print('SKIP already/missing:', path, repr(old[:60]))
            continue
        s = s.replace(old, new)
    wr(path, s)

p = 'src/json_codec.cpp'

# ── ItemEffect 编解码（两份 GC_TO + 两份 GC_FROM） ──
s = rd(p)
# TO：四列保留 + 新两列
s = s.replace('''    GC_TO(v, j, physicalAttackAdd); GC_TO(v, j, magicAttackAdd);
    GC_TO(v, j, physicalDefenseAdd); GC_TO(v, j, magicDefenseAdd);''',
'''    GC_TO(v, j, physicalAttackAdd); GC_TO(v, j, magicAttackAdd);
    GC_TO(v, j, physicalDefenseAdd); GC_TO(v, j, magicDefenseAdd);
    GC_TO(v, j, attackAdd); GC_TO(v, j, defenseAdd);''')
# FROM：直读全部（结构保留旧字段，旧键值进旧字段；消费点用 *Total 归一化）
s = s.replace('''    GC_FROM(j, v, physicalAttackAdd); GC_FROM(j, v, magicAttackAdd);''',
'''    GC_FROM(j, v, physicalAttackAdd); GC_FROM(j, v, magicAttackAdd);
    GC_FROM(j, v, attackAdd); GC_FROM(j, v, defenseAdd);''')
wr(p, s)
print('ItemEffect codec ok')

# ── WorldLevel beast 四键 → 单键（data JSON；读旧键归一化） ──
s = rd(p)
s = s.replace('''GC_TO(v, j, beastPhysicalAttack); GC_TO(v, j, beastMagicAttack);''',
              '''GC_TO(v, j, beastAttack); GC_TO(v, j, beastDefense);''')
s = s.replace('''GC_TO(v, j, beastPhysicalDefense); GC_TO(v, j, beastMagicDefense);''', '')
s = s.replace('''GC_FROM(j, v, beastPhysicalAttack); GC_FROM(j, v, beastMagicAttack);''',
              '''GC_FROM(j, v, beastAttack); GC_FROM(j, v, beastDefense);
    v.beastAttack += j.value("beastPhysicalAttack", 0) + j.value("beastMagicAttack", 0);
    v.beastDefense += j.value("beastPhysicalDefense", 0) + j.value("beastMagicDefense", 0);''')
s = s.replace('''GC_FROM(j, v, beastPhysicalDefense); GC_FROM(j, v, beastMagicDefense);''', '')
wr(p, s)
print('WorldLevel codec ok')

# ── data_json.h：WorldLevel beast 键 ──
p2 = 'include/gamecore/data/data_json.h'
s = rd(p2)
s = s.replace('''jread(j, "physicalAttackAdd", v.physicalAttackAdd);''',
'''jread(j, "physicalAttackAdd", v.physicalAttackAdd);
    jread(j, "attackAdd", v.attackAdd);''')
s = s.replace('''{"physicalAttackAdd", v.physicalAttackAdd},''',
'''{"physicalAttackAdd", v.physicalAttackAdd}, {"attackAdd", v.attackAdd},''')
# beast 键（若在 data_json.h）
s = s.replace('jread(j, "beastPhysicalAttack", v.beastPhysicalAttack);',
              'jread(j, "beastPhysicalAttack", v.beastAttack);\n    { int32_t ma = 0; jread(j, "beastMagicAttack", ma); v.beastAttack += ma; }\n    { int32_t pd = 0; jread(j, "beastPhysicalDefense", pd); v.beastDefense += pd; }\n    { int32_t md = 0; jread(j, "beastMagicDefense", md); v.beastDefense += md; }')
s = s.replace('{"beastPhysicalAttack", v.beastPhysicalAttack},', '{"beastAttack", v.beastAttack},')
wr(p2, s)
print('data_json.h ok')

print('patch6 ok')
