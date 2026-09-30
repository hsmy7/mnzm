// single_column_stat_test.cpp — 属性单列口径黄金测试（装备重构 B1，方案 §15）
//
// 覆盖（对应验收判据 S19 与 Kotlin 侧 SingleColumnStatGuardTest / DiffBattle*）：
//   1. 单列攻防结算（computeBaseStats：物法两半各自 round 后相加）
//   2. 类型桶默认 0.0 时伤害与无类型通道基准公式**逐位一致**（S19）
//   3. 类型桶非零生效（类型增伤进增伤加算区、类型减伤进减伤加算区）
//   4. 普攻伤害类型 = innateDamageType、技能伤害类型 = skill.damageType
//   5. ItemEffect/PillEffect 旧物法四列归一化（*Total 线性合并）
#include <gtest/gtest.h>

#include "gamecore/system/battle.h"
#include "gamecore/system/battle_calculator.h"
#include "gamecore/system/disciple.h"
#include "gamecore/system/sect_power.h"
#include "gamecore/state/models.h"

namespace {

using namespace gamecore;
using gamecore::battle::Combatant;
using gamecore::battle::CombatSkill;
using gamecore::battle::DamageType;
using gamecore::battle::DamageZones;
using gamecore::battle::SkillType;

// ── 1. 单列结算：境界基值 × 方差 × 层数，物法两半各自 round 后相加 ──

TEST(SingleColumnStat, ComputeBaseStatsSumsBothHalves) {
    gamecore::disciple::BaseStatsInput in;
    in.realm = 9;  // 炼气：物攻 16 / 法攻 16 / 物防 13 / 法防 10
    in.realmLayer = 1;
    in.attackVariance = 10;
    in.defenseVariance = -10;

    const auto s = gamecore::disciple::computeBaseStats(in);
    // 各自 round 后相加：round(16×1.10)=18 ×2 = 36；round(13×0.90)=12、round(10×0.90)=9 ⇒ 21
    EXPECT_EQ(36, s.attack);
    EXPECT_EQ(21, s.defense);
}

TEST(SingleColumnStat, CombatPowerLinearEquivalence) {
    // 战力公式取和口径线性等价：旧 (物攻+法攻)×5 == 新 attack×5（Q7 k=1，S20 前提）
    const int64_t oldForm = (static_cast<int64_t>(100) + 80) * 5 + 1000 * 4 +
                            (60 + 50) * 3 + 40 * 2;
    const int64_t newForm = gamecore::system::discipleCombatPower(180, 1000, 110, 40);
    EXPECT_EQ(oldForm, newForm);
}

// ── 2. S19：类型桶默认 0.0 时与基准公式逐位一致 ──

/// 基准 Combatant（无 buff、无类型桶）
Combatant baseFighter() {
    Combatant c;
    c.id = "t1";
    c.name = "剑修";
    c.hp = 1000;
    c.maxHp = 1000;
    c.mp = 100;
    c.maxMp = 100;
    c.attack = 200;
    c.defense = 100;
    c.speed = 50;
    c.critRate = 0.0;  // 免暴击抽数
    return c;
}

CombatSkill plainPhysicalSkill() {
    CombatSkill s;
    s.name = "斩击";
    s.skillType = SkillType::kAttack;
    s.damageType = DamageType::kPhysical;
    s.damageMultiplier = 2.0;
    return s;
}

TEST(SingleColumnStat, TypeBucketsAtZeroAreBitwiseNeutral) {
    auto attacker = baseFighter();
    auto defender = baseFighter();
    defender.defense = 80;
    const auto skill = plainPhysicalSkill();

    // 默认 zones（四类型桶 + typeDamage* 全 0.0）
    const int32_t withDefaultZones =
        battle::estimateDamage(attacker, defender, skill);

    // 显式全零类型桶
    DamageZones zeroZones;
    zeroZones.typeDamageBonus = 0.0;
    zeroZones.typeDamageReduction = 0.0;
    const int32_t withExplicitZero =
        battle::estimateDamage(attacker, defender, skill, &zeroZones);

    // 基准公式（手算）：atk×mult×(1−def/(def+500))
    const double def = 80.0;
    const int32_t baseline = static_cast<int32_t>(
        200.0 * 2.0 * (1.0 - def / (def + 500.0)));

    EXPECT_EQ(baseline, withDefaultZones);
    EXPECT_EQ(baseline, withExplicitZero);
}

// ── 3. 类型桶非零生效（加算进增/减伤区） ──

TEST(SingleColumnStat, PhysicalBonusBucketAppliesOnPhysical) {
    auto attacker = baseFighter();
    auto defender = baseFighter();
    defender.defense = 80;
    const auto skill = plainPhysicalSkill();

    const int32_t baseline = battle::estimateDamage(attacker, defender, skill);

    // 物理伤害加成 +50% ⇒ (1 + 0 + 0.5) 乘区（其余乘区恒 1）；
    // 期望值按全 double 式算再截断（200×2×(1−80/580)×1.5 = 517.24 → 517）
    attacker.physicalDamageBonus = 0.5;
    const int32_t boosted = battle::estimateDamage(attacker, defender, skill);
    const double exact = 200.0 * 2.0 * (1.0 - 80.0 / 580.0) * 1.5;
    EXPECT_EQ(static_cast<int32_t>(exact), boosted);
    EXPECT_EQ(517, boosted);

    // 法术伤害加成对物理技能不生效
    attacker.physicalDamageBonus = 0.0;
    attacker.magicDamageBonus = 0.5;
    EXPECT_EQ(baseline, battle::estimateDamage(attacker, defender, skill));
}

TEST(SingleColumnStat, DefenseTypeReductionBucketAppliesOnMatchingType) {
    auto attacker = baseFighter();
    auto defender = baseFighter();
    defender.defense = 80;
    const auto skill = plainPhysicalSkill();

    const int32_t baseline = battle::estimateDamage(attacker, defender, skill);

    // 守方物理减伤 25% ⇒ (1 − 0 − 0.25) 乘区
    defender.physicalDamageReduction = 0.25;
    const int32_t reduced = battle::estimateDamage(attacker, defender, skill);
    EXPECT_DOUBLE_EQ(baseline * 0.75, static_cast<double>(reduced));

    // 法术减伤对物理技能不生效
    defender.physicalDamageReduction = 0.0;
    defender.magicDamageReduction = 0.25;
    EXPECT_EQ(baseline, battle::estimateDamage(attacker, defender, skill));
}

TEST(SingleColumnStat, DefenseBuffMigratesToTypeReductionBucket) {
    // 物法防 buff 语义迁移（B1 §15.2 改动点③）：PHYSICAL_DEFENSE_BOOST
    // 经 buildDamageZones 进入 physicalDefenseBuffs，按本次类型选桶生效
    auto attacker = baseFighter();
    auto defender = baseFighter();
    defender.defense = 80;
    const auto skill = plainPhysicalSkill();

    const int32_t baseline = battle::estimateDamage(attacker, defender, skill);

    battle::CombatBuff buff;
    buff.type = battle::BuffType::kPhysicalDefenseBoost;
    buff.value = 0.25;
    buff.remainingDuration = 3;
    defender.buffs.push_back(buff);

    const auto zones = battle::buildDamageZones(attacker, &defender);
    EXPECT_DOUBLE_EQ(0.25, zones.physicalDefenseBuffs);
    EXPECT_DOUBLE_EQ(0.0, zones.magicDefenseBuffs);

    const int32_t reduced = battle::estimateDamage(attacker, defender, skill, &zones);
    EXPECT_DOUBLE_EQ(baseline * 0.75, static_cast<double>(reduced));
}

// ── 4. 伤害类型判定：普攻按 innateDamageType、技能按 skill.damageType ──

TEST(SingleColumnStat, InnateTypeDrivesBasicAttackAndSkillOverrides) {
    auto attacker = baseFighter();
    attacker.innateDamageType = DamageType::kMagic;
    auto defender = baseFighter();
    defender.defense = 80;

    // 技能类型跟随 skill.damageType（estimateDamage 契约）：
    // 法术技能吃法术减伤 50% ⇒ 减半；物理技能不吃
    CombatSkill magicSkill = plainPhysicalSkill();
    magicSkill.damageType = DamageType::kMagic;
    defender.magicDamageReduction = 0.5;
    const int32_t magicReduced = battle::estimateDamage(attacker, defender, magicSkill);
    defender.magicDamageReduction = 0.0;
    const int32_t magicPlain = battle::estimateDamage(attacker, defender, magicSkill);
    EXPECT_EQ(magicPlain / 2, magicReduced);
    EXPECT_EQ(magicPlain, battle::estimateDamage(attacker, defender, plainPhysicalSkill()));

    // 普攻（无技能）类型走 innateDamageType（calculateCombatantDamage 契约）：
    // 攻方固有法术 ⇒ DamageResult.isPhysical == false（RNG 消耗存在，仅断言类型位）
    rng::DeterministicRng rng(42);
    defender.magicDamageReduction = 0.0;
    const auto r =
        battle::calculateCombatantDamage(rng, attacker, defender, nullptr);
    EXPECT_FALSE(r.isPhysical);

    // 对照：固有物理 ⇒ isPhysical == true
    auto physicalAttacker = baseFighter();
    rng::DeterministicRng rng2(42);
    const auto r2 =
        battle::calculateCombatantDamage(rng2, physicalAttacker, defender, nullptr);
    EXPECT_TRUE(r2.isPhysical);
}

// ── 5. 旧物法四列归一化（*Total 线性合并；新档旧列恒 0 时即新列本身） ──

TEST(SingleColumnStat, ItemEffectLegacyColumnsNormalizeLinearly) {
    state::ItemEffect e;
    // 旧档：物攻 +30 / 法攻 +20 / 物防 +10 / 法防 +5
    e.physicalAttackAdd = 30;
    e.magicAttackAdd = 20;
    e.physicalDefenseAdd = 10;
    e.magicDefenseAdd = 5;
    EXPECT_EQ(50, e.AttackAddTotal());
    EXPECT_EQ(15, e.DefenseAddTotal());

    // 新档：只写新列（旧列恒 0 ⇒ Total 即新列）
    state::ItemEffect n;
    n.attackAdd = 50;
    n.defenseAdd = 15;
    EXPECT_EQ(50, n.AttackAddTotal());
    EXPECT_EQ(15, n.DefenseAddTotal());
}

}  // namespace
