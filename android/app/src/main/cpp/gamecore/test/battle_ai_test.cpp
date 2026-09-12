// ============================================================
// battle_ai_test.cpp — 统一战斗 AI 决策层黄金序列
//
// 守护目标：固定种子 + 固定 Combatant/Skill → gamecore::battle::battle_ai.h
// 的 decideAction 全链（8 层级联优先级 + 概率衰减）产出确定性 AIAction
// （actionType/skillName/targetId）+ RNG 消费序（决策终态快照锁定）。
// 黄金值来源：Kotlin BattleAI 经 DiffBattleAITest（同种子同消费序跨语言
// 逐位一致）确认后固化——本文件防 C++ 侧回归漂移。
//
// 覆盖：被控/无敌人零消费早退 / 保命（Tier 2）/ 斩杀（Tier 3）/
// 支援（Tier 4）/ 团队 Buff（Tier 5）/ 控制（Tier 6）/ AOE（Tier 7）/
// 省蓝（Tier 8）/ 最优单体（Tier 9）/ 普攻兜底 / 目标选择分支 /
// RNG 审计（各层短路消费次数锁定）。
// ============================================================
#include <gtest/gtest.h>

#include <cstdint>
#include <string>
#include <vector>

#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/system/battle_ai.h"

namespace {

using gamecore::battle::AIAction;
using gamecore::battle::AIActionType;
using gamecore::battle::BuffType;
using gamecore::battle::CombatBuff;
using gamecore::battle::CombatSkill;
using gamecore::battle::Combatant;
using gamecore::battle::DamageType;
using gamecore::battle::HealType;
using gamecore::battle::SkillType;
using gamecore::rng::DeterministicRng;

/// 基础战斗单位（与 DiffBattleAITest.baseCombatant 同规格）
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

/// 独立同种子推进 n 次 nextDouble 后的快照（RNG 审计参照系）
int64_t afterNDoubles(int64_t seed, int n) {
    auto ref = DeterministicRng::fromSeed(seed);
    for (int i = 0; i < n; ++i) ref.nextDouble();
    return ref.snapshot();
}

/// 决策后快照与参照系比对（锁定 RNG 消费次数）
void expectRngConsumed(int64_t seed, int64_t before, int64_t after, int expectedDraws) {
    EXPECT_EQ(afterNDoubles(seed, expectedDraws), after)
        << "RNG 消费次数与预期不符: expected=" << expectedDraws;
    EXPECT_NE(before, after);
}

// ── 黄金序列（DiffBattleAITest 对拍确认后固化） ────────────────────

TEST(BattleAI, GoldenSelfPreserveShieldSeed42) {
    // Tier 2 保命：残血 20% + 护盾技能 → SKILL_BUFF_SELF（消费 1 次）
    auto rng = DeterministicRng::fromSeed(42);
    auto unit = baseCombatant("a1", "残血者");
    unit.hp = 200;
    CombatSkill shield;
    shield.name = "护体灵光";
    shield.skillType = SkillType::kSupport;
    shield.damageMultiplier = 0.0;
    shield.mpCost = 10;
    shield.cooldown = 3;
    shield.targetScope = "self";
    shield.shieldPercent = 0.3;
    shield.buffDuration = 3;
    CombatSkill flame;
    flame.name = "烈焰斩";
    flame.skillType = SkillType::kAttack;
    flame.damageType = DamageType::kPhysical;
    flame.damageMultiplier = 1.8;
    flame.mpCost = 10;
    flame.cooldown = 2;
    unit.skills = {shield, flame};
    const auto enemy = baseCombatant("e1", "敌人");
    const int64_t before = rng.snapshot();
    const auto action = gamecore::battle::decideAction(unit, {unit}, {enemy}, rng);
    const int64_t after = rng.snapshot();
    EXPECT_EQ(AIActionType::kSkillBuffSelf, action.actionType);
    ASSERT_TRUE(action.skill.has_value());
    EXPECT_EQ("护体灵光", action.skill->name);
    ASSERT_TRUE(action.targetId.has_value());
    EXPECT_EQ("a1", *action.targetId);
    expectRngConsumed(42, before, after, 1);
}

TEST(BattleAI, GoldenExecuteKillSeed7) {
    // Tier 3 斩杀：残血敌 20% + 高伤技能 → SKILL_ATTACK_SINGLE（消费 1 次）
    auto rng = DeterministicRng::fromSeed(7);
    auto unit = baseCombatant("a1", "攻一");
    CombatSkill breaker;
    breaker.name = "破军斩";
    breaker.skillType = SkillType::kAttack;
    breaker.damageType = DamageType::kPhysical;
    breaker.damageMultiplier = 3.0;
    breaker.mpCost = 20;
    breaker.cooldown = 3;
    unit.skills = {breaker};
    auto enemy = baseCombatant("e1", "残血敌");
    enemy.hp = 200;
    const int64_t before = rng.snapshot();
    const auto action = gamecore::battle::decideAction(unit, {unit}, {enemy}, rng);
    const int64_t after = rng.snapshot();
    EXPECT_EQ(AIActionType::kSkillAttackSingle, action.actionType);
    ASSERT_TRUE(action.skill.has_value());
    EXPECT_EQ("破军斩", action.skill->name);
    ASSERT_TRUE(action.targetId.has_value());
    EXPECT_EQ("e1", *action.targetId);
    expectRngConsumed(7, before, after, 1);
}

TEST(BattleAI, GoldenAllySupportHealSeed42) {
    // Tier 4 支援：伤者 30% + 单体治疗 → SKILL_HEAL_ALLY（消费 2 次：
    // Tier 3 斩杀 1 + Tier 4 支援 1）
    auto rng = DeterministicRng::fromSeed(42);
    auto unit = baseCombatant("a1", "医师");
    CombatSkill heal;
    heal.name = "回春术";
    heal.skillType = SkillType::kSupport;
    heal.damageMultiplier = 0.0;
    heal.mpCost = 15;
    heal.cooldown = 2;
    heal.healPercent = 0.5;
    heal.healType = HealType::kHp;
    heal.targetScope = "ally";
    unit.skills = {heal};
    auto ally = baseCombatant("a2", "伤者");
    ally.hp = 300;
    const auto enemy = baseCombatant("e1", "敌人");
    const int64_t before = rng.snapshot();
    const auto action = gamecore::battle::decideAction(unit, {unit, ally}, {enemy}, rng);
    const int64_t after = rng.snapshot();
    EXPECT_EQ(AIActionType::kSkillHealAlly, action.actionType);
    ASSERT_TRUE(action.skill.has_value());
    EXPECT_EQ("回春术", action.skill->name);
    ASSERT_TRUE(action.targetId.has_value());
    EXPECT_EQ("a2", *action.targetId);
    expectRngConsumed(42, before, after, 2);
}

TEST(BattleAI, GoldenTeamBuffSeed42) {
    // Tier 5 团队 Buff：满血盟友 + 团队加速阵 → SKILL_BUFF_TEAM
    // （消费 3 次：斩杀 + 支援 + 团队 Buff）
    auto rng = DeterministicRng::fromSeed(42);
    auto unit = baseCombatant("a1", "辅助");
    CombatSkill haste;
    haste.name = "疾风阵";
    haste.skillType = SkillType::kSupport;
    haste.damageMultiplier = 0.0;
    haste.mpCost = 20;
    haste.cooldown = 4;
    haste.targetScope = "team";
    haste.buffType = BuffType::kSpeedBoost;
    haste.buffValue = 0.2;
    haste.buffDuration = 3;
    unit.skills = {haste};
    auto ally = baseCombatant("a2", "队友");
    ally.hp = 900;
    const auto enemy = baseCombatant("e1", "敌人");
    const int64_t before = rng.snapshot();
    const auto action = gamecore::battle::decideAction(unit, {unit, ally}, {enemy}, rng);
    const int64_t after = rng.snapshot();
    EXPECT_EQ(AIActionType::kSkillBuffTeam, action.actionType);
    ASSERT_TRUE(action.skill.has_value());
    EXPECT_EQ("疾风阵", action.skill->name);
    EXPECT_FALSE(action.targetId.has_value());
    expectRngConsumed(42, before, after, 3);
}

TEST(BattleAI, GoldenControlHighThreatSeed42) {
    // Tier 6 控制：STUN 技能 + 最高威胁未受控敌人 → SKILL_ATTACK_SINGLE。
    // seed 42 下 Tier 6 概率判定未命中（第 4 抽 ≥ 0.60）→ 走攻击决策
    // （目标选择 1 抽）→ 总消费 5 次（Tier3+4+5+6 各 1 + 目标选择 1）
    auto rng = DeterministicRng::fromSeed(42);
    auto unit = baseCombatant("a1", "控场");
    CombatSkill stun;
    stun.name = "定身咒";
    stun.skillType = SkillType::kAttack;
    stun.damageType = DamageType::kPhysical;
    stun.damageMultiplier = 0.5;
    stun.mpCost = 15;
    stun.cooldown = 3;
    stun.buffType = BuffType::kStun;
    stun.buffValue = 1.0;
    stun.buffDuration = 2;
    unit.skills = {stun};
    auto enemy1 = baseCombatant("e1", "敌人一");
    enemy1.physicalAttack = 200;
    auto enemy2 = baseCombatant("e2", "敌人二");
    enemy2.physicalAttack = 100;
    const int64_t before = rng.snapshot();
    const auto action = gamecore::battle::decideAction(unit, {unit}, {enemy1, enemy2}, rng);
    const int64_t after = rng.snapshot();
    EXPECT_EQ(AIActionType::kSkillAttackSingle, action.actionType);
    ASSERT_TRUE(action.skill.has_value());
    EXPECT_EQ("定身咒", action.skill->name);
    ASSERT_TRUE(action.targetId.has_value());
    EXPECT_EQ("e1", *action.targetId);
    expectRngConsumed(42, before, after, 5);
}

TEST(BattleAI, GoldenAoeSeed42) {
    // Tier 7 AOE：3 敌人 + AOE 技能 → SKILL_ATTACK_AOE。
    // seed 42 下 Tier 6 概率判定未命中 → Tier 7（3+ 敌人）消费第 5 抽命中
    // → 总消费 5 次（Tier3+4+5+6 各 1 + Tier7 1）
    auto rng = DeterministicRng::fromSeed(42);
    auto unit = baseCombatant("a1", "群攻");
    CombatSkill aoe;
    aoe.name = "裂地崩";
    aoe.skillType = SkillType::kAttack;
    aoe.damageType = DamageType::kPhysical;
    aoe.damageMultiplier = 1.5;
    aoe.mpCost = 25;
    aoe.cooldown = 4;
    aoe.isAoe = true;
    unit.skills = {aoe};
    const std::vector<Combatant> enemies = {
        baseCombatant("e1", "敌人一"), baseCombatant("e2", "敌人二"),
        baseCombatant("e3", "敌人三")};
    const int64_t before = rng.snapshot();
    const auto action = gamecore::battle::decideAction(unit, {unit}, enemies, rng);
    const int64_t after = rng.snapshot();
    EXPECT_EQ(AIActionType::kSkillAttackAoe, action.actionType);
    ASSERT_TRUE(action.skill.has_value());
    EXPECT_EQ("裂地崩", action.skill->name);
    EXPECT_FALSE(action.targetId.has_value());
    expectRngConsumed(42, before, after, 5);
}

TEST(BattleAI, GoldenMpSaveCheapestSeed42) {
    // Tier 8 省蓝：MP 20% + 双攻击技能 → 选蓝耗最低技能
    auto rng = DeterministicRng::fromSeed(42);
    auto unit = baseCombatant("a1", "省蓝者");
    unit.mp = 20;
    CombatSkill heavy;
    heavy.name = "重斩";
    heavy.skillType = SkillType::kAttack;
    heavy.damageType = DamageType::kPhysical;
    heavy.damageMultiplier = 1.2;
    heavy.mpCost = 10;
    heavy.cooldown = 2;
    CombatSkill flame;
    flame.name = "烈焰斩";
    flame.skillType = SkillType::kAttack;
    flame.damageType = DamageType::kPhysical;
    flame.damageMultiplier = 1.8;
    flame.mpCost = 30;
    flame.cooldown = 3;
    unit.skills = {heavy, flame};
    const auto enemy = baseCombatant("e1", "敌人");
    const auto action = gamecore::battle::decideAction(unit, {unit}, {enemy}, rng);
    EXPECT_EQ(AIActionType::kSkillAttackSingle, action.actionType);
    ASSERT_TRUE(action.skill.has_value());
    EXPECT_EQ("重斩", action.skill->name);
    ASSERT_TRUE(action.targetId.has_value());
    EXPECT_EQ("e1", *action.targetId);
}

TEST(BattleAI, GoldenBestSingleSeed42) {
    // Tier 9 最优单体：伤害/蓝耗比最大（1.8/10 > 2.0/20）→ 重斩
    auto rng = DeterministicRng::fromSeed(42);
    auto unit = baseCombatant("a1", "攻一");
    CombatSkill heavy;
    heavy.name = "重斩";
    heavy.skillType = SkillType::kAttack;
    heavy.damageType = DamageType::kPhysical;
    heavy.damageMultiplier = 1.8;
    heavy.mpCost = 10;
    heavy.cooldown = 2;
    CombatSkill flame;
    flame.name = "烈焰斩";
    flame.skillType = SkillType::kAttack;
    flame.damageType = DamageType::kPhysical;
    flame.damageMultiplier = 2.0;
    flame.mpCost = 20;
    flame.cooldown = 3;
    unit.skills = {heavy, flame};
    const auto enemy = baseCombatant("e1", "敌人");
    const auto action = gamecore::battle::decideAction(unit, {unit}, {enemy}, rng);
    EXPECT_EQ(AIActionType::kSkillAttackSingle, action.actionType);
    ASSERT_TRUE(action.skill.has_value());
    EXPECT_EQ("重斩", action.skill->name);
}

TEST(BattleAI, GoldenNormalAttackFallbackSeed3) {
    // 普攻兜底：无技能 → NORMAL_ATTACK（目标选择至多 3 抽）
    auto rng = DeterministicRng::fromSeed(3);
    const auto unit = baseCombatant("a1", "白板");
    const std::vector<Combatant> enemies = {
        baseCombatant("e1", "敌人"), baseCombatant("e2", "敌人二")};
    const int64_t before = rng.snapshot();
    const auto action = gamecore::battle::decideAction(unit, {unit}, enemies, rng);
    const int64_t after = rng.snapshot();
    EXPECT_EQ(AIActionType::kNormalAttack, action.actionType);
    EXPECT_FALSE(action.skill.has_value());
    ASSERT_TRUE(action.targetId.has_value());
    EXPECT_NE(before, after);
}

// ── 结构性分支断言（不依赖黄金值） ────────────────────────────────

TEST(BattleAI, DeadUnitReturnsNoneZeroRng) {
    auto rng = DeterministicRng::fromSeed(42);
    auto dead = baseCombatant("a1", "亡者");
    dead.hp = 0;
    const auto enemy = baseCombatant("e1", "敌人");
    const int64_t before = rng.snapshot();
    const auto action = gamecore::battle::decideAction(dead, {dead}, {enemy}, rng);
    EXPECT_EQ(AIActionType::kNone, action.actionType);
    EXPECT_EQ(before, rng.snapshot());  // 零消费
}

TEST(BattleAI, NoAliveEnemiesReturnsNone) {
    auto rng = DeterministicRng::fromSeed(42);
    const auto unit = baseCombatant("a1", "攻一");
    auto deadEnemy = baseCombatant("e1", "亡敌");
    deadEnemy.hp = 0;
    const int64_t before = rng.snapshot();
    const auto action = gamecore::battle::decideAction(unit, {unit}, {deadEnemy}, rng);
    EXPECT_EQ(AIActionType::kNone, action.actionType);
    EXPECT_EQ(before, rng.snapshot());
}

TEST(BattleAI, ControlledReturnsNoneZeroRng) {
    auto rng = DeterministicRng::fromSeed(42);
    auto unit = baseCombatant("a1", "被控者");
    unit.buffs = {{BuffType::kStun, 1.0, 2}};
    const auto enemy = baseCombatant("e1", "敌人");
    const int64_t before = rng.snapshot();
    const auto action = gamecore::battle::decideAction(unit, {unit}, {enemy}, rng);
    EXPECT_EQ(AIActionType::kNone, action.actionType);
    EXPECT_EQ(before, rng.snapshot());
}

TEST(BattleAI, SilencedFallsThroughToNormalAttack) {
    // 沉默：技能全禁用 → 无攻击技能 → 普攻兜底（目标选择消费 RNG）
    auto rng = DeterministicRng::fromSeed(42);
    auto unit = baseCombatant("a1", "沉默者");
    unit.hp = 600;
    CombatSkill flame;
    flame.name = "烈焰斩";
    flame.skillType = SkillType::kAttack;
    flame.damageType = DamageType::kPhysical;
    flame.damageMultiplier = 1.8;
    flame.mpCost = 10;
    flame.cooldown = 2;
    unit.skills = {flame};
    unit.buffs = {{BuffType::kSilence, 1.0, 2}};
    const std::vector<Combatant> enemies = {
        baseCombatant("e1", "敌人"), baseCombatant("e2", "敌人二")};
    const auto action = gamecore::battle::decideAction(unit, {unit}, enemies, rng);
    EXPECT_EQ(AIActionType::kNormalAttack, action.actionType);
    EXPECT_FALSE(action.skill.has_value());
    ASSERT_TRUE(action.targetId.has_value());
}

TEST(BattleAI, DeterministicReplaySameSeed) {
    // 确定性重放：同种子两次决策全等（含 RNG 终态）
    auto build = [] {
        auto unit = baseCombatant("a1", "攻一");
        CombatSkill flame;
        flame.name = "烈焰斩";
        flame.skillType = SkillType::kAttack;
        flame.damageType = DamageType::kPhysical;
        flame.damageMultiplier = 1.8;
        flame.mpCost = 10;
        flame.cooldown = 2;
        unit.skills = {flame};
        return unit;
    };
    const auto enemy = baseCombatant("e1", "敌人");

    auto rng1 = DeterministicRng::fromSeed(20260901);
    const auto a1 = gamecore::battle::decideAction(build(), {build()}, {enemy}, rng1);
    const int64_t s1 = rng1.snapshot();

    auto rng2 = DeterministicRng::fromSeed(20260901);
    const auto a2 = gamecore::battle::decideAction(build(), {build()}, {enemy}, rng2);
    const int64_t s2 = rng2.snapshot();

    EXPECT_EQ(static_cast<int32_t>(a1.actionType), static_cast<int32_t>(a2.actionType));
    EXPECT_EQ(a1.skill.has_value(), a2.skill.has_value());
    if (a1.skill.has_value() && a2.skill.has_value()) {
        EXPECT_EQ(a1.skill->name, a2.skill->name);
    }
    EXPECT_EQ(a1.targetId, a2.targetId);
    EXPECT_EQ(s1, s2);
}

TEST(BattleAI, TargetSelectionBranchesAcrossSeeds) {
    // 目标选择分支扫描：双敌人（高攻血多 vs 血少攻低）15 种子——
    // 低血量/高威胁/低防御/兜底分支至少各命中一次，且决策确定
    auto unit = baseCombatant("a1", "攻一");
    CombatSkill heavy;
    heavy.name = "重斩";
    heavy.skillType = SkillType::kAttack;
    heavy.damageType = DamageType::kPhysical;
    heavy.damageMultiplier = 1.5;
    heavy.mpCost = 10;
    heavy.cooldown = 2;
    unit.skills = {heavy};
    auto enemy1 = baseCombatant("e1", "高攻");
    enemy1.hp = 800;
    enemy1.physicalAttack = 300;
    enemy1.physicalDefense = 20;
    auto enemy2 = baseCombatant("e2", "血少");
    enemy2.hp = 500;
    enemy2.physicalAttack = 50;
    enemy2.physicalDefense = 90;

    std::set<std::string> seenTargets;
    for (int64_t seed : {1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 42L, 99L, 555L,
                         2024L, 20260901L}) {
        auto rng = DeterministicRng::fromSeed(seed);
        const auto action =
            gamecore::battle::decideAction(unit, {unit}, {enemy1, enemy2}, rng);
        EXPECT_EQ(AIActionType::kSkillAttackSingle, action.actionType);
        ASSERT_TRUE(action.targetId.has_value());
        seenTargets.insert(*action.targetId);
    }
    // 两个敌人在 15 种子上均至少被选中一次（覆盖全部目标选择分支）
    EXPECT_TRUE(seenTargets.count("e1") > 0);
    EXPECT_TRUE(seenTargets.count("e2") > 0);
}

}  // namespace
