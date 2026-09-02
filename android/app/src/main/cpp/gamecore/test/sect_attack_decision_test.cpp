#include <gtest/gtest.h>

#include <map>
#include <string>
#include <vector>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/system/sect_attack_decision.h"
#include "gamecore/system/sect_decision.h"

namespace gamecore::system::detail {
namespace {

state::GameState makeState() {
    state::GameState s;
    s.gameData.gameYear = 10;
    s.gameData.gameMonth = 3;
    return s;
}

state::Disciple makeDisciple(const std::string& id) {
    state::Disciple d;
    d.id = id;
    d.isAlive = true;
    return d;
}

std::vector<state::Disciple> makeAlive(int n, const std::string& prefix) {
    std::vector<state::Disciple> out;
    for (int i = 0; i < n; ++i) out.push_back(makeDisciple(prefix + std::to_string(i)));
    return out;
}

TEST(SectAttackDecisionTest, SameSectReturnsFalseNoRoll) {
    state::GameState s = makeState();
    state::WorldSect a; a.id = "ai1";
    state::WorldSect b; b.id = "ai1";
    rng::RngManager rng; rng.initSystemSeed(42);
    const int64_t before = rng.getRng(rng::RngPartition::kBattle).snapshot();
    EXPECT_FALSE(checkAttackConditions(s, a, b, {}, rng));
    EXPECT_EQ(rng.getRng(rng::RngPartition::kBattle).snapshot(), before);
}

TEST(SectAttackDecisionTest, TooFewAttackersReturnsFalseNoRoll) {
    state::GameState s = makeState();
    state::WorldSect a; a.id = "ai1";
    state::WorldSect b; b.id = "ai2";
    s.aiSectDisciples["ai1"] = makeAlive(2, "d");
    rng::RngManager rng; rng.initSystemSeed(7);
    const int64_t before = rng.getRng(rng::RngPartition::kBattle).snapshot();
    EXPECT_FALSE(checkAttackConditions(s, a, b, {}, rng));
    EXPECT_EQ(rng.getRng(rng::RngPartition::kBattle).snapshot(), before);
}

TEST(SectAttackDecisionTest, SameAllianceReturnsFalseNoRoll) {
    state::GameState s = makeState();
    state::WorldSect a; a.id = "ai1"; a.allianceId = "ally";
    state::WorldSect b; b.id = "ai2"; b.allianceId = "ally";
    s.aiSectDisciples["ai1"] = makeAlive(10, "d");
    rng::RngManager rng; rng.initSystemSeed(7);
    const int64_t before = rng.getRng(rng::RngPartition::kBattle).snapshot();
    EXPECT_FALSE(checkAttackConditions(s, a, b, {}, rng));
    EXPECT_EQ(rng.getRng(rng::RngPartition::kBattle).snapshot(), before);
}

TEST(SectAttackDecisionTest, EmptyPlayerGarrisonReturnsFalseNoRoll) {
    state::GameState s = makeState();
    state::WorldSect a; a.id = "ai1";
    state::WorldSect d; d.id = "pl"; d.isPlayerOccupied = true;
    s.aiSectDisciples["ai1"] = makeAlive(10, "d");
    rng::RngManager rng; rng.initSystemSeed(7);
    const int64_t before = rng.getRng(rng::RngPartition::kBattle).snapshot();
    // 玩家占领但守军空 → defenderPower=0 → 早退 false，不消费
    EXPECT_FALSE(checkAttackConditions(s, a, d, {}, rng));
    EXPECT_EQ(rng.getRng(rng::RngPartition::kBattle).snapshot(), before);
}

TEST(SectAttackDecisionTest, DecidePlayerAttackProtectedSkipsNoRoll) {
    state::GameState s = makeState();
    // isPlayerProtected 为派生计算属性：默认 playerProtectionEnabled=true、startYear=1、
    // !hasAttackedAI、gameYear=10 → elapsed 9 < 100 → 受保护
    rng::RngManager rng; rng.initSystemSeed(7);
    const int64_t before = rng.getRng(rng::RngPartition::kBattle).snapshot();
    const PlayerAttackDecision decision = decidePlayerAttack(s, rng);
    EXPECT_EQ(decision.type, PlayerAttackDecisionType::kSkip);
    EXPECT_EQ(rng.getRng(rng::RngPartition::kBattle).snapshot(), before);
}

TEST(SectAttackDecisionTest, DecidePlayerAttackNoPlayerSectSkips) {
    state::GameState s = makeState();
    rng::RngManager rng; rng.initSystemSeed(7);
    const int64_t before = rng.getRng(rng::RngPartition::kBattle).snapshot();
    const PlayerAttackDecision decision = decidePlayerAttack(s, rng);
    EXPECT_EQ(decision.type, PlayerAttackDecisionType::kSkip);
    EXPECT_EQ(rng.getRng(rng::RngPartition::kBattle).snapshot(), before);
}

}  // namespace
}  // namespace gamecore::system::detail
