# -*- coding: utf-8 -*-
"""B1 C++ 脚本 9：disciple_factory.h + ai_sect_recruit.h 生成链单列"""
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

# ── disciple_factory.h ──
patch('include/gamecore/system/disciple_factory.h', [
    ('''struct DiscipleRolls {
    int32_t hpVariance = 0;
    int32_t mpVariance = 0;
    int32_t physicalAttackVariance = 0;
    int32_t magicAttackVariance = 0;
    int32_t physicalDefenseVariance = 0;
    int32_t magicDefenseVariance = 0;
    int32_t speedVariance = 0;''',
     '''/// 单列口径（B1）：攻/防各一个方差
struct DiscipleRolls {
    int32_t hpVariance = 0;
    int32_t mpVariance = 0;
    int32_t attackVariance = 0;
    int32_t defenseVariance = 0;
    int32_t speedVariance = 0;'''),
    ('''/// 六维方差（Kotlin rollVariances）：7 × gaussianInt(0, 16.667, -50, 50)
inline DiscipleRolls rollVariances(rng::DeterministicRng& rng) {
    DiscipleRolls out;
    out.hpVariance = gaussianInt(rng, 0.0, 16.667, -50, 50);
    out.mpVariance = gaussianInt(rng, 0.0, 16.667, -50, 50);
    out.physicalAttackVariance = gaussianInt(rng, 0.0, 16.667, -50, 50);
    out.magicAttackVariance = gaussianInt(rng, 0.0, 16.667, -50, 50);
    out.physicalDefenseVariance = gaussianInt(rng, 0.0, 16.667, -50, 50);
    out.magicDefenseVariance = gaussianInt(rng, 0.0, 16.667, -50, 50);
    out.speedVariance = gaussianInt(rng, 0.0, 16.667, -50, 50);
    return out;
}''',
     '''/// 五维方差（Kotlin rollVariances；单列 B1）：5 × gaussianInt(0, 16.667, -50, 50)
inline DiscipleRolls rollVariances(rng::DeterministicRng& rng) {
    DiscipleRolls out;
    out.hpVariance = gaussianInt(rng, 0.0, 16.667, -50, 50);
    out.mpVariance = gaussianInt(rng, 0.0, 16.667, -50, 50);
    out.attackVariance = gaussianInt(rng, 0.0, 16.667, -50, 50);
    out.defenseVariance = gaussianInt(rng, 0.0, 16.667, -50, 50);
    out.speedVariance = gaussianInt(rng, 0.0, 16.667, -50, 50);
    return out;
}'''),
    ('''/// 基础属性（Kotlin CombatAttributes.calculateBaseStatsWithVariance）：
/// 120/60/12/12/10/8/15 × (1 + 方差/100) 截断——创建期基准，**非** realm
/// 乘区（computeBaseStats 为 getBaseStats 路径，勿混用）
inline void applyBaseStats(state::Disciple& d, const DiscipleRolls& rolls) {
    d.baseHp = static_cast<int32_t>(120.0 * (1.0 + rolls.hpVariance / 100.0));
    d.baseMp = static_cast<int32_t>(60.0 * (1.0 + rolls.mpVariance / 100.0));
    d.basePhysicalAttack =
        static_cast<int32_t>(12.0 * (1.0 + rolls.physicalAttackVariance / 100.0));
    d.baseMagicAttack =
        static_cast<int32_t>(12.0 * (1.0 + rolls.magicAttackVariance / 100.0));
    d.basePhysicalDefense =
        static_cast<int32_t>(10.0 * (1.0 + rolls.physicalDefenseVariance / 100.0));
    d.baseMagicDefense =
        static_cast<int32_t>(8.0 * (1.0 + rolls.magicDefenseVariance / 100.0));
    d.baseSpeed = static_cast<int32_t>(15.0 * (1.0 + rolls.speedVariance / 100.0));
}''',
     '''/// 基础属性（Kotlin CombatAttributes.calculateBaseStatsWithVariance）：
/// 120/60/24/18/15 × (1 + 方差/100) 截断——创建期基准，**非** realm
/// 乘区（computeBaseStats 为 getBaseStats 路径，勿混用）；单列口径 B1
inline void applyBaseStats(state::Disciple& d, const DiscipleRolls& rolls) {
    d.baseHp = static_cast<int32_t>(120.0 * (1.0 + rolls.hpVariance / 100.0));
    d.baseMp = static_cast<int32_t>(60.0 * (1.0 + rolls.mpVariance / 100.0));
    d.baseAttack =
        static_cast<int32_t>(24.0 * (1.0 + rolls.attackVariance / 100.0));
    d.baseDefense =
        static_cast<int32_t>(18.0 * (1.0 + rolls.defenseVariance / 100.0));
    d.baseSpeed = static_cast<int32_t>(15.0 * (1.0 + rolls.speedVariance / 100.0));
}'''),
    ('''    // 1. 六维方差（7 × gaussianInt = 14 次 nextInt）
    const DiscipleRolls rolls = rollVariances(rng);
    d.hpVariance = rolls.hpVariance;
    d.mpVariance = rolls.mpVariance;
    d.physicalAttackVariance = rolls.physicalAttackVariance;
    d.magicAttackVariance = rolls.magicAttackVariance;
    d.physicalDefenseVariance = rolls.physicalDefenseVariance;
    d.magicDefenseVariance = rolls.magicDefenseVariance;
    d.speedVariance = rolls.speedVariance;''',
     '''    // 1. 五维方差（5 × gaussianInt = 10 次 nextInt；单列口径 B1）
    const DiscipleRolls rolls = rollVariances(rng);
    d.hpVariance = rolls.hpVariance;
    d.mpVariance = rolls.mpVariance;
    d.attackVariance = rolls.attackVariance;
    d.defenseVariance = rolls.defenseVariance;
    d.speedVariance = rolls.speedVariance;'''),
])

# ── ai_sect_recruit.h ──
patch('include/gamecore/system/ai_sect_recruit.h', [
    ('''    // 4. 六维方差（7×nextGaussian = 14×nextDouble——AI 版非 gaussianInt）
    const double kVarianceSigma = 16.667;
    d.hpVariance = aiGaussianInt(rng, 0.0, kVarianceSigma, -50, 50);
    d.mpVariance = aiGaussianInt(rng, 0.0, kVarianceSigma, -50, 50);
    d.physicalAttackVariance = aiGaussianInt(rng, 0.0, kVarianceSigma, -50, 50);
    d.magicAttackVariance = aiGaussianInt(rng, 0.0, kVarianceSigma, -50, 50);
    d.physicalDefenseVariance = aiGaussianInt(rng, 0.0, kVarianceSigma, -50, 50);
    d.magicDefenseVariance = aiGaussianInt(rng, 0.0, kVarianceSigma, -50, 50);
    d.speedVariance = aiGaussianInt(rng, 0.0, kVarianceSigma, -50, 50);''',
     '''    // 4. 五维方差（5×nextGaussian = 10×nextDouble——AI 版非 gaussianInt；单列 B1）
    const double kVarianceSigma = 16.667;
    d.hpVariance = aiGaussianInt(rng, 0.0, kVarianceSigma, -50, 50);
    d.mpVariance = aiGaussianInt(rng, 0.0, kVarianceSigma, -50, 50);
    d.attackVariance = aiGaussianInt(rng, 0.0, kVarianceSigma, -50, 50);
    d.defenseVariance = aiGaussianInt(rng, 0.0, kVarianceSigma, -50, 50);
    d.speedVariance = aiGaussianInt(rng, 0.0, kVarianceSigma, -50, 50);'''),
    ('''        DiscipleRolls rolls;  // 复用聚合结构（值来自 AI 版 variance）
        rolls.hpVariance = d.hpVariance;
        rolls.mpVariance = d.mpVariance;
        rolls.physicalAttackVariance = d.physicalAttackVariance;
        rolls.magicAttackVariance = d.magicAttackVariance;
        rolls.physicalDefenseVariance = d.physicalDefenseVariance;
        rolls.magicDefenseVariance = d.magicDefenseVariance;
        rolls.speedVariance = d.speedVariance;
        applyBaseStats(d, rolls);''',
     '''        DiscipleRolls rolls;  // 复用聚合结构（值来自 AI 版 variance）
        rolls.hpVariance = d.hpVariance;
        rolls.mpVariance = d.mpVariance;
        rolls.attackVariance = d.attackVariance;
        rolls.defenseVariance = d.defenseVariance;
        rolls.speedVariance = d.speedVariance;
        applyBaseStats(d, rolls);'''),
    ('''                         const int64_t pa = static_cast<int64_t>(a.basePhysicalAttack) +
                                            a.baseMagicAttack + a.baseHp;
                         const int64_t pb = static_cast<int64_t>(b.basePhysicalAttack) +
                                            b.baseMagicAttack + b.baseHp;''',
     '''                         const int64_t pa = static_cast<int64_t>(a.baseAttack) + a.baseHp;
                         const int64_t pb = static_cast<int64_t>(b.baseAttack) + b.baseHp;'''),
])

print('factory/recruit ok')
