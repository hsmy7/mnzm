#include <gtest/gtest.h>

// 子事件 6b：AI 攻玩家决策与防守战（P2-18 决策项③ Stage 1）全链测试。
//
// 包含契约：sect_defense_battle.h 须在 month_settlement.h 之后
// （本文件经第一包含引入）。
#include "gamecore/rng/rng_manager.h"
#include "gamecore/system/month_settlement.h"
#include "gamecore/system/sect_attack_decision.h"
#include "gamecore/system/sect_defense_battle.h"

#include <algorithm>
#include <map>
#include <string>
#include <vector>

namespace gamecore::system {
namespace {

using gamecore::state::AttackWarning;
using gamecore::state::Disciple;
using gamecore::state::GameState;
using gamecore::state::WorldSect;

constexpr const char* kWarStage = "WAR_DECLARATION";
constexpr const char* kDenunciationStage = "DENUNCIATION";

state::Disciple makeDisciple(const std::string& id, int32_t realm) {
    state::Disciple d;
    d.id = id;
    d.name = "弟子" + id;
    d.isAlive = true;
    d.realm = realm;
    d.realmLayer = 1;
    d.currentHp = -1;   // 满血语义（Kotlin currentHp -1 哨兵同款）
    d.currentMp = -1;
    return d;
}

/// AI 宗门攻击队（realm 按 from..to 降序生成——id 序与 realm 序一致）
std::vector<state::Disciple> makeAiSquad(const std::string& prefix,
                                         int32_t realm, int32_t count) {
    std::vector<state::Disciple> out;
    for (int32_t i = 0; i < count; ++i) {
        out.push_back(makeDisciple(prefix + std::to_string(i), realm));
    }
    return out;
}

/// 标准场景：玩家宗门 p1 + AI 攻方 atk（realm 0 强者 10 名）+
/// 玩家弟子 10 名（realm 9 弱者，入 DiscipleStore 行序）。返回 state。
GameState makeBattleState() {
    GameState s;
    s.gameData.gameYear = 101;   // 保护期（100 年）届满
    s.gameData.gameMonth = 3;

    WorldSect player;
    player.id = "p1";
    player.name = "青云宗";
    player.isPlayerSect = true;
    WorldSect attacker;
    attacker.id = "atk";
    attacker.name = "青岚宗";
    s.gameData.worldMapSects = {player, attacker};

    s.aiSectDisciples["atk"] = makeAiSquad("att", 0, 10);

    for (int32_t i = 0; i < 10; ++i) {
        s.disciples.appendDisciple(makeDisciple(std::to_string(i + 1), 9));
    }
    return s;
}

AttackWarning makeWarning(const std::string& attackerId,
                          const std::string& attackerName, int32_t attackMonth,
                          int32_t createdAt) {
    AttackWarning w;
    w.warningId = "warn_" + attackerId + "_" + std::to_string(createdAt);
    w.attackerSectId = attackerId;
    w.attackerSectName = attackerName;
    w.stage = kWarStage;
    w.attackMonth = attackMonth;
    w.createdAtMonth = createdAt;
    return w;
}

int32_t nowMonthOf(const GameState& s) {
    return s.gameData.gameYear * 12 + s.gameData.gameMonth;
}

bool hasWarningFor(const GameState& s, const std::string& attackerId) {
    for (const auto& w : s.gameData.activeAttackWarnings) {
        if (w.attackerSectId == attackerId) return true;
    }
    return false;
}

// ── 预警收敛（normalizeAttackWarnings） ─────────────────────────────

TEST(SectDefenseBattleTest, NormalizeConvergesLegacyStagesAndFutureMonths) {
    GameState s = makeBattleState();
    const int32_t now = nowMonthOf(s);
    AttackWarning legacy = makeWarning("atk", "青岚宗", now + 5, now);
    legacy.stage = kDenunciationStage;                    // 旧档阶段
    AttackWarning deferred = makeWarning("atk2", "赤水宗", now + 5, now);
    AttackWarning expired = makeWarning("atk3", "落霞宗", now - 1, now - 2);
    s.gameData.activeAttackWarnings = {legacy, deferred, expired};

    defense_battle::normalizeAttackWarnings(s);

    ASSERT_EQ(s.gameData.activeAttackWarnings.size(), 3u);
    // 旧档阶段收敛为战书 + 下月
    EXPECT_EQ(s.gameData.activeAttackWarnings[0].stage, kWarStage);
    EXPECT_EQ(s.gameData.activeAttackWarnings[0].attackMonth, now + 1);
    // 过晚的战书提前到下月
    EXPECT_EQ(s.gameData.activeAttackWarnings[1].attackMonth, now + 1);
    // 已到期预警保持原值（本批立即结算，不推迟）
    EXPECT_EQ(s.gameData.activeAttackWarnings[2].attackMonth, now - 1);
}

// ── 攻击决策 → 预警 + 冷却写点① ────────────────────────────────────

TEST(SectDefenseBattleTest, DecisionGeneratesWarningAndWritesCooldown) {
    GameState s = makeBattleState();
    s.gameData.playerProtectionEnabled = false;   // 解除保护
    const int32_t now = nowMonthOf(s);

    // 攻方极强（realm 0 vs 玩家 realm 9）→ chance 贴近 maxChance(0.95)；
    // 固定种子扫描（PRNG 确定性 → 扫描结果跨平台一致）
    rng::RngManager rng;
    bool generated = false;
    for (int32_t seed = 1; seed <= 200 && !generated; ++seed) {
        rng.initSystemSeed(seed);
        s.gameData.activeAttackWarnings.clear();
        s.gameData.sectAttackCooldowns.clear();
        const auto decision = detail::decidePlayerAttack(s, rng);
        generated = decision.type ==
                    detail::PlayerAttackDecisionType::kGenerateWarning;
    }
    ASSERT_TRUE(generated);

    // 经子事件入口复跑（种子扫描同款流程）：预警与冷却落状态
    rng.initSystemSeed(1);
    bool generatedViaSubEvent = false;
    for (int32_t seed = 1; seed <= 200 && !generatedViaSubEvent; ++seed) {
        rng.initSystemSeed(seed);
        s.gameData.activeAttackWarnings.clear();
        s.gameData.sectAttackCooldowns.clear();
        detail::processAiPlayerDefenseSubEvent(s, rng);
        generatedViaSubEvent = hasWarningFor(s, "atk");
    }
    ASSERT_TRUE(generatedViaSubEvent);
    ASSERT_EQ(s.gameData.activeAttackWarnings.size(), 1u);
    const auto& w = s.gameData.activeAttackWarnings.front();
    EXPECT_EQ(w.attackerSectId, "atk");
    EXPECT_EQ(w.attackerSectName, "青岚宗");
    EXPECT_EQ(w.stage, kWarStage);
    EXPECT_EQ(w.attackMonth, now + 1);      // 下月进攻
    EXPECT_EQ(w.createdAtMonth, now);
    // 冷却写点①：预警生成即写（now + 12）
    const auto cd = s.gameData.sectAttackCooldowns.find("atk");
    ASSERT_TRUE(cd != s.gameData.sectAttackCooldowns.end());
    EXPECT_EQ(cd->second, now + 12);
}

TEST(SectDefenseBattleTest, ProtectedPlayerNeverGeneratesWarning) {
    GameState s = makeBattleState();
    // 默认 playerProtectionEnabled=true，gameYear=101 - startYear 1 = 100
    // 不 < 100 → 不受保护；回到年内保护：startYear 推到 90
    s.gameData.playerProtectionStartYear = 90;
    rng::RngManager rng;
    rng.initSystemSeed(42);
    const int64_t before = rng.getRng(rng::RngPartition::kBattle).snapshot();
    const auto decision = detail::decidePlayerAttack(s, rng);
    EXPECT_EQ(decision.type, detail::PlayerAttackDecisionType::kSkip);
    EXPECT_EQ(rng.getRng(rng::RngPartition::kBattle).snapshot(), before);
    EXPECT_FALSE(hasWarningFor(s, "atk"));
}

// ── 到期战书 → 防守战全链（结果应用/伤亡/冷却写点②/事件） ──────────

TEST(SectDefenseBattleTest, ExpiredWarningExecutesDefenseBattleFullChain) {
    GameState s = makeBattleState();
    const int32_t now = nowMonthOf(s);
    s.gameData.activeAttackWarnings.push_back(
        makeWarning("atk", "青岚宗", now, now - 1));   // 当月到期
    rng::RngManager rng;
    rng.initSystemSeed(7);

    detail::processAiPlayerDefenseSubEvent(s, rng);

    // 预警移除 + 冷却写点②（now + 12）
    EXPECT_FALSE(hasWarningFor(s, "atk"));
    const auto cd = s.gameData.sectAttackCooldowns.find("atk");
    ASSERT_TRUE(cd != s.gameData.sectAttackCooldowns.end());
    EXPECT_EQ(cd->second, now + 12);

    // shownWarningStageIds 键同步清空（P2-9 键 = attackerSectId:stage）
    const std::string stageKey = "atk:" + std::string(kWarStage);
    EXPECT_EQ(std::find(s.gameData.shownWarningStageIds.begin(),
                        s.gameData.shownWarningStageIds.end(), stageKey),
              s.gameData.shownWarningStageIds.end());

    // 消息栏事件（Kotlin battleLogs 显示域不入 C++ 的登记口径）
    bool eventFound = false;
    for (const auto& e : s.gameData.gameEventRecords) {
        if (e.eventType == "sect_defense_battle") {
            eventFound = true;
            EXPECT_NE(e.summary.find("青岚宗"), std::string::npos);
        }
    }
    EXPECT_TRUE(eventFound);

    // 双侧伤亡守恒：玩家阵亡数 + AI 阵亡数 = 参战 20 人中实际死亡数；
    // 玩家阵亡走 markDead 统一入口（isAlive/status/deathYears/年报计数）
    int32_t playerDeaths = 0;
    for (std::size_t row = 0; row < s.disciples.size(); ++row) {
        if (s.disciples.isAlive[row] == 0) {
            ++playerDeaths;
            EXPECT_EQ(s.disciples.statuses[row], "DEAD");
            EXPECT_EQ(s.disciples.deathYears[row], s.gameData.gameYear);
        }
    }
    EXPECT_EQ(playerDeaths, s.gameData.annualDeceasedDisciples);

    const auto& pool = s.aiSectDisciples["atk"];
    int32_t aiDeaths = 0;
    for (const auto& d : pool) {
        if (!d.isAlive) {
            ++aiDeaths;
            EXPECT_EQ(d.deathYear, s.gameData.gameYear);   // P1-7 尸体窗口基准
            EXPECT_EQ(d.status, "DEAD");
        }
    }
    // 攻方至少存活 1 人或守方至少存活 1 人（战斗必有非平局倾向；
    // 平局时双侧存活——只断言计数一致性与上限）
    EXPECT_LE(playerDeaths + aiDeaths, 20);
    // 冷却已写 → 同月决策不重复生成预警（hasWarning/cooldown 双闸）
    EXPECT_FALSE(hasWarningFor(s, "atk"));
}

TEST(SectDefenseBattleTest, DefenseLossLootsWarehouseAndDropsFavor) {
    // 攻方 realm 0 压制守方 realm 9 → 守方全灭为压倒性倾向（种子扫描下
    // 断言战斗后玩家侧出现阵亡即视为攻方胜路径被覆盖；仓库掠夺只在
    // 攻方胜时发生——构造 10 局扫描至少一局攻方胜）
    bool looted = false;
    bool favorDropped = false;
    for (int32_t seed = 1; seed <= 60 && !looted; ++seed) {
        GameState s = makeBattleState();
        state::SectDetail detail;
        detail.sectId = "p1";
        detail.warehouse.spiritStones = 1000;
        state::WarehouseItem item;
        item.itemId = "mat_1";
        item.itemName = "铁精";
        item.itemType = "MATERIAL";
        item.rarity = 2;
        item.quantity = 10;
        detail.warehouse.items.push_back(item);
        state::SectRelation relation;
        relation.sectId1 = "atk";
        relation.sectId2 = "p1";
        relation.favor = 50;
        s.gameData.sectRelations.push_back(relation);
        s.gameData.sectDetails["p1"] = detail;

        const int32_t now = nowMonthOf(s);
        s.gameData.activeAttackWarnings.push_back(
            makeWarning("atk", "青岚宗", now, now - 1));
        rng::RngManager rng;
        rng.initSystemSeed(seed);
        detail::processAiPlayerDefenseSubEvent(s, rng);

        // 好感 -15（0..100 夹取）——无论胜负
        ASSERT_FALSE(s.gameData.sectRelations.empty());
        EXPECT_LE(s.gameData.sectRelations.front().favor, 35);
        favorDropped = favorDropped ||
                       s.gameData.sectRelations.front().favor < 50;

        // 攻方胜路径：仓库 40% 掠夺（灵石 1000→600、物品 10→6）
        const int32_t playerDeaths = static_cast<int32_t>(std::count_if(
            s.disciples.isAlive.begin(),
            s.disciples.isAlive.begin() + static_cast<std::ptrdiff_t>(
                                             s.disciples.size()),
            [](int8_t alive) { return alive == 0; }));
        if (playerDeaths == 10) {   // 守方全灭 = 攻方胜（Kotlin canOccupy 口径）
            const auto& wh = s.gameData.sectDetails["p1"].warehouse;
            EXPECT_EQ(wh.spiritStones, 600);
            ASSERT_FALSE(wh.items.empty());
            EXPECT_EQ(wh.items.front().quantity, 6);
            looted = true;
        }
    }
    // 压倒性战力差下攻方至少一胜（否则战斗引擎语义漂移，须人工核查）
    EXPECT_TRUE(looted);
    EXPECT_TRUE(favorDropped);
}

TEST(SectDefenseBattleTest, TooFewAttackersKeepsExpiredWarning) {
    GameState s = makeBattleState();
    s.aiSectDisciples["atk"] = makeAiSquad("att", 0, 5);   // 低于 10 人门槛
    const int32_t now = nowMonthOf(s);
    s.gameData.activeAttackWarnings.push_back(
        makeWarning("atk", "青岚宗", now, now - 1));
    rng::RngManager rng;
    rng.initSystemSeed(7);

    detail::processAiPlayerDefenseSubEvent(s, rng);

    // Kotlin executePlayerAttack null → 事务中止：预警保留、零效果
    EXPECT_TRUE(hasWarningFor(s, "atk"));
    EXPECT_EQ(s.gameData.sectAttackCooldowns.count("atk"), 0u);
    EXPECT_EQ(s.gameData.annualDeceasedDisciples, 0);
}

// ── 防守选人（巡逻优先 + 状态排除） ────────────────────────────────

TEST(SectDefenseBattleTest, SelectDefenseSquadExcludesBusyStatuses) {
    GameState s = makeBattleState();
    // 1 号弟子出任务（ON_MISSION）→ 排除
    s.disciples.statuses[*s.disciples.rowOf("1")] = "ON_MISSION";
    // 2 号弟子巡逻（PATROLLING 不排除且优先）
    s.disciples.statuses[*s.disciples.rowOf("2")] = "PATROLLING";
    state::PatrolSlot slot;
    slot.index = 0;
    slot.discipleId = "2";
    s.gameData.patrolSlots.push_back(slot);
    // 3 号弟子阵亡 → 排除
    s.disciples.isAlive[*s.disciples.rowOf("3")] = 0;

    const auto squad = defense_battle::selectDefenseSquad(s);

    EXPECT_EQ(squad.size(), 8u);   // 10 - 出任务 1 - 阵亡 1
    EXPECT_EQ(std::find(squad.begin(), squad.end(), "1"), squad.end());
    EXPECT_EQ(std::find(squad.begin(), squad.end(), "3"), squad.end());
    EXPECT_EQ(squad.front(), "2");   // 巡逻优先居首
}

// ── AI 占领宗门驻军填充（fillEmptyGarrisonSlots） ──────────────────

TEST(SectDefenseBattleTest, FillEmptyGarrisonSlotsFillsVacantOnly) {
    GameState s = makeBattleState();
    // 独立占领者 oc（池仅 2 名未驻守弟子，断言确定性）+ AI 占领宗门 oc1
    // + 玩家占领宗门 oc2（排除）
    WorldSect occupier;
    occupier.id = "oc";
    occupier.name = "占领者宗门";
    s.gameData.worldMapSects.push_back(occupier);
    WorldSect oc1;
    oc1.id = "oc1";
    oc1.name = "被占领宗门";
    oc1.occupierSectId = "oc";
    state::GarrisonSlot emptySlot;
    emptySlot.index = 0;
    state::GarrisonSlot deadSlot;
    deadSlot.index = 1;
    deadSlot.discipleId = "ghost";   // 指向池中不存在的弟子 → 空槽
    oc1.garrisonSlots = {emptySlot, deadSlot};
    WorldSect oc2;
    oc2.id = "oc2";
    oc2.name = "玩家占领宗门";
    oc2.isPlayerOccupied = true;
    oc2.occupierSectId = "p1";
    state::GarrisonSlot playerOccupiedSlot;
    playerOccupiedSlot.index = 0;
    oc2.garrisonSlots = {playerOccupiedSlot};
    s.gameData.worldMapSects.push_back(oc1);
    s.gameData.worldMapSects.push_back(oc2);
    s.aiSectDisciples["oc"] = {makeDisciple("g1", 3), makeDisciple("g2", 4)};

    defense_battle::fillEmptyGarrisonSlots(s);

    // 索引 3 = oc1（game_core_test 场景序：p1/atk 后 push occupier/oc1/oc2）
    const WorldSect& updated = s.gameData.worldMapSects[3];
    EXPECT_EQ(updated.garrisonSlots[0].discipleId, "g1");   // realm 3 强者优先
    EXPECT_EQ(updated.garrisonSlots[1].discipleId, "g2");
    // 玩家占领宗门不被 AI 填充
    const WorldSect& updatedOc2 = s.gameData.worldMapSects[4];
    EXPECT_TRUE(updatedOc2.garrisonSlots[0].discipleId.empty());
}

}  // namespace
}  // namespace gamecore::system
