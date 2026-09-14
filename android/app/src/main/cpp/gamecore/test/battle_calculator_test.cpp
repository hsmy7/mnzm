// ============================================================
// battle_calculator_test.cpp — 战斗计算管线黄金序列
//
// 守护目标：固定种子 + 固定 Combatant → gamecore::battle::battle_calculator.h
// 的计算管线（calculateCombatantDamage 全链 + estimateDamage）逐字段黄金值。
// 黄金值来源：Kotlin BattleCalculator 经 DiffBattleCalculatorTest（同种子
// 同消费序跨语言逐位一致）确认后固化——本文件防 C++ 侧回归漂移。
//
// 覆盖：普攻（闪避→暴击→波动 3 抽 RNG 审计）/ 技能攻击（伤害乘区+多段）/
// 斩杀（无 RNG 消耗）/ Buff+体质+词条因子 / estimateDamage（无 RNG）。
// ============================================================
#include <gtest/gtest.h>

#include <cstdint>
#include <string>
#include <vector>

#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/system/battle_calculator.h"

namespace {

using gamecore::battle::AffixCombatEffects;
using gamecore::battle::BuffType;
using gamecore::battle::CombatBuff;
using gamecore::battle::CombatSkill;
using gamecore::battle::Combatant;
using gamecore::battle::DamageType;
using gamecore::battle::PhysiqueCombatFactors;
using gamecore::battle::SkillType;
using gamecore::rng::DeterministicRng;

/// 基础战斗单位（与 DiffBattleCalculatorTest.baseCombatant 同规格）
Combatant baseCombatant(const std::string& id, const std::string& name) {
    Combatant c;
    c.id = id;
    c.name = name;
    c.hp = 1000;
    c.maxHp = 1000;
    c.mp = 100;
    c.maxMp = 100;
    c.physicalAttack = 120;
    c.magicAttack = 100;
    c.physicalDefense = 60;
    c.magicDefense = 50;
    c.speed = 80;
    c.critRate = 0.15;
    c.realm = 9;
    c.realmLayer = 1;
    return c;
}

TEST(BattleCalculator, GoldenSequenceBasicAttackSeed42) {
    auto rng = DeterministicRng::fromSeed(42);
    const auto attacker = baseCombatant("a1", "攻一");
    const auto defender = baseCombatant("d1", "守一");
    const auto r = gamecore::battle::calculateCombatantDamage(rng, attacker, defender);
    // 黄金值（Kotlin BattleCalculator 同种子输出，DiffBattleCalculatorTest 对拍确认）
    EXPECT_EQ(132, r.damage);
    EXPECT_TRUE(r.isCrit);
    EXPECT_TRUE(r.isPhysical);
    EXPECT_FALSE(r.isDodged);
    EXPECT_EQ(1, r.hits);
}

TEST(BattleCalculator, RngAuditThreeDraws) {
    // RNG 审计：普攻恰好消耗 3 次 nextDouble（闪避 1 + 暴击 1 + 波动 1）——
    // 与 Kotlin calculateCombatantDamage 消费序逐位一致（快照推进锁定）
    auto rng = DeterministicRng::fromSeed(42);
    const int64_t before = rng.snapshot();
    (void)gamecore::battle::calculateCombatantDamage(
        rng, baseCombatant("a1", "攻一"), baseCombatant("d1", "守一"));
    const int64_t after = rng.snapshot();
    // 独立 DeterministicRng 同种子连续 3 次 nextDouble 的终态 == 结算终态
    auto ref = DeterministicRng::fromSeed(42);
    ref.nextInt();  // 快照 before 后推进 3 次
    ref.nextInt();
    ref.nextInt();
    const int64_t expected = ref.snapshot();
    EXPECT_EQ(expected, after);
    EXPECT_NE(before, after);
}

TEST(BattleCalculator, GoldenSequenceSkillSeed7) {
    auto rng = DeterministicRng::fromSeed(7);
    CombatSkill skill;
    skill.name = "烈焰斩";
    skill.skillType = SkillType::kAttack;
    skill.damageType = DamageType::kPhysical;
    skill.damageMultiplier = 1.8;
    skill.mpCost = 10;
    skill.cooldown = 2;
    skill.hits = 2;
    const auto attacker = baseCombatant("a1", "攻一");
    const auto defender = baseCombatant("d1", "守一");
    const auto r = gamecore::battle::calculateCombatantDamage(rng, attacker, defender, &skill);
    EXPECT_EQ(452, r.damage);
    EXPECT_FALSE(r.isCrit);
    EXPECT_TRUE(r.isPhysical);
    EXPECT_FALSE(r.isDodged);
    EXPECT_EQ(2, r.hits);
}

TEST(BattleCalculator, GoldenSequenceBuffPhysiqueSeed99) {
    auto rng = DeterministicRng::fromSeed(99);
    auto attacker = baseCombatant("a1", "攻一");
    attacker.buffs = {
        {BuffType::kPhysicalAttackBoost, 0.3, 3},
        {BuffType::kCritRateBoost, 0.4, 3},
    };
    attacker.physique.damageAmplification = 0.2;
    attacker.physique.critDamageBonus = 0.5;
    auto defender = baseCombatant("d1", "守一");
    defender.buffs = {
        {BuffType::kDamageReduction, 0.1, 3},
        {BuffType::kPhysicalDefenseBoost, 0.2, 3},
    };
    defender.affix.damageReduction = 0.05;
    defender.affix.defenseBonus = 0.1;
    const auto r = gamecore::battle::calculateCombatantDamage(rng, attacker, defender);
    EXPECT_EQ(274, r.damage);
    EXPECT_TRUE(r.isCrit);
    EXPECT_TRUE(r.isPhysical);
    EXPECT_FALSE(r.isDodged);
    EXPECT_EQ(1, r.hits);
}

TEST(BattleCalculator, GoldenSequenceInstantKill) {
    auto rng = DeterministicRng::fromSeed(42);
    auto attacker = baseCombatant("a1", "攻一");
    attacker.realm = 5;
    attacker.realmLayer = 9;
    auto defender = baseCombatant("d1", "守一");
    defender.realm = 7;
    defender.realmLayer = 1;
    defender.maxHp = 999;
    defender.hp = 999;
    const auto r = gamecore::battle::calculateCombatantDamage(
        rng, attacker, defender, nullptr, 1.0, nullptr, true);
    EXPECT_EQ(999, r.damage);  // 斩杀伤害 = defender.maxHp
    EXPECT_FALSE(r.isCrit);
    EXPECT_TRUE(r.isInstantKill);
    EXPECT_EQ(1, r.hits);
}

TEST(BattleCalculator, GoldenEstimateDamage) {
    CombatSkill skill;
    skill.name = "烈焰斩";
    skill.skillType = SkillType::kAttack;
    skill.damageType = DamageType::kPhysical;
    skill.damageMultiplier = 1.8;
    skill.mpCost = 10;
    skill.cooldown = 2;
    skill.hits = 2;
    const auto attacker = baseCombatant("a1", "攻一");
    const auto defender = baseCombatant("d1", "守一");
    EXPECT_EQ(414, gamecore::battle::estimateDamage(attacker, defender, skill));
}

TEST(BattleCalculator, DeterministicAcrossInstances) {
    auto rngA = DeterministicRng::fromSeed(2024);
    auto rngB = DeterministicRng::fromSeed(2024);
    const auto a = gamecore::battle::calculateCombatantDamage(
        rngA, baseCombatant("a1", "攻一"), baseCombatant("d1", "守一"));
    const auto b = gamecore::battle::calculateCombatantDamage(
        rngB, baseCombatant("a1", "攻一"), baseCombatant("d1", "守一"));
    EXPECT_EQ(a.damage, b.damage);
    EXPECT_EQ(a.isCrit, b.isCrit);
    EXPECT_EQ(a.isDodged, b.isDodged);
}

}  // namespace
