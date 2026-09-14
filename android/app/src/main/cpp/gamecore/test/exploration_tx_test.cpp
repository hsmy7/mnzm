// ============================================================
// exploration_tx_test.cpp — 探索域 UI 操作事务黄金用例（batch-13 下沉）
//
// 守护目标：attackWorldLevelTx / scoutSectTx / assignGarrisonTx /
// removeGarrisonTx 的校验链判定序（与 Kotlin 三 Ops 文件逐字对齐）、
// 战斗终态与伤亡写回、失败零写入、RNG 审计：
//   - 零 RNG 族（分舵驻守）：全分区快照差分（零抽取）
//   - 有 RNG 族（关卡/侦察战斗）：仅 BATTLE 分区变化（组装零抽取——
//     pregen/基础值两分支均无 ENEMY_GEN 消费）+ 双运行逐位一致
//     + 终态 rngStates 锁定
//
// Kotlin 语义权威 = GameEngineWorldBattleOps / GameEngineScoutOps /
// GameEngineGarrisonOps（端到端对拍由真机对拍框架覆盖——战斗日志
// message 域为 Kotlin 重建口径）。
// ============================================================
#include <gtest/gtest.h>

#include <map>
#include <string>
#include <vector>

#include "gamecore/game_core.h"
#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/exploration_tx.h"
#include "gamecore/system/inventory.h"

namespace {

using gamecore::rng::RngManager;
using gamecore::state::Disciple;
using gamecore::state::GameState;
using gamecore::state::GarrisonSlot;
using gamecore::state::PatrolSlot;
using gamecore::state::WorldLevel;
using gamecore::state::WorldSect;
namespace exploration_tx = gamecore::system::exploration_tx;

/// 必胜档弟子（境界近顶 + 属性压倒——境界序 0 最强、9 最弱，
/// 高境界对低境界妖兽伤害放大、受击为零压制）
Disciple baseDisciple(const std::string& id) {
    Disciple d;
    d.id = id;
    d.name = "弟子" + id;
    d.realm = 1;
    d.realmLayer = 9;
    d.age = 20;
    d.spiritRootType = "metal";
    d.baseHp = 1000000;
    d.baseMp = 100000;
    d.basePhysicalAttack = 1000000;
    d.baseMagicAttack = 1000000;
    d.basePhysicalDefense = 1000000;
    d.baseMagicDefense = 1000000;
    d.baseSpeed = 10000;
    d.portraitRes = "p" + id;
    return d;
}

/// 弱档弟子（最低境界 + 默认裸装属性——供全灭档使用）
Disciple weakDisciple(const std::string& id) {
    Disciple d;
    d.id = id;
    d.name = "弟子" + id;
    d.realm = 9;
    d.realmLayer = 1;
    d.age = 20;
    d.spiritRootType = "metal";
    d.portraitRes = "p" + id;
    return d;
}

/// 弱妖兽关（pregen 属性极低 + 境界 3 低于玩家——玩家队必胜）
WorldLevel weakBeastLevel(const std::string& id = "lv1") {
    WorldLevel l;
    l.id = id;
    l.type = "BEAST";
    l.beastType = 2;
    l.realm = 3;
    l.realmLayer = 2;
    l.beastName = "弱妖";
    l.count = 2;
    l.beastMaxHp = 10;
    l.beastMaxMp = 10;
    l.beastPhysicalAttack = 0;
    l.beastMagicAttack = 0;
    l.beastPhysicalDefense = 0;
    l.beastMagicDefense = 0;
    l.beastSpeed = 0;
    return l;
}

/// 压制妖兽关（pregen 属性极高 + 境界 0 最高——默认档玩家队必败全灭）
WorldLevel overkillBeastLevel(const std::string& id = "lv1") {
    WorldLevel l;
    l.id = id;
    l.type = "BEAST";
    l.beastType = 0;
    l.realm = 0;
    l.realmLayer = 5;
    l.beastName = "凶妖";
    l.count = 3;
    l.beastMaxHp = 10000000;
    l.beastMaxMp = 100000;
    l.beastPhysicalAttack = 1000000;
    l.beastMagicAttack = 1000000;
    l.beastPhysicalDefense = 1000000;
    l.beastMagicDefense = 1000000;
    l.beastSpeed = 100000;
    return l;
}

/// 空世界状态（年 1 月 1）
GameState emptyState() {
    GameState st;
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 1;
    return st;
}

/// 攻关档：2 名弟子 + 关卡
GameState attackState(const WorldLevel& level) {
    GameState st = emptyState();
    st.gameData.worldLevels.push_back(level);
    st.disciples.appendDisciple(baseDisciple("1"));
    st.disciples.appendDisciple(baseDisciple("2"));
    return st;
}

/// 侦察档：玩家宗门 + 目标宗门（AI 弟子 realm 6..9 各若干）
GameState scoutState() {
    GameState st = emptyState();
    WorldSect player;
    player.id = "sect_player";
    player.isPlayerSect = true;
    WorldSect target;
    target.id = "sect_ai";
    target.name = "青云宗";
    st.gameData.worldMapSects.push_back(player);
    st.gameData.worldMapSects.push_back(target);
    st.disciples.appendDisciple(baseDisciple("1"));
    st.disciples.appendDisciple(baseDisciple("2"));
    // 10 名 AI 守卫：realm 6 ×1（被滤）+ realm 7..9 ×9（取前 8）
    for (int i = 0; i < 10; ++i) {
        Disciple ai;
        ai.id = "ai" + std::to_string(i);
        ai.name = "青弟子" + std::to_string(i);
        ai.realm = i == 0 ? 6 : 7 + (i % 3);
        ai.realmLayer = 1;
        ai.age = 20;
        ai.isAlive = i != 9;   // 最后一名死亡（被滤）
        ai.portraitRes = "ai" + std::to_string(i);
        st.aiSectDisciples["sect_ai"].push_back(ai);
    }
    return st;
}

/// 驻守档：玩家宗门 1 槽 + AI 宗门 1 槽 + 巡逻 1 槽 + 2 名弟子
GameState garrisonState() {
    GameState st = emptyState();
    WorldSect player;
    player.id = "sect_player";
    player.isPlayerSect = true;
    player.garrisonSlots.push_back(GarrisonSlot{});
    player.garrisonSlots[0].index = 0;
    WorldSect other;
    other.id = "sect_ai";
    other.garrisonSlots.push_back(GarrisonSlot{});
    other.garrisonSlots[0].index = 0;
    st.gameData.worldMapSects = {player, other};
    st.gameData.patrolSlots.push_back(PatrolSlot{});
    st.gameData.patrolSlots[0].index = 0;
    st.disciples.appendDisciple(baseDisciple("1"));
    st.disciples.appendDisciple(baseDisciple("2"));
    return st;
}

/// 分区状态快照（RNG 审计用）
std::map<int32_t, int64_t> rngSnapshot(const RngManager& rng) {
    return rng.exportStates();
}

// ── attackWorldLevelTx：校验链 ────────────────────────────────

TEST(ExplorationTxTest, AttackRejectsMissingLevel) {
    auto st = attackState(weakBeastLevel());
    RngManager rng;
    rng.initSystemSeed(42);
    gamecore::system::OverflowMailCollector mail;
    const auto r = exploration_tx::attackWorldLevelTx(st, rng, "nope", {"1", "2"},
                                                      1.0, mail);
    EXPECT_FALSE(r.ok);
    EXPECT_EQ("NOT_FOUND", r.errorType);
    EXPECT_TRUE(rng.exportStates() == rngSnapshot(rng));  // 零写入零抽取
}

TEST(ExplorationTxTest, AttackRejectsDefeatedLevel) {
    auto st = attackState(weakBeastLevel());
    st.gameData.worldLevels[0].defeated = true;
    RngManager rng;
    rng.initSystemSeed(42);
    gamecore::system::OverflowMailCollector mail;
    const auto r = exploration_tx::attackWorldLevelTx(st, rng, "lv1", {"1", "2"},
                                                      1.0, mail);
    EXPECT_FALSE(r.ok);
    EXPECT_EQ("ALREADY_DEFEATED", r.errorType);
}

TEST(ExplorationTxTest, AttackRejectsNoCombatants) {
    auto st = attackState(weakBeastLevel());
    RngManager rng;
    rng.initSystemSeed(42);
    const auto before = rngSnapshot(rng);
    gamecore::system::OverflowMailCollector mail;
    // 弟子不存在 / 空列表 → NO_COMBATANTS（篡改档防御；生产 Kotlin 臂预检）
    const auto r1 = exploration_tx::attackWorldLevelTx(st, rng, "lv1", {"99"},
                                                       1.0, mail);
    EXPECT_FALSE(r1.ok);
    EXPECT_EQ("NO_COMBATANTS", r1.errorType);
    const auto r2 = exploration_tx::attackWorldLevelTx(st, rng, "lv1", {},
                                                       1.0, mail);
    EXPECT_FALSE(r2.ok);
    EXPECT_EQ("NO_COMBATANTS", r2.errorType);
    EXPECT_TRUE(before == rngSnapshot(rng));  // 校验失败臂零抽取
    EXPECT_EQ(0, st.gameData.annualDeceasedDisciples);      // 零写入
}

// ── attackWorldLevelTx：胜利臂 + RNG 面锁定 ───────────────────

TEST(ExplorationTxTest, AttackBeastPreGenVictoryWritesSurvivors) {
    auto st = attackState(weakBeastLevel());
    RngManager rng;
    rng.initSystemSeed(42);
    const auto rngBefore = rngSnapshot(rng);
    gamecore::system::OverflowMailCollector mail;
    const auto r = exploration_tx::attackWorldLevelTx(st, rng, "lv1",
                                                      {"1", "2"}, 1.0, mail);
    ASSERT_TRUE(r.ok);
    EXPECT_TRUE(r.victory);
    EXPECT_EQ(2u, r.survivorIds.size());
    EXPECT_TRUE(r.deadIds.empty());
    // C++ 不写 defeated（Kotlin 胜利事务残差）
    EXPECT_FALSE(st.gameData.worldLevels[0].defeated);
    // 幸存者 HP 写回 [0, maxHp]
    for (const auto& id : r.survivorIds) {
        const auto row = *st.disciples.rowOf(id);
        ASSERT_TRUE(st.disciples.isAlive[row] == 1);
        EXPECT_GE(st.disciples.currentHps[row], 0);
    }
    // RNG 审计：组装零抽取——仅 BATTLE 分区变化，其余分区逐位不变
    const auto after = rngSnapshot(rng);
    ASSERT_EQ(rngBefore.size(), after.size());
    for (const auto& [partition, state] : rngBefore) {
        if (partition == static_cast<int32_t>(gamecore::rng::RngPartition::kBattle)) {
            EXPECT_NE(state, after.at(partition)) << "BATTLE 分区应被消费";
        } else {
            EXPECT_EQ(state, after.at(partition))
                << "非 BATTLE 分区 " << partition << " 被意外抽取";
        }
    }
}

TEST(ExplorationTxTest, AttackBeastWipeMaterializesBagAndMarksDead) {
    // 弱档弟子（境界 9 裸装）vs 仙人境界压制妖兽 → 必败全灭
    auto st = emptyState();
    st.gameData.worldLevels.push_back(overkillBeastLevel());
    st.disciples.appendDisciple(weakDisciple("1"));
    st.disciples.appendDisciple(weakDisciple("2"));
    // 弟子 1 袋中带 1 条堆叠材料 → 阵亡物化回仓
    auto row = *st.disciples.rowOf("1");
    gamecore::state::StorageBagItem bagItem;
    bagItem.itemType = "material";
    bagItem.name = "袋中材料";
    bagItem.rarity = 2;
    bagItem.quantity = 2;
    bagItem.stackedData = gamecore::state::BagStackedData{};   // 堆叠载荷（空载荷按 Kotlin 语义丢弃）
    st.disciples.storageBagItems[row].push_back(bagItem);
    const size_t warehouseBefore = st.materials.size();

    RngManager rng;
    rng.initSystemSeed(42);
    gamecore::system::OverflowMailCollector mail;
    const auto r = exploration_tx::attackWorldLevelTx(st, rng, "lv1",
                                                      {"1", "2"}, 1.0, mail);
    ASSERT_TRUE(r.ok);
    EXPECT_FALSE(r.victory);
    EXPECT_EQ(2u, r.deadIds.size());
    EXPECT_TRUE(r.survivorIds.empty());
    for (const auto& id : r.deadIds) {
        const auto deadRow = *st.disciples.rowOf(id);
        EXPECT_EQ(0, st.disciples.isAlive[deadRow]);
        EXPECT_EQ(gamecore::system::kDeadStatusName, st.disciples.statuses[deadRow]);
        EXPECT_EQ(1, st.disciples.deathYears[deadRow]);
    }
    EXPECT_EQ(2, st.gameData.annualDeceasedDisciples);
    // 袋物化：材料回仓（条目并入堆叠或新增——总量 > before 即物化成功）
    EXPECT_GT(st.materials.size(), warehouseBefore);
    // 清袋幂等（Kotlin processBattleCasualties 事务外重跑安全）
    EXPECT_TRUE(st.disciples.storageBagItems[*st.disciples.rowOf("1")].empty());
}

TEST(ExplorationTxTest, AttackDoubleRunBitIdentical) {
    const auto run = [] {
        auto st = attackState(weakBeastLevel());
        RngManager rng;
        rng.initSystemSeed(2024);
        gamecore::system::OverflowMailCollector mail;
        const auto r = exploration_tx::attackWorldLevelTx(
            st, rng, "lv1", {"1", "2"}, 1.05, mail);
        return std::pair<exploration_tx::ExplorationBattleOutcome,
                         std::map<int32_t, int64_t>>(r, rng.exportStates());
    };
    const auto a = run();
    const auto b = run();
    // 终态逐位一致：胜负/回合/幸存/阵亡/奖励 + 全分区 rngStates 锁定
    EXPECT_EQ(a.first.victory, b.first.victory);
    EXPECT_EQ(a.first.battle.turn, b.first.battle.turn);
    EXPECT_EQ(a.first.battle.rounds.size(), b.first.battle.rounds.size());
    EXPECT_EQ(a.first.survivorIds, b.first.survivorIds);
    EXPECT_EQ(a.first.deadIds, b.first.deadIds);
    EXPECT_EQ(a.first.rewards, b.first.rewards);
    EXPECT_EQ(a.second, b.second);
}

TEST(ExplorationTxTest, AttackCaveLevelBaseValueBranchZeroEnemyGen) {
    auto st = attackState(weakBeastLevel());
    st.gameData.worldLevels[0].type = "CAVE";   // 洞府关 → 基础值妖兽（零抽取组装）
    st.gameData.worldLevels[0].beastMaxHp = 0;
    st.gameData.worldLevels[0].guardianName = "守洞人";
    RngManager rng;
    rng.initSystemSeed(42);
    const auto rngBefore = rngSnapshot(rng);
    gamecore::system::OverflowMailCollector mail;
    const auto r = exploration_tx::attackWorldLevelTx(st, rng, "lv1",
                                                      {"1", "2"}, 1.0, mail);
    ASSERT_TRUE(r.ok);
    EXPECT_TRUE(r.victory);
    EXPECT_EQ(2u, r.battle.beasts.size());   // count=2 → max(count,1) 只
    const auto after = rngSnapshot(rng);
    for (const auto& [partition, state] : rngBefore) {
        if (partition == static_cast<int32_t>(gamecore::rng::RngPartition::kBattle)) {
            EXPECT_NE(state, after.at(partition));
        } else {
            EXPECT_EQ(state, after.at(partition))
                << "非 BATTLE 分区 " << partition << " 被意外抽取";
        }
    }
}

// ── scoutSectTx ──────────────────────────────────────────────

TEST(ExplorationTxTest, ScoutRejectsMissingSectAndNoCombatants) {
    auto st = scoutState();
    RngManager rng;
    rng.initSystemSeed(42);
    gamecore::system::OverflowMailCollector mail;
    const auto r1 = exploration_tx::scoutSectTx(st, rng, "nope", {"1"}, 1.0, mail);
    EXPECT_FALSE(r1.ok);
    EXPECT_EQ("NOT_FOUND", r1.errorType);
    const auto r2 = exploration_tx::scoutSectTx(st, rng, "sect_ai", {}, 1.0, mail);
    EXPECT_FALSE(r2.ok);
    EXPECT_EQ("NO_COMBATANTS", r2.errorType);
}

TEST(ExplorationTxTest, ScoutSelectsAliveRealm79First8InOrder) {
    auto st = scoutState();
    RngManager rng;
    rng.initSystemSeed(42);
    gamecore::system::OverflowMailCollector mail;
    const auto r = exploration_tx::scoutSectTx(st, rng, "sect_ai", {"1", "2"},
                                               1.0, mail);
    ASSERT_TRUE(r.ok);
    // 10 名 AI：realm6 ×1 滤除 + 死亡 ×1 滤除 → 存活 realm7..9 共 8 → 全取
    ASSERT_EQ(8u, r.defenderViews.size());
    EXPECT_EQ("ai1", r.defenderViews[0].id);   // 保序（ai0 realm6 被滤）
    EXPECT_EQ("ai8", r.defenderViews[7].id);   // ai9 死亡被滤
    for (const auto& v : r.defenderViews) {
        EXPECT_FALSE(v.realmName.empty());
        EXPECT_GT(v.maxHp, 0);
        EXPECT_FALSE(v.portraitRes.empty());
    }
}

TEST(ExplorationTxTest, ScoutVictoryKeepsSectDetailsKotlinResidual) {
    auto st = scoutState();
    RngManager rng;
    rng.initSystemSeed(42);
    gamecore::system::OverflowMailCollector mail;
    const auto r = exploration_tx::scoutSectTx(st, rng, "sect_ai", {"1", "2"},
                                               1.0, mail);
    ASSERT_TRUE(r.ok);
    EXPECT_TRUE(r.victory);
    // AI 弟子不落库（Kotlin 侦察路径不持久化 AI 伤亡）
    EXPECT_TRUE(st.aiSectDisciples.at("sect_ai")[1].isAlive);
    // scoutInfo 留 Kotlin（applyScoutVictoryInfo 残差）——C++ 不写 sectDetails
    EXPECT_TRUE(st.gameData.sectDetails.empty());
}

// ── assignGarrisonTx / removeGarrisonTx（零 RNG 族）───────────

TEST(ExplorationTxTest, GarrisonAssignClearsAllSlotsAndWritesTarget) {
    auto st = garrisonState();
    // 弟子 1 已在 AI 宗门驻守槽 0 + 巡逻槽 0（跨域占用）
    st.gameData.worldMapSects[1].garrisonSlots[0].discipleId = "1";
    st.gameData.worldMapSects[1].garrisonSlots[0].discipleName = "弟子1";
    st.gameData.patrolSlots[0].discipleId = "1";
    st.gameData.patrolSlots[0].discipleName = "弟子1";
    // 玩家宗门槽 0 已有旧 occupant
    st.gameData.worldMapSects[0].garrisonSlots[0].discipleId = "2";
    st.gameData.worldMapSects[0].garrisonSlots[0].discipleName = "弟子2";
    // 双灵根 → 颜色档 #F39C12
    const auto row = *st.disciples.rowOf("1");
    st.disciples.spiritRootTypes[row] = "metal,wood";

    RngManager rng;
    rng.initSystemSeed(42);
    const auto rngBefore = rngSnapshot(rng);
    const auto r = exploration_tx::assignGarrisonTx(st, "sect_player", 0, "1");
    ASSERT_TRUE(r.ok);
    EXPECT_TRUE(r.written);
    EXPECT_EQ("2", r.oldOccupantId);
    // 目标槽写入（含灵根色/境界显示名/画像）
    const auto& slot = st.gameData.worldMapSects[0].garrisonSlots[0];
    EXPECT_EQ("1", slot.discipleId);
    EXPECT_EQ("弟子1", slot.discipleName);
    EXPECT_EQ("#F39C12", slot.discipleSpiritRootColor);
    EXPECT_EQ("p1", slot.portraitRes);
    EXPECT_FALSE(slot.discipleRealm.empty());
    // 全槽清理（与 Kotlin 同口径：garrison 仅清玩家宗门）：巡逻槽清空，
    // AI 宗门 garrison 不在清理面（实战中驻守占用只存在于玩家宗门）
    EXPECT_TRUE(st.gameData.patrolSlots[0].discipleId.empty());
    EXPECT_EQ("1", st.gameData.worldMapSects[1].garrisonSlots[0].discipleId);
    // 零 RNG：全分区快照差分
    EXPECT_TRUE(rngBefore == rngSnapshot(rng));
}

TEST(ExplorationTxTest, GarrisonAssignSkipsWhenAlreadyInSect) {
    auto st = garrisonState();
    st.gameData.worldMapSects[0].garrisonSlots.push_back(GarrisonSlot{});
    st.gameData.worldMapSects[0].garrisonSlots[1].index = 1;
    st.gameData.worldMapSects[0].garrisonSlots[1].discipleId = "1";
    const auto before = st.gameData;
    const auto r = exploration_tx::assignGarrisonTx(st, "sect_player", 0, "1");
    ASSERT_TRUE(r.ok);
    EXPECT_FALSE(r.written);   // 同宗已驻 → 静默跳过
    // 零写入：目标槽与已驻槽均维持原状
    EXPECT_EQ("1", st.gameData.worldMapSects[0].garrisonSlots[1].discipleId);
    EXPECT_TRUE(st.gameData.worldMapSects[0].garrisonSlots[0].discipleId.empty());
}

TEST(ExplorationTxTest, GarrisonAssignValidationAndSilentPaths) {
    auto st = garrisonState();
    // 弟子不存在 / 已死亡 → 校验失败（Kotlin 回退臂重执行 require）
    const auto r1 = exploration_tx::assignGarrisonTx(st, "sect_player", 0, "99");
    EXPECT_FALSE(r1.ok);
    EXPECT_EQ("DISCIPLE_INVALID", r1.errorType);
    st.disciples.isAlive[*st.disciples.rowOf("2")] = 0;
    const auto r2 = exploration_tx::assignGarrisonTx(st, "sect_player", 0, "2");
    EXPECT_FALSE(r2.ok);
    // 宗门不存在 → ok 但 written=false（Kotlin return@update 静默同义）
    const auto r3 = exploration_tx::assignGarrisonTx(st, "sect_nope", 0, "1");
    EXPECT_TRUE(r3.ok);
    EXPECT_FALSE(r3.written);
}

TEST(ExplorationTxTest, GarrisonRemoveCapturesOccupantAndClearsSlot) {
    auto st = garrisonState();
    st.gameData.worldMapSects[0].garrisonSlots[0].discipleId = "2";
    st.gameData.worldMapSects[0].garrisonSlots[0].discipleName = "弟子2";
    RngManager rng;
    rng.initSystemSeed(42);
    const auto rngBefore = rngSnapshot(rng);
    const auto r = exploration_tx::removeGarrisonTx(st, "sect_player", 0);
    EXPECT_EQ("2", r.currentDiscipleId);
    const auto& slot = st.gameData.worldMapSects[0].garrisonSlots[0];
    EXPECT_EQ(0, slot.index);
    EXPECT_TRUE(slot.discipleId.empty());
    EXPECT_TRUE(slot.discipleName.empty());
    EXPECT_EQ("#E0E0E0", slot.discipleSpiritRootColor);   // 默认色（Kotlin 同）
    // 零 RNG：全分区快照差分
    EXPECT_TRUE(rngBefore == rngSnapshot(rng));
    // 宗门不存在 → 空 occupant + 零写入
    const auto r2 = exploration_tx::removeGarrisonTx(st, "sect_nope", 0);
    EXPECT_TRUE(r2.currentDiscipleId.empty());
}

}  // namespace
