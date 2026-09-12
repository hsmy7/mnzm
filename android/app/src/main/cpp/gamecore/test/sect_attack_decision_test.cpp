#include <gtest/gtest.h>

#include <map>
#include <string>
#include <vector>

#include "gamecore/rng/rng_manager.h"
// month_settlement.h 须先于 sect_attack_decision.h——后者依赖本文件符号
// （detail::kAiMinDisciplesForAttack 等），且 month_settlement.h 文件尾
// 依序包含 sect_attack_decision.h/sect_defense_battle.h（子事件 6b），
// 直接以 sect_attack_decision.h 为首包含会落在符号未定义窗口
#include "gamecore/system/month_settlement.h"
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

// G7 剩余：占领判定（Kotlin executeSectBattleCore）下沉后守护
// realm 语义 0=仙人 … 9=炼气；realm<=5 视为高阶战力（战败仍能守卫者）。
TEST(SectAttackDecisionTest, ComputeCanOccupyFaithfulToKotlin) {
    // 低阶弟子（realm>5）不会阻碍占领
    std::vector<state::Disciple> pool;
    { state::Disciple d = makeDisciple("a"); d.realm = 7; pool.push_back(d); }
    { state::Disciple d = makeDisciple("b"); d.realm = 6; pool.push_back(d); }
    EXPECT_TRUE(highRealmAllDead(pool, {}));          // 无 realm<=5 存活
    EXPECT_TRUE(computeCanOccupy(true, pool, {}));    // 攻方胜 + 高阶全灭 → 可占领

    // 存在高阶弟子（realm<=5）存活 → 高阶未灭 → 不可占领
    { state::Disciple d = makeDisciple("c"); d.realm = 4; pool.push_back(d); }
    EXPECT_FALSE(highRealmAllDead(pool, {}));
    EXPECT_FALSE(computeCanOccupy(true, pool, {}));

    // 该高阶弟子战死 → 不计入 → 高阶全灭 → 可占领
    EXPECT_TRUE(highRealmAllDead(pool, {"c"}));

    // 攻方失败 → 即使高阶全灭也不可占领
    EXPECT_FALSE(computeCanOccupy(false, pool, {"c"}));

    // 非存活弟子不计入
    std::vector<state::Disciple> pool2;
    { state::Disciple d = makeDisciple("d"); d.isAlive = false; d.realm = 1; pool2.push_back(d); }
    EXPECT_TRUE(highRealmAllDead(pool2, {}));
}

}  // namespace
}  // namespace gamecore::system::detail
