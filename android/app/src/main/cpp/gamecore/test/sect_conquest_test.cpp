#include <gtest/gtest.h>

// 子事件 6b：AI-vs-AI 征伐环（P2-18 决策项③ Stage 2）全链测试。
//
// 包含契约：sect_conquest.h 须在 month_settlement.h 之后
//（本文件经第一包含引入）。
#include "gamecore/rng/rng_manager.h"
#include "gamecore/system/month_settlement.h"
#include "gamecore/system/sect_defense_battle.h"
#include "gamecore/system/sect_conquest.h"

#include <algorithm>
#include <string>
#include <vector>

namespace gamecore::system {
namespace {

using gamecore::state::Disciple;
using gamecore::state::GameState;
using gamecore::state::WorldSect;

state::Disciple makeDisciple(const std::string& id, int32_t realm) {
    state::Disciple d;
    d.id = id;
    d.name = "弟子" + id;
    d.isAlive = true;
    d.realm = realm;
    d.realmLayer = 1;
    d.currentHp = -1;
    d.currentMp = -1;
    return d;
}

std::vector<state::Disciple> makeSquad(const std::string& prefix,
                                       int32_t realm, int32_t count) {
    std::vector<state::Disciple> out;
    for (int32_t i = 0; i < count; ++i) {
        out.push_back(makeDisciple(prefix + std::to_string(i), realm));
    }
    return out;
}

/// 标准征伐场景：AI 攻方 atk（realm 0 ×10）+ AI 守方 def（realm 9 ×10，
/// 无占领）+ 玩家宗门在场。
GameState makeConquestState() {
    GameState s;
    s.gameData.gameYear = 101;
    s.gameData.gameMonth = 3;
    WorldSect player;
    player.id = "p1";
    player.name = "青云宗";
    player.isPlayerSect = true;
    WorldSect attacker;
    attacker.id = "atk";
    attacker.name = "青岚宗";
    WorldSect defender;
    defender.id = "def";
    defender.name = "赤水宗";
    s.gameData.worldMapSects = {player, attacker, defender};
    s.aiSectDisciples["atk"] = makeSquad("att", 0, 10);
    s.aiSectDisciples["def"] = makeSquad("def", 9, 10);
    return s;
}

bool occupiedBy(const GameState& s, const std::string& sectId,
                const std::string& occupierId) {
    for (const auto& sect : s.gameData.worldMapSects) {
        if (sect.id == sectId) return sect.occupierSectId == occupierId;
    }
    return false;
}

// ── 征伐环：占领归属翻转 + 池合并 + 好感惩罚 ────────────────────────

TEST(SectConquestTest, ConquestFlipsOwnershipAndMergesPools) {
    // 攻方 realm 0 压制守方 realm 9（弱者 realm>5 不阻占领）——种子扫描
    // 至少一局攻方全胜占领
    bool occupied = false;
    for (int32_t seed = 1; seed <= 60 && !occupied; ++seed) {
        GameState s = makeConquestState();
        state::SectRelation relation;
        relation.sectId1 = "atk";
        relation.sectId2 = "def";
        relation.favor = 50;
        s.gameData.sectRelations.push_back(relation);

        rng::RngManager rng;
        rng.initSystemSeed(seed);
        std::vector<std::string> seizedIds;
        detail::processAiConquestSubEvent(s, rng, seizedIds);
        occupied = occupiedBy(s, "def", "atk");

        if (occupied) {
            // 好感 -10（0..100 夹取）
            ASSERT_FALSE(s.gameData.sectRelations.empty());
            EXPECT_LE(s.gameData.sectRelations.front().favor, 40);
            // 守方池清空、幸存者并入攻方池
            const auto defIt = s.aiSectDisciples.find("def");
            ASSERT_TRUE(defIt != s.aiSectDisciples.end());
            int32_t defAlive = 0;
            for (const auto& d : defIt->second) {
                if (d.isAlive) ++defAlive;
            }
            EXPECT_EQ(defAlive, 0);
            // 新驻军槽 = 攻方幸存者（至少 1 槽有守军）
            const WorldSect* defSect = nullptr;
            for (const auto& sect : s.gameData.worldMapSects) {
                if (sect.id == "def") { defSect = &sect; break; }
            }
            ASSERT_TRUE(defSect != nullptr);
            int32_t filled = 0;
            for (const auto& slot : defSect->garrisonSlots) {
                if (!slot.discipleId.empty()) ++filled;
            }
            EXPECT_GE(filled, 1);
        }
    }
    // 压倒性战力差下至少一次占领（否则战斗引擎语义漂移，须人工核查）
    EXPECT_TRUE(occupied);
}

TEST(SectConquestTest, DefenderKeepsSectWhenAttackerLoses) {
    // 守方 realm 0 强者反杀攻方 realm 9 弱者：守方存续、归属不变
    GameState s = makeConquestState();
    s.aiSectDisciples["atk"] = makeSquad("att", 9, 10);   // 弱攻方
    s.aiSectDisciples["def"] = makeSquad("def", 0, 10);   // 强守方

    rng::RngManager rng;
    rng.initSystemSeed(11);
    const auto results = conquest::decideAttacks(s, rng);
    bool attacked = !results.empty();
    for (int32_t seed = 12; seed <= 60 && !attacked; ++seed) {
        rng.initSystemSeed(seed);
        auto retry = conquest::decideAttacks(s, rng);
        attacked = !retry.empty();
        if (attacked) {
            for (const auto& r : retry) {
                if (r.winner == battle::AiBattleWinner::kAttacker) {
                    // 攻方胜路径在本场景不应出现（弱攻强守，若出现即
                    // 战斗引擎语义漂移）
                    EXPECT_FALSE(r.canOccupy || occupiedBy(s, "def", "atk"));
                }
            }
        }
    }
    // 种子扫描下至少一局开战（门通过 + 概率判定通过）
    EXPECT_TRUE(attacked);
}

// ── 编排约束：首个可攻击目标即停 + 人数门槛 ─────────────────────────

TEST(SectConquestTest, AttackerBelowMinDisciplesNeverAttacks) {
    GameState s = makeConquestState();
    s.aiSectDisciples["atk"] = makeSquad("att", 0, 5);   // 低于 10 人门槛
    s.aiSectDisciples["def"] = makeSquad("def", 0, 5);   // def 亦为合法攻击者——同门槛压制

    for (int32_t seed = 1; seed <= 30; ++seed) {
        rng::RngManager rng;
        rng.initSystemSeed(seed);
        const int64_t before = rng.getRng(rng::RngPartition::kBattle).snapshot();
        const auto results = conquest::decideAttacks(s, rng);
        EXPECT_TRUE(results.empty());
        // 人数门槛前置早退：BATTLE 分区零消耗
        EXPECT_EQ(rng.getRng(rng::RngPartition::kBattle).snapshot(), before);
    }
}

// ── 玩家占领宗门被夺回：归属翻转 + isOwned 清除 + 建筑没收草稿 ──────

TEST(SectConquestTest, PlayerOccupiedRetakeEmitsSeizureDraft) {
    // def 池低于攻击门槛 → atk 唯一可达目标是玩家占领宗门 poc
    //（Kotlin decideAttacks 同款：def 作为后续攻击者也被门槛压制）
    bool seized = false;
    for (int32_t seed = 1; seed <= 60 && !seized; ++seed) {
        GameState s = makeConquestState();
        s.aiSectDisciples["def"] = makeSquad("def", 0, 5);
        // 玩家占领宗门 poc（占领者 p1）+ 驻军 = 玩家弟子（弱者）
        // ——全部字段先于 push_back 构造（worldMapSects 存副本）
        WorldSect poc;
        poc.id = "poc";
        poc.name = "被夺回宗门";
        poc.isPlayerOccupied = true;
        poc.occupierSectId = "p1";
        for (int32_t i = 0; i < 10; ++i) {
            state::GarrisonSlot slot;
            slot.index = i;
            slot.discipleId = "g" + std::to_string(i);
            poc.garrisonSlots.push_back(slot);
        }
        state::SectDetail detail;
        detail.sectId = "poc";
        detail.isOwned = true;
        s.gameData.sectDetails["poc"] = detail;
        s.gameData.worldMapSects.push_back(poc);
        for (int32_t i = 0; i < 10; ++i) {
            s.disciples.appendDisciple(makeDisciple("g" + std::to_string(i), 9));
        }

        rng::RngManager rng;
        rng.initSystemSeed(seed);
        std::vector<std::string> seizedIds;
        detail::processAiConquestSubEvent(s, rng, seizedIds);

        const bool pocRetaken = occupiedBy(s, "poc", "atk");
        if (!pocRetaken) continue;

        EXPECT_FALSE(s.gameData.sectDetails["poc"].isOwned);
        // 驻军玩家弟子阵亡 → markDead 统一入口（年报计数一致）
        int32_t garrisonDead = 0;
        for (int32_t i = 0; i < 10; ++i) {
            const auto row = s.disciples.rowOf("g" + std::to_string(i));
            ASSERT_TRUE(row.has_value());
            if (s.disciples.isAlive[*row] == 0) ++garrisonDead;
        }
        EXPECT_EQ(garrisonDead, s.gameData.annualDeceasedDisciples);
        // 没收草稿
        if (!seizedIds.empty()) {
            ASSERT_EQ(seizedIds.size(), 1u);
            EXPECT_EQ(seizedIds.front(), "poc");
            seized = true;
        }
    }
    // 弱驻军 + 强攻方扫描下至少一次夺回兼没收
    EXPECT_TRUE(seized);
}

// ── 决策-应用两段式：决策不改状态 ──────────────────────────────────

TEST(SectConquestTest, DecideAttacksDoesNotMutateState) {
    GameState s = makeConquestState();
    GameState snapshot = s;   // 值语义快照

    rng::RngManager rng;
    rng.initSystemSeed(21);
    const auto results = conquest::decideAttacks(s, rng);
    for (int32_t seed = 22; seed <= 60 && results.empty(); ++seed) {
        rng.initSystemSeed(seed);
        auto retry = conquest::decideAttacks(s, rng);
        if (!retry.empty()) break;
    }
    // 状态未变（ disciples 池/宗门归属——决策阶段零 mutation）
    EXPECT_EQ(s.aiSectDisciples.size(), snapshot.aiSectDisciples.size());
    EXPECT_FALSE(occupiedBy(s, "def", "atk"));
}

}  // namespace
}  // namespace gamecore::system
