# -*- coding: utf-8 -*-
"""B1 批次脚本 2：战斗 Ops/试炼/战力消费链单列化"""
import os
os.chdir(r'C:\Mnzm\XianxiaSectNative-equipment\android')

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

# ── BattleSystem战斗Ops1：BeastCombatStats 单列 + createBeast/resolveBeastStats ──
p = 'core/engine/src/main/java/com/xianxia/sect/core/engine/domain/battle/BattleSystem战斗Ops1.kt'
patch(p, [
    ('''        physicalAttack = stats.physicalAttack,
        magicAttack = stats.magicAttack,
        physicalDefense = stats.physicalDefense,
        magicDefense = stats.magicDefense,
        speed = stats.speed,
        critRate = 0.05 + realmIndex * 0.01,''',
     '''        attack = stats.attack,
        defense = stats.defense,
        innateDamageType = innateType,
        speed = stats.speed,
        critRate = 0.05 + realmIndex * 0.01,'''),
    ('''        return BeastCombatStats(
            hp = s.maxHp.coerceIn(1, 10_000_000),
            mp = s.maxMp.coerceAtLeast(0),
            physicalAttack = s.physicalAttack.coerceAtLeast(0),
            magicAttack = s.magicAttack.coerceAtLeast(0),
            physicalDefense = s.physicalDefense.coerceAtLeast(0),
            magicDefense = s.magicDefense.coerceAtLeast(0),
            speed = s.speed.coerceAtLeast(0),
            realmLayer = s.realmLayer
        )''',
     '''        return BeastCombatStats(
            hp = s.maxHp.coerceIn(1, 10_000_000),
            mp = s.maxMp.coerceAtLeast(0),
            attack = s.beastAttackTotal.coerceAtLeast(0),
            defense = s.beastDefenseTotal.coerceAtLeast(0),
            speed = s.speed.coerceAtLeast(0),
            realmLayer = s.realmLayer
        )'''),
    ('''    return BeastCombatStats(
        hp = (stats.hp * layerMult * type.hpMod).toInt(),
        mp = (stats.mp * layerMult * type.hpMod).toInt(),
        physicalAttack = (stats.attack * layerMult * type.atkMod).toInt(),
        magicAttack = (stats.attack * layerMult * type.atkMod).toInt(),
        physicalDefense = (stats.defense * layerMult * type.defMod).toInt(),
        magicDefense = (stats.defense * layerMult * type.defMod).toInt(),
        speed = (stats.speed * layerMult * type.speedMod).toInt(),
        realmLayer = rl
    )''',
     '''    return BeastCombatStats(
        hp = (stats.hp * layerMult * type.hpMod).toInt(),
        mp = (stats.mp * layerMult * type.hpMod).toInt(),
        // 单列口径（B1）：物=法同源两半各自 round 后相加
        attack = (stats.attack * layerMult * type.atkMod).toInt() * 2,
        defense = (stats.defense * layerMult * type.defMod).toInt() * 2,
        speed = (stats.speed * layerMult * type.speedMod).toInt(),
        realmLayer = rl
    )'''),
])

# createBeast 段的 innateType 生成（stats 为 RealmStats 单列）——插在 Combatant 构造前
s = rd(p)
anchor = '''    val beastSkills = buildBeastSkills(type)
    val typeIndex = GameConfig.Beast.TYPES.indexOf(type)
'''
if 'val innateType' not in s:
    assert anchor in s
    s = s.replace(anchor, anchor + '''    // 妖兽伤害类型按种类元素固定（§15.3：金/土→物理、水/木/火→法术）
    val innateType =
        if (type.element == "metal" || type.element == "earth") DamageType.PHYSICAL
        else DamageType.MAGIC
''')
    wr(p, s)
if 'import com.xianxia.sect.core.DamageType' not in s:
    s = rd(p)
    s = s.replace('import com.xianxia.sect.core.CombatantSide\n',
                  'import com.xianxia.sect.core.CombatantSide\nimport com.xianxia.sect.core.DamageType\n', 1)
    wr(p, s)
print('BattleSystem战斗Ops1 ok')

# BeastCombatStats 本体（BattleSystem.kt 数据类）+ BeastPreGenStats 保留
p = 'core/engine/src/main/java/com/xianxia/sect/core/engine/domain/battle/BattleSystem.kt'
s = rd(p)
print('--- BeastPreGenStats 原文 ---')
i = s.find('data class BeastPreGenStats')
print(s[i-20:i+520])
