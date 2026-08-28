#include <gtest/gtest.h>

#include <nlohmann/json.hpp>

#include "gamecore/action_ids.h"
#include "gamecore/core/clock.h"
#include "gamecore/core/logger.h"
#include "gamecore/game_core.h"

namespace gamecore {
namespace {

class GameCoreFixture : public ::testing::Test {
protected:
    void SetUp() override {
        core_ = std::make_unique<GameCore>(&clock_, &logger_);
        GameCoreConfig config;
        config.seedInitialized = true;
        config.systemSeed = 42;
        core_->initialize(config);
    }

    nlohmann::json exec(int32_t actionId, const nlohmann::json& params) {
        const std::string result = core_->execute(
            actionId, params.dump(), 1000);
        return nlohmann::json::parse(result);
    }

    FixedClock clock_;
    ConsoleLogger logger_;
    std::unique_ptr<GameCore> core_;
};

// ── 钱包动作 ───────────────────────────────────────────────────

TEST_F(GameCoreFixture, WalletAdd) {
    auto& gd = core_->state().gameData;
    gd.spiritStones = 1000;
    const auto r = exec(action::WALLET_ADD, {{"amount", 500}, {"grade", "LOW"}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("balance").get<int64_t>(), 1500);
    EXPECT_EQ(gd.spiritStones, 1500);
}

TEST_F(GameCoreFixture, WalletDeductInsufficient) {
    auto& gd = core_->state().gameData;
    gd.spiritStones = 100;
    const auto r = exec(action::WALLET_DEDUCT,
                        {{"amount", 500}, {"grade", "LOW"}, {"autoConvert", false}});
    ASSERT_EQ(r.at("status"), "failure");
    EXPECT_EQ(r.at("code"), "INSUFFICIENT");
}

TEST_F(GameCoreFixture, WalletTotalSellValue) {
    auto& gd = core_->state().gameData;
    gd.spiritStones = 100;
    gd.midGradeSpiritStones = 2;
    const auto r = exec(action::WALLET_TOTAL_SELL_VALUE, nlohmann::json::object());
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("value").get<int64_t>(), 100 + 16000);
}

// ── 库存动作 ───────────────────────────────────────────────────

TEST_F(GameCoreFixture, InvAddEquipment) {
    const auto r = exec(action::INV_ADD_EQUIPMENT_STACK,
                        {{"id", "eq-1"}, {"name", "木剑"}, {"rarity", 1},
                         {"slot", "WEAPON"}, {"quantity", 5}, {"source", "battle"}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("status"), "success");
    ASSERT_EQ(core_->state().equipmentStacks.size(), 1u);
    EXPECT_EQ(core_->state().equipmentStacks[0].quantity, 5);
    // 年度报告追踪
    EXPECT_EQ(core_->state().gameData.annualEquipmentBySource["battle:1"], 5);
}

TEST_F(GameCoreFixture, InvRemoveEquipment) {
    gamecore::state::EquipmentStack item;
    item.id = "eq-1";
    item.name = "木剑";
    item.rarity = 1;
    item.slot = "WEAPON";
    item.quantity = 10;
    core_->state().equipmentStacks.push_back(item);
    const auto r = exec(action::INV_REMOVE_EQUIPMENT, {{"id", "eq-1"}, {"quantity", 4}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_TRUE(r.at("data").at("removed").get<bool>());
    EXPECT_EQ(core_->state().equipmentStacks[0].quantity, 6);
}

// ── 灵田收获 ───────────────────────────────────────────────────

TEST_F(GameCoreFixture, SpiritFieldHarvest) {
    auto& gd = core_->state().gameData;
    gd.gameYear = 2;
    gd.gameMonth = 3;
    gamecore::state::SpiritFieldPlant plant;
    plant.buildingInstanceId = "field-0";
    plant.seedId = "seed-0";
    plant.seedName = "聚灵草种";
    plant.growTime = 1;
    plant.expectedYield = 5;
    plant.plantYear = 1;
    plant.plantMonth = 1;
    gd.spiritFieldPlants.push_back(plant);
    const auto r = exec(action::SPIRIT_FIELD_HARVEST, {{"year", 2}, {"month", 3}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("plantsCompleted").get<int32_t>(), 1);
    ASSERT_FALSE(core_->state().herbs.empty());
    EXPECT_EQ(core_->state().herbs[0].name, "聚灵草");
}

// ── 弟子动作 ───────────────────────────────────────────────────

TEST_F(GameCoreFixture, DiscipleMaxAge) {
    const auto r = exec(action::DISCIPLE_MAX_AGE,
                        {{"lifespan", 100}, {"realmMaxAge", 80}, {"lifespanBonus", 0.5}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("value").get<int32_t>(), 120);
}

TEST_F(GameCoreFixture, DiscipleEstimateBreakthroughMonth) {
    const auto r = exec(action::DISCIPLE_ESTIMATE_BREAKTHROUGH_MONTH,
                        {{"remaining", 60.0}, {"rate", 10.0}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("value").get<int32_t>(), 2);
}

// ── 战斗动作 ───────────────────────────────────────────────────

TEST_F(GameCoreFixture, BattleFinalDamage) {
    const auto r = exec(action::BATTLE_FINAL_DAMAGE,
                        {{"rawAttack", 100}, {"defense", 500}, {"skillMultiplier", 1.0}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("value").get<int32_t>(), 50);
}

TEST_F(GameCoreFixture, BattleInstantKill) {
    const auto r = exec(action::BATTLE_CHECK_INSTANT_KILL,
                        {{"attackerRealm", 0}, {"defenderRealm", 9},
                         {"attackerLayer", 1}, {"defenderLayer", 1}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_TRUE(r.at("data").at("value").get<bool>());
}

// ── 内政动作 ───────────────────────────────────────────────────

TEST_F(GameCoreFixture, GovPolicyCosts) {
    auto& gd = core_->state().gameData;
    gd.spiritStones = 100000;
    gd.sectPolicies.alchemyIncentive = true;
    gd.sectPolicies.curfew = true;
    const auto r = exec(action::GOV_POLICY_COSTS,
                        {{"discipleCount", 5}, {"huashenBelowCount", 3}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_TRUE(r.at("data").at("allPaid").get<bool>());
    EXPECT_EQ(gd.spiritStones, 100000 - 3000 - 1000);
}

TEST_F(GameCoreFixture, GovSpiritMineMonthly) {
    // 初始 lastSettledMonth=0，当前绝对月 = 1×12+1 = 13 → 差分 13 个月
    // 3 矿工 × 170 × 13 = 6630
    const auto r = exec(action::GOV_SPIRIT_MINE_MONTHLY,
                        {{"minerCount", 3}, {"miningSkills", nlohmann::json::array()},
                         {"deaconMorality", nlohmann::json::array()},
                         {"spiritMineBoost", false}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("output").get<int64_t>(), 3 * 170 * 13);
    EXPECT_TRUE(r.at("data").at("settled").get<bool>());
}

// ── 探索动作 ───────────────────────────────────────────────────

TEST_F(GameCoreFixture, WorldLevelCheckExpired) {
    const auto r = exec(action::WORLD_LEVEL_CHECK_EXPIRED,
                        {{"year", 3}, {"month", 6},
                         {"level", {{"id", "b1"}, {"type", "BEAST"},
                                    {"defeated", false},
                                    {"expiryYear", 3}, {"expiryMonth", 6}}}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_TRUE(r.at("data").at("value").get<bool>());  // 含等号 → 过期
}

// ── 库存溢出邮件草稿回传（批 8-2：add 家族生产接线前置）────────

TEST_F(GameCoreFixture, InvOverflowPartialEmitsDrafts) {
    // 堆叠上限 999：先填满，再溢出 5 → partial + 草稿回传
    const auto full = exec(action::INV_ADD_EQUIPMENT_STACK,
                           {{"id", "eq-1"}, {"name", "木剑"}, {"rarity", 1},
                            {"slot", "WEAPON"}, {"quantity", 999}, {"source", "battle"}});
    ASSERT_EQ(full.at("status"), "success");
    const auto r = exec(action::INV_ADD_EQUIPMENT_STACK,
                        {{"id", "eq-2"}, {"name", "木剑"}, {"rarity", 1},
                         {"slot", "WEAPON"}, {"quantity", 5}, {"source", "battle"}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("status"), "partial");
    EXPECT_EQ(r.at("data").at("overflow"), 5);
    ASSERT_EQ(r.at("data").at("overflowMails"), 1);
    const auto& drafts = r.at("data").at("overflowDrafts");
    ASSERT_EQ(drafts.size(), 1u);
    EXPECT_EQ(drafts[0].at("itemType"), "equipment");
    EXPECT_EQ(drafts[0].at("itemName"), "木剑");
    EXPECT_EQ(drafts[0].at("rarity"), 1);
    EXPECT_EQ(drafts[0].at("quantity"), 5);
    EXPECT_EQ(drafts[0].at("source"), "battle");
    EXPECT_EQ(drafts[0].at("slot"), "WEAPON");
}

TEST_F(GameCoreFixture, InvOverflowFullEmitsDrafts) {
    // 仓库 50 槽（无仓库建筑）：填满后新物品入仓失败 → 全量转草稿
    for (int i = 0; i < 50; ++i) {
        const auto r = exec(action::INV_ADD_EQUIPMENT_STACK,
                            {{"id", "eq-" + std::to_string(i)},
                             {"name", "填充剑" + std::to_string(i)}, {"rarity", 1},
                             {"slot", "WEAPON"}, {"quantity", 1}, {"source", "battle"}});
        ASSERT_EQ(r.at("status"), "success");
    }
    const auto r = exec(action::INV_ADD_EQUIPMENT_STACK,
                        {{"id", "eq-x"}, {"name", "溢出丹炼制材料"}, {"rarity", 2},
                         {"slot", "ARMOR"}, {"quantity", 7}, {"source", "alchemy"}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("status"), "failure");
    ASSERT_EQ(r.at("data").at("overflowDrafts").size(), 1u);
    EXPECT_EQ(r.at("data").at("overflowDrafts")[0].at("quantity"), 7);
    EXPECT_EQ(r.at("data").at("overflowDrafts")[0].at("itemType"), "equipment");
}

TEST_F(GameCoreFixture, InvRemoveSuccessHasNoDrafts) {
    exec(action::INV_ADD_EQUIPMENT_STACK,
         {{"id", "eq-1"}, {"name", "木剑"}, {"rarity", 1},
          {"slot", "WEAPON"}, {"quantity", 5}, {"source", "battle"}});
    const auto r = exec(action::INV_REMOVE_EQUIPMENT, {{"id", "eq-1"}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_TRUE(r.at("data").at("removed").get<bool>());
    EXPECT_FALSE(r.at("data").contains("overflowDrafts"));
}

// ── 未实现动作 ─────────────────────────────────────────────────

TEST_F(GameCoreFixture, UnknownActionReturnsFailure) {
    const auto r = exec(999999, {});
    ASSERT_EQ(r.at("status"), "failure");
    EXPECT_EQ(r.at("code"), "NOT_IMPLEMENTED");
}

}  // namespace
}  // namespace gamecore
