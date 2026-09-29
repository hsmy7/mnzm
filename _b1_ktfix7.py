# -*- coding: utf-8 -*-
"""B1：第三轮收尾（CultivationCore tables 列名/生产结算还原/Diff* 桥 JSON 键）"""
import os
import re
os.chdir(r'C:\Mnzm\XianxiaSectNative-equipment\android')

def rd(p):
    return open(p, encoding='utf-8').read()

def wr(p, s):
    open(p, 'w', encoding='utf-8', newline='\n').write(s)

def patch(path, subs):
    s = rd(path)
    for old, new in subs:
        if old not in s:
            print('SKIP:', path, repr(old[:60]))
            continue
        s = s.replace(old, new)
    wr(path, s)

# 1) CultivationCoreTest：tables 列名
patch('core/engine/src/test/java/com/xianxia/sect/core/engine/service/CultivationCoreTest.kt', [
    ('''tables.pillPhysicalAttackBonuses[id] = 0
    tables.pillMagicAttackBonuses[id] = 0''',
     '''tables.pillAttackBonuses[id] = 0'''),
])
s = rd('core/engine/src/test/java/com/xianxia/sect/core/engine/service/CultivationCoreTest.kt')
s = re.sub(r'tables\.pillPhysicalAttackBonuses\[(\w+)\]', r'tables.pillAttackBonuses[\1]', s)
s = re.sub(r'tables\.pillMagicAttackBonuses\[(\w+)\]', '', s)
s = re.sub(r'tables\.pillPhysicalDefenseBonuses\[(\w+)\]', r'tables.pillDefenseBonuses[\1]', s)
s = re.sub(r'tables\.pillMagicDefenseBonuses\[(\w+)\]', '', s)
wr('core/engine/src/test/java/com/xianxia/sect/core/engine/service/CultivationCoreTest.kt', s)
print('cultivation ok')

# 2) ProductionProcessorSettlementTest：还原被删行
p = 'core/engine/src/test/java/com/xianxia/sect/core/engine/service/ProductionProcessorSettlementTest.kt'
s = rd(p)
print(open(p).read().split('\n')[104:114])
