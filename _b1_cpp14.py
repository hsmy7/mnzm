# -*- coding: utf-8 -*-
"""B1 C++ 脚本 14：sect_power 战力单列 + PillEffect helper + 探索/月结/旬结"""
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

# ── sect_power.h：战力公式单列（k=1 线性等价，与 Kotlin 同式） ──
p = 'include/gamecore/system/sect_power.h'
patch(p, [
    ('''/// 弟子战力（Kotlin calculateDiscipleCombatPower；stats 为永久基础属性）
inline int64_t discipleCombatPower(int32_t physicalAttack, int32_t magicAttack,
                                   int32_t maxHp, int32_t physicalDefense,
                                   int32_t magicDefense, int32_t speed) {
    return (static_cast<int64_t>(physicalAttack) + static_cast<int64_t>(magicAttack)) * 5 +
           static_cast<int64_t>(maxHp) * 4 +
           (static_cast<int64_t>(physicalDefense) + static_cast<int64_t>(magicDefense)) * 3 +
           static_cast<int64_t>(speed) * 2;
}

/// 弟子战力（含星级乘区；先求加权和再乘、最后向零截断——Kotlin 同式）
inline int64_t discipleCombatPowerWithStar(int32_t physicalAttack, int32_t magicAttack,
                                           int32_t maxHp, int32_t physicalDefense,
                                           int32_t magicDefense, int32_t speed,
                                           int32_t star) {
    const int64_t base = discipleCombatPower(physicalAttack, magicAttack, maxHp,
                                             physicalDefense, magicDefense, speed);
    return static_cast<int64_t>(static_cast<double>(base) * starZoneOf(star).battleMult);
}''',
     '''/// 弟子战力（Kotlin calculateDiscipleCombatPower；stats 为永久基础属性；
/// 单列口径 B1——旧双列入参口径 = (物攻+法攻)，与单列 attack 线性等价，
/// 与 Kotlin SectCombatPowerCalculator.attack 单参版同式）
inline int64_t discipleCombatPower(int32_t attack, int32_t maxHp,
                                   int32_t defense, int32_t speed) {
    return static_cast<int64_t>(attack) * 5 +
           static_cast<int64_t>(maxHp) * 4 +
           static_cast<int64_t>(defense) * 3 +
           static_cast<int64_t>(speed) * 2;
}

/// 弟子战力（含星级乘区；先求加权和再乘、最后向零截断——Kotlin 同式）
inline int64_t discipleCombatPowerWithStar(int32_t attack, int32_t maxHp,
                                           int32_t defense, int32_t speed,
                                           int32_t star) {
    const int64_t base = discipleCombatPower(attack, maxHp, defense, speed);
    return static_cast<int64_t>(static_cast<double>(base) * starZoneOf(star).battleMult);
}'''),
])
s = rd(p)
# beastCombatPower 单列
i = s.find('inline int64_t beastCombatPower')
j = s.find('}', s.find('return', i))
print('beastCombatPower 段：')
print(s[i:j+2])
