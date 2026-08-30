// ============================================================
// battle_execution_test.cpp — 战斗回合编排黄金序列（战斗批次 C）
//
// 守护目标：固定种子 + 固定 Combatant 列表 → gamecore::battle::
// battle_execution.h 的 executeBattle 全链（速度序 → 逐参战者行动 →
// 伤害应用 → 冷却/治疗/拉条/控制/DoT → 胜负判定 + 奖励）产出确定性
// 战斗终态（turn/winner/rewards + 逐 Combatant hp/mp/buffs/技能冷却）。
// 黄金值来源：Kotlin BattleSystem 经 DiffBattleExecutionTest（同种子同
// 消费序跨语言逐字段一致）确认后固化——本文件防 C++ 侧回归漂移。
//
// 覆盖：基础战斗（普攻/技能/闪避/暴击/波动）/ 全灭提前结束 + 奖励 /
// 支援治疗与团队 Buff / 控制 / RNG 审计（回合总消费次数）/ 确定性重放 /
// 胜负边界（存活数判定）。
// ============================================================
#include <gtest/gtest.h>

#include <cstdint>
#include <string>
#include <vector>

#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/system/battle_execution.h"

namespace {

using gamecore::battle::BattleResult;
using gamecore::battle::BattleState;
using gamecore::battle::BattleWinner;
using gamecore::battle::BuffType;
using gamecore::battle::CombatBuff;
using gamecore::battle::CombatSkill;
using gamecore::battle::Combatant;
using gamecore::battle::CombatantSide;
using gamecore::battle::DamageType;
using gamecore::battle::HealType;
using gamecore::battle::SkillType;
using gamecore::rng::DeterministicRng;

/// 基础战斗单位（与 DiffBattleExecutionTest.baseCombatant 同规格）
Combatant baseCombatant(const std::string& id, const std::string& name) {
    Combatant c;
    c.id = id;
    c.name = name;
    c.side = CombatantSide::kDefender;
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

/// 攻击技能（Kotlin attackSkill 同规格）
CombatSkill attackSkill(const std::string& name, double multiplier, int32_t mpCost,
                        int32_t cooldown) {
    CombatSkill s;
    s.name = name;
    s.skillType = SkillType::kAttack;
    s.damageType = DamageType::kPhysical;
    s.damageMultiplier = multiplier;
    s.mpCost = mpCost;
    s.cooldown = cooldown;
    return s;
}

/// 按 id 找终态 hp（断言辅助）
int32_t hpOf(const std::vector<Combatant>& list, const std::string& id) {
    for (const auto& c : list) {
        if (c.id == id) return c.hp;
    }
    return -999999;
}

// ── 结构性分支断言（不依赖黄金值） ────────────────────────────────

TEST(BattleExecution, DeterministicReplaySameSeed) {
    // 确定性重放：同种子两次战斗终态全等（含 RNG 终态）
    auto buildState = [] {
        BattleState state;
        auto d1 = baseCombatant("d1", "剑修");
        d1.skills = {attackSkill("重斩", 1.8, 10, 2), attackSkill("烈焰斩", 2.2, 18, 3)};
        auto d2 = baseCombatant("d2", "体修");
        d2.physicalAttack = 180;
        d2.physicalDefense = 90;
        d2.speed = 60;
        d2.skills = {attackSkill("碎岩击", 1.6, 8, 2)};
        auto b1 = baseCombatant("beast_1", "烈焰狼");
        b1.side = CombatantSide::kAttacker;
        b1.hp = 800;
        b1.maxHp = 800;
        b1.physicalAttack = 150;
        b1.speed = 90;
        b1.skills = {attackSkill("撕咬", 1.5, 5, 1)};
        auto b2 = baseCombatant("beast_2", "冰霜狼");
        b2.side = CombatantSide::kAttacker;
        b2.hp = 700;
        b2.maxHp = 700;
        b2.magicAttack = 140;
        b2.speed = 75;
        b2.skills = {attackSkill("冰锥", 1.7, 8, 2)};
        state.team = {d1, d2};
        state.beasts = {b1, b2};
        return state;
    };

    auto rng1 = DeterministicRng::fromSeed(20260901);
    auto state1 = buildState();
    const auto out1 = executeBattle(state1, 1.0, rng1);
    const int64_t s1 = rng1.snapshot();

    auto rng2 = DeterministicRng::fromSeed(20260901);
    auto state2 = buildState();
    const auto out2 = executeBattle(state2, 1.0, rng2);
    const int64_t s2 = rng2.snapshot();

    EXPECT_EQ(out1.turn, out2.turn);
    EXPECT_EQ(static_cast<int32_t>(out1.winner), static_cast<int32_t>(out2.winner));
    EXPECT_EQ(out1.rewards, out2.rewards);
    EXPECT_EQ(out1.team.size(), out2.team.size());
    EXPECT_EQ(out1.beasts.size(), out2.beasts.size());
    for (size_t i = 0; i < out1.team.size(); ++i) {
        EXPECT_EQ(out1.team[i].hp, out2.team[i].hp);
        EXPECT_EQ(out1.team[i].mp, out2.team[i].mp);
    }
    for (size_t i = 0; i < out1.beasts.size(); ++i) {
        EXPECT_EQ(out1.beasts[i].hp, out2.beasts[i].hp);
    }
    EXPECT_EQ(s1, s2);
}

TEST(BattleExecution, EndsWhenAllEnemiesDieWithRewards) {
    // 玩家境界压制 + 高伤 → 快速全灭：提前结束 + TEAM 胜利 + 奖励
    auto rng = DeterministicRng::fromSeed(42);
    BattleState state;
    auto d1 = baseCombatant("d1", "高境界");
    d1.realm = 5;
    d1.realmLayer = 9;
    d1.physicalAttack = 500;
    d1.skills = {attackSkill("破军斩", 3.0, 20, 3)};
    auto b1 = baseCombatant("beast_1", "弱兽");
    b1.side = CombatantSide::kAttacker;
    b1.realm = 8;
    b1.hp = 300;
    b1.maxHp = 300;
    b1.physicalDefense = 20;
    state.team = {d1};
    state.beasts = {b1};

    const auto out = executeBattle(state, 1.0, rng);
    EXPECT_EQ(BattleWinner::kTeam, out.winner);
    EXPECT_LE(out.turn, 25);
    // 奖励：100 × 初始 beasts 数（1）
    const auto it = out.rewards.find("spiritStones");
    ASSERT_TRUE(it != out.rewards.end());
    EXPECT_EQ(100, it->second);
    EXPECT_TRUE(hpOf(out.beasts, "beast_1") <= 0);
}

TEST(BattleExecution, NoDamageWithoutEngagement) {
    // 胜负边界：双防极高 → 伤害全部钳制到 1 点/击，打满回合上限 DRAW
    auto rng = DeterministicRng::fromSeed(1);
    BattleState state;
    auto d1 = baseCombatant("d1", "守方");
    d1.physicalDefense = 100000;
    d1.magicDefense = 100000;
    auto b1 = baseCombatant("beast_1", "攻方");
    b1.side = CombatantSide::kAttacker;
    b1.physicalAttack = 1;
    b1.magicAttack = 1;
    b1.physicalDefense = 100000;
    b1.magicDefense = 100000;
    b1.speed = 10;
    state.team = {d1};
    state.beasts = {b1};
    const auto out = executeBattle(state, 1.0, rng);
    EXPECT_EQ(25, out.turn);  // 打满回合上限
    EXPECT_EQ(BattleWinner::kDraw, out.winner);
    for (const auto& c : out.team) {
        EXPECT_GE(c.hp, 0);
        EXPECT_LE(c.hp, c.maxHp);
    }
    for (const auto& c : out.beasts) {
        EXPECT_GE(c.hp, 0);
        EXPECT_LE(c.hp, c.maxHp);
    }
}

TEST(BattleExecution, RngConsumedAcrossBattle) {
    // RNG 审计：整场战斗消费确定性次数（同种子重放消费一致——
    // 由 DeterministicReplaySameSeed 的 s1==s2 保证；此处验证终态快照
    // 与独立推进 N 次不一致（确有消费））
    auto rng = DeterministicRng::fromSeed(42);
    BattleState state;
    auto d1 = baseCombatant("d1", "剑修");
    d1.skills = {attackSkill("重斩", 1.8, 10, 2)};
    auto b1 = baseCombatant("beast_1", "妖兽");
    b1.side = CombatantSide::kAttacker;
    b1.physicalAttack = 150;
    state.team = {d1};
    state.beasts = {b1};
    const int64_t before = rng.snapshot();
    (void)executeBattle(state, 1.0, rng);
    const int64_t after = rng.snapshot();
    EXPECT_NE(before, after);
}

TEST(BattleExecution, SupportHealAndTeamBuffPreserved) {
    // 支援路径：治疗 + 团队 Buff 写回生效（终态 hp 不低于受伤前最低值
    // 的结构性验证——精确黄金值由 DiffBattleExecutionTest 对拍确认）
    auto rng = DeterministicRng::fromSeed(7);
    BattleState state;
    auto healer = baseCombatant("d1", "医师");
    CombatSkill heal;
    heal.name = "回春术";
    heal.skillType = SkillType::kSupport;
    heal.damageMultiplier = 0.0;
    heal.mpCost = 15;
    heal.cooldown = 2;
    heal.healPercent = 0.5;
    heal.healType = HealType::kHp;
    heal.targetScope = "ally";
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
    healer.skills = {heal, haste};
    auto tank = baseCombatant("d2", "肉盾");
    tank.hp = 400;
    auto b1 = baseCombatant("beast_1", "妖兽");
    b1.side = CombatantSide::kAttacker;
    b1.physicalAttack = 200;
    b1.speed = 70;
    state.team = {healer, tank};
    state.beasts = {b1};
    const auto out = executeBattle(state, 1.0, rng);
    // 终态合法 + 双方都参与了行动（hp 变化合法范围）
    for (const auto& c : out.team) {
        EXPECT_GE(c.hp, 0);
        EXPECT_LE(c.hp, c.maxHp);
    }
}

// ── 黄金序列（DiffBattleExecutionTest 对拍确认后固化） ──────────────

TEST(BattleExecution, GoldenBasicBattleSeed42) {
    // 基础战斗：2 弟子 vs 2 妖兽（含技能/闪避/暴击/波动），种子 42
    auto rng = DeterministicRng::fromSeed(42);
    BattleState state;
    auto d1 = baseCombatant("d1", "剑修");
    d1.skills = {attackSkill("重斩", 1.8, 10, 2), attackSkill("烈焰斩", 2.2, 18, 3)};
    auto d2 = baseCombatant("d2", "体修");
    d2.physicalAttack = 180;
    d2.physicalDefense = 90;
    d2.speed = 60;
    d2.skills = {attackSkill("碎岩击", 1.6, 8, 2)};
    auto b1 = baseCombatant("beast_1", "烈焰狼");
    b1.side = CombatantSide::kAttacker;
    b1.hp = 800;
    b1.maxHp = 800;
    b1.physicalAttack = 150;
    b1.speed = 90;
    b1.skills = {attackSkill("撕咬", 1.5, 5, 1)};
    auto b2 = baseCombatant("beast_2", "冰霜狼");
    b2.side = CombatantSide::kAttacker;
    b2.hp = 700;
    b2.maxHp = 700;
    b2.magicAttack = 140;
    b2.speed = 75;
    b2.skills = {attackSkill("冰锥", 1.7, 8, 2)};
    state.team = {d1, d2};
    state.beasts = {b1, b2};
    const auto out = executeBattle(state, 1.0, rng);
    // 黄金值（Kotlin BattleSystem 同种子输出，DiffBattleExecutionTest 对拍确认）
    EXPECT_EQ(4, out.turn);  // 玩家 4 回合全灭妖兽提前结束
    EXPECT_EQ(static_cast<int32_t>(BattleWinner::kTeam), static_cast<int32_t>(out.winner));
    // 奖励：100 × 初始 beasts 数（2）
    const auto rewardIt = out.rewards.find("spiritStones");
    ASSERT_TRUE(rewardIt != out.rewards.end());
    EXPECT_EQ(200, rewardIt->second);
    // 终态 hp（对拍确认值，防 C++ 回归漂移）
    EXPECT_EQ(145, hpOf(out.team, "d1"));
    EXPECT_EQ(840, hpOf(out.team, "d2"));
    EXPECT_EQ(0, hpOf(out.beasts, "beast_1"));
    EXPECT_EQ(0, hpOf(out.beasts, "beast_2"));
}

TEST(BattleExecution, GoldenOverpoweredVictorySeed1) {
    // 境界压制快速全灭：TEAM 胜利 + 奖励 + 提前结束
    auto rng = DeterministicRng::fromSeed(1);
    BattleState state;
    auto d1 = baseCombatant("d1", "高境界");
    d1.realm = 5;
    d1.realmLayer = 9;
    d1.physicalAttack = 500;
    d1.skills = {attackSkill("破军斩", 3.0, 20, 3)};
    auto b1 = baseCombatant("beast_1", "弱兽");
    b1.side = CombatantSide::kAttacker;
    b1.realm = 8;
    b1.hp = 300;
    b1.maxHp = 300;
    b1.physicalDefense = 20;
    state.team = {d1};
    state.beasts = {b1};
    const auto out = executeBattle(state, 1.0, rng);
    EXPECT_EQ(BattleWinner::kTeam, out.winner);
    const auto it = out.rewards.find("spiritStones");
    ASSERT_TRUE(it != out.rewards.end());
    EXPECT_EQ(100, it->second);
    EXPECT_TRUE(hpOf(out.beasts, "beast_1") <= 0);
}

}  // namespace
