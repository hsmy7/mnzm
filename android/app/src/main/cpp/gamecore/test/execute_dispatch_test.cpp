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

// ── 库存动作（B3：1010 号语义 = 添加装备实例，JSON 形状=实例）──

TEST_F(GameCoreFixture, InvAddEquipmentInstanceShape) {
    // 1010 实例形状：setId/part/growth{level,exp,affix}/meta{rarity,...} 全面
    const auto r = exec(action::INV_ADD_EQUIPMENT_STACK,
                        {{"id", "eq-1"}, {"name", "裂天罡煞·战手"},
                         {"setId", "lietian"}, {"part", "HANDS"},
                         {"growth",
                          {{"level", 3}, {"exp", 40},
                           {"affix",
                            {{"mainStat", {{"stat", "ATTACK"}, {"value", 9.0}}},
                             {"subStats",
                              nlohmann::json::array({
                                  {{"stat", "HP"}, {"value", 40.0}}})},
                             {"subRolls", nlohmann::json::array({2})}}}}},
                         {"meta",
                          {{"rarity", 2}, {"minRealm", 7},
                           {"description", "套装战手"}, {"isLocked", false}}},
                         {"isEquipped", false}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("status"), "success");
    ASSERT_EQ(core_->state().equipmentInstances.size(), 1u);
    const auto& inst = core_->state().equipmentInstances[0];
    EXPECT_EQ(inst.id, "eq-1");
    EXPECT_EQ(inst.name, "裂天罡煞·战手");
    EXPECT_EQ(inst.setId, "lietian");
    EXPECT_EQ(inst.part, "HANDS");
    EXPECT_EQ(inst.growth.level, 3);
    EXPECT_EQ(inst.growth.exp, 40);
    EXPECT_EQ(inst.growth.affix.mainStat.stat, "ATTACK");
    EXPECT_DOUBLE_EQ(inst.growth.affix.mainStat.value, 9.0);
    ASSERT_EQ(inst.growth.affix.subStats.size(), 1u);
    EXPECT_EQ(inst.growth.affix.subRolls[0], 2);
    EXPECT_EQ(inst.meta.rarity, 2);
    EXPECT_EQ(inst.meta.minRealm, 7);
    // 实例轨无年度报告追踪（数量语义退役）
    EXPECT_TRUE(core_->state().gameData.annualEquipmentBySource.empty());
}

TEST_F(GameCoreFixture, InvAddEquipmentRejectsDuplicateId) {
    const nlohmann::json payload = {
        {"id", "eq-1"}, {"name", "裂天罡煞·战手"},
        {"meta", {{"rarity", 1}, {"minRealm", 9}}}, {"isEquipped", false}};
    ASSERT_EQ(exec(action::INV_ADD_EQUIPMENT_STACK, payload).at("status"),
              "success");
    const auto dup = exec(action::INV_ADD_EQUIPMENT_STACK, payload);
    ASSERT_EQ(dup.at("status"), "success");   // 信封 success，结果体 failure
    EXPECT_EQ(dup.at("data").at("status"), "failure");
    ASSERT_EQ(core_->state().equipmentInstances.size(), 1u);
}

TEST_F(GameCoreFixture, InvRemoveEquipment) {
    gamecore::state::EquipmentInstance item;
    item.id = "eq-1";
    item.name = "木剑";
    item.part = "HANDS";
    item.meta.rarity = 1;
    core_->state().equipmentInstances.push_back(item);
    // B3 实例轨：quantity 协议占位（1 件 = 1 条目整条移除）
    const auto r = exec(action::INV_REMOVE_EQUIPMENT, {{"id", "eq-1"}, {"quantity", 4}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_TRUE(r.at("data").at("removed").get<bool>());
    EXPECT_TRUE(core_->state().equipmentInstances.empty());
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

TEST_F(GameCoreFixture, DiscipleEstimateBreakthroughMonth) {
    const auto r = exec(action::DISCIPLE_ESTIMATE_BREAKTHROUGH_MONTH,
                        {{"remaining", 60.0}, {"rate", 10.0}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("value").get<int32_t>(), 2);
}

// ── 装备升级/分解事务（B3：1486/1487）──────────────────────────

TEST_F(GameCoreFixture, EquipUpgradeDeductsAndLevelsUp) {
    auto& st = core_->state();
    st.gameData.spiritStones = 100000;
    // 兽材两摞：(rarity 2, id b2) 1 件 + (rarity 1, id b1) 5 件
    // → (rarity,id) 升序扣：先 b1 后 b2
    gamecore::state::Material low;
    low.id = "b1";
    low.name = "凡兽骨";
    low.rarity = 1;
    low.quantity = 5;
    gamecore::state::Material high;
    high.id = "b2";
    high.name = "妖兽革";
    high.rarity = 2;
    high.quantity = 1;
    st.materials.push_back(low);
    st.materials.push_back(high);
    gamecore::state::EquipmentInstance inst;
    inst.id = "eq-1";
    inst.name = "裂天罡煞·战手";
    inst.part = "HANDS";
    inst.growth.affix.mainStat = gamecore::state::EquipStatValue{"ATTACK", 3.0};
    inst.growth.affix.subStats = {gamecore::state::EquipStatValue{"HP", 14.0}};
    inst.growth.affix.subRolls = {1};
    inst.meta.rarity = 1;
    st.equipmentInstances.push_back(inst);

    const int64_t equipRngBefore =
        core_->rng().getRng(gamecore::rng::RngPartition::kEquipment).snapshot();
    const auto r = exec(action::EQUIP_UPGRADE, {{"equipmentId", "eq-1"}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("upgraded"), true);
    EXPECT_EQ(r.at("data").at("newLevel"), 2);
    // 灵石 100 × 1² × 1 = 100；兽材 max(1, 1/10) = 1 件（rarity 1 摞先扣）
    EXPECT_EQ(st.gameData.spiritStones, 100000 - 100);
    ASSERT_EQ(st.materials.size(), 2u);
    EXPECT_EQ(st.materials[0].id, "b1");
    EXPECT_EQ(st.materials[0].quantity, 4);
    EXPECT_EQ(st.materials[1].quantity, 1);
    // 经验折算升级：expRequired(1, 1) = 100×1×1.0 = 100 ≥ 增益 → Lv2 exp 0
    EXPECT_EQ(st.equipmentInstances[0].growth.level, 2);
    EXPECT_EQ(st.equipmentInstances[0].growth.exp, 0);
    // Lv2 非 %3 节点 → 零强化抽取
    EXPECT_EQ(core_->rng().getRng(gamecore::rng::RngPartition::kEquipment).snapshot(),
              equipRngBefore);
}

TEST_F(GameCoreFixture, EquipUpgradeReinforceNodeConsumesEquipmentPartition) {
    // 升至 Lv3（%3 节点）→ 强化一次：kEquipment nextInt(subStats.size) 定位
    auto& st = core_->state();
    st.gameData.spiritStones = 100000;
    gamecore::state::Material m;
    m.id = "b1";
    m.name = "凡兽骨";
    m.rarity = 1;
    m.quantity = 99;
    st.materials.push_back(m);
    gamecore::state::EquipmentInstance inst;
    inst.id = "eq-1";
    inst.name = "裂天罡煞·战手";
    inst.growth.affix.mainStat = gamecore::state::EquipStatValue{"ATTACK", 3.0};
    inst.growth.affix.subStats = {
        gamecore::state::EquipStatValue{"HP", 14.0},
        gamecore::state::EquipStatValue{"CRIT_RATE", 0.002},
    };
    inst.growth.affix.subRolls = {1, 4};
    inst.meta.rarity = 1;
    st.equipmentInstances.push_back(inst);

    // 预演 kEquipment 分区同种子首抽（强化定位 = nextInt(2)）
    auto probe = gamecore::rng::DeterministicRng::fromSeed(42 + 13);
    const int32_t expectIdx = probe.nextInt(2);

    const auto r = exec(action::EQUIP_UPGRADE, {{"equipmentId", "eq-1"}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("newLevel"), 2);
    // 逐次升到 Lv3（每级消耗走同链路）
    ASSERT_EQ(exec(action::EQUIP_UPGRADE, {{"equipmentId", "eq-1"}}).at("status"),
              "success");
    EXPECT_EQ(st.equipmentInstances[0].growth.level, 3);
    // Lv3 节点强化：命中下标的 roll +1（第二抽 = 预演首抽）
    const int32_t hitIdx = expectIdx;
    EXPECT_EQ(st.equipmentInstances[0].growth.affix.subRolls[hitIdx],
              hitIdx == 0 ? 2 : 5);
    EXPECT_EQ(core_->rng().getRng(gamecore::rng::RngPartition::kEquipment).snapshot(),
              probe.snapshot());
}

TEST_F(GameCoreFixture, EquipUpgradeInsufficientMaterialFailsZeroWrite) {
    auto& st = core_->state();
    st.gameData.spiritStones = 50;   // < 100 × 1² × 1
    gamecore::state::EquipmentInstance inst;
    inst.id = "eq-1";
    inst.name = "木剑";
    inst.meta.rarity = 1;
    st.equipmentInstances.push_back(inst);
    const auto r = exec(action::EQUIP_UPGRADE, {{"equipmentId", "eq-1"}});
    ASSERT_EQ(r.at("status"), "failure");
    EXPECT_EQ(r.at("code"), "SlotInvalid");
    // 失败不扣材料
    EXPECT_EQ(st.gameData.spiritStones, 50);
    EXPECT_EQ(st.equipmentInstances[0].growth.level, 1);
}

TEST_F(GameCoreFixture, EquipDismantleRefundsHalfAndStripsBag) {
    auto& st = core_->state();
    st.gameData.spiritStones = 0;
    gamecore::state::EquipmentInstance inst;
    inst.id = "eq-1";
    inst.name = "裂天罡煞·战手";
    inst.meta.rarity = 1;
    inst.growth.level = 2;   // Lv2（已付 1 级消耗）
    st.equipmentInstances.push_back(inst);
    st.disciples.appendDisciple(gamecore::state::Disciple{});
    st.disciples.ids[0] = "1";
    gamecore::state::StorageBagItem bag;
    bag.itemId = "eq-1";
    bag.itemType = "equipment_instance";
    bag.quantity = 1;
    st.disciples.storageBagItems[0].push_back(bag);

    const auto r = exec(action::EQUIP_DISMANTLE, {{"equipmentId", "eq-1"}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("dismantled"), true);
    // 返还（累计 Lv1→2 消耗 × 50%）：灵石 100×0.5 = 50；
    // 兽材 1×0.5 = 0（floor/截断）→ 不铸条目
    EXPECT_EQ(st.gameData.spiritStones, 50);
    EXPECT_TRUE(st.materials.empty());
    // 实例离表 + 袋条目清除（防复活）
    EXPECT_TRUE(st.equipmentInstances.empty());
    EXPECT_TRUE(st.disciples.storageBagItems[0].empty());
}

TEST_F(GameCoreFixture, EquipDismantleRejectsLockedAndEquipped) {
    auto& st = core_->state();
    gamecore::state::EquipmentInstance locked;
    locked.id = "eq-1";
    locked.name = "木剑";
    locked.meta.rarity = 1;
    locked.meta.isLocked = true;
    gamecore::state::EquipmentInstance equipped;
    equipped.id = "eq-2";
    equipped.name = "铁剑";
    equipped.meta.rarity = 1;
    equipped.isEquipped = true;
    st.equipmentInstances.push_back(locked);
    st.equipmentInstances.push_back(equipped);

    const auto r1 = exec(action::EQUIP_DISMANTLE, {{"equipmentId", "eq-1"}});
    ASSERT_EQ(r1.at("status"), "failure");
    EXPECT_EQ(r1.at("code"), "SlotInvalid");
    const auto r2 = exec(action::EQUIP_DISMANTLE, {{"equipmentId", "eq-2"}});
    ASSERT_EQ(r2.at("status"), "failure");
    EXPECT_EQ(r2.at("code"), "SlotInvalid");
    const auto r3 = exec(action::EQUIP_DISMANTLE, {{"equipmentId", "ghost"}});
    ASSERT_EQ(r3.at("status"), "failure");
    EXPECT_EQ(r3.at("code"), "NotFound");
    // 零写入
    ASSERT_EQ(st.equipmentInstances.size(), 2u);
    EXPECT_EQ(st.gameData.spiritStones, 1000);
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

// ── 库存溢出邮件草稿回传（B3：装备实例轨无溢出面，丹药臂代守）──

TEST_F(GameCoreFixture, InvOverflowPartialEmitsDrafts) {
    // Partial 语义 = 发生合并 + 槽位全满（StackableItemStore 契约）。场景：
    // 49 填充 + 同键丹药×998 占满 50 槽（仓库基容量 50、无建筑加成），再入
    // 同键丹药×5 → 合并 1（998→999）、剩余 4 无处落 → partial + 溢出 4 + 草稿
    for (int i = 0; i < 49; ++i) {
        const auto r = exec(action::INV_ADD_PILL,
                            {{"id", "fill-" + std::to_string(i)},
                             {"name", "填充丹" + std::to_string(i)}, {"rarity", 1},
                             {"quantity", 1}, {"source", "battle"}});
        ASSERT_EQ(r.at("status"), "success");
    }
    const auto setup = exec(action::INV_ADD_PILL,
                            {{"id", "p-1"}, {"name", "聚气丹"}, {"rarity", 1},
                             {"quantity", 998}, {"source", "battle"}});
    ASSERT_EQ(setup.at("status"), "success");
    const auto r = exec(action::INV_ADD_PILL,
                        {{"id", "p-2"}, {"name", "聚气丹"}, {"rarity", 1},
                         {"quantity", 5}, {"source", "battle"}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("status"), "partial");
    EXPECT_EQ(r.at("data").at("overflow"), 4);
    ASSERT_EQ(r.at("data").at("overflowMails"), 1);
    const auto& drafts = r.at("data").at("overflowDrafts");
    ASSERT_EQ(drafts.size(), 1u);
    EXPECT_EQ(drafts[0].at("itemType"), "pill");
    EXPECT_EQ(drafts[0].at("itemName"), "聚气丹");
    EXPECT_EQ(drafts[0].at("rarity"), 1);
    EXPECT_EQ(drafts[0].at("quantity"), 4);
    EXPECT_EQ(drafts[0].at("source"), "battle");
}

TEST_F(GameCoreFixture, InvOverflowFullEmitsDrafts) {
    // 仓库 50 槽（无仓库建筑）：填满后新物品入仓失败 → 全量转草稿
    for (int i = 0; i < 50; ++i) {
        const auto r = exec(action::INV_ADD_PILL,
                            {{"id", "p-" + std::to_string(i)},
                             {"name", "填充丹" + std::to_string(i)}, {"rarity", 1},
                             {"quantity", 1}, {"source", "battle"}});
        ASSERT_EQ(r.at("status"), "success");
    }
    const auto r = exec(action::INV_ADD_PILL,
                        {{"id", "p-x"}, {"name", "溢出丹"}, {"rarity", 2},
                         {"quantity", 7}, {"source", "alchemy"}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("status"), "failure");
    ASSERT_EQ(r.at("data").at("overflowDrafts").size(), 1u);
    EXPECT_EQ(r.at("data").at("overflowDrafts")[0].at("quantity"), 7);
    EXPECT_EQ(r.at("data").at("overflowDrafts")[0].at("itemType"), "pill");
}

TEST_F(GameCoreFixture, InvRemoveSuccessHasNoDrafts) {
    exec(action::INV_ADD_EQUIPMENT_STACK,
         {{"id", "eq-1"}, {"name", "木剑"},
          {"meta", {{"rarity", 1}, {"minRealm", 9}}}, {"isEquipped", false}});
    const auto r = exec(action::INV_REMOVE_EQUIPMENT, {{"id", "eq-1"}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_TRUE(r.at("data").at("removed").get<bool>());
    EXPECT_FALSE(r.at("data").contains("overflowDrafts"));
}

// ── 仓库整理动作（consolidate/sort/toggleLock）────────

TEST_F(GameCoreFixture, InvConsolidateRespectsFullAndLock) {
    // merge=false 构造三同键丹药堆叠 [999(full), 100(locked), 50]：
    // 满堆叠跳过、锁定禁作来源、50 被 primary(=100, 锁定可作目标) 吸收 → [999, 150]
    auto add = [this](const std::string& id, int32_t qty) {
        return exec(action::INV_ADD_PILL,
                    {{"id", id}, {"name", "聚气丹"}, {"rarity", 1},
                     {"quantity", qty},
                     {"source", "battle"}, {"merge", false}});
    };
    ASSERT_EQ(add("p-1", 999).at("status"), "success");
    ASSERT_EQ(add("p-2", 100).at("status"), "success");
    ASSERT_EQ(exec(action::INV_TOGGLE_LOCK,
                   {{"itemId", "p-2"}, {"itemType", "pill"}})
                  .at("data").at("toggled").get<bool>(), true);
    ASSERT_EQ(add("p-3", 50).at("status"), "success");

    const auto r = exec(action::INV_CONSOLIDATE, nlohmann::json::object());
    ASSERT_EQ(r.at("status"), "success");
    auto& stacks = core_->state().pills;
    ASSERT_EQ(stacks.size(), 2u);
    EXPECT_EQ(stacks[0].id, "p-1");
    EXPECT_EQ(stacks[0].quantity, 999);
    EXPECT_EQ(stacks[1].id, "p-2");
    EXPECT_EQ(stacks[1].quantity, 150);
    EXPECT_TRUE(stacks[1].isLocked);  // 目标可锁定：吸收后锁语义不变
}

TEST_F(GameCoreFixture, InvSortEquipmentInstancesRarityDescNameAsc) {
    // B3：INV_SORT 装备臂 = sortEquipmentInstances（1010 实例入库 ×3）
    auto add = [this](const std::string& id, const std::string& name, int rarity) {
        return exec(action::INV_ADD_EQUIPMENT_STACK,
                    {{"id", id}, {"name", name},
                     {"meta", {{"rarity", rarity}, {"minRealm", 9}}},
                     {"isEquipped", false}});
    };
    ASSERT_EQ(add("a", "飞剑", 2).at("status"), "success");
    ASSERT_EQ(add("b", "木剑", 1).at("status"), "success");
    ASSERT_EQ(add("c", "青莲", 2).at("status"), "success");

    const auto r = exec(action::INV_SORT, nlohmann::json::object());
    ASSERT_EQ(r.at("status"), "success");
    auto& instances = core_->state().equipmentInstances;
    ASSERT_EQ(instances.size(), 3u);
    // rarity desc, name asc（rarity=2 组在前，组内按名称码点序；同键稳定序不交换）
    EXPECT_EQ(instances[0].name, "青莲");
    EXPECT_EQ(instances[1].name, "飞剑");
    EXPECT_EQ(instances[2].name, "木剑");
}

TEST_F(GameCoreFixture, InvToggleLockFlipAndUnknown) {
    exec(action::INV_ADD_EQUIPMENT_STACK,
         {{"id", "eq-1"}, {"name", "木剑"},
          {"meta", {{"rarity", 1}, {"minRealm", 9}}}, {"isEquipped", false}});
    EXPECT_EQ(exec(action::INV_TOGGLE_LOCK,
                   {{"itemId", "eq-1"}, {"itemType", "equipment"}})
                  .at("data").at("toggled").get<bool>(), true);
    // B3：装备锁 = 实例 meta.isLocked
    EXPECT_TRUE(core_->state().equipmentInstances[0].meta.isLocked);
    EXPECT_EQ(exec(action::INV_TOGGLE_LOCK,
                   {{"itemId", "eq-1"}, {"itemType", "equipment"}})
                  .at("data").at("toggled").get<bool>(), true);
    EXPECT_FALSE(core_->state().equipmentInstances[0].meta.isLocked);
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
