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

// ── 库存溢出邮件草稿回传（add 家族生产接线前置）────────

TEST_F(GameCoreFixture, InvOverflowPartialEmitsDrafts) {
    // Partial 语义 = 发生合并 + 槽位全满（StackableItemStore 契约：同键堆叠
    // 已满且有空槽时走分块创建 → Success 非溢出）。场景：49 填充 + 木剑×998
    // 占满 50 槽（仓库基容量 50、无建筑加成），再入木剑×5 → 合并 1（998→999）、
    // 剩余 4 槽满无处落 → partial + 溢出 4 + 草稿回传
    for (int i = 0; i < 49; ++i) {
        const auto r = exec(action::INV_ADD_EQUIPMENT_STACK,
                            {{"id", "fill-" + std::to_string(i)},
                             {"name", "填充剑" + std::to_string(i)}, {"rarity", 1},
                             {"slot", "ARMOR"}, {"quantity", 1}, {"source", "battle"}});
        ASSERT_EQ(r.at("status"), "success");
    }
    const auto setup = exec(action::INV_ADD_EQUIPMENT_STACK,
                            {{"id", "eq-1"}, {"name", "木剑"}, {"rarity", 1},
                             {"slot", "WEAPON"}, {"quantity", 998}, {"source", "battle"}});
    ASSERT_EQ(setup.at("status"), "success");
    const auto r = exec(action::INV_ADD_EQUIPMENT_STACK,
                        {{"id", "eq-2"}, {"name", "木剑"}, {"rarity", 1},
                         {"slot", "WEAPON"}, {"quantity", 5}, {"source", "battle"}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("status"), "partial");
    EXPECT_EQ(r.at("data").at("overflow"), 4);
    ASSERT_EQ(r.at("data").at("overflowMails"), 1);
    const auto& drafts = r.at("data").at("overflowDrafts");
    ASSERT_EQ(drafts.size(), 1u);
    EXPECT_EQ(drafts[0].at("itemType"), "equipment");
    EXPECT_EQ(drafts[0].at("itemName"), "木剑");
    EXPECT_EQ(drafts[0].at("rarity"), 1);
    EXPECT_EQ(drafts[0].at("quantity"), 4);
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

// ── 仓库整理动作（consolidate/sort/toggleLock）────────

TEST_F(GameCoreFixture, InvConsolidateRespectsFullAndLock) {
    // merge=false 构造三同键堆叠 [999(full), 100(locked), 50]：
    // 满堆叠跳过、锁定禁作来源、50 被 primary(=100, 锁定可作目标) 吸收 → [999, 150]
    auto add = [this](const std::string& id, int32_t qty) {
        return exec(action::INV_ADD_EQUIPMENT_STACK,
                    {{"id", id}, {"name", "木剑"}, {"rarity", 1},
                     {"slot", "WEAPON"}, {"quantity", qty},
                     {"source", "battle"}, {"merge", false}});
    };
    ASSERT_EQ(add("eq-1", 999).at("status"), "success");
    ASSERT_EQ(add("eq-2", 100).at("status"), "success");
    ASSERT_EQ(exec(action::INV_TOGGLE_LOCK,
                   {{"itemId", "eq-2"}, {"itemType", "equipment"}})
                  .at("data").at("toggled").get<bool>(), true);
    ASSERT_EQ(add("eq-3", 50).at("status"), "success");

    const auto r = exec(action::INV_CONSOLIDATE, nlohmann::json::object());
    ASSERT_EQ(r.at("status"), "success");
    auto& stacks = core_->state().equipmentStacks;
    ASSERT_EQ(stacks.size(), 2u);
    EXPECT_EQ(stacks[0].id, "eq-1");
    EXPECT_EQ(stacks[0].quantity, 999);
    EXPECT_EQ(stacks[1].id, "eq-2");
    EXPECT_EQ(stacks[1].quantity, 150);
    EXPECT_TRUE(stacks[1].isLocked);  // 目标可锁定：吸收后锁语义不变
}

TEST_F(GameCoreFixture, InvSortRarityDescNameAsc) {
    auto add = [this](const std::string& id, const std::string& name, int rarity) {
        return exec(action::INV_ADD_EQUIPMENT_STACK,
                    {{"id", id}, {"name", name}, {"rarity", rarity},
                     {"slot", "WEAPON"}, {"quantity", 1},
                     {"source", "battle"}, {"merge", false}});
    };
    ASSERT_EQ(add("a", "飞剑", 2).at("status"), "success");
    ASSERT_EQ(add("b", "木剑", 1).at("status"), "success");
    ASSERT_EQ(add("c", "青莲", 2).at("status"), "success");

    const auto r = exec(action::INV_SORT, nlohmann::json::object());
    ASSERT_EQ(r.at("status"), "success");
    auto& stacks = core_->state().equipmentStacks;
    ASSERT_EQ(stacks.size(), 3u);
    // rarity desc, name asc（rarity=2 组在前，组内按名称码点序；同键稳定序不交换）
    EXPECT_EQ(stacks[0].name, "青莲");
    EXPECT_EQ(stacks[1].name, "飞剑");
    EXPECT_EQ(stacks[2].name, "木剑");
}

TEST_F(GameCoreFixture, InvToggleLockFlipAndUnknown) {
    exec(action::INV_ADD_EQUIPMENT_STACK,
         {{"id", "eq-1"}, {"name", "木剑"}, {"rarity", 1},
          {"slot", "WEAPON"}, {"quantity", 5}, {"source", "battle"}});
    EXPECT_EQ(exec(action::INV_TOGGLE_LOCK,
                   {{"itemId", "eq-1"}, {"itemType", "equipment"}})
                  .at("data").at("toggled").get<bool>(), true);
    EXPECT_TRUE(core_->state().equipmentStacks[0].isLocked);
    EXPECT_EQ(exec(action::INV_TOGGLE_LOCK,
                   {{"itemId", "eq-1"}, {"itemType", "equipment"}})
                  .at("data").at("toggled").get<bool>(), true);
    EXPECT_FALSE(core_->state().equipmentStacks[0].isLocked);
    // 未知 id / 未知类型 → toggled=false
    EXPECT_FALSE(exec(action::INV_TOGGLE_LOCK,
                      {{"itemId", "nope"}, {"itemType", "equipment"}})
                     .at("data").at("toggled").get<bool>());
    EXPECT_FALSE(exec(action::INV_TOGGLE_LOCK,
                      {{"itemId", "eq-1"}, {"itemType", "junk"}})
                     .at("data").at("toggled").get<bool>());
}

// ── 未实现动作 ─────────────────────────────────────────────────

TEST_F(GameCoreFixture, UnknownActionReturnsFailure) {
    const auto r = exec(999999, {});
    ASSERT_EQ(r.at("status"), "failure");
    EXPECT_EQ(r.at("code"), "NOT_IMPLEMENTED");
}

}  // namespace
}  // namespace gamecore
