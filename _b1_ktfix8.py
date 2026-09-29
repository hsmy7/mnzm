# -*- coding: utf-8 -*-
"""B1：Diff* 桥测试 JSON 键迁移（physicalAttack → attack 等，与 C++ battle_json.h 同键）"""
import os
import re
os.chdir(r'C:\Mnzm\XianxiaSectNative-equipment\android')

def rd(p):
    return open(p, encoding='utf-8').read()

def wr(p, s):
    open(p, 'w', encoding='utf-8', newline='\n').write(s)

files = [
    'core/engine/src/test/java/com/xianxia/sect/core/nativebridge/DiffBattleAITest.kt',
    'core/engine/src/test/java/com/xianxia/sect/core/nativebridge/DiffBattleExecutionTest.kt',
    'core/engine/src/test/java/com/xianxia/sect/core/nativebridge/DiffBattleTest.kt',
    'core/engine/src/test/java/com/xianxia/sect/core/nativebridge/DiffDiscipleTest.kt',
    'core/engine/src/test/java/com/xianxia/sect/core/nativebridge/DiffSectBattleTest.kt',
    'core/engine/src/test/java/com/xianxia/sect/core/nativebridge/DiffSectDiplomacyTest.kt',
    'core/engine/src/test/java/com/xianxia/sect/core/nativebridge/DiffStateTest.kt',
    'core/engine/src/test/java/com/xianxia/sect/core/nativebridge/MirrorProtoFeedFixture.kt',
    'core/engine/src/test/java/com/xianxia/sect/core/nativebridge/DiffDiscipleFactoryTest.kt',
    'core/engine/src/test/java/com/xianxia/sect/core/nativebridge/DiffExecuteTest.kt',
]

# JSON 键（引号内）→ 新键；Kotlin put("physicalAttack", x) → put("attack", x)
JSON_KEY_MAP = {
    '"physicalAttack"': '"attack"',
    '"magicAttack"': None,   # 同键合并——单独处理
    '"physicalDefense"': '"defense"',
    '"magicDefense"': None,
    '"physicalAttackVariance"': '"attackVariance"',
    '"magicAttackVariance"': None,
    '"physicalDefenseVariance"': '"defenseVariance"',
    '"magicDefenseVariance"': None,
    '"basePhysicalAttack"': '"baseAttack"',
    '"baseMagicAttack"': None,
    '"basePhysicalDefense"': '"baseDefense"',
    '"baseMagicDefense"': None,
    '"pillPhysicalAttackBonus"': '"pillAttackBonus"',
    '"pillMagicAttackBonus"': None,
    '"pillPhysicalDefenseBonus"': '"pillDefenseBonus"',
    '"pillMagicDefenseBonus"': None,
}

for f in files:
    try:
        s = rd(f)
    except FileNotFoundError:
        print('missing:', f)
        continue
    orig = s
    # put("physicalAttack", A) / put("magicAttack", B) 相邻对 → put("attack", A)（删 B 行）
    s = re.sub(r'put\("physicalAttack",\s*([^\n]+?)\)\s*\n(\s*)put\("magicAttack",\s*[^\n]+?\)\s*\n',
               r'put("attack", \1)\n', s)
    s = re.sub(r'put\("physicalDefense",\s*([^\n]+?)\)\s*\n(\s*)put\("magicDefense",\s*[^\n]+?\)\s*\n',
               r'put("defense", \1)\n', s)
    s = re.sub(r'put\("physicalAttackVariance",\s*([^\n]+?)\)\s*\n(\s*)put\("magicAttackVariance",\s*[^\n]+?\)\s*\n',
               r'put("attackVariance", \1)\n', s)
    s = re.sub(r'put\("physicalDefenseVariance",\s*([^\n]+?)\)\s*\n(\s*)put\("magicDefenseVariance",\s*[^\n]+?\)\s*\n',
               r'put("defenseVariance", \1)\n', s)
    s = re.sub(r'put\("basePhysicalAttack",\s*([^\n]+?)\)\s*\n(\s*)put\("baseMagicAttack",\s*[^\n]+?\)\s*\n',
               r'put("baseAttack", \1)\n', s)
    s = re.sub(r'put\("basePhysicalDefense",\s*([^\n]+?)\)\s*\n(\s*)put\("baseMagicDefense",\s*[^\n]+?\)\s*\n',
               r'put("baseDefense", \1)\n', s)
    s = re.sub(r'put\("pillPhysicalAttackBonus",\s*([^\n]+?)\)\s*\n(\s*)put\("pillMagicAttackBonus",\s*[^\n]+?\)\s*\n',
               r'put("pillAttackBonus", \1)\n', s)
    s = re.sub(r'put\("pillPhysicalDefenseBonus",\s*([^\n]+?)\)\s*\n(\s*)put\("pillMagicDefenseBonus",\s*[^\n]+?\)\s*\n',
               r'put("pillDefenseBonus", \1)\n', s)
    # 单键直改（buildJsonObject put / json object 字面量）
    s = s.replace('"physicalAttack"', '"attack"')
    s = s.replace('"physicalDefense"', '"defense"')
    s = s.replace('"physicalAttackVariance"', '"attackVariance"')
    s = s.replace('"physicalDefenseVariance"', '"defenseVariance"')
    s = s.replace('"basePhysicalAttack"', '"baseAttack"')
    s = s.replace('"basePhysicalDefense"', '"baseDefense"')
    s = s.replace('"pillPhysicalAttackBonus"', '"pillAttackBonus"')
    s = s.replace('"pillPhysicalDefenseBonus"', '"pillDefenseBonus"')
    s = s.replace('"innateDamageType"', '"innateDamageType"')
    # 残留 magic* 键行删除（值已由主键承载）
    s = re.sub(r'\s*put\("(magicAttack|magicDefense|magicAttackVariance|magicDefenseVariance|baseMagicAttack|baseMagicDefense|pillMagicAttackBonus|pillMagicDefenseBonus)",\s*[^\n]+?\)\n', '\n', s)
    # Kotlin 具名参数（combatantCopy / Combatant 构造残留）
    s = re.sub(r'([ \t]*)physicalAttack = ([^\n]+?),\s*\n[ \t]*magicAttack = [^\n]+?,\s*\n', r'\1attack = \2,\n', s)
    s = re.sub(r'([ \t]*)physicalDefense = ([^\n]+?),\s*\n[ \t]*magicDefense = [^\n]+?,\s*\n', r'\1defense = \2,\n', s)
    s = re.sub(r'\bphysicalAttack = ', 'attack = ', s)
    s = re.sub(r'\bmagicAttack = [^\n]+\n', '\n', s)
    s = re.sub(r'\bphysicalDefense = ', 'defense = ', s)
    s = re.sub(r'\bmagicDefense = [^\n]+\n', '\n', s)
    if s != orig:
        wr(f, s)
        print('migrated:', f)
