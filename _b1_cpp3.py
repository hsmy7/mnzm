# -*- coding: utf-8 -*-
"""B1 C++ 脚本 3：battle_calculator.h 战斗公式单列 + 类型桶（与 Kotlin 同构）"""
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

p = 'include/gamecore/system/battle_calculator.h'
patch(p, [
    # ── Combatant 单列 + 类型通道 ──
    ('''/// 战斗单位（Kotlin Combatant——含 effective* 计算属性）
struct Combatant {
    std::string id;
    std::string name;
    CombatantSide side = CombatantSide::kDefender;
    int32_t hp = 0;
    int32_t maxHp = 0;
    int32_t mp = 0;
    int32_t maxMp = 0;
    int32_t physicalAttack = 0;
    int32_t magicAttack = 0;
    int32_t physicalDefense = 0;
    int32_t magicDefense = 0;
    int32_t speed = 0;
    double critRate = 0.05;''',
     '''/// 战斗单位（Kotlin Combatant；单列口径 B1，方案 §15）
/// 攻防各一列；物法之分由三条通道承载：普攻 innateDamageType、技能 damageType、
/// 类型增伤/减伤分桶（默认 0.0 ⇒ 与旧公式逐位一致，S19）
struct Combatant {
    std::string id;
    std::string name;
    CombatantSide side = CombatantSide::kDefender;
    int32_t hp = 0;
    int32_t maxHp = 0;
    int32_t mp = 0;
    int32_t maxMp = 0;
    int32_t attack = 0;
    int32_t defense = 0;
    DamageType innateDamageType = DamageType::kPhysical;
    double physicalDamageBonus = 0.0;      // 类型增伤桶（攻方；B1 默认 0）
    double magicDamageBonus = 0.0;
    double physicalDamageReduction = 0.0;  // 类型减伤桶（守方；B1 默认 0）
    double magicDamageReduction = 0.0;
    int32_t speed = 0;
    double critRate = 0.05;'''),
])

# 删除 effectivePhysical*/effectiveMagic* 四个计算属性
s = rd(p)
import re
for fn in ['effectivePhysicalAttack', 'effectiveMagicAttack', 'effectivePhysicalDefense', 'effectiveMagicDefense']:
    pat = re.compile(r'    int32_t ' + fn + r'\(\) const \{.*?\n    \}\n', re.S)
    s, n = pat.subn('', s)
    assert n == 1, fn
wr(p, s)
print('effective* removed')

# ── DamageZones 与公式段 ──
patch(p, [
    ('''/// 从 Combatant 的 Buff 列表构建战斗乘区（单遍历分桶求和——物理/魔法
/// 互不干扰 + 增伤桶；防守方 DAMAGE_REDUCTION 求和 + 境界三因子）
inline DamageZones buildDamageZones(const Combatant& attacker,
                                    const Combatant* defender = nullptr,
                                    double extraAmplification = 0.0) {
    double physBoost = 0.0, physReduce = 0.0, magBoost = 0.0, magReduce = 0.0;
    double dmgBoost = 0.0;
    for (const auto& buff : attacker.buffs) {
        switch (buff.type) {
            case BuffType::kPhysicalAttackBoost: physBoost += buff.value; break;
            case BuffType::kPhysicalAttackReduce: physReduce += buff.value; break;
            case BuffType::kMagicAttackBoost: magBoost += buff.value; break;
            case BuffType::kMagicAttackReduce: magReduce += buff.value; break;
            case BuffType::kDamageBoost: dmgBoost += buff.value; break;
            default: break;
        }
    }
    double dmgReduce = 0.0;
    if (defender) {
        for (const auto& buff : defender->buffs) {
            if (buff.type == BuffType::kDamageReduction) dmgReduce += buff.value;
        }
    }
    // 境界压制因子（Kotlin realmGapFactorsOf）
    DamageZones zones;
    zones.physicalAttackBuffs = physBoost - physReduce;
    zones.magicAttackBuffs = magBoost - magReduce;
    zones.damageAmplification = dmgBoost + extraAmplification;
    zones.damageReduction = dmgReduce;''',
     '''/// 从 Combatant 的 Buff 列表构建战斗乘区（单遍历分桶求和；单列口径 B1——
/// 物法攻 buff 迁移为类型增伤分桶、物法防 buff 迁移为类型减伤分桶）
inline DamageZones buildDamageZones(const Combatant& attacker,
                                    const Combatant* defender = nullptr,
                                    double extraAmplification = 0.0) {
    double physBoost = 0.0, physReduce = 0.0, magBoost = 0.0, magReduce = 0.0;
    double physDefBoost = 0.0, physDefReduce = 0.0, magDefBoost = 0.0, magDefReduce = 0.0;
    double dmgBoost = 0.0;
    for (const auto& buff : attacker.buffs) {
        switch (buff.type) {
            case BuffType::kPhysicalAttackBoost: physBoost += buff.value; break;
            case BuffType::kPhysicalAttackReduce: physReduce += buff.value; break;
            case BuffType::kMagicAttackBoost: magBoost += buff.value; break;
            case BuffType::kMagicAttackReduce: magReduce += buff.value; break;
            case BuffType::kDamageBoost: dmgBoost += buff.value; break;
            default: break;
        }
    }
    double dmgReduce = 0.0;
    if (defender) {
        for (const auto& buff : defender->buffs) {
            switch (buff.type) {
                case BuffType::kDamageReduction: dmgReduce += buff.value; break;
                case BuffType::kPhysicalDefenseBoost: physDefBoost += buff.value; break;
                case BuffType::kPhysicalDefenseReduce: physDefReduce += buff.value; break;
                case BuffType::kMagicDefenseBoost: magDefBoost += buff.value; break;
                case BuffType::kMagicDefenseReduce: magDefReduce += buff.value; break;
                default: break;
            }
        }
    }
    // 境界压制因子（Kotlin realmGapFactorsOf）
    DamageZones zones;
    zones.physicalAttackBuffs = physBoost - physReduce;
    zones.magicAttackBuffs = magBoost - magReduce;
    zones.physicalDefenseBuffs = physDefBoost - physDefReduce;
    zones.magicDefenseBuffs = magDefBoost - magDefReduce;
    zones.damageAmplification = dmgBoost + extraAmplification;
    zones.damageReduction = dmgReduce;'''),
])

print('battle_calculator part1 ok')
