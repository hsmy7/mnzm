# -*- coding: utf-8 -*-
"""B1 C++ 脚本 10：重打 Combatant 单列 + effective* 删除 + buildDamageZones + 枚举前置"""
import os
import re
os.chdir(r'C:\Mnzm\XianxiaSectNative-equipment\android\app\src\main\cpp\gamecore')

def rd(p):
    return open(p, encoding='utf-8').read()

def wr(p, s):
    open(p, 'w', encoding='utf-8', newline='\n').write(s)

# ── 1) battle_calculator.h：Combatant 单列 ──
p = 'include/gamecore/system/battle_calculator.h'
s = rd(p)

old_combatant = '''struct Combatant {
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
    double critRate = 0.05;'''
new_combatant = '''struct Combatant {
    std::string id;
    std::string name;
    CombatantSide side = CombatantSide::kDefender;
    int32_t hp = 0;
    int32_t maxHp = 0;
    int32_t mp = 0;
    int32_t maxMp = 0;
    // 单列口径（B1，方案 §15）：攻防各一列；物法之分走三条通道
    //（普攻 innateDamageType / 技能 damageType / 类型增减伤分桶，默认 0 ⇒ S19）
    int32_t attack = 0;
    int32_t defense = 0;
    DamageType innateDamageType = DamageType::kPhysical;
    double physicalDamageBonus = 0.0;
    double magicDamageBonus = 0.0;
    double physicalDamageReduction = 0.0;
    double magicDamageReduction = 0.0;
    int32_t speed = 0;
    double critRate = 0.05;'''
if old_combatant in s:
    s = s.replace(old_combatant, new_combatant)
    print('Combatant replaced')
elif 'int32_t attack = 0;' in s and 'innateDamageType' in s:
    print('Combatant already new')
else:
    raise SystemExit('Combatant state unknown')

# ── 2) 删除四个 effective 物法函数 ──
for fn in ['effectivePhysicalAttack', 'effectiveMagicAttack', 'effectivePhysicalDefense', 'effectiveMagicDefense']:
    pat = re.compile(r'    int32_t ' + fn + r'\(\) const \{.*?\n    \}\n', re.S)
    s, n = pat.subn('', s)
    print(fn, 'removed x', n)
wr(p, s)

# ── 3) buildDamageZones：类型减伤桶收集 ──
s = rd(p)
old_bdz = '''/// 从 Combatant 的 Buff 列表构建战斗乘区（单遍历分桶求和——物理/魔法
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
    zones.damageReduction = dmgReduce;'''
new_bdz = '''/// 从 Combatant 的 Buff 列表构建战斗乘区（单遍历分桶求和；单列口径 B1——
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
    zones.damageReduction = dmgReduce;'''
if old_bdz in s:
    s = s.replace(old_bdz, new_bdz)
    print('buildDamageZones replaced')
else:
    print('buildDamageZones SKIP (check manually)')
wr(p, s)

# ── 4) 枚举前置到 battle.h（DamageType 在 CombatantStats 之前可见） ──
p2 = 'include/gamecore/system/battle.h'
s2 = rd(p2)
if 'enum class DamageType' not in s2:
    anchor = 'namespace gamecore::battle {'
    i = s2.find(anchor)
    assert i > 0
    insert_at = i + len(anchor)
    enums = '''
/// 伤害类型（Kotlin DamageType）——battle.h 前置定义（CombatantStats/
/// Combatant 依赖；battle_calculator.h 内不再重复定义）
enum class DamageType : int32_t { kPhysical = 0, kMagic = 1 };
'''
    s2 = s2[:insert_at] + enums + s2[insert_at:]
    wr(p2, s2)
    # battle_calculator.h 删除重复定义
    s = rd(p)
    old_enum = '''/// 技能类型（Kotlin SkillType）
enum class SkillType : int32_t { kAttack = 0, kSupport = 1 };
/// 伤害类型（Kotlin DamageType）
enum class DamageType : int32_t { kPhysical = 0, kMagic = 1 };
/// 治疗类型（Kotlin HealType）'''
    new_enum = '''/// 技能类型（Kotlin SkillType）
enum class SkillType : int32_t { kAttack = 0, kSupport = 1 };
// DamageType 已前置定义于 battle.h（单列口径 B1 依赖前置可见）
/// 治疗类型（Kotlin HealType）'''
    if old_enum in s:
        s = s.replace(old_enum, new_enum)
        wr(p, s)
        print('enum moved to battle.h')
    else:
        print('enum dedup SKIP (manual check)')
else:
    print('battle.h already has DamageType')

print('patch10 ok')
