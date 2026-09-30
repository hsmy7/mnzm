# -*- coding: utf-8 -*-
"""B1 批次脚本 1：妖兽面单列化（WorldLevel/LevelGenerator/ExplorationService）"""
def rd(p):
    return open(p, encoding='utf-8').read()

def wr(p, s):
    open(p, 'w', encoding='utf-8', newline='\n').write(s)

def patch(path, subs):
    s = rd(path)
    for old, new in subs:
        if old not in s:
            raise SystemExit('NOT FOUND in %s:\n%s' % (path, old[:150]))
        s = s.replace(old, new)
    wr(path, s)

OLD_WORLDLEVEL = '''    @ProtoNumber(18) val beastMaxHp: Int = 0,
    @ProtoNumber(19) val beastMaxMp: Int = 0,
    @ProtoNumber(20) val beastPhysicalAttack: Int = 0,
    @ProtoNumber(21) val beastMagicAttack: Int = 0,
    @ProtoNumber(22) val beastPhysicalDefense: Int = 0,
    @ProtoNumber(23) val beastMagicDefense: Int = 0,
    @ProtoNumber(24) val beastSpeed: Int = 0
) {'''

NEW_WORLDLEVEL = '''    // 单列口径（B1，方案 §15.4）：beastAttack/beastDefense 各一列；旧物法四列
    // （20-23）保留声明仅作旧档归一化读取（worldLevels 存 TEXT 列，字段名即 JSON 键），
    // 消费点一律读 [beastAttackTotal]/[beastDefenseTotal]。
    @ProtoNumber(18) val beastMaxHp: Int = 0,
    @ProtoNumber(19) val beastMaxMp: Int = 0,
    @Deprecated("旧妖兽物攻，仅旧档归一化读取；改用 beastAttackTotal")
    @ProtoNumber(20) val beastPhysicalAttack: Int = 0,
    @Deprecated("旧妖兽法攻，仅旧档归一化读取")
    @ProtoNumber(21) val beastMagicAttack: Int = 0,
    @Deprecated("旧妖兽物防，仅旧档归一化读取")
    @ProtoNumber(22) val beastPhysicalDefense: Int = 0,
    @Deprecated("旧妖兽法防，仅旧档归一化读取")
    @ProtoNumber(23) val beastMagicDefense: Int = 0,
    @ProtoNumber(25) val beastAttack: Int = 0,
    @ProtoNumber(26) val beastDefense: Int = 0,
    @ProtoNumber(24) val beastSpeed: Int = 0
) {
    /** 有效妖兽攻击：新单列值 + 旧物法两列归一化（旧 JSON 20/21 有值、新档恒 0） */
    val beastAttackTotal: Int get() = beastAttack + beastPhysicalAttack + beastMagicAttack

    /** 有效妖兽防御：口径同 [beastAttackTotal] */
    val beastDefenseTotal: Int get() = beastDefense + beastPhysicalDefense + beastMagicDefense'''

patch('core/domain/src/main/java/com/xianxia/sect/core/model/WorldLevel.kt', [
    (OLD_WORLDLEVEL, NEW_WORLDLEVEL),
])

OLD_GEN = '''            beastPhysicalAttack = atk,
            beastMagicAttack = atk,
            beastPhysicalDefense = defValue,
            beastMagicDefense = defValue,
            beastSpeed = speed'''

NEW_GEN = '''            // 单列口径（B1）：物=法同源两半各自 round 后相加（= 旧两列相加的等价值）
            beastAttack = atk + atk,
            beastDefense = defValue + defValue,
            beastSpeed = speed'''

patch('core/engine/src/main/java/com/xianxia/sect/core/engine/domain/exploration/LevelGenerator.kt', [
    (OLD_GEN, NEW_GEN),
])

OLD_EXPLORE = '''            physicalAttack = level.beastPhysicalAttack,
            magicAttack = level.beastMagicAttack,
            physicalDefense = level.beastPhysicalDefense,
            magicDefense = level.beastMagicDefense,'''

NEW_EXPLORE = '''            attack = level.beastAttackTotal,
            defense = level.beastDefenseTotal,'''

patch('core/engine/src/main/java/com/xianxia/sect/core/engine/domain/exploration/ExplorationService.kt', [
    (OLD_EXPLORE, NEW_EXPLORE),
])

print('beast chain ok')
